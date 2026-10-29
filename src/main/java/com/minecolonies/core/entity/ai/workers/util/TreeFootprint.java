package com.minecolonies.core.entity.ai.workers.util;

import com.ldtteam.structurize.util.BlockUtils;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.compatibility.Compatibility;
import com.minecolonies.api.items.ModTags;
import com.minecolonies.api.util.constant.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The natural tree footprint: which blocks belong to one natural tree, so a Forester removes all of it and nothing else.
 * <ul>
 * <li>Wood family: logs, wood, stems, hyphae and roots of one kind ({@code oak_log}, {@code stripped_oak_wood} are oak), joined by the datapack tags
 * {@code minecolonies:tree_family/<name>} (mangrove with its roots and muddy roots, pale oak with the creaking heart). {@code minecolonies:tree_excluded}
 * (bamboo) is never a log. A TreeChop chopped log takes the family of the log it was made from.</li>
 * <li>Log flood: from the trunk base over the same family, up and sideways only (roots may go a little down), within a radius of the trunk that grows with
 * the trunk (8 for 1x1, 10 for 2x2, 16 for wider), up to the tree size budget. A tree over the budget is not started: a whole tree left beats half a
 * tree. A log of the tree past the radius makes it too big as well: a cut at the radius would leave that branch standing. Touching trees of one wood are
 * split by trunk: a log on the ground away from the trunk is the trunk of a neighbour, and each log goes to the trunk nearest to it along the wood.</li>
 * <li>Build guard: a log next to planks, stairs, slabs, fences, walls, doors, glass, torches, lanterns, beds, placed leaves, stripped logs on a natural
 * trunk (or natural logs on a stripped one), a log of another wood that is no tree of its own (a post or a beam; a natural tree of another wood, on
 * the ground with leaves of its wood, is a neighbour), or a block entity other than a bee nest; a log inside a colony hut box; a log in a chunk another
 * colony claims. Such a tree is not cut at all.</li>
 * <li>Leaf sweep: after the logs are gone, only leaves vanilla would decay anyway (not persistent, {@code DISTANCE} 7) are broken, found by a flood from the
 * leaves that touched the removed logs. Leaves held by a neighbour tree and placed leaves stay by construction. Mushroom caps, wart blocks and
 * shroomlight (no {@code DISTANCE}) go when their connected cap touches no remaining stem.</li>
 * </ul>
 */
public final class TreeFootprint
{
    /**
     * Matches any family (a block whose original log is unknown).
     */
    public static final String ANY_FAMILY = "*";

    /**
     * Trunk walk and base layer limits.
     */
    private static final int MAX_TRUNK_WALK = 64;
    private static final int MAX_BASE_LAYER = 25;

    /**
     * Roots may reach this far below the lowest trunk block.
     */
    private static final int ROOT_DEPTH = 8;

    /**
     * The cluster of touching logs of one wood is read this far past the tree's radius, and at most this many logs, to find the neighbours' trunks.
     */
    private static final int SPLIT_MARGIN = 12;
    private static final int MAX_CLUSTER  = 8192;

    /**
     * A log on the ground this far from the trunk is still part of it (a trunk on a slope); further out it is the trunk of another tree.
     */
    private static final int TRUNK_GAP = 2;

    /**
     * Logs read at most to tell a natural tree of another wood from a post or a beam.
     */
    private static final int NATURAL_BUDGET = 512;

    /**
     * Logs the off-thread tree check floods at most.
     */
    private static final int CHECK_BUDGET = 64;

    /**
     * Leaves a tree needs to count as one.
     */
    private static final int MIN_LEAVES = 3;

    /**
     * Leaves, caps and decay checks one sweep round reads at most.
     */
    public static final int LEAF_BUDGET = 1024;

    /**
     * A cap this far from a stem belongs to its mushroom or fungus: the side of a huge red mushroom's cap hangs two blocks out from the stem and meets its
     * top only at the edges.
     */
    public static final int CAP_REACH = 2;

    private static final Map<Block, String> FAMILIES     = new ConcurrentHashMap<>();
    private static final Map<Block, String> TAG_FAMILIES = new ConcurrentHashMap<>();
    private static volatile int familyEpoch = -1;

    private TreeFootprint()
    {
    }

    /**
     * Why a tree is not cut.
     */
    public enum Skip
    {
        NONE,
        /**
         * A log touches a player build.
         */
        BUILD,
        /**
         * A log is inside a colony hut box.
         */
        HUT,
        /**
         * A log is in a chunk another colony claims.
         */
        CLAIM,
        /**
         * The tree is bigger than the budget.
         */
        TOO_BIG
    }

    /**
     * The result of a scan.
     */
    public static final class Footprint
    {
        public final BlockPos             base;
        public final List<BlockPos>       logs       = new ArrayList<>();
        public final List<BlockPos>       baseLayer  = new ArrayList<>();
        public final List<BlockPos>       decorations = new ArrayList<>();
        /**
         * Leaves at {@code DISTANCE} 1 next to this tree's logs: how many of each block, and one position of each.
         */
        public final Map<Block, Integer>  nearLeafCount = new LinkedHashMap<>();
        public final Map<Block, BlockPos> nearLeafPos   = new HashMap<>();
        public int                        leaves;
        /**
         * True if the trunk itself is stripped (some nether features): stripped logs next to it are no player work.
         */
        public boolean                    strippedBase;
        public Skip                       skip = Skip.NONE;
        public String                     skipDetail = "";
        public final String               family;

        private Footprint(final BlockPos base, final String family)
        {
            this.base = base;
            this.family = family;
        }

        /**
         * @return the box around the logs and decorations.
         */
        public AABB box()
        {
            int minX = base.getX(), minY = base.getY(), minZ = base.getZ(), maxX = minX, maxY = minY, maxZ = minZ;
            for (final List<BlockPos> list : List.of(logs, decorations))
            {
                for (final BlockPos pos : list)
                {
                    minX = Math.min(minX, pos.getX());
                    minY = Math.min(minY, pos.getY());
                    minZ = Math.min(minZ, pos.getZ());
                    maxX = Math.max(maxX, pos.getX());
                    maxY = Math.max(maxY, pos.getY());
                    maxZ = Math.max(maxZ, pos.getZ());
                }
            }
            return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        }

