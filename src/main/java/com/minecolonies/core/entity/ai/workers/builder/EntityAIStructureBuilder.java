package com.minecolonies.core.entity.ai.workers.builder;

import com.ldtteam.structurize.placement.BlockPlacementResult;
import com.ldtteam.structurize.placement.StructurePhasePlacementResult;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder;
import com.minecolonies.core.colony.workorders.collab.AssistReservations;
import com.minecolonies.core.colony.workorders.collab.BuilderCollab;
import com.minecolonies.core.colony.workorders.collab.WorkOrderCollab;
import com.minecolonies.core.entity.ai.workers.util.BuildReach;
import com.minecolonies.core.entity.ai.workers.util.BuilderStageRules;
import com.minecolonies.core.entity.ai.workers.util.ReachSpots;
import com.minecolonies.core.entity.ai.workers.util.StandSpots;
import com.minecolonies.core.entity.ai.workers.util.AbstractWalking;
import com.minecolonies.core.entity.ai.workers.util.WorkStuckTracker;
import com.minecolonies.core.entity.ai.workers.util.WorkTiming;
import com.minecolonies.core.entity.ai.workers.util.WorkerSettings;
import com.minecolonies.core.entity.ai.workers.util.CollabStructureHandler;
import com.minecolonies.core.entity.ai.workers.util.LeaseAllocator;
import com.minecolonies.core.util.ItemMover;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.workorders.IWorkOrder;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.api.util.MessageUtils;
import com.ldtteam.structurize.api.util.Tuple;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.colony.buildings.modules.settings.BuilderModeSetting;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.core.colony.jobs.JobBuilder;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructureWithWorkOrder;
import com.minecolonies.core.entity.ai.workers.BuilderScaffolding;
import com.minecolonies.core.entity.ai.workers.util.BuildingProgressStage;
import com.minecolonies.core.entity.ai.workers.util.BuildingStructureHandler;
import com.minecolonies.core.entity.pathfinding.navigation.MinecoloniesAdvancedPathNavigate;
import com.minecolonies.core.entity.pathfinding.pathjobs.PathJobMoveCloseToXNearY;
import com.minecolonies.core.entity.pathfinding.pathresults.PathResult;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import org.jetbrains.annotations.NotNull;

import static com.ldtteam.structurize.placement.AbstractBlueprintIterator.NULL_POS;
import static com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState.*;
import static com.minecolonies.api.util.constant.CitizenConstants.MIN_OPEN_SLOTS;
import static com.minecolonies.core.colony.buildings.workerbuildings.BuildingMiner.FILL_BLOCK;
import static com.minecolonies.core.entity.ai.workers.builder.BuilderAssistState.*;
import static com.minecolonies.api.util.constant.TranslationConstants.COM_MINECOLONIES_COREMOD_ENTITY_BUILDER_MANUAL_SUFFIX;

/**
 * AI class for the builder. Manages building and repairing buildings.
 */
public class EntityAIStructureBuilder extends AbstractEntityAIStructureWithWorkOrder<JobBuilder, BuildingBuilder>
{
    /**
     * Speed buff at 0 depth level.
     */
    private static final double SPEED_BUFF_0 = 0.5;

    /**
     * After how many actions should the builder dump his inventory.
     */
    private static final int ACTIONS_UNTIL_DUMP = 4096;

    /**
     * The most blocks a builder places in one AI step when his time allows it.
     */
    private static final int MAX_PLACEMENTS_PER_STEP = 12;

    /**
     * Building level to purge mobs at the build site.
     */
    private static final int LEVEL_TO_PURGE_MOBS = 4;

    /**
     * Current goto path
     */
    PathResult gotoPath = null;

    /**
     * Initialize the builder and add all his tasks.
     *
     * @param job the job he has.
     */
    public EntityAIStructureBuilder(@NotNull final JobBuilder job)
    {
        super(job);
        worker.setCanPickUpLoot(true);
        registerTargets(
          new AITarget(ASSIST_PLAN, this::assistPlan, 20),
          new AITarget(ASSIST_GATHER, this::assistGather, 20),
          new AITarget(ASSIST_WORK, this::assistWork, STANDARD_DELAY),
          new AITarget(ASSIST_MINE, this::assistMine, 10),
          new AITarget(ASSIST_RETURN, this::assistReturn, 20));
    }

    @Override
    public int getBreakSpeedLevel()
    {
        return getSecondarySkillLevel();
    }

    @Override
    public int getPlaceSpeedLevel()
    {
        return getPrimarySkillLevel();
    }

    @Override
    public Class<BuildingBuilder> getExpectedBuildingClass()
    {
        return BuildingBuilder.class;
    }

    /**
     * Checks if we got a valid workorder.
     *
     * @return true if we got a workorder to work with
     */
    private boolean checkForWorkOrder()
    {
        if (!building.hasWorkOrder())
        {
            building.setProgressPos(null, BuildingProgressStage.CLEAR);
            worker.getCitizenData().setStatusPosition(null);
            return false;
        }

        final IWorkOrder wo = building.getWorkOrder();

        if (wo == null)
        {
            building.setWorkOrder(null);
            building.setProgressPos(null, null);
            worker.getCitizenData().setStatusPosition(null);
            return false;
        }

        final IBuilding building = job.getColony().getServerBuildingManager().getBuilding(wo.getLocation());
        if (building == null && wo instanceof WorkOrderBuilding && wo.getWorkOrderType() != WorkOrderType.REMOVE)
        {
            this.building.complete(worker.getCitizenData());
            return false;
        }

        return true;
    }

    @Override
    public void setStructurePlacer(final BuildingStructureHandler<JobBuilder, BuildingBuilder> structure)
    {
        if (building.getWorkOrder().getIteratorType().isEmpty())
        {
            final String mode = BuilderModeSetting.getActualValue(building);
            building.getWorkOrder().setIteratorType(mode);
        }

        structurePlacer = new Tuple<>(new StructurePlacer(structure, building.getWorkOrder().getIteratorType()), structure);

        if (usesPhysicalReach() && BuilderScaffolding.enabled() && mayNeedScaffolding(structure.getBluePrint()))
        {
            // a tall structure: the scaffolding is requested like the materials, so it is in the hut when the first tower is needed
            scaffold.ensureStock(WorkerSettings.scaffoldStock());
        }

        final WorkOrderCollab collab = building.getWorkOrder().getCollab();
        if (!collab.hasStageTotals())
        {
            for (final BuildingProgressStage stage : structure.getStages())
            {
                collab.setStageTotal(stage, BuilderStageRules.estimateBlocks(structure.getBluePrint(), stage));
            }
            collab.setHighBlocks(BuilderStageRules.estimateHighBlocks(structure.getBluePrint(), BuildReach.maxHeightNextToWall(BuildReach.EYE_HEIGHT, WorkerSettings.reach()) + 1));
        }
    }

    private static boolean mayNeedScaffolding(final com.ldtteam.structurize.blueprints.v1.Blueprint blueprint)
    {
        return BuildReach.mayNeedScaffolding(blueprint.getSizeX(), blueprint.getSizeY(), blueprint.getSizeZ(), BuildReach.EYE_HEIGHT, WorkerSettings.reach());
    }

    @Override
    public boolean isAfterDumpPickupAllowed()
    {
        return !checkForWorkOrder();
    }

    @Override
    protected IAIState startWorkingAtOwnBuilding()
    {
        if (building.hasCarry())
        {
            // materials of another builder's structure go back before anything else
            return ASSIST_RETURN;
        }
        if (!building.hasWorkOrder() && building.getColony().getWorkManager().getAssistedOrder(building.getID()) != null)
        {
            return ASSIST_PLAN;
        }

        if (!walkToBuilding())
        {
            return getState();
        }

        if (checkForWorkOrder())
        {
            if (usesPhysicalReach() && building.getWorkOrder() != null && building.getWorkOrder().getBlueprint() != null && mayNeedScaffolding(building.getWorkOrder().getBlueprint()))
            {
                scaffold.prefetch();
            }
            final IAIState state = super.startWorkingAtOwnBuilding();
            if (state == IDLE)
            {
                return LOAD_STRUCTURE;
            }
            return state;
        }
        return IDLE;
    }

    /**
     * Kill all mobs at the building site.
     */
    private void killMobs()
    {
        if (building.getBuildingLevel() >= LEVEL_TO_PURGE_MOBS && building.getWorkOrder() != null && building.getWorkOrder().getWorkOrderType() == WorkOrderType.BUILD)
        {
            final BlockPos buildingPos = building.getWorkOrder().getLocation();
            final IBuilding building = worker.getCitizenColonyHandler().getColonyOrRegister().getServerBuildingManager().getBuilding(buildingPos);
            if (building != null)
            {
                WorldUtil.getEntitiesWithinBuilding(world, Monster.class, building, null).forEach(e -> e.remove(Entity.RemovalReason.DISCARDED));
            }
        }
    }

    @Override
    public void checkForExtraBuildingActions()
    {
        if (!building.hasPurgedMobsToday())
        {
            killMobs();
            building.setPurgedMobsToday(true);
        }
    }

    @Override
    protected boolean mineBlock(@NotNull final BlockPos blockToMine, @NotNull final BlockPos safeStand)
    {
        return mineBlock(blockToMine, safeStand, true, !IColonyManager.getInstance().getCompatibilityManager().isOre(world.getBlockState(blockToMine)), null);
    }

    @Override
    public IAIState afterRequestPickUp()
    {
        return INVENTORY_FULL;
    }

