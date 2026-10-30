package com.minecolonies.core.entity.ai.workers.util;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.IAltersBuildingFootprint;
import com.minecolonies.api.colony.claim.IChunkClaimData;
import com.minecolonies.api.items.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.ldtteam.structurize.api.util.Tuple;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Forestry: the Forester's path planner and his clearing follow one rule for leaves and caps in his way (r2 Fable N3). The planner may route through a
 * natural leaf or a cap of his tree only where he may clear it: not in a hut box, not on another colony's land, not touching a player's block. A leaf he
 * may not clear is a wall to the planner, so he never walks at a tree he cannot get into and stands there for two minutes.
 * <p>
 * Made on the server thread (a snapshot of the hut boxes, the claims around and the tree's blocks); read by the path job off the server thread, through
 * the path job's own world view.
 */
public final class LeafPassGuard
{
    /**
     * How far around the start and the goal the claims are read (blocks): the path search range and a chunk.
     */
    private static final int CLAIM_MARGIN = 66;

    private final List<int[]>    boxes          = new ArrayList<>();
    private final Set<Long>      foreignChunks  = new HashSet<>();
    private final Set<Long>      treeBlocks     = new HashSet<>();
    /**
     * The caps he may clear, judged on the server thread by the clearing's own rule ({@link Tree#mayClearCap}, r3 Fable N12): a cap joined to a decoration
     * or reaching past his tree is a wall to the planner too.
     */
    private final Set<Long>      clearableCaps  = new HashSet<>();
    private final List<BlockPos> logs;
    private final boolean        netherTree;
    private final String         family;
    private final long           madeAt;
    private volatile boolean     refused        = false;

    private LeafPassGuard(final List<BlockPos> logs, final boolean netherTree, final String family, final long madeAt)
    {
        this.logs = logs;
        this.netherTree = netherTree;
        this.family = family;
        this.madeAt = madeAt;
    }

    /**
     * Snapshot of what the rule needs. Server thread.
     *
     * @param level  the level.
     * @param colony the Forester's colony.
     * @param tree   the tree he works on.
     * @param from   where he stands.
     * @param to     where he walks.
     * @return the guard.
     */
    @NotNull
    public static LeafPassGuard of(@NotNull final Level level, @NotNull final IColony colony, @NotNull final Tree tree, @NotNull final BlockPos from, @NotNull final BlockPos to)
    {
        final LeafPassGuard guard = new LeafPassGuard(List.copyOf(tree.getAllLogs()), tree.isNetherTree(), tree.getFamily(), level.getGameTime());
        for (final BlockPos log : tree.getAllLogs())
        {
            guard.treeBlocks.add(log.asLong());
        }
        if (tree.isNetherTree())
        {
            guard.judgeCaps(level, tree);
        }
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            final Tuple<BlockPos, BlockPos> corners = building.getCorners();
            BlockPos a = corners.getA();
            BlockPos b = corners.getB();
            if (building.hasModule(IAltersBuildingFootprint.class))
            {
                final Tuple<BlockPos, BlockPos> extensions = building.getFirstModuleOccurance(IAltersBuildingFootprint.class).getAdditionalCorners();
                a = a.offset(extensions.getA());
                b = b.offset(extensions.getB());
            }
            // the same box as IBuilding.isInBuilding: the corners and one block around
            guard.boxes.add(new int[] {a.getX() - 1, a.getY() - 1, a.getZ() - 1, b.getX() + 1, b.getY() + 1, b.getZ() + 1});
        }
        final int minX = (Math.min(from.getX(), to.getX()) - CLAIM_MARGIN) >> 4;
        final int maxX = (Math.max(from.getX(), to.getX()) + CLAIM_MARGIN) >> 4;
        final int minZ = (Math.min(from.getZ(), to.getZ()) - CLAIM_MARGIN) >> 4;
        final int maxZ = (Math.max(from.getZ(), to.getZ()) + CLAIM_MARGIN) >> 4;
        for (int x = minX; x <= maxX; x++)
        {
            for (int z = minZ; z <= maxZ; z++)
            {
                final IChunkClaimData claim = IColonyManager.getInstance().getClaimData(level.dimension(), new ChunkPos(x, z));
                if (claim != null && claim.getOwningColony() != 0 && claim.getOwningColony() != colony.getID())
                {
                    guard.foreignChunks.add(ChunkPos.pack(x, z));
                }
            }
        }
        return guard;
    }

    /**
     * @return the game time the snapshot was made.
     */
    public long madeAt()
    {
        return madeAt;
    }

    /**
     * @return true if the planner met a leaf or cap it would have gone through but he may not clear, since the last {@link #newSearch}.
     */
    public boolean refused()
    {
        return refused;
    }

    /**
     * A new path search starts with this guard: what the last one ran into is forgotten (r5 Fable N35, a refusal belongs to one search). Server thread,
     * while no search reads the guard.
     */
    public void newSearch()
    {
        refused = false;
    }

    /**
     * Whether the planner may go through a block that the plain rule lets through (a natural leaf, a cap). Any thread.
     *
     * @param world the path job's world view.
     * @param state the block.
     * @param x     its x.
     * @param y     its y.
     * @param z     its z.
     * @return true if he may clear it on his way.
     */
    public boolean passable(@NotNull final LevelReader world, @NotNull final BlockState state, final int x, final int y, final int z)
    {
        final boolean leaf = TreeFootprint.isDecayingLeaves(state) && !state.getValue(LeavesBlock.PERSISTENT);
        final boolean cap = !leaf && state.is(ModTags.hugeMushroomBlocks) && ownsCap(x, y, z) && TreeFootprint.capFitsFamily(family, state)
                              && clearableCaps.contains(BlockPos.asLong(x, y, z));
        if (!leaf && !cap)
        {
            refused |= TreeFootprint.isCap(state);
            return false;
        }
        if (foreignChunks.contains(ChunkPos.pack(x >> 4, z >> 4)) || inBox(x, y, z) || touchesBuild(world, new BlockPos(x, y, z)))
        {
            refused = true;
            return false;
        }
        return true;
    }

    /**
     * Judge each cap within reach of the tree's stems once, one flood per connected cap. Server thread.
     */
    private void judgeCaps(@NotNull final Level level, @NotNull final Tree tree)
    {
        final Set<Long> judged = new HashSet<>();
        for (final BlockPos log : logs)
        {
            for (final BlockPos near : BlockPos.betweenClosed(log.offset(-TreeFootprint.CAP_REACH, -TreeFootprint.CAP_REACH, -TreeFootprint.CAP_REACH),
              log.offset(TreeFootprint.CAP_REACH, TreeFootprint.CAP_REACH, TreeFootprint.CAP_REACH)))
            {
                if (judged.contains(near.asLong()) || !TreeFootprint.isCap(level.getBlockState(near)))
                {
                    continue;
                }
                final TreeFootprint.CapComponent component = TreeFootprint.capComponent(level, near, tree.getBox(), family, tree.isStrippedBase(), tree.getStemsAround(level));
                for (final BlockPos cell : component.cells())
                {
                    judged.add(cell.asLong());
                    if (component.own() && tree.capWasThere(cell, level.getBlockState(cell)))
                    {
                        clearableCaps.add(cell.asLong());
                    }
                }
                judged.add(near.asLong());
            }
            if (judged.size() > TreeFootprint.LEAF_BUDGET * 4)
            {
                break;
            }
        }
    }

    private boolean inBox(final int x, final int y, final int z)
    {
        for (final int[] box : boxes)
        {
            if (x >= box[0] && y >= box[1] && z >= box[2] && x <= box[3] && y <= box[4] && z <= box[5])
            {
                return true;
            }
        }
        return false;
    }

    private static boolean touchesBuild(final LevelReader world, final BlockPos pos)
    {
        for (final Direction dir : Direction.values())
        {
            if (TreeFootprint.isBuildSignal(world.getBlockState(pos.relative(dir)), false))
            {
                return true;
            }
        }
        return false;
    }

    private boolean ownsCap(final int x, final int y, final int z)
    {
        if (treeBlocks.contains(BlockPos.asLong(x, y, z)))
        {
            return true;
        }
        if (!netherTree)
        {
            return false;
        }
        for (final BlockPos log : logs)
        {
            if (Math.abs(log.getX() - x) <= TreeFootprint.CAP_REACH && Math.abs(log.getY() - y) <= TreeFootprint.CAP_REACH && Math.abs(log.getZ() - z) <= TreeFootprint.CAP_REACH)
            {
                return true;
            }
        }
        return false;
    }
}
