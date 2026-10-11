package com.minecolonies.core.colony.requestsystem.management;

import com.minecolonies.api.colony.requestsystem.data.*;
import com.minecolonies.api.colony.requestsystem.management.*;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.core.colony.requestsystem.management.manager.StandardRequestManager;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import org.jetbrains.annotations.NotNull;

/**
 * Describes the {@link StandardRequestManager} data access. Is only used for internal handling.
 */
public interface IStandardRequestManager extends IRequestManager
{

    @NotNull
    IRequestIdentitiesDataStore getRequestIdentitiesDataStore();

    @NotNull
    IRequestResolverIdentitiesDataStore getRequestResolverIdentitiesDataStore();

    @NotNull
    IProviderResolverAssignmentDataStore getProviderResolverAssignmentDataStore();

    @NotNull
    IRequestResolverRequestAssignmentDataStore getRequestResolverRequestAssignmentDataStore();

    @NotNull
    IRequestableTypeRequestResolverAssignmentDataStore getRequestableTypeRequestResolverAssignmentDataStore();

    /**
     * RS1: the colony's reservation ledger. Always present; whether it is used is decided by the {@code reservations} flag.
     *
     * @return the ledger.
     */
    @NotNull
    ReservationLedger getReservationLedger();

    IProviderHandler getProviderHandler();

    IRequestHandler getRequestHandler();

    IResolverHandler getResolverHandler();

    ITokenHandler getTokenHandler();

    IUpdateHandler getUpdateHandler();

    int getCurrentVersion();

    void setCurrentVersion(int currentVersion);
}
