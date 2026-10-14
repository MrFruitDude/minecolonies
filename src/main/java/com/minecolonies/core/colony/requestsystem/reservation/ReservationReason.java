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
     * A labourer (an unemployed citizen, or an idle builder for his own build) plans to take stock out of a warehouse for a request or stockpile goal that no
     * building serves. Like {@link #BUILD_ASSIST} the claim has no request behind it: it is held under the claim's token until the labourer took the items or gave
     * the claim back, and the ledger drops it when no claim of that token lives any more.
     */
    LABOUR,
    /**
     * Anything else (tests, external callers).
     */
    OTHER
}
