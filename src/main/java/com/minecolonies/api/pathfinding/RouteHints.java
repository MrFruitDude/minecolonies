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
     * Provider calls that took longer than {@link #BUDGET_NANOS}, and calls that threw.
     */
    public static final AtomicLong slowCalls   = new AtomicLong();
    public static final AtomicLong failedCalls = new AtomicLong();

    private static final List<IRouteHintProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private static volatile long lastWarn = 0;

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
        PROVIDERS.add(provider);
    }

    /**
     * Removes a provider.
     *
     * @param provider the provider.
     * @return true if it was registered.
     */
    public static boolean unregister(@NotNull final IRouteHintProvider provider)
    {
        return PROVIDERS.remove(provider);
    }

    /**
     * @return true if any provider is registered.
     */
    public static boolean hasProviders()
    {
        return !PROVIDERS.isEmpty();
    }

    /**
     * Asks the providers for waypoints, in registration order. Never throws: a provider that throws is skipped. Null
     * entries are dropped. Called by MineColonies' navigation on the server thread.
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
        for (final IRouteHintProvider provider : PROVIDERS)
        {
            final long t0 = System.nanoTime();
            List<BlockPos> answer = null;
            try
            {
                answer = provider.waypoints(level, colony, from.immutable(), to.immutable());
            }
            catch (final Exception | LinkageError e)
            {
                failedCalls.incrementAndGet();
                warn("Route hint provider " + provider.getClass().getName() + " threw; ignored", e);
            }
            final long took = System.nanoTime() - t0;
            if (took > BUDGET_NANOS)
            {
                slowCalls.incrementAndGet();
                warn("Route hint provider " + provider.getClass().getName() + " took " + took / 1000 + " us (budget 1000 us)", null);
            }
            if (answer == null || answer.isEmpty())
            {
                continue;
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
                warn("Route hint provider " + provider.getClass().getName() + " returned a broken list; ignored", e);
                continue;
            }
            if (!copy.isEmpty())
            {
                return List.copyOf(copy);
            }
        }
        return null;
    }

    private static void warn(final String message, @Nullable final Throwable e)
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
}
