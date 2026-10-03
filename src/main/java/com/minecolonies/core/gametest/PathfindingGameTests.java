package com.minecolonies.core.gametest;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.pathfinding.IStuckHandler;
import com.minecolonies.api.util.Log;
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
import java.util.concurrent.RejectedExecutionException;
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
 * so it cannot teleport the host.
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
     * change. A route that comes out different fails.
     */
    private static final Map<String, String> EXPECTED_ROUTES = new LinkedHashMap<>();

    static
    {
        EXPECTED_ROUTES.put("wall_gap", null);
        EXPECTED_ROUTES.put("stairs_up", null);
        EXPECTED_ROUTES.put("ditch", null);
        EXPECTED_ROUTES.put("pillars", null);
    }

    private PathfindingGameTests()
    {
    }

    // ------------------------------------------------------------------ fixture helpers

    private static void pinDay(final ServerLevel level)
    {
        level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
    }

    /** Flat stone floor at y 0, air y 1..7, chunks forced, clock pinned to 6000. */
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
                for (int y = 1; y <= 7; y++)
                {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
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
        helper.runAfterDelay(2, () -> drive(helper, host, () -> target, 0, BACKOFF_WINDOW, t -> false, () -> {
            Pathfinding.submitObserver = null;
            final String s = summary("unreachable", host);
            Log.getLogger().info(s);
            helper.assertTrue(!host.jobs.isEmpty(), "fixture: no path job was submitted. " + s);
            helper.assertTrue(!host.anyReached(target), "fixture: the sealed target was reached. " + s);
            helper.assertTrue(host.jobs.size() <= MAX_BACKOFF_JOBS,
              "unreachable target: " + host.jobs.size() + " path jobs in " + BACKOFF_WINDOW + " ticks (bar " + MAX_BACKOFF_JOBS + "). " + s);
            helper.succeed();
        }));
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
        helper.runAfterDelay(2, () -> drive(helper, host, () -> target, 0, 300, t -> false, () ->
          drive(helper, host, () -> other, 0, 100, t -> host.anyReached(other), () -> {
              Pathfinding.submitObserver = null;
              final String s = summary("other target", host);
              Log.getLogger().info(s);
              helper.assertTrue(host.jobsFor(target) >= 2, "fixture: fewer than 2 jobs for the sealed target. " + s);
              helper.assertTrue(host.anyReached(other), "a reachable second target was not reached within 100 ticks while the first one backed off. " + s);
              helper.succeed();
          })));
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
        helper.runAfterDelay(2, () -> drive(helper, host, () -> target, 0, 300, t -> false, () -> {
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
        }));
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
        catch (final RejectedExecutionException e)
        {
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
        helper.runAfterDelay(2, () -> {
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
        });
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
        if (tick > 2400)
        {
            helper.fail("queued path jobs not done after 2400 ticks / " + (System.nanoTime() - t0) / 1_000_000L + " ms");
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
     * change (digest of the relative node list) and must reach.
     */
    public static void pathRoutesIdentical(final GameTestHelper helper)
    {
        prepareFloor(helper);
        keepClock(helper, 6000L);
        final Map<String, BlockPos[]> routes = routeCourse(helper);
        helper.runAfterDelay(2, () -> {
            final Map<String, PathResult<?>> results = new LinkedHashMap<>();
            for (final Map.Entry<String, BlockPos[]> e : routes.entrySet())
            {
                final PathJobMoveToLocation job =
                  new PathJobMoveToLocation(helper.getLevel(), helper.absolutePos(e.getValue()[0]), helper.absolutePos(e.getValue()[1]), 48, null);
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
        });
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
