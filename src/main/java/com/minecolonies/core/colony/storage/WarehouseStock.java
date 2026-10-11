package com.minecolonies.core.colony.storage;

import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.core.tileentities.TileEntityRack;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * What one warehouse's racks hold, kept up to date from the racks' change events: the warehouse rack index feeds it a
 * rack when the rack's content changed and the whole set when the index is rebuilt. Every change is also told to the
 * colony storage ({@link StockSink}), so its totals follow without a scan. Server thread only.
 */
public final class WarehouseStock
{
    /**
     * What a rack contributed the last time it was looked at.
     */
    private record Contribution(Object2IntOpenHashMap<ItemStorage> items, int slots, int freeSlots, boolean complete)
    {
    }

    private final StockTable table = new StockTable();

    private final Map<TileEntityRack, Contribution> racks = new IdentityHashMap<>();

    @Nullable
    private StockSink sink;

    private int     slots;
    private int     freeSlots;
    private boolean unloadedRacks;

    /**
     * Bumped on every change.
     */
    private long version;

    /**
     * @return this warehouse's counts.
     */
    @NotNull
    public StockTable table()
    {
        return table;
    }

    /**
     * @return rack slots of the loaded racks.
     */
    public int slots()
    {
        return slots;
    }

    /**
     * @return free rack slots of the loaded racks.
     */
    public int freeSlots()
    {
        return freeSlots;
    }

    /**
     * @return true when some container of the warehouse was not loaded at the last rebuild (figures are a lower bound).
     */
    public boolean hasUnloadedRacks()
    {
        return unloadedRacks;
    }

    /**
     * @return the change counter.
     */
    public long version()
    {
        return version;
    }

    /**
     * Starts telling the sink about changes, and tells it what is held now.
     *
     * @param newSink the colony storage.
     */
    void attach(@NotNull final StockSink newSink)
    {
        if (sink == newSink)
        {
            return;
        }
        detach();
        sink = newSink;
        table.forEach(newSink::itemsChanged);
        newSink.slotsChanged(slots, freeSlots);
    }

    /**
     * Stops telling the sink, taking back what it was told.
     */
    void detach()
    {
        final StockSink old = sink;
        if (old == null)
        {
            return;
        }
        table.forEach((key, count) -> old.itemsChanged(key, -count));
        old.slotsChanged(-slots, -freeSlots);
        sink = null;
    }

    /**
     * Replaces everything: the index was rebuilt.
     *
     * @param loadedRacks the racks of the warehouse that are loaded (null entries are skipped).
     * @param unloaded    true when some container position was not loaded.
     */
    public void rebuild(@NotNull final TileEntityRack[] loadedRacks, final boolean unloaded)
    {
        final StockSink target = sink;
        if (target != null)
        {
            table.forEach((key, count) -> target.itemsChanged(key, -count));
            target.slotsChanged(-slots, -freeSlots);
        }
        table.clear();
        racks.clear();
        slots = 0;
        freeSlots = 0;
        unloadedRacks = unloaded;
        for (final TileEntityRack rack : loadedRacks)
        {
            if (rack != null && !racks.containsKey(rack))
            {
                final Contribution contribution = read(rack);
                racks.put(rack, contribution);
                apply(contribution, 1, false);
            }
        }
        if (target != null)
        {
            table.forEach(target::itemsChanged);
            target.slotsChanged(slots, freeSlots);
        }
        version++;
    }

    /**
     * A rack's content (or size) changed.
     *
     * @param rack the rack.
     */
    public void rackChanged(@NotNull final TileEntityRack rack)
    {
        final Contribution before = racks.get(rack);
        if (before == null)
        {
            return;
        }
        final Contribution after = read(rack);
        racks.put(rack, after);

        for (final Object2IntMap.Entry<ItemStorage> old : before.items().object2IntEntrySet())
        {
            final int now = after.items().getInt(old.getKey());
            if (now != old.getIntValue())
            {
                change(old.getKey(), now - old.getIntValue());
            }
        }
        for (final Object2IntMap.Entry<ItemStorage> added : after.items().object2IntEntrySet())
        {
            if (!before.items().containsKey(added.getKey()))
            {
                change(added.getKey(), added.getIntValue());
            }
        }
        final int slotDelta = after.slots() - before.slots();
        final int freeDelta = after.freeSlots() - before.freeSlots();
        if (slotDelta != 0 || freeDelta != 0)
        {
            slots += slotDelta;
            freeSlots += freeDelta;
            if (sink != null)
            {
                sink.slotsChanged(slotDelta, freeDelta);
            }
        }
        version++;
    }

    /**
     * Forgets everything (the building is gone).
     */
    public void clear()
    {
        rebuild(new TileEntityRack[0], false);
    }

    private void change(final ItemStorage key, final int delta)
    {
        final int applied = table.add(key, delta);
        if (applied != 0 && sink != null)
        {
            sink.itemsChanged(key, applied);
        }
    }

    private void apply(final Contribution contribution, final int sign, final boolean tellSink)
    {
        for (final Object2IntMap.Entry<ItemStorage> entry : contribution.items().object2IntEntrySet())
        {
            table.add(entry.getKey(), sign * entry.getIntValue());
        }
        slots += sign * contribution.slots();
        freeSlots += sign * contribution.freeSlots();
    }

    private static Contribution read(final TileEntityRack rack)
    {
        final Object2IntOpenHashMap<ItemStorage> items = new Object2IntOpenHashMap<>(Math.max(2, rack.getAllContent().size()));
        for (final Map.Entry<ItemStorage, Integer> entry : rack.getAllContent().entrySet())
        {
            items.put(entry.getKey(), entry.getValue().intValue());
        }
        return new Contribution(items, rack.getInventory().getSlots(), rack.getFreeSlots(), true);
    }
}
