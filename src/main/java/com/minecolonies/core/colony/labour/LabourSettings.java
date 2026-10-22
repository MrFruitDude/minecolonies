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

    /** The message a player gets when he would break, pick up or upgrade a hut that still owes its hut item. */
    public static final String HUT_OWED_KEY = "com.minecolonies.coremod.labour.hut_owed";

    private LabourSettings()
    {
    }

    /**
     * Only for tests: forces a value ({@code enabled}, {@code share}, {@code total}, {@code perRequest}, {@code stockpile}, {@code selfSupply}, {@code postMode},
     * {@code coverageMode}, {@code smelt}, {@code pocket}, {@code workScale}, {@code keepTrees}, {@code leaseTicks}, {@code coverageGrace}, {@code graceTicks},
     * {@code supplyMaxTicks}, {@code worldMax}, {@code early}, {@code earlyGraceTicks}, {@code earlyAll}, {@code quarryDepth}). Null removes the
     * override.
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

    /**
     * @return how long a builder's request waits before he fetches it himself: {@link #earlyGraceTicks} while the colony is {@link #early}, then rising
     *   linearly with the built buildings ({@link #earlyEase}) to {@link #supplyGraceTicks()}.
     */
    public static int supplyGraceTicks(final IColony colony)
    {
        return graceFor(earlyEase(colony), earlyGraceTicks(), supplyGraceTicks());
    }

    /**
     * The grace of a colony at the given ease (0 young, 1 grown): from the early grace (never above the normal one) linearly to the normal grace.
     */
    public static int graceFor(final double ease, final int earlyGrace, final int normalGrace)
    {
        final int from = Math.min(earlyGrace, normalGrace);
        final double t = Math.max(0.0D, Math.min(1.0D, ease));
        return (int) Math.round(from + (normalGrace - from) * t);
    }

    /**
     * The labour share of a colony whose share is at its default, at the given ease: every unemployed citizen while young, then down in steps to the default.
     */
    public static double shareFor(final double ease, final double normalShare)
    {
        final double t = Math.max(0.0D, Math.min(1.0D, ease));
        return 1.0D - (1.0D - normalShare) * t;
    }

    /**
     * Round 11 (W2-10): the early pace ends gently. Below {@code limit} built buildings the ease is 0 (the full early pace); from {@code limit} on it rises by
     * 1/{@code limit} per built building, so it is 1 (the normal pace) from {@code 2 * limit - 1} on. A limit of 0 or less switches the early pace off (1).
     */
    public static double easeFor(final int built, final int limit)
    {
        if (limit <= 0)
        {
            return 1.0D;
        }
        if (built < limit)
        {
            return 0.0D;
        }
        return Math.min(1.0D, (built - limit + 1) / (double) limit);
    }

    /**
     * @return how far the colony is from its early pace to the normal one (0 young, 1 grown), from its built buildings ({@link #easeFor}).
     */
    public static double earlyEase(final IColony colony)
    {
        final Object forced = OVERRIDES.get("early");
        if (forced instanceof Boolean b)
        {
            return b ? 0.0D : 1.0D;
        }
        final int limit = earlyBuildings();
        if (limit <= 0)
        {
            return 1.0D;
        }
        try
        {
            int built = 0;
            for (final com.minecolonies.api.colony.buildings.IBuilding building : colony.getServerBuildingManager().getBuildings().values())
            {
                if (building.getBuildingLevel() > 0 && ++built >= 2 * limit)
                {
                    break;
                }
            }
            return easeFor(built, limit);
        }
        catch (final RuntimeException e)
        {
            return 1.0D;
        }
    }

    /**
     * @return whether some of the early pace is still on (the colony is young or easing towards the normal pace).
     */
    public static boolean earlyPace(final IColony colony)
    {
        return earlyEase(colony) < 1.0D;
    }

    /**
     * @return below how many built buildings a colony counts as young ({@link #early}); 0 switches the early pace off.
     */
    public static int earlyBuildings()
    {
        return config(c -> c.labourersEarlyBuildings.get(), 10, "earlyBuildings");
    }

    public static int earlyGraceTicks()
    {
        return config(c -> c.labourersEarlyGraceTicks.get(), 200, "earlyGraceTicks");
    }

    public static boolean earlyAllUnemployed()
    {
        return config(c -> c.labourersEarlyAllUnemployed.get(), true, "earlyAll");
    }

    /**
     * @return whether the colony is young: it has fewer built buildings (level 1 or more) than {@link #earlyBuildings}. A young colony's labour runs at the early pace:
     *   every unemployed citizen may labour ({@link #share}) and a builder's request waits only {@link #earlyGraceTicks}. After that the pace eases to the normal
     *   one ({@link #earlyEase}).
     */
    public static boolean early(final IColony colony)
    {
        return earlyEase(colony) <= 0.0D;
    }

    /**
     * @return whether a hut may be placed from the overview on credit (it owes its hut item, {@link HutDebts}).
     */
    public static boolean hutCredit()
    {
        return config(c -> c.labourersHutCredit.get(), true, "hutCredit");
    }

    /**
     * @return how many layers labour may take off a quarry zone, measured from the zone's surface when it was marked.
     */
    public static int quarryDepth()
    {
        return config(c -> c.labourersQuarryDepth.get(), 8, "quarryDepth");
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

    /** The town hall's labour share when nobody changed it. */
    public static final int DEFAULT_SHARE = 50;

    /**
     * @return whether the early pace raises the colony's labour share now (young or easing colony, share at its default).
     */
    public static boolean earlyShare(final IColony colony)
    {
        return OVERRIDES.get("share") == null && colonyInt(colony, BuildingTownHall.LABOUR_SHARE, DEFAULT_SHARE) == DEFAULT_SHARE && earlyAllUnemployed() && earlyPace(colony);
    }

    /**
     * @return the share (0 to 1) of the unemployed that may labour at once.
     */
    public static double share(final IColony colony)
    {
        final Object forced = OVERRIDES.get("share");
        final int percent = forced instanceof Number n ? n.intValue() : colonyInt(colony, BuildingTownHall.LABOUR_SHARE, DEFAULT_SHARE);
        if (!(forced instanceof Number) && percent == DEFAULT_SHARE && earlyAllUnemployed())
        {
            // a young colony whose manager left the share at its default: every unemployed citizen may labour (the colony cap and their needs still apply), then
            // the share steps down with the built buildings to the default; a share the manager set, 0 included, is kept
            final double ease = earlyEase(colony);
            if (ease < 1.0D)
            {
                return shareFor(ease, DEFAULT_SHARE / 100.0D);
            }
        }
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
