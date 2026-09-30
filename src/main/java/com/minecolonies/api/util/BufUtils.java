package com.minecolonies.api.util;

import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Collection/map helpers for byte buffers.
 * <p>
 * MC 26.3 removed FriendlyByteBuf#writeCollection/readList/readCollection/writeMap/readMap. These keep the
 * same wire format (VarInt size prefix followed by the elements).
 */
public final class BufUtils
{
    private BufUtils()
    {
        // utility class
    }

    public static <B extends FriendlyByteBuf, T> void writeCollection(@NotNull final B buf, @NotNull final Collection<T> collection, @NotNull final BiConsumer<B, T> writer)
    {
        buf.writeVarInt(collection.size());
        for (final T element : collection)
        {
            writer.accept(buf, element);
        }
    }

    public static <B extends FriendlyByteBuf, T, C extends Collection<T>> C readCollection(@NotNull final B buf, @NotNull final IntFunction<C> factory, @NotNull final Function<B, T> reader)
    {
        final int size = buf.readVarInt();
        final C collection = factory.apply(size);
        for (int i = 0; i < size; i++)
        {
            collection.add(reader.apply(buf));
        }
        return collection;
    }

    public static <B extends FriendlyByteBuf, T> List<T> readList(@NotNull final B buf, @NotNull final Function<B, T> reader)
    {
        return readCollection(buf, ArrayList::new, reader);
    }

    public static <B extends FriendlyByteBuf, K, V> void writeMap(
      @NotNull final B buf,
      @NotNull final Map<K, V> map,
      @NotNull final BiConsumer<B, K> keyWriter,
      @NotNull final BiConsumer<B, V> valueWriter)
    {
        buf.writeVarInt(map.size());
        map.forEach((key, value) -> {
            keyWriter.accept(buf, key);
            valueWriter.accept(buf, value);
        });
    }

    public static <B extends FriendlyByteBuf, K, V> Map<K, V> readMap(@NotNull final B buf, @NotNull final Function<B, K> keyReader, @NotNull final Function<B, V> valueReader)
    {
        final int size = buf.readVarInt();
        final Map<K, V> map = new HashMap<>(size);
        for (int i = 0; i < size; i++)
        {
            final K key = keyReader.apply(buf);
            map.put(key, valueReader.apply(buf));
        }
        return map;
    }
}
