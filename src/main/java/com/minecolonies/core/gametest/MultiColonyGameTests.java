package com.minecolonies.core.gametest;

import com.ldtteam.common.network.PlayMessageType;
import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ColonyState;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.OwnedColonySummary;
import com.minecolonies.api.eventbus.events.colony.ColonyFoundingEvent;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.ColonyLimits;
import com.minecolonies.core.colony.SelectedColonySavedData;
import com.minecolonies.core.commands.colonycommands.CommandChangeOwner;
import com.minecolonies.core.network.messages.client.colony.OwnedColoniesMessage;
import com.minecolonies.core.network.messages.server.CreateColonyMessage;
import com.minecolonies.core.network.messages.server.colony.ColonyAbandonOwnMessage;
import com.minecolonies.core.network.messages.server.colony.ColonyDeleteOwnMessage;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.minecolonies.core.gametest.MinecoloniesGameTests.foundGameTestColony;
import static com.minecolonies.core.gametest.MinecoloniesGameTests.makeConnectedSurvivalPlayer;

/**
 * P3a-MC: several colonies per player. A player founds colonies through the production founding packet, with
 * {@code colonies.maxPerPlayer} raised (the tests override it in code; 1, the default, is covered too).
 */
public final class MultiColonyGameTests
{
    private MultiColonyGameTests()
    {
    }

    private static final String PACK = "Minecolonies Original";

    /**
     * Relative x of the second and third town hall. minColonyDistance is 8 chunks (128 blocks) and the claims are 4 chunks.
     */
    private static final int SECOND_X = 200;
    private static final int THIRD_X  = 400;

    /**
     * The production founding packet, run as a player.
     */
    private static final class FoundingMessage extends CreateColonyMessage
    {
        private FoundingMessage(final BlockPos townHall, final String name)
        {
            super(townHall, false, name, PACK, "fundamentals/townhall1.blueprint");
        }

        private void execute(final ServerPlayer player)
        {
            onExecute(null, player);
        }
    }

    /**
     * Founding vetoes and the events seen, for {@link #founding_event}.
     */
    private static final AtomicReference<UUID>        VETO_PLAYER = new AtomicReference<>();
    private static final List<ColonyFoundingEvent>    SEEN        = new ArrayList<>();
    private static boolean listenerRegistered = false;

    private static void registerListener()
    {
        if (listenerRegistered)
        {
            return;
        }
        listenerRegistered = true;
        IMinecoloniesAPI.getInstance().getEventBus().subscribe(ColonyFoundingEvent.class, event -> {
            if (!event.getPlayer().getUUID().equals(VETO_PLAYER.get()))
            {
                return;
            }
            SEEN.add(event);
            if (event.getOwnedColonies() >= 1)
            {
                event.cancel(Component.literal("G-MC test veto"));
            }
        });
    }