    @Override
    public IAIState afterDump()
    {
        return PICK_UP;
    }

    // ------------------------------------------------------------------ physical reach

    /**
     * The walk to a spot from which a block is in reach: the spot, when it was picked, and the spots that did not get him there.
     */
    /** The longest wait before a spot that did not work is tried again (ticks). */
    private static final long BAD_SPOT_MAX_WAIT = 1600L;

    private final class ReachWalk
    {
        BlockPos spot = null;
        long since = 0L;
        long budget = 100L;
        final Set<BlockPos> bad = new HashSet<>();
        /** How often a spot did not work, and when it may be tried again: the wait doubles with every failure. */
        final java.util.Map<BlockPos, Integer> fails = new java.util.HashMap<>();
        final java.util.Map<BlockPos, Long> retryAt = new java.util.HashMap<>();

        void markBad(final BlockPos spot, final long now)
        {
            bad.add(spot);
            final int n = fails.merge(spot, 1, Integer::sum);
            retryAt.put(spot, now + Math.min(200L << Math.min(n - 1, 4), BAD_SPOT_MAX_WAIT));
            badSince = now;
        }
        long badSince = 0L;
        long noSpotUntil = 0L;
        long noSpotSince = 0L;
        net.minecraft.world.phys.Vec3 lastPosition = null;
        long lastMoved = 0L;

        void reset()
        {
            spot = null;
            bad.clear();
            fails.clear();
            retryAt.clear();
            badSince = 0L;
            noSpotUntil = 0L;
            noSpotSince = 0L;
            lastPosition = null;
        }
    }

    private final ReachWalk mainWalk     = new ReachWalk();
    private final ReachWalk scaffoldWalk = new ReachWalk();

    private final BuilderScaffolding scaffold = new BuilderScaffolding(this);

    @Override
    protected boolean usesPhysicalReach()
    {
        return WorkerSettings.physicalReach();
    }

    @Override
    protected boolean canReachNow(final BlockPos pos)
    {
        return !usesPhysicalReach() || BuildReach.inReach(worker.getX(), worker.getEyeY(), worker.getZ(), pos, WorkerSettings.reach());
    }

    @Override
    protected List<BlockPos> workAhead(final BlockPos target, final int n)
    {
        if (assistOrder != null && !building.hasWorkOrder() && !batch.isEmpty())
        {
            // a helper works through his leased positions in the order of his batch
            final List<BlockPos> list = new ArrayList<>(batch.size());
            list.add(target);
            for (final LeaseAllocator.Planned planned : batch)
            {
                if (!planned.world().equals(target))
                {
                    list.add(planned.world());
                }
            }
            return list;
        }
        return super.workAhead(target, n);
    }

    @Override
    protected List<BlockPos> peekWorkAhead(final int n)
    {
        if (assistOrder != null && !building.hasWorkOrder())
        {
            final List<BlockPos> list = new ArrayList<>();
            for (final LeaseAllocator.Planned planned : batch)
            {
                list.add(planned.world());
                if (list.size() >= n)
                {
                    break;
                }
            }
            return list;
        }
        return super.peekWorkAhead(n);
    }

    @Override
    protected void resetWalkTarget()
    {
        mainWalk.reset();
        if (gotoPath != null)
        {
            gotoPath.cancel();
            gotoPath = null;
        }
        super.resetWalkTarget();
    }

    @Override
    protected void resetScaffoldWalk()
    {
        scaffoldWalk.reset();
    }

    @Override
    protected void resetReachWalk()
    {
        mainWalk.reset();
    }

    @Override
    protected void workFromScaffold(final BlockPos stand)
    {
        // the tower is up: the spots that did not work out before get a new chance (the tower may be the way to them), and the top of the tower is the first
        mainWalk.reset();
        workFrom = stand;
    }

    @Override
    protected boolean walkIntoReachFor(final BlockPos target, final Supplier<List<BlockPos>> ahead, final boolean inBlocks, @Nullable final Predicate<BlockPos> accept)
    {
        return walkIntoReach(scaffoldWalk, target, ahead, inBlocks, accept, false);
    }

