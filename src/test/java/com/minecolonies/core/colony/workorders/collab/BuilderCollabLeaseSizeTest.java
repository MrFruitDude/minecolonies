package com.minecolonies.core.colony.workorders.collab;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The size of a lease that grows with the walk to the hut of the lead: a trip to the hut that costs more than the blocks placed after it is no help.
 */
public class BuilderCollabLeaseSizeTest
{
    @Test
    public void aHutRightNextToTheSiteKeepsTheConfiguredSize()
    {
        assertEquals(12, BuilderCollab.scaledLeaseSize(12, 0.0D));
        assertEquals(14, BuilderCollab.scaledLeaseSize(12, 1.0D));
    }

    @Test
    public void aFarHutGrowsTheLeaseUpToFourTimes()
    {
        assertEquals(32, BuilderCollab.scaledLeaseSize(8, 20.0D));
        assertEquals(48, BuilderCollab.scaledLeaseSize(12, 500.0D));
        assertEquals(16, BuilderCollab.scaledLeaseSize(8, 4.0D));
    }

    @Test
    public void neverBelowOneNorShrinkingWithDistance()
    {
        assertEquals(4, BuilderCollab.scaledLeaseSize(0, 1000.0D));
        assertTrue(BuilderCollab.scaledLeaseSize(1, -5.0D) >= 1);
        int last = 0;
        for (int d = 0; d <= 40; d++)
        {
            final int size = BuilderCollab.scaledLeaseSize(8, d);
            assertTrue(size >= last);
            last = size;
        }
    }
}
