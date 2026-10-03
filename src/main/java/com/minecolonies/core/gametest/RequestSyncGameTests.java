package com.minecolonies.core.gametest;

import com.ldtteam.common.network.AbstractClientPlayMessage;
import com.ldtteam.common.network.PlayMessageType;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.requestsystem.StandardFactoryController;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.ColonyView;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;
import com.minecolonies.core.colony.managers.ColonyPackageManager;
import com.minecolonies.core.colony.requestsystem.management.manager.RequestSystemViewSync;
import com.minecolonies.core.colony.requestsystem.management.manager.StandardRequestManager;
import com.minecolonies.core.network.messages.client.colony.ColonyViewCitizenViewMessage;
import com.minecolonies.core.network.messages.client.colony.ColonyViewMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.negotiation.NegotiableNetworkComponent;
import net.neoforged.neoforge.network.negotiation.NegotiationResult;
import net.neoforged.neoforge.network.negotiation.NetworkComponentNegotiator;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;

/**
 * CA-1 + CA-3 view sync GameTests (glob {@code minecolonies:reqsync_*}).
 * <ul>
 * <li>{@code reqsync_bytes_per_minute}: a colony with 100 open requests, one minute with no changes and one minute with
 * one request created and one cancelled per second. The colony view + request-system messages must carry at most a tenth
 * of what one full request-manager payload per second (the old behaviour under churn) would.</li>
 * <li>{@code reqsync_client_equals_full}: create, assign, complete, cancel, and a reconnect with changes made while away;
 * after each step the client view rebuilt from the messages the player received equals a view rebuilt from the full
 * payload, and no delta was refused.</li>
 * <li>{@code reqsync_protocol_mismatch}: a client announcing the pre-change channel version is refused at login with a
 * reason; a matching client is accepted.</li>
 * <li>{@code reqsync_citizen_view_patch}: CA-3, a citizen whose inventory changes every second; the citizen view bytes
 * must be under 30% of resending its full view each time, and the client's rebuilt view payload equals the server's.</li>
 * </ul>
 */
public final class RequestSyncGameTests
{
    private static final int OPEN_REQUESTS = 100;
    private static final int SETTLE_TICKS  = 400;
    private static final int MINUTE        = 1200;
    private static final int STEP_TICKS    = 50;

    private RequestSyncGameTests()
    {
    }

    // ------------------------------------------------------------------ fixture

    /**
     * One recorded message to the test player with its encoded size.
     */
    private record Sent(long tick, AbstractClientPlayMessage msg, int bytes, byte[] reference)
    {
    }

    private static final class Fixture
    {
        final GameTestHelper helper;
        final ServerLevel    level;
        final Colony         colony;
        final ServerPlayer   player;
        final List<Sent>     sent = new ArrayList<>();
        /**
         * When set, every message carrying request-system data also records the full-payload reference of the server's
         * request manager at that moment.
         */
        boolean recordReference;

        Fixture(final GameTestHelper helper, final ServerLevel level, final Colony colony, final ServerPlayer player)
        {
            this.helper = helper;
            this.level = level;
            this.colony = colony;
            this.player = player;
        }

        long now()
        {
            return level.getGameTime();
        }

        StandardRequestManager rm()
        {
            return (StandardRequestManager) colony.getRequestManager();
        }

        /**
         * Creates and assigns a request through the town hall, so the building's own request maps know it (a request
         * cancelled behind the building's back trips its cancellation callback).
         */
        IToken<?> request(final Item item, final int count)
        {
            return colony.getServerBuildingManager().getTownHall().createRequest(new Stack(new ItemStack(item), count, 1), false);
        }

        List<Sent> since(final long tick)
        {
            return sent.stream().filter(s -> s.tick() >= tick).toList();
        }

        void close()
        {
            ColonyPackageManager.viewSendRecorder = null;
            colony.getPackageManager().removeCloseSubscriber(player);
        }
    }

    private static void pinClock(final ServerLevel level)
    {
        level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
    }

