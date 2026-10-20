package com.minecolonies.core.colony.labour;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * The positions where somebody placed a block a labourer could otherwise take (a log, a sapling, sand, dirt, gravel, stone), recorded from the placement events of
 * the game from the day this record exists (saved with the colony). Labourers never take such a block. A block placed before, or by a path that posts no event
 * (commands, pistons, pasted structures), is not known here.
 * <p>
 * The record is bounded: once it would hold more than {@link #MAX} positions it stops growing and from then on every position counts as placed (nothing is taken out
 * of the world for the colony until the record is cleared), so a full record never lets a placed block be taken. Server thread only.
 */
public final class LabourPlacements
{
    /**
     * The most positions the record keeps.
     */
    public static final int MAX = 1 << 18;

    private final LongOpenHashSet placed = new LongOpenHashSet();
    private       boolean         full;

    /**
     * @param pos a packed position ({@code BlockPos.asLong}).
     * @return whether a block placed there is on record, or the record is full (then every position counts as placed).
     */
    public boolean placed(final long pos)
    {
        return full || placed.contains(pos);
    }

    /**
     * @return whether the record is full (nothing is taken out of the world).
     */
    public boolean full()
    {
        return full;
    }

    /**
     * @return how many positions are on record.
     */
    public int size()
    {
        return placed.size();
    }

    /**
     * Records a placement.
     *
     * @return true if the record changed.
     */
    public boolean add(final long pos)
    {
        if (full || placed.contains(pos))
        {
            return false;
        }
        if (placed.size() >= MAX)
        {
            full = true;
            return true;
        }
        return placed.add(pos);
    }

    /**
     * Forgets a position (its block was broken by a player).
     *
     * @return true if the record changed.
     */
    public boolean remove(final long pos)
    {
        return placed.remove(pos);
    }

    public long[] toArray()
    {
        final long[] array = placed.toLongArray();
        java.util.Arrays.sort(array);
        return array;
    }

    public void read(final long[] positions, final boolean wasFull)
    {
        placed.clear();
        full = wasFull;
        for (final long pos : positions)
        {
            if (placed.size() >= MAX)
            {
                full = true;
                break;
            }
            placed.add(pos);
        }
    }
}
