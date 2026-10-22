package com.minecolonies.core.colony.jobs;

import com.google.common.collect.ImmutableList;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.WarehouseRequestQueueModule;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import com.minecolonies.api.client.render.modeltype.ModModelTypes;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.requestsystem.StandardFactoryController;
import com.minecolonies.api.colony.requestsystem.data.IRequestSystemDeliveryManJobDataStore;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.AbstractDeliverymanRequestable;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.IDeliverymanRequestable;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Pickup;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.constant.NbtTagConstants;
import com.minecolonies.api.util.constant.TypeConstants;
import com.minecolonies.core.colony.buildings.modules.CourierAssignmentModule;
import com.minecolonies.api.colony.requestsystem.request.WaitReason;
import com.minecolonies.core.colony.requestsystem.RsFlags;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.requestsystem.RsProbe;
import com.minecolonies.core.colony.requestsystem.RsStats;
import com.minecolonies.core.colony.storage.ColonyStorage;
import com.minecolonies.core.colony.storage.DispatchCost;
import com.minecolonies.core.colony.storage.StorageNetwork;
import com.minecolonies.core.colony.requestsystem.management.IStandardRequestManager;
import com.minecolonies.core.colony.requestsystem.priority.RequestScoring;
import com.minecolonies.core.colony.requestsystem.requests.StandardRequests;
import com.minecolonies.core.colony.requestsystem.wait.CourierWatchdog;
import com.minecolonies.core.colony.requestsystem.wait.RequestWaitTracker;
import com.minecolonies.core.entity.ai.workers.service.EntityAIWorkDeliveryman;
import com.minecolonies.core.util.AttributeModifierUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static com.minecolonies.api.util.constant.BuildingConstants.TAG_ONGOING;
import static com.minecolonies.api.util.constant.CitizenConstants.SKILL_BONUS_ADD_NAME;
import static com.minecolonies.api.util.constant.Suppression.UNCHECKED;

/**
 * Class of the deliveryman job.
 */
public class JobDeliveryman extends AbstractJob<EntityAIWorkDeliveryman, JobDeliveryman>
{
    private IToken<?> rsDataStoreToken;

    /**
     * Walking speed bonus per level
     */
    public static final double BONUS_SPEED_PER_LEVEL = 0.003;

    /**
     * Round 13 (L6): a courier carries at least this many stacks, whatever its hut level (was 2 at level 1).
     */
    public static final int MIN_CARRY_STACKS = 4;

    /**
     * Round 13 (L2): a courier batches at least this many tasks to one destination (was 1 below skill 5).
     */
    public static final int MIN_BATCH = 3;

    /**
     * Round 13 (L2): the most tasks a courier batches by carry capacity (its skill may allow more).
     */
    public static final int MAX_CAPACITY_BATCH = 8;

    /**
     * Round 13 (L4): a courier that waits for work looks at its queue within this many ticks of a new task.
     */
    public static final int WAKE_TICKS = 5;

    /**
     * Old field for backwards compatibility.
     */
    private int ongoingDeliveries;

    /**
     * Round 13 (L11): where each task in the queue was taken from and when its wait began, so that a courier that goes on a
     * break can put it back at the head of that queue with its wait. In memory only; after a load the home warehouse is used.
     */
    private record Taken(@Nullable BlockPos warehouse, long since)
    {
    }

    private final Map<IToken<?>, Taken> taken = new HashMap<>();

    /**
     * RS2: deadline and stuck detection of the current task.
     */
    private final CourierWatchdog watchdog = new CourierWatchdog();

    /**
     * The task at the head of the queue and the game time it was first seen there (not persisted: after a load the age starts again).
     */
    @Nullable
    private IToken<?> headTask;
    private long      headSince;

    /**
     * Instantiates the job for the deliveryman.
     *
     * @param entity the citizen who becomes a deliveryman
     */
    public JobDeliveryman(final ICitizenData entity)
    {
        super(entity);
        if (entity != null)
        {
            setupRsDataStore();
        }
    }

    private void setupRsDataStore()
    {
        rsDataStoreToken = this.getCitizen()
                             .getColony()
                             .getRequestManager()
                             .getDataStoreManager()
                             .get(
                               StandardFactoryController.getInstance().getNewInstance(TypeConstants.ITOKEN),
                               TypeConstants.REQUEST_SYSTEM_DELIVERY_MAN_JOB_DATA_STORE
                             )
                             .getId();
    }

    @Override
    public void onLevelUp()
    {
        if (getCitizen().getEntity().isPresent())
        {
            final AbstractEntityCitizen worker = getCitizen().getEntity().get();
            final AttributeModifier speedModifier = new AttributeModifier(SKILL_BONUS_ADD_NAME, getCitizen().getCitizenSkillHandler().getLevel(getCitizen().getWorkBuilding().getModule(
              BuildingModules.COURIER_WORK).getPrimarySkill()) * BONUS_SPEED_PER_LEVEL, AttributeModifier.Operation.ADD_VALUE);
            AttributeModifierUtils.addModifier(worker, speedModifier, Attributes.MOVEMENT_SPEED);
        }
    }

    @NotNull
    @Override
    public Identifier getModel()
    {
        return ModModelTypes.COURIER_ID;
    }

