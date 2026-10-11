package com.minecolonies.api.colony;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.api.colony.claim.ChunkClaimData;
import com.minecolonies.api.colony.claim.IChunkClaimData;
import com.minecolonies.api.colony.permissions.IPermissions;
import com.minecolonies.api.compatibility.ICompatibilityManager;
import com.minecolonies.api.crafting.IRecipeManager;
import com.minecolonies.core.colony.Colony;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;

public interface IColonyManager
{

    static IColonyManager getInstance()
    {
        return IMinecoloniesAPI.getInstance().getColonyManager();
    }

    /**
     * Create a new Colony in the given world and at that location.
     *
     * @param w          World of the colony.
     * @param pos        Coordinate of the center of the colony.
     * @param player     the player that creates the colony - owner.
     * @param colonyName the initial colony name.
     * @param pack       the default pack of the colony.
     * @return the created colony instance.
     */
    @Nullable
    IColony createColony(@NotNull ServerLevel w, BlockPos pos, @NotNull Player player, @NotNull String colonyName, @NotNull String pack);

    /**
     * Creates a colony that belongs to an NPC faction instead of a player (an AI neighbour). Server side, called by a
     * mod that runs the faction; there is no packet or command for it.
     * <p>
     * The colony is owned by {@link IPermissions#factionOwnerId(String)} ({@code UUID.nameUUIDFromBytes("minecolonies:faction:" + factionId)}),
     * {@link IPermissions#isFactionOwned()} is true and {@link IPermissions#getFactionId()} is {@code factionId}. Players are
     * neutral in it unless the {@code factions.defaultPlayerRank} config says otherwise and can never be owner or officer
     * of it. It is not in the owner index ({@link #getIColoniesByOwner}), does not count towards
     * {@link #getMaxColoniesPerPlayer()} and is not a candidate for {@link #getIColonyByOwner}. The claim and distance
     * rules of a normal colony apply: nothing is created if the town hall is closer than {@code minColonyDistance} to
     * another colony or its claim does not fit (the world spawn distance limits of player founding do not apply). The
     * colony is saved, loaded and deleted like any other, and no player has to be online to create it.
     * <p>
     * Sets the town hall block at {@code townHallPos} (an existing town hall block with its block entity is reused) and
     * registers it as the colony's town hall at building level 0, with the blueprint {@code fundamentals/townhall1.blueprint}
     * of the style pack; the faction builds it up through {@link com.minecolonies.api.colony.faction.IFactionColonyActions}.
     * Without a simulation driver ({@link IColony#setSimulationDriver}) the colony only ticks like any colony, while a
     * player is close.
     *
     * @param w            the level; its chunks around the town hall are loaded.
     * @param townHallPos  where the town hall is, the center of the colony.
     * @param factionId    the faction id, not blank.
     * @param name         the colony name.
     * @param stylePack    the structure pack of the colony, e.g. "Minecolonies Original".
     * @param teamColour   the colony colour as RGB; the nearest of the 16 chat colours is used (the colony colour is a chat colour).
     * @param banner       the colony flag, null for the default.
     * @return the colony, null if it could not be created (too close to another colony, claim does not fit, no town hall block entity).
     */
    @Nullable
    IColony createFactionColony(@NotNull ServerLevel w, @NotNull BlockPos townHallPos, @NotNull String factionId, @NotNull Component name, @NotNull String stylePack,
      int teamColour, @Nullable BannerPatternLayers banner);

    /**
     * The actions a governor of a faction colony takes without a player: place a hut, request an upgrade, hire, set
     * settings. See the trust boundary in {@link com.minecolonies.api.colony.faction.IFactionColonyActions}.
     *
     * @return the faction colony actions.
     */
    @NotNull
    com.minecolonies.api.colony.faction.IFactionColonyActions getFactionColonyActions();

