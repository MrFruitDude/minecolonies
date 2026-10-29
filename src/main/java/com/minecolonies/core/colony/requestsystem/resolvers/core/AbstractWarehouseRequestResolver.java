package com.minecolonies.core.colony.requestsystem.resolvers.core;

import com.google.common.collect.Lists;
import com.google.common.reflect.TypeToken;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.ModBuildings;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.requestsystem.location.ILocation;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.INonExhaustiveDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.MinimumStack;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.Log;
import com.ldtteam.structurize.api.util.Tuple;
import com.minecolonies.api.util.constant.TranslationConstants;
import com.minecolonies.api.util.constant.TypeConstants;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.requestsystem.RsAccess;
import com.minecolonies.core.colony.requestsystem.RsCourierHooks;
import com.minecolonies.core.colony.requestsystem.reservation.DestinationRoom;
import com.minecolonies.core.colony.requestsystem.reservation.Reservation;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationKind;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationReason;
import com.minecolonies.core.colony.requestsystem.wait.RequestWaitTracker;
import com.minecolonies.core.colony.storage.ColonyStorage;
import com.minecolonies.core.colony.storage.NetworkSources;
import com.minecolonies.core.colony.storage.StorageNetwork;
import com.minecolonies.api.colony.requestsystem.request.WaitReason;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
import com.minecolonies.core.tileentities.TileEntityWareHouse;
import com.minecolonies.core.tileentities.WarehouseRackIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static com.minecolonies.api.colony.requestsystem.requestable.deliveryman.AbstractDeliverymanRequestable.getDefaultDeliveryPriority;
import static com.minecolonies.api.util.constant.RSConstants.CONST_WAREHOUSE_RESOLVER_PRIORITY;

/**
 * ----------------------- Not Documented Object ---------------------
 */
public abstract class AbstractWarehouseRequestResolver extends AbstractRequestResolver<IDeliverable>
{
    /**
     * Buildings {@link #canResolveRequest} looked at while summing the other warehouses' stock. Server thread statistic
     * for tests.
     */
    public static long otherBuildingsVisited;

    /**
     * The matching stacks the last attempt from stock read, kept so the follow-up that the request manager runs right
     * after it (same request, same tick, no rack change) does not walk the racks a second time. Transient: never saved.
     *
     * @param request    the request the stacks were read for.
     * @param stacks     the stacks with their rack positions, as getMatchingItemStacksInWarehouse returned them.
     * @param index      the warehouse rack index they were read through.
     * @param changes    the index's change count at the read.
     * @param loadEpoch  the rack load epoch at the read.
     * @param gameTime   the game time of the read.
     */
    private record MatchedStacks(IToken<?> request, List<Tuple<ItemStack, BlockPos>> stacks, WarehouseRackIndex index, long changes, int loadEpoch,
                                 long gameTime)
    {
    }

    @Nullable
    private MatchedStacks lastMatched;

    /**
     * The stacks of one warehouse a network attempt read.
     *
     * @param warehouse the warehouse building's id.
     * @param stacks    the matching stacks with their rack positions, as read (reservations not yet taken off).
     */
    private record WarehouseRead(BlockPos warehouse, List<Tuple<ItemStack, BlockPos>> stacks)
    {
    }

    /**
     * What a network attempt read, in the order of the plan (best warehouse first). Transient, like {@link #lastMatched}.
     *
     * @param request      the request.
     * @param reads        the warehouses read.
     * @param version      the colony storage version at the read.
     * @param loadEpoch    the rack load epoch at the read.
     * @param gameTime     the game time of the read.
     */
    private record NetworkMatched(IToken<?> request, List<WarehouseRead> reads, long version, int loadEpoch, long gameTime)
    {
    }

    @Nullable
    private NetworkMatched lastNetworkMatched;

    /**
     * Round 13 (L7): a remainder the last attempt planned, sent when the request is assigned here (not in the attempt, which another resolver
     * may still win).
     *
     * @param request   the request that was served from stock.
     * @param remainder what goes to the crafters.
     * @param key       the item key.
     */
    private record PendingRemainder(IToken<?> request, IDeliverable remainder, Object key)
    {
    }

    @Nullable
    private PendingRemainder pendingRemainder;

    /**
     * Round 13 (L7): the open remainder per item key. Transient: after a load a second remainder may be sent while an old one is still open.
     */
    private final Map<Object, IToken<?>> openRemainders = new HashMap<>();

    /**
     * Test and bench counter: remainders sent to crafters.
     */
    public static long remaindersSent;

    public AbstractWarehouseRequestResolver(
      @NotNull final ILocation location,
      @NotNull final IToken<?> token)
    {
        super(location, token);
    }

    @Override
    public TypeToken<? extends IDeliverable> getRequestType()
    {
        return TypeConstants.DELIVERABLE;
    }

    /**
     * Storage network: whether this resolver is the one for the request (concrete items or everything else).
     *
     * @param deliverable the request.
     * @return true if this resolver handles it.
     */
    protected abstract boolean handles(final IDeliverable deliverable);

    /**
     * Override to implement specific warehouse counting rules.
     * @param wareHouse the warehouse to check.
     * @param requestToCheck the requested item.
     * @return the available quantity.
     */
    protected abstract int getWarehouseInternalCount(final BuildingWareHouse wareHouse, final IRequest<? extends IDeliverable> requestToCheck);

