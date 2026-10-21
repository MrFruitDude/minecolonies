package com.minecolonies.core.entity.pathfinding;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.entity.pathfinding.pathjobs.AbstractPathJob;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Static class the handles all the Pathfinding.
 */
public final class Pathfinding
{
    /**
     * The shared job queue: colonies take turns (see {@link FairPathQueue}); one colony may hold half of it at most.
     */
    private static final FairPathQueue jobQueue = new FairPathQueue(10000, 5000);
    private static       ThreadPoolExecutor           executor;

    /**
     * Job counters: what was submitted, rejected and how many searches ran at once; nodes searched, failed searches and
     * their nodes, time jobs waited in the queue, jobs refused by the colony unreachable cache, route hint use, and
     * searches whose heuristic was scaled for roads. Cheap atomics,
     * readable for perf logging and by GameTests.
     */
    public static final class Stats
    {
        public static final AtomicLong    submitted   = new AtomicLong();
        public static final AtomicLong    rejected    = new AtomicLong();
        public static final AtomicInteger running     = new AtomicInteger();
        public static final AtomicInteger peakRunning = new AtomicInteger();
        public static final AtomicLong    started     = new AtomicLong();
        public static final AtomicLong    nodesSearched        = new AtomicLong();
        public static final AtomicLong    failedSearches       = new AtomicLong();
        public static final AtomicLong    failedSearchNodes    = new AtomicLong();
        public static final AtomicLong    queueWaitNanos       = new AtomicLong();
        public static final AtomicLong    queueWaitMaxNanos    = new AtomicLong();
        public static final AtomicLong    unreachableCacheHits = new AtomicLong();
        public static final AtomicLong    routeHintQueries     = new AtomicLong();
        public static final AtomicLong    routeLegs            = new AtomicLong();
        public static final AtomicLong    routeFallbacks       = new AtomicLong();
        public static final AtomicLong    roadScaleApplied     = new AtomicLong();

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
            started.set(0);
            nodesSearched.set(0);
            failedSearches.set(0);
            failedSearchNodes.set(0);
            queueWaitNanos.set(0);
            queueWaitMaxNanos.set(0);
            unreachableCacheHits.set(0);
            routeHintQueries.set(0);
            routeLegs.set(0);
            routeFallbacks.set(0);
            roadScaleApplied.set(0);
            peakRunning.set(running.get());
        }

        /**
         * Called on the worker thread when a search starts.
         *
         * @return the start order of this search since the last reset.
         */
        public static long searchStarted()
        {
            final int now = running.incrementAndGet();
            peakRunning.accumulateAndGet(now, Math::max);
            return started.incrementAndGet();
        }

        /**
         * Called on the worker thread when a search ends.
         */
        public static void searchFinished()
        {
            running.decrementAndGet();
        }

        /**
         * Called on the worker thread with a finished search's numbers.
         *
         * @param nodes     nodes the search visited.
         * @param failed    whether it did not reach its destination.
         * @param waitNanos how long the job waited in the queue, negative when unknown.
         */
        public static void searchDone(final int nodes, final boolean failed, final long waitNanos)
        {
            nodesSearched.addAndGet(nodes);
            if (failed)
            {
                failedSearches.incrementAndGet();
                failedSearchNodes.addAndGet(nodes);
            }
            if (waitNanos >= 0)
            {
                queueWaitNanos.addAndGet(waitNanos);
                queueWaitMaxNanos.accumulateAndGet(waitNanos, Math::max);
            }
        }

