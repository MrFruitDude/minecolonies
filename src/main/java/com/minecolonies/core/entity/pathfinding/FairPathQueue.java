package com.minecolonies.core.entity.pathfinding;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The pathfinding job queue: one FIFO per colony (an insertion-ordered set, so removing a cancelled job is O(1)), and colonies take turns (round robin). A colony with many stuck
 * citizens queues many jobs, but every other colony still gets every other job slot, and one colony may fill at most
 * a part of the queue, so it cannot make every other colony's jobs be rejected. Jobs without a colony share one turn.
 * Bounded; a full queue (or a full colony share) refuses the offer, which the executor turns into a failed result.
 */
public final class FairPathQueue extends AbstractQueue<Runnable> implements BlockingQueue<Runnable>
{
    /**
     * A queued task that names its turn (its colony).
     */
    public interface Keyed
    {
        /**
         * @return the queue key, null for "no colony".
         */
        @Nullable
        Object queueKey();
    }

    private static final Object NO_COLONY = new Object();

    private final int capacity;
    private final int perKeyCapacity;

    private final ReentrantLock lock     = new ReentrantLock();
    private final Condition     notEmpty = lock.newCondition();
    private final Condition     notFull  = lock.newCondition();

    private final Map<Object, LinkedHashSet<Runnable>> queues = new HashMap<>();
    private final ArrayDeque<Object>                turns  = new ArrayDeque<>();
    private       int                               count  = 0;

    /**
     * @param capacity       jobs in the queue at most.
     * @param perKeyCapacity jobs of one colony at most.
     */
    public FairPathQueue(final int capacity, final int perKeyCapacity)
    {
        this.capacity = capacity;
        this.perKeyCapacity = Math.min(capacity, perKeyCapacity);
    }

    private static Object keyOf(final Object task)
    {
        if (task instanceof Keyed keyed && keyed.queueKey() != null)
        {
            return keyed.queueKey();
        }
        return NO_COLONY;
    }

    /**
     * Adds under the lock if there is room.
     */
    private boolean add0(final Runnable task)
    {
        if (count >= capacity)
        {
            return false;
        }
        final Object key = keyOf(task);
        LinkedHashSet<Runnable> queue = queues.get(key);
        if (queue == null)
        {
            queue = new LinkedHashSet<>();
            queues.put(key, queue);
            turns.addLast(key);
        }
        else if (queue.size() >= perKeyCapacity)
        {
            return false;
        }
        if (!queue.add(task))
        {
            return false;
        }
        count++;
        notEmpty.signal();
        return true;
    }

    /**
     * Takes the next job under the lock: the first colony in turn gives its oldest job and goes to the back.
     */
    private Runnable take0()
    {
        final Object key = turns.pollFirst();
        final LinkedHashSet<Runnable> queue = queues.get(key);
        final Runnable task = queue.removeFirst();
        if (queue.isEmpty())
        {
            queues.remove(key);
        }
        else
        {
            turns.addLast(key);
        }
        count--;
        notFull.signal();
        return task;
    }

    @Override
    public boolean offer(@NotNull final Runnable task)
    {
        Objects.requireNonNull(task);
        lock.lock();
        try
        {
            return add0(task);
        }
        finally
        {
            lock.unlock();
        }
    }

    @Override
    public boolean offer(@NotNull final Runnable task, final long timeout, @NotNull final TimeUnit unit) throws InterruptedException
    {
        Objects.requireNonNull(task);
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try
        {
            while (!add0(task))
            {
                if (nanos <= 0)
                {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            return true;
        }
        finally
        {
            lock.unlock();
        }
    }

    @Override
    public void put(@NotNull final Runnable task) throws InterruptedException
    {
        Objects.requireNonNull(task);
        lock.lockInterruptibly();
        try
        {
            while (!add0(task))
            {
                notFull.await();
            }
        }
        finally
        {
            lock.unlock();
        }
    }

    @Override
    public Runnable poll()
    {
        lock.lock();
        try
        {
            return count == 0 ? null : take0();
        }
        finally
        {
            lock.unlock();
        }
    }

    @Override
    public Runnable poll(final long timeout, @NotNull final TimeUnit unit) throws InterruptedException
    {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try
        {
            while (count == 0)
            {
                if (nanos <= 0)
                {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            return take0();
        }
        finally
        {
            lock.unlock();
        }
    }

    @NotNull
    @Override
    public Runnable take() throws InterruptedException
    {
        lock.lockInterruptibly();
        try
        {
            while (count == 0)
            {
                notEmpty.await();
            }
            return take0();
        }
        finally
        {
            lock.unlock();
        }
    }

    @Override
    public Runnable peek()
    {
        lock.lock();
        try
        {
            return count == 0 ? null : queues.get(turns.peekFirst()).getFirst();
        }
        finally
        {
            lock.unlock();
        }
    }

    @Override
    public int size()
    {
        lock.lock();
        try
        {
            return count;
        }
        finally
        {
            lock.unlock();
        }
    }

    @Override
    public int remainingCapacity()
    {
        lock.lock();
        try
        {
            return capacity - count;
        }
        finally
        {
            lock.unlock();
        }
    }

    @Override
    public boolean remove(final Object task)
    {
        if (task == null)
        {
            return false;
        }
        lock.lock();
        try
        {
            final Object key = keyOf(task);
            final LinkedHashSet<Runnable> queue = queues.get(key);
            if (queue == null || !queue.remove(task))
            {
                return false;
            }
            if (queue.isEmpty())
            {
                queues.remove(key);
                turns.remove(key);
            }
            count--;
            notFull.signal();
            return true;
        }
        finally
        {
            lock.unlock();
        }
    }

    @Override
    public void clear()
    {
        lock.lock();
        try
        {
            queues.clear();
            turns.clear();
            count = 0;
            notFull.signalAll();
        }
        finally
        {
            lock.unlock();
        }
    }

    @Override
    public int drainTo(@NotNull final Collection<? super Runnable> target)
    {
        return drainTo(target, Integer.MAX_VALUE);
    }

    @Override
    public int drainTo(@NotNull final Collection<? super Runnable> target, final int maxElements)
    {
        lock.lock();
        try
        {
            int n = 0;
            while (n < maxElements && count > 0)
            {
                target.add(take0());
                n++;
            }
            return n;
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * A snapshot in turn order (each colony's jobs in their order); remove() removes from the live queue.
     */
    @NotNull
    @Override
    public Iterator<Runnable> iterator()
    {
        final List<Runnable> snapshot = new ArrayList<>();
        lock.lock();
        try
        {
            for (final Object key : turns)
            {
                snapshot.addAll(queues.get(key));
            }
        }
        finally
        {
            lock.unlock();
        }
        final Iterator<Runnable> it = snapshot.iterator();
        return new Iterator<>()
        {
            private Runnable last;

            @Override
            public boolean hasNext()
            {
                return it.hasNext();
            }

            @Override
            public Runnable next()
            {
                last = it.next();
                return last;
            }

            @Override
            public void remove()
            {
                if (last == null)
                {
                    throw new IllegalStateException();
                }
                FairPathQueue.this.remove(last);
                last = null;
            }
        };
    }
}