    @Override
    public boolean canResolveRequest(@NotNull final IRequestManager manager, final IRequest<? extends IDeliverable> requestToCheck)
    {
        if (requestToCheck.getRequester().getLocation().equals(getLocation()))
        {
            // Don't fulfill its own requests
            return false;
        }

        if (!manager.getColony().getWorld().isClientSide())
        {
            final Colony colony = (Colony) manager.getColony();
            final IBuilding wareHouse = colony.getServerBuildingManager().getBuilding(getLocation().getInDimensionLocation());
            if (wareHouse == null)
            {
                return false;
            }

            if (requestToCheck.getRequest() instanceof MinimumStack)
            {
                final IBuilding otherWarehouse = colony.getServerBuildingManager().getBuilding(requestToCheck.getRequester().getLocation().getInDimensionLocation());
                if (otherWarehouse != null && otherWarehouse.getBuildingType() == ModBuildings.wareHouse.get())
                {
                    return false;
                }
            }

            if (!isRequestChainValid(manager, requestToCheck))
            {
                return false;
            }

            if (StorageNetwork.on(manager))
            {
                return networkCanResolve(colony, (BuildingWareHouse) wareHouse, manager, requestToCheck);
            }

            int totalCount = getWarehouseInternalCount((BuildingWareHouse) wareHouse, requestToCheck);
            if (totalCount <= 0)
            {
                return false;
            }

            try
            {
                // The maintained warehouse list, not a walk over every building. The answer does not depend on the order.
                for (final IWareHouse building : colony.getServerBuildingManager().getWareHouses())
                {
                    otherBuildingsVisited++;
                    if (building.getBuildingType() == ModBuildings.wareHouse.get() && building != wareHouse)
                    {
                        totalCount += getWarehouseInternalCount((BuildingWareHouse) building, requestToCheck);
                        if (totalCount >= RsCourierHooks.outstanding(requestToCheck))
                        {
                            return true;
                        }
                    }
                }
                return totalCount >= RsCourierHooks.outstandingMinimum(requestToCheck);
            }
            catch (Exception e)
            {
                Log.getLogger().error(e);
            }
        }
        return false;
    }

    /**
     * Use to verify that a request chain is valid, and doesn't contain recursive cycles.
     * @param manager
     * @param requestToCheck
     * @return
     */
    public boolean isRequestChainValid(@NotNull final IRequestManager manager, final IRequest<?> requestToCheck)
    {
        if (!requestToCheck.hasParent())
        {
            return true;
        }

        final IRequest<?> parentRequest = manager.getRequestForToken(requestToCheck.getParent());

        //Should not happen but just to be sure.
        if (parentRequest == null)
        {
            return true;
        }

        return isRequestChainValid(manager, parentRequest);
    }

    @Nullable
    @Override
    public List<IToken<?>> attemptResolveRequest(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends IDeliverable> request)
    {
        if (manager.getColony().getWorld().isClientSide())
        {
            return Lists.newArrayList();
        }

        if (!(manager.getColony() instanceof Colony))
        {
            return Lists.newArrayList();
        }

        final Colony colony = (Colony) manager.getColony();
        final IBuilding wareHouseBuilding = colony.getServerBuildingManager().getBuilding(getLocation().getInDimensionLocation());
        final TileEntityWareHouse wareHouse = wareHouseBuilding == null ? null : (TileEntityWareHouse) wareHouseBuilding.getTileEntity();
        lastMatched = null;
        lastNetworkMatched = null;
        pendingRemainder = null;
        if (wareHouse == null)
        {
            return Lists.newArrayList();
        }
        if (StorageNetwork.on(manager))
        {
            return networkAttempt(colony, manager, request);
        }
        final int totalRequested = RsCourierHooks.outstanding(request);
        int totalAvailable = 0;
        final Map<ItemStorage, Integer> storages = new HashMap<>();

        final int toKeep = request.getRequest() instanceof INonExhaustiveDeliverable
            ? ((INonExhaustiveDeliverable) request.getRequest()).getLeftOver()
            : 0;

        final ReservationLedger ledger = RsAccess.ledger(manager);
        final List<Tuple<ItemStack, BlockPos>> raw = wareHouse.getMatchingItemStacksInWarehouse(itemStack -> request.getRequest().matches(itemStack));
        // RS1: what is promised to other requests is not there.
        final List<Tuple<ItemStack, BlockPos>> inv = unreserved(ledger, raw, wareHouseBuilding.getID(), counts(manager, request));
        for (final Tuple<ItemStack, BlockPos> stack : inv)
        {
            final ItemStack s = stack.getA();
            if (s.isEmpty())
            {
                continue;
            }

            if (toKeep > 0)
            {
                final ItemStorage key = new ItemStorage(s);
                int alreadyKept = storages.getOrDefault(key, 0);
                if (alreadyKept < toKeep)
                {
                    final int stackCount = s.getCount();

                    if (stackCount + alreadyKept <= toKeep)
                    {
                        storages.put(key, alreadyKept + stackCount);
                        continue;
                    }
                    
                    int toKeepThisStack = toKeep - alreadyKept;
                    storages.put(key, alreadyKept + toKeepThisStack);
                    totalAvailable += stackCount - toKeepThisStack;
                    continue;
                }
            }
            totalAvailable += s.getCount();
        }

        if (ledger != null && totalAvailable > 0 && !destinationHasRoom(manager, request, inv, totalAvailable))
        {
            // RS1: the destination has no room for it once what is already on its way is counted.
            final RequestWaitTracker tracker = RequestWaitTracker.of(manager);
            if (tracker != null)
            {
                tracker.hint(request.getId(), WaitReason.TARGET_FULL);
            }
            return null;
        }

        if (totalAvailable >= totalRequested || totalAvailable >= RsCourierHooks.outstandingMinimum(request))
        {
            // Resolved from stock: the request manager asks for the follow-up next, before anything else runs.
            lastMatched = matched(wareHouse, request, raw);
            pendingRemainder = planRemainder(colony, manager, request, ledger, totalAvailable, totalRequested);
            return Lists.newArrayList();
        }

        if (ledger != null)
        {
            // Short of stock: the request manager asks for the rest elsewhere; what is here is held for it meanwhile (onRequestAssigned).
            lastMatched = matched(wareHouse, request, raw);
        }

        if (totalAvailable < 0)
        {
            totalAvailable = 0;
        }

        final int totalRemainingRequired = totalRequested - totalAvailable;
        return Lists.newArrayList(manager.createRequest(this, request.getRequest().copyWithCount(totalRemainingRequired)));
    }

