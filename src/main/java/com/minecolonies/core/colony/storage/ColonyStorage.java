package com.minecolonies.core.colony.storage;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.storage.IColonyStorage;
import com.minecolonies.api.colony.storage.StorageFlow;
import com.minecolonies.api.colony.storage.StorageUsage;
import com.minecolonies.api.colony.storage.WarehouseUsage;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.requestsystem.RsAccess;
import com.minecolonies.core.colony.requestsystem.RsFlags;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import com.minecolonies.core.tileentities.WarehouseRackIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The colony's warehouses as one storage. It owns the colony-wide stock table, which every warehouse's
 * {@link WarehouseStock} keeps up to date from rack change events (nothing is scanned per question), and answers
 * availability against the reservation ledger.
 * <p>
 * Server thread only. Safe to use with the storage-network flag off: it is then just a faster way to count.
 */
public final class ColonyStorage implements IColonyStorage, StockSink
{
    /**
     * Flows older than this are forgotten.
     */
    private static final long FLOW_HISTORY_TICKS = 20L * 60 * 10;

    /**
     * The window of the per-minute figures.
     */
    private static final long MINUTE_TICKS = 20L * 60;

    /**
     * Upper bound of the flow history.
     */
    private static final int FLOW_HISTORY_MAX = 1024;

    /**
     * The "colony storage is full" message is sent at most this often.
     */
    private static final long FULL_NOTICE_TICKS = 20L * 60 * 5;

    private record FlowEvent(BlockPos from, BlockPos to, ItemStorage key, int count, long tick)
    {
    }

    private final Colony colony;

    private final StockTable total = new StockTable();

    /**
     * The warehouses whose stock this storage follows, by building id.
     */
    private final Map<BlockPos, WarehouseStock> attached = new LinkedHashMap<>();

    private int totalSlots;
    private int freeSlots;

    private long version;

    private final ArrayDeque<FlowEvent> flows = new ArrayDeque<>();

    /**
     * Cached source plans by request (see {@link NetworkSources}).
     */
    private final Map<IToken<?>, Object> plans = new HashMap<>();

    private final Map<BlockPos, int[]> activeAt = new HashMap<>();
    private long loadsTick = Long.MIN_VALUE;

    private long lastFullNotice = Long.MIN_VALUE / 2;

    /**
     * Creates the storage of a colony.
     *
     * @param colony the colony.
     */
    public ColonyStorage(@NotNull final Colony colony)
    {
        this.colony = colony;
    }

    /**
     * @param colony a server colony.
     * @return its storage.
     */
    @NotNull
    public static ColonyStorage of(@NotNull final com.minecolonies.api.colony.IColony colony)
    {
        return (ColonyStorage) colony.getServerBuildingManager().getColonyStorage();
    }

    // ------------------------------------------------------------------ keeping up

    @Override
    public void itemsChanged(@NotNull final ItemStorage key, final int delta)
    {
        total.add(key, delta);
        version++;
    }

    @Override
    public void slotsChanged(final int totalDelta, final int freeDelta)
    {
        totalSlots += totalDelta;
        freeSlots += freeDelta;
        version++;
    }

    /**
     * Makes the totals follow the colony's warehouses: new warehouses are attached, removed ones forgotten, racks that
     * changed structure are re-read. Cheap when nothing changed; called by every read.
     */
    private void sync()
    {
        final Level world = colony.getWorld();
        if (world == null || world.isClientSide())
        {
            return;
        }
        final List<IWareHouse> list = colony.getServerBuildingManager().getWareHouses();
        boolean listChanged = list.size() != attached.size();
        for (final IWareHouse wareHouse : list)
        {
            if (!(wareHouse instanceof final BuildingWareHouse building))
            {
                continue;
            }
            final WarehouseRackIndex index = building.getRackIndex();
            final WarehouseStock stock = index.stock();
            if (stock == null)
            {
                continue;
            }
            if (attached.get(building.getID()) != stock)
            {
                final WarehouseStock old = attached.put(building.getID(), stock);
                if (old != null)
                {
                    old.detach();
                }
                stock.attach(this);
                listChanged = true;
            }
            index.sync(world);
        }
        if (listChanged)
        {
            final Set<BlockPos> present = new HashSet<>();
            for (final IWareHouse wareHouse : list)
            {
                present.add(wareHouse.getID());
            }
            final var iterator = attached.entrySet().iterator();
            while (iterator.hasNext())
            {
                final var entry = iterator.next();
                if (!present.contains(entry.getKey()))
                {
                    entry.getValue().detach();
                    iterator.remove();
                    version++;
                }
            }
        }
    }

