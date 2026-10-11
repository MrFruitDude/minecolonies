package com.minecolonies.api.colony;

import com.minecolonies.api.colony.buildings.modules.ICommonSettingsModule;
import com.minecolonies.api.colony.connections.IColonyConnectionManager;
import com.minecolonies.api.colony.managers.interfaces.*;
import com.minecolonies.api.colony.permissions.IPermissions;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.requester.IRequester;
import com.minecolonies.api.colony.workorders.IWorkManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.items.component.ColonyId;
import com.minecolonies.api.quests.IQuestManager;
import com.minecolonies.api.research.IResearchManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Interface of the Colony and ColonyView which will have to implement the following methods.
 */
public interface IColony
{
    void onWorldLoad(@NotNull ServerLevel w);

    void onWorldUnload(@NotNull Level w);

    void onServerTick(@NotNull ServerTickEvent.Pre event);

    @NotNull
    IWorkManager getWorkManager();

    void onWorldTick(@NotNull LevelTickEvent.Pre event);

    /**
     * Returns the position of the colony.
     *
     * @return pos of the colony.
     */
    BlockPos getCenter();

    /**
     * Returns the name of the colony.
     *
     * @return Name of the colony.
     */
    String getName();

    void setName(String n);

    /**
     * Returns the permissions of the colony.
     *
     * @return {@link IPermissions} of the colony.
     */
    IPermissions getPermissions();

    /**
     * Determine if a given chunk coordinate is considered to be within the colony's bounds.
     *
     * @param w   World to check.
     * @param pos Block Position.
     * @return True if inside colony, otherwise false.
     */
    boolean isCoordInColony(Level w, BlockPos pos);

    /**
     * Returns the squared (x, z) distance to the center.
     *
     * @param pos Block Position.
     * @return Squared distance to the center in (x, z) direction.
     */
    long getDistanceSquared(BlockPos pos);

    /**
     * returns this colonies unique id.
     *
     * @return an int representing the id.
     */
    int getID();

    /**
     * Getter for the team colony color.
     *
     * @return the color.
     */
    ChatFormatting getTeamColonyColor();

    /**
     * Returns this colony's banner patterns, as a List
     *
     * @return a list of pattern-color pairs
     */
    BannerPatternLayers getColonyFlag();

    /**
     * Whether it is day for the colony
     *
     * @return true if it is day
     */
    boolean isDay();

    /**
     * Get the last contact of a player to the colony in hours.
     *
     * @return an integer with a describing value.
     */
    int getLastContactInHours();

    /**
     * Method to get the World this colony is in.
     *
     * @return the World the colony is in.
     */
    Level getWorld();

    /**
     * Get the current {@link IRequestManager} for this Colony. Returns null if the current Colony does not support the request system.
     *
     * @return the {@link IRequestManager} for this colony, null if not supported.
     */
    @NotNull
    IRequestManager getRequestManager();

    /**
     * Called to mark this colony dirty, and in need of syncing / saving.
     */
    void markDirty();

    /**
     * Called to check if the colony can be deleted by an automatic cleanup.
     *
     * @return true if so.
     */
    boolean canBeAutoDeleted();

    /**
     * Method used to get a {@link IRequester} from a given Position. Is always a Building.
     *
     * @param pos The position to get the Building that acts as a requester.
     * @return The {@link IRequester} from the position, or null.
     */
    @Nullable
    IRequester getRequesterBuildingForPosition(@NotNull final BlockPos pos);

    /**
     * Remove a visiting player.
     *
     * @param player the player.
     */
    void removeVisitingPlayer(final Player player);

    /**
     * Get the players in the colony which should receive the message.
     *
     * @return list of players
     */
    @NotNull
    List<Player> getMessagePlayerEntities();

    @NotNull
    default List<BlockPos> getWayPoints(@NotNull BlockPos position, @NotNull BlockPos target)
    {
        final List<BlockPos> tempWayPoints = new ArrayList<>();
        tempWayPoints.addAll(getWayPoints().keySet());
        tempWayPoints.addAll(getServerBuildingManager().getBuildings().keySet());

        final double maxX = Math.max(position.getX(), target.getX());
        final double maxZ = Math.max(position.getZ(), target.getZ());

        final double minX = Math.min(position.getX(), target.getX());
        final double minZ = Math.min(position.getZ(), target.getZ());

        final Iterator<BlockPos> iterator = tempWayPoints.iterator();
        while (iterator.hasNext())
        {
            final BlockPos p = iterator.next();
            final int x = p.getX();
            final int z = p.getZ();
            if (x < minX || x > maxX || z < minZ || z > maxZ)
            {
                iterator.remove();
            }
        }

        return tempWayPoints;
    }

