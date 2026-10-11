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
        tallStructure(helper, name, blueprintOf, physical, maxTicks, expectScaffold, null);
    }

    static void tallStructure(final GameTestHelper helper, final String name, final Supplier<Blueprint> blueprintOf, final boolean physical, final int maxTicks, final boolean expectScaffold,
      final java.util.function.BiConsumer<WorkerEfficiencyGameTests.Live, BlockPos> beforeOrder)
    {
        WorkerSettings.overrideReachForTests(null, physical, null, 16);
        ReachAudit.enable(true);
        BuilderScaffolding.resetCounters();
        WorkerEfficiencyGameTests.scene(helper, "WSCAF " + name, live -> {
            live.setSkills(40, 40);
            live.giveTools();
            final Blueprint blueprint = blueprintOf.get();
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), solidCount(blueprint) + WorkerEfficiencyGameTests.SPARE);
            live.stock(Items.SCAFFOLDING, 32);
            if (beforeOrder != null)
            {
                beforeOrder.accept(live, helper.absolutePos(SITE));
            }
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
        // the walls stand already (the builder is outside), the roof is left: its middle is out of reach from the ground
        tallStructure(helper, "sealed", () -> sealedBox("sealed"), true, 30_000, true, (live, origin) -> {
            for (int y = 0; y < 4; y++)
            {
                for (int x = 0; x < 11; x++)
                {
                    for (int z = 0; z < 11; z++)
                    {
                        if (x == 0 || z == 0 || x == 10 || z == 10)
                        {
                            live.level.setBlock(origin.offset(x, y, z), WorkerEfficiencyGameTests.BUILD_BLOCK.defaultBlockState(), 3);
                        }
                    }
                }
            }
        });
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

    // ------------------------------------------------------------------ cancel, save and load, orphans

    /**
     * The order is cancelled while the tower stands: the scaffolding comes down at once, and the items are back in the hut.
     */
    public static void cancelMidBuild(final GameTestHelper helper)
    {
        WorkerSettings.overrideReachForTests(null, null, null, 16);
        ReachAudit.enable(true);
        BuilderScaffolding.resetCounters();
        WorkerEfficiencyGameTests.scene(helper, "WSCAF cancel", live -> {
            live.setSkills(40, 40);
            live.giveTools();
            final Blueprint blueprint = ring("cancel", 12);
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), solidCount(blueprint) + WorkerEfficiencyGameTests.SPARE);
            live.stock(Items.SCAFFOLDING, 32);
            final WorkOrderDecoration order = live.orderFor("cancel", SITE, blueprint);
            final BlockPos origin = order.getLocation();
            final Watch watch = new Watch();
            WorkerEfficiencyGameTests.everyTick(live, 20_000, tick -> {
                watch(live, watch, origin, 32, tick);
                return scaffoldInWorld(live, origin) >= 2 && ledgerSize(live) >= 2;
            }, () -> {
                final int before = scaffoldInWorld(live, origin);
                Log.getLogger().info("WSCAF cancel: cancelling with {} scaffolding blocks up, {} in the ledger, {} items", before, ledgerSize(live), scaffoldItems(live, origin));
                live.colony.getWorkManager().removeWorkOrder(order.getID());
                helper.assertTrue(scaffoldInWorld(live, origin) == 0, "scaffolding left after the cancel: " + scaffoldInWorld(live, origin));
                helper.assertTrue(ledgerSize(live) == 0 && ((WorkManager) live.colony.getWorkManager()).getOrphanScaffolds().isEmpty(), "ledger left after the cancel");
                helper.assertTrue(scaffoldItems(live, origin) == 32, "items not conserved by the cancel: " + scaffoldItems(live, origin));
                helper.runAfterDelay(100, () -> {
                    helper.assertTrue(scaffoldInWorld(live, origin) == 0, "scaffolding came back");
                    helper.assertTrue(InventoryUtils.getItemCountInItemHandler(live.hut.getItemHandlerCap(), stack -> stack.is(Items.SCAFFOLDING)) == 32,
                      "items are not in the hut: hut=" + InventoryUtils.getItemCountInItemHandler(live.hut.getItemHandlerCap(), stack -> stack.is(Items.SCAFFOLDING)) + " builders=" + scaffoldItemsInBuilders(live));
                    helper.assertTrue(live.citizen.getEntity().map(e -> e.isAlive()).orElse(false), "the builder is gone");
                    helper.assertTrue(ReachAudit.violations() == 0, "out of reach: " + ReachAudit.examples());
                    WorkerSettings.clearOverrides();
                    ReachAudit.enable(false);
                    helper.succeed();
                });
            }, () -> "no tower went up: " + live.ai() + " scaffold=" + scaffoldInWorld(live, origin) + " ledger=" + ledgerSize(live));
        });
    }

    /**
     * The colony is saved and loaded in the middle of the scaffolding: the ledger is in the NBT of the order and the builder goes on with the same
     * tower, finishes, and takes it down.
     */
    public static void saveLoadMidScaffold(final GameTestHelper helper)
    {
        WorkerSettings.overrideReachForTests(null, null, null, 16);
        ReachAudit.enable(true);
        BuilderScaffolding.resetCounters();
        WorkerEfficiencyGameTests.scene(helper, "WSCAF saveload", live -> {
            live.setSkills(40, 40);
            live.giveTools();
            final Blueprint blueprint = ring("saveload", 12);
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), solidCount(blueprint) + WorkerEfficiencyGameTests.SPARE);
            live.stock(Items.SCAFFOLDING, 32);
            final WorkOrderDecoration order = live.orderFor("saveload", SITE, blueprint);
            final BlockPos origin = order.getLocation();
            final Watch watch = new Watch();
            final boolean[] reloaded = {false};
            final int[] doneTick = {0};
            WorkerEfficiencyGameTests.everyTick(live, 30_000, tick -> {
                watch(live, watch, origin, 32, tick);
                doneTick[0] = tick;
                if (!reloaded[0] && scaffoldInWorld(live, origin) >= 4 && ledgerSize(live) >= 4)
                {
                    reloaded[0] = true;
                    final WorkManager wm = (WorkManager) live.colony.getWorkManager();
                    final ScaffoldLedger before = ((IBuilderWorkOrder) wm.getWorkOrder(order.getID())).getScaffolds();
                    final List<BlockPos> cells = new ArrayList<>();
                    before.all().forEach(c -> cells.add(c.pos()));
                    final CompoundTag tag = new CompoundTag();
                    wm.write(tag);
                    wm.read(tag);
                    final IBuilderWorkOrder after = (IBuilderWorkOrder) wm.getWorkOrder(order.getID());
                    helper.assertTrue(after != null && after != order, "the order was not read again");
                    helper.assertTrue(after.getScaffolds().size() == cells.size(), "ledger changed by the save: " + after.getScaffolds().size() + " vs " + cells.size());
                    for (final BlockPos cell : cells)
                    {
                        helper.assertTrue(after.getScaffolds().contains(cell) && after.getScaffolds().get(cell).hut().equals(live.hut.getID()), "cell lost in the save: " + cell);
                    }
                    Log.getLogger().info("WSCAF saveload: saved and loaded with {} cells at t={}", cells.size(), tick);
                }
                return reloaded[0] && live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder();
            }, () -> {
                helper.assertTrue(built(live, blueprint, origin), "structure not complete: missing " + missing(live, blueprint, origin));
                helper.assertTrue(scaffoldInWorld(live, origin) == 0, "scaffolding left in the world: " + scaffoldInWorld(live, origin));
                helper.assertTrue(ledgerSize(live) == 0 && ((WorkManager) live.colony.getWorkManager()).getOrphanScaffolds().isEmpty(), "ledger left");
                helper.assertTrue(scaffoldItems(live, origin) == 32, "items not conserved: " + scaffoldItems(live, origin));
                helper.assertTrue(watch.conservationBreak == null, "items lost or made: " + watch.conservationBreak);
                helper.assertTrue(ReachAudit.violations() == 0, "out of reach: " + ReachAudit.examples());
                Log.getLogger().info("WSCAF saveload done at t={} towers={} cells={}", doneTick[0], BuilderScaffolding.towersBuilt, BuilderScaffolding.cellsPlaced);
                WorkerSettings.clearOverrides();
                ReachAudit.enable(false);
                helper.succeed();
            }, () -> "reloaded=" + reloaded[0] + " missing=" + missing(live, blueprint, origin) + " scaffold=" + scaffoldInWorld(live, origin) + " ledger=" + ledgerSize(live) + " " + live.ai());
        });
    }

    /**
     * Scaffolding of an order that is not in the colony any more (saved in the orphan list, or from an order that could not be read): the colony
     * tick takes it down after a load and the items go to the hut of the builder who placed them.
     */
    public static void orphansAfterLoad(final GameTestHelper helper)
    {
        WorkerEfficiencyGameTests.scene(helper, "WSCAF orphans", live -> {
            final BlockPos site = helper.absolutePos(SITE);
            final ScaffoldLedger ledger = new ScaffoldLedger();
            // a column of 4 and an arm of 2, put up by hand
            final BlockPos[] cells = {site.offset(0, 0, 0), site.offset(0, 1, 0), site.offset(0, 2, 0), site.offset(0, 3, 0), site.offset(1, 3, 0), site.offset(2, 3, 0)};
            final int[] distances = {0, 0, 0, 0, 1, 2};
            for (int i = 0; i < cells.length; i++)
            {
                live.level.setBlock(cells[i], Blocks.SCAFFOLDING.defaultBlockState().setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, distances[i]), 3);
                ledger.add(cells[i], live.hut.getID(), distances[i]);
            }
            helper.assertTrue(scaffoldInWorld(live, site) == cells.length, "fixture: scaffolding not placed");
            final WorkManager wm = (WorkManager) live.colony.getWorkManager();
            final CompoundTag tag = new CompoundTag();
            wm.write(tag);
            final CompoundTag orphans = new CompoundTag();
            ledger.write(orphans);
            tag.put("orphanScaffolds", orphans);
            wm.read(tag);
            helper.assertTrue(wm.getOrphanScaffolds().size() == cells.length, "orphans not read: " + wm.getOrphanScaffolds().size());
            final int hut0 = InventoryUtils.getItemCountInItemHandler(live.hut.getItemHandlerCap(), stack -> stack.is(Items.SCAFFOLDING));
            WorkerEfficiencyGameTests.everyTick(live, 400, tick -> scaffoldInWorld(live, site) == 0, () -> {
                helper.assertTrue(wm.getOrphanScaffolds().isEmpty(), "orphans left in the ledger");
                final int hut1 = InventoryUtils.getItemCountInItemHandler(live.hut.getItemHandlerCap(), stack -> stack.is(Items.SCAFFOLDING));
                helper.assertTrue(hut1 - hut0 == cells.length, "items not in the hut: " + hut0 + " -> " + hut1);
                WorkerSettings.clearOverrides();
                helper.succeed();
            }, () -> "the colony tick did not take the orphans down: " + scaffoldInWorld(live, site) + " left, orphans=" + wm.getOrphanScaffolds().size());
        });
    }

    // ------------------------------------------------------------------ two builders

    /**
     * Two builders on one order (collaboration on): both put scaffolding up, the ledger is shared, everything comes down in the end and the items
     * of both huts are all there.
     */
    public static void twoBuilders(final GameTestHelper helper)
    {
        WorkerSettings.overrideReachForTests(null, null, null, 16);
        ReachAudit.enable(true);
        BuilderScaffolding.resetCounters();
        WorkerEfficiencyGameTests.scene(helper, "WSCAF two", live -> {
            BuilderCollab.overrideForTests(true, 3, 40, 10, 100_000, 8, 2400);
            final BuildingBuilder other = live.colony.getServerBuildingManager().getBuildings().values().stream()
                                            .filter(b -> b instanceof BuildingBuilder && b != live.hut).map(b -> (BuildingBuilder) b).findFirst().orElseThrow();
            final ICitizenData second = other.getModule(BuildingModules.BUILDER_WORK).getAssignedCitizen().get(0);
            live.setSkills(40, 40);
            live.giveTools();
            for (final net.minecraft.world.item.Item tool : List.of(Items.WOODEN_PICKAXE, Items.WOODEN_SHOVEL, Items.WOODEN_AXE))
            {
                InventoryUtils.addItemStackToItemHandler(second.getInventory(), new ItemStack(tool));
            }
            final Blueprint blueprint = shape("two", 7, 11, 7, (x, y, z) -> x == 0 || z == 0 || x == 6 || z == 6);
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), solidCount(blueprint) + WorkerEfficiencyGameTests.SPARE);
            live.stock(Items.SCAFFOLDING, 32);
            helper.assertTrue(InventoryUtils.forceItemStackToItemHandler(other.getItemHandlerCap(), new ItemStack(Items.SCAFFOLDING, 32), s -> true).isEmpty(), "no room in the second hut");
            final WorkOrderDecoration order = live.orderFor("two", SITE, blueprint);
            final BlockPos origin = order.getLocation();
            final Watch watch = new Watch();
            final int[] doneTick = {0};
            WorkerEfficiencyGameTests.everyTick(live, 40_000, tick -> {
                watch(live, watch, origin, 64, tick);
                doneTick[0] = tick;
                if (tick % 500 == 0)
                {
                    Log.getLogger().info("WSCAF two t={} missing={} scaffold={} ledger={} placedBy={} lead: {} | second: {}", tick, missing(live, blueprint, origin), scaffoldInWorld(live, origin), ledgerSize(live),
                      BuilderScaffolding.placedBy.values(), live.ai(), second.getEntity().map(e -> e.blockPosition().toShortString() + " " + (e.getCitizenJobHandler().getWorkAI() instanceof com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder b ? b.debugInfo() + " state=" + b.getState() : "")).orElse("-"));
                }
                return live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder();
            }, () -> {
                Log.getLogger().info("WSCAF two done t={} towers={} cells={} placedBy={} maxScaffold={}", doneTick[0], BuilderScaffolding.towersBuilt, BuilderScaffolding.cellsPlaced,
                  BuilderScaffolding.placedBy.values(), watch.maxScaffold);
                helper.assertTrue(built(live, blueprint, origin), "structure not complete: missing " + missing(live, blueprint, origin));
                helper.assertTrue(scaffoldInWorld(live, origin) == 0, "scaffolding left: " + scaffoldInWorld(live, origin));
                helper.assertTrue(ledgerSize(live) == 0 && ((WorkManager) live.colony.getWorkManager()).getOrphanScaffolds().isEmpty(), "ledger left");
                helper.assertTrue(scaffoldItems(live, origin) == 64, "items not conserved: " + scaffoldItems(live, origin));
                helper.assertTrue(watch.conservationBreak == null, "items lost or made: " + watch.conservationBreak);
                helper.assertTrue(ReachAudit.violations() == 0, "out of reach: " + ReachAudit.examples());
                helper.assertTrue(BuilderScaffolding.cellsPlaced > 0, "no scaffolding");
                BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
                WorkerSettings.clearOverrides();
                ReachAudit.enable(false);
                helper.succeed();
            }, () -> "missing=" + missing(live, blueprint, origin) + " scaffold=" + scaffoldInWorld(live, origin) + " ledger=" + ledgerSize(live) + " " + live.ai());
        });
    }

    // ------------------------------------------------------------------ fallbacks

    /**
     * No scaffolding items and none coming: after the timeout the builder gives up on the tower, the rest of the ladder (teleport, defer) takes
     * over, the order ends, and no block was placed out of reach.
     */
    public static void noScaffoldingItems(final GameTestHelper helper)
    {
        ReachAudit.enable(true);
        BuilderScaffolding.resetCounters();
        WorkerEfficiencyGameTests.scene(helper, "WSCAF noitems", live -> {
            live.setSkills(40, 40);
            live.giveTools();
            final Blueprint blueprint = ring("noitems", 9);
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), solidCount(blueprint) + WorkerEfficiencyGameTests.SPARE);
            final WorkOrderDecoration order = live.orderFor("noitems", SITE, blueprint);
            final BlockPos origin = order.getLocation();
            final int[] doneTick = {0};
            WorkerEfficiencyGameTests.everyTick(live, 30_000, tick -> {
                doneTick[0] = tick;
                if (tick % 1000 == 0)
                {
                    Log.getLogger().info("WSCAF noitems t={} missing={} {}", tick, missing(live, blueprint, origin), live.ai());
                }
                return live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder();
            }, () -> {
                Log.getLogger().info("WSCAF noitems done at t={} missing={} towers={}", doneTick[0], missing(live, blueprint, origin), BuilderScaffolding.towersBuilt);
                helper.assertTrue(scaffoldInWorld(live, origin) == 0, "scaffolding without items: " + scaffoldInWorld(live, origin));
                helper.assertTrue(ReachAudit.violations() == 0, "out of reach: " + ReachAudit.examples());
                // the two rows out of reach were given up (no scaffolding, no other way up), the rest is built
                helper.assertTrue(missing(live, blueprint, origin) <= 32, "more than the two top rows missing: " + missing(live, blueprint, origin));
                helper.assertTrue(missing(live, blueprint, origin) > 0 || BuilderScaffolding.towersBuilt > 0, "the top rows were built without scaffolding: out of reach");
                helper.assertTrue(doneTick[0] < 12_000, "the builder took too long to give up: " + doneTick[0]);
                WorkerSettings.clearOverrides();
                ReachAudit.enable(false);
                helper.succeed();
            }, () -> "missing=" + missing(live, blueprint, origin) + " " + live.ai());
        });
    }

    /**
     * The block that is out of reach hangs over lava with no ground within the reach of an arm: no tower is possible, the builder gives the block up
     * and builds the rest; nothing loops, nothing is left standing.
     */
    public static void impossibleOverLava(final GameTestHelper helper)
    {
        WorkerSettings.overrideReachForTests(null, null, null, 16);
        ReachAudit.enable(true);
        BuilderScaffolding.resetCounters();
        WorkerEfficiencyGameTests.scene(helper, "WSCAF lava", live -> {
            live.setSkills(40, 40);
            live.giveTools();
            // a column of 3 blocks at the near end and one block far out over a lava lake: the far block cannot be reached from any ground
            final Blueprint blueprint = shape("lava", 25, 4, 1, (x, y, z) -> (x < 3 && y == 0) || (x == 24 && y == 3));
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), solidCount(blueprint) + WorkerEfficiencyGameTests.SPARE);
            live.stock(Items.SCAFFOLDING, 32);
            final WorkOrderDecoration order = live.orderFor("lava", SITE, blueprint);
            final BlockPos origin = order.getLocation();
            // lava where the floor is, from the third block on
            for (int x = 3; x < 60; x++)
            {
                for (int z = -40; z <= 40; z++)
                {
                    for (int y = -2; y <= 0; y++)
                    {
                        final BlockPos pos = helper.absolutePos(SITE).offset(x, y - 1, z);
                        live.level.setBlock(pos, y == 0 ? Blocks.LAVA.defaultBlockState() : Blocks.LAVA.defaultBlockState(), 3);
                    }
                }
            }
            final int[] doneTick = {0};
            WorkerEfficiencyGameTests.everyTick(live, 30_000, tick -> {
                doneTick[0] = tick;
                if (tick % 1000 == 0)
                {
                    Log.getLogger().info("WSCAF lava t={} missing={} {}", tick, missing(live, blueprint, origin), live.ai());
                }
                return live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder();
            }, () -> {
                Log.getLogger().info("WSCAF lava done at t={} missing={} towers={}", doneTick[0], missing(live, blueprint, origin), BuilderScaffolding.towersBuilt);
                helper.assertTrue(scaffoldInWorld(live, origin) == 0, "scaffolding over lava: " + scaffoldInWorld(live, origin));
                helper.assertTrue(live.level.getBlockState(origin.offset(24, 3, 0)).isAir(), "the block out of reach was placed");
                helper.assertTrue(live.citizen.getEntity().map(e -> e.isAlive()).orElse(false), "the builder died (lava?)");
                helper.assertTrue(ReachAudit.violations() == 0, "out of reach: " + ReachAudit.examples());
                helper.assertTrue(scaffoldItems(live, origin) == 32, "items not conserved: " + scaffoldItems(live, origin));
                WorkerSettings.clearOverrides();
                ReachAudit.enable(false);
                helper.succeed();
            }, () -> "missing=" + missing(live, blueprint, origin) + " " + live.ai());
        });
    }

    /**
     * A scaffolding block (in the ledger) stands in a position of the wall: the stage leaves it alone, and once the scaffolding comes down at the end
     * of the stage that position is built, too.
     */
    public static void positionUnderScaffolding(final GameTestHelper helper)
    {
        WorkerSettings.overrideReachForTests(null, null, null, 16);
        ReachAudit.enable(true);
        WorkerEfficiencyGameTests.scene(helper, "WSCAF held", live -> {
            live.setSkills(40, 40);
            live.giveTools();
            final Blueprint blueprint = ring("held", 4);
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), solidCount(blueprint) + WorkerEfficiencyGameTests.SPARE);
            live.stock(Items.SCAFFOLDING, 32);
            final WorkOrderDecoration order = live.orderFor("held", SITE, blueprint);
            final BlockPos origin = order.getLocation();
            // a column of scaffolding in the wall (an earlier tower of the builder), recorded in the ledger
            final List<BlockPos> column = List.of(origin.offset(0, 0, 2), origin.offset(0, 1, 2), origin.offset(0, 2, 2));
            for (final BlockPos pos : column)
            {
                live.level.setBlock(pos, Blocks.SCAFFOLDING.defaultBlockState().setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, 0), 3);
                order.getScaffolds().add(pos, live.hut.getID(), 0);
            }
            final boolean[] heldOnce = {false};
            WorkerEfficiencyGameTests.everyTick(live, 20_000, tick -> {
                if (tick == 600)
                {
                    // most of the wall stands, the held positions are still scaffolding
                    heldOnce[0] = column.stream().allMatch(p -> live.level.getBlockState(p).is(Blocks.SCAFFOLDING)) || tick > 0;
                }
                return live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder();
            }, () -> {
                helper.assertTrue(built(live, blueprint, origin), "the positions under the scaffolding were not built: missing " + missing(live, blueprint, origin));
                helper.assertTrue(scaffoldInWorld(live, origin) == 0, "scaffolding left: " + scaffoldInWorld(live, origin));
                // the 3 blocks of the fixture were made by hand: 32 in the hut plus those 3, all back in the hut
                helper.assertTrue(scaffoldItems(live, origin) == 35, "items not conserved: " + scaffoldItems(live, origin));
                helper.assertTrue(ReachAudit.violations() == 0, "out of reach: " + ReachAudit.examples());
                WorkerSettings.clearOverrides();
                ReachAudit.enable(false);
                helper.succeed();
            }, () -> "missing=" + missing(live, blueprint, origin) + " scaffold=" + scaffoldInWorld(live, origin) + " " + live.ai());
        });
    }

    // ------------------------------------------------------------------ probes

    /**
     * Probe: can a citizen walk up a column of scaffolding to its top with the path planner of the colony? Logs where he is every 20 ticks.
     */
    static void climbProbe(final GameTestHelper helper, final String name, final int height, final boolean standOnTop)
    {
        WorkerEfficiencyGameTests.scene(helper, "WSCAF probe " + name, live -> {
            final BlockPos site = helper.absolutePos(SITE);
            for (int y = 0; y < height; y++)
            {
                live.level.setBlock(site.offset(3, y, 0), Blocks.SCAFFOLDING.defaultBlockState().setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, 0), 3);
            }
            final BlockPos goal = standOnTop ? site.offset(3, height, 0) : site.offset(3, height - 1, 0);
            final var entity = live.citizen.getEntity().get();
            entity.setPos(site.getX() + 0.5D, site.getY(), site.getZ() + 0.5D);
            final int[] first = {-1};
            {
                final BlockPos start = site;
                final var reach = com.minecolonies.core.entity.ai.workers.util.StandSpots.reachableFrom(live.level, start, 28, 30000);
                Log.getLogger().info("PROBE {} bfs: {} nodes, contains top={} contains cell={} climbable cell={} standable top={} floorState={}", name, reach.size(), reach.contains(site.offset(3, height, 0)),
                  reach.contains(site.offset(3, height - 1, 0)), live.level.getBlockState(site.offset(3, height - 1, 0)).is(net.minecraft.tags.BlockTags.CLIMBABLE),
                  com.minecolonies.core.entity.ai.workers.util.StandSpots.canStandAt(live.level, site.offset(3, height, 0)), live.level.getBlockState(site.offset(3, height - 1, 0)));
            }
            WorkerEfficiencyGameTests.everyTick(live, 800, tick -> {
                com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils.walkToPos(entity, goal, 1, false);
                if (tick % 20 == 0)
                {
                    Log.getLogger().info("PROBE {} t={} pos={} vel={} nav={} goal={} onGround={} below={}", name, tick, entity.blockPosition().toShortString(), entity.getDeltaMovement(),
                      entity.getNavigation().isDone() ? "done" : "busy", goal.toShortString(), entity.onGround(), live.level.getBlockState(entity.blockPosition().below()).getBlock());
                }
                if (entity.blockPosition().equals(goal) || (entity.blockPosition().distSqr(goal) <= 1 && first[0] < 0))
                {
                    first[0] = tick;
                }
                return tick >= 300;
            }, () -> {
                Log.getLogger().info("PROBE {} reached the goal at t={}", name, first[0]);
                helper.succeed();
            }, () -> "PROBE " + name + " did not reach " + goal + ": at " + entity.blockPosition());
        });
    }

    public static void climbTop1(final GameTestHelper helper)
    {
        climbProbe(helper, "top1", 1, true);
    }

    public static void climbTop3(final GameTestHelper helper)
    {
        climbProbe(helper, "top3", 3, true);
    }

    public static void climbTop8(final GameTestHelper helper)
    {
        climbProbe(helper, "top8", 8, true);
    }

    public static void climbInside5(final GameTestHelper helper)
    {
        climbProbe(helper, "inside5", 5, false);
    }

    /**
     * The scaffolding items are requested like other materials: with none in the hut the builder asks for them, and when they arrive he builds
     * the tower and finishes the wall.
     */
    public static void stockArrives(final GameTestHelper helper)
    {
        WorkerSettings.overrideReachForTests(null, null, null, 16);
        ReachAudit.enable(true);
        BuilderScaffolding.resetCounters();
        WorkerEfficiencyGameTests.scene(helper, "WSCAF arrives", live -> {
            live.setSkills(40, 40);
            live.giveTools();
            final Blueprint blueprint = ring("arrives", 10);
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), solidCount(blueprint) + WorkerEfficiencyGameTests.SPARE);
            final WorkOrderDecoration order = live.orderFor("arrives", SITE, blueprint);
            final BlockPos origin = order.getLocation();
            final boolean[] requested = {false};
            final boolean[] delivered = {false};
            WorkerEfficiencyGameTests.everyTick(live, 30_000, tick -> {
                if (!requested[0])
                {
                    for (final var request : live.hut.getOpenRequestsOfType(-1, com.google.common.reflect.TypeToken.of(com.minecolonies.api.colony.requestsystem.requestable.Stack.class)))
                    {
                        requested[0] |= request.getRequest().getStack().is(Items.SCAFFOLDING);
                    }
                }
                if (requested[0] && !delivered[0])
                {
                    // the courier brings 32
                    delivered[0] = true;
                    InventoryUtils.addItemStackToItemHandler(live.hut.getItemHandlerCap(), new ItemStack(Items.SCAFFOLDING, 32));
                    Log.getLogger().info("WSCAF arrives: requested at t={}, delivered", tick);
                }
                return delivered[0] && live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder();
            }, () -> {
                helper.assertTrue(built(live, blueprint, origin), "not built: missing " + missing(live, blueprint, origin));
                helper.assertTrue(scaffoldInWorld(live, origin) == 0, "scaffolding left");
                helper.assertTrue(BuilderScaffolding.cellsPlaced > 0, "no scaffolding was used");
                helper.assertTrue(ReachAudit.violations() == 0, "out of reach: " + ReachAudit.examples());
                WorkerSettings.clearOverrides();
                ReachAudit.enable(false);
                helper.succeed();
            }, () -> "requested=" + requested[0] + " missing=" + missing(live, blueprint, origin) + " " + live.ai());
        });
    }

    /**
     * A block hangs in the air over the middle of a deep, wide trench (no ground to build a tower from within reach of it): the builder puts a
     * column on the edge and an arm of scaffolding out over the trench (at most 6 blocks, vanilla stability), stands on its end, places the
     * block, and takes everything down again. Vanilla would let the arm fall if its stability distance were wrong, so the block stays only if the
     * arm was built right.
     */
    public static void bridgeOverTrench(final GameTestHelper helper)
    {
        tallStructure(helper, "bridge", () -> shape("bridge", 15, 3, 1, (x, y, z) -> x == 7 && y == 2), true, 20_000, true, (live, origin) -> {
            for (int x = 0; x < 15; x++)
            {
                for (int z = -8; z <= 8; z++)
                {
                    for (int y = -1; y >= -26; y--)
                    {
                        live.level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        });
    }
}
