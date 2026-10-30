package com.minecolonies.core.entity.ai.workers;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.core.colony.idle.WorkerHelp;
import com.minecolonies.api.colony.buildings.IBuilding;
import org.jetbrains.annotations.Nullable;
import com.minecolonies.api.colony.buildings.ModBuildings;
import com.minecolonies.api.colony.interactionhandling.ChatPriority;
import com.minecolonies.api.colony.jobs.ModJobs;
import com.minecolonies.api.entity.ai.IStateAI;
import com.minecolonies.api.entity.ai.ITickingStateAI;
import com.minecolonies.api.entity.ai.statemachine.AIEventTarget;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIBlockingEventType;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.api.entity.citizen.VisibleCitizenStatus;
import com.minecolonies.api.entity.citizen.citizenhandlers.ICitizenColonyHandler;
import com.minecolonies.api.util.CompatibilityUtils;
import com.minecolonies.api.util.MathUtils;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.api.util.constant.CitizenConstants;
import com.minecolonies.core.entity.ai.workers.util.WorkerSettings;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.idle.IdleCitizenArbiter;
import com.minecolonies.core.colony.life.CitizenLife;
import com.minecolonies.core.colony.interactionhandling.StandardInteraction;
import com.minecolonies.core.colony.jobs.AbstractJobGuard;
import com.minecolonies.core.colony.jobs.JobPupil;
import com.minecolonies.core.entity.ai.minimal.*;
import com.minecolonies.core.entity.ai.workers.service.EntityAIWorkDeliveryman;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import com.minecolonies.api.entity.mobs.AbstractEntityMinecoloniesRaider;

import java.util.ArrayList;
import java.util.List;

import static com.minecolonies.api.entity.citizen.AbstractEntityCitizen.ENTITY_AI_TICKRATE;
import static com.minecolonies.api.entity.citizen.VisibleCitizenStatus.*;
import static com.minecolonies.api.research.util.ResearchConstants.WORKING_IN_RAIN;
import static com.minecolonies.api.util.constant.CitizenConstants.*;
import static com.minecolonies.api.util.constant.Constants.DEFAULT_SPEED;
import static com.minecolonies.api.util.constant.TranslationConstants.*;
import static com.minecolonies.core.entity.ai.minimal.EntityAIEatTask.RESTAURANT_LIMIT;
import static com.minecolonies.core.entity.citizen.citizenhandlers.CitizenDiseaseHandler.SEEK_DOCTOR_HEALTH;

/**
 * High level AI for citizens, which switches between all the different AI states like sleeping,working,fleeing etc
 */
public class CitizenAI implements IStateAI
{
    /**
     * Citizen this AI belongs to
     */
    private final EntityCitizen citizen;

    /**
     * The last citizen AI state
     */
    private IState lastState = CitizenAIState.IDLE;

    /**
     * List of small AI's added
     */
    private List<IStateAI> minimalAI = new ArrayList<>();

    /**
     * The status the last decision asked for (B5): applied when the state changes, so a new state starts with its own status instead of none.
     */
    private VisibleCitizenStatus intendedStatus = null;

    /**
     * D3: whether the citizen hid from raiders at its last check, and the game time until which that answer holds.
     */
    private boolean hidingFromRaid    = false;
    private long    raidCheckValidUntil = Long.MIN_VALUE;

    /**
     * D3: citizens within this many blocks of a raider hide; the others carry on.
     */
    public static final int RAID_HIDE_RANGE = 32;

    /**
     * D3: how long one raider check holds, in ticks.
     */
    private static final int RAID_CHECK_TICKS = 40;

    /**
     * D3: a worker inside its hut hides only from a raider this close.
     */
    public static final int RAID_INDOOR_RANGE = 8;

    /**
     * D5: the longest extra sleep after dawn, in ticks, spread over the citizens by id.
     */
    public static final int MAX_WAKE_DELAY = 600;

    public CitizenAI(final EntityCitizen citizen)
    {
        this.citizen = citizen;

        citizen.getCitizenAI().addTransition(new AIEventTarget<IState>(AIBlockingEventType.EVENT, () -> true, this::decideAiTask, 10));
        registerWorkAI();

        minimalAI.add(new EntityAICitizenAvoidEntity(citizen, Monster.class, (float) DISTANCE_OF_ENTITY_AVOID, LATER_RUN_SPEED_AVOID, INITIAL_RUN_SPEED_AVOID));
        minimalAI.add(new EntityAIEatTask(citizen));
        // round 4 life: chats, the morning greeting and rain shelter come before the wander AI's own free-time steps
        minimalAI.add(new EntityAICitizenLife(citizen));
        minimalAI.add(new EntityAICitizenWander(citizen, DEFAULT_SPEED));
        minimalAI.add(new EntityAISickTask(citizen));
        minimalAI.add(new EntityAISleep(citizen));
        minimalAI.add(new EntityAIMournCitizen(citizen, DEFAULT_SPEED));
    }

