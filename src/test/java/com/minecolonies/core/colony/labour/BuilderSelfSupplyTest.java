package com.minecolonies.core.colony.labour;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * When the builder of an order may fetch for it again: empty episodes never stop him for good (the old limit of three did), the waits are bounded and grow, an episode
 * that brought goods lets him go again soon, one ended by somebody else counts neither way, and the orders are forgotten after a long quiet.
 */
public class BuilderSelfSupplyTest
{
    private static final int ORDER = 7;

    /**
     * Runs one episode that brings nothing, starting as soon as it may.
     *
     * @return the game time it ended at.
     */
    private static long emptyEpisode(final LabourSelfSupply policy, final long from)
    {
        long now = from;
        while (!policy.mayStart(ORDER, now))
        {
            now += 20;
        }
        policy.started(ORDER, now);
        now += 300;
        policy.ended(ORDER, LabourSelfSupply.Outcome.FAILED, now);
        return now;
    }

    @Test
    public void manyEmptyEpisodesNeverExhaustTheOrder()
    {
        final LabourSelfSupply policy = new LabourSelfSupply();
        long now = 1000;
        for (int i = 0; i < 12; i++)
        {
            now = emptyEpisode(policy, now);
            assertFalse("he may not go again right after an empty episode", policy.mayStart(ORDER, now));
            assertTrue("the wait is bounded", policy.retryAt(ORDER) - now <= LabourSelfSupply.MAX_WAIT);
            assertTrue("he may go again after the wait (episode " + (i + 1) + ")", policy.mayStart(ORDER, policy.retryAt(ORDER)));
        }
        assertEquals(12, policy.failures(ORDER));
    }

    @Test
    public void theWaitGrowsThenStops()
    {
        assertEquals(LabourSelfSupply.FIRST_WAIT, LabourSelfSupply.waitAfter(1));
        long last = 0;
        for (int failures = 1; failures < 20; failures++)
        {
            final long wait = LabourSelfSupply.waitAfter(failures);
            assertTrue(wait >= last);
            assertTrue(wait <= LabourSelfSupply.MAX_WAIT);
            last = wait;
        }
        assertEquals(LabourSelfSupply.MAX_WAIT, last);
    }

    @Test
    public void goodsDeliveredResetTheFailures()
    {
        final LabourSelfSupply policy = new LabourSelfSupply();
        long now = emptyEpisode(policy, 0);
        now = emptyEpisode(policy, now);
        now = emptyEpisode(policy, now);
        now = policy.retryAt(ORDER);
        policy.started(ORDER, now);
        policy.ended(ORDER, LabourSelfSupply.Outcome.DELIVERED, now + 400);
        assertEquals(0, policy.failures(ORDER));
        assertFalse("not in the same tick (no busy loop)", policy.mayStart(ORDER, now + 400));
        assertTrue("the next lot soon after", policy.mayStart(ORDER, now + 400 + LabourSelfSupply.AFTER_SUCCESS));
    }

    @Test
    public void anEpisodeEndedBySomebodyElseCountsNeitherWay()
    {
        final LabourSelfSupply policy = new LabourSelfSupply();
        final long now = emptyEpisode(policy, 0);
        final long retry = policy.retryAt(ORDER);
        policy.started(ORDER, retry);
        policy.ended(ORDER, LabourSelfSupply.Outcome.NEUTRAL, retry + 100);
        assertEquals(1, policy.failures(ORDER));
        assertTrue(policy.mayStart(ORDER, retry + 100 + LabourSelfSupply.AFTER_SUCCESS));
        assertTrue(now < retry);
    }

    @Test
    public void anEpisodeNeverEndedDoesNotHoldTheOrderForGood()
    {
        final LabourSelfSupply policy = new LabourSelfSupply();
        policy.started(ORDER, 0);
        assertFalse(policy.mayStart(ORDER, 1));
        assertTrue(policy.mayStart(ORDER, LabourSelfSupply.MAX_WAIT));
    }

    @Test
    public void ordersAreKeptApart()
    {
        final LabourSelfSupply policy = new LabourSelfSupply();
        emptyEpisode(policy, 0);
        assertTrue("another builder's order is not held back", policy.mayStart(ORDER + 1, 400));
        assertEquals(0, policy.failures(ORDER + 1));
    }

    @Test
    public void quietOrdersAreForgotten()
    {
        final LabourSelfSupply policy = new LabourSelfSupply();
        final long end = emptyEpisode(policy, 0);
        policy.sweep(end + 10);
        assertEquals(1, policy.failures(ORDER));
        policy.sweep(end + LabourSelfSupply.FORGET_AFTER + 1);
        assertEquals(0, policy.failures(ORDER));
        assertEquals(0L, policy.retryAt(ORDER));
    }
}
