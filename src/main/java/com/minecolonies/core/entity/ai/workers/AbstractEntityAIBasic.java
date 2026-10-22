package com.minecolonies.core.entity.ai.workers;

import com.google.common.collect.ImmutableList;
import com.ldtteam.structurize.api.util.Tuple;
import com.google.common.collect.Lists;
import com.google.common.reflect.TypeToken;
import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyPaceProvider;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.interactionhandling.ChatPriority;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.colony.permissions.IPermissions;
import com.minecolonies.api.colony.requestsystem.location.ILocation;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.RequestTag;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.requestable.Tool;
import com.minecolonies.api.colony.requestsystem.resolver.IRequestResolver;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.entity.ai.JobStatus;
import com.minecolonies.api.entity.ai.statemachine.AIEventTarget;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIBlockingEventType;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.pathfinding.proxy.IWalkToProxy;
import com.minecolonies.api.equipment.ModEquipmentTypes;
import com.minecolonies.api.equipment.registry.EquipmentTypeEntry;
import com.minecolonies.api.inventory.InventoryCitizen;
import com.minecolonies.api.util.*;
import com.minecolonies.api.util.constant.TypeConstants;
import com.minecolonies.api.util.constant.translation.RequestSystemTranslationConstants;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.interactionhandling.PosBasedInteraction;
import com.minecolonies.core.colony.interactionhandling.StandardInteraction;
import com.minecolonies.core.colony.jobs.AbstractJob;
import com.minecolonies.core.entity.ai.AiCounters;
import com.minecolonies.core.entity.ai.workers.util.AbstractWalking;
import com.minecolonies.core.entity.ai.workers.util.StandSpots;
import com.minecolonies.core.entity.ai.workers.util.WorkStuckTracker;
import com.minecolonies.core.entity.ai.workers.util.WorkerSettings;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.requestsystem.resolvers.StationRequestResolver;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import com.minecolonies.core.entity.pathfinding.proxy.EntityCitizenWalkToProxy;
import com.minecolonies.core.tileentities.TileEntityRack;
import com.minecolonies.core.util.WorkerUtil;
import com.minecolonies.core.util.citizenutils.CitizenItemUtils;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import com.ldtteam.structurize.api.compat.itemhandler.IItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import static com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState.*;
import static com.minecolonies.api.util.constant.CitizenConstants.*;
import static com.minecolonies.api.util.constant.Constants.*;
import static com.minecolonies.api.util.constant.EquipmentLevelConstants.TOOL_LEVEL_WOOD_OR_GOLD;
import static com.minecolonies.api.util.constant.SchematicTagConstants.TAG_WORK;
import static com.minecolonies.api.util.constant.TranslationConstants.COM_MINECOLONIES_COREMOD_ENTITY_WORKER_INVENTORYFULLCHEST;
import static com.minecolonies.api.util.constant.TranslationConstants.WORKER_AI_EXCEPTION;
import static com.minecolonies.core.entity.ai.workers.AbstractEntityAIInteract.RENDER_META_WORKING;
import static com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils.WOKR_IN_BUILDING_DIST;

/**
 * This class provides basic ai functionality.
 *
 * @param <J> The job this ai has to fulfil
 */
public abstract class AbstractEntityAIBasic<J extends AbstractJob<?, J>, B extends AbstractBuilding> extends AbstractAISkeleton<J>
{
    /**
     * The returned slot if a slot hasn't been found.
     */
    private static final int TOOL_NOT_FOUND = -1;

    /**
     * The returned slot if a slot hasn't been found.
     */
    protected static final int NO_TOOL = -10;

    /**
     * The standard delay after each terminated action.
     */
    protected static final int STANDARD_DELAY = 5;

    /**
     * The standard delay after each terminated action.
     */
    protected static final int REQUEST_DELAY = TICKS_20 * 3;

    /**
     * Number of possible pickup attempts.
     */
    private static final int PICKUP_ATTEMPTS = 10;

    /**
     * The block the ai is currently working at or wants to work.
     */
    @Nullable
    protected BlockPos currentWorkingLocation = null;

    /**
     * The time in ticks until the next action is made.
     */
    private int delay = 0;

    /**
     * Fraction of a tick left over when the colony pace is not a whole number, added to the next wait step so the
     * total delay counted down matches tick rate times pace over time.
     */
    private double paceCarry = 0.0D;

    /**
     * G0b: ticks of the next delay already worked off while the colony pace is above 1. A step in which the AI acts
     * (does not wait) does the work of {@code tickRate * pace} ticks in {@code tickRate}, and a wait that runs out
     * part-way through its step leaves the rest unused; both are credited here and taken off the next
     * {@link #setDelay}. So the whole act-and-wait cycle runs at the pace, not only the wait. Always 0 at pace 1.0.
     */
    private int paceCredit = 0;

    /**
     * Fraction of a tick of action-step credit left over at a pace that is not a whole number.
     */
    private double actionCarry = 0.0D;

    /**
     * If we have waited one delay.
     */
    private boolean hasDelayed = false;

    /**
     * Walk to proxy.
     */
    private IWalkToProxy proxy;

    /**
     * This will count up and progressively disable the entity
     */
    private int exceptionTimer = 1;

    /**
     * Slot he is currently trying to dump.
     */
    private int slotAt = 0;

    /**
     * Indicator if something has actually been dumped.
     */
    private int dumpedItems = 0;

    /**
     * Delay for walking.
     */
    protected static final int WALK_DELAY = 20;

    /**
     * What he currently might be needing.
     */
    protected Tuple<Predicate<ItemStack>, Integer> needsCurrently = null;

    /**
     * The current position the worker should walk to.
     */
    protected BlockPos walkTo = null;

    /**
     * Already kept items during the dumping cycle.
     */
    private final List<ItemStorage> alreadyKept = new ArrayList<>();

    /**
     * Counter to count pickup attempts.
     */
    private int pickUpCounter = 0;

    /**
     * The building of the citizen.
     */
    public final B building;

    /**
     * Working pos index. Initialized with -1, not saved.
     */
    public int workPosIndex = -1;

    /**
     * Sets up some important skeleton stuff for every ai.
     *
     * @param job the job class
     */
    protected AbstractEntityAIBasic(@NotNull final J job)
    {
        super(job);
        if (!getExpectedBuildingClass().isInstance(worker.getCitizenData().getWorkBuilding()))
        {
            building = null;
            worker.getCitizenData().setJob(null);
            Log.getLogger().error("Citizen: " + worker.getCitizenData().getId() + " got the wrong job for this building. Abort", new Exception());
            return;
        }
        building = (B) worker.getCitizenData().getWorkBuilding();

        super.registerTargets(
          /*
            Init safety checks and transition to IDLE
           */
          new AIEventTarget(AIBlockingEventType.AI_BLOCKING, this::initSafetyChecks, 1),
          new AITarget(INIT, this::getState, 1),
          /*
            Update chestbelt and nametag
            Will be executed every time
            and does not stop execution
           */
          new AIEventTarget(AIBlockingEventType.AI_BLOCKING, () -> true, this::updateVisualState, 20),
          /*
            If waitingForSomething returns true
            stop execution to wait for it.
            this keeps the current state
            (returning null would not stop execution)
           */
          new AIEventTarget(AIBlockingEventType.AI_BLOCKING, this::waitingForSomething, this::getState, 5),

          /*
            Dumps inventory as long as needs be.
            If inventory is dumped, execution continues
            to resolve state.
           */
          new AIEventTarget(AIBlockingEventType.STATE_BLOCKING, this::inventoryNeedsDump, INVENTORY_FULL, 100),
          new AITarget(INVENTORY_FULL, this::dumpInventory, 20),
          /*
            Check if any items are needed.
            If yes, transition to NEEDS_ITEM.
            and wait for new items.
           */
          new AIEventTarget(AIBlockingEventType.AI_BLOCKING, () -> checkIfNeedsItem(),
            NEEDS_ITEM,
            20),

          new AIEventTarget(AIBlockingEventType.AI_BLOCKING, () -> building.hasCitizenCompletedRequests(worker.getCitizenData()) && this.cleanAsync(), NEEDS_ITEM, 200),

          new AITarget(NEEDS_ITEM, this::waitForRequests, 40),
          /*
           * Gather a needed item.
           */
          new AITarget(GATHERING_REQUIRED_MATERIALS, this::getNeededItem, TICKS_SECOND),
          /*
           * Place any non-restart regarding AITargets before this one
           * Restart AI, building etc.
           */
          new AIEventTarget(AIBlockingEventType.STATE_BLOCKING, this::shouldRestart, this::restart, TICKS_SECOND),
          /*
           * Reset if not paused.
           */
          new AITarget(PAUSED, () -> !this.isPaused(), () -> IDLE, TICKS_SECOND),
          /*
           * Do not work if worker is paused
           */
          new AITarget(PAUSED, this::bePaused, 10),
          /*
           * Start paused with inventory dump
           */
          new AIEventTarget(AIBlockingEventType.AI_BLOCKING, this::isStartingPaused, INVENTORY_FULL, TICKS_SECOND)
        );
    }

    /**
     * Check if
     * @return
     */
    protected boolean checkIfNeedsItem()
    {
        return getState() != INVENTORY_FULL &&
                 (this.building.hasOpenSyncRequest(worker.getCitizenData())
                    || this.building.hasCitizenCompletedRequestsToPickup(worker.getCitizenData()));
    }

    /**
     * Retrieve a material from the building. For this check for the chest with the required material and set the position and
     * return.
     * <p>
     * If the position has been set navigate to it. On arrival transfer to inventory and return to StartWorking.
     *
     * @return the next state to transfer to.
     */
    private IAIState getNeededItem()
    {
        if (pendingNeedsOwner != needsCurrently)
        {
            // the pending list belongs to an earlier gathering: the current need was set without it
            pendingNeeds.clear();
            pendingNeedsOwner = null;
        }
        if (needsCurrently == null)
        {
            return getStateAfterPickUp();
        }
        else
        {
            if (walkTo == null)
            {
                final BlockPos pos = building.getTileEntity().getPositionOfChestWithItemStack(needsCurrently.getA());
                if (pos == null)
                {
                    return nextNeedOrDone();
                }
                walkTo = pos;
            }

            if (!walkToWorkPos(walkTo) && pickUpCounter++ < PICKUP_ATTEMPTS)
            {
                return getState();
            }

            pickUpCounter = 0;

            final int gainedBefore = itemsGainedSinceDump();
            final boolean transferred = tryTransferFromPosToWorkerIfNeeded(walkTo, needsCurrently);
            // W8: what he took from his own hut is no gain
            keepGains(gainedBefore);
            if (!transferred)
            {
                walkTo = null;
                return getState();
            }
        }

        walkTo = null;
        return nextNeedOrDone();
    }

