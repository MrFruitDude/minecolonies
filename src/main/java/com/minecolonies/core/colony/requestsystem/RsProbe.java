package com.minecolonies.core.colony.requestsystem;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opt-in measurement hooks of the request system, for the stress benchmark ({@code core/gametest/bench}). Every hook site is
 * {@code if (RsProbe.enabled) {...}}: one static field read while the probe is off, which is always the case outside the
 * benchmark. While it is on, scoped timers around the request system's entry points record, on the one thread the probe is
 * bound to (the server thread), the CPU time and the allocated bytes of the thread inside each scope.
 * <p>
 * Scopes nest. A scope's <i>inclusive</i> cost contains the scopes inside it, its <i>self</i> cost does not. The request
 * system's total is the sum of the self costs of all scopes, i.e. the time spent inside any scope at all, counted once.
 * Each scope also keeps a log-bucket histogram of its inclusive CPU time per call (p50/p99 per operation).
 * <p>
 * The probe's own cost (two thread-clock reads per scope) lands inside the measured intervals; {@link #calibrate} measures
 * it per scope and the benchmark subtracts it from the total.
 */
public final class RsProbe
{
    /**
     * Master switch. Hook sites read this and nothing else while it is false.
     */
    public static volatile boolean enabled;

    // ------------------------------------------------------------------ scopes

    /** Creating a request in the manager. */
    public static final int CREATE        = 0;
    /** Finding a resolver for a request: the whole assignment pass, resolver checks included. */
    public static final int ASSIGN        = 1;
    /** One resolver's canResolveRequest. */
    public static final int CAN_RESOLVE   = 2;
    /** One resolver's attemptResolveRequest. */
    public static final int ATTEMPT       = 3;
    /** A request state change and everything it triggers (resolved, completed, cancelled follow-ups). */
    public static final int STATE         = 4;
    /** The manager's periodic tick. */
    public static final int MGR_TICK      = 5;
    /** The wait tracker / retrying resolver work inside the tick, and the stock event index. */
    public static final int REEVAL        = 6;
    /** Stock was added to a warehouse: the manager is told. */
    public static final int STOCK_EVENT   = 7;
    /** A courier picks its next task. */
    public static final int COURIER_PICK  = 8;
    /** A reservation ledger write (reserve, commit, release, sweep). */
    public static final int LEDGER        = 9;
    /** A reservation ledger read (available, reserved). */
    public static final int LEDGER_READ   = 10;
    /** The request-system view sync to close players: snapshot, delta, send. */
    public static final int VIEW_SYNC     = 11;
    /** The snapshot inside the view sync. */
    public static final int SNAPSHOT      = 12;
    /** Saving the request manager to NBT. */
    public static final int SAVE          = 13;
    /** Loading the request manager from NBT. */
    public static final int LOAD          = 14;
    /** Re-assigning requests after a colony change. */
    public static final int COLONY_UPDATE = 15;
    /** A courier finishing a task. */
    public static final int COURIER_DONE  = 16;
    /** Scopes a test or benchmark opens itself (no production hook uses it). */
    public static final int EXTERNAL      = 17;

    public static final int SCOPES = 18;

    public static final String[] NAMES = {
      "create", "assign", "canResolve", "attempt", "stateChange", "mgrTick", "reeval", "stockEvent", "courierPick",
      "ledgerWrite", "ledgerRead", "viewSync", "snapshot", "save", "load", "colonyUpdate", "courierDone", "external"
    };

    /**
     * Scopes that are entered thousands of times per tick and take microseconds: timed with the cheap wall clock
     * ({@link System#nanoTime}) instead of the thread CPU clock, which costs more than they do. On a busy single thread the
     * two agree; the benchmark runs on an otherwise idle server thread.
     */
    private static final boolean[] FINE = new boolean[SCOPES];

    static
    {
        FINE[CAN_RESOLVE] = true;
        FINE[ATTEMPT] = true;
        FINE[LEDGER] = true;
        FINE[LEDGER_READ] = true;
    }

