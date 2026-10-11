package com.minecolonies.core.colony.requestsystem.reservation;

/**
 * Why a reservation was made. Every write to the ledger carries one, so "who took the iron?" has an answer.
 */
public enum ReservationReason
{
    /**
     * A warehouse promised stock to a requester; a courier carries it.
     */
    WAREHOUSE_DELIVERY,
    /**
     * A building resolved a request from its own inventory; the requester has not taken the items yet.
     */
    BUILDING_HANDOUT,
    /**
     * Inputs of a queued craft.
     */
    CRAFTING_INPUT,
    /**
     * A builder who helps another builder with an order has planned positions and will take their materials out of the
     * lead's hut. The claim has no request behind it: it is held for the helper until he took the items or gave the
     * positions back, and ages out when the helper vanishes.
     */
    BUILD_ASSIST,
    /**
     * Anything else (tests, external callers).
     */
    OTHER
}
