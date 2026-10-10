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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                        if (totalCount >= requestToCheck.getRequest().getCount())
                        {
                            return true;
                        }
                    }
                }
                return totalCount >= requestToCheck.getRequest().getMinimumCount();
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
        if (wareHouse == null)
        {
            return Lists.newArrayList();
        }
        final int totalRequested = request.getRequest().getCount();
        int totalAvailable = 0;
        final Map<ItemStorage, Integer> storages = new HashMap<>();

        final int toKeep = request.getRequest() instanceof INonExhaustiveDeliverable
            ? ((INonExhaustiveDeliverable) request.getRequest()).getLeftOver()
            : 0;

        final List<Tuple<ItemStack, BlockPos>> inv = wareHouse.getMatchingItemStacksInWarehouse(itemStack -> request.getRequest().matches(itemStack));
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

        if (totalAvailable >= totalRequested || totalAvailable >= request.getRequest().getMinimumCount())
        {
            // Resolved from stock: the request manager asks for the follow-up next, before anything else runs.
            lastMatched = matched(wareHouse, request, inv);
            return Lists.newArrayList();
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

        List<IRequest<?>> deliveries = Lists.newArrayList();
        int remainingCount = completedRequest.getRequest().getCount();

        final Map<ItemStorage, Integer> storages = new HashMap<>();

        final int keep = completedRequest.getRequest() instanceof INonExhaustiveDeliverable ? ((INonExhaustiveDeliverable) completedRequest.getRequest()).getLeftOver() : 0;

        final List<Tuple<ItemStack, BlockPos>> targetStacks = matchingStacksForFollowup(wareHouse, completedRequest);
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
            remainingCount -= count;
            if (remainingCount <= 0)
            {
                break;
            }
        }

        return deliveries.isEmpty() ? null : deliveries;
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
    }

    @Override
    public void onRequestedRequestCancelled(@NotNull final IRequestManager manager, @NotNull final IRequest<?> request)
    {
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
