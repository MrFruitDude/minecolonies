package com.minecolonies.core.entity.ai.workers.util;

import com.minecolonies.api.MinecoloniesAPIProxy;
import com.minecolonies.api.colony.WorkerWalking;

/**
 * The worker efficiency settings (server config categories {@code workers} and {@code builders}), read at the call site every time so a
 * change in the config takes effect at once. A value that is not a finite number in its range is treated as the default, so a broken config
 * cannot stall a colony. Without a loaded config (unit tests) the defaults apply.
 * <p>
 * Worker pace is the product of two independent multipliers: the colony pace of another mod ({@code IColonyPaceProvider.work}, for example
 * Colony Command's pace) and {@link #speedMultiplier()}. Builders place and break with an additional {@link #builderSpeed()}, which divides
 * the per-block delay and does not touch the other workers.
 */
public final class WorkerSettings
{
    private WorkerSettings()
    {
    }

    public static final double DEFAULT_SPEED         = 1.5D;
    public static final double DEFAULT_BUILDER_SPEED = 1.5D;
    public static final double DEFAULT_HOURS         = 12.6D;
    public static final int    DEFAULT_MIN_BLOCKS    = 48;

    /**
     * Game test overrides; null means the config decides.
     */
    private static volatile Double        speedOverride;
    private static volatile Double        builderSpeedOverride;
    private static volatile Double        hoursOverride;
    private static volatile WorkerWalking walkingOverride;
    private static volatile Integer       minBlocksOverride;
    private static volatile Boolean       fastClearOverride;
    private static volatile Double        reachOverride;
    private static volatile Boolean       physicalReachOverride;
    private static volatile Boolean       scaffoldingOverride;
    private static volatile Integer       scaffoldStockOverride;
    /** The worker (entity id) whose ladder teleport a test switched off, or -1. Scoped to one worker: a failed test that never resets it does not touch
     * the workers of later tests (c8 launch: a global flag held a builder in labour_early_grace). */
    private static volatile int           teleportOffFor = -1;
    /** The builder (entity id) for whom a test made the wait for a scaffolding tower endless, or -1. Scoped to one worker like {@link #teleportOffFor}. */
    private static volatile int           towerWaitEndlessFor = -1;

    /**
     * Only for tests: overrides the config. Null values go back to the config.
     */
    public static void overrideForTests(
      final Double speed,
      final Double builderSpeed,
      final Double hours,
      final WorkerWalking walking,
      final Integer minBlocks,
      final Boolean fastClear)
    {
        speedOverride = speed;
        builderSpeedOverride = builderSpeed;
        hoursOverride = hours;
        walkingOverride = walking;
        minBlocksOverride = minBlocks;
        fastClearOverride = fastClear;
    }

    /**
     * Only for tests: switches the unstuck ladder's teleport step off for one worker (entity id; -1 for none).
     */
    public static void overrideTeleportForTests(final int entityId)
    {
        teleportOffFor = entityId;
    }

    /**
     * @return whether a test switched the unstuck ladder's teleport step off for this worker.
     */
    public static boolean teleportOffForTests(final int entityId)
    {
        return entityId >= 0 && entityId == teleportOffFor;
    }

    /**
     * Only for tests: the builder's wait for a scaffolding tower (6000 ticks) never ends for one worker (entity id; -1 for none), so only the 13th return of
     * a block he cannot get to places it the default way (builder review c5 C5-1).
     */
    public static void overrideTowerWaitForTests(final int entityId)
    {
        towerWaitEndlessFor = entityId;
    }

    /**
     * @return whether a test made the wait for a scaffolding tower endless for this worker.
     */
    public static boolean towerWaitEndlessForTests(final int entityId)
    {
        return entityId >= 0 && entityId == towerWaitEndlessFor;
    }

    public static void clearOverrides()
    {
        teleportOffFor = -1;
        towerWaitEndlessFor = -1;
        overrideForTests(null, null, null, null, null, null);
        overrideReachForTests(null, null, null, null);
    }