    @Override
    public void resolveRequest(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends IDeliverable> request)
    {
        manager.updateRequestState(request.getId(), RequestState.RESOLVED);
    }

    @Nullable
    @Override
    public List<IRequest<?>> getFollowupRequestForCompletion(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends IDeliverable> completedRequest)
    {
        if (manager.getColony().getWorld().isClientSide())
        {
            return null;
        }

        final Colony colony = (Colony) manager.getColony();
        final IBuilding wareHouseBuilding = colony.getServerBuildingManager().getBuilding(getLocation().getInDimensionLocation());
        final TileEntityWareHouse wareHouse = wareHouseBuilding == null ? null : (TileEntityWareHouse) wareHouseBuilding.getTileEntity();

        if (wareHouse == null)
        {
            return null;
        }

        if (StorageNetwork.on(manager))
        {
            return networkFollowup(colony, manager, wareHouse, completedRequest);
        }

        List<IRequest<?>> deliveries = Lists.newArrayList();
        int remainingCount = RsCourierHooks.outstanding(completedRequest);

        final Map<ItemStorage, Integer> storages = new HashMap<>();

        final int keep = completedRequest.getRequest() instanceof INonExhaustiveDeliverable ? ((INonExhaustiveDeliverable) completedRequest.getRequest()).getLeftOver() : 0;

        final ReservationLedger ledger = RsAccess.ledger(manager);
        final Map<HoldKey, Integer> heldByHold = heldByKey(ledger, completedRequest.getId());
        final Set<BlockPos> chain = ledger == null ? Set.of() : ReservationLedger.requesterChain(manager, completedRequest);
        if (ledger != null)
        {
            // The hold the attempt put on this request turns into one hold per delivery below.
            ledger.releaseQuiet(completedRequest.getId(), RsAccess.now(manager));
        }
        final List<Tuple<ItemStack, BlockPos>> targetStacks = unreserved(ledger, matchingStacksForFollowup(wareHouse, completedRequest), wareHouse.getBuilding().getID(),
          ReservationLedger.countsAgainst(chain));
        final String requesterName = ledger == null ? "" : describe(manager, completedRequest);
        for (final Tuple<ItemStack, BlockPos> tuple : targetStacks)
        {
            if (ItemStackUtils.isEmpty(tuple.getA()))
            {
                continue;
            }

            int leftOver = tuple.getA().getCount();
            if (keep > 0)
            {
                int kept = storages.getOrDefault(new ItemStorage(tuple.getA()), 0);
                if (kept < keep)
                {
                    if (leftOver + kept <= keep)
                    {
                        storages.put(new ItemStorage(tuple.getA()), storages.getOrDefault(new ItemStorage(tuple.getA()), 0) + tuple.getA().getCount());
                        continue;
                    }
                    int toKeepThisStack = keep - kept;
                    leftOver -= toKeepThisStack;
                    storages.put(new ItemStorage(tuple.getA()), storages.getOrDefault(new ItemStorage(tuple.getA()), 0) + toKeepThisStack);
                }
            }

            int count = Math.min(remainingCount, leftOver);
            final ItemStack matchingStack = tuple.getA().copy();
            matchingStack.setCount(count);

            completedRequest.addDelivery(matchingStack);

            final ILocation itemStackLocation = manager.getFactoryController().getNewInstance(TypeConstants.ILOCATION, tuple.getB(), wareHouse.getLevel().dimension());

            final Delivery delivery =
              new Delivery(itemStackLocation, completedRequest.getRequester().getLocation(), matchingStack, getDefaultDeliveryPriority(true));


            final IToken<?> requestToken = manager.createRequest(this, delivery);
            deliveries.add(manager.getRequestForToken(requestToken));
            if (ledger != null)
            {
                // RS1: reserve-then-commit. The stock is promised at its source, the room at the destination, until the courier moves it.
                final long now = RsAccess.now(manager);
                final ItemStorage key = new ItemStorage(matchingStack);
                ledger.reserveStock(wareHouse.getBuilding().getID(), tuple.getB(), key, count, requestToken, requesterName, ReservationReason.WAREHOUSE_DELIVERY, now);
                ledger.reserveSpace(completedRequest.getRequester().getLocation().getInDimensionLocation(), key, count, requestToken, requesterName, ReservationReason.WAREHOUSE_DELIVERY, now);
                handOverBeyondHold(ledger, wareHouse.getBuilding().getID(), key, count, heldByHold, chain, now);
            }
            remainingCount -= count;
            if (remainingCount <= 0)
            {
                break;
            }
        }

        return deliveries.isEmpty() ? null : deliveries;
    }

    /**
     * RS1: the request was given to this resolver. What the attempt counted is held for it, so that nothing else is promised
     * the same stock while its children (the rest, from elsewhere) are still being served.
     */
    @Override
    public void onRequestAssigned(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends IDeliverable> request, final boolean simulation)
    {
        super.onRequestAssigned(manager, request, simulation);
        final PendingRemainder pending = pendingRemainder;
        final boolean sendPending = !simulation && pending != null && pending.request().equals(request.getId());
        if (sendPending)
        {
            pendingRemainder = null;
        }
        holdAssigned(manager, request, simulation);
        if (sendPending)
        {
            // Sent after the holds: the crafter's input requests can re-enter this resolver and clear the remembered match.
            sendRemainder(manager, pending);
        }
    }

