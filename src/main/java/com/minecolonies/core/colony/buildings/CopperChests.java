package com.minecolonies.core.colony.buildings;

import com.ldtteam.structurize.api.util.Tuple;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.util.BuildingContainers;
import com.minecolonies.api.util.ColonyUtils;
import com.minecolonies.api.util.WorldUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.minecolonies.api.util.constant.ColonyManagerConstants.NO_COLONY_ID;

/**
 * V-7: which building holds a vanilla copper chest, and the checks that keep that true. The rule: a copper chest a building lists stands in a chunk the
 * colony owns and the building claims, inside the building's corners (a double chest: one of its halves inside), has no lock of its own, and both halves of a
 * double chest are listed by the same building and carry the building lock. With nested buildings the smallest one holds it. Anything else leaves the list at
 * the building's next check (its colony tick, loaded chunks only), and a building lock no building holds is cleared when its chunk loads. Server side only.
 */
public final class CopperChests
{
    /**
     * Whether a building may hold a copper chest.
     */
    public enum Fit
    {
        YES,
        NO,
        /**
         * The other half of the double chest is in an unloaded chunk: decided later.
         */
        UNKNOWN
    }

    private CopperChests()
    {
    }

    /**
     * @param pos   a chest position.
     * @param state its state.
     * @return the position of the other half of a double chest, or null for a single chest (or no chest).
     */
    @Nullable
    public static BlockPos otherHalf(final BlockPos pos, final BlockState state)
    {
        if (!state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE)
        {
            return null;
        }
        return pos.relative(ChestBlock.getConnectedDirection(state));
    }

    /**
     * @return whether a position is in a loaded chunk the building's colony owns and the building claims.
     */
    public static boolean claims(final IBuilding building, final Level level, final BlockPos pos)
    {
        if (!WorldUtil.isBlockLoaded(level, pos))
        {
            return false;
        }
        final LevelChunk chunk = level.getChunkAt(pos);
        final int id = building.getColony().getID();
        return ColonyUtils.getOwningColony(chunk) == id
                 && ColonyUtils.getAllClaimingBuildings(chunk).getOrDefault(id, Collections.emptySet()).contains(building.getID());
    }

    /**
     * Whether a building may hold the copper chest at a position (for a double chest: both halves).
     *
     * @param building the building.
     * @param level    the world.
     * @param pos      the chest position (loaded).
     * @return the fit.
     */
    public static Fit fit(final AbstractBuildingContainer building, final Level level, final BlockPos pos)
    {
        if (!building.acceptsCopperChests() || building.getBuildingLevel() < 1 || !WorldUtil.isBlockLoaded(level, pos))
        {
            return Fit.NO;
        }
        final ChestBlockEntity chest = BuildingContainers.copperChest(level.getBlockEntity(pos));
        if (chest == null || BuildingContainers.hasOwnLock(chest) || !claims(building, level, pos))
        {
            return Fit.NO;
        }
        final BlockPos other = otherHalf(pos, level.getBlockState(pos));
        if (other == null)
        {
            return building.isInBuilding(pos) ? Fit.YES : Fit.NO;
        }
        if (!WorldUtil.isBlockLoaded(level, other))
        {
            return Fit.UNKNOWN;
        }
        final ChestBlockEntity otherChest = BuildingContainers.copperChest(level.getBlockEntity(other));
        if (otherChest == null || BuildingContainers.hasOwnLock(otherChest) || !claims(building, level, other))
        {
            return Fit.NO;
        }
        return building.isInBuilding(pos) || building.isInBuilding(other) ? Fit.YES : Fit.NO;
    }

    /**
     * @return whether building a ranks before building b for a copper chest both could hold (smaller corner volume, then position).
     */
    public static boolean ranksBefore(final IBuilding a, final IBuilding b)
    {
        final long va = volume(a);
        final long vb = volume(b);
        return va != vb ? va < vb : a.getID().asLong() < b.getID().asLong();
    }

    private static long volume(final IBuilding building)
    {
        final Tuple<BlockPos, BlockPos> corners = building.getCorners();
        return (long) (Math.abs(corners.getB().getX() - corners.getA().getX()) + 1)
                 * (Math.abs(corners.getB().getY() - corners.getA().getY()) + 1)
                 * (Math.abs(corners.getB().getZ() - corners.getA().getZ()) + 1);
    }

