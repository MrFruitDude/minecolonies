package com.minecolonies.core.entity.ai.workers.builder;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The reach fallback's working range (builder review c1 H1): 5 blocks around, and at most 5 up or down from the eye; a tall wall is never in range from the ground.
 */
public class FallbackRangeTest
{
    private static final BlockPos FEET = new BlockPos(0, 64, 0);
    private static final double   EYE  = 64 + 1.62D;

    @Test
    public void upToFiveAboveTheEye()
    {
        assertTrue(EntityAIStructureBuilder.inFallbackRange(FEET, EYE, new BlockPos(1, 70, 0)));
        assertFalse(EntityAIStructureBuilder.inFallbackRange(FEET, EYE, new BlockPos(1, 71, 0)));
        // a block 8 up and more is never placed from the ground
        assertFalse(EntityAIStructureBuilder.inFallbackRange(FEET, EYE, new BlockPos(0, 72, 1)));
        assertFalse(EntityAIStructureBuilder.inFallbackRange(FEET, EYE, new BlockPos(0, 80, 0)));
    }

    @Test
    public void upToFiveBelowTheEye()
    {
        assertTrue(EntityAIStructureBuilder.inFallbackRange(FEET, EYE, new BlockPos(0, 60, 2)));
        assertFalse(EntityAIStructureBuilder.inFallbackRange(FEET, EYE, new BlockPos(0, 59, 2)));
    }

    @Test
    public void fiveAround()
    {
        assertTrue(EntityAIStructureBuilder.inFallbackRange(FEET, EYE, new BlockPos(5, 65, 0)));
        assertFalse(EntityAIStructureBuilder.inFallbackRange(FEET, EYE, new BlockPos(6, 65, 0)));
    }
}
