package com.minecolonies.core.colony.requestsystem.management.manager;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.minecolonies.api.colony.requestsystem.data.IDataStore;
import com.minecolonies.api.colony.requestsystem.factory.IFactoryController;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.resolver.IRequestResolver;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.core.colony.requestsystem.data.StandardDataStoreManager;
import com.minecolonies.core.colony.requestsystem.data.StandardRequestIdentitiesDataStore;
import com.minecolonies.core.colony.requestsystem.data.StandardRequestResolversIdentitiesDataStore;
import com.minecolonies.core.network.messages.BinaryDelta;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

/**
 * CA-1: request-system view sync, as a delta keyed by token.
 * <p>
 * The client view used to receive the whole request manager (every request, resolver and assignment store) inside the
 * colony view, whenever anything in it changed, about once a second in an active colony. Now the server keeps the bytes
 * it last sent for each part (the header, every data store, and every entry of the request and resolver identity
 * stores, keyed by token) and sends only the parts whose bytes changed: an entry or store that existed before goes out as
 * a binary patch against its previous bytes when that is smaller. The client keeps the same bytes, so it rebuilds each
 * changed object from exactly the bytes the server serialized; a full payload rebuilds them all.
 * <p>
 * Safety: every payload carries a sequence number and a delta names the sequence it applies to, so a client that missed
 * one (or failed to decode a part) refuses the delta and asks for a full resync. A full resync also goes out periodically.
 */
public final class RequestSystemViewSync
{
    /**
     * Payload modes.
     */
    public static final byte MODE_FULL  = 1;
    public static final byte MODE_DELTA = 2;

    /**
     * Part kinds inside a payload.
     */
    private static final byte STORE_RAW       = 0;
    private static final byte STORE_REQUESTS  = 1;
    private static final byte STORE_RESOLVERS = 2;

    /**
     * Value encodings inside a delta.
     */
    private static final byte VALUE_REMOVED = 0;
    private static final byte VALUE_RAW     = 1;
    private static final byte VALUE_PATCH   = 2;

    /**
     * Store operations inside a delta.
     */
    private static final byte STORE_OP_WHOLE   = 1;
    private static final byte STORE_OP_ENTRIES = 2;
    private static final byte STORE_OP_VALUE   = 3;

    /**
     * Ticks between periodic full resyncs to close subscribers (5 minutes).
     */
    public static final int FULL_RESYNC_TICKS = 6000;

    private RequestSystemViewSync()
    {
    }

    // ------------------------------------------------------------------ snapshot

    /**
     * One data store as bytes. {@code entries} is null for a store sent whole ({@link #STORE_RAW}).
     */
    private record StoreSnap(byte kind, byte[] raw, @Nullable byte[] idBytes, @Nullable LinkedHashMap<IToken<?>, Entry> entries)
    {
    }

    /**
     * One identity entry: its key token's bytes and its value's bytes.
     */
    private record Entry(byte[] key, byte[] value)
    {
    }

    /**
     * The request manager as bytes, part by part, in the manager's own iteration order.
     */
    public static final class Snapshot
    {
        private final byte[]                               header;
        private final LinkedHashMap<IToken<?>, StoreSnap> stores;
        private final Map<IToken<?>, byte[]>              storeKeys;

        private Snapshot(final byte[] header, final LinkedHashMap<IToken<?>, StoreSnap> stores, final Map<IToken<?>, byte[]> storeKeys)
        {
            this.header = header;
            this.stores = stores;
            this.storeKeys = storeKeys;
        }

        /**
         * @return total serialized size of the parts.
         */
        public int size()
        {
            int n = header.length;
            for (final StoreSnap s : stores.values())
            {
                n += s.raw().length;
                if (s.entries() != null)
                {
                    for (final Entry e : s.entries().values())
                    {
                        n += e.key().length + e.value().length;
                    }
                }
            }
            return n;
        }
    }

