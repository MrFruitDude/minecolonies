package com.minecolonies.core.colony.permissions;

import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.api.util.BuildingContainers;
import com.minecolonies.api.util.EntityUtils;
import com.minecolonies.api.util.MessageUtils;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.AbstractBuildingContainer;
import com.minecolonies.core.colony.buildings.CopperChests;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

import static com.minecolonies.api.util.constant.TranslationConstants.PERMISSION_DENIED;

/**
 * V-7 (vanilla tie-in wave 2): the copper chests of one colony's buildings. A vanilla copper chest placed inside a building's corners (or joined onto one of its
 * copper chests) becomes one of its containers, breaking it takes it out, and a player without permission to place blocks in the colony cannot join a chest
 * onto one. The rules themselves are in {@link CopperChests}. Registered and unregistered with the colony's {@link ColonyPermissionEventHandler}; every handler returns at once for anything but a copper
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
     * stock (request system, reservation ledger, hut inventory), and copper golems are kept out of it. Runs last and only for a placement that stands. A double
     * chest is held whole or not at all: a half joined onto a container chest goes to that chest's building, a half joined onto a free copper chest takes both
     * halves to the building that fits them (one half inside its corners, both in its claim), and anything else leaves both halves plain vanilla chests. With
     * nested buildings the smallest one takes it; the warehouse keeps racks only.
     *
     * @param event the placement.
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public void registerCopperChest(final BlockEvent.EntityPlaceEvent event)
    {
        if (event.isCanceled() || !BuildingContainers.isCopperChest(event.getPlacedBlock())
              || !(event.getLevel() instanceof final ServerLevel level) || !level.dimension().equals(colony.getDimension()))
        {
            return;
        }
        final BlockPos pos = event.getPos();
        if (!WorldUtil.isBlockLoaded(level, pos))
        {
            return;
        }
        final BlockPos other = CopperChests.otherHalf(pos, level.getBlockState(pos));
        AbstractBuildingContainer building = other == null ? null : CopperChests.holder(colony, other, null);
        if (building != null && CopperChests.fit(building, level, pos) != CopperChests.Fit.YES)
        {
            // a building that lists the other half but cannot hold the pair (a stale listing): the pair goes to the building that fits it, if any
            building = null;
        }
        if (building == null)
        {
            building = CopperChests.bestFit(colony, level, pos);
        }
        if (building == null && other != null)
        {
            building = CopperChests.bestFit(colony, level, other);
        }
        if (building == null || CopperChests.fit(building, level, pos) != CopperChests.Fit.YES)
        {
            // not one of this colony's: a stale listing of the position goes, and so does a half the new one joined onto (no half-held double chest)
            CopperChests.forget(colony, pos, null);
            if (other != null)
            {
                CopperChests.forget(colony, other, null);
            }
            return;
        }
        boolean changed = building.addCopperChest(pos, level);
        if (other != null)
        {
            changed |= building.addCopperChest(other, level);
            CopperChests.forget(colony, other, building);
        }
        CopperChests.forget(colony, pos, building);
        if (changed)
        {
            building.markDirty();
        }
    }

    /**
     * V-7: a copper chest cannot be joined onto one of this colony's building containers by a player without permission to place blocks in the colony (a
     * double chest opens and feeds golems as one inventory), also from outside the colony's chunks, nor by anyone from outside the holding building's claim
     * (the colony's stock never reaches across a border).
     *
     * @param event the placement.
     */
    @SubscribeEvent
    public void refuseForeignCopperChestJoin(final BlockEvent.EntityPlaceEvent event)
    {
        if (event.isCanceled() || !BuildingContainers.isCopperChest(event.getPlacedBlock()) || !(event.getLevel() instanceof final ServerLevel level)
              || !level.dimension().equals(colony.getDimension()))
        {
            return;
        }
        final BlockPos other = CopperChests.otherHalf(event.getPos(), event.getPlacedBlock());
        final AbstractBuildingContainer holder = other == null || !WorldUtil.isBlockLoaded(level, other) ? null : CopperChests.holder(colony, other, null);
        if (holder == null || !holder.acceptsCopperChests())
        {
            // no building holds the other half (a warehouse's listing is stale: it keeps racks only)
            return;
        }
        final Player placer = event.getEntity() instanceof final Player player ? player : null;
        if (!CopperChests.claims(holder, level, event.getPos())
              || (placer != null && !colony.getPermissions().hasPermission(EntityUtils.getPlayerOfFakePlayer(placer, level), Action.PLACE_BLOCKS)))
        {
            event.setCanceled(true);
            if (placer != null && !(placer instanceof net.neoforged.neoforge.common.util.FakePlayer))
            {
                MessageUtils.format(PERMISSION_DENIED).sendTo(placer);
            }
        }
    }

    /**
     * V-7: a player broke a copper chest that is one of this colony's building containers: it leaves every building that lists it (and the golem lock is
     * cleared before the chest drops). Runs last, only for a break that stands. Removal without a player (explosions, commands) is caught by the buildings'
     * next check.
     *
     * @param event the break.
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public void forgetCopperChest(final BreakBlockEvent event)
    {
        if (event.isCanceled() || !BuildingContainers.isCopperChest(event.getState())
              || !(event.getLevel() instanceof final ServerLevel level) || !level.dimension().equals(colony.getDimension()))
        {
            return;
        }
        CopperChests.forget(colony, event.getPos(), null);
    }
}
