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
            for (final net.minecraft.world.item.Item tool : List.of(Items.IRON_PICKAXE, Items.IRON_SHOVEL, Items.IRON_AXE))
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
              : e.getCitizenJobHandler().getWorkAI().getStateAI().getState() + " history=" + e.getCitizenJobHandler().getWorkAI().getStateAI().getHistory().getString()).orElse("no-entity");
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
        live.hut = (BuildingBuilder) MinecoloniesGameTests.placeProductionBuilding(helper, live.colony, ModBlocks.blockHutBuilder, new BlockPos(8, 1, 2), "fundamentals/builder1.blueprint");
        final BlockPos anchor = helper.absolutePos(new BlockPos(10, 1, 8));
        forceArea(live.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(40, 1, 32)));
        BuilderCollab.overrideForTests(false, 3, 10_000, 10, 100_000, 8, 2400);
        helper.runAfterDelay(200, () -> {
            forceArea(live.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(40, 1, 32)));
            live.citizen = live.colony.getCitizenManager().spawnOrCreateCivilian(null, live.level, List.of(anchor), true);
            helper.assertTrue(live.citizen != null && live.citizen.getEntity().isPresent(), "builder did not spawn");
            helper.assertTrue(live.hut.getModule(BuildingModules.BUILDER_WORK).assignCitizen(live.citizen), "builder not assigned");
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
        final boolean[] finished = {false};
        live.helper.onEachTick(() -> {
            if (finished[0])
            {
                return;
            }
            elapsed[0]++;
            if (elapsed[0] % 20 == 0)
            {
                live.level.getServer().clockManager().setTotalTicks(live.level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
                live.colony.getWorkManager().onColonyTick(live.colony);
            }
            if (step.test(elapsed[0]))
            {
                finished[0] = true;
                onDone.run();
            }
            else if (elapsed[0] >= maxTicks)
            {
                finished[0] = true;
                live.helper.assertTrue(false, "timeout after " + maxTicks + " ticks: " + failure.get());
            }
        });
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
    private static void bench(final GameTestHelper helper, final String name, final int skill, final boolean terrain)
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
}
