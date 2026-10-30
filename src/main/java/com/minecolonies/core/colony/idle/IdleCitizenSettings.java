package com.minecolonies.core.colony.idle;

import com.minecolonies.api.MinecoloniesAPIProxy;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;

/**
 * The settings of the unemployed citizens' work, read in one place:
 * <ul>
 *   <li>the colony setting {@link BuildingTownHall#IDLE_HELPERS} (town hall, default on) switches it all off for one colony;</li>
 *   <li>the colony setting {@link BuildingTownHall#IDLE_HELPERS_PER_BUILDER} (default 2) is the most idle citizens that help one builder;</li>
 *   <li>the colony setting {@link BuildingTownHall#WORKERS_HELP} (default on): workers with nothing to do help out, workers without a basic tool get one themselves;</li>
 *   <li>the server config {@code builders.collaborationIdleHelperSpeed} (default 0.6) is the pace of an idle helper compared with the builder he helps.</li>
 * </ul>
 * Game tests override them.
 */
public final class IdleCitizenSettings
{
    /**
     * Default of the colony setting.
     */
    public static final boolean DEFAULT_ENABLED = true;

    /**
     * Default of the most idle helpers of one builder.
     */
    public static final int DEFAULT_PER_BUILDER = 2;

    /**
     * Highest value of the per builder setting.
     */
    public static final int MAX_PER_BUILDER = 8;

    /**
     * Default pace of an idle helper (a share of the builder's).
     */
    public static final double DEFAULT_HELPER_SPEED = 0.6D;

    private static volatile Boolean enabledOverride;
    private static volatile Integer perBuilderOverride;
    private static volatile Double  speedOverride;

    private IdleCitizenSettings()
    {
    }

    /**
     * Only for tests: overrides what the colony settings and the config say. Null goes back to them.
     */
    public static void overrideForTests(final Boolean enabled, final Integer perBuilder, final Double speed)
    {
        enabledOverride = enabled;
        perBuilderOverride = perBuilder;
        speedOverride = speed;
    }

    /**
     * @param colony the colony.
     * @return whether unemployed citizens work in this colony.
     */
    public static boolean enabled(final IColony colony)
    {
        final Boolean override = enabledOverride;
        if (override != null)
        {
            return override;
        }
        try
        {
            return colony.getSettings().getSetting(BuildingTownHall.IDLE_HELPERS).getValue();
        }
        catch (final RuntimeException e)
        {
            return DEFAULT_ENABLED;
        }
    }

    /**
     * @param colony the colony.
     * @return the most idle citizens that help one builder, 0 to {@value #MAX_PER_BUILDER}.
     */
    public static int helpersPerBuilder(final IColony colony)
    {
        final Integer override = perBuilderOverride;
        if (override != null)
        {
            return clamp(override);
        }
        try
        {
            return clamp(colony.getSettings().getSetting(BuildingTownHall.IDLE_HELPERS_PER_BUILDER).getValue());
        }
        catch (final RuntimeException e)
        {
            return DEFAULT_PER_BUILDER;
        }
    }

    /**
     * @return the pace of an idle helper as a share of the builder he helps (0.1 to 1.0).
     */
    public static double helperSpeed()
    {
        final Double override = speedOverride;
        if (override != null)
        {
            return Math.max(0.05D, Math.min(1.0D, override));
        }
        try
        {
            return MinecoloniesAPIProxy.getInstance().getConfig().getServer().builderCollaborationIdleHelperSpeed.get();
        }
        catch (final RuntimeException | NoClassDefFoundError e)
        {
            return DEFAULT_HELPER_SPEED;
        }
    }

    private static volatile Boolean workersHelpOverride;

    /**
     * Only for tests: overrides the colony setting {@link BuildingTownHall#WORKERS_HELP}. Null goes back to it.
     */
    public static void overrideWorkersHelpForTests(final Boolean on)
    {
        workersHelpOverride = on;
    }

    /**
     * @param colony the colony.
     * @return whether the colony's workers help themselves and others: a worker without work at his hut takes a nearby labour job, a worker without a basic tool gets
     *   one from storage or makes it (the colony setting {@link BuildingTownHall#WORKERS_HELP}, default on).
     */
    public static boolean workersHelp(final IColony colony)
    {
        final Boolean override = workersHelpOverride;
        if (override != null)
        {
            return override;
        }
        try
        {
            return colony.getSettings().getSetting(BuildingTownHall.WORKERS_HELP).getValue();
        }
        catch (final RuntimeException e)
        {
            return true;
        }
    }

    private static int clamp(final int value)
    {
        return Math.max(0, Math.min(MAX_PER_BUILDER, value));
    }
}
