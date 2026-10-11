package com.minecolonies.core.colony.requestsystem.wait;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.ModBuildings;
import com.minecolonies.api.colony.buildings.modules.ICraftingBuildingModule;
import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.WaitReason;
import com.minecolonies.api.colony.requestsystem.requestable.IConcreteDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Predicate;

/**
 * RS2: answers "why is this request not moving", and "can anything in this colony ever make this item".
 * <p>
 * Read-only walks over the colony's buildings; called when a request starts to wait and when it is retried, not per tick.
 */
public final class WaitDiagnosis
{
    private WaitDiagnosis()
    {
    }

    /**
     * Whether any recipe in the colony produces an item matching the deliverable.
     *
     * @param colony the colony.
     * @param wanted the deliverable.
     * @return the staffed status: 0 = no recipe at all, 1 = a recipe exists but no worker is assigned to it, 2 = a staffed crafter has it.
     */
    public static int recipeSupport(@NotNull final Colony colony, @NotNull final IDeliverable wanted)
    {
        final Predicate<ItemStack> matches = wanted::matches;
        int best = 0;
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            if (building.getBuildingLevel() <= 0)
            {
                continue;
            }
            for (final ICraftingBuildingModule module : building.getModulesByType(ICraftingBuildingModule.class))
            {
                if (module.getFirstRecipe(matches) == null)
                {
                    continue;
                }
                if (hasWorker(building, module))
                {
                    return 2;
                }
                best = 1;
            }
        }
        return best;
    }

    private static boolean hasWorker(final IBuilding building, final ICraftingBuildingModule module)
    {
        for (final WorkerBuildingModule worker : building.getModulesByType(WorkerBuildingModule.class))
        {
            if (worker.hasAssignedCitizen() && (module.getCraftingJob() == null || worker.getJobEntry() == module.getCraftingJob().getJobRegistryEntry()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Buildings that bring raw materials into the colony. If one exists, an item with no recipe may still turn up.
     */
    private static boolean isGatherer(final IBuilding building)
    {
        final BuildingEntry type = building.getBuildingType();
        return building.getBuildingLevel() > 0 && (type == ModBuildings.lumberjack.get() || type == ModBuildings.miner.get() || type == ModBuildings.farmer.get()
                                                     || type == ModBuildings.fisherman.get() || type == ModBuildings.shepherd.get() || type == ModBuildings.cowboy.get()
                                                     || type == ModBuildings.swineHerder.get() || type == ModBuildings.chickenHerder.get() || type == ModBuildings.rabbitHutch.get()
                                                     || type == ModBuildings.beekeeper.get() || type == ModBuildings.florist.get() || type == ModBuildings.plantation.get()
                                                     || type == ModBuildings.simpleQuarry.get() || type == ModBuildings.mediumQuarry.get() || type == ModBuildings.netherWorker.get());
    }

    /**
     * @param colony the colony.
     * @return true when the colony has a building that gathers raw materials.
     */
    public static boolean hasGatherer(@NotNull final Colony colony)
    {
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            if (isGatherer(building))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Physical stock of the colony's warehouses matching the deliverable, ignoring reservations, counted up to what the
     * request wants.
     */
    public static int physicalStock(@NotNull final Colony colony, @NotNull final IDeliverable wanted)
    {
        int total = 0;
        for (final IWareHouse wareHouse : colony.getServerBuildingManager().getWareHouses())
        {
            if (wareHouse instanceof BuildingWareHouse building)
            {
                total += building.hasEnoughElseCount(wanted::matches, Math.max(1, wanted.getCount()));
            }
        }
        return total;
    }

    /**
     * Stock of the colony's warehouses matching the deliverable that no reservation holds, counted up to what the request wants.
     */
    public static int availableStock(@NotNull final Colony colony, @NotNull final IDeliverable wanted)
    {
        int total = 0;
        for (final IWareHouse wareHouse : colony.getServerBuildingManager().getWareHouses())
        {
            if (wareHouse instanceof BuildingWareHouse building)
            {
                total += building.availableCount(wanted::matches, Math.max(1, wanted.getCount()));
            }
        }
        return total;
    }

    /**
     * RS2: whether nothing in the colony can ever make the requested item: not in stock anywhere, no recipe for it and
     * no building that gathers raw materials. Only then does the player have to bring it. Requests this cannot judge
     * (not a plain deliverable) are never reported as unproducible.
     *
     * @param manager the request manager.
     * @param request the request.
     * @return true when the player has to supply it.
     */
    public static boolean neverProducible(@NotNull final IRequestManager manager, @NotNull final IRequest<?> request)
    {
        if (!(request.getRequest() instanceof final IDeliverable wanted) || !(manager.getColony() instanceof final Colony colony))
        {
            return false;
        }
        if (colony.getWorld().isClientSide())
        {
            return false;
        }
        if (physicalStock(colony, wanted) > 0)
        {
            return false;
        }
        if (recipeSupport(colony, wanted) > 0)
        {
            return false;
        }
        return !hasGatherer(colony);
    }

    /**
     * Why a deliverable request nobody could serve is waiting.
     *
     * @param manager the request manager.
     * @param request the request.
     * @return the reason.
     */
    @NotNull
    public static WaitReason forStarvedRequest(@NotNull final IRequestManager manager, @NotNull final IRequest<?> request)
    {
        if (!(request.getRequest() instanceof final IDeliverable wanted) || !(manager.getColony() instanceof final Colony colony) || colony.getWorld().isClientSide())
        {
            return WaitReason.NO_SOURCE;
        }
        // Enough is there, but promised to others.
        final int wantedCount = Math.max(1, wanted.getMinimumCount());
        if (physicalStock(colony, wanted) >= wantedCount && availableStock(colony, wanted) < wantedCount)
        {
            return WaitReason.RESERVED_ELSEWHERE;
        }
        return switch (recipeSupport(colony, wanted))
        {
            case 1 -> WaitReason.NO_CRAFTER;
            case 2 -> WaitReason.AWAITING_CRAFT;
            default -> WaitReason.NO_SOURCE;
        };
    }

    /**
     * Why a delivery or pickup in the queue is not being carried.
     *
     * @param colony  the colony.
     * @param request the delivery or pickup request.
     * @return the reason.
     */
    @NotNull
    public static WaitReason forCourierTask(@NotNull final Colony colony, @NotNull final IRequest<?> request)
    {
        final List<IWareHouse> wareHouses = colony.getServerBuildingManager().getWareHouses();
        boolean anyCourier = false;
        for (final IWareHouse wareHouse : wareHouses)
        {
            if (!wareHouse.getModule(BuildingModules.WAREHOUSE_COURIERS).getAssignedCitizen().isEmpty())
            {
                anyCourier = true;
                break;
            }
        }
        if (!anyCourier)
        {
            return WaitReason.NO_COURIER;
        }
        if (request.getRequest() instanceof final Delivery delivery
              && !com.minecolonies.api.util.WorldUtil.isBlockLoaded(colony.getWorld(), delivery.getTarget().getInDimensionLocation()))
        {
            return WaitReason.UNLOADED;
        }
        return WaitReason.COURIER_BUSY;
    }
}
