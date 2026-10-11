package com.minecolonies.core.colony.managers;

import com.ldtteam.common.network.AbstractClientPlayMessage;
import com.minecolonies.api.colony.managers.interfaces.IColonyPackageManager;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.colony.workorders.IWorkManager;
import com.minecolonies.api.util.ColonyUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.ColonyView;
import com.minecolonies.core.colony.requestsystem.management.manager.RequestSystemViewSync;
import com.minecolonies.core.colony.requestsystem.management.manager.StandardRequestManager;
import com.minecolonies.core.colony.permissions.Permissions;
import com.minecolonies.core.network.messages.PermissionsMessage;
import com.minecolonies.core.network.messages.client.colony.ColonyViewMessage;
import com.minecolonies.core.network.messages.client.colony.ColonyViewRequestSystemMessage;
import com.minecolonies.core.network.messages.client.colony.ColonyViewWorkOrderMessage;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiConsumer;

import static com.minecolonies.api.util.constant.ColonyConstants.UPDATE_STATE_INTERVAL;
import static com.minecolonies.api.util.constant.Constants.TICKS_HOUR;

public class ColonyPackageManager implements IColonyPackageManager
{
    /**
     * Test seam: when set, every colony view and citizen view message sent through {@link #sendView} is reported to it
     * (once per receiving player) before it goes out. GameTest mock players never show what they received, so the sync
     * GameTests count messages here. Null in production.
     */
    @Nullable
    public static volatile BiConsumer<ServerPlayer, AbstractClientPlayMessage> viewSendRecorder = null;

    /**
     * Sends a view message to the given players, reporting it to {@link #viewSendRecorder} first when one is set.
     */
    public static void sendView(@NotNull final AbstractClientPlayMessage message, @NotNull final Collection<ServerPlayer> players)
    {
        final BiConsumer<ServerPlayer, AbstractClientPlayMessage> recorder = viewSendRecorder;
        if (recorder != null)
        {
            players.forEach(player -> recorder.accept(player, message));
        }
        message.sendToPlayer(players);
    }

    /**
     * List of players close to the colony receiving updates. Populated by chunk entry events
     */
    @NotNull
    private Set<ServerPlayer> closeSubscribers = new HashSet<>();

    /**
     * List of players with global permissions, like receiving important messages from far away. Populated on player login and logoff.
     */
    private Set<ServerPlayer> importantColonyPlayers = new HashSet<>();

    /**
     * New subscribers which havent received a view yet.
     */
    private Set<ServerPlayer> newSubscribers = new HashSet<>();

    /**
     * Variables taking care of updating the views.
     */
    private boolean isDirty = false;

    /**
     * CA-2: how long the colony view fields derived from citizens (overall happiness, statistics) may lag behind when
     * only citizens changed. A citizen change used to re-send the whole colony view every second.
     */
    public static final int CITIZEN_DERIVED_REFRESH_TICKS = 200;

    /**
     * Game time since which the citizen-derived colony view fields are stale, or -1 when close subscribers have them.
     */
    private long citizenDerivedStaleSince = -1;

    /**
     * CA-1: what close subscribers hold of the request-system view, and players that asked for a full resync of it.
     */
    private final RequestSystemViewSync.ServerBaseline requestSystemBaseline = new RequestSystemViewSync.ServerBaseline();
    private final Set<ServerPlayer>                    requestSystemResync   = new HashSet<>();

    /**
     * CA-3: citizens whose view a close subscriber asked to resync.
     */
    private final Map<ServerPlayer, Set<Integer>> citizenResync = new HashMap<>();

    /**
     * Amount of ticks passed.
     */
    private int ticksPassed = 0;

    /**
     * The last contact in hours.
     */
    private int lastContactInHours = 0;

    /**
     * The colony of the manager.
     */
    private final Colony colony;

    /**
     * Creates the ColonyPackageManager for a colony.
     *
     * @param colony the colony.
     */
    public ColonyPackageManager(final Colony colony)
    {
        this.colony = colony;
    }

    @Override
    public int getLastContactInHours()
    {
        return lastContactInHours;
    }

    @Override
    public void setLastContactInHours(final int lastContactInHours)
    {
        this.lastContactInHours = lastContactInHours;
    }

    @Override
    public Set<ServerPlayer> getCloseSubscribers()
    {
        return closeSubscribers;
    }