    private void holdAssigned(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends IDeliverable> request, final boolean simulation)
    {
        final ReservationLedger ledger = RsAccess.ledger(manager);
        if (!simulation && ledger != null && StorageNetwork.on(manager) && lastNetworkMatched != null && lastNetworkMatched.request().equals(request.getId()))
        {
            networkHold(manager, ledger, request, lastNetworkMatched);
            return;
        }
        final MatchedStacks matched = lastMatched;
        if (simulation || ledger == null || matched == null || !matched.request().equals(request.getId()) || !(manager.getColony() instanceof final Colony colony))
        {
            return;
        }
        final IBuilding wareHouse = colony.getServerBuildingManager().getBuilding(getLocation().getInDimensionLocation());
        if (wareHouse == null)
        {
            return;
        }
        final int keep = request.getRequest() instanceof INonExhaustiveDeliverable ne ? ne.getLeftOver() : 0;
        int remaining = RsCourierHooks.outstanding(request);
        final Map<ItemStorage, Integer> kept = new HashMap<>();
        final String name = describe(manager, request);
        final Set<BlockPos> chain = ReservationLedger.requesterChain(manager, request);
        for (final Tuple<ItemStack, BlockPos> tuple : unreserved(ledger, matched.stacks(), wareHouse.getID(), ReservationLedger.countsAgainst(chain)))
        {
            if (remaining <= 0)
            {
                break;
            }
            int available = tuple.getA().getCount();
            if (keep > 0)
            {
                final ItemStorage storage = new ItemStorage(tuple.getA());
                final int alreadyKept = kept.getOrDefault(storage, 0);
                final int toKeep = Math.min(Math.max(0, keep - alreadyKept), available);
                kept.put(storage, alreadyKept + toKeep);
                available -= toKeep;
            }
            final int count = Math.min(remaining, available);
            if (count > 0)
            {
                ledger.reserveStock(wareHouse.getID(), tuple.getB(), new ItemStorage(tuple.getA()), count, request.getId(), name, ReservationReason.WAREHOUSE_DELIVERY, RsAccess.now(manager));
                // stock an add-on held for this request's building is held by the request from now on
                ledger.handOver(wareHouse.getID(), new ItemStorage(tuple.getA()), count, chain, RsAccess.now(manager));
                remaining -= count;
            }
        }
    }

    // ------------------------------------------------------------------ storage network

    /**
     * Storage network: this resolver answers for a request when its warehouse is the best source of it (the colony index
     * says the stock is there, a courier reaches it, and it completes first), so that exactly one warehouse resolver attempts it.
     */
    private boolean networkCanResolve(
      @NotNull final Colony colony,
      @NotNull final BuildingWareHouse wareHouse,
      @NotNull final IRequestManager manager,
      @NotNull final IRequest<? extends IDeliverable> request)
    {
        if (!handles(request.getRequest()))
        {
            return false;
        }
        final NetworkSources.Plan plan = NetworkSources.plan(colony, manager, request);
        final NetworkSources.Candidate owner = plan.owner();
        if (owner == null || owner.warehouse() != wareHouse)
        {
            return false;
        }
        return plan.total() >= RsCourierHooks.outstanding(request) || plan.total() >= RsCourierHooks.outstandingMinimum(request);
    }

    /**
     * Storage network: reads the racks of the planned warehouses, best first, until the request is covered.
     */
    @NotNull
    private List<WarehouseRead> networkRead(@NotNull final Colony colony, @NotNull final IRequestManager manager, @NotNull final IRequest<? extends IDeliverable> request, final boolean all)
    {
        final NetworkSources.Plan plan = NetworkSources.plan(colony, manager, request);
        final ReservationLedger ledger = RsAccess.ledger(manager);
        final int keep = request.getRequest() instanceof final INonExhaustiveDeliverable nonExhaustive ? nonExhaustive.getLeftOver() : 0;
        final List<WarehouseRead> reads = new ArrayList<>();
        int covered = 0;
        for (final NetworkSources.Candidate candidate : plan.ordered())
        {
            final TileEntityWareHouse tileEntity = (TileEntityWareHouse) candidate.warehouse().getTileEntity();
            if (tileEntity == null)
            {
                continue;
            }
            final List<Tuple<ItemStack, BlockPos>> raw = tileEntity.getMatchingItemStacksInWarehouse(itemStack -> request.getRequest().matches(itemStack));
            reads.add(new WarehouseRead(candidate.warehouse().getID(), raw));
            if (!all)
            {
                covered += availableOf(unreserved(ledger, raw, candidate.warehouse().getID(), counts(manager, request)), keep);
                if (covered >= RsCourierHooks.outstanding(request))
                {
                    break;
                }
            }
        }
        return reads;
    }

