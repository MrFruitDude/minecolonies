package com.minecolonies.core.entity.pathfinding.pathjobs;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.compatibility.Compatibility;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.entity.citizen.AbstractCivilianEntity;
import com.minecolonies.api.items.ModTags;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.core.entity.ai.workers.util.Tree;
import com.minecolonies.core.entity.pathfinding.MNode;
import com.minecolonies.core.entity.pathfinding.SurfaceType;
import com.minecolonies.core.entity.pathfinding.pathresults.TreePathResult;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static com.minecolonies.core.entity.pathfinding.pathjobs.PathJobMoveToWithPassable.PASSING_COST;

/**
 * Find and return a path to the nearest tree. Created: May 21, 2015
 */
public class PathJobFindTree extends AbstractPathJob implements ISearchPathJob
{
    /**
     * The list of trees the Lumberjack is not supposed to cut.
     */
    private final List<ItemStorage> excludedTrees;

    /**
     * The Colony the tree is in.
     */
    private final IColony colony;

    /**
     * Position we want to search towards
     */
    private final BlockPos searchTowards;
    private final int      dyntreesize;

    /**
     * Box restriction area
     */
    private AABB restrictionBox = null;

    /**
     * Forestry: trees farther than this from the hut (horizontally) are ignored; 0 for no limit.
     */
    private int homeRadius = 0;

    /**
     * Forestry: the level of the tree claims and the asking Forester, so trees of another Forester (or blocked next to a build) are skipped.
     */
    private final Level claimLevel;
    private final int   colonyId;
    private final int   citizenId;

    /**
     * AbstractPathJob constructor.
     *
     * @param world       the world within which to path.
     * @param start       the start position from which to path from.
     * @param home        the position of the worker hut.
     * @param range       maximum path range.
     * @param treesToCut  the trees the lj is supposed to cut.
     * @param entity      the entity.
     * @param dyntreesize the radius a dynamic tree must have
     * @param colony      the colony.
     */
    public PathJobFindTree(
      final Level world,
      @NotNull final BlockPos start,
      final BlockPos home,
      final int range,
      final List<ItemStorage> treesToCut,
      final int dyntreesize,
      final IColony colony,
      final Mob entity)
    {
        super(world, start, range, new TreePathResult(), entity);
        this.excludedTrees = treesToCut;
        this.colony = colony;
        this.searchTowards = home;
        this.dyntreesize = dyntreesize;
        this.claimLevel = world;
        this.colonyId = colony == null ? 0 : colony.getID();
        this.citizenId = entity instanceof AbstractCivilianEntity civilian ? civilian.getCivilianID() : 0;
    }

    /**
     * Forestry: a search around the hut. Trees farther than the radius from the hut are not trees for this search.
     *
     * @param world       the world within which to path.
     * @param start       the start position from which to path from.
     * @param home        the position of the worker hut.
     * @param homeRadius  the search radius around the hut.
     * @param treesToCut  the trees the lj is not supposed to cut.
     * @param dyntreesize the radius a dynamic tree must have
     * @param colony      the colony.
     * @param entity      the entity.
     */
    public PathJobFindTree(
      final Level world,
      @NotNull final BlockPos start,
      final BlockPos home,
      final int homeRadius,
      final List<ItemStorage> treesToCut,
      final int dyntreesize,
      final IColony colony,
      final Mob entity,
      final boolean aroundHome)
    {
        this(world, start, home, homeRadius * 2, treesToCut, dyntreesize, colony, entity);
        this.homeRadius = aroundHome ? homeRadius : 0;
    }

    /**
     * AbstractPathJob constructor.
     *
     * @param world            the world within which to path.
     * @param start            the start position from which to path from.
     * @param startRestriction start of the restricted area.
     * @param endRestriction   end of the restricted area.
     * @param excludedTrees    the trees the lj is not supposed to cut.
     * @param dyntreesize      the radius a dynamic tree must have
     * @param entity           the entity.
     * @param colony           the colony.
     */
    public PathJobFindTree(
      final Level world,
      @NotNull final BlockPos start,
      final BlockPos startRestriction,
      final BlockPos endRestriction,
      final BlockPos furthestRestriction,
      final List<ItemStorage> excludedTrees,
      final int dyntreesize,
      final IColony colony,
      final Mob entity)
    {
        super(world,
          start,
          (int) (BlockPosUtil.dist(entity.blockPosition(),
            (startRestriction.getX() + endRestriction.getX()) / 2,
            (startRestriction.getY() + endRestriction.getY()) / 2,
            (startRestriction.getZ() + endRestriction.getZ()) / 2) + BlockPosUtil.dist(startRestriction, endRestriction)),
          new TreePathResult(),
          entity);

        restrictionBox = new AABB(Math.min(startRestriction.getX(), endRestriction.getX()),
          Math.min(startRestriction.getY(), endRestriction.getY()),
          Math.min(startRestriction.getZ(), endRestriction.getZ()),
          Math.max(startRestriction.getX(), endRestriction.getX()),
          Math.max(startRestriction.getY(), endRestriction.getY()),
          Math.max(startRestriction.getZ(), endRestriction.getZ()));

        this.excludedTrees = excludedTrees;
        this.colony = colony;
        this.dyntreesize = dyntreesize;
        this.claimLevel = world;
        this.colonyId = colony == null ? 0 : colony.getID();
        this.citizenId = entity instanceof AbstractCivilianEntity civilian ? civilian.getCivilianID() : 0;

        this.searchTowards = BlockPos.containing(restrictionBox.getCenter());
    }