    @Override
    public void updateSubscribers()
    {
        final Level world = colony.getWorld();
        // If the world or server is null, don't try to update the closeSubscribers this tick.
        if (world == null || world.getServer() == null)
        {
            return;
        }

        updateClosePlayers();
        updateColonyViews();
    }

    /**
     * Updates currently close players to the colony
     */
    private void updateClosePlayers()
    {
        for (Iterator<ServerPlayer> iterator = closeSubscribers.iterator(); iterator.hasNext(); )
        {
            final ServerPlayer player = iterator.next();

            if (!player.isAlive() || colony.getWorld() != player.level() || !WorldUtil.isChunkLoaded(player.level(), player.chunkPosition().x(), player.chunkPosition().z()))
            {
                iterator.remove();
                continue;
            }

            final LevelChunk chunk = colony.getWorld().getChunk(player.chunkPosition().x(), player.chunkPosition().z());
            if (chunk.isEmpty())
            {
                iterator.remove();
                continue;
            }

            if (ColonyUtils.getOwningColony(chunk) != colony.getID())
            {
                iterator.remove();
            }
        }
    }

    /**
     * Updates the away timer for the colony.
     */
    @Override
    public void updateAwayTime()
    {
        if (importantColonyPlayers.isEmpty())
        {
            if (ticksPassed >= TICKS_HOUR)
            {
                ticksPassed = 0;
                lastContactInHours++;
                colony.markDirty();
            }
            ticksPassed += UPDATE_STATE_INTERVAL;
        }
        else if (lastContactInHours != 0)
        {
            lastContactInHours = 0;
            ticksPassed = 0;
            colony.markDirty();
        }
    }

    /**
     * Update the closeSubscribers of the colony.
     */
    public void updateColonyViews()
    {
        if (!closeSubscribers.isEmpty() || !newSubscribers.isEmpty())
        {
            //  Send each type of update packet as appropriate:
            //      - To close Subscribers if the data changes
            //      - To New Subscribers even if it hasn't changed

            //ColonyView
            sendColonyViewPackets();

            //Request system (CA-1: its own message, after the colony view a new subscriber needs first)
            sendRequestSystemPackets();

            //Permissions
            sendPermissionsPackets();

            //WorkOrders
            sendWorkOrderPackets();

            colony.getCitizenManager().sendPackets(closeSubscribers, newSubscribers);
            sendCitizenResyncs();
            colony.getVisitorManager().sendPackets(closeSubscribers, newSubscribers);
            colony.getServerBuildingManager().sendPackets(closeSubscribers, newSubscribers);
            colony.getAnimalManager().sendPackets(closeSubscribers, newSubscribers);
            colony.getResearchManager().sendPackets(closeSubscribers, newSubscribers);
        }

        if (newSubscribers.isEmpty())
        {
            isDirty = false;
        }
        colony.getPermissions().clearDirty();
        colony.getServerBuildingManager().clearDirty();
        colony.getCitizenManager().clearDirty();
        colony.getVisitorManager().clearDirty();
        colony.getAnimalManager().clearDirty();
        colony.getResearchManager().clearDirty();
        newSubscribers = new HashSet<>();
    }

    @Override
    public void sendColonyViewPackets()
    {
        if (!isDirty && citizenDerivedRefreshDue())
        {
            isDirty = true;
        }
        if (isDirty || !newSubscribers.isEmpty())
        {
            final RegistryFriendlyByteBuf colonyFriendlyByteBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), colony.getWorld().registryAccess());
            ColonyView.serializeNetworkData(colony, colonyFriendlyByteBuf, !newSubscribers.isEmpty());
            final Set<ServerPlayer> players = new HashSet<>();
            if (isDirty)
            {
                players.addAll(closeSubscribers);
            }
            players.addAll(newSubscribers);

