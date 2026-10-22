package com.minecolonies.core.entity.pathfinding.navigation;

import com.ldtteam.structurize.util.BlockUtils;
import com.minecolonies.api.entity.ai.workers.util.IBuilderUndestroyable;
import com.minecolonies.api.entity.pathfinding.IMinecoloniesNavigator;
import com.minecolonies.api.entity.pathfinding.IStuckHandler;
import com.minecolonies.api.entity.pathfinding.IStuckHandlerEntity;
import com.minecolonies.api.items.ModTags;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.api.util.DamageSourceKeys;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.constant.ColonyConstants;
import com.minecolonies.core.entity.pathfinding.SurfaceType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.Objects;
import java.util.Random;

import static com.minecolonies.api.util.BlockPosUtil.HORIZONTAL_DIRS;

/**
 * Stuck handler for pathing
 */
public class PathingStuckHandler<NAV extends PathNavigation & IMinecoloniesNavigator> implements IStuckHandler<NAV>
{
    /**
     * The distance at which we consider a target to arrive
     */
    private static final double MIN_TARGET_DIST = 3;

    /**
     * Constants related to tp.
     */
    private static final int MIN_TP_DELAY    = 120 * 20;
    private static final int MIN_DIST_FOR_TP = 10;

    /**
     * Rough amount of ticks taken to travel one block
     */
    private static final int TICKS_PER_BLOCK = 7;

    /**
     * Initial stucklevel
     */
    private static final int STARTING_STUCK_LEVEL = -5;

    /**
     * Amount of path steps allowed to teleport on stuck, 0 = disabled
     */
    private int teleportRange = 0;

    /**
     * Max timeout per block to go, default = 10sec per block
     */
    private int timePerBlockDistance = 200;

    /**
     * The current stucklevel, determines actions taken
     */
    private int stuckLevel = STARTING_STUCK_LEVEL;

    /**
     * Global timeout counter, used to determine when we're completly stuck
     */
    private int globalTimeout = 0;

    /**
     * The previously desired go to position of the entity
     */
    private BlockPos prevDestination = BlockPos.ZERO;

    /**
     * Whether breaking blocks is enabled
     */
    private boolean canBreakBlocks = false;

    /**
     * Whether placing ladders is enabled
     */
    private boolean canPlaceLadders = false;

    /**
     * Whether leaf bridges are enabled
     */
    private boolean canBuildLeafBridges = false;

    /**
     * Whether teleport to goal at full stuck is enabled
     */
    private boolean canTeleportGoal = false;

    /**
     * Whether take damage on stuck is enabled
     */
    private boolean takeDamageOnCompleteStuck = false;
    private float   damagePct                 = 0.2f;

    /**
     * BLock break range on complete stuck
     */
    private int completeStuckBlockBreakRange = 0;

    /**
     * Chance to bypass moving away.
     */
    private double chanceToByPassMovingAway = 0;

    /**
     * Temporary comparison variables to compare with last update
     */
    private boolean hadPath         = false;
    private int     lastPathIndex   = -1;
    private int     progressedNodes = 0;

    /**
     * Delay before taking unstuck actions in ticks, default 5 seconds
     */
    private int delayBeforeActions = 5 * 20;
    private int delayToNextUnstuckAction = delayBeforeActions;

    /**
     * The start position of moving away unstuck
     */
    private BlockPos  moveAwayStartPos = BlockPos.ZERO;
    private Direction movingAwayDir    = Direction.EAST;

    private Random rand = new Random();

    /**
     * Round 13 D4: a trip that gets no closer to its goal for this many ticks may end in a quick rescue (see {@link IStuckHandlerEntity#getQuickRescueTarget}).
     */
    public static final int QUICK_RESCUE_TICKS = 1200;

    /**
     * D4: no quick rescue while a player is this close to the entity or to the target (the player would see the jump).
     */
    public static final int QUICK_RESCUE_PLAYER_RANGE = 32;

    /**
     * D4: progress on the current trip: the closest distance to the goal so far, and the global timeout at which it last improved by 2 blocks.
     */
    private double bestDistanceToGoal = Double.MAX_VALUE;
    private int    lastProgressTimeout = 0;
    private int    quickRescues = 0;

