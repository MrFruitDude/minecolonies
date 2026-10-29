package com.minecolonies.core.entity.ai.workers.service;

import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.interactionhandling.ChatPriority;
import com.minecolonies.api.colony.requestsystem.location.ILocation;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.IRequestable;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.IDeliverymanRequestable;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.core.colony.requestsystem.RsFlags;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.citizen.VisibleCitizenStatus;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.StatsUtil;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingDeliveryman;
import com.minecolonies.core.colony.interactionhandling.PosBasedInteraction;
import com.minecolonies.core.colony.interactionhandling.StandardInteraction;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.requestsystem.RefusedTargets;
import com.minecolonies.core.colony.requestsystem.RsCourierHooks;
import com.minecolonies.core.colony.storage.ColonyStorage;
import com.minecolonies.core.colony.storage.StorageNetwork;
import com.minecolonies.core.colony.requestsystem.requests.StandardRequests.DeliveryRequest;
import com.minecolonies.core.colony.requestsystem.requests.StandardRequests.PickupRequest;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIInteract;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import com.minecolonies.core.tileentities.TileEntityRack;
import com.minecolonies.core.util.citizenutils.CitizenItemUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.ldtteam.structurize.api.compat.itemhandler.IItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState.*;
import static com.minecolonies.api.util.constant.Constants.TICKS_SECOND;
import static com.minecolonies.api.util.constant.StatisticsConstants.ITEMS_DELIVERED;
import static com.minecolonies.api.util.constant.StatisticsConstants.DELIVERIES_MADE;
import static com.minecolonies.api.util.constant.StatisticsConstants.PICKUPS_MADE;
import static com.minecolonies.api.util.constant.TranslationConstants.*;

/**
 * Delivers item at needs.
 */
public class EntityAIWorkDeliveryman extends AbstractEntityAIInteract<JobDeliveryman, BuildingDeliveryman>
{
    /**
     * Min distance the worker should have to the warehouse to make any decisions.
     */
    private static final int MIN_DISTANCE_TO_WAREHOUSE = 5;

    /**
     * Wait 5 seconds for the worker to decide what to do.
     */
    private static final int DECISION_DELAY = TICKS_SECOND * 5;

    /**
     * RS4: ticks a courier waits to decide on its next task right after it finished one (the decision delay runs out only while
     * the courier is in the deciding state, so without this every task is followed by almost the whole delay of waiting).
     */
    private static final int QUICK_DECISION_DELAY = 1;

    /**
     * A courier keeps a meal, sleep or shelter from the rain waiting for its current task for at most this long (2 minutes); after that it goes and hands the
     * task back to the request system.
     */
    public static final long FINISH_TASK_CAP = 2400L;

    /**
     * Below this saturation a courier does not wait for its task: it goes to eat and hands the task back.
     */
    public static final double CRITICAL_SATURATION = 1.0D;

    /**
     * Ticks a held-back break stays "pending": the citizen AI looks again every 10 ticks, so a break that was held back for the task that just ended is still
     * pending when the courier decides on its next task, and the courier then takes no new task but leaves for the break.
     */
    private static final long BREAK_PENDING_TICKS = 60L;

    /**
     * Wait a few ticks for the worker to decide what to pick up.
     */
    private static final int PICKUP_DELAY = 5;

    /**
     * Round 13 (L4): slots a courier takes from a hut in one pickup step (was one slot per step).
     */
    public static final int PICKUP_SLOTS_PER_STEP = 8;

    /**
     * The inventory's slot which is held in hand.
     */
    private static final int SLOT_HAND = 0;

    /**
     * Completing a request with a priority of at least PRIORITY_FORCING_DUMP will force a dump.
     */
    private static final int PRIORITY_FORCING_DUMP = 10;

