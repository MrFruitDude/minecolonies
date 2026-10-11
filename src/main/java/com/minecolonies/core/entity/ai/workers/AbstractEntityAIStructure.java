package com.minecolonies.core.entity.ai.workers;
import net.minecraft.world.item.component.SwingAnimation;

import com.ldtteam.structurize.blocks.schematic.BlockFluidSubstitution;
import com.ldtteam.structurize.api.util.Tuple;
import com.ldtteam.structurize.placement.BlockPlacementResult;
import com.ldtteam.structurize.placement.StructurePhasePlacementResult;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.ldtteam.structurize.placement.structure.IStructureHandler;
import com.ldtteam.structurize.util.BlockUtils;
import com.ldtteam.structurize.util.BlueprintPositionInfo;
import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.entity.ai.statemachine.AIEventTarget;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIBlockingEventType;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.ai.workers.util.IBuilderUndestroyable;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.items.ModTags;
import com.minecolonies.api.util.*;
import com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder;
import com.minecolonies.core.colony.buildings.modules.BuildingResourcesModule;
import com.minecolonies.core.colony.buildings.utils.BuilderBucket;
import com.minecolonies.core.colony.buildings.utils.BuildingBuilderResource;
import com.minecolonies.core.colony.jobs.AbstractJobStructure;
import com.minecolonies.core.entity.ai.workers.util.BuilderStageRules;
import com.minecolonies.core.entity.ai.workers.util.AbstractWalking;
import com.minecolonies.core.entity.ai.workers.util.StandSpots;
import com.minecolonies.core.entity.ai.workers.util.WorkStuckTracker;
import com.minecolonies.core.entity.ai.workers.util.WorkTiming;
import com.minecolonies.core.entity.ai.workers.util.BuildingProgressStage;
import com.minecolonies.core.entity.ai.workers.util.BuildingStructureHandler;
import com.minecolonies.core.tileentities.TileEntityDecorationController;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import com.ldtteam.structurize.api.util.TriPredicate;
import com.ldtteam.structurize.placement.AbstractBlueprintIterator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.ldtteam.structurize.placement.AbstractBlueprintIterator.NULL_POS;
import static com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState.*;
import static com.minecolonies.api.research.util.ResearchConstants.BLOCK_PLACE_SPEED;
import static com.minecolonies.api.util.constant.CitizenConstants.*;
import static com.minecolonies.api.util.constant.Constants.TICKS_SECOND;
import static com.minecolonies.core.colony.buildings.workerbuildings.BuildingMiner.FILL_BLOCK;
import static com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.ItemCheckResult.*;
import static com.minecolonies.core.entity.ai.workers.util.BuildingProgressStage.*;

/**
 * This base ai class is used by ai's who need to build entire structures. These structures have to be supplied as schematics files.
 * <p>
 * Once an ai starts building a structure, control over it is only given back once that is done.
 * <p>
 * If the ai resets, the structure is gone, so just restart building and no progress will be reset.
 *
 * @param <J> the job type this AI has to do.
 */
public abstract class AbstractEntityAIStructure<J extends AbstractJobStructure<?, J>, B extends AbstractBuildingStructureBuilder> extends AbstractEntityAIInteract<J, B>
{
    /**
     * Building block delay
     */
    protected static final int BUILD_BLOCK_DELAY = 15;

    /**
     * The current structure task to be build.
     */
    protected Tuple<StructurePlacer, BuildingStructureHandler<J, B>> structurePlacer;

    /**
     * If the structure state is currently reached limit rather than block placement.
     */
    protected boolean limitReached = false;

    /**
     * We're waiting for the blueprint to finish loading.
     */
    protected boolean loadingBlueprint = false;

    /**
     * Different item check result possibilities.
     */
    public enum ItemCheckResult
    {
        FAIL,
        SUCCESS,
        RECALC
    }

    /**
     * Predicate defining things we don't want the builders to ever touch.
     */
    protected TriPredicate<BlueprintPositionInfo, BlockPos, IStructureHandler> DONT_TOUCH_PREDICATE = (info, worldPos, handler) ->
    {
        final BlockState worldState = handler.getWorld().getBlockState(worldPos);

        return worldState.getBlock() instanceof IBuilderUndestroyable
                 || worldState.getBlock() == Blocks.BEDROCK
                 || (info.getBlockInfo().getState().getBlock() instanceof AbstractBlockHut && handler.getCenterPos().equals(worldPos)
                       && worldState.getBlock() instanceof AbstractBlockHut);
    };

    /**
     * Combine the immutable structure guard with a stage-specific skip rule.
     */
    protected TriPredicate<BlueprintPositionInfo, BlockPos, IStructureHandler> withDontTouch(
      final TriPredicate<BlueprintPositionInfo, BlockPos, IStructureHandler> predicate)
    {
        return (info, pos, handler) -> DONT_TOUCH_PREDICATE.test(info, pos, handler)
                 || predicate.test(info, pos, handler);
    }

    /**
     * Adds "not a position another builder has leased" to a stage's skip rule.
     */
    private TriPredicate<BlueprintPositionInfo, BlockPos, IStructureHandler> reserved(
      final TriPredicate<BlueprintPositionInfo, BlockPos, IStructureHandler> rule)
    {
        return (info, pos, handler) -> rule.test(info, pos, handler) || isPositionReserved(pos) || isStuckSkipped(pos);
    }

    /**
     * Whether another builder works on this position, so this one leaves it alone.
     *
     * @param worldPos the position.
     * @return true if reserved. Never by default.
     */
    protected boolean isPositionReserved(final BlockPos worldPos)
    {
        return false;
    }

    /**
     * Whether the stage may end now. Builders that help with a stage can hold it up.
     *
     * @return true by default.
     */
    protected boolean canFinishStage()
    {
        return true;
    }

    /**
     * A position was placed or mined by this builder.
     */
    protected void onBlockProcessed()
    {
    }

    /**
     * The builder cannot go on at this position for now (mining it, or waiting for materials).
     *
     * @param worldPos the position.
     */
    protected void onStepBlocked(final BlockPos worldPos)
    {
    }

    /**
     * The builder waits for materials.
     *
     * @param required what the position he cannot place needs.
     */
    protected void onMissingItems(final List<ItemStack> required)
    {
    }

    /**
     * Position where the Builders constructs from.
     */
    protected BlockPos workFrom;

    /**
     * Previous Position where the Builders constructed from.
     */
    protected BlockPos prevBlockPosition;

    /**
     * Block to mine.
     */
    protected BlockPos blockToMine;

    /**
     * Block to go to when building
     */
    protected BlockPos gotoPos = null;

    /**
     * The id in the list of the last picked up item.
     */
    private int pickUpCount = 0;

