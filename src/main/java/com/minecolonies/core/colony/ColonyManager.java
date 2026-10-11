package com.minecolonies.core.colony;

import com.google.common.collect.Maps;
import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.colony.OwnedColonySummary;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.api.colony.claim.ChunkClaimData;
import com.minecolonies.api.colony.claim.ClaimRevision;
import com.minecolonies.api.colony.claim.IChunkClaimData;
import com.minecolonies.api.colony.permissions.ColonyPlayer;
import com.minecolonies.api.colony.savedata.IServerColonySaveData;
import com.minecolonies.api.compatibility.CompatibilityManager;
import com.minecolonies.api.compatibility.ICompatibilityManager;
import com.minecolonies.api.crafting.IRecipeManager;
import com.minecolonies.api.eventbus.events.ColonyManagerLoadedModEvent;
import com.minecolonies.api.eventbus.events.ColonyManagerUnloadedModEvent;
import com.minecolonies.api.eventbus.events.colony.ColonyCreatedModEvent;
import com.minecolonies.api.eventbus.events.colony.ColonyDeletedModEvent;
import com.minecolonies.api.eventbus.events.colony.ColonyFoundingEvent;
import com.minecolonies.api.eventbus.events.colony.ColonyViewUpdatedModEvent;
import com.minecolonies.api.sounds.SoundManager;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.api.util.ColonyUtils;
import com.minecolonies.api.util.DamageSourceKeys;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.client.gui.WindowReactivateBuilding;
import com.minecolonies.core.blocks.huts.BlockHutTownHall;
import com.minecolonies.core.colony.permissions.Permissions;
import com.minecolonies.core.colony.requestsystem.management.manager.StandardRecipeManager;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import com.minecolonies.core.network.messages.client.colony.ColonyViewRemoveMessage;
import com.minecolonies.core.network.messages.client.colony.OwnedColoniesMessage;
import com.minecolonies.core.util.BackUpHelper;
import com.minecolonies.core.util.ChunkDataHelper;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

import static com.minecolonies.api.util.constant.ColonyManagerConstants.*;
import static com.minecolonies.api.util.constant.Constants.BLOCKS_PER_CHUNK;
import static com.minecolonies.api.util.constant.TranslationConstants.COLONY_FOUNDING_VETOED;
import static com.minecolonies.api.util.constant.TranslationConstants.COLONY_LIMIT_REACHED;
import static com.minecolonies.api.util.constant.NbtTagConstants.TAG_COMPATABILITY_MANAGER;
import static com.minecolonies.core.MineColonies.getConfig;

/**
 * Singleton class that links colonies to minecraft.
 */
@SuppressWarnings("PMD.ExcessiveClassLength")
public final class ColonyManager implements IColonyManager
{
    /**
     * The list of colony views.
     */
    @NotNull
    private final Map<ResourceKey<Level>, ColonyList<IColonyView>> colonyViews = new HashMap<>();

    /**
     * Recipemanager of this server.
     */
    private final IRecipeManager recipeManager = new StandardRecipeManager();

    /**
     * Creates a new compatibilityManager.
     */
    private final ICompatibilityManager compatibilityManager = new CompatibilityManager();

    /**
     * Creates a new compatibilityManager.
     */
    private final ICompatibilityManager compatibilityManagerClient = new CompatibilityManager();

    /**
     * Indicate if a schematic have just been downloaded. Client only
     */
    private boolean schematicDownloaded = false;

    /**
     * Global claim data from all colonies
     */
    private Map<ResourceKey<Level>, Long2ObjectMap<ChunkClaimData>> chunkClaimData = new HashMap<>();

    /**
     * Client side sound manager.
     */
    private SoundManager clientSoundManager;

    /**
     * Server side: owner to owned colonies.
     */
    private final OwnerIndex ownerIndex = new OwnerIndex();

    /**
     * Server side: what each player was sent last as their owned colonies.
     */
    private final Map<UUID, List<OwnedColonySummary>> lastSentOwned = new HashMap<>();

    /**
     * Server side: owners whose owned colonies changed and who are told on the next server tick.
     */
    private final Set<UUID> dirtyOwners = new LinkedHashSet<>();

    /**
     * Client side: the colonies the local player owns, as last sent by the server.
     */
    private volatile List<OwnedColonySummary> clientOwnedColonies = List.of();

    /**
     * How often the owned colonies of the online players are compared with what they were sent, in ticks.
     */
    private static final int OWNED_SYNC_INTERVAL = 100;

    @Nullable
    private IServerColonySaveData getColonySaveData(final ServerLevel w)
    {
       return IServerColonySaveData.getOrComputeSaveData(w);
    }

    @Override
    public IColony createColony(@NotNull final ServerLevel w, final BlockPos pos, @NotNull final Player player, @NotNull final String colonyName, @NotNull final String pack)
    {
        final IServerColonySaveData cap = IServerColonySaveData.getOrComputeSaveData(w);
        if (cap == null)
        {
            Log.getLogger().warn(MISSING_WORLD_CAP_MESSAGE);
            return null;
        }

        final IColony colony = cap.createColony(w, colonyName, pos);
        colony.setStructurePack(pack);

        colony.setName(colonyName);
        colony.getPermissions().setOwner(player);

        colony.getPackageManager().addImportantColonyPlayer((ServerPlayer) player);
        colony.getPackageManager().addCloseSubscriber((ServerPlayer) player);

        Log.getLogger().info(String.format("New Colony Id: %d by %s", colony.getID(), player.getName().getString()));

        if (colony.getWorld() == null)
        {
            Log.getLogger().error("Unable to claim chunks because of the missing world in the colony, please report this to the mod authors!", new Exception());
            return null;
        }

        ChunkDataHelper.claimColonyChunks(w, true, (Colony) colony, colony.getCenter());
        ownerIndex.add(colony);
        markOwnerDirty(colony.getPermissions().getOwner());
        return colony;
    }