    /**
     * The colonies of a faction, in all dimensions, oldest first.
     *
     * @param factionId the faction id.
     * @return the colonies owned by the faction, empty if none.
     */
    @NotNull
    List<IColony> getFactionColonies(@NotNull String factionId);

    /**
     * Delete the colony in a world.
     *
     * @param id         the id of it.
     * @param canDestroy if can destroy the buildings.
     * @param world      the world.
     */
    void deleteColonyByWorld(int id, boolean canDestroy, ServerLevel world);

    /**
     * Delete the colony by dimension.
     *
     * @param id         the id of it.
     * @param canDestroy if can destroy the buildings.
     * @param dimension  the dimension.
     */
    void deleteColonyByDimension(int id, boolean canDestroy, ResourceKey<Level> dimension);

    /**
     * Removes a colony view
     *
     * @param id        the id of the colony.
     * @param dimension the dimension it is in.
     */
    void removeColonyView(int id, ResourceKey<Level> dimension);

    /**
     * Get Colony by UUID.
     *
     * @param id    ID of colony.
     * @param world the world it is in.
     * @return Colony with given ID.
     */
    @Nullable
    IColony getColonyByWorld(int id, Level world);

    /**
     * Get Colony by UUID.
     *
     * @param id        ID of colony.
     * @param dimension the dimension it is in.
     * @return Colony with given ID.
     */
    @Nullable
    IColony getColonyByDimension(int id, ResourceKey<Level> dimension);

    /**
     * Get a AbstractBuilding by a World and coordinates.
     *
     * @param w   World.
     * @param pos Block position.
     * @return AbstractBuilding at the given location.
     */
    IBuilding getBuilding(@NotNull Level w, @NotNull BlockPos pos);

    /**
     * Get colony that contains a given coordinate from world.
     *
     * @param w   World.
     * @param pos coordinates.
     * @return Colony at the given location.
     */
    @Nullable
    IColony getColonyByPosFromWorld(@NotNull Level w, @NotNull BlockPos pos);

    /**
     * Get colony that contains a given coordinate from dimension.
     *
     * @param dim the dimension.
     * @param pos coordinates.
     * @return Colony at the given location.
     */
    IColony getColonyByPosFromDim(ResourceKey<Level> dim, @NotNull BlockPos pos);

    /**
     * Check if a position is too close to another colony to found a new colony.
     *
     * @param w   World.
     * @param pos coordinates.
     * @return true if so.
     */
    boolean isFarEnoughFromColonies(@NotNull Level w, @NotNull BlockPos pos);

    /**
     * Get all colonies in this world.
     *
     * @param w World.
     * @return a list of colonies.
     */
    @NotNull
    List<IColony> getColonies(@NotNull Level w);

    /**
     * Get all colonies in all worlds.
     *
     * @return a list of colonies.
     */
    @NotNull
    List<IColony> getAllColonies();

    /**
     * Get all colonies in all worlds.
     *
     * @param abandonedSince time in hours since the last contact.
     * @return a list of colonies.
     */
    @NotNull
    List<IColony> getColoniesAbandonedSince(int abandonedSince);

    /**
     * Get a AbstractBuilding by position.
     *
     * @param pos       Block position.
     * @param dimension the dimension it is in.
     * @return Returns the view belonging to the building at (x, y, z).
     */
    IBuildingView getBuildingView(final ResourceKey<Level> dimension, BlockPos pos);

    /**
     * Get all colonies in this world.  (Side neutral; on clients it returns the subset of views known to the player.)
     *
     * @param w World.
     * @return a list of colonies.
     */
    @NotNull
    List<IColony> getIColonies(@NotNull Level w);

    /**
     * Side neutral method to get colony. On clients it returns the view. On servers it returns the colony itself.
     *
     * @param w   World.
     * @param pos coordinates.
     * @return View of colony or colony itself depending on side.
     */
    @Nullable
    IColony getIColony(@NotNull Level w, @NotNull BlockPos pos);

