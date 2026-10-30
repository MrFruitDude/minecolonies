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

    /** The record of the tall-structure test that ran last (for the checks of a test that go beyond the common ones). */
    static Watch lastWatch = null;

    /** What a scaffolding test records while it runs. */
    static final class Watch
    {
        int maxScaffold = 0;
        int climbedTicks = 0;
        int maxHeight = 0;
        int minHeight = 0;
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
            watch.minHeight = Math.min(watch.minHeight, feet.getY() - site.getY());
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
        tallStructure(helper, name, blueprintOf, physical, maxTicks, expectScaffold, beforeOrder, extraChecks, 32);
    }

    /**
     * @param scaffoldStock the scaffolding items in the hut at the start (all of them are back in the end).
     */
    static void tallStructure(final GameTestHelper helper, final String name, final Supplier<Blueprint> blueprintOf, final boolean physical, final int maxTicks, final boolean expectScaffold,
      final java.util.function.BiConsumer<WorkerEfficiencyGameTests.Live, BlockPos> beforeOrder, final Runnable extraChecks, final int scaffoldStock)
    {
        WorkerSettings.overrideReachForTests(null, physical, null, 16);
        ReachAudit.enable(true);
        tickHook = null;
        BuilderScaffolding.resetCounters();
        WorkerEfficiencyGameTests.scene(helper, "WSCAF " + name, live -> {
            live.setSkills(40, 40);
            live.giveTools();
            final Blueprint blueprint = blueprintOf.get();
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), solidCount(blueprint) + WorkerEfficiencyGameTests.SPARE);
            if (scaffoldStock > 0)
            {
                live.stock(Items.SCAFFOLDING, scaffoldStock);
            }
            if (beforeOrder != null)
            {
                beforeOrder.accept(live, helper.absolutePos(SITE));
            }
            final WorkOrderDecoration order = live.orderFor(name, SITE, blueprint);
            final BlockPos origin = order.getLocation();
            final Watch watch = new Watch();
            lastWatch = watch;
            final Vec3[] last = {live.citizen.getEntity().get().position()};
            final double[] walked = {0};
            final int[] doneTick = {0};
            final long paths0 = Pathfinding.Stats.submitted.get();
            WorkerEfficiencyGameTests.everyTick(live, maxTicks, tick -> {
                final Vec3 now = live.citizen.getEntity().map(e -> e.position()).orElse(last[0]);
                walked[0] += Math.hypot(now.x - last[0].x, now.z - last[0].z);
                last[0] = now;
                watch(live, watch, origin, scaffoldStock, tick);
                trace(live, name, tick, origin);
                final java.util.function.IntConsumer hook = tickHook;
                if (hook != null)
                {
                    hook.accept(tick);
                }
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
                helper.assertTrue(scaffoldItems(live, origin) == scaffoldStock, "scaffolding items not conserved: " + scaffoldItems(live, origin));
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

    /**
     * Round 8 (roads): a decoration whose top block is 3 above its ground (a lamp post on a 3 by 3 floor), with the ground around it of bottom slabs (a builder
     * there stands half a block lower than the spot search used to think) and no scaffolding at all. The builder finishes it from stand spots he reaches: no
     * tower, no stall waiting for scaffolding, no fallback to MineColonies' working range, nothing placed out of reach.
     */
    public static void lampTopFromAStandSpot(final GameTestHelper helper)
    {
        final int fallbacks0 = com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder.reachFallbacks.get();
        tallStructure(helper, "lamp_top", () -> shape("lamp_top", 3, 4, 3, (x, y, z) -> y == 0 || (x == 1 && z == 1)), true, 6000, false, (live, origin) -> {
            // a ring of bottom slabs on the ground around the footprint, where the builder stands to work
            for (int x = -2; x <= 4; x++)
            {
                for (int z = -2; z <= 4; z++)
                {
                    if (x < 0 || z < 0 || x > 2 || z > 2)
                    {
                        live.level.setBlock(origin.offset(x, 0, z), Blocks.STONE_SLAB.defaultBlockState(), 3);
                    }
                }
            }
        }, () -> {
            helper.assertTrue(BuilderScaffolding.towersBuilt == 0, "a tower was planned for a lamp top 3 above the ground: " + BuilderScaffolding.towersBuilt);
            helper.assertTrue(com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder.reachFallbacks.get() == fallbacks0,
              "the builder fell back to the working range: no stand spot worked for a lamp top 3 above the ground");
        }, 0);
    }

    /**
     * Preview 20261029-3: a builder walking from his hut to his site was put back into the hut every few seconds. The shut-in rescue asks the stand-spot
     * search whether the spot by his hut block (inside the hut) can be walked to, and the search did not go through doors: from outside, the inside of
     * every hut was cut off. The search now goes through what the path planner goes through (r1, Fable r0 F2): an oak door, an iron door (citizens open
     * every door), a fence gate, a pair of open trapdoors used as a door, a carpet in the doorway, and a hatch (a closed trapdoor in a ceiling over a
     * ladder). A door and a trapdoor are no stand spots themselves.
     */
    public static void hutDoorIsAWayThrough(final GameTestHelper helper)
    {
        final net.minecraft.world.level.Level level = helper.getLevel();
        final BlockPos o = helper.absolutePos(new BlockPos(4, 1, 4));
        for (int x = -3; x <= 5; x++)
        {
            for (int z = -1; z <= 5; z++)
            {
                level.setBlock(o.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 1; y <= 5; y++)
                {
                    final boolean wall = x >= 0 && x <= 4 && z >= 0 && z <= 4 && (x == 0 || x == 4 || z == 0 || z == 4 || y == 5);
                    level.setBlock(o.offset(x, y, z), wall ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        final BlockPos door = o.offset(0, 1, 2);
        final BlockPos start = o.offset(-2, 1, 2);
        final BlockPos inside = o.offset(2, 1, 2);
        helper.assertTrue(!com.minecolonies.core.entity.ai.workers.util.StandSpots.reachableFrom(level, start, 28, 30000).contains(inside), "a closed room without a door was reached");

        placeDoor(level, door, Blocks.OAK_DOOR);
        final java.util.Set<BlockPos> oak = com.minecolonies.core.entity.ai.workers.util.StandSpots.reachableFrom(level, start, 28, 30000);
        helper.assertTrue(oak.contains(inside), "the inside of a room shut by an oak door was cut off");
        helper.assertTrue(!com.minecolonies.core.entity.ai.workers.util.StandSpots.canStandAt(level, door), "the door counts as a stand spot");

        placeDoor(level, door, Blocks.IRON_DOOR);
        helper.assertTrue(com.minecolonies.core.entity.ai.workers.util.StandSpots.reachableFrom(level, start, 28, 30000).contains(inside),
          "the inside of a room shut by an iron door was cut off (citizens open iron doors, the path planner goes through them)");

        level.setBlock(door.above(), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(door, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(net.minecraft.world.level.block.FenceGateBlock.FACING, net.minecraft.core.Direction.EAST), 3);
        helper.assertTrue(com.minecolonies.core.entity.ai.workers.util.StandSpots.reachableFrom(level, start, 28, 30000).contains(inside), "a fence gate was no way through");

        placeTrapdoorDoor(level, door, true);
        helper.assertTrue(com.minecolonies.core.entity.ai.workers.util.StandSpots.reachableFrom(level, start, 28, 30000).contains(inside), "a pair of open trapdoors was no way through");
        helper.assertTrue(!com.minecolonies.core.entity.ai.workers.util.StandSpots.canStandAt(level, door), "a trapdoor counts as a stand spot");
        placeTrapdoorDoor(level, door, false);
        helper.assertTrue(com.minecolonies.core.entity.ai.workers.util.StandSpots.reachableFrom(level, start, 28, 30000).contains(inside), "a pair of shut trapdoors was no way through");

        level.setBlock(door, Blocks.CARPET.white().defaultBlockState(), 3);
        level.setBlock(door.above(), Blocks.AIR.defaultBlockState(), 3);
        helper.assertTrue(com.minecolonies.core.entity.ai.workers.util.StandSpots.reachableFrom(level, start, 28, 30000).contains(inside), "a carpet in the doorway cut the room off");

        // a hatch: a closed room with a ladder up to a trapdoor in its ceiling, and a closed room above it
        final BlockPos h = helper.absolutePos(new BlockPos(14, 1, 4));
        for (int x = 0; x <= 4; x++)
        {
            for (int z = 0; z <= 4; z++)
            {
                level.setBlock(h.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 0; y <= 6; y++)
                {
                    final boolean solid = x == 0 || x == 4 || z == 0 || z == 4 || y == 3 || y == 6;
                    level.setBlock(h.offset(x, y, z), solid ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        for (int y = 0; y <= 2; y++)
        {
            level.setBlock(h.offset(2, y, 1), Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING, net.minecraft.core.Direction.SOUTH), 3);
        }
        final BlockPos below = h.offset(2, 0, 3);
        final BlockPos above = h.offset(2, 4, 3);
        helper.assertTrue(!com.minecolonies.core.entity.ai.workers.util.StandSpots.reachableFrom(level, below, 28, 30000).contains(above), "the room above was reached through a shut ceiling");
        level.setBlock(h.offset(2, 3, 1), Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(net.minecraft.world.level.block.TrapDoorBlock.FACING, net.minecraft.core.Direction.SOUTH), 3);
        helper.assertTrue(com.minecolonies.core.entity.ai.workers.util.StandSpots.reachableFrom(level, below, 28, 30000).contains(above), "the hatch over the ladder was no way up");
        helper.assertTrue(com.minecolonies.core.entity.ai.workers.util.StandSpots.reachableFrom(level, above, 28, 30000).contains(below), "the hatch over the ladder was no way down");
        helper.succeed();
    }

    /**
     * Two trapdoors on top of each other in a doorway (the way the doors of some huts are built), facing north: walked through from east or west.
     */
    private static void placeTrapdoorDoor(final net.minecraft.world.level.Level level, final BlockPos lower, final boolean open)
    {
        final net.minecraft.world.level.block.state.BlockState state = Blocks.OAK_TRAPDOOR.defaultBlockState()
                                                                           .setValue(net.minecraft.world.level.block.TrapDoorBlock.FACING, net.minecraft.core.Direction.NORTH)
                                                                           .setValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN, open);
        level.setBlock(lower, state.setValue(net.minecraft.world.level.block.TrapDoorBlock.HALF, net.minecraft.world.level.block.state.properties.Half.BOTTOM), 3);
        level.setBlock(lower.above(), state.setValue(net.minecraft.world.level.block.TrapDoorBlock.HALF, net.minecraft.world.level.block.state.properties.Half.TOP), 3);
    }

    /**
     * The way into a walled hut in the walk tests.
     */
    enum HutEntry
    {
        OAK_DOOR, IRON_DOOR, TRAPDOORS
    }

    /**
     * Walls a hut block in: a room of 5 by 5 inside, walls 3 high (no one climbs them), one way in on the east side (towards the sites).
     *
     * @param hutRel the hut block (relative).
     */
    static void wallHutIn(final GameTestHelper helper, final BlockPos hutRel, final HutEntry entry)
    {
        final net.minecraft.world.level.Level level = helper.getLevel();
        final BlockPos hut = helper.absolutePos(hutRel);
        for (int x = -3; x <= 3; x++)
        {
            for (int z = -3; z <= 3; z++)
            {
                if (Math.max(Math.abs(x), Math.abs(z)) != 3)
                {
                    continue;
                }
                for (int y = 0; y <= 2; y++)
                {
                    level.setBlock(hut.offset(x, y, z), Blocks.COBBLESTONE.defaultBlockState(), 3);
                }
            }
        }
        final BlockPos way = hut.offset(3, 0, 0);
        switch (entry)
        {
            case OAK_DOOR -> placeDoor(level, way, Blocks.OAK_DOOR);
            case IRON_DOOR -> placeDoor(level, way, Blocks.IRON_DOOR);
            case TRAPDOORS -> placeTrapdoorDoor(level, way, true);
        }
    }

    /**
     * The builder of a hut walled in with one way in builds a low ring 16 to 22 blocks away (on the level of his hut, in the range of the shut-in
     * rescue): he walks out through the way in and back, and he is never put back into his hut. Before r1 the stand-spot search did not go through
     * trapdoors and iron doors: he was snapped home every 120 ticks of standing in the box of the ring (Fable r0 F2).
     */
    static void hutDoorWalk(final GameTestHelper helper, final HutEntry entry)
    {
        final String name = "hutwalk_" + entry.name().toLowerCase(java.util.Locale.ROOT);
        final WorkerEfficiencyGameTests.Live[] at = {null};
        final int blocked0 = com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.trappedBlocked;
        tallStructure(helper, name, () -> shape(name, 5, 2, 5, (x, y, z) -> y == 0 || x == 0 || z == 0 || x == 4 || z == 4), true, 12_000, false, (live, site) -> {
            at[0] = live;
            wallHutIn(helper, new BlockPos(8, 1, 2), entry);
        }, () -> {
            final com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure<?, ?> ai = structureAi(at[0].citizen);
            helper.assertTrue(ai != null, "no structure AI");
            Log.getLogger().info("WSCAF {} rescues={} blocked={} reason={}", name, ai.trapRescueCount(),
              com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.trappedBlocked - blocked0, ai.trapBlockedReason());
            helper.assertTrue(ai.trapRescueCount() == 0, "the builder was put back into his hut " + ai.trapRescueCount() + " times (way in: " + entry + ")");
            helper.assertTrue(ai.trapBlockedReason() == null, "the builder is marked shut in: " + ai.trapBlockedReason());
        });
    }

    public static void hutDoorWalkOak(final GameTestHelper helper)
    {
        hutDoorWalk(helper, HutEntry.OAK_DOOR);
    }

    public static void hutDoorWalkTrapdoor(final GameTestHelper helper)
    {
        hutDoorWalk(helper, HutEntry.TRAPDOORS);
    }

    public static void hutDoorWalkIron(final GameTestHelper helper)
    {
        hutDoorWalk(helper, HutEntry.IRON_DOOR);
    }

    /**
     * A helper builder (collaboration on) whose own hut is walled in with a pair of trapdoors as the way in: a helper has no structure of his own, so
     * before r1 the box guard of the shut-in rescue did not hold for him and he was snapped home whenever he stood still near his hut (at the lead's
     * hut for his materials, at the site). He helps, and neither builder is put back into a hut. The order is the size of scaffold_two_builders_big: a
     * 7 by 7 one is too small for the helper gate (the second builder never joins it, scaffold_two_builders places nothing by the helper either).
     */
    public static void hutDoorHelper(final GameTestHelper helper)
    {
        final String name = "hutwalk_helper";
        final int escapes0 = com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.trapEscapes;
        twoBuilders(helper, name, () -> shape(name, 11, 12, 11, (x, y, z) -> x == 0 || z == 0 || x == 10 || z == 10), 40_000, true, SITE,
          live -> wallHutIn(helper, new BlockPos(16, 1, 2), HutEntry.TRAPDOORS),
          (live, blocksByHelper) -> {
              final com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure<?, ?> lead = structureAi(live.citizen);
              final com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure<?, ?> second = structureAi(live.otherCitizen);
              helper.assertTrue(lead != null && second != null, "no structure AI");
              Log.getLogger().info("WSCAF {} helperBlocks={} rescues lead={} helper={} putBacks lead={} helper={} escapes={} reasons {} / {}", name, blocksByHelper,
                lead.trapRescueCount(), second.trapRescueCount(), lead.putBackCount(), second.putBackCount(),
                com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.trapEscapes - escapes0, lead.trapBlockedReason(), second.trapBlockedReason());
              helper.assertTrue(blocksByHelper > 0, "the second builder did not help (no block placed): the helper path was not tested");
              // any jump back to the hut counts (shut-in rescue, the edge teleport's hut fallback, the walk ladder), not only the shut-in rescue
              helper.assertTrue(second.trapRescueCount() == 0 && second.putBackCount() == 0,
                "the helper was put back into his hut " + Math.max(second.trapRescueCount(), second.putBackCount()) + " times");
              helper.assertTrue(lead.trapRescueCount() == 0 && lead.putBackCount() == 0,
                "the lead was put back into his hut " + Math.max(lead.trapRescueCount(), lead.putBackCount()) + " times");
              helper.assertTrue(second.trapBlockedReason() == null && lead.trapBlockedReason() == null, "a builder is still marked shut in at the end");
          });
    }

    /**
     * The rules of the shut-in rescue (ShutInGate, no world): a worker who gets on (moves a block or more between checks) is never put out, however long
     * the search finds him cut off; a worker outside the box of his structure is never put out (the search is not even asked); one who stands still and is
     * cut off is put out at the third check (r2: one who places blocks from where he stands is never; a climb out with scaffolding is no rescue, only
     * {@code rescued} counts); shut in again within 5 minutes he is marked blocked once instead of a second rescue; after the cooldown he is
     * put out again.
     */
    public static void hutDoorProgress(final GameTestHelper helper)
    {
        final com.minecolonies.core.entity.ai.workers.util.ShutInGate gate = new com.minecolonies.core.entity.ai.workers.util.ShutInGate();
        final BlockPos box = BlockPos.ZERO;
        long now = 0L;
        for (int i = 0; i < 20; i++, now += 40L)
        {
            helper.assertTrue(gate.check(true, 1.5D, () -> true, box, now) == com.minecolonies.core.entity.ai.workers.util.ShutInGate.Decision.NONE,
              "a worker walking a path was put out at check " + i);
        }
        com.minecolonies.core.entity.ai.workers.util.ShutInGate.Decision d0 = com.minecolonies.core.entity.ai.workers.util.ShutInGate.Decision.NONE;
        final boolean[] asked = {false};
        for (int i = 0; i < 10; i++, now += 40L)
        {
            helper.assertTrue(gate.check(false, 0.0D, () -> asked[0] = true, box, now) == com.minecolonies.core.entity.ai.workers.util.ShutInGate.Decision.NONE,
              "a worker outside the box was put out");
        }
        helper.assertTrue(!asked[0], "the search ran for a worker outside the box");
        // two still, one step on, two still: no three in a row
        helper.assertTrue(gate.check(true, 0.0D, () -> true, box, now += 40L) == com.minecolonies.core.entity.ai.workers.util.ShutInGate.Decision.NONE, "strike 1");
        helper.assertTrue(gate.check(true, 0.0D, () -> true, box, now += 40L) == com.minecolonies.core.entity.ai.workers.util.ShutInGate.Decision.NONE, "strike 2");
        helper.assertTrue(gate.check(true, 1.2D, () -> true, box, now += 40L) == com.minecolonies.core.entity.ai.workers.util.ShutInGate.Decision.NONE, "moved");
        helper.assertTrue(gate.check(true, 0.0D, () -> true, box, now += 40L) == com.minecolonies.core.entity.ai.workers.util.ShutInGate.Decision.NONE, "strike 1 again");
        helper.assertTrue(gate.check(true, 0.0D, () -> true, box, now += 40L) == com.minecolonies.core.entity.ai.workers.util.ShutInGate.Decision.NONE,
          "put out after a step on reset the count");
        helper.assertTrue(gate.check(true, 0.0D, () -> true, box, now += 40L) == com.minecolonies.core.entity.ai.workers.util.ShutInGate.Decision.RESCUE,
          "a worker standing still and cut off for three checks was not put out");
        // r2: he climbed out with scaffolding instead (no rescue recorded): the next shut-in is a rescue again, not "blocked"
        for (int i = 0; i < 3; i++)
        {
            d0 = gate.check(true, 0.0D, () -> true, box, now += 40L);
        }
        helper.assertTrue(d0 == com.minecolonies.core.entity.ai.workers.util.ShutInGate.Decision.RESCUE && !gate.isBlocked(), "a climb out counted as a rescue: " + d0);
        // r2: one who places block after block from one spot (cut off, not moving) gets on: never put out (run 015, the helper raising the ring around him)
        for (int i = 0; i < 20; i++)
        {
            helper.assertTrue(gate.check(true, 0.0D, true, () -> true, box, now += 40L) == com.minecolonies.core.entity.ai.workers.util.ShutInGate.Decision.NONE,
              "a worker placing blocks from where he stands was put out at check " + i);
        }
        for (int i = 0; i < 3; i++)
        {
            d0 = gate.check(true, 0.0D, () -> true, box, now += 40L);
        }
        helper.assertTrue(d0 == com.minecolonies.core.entity.ai.workers.util.ShutInGate.Decision.RESCUE, "no work, cut off and still: not put out " + d0);
        gate.rescued(box, now);
        final long rescuedAt = now;
        // walks back in at once and stands: blocked once, then nothing
        com.minecolonies.core.entity.ai.workers.util.ShutInGate.Decision d = com.minecolonies.core.entity.ai.workers.util.ShutInGate.Decision.NONE;
        for (int i = 0; i < 3; i++)
        {
            d = gate.check(true, 0.0D, () -> true, box, now += 40L);
        }
        helper.assertTrue(d == com.minecolonies.core.entity.ai.workers.util.ShutInGate.Decision.BLOCKED && gate.isBlocked(), "shut in again within the cooldown: " + d);
        for (int i = 0; i < 30; i++)
        {
            helper.assertTrue(gate.check(true, 0.0D, () -> true, box, now += 40L) == com.minecolonies.core.entity.ai.workers.util.ShutInGate.Decision.NONE,
              "a second rescue (or a second block note) within the cooldown");
        }
        now = rescuedAt + com.minecolonies.core.entity.ai.workers.util.ShutInGate.COOLDOWN;
        for (int i = 0; i < 3; i++)
        {
            d = gate.check(true, 0.0D, () -> true, box, now += 40L);
        }
        helper.assertTrue(d == com.minecolonies.core.entity.ai.workers.util.ShutInGate.Decision.RESCUE, "not put out after the cooldown: " + d);
        gate.rescued(box, now);
        // a way out appears: the block note is gone
        gate.check(true, 0.0D, () -> false, box, now += 40L);
        helper.assertTrue(!gate.isBlocked(), "still blocked with a way out");
        helper.succeed();
    }

    @org.jetbrains.annotations.Nullable
    private static com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure<?, ?> structureAi(final com.minecolonies.api.colony.ICitizenData citizen)
    {
        return citizen.getEntity().map(e -> e.getCitizenJobHandler().getWorkAI() instanceof com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure<?, ?> ai ? ai : null)
                 .orElse(null);
    }

    private static void placeDoor(final net.minecraft.world.level.Level level, final BlockPos lower, final net.minecraft.world.level.block.Block block)
    {
        final net.minecraft.world.level.block.state.BlockState state = block.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.EAST);
        level.setBlock(lower, state.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), 2);
        level.setBlock(lower.above(), state.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 3);
    }

    /** Called on every tick of {@link #tallStructure} (a test's own look at the world), or null; reset when the next structure starts. */
    static volatile java.util.function.IntConsumer tickHook = null;

    /** Whether the cells of the flooded-cell test held water at some point while it built (the clear stage opened them, the water flowed in). */
    private static volatile boolean floodSeen = false;

    /**
     * Round 8 (roads): a 3 by 3 floor laid where dirt stands, next to a water source held in by a rim of stone. The clear stage digs the dirt out, the water
     * flows into the open cells, and the builder places the floor into the water (the block replaces it). He finishes; the water was seen in the cells.
     */
    public static void floodedCellIsFilled(final GameTestHelper helper)
    {
        floodSeen = false;
        tallStructure(helper, "flooded_cell", () -> shape("flooded_cell", 3, 2, 3, (x, y, z) -> y == 0), true, 12_000, false, (live, origin) -> {
            for (int x = -1; x <= 3; x++)
            {
                for (int z = -1; z <= 3; z++)
                {
                    final boolean inside = x >= 0 && z >= 0 && x <= 2 && z <= 2;
                    live.level.setBlock(origin.offset(x, 0, z), inside ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState(), 3);
                }
            }
            // the source sits in the rim beside the middle of the west side, walled in on its other three sides
            live.level.setBlock(origin.offset(-2, 0, 1), Blocks.STONE.defaultBlockState(), 3);
            live.level.setBlock(origin.offset(-1, 0, 1), Blocks.WATER.defaultBlockState(), 3);
            // (looked at from the test's own tick loop: a task scheduled from inside a scheduled task crashed the server in round 8 launch c4)
            tickHook = tick -> {
                if (!floodSeen)
                {
                    for (int x = 0; x <= 2 && !floodSeen; x++)
                    {
                        for (int z = 0; z <= 2; z++)
                        {
                            if (!live.level.getFluidState(origin.offset(x, 0, z)).isEmpty())
                            {
                                floodSeen = true;
                                Log.getLogger().info("WSCAF flooded_cell: water in the cell {} at {}", origin.offset(x, 0, z).toShortString(), live.level.getGameTime());
                                break;
                            }
                        }
                    }
                }
            };
        }, () -> helper.assertTrue(floodSeen, "the cleared cells never flooded: the test did not test a flooded cell"), 0);
    }

    /**
     * Builder review c1 H1, c2 N2: a pillar 12 high, scaffolding switched off. A limited fallback places at most 5 up or down from the eye; a block no spot
     * reaches is placed the way MineColonies does (scaffolding off: no refusal). The pillar is whole.
     */
    public static void tallBlockWithoutScaffolding(final GameTestHelper helper)
    {
        WorkerSettings.overrideReachForTests(null, true, false, 0);
        ReachAudit.enable(true);
        BuilderScaffolding.resetCounters();
        final int fallbacks0 = com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder.reachFallbacks.get();
        final int refusals0 = com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder.reachRefusals.get();
        WorkerEfficiencyGameTests.scene(helper, "WSCAF pillar", live -> {
            live.setSkills(40, 40);
            live.giveTools();
            final Blueprint blueprint = shape("pillar", 1, 12, 1, (x, y, z) -> true);
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), solidCount(blueprint) + WorkerEfficiencyGameTests.SPARE);
            final WorkOrderDecoration order = live.orderFor("pillar", SITE, blueprint);
            final BlockPos origin = order.getLocation();
            WorkerEfficiencyGameTests.everyTick(live, 12_000, tick -> {
                if (tick % 1000 == 0)
                {
                    Log.getLogger().info("WSCAF pillar t={} missing={} fallbacks={} refusals={} audit={} maxVertical={} {}", tick, missing(live, blueprint, origin),
                      com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder.reachFallbacks.get() - fallbacks0,
                      com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder.reachRefusals.get() - refusals0, ReachAudit.violations(),
                      String.format("%.2f", ReachAudit.fallbackMaxVertical()), live.ai());
                }
                return tick >= 9000 || (live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder());
            }, () -> {
                final int fallbacks = com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder.reachFallbacks.get() - fallbacks0;
                final int refusals = com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder.reachRefusals.get() - refusals0;
                Log.getLogger().info("WBENCH RESULT pillar missing={} fallbacks={} refusals={} fallbackActions={} maxVertical={} violations={} top={}", missing(live, blueprint, origin),
                  fallbacks, refusals, ReachAudit.fallbackActions(), String.format("%.2f", ReachAudit.fallbackMaxVertical()), ReachAudit.violations(),
                  live.level.getBlockState(origin.above(11)).getBlock());
                helper.assertTrue(ReachAudit.violations() == 0, "blocks were placed out of reach: " + ReachAudit.examples());
                helper.assertTrue(ReachAudit.fallbackMaxVertical() <= 5.0001D, "the fallback placed a block " + ReachAudit.fallbackMaxVertical() + " up or down from the eye");
                helper.assertTrue(built(live, blueprint, origin), "a hole: missing " + missing(live, blueprint, origin));
                helper.assertTrue(refusals == 0, "a block was refused with scaffolding off: " + refusals);
                WorkerSettings.clearOverrides();
                ReachAudit.enable(false);
                helper.succeed();
            }, () -> "missing=" + missing(live, blueprint, origin) + " " + live.ai());
        });
    }

    /** A full pillar, one block wide: nothing to stand on near its top but a tower (or its own top, which no path reaches). */
    static Blueprint pillar(final String name, final int height)
    {
        return shape(name, 1, height, 1, (x, y, z) -> true);
    }

    /** The counters of the reach fallback, read at the start of a test. */
    private static int[] reachCounters()
    {
        return new int[] {com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder.reachFallbacks.get(),
          com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder.reachDefaults.get(),
          com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder.reachRefusals.get(),
          com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder.reachFlooded.get()};
    }

    /** limited fallbacks, default placements, refusals, flooded fallbacks since {@code at}. */
    private static int[] reachDelta(final int[] at)
    {
        final int[] now = reachCounters();
        return new int[] {now[0] - at[0], now[1] - at[1], now[2] - at[2], now[3] - at[3]};
    }

    /**
     * Builder review c2 N1: a pillar 10 high whose scaffolding is asked for and arrives about 3000 ticks later (a slow courier). The tower waits for it (no
     * 600-tick drop while the request is open) and is built: scaffolding is placed, no fallback, no default placement, the pillar is whole.
     */
    public static void slowDeliveryNoFallback(final GameTestHelper helper)
    {
        WorkerSettings.overrideReachForTests(null, true, true, 16);
        ReachAudit.enable(true);
        BuilderScaffolding.resetCounters();
        final int[] at = reachCounters();
        WorkerEfficiencyGameTests.scene(helper, "WSCAF slow", live -> {
            live.setSkills(40, 40);
            live.giveTools();
            final Blueprint blueprint = pillar("slow", 10);
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), solidCount(blueprint) + WorkerEfficiencyGameTests.SPARE);
            final WorkOrderDecoration order = live.orderFor("slow", SITE, blueprint);
            final BlockPos origin = order.getLocation();
            final int[] requestedAt = {-1};
            final boolean[] delivered = {false};
            WorkerEfficiencyGameTests.everyTick(live, 30_000, tick -> {
                if (requestedAt[0] < 0)
                {
                    // (the same check as BuilderScaffolding.stockAsked: the c6 launch matched on the request text, which does not name the item)
                    if (live.hut.getOpenRequestsOfType(-1, com.google.common.reflect.TypeToken.of(com.minecolonies.api.colony.requestsystem.requestable.Stack.class)).stream()
                          .anyMatch(r -> r.getRequest().getStack().is(Items.SCAFFOLDING)))
                    {
                        requestedAt[0] = tick;
                    }
                }
                if (requestedAt[0] >= 0 && !delivered[0] && tick - requestedAt[0] >= 3000)
                {
                    // the slow courier brings 32
                    delivered[0] = true;
                    InventoryUtils.addItemStackToItemHandler(live.hut.getItemHandlerCap(), new ItemStack(Items.SCAFFOLDING, 32));
                    Log.getLogger().info("WSCAF slow: requested at t={}, delivered at t={}", requestedAt[0], tick);
                }
                if (tick % 1000 == 0)
                {
                    Log.getLogger().info("WSCAF slow t={} missing={} towers={} cells={} reach={} {}", tick, missing(live, blueprint, origin), BuilderScaffolding.towersBuilt,
                      BuilderScaffolding.cellsPlaced, java.util.Arrays.toString(reachDelta(at)), live.ai());
                }
                return delivered[0] && live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder();
            }, () -> {
                final int[] d = reachDelta(at);
                Log.getLogger().info("WBENCH RESULT slow requestedAt={} towers={} cells={} limited={} default={} refused={} flooded={} violations={}", requestedAt[0],
                  BuilderScaffolding.towersBuilt, BuilderScaffolding.cellsPlaced, d[0], d[1], d[2], d[3], ReachAudit.violations());
                helper.assertTrue(built(live, blueprint, origin), "not built: missing " + missing(live, blueprint, origin));
                helper.assertTrue(BuilderScaffolding.cellsPlaced > 0, "the late tower was not built: towers=" + BuilderScaffolding.towersBuilt + " cells=0");
                helper.assertTrue(d[0] == 0 && d[1] == 0, "a late delivery made the builder fall back: limited " + d[0] + " default " + d[1]);
                helper.assertTrue(ReachAudit.violations() == 0, "out of reach: " + ReachAudit.examples());
                helper.assertTrue(scaffoldInWorld(live, origin) == 0, "scaffolding left");
                WorkerSettings.clearOverrides();
                ReachAudit.enable(false);
                helper.succeed();
            }, () -> "requestedAt=" + requestedAt[0] + " delivered=" + delivered[0] + " missing=" + missing(live, blueprint, origin) + " " + live.ai());
        });
    }

    /**
     * Builder review c2 N2: a pillar 10 high, scaffolding on and asked for, but never delivered. While the tower can still come the builder refuses the blocks
     * out of reach (needs scaffolding) and waits; after the 6000-tick limit he places them the way MineColonies does. The pillar is whole: no hole.
     */
    public static void pillarScaffoldingNeverDelivered(final GameTestHelper helper)
    {
        WorkerSettings.overrideReachForTests(null, true, true, 16);
        ReachAudit.enable(true);
        BuilderScaffolding.resetCounters();
        final int[] at = reachCounters();
        WorkerEfficiencyGameTests.scene(helper, "WSCAF undelivered", live -> {
            live.setSkills(40, 40);
            live.giveTools();
            final Blueprint blueprint = pillar("undelivered", 10);
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), solidCount(blueprint) + WorkerEfficiencyGameTests.SPARE);
            final WorkOrderDecoration order = live.orderFor("undelivered", SITE, blueprint);
            final BlockPos origin = order.getLocation();
            final int[] firstDefault = {-1};
            WorkerEfficiencyGameTests.everyTick(live, 25_000, tick -> {
                if (firstDefault[0] < 0 && reachDelta(at)[1] > 0)
                {
                    firstDefault[0] = tick;
                }
                if (tick % 1000 == 0)
                {
                    Log.getLogger().info("WSCAF undelivered t={} missing={} reach={} {}", tick, missing(live, blueprint, origin), java.util.Arrays.toString(reachDelta(at)), live.ai());
                }
                return live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder();
            }, () -> {
                final int[] d = reachDelta(at);
                Log.getLogger().info("WBENCH RESULT undelivered limited={} default={} refused={} flooded={} firstDefault={} towers={} cells={} violations={}", d[0], d[1], d[2], d[3],
                  firstDefault[0], BuilderScaffolding.towersBuilt, BuilderScaffolding.cellsPlaced, ReachAudit.violations());
                helper.assertTrue(built(live, blueprint, origin), "a hole: missing " + missing(live, blueprint, origin));
                // (refusals are counted only when he comes back to such a block before the limit: 344 in c6 launch 1, 0 in launch 2; the wait is what matters)
                helper.assertTrue(d[1] > 0, "no block was placed the way MineColonies does after the limit");
                helper.assertTrue(firstDefault[0] >= 5000, "the default placement came before the tower was waited for: tick " + firstDefault[0]);
                helper.assertTrue(ReachAudit.violations() == 0, "out of reach outside the fallback: " + ReachAudit.examples());
                WorkerSettings.clearOverrides();
                ReachAudit.enable(false);
                helper.succeed();
            }, () -> "missing=" + missing(live, blueprint, origin) + " reach=" + java.util.Arrays.toString(reachDelta(at)) + " " + live.ai());
        });
    }

    /**
     * Builder review c2: a cell at the bottom of a 7 by 7 pool, 4 deep (c6 launch: 3 deep was reached from the shore), on a plateau 4 high (a ramp up from the west). No dry spot reaches the cell by the
     * physical reach, the shore is within 5. Scaffolding is off (no tower in the water). The builder fills the cell through the flooded fallback.
     */
    public static void floodedPoolFallback(final GameTestHelper helper)
    {
        final int[] at = reachCounters();
        tallStructure(helper, "flooded_pool", () -> shape("flooded_pool", 1, 2, 1, (x, y, z) -> y == 0), true, 15_000, false, (live, origin) -> {
            WorkerSettings.overrideReachForTests(null, true, false, 0);
            for (int x = -6; x <= 6; x++)
            {
                for (int z = -6; z <= 6; z++)
                {
                    final int ring = Math.max(Math.abs(x), Math.abs(z));
                    for (int y = 0; y <= 3; y++)
                    {
                        if (ring <= 3)
                        {
                            live.level.setBlock(origin.offset(x, y, z), Blocks.WATER.defaultBlockState(), 3);
                        }
                        else if (ring <= 5)
                        {
                            live.level.setBlock(origin.offset(x, y, z), Blocks.STONE.defaultBlockState(), 3);
                        }
                    }
                }
            }
            // a ramp up the west side
            for (int z = -1; z <= 1; z++)
            {
                live.level.setBlock(origin.offset(-6, 0, z), Blocks.STONE.defaultBlockState(), 3);
                live.level.setBlock(origin.offset(-6, 1, z), Blocks.STONE.defaultBlockState(), 3);
                live.level.setBlock(origin.offset(-6, 2, z), Blocks.STONE.defaultBlockState(), 3);
                live.level.setBlock(origin.offset(-7, 0, z), Blocks.STONE.defaultBlockState(), 3);
                live.level.setBlock(origin.offset(-7, 1, z), Blocks.STONE.defaultBlockState(), 3);
                live.level.setBlock(origin.offset(-8, 0, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }, () -> {
            final int[] d = reachDelta(at);
            Log.getLogger().info("WBENCH RESULT flooded_pool limited={} default={} refused={} flooded={} maxVertical={}", d[0], d[1], d[2], d[3],
              String.format("%.2f", ReachAudit.fallbackMaxVertical()));
            // scaffolding is off and no spot reaches the cell, so the default placement comes before the flooded clock (c6 launch 2: default 1, flooded 0).
            // The cell is filled either way; the flooded fallback itself is not exercised by this shape.
            helper.assertTrue(d[1] + d[3] > 0, "the cell was filled without a fallback: limited " + d[0]);
            helper.assertTrue(d[2] == 0, "a block was refused with scaffolding off: " + d[2]);
        }, 0);
    }

    /**
     * Builder review c2 N2: an ordinary tall hut shape (a ring 10 high), scaffolding switched off for the colony. Out-of-reach blocks are placed the way
     * MineColonies does: the ring is built whole, no block missing.
     */
    public static void tallHutWithoutScaffolding(final GameTestHelper helper)
    {
        final int[] at = reachCounters();
        tallStructure(helper, "tall_hut_noscaf", () -> ring("tall_hut_noscaf", 10), true, 40_000, false, (live, origin) -> WorkerSettings.overrideReachForTests(null, true, false, 0),
          () -> {
              final int[] d = reachDelta(at);
              Log.getLogger().info("WBENCH RESULT tall_hut_noscaf limited={} default={} refused={} flooded={}", d[0], d[1], d[2], d[3]);
              helper.assertTrue(d[2] == 0, "a block was refused with scaffolding off: " + d[2]);
          }, 0);
    }

    /**
     * Builder review c4 C4-1: one block in the middle of a ring of bedrock walls (6 out, 4 high), scaffolding on and stocked, the ladder's teleport off. Stand
     * spots exist inside the ring but no walk gets there, and nothing outside reaches the block. The ladder defers it again and again; the 12 returns count as
     * the wait, and the 13th is placed the way MineColonies does (its walk ends at the wall, its own rule places from there). No hole, 0 violations.
     * c7 launches 1 and 2: the barrier cube versions never blocked the builder.
     */
    public static void pathBlockedOrderEnds(final GameTestHelper helper)
    {
        walledOff(helper, "walled", false, false, false);
    }

    /**
     * Builder review c5 C5-1: the same ring, but the wait for a tower never ends for this builder (test switch), and scaffolding is stocked, so no other
     * default route can win. The block must be placed by the 13th return: every default try of the order is a kept-out one.
     */
    public static void pathBlocked13thReturn(final GameTestHelper helper)
    {
        walledOff(helper, "walled13", false, true, false);
    }

    /**
     * Builder review c4 L-a: the same ring as {@link #pathBlocked13thReturn}, with a dirt block to clear above the block to place. The block to clear comes
     * back like the one to place, and is mined by the same default rule (from where MineColonies' walk ended) instead of staying standing.
     * r9 launch: the dirt (one above the wall's reach line) was mined from a scaffolding tower outside the wall, so only "not left standing" was shown, not
     * the default-rule mining itself (keptOut 1: the block to place). A fixture where no tower reaches the block to clear is still needed for that.
     */
    public static void pathBlockedClear(final GameTestHelper helper)
    {
        walledOff(helper, "walledclear", false, true, true);
    }

    /**
     * The same ring with lava under the block: never a fallback over lava, so the block is left out and the order ends.
     */
    public static void pathBlockedOverLava(final GameTestHelper helper)
    {
        walledOff(helper, "walledlava", true, false, false);
    }

    private static void walledOff(final GameTestHelper helper, final String name, final boolean lava, final boolean only13th, final boolean clear)
    {
        WorkerSettings.overrideReachForTests(null, true, true, 16);
        ReachAudit.enable(true);
        BuilderScaffolding.resetCounters();
        final int[] at = reachCounters();
        final int keptOut0 = com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder.reachKeptOut.get();
        WorkerEfficiencyGameTests.scene(helper, "WSCAF " + name, live -> {
            live.setSkills(40, 40);
            live.giveTools();
            WorkerSettings.overrideTeleportForTests(live.citizen.getEntity().get().getId());
            if (only13th)
            {
                WorkerSettings.overrideTowerWaitForTests(live.citizen.getEntity().get().getId());
            }
            final Blueprint blueprint = shape(name, 1, 2, 1, (x, y, z) -> y == 0);
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), solidCount(blueprint) + WorkerEfficiencyGameTests.SPARE);
            live.stock(Items.SCAFFOLDING, 32);
            final BlockPos site = helper.absolutePos(SITE);
            for (int x = -6; x <= 6; x++)
            {
                for (int z = -6; z <= 6; z++)
                {
                    if (Math.max(Math.abs(x), Math.abs(z)) == 6)
                    {
                        for (int y = 0; y <= 3; y++)
                        {
                            live.level.setBlock(site.offset(x, y, z), Blocks.BEDROCK.defaultBlockState(), 3);
                        }
                    }
                }
            }
            if (lava)
            {
                live.level.setBlock(site.below(), Blocks.LAVA.defaultBlockState(), 3);
            }
            final WorkOrderDecoration order = live.orderFor(name, SITE, blueprint);
            final BlockPos origin = order.getLocation();
            if (clear)
            {
                // the blueprint wants air there: the clear stage has to mine it
                live.level.setBlock(origin.above(), Blocks.DIRT.defaultBlockState(), 3);
            }
            final int[] doneTick = {0};
            WorkerEfficiencyGameTests.everyTick(live, 40_000, tick -> {
                if (tick == 1 || tick % 1000 == 0)
                {
                    Log.getLogger().info("WSCAF {} t={} origin={} wall={} missing={} reach={} keptOut={} {}", name, tick, origin.toShortString(),
                      live.level.getBlockState(site.offset(6, 0, 0)).getBlock(), missing(live, blueprint, origin), java.util.Arrays.toString(reachDelta(at)),
                      com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder.reachKeptOut.get() - keptOut0, live.ai());
                }
                doneTick[0] = tick;
                return live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder();
            }, () -> {
                final int[] d = reachDelta(at);
                final int keptOut = com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder.reachKeptOut.get() - keptOut0;
                final boolean dirtLeft = live.level.getBlockState(origin.above()).is(Blocks.DIRT);
                Log.getLogger().info("WBENCH RESULT {} ticks={} missing={} keptOut={} limited={} default={} refused={} towers={} cells={} default2D={} violations={} dirtLeft={}", name,
                  doneTick[0], missing(live, blueprint, origin), keptOut, d[0], d[1], d[2], BuilderScaffolding.towersBuilt, BuilderScaffolding.cellsPlaced,
                  ReachAudit.defaultMax2D(), ReachAudit.violations(), dirtLeft);
                helper.assertTrue(ReachAudit.violations() == 0, "placed out of range: " + ReachAudit.examples());
                if (lava)
                {
                    helper.assertTrue(missing(live, blueprint, origin) == 1 && keptOut == 0, "over lava: missing " + missing(live, blueprint, origin) + " keptOut " + keptOut);
                }
                else
                {
                    helper.assertTrue(doneTick[0] > 200, "the order ended at once (tick " + doneTick[0] + "): the walls did not block him");
                    helper.assertTrue(missing(live, blueprint, origin) == 0, "a hole: missing " + missing(live, blueprint, origin));
                    // c8 launch: the 6000-tick wait route (no spot reached it for 6000 ticks) placed it after 2 returns, before the 13th; either default route
                    // is the no-holes rule. Which one ran is logged in the result line.
                    helper.assertTrue(keptOut > 0 || d[1] > 0, "not placed by a default try (keptOut 0, default " + d[1] + ")");
                    if (only13th)
                    {
                        // the wait cannot end and scaffolding is there: the only default try is the 13th return's (C5-1)
                        helper.assertTrue(keptOut > 0 && d[1] == keptOut, "not placed by the 13th return: keptOut " + keptOut + ", default tries " + d[1]);
                    }
                    if (clear)
                    {
                        helper.assertTrue(!dirtLeft, "the block to clear stayed standing (L-a)");
                    }
                }
                WorkerSettings.clearOverrides();
                ReachAudit.enable(false);
                helper.succeed();
            }, () -> "the order did not end: missing=" + missing(live, blueprint, origin) + " reach=" + java.util.Arrays.toString(reachDelta(at)) + " keptOut="
                       + (com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder.reachKeptOut.get() - keptOut0) + " " + live.ai());
        });
    }

    /**
     * c7 launch 1 (flooded_pool): a default-placement block was placed 23 around while MineColonies' path to it was still worked out (its "previous block
     * within 10" shortcut). Here: block A on the ground, block B 7 to the east and 7 up (9.9 from A, out of the physical reach of every ground spot), scaffolding
     * off, so B falls back to the default placement. B must be walked to: the audit counts a default placement further than 5 around before MineColonies'
     * walk ended as a violation.
     */
    public static void noDefaultPlacementWhilePathing(final GameTestHelper helper)
    {
        final int[] at = reachCounters();
        tallStructure(helper, "pathcalc", () -> shape("pathcalc", 8, 9, 1, (x, y, z) -> (x == 0 && y == 0) || (x == 7 && y == 7)), true, 15_000, false,
          (live, origin) -> WorkerSettings.overrideReachForTests(null, true, false, 0), () -> {
              final int[] d = reachDelta(at);
              Log.getLogger().info("WBENCH RESULT pathcalc limited={} default={} refused={} default2D={} violations={}", d[0], d[1], d[2], ReachAudit.defaultMax2D(),
                ReachAudit.violations());
              helper.assertTrue(d[1] > 0, "B was not placed by the default placement: the test did not test it (limited " + d[0] + ")");
          }, 0);
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

    /**
     * The same box with no scaffolding at all (none in the hut, none coming): the builder is outside the walls, the middle of the roof is out of reach from
     * anywhere he can walk to. He gives the blocks up for later, and in the second round he is put on a spot that reaches them (inside the box), so the roof is
     * built whole and no block is placed out of reach. (The flake of the worker_eff clear benchmarks, which build a box with the door blocked by dirt: the
     * builder who ended up outside the box with the last roof block left abandoned it.)
     */
    public static void sealedRoofNoItems(final GameTestHelper helper)
    {
        final int teleports0 = com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.teleports;
        tallStructure(helper, "sealed_noitems", () -> sealedBox("sealed_noitems"), true, 30_000, false, BuilderScaffoldGameTests::buildSealedWalls, () ->
        {
            helper.assertTrue(com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.teleports > teleports0, "the builder was never put on a spot that reaches the roof");
        }, 0);
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

    /** The 7 by 7 ring with the second builder idle (collaboration off): what the one builder takes, for the benchmark. */
    public static void twoBuildersSolo(final GameTestHelper helper)
    {
        twoBuilders(helper, "two_solo", () -> shape("two_solo", 7, 11, 7, (x, y, z) -> x == 0 || z == 0 || x == 6 || z == 6), 40_000, false);
    }

    /** The 11 by 11 ring with the second builder idle (collaboration off). */
    public static void twoBuildersBigSolo(final GameTestHelper helper)
    {
        twoBuilders(helper, "twobig_solo", () -> shape("twobig_solo", 11, 12, 11, (x, y, z) -> x == 0 || z == 0 || x == 10 || z == 10), 40_000, false);
    }

    /** A long low wall, 30 long, 3 high and 6 thick, with two builders (540 blocks, in reach of the ground except for the top layer). */
    static Blueprint longWall(final String name)
    {
        return shape(name, 30, 3, 6, (x, y, z) -> true);
    }

    /** A tall square tower: the ring of 9 by 9, 16 high (512 blocks). */
    static Blueprint squareTower(final String name)
    {
        return shape(name, 9, 16, 9, (x, y, z) -> x == 0 || z == 0 || x == 8 || z == 8);
    }

    /** The wall is 30 long: it starts further to the west so all of it lies in the area that is loaded. */
    static final BlockPos WALL_SITE = new BlockPos(4, 1, 14);

    public static void twoBuildersWall(final GameTestHelper helper)
    {
        twoBuilders(helper, "wall", () -> longWall("wall"), 40_000, true, WALL_SITE);
    }

    public static void twoBuildersWallSolo(final GameTestHelper helper)
    {
        twoBuilders(helper, "wall_solo", () -> longWall("wall_solo"), 40_000, false, WALL_SITE);
    }

    /** A slim ring of 7 by 7, 16 high: 384 blocks, tall but under the 400 of the clusters. */
    public static void twoBuildersMid(final GameTestHelper helper)
    {
        twoBuilders(helper, "mid", () -> shape("mid", 7, 16, 7, (x, y, z) -> x == 0 || z == 0 || x == 6 || z == 6), 40_000);
    }

    public static void twoBuildersMidSolo(final GameTestHelper helper)
    {
        twoBuilders(helper, "mid_solo", () -> shape("mid_solo", 7, 16, 7, (x, y, z) -> x == 0 || z == 0 || x == 6 || z == 6), 40_000, false);
    }

    public static void twoBuildersTower16(final GameTestHelper helper)
    {
        twoBuilders(helper, "tower16", () -> squareTower("tower16"), 40_000);
    }

    public static void twoBuildersTower16Solo(final GameTestHelper helper)
    {
        twoBuilders(helper, "tower16_solo", () -> squareTower("tower16_solo"), 40_000, false);
    }

    static void twoBuilders(final GameTestHelper helper, final String name, final Supplier<Blueprint> blueprintOf, final int maxTicks)
    {
        twoBuilders(helper, name, blueprintOf, maxTicks, true);
    }

    static void twoBuilders(final GameTestHelper helper, final String name, final Supplier<Blueprint> blueprintOf, final int maxTicks, final boolean collaboration)
    {
        twoBuilders(helper, name, blueprintOf, maxTicks, collaboration, SITE);
    }

    static void twoBuilders(final GameTestHelper helper, final String name, final Supplier<Blueprint> blueprintOf, final int maxTicks, final boolean collaboration, final BlockPos site)
    {
        twoBuilders(helper, name, blueprintOf, maxTicks, collaboration, site, null, null);
    }

    /**
     * @param beforeOrder called with the scene before the order is made, or null.
     * @param extraChecks called at the end with the scene and the blocks the second builder placed, or null.
     */
    static void twoBuilders(final GameTestHelper helper, final String name, final Supplier<Blueprint> blueprintOf, final int maxTicks, final boolean collaboration, final BlockPos site,
      @org.jetbrains.annotations.Nullable final java.util.function.Consumer<WorkerEfficiencyGameTests.Live> beforeOrder,
      @org.jetbrains.annotations.Nullable final java.util.function.ObjIntConsumer<WorkerEfficiencyGameTests.Live> extraChecks)
    {
        WorkerSettings.overrideReachForTests(null, null, null, 16);
        ReachAudit.enable(true);
        BuilderScaffolding.resetCounters();
        WorkerEfficiencyGameTests.scene(helper, "WSCAF " + name, live -> {
            BuilderCollab.overrideForTests(collaboration && !Boolean.getBoolean("minecolonies.gametest.nocollab"), 3, 40, Integer.getInteger("minecolonies.gametest.minremaining", 10), Integer.getInteger("minecolonies.gametest.stay", 100_000), Integer.getInteger("minecolonies.gametest.lease", 8), 2400);
            BuilderCollab.overrideClusterMinForTests(Integer.getInteger("minecolonies.gametest.clustermin"));
            // (the benchmarks run with the helper gate of the config's default, -Dminecolonies.gametest.minorder=0 switches it off to see what a helper does anyway)
            BuilderCollab.overrideMinOrderScoreForTests(Integer.getInteger("minecolonies.gametest.minorder", BuilderCollab.DEFAULT_MIN_ORDER_SCORE));
            BuilderCollab.overrideLeaseCapFactorForTests(Integer.getInteger("minecolonies.gametest.leasecap"));
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
            if (beforeOrder != null)
            {
                beforeOrder.accept(live);
            }
            final WorkOrderDecoration order = live.orderFor(name, site, blueprint);
            final BlockPos origin = order.getLocation();
            final Watch watch = new Watch();
            final int[] doneTick = {0};
            final java.util.Map<BlockPos, Integer> blocksBy = new java.util.concurrent.ConcurrentHashMap<>();
            final java.util.Map<BlockPos, Long> lastPlaced = new java.util.concurrent.ConcurrentHashMap<>();
            final java.util.Map<BlockPos, long[]> gaps = new java.util.concurrent.ConcurrentHashMap<>();
            BuilderCollab.placementProbe = (pos, hut) -> {
                if (pos.distManhattan(origin) < 200)
                {
                    blocksBy.merge(hut, 1, Integer::sum);
                    final long now = live.level.getGameTime();
                    final Long before = lastPlaced.put(hut, now);
                    if (before != null)
                    {
                        final long gap = now - before;
                        final long[] h = gaps.computeIfAbsent(hut, k -> new long[10]);
                        // [count<=2, ticks<=2, count<=5, ticks<=5, count<=12, ticks<=12, count<=40, ticks<=40, count>40, ticks>40]
                        final int b = gap <= 2 ? 0 : gap <= 5 ? 2 : gap <= 12 ? 4 : gap <= 40 ? 6 : 8;
                        h[b]++;
                        h[b + 1] += gap;
                    }
                }
            };
            WorkerEfficiencyGameTests.everyTick(live, maxTicks, tick -> {
                watch(live, watch, origin, 64, tick);
                trace(live, name, tick, origin);
                if (TRACE_EVERY > 0 && tick % TRACE_EVERY == 0)
                {
                    second.getEntity().ifPresent(e -> trace(e, name + "-2", tick, origin));
                    final java.util.function.Predicate<ItemStack> block = stack -> stack.is(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem());
                    Log.getLogger().info("TRACE {} items t={} lead(inv={} hut={}) helper(inv={} hut={} carry={}) scaffold(lead inv={} hut={}, helper inv={} hut={})", name, tick,
                      InventoryUtils.getItemCountInItemHandler(live.citizen.getInventory(), block), InventoryUtils.getItemCountInItemHandler(live.hut.getItemHandlerCap(), block),
                      InventoryUtils.getItemCountInItemHandler(second.getInventory(), block), InventoryUtils.getItemCountInItemHandler(other.getItemHandlerCap(), block), other.getCarry().values(),
                      InventoryUtils.getItemCountInItemHandler(live.citizen.getInventory(), s2 -> s2.is(Items.SCAFFOLDING)), InventoryUtils.getItemCountInItemHandler(live.hut.getItemHandlerCap(), s2 -> s2.is(Items.SCAFFOLDING)),
                      InventoryUtils.getItemCountInItemHandler(second.getInventory(), s2 -> s2.is(Items.SCAFFOLDING)), InventoryUtils.getItemCountInItemHandler(other.getItemHandlerCap(), s2 -> s2.is(Items.SCAFFOLDING)));
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
                for (final java.util.Map.Entry<BlockPos, long[]> e : gaps.entrySet())
                {
                    Log.getLogger().info("WGAPS {} {} (count,ticks) <=2:{},{} <=5:{},{} <=12:{},{} <=40:{},{} >40:{},{}", name, e.getKey().equals(live.hut.getPosition()) ? "lead" : "second",
                      e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3], e.getValue()[4], e.getValue()[5], e.getValue()[6], e.getValue()[7], e.getValue()[8], e.getValue()[9]);
                }
                Log.getLogger().info("WBENCH RESULT " + name + " builders=2 blocksBy={} lead={} helper={} ticks={} towers={} cells={} placedBy(lead,helper)={},{} maxScaffold={}", blocksBy.values(), blocksBy.getOrDefault(live.hut.getPosition(), 0),
                  blocksBy.getOrDefault(other.getPosition(), 0), doneTick[0], BuilderScaffolding.towersBuilt, BuilderScaffolding.cellsPlaced,
                  BuilderScaffolding.placedBy.getOrDefault(live.hut.getPosition(), 0), BuilderScaffolding.placedBy.getOrDefault(other.getPosition(), 0), watch.maxScaffold);
                helper.assertTrue(missing(live, blueprint, origin) == 0, "structure not complete: missing " + missing(live, blueprint, origin));
                helper.assertTrue(scaffoldInWorld(live, origin) == 0, "scaffolding left: " + scaffoldInWorld(live, origin));
                helper.assertTrue(ledgerSize(live) == 0 && ((WorkManager) live.colony.getWorkManager()).getOrphanScaffolds().isEmpty(), "ledger left");
                helper.assertTrue(scaffoldItems(live, origin) == 64, "items not conserved: " + scaffoldItems(live, origin));
                helper.assertTrue(watch.conservationBreak == null, "items lost or made: " + watch.conservationBreak);
                helper.assertTrue(ReachAudit.violations() == 0, "out of reach: " + ReachAudit.examples());
                helper.assertTrue(BuilderScaffolding.cellsPlaced > 0 || blueprint.getSizeY() <= 4, "no scaffolding");
                if (extraChecks != null)
                {
                    extraChecks.accept(live, blocksBy.getOrDefault(other.getPosition(), 0));
                }
                BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
                BuilderCollab.overrideClusterMinForTests(null);
                BuilderCollab.overrideMinOrderScoreForTests(null);
                BuilderCollab.overrideLeaseCapFactorForTests(null);
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
                // builder review c2 N2: the requested tower is waited for (6000 ticks), then the top rows are placed the way MineColonies does: no hole
                helper.assertTrue(missing(live, blueprint, origin) == 0, "a hole: missing " + missing(live, blueprint, origin));
                // 6000 ticks of waiting for the asked-for tower, then the rest of the ring (about 3000 in the c6 launch: done at 15250 incl. the first walks)
                helper.assertTrue(doneTick[0] < 20_000, "the builder took too long: " + doneTick[0]);
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
        }, () -> helper.assertTrue(lastWatch != null && lastWatch.minHeight > -3,
          "the builder fell off the arm into the trench: lowest point " + (lastWatch == null ? "?" : lastWatch.minHeight) + " blocks below the site"));
    }

    /**
     * A column of scaffolding with an arm out of its top (the way to a spot over a drop or a roof): a citizen walks to the spot on top of the end of
     * the arm, back to the ground, and to the spot again. Each walk gets there (the top of the column is a way up where the path turns sideways, a
     * path that starts in a cell of the arm starts there and not at the bottom of the drop under it, and the spot is a destination that is reached
     * every time, not only the first: the recent end nodes of paths are penalised for a while), and he never falls off.
     */
    public static void climbArmTwice(final GameTestHelper helper)
    {
        WorkerEfficiencyGameTests.scene(helper, "WSCAF arm twice", live -> {
            final BlockPos site = helper.absolutePos(SITE);
            // the ground drops away to the north of the column: a trench under the arm
            for (int x = 0; x <= 6; x++)
            {
                for (int z = -6; z <= -1; z++)
                {
                    for (int y = -1; y >= -12; y--)
                    {
                        live.level.setBlock(site.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
            final BlockState stable = Blocks.SCAFFOLDING.defaultBlockState().setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, 0);
            for (int y = 0; y < 5; y++)
            {
                live.level.setBlock(site.offset(3, y, 0), stable, 3);
            }
            for (int i = 1; i <= 3; i++)
            {
                live.level.setBlock(site.offset(3, 4, -i), Blocks.SCAFFOLDING.defaultBlockState().setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, i)
                                                             .setValue(net.minecraft.world.level.block.ScaffoldingBlock.BOTTOM, true), 3);
            }
            final BlockPos stand = site.offset(3, 5, -3);
            final BlockPos ground = site.offset(1, 0, 1);
            final var entity = live.citizen.getEntity().get();
            entity.setPos(ground.getX() + 0.5D, ground.getY(), ground.getZ() + 0.5D);
            // phase 0: up, 1: down, 2: up again; the time each took
            final int[] phase = {0};
            final int[] phaseStart = {0};
            final int[] took = {-1, -1, -1};
            final int[] lowest = {0};
            WorkerEfficiencyGameTests.everyTick(live, 1500, tick -> {
                lowest[0] = Math.min(lowest[0], entity.blockPosition().getY() - site.getY());
                final boolean up = phase[0] != 1;
                final int walked = up ? com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils.walkToBlock(entity, stand)
                                      : (com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils.walkToPos(entity, ground, 1, false) ? 1 : 0);
                if (walked == 1)
                {
                    took[phase[0]] = tick - phaseStart[0];
                    phase[0]++;
                    phaseStart[0] = tick;
                    return phase[0] == 3;
                }
                if (walked == -1 || tick - phaseStart[0] > 450)
                {
                    helper.fail("phase " + phase[0] + " did not get there (walk=" + walked + ") at " + entity.blockPosition() + ", took " + java.util.Arrays.toString(took));
                    return true;
                }
                return false;
            }, () -> {
                helper.assertTrue(lowest[0] > -3, "he fell: lowest point " + lowest[0] + " below the site");
                helper.succeed();
            }, () -> "phase " + phase[0] + " took " + java.util.Arrays.toString(took) + " at " + entity.blockPosition());
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

    /**
     * r2 Sol H4: scaffolding goes only on land the builder's colony may build on: its own land or land nobody claims. Colony A is founded on the test
     * plot, colony B (another owner) 140 blocks away. A cell in A's land is A's to build on and never B's; an unclaimed cell is anybody's; a planted flower,
     * sapling or crop is never replaced by a tower, grass and air are.
     */
    public static void escapeClaims(final GameTestHelper helper)
    {
        final net.minecraft.server.level.ServerLevel level = helper.getLevel();
        final com.minecolonies.api.colony.IColony a = MinecoloniesGameTests.foundGameTestColony(helper, "scaffold claims A");
        com.minecolonies.core.colony.ColonyLimits.overrideMaxPerPlayer(2);
        final BlockPos hallB = helper.absolutePos(new BlockPos(140, 1, 2));
        level.setChunkForced(hallB.getX() >> 4, hallB.getZ() >> 4, true);
        for (int x = -2; x <= 2; x++)
        {
            for (int z = -2; z <= 2; z++)
            {
                level.setBlock(hallB.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        level.setBlock(hallB, com.minecolonies.api.blocks.ModBlocks.blockHutTownHall.defaultBlockState(), 3);
        final com.minecolonies.core.tileentities.TileEntityColonyBuilding hut = (com.minecolonies.core.tileentities.TileEntityColonyBuilding) level.getBlockEntity(hallB);
        helper.assertTrue(hut != null, "town hall B has no tile entity");
        hut.setPackName("Minecolonies Original");
        hut.setBlueprintPath("fundamentals/townhall1.blueprint");
        final com.minecolonies.api.colony.IColony b = com.minecolonies.api.colony.IColonyManager.getInstance()
          .createColony(level, hallB, MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper), "scaffold claims B", "Minecolonies Original");
        com.minecolonies.core.colony.ColonyLimits.overrideMaxPerPlayer(null);
        helper.assertTrue(b != null && b.getID() != a.getID(), "colony B not created: " + b);

        final BlockPos inA = helper.absolutePos(new BlockPos(6, 2, 6));
        helper.assertTrue(BuilderScaffolding.mayBuildOn(level, inA, a.getID()), "A may not put scaffolding on its own land");
        helper.assertTrue(!BuilderScaffolding.mayBuildOn(level, inA, b.getID()), "B may put scaffolding on A's land");
        final BlockPos inB = hallB.above(2);
        helper.assertTrue(BuilderScaffolding.mayBuildOn(level, inB, b.getID()) && !BuilderScaffolding.mayBuildOn(level, inB, a.getID()), "B's land is not B's alone");
        BlockPos free = null;
        for (int dz = 80; dz <= 400 && free == null; dz += 16)
        {
            final BlockPos pos = inA.offset(0, 0, dz);
            final com.minecolonies.api.colony.claim.IChunkClaimData claim = com.minecolonies.api.colony.IColonyManager.getInstance()
              .getClaimData(level.dimension(), net.minecraft.world.level.ChunkPos.containing(pos));
            if (claim == null || claim.getOwningColony() == 0)
            {
                free = pos;
            }
        }
        helper.assertTrue(free != null, "no unclaimed chunk found near the plot");
        helper.assertTrue(BuilderScaffolding.mayBuildOn(level, free, a.getID()) && BuilderScaffolding.mayBuildOn(level, free, b.getID()), "unclaimed land is refused");

        helper.assertTrue(BuilderScaffolding.mayReplace(Blocks.AIR.defaultBlockState()) && BuilderScaffolding.mayReplace(Blocks.SHORT_GRASS.defaultBlockState()),
          "air or grass may not be replaced by scaffolding");
        for (final Block planted : new Block[] {Blocks.POPPY, Blocks.OAK_SAPLING, Blocks.WHEAT})
        {
            helper.assertTrue(!BuilderScaffolding.mayReplace(planted.defaultBlockState()), planted + " may be replaced by scaffolding");
        }
        level.setChunkForced(hallB.getX() >> 4, hallB.getZ() >> 4, false);
        helper.succeed();
    }

    /**
     * r2 Fable N2, N10, Opus R2-F4: one rule for a block a watched builder cannot reach (kept for later WATCHED_KEEPS times, then put by it in sight, so
     * watching leaves no hole; the watch is not even asked once the keeps are used up), and one stuck notice per episode with words that fit (the
     * in-site notice has its own text and validator).
     */
    public static void watchedPolicy(final GameTestHelper helper)
    {
        final int keeps = com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.WATCHED_KEEPS;
        for (int kept = 0; kept < keeps; kept++)
        {
            helper.assertTrue(com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.keepForWatcher(kept, () -> true), "a watched block is not kept at keep " + kept);
            helper.assertTrue(!com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.keepForWatcher(kept, () -> false), "an unwatched block is kept at keep " + kept);
        }
        final boolean[] asked = {false};
        helper.assertTrue(!com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.keepForWatcher(keeps, () -> asked[0] = true),
          "a watched block is kept for ever: watching leaves a hole");
        helper.assertTrue(!asked[0], "the watch was asked after the keeps were used up");

        final long gap = com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic.STUCK_NOTICE_GAP;
        helper.assertTrue(com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic.newStuckEpisode(10_000L, Long.MIN_VALUE / 2), "the first stuck is no episode");
        helper.assertTrue(!com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic.newStuckEpisode(10_000L + gap / 2, 10_000L),
          "a second unreachable block soon after brings a second notice");
        helper.assertTrue(com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic.newStuckEpisode(10_000L + gap + 1, 10_000L), "a stuck long after the last brings none");

        final String block = com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic.BLOCK_NOTICE;
        final String stuck = com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic.STUCK_NOTICE;
        helper.assertTrue(!block.equals(stuck), "the in-site notice shares the stuck notice's words");
        helper.assertTrue(com.minecolonies.api.colony.interactionhandling.InteractionValidatorRegistry
                            .getStandardInteractionValidatorPredicate(net.minecraft.network.chat.Component.translatableEscape(block)) != null, "no validator for the in-site notice");
        helper.assertTrue(net.minecraft.locale.Language.getInstance().has(block) || !net.minecraft.locale.Language.getInstance().has(stuck),
          "the in-site notice has no text");
        helper.succeed();
    }

    /**
     * Round 4 (r3 Fable N13, N19): the watch rules after the bounded wait.
     * <ul>
     *   <li>the walk ladder counts each keep for a watcher, as the reach rule does: a block he can reach from somewhere but not walk to is kept
     *   {@code WATCHED_KEEPS} times, then he is put by it in sight all the same (the block notice's promise); an unwatched block is never kept or counted;</li>
     *   <li>the watch spares citizens only: a raider wedged in sight of a defender still takes the complete-stuck damage.</li>
     * </ul>
     */
    public static void watchRulesR4(final GameTestHelper helper)
    {
        final int keeps = com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.WATCHED_KEEPS;
        final java.util.Map<BlockPos, Integer> kept = new java.util.HashMap<>();
        final java.util.Set<BlockPos> keep = new java.util.HashSet<>();
        final BlockPos block = helper.absolutePos(new BlockPos(3, 4, 3));
        for (int round = 0; round < keeps; round++)
        {
            helper.assertTrue(com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.keepCountedForWatcher(kept, keep, block, () -> true),
              "a watched block is not kept in round " + round);
            helper.assertTrue(kept.getOrDefault(block, 0) == round + 1, "the walk ladder does not count the keep of round " + round + ": " + kept.get(block));
            helper.assertTrue(keep.contains(block), "a kept block is not kept for the next round");
        }
        helper.assertTrue(!com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.keepCountedForWatcher(kept, keep, block, () -> true),
          "the walk ladder keeps a block for ever while a player watches: the promised move never comes");
        final BlockPos other = block.east();
        helper.assertTrue(!com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.keepCountedForWatcher(kept, keep, other, () -> false)
                            && !kept.containsKey(other) && !keep.contains(other), "an unwatched block is kept or counted");

        final net.minecraft.server.level.ServerPlayer watcher = MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper);
        com.minecolonies.core.entity.pathfinding.PlayerWatch.watchForTests(watcher.getUUID());
        final var raider = helper.spawn(com.minecolonies.api.entity.ModEntities.BARBARIAN, new BlockPos(4, 2, 4));
        watcher.teleportTo(raider.getX() + 3.0D, raider.getY(), raider.getZ());
        try
        {
            helper.assertTrue(com.minecolonies.core.entity.pathfinding.PlayerWatch.watched(helper.getLevel(), raider.position(), (BlockPos) null),
              "fixture: the player does not see the raider");
            helper.assertTrue(!com.minecolonies.core.entity.pathfinding.navigation.PathingStuckHandler.watchedCitizen(raider, helper.getLevel(), null),
              "a raider in sight counts as a watched citizen");
            helper.assertTrue(raider.getNavigation() instanceof com.minecolonies.core.entity.pathfinding.navigation.MinecoloniesAdvancedPathNavigate nav
                                && nav.getStuckHandler() instanceof com.minecolonies.core.entity.pathfinding.navigation.PathingStuckHandler<?>, "fixture: the raider has no stuck handler");
            final var nav = (com.minecolonies.core.entity.pathfinding.navigation.MinecoloniesAdvancedPathNavigate) raider.getNavigation();
            @SuppressWarnings("unchecked")
            final var handler = (com.minecolonies.core.entity.pathfinding.navigation.PathingStuckHandler<com.minecolonies.core.entity.pathfinding.navigation.MinecoloniesAdvancedPathNavigate>) nav.getStuckHandler();
            raider.setHealth(raider.getMaxHealth());
            final float before = raider.getHealth();
            handler.completeStuckForTests(nav);
            helper.assertTrue(raider.getHealth() < before, "a raider stuck in sight of a player took no stuck damage: health " + raider.getHealth() + " of " + before);
        }
        finally
        {
            raider.discard();
            watcher.discard();
        }
        helper.succeed();
    }
}
