package com.minecolonies.core.entity.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Per-colony "cannot reach" cache, keyed by target block and the area the search started from, shared by the citizens
 * of one colony. Only strong proof is shared: a search that tried every node it could reach (at least
 * {@link #MIN_PROOF_NODES} of them, so a citizen boxed in at its start proves nothing about the target) without reaching
 * the target. Such a result says "unreachable from around this start", so another citizen of the colony is refused only
 * when it starts within {@link #START_RADIUS} blocks of a start that proved it; citizens elsewhere still search. A
 * citizen's own failed tries (the CA-11 back-off) are never shared. An entry ends after {@link #EXPIRY_TICKS} game
 * ticks, or as soon as a block in the small box around the target changes (checked on lookup, loaded chunks only).
 * Colonies never share entries: the key is the walker's dimension and colony id ({@link Pathfinding.ColonyKey}).
 * Server thread only.
 */
public final class ColonyUnreachableCache
{
    /**
     * Game ticks an entry lasts.
     */
    public static final int EXPIRY_TICKS = 400;

    /**
     * An exhausted search must have visited at least this many nodes to be shared.
     */
    public static final int MIN_PROOF_NODES = 500;

    /**
     * A start proves the target unreachable for other starts within this distance (blocks, straight line).
     */
    public static final int START_RADIUS = 16;

    /**
     * Entries per colony at most; the oldest are dropped first.
     */
    private static final int MAX_ENTRIES_PER_COLONY = 256;

    /**
     * Proving starts kept per target.
     */
    private static final int MAX_STARTS = 4;

    private record Entry(long created, long expires, int blocks, List<BlockPos> starts)
    {
    }

    private static final Map<Pathfinding.ColonyKey, Map<BlockPos, Entry>> CACHE = new HashMap<>();

    private ColonyUnreachableCache()
    {
    }

    /**
     * Records a target as unreachable from around a start, if the search is strong proof (see the class comment).
     *
     * @param level     the level of the target.
     * @param colony    the colony key, null does nothing.
     * @param target    the target block.
     * @param start     where the search started.
     * @param exhausted whether the search tried every node it could reach.
     * @param nodes     nodes the search visited.
     * @return true if recorded.
     */
    public static synchronized boolean markUnreachable(
      final Level level, @Nullable final Pathfinding.ColonyKey colony, final BlockPos target, final BlockPos start, final boolean exhausted, final int nodes)
    {
        if (colony == null || target == null || start == null || !exhausted || nodes < MIN_PROOF_NODES)
        {
            return false;
        }
        final Map<BlockPos, Entry> entries = CACHE.computeIfAbsent(colony, k -> new HashMap<>());
        final long now = level.getGameTime();
        final int blocks = blocksAround(level, target);
        final Entry old = entries.get(target);
        final List<BlockPos> starts = new ArrayList<>();
        if (old != null && now < old.expires() && now >= old.created() && old.blocks() == blocks)
        {
            starts.addAll(old.starts());
        }
        if (starts.size() >= MAX_STARTS)
        {
            starts.remove(0);
        }
        starts.add(start.immutable());
        if (old == null && entries.size() >= MAX_ENTRIES_PER_COLONY)
        {
            prune(now, entries);
            if (entries.size() >= MAX_ENTRIES_PER_COLONY)
            {
                entries.remove(entries.keySet().iterator().next());
            }
        }
        entries.put(target.immutable(), new Entry(now, now + EXPIRY_TICKS, blocks, List.copyOf(starts)));
        return true;
    }

    /**
     * Whether the colony currently knows the target as unreachable from this start: a proving start lies within
     * {@link #START_RADIUS}. An expired entry, or one whose surrounding blocks changed, is removed and reported as
     * reachable.
     *
     * @param level  the level of the target.
     * @param colony the colony key, null is never cached.
     * @param target the target block.
     * @param start  where the walker would start.
     * @return true while cached for this start.
     */
    public static synchronized boolean isUnreachable(final Level level, @Nullable final Pathfinding.ColonyKey colony, final BlockPos target, final BlockPos start)
    {
        if (colony == null || target == null || start == null)
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
        for (final BlockPos proved : entry.starts())
        {
            if (proved.distSqr(start) <= START_RADIUS * START_RADIUS)
            {
                return true;
            }
        }
        return false;
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
