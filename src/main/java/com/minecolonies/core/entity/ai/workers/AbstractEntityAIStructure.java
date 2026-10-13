package com.minecolonies.core.entity.ai.workers;
import net.minecraft.world.item.component.SwingAnimation;

import com.ldtteam.structurize.blocks.schematic.BlockFluidSubstitution;
import com.ldtteam.structurize.api.util.Tuple;
import com.ldtteam.structurize.placement.BlockPlacementResult;
import com.ldtteam.structurize.placement.StructurePhasePlacementResult;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.ldtteam.structurize.placement.handlers.placement.IPlacementHandler;
import com.ldtteam.structurize.util.BlockInfo;
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
import com.minecolonies.core.entity.ai.workers.util.ReachSpots;
import com.minecolonies.core.entity.ai.workers.util.StandSpots;
import com.minecolonies.core.entity.ai.workers.util.WorkerSettings;
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
        return (info, pos, handler) -> rule.test(info, pos, handler) || isPositionReserved(pos) || isStuckSkipped(pos) || isScaffoldHeld(pos) || (!servedOutOfOrder.isEmpty() && servedOutOfOrder.contains(pos));
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
     * @return true if the missing items were found at once (the worker goes on without waiting for them).
     */
    protected boolean onMissingItems(final List<ItemStack> required)
    {
        return false;
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
     * The time bank for work that is done outside of {@link #structureStep()} (the blocks a helper places from his lease): earns the time that passed.
     *
     * @return false while the bank is still negative (the blocks of the steps before are paid for).
     */
    protected final boolean earnWorkTime()
    {
        final long now = world.getGameTime();
        final double pace = getWorkPace();
        final double stepTicks = getTickRate() * pace;
        workBank = lastBankTick < 0 ? stepTicks : Math.min(workBank + (now - lastBankTick) * pace, 3.0D * stepTicks);
        lastBankTick = now;
        return workBank > 0.0D;
    }

    /**
     * Whether the bank holds time for another block.
     */
    protected final boolean hasWorkTime()
    {
        return workBank > 0.0D;
    }

    /**
     * Pays for a block placed in a burst out of the bank.
     */
    protected final void spendWorkTime(final double ticks)
    {
        workBank -= ticks;
    }

    /**
     * Ends a burst: waits for the debt of the blocks placed, less what the wait of the next step earns anyway.
     */
    protected final void payWorkDebt()
    {
        if (workBank < 0.0D)
        {
            final int wait = (int) Math.ceil(-workBank - getTickRate() * getWorkPace());
            if (wait > 0)
            {
                setDelay(wait);
            }
        }
    }

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

        if (runScaffoldTick())
        {
            // putting a tower up or taking it down
            return getState();
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
        BlockPos worldPos = getPosToWorkAt();
        boolean reachGate = false;
        if (usesPhysicalReach() && blockToMine == null && !limitReached)
        {
            // the worker works on what he reaches: the next block that has something to do is the one he has to be able to reach
            final List<BlockPos> next = peekWork(1);
            if (!next.isEmpty())
            {
                worldPos = next.get(0);
                reachGate = true;
            }
        }

        if (getProgressPos() != null)
        {
            structurePlacer.getB().setStage(getProgressPos().getB());
        }

        // what the worker reaches from where he stands is worked on before he moves, whatever its place in the order of the stage
        final BlockPos nearby = reachGate && !canReachNow(worldPos) ? pollNearby(structurePlacer.getB().getStage()) : null;
        final boolean outOfOrder = nearby != null;

        reachTarget = usesPhysicalReach() ? (blockToMine != null ? blockToMine : (reachGate ? worldPos : null)) : null;
        final boolean mustWalk = !outOfOrder && (worldPos != null || blockToMine != null) && !limitReached
                                   && (blockToMine == null ? !walkToConstructionSite(worldPos) : !walkToConstructionSite(blockToMine));
        if (mustWalk)
        {
            // the worker cannot get to the block: wait for the walk, and climb the ladder out when it takes too long
            final BlockPos target = blockToMine != null ? blockToMine : worldPos;
            if (reachTarget != null && cannotReachAtAll(target))
            {
                if (retryBlocks.contains(target) && reachTeleported.add(target.immutable()) && teleportToReach(target))
                {
                    // the second round (the first one gave the block up for later): no tower came, and the block is out of reach from everywhere he can walk to - but
                    // there is a spot that reaches it (the inside of a box that he built the walls of from outside, with no door), and the unstuck ladder puts him
                    // there, as it does for any block that he cannot get to
                    Log.getLogger().debug("{} is put on a spot that reaches {}", worker.getName().getString(), target);
                    return getState();
                }
                Log.getLogger().debug("{} gives up {}: no place to reach it from", worker.getName().getString(), target);
                deferBlock(target, false);
                escalateStuck(target);
                return getState();
            }
            if (escalateStuck(target) == WorkStuckTracker.Step.DEFER)
            {
                deferBlock(blockToMine != null ? blockToMine : (reachGate ? worldPos : peekNextBlock()));
            }
            return getState();
        }
        stuck.free();

        limitReached = false;

        final StructurePhasePlacementResult result;
        final StructurePlacer placer = structurePlacer.getA();
        final BuildingProgressStage currentStage = structurePlacer.getB().getStage();
        if (outOfOrder)
        {
            result = placeNearby(placer, nearby);
        }
        else
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
        if (outOfOrder)
        {
            // the cursor of the stage stays where it is
            if (result.getBlockResult().getResult() == BlockPlacementResult.Result.SUCCESS)
            {
                servedOutOfOrder.add(nearby.immutable());
            }
        }
        else if (result.getBlockResult().getResult() == BlockPlacementResult.Result.FINISHED)
        {
            if (!canFinishStage())
            {
                // builders helping with this stage have positions left; wait for them (or for their lease to run out). Their scaffolding stays up
                // meanwhile: it is theirs to work from, and a tower that comes down under a helper is put up again by him.
                setDelay(TICKS_SECOND);
                return getState();
            }
            if (scaffoldBeforeStageEnd())
            {
                // the scaffolding comes down before the stage ends
                return getState();
            }
            if (consumeScaffoldRescan())
            {
                // positions that stood under scaffolding come again
                this.storeProgressPos(NULL_POS, currentStage);
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
            if (onMissingItems(result.getBlockResult().getRequiredItems()))
            {
                return getState();
            }
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
        if (toolDeferUntil > world.getGameTime() && onlyToolRequestsOpen())
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
     * A helper gave up on a position: the unstuck ladder starts again for the next one (the worker walks from scratch).
     */
    protected final void stuckGaveUp()
    {
        stuck.progress();
        resetWalkTarget();
    }

    /**
     * The stuck tracker, for the logs of the tests.
     */
    protected final String debugStuck()
    {
        return stuck.stationaryTicks() + "/" + stuck.overallTicks() + "/" + stuck.lastStep() + " deferred=" + deferredBlocks.size() + " abandoned=" + abandonedBlocks.size();
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
     * Builds a tower of scaffolding that gets the worker out of a hole he cannot walk out of.
     *
     * @return true if a tower is on its way.
     */
    protected boolean tryEscapeTower()
    {
        return false;
    }

    /**
     * Whether a perch high above the base of the structure has a way down: a ladder or scaffolding next to it, or ground within a drop of three
     * blocks. A builder who stands on top of a wall that grows under him must not be left with a fall of ten blocks.
     *
     * @param feet the spot.
     * @return true if he could get off it again.
     */
    protected final boolean hasWayDown(final BlockPos feet)
    {
        final BlockPos[] area = workArea();
        if (area == null || feet.getY() <= area[0].getY() + 3)
        {
            return true;
        }
        for (final net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL)
        {
            final BlockPos near = feet.relative(dir);
            if (world.getBlockState(near).is(net.minecraft.tags.BlockTags.CLIMBABLE) || world.getBlockState(near.below()).is(net.minecraft.tags.BlockTags.CLIMBABLE))
            {
                return true;
            }
            for (int drop = 0; drop <= 3 && StandSpots.isFree(world, near.below(Math.max(0, drop - 1))); drop++)
            {
                if (StandSpots.canStandAt(world, near.below(drop)))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether the worker is cut off from his hut by a drop he cannot climb (a pit), as far as a search of the standable spots around him tells.
     */
    protected final boolean isStrandedInHole()
    {
        final BlockPos home = StandSpots.nearest(world, building.getPosition(), 8, 4, 4, null);
        if (home == null || Math.abs(home.getX() - worker.getBlockX()) >= WALKABLE_RADIUS - 2 || Math.abs(home.getZ() - worker.getBlockZ()) >= WALKABLE_RADIUS - 2)
        {
            return false;
        }
        // standing well below the spot by his hut, with no way up
        return home.getY() - worker.getBlockY() >= 3 && !canWalkTo(home);
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
        if (step == WorkStuckTracker.Step.NONE && stuck.stationaryTicks() >= WorkStuckTracker.REPATH_AT + 20 && (fellBelowWorkArea() || isStrandedInHole()))
        {
            // fallen into a hole and no way up: a tower of scaffolding up and out if there is one, else no need to try the gentler steps first
            if (tryEscapeTower())
            {
                stuck.free();
                return WorkStuckTracker.Step.NONE;
            }
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
        deferBlock(worldPos, true);
    }

    /**
     * @param resetLadder whether the unstuck ladder starts again (a worker who gives up block after block because he is cut off keeps climbing it,
     *                    so the teleport still comes).
     */
    protected final void deferBlock(@Nullable final BlockPos worldPos, final boolean resetLadder)
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
        if (resetLadder)
        {
            stuck.progress();
        }
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
            onRetryRound();
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
        reachTeleported.clear();
        servedOutOfOrder.clear();
        nearbyQueue.clear();
        nearbyStage = null;
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

    // ------------------------------------------------------------------ hooks of the scaffolding work (builders)

    private java.util.Set<BlockPos> walkable = null;
    private BlockPos walkableFrom = null;
    private long walkableAt = -100L;
    private static final int WALKABLE_RADIUS = 28;

    /**
     * The world changed around the worker (scaffolding went up or came down): the search has to be done again.
     */
    protected final void forgetWalkable()
    {
        walkable = null;
    }

    /**
     * Whether the worker can walk (or climb) from where he is to a position, as far as a search of the standable spots around him can tell.
     * Positions further away than the search goes count as reachable.
     *
     * @param feet the position.
     * @return false if the position is cut off: inside a closed wall, on top of one, behind a drop.
     */
    protected final boolean canWalkTo(final BlockPos feet)
    {
        final long now = world.getGameTime();
        final BlockPos here = worker.blockPosition();
        if (walkable == null || !here.equals(walkableFrom) && now - walkableAt > 5 || now - walkableAt > 40)
        {
            walkable = StandSpots.reachableFrom(world, here, WALKABLE_RADIUS, 30000);
            walkableFrom = here;
            walkableAt = now;
        }
        return walkable.contains(feet) || Math.abs(feet.getX() - walkableFrom.getX()) >= WALKABLE_RADIUS || Math.abs(feet.getZ() - walkableFrom.getZ()) >= WALKABLE_RADIUS
                 || walkable.size() >= 30000;
    }

    /**
     * The work order the worker's scaffolding belongs to.
     *
     * @return the order, null if there is none.
     */
    @Nullable
    protected IBuilderWorkOrder scaffoldOrder()
    {
        return null;
    }

    /**
     * The structure the worker builds, for the places where scaffolding would be in the way of the building.
     */
    @Nullable
    protected IStructureHandler scaffoldHandler()
    {
        return structurePlacer == null ? null : structurePlacer.getB();
    }

    /**
     * Whether the stage that runs now or later removes the block at the position (the clear stage mines what stands in the volume of the
     * structure): ground like that is no base for a tower.
     */
    protected final boolean willBeRemoved(final BlockPos pos)
    {
        final IStructureHandler handler = scaffoldHandler();
        if (handler == null || structurePlacer == null)
        {
            return false;
        }
        final BuildingProgressStage stage = structurePlacer.getB().getStage();
        if (stage != CLEAR && stage != CLEAR_NON_SOLIDS && stage != REMOVE)
        {
            return false;
        }
        final BlockPos local = handler.getStructurePosFromWorld(pos);
        final var blueprint = handler.getBluePrint();
        if (blueprint == null || local.getX() < 0 || local.getY() < 0 || local.getZ() < 0
              || local.getX() >= blueprint.getSizeX() || local.getY() >= blueprint.getSizeY() || local.getZ() >= blueprint.getSizeZ())
        {
            return false;
        }
        final BlockState world = this.world.getBlockState(pos);
        if (world.isAir() || world.getBlock() instanceof IBuilderUndestroyable || world.getBlock() == Blocks.BEDROCK)
        {
            return false;
        }
        final BlockState wanted = blueprint.getBlockState(local);
        if (stage == REMOVE)
        {
            return wanted != null && !wanted.isAir();
        }
        if (stage == CLEAR_NON_SOLIDS)
        {
            return wanted != null && wanted.isAir();
        }
        // the clear stage mines everything in the volume that is not what the blueprint wants there (with fast clear)
        return wanted == null || wanted != world;
    }

    /**
     * Whether the block at the position is one of the structure that stands in the world already, and more than a floor above its base (the top of a
     * wall: no place that a path leads to; the floor of the structure is where the builders work).
     *
     * @param pos the world position.
     * @return true if the world has there what the blueprint wants, it is no air, and it is at least two layers above the base of the structure.
     */
    protected final boolean isBuiltStructureBlock(final BlockPos pos)
    {
        final IStructureHandler handler = scaffoldHandler();
        final var blueprint = handler == null ? null : handler.getBluePrint();
        if (blueprint == null)
        {
            return false;
        }
        final BlockPos local = handler.getStructurePosFromWorld(pos);
        if (local.getX() < 0 || local.getY() < 2 || local.getZ() < 0 || local.getX() >= blueprint.getSizeX() || local.getY() >= blueprint.getSizeY() || local.getZ() >= blueprint.getSizeZ())
        {
            return false;
        }
        final BlockState wanted = blueprint.getBlockState(local);
        return wanted != null && !wanted.isAir() && wanted == world.getBlockState(pos);
    }

    /**
     * What it costs to put scaffolding into a cell: the blocks of the structure go there later, so the scaffolding would have to come down
     * first (high), the cells inside the volume of the structure are a little in the way (low).
     *
     * @param pos the cell.
     * @return the cost.
     */
    protected int scaffoldPenalty(final BlockPos pos)
    {
        final IStructureHandler handler = scaffoldHandler();
        if (handler == null)
        {
            return 0;
        }
        final BlockPos local = handler.getStructurePosFromWorld(pos);
        final var blueprint = handler.getBluePrint();
        if (blueprint == null || local.getX() < 0 || local.getY() < 0 || local.getZ() < 0
              || local.getX() >= blueprint.getSizeX() || local.getY() >= blueprint.getSizeY() || local.getZ() >= blueprint.getSizeZ())
        {
            return 0;
        }
        final BlockState state = blueprint.getBlockState(local);
        // (a structure void is a cell the blueprint leaves alone: nothing goes there)
        return state == null || state.isAir() || state.is(Blocks.STRUCTURE_VOID) ? 2 : 40;
    }

    /**
     * Gets the worker into a place where he reaches the block, for the scaffolding work.
     *
     * @param target     the block.
     * @param ahead      the blocks that count when the spot is picked (the first is the target).
     * @param inBlocks   whether the spot may be in the cell itself or on the scaffolding of the cells.
     * @param accept     extra condition for the spot, or null.
     * @return true once he reaches the block.
     */
    protected boolean walkIntoReachFor(final BlockPos target, final Supplier<List<BlockPos>> ahead, final boolean inBlocks, @Nullable final Predicate<BlockPos> accept)
    {
        return true;
    }

    protected void resetScaffoldWalk()
    {
    }

    protected void resetReachWalk()
    {
    }

    /**
     * A tower is up: the worker works from its top.
     *
     * @param stand the spot on top of it.
     */
    protected void workFromScaffold(final BlockPos stand)
    {
        workFrom = stand;
    }

    /**
     * The blocks of the next work, for the decision whether scaffolding is still of use.
     */
    protected List<BlockPos> peekWorkAhead(final int n)
    {
        return peekWork(n);
    }

    /**
     * The blocks the lead of the structure works on next (for a helper who decides whether a tower is still of use: the lead's blocks count, his own
     * leased ones are done with the towers he made).
     */
    protected List<BlockPos> leadWorkAhead(final int n)
    {
        return peekWorkAhead(n);
    }

    /**
     * The scaffolding work of this AI step (puts a tower up or takes one down).
     *
     * @return true if the step went into it.
     */
    protected boolean scaffoldTick()
    {
        return false;
    }

    private long trapCheckAt = 0L;
    private int  trapStrikes  = 0;

    /**
     * Counts of the times a worker was found shut in and put out (for the tests).
     */
    public static volatile int trappedRescues = 0;

    /**
     * A worker who works with the physical reach may find the walls of the structure risen around him (he stood inside to reach the blocks of the
     * wall on the other side): no path leads out. He walks in circles at best (the unstuck ladder only counts a worker who stands still), so he is
     * put out at the entrance of his hut once it is clear that he is shut in.
     */
    protected final void checkTrapped()
    {
        if (!usesPhysicalReach())
        {
            return;
        }
        final long now = world.getGameTime();
        if (now < trapCheckAt)
        {
            return;
        }
        trapCheckAt = now + 40L;
        final BlockPos home = StandSpots.nearest(world, building.getPosition(), 8, 4, 4, null);
        // (only on the level of his hut: on a wall or in a pit he is not "shut in", that is for the unstuck ladder)
        if (home == null || Math.abs(worker.getBlockY() - home.getY()) > 1 || Math.abs(home.getX() - worker.getBlockX()) >= WALKABLE_RADIUS - 2
              || Math.abs(home.getZ() - worker.getBlockZ()) >= WALKABLE_RADIUS - 2 || canWalkTo(home))
        {
            trapStrikes = 0;
            return;
        }
        if (++trapStrikes < 3)
        {
            return;
        }
        trapStrikes = 0;
        Log.getLogger().debug("{} is shut in at {}: put out at {}", worker.getName().getString(), worker.blockPosition(), home);
        if (this instanceof com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder && com.minecolonies.core.entity.ai.workers.util.ReachAudit.isEnabled())
        {
            Log.getLogger().info("SCAFFOLD {} is shut in at {}: put out at {}", worker.getName().getString(), worker.blockPosition(), home);
        }
        trappedRescues++;
        puffAt(worker.position());
        worker.getNavigation().stop();
        worker.snapTo(home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D);
        puffAt(worker.position());
        resetWalkTarget();
        forgetWalkable();
    }

    protected final boolean runScaffoldTick()
    {
        checkTrapped();
        if (scaffoldTick())
        {
            stuck.free();
            stuckLastTick = -1L;
            return true;
        }
        return false;
    }

    /**
     * Whether a position holds scaffolding that the worker put up: the stage leaves it alone until the scaffolding is down.
     */
    protected boolean isScaffoldHeld(final BlockPos worldPos)
    {
        return false;
    }

    /**
     * Before a stage ends: scaffolding that is still up comes down first.
     *
     * @return true while there is scaffolding left (the stage waits).
     */
    protected boolean scaffoldBeforeStageEnd()
    {
        return false;
    }

    /**
     * @return true once if positions of the structure were held back by scaffolding that came down: the stage has to look for them again.
     */
    protected boolean consumeScaffoldRescan()
    {
        return false;
    }

    /**
     * The blocks that were given up on come again: whatever stopped the worker from trying (no scaffolding items) may be over.
     */
    protected void onRetryRound()
    {
    }

    /**
     * The structure is reset (done, cancelled, released).
     */
    protected void onStructureReset()
    {
    }

    /**
     * Whether this worker places and breaks only what he reaches (3D, from the eye). Builders do, with the setting on.
     *
     * @return false by default.
     */
    protected boolean usesPhysicalReach()
    {
        return false;
    }

    /**
     * The block the worker has to reach for the walk he is asked for now (the walk to any other position is the usual one), set by the code
     * that asks for the walk.
     */
    protected BlockPos reachTarget = null;

    /**
     * Whether the worker reaches a block from where he stands. Always true for workers without the physical reach.
     *
     * @param pos the block.
     * @return true if he can place or break it right now.
     */
    protected boolean canReachNow(final BlockPos pos)
    {
        return true;
    }

    /**
     * How many of the next blocks are looked at when a worker picks the spot to work from.
     */
    protected static final int REACH_LOOKAHEAD = 48;

    /**
     * The blocks the worker works on next and where something has to be done (not those that are what the blueprint wants already), in the
     * order he works on them, without moving on.
     *
     * @param n how many at most.
     * @return their world positions, empty if the stage does not iterate over blocks (water, entities) or nothing is left.
     */
    protected final List<BlockPos> peekWork(final int n)
    {
        if (structurePlacer == null || structurePlacer.getB().getStage() == null)
        {
            return List.of();
        }
        final StructurePlacer placer = structurePlacer.getA();
        final BuildingStructureHandler<J, B> handler = structurePlacer.getB();
        final BuildingProgressStage stage = handler.getStage();
        final Supplier<AbstractBlueprintIterator.Result> iterate = stageIterator(stage, placer);
        final Tuple<BlockPos, BuildingProgressStage> progress = getProgressPos();
        if (iterate == null || progress == null)
        {
            return List.of();
        }
        final List<BlockPos> work = new ArrayList<>();
        final BlockPos before = placer.getIterator().getProgressPos();
        try
        {
            placer.getIterator().setProgressPos(progress.getA());
            int guard = 0;
            while (work.size() < n && guard++ < 8 * n + 256 && iterate.get() == AbstractBlueprintIterator.Result.NEW_BLOCK)
            {
                final BlockPos local = placer.getIterator().getProgressPos();
                final BlockPos worldPos = handler.getProgressPosInWorld(local);
                if (hasWorkAt(stage, handler, local, worldPos))
                {
                    work.add(worldPos);
                }
            }
        }
        finally
        {
            placer.getIterator().setProgressPos(before);
        }
        return work;
    }

    private boolean hasWorkAt(final BuildingProgressStage stage, final BuildingStructureHandler<J, B> handler, final BlockPos local, final BlockPos worldPos)
    {
        if (stage == CLEAR || stage == REMOVE)
        {
            return !world.getBlockState(worldPos).isAir();
        }
        final BlockState wanted = handler.getBluePrint().getBlockState(local);
        if (wanted == null)
        {
            return false;
        }
        return !IPlacementHandler.doesWorldStateMatchBlueprintState(new BlockInfo(local, wanted, handler.getBluePrint().getTileEntityData(worldPos, local)), worldPos, handler);
    }

    /**
     * The worker has found no place to reach the block from for a while, and building a tower is no way out (no scaffolding, no room): there is
     * no use in waiting for the unstuck ladder to run its course.
     *
     * @param target the block.
     * @return true if the block should be given up for now.
     */
    protected boolean cannotReachAtAll(final BlockPos target)
    {
        return false;
    }

    /**
     * Whether scaffolding is no way out for the worker for now (switched off, no items, no room for a tower).
     */
    protected boolean scaffoldUnavailable()
    {
        return true;
    }

    /**
     * The blocks that count when the worker picks the spot to work from: the target first, then what he works on after it.
     *
     * @param target the block he has to reach.
     * @return the blocks, the target is the first.
     */
    protected List<BlockPos> workAhead(final BlockPos target)
    {
        // (the stages that take what is in reach in any order look further ahead than those that go block by block)
        return workAhead(target, servesAnyOrder() ? REACH_LOOKAHEAD : 16);
    }

    /**
     * @param n how many of the next blocks at most.
     * @see #workAhead(BlockPos)
     */
    protected List<BlockPos> workAhead(final BlockPos target, final int n)
    {
        final List<BlockPos> ahead = peekWork(n);
        if (ahead.isEmpty() || !ahead.get(0).equals(target))
        {
            final List<BlockPos> list = new ArrayList<>(ahead.size() + 1);
            list.add(target);
            for (final BlockPos pos : ahead)
            {
                if (!pos.equals(target))
                {
                    list.add(pos);
                }
            }
            return list;
        }
        return ahead;
    }

    // ------------------------------------------------------------------ work out of order

    /**
     * Blocks the worker placed out of the order of the stage (they were in reach, the next block of the order was not). The stage skips them
     * when its cursor comes to them.
     */
    private final Set<BlockPos> servedOutOfOrder = new HashSet<>();
    private final java.util.ArrayDeque<BlockPos> nearbyQueue = new java.util.ArrayDeque<>();
    private BlockPos nearbyCursor = null;
    private BuildingProgressStage nearbyStage = null;
    private long nearbyBuiltAt = -100L;

    /**
     * How many of the next blocks of a stage are looked at for work in reach.
     */
    protected static final int NEARBY_WINDOW = 96;

    /**
     * The stages in which the worker takes what he reaches before he moves on: the ones that place blocks (the order inside the stage does not
     * matter for the result, the order of the stages does).
     */
    private static boolean servesNearby(@Nullable final BuildingProgressStage stage)
    {
        return stage == BUILD_SOLID || stage == WEAK_SOLID || stage == DECORATE;
    }

    /**
     * Whether the worker takes what he reaches in any order in the current stage (the spot to work from is picked by how many blocks it
     * reaches, not by how many in a row).
     */
    protected boolean servesAnyOrder()
    {
        return usesPhysicalReach() && structurePlacer != null && servesNearby(structurePlacer.getB().getStage());
    }

    /**
     * The next block (of those that come next in the stage) that the worker reaches from where he stands.
     *
     * @return the block, or null if there is none.
     */
    @Nullable
    private BlockPos pollNearby(@Nullable final BuildingProgressStage stage)
    {
        if (!usesPhysicalReach() || !servesNearby(stage) || structurePlacer == null)
        {
            return null;
        }
        final Tuple<BlockPos, BuildingProgressStage> progress = getProgressPos();
        final BlockPos cursor = progress == null ? NULL_POS : progress.getA();
        final long now = world.getGameTime();
        if (stage != nearbyStage || !cursor.equals(nearbyCursor) || (nearbyQueue.size() < NEARBY_WINDOW / 3 && now - nearbyBuiltAt > 20))
        {
            if (stage != nearbyStage)
            {
                servedOutOfOrder.clear();
            }
            nearbyStage = stage;
            nearbyCursor = cursor.immutable();
            nearbyBuiltAt = now;
            nearbyQueue.clear();
            nearbyQueue.addAll(peekWork(NEARBY_WINDOW));
        }
        BlockPos found = null;
        final BuildingStructureHandler<J, B> handler = structurePlacer.getB();
        for (final java.util.Iterator<BlockPos> it = nearbyQueue.iterator(); it.hasNext(); )
        {
            final BlockPos pos = it.next();
            if (servedOutOfOrder.contains(pos))
            {
                it.remove();
                continue;
            }
            if (canReachNow(pos))
            {
                it.remove();
                if (hasWorkAt(stage, handler, handler.getStructurePosFromWorld(pos), pos))
                {
                    found = pos;
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Places (or starts to break) one block out of the order of the stage.
     */
    private StructurePhasePlacementResult placeNearby(final StructurePlacer placer, final BlockPos worldPos)
    {
        final BlockPos local = structurePlacer.getB().getStructurePosFromWorld(worldPos);
        final int[] calls = {0};
        return placer.executeStructureStep(world, null, local, StructurePlacer.Operation.BLOCK_PLACEMENT, () ->
        {
            if (calls[0]++ == 0)
            {
                placer.getIterator().setProgressPos(local);
                return AbstractBlueprintIterator.Result.NEW_BLOCK;
            }
            return AbstractBlueprintIterator.Result.AT_END;
        }, false);
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
        if (usesPhysicalReach() && target.equals(reachTarget))
        {
            // the spot from which the block is in reach (and the blocks after it)
            // only spots that connect to the ground by the hut: the top of a wall is no place to be put on (there is no way down from it)
            final BlockPos home = StandSpots.nearest(world, building.getPosition(), 8, 4, 4, null);
            final Set<BlockPos> connected = home == null ? null : StandSpots.reachableFrom(world, home, 40, 40000);
            List<ReachSpots.Spot> spots = ReachSpots.best(world, workAhead(target), WorkerSettings.reach(), worker.getEyeHeight(), worker.blockPosition(),
              connected == null ? null : p -> connected.contains(p), 1);
            if (spots.isEmpty() && (scaffoldUnavailable() || retryBlocks.contains(target)))
            {
                // no tower is possible: the last resort is any spot from which the block is in reach
                spots = ReachSpots.best(world, workAhead(target), WorkerSettings.reach(), worker.getEyeHeight(), worker.blockPosition(), null, 1);
            }
            if (!spots.isEmpty())
            {
                spot = spots.get(0).feet();
            }
        }
        if (spot == null && area != null)
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
        if (this instanceof com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder && com.minecolonies.core.entity.ai.workers.util.ReachAudit.isEnabled())
        {
            Log.getLogger().info("SCAFFOLD {} teleports from {} to {} for {}", worker.getName().getString(), worker.blockPosition(), spot, target);
        }
        teleports++;
        puffAt(worker.position());
        worker.getNavigation().stop();
        worker.snapTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D);
        puffAt(worker.position());
        resetWalkTarget();
        workFrom = spot;
    }

    /**
     * Blocks that the worker was put on a spot for (once for each block in a structure).
     */
    private final Set<BlockPos> reachTeleported = new HashSet<>();

    /**
     * Puts the worker on a spot from which the block is in reach, wherever it is (also where no path leads: the inside of a closed box, the top of a wall).
     *
     * @param target the block.
     * @return true if there was such a spot.
     */
    private boolean teleportToReach(final BlockPos target)
    {
        final List<ReachSpots.Spot> spots = ReachSpots.best(world, workAhead(target), WorkerSettings.reach(), worker.getEyeHeight(), worker.blockPosition(),
          p -> hasWayDown(p) || !isBuiltStructureBlock(p.below()), 1);
        if (spots.isEmpty())
        {
            return false;
        }
        final BlockPos spot = spots.get(0).feet();
        if (this instanceof com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder && com.minecolonies.core.entity.ai.workers.util.ReachAudit.isEnabled())
        {
            Log.getLogger().info("SCAFFOLD {} is put on the spot {} that reaches {} (from {})", worker.getName().getString(), spot, target, worker.blockPosition());
        }
        teleports++;
        puffAt(worker.position());
        worker.getNavigation().stop();
        worker.snapTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D);
        puffAt(worker.position());
        resetWalkTarget();
        workFrom = spot;
        stuck.progress();
        return true;
    }

    /**
     * How often a structure worker was put on a spot by the unstuck ladder (for the tests).
     */
    public static volatile int teleports = 0;

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
        if (blockToMine == null || workFrom == null || !canReachNow(blockToMine))
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

        if (runScaffoldTick())
        {
            return getState();
        }

        final BlockState worldState = world.getBlockState(blockToMine);
        if (worldState.getBlock() instanceof AirBlock || worldState.getBlock() == Blocks.WATER)
        {
            return BUILDING_STEP;
        }

        reachTarget = usesPhysicalReach() ? blockToMine : null;
        if (!walkToConstructionSite(blockToMine))
        {
            if (reachTarget != null && cannotReachAtAll(blockToMine))
            {
                deferBlock(blockToMine, false);
                escalateStuck(blockToMine);
                return BUILDING_STEP;
            }
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
        onStructureReset();
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
