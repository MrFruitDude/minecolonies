package com.minecolonies.core.colony.labour;

import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.requestsystem.token.StandardToken;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One labourer's claim on a unit of work: a lot of a good for a request, a stock goal, the idle builder's own build, or a manager's directed order. Held by the colony's {@link LabourBoard}
 * (not saved). It has a lease that every finished action renews; a claim whose lease runs out is given back.
 */
public final class LabourClaim
{
    /**
     * What the goods are for.
     */
    public enum Source
    {
        /**
         * A request that no building resolver serves.
         */
        REQUEST,
        /**
         * A stock goal of the warehouse.
         */
        STOCKPILE,
        /**
         * A request of the idle builder's own hut.
         */
        SELF,
        /**
         * A manager's directed gathering order ({@link DirectedOrder}).
         */
        DIRECTED
    }

    private final IToken<?> token = new StandardToken(UUID.randomUUID());

    private final Source source;
    @Nullable
    private final IToken<?> request;
    @Nullable
    private final String    goalId;
    private final int       citizenId;
    private final Item      target;
    private final int       amount;
    private final BlockPos  destination;
    private final long      created;
    private final int       orderId;
    private       long      leaseUntil;

    LabourClaim(final Source source, @Nullable final IToken<?> request, @Nullable final String goalId, final int citizenId, final Item target, final int amount,
      final BlockPos destination, final long now, final long leaseUntil)
    {
        this(source, request, goalId, citizenId, target, amount, destination, now, leaseUntil, 0);
    }

    LabourClaim(final Source source, @Nullable final IToken<?> request, @Nullable final String goalId, final int citizenId, final Item target, final int amount,
      final BlockPos destination, final long now, final long leaseUntil, final int orderId)
    {
        this.orderId = orderId;
        this.source = source;
        this.request = request;
        this.goalId = goalId;
        this.citizenId = citizenId;
        this.target = target;
        this.amount = amount;
        this.destination = destination.immutable();
        this.created = now;
        this.leaseUntil = leaseUntil;
    }

    /**
     * @return the token the claim's ledger entries are held under.
     */
    public IToken<?> token()
    {
        return token;
    }

    public Source source()
    {
        return source;
    }

    /**
     * @return the request served, null for a stock goal.
     */
    @Nullable
    public IToken<?> request()
    {
        return request;
    }

    /**
     * @return the stock goal served, null for a request.
     */
    @Nullable
    public String goalId()
    {
        return goalId;
    }

    public int citizenId()
    {
        return citizenId;
    }

    /**
     * @return the good to bring.
     */
    public Item target()
    {
        return target;
    }

    /**
     * @return how many (the lot).
     */
    public int amount()
    {
        return amount;
    }

    /**
     * @return the hut that asked, or the warehouse a stock goal fills (the nearest one is chosen on arrival).
     */
    public BlockPos destination()
    {
        return destination;
    }

    public long created()
    {
        return created;
    }

    /**
     * @return the directed order served, 0 for any other source.
     */
    public int orderId()
    {
        return orderId;
    }

    public long leaseUntil()
    {
        return leaseUntil;
    }

    void renew(final long until)
    {
        leaseUntil = until;
    }
}
