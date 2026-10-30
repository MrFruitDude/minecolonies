package com.minecolonies.core.entity.ai.workers.util;

import com.ldtteam.structurize.util.BlockUtils;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.compatibility.Compatibility;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.items.ModTags;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.api.util.BlockStateUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.core.MineColonies;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import com.ldtteam.structurize.api.util.Tuple;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static com.minecolonies.api.items.ModTags.fungi;
import static com.minecolonies.api.util.constant.NbtTagConstants.*;

/**
 * Custom class for Trees. Used by lumberjack
 */
public class Tree
{
    /**
     * Radius propertyname for dynamic trees, used to check growth status
     */
    private static final String DYNAMICTREERADIUS = "radius";

    /**
     * Number of leaves necessary for a tree to be recognized.
     */
    private static final int NUMBER_OF_LEAVES = 3;

    /**
     * Number of leaves in every direction from the middle of the tree.
     */
    private static final int LEAVES_WIDTH = 4;

    /**
     * A lot of luck to get a guaranteed saplings drop.
     */
    private static final int A_LOT_OF_LUCK = 10000;

    /**
     * The location of the tree stump.
     */
    private BlockPos location;

    /**
     * The location of the tree stump.
     */
    private BlockPos topLog;

    /**
     * All wood blocks connected to the tree.
     */
    private LinkedList<BlockPos> woodBlocks = new LinkedList<>();

    /**
     * All leaves of the tree.
     */
    private LinkedList<BlockPos> leaves = new LinkedList<>();

    /**
     * Is the tree a tree?
     */
    private boolean isTree;

    /**
     * The spaling the lj has to use to replant the tree.
     */
    private ItemStack sapling;

    /**
     * The locations of the stumps (Some trees are connected to dirt by 4 logs).
     */
    private ArrayList<BlockPos> stumpLocations;

    /**
     * The wood variant of the Tree. Can change depending on Mod
     */
    private Property<?> variant;

    /**
     * If the Tree is a Slime Tree.
     */
    private boolean slimeTree = false;

    /**
     * If the tree is a Dynamic Tree
     */
    private boolean dynamicTree = false;

    /**
     * If the tree is a Nether Tree
     */
    private boolean netherTree = false;

    /**
     * Forestry: the footprint of the tree when it was chosen (null for Dynamic Trees, slime trees and trees read from NBT).
     */
    @Nullable
    private TreeFootprint.Footprint footprint;

    /**
     * Forestry: blocks that hang on the tree and go with it (bee nest, cocoa), mined before the logs.
     */
    private LinkedList<BlockPos> decorations = new LinkedList<>();

    /**
     * Forestry: every log of the tree as first seen (the leaf sweep starts next to them). Not saved: after a reload the sweep uses the box.
     */
    private final List<BlockPos> allLogs = new ArrayList<>();

    /**
     * Forestry: the box of the tree's logs, saved for the leaf sweep and the drop capture.
     */
    @Nullable
    private BlockPos boxMin;
    @Nullable
    private BlockPos boxMax;

    /**
     * Forestry: the wood family of the tree and whether its trunk is stripped, saved so each block is checked again right before it is broken.
     */
    @NotNull
    private String  family       = TreeFootprint.ANY_FAMILY;
    private boolean strippedBase = false;

    /**
     * Forestry: a block of the tree was broken already (kept over a reload), so a change found now drops that block, not the whole tree.
     */
    private boolean started = false;

    /**
     * Private constructor of the tree. Used by the equals and createFromNBt method.
     */
    private Tree()
    {
        isTree = true;
    }

    /**
     * Creates a new tree Object for the lumberjack. Since the same type of variant of the block old log or new log do not match we have to separate them.
     *
     * @param world  The world where the tree is in.
     * @param log    the position of the found log.
     * @param colony the colony to search for buildings, or null if we don't care.
     */
    public Tree(@NotNull final Level world, @NotNull final BlockPos log, @Nullable final IColony colony)
    {
        final BlockState block = BlockPosUtil.getBlockState(world, log);
        if (!Compatibility.isSlimeBlock(block.getBlock()) && !Compatibility.isDynamicBlock(block.getBlock()) && TreeFootprint.isTreeBlock(block))
        {
            initFromFootprint(world, log, colony);
        }
        else if (Compatibility.isSlimeBlock(block.getBlock()) || Compatibility.isDynamicBlock(block.getBlock()))
        {
            isTree = true;
            woodBlocks = new LinkedList<>();
            leaves = new LinkedList<>();
            location = log;
            topLog = log;

            addAndSearch(world, log, colony);
            addAndSearch(world);

            checkTree(world, topLog);

            dynamicTree = Compatibility.isDynamicBlock(block.getBlock());
            stumpLocations = new ArrayList<>();
            woodBlocks.clear();
            slimeTree = Compatibility.isSlimeBlock(block.getBlock());
            sapling = calcSapling(world);
            if (sapling.is(Tags.Items.MUSHROOMS) || sapling.is(fungi))
            {
                netherTree = true;
            }

            // Calculate the Tree's variant Property, add mod compat for other property names later when needed
            variant = BlockStateUtils.getPropertyByNameFromState(world.getBlockState(location), "variant");
        }
        else
        {
            isTree = false;
        }
    }

