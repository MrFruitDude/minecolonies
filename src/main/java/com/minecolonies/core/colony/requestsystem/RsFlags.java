package com.minecolonies.core.colony.requestsystem;

import com.minecolonies.api.MinecoloniesAPIProxy;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

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

    @Nullable
    private static volatile Boolean courierImmediateOverride;

    @Nullable
    private static volatile Boolean storageNetworkOverride;

    @Nullable
    private static volatile Integer serviceRangeOverride;

    @Nullable
    private static volatile Integer itemCapOverride;

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
     * RS4: a courier that finished a task decides on the next one at once (the decision delay is skipped).
     *
     * @return true when couriers do not idle for the decision delay after every task.
     */
    public static boolean courierImmediateNextTask()
    {
        final Boolean override = courierImmediateOverride;
        if (override != null)
        {
            return override;
        }
        final Boolean env = fromEnvironment("MINECOLONIES_RS_COURIER_IMMEDIATE");
        if (env != null)
        {
            return env;
        }
        try
        {
            return MinecoloniesAPIProxy.getInstance().getConfig().getServer().courierImmediateNextTask.get();
        }
        catch (final RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    /**
     * Global storage network: all warehouses are one stock, any courier serves any warehouse in range, sources and drop-offs
     * are chosen by estimated completion time.
     *
     * @return true when the network is on.
     */
    public static boolean storageNetwork()
    {
        final Boolean override = storageNetworkOverride;
        if (override != null)
        {
            return override;
        }
        final Boolean env = fromEnvironment("MINECOLONIES_RS_STORAGENETWORK");
        if (env != null)
        {
            return env;
        }
        try
        {
            return MinecoloniesAPIProxy.getInstance().getConfig().getServer().storageNetwork.get();
        }
        catch (final RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    /**
     * @return how far from its own warehouse (blocks) a courier serves other warehouses; 0 = the whole colony.
     */
    public static int storageServiceRange()
    {
        final Integer override = serviceRangeOverride;
        if (override != null)
        {
            return override;
        }
        try
        {
            return MinecoloniesAPIProxy.getInstance().getConfig().getServer().storageServiceRange.get();
        }
        catch (final RuntimeException | LinkageError e)
        {
            return 0;
        }
    }

    /**
     * @return the soft cap per item type over the whole colony; 0 = off.
     */
    public static int storageItemCap()
    {
        final Integer override = itemCapOverride;
        if (override != null)
        {
            return override;
        }
        try
        {
            return MinecoloniesAPIProxy.getInstance().getConfig().getServer().storageItemCap.get();
        }
        catch (final RuntimeException | LinkageError e)
        {
            return 0;
        }
    }

    /**
     * A switch forced from the environment (benchmark and regression runs of the whole test suite with a phase off).
     */
    @Nullable
    private static Boolean fromEnvironment(final String name)
    {
        // The environment does not change while the game runs, and the flags are asked for on every request and courier decision:
        // System.getenv allocates per call.
        return ENVIRONMENT.computeIfAbsent(name, key -> {
            final String value = System.getenv(key);
            return value == null || value.isBlank() ? Optional.<Boolean>empty() : Optional.of(Boolean.valueOf(value.trim()));
        }).orElse(null);
    }

    private static final java.util.Map<String, Optional<Boolean>> ENVIRONMENT = new java.util.concurrent.ConcurrentHashMap<>();

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

    /**
     * Forces the immediate-next-task switch, for tests. Null returns to the config value.
     *
     * @param value the forced value.
     */
    public static void overrideCourierImmediateNextTask(@Nullable final Boolean value)
    {
        courierImmediateOverride = value;
    }

    /**
     * Forces the storage-network switch, for tests. Null returns to the config value.
     *
     * @param value the forced value.
     */
    public static void overrideStorageNetwork(@Nullable final Boolean value)
    {
        storageNetworkOverride = value;
    }

    /**
     * Forces the courier service range, for tests. Null returns to the config value.
     *
     * @param value the forced range in blocks.
     */
    public static void overrideStorageServiceRange(@Nullable final Integer value)
    {
        serviceRangeOverride = value;
    }

    /**
     * Forces the per-item cap, for tests. Null returns to the config value.
     *
     * @param value the forced cap.
     */
    public static void overrideStorageItemCap(@Nullable final Integer value)
    {
        itemCapOverride = value;
    }
}