    Map<IToken<?>, Object> planCache()
    {
        return plans;
    }

    /**
     * @param position a warehouse building's position.
     * @return its stock, or null when it is not a warehouse of this colony.
     */
    @Nullable
    WarehouseStock stockOf(@NotNull final BlockPos position)
    {
        sync();
        return attached.get(position);
    }

    // ------------------------------------------------------------------ stock

    @Override
    public long version()
    {
        sync();
        return version;
    }

    @Override
    public int physical(@NotNull final ItemStorage key)
    {
        sync();
        return total.get(key);
    }

    @Override
    public int available(@NotNull final ItemStorage key)
    {
        sync();
        final ReservationLedger ledger = RsAccess.ledger(colony.getRequestManager());
        if (ledger == null || ledger.isEmpty())
        {
            return total.get(key);
        }
        int sum = 0;
        for (final Map.Entry<BlockPos, WarehouseStock> entry : attached.entrySet())
        {
            sum += ledger.available(entry.getKey(), key, entry.getValue().table().get(key));
        }
        return sum;
    }

    @Override
    public int physical(@NotNull final Predicate<ItemStack> predicate, final int upTo)
    {
        sync();
        return total.count(predicate, upTo);
    }

    @Override
    public int available(@NotNull final Predicate<ItemStack> predicate, final int upTo)
    {
        sync();
        final ReservationLedger ledger = RsAccess.ledger(colony.getRequestManager());
        if (ledger == null || ledger.isEmpty())
        {
            return total.count(predicate, upTo);
        }
        int sum = 0;
        for (final Map.Entry<BlockPos, WarehouseStock> entry : attached.entrySet())
        {
            sum += availableAt(entry.getKey(), entry.getValue(), predicate, upTo - sum, ledger);
            if (sum >= upTo)
            {
                return sum;
            }
        }
        return sum;
    }

    @Override
    public int physicalIn(@NotNull final BlockPos warehouse, @NotNull final Predicate<ItemStack> predicate, final int upTo)
    {
        final WarehouseStock stock = stockOf(warehouse);
        return stock == null ? 0 : stock.table().count(predicate, upTo);
    }

    @Override
    public int availableIn(@NotNull final BlockPos warehouse, @NotNull final Predicate<ItemStack> predicate, final int upTo)
    {
        final WarehouseStock stock = stockOf(warehouse);
        if (stock == null)
        {
            return 0;
        }
        return availableAt(warehouse, stock, predicate, upTo, RsAccess.ledger(colony.getRequestManager()));
    }

    private static int availableAt(
      final BlockPos warehouse,
      final WarehouseStock stock,
      final Predicate<ItemStack> predicate,
      final int upTo,
      @Nullable final ReservationLedger ledger)
    {
        final int reserved = ledger == null ? 0 : ledger.reservedStock(warehouse, predicate);
        if (reserved <= 0)
        {
            return stock.table().count(predicate, upTo);
        }
        return Math.max(0, stock.table().count(predicate, upTo > Integer.MAX_VALUE - reserved ? Integer.MAX_VALUE : upTo + reserved) - reserved);
    }

    @NotNull
    @Override
    public Map<ItemStorage, Integer> stock()
    {
        sync();
        return total.snapshot();
    }

    // ------------------------------------------------------------------ capacity

    @NotNull
    @Override
    public StorageUsage usage()
    {
        sync();
        boolean complete = true;
        for (final WarehouseStock stock : attached.values())
        {
            complete &= !stock.hasUnloadedRacks();
        }
        return new StorageUsage(Math.max(0, totalSlots - freeSlots), totalSlots, total.items(), total.distinct(), attached.size(), complete);
    }

