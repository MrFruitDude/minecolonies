package com.minecolonies.api.colony.claim;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A process-wide revision of the chunk claim data: bumped whenever any {@link ChunkClaimData} changes or the colony
 * manager's claim maps gain, lose or replace entries (server mutation, client view sync, logout reset). In
 * singleplayer the client reads the integrated server's own claim objects, so a bump from either thread counts.
 * <p>
 * Client caches derived from claims (the colony border grid) compare it instead of re-reading every chunk's claim each
 * frame. It only ever grows; equal values mean "nothing about claims changed since".
 */
public final class ClaimRevision
{
    private static final AtomicLong REVISION = new AtomicLong();

    private ClaimRevision()
    {
    }

    /** The current revision. */
    public static long current()
    {
        return REVISION.get();
    }

    /** Some claim data changed. */
    public static void bump()
    {
        REVISION.incrementAndGet();
    }
}
