package com.minecolonies.api.pathfinding;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.util.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registry of {@link IRouteHintProvider}s: other mods suggest waypoints (for example along their roads) for long citizen
 * walks. MineColonies asks the providers in registration order when a citizen starts a walk longer than
 * {@link #MIN_DISTANCE} blocks; the first non-empty answer is used, and the walk follows it leg by leg with normal path
 * searches, falling back to a plain search when there is no answer or a leg fails. See {@link IRouteHintProvider} for
 * the contract. Without providers nothing changes. Each server tick has a budget of queries ({@link #admit}).
 */
public final class RouteHints
{
    /**
     * Walks longer than this (straight line, blocks) ask for hints.
     */
    public static final int MIN_DISTANCE = 48;

    /**
     * Time a provider call may take.
     */
    public static final long BUDGET_NANOS = 1_000_000L;

    /**
     * Waypoints used at most from one answer.
     */
    public static final int MAX_WAYPOINTS = 64;

    /**
     * A provider is switched off for {@link #DISABLE_NANOS} after sustained slowness: {@link #MAX_OVERRUNS} or more calls
     * over {@link #BUDGET_NANOS} among its last {@link #WINDOW_CALLS} calls (a sliding window), or
     * {@link #MAX_HARD_OVERRUNS} calls over {@link #HARD_LIMIT_NANOS} among them. One occasional overrun (a cold first
     * call, a GC pause) does not switch it off. Also after {@link #MAX_FAILURES} calls in a row that threw or returned a
     * broken list. Times are System.nanoTime.
     */
    public static final int  WINDOW_CALLS      = 100;
    public static final int  MAX_OVERRUNS      = 10;
    public static final long HARD_LIMIT_NANOS  = 20_000_000L;
    public static final int  MAX_HARD_OVERRUNS = 2;
    public static final int  MAX_FAILURES      = 10;
    public static final long DISABLE_NANOS     = 5 * 60 * 1_000_000_000L;

    /**
     * Provider calls that took longer than {@link #BUDGET_NANOS}, calls that threw or returned a broken list, and times a
     * provider was switched off.
     */
    public static final AtomicLong slowCalls   = new AtomicLong();
    public static final AtomicLong failedCalls = new AtomicLong();
    public static final AtomicLong disables    = new AtomicLong();

    /**
     * A registered provider and its budget record: the last {@link #WINDOW_CALLS} call times in a ring.
     */
    private static final class Entry
    {
        final IRouteHintProvider provider;
        final long[] recent        = new long[WINDOW_CALLS];
        int          recentCount   = 0;
        int          recentNext    = 0;
        int          failuresInRow = 0;
        boolean      disabled      = false;
        long         disabledSince = 0;
        long         lastWarn      = 0;
        boolean      warned        = false;

        Entry(final IRouteHintProvider provider)
        {
            this.provider = provider;
        }

        String name()
        {
            return provider.getClass().getName();
        }

        boolean isDisabled()
        {
            if (disabled && System.nanoTime() - disabledSince >= DISABLE_NANOS)
            {
                disabled = false;
            }
            return disabled;
        }

        void record(final long took)
        {
            recent[recentNext] = took;
            recentNext = (recentNext + 1) % WINDOW_CALLS;
            recentCount = Math.min(WINDOW_CALLS, recentCount + 1);
        }

        int countOver(final long limit)
        {
            int n = 0;
            for (int i = 0; i < recentCount; i++)
            {
                if (recent[i] > limit)
                {
                    n++;
                }
            }
            return n;
        }

        void warn(final String message, @Nullable final Throwable e)
        {
            final long now = System.nanoTime();
            if (!warned || now - lastWarn > 10_000_000_000L)
            {
                warned = true;
                lastWarn = now;
                if (e == null)
                {
                    Log.getLogger().warn(message);
                }
                else
                {
                    Log.getLogger().warn(message, e);
                }
            }
        }

        void disable(final String reason)
        {
            disabled = true;
            disabledSince = System.nanoTime();
            recentCount = 0;
            recentNext = 0;
            failuresInRow = 0;
            disables.incrementAndGet();
            // logged once per switch-off, not rate limited away
            Log.getLogger().warn("Route hint provider " + name() + " switched off for " + DISABLE_NANOS / 1_000_000_000L + " s: " + reason);
        }
    }

    private static final List<Entry> PROVIDERS = new CopyOnWriteArrayList<>();

    /**
     * Round 11 (W4-05): the hint queries of one server tick, all levels together, are limited to {@link #TICK_CALLS} queries and {@link #TICK_NANOS} of
     * provider time. The turns are shared between the colonies that asked in the tick before (a walker without a colony counts as one more), so one big
     * colony cannot use up the queries of another. A walker refused here walks a plain search and asks again on a later walk.
     */
    public static final int  TICK_CALLS = 16;
    public static final long TICK_NANOS = 2_000_000L;

    /**
     * Queries refused by the per-tick budget.
     */
    public static final AtomicLong budgetRefusals = new AtomicLong();

    private static final Object NO_COLONY = new Object();
    private static long tick = Long.MIN_VALUE;
    private static int  tickCalls;
    private static long tickNanos;
    private static int  share = TICK_CALLS;
    private static final java.util.Map<Object, Integer> TICK_COLONY_CALLS = new java.util.IdentityHashMap<>();
    private static final java.util.Set<Object>          TICK_ASKERS       = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private RouteHints()
    {
    }

    /**
     * Registers a provider. Thread safe; call during mod setup (or later, on the server thread).
     *
     * @param provider the provider.
     */
    public static void register(@NotNull final IRouteHintProvider provider)
    {
        PROVIDERS.add(new Entry(provider));
    }

    /**
     * Removes a provider.
     *
     * @param provider the provider.
     * @return true if it was registered.
     */
    public static boolean unregister(@NotNull final IRouteHintProvider provider)
    {
        return PROVIDERS.removeIf(e -> e.provider == provider);
    }

    /**
     * @return true if any provider is registered.
     */
    public static boolean hasProviders()
    {
        return !PROVIDERS.isEmpty();
    }

    private static void rollTick(final ServerLevel level)
    {
        final long now = level.getServer() == null ? level.getGameTime() : level.getServer().getTickCount();
        if (now != tick)
        {
            // the colonies that asked in the last tick share the turns of this one
            share = Math.max(1, TICK_CALLS / Math.max(1, now == tick + 1 ? TICK_ASKERS.size() : 1));
            tick = now;
            tickCalls = 0;
            tickNanos = 0L;
            TICK_COLONY_CALLS.clear();
            TICK_ASKERS.clear();
        }
    }

    /**
     * Whether a query for a walker of the colony fits the budget of this server tick ({@link #TICK_CALLS}, {@link #TICK_NANOS}, the colony's share). Asking
     * counts the colony as one that wants turns. Server thread.
     *
     * @param level  the level.
     * @param colony the walker's colony, may be null.
     * @return true if {@link #query} may be called now.
     */
    public static synchronized boolean admit(final ServerLevel level, @Nullable final IColony colony)
    {
        rollTick(level);
        final Object key = colony == null ? NO_COLONY : colony;
        TICK_ASKERS.add(key);
        final boolean ok = tickCalls < TICK_CALLS && tickNanos < TICK_NANOS && TICK_COLONY_CALLS.getOrDefault(key, 0) < share;
        if (!ok)
        {
            budgetRefusals.incrementAndGet();
        }
        return ok;
    }

    /**
     * @return the provider time spent in the current server tick (for the tests).
     */
    public static synchronized long tickNanos()
    {
        return tickNanos;
    }

    /**
     * Asks the providers for waypoints, in registration order, skipping switched-off ones. Never throws: a provider that
     * throws (anything but a VirtualMachineError) is skipped. Null entries are dropped. Called by MineColonies'
     * navigation on the server thread.
     *
     * @param level  the level.
     * @param colony the walker's colony, may be null.
     * @param from   the start.
     * @param to     the destination.
     * @return an unmodifiable copy of the first non-empty answer (at most {@link #MAX_WAYPOINTS}), or null.
     */
    @Nullable
    public static List<BlockPos> query(final ServerLevel level, @Nullable final IColony colony, final BlockPos from, final BlockPos to)
    {
        if (!admit(level, colony))
        {
            // over the budget of this tick (see admit, which callers ask first to keep their own bookkeeping)
            return null;
        }
        final long t0 = System.nanoTime();
        try
        {
            for (final Entry entry : PROVIDERS)
            {
                synchronized (entry)
                {
                    if (entry.isDisabled())
                    {
                        continue;
                    }
                    final List<BlockPos> copy = call(entry, level, colony, from, to);
                    if (copy != null)
                    {
                        return copy;
                    }
                }
            }
            return null;
        }
        finally
        {
            spent(colony, System.nanoTime() - t0);
        }
    }

    private static synchronized void spent(@Nullable final IColony colony, final long nanos)
    {
        tickCalls++;
        tickNanos += nanos;
        TICK_COLONY_CALLS.merge(colony == null ? NO_COLONY : colony, 1, Integer::sum);
    }

    @Nullable
    private static List<BlockPos> call(final Entry entry, final ServerLevel level, @Nullable final IColony colony, final BlockPos from, final BlockPos to)
    {
        final long t0 = System.nanoTime();
        List<BlockPos> answer = null;
        boolean failed = false;
        try
        {
            answer = entry.provider.waypoints(level, colony, from.immutable(), to.immutable());
        }
        catch (final VirtualMachineError e)
        {
            throw e;
        }
        catch (final Throwable e)
        {
            failed = true;
            failedCalls.incrementAndGet();
            entry.warn("Route hint provider " + entry.name() + " threw; ignored", e);
        }
        final long took = System.nanoTime() - t0;
        entry.record(took);
        if (took > BUDGET_NANOS)
        {
            slowCalls.incrementAndGet();
            entry.warn("Route hint provider " + entry.name() + " took " + took / 1000 + " us (budget " + BUDGET_NANOS / 1000 + " us)", null);
        }

        List<BlockPos> copy = null;
        if (!failed && answer != null)
        {
            // every access to the provider's list may throw (its own list type): all inside the catch
            try
            {
                if (!answer.isEmpty())
                {
                    copy = new ArrayList<>(Math.min(answer.size(), MAX_WAYPOINTS));
                    for (final BlockPos pos : answer)
                    {
                        if (pos != null)
                        {
                            copy.add(pos.immutable());
                            if (copy.size() >= MAX_WAYPOINTS)
                            {
                                break;
                            }
                        }
                    }
                }
            }
            catch (final VirtualMachineError e)
            {
                throw e;
            }
            catch (final Throwable e)
            {
                failed = true;
                copy = null;
                failedCalls.incrementAndGet();
                entry.warn("Route hint provider " + entry.name() + " returned a broken list; ignored", e);
            }
        }
        entry.failuresInRow = failed ? entry.failuresInRow + 1 : 0;

        final int hard = entry.countOver(HARD_LIMIT_NANOS);
        final int over = entry.countOver(BUDGET_NANOS);
        if (hard >= MAX_HARD_OVERRUNS)
        {
            entry.disable(hard + " of the last " + entry.recentCount + " calls over " + HARD_LIMIT_NANOS / 1_000_000 + " ms");
        }
        else if (over >= MAX_OVERRUNS)
        {
            entry.disable(over + " of the last " + entry.recentCount + " calls over " + BUDGET_NANOS / 1000 + " us");
        }
        else if (entry.failuresInRow >= MAX_FAILURES)
        {
            entry.disable(entry.failuresInRow + " calls in a row threw or returned a broken list");
        }
        return copy == null || copy.isEmpty() ? null : List.copyOf(copy);
    }
}
