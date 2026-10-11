package com.minecolonies.core.gametest.bench;

import com.google.gson.JsonObject;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * CPU time and allocated bytes of the calling thread: the two noise-resistant clocks the benchmark prefers over wall time.
 */
public final class ThreadCost
{
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final com.sun.management.ThreadMXBean ALLOC =
      THREADS instanceof com.sun.management.ThreadMXBean sun && sun.isThreadAllocatedMemorySupported() ? sun : null;

    private ThreadCost()
    {
    }

    public static long cpuNs()
    {
        return THREADS.getCurrentThreadCpuTime();
    }

    public static long allocBytes()
    {
        return ALLOC == null ? 0 : ALLOC.getCurrentThreadAllocatedBytes();
    }

    /**
     * A running measurement.
     */
    public static final class Mark
    {
        private final long cpu = cpuNs();
        private final long alloc = allocBytes();
        private final long wall = System.nanoTime();

        public long cpuNs()
        {
            return ThreadCost.cpuNs() - cpu;
        }

        public long allocBytes()
        {
            return ThreadCost.allocBytes() - alloc;
        }

        public long wallNs()
        {
            return System.nanoTime() - wall;
        }
    }

    public static Mark mark()
    {
        return new Mark();
    }

    /**
     * Runs the body and returns what it cost the calling thread, as a JSON object {cpuNs, allocB, wallNs}.
     */
    public static JsonObject measure(final Runnable body)
    {
        final Mark mark = mark();
        body.run();
        final JsonObject out = new JsonObject();
        out.addProperty("cpuNs", mark.cpuNs());
        out.addProperty("allocB", mark.allocBytes());
        out.addProperty("wallNs", mark.wallNs());
        return out;
    }
}
