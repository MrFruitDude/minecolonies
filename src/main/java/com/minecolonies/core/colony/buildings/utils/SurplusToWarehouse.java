package com.minecolonies.core.colony.buildings.utils;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.util.ItemMover;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import com.ldtteam.structurize.api.compat.itemhandler.IItemHandler;

import java.util.function.Predicate;

/**
 * Round 11 (W4-06, decision D11 b): goods a hut no longer needs go to the colony's warehouse, so other huts' requests find them there. Used for the soil a
 * builder dug: when a project's dig phase ends, its surplus soil leaves the builders' huts. The move is between two storages of the same colony (like the
 * warehouse's own sorting) and loses nothing: what the warehouse cannot take stays in the hut. Server thread only; both buildings must be loaded.
 */
public final class SurplusToWarehouse
{
    /**
     * Soil: what a fill needs (the dirt item tag).
     */
    public static final Predicate<ItemStack> SOIL = stack -> !stack.isEmpty() && stack.is(ItemTags.DIRT);

    private SurplusToWarehouse()
    {
    }

    /**
     * Moves up to {@code max} matching items out of a building's inventory (hut block and racks) into the colony's warehouse closest to it.
     *
     * @param colony the colony; the building must be one of its own.
     * @param from   the building.
     * @param what   which items.
     * @param max    how many at most.
     * @return how many items moved (0 when the colony has no built, loaded warehouse, or the building is not loaded or not the colony's).
     */
    public static int toWarehouse(final IColony colony, final IBuilding from, final Predicate<ItemStack> what, final int max)
    {
        if (colony == null || from == null || max <= 0 || colony.getWorld() == null || colony.getWorld().isClientSide() || from.getColony() != colony
              || colony.getServerBuildingManager().getBuilding(from.getID()) != from || from instanceof IWareHouse
              || !WorldUtil.isBlockLoaded(colony.getWorld(), from.getPosition()))
        {
            return 0;
        }
        final IWareHouse warehouse = colony.getServerBuildingManager().getClosestWarehouseInColony(from.getPosition());
        if (warehouse == null || warehouse.getColony() != colony || !WorldUtil.isBlockLoaded(colony.getWorld(), warehouse.getPosition()))
        {
            return 0;
        }
        final IItemHandler source = from.getItemHandlerCap();
        final IItemHandler target = warehouse.getItemHandlerCap();
        if (source == null || target == null)
        {
            return 0;
        }
        final int moved = ItemMover.move(source, target, what, max);
        if (moved > 0)
        {
            Log.getLogger().debug("Colony {}: {} surplus items from {} to the warehouse at {}", colony.getID(), moved, from.getPosition(), warehouse.getPosition());
        }
        return moved;
    }
}
