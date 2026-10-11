package com.minecolonies.core.colony.requestsystem.reservation;

import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.crafting.ItemStorage;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One open claim on stock at a source, or on room at a destination, held for a request token.
 */
public final class Reservation
{
    private final ReservationKind   kind;
    private final BlockPos          scope;
    @Nullable
    private final BlockPos          container;
    private final ItemStorage       key;
    private final IToken<?>         token;
    private final String            requester;
    private final ReservationReason reason;
    private final long              tick;
    private       int               amount;

    Reservation(
      final ReservationKind kind,
      final BlockPos scope,
      @Nullable final BlockPos container,
      final ItemStorage key,
      final int amount,
      final IToken<?> token,
      final String requester,
      final ReservationReason reason,
      final long tick)
    {
        this.kind = kind;
        this.scope = scope;
        this.container = container;
        this.key = key;
        this.amount = amount;
        this.token = token;
        this.requester = requester;
        this.reason = reason;
        this.tick = tick;
    }

    @NotNull
    public ReservationKind kind()
    {
        return kind;
    }

    /**
     * @return the building holding the stock (the warehouse) or the room (the destination).
     */
    @NotNull
    public BlockPos scope()
    {
        return scope;
    }

    /**
     * @return the exact container (rack) the stock sits in, null when the claim is not tied to one.
     */
    @Nullable
    public BlockPos container()
    {
        return container;
    }

    /**
     * @return the item, with components. The amount of the storage is not used, see {@link #amount()}.
     */
    @NotNull
    public ItemStorage key()
    {
        return key;
    }

    public int amount()
    {
        return amount;
    }

    void setAmount(final int amount)
    {
        this.amount = amount;
    }

    /**
     * @return the request token the claim belongs to. Ending that request releases the claim.
     */
    @NotNull
    public IToken<?> token()
    {
        return token;
    }

    /**
     * @return who asked, as text for the insight views.
     */
    @NotNull
    public String requester()
    {
        return requester;
    }

    @NotNull
    public ReservationReason reason()
    {
        return reason;
    }

    /**
     * @return the game time the claim was made.
     */
    public long tick()
    {
        return tick;
    }

    @Override
    public String toString()
    {
        return kind + " " + amount + "x " + key.getItemStack().getItem() + " @" + scope + (container == null ? "" : "/" + container) + " for " + token + " (" + requester + ", " + reason + ")";
    }
}
