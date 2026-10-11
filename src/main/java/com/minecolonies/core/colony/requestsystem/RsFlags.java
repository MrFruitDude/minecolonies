package com.minecolonies.core.colony.requestsystem;

import com.minecolonies.api.MinecoloniesAPIProxy;
import org.jetbrains.annotations.Nullable;

/**
 * The server switches of the Anno-informed request system redesign. Read through here so code that runs before the
 * config is loaded (unit tests, early world load) sees the old behaviour instead of throwing.
 */
public final class RsFlags
{
    @Nullable
    private static volatile Boolean reservationsOverride;

    @Nullable
    private static volatile Boolean smartRetryOverride;

    @Nullable
    private static volatile Boolean priorityClassesOverride;

    private RsFlags()
    {
    }

    /**
     * RS1: the reservation ledger.
     *
     * @return true when stock and space are reserved for planned deliveries.
     */
    public static boolean reservations()
    {
        final Boolean override = reservationsOverride;
        if (override != null)
        {
            return override;
        }
        final Boolean env = fromEnvironment("MINECOLONIES_RS_RESERVATIONS");
        if (env != null)
        {
            return env;
        }
        try
        {
            return MinecoloniesAPIProxy.getInstance().getConfig().getServer().reservations.get();
        }
        catch (final RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    /**
     * RS2: named wait reasons and event-driven re-evaluation.
     *
     * @return true when waiting requests are woken by events and described by a wait reason.
     */
    public static boolean smartRetry()
    {
        final Boolean override = smartRetryOverride;
        if (override != null)
        {
            return override;
        }
        final Boolean env = fromEnvironment("MINECOLONIES_RS_SMARTRETRY");
        if (env != null)
        {
            return env;
        }
        try
        {
            return MinecoloniesAPIProxy.getInstance().getConfig().getServer().smartRetry.get();
        }
        catch (final RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    /**
     * RS3: importance classes, per-building priority and alarm in the courier's choice of task.
     *
     * @return true when the courier picks by (importance, building priority, wait) with distance only as the tiebreak.
     */
    public static boolean priorityClasses()
    {
        final Boolean override = priorityClassesOverride;
        if (override != null)
        {
            return override;
        }
        final Boolean env = fromEnvironment("MINECOLONIES_RS_PRIORITY");
        if (env != null)
        {
            return env;
        }
        try
        {
            return MinecoloniesAPIProxy.getInstance().getConfig().getServer().priorityClasses.get();
        }
        catch (final RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    /**
     * A switch forced from the environment (benchmark and regression runs of the whole test suite with a phase off).
     */
    @Nullable
    private static Boolean fromEnvironment(final String name)
    {
        final String value = System.getenv(name);
        return value == null || value.isBlank() ? null : Boolean.valueOf(value.trim());
    }

    /**
     * Forces the reservation switch, for tests. Null returns to the config value.
     *
     * @param value the forced value.
     */
    public static void overrideReservations(@Nullable final Boolean value)
    {
        reservationsOverride = value;
    }

    /**
     * Forces the smart-retry switch, for tests. Null returns to the config value.
     *
     * @param value the forced value.
     */
    public static void overrideSmartRetry(@Nullable final Boolean value)
    {
        smartRetryOverride = value;
    }

    /**
     * Forces the priority-classes switch, for tests. Null returns to the config value.
     *
     * @param value the forced value.
     */
    public static void overridePriorityClasses(@Nullable final Boolean value)
    {
        priorityClassesOverride = value;
    }
}
