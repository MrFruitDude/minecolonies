package com.minecolonies.core.colony.labour;

import com.minecolonies.api.MinecoloniesAPIProxy;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.labour.LabourModes;
import com.minecolonies.api.configuration.ServerConfiguration;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The settings of the labourers, read in one place. Two layers:
 * <ul>
 *   <li>the colony settings of the town hall (on or off, the share of the unemployed, the colony cap, the cap per request, stockpile, self-supply), each clamped to
 *   the server config's ceilings;</li>
 *   <li>the server config category {@code labourers} (switches and numbers of the whole world).</li>
 * </ul>
 * The benchmark switches labour off with {@code MINECOLONIES_LABOUR=off}; the game tests override single values with {@link #override}.
 */
public final class LabourSettings
{
    private static final Map<String, Object> OVERRIDES = new ConcurrentHashMap<>();

    private LabourSettings()
    {
    }

    /**
     * Only for tests: forces a value ({@code enabled}, {@code share}, {@code total}, {@code perRequest}, {@code stockpile}, {@code selfSupply}, {@code postMode},
     * {@code coverageMode}, {@code smelt}, {@code pocket}, {@code workScale}, {@code keepTrees}, {@code leaseTicks}, {@code coverageGrace}, {@code graceTicks},
     * {@code supplyMaxTicks}, {@code worldMax}). Null removes the override.
     */
    public static void override(final String key, final Object value)
    {
        if (value == null)
        {
            OVERRIDES.remove(key);
        }
        else
        {
            OVERRIDES.put(key, value);
        }
    }

    /**
     * Only for tests: back to the config and the colony settings.
     */
    public static void clearOverrides()
    {
        OVERRIDES.clear();
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(final String key, final Supplier<T> fromConfig, final T fallback)
    {
        final Object forced = OVERRIDES.get(key);
        if (forced != null)
        {
            return (T) forced;
        }
        try
        {
            return fromConfig.get();
        }
        catch (final RuntimeException | NoClassDefFoundError e)
        {
            return fallback;
        }
    }

    private static <T> T config(final Function<ServerConfiguration, T> read, final T fallback, final String key)
    {
        return get(key, () -> read.apply(MinecoloniesAPIProxy.getInstance().getConfig().getServer()), fallback);
    }

    private static boolean benchOff()
    {
        return "off".equalsIgnoreCase(System.getenv("MINECOLONIES_LABOUR"));
    }

    // ------------------------------------------------------------------ world

    /**
     * @return whether labour is on in the world at all.
     */
    public static boolean worldEnabled()
    {
        return !benchOff() && config(c -> c.labourersEnabled.get(), true, "worldEnabled");
    }

    public static int worldMax()
    {
        return config(c -> c.labourersWorldMax.get(), 64, "worldMax");
    }

    public static LabourModes.PostMode postMode()
    {
        return config(c -> c.labourersPostMode.get(), LabourModes.PostMode.ANY_IN_RANGE, "postMode");
    }

    public static int postRange()
    {
        return config(c -> c.labourersPostRange.get(), 64, "postRange");
    }

    /**
     * The gathering radius a new config gets, and the old one (configs written before keep 48 until a server owner changes it: a config value is never rewritten).
     */
    public static final int DEFAULT_GATHER_RADIUS = 96;
    public static final int OLD_GATHER_RADIUS     = 48;
    public static final int MIN_GATHER_RADIUS     = 8;
    /**
     * The widest supported radius (the config's ceiling): the scans read only loaded chunks of the colony's own claim, cached per colony.
     */
    public static final int MAX_GATHER_RADIUS     = 160;

    private static volatile boolean oldRadiusLogged;

    /**
     * @return how far around the town hall labourers look for trees and surface blocks: the config value (the fallback when it cannot be read is the new default),
     * clamped to the supported range. Only loaded chunks inside the colony's claim are ever looked at.
     */
    public static int gatherRadius()
    {
        final int raw = config(c -> c.labourersGatherRadius.get(), DEFAULT_GATHER_RADIUS, "gatherRadius");
        if (raw == OLD_GATHER_RADIUS && !oldRadiusLogged && !OVERRIDES.containsKey("gatherRadius"))
        {
            oldRadiusLogged = true;
            com.minecolonies.api.util.Log.getLogger().info("labourers.gatherRadius is {} (the old default). The new default is {}; set it in the server config to use the wider range.",
              OLD_GATHER_RADIUS, DEFAULT_GATHER_RADIUS);
        }
        return clampRadius(raw);
    }

    /**
     * @return the radius inside the supported range.
     */
    public static int clampRadius(final int raw)
    {
        return Math.max(MIN_GATHER_RADIUS, Math.min(MAX_GATHER_RADIUS, raw));
    }

    public static LabourModes.CoverageMode coverageMode()
    {
        return config(c -> c.labourersCoverageMode.get(), LabourModes.CoverageMode.BUILT, "coverageMode");
    }

    public static int coverageGrace()
    {
        return config(c -> c.labourersCoverageGrace.get(), 2400, "coverageGrace");
    }

    public static int leaseTicks()
    {
        return config(c -> c.labourersLeaseTicks.get(), 2400, "leaseTicks");
    }

    public static boolean pocketCrafting()
    {
        return config(c -> c.labourersPocketCrafting.get(), true, "pocket");
    }

    public static LabourModes.SmeltMode smelt()
    {
        return config(c -> c.labourersSmelt.get(), LabourModes.SmeltMode.BASIC, "smelt");
    }

    public static boolean stoneTools()
    {
        return config(c -> c.labourersStoneTools.get(), true, "stoneTools");
    }

    public static int keepTrees()
    {
        return config(c -> c.labourersKeepTrees.get(), 4, "keepTrees");
    }

    /**
     * @return whether labour may dig sand, dirt, gravel and stone out of the world: automatic labour (requests, stock goals, a builder's own supply) and directed
     *   orders alike. Off by default and when the config cannot be read: a block of the ground that a player placed before placements were recorded cannot be told
     *   from the world's own, so nothing is dug unless the server owner accepts that. A directed order's area does not allow it; with the switch on, a directed order
     *   still digs only in its area.
     */
    public static boolean digUnprovenGround()
    {
        return config(c -> c.labourersDigUnprovenGround.get(), false, "digUnproven");
    }

    private static final java.util.Set<Integer> DIG_FOR_TESTS = ConcurrentHashMap.newKeySet();

    /**
     * @return {@link #digUnprovenGround()} for the colony (a test may allow it for its own colony only).
     */
    public static boolean digUnprovenGround(final IColony colony)
    {
        return digUnprovenGround() || DIG_FOR_TESTS.contains(colony.getID());
    }

    /**
     * Only for tests: lets one colony's automatic labour dig the ground. Per colony, so tests that run side by side (and clear the overrides) do not see it.
     */
    public static void allowDiggingForTest(final int colonyId, final boolean allow)
    {
        if (allow)
        {
            DIG_FOR_TESTS.add(colonyId);
        }
        else
        {
            DIG_FOR_TESTS.remove(colonyId);
        }
    }

    public static int supplyGraceTicks()
    {
        return config(c -> c.labourersSupplyGraceTicks.get(), 1200, "graceTicks");
    }

    public static int supplyMaxTicks()
    {
        return config(c -> c.labourersSupplyMaxTicks.get(), 6000, "supplyMaxTicks");
    }

    /**
     * Only for tests: how much faster than real a labourer works (breaking, crafting).
     */
    public static double workScale()
    {
        final Object forced = OVERRIDES.get("workScale");
        return forced instanceof Number n ? Math.max(0.1D, n.doubleValue()) : 1.0D;
    }

    // ------------------------------------------------------------------ colony

    private static boolean colonyBool(final IColony colony, final com.minecolonies.api.colony.buildings.modules.settings.ISettingKey<com.minecolonies.core.colony.buildings.modules.settings.BoolSetting> key,
      final boolean fallback)
    {
        try
        {
            return colony.getSettings().getSetting(key).getValue();
        }
        catch (final RuntimeException e)
        {
            return fallback;
        }
    }

    private static int colonyInt(final IColony colony, final com.minecolonies.api.colony.buildings.modules.settings.ISettingKey<com.minecolonies.core.colony.buildings.modules.settings.IntSetting> key,
      final int fallback)
    {
        try
        {
            return colony.getSettings().getSetting(key).getValue();
        }
        catch (final RuntimeException e)
        {
            return fallback;
        }
    }

    /**
     * @param colony the colony.
     * @return whether the colony's unemployed citizens labour (world switch, then the colony setting).
     */
    public static boolean enabled(final IColony colony)
    {
        if (!worldEnabled())
        {
            return false;
        }
        final Object forced = OVERRIDES.get("enabled");
        return forced instanceof Boolean b ? b : colonyBool(colony, BuildingTownHall.LABOUR, true);
    }

    /**
     * @return the share (0 to 1) of the unemployed that may labour at once.
     */
    public static double share(final IColony colony)
    {
        final Object forced = OVERRIDES.get("share");
        final int percent = forced instanceof Number n ? n.intValue() : colonyInt(colony, BuildingTownHall.LABOUR_SHARE, 50);
        return Math.max(0, Math.min(100, percent)) / 100.0D;
    }

    /**
     * @return the most labourers of the colony at once (the colony setting under the config's ceiling).
     */
    public static int total(final IColony colony)
    {
        final Object forced = OVERRIDES.get("total");
        final int ceiling = config(c -> c.labourersMaxTotalCeiling.get(), 16, "totalCeiling");
        final int wanted = forced instanceof Number n ? n.intValue() : colonyInt(colony, BuildingTownHall.LABOUR_CAP, 8);
        return Math.max(0, Math.min(ceiling, wanted));
    }

    /**
     * @return the most labourers on one request.
     */
    public static int perRequest(final IColony colony)
    {
        final Object forced = OVERRIDES.get("perRequest");
        return Math.max(1, Math.min(8, forced instanceof Number n ? n.intValue() : colonyInt(colony, BuildingTownHall.LABOUR_PER_REQUEST, 2)));
    }

    public static boolean stockpile(final IColony colony)
    {
        final Object forced = OVERRIDES.get("stockpile");
        return forced instanceof Boolean b ? b : colonyBool(colony, BuildingTownHall.LABOUR_STOCKPILE, true);
    }

    public static boolean selfSupply(final IColony colony)
    {
        final Object forced = OVERRIDES.get("selfSupply");
        return forced instanceof Boolean b ? b : colonyBool(colony, BuildingTownHall.LABOUR_SELF_SUPPLY, true);
    }
}
