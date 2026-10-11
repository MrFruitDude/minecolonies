package com.minecolonies.core.colony.requestsystem.resolvers;

import com.google.common.collect.Lists;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.requestsystem.location.ILocation;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.IRequestable;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.api.util.constant.TranslationConstants;
import com.minecolonies.api.colony.requestsystem.request.WaitReason;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.requestsystem.RsFlags;
import com.minecolonies.core.colony.requestsystem.wait.RequestWaitTracker;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.WarehouseRequestQueueModule;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.requestsystem.resolvers.core.AbstractRequestResolver;
import com.minecolonies.core.colony.storage.ColonyStorage;
import com.minecolonies.core.colony.storage.DispatchCost;
import com.minecolonies.core.colony.storage.StorageNetwork;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Resolver which resolves requests to it given deliverymen. Resolving is based on how well a request fits a dman, evaluated through request scores.
 */
public abstract class DeliverymenRequestResolver<R extends IRequestable> extends AbstractRequestResolver<R>
{
    public DeliverymenRequestResolver(
      @NotNull final ILocation location,
      @NotNull final IToken<?> token)
    {
        super(location, token);
    }

    @Override
    public boolean canResolveRequest(@NotNull final IRequestManager manager, final IRequest<? extends R> requestToCheck)
    {
        if (manager.getColony().getWorld().isClientSide())
        {
            return false;
        }

        if (StorageNetwork.on(manager))
        {
            return networkCanResolve(manager, requestToCheck);
        }

        if (manager.getColony().getServerBuildingManager().getBuilding(requestToCheck.getRequester().getLocation().getInDimensionLocation()) instanceof IWareHouse
              && !requestToCheck.getRequester().getLocation().equals(getLocation()))
        {
            return false;
        }

        return hasCouriers(manager);
    }

    /**
     * Storage network: the couriers are the colony's, so a task may sit in any warehouse's queue as long as a courier reaches
     * that warehouse. A delivery out of a warehouse goes to the queue of the warehouse its items are in (that is where its
     * handling happens); everything else may go to any reachable warehouse, the cheapest wins.
     */
    private boolean networkCanResolve(@NotNull final IRequestManager manager, final IRequest<? extends R> requestToCheck)
    {
        final Colony colony = (Colony) manager.getColony();
        final IWareHouse wareHouse = colony.getServerBuildingManager().getBuilding(getLocation().getInDimensionLocation(), IWareHouse.class);
        if (wareHouse == null || !StorageNetwork.served(colony, wareHouse))
        {
            return false;
        }
        final IWareHouse requesterWarehouse = colony.getServerBuildingManager().getBuilding(requestToCheck.getRequester().getLocation().getInDimensionLocation(), IWareHouse.class);
        if (requestToCheck.getRequest() instanceof com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Pickup && requesterWarehouse == null)
        {
            // The colony storage is full: a courier would collect a load nobody can take. The producer keeps its output (and the managers are told once).
            final ColonyStorage storage = ColonyStorage.of(colony);
            if (storage.isFull())
            {
                storage.noticeFull(net.minecraft.world.item.ItemStack.EMPTY);
                return false;
            }
            return true;
        }
        if (requesterWarehouse == null)
        {
            return true;
        }
        if (requestToCheck.getRequest() instanceof final Delivery delivery)
        {
            // Made by a warehouse resolver: the queue of the warehouse whose rack holds the items.
            final IWareHouse source = sourceWarehouse(colony, delivery);
            return source == null ? requestToCheck.getRequester().getLocation().equals(getLocation()) : source == wareHouse;
        }
        return requestToCheck.getRequester().getLocation().equals(getLocation());
    }

    /**
     * @param colony   the colony.
     * @param delivery a delivery.
     * @return the warehouse that holds the delivery's start position, null when none does.
     */
    @Nullable
    private static IWareHouse sourceWarehouse(@NotNull final Colony colony, @NotNull final Delivery delivery)
    {
        final BlockPos owner = ColonyStorage.of(colony).owningWarehouse(delivery.getStart().getInDimensionLocation());
        return owner == null ? null : colony.getServerBuildingManager().getBuilding(owner, IWareHouse.class);
    }

