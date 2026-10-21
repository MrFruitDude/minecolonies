package com.minecolonies.core.gametest;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.pathfinding.IStuckHandler;
import com.minecolonies.api.pathfinding.IRouteHintProvider;
import com.minecolonies.api.pathfinding.RouteHints;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;
import com.minecolonies.core.entity.pathfinding.Pathfinding;
import com.minecolonies.core.entity.pathfinding.navigation.MinecoloniesAdvancedPathNavigate;
import com.minecolonies.core.entity.pathfinding.pathjobs.AbstractPathJob;
import com.minecolonies.core.entity.pathfinding.pathjobs.PathJobMoveToLocation;
import com.minecolonies.core.entity.pathfinding.pathresults.PathResult;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.minecolonies.core.gametest.MinecoloniesGameTests.foundGameTestColony;
import static com.minecolonies.core.gametest.MinecoloniesGameTests.makeConnectedSurvivalPlayer;

/**
 * CA-10 (pathfinding worker pool + queue rejection) and CA-11 (unreachable-target back-off).
 * <p>
 * Cost metrics: path jobs submitted per 2,000 ticks for an unreachable target (CA-11), and searches running at once /
 * wall time for 300 queued move-to jobs (CA-10). Correctness: four fixture routes must produce the same node list as
 * before the change, the back-off must not block another target or a target that became reachable, and a real citizen
 * must still walk home.
 * <p>
 * The navigator tests drive a {@link MinecoloniesAdvancedPathNavigate} on a no-AI villager "host": the test ticks the
 * navigator, issues the move request every 5 ticks the way {@code EntityNavigationUtils.walkToPos} does, and when a path
 * arrives it moves the host to the path's end at once (the walk itself is not under test). The stuck handler is a no-op
 * so it cannot teleport the host. Emulated time does not advance while the host's search runs (see {@link #drive}).
 */
public final class PathfindingGameTests
{
    /** Plot floor area (relative), inside the colony plot structure. */
    private static final int MIN_X = -8, MAX_X = 59, MIN_Z = -8, MAX_Z = 39;

    /** The AI's move-request cadence (citizen AI ticks every 5 ticks). */
    private static final int REQUEST_EVERY = 5;

    /** CA-11 measured window and bar. */
    private static final int  BACKOFF_WINDOW   = 2000;
    private static final int  MAX_BACKOFF_JOBS = 4;

    /** The unreachable target (relative), sealed in a stone shell, and where the host starts. */
    private static final BlockPos SEALED_TARGET = new BlockPos(36, 1, 20);
    private static final BlockPos HOST_START    = new BlockPos(4, 1, 20);

    /** CA-10: queued jobs and the minimum number of searches that must run at once. */
    private static final int QUEUED_JOBS      = 300;
    private static final int MIN_PEAK_RUNNING = 2;

    /**
     * Route digests (relative node list, sha-256 prefix) and node counts captured on port/26.3 @ 1495a510f8 before the
     * change (capture3.log and capture4.log agree). A route that comes out different fails.
     */
    private static final Map<String, String> EXPECTED_ROUTES = new LinkedHashMap<>();

    static
    {
        EXPECTED_ROUTES.put("wall_gap", "41:f4547b373eb19805");
        EXPECTED_ROUTES.put("stairs_up", "10:1b7e98decedcf819");
        EXPECTED_ROUTES.put("ditch", "11:f95b3c8ff9334f69");
        EXPECTED_ROUTES.put("pillars", "40:76308a5fc026bd2f");
    }

    private PathfindingGameTests()
    {
    }

    // ------------------------------------------------------------------ fixture helpers

    private static void pinDay(final ServerLevel level)
    {
        level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
    }

