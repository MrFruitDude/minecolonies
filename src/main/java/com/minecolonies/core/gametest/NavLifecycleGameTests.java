package com.minecolonies.core.gametest;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.core.entity.pathfinding.pathjobs.PathJobFindTree;
import com.minecolonies.core.colony.jobs.JobLumberjack;
import com.minecolonies.core.entity.ai.workers.production.EntityAIWorkLumberjack;
import com.minecolonies.core.entity.ai.workers.util.Tree;
import com.minecolonies.core.entity.pathfinding.PathFindingStatus;
import com.minecolonies.core.entity.pathfinding.PathfindingUtils;
import com.minecolonies.core.entity.pathfinding.navigation.MinecoloniesAdvancedPathNavigate;
import com.minecolonies.core.entity.pathfinding.pathjobs.PathJobMoveToLocation;
import com.minecolonies.core.entity.pathfinding.pathresults.PathResult;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.pathfinder.Path;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Controlled calculations exercise the real navigator without blocking the server thread. */
final class NavLifecycleGameTests
{
    static final Map<String, Consumer<GameTestHelper>> TESTS = new LinkedHashMap<>();
    private static final List<CountDownLatch> GATES = new ArrayList<>();
    static
    {
        TESTS.put("nav_cancelled_tree_search", NavLifecycleGameTests::cancelledTreeSearch);
        TESTS.put("nav_empty_search", NavLifecycleGameTests::emptySearch);
        TESTS.put("nav_slow_calculation", h -> heldCalculation(h, "slow"));
        TESTS.put("nav_hung_calculation", h -> heldCalculation(h, "hung"));
        TESTS.put("nav_cancel_before_calculation", h -> heldCalculation(h, "cancel_before"));
        TESTS.put("nav_cancel_after_reaching", h -> heldCalculation(h, "cancel_after"));
        TESTS.put("worker_eff_stuck_shaft", WorkerEfficiencyGameTests::stuckInShaft);
        TESTS.put("worker_eff_stuck_no_tool", WorkerEfficiencyGameTests::noToolForStone);
        TESTS.put("worker_eff_stuck_unloaded", WorkerEfficiencyGameTests::unloadedTargetKeepsCitizen);
        TESTS.put("worker_eff_stuck_floating", WorkerEfficiencyGameTests::floatingBlockDoesNotHang);
        TESTS.put("rs_wait_courier_stuck", RequestWaitGameTests::stuckCourierGivesUp);
        TESTS.put("rs_courier_watched_stuck", RequestCourierGameTests::watchedStuck);
        TESTS.put("rs_courier_watched_no_stuck_damage", RequestCourierGameTests::watchedNoStuckDamage);
        TESTS.put("scaffold_sealed_roof", BuilderScaffoldGameTests::sealedRoof);
        TESTS.put("scaffold_sealed_no_items", BuilderScaffoldGameTests::sealedRoofNoItems);
        TESTS.put("scaffold_sealed_legacy", BuilderScaffoldGameTests::sealedRoofLegacy);
        TESTS.put("life_cache_rescue_watch", Round13LifeGameTests::cacheRescueWatch);
        TESTS.put("forestry_walk_home_bounded", ForestryGameTests.TESTS.get("forestry_walk_home_bounded"));
    }

    static void register(final RegisterGameTestsEvent event)
    {
        TESTS.forEach((name, test) -> event.registerTest(Identifier.fromNamespaceAndPath("minecolonies", name),
          info -> new MinecoloniesGameTestInstance(info, test), MinecoloniesGameTestRegistrar.isolatedColonyData(event, name)));
    }

    static void clear()
    {
        GATES.forEach(CountDownLatch::countDown);
        GATES.clear();
    }

