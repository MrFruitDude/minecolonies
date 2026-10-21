package com.minecolonies.core.entity.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Per-colony "cannot reach" cache, keyed by target block and shared by all citizens of one colony. When one citizen
 * finds a target unreachable (its search ran out of nodes to try, or it tripped the CA-11 back-off), the colony's other
 * citizens do not search for it again for a short while. An entry ends after {@link #EXPIRY_TICKS} game ticks, or as soon
 * as a block in the small box around the target changes (checked on lookup, loaded chunks only). Colonies never share
 * entries: the key is the colony's dimension and id ({@link Pathfinding.ColonyKey}). Server thread only.
 */
public final class ColonyUnreachableCache
{
    /**
     * Game ticks an entry lasts.
     */
    public static final int EXPIRY_TICKS = 400;

    /**
     * Entries per colony at most; the oldest are dropped first.
     */
    private static final int MAX_ENTRIES_PER_COLONY = 256;

    private record Entry(long created, long expires, int blocks)
    {
    }

    private static final Map<Pathfinding.ColonyKey, Map<BlockPos, Entry>> CACHE = new HashMap<>();

    private ColonyUnreachableCache()
    {
    }

    /**
     * Records a target as unreachable for the colony.
     *
     * @param level  the level of the target.
     * @param colony the colony key, null does nothing.
     * @param target the target block.
     */
    public static synchronized void markUnreachable(final Level level, @Nullable final Pathfinding.ColonyKey colony, final BlockPos target)
    {
        if (colony == null || target == null)
        {
            return;
        }
        final Map<BlockPos, Entry> entries = CACHE.computeIfAbsent(colony, k -> new HashMap<>());
        if (entries.size() >= MAX_ENTRIES_PER_COLONY)
        {
            prune(level.getGameTime(), entries);
            if (entries.size() >= MAX_ENTRIES_PER_COLONY)
            {
                entries.remove(entries.keySet().iterator().next());
            }
        }
        final long now = level.getGameTime();
        entries.put(target.immutable(), new Entry(now, now + EXPIRY_TICKS, blocksAround(level, target)));
    }

    /**
     * Whether the colony currently knows the target as unreachable. An expired entry, or one whose surrounding blocks
     * changed, is removed and reported as reachable.
     *
     * @param level  the level of the target.
     * @param colony the colony key, null is never cached.
     * @param target the target block.
     * @return true while cached.
     */
    public static synchronized boolean isUnreachable(final Level level, @Nullable final Pathfinding.ColonyKey colony, final BlockPos target)
    {
        if (colony == null || target == null)
        {
            return false;
        }
        final Map<BlockPos, Entry> entries = CACHE.get(colony);
        if (entries == null)
        {
            return false;
        }
        final Entry entry = entries.get(target);
        if (entry == null)
        {
            return false;
        }
        final long now = level.getGameTime();
        if (now >= entry.expires() || now < entry.created() || blocksAround(level, target) != entry.blocks())
        {
            entries.remove(target);
            if (entries.isEmpty())
            {
                CACHE.remove(colony);
            }
            return false;
        }
        return true;
    }

    /**
     * Drops every entry (server stop).
     */
    public static synchronized void clear()
    {
        CACHE.clear();
    }

    private static void prune(final long now, final Map<BlockPos, Entry> entries)
    {
        for (final Iterator<Entry> it = entries.values().iterator(); it.hasNext(); )
        {
            final Entry e = it.next();
            if (now >= e.expires() || now < e.created())
            {
                it.remove();
            }
        }
    }

    /**
     * A hash of the block states in the 3 x 4 x 3 box around the target (one below to two above), unloaded chunks
     * counted as a marker; never loads a chunk.
     */
    private static int blocksAround(final Level level, final BlockPos target)
    {
        int hash = 1;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dy = -1; dy <= 2; dy++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    pos.set(target.getX() + dx, target.getY() + dy, target.getZ() + dz);
                    hash = 31 * hash + (level.hasChunkAt(pos) ? Block.getId(level.getBlockState(pos)) : -1);
                }
            }
        }
        return hash;
    }
}