    /**
     * Only for tests: overrides the reach and scaffolding settings. Null values go back to the config.
     */
    public static void overrideReachForTests(final Double reach, final Boolean physicalReach, final Boolean scaffolding, final Integer scaffoldStock)
    {
        reachOverride = reach;
        physicalReachOverride = physicalReach;
        scaffoldingOverride = scaffolding;
        scaffoldStockOverride = scaffoldStock;
    }

    /**
     * The pace of a worker's waits: the colony pace of another mod times the worker speed of the settings. A value that is not a finite number
     * above zero counts as 1.0, like the pace of a provider.
     *
     * @param providerPace the colony pace of {@code IColonyPaceProvider.work}.
     * @param speed        the worker speed multiplier of the settings.
     * @return the pace.
     */
    public static double composePace(final double providerPace, final double speed)
    {
        final double provider = Double.isFinite(providerPace) && providerPace > 0.0D ? providerPace : 1.0D;
        final double setting = Double.isFinite(speed) && speed > 0.0D ? speed : 1.0D;
        return provider * setting;
    }

    /**
     * @return the worker speed multiplier, 0.5 to 4, default 1.5.
     */
    public static double speedMultiplier()
    {
        final Double override = speedOverride;
        if (override != null)
        {
            return sane(override, 0.5D, 4.0D, DEFAULT_SPEED);
        }
        try
        {
            return sane(MinecoloniesAPIProxy.getInstance().getConfig().getServer().workerSpeedMultiplier.get(), 0.5D, 4.0D, DEFAULT_SPEED);
        }
        catch (final RuntimeException | NoClassDefFoundError e)
        {
            return DEFAULT_SPEED;
        }
    }

    /**
     * @return the builder speed multiplier for placing and breaking, 0.5 to 4, default 1.5.
     */
    public static double builderSpeed()
    {
        final Double override = builderSpeedOverride;
        if (override != null)
        {
            return sane(override, 0.5D, 4.0D, DEFAULT_BUILDER_SPEED);
        }
        try
        {
            return sane(MinecoloniesAPIProxy.getInstance().getConfig().getServer().builderSpeedMultiplier.get(), 0.5D, 4.0D, DEFAULT_BUILDER_SPEED);
        }
        catch (final RuntimeException | NoClassDefFoundError e)
        {
            return DEFAULT_BUILDER_SPEED;
        }
    }

    /**
     * @return the length of the work shift in hours of the 24 hour day, 6 to 22, default 12.6 (until tick 12600).
     */
    public static double hoursPerDay()
    {
        final Double override = hoursOverride;
        if (override != null)
        {
            return sane(override, 6.0D, 22.0D, DEFAULT_HOURS);
        }
        try
        {
            return sane(MinecoloniesAPIProxy.getInstance().getConfig().getServer().workerHoursPerDay.get(), 6.0D, 22.0D, DEFAULT_HOURS);
        }
        catch (final RuntimeException | NoClassDefFoundError e)
        {
            return DEFAULT_HOURS;
        }
    }

    /**
     * @return the tick of the day (0 to 23999) at which the work shift ends and workers start to go home to sleep.
     */
    public static int nightStart()
    {
        return nightStartOf(hoursPerDay());
    }

    /**
     * The end of the day's work for a citizen: the shift length of the settings for workers, the usual 12600 for the others.
     *
     * @param hasJob whether the citizen has a job.
     * @return the tick of the day at which he starts to go home to sleep.
     */
    public static int nightStartFor(final boolean hasJob)
    {
        return hasJob ? nightStart() : com.minecolonies.api.util.constant.CitizenConstants.NIGHT;
    }

    /**
     * @param hours the shift length in hours.
     * @return the tick of the day at which the shift ends.
     */
    public static int nightStartOf(final double hours)
    {
        return (int) Math.round(sane(hours, 6.0D, 22.0D, DEFAULT_HOURS) * 1000.0D);
    }