    private PathingStuckHandler()
    {
    }

    /**
     * Creates a new stuck handler
     *
     * @return new stuck handler
     */
    public static <NAV extends PathNavigation & IMinecoloniesNavigator> PathingStuckHandler<NAV> createStuckHandler()
    {
        return new PathingStuckHandler<>();
    }

    /**
     * Checks the entity for stuck
     *
     * @param navigator navigator to check
     */
    @Override
    public void checkStuck(final NAV navigator)
    {
        if (navigator.getPathResult() == null)
        {
            resetGlobalStuckTimers();
            return;
        }

        if (navigator.getOurEntity() instanceof IStuckHandlerEntity && !((IStuckHandlerEntity) navigator.getOurEntity()).canBeStuck())
        {
            resetGlobalStuckTimers();
            return;
        }

        // Global timeout check
        if (Objects.equals(prevDestination, navigator.getSafeDestination()))
        {
            globalTimeout += 10;
            // Try path first, if path fits target pos
            if (globalTimeout > MIN_TP_DELAY)
            {
                if (navigator.getSafeDestination() != null && navigator.getSafeDestination() != BlockPos.ZERO)
                {
                    final int distance = Math.max(MIN_DIST_FOR_TP, BlockPosUtil.distManhattan(navigator.getSafeDestination(), navigator.getOurEntity().blockPosition()));
                    if (globalTimeout > timePerBlockDistance * distance)
                    {
                        completeStuckAction(navigator);
                    }
                }
                else
                {
                    completeStuckAction(navigator);
                }
            }
        }
        else
        {
            resetGlobalStuckTimers();
        }

        prevDestination = navigator.getSafeDestination();

        if (prevDestination != null && prevDestination != BlockPos.ZERO)
        {
            final double distanceToGoal =
                navigator.getOurEntity()
                    .position()
                    .distanceTo(new Vec3(navigator.getSafeDestination().getX(), navigator.getSafeDestination().getY(), navigator.getSafeDestination().getZ()));

            // Close enough to be considered at the goal
            if (distanceToGoal < MIN_TARGET_DIST)
            {
                resetGlobalStuckTimers();
                return;
            }

            // D4: a non-work trip with no progress for about a minute ends with a quick move to its goal, if nobody would see it
            if (distanceToGoal < bestDistanceToGoal - 2)
            {
                bestDistanceToGoal = distanceToGoal;
                lastProgressTimeout = globalTimeout;
            }
            else if (quickRescueDue(globalTimeout, lastProgressTimeout))
            {
                if (tryQuickRescue(navigator.getOurEntity(), prevDestination))
                {
                    quickRescues++;
                    navigator.stop();
                    resetGlobalStuckTimers();
                    return;
                }
                // not this time (a work trip, a player close by, no free spot): ask again in 10 seconds
                lastProgressTimeout = globalTimeout - QUICK_RESCUE_TICKS + 200;
            }
        }

        delayToNextUnstuckAction -= 10;

        if (navigator.getPath() == null || navigator.getPath().isDone())
        {
            // With no path reset the last path index point to -1
            lastPathIndex = -1;
            progressedNodes = 0;

            // Stuck when we have no path and had no path last update before
            if (!hadPath)
            {
                tryUnstuck(navigator);
            }
        }
        else
        {
            if (navigator.getPath().getNextNodeIndex() == lastPathIndex)
            {
                // Stuck when we have a path, but are not progressing on it
                tryUnstuck(navigator);
            }
            else if (lastPathIndex != -1)
            {
                // Delay next action when the entity is moving
                delayToNextUnstuckAction = Math.max(delayToNextUnstuckAction, 100);

                if ((stuckLevel == 0 || (prevDestination != null && prevDestination != BlockPos.ZERO && navigator.getPath().getTarget().distSqr(prevDestination) < 25)))
                {
                    progressedNodes = navigator.getPath().getNextNodeIndex() > lastPathIndex ? progressedNodes + 1 : progressedNodes;
                    if (progressedNodes > 5 && (navigator.getPath().getEndNode() == null || !moveAwayStartPos.equals(navigator.getPath().getEndNode().asBlockPos())))
                    {
                        // Not stuck when progressing
                        resetStuckTimers();
                    }
                }
            }
        }

        lastPathIndex = navigator.getPath() != null ? navigator.getPath().getNextNodeIndex() : -1;

        hadPath = navigator.getPath() != null && !navigator.getPath().isDone();
    }

