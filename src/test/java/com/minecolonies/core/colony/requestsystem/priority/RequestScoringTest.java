package com.minecolonies.core.colony.requestsystem.priority;

import org.junit.Test;

import static com.minecolonies.core.colony.requestsystem.priority.RequestImportance.BACKGROUND;
import static com.minecolonies.core.colony.requestsystem.priority.RequestImportance.NORMAL;
import static com.minecolonies.core.colony.requestsystem.priority.RequestImportance.URGENT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * RS3: courier scoring is lexicographic (importance, building priority, wait seconds); distance only breaks a tie.
 */
public class RequestScoringTest
{
    private static long score(final RequestImportance importance, final int priority, final long waitedTicks, final int distance)
    {
        return RequestScoring.score(importance, priority, waitedTicks, distance);
    }

    @Test
    public void importanceBeatsBuildingPriorityWaitAndDistance()
    {
        // A far, fresh, priority-1 urgent request still beats a near, old, alarmed background one.
        assertTrue(score(URGENT, 1, 0, 5000) > score(NORMAL, BuildingPriorities.ALARM_PRIORITY, 1_000_000, 0));
        assertTrue(score(NORMAL, 1, 0, 5000) > score(BACKGROUND, BuildingPriorities.ALARM_PRIORITY, 1_000_000, 0));
    }

    @Test
    public void buildingPriorityBeatsWaitAndDistanceInsideAnImportanceClass()
    {
        assertTrue(score(NORMAL, 9, 0, 900) > score(NORMAL, 5, 100_000, 1));
        assertTrue(score(NORMAL, 6, 0, 900) > score(NORMAL, 5, 100_000, 1));
    }

    @Test
    public void alarmBeatsNine()
    {
        assertTrue(score(NORMAL, BuildingPriorities.ALARM_PRIORITY, 0, 900) > score(NORMAL, 9, 100_000, 0));
    }

    @Test
    public void waitBreaksTiesInsideAClassAndDistanceOnlyTheRest()
    {
        // The older request wins even when it is further away...
        assertTrue(score(NORMAL, 5, 20 * 60, 400) > score(NORMAL, 5, 20 * 59, 1));
        // ... but waits within the same second are the same wait, and then the nearer one wins.
        assertTrue(score(NORMAL, 5, 20 * 60, 4) > score(NORMAL, 5, 20 * 60 + 19, 400));
        assertEquals(score(NORMAL, 5, 20 * 60, 4), score(NORMAL, 5, 20 * 60 + 10, 4));
    }

    @Test
    public void identicalClassesNearerWins()
    {
        assertTrue(score(URGENT, 5, 0, 3) > score(URGENT, 5, 0, 30));
    }

    @Test
    public void extremeInputsStayInTheirClass()
    {
        final long huge = score(BACKGROUND, 10, Long.MAX_VALUE, Integer.MAX_VALUE);
        assertTrue(huge < score(NORMAL, 0, 0, Integer.MAX_VALUE));
        assertTrue(score(NORMAL, 99, 0, -5) == score(NORMAL, 10, 0, 0));
        assertTrue(score(NORMAL, -3, -7, 0) == score(NORMAL, 0, 0, 0));
    }

    @Test
    public void importanceClassification()
    {
        assertEquals(BACKGROUND, RequestImportance.classify(true, false, true));
        assertEquals(BACKGROUND, RequestImportance.classify(false, true, false));
        assertEquals(URGENT, RequestImportance.classify(false, false, true));
        assertEquals(NORMAL, RequestImportance.classify(false, false, false));
    }

    @Test
    public void rankLowerIsServedFirst()
    {
        assertTrue(URGENT.rank() < NORMAL.rank() && NORMAL.rank() < BACKGROUND.rank());
        assertEquals(0, URGENT.rank());
        assertEquals("com.minecolonies.coremod.request.importance.urgent", URGENT.translationKey());
    }
}
