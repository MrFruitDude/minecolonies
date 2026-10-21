package com.minecolonies.core.entity.pathfinding.navigation;

import com.minecolonies.api.colony.IColony;
import com.ldtteam.structurize.api.util.Tuple;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.entity.ModEntities;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.other.MinecoloniesMinecart;
import com.minecolonies.api.entity.pathfinding.IDynamicHeuristicNavigator;
import com.minecolonies.api.entity.pathfinding.IMinecoloniesNavigator;
import com.minecolonies.api.entity.pathfinding.IStuckHandler;
import com.minecolonies.api.pathfinding.RouteHints;
import com.minecolonies.api.util.*;
import com.minecolonies.api.util.constant.ColonyConstants;
import com.minecolonies.api.util.constant.GuardConstants;
import com.minecolonies.core.entity.other.cavalry.CavalryHorseEntity;
import com.minecolonies.core.entity.pathfinding.*;
import com.minecolonies.core.entity.pathfinding.pathjobs.*;
import com.minecolonies.core.entity.pathfinding.pathresults.PathResult;
import com.minecolonies.core.entity.pathfinding.pathresults.TreePathResult;
import com.minecolonies.core.util.WorkerUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static com.minecolonies.api.util.constant.Constants.TICKS_SECOND;
import static com.minecolonies.core.entity.pathfinding.PathFindingStatus.IN_PROGRESS_FOLLOWING;
import static com.minecolonies.core.entity.pathfinding.pathjobs.AbstractPathJob.MAX_NODES;

/**
 * Minecolonies async PathNavigate.
 */
// TODO: Rework
public class MinecoloniesAdvancedPathNavigate extends AbstractAdvancedPathNavigate implements IDynamicHeuristicNavigator, IMinecoloniesNavigator
{
    private static final double ON_PATH_SPEED_MULTIPLIER = PathingOptions.ROAD_SPEED;
    public static final  double MIN_Y_DISTANCE           = 0.001;
    public static final  int    MAX_SPEED_ALLOWED        = 2;
    public static final  double MIN_SPEED_ALLOWED        = 0.1;

    @Nullable
    private PathResult<? extends AbstractPathJob> pathResult;

    /**
     * Spawn pos of minecart.
     */
    private BlockPos spawnedPos = BlockPos.ZERO;

    /**
     * Desired position to reach
     */
    private BlockPos safeDestinationPos;

    /**
     * The stuck handler to use
     */
    private IStuckHandler<MinecoloniesAdvancedPathNavigate> stuckHandler;

    /**
     * Whether we did set sneaking
     */
    private boolean isSneaking = true;

    /**
     * Speed factor for swimming
     */
    private double swimSpeedFactor = 1.0;

    /**
     * Average heuristic
     */
    private double heuristicAvg = 1;

    /**
     * Paused ticks, during those no new pathjob is allowed
     */
    private int pauseTicks = 0;

    /**
     * Increasing amount for pause times, each time a path fails
     */
    private int pauseTickBackupAmount = 10;

    /**
     * Back-off steps (in navigator ticks) for a move-to destination that keeps failing without progress (CA-11).
     */
    private static final int[] UNREACHABLE_BACKOFF = {200, 600, 1200};

    /**
     * Ticks new path jobs are refused after the entity asked twice in a row for a path into an unloaded area.
     */
    private static final int UNLOADED_TARGET_PAUSE = 200;

    /**
     * A failed move-to path counts as progress when its end is more than this many blocks (manhattan) closer to the
     * destination than its start: walking it gets the entity closer, so the next try is a real retry.
     */
    private static final int UNREACHABLE_PROGRESS_DIST = 2;

    /**
     * The last move-to destination that failed, the failures in a row for it, the next back-off step, and the ticks
     * left during which new move-to jobs for it are refused (CA-11).
     */
    @Nullable
    private BlockPos unreachableDest   = null;
    private int      unreachableFails  = 0;
    private int      unreachableLevel  = 0;
    private int      unreachableTicks  = 0;

    /**
     * A waypoint counts as reached when the entity stands within this distance of it (squared blocks); also the
     * distance under which a hinted waypoint is skipped at the start of a leg.
     */
    private static final int ROUTE_WAYPOINT_REACH_SQ = 16;

    /**
     * Ticks a destination gets no route hints after one of its legs failed.
     */
    private static final int ROUTE_ABANDON_TICKS = 1200;

    /**
     * Ticks before the providers are asked again for the same destination (one query per walk, not per AI request).
     */
    private static final int ROUTE_REQUERY_TICKS = 200;

    /**
     * Test hook: route hints for any entity with this navigator, not only citizens. False in production; only settable
     * in a GameTest run ({@link #routeHintsForAnyMobInTests()}).
     */
    private static volatile boolean routeHintsForAnyMob = false;

    /**
     * GameTests only (-Dneoforge.enableGameTest=true): route hints for any entity with this navigator.
     *
     * @return closing it switches it off.
     */
    public static AutoCloseable routeHintsForAnyMobInTests()
    {
        Pathfinding.requireGameTest();
        routeHintsForAnyMob = true;
        return () -> routeHintsForAnyMob = false;
    }

    /**
     * The hinted route being followed (RouteHints): its destination, the waypoints left after the current leg, the
     * current leg's waypoint and job. A destination whose leg failed gets plain searches until routeAbandonedUntil.
     */
    @Nullable
    private       BlockPos            routeTarget         = null;
    private final java.util.ArrayDeque<BlockPos> routeWaypoints = new java.util.ArrayDeque<>();
    @Nullable
    private       BlockPos            routeLeg            = null;
    @Nullable
    private       AbstractPathJob     routeLegJob         = null;
    @Nullable
    private       BlockPos            routeAbandoned      = null;
    private       long                routeAbandonedUntil = 0;
    /**
     * The last two destinations the providers were asked about and when (game time), so an AI that alternates two far
     * targets does not ask on every switch.
     */
    private final BlockPos[]          routeQueried        = new BlockPos[2];
    private final long[]              routeQueriedAt      = new long[2];

    /**
     * Temporary block position
     */
    private BlockPos.MutableBlockPos tempPos = new BlockPos.MutableBlockPos();

    /**
     * wanted position for movecontrol
     */
    private Vec3Mutable wantedPosition = Vec3Mutable.createEmpty();

    /**
     * The recheck delay for checking stuck
     */
    private int checkStuckDelay = 10;

    /**
     * Time at which a path finished
     */
    private long finishTime = Long.MAX_VALUE;

    /**
     * The last path index used for wanted position calculations
     */
    private int lastWantedPathIndex = -1;

    /**
     * Instantiates the navigation of an ourEntity.
     *
     * @param entity the ourEntity.
     * @param world  the world it is in.
     */
    public MinecoloniesAdvancedPathNavigate(@NotNull final Mob entity, final Level world)
    {
        super(entity, world);

        entity.moveControl = new MovementHandler(entity);
        this.nodeEvaluator.setCanPassDoors(true);
        getPathingOptions().setEnterDoors(true);
        this.nodeEvaluator.setCanOpenDoors(true);
        getPathingOptions().setCanOpenDoors(true);
        this.nodeEvaluator.setCanFloat(true);
        getPathingOptions().setCanSwim(true);

        stuckHandler = PathingStuckHandler.createStuckHandler().withTakeDamageOnStuck(0.2f).withTeleportSteps(6).withTeleportOnFullStuck();
    }