    /**
     * The blueprint of the town hall of a faction colony, the first level of the standard town hall of every style pack.
     */
    public static final String FACTION_TOWN_HALL_BLUEPRINT = "fundamentals/townhall1.blueprint";

    @Override
    @Nullable
    public IColony createFactionColony(
      @NotNull final ServerLevel w,
      @NotNull final BlockPos townHallPos,
      @NotNull final String factionId,
      @NotNull final Component name,
      @NotNull final String stylePack,
      final int teamColour,
      @Nullable final BannerPatternLayers banner)
    {
        if (factionId.isBlank())
        {
            throw new IllegalArgumentException("A faction colony needs a faction id");
        }
        final IServerColonySaveData cap = IServerColonySaveData.getOrComputeSaveData(w);
        if (cap == null)
        {
            Log.getLogger().warn(MISSING_WORLD_CAP_MESSAGE);
            return null;
        }

        if (!isFarEnoughFromColonies(w, townHallPos))
        {
            Log.getLogger().info("Faction colony of {} not created at {}: too close to a colony or the claim does not fit.", factionId, townHallPos);
            return null;
        }

        if (!(w.getBlockState(townHallPos).getBlock() instanceof BlockHutTownHall))
        {
            w.setBlock(townHallPos, ModBlocks.blockHutTownHall.defaultBlockState(), 3);
        }
        if (!(w.getBlockEntity(townHallPos) instanceof final TileEntityColonyBuilding hut))
        {
            Log.getLogger().warn("Faction colony of {} not created at {}: the town hall has no block entity.", factionId, townHallPos);
            return null;
        }
        hut.setPackName(stylePack);
        hut.setBlueprintPath(FACTION_TOWN_HALL_BLUEPRINT);

        final IColony colony = cap.createColony(w, name.getString(), townHallPos);
        colony.setStructurePack(stylePack);
        colony.setName(name.getString());
        ((Permissions) colony.getPermissions()).setFactionOwner(factionId);
        colony.setColonyColor(nearestChatColour(teamColour));
        if (banner != null)
        {
            colony.setColonyFlag(banner);
        }

        Log.getLogger().info(String.format("New faction colony Id: %d of %s", colony.getID(), factionId));

        if (colony.getWorld() == null)
        {
            Log.getLogger().error("Unable to claim chunks because of the missing world in the colony, please report this to the mod authors!", new Exception());
            return null;
        }

        ChunkDataHelper.claimColonyChunks(w, true, (Colony) colony, colony.getCenter());
        colony.getServerBuildingManager().addNewBuilding(hut, w);
        IMinecoloniesAPI.getInstance().getEventBus().post(new ColonyCreatedModEvent(colony));
        return colony;
    }

