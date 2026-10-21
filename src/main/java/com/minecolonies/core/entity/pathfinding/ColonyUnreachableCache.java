package com.minecolonies.core.entity.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Per-colony "cannot reach" cache, shared by the citizens of one colony. Only strong proof is shared: a search that tried
 * every node it could reach (at least {@link #MIN_PROOF_NODES}, at most {@link #MAX_PROOF_POSITIONS}) without reaching
 * the target. Such a search knows every standing position reachable from its start, so it proves the target unreachable
 * exactly for walkers standing on one of those positions (same world, same pathing abilities): a citizen behind a wall,
 * above a ravine or outside a closed yard is not in the set and still searches. Each proof keeps its own positions and
 * expiry ({@link #EXPIRY_TICKS} game ticks); a new proof never refreshes an older one. All proofs of a target end as soon
 * as a block in the small box around it changes (checked on lookup, loaded chunks only). Entries are keyed by colony
 * (walker's dimension and colony id, {@link Pathfinding.ColonyKey}), target block and pathing ability flags
 * ({@link PathingOptions#abilityFlags()}), so colonies and citizens with other abilities never share. A citizen's own
 * failed tries (the CA-11 back-off) are never shared. Server thread only.
 */
public final class ColonyUnreachableCache
{
    /**
     * Game ticks a proof lasts.
     */
    public static final int EXPIRY_TICKS = 400;

    /**
     * An exhausted search must have visited at least this many nodes to be shared.
     */
    public static final int MIN_PROOF_NODES = 500;

    /**
     * A proof keeps at most this many reachable positions (8 bytes each); a larger search is not shared.
     */
    public static final int MAX_PROOF_POSITIONS = 8192;

    /**
     * Targets per colony, and proofs per target, at most; the oldest are dropped first.
     */
    private static final int MAX_TARGETS_PER_COLONY = 32;
    private static final int MAX_PROOFS             = 4;

    /**
     * One proof: its time window and the sorted packed positions reachable from its start.
     */
    private record Proof(long created, long expires, long[] reachable)
    {
        boolean live(final long now)
        {
            return now < expires && now >= created;
        }
    }

    private record TargetKey(BlockPos target, int abilities)
    {
    }

    private record Entry(int blocks, List<Proof> proofs)
    {
    }

    private static final Map<Pathfinding.ColonyKey, Map<TargetKey, Entry>> CACHE = new HashMap<>();

    private ColonyUnreachableCache()
    {
    }

    /**
     * Records a proof that the target cannot be reached from the given reachable positions.
     *
     * @param level     the level of the target.
     * @param colony    the colony key, null does nothing.
     * @param target    the target block.
     * @param abilities the searching walker's pathing ability flags.
     * @param reachable sorted packed positions the exhausted search reached (see AbstractPathJob), null when it is no proof.
     * @return true if recorded.
     */
    public static synchronized boolean markUnreachable(
      final Level level, @Nullable final Pathfinding.ColonyKey colony, final BlockPos target, final int abilities, @Nullable final long[] reachable)
    {
        if (colony == null || target == null || reachable == null || reachable.length == 0 || reachable.length > MAX_PROOF_POSITIONS)
        {
            return false;
        }
        final Map<TargetKey, Entry> entries = CACHE.computeIfAbsent(colony, k -> new HashMap<>());
        final long now = level.getGameTime();
        final int blocks = blocksAround(level, target);
        final TargetKey key = new TargetKey(target.immutable(), abilities);
        final Entry old = entries.get(key);
        final List<Proof> proofs = new ArrayList<>();
        if (old != null && old.blocks() == blocks)
        {
            for (final Proof p : old.proofs())
            {
                if (p.live(now))
                {
                    proofs.add(p);
                }
            }
        }
        if (proofs.size() >= MAX_PROOFS)
        {
            proofs.remove(0);
        }
        proofs.add(new Proof(now, now + EXPIRY_TICKS, reachable));
        if (old == null && entries.size() >= MAX_TARGETS_PER_COLONY)
        {
            prune(now, entries);
            if (entries.size() >= MAX_TARGETS_PER_COLONY)
            {
                entries.remove(entries.keySet().iterator().next());
            }
        }
        entries.put(key, new Entry(blocks, List.copyOf(proofs)));
        return true;
    }

    /**
     * Whether a live proof of the colony shows the target unreachable from this start (the start is one of the proof's
     * reachable positions). Expired proofs, and every proof when the blocks around the target changed, are removed.
     *
     * @param level     the level of the target.
     * @param colony    the colony key, null is never cached.
     * @param target    the target block.
     * @param abilities the walker's pathing ability flags.
     * @param start     where the walker would start.
     * @return true while proven for this start.
     */
    public static synchronized boolean isUnreachable(
      final Level level, @Nullable final Pathfinding.ColonyKey colony, final BlockPos target, final int abilities, final BlockPos start)
    {
        if (colony == null || target == null || start == null)
        {
            return false;
        }
        final Map<TargetKey, Entry> entries = CACHE.get(colony);
        if (entries == null)
        {
            return false;
        }
        final TargetKey key = new TargetKey(target, abilities);
        final Entry entry = entries.get(key);
        if (entry == null)
        {
            return false;
        }
        final long now = level.getGameTime();
        if (blocksAround(level, target) != entry.blocks())
        {
            remove(colony, entries, key);
            return false;
        }
        final long packed = start.asLong();
        boolean anyLive = false;
        for (final Proof p : entry.proofs())
        {
            if (!p.live(now))
            {
                continue;
            }
            anyLive = true;
            if (Arrays.binarySearch(p.reachable(), packed) >= 0)
            {
                return true;
            }
        }
        if (!anyLive)
        {
            remove(colony, entries, key);
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

    private static void remove(final Pathfinding.ColonyKey colony, final Map<TargetKey, Entry> entries, final TargetKey key)
    {
        entries.remove(key);
        if (entries.isEmpty())
        {
            CACHE.remove(colony);
        }
    }

    private static void prune(final long now, final Map<TargetKey, Entry> entries)
    {
        for (final Iterator<Entry> it = entries.values().iterator(); it.hasNext(); )
        {
            if (it.next().proofs().stream().noneMatch(p -> p.live(now)))
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
