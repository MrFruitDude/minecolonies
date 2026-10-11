package com.minecolonies.core.entity.ai.workers.util;

/**
 * Decides when a worker that cannot get on with a block has waited long enough for the next way out. Pure (no world), so the ladder can be
 * tested. The ladder, in the order it is climbed while the worker stays stuck on the same piece of work:
 * <ol>
 *   <li>{@link Step#REPATH}: throw away the standing spot and the path and ask for a new one.</li>
 *   <li>{@link Step#SAFE_SPOT}: pick a safe standable spot near the target and walk there.</li>
 *   <li>{@link Step#SCAFFOLD}: a hook for building a scaffold column to the target (not built yet: the AI says it did nothing and the
 *       ladder goes on).</li>
 *   <li>{@link Step#TELEPORT}: put the worker on the nearest standable spot at the edge of the work area (or at the hut entrance).</li>
 *   <li>{@link Step#DEFER}: give the block up for now. It is retried once at the end of the stage and then left out, so a worker never
 *       loops on one block for ever.</li>
 * </ol>
 * A worker that keeps moving is not stuck (a long walk is slow, not stuck): only time spent standing still counts towards the steps, and an
 * overall limit since the last finished block takes care of workers that move in circles.
 */
public final class WorkStuckTracker
{
    public enum Step
    {
        NONE, REPATH, SAFE_SPOT, SCAFFOLD, TELEPORT, DEFER
    }

    /** Ticks standing still on the same work before each step. */
    public static final int REPATH_AT   = 80;
    public static final int SAFE_SPOT_AT = 200;
    public static final int SCAFFOLD_AT = 300;
    public static final int TELEPORT_AT = 400;
    public static final int DEFER_AT    = 700;

    /** Ticks since the last finished block after which the work is deferred even if the worker moves around. */
    public static final int OVERALL_LIMIT = 3600;

    /** The worker counts as standing still when he moved less than this many blocks since the last check. */
    public static final double MOVED_BLOCKS = 0.75D;

    private int  stationary;
    private int  overall;
    private Step last = Step.NONE;

    /**
     * The worker could not work on the target for {@code ticks} more ticks.
     *
     * @param ticks the ticks since the last call.
     * @param moved how far the worker moved since the last call, in blocks.
     * @return the step that is due now, or NONE. Each step is returned once until {@link #progress()}.
     */
    public Step blocked(final int ticks, final double moved)
    {
        overall += ticks;
        if (moved >= MOVED_BLOCKS)
        {
            stationary = 0;
        }
        else
        {
            stationary += ticks;
        }

        final Step due = stepFor(stationary, overall);
        if (due.ordinal() > last.ordinal())
        {
            last = due;
            return due;
        }
        return Step.NONE;
    }

    /**
     * Takes a step right away (a worker that fell into a hole does not wait for the gentler steps), if it is further up the ladder than the
     * last one taken.
     *
     * @param step the step.
     * @return the step if it is taken now, NONE if the ladder is at or beyond it.
     */
    public Step escalateTo(final Step step)
    {
        if (step.ordinal() > last.ordinal())
        {
            last = step;
            return step;
        }
        return Step.NONE;
    }

    /**
     * The step for the time spent. Public so the thresholds can be tested.
     */
    public static Step stepFor(final int stationaryTicks, final int overallTicks)
    {
        if (stationaryTicks >= DEFER_AT || overallTicks >= OVERALL_LIMIT)
        {
            return Step.DEFER;
        }
        if (stationaryTicks >= TELEPORT_AT)
        {
            return Step.TELEPORT;
        }
        if (stationaryTicks >= SCAFFOLD_AT)
        {
            return Step.SCAFFOLD;
        }
        if (stationaryTicks >= SAFE_SPOT_AT)
        {
            return Step.SAFE_SPOT;
        }
        if (stationaryTicks >= REPATH_AT)
        {
            return Step.REPATH;
        }
        return Step.NONE;
    }

    /**
     * The worker is not stuck at the moment (he reached his spot and works): the time standing still starts again.
     */
    public void free()
    {
        stationary = 0;
    }

    /**
     * A block was finished (placed, mined or given up): everything starts again.
     */
    public void progress()
    {
        stationary = 0;
        overall = 0;
        last = Step.NONE;
    }

    public int stationaryTicks()
    {
        return stationary;
    }

    public int overallTicks()
    {
        return overall;
    }

    public Step lastStep()
    {
        return last;
    }
}
