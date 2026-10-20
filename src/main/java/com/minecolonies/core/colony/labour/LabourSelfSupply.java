package com.minecolonies.core.colony.labour;

import java.util.HashMap;
import java.util.Map;

/**
 * When the builder of an order may go and fetch materials for it himself again (LABOURERS.md 6). Never a lifetime limit: an episode that brought goods lets him go again
 * soon; one that brought nothing waits, longer each time up to {@link #MAX_WAIT}, and then he tries again. An episode whose request was served by somebody else, or
 * whose order ended, counts neither way. Kept in memory only: after a reload every order may try again (after the grace time of its requests).
 */
public final class LabourSelfSupply
{
    /**
     * Wait after an episode that brought goods (the next lot of a larger request, or the next request).
     */
    public static final long AFTER_SUCCESS = 40L;

    /**
     * Wait after the first episode that brought nothing; doubles with each one after it.
     */
    public static final long FIRST_WAIT = 600L;

    /**
     * The longest wait between two episodes of an order.
     */
    public static final long MAX_WAIT = 6000L;

    /**
     * An order not heard of for this long is forgotten (its failures too).
     */
    public static final long FORGET_AFTER = 4 * MAX_WAIT;

    /**
     * How an episode ended.
     */
    public enum Outcome
    {
        /**
         * Goods were handed in at his hut.
         */
        DELIVERED,
        /**
         * He brought nothing: no plan, no path, the time ran out.
         */
        FAILED,
        /**
         * Not his doing: the request was served or cancelled, the order changed, he was called away by his needs.
         */
        NEUTRAL
    }

    private record State(int failures, long notBefore, long seen)
    {
    }

    private final Map<Integer, State> orders = new HashMap<>();

    /**
     * @return whether the builder of the order may start an episode now.
     */
    public boolean mayStart(final int orderId, final long now)
    {
        final State state = orders.get(orderId);
        return state == null || now >= state.notBefore();
    }

    /**
     * An episode starts: until it ends, the order waits as after a failure (an episode that is never ended does not hold the order for good).
     */
    public void started(final int orderId, final long now)
    {
        final State state = orders.get(orderId);
        final int failures = state == null ? 0 : state.failures();
        orders.put(orderId, new State(failures, now + waitAfter(failures + 1), now));
    }

    /**
     * An episode ends.
     */
    public void ended(final int orderId, final Outcome outcome, final long now)
    {
        final State state = orders.get(orderId);
        final int failures = state == null ? 0 : state.failures();
        switch (outcome)
        {
            case DELIVERED -> orders.put(orderId, new State(0, now + AFTER_SUCCESS, now));
            case FAILED -> orders.put(orderId, new State(failures + 1, now + waitAfter(failures + 1), now));
            case NEUTRAL -> orders.put(orderId, new State(failures, now + AFTER_SUCCESS, now));
        }
    }

    /**
     * @return the game time from which the order may try again (0 when it may now).
     */
    public long retryAt(final int orderId)
    {
        final State state = orders.get(orderId);
        return state == null ? 0L : state.notBefore();
    }

    /**
     * @return how many episodes of the order in a row brought nothing.
     */
    public int failures(final int orderId)
    {
        final State state = orders.get(orderId);
        return state == null ? 0 : state.failures();
    }

    /**
     * Forgets the orders not heard of for {@link #FORGET_AFTER}.
     */
    public void sweep(final long now)
    {
        orders.values().removeIf(state -> now - state.seen() > FORGET_AFTER && now >= state.notBefore());
    }

    /**
     * @param failures the episodes in a row that brought nothing (1 for the first).
     * @return the wait before the next episode.
     */
    public static long waitAfter(final int failures)
    {
        if (failures <= 0)
        {
            return AFTER_SUCCESS;
        }
        return Math.min(MAX_WAIT, FIRST_WAIT << Math.min(failures - 1, 4));
    }
}
