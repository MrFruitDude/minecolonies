package com.minecolonies.core.colony.requestsystem.wait;

import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.core.colony.requestsystem.requests.RsTestSupport;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * RS2: the courier's task deadline and stuck detection, on a plain clock.
 */
public class CourierWatchdogTest
{
    private static final BlockPos A = new BlockPos(0, 64, 0);
    private static final BlockPos B = new BlockPos(40, 64, 0);

    @Test
    public void aCourierThatKeepsMovingIsLeftAlone()
    {
        final CourierWatchdog dog = new CourierWatchdog();
        final IToken<?> task = RsTestSupport.token();
        long now = 0;
        for (int step = 0; step < 40; step++)
        {
            now += 100;
            assertEquals(CourierWatchdog.Verdict.OK, dog.check(task, new BlockPos(step * 2, 64, 0), now));
        }
    }

    @Test
    public void aCourierThatDoesNotMoveIsStuckAfterAMinute()
    {
        final CourierWatchdog dog = new CourierWatchdog();
        final IToken<?> task = RsTestSupport.token();
        assertEquals(CourierWatchdog.Verdict.OK, dog.check(task, A, 0));
        assertEquals(CourierWatchdog.Verdict.OK, dog.check(task, A, CourierWatchdog.STUCK_TICKS));
        assertEquals(CourierWatchdog.Verdict.STUCK, dog.check(task, A, CourierWatchdog.STUCK_TICKS + 1));
    }

    @Test
    public void movingInASmallCircleStillCountsAsStuckButOneStepResetsTheClock()
    {
        final CourierWatchdog dog = new CourierWatchdog();
        final IToken<?> task = RsTestSupport.token();
        dog.check(task, A, 0);
        // A one-block jitter is not progress.
        assertEquals(CourierWatchdog.Verdict.STUCK, dog.check(task, A.east(), CourierWatchdog.STUCK_TICKS + 5));
        final CourierWatchdog fresh = new CourierWatchdog();
        fresh.check(task, A, 0);
        assertEquals("two blocks is progress", CourierWatchdog.Verdict.OK, fresh.check(task, A.east(2), CourierWatchdog.STUCK_TICKS + 5));
    }

    @Test
    public void aTaskPastItsDeadlineIsGivenUpEvenWhileMoving()
    {
        final CourierWatchdog dog = new CourierWatchdog();
        final IToken<?> task = RsTestSupport.token();
        dog.check(task, A, 0);
        long now = 0;
        CourierWatchdog.Verdict verdict = CourierWatchdog.Verdict.OK;
        int x = 0;
        while (now <= CourierWatchdog.TASK_DEADLINE && verdict == CourierWatchdog.Verdict.OK)
        {
            now += 50;
            verdict = dog.check(task, new BlockPos(x += 3, 64, 0), now);
        }
        assertEquals(CourierWatchdog.Verdict.DEADLINE, verdict);
    }

    @Test
    public void aNewTaskRestartsTheClock()
    {
        final CourierWatchdog dog = new CourierWatchdog();
        final IToken<?> first = RsTestSupport.token();
        final IToken<?> second = RsTestSupport.token();
        dog.check(first, A, 0);
        assertEquals(CourierWatchdog.Verdict.STUCK, dog.check(first, A, CourierWatchdog.STUCK_TICKS + 10));
        assertEquals("another task, same spot", CourierWatchdog.Verdict.OK, dog.check(second, A, CourierWatchdog.STUCK_TICKS + 20));
        assertEquals(CourierWatchdog.Verdict.OK, dog.check(second, B, CourierWatchdog.STUCK_TICKS + 30));
    }

    @Test
    public void resetForgetsTheTask()
    {
        final CourierWatchdog dog = new CourierWatchdog();
        final IToken<?> task = RsTestSupport.token();
        dog.check(task, A, 0);
        dog.reset();
        assertEquals("the same task after a reset starts over", CourierWatchdog.Verdict.OK, dog.check(task, A, CourierWatchdog.STUCK_TICKS + 500));
    }
}