        /**
         * @return the counters on one line, for logs.
         */
        public static String summary()
        {
            final long n = started.get();
            return String.format("submitted %d, rejected %d, started %d, peak running %d, nodes %d, failed %d (%d nodes), queue wait avg %.2f ms max %.2f ms, "
                                   + "cache refusals %d, route queries %d legs %d fallbacks %d, road scale applied %d",
              submitted.get(), rejected.get(), n, peakRunning.get(), nodesSearched.get(), failedSearches.get(), failedSearchNodes.get(),
              n == 0 ? 0D : queueWaitNanos.get() / 1e6 / n, queueWaitMaxNanos.get() / 1e6, unreachableCacheHits.get(), routeHintQueries.get(), routeLegs.get(),
              routeFallbacks.get(), roadScaleApplied.get());
        }
    }

    /**
     * Identifies a colony across dimensions: colony ids are only unique within one dimension, so two colonies in one
     * world never share a key.
     *
     * @param dimension the colony's dimension.
     * @param colonyId  the colony's id.
     */
    public record ColonyKey(ResourceKey<Level> dimension, int colonyId)
    {
    }

    /**
     * GameTest override of the colony key: tried first, a null answer falls back to the citizen's colony. Null in
     * production; only settable in a GameTest run.
     */
    @Nullable
    private static volatile Function<Mob, ColonyKey> testColonyKeys = null;

    /**
     * @param mob the entity, may be null.
     * @return the colony key of the entity (a citizen's colony, in the citizen's current dimension), or null when it
     * belongs to no colony.
     */
    @Nullable
    public static ColonyKey colonyKeyOf(@Nullable final Mob mob)
    {
        if (mob == null)
        {
            return null;
        }
        final Function<Mob, ColonyKey> override = testColonyKeys;
        if (override != null)
        {
            final ColonyKey key = override.apply(mob);
            if (key != null)
            {
                return key;
            }
        }
        return citizenColonyKey(mob);
    }

    /**
     * GameTests only (-Dneoforge.enableGameTest=true): gives entities that are not citizens a colony key.
     *
     * @param keys the override.
     * @return closing it removes the override.
     */
    public static AutoCloseable overrideColonyKeysForTests(final Function<Mob, ColonyKey> keys)
    {
        requireGameTest();
        testColonyKeys = keys;
        return () -> testColonyKeys = null;
    }

    /**
     * Throws unless this is a GameTest run, so test hooks cannot change a normal server.
     */
    public static void requireGameTest()
    {
        if (!Boolean.getBoolean("neoforge.enableGameTest"))
        {
            throw new IllegalStateException("pathfinding test hook outside a GameTest run");
        }
    }

    @Nullable
    private static ColonyKey citizenColonyKey(final Mob mob)
    {
        if (mob instanceof AbstractEntityCitizen citizen && citizen.getCitizenColonyHandler() != null && citizen.getCitizenColonyHandler().getColonyId() != 0)
        {
            return new ColonyKey(mob.level().dimension(), citizen.getCitizenColonyHandler().getColonyId());
        }
        return null;
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
        return new PathExecutor(threads, queue);
    }

    /**
     * A queued path job that carries its colony, so a {@link FairPathQueue} can give each colony its turn.
     */
    private static final class KeyedTask<T> extends FutureTask<T> implements FairPathQueue.Keyed
    {
        @Nullable
        private final Object key;

        private KeyedTask(final Callable<T> callable, @Nullable final Object key)
        {
            super(callable);
            this.key = key;
        }

        @Nullable
        @Override
        public Object queueKey()
        {
            return key;
        }
    }

    /**
     * The pathfinding executor: wraps each path job in a task that names the job's colony.
     */
    private static final class PathExecutor extends ThreadPoolExecutor
    {
        private PathExecutor(final int threads, final BlockingQueue<Runnable> queue)
        {
            super(threads, threads, 10, TimeUnit.SECONDS, queue, new MinecoloniesThreadFactory(), new RejectAsFailed());
        }

        @Override
        protected <T> RunnableFuture<T> newTaskFor(final Callable<T> callable)
        {
            return new KeyedTask<>(callable, callable instanceof AbstractPathJob job ? job.getQueueKey() : null);
        }
    }

    /**
     * Takes a cancelled job's task out of the shared queue at once, so it no longer counts against its colony's share.
     *
     * @param task the task, may be one of another executor (then nothing happens).
     */
    public static void removeQueued(final Runnable task)
    {
        jobQueue.remove(task);
    }

    /**
     * Stops all running threads in this thread pool
     */
    public static void shutdown()
    {
        jobQueue.clear();
        ColonyUnreachableCache.clear();
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
