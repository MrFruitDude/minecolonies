package com.minecolonies.core.network.messages.server.colony;

import com.ldtteam.common.network.AbstractServerPlayMessage;
import com.ldtteam.common.network.PlayMessageType;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.util.constant.Constants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * A player selects one of the colonies they own (see {@link IColonyManager#setSelectedColony}). The server checks that
 * the colony is the player's; a colony that is not is ignored.
 */
public class SelectColonyMessage extends AbstractServerPlayMessage
{
    public static final PlayMessageType<?> TYPE = PlayMessageType.forServer(Constants.MOD_ID, "select_colony", SelectColonyMessage::new);

    private final ResourceKey<Level> dimension;
    private final int                id;

    public SelectColonyMessage(final ResourceKey<Level> dimension, final int id)
    {
        super(TYPE);
        this.dimension = dimension;
        this.id = id;
    }

    protected SelectColonyMessage(final RegistryFriendlyByteBuf buf, final PlayMessageType<?> type)
    {
        super(buf, type);
        this.dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(buf.readUtf(256)));
        this.id = buf.readInt();
    }

    @Override
    protected void toBytes(final RegistryFriendlyByteBuf buf)
    {
        buf.writeUtf(dimension.identifier().toString());
        buf.writeInt(id);
    }

    @Override
    protected void onExecute(final IPayloadContext ctxIn, final ServerPlayer player)
    {
        if (player != null)
        {
            IColonyManager.getInstance().setSelectedColony(player.getUUID(), dimension, id);
        }
    }
}
