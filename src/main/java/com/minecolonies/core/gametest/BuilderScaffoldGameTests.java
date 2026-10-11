package com.minecolonies.core.gametest;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.core.colony.workorders.WorkManager;
import com.minecolonies.core.colony.workorders.WorkOrderDecoration;
import com.minecolonies.core.colony.workorders.collab.BuilderCollab;
import com.minecolonies.core.colony.workorders.scaffold.ScaffoldLedger;
import com.minecolonies.core.entity.ai.workers.BuilderScaffolding;
import com.minecolonies.core.entity.ai.workers.util.ReachAudit;
import com.minecolonies.core.entity.ai.workers.util.WorkerSettings;
import com.minecolonies.core.entity.pathfinding.Pathfinding;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

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

    // ------------------------------------------------------------------ fixtures

    /** Where the structures of these tests stand, relative to the test plot. */
    static final BlockPos SITE = new BlockPos(24, 1, 10);

    @FunctionalInterface
    interface Solid
    {
        boolean at(int x, int y, int z);
    }

    static Blueprint shape(final String name, final int sx, final int sy, final int sz, final Solid solid)
    {
        final Blueprint blueprint = new Blueprint((short) sx, (short) sy, (short) sz).setName(name).setFileName(name).setPackName("Minecolonies Original");
        for (int y = 0; y < sy; y++)
        {
            for (int x = 0; x < sx; x++)
            {
                for (int z = 0; z < sz; z++)
                {
                    if (solid.at(x, y, z))
                    {
                        blueprint.addBlockState(new BlockPos(x, y, z), WorkerEfficiencyGameTests.BUILD_BLOCK.defaultBlockState());
                    }
                }
            }
        }
        blueprint.setCachePrimaryOffset(BlockPos.ZERO);
        return blueprint;
    }

    /** A hollow 5 by 5 tower without floor and roof. */
    static Blueprint ring(final String name, final int height)
    {
        return shape(name, 5, height, 5, (x, y, z) -> x == 0 || z == 0 || x == 4 || z == 4);
    }

    /** A box of 11 by 11 with walls of 4 and a roof, without a door: the roof can only be reached from outside or from scaffolding. */
    static Blueprint sealedBox(final String name)
    {
        return shape(name, 11, 5, 11, (x, y, z) -> y == 4 || x == 0 || z == 0 || x == 10 || z == 10);
    }

    /** A wall of 5 with a roof slab sticking out 8 blocks at the height of 8: an overhang. */
    static Blueprint overhang(final String name)
    {
        return shape(name, 9, 9, 5, (x, y, z) -> (x == 0 && y < 8) || y == 8);
    }

    static int solidCount(final Blueprint blueprint)
    {
        int n = 0;
        for (int y = 0; y < blueprint.getSizeY(); y++)
        {
            for (int x = 0; x < blueprint.getSizeX(); x++)
            {
                for (int z = 0; z < blueprint.getSizeZ(); z++)
                {
                    if (blueprint.getBlockState(new BlockPos(x, y, z)).is(WorkerEfficiencyGameTests.BUILD_BLOCK))
                    {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    static boolean built(final WorkerEfficiencyGameTests.Live live, final Blueprint blueprint, final BlockPos origin)
    {
        return missing(live, blueprint, origin) == 0;
    }

    static int missing(final WorkerEfficiencyGameTests.Live live, final Blueprint blueprint, final BlockPos origin)
    {
        int n = 0;
        for (int y = 0; y < blueprint.getSizeY(); y++)
        {
            for (int x = 0; x < blueprint.getSizeX(); x++)
            {
                for (int z = 0; z < blueprint.getSizeZ(); z++)
                {
                    if (blueprint.getBlockState(new BlockPos(x, y, z)).is(WorkerEfficiencyGameTests.BUILD_BLOCK) && !live.level.getBlockState(origin.offset(x, y, z)).is(WorkerEfficiencyGameTests.BUILD_BLOCK))
                    {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** Scaffolding blocks anywhere around the site. */
    static int scaffoldInWorld(final WorkerEfficiencyGameTests.Live live, final BlockPos site)
    {
        int n = 0;
        for (final BlockPos pos : BlockPos.betweenClosed(site.offset(-18, -8, -18), site.offset(30, 34, 30)))
        {
            if (live.level.getBlockState(pos).is(Blocks.SCAFFOLDING))
            {
                n++;
            }
        }
        return n;
    }

    /** Scaffolding items in the huts and the inventories of the builders (and dropped items in the area). */
    static int scaffoldItems(final WorkerEfficiencyGameTests.Live live, final BlockPos site)
    {
        int n = 0;
        for (final var building : live.colony.getServerBuildingManager().getBuildings().values())
        {
            if (building instanceof BuildingBuilder hut)
            {
                n += InventoryUtils.getItemCountInItemHandler(hut.getItemHandlerCap(), stack -> stack.is(Items.SCAFFOLDING));
                for (final ICitizenData citizen : hut.getAllAssignedCitizen())
                {
                    n += InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), stack -> stack.is(Items.SCAFFOLDING));
                }
            }
        }
        for (final net.minecraft.world.entity.item.ItemEntity item : live.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
          net.minecraft.world.phys.AABB.encapsulatingFullBlocks(site.offset(-30, -10, -30), site.offset(40, 40, 40))))
        {
            if (item.getItem().is(Items.SCAFFOLDING))
            {
                n += item.getItem().getCount();
            }
        }
        return n;
    }

    static int scaffoldItemsInBuilders(final WorkerEfficiencyGameTests.Live live)
    {
        int n = 0;
        for (final var building : live.colony.getServerBuildingManager().getBuildings().values())
        {
            if (building instanceof BuildingBuilder hut)
            {
                for (final ICitizenData citizen : hut.getAllAssignedCitizen())
                {
                    n += InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), stack -> stack.is(Items.SCAFFOLDING));
                }
            }
        }
        return n;
    }

    static int ledgerSize(final WorkerEfficiencyGameTests.Live live)
    {
        int n = 0;
        for (final var order : live.colony.getWorkManager().getWorkOrders().values())
        {
            if (order instanceof IBuilderWorkOrder b)
            {
                n += b.getScaffolds().size();
            }
        }
        return n;
    }

    /** What a scaffolding test records while it runs. */
    static final class Watch
    {
        int maxScaffold = 0;
        int climbedTicks = 0;
        int maxHeight = 0;
        String conservationBreak = null;
        int orphanedTicks = 0;
        int minItems = Integer.MAX_VALUE;
    }

    static void watch(final WorkerEfficiencyGameTests.Live live, final Watch watch, final BlockPos site, final int total, final int tick)
    {
        live.citizen.getEntity().ifPresent(e -> {
            final BlockPos feet = e.blockPosition();
            if (live.level.getBlockState(feet.below()).is(Blocks.SCAFFOLDING) || live.level.getBlockState(feet).is(Blocks.SCAFFOLDING))
            {
                watch.climbedTicks++;
            }
            watch.maxHeight = Math.max(watch.maxHeight, feet.getY() - site.getY());
        });
        if (tick % 4 == 0)
        {
            final int inWorld = scaffoldInWorld(live, site);
            watch.maxScaffold = Math.max(watch.maxScaffold, inWorld);
            final int items = scaffoldItems(live, site);
            watch.minItems = Math.min(watch.minItems, items);
            if (watch.conservationBreak == null && inWorld + items != total)
            {
                watch.conservationBreak = "t=" + tick + " world=" + inWorld + " items=" + items + " expected " + total;
            }
        }
    }

    // ------------------------------------------------------------------ tall structures

    /**
     * A builder builds a hollow tower and a tall wall: the top rows are out of reach from the ground, so the builder puts up scaffolding, climbs
     * it and works from the top. Everything he did is checked: the structure is complete, no block was placed out of reach, the scaffolding is
     * all gone in the world and in the ledger, and the scaffolding items are all back (nothing lost, nothing made).
     */
    static void tallStructure(final GameTestHelper helper, final String name, final Supplier<Blueprint> blueprintOf, final boolean physical, final int maxTicks, final boolean expectScaffold)
    {
        WorkerSettings.overrideReachForTests(null, physical, null, null);
        ReachAudit.enable(true);
        BuilderScaffolding.resetCounters();
        WorkerEfficiencyGameTests.scene(helper, "WSCAF " + name, live -> {
            live.setSkills(40, 40);
            live.giveTools();
            final Blueprint blueprint = blueprintOf.get();
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), solidCount(blueprint) + WorkerEfficiencyGameTests.SPARE);
            live.stock(Items.SCAFFOLDING, 32);
            final WorkOrderDecoration order = live.orderFor(name, SITE, blueprint);
            final BlockPos origin = order.getLocation();
            final Watch watch = new Watch();
            final Vec3[] last = {live.citizen.getEntity().get().position()};
            final double[] walked = {0};
            final int[] doneTick = {0};
            final long paths0 = Pathfinding.Stats.submitted.get();
            WorkerEfficiencyGameTests.everyTick(live, maxTicks, tick -> {
                final Vec3 now = live.citizen.getEntity().map(e -> e.position()).orElse(last[0]);
                walked[0] += Math.hypot(now.x - last[0].x, now.z - last[0].z);
                last[0] = now;
                watch(live, watch, origin, 32, tick);
                if (tick % 500 == 0)
                {
                    Log.getLogger().info("WSCAF {} t={} missing={} scaffold={} ledger={} {}", name, tick, missing(live, blueprint, origin), scaffoldInWorld(live, origin), ledgerSize(live), live.ai());
                }
                doneTick[0] = tick;
                return live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder();
            }, () -> {
                final int blocks = solidCount(blueprint);
                Log.getLogger().info("WBENCH RESULT {} physical={} ticks={} blocks={} blocksPerMin={} walk={} paths={} towers={} scaffoldPlaced={} maxScaffold={} climbedTicks={} maxHeight={} audit: actions={} violations={} {}",
                  name, physical, doneTick[0], blocks, String.format("%.1f", blocks * 1200.0D / doneTick[0]), Math.round(walked[0]), Pathfinding.Stats.submitted.get() - paths0,
                  BuilderScaffolding.towersBuilt, BuilderScaffolding.cellsPlaced, watch.maxScaffold, watch.climbedTicks, watch.maxHeight, ReachAudit.actions(), ReachAudit.violations(), ReachAudit.examples());
                helper.assertTrue(built(live, blueprint, origin), "structure not complete: missing " + missing(live, blueprint, origin));
                helper.assertTrue(scaffoldInWorld(live, origin) == 0, "scaffolding left in the world: " + scaffoldInWorld(live, origin));
                helper.assertTrue(ledgerSize(live) == 0 && ((WorkManager) live.colony.getWorkManager()).getOrphanScaffolds().isEmpty(), "the ledger is not empty: " + ledgerSize(live));
                helper.assertTrue(scaffoldItems(live, origin) == 32, "scaffolding items not conserved: " + scaffoldItems(live, origin));
                helper.assertTrue(watch.conservationBreak == null, "scaffolding items were lost or made while building: " + watch.conservationBreak);
                if (physical)
                {
                    helper.assertTrue(ReachAudit.violations() == 0, "blocks were placed out of reach: " + ReachAudit.violations() + " " + ReachAudit.examples());
                }
                if (expectScaffold)
                {
                    helper.assertTrue(BuilderScaffolding.towersBuilt > 0 && watch.maxScaffold > 0, "no scaffolding was used: towers=" + BuilderScaffolding.towersBuilt);
                    helper.assertTrue(watch.climbedTicks > 0, "the builder never stood on his scaffolding");
                    helper.assertTrue(InventoryUtils.getItemCountInItemHandler(live.hut.getItemHandlerCap(), stack -> stack.is(Items.SCAFFOLDING)) == 32, "scaffolding not back in the hut: hut has "
                      + InventoryUtils.getItemCountInItemHandler(live.hut.getItemHandlerCap(), stack -> stack.is(Items.SCAFFOLDING)) + " builder has " + scaffoldItemsInBuilders(live));
                }
                BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
                WorkerSettings.clearOverrides();
                ReachAudit.enable(false);
                helper.succeed();
            }, () -> "missing=" + missing(live, blueprint, origin) + " scaffold=" + scaffoldInWorld(live, origin) + " ledger=" + ledgerSize(live) + " " + live.ai() + " audit=" + ReachAudit.violations()
                       + " " + ReachAudit.examples());
        });
    }

    public static void tallWall8(final GameTestHelper helper)
    {
        tallStructure(helper, "tall8", () -> ring("tall8", 8), true, 20_000, true);
    }

    public static void tallTower14(final GameTestHelper helper)
    {
        tallStructure(helper, "tall14", () -> ring("tall14", 14), true, 30_000, true);
    }

    public static void sealedRoof(final GameTestHelper helper)
    {
        tallStructure(helper, "sealed", () -> sealedBox("sealed"), true, 30_000, true);
    }

    public static void overhangSlab(final GameTestHelper helper)
    {
        tallStructure(helper, "overhang", () -> overhang("overhang"), true, 30_000, true);
    }

    /** The tall tower with the physical reach off: the L3 behaviour (the builder places from the ground), for the benchmark. */
    public static void tallTower14Legacy(final GameTestHelper helper)
    {
        tallStructure(helper, "tall14_legacy", () -> ring("tall14_legacy", 14), false, 30_000, false);
    }

    public static void tallWall8Legacy(final GameTestHelper helper)
    {
        tallStructure(helper, "tall8_legacy", () -> ring("tall8_legacy", 8), false, 30_000, false);
    }
}
