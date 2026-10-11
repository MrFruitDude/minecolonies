package com.minecolonies.api.colony.workorders.collab;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * What one builder hut is doing right now, for utilisation statistics. Build the list with {@code CollabViews.builders(colony)}.
 */
public interface ICollabBuilderView
{
    /**
     * What the builder does.
     */
    enum Role
    {
        /** Works on an order of his own (the lead of it). */
        LEAD,
        /** Helps another builder's order. */
        ASSIST,
        /** Has a builder but nothing to do. */
        IDLE,
        /** The hut has no builder (not counted as capacity). */
        VACANT
    }

    BlockPos hut();

    /**
     * @return the citizen id of the builder, -1 if the hut has none.
     */
    int citizenId();

    Role role();

    /**
     * @return the id of the order the builder leads or helps, -1 when idle.
     */
    int orderId();

    /**
     * @return true if the player pinned this builder to the order he helps.
     */
    boolean pinned();

    /**
     * @return the hut of the lead whose order is helped, null unless {@link #role()} is {@link Role#ASSIST}.
     */
    @Nullable
    BlockPos leadHut();
}