    /**
     * Round 13 (L7, c2 decision): the warehouse hands out what it has now, as before, and when that is less than a quarter of the request and a
     * staffed crafter can actually make the item (its inputs are in store or another staffed crafter makes them), the remainder goes to the
     * crafters as a separate request of the warehouse's own, delivered into the warehouse. Stock is never held back while a request waits. One
     * open remainder per item and warehouse at a time.
     *
     * @return the remainder to send once the request is assigned here, null for none.
     */
    @Nullable
    private PendingRemainder planRemainder(@NotNull final Colony colony, @NotNull final IRequestManager manager, @NotNull final IRequest<? extends IDeliverable> request,
      @Nullable final ReservationLedger ledger, final int available, final int requested)
    {
        if (!remainderWorthCrafting(ledger != null, available, requested))
        {
            return null;
        }
        final Object key = com.minecolonies.core.colony.requestsystem.wait.WaitDiagnosis.itemKey(request.getRequest());
        if (key == null || remainderOpen(manager, key) || !com.minecolonies.core.colony.requestsystem.wait.WaitDiagnosis.crafterCanMake(colony, request.getRequest()))
        {
            return null;
        }
        return new PendingRemainder(request.getId(), request.getRequest().copyWithCount(requested - available), key);
    }

    /**
     * @return true while the remainder this warehouse sent for the item is still being made or carried.
     */
    private boolean remainderOpen(@NotNull final IRequestManager manager, @NotNull final Object key)
    {
        final IToken<?> token = openRemainders.get(key);
        if (token == null)
        {
            return false;
        }
        final IRequest<?> open = manager.getRequestForToken(token);
        if (open == null || open.getState().ordinal() >= RequestState.COMPLETED.ordinal())
        {
            openRemainders.remove(key);
            return false;
        }
        return true;
    }

    /**
     * Round 13 (L7, c2): creates and assigns the remainder. It stays only when a crafter took it; anything else (the player, a retry) is cancelled,
     * so nothing new reaches the player.
     */
    private void sendRemainder(@NotNull final IRequestManager manager, @NotNull final PendingRemainder pending)
    {
        try
        {
            final IToken<?> token = manager.createRequest(this, pending.remainder());
            manager.assignRequest(token);
            final IRequest<?> created = manager.getRequestForToken(token);
            if (created == null || created.getState().ordinal() >= RequestState.COMPLETED.ordinal())
            {
                return;
            }
            final Object resolver = manager.getResolverForRequest(token);
            if (resolver instanceof AbstractCraftingRequestResolver || resolver instanceof AbstractCraftingProductionResolver<?>)
            {
                openRemainders.put(pending.key(), token);
                remaindersSent++;
            }
            else
            {
                manager.updateRequestState(token, RequestState.CANCELLED);
            }
        }
        catch (final RuntimeException e)
        {
            Log.getLogger().warn("Warehouse remainder request for {} failed", pending.remainder(), e);
        }
    }

    /**
     * Round 13 (L7): the open remainder this warehouse sent for the deliverable's items, for tests and the UI.
     *
     * @param manager the request manager.
     * @param wanted  the deliverable.
     * @return its token, null for none.
     */
    @Nullable
    public IToken<?> openRemainder(@NotNull final IRequestManager manager, @NotNull final IDeliverable wanted)
    {
        final Object key = com.minecolonies.core.colony.requestsystem.wait.WaitDiagnosis.itemKey(wanted);
        return key != null && remainderOpen(manager, key) ? openRemainders.get(key) : null;
    }

    /**
     * Round 13 (L7): the pure part of {@link #planRemainder}.
     *
     * @param reservations the reservation ledger is on.
     * @param available    what the warehouses can hand out now.
     * @param requested    what the request asks for.
     * @return true when the remainder is worth sending to the crafters at once (a crafter must still exist).
     */
    public static boolean remainderWorthCrafting(final boolean reservations, final int available, final int requested)
    {
        return reservations && available > 0 && available < requested && (long) available * 4 < requested;
    }

    /**
     * What the stacks of one warehouse offer when {@code keep} of each item stays in it.
     */
    private static int availableOf(@NotNull final List<Tuple<ItemStack, BlockPos>> stacks, final int keep)
    {
        int total = 0;
        final Map<ItemStorage, Integer> kept = new HashMap<>();
        for (final Tuple<ItemStack, BlockPos> tuple : stacks)
        {
            final ItemStack stack = tuple.getA();
            if (stack.isEmpty())
            {
                continue;
            }
            int count = stack.getCount();
            if (keep > 0)
            {
                final ItemStorage key = new ItemStorage(stack);
                final int alreadyKept = kept.getOrDefault(key, 0);
                final int keepHere = Math.min(Math.max(0, keep - alreadyKept), count);
                kept.put(key, alreadyKept + keepHere);
                count -= keepHere;
            }
            total += count;
        }
        return total;
    }

    @Nullable
    private List<IToken<?>> networkAttempt(@NotNull final Colony colony, @NotNull final IRequestManager manager, @NotNull final IRequest<? extends IDeliverable> request)
    {
        final ReservationLedger ledger = RsAccess.ledger(manager);
        final int keep = request.getRequest() instanceof final INonExhaustiveDeliverable nonExhaustive ? nonExhaustive.getLeftOver() : 0;
        final List<WarehouseRead> reads = networkRead(colony, manager, request, false);
        int totalAvailable = 0;
        final List<Tuple<ItemStack, BlockPos>> readable = new ArrayList<>();
        for (final WarehouseRead read : reads)
        {
            final List<Tuple<ItemStack, BlockPos>> inv = unreserved(ledger, read.stacks(), read.warehouse(), counts(manager, request));
            totalAvailable += availableOf(inv, keep);
            readable.addAll(inv);
        }
        final int totalRequested = RsCourierHooks.outstanding(request);

        if (ledger != null && totalAvailable > 0 && !destinationHasRoom(manager, request, readable, totalAvailable))
        {
            final RequestWaitTracker tracker = RequestWaitTracker.of(manager);
            if (tracker != null)
            {
                tracker.hint(request.getId(), WaitReason.TARGET_FULL);
            }
            return null;
        }

        final ColonyStorage storage = ColonyStorage.of(colony);
        if (totalAvailable >= totalRequested || totalAvailable >= RsCourierHooks.outstandingMinimum(request))
        {
            lastNetworkMatched = new NetworkMatched(request.getId(), reads, storage.version(), WarehouseRackIndex.loadEpoch(), colony.getWorld().getGameTime());
            pendingRemainder = planRemainder(colony, manager, request, ledger, totalAvailable, totalRequested);
            return Lists.newArrayList();
        }
        if (ledger != null)
        {
            lastNetworkMatched = new NetworkMatched(request.getId(), reads, storage.version(), WarehouseRackIndex.loadEpoch(), colony.getWorld().getGameTime());
        }
        final int totalRemainingRequired = totalRequested - Math.max(0, totalAvailable);
        return Lists.newArrayList(manager.createRequest(this, request.getRequest().copyWithCount(totalRemainingRequired)));
    }

