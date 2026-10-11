package com.minecolonies.core.colony.storage;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.requestsystem.RsFlags;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The switches and the reach rules of the global storage network: whether it is on for a colony, and which warehouses a
 * courier serves. With the network on, a courier belongs to the colony: its warehouse is its home and the anchor of its
 * service range, not the only place it works.
 */
public final class StorageNetwork
{
    private StorageNetwork()
    {
    }

    /**
     * @param manager a request manager.
     * @return true when the network is on and the manager belongs to a server colony.
     */
    public static boolean on(@Nullable final IRequestManager manager)
    {
        return manager != null && on(manager.getColony());
    }

    /**
     * @param colony a colony.
     * @return true when the network is on and the colony is a server colony.
     */
    public static boolean on(@Nullable final IColony colony)
    {
        return colony instanceof Colony && RsFlags.storageNetwork() && colony.getWorld() != null && !colony.getWorld().isClientSide();
    }

    /**
     * @param home      the warehouse a courier is assigned to.
     * @param warehouse another warehouse.
     * @return true when a courier of {@code home} serves {@code warehouse} (the service range; 0 = the whole colony).
     */
    public static boolean inRange(@NotNull final BlockPos home, @NotNull final BlockPos warehouse)
    {
        final int range = RsFlags.storageServiceRange();
        return range <= 0 || home.equals(warehouse) || BlockPosUtil.getDistance(home, warehouse) <= range;
    }

    /**
     * @param colony    the colony.
     * @param warehouse a warehouse.
     * @return true when at least one courier of the colony serves it.
     */
    public static boolean served(@NotNull final Colony colony, @NotNull final IWareHouse warehouse)
    {
        for (final IWareHouse home : colony.getServerBuildingManager().getWareHouses())
        {
            if (!home.getModule(BuildingModules.WAREHOUSE_COURIERS).getAssignedCitizen().isEmpty() && inRange(home.getID(), warehouse.getID()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * @param colony the colony.
     * @return true when any warehouse has a courier.
     */
    public static boolean anyCourier(@NotNull final Colony colony)
    {
        for (final IWareHouse home : colony.getServerBuildingManager().getWareHouses())
        {
            if (!home.getModule(BuildingModules.WAREHOUSE_COURIERS).getAssignedCitizen().isEmpty())
            {
                return true;
            }
        }
        return false;
    }

    /**
     * @param colony the colony.
     * @param home   the warehouse a courier is assigned to.
     * @return the warehouses that courier serves, its home first.
     */
    @NotNull
    public static List<IWareHouse> servedBy(@NotNull final Colony colony, @NotNull final IWareHouse home)
    {
        final List<IWareHouse> result = new ArrayList<>();
        result.add(home);
        for (final IWareHouse other : colony.getServerBuildingManager().getWareHouses())
        {
            if (other != home && inRange(home.getID(), other.getID()))
            {
                result.add(other);
            }
        }
        return result;
    }
}