    /**
     * Creates this ai base class and set's up important things.
     * <p>
     * Always use this constructor!
     *
     * @param job the job class of the ai using this base class.
     */
    protected AbstractEntityAIStructure(@NotNull final J job)
    {
        super(job);
        this.registerTargets(
          /*
           * Check if tasks should be executed.
           */
          new AIEventTarget(AIBlockingEventType.STATE_BLOCKING, this::checkIfCanceled, IDLE, 1),
          /*
           * Select the appropriate State to do next.
           */
          new AITarget(LOAD_STRUCTURE, this::loadRequirements, 5),
          /*
           * Select the appropriate State to do next.
           */
          new AITarget(START_BUILDING, this::startBuilding, 1),
          /*
           * Select the appropriate State to do next.
           */
          new AITarget(MINE_BLOCK, this::doMining, 10),
          /*
           * Check if we have to build something.
           */
          new AITarget(IDLE, START_WORKING, 10),
          /*
           * Start working at the building.
           */
          new AITarget(START_WORKING, this::startWorkingAtOwnBuilding, TICKS_SECOND),
          /*
           * Build the structure and foundation of the building.
           */
          new AITarget(BUILDING_STEP, this::structureStep, STANDARD_DELAY),
          /*
           * Finalize the building and give back control to the ai.
           */
          new AITarget(COMPLETE_BUILD, this::completeBuild, STANDARD_DELAY),
          new AITarget(PICK_UP, this::pickUpMaterial, 5)
        );
    }

    /**
     * Start working at own building. Override for worker specific implementations.
     * @return next state.
     */
    protected IAIState startWorkingAtOwnBuilding()
    {
        if (isThereAStructureToBuild())
        {
            return START_BUILDING;
        }
        return IDLE;
    }

    /**
     * State to pick up material before going back to work.
     *
     * @return the next state to go to.
     */
    public IAIState pickUpMaterial()
    {
        if (structurePlacer == null || !structurePlacer.getB().hasBluePrint())
        {
            return IDLE;
        }

        if (structurePlacer.getB().getStage() == null || structurePlacer.getB().getStage() == BuildingProgressStage.CLEAR)
        {
            pickUpCount = 0;
            return START_WORKING;
        }

        final List<Tuple<Predicate<ItemStack>, Integer>> neededItemsList = new ArrayList<>();

        final BuilderBucket neededRessourcesMap = building.getRequiredResources();
        final BuildingResourcesModule module = building.getFirstModuleOccurance(BuildingResourcesModule.class);
        if (neededRessourcesMap != null)
        {
            for (final Map.Entry<String, Integer> entry : neededRessourcesMap.getResourceMap().entrySet())
            {
                final BuildingBuilderResource res = module.getResourceFromIdentifier(entry.getKey());
                if (res != null)
                {
                    int amount = entry.getValue();
                    neededItemsList.add(new Tuple<>(itemstack -> ItemStackUtils.compareItemStacksIgnoreStackSize(res.getItemStack(), itemstack, true, true), amount));
                }
            }
        }

        if (neededItemsList.size() <= pickUpCount || InventoryUtils.openSlotCount(worker.getInventoryCitizen()) <= MIN_OPEN_SLOTS)
        {
            building.checkOrRequestBucket(building.getRequiredResources(), worker.getCitizenData());
            building.checkOrRequestBucket(building.getNextBucket(), worker.getCitizenData());
            pickUpCount = 0;
            return START_WORKING;
        }

        needsCurrently = neededItemsList.get(pickUpCount);
        pickUpCount++;

        if (InventoryUtils.hasItemInProvider(building.getTileEntity(), needsCurrently.getA()))
        {
            return GATHERING_REQUIRED_MATERIALS;
        }

        return PICK_UP;
    }

    /**
     * Completition logic.
     *
     * @return the final state after completition.
     */
    protected IAIState completeBuild()
    {
        incrementActionsDoneAndDecSaturation();
        executeSpecificCompleteActions();
        worker.getCitizenExperienceHandler().addExperience(XP_EACH_BUILDING);
        resetCurrentStructure();
        return IDLE;
    }

    /**
     * Start building this StructureIterator.
     * <p>
     * Will determine where to start.
     *
     * @return the new State to start in.
     */
    @NotNull
    protected IAIState startBuilding()
    {
        if (structurePlacer == null || !structurePlacer.getB().hasBluePrint())
        {
            return LOAD_STRUCTURE;
        }
        return BUILDING_STEP;
    }

    /**
     * The next state after structure loading.
     *
     * @return the next state.
     */
    public IAIState afterStructureLoading()
    {
        return START_BUILDING;
    }

    /**
     * Walk to the current construction site.
     * <p>
     * Calculates and caches the position where to walk to.
     *
     * @param currentBlock the current block it is working on.
     * @return true while walking to the site.
     */
    public abstract boolean walkToConstructionSite(final BlockPos currentBlock);

    /**
     * Checks for blocks that need to be treated as deco
     */
    protected static boolean isDecoItem(Block block)
    {
        return block.defaultBlockState().is(ModTags.decorationItems) || block instanceof BlockFluidSubstitution;
    }

    /**
     * How many blocks the worker may place in one AI step when his time allows it (see {@link #structureStep()}). One means a delay after
     * every block, as ever.
     *
     * @return the most blocks per step.
     */
    protected int getMaxPlacementsPerStep()
    {
        return 1;
    }

    /**
     * Multiplier of this worker's placing and breaking speed from the settings, 1.0 for none.
     *
     * @return the multiplier.
     */
    protected double getPlacementSpeed()
    {
        return 1.0D;
    }

    /**
     * Time bank of the burst placing, in ticks of work at the worker's pace: it earns the real time that passes (times the pace), every block
     * placed or mined in a burst spends the time the block takes. The worker acts while the bank is positive and waits while it is negative,
     * so the blocks per tick follow the delay of the worker's level exactly, however short the delay is (a delay is no longer rounded up to
     * the next AI step), and a worker that walked for a while does not make up for it with a flood of blocks (the bank is capped).
     */
    private double  workBank     = 0.0D;
    private long    lastBankTick = -1L;
    private boolean burstActive  = false;
    private int     burstIndex   = 0;
    private boolean stepPlaced   = false;

    /**
     * Ticks of work the next block placed costs, at the worker's pace of 1.0.
     */
    protected final double placementTicks()
    {
        final double research = worker.getCitizenColonyHandler().getColonyOrRegister().getResearchManager().getResearchEffects().getEffectStrength(BLOCK_PLACE_SPEED);
        return WorkTiming.placeTicks(getPlaceSpeedLevel(), research, getPlacementSpeed());
    }

    /**
     * The Structure step to execute the actual placement actions etc.
     * <p>
     * A builder places as many blocks as his time allows in one step ({@link #getMaxPlacementsPerStep()}), without walking in between: blocks
     * in reach of the spot he stands on are placed one after the other, and the step ends when the next block needs a walk, a tool, materials or
     * more time than the bank holds.
     *
     * @return the next step to go to.
     */
    protected IAIState structureStep()
    {
        final int maxPlacements = getMaxPlacementsPerStep();
        if (maxPlacements <= 1)
        {
            return structureStepOnce();
        }

        final long now = world.getGameTime();
        final double pace = getWorkPace();
        final double stepTicks = getTickRate() * pace;
        workBank = lastBankTick < 0 ? stepTicks : Math.min(workBank + (now - lastBankTick) * pace, 3.0D * stepTicks);
        lastBankTick = now;
        if (workBank <= 0.0D)
        {
            // still paying for the blocks of the steps before
            return getState();
        }

        IAIState next;
        int placed = 0;
        burstActive = true;
        try
        {
            do
            {
                burstIndex = placed;
                stepPlaced = false;
                next = structureStepOnce();
                if (stepPlaced)
                {
                    placed++;
                }
            }
            while (stepPlaced && next == BUILDING_STEP && workBank > 0.0D && placed < maxPlacements && isThereAStructureToBuild());
        }
        finally
        {
            burstActive = false;
        }

        if (workBank < 0.0D)
        {
            // the debt, less what the wait of the next step earns anyway
            final int wait = (int) Math.ceil(-workBank - stepTicks);
            if (wait > 0)
            {
                setDelay(wait);
            }
        }
        return next;
    }

