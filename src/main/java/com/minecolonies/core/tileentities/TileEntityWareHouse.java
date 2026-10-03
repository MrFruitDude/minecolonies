package com.minecolonies.core.tileentities;

import com.minecolonies.api.inventory.InventoryCitizen;
import com.ldtteam.structurize.api.util.Tuple;
import com.minecolonies.api.tileentities.AbstractTileEntityRack;
import com.minecolonies.api.tileentities.AbstractTileEntityWareHouse;
import com.minecolonies.api.tileentities.MinecoloniesTileEntities;
import com.minecolonies.api.util.*;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static com.minecolonies.api.util.constant.Constants.TICKS_FIVE_MIN;
import static com.minecolonies.api.util.constant.TranslationConstants.*;
import static com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse.MAX_STORAGE_UPGRADE;

/**
 * Class which handles the tileEntity of our colony warehouse.
 */
public class TileEntityWareHouse extends AbstractTileEntityWareHouse
{
    /**
     * Time of last sent notifications.
     */
    private long lastNotification                   = 0;

    public TileEntityWareHouse(final BlockPos pos, final BlockState state)
    {
        super(MinecoloniesTileEntities.WAREHOUSE.get(), pos, state);
        inWarehouse = true;
    }

    @Override
    public boolean hasMatchingItemStackInWarehouse(@NotNull final Predicate<ItemStack> itemStackSelectionPredicate, int count)
    {
        final IBuilding building = getBuilding();
        if (building != null)
        {
            return rackIndex(building).hasMatching(level, itemStackSelectionPredicate, count);
        }

        return false;
    }

    @Override
    public boolean hasMatchingItemStackInWarehouse(@NotNull final ItemStack itemStack, final int count, final boolean ignoreNBT)
    {
        return hasMatchingItemStackInWarehouse(itemStack, count, ignoreNBT, 0);
    }

    @Override
    public boolean hasMatchingItemStackInWarehouse(@NotNull final ItemStack itemStack, final int count, final boolean ignoreNBT, final boolean ignoreDamage, final int leftOver)
    {
        return rackIndex(getBuilding()).hasMatching(level, itemStack, count, ignoreNBT, ignoreDamage, leftOver);
    }

    @Override
    public boolean hasMatchingItemStackInWarehouse(@NotNull final ItemStack itemStack, final int count, final boolean ignoreNBT, final int leftOver)
    {
        return hasMatchingItemStackInWarehouse(itemStack, count, ignoreNBT, true, leftOver);
    }

    @Override
    @NotNull
    public List<Tuple<ItemStack, BlockPos>> getMatchingItemStacksInWarehouse(@NotNull final Predicate<ItemStack> itemStackSelectionPredicate)
    {
        List<Tuple<ItemStack, BlockPos>> found = new ArrayList<>();
        
        final IBuilding building = getBuilding();
        if (building != null)
        {
            for (final Map.Entry<BlockPos, TileEntityRack> entry : rackIndex(building).nonEmptyRacks(level))
            {
                final TileEntityRack rack = entry.getValue();
                WarehouseRackIndex.rackProbes++;
                if (rack.getItemCount(itemStackSelectionPredicate) > 0)
                {
                    for (final ItemStack stack : (InventoryUtils.filterItemHandler(rack.getInventory(), itemStackSelectionPredicate)))
                    {
                        found.add(new Tuple<>(stack, entry.getKey()));
                    }
                }
            }
        }
        return found;
    }

    @Override
    public void dumpInventoryIntoWareHouse(@NotNull final InventoryCitizen inventoryCitizen)
    {
        for (int i = 0; i < inventoryCitizen.getSlots(); i++)
        {
            final ItemStack stack = inventoryCitizen.getStackInSlot(i);
            if (ItemStackUtils.isEmpty(stack))
            {
                continue;
            }

            @Nullable final AbstractTileEntityRack chest = getRackForStack(stack);
            if (chest == null)
            {
                if(level.getGameTime() - lastNotification > TICKS_FIVE_MIN)
                {
                    lastNotification = level.getGameTime();
                    if (getBuilding().getBuildingLevel() == getBuilding().getMaxBuildingLevel())
                    {
                        if (getBuilding().getModule(BuildingModules.WAREHOUSE_OPTIONS).getStorageUpgrade() < MAX_STORAGE_UPGRADE)
                        {
                            MessageUtils.format(COM_MINECOLONIES_COREMOD_WAREHOUSE_FULL_LEVEL5_UPGRADE).sendTo(getColony()).forAllPlayers();
                        }
                        else
                        {
                            MessageUtils.format(COM_MINECOLONIES_COREMOD_WAREHOUSE_FULL_MAX_UPGRADE).sendTo(getColony()).forAllPlayers();
                        }
                    }
                    else
                    {
                        MessageUtils.format(COM_MINECOLONIES_COREMOD_WAREHOUSE_FULL).sendTo(getColony()).forAllPlayers();
                    }
                }
                return;
            }

            InventoryUtils.transferItemStackIntoNextBestSlotInItemHandler(inventoryCitizen, i, chest.getItemHandlerCap());
        }
    }

    /**
     * Get a rack for a stack.
     * @param stack the stack to insert.
     * @return the matching rack.
     */
    public AbstractTileEntityRack getRackForStack(final ItemStack stack)
    {
        // One rack snapshot serves every slot of a dump; racks report their own changes to it.
        final WarehouseRackIndex index = rackIndex(getBuilding());
        AbstractTileEntityRack rack = index.rackWithItemStack(level, stack);
        if (rack == null)
        {
            rack = index.rackWithSimilarStack(level, stack);
            if (rack == null)
            {
                rack = index.hasUnloadedContainers(level) ? searchMostEmptyRack() : index.mostEmptyLoadedRack(level);
            }
        }
        return rack;
    }

    /**
     * The rack index of the building: the warehouse's own, else a one-shot scan.
     *
     * @param building the building (must not be null).
     * @return the index.
     */
    private static WarehouseRackIndex rackIndex(@NotNull final IBuilding building)
    {
        return building instanceof final BuildingWareHouse wareHouse ? wareHouse.getRackIndex() : WarehouseRackIndex.oneShot(building);
    }

    /**
     * Search for the chest with the least items in it. Kept for warehouses with an unloaded container: like before, it
     * looks up every container position, loaded or not.
     *
     * @return the tileEntity of this chest.
     */
    @Nullable
    private AbstractTileEntityRack searchMostEmptyRack()
    {
        int freeSlots = 0;
        AbstractTileEntityRack emptiestChest = null;
        for (@NotNull final BlockPos pos : getBuilding().getContainers())
        {
            final BlockEntity entity = getLevel().getBlockEntity(pos);
            if (entity instanceof final TileEntityRack rack)
            {
                if (rack.isEmpty())
                {
                    return rack;
                }

                final int tempFreeSlots = rack.getFreeSlots();
                if (tempFreeSlots > freeSlots)
                {
                    freeSlots = tempFreeSlots;
                    emptiestChest = rack;
                }
            }
        }
        return emptiestChest;
    }
}