    /**
     * Colony with a connected mock player as close subscriber; every view message to it is recorded with its encoded size.
     */
    private static Fixture fixture(final GameTestHelper helper, final String name)
    {
        return fixture(helper, name, false);
    }

    private static Fixture fixture(final GameTestHelper helper, final String name, final boolean recordReference)
    {
        final ServerLevel level = helper.getLevel();
        pinClock(level);
        final Colony colony = (Colony) MinecoloniesGameTests.foundGameTestColony(helper, name);
        final BlockPos anchor = colony.getCenter();
        final ServerPlayer player = MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper);
        player.teleportTo(anchor.getX() + 0.5D, anchor.getY() + 1, anchor.getZ() + 0.5D);
        final Fixture f = new Fixture(helper, level, colony, player);
        f.recordReference = recordReference;
        final RegistryAccess ra = level.registryAccess();
        ColonyPackageManager.viewSendRecorder = (to, msg) -> {
            if (to == player)
            {
                final byte[] reference = f.recordReference && carriesRequestSystem(msg, ra) ? referenceCanonical(f) : null;
                f.sent.add(new Sent(level.getGameTime(), msg, encodedSize(msg, ra), reference));
            }
        };
        final BoolSetting moveIn = colony.getSettings().getSetting(BuildingTownHall.MOVE_IN);
        if (moveIn.getValue())
        {
            moveIn.trigger();
        }
        colony.getPackageManager().addCloseSubscriber(player);
        return f;
    }

    /**
     * Runs {@code step} after {@code ticks} ticks, pinning the overworld clock at 6000 every tick meanwhile.
     */
    private static void after(final Fixture f, final int ticks, final Runnable step)
    {
        final long until = f.now() + ticks;
        final Runnable[] pump = new Runnable[1];
        pump[0] = () -> {
            pinClock(f.level);
            if (f.now() >= until)
            {
                try
                {
                    step.run();
                }
                catch (final RuntimeException e)
                {
                    Log.getLogger().error("reqsync step failed", e);
                    f.close();
                    throw e;
                }
                return;
            }
            f.helper.runAfterDelay(1, () -> pump[0].run());
        };
        pump[0].run();
    }

    private static List<Item> requestItems(final int n, final int offset)
    {
        final List<Item> items = new ArrayList<>();
        int skip = offset;
        for (final Item item : BuiltInRegistries.ITEM)
        {
            if (item == Items.AIR || !new ItemStack(item).isStackable())
            {
                continue;
            }
            if (skip-- > 0)
            {
                continue;
            }
            items.add(item);
            if (items.size() == n)
            {
                break;
            }
        }
        return items;
    }

    // ------------------------------------------------------------------ message decoding

    @SuppressWarnings("unchecked")
    private static StreamCodec<RegistryFriendlyByteBuf, CustomPacketPayload> codec(final Class<?> type)
    {
        try
        {
            final PlayMessageType<?> playType = (PlayMessageType<?>) type.getField("TYPE").get(null);
            return (StreamCodec<RegistryFriendlyByteBuf, CustomPacketPayload>) (StreamCodec<?, ?>) playType.codec();
        }
        catch (final ReflectiveOperationException e)
        {
            throw new IllegalStateException("no TYPE on " + type, e);
        }
    }

    private static int encodedSize(final AbstractClientPlayMessage msg, final RegistryAccess ra)
    {
        final RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), ra);
        codec(msg.getClass()).encode(out, msg);
        return out.writerIndex();
    }

    /**
     * A copy of the payload bytes a message carries (its first buffer field, from 0 to the writer index).
     */
    private static byte[] payloadBytes(final Object msg)
    {
        for (Class<?> c = msg.getClass(); c != null && c != Object.class; c = c.getSuperclass())
        {
            for (final Field field : c.getDeclaredFields())
            {
                if (ByteBuf.class.isAssignableFrom(field.getType()))
                {
                    try
                    {
                        field.setAccessible(true);
                        final ByteBuf buf = (ByteBuf) field.get(msg);
                        final byte[] out = new byte[buf.writerIndex()];
                        buf.getBytes(0, out);
                        return out;
                    }
                    catch (final IllegalAccessException e)
                    {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        throw new IllegalStateException("no payload buffer on " + msg.getClass());
    }

    private static boolean isRequestSystemMessage(final Object msg)
    {
        return msg.getClass().getSimpleName().equals("ColonyViewRequestSystemMessage");
    }

    /**
     * @return true when the message carries request-system data (a request-system message, or before CA-1 a colony view
     * whose request-manager flag is set).
     */
    private static boolean carriesRequestSystem(final AbstractClientPlayMessage msg, final RegistryAccess ra)
    {
        if (isRequestSystemMessage(msg))
        {
            return true;
        }
        return msg instanceof ColonyViewMessage && ColonyView.REQUEST_SYSTEM_IN_VIEW && skipToRequestSystem(payloadBytes(msg), ra).readBoolean();
    }

    /**
     * Before CA-1 the colony view carried the request manager: reads past the fields in front of it, in
     * {@link ColonyView#serializeNetworkData} order, and returns the buffer positioned on the request-manager flag.
     */
    private static RegistryFriendlyByteBuf skipToRequestSystem(final byte[] payload, final RegistryAccess ra)
    {
        final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload), ra);
        buf.readUtf();
        buf.readUtf();
        buf.readBlockPos();
        buf.readInt();
        buf.readInt();
        for (int i = buf.readInt(); i > 0; i--)
        {
            buf.readUtf();
        }
        for (int i = buf.readInt(); i > 0; i--)
        {
            buf.readBlockPos();
        }
        buf.readDouble();
        for (int i = buf.readInt(); i > 0; i--)
        {
            buf.readBlockPos();
            buf.readInt();
        }
        buf.readInt();
        buf.readUtf();
        buf.readUtf();
        for (int i = buf.readInt(); i > 0; i--)
        {
            buf.readUtf();
        }
        return buf;
    }

    // ------------------------------------------------------------------ client simulation

    /**
     * Stand-in remote colony for client-side request managers: a view colony, so the manager runs no server update steps.
     */
    private static IColony remoteColony(final IColony real)
    {
        final int id = real.getID();
        return (IColony) Proxy.newProxyInstance(IColony.class.getClassLoader(), new Class<?>[] {IColony.class}, (proxy, method, args) -> {
            switch (method.getName())
            {
                case "isRemote":
                    return true;
                case "getID":
                    return id;
                case "getCenter":
                    return real.getCenter();
                case "getDimension":
                    return real.getDimension();
                case "getName":
                    return real.getName();
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "RemoteColonyStandIn#" + id;
                default:
                    final Class<?> r = method.getReturnType();
                    if (r == boolean.class)
                    {
                        return false;
                    }
                    if (r == int.class || r == long.class || r == short.class || r == byte.class)
                    {
                        return r == long.class ? 0L : r == short.class ? (short) 0 : r == byte.class ? (byte) 0 : 0;
                    }
                    if (r == double.class)
                    {
                        return 0D;
                    }
                    if (r == float.class)
                    {
                        return 0F;
                    }
                    return null;
            }
        });
    }

    /**
     * What a client builds from the request-system data it received.
     */
    private static final class SimClient
    {
        final StandardRequestManager           manager;
        final RequestSystemViewSync.ClientMirror mirror = new RequestSystemViewSync.ClientMirror();
        int received;
        int refused;
        int cursor;

        SimClient(final IColony colony)
        {
            manager = new StandardRequestManager(remoteColony(colony));
        }

        /**
         * Applies every request-system payload recorded since the last call.
         */
        void catchUp(final Fixture f)
        {
            final RegistryAccess ra = f.level.registryAccess();
            for (; cursor < f.sent.size(); cursor++)
            {
                final AbstractClientPlayMessage msg = f.sent.get(cursor).msg();
                if (isRequestSystemMessage(msg))
                {
                    received++;
                    final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payloadBytes(msg)), ra);
                    if (!RequestSystemViewSync.apply(manager, mirror, buf))
                    {
                        refused++;
                    }
                }
                else if (msg instanceof ColonyViewMessage && ColonyView.REQUEST_SYSTEM_IN_VIEW)
                {
                    final RegistryFriendlyByteBuf buf = skipToRequestSystem(payloadBytes(msg), ra);
                    if (buf.readBoolean())
                    {
                        received++;
                        manager.deserialize(StandardFactoryController.getInstance(), buf);
                    }
                }
            }
        }
    }

    /**
     * The reference: a client view rebuilt from the full request-manager payload of the server's current state.
     */
    private static byte[] referenceCanonical(final Fixture f)
    {
        final RegistryAccess ra = f.level.registryAccess();
        final RegistryFriendlyByteBuf full = new RegistryFriendlyByteBuf(Unpooled.buffer(), ra);
        f.rm().serialize(StandardFactoryController.getInstance(), full);
        final StandardRequestManager reference = new StandardRequestManager(remoteColony(f.colony));
        reference.deserialize(StandardFactoryController.getInstance(), full);
        return RequestSystemViewSync.canonicalBytes(reference, ra);
    }

    private static int legacyFullSize(final Fixture f)
    {
        final RegistryFriendlyByteBuf full = new RegistryFriendlyByteBuf(Unpooled.buffer(), f.level.registryAccess());
        f.rm().serialize(StandardFactoryController.getInstance(), full);
        return full.writerIndex();
    }

    // ------------------------------------------------------------------ CA-1 bytes

    /**
     * CA-1: view message bytes per minute with 100 open requests, idle and under churn, against one full request-manager
     * payload per second.
     */
    public static void bytesPerMinute(final GameTestHelper helper)
    {
        final Fixture f = fixture(helper, "CA1 bytes");
        final RegistryAccess ra = f.level.registryAccess();
        final List<IToken<?>> open = new ArrayList<>();
        for (final Item item : requestItems(OPEN_REQUESTS, 0))
        {
            open.add(f.request(item, 1));
        }
        final List<Item> churnItems = requestItems(MINUTE / 20, OPEN_REQUESTS);
        after(f, SETTLE_TICKS, () -> {
            final long idleStart = f.now();
            after(f, MINUTE, () -> {
                final long churnStart = f.now();
                final int fullSize = legacyFullSize(f);
                final int[] step = {0};
                final Runnable[] churn = new Runnable[1];
                churn[0] = () -> {
                    if (step[0] < churnItems.size())
                    {
                        open.add(f.request(churnItems.get(step[0]), 1));
                        f.rm().updateRequestState(open.remove(0), RequestState.CANCELLED);
                        step[0]++;
                        after(f, 20, churn[0]);
                        return;
                    }
                    final long end = f.now();
                    final List<Sent> idle = f.sent.stream().filter(s -> s.tick() >= idleStart && s.tick() < churnStart).toList();
                    final List<Sent> busy = f.sent.stream().filter(s -> s.tick() >= churnStart && s.tick() < end).toList();
                    final long idleBytes = viewBytes(idle);
                    final long busyBytes = viewBytes(busy);
                    final long idleRs = idle.stream().filter(s -> carriesRequestSystem(s.msg(), ra)).count();
                    final long busyRs = busy.stream().filter(s -> carriesRequestSystem(s.msg(), ra)).count();
                    final long budget = 6L * fullSize;
                    final long old = 60L * fullSize;
                    Log.getLogger().info("CA1 bytes: open requests {}, full request-manager payload {} B; idle minute: {} B in {} msgs ({} with RS data);"
                                           + " churn minute ({} creates + cancels): {} B in {} msgs ({} with RS data); one full per second would be {} B/min,"
                                           + " budget {} B/min; churn ratio {}x",
                      open.size(), fullSize, idleBytes, idle.size(), idleRs, step[0], busyBytes, busy.size(), busyRs, old, budget,
                      busyBytes == 0 ? "inf" : String.format("%.1f", (double) old / busyBytes));
                    f.close();
                    helper.assertTrue(idleBytes <= budget, "idle minute sent " + idleBytes + " B of view messages, budget " + budget + " B (a tenth of one full RS payload per second)");
                    helper.assertTrue(busyBytes <= budget, "churn minute sent " + busyBytes + " B of view messages, budget " + budget + " B (a tenth of one full RS payload per second, "
                                                             + old + " B); " + busyRs + " messages carried request-system data");
                    helper.succeed();
                };
                churn[0].run();
            });
        });
    }

    private static long viewBytes(final List<Sent> sent)
    {
        return sent.stream().filter(s -> s.msg() instanceof ColonyViewMessage || isRequestSystemMessage(s.msg())).mapToLong(Sent::bytes).sum();
    }

    // ------------------------------------------------------------------ CA-1 equality

    /**
     * CA-1 correctness: after create, assign, complete, cancel, and a reconnect with changes made while away, the client
     * view equals the full-payload reference, and no delta was refused.
     */
    public static void clientEqualsFull(final GameTestHelper helper)
    {
        final Fixture f = fixture(helper, "CA1 equality", true);
        final SimClient client = new SimClient(f.colony);
        final List<String> log = new ArrayList<>();
        final Map<String, IToken<?>> t = new HashMap<>();
        final List<Map.Entry<String, Runnable>> steps = new ArrayList<>();
        steps.add(Map.entry("initial", () -> {}));
        steps.add(Map.entry("create", () -> t.put("a", f.request(Items.DIAMOND, 2))));
        steps.add(Map.entry("assign (reassign)", () -> f.rm().reassignRequest(t.get("a"), List.of())));
        steps.add(Map.entry("create x3", () -> {
            t.put("b", f.request(Items.EMERALD, 3));
            t.put("c", f.request(Items.GOLD_INGOT, 4));
            t.put("d", f.request(Items.IRON_INGOT, 5));
        }));
        steps.add(Map.entry("complete", () -> f.rm().updateRequestState(t.get("b"), RequestState.COMPLETED)));
        steps.add(Map.entry("cancel", () -> f.rm().updateRequestState(t.get("c"), RequestState.CANCELLED)));
        steps.add(Map.entry("reconnect", () -> {
            f.colony.getPackageManager().removeCloseSubscriber(f.player);
            t.put("e", f.request(Items.REDSTONE, 6));
            f.rm().updateRequestState(t.get("d"), RequestState.CANCELLED);
            f.rm().updateRequestState(t.get("a"), RequestState.CANCELLED);
            // Away for two sync rounds, then back.
            f.helper.runAfterDelay(45, () -> f.colony.getPackageManager().addCloseSubscriber(f.player));
        }));
        final int[] index = {0};
        final Runnable[] next = new Runnable[1];
        next[0] = () -> {
            final Map.Entry<String, Runnable> step = steps.get(index[0]);
            step.getValue().run();
            after(f, step.getKey().equals("reconnect") ? 45 + STEP_TICKS : STEP_TICKS, () -> {
                client.catchUp(f);
                final RegistryAccess ra = f.level.registryAccess();
                // The reference is what the full payload produced at the moment of the latest request-system send: the
                // request system also changes between sends without being marked dirty (retry delays count down), and
                // neither the full payload nor a delta is sent for that.
                final byte[] got = RequestSystemViewSync.canonicalBytes(client.manager, ra);
                final byte[] want = f.sent.stream().map(Sent::reference).filter(Objects::nonNull).reduce((a, b) -> b).orElse(new byte[0]);
                final int requests = f.rm().getRequestIdentitiesDataStore().getIdentities().size();
                final int clientRequests = client.manager.getRequestIdentitiesDataStore().getIdentities().size();
                log.add(step.getKey() + ": server requests " + requests + ", client " + clientRequests + ", canonical " + got.length + "/" + want.length + " B, equal "
                          + Arrays.equals(got, want) + ", payloads " + client.received + ", refused " + client.refused);
                if (client.received == 0 || !Arrays.equals(got, want) || client.refused > 0)
                {
                    Log.getLogger().info("CA1 equality: " + String.join(" | ", log));
                    f.close();
                    throw helper.assertionException("after '" + step.getKey() + "' the client request-system view differs from the full-payload reference: " + String.join(" | ", log));
                }
                index[0]++;
                if (index[0] == steps.size())
                {
                    Log.getLogger().info("CA1 equality: " + String.join(" | ", log));
                    f.close();
                    helper.succeed();
                    return;
                }
                next[0].run();
            });
        };
        after(f, STEP_TICKS, next[0]);
    }

    // ------------------------------------------------------------------ protocol

    /**
     * The wire format changed, so a client of the same mod version from before the change must be refused at login with a
     * reason, and a matching client accepted.
     */
    @SuppressWarnings("unchecked")
    public static void protocolMismatch(final GameTestHelper helper)
    {
        final String modVersion = ModList.get().getModContainerById(Constants.MOD_ID).orElseThrow().getModInfo().getVersion().toString();
        final List<NegotiableNetworkComponent> server = new ArrayList<>();
        try
        {
            final Field field = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
            field.setAccessible(true);
            final Map<ConnectionProtocol, Map<Identifier, PayloadRegistration<?>>> all = (Map<ConnectionProtocol, Map<Identifier, PayloadRegistration<?>>>) field.get(null);
            for (final PayloadRegistration<?> registration : all.getOrDefault(ConnectionProtocol.PLAY, Map.of()).values())
            {
                if (registration.id().getNamespace().equals(Constants.MOD_ID))
                {
                    server.add(new NegotiableNetworkComponent(registration));
                }
            }
        }
        catch (final ReflectiveOperationException e)
        {
            throw helper.assertionException("cannot read the payload registrations: " + e);
        }
        helper.assertTrue(!server.isEmpty(), "no minecolonies play payloads registered");
        final String serverVersion = server.get(0).version();
        final List<NegotiableNetworkComponent> legacy = server.stream()
          .map(c -> new NegotiableNetworkComponent(c.id(), modVersion, c.flow(), c.optional()))
          .toList();
        final NegotiationResult refused = NetworkComponentNegotiator.negotiate(server, legacy);
        final NegotiationResult accepted = NetworkComponentNegotiator.negotiate(server, server);
        Log.getLogger().info("CA1 protocol: mod version {}, channel version {}, {} payloads; pre-change client success={} reasons={}; matching client success={}",
          modVersion, serverVersion, server.size(), refused.success(), refused.failureReasons().size(), accepted.success());
        helper.assertTrue(!serverVersion.equals(modVersion), "channel version " + serverVersion + " was not bumped past the mod version: a pre-change client would connect");
        helper.assertTrue(!refused.success(), "a client announcing the pre-change channel version " + modVersion + " was accepted");
        helper.assertTrue(!refused.failureReasons().isEmpty(), "the refusal carries no reason for the player");
        helper.assertTrue(accepted.success(), "a matching client was refused: " + accepted.failureReasons());
        helper.succeed();
    }

    // ------------------------------------------------------------------ CA-3 citizen view

    /**
     * CA-3: a citizen whose inventory changes every second. Citizen view bytes must be under 30% of re-sending its full view
     * each time, and the payload the client rebuilds must equal the server's full view payload.
     */
    public static void citizenViewPatch(final GameTestHelper helper)
    {
        final Fixture f = fixture(helper, "CA3 citizen patch");
        final ICitizenData citizen = f.colony.getCitizenManager().createAndRegisterCivilianData();
        final RegistryAccess ra = f.level.registryAccess();
        after(f, SETTLE_TICKS, () -> {
            final long start = f.now();
            final int[] round = {0};
            final Runnable[] tick = new Runnable[1];
            tick[0] = () -> {
                if (round[0] < 30)
                {
                    citizen.getInventory().insertItem(round[0] % 9, new ItemStack(Items.BREAD, 1 + round[0] % 5), false);
                    round[0]++;
                    after(f, 20, tick[0]);
                    return;
                }
                after(f, 40, () -> {
                    final List<Sent> views = f.sent.stream().filter(s -> s.msg() instanceof ColonyViewCitizenViewMessage && citizenId(s.msg()) == citizen.getId()).toList();
                    final List<Sent> measured = views.stream().filter(s -> s.tick() >= start).toList();
                    final long sentBytes = measured.stream().mapToLong(Sent::bytes).sum();
                    // What the same messages cost as full views (the inventory holds a few bread stacks throughout).
                    final long[] fullBytes = {(long) measured.size() * encodedSize(new ColonyViewCitizenViewMessage(f.colony, citizen), ra)};
                    final byte[] client = rebuildCitizenPayload(views.stream().map(Sent::msg).toList());
                    final byte[] server = payloadBytes(new ColonyViewCitizenViewMessage(f.colony, citizen));
                    final byte[] serverFull = fullPayloadOf(server);
                    Log.getLogger().info("CA3 citizen patch: {} inventory changes, {} citizen views measured, {} B sent; as full views {} B ({}%); client payload {} B equals server {}",
                      round[0], measured.size(), sentBytes, fullBytes[0], fullBytes[0] == 0 ? 0 : 100 * sentBytes / fullBytes[0], client == null ? -1 : client.length,
                      client != null && Arrays.equals(client, serverFull));
                    f.close();
                    helper.assertTrue(client != null && Arrays.equals(client, serverFull), "the client's rebuilt citizen view payload differs from the server's");
                    helper.assertTrue(!measured.isEmpty(), "no citizen view was sent for " + round[0] + " inventory changes");
                    helper.assertTrue(sentBytes * 10 < fullBytes[0] * 3, "citizen views sent " + sentBytes + " B, full views would be " + fullBytes[0] + " B; budget 30%");
                    helper.succeed();
                });
            };
            tick[0].run();
        });
    }

    private static int citizenId(final Object msg)
    {
        try
        {
            final Field id = ColonyViewCitizenViewMessage.class.getDeclaredField("citizenId");
            id.setAccessible(true);
            return id.getInt(msg);
        }
        catch (final ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Runs the client's citizen payload resolution (CA-3: full or patch against the previous payload) over the messages.
     * Before CA-3 every payload is the full view.
     */
    private static byte[] rebuildCitizenPayload(final List<AbstractClientPlayMessage> messages)
    {
        Method resolve = null;
        try
        {
            resolve = ColonyViewCitizenViewMessage.class.getMethod("resolvePayload", byte[].class, byte[].class);
        }
        catch (final NoSuchMethodException e)
        {
            // Before CA-3.
        }
        byte[] held = null;
        for (final AbstractClientPlayMessage msg : messages)
        {
            final byte[] payload = payloadBytes(msg);
            if (resolve == null)
            {
                held = payload;
                continue;
            }
            try
            {
                held = (byte[]) resolve.invoke(null, held, payload);
            }
            catch (final ReflectiveOperationException e)
            {
                throw new IllegalStateException(e);
            }
            if (held == null)
            {
                return null;
            }
        }
        return held;
    }

    /**
     * The full view bytes inside a freshly built (full) citizen view message payload.
     */
    private static byte[] fullPayloadOf(final byte[] payload)
    {
        try
        {
            final Method resolve = ColonyViewCitizenViewMessage.class.getMethod("resolvePayload", byte[].class, byte[].class);
            return (byte[]) resolve.invoke(null, null, payload);
        }
        catch (final NoSuchMethodException e)
        {
            return payload;
        }
        catch (final ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
