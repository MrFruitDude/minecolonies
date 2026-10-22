package com.minecolonies.core.entity.citizen;

import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.TickRateStateMachine;
import com.minecolonies.api.util.Log;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Round 13 B4: the citizen-level AI state machine with an exception handler. Before, a failing step of the citizen AI (eat, sleep, sick, mourn, the work
 * hand-off) logged a stack trace every time it ran, every 20 to 30 ticks, and the citizen never left the state.
 * <ul>
 * <li>a failing transition waits before it runs again: 40 ticks after the first failure, doubling, at most {@link #MAX_BACKOFF} ticks;</li>
 * <li>after {@link #RESET_AFTER} failures in a row the citizen AI goes back to IDLE (and the citizen's own reset runs) to choose its state afresh;</li>
 * <li>failures stop counting as "in a row" after {@link #QUIET_TICKS} quiet ticks;</li>
 * <li>the first failure of a citizen logs its stack trace; further ones log one line at most every {@link #LOG_INTERVAL} ticks, with the number suppressed.</li>
 * </ul>
 * Server thread only, like the rest of the citizen AI.
 */
public class CitizenAIStateMachine extends TickRateStateMachine<IState>
{
    public static final int RESET_AFTER  = 3;
    public static final int MAX_BACKOFF  = 400;
    public static final int QUIET_TICKS  = 1200;
    public static final int LOG_INTERVAL = 1200;

    private final LongSupplier gameTime;
    private final Supplier<String> owner;
    private       Runnable     onReset = () -> {};

    private int  failures     = 0;
    private long lastFailure  = Long.MIN_VALUE / 2;
    private long lastLog      = Long.MIN_VALUE / 2;
    private int  suppressed   = 0;
    private int  resets       = 0;
    private int  logged       = 0;

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
        if (logged == 0)
        {
            Log.getLogger().warn("Citizen AI of " + owner.get() + ": " + message, e);
            lastLog = now;
            logged++;
            return;
        }
        if (now - lastLog >= LOG_INTERVAL)
        {
            Log.getLogger().warn("Citizen AI of " + owner.get() + ": " + message + " " + e + " (" + suppressed + " more since the last report)");
            lastLog = now;
            suppressed = 0;
            logged++;
            return;
        }
        suppressed++;
    }

    @Override
    protected void onException(final RuntimeException e)
    {
        super.onException(e);
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
        return Math.min(MAX_BACKOFF, 40 << Math.min(Math.max(0, failures - 1), 4));
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
     * @return how many log lines were written, and how many failures were not logged since the last line.
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