            for (ServerPlayer player : players)
            {
                sendView(new ColonyViewMessage(colony, colonyFriendlyByteBuf, newSubscribers.contains(player)), List.of(player));
            }
            if (isDirty)
            {
                // Every close subscriber got the current citizen-derived fields.
                citizenDerivedStaleSince = -1;
            }
        }
    }

    /**
     * CA-1: sends the request-system view: a delta to close subscribers when it changed, a full payload to new
     * subscribers and to players that asked for a resync, and a periodic full resync.
     */
    public void sendRequestSystemPackets()
    {
        if (!(colony.getRequestManager() instanceof StandardRequestManager manager))
        {
            return;
        }
        requestSystemResync.retainAll(closeSubscribers);
        requestSystemResync.removeAll(newSubscribers);
        final Set<ServerPlayer> closes = new HashSet<>(closeSubscribers);
        closes.removeAll(newSubscribers);
        closes.removeAll(requestSystemResync);

        final long now = gameTime();
        final RequestSystemViewSync.Snapshot previous = requestSystemBaseline.baseline();
        final boolean fullDue = !closes.isEmpty() && requestSystemBaseline.fullDue(now);
        final boolean snapshotNeeded = !newSubscribers.isEmpty()
                                         || (!closes.isEmpty() && (manager.isDirty() || fullDue || previous == null))
                                         || (!requestSystemResync.isEmpty() && previous == null);
        if (!snapshotNeeded)
        {
            if (!requestSystemResync.isEmpty() && previous != null)
            {
                sendRequestSystem(full(previous, requestSystemBaseline.seq()), requestSystemResync);
            }
            requestSystemResync.clear();
            if (closes.isEmpty() && newSubscribers.isEmpty())
            {
                return;
            }
            manager.setDirty(false);
            return;
        }

        final RequestSystemViewSync.Snapshot snapshot;
        try
        {
            snapshot = RequestSystemViewSync.snapshot(manager, colony.getWorld().registryAccess());
        }
        catch (final Exception e)
        {
            Log.getLogger().warn("Error during request manager serialization for:" + colony.getID(), e);
            manager.reset();
            requestSystemBaseline.clear();
            return;
        }
        if (snapshot.size() >= ColonyView.REQUEST_MANAGER_MAX_SIZE)
        {
            Log.getLogger().warn("Colony " + colony.getID() + " has a very big memory imprint, this could be a memory leak, please contact the mod author!");
        }

        final Set<ServerPlayer> fullTo = new HashSet<>(newSubscribers);
        fullTo.addAll(requestSystemResync);
        if (previous == null || fullDue)
        {
            final int seq = requestSystemBaseline.advance(snapshot, true, now);
            fullTo.addAll(closes);
            sendRequestSystem(full(snapshot, seq), fullTo);
        }
        else
        {
            final int fromSeq = requestSystemBaseline.seq();
            final RegistryFriendlyByteBuf delta = new RegistryFriendlyByteBuf(Unpooled.buffer(), colony.getWorld().registryAccess());
            final int seq;
            if (RequestSystemViewSync.writeDelta(delta, previous, fromSeq, snapshot, fromSeq + 1))
            {
                seq = requestSystemBaseline.advance(snapshot, false, now);
                sendRequestSystem(delta, closes);
            }
            else
            {
                seq = fromSeq;
            }
            sendRequestSystem(full(snapshot, seq), fullTo);
        }
        requestSystemResync.clear();
        manager.setDirty(false);
    }

    private RegistryFriendlyByteBuf full(final RequestSystemViewSync.Snapshot snapshot, final int seq)
    {
        final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), colony.getWorld().registryAccess());
        RequestSystemViewSync.writeFull(buf, snapshot, seq);
        return buf;
    }

    private void sendRequestSystem(final RegistryFriendlyByteBuf payload, final Set<ServerPlayer> players)
    {
        if (players.isEmpty())
        {
            return;
        }
        final ColonyViewRequestSystemMessage message = new ColonyViewRequestSystemMessage(colony.getID(), colony.getDimension(), payload);
        sendView(message, players);
    }

    /**
     * CA-1: a close subscriber could not apply a request-system delta; it gets a full payload next round.
     */
    public void requestRequestSystemResync(@NotNull final ServerPlayer player)
    {
        if (closeSubscribers.contains(player))
        {
            requestSystemResync.add(player);
        }
    }

    /**
     * CA-3: a close subscriber could not apply a citizen view patch; it gets that citizen's full view next round.
     */
    public void requestCitizenResync(@NotNull final ServerPlayer player, final int citizenId)
    {
        if (closeSubscribers.contains(player))
        {
            citizenResync.computeIfAbsent(player, p -> new HashSet<>()).add(citizenId);
        }
    }

    private void sendCitizenResyncs()
    {
        if (citizenResync.isEmpty())
        {
            return;
        }
        for (final Map.Entry<ServerPlayer, Set<Integer>> entry : citizenResync.entrySet())
        {
            if (closeSubscribers.contains(entry.getKey()) && !newSubscribers.contains(entry.getKey())
                  && colony.getCitizenManager() instanceof CitizenManager citizens)
            {
                entry.getValue().forEach(id -> citizens.sendCitizenResync(entry.getKey(), id));
            }
        }
        citizenResync.clear();
    }

    @Override
    public void markCitizenDerivedDirty()
    {
        if (citizenDerivedStaleSince < 0)
        {
            citizenDerivedStaleSince = gameTime();
        }
    }

    /**
     * @return true once the citizen-derived colony view fields (or unsent statistics) have been stale for the refresh window.
     */
    private boolean citizenDerivedRefreshDue()
    {
        if (citizenDerivedStaleSince < 0 && colony.getStatisticsManager().hasDirtyStats())
        {
            citizenDerivedStaleSince = gameTime();
        }
        return citizenDerivedStaleSince >= 0 && gameTime() - citizenDerivedStaleSince >= CITIZEN_DERIVED_REFRESH_TICKS;
    }

    private long gameTime()
    {
        // No world yet (colony still loading): count it as stale since the beginning, so the first send refreshes.
        return colony.getWorld() == null ? 0 : colony.getWorld().getGameTime();
    }

    @Override
    public void sendPermissionsPackets()
    {
        final Permissions permissions = colony.getPermissions();
        if (permissions.isDirty() || !newSubscribers.isEmpty())
        {
            final Set<ServerPlayer> players = new HashSet<>();
            if (isDirty)
            {
                players.addAll(closeSubscribers);
            }
            players.addAll(newSubscribers);
            players.forEach(player -> new PermissionsMessage.View(colony, permissions.getRank(player)).sendToPlayer(player));
        }
    }

    @Override
    public void sendWorkOrderPackets()
    {
        final IWorkManager workManager = colony.getWorkManager();
        if (workManager.isDirty() || !newSubscribers.isEmpty())
        {
            final Set<ServerPlayer> players = new HashSet<>();

            players.addAll(closeSubscribers);
            players.addAll(newSubscribers);

            List<IServerWorkOrder> workOrders = new ArrayList<>(workManager.getWorkOrders().values());
            new ColonyViewWorkOrderMessage(colony, workOrders).sendToPlayer(players);

            workManager.setDirty(false);
        }
    }

    @Override
    public void setDirty()
    {
        this.isDirty = true;
    }

    @Override
    public void addCloseSubscriber(@NotNull final ServerPlayer subscriber)
    {
        if (subscriber instanceof FakePlayer)
        {
            // A faction colony's worker AI acts through a fake player; that is no subscriber, and no reason to complain.
            if (!colony.getPermissions().isFactionOwned())
            {
                Log.getLogger().warn("Adding fakeplayer as subscriber: this should not happen", new Exception());
            }
            return;
        }

        if (!closeSubscribers.contains(subscriber))
        {
            closeSubscribers.add(subscriber);
            newSubscribers.add(subscriber);
            updateColonyViews();
        }
    }

    @Override
    public void removeCloseSubscriber(@NotNull final ServerPlayer player)
    {
        newSubscribers.remove(player);
        closeSubscribers.remove(player);
    }

    /**
     * On login we're adding global subscribers.
     */
    @Override
    public void addImportantColonyPlayer(@NotNull final ServerPlayer subscriber)
    {
        if (subscriber instanceof FakePlayer)
        {
            if (!colony.getPermissions().isFactionOwned())
            {
                Log.getLogger().warn("Adding fakeplayer as important subscriber: this should not happen", new Exception());
            }
            return;
        }

        importantColonyPlayers.add(subscriber);
        newSubscribers.add(subscriber);
    }

    /**
     * On logoff we're removing global subscribers.
     */
    @Override
    public void removeImportantColonyPlayer(@NotNull final ServerPlayer subscriber)
    {
        importantColonyPlayers.remove(subscriber);
        newSubscribers.remove(subscriber);
    }

    /**
     * Returns the list of online global subscribers of the colony.
     */
    @Override
    public Set<ServerPlayer> getImportantColonyPlayers()
    {
        return importantColonyPlayers;
    }
}
