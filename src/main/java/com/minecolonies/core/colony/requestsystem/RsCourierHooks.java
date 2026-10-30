package com.minecolonies.core.colony.requestsystem;

import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
import com.minecolonies.core.colony.storage.ColonyStorage;
import com.minecolonies.core.colony.storage.StorageNetwork;
import net.minecraft.core.BlockPos;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationKind;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationReason;
import org.jetbrains.annotations.NotNull;

/**
 * The courier's half of the reserve-then-commit transaction (RS1): commit the stock claim when the items leave the source,
 * the space claim when they arrive. Every method is a no-op when reservations are off.
 */
public final class RsCourierHooks
{
    private RsCourierHooks()
    {
    }

    /**
     * The courier took the delivery's items out of the source rack.
     *
     * @param manager  the request manager.
     * @param delivery the delivery request.
     */
    public static void stockTaken(@NotNull final IRequestManager manager, @NotNull final IRequest<?> delivery)
    {
        final ReservationLedger ledger = RsAccess.ledger(manager);
        if (ledger != null)
        {
            ledger.commit(delivery.getId(), ReservationKind.STOCK, RsAccess.now(manager));
        }
    }

    /**
     * The courier put the delivery's items into the destination. The room claim becomes a physical change; the items now
     * sitting in the building are held for the request they were fetched for until its requester took them, so that
     * another request of the same building cannot be handed them.
     *
     * @param manager  the request manager.
     * @param delivery the delivery request that arrived in full.
     */
    public static void arrived(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends Delivery> delivery)
    {
        if (StorageNetwork.on(manager) && manager.getColony() instanceof final Colony colony)
        {
            final ColonyStorage storage = ColonyStorage.of(colony);
            final BlockPos source = storage.owningWarehouse(delivery.getRequest().getStart().getInDimensionLocation());
            storage.recordFlow(source == null ? delivery.getRequest().getStart().getInDimensionLocation() : source,
              delivery.getRequest().getTarget().getInDimensionLocation(), delivery.getRequest().getStack());
        }
        RsStats.itemsDelivered += delivery.getRequest().getStack().getCount();
        RsStats.deliveriesArrived++;
        if (delivery.hasParent())
        {
            forgetCredit(manager, delivery.getParent());
        }
        final ReservationLedger ledger = RsAccess.ledger(manager);
        if (ledger == null)
        {
            return;
        }
        final long now = RsAccess.now(manager);
        ledger.commit(delivery.getId(), ReservationKind.SPACE, now);
        if (!delivery.hasParent() || !(manager.getColony() instanceof final Colony colony))
        {
            return;
        }
        final IRequest<?> parent = manager.getRequestForToken(delivery.getParent());
        final IBuilding destination = colony.getServerBuildingManager().getBuilding(delivery.getRequest().getTarget().getInDimensionLocation());
        if (parent == null || destination == null || destination instanceof BuildingWareHouse)
        {
            return;
        }
        String name;
        try
        {
            name = parent.getRequester().getRequesterDisplayName(manager, parent).getString();
        }
        catch (final RuntimeException e)
        {
            name = "?";
        }
        ledger.reserveStock(destination.getID(), null, new ItemStorage(delivery.getRequest().getStack()), delivery.getRequest().getStack().getCount(), parent.getId(), name,
          ReservationReason.BUILDING_HANDOUT, now);
    }

    /**
     * Part of a delivery went into the destination (a short load): the items are there and count as moved, but the delivery is not done. It fails
     * and goes back to the request system, which asks again for what is missing; no space is committed and nothing is held for the requester.
     *
     * @param manager  the request manager.
     * @param delivery the delivery request.
     * @param count    how many of its items went in.
     */
    public static void arrivedPartly(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends Delivery> delivery, final int count)
    {
        if (StorageNetwork.on(manager) && manager.getColony() instanceof final Colony colony)
        {
            final ColonyStorage storage = ColonyStorage.of(colony);
            final BlockPos source = storage.owningWarehouse(delivery.getRequest().getStart().getInDimensionLocation());
            storage.recordFlow(source == null ? delivery.getRequest().getStart().getInDimensionLocation() : source,
              delivery.getRequest().getTarget().getInDimensionLocation(), delivery.getRequest().getStack().copyWithCount(count));
        }
        RsStats.itemsDelivered += count;
        RsStats.deliveriesShort++;
        if (delivery.hasParent())
        {
            // the parent asks again only for what is still missing (r1 Fable F17, Sol M1: run 014 delivered 24 logs for a request of 16)
            credit(manager, delivery.getParent(), count);
        }
    }

