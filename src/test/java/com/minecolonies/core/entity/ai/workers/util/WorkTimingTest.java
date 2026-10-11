package com.minecolonies.core.entity.ai.workers.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WorkTimingTest
{
    /** The research levels of block placing and breaking speed (ResearchEffectCategory.levelsAbsolute). */
    private static final double[] RESEARCH = {0, 0.1, 0.25, 0.5, 1, 2};

    @Test
    public void placeTicksKeepTheLevelScaling()
    {
        // 15 * 10 / (level/2 + 10): level 1 -> 15, 10 -> 10, 20 -> 7.5, 40 -> 5, 60 -> 3.75, 99 -> 2.5 (integer level/2)
        assertEquals(15.0D, WorkTiming.placeTicks(1, 0, 1.0D), 1e-9);
        assertEquals(10.0D, WorkTiming.placeTicks(10, 0, 1.0D), 1e-9);
        assertEquals(7.5D, WorkTiming.placeTicks(20, 0, 1.0D), 1e-9);
        assertEquals(5.0D, WorkTiming.placeTicks(40, 0, 1.0D), 1e-9);
        assertEquals(150.0D / 59.0D, WorkTiming.placeTicks(98, 0, 1.0D), 1e-9);
    }

    @Test
    public void higherLevelsAreNeverSlower()
    {
        double last = Double.MAX_VALUE;
        for (int level = 1; level <= 99; level++)
        {
            final double ticks = WorkTiming.placeTicks(level, 0, 1.5D);
            assertTrue("level " + level + " is slower than " + (level - 1), ticks <= last + 1e-12);
            last = ticks;
        }
    }

    @Test
    public void researchNeverMakesPlacingInstantOrNegative()
    {
        for (final double strength : RESEARCH)
        {
            final double ticks = WorkTiming.placeTicks(99, strength, 1.0D);
            assertTrue("research strength " + strength + " gives " + ticks + " ticks", ticks >= WorkTiming.MIN_PLACE_TICKS);
        }
    }

    @Test
    public void researchNeverMakesBreakingInstantOrNegative()
    {
        for (final double strength : RESEARCH)
        {
            final double ticks = WorkTiming.breakTicks(99, 0.5D, 8.0D, 0.5D, strength, 1.0D);
            assertTrue("research strength " + strength + " gives " + ticks + " ticks", ticks >= WorkTiming.MIN_BREAK_TICKS);
        }
    }

    @Test
    public void moreResearchIsNeverSlower()
    {
        double last = Double.MAX_VALUE;
        for (final double strength : RESEARCH)
        {
            final double ticks = WorkTiming.placeTicks(1, strength, 1.0D);
            assertTrue("research " + strength + " is slower than the one before", ticks <= last);
            last = ticks;
        }
    }

    @Test
    public void lowResearchLevelsKeepTheirEffect()
    {
        // +10%, +25%, +50% take that much off the delay, as before
        assertEquals(13.5D, WorkTiming.placeTicks(1, 0.1D, 1.0D), 1e-9);
        assertEquals(11.25D, WorkTiming.placeTicks(1, 0.25D, 1.0D), 1e-9);
        assertEquals(7.5D, WorkTiming.placeTicks(1, 0.5D, 1.0D), 1e-9);
    }

    @Test
    public void builderSpeedDividesTheDelayAndHasAFloor()
    {
        assertEquals(10.0D, WorkTiming.placeTicks(1, 0, 1.5D), 1e-9);
        assertEquals(7.5D, WorkTiming.placeTicks(1, 0, 2.0D), 1e-9);
        // level 99 at speed 4 would be below one tick: the floor holds
        assertEquals(WorkTiming.MIN_PLACE_TICKS, WorkTiming.placeTicks(99, 0, 4.0D), 1e-9);
        assertEquals(WorkTiming.MIN_BREAK_TICKS, WorkTiming.breakTicks(99, 0.5D, 8.0D, 0.5D, 0, 4.0D), 1e-9);
    }

    @Test
    public void breakTicksScaleWithLevelHardnessAndTool()
    {
        // dirt (0.5) by hand at level 1, builder factor 0.5: 500 * 0.85^0.5 * 0.5 / 1 * 0.5
        assertEquals(500 * Math.pow(0.85D, 0.5D) * 0.25D, WorkTiming.breakTicks(1, 0.5D, 1.0D, 0.5D, 0, 1.0D), 1e-9);
        assertTrue(WorkTiming.breakTicks(40, 1.5D, 6.0D, 0.5D, 0, 1.0D) < WorkTiming.breakTicks(1, 1.5D, 6.0D, 0.5D, 0, 1.0D));
        assertTrue(WorkTiming.breakTicks(10, 1.5D, 6.0D, 0.5D, 0, 1.0D) < WorkTiming.breakTicks(10, 1.5D, 1.0D, 0.5D, 0, 1.0D));
    }
}
