package com.minecolonies.core.network.messages.client.colony;

import com.ldtteam.common.network.AbstractClientPlayMessage;
import com.ldtteam.common.network.PlayMessageType;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.OwnedColonySummary;
import com.minecolonies.api.util.constant.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Tells a player which colonies they own (id, dimension, name, town hall position, citizen count, colour, banner and
 * whether it is the selected one), also colonies the player is not subscribed to. Sent on login and on every change.
 */
public class OwnedColoniesMessage extends AbstractClientPlayMessage
{
    public static final PlayMessageType<?> TYPE = PlayMessageType.forClient(Constants.MOD_ID, "owned_colonies", OwnedColoniesMessage::new);

    /**
     * A player owns far fewer colonies than this; the limit keeps a bad packet from allocating.
     */
    private static final int MAX_ENTRIES = 256;

    private final List<OwnedColonySummary> summaries;

    public OwnedColoniesMessage(final List<OwnedColonySummary> summaries)
    {
        super(TYPE);
        this.summaries = List.copyOf(summaries);
    }

    protected OwnedColoniesMessage(final RegistryFriendlyByteBuf buf, final PlayMessageType<?> type)
    {
        super(buf, type);
        final int size = Math.min(buf.readVarInt(), MAX_ENTRIES);
        final List<OwnedColonySummary> read = new ArrayList<>(size);
        for (int i = 0; i < size; i++)
        {
            read.add(OwnedColonySummary.read(buf));
        }
        this.summaries = read;
    }

    /**
     * @return the owned colonies this message carries.
     */
    public List<OwnedColonySummary> getSummaries()
    {
        return summaries;
    }

    @Override
    protected void toBytes(final RegistryFriendlyByteBuf buf)
    {
        buf.writeVarInt(summaries.size());
        for (final OwnedColonySummary summary : summaries)
        {
            summary.write(buf);
        }
    }

    @Override
    protected void onExecute(final IPayloadContext ctxIn, final Player player)
    {
        IColonyManager.getInstance().handleOwnedColoniesMessage(summaries);
    }
}