    /**
     * Serializes the manager part by part.
     */
    @NotNull
    public static Snapshot snapshot(@NotNull final StandardRequestManager manager, @NotNull final RegistryAccess ra)
    {
        final IFactoryController controller = manager.getFactoryController();
        final byte[] header = bytes(ra, b -> manager.writeViewHeader(controller, b));
        final LinkedHashMap<IToken<?>, StoreSnap> stores = new LinkedHashMap<>();
        final Map<IToken<?>, byte[]> storeKeys = new HashMap<>();
        for (final Map.Entry<IToken<?>, IDataStore> store : storeMap(manager).entrySet())
        {
            storeKeys.put(store.getKey(), bytes(ra, b -> controller.serialize(b, store.getKey())));
            final IDataStore value = store.getValue();
            if (value instanceof StandardRequestIdentitiesDataStore requests)
            {
                stores.put(store.getKey(), identitySnap(STORE_REQUESTS, requests.getId(), requests.getIdentities(), controller, ra));
            }
            else if (value instanceof StandardRequestResolversIdentitiesDataStore resolvers)
            {
                stores.put(store.getKey(), identitySnap(STORE_RESOLVERS, resolvers.getId(), resolvers.getIdentities(), controller, ra));
            }
            else
            {
                stores.put(store.getKey(), new StoreSnap(STORE_RAW, bytes(ra, b -> controller.serialize(b, value)), null, null));
            }
        }
        return new Snapshot(header, stores, storeKeys);
    }

    private static StoreSnap identitySnap(
      final byte kind,
      final IToken<?> id,
      final BiMap<IToken<?>, ?> identities,
      final IFactoryController controller,
      final RegistryAccess ra)
    {
        final LinkedHashMap<IToken<?>, Entry> entries = new LinkedHashMap<>();
        for (final Map.Entry<IToken<?>, ?> e : identities.entrySet())
        {
            entries.put(e.getKey(), new Entry(bytes(ra, b -> controller.serialize(b, e.getKey())), bytes(ra, b -> controller.serialize(b, e.getValue()))));
        }
        return new StoreSnap(kind, new byte[0], bytes(ra, b -> controller.serialize(b, id)), entries);
    }

    // ------------------------------------------------------------------ server

    /**
     * What every close subscriber holds: the last snapshot sent to them, and its sequence number.
     */
    public static final class ServerBaseline
    {
        @Nullable
        private Snapshot baseline;
        private int      seq;
        private long     lastFullTick;

        /**
         * @return the snapshot close subscribers hold, or null before the first send.
         */
        @Nullable
        public Snapshot baseline()
        {
            return baseline;
        }

        public int seq()
        {
            return seq;
        }

        public boolean fullDue(final long gameTime)
        {
            return baseline != null && gameTime - lastFullTick >= FULL_RESYNC_TICKS;
        }

        /**
         * Moves the baseline to {@code next}.
         *
         * @return the new sequence number.
         */
        public int advance(@NotNull final Snapshot next, final boolean full, final long gameTime)
        {
            baseline = next;
            seq++;
            if (full)
            {
                lastFullTick = gameTime;
            }
            return seq;
        }

        public void markFullSent(final long gameTime)
        {
            lastFullTick = gameTime;
        }

        public void clear()
        {
            baseline = null;
        }
    }

    /**
     * Writes a full payload for {@code snap} at sequence {@code seq}.
     */
    public static void writeFull(@NotNull final RegistryFriendlyByteBuf buf, @NotNull final Snapshot snap, final int seq)
    {
        buf.writeByte(MODE_FULL);
        buf.writeInt(seq);
        buf.writeByteArray(snap.header);
        buf.writeVarInt(snap.stores.size());
        for (final Map.Entry<IToken<?>, StoreSnap> store : snap.stores.entrySet())
        {
            buf.writeByteArray(snap.storeKeys.get(store.getKey()));
            writeStoreWhole(buf, store.getValue());
        }
    }