    /**
     * Resets global stuck timers
     */
    @Override
    public void resetGlobalStuckTimers()
    {
        globalTimeout = 0;
        bestDistanceToGoal = Double.MAX_VALUE;
        lastProgressTimeout = 0;
        prevDestination = BlockPos.ZERO;
        resetStuckTimers();
    }

    /**
     * D4: whether a trip is due a quick rescue.
     *
     * @param globalTimeout       the ticks on the trip so far.
     * @param lastProgressTimeout the trip tick at which the entity last got 2 blocks closer to its goal.
     * @return true after {@link #QUICK_RESCUE_TICKS} ticks without progress.
     */
    public static boolean quickRescueDue(final int globalTimeout, final int lastProgressTimeout)
    {
        return globalTimeout - lastProgressTimeout >= QUICK_RESCUE_TICKS;
    }

    /**
     * D4: moves the entity next to the target its own rules give for this trip, when no player is within {@link #QUICK_RESCUE_PLAYER_RANGE} blocks of it or of
     * the target. Server side only.
     *
     * @param entity      the entity.
     * @param destination the destination of its trip.
     * @return true when the entity was moved.
     */
    public static boolean tryQuickRescue(final Mob entity, final BlockPos destination)
    {
        if (!(entity instanceof IStuckHandlerEntity handlerEntity) || entity.level().isClientSide() || entity.isPassenger())
        {
            return false;
        }
        final BlockPos target = handlerEntity.getQuickRescueTarget(destination);
        if (target == null)
        {
            return false;
        }
        final Level world = entity.level();
        if (world.getNearestPlayer(entity, QUICK_RESCUE_PLAYER_RANGE) != null
              || world.getNearestPlayer(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, QUICK_RESCUE_PLAYER_RANGE, false) != null)
        {
            return false;
        }
        final BlockPos tpPos = BlockPosUtil.findAround(world, target, 6, 6,
          (posworld, pos) -> SurfaceType.getSurfaceType(posworld, posworld.getBlockState(pos.below()), pos.below()) == SurfaceType.WALKABLE
                               && SurfaceType.getSurfaceType(posworld, posworld.getBlockState(pos), pos) == SurfaceType.DROPABLE
                               && SurfaceType.getSurfaceType(posworld, posworld.getBlockState(pos.above()), pos.above()) == SurfaceType.DROPABLE);
        if (tpPos == null)
        {
            return false;
        }
        entity.teleportTo(tpPos.getX() + 0.5, tpPos.getY(), tpPos.getZ() + 0.5);
        return true;
    }

    /**
     * @return how many quick rescues this handler made (for tests and diagnostics).
     */
    public int getQuickRescues()
    {
        return quickRescues;
    }

