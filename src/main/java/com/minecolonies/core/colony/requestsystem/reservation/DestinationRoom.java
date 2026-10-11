package com.minecolonies.core.colony.requestsystem.reservation;

import com.ldtteam.structurize.api.compat.itemhandler.IItemHandler;
import com.minecolonies.api.colony.buildings.IBuilding;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * How much of an item a destination building can still take, for the space half of a reservation.
 * <p>
 * Counted as the courier will insert: empty slots, room in stacks of the same item, and slots whose content the building
 * is not waiting for (a delivery replaces those, they go back to the warehouse).
 */
public final class DestinationRoom
{
    private DestinationRoom()
    {
    }

    /**
     * @param destination the building.
     * @param sample      the item to place.
     * @return the room, {@link Integer#MAX_VALUE} when the building has no inventory to measure.
     */
    public static int roomFor(@NotNull final IBuilding destination, @NotNull final ItemStack sample)
    {
        final IItemHandler handler = destination.getItemHandlerCap();
        if (handler == null || sample.isEmpty())
        {
            return Integer.MAX_VALUE;
        }
        final int max = Math.max(1, sample.getMaxStackSize());
        long room = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++)
        {
            final ItemStack inSlot = handler.getStackInSlot(slot);
            if (inSlot.isEmpty())
            {
                room += max;
            }
            else if (ItemStack.isSameItemSameComponents(inSlot, sample))
            {
                room += Math.max(0, max - inSlot.getCount());
            }
            else if (!destination.isItemStackInRequest(inSlot))
            {
                room += max;
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, room);
    }
}
