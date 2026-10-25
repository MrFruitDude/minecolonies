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
    OTHER,
    /**
     * An add-on's make order holds the recipe inputs it still needs. Like {@link #LABOUR} the claim has no request behind it: the add-on holds it under its own
     * token (see {@link ReservationLedger#setStock}) and ends it, or {@link ReservationLedger#sweepStale} drops it when the add-on no longer knows the token.
     * Added after {@link #OTHER} so the ordinals of the older reasons stay the same.
     */
    MAKE,
    /**
     * An add-on's claimed build (a placed plan, a builder's work order) holds the materials it still needs; no request behind it, as {@link #MAKE}.
     */
    BUILD;

    /**
     * @return true when claims of this reason have no request behind them: the request sweep leaves them alone and their holder (or a stale sweep) ends them.
     */
    public boolean withoutRequest()
    {
        return this == BUILD_ASSIST || this == LABOUR || this == MAKE || this == BUILD;
    }
}
