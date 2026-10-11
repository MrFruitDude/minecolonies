package com.minecolonies.api.colony.faction;

/**
 * What became of an action of {@link IFactionColonyActions}.
 */
public enum FactionActionResult
{
    /**
     * Done (a work order was created, the hut placed, the citizen hired, the setting changed).
     */
    OK,
    /**
     * The colony (or the colony of the building) is not faction-owned. These actions skip the permission checks of a
     * player, so they refuse every colony that a player owns.
     */
    NOT_FACTION_COLONY,
    /**
     * The building is not a building of the colony it was given with, or is not registered any more.
     */
    NO_SUCH_BUILDING,
    /**
     * The hut item given is empty or no hut block item.
     */
    NO_HUT_ITEM,
    /**
     * The hut item is bound to another colony.
     */
    WRONG_COLONY,
    /**
     * The structure pack or blueprint is unknown, or its primary block is not the hut of the item.
     */
    BLUEPRINT_UNAVAILABLE,
    /**
     * The position, or the footprint of the blueprint, is not inside the colony's claim.
     */
    OUTSIDE_COLONY,
    /**
     * The hut block refuses the position (e.g. a second town hall or tavern).
     */
    PLACEMENT_REFUSED,
    /**
     * The request was valid but nothing came of it, e.g. no builder, building at its maximum level, a listener of
     * {@code BuildingUpgradeRequestModEvent} refused it. No work order was created.
     */
    REFUSED,
    /**
     * There is a work order for this building already.
     */
    ALREADY_REQUESTED,
    /**
     * The citizen or the module does not exist or cannot take the job.
     */
    NOT_POSSIBLE
}
