package com.minecolonies.core.entity.ai.workers.util;

import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.entity.pathfinding.SurfaceType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

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
        if (SurfaceType.getSurfaceType(level, floorState, floor) != SurfaceType.WALKABLE)
        {
            return false;
        }
        return isFree(level, feet) && isFree(level, feet.above());
    }

    private static boolean isFree(final Level level, final BlockPos pos)
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
}
