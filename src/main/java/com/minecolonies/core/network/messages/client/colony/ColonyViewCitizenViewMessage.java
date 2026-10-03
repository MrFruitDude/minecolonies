package com.minecolonies.core.network.messages.client.colony;

import com.ldtteam.common.network.AbstractClientPlayMessage;
import com.ldtteam.common.network.PlayMessageType;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.ColonyView;
import com.minecolonies.core.network.messages.BinaryDelta;
import com.minecolonies.core.network.messages.server.colony.ColonyViewResyncRequestMessage;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.minecolonies.core.network.messages.MessageBuffers;

import java.util.zip.CRC32;

/**
 * Add or Update a ColonyView on the client.
 * <p>
 * CA-3: the payload is the citizen's full view, or a binary patch against the full view the client got last
 * ({@link BinaryDelta}). A citizen re-sends its view whenever its inventory changes, and most of the view (skills,
 * happiness, interactions, job, family) did not change with it. The client rebuilds the exact full view bytes and reads
 * them as before, so the citizen view it ends up with is the one the full payload produces. A patch names the CRC of the
 * bytes it applies to; a client that holds other bytes asks for a resync.
 */
public class ColonyViewCitizenViewMessage extends AbstractClientPlayMessage
{
    public static final PlayMessageType<?> TYPE = PlayMessageType.forClient(Constants.MOD_ID, "colony_view_citizen_view", ColonyViewCitizenViewMessage::new);

    /**
     * Payload modes: a full view every close subscriber now holds; a patch against the view they hold; a full view sent
     * to one player out of band (it does not replace the bytes patches apply to, unless the client has none).
     */
    public static final byte MODE_FULL      = 0;
    public static final byte MODE_PATCH     = 1;
    public static final byte MODE_TRANSIENT = 2;

    private final int          colonyId;
    private final int          citizenId;
    private final RegistryFriendlyByteBuf citizenBuffer;

    /**
     * The dimension the citizen is in.
     */
    private final ResourceKey<Level> dimension;

    /**
     * A full, out-of-band update of a {@link com.minecolonies.core.colony.CitizenDataView} for one player.
     *
     * @param colony  Colony of the citizen
     * @param citizen Citizen data of the citizen to update view
     */
    public ColonyViewCitizenViewMessage(@NotNull final Colony colony, @NotNull final ICitizenData citizen)
    {
        this(colony, citizen.getId(), MODE_TRANSIENT, 0, viewPayload(colony, citizen));
    }

    private ColonyViewCitizenViewMessage(@NotNull final Colony colony, final int citizenId, final byte mode, final int baseCrc, final byte[] bytes)
    {
        super(TYPE);
        this.colonyId = colony.getID();
        this.citizenId = citizenId;
        this.citizenBuffer = new RegistryFriendlyByteBuf(new FriendlyByteBuf(Unpooled.buffer(bytes.length + 5)), colony.getWorld().registryAccess());
        this.dimension = colony.getDimension();
        citizenBuffer.writeByte(mode);
        if (mode == MODE_PATCH)
        {
            citizenBuffer.writeInt(baseCrc);
        }
        citizenBuffer.writeBytes(bytes);
    }

    /**
     * CA-3: the update for close subscribers that hold {@code base} (null: they hold nothing yet), bringing them to {@code now}.
     */
    public static ColonyViewCitizenViewMessage forSubscribers(@NotNull final Colony colony, final int citizenId, @Nullable final byte[] base, @NotNull final byte[] now)
    {
        if (base != null)
        {
            final byte[] patch = BinaryDelta.diff(base, now);
            if (patch.length + 4 < now.length)
            {
                return new ColonyViewCitizenViewMessage(colony, citizenId, MODE_PATCH, crc(base), patch);
            }
        }
        return new ColonyViewCitizenViewMessage(colony, citizenId, MODE_FULL, 0, now);
    }