    /**
     * Puts a town hall hut on a flat patch at the relative position and loads its chunk.
     *
     * @return the absolute position.
     */
    private static BlockPos placeTownHall(final GameTestHelper helper, final int relX, final int relZ)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos pos = helper.absolutePos(new BlockPos(relX, 1, relZ));
        level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
        for (int x = -2; x <= 2; x++)
        {
            for (int z = -2; z <= 2; z++)
            {
                level.setBlock(pos.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        level.setBlock(pos, ModBlocks.blockHutTownHall.defaultBlockState(), 3);
        final TileEntityColonyBuilding hut = (TileEntityColonyBuilding) level.getBlockEntity(pos);
        helper.assertTrue(hut != null, "town hall at " + pos + " has no tile entity");
        hut.setPackName(PACK);
        hut.setBlueprintPath("fundamentals/townhall1.blueprint");
        return pos;
    }

    private static void unforce(final ServerLevel level, final BlockPos... positions)
    {
        for (final BlockPos pos : positions)
        {
            level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, false);
        }
    }

    /**
     * Founds a colony as the player through the production packet and returns it, null if none was founded.
     */
    private static IColony found(final GameTestHelper helper, final ServerPlayer player, final BlockPos townHall, final String name)
    {
        new FoundingMessage(townHall, name).execute(player);
        final IColony closest = IColonyManager.getInstance().getClosestColony(helper.getLevel(), townHall);
        return closest != null && closest.getName().equals(name) ? closest : null;
    }

    private static List<Integer> ids(final List<IColony> colonies)
    {
        return colonies.stream().map(IColony::getID).toList();
    }

    /**
     * The colonies owned by the player the slow way, to compare with the owner index.
     */
    private static List<Integer> scan(final ServerLevel level, final UUID owner)
    {
        final List<IColony> found = new ArrayList<>();
        for (final IColony colony : IColonyManager.getInstance().getAllColonies())
        {
            if (owner.equals(colony.getPermissions().getOwner()))
            {
                found.add(colony);
            }
        }
        found.sort(java.util.Comparator.comparingLong(IColony::getFoundedTime));
        return ids(found);
    }

    // ------------------------------------------------------------------------------------------------------------

    /**
     * maxPerPlayer = 2: two colonies are founded through the production packet, the third is refused; the owner
     * index, the selected colony and the legacy lookup behave.
     */
    public static void founding_and_selection(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColonyManager manager = IColonyManager.getInstance();
        ColonyLimits.overrideMaxPerPlayer(2);
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
        final UUID uuid = player.getUUID();
        helper.assertTrue(manager.getMaxColoniesPerPlayer() == 2, "limit override not read: " + manager.getMaxColoniesPerPlayer());
        helper.assertTrue(manager.getIColoniesByOwner(uuid).isEmpty(), "a new player owns colonies");
        helper.assertTrue(manager.getSelectedColony(uuid) == null, "a player without colonies has a selected colony");
        helper.assertTrue(manager.getIColonyByOwner(level, uuid) == null, "legacy lookup finds a colony for a player without one");

        final BlockPos hallA = placeTownHall(helper, 2, 2);
        final BlockPos hallB = placeTownHall(helper, SECOND_X, 2);
        final BlockPos hallC = placeTownHall(helper, THIRD_X, 2);
        final IColony a = found(helper, player, hallA, "MC first");
        helper.assertTrue(a != null, "first founding made no colony");
        final IColony b = found(helper, player, hallB, "MC second");
        helper.assertTrue(b != null, "second founding made no colony with maxPerPlayer=2");
        final IColony c = found(helper, player, hallC, "MC third");
        helper.assertTrue(c == null, "third founding made a colony with maxPerPlayer=2: " + c);
        helper.assertTrue(a.getID() != b.getID() || !a.getDimension().equals(b.getDimension()), "both colonies are the same colony");

        helper.assertTrue(a.getFoundedTime() > 0 && a.getFoundedTime() < b.getFoundedTime(),
          "founded times not increasing: " + a.getFoundedTime() + ", " + b.getFoundedTime());
        helper.assertTrue(ids(manager.getIColoniesByOwner(uuid)).equals(List.of(a.getID(), b.getID())),
          "owned colonies are " + ids(manager.getIColoniesByOwner(uuid)) + ", expected the first two oldest first");
        helper.assertTrue(ids(manager.getIColoniesByOwner(uuid)).equals(scan(level, uuid)), "owner index differs from a scan: " + scan(level, uuid));
        helper.assertTrue(manager.checkFounding(level, hallC, player, true).isPresent(), "checkFounding allows a third colony");

        // Default selection is the oldest, and the legacy lookup returns the selected one.
        helper.assertTrue(manager.getSelectedColony(uuid) == a, "default selection is not the oldest colony");
        helper.assertTrue(manager.getIColonyByOwner(level, player) == a && manager.getIColonyByOwner(level, uuid) == a,
          "legacy lookup does not return the selected (oldest) colony");

        helper.assertTrue(manager.setSelectedColony(uuid, b.getDimension(), b.getID()), "selecting the second colony failed");
        helper.assertTrue(manager.getSelectedColony(uuid) == b, "selection did not change");
        helper.assertTrue(manager.getIColonyByOwner(level, uuid) == b, "legacy lookup ignores the selection");
        helper.assertTrue(!manager.setSelectedColony(uuid, a.getDimension(), 9999), "selected a colony that does not exist");
        helper.assertTrue(!manager.setSelectedColony(UUID.randomUUID(), a.getDimension(), a.getID()), "selected someone else's colony");
        helper.assertTrue(manager.getSelectedColony(uuid) == b, "a refused selection changed the selection");

        // Persisted: the saved data holds it and survives its codec.
        final SelectedColonySavedData saved = SelectedColonySavedData.get(level.getServer());
        helper.assertTrue(saved.get(uuid) != null && saved.get(uuid).id() == b.getID(), "selection not in the saved data: " + saved.get(uuid));
        final SelectedColonySavedData roundTrip = SelectedColonySavedData.CODEC.parse(JsonOps.INSTANCE,
          SelectedColonySavedData.CODEC.encodeStart(JsonOps.INSTANCE, saved).getOrThrow()).getOrThrow();
        helper.assertTrue(roundTrip.get(uuid).equals(saved.get(uuid)), "saved selection lost in a save/load round trip: " + roundTrip.get(uuid));

        // Owner change moves the colony in the index; abandoning too.
        final ServerPlayer other = makeConnectedSurvivalPlayer(helper);
        helper.assertTrue(!CommandChangeOwner.wouldExceedLimit(a, other.getUUID()), "setowner to a player with no colony would exceed the limit");
        a.getPermissions().setOwner(other);
        helper.assertTrue(ids(manager.getIColoniesByOwner(uuid)).equals(List.of(b.getID())), "old owner still owns the colony: " + ids(manager.getIColoniesByOwner(uuid)));
        helper.assertTrue(ids(manager.getIColoniesByOwner(other.getUUID())).equals(List.of(a.getID())), "new owner does not own the colony");
        helper.assertTrue(ids(manager.getIColoniesByOwner(uuid)).equals(scan(level, uuid)) && ids(manager.getIColoniesByOwner(other.getUUID())).equals(scan(level, other.getUUID())),
          "owner index differs from a scan after setOwner");
        // The other player gets a second colony, and the setowner limit holds at two.
        final IColony cc = found(helper, other, hallC, "MC other second");
        helper.assertTrue(cc != null, "other player could not found a second colony");
        helper.assertTrue(CommandChangeOwner.wouldExceedLimit(b, other.getUUID()), "setowner past the limit is not refused");
        helper.assertTrue(!CommandChangeOwner.wouldExceedLimit(a, other.getUUID()), "setowner of an already owned colony counts twice");

        unforce(level, hallA, hallB, hallC);
        helper.succeed();
    }

    /**
     * The default limit (1) behaves as before: the second founding is refused, the legacy lookup returns the colony.
     */
    public static void default_limit_one(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColonyManager manager = IColonyManager.getInstance();
        ColonyLimits.overrideMaxPerPlayer(null);
        helper.assertTrue(manager.getMaxColoniesPerPlayer() == 1, "default limit is " + manager.getMaxColoniesPerPlayer());
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
        final BlockPos hallA = placeTownHall(helper, 2, 2);
        final BlockPos hallB = placeTownHall(helper, SECOND_X, 2);
        final IColony a = found(helper, player, hallA, "MC only");
        helper.assertTrue(a != null, "first founding made no colony");
        helper.assertTrue(found(helper, player, hallB, "MC refused") == null, "second founding made a colony with the default limit");
        helper.assertTrue(manager.getIColoniesByOwner(player.getUUID()).size() == 1, "player owns " + manager.getIColoniesByOwner(player.getUUID()).size());
        helper.assertTrue(manager.getIColonyByOwner(level, player) == a && manager.getSelectedColony(player.getUUID()) == a, "legacy lookup lost the only colony");
        helper.assertTrue(manager.checkFounding(level, hallB, player, false).isPresent(), "checkFounding allows a second colony with limit 1");
        unforce(level, hallA, hallB);
        helper.succeed();
    }

    /**
     * ColonyFoundingEvent: seen before the colony exists with the right data, a cancel stops the founding and carries
     * its reason, a preview is marked as one.
     */
    public static void founding_event(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColonyManager manager = IColonyManager.getInstance();
        ColonyLimits.overrideMaxPerPlayer(3);
        registerListener();
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
        VETO_PLAYER.set(player.getUUID());
        SEEN.clear();
        try
        {
            final BlockPos hallA = placeTownHall(helper, 2, 2);
            final BlockPos hallB = placeTownHall(helper, SECOND_X, 2);
            final IColony a = found(helper, player, hallA, "MC event first");
            helper.assertTrue(a != null, "the first founding was refused: events " + SEEN.size());
            helper.assertTrue(SEEN.size() == 1 && SEEN.get(0).getOwnedColonies() == 0 && !SEEN.get(0).isPreview()
                                && SEEN.get(0).getPosition().equals(hallA) && SEEN.get(0).getPlayer() == player && SEEN.get(0).getLevel() == level,
              "first founding event wrong: " + SEEN.size());

            helper.assertTrue(found(helper, player, hallB, "MC event vetoed") == null, "a cancelled founding made a colony");
            helper.assertTrue(manager.getIColoniesByOwner(player.getUUID()).size() == 1, "colonies after the veto: " + manager.getIColoniesByOwner(player.getUUID()).size());
            helper.assertTrue(SEEN.size() == 2 && SEEN.get(1).getOwnedColonies() == 1 && SEEN.get(1).isCanceled(), "veto not seen: " + SEEN.size());

            final Optional<Component> reason = manager.checkFounding(level, hallB, player, true);
            helper.assertTrue(reason.isPresent() && reason.get().getString().equals("G-MC test veto"), "veto reason lost: " + reason);
            helper.assertTrue(SEEN.size() == 3 && SEEN.get(2).isPreview(), "preview not marked");

            VETO_PLAYER.set(null);
            helper.assertTrue(found(helper, player, hallB, "MC event allowed") != null, "founding refused with no listener acting");
            helper.assertTrue(manager.getIColoniesByOwner(player.getUUID()).size() == 2, "colonies after the allowed founding");
            unforce(level, hallA, hallB);
        }
        finally
        {
            VETO_PLAYER.set(null);
        }
        helper.succeed();
    }

    /**
     * Abandon and delete act on the colony named, else the one the player stands in, else the selected one, and only on
     * colonies the player owns.
     */
    public static void abandon_delete_right_one(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColonyManager manager = IColonyManager.getInstance();
        ColonyLimits.overrideMaxPerPlayer(3);
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
        final UUID uuid = player.getUUID();
        final BlockPos hallA = placeTownHall(helper, 2, 2);
        final BlockPos hallB = placeTownHall(helper, SECOND_X, 2);
        final BlockPos hallC = placeTownHall(helper, THIRD_X, 2);
        final IColony a = found(helper, player, hallA, "MC del A");
        final IColony b = found(helper, player, hallB, "MC del B");
        final IColony c = found(helper, player, hallC, "MC del C");
        helper.assertTrue(a != null && b != null && c != null, "could not found three colonies");
        final int aId = a.getID();
        final int bId = b.getID();
        final int cId = c.getID();
        final ServerPlayer stranger = makeConnectedSurvivalPlayer(helper);

        // Not the player's colony: nothing happens.
        helper.assertTrue(ColonyDeleteOwnMessage.execute(stranger, a.getDimension(), aId) == -1, "a stranger deleted a colony");
        helper.assertTrue(ColonyAbandonOwnMessage.execute(stranger, a.getDimension(), aId) == -1, "a stranger abandoned a colony");
        helper.assertTrue(manager.getIColoniesByOwner(uuid).size() == 3, "colonies after the stranger's attempts");

        // Select B, delete A explicitly: A goes, B stays selected.
        manager.setSelectedColony(uuid, b.getDimension(), bId);
        helper.assertTrue(ColonyDeleteOwnMessage.execute(player, a.getDimension(), aId) == aId, "explicit delete returned wrong");
        helper.assertTrue(manager.getColonyByDimension(aId, a.getDimension()) == null, "deleted colony still exists");
        helper.assertTrue(ids(manager.getIColoniesByOwner(uuid)).equals(List.of(bId, cId)), "owned after delete: " + ids(manager.getIColoniesByOwner(uuid)));
        helper.assertTrue(manager.getSelectedColony(uuid) == b, "selection moved when another colony was deleted");

        // Abandon the selected colony B explicitly: it is no longer the player's, selection falls back to C.
        helper.assertTrue(ColonyAbandonOwnMessage.execute(player, b.getDimension(), bId) == bId, "explicit abandon returned wrong");
        helper.assertTrue(!uuid.equals(b.getPermissions().getOwner()), "abandoned colony still owned");
        helper.assertTrue(b.getPermissions().getRank(player).isColonyManager(), "former owner is not an officer of the abandoned colony");
        helper.assertTrue(ids(manager.getIColoniesByOwner(uuid)).equals(List.of(cId)), "owned after abandon: " + ids(manager.getIColoniesByOwner(uuid)));
        helper.assertTrue(manager.getSelectedColony(uuid) == c, "selection did not fall back to the remaining colony");
        helper.assertTrue(SelectedColonySavedData.get(level.getServer()).get(uuid) == null, "dropped selection still saved");

        // No colony named: the one the player stands in, else the selected one. Found two more so there is a choice.
        placeTownHall(helper, 2, 2);
        final IColony d = found(helper, player, hallA, "MC del D");
        helper.assertTrue(d != null, "could not found a colony on the freed spot");
        final int dId = d.getID();
        manager.setSelectedColony(uuid, c.getDimension(), cId);
        player.setPos(hallA.getX() + 0.5, hallA.getY() + 1, hallA.getZ() + 0.5);
        helper.assertTrue(manager.getIColony(level, player.blockPosition()) == d, "fixture: player is not standing in colony D");
        helper.assertTrue(ColonyDeleteOwnMessage.execute(player, null, -1) == dId, "unnamed delete did not pick the colony the player stands in");
        helper.assertTrue(manager.getColonyByDimension(dId, d.getDimension()) == null, "colony D not deleted");
        player.setPos(hallA.getX() + 0.5, hallA.getY() + 1, hallA.getZ() + 0.5);
        helper.assertTrue(ColonyDeleteOwnMessage.execute(player, null, -1) == cId, "unnamed delete outside a colony did not pick the selected colony");
        helper.assertTrue(manager.getIColoniesByOwner(uuid).isEmpty(), "player still owns colonies: " + ids(manager.getIColoniesByOwner(uuid)));
        helper.assertTrue(ColonyDeleteOwnMessage.execute(player, null, -1) == -1, "deleted something with no colony");

        unforce(level, hallA, hallB, hallC);
        helper.succeed();
    }

    /**
     * The owned-colonies summaries carry the right data, survive the network codec, are sent on login and on change,
     * and the client store returns them.
     */
    public static void owned_colonies_message(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColonyManager manager = IColonyManager.getInstance();
        ColonyLimits.overrideMaxPerPlayer(2);
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
        final UUID uuid = player.getUUID();

        // The player logged in without colonies: the server sent an (empty) list.
        helper.assertTrue(manager.getLastSentOwnedColonySummaries(uuid) != null && manager.getLastSentOwnedColonySummaries(uuid).isEmpty(),
          "nothing sent at login: " + manager.getLastSentOwnedColonySummaries(uuid));

        final BlockPos hallA = placeTownHall(helper, 2, 2);
        final BlockPos hallB = placeTownHall(helper, SECOND_X, 2);
        final Colony a = (Colony) found(helper, player, hallA, "MC sum A");
        final Colony b = (Colony) found(helper, player, hallB, "MC sum B");
        helper.assertTrue(a != null && b != null, "could not found two colonies");
        b.setColonyColor(net.minecraft.ChatFormatting.RED);

        final List<OwnedColonySummary> summaries = manager.computeOwnedColonySummaries(uuid);
        helper.assertTrue(summaries.size() == 2, "summaries: " + summaries.size());
        final OwnedColonySummary sa = summaries.get(0);
        final OwnedColonySummary sb = summaries.get(1);
        helper.assertTrue(sa.id() == a.getID() && sa.dimension().equals(a.getDimension()) && sa.name().equals("MC sum A"), "summary A identity: " + sa);
        helper.assertTrue(sa.townHallPos().equals(hallA), "summary A town hall " + sa.townHallPos() + " expected " + hallA);
        helper.assertTrue(sb.townHallPos().equals(hallB) && sb.id() == b.getID(), "summary B: " + sb);
        helper.assertTrue(sa.citizenCount() == a.getCitizenManager().getCurrentCitizenCount(), "summary A citizens " + sa.citizenCount());
        helper.assertTrue(sa.selected() && !sb.selected(), "selected flags: " + sa.selected() + ", " + sb.selected());
        helper.assertTrue(sb.colour() == 0xFF5555 && sa.colour() == 0xFFFFFF, "colours: " + Integer.toHexString(sa.colour()) + ", " + Integer.toHexString(sb.colour()));
        helper.assertTrue(sa.banner().equals(a.getColonyFlag()), "banner differs");

        // Network round trip of the message.
        final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        final StreamCodec<RegistryFriendlyByteBuf, CustomPacketPayload> codec = codec();
        codec.encode(buf, new OwnedColoniesMessage(summaries));
        final OwnedColoniesMessage decoded = (OwnedColoniesMessage) codec.decode(buf);
        helper.assertTrue(decoded.getSummaries().equals(summaries), "message round trip: " + decoded.getSummaries());
        helper.assertTrue(buf.readableBytes() == 0, "message left " + buf.readableBytes() + " bytes");

        // Client store.
        manager.handleOwnedColoniesMessage(decoded.getSummaries());
        helper.assertTrue(manager.getOwnedColonySummaries().equals(summaries), "client store differs");
        manager.handleOwnedColoniesMessage(List.of());

        // Sent on change: founding (next tick), selection (next tick), rename (periodic check).
        helper.runAfterDelay(5, () -> {
            final List<OwnedColonySummary> sent = manager.getLastSentOwnedColonySummaries(uuid);
            helper.assertTrue(sent != null && sent.equals(summaries), "founding not sent: " + sent);
            manager.setSelectedColony(uuid, b.getDimension(), b.getID());
            helper.runAfterDelay(5, () -> {
                final List<OwnedColonySummary> sentSelected = manager.getLastSentOwnedColonySummaries(uuid);
                helper.assertTrue(sentSelected.size() == 2 && !sentSelected.get(0).selected() && sentSelected.get(1).selected(), "selection not sent: " + sentSelected);
                b.setName("MC sum B renamed");
                a.getCitizenManager();
                helper.runAfterDelay(110, () -> {
                    final List<OwnedColonySummary> sentRename = manager.getLastSentOwnedColonySummaries(uuid);
                    helper.assertTrue(sentRename.get(1).name().equals("MC sum B renamed"), "rename not sent within the check interval: " + sentRename);
                    // Deleting a colony is sent too.
                    ColonyDeleteOwnMessage.execute(player, a.getDimension(), a.getID());
                    helper.runAfterDelay(5, () -> {
                        final List<OwnedColonySummary> sentDelete = manager.getLastSentOwnedColonySummaries(uuid);
                        helper.assertTrue(sentDelete.size() == 1 && sentDelete.get(0).id() == b.getID() && sentDelete.get(0).selected(), "delete not sent: " + sentDelete);
                        unforce(level, hallA, hallB);
                        helper.succeed();
                    });
                });
            });
        });
    }

    @SuppressWarnings("unchecked")
    private static StreamCodec<RegistryFriendlyByteBuf, CustomPacketPayload> codec()
    {
        return (StreamCodec<RegistryFriendlyByteBuf, CustomPacketPayload>) (StreamCodec<?, ?>) ((PlayMessageType<?>) OwnedColoniesMessage.TYPE).codec();
    }

    /**
     * setForceActive: ACTIVE at once while a manager is online, back at once when released, no effect without a
     * manager, force load tickets for the town hall chunk that expire after the release.
     */
    public static void force_active_state(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = foundGameTestColony(helper, "MC force active");
        // A fake player is no colony subscriber; a connected player is the owner and the manager who is online.
        final ServerPlayer fake = makeConnectedSurvivalPlayer(helper);
        colony.getPermissions().setOwner(fake);
        colony.getPackageManager().getImportantColonyPlayers().clear();
        colony.getPackageManager().addImportantColonyPlayer(fake);
        colony.getPackageManager().getCloseSubscribers().clear();
        // A fixture with a manager online and over 40 loaded chunks is ACTIVE by itself; forget the loaded chunks so only
        // the flag can make this colony ACTIVE.
        new ArrayList<>(colony.getLoadedChunks()).forEach(colony::removeLoadedChunk);
        helper.assertTrue(colony.getPackageManager().getImportantColonyPlayers().contains(fake), "fixture: the founder is not an important player");
        helper.assertTrue(!colony.isForceActive(), "force active by default");
        helper.runAfterDelay(260, () -> {
            colony.getPackageManager().getCloseSubscribers().clear();
            helper.runAfterDelay(110, () -> {
                // Nobody close: not simulating, but a manager is online.
                helper.assertTrue(colony.getState() == ColonyState.UNLOADED, "baseline state " + colony.getState() + ", close " + colony.getPackageManager().getCloseSubscribers().size() + ", loaded chunks " + colony.getLoadedChunkCount() + ", important " + colony.getPackageManager().getImportantColonyPlayers().size());
                colony.setForceActive(true);
                helper.assertTrue(colony.isForceActive() && colony.getState() == ColonyState.ACTIVE, "not ACTIVE at once: " + colony.getState());
                final long townHallChunk = net.minecraft.world.level.ChunkPos.containing(colony.getServerBuildingManager().getTownHall().getPosition()).pack();
                helper.assertTrue(colony.getTicketedChunks().contains(townHallChunk), "town hall chunk not ticketed: " + colony.getTicketedChunks());
                helper.assertTrue(colony.getTicketedChunks().size() <= Colony.MAX_FORCE_ACTIVE_CHUNKS, "ticket bound broken");
                helper.runAfterDelay(250, () -> {
                    helper.assertTrue(colony.getState() == ColonyState.ACTIVE, "force active colony left ACTIVE by itself: " + colony.getState());
                    colony.setForceActive(false);
                    helper.assertTrue(!colony.isForceActive() && colony.getState() == ColonyState.UNLOADED, "not released at once: " + colony.getState());

                    // No manager online: the flag simulates nothing.
                    colony.setForceActive(true);
                    helper.assertTrue(colony.getState() == ColonyState.ACTIVE, "not ACTIVE again: " + colony.getState());
                    colony.getPackageManager().getImportantColonyPlayers().clear();
                    helper.runAfterDelay(110, () -> {
                        helper.assertTrue(colony.getState() != ColonyState.ACTIVE, "colony simulates with no manager online: " + colony.getState());
                        colony.getPackageManager().addImportantColonyPlayer(fake);
                        helper.runAfterDelay(110, () -> {
                            helper.assertTrue(colony.getState() == ColonyState.ACTIVE, "force active did not resume with a manager online: " + colony.getState());
                            colony.setForceActive(false);
                            // The tickets expire at the next load timer update.
                            helper.runAfterDelay(1100, () -> {
                                helper.assertTrue(colony.getTicketedChunks().isEmpty(), "tickets kept after the release: " + colony.getTicketedChunks());
                                helper.succeed();
                            });
                        });
                    });
                });
            });
        });
    }
}