    /**
     * Gets the builder to a place where he reaches the block: stays where he stands if the block is in reach from there, else walks to the
     * best spot (the one that serves most of the blocks he works on next).
     *
     * @param walk       the walk state.
     * @param target     the block.
     * @param ahead      the blocks that count for the choice of the spot, the first one is the target.
     * @param inBlocks   whether the spot may be made of the cell itself (scaffolding work).
     * @param accept     extra condition for a spot, or null.
     * @param mayScaffold whether a block without any spot gets a tower.
     * @return true once the builder reaches the block.
     */
    private boolean walkIntoReach(
      final ReachWalk walk,
      final BlockPos target,
      final Supplier<List<BlockPos>> ahead,
      final boolean inBlocks,
      @Nullable final Predicate<BlockPos> accept,
      final boolean mayScaffold)
    {
        final double reach = WorkerSettings.reach();
        if (BuildReach.inReach(worker.getX(), worker.getEyeY(), worker.getZ(), target, reach)
              && (accept == null || accept.test(worker.blockPosition())))
        {
            if (walk.spot != null && !world.getBlockState(worker.blockPosition()).is(net.minecraft.tags.BlockTags.CLIMBABLE))
            {
                // (not in the middle of a climb: a builder who stops inside a column gets no path up from there)
                worker.getNavigation().stop();
                walk.spot = null;
            }
            walk.noSpotUntil = 0L;
            walk.noSpotSince = 0L;
            workFrom = worker.blockPosition();
            prevBlockPosition = target;
            return true;
        }

        // A long way to the block: abstract walking (a timed relocation) instead of a long path, if the settings allow it
        final Boolean relocated = AbstractWalking.tryRelocate(worker, target, 4);
        if (relocated != null && !relocated)
        {
            return false;
        }

        final long now = world.getGameTime();
        if (!walk.bad.isEmpty() && now - walk.badSince > 200L)
        {
            // spots that did not work get another chance after a while (a tower may have been climbable by now), a spot that failed again and again
            // after a longer while: every try costs a walk to the end of a path that does not lead there
            walk.bad.removeIf(p -> walk.retryAt.getOrDefault(p, 0L) <= now);
            walk.noSpotUntil = 0L;
            walk.badSince = now;
        }
        final double margin = reach - ReachSpots.MARGIN;
        if (walk.spot != null)
        {
            // a walk that does not get anywhere (a path along scaffolding that the builder cannot follow) is given up after a while
            final boolean pathReady = worker.getNavigation() instanceof MinecoloniesAdvancedPathNavigate pn && pn.getPathResult() != null && pn.getPathResult().isDone();
            if (walk.lastPosition == null || !pathReady || worker.position().distanceTo(walk.lastPosition) >= 0.4D)
            {
                walk.lastPosition = worker.position();
                walk.lastMoved = now;
            }
            final boolean timedOut = now - walk.since > walk.budget || now - walk.lastMoved > 60L;
            final BlockPos effective = world.getBlockState(walk.spot.below()).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING) ? walk.spot.below() : walk.spot;
            if (!timedOut && StandSpots.canStandAt(world, walk.spot) && !BuildReach.inReachFromFeet(effective, worker.getEyeHeight(), target, margin)
                  && !(worker.getNavigation() instanceof MinecoloniesAdvancedPathNavigate nav0 && nav0.isStuck()))
            {
                // the block the spot was picked for is not the one he works on now: another spot (this one is no worse for it)
                walk.spot = null;
                worker.getNavigation().stop();
            }
            else if (walk.spot != null && (timedOut || !StandSpots.canStandAt(world, walk.spot) || (worker.getNavigation() instanceof MinecoloniesAdvancedPathNavigate nav && nav.isStuck())))
            {
                scaffold.note("spot " + walk.spot + " for " + target + " does not work (timedOut=" + timedOut + ") builder at " + worker.blockPosition());
                walk.markBad(walk.spot, now);
                walk.spot = null;
                worker.getNavigation().stop();
            }
        }
        if (walk == mainWalk && walk.spot == null && workFrom != null && !workFrom.equals(worker.blockPosition()) && !walk.bad.contains(workFrom)
              && StandSpots.canStandAt(world, workFrom)
              && BuildReach.inReachFromFeet(world.getBlockState(workFrom.below()).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING) ? workFrom.below() : workFrom, worker.getEyeHeight(), target, margin))
        {
            // a spot the unstuck ladder or a tower picked
            walk.spot = workFrom;
            walk.since = now;
            walk.lastPosition = null;
            walk.budget = 100L + 12L * (long) Math.ceil(ReachSpots.walkCost(worker.blockPosition(), workFrom));
        }
        if (walk.spot == null)
        {
            if (now < walk.noSpotUntil)
            {
                return false;
            }
            // (the top of a built wall is a place of last resort: towers come first, and the walk to it often does not lead there; when no tower is
            // possible - no scaffolding items, none came in time - it is a place to work from like any other)
            final boolean wallTopsAllowed = scaffold.unavailable() || scaffold.hasNoItems();
            final List<ReachSpots.Spot> spots = ReachSpots.best(world, ahead.get(), reach, worker.getEyeHeight(), worker.blockPosition(),
              p -> !walk.bad.contains(p) && canWalkTo(p) && (inBlocks || (hasWayDown(p) && (wallTopsAllowed || !isBuiltStructureBlock(p.below())))) && (accept == null || accept.test(p)), 1, inBlocks,
              !inBlocks && servesAnyOrder());
            if (spots.isEmpty())
            {
                // nothing to stand on within reach of the block: this is a job for scaffolding
                walk.noSpotUntil = now + 20;
                if (walk.noSpotSince == 0L)
                {
                    walk.noSpotSince = now;
                }
                if (!mayScaffold)
                {
                    final List<ReachSpots.Spot> all = ReachSpots.best(world, ahead.get(), reach, worker.getEyeHeight(), worker.blockPosition(), null, 200, inBlocks, false);
                    final long walkable = all.stream().filter(sp -> canWalkTo(sp.feet())).count();
                    final long accepted = all.stream().filter(sp -> canWalkTo(sp.feet()) && (accept == null || accept.test(sp.feet()))).count();
                    final long notBad = all.stream().filter(sp -> canWalkTo(sp.feet()) && (accept == null || accept.test(sp.feet())) && !walk.bad.contains(sp.feet())).count();
                    scaffold.note("no spot for " + target + " (scaffold work) builder at " + worker.blockPosition() + " candidates=" + all.size() + " walkable=" + walkable + " accepted=" + accepted + " notBad=" + notBad
                                    + " bad=" + walk.bad + " first=" + (all.isEmpty() ? "-" : all.get(0).feet().toShortString()));
                }
                else
                {
                    final List<ReachSpots.Spot> all = ReachSpots.best(world, ahead.get(), reach, worker.getEyeHeight(), worker.blockPosition(), null, 200, inBlocks, false);
                    final long walkable = all.stream().filter(sp -> canWalkTo(sp.feet())).count();
                    final long notBad = all.stream().filter(sp -> canWalkTo(sp.feet()) && !walk.bad.contains(sp.feet())).count();
                    final long wayDown = all.stream().filter(sp -> canWalkTo(sp.feet()) && !walk.bad.contains(sp.feet()) && hasWayDown(sp.feet())).count();
                    scaffold.note("no spot for " + target + " builder at " + worker.blockPosition() + " candidates=" + all.size() + " walkable=" + walkable + " notBad=" + notBad + " wayDown=" + wayDown
                                    + " wallTopsAllowed=" + wallTopsAllowed + " bad=" + walk.bad + " first=" + (all.isEmpty() ? "-" : all.get(0).feet().toShortString()) + " ahead=" + ahead.get().size());
                }
                if (mayScaffold)
                {
                    tryScaffold(target);
                }
                return false;
            }
            walk.spot = spots.get(0).feet();
            walk.since = now;
            walk.budget = 100L + 12L * (long) Math.ceil(spots.get(0).walk());
            walk.lastPosition = null;
            walk.noSpotSince = 0L;
            scaffold.note((walk == mainWalk ? "spot " : "scaffold spot ") + walk.spot + " for " + target + " covers " + spots.get(0).covered() + " walk " + String.format("%.1f", spots.get(0).walk()) + " builder at " + worker.blockPosition());
        }

        if (walk == mainWalk)
        {
            workFrom = walk.spot;
        }
        // walk to the very spot (the usual walk stops a block and a half short, and that is a block below the top of a tower)
        final int walked = EntityNavigationUtils.walkToBlock(worker, walk.spot);
        if (walked != 0)
        {
            scaffold.note("walk to " + walk.spot + " ended (" + walked + ") at " + worker.blockPosition() + " for " + target + " nav stuck=" + (worker.getNavigation() instanceof MinecoloniesAdvancedPathNavigate n && n.isStuck())
                            + " path=" + (worker.getNavigation() instanceof MinecoloniesAdvancedPathNavigate n2 && n2.getPathResult() != null ? n2.getPathResult().getStatus() + " " + pathDescription(n2) : "-") + columnDescription(walk.spot));
            // the walk is over, and the block is still out of reach: this spot does not work
            walk.markBad(walk.spot, now);
            walk.spot = null;
        }
        return false;
    }


    private String columnDescription(final BlockPos spot)
    {
        final StringBuilder sb = new StringBuilder(" column=");
        for (int y = spot.getY() - 7; y <= spot.getY() + 1; y++)
        {
            final BlockPos p = new BlockPos(spot.getX(), y, spot.getZ());
            final var st = world.getBlockState(p);
            sb.append(y - spot.getY()).append(':').append(st.isAir() ? "air" : st.getBlock().getDescriptionId().replace("block.minecraft.", "")).append(st.is(net.minecraft.world.level.block.Blocks.SCAFFOLDING) ? "[d" + st.getValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE) + (st.getValue(net.minecraft.world.level.block.ScaffoldingBlock.BOTTOM) ? "b" : "") + "]" : "").append(' ');
        }
        return sb.toString();
    }

    private static String pathDescription(final MinecoloniesAdvancedPathNavigate nav)
    {
        final var path = nav.getPathResult().getPath();
        if (path == null)
        {
            return "no path";
        }
        final StringBuilder sb = new StringBuilder("nodes=" + path.getNodeCount() + " next=" + path.getNextNodeIndex() + " [");
        for (int i = 0; i < path.getNodeCount() && i < 14; i++)
        {
            sb.append(path.getNode(i).asBlockPos().toShortString()).append(' ');
        }
        sb.append(']');
        if (path.getNextNodeIndex() < path.getNodeCount() && path.getNode(path.getNextNodeIndex()) instanceof com.minecolonies.core.entity.pathfinding.PathPointExtended ppe)
        {
            sb.append(" nextNode=").append(ppe.asBlockPos().toShortString()).append(" ladder=").append(ppe.isOnLadder()).append(" facing=").append(ppe.getLadderFacing());
        }
        return sb.toString();
    }

    /**
     * What the builder is up to, for the logs of the tests.
     */
    public String debugInfo()
    {
        return "pos=" + worker.blockPosition().toShortString() + " eye=" + String.format("%.1f", worker.getEyeY()) + " workFrom=" + (workFrom == null ? "-" : workFrom.toShortString())
                 + " mine=" + (blockToMine == null ? "-" : blockToMine.toShortString()) + " spot=" + (mainWalk.spot == null ? "-" : mainWalk.spot.toShortString())
                 + " bad=" + mainWalk.bad.size() + " reachTarget=" + (reachTarget == null ? "-" : reachTarget.toShortString()) + " nav=" + (worker.getNavigation().isDone() ? "done" : "busy")
                 + " stuck=" + debugStuck() + " scaffold=" + scaffold.debug()
                 + (worker.getNavigation() instanceof MinecoloniesAdvancedPathNavigate pn && pn.getPathResult() != null && !worker.getNavigation().isDone() ? " path=" + pn.getPathResult().getStatus() + " " + pathDescription(pn) : "");
    }

    // ------------------------------------------------------------------ scaffolding

    @Override
    protected boolean cannotReachAtAll(final BlockPos target)
    {
        // no spot for 40 ticks and no tower under way: nothing to wait for
        return usesPhysicalReach() && mainWalk.noSpotSince != 0L && world.getGameTime() - mainWalk.noSpotSince >= 40L && !scaffold.busy() && scaffold.denied();
    }

    @Override
    protected boolean scaffoldUnavailable()
    {
        return scaffold.unavailable();
    }

    @Override
    protected void onRetryRound()
    {
        scaffold.clearDenial();
        mainWalk.reset();
    }

    @Override
    protected boolean tryEscapeTower()
    {
        return usesPhysicalReach() && scaffold.startEscape();
    }

    @Override
    protected boolean tryScaffold(final BlockPos target)
    {
        return usesPhysicalReach() && scaffold.start(target);
    }

    @Override
    protected boolean scaffoldTick()
    {
        return usesPhysicalReach() && scaffold.tick();
    }

    @Override
    protected IBuilderWorkOrder scaffoldOrder()
    {
        if (assistOrder != null && !building.hasWorkOrder())
        {
            return assistOrder;
        }
        return building.getWorkOrder();
    }

    @Override
    protected com.ldtteam.structurize.placement.structure.IStructureHandler scaffoldHandler()
    {
        if (assistOrder != null && !building.hasWorkOrder() && assistHandler != null)
        {
            return assistHandler;
        }
        return super.scaffoldHandler();
    }

    @Override
    protected boolean isScaffoldHeld(final BlockPos worldPos)
    {
        final IBuilderWorkOrder order = building.getWorkOrder();
        return order != null && !order.getScaffolds().isEmpty() && order.getScaffolds().contains(worldPos);
    }

    @Override
    protected boolean scaffoldBeforeStageEnd()
    {
        final IBuilderWorkOrder order = building.getWorkOrder();
        if (order == null || !usesPhysicalReach())
        {
            return false;
        }
        if (order.getScaffolds().isEmpty() && !scaffold.busy())
        {
            return false;
        }
        scaffold.requestTakeDownAll();
        return true;
    }

    @Override
    protected boolean consumeScaffoldRescan()
    {
        return scaffold.consumeRescan();
    }

    @Override
    protected void onStructureReset()
    {
        scaffold.reset();
        // the scaffolding items he carries go back into his hut
        scaffold.returnStock();
        mainWalk.reset();
        scaffoldWalk.reset();
    }

    @Override
    public boolean walkToConstructionSite(final BlockPos currentBlock)
    {
        if (usesPhysicalReach() && currentBlock.equals(reachTarget))
        {
            return walkIntoReach(mainWalk, currentBlock, () -> workAhead(currentBlock), false, null, true);
        }

        // A builder that is already within the normal working radius does not
        // need an asynchronous path calculation.  This is also important for
        // freshly-created worlds where the path executor can briefly lag the
        // server tick; the placement code below already enforces the same
        // five-block horizontal working radius.
        if (workFrom == null && BlockPosUtil.getDistance2D(worker.blockPosition(), currentBlock) <= 5)
        {
            workFrom = worker.blockPosition();
            prevBlockPosition = currentBlock;
            return true;
        }

        // A long way to the block: abstract walking (a timed relocation) instead of a long path, if the settings allow it
        final Boolean relocated = AbstractWalking.tryRelocate(worker, currentBlock, 4);
        if (relocated != null)
        {
            if (relocated)
            {
                workFrom = worker.blockPosition();
                prevBlockPosition = currentBlock;
            }
            return relocated;
        }

        if (workFrom != null && workFrom.getX() == currentBlock.getX() && workFrom.getZ() == currentBlock.getZ() && workFrom.getY() >= currentBlock.getY())
        {
            // Reset working position when standing ontop
            workFrom = null;
        }

        // A previous path can remain in progress after the worker has already
        // reached the next block.  Stop that stale path and use the worker's
        // current position instead of waiting forever for an unreachable
        // safe-position node.
        final BlockPos workerPosition = worker.blockPosition();
        if (BlockPosUtil.getDistance2D(workerPosition, currentBlock) <= 5
              && !(workerPosition.getX() == currentBlock.getX()
                     && workerPosition.getZ() == currentBlock.getZ()
                     && workerPosition.getY() >= currentBlock.getY()))
        {
            worker.getNavigation().stop();
            workFrom = workerPosition;
            prevBlockPosition = currentBlock;
            return true;
        }

        if (workFrom == null)
        {
            if (gotoPath == null || gotoPath.isCancelled())
            {
                final PathJobMoveCloseToXNearY pathJob = new PathJobMoveCloseToXNearY(world,
                    currentBlock,
                    getSiteLocation(),
                    4,
                    worker);
                gotoPath = ((MinecoloniesAdvancedPathNavigate) worker.getNavigation()).setPathJob(pathJob, currentBlock, 1.0, false);
                pathJob.getPathingOptions().canDrop = false;
                pathJob.extraNodes = 0;
            }
            else if (gotoPath.isDone())
            {
                if (gotoPath.getPath() != null)
                {
                    workFrom = gotoPath.getPath().getTarget();
                }
                gotoPath = null;
            }

            if (prevBlockPosition != null)
            {
                // This is good for building, and bad for mining, this is why mining should validate workFrom.
                return BlockPosUtil.dist(prevBlockPosition, currentBlock) <= 10;
            }
            return false;
        }

        if (!walkToSafePos(workFrom))
        {
            // Something might have changed, new wall and we can't reach the position anymore. Reset workfrom if stuck.
            if (worker.getNavigation() instanceof MinecoloniesAdvancedPathNavigate pathNavigate && pathNavigate.isStuck())
            {
                workFrom = null;
            }
            return false;
        }

        if (BlockPosUtil.getDistance2D(worker.blockPosition(), currentBlock) > 5)
        {
            if (BlockPosUtil.dist(workFrom, getSiteLocation()) < 100)
            {
                prevBlockPosition = currentBlock;
                workFrom = null;
                return true;
            }
            workFrom = null;
            return false;
        }

        prevBlockPosition = currentBlock;
        return true;
    }

    @Override
    public boolean shallReplaceSolidSubstitutionBlock(final Block worldBlock, final BlockState worldMetadata)
    {
        return false;
    }

    @Override
    public int getBlockMiningTime(@NotNull final BlockState state, @NotNull final BlockPos pos)
    {
        // a builder breaks at half the time of other workers (SPEED_BUFF_0), faster with the builder speed of the settings, but never in less than the
        // floor of the break time
        return (int) Math.ceil(Math.max(WorkTiming.MIN_BREAK_TICKS, super.getBlockMiningTime(state, pos) * SPEED_BUFF_0 / WorkerSettings.builderSpeed()));
    }

    @Override
    protected int getMaxPlacementsPerStep()
    {
        return MAX_PLACEMENTS_PER_STEP;
    }

    @Override
    protected double getPlacementSpeed()
    {
        return WorkerSettings.builderSpeed();
    }

    /**
     * Calculates after how many actions the AI should dump its inventory.
     *
     * @return the number of actions done before item dump.
     */
    @Override
    protected int getActionsDoneUntilDumping()
    {
        return ACTIONS_UNTIL_DUMP;
    }

    @Override
    protected void sendCompletionMessage(final IWorkOrder wo)
    {
        super.sendCompletionMessage(wo);

        final BlockPos position = wo.getLocation();
        boolean showManualSuffix = false;
        if (building.getManualMode())
        {
            showManualSuffix = true;
            for (final IWorkOrder workorder : building.getColony().getWorkManager().getWorkOrders().values())
            {
                if (workorder.getID() != wo.getID() && building.getID().equals(workorder.getClaimedBy()))
                {
                    showManualSuffix = false;
                }
            }
        }

        final MutableComponent message = Component.translatableEscape(
                wo.getWorkOrderType().getCompletionMessageID(),
                wo.getDisplayName(),
                BlockPosUtil.calcDirection(building.getColony().getCenter(), position).getLongText())
            .withStyle(style -> style
                .withHoverEvent(new HoverEvent.ShowText(
                    Component.translatable("message.positiondist",
                        position.getX(),
                        position.getY(),
                        position.getZ(),
                        (int) BlockPosUtil.dist(building.getColony().getCenter(), position))))
            .withColor(ChatFormatting.GREEN));

        if (showManualSuffix)
        {
            message.append(Component.translatableEscape(COM_MINECOLONIES_COREMOD_ENTITY_BUILDER_MANUAL_SUFFIX));
        }

        MessageUtils.forCitizen(worker, message).sendTo(worker.getCitizenColonyHandler().getColonyOrRegister().getImportantMessageEntityPlayers());
    }

    @Override
    public boolean canGoIdle()
    {
        // a builder who helps or still has materials to bring back keeps his work AI running (the citizen AI ticks it only if he cannot idle)
        return !building.hasWorkOrder() && !building.hasCarry() && assistOrder == null && building.getColony().getWorkManager().getAssistedOrder(building.getID()) == null;
    }

    @Override
    protected IBuilding getBuildingToDump()
    {
        // what a helper mines (and what he carries) goes to the hut of the builder who leads the structure
        final BlockPos carryLead = building.getCarryLead();
        if (assistOrder != null && !building.hasWorkOrder())
        {
            final AbstractBuildingStructureBuilder lead = leadOf(assistOrder);
            if (lead != null)
            {
                return lead;
            }
        }
        else if (carryLead != null)
        {
            final IBuilding lead = building.getColony().getServerBuildingManager().getBuilding(carryLead);
            if (lead != null)
            {
                return lead;
            }
        }
        return super.getBuildingToDump();
    }

    // ------------------------------------------------------------------ the lead of a shared structure

    /**
     * Positions right after the lead's cursor that helpers leave to the lead.
     */
    private static final int LEAD_BUFFER = 4;

    private WorkOrderCollab collabCache     = null;
    private long            collabCacheTick = -1;

    /**
     * The shared state of the order of this builder, looked up once per tick.
     */
    private WorkOrderCollab leadCollab()
    {
        final long now = world.getGameTime();
        if (collabCacheTick != now)
        {
            final IBuilderWorkOrder order = building.getWorkOrder();
            collabCache = order == null ? null : order.getCollab();
            collabCacheTick = now;
        }
        return collabCache;
    }

    @Override
    protected boolean isPositionReserved(final BlockPos worldPos)
    {
        final WorkOrderCollab collab = leadCollab();
        return collab != null && (collab.isLeasedByOther(worldPos, building.getID()) || collab.isInOthersTerritory(worldPos, building.getID()));
    }

    @Override
    protected boolean canFinishStage()
    {
        final WorkOrderCollab collab = leadCollab();
        if (collab != null && collab.hasTerritory())
        {
            // the lead is through with his part: the parts are given up, and he scans the stage again for what is left in the others'
            scaffold.note("lead has nothing left in his part: territory given up, remaining=" + collab.getUnleasedStageRemaining() + " leases=" + collab.getLeaseCount());
            collab.dissolveTerritory();
            return false;
        }
        if (collab == null || collab.getOpenLeasesExcluding(building.getID()) == 0)
        {
            return true;
        }
        // the lead has nothing left but what helpers have leased: they keep the position they work on and half of the rest, the lead takes the other
        // half (the stage is scanned again for it), so nobody waits for the other at the end of the stage
        for (final BlockPos helper : collab.getAssistantHuts())
        {
            final int stolen = collab.stealLeases(helper);
            if (stolen > 0)
            {
                takeCarryOfStolenFrom(helper, stolen);
            }
        }
        return false;
    }

    @Override
    protected void onBlockProcessed()
    {
        final WorkOrderCollab collab = leadCollab();
        if (collab != null)
        {
            collab.setLeadPending(building.getID(), null);
            collab.blockProcessed();
        }
        building.setWaitingFor(Map.of());
    }

    /**
     * The lead took positions back from a helper: the materials that the helper took for them come with them (about an item for each position), so the lead does not
     * run short while the helper keeps what he took for work that is not his any more.
     */
    private void takeCarryOfStolenFrom(final BlockPos helperHutPos, final int positions)
    {
        final IBuilding hut = building.getColony().getServerBuildingManager().getBuilding(helperHutPos);
        if (!(hut instanceof AbstractBuildingStructureBuilder helperHut) || !building.getID().equals(helperHut.getCarryLead()))
        {
            return;
        }
        final ICitizenData helper = leadCitizen(helperHut);
        if (helper == null)
        {
            return;
        }
        int left = positions;
        for (final Map.Entry<ItemStorage, Integer> entry : helperHut.getCarry().entrySet())
        {
            if (left <= 0)
            {
                break;
            }
            final ItemStack kind = entry.getKey().getItemStack();
            final int moved = ItemMover.move(helper.getInventory(), worker.getInventoryCitizen(), sameAs(kind), Math.min(left, entry.getValue()));
            if (moved > 0)
            {
                helperHut.reduceCarry(Map.of(entry.getKey(), moved));
                left -= moved;
            }
        }
    }

    /**
     * The lead is short of materials that his helpers carry (they took them for positions that he has taken back since, or have not used yet): he takes
     * them back from the helpers' inventories (a walk to the hut and a request for what is in the pockets of a helper is the long way round).
     */
    private boolean takeBackFromHelpers(final List<ItemStack> required)
    {
        final WorkOrderCollab collab = leadCollab();
        if (collab == null || collab.getAssistantCount() == 0)
        {
            return false;
        }
        boolean allThere = true;
        boolean movedAny = false;
        for (final ItemStack stack : required)
        {
            if (stack.isEmpty())
            {
                continue;
            }
            int missing = stack.getCount() - InventoryUtils.getItemCountInItemHandler(worker.getInventoryCitizen(), sameAs(stack));
            if (missing <= 0)
            {
                continue;
            }
            final ItemStorage key = new ItemStorage(stack.copyWithCount(1));
            for (final BlockPos hutPos : collab.getAssistantHuts())
            {
                final IBuilding hut = building.getColony().getServerBuildingManager().getBuilding(hutPos);
                if (!(hut instanceof AbstractBuildingStructureBuilder helperHut) || !building.getID().equals(helperHut.getCarryLead()))
                {
                    continue;
                }
                final ICitizenData helper = leadCitizen(helperHut);
                final int carried = helperHut.getCarry().getOrDefault(key, 0);
                if (helper == null || carried <= 0)
                {
                    continue;
                }
                final int moved = ItemMover.move(helper.getInventory(), worker.getInventoryCitizen(), sameAs(stack), Math.min(missing, carried));
                if (moved > 0)
                {
                    helperHut.reduceCarry(Map.of(key, moved));
                    missing -= moved;
                    movedAny = true;
                }
                if (missing <= 0)
                {
                    break;
                }
            }
            allThere &= missing <= 0;
        }
        return allThere && movedAny;
    }

    @Override
    protected boolean onMissingItems(final List<ItemStack> required)
    {
        if (takeBackFromHelpers(required))
        {
            // what the helpers carried is in the lead's inventory now: he goes on
            return true;
        }
        final Map<ItemStorage, Integer> waiting = new HashMap<>();
        for (final ItemStack stack : required)
        {
            if (!stack.isEmpty())
            {
                waiting.merge(new ItemStorage(stack.copyWithCount(1)), stack.getCount(), Integer::sum);
            }
        }
        building.setWaitingFor(waiting);
        return false;
    }

    @Override
    protected void onStepBlocked(final BlockPos worldPos)
    {
        final WorkOrderCollab collab = leadCollab();
        if (collab != null)
        {
            // helpers must not take the position the lead is working on or waiting for materials for
            collab.setLeadPending(building.getID(), worldPos);
        }
    }

    @Override
    public Tuple<BlockPos, BuildingProgressStage> getProgressPos()
    {
        final WorkOrderCollab collab = leadCollab();
        if (collab != null && collab.isRescanRequested())
        {
            // positions that helpers gave back lie behind the cursor: scan the stage again from its start
            final Tuple<BlockPos, BuildingProgressStage> progress = building.getProgress();
            collab.consumeRescan();
            if (progress != null)
            {
                building.setProgressPos(NULL_POS, progress.getB());
            }
        }
        return building.getProgress();
    }

    private BlockPos getSiteLocation()
    {
        if (assistOrder != null && !building.hasWorkOrder())
        {
            return assistOrder.getLocation();
        }
        return building.getWorkOrder().getLocation();
    }

    // ------------------------------------------------------------------ helping another builder

    private IBuilderWorkOrder                assistOrder   = null;
    private final List<LeaseAllocator.Planned> batch       = new ArrayList<>();
    private BuildingProgressStage            batchStage    = null;
    private CollabStructureHandler           assistHandler = null;
    private StructurePlacer                  assistPlacer  = null;
    private BlockPos                         assistMine    = null;
    private int                              assistTries   = 0;
    private final Set<BlockPos>              assistGaveUp  = new HashSet<>();
    private boolean                          gatherFromLead = false;
    private int                              gatherSteps    = 0;

    /**
     * Steps (about 20 ticks each) a helper tries to get his materials before he gives the positions back.
     */
    private static final int MAX_GATHER_STEPS = 90;

    /**
     * The builder who leads the order, if his hut really works on it.
     */
    private AbstractBuildingStructureBuilder leadOf(final IBuilderWorkOrder order)
    {
        final IBuilding hut = building.getColony().getServerBuildingManager().getBuilding(order.getClaimedBy());
        if (hut instanceof AbstractBuildingStructureBuilder lead && lead != building && lead.hasWorkOrder() && lead.getWorkOrder() == order)
        {
            return lead;
        }
        return null;
    }

    private boolean assistStillValid()
    {
        if (assistOrder == null || building.hasWorkOrder())
        {
            return false;
        }
        return building.getColony().getWorkManager().getAssistedOrder(building.getID()) == assistOrder && leadOf(assistOrder) != null;
    }

    /**
     * Stops helping: leases go back, the way the cargo returns is up to {@link #assistReturn()}.
     */
    private IAIState leaveAssist()
    {
        // the helper leaves: what he put up comes down, with the items back in his hut
        if (assistOrder != null && !assistOrder.getScaffolds().isEmpty())
        {
            final BlockPos hutPos = building.getPosition();
            // the towers that are all his; a tower with cells of the lead on it stays for the lead to take down
            final Set<BlockPos> mine = new HashSet<>();
            for (final List<com.minecolonies.core.colony.workorders.scaffold.ScaffoldLedger.Cell> tower : assistOrder.getScaffolds().components(null))
            {
                if (tower.stream().allMatch(c -> hutPos.equals(c.hut())))
                {
                    tower.forEach(c -> mine.add(c.pos()));
                }
            }
            com.minecolonies.core.colony.workorders.scaffold.ScaffoldSweeper.sweep(building.getColony(), assistOrder.getScaffolds(), cell -> mine.contains(cell.pos()), Integer.MAX_VALUE);
        }
        scaffold.reset();
        assistGaveUp.clear();
        dropBatch();
        assistOrder = null;
        assistHandler = null;
        assistPlacer = null;
        assistMine = null;
        batchStage = null;
        return building.hasCarry() ? ASSIST_RETURN : IDLE;
    }

    private void dropBatch()
    {
        if (assistOrder != null)
        {
            assistOrder.getCollab().releaseAll(building.getID());
        }
        batch.clear();
        assistMine = null;
        // the stock claimed for the positions is no one's again
        AssistReservations.releaseAll(building, false);
    }

    private void ensureHandler(final IBuilderWorkOrder order, final AbstractBuildingStructureBuilder lead, final BuildingProgressStage stage)
    {
        if (assistHandler == null || assistPlacer == null || batchStage != stage || assistHandler.getBluePrint() != order.getBlueprint())
        {
            if (batchStage != stage)
            {
                dropBatch();
            }
            batchStage = stage;
            assistHandler = new CollabStructureHandler(world, order, stage, worker, () -> fillBlockOf(lead));
            assistPlacer = new StructurePlacer(assistHandler, order.getIteratorType());
        }
    }

    private static BlockState fillBlockOf(final AbstractBuildingStructureBuilder lead)
    {
        return lead.getSetting(FILL_BLOCK).getValue().getBlock().defaultBlockState();
    }

    private static java.util.function.Predicate<ItemStack> sameAs(final ItemStack stack)
    {
        return candidate -> ItemStackUtils.compareItemStacksIgnoreStackSize(stack, candidate);
    }

    /**
     * Looks at the order and leases the next positions.
     */
    private IAIState assistPlan()
    {
        final IBuilderWorkOrder order = building.hasWorkOrder() ? null : building.getColony().getWorkManager().getAssistedOrder(building.getID());
        final AbstractBuildingStructureBuilder lead = order == null ? null : leadOf(order);
        if (order == null || lead == null)
        {
            return leaveAssist();
        }
        if (building.hasCarry() && !lead.getID().equals(building.getCarryLead()))
        {
            // still carrying for somebody else: give that back first
            return ASSIST_RETURN;
        }
        if (order != assistOrder)
        {
            dropBatch();
            assistOrder = order;
            assistHandler = null;
            assistPlacer = null;
        }

        final WorkOrderCollab collab = order.getCollab();
        final long now = world.getGameTime();
        collab.renew(building.getID(), now + BuilderCollab.leaseTicks());
        if (order.getBlueprint() == null)
        {
            order.loadBlueprint(world, blueprint -> { });
            setDelay(40);
            return getState();
        }

        final BuildingProgressStage stage = collab.getProgressStage();
        if (stage == null || !BuilderStageRules.isSharedStage(stage) || order.getIteratorType().isEmpty())
        {
            // nothing to share right now; wait at the site
            if (!batch.isEmpty())
            {
                dropBatch();
            }
            return waitAtTheSite();
        }

        ensureHandler(order, lead, stage);
        if (batch.isEmpty())
        {
            // leases that survived a reload
            for (final BlockPos leased : collab.getLeasedPositions(building.getID()))
            {
                final BlockPos local = assistHandler.getStructurePosFromWorld(leased);
                batch.add(new LeaseAllocator.Planned(local, leased, LeaseAllocator.requirements(world, assistPlacer, assistHandler, local, leased)));
            }
        }
        if (batch.isEmpty() && !collab.isTerritoryDissolved() && collab.getTotalBlocks() >= BuilderCollab.clusterMinBlocks())
        {
            final List<BlockPos> participants = new ArrayList<>();
            participants.add(lead.getID());
            participants.addAll(collab.getAssistantHuts());
            if (!collab.territoryCovers(participants))
            {
                // the lead and the helpers divide the rest of the stage between them
                LeaseAllocator.divideStage(world, order, stage, assistHandler, assistPlacer, participants);
                collab.setScanCursor(null);
                collab.setScanExhausted(false);
                scaffold.note("territory for " + participants.size() + " builders: " + collab.describeTerritory() + " remaining=" + collab.getUnleasedStageRemaining());
            }
        }
        if (batch.isEmpty())
        {
            if (collab.isScanExhausted() && collab.hasTerritory())
            {
                // nothing left in the part of this helper: the parts are given up
                scaffold.note("helper has nothing left in his part: territory given up, remaining=" + collab.getUnleasedStageRemaining() + " leases=" + collab.getLeaseCount());
                collab.dissolveTerritory();
            }
            if (collab.isScanExhausted())
            {
                if (collab.getUnleasedStageRemaining() > 2 * LEAD_BUFFER && now >= nextRescanAt)
                {
                    // the scan reached the end of the stage, but the lead has much of it left: the helper takes from what is left behind the scan (what
                    // the lead has not come to), after the few positions the lead leaves for himself
                    nextRescanAt = now + RESCAN_EVERY;
                    collab.setScanCursor(null);
                    collab.setScanExhausted(false);
                }
                else
                {
                    return waitAtTheSite();
                }
            }
            final Map<ItemStorage, Integer> stockMemo = new HashMap<>();
            final int room = (int) Math.max(1, InventoryUtils.openSlotCount(worker.getInventoryCitizen()) - MIN_OPEN_SLOTS);
            final List<LeaseAllocator.Planned> plan = LeaseAllocator.plan(world, order, stage, assistHandler, assistPlacer, leaseSizeFor(lead), collab.hasTerritory() ? 0 : LEAD_BUFFER,
              key -> stockMemo.computeIfAbsent(key, k -> Math.max(0, InventoryUtils.getItemCountInItemHandler(lead.getItemHandlerCap(), sameAs(k.getItemStack()))
                                                                    - AssistReservations.heldByOthers(lead, building, k.getItemStack()))
                                                      + leadCarried(lead, k.getItemStack())
                                                      + InventoryUtils.getItemCountInItemHandler(worker.getInventoryCitizen(), sameAs(k.getItemStack()))), room,
              pos -> assistGaveUp.contains(pos) || collab.isInOthersTerritory(pos, building.getID()), collab.getTotalBlocks() < BuilderCollab.clusterMinBlocks() ? null : worker.blockPosition());
            if (plan.isEmpty())
            {
                return waitAtTheSite();
            }
            gatherFromLead = false;
            gatherSteps = 0;
            scaffold.setIdleHelper(false);
            final long expiry = now + BuilderCollab.leaseTicks();
            for (final LeaseAllocator.Planned planned : plan)
            {
                collab.lease(planned.world(), building.getID(), expiry, false);
            }
            batch.addAll(plan);
        }

        for (final LeaseAllocator.Planned planned : batch)
        {
            if (!planned.required().isEmpty())
            {
                reserveBatchStock(lead);
                return ASSIST_GATHER;
            }
        }
        return ASSIST_WORK;
    }


    /**
     * A helper with nothing to lease waits at the site, but not on a tower: the tower cannot come down under him, and the lead may be waiting for it.
     */
    /**
     * A helper with nothing to do hands what is left of the materials he took to the lead (who is working at the site and may run short of exactly that),
     * instead of keeping it until he leaves.
     *
     * @return true while he is on his way to the lead.
     */
    private boolean handBackToLead()
    {
        if (!building.hasCarry() || assistOrder == null)
        {
            return false;
        }
        final AbstractBuildingStructureBuilder lead = leadOf(assistOrder);
        final ICitizenData leadCitizen = lead == null ? null : leadCitizen(lead);
        if (leadCitizen == null || leadCitizen.getEntity().isEmpty() || !lead.getID().equals(building.getCarryLead()))
        {
            return false;
        }
        if (Math.hypot(worker.getX() - leadCitizen.getEntity().get().getX(), worker.getZ() - leadCitizen.getEntity().get().getZ()) > 24.0D)
        {
            // (the lead is away at his hut for materials: no chase)
            return false;
        }
        if (!walkToLeadForHandOver(leadCitizen.getEntity().get()))
        {
            return true;
        }
        for (final Map.Entry<ItemStorage, Integer> entry : building.getCarry().entrySet())
        {
            final ItemStack kind = entry.getKey().getItemStack();
            final int have = InventoryUtils.getItemCountInItemHandler(worker.getInventoryCitizen(), sameAs(kind));
            final int moved = ItemMover.move(worker.getInventoryCitizen(), leadCitizen.getInventory(), sameAs(kind), Math.min(have, entry.getValue()));
            building.reduceCarry(Map.of(entry.getKey(), Math.max(moved, have == 0 ? entry.getValue() : 0)));
        }
        return false;
    }

    private IAIState waitAtTheSite()
    {
        if (workFrom != null && batch.isEmpty() && !scaffold.busy())
        {
            // a walk to a spot for a position that is not his any more (the lead took it back, the tower is gone) must not go on: the path planner takes him to the
            // nearest place it can find, which is wherever
            resetWalkTarget();
        }
        if (handBackToLead())
        {
            return getState();
        }
        // nothing to lease: the towers of this helper that nobody uses come down meanwhile (the lead takes his own down alone, and he has the most of the stage still)
        scaffold.setIdleHelper(true);
        if (runScaffoldTick())
        {
            return getState();
        }
        stepOffScaffolding();
        setDelay(40);
        return getState();
    }

    @Override
    protected boolean servesAnyOrder()
    {
        if (assistOrder != null && !building.hasWorkOrder())
        {
            // a helper works on his leased positions in the order that suits his spots (what he reaches from where he stands first), like the lead does in the
            // stages that place blocks
            return usesPhysicalReach() && (batchStage == BuildingProgressStage.BUILD_SOLID || batchStage == BuildingProgressStage.WEAK_SOLID || batchStage == BuildingProgressStage.DECORATE);
        }
        return super.servesAnyOrder();
    }

    @Override
    protected List<BlockPos> leadWorkAhead(final int n)
    {
        if (assistOrder != null && !building.hasWorkOrder())
        {
            final AbstractBuildingStructureBuilder lead = leadOf(assistOrder);
            final ICitizenData citizen = lead == null ? null : leadCitizen(lead);
            if (citizen != null && citizen.getEntity().isPresent() && citizen.getEntity().get().getCitizenJobHandler().getWorkAI() instanceof EntityAIStructureBuilder leadAi)
            {
                return leadAi.peekWork(n);
            }
            return List.of();
        }
        return peekWorkAhead(n);
    }

    /**
     * A builder who stands on or in scaffolding that he has no use for climbs down to the foot of it.
     *
     * @return true if he is on his way down.
     */
    private boolean stepOffScaffolding()
    {
        final IBuilderWorkOrder order = scaffoldOrder();
        if (order == null || order.getScaffolds().isEmpty())
        {
            return false;
        }
        final com.minecolonies.core.colony.workorders.scaffold.ScaffoldLedger ledger = order.getScaffolds();
        final BlockPos feet = worker.blockPosition();
        if (!ledger.contains(feet) && !ledger.contains(feet.below()))
        {
            return false;
        }
        int base = feet.getY();
        for (final com.minecolonies.core.colony.workorders.scaffold.ScaffoldLedger.Cell cell : ledger.all())
        {
            if (Math.abs(cell.pos().getX() - feet.getX()) <= 12 && Math.abs(cell.pos().getZ() - feet.getZ()) <= 12)
            {
                base = Math.min(base, cell.pos().getY());
            }
        }
        final int baseY = base;
        final BlockPos ground = StandSpots.nearest(world, feet, 10, feet.getY() - baseY + 2, 0,
          p -> p.getY() <= baseY + 1 && !ledger.contains(p) && !ledger.contains(p.below()) && !world.getBlockState(p.below()).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING) && canWalkTo(p));
        if (ground == null)
        {
            return false;
        }
        EntityNavigationUtils.walkToBlock(worker, ground);
        return true;
    }


    /**
     * How many positions the helper leases at once: the configured size, more when the hut of the lead is far (every lease costs a walk to the hut and back,
     * a lease of a few blocks that takes longer to fetch than to place is no help), at most four times the configured size.
     */
    private int leaseSizeFor(final AbstractBuildingStructureBuilder lead)
    {
        final int size = BuilderCollab.scaledLeaseSize(BuilderCollab.leaseSize(), Math.sqrt(worker.blockPosition().distSqr(lead.getPosition())));
        // towards the end of the stage the leases get smaller (a share of what is left for each of the builders, twice over), so nobody works on a big
        // lease while the others have nothing to do
        final WorkOrderCollab collab = assistOrder.getCollab();
        final int parts = 2 * (collab.getAssistantCount() + 1);
        return Math.max(1, Math.min(size, Math.max(MIN_SHARE, collab.getUnleasedStageRemaining() / parts)));
    }

    /**
     * A structure with fewer blocks than this is neither divided into parts for the builders nor leased as clusters: its next positions are close to each other
     * anyway, and the walk to a part of one's own costs more than it saves (a small ring was slower with a helper that kept to a part than with one that took the
     * next positions).
     */
    /** The smallest lease the stage hands out towards its end. */
    private static final int MIN_SHARE = 4;

    /** Ticks between two scans of a helper over the stage again. */
    private static final long RESCAN_EVERY = 100L;

    private long nextRescanAt = 0L;

    /**
     * Claims in the ledger the stock of the lead's hut that the batch needs and the helper does not have yet, as far as nobody else
     * claimed it: from now on the lead's other requests and the other helpers see it as taken. (A no-op without reservations.)
     */
    private void reserveBatchStock(final AbstractBuildingStructureBuilder lead)
    {
        AssistReservations.releaseAll(building, true);
        final long now = world.getGameTime();
        for (final Map.Entry<ItemStorage, Integer> missing : missingForBatch().entrySet())
        {
            final ItemStack kind = missing.getKey().getItemStack();
            final int inHut = InventoryUtils.getItemCountInItemHandler(lead.getItemHandlerCap(), sameAs(kind)) - AssistReservations.heldByOthers(lead, building, kind);
            AssistReservations.reserve(building, lead, kind, Math.min(missing.getValue(), inHut), now);
        }
    }

    /**
     * The builder citizen who leads (his inventory holds the bucket of materials he is working with).
     */
    private static ICitizenData leadCitizen(final AbstractBuildingStructureBuilder lead)
    {
        return lead.getFirstModuleOccurance(WorkerBuildingModule.class).getFirstCitizen();
    }

    private static int leadCarried(final AbstractBuildingStructureBuilder lead, final ItemStack kind)
    {
        final ICitizenData citizen = leadCitizen(lead);
        return citizen == null ? 0 : InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), sameAs(kind));
    }

    /**
     * The materials of the batch, per item.
     */
    private Map<ItemStorage, Integer> batchNeeds()
    {
        final Map<ItemStorage, Integer> needs = new HashMap<>();
        for (final LeaseAllocator.Planned planned : batch)
        {
            for (final ItemStack stack : planned.required())
            {
                needs.merge(new ItemStorage(stack.copyWithCount(1)), stack.getCount(), Integer::sum);
            }
        }
        return needs;
    }

    /**
     * What the batch needs and the helper does not have yet.
     */
    private Map<ItemStorage, Integer> missingForBatch()
    {
        final Map<ItemStorage, Integer> missing = new HashMap<>();
        for (final Map.Entry<ItemStorage, Integer> need : batchNeeds().entrySet())
        {
            final int have = InventoryUtils.getItemCountInItemHandler(worker.getInventoryCitizen(), sameAs(need.getKey().getItemStack()));
            if (have < need.getValue())
            {
                missing.put(need.getKey(), need.getValue() - have);
            }
        }
        return missing;
    }

    private void takeFrom(final com.ldtteam.structurize.api.compat.itemhandler.IItemHandler source, final AbstractBuildingStructureBuilder lead, final Map<ItemStorage, Integer> missing,
      final boolean fromHut)
    {
        if (source == null)
        {
            return;
        }
        for (final Map.Entry<ItemStorage, Integer> need : missing.entrySet())
        {
            final ItemStack kind = need.getKey().getItemStack();
            int wanted = need.getValue();
            if (fromHut)
            {
                // what the ledger holds in the hut for others (requests that were handed items, other helpers) is not for this helper
                wanted = Math.min(wanted, Math.max(0, InventoryUtils.getItemCountInItemHandler(source, sameAs(kind)) - AssistReservations.heldByOthers(lead, building, kind)));
            }
            final int taken = wanted <= 0 ? 0 : ItemMover.move(source, worker.getInventoryCitizen(), sameAs(kind), wanted);
            building.addCarry(lead.getID(), kind, taken);
            if (fromHut)
            {
                AssistReservations.commit(building, lead, kind, taken, world.getGameTime());
            }
        }
    }



    /**
     * Whether the lead carries all of the materials himself (he works at the site, the hut is a long walk away).
     */
    private boolean leadCarriesAll(final AbstractBuildingStructureBuilder lead, final Map<ItemStorage, Integer> missing)
    {
        for (final Map.Entry<ItemStorage, Integer> need : missing.entrySet())
        {
            if (leadCarried(lead, need.getKey().getItemStack()) < need.getValue())
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the hut of the lead holds any of the materials (that are not claimed for others).
     */
    private boolean hutHoldsAnyOf(final AbstractBuildingStructureBuilder lead, final Map<ItemStorage, Integer> missing)
    {
        for (final ItemStorage key : missing.keySet())
        {
            final ItemStack kind = key.getItemStack();
            if (InventoryUtils.getItemCountInItemHandler(lead.getItemHandlerCap(), sameAs(kind)) - AssistReservations.heldByOthers(lead, building, kind) > 0)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the helper to the lead for the hand-over of materials: close enough to hand things up or down (the lead may stand on a tower: the
     * helper waits at the foot of it, he does not climb up to him).
     *
     * @return true once he is there.
     */
    private boolean walkToLeadForHandOver(final net.minecraft.world.entity.LivingEntity lead)
    {
        final BlockPos at = lead.blockPosition();
        final double horizontal = Math.hypot(worker.getX() - lead.getX(), worker.getZ() - lead.getZ());
        if (horizontal <= 4.0D && Math.abs(worker.getY() - lead.getY()) <= 10.0D)
        {
            return true;
        }
        final com.minecolonies.core.colony.workorders.scaffold.ScaffoldLedger ledger = assistOrder == null ? null : assistOrder.getScaffolds();
        BlockPos ground = at;
        // down to the foot of the scaffolding he stands on
        for (int i = 0; i < 40 && ledger != null && (ledger.contains(ground) || ledger.contains(ground.below()) || world.getBlockState(ground.below()).isAir()); i++)
        {
            ground = ground.below();
        }
        return EntityNavigationUtils.walkToPos(worker, ground, 3, false);
    }

    /**
     * Takes the materials for the batch out of the lead's hut, or from the lead himself.
     */
    private IAIState assistGather()
    {
        if (!assistStillValid())
        {
            return leaveAssist();
        }
        checkTrapped();
        final AbstractBuildingStructureBuilder lead = leadOf(assistOrder);
        assistOrder.getCollab().renew(building.getID(), world.getGameTime() + BuilderCollab.leaseTicks());
        if (pruneBatch(assistOrder.getCollab()) && batch.isEmpty())
        {
            return ASSIST_PLAN;
        }
        final boolean giveUp = ++gatherSteps > MAX_GATHER_STEPS;
        if (!giveUp && !gatherFromLead && !missingForBatch().isEmpty() && (!hutHoldsAnyOf(lead, missingForBatch()) || leadCarriesAll(lead, missingForBatch())))
        {
            // nothing of it is in the hut (the lead took it all with him), or the lead has all of it with him at the site: no walk to the hut
            gatherFromLead = true;
        }
        if (!giveUp && !gatherFromLead && !missingForBatch().isEmpty())
        {
            // first from the lead's hut ...
            if (!walkToBuilding(lead))
            {
                return getState();
            }
            takeFrom(lead.getItemHandlerCap(), lead, missingForBatch(), true);
            if (!missingForBatch().isEmpty())
            {
                gatherFromLead = true;
                return getState();
            }
        }
        if (!giveUp && gatherFromLead)
        {
            // ... then from what the lead carries: the builder keeps the bucket of materials he works with in his inventory
            final ICitizenData leadCitizen = leadCitizen(lead);
            if (leadCitizen != null)
            {
                if (leadCitizen.getEntity().isPresent() && !walkToLeadForHandOver(leadCitizen.getEntity().get()))
                {
                    return getState();
                }
                takeFrom(leadCitizen.getInventory(), lead, missingForBatch(), false);
            }
        }
        gatherFromLead = false;
        gatherSteps = 0;
        // what is taken is taken: what was claimed and not taken is free again
        AssistReservations.releaseAll(building, false);

        // positions the stock did not cover go back to the lead
        final Map<ItemStorage, Integer> available = new HashMap<>();
        for (final Iterator<LeaseAllocator.Planned> it = batch.iterator(); it.hasNext(); )
        {
            final LeaseAllocator.Planned planned = it.next();
            final Map<ItemStorage, Integer> wanted = new HashMap<>();
            for (final ItemStack stack : planned.required())
            {
                wanted.merge(new ItemStorage(stack.copyWithCount(1)), stack.getCount(), Integer::sum);
            }
            boolean covered = true;
            for (final Map.Entry<ItemStorage, Integer> entry : wanted.entrySet())
            {
                final int left = available.computeIfAbsent(entry.getKey(), k -> InventoryUtils.getItemCountInItemHandler(worker.getInventoryCitizen(), sameAs(k.getItemStack())));
                if (left < entry.getValue())
                {
                    covered = false;
                    break;
                }
            }
            if (covered)
            {
                wanted.forEach((key, count) -> available.merge(key, -count, Integer::sum));
            }
            else
            {
                assistOrder.getCollab().releaseLease(planned.world());
                it.remove();
            }
        }
        return batch.isEmpty() ? ASSIST_PLAN : ASSIST_WORK;
    }

    /**
     * Places the leased positions.
     */
    private boolean assistBurst  = false;
    private boolean assistPlaced = false;
    private int     assistBurstIndex = 0;

    /**
     * Places the leased positions, as many in one step as the time bank allows (like the lead does with the blocks he reaches from one spot): a helper
     * that waits a delay after every block places a third of what the lead does in the same time.
     */
    private IAIState assistWork()
    {
        final int max = getMaxPlacementsPerStep();
        if (max <= 1 || !usesPhysicalReach())
        {
            return assistWorkOnce();
        }
        if (!earnWorkTime())
        {
            return getState();
        }
        IAIState next;
        int placed = 0;
        assistBurst = true;
        try
        {
            do
            {
                assistBurstIndex = placed;
                assistPlaced = false;
                next = assistWorkOnce();
                if (assistPlaced)
                {
                    placed++;
                }
            }
            while (assistPlaced && next == ASSIST_WORK && hasWorkTime() && placed < max && !batch.isEmpty());
        }
        finally
        {
            assistBurst = false;
        }
        payWorkDebt();
        return next;
    }

    private IAIState assistWorkOnce()
    {
        if (!assistStillValid())
        {
            return leaveAssist();
        }
        if (batch.isEmpty())
        {
            return ASSIST_PLAN;
        }
        final AbstractBuildingStructureBuilder lead = leadOf(assistOrder);
        final WorkOrderCollab collab = assistOrder.getCollab();
        if (collab.getProgressStage() != batchStage)
        {
            return leaveAssist();
        }
        collab.renew(building.getID(), world.getGameTime() + BuilderCollab.leaseTicks());
        if (runScaffoldTick())
        {
            return getState();
        }
        if (!worker.getInventoryCitizen().hasSpace())
        {
            return INVENTORY_FULL;
        }
        if (pruneBatch(collab) && batch.isEmpty())
        {
            return ASSIST_PLAN;
        }

        if (usesPhysicalReach() && batch.size() > 1 && !canReachNow(batch.get(0).world()))
        {
            // what he reaches from where he stands first, like the lead does: a walk for every position in the order of the batch is most of the helper's time
            for (final LeaseAllocator.Planned other : batch)
            {
                if (canReachNow(other.world()) && !assistGaveUp.contains(other.world()))
                {
                    batch.remove(other);
                    batch.add(0, other);
                    break;
                }
            }
        }
        final LeaseAllocator.Planned planned = batch.get(0);
        final WorkOrderCollab.Assistant self = collab.getAssistant(building.getID());
        if (self != null)
        {
            self.active = planned.world();
        }
        final BlockState worldState = world.getBlockState(planned.world());
        if (batchStage == BuildingProgressStage.CLEAR ? (worldState.isAir() || !worldState.getFluidState().isEmpty())
              : worldState == assistHandler.getBluePrint().getBlockState(planned.local()))
        {
            finishPosition(planned, lead, false);
            return getState();
        }

        reachTarget = usesPhysicalReach() ? planned.world() : null;
        if (!walkToConstructionSite(planned.world()))
        {
            if ((usesPhysicalReach() && cannotReachAtAll(planned.world())) || escalateStuck(planned.world()) == WorkStuckTracker.Step.DEFER)
            {
                // the helper cannot get there: the lead gets the position back, and the helper does not take it again
                assistGaveUp.add(planned.world().immutable());
                collab.releaseLease(planned.world());
                batch.remove(0);
                stuckGaveUp();
            }
            return getState();
        }

        final StructurePhasePlacementResult result = LeaseAllocator.doPosition(assistPlacer, world, planned.local(),
          batchStage == BuildingProgressStage.CLEAR ? StructurePlacer.Operation.BLOCK_REMOVAL : StructurePlacer.Operation.BLOCK_PLACEMENT);
        switch (result.getBlockResult().getResult())
        {
            case BREAK_BLOCK:
                assistMine = result.getBlockResult().getWorldPos();
                worker.getCitizenData().setStatusPosition(assistMine);
                return ASSIST_MINE;
            case MISSING_ITEMS:
            case FAIL:
            case LIMIT_REACHED:
                // not possible for this helper right now: the lead gets the position back
                collab.releaseLease(planned.world());
                batch.remove(0);
                return getState();
            default:
                finishPosition(planned, lead, true);
                if (!assistBurst || assistBurstIndex == 0)
                {
                    worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                    worker.queueSound(SoundEvents.BAMBOO_HIT, worker.blockPosition(), 10, 0, 0.5f, 0.1f);
                }
                if (assistBurst)
                {
                    spendWorkTime(placementTicks());
                    assistPlaced = true;
                }
                else
                {
                    setPlacementDelay();
                }
                return getState();
        }
    }

    private IAIState assistMine()
    {
        if (!assistStillValid() || batch.isEmpty() || assistMine == null)
        {
            assistMine = null;
            return assistStillValid() ? ASSIST_WORK : leaveAssist();
        }
        final LeaseAllocator.Planned planned = batch.get(0);
        final BlockState state = world.getBlockState(assistMine);
        if (state.isAir() || !state.getFluidState().isEmpty())
        {
            assistMine = null;
            finishPosition(planned, leadOf(assistOrder), false);
            return ASSIST_WORK;
        }
        assistOrder.getCollab().renew(building.getID(), world.getGameTime() + BuilderCollab.leaseTicks());
        if (runScaffoldTick())
        {
            return getState();
        }
        reachTarget = usesPhysicalReach() ? assistMine : null;
        if (!walkToConstructionSite(assistMine) || workFrom == null)
        {
            if (escalateStuck(assistMine) == WorkStuckTracker.Step.DEFER)
            {
                assistGaveUp.add(planned.world().immutable());
                stuckGaveUp();
                assistOrder.getCollab().releaseLease(planned.world());
                batch.remove(planned);
                assistMine = null;
                return ASSIST_WORK;
            }
            return getState();
        }
        if (!mineBlock(assistMine, workFrom))
        {
            worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            return getState();
        }
        worker.decreaseSaturationForContinuousAction();
        assistMine = null;
        finishPosition(planned, leadOf(assistOrder), true);
        return ASSIST_WORK;
    }

    /**
     * Drops the positions of the batch that the lead took back (he had nothing left to do): they are not leased to this helper any more.
     *
     * @return true if something was dropped.
     */
    private boolean pruneBatch(final WorkOrderCollab collab)
    {
        final BlockPos me = building.getID();
        return batch.removeIf(planned -> !collab.isLeasedTo(planned.world(), me));
    }

    private void finishPosition(final LeaseAllocator.Planned planned, final AbstractBuildingStructureBuilder lead, final boolean counted)
    {
        final WorkOrderCollab collab = assistOrder.getCollab();
        collab.completeLease(planned.world());
        if (counted)
        {
            collab.blockProcessed();
        }
        final Map<ItemStorage, Integer> consumed = assistHandler.takeConsumed();
        if (!consumed.isEmpty())
        {
            building.reduceCarry(consumed);
            if (lead != null)
            {
                for (final Map.Entry<ItemStorage, Integer> entry : consumed.entrySet())
                {
                    lead.reduceNeededResource(entry.getKey().getItemStack(), entry.getValue());
                }
            }
        }
        batch.remove(planned);
        collab.renew(building.getID(), world.getGameTime() + BuilderCollab.leaseTicks());
    }

    /**
     * Gives what is left of the lead's materials back to his hut.
     */
    private IAIState assistReturn()
    {
        dropBatch();
        if (!building.hasCarry())
        {
            assistTries = 0;
            return IDLE;
        }

        final IBuilding leadHut = building.getColony().getServerBuildingManager().getBuilding(building.getCarryLead());
        if (leadHut == null || leadHut.getItemHandlerCap() == null)
        {
            // the lead's hut is gone: what is carried is the builder's own stuff now and the normal dump takes it
            building.clearCarry();
            return IDLE;
        }

        if (assistTries++ < 60 && !walkToBuilding(leadHut))
        {
            return getState();
        }

        final Map<ItemStorage, Integer> left = new HashMap<>();
        for (final Map.Entry<ItemStorage, Integer> entry : building.getCarry().entrySet())
        {
            final ItemStack kind = entry.getKey().getItemStack();
            final int have = InventoryUtils.getItemCountInItemHandler(worker.getInventoryCitizen(), sameAs(kind));
            final int give = Math.min(have, entry.getValue());
            int moved = 0;
            if (give > 0 && assistTries < 60)
            {
                moved = ItemMover.move(worker.getInventoryCitizen(), leadHut.getItemHandlerCap(), sameAs(kind), give);
            }
            else if (give > 0)
            {
                // could not get there: keep it in the own hut and ask for a transfer
                final int dumped = ItemMover.move(worker.getInventoryCitizen(), building.getItemHandlerCap(), sameAs(kind), give);
                if (dumped > 0)
                {
                    leadHut.createRequest(new Delivery(building.getLocation(), leadHut.getLocation(), kind.copyWithCount(dumped), 13), true);
                }
                moved = give;
            }
            if (moved < give)
            {
                left.put(entry.getKey(), give - moved);
            }
        }
        // what is left in the carry is what the lead's hut could not take yet
        for (final ItemStorage key : building.getCarry().keySet())
        {
            building.setCarry(key, left.getOrDefault(key, 0));
        }
        if (!building.hasCarry())
        {
            assistTries = 0;
            return IDLE;
        }
        setDelay(40);
        return getState();
    }
}