    private static void writeStoreWhole(final RegistryFriendlyByteBuf buf, final StoreSnap store)
    {
        buf.writeByte(store.kind());
        if (store.entries() == null)
        {
            buf.writeByteArray(store.raw());
            return;
        }
        buf.writeByteArray(store.idBytes());
        buf.writeVarInt(store.entries().size());
        for (final Entry e : store.entries().values())
        {
            buf.writeByteArray(e.key());
            buf.writeByteArray(e.value());
        }
    }

    /**
     * Writes the delta from {@code from} (sequence {@code fromSeq}) to {@code to} (sequence {@code toSeq}).
     *
     * @return false when nothing changed, in which case nothing useful was written.
     */
    public static boolean writeDelta(
      @NotNull final RegistryFriendlyByteBuf buf,
      @NotNull final Snapshot from,
      final int fromSeq,
      @NotNull final Snapshot to,
      final int toSeq)
    {
        boolean changed = false;
        buf.writeByte(MODE_DELTA);
        buf.writeInt(fromSeq);
        buf.writeInt(toSeq);
        if (Arrays.equals(from.header, to.header))
        {
            buf.writeBoolean(false);
        }
        else
        {
            buf.writeBoolean(true);
            buf.writeByteArray(to.header);
            changed = true;
        }

        // Stores removed.
        final List<IToken<?>> removed = new ArrayList<>();
        for (final IToken<?> token : from.stores.keySet())
        {
            if (!to.stores.containsKey(token))
            {
                removed.add(token);
            }
        }
        buf.writeVarInt(removed.size());
        for (final IToken<?> token : removed)
        {
            buf.writeByteArray(from.storeKeys.get(token));
        }
        changed |= !removed.isEmpty();

        // Stores added or changed.
        final int countIndex = buf.writerIndex();
        buf.writeInt(0);
        int count = 0;
        for (final Map.Entry<IToken<?>, StoreSnap> store : to.stores.entrySet())
        {
            final StoreSnap now = store.getValue();
            final StoreSnap before = from.stores.get(store.getKey());
            if (before != null && before.kind() == now.kind() && now.entries() != null && Arrays.equals(before.idBytes(), now.idBytes()))
            {
                // Identity store: per-entry delta.
                final List<IToken<?>> gone = new ArrayList<>();
                for (final IToken<?> key : before.entries().keySet())
                {
                    if (!now.entries().containsKey(key))
                    {
                        gone.add(key);
                    }
                }
                final List<IToken<?>> diff = new ArrayList<>();
                for (final Map.Entry<IToken<?>, Entry> e : now.entries().entrySet())
                {
                    final Entry old = before.entries().get(e.getKey());
                    if (old == null || !Arrays.equals(old.value(), e.getValue().value()))
                    {
                        diff.add(e.getKey());
                    }
                }
                if (gone.isEmpty() && diff.isEmpty())
                {
                    continue;
                }
                count++;
                buf.writeByteArray(to.storeKeys.get(store.getKey()));
                buf.writeByte(STORE_OP_ENTRIES);
                buf.writeVarInt(gone.size() + diff.size());
                for (final IToken<?> key : gone)
                {
                    buf.writeByteArray(before.entries().get(key).key());
                    buf.writeByte(VALUE_REMOVED);
                }
                for (final IToken<?> key : diff)
                {
                    final Entry e = now.entries().get(key);
                    final Entry old = before.entries().get(key);
                    buf.writeByteArray(e.key());
                    writeValue(buf, old == null ? null : old.value(), e.value());
                }
            }
            else if (before == null || before.kind() != now.kind() || now.entries() != null || !Arrays.equals(before.raw(), now.raw()))
            {
                count++;
                buf.writeByteArray(to.storeKeys.get(store.getKey()));
                if (now.entries() == null && before != null && before.entries() == null)
                {
                    // Plain store that existed before: raw or patch.
                    buf.writeByte(STORE_OP_VALUE);
                    writeValue(buf, before.raw(), now.raw());
                }
                else
                {
                    buf.writeByte(STORE_OP_WHOLE);
                    writeStoreWhole(buf, now);
                }
            }
        }
        buf.setInt(countIndex, count);
        return changed || count > 0;
    }