    /**
     * One stack to hand out, with where it sits.
     */
    private record Allocation(BlockPos warehouse, BlockPos rack, ItemStack stack, int count)
    {
    }

    /**
     * Storage network: splits what the request wants over the warehouses' stacks, best warehouse first, rack by rack.
     * {@code keep} of each item stays in each warehouse.
     */
    @NotNull
    private static List<Allocation> allocate(
      @NotNull final List<WarehouseRead> reads,
      @Nullable final ReservationLedger ledger,
      final int wanted,
      final int keep,
      @NotNull final Predicate<Reservation> counts)
    {
        final List<Allocation> result = new ArrayList<>();
        int remaining = wanted;
        for (final WarehouseRead read : reads)
        {
            if (remaining <= 0)
            {
                break;
            }
            final Map<ItemStorage, Integer> kept = new HashMap<>();
            for (final Tuple<ItemStack, BlockPos> tuple : unreserved(ledger, read.stacks(), read.warehouse(), counts))
            {
                if (remaining <= 0)
                {
                    break;
                }
                int available = tuple.getA().getCount();
                if (available <= 0)
                {
                    continue;
                }
                if (keep > 0)
                {
                    final ItemStorage key = new ItemStorage(tuple.getA());
                    final int alreadyKept = kept.getOrDefault(key, 0);
                    final int keepHere = Math.min(Math.max(0, keep - alreadyKept), available);
                    kept.put(key, alreadyKept + keepHere);
                    available -= keepHere;
                }
                final int count = Math.min(remaining, available);
                if (count > 0)
                {
                    result.add(new Allocation(read.warehouse(), tuple.getB(), tuple.getA(), count));
                    remaining -= count;
                }
            }
        }
        return result;
    }

    /**
     * Storage network: the request was given to this resolver; what the attempt counted is held for it, in every warehouse it
     * will come from.
     */
    private void networkHold(
      @NotNull final IRequestManager manager,
      @NotNull final ReservationLedger ledger,
      @NotNull final IRequest<? extends IDeliverable> request,
      @NotNull final NetworkMatched matched)
    {
        final int keep = request.getRequest() instanceof final INonExhaustiveDeliverable nonExhaustive ? nonExhaustive.getLeftOver() : 0;
        final String name = describe(manager, request);
        final long now = RsAccess.now(manager);
        final Set<BlockPos> chain = ReservationLedger.requesterChain(manager, request);
        for (final Allocation allocation : allocate(matched.reads(), ledger, RsCourierHooks.outstanding(request), keep, ReservationLedger.countsAgainst(chain)))
        {
            ledger.reserveStock(allocation.warehouse(), allocation.rack(), new ItemStorage(allocation.stack()), allocation.count(), request.getId(), name,
              ReservationReason.WAREHOUSE_DELIVERY, now);
            ledger.handOver(allocation.warehouse(), new ItemStorage(allocation.stack()), allocation.count(), chain, now);
        }
    }

    @Nullable
    private List<IRequest<?>> networkFollowup(
      @NotNull final Colony colony,
      @NotNull final IRequestManager manager,
      @NotNull final TileEntityWareHouse anyWareHouse,
      @NotNull final IRequest<? extends IDeliverable> completedRequest)
    {
        final ReservationLedger ledger = RsAccess.ledger(manager);
        final Map<HoldKey, Integer> heldByHold = heldByKey(ledger, completedRequest.getId());
        final Set<BlockPos> chain = ledger == null ? Set.of() : ReservationLedger.requesterChain(manager, completedRequest);
        if (ledger != null)
        {
            // The hold the attempt put on this request turns into one hold per delivery below.
            ledger.releaseQuiet(completedRequest.getId(), RsAccess.now(manager));
        }
        final int keep = completedRequest.getRequest() instanceof final INonExhaustiveDeliverable nonExhaustive ? nonExhaustive.getLeftOver() : 0;
        final ColonyStorage storage = ColonyStorage.of(colony);
        final NetworkMatched remembered = lastNetworkMatched;
        lastNetworkMatched = null;
        final List<WarehouseRead> reads;
        if (remembered != null
              && remembered.request().equals(completedRequest.getId())
              && remembered.gameTime() == colony.getWorld().getGameTime()
              && remembered.version() == storage.version()
              && remembered.loadEpoch() == WarehouseRackIndex.loadEpoch())
        {
            reads = remembered.reads();
        }
        else
        {
            reads = networkRead(colony, manager, completedRequest, false);
        }

        final List<IRequest<?>> deliveries = Lists.newArrayList();
        final String requesterName = ledger == null ? "" : describe(manager, completedRequest);
        for (final Allocation allocation : allocate(reads, ledger, RsCourierHooks.outstanding(completedRequest), keep, ReservationLedger.countsAgainst(chain)))
        {
            final ItemStack matchingStack = allocation.stack().copy();
            matchingStack.setCount(allocation.count());
            completedRequest.addDelivery(matchingStack);

            final ILocation itemStackLocation = manager.getFactoryController().getNewInstance(TypeConstants.ILOCATION, allocation.rack(), anyWareHouse.getLevel().dimension());
            final Delivery delivery = new Delivery(itemStackLocation, completedRequest.getRequester().getLocation(), matchingStack, getDefaultDeliveryPriority(true));
            final IToken<?> requestToken = manager.createRequest(this, delivery);
            deliveries.add(manager.getRequestForToken(requestToken));
            if (ledger != null)
            {
                final long now = RsAccess.now(manager);
                final ItemStorage key = new ItemStorage(matchingStack);
                ledger.reserveStock(allocation.warehouse(), allocation.rack(), key, allocation.count(), requestToken, requesterName, ReservationReason.WAREHOUSE_DELIVERY, now);
                ledger.reserveSpace(completedRequest.getRequester().getLocation().getInDimensionLocation(), key, allocation.count(), requestToken, requesterName,
                  ReservationReason.WAREHOUSE_DELIVERY, now);
                handOverBeyondHold(ledger, allocation.warehouse(), key, allocation.count(), heldByHold, chain, now);
            }
        }
        return deliveries.isEmpty() ? null : deliveries;
    }

