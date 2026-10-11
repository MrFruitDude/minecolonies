package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.requestsystem.RsFlags;
import com.minecolonies.core.colony.requestsystem.management.manager.StandardRequestManager;
import com.minecolonies.core.colony.requestsystem.reservation.LedgerEvent;
import com.minecolonies.core.colony.requestsystem.reservation.Reservation;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationKind;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationReason;
import com.minecolonies.core.entity.ai.workers.service.EntityAIWorkDeliveryman;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * RS1: the reservation ledger (glob {@code minecolonies:rs_*}). Every flag a test switches is reset by the colony isolation
 * environment around each test.
 */
public final class RequestReservationGameTests
{
    private static final Predicate<ItemStack> LOGS = stack -> stack.is(Items.OAK_LOG);

    private RequestReservationGameTests()
    {
    }

    private static IToken<?> ask(final IBuilding requester, final int logs)
    {
        return requester.createRequest(new Stack(new ItemStack(Items.OAK_LOG, logs), logs, logs), false);
    }

    // ------------------------------------------------------------------ the ledger on its own

    /**
     * The ledger's rules in the real game environment (the plain-JVM tests cannot build item stacks).
     */
    public static void ledgerAlgebra(final GameTestHelper helper)
    {
        final BlockPos warehouse = new BlockPos(10, 64, 10);
        final BlockPos rackA = new BlockPos(11, 64, 10);
        final BlockPos rackB = new BlockPos(12, 64, 10);
        final BlockPos hut = new BlockPos(30, 64, 10);
        final ItemStorage logs = new ItemStorage(new ItemStack(Items.OAK_LOG));
        final ItemStorage dirt = new ItemStorage(new ItemStack(Items.DIRT));
        final IToken<?> t1 = new com.minecolonies.api.colony.requestsystem.token.StandardToken();
        final IToken<?> t2 = new com.minecolonies.api.colony.requestsystem.token.StandardToken();
        final IToken<?> t3 = new com.minecolonies.api.colony.requestsystem.token.StandardToken();

        final ReservationLedger ledger = new ReservationLedger();
        final List<Reservation> freed = new ArrayList<>();
        ledger.setOnFreed(freed::add);

        helper.assertTrue(ledger.reserveStock(warehouse, rackA, logs, 8, t1, "Builder", ReservationReason.WAREHOUSE_DELIVERY, 1L) == 8, "reserve returns the amount");
        helper.assertTrue(ledger.available(warehouse, logs, 10) == 2, "10 logs with 8 promised leave 2: " + ledger.available(warehouse, logs, 10));
        helper.assertTrue(ledger.available(warehouse, dirt, 10) == 10, "another item is unaffected");
        helper.assertTrue(ledger.available(hut, logs, 10) == 10, "another building is unaffected");
        helper.assertTrue(ledger.available(warehouse, logs, 5) == 0, "available never goes below zero");
        helper.assertTrue(ledger.reservedInContainer(rackA, logs) == 8 && ledger.reservedInContainer(rackB, logs) == 0, "claims are per rack");

        // Same token, same slot: merged.
        ledger.reserveStock(warehouse, rackA, logs, 2, t1, "Builder", ReservationReason.WAREHOUSE_DELIVERY, 2L);
        helper.assertTrue(ledger.reservationsOf(t1).size() == 1 && ledger.reservationsOf(t1).get(0).amount() == 10, "a second claim on the same slot merges");

        // Another request's claim.
        ledger.reserveStock(warehouse, rackB, dirt, 5, t2, "Sawmill", ReservationReason.WAREHOUSE_DELIVERY, 3L);
        helper.assertTrue(ledger.reservedStockExcluding(warehouse, stack -> true, t1) == 5, "the exclusion leaves out the asker's own claim");

        // Commit: stock leaves the source.
        ledger.reserveSpace(hut, logs, 10, t1, "Builder", ReservationReason.WAREHOUSE_DELIVERY, 4L);
        helper.assertTrue(ledger.commit(t1, ReservationKind.STOCK, 5L) == 10, "commit returns the stock claim");
        helper.assertTrue(ledger.reservationsOf(t1).size() == 1 && ledger.reservationsOf(t1).get(0).kind() == ReservationKind.SPACE, "only the space claim is left");
        helper.assertTrue(freed.isEmpty(), "a commit frees nothing");
        helper.assertTrue(ledger.commit(t1, ReservationKind.SPACE, 6L) == 10 && ledger.reservationsOf(t1).isEmpty(), "committing the space ends the claim");

        // Release.
        helper.assertTrue(ledger.release(t2, 7L) == 5 && freed.size() == 1, "release frees and tells the listener");
        helper.assertTrue(ledger.release(t2, 8L) == 0, "release twice is harmless");

        // Handouts survive a COMPLETED delivery but not RECEIVED.
        ledger.reserveStock(hut, null, logs, 4, t3, "Builder", ReservationReason.BUILDING_HANDOUT, 9L);
        helper.assertTrue(ledger.release(t3, 10L, false) == 0 && ledger.reservedStock(hut, stack -> true) == 4, "completed: handout stays");
        helper.assertTrue(ledger.release(t3, 11L, true) == 4 && ledger.isEmpty(), "received: handout ends");

        // Sweep and stale handouts.
        final IToken<?> alive = new com.minecolonies.api.colony.requestsystem.token.StandardToken();
        final IToken<?> gone = new com.minecolonies.api.colony.requestsystem.token.StandardToken();
        ledger.reserveStock(warehouse, rackA, logs, 4, alive, "x", ReservationReason.WAREHOUSE_DELIVERY, 20L);
        ledger.reserveStock(warehouse, rackA, logs, 4, gone, "x", ReservationReason.WAREHOUSE_DELIVERY, 20L);
        helper.assertTrue(ledger.sweep(token -> token.equals(alive), 21L) == 1 && ledger.tokenCount() == 1, "the sweep drops the claim without a request");
        final IToken<?> handout = new com.minecolonies.api.colony.requestsystem.token.StandardToken();
        ledger.reserveStock(hut, null, logs, 1, handout, "x", ReservationReason.BUILDING_HANDOUT, 100L);
        helper.assertTrue(ledger.sweepStaleHandouts(200L, 1000L) == 0, "a young handout is kept");
        helper.assertTrue(ledger.sweepStaleHandouts(5000L, 1000L) == 1 && ledger.tokenCount() == 1, "an old one is dropped, the delivery claim is not");

        // Who reserved, who took.
        final List<LedgerEvent> events = ledger.history(LOGS);
        helper.assertTrue(events.stream().anyMatch(e -> e.op() == LedgerEvent.Op.RESERVE && e.requester().equals("Builder")), "history names who reserved");
        helper.assertTrue(events.stream().anyMatch(e -> e.op() == LedgerEvent.Op.COMMIT && e.amount() == 10), "history names what was taken");
        helper.assertTrue(events.stream().anyMatch(e -> e.op() == LedgerEvent.Op.ORPHAN), "history shows the sweep");
        helper.assertTrue(ledger.whoHolds(LOGS).stream().anyMatch(r -> r.requester().equals("x")), "whoHolds lists the open claims");

        // The history stays bounded.
        for (int i = 0; i < ReservationLedger.HISTORY_SIZE * 2; i++)
        {
            final IToken<?> t = new com.minecolonies.api.colony.requestsystem.token.StandardToken();
            ledger.reserveStock(warehouse, rackA, logs, 1, t, "y", ReservationReason.OTHER, i);
            ledger.release(t, i);
        }
        helper.assertTrue(ledger.history(stack -> true).size() == ReservationLedger.HISTORY_SIZE, "the history is bounded");
        helper.succeed();
    }

