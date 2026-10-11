package com.minecolonies.core.gametest;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.util.RotationMirror;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.core.colony.workorders.WorkOrderDecoration;
import com.minecolonies.core.colony.workorders.collab.BuilderCollab;
import com.minecolonies.core.entity.pathfinding.Pathfinding;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Supplier;

/**
 * L3 worker efficiency: a build benchmark with a real builder (real AI, real pathing) that records ticks to complete, blocks per minute, the
 * distance walked and the path jobs started, and failing-first GameTests for the "builder gets stuck" cases. The benchmark lines start with
 * {@code WBENCH} in the log; the numbers in the commit messages come from them.
 */
public final class WorkerEfficiencyGameTests
{
    private WorkerEfficiencyGameTests()
    {
    }

    /** Footprint of the test structure: 9 by 9, 5 high (floor, three wall layers, roof). */
    static final int SIZE = 9;
    static final int HEIGHT = 5;
    static final Block BUILD_BLOCK = Blocks.STONE_BRICKS;
    static final int SPARE = 24;
    static final Block WOOL_BLOCK = Blocks.WOOL.pick(DyeColor.WHITE);

    // ------------------------------------------------------------------ fixture

    /**
     * A colony with one builder hut and a live builder (entity and AI).
     */
    static final class Live
    {
        final GameTestHelper helper;
        final ServerLevel level;
        IColony colony;
        BuildingBuilder hut;
        ICitizenData citizen;

        Live(final GameTestHelper helper)
        {
            this.helper = helper;
            this.level = helper.getLevel();
        }

        /**
         * The 9x9x5 box: floor, three wall layers (a door gap in the middle of the south wall), roof.
         */
        Blueprint box(final String name)
        {
            final Blueprint blueprint = new Blueprint((short) SIZE, (short) HEIGHT, (short) SIZE).setName(name).setFileName(name).setPackName("Minecolonies Original");
            for (int y = 0; y < HEIGHT; y++)
            {
                for (int x = 0; x < SIZE; x++)
                {
                    for (int z = 0; z < SIZE; z++)
                    {
                        final boolean floorOrRoof = y == 0 || y == HEIGHT - 1;
                        final boolean wall = x == 0 || z == 0 || x == SIZE - 1 || z == SIZE - 1;
                        final boolean door = x == SIZE / 2 && z == 0 && (y == 1 || y == 2);
                        if ((floorOrRoof || wall) && !door)
                        {
                            blueprint.addBlockState(new BlockPos(x, y, z), BUILD_BLOCK.defaultBlockState());
                        }
                    }
                }
            }
            blueprint.setCachePrimaryOffset(BlockPos.ZERO);
            return blueprint;
        }

        int blocksInBox()
        {
            int n = 0;
            for (int y = 0; y < HEIGHT; y++)
            {
                for (int x = 0; x < SIZE; x++)
                {
                    for (int z = 0; z < SIZE; z++)
                    {
                        final boolean floorOrRoof = y == 0 || y == HEIGHT - 1;
                        final boolean wall = x == 0 || z == 0 || x == SIZE - 1 || z == SIZE - 1;
                        final boolean door = x == SIZE / 2 && z == 0 && (y == 1 || y == 2);
                        if ((floorOrRoof || wall) && !door)
                        {
                            n++;
                        }
                    }
                }
            }
            return n;
        }

        WorkOrderDecoration order(final String name, final BlockPos rel)
        {
            final WorkOrderDecoration order = WorkOrderDecoration.create(WorkOrderType.BUILD, "Minecolonies Original", "gametest/" + name + ".blueprint", "gametest." + name,
              helper.absolutePos(rel), RotationMirror.NONE, 0);
            order.setBlueprint(box(name), level);
            colony.getWorkManager().addWorkOrder(order, true);
            helper.assertTrue(order.getID() > 0, "no id for " + name);
            order.setClaimedBy(hut.getID());
            hut.setWorkOrder(order);
            return order;
        }

        /**
         * An order for a blueprint of the test.
         */
        WorkOrderDecoration orderFor(final String name, final BlockPos rel, final Blueprint blueprint)
        {
            final WorkOrderDecoration order = WorkOrderDecoration.create(WorkOrderType.BUILD, "Minecolonies Original", "gametest/" + name + ".blueprint", "gametest." + name,
              helper.absolutePos(rel), RotationMirror.NONE, 0);
            order.setBlueprint(blueprint, level);
            colony.getWorkManager().addWorkOrder(order, true);
            helper.assertTrue(order.getID() > 0, "no id for " + name);
            order.setClaimedBy(hut.getID());
            hut.setWorkOrder(order);
            return order;
        }