    /**
     * Registers callbacks for the work/job AI
     */
    private void registerWorkAI()
    {
        citizen.getCitizenAI().addTransition(new AITarget<>(CitizenAIState.WORK, () -> true, () ->
        {
            final ITickingStateAI ai = citizen.getCitizenJobHandler().getColonyJob().getWorkerAI();
            if (ai != null)
            {
                ai.resetAI();
            }
            citizen.getCitizenData().setVisibleStatus(VisibleCitizenStatus.WORKING);
            return CitizenAIState.WORKING;
        }, 10));

        // An unemployed citizen at work for an idle task (helping a builder, ...): the session of the idle citizen arbiter runs his steps.
        citizen.getCitizenAI().addTransition(new AITarget<>(CitizenAIState.LABOUR, () -> true, () ->
        {
            IdleCitizenArbiter.tickSession(citizen.getCitizenData());
            return CitizenAIState.LABOUR;
        }, ENTITY_AI_TICKRATE));

        citizen.getCitizenAI().addTransition(new AITarget<>(CitizenAIState.WORKING, () -> true, () ->
        {
            if (citizen.getCitizenJobHandler().getColonyJob() != null)
            {
                final ITickingStateAI ai = citizen.getCitizenJobHandler().getColonyJob().getWorkerAI();
                if (ai != null)
                {
                    citizen.getCitizenJobHandler().getColonyJob().getWorkerAI().tick();
                }
            }

            return CitizenAIState.WORKING;
        }, ENTITY_AI_TICKRATE));
    }

    /**
     * Checks on the AI state the citizen should be in, and transitions as necessary
     *
     * @return
     */
    private IState decideAiTask()
    {
        intendedStatus = null;
        IState next = calculateNextState();
        if (next == null || next == lastState)
        {
            if (citizen.getCitizenAI().getState() == CitizenAIState.IDLE && next != CitizenAIState.IDLE)
            {
                return next;
            }
            return null;
        }

        // B5: the new state starts with the status the decision asked for (MOURNING, RAIDED, ...), not with none; its own AI may set a finer one.
        citizen.getCitizenData().setVisibleStatus(intendedStatus);
        if (lastState == CitizenAIState.LABOUR)
        {
            // sleep, food, care, a job: the idle task of the citizen ends
            IdleCitizenArbiter.leftLabour(citizen.getCitizenData(), next);
        }
        lastState = next;
        return lastState;
    }