    /**
     * The best-ranked building of a colony that lists a position as a container.
     *
     * @param colony the colony.
     * @param pos    the position.
     * @param except a building to leave out (one being destroyed), or null.
     * @return the building, or null.
     */
    @Nullable
    public static AbstractBuildingContainer holder(final IColony colony, final BlockPos pos, @Nullable final IBuilding except)
    {
        AbstractBuildingContainer best = null;
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            if (building != except && building instanceof final AbstractBuildingContainer container && container.listsContainer(pos)
                  && (best == null || ranksBefore(container, best)))
            {
                best = container;
            }
        }
        return best;
    }

    /**
     * The best-ranked building of a colony that may hold a new copper chest at a position, read from the claims of its chunk.
     *
     * @param colony the colony.
     * @param level  the world.
     * @param pos    the chest position.
     * @return the building, or null.
     */
    @Nullable
    public static AbstractBuildingContainer bestFit(final IColony colony, final Level level, final BlockPos pos)
    {
        if (!WorldUtil.isBlockLoaded(level, pos))
        {
            return null;
        }
        final LevelChunk chunk = level.getChunkAt(pos);
        if (ColonyUtils.getOwningColony(chunk) != colony.getID())
        {
            return null;
        }
        AbstractBuildingContainer best = null;
        for (final BlockPos buildingPos : ColonyUtils.getAllClaimingBuildings(chunk).getOrDefault(colony.getID(), Collections.emptySet()))
        {
            if (colony.getServerBuildingManager().getBuilding(buildingPos) instanceof final AbstractBuildingContainer building
                  && building.isInBuilding(pos) && fit(building, level, pos) != Fit.NO && (best == null || ranksBefore(building, best)))
            {
                best = building;
            }
        }
        return best;
    }

    /**
     * Whether a building of the colony that owns the chunk holds the position (a colony that is not loaded counts as holding it).
     */
    private static boolean held(final Level level, final LevelChunk chunk, final BlockPos pos, @Nullable final IBuilding except)
    {
        final int owner = ColonyUtils.getOwningColony(chunk);
        if (owner == NO_COLONY_ID)
        {
            return false;
        }
        final IColony colony = IColonyManager.getInstance().getColonyByWorld(owner, level);
        return colony == null || holder(colony, pos, except) != null;
    }

    /**
     * A building let go of a position: the copper chest there is open to copper golems again, unless a building of the colony that owns the chunk still
     * lists it.
     *
     * @param level  the world.
     * @param pos    the position.
     * @param except a building to leave out (one being destroyed), or null.
     */
    public static void releaseIfUnheld(@Nullable final Level level, final BlockPos pos, @Nullable final IBuilding except)
    {
        if (level == null || level.isClientSide() || !WorldUtil.isBlockLoaded(level, pos))
        {
            return;
        }
        final BlockEntity entity = level.getBlockEntity(pos);
        final ChestBlockEntity chest = BuildingContainers.copperChest(entity);
        if (chest != null && BuildingContainers.keepsGolemsOut(chest) && !held(level, level.getChunkAt(pos), pos, except))
        {
            BuildingContainers.letGolemsIn(chest);
        }
    }

    /**
     * A chunk loaded: clears the building lock of every copper chest in it that no building of the chunk's colony lists (its building was destroyed or its
     * colony deleted while the chunk was unloaded).
     *
     * @param chunk the chunk (its block entities registered).
     * @param level the world.
     */
    public static void onChunkLoad(final LevelChunk chunk, final Level level)
    {
        boolean changed = false;
        for (final BlockEntity entity : chunk.getBlockEntities().values())
        {
            final ChestBlockEntity chest = BuildingContainers.copperChest(entity);
            if (chest != null && BuildingContainers.keepsGolemsOut(chest) && !held(level, chunk, chest.getBlockPos(), null))
            {
                changed |= BuildingContainers.letGolemsInQuietly(chest);
            }
        }
        if (changed)
        {
            chunk.markUnsaved();
        }
    }

    /**
     * One building's check (its colony tick): takes up the copper chests inside its corners that it should hold (placed before it was built, or not by a
     * player), and drops every listed copper chest it may not hold, a better-ranked building holds, or whose other half another building holds. Listed
     * chests it keeps carry the building lock, and a double chest's other half is listed too. A loaded copper chest position whose chest is gone (no block
     * entity) goes too; other positions (racks) stay listed while empty, as before: an empty position is no stock, and a rack placed back there is a container
     * again.
     *
     * @param building the building.
     * @return whether its container list changed.
     */
    public static boolean reconcile(final AbstractBuildingContainer building)
    {
        final IColony colony = building.getColony();
        final Level level = colony == null ? null : colony.getWorld();
        if (level == null || level.isClientSide())
        {
            return false;
        }
        boolean changed = false;
        if (building.acceptsCopperChests() && building.getBuildingLevel() >= 1)
        {
            final Tuple<BlockPos, BlockPos> corners = building.getCorners();
            final int minX = (Math.min(corners.getA().getX(), corners.getB().getX()) - 1) >> 4;
            final int maxX = (Math.max(corners.getA().getX(), corners.getB().getX()) + 1) >> 4;
            final int minZ = (Math.min(corners.getA().getZ(), corners.getB().getZ()) - 1) >> 4;
            final int maxZ = (Math.max(corners.getA().getZ(), corners.getB().getZ()) + 1) >> 4;
            final List<BlockPos> found = new ArrayList<>();
            for (int cx = minX; cx <= maxX; cx++)
            {
                for (int cz = minZ; cz <= maxZ; cz++)
                {
                    if (!WorldUtil.isChunkLoaded(level, cx, cz))
                    {
                        continue;
                    }
                    for (final BlockEntity entity : level.getChunk(cx, cz).getBlockEntities().values())
                    {
                        final BlockPos pos = entity.getBlockPos();
                        if (BuildingContainers.copperChest(entity) != null && !building.listsContainer(pos) && building.isInBuilding(pos))
                        {
                            found.add(pos);
                        }
                    }
                }
            }
            for (final BlockPos pos : found)
            {
                if (holder(colony, pos, null) == null && bestFit(colony, level, pos) == building && fit(building, level, pos) == Fit.YES
                      && building.addCopperChest(pos, level))
                {
                    changed = true;
                }
            }
        }

        for (final BlockPos pos : new ArrayList<>(building.containerList))
        {
            if (pos.equals(building.getID()) || !building.listsContainer(pos) || !WorldUtil.isBlockLoaded(level, pos))
            {
                continue;
            }
            final BlockEntity entity = level.getBlockEntity(pos);
            if (entity == null)
            {
                if (building.copperChestPositions.contains(pos))
                {
                    // the copper chest is gone without a player (explosion, command): a builder that places one again registers it again
                    building.removeContainerPosition(pos);
                    changed = true;
                }
                continue;
            }
            final ChestBlockEntity chest = BuildingContainers.copperChest(entity);
            if (chest == null)
            {
                building.copperChestPositions.remove(pos);
                continue;
            }
            building.copperChestPositions.add(pos.immutable());
            final BlockPos other = otherHalf(pos, level.getBlockState(pos));
            final Fit fit = fit(building, level, pos);
            if (fit == Fit.NO || rivalHolds(colony, building, pos) || (other != null && rivalHolds(colony, building, other)))
            {
                building.removeContainerPosition(pos);
                if (other != null && building.listsContainer(other))
                {
                    building.removeContainerPosition(other);
                }
                changed = true;
                continue;
            }
            BuildingContainers.keepGolemsOut(chest);
            if (fit == Fit.YES && other != null && !building.listsContainer(other) && building.addCopperChest(other, level))
            {
                changed = true;
            }
        }
        return changed;
    }

    /**
     * @return whether a building of the colony that ranks before this one lists the position.
     */
    private static boolean rivalHolds(final IColony colony, final AbstractBuildingContainer building, final BlockPos pos)
    {
        for (final IBuilding other : colony.getServerBuildingManager().getBuildings().values())
        {
            if (other != building && other instanceof final AbstractBuildingContainer container && container.listsContainer(pos) && ranksBefore(container, building))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes a position from every building of the colony that lists it, except one (the lock stays when a building still lists it).
     *
     * @param colony the colony.
     * @param pos    the position.
     * @param keep   the building that keeps it, or null.
     * @return whether a building's list changed.
     */
    public static boolean forget(final IColony colony, final BlockPos pos, @Nullable final IBuilding keep)
    {
        boolean changed = false;
        for (final IBuilding building : new ArrayList<>(colony.getServerBuildingManager().getBuildings().values()))
        {
            if (building != keep && building instanceof final AbstractBuildingContainer container && !building.getID().equals(pos) && container.listsContainer(pos))
            {
                container.removeContainerPosition(pos);
                container.markDirty();
                changed = true;
            }
        }
        return changed;
    }
}
