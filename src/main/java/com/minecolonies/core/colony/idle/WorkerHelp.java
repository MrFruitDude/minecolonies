package com.minecolonies.core.colony.idle;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.idle.IIdleTaskSession;
import com.minecolonies.core.colony.jobs.AbstractJobGuard;
import com.minecolonies.core.colony.labour.LabourSession;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Round 3 agency (r0 F7): a worker with nothing to do at his hut lends a hand. The rules in one place:
 * <ul>
 *   <li>he has a job whose AI says it may go idle ({@link AbstractEntityAIBasic#canGoIdle()}: no field to work, no order to build), and has said so for
 *   {@link #idleTicks()} ticks without a break;</li>
 *   <li>he carries nothing but his equipment (tools, armour): what he would gather or bring for somebody else is never mixed with his own work's goods;</li>
 *   <li>guards never help: they guard;</li>
 *   <li>the colony setting {@link IdleCitizenSettings#workersHelp} is on.</li>
 * </ul>
 * Then the idle citizen arbiter offers him the labour tasks that serve a hut's request (never the stock goals, a directed order or the builder helping, which are the
 * jobless citizens' own). The labour board takes him under its usual rules (his own colony only, the claims per request), and at most one worker per hut helps at
 * once. As soon as his own work is back (the AI no longer may go idle) the session ends and he goes back to his hut.
 */
public final class WorkerHelp
{
    /**
     * How long a worker is without work before he helps: one minute.
     */
    public static final long IDLE_TICKS = 1200L;

    /**
     * What a worker says while helping out (an interaction that holds while he helps).
     */
    public static final String HELPING_NOTICE = "com.minecolonies.coremod.entity.citizen.workerhelps";

    private static volatile Long idleTicksOverride;

    private WorkerHelp()
    {
    }

    /**
     * Only for tests: how long a worker waits without work before he helps (null: {@link #IDLE_TICKS}).
     */
    public static void overrideIdleTicksForTests(@Nullable final Long ticks)
    {
        idleTicksOverride = ticks;
    }

    /**
     * @return the ticks without work before a worker helps.
     */
    public static long idleTicks()
    {
        final Long override = idleTicksOverride;
        return override != null ? Math.max(0L, override) : IDLE_TICKS;
    }

    /**
     * @param citizen a citizen.
     * @return whether he has a job, it is not a guard's, and its AI has nothing to do now (asked without running it).
     */
    public static boolean outOfWork(final ICitizenData citizen)
    {
        if (citizen.getJob() == null || citizen.getJob() instanceof AbstractJobGuard<?> || citizen.isChild())
        {
            return false;
        }
        try
        {
            return citizen.getJob().getWorkerAI() instanceof AbstractEntityAIBasic<?, ?> ai && ai.building != null && ai.canGoIdle();
        }
        catch (final RuntimeException e)
        {
            return false;
        }
    }

    /**
     * @param citizen a citizen.
     * @return whether he carries only equipment (tools, weapons, armour: things that wear out), or nothing.
     */
    public static boolean carriesOnlyEquipment(final ICitizenData citizen)
    {
        final var inventory = citizen.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && !stack.isDamageableItem())
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether an employed worker may be offered a helping job now (the clock is kept by the colony's idle citizen state).
     *
     * @param colony  the colony.
     * @param citizen the worker.
     * @param state   the colony's idle citizen state.
     * @param now     the game time.
     * @return true if he may help.
     */
    static boolean mayHelp(final IColony colony, final ICitizenData citizen, final IdleCitizenState state, final long now)
    {
        final boolean idle = outOfWork(citizen);
        final long idleFor = state.outOfWorkFor(citizen.getId(), idle, now);
        return idle && idleFor >= idleTicks() && IdleCitizenSettings.workersHelp(colony) && carriesOnlyEquipment(citizen);
    }

    /**
     * @param session a session of the arbiter.
     * @return whether an employed worker holds it (a helping job, not a jobless citizen's labour).
     */
    public static boolean isWorkerSession(@Nullable final IIdleTaskSession session)
    {
        return session instanceof LabourSession labour && labour.byWorker();
    }

    /**
     * @param citizen a citizen.
     * @return whether he is a worker helping out now.
     */
    public static boolean isHelping(final ICitizenData citizen)
    {
        return citizen.getJob() != null && isWorkerSession(IdleCitizenArbiter.sessionOf(citizen));
    }
}