    double getOverallHappiness();

    Map<BlockPos, BlockState> getWayPoints();

    String getStructurePack();

    void setStructurePack(String style);

    IRegisteredStructureManager getServerBuildingManager();

    ICommonRegisteredStructureManager getCommonBuildingManager();

    ICitizenManager getCitizenManager();

    IGraveManager getGraveManager();

    /**
     * Gets the visitor manager
     *
     * @return manager
     */
    IVisitorManager getVisitorManager();

    /**
     * Get the animal manager of the colony.
     *
     * @return the animal manager.
     */
    IAnimalManager getAnimalManager();

    IRaiderManager getRaiderManager();

    /**
     * Get the event manager of the colony.
     *
     * @return the event manager.
     */
    IEventManager getEventManager();

    /**
     * Get the reproduction manager of the colony.
     *
     * @return the reproduction manager.
     */
    IReproductionManager getReproductionManager();

    /**
     * Get the event description manager of the colony.
     *
     * @return the event description manager.
     */
    IEventDescriptionManager getEventDescriptionManager();

    /**
     * The colony networking packaging manager.
     *
     * @return The packaging manager.
     */
    IColonyPackageManager getPackageManager();

    /**
     * Get the travelling manager of the colony.
     *
     * @return the travelling manager.
     */
    ITravellingManager getTravellingManager();

    /**
     * Get the connection manager of the colony.
     * @return the connection manager.
     */
    IColonyConnectionManager getConnectionManager();

    /**
     * Add a visiting player.
     *
     * @param player the player.
     */
    void addVisitingPlayer(final Player player);

    /**
     * Get the colony dimension.
     *
     * @return the dimension id.
     */
    ResourceKey<Level> getDimension();

    /**
     * Check if the colony is on the server or client.
     *
     * @return true if so.
     */
    boolean isRemote();

    /**
     * Get the research manager.
     *
     * @return the research manager object.
     */
    IResearchManager getResearchManager();

    /**
     * Save the time when mercenaries are used, to set a cooldown.
     */
    void usedMercenaries();

    /**
     * Get the last time mercenaries were used.
     *
     * @return the mercenary use time.
     */
    long getMercenaryUseTime();

    CompoundTag getColonyTag();

    boolean isColonyUnderAttack();

    boolean isValidAttackingPlayer(Player entity);

    boolean isValidAttackingGuard(AbstractEntityCitizen entity);

    void setColonyColor(ChatFormatting color);

    void setColonyFlag(BannerPatternLayers patterns);

    void addWayPoint(BlockPos pos, BlockState newWayPointState);

    void addGuardToAttackers(AbstractEntityCitizen entityCitizen, Player followPlayer);

    void addFreePosition(BlockPos pos);

    void addFreeBlock(Block block);

    void removeFreePosition(BlockPos pos);

    void removeFreeBlock(Block block);

    void setCanBeAutoDeleted(boolean canBeDeleted);

    CompoundTag write(CompoundTag colonyCompound, @NotNull final HolderLookup.Provider provider);

    void read(CompoundTag compound, @NotNull final HolderLookup.Provider provider);

    /**
     * Returns a set of players receiving important messages for the colony.
     *
     * @return set of players.
     */
    @NotNull
    List<Player> getImportantMessageEntityPlayers();

    /**
     * Tries to use a given amount of additional growth-time for childs.
     *
     * @param amount amount to use
     * @return true if used up.
     */
    boolean useAdditionalChildTime(int amount);

    /**
     * Sets whether the colony has a child.
     */
    void updateHasChilds();

    /**
     * Adds a loaded chunk to the colony list
     *
     * @param chunkPos chunk to add
     */
    void addLoadedChunk(long chunkPos, final LevelChunk chunk);

    /**
     * Adds a chunk from the colony list
     *
     * @param chunkPos chunk to remove
     */
    void removeLoadedChunk(long chunkPos);