    /**
     * One block of the structure: walk into reach, place or break it, pay for it.
     *
     * @return the next step to go to.
     */
    private IAIState structureStepOnce()
    {
        if (!isThereAStructureToBuild())
        {
            return IDLE;
        }
        if (structurePlacer.getB().getStage() == null)
        {
            resetCurrentStructure();
            return IDLE;
        }

        if (!worker.getInventoryCitizen().hasSpace())
        {
            return INVENTORY_FULL;
        }

        checkForExtraBuildingActions();

        // some things to do first! then we go to the actual phase!

        //Fill workFrom with the position from where the builder should build.
        //also ensure we are at that position.
        final BlockPos progress = getProgressPos() == null ? NULL_POS : getProgressPos().getA();
        final BlockPos worldPos = getPosToWorkAt();

        if (getProgressPos() != null)
        {
            structurePlacer.getB().setStage(getProgressPos().getB());
        }

        final boolean mustWalk = (worldPos != null || blockToMine != null) && !limitReached
                                   && (blockToMine == null ? !walkToConstructionSite(worldPos) : !walkToConstructionSite(blockToMine));
        if (mustWalk)
        {
            // the worker cannot get to the block: wait for the walk, and climb the ladder out when it takes too long
            final BlockPos target = blockToMine != null ? blockToMine : worldPos;
            if (escalateStuck(target) == WorkStuckTracker.Step.DEFER)
            {
                deferBlock(blockToMine != null ? blockToMine : peekNextBlock());
            }
            return getState();
        }
        stuck.free();

        limitReached = false;

        final StructurePhasePlacementResult result;
        final StructurePlacer placer = structurePlacer.getA();
        final BuildingProgressStage currentStage = structurePlacer.getB().getStage();
        switch (currentStage)
        {
            case BUILD_SOLID:
            case WEAK_SOLID:
            case CLEAR_NON_SOLIDS:
            case DECORATE:
                // structure, not solid, clear air, decoration
                result = placer.executeStructureStep(world, null, progress, StructurePlacer.Operation.BLOCK_PLACEMENT, stageIterator(currentStage, placer), false);
                break;
            case CLEAR_WATER:
                //water
                result = placer.clearWaterStep(world, progress);
                break;
            case SPAWN:
                if (placer.getHandler().getBluePrint().getEntities().length == 0)
                {
                    result = new StructurePhasePlacementResult(BlockPos.ZERO, new BlockPlacementResult(BlockPos.ZERO, BlockPlacementResult.Result.FINISHED));
                }
                else
                {
                    // entities
                    result = placer.executeStructureStep(world, null, progress, StructurePlacer.Operation.SPAWN_ENTITY,
                      () -> placer.getIterator().increment((info, pos, handler) -> info.getEntities().length == 0 || DONT_TOUCH_PREDICATE.test(info, pos, handler)), true);
                }
                break;
            case REMOVE_WATER:
                //water
                placer.getIterator().setRemoving();
                result = placer.executeStructureStep(world, null, progress, StructurePlacer.Operation.WATER_REMOVAL,
                  () -> placer.getIterator().decrement((info, pos, handler) -> info.getBlockInfo().getState().getFluidState().isEmpty()), false);
                break;
            case REMOVE:
                placer.getIterator().setRemoving();
                result = placer.executeStructureStep(world, null, progress, StructurePlacer.Operation.BLOCK_REMOVAL, stageIterator(currentStage, placer), true);
                break;
            case CLEAR:
            default:
                result = placer.executeStructureStep(world, null, progress, StructurePlacer.Operation.BLOCK_REMOVAL, stageIterator(CLEAR, placer), false);
                break;
        }

        if (result.getBlockResult().getResult() == BlockPlacementResult.Result.FAIL)
        {
            Log.getLogger().error("Failed placement at: " + result.getBlockResult().getWorldPos().toShortString());
        }

        final boolean firstIteration = building.getProgress() == null;
        if (result.getBlockResult().getResult() == BlockPlacementResult.Result.FINISHED)
        {
            if (!canFinishStage())
            {
                // builders helping with this stage have positions left; wait for them (or for their lease to run out)
                setDelay(TICKS_SECOND);
                return getState();
            }

            if (retryDeferredBlocks(currentStage))
            {
                // blocks the worker gave up on come again once, now that the rest of the stage is done
                return getState();
            }

            building.nextStage();
            if (!goToNextStage(result))
            {
                building.setProgressPos(null, null);
                worker.getCitizenData().setStatusPosition(null);
                return COMPLETE_BUILD;
            }
            this.storeProgressPos(NULL_POS, structurePlacer.getB().getStage());
            if (currentStage == CLEAR)
            {
                building.checkOrRequestBucket(building.getRequiredResources(), worker.getCitizenData());
            }
        }
        else if (result.getBlockResult().getResult() == BlockPlacementResult.Result.LIMIT_REACHED)
        {
            this.limitReached = true;
            this.storeProgressPos(result.getIteratorPos(), structurePlacer.getB().getStage());
        }
        else
        {
            if (structurePlacer.getB().getStage() != CLEAR_WATER && !result.getIteratorPos().equals(NULL_POS))
            {
                gotoPos = result.getIteratorPos();
            }
            else
            {
                gotoPos = null;
            }

            this.storeProgressPos(result.getIteratorPos(), structurePlacer.getB().getStage());
        }

        if (firstIteration)
        {
            building.checkOrRequestBucket(building.getRequiredResources(), worker.getCitizenData());
        }

        if (result.getBlockResult().getResult() == BlockPlacementResult.Result.SUCCESS)
        {
            stuck.progress();
            onBlockProcessed();
        }

        if (result.getBlockResult().getResult() == BlockPlacementResult.Result.MISSING_ITEMS)
        {
            onStepBlocked(result.getBlockResult().getWorldPos());
            onMissingItems(result.getBlockResult().getRequiredItems());
            if (hasListOfResInInvOrRequest(this, result.getBlockResult().getRequiredItems(), result.getBlockResult().getRequiredItems().size() > 1) == RECALC)
            {
                building.getWorkOrder().setRequested(false);
                return LOAD_STRUCTURE;
            }
            return NEEDS_ITEM;
        }

        if (!burstActive || burstIndex == 0)
        {
            worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            worker.queueSound(SoundEvents.BAMBOO_HIT, worker.blockPosition(), 10, 0, 0.5f, 0.1f);
        }

        if (result.getBlockResult().getResult() == BlockPlacementResult.Result.BREAK_BLOCK)
        {
            blockToMine = result.getBlockResult().getWorldPos();
            onStepBlocked(blockToMine);
            worker.getCitizenData().setStatusPosition(blockToMine);
            if (burstActive && tryMineInline())
            {
                // a cheap block, mined and paid from the time bank: on with the next one in the same step
                stepPlaced = true;
                return getState();
            }
            return MINE_BLOCK;
        }
        else
        {
            blockToMine = null;
        }

        if (burstActive)
        {
            // clearing a block that is gone already costs nothing but the look at it: its work was the mining, paid when it was mined
            final boolean clearing = currentStage == CLEAR || currentStage == REMOVE;
            workBank -= clearing ? WorkTiming.MIN_PLACE_TICKS : placementTicks();
            stepPlaced = result.getBlockResult().getResult() == BlockPlacementResult.Result.SUCCESS;
        }
        else
        {
            setPlacementDelay();
        }

        return getState();
    }