    public static WorkerWalking abstractWalking()
    {
        final WorkerWalking override = walkingOverride;
        if (override != null)
        {
            return override;
        }
        try
        {
            return MinecoloniesAPIProxy.getInstance().getConfig().getServer().workerAbstractWalking.get();
        }
        catch (final RuntimeException | NoClassDefFoundError e)
        {
            return WorkerWalking.LONG_ONLY;
        }
    }

    /**
     * @return walks longer than this many blocks are abstracted in the LONG_ONLY mode.
     */
    public static int abstractWalkingMinBlocks()
    {
        final Integer override = minBlocksOverride;
        if (override != null)
        {
            return override;
        }
        try
        {
            return MinecoloniesAPIProxy.getInstance().getConfig().getServer().workerAbstractWalkingMinBlocks.get();
        }
        catch (final RuntimeException | NoClassDefFoundError e)
        {
            return DEFAULT_MIN_BLOCKS;
        }
    }

    /**
     * @return whether the clear stage leaves blocks alone that already are what the blueprint wants (default on).
     */
    public static boolean fastClear()
    {
        final Boolean override = fastClearOverride;
        if (override != null)
        {
            return override;
        }
        try
        {
            return MinecoloniesAPIProxy.getInstance().getConfig().getServer().builderFastClear.get();
        }
        catch (final RuntimeException | NoClassDefFoundError e)
        {
            return true;
        }
    }

    public static final double DEFAULT_REACH         = 5.0D;
    public static final int    DEFAULT_SCAFFOLD_STOCK = 32;

    /**
     * @return how far (blocks, 3D, from the eye) a builder places and breaks blocks, 2 to 16, default 5.
     */
    public static double reach()
    {
        final Double override = reachOverride;
        if (override != null)
        {
            return sane(override, 2.0D, 16.0D, DEFAULT_REACH);
        }
        try
        {
            return sane(MinecoloniesAPIProxy.getInstance().getConfig().getServer().builderReach.get(), 2.0D, 16.0D, DEFAULT_REACH);
        }
        catch (final RuntimeException | NoClassDefFoundError e)
        {
            return DEFAULT_REACH;
        }
    }

    /**
     * @return whether builders need the physical 3D reach (default on). Off: the old horizontal test.
     */
    public static boolean physicalReach()
    {
        final Boolean override = physicalReachOverride;
        if (override != null)
        {
            return override;
        }
        try
        {
            return MinecoloniesAPIProxy.getInstance().getConfig().getServer().builderPhysicalReach.get();
        }
        catch (final RuntimeException | NoClassDefFoundError e)
        {
            return true;
        }
    }

    /**
     * @return whether builders put up scaffolding for blocks out of reach (default on, needs the physical reach).
     */
    public static boolean scaffolding()
    {
        final Boolean override = scaffoldingOverride;
        if (override != null)
        {
            return override;
        }
        try
        {
            return MinecoloniesAPIProxy.getInstance().getConfig().getServer().builderScaffolding.get();
        }
        catch (final RuntimeException | NoClassDefFoundError e)
        {
            return true;
        }
    }

    /**
     * @return how many scaffolding items a builder keeps in stock, 8 to 128, default 32.
     */
    public static int scaffoldStock()
    {
        final Integer override = scaffoldStockOverride;
        if (override != null)
        {
            return Math.max(1, Math.min(128, override));
        }
        try
        {
            return Math.max(8, Math.min(128, MinecoloniesAPIProxy.getInstance().getConfig().getServer().builderScaffoldingStock.get()));
        }
        catch (final RuntimeException | NoClassDefFoundError e)
        {
            return DEFAULT_SCAFFOLD_STOCK;
        }
    }

    private static double sane(final double value, final double min, final double max, final double fallback)
    {
        if (!Double.isFinite(value))
        {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