    /**
     * @return which ledger claims keep stock from the request (all but an add-on's claims held for a building of its chain).
     */
    @NotNull
    private static Predicate<Reservation> counts(@NotNull final IRequestManager manager, @NotNull final IRequest<?> request)
    {
        return ReservationLedger.countsAgainst(ReservationLedger.requesterChain(manager, request));
    }

    /**
     * A hold's amount at one source for one item (normalised key).
     */
    private record HoldKey(BlockPos scope, ItemStorage item)
    {
    }

    /**
     * @return what the request's own hold holds per source and item, before the hold turns into deliveries.
     */
    @NotNull
    private static Map<HoldKey, Integer> heldByKey(@Nullable final ReservationLedger ledger, @NotNull final IToken<?> token)
    {
        final Map<HoldKey, Integer> held = new HashMap<>();
        if (ledger != null)
        {
            for (final Reservation reservation : ledger.reservationsOf(token))
            {
                if (reservation.kind() == ReservationKind.STOCK)
                {
                    held.merge(new HoldKey(reservation.scope(), reservation.key()), reservation.amount(), Integer::sum);
                }
            }
        }
        return held;
    }

    /**
     * A delivery reserved {@code count}: the part its request's hold did not already hold is handed over from an add-on's claims for the chain
     * ({@link ReservationLedger#handOver}); the hold's part was handed over when the hold was made.
     */
    private static void handOverBeyondHold(
      final ReservationLedger ledger,
      final BlockPos scope,
      final ItemStorage key,
      final int count,
      final Map<HoldKey, Integer> heldByHold,
      final Set<BlockPos> chain,
      final long now)
    {
        final HoldKey normal = new HoldKey(scope, new ItemStorage(key.getItemStack().copyWithCount(1)));
        final int covered = Math.min(count, heldByHold.getOrDefault(normal, 0));
        heldByHold.merge(normal, -covered, Integer::sum);
        if (count > covered)
        {
            ledger.handOver(scope, key, count - covered, chain, now);
        }
    }

    /**
     * RS1: the stacks of a warehouse minus what the ledger holds for other requests, rack by rack.
     *
     * @param ledger the ledger, null when reservations are off.
     * @param raw    the stacks as the racks hold them.
     * @return the stacks that are not promised, possibly shortened.
     */
    @NotNull
    private static List<Tuple<ItemStack, BlockPos>> unreserved(
      @Nullable final ReservationLedger ledger,
      @NotNull final List<Tuple<ItemStack, BlockPos>> raw,
      @NotNull final BlockPos scope,
      @NotNull final Predicate<Reservation> counts)
    {
        if (ledger == null || ledger.isEmpty())
        {
            return raw;
        }
        record RackItem(BlockPos rack, ItemStorage item)
        {
        }
        final Map<RackItem, Integer> held = new HashMap<>();
        // an add-on's rack-less MAKE/BUILD claims at the warehouse, except those held for the asking request's chain
        final Map<ItemStorage, Integer> heldForOthers = new HashMap<>();
        final List<Tuple<ItemStack, BlockPos>> result = new ArrayList<>(raw.size());
        for (final Tuple<ItemStack, BlockPos> tuple : raw)
        {
            final ItemStack stack = tuple.getA();
            if (stack.isEmpty())
            {
                result.add(tuple);
                continue;
            }
            final ItemStorage item = new ItemStorage(stack);
            final RackItem key = new RackItem(tuple.getB(), item);
            final int heldHere = held.computeIfAbsent(key, k -> ledger.reservedInContainer(k.rack(), k.item()));
            final int rackSkipped = Math.min(heldHere, stack.getCount());
            held.put(key, heldHere - rackSkipped);
            final int othersHere = heldForOthers.computeIfAbsent(item, k -> ledger.heldForOthers(scope, k, counts));
            final int othersSkipped = Math.min(othersHere, stack.getCount() - rackSkipped);
            heldForOthers.put(item, othersHere - othersSkipped);
            final int skipped = rackSkipped + othersSkipped;
            if (skipped >= stack.getCount())
            {
                continue;
            }
            result.add(skipped == 0 ? tuple : new Tuple<>(stack.copyWithCount(stack.getCount() - skipped), tuple.getB()));
        }
        return result;
    }

