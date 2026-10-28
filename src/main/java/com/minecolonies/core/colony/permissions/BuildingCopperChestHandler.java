package com.minecolonies.core.colony.permissions;

import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.api.util.EntityUtils;
import com.minecolonies.api.util.MessageUtils;
import com.minecolonies.core.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

import static com.minecolonies.api.util.constant.TranslationConstants.PERMISSION_DENIED;

/**
 * V-7 (vanilla tie-in wave 2): the copper chests of one colony's buildings. A vanilla copper chest placed inside a building's corners (or joined onto one of its
 * copper chests) becomes one of its containers, breaking it takes it out, and a player without permission to place blocks in the colony cannot join a chest
 * onto one. Registered and unregistered with the colony's {@link ColonyPermissionEventHandler}; every handler returns at once for anything but a copper
 * chest in this colony's dimension.
 */
public class BuildingCopperChestHandler
{
    /**
     * The colony whose buildings this handler serves.
     */
    private final Colony colony;

    /**
     * @param colony the colony.
     */
    public BuildingCopperChestHandler(final Colony colony)
    {
        this.colony = colony;
    }

    /**
     * V-7: a vanilla copper chest placed inside the corners of one of this colony's built buildings becomes one of its containers: its items are the building's
     * stock (request system, reservation ledger, hut inventory), and copper golems are kept out of it. Runs last and only for a placement that stands; a half
     * joined onto a container chest goes to that chest's building, otherwise with nested buildings the smallest one that contains the chest takes it. The
     * warehouse keeps racks only.
     *
     * @param event the placement.
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public void registerCopperChest(final BlockEvent.EntityPlaceEvent event)
    {
        if (event.isCanceled() || !com.minecolonies.api.util.BuildingContainers.isCopperChest(event.getPlacedBlock())
              || !(event.getLevel() instanceof final ServerLevel level) || !level.dimension().equals(colony.getDimension()))
        {
            return;
        }
        final BlockPos pos = event.getPos();
        // the other half of a double chest whose first half is a container belongs to the same building (the whole chest is one inventory)
        com.minecolonies.core.colony.buildings.AbstractBuildingContainer building = joinedContainerOwner(level, pos, event.getPlacedBlock());
        if (building == null)
        {
            building = copperChestOwner(level, pos);
        }
        if (building != null && building.addCopperChest(pos, level))
        {
            building.markDirty();
        }
    }

    /**
     * V-7: a player without permission to place blocks in this colony cannot join a copper chest onto one of its building containers (a double chest opens
     * and feeds golems as one inventory), also from outside the colony's chunks.
     *
     * @param event the placement.
     */
    @SubscribeEvent
    public void refuseForeignCopperChestJoin(final BlockEvent.EntityPlaceEvent event)
    {
        if (!event.isCanceled() && event.getEntity() instanceof final Player placer && com.minecolonies.api.util.BuildingContainers.isCopperChest(event.getPlacedBlock())
              && event.getLevel() instanceof final ServerLevel level && level.dimension().equals(colony.getDimension())
              && joinedContainerOwner(level, event.getPos(), event.getPlacedBlock()) != null
              && !colony.getPermissions().hasPermission(EntityUtils.getPlayerOfFakePlayer(placer, level), Action.PLACE_BLOCKS))
        {
            event.setCanceled(true);
            if (!(placer instanceof net.neoforged.neoforge.common.util.FakePlayer))
            {
                MessageUtils.format(PERMISSION_DENIED).sendTo(placer);
            }
        }
    }

    /**
     * The building of this colony that holds the other half of a double copper chest as a container, or null (a single chest, or the other half is not one).
     */
    @org.jetbrains.annotations.Nullable
    private com.minecolonies.core.colony.buildings.AbstractBuildingContainer joinedContainerOwner(final ServerLevel level, final BlockPos pos, final BlockState state)
    {
        if (!state.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)
              || state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) == net.minecraft.world.level.block.state.properties.ChestType.SINGLE)
        {
            return null;
        }
        final BlockPos other = pos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
        if (!com.minecolonies.api.util.WorldUtil.isBlockLoaded(level, other))
        {
            return null;
        }
        for (final BlockPos buildingPos : com.minecolonies.api.util.ColonyUtils.getAllClaimingBuildings(level.getChunkAt(other))
                                            .getOrDefault(colony.getID(), java.util.Collections.emptySet()))
        {
            if (colony.getServerBuildingManager().getBuilding(buildingPos) instanceof final com.minecolonies.core.colony.buildings.AbstractBuildingContainer building
                  && building.getContainers().contains(other))
            {
                return building;
            }
        }
        return null;
    }

    /**
     * V-7: a player broke a copper chest that is one of this colony's building containers: it leaves the building (and the golem lock is cleared before the
     * chest drops). Runs last, only for a break that stands.
     *
     * @param event the break.
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public void forgetCopperChest(final BreakBlockEvent event)
    {
        if (event.isCanceled() || !com.minecolonies.api.util.BuildingContainers.isCopperChest(event.getState())
              || !(event.getLevel() instanceof final ServerLevel level) || !level.dimension().equals(colony.getDimension()))
        {
            return;
        }
        final BlockPos pos = event.getPos();
        if (!com.minecolonies.api.util.WorldUtil.isBlockLoaded(level, pos))
        {
            return;
        }
        // A building's containers are inside its corners, so inside the chunks it claims.
        for (final BlockPos buildingPos : com.minecolonies.api.util.ColonyUtils.getAllClaimingBuildings(level.getChunkAt(pos))
                                            .getOrDefault(colony.getID(), java.util.Collections.emptySet()))
        {
            final IBuilding building = colony.getServerBuildingManager().getBuilding(buildingPos);
            if (building != null && building.getContainers().contains(pos) && !building.getPosition().equals(pos))
            {
                building.removeContainerPosition(pos);
                building.markDirty();
            }
        }
    }

    /**
     * The built building of this colony whose corners contain a position, read from the claim data of its chunk (the smallest one when buildings nest).
     */
    @org.jetbrains.annotations.Nullable
    private com.minecolonies.core.colony.buildings.AbstractBuildingContainer copperChestOwner(final ServerLevel level, final BlockPos pos)
    {
        if (!com.minecolonies.api.util.WorldUtil.isBlockLoaded(level, pos))
        {
            return null;
        }
        final net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkAt(pos);
        if (com.minecolonies.api.util.ColonyUtils.getOwningColony(chunk) != colony.getID())
        {
            return null;
        }
        com.minecolonies.core.colony.buildings.AbstractBuildingContainer best = null;
        long bestVolume = Long.MAX_VALUE;
        for (final BlockPos buildingPos : com.minecolonies.api.util.ColonyUtils.getAllClaimingBuildings(chunk).getOrDefault(colony.getID(), java.util.Collections.emptySet()))
        {
            if (colony.getServerBuildingManager().getBuilding(buildingPos) instanceof final com.minecolonies.core.colony.buildings.AbstractBuildingContainer building
                  && building.getBuildingLevel() >= 1 && building.acceptsCopperChests() && building.isInBuilding(pos))
            {
                final com.ldtteam.structurize.api.util.Tuple<BlockPos, BlockPos> corners = building.getCorners();
                final long volume = (long) (Math.abs(corners.getB().getX() - corners.getA().getX()) + 1)
                                      * (Math.abs(corners.getB().getY() - corners.getA().getY()) + 1)
                                      * (Math.abs(corners.getB().getZ() - corners.getA().getZ()) + 1);
                if (volume < bestVolume)
                {
                    best = building;
                    bestVolume = volume;
                }
            }
        }
        return best;
    }
}
