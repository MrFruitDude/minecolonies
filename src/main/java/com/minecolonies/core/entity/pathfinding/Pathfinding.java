package com.minecolonies.core.entity.pathfinding;

import com.minecolonies.api.util.Log;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.entity.pathfinding.pathjobs.AbstractPathJob;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Static class the handles all the Pathfinding.
 */
public final class Pathfinding
{
    private static final ArrayBlockingQueue<Runnable> jobQueue = new ArrayBlockingQueue<>(10000, false);
    private static       ThreadPoolExecutor           executor;

    /**
     * Job counters: what was submitted, rejected and how many searches ran at once. Cheap atomics, readable for perf
     * logging and by GameTests.
     */
    public static final class Stats
    {
        public static final AtomicLong    submitted   = new AtomicLong();
        public static final AtomicLong    rejected    = new AtomicLong();
        public static final AtomicInteger running     = new AtomicInteger();
        public static final AtomicInteger peakRunning = new AtomicInteger();

        private Stats()
        {
        }

        /**
         * Resets the submitted / rejected / peak counters (running is live state and is left alone).
         */
        public static void reset()
        {
            submitted.set(0);
            rejected.set(0);
            peakRunning.set(running.get());
        }

        /**
         * Called on the worker thread when a search starts.
         */
        public static void searchStarted()
        {
            final int now = running.incrementAndGet();
            peakRunning.accumulateAndGet(now, Math::max);
        }

        /**
         * Called on the worker thread when a search ends.
         */
        public static void searchFinished()
        {
            running.decrementAndGet();
        }
    }

    /**
     * Test hook: sees every job handed to an executor, on the submitting thread. Null in production.
     */
    @Nullable
    public static volatile Consumer<AbstractPathJob> submitObserver = null;

    /**
     * Minecolonies specific thread factory.
     */
    public static class MinecoloniesThreadFactory implements ThreadFactory
    {
        /**
         * Ongoing thread IDs.
         */
        private static final AtomicInteger id = new AtomicInteger();

        @Override
        public Thread newThread(@NotNull final Runnable runnable)
        {
            final Thread thread = new Thread(runnable, "Minecolonies Pathfinding Worker #" + id.getAndIncrement());
            thread.setDaemon(true);

            thread.setUncaughtExceptionHandler((thread1, throwable) -> Log.getLogger().error("Minecolonies Pathfinding Thread errored! ", throwable));
            return thread;
        }
    }

    /**
     * Upper bound for the configured worker count.
     */
    private static final int MAX_WORKERS = 4;

    /**
     * A full queue must never throw on the server thread (the default AbortPolicy does, and its message even calls the
     * unstarted job's toString, which throws an NPE). The rejected job's future is cancelled instead, so its PathResult
     * finishes as a failed result with no path, which the navigator handles like any failed path.
     */
    private static final class RejectAsFailed implements RejectedExecutionHandler
    {
        private static volatile long lastWarn = 0;

        @Override
        public void rejectedExecution(final Runnable runnable, final ThreadPoolExecutor pool)
        {
            Stats.rejected.incrementAndGet();
            if (runnable instanceof Future<?> future)
            {
                future.cancel(false);
            }
            final long now = System.currentTimeMillis();
            if (now - lastWarn > 10_000L)
            {
                lastWarn = now;
                Log.getLogger().warn("Minecolonies pathfinding queue is full ({} queued); path job dropped and reported as failed", pool.getQueue().size());
            }
        }
    }

    /**
     * Creates a new thread pool for pathfinding jobs, sized by the pathfindingworkerthreads server config (CA-10); a
     * changed config value resizes the running pool.
     *
     * @return the threadpool executor.
     */
    public static ThreadPoolExecutor getExecutor()
    {
        final int threads = configuredWorkers();
        if (executor == null)
        {
            executor = createExecutor(threads, jobQueue);
        }
        else if (executor.getMaximumPoolSize() != threads)
        {
            if (threads > executor.getMaximumPoolSize())
            {
                executor.setMaximumPoolSize(threads);
                executor.setCorePoolSize(threads);
            }
            else
            {
                executor.setCorePoolSize(threads);
                executor.setMaximumPoolSize(threads);
            }
        }
        return executor;
    }

    /**
     * @return the configured worker count, 1 when the server config is not loaded.
     */
    private static int configuredWorkers()
    {
        try
        {
            return Math.max(1, Math.min(MAX_WORKERS, MineColonies.getConfig().getServer().pathfindingWorkerThreads.get()));
        }
        catch (final IllegalStateException | NullPointerException e)
        {
            return 1;
        }
    }

    /**
     * Builds a pathfinding executor with the given worker count over the given queue. A full queue cancels the job
     * (failed result) instead of throwing.
     *
     * @param threads worker threads.
     * @param queue   job queue.
     * @return the executor.
     */
    public static ThreadPoolExecutor createExecutor(final int threads, final BlockingQueue<Runnable> queue)
    {
        return new ThreadPoolExecutor(threads, threads, 10, TimeUnit.SECONDS, queue, new MinecoloniesThreadFactory(), new RejectAsFailed());
    }

    /**
     * Stops all running threads in this thread pool
     */
    public static void shutdown()
    {
        jobQueue.clear();
    }

    private Pathfinding()
    {
        //Hides default constructor.
    }

    /**
     * Add a job to the queue for processing.
     *
     * @param job PathJob
     */
    public static void enqueue(@NotNull final AbstractPathJob job)
    {
        job.getResult().startJob(getExecutor());
    }
}
