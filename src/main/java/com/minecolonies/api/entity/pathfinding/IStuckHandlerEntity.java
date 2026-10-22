package com.minecolonies.api.entity.pathfinding;

/**
 * Implemented by entities the stuck handler takes care of, used to ask the AI if it possibly could be stuck
 */
public interface IStuckHandlerEntity
{
    /**
     * Check whether the entity could currently be stuck, return false to prevent the stuck handler from taking action
     *
     * @return true if so.
     */
    default public boolean canBeStuck()
    {
        return true;
    }

    /**
     * Round 13 D4: where a stuck entity may be moved quickly. Called after about a minute without progress on a trip; null keeps the normal, slower stuck
     * handling (the default). Citizens answer for trips that are not work: home, bed and restaurant.
     *
     * @param destination the destination of the current path.
     * @return the position to move the entity to, or null.
     */
    @org.jetbrains.annotations.Nullable
    default net.minecraft.core.BlockPos getQuickRescueTarget(final net.minecraft.core.BlockPos destination)
    {
        return null;
    }
}
