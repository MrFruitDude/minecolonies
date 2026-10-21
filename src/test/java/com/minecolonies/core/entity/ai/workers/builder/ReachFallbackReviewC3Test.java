package com.minecolonies.core.entity.ai.workers.builder;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Builder review c3: a default placement only within MineColonies' own radius (R1); the returns of a block the ladder would leave out always end (R2).
 */
public class ReachFallbackReviewC3Test
{
    private static final BlockPos FEET = new BlockPos(0, 64, 0);

    @Test
    public void defaultBlockEightAwayIsNotReachable()
    {
        assertFalse(EntityAIStructureBuilder.inDefaultRange(FEET, new BlockPos(8, 64, 0)));
        assertFalse(EntityAIStructureBuilder.inDefaultRange(FEET, new BlockPos(4, 64, 4)));
        assertFalse(EntityAIStructureBuilder.inDefaultRange(FEET, new BlockPos(0, 64, -6)));
    }

    @Test
    public void defaultBlockFiveAroundAnyHeight()
    {
        assertTrue(EntityAIStructureBuilder.inDefaultRange(FEET, new BlockPos(5, 64, 0)));
        assertTrue(EntityAIStructureBuilder.inDefaultRange(FEET, new BlockPos(2, 80, 3)));
    }

    @Test
    public void thirteenthReturnIsADefaultTry()
    {
        // builder review c4 C4-1: the 12 returns count as the wait, so the 13th is placed the way MineColonies does, scaffolding there or not
        for (int n = 1; n <= EntityAIStructureBuilder.LAST_RESORT_KEEPS; n++)
        {
            assertEquals(EntityAIStructureBuilder.KeepStep.KEEP, EntityAIStructureBuilder.keepStep(n));
        }
        assertEquals(EntityAIStructureBuilder.KeepStep.DEFAULT, EntityAIStructureBuilder.keepStep(EntityAIStructureBuilder.LAST_RESORT_KEEPS + 1));
    }

    @Test
    public void leftOutOnlyAfterTheDefaultTryFailed()
    {
        for (int n = EntityAIStructureBuilder.LAST_RESORT_KEEPS + 2; n < 1000; n++)
        {
            assertEquals(EntityAIStructureBuilder.KeepStep.LEAVE_OUT, EntityAIStructureBuilder.keepStep(n));
        }
    }
}
