package com.minecolonies.core.colony.workorders.collab;

import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.requestsystem.token.StandardToken;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.core.colony.requestsystem.RsAccess;
import com.minecolonies.core.colony.requestsystem.reservation.Reservation;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationReason;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Builder collaboration on the reservation ledger (RS1): the stock a helping builder has planned to take out of the hut of
 * the lead is claimed for him ({@link ReservationReason#BUILD_ASSIST}), so that it is not handed to anybody else meanwhile
 * (the lead's other requests, another helper) and so that he never takes what is claimed for somebody else.
 * <p>
 * The claim belongs to the helper's hut (one token per helper), not to a request. It ends when the helper took the items
 * ({@link #commit}), when he gave the positions back or left the order ({@link #releaseAll}), or, if nobody ends it, after a
 * while (the ledger's own sweep). Everything here does nothing when reservations are off.
 */
public final class AssistReservations
{
    private AssistReservations()
    {
    }

    /**
     * @param helperHut the hut of the helping builder.
     * @return the token the helper's claims are held under.
     */
    @NotNull
    public static IToken<?> token(@NotNull final BlockPos helperHut)
    {
        return new StandardToken(UUID.nameUUIDFromBytes(("builder-assist:" + helperHut.asLong()).getBytes(StandardCharsets.UTF_8)));
    }

    @Nullable
    private static ReservationLedger ledger(@NotNull final IBuilding hut)
    {
        return RsAccess.ledger(hut.getColony().getRequestManager());
    }

    private static Predicate<ItemStack> matching(final ItemStack kind)
    {
        return candidate -> ItemStackUtils.compareItemStacksIgnoreStackSize(kind, candidate);
    }

    /**
     * Whether the claim is held for a request of the hut itself. A hut that hands stock out to its own requests (the materials of
     * the order it leads) is not a rival of its helpers: they place positions of the same order.
     */
    private static boolean ownRequestOf(final IBuilding hut, final Reservation claim)
    {
        if (claim.reason() == ReservationReason.BUILD_ASSIST)
        {
            return false;
        }
        final IRequest<?> request = hut.getColony().getRequestManager().getRequestForToken(claim.token());
        return request != null && request.getRequester().getLocation().getInDimensionLocation().equals(hut.getID());
    }

    /**
     * @param hut     the hut the stock sits in.
     * @param kind    the item.
     * @param exclude a claim token whose claims do not count (the helper's own), or null.
     * @return how many of the item are claimed in the hut for anybody but the hut's own requests and the excluded token: the stock that
     *   a builder who takes over from the hut, or helps its lead, must leave.
     */
    public static int heldForOthers(@NotNull final IBuilding hut, @NotNull final ItemStack kind, @Nullable final IToken<?> exclude)
    {
        final ReservationLedger ledger = ledger(hut);
        if (ledger == null)
        {
            return 0;
        }
        return ledger.reservedStockWhere(hut.getID(), matching(kind), claim -> !claim.token().equals(exclude) && !ownRequestOf(hut, claim));
    }

    /**
     * @param leadHut the hut the stock sits in.
     * @param helper  the helper asking.
     * @param kind    the item.
     * @return how many of the item are claimed in the lead's hut by anybody but the helper and the lead's own requests.
     */
    public static int heldByOthers(@NotNull final IBuilding leadHut, @NotNull final IBuilding helper, @NotNull final ItemStack kind)
    {
        return heldForOthers(leadHut, kind, token(helper.getID()));
    }

    /**
     * @param hut  a hut.
     * @param kind the item.
     * @return how many of the item are claimed in the hut for anybody but the hut's own requests.
     */
    public static int held(@NotNull final IBuilding hut, @NotNull final ItemStack kind)
    {
        return heldForOthers(hut, kind, null);
    }

    /**
     * Claims stock in the lead's hut for the helper, on top of what he already holds.
     *
     * @param helper  the helper.
     * @param leadHut the hut of the lead.
     * @param kind    the item.
     * @param amount  how many.
     * @param now     the game time.
     * @return how many were claimed.
     */
    public static int reserve(@NotNull final IBuilding helper, @NotNull final IBuilding leadHut, @NotNull final ItemStack kind, final int amount, final long now)
    {
        final ReservationLedger ledger = ledger(leadHut);
        if (ledger == null || amount <= 0)
        {
            return 0;
        }
        return ledger.reserveStock(leadHut.getID(), null, new ItemStorage(kind.copyWithCount(1)), amount, token(helper.getID()), "builder assist @" + helper.getID().toShortString(),
          ReservationReason.BUILD_ASSIST, now);
    }

    /**
     * The helper took items out of the lead's hut: that much of his claim is a physical change now.
     *
     * @param helper  the helper.
     * @param leadHut the hut of the lead.
     * @param kind    the item.
     * @param amount  how many were taken.
     * @param now     the game time.
     */
    public static void commit(@NotNull final IBuilding helper, @NotNull final IBuilding leadHut, @NotNull final ItemStack kind, final int amount, final long now)
    {
        final ReservationLedger ledger = ledger(leadHut);
        if (ledger != null && amount > 0)
        {
            ledger.commitStock(token(helper.getID()), leadHut.getID(), new ItemStorage(kind.copyWithCount(1)), amount, now);
        }
    }

    /**
     * Ends everything the helper claims: he took what he needed, or gave the positions back. Stock that becomes available
     * wakes the requests that wait for it.
     *
     * @param helper the helper.
     * @param quiet  true when the helper claims again right away for the same items (nobody is told that stock became available).
     */
    public static void releaseAll(@NotNull final IBuilding helper, final boolean quiet)
    {
        final ReservationLedger ledger = ledger(helper);
        if (ledger == null)
        {
            return;
        }
        final long now = RsAccess.now(helper.getColony().getRequestManager());
        final IToken<?> token = token(helper.getID());
        if (quiet)
        {
            ledger.releaseQuiet(token, now);
        }
        else
        {
            ledger.release(token, now);
        }
    }
}