    /**
     * Get all colony views in this world known to the current player.
     *
     * @param w World.
     * @return a list of colony views.
     */
    @NotNull
    List<IColonyView> getColonyViews(@NotNull Level w);

    /**
     * Gets the colony view aat the given position
     *
     * @param w
     * @param pos
     * @return IColonyView
     */
    IColonyView getColonyView(@NotNull Level w, @NotNull BlockPos pos);

    /**
     * Side neutral method to get colony. On clients it returns the view. On servers it returns the colony itself. {@link #getClosestColony(Level, BlockPos)}
     *
     * @param w   World.
     * @param pos Block position.
     * @return View of colony or colony itself depending on side, closest to coordinates.
     */
    @Nullable
    IColony getClosestIColony(@NotNull Level w, @NotNull BlockPos pos);

    /**
     * Returns the closest view {@link #getColonyView}.
     *
     * @param w   World.
     * @param pos Block Position.
     * @return View of the closest colony.
     */
    // TODO: Check usages, some of the probably want to avoid getting a distant colony and use getColonyView at pos instead
    @Nullable
    IColonyView getClosestColonyView(@Nullable Level w, @Nullable BlockPos pos);

    /**
     * Get closest colony by x,y,z.
     *
     * @param w   World.
     * @param pos coordinates.
     * @return Colony closest to coordinates.
     */
    IColony getClosestColony(@NotNull Level w, @NotNull BlockPos pos);

    /**
     * Side neutral method to get colony. On clients it returns the view. On servers it returns the colony itself.
     * <p>
     * Returns a colony or view with the given Player as owner, in the dimension of the world: the player's selected
     * colony ({@link #getSelectedColony}) if it is in this dimension, otherwise the oldest colony the player owns here,
     * otherwise null. A player may own several colonies (see {@link #getIColoniesByOwner}); this keeps callers that
     * want one colony of the player working. On clients only the views the client holds are known.
     *
     * @param w     World.
     * @param owner Entity Player.
     * @return IColony belonging to specific player.
     */
    @Nullable
    IColony getIColonyByOwner(@NotNull Level w, @NotNull Player owner);

    /**
     * Side neutral method to get colony. On clients it returns the view. On servers it returns the colony itself.
     * <p>
     * Returns a colony or view with given Player as owner, in the dimension of the world: the player's selected colony
     * ({@link #getSelectedColony}) if it is in this dimension, otherwise the oldest colony the player owns here,
     * otherwise null. On clients only the views the client holds are known.
     *
     * @param w     World
     * @param owner UUID of the owner.
     * @return IColony belonging to specific player.
     */
    @Nullable
    IColony getIColonyByOwner(@NotNull Level w, UUID owner);

    /**
     * Server side. All colonies a player owns, in every dimension, oldest colony first. Backed by an owner index that
     * is built per level on first use and kept current when colonies are created, deleted or change owner, so the cost
     * is the size of the answer, not a scan of all colonies.
     *
     * @param owner UUID of the owner.
     * @return a new list, empty if the player owns none.
     */
    @NotNull
    List<IColony> getIColoniesByOwner(@NotNull UUID owner);

    /**
     * Server side. The colony the player has selected: the one set with {@link #setSelectedColony}, or, for a player
     * without a (still valid) selection, the oldest colony the player owns. The selection is saved per player.
     *
     * @param owner UUID of the owner.
     * @return the selected colony, null if the player owns none.
     */
    @Nullable
    IColony getSelectedColony(@NotNull UUID owner);

    /**
     * Server side. Selects one of the colonies the player owns, and tells the player's client (see
     * {@link OwnedColonySummary}). The selection is saved. Deleting or abandoning the selected colony drops the
     * selection (the oldest colony is selected again).
     *
     * @param owner UUID of the owner.
     * @param dim   dimension of the colony.
     * @param id    colony id.
     * @return true if the colony exists and is owned by the player, false (nothing changes) otherwise.
     */
    boolean setSelectedColony(@NotNull UUID owner, @NotNull ResourceKey<Level> dim, int id);

