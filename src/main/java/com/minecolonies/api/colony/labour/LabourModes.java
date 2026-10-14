package com.minecolonies.api.colony.labour;

/**
 * The switches of the labourers (unemployed citizens and idle builders who gather and craft the basic goods), as server config values.
 */
public final class LabourModes
{
    private LabourModes()
    {
    }

    /**
     * Which crafting tables and furnaces labourers use.
     */
    public enum PostMode
    {
        /**
         * Any vanilla crafting table or furnace inside the colony's claim and within the post range of the town hall, except those flagged EXCLUDE.
         */
        ANY_IN_RANGE,
        /**
         * Only those flagged PREFER.
         */
        MARKED
    }

    /**
     * When a specialist building covers a good, so that labour keeps off.
     */
    public enum CoverageMode
    {
        /**
         * The building is built (level 1 or more) and has the recipe: a worker is not needed.
         */
        BUILT,
        /**
         * The building has the recipe and a worker.
         */
        STAFFED
    }

    /**
     * What labourers smelt.
     */
    public enum SmeltMode
    {
        OFF,
        /**
         * Charcoal and stone.
         */
        BASIC,
        /**
         * Adds raw metals to ingots.
         */
        IRON
    }
}
