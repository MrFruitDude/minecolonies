package com.minecolonies.core.entity.ai.workers.util;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The one reach rule of a stand spot: the eye is where a citizen who stands there really has it (half a block lower on a bottom slab floor), for the spot
 * search and for the check when he arrives alike.
 */
public class StandReachTest
{
    private static final double EYE   = BuildReach.EYE_HEIGHT;
    private static final double DELTA = 1e-9;

    @Test
    public void theFloorDecidesHowHighHeStands()
    {
        assertEquals(0.0D, StandSpots.standOffsetForFloorTop(1.0D), DELTA);
        assertEquals(-0.5D, StandSpots.standOffsetForFloorTop(0.5D), DELTA);
        assertEquals(0.5D, StandSpots.standOffsetForFloorTop(1.5D), DELTA);
        assertEquals(-0.9375D, StandSpots.standOffsetForFloorTop(0.0625D), DELTA);
        // no collision box below: as on a full block (the spot search does not offer such a cell anyway)
        assertEquals(0.0D, StandSpots.standOffsetForFloorTop(0.0D), DELTA);
        // never more than a fence's half block up
        assertEquals(0.5D, StandSpots.standOffsetForFloorTop(3.0D), DELTA);
    }

    @Test
    public void onAFullBlockTheRuleIsTheOldOne()
    {
        final BlockPos feet = new BlockPos(10, 64, 10);
        for (final BlockPos block : List.of(feet.above(3), feet.north().above(2), new BlockPos(14, 66, 11), feet.below()))
        {
            assertEquals(BuildReach.inReachFromFeet(feet, EYE, block, 3.6D), BuildReach.inReachFromStand(feet, 0.0D, EYE, block, 3.6D));
        }
    }

    @Test
    public void onASlabTheTopOfALampCanBeOutOfReachThatTheOldSpotRuleCounted()
    {
        // the old spot search measured from the bottom of the feet block; a builder on a bottom slab stands half a block lower, more than the margin
        final BlockPos feet = new BlockPos(0, 0, 0);
        final BlockPos lampTop = new BlockPos(0, 4, 0);
        assertTrue(BuildReach.inReachFromFeet(feet, EYE, lampTop, 2.8D));
        assertFalse(BuildReach.inReachFromStand(feet, -0.5D, EYE, lampTop, 2.8D));
        // and on a fence or wall he reaches further up
        assertTrue(BuildReach.inReachFromStand(feet, 0.5D, EYE, new BlockPos(0, 5, 0), 2.9D));
        assertFalse(BuildReach.inReachFromFeet(feet, EYE, new BlockPos(0, 5, 0), 2.9D));
    }

    @Test
    public void theCountsUseTheSameEye()
    {
        final BlockPos feet = new BlockPos(0, 0, 0);
        final List<BlockPos> column = List.of(new BlockPos(1, 1, 0), new BlockPos(1, 2, 0), new BlockPos(1, 3, 0), new BlockPos(1, 4, 0));
        assertEquals(BuildReach.prefixInReach(feet, EYE, column, 2.8D), BuildReach.prefixInReach(feet, 0.0D, EYE, column, 2.8D));
        assertTrue(BuildReach.prefixInReach(feet, -0.5D, EYE, column, 2.8D) <= BuildReach.prefixInReach(feet, EYE, column, 2.8D));
        assertEquals(BuildReach.countInReach(feet, EYE - 0.5D, column, 2.8D), BuildReach.countInReach(feet, -0.5D, EYE, column, 2.8D));
    }
}