    // ------------------------------------------------------------------ two requests, one stock

    /**
     * Two requests for 8 logs, 10 in the warehouse: the first holds 8, the second waits (no promise of stock that is not
     * there, so no pickup failure later).
     */
    public static void competingRequests(final GameTestHelper helper)
    {
        RsFlags.overrideReservations(true);
        final RsFixture f = RsFixture.found(helper, "rs-compete", 4);
        final IBuilding second = f.place(ModBlocks.blockHutBuilder, new BlockPos(24, 1, 2), "fundamentals/builder1.blueprint");
        f.stock(0, new ItemStack(Items.OAK_LOG, 10));

        final IToken<?> first = ask(f.builder, 8);
        final IToken<?> other = ask(second, 8);
        final ReservationLedger ledger = f.ledger();

        final IRequest<?> firstRequest = f.request(first);
        final IRequest<?> otherRequest = f.request(other);
        helper.assertTrue(firstRequest.getState() == RequestState.FOLLOWUP_IN_PROGRESS && firstRequest.getChildren().size() == 1,
          "the first request is served by one delivery: " + firstRequest.getState() + " children=" + firstRequest.getChildren().size());
        helper.assertTrue(f.ledger().reservedStock(f.warehouse.getID(), LOGS) == 8, "8 logs are reserved at the warehouse: " + ledger.all());
        helper.assertTrue(ledger.available(f.warehouse.getID(), new ItemStorage(new ItemStack(Items.OAK_LOG)), f.physical(LOGS)) == 2, "2 logs are available");
        helper.assertTrue(ledger.reservedSpace(f.builder.getID(), LOGS) == 8, "8 spaces are reserved at the first builder: " + ledger.all());

        helper.assertTrue(!otherRequest.hasChildren() && f.isRetrying(other), "the second request waits at the retrying resolver, no delivery: state=" + otherRequest.getState()
                                                                                + " children=" + otherRequest.getChildren().size());
        helper.assertTrue(ledger.reservedSpace(second.getID(), LOGS) == 0, "nothing is reserved for the second request");
        helper.assertTrue(otherRequest.getState() != RequestState.FAILED && firstRequest.getState() != RequestState.FAILED, "nothing failed");
        helper.succeed();
    }

