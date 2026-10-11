package com.minecolonies.core.entity.ai.workers.util;

import com.minecolonies.api.colony.WorkerWalking;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WorkerSettingsTest
{
    @After
    public void reset()
    {
        WorkerSettings.clearOverrides();
    }

    @Test
    public void paceIsTheProductOfTheModPaceAndTheSetting()
    {
        assertEquals(3.0D, WorkerSettings.composePace(2.0D, 1.5D), 1e-9);
        assertEquals(1.5D, WorkerSettings.composePace(1.0D, 1.5D), 1e-9);
        assertEquals(1.0D, WorkerSettings.composePace(1.0D, 1.0D), 1e-9);
        // a broken provider answer counts as 1.0, like in IColonyPaceProvider.sanitize
        assertEquals(1.5D, WorkerSettings.composePace(Double.NaN, 1.5D), 1e-9);
        assertEquals(1.5D, WorkerSettings.composePace(-3.0D, 1.5D), 1e-9);
        assertEquals(2.0D, WorkerSettings.composePace(2.0D, 0.0D), 1e-9);
    }

    @Test
    public void defaultsApplyWithoutAConfig()
    {
        assertEquals(1.5D, WorkerSettings.speedMultiplier(), 1e-9);
        assertEquals(1.5D, WorkerSettings.builderSpeed(), 1e-9);
        assertEquals(12600, WorkerSettings.nightStart());
        assertEquals(WorkerWalking.LONG_ONLY, WorkerSettings.abstractWalking());
        assertEquals(48, WorkerSettings.abstractWalkingMinBlocks());
        assertTrue(WorkerSettings.fastClear());
    }

    @Test
    public void overridesAreClampedToTheRange()
    {
        WorkerSettings.overrideForTests(100.0D, -1.0D, 99.0D, null, null, false);
        assertEquals(4.0D, WorkerSettings.speedMultiplier(), 1e-9);
        assertEquals(0.5D, WorkerSettings.builderSpeed(), 1e-9);
        assertEquals(22000, WorkerSettings.nightStart());
        assertFalse(WorkerSettings.fastClear());
        WorkerSettings.overrideForTests(Double.NaN, null, 12.6D, null, null, null);
        assertEquals(1.5D, WorkerSettings.speedMultiplier(), 1e-9);
    }

    @Test
    public void shiftLengthIsTheNightStart()
    {
        assertEquals(12600, WorkerSettings.nightStartOf(12.6D));
        assertEquals(8000, WorkerSettings.nightStartOf(8.0D));
        assertEquals(6000, WorkerSettings.nightStartOf(1.0D));
        // citizens without a job keep the usual day
        WorkerSettings.overrideForTests(null, null, 16.0D, null, null, null);
        assertEquals(16000, WorkerSettings.nightStartFor(true));
        assertEquals(12600, WorkerSettings.nightStartFor(false));
    }

    @Test
    public void abstractWalkingAppliesToLongWalksOnly()
    {
        assertFalse(AbstractWalking.applies(WorkerWalking.OFF, 500.0D, 4, 48));
        assertFalse(AbstractWalking.applies(WorkerWalking.LONG_ONLY, 47.0D, 4, 48));
        assertTrue(AbstractWalking.applies(WorkerWalking.LONG_ONLY, 49.0D, 4, 48));
        assertFalse(AbstractWalking.applies(WorkerWalking.ALWAYS, 8.0D, 4, 48));
        assertTrue(AbstractWalking.applies(WorkerWalking.ALWAYS, 13.0D, 4, 48));
    }

    @Test
    public void relocationTakesAsLongAsTheWalkDividedByTheSpeed()
    {
        final int slow = AbstractWalking.travelTicks(100.0D, 1.0D);
        final int fast = AbstractWalking.travelTicks(100.0D, 2.0D);
        assertEquals((int) Math.ceil(100 * AbstractWalking.PATH_FACTOR * AbstractWalking.TICKS_PER_BLOCK), slow);
        assertTrue(Math.abs(slow - 2 * fast) <= 1);
        assertEquals(AbstractWalking.MIN_TICKS, AbstractWalking.travelTicks(1.0D, 4.0D));
        // longer walks take longer
        assertTrue(AbstractWalking.travelTicks(200.0D, 1.5D) > AbstractWalking.travelTicks(60.0D, 1.5D));
    }
}