    /**
     * W5: the next item of the same gathering pass, or the state after the pass.
     */
    private IAIState nextNeedOrDone()
    {
        walkTo = null;
        if (!pendingNeeds.isEmpty() && pendingNeedsOwner == needsCurrently)
        {
            needsCurrently = pendingNeeds.remove(0);
            pendingNeedsOwner = needsCurrently;
            return GATHERING_REQUIRED_MATERIALS;
        }
        pendingNeeds.clear();
        pendingNeedsOwner = null;
        return getStateAfterPickUp();
    }

    /**
     * W5: the items still to take in this gathering pass after {@link #needsCurrently}.
     */
    private final List<Tuple<Predicate<ItemStack>, Integer>> pendingNeeds = new ArrayList<>();

    /**
     * W5: the need the pending list was queued with (the list is dropped when {@link #needsCurrently} is set without it).
     */
    @Nullable
    private Tuple<Predicate<ItemStack>, Integer> pendingNeedsOwner = null;

    /**
     * W5: gather several items in one pass through the hut (one {@link AIWorkerState#GATHERING_REQUIRED_MATERIALS} visit per item, no return to the
     * decision in between). Sets {@link #needsCurrently} to the first.
     *
     * @param needs the items and amounts, in order (not empty).
     * @return the gathering state.
     */
    protected final IAIState gatherAll(final List<Tuple<Predicate<ItemStack>, Integer>> needs)
    {
        pendingNeeds.clear();
        needsCurrently = needs.get(0);
        pendingNeedsOwner = needsCurrently;
        for (int i = 1; i < needs.size(); i++)
        {
            pendingNeeds.add(needs.get(i));
        }
        walkTo = null;
        return GATHERING_REQUIRED_MATERIALS;
    }

    /**
     * W5: items still queued in the current gathering pass (tests and debug).
     *
     * @return the count.
     */
    public int pendingNeedCount()
    {
        return pendingNeedsOwner == needsCurrently ? pendingNeeds.size() : 0;
    }

    /**
     * The state to transform to after picking up things.
     *
     * @return the next state to go to.
     */
    public IAIState getStateAfterPickUp()
    {
        return START_WORKING;
    }

    /**
     * Can be overridden in implementations to return the exact building type the worker expects.
     *
     * @return the building type associated with this AI's worker.
     */
    public abstract Class<B> getExpectedBuildingClass();

    /**
     * Can be overridden in implementations to return the exact building type.
     *
     * @param type the type.
     * @return the building associated with this AI's worker.
     */
    @NotNull
    @SuppressWarnings("unchecked")
    private B getOwnBuilding(@NotNull final Class<B> type)
    {
        if (type.isInstance(worker.getCitizenColonyHandler().getWorkBuilding()))
        {
            return (B) worker.getCitizenColonyHandler().getWorkBuilding();
        }
        throw new IllegalStateException("Citizen " + worker.getCitizenData().getName() + " has lost its building unexpectedly, type does not match");
    }

    @Override
    protected void onException(final RuntimeException e)
    {
        worker.getCitizenData().triggerInteraction(new StandardInteraction(Component.translatableEscape(WORKER_AI_EXCEPTION), ChatPriority.BLOCKING));

        try
        {
            // B3: the back-off starts again after a quiet spell (counted from the end of the last pause), and stops growing at a cap
            final long now = world == null ? 0L : world.getGameTime();
            final ExceptionStep step = exceptionStep(exceptionTimer, pauseEndTick, now, FMLEnvironment.isProduction());
            final int timeout = step.pause();
            this.setDelay(timeout);
            setCurrentDelay(timeout);
            pauseEndTick = step.pauseEnd();
            // wait for longer next time
            exceptionTimer = step.next();
            if (worker != null)
            {
                final String name = this.worker.getName().getString();
                final BlockPos workerPosition = worker.blockPosition();
                final IJob<?> colonyJob = worker.getCitizenJobHandler().getColonyJob();
                final String jobName = colonyJob == null ? "null" : colonyJob.getJobRegistryEntry().getTranslationKey();
                Log.getLogger()
                  .error("Pausing Citizen " + name + " (" + jobName + ") in colony:" + worker.getCitizenData().getColony().getID() + " at " + workerPosition + " for " + timeout
                           + " ticks because of error:");
            }
            else
            {
                Log.getLogger().error("Pausing Entity that is null for " + timeout + " ticks because of error:");
            }

            // fix for printing the actual exception
            e.printStackTrace();
            Log.getLogger().warn("AI history for citizen error:{}", getStateAI().getHistory().getString());
        }
        catch (final RuntimeException exp)
        {
            Log.getLogger().error("Welp reporting crashed:");
            exp.printStackTrace();
            Log.getLogger().error("Caused by ai exception:");
            e.printStackTrace();
        }
    }

    /**
     * B3: the largest multiplier of {@code EXCEPTION_TIMEOUT} (64 x 100 ticks, about 5 minutes).
     */
    public static final int EXCEPTION_BACKOFF_CAP = 64;

    /**
     * B3: ticks without an error after which the back-off starts again at 1 (5 minutes).
     */
    public static final long EXCEPTION_QUIET_RESET = 6000L;

    /**
     * B3: game time at which the last error pause of this AI ends, -1 before the first error.
     */
    private long pauseEndTick = -1L;

    /**
     * B3: one error of a worker AI.
     *
     * @param used     the multiplier used for this pause.
     * @param pause    the pause in ticks.
     * @param pauseEnd the game time the pause ends.
     * @param next     the multiplier kept for the next error.
     */
    public record ExceptionStep(int used, int pause, long pauseEnd, int next)
    {
    }

    /**
     * B3: the pause for an error at {@code now}. The quiet time is measured from the end of the last pause, so an AI that fails again right
     * after each pause holds at {@link #EXCEPTION_BACKOFF_CAP} (measured from the last error, the 6400 tick pause at the cap counted as quiet).
     *
     * @param current      the current multiplier.
     * @param pauseEndTick the end of the last pause, -1 before the first error.
     * @param now          the game time of this error.
     * @param production   whether the pause grows (outside production it stays {@code EXCEPTION_TIMEOUT}).
     * @return the step.
     */
    public static ExceptionStep exceptionStep(final int current, final long pauseEndTick, final long now, final boolean production)
    {
        final int used = exceptionBackoff(current, pauseEndTick < 0 ? Long.MAX_VALUE : Math.max(0L, now - pauseEndTick));
        final int pause = production ? EXCEPTION_TIMEOUT * used : EXCEPTION_TIMEOUT;
        return new ExceptionStep(used, pause, now + pause, Math.min(used * 2, EXCEPTION_BACKOFF_CAP));
    }

    /**
     * B3: the back-off multiplier for an error now: 1 again after {@link #EXCEPTION_QUIET_RESET} ticks without an error, else the current one
     * (at most {@link #EXCEPTION_BACKOFF_CAP}). The caller doubles it for the next error, up to the cap.
     *
     * @param current          the current multiplier.
     * @param ticksSinceLast   ticks since the end of the last pause ({@link Long#MAX_VALUE} for the first error).
     * @return the multiplier to use now.
     */
    public static int exceptionBackoff(final int current, final long ticksSinceLast)
    {
        if (ticksSinceLast >= EXCEPTION_QUIET_RESET || current < 1)
        {
            return 1;
        }
        return Math.min(current, EXCEPTION_BACKOFF_CAP);
    }

    /**
     * Set a delay in ticks.
     *
     * @param timeout the delay to wait after this tick.
     */
    public final void setDelay(final int timeout)
    {
        this.delay = timeout;
        if (paceCredit > 0)
        {
            if (getWorkPace() == 1.0D)
            {
                // Back at 1x: nothing carries over.
                paceCredit = 0;
                actionCarry = 0.0D;
                return;
            }
            delay -= paceCredit;
            paceCredit = 0;
            if (delay < 0)
            {
                // More credit than this delay: the rest is kept for the next one.
                paceCredit = -delay;
                delay = 0;
            }
        }
    }

    /**
     * Check if we need to dump the worker inventory.
     * <p>
     * This will also ask the implementing ai if we need to dump on custom reasons. {see wantInventoryDumped}
     *
     * @return true if we need to dump the inventory.
     */
    protected boolean inventoryNeedsDump()
    {
        return getState() != INVENTORY_FULL && canBeInterrupted() &&
                 (worker.getCitizenInventoryHandler().isInventoryFull()
                    || job.getActionsDone() >= getActionsDoneUntilDumping()
                    || wantInventoryDumped())
                 && !(job instanceof JobDeliveryman);
    }

    /**
     * Calculates after how many actions the ai should dump it's inventory.
     * <p>
     * Override this to change the value.
     *
     * @return the number of actions done before item dump.
     */
    protected int getActionsDoneUntilDumping()
    {
        return ACTIONS_UNTIL_DUMP;
    }

    /**
     * Has to be overridden by classes to specify when to dump inventory. Always dump on inventory full.
     *
     * @return true if inventory needs to be dumped now
     */
    protected boolean wantInventoryDumped()
    {
        return false;
    }

    /**
     * Check for null on important variables to prevent crashes.
     *
     * @return IDLE if all ready, else stay in INIT
     */
    @Nullable
    private IAIState initSafetyChecks()
    {
        if (null == job || worker.getCitizenData() == null || building == null)
        {
            return INIT;
        }

        if (worker.getCitizenData().getJob() != job || building != worker.getCitizenData().getWorkBuilding())
        {
            worker.getCitizenData().setJob(null);
            return INIT;
        }

        if (getState() == INIT)
        {
            return IDLE;
        }

        return null;
    }

    /**
     * Updates the visual state of the worker. Updates render meta data. Updates the current state on the nametag.
     *
     * @return null to execute more targets.
     */
    private IAIState updateVisualState()
    {
        //Update the current state the worker is in.
        job.setNameTag(this.getState().toString());
        //Update torch, seeds etc. in chestbelt etc.
        updateRenderMetaData();
        return null;
    }