    /**
     * The same two requests with the flag off: both are promised the same stock (the old behaviour).
     */
    public static void competingRequestsFlagOff(final GameTestHelper helper)
    {
        RsFlags.overrideReservations(false);
        final RsFixture f = RsFixture.found(helper, "rs-compete-off", 4);
        final IBuilding second = f.place(ModBlocks.blockHutBuilder, new BlockPos(24, 1, 2), "fundamentals/builder1.blueprint");
        f.stock(0, new ItemStack(Items.OAK_LOG, 10));

        final IToken<?> first = ask(f.builder, 8);
        final IToken<?> other = ask(second, 8);

        final IRequest<?> firstRequest = f.request(first);
        final IRequest<?> otherRequest = f.request(other);
        helper.assertTrue(f.ledger().isEmpty(), "the ledger is not used: " + f.ledger().all());
        helper.assertTrue(firstRequest.getState() == RequestState.FOLLOWUP_IN_PROGRESS && otherRequest.getState() == RequestState.FOLLOWUP_IN_PROGRESS,
          "both are resolved from the same 10 logs, as before: " + firstRequest.getState() + " / " + otherRequest.getState());
        final int promised = deliveredTo(f, firstRequest) + deliveredTo(f, otherRequest);
        helper.assertTrue(promised == 16, "16 logs promised out of 10: " + promised);
        helper.succeed();
    }

    private static int deliveredTo(final RsFixture f, final IRequest<?> parent)
    {
        int total = 0;
        for (final IToken<?> child : parent.getChildren())
        {
            final IRequest<?> delivery = f.request(child);
            if (delivery != null && delivery.getRequest() instanceof Delivery d)
            {
                total += d.getStack().getCount();
            }
        }
        return total;
    }

