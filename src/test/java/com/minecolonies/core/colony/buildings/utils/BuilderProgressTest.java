package com.minecolonies.core.colony.buildings.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * An order that costs nothing must not read as done (findings/17 risk 9).
 */
public class BuilderProgressTest
{
    private static final double EPS = 1e-9;

    @Test
    public void costOrderCountsMaterials()
    {
        assertEquals(0.25D, BuilderProgress.remainingFraction(100, 25, 0, 0, 0, 0), EPS);
        assertEquals(0.0D, BuilderProgress.remainingFraction(100, 0, 0, 0, 0, 0), EPS);
    }

    @Test
    public void zeroCostOrderCountsBlocks()
    {
        assertEquals(1.0D, BuilderProgress.remainingFraction(0, 0, 0, 400, 0, 6), EPS);
        assertEquals(0.75D, BuilderProgress.remainingFraction(0, 0, 100, 400, 0, 6), EPS);
        assertEquals(0.0D, BuilderProgress.remainingFraction(0, 0, 500, 400, 0, 6), EPS);
    }

    @Test
    public void zeroCostOrderWithoutBlocksCountsStages()
    {
        assertEquals(0.5D, BuilderProgress.remainingFraction(0, 0, 0, 0, 3, 6), EPS);
    }

    @Test
    public void nothingKnownIsNotDone()
    {
        assertEquals(1.0D, BuilderProgress.remainingFraction(0, 0, 0, 0, 0, 0), EPS);
    }
}
