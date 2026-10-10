package com.minecolonies.api.colony.workorders;

/**
 * Why a work order left the work manager.
 */
public enum WorkOrderRemovalReason
{
    /**
     * The builder finished the structure.
     */
    COMPLETED,
    /**
     * Somebody cancelled it, or the builder found its structure missing.
     */
    CANCELLED,
    /**
     * The work manager found it invalid (its building is gone, no structure path).
     */
    INVALID,
    /**
     * A new order for the same structure at the same place replaced it.
     */
    REPLACED
}