    @NotNull
    @Override
    public TreePathResult getResult()
    {
        return (TreePathResult) super.getResult();
    }

    @Override
    protected double computeHeuristic(final int x, final int y, final int z)
    {
        return searchTowards == null ? BlockPosUtil.distManhattan(start, x, y, z) : BlockPosUtil.distManhattan(searchTowards, x, y, z);
    }

    @Override
    protected boolean isAtDestination(@NotNull final MNode n)
    {
        if (restrictionBox != null && !restrictionBox.contains(n.x, n.y, n.z))
        {
            return false;
        }

        return n.parent != null && isNearTree(n)
                 && SurfaceType.getSurfaceType(world, cachedBlockLookup.getBlockState(n.x, n.y - 1, n.z), tempWorldPos.set(n.x, n.y - 1, n.z), getPathingOptions())
                      == SurfaceType.WALKABLE;
    }

    private boolean isNearTree(@NotNull final MNode n)
    {
        if (n.parent == null)
        {
            return false;
        }

        // TODO: Recheck logic
        if (n.x == n.parent.x)
        {
            final int dz = n.z > n.parent.z ? 1 : -1;
            return isTree(tempWorldPos.set(n.x, n.y, n.z + dz)) || isTree(tempWorldPos.set(n.x - 1, n.y, n.z)) || isTree(tempWorldPos.set(n.x + 1, n.y, n.z));
        }
        else
        {
            final int dx = n.x > n.parent.x ? 1 : -1;
            return isTree(tempWorldPos.set(n.x + dx, n.y, n.z)) || isTree(tempWorldPos.set(n.x, n.y, n.z - 1)) || isTree(tempWorldPos.set(n.x, n.y, n.z + 1));
        }
    }

    private boolean isTree(final BlockPos pos)
    {
        if (homeRadius > 0 && searchTowards != null)
        {
            // A real radius: getDistance2D is the x plus z distance, a diamond that misses trees towards the hut's corners
            final long dx = pos.getX() - searchTowards.getX();
            final long dz = pos.getZ() - searchTowards.getZ();
            if (dx * dx + dz * dz > (long) homeRadius * homeRadius)
            {
                return false;
            }
        }
        if (Tree.checkTree(world, pos, excludedTrees, dyntreesize, claimLevel, colonyId, citizenId) && Tree.checkIfInColony(pos, colony, world, restrictionBox != null))
        {
            getResult().treeLocation = pos.immutable();
            return true;
        }

        return false;
    }

    @Override
    public double getEndNodeScore(final MNode n)
    {
        return BlockPosUtil.distManhattan(searchTowards, n.x, n.y, n.z);
    }

    @Override
    protected boolean isPassable(@NotNull final BlockState block, final int x, final int y, final int z, final MNode parent, final boolean head)
    {
        return super.isPassable(block, x, y, z, parent, head) || isLeafLike(block);
    }

    @Override
    protected double modifyCost(
      double cost,
      final MNode parent,
      final boolean swimstart,
      final boolean swimming,
      final int x,
      final int y,
      final int z,
      final BlockState state,
      final BlockState below)
    {
        if (!state.isAir() && isLeafLike(state))
        {
            cost *= PASSING_COST;
        }
        else
        {
            final BlockState above = cachedBlockLookup.getBlockState(x, y + 1, z);
            if (!above.isAir() && isLeafLike(above))
            {
                cost *= PASSING_COST;
            }
        }

        if (restrictionBox != null && !restrictionBox.contains(x, y, z))
        {
            cost *= 2;
        }

        return cost;
    }

    private boolean isLeafLike(@NotNull final BlockState block)
    {
        return block.is(BlockTags.LEAVES) || Compatibility.isDynamicTrunkShell(block.getBlock()) || block.is(ModTags.hugeMushroomBlocks);
    }
}
