package com.minecolonies.core.network.messages.server.colony;

import com.ldtteam.common.network.PlayMessageType;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.connections.ConnectionEvent;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.network.messages.server.AbstractColonyServerMessage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Message for triggering a connection event message at a given colony from another colony.
 */
public class TriggerConnectionEventMessage extends AbstractColonyServerMessage
{
    public static final PlayMessageType<?> TYPE = PlayMessageType.forServer(Constants.MOD_ID, "trigger_connection", TriggerConnectionEventMessage::new);

    /**
     * Set the connection event data.
     */
    private ConnectionEvent connectionEventData;

    /**
     * Target colony id.
     */
    private int targetColonyId;

    public TriggerConnectionEventMessage(final IColony colony, final ConnectionEvent coreConnectionEventData, final int targetColonyId)
    {
        super(TYPE, colony);
        this.connectionEventData = coreConnectionEventData;
        this.targetColonyId = targetColonyId;
    }

    protected TriggerConnectionEventMessage(final RegistryFriendlyByteBuf buf, final PlayMessageType<?> type)
    {
        super(buf, type);
        this.connectionEventData = ConnectionEvent.deserializeByteBuf(buf);
        this.targetColonyId = buf.readInt();
    }

    @Override
    protected void onExecute(final IPayloadContext ctxIn, final ServerPlayer player, final IColony colony)
    {
        if (player == null)
        {
            return;
        }

        // The event is the sending colony's own: its origin is the colony the message is sent for, never another one (the id came from the client).
        if (connectionEventData.id() != colony.getID())
        {
            return;
        }

        final IColony targetColony = IColonyManager.getInstance().getColonyByDimension(targetColonyId, colony.getDimension());
        if (targetColony == null)
        {
            Log.getLogger().error("Tried to trigger connection event at null colony: {}", targetColonyId);
            return;
        }

        // A status between two colonies is a decision of both: the sender needs the right to manage huts in each of them.
        if (targetColony != colony
              && colony.getPermissions().hasPermission(player, Action.MANAGE_HUTS)
              && targetColony.getPermissions().hasPermission(player, Action.MANAGE_HUTS))
        {
            targetColony.getConnectionManager().triggerConnectionEvent(connectionEventData);
        }
    }

    @Override
    protected void toBytes(final RegistryFriendlyByteBuf buf)
    {
        super.toBytes(buf);
        connectionEventData.serializeByteBuf(buf);
        buf.writeInt(targetColonyId);
    }
}