    @NotNull
    @Override
    public List<WarehouseUsage> warehouses()
    {
        sync();
        final long now = colony.getWorld().getGameTime();
        refreshActive();
        final List<WarehouseUsage> result = new ArrayList<>();
        final List<IWareHouse> sorted = new ArrayList<>(colony.getServerBuildingManager().getWareHouses());
        sorted.sort(Comparator.comparingInt((IWareHouse w) -> w.getID().getX()).thenComparingInt(w -> w.getID().getZ()).thenComparingInt(w -> w.getID().getY()));
        for (final IWareHouse wareHouse : sorted)
        {
            final WarehouseStock stock = attached.get(wareHouse.getID());
            final int slots = stock == null ? 0 : stock.slots();
            final int free = stock == null ? 0 : stock.freeSlots();
            final int[] load = {queueSize(wareHouse), activeCount(wareHouse.getID())};
            final int couriers = wareHouse.getModule(BuildingModules.WAREHOUSE_COURIERS).getAssignedCitizen().size();
            result.add(new WarehouseUsage(wareHouse.getID(), wareHouse.getBuildingLevel(), Math.max(0, slots - free), slots, stock == null ? 0 : stock.table().items(),
              load[0], load[1], couriers, movedInto(wareHouse.getID(), now), movedOutOf(wareHouse.getID(), now),
              stock == null || !stock.hasUnloadedRacks()));
        }
        return result;
    }