    /**
     * Cancelling the first request frees its promise, and the waiting request is served from it.
     */
    public static void cancelReleases(final GameTestHelper helper)
    {
        RsFlags.overrideReservations(true);
        final RsFixture f = RsFixture.found(helper, "rs-cancel", 4);
        final IBuilding second = f.place(ModBlocks.blockHutBuilder, new BlockPos(24, 1, 2), "fundamentals/builder1.blueprint");
        f.stock(0, new ItemStack(Items.OAK_LOG, 10));
        final IToken<?> first = ask(f.builder, 8);
        final IToken<?> other = ask(second, 8);
        helper.assertTrue(f.ledger().reservedStock(f.warehouse.getID(), LOGS) == 8 && f.isRetrying(other), "fixture: 8 reserved, the second waits");

        f.manager().updateRequestState(first, RequestState.CANCELLED);
        helper.assertTrue(f.ledger().isEmpty(), "cancelling released every claim of the first request: " + f.ledger().all());

        helper.succeedWhen(() -> {
            final IRequest<?> waiting = f.request(other);
            helper.assertTrue(waiting != null && waiting.getState() == RequestState.FOLLOWUP_IN_PROGRESS && waiting.getChildren().size() == 1,
              "the waiting request is served once the stock is free: " + (waiting == null ? "gone" : waiting.getState() + " children=" + waiting.getChildren().size()));
            helper.assertTrue(f.ledger().reservedStock(f.warehouse.getID(), LOGS) == 8, "its delivery holds the 8 logs now: " + f.ledger().all());
        });
    }

    // ------------------------------------------------------------------ persistence and healing

    /**
     * Reservations survive the colony being saved and loaded, and the load keeps exactly those whose request survived.
     */
    public static void saveLoadKeepsReservations(final GameTestHelper helper)
    {
        RsFlags.overrideReservations(true);
        final RsFixture f = RsFixture.found(helper, "rs-saveload", 4);
        f.stock(0, new ItemStack(Items.OAK_LOG, 10));
        final IToken<?> first = ask(f.builder, 8);
        final ReservationLedger ledger = f.ledger();
        helper.assertTrue(ledger.reservedStock(f.warehouse.getID(), LOGS) == 8, "fixture: 8 reserved");
        final List<Reservation> before = ledger.all();
        helper.assertTrue(before.size() == 2, "a stock claim and a space claim: " + before);

        // A claim with no request behind it must not survive the load.
        final IToken<?> orphan = new com.minecolonies.api.colony.requestsystem.token.StandardToken();
        ledger.reserveStock(f.warehouse.getID(), f.racks.get(0), new ItemStorage(new ItemStack(Items.OAK_LOG)), 1, orphan, "ghost", ReservationReason.OTHER, 0L);

        final var saved = ((Colony) f.colony).write(new net.minecraft.nbt.CompoundTag(), f.level.registryAccess());
        final Colony restored = Colony.loadColony(saved, f.level, f.level.registryAccess());
        helper.assertTrue(restored != null, "colony could not be loaded");
        final ReservationLedger after = ((StandardRequestManager) restored.getRequestManager()).getReservationLedger();

        helper.assertTrue(after.tokenCount() == 1, "only the claims of the surviving request are left: " + after.all());
        helper.assertTrue(after.all().size() == before.size(), "both claims came back: " + after.all());
        for (final Reservation original : before)
        {
            final boolean found = after.all().stream().anyMatch(r -> r.token().equals(original.token()) && r.kind() == original.kind() && r.amount() == original.amount()
                                                                      && r.scope().equals(original.scope()) && java.util.Objects.equals(r.container(), original.container())
                                                                      && r.key().equals(original.key()) && r.requester().equals(original.requester())
                                                                      && r.reason() == original.reason());
            helper.assertTrue(found, "claim lost or changed by the save/load: " + original + " -> " + after.all());
        }
        helper.assertTrue(after.reservedStock(f.warehouse.getID(), LOGS) == 8, "still 8 reserved after the load");
        helper.succeed();
    }

