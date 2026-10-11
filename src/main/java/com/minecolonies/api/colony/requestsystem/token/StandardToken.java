package com.minecolonies.api.colony.requestsystem.token;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Internal implementation of the IToken interface. Uses UUID to store the ID of the request.
 */
public class StandardToken implements IToken<UUID>
{

    @NotNull
    private final UUID id;

    /**
     * Benchmark hook: when set, new tokens get reproducible ids (a counter mixed with the seed) instead of random ones, so
     * hash-ordered iteration over tokens is the same from run to run. Null in the game.
     */
    private static volatile java.util.concurrent.atomic.AtomicLong benchmarkCounter;

    private static volatile long benchmarkSeed;

    /**
     * Makes new tokens reproducible from {@code seed} on. For the request-system benchmark only.
     *
     * @param seed the seed, or null to return to random ids.
     */
    public static void seedForBenchmark(final Long seed)
    {
        if (seed == null)
        {
            benchmarkCounter = null;
            return;
        }
        benchmarkSeed = seed;
        benchmarkCounter = new java.util.concurrent.atomic.AtomicLong();
    }

    /**
     * @return a new token id: random, or the next reproducible one while {@link #seedForBenchmark} is active.
     */
    public static UUID newId()
    {
        final java.util.concurrent.atomic.AtomicLong counter = benchmarkCounter;
        if (counter == null)
        {
            return UUID.randomUUID();
        }
        final long n = counter.incrementAndGet();
        // SplitMix64 finalizer over (seed, n): well spread bits, so HashMap buckets behave like those of random ids.
        long z = n * 0x9E3779B97F4A7C15L + benchmarkSeed;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        long y = (n + benchmarkSeed) * 0xD6E8FEB86659FD93L;
        y = (y ^ (y >>> 32)) * 0xD6E8FEB86659FD93L;
        y ^= y >>> 32;
        return new UUID(z, y);
    }

    /**
     * Creates a new token with a random id.
     */
    public StandardToken()
    {
        this(newId());
    }

    /**
     * Creates a new token with the given id.
     *
     * @param id the UUID to create it from.
     */
    public StandardToken(@NotNull final UUID id)
    {
        this.id = id;
    }

    /**
     * The identifier used to represent a request.
     *
     * @return The identifier of the request that this token represents.
     */
    @Override
    public UUID getIdentifier()
    {
        return id;
    }

    @Override
    public int hashCode()
    {
        return id.hashCode();
    }

    @Override
    public boolean equals(final Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof IToken))
        {
            return false;
        }

        final IToken<?> that = (IToken<?>) o;

        return id.equals(that.getIdentifier());
    }

    @Override
    public String toString()
    {
        return "StandardToken{" +
                 "id=" + id +
                 '}';
    }
}