    private static double pairCoarse = 1400;
    private static double pairFine   = 60;

    private static final int  MAX_DEPTH = 96;
    private static final int  BUCKETS   = 64 * 4;
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final com.sun.management.ThreadMXBean ALLOC =
      THREADS instanceof com.sun.management.ThreadMXBean sun && sun.isThreadAllocatedMemorySupported() ? sun : null;

    private static volatile Thread owner;

    // frame stack (owner thread only)
    private static final long[] startCpu     = new long[MAX_DEPTH];
    private static final long[] startAlloc   = new long[MAX_DEPTH];
    private static final long[] childCpu     = new long[MAX_DEPTH];
    private static final long[] childAlloc   = new long[MAX_DEPTH];
    private static final int[]  frameScope   = new int[MAX_DEPTH];
    private static int          depth;

    // per scope totals
    private static final long[]   count      = new long[SCOPES];
    private static final long[]   inclCpu    = new long[SCOPES];
    private static final long[]   selfCpu    = new long[SCOPES];
    private static final long[]   inclAlloc  = new long[SCOPES];
    private static final long[]   selfAlloc  = new long[SCOPES];
    private static final long[][] hist       = new long[SCOPES][BUCKETS];
    private static final Map<Class<?>, long[]> byClass = new IdentityHashMap<>();

    private static long topCount;
    private static long totalCpu;
    private static long totalAlloc;
    private static long extraBytes;
    private static long extraMessages;

    private RsProbe()
    {
    }

    // ------------------------------------------------------------------ control

    /**
     * Binds the probe to the calling thread (the server thread), clears all counters and switches it on.
     */
    public static void start()
    {
        reset();
        owner = Thread.currentThread();
        enabled = true;
    }

    /**
     * Switches the probe off; the counters stay readable.
     */
    public static void stop()
    {
        enabled = false;
        owner = null;
        depth = 0;
    }

    public static void reset()
    {
        depth = 0;
        for (int i = 0; i < SCOPES; i++)
        {
            count[i] = 0;
            inclCpu[i] = 0;
            selfCpu[i] = 0;
            inclAlloc[i] = 0;
            selfAlloc[i] = 0;
            java.util.Arrays.fill(hist[i], 0L);
        }
        byClass.clear();
        topCount = 0;
        totalCpu = 0;
        totalAlloc = 0;
        extraBytes = 0;
        extraMessages = 0;
    }

    // ------------------------------------------------------------------ hooks

    /**
     * Opens a scope. Pair with {@link #end} in a finally block.
     */
    public static void begin(final int scope)
    {
        if (Thread.currentThread() != owner || depth >= MAX_DEPTH)
        {
            return;
        }
        final int d = depth++;
        frameScope[d] = scope;
        childCpu[d] = 0;
        childAlloc[d] = 0;
        startAlloc[d] = ALLOC == null ? 0 : ALLOC.getCurrentThreadAllocatedBytes();
        startCpu[d] = FINE[scope] ? System.nanoTime() : THREADS.getCurrentThreadCpuTime();
    }

    public static void end(final int scope)
    {
        end(scope, null);
    }

    /**
     * Closes a scope, optionally attributing it to a class too (the resolver that was asked).
     */
    public static void end(final int scope, final Class<?> key)
    {
        if (depth == 0 || Thread.currentThread() != owner)
        {
            return;
        }
        final long cpuNow = FINE[scope] ? System.nanoTime() : THREADS.getCurrentThreadCpuTime();
        final long allocNow = ALLOC == null ? 0 : ALLOC.getCurrentThreadAllocatedBytes();
        final int d = --depth;
        if (frameScope[d] != scope)
        {
            // Unbalanced (a hook site without a finally): drop the frame instead of corrupting the stack.
            return;
        }
        final long incl = Math.max(0, cpuNow - startCpu[d]);
        final long inclA = Math.max(0, allocNow - startAlloc[d]);
        final long self = Math.max(0, incl - childCpu[d]);
        final long selfA = Math.max(0, inclA - childAlloc[d]);
        count[scope]++;
        inclCpu[scope] += incl;
        selfCpu[scope] += self;
        inclAlloc[scope] += inclA;
        selfAlloc[scope] += selfA;
        hist[scope][bucket(incl)]++;
        if (key != null)
        {
            final long[] cell = byClass.computeIfAbsent(key, k -> new long[SCOPES * 3]);
            cell[scope * 3] += 1;
            cell[scope * 3 + 1] += incl;
            cell[scope * 3 + 2] += inclA;
        }
        if (d > 0)
        {
            childCpu[d - 1] += incl;
            childAlloc[d - 1] += inclA;
        }
        else
        {
            topCount++;
            totalCpu += incl;
            totalAlloc += inclA;
        }
    }

