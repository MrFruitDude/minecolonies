package com.minecolonies.core.entity.ai.workers.util;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Decides when a structure worker who seems shut in by the walls of his structure is put out at his hut (the shut-in rescue). Pure (no world), so
 * the rules can be tested:
 * <ul>
 *   <li>only a worker who stands in the box of his structure (a worker without a structure, on the walk between hut and site, is never shut in);</li>
 *   <li>never while he gets on: moved {@link #PROGRESS_BLOCKS} or more since the last check (he walks a path, he climbs), or did work (placed or
 *       broke a block: a helper who places the wall around him block after block from one spot stands still, but is not stuck, r1 run 015);</li>
 *   <li>only when the search finds no way from him to his hut, {@link #STRIKES} checks in a row;</li>
 *   <li>once per structure in {@link #COOLDOWN} ticks: shut in again soon after a rescue is a loop (the search misses the way out, he walks back
 *       in), so he is marked blocked once instead and stays where he is.</li>
 * </ul>
 * RESCUE says he is shut in; the caller first tries a way out that is no jump (a tower of scaffolding over the wall, a wait while a player watches) and
 * tells the gate with {@link #rescued} only when he is put out: a worker who climbs out does not use up the rescue of the structure.
 */
public final class ShutInGate
{
    public enum Decision
    {
        NONE, RESCUE, BLOCKED
    }

    /** Checks in a row that find him cut off before he is put out. */
    public static final int STRIKES = 3;

    /** Ticks after a rescue out of a structure before the next one out of the same structure (5 minutes). */
    public static final long COOLDOWN = 6000L;

    /** Blocks moved since the last check that count as getting on. */
    public static final double PROGRESS_BLOCKS = 1.0D;

    private int strikes = 0;
    private boolean blocked = false;
    private final Map<BlockPos, Long> rescuedAt = new HashMap<>();

    /**
     * One check (the AI calls it every 40 ticks), for a worker who did no work since the last check.
     *
     * @see #check(boolean, double, boolean, BooleanSupplier, BlockPos, long)
     */
    public Decision check(final boolean inBox, final double moved, final BooleanSupplier cutOff, final BlockPos box, final long now)
    {
        return check(inBox, moved, false, cutOff, box, now);
    }

    /**
     * One check (the AI calls it every 40 ticks).
     *
     * @param inBox  whether he stands in the box of his structure (false without a structure).
     * @param moved  how far he moved since the last check, in blocks.
     * @param worked whether he placed or broke a block since the last check.
     * @param cutOff whether he is cut off from his hut (asked only when it matters: a search).
     * @param box    the structure (the low corner of its box).
     * @param now    the game time.
     * @return RESCUE when he is shut in (every {@link #STRIKES} checks while he is), BLOCKED once when he is shut in again within the cooldown of a
     *     rescue out of the same structure, else NONE.
     */
    public Decision check(final boolean inBox, final double moved, final boolean worked, final BooleanSupplier cutOff, final BlockPos box, final long now)
    {
        if (!inBox)
        {
            strikes = 0;
            blocked = false;
            return Decision.NONE;
        }
        if (moved >= PROGRESS_BLOCKS || worked)
        {
            strikes = 0;
            return Decision.NONE;
        }
        if (!cutOff.getAsBoolean())
        {
            strikes = 0;
            blocked = false;
            return Decision.NONE;
        }
        if (++strikes < STRIKES)
        {
            return Decision.NONE;
        }
        strikes = 0;
        final Long last = rescuedAt.get(box);
        if (last != null && now - last < COOLDOWN)
        {
            if (blocked)
            {
                return Decision.NONE;
            }
            blocked = true;
            return Decision.BLOCKED;
        }
        blocked = false;
        return Decision.RESCUE;
    }

    /**
     * He was put out of the structure (the jump): the next shut-in in the same structure within the cooldown is a loop.
     *
     * @param box the structure (the low corner of its box).
     * @param now the game time.
     */
    public void rescued(final BlockPos box, final long now)
    {
        rescuedAt.entrySet().removeIf(e -> now - e.getValue() >= COOLDOWN);
        rescuedAt.put(box.immutable(), now);
    }

    /**
     * @return whether he is marked blocked (shut in again within the cooldown, no rescue).
     */
    public boolean isBlocked()
    {
        return blocked;
    }
}