    /**
     * Flat stone floor at y 0, air y 1..7, a 3-high stone border so no search leaves the floor (the world outside the
     * plot differs with where the plot lands), chunks forced, clock pinned to 6000.
     */
    private static void prepareFloor(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        pinDay(level);
        final BlockPos a = helper.absolutePos(new BlockPos(MIN_X, 0, MIN_Z));
        final BlockPos b = helper.absolutePos(new BlockPos(MAX_X, 0, MAX_Z));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++)
        {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++)
            {
                level.setChunkForced(cx, cz, true);
            }
        }
        for (int x = MIN_X; x <= MAX_X; x++)
        {
            for (int z = MIN_Z; z <= MAX_Z; z++)
            {
                helper.setBlock(new BlockPos(x, -1, z), Blocks.STONE.defaultBlockState());
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
                final boolean border = x == MIN_X || x == MAX_X || z == MIN_Z || z == MAX_Z;
                for (int y = 1; y <= 7; y++)
                {
                    helper.setBlock(new BlockPos(x, y, z), border && y <= 3 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /** Keeps the clock pinned every tick until the test ends (fresh lambda each tick: the scheduler keys by Runnable). */
    private static void keepClock(final GameTestHelper helper, final long time)
    {
        helper.getLevel().getServer().clockManager().setTotalTicks(helper.getLevel().registryAccess().getOrThrow(WorldClocks.OVERWORLD), time);
        helper.runAfterDelay(1, () -> keepClock(helper, time));
    }

    private static void fill(final GameTestHelper helper, final BlockPos from, final BlockPos to, final BlockState state)
    {
        for (int x = Math.min(from.getX(), to.getX()); x <= Math.max(from.getX(), to.getX()); x++)
        {
            for (int y = Math.min(from.getY(), to.getY()); y <= Math.max(from.getY(), to.getY()); y++)
            {
                for (int z = Math.min(from.getZ(), to.getZ()); z <= Math.max(from.getZ(), to.getZ()); z++)
                {
                    helper.setBlock(new BlockPos(x, y, z), state);
                }
            }
        }
    }

    /** A 1-thick stone shell (3 wide, 4 high incl. roof) around a 1x2 air pocket at {@code target}. */
    private static void sealTarget(final GameTestHelper helper, final BlockPos target)
    {
        fill(helper, target.offset(-1, 0, -1), target.offset(1, 2, 1), Blocks.STONE.defaultBlockState());
        helper.setBlock(target, Blocks.AIR.defaultBlockState());
        helper.setBlock(target.above(), Blocks.AIR.defaultBlockState());
    }

    private static final class NoStuck implements IStuckHandler<MinecoloniesAdvancedPathNavigate>
    {
        @Override
        public void checkStuck(final MinecoloniesAdvancedPathNavigate navigator)
        {
        }

        @Override
        public void resetGlobalStuckTimers()
        {
        }

        @Override
        public int getStuckLevel()
        {
            return 0;
        }
    }

    /** A no-AI villager carrying a MineColonies navigator the test drives. */
    private static final class Host
    {
        final Mob                              mob;
        final MinecoloniesAdvancedPathNavigate nav;
        final List<AbstractPathJob>            jobs = new ArrayList<>();

        Host(final GameTestHelper helper, final BlockPos rel)
        {
            mob = helper.spawn(EntityTypes.VILLAGER, rel);
            mob.setNoAi(true);
            final BlockPos abs = helper.absolutePos(rel);
            mob.setPos(abs.getX() + 0.5D, abs.getY(), abs.getZ() + 0.5D);
            nav = new MinecoloniesAdvancedPathNavigate(mob, helper.getLevel());
            nav.setStuckHandler(new NoStuck());
        }

        /** The AI's move request: re-issue when the navigation is done or on another task (EntityNavigationUtils.walkToPos). */
        @SuppressWarnings("deprecation")
        void request(final BlockPos dest)
        {
            final PathResult<?> r = nav.getPathResult();
            final boolean right = r != null && PathJobMoveToLocation.isJobFor(r.getJob(), dest);
            if (nav.isDone() || !right)
            {
                nav.walkTo(dest, 1.0D);
            }
        }

        /** One tick: navigator tick, then "walk" a fresh path instantly. */
        void step()
        {
            nav.tick();
            final Path path = nav.getPath();
            if (path != null && !path.isDone())
            {
                final Node end = path.getEndNode();
                if (end != null)
                {
                    mob.setPos(end.x + 0.5D, end.y, end.z + 0.5D);
                }
                path.setNextNodeIndex(path.getNodeCount());
            }
        }

        boolean anyReached(final BlockPos dest)
        {
            for (final AbstractPathJob job : jobs)
            {
                if (PathJobMoveToLocation.isJobFor(job, dest) && job.getResult().isPathReachingDestination())
                {
                    return true;
                }
            }
            return false;
        }

        int jobsFor(final BlockPos dest)
        {
            int n = 0;
            for (final AbstractPathJob job : jobs)
            {
                if (PathJobMoveToLocation.isJobFor(job, dest))
                {
                    n++;
                }
            }
            return n;
        }

        long nodes()
        {
            long n = 0;
            for (final AbstractPathJob job : jobs)
            {
                n += job.getResult().searchedNodes;
            }
            return n;
        }
    }

    /**
     * Waits (wall time, the GameTest server runs unthrottled) until the shared pathfinding pool has nothing queued or
     * running, so an earlier batch's leftover jobs cannot delay this test's, and until every floor chunk is
     * entity-ticking: a path job only sees entity-ticking chunks, and forced chunks get there a few ticks late.
     */
    private static void awaitIdlePool(final GameTestHelper helper, final long t0, final Runnable then)
    {
        if (Pathfinding.getExecutor().getQueue().isEmpty() && Pathfinding.Stats.running.get() == 0 && floorChunksReady(helper))
        {
            then.run();
            return;
        }
        if (System.nanoTime() - t0 > 120_000_000_000L)
        {
            helper.fail("fixture: the pathfinding pool was still busy after 120 s");
            return;
        }
        helper.runAfterDelay(1, () -> awaitIdlePool(helper, t0, then));
    }

    private static boolean floorChunksReady(final GameTestHelper helper)
    {
        final BlockPos a = helper.absolutePos(new BlockPos(MIN_X, 0, MIN_Z));
        final BlockPos b = helper.absolutePos(new BlockPos(MAX_X, 0, MAX_Z));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++)
        {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++)
            {
                if (!WorldUtil.isEntityChunkLoaded(helper.getLevel(), cx, cz))
                {
                    return false;
                }
            }
        }
        return true;
    }

    /** Installs the submit observer that records every job of {@code host}. */
    private static void observe(final Host host)
    {
        Pathfinding.submitObserver = job -> {
            if (job.getEntity() == host.mob)
            {
                host.jobs.add(job);
            }
        };
    }

    /**
     * Runs {@code ticks} host ticks, requesting {@code dest.get()} every 5 ticks, then {@code then}. Each tick's work is a
     * fresh lambda.
     */
    private static void drive(final GameTestHelper helper, final Host host, final java.util.function.Supplier<BlockPos> dest, final int tick, final int ticks,
      final java.util.function.IntPredicate stopEarly, final Runnable then)
    {
        if (tick >= ticks || stopEarly.test(tick))
        {
            then.run();
            return;
        }
        // Emulated time stands still while the host's search runs: the GameTest server is unthrottled, so a search
        // spans a varying number of real ticks. Here a search takes no time, so the counts depend only on the logic.
        final PathResult<?> computing = host.nav.getPathResult();
        if (computing != null && !computing.isDone())
        {
            helper.runAfterDelay(1, () -> drive(helper, host, dest, tick, ticks, stopEarly, then));
            return;
        }
        host.step();
        if (tick % REQUEST_EVERY == 0)
        {
            host.request(dest.get());
        }
        helper.runAfterDelay(1, () -> drive(helper, host, dest, tick + 1, ticks, stopEarly, then));
    }

    private static String summary(final String tag, final Host host)
    {
        final StringBuilder sb = new StringBuilder("CA11 ").append(tag).append(": ").append(host.jobs.size()).append(" path jobs, ")
          .append(host.nodes()).append(" nodes searched; jobs [");
        for (final AbstractPathJob job : host.jobs)
        {
            final PathResult<?> r = job.getResult();
            sb.append(r.searchedNodes).append(r.isPathReachingDestination() ? "R" : "F").append(' ');
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------ CA-11

    /**
     * path_unreachable_backoff: the host asks for a target sealed in stone every 5 ticks for 2,000 ticks. Today each
     * failure pauses only 10, 20, 30 ... ticks (+50 on the node cap), so ~20 full searches run; the bar is at most 4.
     */
    public static void unreachableTargetBackoff(final GameTestHelper helper)
    {
        prepareFloor(helper);
        keepClock(helper, 6000L);
        sealTarget(helper, SEALED_TARGET);
        final Host host = new Host(helper, HOST_START);
        final BlockPos target = helper.absolutePos(SEALED_TARGET);
        observe(host);
        helper.runAfterDelay(2, () -> awaitIdlePool(helper, System.nanoTime(), () -> drive(helper, host, () -> target, 0, BACKOFF_WINDOW, t -> false, () -> {
            Pathfinding.submitObserver = null;
            final String s = summary("unreachable", host);
            Log.getLogger().info(s);
            helper.assertTrue(!host.jobs.isEmpty(), "fixture: no path job was submitted. " + s);
            helper.assertTrue(!host.anyReached(target), "fixture: the sealed target was reached. " + s);
            helper.assertTrue(host.jobs.size() <= MAX_BACKOFF_JOBS,
              "unreachable target: " + host.jobs.size() + " path jobs in " + BACKOFF_WINDOW + " ticks (bar " + MAX_BACKOFF_JOBS + "). " + s);
            helper.succeed();
        })));
    }

    /**
     * path_unreachable_other_target: after 300 ticks of failing on the sealed target, the host is sent to a reachable
     * spot. The back-off is per target, so that path must be searched and reach within 100 ticks.
     */
    public static void unreachableTargetOtherTarget(final GameTestHelper helper)
    {
        prepareFloor(helper);
        keepClock(helper, 6000L);
        sealTarget(helper, SEALED_TARGET);
        final Host host = new Host(helper, HOST_START);
        final BlockPos target = helper.absolutePos(SEALED_TARGET);
        final BlockPos other = helper.absolutePos(new BlockPos(10, 1, 34));
        observe(host);
        helper.runAfterDelay(2, () -> awaitIdlePool(helper, System.nanoTime(), () -> drive(helper, host, () -> target, 0, 300, t -> false, () ->
          drive(helper, host, () -> other, 0, 100, t -> host.anyReached(other), () -> {
              Pathfinding.submitObserver = null;
              final String s = summary("other target", host);
              Log.getLogger().info(s);
              helper.assertTrue(host.jobsFor(target) >= 2, "fixture: fewer than 2 jobs for the sealed target. " + s);
              helper.assertTrue(host.anyReached(other), "a reachable second target was not reached within 100 ticks while the first one backed off. " + s);
              helper.succeed();
          }))));
    }

    /**
     * path_unreachable_recovers: after 300 ticks of failing, the shell is opened. The host keeps asking; a path must
     * reach the target within 1,400 ticks (the longest back-off is 1,200).
     */
    public static void unreachableTargetRecovers(final GameTestHelper helper)
    {
        prepareFloor(helper);
        keepClock(helper, 6000L);
        sealTarget(helper, SEALED_TARGET);
        final Host host = new Host(helper, HOST_START);
        final BlockPos target = helper.absolutePos(SEALED_TARGET);
        observe(host);
        helper.runAfterDelay(2, () -> awaitIdlePool(helper, System.nanoTime(), () -> drive(helper, host, () -> target, 0, 300, t -> false, () -> {
            final int failedBefore = host.jobs.size();
            // Doorway on the side facing the host.
            helper.setBlock(SEALED_TARGET.west(), Blocks.AIR.defaultBlockState());
            helper.setBlock(SEALED_TARGET.west().above(), Blocks.AIR.defaultBlockState());
            final long opened = helper.getLevel().getGameTime();
            drive(helper, host, () -> target, 0, 1400, t -> host.anyReached(target), () -> {
                Pathfinding.submitObserver = null;
                final String s = summary("recovers", host) + " opened after " + failedBefore + " jobs; waited "
                                   + (helper.getLevel().getGameTime() - opened) + " ticks";
                Log.getLogger().info(s);
                helper.assertTrue(failedBefore >= 2, "fixture: fewer than 2 failed jobs before opening. " + s);
                helper.assertTrue(host.anyReached(target), "the target was not reached within 1400 ticks of becoming reachable. " + s);
                helper.succeed();
            });
        })));
    }

    // ------------------------------------------------------------------ CA-10

    /**
     * path_queue_reject_marks_failed: a full pathfinding queue must not throw on the server thread. A 1-worker executor
     * with a 1-slot queue is filled, then a move-to job is submitted: today submit throws RejectedExecutionException
     * (the default AbortPolicy); it must instead come back as a finished, failed result.
     */
    public static void pathQueueRejectMarksFailed(final GameTestHelper helper)
    {
        prepareFloor(helper);
        final ThreadPoolExecutor executor = Pathfinding.createExecutor(1, new ArrayBlockingQueue<>(1));
        final CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            try
            {
                release.await(30, TimeUnit.SECONDS);
            }
            catch (final InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        });
        executor.execute(() -> {});
        final PathJobMoveToLocation job =
          new PathJobMoveToLocation(helper.getLevel(), helper.absolutePos(new BlockPos(0, 1, 0)), helper.absolutePos(new BlockPos(10, 1, 0)), 32, null);
        final PathResult<?> result = job.getResult();
        final long rejectedBefore = Pathfinding.Stats.rejected.get();
        String thrown = null;
        try
        {
            result.startJob(executor);
        }
        catch (final RuntimeException e)
        {
            // Today: AbortPolicy builds its message from the job's toString, which itself throws an NPE before the
            // job ran (no best node yet), so the server thread gets an NPE, not even a RejectedExecutionException.
            thrown = e.toString();
        }
        finally
        {
            release.countDown();
            executor.shutdownNow();
        }
        final String s = String.format("CA10 reject: threw=%s done=%s failed=%s hasPath=%s rejected+%d", thrown, result.isDone(), result.failedToReachDestination(),
          result.hasPath(), Pathfinding.Stats.rejected.get() - rejectedBefore);
        Log.getLogger().info(s);
        helper.assertTrue(thrown == null, "submitting to a full pathfinding queue threw on the server thread. " + s);
        helper.assertTrue(result.isDone() && result.failedToReachDestination() && !result.hasPath(), "a rejected job is not a finished, failed result. " + s);
        helper.succeed();
    }

    /** Builds the obstacle course for the queue test: three walls with one gap each. */
    private static void queueCourse(final GameTestHelper helper)
    {
        fill(helper, new BlockPos(10, 1, MIN_Z), new BlockPos(10, 2, MAX_Z), Blocks.STONE.defaultBlockState());
        fill(helper, new BlockPos(10, 1, 30), new BlockPos(10, 2, 31), Blocks.AIR.defaultBlockState());
        fill(helper, new BlockPos(30, 1, MIN_Z), new BlockPos(30, 2, MAX_Z), Blocks.STONE.defaultBlockState());
        fill(helper, new BlockPos(30, 1, -4), new BlockPos(30, 2, -3), Blocks.AIR.defaultBlockState());
        fill(helper, new BlockPos(45, 1, MIN_Z), new BlockPos(45, 2, MAX_Z), Blocks.STONE.defaultBlockState());
        fill(helper, new BlockPos(45, 1, 20), new BlockPos(45, 2, 21), Blocks.AIR.defaultBlockState());
    }

    /**
     * path_queue_parallel: 300 move-to jobs (≈64 blocks, three walls) are queued at once. Today one worker runs them one
     * after another (peak 1 search at a time); at least 2 must run at once. All must reach. Wall time is logged.
     */
    public static void pathQueueParallel(final GameTestHelper helper)
    {
        prepareFloor(helper);
        keepClock(helper, 6000L);
        queueCourse(helper);
        final BlockPos start = helper.absolutePos(new BlockPos(-6, 1, 10));
        final BlockPos end = helper.absolutePos(new BlockPos(57, 1, 12));
        helper.runAfterDelay(2, () -> awaitIdlePool(helper, System.nanoTime(), () -> {
            Pathfinding.Stats.reset();
            final List<PathResult<?>> results = new ArrayList<>();
            final long t0 = System.nanoTime();
            for (int i = 0; i < QUEUED_JOBS; i++)
            {
                final PathJobMoveToLocation job = new PathJobMoveToLocation(helper.getLevel(), start, end, 48, null);
                results.add(job.getResult());
                Pathfinding.enqueue(job);
            }
            final long submitNs = System.nanoTime() - t0;
            waitAll(helper, results, t0, 0, () -> {
                final long wallMs = (System.nanoTime() - t0) / 1_000_000L;
                int reached = 0;
                long nodes = 0;
                for (final PathResult<?> r : results)
                {
                    reached += r.isPathReachingDestination() ? 1 : 0;
                    nodes += r.searchedNodes;
                }
                final String s = String.format("CA10 queue: %d jobs, %d reached, %d nodes, submit %.1f ms, all done after %d ms, peak running %d, workers %d",
                  QUEUED_JOBS, reached, nodes, submitNs / 1e6, wallMs, Pathfinding.Stats.peakRunning.get(), Pathfinding.getExecutor().getMaximumPoolSize());
                Log.getLogger().info(s);
                helper.assertTrue(reached == QUEUED_JOBS, "not every queued job reached. " + s);
                helper.assertTrue(Pathfinding.Stats.peakRunning.get() >= MIN_PEAK_RUNNING,
                  "only " + Pathfinding.Stats.peakRunning.get() + " search ran at once for " + QUEUED_JOBS + " queued jobs (bar " + MIN_PEAK_RUNNING + "). " + s);
                helper.succeed();
            });
        }));
    }

    private static void waitAll(final GameTestHelper helper, final List<PathResult<?>> results, final long t0, final int tick, final Runnable then)
    {
        boolean all = true;
        for (final PathResult<?> r : results)
        {
            if (!r.isDone())
            {
                all = false;
            }
        }
        if (all)
        {
            then.run();
            return;
        }
        // The GameTest server runs unthrottled (thousands of ticks per second), so the bound is wall time.
        if (System.nanoTime() - t0 > 120_000_000_000L)
        {
            helper.fail("queued path jobs not done after 120 s (" + tick + " ticks)");
            return;
        }
        helper.runAfterDelay(1, () -> waitAll(helper, results, t0, tick + 1, then));
    }

    /** Four routes over one course: a wall with a gap, stairs onto a platform, a 1-deep ditch, a pillar field. */
    private static Map<String, BlockPos[]> routeCourse(final GameTestHelper helper)
    {
        final BlockState stone = Blocks.STONE.defaultBlockState();
        // wall_gap: wall at x = 5, z -8..6, gap from z = 7.
        fill(helper, new BlockPos(5, 1, MIN_Z), new BlockPos(5, 2, 6), stone);
        // stairs_up: columns of height 1, 2, 3 at x = 26, 27, then a platform x 28..32 of height 3, z 3..7.
        fill(helper, new BlockPos(26, 1, 3), new BlockPos(26, 1, 7), stone);
        fill(helper, new BlockPos(27, 1, 3), new BlockPos(27, 2, 7), stone);
        fill(helper, new BlockPos(28, 1, 3), new BlockPos(32, 3, 7), stone);
        // ditch: x 40..41 one block deep across the plot.
        fill(helper, new BlockPos(40, 0, MIN_Z), new BlockPos(41, 0, MAX_Z), Blocks.AIR.defaultBlockState());
        // pillars: 2-high pillars every 3 blocks in x 0..21, z 28..38.
        for (int x = 0; x <= 21; x += 3)
        {
            for (int z = 28; z <= 38; z += 3)
            {
                fill(helper, new BlockPos(x, 1, z), new BlockPos(x, 2, z), stone);
            }
        }
        final Map<String, BlockPos[]> routes = new LinkedHashMap<>();
        routes.put("wall_gap", new BlockPos[] {new BlockPos(-6, 1, 0), new BlockPos(20, 1, 0)});
        routes.put("stairs_up", new BlockPos[] {new BlockPos(22, 1, 5), new BlockPos(31, 4, 5)});
        routes.put("ditch", new BlockPos[] {new BlockPos(36, 1, 25), new BlockPos(46, 1, 25)});
        routes.put("pillars", new BlockPos[] {new BlockPos(-6, 1, 30), new BlockPos(25, 1, 38)});
        return routes;
    }

    private static String digest(final GameTestHelper helper, final Path path)
    {
        final BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.getNodeCount(); i++)
        {
            final Node n = path.getNode(i);
            sb.append(n.x - origin.getX()).append(',').append(n.y - origin.getY()).append(',').append(n.z - origin.getZ()).append(';');
        }
        sb.append(path.canReach() ? "R" : "F");
        try
        {
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return path.getNodeCount() + ":" + HexFormat.of().formatHex(hash, 0, 8);
        }
        catch (final Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * path_routes_identical: four fixture routes, all queued at once, must produce the same node list as before the
     * change (digest of the relative node list, cost jitter off) and must reach.
     */
    public static void pathRoutesIdentical(final GameTestHelper helper)
    {
        prepareFloor(helper);
        keepClock(helper, 6000L);
        final Map<String, BlockPos[]> routes = routeCourse(helper);
        helper.runAfterDelay(2, () -> awaitIdlePool(helper, System.nanoTime(), () -> {
            final Map<String, PathResult<?>> results = new LinkedHashMap<>();
            for (final Map.Entry<String, BlockPos[]> e : routes.entrySet())
            {
                final PathJobMoveToLocation job =
                  new PathJobMoveToLocation(helper.getLevel(), helper.absolutePos(e.getValue()[0]), helper.absolutePos(e.getValue()[1]), 48, null);
                // Path costs carry a random 0..0.1 jitter by default (PathingOptions.randomnessFactor); off here so a
                // route has one right answer.
                job.getPathingOptions().randomnessFactor = 0.0D;
                results.put(e.getKey(), job.getResult());
                Pathfinding.enqueue(job);
            }
            waitAll(helper, new ArrayList<>(results.values()), System.nanoTime(), 0, () -> {
                final StringBuilder s = new StringBuilder("CA10 routes:");
                final List<String> wrong = new ArrayList<>();
                for (final Map.Entry<String, PathResult<?>> e : results.entrySet())
                {
                    final PathResult<?> r = e.getValue();
                    final String d = r.getPath() == null ? "null" : digest(helper, r.getPath());
                    s.append(' ').append(e.getKey()).append('=').append(d).append(r.isPathReachingDestination() ? " reach" : " NOREACH")
                      .append(" (").append(r.searchedNodes).append(" nodes)");
                    final String want = EXPECTED_ROUTES.get(e.getKey());
                    if (!r.isPathReachingDestination() || want == null || !want.equals(d))
                    {
                        wrong.add(e.getKey() + " got " + d + " want " + want);
                    }
                }
                Log.getLogger().info(s.toString());
                helper.assertTrue(wrong.isEmpty(), "routes differ from the pre-change node lists: " + wrong + ". " + s);
                helper.succeed();
            });
        }));
    }

    // ------------------------------------------------------------------ round 10: road cost

    /** Road lanes: a near road (3 blocks off the line, faster) and a far road (10 off, slower than the line). */
    private static final int NEAR_LINE_Z = 4, NEAR_ROAD_Z = 7, FAR_LINE_Z = 24, FAR_ROAD_Z = 34;

    /** Path nodes standing on a road block. */
    private static int roadNodes(final GameTestHelper helper, final Path path)
    {
        int n = 0;
        for (int i = 0; i < path.getNodeCount(); i++)
        {
            final Node node = path.getNode(i);
            if (com.minecolonies.core.util.WorkerUtil.isPathBlock(helper.getLevel().getBlockState(new BlockPos(node.x, node.y - 1, node.z)).getBlock()))
            {
                n++;
            }
        }
        return n;
    }

    /**
     * path_road_preference: road cost is 1 / road speed. A stone brick road 3 blocks off a 63-block straight line walks in
     * about 3 + 63 / 1.3 + 3 = 54.5 against 63, so the path must use it. A road 10 blocks off takes about 68.5, slower than
     * the line, so that path must not use it (with the old cost of 1/6 it looked like 30.5).
     */
    public static void pathRoadPreference(final GameTestHelper helper)
    {
        prepareFloor(helper);
        keepClock(helper, 6000L);
        fill(helper, new BlockPos(-4, 0, NEAR_ROAD_Z), new BlockPos(55, 0, NEAR_ROAD_Z), Blocks.STONE_BRICKS.defaultBlockState());
        fill(helper, new BlockPos(-4, 0, FAR_ROAD_Z), new BlockPos(55, 0, FAR_ROAD_Z), Blocks.STONE_BRICKS.defaultBlockState());
        helper.runAfterDelay(2, () -> awaitIdlePool(helper, System.nanoTime(), () -> {
            final PathJobMoveToLocation near = new PathJobMoveToLocation(helper.getLevel(), helper.absolutePos(new BlockPos(-6, 1, NEAR_LINE_Z)),
              helper.absolutePos(new BlockPos(57, 1, NEAR_LINE_Z)), 48, null);
            final PathJobMoveToLocation far = new PathJobMoveToLocation(helper.getLevel(), helper.absolutePos(new BlockPos(-6, 1, FAR_LINE_Z)),
              helper.absolutePos(new BlockPos(57, 1, FAR_LINE_Z)), 48, null);
            near.getPathingOptions().randomnessFactor = 0.0D;
            far.getPathingOptions().randomnessFactor = 0.0D;
            Pathfinding.enqueue(near);
            Pathfinding.enqueue(far);
            waitAll(helper, List.of(near.getResult(), far.getResult()), System.nanoTime(), 0, () -> {
                final Path nearPath = near.getResult().getPath();
                final Path farPath = far.getResult().getPath();
                final int nearRoad = nearPath == null ? -1 : roadNodes(helper, nearPath);
                final int farRoad = farPath == null ? -1 : roadNodes(helper, farPath);
                final String s = String.format("R10 road: near %s road nodes %d of %d (%d searched), far %s road nodes %d of %d (%d searched)",
                  near.getResult().isPathReachingDestination() ? "reach" : "NOREACH", nearRoad, nearPath == null ? 0 : nearPath.getNodeCount(),
                  near.getResult().searchedNodes, far.getResult().isPathReachingDestination() ? "reach" : "NOREACH", farRoad,
                  farPath == null ? 0 : farPath.getNodeCount(), far.getResult().searchedNodes);
                Log.getLogger().info(s);
                helper.assertTrue(near.getResult().isPathReachingDestination() && far.getResult().isPathReachingDestination(), "a road route did not reach. " + s);
                helper.assertTrue(nearRoad >= 40, "the faster road 3 blocks off the line was not used. " + s);
                helper.assertTrue(farRoad == 0, "the slower road 10 blocks off the line was used. " + s);
                helper.succeed();
            });
        }));
    }

    // ------------------------------------------------------------------ round 10: colony unreachable cache

    /** Installs a submit observer that records the jobs of every host. */
    private static void observeAll(final Host... hosts)
    {
        Pathfinding.submitObserver = job -> {
            for (final Host host : hosts)
            {
                if (job.getEntity() == host.mob)
                {
                    host.jobs.add(job);
                }
            }
        };
    }

    /** Maps the given hosts to colony keys for the test (the hosts are villagers, not citizens); close() restores. */
    private static AutoCloseable testColonies(final ServerLevel level, final Map<Mob, Integer> ids)
    {
        return Pathfinding.overrideColonyKeysForTests(mob -> ids.containsKey(mob) ? new Pathfinding.ColonyKey(level.dimension(), ids.get(mob)) : null);
    }

    private static void close(final AutoCloseable hook)
    {
        try
        {
            hook.close();
        }
        catch (final Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * path_unreachable_colony_cache: host A of colony 1 searches for the sealed target from the far corner; the search
     * tries every node of the bordered floor (strong proof, with every reachable position). Hosts B (10 blocks from A)
     * and D (far, but on the same open floor) of colony 1 then ask for it and must not search once. Host C of colony 2
     * must search. Then the shell is opened: the block change clears the entry and B
     * must reach the target.
     */
    public static void pathUnreachableColonyCache(final GameTestHelper helper)
    {
        prepareFloor(helper);
        keepClock(helper, 6000L);
        sealTarget(helper, SEALED_TARGET);
        final Host a = new Host(helper, new BlockPos(-6, 1, -6));
        final Host b = new Host(helper, new BlockPos(-6, 1, 4));
        final Host c = new Host(helper, new BlockPos(-2, 1, -6));
        final Host d = new Host(helper, new BlockPos(50, 1, 30));
        final BlockPos target = helper.absolutePos(SEALED_TARGET);
        final Map<Mob, Integer> ids = new java.util.HashMap<>();
        ids.put(a.mob, 1);
        ids.put(b.mob, 1);
        ids.put(d.mob, 1);
        ids.put(c.mob, 2);
        final AutoCloseable colonies = testColonies(helper.getLevel(), ids);
        observeAll(a, b, c, d);
        final long[] hitsBefore = {Pathfinding.Stats.unreachableCacheHits.get()};
        final Runnable restore = () -> {
            Pathfinding.submitObserver = null;
            close(colonies);
        };
        helper.runAfterDelay(2, () -> awaitIdlePool(helper, System.nanoTime(), () ->
          drive(helper, a, () -> target, 0, 100, t -> a.jobs.size() >= 2, () ->
            drive(helper, b, () -> target, 0, 100, t -> false, () ->
              drive(helper, c, () -> target, 0, 100, t -> c.jobs.size() > 0, () ->
                drive(helper, d, () -> target, 0, 100, t -> d.jobs.size() > 0, () -> {
                    final int bBlocked = b.jobs.size();
                    helper.setBlock(SEALED_TARGET.west(), Blocks.AIR.defaultBlockState());
                    helper.setBlock(SEALED_TARGET.west().above(), Blocks.AIR.defaultBlockState());
                    drive(helper, b, () -> target, 0, 300, t -> b.anyReached(target), () -> {
                        restore.run();
                        final PathResult<?> first = a.jobs.isEmpty() ? null : a.jobs.get(0).getResult();
                        final String s = String.format("R10 colony cache: A first search exhausted %s (%d nodes), B (same colony, near A) %d jobs while cached then %d, "
                                                         + "D (same colony, far, same floor) %d jobs, C (other colony) %d jobs, B reached %s, cache refusals %d",
                          first != null && first.searchExhausted, first == null ? 0 : first.searchedNodes, bBlocked, b.jobs.size(), d.jobs.size(), c.jobs.size(),
                          b.anyReached(target), Pathfinding.Stats.unreachableCacheHits.get() - hitsBefore[0]);
                        Log.getLogger().info(s);
                        helper.assertTrue(first != null && first.searchExhausted && first.searchedNodes >= 500, "fixture: A's search was not strong proof. " + s);
                        helper.assertTrue(bBlocked == 0, "a citizen of the same colony starting near A searched the proven unreachable target. " + s);
                        helper.assertTrue(Pathfinding.Stats.unreachableCacheHits.get() > hitsBefore[0], "no refusal by the colony cache was counted. " + s);
                        helper.assertTrue(d.jobs.isEmpty(), "a citizen of the same colony on a position A's search reached (same proof) still searched. " + s);
                        helper.assertTrue(c.jobs.size() >= 1, "a citizen of another colony was blocked by colony 1's cache. " + s);
                        helper.assertTrue(b.anyReached(target), "the target was not reached after the blocks around it changed. " + s);
                        helper.succeed();
                    });
                }))))));
    }

    /**
     * path_colony_cache_walled_yard: host T of colony 1 stands in a walled yard (26 x 26, 3 high, closed) and asks for an
     * open target outside; its search exhausts the yard (500+ nodes, strong proof for positions inside the yard). Host U
     * of colony 1, inside the yard, must be refused. Host B of colony 1, 10 blocks from T on the other side of the wall,
     * must still search and reach the target.
     */
    public static void pathColonyCacheWalledYard(final GameTestHelper helper)
    {
        prepareFloor(helper);
        keepClock(helper, 6000L);
        final BlockState stone = Blocks.STONE.defaultBlockState();
        fill(helper, new BlockPos(-1, 1, -1), new BlockPos(26, 3, -1), stone);
        fill(helper, new BlockPos(-1, 1, 26), new BlockPos(26, 3, 26), stone);
        fill(helper, new BlockPos(-1, 1, -1), new BlockPos(-1, 3, 26), stone);
        fill(helper, new BlockPos(26, 1, -1), new BlockPos(26, 3, 26), stone);
        final Host t = new Host(helper, new BlockPos(12, 1, 22));
        final Host u = new Host(helper, new BlockPos(15, 1, 22));
        final Host b = new Host(helper, new BlockPos(12, 1, 32));
        final BlockPos target = helper.absolutePos(new BlockPos(50, 1, 30));
        final Map<Mob, Integer> ids = new java.util.HashMap<>();
        ids.put(t.mob, 1);
        ids.put(u.mob, 1);
        ids.put(b.mob, 1);
        final AutoCloseable colonies = testColonies(helper.getLevel(), ids);
        observeAll(t, u, b);
        helper.runAfterDelay(2, () -> awaitIdlePool(helper, System.nanoTime(), () ->
          drive(helper, t, () -> target, 0, 100, t2 -> t.jobs.size() >= 2, () ->
            drive(helper, u, () -> target, 0, 100, t2 -> false, () ->
              drive(helper, b, () -> target, 0, 300, t2 -> b.anyReached(target), () -> {
                  Pathfinding.submitObserver = null;
                  close(colonies);
                  final PathResult<?> first = t.jobs.isEmpty() ? null : t.jobs.get(0).getResult();
                  final String s = String.format("R10 walled yard: T first search exhausted %s (%d nodes, proof %d positions), U (inside) %d jobs, "
                                                   + "B (10 blocks, other side of the wall) %d jobs, B reached %s",
                    first != null && first.searchExhausted, first == null ? 0 : first.searchedNodes,
                    first == null || first.reachablePositions == null ? 0 : first.reachablePositions.length, u.jobs.size(), b.jobs.size(), b.anyReached(target));
                  Log.getLogger().info(s);
                  helper.assertTrue(first != null && first.searchExhausted && first.reachablePositions != null, "fixture: the yard search was no proof. " + s);
                  helper.assertTrue(u.jobs.isEmpty(), "a citizen inside the proven yard searched again. " + s);
                  helper.assertTrue(b.jobs.size() >= 1, "a citizen outside the yard was blocked by the yard's proof. " + s);
                  helper.assertTrue(b.anyReached(target), "the citizen outside the yard did not reach the target. " + s);
                  helper.succeed();
              })))));
    }

    /**
     * path_colony_cache_boxed_citizen: host T of colony 1 is boxed in (a 1x2 pocket in stone) and asks for an open target
     * 36 blocks away: its search exhausts in a few nodes. That is no proof about the target, so host B of the same colony,
     * standing 4 blocks from T outside the box, must still search and reach the target.
     */
    public static void pathColonyCacheBoxedCitizen(final GameTestHelper helper)
    {
        prepareFloor(helper);
        keepClock(helper, 6000L);
        final BlockPos boxRel = new BlockPos(4, 1, 10);
        final Host t = new Host(helper, boxRel);
        sealTarget(helper, boxRel);
        final Host b = new Host(helper, new BlockPos(8, 1, 10));
        final BlockPos target = helper.absolutePos(new BlockPos(40, 1, 10));
        final Map<Mob, Integer> ids = new java.util.HashMap<>();
        ids.put(t.mob, 1);
        ids.put(b.mob, 1);
        final AutoCloseable colonies = testColonies(helper.getLevel(), ids);
        observeAll(t, b);
        helper.runAfterDelay(2, () -> awaitIdlePool(helper, System.nanoTime(), () ->
          drive(helper, t, () -> target, 0, 100, t2 -> false, () ->
            drive(helper, b, () -> target, 0, 300, t2 -> b.anyReached(target), () -> {
                Pathfinding.submitObserver = null;
                close(colonies);
                final PathResult<?> first = t.jobs.isEmpty() ? null : t.jobs.get(0).getResult();
                final String s = String.format("R10 boxed citizen: T %d jobs (first exhausted %s, %d nodes, backed off %s), B %d jobs, B reached %s",
                  t.jobs.size(), first != null && first.searchExhausted, first == null ? 0 : first.searchedNodes, t.nav.isDestinationUnreachable(target), b.jobs.size(),
                  b.anyReached(target));
                Log.getLogger().info(s);
                helper.assertTrue(first != null && first.searchExhausted && !first.isPathReachingDestination(), "fixture: the boxed host's search did not exhaust. " + s);
                helper.assertTrue(b.jobs.size() >= 1, "a boxed citizen's failure stopped another citizen of its colony from searching. " + s);
                helper.assertTrue(b.anyReached(target), "the other citizen did not reach the shared target. " + s);
                helper.succeed();
            }))));
    }

    // ------------------------------------------------------------------ round 10: failed-search cap

    /**
     * path_failed_search_cap: a host asks for a target sealed in stone. Its first search keeps the normal budget; its
     * retry for the same destination is marked and must stay within the retry cap plus the high-cost allowance
     * (2 x 25^2). Logged: nodes per search.
     */
    public static void pathFailedSearchCap(final GameTestHelper helper)
    {
        prepareFloor(helper);
        keepClock(helper, 6000L);
        sealTarget(helper, SEALED_TARGET);
        final Host host = new Host(helper, HOST_START);
        final BlockPos target = helper.absolutePos(SEALED_TARGET);
        observe(host);
        helper.runAfterDelay(2, () -> awaitIdlePool(helper, System.nanoTime(), () -> {
            Pathfinding.Stats.reset();
            drive(helper, host, () -> target, 0, 400, t -> host.jobs.size() >= 2 && host.jobs.get(1).getResult().isDone(), () -> {
                Pathfinding.submitObserver = null;
                final int allowance = 2 * com.minecolonies.core.entity.pathfinding.PathingOptions.MAX_COST * com.minecolonies.core.entity.pathfinding.PathingOptions.MAX_COST + 1;
                final StringBuilder jobs = new StringBuilder();
                for (final AbstractPathJob job : host.jobs)
                {
                    final PathJobMoveToLocation m = (PathJobMoveToLocation) job;
                    jobs.append(m.getResult().searchedNodes).append(m.isRetryCapped() ? " capped " + m.getFailedNodeCap() : " normal").append("; ");
                }
                final String s = "R10 retry cap: jobs [" + jobs + "] allowance " + allowance + "; stats: " + Pathfinding.Stats.summary();
                Log.getLogger().info(s);
                helper.assertTrue(host.jobs.size() >= 2, "fixture: fewer than 2 searches. " + s);
                final PathJobMoveToLocation first = (PathJobMoveToLocation) host.jobs.get(0);
                final PathJobMoveToLocation retry = (PathJobMoveToLocation) host.jobs.get(1);
                helper.assertTrue(!first.isRetryCapped(), "the first search was capped. " + s);
                helper.assertTrue(retry.isRetryCapped(), "the retry for a failed destination was not capped. " + s);
                helper.assertTrue(!retry.getResult().isPathReachingDestination(), "fixture: the sealed target was reached. " + s);
                helper.assertTrue(retry.getResult().searchedNodes <= retry.getFailedNodeCap() + allowance, "the retry ran past its cap. " + s);
                helper.succeed();
            });
        }));
    }

    /**
     * path_long_detour_reaches: a 106-block (manhattan) walk from corner to corner around a wall across almost the whole
     * plot (gap only at the far end) must reach with the normal budget. Logged: nodes searched.
     */
    public static void pathLongDetourReaches(final GameTestHelper helper)
    {
        prepareFloor(helper);
        keepClock(helper, 6000L);
        fill(helper, new BlockPos(25, 1, MIN_Z), new BlockPos(25, 3, 35), Blocks.STONE.defaultBlockState());
        helper.runAfterDelay(2, () -> awaitIdlePool(helper, System.nanoTime(), () -> {
            final PathJobMoveToLocation job =
              new PathJobMoveToLocation(helper.getLevel(), helper.absolutePos(new BlockPos(-6, 1, -6)), helper.absolutePos(new BlockPos(57, 1, 37)), 48, null);
            Pathfinding.enqueue(job);
            waitAll(helper, List.of(job.getResult()), System.nanoTime(), 0, () -> {
                final Path path = job.getResult().getPath();
                final String s = String.format("R10 long detour: %s, %d path nodes, %d searched", job.getResult().isPathReachingDestination() ? "reach" : "NOREACH",
                  path == null ? 0 : path.getNodeCount(), job.getResult().searchedNodes);
                Log.getLogger().info(s);
                helper.assertTrue(job.getResult().isPathReachingDestination(), "a long legit detour did not reach. " + s);
                helper.assertTrue(path != null && path.getNodeCount() >= 100, "fixture: the walk is shorter than 100 nodes. " + s);
                helper.succeed();
            });
        }));
    }

    /**
     * path_road_heuristic_nodes (M3 measurement): a road grid (stone brick lines every 8 blocks both ways) and 8 trips
     * between off-road points, each searched with and without the road heuristic scale (cost jitter off). Logged: total
     * nodes, walk time (1 per off-road step, 1/1.3 on road) and road share for both. Every trip must reach both ways.
     */
    public static void pathRoadHeuristicNodes(final GameTestHelper helper)
    {
        prepareFloor(helper);
        keepClock(helper, 6000L);
        for (int x = -4; x <= 56; x += 8)
        {
            fill(helper, new BlockPos(x, 0, MIN_Z + 1), new BlockPos(x, 0, MAX_Z - 1), Blocks.STONE_BRICKS.defaultBlockState());
        }
        for (int z = -4; z <= 36; z += 8)
        {
            fill(helper, new BlockPos(MIN_X + 1, 0, z), new BlockPos(MAX_X - 1, 0, z), Blocks.STONE_BRICKS.defaultBlockState());
        }
        final int[][] trips = {{-6, -6, 57, 37}, {57, -6, -6, 37}, {1, 2, 50, 30}, {-2, 30, 54, -2}, {10, 10, 46, 26}, {22, -7, 30, 38}, {-7, 18, 58, 18}, {34, 2, 2, 34}};
        helper.runAfterDelay(2, () -> awaitIdlePool(helper, System.nanoTime(), () -> {
            final List<PathJobMoveToLocation> on = new ArrayList<>();
            final List<PathJobMoveToLocation> off = new ArrayList<>();
            for (final int[] trip : trips)
            {
                for (final boolean scale : new boolean[] {true, false})
                {
                    final PathJobMoveToLocation job = new PathJobMoveToLocation(helper.getLevel(), helper.absolutePos(new BlockPos(trip[0], 1, trip[1])),
                      helper.absolutePos(new BlockPos(trip[2], 1, trip[3])), 48, null);
                    job.getPathingOptions().randomnessFactor = 0.0D;
                    job.getPathingOptions().roadHeuristicScale = scale;
                    (scale ? on : off).add(job);
                    Pathfinding.enqueue(job);
                }
            }
            final List<PathResult<?>> all = new ArrayList<>();
            on.forEach(j -> all.add(j.getResult()));
            off.forEach(j -> all.add(j.getResult()));
            waitAll(helper, all, System.nanoTime(), 0, () -> {
                final double[] onNums = roadTotals(helper, on);
                final double[] offNums = roadTotals(helper, off);
                final String s = String.format("R10 road heuristic: scale on %d reached, %.0f nodes, walk time %.1f, road share %.2f; scale off %d reached, %.0f nodes, "
                                                 + "walk time %.1f, road share %.2f; nodes ratio on/off %.2f",
                  (int) onNums[0], onNums[1], onNums[2], onNums[3], (int) offNums[0], offNums[1], offNums[2], offNums[3], onNums[1] / Math.max(1, offNums[1]));
                Log.getLogger().info(s);
                helper.assertTrue(onNums[0] == trips.length && offNums[0] == trips.length, "a road grid trip did not reach. " + s);
                helper.succeed();
            });
        }));
    }

    /**
     * path_road_heuristic_citizen (M3): a real citizen's navigator gives the heuristic. A road grid with 5 x 5 stone
     * buildings in its cells, 8 trips between building sides, each searched with and without the road scale, once with the
     * citizen's own learned average and once with it set to 0.3 (multiplier 1.3). Logged: nodes, walk time, how often the
     * scale fired. Every trip must reach.
     */
    public static void pathRoadHeuristicCitizen(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = foundGameTestColony(helper, "R10 road heuristic");
        final int x0 = 10, x1 = 59, z0 = -8, z1 = 39;
        final BlockPos a = helper.absolutePos(new BlockPos(x0, 0, z0));
        final BlockPos b = helper.absolutePos(new BlockPos(x1, 0, z1));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++)
        {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++)
            {
                level.setChunkForced(cx, cz, true);
            }
        }
        keepClock(helper, 6000L);
        final BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = x0; x <= x1; x++)
        {
            for (int z = z0; z <= z1; z++)
            {
                helper.setBlock(new BlockPos(x, -1, z), stone);
                final boolean border = x == x0 || x == x1 || z == z0 || z == z1;
                final boolean road = (x - x0) % 8 == 4 || (z - z0) % 8 == 4;
                helper.setBlock(new BlockPos(x, 0, z), road && !border ? Blocks.STONE_BRICKS.defaultBlockState() : stone);
                final boolean building = !road && !border && Math.floorMod(x - x0, 8) >= 6 - 4 && Math.floorMod(x - x0, 8) <= 6
                                           && Math.floorMod(z - z0, 8) >= 2 && Math.floorMod(z - z0, 8) <= 6;
                for (int y = 1; y <= 5; y++)
                {
                    helper.setBlock(new BlockPos(x, y, z), (border || building) && y <= 3 ? stone : Blocks.AIR.defaultBlockState());
                }
            }
        }
        final int[][] trips = {{x0 + 1, z0 + 1, x1 - 1, z1 - 1}, {x1 - 1, z0 + 1, x0 + 1, z1 - 1}, {x0 + 9, z0 + 1, x1 - 9, z1 - 1}, {x0 + 1, z1 - 9, x1 - 1, z0 + 9},
          {x0 + 17, z0 + 1, x0 + 33, z1 - 1}, {x0 + 1, z0 + 17, x1 - 1, z0 + 17}, {x1 - 1, z0 + 25, x0 + 1, z0 + 9}, {x0 + 25, z1 - 1, x0 + 9, z0 + 1}};
        helper.runAfterDelay(20, () -> {
            final BlockPos spawn = helper.absolutePos(new BlockPos(x0 + 1, 1, z0 + 1));
            final ICitizenData data = colony.getCitizenManager().spawnOrCreateCivilian(null, level, List.of(spawn), true);
            helper.assertTrue(data != null && data.getEntity().isPresent(), "citizen did not spawn");
            final AbstractEntityCitizen citizen = data.getEntity().get();
            final MinecoloniesAdvancedPathNavigate nav = (MinecoloniesAdvancedPathNavigate) citizen.getNavigation();
            final double learned = nav.getAvgHeuristicModifier();
            final StringBuilder out = new StringBuilder("R10 road heuristic citizen:");
            final boolean[] allReached = {true};
            final long[] scaleFired = {0};
            final Runnable[] round = new Runnable[1];
            final double[] avgs = {learned, 0.3D};
            final int[] index = {0};
            round[0] = () -> {
                if (index[0] >= avgs.length)
                {
                    final String s = out.append(" (avg ").append(String.format("%.2f", learned)).append(" learned at spawn)").toString();
                    Log.getLogger().info(s);
                    helper.assertTrue(allReached[0], "a road grid trip did not reach. " + s);
                    helper.assertTrue(scaleFired[0] > 0, "fixture: the road scale never fired. " + s);
                    helper.succeed();
                    return;
                }
                final double avg = avgs[index[0]++];
                nav.setAvgHeuristicModifierForTests(avg);
                final List<PathJobMoveToLocation> on = new ArrayList<>();
                final List<PathJobMoveToLocation> off = new ArrayList<>();
                for (final int[] trip : trips)
                {
                    for (final boolean scale : new boolean[] {true, false})
                    {
                        final PathJobMoveToLocation job = new PathJobMoveToLocation(level, helper.absolutePos(new BlockPos(trip[0], 1, trip[1])),
                          helper.absolutePos(new BlockPos(trip[2], 1, trip[3])), 48, citizen);
                        job.getPathingOptions().randomnessFactor = 0.0D;
                        job.getPathingOptions().roadHeuristicScale = scale;
                        (scale ? on : off).add(job);
                        Pathfinding.enqueue(job);
                    }
                }
                final List<PathResult<?>> all = new ArrayList<>();
                on.forEach(j -> all.add(j.getResult()));
                off.forEach(j -> all.add(j.getResult()));
                waitAll(helper, all, System.nanoTime(), 0, () -> {
                    final double[] onNums = roadTotals(helper, on);
                    final double[] offNums = roadTotals(helper, off);
                    final long fired = on.stream().filter(AbstractPathJob::isRoadScaled).count();
                    scaleFired[0] += fired;
                    allReached[0] &= onNums[0] == trips.length && offNums[0] == trips.length;
                    out.append(String.format(" | multiplier %.2f: scale on %.0f nodes (fired in %d of %d), walk %.1f, road %.2f; off %.0f nodes, walk %.1f, road %.2f; "
                                               + "nodes on/off %.2f", on.get(0).getStartHeuristicMod(), onNums[1], fired, trips.length, onNums[2], onNums[3], offNums[1],
                      offNums[2], offNums[3], onNums[1] / Math.max(1, offNums[1])));
                    round[0].run();
                });
            };
            round[0].run();
        });
    }

    /** Reached count, total nodes searched, total walk time, share of steps on road. */
    private static double[] roadTotals(final GameTestHelper helper, final List<PathJobMoveToLocation> jobs)
    {
        double reached = 0, nodes = 0, time = 0, road = 0, steps = 0;
        for (final PathJobMoveToLocation job : jobs)
        {
            reached += job.getResult().isPathReachingDestination() ? 1 : 0;
            nodes += job.getResult().searchedNodes;
            final Path path = job.getResult().getPath();
            if (path == null)
            {
                continue;
            }
            for (int i = 1; i < path.getNodeCount(); i++)
            {
                final Node n = path.getNode(i);
                final boolean onRoad =
                  com.minecolonies.core.util.WorkerUtil.isPathBlock(helper.getLevel().getBlockState(new BlockPos(n.x, n.y - 1, n.z)).getBlock());
                time += onRoad ? 1 / com.minecolonies.core.entity.pathfinding.PathingOptions.ROAD_SPEED : 1;
                road += onRoad ? 1 : 0;
                steps++;
            }
        }
        return new double[] {reached, nodes, time, steps == 0 ? 0 : road / steps};
    }

    // ------------------------------------------------------------------ round 10: fair queue

    /** Fair queue: jobs of the flooding colony, jobs of the quiet colony, and the start-order bar for the quiet ones. */
    private static final int FLOOD_JOBS = 60, QUIET_JOBS = 10, QUIET_START_BAR = 2 * QUIET_JOBS + 10;

    /**
     * path_queue_fair_colonies: colony 1 floods the shared queue with 60 failing searches (a sealed target 102 blocks
     * away), then colony 2 queues 10 short ones. Colonies take turns, so every colony 2 search must start among the first
     * 2 x 10 + 10 searches (one queue in order would start them after all 60; 150 ran close to the 40,100-tick test limit on the unthrottled server). Nothing may be rejected.
     */
    public static void pathQueueFairColonies(final GameTestHelper helper)
    {
        prepareFloor(helper);
        keepClock(helper, 6000L);
        final BlockPos farTarget = new BlockPos(55, 1, 35);
        sealTarget(helper, farTarget);
        final Pathfinding.ColonyKey flooding = new Pathfinding.ColonyKey(helper.getLevel().dimension(), 1);
        final Pathfinding.ColonyKey quiet = new Pathfinding.ColonyKey(helper.getLevel().dimension(), 2);
        helper.runAfterDelay(2, () -> awaitIdlePool(helper, System.nanoTime(), () -> {
            Pathfinding.Stats.reset();
            final List<PathJobMoveToLocation> flood = new ArrayList<>();
            final List<PathJobMoveToLocation> calm = new ArrayList<>();
            for (int i = 0; i < FLOOD_JOBS; i++)
            {
                final PathJobMoveToLocation job =
                  new PathJobMoveToLocation(helper.getLevel(), helper.absolutePos(new BlockPos(-6, 1, -6)), helper.absolutePos(farTarget), 48, null);
                job.setQueueKey(flooding);
                flood.add(job);
                Pathfinding.enqueue(job);
            }
            for (int i = 0; i < QUIET_JOBS; i++)
            {
                final PathJobMoveToLocation job =
                  new PathJobMoveToLocation(helper.getLevel(), helper.absolutePos(new BlockPos(0, 1, 2 * i)), helper.absolutePos(new BlockPos(12, 1, 2 * i)), 48, null);
                job.setQueueKey(quiet);
                calm.add(job);
                Pathfinding.enqueue(job);
            }
            final List<PathResult<?>> all = new ArrayList<>();
            flood.forEach(j -> all.add(j.getResult()));
            calm.forEach(j -> all.add(j.getResult()));
            final long t0 = System.nanoTime();
            waitAll(helper, all, t0, 0, () -> {
                long quietLast = 0;
                int quietReached = 0;
                for (final PathJobMoveToLocation j : calm)
                {
                    quietLast = Math.max(quietLast, j.getStartOrder());
                    quietReached += j.getResult().isPathReachingDestination() ? 1 : 0;
                }
                long floodLast = 0;
                long floodWait = 0;
                long quietWait = 0;
                for (final PathJobMoveToLocation j : flood)
                {
                    floodLast = Math.max(floodLast, j.getStartOrder());
                    floodWait += Math.max(0, j.getQueueWaitNanos());
                }
                for (final PathJobMoveToLocation j : calm)
                {
                    quietWait += Math.max(0, j.getQueueWaitNanos());
                }
                final String s = String.format("R10 fair queue: %d + %d jobs, last quiet start %d (bar %d), last flood start %d, quiet reached %d, rejected %d, "
                                                 + "avg queue wait quiet %.2f ms flood %.2f ms, all done after %d ms; stats: %s",
                  FLOOD_JOBS, QUIET_JOBS, quietLast, QUIET_START_BAR, floodLast, quietReached, Pathfinding.Stats.rejected.get(), quietWait / 1e6 / QUIET_JOBS,
                  floodWait / 1e6 / FLOOD_JOBS, (System.nanoTime() - t0) / 1_000_000L, Pathfinding.Stats.summary());
                Log.getLogger().info(s);
                helper.assertTrue(Pathfinding.Stats.rejected.get() == 0, "jobs were rejected. " + s);
                helper.assertTrue(quietReached == QUIET_JOBS, "fixture: a quiet colony job did not reach. " + s);
                helper.assertTrue(quietLast > 0 && quietLast <= QUIET_START_BAR, "the quiet colony waited behind the flooding one. " + s);
                helper.succeed();
            });
        }));
    }

    // ------------------------------------------------------------------ round 10: route hints

    /** A plain (not a route leg) job of the host for the destination that reached it. */
    private static boolean plainReached(final Host host, final BlockPos dest)
    {
        for (final AbstractPathJob job : host.jobs)
        {
            if (job.getRouteTarget() == null && PathJobMoveToLocation.isJobFor(job, dest) && job.getResult().isPathReachingDestination())
            {
                return true;
            }
        }
        return false;
    }

    /** The host's route legs for the destination, in order. */
    private static List<AbstractPathJob> legs(final Host host, final BlockPos dest)
    {
        final List<AbstractPathJob> legs = new ArrayList<>();
        for (final AbstractPathJob job : host.jobs)
        {
            if (dest.equals(job.getRouteTarget()))
            {
                legs.add(job);
            }
        }
        return legs;
    }

    private static String legSummary(final GameTestHelper helper, final List<AbstractPathJob> legs)
    {
        final StringBuilder sb = new StringBuilder("[");
        for (final AbstractPathJob leg : legs)
        {
            sb.append(helper.relativePos(((PathJobMoveToLocation) leg).getDestination()).toShortString()).append(leg.getResult().isPathReachingDestination() ? " R; " : " F; ");
        }
        return sb.append(']').toString();
    }

    /**
     * path_route_hints_followed: a test provider answers a 76-block walk with two waypoints on a detour (the plain
     * search would go diagonally). The host must walk leg to the first, leg to the second, then search plainly to the
     * destination and reach it; the provider is asked once.
     */
    public static void pathRouteHintsFollowed(final GameTestHelper helper)
    {
        prepareFloor(helper);
        keepClock(helper, 6000L);
        final Host host = new Host(helper, new BlockPos(-6, 1, -6));
        final BlockPos dest = helper.absolutePos(new BlockPos(57, 1, 37));
        final BlockPos w1 = helper.absolutePos(new BlockPos(50, 1, -4));
        final BlockPos w2 = helper.absolutePos(new BlockPos(52, 1, 20));
        final List<BlockPos> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        final IRouteHintProvider provider = (level, colony, from, to) -> {
            if (!to.equals(dest))
            {
                return null;
            }
            calls.add(from);
            return List.of(w1, w2);
        };
        observe(host);
        helper.runAfterDelay(2, () -> awaitIdlePool(helper, System.nanoTime(), () -> {
            Pathfinding.Stats.reset();
            RouteHints.register(provider);
            final AutoCloseable anyMob = MinecoloniesAdvancedPathNavigate.routeHintsForAnyMobInTests();
            drive(helper, host, () -> dest, 0, 1200, t -> plainReached(host, dest), () -> {
                RouteHints.unregister(provider);
                close(anyMob);
                Pathfinding.submitObserver = null;
                final List<AbstractPathJob> legs = legs(host, dest);
                final String s = String.format("R10 route hints followed: %d provider calls, legs %s, plain reached %s, host at %s; stats: %s", calls.size(),
                  legSummary(helper, legs), plainReached(host, dest), helper.relativePos(host.mob.blockPosition()).toShortString(), Pathfinding.Stats.summary());
                Log.getLogger().info(s);
                helper.assertTrue(calls.size() == 1, "the provider was not asked exactly once. " + s);
                helper.assertTrue(legs.size() == 2, "not two route legs. " + s);
                helper.assertTrue(legs.size() == 2 && ((PathJobMoveToLocation) legs.get(0)).getDestination().equals(w1)
                                    && ((PathJobMoveToLocation) legs.get(1)).getDestination().equals(w2), "the legs do not follow the waypoints in order. " + s);
                helper.assertTrue(legs.stream().allMatch(l -> l.getResult().isPathReachingDestination()), "a leg did not reach its waypoint. " + s);
                helper.assertTrue(plainReached(host, dest), "the destination was not reached after the last waypoint. " + s);
                helper.succeed();
            });
        }));
    }

    /**
     * path_route_hints_fallback: (1) a provider that throws and one that answers null: the walk is one plain search that
     * reaches, the throw is caught. (2) a provider whose only waypoint is sealed in stone: the leg fails, the route is
     * dropped, and a plain search reaches the destination.
     */
    public static void pathRouteHintsFallback(final GameTestHelper helper)
    {
        prepareFloor(helper);
        keepClock(helper, 6000L);
        final Host host = new Host(helper, new BlockPos(-6, 1, -6));
        final BlockPos dest = helper.absolutePos(new BlockPos(57, 1, 37));
        final BlockPos dest2 = helper.absolutePos(new BlockPos(-6, 1, 30));
        final BlockPos sealedRel = new BlockPos(25, 1, 33);
        final BlockPos sealed = helper.absolutePos(sealedRel);
        final int[] throwCalls = {0};
        final int[] nullCalls = {0};
        final IRouteHintProvider thrower = (level, colony, from, to) -> {
            throwCalls[0]++;
            throw new IllegalStateException("R10 test provider throws on purpose");
        };
        final IRouteHintProvider none = (level, colony, from, to) -> {
            nullCalls[0]++;
            return null;
        };
        final IRouteHintProvider sealedRoute = (level, colony, from, to) -> to.equals(dest2) ? List.of(sealed) : null;
        observe(host);
        final AutoCloseable[] anyMob = new AutoCloseable[1];
        final Runnable restore = () -> {
            RouteHints.unregister(thrower);
            RouteHints.unregister(none);
            RouteHints.unregister(sealedRoute);
            if (anyMob[0] != null)
            {
                close(anyMob[0]);
            }
            Pathfinding.submitObserver = null;
        };
        helper.runAfterDelay(2, () -> awaitIdlePool(helper, System.nanoTime(), () -> {
            Pathfinding.Stats.reset();
            final long failedBefore = RouteHints.failedCalls.get();
            RouteHints.register(thrower);
            RouteHints.register(none);
            anyMob[0] = MinecoloniesAdvancedPathNavigate.routeHintsForAnyMobInTests();
            drive(helper, host, () -> dest, 0, 600, t -> plainReached(host, dest), () -> {
                final int phase1Jobs = host.jobs.size();
                final boolean phase1Reached = plainReached(host, dest);
                final int phase1Legs = legs(host, dest).size();
                RouteHints.unregister(thrower);
                RouteHints.unregister(none);
                sealTarget(helper, sealedRel);
                RouteHints.register(sealedRoute);
                drive(helper, host, () -> dest2, 0, 1200, t -> plainReached(host, dest2), () -> {
                    restore.run();
                    final List<AbstractPathJob> legs2 = legs(host, dest2);
                    final String s = String.format("R10 route hints fallback: phase 1 throw calls %d, null calls %d, caught %d, %d jobs, %d legs, reached %s; "
                                                     + "phase 2 legs %s, plain reached %s; stats: %s", throwCalls[0], nullCalls[0], RouteHints.failedCalls.get() - failedBefore,
                      phase1Jobs, phase1Legs, phase1Reached, legSummary(helper, legs2), plainReached(host, dest2), Pathfinding.Stats.summary());
                    Log.getLogger().info(s);
                    helper.assertTrue(throwCalls[0] >= 1 && nullCalls[0] >= 1, "fixture: the providers were not asked. " + s);
                    helper.assertTrue(RouteHints.failedCalls.get() - failedBefore >= 1, "the provider's exception was not caught and counted. " + s);
                    helper.assertTrue(phase1Legs == 0 && phase1Reached, "a null answer did not give one plain search that reaches. " + s);
                    helper.assertTrue(legs2.size() == 1 && !legs2.get(0).getResult().isPathReachingDestination(), "fixture: no failed leg to the sealed waypoint. " + s);
                    helper.assertTrue(Pathfinding.Stats.routeFallbacks.get() >= 1, "the failed leg did not drop the route. " + s);
                    helper.assertTrue(plainReached(host, dest2), "no plain search reached the destination after the leg failed. " + s);
                    helper.succeed();
                });
            });
        }));
    }

    // ------------------------------------------------------------------ real citizen

    /**
     * path_citizen_walks_home: a homeless citizen 30+ blocks from the town hall at midnight walks home around a wall,
     * through the real AI, navigator and pathfinding pool, within 1,200 ticks and without being teleported. (Clock pinned
     * to midnight, not 6000: walking home is the behaviour under test.)
     */
    public static void pathCitizenWalksHome(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = foundGameTestColony(helper, "CA1011 walk home");
        final BoolSetting moveIn = colony.getSettings().getSetting(BuildingTownHall.MOVE_IN);
        if (moveIn.getValue())
        {
            moveIn.trigger();
        }
        final BlockPos a = helper.absolutePos(new BlockPos(-8, 0, -8));
        final BlockPos b = helper.absolutePos(new BlockPos(40, 0, 32));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++)
        {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++)
            {
                level.setChunkForced(cx, cz, true);
            }
        }
        keepClock(helper, 18000L);
        // A wall between the citizen and the town hall with a gap at its south end.
        fill(helper, new BlockPos(16, 1, -8), new BlockPos(16, 2, 26), Blocks.STONE.defaultBlockState());
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
        final BlockPos playerPos = helper.absolutePos(new BlockPos(20, 1, 30));
        player.teleportTo(playerPos.getX() + 0.5D, playerPos.getY(), playerPos.getZ() + 0.5D);
        colony.getPackageManager().addCloseSubscriber(player);
        final BlockPos townHall = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.runAfterDelay(20, () -> {
            final BlockPos spawn = helper.absolutePos(new BlockPos(30, 1, 20));
            final ICitizenData data = colony.getCitizenManager().spawnOrCreateCivilian(null, level, List.of(spawn), true);
            helper.assertTrue(data != null && data.getEntity().isPresent(), "citizen did not spawn");
            final AbstractEntityCitizen citizen = data.getEntity().get();
            citizen.setPos(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D);
            final long t0 = level.getGameTime();
            final Vec3[] last = {citizen.position()};
            final double[] maxStep = {0};
            final Runnable[] poll = new Runnable[1];
            final int[] tick = {0};
            poll[0] = () -> {
                final Vec3 now = citizen.position();
                maxStep[0] = Math.max(maxStep[0], now.distanceTo(last[0]));
                last[0] = now;
                data.setSaturation(20);
                final double distSq = citizen.blockPosition().distSqr(townHall);
                if (distSq <= 25)
                {
                    final String s = String.format("CA1011 walk home: home after %d ticks, max step %.2f", level.getGameTime() - t0, maxStep[0]);
                    Log.getLogger().info(s);
                    helper.assertTrue(maxStep[0] < 2.0D, "citizen was teleported, not walked. " + s);
                    helper.succeed();
                    return;
                }
                if (++tick[0] > 1200)
                {
                    helper.fail(String.format("citizen not home after 1200 ticks: at %s, %.1f blocks away, max step %.2f", helper.relativePos(citizen.blockPosition()),
                      Math.sqrt(distSq), maxStep[0]));
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }
}
