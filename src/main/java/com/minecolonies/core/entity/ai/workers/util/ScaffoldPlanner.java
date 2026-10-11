package com.minecolonies.core.entity.ai.workers.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plans the scaffolding a builder puts up to reach a block he cannot reach from the ground: a spot to stand on, a column of scaffolding
 * under it that stands on the ground (the vanilla stability distance stays 0 in a grounded column), and when the ground is not under the
 * spot (a gap, water, an overhang) an arm of scaffolding at the top of a grounded column, at most {@link #MAX_ARM} blocks long (the vanilla
 * stability distance must stay under 7).
 * <p>
 * Pure (the world is a {@link Grid}), so the rules can be tested without a world.
 */
public final class ScaffoldPlanner
{
    private ScaffoldPlanner()
    {
    }

    /**
     * The vanilla scaffolding collapses at a stability distance of 7: a horizontal arm of at most 6 blocks stays up.
     */
    public static final int MAX_ARM = 6;

    /**
     * The longest column from the ground up (blocks).
     */
    public static final int MAX_COLUMN = 24;

    /**
     * What the planner needs to know about the world.
     */
    public interface Grid
    {
        /**
         * @return true if a scaffolding block can be put in the cell: air or something that gives way (plants, water), not lava.
         */
        boolean free(BlockPos pos);

        /**
         * @return true if the cell is a block with a sturdy top: a scaffolding block can stand on it (distance 0).
         */
        boolean ground(BlockPos pos);

        /**
         * @return true if the cell holds a scaffolding block of the builder's own (ledger), which counts as supported.
         */
        boolean scaffold(BlockPos pos);

        /**
         * @return extra cost of putting a scaffolding block into the cell: 0 for open space, more for the cells of the building that is built
         * (blocks of the blueprint go there later, so the scaffolding would have to go first).
         */
        int penalty(BlockPos pos);

        /**
         * @return true if the builder can walk to the position (standing there, or climbing there): a tower has to start where he can get to.
         */
        default boolean reachable(final BlockPos feet)
        {
            return true;
        }

        /**
         * @return the lowest and highest height a block can be at.
         */
        int minY();

        int maxY();
    }

    /**
     * A plan.
     *
     * @param cells   the cells that get a scaffolding block, in the order they are placed (each one rests on the ones before it).
     * @param stand   the feet position of the spot to work from (on top of the last column cell).
     * @param covered how many of the next blocks are in reach from the spot.
     * @param cost    the cost the plan was ranked by (lower is better).
     */
    public record Plan(List<BlockPos> cells, BlockPos stand, int covered, double cost)
    {
    }

    /**
     * Cost of a scaffolding block, in blocks of walking: placing and removing it again, and one item of the stock.
     */
    public static final double CELL_COST = 1.2D;

    /**
     * What a tower on its own foot costs on top of its blocks: the walk to it, the climb, and the walk back to take it down. An arm or an extension of
     * a tower that stands is cheaper than another tower.
     */
    public static final double NEW_TOWER_COST = 6.0D;

    /**
     * Plans the scaffolding to reach the first of the blocks.
     *
     * @param grid      the world.
     * @param blocks    the blocks to work on, the first one has to be in reach from the spot.
     * @param reach     the reach in blocks.
     * @param eyeHeight the eye height of the builder above his feet.
     * @param avoid     cells that are no place for scaffolding or for the spot (the blocks that are worked on soon), can be null.
     * @param maxCells  the most scaffolding blocks the plan may need (the stock).
     * @return the plan, or null if there is none.
     */
    @Nullable
    public static Plan plan(
      final Grid grid, final List<BlockPos> blocks, final double reach, final double eyeHeight, @Nullable final Set<BlockPos> avoid, final int maxCells)
    {
        return plan(grid, blocks, reach, eyeHeight, avoid, maxCells, false);
    }