    private static void writeValue(final RegistryFriendlyByteBuf buf, @Nullable final byte[] old, final byte[] now)
    {
        if (old != null)
        {
            final byte[] patch = BinaryDelta.diff(old, now);
            if (patch.length < now.length)
            {
                buf.writeByte(VALUE_PATCH);
                buf.writeByteArray(patch);
                return;
            }
        }
        buf.writeByte(VALUE_RAW);
        buf.writeByteArray(now);
    }

    // ------------------------------------------------------------------ client

    /**
     * What a client holds: the bytes of every part of its request manager view, and the payload sequence they belong to.
     */
    public static final class ClientMirror
    {
        private int                                                seq    = -1;
        private byte[]                                             header = new byte[0];
        private final Map<IToken<?>, byte[]>                       raw    = new HashMap<>();
        private final Map<IToken<?>, Map<IToken<?>, byte[]>>       values = new HashMap<>();

        public int seq()
        {
            return seq;
        }
    }

    /**
     * Applies one payload to the client view.
     *
     * @return false when the payload could not be applied (a delta against another sequence, or a part that failed to
     * decode); the caller then asks the server for a full resync.
     */
    public static boolean apply(
      @NotNull final StandardRequestManager manager,
      @NotNull final ClientMirror mirror,
      @NotNull final RegistryFriendlyByteBuf buf)
    {
        final IFactoryController controller = manager.getFactoryController();
        final RegistryAccess ra = buf.registryAccess();
        final byte mode = buf.readByte();
        if (mode == MODE_FULL)
        {
            final int seq = buf.readInt();
            final byte[] header = buf.readByteArray();
            final Map<IToken<?>, IDataStore> stores = new HashMap<>();
            final Map<IToken<?>, byte[]> raw = new HashMap<>();
            final Map<IToken<?>, Map<IToken<?>, byte[]>> values = new HashMap<>();
            final int n = buf.readVarInt();
            boolean ok = true;
            for (int i = 0; i < n; i++)
            {
                final IToken<?> token = read(controller, ra, buf.readByteArray());
                final IDataStore store = readStoreWhole(buf, controller, ra, token, raw, values);
                if (token == null || store == null)
                {
                    ok = false;
                    continue;
                }
                stores.put(token, store);
            }
            readHeader(manager, controller, ra, header);
            final Map<IToken<?>, IDataStore> target = storeMap(manager);
            target.clear();
            target.putAll(stores);
            mirror.header = header;
            mirror.raw.clear();
            mirror.raw.putAll(raw);
            mirror.values.clear();
            mirror.values.putAll(values);
            mirror.seq = ok ? seq : -1;
            return ok;
        }
        if (mode != MODE_DELTA)
        {
            return false;
        }

        final int baseSeq = buf.readInt();
        final int seq = buf.readInt();
        if (mirror.seq < 0 || baseSeq != mirror.seq)
        {
            return false;
        }
        boolean ok = true;
        if (buf.readBoolean())
        {
            mirror.header = buf.readByteArray();
            readHeader(manager, controller, ra, mirror.header);
        }
        final Map<IToken<?>, IDataStore> target = storeMap(manager);
        final int removed = buf.readVarInt();
        for (int i = 0; i < removed; i++)
        {
            final IToken<?> token = read(controller, ra, buf.readByteArray());
            if (token == null)
            {
                ok = false;
                continue;
            }
            target.remove(token);
            mirror.raw.remove(token);
            mirror.values.remove(token);
        }
        final int changed = buf.readInt();
        for (int i = 0; i < changed; i++)
        {
            final IToken<?> token = read(controller, ra, buf.readByteArray());
            final byte how = buf.readByte();
            if (how == STORE_OP_ENTRIES)
            {
                // Per-entry delta of an identity store the client already has.
                final Map<IToken<?>, byte[]> held = token == null ? null : mirror.values.get(token);
                final IDataStore store = token == null ? null : target.get(token);
                final BiMap<IToken<?>, Object> identities = identities(store);
                final int entries = buf.readVarInt();
                for (int j = 0; j < entries; j++)
                {
                    final IToken<?> key = read(controller, ra, buf.readByteArray());
                    final byte valueHow = buf.readByte();
                    if (valueHow == VALUE_REMOVED)
                    {
                        if (held != null && identities != null && key != null)
                        {
                            held.remove(key);
                            identities.remove(key);
                        }
                        else
                        {
                            ok = false;
                        }
                        continue;
                    }
                    final byte[] bytes = readValue(buf, valueHow, held == null || key == null ? null : held.get(key));
                    final Object value = bytes == null ? null : read(controller, ra, bytes);
                    if (held == null || identities == null || key == null || value == null)
                    {
                        ok = false;
                        continue;
                    }
                    held.put(key, bytes);
                    identities.remove(key);
                    identities.forcePut(key, value);
                }
                continue;
            }
            if (how == STORE_OP_VALUE)
            {
                final byte valueHow = buf.readByte();
                final byte[] bytes = readValue(buf, valueHow, token == null ? null : mirror.raw.get(token));
                final IDataStore store = bytes == null ? null : read(controller, ra, bytes);
                if (token == null || store == null)
                {
                    ok = false;
                    continue;
                }
                mirror.raw.put(token, bytes);
                target.put(token, store);
                continue;
            }
            if (how != STORE_OP_WHOLE)
            {
                return false;
            }
            final byte kind = buf.readByte();
            final Map<IToken<?>, byte[]> raw = new HashMap<>();
            final Map<IToken<?>, Map<IToken<?>, byte[]>> values = new HashMap<>();
            final IDataStore store = readStoreBody(buf, kind, controller, ra, token, raw, values);
            if (token == null || store == null)
            {
                ok = false;
                continue;
            }
            mirror.raw.remove(token);
            mirror.values.remove(token);
            mirror.raw.putAll(raw);
            mirror.values.putAll(values);
            target.put(token, store);
        }
        mirror.seq = ok ? seq : -1;
        return ok;
    }

