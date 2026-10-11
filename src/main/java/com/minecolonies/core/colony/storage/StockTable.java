package com.minecolonies.core.colony.storage;

import com.minecolonies.api.crafting.ItemStorage;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Item counts, exact key (item, damage, components) to count, grouped by item so an item's variants are found without
 * looking at the other items. Server thread only.
 */
public final class StockTable
{
    private final Map<Item, Object2IntOpenHashMap<ItemStorage>> byItem = new HashMap<>();

    private long items;
    private int  distinct;

    /**
     * Changes the count of an item.
     *
     * @param key   the item.
     * @param delta the change; the count never goes below zero.
     * @return the change that was applied (it differs from delta only when the count would have gone negative).
     */
    public int add(@NotNull final ItemStorage key, final int delta)
    {
        if (delta == 0)
        {
            return 0;
        }
        final Object2IntOpenHashMap<ItemStorage> variants = byItem.computeIfAbsent(key.getItem(), item -> new Object2IntOpenHashMap<>());
        final int before = variants.getInt(key);
        final int after = Math.max(0, before + delta);
        if (after == before)
        {
            return 0;
        }
        if (after == 0)
        {
            variants.removeInt(key);
            distinct--;
            if (variants.isEmpty())
            {
                byItem.remove(key.getItem());
            }
        }
        else
        {
            if (before == 0)
            {
                distinct++;
            }
            variants.put(key, after);
        }
        items += after - before;
        return after - before;
    }

    /**
     * @param key the item, compared exactly.
     * @return its count.
     */
    public int get(@NotNull final ItemStorage key)
    {
        final Object2IntOpenHashMap<ItemStorage> variants = byItem.get(key.getItem());
        return variants == null ? 0 : variants.getInt(key);
    }

    /**
     * @param predicate which items.
     * @param upTo      stop at this amount.
     * @return the count of the items matching the predicate.
     */
    public int count(@NotNull final Predicate<ItemStack> predicate, final int upTo)
    {
        int total = 0;
        for (final Object2IntOpenHashMap<ItemStorage> variants : byItem.values())
        {
            for (final Object2IntMap.Entry<ItemStorage> entry : variants.object2IntEntrySet())
            {
                if (predicate.test(entry.getKey().getItemStack()))
                {
                    total += entry.getIntValue();
                    if (total >= upTo)
                    {
                        return total;
                    }
                }
            }
        }
        return total;
    }

    /**
     * Like {@link #count} but keeps {@code keep} of every distinct item back (a warehouse that must always hold some).
     *
     * @param predicate which items.
     * @param keep      how many of each distinct item to leave out.
     * @param upTo      stop at this amount.
     * @return the count above the kept amounts.
     */
    public int countKeeping(@NotNull final Predicate<ItemStack> predicate, final int keep, final int upTo)
    {
        int total = 0;
        for (final Object2IntOpenHashMap<ItemStorage> variants : byItem.values())
        {
            for (final Object2IntMap.Entry<ItemStorage> entry : variants.object2IntEntrySet())
            {
                if (predicate.test(entry.getKey().getItemStack()))
                {
                    total += Math.max(0, entry.getIntValue() - keep);
                    if (total >= upTo)
                    {
                        return total;
                    }
                }
            }
        }
        return total;
    }

    /**
     * @param item      the item type.
     * @param predicate which variants of the item.
     * @param upTo      stop at this amount.
     * @return the count of the variants of the item that match.
     */
    public int countOfItem(@NotNull final Item item, @NotNull final Predicate<ItemStack> predicate, final int upTo)
    {
        final Object2IntOpenHashMap<ItemStorage> variants = byItem.get(item);
        if (variants == null)
        {
            return 0;
        }
        int total = 0;
        for (final Object2IntMap.Entry<ItemStorage> entry : variants.object2IntEntrySet())
        {
            if (predicate.test(entry.getKey().getItemStack()))
            {
                total += entry.getIntValue();
                if (total >= upTo)
                {
                    return total;
                }
            }
        }
        return total;
    }

    /**
     * @return all items held.
     */
    public long items()
    {
        return items;
    }

    /**
     * @return the number of distinct keys.
     */
    public int distinct()
    {
        return distinct;
    }

    /**
     * @return true when nothing is held.
     */
    public boolean isEmpty()
    {
        return distinct == 0;
    }

    /**
     * @return a copy: exact key to count.
     */
    @NotNull
    public Map<ItemStorage, Integer> snapshot()
    {
        final Map<ItemStorage, Integer> copy = new LinkedHashMap<>();
        for (final Object2IntOpenHashMap<ItemStorage> variants : byItem.values())
        {
            for (final Object2IntMap.Entry<ItemStorage> entry : variants.object2IntEntrySet())
            {
                copy.put(entry.getKey(), entry.getIntValue());
            }
        }
        return copy;
    }

    /**
     * Visits every key.
     *
     * @param visitor receives the key and its count.
     */
    public void forEach(@NotNull final java.util.function.ObjIntConsumer<ItemStorage> visitor)
    {
        for (final Object2IntOpenHashMap<ItemStorage> variants : byItem.values())
        {
            for (final Object2IntMap.Entry<ItemStorage> entry : variants.object2IntEntrySet())
            {
                visitor.accept(entry.getKey(), entry.getIntValue());
            }
        }
    }

    /**
     * Empties the table.
     */
    public void clear()
    {
        byItem.clear();
        items = 0;
        distinct = 0;
    }
}
