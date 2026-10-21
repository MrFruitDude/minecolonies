package com.minecolonies.api.colony.labour;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.util.Log;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The vetoes on what labourers may dig and fell. See {@link ILabourExclusion} for the rules a hook must follow.
 */
public final class LabourExclusions
{
    /** A failing hook is logged at most once in this many milliseconds. */
    static final long LOG_EVERY_MS = 60_000L;

    private static final List<ILabourExclusion> EXCLUSIONS = new CopyOnWriteArrayList<>();
    /** When each failing hook was last logged (wall clock ms). */
    private static final Map<ILabourExclusion, Long> LOGGED = new ConcurrentHashMap<>();

    private LabourExclusions()
    {
    }

    /**
     * @param exclusion the veto to add (server thread only, constant time, no world access, filtered by colony id: see {@link ILabourExclusion}).
     */
    public static void register(final ILabourExclusion exclusion)
    {
        EXCLUSIONS.add(exclusion);
    }

    /**
     * Only for tests.
     */
    public static void unregister(final ILabourExclusion exclusion)
    {
        EXCLUSIONS.remove(exclusion);
        LOGGED.remove(exclusion);
    }

    /**
     * @return whether any registered veto holds for the block. A veto that throws holds (fail closed): the block is left alone and the error logged, rate limited.
     */
    public static boolean excluded(final IColony colony, final BlockPos pos)
    {
        for (final ILabourExclusion exclusion : EXCLUSIONS)
        {
            try
            {
                if (exclusion.excluded(colony, pos))
                {
                    return true;
                }
            }
            catch (final RuntimeException | LinkageError e)
            {
                if (shouldLog(exclusion, System.currentTimeMillis()))
                {
                    Log.getLogger().error("labour exclusion {} failed at {}; the block is left alone (logged at most once a minute per hook)", exclusion, pos, e);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether a failure of the hook is logged now (at most once per {@link #LOG_EVERY_MS}).
     */
    static boolean shouldLog(final ILabourExclusion exclusion, final long nowMs)
    {
        final Long last = LOGGED.get(exclusion);
        if (last != null && nowMs - last < LOG_EVERY_MS && nowMs >= last)
        {
            return false;
        }
        LOGGED.put(exclusion, nowMs);
        return true;
    }
}
