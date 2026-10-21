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
 * the contract. Without providers nothing changes.
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
     * A provider is switched off for {@link #DISABLE_MILLIS} after sustained slowness: {@link #MAX_OVERRUNS} or more calls
     * over {@link #BUDGET_NANOS} within a window of {@link #WINDOW_CALLS} calls, or one call over
     * {@link #HARD_LIMIT_NANOS}. One occasional overrun (a cold first call) does not switch it off. Also after
     * {@link #MAX_FAILURES} calls in a row that threw.
     */
    public static final int  WINDOW_CALLS     = 100;
    public static final int  MAX_OVERRUNS     = 10;
    public static final long HARD_LIMIT_NANOS = 20_000_000L;
    public static final int  MAX_FAILURES     = 10;
    public static final long DISABLE_MILLIS   = 5 * 60 * 1000L;

    /**
     * Provider calls that took longer than {@link #BUDGET_NANOS}, calls that threw, and times a provider was switched off.
     */
    public static final AtomicLong slowCalls   = new AtomicLong();
    public static final AtomicLong failedCalls = new AtomicLong();
    public static final AtomicLong disables    = new AtomicLong();

    /**
     * A registered provider and its budget record.
     */
    private static final class Entry
    {
        final IRouteHintProvider provider;
        int  windowCalls         = 0;
        int  windowOverruns      = 0;
        int  failuresInRow       = 0;
        long disabledUntilMillis = 0;
        long lastWarn            = 0;

        Entry(final IRouteHintProvider provider)
        {
            this.provider = provider;
        }

        String name()
        {
            return provider.getClass().getName();
        }

        void warn(final String message, @Nullable final Throwable e)
        {
            final long now = System.currentTimeMillis();
            if (now - lastWarn > 10_000L)
            {
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
            disabledUntilMillis = System.currentTimeMillis() + DISABLE_MILLIS;
            windowCalls = 0;
            windowOverruns = 0;
            failuresInRow = 0;
            disables.incrementAndGet();
            // logged once per switch-off, not rate limited away
            Log.getLogger().warn("Route hint provider " + name() + " switched off for " + DISABLE_MILLIS / 1000 + " s: " + reason);
        }
    }

    private static final List<Entry> PROVIDERS = new CopyOnWriteArrayList<>();

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
        for (final Entry entry : PROVIDERS)
        {
            synchronized (entry)
            {
                if (System.currentTimeMillis() < entry.disabledUntilMillis)
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

        entry.windowCalls++;
        if (took > BUDGET_NANOS)
        {
            slowCalls.incrementAndGet();
            entry.windowOverruns++;
            entry.warn("Route hint provider " + entry.name() + " took " + took / 1000 + " us (budget " + BUDGET_NANOS / 1000 + " us)", null);
        }
        entry.failuresInRow = failed ? entry.failuresInRow + 1 : 0;
        if (took > HARD_LIMIT_NANOS)
        {
            entry.disable("one call took " + took / 1_000_000 + " ms");
        }
        else if (entry.windowOverruns >= MAX_OVERRUNS)
        {
            entry.disable(entry.windowOverruns + " of " + entry.windowCalls + " calls over " + BUDGET_NANOS / 1000 + " us");
        }
        else if (entry.failuresInRow >= MAX_FAILURES)
        {
            entry.disable(entry.failuresInRow + " calls in a row threw");
        }
        else if (entry.windowCalls >= WINDOW_CALLS)
        {
            entry.windowCalls = 0;
            entry.windowOverruns = 0;
        }

        if (failed || answer == null || answer.isEmpty())
        {
            return null;
        }
        final List<BlockPos> copy = new ArrayList<>(Math.min(answer.size(), MAX_WAYPOINTS));
        try
        {
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
        catch (final RuntimeException e)
        {
            failedCalls.incrementAndGet();
            entry.warn("Route hint provider " + entry.name() + " returned a broken list; ignored", e);
            return null;
        }
        return copy.isEmpty() ? null : List.copyOf(copy);
    }
}
