package com.minecolonies.core.colony.buildings;

import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.IBuildingContainer;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.api.util.BuildingContainers;
import com.minecolonies.api.util.NBTUtils;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import com.minecolonies.core.tileentities.TileEntityRack;
import com.minecolonies.core.blocks.BlockMinecoloniesRack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import com.ldtteam.structurize.api.util.Tuple;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import com.ldtteam.structurize.api.compat.itemhandler.IItemHandler;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Predicate;

import static com.minecolonies.api.colony.requestsystem.requestable.deliveryman.AbstractDeliverymanRequestable.getMaxBuildingPriority;
import static com.minecolonies.api.util.constant.NbtTagConstants.*;

/**
 * Class containing the container action of the buildings.
 */
public abstract class AbstractBuildingContainer extends AbstractSchematicProvider implements IBuildingContainer
{
    /**
     * A list which contains the position of all containers which belong to the worker building.
     */
    protected final Set<BlockPos> containerList = new HashSet<>();

    /**
     * List of items the worker should keep. With the quantity and if he should keep it in the inventory as well.
     */
    protected final Map<Predicate<ItemStack>, Tuple<Integer, Boolean>> keepX = new HashMap<>();

    /**
     * The tileEntity of the building.
     */
    protected AbstractTileEntityColonyBuilding tileEntity;

    /**
     * Priority of the building in the pickUpList. This is the unscaled value (mainly for a more intuitive GUI).
     */
    private int unscaledPickUpPriority = 5;

    /**
     * The constructor for the building container.
     *
     * @param pos    the position of it.
     * @param colony the colony.
     */
    public AbstractBuildingContainer(final BlockPos pos, final IColony colony)
    {
        super(pos, colony);
    }

    @Override
    public void deserializeNBT(@NotNull final HolderLookup.Provider provider, final CompoundTag compound)
    {
        super.deserializeNBT(provider, compound);

        final ListTag containerTagList = compound.getListOrEmpty(TAG_CONTAINERS);
        for (int i = 0; i < containerTagList.size(); ++i)
        {
            containerList.add(NBTUtils.readBlockPos(containerTagList.get(i)));
        }
        onContainersChanged();
        if (compound.contains(TAG_PRIO))
        {
            this.unscaledPickUpPriority = compound.getIntOr(TAG_PRIO, 0);
        }
        if (compound.contains(TAG_PRIO_STATE))
        {
            // This was the old int representation of Pickup:Never
            if (compound.getIntOr(TAG_PRIO_STATE, 0) == 0)
            {
                this.unscaledPickUpPriority = 0;
            }
        }
    }

    @Override
    public CompoundTag serializeNBT(@NotNull final HolderLookup.Provider provider)
    {
        final CompoundTag compound = super.serializeNBT(provider);

        @NotNull final ListTag containerTagList = new ListTag();
        for (@NotNull final BlockPos pos : containerList)
        {
            containerTagList.add(NBTUtils.writeBlockPos(pos));
        }
        compound.put(TAG_CONTAINERS, containerTagList);
        compound.putInt(TAG_PRIO, this.unscaledPickUpPriority);

        return compound;
    }

    @Override
    public int getPickUpPriority()
    {
        return this.unscaledPickUpPriority;
    }

    @Override
    public void alterPickUpPriority(final int value)
    {
        this.unscaledPickUpPriority = Mth.clamp(this.unscaledPickUpPriority + value, 0, getMaxBuildingPriority(false));
    }

    @Override
    public void addContainerPosition(@NotNull final BlockPos pos)
    {
        containerList.add(pos);
        onContainersChanged();
    }

    @Override
    public void removeContainerPosition(final BlockPos pos)
    {
        if (containerList.remove(pos))
        {
            // V-7: a copper chest that leaves the building is open to copper golems again, unless another building still lists it.
            CopperChests.releaseIfUnheld(colony == null ? null : colony.getWorld(), pos, null);
        }
        onContainersChanged();
    }

    /**
     * @param pos a position.
     * @return whether this building lists it as a container (the hut itself is not in the list).
     */
    public boolean listsContainer(final BlockPos pos)
    {
        return containerList.contains(pos);
    }

    /**
     * Whether a vanilla copper chest inside this building's corners counts as one of its containers (V-7).
     *
     * @return true, except where the stock index is rack based (the warehouse).
     */
    public boolean acceptsCopperChests()
    {
        return true;
    }

    /**
     * Registers a vanilla copper chest inside the corners as a container (V-7): its items count as the building's stock, and copper golems are kept out
     * of it ({@link BuildingContainers#keepGolemsOut}). Does nothing for a building that keeps racks only or a position that is not a copper chest.
     *
     * @param pos   the chest's position (one half of a double chest).
     * @param world the world.
     * @return whether the chest is a container of this building now.
     */
    public boolean addCopperChest(@NotNull final BlockPos pos, @NotNull final Level world)
    {
        final net.minecraft.world.level.block.entity.ChestBlockEntity chest = BuildingContainers.copperChest(world.getBlockEntity(pos));
        if (!acceptsCopperChests() || chest == null || BuildingContainers.hasOwnLock(chest))
        {
            // a chest with a lock of its own stays its owner's
            return false;
        }
        if (!containerList.contains(pos))
        {
            addContainerPosition(pos);
        }
        BuildingContainers.keepGolemsOut(chest);
        return true;
    }

