package com.minecolonies.api.colony.storage;

import com.minecolonies.api.crafting.ItemStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The colony's storage seen as one: the stock of every warehouse in one index, the slots of every warehouse as one
 * capacity. The index is kept up to date from the racks' own change events, so a question costs no walk over the racks.
 * <p>
 * Read it on the server only. {@code available = physical - reserved}: what the request system's reservation ledger holds
 * for planned deliveries is not available (when reservations are off, available equals physical). {@code available} excludes every claim, also an
 * add-on's stock held for a building ({@code ReservationLedger.setStock(..., forBuilding)}); for what one request may use, the request system reads
 * {@code BuildingWareHouse.availableCount(..., IRequest)} and {@code ColonyStorage.availableFor}.
 */
public interface IColonyStorage
{
    // ------------------------------------------------------------------ stock

    /**
     * @return a number that changes whenever any stock count or any capacity figure changes; cache answers against it.
     */
    long version();

    /**
     * @param key the item, compared exactly (damage and components).
     * @return how many of the item sit in the racks of all warehouses.
     */
    int physical(@NotNull ItemStorage key);

    /**
     * @param key the item, compared exactly.
     * @return physical minus what is reserved for deliveries that are planned, per warehouse and never below zero.
     */
    int available(@NotNull ItemStorage key);

    /**
     * @param predicate which items.
     * @param upTo      stop counting at this amount (use {@link Integer#MAX_VALUE} for all).
     * @return how many matching items sit in the racks of all warehouses.
     */
    int physical(@NotNull Predicate<ItemStack> predicate, int upTo);

    /**
     * @param predicate which items.
     * @param upTo      stop counting at this amount.
     * @return how many matching items are physically there and not reserved for a planned delivery.
     */
    int available(@NotNull Predicate<ItemStack> predicate, int upTo);

    /**
     * @param warehouse the warehouse building's position (its id).
     * @param predicate which items.
     * @param upTo      stop counting at this amount.
     * @return how many matching items sit in that warehouse.
     */
    int physicalIn(@NotNull BlockPos warehouse, @NotNull Predicate<ItemStack> predicate, int upTo);

    /**
     * @param warehouse the warehouse building's position (its id).
     * @param predicate which items.
     * @param upTo      stop counting at this amount.
     * @return how many matching items that warehouse holds that no reservation holds.
     */
    int availableIn(@NotNull BlockPos warehouse, @NotNull Predicate<ItemStack> predicate, int upTo);

    /**
     * @return a copy of the colony's stock: exact item key to count, all warehouses together.
     */
    @NotNull
    Map<ItemStorage, Integer> stock();

    // ------------------------------------------------------------------ capacity

    /**
     * @return used and total slots of all warehouses, items held, distinct items.
     */
    @NotNull
    StorageUsage usage();

    /**
     * @return one entry per warehouse, in building id order.
     */
    @NotNull
    List<WarehouseUsage> warehouses();

    /**
     * @return true when no warehouse has a free slot (nothing that is not already in a partly filled stack can be stored).
     */
    boolean isFull();

    /**
     * @param stack a stack.
     * @return true when some warehouse rack could take at least one of it (a free slot, or a partly filled stack of it).
     */
    boolean hasRoomFor(@NotNull ItemStack stack);

    /**
     * @return the soft cap per item type over the whole colony; 0 when there is none.
     */
    int itemCap();

    /**
     * @param key the item.
     * @return true when a cap is set and the colony holds at least that many of the item.
     */
    boolean isAtCap(@NotNull ItemStorage key);

    // ------------------------------------------------------------------ flows

    /**
     * @param sinceTick game time; only flows at or after it are returned.
     * @return what moved between buildings and warehouses since then, newest last, merged per (from, to, item).
     */
    @NotNull
    List<StorageFlow> flowsSince(long sinceTick);
}
