package com.minecolonies.core.colony.storage;

import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.util.BlockPosUtil;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;

/**
 * Estimated completion time of a courier trip, after Anno's hub ranking: travel plus the wait in the hub's ramp queue plus
 * the handling time, the handling capped. All in game ticks. The best warehouse is the one that finishes first, which is not
 * always the nearest: a near warehouse with a full queue loses to a far one with free ramps.
 */
public final class DispatchCost
{
    /**
     * Walking speed of a courier, blocks per tick (a player walks 0.216).
     */
    public static final double BLOCKS_PER_TICK = 0.2D;

    /**
     * Roads and corners make the walk longer than the straight line.
     */
    public static final double PATH_FACTOR = 1.3D;

    /**
     * Anno caps handling at 300 s.
     */
    public static final int MAX_HANDLING_TICKS = 300 * 20;

    /**
     * Ticks to move one item at a rack (a stack of 64 takes 6.4 s).
     */
    public static final double TICKS_PER_ITEM = 2.0D;

    /**
     * Ticks a courier occupies a ramp of the warehouse for one task, apart from the item handling.
     */
    public static final int TASK_TICKS = 60;

    private DispatchCost()
    {
    }

    /**
     * @param from where the courier starts.
     * @param to   where it goes.
     * @return the walking time in ticks.
     */
    public static int travelTicks(@NotNull final BlockPos from, @NotNull final BlockPos to)
    {
        return (int) Math.ceil(BlockPosUtil.getDistance(from, to) * PATH_FACTOR / BLOCKS_PER_TICK);
    }

    /**
     * @param items how many items are loaded or unloaded.
     * @return the handling time in ticks, at least one second, at most 300 s.
     */
    public static int handlingTicks(final int items)
    {
        return (int) Math.min(MAX_HANDLING_TICKS, Math.max(20, Math.ceil(items * TICKS_PER_ITEM)));
    }

    /**
     * @param level  the warehouse building's level: the number of ramps.
     * @param queued tasks waiting in the warehouse's queue plus couriers on a task that starts at it.
     * @return how long a new task waits for a free ramp, in ticks.
     */
    public static int queueTicks(final int level, final int queued)
    {
        final int ramps = Math.max(1, level);
        return (int) Math.ceil(queued / (double) ramps) * TASK_TICKS;
    }

    /**
     * Completion time of fetching {@code items} from a warehouse for a destination.
     *
     * @param warehouse   the warehouse.
     * @param queued      tasks at the warehouse (queued and active).
     * @param destination where the items go.
     * @param items       how many.
     * @return ticks.
     */
    public static int fetchTicks(@NotNull final IWareHouse warehouse, final int queued, @NotNull final BlockPos destination, final int items)
    {
        // the warehouse-to-building walk counts roads (cached per pair, see DispatchPaths); without roads it is travelTicks
        return queueTicks(warehouse.getBuildingLevel(), queued) + handlingTicks(items) + DispatchPaths.travelTicks(warehouse.getColony(), warehouse.getID(), destination);
    }

    /**
     * Completion time of dropping {@code items} at a warehouse.
     *
     * @param warehouse the warehouse.
     * @param queued    tasks at the warehouse (queued and active).
     * @param origin    where the courier comes from.
     * @param items     how many.
     * @return ticks.
     */
    public static int dropTicks(@NotNull final IWareHouse warehouse, final int queued, @NotNull final BlockPos origin, final int items)
    {
        return queueTicks(warehouse.getBuildingLevel(), queued) + handlingTicks(items) + travelTicks(origin, warehouse.getID());
    }
}