        /**
         * @return the leaf block seen most often next to the logs, or null.
         */
        @Nullable
        public BlockPos mostCommonNearLeaf()
        {
            Block best = null;
            int count = 0;
            for (final Map.Entry<Block, Integer> entry : nearLeafCount.entrySet())
            {
                if (entry.getValue() > count)
                {
                    best = entry.getKey();
                    count = entry.getValue();
                }
            }
            return best == null ? null : nearLeafPos.get(best);
        }
    }

    /**
     * @param state a block state.
     * @return true if a Forester takes the block for a part of a tree trunk, branch or root.
     */
    public static boolean isTreeBlock(@NotNull final BlockState state)
    {
        if (state.isAir() || state.is(ModTags.treeExcluded))
        {
            return false;
        }
        return state.is(ModTags.tree) || tagFamily(state.getBlock()) != null || Compatibility.treeChop.isChoppedLog(state);
    }

    /**
     * The wood family of a tree block.
     *
     * @param level        the level.
     * @param pos          its position.
     * @param state        its state.
     * @param serverThread true to read a chopped log's original log (block entity); off the server thread a chopped log matches any family.
     * @return the family key, or {@link #ANY_FAMILY}.
     */
    @NotNull
    public static String family(@NotNull final LevelReader level, @NotNull final BlockPos pos, @NotNull final BlockState state, final boolean serverThread)
    {
        if (Compatibility.treeChop.isChoppedLog(state))
        {
            final BlockState original = serverThread ? Compatibility.treeChop.getOriginalLogState(level, pos) : null;
            return original == null || Compatibility.treeChop.isChoppedLog(original) ? ANY_FAMILY : family(original.getBlock());
        }
        if (state.is(ModTags.extraTree))
        {
            return ANY_FAMILY;
        }
        return family(state.getBlock());
    }

    /**
     * @param a a family.
     * @param b another.
     * @return true if they are one family.
     */
    public static boolean sameFamily(@NotNull final String a, @NotNull final String b)
    {
        return a.equals(b) || ANY_FAMILY.equals(a) || ANY_FAMILY.equals(b);
    }

    private static void checkEpoch()
    {
        if (familyEpoch != ModTags.tagsEpoch)
        {
            FAMILIES.clear();
            TAG_FAMILIES.clear();
            familyEpoch = ModTags.tagsEpoch;
        }
    }

    @NotNull
    private static String family(@NotNull final Block block)
    {
        checkEpoch();
        return FAMILIES.computeIfAbsent(block, b -> {
            final String tag = tagFamily(b);
            if (tag != null)
            {
                return "tag:" + tag;
            }
            final Identifier id = BuiltInRegistries.BLOCK.getKey(b);
            String path = id.getPath();
            if (path.startsWith("stripped_"))
            {
                path = path.substring("stripped_".length());
            }
            return id.getNamespace() + ":" + path.replaceFirst("(_log|_wood|_stem|_hyphae|_roots)$", "");
        });
    }

    @Nullable
    private static String tagFamily(@NotNull final Block block)
    {
        checkEpoch();
        final String tag = TAG_FAMILIES.computeIfAbsent(block, b -> b.builtInRegistryHolder().tags()
          .filter(key -> key.location().getNamespace().equals(Constants.MOD_ID) && key.location().getPath().startsWith(ModTags.TREE_FAMILY_PREFIX))
          .map(key -> key.location().getPath())
          .findFirst()
          .orElse(""));
        return tag.isEmpty() ? null : tag;
    }

    private static boolean isStripped(@NotNull final BlockState state)
    {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().startsWith("stripped_");
    }

    /**
     * Whether a log of the tree's family has the bark of the tree: natural trees are stripped all through or not at all, so a stripped log on a natural
     * trunk (or a natural log on a stripped one) is a player's work. A TreeChop chopped log counts by the log it was made from (as it is when unknown).
     *
     * @param level        the level.
     * @param pos          the log.
     * @param state        its state.
     * @param strippedBase true if the trunk itself is stripped.
     * @param serverThread true to read a chopped log's original log (block entity).
     * @return true if it matches the trunk.
     */
    private static boolean barkMatches(
      @NotNull final LevelReader level,
      @NotNull final BlockPos pos,
      @NotNull final BlockState state,
      final boolean strippedBase,
      final boolean serverThread)
    {
        if (Compatibility.treeChop.isChoppedLog(state))
        {
            final BlockState original = serverThread ? Compatibility.treeChop.getOriginalLogState(level, pos) : null;
            return original == null || Compatibility.treeChop.isChoppedLog(original) || isStripped(original) == strippedBase;
        }
        return isStripped(state) == strippedBase;
    }

    /**
     * @param state a block state.
     * @return true for leaves that decay by {@code DISTANCE}.
     */
    public static boolean isDecayingLeaves(@NotNull final BlockState state)
    {
        return state.hasProperty(LeavesBlock.DISTANCE) && state.hasProperty(LeavesBlock.PERSISTENT);
    }

    /**
     * @param state a block state.
     * @return true for the leaves of a mushroom or nether tree: caps, wart blocks, shroomlight, and leaves without a {@code DISTANCE}.
     */
    public static boolean isCap(@NotNull final BlockState state)
    {
        return !state.hasProperty(LeavesBlock.DISTANCE)
                 && (state.is(BlockTags.LEAVES) || state.is(ModTags.hugeMushroomBlocks) || state.is(BlockTags.WART_BLOCKS) || state.is(Blocks.SHROOMLIGHT));
    }

