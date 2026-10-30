package com.minecolonies.core.colony.idle;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.idle.IIdleCitizenTask;
import com.minecolonies.api.colony.idle.IIdleTaskSession;
import com.minecolonies.api.colony.idle.IdleStopReason;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.entity.ai.idle.BuilderHelperTask;
import com.minecolonies.core.entity.ai.minimal.EntityAIEatTask;
import com.minecolonies.core.entity.ai.workers.CitizenAI;
import com.minecolonies.core.entity.ai.workers.util.WorkerSettings;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Decides what the unemployed citizens of a colony do with their day: the one place that knows which citizens are free, offers them to the registered
 * {@link IIdleCitizenTask}s (highest priority first) and takes them off a task again when their day takes them away.
 * <p>
 * The lifecycle shared by every task, once a second per colony ({@link #tick}):
 * <ul>
 *   <li>a citizen is free when he has no job (or, round 3, his hut has had nothing for him for a while: {@link WorkerHelp}), is an adult with a loaded entity, is awake,
 *   not sick or hurt, not mourning, not hungry, no raider is near him and the rain does not stop the workers either (one rule for all, R2-F3), and the work hours are
 *   not over ({@link #isFree});</li>
 *   <li>a free citizen without a task is offered to the tasks; the first that has something gets him, the citizen AI ({@code CitizenAI}) then runs his
 *   {@link IIdleTaskSession#tick()} in the {@link CitizenAIState#LABOUR} state;</li>
 *   <li>a citizen leaves his task when he gets a job, the work hours end, he has needs (the citizen AI moves him to sleep, food or care: see
 *   {@link #leftLabour}), the colony setting is switched off, or the task has nothing for him any more;</li>
 *   <li>the per colony settings are in {@link IdleCitizenSettings}.</li>
 * </ul>
 * Everything is server side and per colony: a task only ever gets the citizens of the colony it is asked about.
 */
public final class IdleCitizenArbiter
{
    /**
     * Ticks a citizen who left a task for a need does not start another one.
     */
    private static final long NEEDS_COOLDOWN = 400L;

    /**
     * Ticks after joining that the citizen AI has to move him into the labour state (it decides every 10 ticks); a session of a citizen who is not there by then ends.
     */
    private static final long JOIN_GRACE = 200L;

    private static final List<IIdleCitizenTask> TASKS = new CopyOnWriteArrayList<>();

    static
    {
        register(new BuilderHelperTask());
        register(new com.minecolonies.core.colony.labour.DirectedTask());
        for (final com.minecolonies.core.colony.labour.LabourTask task : com.minecolonies.core.colony.labour.LabourTask.all())
        {
            register(task);
        }
    }

    private IdleCitizenArbiter()
    {
    }

    /**
     * Adds a task. Tasks are offered the free citizens from the highest priority to the lowest; a task registered twice (same id) replaces the first.
     *
     * @param task the task.
     */
    public static void register(final IIdleCitizenTask task)
    {
        TASKS.removeIf(t -> t.getId().equals(task.getId()));
        TASKS.add(task);
        TASKS.sort(Comparator.comparingInt(IIdleCitizenTask::getPriority).reversed());
    }

    /**
     * Removes a task (tests; citizens that work for it keep their session until it ends).
     *
     * @param id the id of the task.
     */
    public static void unregister(final String id)
    {
        TASKS.removeIf(t -> t.getId().equals(id));
    }

    /**
     * @return the registered tasks, the highest priority first.
     */
    public static List<IIdleCitizenTask> tasks()
    {
        return List.copyOf(TASKS);
    }

    // ------------------------------------------------------------------ queries

    /**
     * @param citizen a citizen.
     * @return the session he works on, null if he is not working for an idle task (or on the client).
     */
    @Nullable
    public static IIdleTaskSession sessionOf(final ICitizenData citizen)
    {
        return citizen != null && citizen.getColony() instanceof Colony colony ? colony.getIdleCitizens().session(citizen.getId()) : null;
    }

    /**
     * @param colony    the colony.
     * @param citizenId the id of a citizen of it.
     * @return the session he works on, or null.
     */
    @Nullable
    public static IIdleTaskSession sessionOf(final IColony colony, final int citizenId)
    {
        return colony instanceof Colony c ? c.getIdleCitizens().session(citizenId) : null;
    }

    public static boolean hasSession(final ICitizenData citizen)
    {
        return sessionOf(citizen) != null;
    }

    /**
     * Whether the work hours of the unemployed are on: the citizen AI sends them to bed two thousand ticks before the shift of the others ends.
     */
    public static boolean workHours(final Level level)
    {
        return WorldUtil.isPastTime(level, WorkerSettings.nightStartFor(false) - 2000);
    }

    /**
     * Whether the citizen is free for a task now: see the class comment. The same test decides who is offered to the tasks.
     *
     * @param colony  the colony.
     * @param citizen the citizen.
     * @return true if he may start work.
     */
    public static boolean isFree(final IColony colony, final ICitizenData citizen)
    {
        final Level level = colony.getWorld();
        if (level == null || citizen.isChild())
        {
            return false;
        }
        if (citizen.getJob() != null)
        {
            // round 3 agency: a worker whose hut has had nothing for him for a while may help out (WorkerHelp keeps the clock)
            if (!(colony instanceof Colony c) || !WorkerHelp.mayHelp(colony, citizen, c.getIdleCitizens(), level.getGameTime()))
            {
                return false;
            }
        }
        if (citizen.isPaused() || citizen.isAsleep() || !workHours(level))
        {
            return false;
        }
        final AbstractEntityCitizen entity = citizen.getEntity().orElse(null);
        if (entity == null || !entity.isAlive() || entity.isBaby() || !entity.isEffectiveAi())
        {
            return false;
        }
        if (citizen.getCitizenDiseaseHandler().isSick() || citizen.getCitizenDiseaseHandler().isHurt() || citizen.getCitizenMournHandler().isMourning()
              || citizen.getSaturation() <= EntityAIEatTask.RESTAURANT_LIMIT)
        {
            return false;
        }
        return !stoppedByRaid(colony, entity) && !stoppedByRain(colony, citizen);
    }

    /**
     * R2-F3: the raid rule of the citizen AI (D3): only a citizen near a raider stops; the rest of the colony goes on.
     *
     * @param colony the colony.
     * @param entity the citizen.
     * @return true while the colony is raided and a raider is within {@link CitizenAI#RAID_HIDE_RANGE} blocks of him.
     */
    public static boolean stoppedByRaid(final IColony colony, final AbstractEntityCitizen entity)
    {
        return colony.getRaiderManager().isRaided() && CitizenAI.raiderWithin(entity, CitizenAI.RAID_HIDE_RANGE);
    }

    /**
     * R2-F3: the rain rule of the workers ({@link CitizenAI#worksInRain}): the rain stops idle work only where it stops the workers (no rain research, no server
     * setting), so a shower no longer freezes the labour of a colony whose workers carry on.
     *
     * @param colony  the colony.
     * @param citizen the citizen.
     * @return true while it rains and the rain stops him.
     */
    public static boolean stoppedByRain(final IColony colony, final ICitizenData citizen)
    {
        final Level level = colony.getWorld();
        return level != null && level.isRaining() && !WorldUtil.isNetherType(level) && !CitizenAI.worksInRain(colony, citizen.getWorkBuilding());
    }

    // ------------------------------------------------------------------ the once a second tick

    /**
     * Once a second per colony: ends sessions whose citizen cannot go on, and offers the free citizens to the tasks.
     *
     * @param colony the colony.
     */
    public static void tick(final Colony colony)
    {
        final Level level = colony.getWorld();
        if (level == null || level.isClientSide())
        {
            return;
        }
        final IdleCitizenState state = colony.getIdleCitizens();
        final long now = level.getGameTime();
        final boolean enabled = IdleCitizenSettings.enabled(colony);

        if (!state.sessions().isEmpty())
        {
            for (final var entry : new ArrayList<>(state.sessions().entrySet()))
            {
                final int id = entry.getKey();
                final ICitizenData citizen = colony.getCitizenManager().getCivilian(id);
                final IdleStopReason reason = stopReason(colony, citizen, entry.getValue(), enabled, now);
                if (reason != null)
                {
                    end(colony, id, reason, now);
                }
            }
        }

        if (!enabled || TASKS.isEmpty() || !workHours(level))
        {
            return;
        }
        for (final ICitizenData citizen : colony.getCitizenManager().getCitizens())
        {
            if (state.session(citizen.getId()) != null || state.onCooldown(citizen.getId(), now) || !isFree(colony, citizen))
            {
                continue;
            }
            final boolean worker = citizen.getJob() != null;
            for (final IIdleCitizenTask task : TASKS)
            {
                if (worker && !task.acceptsWorkers())
                {
                    continue;
                }
                final IIdleTaskSession session = task.tryStart(colony, citizen);
                if (session != null)
                {
                    state.put(citizen.getId(), session, now);
                    break;
                }
            }
        }
    }

    @Nullable
    private static IdleStopReason stopReason(final Colony colony, @Nullable final ICitizenData citizen, final IdleCitizenState.Entry entry, final boolean enabled, final long now)
    {
        if (citizen == null || citizen.getEntity().isEmpty() || !citizen.getEntity().get().isAlive())
        {
            return IdleStopReason.CITIZEN_GONE;
        }
        if (!enabled)
        {
            return IdleStopReason.SETTING_OFF;
        }
        if (citizen.getJob() != null)
        {
            if (!WorkerHelp.isWorkerSession(entry.session()))
            {
                return IdleStopReason.GOT_JOB;
            }
            if (!IdleCitizenSettings.workersHelp(colony))
            {
                return IdleStopReason.SETTING_OFF;
            }
            if (!WorkerHelp.outOfWork(citizen))
            {
                // his hut has work for him again: back to it
                return IdleStopReason.OWN_WORK;
            }
        }
        if (citizen.isPaused())
        {
            return IdleStopReason.TASK_INVALID;
        }
        if (!workHours(colony.getWorld()))
        {
            return IdleStopReason.NIGHT;
        }
        if (!entry.session().isValid())
        {
            return IdleStopReason.TASK_INVALID;
        }
        if (now - entry.since() > JOIN_GRACE && citizen.getEntity().get() instanceof com.minecolonies.core.entity.citizen.EntityCitizen entity
              && entity.getCitizenAI().getState() != CitizenAIState.LABOUR)
        {
            // the citizen AI did not take him (or has taken him elsewhere without us noticing)
            return IdleStopReason.NEEDS;
        }
        return null;
    }

    private static void end(final Colony colony, final int citizenId, final IdleStopReason reason, final long now)
    {
        final IdleCitizenState.Entry entry = colony.getIdleCitizens().remove(citizenId);
        if (entry == null)
        {
            return;
        }
        try
        {
            entry.session().stop(reason);
        }
        catch (final RuntimeException e)
        {
            Log.getLogger().error("Idle task {} of citizen {} failed to stop ({})", entry.session().getTaskId(), citizenId, reason, e);
        }
        if (reason == IdleStopReason.NEEDS || reason == IdleStopReason.NIGHT)
        {
            colony.getIdleCitizens().cooldown(citizenId, now + NEEDS_COOLDOWN);
        }
    }

    /**
     * The citizen AI moved the citizen out of the labour state: sleep, food, care, mourning, a job. The session ends.
     *
     * @param citizen the citizen.
     * @param next    the state he goes to.
     */
    public static void leftLabour(final ICitizenData citizen, final IState next)
    {
        if (!(citizen.getColony() instanceof Colony colony) || colony.getWorld() == null || colony.getIdleCitizens().session(citizen.getId()) == null)
        {
            return;
        }
        final boolean helping = WorkerHelp.isWorkerSession(colony.getIdleCitizens().session(citizen.getId()));
        final IdleStopReason reason = citizen.getJob() != null && !helping ? IdleStopReason.GOT_JOB
                                        : !workHours(colony.getWorld()) ? IdleStopReason.NIGHT : IdleStopReason.NEEDS;
        end(colony, citizen.getId(), reason, colony.getWorld().getGameTime());
    }

    /**
     * Ends the session of a citizen (a test, or a task that decided on its own).
     *
     * @param colony    the colony.
     * @param citizenId the citizen.
     * @param reason    why.
     */
    public static void stop(final IColony colony, final int citizenId, final IdleStopReason reason)
    {
        if (colony instanceof Colony c && c.getWorld() != null)
        {
            end(c, citizenId, reason, c.getWorld().getGameTime());
        }
    }

    /**
     * Only for tests: the sessions are forgotten without being stopped (what a restart does to them: they are not saved).
     */
    public static void forgetSessionsForTests(final Colony colony)
    {
        colony.getIdleCitizens().sessions().clear();
    }

    /**
     * Ends every session of the colony.
     */
    public static void stopAll(final Colony colony, final IdleStopReason reason)
    {
        for (final Integer id : new ArrayList<>(colony.getIdleCitizens().sessions().keySet()))
        {
            stop(colony, id, reason);
        }
    }

    /**
     * Runs the work of a citizen's session; the citizen AI calls this every AI step while he is in the labour state.
     *
     * @param citizen the citizen.
     */
    public static void tickSession(final ICitizenData citizen)
    {
        final IIdleTaskSession session = sessionOf(citizen);
        if (session == null)
        {
            return;
        }
        try
        {
            session.tick();
        }
        catch (final RuntimeException e)
        {
            Log.getLogger().error("Idle task {} of citizen {} failed in its step", session.getTaskId(), citizen.getId(), e);
            stop(citizen.getColony(), citizen.getId(), IdleStopReason.TASK_INVALID);
        }
    }
}
