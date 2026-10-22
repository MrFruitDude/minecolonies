package com.minecolonies.core.entity.ai.minimal;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Round 13 D7: one citizen's answer to "which hospital / restaurant is best for me", kept for {@link #TTL} ticks. The AI tasks asked the building manager for it
 * every second (sick citizens twice), which walks every building of the colony. A cached building that is gone, or no longer of the type, is searched again at
 * once. A cached "none" holds for the TTL too, so a new building is found within 30 seconds.
 */
public final class CachedBuildingLookup
{
    /**
     * How long one answer holds, in ticks (30 seconds).
     */
    public static final int TTL = 600;

    private final Class<? extends IBuilding> type;

    @Nullable
    private BlockPos cached;
    private long     validUntil = Long.MIN_VALUE;
    private int      searches   = 0;

    public CachedBuildingLookup(final Class<? extends IBuilding> type)
    {
        this.type = type;
    }

    /**
     * @param colony the colony of the citizen.
     * @param now    the game time.
     * @param search the full search, run when the answer is old or no longer valid.
     * @return the position of the best building, or null for none.
     */
    @Nullable
    public BlockPos get(final IColony colony, final long now, final Supplier<BlockPos> search)
    {
        if (now < validUntil && (cached == null || type.isInstance(colony.getServerBuildingManager().getBuilding(cached))))
        {
            return cached;
        }
        searches++;
        cached = search.get();
        validUntil = now + TTL;
        return cached;
    }

    /**
     * Forgets the answer, so the next call searches.
     */
    public void invalidate()
    {
        validUntil = Long.MIN_VALUE;
        cached = null;
    }

    /**
     * @return how many full searches ran (for tests).
     */
    public int searches()
    {
        return searches;
    }
}
