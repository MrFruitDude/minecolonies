package com.minecolonies.core.colony.storage;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The estimated completion time of a courier trip: walk, wait at the warehouse's ramps, handling (capped at 300 s as in Anno).
 */
public class DispatchCostTest
{
    @Test
    public void handlingIsAtLeastOneSecondAndCappedAtThreeHundred()
    {
        assertEquals(20, DispatchCost.handlingTicks(1));
        assertEquals(128, DispatchCost.handlingTicks(64));
        assertEquals(DispatchCost.MAX_HANDLING_TICKS, DispatchCost.handlingTicks(1_000_000));
    }

    @Test
    public void travelGrowsWithDistanceAndIsZeroAtTheSamePlace()
    {
        final BlockPos origin = BlockPos.ZERO;
        assertEquals(0, DispatchCost.travelTicks(origin, origin));
        assertTrue(DispatchCost.travelTicks(origin, new BlockPos(40, 0, 0)) > DispatchCost.travelTicks(origin, new BlockPos(10, 0, 0)));
        // 10 blocks at 0.2 blocks per tick with the path factor: 65 ticks
        assertEquals(65, DispatchCost.travelTicks(origin, new BlockPos(10, 0, 0)));
    }

    @Test
    public void moreRampsShortenTheQueue()
    {
        assertEquals(0, DispatchCost.queueTicks(1, 0));
        assertEquals(6 * DispatchCost.TASK_TICKS, DispatchCost.queueTicks(1, 6));
        assertEquals(2 * DispatchCost.TASK_TICKS, DispatchCost.queueTicks(3, 6));
        assertTrue(DispatchCost.queueTicks(5, 6) < DispatchCost.queueTicks(1, 6));
    }

    @Test
    public void aNearWarehouseWithALongQueueLosesToAFarOneWithFreeRamps()
    {
        final int near = DispatchCost.travelTicks(BlockPos.ZERO, new BlockPos(8, 0, 0)) + DispatchCost.queueTicks(1, 6) + DispatchCost.handlingTicks(4);
        final int far = DispatchCost.travelTicks(BlockPos.ZERO, new BlockPos(28, 0, 0)) + DispatchCost.queueTicks(1, 0) + DispatchCost.handlingTicks(4);
        assertTrue(far < near);
        final int nearIdle = DispatchCost.travelTicks(BlockPos.ZERO, new BlockPos(8, 0, 0)) + DispatchCost.queueTicks(1, 0) + DispatchCost.handlingTicks(4);
        assertTrue(nearIdle < far);
    }
}
