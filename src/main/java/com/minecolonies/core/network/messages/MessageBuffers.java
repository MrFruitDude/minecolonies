package com.minecolonies.core.network.messages;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Helpers for messages that carry a pre-serialized payload buffer.
 */
public final class MessageBuffers
{
    private MessageBuffers()
    {
    }

    /**
     * Writes the bytes written to {@code payload} (index 0 up to its writer index) as a byte array, in the same wire
     * format as {@link FriendlyByteBuf#writeByteArray(byte[])} so {@link FriendlyByteBuf#readByteArray()} reads it.
     * Unlike {@code writeByteArray(payload.array())} it does not send the unused tail of the backing array, which on a
     * growing {@code Unpooled.buffer()} is a quarter to half of it. Leaves {@code payload}'s indices untouched, so the
     * same message can be encoded once per player.
     *
     * @param out     the packet buffer.
     * @param payload the payload buffer.
     */
    public static void writeWrittenBytes(@NotNull final FriendlyByteBuf out, @NotNull final ByteBuf payload)
    {
        final int length = payload.writerIndex();
        out.writeVarInt(length);
        out.writeBytes(payload, 0, length);
    }
}
