package com.minecolonies.core.colony.requestsystem;

/**
 * Server-thread counters of the request system, for tests and benchmarks (the acceptance test reports them). They only
 * count; nothing reads them in the game.
 */
public final class RsStats
{
    /**
     * Requests created.
     */
    public static long requestsCreated;

    /**
     * Delivery requests created (one per stack a warehouse sends).
     */
    public static long deliveriesCreated;

    /**
     * Tasks couriers finished (successfully or not).
     */
    public static long courierTasksFinished;

    /**
     * Requests handed to the player resolver that are not courier tasks: the player has to act.
     */
    public static long playerFallbacks;

    private static final java.util.Set<com.minecolonies.api.colony.requestsystem.token.IToken<?>> FELL_BACK = new java.util.HashSet<>();

    private RsStats()
    {
    }

    /**
     * A request that is not a courier task went to the player. Counted once per request.
     *
     * @param token the request token.
     */
    public static void playerFallback(final com.minecolonies.api.colony.requestsystem.token.IToken<?> token)
    {
        if (FELL_BACK.add(token))
        {
            playerFallbacks++;
        }
    }

    public static void reset()
    {
        requestsCreated = 0;
        deliveriesCreated = 0;
        courierTasksFinished = 0;
        playerFallbacks = 0;
        FELL_BACK.clear();
    }
}