    /**
     * @return the citizen's full view payload, as {@link ICitizenData#serializeViewNetworkData} writes it.
     */
    public static byte[] viewPayload(@NotNull final Colony colony, @NotNull final ICitizenData citizen)
    {
        final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(new FriendlyByteBuf(Unpooled.buffer()), colony.getWorld().registryAccess());
        citizen.serializeViewNetworkData(buf);
        final byte[] out = new byte[buf.writerIndex()];
        buf.getBytes(0, out);
        return out;
    }

    /**
     * CA-3 client side: the full view payload a message payload stands for, given the full view the client holds.
     *
     * @return the full view bytes, or null when the message is a patch the held bytes do not match.
     */
    @Nullable
    public static byte[] resolvePayload(@Nullable final byte[] held, @NotNull final byte[] payload)
    {
        if (payload.length == 0)
        {
            return null;
        }
        final byte mode = payload[0];
        if (mode == MODE_FULL || mode == MODE_TRANSIENT)
        {
            final byte[] full = new byte[payload.length - 1];
            System.arraycopy(payload, 1, full, 0, full.length);
            return full;
        }
        if (mode != MODE_PATCH || held == null || payload.length < 5)
        {
            return null;
        }
        final int baseCrc = ((payload[1] & 0xFF) << 24) | ((payload[2] & 0xFF) << 16) | ((payload[3] & 0xFF) << 8) | (payload[4] & 0xFF);
        if (crc(held) != baseCrc)
        {
            return null;
        }
        final byte[] patch = new byte[payload.length - 5];
        System.arraycopy(payload, 5, patch, 0, patch.length);
        try
        {
            return BinaryDelta.apply(held, patch);
        }
        catch (final IllegalArgumentException e)
        {
            return null;
        }
    }

    private static int crc(final byte[] bytes)
    {
        final CRC32 crc = new CRC32();
        crc.update(bytes);
        return (int) crc.getValue();
    }

    protected ColonyViewCitizenViewMessage(@NotNull final RegistryFriendlyByteBuf buf, final PlayMessageType<?> type)
    {
        super(buf, type);
        colonyId = buf.readInt();
        citizenId = buf.readInt();
        dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(buf.readUtf(32767)));
        this.citizenBuffer = new RegistryFriendlyByteBuf(new FriendlyByteBuf(Unpooled.wrappedBuffer(buf.readByteArray())), buf.registryAccess());
    }

    @Override
    protected void toBytes(@NotNull final RegistryFriendlyByteBuf buf)
    {
        citizenBuffer.resetReaderIndex();
        buf.writeInt(colonyId);
        buf.writeInt(citizenId);
        buf.writeUtf(dimension.identifier().toString());
        MessageBuffers.writeWrittenBytes(buf, citizenBuffer);
    }

    @Override
    protected void onExecute(final IPayloadContext ctxIn, final Player player)
    {
        final IColonyView view = IColonyManager.getInstance().getColonyView(colonyId, dimension);
        if (!(view instanceof ColonyView colonyView))
        {
            return;
        }
        final byte[] payload = new byte[citizenBuffer.writerIndex()];
        citizenBuffer.getBytes(0, payload);
        final byte[] held = colonyView.getCitizenViewPayload(citizenId);
        final byte[] full = resolvePayload(held, payload);
        if (full == null)
        {
            new ColonyViewResyncRequestMessage(dimension, colonyId, ColonyViewResyncRequestMessage.CITIZEN, citizenId).sendToServer();
            return;
        }
        if (payload[0] != MODE_TRANSIENT || held == null)
        {
            colonyView.setCitizenViewPayload(citizenId, full);
        }
        final RegistryFriendlyByteBuf fullBuf = new RegistryFriendlyByteBuf(new FriendlyByteBuf(Unpooled.wrappedBuffer(full)), citizenBuffer.registryAccess());
        IColonyManager.getInstance().handleColonyViewCitizensMessage(colonyId, citizenId, fullBuf, dimension);
    }
}
