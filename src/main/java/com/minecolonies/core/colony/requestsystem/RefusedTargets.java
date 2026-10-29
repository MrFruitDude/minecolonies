package com.minecolonies.core.colony.requestsystem;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Destinations that refused a delivery (full): couriers leave them be for a while instead of coming back at once with the same load (the refused
 * items are dumped, the delivery fails, the request is handed out again and a courier is sent again: a loop, r1 Fable F17). The pause doubles with
 * each refusal in a row ({@link #FIRST} ticks, up to {@link #MAX}) and ends at the first delivery that goes in. Pure (no world), shared by the couriers
 * of all colonies (keyed by colony and position). Held in memory only.
 */
public final class RefusedTargets
{
    /** The pause after the first refusal (1 minute). */
    public static final long FIRST = 1200L;

    /** The longest pause (5 minutes). */
    public static final long MAX = 6000L;

    private record Key(int colony, long pos)
    {
    }

    private record Entry(int refusals, long until)
    {
    }

    private static final Map<Key, Entry> REFUSED = new ConcurrentHashMap<>();

    private RefusedTargets()
    {
    }

    /**
     * A delivery to the destination was refused.
     *
     * @return the game time until which couriers leave it be.
     */
    public static long refused(final int colony, final BlockPos target, final long now)
    {
        final Key key = new Key(colony, target.asLong());
        final Entry last = REFUSED.get(key);
        final int refusals = last == null ? 1 : last.refusals() + 1;
        final long until = now + Math.min(MAX, FIRST << Math.min(refusals - 1, 8));
        REFUSED.put(key, new Entry(refusals, until));
        return until;
    }

    /**
     * Something went into the destination: the pause ends.
     */
    public static void accepted(final int colony, final BlockPos target)
    {
        REFUSED.remove(new Key(colony, target.asLong()));
    }

    /**
     * @return whether couriers leave the destination be at the moment.
     */
    public static boolean paused(final int colony, final BlockPos target, final long now)
    {
        final Entry entry = REFUSED.get(new Key(colony, target.asLong()));
        return entry != null && now < entry.until();
    }

    /**
     * @return the refusals in a row of the destination (for the tests).
     */
    public static int refusals(final int colony, final BlockPos target)
    {
        final Entry entry = REFUSED.get(new Key(colony, target.asLong()));
        return entry == null ? 0 : entry.refusals();
    }
}