    @Nullable
    protected PathResult<PathJobMoveAwayFromLocation> walkAwayFrom(final BlockPos avoid, final double range, final double speedFactor, final boolean safeDestination)
    {
        @NotNull final BlockPos start = PathfindingUtils.prepareStart(ourEntity);

        return setPathJob(new PathJobMoveAwayFromLocation(CompatibilityUtils.getWorldFromEntity(ourEntity),
            start,
            avoid,
            (int) range,
            (int) ourEntity.getAttribute(Attributes.FOLLOW_RANGE).getValue(),
            ourEntity), null, speedFactor, safeDestination);
    }

    @Nullable
    @Override
    protected PathResult<AbstractPathJob> walkTowards(final BlockPos towards, final double range, final double speedFactor)
    {
        return setPathJob(new PathJobMoveTowards(CompatibilityUtils.getWorldFromEntity(ourEntity),
            PathfindingUtils.prepareStart(ourEntity),
            towards,
            (int) range,
            ourEntity), null, speedFactor, false);
    }

    @Nullable
    protected PathResult<PathJobRandomPos> walkToRandomPos(final int range, final double speedFactor)
    {
        @NotNull final BlockPos start = PathfindingUtils.prepareStart(ourEntity);
        final PathResult<PathJobRandomPos> result = setPathJob(new PathJobRandomPos(CompatibilityUtils.getWorldFromEntity(ourEntity),
            start,
            range,
            (int) ourEntity.getAttribute(Attributes.FOLLOW_RANGE).getValue(),

            ourEntity), null, speedFactor, true);

        if (result == null)
        {
            return null;
        }

        result.getJob().getPathingOptions().withToggleCost(1).withJumpCost(1).withDropCost(1).canDrop = false;
        return result;
    }

    @Nullable
    protected PathResult<PathJobRandomPos> walkToRandomPosAround(final int range, final double speedFactor, final BlockPos pos)
    {
        final PathResult<PathJobRandomPos> result = setPathJob(new PathJobRandomPos(CompatibilityUtils.getWorldFromEntity(ourEntity),
            PathfindingUtils.prepareStart(ourEntity),
            3,
            (int) ourEntity.getAttribute(Attributes.FOLLOW_RANGE).getValue(),
            range,
            ourEntity, pos), pos, speedFactor, false);

        if (result == null)
        {
            return null;
        }

        result.getJob().getPathingOptions().withToggleCost(1).withJumpCost(1).withDropCost(1).canDrop = false;
        return result;
    }

    @Override
    protected PathResult<PathJobRandomPos> walkToRandomPos(
        final int range,
        final double speedFactor,
        final com.ldtteam.structurize.api.util.Tuple<BlockPos, BlockPos> corners)
    {
        return walkToRandomPos(range, speedFactor, corners, false);
    }

    @Override
    protected PathResult<PathJobRandomPos> walkToRandomPos(
        final int range,
        final double speedFactor,
        final com.ldtteam.structurize.api.util.Tuple<BlockPos, BlockPos> corners, final boolean preferInside)
    {
        @NotNull final BlockPos start = PathfindingUtils.prepareStart(ourEntity);

        final PathResult<PathJobRandomPos> result = setPathJob(new PathJobRandomPos(CompatibilityUtils.getWorldFromEntity(ourEntity),
            start,
            range,
            (int) ourEntity.getAttribute(Attributes.FOLLOW_RANGE).getValue(),
            ourEntity,
            corners.getA(),
            corners.getB(), preferInside), null, speedFactor, true);

        if (result == null)
        {
            return null;
        }

        result.getJob().getPathingOptions().withJumpCost(1).withDropCost(1).canDrop = false;
        return result;
    }

    @Override
    protected PathResult<PathJobMoveCloseToXNearY> walkCloseToXNearY(
        final BlockPos desiredPosition,
        final BlockPos nearbyPosition,
        final int distToDesired,
        final double speedFactor,
        final boolean safeDestination)
    {
        // Same refusal as walkTo, before any route query or job: a refused target asks no provider and builds nothing.
        if (pauseTicks > 0 || isRefused(desiredPosition))
        {
            return null;
        }
        final PathJobMoveToLocation leg = nextRouteLeg(PathfindingUtils.prepareStart(ourEntity), desiredPosition);
        if (leg != null)
        {
            // A leg of a hinted route is a move-to job; callers only test it with PathJobMoveCloseToXNearY.isJobFor, which
            // accepts a leg of a route to the desired position.
            return (PathResult<PathJobMoveCloseToXNearY>) (PathResult<?>) setPathJob(leg, desiredPosition, speedFactor, safeDestination);
        }
        PathJobMoveCloseToXNearY pathJob = new PathJobMoveCloseToXNearY(ourEntity.level(), desiredPosition, nearbyPosition, 1, ourEntity);
        return setPathJob(pathJob, desiredPosition, speedFactor, safeDestination);
    }

    @Nullable
    @Override
    public <T extends AbstractPathJob> PathResult<T> setPathJob(
        @NotNull final AbstractPathJob job,
        final BlockPos dest,
        final double speedFactor, final boolean safeDestination)
    {
        if (pauseTicks > 0)
        {
            return null;
        }

        // CA-11, now for the "move close to X near Y" jobs of the builders as well: an unreachable target is not searched again and again
        // (also when another citizen of the colony just found it unreachable)
        if (job instanceof IDestinationPathJob destinationJob && isRefused(destinationJob.getDestination()))
        {
            return null;
        }

        if (ourEntity.getPose() != Pose.STANDING)
        {
            ourEntity.setPose(Pose.STANDING);
        }

        if (pathResult != null)
        {
            // If last pathjob was into unloaded, and we're trying to path into unloaded again unload entity
            if (dest != null && !WorldUtil.isBlockLoaded(ourEntity.level(), dest) && pathResult.getJob() instanceof IDestinationPathJob destinationPathJob &&
                !WorldUtil.isBlockLoaded(ourEntity.level(), destinationPathJob.getDestination()))
            {
                // used to discard the citizen (a silent loss of a colonist whose work site was unloaded). He stays; the job is refused and new
                // ones wait a while, so he is not searching paths into an unloaded area every tick.
                if (!FMLEnvironment.isProduction())
                {
                    Log.getLogger().info("Citizen:" + ourEntity + " trying to path into unloaded position at: " + dest + ", waiting");
                }
                pauseTicks = UNLOADED_TARGET_PAUSE;
                return null;
            }

            pathResult.cancel();
            pathResult.setStatus(PathFindingStatus.CANCELLED);
            pathResult = null;
        }
        super.stop();

        if (dest != null)
        {
            if (job.getStart().distSqr(dest) > 900 * 900)
            {
                Log.getLogger()
                    .error(
                        "Entity: " + ourEntity.getDisplayName().getString() + " is trying to walk too far! distance:" + Math.sqrt(job.getStart().distSqr(dest)) + " from:"
                            + job.getStart() + " to:"
                            + dest, new Exception());

                if (!dest.equals(BlockPos.ZERO))
                {
                    if (ourEntity instanceof AbstractEntityCitizen citizen)
                    {
                        final BlockPos tpPos = citizen.getCitizenData().getHomePosition();
                        ourEntity.snapTo(tpPos.getX(), tpPos.getY(), tpPos.getZ());
                        return null;
                    }

                    ourEntity.snapTo(dest.getX(), dest.getY(), dest.getZ());
                }

                pauseTicks = 20 * 300;
                return null;
            }
        }

        finishTime = Long.MAX_VALUE;
        this.originalDestination = dest;
        if (safeDestination)
        {
            safeDestinationPos = dest;
        }

        this.walkSpeedFactor = speedFactor;

        if (speedFactor > MAX_SPEED_ALLOWED || speedFactor < MIN_SPEED_ALLOWED)
        {
            Log.getLogger().error("Tried to set a bad speed:" + speedFactor + " for entity:" + ourEntity, new Exception());
            return null;
        }

        job.setPathingOptions(getOptionsForPathJob());
        if (job instanceof PathJobMoveToLocation moveTo && unreachableFails > 0 && moveTo.getDestination().equals(unreachableDest))
        {
            // a retry for a destination that already failed: bounded by distance, not stretched to ~16,000 nodes again
            moveTo.limitToFailedCap();
        }
        pathResult = job.getResult();
        pathResult.startJob(Pathfinding.getExecutor());
        return (PathResult<T>) pathResult;
    }

