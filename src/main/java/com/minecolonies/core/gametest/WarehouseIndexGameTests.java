package com.minecolonies.core.gametest;

import com.ldtteam.structurize.api.util.Tuple;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.api.colony.requestsystem.resolver.IRequestResolver;
import com.minecolonies.api.colony.requestsystem.requestable.RequestTag;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.inventory.InventoryCitizen;
import com.minecolonies.api.tileentities.AbstractTileEntityRack;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.api.util.constant.TypeConstants;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
import com.minecolonies.core.colony.requestsystem.resolvers.WarehouseConcreteRequestResolver;
import com.minecolonies.core.colony.requestsystem.resolvers.WarehouseRequestResolver;
import com.minecolonies.core.colony.requestsystem.resolvers.core.AbstractWarehouseRequestResolver;
import com.minecolonies.core.tileentities.TileEntityRack;
import com.minecolonies.core.tileentities.TileEntityWareHouse;
import com.minecolonies.core.tileentities.WarehouseRackIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.clock.WorldClocks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Predicate;

/**
 * PF4: warehouse request resolution and courier dumps read racks from a cached index instead of a block-entity lookup
 * per container per call. The scaling test counts block-entity lookups through {@link WarehouseRackIndex}'s
 * statistics (the pre-fix stub counts the old loops into the same fields); the correctness tests compare every answer
 * with the old algorithm, run inline against live block entities.
 */
public final class WarehouseIndexGameTests
{
    private static final Item[] FILLER = {
      Items.STONE, Items.COBBLESTONE, Items.GRANITE, Items.DIORITE, Items.ANDESITE, Items.STONE_BRICKS, Items.BRICKS,
      Items.SANDSTONE, Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS,
      Items.DARK_OAK_PLANKS, Items.MOSSY_COBBLESTONE, Items.POLISHED_GRANITE, Items.POLISHED_DIORITE, Items.POLISHED_ANDESITE,
      Items.DEEPSLATE, Items.COBBLED_DEEPSLATE, Items.TUFF, Items.CALCITE, Items.PRISMARINE, Items.END_STONE, Items.NETHER_BRICKS,
      Items.RED_SANDSTONE, Items.QUARTZ_BLOCK, Items.MUD_BRICKS, Items.BLACKSTONE, Items.TERRACOTTA};

    private static final Item[] MISSING = {
      Items.DIAMOND, Items.EMERALD, Items.GOLD_INGOT, Items.IRON_INGOT, Items.REDSTONE, Items.LAPIS_LAZULI, Items.COAL,
      Items.APPLE, Items.BREAD, Items.STRING};

    private WarehouseIndexGameTests()
    {
    }

    // ------------------------------------------------------------------ fixture

    private record Fixture(ServerLevel level, IColony colony, IBuilding requester, List<BuildingWareHouse> warehouses, List<List<BlockPos>> racks)
    {
    }

