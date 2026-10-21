package com.minecolonies.core.entity.pathfinding.pathjobs;

import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.api.util.ShapeUtil;
import com.minecolonies.core.entity.pathfinding.MNode;
import com.minecolonies.core.entity.pathfinding.PathfindingUtils;
import com.minecolonies.core.entity.pathfinding.SurfaceType;
import com.minecolonies.core.entity.pathfinding.pathresults.PathResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Job that handles moving to a location.
 */
public class PathJobMoveToLocation extends AbstractPathJob implements IDestinationPathJob
{
    private static final float    DESTINATION_SLACK_NONE     = 0.1F;
    // 1^2 + 1^2 + 1^2 + (epsilon of 0.1F)
    private static final float    DESTINATION_SLACK_ADJACENT = (float) Math.sqrt(2f);
    @NotNull
    protected final BlockPos destination;
    // 0 = exact match
    private              float    destinationSlack           = DESTINATION_SLACK_NONE;

    /**
     * Failed-search cap: a search that has not reached its destination never runs past this many nodes, so
     * {@link #stopOnNodeLimit} may extend the node limit only up to it. Nodes per block of manhattan distance, a floor
     * and a ceiling of twice {@link #MAX_NODES}.
     */
    public static final int FAILED_NODES_PER_BLOCK = 64;
    public static final int FAILED_NODES_MIN       = 2000;

    /**
     * This job's failed-search cap (see {@link #failedSearchCap(int)}), scaled by the node limit config like maxNodes.
     */
    private final int failedNodeCap;

    /**
     * Prepares the PathJob for the path finding system.
     *
     * @param world  world the entity is in.
     * @param start  starting location.
     * @param end    target location.
     * @param range  max search range.
     * @param entity the entity.
     */
    public PathJobMoveToLocation(final Level world, @NotNull final BlockPos start, @NotNull final BlockPos end, final int range, final Mob entity)
    {
        super(world, start, end, new PathResult<PathJobMoveToLocation>(), entity);

        maxNodes += range;
        this.destination = end.immutable();
        this.failedNodeCap = Math.max(maxNodes,
          (int) (failedSearchCap(BlockPosUtil.distManhattan(start, end)) * com.minecolonies.core.MineColonies.getConfig().getServer().pathNodeLimitMultiplier.get()));

        extraNodes = 4;
    }

    /**
     * Perform the search.
     *
     * @return Path of a path to the given location, a best-effort, or null.
     */
    @Nullable
    @Override
    protected Path search()
    {
        //  Compute destination slack - if the destination point cannot be stood in
        if (getGroundHeight(null, destination.getX(), destination.getY(), destination.getZ()) != destination.getY())
        {
            destinationSlack = DESTINATION_SLACK_ADJACENT;
        }

        return super.search();
    }

    @Override
    protected double computeHeuristic(final int x, final int y, final int z)
    {
        return BlockPosUtil.distManhattan(destination, x, y, z);
    }

    @Override
    protected boolean scaleHeuristicOnRoads()
    {
        return true;
    }

    /**
     * Checks if the target has been reached.
     *
     * @param n Node to test.
     * @return true if has been reached.
     */
    @Override
    protected boolean isAtDestination(@NotNull final MNode n)
    {
        boolean atDest = false;
        if (destinationSlack <= DESTINATION_SLACK_NONE)
        {
            atDest = n.x == destination.getX()
                       && n.y == destination.getY()
                       && n.z == destination.getZ();
        }
        else if (n.y == destination.getY() - 1)
        {
            atDest = BlockPosUtil.distSqr(destination, n.x, destination.getY(), n.z) < DESTINATION_SLACK_ADJACENT * DESTINATION_SLACK_ADJACENT;
        }
        else
        {
            atDest = BlockPosUtil.distSqr(destination, n.x, n.y, n.z) < DESTINATION_SLACK_ADJACENT * DESTINATION_SLACK_ADJACENT;
        }

        if (atDest)
        {
            final net.minecraft.world.level.block.state.BlockState floor = cachedBlockLookup.getBlockState(n.x, n.y - 1, n.z);
            // (the top of a block of scaffolding holds a citizen, though the block has no collision for a path: a spot on top of a tower is a destination
            // that is reached, not only approached - a path that only approaches it ends where the recently used end nodes are not penalised)
            atDest = floor.is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)
                       || SurfaceType.getSurfaceType(world, floor, tempWorldPos.set(n.x, n.y - 1, n.z), getPathingOptions()) == SurfaceType.WALKABLE;
        }

        return atDest;
    }

    /**
     * Calculate the distance to the target.
     *
     * @param n Node to test.
     * @return double of the distance.
     */
    @Override
    protected double getEndNodeScore(@NotNull final MNode n)
    {
        if (PathfindingUtils.isLiquid(cachedBlockLookup.getBlockState(n.x, n.y - 1, n.z)))
        {
            return BlockPosUtil.distManhattan(destination, n.x, n.y, n.z) + 30;
        }

        if (!ShapeUtil.isEmpty(cachedBlockLookup.getBlockState(n.x, n.y, n.z).getCollisionShape(cachedBlockLookup, tempWorldPos.set(n.x, n.y, n.z))))
        {
            return BlockPosUtil.distManhattan(destination, n.x, n.y, n.z) + 10;
        }

        //  For Result Score lower is better

        int xDist = Math.abs(destination.getX() - n.x);
        int yDist = Math.abs(destination.getY() - n.y);
        int zDist = Math.abs(destination.getZ() - n.z);
        return xDist + yDist + zDist;
    }

    @Override
    protected boolean stopOnNodeLimit(final int totalNodesVisited, final MNode bestNode, final int nodesSinceEndNode)
    {
        // While the search still finds better end nodes it may go on, but only up to a cap that grows with the distance.
        // (It used to stretch to about 16,000 nodes, plus a random +1000 near the start: a failed search was the most
        // expensive kind.)
        if (nodesSinceEndNode < 200 && totalNodesVisited + 500 <= failedNodeCap)
        {
            maxNodes += 500;
            return false;
        }

        return true;
    }

    /**
     * The node cap of a failed move-to search over the given distance, before the node limit config.
     *
     * @param manhattanDistance start to destination.
     * @return the cap.
     */
    public static int failedSearchCap(final int manhattanDistance)
    {
        return Math.min(MAX_NODES * 2, Math.max(FAILED_NODES_MIN, manhattanDistance * FAILED_NODES_PER_BLOCK));
    }

    /**
     * @return this job's failed-search cap, never below its starting node limit.
     */
    public int getFailedNodeCap()
    {
        return failedNodeCap;
    }

    @Override
    public String toString()
    {
        return super.toString() + " destination:" + destination;
    }

    @Override
    public BlockPos getDestination()
    {
        return destination;
    }

    /**
     * Helper to compare if the given move to location job matches the input parameters (a leg of a hinted route to the
     * position matches too)
     *
     * @return true if the given job is the same
     */
    public static boolean isJobFor(final AbstractPathJob job, final BlockPos desiredPosition)
    {
        if (job != null && job.getRouteTarget() != null)
        {
            // one leg of a hinted route to that destination
            return job.getRouteTarget().equals(desiredPosition);
        }
        if (job instanceof PathJobMoveToLocation pathJob)
        {
            return pathJob.getDestination().equals(desiredPosition);
        }

        return false;
    }
}