    /**
     * How many colonies one player may own: the {@code colonies.maxPerPlayer} config, 1 by default.
     *
     * @return the limit, at least 1.
     */
    int getMaxColoniesPerPlayer();

    /**
     * Server side. Asks whether the player may found one more colony at the position: refuses when the player owns
     * {@link #getMaxColoniesPerPlayer()} colonies already, and posts the cancellable
     * {@link com.minecolonies.api.eventbus.events.colony.ColonyFoundingEvent} for everything else to veto. Distance,
     * claim and spawn rules are not part of this check (they are checked where the founding happens, as before).
     *
     * @param level   the level of the new colony.
     * @param pos     the town hall position.
     * @param player  the founding player.
     * @param preview true if this only decides whether the founding screen opens (the event is then marked preview).
     * @return empty if the player may found the colony, otherwise the reason to show the player.
     */
    @NotNull
    Optional<Component> checkFounding(@NotNull ServerLevel level, @NotNull BlockPos pos, @NotNull Player player, boolean preview);

    /**
     * Client side. The colonies the local player owns, as last sent by the server (on login and on every change),
     * also for colonies the client is not subscribed to. Empty on a dedicated server.
     *
     * @return an unmodifiable list, oldest colony first; the selected colony has {@link OwnedColonySummary#selected()} set.
     */
    @NotNull
    List<OwnedColonySummary> getOwnedColonySummaries();

    /**
     * Server side. The summaries {@link #getOwnedColonySummaries()} shows a player, built from the current state.
     *
     * @param owner UUID of the owner.
     * @return the summaries, oldest colony first.
     */
    @NotNull
    List<OwnedColonySummary> computeOwnedColonySummaries(@NotNull UUID owner);

    /**
     * Server side. Sends a player their owned colonies, when they differ from what the player got last (or always if forced).
     * Done on login, when the owned colonies or the selection change, and by a periodic check for the rest (name, citizens, flag).
     *
     * @param player the player.
     * @param force  send even if nothing changed.
     */
    void syncOwnedColonies(@NotNull ServerPlayer player, boolean force);

    /**
     * Server side. What the player was sent last by {@link #syncOwnedColonies}.
     *
     * @param owner UUID of the player.
     * @return the list, null if nothing was sent since the player logged in.
     */
    @Nullable
    List<OwnedColonySummary> getLastSentOwnedColonySummaries(@NotNull UUID owner);

    /**
     * Client side. Stores what the server sent.
     *
     * @param summaries the owned colonies.
     */
    void handleOwnedColoniesMessage(@NotNull List<OwnedColonySummary> summaries);

    /**
     * Called by the permissions when the owner of a colony changes (founding, setowner, abandon), to keep the owner index current.
     *
     * @param colony        the colony.
     * @param previousOwner its owner before, null if it had none.
     */
    void onColonyOwnerChanged(@NotNull IColony colony, @Nullable UUID previousOwner);

    /**
     * Returns the minimum distance between two town halls, to not make colonies collide.
     *
     * @return Minimum town hall distance.
     */
    int getMinimumDistanceBetweenTownHalls();

    /**
     * On server tick, tick every Colony. NOTE: Review this for performance.
     *
     * @param event {@link net.neoforged.neoforge.event.tick.ServerTickEvent}
     */
    void onServerTick(@NotNull ServerTickEvent.Pre event);

    /**
     * Write colonies to NBT data for saving.
     *
     * @param compound NBT-Tag.
     */
    void write(final HolderLookup.Provider provider, @NotNull CompoundTag compound);

    /**
     * Read Colonies from saved NBT data.
     *
     * @param compound NBT Tag.
     */
    void read(final HolderLookup.Provider provider, @NotNull CompoundTag compound);

    /**
     * On Client tick, clears views when player left.
     *
     * @param event {@link net.neoforged.neoforge.client.event.ClientTickEvent}.
     */
    void onClientTick(@NotNull ClientTickEvent.Pre event);

