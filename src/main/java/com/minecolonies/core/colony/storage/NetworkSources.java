package com.minecolonies.core.colony.storage;

import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.INonExhaustiveDeliverable;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
import com.minecolonies.core.colony.requestsystem.RsAccess;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Which warehouses can serve a request, best first. A warehouse is a candidate when it holds matching stock that no
 * reservation holds and a courier can reach it; the best is the one that finishes the trip first
 * ({@link DispatchCost#fetchTicks}: queue at its ramps, handling, walk to the requester), not the nearest.
 * <p>
 * The answer comes from the colony stock index, so it costs no walk over the racks. It is cached for the game tick and
 * the state of the index and the ledger, because every warehouse resolver asks about the same request one after another.
 */
public final class NetworkSources
{
    /**
     * One warehouse that can serve a request.
     *
     * @param warehouse the warehouse.
     * @param available what it holds that no reservation holds (an estimate, exact for requests that keep nothing back).
     * @param cost      estimated completion time of fetching the request's items from it, in ticks.
     */
    public record Candidate(BuildingWareHouse warehouse, int available, int cost)
    {
    }

    /**
     * The candidates of a request.
     *
     * @param ordered the candidates, best first.
     * @param total   the sum of their available amounts.
     */
    public record Plan(List<Candidate> ordered, int total)
    {
        /**
         * @return the best candidate, null when no warehouse can serve.
         */
        @Nullable
        public Candidate owner()
        {
            return ordered.isEmpty() ? null : ordered.get(0);
        }
    }

    private record Cached(long tick, long storageVersion, long ledgerRevision, int taskLoadHash, Plan plan)
    {
    }

    private static final Plan EMPTY = new Plan(List.of(), 0);

    private NetworkSources()
    {
    }

    /**
     * @param colony  the colony.
     * @param manager its request manager.
     * @param request the request.
     * @return the plan for the request.
     */
    @NotNull
    public static Plan plan(@NotNull final Colony colony, @NotNull final IRequestManager manager, @NotNull final IRequest<? extends IDeliverable> request)
    {
        final ColonyStorage storage = ColonyStorage.of(colony);
        final long tick = colony.getWorld().getGameTime();
        final ReservationLedger ledger = RsAccess.ledger(manager);
        final long ledgerRevision = ledger == null ? -1L : ledger.revision();
        final long version = storage.version();
        final int loadHash = loadHash(colony, storage);
        final Map<IToken<?>, Object> perColony = storage.planCache();
        final Cached cached = perColony.get(request.getId()) instanceof final Cached c ? c : null;
        if (cached != null && cached.tick() == tick && cached.storageVersion() == version && cached.ledgerRevision() == ledgerRevision && cached.taskLoadHash() == loadHash)
        {
            return cached.plan();
        }
        final Plan plan = compute(colony, storage, request, ledger);
        if (perColony.size() > 512)
        {
            perColony.clear();
        }
        perColony.put(request.getId(), new Cached(tick, version, ledgerRevision, loadHash, plan));
        return plan;
    }

    private static int loadHash(final Colony colony, final ColonyStorage storage)
    {
        int hash = 1;
        for (final IWareHouse wareHouse : colony.getServerBuildingManager().getWareHouses())
        {
            hash = hash * 31 + storage.taskLoad(wareHouse.getID());
        }
        return hash;
    }

    @NotNull
    private static Plan compute(final Colony colony, final ColonyStorage storage, final IRequest<? extends IDeliverable> request, @Nullable final ReservationLedger ledger)
    {
        final IDeliverable wanted = request.getRequest();
        final Predicate<ItemStack> matches = wanted::matches;
        final int keep = wanted instanceof final INonExhaustiveDeliverable nonExhaustive ? nonExhaustive.getLeftOver() : 0;
        final BlockPos destination = request.getRequester().getLocation().getInDimensionLocation();
        final boolean anyCourier = StorageNetwork.anyCourier(colony);
        final List<Candidate> candidates = new ArrayList<>();
        int total = 0;
        for (final IWareHouse wareHouse : colony.getServerBuildingManager().getWareHouses())
        {
            if (!(wareHouse instanceof final BuildingWareHouse building) || building.getTileEntity() == null)
            {
                continue;
            }
            if (anyCourier && !StorageNetwork.served(colony, building))
            {
                // Couriers exist, but none reaches this warehouse: a delivery out of it would never be carried.
                continue;
            }
            final WarehouseStock stock = storage.stockOf(building.getID());
            if (stock == null)
            {
                continue;
            }
            final int reserved = ledger == null ? 0 : ledger.reservedStock(building.getID(), matches);
            final int physical = keep > 0 ? stock.table().countKeeping(matches, keep, Integer.MAX_VALUE) : stock.table().count(matches, Integer.MAX_VALUE);
            final int available = Math.max(0, physical - reserved);
            if (available <= 0)
            {
                continue;
            }
            final int items = Math.max(1, Math.min(wanted.getCount(), available));
            candidates.add(new Candidate(building, available, DispatchCost.fetchTicks(building, storage.taskLoad(building.getID()), destination, items)));
            total += available;
        }
        if (candidates.isEmpty())
        {
            return EMPTY;
        }
        candidates.sort(Comparator.comparingInt(Candidate::cost).thenComparingInt(c -> c.warehouse().getID().getX()).thenComparingInt(c -> c.warehouse().getID().getZ())
                          .thenComparingInt(c -> c.warehouse().getID().getY()));
        return new Plan(List.copyOf(candidates), total);
    }
}