    /**
     * Counts bytes the request system sent to players (view sync).
     */
    public static void addSent(final int bytes, final int receivers)
    {
        if (Thread.currentThread() == owner)
        {
            extraBytes += (long) bytes * Math.max(1, receivers);
            extraMessages++;
        }
    }

    // ------------------------------------------------------------------ readout

    /**
     * @return CPU nanoseconds the owner thread spent inside any scope so far (nested scopes counted once).
     */
    public static long totalCpuNs()
    {
        return totalCpu;
    }

    public static long totalAllocBytes()
    {
        return totalAlloc;
    }

    /**
     * @return the number of scopes opened at depth 0.
     */
    public static long topLevelScopes()
    {
        return topCount;
    }

    public static long scopeCount()
    {
        long n = 0;
        for (int i = 0; i < SCOPES; i++)
        {
            n += count[i];
        }
        return n;
    }

    public static long count(final int scope)
    {
        return count[scope];
    }

    public static long inclCpuNs(final int scope)
    {
        return inclCpu[scope];
    }

    public static long selfCpuNs(final int scope)
    {
        return selfCpu[scope];
    }

    public static long inclAllocBytes(final int scope)
    {
        return inclAlloc[scope];
    }

    public static long sentBytes()
    {
        return extraBytes;
    }

    public static long sentMessages()
    {
        return extraMessages;
    }

    /**
     * @param scope a scope.
     * @param q     quantile 0..1.
     * @return the per-call inclusive CPU time at that quantile in nanoseconds (bucket upper bound), 0 if no calls.
     */
    public static long quantileNs(final int scope, final double q)
    {
        final long[] h = hist[scope];
        long total = 0;
        for (final long c : h)
        {
            total += c;
        }
        if (total == 0)
        {
            return 0;
        }
        final long target = Math.max(1, (long) Math.ceil(q * total));
        long seen = 0;
        for (int b = 0; b < h.length; b++)
        {
            seen += h[b];
            if (seen >= target)
            {
                return bucketUpper(b);
            }
        }
        return bucketUpper(h.length - 1);
    }

    /**
     * @return per resolver (or other keyed) class: scope name, calls, inclusive CPU ns and allocated bytes.
     */
    public static List<Object[]> byClassRows()
    {
        final List<Object[]> rows = new ArrayList<>();
        for (final Map.Entry<Class<?>, long[]> e : byClass.entrySet())
        {
            for (int s = 0; s < SCOPES; s++)
            {
                final long[] cell = e.getValue();
                if (cell[s * 3] > 0)
                {
                    rows.add(new Object[] {e.getKey().getSimpleName(), NAMES[s], cell[s * 3], cell[s * 3 + 1], cell[s * 3 + 2]});
                }
            }
        }
        return rows;
    }


    // ------------------------------------------------------------------ snapshots