    /**
     * The iteration of a stage: which blocks it visits next, skipping what has nothing to do for the stage and what is left alone for now.
     *
     * @param stage  the stage.
     * @param placer the placer.
     * @return the function that moves the iterator to the next block, or null for stages that do not iterate over blocks this way.
     */
    @Nullable
    private Supplier<AbstractBlueprintIterator.Result> stageIterator(final BuildingProgressStage stage, final StructurePlacer placer)
    {
        switch (stage)
        {
            case BUILD_SOLID:
                return () -> placer.getIterator().increment(reserved(this::skipBuilding));
            case WEAK_SOLID:
                return () -> placer.getIterator()
                               .increment(reserved((info, pos, handler) -> !BlockUtils.isWeakSolidBlock(info.getBlockInfo().getState()) || DONT_TOUCH_PREDICATE.test(info, pos, handler)));
            case CLEAR_NON_SOLIDS:
                return () -> placer.getIterator().decrement(reserved((info, pos, handler) ->
                                                                       !(info.getBlockInfo().getState().getBlock() instanceof AirBlock)
                                                                         || (handler.getWorld().isEmptyBlock(pos))
                                                                         || DONT_TOUCH_PREDICATE.test(info, pos, handler)));
            case DECORATE:
                return () -> placer.getIterator().increment(reserved(this::skipDecorate));
            case REMOVE:
                return () -> placer.getIterator().decrement(reserved(this::skipRemoval));
            case CLEAR:
                return () -> placer.getIterator().decrement(reserved(this::skipClearing));
            default:
                return null;
        }
    }

    /**
     * Waits as long as placing one block takes this builder.
     */
    protected final void setPlacementDelay()
    {
        setDelay(Math.max(1, (int) placementTicks()));
    }

    // ------------------------------------------------------------------ never stuck

    /**
     * The ladder out of a block the worker cannot get on with, see {@link WorkStuckTracker}.
     */
    private final WorkStuckTracker stuck = new WorkStuckTracker();
    private long stuckLastTick = -1L;
    private Vec3 stuckLastPos  = null;

    /**
     * Blocks (world positions) the worker gave up on for now and the stage skips; the blocks that come again once at the end of the stage;
     * the blocks he finally left out in this stage; and the retry rounds done in this stage.
     */
    private final Set<BlockPos> deferredBlocks  = new HashSet<>();
    private final Set<BlockPos> retryBlocks     = new HashSet<>();
    private final Set<BlockPos> abandonedBlocks = new HashSet<>();
    private int                 retryRounds     = 0;

    /**
     * Ticks the worker waited for a tool for the block he wants to mine.
     */
    private int  toolWaitTicks = 0;
    private long toolWaitTick  = -1L;

    /**
     * Until this game time blocks that need a tool the worker does not have are deferred at once and the open tool request does not hold the AI
     * in NEEDS_ITEM: the worker does the work he can do first (the request stays open and the tool is used when it arrives).
     */
    private long toolDeferUntil = -1L;

    /**
     * How long that lasts after a tool request has held the worker for {@link #TOOL_TIMEOUT_TICKS}.
     */
    protected static final int TOOL_DEFER_WINDOW_TICKS = 6000;

    /**
     * Whether everything the worker waits for is a tool (and nothing waits to be picked up).
     */
    private boolean onlyToolRequestsOpen()
    {
        final ICitizenData citizen = worker.getCitizenData();
        boolean any = false;
        for (final IRequest<?> request : building.getOpenRequests(citizen.getId()))
        {
            if (citizen.isRequestAsync(request.getId()))
            {
                continue;
            }
            if (!(request.getRequest() instanceof com.minecolonies.api.colony.requestsystem.requestable.Tool))
            {
                return false;
            }
            any = true;
        }
        return any && !building.hasCitizenCompletedRequestsToPickup(citizen);
    }

    @Override
    protected boolean checkIfNeedsItem()
    {
        if (toolDeferUntil > world.getGameTime() && blockToMine != null && onlyToolRequestsOpen())
        {
            return false;
        }
        return super.checkIfNeedsItem();
    }

    @NotNull
    @Override
    protected IAIState waitForRequests()
    {
        if (structurePlacer != null && blockToMine != null && !retryBlocks.contains(blockToMine) && onlyToolRequestsOpen())
        {
            // held by a tool request while a block waits for it: after a while the worker does the rest first
            final long now = world.getGameTime();
            toolWaitTicks += toolWaitTick < 0 ? 0 : (int) Math.min(now - toolWaitTick, 200L);
            toolWaitTick = now;
            if (toolWaitTicks >= TOOL_TIMEOUT_TICKS)
            {
                toolWaitTicks = 0;
                toolWaitTick = -1L;
                toolDeferUntil = now + TOOL_DEFER_WINDOW_TICKS;
                deferBlock(blockToMine);
                return BUILDING_STEP;
            }
        }
        else
        {
            toolWaitTick = -1L;
        }
        return super.waitForRequests();
    }

    /**
     * After this many ticks without a tool the block is deferred (the request for the tool stays open).
     */
    protected static final int TOOL_TIMEOUT_TICKS = 1200;

    /**
     * Blocks of a stage that are left alone for now (deferred) or for good (abandoned).
     *
     * @param worldPos the block.
     * @return true if the stage skips it.
     */
    protected final boolean isStuckSkipped(final BlockPos worldPos)
    {
        return (!deferredBlocks.isEmpty() && deferredBlocks.contains(worldPos)) || (!abandonedBlocks.isEmpty() && abandonedBlocks.contains(worldPos));
    }

    /**
     * How many blocks the worker gave up on in this structure so far (for tests and the UI).
     */
    public final int getDeferredBlockCount()
    {
        return deferredBlocks.size();
    }

    public final int getAbandonedBlockCount()
    {
        return abandonedBlocks.size();
    }

    /**
     * Throws away where the worker wanted to stand and the path to it, so the next step asks for a new spot.
     */
    protected void resetWalkTarget()
    {
        workFrom = null;
        worker.getNavigation().stop();
    }

    /**
     * Hook for the scaffolding of the 3D reach work: build a column of scaffolding next to the target so the worker can stand next to it.
     * The unstuck ladder calls it between "a spot near the target" and "teleport".
     *
     * @param target the block the worker cannot get to.
     * @return true if the worker has a new way to the target now (a tower is being built), false if nothing was done (the default).
     */
    protected boolean tryScaffold(final BlockPos target)
    {
        return false;
    }

