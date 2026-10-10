package com.minecolonies.core.util;

import com.ldtteam.structurize.api.compat.itemhandler.IItemHandler;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * Moves items between two inventories without losing any: what the target does not take goes back to the source.
 */
public final class ItemMover
{
    private ItemMover()
    {
    }

    /**
     * Moves up to the given amount of matching items.
     *
     * @param from      the inventory to take from.
     * @param to        the inventory to put into.
     * @param predicate which items to move.
     * @param amount    how many to move at most.
     * @return how many items moved.
     */
    public static int move(final IItemHandler from, final IItemHandler to, final Predicate<ItemStack> predicate, final int amount)
    {
        int moved = 0;
        for (int slot = 0; slot < from.getSlots() && moved < amount; slot++)
        {
            final ItemStack inSlot = from.getStackInSlot(slot);
            if (ItemStackUtils.isEmpty(inSlot) || !predicate.test(inSlot))
            {
                continue;
            }
            final ItemStack extracted = from.extractItem(slot, Math.min(amount - moved, inSlot.getCount()), false);
            if (ItemStackUtils.isEmpty(extracted))
            {
                continue;
            }
            final ItemStack rest = InventoryUtils.transferItemStackIntoNextBestSlotInItemHandlerWithResult(extracted, to);
            moved += extracted.getCount() - rest.getCount();
            if (!ItemStackUtils.isEmpty(rest))
            {
                final ItemStack back = from.insertItem(slot, rest, false);
                if (!ItemStackUtils.isEmpty(back))
                {
                    InventoryUtils.transferItemStackIntoNextBestSlotInItemHandlerWithResult(back, from);
                }
                break;
            }
        }
        return moved;
    }
}