    /**
     * Counts the leaves and caps on the 3x3 ring above a log (a brown mushroom's flat cap touches its stem only from above).
     */
    private static int leavesAbove(@NotNull final LevelReader level, @NotNull final BlockPos log)
    {
        int count = 0;
        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dz = -1; dz <= 1; dz++)
            {
                if (dx == 0 && dz == 0)
                {
                    continue;
                }
                final BlockState state = level.getBlockState(log.offset(dx, 1, dz));
                if (isCap(state) || (isDecayingLeaves(state) && !state.getValue(LeavesBlock.PERSISTENT)))
                {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Whether a block next to a log shows a player build.
     *
     * @param state        the neighbour.
     * @param strippedBase true if the tree itself is stripped (some nether features).
     * @return true for a build signal.
     */
    public static boolean isBuildSignal(@NotNull final BlockState state, final boolean strippedBase)
    {
        if (state.isAir())
        {
            return false;
        }
        if (state.is(BlockTags.LEAVES))
        {
            return state.getOptionalValue(LeavesBlock.PERSISTENT).orElse(false);
        }
        if (state.is(BlockTags.PLANKS) || state.is(BlockTags.STAIRS) || state.is(BlockTags.SLABS) || state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)
              || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.BEDS)
              || state.is(BlockTags.SIGNS) || state.is(BlockTags.BANNERS) || state.is(BlockTags.CANDLES) || state.is(BlockTags.WOOL)
              || state.is(BlockTags.WOOL_CARPETS) || state.is(BlockTags.BUTTONS) || state.is(BlockTags.PRESSURE_PLATES) || state.is(BlockTags.LANTERNS)
              || state.is(BlockTags.CAMPFIRES) || state.is(BlockTags.RAILS) || state.is(Tags.Blocks.GLASS_BLOCKS) || state.is(Tags.Blocks.GLASS_PANES)
              || state.getBlock() instanceof BaseTorchBlock)
        {
            return true;
        }
        if (!strippedBase && isTreeBlock(state) && isStripped(state))
        {
            return true;
        }
        return state.hasBlockEntity() && !state.is(Blocks.BEE_NEST) && !state.is(Blocks.CREAKING_HEART) && !Compatibility.treeChop.isChoppedLog(state)
                 && !state.is(ModTags.extraTree);
    }

    /**
     * @param state a neighbour of a log.
     * @return true for blocks that hang on a tree and go with it (a natural bee nest, never a crafted beehive; cocoa).
     */
    public static boolean isDecoration(@NotNull final BlockState state)
    {
        return state.is(Blocks.BEE_NEST) || state.is(Blocks.COCOA);
    }