    /**
     * On world tick, tick every Colony in that world. NOTE: Review this for performance.
     */
    void onWorldTick(@NotNull LevelTickEvent.Pre event);

    /**
     * When a world is loaded, Colonies in that world need to grab the reference to the World. Additionally, when loading the first world, load the manager data.
     *
     * @param world World.
     */
    void onWorldLoad(@NotNull Level world);

    /**
     * When a world unloads, all colonies in that world are informed. Additionally, when the last world is unloaded, delete all colonies.
     *
     * @param world World.
     */
    void onWorldUnload(@NotNull Level world);

    /**
     * Sends view message to the right view.
     *
     * @param colonyId          ID of the colony.
     * @param colonyData        {@link RegistryFriendlyByteBuf} with colony data.
     * @param isNewSubscription whether this is a new subscription or not.
     * @param dim               the dimension.
     */
    void handleColonyViewMessage(int colonyId, @NotNull RegistryFriendlyByteBuf colonyData, boolean isNewSubscription, ResourceKey<Level> dim);

    /**
     * Get IColonyView by ID.
     *
     * @param id        ID of colony.
     * @param dimension the dimension id.
     * @return The IColonyView belonging to the colony.
     */
    IColonyView getColonyView(int id, final ResourceKey<Level> dimension);

    /**
     * Returns result of {@link IColonyView#handlePermissionsViewMessage(RegistryFriendlyByteBuf)} if {@link #getColonyView(int, ResourceKey)}. gives a not-null result. If {@link #getColonyView(int,
     * ResourceKey)} is null, returns null.
     *
     * @param colonyID ID of the colony.
     * @param data     {@link RegistryFriendlyByteBuf} with colony data.
     * @param dim      the dimension.
     */
    void handlePermissionsViewMessage(int colonyID, @NotNull RegistryFriendlyByteBuf data, ResourceKey<Level> dim);

    /**
     * Returns result of {@link IColonyView#handleColonyViewCitizensMessage(int, RegistryFriendlyByteBuf)} if {@link #getColonyView(int, ResourceKey)} gives a not-null result. If {@link
     * #getColonyView(int, ResourceKey)} is null, returns null.
     *
     * @param colonyId  ID of the colony.
     * @param citizenId ID of the citizen.
     * @param buf       {@link RegistryFriendlyByteBuf} with colony data.
     * @param dim       the dimension.
     */
    void handleColonyViewCitizensMessage(int colonyId, int citizenId, RegistryFriendlyByteBuf buf, ResourceKey<Level> dim);

    /**
     * Returns result of {@link IColonyView#handleColonyViewWorkOrderMessage(RegistryFriendlyByteBuf)} (int, ByteBuf)} if {@link #getColonyView(int, ResourceKey)} gives a not-null result. If {@link
     * #getColonyView(int, ResourceKey)} is null, returns null.
     *
     * @param colonyId ID of the colony.
     * @param buf      {@link RegistryFriendlyByteBuf} with colony data.
     * @param dim      the dimension.
     */
    void handleColonyViewWorkOrderMessage(int colonyId, RegistryFriendlyByteBuf buf, ResourceKey<Level> dim);

    /**
     * Returns result of {@link IColonyView#handleColonyViewRemoveCitizenMessage(int)} if {@link #getColonyView(int, ResourceKey)} gives a not-null result. If {@link #getColonyView(int,
     * ResourceKey)} is null, returns null.
     *
     * @param colonyId  ID of the colony.
     * @param citizenId ID of the citizen.
     * @param dim       the dimension.
     */
    void handleColonyViewRemoveCitizenMessage(int colonyId, int citizenId, ResourceKey<Level> dim);