    @Override
    public CompoundTag serializeNBT(@NotNull final HolderLookup.Provider provider)
    {
        final CompoundTag compound = super.serializeNBT(provider);
        compound.put(NbtTagConstants.TAG_RS_DMANJOB_DATASTORE, StandardFactoryController.getInstance().serializeTag(provider, rsDataStoreToken));
        return compound;
    }

    @Override
    public void deserializeNBT(@NotNull final HolderLookup.Provider provider, final CompoundTag compound)
    {
        super.deserializeNBT(provider, compound);

        if (compound.contains(NbtTagConstants.TAG_RS_DMANJOB_DATASTORE))
        {
            rsDataStoreToken = StandardFactoryController.getInstance().deserializeTag(provider, compound.getCompoundOrEmpty(NbtTagConstants.TAG_RS_DMANJOB_DATASTORE));
        }
        else
        {
            setupRsDataStore();
        }
        this.ongoingDeliveries = compound.getIntOr(TAG_ONGOING, 0);
    }

    /**
     * Generate your AI class to register.
     *
     * @return your personal AI instance.
     */
    @NotNull
    @Override
    public EntityAIWorkDeliveryman generateAI()
    {
        return new EntityAIWorkDeliveryman(this);
    }

    private IRequestSystemDeliveryManJobDataStore getDataStore()
    {
        return getCitizen().getColony().getRequestManager().getDataStoreManager().get(rsDataStoreToken, TypeConstants.REQUEST_SYSTEM_DELIVERY_MAN_JOB_DATA_STORE);
    }

    @Override
    public void serializeToView(final RegistryFriendlyByteBuf buffer)
    {
        super.serializeToView(buffer);
        StandardFactoryController.getInstance().serialize(buffer, rsDataStoreToken);
    }

    private LinkedList<IToken<?>> getTaskQueueFromDataStore()
    {
        return getDataStore().getQueue();
    }

    @Override
    public int getInactivityLimit()
    {
        return 60 * 10;
    }

    @Override
    public void triggerActivityChangeAction(final boolean newState)
    {
        try
        {
            if (newState)
            {
                getColony().getRequestManager().onColonyUpdate(request -> request.getRequest() instanceof Delivery || request.getRequest() instanceof Pickup);
            }
            else
            {
                cancelAssignedRequests();
            }
        }
        catch (final Exception ex)
        {
            Log.getLogger().warn("Active Triggered resulted in exception", ex);
        }
    }

    /**
     * RS2: a task has a deadline, and a courier that does not move is stuck. Either way the task goes back to the request system.
     *
     * @param current the task at the head of the queue.
     * @return true when the task was given up.
     */
    private boolean watchTask(final IToken<?> current)
    {
        final var entity = getCitizen().getEntity();
        if (entity.isEmpty())
        {
            return false;
        }
        final CourierWatchdog.Verdict verdict = watchdog.check(current, entity.get().blockPosition(), getColony().getWorld().getGameTime());
        if (verdict == CourierWatchdog.Verdict.OK)
        {
            return false;
        }
        final IRequest<?> request = getColony().getRequestManager().getRequestForToken(current);
        if (request != null)
        {
            Log.getLogger().warn("Courier {} gave up {} ({}): {}", getCitizen().getName(), current, verdict, request.getRequest());
            final RequestWaitTracker tracker = RequestWaitTracker.of(getColony().getRequestManager());
            if (tracker != null)
            {
                tracker.setReason(request, WaitReason.COURIER_STUCK);
                tracker.notifier().report(getColony(), request, WaitReason.COURIER_STUCK, getColony().getWorld().getGameTime());
            }
        }
        watchdog.reset();
        giveUp(current, request);
        return true;
    }

    private void trackHead(final IToken<?> head)
    {
        if (!head.equals(headTask))
        {
            headTask = head;
            headSince = getColony().getWorld().getGameTime();
        }
    }

    /**
     * @return how many game ticks the courier has held the task at the head of its queue (0 without a task).
     */
    public long currentTaskAge()
    {
        final IToken<?> head = getTaskQueueFromDataStore().peekFirst();
        if (head == null)
        {
            return 0L;
        }
        trackHead(head);
        return getColony().getWorld().getGameTime() - headSince;
    }

    /**
     * The courier is leaving (a meal, sleep, shelter from the rain) with tasks still in its queue: hands every one of them back, so that another
     * courier can take them. The queue of a courier is the only place its tasks are kept; a task left there while the courier is away stands still
     * for the whole break.
     * <p>
     * Round 13 (L11): a task the courier has not started yet goes back to the head of the warehouse queue it came from, with the wait it already
     * had (its promises stay), instead of failing; an idle courier is woken for it. A delivery whose items the courier already carries fails as
     * before (the items are in its bag; the request system serves the request again).
     *
     * @return how many tasks were handed back.
     */
    public int giveBackTasks()
    {
        int handedBack = 0;
        final LinkedList<IToken<?>> queue = getTaskQueueFromDataStore();
        final List<IToken<?>> requeue = new ArrayList<>();
        final List<IToken<?>> started = new ArrayList<>();
        for (final IToken<?> token : new ArrayList<>(queue))
        {
            final IRequest<?> request = getColony().getRequestManager().getRequestForToken(token);
            if (request == null)
            {
                continue;
            }
            if (getDataStore().getOngoingDeliveries().contains(token) || !canRequeue(token))
            {
                started.add(token);
            }
            else
            {
                requeue.add(token);
            }
        }
        queue.clear();
        for (final IToken<?> token : started)
        {
            getDataStore().getOngoingDeliveries().remove(token);
            final IRequest<?> failed = getColony().getRequestManager().getRequestForToken(token);
            if (failed != null && failed.getState() == RequestState.IN_PROGRESS)
            {
                getColony().getRequestManager().updateRequestState(token, RequestState.FAILED);
            }
            handedBack++;
        }
        final Set<IWareHouse> woken = new HashSet<>();
        for (int i = requeue.size() - 1; i >= 0; i--)
        {
            final IWareHouse wareHouse = requeueAtHead(requeue.get(i));
            if (wareHouse != null)
            {
                woken.add(wareHouse);
            }
            handedBack++;
        }
        taken.clear();
        if (getCitizen().getWorkBuilding() != null)
        {
            getCitizen().getWorkBuilding().markDirty();
        }
        watchdog.reset();
        headTask = null;
        for (final IWareHouse wareHouse : woken)
        {
            wakeIdleCouriers(getColony(), wareHouse, getCitizen());
        }
        return handedBack;
    }

