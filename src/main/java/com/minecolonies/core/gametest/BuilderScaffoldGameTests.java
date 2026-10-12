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
import net.minecraft.world.level.block.state.BlockState;
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

    public static void legacyClearL40(final GameTestHelper helper)
    {
        boxAudit(helper, "legacy_clear_l40", false, 40, true);
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
                    // (a cell of a real blueprint that is empty holds air; the default of a new blueprint is a substitution block, which is something else)
                    blueprint.addBlockState(new BlockPos(x, y, z), solid.at(x, y, z) ? WorkerEfficiencyGameTests.BUILD_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
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

    /** A development trace of the builder every few ticks ({@code -Dminecolonies.gametest.trace=N}): where he is relative to the site, what the AI does. */
    static final int TRACE_EVERY = Integer.getInteger("minecolonies.gametest.trace", 0);

    static void trace(final WorkerEfficiencyGameTests.Live live, final String name, final int tick, final BlockPos origin)
    {
        if (TRACE_EVERY <= 0 || tick % TRACE_EVERY != 0)
        {
            return;
        }
        live.citizen.getEntity().ifPresent(e -> trace(e, name, tick, origin));
    }

    static void trace(final com.minecolonies.api.entity.citizen.AbstractEntityCitizen e, final String name, final int tick, final BlockPos origin)
    {
        {
            final Vec3 p = e.position();
            final String ai = e.getCitizenJobHandler().getWorkAI() instanceof com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder b ? b.getState() + " " + b.debugInfo() : "";
            final Vec3 dm = e.getDeltaMovement();
            Log.getLogger().info("TRACE {} {} t={} rel=({},{},{}) dm=({},{},{}) climb={} hc={} ground={} {}", name, e.getName().getString(), tick, String.format("%.1f", p.x - origin.getX()), String.format("%.1f", p.y - origin.getY()),
              String.format("%.1f", p.z - origin.getZ()), String.format("%.2f", dm.x), String.format("%.2f", dm.y), String.format("%.2f", dm.z), e.onClimbable(), e.horizontalCollision, e.onGround(), ai);
        }
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
        tallStructure(helper, name, blueprintOf, physical, maxTicks, expectScaffold, beforeOrder, null);
    }

    static void tallStructure(final GameTestHelper helper, final String name, final Supplier<Blueprint> blueprintOf, final boolean physical, final int maxTicks, final boolean expectScaffold,
      final java.util.function.BiConsumer<WorkerEfficiencyGameTests.Live, BlockPos> beforeOrder, final Runnable extraChecks)
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
                trace(live, name, tick, origin);
                if (tick == 1)
                {
                    Log.getLogger().info("WSCAF {} start gameTime={} origin={} size={}x{}x{} hut={} unsetState={}", name, live.level.getGameTime(), origin, blueprint.getSizeX(), blueprint.getSizeY(), blueprint.getSizeZ(), live.hut.getPosition(), blueprint.getBlockState(new BlockPos(blueprint.getSizeX() - 1, 1, blueprint.getSizeZ() - 1)));
                }
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
                if (extraChecks != null)
                {
                    extraChecks.run();
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

    private static void buildSealedWalls(final WorkerEfficiencyGameTests.Live live, final BlockPos origin)
    {
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
    }

    public static void sealedRoof(final GameTestHelper helper)
    {
        // the walls stand already (the builder is outside), the roof is left: its middle is out of reach from the ground
        tallStructure(helper, "sealed", () -> sealedBox("sealed"), true, 30_000, true, BuilderScaffoldGameTests::buildSealedWalls);
    }

    public static void sealedRoofLegacy(final GameTestHelper helper)
    {
        tallStructure(helper, "sealed_legacy", () -> sealedBox("sealed_legacy"), false, 30_000, false, BuilderScaffoldGameTests::buildSealedWalls);
    }

    public static void overhangLegacy(final GameTestHelper helper)
    {
        tallStructure(helper, "overhang_legacy", () -> overhang("overhang_legacy"), false, 30_000, false);
    }

    public static void overhangSlab(final GameTestHelper helper)
    {
        tallStructure(helper, "overhang", () -> overhang("overhang"), true, 30_000, true);
    }

    /** The ring of the big two-builder test with one builder (for the benchmark: what a second builder is worth). */
    public static void oneBuilderBig(final GameTestHelper helper)
    {
        tallStructure(helper, "onebig", () -> shape("onebig", 11, 12, 11, (x, y, z) -> x == 0 || z == 0 || x == 10 || z == 10), true, 40_000, true);
    }

    public static void oneBuilderBigLegacy(final GameTestHelper helper)
    {
        tallStructure(helper, "onebig_legacy", () -> shape("onebig_legacy", 11, 12, 11, (x, y, z) -> x == 0 || z == 0 || x == 10 || z == 10), false, 40_000, false);
    }

    /**
     * The ground the clear stage removes is no base for a tower: a ledge of dirt inside the volume of the structure (reached by a ramp from outside)
     * is the nearest ground to a pillar of dirt that has to be mined from the top, but it is mined away later, so no scaffolding may ever stand on
     * it. The builder mines the pillar from a tower on the ground next to it, everything is cleared, the scaffolding comes down whole.
     */
    public static void clearNextToALedge(final GameTestHelper helper)
    {
        final boolean[] onTheLedge = {false};
        tallStructure(helper, "ledge", () -> shape("ledge", 9, 12, 9, (x, y, z) -> false), true, 30_000, true, (live, origin) -> {
            final BlockPos site = helper.absolutePos(SITE);
            final BlockState dirt = Blocks.DIRT.defaultBlockState();
            for (int z = 3; z <= 5; z++)
            {
                // the ramp (outside of the volume) and the ledge (inside it), steps of one block
                for (int x = -3; x <= 3; x++)
                {
                    final int height = x < 0 ? 4 + x : 4;
                    for (int y = 0; y < height; y++)
                    {
                        live.level.setBlock(site.offset(x, y, z), dirt, 2);
                    }
                }
            }
            for (int y = 0; y < 10; y++)
            {
                live.level.setBlock(site.offset(7, y, 4), dirt, 2);
            }
            BuilderScaffolding.cellProbe = cell -> {
                final BlockPos rel = cell.subtract(site);
                if (rel.getX() >= 0 && rel.getX() <= 3 && rel.getZ() >= 3 && rel.getZ() <= 5 && rel.getY() >= 4 && rel.getY() <= 9)
                {
                    onTheLedge[0] = true;
                }
            };
        }, () -> {
            BuilderScaffolding.cellProbe = null;
            helper.assertFalse(onTheLedge[0], "scaffolding stood on the ledge that the clear stage removes");
        });
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
        twoBuilders(helper, "two", () -> shape("two", 7, 11, 7, (x, y, z) -> x == 0 || z == 0 || x == 6 || z == 6), 40_000);
    }

    /** The 11 by 11 ring, 12 high, with two builders. */
    public static void twoBuildersBig(final GameTestHelper helper)
    {
        twoBuilders(helper, "twobig", () -> shape("twobig", 11, 12, 11, (x, y, z) -> x == 0 || z == 0 || x == 10 || z == 10), 25_000);
    }

    static void twoBuilders(final GameTestHelper helper, final String name, final Supplier<Blueprint> blueprintOf, final int maxTicks)
    {
        WorkerSettings.overrideReachForTests(null, null, null, 16);
        ReachAudit.enable(true);
        BuilderScaffolding.resetCounters();
        WorkerEfficiencyGameTests.scene(helper, "WSCAF " + name, live -> {
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
            final Blueprint blueprint = blueprintOf.get();
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), solidCount(blueprint) + WorkerEfficiencyGameTests.SPARE);
            live.stock(Items.SCAFFOLDING, 32);
            helper.assertTrue(InventoryUtils.forceItemStackToItemHandler(other.getItemHandlerCap(), new ItemStack(Items.SCAFFOLDING, 32), s -> true).isEmpty(), "no room in the second hut");
            final WorkOrderDecoration order = live.orderFor(name, SITE, blueprint);
            final BlockPos origin = order.getLocation();
            final Watch watch = new Watch();
            final int[] doneTick = {0};
            final java.util.Map<BlockPos, Integer> blocksBy = new java.util.concurrent.ConcurrentHashMap<>();
            BuilderCollab.placementProbe = (pos, hut) -> {
                if (pos.distManhattan(origin) < 200)
                {
                    blocksBy.merge(hut, 1, Integer::sum);
                }
            };
            WorkerEfficiencyGameTests.everyTick(live, maxTicks, tick -> {
                watch(live, watch, origin, 64, tick);
                trace(live, name, tick, origin);
                if (TRACE_EVERY > 0 && tick % TRACE_EVERY == 0)
                {
                    second.getEntity().ifPresent(e -> trace(e, name + "-2", tick, origin));
                }
                doneTick[0] = tick;
                if (tick == 1)
                {
                    Log.getLogger().info("WSCAF {} start gameTime={} origin={} size={}x{}x{} hut={} other={}", name, live.level.getGameTime(), origin, blueprint.getSizeX(), blueprint.getSizeY(), blueprint.getSizeZ(), live.hut.getPosition(), other.getPosition());
                }
                if (tick % 500 == 0)
                {
                    Log.getLogger().info("WSCAF two t={} blocksBy(lead,second)={},{} missing={} scaffold={} ledger={} placedBy={} lead: {} | second: {}", tick, blocksBy.getOrDefault(live.hut.getPosition(), 0), blocksBy.getOrDefault(other.getPosition(), 0), missing(live, blueprint, origin), scaffoldInWorld(live, origin), ledgerSize(live),
                      BuilderScaffolding.placedBy.values(), live.ai(), second.getEntity().map(e -> e.blockPosition().toShortString() + " " + (e.getCitizenJobHandler().getWorkAI() instanceof com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder b ? b.debugInfo() + " state=" + b.getState() : "")).orElse("-"));
                }
                return live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder();
            }, () -> {
                Log.getLogger().info("WBENCH RESULT " + name + " builders=2 blocksBy={} ticks={} towers={} cells={} placedBy={} maxScaffold={}", blocksBy.values(), doneTick[0], BuilderScaffolding.towersBuilt, BuilderScaffolding.cellsPlaced,
                  BuilderScaffolding.placedBy.values(), watch.maxScaffold);
                helper.assertTrue(missing(live, blueprint, origin) == 0, "structure not complete: missing " + missing(live, blueprint, origin));
                helper.assertTrue(scaffoldInWorld(live, origin) == 0, "scaffolding left: " + scaffoldInWorld(live, origin));
                helper.assertTrue(ledgerSize(live) == 0 && ((WorkManager) live.colony.getWorkManager()).getOrphanScaffolds().isEmpty(), "ledger left");
                helper.assertTrue(scaffoldItems(live, origin) == 64, "items not conserved: " + scaffoldItems(live, origin));
                helper.assertTrue(watch.conservationBreak == null, "items lost or made: " + watch.conservationBreak);
                helper.assertTrue(ReachAudit.violations() == 0, "out of reach: " + ReachAudit.examples());
                helper.assertTrue(BuilderScaffolding.cellsPlaced > 0, "no scaffolding");
                BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
                BuilderCollab.placementProbe = null;
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
        climbProbe(helper, name, height, standOnTop, false);
    }

    static void climbProbe(final GameTestHelper helper, final String name, final int height, final boolean standOnTop, final boolean wall)
    {
        WorkerEfficiencyGameTests.scene(helper, "WSCAF probe " + name, live -> {
            final BlockPos site = helper.absolutePos(SITE);
            if (wall)
            {
                // a wall right next to the tower, 9 high and 5 wide
                for (int x = 1; x <= 5; x++)
                {
                    for (int y = 0; y < 9; y++)
                    {
                        live.level.setBlock(site.offset(x, y, 1), WorkerEfficiencyGameTests.BUILD_BLOCK.defaultBlockState(), 3);
                    }
                }
            }
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

    public static void climbWall5(final GameTestHelper helper)
    {
        climbProbe(helper, "wall5", 5, true, true);
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

    /**
     * Probe: the search of walkable spots from the top of a column of scaffolding reaches the ground again (a builder who stands on top of a
     * tower must be able to go down it).
     */
    public static void descendProbe(final GameTestHelper helper)
    {
        WorkerEfficiencyGameTests.scene(helper, "WSCAF descend", live -> {
            final BlockPos site = helper.absolutePos(SITE);
            for (int y = 0; y < 6; y++)
            {
                live.level.setBlock(site.offset(3, y, 0), Blocks.SCAFFOLDING.defaultBlockState().setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, 0), 3);
            }
            for (final int topFeet : new int[] {6, 5})
            {
                final BlockPos top = site.offset(3, topFeet, 0);
                final var reach = com.minecolonies.core.entity.ai.workers.util.StandSpots.reachableFrom(live.level, top, 28, 30000);
                Log.getLogger().info("PROBE descend from feet {} (floor {}, standable {}): {} nodes, contains ground={} contains cell below={}", topFeet, live.level.getBlockState(top.below()).getBlock(),
                  com.minecolonies.core.entity.ai.workers.util.StandSpots.canStandAt(live.level, top), reach.size(), reach.contains(site), reach.contains(top.below()));
                helper.assertTrue(reach.contains(site), "no way down from feet " + topFeet);
            }
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ a pit

    /**
     * A builder who fell into a pit of 5 blocks (no path out): he puts a column at the wall of the pit up to the rim and climbs out, instead of
     * being put on the rim. Then he builds a small ring and the scaffolding comes down.
     */
    public static void escapeFromPit(final GameTestHelper helper)
    {
        WorkerSettings.overrideReachForTests(null, null, null, 16);
        ReachAudit.enable(true);
        BuilderScaffolding.resetCounters();
        com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.teleports = 0;
        WorkerEfficiencyGameTests.scene(helper, "WSCAF pit", live -> {
            live.setSkills(40, 40);
            live.giveTools();
            final Blueprint blueprint = ring("pit", 3);
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), solidCount(blueprint) + WorkerEfficiencyGameTests.SPARE);
            live.stock(Items.SCAFFOLDING, 32);
            final BlockPos site = helper.absolutePos(SITE);
            // a pit of 3 by 3 and 5 deep, a few blocks from the hut
            final BlockPos pit = site.offset(-14, 0, 0);
            for (int x = 0; x < 3; x++)
            {
                for (int z = 0; z < 3; z++)
                {
                    for (int y = -1; y >= -5; y--)
                    {
                        live.level.setBlock(pit.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
            final WorkOrderDecoration order = live.orderFor("pit", SITE, blueprint);
            final BlockPos origin = order.getLocation();
            final BlockPos bottom = pit.offset(1, -5, 1);
            final Watch watch = new Watch();
            final int[] doneTick = {0};
            final boolean[] out = {false};
            final boolean[] fell = {false};
            WorkerEfficiencyGameTests.everyTick(live, 20_000, tick -> {
                watch(live, watch, origin, 32, tick);
                doneTick[0] = tick;
                if (!fell[0] && missing(live, blueprint, origin) < solidCount(blueprint) - 4)
                {
                    // he has started to build: now he falls into the pit
                    fell[0] = true;
                    live.citizen.getEntity().ifPresent(e -> e.setPos(bottom.getX() + 0.5D, bottom.getY(), bottom.getZ() + 0.5D));
                }
                if (fell[0]) { live.citizen.getEntity().ifPresent(e -> out[0] |= e.getY() >= site.getY() - 0.5D && e.position().distanceTo(Vec3.atCenterOf(pit)) < 8.0D); }
                if (tick % 500 == 0)
                {
                    Log.getLogger().info("WSCAF pit t={} out={} missing={} scaffold={} teleports={} {}", tick, out[0], missing(live, blueprint, origin), scaffoldInWorld(live, origin),
                      com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.teleports, live.ai());
                }
                return live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder();
            }, () -> {
                Log.getLogger().info("WBENCH RESULT pit ticks={} escapes={} teleports={} cells={}", doneTick[0], BuilderScaffolding.escapes,
                  com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.teleports, BuilderScaffolding.cellsPlaced);
                helper.assertTrue(built(live, blueprint, origin), "structure not complete: missing " + missing(live, blueprint, origin));
                helper.assertTrue(BuilderScaffolding.escapes > 0, "no tower was put up to get out of the pit");
                helper.assertTrue(com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.teleports == 0, "the builder was teleported: " + com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.teleports);
                helper.assertTrue(scaffoldInWorld(live, origin) == 0 && scaffoldInWorld(live, pit) == 0, "scaffolding left");
                helper.assertTrue(ledgerSize(live) == 0, "ledger left");
                helper.assertTrue(scaffoldItems(live, origin) == 32, "items not conserved: " + scaffoldItems(live, origin));
                helper.assertTrue(watch.conservationBreak == null, "items lost or made: " + watch.conservationBreak);
                WorkerSettings.clearOverrides();
                ReachAudit.enable(false);
                helper.succeed();
            }, () -> "out=" + out[0] + " missing=" + missing(live, blueprint, origin) + " scaffold=" + scaffoldInWorld(live, origin) + " " + live.ai());
        });
    }

    /**
     * Probe: can a citizen walk along an arm of scaffolding at the top of a column (4 blocks out at the height of 6)?
     */
    public static void armProbe(final GameTestHelper helper)
    {
        WorkerEfficiencyGameTests.scene(helper, "WSCAF arm", live -> {
            final BlockPos site = helper.absolutePos(SITE);
            for (int y = 0; y < 7; y++)
            {
                live.level.setBlock(site.offset(3, y, 0), Blocks.SCAFFOLDING.defaultBlockState().setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, 0), 3);
            }
            for (int x = 4; x <= 7; x++)
            {
                live.level.setBlock(site.offset(x, 6, 0), Blocks.SCAFFOLDING.defaultBlockState().setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, x - 3), 3);
            }
            final BlockPos goal = site.offset(7, 7, 0);
            final var entity = live.citizen.getEntity().get();
            entity.setPos(site.getX() + 0.5D, site.getY(), site.getZ() + 0.5D);
            final var reach = com.minecolonies.core.entity.ai.workers.util.StandSpots.reachableFrom(live.level, site, 28, 30000);
            Log.getLogger().info("PROBE arm bfs contains goal={}", reach.contains(goal));
            WorkerEfficiencyGameTests.everyTick(live, 800, tick -> {
                com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils.walkToBlock(entity, goal);
                if (tick % 20 == 0)
                {
                    Log.getLogger().info("PROBE arm t={} pos={} nav={}", tick, entity.blockPosition().toShortString(), entity.getNavigation().isDone() ? "done" : "busy");
                }
                return tick >= 400;
            }, () -> helper.succeed(), () -> "x");
        });
    }
}
