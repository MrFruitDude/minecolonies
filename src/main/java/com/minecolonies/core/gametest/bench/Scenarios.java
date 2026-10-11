package com.minecolonies.core.gametest.bench;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.util.RotationMirror;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.ICraftingBuildingModule;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.crafting.RecipeStorage;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.workorders.WorkOrderDecoration;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The macro scenarios (see {@link MacroRun}). Shared building blocks first, then:
 * <ul>
 * <li>{@link #fresh}: a fresh colony with no materials queues six huts; materials must be crafted and gathered.</li>
 * </ul>
 */
public final class Scenarios
{
    // plot layout, relative to the test origin
    public static final int X0 = -4;
    public static final int X1 = 108;
    public static final int Z0 = -4;
    public static final int Z1 = 92;
    public static final BlockPos TOWN_HALL = new BlockPos(50, 1, 44);

    private Scenarios()
    {
    }

    // ------------------------------------------------------------------ shared pieces

    /**
     * The colony core every scenario starts from: town hall, warehouses, courier hut, builder.
     */
    static void core(final MacroRun r, final String name, final int warehouseCount)
    {
        r.found(name, TOWN_HALL, X0, X1, Z0, Z1);
        for (int i = 0; i < warehouseCount; i++)
        {
            final IBuilding wh = r.place(ModBlocks.blockHutWareHouse, 58 + 14 * i, 44, "craftsmanship/storage/warehouse1.blueprint");
            wh.setBuildingLevel(Math.max(1, wh.getBuildingLevel()));
            r.racks(wh, 12);
            r.warehouses.add(wh);
        }
    }

    /**
     * Hires {@code count} couriers into a courier hut and a warehouse.
     */
    static void couriers(final MacroRun r, final IBuilding hut, final IBuilding warehouse, final int count)
    {
        hut.setBuildingLevel(Math.max(count, hut.getBuildingLevel()));
        warehouse.setBuildingLevel(Math.max(count, warehouse.getBuildingLevel()));
        for (int i = 0; i < count; i++)
        {
            final ICitizenData courier = r.hire(hut, () -> hut.getModule(BuildingModules.COURIER_WORK));
            r.helper.assertTrue(warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "courier " + i + " not assigned to the warehouse");
        }
    }

    static void recipe(final IBuilding crafterHut, final com.minecolonies.api.colony.buildings.registry.BuildingEntry.ModuleProducer<?, ?> producer, final List<ItemStack> inputs,
                       final ItemStack output, final int grid)
    {
        @SuppressWarnings({"unchecked", "rawtypes"})
        final ICraftingBuildingModule craft = (ICraftingBuildingModule) crafterHut.getModule((com.minecolonies.api.colony.buildings.registry.BuildingEntry.ModuleProducer) producer);
        final List<ItemStorage> in = new ArrayList<>();
        for (final ItemStack s : inputs)
        {
            in.add(new ItemStorage(s));
        }
        final RecipeStorage recipe = RecipeStorage.builder().withRecipeId(null).withInputs(in).withPrimaryOutput(output).withGridSize(grid).build();
        if (!craft.addRecipe(IColonyManager.getInstance().getRecipeManager().checkOrAddRecipe(recipe)))
        {
            throw new IllegalStateException("crafter rejected the recipe for " + output);
        }
    }

    /**
     * A one-row blueprint of the given blocks, queued as a build order at (ox, oz) of the test plot; its cells are
     * registered as the world state the run expects when it is built.
     *
     * @return the work order's id.
     */
    static int order(final MacroRun r, final String name, final int ox, final int oz, final List<Block> blocks, final int priority)
    {
        final Blueprint blueprint = new Blueprint((short) blocks.size(), (short) 1, (short) 1).setName(name).setFileName(name).setPackName("Minecolonies Original");
        for (int i = 0; i < blocks.size(); i++)
        {
            blueprint.addBlockState(new BlockPos(i, 0, 0), blocks.get(i).defaultBlockState());
        }
        blueprint.setCachePrimaryOffset(BlockPos.ZERO);
        final BlockPos abs = r.helper.absolutePos(new BlockPos(ox, 1, oz));
        final WorkOrderDecoration order = WorkOrderDecoration.create(WorkOrderType.BUILD, "Minecolonies Original", "gametest/" + name + ".blueprint", "rsb." + name, abs,
          RotationMirror.NONE, 0);
        order.setBlueprint(blueprint, r.level);
        order.setPriority(priority);
        r.colony.getWorkManager().addWorkOrder(order, true);
        for (int i = 0; i < blocks.size(); i++)
        {
            r.sites.put(abs.offset(i, 0, 0), blocks.get(i));
        }
        return order.getID();
    }

    static boolean allBuilt(final MacroRun r, final List<Integer> orders)
    {
        for (final int id : orders)
        {
            if (r.colony.getWorkManager().getWorkOrder(id) != null)
            {
                return false;
            }
        }
        for (final var site : r.sites.entrySet())
        {
            if (!r.level.getBlockState(site.getKey()).is(site.getValue()))
            {
                return false;
            }
        }
        return true;
    }

    static List<Block> hutBlocks(final int planks, final int cobble, final int bricks, final int fences)
    {
        final List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < cobble; i++)
        {
            blocks.add(Blocks.COBBLESTONE);
        }
        for (int i = 0; i < planks; i++)
        {
            blocks.add(Blocks.OAK_PLANKS);
        }
        for (int i = 0; i < bricks; i++)
        {
            blocks.add(Blocks.STONE_BRICKS);
        }
        for (int i = 0; i < fences; i++)
        {
            blocks.add(Blocks.OAK_FENCE);
        }
        return blocks;
    }

    // ------------------------------------------------------------------ a) fresh colony, zero materials

    /**
     * Fresh colony: town hall, warehouse, two couriers, a builder; a sawmill, a fletcher and a stonemason; a lumberjack,
     * miner and farmer hut (their output is scripted). Six huts are queued with nothing in stock anywhere. Measures the time
     * until all six stand.
     */
    public static MacroRun.Scenario fresh(final boolean quick)
    {
        return new MacroRun.Scenario()
        {
            private final List<Integer> orders = new ArrayList<>();

            @Override
            public void setup(final MacroRun r)
            {
                core(r, "rsb-fresh", 1);
                final IBuilding wh = r.warehouses.get(0);
                final IBuilding dman = r.place(ModBlocks.blockHutDeliveryman, 66, 44, "craftsmanship/storage/deliveryman1.blueprint");
                final IBuilding builder = r.place(ModBlocks.blockHutBuilder, 42, 44, "fundamentals/builder1.blueprint");
                final IBuilding sawmill = r.place(ModBlocks.blockHutSawmill, 50, 54, "craftsmanship/carpentry/sawmill1.blueprint");
                final IBuilding mason = r.place(ModBlocks.blockHutStonemason, 66, 54, "craftsmanship/masonry/stonemason1.blueprint");
                r.place(ModBlocks.blockHutLumberjack, 50, 34, "fundamentals/lumberjack1.blueprint");
                r.place(ModBlocks.blockHutMiner, 58, 34, "fundamentals/miner1.blueprint");
                r.place(ModBlocks.blockHutFarmer, 66, 34, "agriculture/horticulture/farmer1.blueprint");

                recipe(sawmill, BuildingModules.SAWMILL_CRAFT, List.of(new ItemStack(Items.OAK_LOG, 1)), new ItemStack(Items.OAK_PLANKS, 4), 1);
                // The fence is made from planks the same sawmill makes from logs: a two-step chain inside one crafter.
                recipe(sawmill, BuildingModules.SAWMILL_CRAFT, List.of(new ItemStack(Items.OAK_PLANKS, 2)), new ItemStack(Items.OAK_FENCE, 1), 2);
                recipe(mason, BuildingModules.STONEMASON_CRAFT, List.of(new ItemStack(Items.COBBLESTONE, 1)), new ItemStack(Items.STONE_BRICKS, 1), 1);

                r.hire(builder, () -> builder.getModule(BuildingModules.BUILDER_WORK));
                r.hire(sawmill, () -> sawmill.getModule(BuildingModules.SAWMILL_WORK));
                r.hire(mason, () -> mason.getModule(BuildingModules.STONEMASON_WORK));
                couriers(r, dman, wh, 2);

                // The gatherers' output, scripted: a haul every few hundred ticks, from the start of the clock.
                r.supplies.add(new MacroRun.Supply("logs", new ItemStack(Items.OAK_LOG, 8), 300, 100, 0));
                r.supplies.add(new MacroRun.Supply("cobble", new ItemStack(Items.COBBLESTONE, 12), 400, 200, 0));

                final int huts = quick ? 3 : 6;
                for (int k = 0; k < huts; k++)
                {
                    // 4..8 planks, 3..5 cobblestone, 0..2 stone bricks, 0..2 fences: every hut needs crafted and gathered goods
                    orders.add(order(r, "hut" + k, 12, 62 + 3 * k, hutBlocks(4 + 2 * (k % 3), 3 + 2 * (k % 2), k % 3, (k + 1) % 3), 0));
                }
            }

            @Override
            public void onTick(final MacroRun r, final long tick)
            {
            }

            @Override
            public boolean done(final MacroRun r, final long tick)
            {
                return tick > 60 && allBuilt(r, orders);
            }

            @Override
            public int budget()
            {
                return 60_000;
            }
        };
    }
}
