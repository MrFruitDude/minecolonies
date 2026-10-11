package com.minecolonies.api.eventbus.events.colony;

import com.minecolonies.api.eventbus.events.AbstractModEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Posted on the mod event bus when a player is about to found a colony, after MineColonies' own checks passed (not
 * more than {@code colonies.maxPerPlayer} colonies owned, distance and claim rules) and BEFORE the colony is created.
 * Cancel it to refuse the founding: no colony is created and the player is shown the reason. A founding that is not
 * cancelled can still fail for MineColonies' usual reasons.
 * <p>
 * Also posted with {@link #isPreview()} set when the player only looks at the founding screen, so a refusal can be shown
 * before the player goes through the founding. A listener that charges or reserves something for a founding must do so
 * only when the event is not a preview; it can still cancel a preview.
 */
public final class ColonyFoundingEvent extends AbstractModEvent
{
    /**
     * The founding player.
     */
    private final Player player;

    /**
     * The level of the new colony.
     */
    private final ServerLevel level;

    /**
     * The town hall position of the new colony.
     */
    private final BlockPos position;

    /**
     * The number of colonies the player owns already.
     */
    private final int ownedColonies;

    /**
     * Whether this is only the check made when the founding screen opens, not a founding that goes ahead.
     */
    private final boolean preview;

    /**
     * Whether a listener refused the founding.
     */
    private boolean canceled = false;

    /**
     * Reason shown to the player when refused, or null for none.
     */
    @Nullable
    private Component cancelMessage = null;

    /**
     * Colony founding event.
     *
     * @param player        the founding player.
     * @param level         the level of the new colony.
     * @param position      the town hall position of the new colony.
     * @param ownedColonies the number of colonies the player owns already.
     */
    public ColonyFoundingEvent(@NotNull final Player player, @NotNull final ServerLevel level, @NotNull final BlockPos position, final int ownedColonies)
    {
        this(player, level, position, ownedColonies, false);
    }

    /**
     * Colony founding event.
     *
     * @param player        the founding player.
     * @param level         the level of the new colony.
     * @param position      the town hall position of the new colony.
     * @param ownedColonies the number of colonies the player owns already.
     * @param preview       true if this only checks whether the founding screen may open.
     */
    public ColonyFoundingEvent(
      @NotNull final Player player,
      @NotNull final ServerLevel level,
      @NotNull final BlockPos position,
      final int ownedColonies,
      final boolean preview)
    {
        this.player = player;
        this.level = level;
        this.position = position;
        this.ownedColonies = ownedColonies;
        this.preview = preview;
    }

    /**
     * @return true if this only checks whether the founding screen may open, false if a colony is about to be created.
     */
    public boolean isPreview()
    {
        return preview;
    }

    /**
     * @return the founding player.
     */
    @NotNull
    public Player getPlayer()
    {
        return player;
    }

    /**
     * @return the level of the new colony.
     */
    @NotNull
    public ServerLevel getLevel()
    {
        return level;
    }

    /**
     * @return the town hall position of the new colony.
     */
    @NotNull
    public BlockPos getPosition()
    {
        return position;
    }

    /**
     * @return the number of colonies the player owns already (all dimensions).
     */
    public int getOwnedColonies()
    {
        return ownedColonies;
    }

    /**
     * @return true if a listener refused the founding.
     */
    public boolean isCanceled()
    {
        return canceled;
    }

    /**
     * Refuses (or un-refuses) the founding.
     *
     * @param canceled true to refuse it.
     */
    public void setCanceled(final boolean canceled)
    {
        this.canceled = canceled;
    }

    /**
     * Refuses the founding and tells the player why.
     *
     * @param message the reason shown to the player, usually a translatable component.
     */
    public void cancel(@NotNull final Component message)
    {
        this.canceled = true;
        this.cancelMessage = message;
    }

    /**
     * @return the reason shown to the player when refused, or null for none.
     */
    @Nullable
    public Component getCancelMessage()
    {
        return cancelMessage;
    }
}