    /**
     * Resolve the effective pathing options for a newly created path job.
     *
     * <p>Mounted units should path according to their vehicle's constraints without mutating
     * either navigator's long-lived settings. The returned options are copied into the job.</p>
     *
     * @return the pathing options for the next job
     */
    protected PathingOptions getOptionsForPathJob()
    {
        if (ourEntity.getVehicle() instanceof Mob riddenMob
            && riddenMob.getNavigation() instanceof AbstractAdvancedPathNavigate vehicleNavigation)
        {
            final PathingOptions mountedOptions = new PathingOptions();
            mountedOptions.importFrom(vehicleNavigation.getPathingOptions());

            if (riddenMob instanceof CavalryHorseEntity)
            {
                mountedOptions.setEnterGates(true);
                mountedOptions.setEnterDoors(false);
                mountedOptions.setTurnPenalty(GuardConstants.CAVALRY_CORNER_PENALTY);
            }

            return mountedOptions;
        }

        return getPathingOptions();
    }

    @Override
    public boolean isDone()
    {
        return (pathResult == null || pathResult.isDone() && pathResult.getStatus() != PathFindingStatus.CALCULATION_COMPLETE) && super.isDone();
    }

    @Override
    public void tick()
    {
        if (checkStuckDelay-- < 0)
        {
            checkStuckDelay = 10;
            stuckHandler.checkStuck(this);
        }

        if (pauseTicks > 0)
        {
            pauseTicks--;
        }

        if (unreachableTicks > 0)
        {
            unreachableTicks--;
        }

        if (pathResult != null)
        {
            if (!pathResult.isDone())
            {
                return;
            }
            else if (pathResult.getStatus() == PathFindingStatus.CALCULATION_COMPLETE)
            {
                processCompletedCalculationResult();
                wantedPosition.setEmpty();
            }
        }

        if (isDone())
        {
            if (pathResult != null)
            {
                pathResult.setStatus(PathFindingStatus.COMPLETE);

                // Cleanup pathresult if the entity forgot about it
                if (ourEntity.level().getGameTime() - finishTime > TICKS_SECOND * 20 + pauseTickBackupAmount)
                {
                    pathResult = null;
                }
            }

            if (!wantedPosition.empty())
            {
                mob.getMoveControl().setWantedPosition(wantedPosition.getX(), wantedPosition.getY(), wantedPosition.getZ(), speedModifier);
                wantedPosition.setEmpty();
            }
            return;
        }

        this.ourEntity.setYya(0);
        if (handleLadders())
        {
            return;
        }

        if (ColonyConstants.rand.nextInt(20) == 0)
        {
            if (WorkerUtil.isPathBlock(level.getBlockState(findBlockUnderEntity(ourEntity)).getBlock()))
            {
                speedModifier = ON_PATH_SPEED_MULTIPLIER * getSpeedFactor();
            }
            else
            {
                speedModifier = getSpeedFactor();
            }
        }

        if (isSneaking)
        {
            isSneaking = false;
            mob.setShiftKeyDown(false);
        }

        if (handleRails())
        {
            return;
        }

        ++this.tick;
        if (this.hasDelayedRecomputation)
        {
            this.recomputePath();
        }

        // The following block replaces mojangs super.tick(). Why you may ask? Because it's broken, that's why.
        // The moveHelper won't move up if standing in a block with an empty bounding box (put grass, 1 layer snow, mushroom in front of a solid block and have them try jump up).
        this.followThePath();

        if (this.path != null && !this.path.isDone())
        {
            if ((wantedPosition.empty() || lastWantedPathIndex != path.getNextNodeIndex() && path.getNextNodeIndex() < path.getNodeCount()))
            {
                lastWantedPathIndex = path.getNextNodeIndex();
                Vec3 vector3d2 = path.getNextEntityPos(mob);
                tempPos.set(Mth.floor(vector3d2.x), Mth.floor(vector3d2.y), Mth.floor(vector3d2.z));
                if (wantedPosition.empty() || ChunkPos.pack(tempPos) == mob.chunkPosition().pack() || WorldUtil.isEntityBlockLoaded(level, tempPos))
                {
                    wantedPosition.set(vector3d2.x,
                        getSmartGroundY(this.level, tempPos, vector3d2.y),
                        vector3d2.z);
                }
            }

            if (!wantedPosition.empty())
            {
                double moveSpeed = speedModifier;

                // Lower speed when moving up/down to the side to not miss a block when we have perpendicular momentum
                if (Math.abs(wantedPosition.getY() - mob.getY()) > 0.6 && getPreviousNode() != null
                    && ((getPreviousNode().x != getNextNode().x && Math.abs(mob.getDeltaMovement().z()) > Math.abs(mob.getDeltaMovement().x()))
                    || (getPreviousNode().z != getNextNode().z && Math.abs(mob.getDeltaMovement().x()) > Math.abs(mob.getDeltaMovement().z()))))
                {
                    // Overrule existing speed for safe turn, when changing y levels
                    moveSpeed = 0.6;
                }

                mob.getMoveControl().setWantedPosition(wantedPosition.getX(), wantedPosition.getY(), wantedPosition.getZ(), moveSpeed);
            }
        }
    }

    /**
     * Similar to WalkNodeProcessor.getGroundY but not broken.
     * This checks if the block below the position we're trying to move to reaches into the block above, if so, it has to aim a little bit higher.
     *
     * @param world the world.
     * @param pos   the position to check.
     * @param orgY  original y level
     * @return the next y level to go to.
     */
    public static double getSmartGroundY(final BlockGetter world, final BlockPos.MutableBlockPos pos, final double orgY)
    {
        BlockState state = world.getBlockState(pos);

        if (!state.isAir())
        {
            if (state.getBlock() instanceof FenceGateBlock || state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock)
            {
                return orgY;
            }

            final VoxelShape voxelshape = state.getCollisionShape(world, pos);
            if (!ShapeUtil.isEmpty(voxelshape))
            {
                return pos.getY() + ShapeUtil.max(voxelshape, Direction.Axis.Y);
            }
        }

        pos.set(pos.getX(), pos.getY() - 1, pos.getZ());

        state = world.getBlockState(pos);
        if (!state.isAir())
        {
            final VoxelShape voxelshape = state.getCollisionShape(world, pos);
            if (!ShapeUtil.isEmpty(voxelshape))
            {
                return pos.getY() + ShapeUtil.max(voxelshape, Direction.Axis.Y);
            }
        }

        return orgY;
    }

