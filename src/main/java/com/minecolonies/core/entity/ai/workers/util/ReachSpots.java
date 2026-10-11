package com.minecolonies.core.entity.ai.workers.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Finds the spots a builder can stand on to reach blocks, and picks the one that serves the most of the blocks he works on next: the spots are
 * ranked by how many of the next blocks (in the order he works on them) are in reach from there without a step, less the way to get there.
 */
public final class ReachSpots
{
    private ReachSpots()
    {
    }

    /**
     * A place to work from.
     *
     * @param feet    the block the feet are in.
     * @param covered how many of the next blocks, in order and without a gap, are in reach from there.
     * @param walk    the cost of getting there from where the builder is (about blocks of walking).
     */
    public record Spot(BlockPos feet, int covered, double walk)
    {
        /**
         * Higher is better: every block served is worth about a block of walking.
         */
        public double utility()
        {
            return covered - walk;
        }
    }

    /**
     * A little less than the full reach, so that a builder who stops a step short of the spot still reaches the first block.
     */
    public static final double MARGIN = 0.4D;

    /**
     * The best spots to work from, best first.
     *
     * @param level     the world.
     * @param blocks    the blocks to work on, the first one is the one that has to be reached (the rest only count for the ranking).
     * @param reach     the reach in blocks.
     * @param eyeHeight the eye height above the feet.
     * @param from      where the builder stands now (for the cost of the way), or null.
     * @param accept    extra condition for a spot (not a known dead end, not in a block that is about to change), or null.
     * @param limit     how many spots at most.
     * @return the spots, possibly none.
     */
    public static List<Spot> best(
      final Level level,
      final List<BlockPos> blocks,
      final double reach,
      final double eyeHeight,
      @Nullable final BlockPos from,
      @Nullable final Predicate<BlockPos> accept,
      final int limit)
    {
        return best(level, blocks, reach, eyeHeight, from, accept, limit, false, false);
    }

    public static List<Spot> best(
      final Level level,
      final List<BlockPos> blocks,
      final double reach,
      final double eyeHeight,
      @Nullable final BlockPos from,
      @Nullable final Predicate<BlockPos> accept,
      final int limit,
      final boolean inBlocks)
    {
        return best(level, blocks, reach, eyeHeight, from, accept, limit, inBlocks, false);
    }

    /**
     * The best spots to work from, best first.
     *
     * @param inBlocks whether the spot may be made of the blocks themselves (a builder who stands in the cell he puts scaffolding into, on
     *                 top of the scaffolding he put up before).
     * @see #best(Level, List, double, double, BlockPos, Predicate, int)
     */
    public static List<Spot> best(
      final Level level,
      final List<BlockPos> blocks,
      final double reach,
      final double eyeHeight,
      @Nullable final BlockPos from,
      @Nullable final Predicate<BlockPos> accept,
      final int limit,
      final boolean inBlocks,
      final boolean anyOrder)
    {
        if (blocks.isEmpty())
        {
            return List.of();
        }
        final BlockPos target = blocks.get(0);
        final double usable = Math.max(1.0D, reach - MARGIN);
        final int r = (int) Math.ceil(reach);
        // the blocks the spot must not be made of: the target and the blocks that are worked on soon
        final Set<BlockPos> soon = inBlocks ? Set.of() : new HashSet<>(blocks.subList(0, Math.min(blocks.size(), 12)));
        final List<Spot> spots = new ArrayList<>();
        for (int dy = -(r + 1); dy <= r + 1; dy++)
        {
            for (int dx = -r; dx <= r; dx++)
            {
                for (int dz = -r; dz <= r; dz++)
                {
                    final BlockPos feet = target.offset(dx, dy, dz);
                    if (!BuildReach.inReachFromFeet(feet, eyeHeight, target, usable))
                    {
                        continue;
                    }
                    if (soon.contains(feet) || soon.contains(feet.above()) || soon.contains(feet.below()))
                    {
                        continue;
                    }
                    if (!StandSpots.canStandAt(level, feet) || (accept != null && !accept.test(feet)))
                    {
                        continue;
                    }
                    spots.add(new Spot(feet, anyOrder ? BuildReach.countInReach(feet, eyeHeight, blocks, usable) : BuildReach.prefixInReach(feet, eyeHeight, blocks, usable), walkCost(from, feet)));
                }
            }
        }
        spots.sort(Comparator.comparingDouble(Spot::utility).reversed().thenComparingDouble(Spot::walk));
        return spots.size() > limit ? new ArrayList<>(spots.subList(0, limit)) : spots;
    }

    /**
     * About the number of blocks of walking from one spot to another: horizontal distance, climbing counts double.
     */
    public static double walkCost(@Nullable final BlockPos from, final BlockPos to)
    {
        if (from == null)
        {
            return 0.0D;
        }
        final int dx = Math.abs(from.getX() - to.getX());
        final int dz = Math.abs(from.getZ() - to.getZ());
        final int dy = to.getY() - from.getY();
        return Math.sqrt(dx * dx + dz * dz) + (dy > 0 ? 2.0D * dy : -0.5D * dy);
    }
}