    /**
     * What arrived of a request in short loads (its deliveries failed short and it was handed out again), by world and dimension
     * ({@link RsWorldScope}) and the token of the request. Held in memory only: after a reload the rest is asked for in full again (the old behaviour).
     */
    private record CreditKey(String world, IToken<?> request)
    {
    }

    private static final java.util.Map<CreditKey, Integer> CREDIT = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Part of a request arrived in a short load.
     *
     * @param manager the request manager of the request.
     * @param request the token of the request (the parent of the short delivery).
     * @param count   how many of its items arrived.
     */
    public static void credit(@NotNull final IRequestManager manager, @NotNull final IToken<?> request, final int count)
    {
        credit(RsWorldScope.of(manager), request, count);
    }

    /**
     * Part of a request arrived in a short load (pure: the world key is given).
     *
     * @param world   the world and dimension key ({@link RsWorldScope}).
     * @param request the token of the request.
     * @param count   how many of its items arrived.
     */
    public static void credit(@NotNull final String world, @NotNull final IToken<?> request, final int count)
    {
        if (count <= 0)
        {
            return;
        }
        if (CREDIT.size() > 4096)
        {
            // requests that were cancelled or finished another way: forget the oldest guesses rather than grow for ever
            CREDIT.clear();
        }
        CREDIT.merge(new CreditKey(world, request), count, Integer::sum);
    }

    /**
     * @param manager the request manager of the request.
     * @param request a request for items.
     * @return how many items of it are still missing: what it asks for, minus what arrived of it in short loads (at least 1 while it is open).
     */
    public static int outstanding(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends IDeliverable> request)
    {
        final int count = request.getRequest().getCount();
        final Integer arrived = CREDIT.get(new CreditKey(RsWorldScope.of(manager), request.getId()));
        return arrived == null ? count : Math.max(1, count - arrived);
    }

    /**
     * @param manager the request manager of the request.
     * @param request a request for items.
     * @return the least that is worth a delivery: the minimum of the request, but no more than is still missing.
     */
    public static int outstandingMinimum(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends IDeliverable> request)
    {
        return Math.min(request.getRequest().getMinimumCount(), outstanding(manager, request));
    }

    /**
     * The request is done (its rest arrived in full, or it is gone): its short-load count is forgotten.
     *
     * @param manager the request manager of the request.
     * @param request the token of the request.
     */
    public static void forgetCredit(@NotNull final IRequestManager manager, @NotNull final IToken<?> request)
    {
        CREDIT.remove(new CreditKey(RsWorldScope.of(manager), request));
    }

    /**
     * @return what arrived of the request in short loads (for the tests).
     */
    public static int creditOf(@NotNull final IRequestManager manager, @NotNull final IToken<?> request)
    {
        return creditOf(RsWorldScope.of(manager), request);
    }

    /**
     * @return what arrived of the request in short loads in the given world and dimension (for the tests).
     */
    public static int creditOf(@NotNull final String world, @NotNull final IToken<?> request)
    {
        return CREDIT.getOrDefault(new CreditKey(world, request), 0);
    }

    /**
     * Forget the short-load count of a request in the given world and dimension (for the tests).
     */
    public static void forgetCredit(@NotNull final String world, @NotNull final IToken<?> request)
    {
        CREDIT.remove(new CreditKey(world, request));
    }

    /**
     * Forget all short-load credit (a server starts or stops).
     */
    static void clearCredit()
    {
        CREDIT.clear();
    }
}
