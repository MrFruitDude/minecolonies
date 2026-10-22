package com.minecolonies.core.entity.citizen;

import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.ITickingTransition;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.TickRateStateMachine;
import com.minecolonies.api.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Round 13 B4: the citizen-level AI state machine with an exception handler. Before, a failing step of the citizen AI (eat, sleep, sick, mourn, the work
 * hand-off) logged a stack trace every time it ran, every 20 to 30 ticks, and the citizen never left the state.
 * <ul>
 * <li>a failing transition waits before it runs again: 40 ticks after the first failure, then 80;</li>
 * <li>at failure number {@link #RESET_AFTER} in a row the citizen AI goes back to IDLE (and the citizen's own reset runs) to choose its state afresh, so the
 * real sequence is: fail, wait 40, fail, wait 80, fail, reset ({@link #MAX_BACKOFF} is the longest wait);</li>
 * <li>"in a row": a transition that fires without an exception clears the count, and so do {@link #QUIET_TICKS} quiet ticks;</li>
 * <li>the log is limited for all citizens together, per exception type and throw site: the first one logs its stack trace, then one line at most every
 * {@link #LOG_INTERVAL} ticks, with the number suppressed.</li>
 * </ul>
 * Server thread only, like the rest of the citizen AI.
 */
public class CitizenAIStateMachine extends TickRateStateMachine<IState>
{
    public static final int RESET_AFTER  = 3;
    public static final int MAX_BACKOFF  = 40 << (RESET_AFTER - 2);
    public static final int QUIET_TICKS  = 1200;
    public static final int LOG_INTERVAL = 1200;

    /**
     * The shared log limit: one entry per exception type and throw site, for all citizens. Server thread only.
     */
    private static final Map<String, long[]> LOG_LIMITS     = new HashMap<>();
    private static final int                 MAX_LOG_LIMITS = 256;

    private final LongSupplier gameTime;
    private final Supplier<String> owner;
    private       Runnable     onReset = () -> {};

    private int  failures     = 0;
    private long lastFailure  = Long.MIN_VALUE / 2;
    private int  suppressed   = 0;
    private int  resets       = 0;
    private int  logged       = 0;
    private int  exceptions   = 0;

    /**
     * @param gameTime the game time.
     * @param owner    a name for the log (the citizen).
     * @param tickRate the tick rate of the machine.
     */
    public CitizenAIStateMachine(final LongSupplier gameTime, final Supplier<String> owner, final int tickRate)
    {
        super(CitizenAIState.IDLE, e -> {}, tickRate);
        this.gameTime = gameTime;
        this.owner = owner;
    }

    /**
     * @param onReset runs when the machine goes back to IDLE after repeated failures.
     */
    public void setOnReset(final Runnable onReset)
    {
        this.onReset = onReset == null ? () -> {} : onReset;
    }

    @Override
    protected void logTransitionException(final String message, final RuntimeException e)
    {
        final long now = gameTime.getAsLong();
        final String key = logKey(e);
        long[] limit = LOG_LIMITS.get(key);
        if (limit == null)
        {
            if (LOG_LIMITS.size() >= MAX_LOG_LIMITS)
            {
                LOG_LIMITS.clear();
            }
            // [0] last log time, [1] failures not logged since then
            limit = new long[] {now, 0};
            LOG_LIMITS.put(key, limit);
            Log.getLogger().warn("Citizen AI of " + owner.get() + ": " + message, e);
            logged++;
            suppressed = 0;
            return;
        }
        if (now - limit[0] >= LOG_INTERVAL || now < limit[0])
        {
            Log.getLogger().warn("Citizen AI of " + owner.get() + ": " + message + " " + e + " (" + limit[1] + " more of this kind, all citizens, since the last report)");
            limit[0] = now;
            limit[1] = 0;
            logged++;
            suppressed = 0;
            return;
        }
        limit[1]++;
        suppressed++;
    }

    /**
     * @param e the exception.
     * @return the key of the shared log limit: the exception type and the place it was thrown.
     */
    private static String logKey(final RuntimeException e)
    {
        final StackTraceElement[] trace = e.getStackTrace();
        return trace.length == 0 ? e.getClass().getName() : e.getClass().getName() + "@" + trace[0];
    }

    @Override
    public boolean transitionToNext(final ITickingTransition<IState> transition)
    {
        final int failuresBefore = exceptions;
        final boolean fired = super.transitionToNext(transition);
        if (fired && exceptions == failuresBefore)
        {
            // a step that worked: the failures so far were not in a row
            failures = 0;
        }
        return fired;
    }

    @Override
    protected void onException(final RuntimeException e)
    {
        super.onException(e);
        exceptions++;
        final long now = gameTime.getAsLong();
        if (now - lastFailure > QUIET_TICKS)
        {
            failures = 0;
        }
        lastFailure = now;
        failures++;
        if (failures >= RESET_AFTER)
        {
            failures = 0;
            resets++;
            reset();
            try
            {
                onReset.run();
            }
            catch (final RuntimeException resetError)
            {
                logTransitionException("reset after repeated failures threw an exception:", resetError);
            }
            return;
        }
        setCurrentDelay(backoff(failures));
    }

    /**
     * @param failures the failures in a row, from 1.
     * @return the wait before the failing transition runs again, in ticks.
     */
    public static int backoff(final int failures)
    {
        return 40 << Math.min(Math.max(0, failures - 1), RESET_AFTER - 2);
    }

    /**
     * @return the failures in a row so far.
     */
    public int getFailures()
    {
        return failures;
    }

    /**
     * @return how often the machine went back to IDLE after repeated failures.
     */
    public int getResets()
    {
        return resets;
    }

    /**
     * @return how many log lines this citizen wrote, and how many of its failures were not logged since its last line (the limit is shared by all citizens).
     */
    public int getLogged()
    {
        return logged;
    }

    public int getSuppressed()
    {
        return suppressed;
    }
}