    @Nullable
    protected PathResult<PathJobMoveToLocation> walkTo(final BlockPos desiredPos, final double speedFactor, final boolean safeDestination)
    {
        // Same outcome as setPathJob's refusal, without first building the job's chunk cache on the server thread.
        if (pauseTicks > 0 || isRefused(desiredPos))
        {
            return null;
        }

        @NotNull final BlockPos start = PathfindingUtils.prepareStart(ourEntity);
        final PathJobMoveToLocation leg = nextRouteLeg(start, desiredPos);
        if (leg != null)
        {
            return setPathJob(leg, desiredPos, speedFactor, safeDestination);
        }
        return setPathJob(
            new PathJobMoveToLocation(CompatibilityUtils.getWorldFromEntity(ourEntity),
                start,
                desiredPos,
                (int) ourEntity.getAttribute(Attributes.FOLLOW_RANGE).getValue(),
                ourEntity),
            desiredPos, speedFactor, safeDestination);
    }

    @Deprecated(since = "Do not use, always returns true, vanilla override")
    @Override
    public boolean walkTo(final BlockPos pos, final double speedFactor)
    {
        walkTo(pos, speedFactor, false);
        return true;
    }

    @Override
    protected PathFinder createPathFinder(final int p_179679_1_)
    {
        // Minecraft 26.2 invokes createPathFinder from PathNavigation's
        // constructor and immediately configures the returned finder.  The
        // async Minecolonies path jobs still own gameplay path creation, but a
        // valid vanilla finder/evaluator is required for construction and for
        // inherited navigation bookkeeping.
        this.nodeEvaluator = new WalkNodeEvaluator();
        return new PathFinder(this.nodeEvaluator, p_179679_1_);
    }

    @Override
    protected boolean canUpdatePath()
    {
        return true;
    }

    @NotNull
    @Override
    protected Vec3 getTempMobPos()
    {
        return this.ourEntity.position();
    }

    @Override
    public Path createPath(final BlockPos pos, final int p_179680_2_)
    {
        //Because this directly returns Path we can't do it async.
        return null;
    }

    @Override
    protected boolean canMoveDirectly(final Vec3 start, final Vec3 end)
    {
        // TODO improve road walking. This is better in some situations, but still not great.
        return !WorkerUtil.isPathBlock(level.getBlockState(BlockPos.containing(start.x, start.y - 1, start.z)).getBlock())
            && super.canMoveDirectly(start, end);
    }

    public double getSpeedFactor()
    {
        if (ourEntity.isInWater())
        {
            speedModifier = walkSpeedFactor * swimSpeedFactor;
            return speedModifier;
        }

        speedModifier = walkSpeedFactor;
        return walkSpeedFactor;
    }

    @Override
    public void setSpeedModifier(final double speedFactor)
    {
        if (speedFactor > MAX_SPEED_ALLOWED || speedFactor < MIN_SPEED_ALLOWED)
        {
            Log.getLogger().error("Tried to set a bad speed:" + speedFactor + " for entity:" + ourEntity, new Exception());
            return;
        }
        walkSpeedFactor = speedFactor;
    }

    @Deprecated(since = "Do not use, always returns true, vanilla override")
    @Override
    public boolean moveTo(final double x, final double y, final double z, final double speedFactor)
    {
        walkTo(BlockPos.containing(x, y, z), speedFactor, false);
        return true;
    }

    @Override
    public boolean moveTo(final Entity entityIn, final double speedFactor)
    {
        return walkTo(entityIn.blockPosition(), speedFactor);
    }

    // Removes stupid vanilla stuff, causing our pathpoints to occasionally be replaced by vanilla ones.
    @Override
    protected void trimPath() {}

    @Deprecated(since = "Do not use, always returns true, vanilla override")
    @Override
    public boolean moveTo(@Nullable final Path path, final double speedFactor)
    {
        if (path == null)
        {
            super.stop();
            return false;
        }
        return super.moveTo(convertPath(path), speedFactor);
    }

    /**
     * Converts the given path to a minecolonies path if needed.
     *
     * @param path given path
     * @return resulting path
     */
    private Path convertPath(final Path path)
    {
        final int pathLength = path.getNodeCount();
        Path tempPath = null;
        if (pathLength > 0 && !(path.getNode(0) instanceof PathPointExtended))
        {
            //  Fix vanilla PathPoints to be PathPointExtended
            @NotNull final PathPointExtended[] newPoints = new PathPointExtended[pathLength];

            for (int i = 0; i < pathLength; ++i)
            {
                final Node point = path.getNode(i);
                if (!(point instanceof PathPointExtended))
                {
                    newPoints[i] = new PathPointExtended(new BlockPos(point.x, point.y, point.z));
                }
                else
                {
                    newPoints[i] = (PathPointExtended) point;
                }
            }

            tempPath = new Path(Arrays.asList(newPoints), path.getTarget(), path.canReach());
        }

        return tempPath == null ? path : tempPath;
    }

    /**
     * Processes the pathresult when it finished computing
     */
    private void processCompletedCalculationResult()
    {
        if (pathResult == null)
        {
            return;
        }

        if (pathResult != null)
        {
            pathResult.setStatus(IN_PROGRESS_FOLLOWING);
        }

        // Calculate an overtime-heuristic adjustment for pathfinding to use which fits the terrain
        if (pathResult.hasPath() && pathResult.getPathLength() > 2 && pathResult.costPerDist != 1)
        {
            final double factor = 1 + pathResult.getPathLength() / 30.0;
            heuristicAvg -= heuristicAvg / (50 / factor);
            heuristicAvg += pathResult.costPerDist / (50 / factor);
        }

        if (pathResult.failedToReachDestination())
        {
            pauseTicks = pauseTickBackupAmount;
            pauseTickBackupAmount += 10;

            if (pathResult.searchedNodes >= MAX_NODES)
            {
                pauseTicks += 50;
            }
        }
        else
        {
            pauseTickBackupAmount = 10;
        }

        recordMoveToOutcome();

        moveTo(pathResult.getPath(), getSpeedFactor());
    }

    /**
     * CA-11: tracks a move-to destination that keeps failing. Once it failed twice in a row and the latest path made no
     * progress towards it, new move-to jobs for that destination are refused for 200, then 600, then 1200 ticks, so an
     * unreachable target stops costing a full search every few ticks. Other destinations are not affected, and a
     * successful path to it clears the state. Results without a path (rejected or crashed jobs) are not counted.
     */
    private void recordMoveToOutcome()
    {
        if (!(pathResult.getJob() instanceof IDestinationPathJob destinationJob) || pathResult.getPath() == null)
        {
            return;
        }

        final BlockPos dest = destinationJob.getDestination();
        if (pathResult.isPathReachingDestination())
        {
            if (dest.equals(unreachableDest))
            {
                unreachableDest = null;
                unreachableFails = 0;
                unreachableLevel = 0;
                unreachableTicks = 0;
            }
            return;
        }

        if (!dest.equals(unreachableDest))
        {
            unreachableDest = dest;
            unreachableFails = 0;
            unreachableLevel = 0;
            unreachableTicks = 0;
        }
        unreachableFails++;

        final boolean progress = BlockPosUtil.distManhattan(pathResult.getJob().getStart(), dest) - BlockPosUtil.distManhattan(pathResult.getPath().getTarget(), dest)
                                   > UNREACHABLE_PROGRESS_DIST;
        if (unreachableFails >= 2 && !progress)
        {
            unreachableTicks = UNREACHABLE_BACKOFF[Math.min(unreachableLevel, UNREACHABLE_BACKOFF.length - 1)];
            unreachableLevel++;
        }
        // Shared with the colony only as strong proof: the search tried every node it could reach (500+), and only for
        // walkers standing on one of those positions with the same abilities. The back-off above stays this citizen's own.
        ColonyUnreachableCache.markUnreachable(ourEntity.level(), Pathfinding.colonyKeyOf(ourEntity), dest,
          pathResult.getJob().getPathingOptions().abilityFlags(), pathResult.reachablePositions);
    }