    /**
     * Scan the whole tree a log belongs to. Server thread.
     *
     * @param level  the level.
     * @param start  a log of the tree.
     * @param colony the Forester's colony (its hut boxes are never cut, other colonies' claims never entered), or null.
     * @param budget the maximum number of logs.
     * @return the footprint, or null if the block is no tree block.
     */
    @Nullable
    public static Footprint scan(@NotNull final Level level, @NotNull final BlockPos start, @Nullable final IColony colony, final int budget)
    {
        final BlockState startState = level.getBlockState(start);
        if (!isTreeBlock(startState))
        {
            return null;
        }
        String fam = family(level, start, startState, true);

        // Walk down the trunk to its lowest block.
        BlockPos bottom = start;
        for (int i = 0; i < MAX_TRUNK_WALK && bottom.getY() > level.getMinY(); i++)
        {
            final BlockPos below = bottom.below();
            final BlockState belowState = level.getBlockState(below);
            if (!isTreeBlock(belowState) || !sameFamily(fam, family(level, below, belowState, true)))
            {
                break;
            }
            bottom = below;
            if (ANY_FAMILY.equals(fam))
            {
                fam = family(level, below, belowState, true);
            }
        }

        // The base layer: the trunk's blocks at the bottom height.
        final List<BlockPos> baseLayer = new ArrayList<>();
        final Set<BlockPos> seenBase = new HashSet<>();
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(bottom);
        seenBase.add(bottom);
        while (!queue.isEmpty() && baseLayer.size() < MAX_BASE_LAYER)
        {
            final BlockPos pos = queue.poll();
            baseLayer.add(pos);
            for (final Direction dir : Direction.Plane.HORIZONTAL)
            {
                final BlockPos next = pos.relative(dir);
                if (seenBase.add(next) && Math.abs(next.getX() - bottom.getX()) <= 2 && Math.abs(next.getZ() - bottom.getZ()) <= 2)
                {
                    final BlockState state = level.getBlockState(next);
                    if (isTreeBlock(state) && sameFamily(fam, family(level, next, state, true)))
                    {
                        queue.add(next);
                    }
                }
            }
        }
        double cx = 0, cz = 0;
        for (final BlockPos pos : baseLayer)
        {
            cx += pos.getX();
            cz += pos.getZ();
        }
        cx /= baseLayer.size();
        cz /= baseLayer.size();
        // Roots (mangrove) spread wide from a base that is a root tip, so they get the wide radius.
        final int radius = baseLayer.size() > 4 || level.getBlockState(bottom).is(ModTags.treeRoots) ? 16 : baseLayer.size() <= 1 ? 8 : 10;
        final BlockState bottomState = level.getBlockState(bottom);
        final BlockState bottomLog = Compatibility.treeChop.isChoppedLog(bottomState) ? Compatibility.treeChop.getOriginalLogState(level, bottom) : bottomState;
        final boolean strippedBase = bottomLog != null && isStripped(bottomLog);

        final Footprint result = new Footprint(bottom, fam);
        result.strippedBase = strippedBase;
        result.baseLayer.addAll(baseLayer);

        // Every log of the family that touches this tree (neighbour trees of the same wood included), within a window around the trunk.
        final Set<BlockPos> cluster = new HashSet<>(baseLayer);
        final ArrayDeque<BlockPos> flood = new ArrayDeque<>(baseLayer);
        final int window = radius + SPLIT_MARGIN;
        final int clusterBudget = Math.min(Math.max(1, budget) * 4, MAX_CLUSTER);
        while (!flood.isEmpty())
        {
            final BlockPos log = flood.poll();
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = -1; dy <= 1; dy++)
                {
                    for (int dz = -1; dz <= 1; dz++)
                    {
                        final BlockPos next = log.offset(dx, dy, dz);
                        if (cluster.contains(next) || next.getY() < bottom.getY() - ROOT_DEPTH
                              || Math.max(Math.abs(next.getX() - cx), Math.abs(next.getZ() - cz)) > window + 0.5D)
                        {
                            continue;
                        }
                        if (!isFamilyLog(level, next, level.getBlockState(next), fam, strippedBase))
                        {
                            continue;
                        }
                        if (cluster.size() >= clusterBudget)
                        {
                            result.skip = Skip.TOO_BIG;
                            result.skipDetail = "more than " + clusterBudget + " logs of the wood around the trunk";
                            return result;
                        }
                        cluster.add(next);
                        flood.add(next);
                    }
                }
            }
        }

        // The trunks of the neighbour trees in it: a log on the ground away from this trunk (never a root, mangroves stand on many). Each log goes to the
        // trunk it is nearest to along the wood, so touching trees of one wood are cut one by one; ties go to the neighbour.
        final Set<BlockPos> own = ownLogs(level, cluster, baseLayer, cx, cz);

        final Set<BlockPos> seen = new HashSet<>(baseLayer);
        final Set<BlockPos> decorations = new HashSet<>();
        final Map<Long, Integer> claimOwners = new HashMap<>();
        final Map<BlockPos, Boolean> naturalNeighbours = new HashMap<>();
        queue.clear();
        queue.addAll(baseLayer);
        final int ownId = colony == null ? 0 : colony.getID();
        final Collection<IBuilding> buildings = colony == null ? List.of() : colony.getServerBuildingManager().getBuildings().values();
        while (!queue.isEmpty())
        {
            final BlockPos log = queue.poll();
            result.logs.add(log);
            if (result.logs.size() > budget)
            {
                result.skip = Skip.TOO_BIG;
                result.skipDetail = "more than " + budget + " logs";
                return result;
            }

            // Guard: hut boxes, other colonies' claims, build signals next to the log.
            for (final IBuilding building : buildings)
            {
                if (building.isInBuilding(log))
                {
                    result.skip = Skip.HUT;
                    result.skipDetail = "log " + log.toShortString() + " in the box of " + building.getSchematicName();
                    return result;
                }
            }
            final long chunk = ChunkPos.pack(log);
            final int owner = claimOwners.computeIfAbsent(chunk, c -> {
                final IColony found = IColonyManager.getInstance().getColonyByPosFromWorld(level, log);
                return found == null ? 0 : found.getID();
            });
            if (owner != 0 && owner != ownId)
            {
                result.skip = Skip.CLAIM;
                result.skipDetail = "log " + log.toShortString() + " in the claim of colony " + owner;
                return result;
            }

            for (final Direction dir : Direction.values())
            {
                final BlockPos near = log.relative(dir);
                if (seen.contains(near) || decorations.contains(near))
                {
                    continue;
                }
                final BlockState state = level.getBlockState(near);
                if (isTreeBlock(state))
                {
                    if (sameFamily(fam, family(level, near, state, true)) && barkMatches(level, near, state, strippedBase, true))
                    {
                        continue;
                    }
                    if (!sameFamily(fam, family(level, near, state, true)) && isNaturalNeighbourTree(level, near, state, naturalNeighbours))
                    {
                        // A natural tree of another wood touches this one: each is cut on its own
                        continue;
                    }
                    // A stripped log on a natural trunk, or a log of another wood that is no tree (a post, a beam): a player's work touches the tree
                    result.skip = Skip.BUILD;
                    result.skipDetail = state.getBlock() + " at " + near.toShortString() + " next to log " + log.toShortString();
                    return result;
                }
                if (isDecoration(state))
                {
                    decorations.add(near);
                    result.decorations.add(near);
                    continue;
                }
                if (isBuildSignal(state, strippedBase))
                {
                    result.skip = Skip.BUILD;
                    result.skipDetail = state.getBlock() + " at " + near.toShortString() + " next to log " + log.toShortString();
                    return result;
                }
                if (isDecayingLeaves(state))
                {
                    result.leaves++;
                    if (state.getValue(LeavesBlock.DISTANCE) == 1)
                    {
                        result.nearLeafCount.merge(state.getBlock(), 1, Integer::sum);
                        result.nearLeafPos.putIfAbsent(state.getBlock(), near);
                    }
                }
                else if (isCap(state))
                {
                    result.leaves++;
                }
            }

            if (result.leaves < MIN_LEAVES && !isTreeBlock(level.getBlockState(log.above())))
            {
                result.leaves += leavesAbove(level, log);
            }

            // Flood to the next logs of this tree.
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = -1; dy <= 1; dy++)
                {
                    for (int dz = -1; dz <= 1; dz++)
                    {
                        final BlockPos next = log.offset(dx, dy, dz);
                        if (!own.contains(next) || !seen.add(next))
                        {
                            continue;
                        }
                        if (next.getY() < bottom.getY() && !(level.getBlockState(next).is(ModTags.treeRoots) && next.getY() >= bottom.getY() - ROOT_DEPTH))
                        {
                            seen.remove(next);
                            continue;
                        }
                        if (Math.max(Math.abs(next.getX() - cx), Math.abs(next.getZ() - cz)) > radius + 0.5D)
                        {
                            // A branch of the tree goes on past the radius: cutting up to the radius would leave it standing, so the whole tree stays
                            result.skip = Skip.TOO_BIG;
                            result.skipDetail = "log " + next.toShortString() + " past the radius " + radius + " of the trunk";
                            return result;
                        }
                        queue.add(next);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Whether the flood goes on to a block: a tree block of the family with the bark of the trunk, a creaking heart only inside a trunk.
     */
    private static boolean isFamilyLog(
      @NotNull final Level level,
      @NotNull final BlockPos pos,
      @NotNull final BlockState state,
      @NotNull final String fam,
      final boolean strippedBase)
    {
        if (!isTreeBlock(state) || !sameFamily(fam, family(level, pos, state, true)))
        {
            return false;
        }
        if (!barkMatches(level, pos, state, strippedBase, true))
        {
            // A stripped log on a natural tree (a natural log on a stripped one) is a player's work; the 6-neighbour guard decides when it touches a log.
            return false;
        }
        return !state.is(Blocks.CREAKING_HEART) || (isTreeBlock(level.getBlockState(pos.above())) && isTreeBlock(level.getBlockState(pos.below())));
    }

    /**
     * @param state the block under a log.
     * @return true for natural ground a trunk stands on (no tree block, no leaf, no player block).
     */
    private static boolean isGround(@NotNull final BlockState state)
    {
        return !state.isAir() && !isTreeBlock(state) && !state.is(BlockTags.LEAVES) && !isCap(state) && BlockUtils.isAnySolid(state)
                 && state.getBlock() != Blocks.COBBLESTONE && !isBuildSignal(state, false);
    }

    /**
     * Splits a cluster of touching logs of one wood by trunk: each log goes to the trunk it is nearest to along the wood (ties to the neighbour).
     *
     * @param level     the level.
     * @param cluster   the logs of the wood that touch the tree.
     * @param baseLayer this tree's trunk at its bottom.
     * @param cx        the trunk's centre x.
     * @param cz        the trunk's centre z.
     * @return the logs of this tree.
     */
    private static Set<BlockPos> ownLogs(
      @NotNull final Level level,
      @NotNull final Set<BlockPos> cluster,
      @NotNull final List<BlockPos> baseLayer,
      final double cx,
      final double cz)
    {
        double baseReach = 0;
        for (final BlockPos pos : baseLayer)
        {
            baseReach = Math.max(baseReach, Math.max(Math.abs(pos.getX() - cx), Math.abs(pos.getZ() - cz)));
        }
        final Set<BlockPos> ownBase = new HashSet<>(baseLayer);
        final List<BlockPos> otherBases = new ArrayList<>();
        for (final BlockPos pos : cluster)
        {
            if (ownBase.contains(pos) || Math.max(Math.abs(pos.getX() - cx), Math.abs(pos.getZ() - cz)) <= baseReach + TRUNK_GAP + 0.5D)
            {
                continue;
            }
            final BlockState state = level.getBlockState(pos);
            if (!state.is(ModTags.treeRoots) && !cluster.contains(pos.below()) && isGround(level.getBlockState(pos.below())))
            {
                otherBases.add(pos);
            }
        }
        if (otherBases.isEmpty())
        {
            return cluster;
        }
        // Many-source flood: the neighbours' trunks first, so a log as near to both goes to the neighbour
        final Map<BlockPos, Boolean> mine = new HashMap<>();
        final ArrayDeque<BlockPos> wave = new ArrayDeque<>();
        for (final BlockPos pos : otherBases)
        {
            mine.put(pos, false);
            wave.add(pos);
        }
        for (final BlockPos pos : baseLayer)
        {
            mine.put(pos, true);
            wave.add(pos);
        }
        while (!wave.isEmpty())
        {
            final BlockPos pos = wave.poll();
            final boolean owner = mine.get(pos);
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = -1; dy <= 1; dy++)
                {
                    for (int dz = -1; dz <= 1; dz++)
                    {
                        final BlockPos next = pos.offset(dx, dy, dz);
                        if (cluster.contains(next) && !mine.containsKey(next))
                        {
                            mine.put(next, owner);
                            wave.add(next);
                        }
                    }
                }
            }
        }
        final Set<BlockPos> own = new HashSet<>();
        for (final Map.Entry<BlockPos, Boolean> entry : mine.entrySet())
        {
            if (entry.getValue())
            {
                own.add(entry.getKey());
            }
        }
        return own;
    }

    /**
     * Whether a log of another wood next to a tree belongs to a natural tree of its own (cut on its own merits) rather than to a player's post or beam:
     * not stripped, standing on natural ground, and holding leaves of its own wood (or caps).
     *
     * @param level the level.
     * @param start the log of the other wood.
     * @param state its state.
     * @param cache answers so far for this tree, by log (may be null).
     * @return true for a natural tree.
     */
    public static boolean isNaturalNeighbourTree(
      @NotNull final LevelReader level,
      @NotNull final BlockPos start,
      @NotNull final BlockState state,
      @Nullable final Map<BlockPos, Boolean> cache)
    {
        if (cache != null && cache.containsKey(start))
        {
            return cache.get(start);
        }
        final String fam = family(level, start, state, true);
        final Set<BlockPos> logs = new HashSet<>();
        boolean natural = false;
        if (!ANY_FAMILY.equals(fam) && !isStripped(state) && !Compatibility.treeChop.isChoppedLog(state))
        {
            final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            logs.add(start);
            boolean base = false;
            int leaves = 0;
            while (!queue.isEmpty() && logs.size() <= NATURAL_BUDGET && !(base && leaves >= MIN_LEAVES))
            {
                final BlockPos log = queue.poll();
                final BlockState below = level.getBlockState(log.below());
                if (level.getBlockState(log).is(ModTags.treeRoots) || (!isTreeBlock(below) && isGround(below)))
                {
                    base = true;
                }
                for (final Direction dir : Direction.values())
                {
                    final BlockState near = level.getBlockState(log.relative(dir));
                    if (isCap(near) || (isDecayingLeaves(near) && !near.getValue(LeavesBlock.PERSISTENT) && leafOfFamily(fam, near)))
                    {
                        leaves++;
                    }
                }
                for (int dx = -1; dx <= 1; dx++)
                {
                    for (int dy = -1; dy <= 1; dy++)
                    {
                        for (int dz = -1; dz <= 1; dz++)
                        {
                            final BlockPos next = log.offset(dx, dy, dz);
                            if (logs.contains(next))
                            {
                                continue;
                            }
                            final BlockState nextState = level.getBlockState(next);
                            if (isTreeBlock(nextState) && !isStripped(nextState) && sameFamily(fam, family(level, next, nextState, true)))
                            {
                                logs.add(next);
                                queue.add(next);
                            }
                        }
                    }
                }
            }
            natural = base && leaves >= MIN_LEAVES;
        }
        if (cache != null)
        {
            for (final BlockPos log : logs)
            {
                cache.put(log, natural);
            }
            cache.put(start, natural);
        }
        return natural;
    }

    /**
     * @param fam   a wood family.
     * @param state a leaf.
     * @return true if the leaf grows on that wood ({@code birch_leaves} on birch; azalea leaves on oak; any leaf for a family of a tag).
     */
    private static boolean leafOfFamily(@NotNull final String fam, @NotNull final BlockState state)
    {
        if (fam.startsWith("tag:"))
        {
            return true;
        }
        final String wood = fam.substring(fam.indexOf(':') + 1);
        final String leaf = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return leaf.startsWith(wood) || (wood.equals("oak") && leaf.contains("azalea"));
    }

    /**
     * Which part of a chosen tree a block is, for {@link #recheck}.
     */
    public enum Part
    {
        LOG,
        LEAF,
        DECORATION
    }

    /**
     * What {@link #recheck} found.
     */
    public enum Recheck
    {
        /**
         * Still the tree's own block, outside the build guard: it may go.
         */
        OK,
        /**
         * Gone already (air, or a block anything may replace): nothing to break.
         */
        GONE,
        /**
         * Something else stands there now (planks in place of a log, a placed leaf, another wood).
         */
        CHANGED,
        /**
         * A log now touches a player build (a leaf or cap: a player's block other than a leaf).
         */
        BUILD,
        /**
         * The block is in a colony hut box.
         */
        HUT,
        /**
         * The block is in a chunk another colony claims.
         */
        CLAIM
    }

    /**
     * The scan's rules for one block of a chosen tree, read again right before a Forester breaks it: blocks change after the tree was chosen (a player
     * puts planks in place of a log or builds next to the tree, a colony claims the chunk). Server thread.
     *
     * @param level        the level.
     * @param pos          the block.
     * @param part         which part of the tree it was when the tree was chosen.
     * @param family       the tree's wood family ({@link #ANY_FAMILY} if unknown).
     * @param strippedBase true if the trunk itself is stripped.
     * @param colony       the Forester's colony, or null.
     * @return what was found.
     */
    @NotNull
    public static Recheck recheck(
      @NotNull final Level level,
      @NotNull final BlockPos pos,
      @NotNull final Part part,
      @NotNull final String family,
      final boolean strippedBase,
      @Nullable final IColony colony)
    {
        final BlockState state = level.getBlockState(pos);
        if (state.isAir() || (state.canBeReplaced() && !state.is(BlockTags.LEAVES)))
        {
            return Recheck.GONE;
        }
        final boolean own = switch (part)
        {
            case LOG -> isTreeBlock(state) && sameFamily(family, family(level, pos, state, true)) && (strippedBase || !isStripped(state));
            case LEAF -> isDecayingLeaves(state) ? !state.getValue(LeavesBlock.PERSISTENT) : isCap(state);
            case DECORATION -> isDecoration(state);
        };
        if (!own)
        {
            return Recheck.CHANGED;
        }
        final Recheck guard = guardPosition(level, pos, colony);
        if (guard != Recheck.OK || part == Part.DECORATION)
        {
            return guard;
        }
        if (part == Part.LEAF)
        {
            // A leaf or cap touching a player's block (a torch, a fence, a lantern) stays; a placed leaf next to it does not count, a hedge under a
            // canopy is no reason to leave its doomed leaves
            for (final Direction dir : Direction.values())
            {
                final BlockState near = level.getBlockState(pos.relative(dir));
                if (!near.is(BlockTags.LEAVES) && !isDecoration(near) && isBuildSignal(near, strippedBase))
                {
                    return Recheck.BUILD;
                }
            }
            return Recheck.OK;
        }
        for (final Direction dir : Direction.values())
        {
            final BlockState near = level.getBlockState(pos.relative(dir));
            if (isTreeBlock(near))
            {
                if (sameFamily(family, family(level, pos.relative(dir), near, true)) && barkMatches(level, pos.relative(dir), near, strippedBase, true))
                {
                    continue;
                }
                if (!sameFamily(family, family(level, pos.relative(dir), near, true)) && isNaturalNeighbourTree(level, pos.relative(dir), near, null))
                {
                    continue;
                }
                return Recheck.BUILD;
            }
            if (!isDecoration(near) && isBuildSignal(near, strippedBase))
            {
                return Recheck.BUILD;
            }
        }
        return Recheck.OK;
    }

    /**
     * The hut and claim part of the build guard for one position. Server thread.
     *
     * @param level  the level.
     * @param pos    the position.
     * @param colony the Forester's colony, or null.
     * @return {@link Recheck#HUT}, {@link Recheck#CLAIM} or {@link Recheck#OK}.
     */
    @NotNull
    public static Recheck guardPosition(@NotNull final Level level, @NotNull final BlockPos pos, @Nullable final IColony colony)
    {
        if (colony != null)
        {
            for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
            {
                if (building.isInBuilding(pos))
                {
                    return Recheck.HUT;
                }
            }
        }
        final IColony owner = IColonyManager.getInstance().getColonyByPosFromWorld(level, pos);
        if (owner != null && (colony == null || owner.getID() != colony.getID()))
        {
            return Recheck.CLAIM;
        }
        return Recheck.OK;
    }

    /**
     * The tree check for the tree search, off the server thread: a small flood over the family from the given log, which needs enough non-persistent
     * leaves (or caps) touching its logs, a solid base that is not cobblestone, and no log claimed by another Forester or blocked as next to a build.
     *
     * @param world        the world to read.
     * @param pos          the log.
     * @param claimLevel   the level of the claims, or null to skip the claim check.
     * @param colonyId     the asking Forester's colony.
     * @param citizenId    the asking Forester's citizen id.
     * @param supposedToCut asked once with the first leaf found: false if the Forester must not cut this tree.
     * @return true if this is a tree for him.
     */
    public static boolean check(
      @NotNull final LevelReader world,
      @NotNull final BlockPos pos,
      @Nullable final Level claimLevel,
      final int colonyId,
      final int citizenId,
      @NotNull final Predicate<BlockPos> supposedToCut)
    {
        final BlockState startState = world.getBlockState(pos);
        if (!isTreeBlock(startState))
        {
            return false;
        }
        final String fam = family(world, pos, startState, false);
        BlockPos bottom = pos;
        for (int i = 0; i < MAX_TRUNK_WALK / 2; i++)
        {
            final BlockPos below = bottom.below();
            final BlockState belowState = world.getBlockState(below);
            if (!isTreeBlock(belowState) || !sameFamily(fam, family(world, below, belowState, false)))
            {
                break;
            }
            bottom = below;
        }
        final BlockState ground = world.getBlockState(bottom.below());
        if (!(BlockUtils.isAnySolid(ground) || world.getBlockState(bottom).is(ModTags.treeRoots)) || ground.getBlock() == Blocks.COBBLESTONE)
        {
            return false;
        }

        // The top of the trunk column: a tall tree with a bare trunk (a mega jungle, 4 logs a layer and leaves only on the upper half) has no leaves
        // within the flood budget from the base, so the flood starts from both ends.
        BlockPos top = bottom;
        for (int i = 0; i < MAX_TRUNK_WALK; i++)
        {
            final BlockPos above = top.above();
            final BlockState aboveState = world.getBlockState(above);
            if (!isTreeBlock(aboveState) || !sameFamily(fam, family(world, above, aboveState, false)))
            {
                break;
            }
            top = above;
        }

        final Set<BlockPos> seen = new HashSet<>();
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(bottom);
        seen.add(bottom);
        if (seen.add(top))
        {
            queue.add(top);
        }
        int logs = 0;
        int leaves = 0;
        boolean askedLeaf = false;
        while (!queue.isEmpty() && logs < CHECK_BUDGET)
        {
            final BlockPos log = queue.poll();
            logs++;
            if (claimLevel != null && TreeClaims.isTakenByOther(claimLevel, log, colonyId, citizenId))
            {
                return false;
            }
            for (final Direction dir : Direction.values())
            {
                final BlockPos near = log.relative(dir);
                final BlockState state = world.getBlockState(near);
                final boolean leaf = isDecayingLeaves(state) ? !state.getValue(LeavesBlock.PERSISTENT) : isCap(state);
                if (leaf && leaves < MIN_LEAVES)
                {
                    if (!askedLeaf)
                    {
                        askedLeaf = true;
                        if (!supposedToCut.test(near))
                        {
                            return false;
                        }
                    }
                    leaves++;
                }
            }
            if (leaves < MIN_LEAVES && !isTreeBlock(world.getBlockState(log.above())))
            {
                leaves += leavesAbove(world, log);
            }
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = 0; dy <= 1; dy++)
                {
                    for (int dz = -1; dz <= 1; dz++)
                    {
                        final BlockPos next = log.offset(dx, dy, dz);
                        if (seen.add(next))
                        {
                            final BlockState state = world.getBlockState(next);
                            if (isTreeBlock(state) && sameFamily(fam, family(world, next, state, false)))
                            {
                                queue.add(next);
                            }
                        }
                    }
                }
            }
        }
        return leaves >= MIN_LEAVES;
    }

    /**
     * The leaf sweep after a felling: rounds ten ticks apart, each a flood from the leaves that touched the removed logs (and the leaves of the previous
     * round that were still held), which hands out the leaves vanilla would decay. Server thread; at most {@link #LEAF_BUDGET} reads per round.
     */
    public static final class LeafSweep
    {
        private static final int MIN_ROUNDS     = 3;
        private static final int EMPTY_ROUNDS   = 2;
        private static final int MAX_ROUNDS     = 10;
        private static final int ROUND_INTERVAL = 10;

        /**
         * Caps {@link TreeFootprint#CAP_REACH} from a removed stem seed the sweep of a mushroom or fungus of at most {@link #CAP_SEED_LOGS} stems.
         */
        private static final int CAP_SEED_LOGS = 128;

        /**
         * Doomed leaves this far around the tree's box are swept too, when the area is at most {@link #SCAN_VOLUME} blocks.
         */
        private static final int LEAF_REACH  = LeavesBlock.DECAY_DISTANCE - 1;
        private static final int SCAN_VOLUME = 65536;

        private final Level               level;
        private final Set<BlockPos>       removedLogs;
        private final ArrayDeque<BlockPos> toBreak = new ArrayDeque<>();
        private final Set<BlockPos>       capsSeen = new HashSet<>();
        private       Set<BlockPos>       seeds   = new HashSet<>();
        private       int                 rounds  = 0;
        private       int                 emptyRounds = 0;
        private       long                nextRound;
        private       boolean             done    = false;
        private       int                 broken  = 0;

        /**
         * @param level       the level.
         * @param removedLogs the logs of the felled tree.
         * @param box         the box of the tree's logs: doomed leaves around it are swept, and all its leaves when the removed logs are unknown (after a
         *                    reload).
         * @param capTree     true for a huge mushroom or fungus, whose cap may hang away from the stem.
         */
        public LeafSweep(@NotNull final Level level, @NotNull final Collection<BlockPos> removedLogs, @Nullable final AABB box, final boolean capTree)
        {
            this.level = level;
            this.removedLogs = new HashSet<>(removedLogs);
            this.nextRound = level.getGameTime() + ROUND_INTERVAL;
            for (final BlockPos log : this.removedLogs)
            {
                for (final Direction dir : Direction.values())
                {
                    final BlockPos near = log.relative(dir);
                    final BlockState state = level.getBlockState(near);
                    if (isDecayingLeaves(state) || isCap(state))
                    {
                        seeds.add(near);
                    }
                }
            }
            if (capTree && this.removedLogs.size() <= CAP_SEED_LOGS)
            {
                // The side of a huge red mushroom's cap hangs two blocks out from the stem and meets its top only at the edges
                for (final BlockPos log : this.removedLogs)
                {
                    for (final BlockPos near : BlockPos.betweenClosed(log.offset(-CAP_REACH, -CAP_REACH, -CAP_REACH), log.offset(CAP_REACH, CAP_REACH, CAP_REACH)))
                    {
                        if (isCap(level.getBlockState(near)))
                        {
                            seeds.add(near.immutable());
                        }
                    }
                }
            }
            if (!this.removedLogs.isEmpty() && box != null)
            {
                // Leaves vanilla dooms from the start (a stray leaf of a random canopy touching no other leaf) are not reached by the flood: take the
                // doomed ones around the tree too, they would decay anyway
                final AABB around = box.inflate(LEAF_REACH, LEAF_REACH, LEAF_REACH);
                if (around.getXsize() * around.getYsize() * around.getZsize() <= SCAN_VOLUME)
                {
                    for (final BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(around.minX, around.minY, around.minZ),
                      BlockPos.containing(around.maxX - 1, around.maxY - 1, around.maxZ - 1)))
                    {
                        if (isDoomed(level.getBlockState(pos)))
                        {
                            seeds.add(pos.immutable());
                        }
                    }
                }
            }
            if (this.removedLogs.isEmpty() && box != null && box.getXsize() * box.getYsize() * box.getZsize() <= 32768)
            {
                for (final BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ)))
                {
                    final BlockState state = level.getBlockState(pos);
                    if ((isDecayingLeaves(state) && !state.getValue(LeavesBlock.PERSISTENT)) || isCap(state))
                    {
                        seeds.add(pos.immutable());
                    }
                }
            }
        }

        /**
         * @return true once nothing is left to sweep.
         */
        public boolean isDone()
        {
            return done && toBreak.isEmpty();
        }

        /**
         * @return blocks broken so far (counted by the caller through {@link #markBroken}).
         */
        public int getBroken()
        {
            return broken;
        }

        /**
         * Count one broken block.
         */
        public void markBroken()
        {
            broken++;
        }

        /**
         * The next blocks to break, rechecked now. Empty while a round is due later.
         *
         * @param limit how many at most.
         * @return the positions.
         */
        @NotNull
        public List<BlockPos> next(final int limit)
        {
            final List<BlockPos> out = new ArrayList<>();
            if (toBreak.isEmpty() && !done && level.getGameTime() >= nextRound)
            {
                round();
            }
            while (!toBreak.isEmpty() && out.size() < limit)
            {
                final BlockPos pos = toBreak.poll();
                final BlockState state = level.getBlockState(pos);
                if (isDoomed(state) || isCap(state))
                {
                    out.add(pos);
                }
            }
            return out;
        }

        private static boolean isDoomed(final BlockState state)
        {
            return isDecayingLeaves(state) && !state.getValue(LeavesBlock.PERSISTENT) && state.getValue(LeavesBlock.DISTANCE) >= LeavesBlock.DECAY_DISTANCE;
        }

        private void round()
        {
            rounds++;
            nextRound = level.getGameTime() + ROUND_INTERVAL;
            final Set<BlockPos> held = new HashSet<>();
            final Set<BlockPos> visited = new HashSet<>();
            final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            int found = 0;
            for (final BlockPos seed : seeds)
            {
                final BlockState state = level.getBlockState(seed);
                if (isCap(state))
                {
                    found += sweepCaps(seed);
                }
                else if (isDecayingLeaves(state) && !state.getValue(LeavesBlock.PERSISTENT) && visited.add(seed))
                {
                    queue.add(seed);
                }
            }
            while (!queue.isEmpty() && visited.size() < LEAF_BUDGET)
            {
                final BlockPos pos = queue.poll();
                final BlockState state = level.getBlockState(pos);
                if (isDoomed(state))
                {
                    toBreak.add(pos);
                    found++;
                }
                else
                {
                    held.add(pos);
                }
                for (final Direction dir : Direction.values())
                {
                    final BlockPos near = pos.relative(dir);
                    if (!visited.contains(near))
                    {
                        final BlockState nearState = level.getBlockState(near);
                        if (isDecayingLeaves(nearState) && !nearState.getValue(LeavesBlock.PERSISTENT))
                        {
                            visited.add(near);
                            queue.add(near);
                        }
                    }
                }
            }
            seeds = held;
            // Vanilla raises DISTANCE one step per tick, so a canopy turns doomed over a few rounds: stop after two rounds in a row found nothing
            emptyRounds = found == 0 ? emptyRounds + 1 : 0;
            if ((emptyRounds >= EMPTY_ROUNDS && rounds >= MIN_ROUNDS) || rounds >= MAX_ROUNDS || (seeds.isEmpty() && found == 0 && rounds >= MIN_ROUNDS))
            {
                done = true;
            }
        }

        /**
         * A connected cap (mushroom cap, wart blocks, shroomlight) goes when it touches no remaining stem or log.
         */
        private int sweepCaps(final BlockPos seed)
        {
            if (capsSeen.contains(seed))
            {
                return 0;
            }
            final List<BlockPos> component = new ArrayList<>();
            final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed);
            capsSeen.add(seed);
            boolean held = false;
            while (!queue.isEmpty())
            {
                final BlockPos pos = queue.poll();
                component.add(pos);
                if (component.size() > LEAF_BUDGET)
                {
                    return 0;
                }
                // Through faces, edges and corners: a cap's side and top touch only at the edges
                for (int dx = -1; dx <= 1; dx++)
                {
                    for (int dy = -1; dy <= 1; dy++)
                    {
                        for (int dz = -1; dz <= 1; dz++)
                        {
                            final BlockPos near = pos.offset(dx, dy, dz);
                            if ((dx == 0 && dy == 0 && dz == 0) || capsSeen.contains(near))
                            {
                                continue;
                            }
                            final BlockState state = level.getBlockState(near);
                            if (isCap(state))
                            {
                                capsSeen.add(near);
                                queue.add(near);
                            }
                            else if (!removedLogs.contains(near) && isTreeBlock(state))
                            {
                                held = true;
                            }
                        }
                    }
                }
            }
            if (held)
            {
                return 0;
            }
            toBreak.addAll(component);
            return component.size();
        }
    }
}