    /**
     * Can be overridden in implementations.
     * <p>
     * Here the AI can check if the chestBelt has to be re rendered and do it.
     */
    protected void updateRenderMetaData()
    {
        worker.setRenderMetadata(getState() == IDLE ? "" : RENDER_META_WORKING);
    }

    /**
     * This method will return true if the AI is waiting for something. In that case, don't execute any more AI code, until it returns false. Call this exactly once per tick to get
     * the delay right. The worker will move and animate correctly while he waits.
     *
     * @return true if we have to wait for something
     */
    private boolean waitingForSomething()
    {
        if (delay > 0 && shouldWakeEarly())
        {
            // W3: new work came in during an idle wait
            clearWorkTarget();
            onWokeEarly();
        }
        if (delay > 0)
        {
            if (currentWorkingLocation != null && EntityUtils.isLivingAtSite(worker,
              currentWorkingLocation.getX(),
              currentWorkingLocation.getY(),
              currentWorkingLocation.getZ(),
              DEFAULT_RANGE_FOR_DELAY))
            {
                CitizenItemUtils.hitBlockWithToolInHand(worker, currentWorkingLocation);
            }
            delay -= getPacedTickRate();
            if (delay <= 0)
            {
                // G0b: the part of this step the wait did not need is credited to the next delay (only when paced).
                final int unused = -delay;
                clearWorkTarget();
                if (unused > 0 && getWorkPace() != 1.0D)
                {
                    paceCredit = Math.min(paceCredit + unused, maxPaceCredit());
                }
            }
            return true;
        }
        creditActionStep();
        return false;
    }

    /**
     * W3: whether a running delay ends now because there is work (checked every AI step while the delay runs). Cheap checks only.
     *
     * @return true to stop waiting.
     */
    protected boolean shouldWakeEarly()
    {
        return false;
    }

    /**
     * W3: called when {@link #shouldWakeEarly()} ended a delay.
     */
    protected void onWokeEarly()
    {
    }

    /**
     * G0b: this step the AI acts instead of waiting. At pace {@code p} it counts as {@code tickRate * p} ticks of work
     * done in {@code tickRate}, so {@code tickRate * (p - 1)} is credited to the next delay (with the fraction carried).
     * At pace 1.0 nothing is credited and nothing changes.
     */
    private void creditActionStep()
    {
        final double pace = getWorkPace();
        if (pace == 1.0D)
        {
            paceCredit = 0;
            actionCarry = 0.0D;
            return;
        }
        if (pace < 1.0D)
        {
            return;
        }
        final double credit = getTickRate() * (pace - 1.0D) + actionCarry;
        final int whole = (int) credit;
        actionCarry = credit - whole;
        paceCredit = Math.min(paceCredit + whole, maxPaceCredit());
    }

    /**
     * G0b: at most what one cycle can earn is banked (the unused part of a paced wait step, {@code tickRate * pace}, plus
     * one action step's {@code tickRate * (pace - 1)}), so an idle AI (acting without delays for a while) cannot save up
     * a long stretch of free work.
     */
    private int maxPaceCredit()
    {
        return (int) Math.ceil(getTickRate() * (2.0D * getWorkPace() - 1.0D));
    }

    /**
     * The pace of this worker's waits: the colony pace of another mod ({@link IColonyPaceProvider#work}, sanitized) times the worker speed
     * of the settings ({@link WorkerSettings#speedMultiplier()}). Exactly 1.0 with the speed setting at 1.0, without a colony or provider.
     * <p>
     * This is the one place where both are combined: there is only one {@code IColonyPaceProvider}, so the setting is folded in here instead
     * of being a second provider, and Colony Command's pace and the setting multiply (pace 2 and setting 1.5 make 3).
     */
    protected double getWorkPace()
    {
        final IColony colony = job.getColony();
        final double provider = colony == null ? 1.0D : IColonyPaceProvider.sanitize(IMinecoloniesAPI.getInstance().getColonyPaceProvider().work(colony, job));
        return WorkerSettings.composePace(provider, WorkerSettings.speedMultiplier());
    }

    /**
     * How much of the delay one wait step counts down: the tick rate times the work pace ({@link #getWorkPace()}), with the fraction carried
     * to the next step. At pace 1.0 this is exactly the tick rate.
     *
     * @return the ticks to subtract from the delay.
     */
    private int getPacedTickRate()
    {
        final double pace = getWorkPace();
        if (pace == 1.0D)
        {
            paceCarry = 0.0D;
            return getTickRate();
        }
        final double step = getTickRate() * pace + paceCarry;
        final int whole = (int) step;
        paceCarry = step - whole;
        return whole;
    }

    /**
     * Remove the current working block and it's delay.
     */
    private void clearWorkTarget()
    {
        this.currentWorkingLocation = null;
        this.delay = 0;
    }

    /**
     * If the worker has open requests their results will be queried until they all are completed Also waits for DELAY_RECHECK.
     *
     * @return NEEDS_ITEM
     */
    @NotNull
    protected IAIState waitForRequests()
    {
        delay = DELAY_RECHECK;
        return lookForRequests();
    }

    /**
     * Utility method to search for items currently needed. Poll this until all items are there.
     *
     * @return the next state to go to.
     */
    @NotNull
    private IAIState lookForRequests()
    {
        if (!this.building.hasOpenSyncRequest(worker.getCitizenData())
              && !building.hasCitizenCompletedRequests(worker.getCitizenData()))
        {
            return afterRequestPickUp();
        }
        if (building.hasCitizenCompletedRequests(worker.getCitizenData()))
        {
            final Collection<IRequest<?>> completedRequests = building.getCompletedRequestsOfCitizenOrBuilding(worker.getCitizenData());
            final List<IRequest<?>> deliverableRequests = new ArrayList<>();
            for (final IRequest<?> req : completedRequests)
            {
                if (!req.canBeDelivered())
                {
                    building.markRequestAsAccepted(worker.getCitizenData(), req.getId());
                }
                else
                {
                    deliverableRequests.add(req);
                }
            }

            if (!deliverableRequests.isEmpty())
            {
                final IRequest<?> firstDeliverableRequest = deliverableRequests.get(0);
                final ILocation pickupLocation = pickupLocationOf(firstDeliverableRequest);

                if (!walkToWorkPos(pickupLocation.getInDimensionLocation()) || !WorldUtil.isBlockLoaded(world, pickupLocation.getInDimensionLocation()))
                {
                    return NEEDS_ITEM;
                }
                final BlockEntity blockEntity = world.getBlockEntity(pickupLocation.getInDimensionLocation());
                if (blockEntity == null)
                {
                    return NEEDS_ITEM;
                }

                // W6: every completed request waiting at this place is taken in this one visit (it was one per 40 ticks)
                final int gainedBefore = itemsGainedSinceDump();
                final List<IRequest<?>> here = new ArrayList<>();
                for (final IRequest<?> request : deliverableRequests)
                {
                    // another place (a station): the next visit
                    if (request == firstDeliverableRequest || pickupLocation.equals(pickupLocationOf(request)))
                    {
                        here.add(request);
                    }
                }
                final int taken = takeCompletedRequestsHere(blockEntity, here);
                final AiCounters.JobCounters jobCounters = counters();
                if (jobCounters != null && taken > 0)
                {
                    jobCounters.pickup(taken);
                }
                lastPickupRequests = taken;
                // W8: deliveries are no gain
                keepGains(gainedBefore);
                return NEEDS_ITEM;
            }
        }
        else
        {
            walkToBuilding();
        }

        return NEEDS_ITEM;
    }

    /**
     * W6: takes completed requests of one place in order while the worker has room. A request is accepted only when all its items are with
     * him; the first one that does not fit and the rest stay completed for the next visit (after a dump). Public for tests.
     *
     * @param blockEntity the block entity at the place (the worker stands there).
     * @param requests    the completed requests of that place.
     * @return the requests taken.
     */
    public int takeCompletedRequestsHere(final BlockEntity blockEntity, final List<IRequest<?>> requests)
    {
        int taken = 0;
        final List<ItemStack> takenDeliveries = new ArrayList<>();
        for (final IRequest<?> request : requests)
        {
            if (taken > 0 && worker.getCitizenInventoryHandler().isInventoryFull())
            {
                // no room for more: the rest waits for the next visit
                break;
            }
            final TakeResult result = takeCompletedRequest(request, blockEntity, takenDeliveries);
            if (result == TakeResult.TAKEN)
            {
                taken++;
            }
            else if (result == TakeResult.NO_ROOM)
            {
                break;
            }
        }
        return taken;
    }

    /**
     * W6: requests taken by the last hut visit that took completed requests (tests).
     */
    private int lastPickupRequests = 0;

    /**
     * @return the completed requests taken by the last pickup visit (W6: all of one place at once).
     */
    public int getLastPickupRequests()
    {
        return lastPickupRequests;
    }

    /**
     * The place to take a completed request from: the station of a station resolver, else the own building.
     */
    private ILocation pickupLocationOf(final IRequest<?> request)
    {
        IRequestResolver<?> resolver = null;
        try
        {
            resolver = building.getColony().getRequestManager().getResolverForRequest(request.getId());
        }
        catch (final Exception ex)
        {
            Log.getLogger().warn("Resolver died for finished request. Oopsy. " + worker.getCitizenData().getName() + " witnessed it.", ex);
        }
        return resolver instanceof StationRequestResolver ? resolver.getLocation() : building.getLocation();
    }

    /**
     * W6: the outcome of taking one completed request.
     */
    private enum TakeResult
    {
        /** The items are with the worker and the request is accepted. */
        TAKEN,
        /** The items are still (partly) at the place: the request stays completed for the next visit. */
        NO_ROOM,
        /** The items were gone: the request was made again. */
        REMADE
    }