    /**
     * Forestry: the whole natural tree from one scan (logs, base, stumps, decorations, the sapling from the leaves next to its own logs).
     */
    private void initFromFootprint(@NotNull final Level world, @NotNull final BlockPos log, @Nullable final IColony colony)
    {
        footprint = TreeFootprint.scan(world, log, colony, MineColonies.getConfig().getServer().maxTreeSize.get());
        if (footprint == null || footprint.logs.isEmpty())
        {
            isTree = false;
            return;
        }
        location = footprint.base;
        topLog = footprint.base;
        for (final BlockPos pos : footprint.logs)
        {
            if (pos.getY() > topLog.getY())
            {
                topLog = pos;
            }
        }
        // A tree the scan skips (next to a build, too big) still counts as a tree, so the Forester records the skip instead of picking it again
        isTree = footprint.skip != TreeFootprint.Skip.NONE || footprint.leaves >= NUMBER_OF_LEAVES;
        woodBlocks = new LinkedList<>(footprint.logs);
        woodBlocks.sort((c1, c2) -> (int) (c1.distSqr(location) - c2.distSqr(location)));
        allLogs.addAll(footprint.logs);
        family = footprint.family;
        strippedBase = footprint.strippedBase;
        decorations = new LinkedList<>(footprint.decorations);
        final net.minecraft.world.phys.AABB box = footprint.box();
        boxMin = BlockPos.containing(box.minX, box.minY, box.minZ);
        boxMax = BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1);
        leaves = collectOwnLeaves(world);

