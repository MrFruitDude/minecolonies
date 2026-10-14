package com.minecolonies.api.colony.idle;

/**
 * Why an unemployed citizen stopped working on an idle task.
 */
public enum IdleStopReason
{
    /**
     * He got a job.
     */
    GOT_JOB,
    /**
     * The work hours are over.
     */
    NIGHT,
    /**
     * He sleeps, eats, is sick or hurt, mourns, flees or hides from the rain.
     */
    NEEDS,
    /**
     * The colony setting for idle citizens was switched off.
     */
    SETTING_OFF,
    /**
     * The task has nothing for him any more (the order is done, the lead is gone, the cap was lowered).
     */
    TASK_INVALID,
    /**
     * The citizen is gone from the colony (dead, removed) or his entity is not loaded.
     */
    CITIZEN_GONE
}
