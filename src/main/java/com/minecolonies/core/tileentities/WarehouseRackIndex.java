package com.minecolonies.core.tileentities;

import com.minecolonies.api.colony.buildings.IBuildingContainer;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.api.tileentities.AbstractTileEntityRack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Cached view of a storage building's racks, so warehouse queries stop doing a block-entity lookup per container per
 * request.
 * <p>
 * It holds, in {@link IBuildingContainer#getContainers()} order, whether each container position was loaded and which
 * block entity sat there, plus an index from {@link Item} to the racks whose content holds that item. Every answer is
 * still read live from the racks themselves (counts, free slots, similarity); the cache only replaces the
 * {@code isBlockLoaded + getBlockEntity} walk and, for item queries, skips racks that cannot hold the item. Iteration
 * order and early-out points are the ones of the old loops, so callers get the same answers.
 * <p>
 * Invalidation:
 * <ul>
 *     <li>rack content change ({@link TileEntityRack} content rebuild: insert, extract, load, upgrade) re-indexes that
 *     rack's items;</li>
 *     <li>rack block entity removed (broken, replaced, chunk unload) or container list changed (rack added/removed,
 *     building upgrade, building load) rebuilds the snapshot;</li>
 *     <li>any rack block entity joining a level (chunk load, placement) and every new game tick re-check the loaded
 *     state of each position, rebuilding if it changed.</li>
 * </ul>
 */
public final class WarehouseRackIndex
{
    /**
     * Block-entity lookups made by warehouse queries (rebuilds and revalidations). Server thread statistic for tests.
     */
    public static long beLookups;

    /**
     * Rack content reads made by warehouse queries. Server thread statistic for tests.
     */
    public static long rackProbes;

    /**
     * Snapshot rebuilds. Server thread statistic for tests.
     */
    public static long rebuilds;

    /**
     * Full matching-stack walks over a warehouse's racks ({@code TileEntityWareHouse#getMatchingItemStacksInWarehouse}).
     * Server thread statistic for tests.
     */
    public static long rackWalks;

    /**
     * Bumped whenever a rack block entity joins a level, so a snapshot that saw an empty or unloaded position re-checks it.
     */
    private static final AtomicInteger RACK_LOAD_EPOCH = new AtomicInteger();

    /**
     * The building whose containers are indexed.
     */
    private final IBuildingContainer building;

    /**
     * Whether the racks are told to report changes to this index (false for a one-shot index).
     */
    private final boolean tracked;

    private boolean structureDirty = true;
    /**
     * Bumped on every reported rack content change, rack removal or container list change.
     */
    private long    changeCount;
    private Level   level;
    private long    validatedTick;
    private int     validatedEpoch;

    private BlockPos[]       positions = new BlockPos[0];
    private boolean[]        loaded    = new boolean[0];
    private BlockEntity[]    entities  = new BlockEntity[0];
    private TileEntityRack[] racks     = new TileEntityRack[0];
    private Set<Item>[]      rackItems = newItemSets(0);
    private boolean          anyUnloaded;
    private boolean          anyForeignRack;

    private final Map<Item, BitSet>                 itemRacks    = new HashMap<>();
    private final IdentityHashMap<TileEntityRack, BitSet> rackSlot = new IdentityHashMap<>();
    private final BitSet                            dirtyRacks   = new BitSet();

    /**
     * Create a tracked index for a building.
     *
     * @param building the building whose containers are indexed.
     */
    public WarehouseRackIndex(@NotNull final IBuildingContainer building)
    {
        this(building, true);
    }

    private WarehouseRackIndex(@NotNull final IBuildingContainer building, final boolean tracked)
    {
        this.building = building;
        this.tracked = tracked;
    }

    /**
     * A one-shot index: a fresh scan with the same iteration order, registering nothing.
     *
     * @param building the building.
     * @return the index.
     */
    public static WarehouseRackIndex oneShot(@NotNull final IBuildingContainer building)
    {
        return new WarehouseRackIndex(building, false);
    }

    /**
     * Reset the test statistics.
     */
    public static void resetStats()
    {
        beLookups = 0;
        rackProbes = 0;
        rebuilds = 0;
        rackWalks = 0;
    }

    /**
     * Change counter: differs from an earlier reading once any indexed rack's content changed, a rack was removed or
     * the container list changed since then. Pair it with {@link #loadEpoch()} and the game time to tell whether an
     * earlier read of the racks still holds.
     *
     * @return the change count.
     */
    public long changeCount()
    {
        return changeCount;
    }

    /**
     * Rack load epoch: changes whenever a rack block entity joins a level.
     *
     * @return the epoch.
     */
    public static int loadEpoch()
    {
        return RACK_LOAD_EPOCH.get();
    }

    /**
     * Called by a rack block entity when it joins a level.
     */
    static void onRackLoaded()
    {
        RACK_LOAD_EPOCH.incrementAndGet();
    }

    /**
     * Container list changed: rebuild on next use.
     */
    public void markStructureDirty()
    {
        structureDirty = true;
        changeCount++;
    }

    /**
     * Unregister from every rack and drop the snapshot (the building is gone).
     */
    public void release()
    {
        if (tracked)
        {
            for (final TileEntityRack rack : racks)
            {
                if (rack != null)
                {
                    rack.removeIndexListener(this);
                }
            }
        }
        racks = new TileEntityRack[0];
        entities = new BlockEntity[0];
        positions = new BlockPos[0];
        loaded = new boolean[0];
        rackItems = newItemSets(0);
        itemRacks.clear();
        rackSlot.clear();
        dirtyRacks.clear();
        structureDirty = true;
        changeCount++;
    }

    /**
     * Called by a registered rack when its content was rebuilt.
     *
     * @param rack the rack.
     */
    void onRackContentChanged(final TileEntityRack rack)
    {
        changeCount++;
        final BitSet slots = rackSlot.get(rack);
        if (slots != null)
        {
            dirtyRacks.or(slots);
        }
    }

    /**
     * Called by a registered rack when its block entity was removed.
     *
     * @param rack the rack.
     */
    void onRackRemoved(final TileEntityRack rack)
    {
        if (rackSlot.containsKey(rack))
        {
            structureDirty = true;
            changeCount++;
        }
    }

    // ------------------------------------------------------------------ validity

    private void ensure(@NotNull final Level world)
    {
        if (structureDirty || world != level)
        {
            rebuild(world);
            return;
        }

        if (world.getGameTime() != validatedTick || RACK_LOAD_EPOCH.get() != validatedEpoch)
        {
            if (!revalidate())
            {
                rebuild(world);
                return;
            }
        }

        if (!dirtyRacks.isEmpty())
        {
            for (int i = dirtyRacks.nextSetBit(0); i >= 0; i = dirtyRacks.nextSetBit(i + 1))
            {
                reindexRack(i);
            }
            dirtyRacks.clear();
        }
    }

    /**
     * Re-check the loaded state and block entity of every position without a full rebuild.
     *
     * @return false if anything changed.
     */
    private boolean revalidate()
    {
        for (int i = 0; i < positions.length; i++)
        {
            final boolean isLoaded = WorldUtil.isBlockLoaded(level, positions[i]);
            if (isLoaded != loaded[i])
            {
                return false;
            }
            if (isLoaded)
            {
                final BlockEntity entity = entities[i];
                if (entity != null)
                {
                    if (entity.isRemoved())
                    {
                        return false;
                    }
                }
                else
                {
                    beLookups++;
                    if (level.getBlockEntity(positions[i]) != null)
                    {
                        return false;
                    }
                }
            }
        }
        validatedTick = level.getGameTime();
        validatedEpoch = RACK_LOAD_EPOCH.get();
        return true;
    }

    private void rebuild(@NotNull final Level world)
    {
        rebuilds++;
        final int epoch = RACK_LOAD_EPOCH.get();
        final List<BlockPos> containers = building.getContainers();
        final int size = containers.size();

        final TileEntityRack[] oldRacks = racks;
        positions = containers.toArray(new BlockPos[0]);
        loaded = new boolean[size];
        entities = new BlockEntity[size];
        racks = new TileEntityRack[size];
        rackItems = newItemSets(size);
        anyUnloaded = false;
        anyForeignRack = false;
        itemRacks.clear();
        rackSlot.clear();
        dirtyRacks.clear();

        for (int i = 0; i < size; i++)
        {
            final BlockPos pos = positions[i];
            if (!WorldUtil.isBlockLoaded(world, pos))
            {
                anyUnloaded = true;
                continue;
            }
            loaded[i] = true;
            beLookups++;
            final BlockEntity entity = world.getBlockEntity(pos);
            entities[i] = entity;
            if (entity instanceof final TileEntityRack rack)
            {
                racks[i] = rack;
                rackSlot.computeIfAbsent(rack, k -> new BitSet()).set(i);
                reindexRack(i);
            }
            else if (entity instanceof AbstractTileEntityRack)
            {
                anyForeignRack = true;
            }
        }

        if (tracked)
        {
            for (final TileEntityRack old : oldRacks)
            {
                if (old != null && !rackSlot.containsKey(old))
                {
                    old.removeIndexListener(this);
                }
            }
            for (final TileEntityRack rack : racks)
            {
                if (rack != null)
                {
                    rack.addIndexListener(this);
                }
            }
        }

        level = world;
        structureDirty = false;
        validatedTick = world.getGameTime();
        validatedEpoch = epoch;
    }

    private void reindexRack(final int i)
    {
        final TileEntityRack rack = racks[i];
        if (rack == null)
        {
            return;
        }
        for (final Item item : rackItems[i])
        {
            final BitSet bits = itemRacks.get(item);
            if (bits != null)
            {
                bits.clear(i);
            }
        }
        final Set<Item> items = new HashSet<>();
        for (final ItemStorage key : rack.getAllContent().keySet())
        {
            items.add(key.getItem());
        }
        rackItems[i] = items;
        for (final Item item : items)
        {
            itemRacks.computeIfAbsent(item, k -> new BitSet()).set(i);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<Item>[] newItemSets(final int size)
    {
        final Set<Item>[] sets = new Set[size];
        Arrays.fill(sets, Collections.emptySet());
        return sets;
    }

    // ------------------------------------------------------------------ building counts (InventoryUtils.hasBuildingEnoughElseCount)

    /**
     * Same answer as {@code InventoryUtils.hasBuildingEnoughElseCount(building, storage, count)}.
     *
     * @param world   the world.
     * @param storage the storage to count.
     * @param count   the count wanted.
     * @return the count found, stopping as soon as it reaches {@code count}.
     */
    public int countUpTo(@NotNull final Level world, @NotNull final ItemStorage storage, final int count)
    {
        ensure(world);
        int totalCount = 0;
        if (count <= 0)
        {
            // The old loop returns after the first loaded position.
            for (int i = 0; i < positions.length; i++)
            {
                if (loaded[i])
                {
                    if (racks[i] != null)
                    {
                        rackProbes++;
                        totalCount += racks[i].getCount(storage);
                    }
                    return totalCount;
                }
            }
            return totalCount;
        }

        // Racks without the item add 0, so only racks holding it can move the sum or the early-out point.
        final BitSet bits = itemRacks.get(storage.getItem());
        if (bits == null)
        {
            return 0;
        }
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1))
        {
            rackProbes++;
            totalCount += racks[i].getCount(storage);
            if (totalCount >= count)
            {
                return totalCount;
            }
        }
        return totalCount;
    }

    /**
     * Same answer as {@code InventoryUtils.hasBuildingEnoughElseCount(building, predicate, count)}.
     *
     * @param world     the world.
     * @param predicate the predicate to match.
     * @param count     the count wanted.
     * @return the count found, stopping as soon as it reaches {@code count}.
     */
    public int countUpTo(@NotNull final Level world, @NotNull final Predicate<ItemStack> predicate, final int count)
    {
        ensure(world);
        int totalCount = 0;
        for (int i = 0; i < positions.length; i++)
        {
            if (loaded[i])
            {
                if (racks[i] != null)
                {
                    rackProbes++;
                    totalCount += racks[i].getItemCount(predicate);
                }
                if (totalCount >= count)
                {
                    return totalCount;
                }
            }
        }
        return totalCount;
    }

    // ------------------------------------------------------------------ warehouse queries (TileEntityWareHouse)

    /**
     * Whether the non-empty racks hold at least {@code count} items matching the predicate.
     *
     * @param world     the world.
     * @param predicate the predicate.
     * @param count     the count.
     * @return true if so.
     */
    public boolean hasMatching(@NotNull final Level world, @NotNull final Predicate<ItemStack> predicate, final int count)
    {
        ensure(world);
        int totalCount = 0;
        for (int i = 0; i < positions.length; i++)
        {
            final TileEntityRack rack = racks[i];
            if (rack != null && !rack.isEmpty())
            {
                rackProbes++;
                totalCount += rack.getItemCount(predicate);
                if (totalCount >= count)
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether the non-empty racks hold at least {@code count} of the stack beyond {@code leftOver}.
     *
     * @param world        the world.
     * @param itemStack    the stack.
     * @param count        the count.
     * @param ignoreNBT    ignore components.
     * @param ignoreDamage ignore damage.
     * @param leftOver     the amount to keep.
     * @return true if so.
     */
    public boolean hasMatching(
      @NotNull final Level world,
      @NotNull final ItemStack itemStack,
      final int count,
      final boolean ignoreNBT,
      final boolean ignoreDamage,
      final int leftOver)
    {
        ensure(world);
        int totalCountFound = 0 - leftOver;
        if (totalCountFound >= count)
        {
            // The old loop answers true at the first non-empty rack, whatever it holds.
            for (int i = 0; i < positions.length; i++)
            {
                if (racks[i] != null && !racks[i].isEmpty())
                {
                    return true;
                }
            }
            return false;
        }

        final BitSet bits = itemRacks.get(itemStack.getItem());
        if (bits == null)
        {
            return false;
        }
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1))
        {
            rackProbes++;
            totalCountFound += racks[i].getCount(itemStack, ignoreDamage, ignoreNBT);
            if (totalCountFound >= count)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Every rack position holding something matching the predicate, in container order.
     *
     * @param world the world.
     * @return the non-empty racks with their positions.
     */
    public List<Map.Entry<BlockPos, TileEntityRack>> nonEmptyRacks(@NotNull final Level world)
    {
        ensure(world);
        final List<Map.Entry<BlockPos, TileEntityRack>> list = new ArrayList<>();
        for (int i = 0; i < positions.length; i++)
        {
            final TileEntityRack rack = racks[i];
            if (rack != null && !rack.isEmpty())
            {
                list.add(new AbstractMap.SimpleImmutableEntry<>(positions[i], rack));
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ dump (TileEntityWareHouse#getRackForStack)

    /**
     * First loaded rack with a free slot that already holds the stack (damage ignored).
     *
     * @param world the world.
     * @param stack the stack.
     * @return the rack or null.
     */
    @Nullable
    public AbstractTileEntityRack rackWithItemStack(@NotNull final Level world, @NotNull final ItemStack stack)
    {
        ensure(world);
        if (anyForeignRack)
        {
            for (int i = 0; i < positions.length; i++)
            {
                if (entities[i] instanceof final AbstractTileEntityRack rack)
                {
                    rackProbes++;
                    if (rack.getFreeSlots() > 0 && rack.hasItemStack(stack, 1, true))
                    {
                        return rack;
                    }
                }
            }
            return null;
        }

        final BitSet bits = itemRacks.get(stack.getItem());
        if (bits == null)
        {
            return null;
        }
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1))
        {
            rackProbes++;
            final TileEntityRack rack = racks[i];
            if (rack.getFreeSlots() > 0 && rack.hasItemStack(stack, 1, true))
            {
                return rack;
            }
        }
        return null;
    }

    /**
     * First loaded rack with a free slot that holds a similar stack.
     *
     * @param world the world.
     * @param stack the stack.
     * @return the rack or null.
     */
    @Nullable
    public AbstractTileEntityRack rackWithSimilarStack(@NotNull final Level world, @NotNull final ItemStack stack)
    {
        ensure(world);
        for (int i = 0; i < positions.length; i++)
        {
            if (entities[i] instanceof final AbstractTileEntityRack rack)
            {
                rackProbes++;
                if (rack.getFreeSlots() > 0 && rack.hasSimilarStack(stack))
                {
                    return rack;
                }
            }
        }
        return null;
    }

    /**
     * Whether some container position was not loaded at the last snapshot.
     *
     * @param world the world.
     * @return true if so.
     */
    public boolean hasUnloadedContainers(@NotNull final Level world)
    {
        ensure(world);
        return anyUnloaded;
    }

    /**
     * The first empty rack, else the rack with the most free slots (first on ties). Only valid when
     * {@link #hasUnloadedContainers(Level)} is false: the old search looked up every position, loaded or not.
     *
     * @param world the world.
     * @return the rack or null.
     */
    @Nullable
    public AbstractTileEntityRack mostEmptyLoadedRack(@NotNull final Level world)
    {
        ensure(world);
        int freeSlots = 0;
        AbstractTileEntityRack emptiestChest = null;
        for (int i = 0; i < positions.length; i++)
        {
            final TileEntityRack rack = racks[i];
            if (rack != null)
            {
                rackProbes++;
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
