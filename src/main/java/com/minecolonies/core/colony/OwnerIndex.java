package com.minecolonies.core.colony;

import com.minecolonies.api.colony.IColony;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server side index from a player to the colonies they own, oldest colony first. It is built per level the first time
 * it is asked about that level and kept current through the colony manager's hooks (colony created, deleted, owner
 * changed, level unloaded), so finding the colonies of an owner costs the size of the answer, not a scan of all colonies.
 * Entries are only keys; the manager resolves them and drops the ones that no longer match.
 */
final class OwnerIndex
{
    /**
     * A colony of an owner.
     *
     * @param dimension the dimension.
     * @param id        the colony id.
     * @param founded   the founding time, the sort key.
     */
    record Entry(ResourceKey<Level> dimension, int id, long founded)
    {
    }

    private static final Comparator<Entry> OLDEST_FIRST = Comparator.comparingLong(Entry::founded)
                                                            .thenComparing(e -> e.dimension().identifier().toString())
                                                            .thenComparingInt(Entry::id);

    private final Map<UUID, List<Entry>>        byOwner = new HashMap<>();
    private final Set<ResourceKey<Level>>       levels  = new HashSet<>();
    @Nullable
    private       Object                        owner   = null;

    /**
     * Binds the index to a server instance; a different instance (the integrated server restarted) starts empty.
     *
     * @param server the current server.
     */
    synchronized void bind(@Nullable final Object server)
    {
        if (this.owner != server)
        {
            byOwner.clear();
            levels.clear();
            this.owner = server;
        }
    }

    synchronized boolean isIndexed(@NotNull final ResourceKey<Level> dimension)
    {
        return levels.contains(dimension);
    }

    /**
     * (Re)builds the entries of one level.
     *
     * @param dimension the level.
     * @param colonies  all colonies of the level.
     */
    synchronized void rebuild(@NotNull final ResourceKey<Level> dimension, @NotNull final Iterable<? extends IColony> colonies)
    {
        removeLevelEntries(dimension);
        levels.add(dimension);
        for (final IColony colony : colonies)
        {
            insert(colony);
        }
    }

    synchronized void dropLevel(@NotNull final ResourceKey<Level> dimension)
    {
        removeLevelEntries(dimension);
        levels.remove(dimension);
    }

    /**
     * Adds a colony under its current owner, if its level is indexed. Does nothing if it is in the index already.
     *
     * @param colony the colony.
     */
    synchronized void add(@NotNull final IColony colony)
    {
        if (colony.getDimension() != null && levels.contains(colony.getDimension()))
        {
            insert(colony);
        }
    }

    /**
     * Removes a colony from an owner.
     *
     * @param dimension the dimension of the colony.
     * @param id        the colony id.
     * @param ownerId   the owner it was indexed under.
     */
    synchronized void remove(@NotNull final ResourceKey<Level> dimension, final int id, @Nullable final UUID ownerId)
    {
        if (ownerId == null)
        {
            return;
        }
        final List<Entry> list = byOwner.get(ownerId);
        if (list == null)
        {
            return;
        }
        list.removeIf(e -> e.id() == id && e.dimension().equals(dimension));
        if (list.isEmpty())
        {
            byOwner.remove(ownerId);
        }
    }

    /**
     * @param ownerId the owner.
     * @return a copy of the owner's entries, oldest colony first.
     */
    synchronized List<Entry> entries(@NotNull final UUID ownerId)
    {
        final List<Entry> list = byOwner.get(ownerId);
        return list == null ? List.of() : new ArrayList<>(list);
    }

    private void insert(final IColony colony)
    {
        final UUID ownerId = colony.getPermissions().getOwner();
        final Entry entry = new Entry(colony.getDimension(), colony.getID(), colony.getFoundedTime());
        final List<Entry> list = byOwner.computeIfAbsent(ownerId, k -> new ArrayList<>());
        for (final Entry existing : list)
        {
            if (existing.id() == entry.id() && existing.dimension().equals(entry.dimension()))
            {
                return;
            }
        }
        list.add(entry);
        list.sort(OLDEST_FIRST);
    }

    private void removeLevelEntries(final ResourceKey<Level> dimension)
    {
        byOwner.values().forEach(list -> list.removeIf(e -> e.dimension().equals(dimension)));
        byOwner.values().removeIf(List::isEmpty);
    }
}