    /**
     * Delivery icon
     */
    private final static VisibleCitizenStatus DELIVERING =
      new VisibleCitizenStatus(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "textures/icons/work/delivery.png"), "com.minecolonies.gui.visiblestatus.delivery");

    /**
     * Render meta backpack.
     */
    public static final String RENDER_META_BACKPACK = "backpack";

    /**
     * Amount of stacks left to gather from the inventory at the gathering step.
     */
    private int currentSlot = 0;

    /**
     * Amount of stacks the worker already kept in the current gathering process.
     */
    private List<ItemStorage> alreadyKept = new ArrayList<>();

    /**
     * Storage network: warehouses (building ids) that could not take the load during the current dump.
     */
    private final Set<BlockPos> dumpRefused = new HashSet<>();

    /**
     * The target that decides on the next task; its delay is shortened when a task has just been finished.
     */
    private final AITarget<IAIState> decisionTarget = new AITarget<>(START_WORKING, this::checkIfExecute, this::decide, DECISION_DELAY);

    /**
     * Initialize the deliveryman and add all his tasks.
     *
     * @param deliveryman the job he has.
     */
    public EntityAIWorkDeliveryman(@NotNull final JobDeliveryman deliveryman)
    {
        super(deliveryman);
        super.registerTargets(
          /*
           * Check if tasks should be executed.
           */
          new AITarget(IDLE, () -> START_WORKING, 1),
          decisionTarget,
          new AITarget(PREPARE_DELIVERY, this::prepareDelivery, STANDARD_DELAY),
          new AITarget(DELIVERY, this::deliver, STANDARD_DELAY),
          new AITarget(PICKUP, this::pickup, PICKUP_DELAY),
          new AITarget(DUMPING, this::dump, TICKS_SECOND)

        );
        worker.setCanPickUpLoot(true);
    }

    @Override
    protected void updateRenderMetaData()
    {
        worker.setRenderMetadata(worker.getInventoryCitizen().isEmpty() ? "" : RENDER_META_BACKPACK);
    }

    @Override
    public Class<BuildingDeliveryman> getExpectedBuildingClass()
    {
        return BuildingDeliveryman.class;
    }

    /**
     * A courier that holds a task does not take a leisure break: the break lasts three minutes of walking about,
     * and the courier's own queue is the only place its task is kept, so every delivery in it, and whoever waits for it, stands
     * still that long while other couriers idle. (Found by the stall trail of the benchmark: the "stuck courier" of the busy scenario
     * was a courier in {@code PREPARE_DELIVERY} that the citizen AI sent to wander during leisure time.) Meals, rain, night and
     * sickness still send it away, as for every worker.
     *
     * @return true when the citizen AI may send the courier on a leisure break.
     */
    @Override
    public boolean canTakeLeisure()
    {
        return super.canTakeLeisure() && job.getTaskQueue().isEmpty();
    }

    /**
     * The citizen AI wants to send this courier to eat, sleep or hide from the rain: should it wait for the task the courier holds? With the
     * {@code courierFinishesDelivery} switch on it waits while the courier holds a task, and sends the courier as soon as the task is done (the courier
     * takes no new one meanwhile, see {@link #decide}). It does not wait when the courier is critically hungry or has held the task longer than
     * {@link #FINISH_TASK_CAP}: then the courier goes at once and the task goes back to the request system, so another courier can take it and
     * nothing stays in the queue of a courier that is away. Sickness, raids and danger never ask: they interrupt at once.
     *
     * @return true when the break has to wait.
     */
    public boolean deferBreakForTask()
    {
        if (!RsFlags.courierFinishesDelivery() || job.getTaskQueue().isEmpty())
        {
            return false;
        }
        final long age = job.currentTaskAge();
        final double saturation = worker.getCitizenData().getSaturation();
        if (saturation <= CRITICAL_SATURATION || age > FINISH_TASK_CAP)
        {
            final int handedBack = job.giveBackTasks();
            Log.getLogger().info("Courier {} leaves its task ({}): {} task(s) back to the request system (held {} ticks, saturation {})", worker.getName(),
              saturation <= CRITICAL_SATURATION ? "critically hungry" : "held too long", handedBack, age, saturation);
            return false;
        }
        breakPendingAt = world.getGameTime();
        return true;
    }

    /**
     * Game time at which the citizen AI last held a break back for the courier's task.
     */
    private long breakPendingAt = Long.MIN_VALUE / 2;

    /**
     * Round 13 (L4): a task was queued where this courier works. A courier that waits for work in the deciding state looks at its queue within
     * {@code ticks} ticks instead of at the end of its decision delay.
     *
     * @param ticks the longest wait.
     * @return true when the courier's next decision was brought forward.
     */
    public boolean wakeSoon(final int ticks)
    {
        final IAIState state = getState();
        if (state != START_WORKING && state != IDLE)
        {
            return false;
        }
        if (decisionTarget.countdownTicksToUpdate(0) > ticks)
        {
            decisionTarget.setTicksToUpdate(ticks);
            return true;
        }
        return false;
    }

    /**
     * Back to deciding after a task was finished. With the {@code courierImmediateNextTask} switch on the courier looks for its next task at once.
     *
     * @return the deciding state.
     */
    private IAIState nextTask()
    {
        if (RsFlags.courierImmediateNextTask())
        {
            decisionTarget.setTicksToUpdate(QUICK_DECISION_DELAY);
        }
        return START_WORKING;
    }

    /**
     * Pickup items from a hut that has requested a pickup.
     *
     * @return the next state to go to.
     */
    private IAIState pickup()
    {
        setDelay(WALK_DELAY);
        final IRequest<? extends IDeliverymanRequestable> currentTask = job.getCurrentTask();

        if (!(currentTask instanceof PickupRequest))
        {
            // The current task has changed since the Decision-state. Restart.
            return START_WORKING;
        }

        if (cannotHoldMoreItems())
        {
            this.alreadyKept = new ArrayList<>();
            this.currentSlot = 0;
            return DUMPING;
        }

        worker.getCitizenData().setVisibleStatus(DELIVERING);

        final BlockPos pickupTarget = currentTask.getRequester().getLocation().getInDimensionLocation();
        final IBuilding pickupBuilding = building.getColony().getServerBuildingManager().getBuilding(pickupTarget);
        if (pickupBuilding == null)
        {
            job.finishRequest(false);
            return START_WORKING;
        }

        if (!walkToBuilding(pickupBuilding))
        {
            return PICKUP;
        }

        job.rememberPickupSource(pickupBuilding.getID());
        if (pickupFromBuilding(pickupBuilding))
        {
            this.alreadyKept = new ArrayList<>();
            this.currentSlot = 0;
            job.finishRequest(true);
            StatsUtil.trackStatByName(this.building, PICKUPS_MADE, pickupBuilding.getBuildingDisplayName(), 1);

            worker.decreaseSaturationForContinuousAction();
            worker.getCitizenExperienceHandler().addExperience(0.05D);

            if (currentTask.getRequest().getPriority() >= PRIORITY_FORCING_DUMP)
            {
                return DUMPING;
            }
            else
            {
                return nextTask();
            }
        }
        else if (InventoryUtils.openSlotCount(worker.getInventoryCitizen()) <= 0)
        {
            this.alreadyKept = new ArrayList<>();
            this.currentSlot = 0;
            return DUMPING;
        }

        setDelay(PICKUP_DELAY);
        return PICKUP;
    }

    /**
     * Gather not needed Items from building. Round 13 (L4): up to {@value #PICKUP_SLOTS_PER_STEP} slots in one step, as long as the courier can
     * carry more.
     *
     * @param targetBuilding building to gather it from.
     * @return true when finished.
     */
    private boolean pickupFromBuilding(@NotNull final IBuilding targetBuilding)
    {
        final IItemHandler handler = targetBuilding.getItemHandlerCap();
        if (handler == null)
        {
            return false;
        }

        int taken = 0;
        boolean moved = false;
        while (currentSlot < handler.getSlots())
        {
            if (cannotHoldMoreItems() || InventoryUtils.openSlotCount(worker.getInventoryCitizen()) <= 0)
            {
                break;
            }
            final ItemStack stack = handler.getStackInSlot(currentSlot);
            if (stack.isEmpty())
            {
                currentSlot++;
                continue;
            }

            final int amount = workerRequiresItem(targetBuilding, stack, alreadyKept);
            // The colony holds as much of it as the cap allows: it stays with its producer.
            if (amount > 0 && !(StorageNetwork.on(job.getColony()) && ColonyStorage.of(job.getColony()).isAtCap(new ItemStorage(stack))))
            {
                final ItemStack activeStack = handler.extractItem(currentSlot, amount, false);
                final ItemStack rest = InventoryUtils.transferItemStackIntoNextBestSlotInItemHandlerWithResult(activeStack, worker.getInventoryCitizen());
                if (!rest.isEmpty())
                {
                    // The bag took less than expected: the rest goes back where it came from.
                    handler.insertItem(currentSlot, rest, false);
                }
                moved = true;
                taken++;
            }
            currentSlot++;
            if (taken >= PICKUP_SLOTS_PER_STEP)
            {
                break;
            }
        }
        if (moved)
        {
            targetBuilding.markDirty();
            CitizenItemUtils.setHeldItem(worker, InteractionHand.MAIN_HAND, SLOT_HAND);
        }
        return currentSlot >= handler.getSlots();
    }

    /**
     * Check if the worker can hold that much items. It depends on his building level: see {@link JobDeliveryman#carryStacks} (round 13: at
     * least 4 stacks, no limit at the maximum level).
     *
     * @return whether this deliveryman can hold more items
     */
    private boolean cannotHoldMoreItems()
    {
        final int stacks = JobDeliveryman.carryStacks(building.getBuildingLevel(), building.getMaxBuildingLevel());
        if (stacks == Integer.MAX_VALUE)
        {
            return false;
        }
        return InventoryUtils.getAmountOfStacksInItemHandler(worker.getInventoryCitizen()) >= stacks;
    }

    /**
     * Check if worker of a certain building requires the item now. Or the builder for the current task.
     *
     * @param building         the building to check for.
     * @param stack            the stack to stack with.
     * @param localAlreadyKept already kept resources.
     * @return the amount which can get dumped.
     */
    public static int workerRequiresItem(final IBuilding building, final ItemStack stack, final List<ItemStorage> localAlreadyKept)
    {
        return building.buildingRequiresCertainAmountOfItem(stack, localAlreadyKept, false);
    }

    /**
     * Dump the inventory into the warehouse. With the storage network on, into the warehouse that finishes the drop first and
     * has room; whatever it could not take goes on to the next one, and what no warehouse can take stays with the courier.
     *
     * @return the next state to go to.
     */
    private IAIState dump()
    {
        final boolean network = StorageNetwork.on(job.getColony());
        final @Nullable IWareHouse warehouse = network ? job.findDumpWarehouse(dumpRefused) : getAndCheckWareHouse();
        if (warehouse == null)
        {
            dumpRefused.clear();
            return START_WORKING;
        }

        if (!walkToBuilding(warehouse))
        {
            setDelay(WALK_DELAY);
            return DUMPING;
        }

        final List<ItemStack> before = network ? snapshot(worker.getInventoryCitizen()) : List.of();
        warehouse.getTileEntity().dumpInventoryIntoWareHouse(worker.getInventoryCitizen());
        CitizenItemUtils.setHeldItem(worker, InteractionHand.MAIN_HAND, SLOT_HAND);

        if (network)
        {
            recordDump(warehouse, before);
            if (!worker.getInventoryCitizen().isEmpty())
            {
                // This warehouse could not take it all: try the next one, or give up for now when none is left.
                dumpRefused.add(warehouse.getID());
                return DUMPING;
            }
            dumpRefused.clear();
        }

        return nextTask();
    }

    /**
     * @param inventory an inventory.
     * @return copies of its stacks.
     */
    private static List<ItemStack> snapshot(final IItemHandler inventory)
    {
        final List<ItemStack> copy = new ArrayList<>();
        for (int i = 0; i < inventory.getSlots(); i++)
        {
            if (!inventory.getStackInSlot(i).isEmpty())
            {
                copy.add(inventory.getStackInSlot(i).copy());
            }
        }
        return copy;
    }

    /**
     * Tells the colony storage which items moved into the warehouse (the difference of the inventory over the dump).
     */
    private void recordDump(final IWareHouse warehouse, final List<ItemStack> before)
    {
        final Map<ItemStorage, Integer> moved = new HashMap<>();
        for (final ItemStack stack : before)
        {
            moved.merge(new ItemStorage(stack.copyWithCount(1)), stack.getCount(), Integer::sum);
        }
        final IItemHandler inventory = worker.getInventoryCitizen();
        for (int i = 0; i < inventory.getSlots(); i++)
        {
            final ItemStack left = inventory.getStackInSlot(i);
            if (!left.isEmpty())
            {
                moved.merge(new ItemStorage(left.copyWithCount(1)), -left.getCount(), Integer::sum);
            }
        }
        final ColonyStorage storage = ColonyStorage.of(job.getColony());
        for (final Map.Entry<ItemStorage, Integer> entry : moved.entrySet())
        {
            if (entry.getValue() > 0)
            {
                storage.recordFlow(job.lastPickupSource(), warehouse.getID(), entry.getKey().getItemStack().copyWithCount(entry.getValue()));
            }
        }
    }

    /**
     * Gets the colony's warehouse for the Deliveryman.
     *
     * @return the warehouse. null if no warehouse registered.
     */
    @Nullable
    private IWareHouse getAndCheckWareHouse()
    {
        return job.findWareHouse();
    }

    /**
     * Deliver the items to the hut. TODO: Current precondition: The dman's inventory may only consist of the requested itemstack.
     *
     * @return the next state.
     */
    private IAIState deliver()
    {
        final IRequest<? extends IDeliverymanRequestable> currentTask = job.getCurrentTask();

        if (!(currentTask instanceof DeliveryRequest))
        {
            // The current task has changed since the Decision-state.
            // Since prepareDelivery() was called earlier, go dumping first and then restart.
            return DUMPING;
        }

        worker.getCitizenData().setVisibleStatus(DELIVERING);

        final ILocation targetBuildingLocation = ((Delivery) currentTask.getRequest()).getTarget();
        if (!targetBuildingLocation.isReachableFromLocation(worker.getLocation()))
        {
            Log.getLogger().info(worker.getCitizenColonyHandler().getColonyOrRegister().getName() + ": " + worker.getName() + ": Can't inter dimension yet: ");
            return START_WORKING;
        }

        final IBuilding targetBuilding = worker.getCitizenColonyHandler().getColony().getServerBuildingManager().getBuilding(targetBuildingLocation.getInDimensionLocation());
        if (targetBuilding == null)
        {
            job.finishRequest(true);
            return START_WORKING;
        }

        if (!walkToBuilding(targetBuilding))
        {
            setDelay(WALK_DELAY);
            return DELIVERY;
        }

        boolean success = true;
        boolean extracted = false;
        final IItemHandler workerInventory = worker.getInventoryCitizen();
        final List<IRequest<? extends Delivery>> deliveries = job.getTaskListWithSameDestination((IRequest<? extends Delivery>) currentTask);
        final List<ItemStorage> itemsToDeliver = deliveries.stream().map(r -> new ItemStorage(r.getRequest().getStack())).collect(Collectors.toList());
        // What went into the target, per item (a short load, less in the bag than the batch asks for, is no full delivery: Sol r0 F4).
        final Map<ItemStorage, Integer> inserted = new HashMap<>();

        for (int i = 0; i < workerInventory.getSlots(); i++)
        {
            if (workerInventory.getStackInSlot(i).isEmpty())
            {
                continue;
            }

            if (!itemsToDeliver.contains(new ItemStorage(workerInventory.getStackInSlot(i))))
            {
                continue;
            }

            final ItemStack stack = workerInventory.extractItem(i, Integer.MAX_VALUE, false);
            final int count = stack.getCount();
            if (ItemStackUtils.isEmpty(stack))
            {
                continue;
            }

            extracted = true;
            final ItemStack insertionResultStack;

            // TODO: Please only push items into the target that were actually requested.
            if (targetBuilding instanceof AbstractBuilding)
            {
                insertionResultStack = InventoryUtils.forceItemStackToItemHandler(
                  targetBuilding.getItemHandlerCap(), stack, ((IBuilding) targetBuilding)::isItemStackInRequest);
            }
            else
            {
                // Buildings that are not inherently part of the request system, but just receive a delivery, cannot have their items replaced.
                // Therefore, the keep-predicate always returns true.
                insertionResultStack =
                  InventoryUtils.forceItemStackToItemHandler(targetBuilding.getItemHandlerCap(),
                    stack,
                    itemStack -> true);
            }

            if (!ItemStackUtils.isEmpty(insertionResultStack))
            {
                // A stack was replaced (meaning the inventory didn't have enough space).

                if (ItemStack.matches(insertionResultStack, stack) && worker.getCitizenData() != null)
                {
                    // The replaced stack is the same as the one we tried to put into the inventory.
                    // Meaning, replacing failed.
                    success = false;

                    if (targetBuilding.hasModule(WorkerBuildingModule.class))
                    {
                        worker.getCitizenData()
                          .triggerInteraction(new PosBasedInteraction(Component.translatableEscape(COM_MINECOLONIES_COREMOD_JOB_DELIVERYMAN_NAMEDCHESTFULL,
                            targetBuilding.getFirstModuleOccurance(WorkerBuildingModule.class).getFirstCitizen().getName()),
                            ChatPriority.IMPORTANT,
                            Component.translatableEscape(COM_MINECOLONIES_COREMOD_JOB_DELIVERYMAN_NAMEDCHESTFULL),
                            targetBuilding.getID()));
                    }
                    else
                    {
                        worker.getCitizenData()
                          .triggerInteraction(new PosBasedInteraction(Component.translatableEscape(COM_MINECOLONIES_COREMOD_JOB_DELIVERYMAN_CHESTFULL,
                            Component.literal(" :" + targetBuilding.getSchematicName())),
                            ChatPriority.IMPORTANT,
                            Component.translatableEscape(COM_MINECOLONIES_COREMOD_JOB_DELIVERYMAN_CHESTFULL),
                            targetBuildingLocation.getInDimensionLocation()));
                    }
                }

                // What came back is what the target could not take, unless it is a stack that was pushed out to make room.
                if (ItemStack.isSameItemSameComponents(insertionResultStack, stack) && insertionResultStack.getCount() <= count)
                {
                    inserted.merge(new ItemStorage(stack), count - insertionResultStack.getCount(), Integer::sum);
                }
                else
                {
                    inserted.merge(new ItemStorage(stack), count, Integer::sum);
                }

                //Insert the result back into the inventory so we do not lose it.
                workerInventory.insertItem(i, insertionResultStack, false);
            }
            else
            {
                inserted.merge(new ItemStorage(stack), count, Integer::sum);
            }
            worker.getCitizenColonyHandler()
              .getColonyOrRegister()
              .getStatisticsManager()
              .incrementBy(ITEMS_DELIVERED, count - insertionResultStack.getCount(), worker.getCitizenColonyHandler().getColonyOrRegister().getDay());
            StatsUtil.trackStatByName(building, DELIVERIES_MADE, targetBuilding.getBuildingDisplayName(), 1);
        }

        if (!extracted)
        {
            // This can only happen if the dman's inventory was completely empty.
            // Let the retry-system handle this case.
            worker.decreaseSaturationForContinuousAction();
            CitizenItemUtils.setHeldItem(worker, InteractionHand.MAIN_HAND, SLOT_HAND);
            job.finishRequest(false);

            // No need to go dumping in this case.
            return START_WORKING;
        }

        worker.getCitizenExperienceHandler().addExperience(1.5D);
        worker.decreaseSaturationForContinuousAction();
        CitizenItemUtils.setHeldItem(worker, InteractionHand.MAIN_HAND, SLOT_HAND);
        // Only the deliveries that arrived in full are resolved; the others fail and go back to the request system (the requester asks again for what it
        // still lacks). Short is what did not go in: refused by the target (it stays with the courier and is dumped into the warehouse) or never carried
        // (fewer items picked up than asked, or taken out of the courier's bag on the way). What did go in is counted as delivered.
        final Map<ItemStorage, Integer> notDelivered = shortfall(deliveries, inserted);
        final int colonyId = worker.getCitizenColonyHandler().getColonyOrRegister().getID();
        if (!success)
        {
            // the destination refused (full): couriers leave it be for a while instead of coming straight back with the same load
            final long until = RefusedTargets.refused(colonyId, targetBuilding.getPosition(), world.getGameTime());
            Log.getLogger().info("{}: {} refused the delivery (full), couriers come back after tick {}", worker.getName().getString(), targetBuilding.getPosition().toShortString(), until);
        }
        else if (!inserted.isEmpty())
        {
            RefusedTargets.accepted(colonyId, targetBuilding.getPosition());
        }
        final Set<IToken<?>> undelivered = undeliveredRequests(deliveries, notDelivered);
        final Map<ItemStorage, Integer> left = new HashMap<>(inserted);
        for (final IRequest<? extends Delivery> arrived : deliveries)
        {
            if (!undelivered.contains(arrived.getId()))
            {
                left.merge(new ItemStorage(arrived.getRequest().getStack()), -arrived.getRequest().getStack().getCount(), Integer::sum);
                RsCourierHooks.arrived(job.getColony().getRequestManager(), arrived);
            }
        }
        for (final IRequest<? extends Delivery> shortOne : deliveries)
        {
            final ItemStorage key = new ItemStorage(shortOne.getRequest().getStack());
            final int part = Math.min(left.getOrDefault(key, 0), shortOne.getRequest().getStack().getCount());
            if (undelivered.contains(shortOne.getId()) && part > 0)
            {
                left.merge(key, -part, Integer::sum);
                RsCourierHooks.arrivedPartly(job.getColony().getRequestManager(), shortOne, part);
            }
        }
        job.finishRequest(request -> !undelivered.contains(request.getId()));
        return success && notDelivered.isEmpty() ? nextTask() : DUMPING;
    }

    /**
     * How much of each item of a batch did not go into the target: what the batch asks for minus what went in.
     *
     * @param batch    the deliveries of the batch.
     * @param inserted how much of each item went into the target.
     * @return the missing count per item (only items that came up short).
     */
    static Map<ItemStorage, Integer> shortfall(final List<IRequest<? extends Delivery>> batch, final Map<ItemStorage, Integer> inserted)
    {
        final Map<ItemStorage, Integer> missing = new HashMap<>();
        for (final IRequest<? extends Delivery> delivery : batch)
        {
            missing.merge(new ItemStorage(delivery.getRequest().getStack()), delivery.getRequest().getStack().getCount(), Integer::sum);
        }
        for (final Map.Entry<ItemStorage, Integer> in : inserted.entrySet())
        {
            missing.computeIfPresent(in.getKey(), (k, v) -> v - in.getValue());
        }
        missing.values().removeIf(v -> v <= 0);
        return missing;
    }

    /**
     * Finds the deliveries of a batch that did not arrive in full.
     *
     * @param batch        the deliveries of the batch, in order.
     * @param notDelivered how much of each item did not go into the target (see {@link #shortfall}).
     * @return the tokens of the deliveries that came up short. The last deliveries of an item go short first.
     */
    static Set<IToken<?>> undeliveredRequests(final List<IRequest<? extends Delivery>> batch, final Map<ItemStorage, Integer> notDelivered)
    {
        final Set<IToken<?>> undelivered = new HashSet<>();
        for (final Map.Entry<ItemStorage, Integer> refused : notDelivered.entrySet())
        {
            int missing = refused.getValue();
            for (int i = batch.size() - 1; i >= 0 && missing > 0; i--)
            {
                final ItemStack requested = batch.get(i).getRequest().getStack();
                if (refused.getKey().equals(new ItemStorage(requested)))
                {
                    undelivered.add(batch.get(i).getId());
                    missing -= requested.getCount();
                }
            }
        }
        return undelivered;
    }

    /**
     * Prepare deliveryman for delivery. Check if the building still needs the item and if the required items are still in the warehouse.
     *
     * @return the next state to go to.
     */
    private IAIState prepareDelivery()
    {
        final IRequest<? extends IRequestable> currentTask = job.getCurrentTask();
        if (!(currentTask instanceof DeliveryRequest))
        {
            // The current task has changed since the Decision-state.
            // Restart.
            return START_WORKING;
        }

        final BlockPos refusedAt = ((Delivery) currentTask.getRequest()).getTarget().getInDimensionLocation();
        final int colonyId = worker.getCitizenColonyHandler().getColonyOrRegister().getID();
        if (!job.hasOngoingDeliveries() && RefusedTargets.paused(colonyId, refusedAt, world.getGameTime()))
        {
            // its destination refused a delivery a short while ago (full): no new trip yet with the same load. The other tasks first, or a wait
            if (!job.sendToBack(currentTask.getId()))
            {
                setDelay(TICKS_SECOND * 5);
            }
            return START_WORKING;
        }

        final List<IRequest<? extends Delivery>> taskList = job.getTaskListWithSameDestination((IRequest<? extends Delivery>) currentTask);
        final List<ItemStack> alreadyInInv = new ArrayList<>();
        IRequest<? extends Delivery> nextPickUp = null;

        int parallelDeliveryCount = 0;
        for (final IRequest<? extends Delivery> task : taskList)
        {
            parallelDeliveryCount++;
            int totalCount = InventoryUtils.getItemCountInItemHandler(worker.getInventoryCitizen(),
              itemStack -> ItemStackUtils.compareItemStacksIgnoreStackSize(task.getRequest().getStack(), itemStack));
            int hasCount = 0;
            for (final ItemStack stack : alreadyInInv)
            {
                if (ItemStackUtils.compareItemStacksIgnoreStackSize(stack, task.getRequest().getStack()))
                {
                    hasCount += stack.getCount();
                }
            }

            if (totalCount < hasCount + task.getRequest().getStack().getCount())
            {
                nextPickUp = task;
                break;
            }
            else
            {
                alreadyInInv.add(task.getRequest().getStack());
            }
        }

        if (nextPickUp == null || parallelDeliveryCount > job.getMaxParallelDeliveries())
        {
            return DELIVERY;
        }

        final ILocation location = nextPickUp.getRequest().getStart();

        if (!location.isReachableFromLocation(worker.getLocation()))
        {
            job.finishRequest(false);
            return START_WORKING;
        }

        if (!walkToSafePos(location.getInDimensionLocation()))
        {
            return PREPARE_DELIVERY;
        }

        if (getInventory().isFull())
        {
            return DUMPING;
        }

        final BlockEntity tileEntity = world.getBlockEntity(location.getInDimensionLocation());
        job.addConcurrentDelivery(nextPickUp.getId());
        if (gatherIfInTileEntity(tileEntity, nextPickUp.getRequest().getStack()))
        {
            RsCourierHooks.stockTaken(job.getColony().getRequestManager(), nextPickUp);
            return PREPARE_DELIVERY;
        }

        if (parallelDeliveryCount > 1)
        {
            job.removeConcurrentDelivery(nextPickUp.getId());
            return DELIVERY;
        }

        job.finishRequest(false);
        job.removeConcurrentDelivery(nextPickUp.getId());
        return START_WORKING;
    }

    /**
     * Finds the first @see ItemStack the type of {@code is}. It will be taken from the chest and placed in the worker inventory. Make sure that the worker stands next the chest to
     * not break immersion. Also make sure to have inventory space for the stack.
     *
     * @param entity the tileEntity chest or building or rack.
     * @param is     the itemStack.
     * @return true if found the stack.
     */
    public boolean gatherIfInTileEntity(final BlockEntity entity, final ItemStack is)
    {
        if (ItemStackUtils.isEmpty(is))
        {
            return false;
        }

        IItemHandler handler = null;
        if (entity instanceof final TileEntityColonyBuilding hut && InventoryUtils.hasBuildingEnoughElseCount(hut.getBuilding(), new ItemStorage(is), is.getCount()) >= is.getCount())
        {
            handler = hut.getItemHandlerCap();
        }
        else if (entity instanceof final TileEntityRack rack && rack.getCount(new ItemStorage(is)) >= is.getCount())
        {
            handler = rack.getItemHandlerCap();
        }
        else if (com.minecolonies.api.util.BuildingContainers.copperChest(entity) != null
                   && com.minecolonies.api.util.BuildingContainers.count(entity, new ItemStorage(is)) >= is.getCount())
        {
            // V-7: a copper chest in a building.
            handler = com.minecolonies.api.util.BuildingContainers.handler(entity);
        }

        if (handler != null)
        {
            return InventoryUtils.transferItemStackIntoNextFreeSlotFromItemHandler(handler,
              stack -> !ItemStackUtils.isEmpty(stack) && ItemStackUtils.compareItemStacksIgnoreStackSize(is, stack, true, true),
              is.getCount(),
              worker.getInventoryCitizen());
        }

        return false;
    }

    /**
     * Check the wareHouse for the next task.
     *
     * @return the next AiState to go to.
     */
    private IAIState decide()
    {
        worker.getCitizenData().setVisibleStatus(VisibleCitizenStatus.WORKING);
        if (job.getTaskQueue().isEmpty() && RsFlags.courierFinishesDelivery() && world.getGameTime() - breakPendingAt < BREAK_PENDING_TICKS)
        {
            // A meal, sleep or shelter from the rain was held back until this delivery was done: no new task, the citizen AI sends the courier within a few ticks.
            return START_WORKING;
        }
        final IRequest<? extends IDeliverymanRequestable> currentTask = job.getCurrentTask();
        if (currentTask == null)
        {
            // If there are no deliveries/pickups pending, just loiter around the warehouse.
            if (!walkToBuilding(getAndCheckWareHouse()))
            {
                setDelay(WALK_DELAY);
                return START_WORKING;
            }
            else
            {
                if (!worker.getInventoryCitizen().isEmpty())
                {
                    return DUMPING;
                }
                else
                {
                    return START_WORKING;
                }
            }
        }
        if (currentTask instanceof DeliveryRequest)
        {
            // Before a delivery can be made, the inventory first needs to be dumped.
            if (!worker.getInventoryCitizen().isEmpty())
            {
                return DUMPING;
            }
            else
            {
                return PREPARE_DELIVERY;
            }
        }
        else
        {
            return PICKUP;
        }
    }

    /**
     * Check if the deliveryman code should be executed. More concretely if he has a warehouse to work at.
     *
     * @return false if should continue as planned.
     */
    private boolean checkIfExecute()
    {
        final IWareHouse wareHouse = getAndCheckWareHouse();
        if (wareHouse != null)
        {
            worker.getCitizenData().setWorking(true);
            if (wareHouse.getTileEntity() == null)
            {
                return false;
            }
            {
                return true;
            }
        }

        worker.getCitizenData().setWorking(false);
        if (worker.getCitizenData() != null)
        {
            worker.getCitizenData()
              .triggerInteraction(new StandardInteraction(Component.translatableEscape(COM_MINECOLONIES_COREMOD_JOB_DELIVERYMAN_NOWAREHOUSE),
                ChatPriority.BLOCKING));
        }
        return false;
    }
}
