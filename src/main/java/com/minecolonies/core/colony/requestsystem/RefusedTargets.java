package com.minecolonies.core.colony.requestsystem;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Destinations that refused a delivery (full): couriers leave them be for a while instead of coming back at once with the same load (the refused
 * items are dumped, the delivery fails, the request is handed out again and a courier is sent again: a loop, r1 Fable F17). The pause doubles with
 * each refusal in a row ({@link #FIRST} ticks, up to {@link #MAX}) and ends at the first delivery that goes in. Keyed by world and dimension
 * ({@link RsWorldScope}), colony and position, so no pause leaks into another world or dimension. Held in memory only, dropped when a server starts or
 * stops; an entry that cannot be from this clock (a pause longer than {@link #MAX}) or that ran out long ago counts as none.
 */
public final class RefusedTargets
{
    /** The pause after the first refusal (1 minute). */
    public static final long FIRST = 1200L;

    /** The longest pause (5 minutes). */
    public static final long MAX = 6000L;

    /** An entry this long past its pause is forgotten: the next refusal counts as the first. */
    private static final long FORGET = 2 * MAX;

    /** Entries kept before the old ones are swept. */
    private static final int SWEEP_SIZE = 1024;

    private record Key(String world, int colony, long pos)
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
    public static long refused(@NotNull final Level level, final int colony, final BlockPos target)
    {
        return refused(RsWorldScope.of(level), colony, target, level.getGameTime());
    }

    /**
     * Something went into the destination: the pause ends.
     */
    public static void accepted(@NotNull final Level level, final int colony, final BlockPos target)
    {
        accepted(RsWorldScope.of(level), colony, target);
    }

    /**
     * @return whether couriers leave the destination be at the moment.
     */
    public static boolean paused(@NotNull final Level level, final int colony, final BlockPos target)
    {
        return paused(RsWorldScope.of(level), colony, target, level.getGameTime());
    }

    /**
     * A delivery to the destination was refused (pure: the world key and the time are given).
     *
     * @return the game time until which couriers leave it be.
     */
    public static long refused(@NotNull final String world, final int colony, final BlockPos target, final long now)
    {
        if (REFUSED.size() > SWEEP_SIZE)
        {
            REFUSED.values().removeIf(entry -> stale(entry, now));
        }
        final Key key = new Key(world, colony, target.asLong());
        final Entry last = REFUSED.get(key);
        final int refusals = last == null || stale(last, now) ? 1 : last.refusals() + 1;
        final long until = now + Math.min(MAX, FIRST << Math.min(refusals - 1, 8));
        REFUSED.put(key, new Entry(refusals, until));
        return until;
    }

    /**
     * Something went into the destination: the pause ends.
     */
    public static void accepted(@NotNull final String world, final int colony, final BlockPos target)
    {
        REFUSED.remove(new Key(world, colony, target.asLong()));
    }

    /**
     * @return whether couriers leave the destination be at the moment.
     */
    public static boolean paused(@NotNull final String world, final int colony, final BlockPos target, final long now)
    {
        final Key key = new Key(world, colony, target.asLong());
        final Entry entry = REFUSED.get(key);
        if (entry != null && stale(entry, now))
        {
            REFUSED.remove(key, entry);
            return false;
        }
        return entry != null && now < entry.until();
    }

    /**
     * @return the refusals in a row of the destination (for the tests).
     */
    public static int refusals(@NotNull final String world, final int colony, final BlockPos target)
    {
        final Entry entry = REFUSED.get(new Key(world, colony, target.asLong()));
        return entry == null ? 0 : entry.refusals();
    }

    /**
     * Forget every pause (a server starts or stops).
     */
    static void clear()
    {
        REFUSED.clear();
    }

    /**
     * @return true for an entry of another clock (its pause reaches further than any pause can) or one that ran out long ago.
     */
    private static boolean stale(final Entry entry, final long now)
    {
        return entry.until() - now > MAX || now - entry.until() > FORGET;
    }
}
