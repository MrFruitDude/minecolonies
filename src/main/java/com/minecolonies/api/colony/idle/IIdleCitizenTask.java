package com.minecolonies.api.colony.idle;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import org.jetbrains.annotations.Nullable;

/**
 * Something an unemployed citizen does with his working day: helps a builder, later gathers, crafts at a table, serves a request. The colony's idle citizen arbiter
 * ({@code IdleCitizenArbiter}, once a second per colony) offers every citizen without a job and without a task to the registered tasks, the highest
 * {@link #getPriority() priority} first; the first one that accepts gets the citizen until it ends or the citizen's day takes him away (a job, the night, sleep,
 * food, sickness, the colony setting going off).
 * <p>
 * A task never creates work for itself: it only joins work that somebody else already has (a builder's open order, a request nobody else serves). Server thread only.
 * Register tasks through {@code IdleCitizenArbiter.register}.
 */
public interface IIdleCitizenTask
{
    /**
     * @return a short stable id, e.g. {@code builder_helper}; it is what UIs and logs show for the task.
     */
    String getId();

    /**
     * @return the priority: tasks are offered the citizen from the highest to the lowest. Builder helping is 100.
     */
    int getPriority();

    /**
     * Offered an unemployed citizen that is free to work now (adult, alive and loaded, no job, awake, not eating or sick, in the work hours, the colony setting is
     * on, no other task). Cheap: it is asked once a second for every free citizen. A task that already holds state for this citizen from before a reload (saved
     * with the colony) returns a session for it again.
     *
     * @param colony  the colony (the task must never look at another colony).
     * @param citizen the citizen.
     * @return the session, or null if the task has nothing for the citizen.
     */
    @Nullable
    IIdleTaskSession tryStart(IColony colony, ICitizenData citizen);
}