    /**
     * Final action when completly stuck before resetting stuck handler and path
     */
    private void completeStuckAction(final NAV navigator)
    {
        final BlockPos desired = navigator.getSafeDestination();
        final Level world = navigator.getOurEntity().level();
        final Mob entity = navigator.getOurEntity();

        if (!FMLEnvironment.isProduction())
        {
            Log.getLogger()
                .warn("Entity complete stuck action stuck:" + navigator.getOurEntity() + " desired:" + navigator.getSafeDestination() + " stuckLevel:" + stuckLevel + " teleport:"
                    + canTeleportGoal);
        }

        if (canTeleportGoal && desired != null && desired != BlockPos.ZERO)
        {
            final BlockPos tpPos = BlockPosUtil.findAround(world, desired, 10, 10,
                (posworld, pos) -> SurfaceType.getSurfaceType(posworld, posworld.getBlockState(pos.below()), pos.below()) == SurfaceType.WALKABLE
                    && SurfaceType.getSurfaceType(posworld, posworld.getBlockState(pos), pos) == SurfaceType.DROPABLE
                    && SurfaceType.getSurfaceType(posworld, posworld.getBlockState(pos.above()), pos.above()) == SurfaceType.DROPABLE);
            if (tpPos != null)
            {
                entity.teleportTo(tpPos.getX() + 0.5, tpPos.getY(), tpPos.getZ() + 0.5);
            }
        }

        if (takeDamageOnCompleteStuck)
        {
            entity.hurt(world.damageSources().source(DamageSourceKeys.STUCK_DAMAGE), entity.getMaxHealth() * damagePct);
        }

        if (completeStuckBlockBreakRange > 0)
        {
            final BlockPos neighbour = prevDestination != null && prevDestination != BlockPos.ZERO
                ? prevDestination
                : navigator.getPath() != null ? navigator.getPath().getTarget() : entity.blockPosition().east();
            final Direction facing = BlockPosUtil.getFacing(BlockPos.containing(entity.position()), neighbour);
            for (int i = 1; i <= completeStuckBlockBreakRange; i++)
            {
                if (!world.isEmptyBlock(BlockPos.containing(entity.position()).relative(facing, i)) || !world.isEmptyBlock(BlockPos.containing(entity.position())
                    .relative(facing, i)
                    .above()))
                {
                    breakBlocksAhead(world, BlockPos.containing(entity.position()).relative(facing, i - 1), facing);
                    break;
                }
            }
        }

        navigator.stop();
        resetGlobalStuckTimers();
    }

    /**
     * Tries unstuck options depending on the level
     */
    private void tryUnstuck(final NAV navigator)
    {
        if (delayToNextUnstuckAction > 0)
        {
            return;
        }
        delayToNextUnstuckAction = 50;

        if (stuckLevel < 0)
        {
            if (navigator.getPath() != null && !navigator.isDone() && navigator.getPath().getNextNodeIndex() < navigator.getPath().getNodeCount() - 1)
            {
                // Skip ahead on the node index in hopes that the potentially different direction helps us unstuck
                navigator.getPath().setNextNodeIndex(navigator.getPath().getNextNodeIndex() + 1);
                delayToNextUnstuckAction = 30;
                stuckLevel++;
            }
            else
            {
                stuckLevel = 0;
            }

            return;
        }

        // Clear path
        if (stuckLevel == 0)
        {
            stuckLevel++;
            delayToNextUnstuckAction = 200;
            navigator.getOurEntity().stopRiding();
            navigator.recalc();
            return;
        }

        final int lastStuckLevel = this.stuckLevel;
        chanceStuckLevel(navigator);

        // Move away, with chance to skip this.
        if (rand.nextDouble() < chanceToByPassMovingAway ||
            (lastStuckLevel == 1 || ((lastStuckLevel >= 3 && lastStuckLevel <= 8) && !(canBreakBlocks || canBuildLeafBridges || canPlaceLadders) && rand.nextBoolean())))
        {
            if (navigator.getPath() != null && navigator.getPath().getNextNodeIndex() > 0)
            {
                moveAwayStartPos = navigator.getPath().getNodePos(navigator.getPath().getNextNodeIndex() - 1);
            }
            else
            {
                moveAwayStartPos = navigator.getOurEntity().blockPosition().above();
            }

            final int range = ColonyConstants.rand.nextInt(20) + 20;
            navigator.setPauseTicks(0);
            ((MinecoloniesAdvancedPathNavigate) navigator).walkTowards(navigator.getOurEntity().blockPosition().relative(movingAwayDir, 40), range, 1.0f);
            movingAwayDir = movingAwayDir.getClockWise();
            navigator.setPauseTicks(range * TICKS_PER_BLOCK);
            delayToNextUnstuckAction = (int) (range * TICKS_PER_BLOCK * 1.5);
            return;
        }

        // Skip ahead
        if (lastStuckLevel == 2 && teleportRange > 0 && hadPath)
        {
            int index = Math.min(navigator.getPath().getNextNodeIndex() + teleportRange, navigator.getPath().getNodeCount() - 1);
            final Node togo = navigator.getPath().getNode(index);
            navigator.getOurEntity().teleportTo(togo.x + 0.5d, togo.y, togo.z + 0.5d);
            delayToNextUnstuckAction = 200;
        }

        // Place ladders & leaves
        if (lastStuckLevel >= 3 && lastStuckLevel <= 5)
        {
            if (canPlaceLadders && rand.nextBoolean())
            {
                delayToNextUnstuckAction = 200;
                placeLadders(navigator);
            }
            else if (canBuildLeafBridges)
            {
                delayToNextUnstuckAction = 100;
                placeLeaves(navigator);
            }
        }

        // break blocks
        if (lastStuckLevel >= 6 && lastStuckLevel <= 8 && canBreakBlocks)
        {
            delayToNextUnstuckAction = 200;
            breakBlocks(navigator);
        }

        if (lastStuckLevel == 9)
        {
            completeStuckAction(navigator);
            resetStuckTimers();
        }
    }