        final BlockPos nearLeaf = footprint.mostCommonNearLeaf();
        final ItemStack fromNear = nearLeaf == null ? null : calcSaplingForPos(world, nearLeaf, false);
        sapling = fromNear != null ? fromNear : calcSapling(world);
        netherTree = sapling.is(Tags.Items.MUSHROOMS) || sapling.is(fungi);
        stumpLocations = new ArrayList<>();
        if (sapling.is(Items.MANGROVE_PROPAGULE))
        {
            stumpLocations.add(mangroveStump(world));
        }
        else
        {
            fillTreeStumps(location.getY());
        }
        variant = BlockStateUtils.getPropertyByNameFromState(world.getBlockState(location), "variant");
    }

    /**
     * Forestry: a propagule goes on the ground under the lowest trunk log, not into the average of the root tips (often inside a muddy root, where it
     * cannot stand).
     */
    private BlockPos mangroveStump(@NotNull final Level world)
    {
        BlockPos trunk = null;
        for (final BlockPos pos : allLogs)
        {
            if (!world.getBlockState(pos).is(ModTags.treeRoots) && (trunk == null || pos.getY() < trunk.getY()))
            {
                trunk = pos;
            }
        }
        if (trunk == null)
        {
            return location;
        }
        BlockPos pos = trunk;
        for (int i = 0; i < 16 && pos.getY() > world.getMinY(); i++)
        {
            final BlockState below = world.getBlockState(pos.below());
            if (!below.isAir() && below.getFluidState().isEmpty() && !TreeFootprint.isTreeBlock(below))
            {
                return pos;
            }
            pos = pos.below();
        }
        return trunk;
    }

    /**
     * Forestry: the leaves this tree holds, for breaking them first ({@code defoliate}, nether trees): leaves whose {@code DISTANCE} equals their step
     * count from this tree's logs, and the caps (mushroom, wart, shroomlight) connected to them.
     */
    private LinkedList<BlockPos> collectOwnLeaves(@NotNull final Level world)
    {
        final LinkedList<BlockPos> own = new LinkedList<>();
        final java.util.Set<BlockPos> seen = new java.util.HashSet<>(allLogs);
        java.util.List<BlockPos> layer = new ArrayList<>(allLogs);
        for (int depth = 1; depth < LeavesBlock.DECAY_DISTANCE && !layer.isEmpty() && own.size() < TreeFootprint.LEAF_BUDGET; depth++)
        {
            final java.util.List<BlockPos> next = new ArrayList<>();
            for (final BlockPos pos : layer)
            {
                for (final net.minecraft.core.Direction dir : net.minecraft.core.Direction.values())
                {
                    final BlockPos near = pos.relative(dir);
                    if (!seen.add(near))
                    {
                        continue;
                    }
                    final BlockState state = world.getBlockState(near);
                    if (TreeFootprint.isDecayingLeaves(state) && !state.getValue(LeavesBlock.PERSISTENT) && state.getValue(LeavesBlock.DISTANCE) == depth)
                    {
                        own.add(near);
                        next.add(near);
                    }
                    else if (TreeFootprint.isCap(state))
                    {
                        own.add(near);
                        next.add(near);
                    }
                }
            }
            layer = next;
        }
        // a cap joined to a player's decoration is no cap of this tree (r2 Sol H1): the whole connected cap stays
        final net.minecraft.world.phys.AABB logBox = boxMin == null || boxMax == null ? null : net.minecraft.world.phys.AABB.encapsulatingFullBlocks(boxMin, boxMax);
        final java.util.Map<BlockPos, Boolean> capOwned = new java.util.HashMap<>();
        own.removeIf(pos -> TreeFootprint.isCap(world.getBlockState(pos))
                              && !capOwned.computeIfAbsent(pos, p -> TreeFootprint.capComponentIsOwn(world, p, logBox, family, strippedBase)));
        // farthest first: pollLast takes from the end
        own.sort((c1, c2) -> (int) (c1.distSqr(location) - c2.distSqr(location)));
        return own;
    }

    /**
     * Checks the leaf above the highest Log for drops
     *
     * @param world world the tree is in
     * @return ItemStack of the sapling found
     */
    private ItemStack calcSapling(final Level world)
    {
        if (topLog == null)
        {
            return ItemStack.EMPTY;
        }

        ItemStack sapling;

        // Try leaf directly above the tree base first
        final BlockPos firstLeaf = getFirstLeaf(world);
        sapling = calcSaplingForPos(world, firstLeaf, true);
        if (sapling != null)
        {
            return sapling;
        }

        // Try all leaves found related to the tree
        for (final BlockPos pos : leaves)
        {
            sapling = calcSaplingForPos(world, pos, true);

            if (sapling != null)
            {
                return sapling;
            }
        }

        // Get sapling from Leaf above the treebase without checking compability
        sapling = calcSaplingForPos(world, firstLeaf, false);
        if (sapling != null)
        {
            return sapling;
        }

        return ItemStackUtils.EMPTY;
    }

    /**
     * Calculates a sapling from the leaf at the given position
     *
     * @param world         world to use for accessing blocks
     * @param pos           Blockposition of the leaf
     * @param checkFitsBase boolean whether we should check leaf and tree's log compatibility
     * @return the sapling to plant at the given position
     */
    private ItemStack calcSaplingForPos(final Level world, final BlockPos pos, final boolean checkFitsBase)
    {
        BlockState blockState = world.getBlockState(pos);
        final Block block = blockState.getBlock();

        if (blockState.is(BlockTags.LEAVES) || Compatibility.isDynamicLeaf(block) || blockState.is(ModTags.hugeMushroomBlocks))
        {
            NonNullList<ItemStack> list = NonNullList.create();

            if (checkFitsBase)
            {
                // Check if the tree's base log variant fits the leaf
                if (Compatibility.isDynamicLeaf(block))
                {
                    if (!isDynamicTree() || !Compatibility.isDynamicFamilyFitting(pos, location, world))
                    {
                        return null;
                    }
                }
            }

            // Dynamic trees is using a custom Drops function
            if (Compatibility.isDynamicLeaf(block))
            {
                list = Compatibility.getDropsForDynamicLeaf(world, pos, blockState, A_LOT_OF_LUCK, block);
            }
            else
            {
                list.addAll(getSaplingsForLeaf((ServerLevel) world, pos));
            }

            for (final ItemStack stack : list)
            {
                // Skip bad stacks from drops calc
                if (stack.isEmpty())
                {
                    continue;
                }

                if (stack.is(ItemTags.SAPLINGS) || stack.is(Tags.Items.MUSHROOMS))
                {
                    IColonyManager.getInstance().getCompatibilityManager().connectLeafToSapling(block, stack);
                    return stack;
                }
            }
        }
        else if (blockState.is(BlockTags.WART_BLOCKS))
        {
            return IColonyManager.getInstance().getCompatibilityManager().getSaplingForLeaf(block);
        }
        return null;
    }

    /**
     * Fills the list of drops for a leaf.
     *
     * @param world    world reference
     * @param position position of the leaf
     * @return the list of saplings.
     */
    public static List<ItemStack> getSaplingsForLeaf(ServerLevel world, BlockPos position)
    {
        NonNullList<ItemStack> list = NonNullList.create();
        BlockState state = world.getBlockState(position);

        if (state.is(Blocks.MANGROVE_LEAVES))
        {
            list.add(new ItemStack(Items.MANGROVE_PROPAGULE));
            return list;
        }

        for (int i = 1; i < 100; i++)
        {
            list.addAll(state.getDrops(new LootParams.Builder(world)
                                         .withParameter(LootContextParams.TOOL,
                                           new ItemStack(Items.WOODEN_AXE)).withLuck(100)
                                         .withParameter(LootContextParams.ORIGIN, new Vec3(position.getX(), position.getY(), position.getZ()))));
            if (!list.isEmpty())
            {
                for (ItemStack stack : list)
                {
                    if (stack.is(ItemTags.SAPLINGS) || stack.is(Tags.Items.MUSHROOMS))
                    {
                        return list;
                    }
                }
            }
        }
        return list;
    }

    /**
     * Returns the first Leaf above the toplog
     *
     * @param world the world to search in
     * @return leaf pos found
     */
    private BlockPos getFirstLeaf(final LevelAccessor world)
    {
        // Find the closest leaf above, stay below max height
        for (int i = 1; (i + topLog.getY()) < world.getMaxY() && i < 10; i++)
        {
            final BlockState blockState = world.getBlockState(topLog.offset(0, i, 0));
            if (blockState.is(BlockTags.LEAVES) || blockState.is(ModTags.hugeMushroomBlocks))
            {
                return topLog.offset(0, i, 0);
            }
        }
        return topLog.offset(0, 1, 0);
    }

    /**
     * For use in PathJobFindTree.
     *
     * @param world         the world.
     * @param pos           The coordinates.
     * @param treesToNotCut the trees the lumberjack is not supposed to cut.
     * @param dyntreesize   the radius a dynamic tree must have in order to get cut down.
     * @return true if the log is part of a tree.
     */
    public static boolean checkTree(@NotNull final LevelReader world, final BlockPos pos, final List<ItemStorage> treesToNotCut, final int dyntreesize)
    {
        return checkTree(world, pos, treesToNotCut, dyntreesize, null, 0, 0);
    }

    /**
     * For use in PathJobFindTree, with the tree claims: a tree another Forester works on, or one blocked as next to a build, is no tree for this one.
     *
     * @param world         the world.
     * @param pos           The coordinates.
     * @param treesToNotCut the trees the lumberjack is not supposed to cut.
     * @param dyntreesize   the radius a dynamic tree must have in order to get cut down.
     * @param claimLevel    the level of the claims, or null to ignore claims.
     * @param colonyId      the Forester's colony.
     * @param citizenId     the Forester's citizen id.
     * @return true if the log is part of a tree.
     */
    public static boolean checkTree(
      @NotNull final LevelReader world,
      final BlockPos pos,
      final List<ItemStorage> treesToNotCut,
      final int dyntreesize,
      @Nullable final Level claimLevel,
      final int colonyId,
      final int citizenId)
    {
        //Is the first block a log?
        final BlockState state = world.getBlockState(pos);
        final Block block = state.getBlock();
        if (!Compatibility.isSlimeBlock(block) && !Compatibility.isDynamicBlock(block))
        {
            return TreeFootprint.check(world, pos, claimLevel, colonyId, citizenId, leafPos -> supposedToCut(world, treesToNotCut, leafPos));
        }
        if (!state.is(ModTags.tree) && !Compatibility.isSlimeBlock(block) && !Compatibility.isDynamicBlock(block))
        {
            return false;
        }

        // Only harvest nearly fully grown dynamic trees(8 max)
        if (Compatibility.isDynamicBlock(block)
              && BlockStateUtils.getPropertyByNameFromState(state, DYNAMICTREERADIUS) != null
              && ((Integer) state.getValue(BlockStateUtils.getPropertyByNameFromState(state, DYNAMICTREERADIUS)) < dyntreesize))
        {
            return false;
        }

        final Tuple<BlockPos, BlockPos> baseAndTOp = getBottomAndTopLog(world, pos, new LinkedList<>(), null, null);

        //Get base log, should already be base log.
        final BlockPos basePos = baseAndTOp.getA();

        //Make sure tree is on solid ground and tree is not build above cobblestone.
        return BlockUtils.isAnySolid(world.getBlockState(basePos.below()))
                 && world.getBlockState(basePos.below()).getBlock() != Blocks.COBBLESTONE
                 && hasEnoughLeavesAndIsSupposedToCut(world, baseAndTOp.getB(), treesToNotCut);
    }

    /**
     * Adds a log and searches for further logs(Breadth first search).
     *
     * @param world        The world the log is in.
     * @param log          The log to add.
     * @param woodenBlocks The wooden blocks.
     * @param bottomLog    The bottom most log.
     * @param topLog       The top most log.
     * @return a tuple containing, first: bottom log and second: top log.
     */
    @NotNull
    private static Tuple<BlockPos, BlockPos> getBottomAndTopLog(
      @NotNull final LevelReader world,
      @NotNull final BlockPos log,
      @NotNull final LinkedList<BlockPos> woodenBlocks,
      final BlockPos bottomLog,
      final BlockPos topLog)
    {
        BlockPos bottom = bottomLog == null ? log : bottomLog;
        BlockPos top = topLog == null ? log : topLog;

        if (woodenBlocks.size() >= MineColonies.getConfig().getServer().maxTreeSize.get())
        {
            return new Tuple<>(bottom, top);
        }

        if (log.getY() < bottom.getY())
        {
            bottom = log;
        }

        if (log.getY() > top.getY())
        {
            top = log;
        }

        woodenBlocks.add(log);
        for (int y = -1; y <= 1; y++)
        {
            for (int x = -1; x <= 1; x++)
            {
                for (int z = -1; z <= 1; z++)
                {
                    final BlockPos temp = log.offset(x, y, z);
                    final BlockState block = world.getBlockState(temp);
                    if ((block.is(ModTags.tree) || Compatibility.isSlimeBlock(block.getBlock()) || Compatibility.isDynamicBlock(block.getBlock())) && !woodenBlocks.contains(temp))
                    {
                        return getBottomAndTopLog(world, temp, woodenBlocks, bottom, top);
                    }
                }
            }
        }

        return new Tuple<>(bottom, top);
    }

    /**
     * Check if the tree has enough leaves and the lj is supposed to cut them.
     *
     * @param world         the world it is in.
     * @param pos           the position.
     * @param treesToNotCut the trees the lj is not supposed to cut.
     * @return true if so.
     */
    private static boolean hasEnoughLeavesAndIsSupposedToCut(@NotNull final LevelReader world, final BlockPos pos, final List<ItemStorage> treesToNotCut)
    {
        boolean checkedLeaves = false;
        int leafCount = 0;
        int dynamicBonusY = 0;
        final BlockState blockState = world.getBlockState(pos);
        // Additional leaf search range for dynamic trees, as we start from the baselog
        if (blockState.is(ModTags.mangroveTree) || Compatibility.isDynamicBlock(blockState.getBlock()))
        {
            dynamicBonusY = 8;
        }

        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dz = -1; dz <= 1; dz++)
            {
                for (int dy = -3; dy <= 3 + dynamicBonusY; dy++)
                {
                    final BlockPos leafPos = pos.offset(dx, dy, dz);
                    final BlockState block = world.getBlockState(leafPos);
                    if (block.is(BlockTags.LEAVES) || block.is(ModTags.hugeMushroomBlocks) || block.is(BlockTags.WART_BLOCKS))
                    {
                        if (!checkedLeaves && !supposedToCut(world, treesToNotCut, leafPos))
                        {
                            return false;
                        }
                        checkedLeaves = true;

                        leafCount++;
                        // Dynamic tree growth is checked by radius instead of leafcount
                        if (leafCount >= NUMBER_OF_LEAVES || (Compatibility.isDynamicLeaf(block.getBlock())))
                        {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Check if the Lj is supposed to cut a tree.
     *
     * @param world         the world it is in.
     * @param treesToNotCut the trees he is not supposed to cut.
     * @param leafPos       the position a leaf is at.
     * @return false if not.
     */
    private static boolean supposedToCut(final LevelReader world, final List<ItemStorage> treesToNotCut, final BlockPos leafPos)
    {
        final BlockState leaf = world.getBlockState(leafPos);
        if (leaf.getOptionalValue(LeavesBlock.PERSISTENT).orElse(false))
        {
            return false;
        }

        // sadly this is called from pathfinding so can't directly access server loot; this means if the sapling
        // isn't already cached (which it won't be the very first time we encounter a new tree type for this colony)
        // then we will try to chop it regardless of hut settings.  but the *second* tree of that type we should
        // obey the settings properly.
        final ItemStack sap = IColonyManager.getInstance().getCompatibilityManager().getSaplingForLeaf(leaf.getBlock());

        if (sap == null)
        {
            return true;
        }

        for (final ItemStorage stack : treesToNotCut)
        {
            if (ItemStackUtils.compareItemStacksIgnoreStackSize(sap, stack.getItemStack()))
            {
                return false;
            }
        }

        return true;
    }

    /**
     * Reads the tree object from NBT.
     *
     * @param compound the compound of the tree.
     * @return a new tree object.
     */
    @NotNull
    public static Tree read(@NotNull final HolderLookup.Provider provider, @NotNull final CompoundTag compound)
    {
        @NotNull final Tree tree = new Tree();
        tree.location = BlockPosUtil.read(compound, TAG_LOCATION);

        tree.woodBlocks = new LinkedList<>();
        final ListTag logs = compound.getListOrEmpty(TAG_LOGS);
        for (int i = 0; i < logs.size(); i++)
        {
            tree.woodBlocks.add(BlockPosUtil.readFromListNBT(logs, i));
        }

        tree.stumpLocations = new ArrayList<>();
        final ListTag stumps = compound.getListOrEmpty(TAG_STUMPS);
        for (int i = 0; i < stumps.size(); i++)
        {
            tree.stumpLocations.add(BlockPosUtil.readFromListNBT(stumps, i));
        }

        tree.topLog = BlockPosUtil.read(compound, TAG_TOP_LOG);

        tree.slimeTree = compound.getBooleanOr(TAG_IS_SLIME_TREE, false);
        tree.dynamicTree = compound.getBooleanOr(TAG_DYNAMIC_TREE, false);

        if (compound.contains(TAG_SAPLING))
        {
            tree.sapling = ItemStackUtils.parseOptional(provider, compound.getCompoundOrEmpty(TAG_SAPLING));
        }
        else
        {
            tree.isTree = false;
        }

        if (compound.contains(TAG_NETHER_TREE))
        {
            tree.netherTree = compound.getBooleanOr(TAG_NETHER_TREE, false);
        }
        else
        {
            tree.netherTree = false;
        }

        if (compound.contains(TAG_LEAVES))
        {
            final ListTag leavesBin = compound.getListOrEmpty(TAG_LEAVES);
            for (int i = 0; i < leavesBin.size(); i++)
            {
                tree.leaves.add(BlockPosUtil.readFromListNBT(leavesBin, i));
            }
        }

        final ListTag decorationsBin = compound.getListOrEmpty(TAG_DECORATIONS);
        for (int i = 0; i < decorationsBin.size(); i++)
        {
            tree.decorations.add(BlockPosUtil.readFromListNBT(decorationsBin, i));
        }
        if (compound.contains(TAG_BOX_MIN) && compound.contains(TAG_BOX_MAX))
        {
            tree.boxMin = BlockPosUtil.read(compound, TAG_BOX_MIN);
            tree.boxMax = BlockPosUtil.read(compound, TAG_BOX_MAX);
        }
        tree.family = compound.getStringOr(TAG_FAMILY, TreeFootprint.ANY_FAMILY);
        tree.strippedBase = compound.getBooleanOr(TAG_STRIPPED_BASE, false);
        tree.started = compound.getBooleanOr(TAG_STARTED, false);
        final ListTag allLogsBin = compound.getListOrEmpty(TAG_ALL_LOGS);
        for (int i = 0; i < allLogsBin.size(); i++)
        {
            tree.allLogs.add(BlockPosUtil.readFromListNBT(allLogsBin, i));
        }

        return tree;
    }

    /**
     * Checks if the found log is part of a tree.
     *
     * @param world  The world the tree is in.
     * @param topLog The most upper log of the tree.
     */
    private void checkTree(@NotNull final Level world, @NotNull final BlockPos topLog)
    {
        if (!BlockUtils.isAnySolid(world.getBlockState(new BlockPos(location.getX(), location.getY() - 1, location.getZ()))))
        {
            return;
        }
        int leafCount = 0;
        for (int x = -1; x <= 1; x++)
        {
            for (int z = -1; z <= 1; z++)
            {
                for (int y = -1; y <= 1; y++)
                {
                    final BlockPos leaf = new BlockPos(topLog.getX() + x, topLog.getY() + y, topLog.getZ() + z);
                    final BlockState leaves = world.getBlockState(leaf);
                    if (leaves.is(BlockTags.LEAVES) || leaves.is(ModTags.hugeMushroomBlocks))
                    {
                        if (leaves.getOptionalValue(LeavesBlock.PERSISTENT).orElse(false))
                        {
                            continue;
                        }
                        leafCount++;
                        if (leafCount >= NUMBER_OF_LEAVES)
                        {
                            isTree = true;
                            return;
                        }
                    }
                }
            }
        }
    }

    /**
     * Searches all logs that belong to the tree.
     *
     * @param world  The world where the blocks are in.
     * @param colony the colony to search for buildings, or null if we don't care.
     */
    public void findLogs(@NotNull final Level world, @Nullable final IColony colony)
    {
        if (footprint != null)
        {
            // The scan of the constructor found the logs and stumps already.
            return;
        }
        addAndSearch(world, location, colony);
        woodBlocks.sort((c1, c2) -> (int) (c1.distSqr(location) - c2.distSqr(location)));
        if (getStumpLocations().isEmpty())
        {
            fillTreeStumps(location.getY());
        }
    }

    /**
     * Checks if the tree has been planted from more than 1 saplings. Meaning that more than 1 log is on the lowest level.
     *
     * @param yLevel The base y.
     */
    public void fillTreeStumps(final int yLevel)
    {
        for (@NotNull final BlockPos pos : woodBlocks)
        {
            if (pos.getY() == yLevel)
            {
                stumpLocations.add(pos);
            }
        }

        // todo: for the sake of generic-ness this could check for adjacency rather than just special-casing,
        //       though that's harder if someone decides to make a tree bigger than 2x2.
        if (footprint != null && sapling.is(Items.MANGROVE_PROPAGULE))
        {
            // Set by the scan (initFromFootprint): the ground under the trunk.
            return;
        }
        if (stumpLocations.size() > 1 && sapling.is(Items.MANGROVE_PROPAGULE))
        {
            BlockPos.MutableBlockPos acc = BlockPos.ZERO.mutable();
            for (final BlockPos stump : stumpLocations)
            {
                acc = acc.move(stump);
            }

            final BlockPos mean = new BlockPos(acc.getX() / stumpLocations.size(),
              acc.getY() / stumpLocations.size(), acc.getZ() / stumpLocations.size());
            stumpLocations.clear();
            stumpLocations.add(mean);
        }
    }

    /**
     * Adds a log and searches for further logs(Breadth first search).
     *
     * @param world  The world the log is in.
     * @param log    the log to add.
     * @param colony the colony to search for buildings, or null if we don't care.
     */
    private void addAndSearch(@NotNull final Level world, @NotNull final BlockPos log, @Nullable final IColony colony)
    {
        if (woodBlocks.size() >= MineColonies.getConfig().getServer().maxTreeSize.get())
        {
            return;
        }

        if (woodBlocks.contains(log))
        {
            return;
        }

        // Check if the new log fits the Tree's base log type
        if (!isBlockPartOfSameTree(world.getBlockState(log), world.getBlockState(location)))
        {
            return;
        }

        if (log.getY() < location.getY())
        {
            location = log;
        }

        if (log.getY() > topLog.getY())
        {
            topLog = log;
        }

        if (colony != null)
        {
            for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
            {
                if (building.isInBuilding(log))
                {
                    return;
                }
            }
        }

        woodBlocks.add(log);

        // Only add the base to a dynamic tree
        if (Compatibility.isDynamicBlock(BlockPosUtil.getBlock(world, log)))
        {
            return;
        }


        for (int y = -1; y <= 1; y++)
        {
            for (int x = -1; x <= 1; x++)
            {
                for (int z = -1; z <= 1; z++)
                {
                    final BlockPos temp = log.offset(x, y, z);
                    final BlockState block = BlockPosUtil.getBlockState(world, temp);
                    if ((block.is(ModTags.tree) || Compatibility.isSlimeBlock(block.getBlock())))
                    {
                        addAndSearch(world, temp, colony);
                    }
                }
            }
        }
    }

    /**
     * Get the prefix of a log block, which is the path of the block in the
     * registries, with "_log" or "_wood" removed from the end.
     *
     * @param block the block to get the prefix from.
     * @return the prefix of the log block.
     */
    private String logPrefix(BlockState block) 
    {
        String path = BuiltInRegistries.BLOCK.getKey(block.getBlock()).getPath();
        return path.replaceFirst("(_log|_wood|_stem|_hyphae)$", "");
    }

    /**
     * Check if this is a log in the same tree type.
     *
     * @param checkBlock the current block in the tree being evaluated.
     * @param stumpBlock the block to check against.
     * @return true if this is the same type of tree; false if it's something different.
     */
    private boolean isBlockPartOfSameTree(
      @NotNull final BlockState checkBlock,
      @NotNull final BlockState stumpBlock)
    {
        if (checkBlock.is(ModTags.mangroveTree))
        {
            return stumpBlock.is(ModTags.mangroveTree);
        }

        return (checkBlock.getBlock() == stumpBlock.getBlock()) || checkBlock.is(ModTags.extraTree) || (logPrefix(checkBlock).equals(logPrefix(stumpBlock)));
	}

    /**
     * Adds a leaf and searches for further leaves.
     *
     * @param world The world the leaf is in.
     */
    private void addAndSearch(@NotNull final Level world)
    {
        int locXMin = location.getX() - LEAVES_WIDTH;
        int locXMax = location.getX() + LEAVES_WIDTH;
        final int locYMin = location.getY() + 1;
        int locZMin = location.getZ() - LEAVES_WIDTH;
        int locZMax = location.getZ() + LEAVES_WIDTH;
        int temp;
        if (locXMin > locXMax)
        {
            temp = locXMax;
            locXMax = locXMin;
            locXMin = temp;
        }
        if (locZMin > locZMax)
        {
            temp = locZMax;
            locZMax = locZMin;
            locZMin = temp;
        }
        for (int locX = locXMin; locX <= locXMax; locX++)
        {
            for (int locY = locYMin; locY < world.getHeight(); locY++)
            {
                for (int locZ = locZMin; locZ <= locZMax; locZ++)
                {
                    final BlockPos leaf = new BlockPos(locX, locY, locZ);
                    final BlockState block = world.getBlockState(leaf);
                    if (block.is(BlockTags.LEAVES) || block.is(ModTags.hugeMushroomBlocks) ||
                            block.is(BlockTags.WART_BLOCKS) || block.is(Blocks.SHROOMLIGHT))
                    {
                        if (!block.getOptionalValue(LeavesBlock.PERSISTENT).orElse(false))
                        {
                            leaves.add(leaf);
                        }
                    }
                }
            }
        }
    }

    /**
     * Forestry: NBT keys of the decorations and the box.
     */
    private static final String TAG_DECORATIONS = "forestryDecorations";
    private static final String TAG_BOX_MIN     = "forestryBoxMin";
    private static final String TAG_BOX_MAX     = "forestryBoxMax";
    private static final String TAG_FAMILY        = "forestryFamily";
    private static final String TAG_STRIPPED_BASE = "forestryStrippedBase";
    private static final String TAG_STARTED       = "forestryStarted";
    private static final String TAG_ALL_LOGS      = "forestryAllLogs";

    /**
     * @return true once a block of the tree was broken.
     */
    public boolean isStarted()
    {
        return started;
    }

    /**
     * Forestry: a block of the tree was broken.
     */
    public void markStarted()
    {
        started = true;
    }

    /**
     * @return the wood family of the tree ({@link TreeFootprint#ANY_FAMILY} for a tree saved before it was kept).
     */
    @NotNull
    public String getFamily()
    {
        return family;
    }

    /**
     * @return true if the trunk itself is stripped.
     */
    public boolean isStrippedBase()
    {
        return strippedBase;
    }

    /**
     * @param pos a position.
     * @return true if the block there is one of this tree's logs, leaves or decorations as he saw them.
     */
    public boolean ownsBlock(@NotNull final BlockPos pos)
    {
        return allLogs.contains(pos) || woodBlocks.contains(pos) || leaves.contains(pos) || decorations.contains(pos);
    }

    /**
     * @param pos a cap (mushroom cap, wart block, shroomlight).
     * @return true if it is a cap of this mushroom or fungus: one he saw, or one within {@link TreeFootprint#CAP_REACH} of its stems (the side of a huge red
     * mushroom's cap meets its top only at the edges, so it is not among the caps he saw).
     */
    public boolean ownsCap(@NotNull final BlockPos pos)
    {
        if (ownsBlock(pos))
        {
            return true;
        }
        if (!netherTree)
        {
            return false;
        }
        for (final BlockPos log : allLogs)
        {
            if (Math.abs(log.getX() - pos.getX()) <= TreeFootprint.CAP_REACH && Math.abs(log.getY() - pos.getY()) <= TreeFootprint.CAP_REACH
                  && Math.abs(log.getZ() - pos.getZ()) <= TreeFootprint.CAP_REACH)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true if a block hangs on the tree that goes with it (bee nest, cocoa).
     */
    public boolean hasDecorations()
    {
        return !decorations.isEmpty();
    }

    /**
     * @return the next decoration to take.
     */
    public BlockPos peekNextDecoration()
    {
        return decorations.peekLast();
    }

    /**
     * @return the next decoration, removed from the list.
     */
    public BlockPos pollNextDecoration()
    {
        return decorations.pollLast();
    }

    /**
     * @return why the tree must not be cut ({@link TreeFootprint.Skip#NONE} for a tree read from NBT or from Dynamic Trees).
     */
    @NotNull
    public TreeFootprint.Skip getSkip()
    {
        return footprint == null ? TreeFootprint.Skip.NONE : footprint.skip;
    }

    /**
     * @return what made the scan skip the tree, for logs and tests.
     */
    @NotNull
    public String getSkipDetail()
    {
        return footprint == null ? "" : footprint.skipDetail;
    }

    /**
     * @return every log of the tree as first seen (empty for a tree saved before forestry kept them).
     */
    @NotNull
    public List<BlockPos> getAllLogs()
    {
        return allLogs;
    }

    /**
     * @return the box of the tree's logs, or null for a tree from before forestry.
     */
    @Nullable
    public net.minecraft.world.phys.AABB getBox()
    {
        return boxMin == null || boxMax == null ? null : net.minecraft.world.phys.AABB.encapsulatingFullBlocks(boxMin, boxMax);
    }

    /**
     * @return the logs not yet cut.
     */
    public int getLogsLeft()
    {
        return woodBlocks.size();
    }

    /**
     * Forestry: after a TreeChop felling, the logs TreeChop does not know (roots, a creaking heart, a log beyond its tree) are left to the normal path.
     *
     * @param world the world.
     */
    public void keepOnlyStandingLogs(@NotNull final Level world)
    {
        final String fam = footprint == null ? null : footprint.family;
        woodBlocks.removeIf(pos -> {
            final BlockState state = world.getBlockState(pos);
            return !TreeFootprint.isTreeBlock(state) || (fam != null && !TreeFootprint.sameFamily(fam, TreeFootprint.family(world, pos, state, true)));
        });
    }

    /**
     * @return true if the tree was scanned with the footprint (not Dynamic Trees, not a slime tree).
     */
    public boolean isFootprintTree()
    {
        return footprint != null || boxMin != null;
    }

    /**
     * Returns the next log block.
     *
     * @return the position.
     */
    public BlockPos pollNextLog()
    {
        return woodBlocks.pollLast();
    }

    /**
     * Gets the trees sapling
     *
     * @return the sapling stack.
     */
    public ItemStack getSapling()
    {
        return sapling;
    }

    /**
     * Returns the next leaf block.
     *
     * @return the position.
     */
    public BlockPos pollNextLeaf()
    {
        return leaves.pollLast();
    }

    /**
     * Looks up the next log block.
     *
     * @return the position.
     */
    public BlockPos peekNextLog()
    {
        return woodBlocks.peekLast();
    }

    /**
     * Looks up the next leaf block.
     *
     * @return the position.
     */
    public BlockPos peekNextLeaf()
    {
        return leaves.peekLast();
    }

    /**
     * Check if the found tree has any leaves.
     *
     * @return true if there are leaves associated with the tree.
     */
    public boolean hasLeaves()
    {
        return !leaves.isEmpty();
    }

    /**
     * Check if the found tree has any logs.
     *
     * @return true if there are wood blocks associated with the tree.
     */
    public boolean hasLogs()
    {
        return !woodBlocks.isEmpty();
    }

    /**
     * @return if tree is slime tree.
     */
    public boolean isSlimeTree()
    {
        return slimeTree;
    }

    /**
     * @return if tree is dynamic tree
     */
    public boolean isDynamicTree()
    {
        return dynamicTree;
    }

    /**
     * @return if tree is nether tree
     */
    public boolean isNetherTree()
    {
        return netherTree;
    }

    /**
     * All stump positions of a tree (A tree may have been planted with different saplings).
     *
     * @return an Arraylist of the positions.
     */
    @NotNull
    public List<BlockPos> getStumpLocations()
    {
        return new ArrayList<>(stumpLocations);
    }

    /**
     * Removes a stump from the stump list.
     *
     * @param pos the position of the stump.
     */
    public void removeStump(final BlockPos pos)
    {
        stumpLocations.remove(pos);
    }

    /**
     * Get's the variant of a tree. A tree may only have 1 variant.
     *
     * @return the EnumType variant.
     */
    public Property<?> getVariant()
    {
        return variant;
    }

    /**
     * Returns the trees location.
     *
     * @return the position.
     */
    public BlockPos getLocation()
    {
        return location;
    }

    /**
     * Needed for the equals method.
     *
     * @return the hash code of the location.
     */
    @Override
    public int hashCode()
    {
        return location.hashCode();
    }

    /**
     * Overridden equals method checks if the location of the both trees are equal.
     *
     * @param tree the object to compare.
     * @return true if equal or false if not.
     */
    @Override
    public boolean equals(@Nullable final Object tree)
    {
        return tree != null && tree.getClass() == this.getClass() && ((Tree) tree).getLocation().equals(location);
    }

    /**
     * Writes the tree Object to NBT.
     *
     * @param compound the compound of the tree.
     */
    public void write(@NotNull final HolderLookup.Provider provider, @NotNull final CompoundTag compound)
    {
        if (!isTree)
        {
            return;
        }

        BlockPosUtil.write(compound, TAG_LOCATION, location);

        @NotNull final ListTag logs = new ListTag();
        for (@NotNull final BlockPos log : woodBlocks)
        {
            BlockPosUtil.writeToListNBT(logs, log);
        }
        compound.put(TAG_LOGS, logs);

        @NotNull final ListTag stumps = new ListTag();
        for (@NotNull final BlockPos stump : stumpLocations)
        {
            BlockPosUtil.writeToListNBT(stumps, stump);
        }
        compound.put(TAG_STUMPS, stumps);

        BlockPosUtil.write(compound, TAG_TOP_LOG, topLog);

        compound.putBoolean(TAG_IS_SLIME_TREE, slimeTree);
        compound.putBoolean(TAG_DYNAMIC_TREE, dynamicTree);

        compound.put(TAG_SAPLING, ItemStackUtils.serializeOptional(sapling, provider));
        compound.putBoolean(TAG_NETHER_TREE, netherTree);

        @NotNull final ListTag leavesBin = new ListTag();
        for (@NotNull final BlockPos pos : leaves)
        {
            BlockPosUtil.writeToListNBT(leavesBin, pos);
        }
        compound.put(TAG_LEAVES, leavesBin);

        @NotNull final ListTag decorationsBin = new ListTag();
        for (@NotNull final BlockPos pos : decorations)
        {
            BlockPosUtil.writeToListNBT(decorationsBin, pos);
        }
        compound.put(TAG_DECORATIONS, decorationsBin);
        if (boxMin != null && boxMax != null)
        {
            BlockPosUtil.write(compound, TAG_BOX_MIN, boxMin);
            BlockPosUtil.write(compound, TAG_BOX_MAX, boxMax);
        }
        compound.putString(TAG_FAMILY, family);
        compound.putBoolean(TAG_STRIPPED_BASE, strippedBase);
        compound.putBoolean(TAG_STARTED, started);
        @NotNull final ListTag allLogsBin = new ListTag();
        for (@NotNull final BlockPos pos : allLogs)
        {
            BlockPosUtil.writeToListNBT(allLogsBin, pos);
        }
        compound.put(TAG_ALL_LOGS, allLogsBin);
    }

    /**
     * Returns whether the Tree object actually is a tree.
     *
     * @return isTree
     */
    public boolean isTree()
    {
        return isTree;
    }

    /**
     * Calculates with a colony if the position is inside the colony and optionally if it is inside a building.
     * Accessed off-thread by pathfinding
     *
     * @param pos                 the position.
     * @param colony              the colony.
     * @param world               the world to use
     * @param allowInsideBuilding if false, also checks that the tree is not inside a building.
     * @return return false if not inside the colony or optionally if inside a building.
     */
    public static boolean checkIfInColony(final BlockPos pos, final IColony colony, final LevelReader world, final boolean allowInsideBuilding)
    {
        if (!colony.getLoadedChunks().contains(ChunkPos.pack(pos)))
        {
            return false;
        }

        // Dynamic trees are never part of buildings
        if (allowInsideBuilding || Compatibility.isDynamicBlock(world.getBlockState(pos).getBlock()))
        {
            return true;
        }

        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            if (building.isInBuilding(pos))
            {
                return false;
            }
        }
        return true;
    }
}