        void stock(final net.minecraft.world.item.Item item, int amount)
        {
            while (amount > 0)
            {
                final int n = Math.min(amount, item.getDefaultMaxStackSize());
                helper.assertTrue(InventoryUtils.forceItemStackToItemHandler(hut.getItemHandlerCap(), new ItemStack(item, n), s -> true).isEmpty(), "no room in the hut for " + item);
                amount -= n;
            }
        }

        void giveTools()
        {
            // wooden tools wear out (59 uses): enough shovels for the dirt of the clear tests, the hut is too low for better tools
            for (final net.minecraft.world.item.Item tool : List.of(Items.WOODEN_PICKAXE, Items.WOODEN_PICKAXE, Items.WOODEN_AXE, Items.WOODEN_SHOVEL, Items.WOODEN_SHOVEL,
              Items.WOODEN_SHOVEL, Items.WOODEN_SHOVEL, Items.WOODEN_SHOVEL, Items.WOODEN_SHOVEL, Items.WOODEN_SHOVEL))
            {
                InventoryUtils.addItemStackToItemHandler(citizen.getInventory(), new ItemStack(tool));
            }
        }

        void setSkills(final int adaptability, final int athletics)
        {
            citizen.getCitizenSkillHandler().incrementLevel(Skill.Adaptability, adaptability - citizen.getCitizenSkillHandler().getLevel(Skill.Adaptability));
            citizen.getCitizenSkillHandler().incrementLevel(Skill.Athletics, athletics - citizen.getCitizenSkillHandler().getLevel(Skill.Athletics));
        }

        String ai()
        {
            return citizen.getEntity().map(e -> e.getCitizenJobHandler().getWorkAI() == null ? "no-ai"
              : e.getCitizenJobHandler().getWorkAI().getStateAI().getState() + " history=" + e.getCitizenJobHandler().getWorkAI().getStateAI().getHistory().getString()
                  + (e.getCitizenJobHandler().getWorkAI() instanceof com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder b ? " " + b.debugInfo() : "")).orElse("no-entity")
                     + " open=" + hut.getOpenRequests(citizen.getId()).stream().map(r -> String.valueOf(r.getRequest())).toList()
                     + " buildingOpen=" + hut.getOpenRequests(-1).stream().map(r -> String.valueOf(r.getRequest())).toList();
        }
    }