    /**
     * Get the deliverymen we can resolve requests for
     *
     * @param manager request manager
     * @return list of citizens
     */
    public boolean hasCouriers(@NotNull final IRequestManager manager)
    {
        final Colony colony = (Colony) manager.getColony();
        final IWareHouse wareHouse = colony.getServerBuildingManager().getBuilding(getLocation().getInDimensionLocation(), IWareHouse.class);
        if (wareHouse == null)
        {
            return false;
        }

        if (StorageNetwork.on(manager))
        {
            // Any courier of the colony that reaches this warehouse.
            return StorageNetwork.served(colony, wareHouse);
        }

        return !wareHouse.getModule(BuildingModules.WAREHOUSE_COURIERS).getAssignedCitizen().isEmpty();
    }

    @Override
    public int getSuitabilityMetric(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends R> request)
    {
        final IWareHouse wareHouse = manager.getColony().getServerBuildingManager().getBuilding(getLocation().getInDimensionLocation(), IWareHouse.class);
        final int distance = (int) BlockPosUtil.getDistance(request.getRequester().getLocation().getInDimensionLocation(), getLocation().getInDimensionLocation());
        if (wareHouse == null)
        {
            return distance;
        }
        if (StorageNetwork.on(manager))
        {
            return networkMetric(manager, wareHouse, request);
        }
        return Math.max(distance/10, 1) + wareHouse.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE).getMutableRequestList().size();
    }

    /**
     * Storage network: the estimated completion time in seconds of a trip that involves this warehouse and the requester.
     */
    protected int networkMetric(@NotNull final IRequestManager manager, @NotNull final IWareHouse wareHouse, @NotNull final IRequest<? extends R> request)
    {
        final ColonyStorage storage = ColonyStorage.of(manager.getColony());
        return DispatchCost.fetchTicks(wareHouse, storage.taskLoad(wareHouse.getID()), request.getRequester().getLocation().getInDimensionLocation(), 64) / 20;
    }

    @Nullable
    @Override
    public List<IToken<?>> attemptResolveRequest(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends R> request)
    {
        if (manager.getColony().getWorld().isClientSide() || !hasCouriers(manager))
        {
            return null;
        }

        return Lists.newArrayList();
    }

    @Override
    public void resolveRequest(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends R> request) throws RuntimeException
    {
        final Colony colony = (Colony) manager.getColony();
        final IWareHouse wareHouse = colony.getServerBuildingManager().getBuilding(getLocation().getInDimensionLocation(), IWareHouse.class);
        if (wareHouse == null)
        {
            return;
        }

        if (!hasCouriers(manager))
        {
            return;
        }

        final WarehouseRequestQueueModule module = wareHouse.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE);
        module.addRequest(request.getId());

        if (RsFlags.smartRetry())
        {
            // RS2: promised and queued; a courier takes it from here.
            final RequestWaitTracker tracker = RequestWaitTracker.of(manager);
            if (tracker != null)
            {
                tracker.setReason(request, WaitReason.AWAITING_DELIVERY);
            }
        }
    }

    @Nullable
    @Override
    public List<IRequest<?>> getFollowupRequestForCompletion(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends R> completedRequest)
    {
        return null;
    }

    @Override
    public void onAssignedRequestBeingCancelled(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends R> request)
    {

    }

    @Override
    public void onAssignedRequestCancelled(
      @NotNull final IRequestManager manager, @NotNull final IRequest<? extends R> request)
    {
        if (!manager.getColony().getWorld().isClientSide())
        {
            final Colony colony = (Colony) manager.getColony();
            final ICitizenData freeDeliveryMan = colony.getCitizenManager().getCitizens()
                                                   .stream()
                                                   .filter(c -> c.getJob() instanceof JobDeliveryman && ((JobDeliveryman) c.getJob()).getTaskQueue().contains(request.getId()))
                                                   .findFirst()
                                                   .orElse(null);

            if (freeDeliveryMan != null)
            {
                final JobDeliveryman job = (JobDeliveryman) freeDeliveryMan.getJob();
                job.onTaskDeletion(request.getId());
            }

            final IWareHouse wareHouse = colony.getServerBuildingManager().getBuilding(getLocation().getInDimensionLocation(), IWareHouse.class);
            if (wareHouse == null)
            {
                return;
            }

            final WarehouseRequestQueueModule module = wareHouse.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE);
            module.getMutableRequestList().remove(request.getId());
        }
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
    public MutableComponent getRequesterDisplayName(
      @NotNull final IRequestManager manager, @NotNull final IRequest<?> request)
    {
        return Component.translatableEscape(TranslationConstants.COM_MINECOLONIES_COREMOD_JOB_DELIVERYMAN);
    }

    @Override
    public boolean isValid()
    {
        // Always valid
        return true;
    }
}