    /**
     * The sweep drops promises without a live request, and keeps the others.
     */
    public static void sweepDropsOrphans(final GameTestHelper helper)
    {
        RsFlags.overrideReservations(true);
        final RsFixture f = RsFixture.found(helper, "rs-sweep", 4);
        f.stock(0, new ItemStack(Items.OAK_LOG, 10));
        final IToken<?> first = ask(f.builder, 8);
        final ReservationLedger ledger = f.ledger();
        final IToken<?> orphan = new com.minecolonies.api.colony.requestsystem.token.StandardToken();
        ledger.reserveStock(f.warehouse.getID(), f.racks.get(0), new ItemStorage(new ItemStack(Items.OAK_LOG)), 2, orphan, "ghost", ReservationReason.OTHER, 0L);
        helper.assertTrue(ledger.reservedStock(f.warehouse.getID(), LOGS) == 10, "fixture: 8 + 2 reserved");
        helper.assertTrue(f.physical(LOGS) == 10 && ledger.available(f.warehouse.getID(), new ItemStorage(new ItemStack(Items.OAK_LOG)), 10) == 0,
          "the orphan blocks the last 2 logs");

        final int dropped = ((StandardRequestManager) f.manager()).sweepReservations();
        helper.assertTrue(dropped == 1, "exactly the orphan was dropped: " + dropped);
        helper.assertTrue(ledger.reservedStock(f.warehouse.getID(), LOGS) == 8, "the real promise stays");
        helper.assertTrue(ledger.history(LOGS).stream().anyMatch(e -> e.op() == LedgerEvent.Op.ORPHAN && e.requester().equals("ghost")), "the history says who lost it");
        helper.succeed();
    }

    // ------------------------------------------------------------------ the courier commits