    /**
     * Courier tasks at a warehouse: the ones waiting in its queue plus the couriers that are on a task starting there. The
     * figures are read once per game tick.
     *
     * @param warehouse the warehouse building's position.
     * @return queued plus active tasks.
     */
    public int taskLoad(@NotNull final BlockPos warehouse)
    {
        refreshActive();
        final int[] active = activeAt.get(warehouse);
        final IBuilding building = colony.getServerBuildingManager().getBuilding(warehouse);
        final var queue = building == null ? null : building.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE);
        return (queue == null ? 0 : queue.getMutableRequestList().size()) + (active == null ? 0 : active[0]);
    }

    private static int queueSize(final IWareHouse wareHouse)
    {
        final var queue = wareHouse.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE);
        return queue == null ? 0 : queue.getMutableRequestList().size();
    }

    private int activeCount(final BlockPos warehouse)
    {
        final int[] active = activeAt.get(warehouse);
        return active == null ? 0 : active[0];
    }

    /**
     * The couriers on a task that starts at a warehouse, counted once per game tick.
     */
    private void refreshActive()
    {
        final long tick = colony.getWorld().getGameTime();
        if (tick == loadsTick)
        {
            return;
        }
        loadsTick = tick;
        activeAt.clear();
        for (final ICitizenData citizen : colony.getCitizenManager().getCitizens())
        {
            if (citizen.getJob() instanceof final JobDeliveryman courier)
            {
                final BlockPos start = currentTaskStart(courier);
                final BlockPos at = start == null ? null : owningWarehouse(start);
                if (at != null)
                {
                    activeAt.computeIfAbsent(at, k -> new int[1])[0]++;
                }
            }
        }
    }

    /**
     * @param warehouse a warehouse building's position.
     * @return true when the warehouse has a free rack slot.
     */
    public boolean hasFreeSlots(@NotNull final BlockPos warehouse)
    {
        final WarehouseStock stock = stockOf(warehouse);
        return stock != null && stock.freeSlots() > 0;
    }

    @Override
    public boolean isFull()
    {
        sync();
        if (attached.isEmpty())
        {
            return false;
        }
        return freeSlots <= 0;
    }

    @Override
    public boolean hasRoomFor(@NotNull final ItemStack stack)
    {
        sync();
        if (freeSlots > 0)
        {
            return true;
        }
        final Level world = colony.getWorld();
        for (final IWareHouse wareHouse : colony.getServerBuildingManager().getWareHouses())
        {
            if (wareHouse instanceof final BuildingWareHouse building && world != null
                  && building.getRackIndex().rackWithMergeSlot(world, stack) != null)
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public int itemCap()
    {
        return RsFlags.storageItemCap();
    }

    @Override
    public boolean isAtCap(@NotNull final ItemStorage key)
    {
        final int cap = itemCap();
        return cap > 0 && physical(key) >= cap;
    }

    // ------------------------------------------------------------------ tasks and couriers

    /**
     * @param position a position inside a warehouse (a rack, the hut).
     * @return the id of the warehouse that contains it, null when none does.
     */
    @Nullable
    public BlockPos owningWarehouse(@NotNull final BlockPos position)
    {
        for (final IWareHouse wareHouse : colony.getServerBuildingManager().getWareHouses())
        {
            if (wareHouse.hasContainerPosition(position))
            {
                return wareHouse.getID();
            }
        }
        return null;
    }

    /**
     * @param courier a courier job.
     * @return where its current delivery starts (the rack it takes the items from), null when it has no delivery.
     */
    @Nullable
    private BlockPos currentTaskStart(final JobDeliveryman courier)
    {
        final List<IToken<?>> queue = courier.getTaskQueue();
        if (queue.isEmpty())
        {
            return null;
        }
        final IRequest<?> head = colony.getRequestManager().getRequestForToken(queue.get(0));
        if (head != null && head.getRequest() instanceof final Delivery delivery)
        {
            return delivery.getStart().getInDimensionLocation();
        }
        return null;
    }

    // ------------------------------------------------------------------ flows

    /**
     * Records that items moved.
     *
     * @param from  where they were taken from.
     * @param to    where they were put.
     * @param stack the items (count is the amount moved).
     */
    public void recordFlow(@NotNull final BlockPos from, @NotNull final BlockPos to, @NotNull final ItemStack stack)
    {
        if (stack.isEmpty() || colony.getWorld() == null)
        {
            return;
        }
        final long now = colony.getWorld().getGameTime();
        flows.addLast(new FlowEvent(from, to, new ItemStorage(stack.copyWithCount(1)), stack.getCount(), now));
        while (flows.size() > FLOW_HISTORY_MAX || (!flows.isEmpty() && now - flows.peekFirst().tick() > FLOW_HISTORY_TICKS))
        {
            flows.removeFirst();
        }
    }

    private int movedInto(final BlockPos warehouse, final long now)
    {
        int sum = 0;
        for (final FlowEvent event : flows)
        {
            if (event.to().equals(warehouse) && now - event.tick() <= MINUTE_TICKS)
            {
                sum += event.count();
            }
        }
        return sum;
    }

    private int movedOutOf(final BlockPos warehouse, final long now)
    {
        int sum = 0;
        for (final FlowEvent event : flows)
        {
            if (event.from().equals(warehouse) && now - event.tick() <= MINUTE_TICKS)
            {
                sum += event.count();
            }
        }
        return sum;
    }

    @NotNull
    @Override
    public List<StorageFlow> flowsSince(final long sinceTick)
    {
        record Route(BlockPos from, BlockPos to, ItemStorage key)
        {
        }
        final Map<Route, int[]> merged = new LinkedHashMap<>();
        final Map<Route, long[]> last = new HashMap<>();
        for (final FlowEvent event : flows)
        {
            if (event.tick() < sinceTick)
            {
                continue;
            }
            final Route route = new Route(event.from(), event.to(), event.key());
            merged.computeIfAbsent(route, k -> new int[1])[0] += event.count();
            last.computeIfAbsent(route, k -> new long[1])[0] = event.tick();
        }
        final List<StorageFlow> result = new ArrayList<>(merged.size());
        for (final Map.Entry<Route, int[]> entry : merged.entrySet())
        {
            final Route route = entry.getKey();
            result.add(new StorageFlow(route.from(), route.to(), route.key().getItemStack().copy(), entry.getValue()[0], last.get(route)[0]));
        }
        result.sort(Comparator.comparingLong(StorageFlow::lastTick));
        return result;
    }

    // ------------------------------------------------------------------ notices

    /**
     * Tells the colony's players that nothing more fits, at most once in five minutes.
     *
     * @param stack the stack that found no room.
     * @return true when the message was sent.
     */
    public boolean noticeFull(@NotNull final ItemStack stack)
    {
        final Level world = colony.getWorld();
        if (world == null || world.getGameTime() - lastFullNotice < FULL_NOTICE_TICKS)
        {
            return false;
        }
        lastFullNotice = world.getGameTime();
        StorageNotices.colonyFull(colony, stack);
        return true;
    }
}