    /**
     * The worker cannot work on the target block (he cannot get to a spot to work from). Counts the time he stands still and climbs one step
     * of the ladder when it is due: new path, a safe spot near the target, scaffold (a hook), teleport to the edge of the work area, defer.
     * DEFER is left to the caller, who knows which block it is.
     *
     * @param target the block he works on.
     * @return the step that was taken now, or NONE.
     */
    protected final WorkStuckTracker.Step escalateStuck(final BlockPos target)
    {
        if (AbstractWalking.isRelocating(worker))
        {
            // waiting out an abstract walk is not being stuck
            stuck.free();
            stuckLastTick = -1L;
            return WorkStuckTracker.Step.NONE;
        }

        final long now = world.getGameTime();
        final int dt = stuckLastTick < 0 ? 0 : (int) Math.min(now - stuckLastTick, 200L);
        stuckLastTick = now;
        final Vec3 position = worker.position();
        final double moved = stuckLastPos == null ? 0.0D : position.distanceTo(stuckLastPos);
        stuckLastPos = position;

        WorkStuckTracker.Step step = stuck.blocked(dt, moved);
        if (step == WorkStuckTracker.Step.NONE && stuck.stationaryTicks() >= WorkStuckTracker.REPATH_AT + 20 && fellBelowWorkArea())
        {
            // fallen into a hole below the site and no way up: no need to try the gentler steps first
            step = stuck.escalateTo(WorkStuckTracker.Step.TELEPORT);
        }

        switch (step)
        {
            case REPATH:
                resetWalkTarget();
                break;
            case SAFE_SPOT:
                final BlockPos spot = findSafeSpotNear(target);
                if (spot != null)
                {
                    resetWalkTarget();
                    workFrom = spot;
                }
                else
                {
                    resetWalkTarget();
                }
                break;
            case SCAFFOLD:
                tryScaffold(target);
                break;
            case TELEPORT:
                teleportOut(target);
                break;
            default:
                break;
        }
        if (step != WorkStuckTracker.Step.NONE)
        {
            Log.getLogger().debug("{} is stuck at {} (standing still {} ticks): {}", worker.getName().getString(), target, stuck.stationaryTicks(), step);
        }
        return step;
    }

    /**
     * Gives up on a block for now: the stage skips it, and it comes again once at the end of the stage. Blocks that were retried and fail again
     * are left out of the stage for good, and the worker says so.
     *
     * @param worldPos the block, can be null.
     */
    protected final void deferBlock(@Nullable final BlockPos worldPos)
    {
        if (worldPos != null)
        {
            final BlockPos pos = worldPos.immutable();
            if (retryBlocks.remove(pos))
            {
                abandonedBlocks.add(pos);
                onBlockAbandoned(pos);
            }
            else
            {
                deferredBlocks.add(pos);
            }
            if (pos.equals(blockToMine))
            {
                blockToMine = null;
            }
        }
        workFrom = null;
        gotoPos = null;
        resetWalkTarget();
        stuck.progress();
        toolWaitTicks = 0;
    }

    /**
     * A block was left out of the stage for good because the worker could not get to it twice.
     *
     * @param worldPos the block.
     */
    protected void onBlockAbandoned(final BlockPos worldPos)
    {
        Log.getLogger().warn("{} could not reach {} and leaves it out", worker.getName().getString(), worldPos);
        worker.getCitizenData().triggerInteraction(
          new com.minecolonies.core.colony.interactionhandling.SimpleNotificationInteraction(Component.translatableEscape("com.minecolonies.coremod.gui.chat.builder.cantreach"),
            com.minecolonies.api.colony.interactionhandling.ChatPriority.IMPORTANT));
    }

    /**
     * At the end of a stage: the blocks that were deferred come once more.
     *
     * @param stage the stage that has ended.
     * @return true if the stage starts again for them (the caller does not go to the next stage).
     */
    private boolean retryDeferredBlocks(final BuildingProgressStage stage)
    {
        if (!deferredBlocks.isEmpty() && retryRounds < 1)
        {
            retryRounds++;
            retryBlocks.addAll(deferredBlocks);
            deferredBlocks.clear();
            workFrom = null;
            gotoPos = null;
            blockToMine = null;
            this.storeProgressPos(NULL_POS, stage);
            return true;
        }
        // the stage is over: what is left of the bookkeeping goes with it
        retryRounds = 0;
        retryBlocks.clear();
        abandonedBlocks.clear();
        return false;
    }

    /**
     * Forgets what the worker left out: a new structure starts.
     */
    protected final void resetStuckState()
    {
        deferredBlocks.clear();
        retryBlocks.clear();
        abandonedBlocks.clear();
        retryRounds = 0;
        toolWaitTicks = 0;
        toolWaitTick = -1L;
        toolDeferUntil = -1L;
        stuck.progress();
        stuckLastTick = -1L;
        stuckLastPos = null;
        workBank = 0.0D;
        lastBankTick = -1L;
    }

    /**
     * The block the stage would visit next, without moving on (for deferring a block the worker is walking to).
     *
     * @return its world position, or null if there is none or the stage does not iterate.
     */
    @Nullable
    private BlockPos peekNextBlock()
    {
        if (structurePlacer == null || structurePlacer.getB().getStage() == null)
        {
            return null;
        }
        final StructurePlacer placer = structurePlacer.getA();
        final Supplier<AbstractBlueprintIterator.Result> iterate = stageIterator(structurePlacer.getB().getStage(), placer);
        final Tuple<BlockPos, BuildingProgressStage> progress = getProgressPos();
        if (iterate == null || progress == null)
        {
            return null;
        }
        final BlockPos before = placer.getIterator().getProgressPos();
        try
        {
            placer.getIterator().setProgressPos(progress.getA());
            if (iterate.get() == AbstractBlueprintIterator.Result.NEW_BLOCK)
            {
                return structurePlacer.getB().getProgressPosInWorld(placer.getIterator().getProgressPos());
            }
            return null;
        }
        finally
        {
            placer.getIterator().setProgressPos(before);
        }
    }

