package com.minecolonies.api.colony;

/**
 * Whether the walks of workers are replaced by a timed relocation (the worker waits as long as the walk would take, then appears at the
 * destination). Server setting {@code workers.abstractWalking}.
 */
public enum WorkerWalking
{
    /**
     * Workers always walk.
     */
    OFF,

    /**
     * Only walks longer than {@code workers.abstractWalkingMinBlocks} blocks are replaced.
     */
    LONG_ONLY,

    /**
     * Every walk beyond the reach of the destination is replaced.
     */
    ALWAYS
}