    /**
     * The building is gone: its copper chests are open to copper golems again, except where another building still lists them (the positions stay with the
     * building's data). Chests in unloaded chunks are released when their chunk loads ({@link CopperChests#onChunkLoad}).
     */
    protected void releaseCopperChests()
    {
        final Level world = colony == null ? null : colony.getWorld();
        for (final BlockPos pos : new ArrayList<>(containerList))
        {
            CopperChests.releaseIfUnheld(world, pos, this);
        }
    }

    /**
     * The fingerprint of the copper chests' stock at the last view sync check.
     */
    private long copperStockPrint = 17L;

    @Override
    public void onColonyTick(final IColony colony)
    {
        super.onColonyTick(colony);
        // V-7: keep the copper chest list true (see CopperChests), and resync the view when the chests' stock changed (the client gets no chest contents).
        final boolean changed = CopperChests.reconcile(this);
        final Level world = colony.getWorld();
        long print = 17L;
        if (world != null && !world.isClientSide())
        {
            for (final BlockPos pos : containerList)
            {
                if (WorldUtil.isBlockLoaded(world, pos))
                {
                    final long chest = BuildingContainers.contentPrint(world.getBlockEntity(pos));
                    if (chest != 0L)
                    {
                        print = print * 31L + pos.asLong() * 7L + chest;
                    }
                }
            }
        }
        if (changed || print != copperStockPrint)
        {
            copperStockPrint = print;
            markDirty();
        }
    }

    /**
     * The stock of this building's registered copper chests, slot by slot (copies), by chest position: for the building view (V-7).
     *
     * @return the stock, empty when there is none.
     */
    public Map<BlockPos, List<ItemStack>> copperChestStock()
    {
        final Map<BlockPos, List<ItemStack>> stock = new HashMap<>();
        final Level world = colony == null ? null : colony.getWorld();
        if (world == null || world.isClientSide())
        {
            return stock;
        }
        for (final BlockPos pos : containerList)
        {
            final net.minecraft.world.level.block.entity.ChestBlockEntity chest =
              WorldUtil.isBlockLoaded(world, pos) ? BuildingContainers.stockChest(world.getBlockEntity(pos)) : null;
            if (chest != null)
            {
                final List<ItemStack> stacks = new ArrayList<>();
                for (int slot = 0; slot < chest.getContainerSize(); slot++)
                {
                    if (!chest.getItem(slot).isEmpty())
                    {
                        stacks.add(chest.getItem(slot).copy());
                    }
                }
                if (!stacks.isEmpty())
                {
                    stock.put(pos.immutable(), stacks);
                }
            }
        }
        return stock;
    }

    /**
     * Called after the container list may have changed.
     */
    protected void onContainersChanged()
    {
        // Nothing by default.
    }

    @Override
    public List<BlockPos> getContainers()
    {
        final List<BlockPos> list = new ArrayList<>(containerList);;
        list.add(this.getPosition());
        return list;
    }

    @Override
    public void registerBlockPosition(@NotNull final BlockState blockState, @NotNull final BlockPos pos, @NotNull final Level world)
    {
        registerBlockPosition(blockState.getBlock(), pos, world);
    }

    @Override
    @SuppressWarnings("squid:S1172")
    public void registerBlockPosition(@NotNull final Block block, @NotNull final BlockPos pos, @NotNull final Level world)
    {
        if (block instanceof AbstractBlockHut)
        {
            final BlockEntity entity = world.getBlockEntity(pos);
            if (entity instanceof TileEntityColonyBuilding buildingEntity)
            {
                buildingEntity.setStructurePack(StructurePacks.getStructurePack(getStructurePack()));
                final IBuilding building = colony.getServerBuildingManager().getBuilding(pos);
                if (building != null)
                {
                    building.setStructurePack(getStructurePack());
                    building.setParent(getID());
                }
            }
        }
        else if (BuildingContainers.isCopperChest(block))
        {
            addCopperChest(pos, world);
        }
        else if (block instanceof BlockMinecoloniesRack)
        {
            addContainerPosition(pos);
            final BlockEntity entity = world.getBlockEntity(pos);
            if (entity instanceof TileEntityRack rackEntity)
            {
                rackEntity.setBuildingPos(this.getID());
            }
        }
    }

    /**
     * Gets the list of tags, and finds the first location registered there. 
     * @param tagName the name of the tag to query
     * @return the BlockPos, or null if not found
     */
    @Nullable
    protected BlockPos getFirstLocationFromTag(@NotNull final String tagName)
    {
        final List<BlockPos> locations = getLocationsFromTag(tagName);
        return locations.isEmpty() ? null : locations.get(0);
    }

    @Override
    public List<BlockPos> getLocationsFromTag(@NotNull final String tagName)
    {
        if (getTileEntity() != null)
        {
            return getTileEntity().getCachedWorldTagNamePosMap().getOrDefault(tagName, Collections.emptyList());
        }
        return Collections.emptyList();
    }

    @Override
    public void setTileEntity(final AbstractTileEntityColonyBuilding te)
    {
        tileEntity = te;
        if (te != null && te.isOutdated())
        {
            safeUpdateTEDataFromSchematic();
        }
    }

    //------------------------- !Start! Capabilities handling for minecolonies buildings -------------------------//

    @Override
    public @Nullable IItemHandler getItemHandlerCap(Direction direction)
    {
        final AbstractTileEntityColonyBuilding tileEntity = getTileEntity();
        return tileEntity == null ? null : tileEntity.getItemHandlerCap(direction);
    }

    //------------------------- !End! Capabilities handling for minecolonies buildings -------------------------//
}
