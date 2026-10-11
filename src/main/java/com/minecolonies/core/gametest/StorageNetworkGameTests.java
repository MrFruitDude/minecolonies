package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.storage.IColonyStorage;
import com.minecolonies.api.colony.storage.StorageUsage;
import com.minecolonies.api.colony.storage.WarehouseUsage;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.WarehouseRequestQueueModule;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.requestsystem.RsFlags;
import com.minecolonies.core.colony.requestsystem.management.manager.StandardRequestManager;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import com.minecolonies.core.entity.ai.workers.service.EntityAIWorkDeliveryman;
import com.minecolonies.core.tileentities.TileEntityRack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The global storage network (glob {@code minecolonies:storage_*}): one stock index over all warehouses, one courier pool,
 * sources and drop-offs chosen by estimated completion time. Layout of the fixture (relative): builder (8,1,2), warehouse A
 * (16,1,2) with its racks at z=10, warehouse B (30,1,12) with its racks at z=22, courier hut (32,1,2).
 */
public final class StorageNetworkGameTests
{
    private static final Predicate<ItemStack> LOGS = stack -> stack.is(Items.OAK_LOG);
    private static final Predicate<ItemStack> DIRT = stack -> stack.is(Items.DIRT);
    private static final Predicate<ItemStack> COBBLE = stack -> stack.is(Items.COBBLESTONE);

    private StorageNetworkGameTests()
    {
    }

    /**
     * Registers the tests (glob {@code minecolonies:storage_*}); each runs in its own colony environment.
     */
    static void register(final net.neoforged.neoforge.event.RegisterGameTestsEvent event)
    {
        final String[] names = {"index_tracks_racks", "available_reserve_aware", "capacity_and_full", "any_courier_serves_any_warehouse", "courier_locked_flag_off",
          "completion_time_beats_nearest", "request_splits_across_warehouses", "no_routing_to_unserved_warehouse", "dump_continues_past_full_rack", "dump_stops_flag_off",
          "dump_goes_to_warehouse_with_room", "dump_stays_flag_off", "save_load_mid_delivery", "conservation", "full_storage_pickups_wait", "flag_flip_loses_nothing", "item_cap_stops_collection", "no_cap_collects", "resolver_checks_are_cheap"};
        final java.util.List<java.util.function.Consumer<GameTestHelper>> bodies = List.of(StorageNetworkGameTests::indexTracksRacks, StorageNetworkGameTests::availableIsReserveAware,
          StorageNetworkGameTests::capacityAndFull, StorageNetworkGameTests::anyCourierServesAnyWarehouse, StorageNetworkGameTests::courierLockedFlagOff,
          StorageNetworkGameTests::completionTimeBeatsNearest, StorageNetworkGameTests::requestSplitsAcrossWarehouses, StorageNetworkGameTests::noRoutingToUnservedWarehouse,
          StorageNetworkGameTests::dumpContinuesPastFullRack, StorageNetworkGameTests::dumpStopsFlagOff, StorageNetworkGameTests::dumpGoesToWarehouseWithRoom,
          StorageNetworkGameTests::dumpStaysFlagOff, StorageNetworkGameTests::saveLoadMidDelivery, StorageNetworkGameTests::conservation, StorageNetworkGameTests::fullStoragePickupsWait, StorageNetworkGameTests::flagFlipLosesNothing, StorageNetworkGameTests::itemCapStopsCollection, StorageNetworkGameTests::noCapCollects, StorageNetworkGameTests::resolverChecksAreCheap);
        for (int i = 0; i < names.length; i++)
        {
            final String name = "storage_" + names[i];
            final java.util.function.Consumer<GameTestHelper> body = bodies.get(i);
            event.registerTest(
              net.minecraft.resources.Identifier.fromNamespaceAndPath(com.minecolonies.api.util.constant.Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, body),
              MinecoloniesGameTestRegistrar.isolatedColonyData(event, name));
        }
    }

    // ------------------------------------------------------------------ fixture

    /**
     * Two warehouses, a builder, a courier hut.
     */
    private static final class Net
    {
        final GameTestHelper    helper;
        final RsFixture         f;
        final BuildingWareHouse a;
        final BuildingWareHouse b;
        final IBuilding         hut;
        final List<BlockPos>    racksB = new ArrayList<>();

        private Net(final GameTestHelper helper, final RsFixture f, final BuildingWareHouse b, final IBuilding hut)
        {
            this.helper = helper;
            this.f = f;
            this.a = f.warehouse;
            this.b = b;
            this.hut = hut;
        }

        static Net found(final GameTestHelper helper, final String name, final int racksA, final int racksB)
        {
            final RsFixture f = RsFixture.found(helper, name, racksA);
            final IBuilding second = f.place(ModBlocks.blockHutWareHouse, new BlockPos(30, 1, 12), "craftsmanship/storage/warehouse1.blueprint");
            helper.assertTrue(second instanceof BuildingWareHouse, "second warehouse is " + second);
            final IBuilding hut = f.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");
            final Net net = new Net(helper, f, (BuildingWareHouse) second, hut);
            for (int r = 0; r < racksB; r++)
            {
                final BlockPos rel = new BlockPos(22 + 2 * (r % 8), 1, 22 + 2 * (r / 8));
                helper.setBlock(rel, ModBlocks.blockRack.defaultBlockState());
                final BlockPos abs = helper.absolutePos(rel);
                net.b.registerBlockPosition(ModBlocks.blockRack, abs, f.level);
                net.racksB.add(abs);
            }
            return net;
        }

        IColonyStorage storage()
        {
            return f.colony.getServerBuildingManager().getColonyStorage();
        }

        TileEntityRack rackB(final int index)
        {
            final BlockEntity entity = f.level.getBlockEntity(racksB.get(index));
            helper.assertTrue(entity instanceof TileEntityRack, "no rack at " + racksB.get(index));
            return (TileEntityRack) entity;
        }

        void stockB(final int rackIndex, final ItemStack stack)
        {
            insert(helper, rackB(rackIndex), stack);
        }

        void stockA(final int rackIndex, final ItemStack stack)
        {
            f.stock(rackIndex, stack);
        }

