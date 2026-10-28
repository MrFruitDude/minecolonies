package com.minecolonies.api.util;

import com.ldtteam.structurize.api.compat.itemhandler.IItemHandlerModifiable;
import com.ldtteam.structurize.api.compat.itemhandler.wrapper.InvWrapper;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.tileentities.AbstractTileEntityRack;
import com.minecolonies.core.tileentities.TileEntityRack;
import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.advancements.predicates.MinMaxBounds;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The stock containers a building registers: its racks (the hut block among them) and, since vanilla tie-in wave 2 (V-7), a vanilla copper chest (every
 * oxidation stage, waxed or not: {@link BlockTags#COPPER_CHESTS}) placed inside the building's corners. The readers of building stock (counts, free slots,
 * the hut's combined inventory, the reservation ledger, worker pickups) go through here, so a copper chest's items are part of the colony's one stock truth
 * like a rack's. A copper chest counts its own 27 slots (one half of a double chest), never the combined double inventory, so two registered halves are not
 * counted twice. Warehouses keep racks only (their stock index is rack based).
 * <p>
 * Copper golems take from copper chests and put into plain chests ({@code CopperGolemAi}, {@code TransportItemsBetweenContainers}) and skip a locked
 * container. A registered copper chest gets {@link #BUILDING_LOCK}, a lock every hand unlocks (any item, also an empty hand): players and hoppers use the chest
 * as before, no golem (a rival's across a border or the colony's own) takes building stock out of the request system's sight. Unregistering clears it again.
 */
public final class BuildingContainers
{
    /**
     * The lock of a registered copper chest: matches every stack (count at least 0, also the empty hand), but is not {@link LockCode#NO_LOCK}, so
     * {@link BaseContainerBlockEntity#isLocked()} is true and copper golems skip the chest.
     */
    public static final LockCode BUILDING_LOCK = new LockCode(ItemPredicate.Builder.item().withCount(MinMaxBounds.Ints.atLeast(0)).build());

    /**
     * The private lock field of a vanilla container, null when it cannot be reached (then golems are not kept out; logged once).
     */
    @Nullable
    private static final Field LOCK_FIELD = lockField();

    private BuildingContainers()
    {
    }

    @Nullable
    private static Field lockField()
    {
        try
        {
            final Field field = BaseContainerBlockEntity.class.getDeclaredField("lockKey");
            field.setAccessible(true);
            return field;
        }
        catch (final ReflectiveOperationException | RuntimeException e)
        {
            Log.getLogger().warn("Copper chests in buildings: cannot reach the container lock, copper golems are not kept out of them", e);
            return null;
        }
    }

    /**
     * @param state a block state.
     * @return whether it is a vanilla copper chest (any oxidation, waxed or not).
     */
    public static boolean isCopperChest(final BlockState state)
    {
        return state.is(BlockTags.COPPER_CHESTS);
    }

    /**
     * @param block a block.
     * @return whether it is a vanilla copper chest (any oxidation, waxed or not).
     */
    public static boolean isCopperChest(final Block block)
    {
        return isCopperChest(block.defaultBlockState());
    }

    /**
     * @param entity a block entity, may be null.
     * @return the copper chest at it (one half of a double chest), or null.
     */
    @Nullable
    public static ChestBlockEntity copperChest(@Nullable final BlockEntity entity)
    {
        return entity instanceof final ChestBlockEntity chest && isCopperChest(chest.getBlockState()) ? chest : null;
    }

    /**
     * @param entity the block entity at a registered container position, may be null.
     * @return whether it holds building stock (a rack, the hut, or a copper chest).
     */
    public static boolean isStock(@Nullable final BlockEntity entity)
    {
        return entity instanceof AbstractTileEntityRack || copperChest(entity) != null;
    }

    /**
     * @param entity the block entity at a registered container position, may be null.
     * @return its item handler (a rack's inventory, or a copper chest's own slots), or null when it holds no building stock.
     */
    @Nullable
    public static IItemHandlerModifiable handler(@Nullable final BlockEntity entity)
    {
        if (entity instanceof final AbstractTileEntityRack rack)
        {
            return rack.getInventory();
        }
        final ChestBlockEntity chest = copperChest(entity);
        return chest == null ? null : new InvWrapper(chest);
    }

    /**
     * How many of an item a container holds (the rack's own count, a copper chest slot by slot with the same {@link ItemStorage} comparison).
     *
     * @param entity  the block entity at a registered container position, may be null.
     * @param storage the item.
     * @return the count, 0 for no stock container.
     */
    public static int count(@Nullable final BlockEntity entity, final ItemStorage storage)
    {
        if (entity instanceof final TileEntityRack rack)
        {
            return rack.getCount(storage);
        }
        final ChestBlockEntity chest = copperChest(entity);
        if (chest == null)
        {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++)
        {
            final ItemStack stack = chest.getItem(slot);
            if (!stack.isEmpty() && storage.equals(new ItemStorage(stack)))
            {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * How many matching items a container holds.
     *
     * @param entity    the block entity at a registered container position, may be null.
     * @param predicate the match.
     * @return the count, 0 for no stock container.
     */
    public static int count(@Nullable final BlockEntity entity, final Predicate<ItemStack> predicate)
    {
        if (entity instanceof final TileEntityRack rack)
        {
            return rack.getItemCount(predicate);
        }
        final ChestBlockEntity chest = copperChest(entity);
        if (chest == null)
        {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++)
        {
            final ItemStack stack = chest.getItem(slot);
            if (!stack.isEmpty() && predicate.test(stack))
            {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * @param entity the block entity at a registered container position, may be null.
     * @return its free slots, or -1 when it holds no building stock.
     */
    public static int freeSlots(@Nullable final BlockEntity entity)
    {
        if (entity instanceof final TileEntityRack rack)
        {
            return rack.getFreeSlots();
        }
        final ChestBlockEntity chest = copperChest(entity);
        if (chest == null)
        {
            return -1;
        }
        int free = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++)
        {
            if (chest.getItem(slot).isEmpty())
            {
                free++;
            }
        }
        return free;
    }

    /**
     * @param entity the block entity at a registered container position, may be null.
     * @return its content by item (a rack's own map, read only; a copper chest's built fresh), empty for no stock container.
     */
    public static Map<ItemStorage, Integer> content(@Nullable final BlockEntity entity)
    {
        if (entity instanceof final TileEntityRack rack)
        {
            return rack.getAllContent();
        }
        final Map<ItemStorage, Integer> content = new HashMap<>();
        final ChestBlockEntity chest = copperChest(entity);
        if (chest != null)
        {
            for (int slot = 0; slot < chest.getContainerSize(); slot++)
            {
                final ItemStack stack = chest.getItem(slot);
                if (!stack.isEmpty())
                {
                    content.merge(new ItemStorage(stack), stack.getCount(), Integer::sum);
                }
            }
        }
        return content;
    }

    /**
     * Keeps copper golems out of a copper chest a building registered (a chest that already has a real lock keeps it).
     *
     * @param entity the block entity, may be null.
     */
    public static void keepGolemsOut(@Nullable final BlockEntity entity)
    {
        final ChestBlockEntity chest = copperChest(entity);
        if (chest != null && LOCK_FIELD != null && !chest.isLocked())
        {
            setLock(chest, BUILDING_LOCK);
        }
    }

    /**
     * Lets copper golems use a copper chest again once no building holds it (only the building lock is cleared).
     *
     * @param entity the block entity, may be null.
     */
    public static void letGolemsIn(@Nullable final BlockEntity entity)
    {
        final ChestBlockEntity chest = copperChest(entity);
        if (chest != null && LOCK_FIELD != null && BUILDING_LOCK.equals(lock(chest)))
        {
            setLock(chest, LockCode.NO_LOCK);
        }
    }

    /**
     * @param chest a container.
     * @return whether copper golems are kept out of it by a building.
     */
    public static boolean keepsGolemsOut(final BaseContainerBlockEntity chest)
    {
        return LOCK_FIELD != null && BUILDING_LOCK.equals(lock(chest));
    }

    @Nullable
    private static LockCode lock(final BaseContainerBlockEntity chest)
    {
        try
        {
            return (LockCode) LOCK_FIELD.get(chest);
        }
        catch (final IllegalAccessException e)
        {
            return null;
        }
    }

    private static void setLock(final BaseContainerBlockEntity chest, final LockCode lock)
    {
        try
        {
            LOCK_FIELD.set(chest, lock);
            chest.setChanged();
        }
        catch (final IllegalAccessException e)
        {
            Log.getLogger().warn("Copper chests in buildings: cannot set the container lock at " + chest.getBlockPos(), e);
        }
    }
}
