package com.minecolonies.api.eventbus.events.colony.buildings;

import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Posted when a player asks for a building to be built or upgraded (hut window, build tool), after MineColonies' own
 * research and parent-level checks passed and BEFORE the work order is created. Cancel it to refuse the request: no
 * work order is created. A request that is not cancelled can still fail later for MineColonies' usual reasons
 * (an open work order already exists, no builder can do it...).
 */
public final class BuildingUpgradeRequestModEvent extends AbstractBuildingModEvent
{
    /**
     * The player who asked.
     */
    private final Player player;

    /**
     * The builder the player picked, {@link BlockPos#ZERO} for any.
     */
    private final BlockPos builder;

    /**
     * {@link WorkOrderType#BUILD} for a level 0 building, otherwise {@link WorkOrderType#UPGRADE}.
     */
    private final WorkOrderType type;

    /**
     * The level the building would reach.
     */
    private final int targetLevel;

    /**
     * Whether a listener refused the request.
     */
    private boolean canceled = false;

    /**
     * Message sent to the player when the request is refused, or null for none.
     */
    @Nullable
    private Component cancelMessage = null;

    /**
     * Building upgrade request event.
     *
     * @param building    the building.
     * @param player      the player who asked.
     * @param builder     the chosen builder, {@link BlockPos#ZERO} for any.
     * @param type        BUILD or UPGRADE.
     * @param targetLevel the level the building would reach.
     */
    public BuildingUpgradeRequestModEvent(
      final IBuilding building,
      final Player player,
      final BlockPos builder,
      final WorkOrderType type,
      final int targetLevel)
    {
        super(building);
        this.player = player;
        this.builder = builder;
        this.type = type;
        this.targetLevel = targetLevel;
    }

    /**
     * @return the player who asked.
     */
    public Player getPlayer()
    {
        return player;
    }

    /**
     * @return the chosen builder, {@link BlockPos#ZERO} for any.
     */
    public BlockPos getBuilder()
    {
        return builder;
    }

    /**
     * @return BUILD for a level 0 building, otherwise UPGRADE.
     */
    public WorkOrderType getType()
    {
        return type;
    }

    /**
     * @return the level the building would reach.
     */
    public int getTargetLevel()
    {
        return targetLevel;
    }

    /**
     * @return true if a listener refused the request.
     */
    public boolean isCanceled()
    {
        return canceled;
    }

    /**
     * Refuses (or un-refuses) the request.
     *
     * @param canceled true to refuse it.
     */
    public void setCanceled(final boolean canceled)
    {
        this.canceled = canceled;
    }

    /**
     * Refuses the request and tells the player why.
     *
     * @param message the message sent to the player.
     */
    public void cancel(@NotNull final Component message)
    {
        this.canceled = true;
        this.cancelMessage = message;
    }

    /**
     * @return the message sent to the player when refused, or null for none.
     */
    @Nullable
    public Component getCancelMessage()
    {
        return cancelMessage;
    }
}