    /**
     * Returns the amount of loaded chunks
     *
     * @return amount of chunks
     */
    int getLoadedChunkCount();

    Set<Long> getLoadedChunks();

    /**
     * Returns the colonies current state.
     *
     * @return the state.
     */
    ColonyState getState();

    /**
     * Is the colony active currently.
     *
     * @return true if so.
     */
    boolean isActive();

    /**
     * Keeps the colony ACTIVE (simulating) without a player standing in it, for as long as a colony manager (the owner,
     * an officer) of it is online. A manager of the player's colonies uses this to keep the owned colonies of an online
     * player running although the player is in only one of them. The flag does not simulate anything offline: while no
     * manager is online it has no effect, and it is not saved. It also asks for the colony's building chunks to be
     * force loaded through the existing {@code forceloadcolony} mechanism (same bounds, same config switch), and the
     * state changes at once instead of at the next state check.
     *
     * @param forceActive true to keep the colony active, false to release it again (the colony is then active only when a
     *                    player is close, like any colony, and its force load tickets expire).
     */
    default void setForceActive(final boolean forceActive)
    {
    }

    /**
     * @return true if {@link #setForceActive(boolean)} is set (it may have no effect while no manager is online).
     */
    default boolean isForceActive()
    {
        return false;
    }

    /**
     * Sets who decides whether a faction-owned colony simulates, in place of the players there are none of. See
     * {@link ColonySimulationDriver}: while the driver {@linkplain ColonySimulationDriver#wantsActive() wants} the colony
     * active and the chunks of its town hall are loaded, the colony is ACTIVE although no player is close or online; with
     * {@link #setForceActive(boolean)} the colony also force loads its building chunks (bounded) and is ACTIVE while the
     * driver wants it, with no manager online. It never simulates offline: nothing ticks while the chunks are unloaded.
     * The state changes at once; later changes of {@code wantsActive()} are picked up at the next state check (100 ticks).
     * The driver is not saved: the owner sets it again after a restart.
     *
     * @param driver the driver, null to remove it.
     * @throws IllegalStateException if the colony is not faction-owned (a player colony is run by its players).
     */
    default void setSimulationDriver(@Nullable final ColonySimulationDriver driver)
    {
    }

    /**
     * @return the simulation driver of the colony, null if there is none.
     */
    @Nullable
    default ColonySimulationDriver getSimulationDriver()
    {
        return null;
    }

    /**
     * When the colony was founded, as epoch milliseconds (strictly increasing between colonies created in one run).
     * 0 for a colony saved before this was recorded; those are older than any other. Used to find a player's oldest colony.
     *
     * @return the founding time, 0 if unknown.
     */
    default long getFoundedTime()
    {
        return 0L;
    }

    /**
     * Get the set of chunk positions which the colony is loading through tickets
     *
     * @return set of positions
     */
    Set<Long> getTicketedChunks();

    /**
     * Set the texture style of the colony.
     *
     * @param style the style to set.
     */
    void setTextureStyle(String style);

    /**
     * Get the colony style.
     *
     * @return the string id of the style.
     */
    String getTextureStyleId();

    /**
     * Get the colony name style.
     *
     * @return the string id of the style.
     */
    String getNameStyle();

    /**
     * Set the colony name style.
     *
     * @param style the string id of the style.
     */
    void setNameStyle(final String style);

    /**
     * Get the matching citizen name file of the colony .
     *
     * @return the matching file.
     */
    CitizenNameFile getCitizenNameFile();

    /**
     * Get the statistics manager of the colony.
     *
     * @return the statistics manager.
     */
    IStatisticsManager getStatisticsManager();

    /**
     * Get the current day of the colony.
     *
     * @return the current day progress of the colony.
     */
    int getDay();

    /**
     * Get the quest manager of the colony.
     *
     * @return the quest manager.
     */
    IQuestManager getQuestManager();

    /**
     * Get citizen from colony.
     *
     * @param id the id of the cit.
     * @return the cit.
     */
    ICitizen getCitizen(int id);

    /**
     * Get the colony level settings module.
     * @return the settings module.
     */
    ICommonSettingsModule getSettings();

    /**
     * Saves reference of this colony to given itemStack.
     */
    default void writeToItemStack(final ItemStack itemStack)
    {
        new ColonyId(getID(), getDimension()).writeToItemStack(itemStack);
    }
}
