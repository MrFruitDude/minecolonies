package com.minecolonies.core.colony.storage;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.pathfinding.RouteHints;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.entity.pathfinding.PathingOptions;
import com.minecolonies.core.util.WorkerUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Courier dispatch over roads (RS4): the walk from a warehouse to a building as a road-aware estimate. A block on a road
 * (the {@code minecolonies:pathblocks} tag) costs {@code 1 / ROAD_SPEED} of an off-road block, as citizens walk faster
 * there; off-road the straight line counts {@link DispatchCost#PATH_FACTOR} longer for corners. The estimate is the cheaper
 * of the straight line and the route an add-on suggests along its roads ({@link RouteHints}), both sampled block by block
 * on loaded chunks only. Without roads the result equals the plain straight-line estimate.
 * <p>
 * Cached per (warehouse, building) for {@link #MAX_AGE} ticks; a road block placed, broken or made with a tool in the
 * level, or {@link #invalidate(Level)} (an add-on's road network changed), drops the cache of that level. Bounded: at most
 * {@link #MAX_ENTRIES} pairs per level, {@link #MAX_SAMPLES} samples per estimate and {@link #FRESH_PER_TICK} fresh
 * estimates per server tick (the rest use the straight line until a later tick). Server thread only.
 */
public final class DispatchPaths
{
    /**
     * Ticks a cached estimate is used (blocks placed without an event, terrain changes).
     */
    public static final long MAX_AGE = 2400L;

    /**
     * Pairs cached per level, oldest dropped first.
     */
    public static final int MAX_ENTRIES = 512;

    /**
     * Samples per estimate (straight line and route together); a longer walk is sampled more coarsely.
     */
    public static final int MAX_SAMPLES = 1024;

    /**
     * Estimates computed per server tick, all levels together.
     */
    public static final int FRESH_PER_TICK = 8;

    private static final Map<ResourceKey<Level>, LevelCache> LEVELS = new HashMap<>();

    private static long tick = Long.MIN_VALUE;
    private static int  freshThisTick;

    private record Key(long from, long to)
    {
    }

    private record Cached(double factor, long at)
    {
    }

    private static final class LevelCache
    {
        final LinkedHashMap<Key, Cached> entries = new LinkedHashMap<>(64, 0.75f, true)
        {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<Key, Cached> eldest)
            {
                return size() > MAX_ENTRIES;
            }
        };
        long revision;
    }

    private DispatchPaths()
    {
    }

    /**
     * Walking time from a warehouse to a building, road-aware (see the class comment).
     *
     * @param colony the colony of the warehouse, null for the straight line.
     * @param from   the warehouse.
     * @param to     the building.
     * @return ticks.
     */
    public static int travelTicks(@Nullable final IColony colony, @NotNull final BlockPos from, @NotNull final BlockPos to)
    {
        final double factor = colony != null && colony.getWorld() instanceof final ServerLevel level ? factor(level, colony, from, to) : DispatchCost.PATH_FACTOR;
        return (int) Math.ceil(BlockPosUtil.getDistance(from, to) * factor / DispatchCost.BLOCKS_PER_TICK);
    }

    /**
     * The walk's cost per straight-line block: {@link DispatchCost#PATH_FACTOR} off road, less the more of it is on roads.
     *
     * @param level  the level.
     * @param colony the colony, for the route hints.
     * @param from   the start.
     * @param to     the destination.
     * @return the factor.
     */
    public static synchronized double factor(@NotNull final ServerLevel level, @Nullable final IColony colony, @NotNull final BlockPos from, @NotNull final BlockPos to)
    {
        final double straight = BlockPosUtil.getDistance(from, to);
        if (straight < 1.0D)
        {
            return DispatchCost.PATH_FACTOR;
        }
        final LevelCache cache = LEVELS.computeIfAbsent(level.dimension(), k -> new LevelCache());
        final Key key = new Key(from.asLong(), to.asLong());
        final long now = level.getGameTime();
        final Cached cached = cache.entries.get(key);
        if (cached != null && now - cached.at() <= MAX_AGE && now >= cached.at())
        {
            return cached.factor();
        }
        final long serverTick = level.getServer() == null ? now : level.getServer().getTickCount();
        if (serverTick != tick)
        {
            tick = serverTick;
            freshThisTick = 0;
        }
        if (freshThisTick >= FRESH_PER_TICK)
        {
            return cached != null ? cached.factor() : DispatchCost.PATH_FACTOR;
        }
        freshThisTick++;
        final long revision = cache.revision;
        final double line = cost(level, List.of(from, to));
        double best = Double.isNaN(line) ? DispatchCost.PATH_FACTOR : Math.min(DispatchCost.PATH_FACTOR, line / straight);
        boolean complete = true;
        if (RouteHints.hasProviders() && straight > RouteHints.MIN_DISTANCE)
        {
            if (RouteHints.admit(level, colony))
            {
                final List<BlockPos> waypoints = RouteHints.query(level, colony, from, to);
                if (waypoints != null && !waypoints.isEmpty())
                {
                    final List<BlockPos> route = new java.util.ArrayList<>(waypoints.size() + 2);
                    route.add(from);
                    route.addAll(waypoints);
                    route.add(to);
                    final double viaRoads = cost(level, route);
                    if (!Double.isNaN(viaRoads))
                    {
                        best = Math.min(best, viaRoads / straight);
                    }
                }
            }
            else
            {
                // no hint turn this tick: use the answer, do not keep it
                complete = false;
            }
        }
        if (complete && revision == cache.revision)
        {
            cache.entries.put(key, new Cached(best, now));
        }
        return best;
    }

    /**
     * The cost of walking a polyline: each sampled block costs 1 on a road, {@link DispatchCost#PATH_FACTOR} off it (scaled so
     * that walking on a road counts {@link PathingOptions#ROAD_SPEED} times faster). Unloaded samples count as off road.
     *
     * @return the cost, NaN when no sample is on a road (the straight-line estimate stays exact then).
     */
    private static double cost(final ServerLevel level, final List<BlockPos> points)
    {
        double length = 0.0D;
        for (int i = 1; i < points.size(); i++)
        {
            length += BlockPosUtil.getDistance(points.get(i - 1), points.get(i));
        }
        if (length <= 0.0D)
        {
            return Double.NaN;
        }
        boolean anyRoad = false;
        final double step = Math.max(1.0D, length / MAX_SAMPLES);
        final double road = DispatchCost.PATH_FACTOR / PathingOptions.ROAD_SPEED;
        double total = 0.0D;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 1; i < points.size(); i++)
        {
            final BlockPos a = points.get(i - 1);
            final BlockPos b = points.get(i);
            final double leg = BlockPosUtil.getDistance(a, b);
            final int samples = Math.max(1, (int) Math.ceil(leg / step));
            int onRoad = 0;
            for (int s = 0; s < samples; s++)
            {
                final double t = (s + 0.5D) / samples;
                pos.set(Math.floor(a.getX() + (b.getX() - a.getX()) * t), Math.floor(a.getY() + (b.getY() - a.getY()) * t), Math.floor(a.getZ() + (b.getZ() - a.getZ()) * t));
                if (onRoad(level, pos))
                {
                    onRoad++;
                }
            }
            total += leg * (DispatchCost.PATH_FACTOR - (DispatchCost.PATH_FACTOR - road) * onRoad / samples);
            anyRoad |= onRoad > 0;
        }
        return anyRoad ? total : Double.NaN;
    }

    /**
     * Whether a road block is the floor at a sampled column: the block below the walking height, the walking height or two below (slopes between
     * waypoints).
     */
    private static boolean onRoad(final ServerLevel level, final BlockPos.MutableBlockPos pos)
    {
        if (!WorldUtil.isBlockLoaded(level, pos))
        {
            return false;
        }
        final int y = pos.getY();
        for (final int dy : new int[] {-1, 0, -2})
        {
            pos.setY(y + dy);
            if (WorkerUtil.isPathBlock(level.getBlockState(pos).getBlock()))
            {
                pos.setY(y);
                return true;
            }
        }
        pos.setY(y);
        return false;
    }

    /**
     * Drops the cached estimates of a level: its roads changed. Call when an add-on's road network changes.
     *
     * @param level the level.
     */
    public static synchronized void invalidate(@NotNull final Level level)
    {
        final LevelCache cache = LEVELS.get(level.dimension());
        if (cache != null)
        {
            cache.entries.clear();
            cache.revision++;
        }
    }

    /**
     * Drops every cached estimate (server stop, tests).
     */
    public static synchronized void clear()
    {
        LEVELS.clear();
    }

    /**
     * @param level a level.
     * @return the cached pairs of the level (for the tests).
     */
    public static synchronized int cachedPairs(@NotNull final Level level)
    {
        final LevelCache cache = LEVELS.get(level.dimension());
        return cache == null ? 0 : cache.entries.size();
    }

    private static void changed(final LevelAccessor accessor, @Nullable final BlockState before, @Nullable final BlockState after)
    {
        if (accessor instanceof final Level level && !level.isClientSide()
              && (before != null && WorkerUtil.isPathBlock(before.getBlock()) || after != null && WorkerUtil.isPathBlock(after.getBlock())))
        {
            invalidate(level);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(final ServerStoppedEvent event)
    {
        clear();
    }

    @SubscribeEvent
    public static void onBreak(final BreakBlockEvent event)
    {
        changed(event.getLevel(), event.getState(), null);
    }

    @SubscribeEvent
    public static void onPlace(final BlockEvent.EntityPlaceEvent event)
    {
        changed(event.getLevel(), event.getBlockSnapshot().getState(), event.getPlacedBlock());
    }

    @SubscribeEvent
    public static void onToolModification(final BlockEvent.BlockToolModificationEvent event)
    {
        if (!event.isSimulated())
        {
            changed(event.getLevel(), event.getState(), event.getFinalState());
        }
    }
}
