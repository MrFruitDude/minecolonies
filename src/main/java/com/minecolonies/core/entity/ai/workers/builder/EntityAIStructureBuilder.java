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
import com.minecolonies.core.colony.workorders.collab.BuilderCollab;
import com.minecolonies.core.colony.workorders.collab.WorkOrderCollab;
import com.minecolonies.core.entity.ai.workers.util.BuilderStageRules;
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

        final WorkOrderCollab collab = building.getWorkOrder().getCollab();
        if (!collab.hasStageTotals())
        {
            for (final BuildingProgressStage stage : structure.getStages())
            {
                collab.setStageTotal(stage, BuilderStageRules.estimateBlocks(structure.getBluePrint(), stage));
            }
        }
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

    @Override
    public boolean walkToConstructionSite(final BlockPos currentBlock)
    {
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
        return (int) (super.getBlockMiningTime(state, pos) * SPEED_BUFF_0);
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
        return collab != null && collab.isLeasedByOther(worldPos, building.getID());
    }

    @Override
    protected boolean canFinishStage()
    {
        final WorkOrderCollab collab = leadCollab();
        return collab == null || collab.getOpenLeasesExcluding(building.getID()) == 0;
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
            setDelay(40);
            return getState();
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
        if (batch.isEmpty())
        {
            if (collab.isScanExhausted())
            {
                setDelay(40);
                return getState();
            }
            final Map<ItemStorage, Integer> stockMemo = new HashMap<>();
            final int room = (int) Math.max(1, InventoryUtils.openSlotCount(worker.getInventoryCitizen()) - MIN_OPEN_SLOTS);
            final List<LeaseAllocator.Planned> plan = LeaseAllocator.plan(world, order, stage, assistHandler, assistPlacer, BuilderCollab.leaseSize(), LEAD_BUFFER,
              key -> stockMemo.computeIfAbsent(key, k -> InventoryUtils.getItemCountInItemHandler(lead.getItemHandlerCap(), sameAs(k.getItemStack()))
                                                      + leadCarried(lead, k.getItemStack())
                                                      + InventoryUtils.getItemCountInItemHandler(worker.getInventoryCitizen(), sameAs(k.getItemStack()))), room);
            if (plan.isEmpty())
            {
                setDelay(40);
                return getState();
            }
            gatherFromLead = false;
            gatherSteps = 0;
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
                return ASSIST_GATHER;
            }
        }
        return ASSIST_WORK;
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

    private void takeFrom(final com.ldtteam.structurize.api.compat.itemhandler.IItemHandler source, final AbstractBuildingStructureBuilder lead, final Map<ItemStorage, Integer> missing)
    {
        if (source == null)
        {
            return;
        }
        for (final Map.Entry<ItemStorage, Integer> need : missing.entrySet())
        {
            final ItemStack kind = need.getKey().getItemStack();
            final int taken = ItemMover.move(source, worker.getInventoryCitizen(), sameAs(kind), need.getValue());
            building.addCarry(lead.getID(), kind, taken);
        }
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
        final AbstractBuildingStructureBuilder lead = leadOf(assistOrder);
        assistOrder.getCollab().renew(building.getID(), world.getGameTime() + BuilderCollab.leaseTicks());
        final boolean giveUp = ++gatherSteps > MAX_GATHER_STEPS;
        if (!giveUp && !gatherFromLead && !missingForBatch().isEmpty())
        {
            // first from the lead's hut ...
            if (!walkToBuilding(lead))
            {
                return getState();
            }
            takeFrom(lead.getItemHandlerCap(), lead, missingForBatch());
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
                if (leadCitizen.getEntity().isPresent() && !EntityNavigationUtils.walkToPos(worker, leadCitizen.getEntity().get().blockPosition(), 3, false))
                {
                    return getState();
                }
                takeFrom(leadCitizen.getInventory(), lead, missingForBatch());
            }
        }
        gatherFromLead = false;
        gatherSteps = 0;

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
    private IAIState assistWork()
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
        if (!worker.getInventoryCitizen().hasSpace())
        {
            return INVENTORY_FULL;
        }

        final LeaseAllocator.Planned planned = batch.get(0);
        final BlockState worldState = world.getBlockState(planned.world());
        if (batchStage == BuildingProgressStage.CLEAR ? (worldState.isAir() || !worldState.getFluidState().isEmpty())
              : worldState == assistHandler.getBluePrint().getBlockState(planned.local()))
        {
            finishPosition(planned, lead, false);
            return getState();
        }

        if (!walkToConstructionSite(planned.world()))
        {
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
                worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                worker.queueSound(SoundEvents.BAMBOO_HIT, worker.blockPosition(), 10, 0, 0.5f, 0.1f);
                setPlacementDelay();
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
        if (!walkToConstructionSite(assistMine) || workFrom == null)
        {
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
