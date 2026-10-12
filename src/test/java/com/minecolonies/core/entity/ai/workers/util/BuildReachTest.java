package com.minecolonies.core.entity.ai.workers.util;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuildReachTest
{
    private static final double EYE = BuildReach.EYE_HEIGHT;

    @Test
    public void theBlockUnderTheFeetAndTheOnesAroundAreInReach()
    {
        final BlockPos feet = new BlockPos(10, 64, 10);
        assertTrue(BuildReach.inReachFromFeet(feet, EYE, feet.below(), 5.0D));
        assertTrue(BuildReach.inReachFromFeet(feet, EYE, feet.north(), 5.0D));
        assertTrue(BuildReach.inReachFromFeet(feet, EYE, feet.above(2), 5.0D));
    }

    @Test
    public void distanceIsToTheNearestPointOfTheBlockNotItsMiddle()
    {
        // the eye is at 10.5, 65.62, 10.5: a block 5.4 blocks to the east has its near face at 4.9 from the eye
        final BlockPos feet = new BlockPos(10, 64, 10);
        assertTrue(BuildReach.inReachFromFeet(feet, EYE, new BlockPos(15, 65, 10), 5.0D));
        assertFalse(BuildReach.inReachFromFeet(feet, EYE, new BlockPos(16, 65, 10), 5.0D));
    }

    @Test
    public void reachIsSphericalNotAColumn()
    {
        // straight up the reach is 5 blocks above the eye, but a block 4 up and 4 to the side is out of reach
        final BlockPos feet = new BlockPos(0, 0, 0);
        assertTrue(BuildReach.inReachFromFeet(feet, EYE, new BlockPos(0, 6, 0), 5.0D));
        assertFalse(BuildReach.inReachFromFeet(feet, EYE, new BlockPos(0, 7, 0), 5.0D));
        assertFalse(BuildReach.inReachFromFeet(feet, EYE, new BlockPos(4, 5, 4), 5.0D));
    }

    @Test
    public void aWallIsReachedUpToSixBlocksFromTheGround()
    {
        // standing next to a wall: the 7th row (6 above the feet) is the last one in reach
        assertEquals(6, BuildReach.maxHeightNextToWall(EYE, 5.0D));
        assertEquals(8, BuildReach.maxHeightNextToWall(EYE, 7.0D));
    }

    @Test
    public void prefixCountsBlocksWithoutAGap()
    {
        final BlockPos feet = new BlockPos(0, 0, 0);
        final List<BlockPos> blocks = List.of(new BlockPos(1, 0, 0), new BlockPos(2, 0, 0), new BlockPos(20, 0, 0), new BlockPos(3, 0, 0));
        assertEquals(2, BuildReach.prefixInReach(feet, EYE, blocks, 5.0D));
        assertEquals(0, BuildReach.prefixInReach(feet, EYE, List.of(new BlockPos(30, 0, 0), new BlockPos(1, 0, 0)), 5.0D));
    }

    @Test
    public void eyeInsideTheBlockIsDistanceZero()
    {
        assertEquals(0.0D, BuildReach.distSq(10.5D, 65.0D, 10.5D, new BlockPos(10, 64, 10)), 1.0E-9D);
    }

    @Test
    public void tallStructuresAndWideRoofsMayNeedScaffoldingSmallHousesDoNot()
    {
        // a wall of 8 or more is out of reach from the ground
        assertTrue(BuildReach.mayNeedScaffolding(5, 8, 5, EYE, 5.0D));
        assertFalse(BuildReach.mayNeedScaffolding(5, 5, 5, EYE, 5.0D));
        // a roof 11 across, 5 high: the middle of it is not in reach from the ground around
        assertTrue(BuildReach.mayNeedScaffolding(11, 5, 11, EYE, 5.0D));
        // a flat 11 by 11 floor plan of one or two levels is
        assertFalse(BuildReach.mayNeedScaffolding(11, 2, 11, EYE, 5.0D));
        // with a longer reach the roof is in reach too
        assertFalse(BuildReach.mayNeedScaffolding(11, 5, 11, EYE, 7.0D));
    }
}