    @Nullable
    private static IDataStore readStoreWhole(
      final RegistryFriendlyByteBuf buf,
      final IFactoryController controller,
      final RegistryAccess ra,
      @Nullable final IToken<?> token,
      final Map<IToken<?>, byte[]> raw,
      final Map<IToken<?>, Map<IToken<?>, byte[]>> values)
    {
        return readStoreBody(buf, buf.readByte(), controller, ra, token, raw, values);
    }

    @Nullable
    private static IDataStore readStoreBody(
      final RegistryFriendlyByteBuf buf,
      final byte kind,
      final IFactoryController controller,
      final RegistryAccess ra,
      @Nullable final IToken<?> token,
      final Map<IToken<?>, byte[]> raw,
      final Map<IToken<?>, Map<IToken<?>, byte[]>> values)
    {
        if (kind == STORE_RAW)
        {
            final byte[] bytes = buf.readByteArray();
            final IDataStore store = read(controller, ra, bytes);
            if (token != null && store != null)
            {
                raw.put(token, bytes);
            }
            return store;
        }
        final IToken<?> id = read(controller, ra, buf.readByteArray());
        final int n = buf.readVarInt();
        final Map<IToken<?>, byte[]> held = new HashMap<>();
        final BiMap<IToken<?>, Object> map = HashBiMap.create();
        boolean ok = id != null;
        for (int i = 0; i < n; i++)
        {
            final IToken<?> key = read(controller, ra, buf.readByteArray());
            final byte[] bytes = buf.readByteArray();
            final Object value = read(controller, ra, bytes);
            if (key == null || value == null)
            {
                ok = false;
                continue;
            }
            held.put(key, bytes);
            map.forcePut(key, value);
        }
        if (!ok || token == null)
        {
            return null;
        }
        values.put(token, held);
        return newIdentityStore(kind, id, map);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static IDataStore newIdentityStore(final byte kind, final IToken<?> id, final BiMap<IToken<?>, Object> map)
    {
        if (kind == STORE_REQUESTS)
        {
            return new StandardRequestIdentitiesDataStore(id, (BiMap<IToken<?>, IRequest<?>>) (BiMap) map);
        }
        return new StandardRequestResolversIdentitiesDataStore(id, (BiMap<IToken<?>, IRequestResolver<?>>) (BiMap) map);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Nullable
    private static BiMap<IToken<?>, Object> identities(@Nullable final IDataStore store)
    {
        if (store instanceof StandardRequestIdentitiesDataStore requests)
        {
            return (BiMap) requests.getIdentities();
        }
        if (store instanceof StandardRequestResolversIdentitiesDataStore resolvers)
        {
            return (BiMap) resolvers.getIdentities();
        }
        return null;
    }

    @Nullable
    private static byte[] readValue(final RegistryFriendlyByteBuf buf, final byte how, @Nullable final byte[] old)
    {
        final byte[] bytes = buf.readByteArray();
        if (how == VALUE_RAW)
        {
            return bytes;
        }
        if (how == VALUE_PATCH && old != null)
        {
            try
            {
                return BinaryDelta.apply(old, bytes);
            }
            catch (final IllegalArgumentException e)
            {
                return null;
            }
        }
        return null;
    }

    private static void readHeader(final StandardRequestManager manager, final IFactoryController controller, final RegistryAccess ra, final byte[] header)
    {
        final RegistryFriendlyByteBuf b = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(header), ra);
        manager.readViewHeader(controller, b);
    }

    // ------------------------------------------------------------------ canonical form (tests)

    /**
     * The manager's content in a canonical order: the header, then every data store sorted by token, and inside the
     * request and resolver identity stores every entry sorted by token. Two managers with equal canonical bytes hold the
     * same serialized content; only hash-map iteration order (which the full payload does not fix either) is ignored.
     */
    @NotNull
    public static byte[] canonicalBytes(@NotNull final StandardRequestManager manager, @NotNull final RegistryAccess ra)
    {
        final Snapshot snap = snapshot(manager, ra);
        final RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), ra);
        out.writeByteArray(snap.header);
        final List<IToken<?>> tokens = new ArrayList<>(snap.stores.keySet());
        tokens.sort((a, b) -> Arrays.compare(snap.storeKeys.get(a), snap.storeKeys.get(b)));
        out.writeVarInt(tokens.size());
        for (final IToken<?> token : tokens)
        {
            final StoreSnap store = snap.stores.get(token);
            out.writeByteArray(snap.storeKeys.get(token));
            out.writeByte(store.kind());
            if (store.entries() == null)
            {
                out.writeByteArray(store.raw());
                continue;
            }
            out.writeByteArray(store.idBytes());
            final List<Entry> entries = new ArrayList<>(store.entries().values());
            entries.sort((a, b) -> Arrays.compare(a.key(), b.key()));
            out.writeVarInt(entries.size());
            for (final Entry e : entries)
            {
                out.writeByteArray(e.key());
                out.writeByteArray(e.value());
            }
        }
        final byte[] result = new byte[out.writerIndex()];
        out.getBytes(0, result);
        return result;
    }

    // ------------------------------------------------------------------ helpers

    @NotNull
    static Map<IToken<?>, IDataStore> storeMap(@NotNull final StandardRequestManager manager)
    {
        return ((StandardDataStoreManager) manager.getDataStoreManager()).getStoreMap();
    }

    private static byte[] bytes(final RegistryAccess ra, final Consumer<RegistryFriendlyByteBuf> writer)
    {
        final RegistryFriendlyByteBuf b = new RegistryFriendlyByteBuf(Unpooled.buffer(), ra);
        writer.accept(b);
        final byte[] out = new byte[b.writerIndex()];
        b.getBytes(0, out);
        return out;
    }

    @Nullable
    private static <T> T read(final IFactoryController controller, final RegistryAccess ra, final byte[] bytes)
    {
        try
        {
            final RegistryFriendlyByteBuf b = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), ra);
            return controller.deserialize(b);
        }
        catch (final RuntimeException e)
        {
            return null;
        }
    }
}
