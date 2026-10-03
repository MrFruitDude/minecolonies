package com.minecolonies.core.network.messages.client.colony;

import com.ldtteam.common.network.AbstractClientPlayMessage;
import com.ldtteam.common.network.PlayMessageType;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.ColonyView;
import com.minecolonies.core.network.messages.MessageBuffers;
import com.minecolonies.core.network.messages.server.colony.ColonyViewResyncRequestMessage;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * CA-1: the colony's request-system view, as a full payload or a delta keyed by token
 * ({@link com.minecolonies.core.colony.requestsystem.management.manager.RequestSystemViewSync}). It used to ride inside
 * every {@link ColonyViewMessage} the request manager was dirty for, whole.
 */
public class ColonyViewRequestSystemMessage extends AbstractClientPlayMessage
{
    public static final PlayMessageType<?> TYPE = PlayMessageType.forClient(Constants.MOD_ID, "colony_view_request_system", ColonyViewRequestSystemMessage::new, true, false);

    private final int                     colonyId;
    private final ResourceKey<Level>      dimension;
    private final RegistryFriendlyByteBuf payload;

    /**
     * @param payload a full or delta payload written by RequestSystemViewSync, shared by every receiving player.
     */
    public ColonyViewRequestSystemMessage(final int colonyId, final ResourceKey<Level> dimension, final RegistryFriendlyByteBuf payload)
    {
        super(TYPE);
        this.colonyId = colonyId;
        this.dimension = dimension;
        this.payload = payload;
    }

    protected ColonyViewRequestSystemMessage(@NotNull final RegistryFriendlyByteBuf buf, final PlayMessageType<?> type)
    {
        super(buf, type);
        colonyId = buf.readInt();
        dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(buf.readUtf(32767)));
        payload = new RegistryFriendlyByteBuf(new FriendlyByteBuf(Unpooled.wrappedBuffer(buf.readByteArray())), buf.registryAccess());
    }

    @Override
    protected void toBytes(@NotNull final RegistryFriendlyByteBuf buf)
    {
        buf.writeInt(colonyId);
        buf.writeUtf(dimension.identifier().toString());
        MessageBuffers.writeWrittenBytes(buf, payload);
    }

    @Override
    protected void onExecute(final IPayloadContext ctxIn, @Nullable final Player player)
    {
        final IColonyView view = IColonyManager.getInstance().getColonyView(colonyId, dimension);
        if (view instanceof ColonyView colonyView && !colonyView.handleRequestSystemMessage(payload))
        {
            new ColonyViewResyncRequestMessage(dimension, colonyId, ColonyViewResyncRequestMessage.REQUEST_SYSTEM, -1).sendToServer();
        }
    }
}