    /**
     * Determines the AI state the citizen should be doing, sleeping,raiding etc at different priorities
     *
     * @return
     */
    private IState calculateNextState()
    {
        if (citizen.getCitizenJobHandler().getColonyJob() instanceof AbstractJobGuard guardJob)
        {
            if (shouldEat())
            {
                return CitizenAIState.EATING;
            }

            // Sick
            if (citizen.getCitizenData().getCitizenDiseaseHandler().isSick() && guardJob.canAIBeInterrupted())
            {
                setStatus(VisibleCitizenStatus.SICK);
                return CitizenAIState.SICK;
            }

            return CitizenAIState.WORK;
        }

        // Sick at hospital
        if (citizen.getCitizenData().getCitizenDiseaseHandler().isSick() && citizen.getCitizenData().getCitizenDiseaseHandler().sleepsAtHospital())
        {
            setStatus(VisibleCitizenStatus.SICK);
            return CitizenAIState.SICK;
        }

        // Raiding (D3): only citizens near a raider hide; the others, indoor workers included, carry on.
        if (citizen.getCitizenColonyHandler().getColonyOrRegister().getRaiderManager().isRaided())
        {
            if (shouldHideFromRaid())
            {
                citizen.getCitizenData().triggerInteraction(new StandardInteraction(Component.translatableEscape(COM_MINECOLONIES_COREMOD_ENTITY_CITIZEN_RAID), ChatPriority.IMPORTANT));
                want(RAIDED);
                return CitizenAIState.SLEEP;
            }
        }
        else
        {
            hidingFromRaid = false;
            raidCheckValidUntil = Long.MIN_VALUE;
        }

        // Sleeping (D5: a sleeper wakes up to MAX_WAKE_DELAY ticks after dawn, spread by citizen id, so the town does not set off all at once)
        final Level level = CompatibilityUtils.getWorldFromCitizen(citizen);
        if (!WorldUtil.isPastTime(level, WorkerSettings.nightStartFor(citizen.getCitizenData().getJob() != null) - 2000)
              || (lastState == CitizenAIState.SLEEP && level.getOverworldClockTime() % 24000 < wakeDelayOf(citizen)))
        {
            if (lastState == CitizenAIState.SLEEP)
            {
                want(SLEEP);
                citizen.getCitizenAI().setCurrentDelay(20 * 15);
                return CitizenAIState.SLEEP;
            }

            if (citizen.getCitizenSleepHandler().shouldGoSleep() && !courierFinishesTaskFirst())
            {
                citizen.getCitizenData().onGoSleep();
                want(SLEEP);
                return CitizenAIState.SLEEP;
            }
        }
        else
        {
            if (citizen.getCitizenSleepHandler().isAsleep())
            {
                if (citizen.getCitizenData().getCitizenDiseaseHandler().isSick())
                {
                    final BlockPos bedPos = citizen.getCitizenSleepHandler().getBedLocation();
                    if (bedPos == null || bedPos.distSqr(citizen.blockPosition()) > 5)
                    {
                        citizen.getCitizenSleepHandler().onWakeUp();
                    }
                }
                else
                {
                    citizen.getCitizenSleepHandler().onWakeUp();
                }
            }
        }

        // Sick
        if (citizen.getCitizenData().getCitizenDiseaseHandler().isSick() || citizen.getCitizenData().getCitizenDiseaseHandler().isHurt())
        {
            setStatus(VisibleCitizenStatus.SICK);
            return CitizenAIState.SICK;
        }

        // Eating
        if (shouldEat() && !courierFinishesTaskFirst())
        {
            want(EAT);
            return CitizenAIState.EATING;
        }

        // Mourning
        if (citizen.getCitizenData().getCitizenMournHandler().isMourning())
        {
            if (lastState != CitizenAIState.MOURN)
            {
                citizen.getCitizenData().triggerInteraction(new StandardInteraction(Component.translatableEscape(COM_MINECOLONIES_COREMOD_ENTITY_CITIZEN_MOURNING,
                  citizen.getCitizenData().getCitizenMournHandler().getDeceasedCitizens().iterator().next()),
                  Component.translatableEscape(COM_MINECOLONIES_COREMOD_ENTITY_CITIZEN_MOURNING),
                  ChatPriority.IMPORTANT));

            }
            // B5: mourners keep the MOURNING status for the whole mourning, not only on its first step
            want(MOURNING);
            return CitizenAIState.MOURN;
        }

        // Raining
        if (CompatibilityUtils.getWorldFromCitizen(citizen).isRaining() && !shouldWorkWhileRaining() && !WorldUtil.isNetherType(citizen.level()) && !courierFinishesTaskFirst())
        {
            want(BAD_WEATHER);
            if (!citizen.getCitizenData().getColony().getRaiderManager().isRaided()
                  && !citizen.getCitizenData().getCitizenMournHandler().isMourning())
            {
                citizen.getCitizenData().triggerInteraction(new StandardInteraction(Component.translatableEscape(COM_MINECOLONIES_COREMOD_ENTITY_CITIZEN_RAINING), ChatPriority.HIDDEN));
            }
            return CitizenAIState.IDLE;
        }

        // Work
        if (citizen.isBaby() && citizen.getCitizenJobHandler().getColonyJob() instanceof JobPupil && citizen.level().getOverworldClockTime() % 24000 > NOON)
        {
            want(HOUSE);
            return CitizenAIState.IDLE;
        }

        // Round 4 life: once a morning a citizen who just woke greets the others at the town hall or his hut first, at most CitizenLife.MORNING_BUDGET ticks
        if (citizen.getLife().greetingHolds(lastState == CitizenAIState.SLEEP || lastState == CitizenAIState.IDLE || lastState == CitizenAIState.EATING))
        {
            want(LEISURE);
            return CitizenAIState.IDLE;
        }

        // An unemployed citizen with a task of the idle citizen arbiter (after the needs above: they end it), or a worker helping out while his hut has nothing for
        // him (round 3 agency; the arbiter ends it when his own work is back)
        if ((citizen.getCitizenJobHandler().getColonyJob() == null && IdleCitizenArbiter.hasSession(citizen.getCitizenData()))
              || WorkerHelp.isHelping(citizen.getCitizenData()))
        {
            if (lastState != CitizenAIState.LABOUR && citizen.getCitizenJobHandler().getColonyJob() != null)
            {
                citizen.getCitizenData().triggerInteraction(new StandardInteraction(Component.translatableEscape(WorkerHelp.HELPING_NOTICE), ChatPriority.CHITCHAT));
            }
            want(WORKING);
            return CitizenAIState.LABOUR;
        }

        if (citizen.getCitizenJobHandler().getColonyJob() != null
            && citizen.getCitizenJobHandler().getColonyJob().getWorkerAI() instanceof AbstractEntityAIBasic<?,?> abstractEntityAIBasic && !abstractEntityAIBasic.canGoIdle()
            && (citizen.getCitizenData().getLeisureTime() <= 0
            || !citizen.getCitizenData().getJob().canAIBeInterrupted()
            || !abstractEntityAIBasic.canTakeLeisure()))
        {
            want(WORKING);
            return CitizenAIState.WORK;
        }

        want(idleStatus(citizen.getCitizenJobHandler().getColonyJob() != null, citizen.getCitizenData().getLeisureTime(), citizen.getCitizenData().getHomeBuilding() != null));
        return CitizenAIState.IDLE;
    }

