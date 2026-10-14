package com.minecolonies.api.colony.labour;

import com.minecolonies.api.colony.IColony;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The vetoes on what labourers may dig and fell.
 */
public final class LabourExclusions
{
    private static final List<ILabourExclusion> EXCLUSIONS = new CopyOnWriteArrayList<>();

    private LabourExclusions()
    {
    }

    /**
     * @param exclusion the veto to add.
     */
    public static void register(final ILabourExclusion exclusion)
    {
        EXCLUSIONS.add(exclusion);
    }

    /**
     * Only for tests.
     */
    public static void unregister(final ILabourExclusion exclusion)
    {
        EXCLUSIONS.remove(exclusion);
    }

    /**
     * @return whether any registered veto holds for the block.
     */
    public static boolean excluded(final IColony colony, final BlockPos pos)
    {
        for (final ILabourExclusion exclusion : EXCLUSIONS)
        {
            if (exclusion.excluded(colony, pos))
            {
                return true;
            }
        }
        return false;
    }
}