    /**
     * Takes the items of one completed request from the block entity at its place (the worker stands there). W6: the request is accepted
     * only once all its items are with the worker, and the items of requests taken before in this visit are never swapped out for these.
     *
     * @param request         the request.
     * @param blockEntity     the block entity at the pickup place.
     * @param takenDeliveries the deliveries taken before in this visit; this request's are added when it is taken.
     * @return the outcome.
     */
    private TakeResult takeCompletedRequest(final IRequest<?> request, final BlockEntity blockEntity, final List<ItemStack> takenDeliveries)
    {
        final boolean async = worker.getCitizenData().isRequestAsync(request.getId());

        final IItemHandlerCapProvider wrappedBlockEntity = IItemHandlerCapProvider.wrap(blockEntity);
        final List<IItemHandler> validHandlers = Lists.newArrayList();
        validHandlers.add(worker.getItemHandlerCitizen());
        validHandlers.addAll(InventoryUtils.getItemHandlersFromProvider(wrappedBlockEntity));

        //Check if we either have the requested Items in our inventory or if they are in the building.
        if (InventoryUtils.areAllItemsInItemHandlerList(request.getDeliveries(), validHandlers))
        {
            final List<ItemStorage> niceToHave = itemsNiceToHave();
            final List<ItemStack> contained = InventoryUtils.getContainedFromItemHandler(request.getDeliveries(), worker.getItemHandlerCitizen());

            InventoryUtils.moveItemStacksWithPossibleSwap(
              worker.getItemHandlerCitizen(),
              InventoryUtils.getItemHandlersFromProvider(wrappedBlockEntity),
              request.getDeliveries(),
              itemStack ->
                contained.stream().anyMatch(stack -> ItemStackUtils.compareItemStacksIgnoreStackSize(itemStack, stack)) ||
                  takenDeliveries.stream().anyMatch(stack -> ItemStackUtils.compareItemStacksIgnoreStackSize(itemStack, stack)) ||
                  niceToHave.stream().anyMatch(storage -> ItemStackUtils.compareItemStacksIgnoreStackSize(itemStack, storage.getItemStack()))
            );
            // (counted together with the deliveries taken before in this visit, so their items do not stand in for these)
            final List<ItemStack> carried = new ArrayList<>(takenDeliveries);
            carried.addAll(request.getDeliveries());
            if (!InventoryUtils.areAllItemsInItemHandlerList(carried, List.of(worker.getItemHandlerCitizen())))
            {
                // W6: not everything moved (no room): not accepted, the request stays completed for the next visit
                return TakeResult.NO_ROOM;
            }
            if (async)
            {
                job.getAsyncRequests().remove(request.getId());
            }
            building.markRequestAsAccepted(worker.getCitizenData(), request.getId());
            takenDeliveries.addAll(request.getDeliveries());
            return TakeResult.TAKEN;
        }

        if (async)
        {
            job.getAsyncRequests().remove(request.getId());
        }
        building.markRequestAsAccepted(worker.getCitizenData(), request.getId());

        //Seems like somebody else picked up our stack.
        //Lets try this again.
        if (async)
        {
            worker.getCitizenData().createRequestAsync(request.getRequest());
        }
        else
        {
            worker.getCitizenData().createRequest(request.getRequest());
        }
        return TakeResult.REMADE;
    }

    /**
     * Get the primary skill of this worker.
     *
     * @return the primary skill.
     */
    protected int getPrimarySkillLevel()
    {
        return worker.getCitizenData().getCitizenSkillHandler().getLevel(getModuleForJob().getPrimarySkill());
    }

    /**
     * Get the secondary skill of this worker.
     *
     * @return the secondary skill.
     */
    protected int getSecondarySkillLevel()
    {
        return worker.getCitizenData().getCitizenSkillHandler().getLevel(getModuleForJob().getSecondarySkill());
    }

    /**
     * Utility method to get a given module for this AI.
     *
     * @return the right module.
     */
    protected WorkerBuildingModule getModuleForJob()
    {
        return (WorkerBuildingModule) job.getWorkModule();
    }

    /**
     * Apply a early bonus curve to a skill level
     * note: This adjusts the range from 0-99 to be 1-100
     *
     * @param rawSkillLevel to apply the curve to
     * @return effective skill level to use in linear bonus functions
     */
    protected static int getEffectiveSkillLevel(int rawSkillLevel)
    {
        return (int) (((rawSkillLevel + 1) * 2) - Math.pow((rawSkillLevel + 1) / 10.0, 2));
    }

    /**
     * Returns the inverted skill level, to use in chance based calcs
     *
     * @param rawSkillLevel
     * @return
     */
    protected static int getInvertedEffectiveSkillLevel(final int rawSkillLevel)
    {
        return Math.max(1, 100 - getEffectiveSkillLevel(rawSkillLevel));
    }

    /**
     * Cleans up async requests
     *
     * @return false
     */
    private boolean cleanAsync()
    {
        final Collection<IRequest<?>> completedRequests = building.getCompletedRequestsOfCitizenOrBuilding(worker.getCitizenData());

        for (IRequest<?> request : completedRequests)
        {
            if (worker.getCitizenData().isRequestAsync(request.getId()))
            {
                building.markRequestAsAccepted(worker.getCitizenData(), request.getId());
            }
        }

        return false;
    }

    /**
     * Checks whether automatic pickups after dumps are allowed. Usually we want this, but if the worker is currently crafting/building, he will handle deliveries/afterdumps in
     * their resolvers, so we can disable automatic pickups in that time. Note that this is just a efficiency-thing. It doesn't hurt when the dman does a pickup during crafting,
     * it's just a wasted run. Therefore, this flag is only considered for *automatic* pickups after dump. It is *ignored* for player-triggered forcePickups, and when the inventory
     * is full.
     *
     * @return true if after-dump pickups are allowed currently.
     */
    public boolean isAfterDumpPickupAllowed()
    {
        return true;
    }

    /**
     * What to do after picking up a request.
     *
     * @return the next state to go to.
     */
    public IAIState afterRequestPickUp()
    {
        return IDLE;
    }

    /**
     * Get the total amount required for an itemStack. Workers have to override this if they have more information.
     *
     * @param deliveredItemStack the required stack.
     * @return on default the size of the stack.
     */
    public int getTotalRequiredAmount(final ItemStack deliveredItemStack)
    {
        return deliveredItemStack.getCount();
    }

    /**
     * Walk the worker to it's work building.
     *
     * @return true on arrival
     */
    protected final boolean walkToBuilding()
    {
        @Nullable final IBuilding ownBuilding = building;
        if (ownBuilding == null)
        {
            return true;
        }

        final boolean arrived = checkedWalkToBuilding(ownBuilding);
        if (arrived)
        {
            walkingToHut = false;
        }
        else if (!walkingToHut)
        {
            // round 13: one more walk to the own hut (AiCounters)
            walkingToHut = true;
            final AiCounters.JobCounters jobCounters = counters();
            if (jobCounters != null)
            {
                jobCounters.hutWalk();
            }
        }
        return arrived;
    }

    /**
     * Round 13: whether a walk to the own hut is under way (counted once per walk in {@link AiCounters}).
     */
    private boolean walkingToHut = false;

