package com.minecolonies.core.entity.pathfinding.pathjobs;

import com.minecolonies.core.entity.pathfinding.MNode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Moves to the given location, with a special passable check.
 */
public class PathJobMoveToWithPassable extends PathJobMoveToLocation
{
    /**
     * Additional cost to special passing rule
     */
    public static final double PASSING_COST = 3;

    /**
     * Function which tests if the given blockstate is passable
     */
    private Function<BlockState, Boolean> isPassable;

    /**
     * A second check by position for what {@link #isPassable} lets through, or null: run off the server thread with the path job's world view.
     */
    @FunctionalInterface
    public interface PassableAt
    {
        boolean test(LevelReader world, BlockState state, int x, int y, int z);
    }

    @Nullable
    private final PassableAt passableAt;

    /**
     * Prepares the PathJob for the path finding system.
     *
     * @param world      world the entity is in.
     * @param start      starting location.
     * @param end        target location.
     * @param range      max search range.
     * @param entity     the entity.
     * @param isPassable passable check
     */
    public PathJobMoveToWithPassable(
      final Level world,
      @NotNull final BlockPos start,
      @NotNull final BlockPos end, final int range, final Mob entity, final Function<BlockState, Boolean> isPassable)
    {
        this(world, start, end, range, entity, isPassable, null);
    }

    /**
     * @param passableAt a second check by position for the blocks isPassable lets through, or null.
     */
    public PathJobMoveToWithPassable(
      final Level world,
      @NotNull final BlockPos start,
      @NotNull final BlockPos end, final int range, final Mob entity, final Function<BlockState, Boolean> isPassable, @Nullable final PassableAt passableAt)
    {
        super(world, start, end, range, entity);
        this.isPassable = isPassable;
        this.passableAt = passableAt;
    }

    private boolean passes(final BlockState state, final int x, final int y, final int z)
    {
        return isPassable.apply(state) && (passableAt == null || passableAt.test(world, state, x, y, z));
    }

    @Override
    protected boolean isPassable(@NotNull final BlockState block, final int x, final int y, final int z, final MNode parent, final boolean head)
    {
        return super.isPassable(block, x, y, z, parent, head) || passes(block, x, y, z);
    }

    @Override
    protected double modifyCost(
      final double cost,
      final MNode parent,
      final boolean swimstart,
      final boolean swimming,
      final int x,
      final int y,
      final int z,
      final BlockState state, final BlockState below)
    {
        if (!state.isAir() && passes(state, x, y, z))
        {
            return cost * PASSING_COST;
        }
        else
        {
            final BlockState above = cachedBlockLookup.getBlockState(x, y + 1, z);
            if (!above.isAir() && passes(above, x, y + 1, z))
            {
                return cost * PASSING_COST;
            }
        }

        return cost;
    }
}
