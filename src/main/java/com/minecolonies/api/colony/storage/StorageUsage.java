package com.minecolonies.api.colony.storage;

/**
 * The colony's storage in numbers.
 *
 * @param usedSlots     rack slots holding something.
 * @param totalSlots    rack slots of all warehouses (loaded racks).
 * @param items         items in all warehouses.
 * @param distinctItems different item types in all warehouses.
 * @param warehouses    number of warehouses.
 * @param complete      false when some rack is not loaded, so the figures are a lower bound.
 */
public record StorageUsage(int usedSlots, int totalSlots, long items, int distinctItems, int warehouses, boolean complete)
{
    /**
     * @return the free slots.
     */
    public int freeSlots()
    {
        return Math.max(0, totalSlots - usedSlots);
    }

    /**
     * @return the fill 0..1, 1 when there are no slots.
     */
    public double fill()
    {
        return totalSlots <= 0 ? 1.0D : Math.min(1.0D, usedSlots / (double) totalSlots);
    }
}
