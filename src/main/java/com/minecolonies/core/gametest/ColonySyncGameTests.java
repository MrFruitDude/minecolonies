package com.minecolonies.core.gametest;

import com.ldtteam.common.network.AbstractClientPlayMessage;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import com.ldtteam.common.network.PlayMessageType;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildingextensions.IBuildingExtension;
import com.minecolonies.api.colony.buildingextensions.registry.BuildingExtensionRegistries;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.happiness.ExpirationBasedHappinessModifier;
import com.minecolonies.api.entity.citizen.happiness.StaticHappinessSupplier;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.ColonyView;
import com.minecolonies.core.colony.buildingextensions.registry.BuildingExtensionDataManager;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;
import com.minecolonies.core.colony.crafting.CustomRecipeManagerMessage;
import com.minecolonies.core.colony.managers.ColonyPackageManager;
import com.minecolonies.core.network.messages.PermissionsMessage;
import com.minecolonies.core.network.messages.client.GlobalQuestSyncMessage;
import com.minecolonies.core.network.messages.client.UpdateClientWithCompatibilityMessage;
import com.minecolonies.core.network.messages.client.colony.*;
import com.minecolonies.core.network.messages.server.colony.building.fields.AssignFieldMessage;
import com.minecolonies.core.research.GlobalResearchTreeMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static com.minecolonies.api.util.constant.HappinessConstants.DAMAGE;

/**
 * CA-4 + CA-2 network sync GameTests.
 * <ul>
 * <li>{@code sync_view_message_no_tail}: each of the 14 messages that carry a pre-serialized payload buffer sends exactly
 * the bytes written to it, not the zero tail of its backing array.</li>
 * <li>{@code sync_citizen_dirty_not_colony_dirty}: one citizen's inventory change re-sends that citizen's view and not the
 * whole colony view.</li>
 * <li>{@code sync_citizen_derived_refresh}: the colony view's citizen-derived field (overall happiness) still reaches the
 * client within the refresh window after only a citizen changed.</li>
 * </ul>
 * Messages are counted through {@link ColonyPackageManager#viewSendRecorder}, since the mock player keeps nothing it receives.
 */
public final class ColonySyncGameTests
{
    /**
     * Quiet ticks (no view message at all) before a measurement starts. Longer than the colony view's citizen-derived
     * refresh window (200 ticks), so a refresh owed for the fixture's own setup has gone out before.
     */
    private static final int QUIET_TICKS = 250;

    /**
     * Ticks measured after the citizen change. The change is sent after at most two 20-tick sync rounds.
     */
    private static final int MEASURE_TICKS = 60;

    /**
     * Upper bound for the citizen-derived colony view refresh to arrive (the 200-tick window plus a sync round and slack).
     */
    private static final int REFRESH_BOUND_TICKS = 300;

    private ColonySyncGameTests()
    {
    }

    // ------------------------------------------------------------------ CA-4