    /**
     * The corners of the work area: the blueprint's box in the world, or null without a structure.
     */
    @Nullable
    private BlockPos[] workArea()
    {
        if (structurePlacer == null || !structurePlacer.getB().hasBluePrint())
        {
            return null;
        }
        final BuildingStructureHandler<J, B> handler = structurePlacer.getB();
        final BlockPos a = handler.getProgressPosInWorld(BlockPos.ZERO);
        final BlockPos b = handler.getProgressPosInWorld(new BlockPos(handler.getBluePrint().getSizeX() - 1, handler.getBluePrint().getSizeY() - 1, handler.getBluePrint().getSizeZ() - 1));
        return new BlockPos[] {new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
          new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()))};
    }

    /**
     * Whether the worker stands well below the work area (fallen into a hole).
     */
    private boolean fellBelowWorkArea()
    {
        final BlockPos[] area = workArea();
        return area != null && worker.getY() < area[0].getY() - 2;
    }

    /**
     * A spot near the target to stand on, not in the column of the target and not in a hole below the work area.
     */
    @Nullable
    private BlockPos findSafeSpotNear(final BlockPos target)
    {
        final BlockPos[] area = workArea();
        final int floor = area == null ? target.getY() - 3 : area[0].getY() - 1;
        return StandSpots.nearest(world, target, 5, 6, 2,
          p -> (p.getX() != target.getX() || p.getZ() != target.getZ() || p.getY() < target.getY()) && p.getY() >= floor);
    }

    /**
     * Puts the worker on the nearest spot to the target that lies at the edge of the work area (outside its footprint, at the level of its
     * base), or at the entrance of his hut if there is none, and starts his walk from there.
     */
    private void teleportOut(final BlockPos target)
    {
        final BlockPos[] area = workArea();
        BlockPos spot = null;
        if (area != null)
        {
            final int base = area[0].getY();
            spot = StandSpots.nearest(world, target, 16, 12, 12,
              p -> (p.getX() < area[0].getX() || p.getX() > area[1].getX() || p.getZ() < area[0].getZ() || p.getZ() > area[1].getZ())
                     && p.getY() >= base - 1 && p.getY() <= base + 3);
        }
        if (spot == null)
        {
            spot = StandSpots.nearest(world, building.getPosition(), 8, 4, 4, null);
        }
        if (spot == null)
        {
            return;
        }
        puffAt(worker.position());
        worker.getNavigation().stop();
        worker.snapTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D);
        puffAt(worker.position());
        resetWalkTarget();
        workFrom = spot;
    }

    private void puffAt(final Vec3 position)
    {
        if (world instanceof ServerLevel serverLevel)
        {
            serverLevel.sendParticles(ParticleTypes.POOF, position.x, position.y + 0.5D, position.z, 12, 0.3D, 0.4D, 0.3D, 0.02D);
        }
    }

    /**
     * Mines the block to mine right here if it takes no more than a few AI steps, paying from the time bank instead of waiting.
     *
     * @return true if the block is gone.
     */
    private boolean tryMineInline()
    {
        if (blockToMine == null || workFrom == null)
        {
            return false;
        }
        final BlockState state = world.getBlockState(blockToMine);
        if (state.getBlock() instanceof AirBlock || state.getBlock() == Blocks.WATER)
        {
            blockToMine = null;
            return true;
        }
        if (!holdEfficientTool(state, blockToMine))
        {
            return false;
        }
        final int time = getBlockMiningTime(state, blockToMine);
        if (time > 3 * getTickRate() * getWorkPace())
        {
            // slow work keeps its animated wait in the mining state
            return false;
        }
        final boolean mined;
        miningTimePaidByBank = true;
        try
        {
            mined = mineBlock(blockToMine, workFrom);
        }
        finally
        {
            miningTimePaidByBank = false;
        }
        if (!mined)
        {
            return false;
        }
        workBank -= time;
        worker.decreaseSaturationForContinuousAction();
        stuck.progress();
        onBlockProcessed();
        blockToMine = null;
        return true;
    }

    /**
     * Get the position the citizen needs to walk to in order to build
     *
     * @return the position to go to
     */
    protected BlockPos getPosToWorkAt()
    {
        if (gotoPos != null)
        {
            return structurePlacer.getB().getProgressPosInWorld(gotoPos);
        }
        else
        {
            return building.getWorkOrder().getLocation();
        }
    }

    /**
     * This method is called when a stage is finished, and it is needed to go to the next stage
     *
     * @return whether it succeeded, i.e. there was a next stage to go to.
     */
    protected boolean goToNextStage(StructurePhasePlacementResult result)
    {
        return structurePlacer.getB().nextStage();
    }

    /**
     * Checks which blocks are skipped on decorate
     *
     * @param info
     * @param pos
     * @param handler
     * @return
     */
    private boolean skipDecorate(final BlueprintPositionInfo info, final BlockPos pos, final IStructureHandler handler)
    {
        return BuilderStageRules.skipDecorate(info, pos, handler);
    }

    /**
     * Checks wich blocks are skipped on building
     *
     * @param info
     * @param pos
     * @param handler
     * @return
     */
    protected boolean skipBuilding(final BlueprintPositionInfo info, final BlockPos pos, final IStructureHandler handler)
    {
        return BuilderStageRules.skipBuilding(info, pos, handler);
    }

    /**
     * Checks which blocks are skipped on clearing
     *
     * @param info
     * @param pos
     * @param handler
     * @return
     */
    protected boolean skipClearing(final BlueprintPositionInfo info, final BlockPos pos, final IStructureHandler handler)
    {
        return BuilderStageRules.skipClearing(info, pos, handler);
    }

    /**
     * Checks which blocks are skipped on removal on building upgrade
     *
     * @param info
     * @param pos
     * @param handler
     * @return
     */
    private boolean skipRemoval(final BlueprintPositionInfo info, final BlockPos pos, final IStructureHandler handler)
    {
        final BlockState infoBlockState = info.getBlockInfo().getState();
        final Block infoBlock = infoBlockState.getBlock();
        if (infoBlockState.isAir()
              || infoBlock == com.ldtteam.structurize.blocks.ModBlocks.blockSolidSubstitution.get()
              || infoBlock == com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get()
              || infoBlock == com.ldtteam.structurize.blocks.ModBlocks.blockFluidSubstitution.get())
        {
            return true;
        }

        final BlockState state = handler.getWorld().getBlockState(pos);
        return state.isAir() || state.getBlock() instanceof IBuilderUndestroyable || state.getBlock() == Blocks.WATER;
    }

    /**
     * Get the level that affects the place speed.
     *
     * @return the level.
     */
    public abstract int getPlaceSpeedLevel();

    @Override
    public IAIState getStateAfterPickUp()
    {
        return PICK_UP;
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

    /**
     * Separate step for mining.
     *
     * @return the next state to go to.
     */
    public IAIState doMining()
    {
        if (blockToMine == null)
        {
            return BUILDING_STEP;
        }

        if (structurePlacer == null)
        {
            return IDLE;
        }

        final BlockState worldState = world.getBlockState(blockToMine);
        if (worldState.getBlock() instanceof AirBlock || worldState.getBlock() == Blocks.WATER)
        {
            return BUILDING_STEP;
        }

        if (!walkToConstructionSite(blockToMine))
        {
            if (escalateStuck(blockToMine) == WorkStuckTracker.Step.DEFER)
            {
                deferBlock(blockToMine);
                return BUILDING_STEP;
            }
            return getState();
        }

        if (workFrom == null)
        {
            // the walk says "go ahead" but no spot to mine from was found (nothing to stand on within reach): that is being stuck, too
            if (escalateStuck(blockToMine) == WorkStuckTracker.Step.DEFER)
            {
                deferBlock(blockToMine);
                return BUILDING_STEP;
            }
            return getState();
        }

        if (!mineBlock(blockToMine, workFrom))
        {
            worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            switch (miningWait)
            {
                case TOOL:
                    // no tool for this block (it is requested): do the rest first, the block comes again at the end of the stage
                    final long now = world.getGameTime();
                    if (now < toolDeferUntil && !retryBlocks.contains(blockToMine))
                    {
                        deferBlock(blockToMine);
                        return BUILDING_STEP;
                    }
                    toolWaitTicks += toolWaitTick < 0 ? 0 : (int) Math.min(now - toolWaitTick, 200L);
                    toolWaitTick = now;
                    if (toolWaitTicks >= TOOL_TIMEOUT_TICKS)
                    {
                        toolWaitTicks = 0;
                        toolWaitTick = -1L;
                        if (!retryBlocks.contains(blockToMine))
                        {
                            toolDeferUntil = now + TOOL_DEFER_WINDOW_TICKS;
                            deferBlock(blockToMine);
                            return BUILDING_STEP;
                        }
                    }
                    break;
                case WALK:
                    if (escalateStuck(blockToMine) == WorkStuckTracker.Step.DEFER)
                    {
                        deferBlock(blockToMine);
                        return BUILDING_STEP;
                    }
                    break;
                default:
                    // the wait for the time the block takes
                    stuck.free();
                    toolWaitTick = -1L;
                    break;
            }
            return getState();
        }
        toolWaitTicks = 0;
        toolWaitTick = -1L;
        toolDeferUntil = -1L;
        worker.decreaseSaturationForContinuousAction();
        stuck.progress();
        onBlockProcessed();
        return BUILDING_STEP;
    }

    /**
     * Loads the structure given the name, rotation and position.
     *
     * @param workOrder   the work order.
     * @param position    the position to set it.
     * @param removal     if removal step.
     */
    public void loadStructure(@NotNull final IBuilderWorkOrder workOrder, final BlockPos position, final boolean removal)
    {
        resetStuckState();
        this.loadingBlueprint = true;
        workOrder.loadBlueprint(world, (blueprint -> {
            if (blueprint == null)
            {
                handleSpecificCancelActions();
                Log.getLogger()
                  .warn("Couldn't find structure with name: " + workOrder.getStructurePath() + " in: " + workOrder.getStructurePack() + ". Aborting loading procedure");
                this.loadingBlueprint = false;
                return;
            }

            final BuildingStructureHandler<J, B> structure;
            IBuilding colonyBuilding = worker.getCitizenColonyHandler().getColonyOrRegister().getServerBuildingManager().getBuilding(position);
            final BlockEntity entity = world.getBlockEntity(position);

            if (removal)
            {
                structure = new BuildingStructureHandler<>(world,
                    workOrder,
                    this, new BuildingProgressStage[] {REMOVE_WATER, REMOVE});
                building.setTotalStages(2);
            }
            else if ((colonyBuilding != null && (colonyBuilding.getBuildingLevel() > 0 || colonyBuilding.hasParent())) ||
                       (entity instanceof TileEntityDecorationController && Utils.getBlueprintLevel(((TileEntityDecorationController) entity).getBlueprintPath()) != -1))
            {
                structure = new BuildingStructureHandler<>(world,
                    workOrder,
                    this, new BuildingProgressStage[] {BUILD_SOLID, WEAK_SOLID, CLEAR_WATER, CLEAR_NON_SOLIDS, DECORATE, SPAWN});
                building.setTotalStages(5);
            }
            else
            {
                structure = new BuildingStructureHandler<>(world,
                    workOrder,
                    this, new BuildingProgressStage[] {CLEAR, BUILD_SOLID, WEAK_SOLID, CLEAR_WATER, CLEAR_NON_SOLIDS, DECORATE, SPAWN});
                building.setTotalStages(6);
            }

            setStructurePlacer(structure);

            if (getProgressPos() != null)
            {
                structure.setStage(getProgressPos().getB());
            }
            this.loadingBlueprint = false;
        }));
    }

    /**
     * Set the structure placer.
     *
     * @param structure the placer.
     */
    public void setStructurePlacer(final BuildingStructureHandler<J, B> structure)
    {
        structurePlacer = new Tuple<>(new StructurePlacer(structure), structure);
    }

    /**
     * Check the placers inventory for the items in the itemList and remove it of the list if found.
     *
     * @param placer   the placer.
     * @param itemList the list to check.
     * @param force    if force insertion.
     * @return true if need to request.
     */
    public static <J extends AbstractJobStructure<?, J>, B extends AbstractBuildingStructureBuilder> ItemCheckResult hasListOfResInInvOrRequest(
      @NotNull final AbstractEntityAIStructure<J, B> placer,
      final List<ItemStack> itemList,
      final boolean force)
    {
        final Map<ItemStorage, Integer> requestedMap = new HashMap<>();
        for (final ItemStack stack : itemList)
        {
            if (stack.getItem() instanceof BlockItem && isBlockFree(((BlockItem) stack.getItem()).getBlock().defaultBlockState()))
            {
                continue;
            }

            ItemStorage tempStorage = new ItemStorage(stack.copy());
            if (requestedMap.containsKey(tempStorage))
            {
                final int oldSize = requestedMap.get(tempStorage);
                tempStorage.setAmount(tempStorage.getAmount() + oldSize);
            }
            requestedMap.put(tempStorage, tempStorage.getAmount());
        }

        for (final ItemStorage stack : requestedMap.keySet())
        {
            if (!InventoryUtils.hasItemInItemHandler(placer.getInventory(), stack1 -> ItemStackUtils.compareItemStacksIgnoreStackSize(stack.getItemStack(), stack1))
                  && !placer.building.hasResourceInBucket(stack.getItemStack()))
            {
                return RECALC;
            }
        }
        // TODO: Why predicate based search when we got our ItemStorages we look for already?
        final List<ItemStack> foundStacks = InventoryUtils.filterItemHandler(placer.getWorker().getInventoryCitizen(),
          itemStack -> requestedMap.keySet().stream().anyMatch(storage -> ItemStackUtils.compareItemStacksIgnoreStackSize(storage.getItemStack(), itemStack)));

        final Map<ItemStorage, Integer> localMap = new HashMap<>();
        for (final ItemStack stack : foundStacks)
        {
            ItemStorage tempStorage = new ItemStorage(stack.copy());
            if (localMap.containsKey(tempStorage))
            {
                final int oldSize = localMap.get(tempStorage);
                tempStorage.setAmount(tempStorage.getAmount() + oldSize);
            }
            localMap.put(tempStorage, tempStorage.getAmount());
        }

        if (force)
        {
            for (final Map.Entry<ItemStorage, Integer> local : localMap.entrySet())
            {
                int req = requestedMap.getOrDefault(local.getKey(), 0);
                if (req != 0)
                {
                    if (local.getValue() >= req)
                    {
                        requestedMap.remove(local.getKey());
                    }
                    else
                    {
                        requestedMap.put(local.getKey(), req - local.getValue());
                    }
                }
            }
        }
        else
        {
            requestedMap.entrySet()
              .removeIf(entry -> ItemStackUtils.isEmpty(entry.getKey().getItemStack()) || foundStacks.stream()
                                                                                            .anyMatch(target -> ItemStackUtils.compareItemStacksIgnoreStackSize(target,
                                                                                              entry.getKey().getItemStack())));
        }

        final ICitizenData citizenData = placer.getWorker().getCitizenData();
        final int citizenId = citizenData.getId();
        for (final Map.Entry<ItemStorage, Integer> placedStack : requestedMap.entrySet())
        {
            final ItemStack stack = placedStack.getKey().getItemStack();
            if (ItemStackUtils.isEmpty(stack))
            {
                return FAIL;
            }

            final List<IRequest<?>> requests = placer.building.getOpenRequestsOfCitizenOrBuilding(citizenId, e -> e.getRequest() instanceof IDeliverable deliverable && deliverable.matches(stack));
            final List<IRequest<?>> completedRequests = placer.building.getCompletedRequestsOfCitizenOrBuilding(citizenData, e -> e.getRequest() instanceof IDeliverable deliverable && deliverable.matches(stack));

            if (requests.isEmpty() && completedRequests.isEmpty())
            {
                final Stack stackRequest = new Stack(stack, placer.getTotalAmount(stack).getCount(), 1);
                placer.getWorker().getCitizenData().createRequest(stackRequest);
                placer.registerBlockAsNeeded(stack);
                return FAIL;
            }
            else
            {
                for (final IRequest<?> request : requests)
                {
                    placer.building.moveToSyncCitizen(citizenData, request);
                }

                for (final IRequest<?> request : completedRequests)
                {
                    placer.building.moveToSyncCitizen(citizenData, request);
                }
            }
            return FAIL;
        }
        return SUCCESS;
    }

    /**
     * Load all requirements of the structure.
     *
     * @return the next state to go to.
     */
    public IAIState loadRequirements()
    {
        return START_WORKING;
    }

    /**
     * Iterates through all the required resources and stores them in the building.
     *
     * @return true if finished.
     */
    public boolean requestMaterials()
    {
        /*
         *  Override if needed.
         */
        return true;
    }

    /**
     * Register the block as needed at the building if possible.
     *
     * @param stack the stack.
     */
    public void registerBlockAsNeeded(final ItemStack stack)
    {
        /*
         * Override in child if possible.
         */
    }

    /**
     * Store the progressPos in the building if possible for the worker.
     *
     * @param blockPos the progressResult.
     * @param stage    the current stage.
     */
    public void storeProgressPos(final BlockPos blockPos, final BuildingProgressStage stage)
    {
        /*
         * Override if needed.
         */
    }

    /**
     * Calculates the working position.
     * <p>
     * Takes a min distance from width and length.
     * <p>
     * Then finds the floor level at that distance and then check if it does contain two air levels.
     *
     * @param targetPosition the position to work at.
     * @return BlockPos position to work from.
     */
    @Override
    public BlockPos getWorkingPosition(final BlockPos targetPosition)
    {
        //get length or width either is larger.
        final int length = structurePlacer.getB().getBluePrint().getSizeX();
        final int width = structurePlacer.getB().getBluePrint().getSizeZ();
        final int distance = Math.max(width, length) + MIN_ADDITIONAL_RANGE_TO_BUILD;

        return getWorkingPosition(distance, targetPosition, 0);
    }

    /**
     * Defines blocks that can be built for free.
     *
     * @param block The block to check if it is free.
     * @return true or false.
     */
    public static boolean isBlockFree(@Nullable final BlockState block)
    {
        return block == null
                 || BlockUtils.isWater(block)
                 || block.is(BlockTags.LEAVES)
                 || block.getBlock() == ModBlocks.blockDecorationPlaceholder;
    }

    /**
     * Let childs overwrite this if necessary.
     *
     * @return true if so.
     */
    protected boolean isAlreadyCleared()
    {
        return false;
    }

    /**
     * Get the current block being placed/removed. May be null if nothing is loaded yet.
     *
     * @return the current building position, or null.
     */
    @Nullable
    public BlockPos getCurrentBuildingPosition()
    {
        if (structurePlacer == null || structurePlacer.getA() == null || structurePlacer.getB() == null)
        {
            return null;
        }

        final BlockPos progressPos = structurePlacer.getA().getIterator().getProgressPos();
        if (progressPos == null || progressPos.equals(NULL_POS))
        {
            return null;
        }

        return structurePlacer.getB().getProgressPosInWorld(progressPos);
    }

    /**
     * Get the current working position for the worker. If workFrom is null calculate a new one.
     *
     * @return the current working position.
     */
    protected BlockPos getCurrentWorkingPosition()
    {
        return workFrom == null ? getWorkingPosition(getCurrentBuildingPosition()) : workFrom;
    }

    /**
     * Check if there is a StructureIterator to be build.
     *
     * @return true if we should start building.
     */
    protected boolean isThereAStructureToBuild()
    {
        if (structurePlacer == null || !structurePlacer.getB().hasBluePrint() || building.getWorkOrder() == null)
        {
            return false;
        }
        return true;
    }

    /**
     * Reduces the needed resources by 1.
     *
     * @param stack the stack which has been used now.
     */
    public void reduceNeededResources(final ItemStack stack)
    {
        /*
         * Nothing to be done here. Workers overwrite this if necessary.
         */
    }

    /**
     * Check for extra building options to do with each block.
     */
    public void checkForExtraBuildingActions()
    {
        /*
         * Override by worker if necessary.
         */
    }

    /**
     * Specific actions to handle a cancellation of a structure.
     */
    public void handleSpecificCancelActions()
    {
        /*
         * Child classes have to override this.
         */
    }

    /**
     * Check how much of a certain stuck is actually required.
     *
     * @param stack the stack to check.
     * @return the new stack with the correct amount.
     */
    @Nullable
    public ItemStack getTotalAmount(@Nullable final ItemStack stack)
    {
        return stack;
    }

    /**
     * Set the currentStructure to null.
     */
    public void resetCurrentStructure()
    {
        resetStuckState();
        workFrom = null;
        structurePlacer = null;
        building.setProgressPos(null, null);
        worker.getCitizenData().setStatusPosition(null);
    }

    /**
     * Get the worker of the AI.
     *
     * @return the EntityCitizen object.
     */
    public AbstractEntityCitizen getWorker()
    {
        return this.worker;
    }

    /**
     * Get the current structure progress,
     *
     * @return the progress with the current stage.
     */
    public abstract Tuple<BlockPos, BuildingProgressStage> getProgressPos();

    /**
     * Check if a solid substitution block should be overwritten in a specific case.
     *
     * @param worldBlock    the worldblock.
     * @param worldMetadata the world metadata.
     * @return true if should be overwritten.
     */
    public abstract boolean shallReplaceSolidSubstitutionBlock(final Block worldBlock, final BlockState worldMetadata);

    /**
     * Searches a handy block to substitute a non-solid space which should be guaranteed solid.
     *
     * @param ignored the location the block should be at.
     * @param virtualBlocks blueprint blocks, fnc may return null if virtual block is not available (then use level instead for getting surrounding block states), pos argument is using world coords
     * @return the Block.
     */
    public BlockState getSolidSubstitution(final BlockPos ignored, final Function<BlockPos, @Nullable BlockState> virtualBlocks)
    {
        return building.getSetting(FILL_BLOCK).getValue().getBlock().defaultBlockState();
    }

    /**
     * Execute specific actions on loading a structure.
     */
    protected abstract void executeSpecificCompleteActions();

    /**
     * Check if the structure tusk has been canceled.
     *
     * @return true if reset to idle.
     */
    protected abstract boolean checkIfCanceled();
}
