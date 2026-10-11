package com.minecolonies.api.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import org.jetbrains.annotations.NotNull;

/**
 * Lightweight description of a colony a player owns. The server sends the list of these to the owner on login and
 * whenever one changes, so the client knows its owned colonies even for colonies it is not subscribed to (it holds no
 * colony view for those). Read it client-side with {@link IColonyManager#getOwnedColonySummaries()}.
 *
 * @param id            the colony id, unique per dimension only.
 * @param dimension     the dimension of the colony.
 * @param name          the colony name.
 * @param townHallPos   the town hall position, the colony center while there is no town hall building.
 * @param citizenCount  the number of citizens.
 * @param colour        the team colour of the colony as 0xRRGGBB.
 * @param banner        the colony flag (banner patterns).
 * @param selected      whether this is the owner's selected colony ({@link IColonyManager#getSelectedColony}).
 */
public record OwnedColonySummary(
  int id,
  @NotNull ResourceKey<Level> dimension,
  @NotNull String name,
  @NotNull BlockPos townHallPos,
  int citizenCount,
  int colour,
  @NotNull BannerPatternLayers banner,
  boolean selected)
{
    /**
     * Longest colony name written to the network.
     */
    private static final int MAX_NAME_LENGTH = 256;

    /**
     * Writes this summary.
     *
     * @param buf the buffer.
     */
    public void write(@NotNull final RegistryFriendlyByteBuf buf)
    {
        buf.writeInt(id);
        buf.writeUtf(dimension.identifier().toString());
        buf.writeUtf(name, MAX_NAME_LENGTH);
        buf.writeBlockPos(townHallPos);
        buf.writeVarInt(citizenCount);
        buf.writeInt(colour);
        BannerPatternLayers.STREAM_CODEC.encode(buf, banner);
        buf.writeBoolean(selected);
    }

    /**
     * Reads a summary written by {@link #write}.
     *
     * @param buf the buffer.
     * @return the summary.
     */
    @NotNull
    public static OwnedColonySummary read(@NotNull final RegistryFriendlyByteBuf buf)
    {
        final int id = buf.readInt();
        final ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(buf.readUtf(256)));
        final String name = buf.readUtf(MAX_NAME_LENGTH);
        final BlockPos pos = buf.readBlockPos();
        final int citizens = buf.readVarInt();
        final int colour = buf.readInt();
        final BannerPatternLayers banner = BannerPatternLayers.STREAM_CODEC.decode(buf);
        final boolean selected = buf.readBoolean();
        return new OwnedColonySummary(id, dimension, name, pos, citizens, colour, banner, selected);
    }
}
