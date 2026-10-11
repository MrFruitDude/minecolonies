package com.minecolonies.core.gametest;

import com.minecolonies.api.colony.WorkerWalking;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.workorders.WorkOrderDecoration;
import com.minecolonies.core.colony.workorders.collab.BuilderCollab;
import com.minecolonies.core.entity.ai.workers.util.AbstractWalking;
import com.minecolonies.core.entity.ai.workers.util.WorkerSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;

/**
 * The worker efficiency settings at work: the benchmark with the speed-ups off (what the burst placing and the time bank alone gain), the speed multiplier,
 * abstract walking, and a calibration of how fast a citizen walks. The benchmark lines start with {@code WBENCH}.
 */
final class WorkerSettingsGameTests
{
    private WorkerSettingsGameTests()
    {
    }

    /** Structure gains only: worker and builder speed at 1.0, no abstract walking. */
    public static void benchPlaceL40Plain(final GameTestHelper helper)
    {
        WorkerSettings.overrideForTests(1.0D, 1.0D, null, WorkerWalking.OFF, null, null);
        WorkerEfficiencyGameTests.bench(helper, "place_l40_plain", 40, false);
    }

    public static void benchPlaceL1Plain(final GameTestHelper helper)
    {
        WorkerSettings.overrideForTests(1.0D, 1.0D, null, WorkerWalking.OFF, null, null);
        WorkerEfficiencyGameTests.bench(helper, "place_l1_plain", 1, false);
    }

    public static void benchClearL40Plain(final GameTestHelper helper)
    {
        WorkerSettings.overrideForTests(1.0D, 1.0D, null, WorkerWalking.OFF, null, null);
        WorkerEfficiencyGameTests.bench(helper, "clear_l40_plain", 40, true);
    }

    /** The settings at their maximum. */
    public static void benchPlaceL40Max(final GameTestHelper helper)
    {
        WorkerSettings.overrideForTests(4.0D, 4.0D, null, null, null, null);
        WorkerEfficiencyGameTests.bench(helper, "place_l40_max", 40, false);
    }

    /**
     * A site far from the hut with abstract walking ALWAYS: the builder relocates (a timed wait, no long walk) and the box gets built.
     */
    public static void abstractWalkingRelocates(final GameTestHelper helper)
    {
        WorkerSettings.overrideForTests(null, null, null, WorkerWalking.ALWAYS, 8, null);
        WorkerEfficiencyGameTests.scene(helper, "WSET relocate", live -> {
            live.setSkills(40, 40);
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), live.blocksInBox() + WorkerEfficiencyGameTests.SPARE);
            final WorkOrderDecoration order = live.order("relocate", new BlockPos(30, 1, 20));
            final boolean[] relocated = {false};
            final Vec3[] last = {live.citizen.getEntity().get().position()};
            final double[] maxStep = {0};
            WorkerEfficiencyGameTests.everyTick(live, 20_000, tick -> {
                final Vec3 now = live.citizen.getEntity().map(e -> e.position()).orElse(last[0]);
                maxStep[0] = Math.max(maxStep[0], now.distanceTo(last[0]));
                last[0] = now;
                relocated[0] |= live.citizen.getEntity().map(AbstractWalking::isRelocating).orElse(false);
                return live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder();
            }, () -> {
                helper.assertTrue(relocated[0], "the builder never relocated");
                helper.assertTrue(maxStep[0] > 3.0D, "no jump seen (max step " + maxStep[0] + ")");
                Log.getLogger().info("WBENCH relocate maxStep={}", maxStep[0]);
                BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
                WorkerSettings.clearOverrides();
                helper.succeed();
            }, live::ai);
        });
    }

    /**
     * Calibrates AbstractWalking.TICKS_PER_BLOCK: a citizen walks 40 blocks along a flat floor with abstract walking off.
     */
    public static void walkSpeedCalibration(final GameTestHelper helper)
    {
        WorkerSettings.overrideForTests(1.0D, 1.0D, null, WorkerWalking.OFF, null, null);
        WorkerEfficiencyGameTests.scene(helper, "WSET walk", live -> {
            final var entity = live.citizen.getEntity().get();
            final BlockPos target = helper.absolutePos(new BlockPos(10, 1, 8)).offset(30, 0, 0);
            entity.setPos(target.getX() - 40 + 0.5D, target.getY(), target.getZ() + 0.5D);
            final long[] start = {-1};
            WorkerEfficiencyGameTests.everyTick(live, 1500, tick -> {
                if (start[0] < 0)
                {
                    start[0] = tick;
                    com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils.walkToPos(entity, target, 1, true);
                }
                else
                {
                    com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils.walkToPos(entity, target, 1, true);
                }
                if (entity.blockPosition().distSqr(target) <= 4)
                {
                    Log.getLogger().info("WBENCH walk 40 blocks in {} ticks = {} ticks per block", tick - start[0], (tick - start[0]) / 40.0D);
                    return true;
                }
                return false;
            }, () -> {
                WorkerSettings.clearOverrides();
                BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
                helper.succeed();
            }, () -> "the citizen did not arrive");
        });
    }
}
