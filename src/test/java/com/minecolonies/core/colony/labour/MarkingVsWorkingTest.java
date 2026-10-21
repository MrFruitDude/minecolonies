package com.minecolonies.core.colony.labour;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Preview8 integration (parent decision): marking a zone needs a loaded chunk this colony owns, not entity ticking (a player may mark from the overview beyond
 * the simulation distance); labour digs there only once entities tick (builder review c3 R3).
 */
public class MarkingVsWorkingTest
{
    @Test
    public void markingAcceptedInALoadedChunkThatDoesNotTick()
    {
        assertTrue(LabourSites.mayMark(true, true));
        assertFalse(LabourSites.mayMark(false, true));
        assertFalse(LabourSites.mayMark(true, false));
    }

    @Test
    public void labourDoesNotDigThereUntilItTicks()
    {
        assertFalse(LabourSites.mayWork(true, false, true));
        assertTrue(LabourSites.mayWork(true, true, true));
        assertFalse(LabourSites.mayWork(true, true, false));
        assertFalse(LabourSites.mayWork(false, false, true));
    }
}
