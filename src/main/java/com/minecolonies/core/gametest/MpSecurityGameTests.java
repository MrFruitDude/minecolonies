package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.connections.ColonyConnection;
import com.minecolonies.api.colony.connections.ConnectionEvent;
import com.minecolonies.api.colony.connections.ConnectionEventType;
import com.minecolonies.api.colony.connections.DiplomacyStatus;
import com.minecolonies.api.colony.permissions.IPermissions;
import com.minecolonies.core.colony.ColonyLimits;
import com.minecolonies.core.network.messages.server.CreateColonyMessage;
import com.minecolonies.core.network.messages.server.colony.TriggerConnectionEventMessage;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

import static com.minecolonies.core.gametest.MinecoloniesGameTests.makeConnectedSurvivalPlayer;

/**
 * MULTIPLAYER (MP-A) section 2, the two MineColonies messages that let one player write to another's colony:
 * <ul>
 *     <li>{@code CreateColonyMessage} used to rewrite the structure pack and blueprint path of the hut it names (and reactivate it) before it checked who the hut belongs to, whether
 *     the sender is near it, the founding limit or the spawn distance; and it named the nearest colony to a stranger.</li>
 *     <li>{@code TriggerConnectionEventMessage} checked the sender's rank only in the colony the message names, so a player with rights in one colony could set the diplomacy status of any
 *     two connected colonies (the event's origin id was client-supplied).</li>
 * </ul>
 * One glob: {@code minecolonies:mpsec_*}.
 */
public final class MpSecurityGameTests
{
    private MpSecurityGameTests()
    {
    }

    private static final String PACK = "Minecolonies Original";
    private static final String HALL = "fundamentals/townhall1.blueprint";
    private static final int FAR = 200;

    /**
     * The production founding packet, run as a player.
     */
    private static final class FoundingMessage extends CreateColonyMessage
    {
        private FoundingMessage(final BlockPos townHall, final String name, final String path)
        {
            super(townHall, false, name, PACK, path);
        }

        private void execute(final ServerPlayer player)
        {
            onExecute(null, player);
        }
    }

    /**
     * The production trigger packet, run as a player.
     */
    private static final class TriggerMessage extends TriggerConnectionEventMessage
    {
        private final IColony colony;

        private TriggerMessage(final IColony colony, final ConnectionEvent event, final int target)
        {
            super(colony, event, target);
            this.colony = colony;
        }

        private void execute(final ServerPlayer player)
        {
            onExecute(null, player, colony);
        }
    }

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
        hut.setBlueprintPath(HALL);
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
     * Stand the player at the hut, as a player who just right-clicked it does.
     */
    private static void stand(final ServerPlayer player, final BlockPos hut)
    {
        player.teleportTo(hut.getX() + 0.5, hut.getY(), hut.getZ() + 2.5);
    }

    private static IColony found(final GameTestHelper helper, final ServerPlayer player, final BlockPos hall, final String name)
    {
        stand(player, hall);
        new FoundingMessage(hall, name, HALL).execute(player);
        final IColony closest = IColonyManager.getInstance().getClosestColony(helper.getLevel(), hall);
        return closest != null && closest.getName().equals(name) ? closest : null;
    }

    private static TileEntityColonyBuilding hutAt(final ServerLevel level, final BlockPos pos)
    {
        return (TileEntityColonyBuilding) level.getBlockEntity(pos);
    }

    private static String describe(final TileEntityColonyBuilding hut)
    {
        return hut.getPackName() + ":" + hut.getBlueprintPath();
    }

    private static void connect(final IColony a, final IColony b)
    {
        a.getConnectionManager().getDirectlyConnectedColonies().put(b.getID(), new ColonyConnection(b.getID(), b.getName(), b.getCenter(), DiplomacyStatus.NEUTRAL));
        b.getConnectionManager().getDirectlyConnectedColonies().put(a.getID(), new ColonyConnection(a.getID(), a.getName(), a.getCenter(), DiplomacyStatus.NEUTRAL));
    }

    private static DiplomacyStatus status(final IColony of, final IColony toward)
    {
        return of.getConnectionManager().getColonyDiplomacyStatus(toward.getID());
    }

    // ------------------------------------------------------------------------------------------------------------