    /**
     * @param anyOrder whether the builder takes what he reaches in any order (a spot is as good as the number of blocks it reaches), not just in a row.
     * @see #plan(Grid, List, double, double, Set, int)
     */
    @Nullable
    public static Plan plan(
      final Grid grid, final List<BlockPos> blocks, final double reach, final double eyeHeight, @Nullable final Set<BlockPos> avoid, final int maxCells, final boolean anyOrder)
    {
        if (blocks.isEmpty())
        {
            return null;
        }
        final BlockPos target = blocks.get(0);
        final double usable = Math.max(1.0D, reach - ReachSpots.MARGIN);
        final int r = (int) Math.ceil(reach);
        final Set<BlockPos> blocked = new HashSet<>(avoid == null ? Set.of() : avoid);
        blocked.addAll(blocks.subList(0, Math.min(blocks.size(), 12)));

        Plan best = null;
        for (int dy = -(r + 1); dy <= r + 1; dy++)
        {
            for (int dx = -r; dx <= r; dx++)
            {
                for (int dz = -r; dz <= r; dz++)
                {
                    final BlockPos feet = target.offset(dx, dy, dz);
                    // the builder who climbs the column ends up inside its top cell, one lower than the spot on top of it: that is where he must reach from
                    if (!BuildReach.inReachFromFeet(feet.below(), eyeHeight, target, usable) || feet.getY() - 1 < grid.minY() || feet.getY() + 1 > grid.maxY())
                    {
                        continue;
                    }
                    final BlockPos head = feet.above();
                    final BlockPos floor = feet.below();
                    if (blocked.contains(feet) || blocked.contains(head) || blocked.contains(floor) || !grid.free(feet) || !grid.free(head) || grid.scaffold(feet) || grid.scaffold(head))
                    {
                        continue;
                    }
                    final Support support = support(grid, floor, blocked, maxCells);
                    if (support == null)
                    {
                        continue;
                    }
                    final int covered = anyOrder ? BuildReach.countInReach(feet.below(), eyeHeight, blocks, usable) : BuildReach.prefixInReach(feet.below(), eyeHeight, blocks, usable);
                    final double cost = support.cells.size() * CELL_COST + support.penalty + penaltyOf(grid, feet) + penaltyOf(grid, head) - covered
                                          + (!support.cells.isEmpty() && grid.ground(support.cells.get(0).below()) ? NEW_TOWER_COST : 0.0D);
                    if (best == null || cost < best.cost || (cost == best.cost && support.cells.size() < best.cells.size()))
                    {
                        best = new Plan(support.cells, feet, covered, cost);
                    }
                }
            }
        }
        return best;
    }

    /**
     * Plans a tower for a builder who cannot walk out of a hole: a column with its top at the level of a spot on the rim, right next to that
     * spot, so the builder climbs it and steps off. The rim spots are those he cannot reach by walking but that connect to the rest of the world.
     *
     * @param grid     the world.
     * @param rims     the spots on the rim (feet positions).
     * @param maxCells the most scaffolding blocks the plan may need.
     * @return the plan (the stand is the spot on top of the tower, next to a rim spot), or null.
     */
    @Nullable
    public static Plan planEscape(final Grid grid, final List<BlockPos> rims, final int maxCells)
    {
        Plan best = null;
        final Set<BlockPos> none = new HashSet<>();
        for (final BlockPos rim : rims)
        {
            for (final Direction dir : Direction.Plane.HORIZONTAL)
            {
                final BlockPos feet = rim.relative(dir);
                if (!grid.free(feet) || !grid.free(feet.above()) || grid.scaffold(feet) || grid.scaffold(feet.above()))
                {
                    continue;
                }
                final Support support = support(grid, feet.below(), none, maxCells);
                if (support == null || support.cells.isEmpty())
                {
                    continue;
                }
                final double cost = support.cells.size() * CELL_COST + support.penalty;
                if (best == null || cost < best.cost)
                {
                    best = new Plan(support.cells, feet, 0, cost);
                }
            }
        }
        return best;
    }

    private static int penaltyOf(final Grid grid, final BlockPos pos)
    {
        return grid.penalty(pos);
    }

