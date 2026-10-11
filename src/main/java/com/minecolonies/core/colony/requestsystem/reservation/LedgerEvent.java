package com.minecolonies.core.colony.requestsystem.reservation;

import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.crafting.ItemStorage;
import net.minecraft.core.BlockPos;

/**
 * One write to the ledger, kept in a bounded history so "who reserved, took or dropped X" can be answered.
 *
 * @param tick      the game time.
 * @param op        what happened.
 * @param kind      stock or space.
 * @param token     the request token the claim belongs to.
 * @param requester who asked.
 * @param reason    why.
 * @param scope     the building.
 * @param key       the item.
 * @param amount    how many.
 */
public record LedgerEvent(long tick, Op op, ReservationKind kind, IToken<?> token, String requester, ReservationReason reason, BlockPos scope, ItemStorage key, int amount)
{
    /**
     * What happened to a claim.
     */
    public enum Op
    {
        /** A claim was made. */
        RESERVE,
        /** The items left the source, or the room was filled: the claim became a physical change. */
        COMMIT,
        /** The claim ended without a physical change (cancelled, failed, finished). */
        RELEASE,
        /** The sweep found no request behind the claim and dropped it. */
        ORPHAN
    }
}
