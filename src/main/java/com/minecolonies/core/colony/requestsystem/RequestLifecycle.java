package com.minecolonies.core.colony.requestsystem;

import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.requestsystem.management.IStandardRequestManager;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import org.jetbrains.annotations.Nullable;

/**
 * What the redesigned request system does when any request changes state: end the promises it held. One entry point, called from {@code AbstractRequest#setState}, so no transition is missed.
 * Nothing here may break a state change: failures are logged and swallowed.
 */
public final class RequestLifecycle
{
    private RequestLifecycle()
    {
    }

    /**
     * @param manager  the manager the state was set through, possibly a wrapped view.
     * @param request  the request.
     * @param previous its state before.
     */
    public static void onStateSet(final IRequestManager manager, final IRequest<?> request, @Nullable final RequestState previous)
    {
        final IStandardRequestManager standard = RsAccess.standard(manager);
        if (standard == null)
        {
            return;
        }
        try
        {
            final RequestState state = request.getState();
            final ReservationLedger ledger = standard.getReservationLedger();
            if (!ledger.isEmpty())
            {
                switch (state)
                {
                    // A delivery's promises end with it. Items handed out to a requester stay held until it received them.
                    case COMPLETED -> ledger.release(request.getId(), RsAccess.now(standard), false);
                    case RECEIVED, CANCELLED, FAILED, OVERRULED -> ledger.release(request.getId(), RsAccess.now(standard), true);
                    default ->
                    {
                    }
                }
            }
        }
        catch (final RuntimeException e)
        {
            Log.getLogger().warn("Request lifecycle hook failed for " + request.getId(), e);
        }
    }
}