    /**
     * Random chance to decrease to a previous level of stuck
     */
    private void chanceStuckLevel(NAV nav)
    {
        stuckLevel++;
        // 20 % to decrease to the previous level again
        if (stuckLevel > 1 && rand.nextInt(6) == 0)
        {
            stuckLevel = Math.max(1, stuckLevel - 2);
        }
    }

    /**
     * Resets timers
     */
    private void resetStuckTimers()
    {
        delayToNextUnstuckAction = delayBeforeActions;
        lastPathIndex = -1;
        progressedNodes = 0;
        stuckLevel = STARTING_STUCK_LEVEL;
        moveAwayStartPos = BlockPos.ZERO;
    }

    /**
     * Attempt to break blocks that are blocking the entity to reach its destination.
     *
     * @param world  the world it is in.
     * @param start  the position the entity is at.
     * @param facing the direction the goal is in.
     */
    private boolean breakBlocksAhead(final Level world, final BlockPos start, final Direction facing)
    {
        // In entity
        if (!world.isEmptyBlock(start))
        {
            setAirIfPossible(world, start);
            return true;
        }

        // Above entity
        if (!world.isEmptyBlock(start.above(3)))
        {
            setAirIfPossible(world, start.above(3));
            return true;
        }

        // Goal direction up
        if (!world.isEmptyBlock(start.above().relative(facing)))
        {
            setAirIfPossible(world, start.above().relative(facing));
            return true;
        }

        // In goal direction
        if (!world.isEmptyBlock(start.relative(facing)))
        {
            setAirIfPossible(world, start.relative(facing));
            return true;
        }
        return false;
    }

    /**
     * Check if the block at the position is indestructible, if not, attempt to break it.
     *
     * @param world the world the block is in.
     * @param pos   the pos the block is at.
     */
    private void setAirIfPossible(final Level world, final BlockPos pos)
    {
        final BlockState state = world.getBlockState(pos);
        final Block blockAtPos = state.getBlock();
        if (blockAtPos instanceof IBuilderUndestroyable || state.is(ModTags.indestructible))
        {
            return;
        }
        world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
    }

    /**
     * Places ladders
     *
     * @param navigator navigator to use
     */
    private void placeLadders(final NAV navigator)
    {
        final Level world = navigator.getOurEntity().level();
        final Mob entity = navigator.getOurEntity();

        BlockPos entityPos = entity.blockPosition();

        while (world.getBlockState(entityPos).getBlock() == Blocks.LADDER)
        {
            entityPos = entityPos.above();
        }

        tryPlaceLadderAt(world, entityPos);
        tryPlaceLadderAt(world, entityPos.above());
        tryPlaceLadderAt(world, entityPos.above(2));
    }

    /**
     * Tries to place leaves
     *
     * @param navigator navigator to use
     */
    private void placeLeaves(final NAV navigator)
    {
        final Level world = navigator.getOurEntity().level();
        final Mob entity = navigator.getOurEntity();

        final Direction badFacing = navigator.getSafeDestination() == null
            ? entity.getDirection().getOpposite()
            : BlockPosUtil.getFacing(BlockPos.containing(entity.position()), navigator.getSafeDestination()).getOpposite();

        for (final Direction dir : HORIZONTAL_DIRS)
        {
            if (dir == badFacing)
            {
                continue;
            }

            for (int i = 1; i <= (dir == badFacing.getOpposite() ? 3 : 1); i++)
            {
                if (!tryPlaceLeaveOnPos(world, BlockPos.containing(entity.position()).below().relative(dir, i)))
                {
                    break;
                }
            }
        }
    }

