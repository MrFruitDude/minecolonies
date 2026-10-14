package com.minecolonies.api.colony.idle;

import net.minecraft.network.chat.Component;

/**
 * An unemployed citizen working on an {@link IIdleCitizenTask}. Held by the arbiter; ended by {@link #stop}. Server thread only.
 */
public interface IIdleTaskSession
{
    /**
     * @return the id of the task this session belongs to.
     */
    String getTaskId();

    /**
     * Asked once a second: whether there is still something to do for the citizen (the order he helps with is still led, the request is still open ...).
     * A false ends the session with {@link IdleStopReason#TASK_INVALID}.
     *
     * @return true to go on.
     */
    boolean isValid();

    /**
     * Called every AI step (a few ticks) while the citizen works on the session: walk, place, carry, craft.
     */
    void tick();

    /**
     * The session ends: give back whatever the citizen holds for the task (materials, claims, leases). Called once, with the citizen still in the colony (unless
     * the reason is {@link IdleStopReason#CITIZEN_GONE}).
     *
     * @param reason why.
     */
    void stop(IdleStopReason reason);

    /**
     * @return what to show for the citizen, e.g. "Helper (idle citizen)".
     */
    Component describe();
}
