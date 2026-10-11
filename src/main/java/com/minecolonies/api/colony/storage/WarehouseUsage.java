package com.minecolonies.api.colony.storage;

import net.minecraft.core.BlockPos;

/**
 * One warehouse of the storage network.
 *
 * @param pos          the building's position (its id).
 * @param level        the building level.
 * @param usedSlots    rack slots holding something.
 * @param totalSlots   rack slots.
 * @param items        items in the racks.
 * @param queuedTasks  courier tasks (deliveries, pickups) waiting in its queue.
 * @param activeTasks  couriers that are on a task that starts at this warehouse.
 * @param couriers     couriers assigned to this warehouse as their home.
 * @param inPerMinute  items put into it during the last minute (dumps, deliveries to it).
 * @param outPerMinute items taken out of it during the last minute.
 * @param loaded       false when some of its racks are not loaded.
 */
public record WarehouseUsage(BlockPos pos, int level, int usedSlots, int totalSlots, long items, int queuedTasks, int activeTasks, int couriers, int inPerMinute, int outPerMinute,
                             boolean loaded)
{
    /**
     * @return the fill 0..1, 1 when there are no slots.
     */
    public double fill()
    {
        return totalSlots <= 0 ? 1.0D : Math.min(1.0D, usedSlots / (double) totalSlots);
    }
}
