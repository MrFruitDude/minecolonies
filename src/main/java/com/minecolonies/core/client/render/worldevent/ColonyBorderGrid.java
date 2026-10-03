package com.minecolonies.core.client.render.worldevent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The colony border overlay's chunk grid (the chunks around the player that are claimed, or ticketed while Ctrl is
 * held), and the cache that keeps it between frames.
 * <p>
 * The grid is (2·range+1)² chunk reads plus a claim lookup each, with {@code range = max(render distance,
 * maxColonySize)}, so about 1.7k lookups at the default size. It only changes when the player moves to another chunk,
 * Ctrl is pressed or released, the nearest colony or the level changes, a chunk arrives or leaves on the client, or
 * any claim changes ({@link com.minecolonies.api.colony.claim.ClaimRevision}). {@link Key} holds exactly those, so an
 * unchanged frame reuses the last grid instead of reading it again.
 * <p>
 * Pure (no Minecraft types), so it is unit tested without a client.
 */
public final class ColonyBorderGrid
{
    /** Whether the client holds a chunk (a not-loaded chunk is skipped, as an empty chunk was before). */
    @FunctionalInterface
    public interface Loaded
    {
        boolean loaded(int chunkX, int chunkZ);
    }

    /** The colony owning a chunk, 0 for none. */
    @FunctionalInterface
    public interface Owner
    {
        int owningColony(int chunkX, int chunkZ);
    }

    /** Whether the nearest colony has a ticket on a chunk. */
    @FunctionalInterface
    public interface Ticketed
    {
        boolean ticketed(int chunkX, int chunkZ);
    }

    /** One chunk of the grid and the colony its border is drawn for. */
    public record Cell(int chunkX, int chunkZ, int colonyId)
    {
    }

    /**
     * Everything the grid depends on.
     *
     * @param level         the client level (identity)
     * @param colony        the nearest colony view (identity)
     * @param tickets       the colony's ticketed chunk set while {@code showTickets}, else null
     * @param claimRevision {@link com.minecolonies.api.colony.claim.ClaimRevision#current()}
     * @param chunkRevision bumped on every client chunk load / unload
     */
    public record Key(Object level, Object colony, int playerChunkX, int playerChunkZ, boolean showTickets, int range, long claimRevision, long chunkRevision,
                      Object tickets)
    {
    }

    private ColonyBorderGrid()
    {
    }

    /**
     * The grid around the player's chunk: claimed chunks with their owner, or (Ctrl) the nearest colony's ticketed
     * chunks under its own id. Same cells as the border renderer read every frame before the cache.
     */
    public static List<Cell> compute(final int playerChunkX, final int playerChunkZ, final int range, final boolean showTickets, final int nearestColonyId,
      final Loaded loaded, final Owner owner, final Ticketed ticketed)
    {
        final List<Cell> out = new ArrayList<>();
        for (int dx = -range; dx <= range; dx++)
        {
            for (int dz = -range; dz <= range; dz++)
            {
                final int x = playerChunkX + dx;
                final int z = playerChunkZ + dz;
                if (!loaded.loaded(x, z))
                {
                    continue;
                }
                if (!showTickets)
                {
                    final int colony = owner.owningColony(x, z);
                    if (colony != 0)
                    {
                        out.add(new Cell(x, z, colony));
                    }
                }
                else if (ticketed.ticketed(x, z))
                {
                    out.add(new Cell(x, z, nearestColonyId));
                }
            }
        }
        return out;
    }

    /** The last grid (or anything built from it) and the key it was built for. */
    public static final class Cache<V>
    {
        private Key key;
        private V value;
        private int builds;

        /** The value for {@code key}: the cached one when the key is unchanged, else a fresh {@code build}. */
        public V get(final Key key, final Supplier<V> build)
        {
            if (value == null || !Objects.equals(this.key, key))
            {
                value = build.get();
                this.key = key;
                builds++;
            }
            return value;
        }

        /** Forget the grid (logout, level change). */
        public void clear()
        {
            key = null;
            value = null;
        }

        /** How many times a grid was built (tests). */
        public int builds()
        {
            return builds;
        }
    }
}
