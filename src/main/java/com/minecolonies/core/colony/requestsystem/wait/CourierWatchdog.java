package com.minecolonies.core.colony.requestsystem.wait;

import com.minecolonies.api.colony.requestsystem.token.IToken;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * RS2: gives a courier's task a deadline and notices a courier that stopped making progress.
 * <p>
 * Walking has no per-request timeout in the old system: the only cutoff was the 600-tick idle limit, so a courier that
 * cannot reach a target (blocked path, unreachable rack) kept its task, and the request with it, forever. Here a task
 * that is not done within {@link #TASK_DEADLINE} ticks, or during which the courier did not move for
 * {@link #STUCK_TICKS} ticks, is given up and goes back to the request system.
 * <p>
 * Not persisted: after a load the clock starts again.
 */
public final class CourierWatchdog
{
    /**
     * Game ticks a courier may spend on one task: five minutes.
     */
    public static final long TASK_DEADLINE = 6000L;

    /**
     * Game ticks without moving two blocks after which a courier with a task counts as stuck: one minute.
     */
    public static final long STUCK_TICKS = 1200L;

    /**
     * What the check found.
     */
    public enum Verdict
    {
        OK,
        DEADLINE,
        STUCK
    }

    @Nullable
    private IToken<?> task;
    private long      since;
    private long      lastMove;
    @Nullable
    private BlockPos  lastPos;

    /**
     * @param current  the task the courier is working on.
     * @param position where the courier stands.
     * @param now      the game time.
     * @return whether the task should be given up.
     */
    @NotNull
    public Verdict check(@NotNull final IToken<?> current, @NotNull final BlockPos position, final long now)
    {
        if (!current.equals(task))
        {
            task = current;
            since = now;
            lastMove = now;
            lastPos = position;
            return Verdict.OK;
        }
        if (lastPos == null || lastPos.distManhattan(position) >= 2)
        {
            lastPos = position;
            lastMove = now;
        }
        if (now - since > TASK_DEADLINE)
        {
            return Verdict.DEADLINE;
        }
        return now - lastMove > STUCK_TICKS ? Verdict.STUCK : Verdict.OK;
    }

    /**
     * Forget the task (it ended or was given up).
     */
    public void reset()
    {
        task = null;
        lastPos = null;
    }
}
