package com.minecolonies.core.colony.requestsystem;

import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
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
}
