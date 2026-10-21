package com.minecolonies.api.pathfinding;

import com.minecolonies.api.colony.IColony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Suggests waypoints for a citizen's long walk, for example along a road network. Register with
 * {@link RouteHints#register(IRouteHintProvider)}.
 * <p>
 * Contract:
 * <ul>
 * <li>Called on the server thread only, when a citizen starts a walk to a destination more than
 * {@link RouteHints#MIN_DISTANCE} blocks away (straight line), once per new destination, not every tick.</li>
 * <li>Must return within {@link RouteHints#BUDGET_NANOS} (1 ms); slower calls are counted and logged. Work from a
 * snapshot prepared beforehand, not from a search done here. A provider that is slow again and again
 * ({@link RouteHints#MAX_OVERRUNS} overruns within {@link RouteHints#WINDOW_CALLS} calls) or once very slow (over
 * {@link RouteHints#HARD_LIMIT_NANOS}, 20 ms) is switched off for {@link RouteHints#DISABLE_MILLIS} (5 minutes),
 * logged once. A single occasional overrun is tolerated.</li>
 * <li>Must not load chunks: no world reads of positions whose chunk may be unloaded (no {@code getBlockState},
 * {@code getChunk}, {@code getHeight} on arbitrary positions).</li>
 * <li>Waypoints must be inside the build height, with at most 96 blocks (straight line) from the start to the first
 * and between waypoints; otherwise the answer is not used.</li>
 * <li>Must only describe the given colony in the given level. Two colonies in one world, or one colony's citizens in
 * another dimension, must never get each other's routes.</li>
 * <li>Must not throw. Anything thrown (except a VirtualMachineError) is caught, logged at most every 10 s per provider,
 * and treated as {@code null}; {@link RouteHints#MAX_FAILURES} throws in a row switch the provider off like slowness.</li>
 * </ul>
 * How the result is used: the citizen walks to each waypoint in order, each leg by a normal path search, then from the
 * last waypoint to the destination by a normal search. A waypoint within 4 blocks of where the citizen stands is
 * skipped. A waypoint should be a standable block (the block above a walkable floor), at most about 48 blocks from the
 * previous one, so each leg is a short search. Do not include the start or the destination. If a leg does not reach its
 * waypoint, the rest of the route is dropped and the citizen searches directly to the destination (no hints for that
 * destination for 1200 ticks). The list is copied; at most {@link RouteHints#MAX_WAYPOINTS} are used.
 */
@FunctionalInterface
public interface IRouteHintProvider
{
    /**
     * Waypoints for a walk.
     *
     * @param level  the level of the walk (the citizen's level).
     * @param colony the walking citizen's colony, null when the walker has none.
     * @param from   where the walk starts (the block the walker stands in).
     * @param to     the destination.
     * @return the waypoints in walking order, or null (or an empty list) to give no hint: the walk is a normal search.
     */
    @Nullable
    List<BlockPos> waypoints(ServerLevel level, @Nullable IColony colony, BlockPos from, BlockPos to);
}