    /**
     * The walk to a building with a way out for a worker who cannot get there (fallen into a hole, shut in): when he stands still for too
     * long on his way to the building he asks for a new path, and then is put on a standable spot at the building's entrance. A worker who
     * keeps moving is not touched, so long walks stay what they are.
     */
    private boolean checkedWalkToBuilding(final IBuilding target)
    {
        final boolean arrived = EntityNavigationUtils.walkToBuilding(worker, target);
        if (arrived)
        {
            walkStuck.progress();
            walkStuckTick = -1L;
            return true;
        }
        if (AbstractWalking.isRelocating(worker))
        {
            walkStuck.free();
            walkStuckTick = -1L;
            return false;
        }

        final long now = world.getGameTime();
        final int dt = walkStuckTick < 0 ? 0 : (int) Math.min(now - walkStuckTick, 200L);
        walkStuckTick = now;
        final Vec3 position = worker.position();
        final double moved = walkStuckPos == null ? 0.0D : position.distanceTo(walkStuckPos);
        walkStuckPos = position;
        switch (walkStuck.blocked(dt, moved))
        {
            case REPATH:
                worker.getNavigation().stop();
                break;
            case TELEPORT:
            case DEFER:
                final BlockPos spot = StandSpots.nearest(world, target.getPosition(), 6, 6, 4, null);
                if (spot != null)
                {
                    worker.getNavigation().stop();
                    worker.snapTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D);
                    walkStuck.progress();
                    walkStuckTick = -1L;
                    Log.getLogger().debug("{} could not get to {} and was put at {}", worker.getName().getString(), target.getPosition(), spot);
                }
                break;
            default:
                break;
        }
        return false;
    }

    private final WorkStuckTracker walkStuck = new WorkStuckTracker();
    private long walkStuckTick = -1L;
    private Vec3 walkStuckPos  = null;

    /**
     * Walk the worker to a tagged workpos in building, or the building pos if non existent.
     *
     * @return true while walking
     */
    protected final boolean walkToTaggedWorkPos()
    {
        if (building == null)
        {
            return walkToBuilding();
        }

        final List<BlockPos> workTags = building.getLocationsFromTag(TAG_WORK);
        if (workTags.isEmpty())
        {
            return walkToBuilding();
        }

        if (workPosIndex == -1 || (MathUtils.RANDOM.nextInt(30) <= 0 && worker.getNavigation().isDone()))
        {
            workPosIndex = MathUtils.RANDOM.nextInt(workTags.size());
        }

        return EntityNavigationUtils.walkToPosInBuilding(worker, workTags.get(workPosIndex), building, WOKR_IN_BUILDING_DIST);
    }


    /**
     * Walk the worker to the given building.
     *
     * @return true while walking
     */
    protected final boolean walkToWorkPos(final BlockPos pos)
    {
        if (pos == null)
        {
            return true;
        }

        return EntityNavigationUtils.walkToPosInBuilding(worker, pos, building, WOKR_IN_BUILDING_DIST);
    }

    /**
     * Walk the worker to the given building.
     *
     * @return true while walking
     */
    protected final boolean walkToBuilding(final IBuilding building)
    {
        if (building == null)
        {
            return true;
        }

        return checkedWalkToBuilding(building);
    }

    /**
     * Walk the worker to the safe position
     *
     * @return false while walking
     */
    protected final boolean walkToSafePos(final BlockPos pos)
    {
        return EntityNavigationUtils.walkToPos(worker, pos, 4, true);
    }

    /**
     * Walk the worker to the unsafe position
     *
     * @return false while walking
     */
    protected final boolean walkToUnSafePos(final BlockPos pos)
    {
        return EntityNavigationUtils.walkToPos(worker, pos, 4, false);
    }

    /**
     * Walk the worker to the unsafe position
     *
     * @return false while walking
     */
    protected final boolean walkToUnSafePos(final BlockPos pos, final int distance)
    {
        return EntityNavigationUtils.walkToPos(worker, pos, distance, false);
    }

    /**
     * Check all racks in the workers hut for a required item.
     * Transfer to worker if found.
     *
     * @param is the type of item requested (amount is ignored)
     * @return true if a stack of that type was found and transferred.
     */
    public boolean checkAndTransferFromHut(@Nullable final ItemStack is)
    {
        for (final BlockPos pos : building.getContainers())
        {
            final BlockEntity entity = world.getBlockEntity(pos);
            if (entity instanceof final TileEntityRack rack && rack.hasItemStack(is, 1, false))
            {
                InventoryUtils.transferItemStackIntoNextBestSlotInItemHandler(rack.getItemHandlerCap(),
                    (stack) -> ItemStackUtils.compareItemStacksIgnoreStackSize(is, stack),
                    getInventory());
                return true;
            }
        }

        return false;
    }

    /**
     * Sets the block the AI is currently walking to.
     *
     * @param stand where to walk to
     * @return true while walking to the block
     */
    protected final boolean walkWithProxy(@NotNull final BlockPos stand)
    {
        return walkWithProxy(stand, DEFAULT_RANGE_FOR_DELAY);
    }

    /**
     * Sets the block the AI is currently walking to.
     *
     * @param stand where to walk to
     * @param range how close we need to be
     * @return true while walking to the block
     */
    protected final boolean walkWithProxy(@NotNull final BlockPos stand, final int range)
    {
        if (proxy == null)
        {
            proxy = new EntityCitizenWalkToProxy(worker);
        }
        if (!proxy.walkToBlock(stand, range))
        {
            workOnBlock(null, DELAY_RECHECK);
            return true;
        }

        worker.getNavigation().stop();
        return false;
    }

    /**
     * Sets the block the AI is currently working on. This block will receive animation hits on delay.
     *
     * @param target  the block that will be hit
     * @param timeout the time in ticks to hit the block
     */
    private void workOnBlock(@Nullable final BlockPos target, final int timeout)
    {
        this.currentWorkingLocation = target;
    }

    /**
     * Finds the first @see ItemStack the type of {@code is}. It will be taken from the chest and placed in the worker inventory. Make sure that the worker stands next the chest to
     * not break immersion. Also make sure to have inventory space for the stack.
     *
     * @param entity   the tileEntity chest or building.
     * @param toolType the type of tool.
     * @param minLevel the min tool level.
     * @param maxLevel the max tool level.
     * @return true if found the tool.
     */
    public boolean retrieveToolInTileEntity(final BlockEntity entity, final EquipmentTypeEntry toolType, final int minLevel, final int maxLevel)
    {
        if (ModEquipmentTypes.none.get().equals(toolType))
        {
            return false;
        }
        return InventoryFunctions.matchFirstInProviderWithAction(
          IItemHandlerCapProvider.wrap(entity),
          stack -> ItemStackUtils.hasEquipmentLevel(stack, toolType, minLevel, maxLevel),
          this::takeItemStackFromProvider
        );
    }

    /**
     * Takes whatever is in that slot of the worker chest and puts it in his inventory. If the inventory is full, only the fitting part will be moved. Beware this method shouldn't
     * be private, because the generic access won't work within a lambda won't work else.
     *
     * @param provider  The provider to take from.
     * @param slotIndex The slot to take.
     */
    public void takeItemStackFromProvider(@NotNull final IItemHandlerCapProvider provider, final int slotIndex)
    {
        InventoryUtils.transferItemStackIntoNextBestSlotFromProvider(provider, slotIndex, worker.getInventoryCitizen());
    }

    /**
     * Ensures that we have a appropriate tool available. Will set {@code needsTool} accordingly.
     *
     * @param toolType type of tool we check for.
     * @return false if we have the tool
     */
    public boolean checkForToolOrWeapon(@NotNull final EquipmentTypeEntry toolType)
    {
        final boolean needTool = checkForToolOrWeapon(toolType, TOOL_LEVEL_WOOD_OR_GOLD);
        if (needTool)
        {
            worker.getCitizenData().setJobStatus(JobStatus.STUCK);
        }
        else
        {
            worker.getCitizenData().setJobStatus(JobStatus.WORKING);
        }
        return needTool;
    }

    protected boolean checkForToolOrWeapon(@NotNull final EquipmentTypeEntry toolType, final int minimalLevel)
    {
        final ImmutableList<IRequest<? extends Tool>> openToolRequests =
          building.getOpenRequestsOfTypeFiltered(
            worker.getCitizenData(),
            TypeToken.of(Tool.class),
            r -> r.getRequest().getEquipmentType().equals(toolType) && r.getRequest().getMinLevel() >= minimalLevel);
        final ImmutableList<IRequest<? extends Tool>> completedToolRequests =
          building.getCompletedRequestsOfTypeFiltered(
            worker.getCitizenData(),
            TypeToken.of(Tool.class),
            r -> r.getRequest().getEquipmentType().equals(toolType) && r.getRequest().getMinLevel() >= minimalLevel);

        if (checkForNeededTool(toolType, minimalLevel))
        {
            if (openToolRequests.isEmpty() && completedToolRequests.isEmpty())
            {
                final Tool request = new Tool(toolType, minimalLevel, building.getMaxEquipmentLevel() < minimalLevel ? minimalLevel : building.getMaxEquipmentLevel());
                worker.getCitizenData().createRequest(request);
            }
            delay = 0;
            return true;
        }

        return false;
    }

    /**
     * Ensures that we have a appropriate tool available. ASync call on the tool.
     *
     * @param toolType     Tool type that is requested
     * @param minLevel min. level of the tool
     * @param maxLevel min. level of the tool
     */
    protected void checkForToolOrWeaponAsync(@NotNull final EquipmentTypeEntry toolType, final int minLevel, final int maxLevel)
    {
        final ImmutableList<IRequest<? extends Tool>> openToolRequests =
          building.getOpenRequestsOfTypeFiltered(
            worker.getCitizenData(),
            TypeToken.of(Tool.class),
            r -> r.getRequest().getEquipmentType().equals(toolType));

        final ImmutableList<IRequest<? extends Tool>> completedToolRequests =
          building.getCompletedRequestsOfTypeFiltered(
            worker.getCitizenData(),
            TypeToken.of(Tool.class),
            r -> r.getRequest().getEquipmentType().equals(toolType) && r.getRequest().getMinLevel() >= minLevel);

        final List<IRequest<? extends Tool>> actualOpen = new ArrayList<>();
        for (final IRequest<? extends Tool> req : openToolRequests)
        {
            if (req.getRequest().getMinLevel() < minLevel || req.getRequest().getMaxLevel() < maxLevel)
            {
                worker.getCitizenColonyHandler().getColonyOrRegister().getRequestManager().updateRequestState(req.getId(), RequestState.CANCELLED);
            }
            else
            {
                actualOpen.add(req);
            }
        }

        if (actualOpen.isEmpty() && completedToolRequests.isEmpty())
        {
            final Tool request = new Tool(toolType, minLevel, maxLevel);
            worker.getCitizenData().createRequestAsync(request);
        }
    }

    /**
     * Cancel all requests for a certain armor type for a certain citizen.
     *
     * @param armorType the armor type.
     */
    protected void cancelAsynchRequestForArmor(final EquipmentTypeEntry armorType)
    {
        final List<IRequest<? extends Tool>> openRequests =
          building.getOpenRequestsOfTypeFiltered(worker.getCitizenData(), TypeConstants.TOOL, iRequest -> iRequest.getRequest().getEquipmentType() == armorType);
        for (final IRequest<?> token : openRequests)
        {
            worker.getCitizenColonyHandler().getColonyOrRegister().getRequestManager().updateRequestState(token.getId(), RequestState.CANCELLED);
        }
    }

    /**
     * Check if there is an open request for a certain tooltype.
     *
     * @param key the tooltype.
     * @return true if so.
     */
    private boolean hasOpenToolRequest(final EquipmentTypeEntry key)
    {
        return building.hasWorkerOpenRequestsFiltered(worker.getCitizenData().getId(),
          iRequest -> iRequest.getRequest() instanceof Tool && ((Tool) iRequest.getRequest()).getEquipmentType() == key);
    }

    /**
     * Check if we need a tool.
     * <p>
     * Do not use it to find a pickaxe as it need a minimum level.
     *
     * @param toolType     tool required for block.
     * @param minimalLevel the minimal level.
     * @return true if we need a tool.
     */
    private boolean checkForNeededTool(@NotNull final EquipmentTypeEntry toolType, final int minimalLevel)
    {
        final int maxToolLevel = worker.getCitizenColonyHandler().getWorkBuilding().getMaxEquipmentLevel();
        final InventoryCitizen inventory = worker.getInventoryCitizen();
        if (InventoryUtils.isEquipmentInItemHandler(inventory, toolType, minimalLevel, maxToolLevel))
        {
            return false;
        }

        delay += DELAY_RECHECK;
        return !walkToBuilding() || !retrieveToolInHut(toolType, minimalLevel);
    }

    /**
     * Check all chests in the workers hut for a required tool.
     *
     * @param toolType     the type of tool requested (amount is ignored)
     * @param minimalLevel the minimal level the tool should have.
     * @return true if a stack of that type was found
     */
    public boolean retrieveToolInHut(final EquipmentTypeEntry toolType, final int minimalLevel)
    {
        if (building != null)
        {
            final Predicate<ItemStack> toolPredicate = stack -> ItemStackUtils.hasEquipmentLevel(stack, toolType, minimalLevel, building.getMaxEquipmentLevel());
            for (final BlockPos pos : building.getContainers())
            {
                final BlockEntity entity = world.getBlockEntity(pos);
                if (entity instanceof final TileEntityRack rack)
                {
                    if (ModEquipmentTypes.none.get().equals(toolType))
                    {
                        return false;
                    }

                    if (rack.hasItemStack(toolPredicate))
                    {
                        if (InventoryUtils.transferItemStackIntoNextBestSlotInItemHandler(rack.getItemHandlerCap(),
                          toolPredicate,
                          worker.getInventoryCitizen()))
                        {
                            return true;
                        }
                    }
                }
                else if (entity instanceof ChestBlockEntity)
                {
                    if (retrieveToolInTileEntity(building.getTileEntity(), toolType, minimalLevel, building.getMaxEquipmentLevel()))
                    {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Get the building to dump the inventory into.
     *
     * @return its own building by default.
     */
    protected IBuilding getBuildingToDump()
    {
        return building;
    }

    /**
     * Walk to building and dump inventory. If inventory is dumped, continue execution so that the state can be resolved.
     *
     * @return INVENTORY_FULL | IDLE
     */
    @NotNull
    private IAIState dumpInventory()
    {
        final IBuilding building = getBuildingToDump();
        if (building == null)
        {
            // Uh oh, that shouldn't happen. Restart AI.
            return afterDump();
        }

        boolean full = InventoryUtils.isBuildingFull(building);
        if (!full)
        {
            final DumpStep step = dumpAllSlots();
            if (step == DumpStep.WALKING)
            {
                return INVENTORY_FULL;
            }
            // the building filled up during this dump: the rest stays with the worker (nothing is dropped)
            full = step == DumpStep.BUILDING_FULL;
        }
        if (full)
        {
            final ICitizenData citizenData = worker.getCitizenData();
            if (citizenData != null)
            {
                citizenData
                  .triggerInteraction(new StandardInteraction(Component.translatableEscape(COM_MINECOLONIES_COREMOD_ENTITY_WORKER_INVENTORYFULLCHEST),
                    ChatPriority.IMPORTANT));
            }

            // In this case, pickup during crafting is ok, since cleaning a full inventory is very important.
            // Note that this will not create a pickup request when another request is already in progress.
            if (building.getPickUpPriority() > 0)
            {
                building.createPickupRequest(dumpedItems, true);
                dumpedItems = 0;
            }
            alreadyKept.clear();
            slotAt = 0;
            this.clearActionsDone();
            dumpFinished();
            return afterDump();
        }

        alreadyKept.clear();
        slotAt = 0;
        this.clearActionsDone();
        dumpFinished();

        // Worker is not currently crafting, pickup is allowed.
        // Note that this will not create a pickup request when another request is already in progress.
        if (isAfterDumpPickupAllowed() && building.getPickUpPriority() > 0 && dumpedItems > 0)
        {
            building.createPickupRequest(dumpedItems, false);
            dumpedItems = 0;
        }
        return afterDump();
    }

    /**
     * State to go to after dumping.
     *
     * @return the next state.
     */
    public IAIState afterDump()
    {
        if (isPaused())
        {
            // perform a cleanUp before going to PAUSED
            this.building.onCleanUp(worker.getCitizenData());
            return PAUSED;
        }
        return IDLE;
    }

    /**
     * The result of one dump step.
     */
    private enum DumpStep
    {
        /** Still on the way to the building. */
        WALKING,
        /** Every slot was looked at. */
        DONE,
        /** The building could not take a stack: the rest stays with the worker. */
        BUILDING_FULL
    }

    /**
     * W1: dump steps after arrival in the current dump (one when the whole inventory went in one call); read by tests.
     */
    private int dumpCallsThisDump = 0;

    /**
     * W1: stacks moved into the building in the current dump.
     */
    private int dumpedStacksThisDump = 0;

    /**
     * W1: dump steps after arrival of the last finished dump (tests: 1 when all slots went in one call).
     */
    private int lastDumpCalls = 0;

    /**
     * W8: the item count of the inventory after the last finished dump (what the worker kept), see {@link #itemsGainedSinceDump()}.
     */
    private int itemsAfterLastDump = -1;

    /**
     * @return the dump steps after arrival of the last finished dump (1 when every slot went in one call).
     */
    public int getLastDumpCalls()
    {
        return lastDumpCalls;
    }

    /**
     * A dump finished: counters and the baseline for {@link #itemsGainedSinceDump()}.
     */
    private void dumpFinished()
    {
        lastDumpCalls = dumpCallsThisDump;
        final AiCounters.JobCounters jobCounters = counters();
        if (jobCounters != null)
        {
            jobCounters.dump(dumpedStacksThisDump);
        }
        dumpCallsThisDump = 0;
        dumpedStacksThisDump = 0;
        itemsAfterLastDump = carriedItemCount();
    }

    /**
     * @return the number of items the worker carries.
     */
    protected final int carriedItemCount()
    {
        int count = 0;
        final InventoryCitizen inventory = worker.getInventoryCitizen();
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            count += inventory.getStackInSlot(slot).getCount();
        }
        return count;
    }

    /**
     * W8: items picked up or made since the last dump (the items kept at the dump do not count, so keeping seeds or saplings never starts
     * another dump).
     *
     * @return the items gained.
     */
    protected final int itemsGainedSinceDump()
    {
        final int carried = carriedItemCount();
        if (itemsAfterLastDump < 0)
        {
            // no dump yet since the AI started: what he carried then is his baseline
            itemsAfterLastDump = carried;
        }
        return Math.max(0, carried - itemsAfterLastDump);
    }

    /**
     * W8: after taking items from a building: the gain since the last dump stays what it was before.
     *
     * @param gainedBefore the gain before.
     */
    private void keepGains(final int gainedBefore)
    {
        itemsAfterLastDump = Math.max(0, carriedItemCount() - gainedBefore);
    }

    /**
     * W8: items a gatherer (fisher, planter, farmer, herder) collects before he takes them to his hut (he also goes when his inventory is
     * full). It was 5 to 10 actions.
     */
    public static final int GATHERER_DUMP_ITEMS = 64;

    /**
     * W8: actions after which a gatherer dumps even without {@link #GATHERER_DUMP_ITEMS} items (a safety for actions that yield nothing).
     */
    public static final int GATHERER_DUMP_ACTIONS = 256;

    /**
     * W8: whether a gatherer has collected enough to take it to his hut.
     *
     * @param gained items gained since the last dump.
     * @return true if so.
     */
    public static boolean gathererShouldDump(final int gained)
    {
        return gained >= GATHERER_DUMP_ITEMS;
    }

    /**
     * W8: {@link #gathererShouldDump} for this worker.
     *
     * @return true if the gatherer should take his items to the hut now.
     */
    protected final boolean gatheredEnoughToDump()
    {
        return gathererShouldDump(itemsGainedSinceDump());
    }

    /**
     * W4: the share of used slots above which a worker who still has work dumps anyway.
     */
    public static final double DUMP_FILL_RATIO = 0.7D;

    /**
     * W4: whether used slots are more than {@link #DUMP_FILL_RATIO} of all slots.
     *
     * @param usedSlots  used slots.
     * @param totalSlots all slots.
     * @return true if over the ratio.
     */
    public static boolean overDumpRatio(final int usedSlots, final int totalSlots)
    {
        return totalSlots <= 0 || usedSlots > totalSlots * DUMP_FILL_RATIO;
    }

    /**
     * W4: whether the worker's inventory is more than 70% used.
     *
     * @return true if so.
     */
    protected final boolean inventoryOverDumpRatio()
    {
        final InventoryCitizen inventory = worker.getInventoryCitizen();
        final int total = inventory.getSlots();
        return overDumpRatio(total - (int) InventoryUtils.openSlotCount(inventory), total);
    }

    /**
     * W1: dumps every slot into the building in one call once the worker is there (it was one slot per 20 ticks). B7: only slots inside the
     * inventory are read. A stack the building cannot take stays in its slot.
     *
     * @return the step result.
     */
    @SuppressWarnings("PMD.PrematureDeclaration")
    private DumpStep dumpAllSlots()
    {
        final IBuilding target = getBuildingToDump();
        if (!walkToWorkPos(target.getPosition()))
        {
            return DumpStep.WALKING;
        }
        dumpCallsThisDump++;

        @Nullable final IBuilding buildingWorker = building;
        final InventoryCitizen inventory = worker.getInventoryCitizen();
        final int totalSize = inventory.getSlots();
        for (; slotAt >= 0 && slotAt < totalSize; slotAt++)
        {
            final ItemStack stackToDump = inventory.getStackInSlot(slotAt);
            if (stackToDump.isEmpty())
            {
                continue;
            }

            boolean dumpAnyway = false;
            if (slotAt + MIN_OPEN_SLOTS * 2 >= totalSize)
            {
                final long openSlots = InventoryUtils.openSlotCount(inventory);
                if (openSlots < MIN_OPEN_SLOTS * 2)
                {
                    if (stackToDump.getCount() < CHANCE_TO_DUMP_50)
                    {
                        dumpAnyway = worker.getRandom().nextBoolean();
                    }
                    else
                    {
                        dumpAnyway = worker.getRandom().nextInt(stackToDump.getCount()) < CHANCE_TO_DUMP;
                    }
                }
            }

            if (buildingWorker == null)
            {
                continue;
            }
            final int amount = dumpAnyway ? stackToDump.getCount() : buildingWorker.buildingRequiresCertainAmountOfItem(stackToDump, alreadyKept, true, job.getJobRegistryEntry());
            if (amount <= 0)
            {
                continue;
            }
            final ItemStack activeStack = getInventory().extractItem(slotAt, amount, false);
            final ItemStack rest = InventoryUtils.transferItemStackIntoNextBestSlotInItemHandlerWithResult(activeStack, target.getItemHandlerCap());
            final int moved = activeStack.getCount() - rest.getCount();
            if (!rest.isEmpty())
            {
                // the building is full: put the rest back (it used to be dropped from existence)
                final ItemStack left = getInventory().insertItem(slotAt, rest, false);
                if (!left.isEmpty())
                {
                    InventoryUtils.addItemStackToItemHandler(getInventory(), left);
                }
            }

            if (moved > 0)
            {
                if (getInventory().getHeldItemSlot(InteractionHand.MAIN_HAND) == slotAt && getInventory().getStackInSlot(slotAt).isEmpty())
                {
                    getInventory().setHeldItem(InteractionHand.MAIN_HAND, -1);
                    worker.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                }
                if (getInventory().getHeldItemSlot(InteractionHand.OFF_HAND) == slotAt && getInventory().getStackInSlot(slotAt).isEmpty())
                {
                    getInventory().setHeldItem(InteractionHand.OFF_HAND, -1);
                    worker.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                }
                dumpedItems += moved;
                dumpedStacksThisDump++;
            }
            if (!rest.isEmpty())
            {
                return DumpStep.BUILDING_FULL;
            }
        }
        return DumpStep.DONE;
    }

    /**
     * Can be overridden by implementations to specify items useful for the worker. When the worker inventory is full, he will try to keep these items. ItemStack amounts are
     * ignored, the first stack found will be taken.
     *
     * @return a list with items nice to have for the worker
     */
    @NotNull
    protected List<ItemStorage> itemsNiceToHave()
    {
        return new ArrayList<>();
    }

    /**
     * Clear the actions done counter. Call this when dumping into the chest.
     */
    private void clearActionsDone()
    {
        job.clearActionsDone();
    }

    /**
     * Calculate the citizens inventory.
     *
     * @return A InventoryCitizen matching this ai's citizen.
     */
    @NotNull
    protected InventoryCitizen getInventory()
    {
        return worker.getInventoryCitizen();
    }

    /**
     * Check and ensure that we hold the most efficient tool for the job.
     * <p>
     * If we have no tool for the job, we will request on, return immediately.
     *
     * @param target the BlockState to mine
     * @param pos    the pos to mine
     * @return true if we have a tool for the job
     */
    public boolean holdEfficientTool(@NotNull final BlockState target, final BlockPos pos)
    {
        final int bestSlot = getMostEfficientTool(target, pos);
        if (bestSlot >= 0)
        {
            worker.getCitizenData().setJobStatus(JobStatus.WORKING);
            CitizenItemUtils.setHeldItem(worker, InteractionHand.MAIN_HAND, bestSlot);
            return true;
        }
        else if (bestSlot == NO_TOOL)
        {
            worker.getCitizenData().setJobStatus(JobStatus.WORKING);
            CitizenItemUtils.removeHeldItem(worker);
            return true;
        }
        requestTool(target, pos);
        return false;
    }

    /**
     * Request the appropriate tool for this block.
     *
     * @param target the blockstate to mine
     * @param pos    the pos to mine
     */
    private void requestTool(@NotNull final BlockState target, final BlockPos pos)
    {
        final EquipmentTypeEntry toolType = WorkerUtil.getBestToolForBlock(target, target.getDestroySpeed(world, pos), building, world, pos);
        final int required = WorkerUtil.getCorrectHarvestLevelForBlock(target);
        if (building.getMaxEquipmentLevel() < required && worker.getCitizenData() != null)
        {
            worker.getCitizenData().triggerInteraction(new PosBasedInteraction(
              Component.translatableEscape(RequestSystemTranslationConstants.REQUEST_SYSTEM_BUILDING_LEVEL_TOO_LOW,
                new ItemStack(target.getBlock()).getHoverName(),
                pos.getX(),
                pos.getY(),
                pos.getZ()),
              ChatPriority.IMPORTANT,
              Component.translatableEscape(RequestSystemTranslationConstants.REQUEST_SYSTEM_BUILDING_LEVEL_TOO_LOW),
              pos));
        }
        updateToolFlag(toolType, required);
    }

    /**
     * Checks if said tool of said level is usable. if not, it updates the needsTool flag for said tool.
     *
     * @param toolType the tool needed
     * @param required the level needed (for pickaxe only)
     */
    private void updateToolFlag(@NotNull final EquipmentTypeEntry toolType, final int required)
    {
        if (ModEquipmentTypes.pickaxe.get().equals(toolType))
        {
            checkForToolOrWeapon(toolType, required);
        }
        else
        {
            checkForToolOrWeapon(toolType);
        }
    }

    /**
     * Calculates the most efficient tool to use on that block.
     *
     * @param target the BlockState to mine
     * @param pos    the pos it is at.
     * @return the slot with the best tool
     */
    protected int getMostEfficientTool(@NotNull final BlockState target, final BlockPos pos)
    {
        final EquipmentTypeEntry toolType = WorkerUtil.getBestToolForBlock(target, target.getDestroySpeed(world, pos), building, world, pos);
        final int required = WorkerUtil.getCorrectHarvestLevelForBlock(target);

        if (toolType == ModEquipmentTypes.none.get())
        {
            return NO_TOOL;
        }

        int bestSlot = TOOL_NOT_FOUND;
        int bestLevel = Integer.MAX_VALUE;
        @NotNull final InventoryCitizen inventory = worker.getInventoryCitizen();
        final int maxToolLevel = worker.getCitizenColonyHandler().getWorkBuilding().getMaxEquipmentLevel();

        for (int i = 0; i < worker.getInventoryCitizen().getSlots(); i++)
        {
            final ItemStack item = inventory.getStackInSlot(i);
            final int level = toolType.getMiningLevel(item);

            if (level > -1 && level >= required && level < bestLevel && ItemStackUtils.verifyEquipmentLevel(item, level, required, maxToolLevel))
            {
                bestSlot = i;
                bestLevel = level;
            }
        }

        return bestSlot;
    }

    /**
     * Will delay one time and pass through the second time. Use for convenience instead of SetDelay
     *
     * @param time the time to wait
     * @return true if you should wait
     */
    protected final boolean hasNotDelayed(final int time)
    {
        if (!hasDelayed)
        {
            setDelay(time);
            hasDelayed = true;
            return true;
        }
        hasDelayed = false;
        return false;
    }

    /**
     * Tell the ai that you have done one more action.
     * <p>
     * if the actions exceed a certain number, the ai will dump it's inventory. this also triggers the AI to get hungry.
     * <p>
     * For example:
     * <p>
     * After x blocks, bring everything back.
     */
    public final void incrementActionsDoneAndDecSaturation()
    {
        worker.decreaseSaturationForAction();
        incrementActionsDone();
    }

    /**
     * Tell the ai that you have done one more action.
     * <p>
     * if the actions exceed a certain number, the ai will dump it's inventory.
     * <p>
     * For example:
     * <p>
     * After x blocks, bring everything back.
     *
     * @see #incrementActionsDone(int)
     */
    public final void incrementActionsDone()
    {
        job.incrementActionsDone();
    }

    /**
     * Reset the done actions of the AI.
     *
     * @see #incrementActionsDone(int)
     */
    protected final void resetActionsDone()
    {
        job.clearActionsDone();
    }

    /**
     * Tell the ai that you have done numberOfActions more actions.
     * <p>
     * if the actions exceed a certain number, the ai will dump it's inventory.
     * <p>
     * For example:
     * <p>
     * After x blocks, bring everything back.
     *
     * @param numberOfActions number of actions to be added at once.
     * @see #incrementActionsDone()
     */
    protected final void incrementActionsDone(final int numberOfActions)
    {
        job.incrementActionsDone(numberOfActions);
    }

    /**
     * Calculates the working position.
     * <p>
     * Takes the position where the worker would like to work on and return the most appropriate position for it.
     * <p>
     *
     * @param targetPosition the position to work at.
     * @return BlockPos most appropiate position to work from.
     */
    public BlockPos getWorkingPosition(final BlockPos targetPosition)
    {
        return targetPosition;
    }

    /**
     * Calculates the working position.
     * <p>
     * Takes a min distance from width and length.
     * <p>
     * Then finds the floor level at that distance and then check if it does contain two air levels.
     *
     * @param distance  the extra distance to apply away from the building.
     * @param targetPos the target position which needs to be worked.
     * @param offset    an additional offset
     * @return BlockPos position to work from.
     */
    public BlockPos getWorkingPosition(final int distance, final BlockPos targetPos, final int offset)
    {
        // TODO: Use pathfinding for this instead! Get rid of all those getWork position stuff
        final BlockPos workPos = BlockPosUtil.findSpawnPosAround(world, targetPos);
        return workPos == null ? targetPos : workPos;
    }

    /**
     * Gets a floorPosition in a particular direction.
     *
     * @param facing    the direction.
     * @param distance  the distance.
     * @param targetPos the position to work at.
     * @return a BlockPos position.
     */
    @NotNull
    private BlockPos getPositionInDirection(final Direction facing, final int distance, final BlockPos targetPos)
    {
        return BlockPosUtil.getFloor(targetPos.relative(facing, distance), world);
    }

    /**
     * Requests a list of itemstacks.
     *
     * @param stacks the stacks.
     * @return true if they're in the inventory.
     */
    public boolean checkIfRequestForItemExistOrCreate(@NotNull final ItemStack... stacks)
    {
        return checkIfRequestForItemExistOrCreate(Lists.newArrayList(stacks));
    }

    /**
     * Check if any of the stacks is in the inventory.
     *
     * @param stacks the list of stacks.
     * @return true if so.
     */
    public boolean checkIfRequestForItemExistOrCreate(@NotNull final Collection<ItemStack> stacks)
    {
        return stacks.stream().allMatch(this::checkIfRequestForItemExistOrCreate);
    }

    /**
     * Check if a stack has been requested already or is in the inventory. If not in the inventory and not requested already, create request
     *
     * @param stack the requested stack.
     * @return true if in the inventory, else false.
     */
    public boolean checkIfRequestForItemExistOrCreate(@NotNull final ItemStack stack)
    {
        final int amount = stack.getCount();
        return checkIfRequestForItemExistOrCreate(stack.copyWithCount(1), amount, amount);
    }

    /**
     * Check if a stack has been requested already or is in the inventory. If not in the inventory and not requested already, create request
     *
     * @param stack    the requested stack.
     * @param count    count to request.
     * @param minCount the min count to fulfill.
     * @return true if in the inventory, else false.
     */
    public boolean checkIfRequestForItemExistOrCreate(@NotNull final ItemStack stack, final int count, final int minCount)
    {
        return checkIfRequestForItemExistOrCreate(stack, count, minCount, true, false);
    }

    /**
     * Requests a list of itemstacks.
     *
     * @param stacks the stacks.
     * @return true if they're in the inventory.
     */
    public boolean checkIfRequestForItemExistOrCreateAsync(@NotNull final ItemStack... stacks)
    {
        return checkIfRequestForItemExistOrCreateAsync(Lists.newArrayList(stacks));
    }

    /**
     * Check if any of the stacks is in the inventory.
     *
     * @param stacks the list of stacks.
     * @return true if so.
     */
    public boolean checkIfRequestForItemExistOrCreateAsync(@NotNull final Collection<ItemStack> stacks)
    {
        return stacks.stream().allMatch(this::checkIfRequestForItemExistOrCreateAsync);
    }

    /**
     * Check if a stack has been requested already or is in the inventory. If not in the inventory and not requested already, create request
     *
     * @param stack the requested stack.
     * @return true if in the inventory, else false.
     */
    public boolean checkIfRequestForItemExistOrCreateAsync(@NotNull final ItemStack stack)
    {
        final int amount = stack.getCount();
        return checkIfRequestForItemExistOrCreateAsync(stack.copyWithCount(1), amount, amount);
    }

    /**
     * Check if a stack has been requested already or is in the inventory. If not in the inventory and not requested already, create request
     *
     * @param stack    the requested stack.
     * @param count    the count.
     * @param minCount the min count.
     * @return true if in the inventory, else false.
     */
    public boolean checkIfRequestForItemExistOrCreateAsync(@NotNull final ItemStack stack, final int count, final int minCount)
    {
        return checkIfRequestForItemExistOrCreateAsync(stack, count, minCount, true);
    }

    /**
     * Check if a stack has been requested already or is in the inventory. If not in the inventory and not requested already, create request
     *
     * @param stack    the requested stack.
     * @param count    the total count.
     * @param minCount the minimum count.
     * @param matchNBT if nbt has to be matched.
     * @return true if in the inventory, else false.
     */
    public boolean checkIfRequestForItemExistOrCreateAsync(@NotNull final ItemStack stack, final int count, final int minCount, final boolean matchNBT)
    {
        return checkIfRequestForItemExistOrCreate(stack, count, minCount, matchNBT, true);
    }

    /**
     * Check if a stack has been requested already or is in the inventory. If not in the inventory and not requested already, create request
     *
     * @param stack    the requested stack.
     * @param count    the total count.
     * @param minCount the minimum count.
     * @param matchNBT if nbt has to be matched.
     * @param async    if should be an async request.
     * @return true if in the inventory, else false.
     */
    public boolean checkIfRequestForItemExistOrCreate(@NotNull final ItemStack stack, final int count, final int minCount, final boolean matchNBT, final boolean async)
    {
        if (stack.isEmpty())
        {
            return true;
        }

        final int invCount = InventoryUtils.getItemCountInItemHandler(worker.getInventoryCitizen(), s -> ItemStackUtils.compareItemStacksIgnoreStackSize(s, stack));
        if (invCount >= minCount)
        {
            return true;
        }

        final int updatedCount = count - invCount;
        final int updatedMinCount = Math.min(updatedCount, minCount);

        // Check if in building and if we could transfer at least "mincount" over.
        if (InventoryUtils.hasBuildingEnoughElseCount(building, new ItemStorage(stack, true, matchNBT), updatedMinCount) >= updatedMinCount &&
              updatedCount - InventoryUtils.transferXOfFirstSlotInProviderWithIntoNextFreeSlotInItemHandlerWithResult(
                building, itemStack -> ItemStackUtils.compareItemStacksIgnoreStackSize(itemStack, stack, true, matchNBT),
                updatedCount,
                worker.getInventoryCitizen()) >= minCount)
        {
            return true;
        }

        if (building.getOpenRequestsOfTypeFiltered(worker.getCitizenData(), TypeConstants.DELIVERABLE,
          (IRequest<? extends IDeliverable> r) -> r.getRequest().matches(stack)).isEmpty()
              && building.getCompletedRequestsOfTypeFiltered(worker.getCitizenData(), TypeConstants.DELIVERABLE,
          (IRequest<? extends IDeliverable> r) -> r.getRequest().matches(stack)).isEmpty())
        {
            final Stack stackRequest = new Stack(stack, updatedCount, updatedMinCount, matchNBT);
            if (async)
            {
                worker.getCitizenData().createRequestAsync(stackRequest);
            }
            else
            {
                worker.getCitizenData().createRequest(stackRequest);
            }
        }

        return false;
    }

    /**
     * Check if a request exists for a given deliverable.
     *
     * @param deliverable the deliverable to check of request.
     * @return true if available or transfered.
     */
    public boolean checkIfRequestForItemExistOrCreate(@NotNull final IDeliverable deliverable)
    {
        final int invCount = InventoryUtils.getItemCountInItemHandler(worker.getInventoryCitizen(), deliverable::matches);
        if (invCount >= deliverable.getCount())
        {
            return true;
        }
        final int updatedCount = deliverable.getCount() - invCount;
        final int updatedMinCount = Math.min(updatedCount, deliverable.getMinimumCount());

        if (InventoryUtils.hasBuildingEnoughElseCount(building,
          deliverable::matches, updatedMinCount) >= updatedMinCount &&
              InventoryUtils.transferXOfFirstSlotInProviderWithIntoNextFreeSlotInItemHandler(
                building, deliverable::matches,
                updatedCount,
                worker.getInventoryCitizen()))
        {
            return true;
        }

        if (building.getOpenRequestsOfTypeFiltered(worker.getCitizenData(), TypeConstants.DELIVERABLE,
          (IRequest<? extends IDeliverable> r) -> r.getRequest().getClass().equals(deliverable.getClass())).isEmpty()
              && building.getCompletedRequestsOfTypeFiltered(worker.getCitizenData(), TypeConstants.DELIVERABLE,
          (IRequest<? extends IDeliverable> r) -> r.getRequest().getClass().equals(deliverable.getClass())).isEmpty())
        {
            worker.getCitizenData().createRequestAsync(deliverable);
        }

        return false;
    }

    /**
     * Check if a tag has been requested already or is in the inventory. If not in the inventory and not requested already, create request
     *
     * @param tag the requested tag.
     * @return true if in the inventory, else false.
     */
    public boolean checkIfRequestForTagExistOrCreateAsync(@NotNull final TagKey<Item> tag, final int count)
    {
        if (InventoryUtils.hasItemInItemHandler(worker.getInventoryCitizen(), stack -> stack.is(tag) && stack.getCount() >= count))
        {
            return true;
        }

        if (InventoryUtils.hasBuildingEnoughElseCount(building,
          itemStack -> itemStack.is(tag), count) >= count &&
              InventoryUtils.transferXOfFirstSlotInProviderWithIntoNextFreeSlotInItemHandler(
                building, itemStack -> itemStack.is(tag),
                count,
                worker.getInventoryCitizen()))
        {
            return true;
        }

        if (building.getOpenRequestsOfTypeFiltered(worker.getCitizenData(), TypeConstants.TAG_REQUEST,
          (IRequest<? extends RequestTag> r) -> r.getRequest().getTag().equals(tag)).isEmpty()
              && building.getCompletedRequestsOfTypeFiltered(worker.getCitizenData(), TypeConstants.TAG_REQUEST,
          (IRequest<? extends RequestTag> r) -> r.getRequest().getTag().equals(tag)).isEmpty())
        {
            final IDeliverable tagRequest = new RequestTag(tag, count);
            worker.getCitizenData().createRequestAsync(tagRequest);
        }

        return false;
    }

    /**
     * Try to transfer a item matching a predicate from a position to the cook.
     *
     * @param pos       the position to transfer it from.
     * @param predicate the predicate to evaluate.
     * @return true if cancelling state.
     */

    private boolean tryTransferFromPosToWorkerIfNeeded(final BlockPos pos, @NotNull final Tuple<Predicate<ItemStack>, Integer> predicate)
    {
        final BlockEntity entity = world.getBlockEntity(pos);
        if (entity == null)
        {
            return true;
        }

        int existingAmount = InventoryUtils.getItemCountInItemHandler(worker.getInventoryCitizen(), predicate.getA());
        int amount;
        if (predicate.getB() > existingAmount)
        {
            amount = predicate.getB() - existingAmount;
        }
        else
        {
            return true; // has already needed transfers...
        }

        InventoryUtils.transferXOfFirstSlotInProviderWithIntoNextFreeSlotInItemHandlerWithResult(IItemHandlerCapProvider.wrap(entity), predicate.getA(), amount, worker.getInventoryCitizen());
        existingAmount = InventoryUtils.getItemCountInItemHandler(worker.getInventoryCitizen(), predicate.getA());
        // has already needed transfers...
        return existingAmount >= predicate.getB();
    }

    /**
     * Is worker paused?
     *
     * @return true if paused
     */
    private boolean isPaused()
    {
        return worker.getCitizenData().isPaused();
    }

    /**
     * Is worker starting paused
     *
     * @return true if starting paused
     */
    private boolean isStartingPaused()
    {
        return isPaused() && getState() != PAUSED && getState() != INVENTORY_FULL;
    }

    /**
     * Paused activity
     *
     * @return next state
     */
    private IAIState bePaused()
    {
        if (!worker.getNavigation().isDone())
        {
            return null;
        }

        // Pick random activity.
        final int percent = worker.getRandom().nextInt(ONE_HUNDRED_PERCENT);
        if (percent < VISIT_BUILDING_CHANCE)
        {
            walkToBuilding();
        }
        else if (percent < WANDER_CHANCE)
        {
            EntityNavigationUtils.walkToRandomPos(worker, 10, DEFAULT_SPEED);
        }

        return null;
    }

    /**
     * Is worker paused but not walking.
     *
     * @return true if restart is scheduled
     */
    private boolean shouldRestart()
    {
        return worker.getCitizenData().shouldRestart() && this.isPaused();
    }

    /**
     * Restart AI, building etc.
     *
     * @return <code>State.INIT</code>
     */
    private IAIState restart()
    {
        this.building.onCleanUp(worker.getCitizenData());
        this.building.onRestart(worker.getCitizenData());
        setDelay(WALK_DELAY);
        worker.getCitizenData().restartDone();
        return INIT;
    }

    /**
     * The current exception timer
     *
     * @return the timer.
     */
    public int getExceptionTimer()
    {
        return exceptionTimer;
    }

    /**
     * Obtains a fake player class which can be used inside the AI logic to utilize in requests that require a player instance when none is available.
     *
     * @return a fake player.
     */
    protected FakePlayer getFakePlayer()
    {
        IPermissions permissions = building.getColony().getPermissions();
        return FakePlayerFactory.get((ServerLevel) world, new GameProfile(permissions.getOwner(), permissions.getOwnerName()));
    }

    /**
     * Whether the citizen AI may send this worker on a leisure break now (a few minutes of walking about). Workers that hold work nobody
     * else can take over say no.
     *
     * @return true if a leisure break is fine.
     */
    public boolean canTakeLeisure()
    {
        return canBeInterrupted();
    }

    /**
     * If the worker currently has work to do or could also just wander around.
     * @return true if can go idle.
     */
    public boolean canGoIdle()
    {
        return false;
    }
}
