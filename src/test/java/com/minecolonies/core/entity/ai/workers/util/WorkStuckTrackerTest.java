package com.minecolonies.core.entity.ai.workers.util;

import com.minecolonies.core.entity.ai.workers.util.WorkStuckTracker.Step;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WorkStuckTrackerTest
{
    /** One check per 5 ticks, the worker does not move. */
    private static List<Step> standStill(final WorkStuckTracker tracker, final int ticks)
    {
        final List<Step> steps = new ArrayList<>();
        for (int t = 0; t < ticks; t += 5)
        {
            final Step step = tracker.blocked(5, 0.0D);
            if (step != Step.NONE)
            {
                steps.add(step);
            }
        }
        return steps;
    }

    @Test
    public void laddersUpInOrderAndEachStepComesOnce()
    {
        final WorkStuckTracker tracker = new WorkStuckTracker();
        assertEquals(List.of(Step.REPATH, Step.SAFE_SPOT, Step.SCAFFOLD, Step.TELEPORT, Step.DEFER), standStill(tracker, 2000));
    }

    @Test
    public void nothingHappensBeforeTheFirstThreshold()
    {
        final WorkStuckTracker tracker = new WorkStuckTracker();
        assertTrue(standStill(tracker, WorkStuckTracker.REPATH_AT - 5).isEmpty());
    }

    @Test
    public void aWorkerWhoKeepsMovingIsNotStuck()
    {
        final WorkStuckTracker tracker = new WorkStuckTracker();
        // a long walk: one block per check for 3000 ticks
        for (int t = 0; t < 3000; t += 5)
        {
            assertEquals(Step.NONE, tracker.blocked(5, 1.0D));
        }
    }

    @Test
    public void movingInCirclesIsDeferredByTheOverallLimit()
    {
        final WorkStuckTracker tracker = new WorkStuckTracker();
        Step last = Step.NONE;
        for (int t = 0; t < WorkStuckTracker.OVERALL_LIMIT + 50; t += 5)
        {
            final Step step = tracker.blocked(5, 1.0D);
            if (step != Step.NONE)
            {
                last = step;
            }
        }
        assertEquals(Step.DEFER, last);
    }

    @Test
    public void progressStartsTheLadderAgain()
    {
        final WorkStuckTracker tracker = new WorkStuckTracker();
        standStill(tracker, 1000);
        tracker.progress();
        assertEquals(Step.NONE, tracker.lastStep());
        assertEquals(List.of(Step.REPATH, Step.SAFE_SPOT), standStill(tracker, WorkStuckTracker.SAFE_SPOT_AT + 5));
    }

    @Test
    public void freeResetsTheStandingStillTime()
    {
        final WorkStuckTracker tracker = new WorkStuckTracker();
        standStill(tracker, WorkStuckTracker.REPATH_AT - 10);
        tracker.free();
        assertEquals(0, tracker.stationaryTicks());
        assertTrue(standStill(tracker, WorkStuckTracker.REPATH_AT - 10).isEmpty());
    }

    @Test
    public void aFallenWorkerTakesTheTeleportAtOnceAndOnlyOnce()
    {
        final WorkStuckTracker tracker = new WorkStuckTracker();
        assertEquals(Step.TELEPORT, tracker.escalateTo(Step.TELEPORT));
        assertEquals(Step.NONE, tracker.escalateTo(Step.TELEPORT));
        assertEquals(Step.NONE, tracker.escalateTo(Step.REPATH));
        // the ladder goes on above it
        assertEquals(List.of(Step.DEFER), standStill(tracker, 2000));
    }

    @Test
    public void thresholdsAreOrdered()
    {
        assertTrue(WorkStuckTracker.REPATH_AT < WorkStuckTracker.SAFE_SPOT_AT);
        assertTrue(WorkStuckTracker.SAFE_SPOT_AT < WorkStuckTracker.SCAFFOLD_AT);
        assertTrue(WorkStuckTracker.SCAFFOLD_AT < WorkStuckTracker.TELEPORT_AT);
        assertTrue(WorkStuckTracker.TELEPORT_AT < WorkStuckTracker.DEFER_AT);
        assertTrue(WorkStuckTracker.DEFER_AT < WorkStuckTracker.OVERALL_LIMIT);
    }
}