    /**
     * @return true when the queue the task came from is known (or the home warehouse can take it).
     */
    private boolean canRequeue(final IToken<?> token)
    {
        return queueOf(token) != null;
    }

    @Nullable
    private IWareHouse queueOf(final IToken<?> token)
    {
        final Taken from = taken.get(token);
        if (from != null && from.warehouse() != null)
        {
            final IWareHouse wareHouse = getColony().getServerBuildingManager().getBuilding(from.warehouse(), IWareHouse.class);
            if (wareHouse != null && wareHouse.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE) != null)
            {
                return wareHouse;
            }
        }
        final IWareHouse home = findWareHouse();
        return home == null || home.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE) == null ? null : home;
    }

    /**
     * Round 13 (L11): puts a task back at the head of its warehouse queue, with its wait clock.
     *
     * @return the warehouse whose queue took it.
     */
    @Nullable
    private IWareHouse requeueAtHead(final IToken<?> token)
    {
        final IWareHouse wareHouse = queueOf(token);
        if (wareHouse == null)
        {
            return null;
        }
        final WarehouseRequestQueueModule module = wareHouse.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE);
        module.getMutableRequestList().remove(token);
        module.getMutableRequestList().add(0, token);
        module.markDirty();
        final Taken from = taken.get(token);
        if (from != null && getColony().getRequestManager() instanceof IStandardRequestManager standard)
        {
            standard.getBuildingPriorities().restoreWait(token, from.since());
        }
        return wareHouse;
    }

    /**
     * Fails the task at the head of the queue and the deliveries batched with it, and takes them off the queue. Unlike
     * {@link #finishRequest}, which only finishes the deliveries the courier already started to gather, this also ends a task
     * the courier never got to.
     */
    private void giveUp(final IToken<?> current, @Nullable final IRequest<?> request)
    {
        final List<IToken<?>> batch = new ArrayList<>();
        batch.add(current);
        if (request != null && request.getRequest() instanceof Delivery)
        {
            for (final IRequest<? extends Delivery> sibling : getTaskListWithSameDestination((IRequest<? extends Delivery>) request))
            {
                if (!batch.contains(sibling.getId()))
                {
                    batch.add(sibling.getId());
                }
            }
        }
        for (final IToken<?> token : batch)
        {
            getTaskQueueFromDataStore().remove(token);
            getDataStore().getOngoingDeliveries().remove(token);
        }
        for (final IToken<?> token : batch)
        {
            final IRequest<?> failed = getColony().getRequestManager().getRequestForToken(token);
            if (failed != null && failed.getState() == RequestState.IN_PROGRESS)
            {
                getColony().getRequestManager().updateRequestState(token, RequestState.FAILED);
            }
        }
        getCitizen().getWorkBuilding().markDirty();
    }

    /**
     * The score of a task in the warehouse queue; the highest is taken. RS3 (flag {@code priorityClasses}) scores by importance,
     * building priority and the real wait; otherwise the old sum with the aging counter.
     */
    private long getTaskScore(final IRequest<?> req, final int queueIndex, final int queueSize)
    {
        if (!RsFlags.priorityClasses())
        {
            return getRequestPriority(req, queueIndex, queueSize);
        }
        final BlockPos target = getTarget(req);
        if (StorageNetwork.on(getColony()))
        {
            return RequestScoring.courierScore(getColony(), req, getSource(req), target, WorldUtil.isBlockLoaded(getColony().getWorld(), target), estimateSeconds(req));
        }
        // Round 13 (L3): inside a wait bucket the courier's own walk decides, from where it stands.
        final BlockPos start = req.getRequest() instanceof Delivery delivery ? delivery.getStart().getInDimensionLocation() : null;
        final IWareHouse home = scoringHome != null ? scoringHome : findWareHouse();
        final int eta = RequestScoring.etaBlocks(scoringHere, start, target, home == null ? null : home.getID());
        return RequestScoring.courierScore(getColony(), req, getSource(req), target, WorldUtil.isBlockLoaded(getColony().getWorld(), target), eta);
    }

    /**
     * The courier's position and home while a task is chosen (read once, not per task); null otherwise.
     */
    @Nullable
    private BlockPos scoringHere;
    @Nullable
    private IWareHouse scoringHome;

    /**
     * Storage network: how long the task takes this courier from where it stands, in seconds: the walk to the pickup, the
     * wait at the source warehouse's ramps, the handling, the walk to the destination.
     */
    private int estimateSeconds(final IRequest<?> req)
    {
        final BlockPos here = scoringHere != null ? scoringHere : courierPosition();
        final ColonyStorage storage = ColonyStorage.of(getColony());
        final BlockPos target = getTarget(req);
        if (req.getRequest() instanceof final Delivery delivery)
        {
            final BlockPos start = delivery.getStart().getInDimensionLocation();
            final BlockPos wareHouse = storage.owningWarehouse(start);
            final IBuilding building = wareHouse == null ? null : getColony().getServerBuildingManager().getBuilding(wareHouse);
            final int queued = wareHouse == null ? 0 : storage.taskLoad(wareHouse);
            final int items = Math.max(1, delivery.getStack().getCount());
            return (DispatchCost.travelTicks(here, start) + DispatchCost.queueTicks(building == null ? 1 : building.getBuildingLevel(), queued) + DispatchCost.handlingTicks(items)
                      + DispatchCost.travelTicks(start, target) + DispatchCost.handlingTicks(items)) / 20;
        }
        final IWareHouse home = scoringHome != null ? scoringHome : findWareHouse();
        return (DispatchCost.travelTicks(here, target) + DispatchCost.handlingTicks(64) + (home == null ? 0 : DispatchCost.travelTicks(target, home.getID()))) / 20;
    }

    /**
     * @return where the courier is now (its home warehouse when it is not in the world).
     */
    @NotNull
    private BlockPos courierPosition()
    {
        final var entity = getCitizen().getEntity();
        if (entity.isPresent())
        {
            return entity.get().blockPosition();
        }
        final IWareHouse home = findWareHouse();
        return home == null ? BlockPos.ZERO : home.getID();
    }

    private int getRequestPriority(final IRequest<?> req, final int queueIndex, final int queueSize)
    {
        final boolean network = StorageNetwork.on(getColony());
        int priority = 1;
        if (!WorldUtil.isBlockLoaded(getColony().getWorld(), getTarget(req)))
        {
            priority -= 1000;
        }
        if (req.getRequest() instanceof AbstractDeliverymanRequestable requestable)
        {
            priority = requestable.getPriority();
            if (requestable instanceof Pickup pickup && pickup.getDay() > getColony().getDay())
            {
                priority -= 100;
            }
        }

        priority += queueSize - queueIndex;
        final int distance = network ? estimateSeconds(req) : (int) Math.sqrt(getSource(req).distManhattan(getTarget(req)));
        return priority - distance;
    }

    /**
     * Returns the {@link IRequest} of the current Task.
     *
     * @return {@link IRequest} of the current Task.
     */
    @SuppressWarnings(UNCHECKED)
    // TODO: Rework logic to account for partially unloaded colonies, skipping tasks who's location is unloaded temporarily
    public IRequest<IDeliverymanRequestable> getCurrentTask()
    {
        final boolean probe = RsProbe.enabled;
        if (probe)
        {
            RsProbe.begin(RsProbe.COURIER_PICK);
        }
        try
        {
            return getCurrentTask0();
        }
        finally
        {
            if (probe)
            {
                RsProbe.end(RsProbe.COURIER_PICK);
            }
        }
    }

    @SuppressWarnings(UNCHECKED)
    private IRequest<IDeliverymanRequestable> getCurrentTask0()
    {
        final IToken<?> currentRequest = getTaskQueueFromDataStore().peekFirst();
        if (currentRequest != null)
        {
            trackHead(currentRequest);
            if (RsFlags.smartRetry() && watchTask(currentRequest))
            {
                // Given up: the queue moved on.
                return getCurrentTask0();
            }
            return (IRequest<IDeliverymanRequestable>) getColony().getRequestManager().getRequestForToken(currentRequest);
        }
        watchdog.reset();
        taken.clear();

        final IBuilding wareHouse = findWareHouse();
        if (wareHouse == null)
        {
            return null;
        }

        final boolean network = StorageNetwork.on(getColony());
        final List<WarehouseRequestQueueModule> queues = new ArrayList<>();
        if (network)
        {
            // The pool is the colony's: every queue of a warehouse in the service range, the home queue first.
            for (final IWareHouse served : StorageNetwork.servedBy((Colony) getColony(), (IWareHouse) wareHouse))
            {
                queues.add(served.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE));
            }
        }
        else
        {
            queues.add(wareHouse.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE));
            adoptOrphanQueues(queues);
        }

        // Round 13 (L10): the courier's position and home warehouse are read once per decision, not per task.
        scoringHere = courierPosition();
        scoringHome = (IWareHouse) wareHouse;
        try
        {
            return chooseTask(queues);
        }
        finally
        {
            scoringHere = null;
            scoringHome = null;
        }
    }

    @SuppressWarnings(UNCHECKED)
    private IRequest<IDeliverymanRequestable> chooseTask(final List<WarehouseRequestQueueModule> queues)
    {
        int totalTasks = 0;
        for (final WarehouseRequestQueueModule queue : queues)
        {
            totalTasks += queue.getMutableRequestList().size();
        }
        if (totalTasks == 0)
        {
            return null;
        }

        final Set<IToken<?>> reqsToRemove = new HashSet<>();

        IToken<?> resultRequestId = null;
        WarehouseRequestQueueModule resultModule = null;
        int resultIndex = -1;
        long priority = Long.MIN_VALUE;
        for (final WarehouseRequestQueueModule wareHouseModule : queues)
        {
            final int queueSize = wareHouseModule.getMutableRequestList().size();
            int queueIndex = 0;
            for (final IToken<?> reqId : wareHouseModule.getMutableRequestList())
            {
                final int position = queueIndex++;
                final IRequest<?> req = getColony().getRequestManager().getRequestForToken(reqId);
                if (req == null)
                {
                    reqsToRemove.add(reqId);
                    continue;
                }
                final long localPriority = getTaskScore(req, position, queueSize);
                if (localPriority > priority)
                {
                    priority = localPriority;
                    resultRequestId = reqId;
                    resultModule = wareHouseModule;
                    resultIndex = position;
                }
            }
        }

        if (resultRequestId == null)
        {
            // Only dead tokens: drop them.
            for (final WarehouseRequestQueueModule wareHouseModule : queues)
            {
                wareHouseModule.getMutableRequestList().removeAll(reqsToRemove);
            }
            return null;
        }

        reqsToRemove.add(resultRequestId);
        remember(resultRequestId, resultModule);
        if (RsFlags.priorityClasses() && getColony().getRequestManager() instanceof IStandardRequestManager standard)
        {
            standard.getBuildingPriorities().forgetWait(resultRequestId);
        }

        final IRequest<?> resultRequest = getColony().getRequestManager().getRequestForToken(resultRequestId);
        if (resultRequest instanceof StandardRequests.DeliveryRequest)
        {
            getTaskQueueFromDataStore().add(resultRequestId);
        }
        int extendedReqs = 1;
        batching:
        for (final WarehouseRequestQueueModule wareHouseModule : queues)
        {
            int index = 0;
            for (final IToken<?> reqId : wareHouseModule.getMutableRequestList())
            {
                final IRequest<?> localRequest = getColony().getRequestManager().getRequestForToken(reqId);
                if (localRequest == null)
                {
                    reqsToRemove.add(reqId);
                    index++;
                    continue;
                }

                final boolean sameQueue = wareHouseModule == resultModule;
                // If we skipped this, we should add this
                if (sameQueue && index < resultIndex)
                {
                    if (localRequest.getRequest() instanceof AbstractDeliverymanRequestable requestable)
                    {
                        requestable.incrementPriorityDueToAging();
                        if (localRequest instanceof com.minecolonies.core.colony.requestsystem.requests.AbstractRequest<?> aged)
                        {
                            aged.touch();
                        }
                    }
                }
                else if (sameQueue && index == resultIndex)
                {
                    index++;
                    continue;
                }

                if (getTarget(localRequest).equals(getTarget(resultRequest)))
                {
                    getTaskQueueFromDataStore().add(reqId);
                    remember(reqId, wareHouseModule);
                    extendedReqs++;
                    reqsToRemove.add(reqId);
                }

                index++;
                if (extendedReqs >= getMaxParallelDeliveries())
                {
                    break batching;
                }
            }
        }

        if (resultRequest instanceof StandardRequests.PickupRequest)
        {
            getTaskQueueFromDataStore().add(resultRequestId);
        }

        for (final WarehouseRequestQueueModule wareHouseModule : queues)
        {
            wareHouseModule.getMutableRequestList().removeAll(reqsToRemove);
            wareHouseModule.markDirty();
        }

        return (IRequest<IDeliverymanRequestable>) resultRequest;
    }

    /**
     * Round 13 (L11): notes the queue a task was taken from and when its wait began.
     */
    private void remember(final IToken<?> token, @Nullable final WarehouseRequestQueueModule module)
    {
        final long now = getColony().getWorld().getGameTime();
        final long waited = getColony().getRequestManager() instanceof IStandardRequestManager standard ? standard.getBuildingPriorities().peekWaited(token, now) : 0L;
        taken.put(token, new Taken(module == null ? null : module.getBuilding().getID(), now - waited));
    }

    /**
     * Mixed-flag worlds: tasks that sit in the queue of a warehouse nobody works for (they were queued while the storage
     * network was on) are served by the other couriers once it is off, so they are not lost.
     */
    private void adoptOrphanQueues(@NotNull final List<WarehouseRequestQueueModule> queues)
    {
        if (!queues.get(0).getMutableRequestList().isEmpty())
        {
            return;
        }
        for (final IWareHouse other : getColony().getServerBuildingManager().getWareHouses())
        {
            if (other.getModule(BuildingModules.WAREHOUSE_COURIERS).getAssignedCitizen().isEmpty())
            {
                final WarehouseRequestQueueModule orphan = other.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE);
                if (orphan != null && !orphan.getMutableRequestList().isEmpty())
                {
                    queues.add(orphan);
                }
            }
        }
    }

    /**
     * Method called to mark the current request as finished.
     *
     * @param successful True when the processing was successful, false when not.
     */
    public void finishRequest(final boolean successful)
    {
        finishRequest(request -> successful);
    }

    /**
     * Method called to mark the current request, and the deliveries running along with it, as finished.
     *
     * @param successful decides per request whether it was handled successfully.
     */
    public void finishRequest(final Predicate<IRequest<?>> successful)
    {
        final boolean probe = RsProbe.enabled;
        if (probe)
        {
            RsProbe.begin(RsProbe.COURIER_DONE);
        }
        try
        {
            finishRequest0(successful);
        }
        finally
        {
            if (probe)
            {
                RsProbe.end(RsProbe.COURIER_DONE);
            }
        }
    }

    private void finishRequest0(final Predicate<IRequest<?>> successful)
    {
        if (getTaskQueueFromDataStore().isEmpty())
        {
            return;
        }
        RsStats.courierTasksFinished++;

        final IToken<?> current = getTaskQueueFromDataStore().getFirst();

        final IRequest<?> request = getColony().getRequestManager().getRequestForToken(current);

        if (request == null)
        {
            if (!getTaskQueueFromDataStore().isEmpty() && current == getTaskQueueFromDataStore().getFirst())
            {
                getTaskQueueFromDataStore().removeFirst();
            }
            return;
        }
        else if (request.getRequest() instanceof Delivery)
        {
            final List<IRequest<? extends Delivery>> taskList = getTaskListWithSameDestination((IRequest<? extends Delivery>) request);
            if (ongoingDeliveries != 0)
            {
                for (int i = 0; i < Math.max(1, Math.min(ongoingDeliveries, taskList.size())); i++)
                {
                    final IRequest<? extends Delivery> req = taskList.get(i);
                    if (req.getState() == RequestState.IN_PROGRESS)
                    {
                        getColony().getRequestManager().updateRequestState(req.getId(), successful.test(req) ? RequestState.RESOLVED : RequestState.FAILED);
                    }
                    getTaskQueueFromDataStore().remove(req.getId());
                }
            }
            else
            {
                for (final IToken<?> token : new ArrayList<>(getDataStore().getOngoingDeliveries()))
                {
                    final IRequest<?> req = getColony().getRequestManager().getRequestForToken(token);
                    if (req != null && req.getState() == RequestState.IN_PROGRESS)
                    {
                        getColony().getRequestManager().updateRequestState(req.getId(), successful.test(req) ? RequestState.RESOLVED : RequestState.FAILED);
                    }
                    getTaskQueueFromDataStore().remove(token);
                    getDataStore().getOngoingDeliveries().remove(token);
                }
            }
        }
        else if (request.getRequest() instanceof Pickup)
        {
            getTaskQueueFromDataStore().remove(request.getId());
            getColony().getRequestManager().updateRequestState(current, successful.test(request) ? RequestState.RESOLVED : RequestState.FAILED);
        }
        else
        {
            getColony().getRequestManager().updateRequestState(current, successful.test(request) ? RequestState.RESOLVED : RequestState.FAILED);

            //Just to be sure lets delete them!
            if (!getTaskQueueFromDataStore().isEmpty() && current == getTaskQueueFromDataStore().getFirst())
            {
                getTaskQueueFromDataStore().removeFirst();
            }
        }

        getCitizen().getWorkBuilding().markDirty();

        if (RsFlags.smartRetry())
        {
            // RS2: a courier is free again: deliveries that waited for one are looked at.
            watchdog.reset();
            final RequestWaitTracker tracker = RequestWaitTracker.of(getColony().getRequestManager());
            if (tracker != null)
            {
                // Round 13 (L9): as many waiters as there are free couriers, the longest waiting first, not every one of them.
                tracker.wakeByReason(Set.of(WaitReason.NO_COURIER, WaitReason.COURIER_BUSY), Math.max(1, freeCouriers(getColony())));
            }
        }
    }

    /**
     * Called when a task that is being scheduled is being canceled.
     *
     * @param token token of the task to be deleted.
     */
    public void onTaskDeletion(@NotNull final IToken<?> token)
    {
        if (getTaskQueueFromDataStore().contains(token))
        {
            getTaskQueueFromDataStore().remove(token);
        }
        taken.remove(token);

        if (getCitizen().getWorkBuilding() != null)
        {
            getCitizen().getWorkBuilding().markDirty();
        }
    }

    /**
     * Method to get the task queue of this job.
     *
     * @return The task queue.
     */
    public List<IToken<?>> getTaskQueue()
    {
        return ImmutableList.copyOf(getTaskQueueFromDataStore());
    }

    private void cancelAssignedRequests()
    {
        for (final IToken<?> t : getTaskQueue())
        {
            final IRequest<?> r = getColony().getRequestManager().getRequestForToken(t);
            if (r != null)
            {
                getColony().getRequestManager().updateRequestState(t, RequestState.FAILED);
            }
            else
            {
                Log.getLogger().warn("Oops, the request with ID: " + t.toString() + " couldn't be cancelled by the deliveryman because it doesn't exist");
            }
            getTaskQueueFromDataStore().remove(t);
        }
    }

    @Override
    public void onRemoval()
    {
        getCitizen().setWorking(false);
        try
        {
            cancelAssignedRequests();
        }
        catch (final Exception ex)
        {
            Log.getLogger().warn("Active Triggered resulted in exception", ex);
        }
        super.onRemoval();
        getColony().getRequestManager().getDataStoreManager().remove(this.rsDataStoreToken);
    }

    /**
     * Check if two deliveries have the same source and destination.
     *
     * @param requestA the first request.
     * @param requestB the second request.
     * @return true if so.
     */
    private boolean haveTasksSameSourceAndDest(@NotNull final Delivery requestA, @NotNull final Delivery requestB)
    {
        if (requestA.getTarget().equals(requestB.getTarget()))
        {
            if (requestA.getStart().equals(requestB.getStart()))
            {
                return true;
            }
            for (final IWareHouse wareHouse : getColony().getServerBuildingManager().getWareHouses())
            {
                if (wareHouse.hasContainerPosition(requestA.getStart().getInDimensionLocation()) && wareHouse.hasContainerPosition(requestB.getStart().getInDimensionLocation()))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Build a list of all requests that have the same source/dest pair.
     *
     * @param request the first request.
     * @return a list.
     */
    public List<IRequest<? extends Delivery>> getTaskListWithSameDestination(final IRequest<? extends Delivery> request)
    {
        final List<IRequest<? extends Delivery>> deliveryList = new ArrayList<>();
        deliveryList.add(request);
        for (final IToken<?> requestToken : getTaskQueue())
        {
            if (!requestToken.equals(request.getId()))
            {
                final IRequest<?> compareRequest = getColony().getRequestManager().getRequestForToken(requestToken);
                if (compareRequest != null && compareRequest.getRequest() instanceof Delivery)
                {
                    final Delivery current = (Delivery) compareRequest.getRequest();
                    final Delivery newDev = request.getRequest();
                    if (haveTasksSameSourceAndDest(current, newDev))
                    {
                        deliveryList.add((IRequest<? extends Delivery>) compareRequest);
                    }
                }
            }
        }
        return deliveryList;
    }

    /**
     * Calculate the max parallel deliveries the courier can do.
     * @return the max.
     */
    public int getMaxParallelDeliveries()
    {
        if (getWorkModule().getAssignedCitizen().isEmpty())
        {
            return 1;
        }
        final int bySkill = 1 + (getWorkModule().getAssignedCitizen().get(0).getCitizenSkillHandler().getLevel(((WorkerBuildingModule) getWorkModule()).getSecondarySkill()) / 5);
        final IBuilding hut = getCitizen().getWorkBuilding();
        return maxParallelDeliveries(bySkill, hut == null ? 1 : hut.getBuildingLevel(), hut == null ? 5 : hut.getMaxBuildingLevel());
    }

    /**
     * Round 13 (L2): how many tasks to one destination a courier carries together: as many as it can carry (at most
     * {@value #MAX_CAPACITY_BATCH}), at least {@value #MIN_BATCH}, or more when its skill allows.
     *
     * @param bySkill  the old skill-based count, 1 + skill / 5.
     * @param level    the courier hut's level.
     * @param maxLevel the courier hut's maximum level.
     * @return the batch size.
     */
    public static int maxParallelDeliveries(final int bySkill, final int level, final int maxLevel)
    {
        return Math.max(MIN_BATCH, Math.max(bySkill, Math.min(MAX_CAPACITY_BATCH, carryStacks(level, maxLevel))));
    }

    /**
     * Round 13 (L6): how many stacks a courier carries: 2^(level-1)+1 as before, at least {@value #MIN_CARRY_STACKS}, no limit at the maximum
     * level (decision 2026-10-22: 4 stacks at level 1).
     *
     * @param level    the courier hut's level.
     * @param maxLevel the courier hut's maximum level.
     * @return the stacks, {@link Integer#MAX_VALUE} for no limit.
     */
    public static int carryStacks(final int level, final int maxLevel)
    {
        if (level >= maxLevel)
        {
            return Integer.MAX_VALUE;
        }
        return Math.max(MIN_CARRY_STACKS, (1 << Math.max(0, level - 1)) + 1);
    }

    /**
     * Round 13 (L10): whether the task sits in this courier's queue, without copying the queue.
     *
     * @param token a request token.
     * @return true when it does.
     */
    public boolean holdsTask(@NotNull final IToken<?> token)
    {
        return getTaskQueueFromDataStore().contains(token);
    }

    /**
     * @param colony a colony.
     * @return how many of its couriers have nothing in their queue.
     */
    public static int freeCouriers(@NotNull final com.minecolonies.api.colony.IColony colony)
    {
        int free = 0;
        for (final IWareHouse wareHouse : colony.getServerBuildingManager().getWareHouses())
        {
            for (final ICitizenData courier : wareHouse.getModule(BuildingModules.WAREHOUSE_COURIERS).getAssignedCitizen())
            {
                if (courier.getJob() instanceof final JobDeliveryman job && job.getTaskQueueFromDataStore().isEmpty())
                {
                    free++;
                }
            }
        }
        return free;
    }

    /**
     * Round 13 (L4): a task was queued at a warehouse: the couriers that serve it and wait for work look at their queue within
     * {@value #WAKE_TICKS} ticks, instead of at the end of their 5 second decision delay.
     *
     * @param colony    the colony.
     * @param wareHouse the warehouse whose queue got the task.
     * @param except    a courier not to wake (the one that is leaving), may be null.
     * @return how many couriers were woken.
     */
    public static int wakeIdleCouriers(@NotNull final com.minecolonies.api.colony.IColony colony, @NotNull final IWareHouse wareHouse, @Nullable final ICitizenData except)
    {
        if (colony.getWorld() == null || colony.getWorld().isClientSide())
        {
            return 0;
        }
        final List<ICitizenData> couriers = new ArrayList<>();
        if (colony instanceof final Colony real && StorageNetwork.on(real))
        {
            for (final IWareHouse home : colony.getServerBuildingManager().getWareHouses())
            {
                if (StorageNetwork.inRange(home.getID(), wareHouse.getID()))
                {
                    couriers.addAll(home.getModule(BuildingModules.WAREHOUSE_COURIERS).getAssignedCitizen());
                }
            }
        }
        else
        {
            couriers.addAll(wareHouse.getModule(BuildingModules.WAREHOUSE_COURIERS).getAssignedCitizen());
        }
        int woken = 0;
        for (final ICitizenData courier : couriers)
        {
            if (courier != except && courier.getJob() instanceof final JobDeliveryman job && job.getTaskQueueFromDataStore().isEmpty()
                  && job.getWorkerAI() instanceof final EntityAIWorkDeliveryman ai && ai.wakeSoon(WAKE_TICKS))
            {
                woken++;
            }
        }
        return woken;
    }

    /**
     * Gets the source position of a request, pickups are reversed
     *
     * @param request
     * @return
     */
    private BlockPos getSource(final IRequest<?> request)
    {
        if (request.getRequest() instanceof Delivery)
        {
            return ((Delivery) request.getRequest()).getStart().getInDimensionLocation();
        }

        if (request.getRequest() instanceof Pickup)
        {
            final IWareHouse wareHouse = scoringHome != null ? scoringHome : findWareHouse();
            if (wareHouse != null)
            {
                return wareHouse.getID();
            }
        }

        return null;
    }

    /**
     * Gets the target position of a request, pickups are reversed
     *
     * @param request
     * @return
     */
    private BlockPos getTarget(final IRequest<?> request)
    {
        if (request.getRequest() instanceof Delivery)
        {
            return ((Delivery) request.getRequest()).getTarget().getInDimensionLocation();
        }

        if (request.getRequest() instanceof Pickup)
        {
            return request.getRequester().getLocation().getInDimensionLocation();
        }

        return null;
    }

    /**
     * Finds the warehouse our dman is assigned to
     *
     * @return warehouse building or null
     */
    public IWareHouse findWareHouse()
    {
        for (final IWareHouse building : getColony().getServerBuildingManager().getWareHouses())
        {
            if (building.getFirstModuleOccurance(CourierAssignmentModule.class).hasAssignedCitizen(getCitizen()))
            {
                return building;
            }
        }

        return null;
    }

    /**
     * Where the load the courier carries was last picked up (a building), for the storage flows.
     */
    private BlockPos lastPickupSource;

    /**
     * @param source the building the courier picked up at.
     */
    public void rememberPickupSource(@NotNull final BlockPos source)
    {
        lastPickupSource = source;
    }

    /**
     * @return where the load was picked up, the home warehouse when unknown.
     */
    @NotNull
    public BlockPos lastPickupSource()
    {
        if (lastPickupSource != null)
        {
            return lastPickupSource;
        }
        final IWareHouse home = findWareHouse();
        return home == null ? BlockPos.ZERO : home.getID();
    }

    /**
     * The warehouse this courier should dump its load into. With the storage network off that is the home warehouse. With it
     * on, it is the warehouse (of those the courier serves) that has room and finishes the drop first: the walk from where the
     * courier stands plus the wait at the warehouse's ramps plus the handling.
     *
     * @param excluded warehouses (building ids) that already refused the load.
     * @return the warehouse, null when none is left.
     */
    @Nullable
    public IWareHouse findDumpWarehouse(@NotNull final Set<BlockPos> excluded)
    {
        final IWareHouse home = findWareHouse();
        if (home == null || !StorageNetwork.on(getColony()))
        {
            return home;
        }
        final Colony colony = (Colony) getColony();
        final ColonyStorage storage = ColonyStorage.of(colony);
        final BlockPos here = courierPosition();
        IWareHouse best = null;
        int bestCost = Integer.MAX_VALUE;
        for (final IWareHouse candidate : StorageNetwork.servedBy(colony, home))
        {
            if (excluded.contains(candidate.getID()) || candidate.getTileEntity() == null || !storage.hasFreeSlots(candidate.getID()))
            {
                continue;
            }
            final int cost = DispatchCost.dropTicks(candidate, storage.taskLoad(candidate.getID()), here, 64);
            if (cost < bestCost)
            {
                bestCost = cost;
                best = candidate;
            }
        }
        if (best != null)
        {
            return best;
        }
        // Nothing has a free slot: the home warehouse still takes what merges into its part-filled stacks.
        return excluded.contains(home.getID()) ? null : home;
    }

    /**
     * Add a concurrent delivery that is going on.
     * @param requestToken the token of the request.
     */
    public void addConcurrentDelivery(final IToken<?> requestToken)
    {
        getDataStore().getOngoingDeliveries().add(requestToken);
    }

    /**
     * Remove a concurrent delivery that is going on.
     * @param requestToken the token of the request.
     */
    public void removeConcurrentDelivery(final IToken<?> requestToken)
    {
        getDataStore().getOngoingDeliveries().remove(requestToken);
    }

    @Override
    public double getSaturationFactor()
    {
        return 1.2;
    }
}
