package com.minecolonies.core.network.messages.server.colony;

import com.ldtteam.common.network.AbstractServerPlayMessage;
import com.ldtteam.common.network.PlayMessageType;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.util.MessageUtils;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.OwnedColonyTarget;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import static com.minecolonies.api.util.constant.TranslationConstants.MESSAGE_INFO_COLONY_DESTROY_SUCCESS;
import static com.minecolonies.api.util.constant.TranslationConstants.MESSAGE_INFO_COLONY_NOT_FOUND;

/**
 * Message for deleting an owned colony. The colony is the one the message names; a message that names none deletes the
 * colony the player stands in if they own it, otherwise their selected colony.
 */
public class ColonyDeleteOwnMessage extends AbstractServerPlayMessage
{
    public static final PlayMessageType<?> TYPE = PlayMessageType.forServer(Constants.MOD_ID, "colony_delete_own", ColonyDeleteOwnMessage::new);

    /**
     * The dimension of the colony to delete, null to let the server pick.
     */
    @Nullable
    private final ResourceKey<Level> dimension;

    /**
     * The id of the colony to delete, negative for none.
     */
    private final int colonyId;

    @Override
    protected void toBytes(final RegistryFriendlyByteBuf buf)
    {
        buf.writeBoolean(dimension != null);
        if (dimension != null)
        {
            buf.writeUtf(dimension.identifier().toString());
            buf.writeInt(colonyId);
        }
    }

    protected ColonyDeleteOwnMessage(final RegistryFriendlyByteBuf buf, final PlayMessageType<?> type)
    {
        super(buf, type);
        if (buf.readBoolean())
        {
            dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(buf.readUtf(256)));
            colonyId = buf.readInt();
        }
        else
        {
            dimension = null;
            colonyId = -1;
        }
    }

    /**
     * Deletes the colony the server picks.
     */
    public ColonyDeleteOwnMessage()
    {
        this(null, -1);
    }

    /**
     * Deletes a given colony of the player.
     *
     * @param dimension the dimension of the colony, null to let the server pick.
     * @param colonyId  the colony id.
     */
    public ColonyDeleteOwnMessage(@Nullable final ResourceKey<Level> dimension, final int colonyId)
    {
        super(TYPE);
        this.dimension = dimension;
        this.colonyId = colonyId;
    }

    @Override
    protected void onExecute(final IPayloadContext ctxIn, final ServerPlayer player)
    {
        if (player == null)
        {
            return;
        }

        execute(player, dimension, colonyId);
    }

    /**
     * Deletes an owned colony.
     *
     * @param player    the player.
     * @param dimension the dimension of the colony, null to let the server pick.
     * @param colonyId  the colony id, negative to let the server pick.
     * @return the deleted colony's id, -1 if there was no colony of the player to delete.
     */
    public static int execute(final ServerPlayer player, @Nullable final ResourceKey<Level> dimension, final int colonyId)
    {
        final IColony colony = OwnedColonyTarget.resolve(player, dimension, colonyId);
        if (colony != null)
        {
            final int id = colony.getID();
            IColonyManager.getInstance().deleteColonyByDimension(id, false, colony.getDimension());
            MessageUtils.format(MESSAGE_INFO_COLONY_DESTROY_SUCCESS).sendTo(player);
            return id;
        }

        MessageUtils.format(MESSAGE_INFO_COLONY_NOT_FOUND).sendTo(player);
        return -1;
    }
}
