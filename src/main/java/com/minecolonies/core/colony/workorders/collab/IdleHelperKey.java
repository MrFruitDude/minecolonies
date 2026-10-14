package com.minecolonies.core.colony.workorders.collab;

import net.minecraft.core.BlockPos;

/**
 * How an unemployed citizen who helps a builder is named in the shared state of an order ({@link WorkOrderCollab}): a helper is known there by the position of his
 * hut (leases, helper lists, territories and the reservation ledger are all keyed by it), and a citizen without a job has none. His key is a position that no hut can
 * have, outside of the world border: x and y are fixed, z is the citizen's id.
 */
public final class IdleHelperKey
{
    /**
     * Outside of the world border (30 million), inside of what a block position holds (33.5 million).
     */
    private static final int X = -33_000_000;

    /**
     * Below any world.
     */
    private static final int Y = -2_000;

    private IdleHelperKey()
    {
    }

    /**
     * @param citizenId the id of an unemployed citizen of the colony.
     * @return his key.
     */
    public static BlockPos of(final int citizenId)
    {
        return new BlockPos(X, Y, citizenId);
    }

    /**
     * @param key a helper's key.
     * @return whether it is the key of an unemployed citizen (and not the position of a builder's hut).
     */
    public static boolean is(final BlockPos key)
    {
        return key.getX() == X && key.getY() == Y;
    }

    /**
     * @param key the key of an unemployed citizen.
     * @return his id.
     */
    public static int citizenId(final BlockPos key)
    {
        return key.getZ();
    }
}
