package com.minecolonies.core.gametest;

import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.workorders.WorkOrderDecoration;
import com.minecolonies.core.colony.workorders.collab.BuilderCollab;
import com.minecolonies.core.entity.ai.workers.util.ReachAudit;
import com.minecolonies.core.entity.ai.workers.util.WorkerSettings;
import com.minecolonies.core.entity.pathfinding.Pathfinding;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;

/**
 * L4: the physical 3D reach of builders and the scaffolding they put up for what they cannot reach from the ground. Run on their own with
 * {@code JAVA_TOOL_OPTIONS=-Dminecolonies.gametest.only=scaffold}. Benchmark lines start with {@code WBENCH}, like the L3 benchmark.
 */
final class BuilderScaffoldGameTests
{
    private BuilderScaffoldGameTests()
    {
    }

    // ------------------------------------------------------------------ the box benchmark with the reach audit

    /**
     * The L3 box benchmark (9x9x5, skill 40) with the reach audit on. With the physical reach the audit sees no block placed out of reach;
     * with it off (the L3 behaviour, a horizontal test only) the audit counts what was out of reach, for the record.
     */
    static void boxAudit(final GameTestHelper helper, final String name, final boolean physical, final int skill, final boolean terrain)
    {
        WorkerSettings.overrideReachForTests(null, physical, null, null);
        ReachAudit.enable(true);
        WorkerEfficiencyGameTests.scene(helper, "WREACH " + name, live -> {
            live.setSkills(skill, skill);
            live.giveTools();
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), live.blocksInBox() + WorkerEfficiencyGameTests.SPARE);
            final BlockPos rel = new BlockPos(24, 1, 10);
            if (terrain)
            {
                for (int x = 0; x < WorkerEfficiencyGameTests.SIZE; x++)
                {
                    for (int z = 0; z < WorkerEfficiencyGameTests.SIZE; z++)
                    {
                        helper.setBlock(rel.offset(x, 0, z), net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
                        helper.setBlock(rel.offset(x, 1, z), net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
                    }
                }
            }
            final WorkOrderDecoration order = live.order(name, rel);
            final Vec3[] last = {live.citizen.getEntity().get().position()};
            final double[] walked = {0};
            final int[] doneTick = {0};
            final long paths0 = Pathfinding.Stats.submitted.get();
            WorkerEfficiencyGameTests.everyTick(live, 38_000, tick -> {
                final Vec3 now = live.citizen.getEntity().map(e -> e.position()).orElse(last[0]);
                walked[0] += Math.hypot(now.x - last[0].x, now.z - last[0].z);
                last[0] = now;
                doneTick[0] = tick;
                return live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder();
            }, () -> {
                final int ticks = doneTick[0];
                final int blocks = live.blocksInBox() + (terrain ? 2 * WorkerEfficiencyGameTests.SIZE * WorkerEfficiencyGameTests.SIZE : 0);
                Log.getLogger().info("WBENCH RESULT {} physical={} skill={} terrain={} ticks={} blocks={} blocksPerMin={} walk={} paths={} audit: actions={} violations={} worst={} {}", name,
                  physical, skill, terrain, ticks, blocks, String.format("%.1f", blocks * 1200.0D / ticks), Math.round(walked[0]), Pathfinding.Stats.submitted.get() - paths0,
                  ReachAudit.actions(), ReachAudit.violations(), String.format("%.2f", ReachAudit.worstExcess()), ReachAudit.examples());
                if (physical)
                {
                    helper.assertTrue(ReachAudit.violations() == 0, "blocks were placed out of reach: " + ReachAudit.violations() + " " + ReachAudit.examples());
                }
                BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
                WorkerSettings.clearOverrides();
                ReachAudit.enable(false);
                helper.succeed();
            }, () -> live.ai() + " order=" + live.hut.getWorkOrder() + " progress=" + live.hut.getProgress() + " audit=" + ReachAudit.violations());
        });
    }

    public static void reachBoxL40(final GameTestHelper helper)
    {
        boxAudit(helper, "reach_box_l40", true, 40, false);
    }

    public static void reachBoxL1(final GameTestHelper helper)
    {
        boxAudit(helper, "reach_box_l1", true, 1, false);
    }

    public static void reachClearL40(final GameTestHelper helper)
    {
        boxAudit(helper, "reach_clear_l40", true, 40, true);
    }

    public static void legacyBoxL40(final GameTestHelper helper)
    {
        boxAudit(helper, "legacy_box_l40", false, 40, false);
    }

    public static void legacyBoxL1(final GameTestHelper helper)
    {
        boxAudit(helper, "legacy_box_l1", false, 1, false);
    }
}
