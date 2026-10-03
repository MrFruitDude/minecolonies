package com.minecolonies.core.network.messages;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Small binary delta (CA-1/CA-3 view sync): describes {@code now} as copies of byte ranges of {@code old} plus literal
 * bytes. Used where the receiver already holds {@code old} byte for byte, so the patched result is exactly {@code now}.
 * <p>
 * Format: VarInt length of the result, then operations until the result is full: {@code 0} = copy (VarInt offset in old,
 * VarInt length), {@code 1} = literal (VarInt length, bytes).
 */
public final class BinaryDelta
{
    private static final int  BLOCK   = 16;
    private static final byte COPY    = 0;
    private static final byte LITERAL = 1;

    private BinaryDelta()
    {
    }

    /**
     * @return a patch that turns {@code old} into {@code now}.
     */
    @NotNull
    public static byte[] diff(@NotNull final byte[] old, @NotNull final byte[] now)
    {
        final FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
        out.writeVarInt(now.length);

        int prefix = 0;
        final int maxCommon = Math.min(old.length, now.length);
        while (prefix < maxCommon && old[prefix] == now[prefix])
        {
            prefix++;
        }
        int suffix = 0;
        while (suffix < maxCommon - prefix && old[old.length - 1 - suffix] == now[now.length - 1 - suffix])
        {
            suffix++;
        }
        if (prefix > 0)
        {
            copy(out, 0, prefix);
        }

        final int end = now.length - suffix;
        final int oldStart = prefix;
        final int oldEnd = old.length - suffix;
        final Map<Long, Integer> index = new HashMap<>();
        for (int pos = oldStart; pos + BLOCK <= oldEnd; pos += BLOCK)
        {
            index.putIfAbsent(hash(old, pos), pos);
        }

        int literalStart = prefix;
        int i = prefix;
        while (i + BLOCK <= end)
        {
            final Integer cand = index.isEmpty() ? null : index.get(hash(now, i));
            if (cand != null && Arrays.equals(old, cand, cand + BLOCK, now, i, i + BLOCK))
            {
                int from = cand;
                int at = i;
                while (at > literalStart && from > 0 && old[from - 1] == now[at - 1])
                {
                    from--;
                    at--;
                }
                int len = i + BLOCK - at;
                while (at + len < end && from + len < old.length && old[from + len] == now[at + len])
                {
                    len++;
                }
                if (at > literalStart)
                {
                    literal(out, now, literalStart, at);
                }
                copy(out, from, len);
                i = at + len;
                literalStart = i;
                continue;
            }
            i++;
        }
        if (end > literalStart)
        {
            literal(out, now, literalStart, end);
        }
        if (suffix > 0)
        {
            copy(out, old.length - suffix, suffix);
        }

        final byte[] patch = new byte[out.writerIndex()];
        out.getBytes(0, patch);
        return patch;
    }

    /**
     * Applies a patch made by {@link #diff} to the {@code old} bytes it was made against.
     *
     * @throws IllegalArgumentException when the patch does not fit {@code old}.
     */
    @NotNull
    public static byte[] apply(@NotNull final byte[] old, @NotNull final byte[] patch)
    {
        final FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(patch));
        try
        {
            final int length = in.readVarInt();
            if (length < 0)
            {
                throw new IllegalArgumentException("negative length");
            }
            final byte[] now = new byte[length];
            int at = 0;
            while (at < length)
            {
                final byte op = in.readByte();
                if (op == COPY)
                {
                    final int from = in.readVarInt();
                    final int len = in.readVarInt();
                    if (from < 0 || len <= 0 || from + len > old.length || at + len > length)
                    {
                        throw new IllegalArgumentException("copy out of range");
                    }
                    System.arraycopy(old, from, now, at, len);
                    at += len;
                }
                else if (op == LITERAL)
                {
                    final int len = in.readVarInt();
                    if (len <= 0 || at + len > length)
                    {
                        throw new IllegalArgumentException("literal out of range");
                    }
                    in.readBytes(now, at, len);
                    at += len;
                }
                else
                {
                    throw new IllegalArgumentException("unknown op " + op);
                }
            }
            if (in.isReadable())
            {
                throw new IllegalArgumentException("trailing patch bytes");
            }
            return now;
        }
        catch (final IndexOutOfBoundsException e)
        {
            throw new IllegalArgumentException("truncated patch", e);
        }
    }

    private static void copy(final FriendlyByteBuf out, final int from, final int len)
    {
        out.writeByte(COPY);
        out.writeVarInt(from);
        out.writeVarInt(len);
    }

    private static void literal(final FriendlyByteBuf out, final byte[] src, final int from, final int to)
    {
        out.writeByte(LITERAL);
        out.writeVarInt(to - from);
        out.writeBytes(src, from, to - from);
    }

    private static long hash(final byte[] b, final int from)
    {
        long h = 1125899906842597L;
        for (int k = from; k < from + BLOCK; k++)
        {
            h = 31 * h + b[k];
        }
        return h;
    }
}