    /**
     * The status of an idle citizen (B5): LEISURE for a worker on a leisure break, HOMELESS for one without a home, else HOUSE.
     *
     * @param hasJob      whether the citizen has a job.
     * @param leisureTime the remaining leisure time in ticks.
     * @param hasHome     whether the citizen has a home building.
     * @return the status.
     */
    public static VisibleCitizenStatus idleStatus(final boolean hasJob, final int leisureTime, final boolean hasHome)
    {
        if (hasJob && leisureTime > 0)
        {
            return LEISURE;
        }
        return hasHome ? HOUSE : HOMELESS;
    }

    /**
     * Asks for a status: set now if the citizen shows none, and applied when the state changes.
     */
    private void want(final VisibleCitizenStatus status)
    {
        intendedStatus = status;
        citizen.setVisibleStatusIfNone(status);
    }

    /**
     * Sets a status now (it replaces the current one), and applies it when the state changes.
     */
    private void setStatus(final VisibleCitizenStatus status)
    {
        intendedStatus = status;
        citizen.getCitizenData().setVisibleStatus(status);
    }

    /**
     * D5: the extra sleep of a citizen after dawn.
     *
     * @param citizenId the citizen id.
     * @return 0 to {@link #MAX_WAKE_DELAY} ticks, fixed per citizen.
     */
    public static int wakeDelay(final int citizenId)
    {
        return Math.floorMod(citizenId * 7919, MAX_WAKE_DELAY + 1);
    }

    /**
     * D5 and round 4 life: the extra sleep of this citizen after dawn, by its trait when the life extras are on (a brisk citizen is up first, a thoughtful one
     * last), else by id. Never beyond {@link #MAX_WAKE_DELAY}.
     *
     * @param citizen the citizen.
     * @return 0 to {@link #MAX_WAKE_DELAY} ticks.
     */
    public static int wakeDelayOf(final EntityCitizen citizen)
    {
        return CitizenLife.enabled() ? citizen.getLife().trait().wakeDelay(citizen.getCivilianID()) : wakeDelay(citizen.getCivilianID());
    }

    /**
     * D5: whether a sleeper still sleeps at this time of the day.
     *
     * @param dayTime   the time of the day, 0 is dawn.
     * @param citizenId the citizen id.
     * @return true in the first {@link #wakeDelay} ticks after dawn.
     */
    public static boolean stillSleepingAfterDawn(final long dayTime, final int citizenId)
    {
        return dayTime >= 0 && dayTime < wakeDelay(citizenId);
    }

    /**
     * D3: whether this citizen hides from the raid: only when a raider is within {@link #RAID_HIDE_RANGE} blocks. One check holds for
     * {@link #RAID_CHECK_TICKS} ticks; a hider keeps hiding until no raider is within the range plus 8 blocks.
     *
     * @return true when the citizen should hide.
     */
    private boolean shouldHideFromRaid()
    {
        final long now = citizen.level().getGameTime();
        if (now < raidCheckValidUntil)
        {
            return hidingFromRaid;
        }
        raidCheckValidUntil = now + RAID_CHECK_TICKS;
        hidingFromRaid = raiderWithin(citizen, raidHideRange(hidingFromRaid, isIndoorsAtWork()));
        return hidingFromRaid;
    }