    /**
     * A connection event is the sending colony's own, and a status between two colonies is a decision of both: the sender needs MANAGE_HUTS in the colony the message
     * names and in the target, and the event must name the sending colony as its origin.
     */
    public static void trigger_connection_needs_both_colonies(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        ColonyLimits.overrideMaxPerPlayer(null);
        final ServerPlayer playerA = makeConnectedSurvivalPlayer(helper);
        final ServerPlayer playerB = makeConnectedSurvivalPlayer(helper);
        final ServerPlayer playerC = makeConnectedSurvivalPlayer(helper);
        final BlockPos hallA = placeTownHall(helper, 2, 2);
        final BlockPos hallB = placeTownHall(helper, FAR, 2);
        final BlockPos hallC = placeTownHall(helper, 2 * FAR, 2);
        try
        {
            final IColony a = found(helper, playerA, hallA, "MP sec A");
            final IColony b = found(helper, playerB, hallB, "MP sec B");
            final IColony c = found(helper, playerC, hallC, "MP sec C");
            helper.assertTrue(a != null && b != null && c != null, "could not found three colonies");
            connect(a, b);
            connect(a, c);
            connect(b, c);

            // The owner of A has no rights in B: an alliance he declares is not set, in either colony.
            new TriggerMessage(a, new ConnectionEvent(a.getID(), a.getName(), ConnectionEventType.ALLY_CONFIRMED), b.getID()).execute(playerA);
            helper.assertTrue(status(a, b) == DiplomacyStatus.NEUTRAL && status(b, a) == DiplomacyStatus.NEUTRAL,
              "the owner of A allied B's colony without any right in it: " + status(a, b) + " / " + status(b, a));

            // The crafted event: sent for A (where he may act), but naming C as the origin, aimed at B. It would set the pair B and C, two colonies he has nothing to do with.
            new TriggerMessage(a, new ConnectionEvent(c.getID(), c.getName(), ConnectionEventType.FEUD_STARTED), b.getID()).execute(playerA);
            helper.assertTrue(status(b, c) == DiplomacyStatus.NEUTRAL && status(c, b) == DiplomacyStatus.NEUTRAL,
              "a crafted event set the status between two other colonies: " + status(b, c) + " / " + status(c, b));

            // With MANAGE_HUTS in both colonies the pair can decide; the event must still be the sender colony's own.
            b.getPermissions().addPlayer(playerA.getUUID(), playerA.getGameProfile().name(), b.getPermissions().getRankOfficer());
            new TriggerMessage(a, new ConnectionEvent(c.getID(), c.getName(), ConnectionEventType.FEUD_STARTED), b.getID()).execute(playerA);
            helper.assertTrue(status(b, c) == DiplomacyStatus.NEUTRAL && status(c, b) == DiplomacyStatus.NEUTRAL,
              "an officer of A and B set the status between B and C, which is neither of the sender's colonies: " + status(b, c));
            new TriggerMessage(a, new ConnectionEvent(a.getID(), a.getName(), ConnectionEventType.ALLY_CONFIRMED), b.getID()).execute(playerA);
            helper.assertTrue(status(a, b) == DiplomacyStatus.ALLIES && status(b, a) == DiplomacyStatus.ALLIES,
              "an officer of both colonies could not ally them: " + status(a, b) + " / " + status(b, a));

            // An officer of B only (not of A) is refused when the message names A.
            final IPermissions permsA = a.getPermissions();
            helper.assertTrue(!permsA.hasPermission(playerB, com.minecolonies.api.colony.permissions.Action.MANAGE_HUTS), "fixture: B's owner has no rights in A");
            new TriggerMessage(a, new ConnectionEvent(a.getID(), a.getName(), ConnectionEventType.NEUTRAL_SET), b.getID()).execute(playerB);
            helper.assertTrue(status(a, b) == DiplomacyStatus.ALLIES, "a player without rights in A reset A's alliance: " + status(a, b));
        }
        finally
        {
            unforce(level, hallA, hallB, hallC);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------

    /**
     * Founding changes the hut (its pack, its blueprint path, reactivation) only once the sender has shown that the hut is his to found with: it is within his reach, it is not a
     * building of a colony already, and the founding is allowed (limit, spawn distance, distance to other colonies, listeners). A refusal leaves the hut as it was.
     */
    public static void create_colony_changes_the_hut_only_after_the_checks(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        ColonyLimits.overrideMaxPerPlayer(null);
        final ServerPlayer owner = makeConnectedSurvivalPlayer(helper);
        final ServerPlayer attacker = makeConnectedSurvivalPlayer(helper);
        final BlockPos hallOwner = placeTownHall(helper, 2, 2);
        final BlockPos hallFresh = placeTownHall(helper, FAR, 2);
        final BlockPos hallSecond = placeTownHall(helper, 2 * FAR, 2);
        final BlockPos hallFar = placeTownHall(helper, 3 * FAR, 2);
        try
        {
            final IColony colony = found(helper, owner, hallOwner, "MP sec owner");
            helper.assertTrue(colony != null, "the owner could not found a colony");
            final String before = describe(hutAt(level, hallOwner));
            final int coloniesBefore = IColonyManager.getInstance().getAllColonies().size();

            // 1. A stranger names a hut of someone else's colony, standing right next to it: refused, nothing rewritten.
            stand(attacker, hallOwner);
            new FoundingMessage(hallOwner, "MP sec stolen", "evil/path.blueprint").execute(attacker);
            helper.assertTrue(describe(hutAt(level, hallOwner)).equals(before), "a stranger rewrote the hut of another colony: " + before + " -> " + describe(hutAt(level, hallOwner)));
            helper.assertTrue(IColonyManager.getInstance().getAllColonies().size() == coloniesBefore, "a colony was founded on a hut of another colony");

            // 2. A fresh hut far from the sender: out of reach, nothing rewritten, no colony.
            final String freshBefore = describe(hutAt(level, hallFresh));
            attacker.teleportTo(hallFresh.getX() + 60.5, hallFresh.getY(), hallFresh.getZ() + 0.5);
            new FoundingMessage(hallFresh, "MP sec remote", "evil/path.blueprint").execute(attacker);
            helper.assertTrue(describe(hutAt(level, hallFresh)).equals(freshBefore), "a hut out of the sender's reach was rewritten: " + describe(hutAt(level, hallFresh)));
            helper.assertTrue(IColonyManager.getInstance().getAllColonies().size() == coloniesBefore, "a colony was founded on a hut the sender could not reach");

            // 3. The founding limit: the owner already has a colony (limit 1); a second hut is refused and left as it was.
            final String secondBefore = describe(hutAt(level, hallSecond));
            stand(owner, hallSecond);
            new FoundingMessage(hallSecond, "MP sec second", "evil/path.blueprint").execute(owner);
            helper.assertTrue(describe(hutAt(level, hallSecond)).equals(secondBefore), "a refused founding (limit) rewrote the hut: " + describe(hutAt(level, hallSecond)));
            helper.assertTrue(IColonyManager.getInstance().getAllColonies().size() == coloniesBefore, "the limit did not hold");

            // 4. The same sender next to a fresh hut founds a colony, and the hut takes the path he sent.
            stand(attacker, hallFar);
            new FoundingMessage(hallFar, "MP sec honest", "fundamentals/townhall2.blueprint").execute(attacker);
            final IColony honest = IColonyManager.getInstance().getClosestColony(level, hallFar);
            helper.assertTrue(honest != null && honest.getName().equals("MP sec honest") && honest.getPermissions().getOwner().equals(attacker.getUUID()), "an honest founding next to a fresh hut was refused");
            helper.assertTrue(describe(hutAt(level, hallFar)).equals(PACK + ":fundamentals/townhall2.blueprint"), "the founded hut did not take the sent path: " + describe(hutAt(level, hallFar)));
        }
        finally
        {
            unforce(level, hallOwner, hallFresh, hallSecond, hallFar);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------

    /**
     * A founding refused for being too close to a colony says so without naming it: the player may not be a member of that colony, and its name is none of his business.
     */
    public static void create_colony_too_close_names_nobody(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        ColonyLimits.overrideMaxPerPlayer(null);
        final ServerPlayer owner = makeConnectedSurvivalPlayer(helper);
        final ServerPlayer neighbour = makeConnectedSurvivalPlayer(helper);
        final BlockPos hallOwner = placeTownHall(helper, 2, 2);
        final BlockPos hallNear = placeTownHall(helper, 40, 2);
        try
        {
            final IColony colony = found(helper, owner, hallOwner, "MP sec secret name");
            helper.assertTrue(colony != null, "the owner could not found a colony");
            MinecoloniesGameTests.drainChat(neighbour);
            stand(neighbour, hallNear);
            new FoundingMessage(hallNear, "MP sec too close", HALL).execute(neighbour);
            helper.assertTrue(IColonyManager.getInstance().getClosestColony(level, hallNear) == colony, "a colony was founded inside another colony's claim");
            final List<Component> lines = MinecoloniesGameTests.drainChat(neighbour);
            helper.assertTrue(!lines.isEmpty(), "the refused player was told nothing");
            for (final Component line : lines)
            {
                helper.assertTrue(!mentions(line, "MP sec secret name"), "the refusal names the other colony: " + line);
            }
        }
        finally
        {
            unforce(level, hallOwner, hallNear);
        }
        helper.succeed();
    }

    private static boolean mentions(final Component component, final String text)
    {
        if (component.getString().contains(text))
        {
            return true;
        }
        if (component.getContents() instanceof final TranslatableContents translatable)
        {
            for (final Object arg : translatable.getArgs())
            {
                if (arg instanceof final Component nested ? mentions(nested, text) : String.valueOf(arg).contains(text))
                {
                    return true;
                }
            }
        }
        return component.getSiblings().stream().anyMatch(sibling -> mentions(sibling, text));
    }

    // ------------------------------------------------------------------------------------------------------------

    /**
     * Decoders refuse junk with a DecoderException (what the connection handles by dropping the sender), not with whatever the parser throws, and never size a list by a
     * negative number. The messages are the ones the multi-colony work added.
     */
    public static void decoders_refuse_junk(final GameTestHelper helper)
    {
        final net.minecraft.core.RegistryAccess access = helper.getLevel().registryAccess();
        final String bad = "Not A Valid Id!";

        net.minecraft.network.RegistryFriendlyByteBuf buf = buffer(access);
        buf.writeUtf(bad);
        buf.writeInt(3);
        expectDecoderException(helper, "SelectColonyMessage", buf, b -> new com.minecolonies.core.network.messages.server.colony.SelectColonyMessage(b,
          com.minecolonies.core.network.messages.server.colony.SelectColonyMessage.TYPE) {});

        buf = buffer(access);
        buf.writeBoolean(true);
        buf.writeUtf(bad);
        buf.writeInt(3);
        expectDecoderException(helper, "ColonyDeleteOwnMessage", buf, b -> new com.minecolonies.core.network.messages.server.colony.ColonyDeleteOwnMessage(b,
          com.minecolonies.core.network.messages.server.colony.ColonyDeleteOwnMessage.TYPE) {});

        buf = buffer(access);
        buf.writeBoolean(true);
        buf.writeUtf(bad);
        buf.writeInt(3);
        expectDecoderException(helper, "ColonyAbandonOwnMessage", buf, b -> new com.minecolonies.core.network.messages.server.colony.ColonyAbandonOwnMessage(b,
          com.minecolonies.core.network.messages.server.colony.ColonyAbandonOwnMessage.TYPE) {});

        // A negative count is no count: the list is empty, nothing is allocated by it.
        buf = buffer(access);
        buf.writeVarInt(-5);
        try
        {
            final com.minecolonies.core.network.messages.client.colony.OwnedColoniesMessage message = new com.minecolonies.core.network.messages.client.colony.OwnedColoniesMessage(buf,
              com.minecolonies.core.network.messages.client.colony.OwnedColoniesMessage.TYPE) {};
            helper.assertTrue(message.getSummaries().isEmpty(), "a negative count decoded to entries");
        }
        catch (final RuntimeException e)
        {
            helper.assertTrue(false, "OwnedColoniesMessage threw on a negative count: " + e);
        }
        finally
        {
            buf.release();
        }
        helper.succeed();
    }

    private static net.minecraft.network.RegistryFriendlyByteBuf buffer(final net.minecraft.core.RegistryAccess access)
    {
        return new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), access);
    }

    private static void expectDecoderException(final GameTestHelper helper, final String what, final net.minecraft.network.RegistryFriendlyByteBuf buf,
      final java.util.function.Function<net.minecraft.network.RegistryFriendlyByteBuf, ?> decode)
    {
        try
        {
            decode.apply(buf);
            helper.assertTrue(false, what + " accepted a malformed dimension id");
        }
        catch (final io.netty.handler.codec.DecoderException expected)
        {
            // refused the way the connection expects
        }
        catch (final RuntimeException e)
        {
            helper.assertTrue(false, what + " threw " + e.getClass().getSimpleName() + " instead of a DecoderException on a malformed dimension id");
        }
        finally
        {
            buf.release();
        }
    }
}
