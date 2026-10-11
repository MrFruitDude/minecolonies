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

    public static void clearOverrides()
    {
        overrideForTests(null, null, null, null, null, null);
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

    private static double sane(final double value, final double min, final double max, final double fallback)
    {
        if (!Double.isFinite(value))
        {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