    /**
     * The support if the builder can walk to where it starts.
     */
    @Nullable
    private static Support usable(final Grid grid, @Nullable final Support support)
    {
        return support != null && (support.cells.isEmpty() || grid.reachable(support.cells.get(0))) ? support : null;
    }

    private record Support(List<BlockPos> cells, int penalty)
    {
        double weight()
        {
            return cells.size() * CELL_COST + penalty;
        }
    }

    /**
     * The cheapest way to put a scaffolding block into the floor cell so that it stands: a column to the ground right under it, or a column
     * next to it with an arm at the height of the floor.
     */
    @Nullable
    private static Support support(final Grid grid, final BlockPos floor, final Set<BlockPos> blocked, final int maxCells)
    {
        if (grid.scaffold(floor))
        {
            // already there and standing
            return new Support(List.of(), 0);
        }
        if (!grid.free(floor) || blocked.contains(floor))
        {
            return null;
        }
        Support best = usable(grid, column(grid, floor, blocked, maxCells));
        for (int k = 1; k <= MAX_ARM; k++)
        {
            for (final Direction dir : Direction.Plane.HORIZONTAL)
            {
                // the arm: k cells from the top of the column at "base" to the floor cell
                final Support arm = usable(grid, armFrom(grid, floor.relative(dir, k), dir.getOpposite(), k, blocked, maxCells));
                if (arm != null && (best == null || arm.weight() < best.weight()))
                {
                    best = arm;
                }
            }
        }
        return best != null && best.cells.size() <= maxCells ? best : null;
    }

    /**
     * The arm from the column at base (the top cell of that column is at the height of the floor cell) to the floor cell.
     */
    @Nullable
    private static Support armFrom(final Grid grid, final BlockPos base, final Direction towards, final int length, final Set<BlockPos> blocked, final int maxCells)
    {
        final Support column = column(grid, base, blocked, maxCells);
        if (column == null)
        {
            return null;
        }
        final List<BlockPos> cells = new ArrayList<>(column.cells);
        int penalty = column.penalty;
        for (int i = 1; i <= length; i++)
        {
            final BlockPos cell = base.relative(towards, i);
            if (grid.scaffold(cell))
            {
                continue;
            }
            if (!grid.free(cell) || blocked.contains(cell))
            {
                return null;
            }
            cells.add(cell);
            penalty += grid.penalty(cell);
        }
        return new Support(cells, penalty);
    }

    /**
     * The column of scaffolding from the ground up to the top cell (included): the cells from the first supported one up. Null if the cell below
     * the column is no ground within {@link #MAX_COLUMN} blocks or something is in the way.
     */
    @Nullable
    private static Support column(final Grid grid, final BlockPos top, final Set<BlockPos> blocked, final int maxCells)
    {
        final List<BlockPos> down = new ArrayList<>();
        int penalty = 0;
        BlockPos cell = top;
        for (int i = 0; i <= MAX_COLUMN; i++)
        {
            if (cell.getY() < grid.minY())
            {
                return null;
            }
            if (grid.scaffold(cell))
            {
                // an own column: it carries what comes on top of it
                break;
            }
            if (!grid.free(cell) || blocked.contains(cell))
            {
                // a solid block that is no ground (or one of the blocks being worked on): the column cannot stand on it
                return null;
            }
            if (grid.ground(cell.below()))
            {
                down.add(cell);
                penalty += grid.penalty(cell);
                break;
            }
            down.add(cell);
            penalty += grid.penalty(cell);
            cell = cell.below();
            if (down.size() > maxCells)
            {
                return null;
            }
        }
        if (down.isEmpty() && !grid.scaffold(top))
        {
            return null;
        }
        if (!down.isEmpty() && !grid.ground(down.get(down.size() - 1).below()) && !grid.scaffold(down.get(down.size() - 1).below()))
        {
            return null;
        }
        final List<BlockPos> up = new ArrayList<>(down.size());
        for (int i = down.size() - 1; i >= 0; i--)
        {
            up.add(down.get(i));
        }
        return new Support(up, penalty);
    }
}
