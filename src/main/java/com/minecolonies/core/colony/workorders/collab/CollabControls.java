package com.minecolonies.core.colony.workorders.collab;

import com.minecolonies.api.colony.IColony;
import net.minecraft.core.BlockPos;

/**
 * Write side of the builder collaboration. (Stub: the tests come first.)
 */
public final class CollabControls
{
    public static final int LIMIT = 8;

    public enum Result
    {
        OK, DISABLED, NO_SUCH_ORDER, NOT_LED, NOT_A_BUILDER, BUSY, CANNOT_BUILD, STAGE_NOT_SHARED, NOT_AN_ASSISTANT, BAD_VALUE
    }

    private CollabControls()
    {
    }

    public static Result setMaxAssistants(final IColony colony, final int orderId, final int max)
    {
        return Result.DISABLED;
    }

    public static Result pin(final IColony colony, final int orderId, final BlockPos hut)
    {
        return Result.DISABLED;
    }

    public static Result unpin(final IColony colony, final int orderId, final BlockPos hut)
    {
        return Result.DISABLED;
    }
}