    /**
     * Returns result of {@link IColonyView#handleColonyBuildingViewMessage(BlockPos, RegistryFriendlyByteBuf)} if {@link #getColonyView(int, ResourceKey)} gives a not-null result. If {@link
     * #getColonyView(int, ResourceKey)} is null, returns null.
     *
     * @param colonyId   ID of the colony.
     * @param buildingId ID of the building.
     * @param buf        {@link RegistryFriendlyByteBuf} with colony data.
     * @param dim        the dimension.
     */
    void handleColonyBuildingViewMessage(int colonyId, BlockPos buildingId, @NotNull RegistryFriendlyByteBuf buf, ResourceKey<Level> dim);

    /**
     * Returns result of {@link IColonyView#handleColonyViewRemoveBuildingMessage(BlockPos)} if {@link #getColonyView(int, ResourceKey)} gives a not-null result. If {@link
     * #getColonyView(int, ResourceKey)} is null, returns null.
     *
     * @param colonyId   ID of the colony.
     * @param buildingId ID of the building.
     * @param dim        the dimension.
     */
    void handleColonyViewRemoveBuildingMessage(int colonyId, final BlockPos buildingId, final ResourceKey<Level> dim);

    /**
     * Returns result of {@link IColonyView#handleColonyViewRemoveWorkOrderMessage(int)} if {@link #getColonyView(int, ResourceKey)} gives a not-null result. If {@link #getColonyView(int,
     * ResourceKey)} is null, returns null.
     *
     * @param colonyId    ID of the colony.
     * @param workOrderId ID of the workOrder.
     * @param dim         the dimension.
     */
    void handleColonyViewRemoveWorkOrderMessage(int colonyId, int workOrderId, ResourceKey<Level> dim);

    /**
     * Whether or not a new schematic have been downloaded.
     *
     * @return True if a new schematic have been received.
     */
    boolean isSchematicDownloaded();

    /**
     * Set the schematic downloaded
     *
     * @param downloaded True if a new schematic have been received.
     */
    void setSchematicDownloaded(boolean downloaded);

    /**
     * Check if a given coordinate is inside any other colony.
     *
     * @param world the world to check in.
     * @param pos   the position to check.
     * @return true if a colony has been found.
     */
    boolean isCoordinateInAnyColony(@NotNull Level world, BlockPos pos);

    /**
     * Get an instance of the compatibilityManager.
     *
     * @return the manager.
     */
    ICompatibilityManager getCompatibilityManager();

    /**
     * Getter for the recipeManager.
     *
     * @return an IRecipeManager.
     */
    IRecipeManager getRecipeManager();

    /**
     * Get the top colony id of all colonies.
     *
     * @return the top id.
     */
    int getTopColonyId();

    /**
     * Reset all colony views on login to new world.
     */
    void resetColonyViews();

    /**
     * Open the new reactivation window.
     * @param pos the pos to open it at.
     */
    void openReactivationWindow(final BlockPos pos);

    /**
     * Adds colony directly to cap. Use only during loading!
     *
     * @param colony loaded colony
     */
    void addColonyDirect(IColony colony, ServerLevel world);

    /**
     * Add claim data of a colony.
     * @param colony the colony from which to add the claim data.
     * @param claimData the claim data to add.
     */
    void addClaimData(IColony colony, Long2ObjectMap<ChunkClaimData> claimData);

    /**
     * Get the claim data for the whole dimension.
     * @param dimension the dim.
     * @return the claim data.
     */
    Map<ChunkPos, IChunkClaimData> getClaimData(final ResourceKey<Level> dimension);

    /**
     * Get the claim data for a dimension and pos.
     * @param dimension the dim.
     * @param pos the pos.
     * @return the claim data.
     */
    IChunkClaimData getClaimData(ResourceKey<Level> dimension, ChunkPos pos);

    /**
     * New chunk to track claim of.
     * @param colony the colony claiming it.
     * @param pos the chunk pos.
     * @param chunkClaimData the claim data to track.
     */
    void addNewChunk(Colony colony, ChunkPos pos, ChunkClaimData chunkClaimData);
}