    /**
     * The chat colour closest to an RGB colour.
     */
    private static ChatFormatting nearestChatColour(final int rgb)
    {
        ChatFormatting best = ChatFormatting.WHITE;
        long bestDistance = Long.MAX_VALUE;
        for (final ChatFormatting format : ChatFormatting.values())
        {
            final TextColor textColor = Style.EMPTY.applyFormat(format).getColor();
            if (textColor == null)
            {
                continue;
            }
            final int c = textColor.getValue();
            final long dr = ((rgb >> 16) & 0xFF) - ((c >> 16) & 0xFF);
            final long dg = ((rgb >> 8) & 0xFF) - ((c >> 8) & 0xFF);
            final long db = (rgb & 0xFF) - (c & 0xFF);
            final long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = format;
            }
        }
        return best;
    }

    @Override
    @NotNull
    public com.minecolonies.api.colony.faction.IFactionColonyActions getFactionColonyActions()
    {
        return FactionColonyActions.INSTANCE;
    }

    @Override
    @NotNull
    public List<IColony> getFactionColonies(@NotNull final String factionId)
    {
        final List<IColony> result = new ArrayList<>();
        if (ServerLifecycleHooks.getCurrentServer() == null)
        {
            return result;
        }
        for (final IColony colony : getAllColonies())
        {
            if (factionId.equals(colony.getPermissions().getFactionId()))
            {
                result.add(colony);
            }
        }
        result.sort(Comparator.comparingLong(IColony::getFoundedTime).thenComparingInt(IColony::getID));
        return result;
    }

    @Override
    public void deleteColonyByWorld(final int id, final boolean canDestroy, final ServerLevel world)
    {
        deleteColony(getColonyByWorld(id, world), canDestroy);
    }

    @Override
    public void deleteColonyByDimension(final int id, final boolean canDestroy, final ResourceKey<Level> dimension)
    {
        deleteColony(getColonyByDimension(id, dimension), canDestroy);
    }

    /**
     * Delete a colony and purge all buildings and citizens.
     *
     * @param iColony    the colony to destroy.
     * @param canDestroy if the building outlines should be destroyed as well.
     */
    private void deleteColony(@Nullable final IColony iColony, final boolean canDestroy)
    {
        if (!(iColony instanceof final Colony colony))
        {
            return;
        }

        final int id = colony.getID();
        final ServerLevel world = colony.getWorld();

        if (world == null)
        {
            Log.getLogger().warn("Deleting Colony " + id + " errored: World is Null");
            return;
        }

        try
        {
            ChunkDataHelper.claimColonyChunks(world, false, colony, colony.getCenter());
            Log.getLogger().info("Removing citizens for " + id);
            for (final ICitizenData citizenData : new ArrayList<>(colony.getCitizenManager().getCitizens()))
            {
                Log.getLogger().info("Kill Citizen " + citizenData.getName());
                citizenData.getEntity().ifPresent(entityCitizen -> entityCitizen.die(world.damageSources().source(DamageSourceKeys.CONSOLE)));
            }

            Log.getLogger().info("Removing buildings for " + id);
            for (final IBuilding building : new ArrayList<>(colony.getServerBuildingManager().getBuildings().values()))
            {
                try
                {
                    final BlockPos location = building.getPosition();
                    Log.getLogger().info("Delete Building at " + location);
                    if (canDestroy)
                    {
                        building.deconstruct();
                    }
                    building.destroy();
                    if (world.getBlockState(location).getBlock() instanceof AbstractBlockHut)
                    {
                        Log.getLogger().info("Found Block, deleting " + world.getBlockState(location).getBlock());
                        world.removeBlock(location, false);
                    }
                }
                catch (final Exception ex)
                {
                    Log.getLogger().warn("Something went wrong deleting a building while deleting the colony!", ex);
                }
            }

            try
            {
                NeoForge.EVENT_BUS.unregister(colony.getEventHandler());
            }
            catch (final NullPointerException e)
            {
                Log.getLogger().warn("Can't unregister the event handler twice");
            }

            Log.getLogger().info("Deleting colony: " + colony.getID());

            final IServerColonySaveData cap = getColonySaveData(world);
            if (cap == null)
            {
                Log.getLogger().warn(MISSING_WORLD_CAP_MESSAGE);
                return;
            }

            IMinecoloniesAPI.getInstance().getEventBus().post(new ColonyDeletedModEvent(colony));
            final UUID formerOwner = colony.getPermissions().getOwner();
            ownerIndex.remove(colony.getDimension(), id, formerOwner);
            dropSelection(formerOwner, colony.getDimension(), id);
            markOwnerDirty(formerOwner);
            cap.deleteColony(id);
            BackUpHelper.markColonyDeleted(colony.getID(), colony.getDimension());
            colony.getImportantMessageEntityPlayers()
              .forEach(player -> new ColonyViewRemoveMessage(colony.getID(), colony.getDimension()).sendToPlayer((ServerPlayer) player));
            Log.getLogger().info("Successfully deleted colony: " + id);
        }
        catch (final RuntimeException e)
        {
            Log.getLogger().warn("Deleting Colony " + id + " errored:", e);
        }
    }

    @Override
    public void removeColonyView(final int id, final ResourceKey<Level> dimension)
    {
        if (colonyViews.containsKey(dimension))
        {
            colonyViews.get(dimension).remove(id);
        }
    }

    @Override
    @Nullable
    public IColony getColonyByWorld(final int id, final Level world)
    {
        if (!(world instanceof final ServerLevel serverLevel))
        {
            return null;
        }
        final IServerColonySaveData cap = getColonySaveData(serverLevel);
        if (cap == null)
        {
            Log.getLogger().warn(MISSING_WORLD_CAP_MESSAGE);
            return null;
        }
        return cap.getColony(id);
    }

    @Override
    @Nullable
    public IColony getColonyByDimension(final int id, final ResourceKey<Level> registryKey)
    {
        final ServerLevel world = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer().getLevel(registryKey);
        if (world == null)
        {
            return null;
        }
        final IServerColonySaveData cap = getColonySaveData(world);
        if (cap == null)
        {
            Log.getLogger().warn(MISSING_WORLD_CAP_MESSAGE);
            return null;
        }
        return cap.getColony(id);
    }

    @Override
    public IBuilding getBuilding(@NotNull final Level w, @NotNull final BlockPos pos)
    {
        @Nullable final IColony colony = getColonyByPosFromWorld(w, pos);
        if (colony != null)
        {
            final IBuilding building = colony.getServerBuildingManager().getBuilding(pos);
            if (building != null)
            {
                return building;
            }
        }


        //  Fallback - there might be a AbstractBuilding for this block, but it's outside of it's owning colony's radius.
        for (@NotNull final IColony otherColony : getColonies(w))
        {
            final IBuilding building = otherColony.getServerBuildingManager().getBuilding(pos);
            if (building != null)
            {
                return building;
            }
        }

        return null;
    }

    @Override
    public IColony getColonyByPosFromWorld(@Nullable final Level w, @NotNull final BlockPos pos)
    {
        if (w == null)
        {
            return null;
        }
        final LevelChunk centralChunk = w.getChunkAt(pos);
        final int id = ColonyUtils.getOwningColony(centralChunk);
        if (id == NO_COLONY_ID)
        {
            return null;
        }
        return getColonyByWorld(id, w);
    }

    @Override
    public IColony getColonyByPosFromDim(final ResourceKey<Level> registryKey, @NotNull final BlockPos pos)
    {
        return getColonyByPosFromWorld(ServerLifecycleHooks.getCurrentServer().getLevel(registryKey), pos);
    }

    @Override
    public boolean isFarEnoughFromColonies(@NotNull final Level w, @NotNull final BlockPos pos)
    {
        final int blockRange = Math.max(MineColonies.getConfig().getServer().minColonyDistance.get(), getConfig().getServer().initialColonySize.get()) << 4;
        final IColony closest = getClosestColony(w, pos);

        if (closest != null && BlockPosUtil.getDistance(pos, closest.getCenter()) < blockRange)
        {
            return false;
        }

        if (w.isClientSide())
        {
            return true;
        }

        return ChunkDataHelper.canClaimChunksInRange((ServerLevel) w, pos, getConfig().getServer().initialColonySize.get());
    }

    @Override
    @NotNull
    public List<IColony> getColonies(@NotNull final Level w)
    {
        if (!(w instanceof final ServerLevel serverLevel))
        {
            return Collections.emptyList();
        }
        final IServerColonySaveData cap = getColonySaveData(serverLevel);
        if (cap == null)
        {
            Log.getLogger().warn(MISSING_WORLD_CAP_MESSAGE);
            return Collections.emptyList();
        }
        return cap.getColonies();
    }

    @Override
    @NotNull
    public List<IColony> getAllColonies()
    {
        final List<IColony> allColonies = new ArrayList<>();
        for (final ServerLevel world : net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer().getAllLevels())
        {
            final IServerColonySaveData cap = getColonySaveData(world);
            if (cap != null)
            {
                allColonies.addAll(cap.getColonies());
            }
        }
        return allColonies;
    }

    @Override
    @NotNull
    public List<IColony> getColoniesAbandonedSince(final int abandonedSince)
    {
        final List<IColony> sortedList = new ArrayList<>();
        for (final IColony colony : getAllColonies())
        {
            if (colony.getLastContactInHours() >= abandonedSince)
            {
                sortedList.add(colony);
            }
        }

        return sortedList;
    }

    @Override
    public IBuildingView getBuildingView(final ResourceKey<Level> dimension, final BlockPos pos)
    {
        if (colonyViews.containsKey(dimension))
        {
            //  On client we will just check all known views
            for (@NotNull final IColonyView colony : colonyViews.get(dimension))
            {
                final IBuildingView building = colony.getClientBuildingManager().getBuilding(pos);
                if (building != null)
                {
                    return building;
                }
            }
        }

        return null;
    }

    @Override
    @NotNull
    public List<IColony> getIColonies(@NotNull final Level w)
    {
        return w.isClientSide() ? new ArrayList<>(getColonyViews(w)) : getColonies(w);
    }

    @Override
    @Nullable
    public IColony getIColony(@NotNull final Level w, @NotNull final BlockPos pos)
    {
        return w.isClientSide() ? getColonyView(w, pos) : getColonyByPosFromWorld(w, pos);
    }

    @Override
    public void openReactivationWindow(final BlockPos pos)
    {
        new WindowReactivateBuilding(pos).open();
    }

    @Override
    @NotNull
    public List<IColonyView> getColonyViews(@NotNull final Level w)
    {
        // this might be a subset of colonies since it's only those known to the player right now
        final ColonyList<IColonyView> colonies = colonyViews.get(w.dimension());
        return colonies == null ? List.of() : colonies.getCopyAsList();
    }

    /**
     * Get Colony that contains a given (x, y, z).
     *
     * @param w   World.
     * @param pos coordinates.
     * @return returns the view belonging to the colony at x, y, z.
     */
    @Override
    public IColonyView getColonyView(@NotNull final Level w, @NotNull final BlockPos pos)
    {
        final LevelChunk centralChunk = w.getChunkAt(pos);

        final int id = ColonyUtils.getOwningColony(centralChunk);
        if (id == 0)
        {
            return null;
        }
        return getColonyView(id, w.dimension());
    }

    @Override
    @Nullable
    public IColony getClosestIColony(@NotNull final Level w, @NotNull final BlockPos pos)
    {
        return w.isClientSide() ? getClosestColonyView(w, pos) : getClosestColony(w, pos);
    }

    @Override
    @Nullable
    public IColonyView getClosestColonyView(@Nullable final Level w, @Nullable final BlockPos pos)
    {
        if (w == null || pos == null)
        {
            return null;
        }

        final LevelChunk chunk = w.getChunkAt(pos);
        final int owningColony = ColonyUtils.getOwningColony(chunk);
        if (owningColony != NO_COLONY_ID)
        {
            return getColonyView(owningColony, w.dimension());
        }

        @Nullable IColonyView closestColony = null;
        long closestDist = Long.MAX_VALUE;

        if (colonyViews.containsKey(w.dimension()))
        {
            for (@NotNull final IColonyView c : colonyViews.get(w.dimension()))
            {
                if (c.getDimension() == w.dimension() && c.getCenter() != null)
                {
                    final long dist = c.getDistanceSquared(pos);
                    if (dist < closestDist)
                    {
                        closestColony = c;
                        closestDist = dist;
                    }
                }
            }
        }

        return closestColony;
    }

    @Override
    public IColony getClosestColony(@NotNull final Level w, @NotNull final BlockPos pos)
    {
        final LevelChunk chunk = w.getChunkAt(pos);
        final int owningColony = ColonyUtils.getOwningColony(chunk);
        if (owningColony != NO_COLONY_ID)
        {
            return getColonyByWorld(owningColony, w);
        }

        @Nullable IColony closestColony = null;
        long closestDist = Long.MAX_VALUE;

        for (@NotNull final IColony c : getColonies(w))
        {
            if (c.getDimension() == w.dimension())
            {
                final long dist = c.getDistanceSquared(pos);
                if (dist < closestDist)
                {
                    closestColony = c;
                    closestDist = dist;
                }
            }
        }

        return closestColony;
    }

    @Override
    @Nullable
    public IColony getIColonyByOwner(@NotNull final Level w, @NotNull final Player owner)
    {
        return getIColonyByOwner(w, w.isClientSide() ? owner.getUUID() : owner.nameAndId().id());
    }

    @Override
    @Nullable
    public IColony getIColonyByOwner(@NotNull final Level w, final UUID owner)
    {
        if (owner == null)
        {
            return null;
        }
        return w.isClientSide() ? getColonyViewByOwner(owner, w.dimension()) : getColonyByOwner(owner, w.dimension());
    }

    /**
     * Returns a ColonyView with specific owner: the selected colony if it is in the dimension, otherwise the owned colony
     * in the dimension with the lowest id. Only knows the views the client holds.
     *
     * @param owner     UUID of the owner.
     * @param dimension the dimension id.
     * @return ColonyView.
     */
    private IColony getColonyViewByOwner(final UUID owner, final ResourceKey<Level> dimension)
    {
        IColonyView first = null;
        if (colonyViews.containsKey(dimension))
        {
            final OwnedColonySummary selected = clientOwnedColonies.stream().filter(OwnedColonySummary::selected).findFirst().orElse(null);
            for (@NotNull final IColonyView c : colonyViews.get(dimension))
            {
                final ColonyPlayer p = c.getPlayers().get(owner);
                if (p != null && p.getRank().equals(c.getPermissions().getRankOwner()))
                {
                    if (selected != null && selected.id() == c.getID() && selected.dimension().equals(dimension))
                    {
                        return c;
                    }
                    if (first == null || c.getID() < first.getID())
                    {
                        first = c;
                    }
                }
            }
        }

        return first;
    }

    /**
     * Server: the owner's selected colony if it is in the dimension, otherwise their oldest colony in the dimension.
     */
    @Nullable
    private IColony getColonyByOwner(@NotNull final UUID owner, @NotNull final ResourceKey<Level> dimension)
    {
        final List<IColony> owned = getIColoniesByOwner(owner);
        if (owned.isEmpty())
        {
            return null;
        }
        final IColony selected = selectedOf(owner, owned);
        if (selected != null && selected.getDimension().equals(dimension))
        {
            return selected;
        }
        for (final IColony colony : owned)
        {
            if (colony.getDimension().equals(dimension))
            {
                return colony;
            }
        }
        return null;
    }

    /**
     * Binds the owner index to the current server and builds it for the levels that are not indexed yet.
     *
     * @return the server, null if there is none.
     */
    @Nullable
    private MinecraftServer ensureIndexed()
    {
        final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
        {
            return null;
        }
        ownerIndex.bind(server);
        for (final ServerLevel level : server.getAllLevels())
        {
            if (!ownerIndex.isIndexed(level.dimension()))
            {
                ownerIndex.rebuild(level.dimension(), getColonies(level));
            }
        }
        return server;
    }

    @Override
    @NotNull
    public List<IColony> getIColoniesByOwner(@NotNull final UUID owner)
    {
        final List<IColony> result = new ArrayList<>();
        if (ensureIndexed() == null)
        {
            return result;
        }
        for (final OwnerIndex.Entry entry : ownerIndex.entries(owner))
        {
            final IColony colony = getColonyByDimension(entry.id(), entry.dimension());
            if (colony != null && owner.equals(colony.getPermissions().getOwner()))
            {
                result.add(colony);
            }
            else
            {
                // Stale: deleted, or owned by someone else by a path that did not tell the manager.
                ownerIndex.remove(entry.dimension(), entry.id(), owner);
                if (colony != null)
                {
                    ownerIndex.add(colony);
                }
            }
        }
        return result;
    }

    @Override
    @Nullable
    public IColony getSelectedColony(@NotNull final UUID owner)
    {
        final List<IColony> owned = getIColoniesByOwner(owner);
        return owned.isEmpty() ? null : selectedOf(owner, owned);
    }

    /**
     * The saved selection of the owner if it is one of the owned colonies, else the oldest.
     */
    @Nullable
    private IColony selectedOf(@NotNull final UUID owner, @NotNull final List<IColony> owned)
    {
        final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null)
        {
            final SelectedColonySavedData.Selection selection = SelectedColonySavedData.get(server).get(owner);
            if (selection != null)
            {
                for (final IColony colony : owned)
                {
                    if (colony.getID() == selection.id() && colony.getDimension().equals(selection.dimension()))
                    {
                        return colony;
                    }
                }
            }
        }
        return owned.isEmpty() ? null : owned.get(0);
    }

    @Override
    public boolean setSelectedColony(@NotNull final UUID owner, @NotNull final ResourceKey<Level> dim, final int id)
    {
        final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
        {
            return false;
        }
        for (final IColony colony : getIColoniesByOwner(owner))
        {
            if (colony.getID() == id && colony.getDimension().equals(dim))
            {
                SelectedColonySavedData.get(server).set(owner, new SelectedColonySavedData.Selection(dim, id));
                markOwnerDirty(owner);
                return true;
            }
        }
        return false;
    }

    @Override
    public int getMaxColoniesPerPlayer()
    {
        return ColonyLimits.maxPerPlayer();
    }

    @Override
    @NotNull
    public Optional<Component> checkFounding(@NotNull final ServerLevel level, @NotNull final BlockPos pos, @NotNull final Player player, final boolean preview)
    {
        final int owned = getIColoniesByOwner(player.getUUID()).size();
        final int max = getMaxColoniesPerPlayer();
        if (owned >= max)
        {
            return Optional.of(Component.translatable(COLONY_LIMIT_REACHED, max));
        }

        final ColonyFoundingEvent event = new ColonyFoundingEvent(player, level, pos, owned, preview);
        IMinecoloniesAPI.getInstance().getEventBus().post(event);
        if (event.isCanceled())
        {
            final Component reason = event.getCancelMessage();
            return Optional.of(reason != null ? reason : Component.translatable(COLONY_FOUNDING_VETOED));
        }
        return Optional.empty();
    }

    @Override
    @NotNull
    public List<OwnedColonySummary> getOwnedColonySummaries()
    {
        return clientOwnedColonies;
    }

    @Override
    @NotNull
    public List<OwnedColonySummary> computeOwnedColonySummaries(@NotNull final UUID owner)
    {
        final List<IColony> owned = getIColoniesByOwner(owner);
        final IColony selected = owned.isEmpty() ? null : selectedOf(owner, owned);
        final List<OwnedColonySummary> result = new ArrayList<>(owned.size());
        for (final IColony colony : owned)
        {
            final BlockPos townHall = colony.getServerBuildingManager().getTownHall() != null
                                        ? colony.getServerBuildingManager().getTownHall().getPosition()
                                        : colony.getCenter();
            final TextColor color = Style.EMPTY.applyFormat(colony.getTeamColonyColor()).getColor();
            result.add(new OwnedColonySummary(colony.getID(),
              colony.getDimension(),
              colony.getName(),
              townHall,
              colony.getCitizenManager().getCurrentCitizenCount(),
              color == null ? 0xFFFFFF : color.getValue(),
              colony.getColonyFlag(),
              colony == selected));
        }
        return result;
    }

    @Override
    public void syncOwnedColonies(@NotNull final ServerPlayer player, final boolean force)
    {
        final UUID uuid = player.getUUID();
        final List<OwnedColonySummary> now = List.copyOf(computeOwnedColonySummaries(uuid));
        final List<OwnedColonySummary> last = lastSentOwned.get(uuid);
        if (!force && (last == null ? now.isEmpty() : now.equals(last)))
        {
            return;
        }
        lastSentOwned.put(uuid, now);
        new OwnedColoniesMessage(now).sendToPlayer(player);
    }

    @Override
    @Nullable
    public List<OwnedColonySummary> getLastSentOwnedColonySummaries(@NotNull final UUID owner)
    {
        return lastSentOwned.get(owner);
    }

    @Override
    public void handleOwnedColoniesMessage(@NotNull final List<OwnedColonySummary> summaries)
    {
        clientOwnedColonies = List.copyOf(summaries);
    }

    @Override
    public void onColonyOwnerChanged(@NotNull final IColony colony, @Nullable final UUID previousOwner)
    {
        if (colony.getDimension() == null)
        {
            return;
        }
        ownerIndex.remove(colony.getDimension(), colony.getID(), previousOwner);
        ownerIndex.add(colony);
        if (previousOwner != null && !previousOwner.equals(colony.getPermissions().getOwner()))
        {
            dropSelection(previousOwner, colony.getDimension(), colony.getID());
        }
        markOwnerDirty(previousOwner);
        markOwnerDirty(colony.getPermissions().getOwner());
    }

    /**
     * A player no longer owns a colony: if it was their selected one, the selection goes (the oldest colony is selected again).
     * Only once the level is indexed, i.e. after loading: the saved data is not touched while colonies are loaded.
     */
    private void dropSelection(@Nullable final UUID owner, @NotNull final ResourceKey<Level> dimension, final int id)
    {
        final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (owner == null || server == null || !ownerIndex.isIndexed(dimension))
        {
            return;
        }
        final SelectedColonySavedData data = SelectedColonySavedData.get(server);
        final SelectedColonySavedData.Selection selection = data.get(owner);
        if (selection != null && selection.id() == id && selection.dimension().equals(dimension))
        {
            data.remove(owner);
        }
    }

    private void markOwnerDirty(@Nullable final UUID owner)
    {
        if (owner != null)
        {
            synchronized (dirtyOwners)
            {
                dirtyOwners.add(owner);
            }
        }
    }

    /**
     * Tells the online players their owned colonies when they changed: at once for owners marked dirty (colony founded,
     * deleted, abandoned, selection changed), and for the rest (name, citizens, flag) every {@value #OWNED_SYNC_INTERVAL} ticks.
     */
    private void syncOwnedColoniesOfOnlinePlayers(@NotNull final MinecraftServer server)
    {
        final List<UUID> dirty;
        synchronized (dirtyOwners)
        {
            dirty = dirtyOwners.isEmpty() ? List.of() : new ArrayList<>(dirtyOwners);
            dirtyOwners.clear();
        }
        for (final UUID uuid : dirty)
        {
            final ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null)
            {
                syncOwnedColonies(player, false);
            }
        }

        if (server.getTickCount() % OWNED_SYNC_INTERVAL == 0)
        {
            lastSentOwned.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
            for (final ServerPlayer player : server.getPlayerList().getPlayers())
            {
                syncOwnedColonies(player, false);
            }
        }
    }

    @Override
    public int getMinimumDistanceBetweenTownHalls()
    {
        //  [TownHall](Radius)+(Padding)+(Radius)[TownHall]
        return getConfig().getServer().minColonyDistance.get() * BLOCKS_PER_CHUNK;
    }

    @Override
    public void onServerTick(@NotNull final ServerTickEvent.Pre event)
    {
        for (@NotNull final IColony c : getAllColonies())
        {
            c.onServerTick(event);
        }
        syncOwnedColoniesOfOnlinePlayers(event.getServer());
    }

    @Override
    public void write(final HolderLookup.Provider provider, @NotNull final CompoundTag compound)
    {
        //Get the colonies NBT tags and store them in a ListNBT.
        final CompoundTag compCompound = new CompoundTag();
        compatibilityManager.write(provider, compCompound);
        compound.put(TAG_COMPATABILITY_MANAGER, compCompound);

        compound.putBoolean(TAG_DISTANCE, true);
        final CompoundTag recipeCompound = new CompoundTag();
        recipeManager.write(provider, recipeCompound);

        compound.put(RECIPE_MANAGER_TAG, recipeCompound);
    }

    // File read for compat/recipe
    @Override
    public void read(final HolderLookup.Provider provider, @NotNull final CompoundTag compound)
    {
        if (compound.contains(TAG_COMPATABILITY_MANAGER))
        {
            compatibilityManager.read(provider, compound.getCompoundOrEmpty(TAG_COMPATABILITY_MANAGER));
        }

        recipeManager.read(provider, compound.getCompoundOrEmpty(RECIPE_MANAGER_TAG));
    }

    @Override
    public void onClientTick(@NotNull final ClientTickEvent.Pre event)
    {
        if (Minecraft.getInstance().level == null && !colonyViews.isEmpty())
        {
            //  Player has left the game, clear the Colony View cache
            colonyViews.clear();
        }
        if (Minecraft.getInstance().level == null && !clientOwnedColonies.isEmpty())
        {
            clientOwnedColonies = List.of();
        }


        if (clientSoundManager == null)
        {
            clientSoundManager = new SoundManager();
        }
        clientSoundManager.tick();
    }

    @Override
    public void onWorldTick(final @NotNull LevelTickEvent.Pre event)
    {
        if (!event.getLevel().isClientSide())
        {
            for (final IColony colony : getColonies(event.getLevel()))
            {
                try
                {
                    colony.onWorldTick(event);
                }
                catch (final Exception ex)
                {
                    Log.getLogger().error("Something went wrong ticking colony: " + colony.getID(), ex);
                }
            }
        }
    }

    @Override
    public void onWorldLoad(@NotNull final Level w)
    {
        if (w instanceof final ServerLevel world)
        {
            for (@NotNull final IColony c : getColonies(world))
            {
                c.onWorldLoad(world);
            }

            IMinecoloniesAPI.getInstance().getEventBus().post(new ColonyManagerLoadedModEvent(this));
        }
    }

    @Override
    public void onWorldUnload(@NotNull final Level world)
    {
        if (!world.isClientSide())
        {
            ownerIndex.dropLevel(world.dimension());
            boolean hasColonies = false;
            for (@NotNull final IColony c : getColonies(world))
            {
                hasColonies = true;
                c.onWorldUnload(world);
            }

            if (hasColonies)
            {
                BackUpHelper.backupColonyData(world.registryAccess());
            }

            IMinecoloniesAPI.getInstance().getEventBus().post(new ColonyManagerUnloadedModEvent(this));
        }
    }

    @Override
    public void handleColonyViewMessage(
      final int colonyId,
      @NotNull final RegistryFriendlyByteBuf colonyData,
      final boolean isNewSubscription,
      final ResourceKey<Level> dim)
    {
        IColonyView view = getColonyView(colonyId, dim);
        if (view == null)
        {
            view = ColonyView.createFromNetwork(colonyId, dim);
            if (colonyViews.containsKey(dim))
            {
                colonyViews.get(dim).add(view);
            }
            else
            {
                final ColonyList<IColonyView> list = new ColonyList<>();
                list.add(view);
                colonyViews.put(dim, list);
            }
        }
        view.handleColonyViewMessage(colonyData, isNewSubscription);

        IMinecoloniesAPI.getInstance().getEventBus().post(new ColonyViewUpdatedModEvent(view));
    }

    @Override
    public IColonyView getColonyView(final int id, final ResourceKey<Level> dimension)
    {
        if (colonyViews.containsKey(dimension))
        {
            return colonyViews.get(dimension).get(id);
        }
        return null;
    }

    @Override
    public void handlePermissionsViewMessage(final int colonyID, @NotNull final RegistryFriendlyByteBuf data, final ResourceKey<Level> dim)
    {
        final IColonyView view = getColonyView(colonyID, dim);
        if (view == null)
        {
            Log.getLogger().error(String.format("Colony view does not exist for ID #%d", colonyID), new Exception());
        }
        else
        {
            view.handlePermissionsViewMessage(data);
        }
    }

    @Override
    public void handleColonyViewCitizensMessage(final int colonyId, final int citizenId, final RegistryFriendlyByteBuf buf, final ResourceKey<Level> dim)
    {
        final IColonyView view = getColonyView(colonyId, dim);
        if (view == null)
        {
            return;
        }
        view.handleColonyViewCitizensMessage(citizenId, buf);
    }

    @Override
    public void handleColonyViewWorkOrderMessage(final int colonyId, final RegistryFriendlyByteBuf buf, final ResourceKey<Level> dim)
    {
        final IColonyView view = getColonyView(colonyId, dim);
        if (view == null)
        {
            return;
        }
        view.handleColonyViewWorkOrderMessage(buf);
    }

    @Override
    public void handleColonyViewRemoveCitizenMessage(final int colonyId, final int citizenId, final ResourceKey<Level> dim)
    {
        final IColonyView view = getColonyView(colonyId, dim);
        if (view != null)
        {
            //  Can legitimately be NULL, because (to keep the code simple and fast), it is
            //  possible to receive a 'remove' notice before receiving the View.
            view.handleColonyViewRemoveCitizenMessage(citizenId);
        }
    }

    @Override
    public void handleColonyBuildingViewMessage(final int colonyId, final BlockPos buildingId, @NotNull final RegistryFriendlyByteBuf buf, final ResourceKey<Level> dim)
    {
        final IColonyView view = getColonyView(colonyId, dim);
        if (view != null)
        {
            view.getClientBuildingManager().handleColonyBuildingViewMessage(buildingId, buf);
        }
        else
        {
            Log.getLogger().error(String.format("Colony view does not exist for ID #%d", colonyId), new Exception());
        }
    }

    @Override
    public void handleColonyViewRemoveBuildingMessage(final int colonyId, final BlockPos buildingId, final ResourceKey<Level> dim)
    {
        final IColonyView view = getColonyView(colonyId, dim);
        if (view != null)
        {
            //  Can legitimately be NULL, because (to keep the code simple and fast), it is
            //  possible to receive a 'remove' notice before receiving the View.
            view.getClientBuildingManager().handleColonyViewRemoveBuildingMessage(buildingId);
        }
    }

    @Override
    public void handleColonyViewRemoveWorkOrderMessage(final int colonyId, final int workOrderId, final ResourceKey<Level> dim)
    {
        final IColonyView view = getColonyView(colonyId, dim);
        if (view != null)
        {
            //  Can legitimately be NULL, because (to keep the code simple and fast), it is
            //  possible to receive a 'remove' notice before receiving the View.
            view.handleColonyViewRemoveWorkOrderMessage(workOrderId);
        }
    }

    @Override
    public boolean isSchematicDownloaded()
    {
        return schematicDownloaded;
    }

    @Override
    public void setSchematicDownloaded(final boolean downloaded)
    {
        schematicDownloaded = downloaded;
    }

    @Override
    public boolean isCoordinateInAnyColony(@NotNull final Level world, final BlockPos pos)
    {
        final LevelChunk centralChunk = world.getChunkAt(pos);
        return ColonyUtils.getOwningColony(centralChunk) != NO_COLONY_ID;
    }

    @Override
    public ICompatibilityManager getCompatibilityManager()
    {
        if (Thread.currentThread().getName().toLowerCase().contains("server"))
        {
            return compatibilityManager;
        }
        else
        {
            return compatibilityManagerClient;
        }
    }

    @Override
    public IRecipeManager getRecipeManager()
    {
        return recipeManager;
    }

    @Override
    public int getTopColonyId()
    {
        int top = 0;
        for (final ServerLevel world : ServerLifecycleHooks.getCurrentServer().getAllLevels())
        {
            final IServerColonySaveData cap = getColonySaveData(world);
            final int tempTop = cap == null ? 0 : cap.getTopID();
            if (tempTop > top)
            {
                top = tempTop;
            }
        }
        return top;
    }

    @Override
    public void resetColonyViews()
    {
        colonyViews.clear();
        clientOwnedColonies = List.of();
        chunkClaimData.clear();
        ClaimRevision.bump();
    }

    @Override
    public void addColonyDirect(final IColony colony, final ServerLevel world)
    {
        final IServerColonySaveData cap = getColonySaveData(world);
        if (cap != null)
        {
            cap.addColony(colony);
            ownerIndex.add(colony);
            markOwnerDirty(colony.getPermissions().getOwner());
        }
    }

    @Override
    public void addClaimData(final IColony colony, final Long2ObjectMap<ChunkClaimData> claimData)
    {
        this.chunkClaimData.computeIfAbsent(colony.getDimension(), (k) -> new Long2ObjectOpenHashMap<>()).putAll(claimData);
        ClaimRevision.bump();
    }

    @Override
    public Map<ChunkPos, IChunkClaimData> getClaimData(final ResourceKey<Level> dimension)
    {
        final Map<Long, ChunkClaimData> map = this.chunkClaimData.computeIfAbsent(dimension, (k) -> new Long2ObjectOpenHashMap<>());
        return Maps.asMap(map.keySet().stream().map(ChunkPos::unpack).collect(Collectors.toSet()),
                p -> map.getOrDefault(p.pack(), null));
    }

    @Nullable
    @Override
    public IChunkClaimData getClaimData(final ResourceKey<Level> dimension, final ChunkPos pos)
    {
        return this.chunkClaimData.computeIfAbsent(dimension, (k) -> new Long2ObjectOpenHashMap<>()).getOrDefault(pos.pack(), null);
    }

    @Override
    public void addNewChunk(final Colony colony, final ChunkPos pos, final ChunkClaimData chunkClaimData)
    {
        this.chunkClaimData.computeIfAbsent(colony.getDimension(), (k) -> new Long2ObjectOpenHashMap<>()).put(pos.pack(), chunkClaimData);
        ClaimRevision.bump();
    }
}