    /**
     * Colony with a builder (the requester) and {@code warehouses} warehouses, each with {@code racksEach} racks spaced
     * one block apart so no two form a double rack.
     */
    private static Fixture fixture(final GameTestHelper helper, final String name, final int warehouses, final int racksEach)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, name);
        // The whole pad stays loaded, so no rack can drop out mid-test.
        final BlockPos min = helper.absolutePos(new BlockPos(-8, 0, -8));
        final BlockPos max = helper.absolutePos(new BlockPos(40, 0, 32));
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++)
        {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++)
            {
                level.setChunkForced(cx, cz, true);
            }
        }
        final IBuilding requester = MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockHutBuilder,
          new BlockPos(8, 1, 2), "fundamentals/builder1.blueprint");
        final List<BuildingWareHouse> houses = new ArrayList<>();
        final List<List<BlockPos>> racks = new ArrayList<>();
        for (int w = 0; w < warehouses; w++)
        {
            final IBuilding building = MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockHutWareHouse,
              new BlockPos(16 + 10 * w, 1, 2), "craftsmanship/storage/warehouse1.blueprint");
            helper.assertTrue(building instanceof BuildingWareHouse, "warehouse " + w + " is " + building);
            final BuildingWareHouse house = (BuildingWareHouse) building;
            final List<BlockPos> list = new ArrayList<>();
            for (int r = 0; r < racksEach; r++)
            {
                // 12 x 5 grid on every other block, warehouse w on layer y = 1 + 2w.
                final BlockPos rel = new BlockPos(-6 + 2 * (r % 12), 1 + 2 * w, 10 + 2 * (r / 12));
                helper.setBlock(rel, ModBlocks.blockRack.defaultBlockState());
                final BlockPos abs = helper.absolutePos(rel);
                house.registerBlockPosition(ModBlocks.blockRack, abs, level);
                list.add(abs);
            }
            houses.add(house);
            racks.add(list);
        }
        return new Fixture(level, colony, requester, houses, racks);
    }

    private static BlockPos rel(final GameTestHelper helper, final BlockPos abs)
    {
        return abs.subtract(helper.absolutePos(BlockPos.ZERO));
    }

    private static TileEntityRack rack(final Fixture f, final BlockPos pos)
    {
        final BlockEntity entity = f.level().getBlockEntity(pos);
        if (!(entity instanceof final TileEntityRack rack))
        {
            throw new IllegalStateException("no rack at " + pos + ": " + entity);
        }
        return rack;
    }

    private static void put(final TileEntityRack rack, final int slot, final ItemStack stack)
    {
        rack.getInventory().setStackInSlot(slot, stack);
    }

    private static int totalPositions(final Fixture f)
    {
        int n = 0;
        for (final BuildingWareHouse house : f.warehouses())
        {
            n += house.getContainers().size();
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private static IRequest<? extends IDeliverable> newRequest(final Fixture f, final IDeliverable deliverable)
    {
        final IRequestManager manager = f.colony().getRequestManager();
        final IToken<?> token = manager.createRequest(f.requester().getRequester(), deliverable);
        return (IRequest<? extends IDeliverable>) manager.getRequestForToken(token);
    }

    private static <T extends AbstractWarehouseRequestResolver> T resolver(final Fixture f, final BuildingWareHouse house, final boolean concrete)
    {
        final IToken<?> token = f.colony().getRequestManager().getFactoryController().getNewInstance(TypeConstants.ITOKEN);
        @SuppressWarnings("unchecked")
        final T resolver = (T) (concrete
                                  ? new WarehouseConcreteRequestResolver(house.getRequester().getLocation(), token)
                                  : new WarehouseRequestResolver(house.getRequester().getLocation(), token));
        return resolver;
    }

    // ------------------------------------------------------------------ the old algorithms, inline

    private static boolean refHasMatching(final BuildingWareHouse house, final ServerLevel level, final ItemStack stack, final int count,
      final boolean ignoreNBT, final boolean ignoreDamage, final int leftOver)
    {
        int total = 0 - leftOver;
        for (final BlockPos pos : house.getContainers())
        {
            if (WorldUtil.isBlockLoaded(level, pos))
            {
                final BlockEntity entity = level.getBlockEntity(pos);
                if (entity instanceof TileEntityRack && !((AbstractTileEntityRack) entity).isEmpty())
                {
                    total += ((AbstractTileEntityRack) entity).getCount(stack, ignoreDamage, ignoreNBT);
                    if (total >= count)
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean refHasMatching(final BuildingWareHouse house, final ServerLevel level, final Predicate<ItemStack> predicate, final int count)
    {
        int total = 0;
        for (final BlockPos pos : house.getContainers())
        {
            if (WorldUtil.isBlockLoaded(level, pos))
            {
                final BlockEntity entity = level.getBlockEntity(pos);
                if (entity instanceof final TileEntityRack rack && !rack.isEmpty())
                {
                    total += rack.getItemCount(predicate);
                    if (total >= count)
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<Tuple<ItemStack, BlockPos>> refMatching(final BuildingWareHouse house, final ServerLevel level, final Predicate<ItemStack> predicate)
    {
        final List<Tuple<ItemStack, BlockPos>> found = new ArrayList<>();
        for (final BlockPos pos : house.getContainers())
        {
            if (WorldUtil.isBlockLoaded(level, pos))
            {
                final BlockEntity entity = level.getBlockEntity(pos);
                if (entity instanceof final TileEntityRack rack && !rack.isEmpty() && rack.getItemCount(predicate) > 0)
                {
                    for (final ItemStack stack : InventoryUtils.filterItemHandler(rack.getInventory(), predicate))
                    {
                        found.add(new Tuple<>(stack, pos));
                    }
                }
            }
        }
        return found;
    }

    private static AbstractTileEntityRack refRackForStack(final BuildingWareHouse house, final ServerLevel level, final ItemStack stack)
    {
        for (final BlockPos pos : house.getContainers())
        {
            if (WorldUtil.isBlockLoaded(level, pos) && level.getBlockEntity(pos) instanceof final AbstractTileEntityRack rack
                  && rack.getFreeSlots() > 0 && rack.hasItemStack(stack, 1, true))
            {
                return rack;
            }
        }
        for (final BlockPos pos : house.getContainers())
        {
            if (WorldUtil.isBlockLoaded(level, pos) && level.getBlockEntity(pos) instanceof final AbstractTileEntityRack rack
                  && rack.getFreeSlots() > 0 && rack.hasSimilarStack(stack))
            {
                return rack;
            }
        }
        int freeSlots = 0;
        AbstractTileEntityRack emptiest = null;
        for (final BlockPos pos : house.getContainers())
        {
            if (level.getBlockEntity(pos) instanceof final TileEntityRack rack)
            {
                if (rack.isEmpty())
                {
                    return rack;
                }
                if (rack.getFreeSlots() > freeSlots)
                {
                    freeSlots = rack.getFreeSlots();
                    emptiest = rack;
                }
            }
        }
        return emptiest;
    }

    private static String describe(final List<Tuple<ItemStack, BlockPos>> list)
    {
        final StringBuilder sb = new StringBuilder("[");
        for (final Tuple<ItemStack, BlockPos> t : list)
        {
            sb.append(t.getA().getCount()).append('x').append(t.getA().getItem()).append('@').append(t.getB().toShortString()).append(' ');
        }
        return sb.append(']').toString();
    }

    private static boolean sameList(final List<Tuple<ItemStack, BlockPos>> a, final List<Tuple<ItemStack, BlockPos>> b)
    {
        if (a.size() != b.size())
        {
            return false;
        }
        for (int i = 0; i < a.size(); i++)
        {
            if (!ItemStack.matches(a.get(i).getA(), b.get(i).getA()) || !a.get(i).getB().equals(b.get(i).getB()))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Every warehouse query against the old algorithm, for a set of probe items and counts.
     */
    private static void compareAll(final Fixture f, final String step, final List<String> failures)
    {
        final Item[] probes = {Items.DIRT, Items.STONE, Items.COBBLESTONE, Items.DIAMOND, Items.OAK_LOG};
        final int[] counts = {0, 1, 3, 5, 64};
        for (final BuildingWareHouse house : f.warehouses())
        {
            final TileEntityWareHouse te = (TileEntityWareHouse) house.getTileEntity();
            for (final Item item : probes)
            {
                final ItemStack stack = new ItemStack(item);
                final Predicate<ItemStack> predicate = s -> s.is(item);
                final List<Tuple<ItemStack, BlockPos>> got = te.getMatchingItemStacksInWarehouse(predicate);
                final List<Tuple<ItemStack, BlockPos>> want = refMatching(house, f.level(), predicate);
                if (!sameList(got, want))
                {
                    failures.add(step + ": getMatching(" + item + ") " + describe(got) + " != old " + describe(want));
                }
                for (final int count : counts)
                {
                    for (final boolean ignore : new boolean[] {false, true})
                    {
                        final ItemStorage storage = new ItemStorage(stack, ignore, ignore);
                        final int a = house.hasEnoughElseCount(storage, count);
                        final int b = InventoryUtils.hasBuildingEnoughElseCount(house, storage, count);
                        if (a != b)
                        {
                            failures.add(step + ": count(" + item + ", ignore=" + ignore + ", " + count + ") " + a + " != old " + b);
                        }
                        for (final int leftOver : new int[] {0, 2})
                        {
                            final boolean c = te.hasMatchingItemStackInWarehouse(stack, count, ignore, leftOver);
                            final boolean d = refHasMatching(house, f.level(), stack, count, ignore, true, leftOver);
                            if (c != d)
                            {
                                failures.add(step + ": hasMatching(" + item + ", " + count + ", ignoreNbt=" + ignore + ", leftOver=" + leftOver + ") " + c + " != old " + d);
                            }
                        }
                    }
                    final int p = house.hasEnoughElseCount(predicate, count);
                    final int q = InventoryUtils.hasBuildingEnoughElseCount(house, predicate, count);
                    if (p != q)
                    {
                        failures.add(step + ": countPredicate(" + item + ", " + count + ") " + p + " != old " + q);
                    }
                    final boolean r = te.hasMatchingItemStackInWarehouse(predicate, count);
                    final boolean s = refHasMatching(house, f.level(), predicate, count);
                    if (r != s)
                    {
                        failures.add(step + ": hasMatchingPredicate(" + item + ", " + count + ") " + r + " != old " + s);
                    }
                }
            }
        }
    }

    private static void expect(final List<String> failures, final String step, final boolean got, final boolean want)
    {
        Log.getLogger().info("PF4 invalidation {}: canResolve={} (want {})", step, got, want);
        if (got != want)
        {
            failures.add(step + ": canResolve=" + got + ", want " + want);
        }
    }

    // ------------------------------------------------------------------ tests

    /**
     * (a) 200 requests x 2 warehouses x 60 racks, plus a 27-slot courier dump: block-entity lookups stay bounded by the
     * container count instead of growing with requests x warehouses x racks.
     */
    public static void warehouseIndexRequestScaling(final GameTestHelper helper)
    {
        final Fixture f = fixture(helper, "PF4 scaling colony", 2, 60);
        for (int w = 0; w < 2; w++)
        {
            final List<BlockPos> list = f.racks().get(w);
            for (int r = 0; r < list.size(); r++)
            {
                final TileEntityRack rack = rack(f, list.get(r));
                for (int k = 0; k < 3; k++)
                {
                    put(rack, k, new ItemStack(FILLER[(r * 3 + k + w * 7) % FILLER.length], 16));
                }
            }
        }

        helper.runAfterDelay(20, () -> {
            final int positions = totalPositions(f);
            final long bound = 4L * positions;
            final List<String> failures = new ArrayList<>();
            final StringBuilder digest = new StringBuilder();

            // Phase 1: 180 concrete requests for items no warehouse holds, then 20 for items they do.
            final List<IToken<?>> tokens = new ArrayList<>();
            WarehouseRackIndex.resetStats();
            long start = System.nanoTime();
            for (int i = 0; i < 180; i++)
            {
                tokens.add(f.requester().createRequest(new Stack(new ItemStack(MISSING[i % MISSING.length]), 1, 1), false));
            }
            final double missMs = (System.nanoTime() - start) / 1e6;
            final long missLookups = WarehouseRackIndex.beLookups;
            final long missProbes = WarehouseRackIndex.rackProbes;

            WarehouseRackIndex.resetStats();
            start = System.nanoTime();
            for (int i = 0; i < 20; i++)
            {
                tokens.add(f.requester().createRequest(new Stack(new ItemStack(FILLER[i % FILLER.length]), 1, 1), false));
            }
            final double hitMs = (System.nanoTime() - start) / 1e6;
            final long hitLookups = WarehouseRackIndex.beLookups;
            final long hitProbes = WarehouseRackIndex.rackProbes;

            // Phase 2: 50 tag requests (predicate path) for a tag no warehouse holds.
            WarehouseRackIndex.resetStats();
            start = System.nanoTime();
            for (int i = 0; i < 50; i++)
            {
                tokens.add(f.requester().createRequest(new RequestTag(ItemTags.WOOL, 1), false));
            }
            final double tagMs = (System.nanoTime() - start) / 1e6;
            final long tagLookups = WarehouseRackIndex.beLookups;
            final long tagProbes = WarehouseRackIndex.rackProbes;

            for (int i = 0; i < tokens.size(); i++)
            {
                final IRequest<?> request = f.colony().getRequestManager().getRequestForToken(tokens.get(i));
                String resolverName;
                try
                {
                    resolverName = f.colony().getRequestManager().getResolverForRequest(tokens.get(i)).getClass().getSimpleName();
                }
                catch (final RuntimeException e)
                {
                    resolverName = "none";
                }
                digest.append(i).append(':').append(request == null ? "gone" : request.getState()).append(':').append(resolverName)
                  .append(':').append(request == null ? 0 : request.getChildren().size()).append(';');
            }

            // Phase 3: a courier dump of 27 mixed slots into warehouse 0.
            final InventoryCitizen inventory = new InventoryCitizen("pf4", false);
            for (int i = 0; i < inventory.getSlots(); i++)
            {
                inventory.setStackInSlot(i, new ItemStack(i % 2 == 0 ? FILLER[i % FILLER.length] : MISSING[i % MISSING.length], 8));
            }
            final TileEntityWareHouse te = (TileEntityWareHouse) f.warehouses().get(0).getTileEntity();
            WarehouseRackIndex.resetStats();
            start = System.nanoTime();
            te.dumpInventoryIntoWareHouse(inventory);
            final double dumpMs = (System.nanoTime() - start) / 1e6;
            final long dumpLookups = WarehouseRackIndex.beLookups;
            final long dumpProbes = WarehouseRackIndex.rackProbes;
            int left = 0;
            for (int i = 0; i < inventory.getSlots(); i++)
            {
                left += inventory.getStackInSlot(i).getCount();
            }

            String sha;
            try
            {
                sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(digest.toString().getBytes(StandardCharsets.UTF_8)));
            }
            catch (final Exception e)
            {
                sha = e.toString();
            }

            Log.getLogger().info("PF4 scaling positions={} bound={}", positions, bound);
            Log.getLogger().info("PF4 scaling phase=miss180 beLookups={} rackProbes={} wallMs={}", missLookups, missProbes, String.format("%.2f", missMs));
            Log.getLogger().info("PF4 scaling phase=hit20 beLookups={} rackProbes={} wallMs={}", hitLookups, hitProbes, String.format("%.2f", hitMs));
            Log.getLogger().info("PF4 scaling phase=tag50 beLookups={} rackProbes={} wallMs={}", tagLookups, tagProbes, String.format("%.2f", tagMs));
            Log.getLogger().info("PF4 scaling phase=dump27 beLookups={} rackProbes={} wallMs={} itemsLeft={}", dumpLookups, dumpProbes, String.format("%.2f", dumpMs), left);
            Log.getLogger().info("PF4 scaling digest requests={} sha256={}", tokens.size(), sha);
            Log.getLogger().info("PF4 scaling digest-text {}", digest);

            if (missLookups > bound)
            {
                failures.add("180 missing-item requests made " + missLookups + " block-entity lookups (bound " + bound + ")");
            }
            if (missProbes > 180)
            {
                failures.add("180 missing-item requests made " + missProbes + " rack probes (bound 180)");
            }
            if (hitLookups > bound)
            {
                failures.add("20 stocked-item requests made " + hitLookups + " block-entity lookups (bound " + bound + ")");
            }
            if (tagLookups > bound)
            {
                failures.add("50 tag requests made " + tagLookups + " block-entity lookups (bound " + bound + ")");
            }
            if (dumpLookups > bound)
            {
                failures.add("27-slot dump made " + dumpLookups + " block-entity lookups (bound " + bound + ")");
            }
            if (!failures.isEmpty())
            {
                helper.fail(String.join("; ", failures));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * (b) The resolver's answer follows every rack change made in the same tick, and every warehouse query equals the
     * old algorithm after each change.
     */
    public static void warehouseIndexInvalidation(final GameTestHelper helper)
    {
        final Fixture f = fixture(helper, "PF4 invalidation colony", 2, 6);
        helper.runAfterDelay(20, () -> {
            final List<String> failures = new ArrayList<>();
            final BuildingWareHouse w0 = f.warehouses().get(0);
            final BuildingWareHouse w1 = f.warehouses().get(1);
            final WarehouseConcreteRequestResolver concrete = resolver(f, w0, true);
            final WarehouseRequestResolver generic = resolver(f, w0, false);
            final IRequestManager manager = f.colony().getRequestManager();
            final IRequest<? extends IDeliverable> five = newRequest(f, new Stack(new ItemStack(Items.DIRT), 5, 5));
            final IRequest<? extends IDeliverable> six = newRequest(f, new Stack(new ItemStack(Items.DIRT), 6, 6));
            final IRequest<? extends IDeliverable> logs = newRequest(f, new RequestTag(ItemTags.LOGS, 4, 4));
            final List<BlockPos> racks = f.racks().get(0);
            final BlockPos b = racks.get(1);
            final BlockPos c = racks.get(2);

            // Warm the index so every later step tests invalidation, not a first build.
            expect(failures, "0-empty", concrete.canResolveRequest(manager, five), false);
            compareAll(f, "0-empty", failures);

            put(rack(f, b), 0, new ItemStack(Items.DIRT, 3));
            expect(failures, "1-insert-3-in-B", concrete.canResolveRequest(manager, five), false);
            put(rack(f, c), 0, new ItemStack(Items.DIRT, 2));
            expect(failures, "2-insert-2-in-C", concrete.canResolveRequest(manager, five), true);
            compareAll(f, "2-insert", failures);

            rack(f, c).getInventory().extractItem(0, 2, false);
            expect(failures, "3-extract-C", concrete.canResolveRequest(manager, five), false);
            compareAll(f, "3-extract", failures);

            put(rack(f, c), 4, new ItemStack(Items.DIRT, 2));
            expect(failures, "4-reinsert-C", concrete.canResolveRequest(manager, five), true);
            helper.setBlock(rel(helper, c), Blocks.AIR.defaultBlockState());
            expect(failures, "5-break-C", concrete.canResolveRequest(manager, five), false);
            compareAll(f, "5-break", failures);

            // A new rack at the still-registered position, with no new registration.
            helper.setBlock(rel(helper, c), ModBlocks.blockRack.defaultBlockState());
            put(rack(f, c), 0, new ItemStack(Items.DIRT, 2));
            expect(failures, "6-new-rack-at-C", concrete.canResolveRequest(manager, five), true);
            compareAll(f, "6-new-rack", failures);
            rack(f, c).getInventory().extractItem(0, 2, false);
            expect(failures, "7-empty-C-again", concrete.canResolveRequest(manager, five), false);

            // A rack added to the container list after the index was built.
            final BlockPos dRel = new BlockPos(20, 1, 24);
            helper.setBlock(dRel, ModBlocks.blockRack.defaultBlockState());
            final BlockPos d = helper.absolutePos(dRel);
            put(rack(f, d), 0, new ItemStack(Items.DIRT, 2));
            expect(failures, "8-unregistered-D", concrete.canResolveRequest(manager, five), false);
            w0.registerBlockPosition(ModBlocks.blockRack, d, f.level());
            expect(failures, "9-registered-D", concrete.canResolveRequest(manager, five), true);
            compareAll(f, "9-registered", failures);
            w0.removeContainerPosition(d);
            expect(failures, "10-unregistered-D-again", concrete.canResolveRequest(manager, five), false);

            // Across warehouses: 3 here + 2 in the other one = 5, but not 6.
            put(rack(f, f.racks().get(1).get(0)), 0, new ItemStack(Items.DIRT, 2));
            expect(failures, "11-other-warehouse-5", concrete.canResolveRequest(manager, five), true);
            expect(failures, "12-other-warehouse-6", concrete.canResolveRequest(manager, six), false);
            compareAll(f, "12-two-warehouses", failures);

            // Predicate path (tag request).
            expect(failures, "13-no-logs", generic.canResolveRequest(manager, logs), false);
            put(rack(f, racks.get(3)), 0, new ItemStack(Items.OAK_LOG, 2));
            put(rack(f, racks.get(4)), 0, new ItemStack(Items.BIRCH_LOG, 2));
            expect(failures, "14-logs-inserted", generic.canResolveRequest(manager, logs), true);
            rack(f, racks.get(4)).getInventory().extractItem(0, 1, false);
            expect(failures, "15-log-extracted", generic.canResolveRequest(manager, logs), false);
            compareAll(f, "15-logs", failures);

            // Storage upgrade resizes every rack; content and answers stay.
            w0.upgradeContainers(f.level());
            expect(failures, "16-after-upgrade", concrete.canResolveRequest(manager, five), true);
            put(rack(f, b), 30, new ItemStack(Items.DIRT, 1));
            expect(failures, "17-insert-in-upgraded-slot", concrete.canResolveRequest(manager, six), true);
            compareAll(f, "17-upgrade", failures);

            if (!failures.isEmpty())
            {
                helper.fail(String.join("; ", failures));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * (b) A courier dump picks the same rack for every slot as the old three-pass search, with racks changing between
     * slots: existing stack, similar stack, emptiest rack (ties go to the first), full racks skipped.
     */
    public static void warehouseIndexDumpSameRack(final GameTestHelper helper)
    {
        final Fixture f = fixture(helper, "PF4 dump colony", 1, 10);
        helper.runAfterDelay(20, () -> {
            final BuildingWareHouse house = f.warehouses().get(0);
            final TileEntityWareHouse te = (TileEntityWareHouse) house.getTileEntity();
            final List<BlockPos> racks = f.racks().get(0);
            // No rack is empty, so the emptiest-rack pass decides by free slots; several racks tie.
            for (int r = 0; r < racks.size(); r++)
            {
                final TileEntityRack rack = rack(f, racks.get(r));
                final int used = r == 0 ? 27 : 1 + (r % 3) * 4;
                for (int slot = 0; slot < used; slot++)
                {
                    put(rack, slot, new ItemStack(r == 0 ? Items.COBBLESTONE : FILLER[(r + slot) % 8], 1));
                }
            }
            put(te, 0, new ItemStack(Items.STONE, 1));

            final List<ItemStack> incoming = new ArrayList<>();
            for (int i = 0; i < 40; i++)
            {
                final Item item = switch (i % 5)
                {
                    case 0 -> Items.COBBLESTONE;
                    case 1 -> FILLER[i % 8];
                    case 2 -> Items.MUD_BRICKS;
                    case 3 -> MISSING[i % MISSING.length];
                    default -> Items.SHEARS;
                };
                incoming.add(new ItemStack(item, item == Items.SHEARS ? 1 : 1 + i % 16));
            }

            final List<String> failures = new ArrayList<>();
            int step = 0;
            for (final ItemStack stack : incoming)
            {
                final AbstractTileEntityRack want = refRackForStack(house, f.level(), stack);
                final AbstractTileEntityRack got = te.getRackForStack(stack);
                Log.getLogger().info("PF4 dump step={} stack={} rack={} old={}", step, stack, got == null ? null : got.getBlockPos().toShortString(),
                  want == null ? null : want.getBlockPos().toShortString());
                if (got != want)
                {
                    failures.add("step " + step + " " + stack + ": rack " + (got == null ? null : got.getBlockPos().toShortString())
                                   + " != old " + (want == null ? null : want.getBlockPos().toShortString()));
                }
                if (want != null)
                {
                    InventoryUtils.addItemStackToItemHandler(want.getItemHandlerCap(), stack.copy());
                }
                step++;
            }

            // The real dumpInventoryIntoWareHouse, one stack at a time: it must land in the rack the old search picks.
            final List<String> expected = new ArrayList<>();
            for (int i = 0; i < incoming.size(); i++)
            {
                final ItemStack stack = incoming.get(i).copy();
                final AbstractTileEntityRack want = refRackForStack(house, f.level(), stack);
                if (want == null)
                {
                    break;
                }
                final Predicate<ItemStack> same = s -> ItemStack.isSameItemSameComponents(s, stack);
                final int before = InventoryUtils.getItemCountInItemHandler(want.getItemHandlerCap(), same);
                final InventoryCitizen inventory = new InventoryCitizen("pf4-dump", false);
                inventory.setStackInSlot(i % inventory.getSlots(), stack.copy());
                te.dumpInventoryIntoWareHouse(inventory);
                final int after = InventoryUtils.getItemCountInItemHandler(want.getItemHandlerCap(), same);
                if (after - before != stack.getCount())
                {
                    failures.add("dump " + i + " " + stack + ": old search rack " + want.getBlockPos().toShortString() + " gained " + (after - before));
                }
                expected.add(want.getBlockPos().toShortString());
            }
            Log.getLogger().info("PF4 dump slots placed={}", expected);

            if (!failures.isEmpty())
            {
                helper.fail(String.join("; ", failures));
                return;
            }
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ CA-14

    /**
     * Keep the fixture in daylight (worker policy: every GameTest pins the overworld clock).
     */
    private static void pinClock(final ServerLevel level)
    {
        level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
    }

    /**
     * The follow-up deliveries the old code makes for a request without a keep amount: a fresh walk of the warehouse,
     * consumed in container order.
     */
    private static List<String> refDeliveries(final BuildingWareHouse house, final ServerLevel level, final IRequest<? extends IDeliverable> request)
    {
        final List<String> out = new ArrayList<>();
        int remaining = request.getRequest().getCount();
        for (final Tuple<ItemStack, BlockPos> t : refMatching(house, level, stack -> request.getRequest().matches(stack)))
        {
            if (t.getA().isEmpty())
            {
                continue;
            }
            final int n = Math.min(remaining, t.getA().getCount());
            out.add(n + "x" + t.getA().getItem() + "@" + t.getB().toShortString());
            remaining -= n;
            if (remaining <= 0)
            {
                break;
            }
        }
        return out;
    }

    private static String describeDelivery(final IRequest<?> request)
    {
        if (request == null)
        {
            return "gone";
        }
        if (!(request.getRequest() instanceof final Delivery delivery))
        {
            return "not-a-delivery:" + request.getRequest();
        }
        return delivery.getStack().getCount() + "x" + delivery.getStack().getItem() + "@" + delivery.getStart().getInDimensionLocation().toShortString();
    }

    private static List<String> describeFollowups(final List<IRequest<?>> followups)
    {
        final List<String> out = new ArrayList<>();
        if (followups != null)
        {
            for (final IRequest<?> request : followups)
            {
                out.add(describeDelivery(request));
            }
        }
        return out;
    }

    /**
     * CA-14 (remainder): a request the warehouse resolves from stock walks the warehouse's racks once, not once in
     * attemptResolveRequest and again in getFollowupRequestForCompletion. 20 stocked-item requests x 2 warehouses x 60
     * racks through the real request manager; every request still gets the old algorithm's deliveries.
     */
    public static void warehouseCa14OneRackWalk(final GameTestHelper helper)
    {
        pinClock(helper.getLevel());
        final Fixture f = fixture(helper, "CA-14 walk colony", 2, 60);
        for (int w = 0; w < 2; w++)
        {
            final List<BlockPos> list = f.racks().get(w);
            for (int r = 0; r < list.size(); r++)
            {
                final TileEntityRack rack = rack(f, list.get(r));
                for (int k = 0; k < 3; k++)
                {
                    put(rack, k, new ItemStack(FILLER[(r * 3 + k + w * 7) % FILLER.length], 16));
                }
            }
        }

        helper.runAfterDelay(20, () -> {
            pinClock(f.level());
            final List<String> failures = new ArrayList<>();
            final IRequestManager manager = f.colony().getRequestManager();
            final int n = 20;
            final List<IToken<?>> tokens = new ArrayList<>();
            WarehouseRackIndex.resetStats();
            final long start = System.nanoTime();
            for (int i = 0; i < n; i++)
            {
                // Counts 1..20 so some requests take more than one stack (16 per stack).
                tokens.add(f.requester().createRequest(new Stack(new ItemStack(FILLER[i % FILLER.length]), 1 + i * 2, 1 + i * 2), false));
            }
            final double ms = (System.nanoTime() - start) / 1e6;
            final long walks = WarehouseRackIndex.rackWalks;
            final long probes = WarehouseRackIndex.rackProbes;

            int resolved = 0;
            int deliveries = 0;
            for (int i = 0; i < n; i++)
            {
                @SuppressWarnings("unchecked")
                final IRequest<? extends IDeliverable> request = (IRequest<? extends IDeliverable>) manager.getRequestForToken(tokens.get(i));
                if (request == null || request.getState() != RequestState.FOLLOWUP_IN_PROGRESS)
                {
                    failures.add("request " + i + " state " + (request == null ? "gone" : request.getState()) + " (want FOLLOWUP_IN_PROGRESS)");
                    continue;
                }
                final IRequestResolver<?> resolver = manager.getResolverForRequest(tokens.get(i));
                if (!(resolver instanceof final AbstractWarehouseRequestResolver warehouseResolver))
                {
                    failures.add("request " + i + " resolved by " + resolver);
                    continue;
                }
                final BuildingWareHouse house = (BuildingWareHouse) f.colony().getServerBuildingManager().getBuilding(warehouseResolver.getLocation().getInDimensionLocation());
                final List<String> want = refDeliveries(house, f.level(), request);
                final List<String> got = new ArrayList<>();
                for (final IToken<?> child : request.getChildren())
                {
                    got.add(describeDelivery(manager.getRequestForToken(child)));
                }
                if (!want.equals(got))
                {
                    failures.add("request " + i + " deliveries " + got + " (old algorithm " + want + ")");
                }
                deliveries += got.size();
                resolved++;
            }

            Log.getLogger().info("CA14 walk requests={} resolved={} deliveries={} rackWalks={} rackProbes={} wallMs={}", n, resolved, deliveries, walks, probes,
              String.format("%.2f", ms));

            if (resolved != n)
            {
                failures.add(resolved + " of " + n + " requests resolved by a warehouse");
            }
            if (walks > resolved)
            {
                failures.add(resolved + " resolved requests made " + walks + " rack walks (bound: 1 per resolved request)");
            }
            if (!failures.isEmpty())
            {
                helper.fail(String.join("; ", failures));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * This test calls the resolver by hand, so no courier ever moves the stock the follow-ups promise (reservations, RS1): each step starts
     * from a ledger that holds nothing, as it would after the deliveries of the step before were done.
     */
    private static void forgetPromises(final IRequestManager manager)
    {
        if (manager instanceof final com.minecolonies.core.colony.requestsystem.management.IStandardRequestManager standard)
        {
            standard.getReservationLedger().clear();
        }
    }

    /**
     * CA-14 (remainder): the follow-up only reuses the attempt's rack walk for the same request in the same tick with
     * no rack change in between; otherwise it walks the racks again and its deliveries equal the old algorithm's.
     */
    public static void warehouseCa14FollowupFresh(final GameTestHelper helper)
    {
        pinClock(helper.getLevel());
        final Fixture f = fixture(helper, "CA-14 followup colony", 2, 6);
        helper.runAfterDelay(20, () -> {
            pinClock(f.level());
            final List<String> failures = new ArrayList<>();
            final BuildingWareHouse w0 = f.warehouses().get(0);
            final WarehouseConcreteRequestResolver concrete = resolver(f, w0, true);
            final IRequestManager manager = f.colony().getRequestManager();
            final List<BlockPos> racks = f.racks().get(0);
            final BlockPos b = racks.get(1);
            final BlockPos c = racks.get(2);
            final BlockPos d = racks.get(3);
            final BlockPos e = racks.get(4);
            final IRequest<? extends IDeliverable> dirt5 = newRequest(f, new Stack(new ItemStack(Items.DIRT), 5, 5));
            final IRequest<? extends IDeliverable> stone2 = newRequest(f, new Stack(new ItemStack(Items.STONE), 2, 2));
            final IRequest<? extends IDeliverable> dirt8 = newRequest(f, new Stack(new ItemStack(Items.DIRT), 8, 8));

            // Step 1: attempt then follow-up in the same tick, nothing changed: one walk, the old deliveries.
            put(rack(f, b), 0, new ItemStack(Items.DIRT, 5));
            put(rack(f, d), 0, new ItemStack(Items.STONE, 2));
            forgetPromises(manager);
            WarehouseRackIndex.resetStats();
            final List<IToken<?>> attempt1 = concrete.attemptResolveRequest(manager, dirt5);
            expect(failures, "1-attempt-from-stock", attempt1 != null && attempt1.isEmpty(), true);
            List<String> got = describeFollowups(concrete.getFollowupRequestForCompletion(manager, dirt5));
            List<String> want = refDeliveries(w0, f.level(), dirt5);
            if (!want.equals(got))
            {
                failures.add("1-unchanged: deliveries " + got + " (old algorithm " + want + ")");
            }
            if (WarehouseRackIndex.rackWalks != 1)
            {
                failures.add("1-unchanged: attempt + follow-up made " + WarehouseRackIndex.rackWalks + " rack walks (want 1)");
            }

            // Step 2: the stock moves from B to C between attempt and follow-up: the follow-up must see C.
            forgetPromises(manager);
            concrete.attemptResolveRequest(manager, dirt5);
            put(rack(f, b), 0, ItemStack.EMPTY);
            put(rack(f, c), 0, new ItemStack(Items.DIRT, 5));
            got = describeFollowups(concrete.getFollowupRequestForCompletion(manager, dirt5));
            want = refDeliveries(w0, f.level(), dirt5);
            if (!want.equals(got))
            {
                failures.add("2-moved-B-to-C: deliveries " + got + " (old algorithm " + want + ")");
            }

            // Step 3: attempt for one request, follow-up for another: the follow-up must not reuse the first's stacks.
            forgetPromises(manager);
            concrete.attemptResolveRequest(manager, dirt5);
            got = describeFollowups(concrete.getFollowupRequestForCompletion(manager, stone2));
            want = refDeliveries(w0, f.level(), stone2);
            if (!want.equals(got))
            {
                failures.add("3-other-request: deliveries " + got + " (old algorithm " + want + ")");
            }

            // Step 4: partial stock (5 of 8): the attempt makes a child; more stock arrives before the follow-up.
            forgetPromises(manager);
            final List<IToken<?>> attempt4 = concrete.attemptResolveRequest(manager, dirt8);
            expect(failures, "4-attempt-partial", attempt4 != null && attempt4.size() == 1, true);
            put(rack(f, e), 0, new ItemStack(Items.DIRT, 3));
            got = describeFollowups(concrete.getFollowupRequestForCompletion(manager, dirt8));
            want = refDeliveries(w0, f.level(), dirt8);
            if (!want.equals(got))
            {
                failures.add("4-partial-then-restocked: deliveries " + got + " (old algorithm " + want + ")");
            }

            // Step 5: attempt in this tick, follow-up in a later tick: the follow-up walks the racks again.
            forgetPromises(manager);
            concrete.attemptResolveRequest(manager, dirt5);
            helper.runAfterDelay(1, () -> {
                pinClock(f.level());
                final long before = WarehouseRackIndex.rackWalks;
                final List<String> later = describeFollowups(concrete.getFollowupRequestForCompletion(manager, dirt5));
                final List<String> laterWant = refDeliveries(w0, f.level(), dirt5);
                if (!laterWant.equals(later))
                {
                    failures.add("5-later-tick: deliveries " + later + " (old algorithm " + laterWant + ")");
                }
                if (WarehouseRackIndex.rackWalks - before != 1)
                {
                    failures.add("5-later-tick: follow-up made " + (WarehouseRackIndex.rackWalks - before) + " rack walks (want 1, a fresh walk)");
                }
                Log.getLogger().info("CA14 followup failures={}", failures.size());
                if (!failures.isEmpty())
                {
                    helper.fail(String.join("; ", failures));
                    return;
                }
                helper.succeed();
            });
        });
    }

    /**
     * CA-14 (covered by PF4): canResolveRequest sums the other warehouses from the maintained warehouse list, so the
     * buildings it looks at per call grow with the warehouses (W), not with every building in the colony (B).
     */
    public static void warehouseCa14WarehousesOnly(final GameTestHelper helper)
    {
        pinClock(helper.getLevel());
        final Fixture f = fixture(helper, "CA-14 warehouses colony", 2, 2);
        for (int k = 0; k < 12; k++)
        {
            MinecoloniesGameTests.placeProductionBuilding(helper, f.colony(), ModBlocks.blockHutBuilder, new BlockPos(-6 + 4 * k, 1, 26),
              "fundamentals/builder1.blueprint");
        }
        put(rack(f, f.racks().get(0).get(0)), 0, new ItemStack(Items.DIRT, 3));
        put(rack(f, f.racks().get(1).get(0)), 0, new ItemStack(Items.DIRT, 3));

        helper.runAfterDelay(20, () -> {
            pinClock(f.level());
            final List<String> failures = new ArrayList<>();
            final IRequestManager manager = f.colony().getRequestManager();
            final WarehouseConcreteRequestResolver concrete = resolver(f, f.warehouses().get(0), true);
            final IRequest<? extends IDeliverable> six = newRequest(f, new Stack(new ItemStack(Items.DIRT), 6, 6));
            final IRequest<? extends IDeliverable> seven = newRequest(f, new Stack(new ItemStack(Items.DIRT), 7, 7));
            final int buildings = f.colony().getServerBuildingManager().getBuildings().size();
            final int warehouses = f.colony().getServerBuildingManager().getWareHouses().size();
            final int calls = 10;

            AbstractWarehouseRequestResolver.otherBuildingsVisited = 0;
            for (int i = 0; i < calls; i++)
            {
                // 3 + 3 = 6: seven never reaches its count, so every call visits the whole list.
                expect(failures, "seven-" + i, concrete.canResolveRequest(manager, seven), false);
                expect(failures, "six-" + i, concrete.canResolveRequest(manager, six), true);
            }
            final long visits = AbstractWarehouseRequestResolver.otherBuildingsVisited;
            Log.getLogger().info("CA14 warehouses buildings={} warehouses={} canResolveCalls={} buildingsVisited={}", buildings, warehouses, 2 * calls, visits);

            if (buildings < warehouses + 12)
            {
                failures.add("fixture has " + buildings + " buildings, want at least " + (warehouses + 12));
            }
            if (visits > 2L * calls * warehouses)
            {
                failures.add(2 * calls + " canResolve calls visited " + visits + " buildings (bound " + (2L * calls * warehouses) + " = calls x warehouses; colony has "
                               + buildings + " buildings)");
            }
            if (!failures.isEmpty())
            {
                helper.fail(String.join("; ", failures));
                return;
            }
            helper.succeed();
        });
    }
}
