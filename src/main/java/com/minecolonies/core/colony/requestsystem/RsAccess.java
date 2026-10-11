package com.minecolonies.core.colony.requestsystem;

import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.core.colony.requestsystem.management.IStandardRequestManager;
import com.minecolonies.core.colony.requestsystem.management.manager.wrapped.AbstractWrappedRequestManager;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import org.jetbrains.annotations.Nullable;

/**
 * Finds the colony-wide parts of the redesigned request system behind whatever manager a resolver was handed (the real
 * one, or one of the wrapped views used while assigning).
 */
public final class RsAccess
{
    private RsAccess()
    {
    }

    /**
     * @param manager a request manager, possibly wrapped.
     * @return the standard manager behind it, null for fakes.
     */
    @Nullable
    public static IStandardRequestManager standard(@Nullable final IRequestManager manager)
    {
        if (manager instanceof final IStandardRequestManager standard)
        {
            return standard;
        }
        if (manager instanceof final AbstractWrappedRequestManager wrapped)
        {
            return wrapped.getWrappedManager();
        }
        return null;
    }

    /**
     * @param manager a request manager, possibly wrapped.
     * @return the ledger when reservations are switched on, else null: callers fall back to the old behaviour.
     */
    @Nullable
    public static ReservationLedger ledger(@Nullable final IRequestManager manager)
    {
        if (!RsFlags.reservations())
        {
            return null;
        }
        final IStandardRequestManager standard = standard(manager);
        return standard == null ? null : standard.getReservationLedger();
    }

    /**
     * @param manager a request manager.
     * @return the game time of the manager's colony.
     */
    public static long now(final IRequestManager manager)
    {
        return manager.getColony().getWorld().getGameTime();
    }
}
