package com.minecolonies.core.colony;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Finds the colony an action of a player that owns several colonies is meant for (abandon, delete).
 */
public final class OwnedColonyTarget
{
    private OwnedColonyTarget()
    {
    }

    /**
     * Reads a dimension id sent by a client. A malformed one is a refused packet (a {@link io.netty.handler.codec.DecoderException}, which the connection answers by dropping
     * the sender), not whatever exception the parser throws.
     *
     * @param buf the buffer to read from.
     * @return the id.
     */
    public static Identifier readDimension(@NotNull final FriendlyByteBuf buf)
    {
        final Identifier id = Identifier.tryParse(buf.readUtf(256));
        if (id == null)
        {
            throw new DecoderException("Malformed dimension id");
        }
        return id;
    }

    /**
     * The colony to act on, always one the player owns: the colony the client named, else the colony the player stands
     * in, else the player's selected colony.
     *
     * @param player    the acting player.
     * @param dimension the dimension of the colony the client named, null if it named none.
     * @param id        the id of the colony the client named, negative if it named none.
     * @return the colony, null if there is none the player owns (a named colony that the player does not own is not replaced by another).
     */
    @Nullable
    public static IColony resolve(@NotNull final ServerPlayer player, @Nullable final ResourceKey<Level> dimension, final int id)
    {
        final IColonyManager manager = IColonyManager.getInstance();
        if (dimension != null && id >= 0)
        {
            final IColony named = manager.getColonyByDimension(id, dimension);
            return named != null && player.getUUID().equals(named.getPermissions().getOwner()) ? named : null;
        }

        final IColony here = manager.getIColony(player.level(), player.blockPosition());
        if (here != null && player.getUUID().equals(here.getPermissions().getOwner()))
        {
            return here;
        }
        return manager.getSelectedColony(player.getUUID());
    }
}