        /**
         * What the racks really hold, counted rack by rack (the old way), for the checks of the index.
         */
        int bruteForce(final Predicate<ItemStack> predicate)
        {
            return a.hasEnoughElseCount(predicate, Integer.MAX_VALUE) + b.hasEnoughElseCount(predicate, Integer.MAX_VALUE);
        }

        /**
         * Hires a courier (a live citizen with AI) into the hut and the given warehouse's courier pool.
         */
        ICitizenData courier(final BuildingWareHouse home, final BlockPos spawn)
        {
            final ICitizenData courier = f.spawn(spawn, false);
            helper.assertTrue(hut.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier), "courier not assigned to the courier hut");
            helper.assertTrue(home.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "courier not assigned to the warehouse");
            return courier;
        }

        /**
         * Same, without an AI of its own: the test drives it.
         */
        ICitizenData quietCourier(final BuildingWareHouse home, final BlockPos spawn)
        {
            final ICitizenData courier = f.spawn(spawn, true);
            helper.assertTrue(hut.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier), "courier not assigned to the courier hut");
            helper.assertTrue(home.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "courier not assigned to the warehouse");
            return courier;
        }
    }

    /**
     * The racks of a warehouse as the building lists them (the hut's own inventory is one of them).
     */
    private static List<TileEntityRack> containersOf(final Net n, final BuildingWareHouse warehouse)
    {
        final List<TileEntityRack> list = new ArrayList<>();
        for (final BlockPos pos : warehouse.getContainers())
        {
            if (n.f.level.getBlockEntity(pos) instanceof final TileEntityRack rack)
            {
                list.add(rack);
            }
        }
        return list;
    }

    private static int bruteSlots(final Net n)
    {
        int total = 0;
        for (final BuildingWareHouse warehouse : List.of(n.a, n.b))
        {
            for (final TileEntityRack rack : containersOf(n, warehouse))
            {
                total += rack.getInventory().getSlots();
            }
        }
        return total;
    }

    private static int bruteUsed(final Net n)
    {
        int used = 0;
        for (final BuildingWareHouse warehouse : List.of(n.a, n.b))
        {
            for (final TileEntityRack rack : containersOf(n, warehouse))
            {
                for (int slot = 0; slot < rack.getInventory().getSlots(); slot++)
                {
                    if (!rack.getInventory().getStackInSlot(slot).isEmpty())
                    {
                        used++;
                    }
                }
            }
        }
        return used;
    }

    /**
     * Every slot of every container of the warehouse holds a full stack of the item.
     */
    private static void fillWarehouse(final Net n, final BuildingWareHouse warehouse, final ItemStack stack)
    {
        for (final TileEntityRack rack : containersOf(n, warehouse))
        {
            fillRack(rack, stack, rack.getInventory().getSlots());
        }
    }

    private static void insert(final GameTestHelper helper, final TileEntityRack rack, final ItemStack stack)
    {
        ItemStack rest = stack.copy();
        for (int slot = 0; slot < rack.getInventory().getSlots() && !rest.isEmpty(); slot++)
        {
            rest = rack.getInventory().insertItem(slot, rest, false);
        }
        helper.assertTrue(rest.isEmpty(), "the rack could not take " + stack);
    }

    private static IToken<?> ask(final IBuilding requester, final int logs)
    {
        return requester.createRequest(new Stack(new ItemStack(Items.OAK_LOG, logs), logs, logs), false);
    }

    private static void day(final ServerLevel level)
    {
        level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
    }

    private static List<Delivery> deliveriesOf(final Net n, final IToken<?> parent)
    {
        final List<Delivery> deliveries = new ArrayList<>();
        final IRequest<?> request = n.f.request(parent);
        for (final IToken<?> child : request.getChildren())
        {
            final IRequest<?> childRequest = n.f.request(child);
            if (childRequest != null && childRequest.getRequest() instanceof final Delivery delivery)
            {
                deliveries.add(delivery);
            }
        }
        return deliveries;
    }

    private static int deliveredCount(final List<Delivery> deliveries)
    {
        return deliveries.stream().mapToInt(d -> d.getStack().getCount()).sum();
    }

    // ------------------------------------------------------------------ the index

    /**
     * The colony-wide index agrees with the racks after every kind of change: insert, extract, a rack added, a rack broken, a
     * warehouse removed; and its slot figures follow the racks.
     */
    public static void indexTracksRacks(final GameTestHelper helper)
    {
        final Net n = Net.found(helper, "storage-index", 3, 3);
        final IColonyStorage storage = n.storage();
        helper.assertTrue(storage.physical(LOGS, Integer.MAX_VALUE) == 0 && storage.usage().totalSlots() == bruteSlots(n) && storage.usage().usedSlots() == 0, "empty fixture: " + storage.usage()
                                                                                                                                                      + " brute " + bruteSlots(n));

        n.stockA(0, new ItemStack(Items.OAK_LOG, 100));
        n.stockB(1, new ItemStack(Items.OAK_LOG, 30));
        n.stockB(0, new ItemStack(Items.DIRT, 7));
        helper.assertTrue(storage.physical(LOGS, Integer.MAX_VALUE) == 130 && n.bruteForce(LOGS) == 130, "logs after inserts: " + storage.physical(LOGS, Integer.MAX_VALUE));
        helper.assertTrue(storage.physical(new ItemStorage(new ItemStack(Items.DIRT))) == 7, "dirt by key");
        helper.assertTrue(storage.physicalIn(n.a.getID(), LOGS, Integer.MAX_VALUE) == 100 && storage.physicalIn(n.b.getID(), LOGS, Integer.MAX_VALUE) == 30, "per warehouse");
        helper.assertTrue(storage.usage().items() == 137 && storage.usage().distinctItems() == 2, "items " + storage.usage());
        helper.assertTrue(storage.usage().usedSlots() == 2 + 1 + 1 && bruteUsed(n) == 4, "used slots (100 logs = 2 stacks, 30 logs, 7 dirt): " + storage.usage());

        // Extract through the handler (what a courier does).
        final ItemStack taken = n.f.rack(0).getInventory().extractItem(0, 40, false);
        helper.assertTrue(taken.getCount() == 40, "extracted " + taken);
        helper.assertTrue(storage.physical(LOGS, Integer.MAX_VALUE) == 90 && n.bruteForce(LOGS) == 90, "logs after the extract: " + storage.physical(LOGS, Integer.MAX_VALUE));

        // A rack is added to warehouse B, with content.
        final int slotsBefore = storage.usage().totalSlots();
        final BlockPos rel = new BlockPos(22 + 2 * 3, 1, 22);
        helper.setBlock(rel, ModBlocks.blockRack.defaultBlockState());
        final BlockPos abs = helper.absolutePos(rel);
        n.b.registerBlockPosition(ModBlocks.blockRack, abs, n.f.level);
        n.racksB.add(abs);
        helper.assertTrue(storage.usage().totalSlots() == bruteSlots(n) && storage.usage().totalSlots() == slotsBefore + 27, "the new rack's slots count: " + storage.usage());
        n.stockB(3, new ItemStack(Items.OAK_LOG, 10));
        helper.assertTrue(storage.physical(LOGS, Integer.MAX_VALUE) == 100 && n.bruteForce(LOGS) == 100, "logs after the added rack: " + storage.physical(LOGS, Integer.MAX_VALUE));

        // A rack is broken.
        helper.setBlock(new BlockPos(22 + 2 * 3, 1, 22), Blocks.AIR.defaultBlockState());
        helper.assertTrue(storage.physical(LOGS, Integer.MAX_VALUE) == 90 && n.bruteForce(LOGS) == 90, "logs after the rack was broken: " + storage.physical(LOGS, Integer.MAX_VALUE));
        helper.assertTrue(storage.usage().totalSlots() == slotsBefore && storage.usage().totalSlots() == bruteSlots(n), "the broken rack's slots are gone: " + storage.usage());

        // A storage upgrade of the racks: more slots.
        n.a.upgradeContainers(n.f.level);
        helper.assertTrue(storage.usage().totalSlots() > slotsBefore && storage.usage().totalSlots() == bruteSlots(n) && storage.usage().usedSlots() == bruteUsed(n),
          "an upgraded warehouse has more slots: " + storage.usage() + " brute " + bruteSlots(n));

        // A warehouse goes away: its stock leaves the colony's.
        n.f.colony.getServerBuildingManager().removeBuilding(n.b, java.util.Collections.emptySet());
        helper.assertTrue(storage.physical(LOGS, Integer.MAX_VALUE) == 60 && storage.physical(new ItemStorage(new ItemStack(Items.DIRT))) == 0,
          "logs and dirt after warehouse B was removed: " + storage.physical(LOGS, Integer.MAX_VALUE) + " dirt " + storage.physical(new ItemStorage(new ItemStack(Items.DIRT))));
        helper.assertTrue(storage.warehouses().size() == 1 && storage.usage().warehouses() == 1, "one warehouse left: " + storage.warehouses());
        helper.succeed();
    }

    /**
     * available = physical - reserved, over all warehouses.
     */
    public static void availableIsReserveAware(final GameTestHelper helper)
    {
        RsFlags.overrideReservations(true);
        final Net n = Net.found(helper, "storage-available", 2, 2);
        final IColonyStorage storage = n.storage();
        n.stockA(0, new ItemStack(Items.OAK_LOG, 10));
        n.stockB(0, new ItemStack(Items.OAK_LOG, 6));
        final ItemStorage logs = new ItemStorage(new ItemStack(Items.OAK_LOG));
        helper.assertTrue(storage.available(logs) == 16 && storage.physical(logs) == 16, "nothing reserved yet");

        final IToken<?> token = ask(n.f.builder, 8);
        final IRequest<?> request = n.f.request(token);
        helper.assertTrue(request.getState() == RequestState.FOLLOWUP_IN_PROGRESS, "the request is served: " + n.f.describe(token));
        helper.assertTrue(storage.physical(logs) == 16, "physical is unchanged by a promise");
        helper.assertTrue(storage.available(logs) == 8 && storage.available(LOGS, Integer.MAX_VALUE) == 8, "8 of the 16 are promised: " + storage.available(logs));
        helper.assertTrue(storage.availableIn(n.a.getID(), LOGS, Integer.MAX_VALUE) + storage.availableIn(n.b.getID(), LOGS, Integer.MAX_VALUE) == 8, "per warehouse sums up");

        n.f.manager().updateRequestState(token, RequestState.CANCELLED);
        helper.assertTrue(storage.available(logs) == 16, "cancelling frees the promise: " + storage.available(logs));
        helper.succeed();
    }

    /**
     * Capacity: total slots over all warehouses, used slots, "full" when no slot is free, the colony-wide notice is
     * rate-limited.
     */
    public static void capacityAndFull(final GameTestHelper helper)
    {
        final Net n = Net.found(helper, "storage-capacity", 1, 1);
        final IColonyStorage storage = n.storage();
        StorageUsage usage = storage.usage();
        helper.assertTrue(usage.totalSlots() == bruteSlots(n) && usage.usedSlots() == 0 && !storage.isFull() && usage.warehouses() == 2, "empty: " + usage);
        helper.assertTrue(storage.hasRoomFor(new ItemStack(Items.DIRT)), "room for dirt");

        fillWarehouse(n, n.a, new ItemStack(Items.STONE));
        usage = storage.usage();
        helper.assertTrue(usage.usedSlots() == bruteUsed(n) && usage.usedSlots() > 0 && !storage.isFull(), "one warehouse full, the colony is not: " + usage);
        final List<WarehouseUsage> per = storage.warehouses();
        helper.assertTrue(per.size() == 2 && per.stream().anyMatch(w -> w.usedSlots() == w.totalSlots() && w.totalSlots() > 0 && w.fill() >= 1.0D)
                            && per.stream().anyMatch(w -> w.usedSlots() == 0), "per warehouse fill: " + per);

        fillWarehouse(n, n.b, new ItemStack(Items.COBBLESTONE));
        helper.assertTrue(storage.isFull() && storage.usage().freeSlots() == 0, "both full: " + storage.usage());
        helper.assertTrue(!storage.hasRoomFor(new ItemStack(Items.DIRT)), "no room for dirt");
        helper.assertTrue(!storage.hasRoomFor(new ItemStack(Items.STONE)), "full stacks of stone have nothing to merge into");

        final com.minecolonies.core.colony.storage.ColonyStorage impl = (com.minecolonies.core.colony.storage.ColonyStorage) storage;
        helper.assertTrue(impl.noticeFull(new ItemStack(Items.DIRT)), "the first notice is sent");
        helper.assertTrue(!impl.noticeFull(new ItemStack(Items.DIRT)), "the next one within five minutes is not");
        helper.succeed();
    }

    // ------------------------------------------------------------------ one courier pool

    /**
     * Stock only in warehouse B, the only courier belongs to warehouse A: the builder still gets its logs.
     */
    public static void anyCourierServesAnyWarehouse(final GameTestHelper helper)
    {
        RsFlags.overrideStorageNetwork(true);
        final Net n = Net.found(helper, "storage-any-courier", 2, 2);
        n.stockB(0, new ItemStack(Items.OAK_LOG, 10));
        final ICitizenData courier = n.courier(n.a, new BlockPos(34, 1, 8));
        courier.getEntity().ifPresent(e -> e.setPos(n.hut.getID().getX() + 0.5D, n.hut.getID().getY(), n.hut.getID().getZ() + 0.5D));

        helper.runAfterDelay(100, () -> {
            final IToken<?> token = ask(n.f.builder, 8);
            helper.assertTrue(n.f.request(token).getState() == RequestState.FOLLOWUP_IN_PROGRESS, "the request is served from warehouse B: " + n.f.describe(token));
            helper.succeedWhen(() -> {
                day(n.f.level);
                helper.assertTrue(InventoryUtils.getCountFromBuilding(n.f.builder, LOGS) == 8,
                  "a courier of warehouse A carried the logs from warehouse B: builder has " + InventoryUtils.getCountFromBuilding(n.f.builder, LOGS)
                    + ", A has " + n.a.hasEnoughElseCount(LOGS, 100) + ", B has " + n.b.hasEnoughElseCount(LOGS, 100) + " / " + n.f.describe(token));
                helper.assertTrue(n.storage().flowsSince(0).stream().anyMatch(flow -> flow.from().equals(n.b.getID()) && flow.to().equals(n.f.builder.getID()) && flow.count() == 8),
                  "the network flows show 8 logs from warehouse B to the builder: " + n.storage().flowsSince(0));
            });
        });
    }

    /**
     * Flag off: a delivery out of warehouse B is carried by warehouse B's couriers only, so with the only courier at A the
     * logs stay where they are (the lock of the old system).
     */
    public static void courierLockedFlagOff(final GameTestHelper helper)
    {
        RsFlags.overrideStorageNetwork(false);
        final Net n = Net.found(helper, "storage-lock-off", 2, 2);
        n.stockB(0, new ItemStack(Items.OAK_LOG, 10));
        final ICitizenData courier = n.courier(n.a, new BlockPos(34, 1, 8));
        courier.getEntity().ifPresent(e -> e.setPos(n.hut.getID().getX() + 0.5D, n.hut.getID().getY(), n.hut.getID().getZ() + 0.5D));

        helper.runAfterDelay(100, () -> {
            final IToken<?> token = ask(n.f.builder, 8);
            final long start = n.f.level.getGameTime();
            helper.succeedWhen(() -> {
                day(n.f.level);
                helper.assertTrue(n.f.level.getGameTime() - start > 1500, "waiting");
                helper.assertTrue(InventoryUtils.getCountFromBuilding(n.f.builder, LOGS) == 0 && n.b.hasEnoughElseCount(LOGS, 100) == 10 && n.f.request(token).getState() != RequestState.RECEIVED,
                  "with the network off warehouse A's courier does not carry from B: builder has " + InventoryUtils.getCountFromBuilding(n.f.builder, LOGS));
            });
        });
    }

    // ------------------------------------------------------------------ the choice of warehouse

    /**
     * Both warehouses hold logs. The nearer one serves the first request; once its queue is long, the farther one finishes
     * earlier and serves the next.
     */
    public static void completionTimeBeatsNearest(final GameTestHelper helper)
    {
        RsFlags.overrideStorageNetwork(true);
        final Net n = Net.found(helper, "storage-congestion", 2, 2);
        n.stockA(0, new ItemStack(Items.OAK_LOG, 20));
        n.stockB(0, new ItemStack(Items.OAK_LOG, 20));

        final IToken<?> first = ask(n.f.builder, 4);
        final List<Delivery> firstDeliveries = deliveriesOf(n, first);
        helper.assertTrue(!firstDeliveries.isEmpty() && firstDeliveries.stream().allMatch(d -> n.a.hasContainerPosition(d.getStart().getInDimensionLocation())),
          "unloaded colony: the nearer warehouse A serves: " + firstDeliveries.stream().map(d -> d.getStart().getInDimensionLocation()).toList() + " " + n.f.describe(first));

        // Warehouse A's queue grows (tasks nobody has taken yet).
        final WarehouseRequestQueueModule queue = n.a.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE);
        for (int i = 0; i < 6; i++)
        {
            queue.addRequest(new com.minecolonies.api.colony.requestsystem.token.StandardToken());
        }
        final IToken<?> second = ask(n.f.builder, 4);
        final List<Delivery> secondDeliveries = deliveriesOf(n, second);
        helper.assertTrue(!secondDeliveries.isEmpty() && secondDeliveries.stream().allMatch(d -> n.b.hasContainerPosition(d.getStart().getInDimensionLocation())),
          "congested A: the farther warehouse B finishes earlier: " + secondDeliveries.stream().map(d -> d.getStart().getInDimensionLocation()).toList() + " " + n.f.describe(second));
        helper.succeed();
    }

    /**
     * A request larger than any single warehouse holds is served by deliveries from both, with no warehouse-to-warehouse leg.
     */
    public static void requestSplitsAcrossWarehouses(final GameTestHelper helper)
    {
        RsFlags.overrideStorageNetwork(true);
        RsFlags.overrideReservations(true);
        final Net n = Net.found(helper, "storage-split", 2, 2);
        n.stockA(0, new ItemStack(Items.OAK_LOG, 5));
        n.stockB(0, new ItemStack(Items.OAK_LOG, 5));

        final IToken<?> token = ask(n.f.builder, 8);
        final IRequest<?> request = n.f.request(token);
        helper.assertTrue(request.getState() == RequestState.FOLLOWUP_IN_PROGRESS, "served: " + n.f.describe(token));
        final List<Delivery> deliveries = deliveriesOf(n, token);
        helper.assertTrue(deliveries.size() == request.getChildren().size(), "every child is a delivery, no warehouse-to-warehouse leg: " + request.getChildren().size() + " children, " + deliveries.size() + " deliveries");
        helper.assertTrue(deliveredCount(deliveries) == 8, "8 logs are on their way: " + deliveredCount(deliveries));
        helper.assertTrue(deliveries.stream().anyMatch(d -> n.a.hasContainerPosition(d.getStart().getInDimensionLocation()))
                            && deliveries.stream().anyMatch(d -> n.b.hasContainerPosition(d.getStart().getInDimensionLocation())), "from both warehouses");
        final ReservationLedger ledger = n.f.ledger();
        helper.assertTrue(ledger.reservedStock(n.a.getID(), LOGS) + ledger.reservedStock(n.b.getID(), LOGS) == 8, "8 reserved over the two warehouses: " + ledger.all());
        helper.assertTrue(ledger.reservedSpace(n.f.builder.getID(), LOGS) == 8, "8 spaces reserved at the builder");
        helper.succeed();
    }

    /**
     * Stock in a warehouse no courier can reach (out of the service range) is not promised to anyone; the same stock in reach is.
     */
    public static void noRoutingToUnservedWarehouse(final GameTestHelper helper)
    {
        RsFlags.overrideStorageNetwork(true);
        RsFlags.overrideStorageServiceRange(10);
        final Net n = Net.found(helper, "storage-unserved", 2, 2);
        n.stockB(0, new ItemStack(Items.OAK_LOG, 10));
        n.quietCourier(n.a, new BlockPos(34, 1, 8));

        final IToken<?> first = ask(n.f.builder, 8);
        helper.assertTrue(n.f.request(first).getChildren().isEmpty() && n.f.isRetrying(first),
          "warehouse B is out of reach of the only courier: the request is not routed there: " + n.f.describe(first));
        helper.assertTrue(n.f.ledger().isEmpty(), "nothing is reserved at B: " + n.f.ledger().all());

        RsFlags.overrideStorageServiceRange(0);
        final IToken<?> second = ask(n.f.builder, 8);
        helper.assertTrue(n.f.request(second).getState() == RequestState.FOLLOWUP_IN_PROGRESS && !n.f.request(second).getChildren().isEmpty(),
          "in reach: the request is served: " + n.f.describe(second));
        helper.succeed();
    }

    // ------------------------------------------------------------------ the dump

    private static Object invoke(final Object target, final String name) throws ReflectiveOperationException
    {
        final Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static void fillRack(final TileEntityRack rack, final ItemStack stack, final int slots)
    {
        for (int slot = 0; slot < slots; slot++)
        {
            rack.getInventory().insertItem(slot, stack.copyWithCount(stack.getMaxStackSize()), false);
        }
    }

    private static int count(final ICitizenData courier, final Predicate<ItemStack> predicate)
    {
        return InventoryUtils.getItemCountInItemHandler(courier.getInventory(), predicate);
    }

    /**
     * The warehouse has no room for the first stack the courier carries but can take the next: the dump goes on past it.
     * (Flag off: it stops at the first stack, as before.)
     */
    public static void dumpContinuesPastFullRack(final GameTestHelper helper)
    {
        dumpPastFullRack(helper, true);
    }

    public static void dumpStopsFlagOff(final GameTestHelper helper)
    {
        dumpPastFullRack(helper, false);
    }

    private static void dumpPastFullRack(final GameTestHelper helper, final boolean network)
    {
        RsFlags.overrideStorageNetwork(network);
        final Net n = Net.found(helper, "storage-dump-rack-" + network, 1, 0);
        // No free slot anywhere in the warehouse, but a part-filled cobblestone stack that takes more.
        fillWarehouse(n, n.a, new ItemStack(Items.STONE));
        final TileEntityRack first = containersOf(n, n.a).get(0);
        first.getInventory().setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 10));
        helper.assertTrue(bruteUsed(n) == containersOf(n, n.a).stream().mapToInt(r -> r.getInventory().getSlots()).sum(), "every container of warehouse A is full");
        final ICitizenData courier = n.quietCourier(n.a, new BlockPos(20, 1, 6));
        courier.getInventory().insertItem(0, new ItemStack(Items.DIRT, 5), false);
        courier.getInventory().insertItem(1, new ItemStack(Items.COBBLESTONE, 4), false);

        n.a.getTileEntity().dumpInventoryIntoWareHouse(courier.getInventory());
        if (network)
        {
            helper.assertTrue(count(courier, COBBLE) == 0 && n.a.hasEnoughElseCount(COBBLE, 1000) == 14, "the cobblestone was merged into the part-filled stack: courier " + count(courier, COBBLE));
            helper.assertTrue(count(courier, DIRT) == 5 && n.a.hasEnoughElseCount(DIRT, 1000) == 0, "the dirt had no room and stays with the courier (nothing lost)");
        }
        else
        {
            helper.assertTrue(count(courier, COBBLE) == 4 && count(courier, DIRT) == 5, "flag off: the dump stopped at the first stack: dirt " + count(courier, DIRT) + " cobble " + count(courier, COBBLE));
        }
        helper.succeed();
    }

    /**
     * Warehouse A is full, warehouse B has room: a courier of A dumps its load into B and loses nothing. (Flag off: it keeps
     * its load.)
     */
    public static void dumpGoesToWarehouseWithRoom(final GameTestHelper helper)
    {
        dumpToOtherWarehouse(helper, true);
    }

    public static void dumpStaysFlagOff(final GameTestHelper helper)
    {
        dumpToOtherWarehouse(helper, false);
    }

    private static void dumpToOtherWarehouse(final GameTestHelper helper, final boolean network)
    {
        RsFlags.overrideStorageNetwork(network);
        final Net n = Net.found(helper, "storage-dump-wh-" + network, 1, 1);
        fillWarehouse(n, n.a, new ItemStack(Items.STONE));
        final ICitizenData courier = n.quietCourier(n.a, new BlockPos(20, 1, 6));
        courier.getInventory().insertItem(0, new ItemStack(Items.DIRT, 5), false);
        courier.getInventory().insertItem(1, new ItemStack(Items.COBBLESTONE, 4), false);
        final JobDeliveryman job = courier.getJob(JobDeliveryman.class);
        final EntityAIWorkDeliveryman ai = job.generateAI();
        final int before = count(courier, DIRT) + count(courier, COBBLE) + n.bruteForce(DIRT) + n.bruteForce(COBBLE);

        try
        {
            for (int step = 0; step < 6; step++)
            {
                // The courier is where it wants to dump: next to the warehouse it walks to.
                final com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse target = network ? job.findDumpWarehouse(java.util.Set.of()) : job.findWareHouse();
                if (target != null)
                {
                    courier.getEntity().get().setPos(target.getID().getX() + 0.5D, target.getID().getY(), target.getID().getZ() + 0.5D);
                }
                invoke(ai, "dump");
            }
        }
        catch (final ReflectiveOperationException e)
        {
            helper.fail("could not drive the courier: " + e + " / " + e.getCause());
            return;
        }

        final int after = count(courier, DIRT) + count(courier, COBBLE) + n.bruteForce(DIRT) + n.bruteForce(COBBLE);
        helper.assertTrue(before == after, "nothing lost or duplicated: " + before + " -> " + after);
        if (network)
        {
            helper.assertTrue(count(courier, DIRT) == 0 && count(courier, COBBLE) == 0, "the courier dumped everything: " + count(courier, DIRT) + "/" + count(courier, COBBLE));
            helper.assertTrue(n.b.hasEnoughElseCount(DIRT, 100) == 5 && n.b.hasEnoughElseCount(COBBLE, 100) == 4, "into warehouse B");
            helper.assertTrue(n.storage().flowsSince(0).stream().anyMatch(flow -> flow.to().equals(n.b.getID()) && flow.stack().is(Items.DIRT) && flow.count() == 5),
              "the dump is a flow into warehouse B: " + n.storage().flowsSince(0));
        }
        else
        {
            helper.assertTrue(count(courier, DIRT) == 5 && count(courier, COBBLE) == 4, "flag off: the courier keeps its load, warehouse A is full");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ save and load

    /**
     * Colony saved while a delivery out of B is queued for a courier of A: the queue, the reservations and the index come back.
     */
    public static void saveLoadMidDelivery(final GameTestHelper helper)
    {
        RsFlags.overrideStorageNetwork(true);
        RsFlags.overrideReservations(true);
        final Net n = Net.found(helper, "storage-saveload", 2, 2);
        n.stockB(0, new ItemStack(Items.OAK_LOG, 10));
        n.stockA(0, new ItemStack(Items.DIRT, 3));
        n.quietCourier(n.a, new BlockPos(34, 1, 8));
        final IToken<?> token = ask(n.f.builder, 8);
        final IRequest<?> request = n.f.request(token);
        helper.assertTrue(request.getState() == RequestState.FOLLOWUP_IN_PROGRESS && !request.getChildren().isEmpty(), "served: " + n.f.describe(token));
        final int queuedBefore = queued(n.a) + queued(n.b);
        helper.assertTrue(queuedBefore == request.getChildren().size(), "the deliveries are queued (at the warehouse they start from): " + queuedBefore + " vs " + request.getChildren().size());
        final var reservationsBefore = n.f.ledger().all();

        final var saved = ((Colony) n.f.colony).write(new net.minecraft.nbt.CompoundTag(), n.f.level.registryAccess());
        final Colony restored = Colony.loadColony(saved, n.f.level, n.f.level.registryAccess());
        helper.assertTrue(restored != null, "colony could not be loaded");
        final ReservationLedger after = ((StandardRequestManager) restored.getRequestManager()).getReservationLedger();
        helper.assertTrue(after.all().size() == reservationsBefore.size(), "the reservations came back: " + after.all() + " vs " + reservationsBefore);
        int queuedAfter = 0;
        for (final var wareHouse : restored.getServerBuildingManager().getWareHouses())
        {
            queuedAfter += wareHouse.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE).getMutableRequestList().size();
        }
        helper.assertTrue(queuedAfter == queuedBefore, "the courier queues came back: " + queuedAfter + " vs " + queuedBefore);
        final IColonyStorage restoredStorage = restored.getServerBuildingManager().getColonyStorage();
        helper.assertTrue(restoredStorage.physical(LOGS, Integer.MAX_VALUE) == 10 && restoredStorage.physical(DIRT, Integer.MAX_VALUE) == 3,
          "the index of the loaded colony matches the racks: logs " + restoredStorage.physical(LOGS, Integer.MAX_VALUE) + " dirt " + restoredStorage.physical(DIRT, Integer.MAX_VALUE));
        helper.succeed();
    }

    private static int queued(final BuildingWareHouse wareHouse)
    {
        return wareHouse.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE).getMutableRequestList().size();
    }

    // ------------------------------------------------------------------ nothing is created or lost

    /**
     * Several requests from two builders out of two warehouses with two couriers (one per warehouse): when everything has
     * settled, every item is where the requests say, and the total never changed.
     */
    public static void conservation(final GameTestHelper helper)
    {
        RsFlags.overrideStorageNetwork(true);
        RsFlags.overrideReservations(true);
        final Net n = Net.found(helper, "storage-conservation", 3, 3);
        final IBuilding second = n.f.place(ModBlocks.blockHutBuilder, new BlockPos(8, 1, 12), "fundamentals/builder1.blueprint");
        final IBuilding hut2 = n.f.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 6), "craftsmanship/storage/deliveryman1.blueprint");
        n.stockA(0, new ItemStack(Items.OAK_LOG, 40));
        n.stockA(1, new ItemStack(Items.DIRT, 30));
        n.stockB(0, new ItemStack(Items.OAK_LOG, 40));
        n.stockB(1, new ItemStack(Items.COBBLESTONE, 50));
        final List<ICitizenData> couriers = new ArrayList<>();
        couriers.add(n.courier(n.a, new BlockPos(34, 1, 8)));
        final ICitizenData second2 = n.f.spawn(new BlockPos(34, 1, 10), false);
        helper.assertTrue(hut2.getModule(BuildingModules.COURIER_WORK).assignCitizen(second2), "second courier not assigned to the second courier hut");
        helper.assertTrue(n.b.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(second2), "second courier not assigned to warehouse B");
        couriers.add(second2);
        for (final ICitizenData courier : couriers)
        {
            courier.getEntity().ifPresent(e -> e.setPos(n.hut.getID().getX() + 0.5D, n.hut.getID().getY(), n.hut.getID().getZ() + 0.5D));
        }
        final Predicate<ItemStack> all = stack -> stack.is(Items.OAK_LOG) || stack.is(Items.DIRT) || stack.is(Items.COBBLESTONE);
        final int total = 40 + 30 + 40 + 50;

        helper.runAfterDelay(100, () -> {
            final List<IToken<?>> tokens = new ArrayList<>();
            tokens.add(n.f.builder.createRequest(new Stack(new ItemStack(Items.OAK_LOG, 30), 30, 30), false));
            tokens.add(second.createRequest(new Stack(new ItemStack(Items.OAK_LOG, 30), 30, 30), false));
            tokens.add(n.f.builder.createRequest(new Stack(new ItemStack(Items.COBBLESTONE, 20), 20, 20), false));
            tokens.add(second.createRequest(new Stack(new ItemStack(Items.DIRT, 25), 25, 25), false));
            helper.succeedWhen(() -> {
                day(n.f.level);
                int held = n.bruteForce(all) + InventoryUtils.getCountFromBuilding(n.f.builder, all) + InventoryUtils.getCountFromBuilding(second, all);
                for (final ICitizenData courier : couriers)
                {
                    held += InventoryUtils.getItemCountInItemHandler(courier.getInventory(), all);
                }
                helper.assertTrue(held == total, "items are neither created nor lost: " + held + " vs " + total);
                for (final IToken<?> token : tokens)
                {
                    final IRequest<?> request = n.f.request(token);
                    helper.assertTrue(request == null || request.getState() == RequestState.RESOLVED || request.getState() == RequestState.RECEIVED || request.getState() == RequestState.COMPLETED,
                      "a request is still open: " + n.f.describe(token));
                }
            });
        });
    }

    // ------------------------------------------------------------------ a full colony

    /**
     * When no warehouse has a free slot, a pickup is not queued (a courier would carry a load nobody can take): the producer keeps its
     * output. Once room is made the next pickup is queued.
     */
    public static void fullStoragePickupsWait(final GameTestHelper helper)
    {
        RsFlags.overrideStorageNetwork(true);
        final Net n = Net.found(helper, "storage-full-pickup", 1, 1);
        n.quietCourier(n.a, new BlockPos(34, 1, 8));
        fillWarehouse(n, n.a, new ItemStack(Items.STONE));
        fillWarehouse(n, n.b, new ItemStack(Items.COBBLESTONE));
        helper.assertTrue(n.storage().isFull(), "fixture: the colony is full");

        helper.assertTrue(n.f.builder.createPickupRequest(64, true), "the pickup was requested");
        helper.assertTrue(queued(n.a) + queued(n.b) == 0, "full: no courier task is queued (" + (queued(n.a) + queued(n.b)) + ")");

        // Room in warehouse B.
        for (final TileEntityRack rack : containersOf(n, n.b))
        {
            for (int slot = 0; slot < rack.getInventory().getSlots(); slot++)
            {
                rack.getInventory().extractItem(slot, 64, false);
            }
        }
        helper.assertTrue(!n.storage().isFull(), "fixture: room again");
        n.f.manager().onColonyUpdate(request -> request.getRequest() instanceof com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Pickup);
        helper.succeedWhen(() -> helper.assertTrue(queued(n.a) + queued(n.b) == 1, "with room the pickup is queued for a courier: " + (queued(n.a) + queued(n.b))));
    }

    /**
     * A world saved with the network on and loaded with it off: the delivery queued in the queue of a warehouse nobody works for is taken by
     * the other warehouse's courier (nothing is stranded), and the request still ends in its delivery.
     */
    public static void flagFlipLosesNothing(final GameTestHelper helper)
    {
        RsFlags.overrideStorageNetwork(true);
        RsFlags.overrideReservations(true);
        final Net n = Net.found(helper, "storage-flip", 2, 2);
        n.stockB(0, new ItemStack(Items.OAK_LOG, 10));
        final ICitizenData courier = n.quietCourier(n.a, new BlockPos(34, 1, 8));
        final IToken<?> token = ask(n.f.builder, 8);
        helper.assertTrue(queued(n.b) == 1 && queued(n.a) == 0, "network on: the delivery sits in warehouse B's queue: A " + queued(n.a) + " B " + queued(n.b));

        RsFlags.overrideStorageNetwork(false);
        final JobDeliveryman job = courier.getJob(JobDeliveryman.class);
        final IRequest<?> task = job.getCurrentTask();
        helper.assertTrue(task != null && n.f.request(token).getChildren().contains(task.getId()), "network off: the courier of A takes the delivery out of the orphaned queue of B: " + task);
        helper.assertTrue(queued(n.b) == 0, "and it left the queue");
        helper.assertTrue(n.f.ledger().reservedStock(n.b.getID(), LOGS) == 8, "the promise at B is still held: " + n.f.ledger().all());
        helper.succeed();
    }

    /**
     * The soft cap per good: once the colony holds as much of an item as the cap allows, couriers leave more of it with its producer. Without
     * a cap they collect it.
     */
    public static void itemCapStopsCollection(final GameTestHelper helper)
    {
        capCase(helper, true);
    }

    /**
     * The control of the cap test: the same pickup without a cap fills the courier.
     */
    public static void noCapCollects(final GameTestHelper helper)
    {
        capCase(helper, false);
    }

    private static void capCase(final GameTestHelper helper, final boolean capped)
    {
        RsFlags.overrideStorageNetwork(true);
        final Net n = Net.found(helper, "storage-cap", 2, 2);
        n.stockA(0, new ItemStack(Items.COBBLESTONE, 40));
        final ICitizenData courier = n.quietCourier(n.a, new BlockPos(20, 1, 6));
        final ItemStorage cobble = new ItemStorage(new ItemStack(Items.COBBLESTONE));
        helper.assertTrue(!n.storage().isAtCap(cobble) && n.storage().itemCap() == 0, "no cap: not at cap");
        RsFlags.overrideStorageItemCap(40);
        helper.assertTrue(n.storage().isAtCap(cobble) && !n.storage().isAtCap(new ItemStorage(new ItemStack(Items.DIRT))), "a cap of 40: cobblestone is at it, dirt is not");
        RsFlags.overrideStorageItemCap(41);
        helper.assertTrue(!n.storage().isAtCap(cobble), "one below the cap: still collected");
        RsFlags.overrideStorageItemCap(capped ? 40 : 0);

        // The courier at the builder's hut, which holds 20 cobblestone nobody asked for.
        final ItemStack rest = InventoryUtils.forceItemStackToItemHandler(n.f.builder.getItemHandlerCap(), new ItemStack(Items.COBBLESTONE, 20), stack -> true);
        helper.assertTrue(rest.isEmpty(), "could not stock the hut");
        helper.assertTrue(n.f.builder.createPickupRequest(64, true), "pickup requested");
        final JobDeliveryman job = courier.getJob(JobDeliveryman.class);
        final EntityAIWorkDeliveryman ai = job.generateAI();
        final IRequest<?> task = job.getCurrentTask();
        helper.assertTrue(task != null, "the courier took the pickup");
        try
        {
            courier.getEntity().get().setPos(n.f.builder.getID().getX() + 0.5D, n.f.builder.getID().getY(), n.f.builder.getID().getZ() + 0.5D);
            for (int i = 0; i < 40; i++)
            {
                invoke(ai, "pickup");
            }
        }
        catch (final ReflectiveOperationException e)
        {
            helper.fail("could not drive the courier: " + e + " / " + e.getCause());
            return;
        }
        if (capped)
        {
            helper.assertTrue(count(courier, COBBLE) == 0, "at the cap the courier leaves the cobblestone in the hut: it carries " + count(courier, COBBLE));
            helper.assertTrue(InventoryUtils.getCountFromBuilding(n.f.builder, COBBLE) == 20, "it is still there");
        }
        else
        {
            helper.assertTrue(count(courier, COBBLE) == 20, "without a cap the courier collects the cobblestone: it carries " + count(courier, COBBLE));
        }
        helper.succeed();
    }

    private static long allocated()
    {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    /**
     * A resolver's canResolveRequest runs for every request and every resolver; with the network on the delivery and pickup resolvers must
     * stay cheap (no rack walk, no snapshot rebuild per call), in the same tick and from one tick to the next.
     */
    public static void resolverChecksAreCheap(final GameTestHelper helper)
    {
        RsFlags.overrideStorageNetwork(true);
        final Net n = Net.found(helper, "storage-cheap", 12, 12);
        n.stockA(0, new ItemStack(Items.OAK_LOG, 100));
        n.stockB(0, new ItemStack(Items.OAK_LOG, 100));
        n.quietCourier(n.a, new BlockPos(34, 1, 8));
        final IToken<?> token = ask(n.f.builder, 8);
        final List<Delivery> deliveries = deliveriesOf(n, token);
        helper.assertTrue(!deliveries.isEmpty(), "fixture: a delivery exists");
        final IRequest<?> delivery = n.f.request(n.f.request(token).getChildren().iterator().next());
        final var resolvers = n.a.getResolvers();
        final com.minecolonies.api.colony.requestsystem.resolver.IRequestResolver<?> deliveryResolver = resolvers.stream()
          .filter(r -> r instanceof com.minecolonies.core.colony.requestsystem.resolvers.DeliveryRequestResolver).findFirst().orElseThrow();
        @SuppressWarnings({"unchecked", "rawtypes"})
        final java.util.function.Supplier<Boolean> call = () -> ((com.minecolonies.api.colony.requestsystem.resolver.IRequestResolver) deliveryResolver).canResolveRequest(n.f.manager(), delivery);

        for (int i = 0; i < 50; i++)
        {
            call.get();   // warm up
        }
        final long before = allocated();
        for (int i = 0; i < 200; i++)
        {
            call.get();
        }
        final long perCall = (allocated() - before) / 200;
        helper.assertTrue(perCall < 2048, "same tick: a canResolveRequest allocates " + perCall + " bytes");

        final long[] total = {0};
        final int[] ticks = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            // Stock changes every tick, as in a busy colony: the first question of the tick finds racks to look at again.
            n.f.rack(ticks[0] % 12).getInventory().insertItem(1, new ItemStack(Items.DIRT, 1), false);
            n.rackB(ticks[0] % 12).getInventory().insertItem(1, new ItemStack(Items.DIRT, 1), false);
            final long b = allocated();
            call.get();
            total[0] += allocated() - b;
            if (++ticks[0] < 30)
            {
                helper.runAfterDelay(1, () -> step[0].run());
                return;
            }
            final long perTick = total[0] / ticks[0];
            org.slf4j.LoggerFactory.getLogger("storage").info("STORAGE_CHEAP first call per tick with churn: {} bytes, same tick: {} bytes", perTick, perCall);
            helper.assertTrue(perTick < 24 * 1024, "first call of a tick: " + perTick + " bytes");
            helper.succeed();
        };
        helper.runAfterDelay(1, () -> step[0].run());
    }
}
