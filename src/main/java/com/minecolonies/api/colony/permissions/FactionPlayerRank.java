package com.minecolonies.api.colony.permissions;

/**
 * The rank a player has in a faction-owned colony (see {@link IPermissions#isFactionOwned()}) unless they were given
 * another one individually. Never an owner or officer rank: a player cannot manage a faction colony.
 */
public enum FactionPlayerRank
{
    /**
     * Visitors; the default.
     */
    NEUTRAL,
    /**
     * Allied: friends may use the colony's huts and tools and teleport to it.
     */
    FRIEND,
    /**
     * At war: hostile players may hurt citizens and visitors.
     */
    HOSTILE
}
