package com.minecolonies.core.entity.ai.workers.util;

import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.entity.pathfinding.SurfaceType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;

/**
 * Finds spots a worker can stand on: a walkable floor below and two free blocks (no collision, no liquid) for the body. Used by the
 * unstuck steps of the structure workers (a spot near a target, a spot at the edge of the work area).
 */
public final class StandSpots
{
    private StandSpots()
    {
    }

    /**
     * @param level the world.
     * @param feet  the block the feet would be in.
     * @return true if a citizen can stand with his feet in this block.
     */
    public static boolean canStandAt(final Level level, final BlockPos feet)
    {
        if (!WorldUtil.isBlockLoaded(level, feet) || !WorldUtil.isInWorldHeight(feet.getY(), level) || !WorldUtil.isInWorldHeight(feet.getY() + 1, level))
        {
            return false;
        }
        final BlockPos floor = feet.below();
        final BlockState floorState = level.getBlockState(floor);
        if (SurfaceType.getSurfaceType(level, floorState, floor) != SurfaceType.WALKABLE && !floorState.is(net.minecraft.world.level.block.Blocks.SCAFFOLDING))
        {
            // (the top of a scaffolding block holds a citizen, the path planner climbs onto it)
            return false;
        }
        return isFree(level, feet) && isFree(level, feet.above());
    }

    public static boolean isFree(final Level level, final BlockPos pos)
    {
        final BlockState state = level.getBlockState(pos);
        return state.getFluidState().isEmpty() && state.getCollisionShape(level, pos, CollisionContext.empty()).isEmpty();
    }

    /**
     * The standable spot nearest to a position: searches columns around it in growing squares and, in each column, the heights from a little above
     * to well below the position (the first standable spot from the top, so roofs win over caves).
     *
     * @param level     the world.
     * @param around    the position.
     * @param radius    how far around it (blocks, horizontally).
     * @param below     how many blocks below it to look.
     * @param above     how many blocks above it to look.
     * @param accept    extra condition for a spot (for example not inside a box), or null.
     * @return the feet position of the spot, or null.
     */
    @Nullable
    public static BlockPos nearest(final Level level, final BlockPos around, final int radius, final int below, final int above, @Nullable final Predicate<BlockPos> accept)
    {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int r = 0; r <= radius; r++)
        {
            for (int dx = -r; dx <= r; dx++)
            {
                for (int dz = -r; dz <= r; dz++)
                {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r)
                    {
                        continue;
                    }
                    for (int dy = above; dy >= -below; dy--)
                    {
                        final BlockPos candidate = around.offset(dx, dy, dz);
                        if (canStandAt(level, candidate) && (accept == null || accept.test(candidate)))
                        {
                            final double dist = candidate.distSqr(around);
                            if (dist < bestDist)
                            {
                                bestDist = dist;
                                best = candidate;
                            }
                            break;
                        }
                    }
                }
            }
            if (best != null && r + 1 > Math.sqrt(bestDist))
            {
                // every spot of the next ring is at least r + 1 blocks away horizontally, so none of them is nearer
                return best;
            }
        }
        return best;
    }

    private static boolean climbable(final Level level, final BlockPos pos)
    {
        return WorldUtil.isBlockLoaded(level, pos) && level.getBlockState(pos).is(BlockTags.CLIMBABLE);
    }

    /**
     * Everything a citizen can walk to from a position without breaking or placing anything: standing spots (steps up of one block, steps down of
     * one block) and the cells of climbable blocks (scaffolding, ladders) with the spots on top of them and next to them. A rough model of the
     * path planner, to tell the spots that are cut off (inside a closed wall, on top of a wall, behind a drop) from the ones that can be walked to.
     *
     * @param level    the world.
     * @param start    where the citizen is.
     * @param radius   how far (blocks, horizontally) the search goes.
     * @param maxNodes the most positions looked at.
     * @return the feet positions that can be reached, including the start.
     */
    public static Set<BlockPos> reachableFrom(final Level level, final BlockPos start, final int radius, final int maxNodes)
    {
        final Set<BlockPos> seen = new HashSet<>();
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty() && seen.size() < maxNodes)
        {
            final BlockPos at = queue.poll();
            if (climbable(level, at))
            {
                visit(level, at.above(), start, radius, seen, queue, climbable(level, at.above()) || canStandAt(level, at.above()));
                visit(level, at.below(), start, radius, seen, queue, climbable(level, at.below()) || canStandAt(level, at.below()));
                for (final Direction dir : Direction.Plane.HORIZONTAL)
                {
                    final BlockPos near = at.relative(dir);
                    visit(level, near, start, radius, seen, queue, climbable(level, near) || canStandAt(level, near));
                }
                continue;
            }
            for (final Direction dir : Direction.Plane.HORIZONTAL)
            {
                final BlockPos near = at.relative(dir);
                if (climbable(level, near) || canStandAt(level, near))
                {
                    visit(level, near, start, radius, seen, queue, true);
                }
                else if (canStandAt(level, near.above()) && isFree(level, at.above(2)))
                {
                    visit(level, near.above(), start, radius, seen, queue, true);
                }
                else if (swimmable(level, near))
                {
                    visit(level, near, start, radius, seen, queue, true);
                }
                else if (isFree(level, near))
                {
                    // a step down (or a short drop, the paths of citizens allow drops, six are taken here)
                    for (int drop = 1; drop <= 6 && isFree(level, near.below(drop - 1)); drop++)
                    {
                        if (canStandAt(level, near.below(drop)))
                        {
                            visit(level, near.below(drop), start, radius, seen, queue, true);
                            break;
                        }
                    }
                }
            }
            // a climbable block under the feet or above them is a way down or up
            if (climbable(level, at.above()))
            {
                visit(level, at.above(), start, radius, seen, queue, true);
            }
            if (climbable(level, at.below()))
            {
                visit(level, at.below(), start, radius, seen, queue, true);
            }
        }
        return seen;
    }

    /**
     * Water the citizen swims in: water at the feet and room for the head.
     */
    private static boolean swimmable(final Level level, final BlockPos feet)
    {
        return WorldUtil.isBlockLoaded(level, feet) && level.getFluidState(feet).is(net.minecraft.tags.FluidTags.WATER)
                 && level.getBlockState(feet.above()).getCollisionShape(level, feet.above(), CollisionContext.empty()).isEmpty();
    }

    private static void visit(final Level level, final BlockPos pos, final BlockPos start, final int radius, final Set<BlockPos> seen, final ArrayDeque<BlockPos> queue, final boolean allowed)
    {
        if (!allowed || Math.abs(pos.getX() - start.getX()) > radius || Math.abs(pos.getZ() - start.getZ()) > radius || Math.abs(pos.getY() - start.getY()) > radius * 2)
        {
            return;
        }
        if (seen.add(pos))
        {
            queue.add(pos);
        }
    }
}
