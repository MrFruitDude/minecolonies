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
     * Places {@code count} courier huts in a row starting at (x, z): one courier lives in each (the hut holds one).
     */
    static List<IBuilding> courierHuts(final MacroRun r, final int count, final int x, final int z)
    {
        final List<IBuilding> huts = new ArrayList<>();
        for (int i = 0; i < count; i++)
        {
            huts.add(r.place(ModBlocks.blockHutDeliveryman, x + 8 * i, z, "craftsmanship/storage/deliveryman1.blueprint"));
        }
        return huts;
    }

    /**
     * Hires one courier into each courier hut and into the warehouse's courier pool.
     */
    static void couriers(final MacroRun r, final List<IBuilding> huts, final IBuilding warehouse)
    {
        warehouse.setBuildingLevel(Math.max(huts.size(), warehouse.getBuildingLevel()));
        for (final IBuilding hut : huts)
        {
            final ICitizenData courier = r.hire(hut, () -> hut.getModule(BuildingModules.COURIER_WORK));
            r.helper.assertTrue(warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "courier not assigned to the warehouse");
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
                dman = courierHuts(r, 2, 66, 44);
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

                this.builder = builder;
                this.sawmill = sawmill;
                this.mason = mason;
                                this.wh = wh;
            }

            private IBuilding builder, sawmill, mason, wh;
            private List<IBuilding> dman;

            @Override
            public void staff(final MacroRun r)
            {
                r.hire(builder, () -> builder.getModule(BuildingModules.BUILDER_WORK));
                r.hire(sawmill, () -> sawmill.getModule(BuildingModules.SAWMILL_WORK));
                r.hire(mason, () -> mason.getModule(BuildingModules.STONEMASON_WORK));
                couriers(r, dman, wh);
            }

            @Override
            public void begin(final MacroRun r)
            {
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

    // ------------------------------------------------------------------ scripted consumers

    /**
     * A building that keeps a little stock of one item: it asks for a batch whenever its racks and its open requests together
     * hold less than {@code low}, and uses one item up every {@code usePeriod} ticks (a cook feeding, a worker wearing a tool out).
     */
    static final class Consumer
    {
        final IBuilding building;
        final Item      item;
        final int       batch;
        final int       low;
        final int       usePeriod;
        final int       phase;
        final boolean   asTool;
        final List<com.minecolonies.api.colony.requestsystem.token.IToken<?>> open = new ArrayList<>();

        Consumer(final IBuilding building, final Item item, final int batch, final int low, final int usePeriod, final int phase, final boolean asTool)
        {
            this.building = building;
            this.item = item;
            this.batch = batch;
            this.low = low;
            this.usePeriod = usePeriod;
            this.phase = phase;
            this.asTool = asTool;
        }

        void tick(final MacroRun r, final long tick, final boolean demand)
        {
            if (tick % usePeriod == phase % usePeriod)
            {
                r.sink(building, stack -> stack.is(item), 1);
            }
            if (!demand || tick % 20 != phase % 20)
            {
                return;
            }
            open.removeIf(token -> r.rm.getRequestForToken(token) == null);
            int have = 0;
            for (final BlockPos pos : building.getContainers())
            {
                if (r.level.getBlockEntity(pos) instanceof com.minecolonies.core.tileentities.TileEntityRack rack)
                {
                    for (int slot = 0; slot < rack.getInventory().getSlots(); slot++)
                    {
                        final ItemStack in = rack.getInventory().getStackInSlot(slot);
                        if (in.is(item))
                        {
                            have += in.getCount();
                        }
                    }
                }
            }
            if (have + open.size() * batch < low)
            {
                open.add(building.createRequest(new com.minecolonies.api.colony.requestsystem.requestable.Stack(new ItemStack(item, batch), batch, batch), false));
            }
        }
    }

    /** Positions on two rows north of the colony core and two south of the build sites, for the many small buildings. */
    static BlockPos ring(final int i)
    {
        final int perRow = 9;
        final int row = i / perRow;
        final int col = i % perRow;
        final int z = switch (row)
        {
            case 0 -> 8;
            case 1 -> 18;
            case 2 -> 28;
            default -> 36 + 10 * (row - 3);
        };
        return new BlockPos(10 + 10 * col, 1, z);
    }

    private static final String[][] CONSUMER_HUTS = {
      {"cook", "fundamentals/cook1.blueprint"},
      {"baker", "craftsmanship/luxury/baker1.blueprint"},
      {"blacksmith", "craftsmanship/metallurgy/blacksmith1.blueprint"},
      {"tavern", "fundamentals/tavern1.blueprint"},
      {"glassblower", "craftsmanship/luxury/glassblower1.blueprint"},
      {"fletcher", "craftsmanship/carpentry/fletcher1.blueprint"},
      {"smeltery", "craftsmanship/metallurgy/smeltery1.blueprint"},
    };

    static net.minecraft.world.level.block.Block hutBlock(final String kind)
    {
        return switch (kind)
        {
            case "cook" -> ModBlocks.blockHutCook;
            case "baker" -> ModBlocks.blockHutBaker;
            case "blacksmith" -> ModBlocks.blockHutBlacksmith;
            case "tavern" -> ModBlocks.blockHutTavern;
            case "glassblower" -> ModBlocks.blockHutGlassblower;
            case "fletcher" -> ModBlocks.blockHutFletcher;
            default -> ModBlocks.blockHutSmeltery;
        };
    }

    // ------------------------------------------------------------------ b) busy city

    /**
     * A city of 30 buildings: town hall, two warehouses, six couriers (six courier huts), three builders with nine
     * overlapping build orders, a sawmill and a stonemason, three gatherer huts, and seventeen small buildings that keep
     * asking for food and tools all the time. The measured work is the nine builds; the consumers keep demanding until they are done.
     */
    public static MacroRun.Scenario busy(final boolean quick)
    {
        return new MacroRun.Scenario()
        {
            private final List<Integer> orders = new ArrayList<>();
            private final List<Consumer> consumers = new ArrayList<>();
            private final List<IBuilding> builders = new ArrayList<>();
            private IBuilding sawmill, mason, wh1, wh2;
            private List<IBuilding> huts;
            private boolean demand = true;

            @Override
            public void setup(final MacroRun r)
            {
                core(r, "rsb-busy", 2);
                wh1 = r.warehouses.get(0);
                wh2 = r.warehouses.get(1);
                huts = courierHuts(r, 6, 30, 38);
                for (int i = 0; i < 3; i++)
                {
                    builders.add(r.place(ModBlocks.blockHutBuilder, 20, 34 + 10 * i, "fundamentals/builder1.blueprint"));
                }
                sawmill = r.place(ModBlocks.blockHutSawmill, 42, 54, "craftsmanship/carpentry/sawmill1.blueprint");
                mason = r.place(ModBlocks.blockHutStonemason, 58, 54, "craftsmanship/masonry/stonemason1.blueprint");
                r.place(ModBlocks.blockHutLumberjack, 30, 28, "fundamentals/lumberjack1.blueprint");
                r.place(ModBlocks.blockHutMiner, 40, 28, "fundamentals/miner1.blueprint");
                r.place(ModBlocks.blockHutFarmer, 50, 28, "agriculture/horticulture/farmer1.blueprint");
                recipe(sawmill, BuildingModules.SAWMILL_CRAFT, List.of(new ItemStack(Items.OAK_LOG, 1)), new ItemStack(Items.OAK_PLANKS, 4), 1);
                recipe(sawmill, BuildingModules.SAWMILL_CRAFT, List.of(new ItemStack(Items.OAK_PLANKS, 2)), new ItemStack(Items.OAK_FENCE, 1), 2);
                recipe(mason, BuildingModules.STONEMASON_CRAFT, List.of(new ItemStack(Items.COBBLESTONE, 1)), new ItemStack(Items.STONE_BRICKS, 1), 1);
                final int small = quick ? 8 : 17;
                for (int i = 0; i < small; i++)
                {
                    final String[] kind = CONSUMER_HUTS[i % CONSUMER_HUTS.length];
                    final BlockPos at = ring(i);
                    final IBuilding b = r.place(hutBlock(kind[0]), at.getX(), at.getZ(), kind[1]);
                    r.racks(b, 2);
                    final boolean tool = i % 2 == 1;
                    consumers.add(new Consumer(b, tool ? Items.STONE_PICKAXE : Items.COOKED_BEEF, tool ? 1 : 8, tool ? 1 : 6, tool ? 600 : 70 + 10 * (i % 5), 7 * i, tool));
                }
            }

            @Override
            public void staff(final MacroRun r)
            {
                for (final IBuilding b : builders)
                {
                    r.hire(b, () -> b.getModule(BuildingModules.BUILDER_WORK));
                }
                r.hire(sawmill, () -> sawmill.getModule(BuildingModules.SAWMILL_WORK));
                r.hire(mason, () -> mason.getModule(BuildingModules.STONEMASON_WORK));
                couriers(r, huts.subList(0, 3), wh1);
                couriers(r, huts.subList(3, 6), wh2);
            }

            @Override
            public void begin(final MacroRun r)
            {
                r.supplies.add(new MacroRun.Supply("logs", new ItemStack(Items.OAK_LOG, 12), 250, 100, 0));
                r.supplies.add(new MacroRun.Supply("cobble", new ItemStack(Items.COBBLESTONE, 16), 250, 180, 0));
                r.supplies.add(new MacroRun.Supply("beef", new ItemStack(Items.COOKED_BEEF, 12), 100, 50, 0));
                r.supplies.add(new MacroRun.Supply("pickaxe", new ItemStack(Items.STONE_PICKAXE, 2), 100, 70, 0));
                final int n = quick ? 4 : 9;
                for (int k = 0; k < n; k++)
                {
                    orders.add(order(r, "city" + k, 10, 62 + 3 * k, hutBlocks(6 + (k % 4) * 2, 4 + (k % 3) * 2, k % 4, k % 3), 0));
                }
            }

            @Override
            public void onTick(final MacroRun r, final long tick)
            {
                for (final Consumer c : consumers)
                {
                    c.tick(r, tick, demand);
                }
            }

            @Override
            public void stopDemand(final MacroRun r)
            {
                demand = false;
            }

            @Override
            public boolean done(final MacroRun r, final long tick)
            {
                return tick > 60 && allBuilt(r, orders);
            }

            @Override
            public int budget()
            {
                return 80_000;
            }
        };
    }

    // ------------------------------------------------------------------ c) storm

    /**
     * 500 requests at once and too few couriers: a warehouse minimum stock of many items, a request wave from fourteen
     * buildings, two builders with big orders; then the stock arrives in bursts.
     */
    public static MacroRun.Scenario storm(final boolean quick)
    {
        final int minStockKinds = quick ? 30 : 120;
        final int requesters = quick ? 6 : 14;
        final int perRequester = quick ? 14 : 20;
        return new MacroRun.Scenario()
        {
            private final List<Integer> orders = new ArrayList<>();
            private final List<IBuilding> consumers = new ArrayList<>();
            private final List<IBuilding> builders = new ArrayList<>();
            private final List<Item> minKinds = new ArrayList<>();
            private final List<Item> poolKinds = new ArrayList<>();
            private final java.util.Map<Item, Integer> wanted = new java.util.HashMap<>();
            private IBuilding wh, sawmill, mason;
            private List<IBuilding> huts;
            private final int[] burstAt = {600, 1500, 2600, 3800, 5200};
            private int bursts;

            @Override
            public void setup(final MacroRun r)
            {
                core(r, "rsb-storm", 1);
                wh = r.warehouses.get(0);
                r.racks(wh, 24);
                huts = courierHuts(r, quick ? 2 : 3, 66, 44);
                for (int i = 0; i < 2; i++)
                {
                    builders.add(r.place(ModBlocks.blockHutBuilder, 42, 40 + 10 * i, "fundamentals/builder1.blueprint"));
                }
                sawmill = r.place(ModBlocks.blockHutSawmill, 50, 54, "craftsmanship/carpentry/sawmill1.blueprint");
                mason = r.place(ModBlocks.blockHutStonemason, 58, 54, "craftsmanship/masonry/stonemason1.blueprint");
                r.place(ModBlocks.blockHutLumberjack, 50, 34, "fundamentals/lumberjack1.blueprint");
                r.place(ModBlocks.blockHutMiner, 58, 34, "fundamentals/miner1.blueprint");
                r.place(ModBlocks.blockHutFarmer, 66, 34, "agriculture/horticulture/farmer1.blueprint");
                recipe(sawmill, BuildingModules.SAWMILL_CRAFT, List.of(new ItemStack(Items.OAK_LOG, 1)), new ItemStack(Items.OAK_PLANKS, 4), 1);
                recipe(sawmill, BuildingModules.SAWMILL_CRAFT, List.of(new ItemStack(Items.OAK_PLANKS, 2)), new ItemStack(Items.OAK_FENCE, 1), 2);
                recipe(mason, BuildingModules.STONEMASON_CRAFT, List.of(new ItemStack(Items.COBBLESTONE, 1)), new ItemStack(Items.STONE_BRICKS, 1), 1);
                for (int i = 0; i < requesters; i++)
                {
                    final String[] kind = CONSUMER_HUTS[i % CONSUMER_HUTS.length];
                    // close to the warehouse: the shortage should be of couriers, not of walking time
                    final int x = 58 + 9 * (i % 5);
                    final int z = 62 + 8 * (i / 5);
                    final IBuilding b = r.place(hutBlock(kind[0]), x, z, kind[1]);
                    r.racks(b, 4);
                    consumers.add(b);
                }
            }

            @Override
            public void staff(final MacroRun r)
            {
                for (final IBuilding b : builders)
                {
                    r.hire(b, () -> b.getModule(BuildingModules.BUILDER_WORK));
                }
                r.hire(sawmill, () -> sawmill.getModule(BuildingModules.SAWMILL_WORK));
                r.hire(mason, () -> mason.getModule(BuildingModules.STONEMASON_WORK));
                couriers(r, huts, wh);
            }

            /** The goods the storm asks for: plain items by registry order, chosen once the crafters know their recipes. */
            private void pickKinds(final MacroRun r)
            {
                final List<Item> candidates = new ArrayList<>();
                for (final Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM)
                {
                    final ItemStack sample = new ItemStack(item);
                    // only goods nothing in the colony can craft: a crafter that "can" make an item whose ingredients nobody makes
                    // would hold the request forever, which is a different test
                    if (!sample.isEmpty() && sample.getMaxStackSize() == 64 && !sample.isDamageableItem() && !ItemUnits.tracked(item) && item != Items.AIR
                          && com.minecolonies.core.colony.requestsystem.wait.WaitDiagnosis.recipeSupport((com.minecolonies.core.colony.Colony) r.colony,
                      new com.minecolonies.api.colony.requestsystem.requestable.Stack(sample)) == 0)
                    {
                        candidates.add(item);
                    }
                }
                candidates.sort(java.util.Comparator.comparing(item -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString()));
                final int stride = Math.max(1, candidates.size() / (minStockKinds + 60));
                for (int i = 0; i < minStockKinds + 60; i++)
                {
                    (i < minStockKinds ? minKinds : poolKinds).add(candidates.get(Math.min(candidates.size() - 1, i * stride)));
                }
            }

            @Override
            public void begin(final MacroRun r)
            {
                pickKinds(r);
                r.supplies.add(new MacroRun.Supply("logs", new ItemStack(Items.OAK_LOG, 12), 250, 100, 0));
                r.supplies.add(new MacroRun.Supply("cobble", new ItemStack(Items.COBBLESTONE, 16), 250, 180, 0));
                final java.util.Random rng = new java.util.Random(r.seed * 7919L);
                // the storm: everything is asked for in the same tick
                final com.minecolonies.core.colony.buildings.modules.MinimumStockModule minimum = wh.getModule(BuildingModules.MIN_STOCK);
                for (final Item item : minKinds)
                {
                    minimum.addMinimumStock(new ItemStack(item), 1);
                }
                minimum.addMinimumStock(new ItemStack(Items.OAK_PLANKS), 1);
                minimum.addMinimumStock(new ItemStack(Items.STONE_BRICKS), 1);
                int created = 0;
                for (final IBuilding b : consumers)
                {
                    final List<Item> mine = new ArrayList<>(poolKinds);
                    java.util.Collections.shuffle(mine, rng);
                    for (int i = 0; i < perRequester; i++)
                    {
                        final Item item = mine.get(i % mine.size());
                        final int count = 1 + rng.nextInt(8);
                        b.createRequest(new com.minecolonies.api.colony.requestsystem.requestable.Stack(new ItemStack(item, count), count, count), false);
                        wanted.merge(item, count, Integer::sum);
                        created++;
                    }
                }
                final int n = quick ? 2 : 4;
                for (int k = 0; k < n; k++)
                {
                    orders.add(order(r, "storm" + k, 10, 62 + 3 * k, hutBlocks(10 + 2 * k, 6, 2 + k % 2, 0), 0));
                }
            }

            @Override
            public void onTick(final MacroRun r, final long tick)
            {
                // the stock arrives in bursts: each burst brings a fifth of the minimum-stock items and of the asked-for goods
                if (bursts < burstAt.length && tick >= burstAt[bursts])
                {
                    for (int i = bursts; i < minKinds.size(); i += burstAt.length)
                    {
                        r.stockWarehouse(new ItemStack(minKinds.get(i), 64));
                    }
                    int j = 0;
                    for (final Item item : poolKinds)
                    {
                        if (j++ % burstAt.length == bursts)
                        {
                            int left = wanted.getOrDefault(item, 0);
                            while (left > 0)
                            {
                                final int give = Math.min(64, left);
                                r.stockWarehouse(new ItemStack(item, give));
                                left -= give;
                            }
                        }
                    }
                    bursts++;
                }
            }

            @Override
            public boolean done(final MacroRun r, final long tick)
            {
                return bursts >= burstAt.length && tick > 60 && allBuilt(r, orders) && r.pendingRequests() == 0;
            }

            @Override
            public int budget()
            {
                return 160_000;
            }
        };
    }

    // ------------------------------------------------------------------ d) unload churn

    /**
     * The fresh-colony chain with the colony's chunks unloading and reloading every 900 ticks while deliveries are on
     * their way: the player leaves, the chunks are released, a while later everything comes back.
     */
    public static MacroRun.Scenario unload(final boolean quick)
    {
        final MacroRun.Scenario base = fresh(true);
        return new MacroRun.Scenario()
        {
            private boolean away;
            private final List<long[]> chunks = new ArrayList<>();

            @Override
            public void setup(final MacroRun r)
            {
                base.setup(r);
                final BlockPos a = r.helper.absolutePos(new BlockPos(X0, 0, Z0));
                final BlockPos b = r.helper.absolutePos(new BlockPos(X1, 0, Z1));
                for (int cx = a.getX() >> 4; cx <= b.getX() >> 4; cx++)
                {
                    for (int cz = a.getZ() >> 4; cz <= b.getZ() >> 4; cz++)
                    {
                        chunks.add(new long[] {cx, cz});
                    }
                }
            }

            @Override
            public void staff(final MacroRun r)
            {
                base.staff(r);
            }

            @Override
            public void begin(final MacroRun r)
            {
                base.begin(r);
            }

            @Override
            public void onTick(final MacroRun r, final long tick)
            {
                final long phase = tick % 900;
                if (phase == 600 && !away)
                {
                    away = true;
                    r.away = true;
                    r.colony.getPackageManager().removeCloseSubscriber(r.player);
                    for (final long[] c : chunks)
                    {
                        r.level.setChunkForced((int) c[0], (int) c[1], false);
                    }
                    final BlockPos here = r.colony.getCenter();
                    r.player.teleportTo(here.getX() + 3000.5D, here.getY() + 1, here.getZ() + 0.5D);
                }
                else if (phase == 780 && away)
                {
                    for (final long[] c : chunks)
                    {
                        r.level.setChunkForced((int) c[0], (int) c[1], true);
                    }
                    final BlockPos here = r.colony.getCenter();
                    r.player.teleportTo(here.getX() + 0.5D, here.getY() + 1, here.getZ() + 0.5D);
                    r.colony.getPackageManager().addCloseSubscriber(r.player);
                    away = false;
                    r.away = false;
                    r.cycles++;
                }
                base.onTick(r, tick);
            }

            @Override
            public boolean done(final MacroRun r, final long tick)
            {
                return !away && base.done(r, tick);
            }

            @Override
            public int budget()
            {
                return 90_000;
            }
        };
    }
}