    private static Object invoke(final Object target, final String name) throws ReflectiveOperationException
    {
        final Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    /**
     * A courier takes exactly the reserved stack from its rack (the stock claim is committed), delivers it (the space claim
     * is committed), and the items are then held for the request they were fetched for until it is received.
     */
    public static void courierCommits(final GameTestHelper helper)
    {
        RsFlags.overrideReservations(true);
        final RsFixture f = RsFixture.found(helper, "rs-courier-commit", 4);
        final IBuilding deliveryman = f.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");

        helper.runAfterDelay(200, () -> {
            final ICitizenData courier = f.spawn(new BlockPos(34, 1, 8), true);
            final ICitizenData worker = f.spawn(new BlockPos(12, 1, 8), true);
            helper.assertTrue(deliveryman.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier), "courier not assigned to the courier hut");
            helper.assertTrue(f.warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "courier not assigned to the warehouse");
            helper.assertTrue(f.builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(worker), "worker not assigned");

            f.stock(0, new ItemStack(Items.OAK_LOG, 10));
            // A request of the worker (not of the building): the building's own requests are received at once.
            final IToken<?> parentToken = ((AbstractBuilding) f.builder).createRequest(worker, new Stack(new ItemStack(Items.OAK_LOG, 8), 8, 8), false);
            final IRequest<?> parent = f.request(parentToken);
            helper.assertTrue(parent.getChildren().size() == 1, "one delivery: " + parent.getChildren());
            final IToken<?> deliveryToken = parent.getChildren().iterator().next();
            final ReservationLedger ledger = f.ledger();
            helper.assertTrue(ledger.reservationsOf(deliveryToken).size() == 2, "the delivery holds stock and space: " + ledger.reservationsOf(deliveryToken));

            final JobDeliveryman job = courier.getJob(JobDeliveryman.class);
            final EntityAIWorkDeliveryman ai = job.generateAI();
            final IRequest<?> task = job.getCurrentTask();
            helper.assertTrue(task != null && task.getId().equals(deliveryToken), "the courier took the delivery: " + task);

            try
            {
                // At the rack: pick up.
                final BlockPos rack = f.racks.get(0);
                courier.getEntity().get().setPos(rack.getX() + 0.5D, rack.getY(), rack.getZ() + 0.5D);
                Object state = invoke(ai, "prepareDelivery");
                Log.getLogger().info("RS courier commit: prepareDelivery -> {}", state);
                helper.assertTrue(ledger.reservationsOf(deliveryToken).size() == 1 && ledger.reservationsOf(deliveryToken).get(0).kind() == ReservationKind.SPACE,
                  "picking up committed the stock claim, the space claim is still open: " + ledger.reservationsOf(deliveryToken));
                helper.assertTrue(f.physical(LOGS) == 2, "the courier took exactly the 8 reserved logs from the rack: " + f.physical(LOGS) + " left");
                helper.assertTrue(InventoryUtils.getItemCountInItemHandler(courier.getInventory(), LOGS) == 8, "the courier carries 8 logs");
                invoke(ai, "prepareDelivery");

                // At the builder: deliver.
                courier.getEntity().get().setPos(f.builder.getID().getX() + 0.5D, f.builder.getID().getY(), f.builder.getID().getZ() + 0.5D);
                state = invoke(ai, "deliver");
                Log.getLogger().info("RS courier commit: deliver -> {}", state);
            }
            catch (final ReflectiveOperationException e)
            {
                helper.fail("could not drive the courier: " + e + " / " + e.getCause());
                return;
            }

            helper.assertTrue(ledger.reservationsOf(deliveryToken).isEmpty(), "delivering committed the space claim: " + ledger.reservationsOf(deliveryToken));
            helper.assertTrue(InventoryUtils.getCountFromBuilding(f.builder, LOGS) == 8, "the builder hut holds the logs");
            helper.assertTrue(ledger.reservedStock(f.builder.getID(), LOGS) == 8 && ledger.reservationsOf(parentToken).size() == 1,
              "the logs in the hut are held for the request they came for: " + ledger.all());
            helper.assertTrue(ledger.reservationsOf(parentToken).get(0).reason() == ReservationReason.BUILDING_HANDOUT, "as a handout");

            // The requester takes them.
            f.manager().updateRequestState(parentToken, RequestState.RECEIVED);
            helper.assertTrue(ledger.isEmpty(), "received: nothing is held any more: " + ledger.all());
            final List<LedgerEvent> events = ledger.history(LOGS);
            helper.assertTrue(events.stream().anyMatch(e -> e.op() == LedgerEvent.Op.COMMIT && e.kind() == ReservationKind.STOCK && e.amount() == 8), "the history shows the pickup");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ the building resolver

    /**
     * Items a building hands out to one request are not handed out again to the next one.
     */
    public static void buildingHandoutHeld(final GameTestHelper helper)
    {
        RsFlags.overrideReservations(true);
        final RsFixture f = RsFixture.found(helper, "rs-handout", 2);
        helper.runAfterDelay(100, () -> {
            final ICitizenData worker = f.spawn(new BlockPos(12, 1, 8), true);
            helper.assertTrue(f.builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(worker), "worker not assigned");
            final ItemStack rest = InventoryUtils.forceItemStackToItemHandler(f.builder.getItemHandlerCap(), new ItemStack(Items.OAK_LOG, 6), stack -> true);
            helper.assertTrue(rest.isEmpty(), "could not stock the hut");

            final IToken<?> first = ((AbstractBuilding) f.builder).createRequest(worker, new Stack(new ItemStack(Items.OAK_LOG, 4), 4, 4), false);
            final IToken<?> second = ((AbstractBuilding) f.builder).createRequest(worker, new Stack(new ItemStack(Items.OAK_LOG, 4), 4, 4), false);
            final ReservationLedger ledger = f.ledger();

            final var served = ((AbstractBuilding) f.builder).getCompletedRequestsOfCitizenOrBuilding(worker).stream().filter(r -> r.getId().equals(first)).findFirst();
            helper.assertTrue(served.isPresent(), "the first request was served from the hut");
            // The building hands out whole stacks: that is the amount held.
            final int handedOut = served.get().getDeliveries().stream().mapToInt(ItemStack::getCount).sum();
            helper.assertTrue(handedOut >= 4, "it was handed at least what it asked for: " + handedOut);
            helper.assertTrue(ledger.reservedStock(f.builder.getID(), LOGS) == handedOut, "what it was handed is held for it: " + ledger.all());
            final IRequest<?> secondRequest = f.request(second);
            helper.assertTrue(secondRequest != null && secondRequest.getDeliveries().stream().mapToInt(ItemStack::getCount).sum() == 0,
              "the second request got none of the logs held for the first: " + secondRequest.getDeliveries() + " state=" + secondRequest.getState());

            // The first request is received: its logs are no one's.
            f.manager().updateRequestState(first, RequestState.RECEIVED);
            helper.assertTrue(ledger.reservedStock(f.builder.getID(), LOGS) == 0, "received: nothing held any more");
            helper.succeed();
        });
    }
}