    /**
     * Builds the scene (colony, builder hut, a live builder next to it) and calls {@code ready} when the builder exists.
     */
    static void scene(final GameTestHelper helper, final String name, final java.util.function.Consumer<Live> ready)
    {
        MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper);
        final Live live = new Live(helper);
        live.colony = MinecoloniesGameTests.foundGameTestColony(helper, name);
        // the same scene as the builder collaboration tests: two builder huts (the second one stays idle, collaboration is off) with a citizen each
        live.hut = (BuildingBuilder) MinecoloniesGameTests.placeProductionBuilding(helper, live.colony, ModBlocks.blockHutBuilder, new BlockPos(8, 1, 2), "fundamentals/builder1.blueprint");
        final BuildingBuilder other = (BuildingBuilder) MinecoloniesGameTests.placeProductionBuilding(helper, live.colony, ModBlocks.blockHutBuilder, new BlockPos(16, 1, 2), "fundamentals/builder1.blueprint");
        final BlockPos anchor = helper.absolutePos(new BlockPos(10, 1, 8));
        final BlockPos otherAnchor = helper.absolutePos(new BlockPos(18, 1, 8));
        forceArea(live.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(40, 1, 32)));
        BuilderCollab.overrideForTests(false, 3, 10_000, 10, 100_000, 8, 2400);
        helper.runAfterDelay(200, () -> {
            forceArea(live.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(40, 1, 32)));
            live.citizen = live.colony.getCitizenManager().spawnOrCreateCivilian(null, live.level, List.of(anchor), true);
            final ICitizenData second = live.colony.getCitizenManager().spawnOrCreateCivilian(null, live.level, List.of(otherAnchor), true);
            helper.assertTrue(live.citizen != null && second != null && live.citizen.getEntity().isPresent(), "builder did not spawn");
            // the colony hires its first citizens into the free huts by itself: let go of them, the test works with the citizens it spawned
            for (final BuildingBuilder hut : List.of(live.hut, other))
            {
                for (final ICitizenData hired : new java.util.ArrayList<>(hut.getModule(BuildingModules.BUILDER_WORK).getAssignedCitizen()))
                {
                    hut.getModule(BuildingModules.BUILDER_WORK).removeCitizen(hired);
                }
            }
            helper.assertTrue(live.hut.getModule(BuildingModules.BUILDER_WORK).assignCitizen(live.citizen), "builder not assigned: assigned="
              + live.hut.getModule(BuildingModules.BUILDER_WORK).getAssignedCitizen() + " full=" + live.hut.getModule(BuildingModules.BUILDER_WORK).isFull() + " job=" + live.citizen.getJob());
            helper.assertTrue(other.getModule(BuildingModules.BUILDER_WORK).assignCitizen(second), "second builder not assigned");
            live.citizen.getEntity().ifPresent(e -> e.setPos(live.hut.getID().getX() + 0.5D, live.hut.getID().getY(), live.hut.getID().getZ() + 0.5D));
            ready.accept(live);
        });
    }

    static void forceArea(final ServerLevel level, final BlockPos min, final BlockPos max)
    {
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++)
        {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++)
            {
                level.setChunkForced(cx, cz, true);
            }
        }
    }

    /**
     * Runs {@code step} every tick (also holds the clock at noon and ticks the work manager once a second, like a running colony) until it returns
     * true or the budget is spent.
     */
    static void everyTick(final Live live, final int maxTicks, final java.util.function.IntPredicate step, final Runnable onDone, final Supplier<String> failure)
    {
        final int[] elapsed = {0};
        // a fresh Runnable per tick: the GameTest framework keys its callbacks by Runnable
        final Runnable[] next = new Runnable[1];
        next[0] = () -> {
            elapsed[0]++;
            if (elapsed[0] % 20 == 0)
            {
                live.level.getServer().clockManager().setTotalTicks(live.level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
                live.colony.getWorkManager().onColonyTick(live.colony);
            }
            if (step.test(elapsed[0]))
            {
                onDone.run();
            }
            else if (elapsed[0] >= maxTicks)
            {
                live.helper.assertTrue(false, "timeout after " + maxTicks + " ticks: " + failure.get());
            }
            else
            {
                live.helper.runAfterDelay(1, () -> next[0].run());
            }
        };
        live.helper.runAfterDelay(1, () -> next[0].run());
    }

    // ------------------------------------------------------------------ benchmark

    private static boolean boxBuilt(final Live live, final BlockPos origin)
    {
        for (int y = 0; y < HEIGHT; y++)
        {
            for (int x = 0; x < SIZE; x++)
            {
                for (int z = 0; z < SIZE; z++)
                {
                    final boolean floorOrRoof = y == 0 || y == HEIGHT - 1;
                    final boolean wall = x == 0 || z == 0 || x == SIZE - 1 || z == SIZE - 1;
                    final boolean door = x == SIZE / 2 && z == 0 && (y == 1 || y == 2);
                    if ((floorOrRoof || wall) && !door && !live.level.getBlockState(origin.offset(x, y, z)).is(BUILD_BLOCK))
                    {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Builds the 9x9x5 box with a real builder of the given skills and logs ticks to complete, blocks per minute, the distance walked and the path
     * jobs started. With {@code terrain} the footprint is full of dirt two layers deep first, which the clear stage has to mine.
     */
    static void bench(final GameTestHelper helper, final String name, final int skill, final boolean terrain)
    {
        scene(helper, "WBENCH " + name, live -> {
            live.setSkills(skill, skill);
            live.giveTools();
            live.stock(BUILD_BLOCK.asItem(), live.blocksInBox() + SPARE);
            final BlockPos rel = new BlockPos(24, 1, 10);
            if (terrain)
            {
                for (int x = 0; x < SIZE; x++)
                {
                    for (int z = 0; z < SIZE; z++)
                    {
                        helper.setBlock(rel.offset(x, 0, z), Blocks.DIRT.defaultBlockState());
                        helper.setBlock(rel.offset(x, 1, z), Blocks.DIRT.defaultBlockState());
                    }
                }
            }
            final WorkOrderDecoration order = live.order(name, rel);
            final BlockPos origin = order.getLocation();
            final Vec3[] last = {live.citizen.getEntity().get().position()};
            final double[] walked = {0};
            final int[] doneTick = {0};
            final long paths0 = Pathfinding.Stats.submitted.get();
            everyTick(live, 38_000, tick -> {
                final Vec3 now = live.citizen.getEntity().map(e -> e.position()).orElse(last[0]);
                walked[0] += Math.hypot(now.x - last[0].x, now.z - last[0].z);
                last[0] = now;
                if (tick % 2000 == 0)
                {
                    Log.getLogger().info("WBENCH {} t={} {}", name, tick, live.ai());
                }
                doneTick[0] = tick;
                return live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder();
            }, () -> {
                helper.assertTrue(boxBuilt(live, origin), "box not complete");
                final int ticks = doneTick[0];
                final int blocks = live.blocksInBox() + (terrain ? 2 * SIZE * SIZE : 0);
                Log.getLogger().info("WBENCH RESULT {} skill={} terrain={} ticks={} blocks={} blocksPerMin={} walk={} paths={}", name, skill, terrain, ticks, blocks,
                  String.format("%.1f", blocks * 1200.0D / ticks), Math.round(walked[0]), Pathfinding.Stats.submitted.get() - paths0);
                BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
                helper.succeed();
            }, () -> live.ai() + " order=" + live.hut.getWorkOrder() + " progress=" + live.hut.getProgress());
        });
    }

    public static void benchPlaceL1(final GameTestHelper helper)
    {
        bench(helper, "place_l1", 1, false);
    }

    public static void benchPlaceL40(final GameTestHelper helper)
    {
        bench(helper, "place_l40", 40, false);
    }

    public static void benchClearL1(final GameTestHelper helper)
    {
        bench(helper, "clear_l1", 1, true);
    }

    public static void benchClearL40(final GameTestHelper helper)
    {
        bench(helper, "clear_l40", 40, true);
    }

    // ------------------------------------------------------------------ stuck cases (failing first)

    /** Where the structure of the stuck tests stands, relative to the test plot. */
    static final BlockPos SITE = new BlockPos(24, 1, 10);

    private static int countBlocks(final Live live, final BlockPos origin, final Block block, final int layers)
    {
        int n = 0;
        for (int y = 0; y < layers; y++)
        {
            for (int x = 0; x < SIZE; x++)
            {
                for (int z = 0; z < SIZE; z++)
                {
                    if (live.level.getBlockState(origin.offset(x, y, z)).is(block))
                    {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /**
     * A builder who fell down a 4 deep shaft inside the site he has to clear (the shaft walls are the terrain he has to mine): he cannot walk
     * out, and the blocks he has to mine are far from where he stands. Before: he stands in the shaft, asks for a path again and again, and
     * only the stuck handler's teleport after 2400 ticks or more gets him out. After: the unstuck ladder puts him on a spot at the edge of
     * the site after about 400 ticks, and the clear stage goes on.
     */
    public static void stuckInShaft(final GameTestHelper helper)
    {
        scene(helper, "WSTUCK shaft", live -> {
            live.setSkills(40, 40);
            live.giveTools();
            live.stock(BUILD_BLOCK.asItem(), live.blocksInBox() + SPARE);
            final BlockPos rel = SITE;
            // 4 layers of dirt over the whole footprint, a shaft down to the floor at (1, 1)
            for (int y = 0; y < 4; y++)
            {
                for (int x = 0; x < SIZE; x++)
                {
                    for (int z = 0; z < SIZE; z++)
                    {
                        helper.setBlock(rel.offset(x, y, z), x == 1 && z == 1 ? Blocks.AIR.defaultBlockState() : Blocks.DIRT.defaultBlockState());
                    }
                }
            }
            final WorkOrderDecoration order = live.order("shaft", rel);
            final BlockPos origin = order.getLocation();
            final BlockPos bottom = origin.offset(1, 0, 1);
            live.citizen.getEntity().ifPresent(e -> e.setPos(bottom.getX() + 0.5D, bottom.getY(), bottom.getZ() + 0.5D));
            final int dirt0 = countBlocks(live, origin, Blocks.DIRT, 4);
            final boolean[] early = {false};
            everyTick(live, 30_000, tick -> {
                if (tick == 1000)
                {
                    final int cleared = dirt0 - countBlocks(live, origin, Blocks.DIRT, 4);
                    Log.getLogger().info("WSTUCK shaft t=1000 cleared={} {}", cleared, live.ai());
                    early[0] = cleared >= 10;
                    helper.assertTrue(early[0], "the builder stood in the shaft for 1000 ticks and cleared only " + cleared + " blocks: " + live.ai());
                }
                return live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder();
            }, () -> {
                helper.assertTrue(boxBuilt(live, origin), "box not complete");
                BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
                helper.succeed();
            }, () -> live.ai() + " dirt left=" + countBlocks(live, origin, Blocks.DIRT, 4));
        });
    }

    /**
     * No tool for the stone the clear stage meets first: only wool (no tool needed) lies below it. Before: the builder waits for a pickaxe at the
     * first stone block for ever and never mines the wool. After: after the tool timeout he leaves the stone for the end of the stage and clears
     * the wool; the request for the pickaxe stays open.
     */
    public static void noToolForStone(final GameTestHelper helper)
    {
        scene(helper, "WSTUCK notool", live -> {
            live.setSkills(40, 40);
            live.stock(BUILD_BLOCK.asItem(), live.blocksInBox() + SPARE);
            final BlockPos rel = SITE;
            for (int x = 0; x < SIZE; x++)
            {
                for (int z = 0; z < SIZE; z++)
                {
                    helper.setBlock(rel.offset(x, 0, z), Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState());
                    helper.setBlock(rel.offset(x, 1, z), Blocks.STONE.defaultBlockState());
                }
            }
            final WorkOrderDecoration order = live.order("notool", rel);
            final BlockPos origin = order.getLocation();
            final int wool0 = countBlocks(live, origin, WOOL_BLOCK, 1);
            everyTick(live, 4000, tick -> {
                if (tick % 250 == 0)
                {
                    Log.getLogger().info("WSTUCK notool t={} wool left={} {}", tick, countBlocks(live, origin, WOOL_BLOCK, 1), live.ai());
                }
                // the stone cannot be mined without a pickaxe, the wool can
                return wool0 - countBlocks(live, origin, WOOL_BLOCK, 1) >= 60;
            }, () -> {
                BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
                helper.succeed();
            }, () -> "the builder waits for a pickaxe at the stone and does not clear the wool: wool left=" + countBlocks(live, origin, WOOL_BLOCK, 1) + " " + live.ai());
        });
    }

    /**
     * A builder whose path target lies in an unloaded chunk twice in a row used to be discarded (a silent loss of a colonist). He has to stay.
     */
    public static void unloadedTargetKeepsCitizen(final GameTestHelper helper)
    {
        scene(helper, "WSTUCK unloaded", live -> {
            final var entity = live.citizen.getEntity().get();
            final BlockPos far = entity.blockPosition().offset(600, 0, 600);
            helper.assertTrue(!com.minecolonies.api.util.WorldUtil.isBlockLoaded(live.level, far), "fixture: the far chunk is loaded");
            final var nav = (com.minecolonies.core.entity.pathfinding.navigation.MinecoloniesAdvancedPathNavigate) entity.getNavigation();
            for (int i = 0; i < 3; i++)
            {
                nav.setPathJob(new com.minecolonies.core.entity.pathfinding.pathjobs.PathJobMoveToLocation(live.level, entity.blockPosition(), far, 20, entity), far, 1.0D, false);
            }
            helper.runAfterDelay(20, () -> {
                helper.assertTrue(entity.isAlive() && !entity.isRemoved(), "the citizen was discarded after two path jobs into an unloaded chunk");
                BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
                helper.succeed();
            });
        });
    }

    /**
     * A block floating high above the ground in the footprint (nothing to stand on within reach of it) is cleared; the clear stage and the
     * building go on. (Physical reach and scaffolding for such blocks are the 3D reach work; this keeps the case from hanging.)
     */
    public static void floatingBlockDoesNotHang(final GameTestHelper helper)
    {
        scene(helper, "WSTUCK floating", live -> {
            live.setSkills(40, 40);
            live.giveTools();
            live.stock(BUILD_BLOCK.asItem(), live.blocksInBox() + SPARE);
            final WorkOrderDecoration order = live.order("floating", SITE);
            final BlockPos origin = order.getLocation();
            final BlockPos floating = origin.offset(4, 4, 4);
            helper.setBlock(SITE.offset(4, 4, 4), Blocks.DIRT.defaultBlockState());
            everyTick(live, 20_000, tick -> live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder(), () -> {
                helper.assertTrue(boxBuilt(live, origin), "box not complete");
                helper.assertTrue(live.level.getBlockState(floating).is(BUILD_BLOCK) || live.level.getBlockState(floating).isAir(), "the floating block is still there: " + live.level.getBlockState(floating));
                BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
                helper.succeed();
            }, () -> live.ai());
        });
    }
}