    /**
     * CA-4: encodes each payload-carrying message twice and decodes it again; the decoded payload must be exactly the
     * bytes written to the sender's buffer. Before the fix the whole backing array went out, zero tail included.
     */
    public static void viewMessageNoTail(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final RegistryAccess ra = level.registryAccess();
        final Colony colony = (Colony) MinecoloniesGameTests.foundGameTestColony(helper, "CA4 no tail");
        final ICitizenData citizen = colony.getCitizenManager().createAndRegisterCivilianData();
        final IBuilding townHall = colony.getServerBuildingManager().getTownHall();
        final IBuildingExtension farm = BuildingExtensionRegistries.farmField.get().produceExtension(helper.absolutePos(new BlockPos(12, 1, 12)));
        final List<String> failures = new ArrayList<>();
        final List<String> report = new ArrayList<>();
        int checked = 0;

        final RegistryFriendlyByteBuf colonyBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), ra);
        ColonyView.serializeNetworkData(colony, colonyBuf, true);
        checked += check("ColonyViewMessage", new ColonyViewMessage(colony, colonyBuf, true), ra, failures, report);
        checked += check("ColonyViewCitizenViewMessage", new ColonyViewCitizenViewMessage(colony, citizen), ra, failures, report);
        checked += check("ColonyViewBuildingViewMessage", new ColonyViewBuildingViewMessage(townHall), ra, failures, report);
        checked += check("ColonyViewWorkOrderMessage", new ColonyViewWorkOrderMessage(colony, List.of()), ra, failures, report);
        checked += check("ColonyViewAnimalViewDataMessage", new ColonyViewAnimalViewDataMessage(colony, Set.of(), false), ra, failures, report);
        checked += check("ColonyVisitorViewDataMessage", new ColonyVisitorViewDataMessage(colony, Set.of(), false), ra, failures, report);
        checked += check("PermissionsMessage.View", new PermissionsMessage.View(colony, colony.getPermissions().getRankOwner()), ra, failures, report);
        checked += check("UpdateClientWithCompatibilityMessage", new UpdateClientWithCompatibilityMessage(ra), ra, failures, report);
        checked += check("GlobalResearchTreeMessage", new GlobalResearchTreeMessage(bytes(ra, 37)), ra, failures, report);
        checked += check("CustomRecipeManagerMessage", new CustomRecipeManagerMessage(bytes(ra, 41)), ra, failures, report);
        checked += check("GlobalQuestSyncMessage", new GlobalQuestSyncMessage(bytes(ra, 43)), ra, failures, report);
        checked += check("GlobalDiseaseSyncMessage", new GlobalDiseaseSyncMessage(bytes(ra, 47)), ra, failures, report);

        // Server-bound: only the client builds it from a building view, so decode one and give it a real field payload.
        final RegistryFriendlyByteBuf assignIn = new RegistryFriendlyByteBuf(Unpooled.buffer(), ra);
        assignIn.writeUtf(colony.getDimension().identifier().toString());
        assignIn.writeInt(colony.getID());
        assignIn.writeBlockPos(townHall.getID());
        assignIn.writeBoolean(true);
        assignIn.writeInt(1);
        assignIn.writeByteArray(new byte[] {1, 2, 3});
        final CustomPacketPayload assign = codec(AssignFieldMessage.class).decode(assignIn);
        setPayload(assign, BuildingExtensionDataManager.extensionToBuffer(farm, ra));
        checked += check("AssignFieldMessage", assign, ra, failures, report);

        // No stored payload: one buffer per extension, built while encoding.
        {
            final ColonyViewBuildingExtensionsUpdateMessage msg = new ColonyViewBuildingExtensionsUpdateMessage(colony, List.of(farm));
            final int payload = BuildingExtensionDataManager.extensionToBuffer(farm, ra).writerIndex();
            final RegistryFriendlyByteBuf header = new RegistryFriendlyByteBuf(Unpooled.buffer(), ra);
            header.writeInt(colony.getID());
            header.writeUtf(colony.getDimension().identifier().toString());
            header.writeInt(1);
            final int expected = header.writerIndex() + VarInt.getByteSize(payload) + payload;
            final RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), ra);
            codec(msg.getClass()).encode(out, msg);
            report.add("ColonyViewBuildingExtensionsUpdateMessage encoded=" + out.writerIndex() + " expected=" + expected);
            if (out.writerIndex() != expected)
            {
                failures.add("ColonyViewBuildingExtensionsUpdateMessage: encoded " + out.writerIndex() + " bytes, header + payload is " + expected);
            }
            else
            {
                codec(msg.getClass()).decode(out);
                if (out.isReadable())
                {
                    failures.add("ColonyViewBuildingExtensionsUpdateMessage: " + out.readableBytes() + " bytes left after decode");
                }
            }
            checked++;
        }

        Log.getLogger().info("CA4 no tail: " + String.join(" | ", report));
        helper.assertTrue(checked == 14, "expected 14 message types, checked " + checked);
        final long failedTypes = failures.stream().map(f -> f.split(" ")[0]).distinct().count();
        helper.assertTrue(failures.isEmpty(), failedTypes + " of 14 messages send more than the written bytes: " + String.join(" | ", failures));
        helper.succeed();
    }

    /**
     * Encodes {@code msg} twice (a message reaching two players is encoded once per player), decoding each result: the
     * decoded payload must be the sender's written bytes, and nothing may be left over.
     */
    private static int check(final String name, final CustomPacketPayload msg, final RegistryAccess ra, final List<String> failures, final List<String> report)
    {
        ByteBuf src = payload(msg);
        if (src.capacity() == src.writerIndex())
        {
            // The buffer is exactly full, so it has no tail to leak; pad it so the encoding is actually tested.
            final ByteBuf padded = Unpooled.buffer(src.writerIndex() * 2 + 16);
            padded.writeBytes(src, 0, src.writerIndex());
            setPayload(msg, new RegistryFriendlyByteBuf(new FriendlyByteBuf(padded), ra));
            src = payload(msg);
        }
        final int written = src.writerIndex();
        final StreamCodec<RegistryFriendlyByteBuf, CustomPacketPayload> codec = codec(msg.getClass());
        for (int round = 1; round <= 2; round++)
        {
            final RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), ra);
            codec.encode(out, msg);
            final int encoded = out.writerIndex();
            final ByteBuf got = payload(codec.decode(out));
            if (round == 1)
            {
                report.add(name + " written=" + written + " capacity=" + src.capacity() + " sent=" + got.writerIndex() + " encoded=" + encoded);
            }
            if (out.isReadable())
            {
                failures.add(name + " encode " + round + ": " + out.readableBytes() + " bytes left after decode");
            }
            if (got.writerIndex() != written)
            {
                failures.add(name + " encode " + round + ": sent " + got.writerIndex() + " payload bytes, " + written + " written (array " + src.capacity() + ")");
            }
            else if (!ByteBufUtil.equals(got, 0, src, 0, written))
            {
                failures.add(name + " encode " + round + ": payload bytes differ from the written bytes");
            }
        }
        return 1;
    }

    private static RegistryFriendlyByteBuf bytes(final RegistryAccess ra, final int n)
    {
        final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), ra);
        for (int i = 0; i < n; i++)
        {
            buf.writeByte(i * 7 + 1);
        }
        return buf;
    }

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

    private static Field payloadField(final Object msg)
    {
        for (Class<?> c = msg.getClass(); c != null && c != Object.class; c = c.getSuperclass())
        {
            for (final Field f : c.getDeclaredFields())
            {
                if (ByteBuf.class.isAssignableFrom(f.getType()))
                {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        throw new IllegalStateException("no payload buffer field on " + msg.getClass());
    }

    private static ByteBuf payload(final Object msg)
    {
        try
        {
            return (ByteBuf) payloadField(msg).get(msg);
        }
        catch (final IllegalAccessException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static void setPayload(final Object msg, final RegistryFriendlyByteBuf buf)
    {
        try
        {
            payloadField(msg).set(msg, buf);
        }
        catch (final IllegalAccessException e)
        {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ CA-2

    /**
     * One recorded view message to the test player.
     *
     * @param tick      game time it was sent.
     * @param colonyView true for a {@link ColonyViewMessage}.
     * @param citizenId the citizen of a {@link ColonyViewCitizenViewMessage}, else -1.
     * @param happiness the overall happiness a colony view carries, else NaN.
     */
    private record Sent(long tick, boolean colonyView, int citizenId, double happiness)
    {
    }

    private record SyncFixture(ServerLevel level, Colony colony, ICitizenData citizen, ServerPlayer player, List<Sent> sent)
    {
        long now()
        {
            return level.getGameTime();
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

    /**
     * Colony with one citizen (data only, no entity, so nothing but the test changes it) and a connected mock player as
     * close subscriber. Runs {@code body} once the colony is active and no view message went out for {@link #QUIET_TICKS}.
     */
    private static void syncFixture(final GameTestHelper helper, final String name, final Consumer<SyncFixture> body)
    {
        final ServerLevel level = helper.getLevel();
        final Colony colony = (Colony) MinecoloniesGameTests.foundGameTestColony(helper, name);
        final BlockPos anchor = colony.getCenter();
        final ServerPlayer player = MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper);
        player.teleportTo(anchor.getX() + 0.5D, anchor.getY() + 1, anchor.getZ() + 0.5D);
        final List<Sent> sent = new ArrayList<>();
        ColonyPackageManager.viewSendRecorder = (to, msg) -> {
            if (to == player)
            {
                sent.add(record(level.getGameTime(), msg));
            }
        };
        colony.getPackageManager().addCloseSubscriber(player);
        // No natural move-ins: another citizen would change overall happiness and send views of its own.
        final BoolSetting moveIn = colony.getSettings().getSetting(BuildingTownHall.MOVE_IN);
        if (moveIn.getValue())
        {
            moveIn.trigger();
        }
        final ICitizenData citizen = colony.getCitizenManager().createAndRegisterCivilianData();
        final SyncFixture fixture = new SyncFixture(level, colony, citizen, player, sent);
        final long start = level.getGameTime();
        final Runnable[] waitQuiet = new Runnable[1];
        waitQuiet[0] = () -> {
            final long now = level.getGameTime();
            final long last = sent.isEmpty() ? start : sent.get(sent.size() - 1).tick();
            if (colony.isActive() && !sent.isEmpty() && now - last >= QUIET_TICKS)
            {
                body.accept(fixture);
                return;
            }
            if (now - start > 2000)
            {
                fixture.close();
                throw helper.assertionException("fixture: colony never went quiet (active=" + colony.isActive() + ", " + sent.size() + " messages, last at +"
                                                  + (last - start) + "): " + sent.subList(Math.max(0, sent.size() - 6), sent.size()));
            }
            helper.runAfterDelay(1, () -> waitQuiet[0].run());
        };
        waitQuiet[0].run();
    }

    private static Sent record(final long tick, final AbstractClientPlayMessage msg)
    {
        if (msg instanceof ColonyViewMessage)
        {
            return new Sent(tick, true, -1, readHappiness(payload(msg)));
        }
        if (msg instanceof ColonyViewCitizenViewMessage)
        {
            try
            {
                final Field id = ColonyViewCitizenViewMessage.class.getDeclaredField("citizenId");
                id.setAccessible(true);
                return new Sent(tick, false, id.getInt(msg), Double.NaN);
            }
            catch (final ReflectiveOperationException e)
            {
                throw new IllegalStateException(e);
            }
        }
        return new Sent(tick, false, -1, Double.NaN);
    }

    /**
     * Reads the overall happiness out of a colony view payload, in {@link ColonyView#serializeNetworkData} order.
     */
    private static double readHappiness(final ByteBuf payload)
    {
        final FriendlyByteBuf buf = new FriendlyByteBuf(payload.duplicate().readerIndex(0));
        buf.readUtf();
        buf.readUtf();
        buf.readBlockPos();
        buf.readInt();
        buf.readInt();
        final int freeBlocks = buf.readInt();
        for (int i = 0; i < freeBlocks; i++)
        {
            buf.readUtf();
        }
        final int freePositions = buf.readInt();
        for (int i = 0; i < freePositions; i++)
        {
            buf.readBlockPos();
        }
        return buf.readDouble();
    }

    /**
     * CA-2: a citizen's inventory change re-sends that citizen's view only. Before the fix the dirty citizen marked the
     * colony dirty, so the full colony view went out with it.
     */
    public static void citizenDirtyNotColonyDirty(final GameTestHelper helper)
    {
        syncFixture(helper, "CA2 citizen dirty", f -> {
            final long t0 = f.now();
            f.citizen().getInventory().insertItem(0, new ItemStack(Items.BREAD, 3), false);
            helper.runAfterDelay(MEASURE_TICKS, () -> {
                final List<Sent> after = f.since(t0);
                final long citizenViews = after.stream().filter(s -> s.citizenId() == f.citizen().getId()).count();
                final long colonyViews = after.stream().filter(Sent::colonyView).count();
                Log.getLogger().info("CA2 citizen dirty: after inventory change, " + MEASURE_TICKS + " ticks: citizenViews=" + citizenViews + " colonyViews=" + colonyViews
                                       + " " + after);
                f.close();
                helper.assertTrue(citizenViews == 1, "expected one citizen view for citizen " + f.citizen().getId() + ", got " + citizenViews + ": " + after);
                helper.assertTrue(colonyViews == 0, "a citizen inventory change re-sent the whole colony view " + colonyViews + " time(s): " + after);
                helper.succeed();
            });
        });
    }

    /**
     * CA-2 correctness: the colony view's overall happiness is derived from citizens. When only a citizen changes, the new
     * value must still reach the client, within the refresh window.
     */
    public static void citizenDerivedRefresh(final GameTestHelper helper)
    {
        syncFixture(helper, "CA2 derived refresh", f -> {
            final double before = f.colony().getOverallHappiness();
            f.citizen().getCitizenHappinessHandler().addModifier(new ExpirationBasedHappinessModifier(DAMAGE, 2.0, new StaticHappinessSupplier(0.0), 1));
            f.citizen().getInventory().insertItem(0, new ItemStack(Items.BREAD, 1), false);
            final double target = f.colony().getOverallHappiness();
            if (Double.compare(before, target) == 0)
            {
                f.close();
                throw helper.assertionException("fixture: the damage modifier did not change overall happiness (" + before + ")");
            }
            final long t0 = f.now();
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                final List<Sent> after = f.since(t0);
                final Sent hit = after.stream().filter(s -> s.colonyView() && Double.compare(s.happiness(), target) == 0).findFirst().orElse(null);
                if (hit != null)
                {
                    Log.getLogger().info("CA2 derived refresh: happiness " + before + " -> " + target + " reached the client after " + (hit.tick() - t0) + " ticks; " + after);
                    f.close();
                    helper.succeed();
                    return;
                }
                if (f.now() - t0 > REFRESH_BOUND_TICKS)
                {
                    f.close();
                    throw helper.assertionException("overall happiness " + before + " -> " + target + " never reached the client within " + REFRESH_BOUND_TICKS
                                                      + " ticks: " + after);
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            poll[0].run();
        });
    }
}
