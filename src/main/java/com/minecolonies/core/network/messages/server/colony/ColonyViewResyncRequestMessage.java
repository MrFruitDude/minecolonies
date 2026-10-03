package com.minecolonies.core.network.messages.server.colony;

import com.ldtteam.common.network.PlayMessageType;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.managers.ColonyPackageManager;
import com.minecolonies.core.network.messages.server.AbstractColonyServerMessage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * CA-1/CA-3: the client could not apply a view delta (it missed one, or a part failed to decode) and asks for a full
 * resync of that part. Only a player the colony already sends views to gets one.
 */
public class ColonyViewResyncRequestMessage extends AbstractColonyServerMessage
{
    public static final PlayMessageType<?> TYPE = PlayMessageType.forServer(Constants.MOD_ID, "colony_view_resync_request", ColonyViewResyncRequestMessage::new);

    /**
     * What to resync.
     */
    public static final byte REQUEST_SYSTEM = 0;
    public static final byte CITIZEN        = 1;

    private final byte kind;
    private final int  citizenId;

    public ColonyViewResyncRequestMessage(final ResourceKey<Level> dimension, final int colonyId, final byte kind, final int citizenId)
    {
        super(TYPE, dimension, colonyId);
        this.kind = kind;
        this.citizenId = citizenId;
    }

    protected ColonyViewResyncRequestMessage(final RegistryFriendlyByteBuf buf, final PlayMessageType<?> type)
    {
        super(buf, type);
        this.kind = buf.readByte();
        this.citizenId = buf.readInt();
    }

    @Override
    protected void toBytes(@NotNull final RegistryFriendlyByteBuf buf)
    {
        super.toBytes(buf);
        buf.writeByte(kind);
        buf.writeInt(citizenId);
    }

    @Nullable
    @Override
    protected Action permissionNeeded()
    {
        // Views go to every close subscriber regardless of rank; the package manager only answers those.
        return null;
    }

    @Override
    protected void onExecute(final IPayloadContext ctxIn, final ServerPlayer player, final IColony colony)
    {
        if (colony.getPackageManager() instanceof ColonyPackageManager manager)
        {
            if (kind == REQUEST_SYSTEM)
            {
                manager.requestRequestSystemResync(player);
            }
            else if (kind == CITIZEN)
            {
                manager.requestCitizenResync(player, citizenId);
            }
        }
    }
}