    /**
     * Whether move-to jobs for this destination are currently refused because it kept failing (CA-11), or because a
     * search of the same colony that reached where this entity stands just proved it unreachable (colony cache). AI code can use this to treat the target as
     * "can't reach" instead of asking again.
     *
     * @param dest the destination.
     * @return true while backing off from it.
     */
    public boolean isDestinationUnreachable(final BlockPos dest)
    {
        if (dest == null)
        {
            return false;
        }
        return unreachableTicks > 0 && dest.equals(unreachableDest)
                 || ColonyUnreachableCache.isUnreachable(ourEntity.level(), Pathfinding.colonyKeyOf(ourEntity), dest, getOptionsForPathJob().abilityFlags(), PathfindingUtils.prepareStart(ourEntity));
    }

    /**
     * RouteHints: the next leg of a hinted route for a long walk, or null for a plain search to the destination.
     * <p>
     * A new destination more than {@link RouteHints#MIN_DISTANCE} blocks away asks the providers once (citizens only); an
     * answer is followed waypoint by waypoint, each leg a normal move-to search marked with the route's destination, so
     * the AI sees it as the walk it asked for. Each later request for the same destination (the AI asks again when a leg
     * is done) checks that the leg reached its waypoint and gives the next leg; after the last waypoint the walk is a
     * plain search. A leg that did not reach its waypoint drops the route: plain searches to that destination for
     * {@link #ROUTE_ABANDON_TICKS}.
     *
     * @param start the start of the walk.
     * @param dest  the destination.
     * @return the leg job, or null.
     */
    @Nullable
    private PathJobMoveToLocation nextRouteLeg(final BlockPos start, final BlockPos dest)
    {
        // (while paused setPathJob refuses every job: no provider call and no leg job for nothing)
        if (dest == null || pauseTicks > 0 || !RouteHints.hasProviders() || !(ourEntity.level() instanceof ServerLevel level))
        {
            return null;
        }
        final boolean citizen = ourEntity instanceof AbstractEntityCitizen;
        if (!citizen && !routeHintsForAnyMob)
        {
            return null;
        }
        if (dest.equals(routeAbandoned) && level.getGameTime() < routeAbandonedUntil && level.getGameTime() >= routeAbandonedUntil - ROUTE_ABANDON_TICKS)
        {
            return null;
        }

        // (the current path is this route's last leg, so the leg was accepted and walked; a refused leg starts over)
        final boolean onThisRoute = routeLegJob != null && dest.equals(routeTarget) && pathResult != null && pathResult.getJob() == routeLegJob;
        if (onThisRoute)
        {
            // the AI asks again: the last leg is over, it must have reached its waypoint
            if (!pathResult.isPathReachingDestination() || ourEntity.blockPosition().distSqr(routeLeg) > ROUTE_WAYPOINT_REACH_SQ)
            {
                abandonRoute(level, dest);
                return null;
            }
        }
        else
        {
            clearRoute();
            if (start.distSqr(dest) <= (double) RouteHints.MIN_DISTANCE * RouteHints.MIN_DISTANCE)
            {
                return null;
            }
            final long now = level.getGameTime();
            for (int i = 0; i < routeQueried.length; i++)
            {
                if (dest.equals(routeQueried[i]) && now - routeQueriedAt[i] < ROUTE_REQUERY_TICKS && now >= routeQueriedAt[i])
                {
                    // asked for this destination moments ago (the route is done or was refused): plain search
                    return null;
                }
            }
            // remember it in the older slot
            final int slot = routeQueried[0] == null || routeQueried[1] != null && routeQueriedAt[0] <= routeQueriedAt[1] ? 0 : 1;
            routeQueried[slot] = dest;
            routeQueriedAt[slot] = now;
            Pathfinding.Stats.routeHintQueries.incrementAndGet();
            final List<BlockPos> waypoints =
              RouteHints.query(level, citizen ? ((AbstractEntityCitizen) ourEntity).getCitizenColonyHandler().getColony() : null, start, dest);
            if (waypoints == null)
            {
                return null;
            }
            if (!validRoute(level, start, waypoints))
            {
                Pathfinding.Stats.routeFallbacks.incrementAndGet();
                return null;
            }
            routeTarget = dest;
            routeWaypoints.addAll(waypoints);
        }

        BlockPos next = routeWaypoints.pollFirst();
        while (next != null && start.distSqr(next) <= ROUTE_WAYPOINT_REACH_SQ)
        {
            next = routeWaypoints.pollFirst();
        }
        if (next == null)
        {
            // route done (or every waypoint was at hand): the rest is a plain search
            clearRoute();
            return null;
        }
        if (!WorldUtil.isBlockLoaded(level, next) || isRefused(next))
        {
            abandonRoute(level, dest);
            return null;
        }
        routeLeg = next;
        Pathfinding.Stats.routeLegs.incrementAndGet();
        final PathJobMoveToLocation leg =
          new PathJobMoveToLocation(level, start, next, (int) ourEntity.getAttribute(Attributes.FOLLOW_RANGE).getValue(), ourEntity);
        leg.setRouteTarget(dest);
        routeLegJob = leg;
        return leg;
    }

    /**
     * Max straight-line gap between the start and the first waypoint, and between waypoints, before a route is refused.
     */
    private static final int ROUTE_MAX_GAP = 96;

    /**
     * A hinted route is used only when every waypoint is inside the build height and no gap is longer than
     * {@link #ROUTE_MAX_GAP}, so a bad answer cannot turn each leg into a long search.
     */
    private static boolean validRoute(final Level level, final BlockPos start, final List<BlockPos> waypoints)
    {
        BlockPos previous = start;
        for (final BlockPos waypoint : waypoints)
        {
            if (level.isOutsideBuildHeight(waypoint) || previous.distSqr(waypoint) > (double) ROUTE_MAX_GAP * ROUTE_MAX_GAP)
            {
                return false;
            }
            previous = waypoint;
        }
        return true;
    }

    private void abandonRoute(final Level level, final BlockPos dest)
    {
        Pathfinding.Stats.routeFallbacks.incrementAndGet();
        clearRoute();
        routeAbandoned = dest;
        routeAbandonedUntil = level.getGameTime() + ROUTE_ABANDON_TICKS;
    }

    private void clearRoute()
    {
        routeTarget = null;
        routeWaypoints.clear();
        routeLeg = null;
        routeLegJob = null;
    }