    /**
     * RS1: whether the destination of the request can take what the warehouse would send, counting what is already on its
     * way there. Only judged for ordinary buildings; a warehouse (a transfer between warehouses) always has room.
     */
    private boolean destinationHasRoom(
      @NotNull final IRequestManager manager,
      @NotNull final IRequest<? extends IDeliverable> request,
      @NotNull final List<Tuple<ItemStack, BlockPos>> stacks,
      final int totalAvailable)
    {
        if (!(manager.getColony() instanceof final Colony colony) || stacks.isEmpty() || request.getRequest() instanceof MinimumStack)
        {
            return true;
        }
        final BlockPos destinationPos = request.getRequester().getLocation().getInDimensionLocation();
        final IBuilding destination = colony.getServerBuildingManager().getBuilding(destinationPos);
        final ReservationLedger ledger = RsAccess.ledger(manager);
        if (destination == null || ledger == null || destination.getBuildingType() == ModBuildings.wareHouse.get())
        {
            return true;
        }
        final ItemStack sample = stacks.get(0).getA();
        final int room = DestinationRoom.roomFor(destination, sample) - ledger.reservedSpace(destinationPos, request.getRequest()::matches);
        return room >= Math.max(1, Math.min(RsCourierHooks.outstandingMinimum(request), totalAvailable));
    }

    private static String describe(@NotNull final IRequestManager manager, @NotNull final IRequest<?> request)
    {
        try
        {
            return request.getRequester().getRequesterDisplayName(manager, request).getString();
        }
        catch (final RuntimeException e)
        {
            return "?";
        }
    }

    /**
     * Remember the stacks an attempt from stock read.
     *
     * @param wareHouse the warehouse block entity.
     * @param request   the request.
     * @param stacks    the stacks read.
     * @return the record, or null when the warehouse has no rack index to check it against later.
     */
    @Nullable
    private static MatchedStacks matched(
      @NotNull final TileEntityWareHouse wareHouse,
      @NotNull final IRequest<?> request,
      @NotNull final List<Tuple<ItemStack, BlockPos>> stacks)
    {
        if (!(wareHouse.getBuilding() instanceof final BuildingWareHouse building) || wareHouse.getLevel() == null)
        {
            return null;
        }
        final WarehouseRackIndex index = building.getRackIndex();
        return new MatchedStacks(request.getId(), stacks, index, index.changeCount(), WarehouseRackIndex.loadEpoch(), wareHouse.getLevel().getGameTime());
    }

    /**
     * The matching stacks for a follow-up: the attempt's read when it was for this request, in this tick, with no rack
     * change since (the from-stock case, where the follow-up runs straight after the attempt); otherwise a fresh walk.
     * The remembered read is used at most once.
     *
     * @param wareHouse the warehouse block entity.
     * @param request   the completed request.
     * @return the stacks with their rack positions.
     */
    private List<Tuple<ItemStack, BlockPos>> matchingStacksForFollowup(@NotNull final TileEntityWareHouse wareHouse, @NotNull final IRequest<? extends IDeliverable> request)
    {
        final MatchedStacks matched = lastMatched;
        lastMatched = null;
        if (matched != null
              && matched.request().equals(request.getId())
              && wareHouse.getLevel() != null
              && wareHouse.getLevel().getGameTime() == matched.gameTime()
              && wareHouse.getBuilding() instanceof final BuildingWareHouse building
              && building.getRackIndex() == matched.index()
              && matched.index().changeCount() == matched.changes()
              && WarehouseRackIndex.loadEpoch() == matched.loadEpoch())
        {
            return matched.stacks();
        }
        return wareHouse.getMatchingItemStacksInWarehouse(itemStack -> request.getRequest().matches(itemStack));
    }

    @Override
    public void onAssignedRequestBeingCancelled(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends IDeliverable> request)
    {

    }

    @Override
    public void onAssignedRequestCancelled(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends IDeliverable> request)
    {

    }

    @Override
    public void onRequestedRequestComplete(@NotNull final IRequestManager manager, @NotNull final IRequest<?> request)
    {
        if (!request.hasParent())
        {
            // Round 13 (L7): a remainder (the warehouse's only request without a parent) is in the racks now: done with it.
            openRemainders.values().remove(request.getId());
            manager.updateRequestState(request.getId(), RequestState.RECEIVED);
        }
    }

    @Override
    public void onRequestedRequestCancelled(@NotNull final IRequestManager manager, @NotNull final IRequest<?> request)
    {
        openRemainders.values().remove(request.getId());
    }

    @NotNull
    @Override
    public MutableComponent getRequesterDisplayName(@NotNull final IRequestManager manager, @NotNull final IRequest<?> request)
    {
        return Component.translatableEscape(TranslationConstants.COM_MINECOLONIES_BUILDING_WAREHOUSE_NAME);
    }

    @Override
    public int getPriority()
    {
        return CONST_WAREHOUSE_RESOLVER_PRIORITY;
    }

    @Override
    public boolean isValid()
    {
        // Always valid
        return true;
    }

    @Override
    public int getSuitabilityMetric(final @NotNull IRequestManager manager, final @NotNull IRequest<? extends IDeliverable> request)
    {
        final IWareHouse wareHouse = manager.getColony().getServerBuildingManager().getBuilding(getLocation().getInDimensionLocation(), IWareHouse.class);
        final int distance = (int) BlockPosUtil.getDistance(request.getRequester().getLocation().getInDimensionLocation(), getLocation().getInDimensionLocation());
        if (wareHouse == null)
        {
            return distance;
        }
        return Math.max(distance/10, 1) + wareHouse.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE).getMutableRequestList().size();
    }
}
