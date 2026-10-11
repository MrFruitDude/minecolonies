package com.minecolonies.core.colony.storage;

import com.minecolonies.api.crafting.ItemStorage;
import org.jetbrains.annotations.NotNull;

/**
 * Hears about every change of a warehouse's stock and slots, to keep the colony-wide totals current.
 */
interface StockSink
{
    /**
     * @param key   the item.
     * @param delta the change of the count (negative for a loss).
     */
    void itemsChanged(@NotNull ItemStorage key, int delta);

    /**
     * @param totalDelta the change of the total slot count.
     * @param freeDelta  the change of the free slot count.
     */
    void slotsChanged(int totalDelta, int freeDelta);
}