    private static void emptySearch(final GameTestHelper helper)
    {
        final var colony = MinecoloniesGameTests.foundGameTestColony(helper, "empty search");
        final var hut = ForestryGameTests.hut(helper, colony, new BlockPos(8, 1, 2), false);
        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = ForestryGameTests.forester(helper, colony, hut, new BlockPos(14, 1, 10));
            final var entity = worker.getEntity().orElseThrow();
            final var nav = (MinecoloniesAdvancedPathNavigate) entity.getNavigation();
            nav.enableNavigationTrace();
            final long start = helper.getLevel().getGameTime();
            final boolean[] sawSearch = {false};
            final boolean[] planted = {false};
            final java.util.Set<BlockPos> logs = new java.util.HashSet<>();
            final var initialPosition = entity.position();
            ForestryGameTests.pump(helper, List.of(worker), 12_000, () -> {
                if (!planted[0])
                {
                    helper.assertTrue(entity.getHealth() == entity.getMaxHealth(), "idle search caused damage " + nav.navigationTraceHistory());
                    helper.assertTrue(!nav.navigationTraceHistory().contains("stuck move-away") && !nav.navigationTraceHistory().contains("stuck complete"),
                      "empty search triggered movement rescue " + nav.navigationTraceHistory());
                    if (helper.getLevel().getGameTime() - start < 1500)
                    {
                        return false;
                    }
                    helper.assertTrue(sawSearch[0], "no natural empty search was observed");
                    final BlockPos base = helper.absolutePos(new BlockPos(22, 1, 18));
                    helper.getLevel().setBlockAndUpdate(base.below(), Blocks.GRASS_BLOCK.defaultBlockState());
                    helper.getLevel().setBlockAndUpdate(base, Blocks.OAK_SAPLING.defaultBlockState());
                    helper.assertTrue(TreeGrower.OAK.growTree(helper.getLevel(), helper.getLevel().getChunkSource().getGenerator(), base,
                      helper.getLevel().getBlockState(base), RandomSource.create(71L)), "later tree failed to grow");
                    for (final BlockPos pos : BlockPos.betweenClosed(base.offset(-4, 0, -4), base.offset(4, 12, 4)))
                    {
                        if (helper.getLevel().getBlockState(pos).is(Blocks.OAK_LOG))
                        {
                            logs.add(pos.immutable());
                        }
                    }
                    helper.assertTrue(!logs.isEmpty(), "later tree has no logs");
                    planted[0] = true;
                }
                return entity.position().distanceToSqr(initialPosition) > 4 && logs.stream().noneMatch(pos -> helper.getLevel().getBlockState(pos).is(Blocks.OAK_LOG))
                  && ((JobLumberjack) worker.getJob()).getTree() == null;
            }, () -> "no natural empty search observed: " + ForestryGameTests.describe(worker), () -> {
                final var result = nav.getPathResult();
                sawSearch[0] |= result != null && result.getJob().getClass().getSimpleName().equals("PathJobFindTree");
            }, "nav_empty_search");
        });
    }

    private static void heldCalculation(final GameTestHelper helper, final String mode)
    {
        final var colony = MinecoloniesGameTests.foundGameTestColony(helper, "held calculation " + mode);
        final var hut = ForestryGameTests.hut(helper, colony, new BlockPos(8, 1, 2), false);
        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = ForestryGameTests.forester(helper, colony, hut, new BlockPos(24, 1, 10));
            final var entity = worker.getEntity().orElseThrow();
            entity.setNoAi(true);
            ((JobLumberjack) worker.getJob()).createAI();
            final var nav = (MinecoloniesAdvancedPathNavigate) entity.getNavigation();
            nav.enableNavigationTrace();
            final CountDownLatch gate = new CountDownLatch(1);
            GATES.add(gate);
            final AtomicBoolean entered = new AtomicBoolean();
            final AtomicBoolean interrupted = new AtomicBoolean();
            final BlockPos target = helper.absolutePos(new BlockPos(30, 1, 10));
            final PathJobMoveToLocation job = new PathJobMoveToLocation(helper.getLevel(), PathfindingUtils.prepareStart(entity), target, 32, entity)
            {
                @Override
                protected Path search()
                {
                    entered.set(true);
                    try
                    {
                        gate.await();
                    }
                    catch (final InterruptedException e)
                    {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    return super.search();
                }
            };
            final PathResult<?> result = nav.setPathJob(job, target, 1.0D, true);
            helper.assertTrue(result != null, "held job submission refused");
            final int[] tick = {0};
            final int[] interruptedAt = {-1};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                tick[0]++;
                nav.tick();
                if (interrupted.get() && interruptedAt[0] < 0)
                {
                    interruptedAt[0] = tick[0];
                }
                if (mode.equals("cancel_before") && tick[0] == 40)
                {
                    helper.assertTrue(entered.get(), "held job never began");
                    nav.stop();
                    gate.countDown();
                    judgeCancelledRoute(helper, worker, result);
                    helper.succeed();
                    return;
                }
                if (tick[0] == 400 && !mode.equals("hung"))
                {
                    gate.countDown();
                    helper.assertTrue(entered.get() && !interrupted.get() && result.isComputing() && nav.getStuckHandler().getStuckLevel() < 0,
                      "calculation was rescued/cancelled before it could finish: " + nav.navigationTraceHistory());
                }
                if (mode.equals("hung") && interruptedAt[0] >= 0)
                {
                    gate.countDown();
                    helper.assertTrue(interruptedAt[0] >= 600 && interruptedAt[0] < 1200, "hung calculation rescue age " + interruptedAt[0]);
                    helper.succeed();
                    return;
                }
                if (!mode.equals("hung") && tick[0] > 400 && result.isDone())
                {
                    helper.assertTrue(result.isPathReachingDestination(), "released calculation did not produce a reaching path");
                    if (mode.equals("cancel_after"))
                    {
                        nav.stop();
                        // Preserve the real reaching result across the same status overwrite that used to erase cancellation.
                        result.setStatus(PathFindingStatus.COMPLETE);
                        judgeCancelledRoute(helper, worker, result);
                    }
                    helper.succeed();
                    return;
                }
                if (tick[0] >= 1400)
                {
                    gate.countDown();
                    throw helper.assertionException("held calculation timed out: " + mode + " " + nav.navigationTraceHistory());
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    private static void cancelledTreeSearch(final GameTestHelper helper)
    {
        final var colony = MinecoloniesGameTests.foundGameTestColony(helper, "cancelled search");
        final var hut = ForestryGameTests.hut(helper, colony, new BlockPos(8, 1, 2), false);
        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = ForestryGameTests.forester(helper, colony, hut, new BlockPos(14, 1, 10));
            final var entity = worker.getEntity().orElseThrow();
            entity.setNoAi(true);
            ((JobLumberjack) worker.getJob()).createAI();
            final var ai = ForestryGameTests.ai(worker);
            ai.getStateAI().transitionToNext(new AITarget<>(AIWorkerState.IDLE, AIWorkerState.LUMBERJACK_SEARCHING_TREE, 1));
            final var nav = (MinecoloniesAdvancedPathNavigate) entity.getNavigation();
            final CountDownLatch gate = new CountDownLatch(1);
            GATES.add(gate);
            final AtomicBoolean entered = new AtomicBoolean();
            final PathJobFindTree job = new PathJobFindTree(helper.getLevel(), PathfindingUtils.prepareStart(entity), hut.getPosition(), 16,
              List.of(), 1, colony, entity, true)
            {
                @Override
                protected Path search()
                {
                    entered.set(true);
                    try
                    {
                        gate.await();
                    }
                    catch (final InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    return super.search();
                }
            };
            final PathResult<?> result = nav.setPathJob(job, null, 1.0D, true);
            helper.runAfterDelay(40, () -> {
                helper.assertTrue(entered.get(), "held tree search never began");
                nav.recalc();
                nav.tick();
                gate.countDown();
                try
                {
                    final var pending = EntityAIWorkLumberjack.class.getDeclaredField("pathResult");
                    pending.setAccessible(true);
                    pending.set(ai, result);
                    final var find = EntityAIWorkLumberjack.class.getDeclaredMethod("findTree");
                    find.setAccessible(true);
                    final Object state = find.invoke(ai);
                    helper.assertTrue(state == AIWorkerState.LUMBERJACK_SEARCHING_TREE && pending.get(ai) == null,
                      "cancelled search became no-trees: state=" + state + " result=" + pending.get(ai));
                    helper.succeed();
                }
                catch (final ReflectiveOperationException e)
                {
                    throw new IllegalStateException(e);
                }
            });
        });
    }

    private static void judgeCancelledRoute(final GameTestHelper helper, final ICitizenData worker, final PathResult<?> result)
    {
        final BlockPos base = helper.absolutePos(new BlockPos(28, 1, 18));
        helper.getLevel().setBlockAndUpdate(base.below(), Blocks.GRASS_BLOCK.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(base, Blocks.OAK_SAPLING.defaultBlockState());
        helper.assertTrue(TreeGrower.OAK.growTree(helper.getLevel(), helper.getLevel().getChunkSource().getGenerator(), base,
          helper.getLevel().getBlockState(base), RandomSource.create(71L)), "route cancellation fixture tree did not grow");
        ((JobLumberjack) worker.getJob()).setTree(new Tree(helper.getLevel(), base.above(), worker.getColony()));
        final EntityAIWorkLumberjack ai = ForestryGameTests.ai(worker);
        try
        {
            final var paths = EntityAIWorkLumberjack.class.getDeclaredField("failedTreePaths");
            final var since = EntityAIWorkLumberjack.class.getDeclaredField("leafWallSince");
            final var pending = EntityAIWorkLumberjack.class.getDeclaredField("pathToTree");
            paths.setAccessible(true);
            since.setAccessible(true);
            pending.setAccessible(true);
            paths.setInt(ai, 7);
            final long originalSince = helper.getLevel().getGameTime() - 99;
            since.setLong(ai, originalSince);
            pending.set(ai, result);
            ai.walkToTree(base);
            helper.assertTrue(paths.getInt(ai) == 7 && since.getLong(ai) == originalSince,
              "cancelled route changed failure evidence: failures=" + paths.getInt(ai) + " since=" + since.getLong(ai));
        }
        catch (final ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