    /**
     * A copy of all counters, to measure a phase as the difference of two.
     */
    public static final class Snap
    {
        public final long[]   count      = RsProbe.count.clone();
        public final long[]   inclCpu    = RsProbe.inclCpu.clone();
        public final long[]   selfCpu    = RsProbe.selfCpu.clone();
        public final long[]   inclAlloc  = RsProbe.inclAlloc.clone();
        public final long[]   selfAlloc  = RsProbe.selfAlloc.clone();
        public final long[][] hist;
        public final long     totalCpu   = RsProbe.totalCpu;
        public final long     totalAlloc = RsProbe.totalAlloc;
        public final long     topCount   = RsProbe.topCount;
        public final long     overhead   = overheadNs();
        public final long     sentBytes  = extraBytes;
        public final long     sentMsgs   = extraMessages;

        private Snap()
        {
            hist = new long[SCOPES][];
            for (int i = 0; i < SCOPES; i++)
            {
                hist[i] = RsProbe.hist[i].clone();
            }
        }

        /**
         * @param scope a scope.
         * @param q     quantile 0..1.
         * @param base  the snapshot to subtract (earlier), or null.
         * @return the per-call inclusive CPU time at that quantile in the interval, nanoseconds.
         */
        public long quantileNs(final int scope, final double q, final Snap base)
        {
            final long[] h = hist[scope];
            final long[] b = base == null ? null : base.hist[scope];
            long total = 0;
            for (int i = 0; i < h.length; i++)
            {
                total += h[i] - (b == null ? 0 : b[i]);
            }
            if (total <= 0)
            {
                return 0;
            }
            final long target = Math.max(1, (long) Math.ceil(q * total));
            long seen = 0;
            for (int i = 0; i < h.length; i++)
            {
                seen += h[i] - (b == null ? 0 : b[i]);
                if (seen >= target)
                {
                    return bucketUpper(i);
                }
            }
            return bucketUpper(h.length - 1);
        }
    }

    public static Snap snap()
    {
        return new Snap();
    }

    // ------------------------------------------------------------------ calibration

    /**
     * Measures what one empty scope costs on the calling thread (a coarse one with the thread CPU clock, a fine one with the
     * wall clock) and stores it for {@link #overheadNs()}. Call before the real run.
     *
     * @return {coarse, fine} nanoseconds a begin/end pair adds to the time measured around it.
     */
    public static double[] calibrate(final int pairs)
    {
        final Thread previous = owner;
        final boolean wasEnabled = enabled;
        owner = Thread.currentThread();
        enabled = true;
        final double[] result = new double[2];
        for (int kind = 0; kind < 2; kind++)
        {
            final int scope = kind == 0 ? EXTERNAL : CAN_RESOLVE;
            reset();
            for (int i = 0; i < pairs / 10; i++)
            {
                begin(scope);
                end(scope);
            }
            final long t0 = System.nanoTime();
            for (int i = 0; i < pairs; i++)
            {
                begin(scope);
                end(scope);
            }
            final long t1 = System.nanoTime();
            result[kind] = (double) (t1 - t0) / pairs;
        }
        reset();
        owner = previous;
        enabled = wasEnabled;
        pairCoarse = result[0];
        pairFine = result[1];
        return result;
    }

    /**
     * @return the part of {@link #totalCpuNs()} that is the probe itself: every nested scope's pair lands inside its
     * parent's interval, a top-level scope keeps about half of its own pair.
     */
    public static long overheadNs()
    {
        double nested = 0;
        for (int i = 0; i < SCOPES; i++)
        {
            nested += count[i] * (FINE[i] ? pairFine : pairCoarse);
        }
        return (long) Math.max(0, nested - topCount * 0.5 * pairCoarse);
    }

    // ------------------------------------------------------------------ histogram

    private static int bucket(final long ns)
    {
        if (ns <= 1)
        {
            return 0;
        }
        final int log = 63 - Long.numberOfLeadingZeros(ns);
        final int sub = log >= 2 ? (int) ((ns >> (log - 2)) & 3) : 0;
        return Math.min(BUCKETS - 1, log * 4 + sub);
    }

    private static long bucketUpper(final int b)
    {
        final int log = b / 4;
        final int sub = b % 4;
        if (log < 2)
        {
            return 1L << (log + 1);
        }
        return ((4L + sub + 1) << (log - 2));
    }
}