    /**
     * D3: a worker at work inside its hut keeps working unless a raider comes within {@link #RAID_INDOOR_RANGE} blocks.
     *
     * @return true when the citizen works and stands inside its work building.
     */
    private boolean isIndoorsAtWork()
    {
        final var work = citizen.getCitizenColonyHandler().getWorkBuilding();
        return (lastState == CitizenAIState.WORK || lastState == CitizenAIState.WORKING) && work != null && work.isInBuilding(citizen.blockPosition());
    }

    /**
     * D3: the raider range at which a citizen hides.
     *
     * @param hiding          whether the citizen hides already (it keeps hiding until the raiders are 8 blocks further away).
     * @param indoorsAtWork   whether the citizen works inside its hut.
     * @return the range in blocks.
     */
    public static int raidHideRange(final boolean hiding, final boolean indoorsAtWork)
    {
        return (indoorsAtWork ? RAID_INDOOR_RANGE : RAID_HIDE_RANGE) + (hiding ? 8 : 0);
    }

    /**
     * @param entity the citizen.
     * @param range  the range in blocks.
     * @return true when a living raider is within the range of the citizen.
     */
    public static boolean raiderWithin(final Entity entity, final double range)
    {
        return !entity.level().getEntitiesOfClass(AbstractEntityMinecoloniesRaider.class, entity.getBoundingBox().inflate(range), Entity::isAlive).isEmpty();
    }

    /**
     * A courier that holds a task finishes the delivery before it goes to eat, sleep or hide from the rain (see {@link EntityAIWorkDeliveryman#deferBreakForTask}).
     * A break that is already under way is not interrupted. Asked only when one of those breaks is due.
     *
     * @return true when the break has to wait for the courier's task.
     */
    private boolean courierFinishesTaskFirst()
    {
        if (lastState == CitizenAIState.SLEEP || lastState == CitizenAIState.EATING)
        {
            return false;
        }
        final var job = citizen.getCitizenJobHandler().getColonyJob();
        return job != null && job.getWorkerAI() instanceof EntityAIWorkDeliveryman courier && courier.deferBreakForTask();
    }

    /**
     * Checks if the citizen should be eating
     *
     * @return
     */
    public boolean shouldEat()
    {
        if (citizen.getCitizenData().justAte() || citizen.getCitizenData().getSaturation() >= FULL_SATURATION)
        {
            return false;
        }

        if (citizen.getCitizenData().getJob() != null && (!citizen.getCitizenData().getJob().canAIBeInterrupted()))
        {
            return false;
        }

        if (lastState == CitizenAIState.EATING)
        {
            return true;
        }

        if (citizen.getCitizenData().getJob() != null && (citizen.getCitizenData().getJob().getJobRegistryEntry() == ModJobs.cook.get()) && MathUtils.RANDOM.nextInt(200) > 0)
        {
            return false;
        }

        if (citizen.getCitizenData().getCitizenDiseaseHandler().isSick() && citizen.getCitizenSleepHandler().isAsleep())
        {
            return false;
        }

        return citizen.getCitizenData().getSaturation() <= CitizenConstants.AVERAGE_SATURATION &&
                 (citizen.getCitizenData().getSaturation() <= RESTAURANT_LIMIT ||
                    (citizen.getCitizenData().getSaturation() < LOW_SATURATION && citizen.getHealth() < SEEK_DOCTOR_HEALTH));
    }

    /**
     * Checks if the citizen should work even when it rains.
     *
     * @return true if his building level is bigger than 5.
     */
    private boolean shouldWorkWhileRaining()
    {
        final ICitizenColonyHandler colonyHandler = citizen.getCitizenColonyHandler();
        return worksInRain(colonyHandler.getColonyOrRegister(), colonyHandler.getWorkBuilding());
    }

    /**
     * The one rain rule of the colony (R2-F3): a citizen keeps working in the rain when the server says so, when the colony has the rain research, or when his hut
     * allows it. The idle citizen arbiter asks the same question, so the jobless and the workers stop for the same rain.
     *
     * @param colony       the colony.
     * @param workBuilding the citizen's hut, null for a citizen without a job.
     * @return true if the rain does not stop him.
     */
    public static boolean worksInRain(final IColony colony, @Nullable final IBuilding workBuilding)
    {
        if (MineColonies.getConfig().getServer().workersAlwaysWorkInRain.get())
        {
            return true;
        }

        if (colony.getResearchManager().getResearchEffects().getEffectStrength(WORKING_IN_RAIN) > 0)
        {
            return true;
        }

        if (workBuilding != null && workBuilding.hasModule(WorkerBuildingModule.class))
        {
            return workBuilding.getFirstModuleOccurance(WorkerBuildingModule.class).canWorkDuringTheRain();
        }
        return false;
    }
}