    /**
     * {@link #isDestinationUnreachable}, counting refusals by the colony cache in {@link Pathfinding.Stats}.
     */
    private boolean isRefused(final BlockPos dest)
    {
        if (dest == null)
        {
            return false;
        }
        if (unreachableTicks > 0 && dest.equals(unreachableDest))
        {
            return true;
        }
        if (ColonyUnreachableCache.isUnreachable(ourEntity.level(), Pathfinding.colonyKeyOf(ourEntity), dest, getOptionsForPathJob().abilityFlags(), PathfindingUtils.prepareStart(ourEntity)))
        {
            Pathfinding.Stats.unreachableCacheHits.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * Handles ladders on a path, following and movement
     *
     * @return true if handling a ladder
     */
    private boolean handleLadders()
    {
        if (!getNextNode().isOnLadder())
        {
            return false;
        }

        if (ourEntity.getVehicle() != null)
        {
            final Entity entity = ourEntity.getVehicle();
            ourEntity.stopRiding();
            if (!(ourEntity.getVehicle() instanceof CavalryHorseEntity))
            {
                entity.remove(Entity.RemovalReason.DISCARDED);
            }
        }

        // Ladder path follow
        if (path.getNextNodeIndex() < path.getNodeCount())
        {
            HashSet<BlockPos> reached = null;
            if (PathfindingUtils.trackingMap.containsValue(ourEntity.getUUID()))
            {
                reached = new HashSet<>();
            }

            final double nextX = (double) getNextNode().x + (double) ((int) (this.mob.getBbWidth() + 1.0F)) * 0.5D;
            final double nextY = getNextNode().y;
            final double nextZ = (double) getNextNode().z + (double) ((int) (this.mob.getBbWidth() + 1.0F)) * 0.5D;

            final double diffX = Math.abs(this.mob.getX() - nextX);
            final double diffY = Math.abs(this.mob.getY() - nextY);
            final double diffZ = Math.abs(this.mob.getZ() - nextZ);

            // Ladder entry needs more exact position tracking, we want to center the citizen before doing movement in another axis
            if (getNextNode().isOnLadder() && getPreviousNode() == null || !getPreviousNode().isOnLadder())
            {
                if (diffX < 0.2 && diffZ < 0.2)
                {
                    if (reached != null)
                    {
                        reached.add(getNextNode().asBlockPos());
                        PathfindingUtils.syncDebugReachedPositions(reached, pathResult.getDebugWatchers());
                    }
                    this.path.setNextNodeIndex(path.getNextNodeIndex() + 1);
                    return true;
                }

                // Slightly offsets the ladders starting position, so entities walk infront of it and do not get stuck trying to enter from the side
                final double offSetStartX = nextX + getNextNode().getLadderFacing().getStepX() * 0.1;
                final double offSetStartZ = nextZ + getNextNode().getLadderFacing().getStepZ() * 0.1;
                ourEntity.xxa = 0;
                ourEntity.zza = 0;
                wantedPosition.set(offSetStartX, nextY, offSetStartZ);
                this.ourEntity.getMoveControl().setWantedPosition(offSetStartX, nextY, offSetStartZ, 0.4);
            }
            // Scaling ladder, move
            else
            {
                final PathPointExtended afterNext = getNextNextNode();
                if (diffX < 0.5 && diffZ < 0.5 && diffY < 0.1)
                {
                    if (reached != null)
                    {
                        reached.add(getNextNode().asBlockPos());
                        PathfindingUtils.syncDebugReachedPositions(reached, pathResult.getDebugWatchers());
                    }

                    if (afterNext == null || !afterNext.isOnLadder())
                    {
                        final PathPointExtended previous = getPreviousNode();
                        if (previous != null)
                        {
                            final boolean up = previous.y < nextY;
                            if (up && ourEntity.getY() > nextY || !up && ourEntity.getY() < nextY)
                            {
                                this.path.setNextNodeIndex(path.getNextNodeIndex() + 1);
                            }
                        }
                    }
                    else
                    {
                        this.path.setNextNodeIndex(path.getNextNodeIndex() + 1);
                    }
                }

                if (isDone())
                {
                    return true;
                }

                //  Ladder Workaround
                // (an entity below the node keeps climbing even when the path turns sideways at the node: the top of a column of scaffolding with an arm,
                // where the next node is at the same height, is where the climb used to stall and slide back down)
                if (getNextNode().isOnLadder() && afterNext != null && (getNextNode().y != afterNext.y || mob.getY() > getNextNode().y
                                                                          || (getNextNode().getLadderFacing() == Direction.UP && mob.getY() < getNextNode().y - 0.1D)))
                {
                    return doLadderMovement();
                }
                return false;
            }
        }

        return true;
    }

    /**
     * Handles movement on a ladder
     *
     * @return true if a ladder is being handled
     */
    private boolean doLadderMovement()
    {
        Vec3 vec3 = this.getPath().getNextEntityPos(this.ourEntity);
        final BlockPos entityPos = this.ourEntity.blockPosition();
        //This way he is less nervous and gets up the ladder
        double newSpeed = 0.5;
        switch (getNextNode().getLadderFacing())
        {
            //  Any of these values is climbing, so adjust our direction of travel towards the ladder
            case NORTH:
                vec3 = vec3.add(0, 1, 0.8);
                break;
            case SOUTH:
                vec3 = vec3.add(0, 1, -0.8);
                break;
            case WEST:
                vec3 = vec3.add(0.8, 1, 0);
                break;
            case EAST:
                vec3 = vec3.add(-0.8, 1, 0);
                break;
            case UP:
                vec3 = vec3.add(0, 1, 0);
                break;
            //  Any other value is going down, so lets not move at all
            default:
                newSpeed = 0;
                if (!isSneaking)
                {
                    mob.setShiftKeyDown(true);
                    isSneaking = true;
                }
                this.ourEntity.getMoveControl().setWantedPosition(vec3.x, vec3.y, vec3.z, 0.2);
                wantedPosition.set(vec3.x, vec3.y, vec3.z);
                break;
        }

        if (newSpeed > 0)
        {
            if (!(level.getBlockState(ourEntity.blockPosition()).getBlock() instanceof LadderBlock) && ourEntity.getY() <= vec3.y)
            {
                this.ourEntity.setDeltaMovement(0, 0.1D, 0);
            }
            this.ourEntity.getMoveControl().setWantedPosition(vec3.x, vec3.y, vec3.z, newSpeed);
            wantedPosition.set(vec3.x, vec3.y, vec3.z);
            return true;
        }
        else
        {
            if (PathfindingUtils.isLadder(level.getBlockState(entityPos.below()), getPathingOptions()) || ourEntity.getY() > getNextNode().y)
            {
                this.ourEntity.setYya(-0.5f);
            }
            else
            {
                return false;
            }
            return true;
        }
    }

    /**
     * Determine what block the entity stands on
     *
     * @param parEntity the entity that stands on the block
     * @return the Blockstate.
     */
    private BlockPos findBlockUnderEntity(@NotNull final Entity parEntity)
    {
        int blockX = (int) Math.round(parEntity.getX());
        int blockY = Mth.floor(parEntity.getY() - 0.2D);
        int blockZ = (int) Math.round(parEntity.getZ());
        return tempPos.set(blockX, blockY, blockZ);
    }

    /**
     * Handle rails navigation.
     *
     * @return true if block.
     */
    private boolean handleRails()
    {
        if (!this.isDone())
        {
            @NotNull final PathPointExtended pEx = (PathPointExtended) this.getPath().getNode(this.getPath().getNextNodeIndex());
            PathPointExtended pExNext = getPath().getNodeCount() > this.getPath().getNextNodeIndex() + 1
                ? (PathPointExtended) this.getPath()
                                      .getNode(this.getPath()
                                               .getNextNodeIndex() + 1) : null;

            if (pExNext != null && pEx.x == pExNext.x && pEx.z == pExNext.z)
            {
                pExNext = getPath().getNodeCount() > this.getPath().getNextNodeIndex() + 2
                    ? (PathPointExtended) this.getPath()
                                          .getNode(this.getPath()
                                                   .getNextNodeIndex() + 2) : null;
            }

            if (pEx.isOnRails() || pEx.isRailsExit())
            {
                return handlePathOnRails(pEx, pExNext);
            }
        }
        return false;
    }

    /**
     * Handle pathing on rails.
     *
     * @param pEx     the current path point.
     * @param pExNext the next path point.
     * @return if go to next point.
     */
    private boolean handlePathOnRails(final PathPointExtended pEx, final PathPointExtended pExNext)
    {
        if (pEx.isRailsEntry())
        {
            tempPos.set(pEx.x, pEx.y, pEx.z);
            if (!spawnedPos.equals(tempPos))
            {
                final BlockState blockstate = level.getBlockState(tempPos);
                RailShape railshape = blockstate.getBlock() instanceof BaseRailBlock
                    ? ((BaseRailBlock) blockstate.getBlock()).getRailDirection(blockstate, level, tempPos, null)
                    : RailShape.NORTH_SOUTH;
                double yOffset = 0.0D;
                if (railshape.isSlope())
                {
                    yOffset = 0.5D;
                }

                if (mob.getVehicle() instanceof final MinecoloniesMinecart ourMinecart)
                {
                    ourMinecart.setHurtDir(1);
                }
                else
                {
                    MinecoloniesMinecart minecart = ModEntities.MINECART.create(level, EntitySpawnReason.EVENT);
                    final double x = pEx.x + 0.5D;
                    final double y = pEx.y + 0.625D + yOffset;
                    final double z = pEx.z + 0.5D;
                    minecart.setPos(x, y, z);
                    minecart.setDeltaMovement(Vec3.ZERO);
                    minecart.xo = x;
                    minecart.yo = y;
                    minecart.zo = z;


                    level.addFreshEntity(minecart);
                    minecart.setHurtDir(1);
                    mob.startRiding(minecart, true, false);
                }
                spawnedPos = tempPos.immutable();
            }
        }
        else
        {
            spawnedPos = BlockPos.ZERO;
        }

        if (mob.getVehicle() instanceof MinecoloniesMinecart && pExNext != null)
        {
            final Vec3 motion = mob.getVehicle().getDeltaMovement();
            double forward;
            switch (BlockPosUtil.directionFromDelta(pExNext.x - pEx.x, 0, pExNext.z - pEx.z).getOpposite())
            {
                case EAST:
                    forward = Math.min(Math.max(motion.x() - 1 * 0.01D, -1), 0);
                    mob.getVehicle().setDeltaMovement(motion.add(forward == -1 ? -1 : -0.01D, 0.0D, 0.0D));
                    break;
                case WEST:
                    forward = Math.max(Math.min(motion.x() + 0.01D, 1), 0);
                    mob.getVehicle().setDeltaMovement(motion.add(forward == 1 ? 1 : 0.01D, 0.0D, 0.0D));
                    break;
                case NORTH:
                    forward = Math.max(Math.min(motion.z() + 0.01D, 1), 0);
                    mob.getVehicle().setDeltaMovement(motion.add(0.0D, 0.0D, forward == 1 ? 1 : 0.01D));
                    break;
                case SOUTH:
                    forward = Math.min(Math.max(motion.z() - 1 * 0.01D, -1), 0);
                    mob.getVehicle().setDeltaMovement(motion.add(0.0D, 0.0D, forward == -1 ? -1 : -0.01D));
                    break;

                case DOWN:
                case UP:
                    // unreachable
                    break;
            }
        }
        return false;
    }

    @Override
    protected void followThePath()
    {
        // TODO: Rework pathfollow
        getSpeedFactor();
        final int curNode = path.getNextNodeIndex();
        final int curNodeNext = curNode + 1;
        if (curNodeNext < path.getNodeCount())
        {
            if (!(path.getNode(curNode) instanceof PathPointExtended))
            {
                path = convertPath(path);
            }

            final PathPointExtended pEx = (PathPointExtended) path.getNode(curNode);
            final PathPointExtended pExNext = (PathPointExtended) path.getNode(curNodeNext);

            //  If current node is bottom of a ladder, then stay on this node until
            //  the ourEntity reaches the bottom, otherwise they will try to head out early
            if (pEx.isOnLadder() && pEx.getLadderFacing() == Direction.DOWN
                && !pExNext.isOnLadder())
            {
                final Vec3 vec3 = getTempMobPos();
                if ((vec3.y - (double) pEx.y) < MIN_Y_DISTANCE)
                {
                    this.path.setNextNodeIndex(curNodeNext);
                }
                return;
            }

            if (!pEx.isOnRails() && ourEntity.getVehicle() != null && !(ourEntity.getVehicle() instanceof CavalryHorseEntity))
            {
                final Entity entity = ourEntity.getVehicle();
                ourEntity.stopRiding();
                entity.remove(Entity.RemovalReason.DISCARDED);
            }
        }

        this.maxDistanceToWaypoint = 0.5F;
        boolean wentAhead = false;
        boolean isTracking = PathfindingUtils.trackingMap.containsValue(ourEntity.getUUID());

        HashSet<BlockPos> reached = null;
        if (isTracking)
        {
            reached = new HashSet<>();
        }

        // Look at multiple points, incase we're too fast
        for (int i = this.path.getNextNodeIndex(); i < Math.min(this.path.getNodeCount(), this.path.getNextNodeIndex() + 4); i++)
        {
            // TODO: Only keep advancing if distance gets closer, instead of looping many points, check if entity pos at node is even needed, normal pos probably fine
            final Node node = path.getNode(i);
            final double nextX = (double) node.x + (double) ((int) (this.mob.getBbWidth() + 1.0F)) * 0.5D;
            final double nextY = node.y;
            final double nextZ = (double) node.z + (double) ((int) (this.mob.getBbWidth() + 1.0F)) * 0.5D;

            if (Math.abs(this.mob.getX() - nextX) < (double) this.maxDistanceToWaypoint - Math.abs(this.mob.getY() - (nextY)) * 0.1
                && Math.abs(this.mob.getZ() - nextZ) < (double) this.maxDistanceToWaypoint - Math.abs(this.mob.getY() - (nextY)) * 0.1 &&
                Math.abs(this.mob.getY() - nextY) <= 1.0D)
            {
                this.path.advance();
                wentAhead = true;

                if (isTracking)
                {
                    final Node point = path.getNode(i);
                    reached.add(new BlockPos(point.x, point.y, point.z));
                }
            }
        }

        if (isTracking)
        {
            PathfindingUtils.syncDebugReachedPositions(reached, pathResult.getDebugWatchers());
            reached.clear();
        }

        if (path.isDone())
        {
            onPathFinish();
            return;
        }

        if (wentAhead)
        {
            return;
        }

        if (curNode >= path.getNodeCount() || curNode <= 1)
        {
            return;
        }

        // Check some past nodes case we fell behind.
        final Vec3 curr = this.path.getEntityPosAtNode(this.mob, curNode - 1);
        final Vec3 next = this.path.getEntityPosAtNode(this.mob, curNode);

        if (mob.position().distanceTo(curr) >= 2.0 && mob.position().distanceTo(next) >= 2.0)
        {
            int currentIndex = curNode - 1;
            while (currentIndex > 0)
            {
                final Vec3 tempoPos = this.path.getEntityPosAtNode(this.mob, currentIndex);
                if (mob.position().distanceTo(tempoPos) <= 1.0)
                {
                    this.path.setNextNodeIndex(currentIndex);
                }
                else if (isTracking)
                {
                    reached.add(BlockPos.containing(tempoPos.x, tempoPos.y, tempoPos.z));
                }
                currentIndex--;
            }
        }

        if (isTracking)
        {
            PathfindingUtils.syncDebugReachedPositions(reached, pathResult.getDebugWatchers());
            reached.clear();
        }
    }

    /**
     * Called upon reaching the path end, reset values
     */
    private void onPathFinish()
    {
        finishTime = ourEntity.level().getGameTime();
        super.stop();
    }

    public void recomputePath() {}

    /**
     * Don't let vanilla rapidly discard paths, set a timeout before its allowed to use stuck.
     */
    @Override
    protected void doStuckDetection(@NotNull final Vec3 positionVec3)
    {
        // Do nothing, unstuck is checked on tick, not just when we have a path
    }

    /**
     * Stop indicates that the entity no longer desires to move.
     */
    @Override
    public void stop()
    {
        if (pathResult != null)
        {
            pathResult.cancel();
            pathResult.setStatus(PathFindingStatus.CANCELLED);
            pathResult = null;
            if ((ourEntity.getVehicle() != null) && !(ourEntity.getVehicle() instanceof CavalryHorseEntity))
            {
                final Entity entity = ourEntity.getVehicle();
                ourEntity.stopRiding();
                entity.remove(Entity.RemovalReason.DISCARDED);
            }
        }

        safeDestinationPos = BlockPos.ZERO;
        stuckHandler.resetGlobalStuckTimers();

        super.stop();
    }

    /**
     * Triggers an indirect recalc, isDone() returns true now
     */
    @Override
    public void recalc()
    {
        if (pathResult != null)
        {
            pathResult.cancel();
            pathResult.setStatus(PathFindingStatus.CANCELLED);
        }
        super.stop();
    }

    @Override
    public TreePathResult walkToTree(
        final BlockPos startRestriction,
        final BlockPos endRestriction,
        final double speed,
        final List<ItemStorage> excludedTrees,
        final int dyntreesize,
        final IColony colony)
    {
        @NotNull final BlockPos start = PathfindingUtils.prepareStart(ourEntity);
        final BlockPos furthestRestriction = BlockPosUtil.getFurthestCorner(start, startRestriction, endRestriction);

        final PathJobFindTree job =
            new PathJobFindTree(CompatibilityUtils.getWorldFromEntity(mob),
                start,
                startRestriction,
                endRestriction,
                furthestRestriction,
                excludedTrees,
                dyntreesize,
                colony,
                ourEntity);

        return (TreePathResult) setPathJob(job, null, speed, true);
    }

    @Override
    public TreePathResult walkToTree(final int range, final double speed, final List<ItemStorage> excludedTrees, final int dyntreesize, final IColony colony)
    {
        @NotNull BlockPos start = PathfindingUtils.prepareStart(ourEntity);
        final BlockPos buildingPos = ((AbstractEntityCitizen) mob).getCitizenColonyHandler().getWorkBuilding().getPosition();

        if (BlockPosUtil.getDistance2D(buildingPos, mob.blockPosition()) > range * 4)
        {
            start = buildingPos;
        }

        return (TreePathResult) setPathJob(
            new PathJobFindTree(CompatibilityUtils.getWorldFromEntity(mob), start, buildingPos, range, excludedTrees, dyntreesize, colony, ourEntity), null, speed, true);
    }

    @Nullable
    @Override
    public PathResult<PathJobMoveToLocation> walkToEntity(@NotNull final Entity e, final double speed)
    {
        return walkTo(e.blockPosition(), speed, false);
    }

    @Nullable
    @Override
    public PathResult<PathJobMoveAwayFromLocation> moveAwayFromLivingEntity(@NotNull final Entity e, final double distance, final double speed)
    {
        return walkAwayFrom(e.blockPosition(), distance, speed, true);
    }

    @Override
    public void setCanFloat(boolean canSwim)
    {
        super.setCanFloat(canSwim);
        getPathingOptions().setCanSwim(canSwim);
    }

    @Override
    public BlockPos getSafeDestination()
    {
        return safeDestinationPos;
    }

    @Override
    public void setSafeDestinationPos(final BlockPos pos)
    {
        safeDestinationPos = pos;
    }

    /**
     * Sets the stuck handler
     *
     * @param stuckHandler handler to set
     */
    @Override
    public void setStuckHandler(final IStuckHandler stuckHandler)
    {
        this.stuckHandler = stuckHandler;
    }

    @Override
    public void setSwimSpeedFactor(final double factor)
    {
        this.swimSpeedFactor = factor;
    }

    @Override
    public double getAvgHeuristicModifier()
    {
        return heuristicAvg;
    }

    /**
     * GameTests only (-Dneoforge.enableGameTest=true): sets the learned heuristic average, to measure searches with a
     * value a long-lived citizen may have.
     *
     * @param value the average cost per block.
     */
    public void setAvgHeuristicModifierForTests(final double value)
    {
        Pathfinding.requireGameTest();
        heuristicAvg = value;
    }

    @Override
    public void setPauseTicks(final int pauseTicks)
    {
        if (pauseTicks > TICKS_SECOND * 120)
        {
            Log.getLogger().warn("Tried to pause entity pathfinding for " + mob + " too long for " + pauseTicks + " ticks.", new Exception());
            this.pauseTicks = 50;
        }
        else
        {
            this.pauseTicks = pauseTicks;
        }
    }

    @Override
    public PathResult getPathResult()
    {
        return pathResult;
    }

    @Override
    public IStuckHandler<MinecoloniesAdvancedPathNavigate> getStuckHandler()
    {
        return stuckHandler;
    }

    @Override
    public boolean isStuck()
    {
        return stuckHandler.getStuckLevel() >= 3;
    }

    /**
     * Gets the next node, which is the node the entity is currently moving towards
     *
     * @return the next path node
     */
    private PathPointExtended getNextNode()
    {
        return (PathPointExtended) path.getNextNode();
    }

    /**
     * Get the previous node, which is the last node the entity reached
     *
     * @return
     */
    @Nullable
    private PathPointExtended getPreviousNode()
    {
        if (path.getNextNodeIndex() > 0)
        {
            return (PathPointExtended) path.getNode(path.getNextNodeIndex() - 1);
        }

        return null;
    }

    /**
     * Get the node after the next node, which the entity will be going for after reaching the next node
     *
     * @return
     */
    @Nullable
    private PathPointExtended getNextNextNode()
    {
        if (path.getNextNodeIndex() + 1 < path.getNodeCount())
        {
            return (PathPointExtended) path.getNode(path.getNextNodeIndex() + 1);
        }

        return null;
    }
}