    /**
     * Places a leave block in the world
     *
     * @param world
     * @param pos
     * @return
     */
    private boolean tryPlaceLeaveOnPos(final Level world, final BlockPos pos)
    {
        if (world.isEmptyBlock(pos))
        {
            world.setBlockAndUpdate(pos, Blocks.ACACIA_LEAVES.defaultBlockState());
            return true;
        }
        return false;
    }

    /**
     * Tries to randomly break blocks
     *
     * @param navigator navigator to use
     */
    private void breakBlocks(final NAV navigator)
    {
        final Level world = navigator.getOurEntity().level();
        final Mob entity = navigator.getOurEntity();

        final Direction facing =
            navigator.getSafeDestination() == null ? entity.getDirection() : BlockPosUtil.getFacing(BlockPos.containing(entity.position()), navigator.getSafeDestination());

        if (breakBlocksAhead(world, entity.blockPosition(), facing) && entity.getHealth() >= entity.getMaxHealth() / 3)
        {
            entity.hurt(world.damageSources().source(DamageSourceKeys.STUCK_DAMAGE), (float) Math.max(0.5, entity.getHealth() / 20.0));
        }
    }

    /**
     * Tries to place a ladder at the given position
     *
     * @param world world to use
     * @param pos   position to set
     */
    private void tryPlaceLadderAt(final Level world, final BlockPos pos)
    {
        final BlockState state = world.getBlockState(pos);
        if ((canBreakBlocks || state.canBeReplaced() || state.isAir()) && state.getBlock() != Blocks.LADDER && !(state.getBlock() instanceof IBuilderUndestroyable) && !state.is(
            ModTags.indestructible))
        {
            for (final Direction dir : HORIZONTAL_DIRS)
            {
                final BlockState toPlace = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, dir.getOpposite());
                if (BlockUtils.isAnySolid(world.getBlockState(pos.relative(dir))) && toPlace.canSurvive(world, pos))
                {
                    world.setBlockAndUpdate(pos, toPlace);
                    break;
                }
            }
        }
    }

    public PathingStuckHandler withBlockBreaks()
    {
        canBreakBlocks = true;
        return this;
    }

    public PathingStuckHandler withPlaceLadders()
    {
        canPlaceLadders = true;
        return this;
    }

    public PathingStuckHandler withBuildLeafBridges()
    {
        canBuildLeafBridges = true;
        return this;
    }

    public PathingStuckHandler withChanceToByPassMovingAway(final double chance)
    {
        chanceToByPassMovingAway = chance;
        return this;
    }

    /**
     * Enables teleporting a certain amount of steps along a generated path
     *
     * @param steps steps to teleport
     * @return this
     */
    public PathingStuckHandler withTeleportSteps(int steps)
    {
        teleportRange = steps;
        return this;
    }

    public PathingStuckHandler withTeleportOnFullStuck()
    {
        canTeleportGoal = true;
        return this;
    }

    public PathingStuckHandler withTakeDamageOnStuck(float damagePct)
    {
        this.damagePct = damagePct;
        takeDamageOnCompleteStuck = true;
        return this;
    }

    /**
     * Sets the time per block distance to travel, before timing out
     *
     * @param time in ticks to set
     * @return this
     */
    public PathingStuckHandler withTimePerBlockDistance(int time)
    {
        timePerBlockDistance = time;
        return this;
    }

    /**
     * Sets the delay before taking stuck actions
     *
     * @param delay to set
     * @return this
     */
    public PathingStuckHandler withDelayBeforeStuckActions(int delay)
    {
        delayBeforeActions = delay;
        return this;
    }

    /**
     * Sets the block break range on complete stuck
     *
     * @param range to set
     * @return this
     */
    public PathingStuckHandler withCompleteStuckBlockBreak(int range)
    {
        completeStuckBlockBreakRange = range;
        return this;
    }

    @Override
    public int getStuckLevel()
    {
        return stuckLevel;
    }
}
