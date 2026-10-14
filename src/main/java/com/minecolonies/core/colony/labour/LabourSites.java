package com.minecolonies.core.colony.labour;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.labour.LabourExclusions;
import com.minecolonies.api.colony.labour.LabourModes;
import com.minecolonies.core.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Where a colony's labourers may work: the natural trees, the surface blocks, the loose items, the crafting tables and furnaces and the warehouses, always inside
 * THIS colony's claim (a table or tree inside another colony's borders does not exist for it). Scans read the loaded chunks of the claim around the town hall,
 * are cached for {@link #CACHE_TICKS} ticks and run only when a plan asks. Server thread only.
 */
public final class LabourSites
{
    /**
     * How long a scan is reused.
     */
    public static final int CACHE_TICKS = 600;

    /**
     * The biggest tree (logs) a labourer fells.
     */
    public static final int MAX_TREE_LOGS = 64;

    /**
     * Leaf blocks (not placed by a player) a log set needs to be a tree.
     */
    public static final int MIN_LEAVES = 4;

    /**
     * A natural tree.
     *
     * @param base the lowest log.
     * @param logs its logs, lowest first.
     * @param log  the kind of log (what the base block gives).
     */
    public record TreeSite(BlockPos base, List<BlockPos> logs, Item log)
    {
    }

    private final Colony colony;

    private List<TreeSite> trees   = List.of();
    private long           treesAt = Long.MIN_VALUE / 2;

    private final Map<Item, List<BlockPos>> surface = new HashMap<>();
    private long                            surfaceAt = Long.MIN_VALUE / 2;

    private List<BlockPos> tables      = List.of();
    private List<BlockPos> furnaces    = List.of();
    private long           postsAt     = Long.MIN_VALUE / 2;

    private List<BoundingBox> boxes   = List.of();
    private long              boxesAt = Long.MIN_VALUE / 2;

    public LabourSites(final Colony colony)
    {
        this.colony = colony;
    }

    /**
     * Forgets the scans (a tree was felled, a table placed, a test changed the world).
     */
    public void invalidate()
    {
        treesAt = Long.MIN_VALUE / 2;
        surfaceAt = Long.MIN_VALUE / 2;
        postsAt = Long.MIN_VALUE / 2;
        boxesAt = Long.MIN_VALUE / 2;
    }

    @Nullable
    private ServerLevel level()
    {
        return colony.getWorld() instanceof ServerLevel serverLevel ? serverLevel : null;
    }

    private long now()
    {
        return colony.getWorld() == null ? 0L : colony.getWorld().getGameTime();
    }

    /**
     * @return the point the labour range is measured from: the town hall.
     */
    public BlockPos anchor()
    {
        return colony.getCenter();
    }

    /**
     * @param pos a position.
     * @return whether it lies inside this colony's claim (and in a loaded chunk).
     */
    public boolean ownedBy(final BlockPos pos)
    {
        final ServerLevel level = level();
        if (level == null || !level.isLoaded(pos))
        {
            return false;
        }
        final IColony owner = IColonyManager.getInstance().getColonyByPosFromWorld(level, pos);
        return owner != null && owner.getID() == colony.getID() && owner.getDimension().equals(colony.getDimension());
    }

    // ------------------------------------------------------------------ scanning

    /**
     * Calls {@code out} for every block in the loaded chunks within the radius of the anchor whose state satisfies the test (a section whose palette holds no such
     * state is skipped without a look at its blocks).
     */
    private void scan(final int radius, final Predicate<BlockState> test, final Consumer<BlockPos> out)
    {
        final ServerLevel level = level();
        if (level == null)
        {
            return;
        }
        final BlockPos center = anchor();
        final int minCx = (center.getX() - radius) >> 4;
        final int maxCx = (center.getX() + radius) >> 4;
        final int minCz = (center.getZ() - radius) >> 4;
        final int maxCz = (center.getZ() + radius) >> 4;
        final long radiusSq = (long) radius * radius;
        for (int cx = minCx; cx <= maxCx; cx++)
        {
            for (int cz = minCz; cz <= maxCz; cz++)
            {
                final LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null)
                {
                    continue;
                }
                final LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++)
                {
                    final LevelChunkSection section = sections[i];
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(test))
                    {
                        continue;
                    }
                    final int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                    for (int x = 0; x < 16; x++)
                    {
                        for (int y = 0; y < 16; y++)
                        {
                            for (int z = 0; z < 16; z++)
                            {
                                if (test.test(section.getBlockState(x, y, z)))
                                {
                                    final BlockPos pos = new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z);
                                    final long dx = pos.getX() - center.getX();
                                    final long dz = pos.getZ() - center.getZ();
                                    if (dx * dx + dz * dz <= radiusSq)
                                    {
                                        out.accept(pos);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private List<BoundingBox> buildingBoxes()
    {
        final long now = now();
        if (now - boxesAt < CACHE_TICKS && now >= boxesAt)
        {
            return boxes;
        }
        final List<BoundingBox> list = new ArrayList<>();
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            final BlockPos a = building.getCorners().getA();
            final BlockPos b = building.getCorners().getB();
            list.add(new BoundingBox(Math.min(a.getX(), b.getX()) - 2, Math.min(a.getY(), b.getY()) - 2, Math.min(a.getZ(), b.getZ()) - 2,
              Math.max(a.getX(), b.getX()) + 2, Math.max(a.getY(), b.getY()) + 2, Math.max(a.getZ(), b.getZ()) + 2));
        }
        boxes = list;
        boxesAt = now;
        return list;
    }

    /**
     * @return whether the block lies in a building, its 2 block margin, or a veto of a mod (roads, plots).
     */
    public boolean protectedBlock(final BlockPos pos)
    {
        for (final BoundingBox box : buildingBoxes())
        {
            if (box.isInside(pos))
            {
                return true;
            }
        }
        return LabourExclusions.excluded(colony, pos);
    }

    // ------------------------------------------------------------------ trees

    private List<TreeSite> scanTrees()
    {
        final long now = now();
        if (now - treesAt < CACHE_TICKS && now >= treesAt)
        {
            return trees;
        }
        final ServerLevel level = level();
        final List<TreeSite> found = new ArrayList<>();
        if (level != null)
        {
            final Set<BlockPos> seen = new HashSet<>();
            scan(LabourSettings.gatherRadius(), LabourRules::log, pos -> {
                if (!seen.contains(pos) && level.getBlockState(pos.below()).is(BlockTags.DIRT))
                {
                    final TreeSite tree = detectTree(level, pos);
                    if (tree != null)
                    {
                        seen.addAll(tree.logs());
                        found.add(tree);
                    }
                }
            });
        }
        final BlockPos hall = anchor();
        found.sort(Comparator.comparingDouble(t -> t.base().distSqr(hall)));
        // the trees nearest to the town hall stay (a colony keeps a few trees of its own)
        final int keep = LabourSettings.keepTrees();
        final List<TreeSite> usable = new ArrayList<>();
        int kept = 0;
        for (final TreeSite tree : found)
        {
            if (kept < keep && tree.base().distSqr(hall) <= 24 * 24)
            {
                kept++;
                continue;
            }
            usable.add(tree);
        }
        trees = usable;
        treesAt = now;
        return usable;
    }

    @Nullable
    private TreeSite detectTree(final ServerLevel level, final BlockPos base)
    {
        final BlockState baseState = level.getBlockState(base);
        if (!LabourRules.log(baseState))
        {
            return null;
        }
        final List<BlockPos> logs = new ArrayList<>();
        final Set<BlockPos> visited = new HashSet<>();
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(base);
        visited.add(base);
        while (!queue.isEmpty())
        {
            final BlockPos current = queue.poll();
            logs.add(current);
            if (logs.size() > MAX_TREE_LOGS)
            {
                return null;
            }
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = 0; dy <= 1; dy++)
                {
                    for (int dz = -1; dz <= 1; dz++)
                    {
                        final BlockPos next = current.offset(dx, dy, dz);
                        if (visited.add(next) && LabourRules.log(level.getBlockState(next)))
                        {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        final Set<BlockPos> leaves = new HashSet<>();
        for (final BlockPos log : logs)
        {
            if (!ownedBy(log) || LabourExclusions.excluded(colony, log))
            {
                return null;
            }
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = -1; dy <= 1; dy++)
                {
                    for (int dz = -1; dz <= 1; dz++)
                    {
                        final BlockPos near = log.offset(dx, dy, dz);
                        final BlockState state = level.getBlockState(near);
                        if (state.getBlock() instanceof LeavesBlock && !state.getValue(LeavesBlock.PERSISTENT))
                        {
                            leaves.add(near);
                        }
                    }
                }
            }
        }
        if (leaves.size() < MIN_LEAVES || protectedBlock(base))
        {
            return null;
        }
        logs.sort(Comparator.comparingInt(BlockPos::getY));
        return new TreeSite(base, logs, baseState.getBlock().asItem());
    }

    /**
     * @param log a kind of log.
     * @return whether a tree of that wood stands in reach.
     */
    public boolean hasTree(final Item log)
    {
        for (final TreeSite tree : scanTrees())
        {
            if (tree.log() == log)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * @param log   a kind of log.
     * @param from  where the labourer stands.
     * @param taken bases of trees another labourer is felling.
     * @return the nearest tree of that wood that is still there, or null.
     */
    @Nullable
    public TreeSite nearestTree(final Item log, final BlockPos from, final Set<BlockPos> taken)
    {
        final ServerLevel level = level();
        TreeSite best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final TreeSite tree : scanTrees())
        {
            if (tree.log() != log || taken.contains(tree.base()) || level == null || !LabourRules.log(level.getBlockState(tree.base())))
            {
                continue;
            }
            final double distance = tree.base().distSqr(from);
            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = tree;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ surface blocks

    private Map<Item, List<BlockPos>> scanSurface()
    {
        final long now = now();
        if (now - surfaceAt < CACHE_TICKS && now >= surfaceAt)
        {
            return surface;
        }
        surface.clear();
        final ServerLevel level = level();
        if (level != null)
        {
            final BlockPos hall = anchor();
            final int radius = LabourSettings.gatherRadius();
            final Map<Long, Boolean> chunkOwned = new HashMap<>();
            final int minX = hall.getX() - radius;
            final int minZ = hall.getZ() - radius;
            for (int x = minX; x <= hall.getX() + radius; x++)
            {
                for (int z = minZ; z <= hall.getZ() + radius; z++)
                {
                    final long dx = x - hall.getX();
                    final long dz = z - hall.getZ();
                    if (dx * dx + dz * dz > (long) radius * radius || dx * dx + dz * dz < 36 || !level.hasChunk(x >> 4, z >> 4))
                    {
                        continue;
                    }
                    final int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    final BlockPos pos = new BlockPos(x, y, z);
                    final BlockState state = level.getBlockState(pos);
                    if (!LabourRules.surface(state) && !LabourRules.stone(state))
                    {
                        continue;
                    }
                    final long key = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
                    if (!chunkOwned.computeIfAbsent(key, k -> ownedBy(pos)))
                    {
                        continue;
                    }
                    if (!level.getBlockState(pos.above()).isAir() || level.getBlockState(pos.below()).isAir() || !level.getFluidState(pos.below()).isEmpty()
                          || exposedToFluid(level, pos) || protectedBlock(pos))
                    {
                        continue;
                    }
                    final List<BlockPos> list = surface.computeIfAbsent(LabourRules.dropOf(state), k -> new ArrayList<>());
                    if (list.size() < 256)
                    {
                        list.add(pos);
                    }
                }
            }
        }
        surfaceAt = now;
        return surface;
    }

    private static boolean exposedToFluid(final ServerLevel level, final BlockPos pos)
    {
        for (final net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL)
        {
            if (!level.getFluidState(pos.relative(direction)).isEmpty() || !level.getFluidState(pos.above()).isEmpty())
            {
                return true;
            }
        }
        return false;
    }

    /**
     * @param drop what the block gives (sand, dirt, cobblestone ...).
     * @return whether such a block is within reach.
     */
    public boolean hasSurface(final Item drop)
    {
        final List<BlockPos> list = scanSurface().get(drop);
        return list != null && !list.isEmpty();
    }

    /**
     * @param drop  what the block gives.
     * @param from  where the labourer stands.
     * @param taken blocks another labourer is digging.
     * @return the nearest such block that is still there, or null.
     */
    @Nullable
    public BlockPos nearestSurface(final Item drop, final BlockPos from, final Set<BlockPos> taken)
    {
        final ServerLevel level = level();
        final List<BlockPos> list = scanSurface().get(drop);
        if (list == null || level == null)
        {
            return null;
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final BlockPos pos : list)
        {
            if (taken.contains(pos))
            {
                continue;
            }
            final BlockState state = level.getBlockState(pos);
            if ((!LabourRules.surface(state) && !LabourRules.stone(state)) || LabourRules.dropOf(state) != drop || !level.getBlockState(pos.above()).isAir())
            {
                continue;
            }
            final double distance = pos.distSqr(from);
            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ loose items

    /**
     * @param item  an item.
     * @param from  where the labourer stands.
     * @param taken the entities another labourer is walking to (by id).
     * @return the nearest item entity of that kind lying in the claim that nobody dropped on purpose, or null.
     */
    @Nullable
    public ItemEntity nearestLoose(final Item item, final BlockPos from, final Set<Integer> taken)
    {
        final ServerLevel level = level();
        if (level == null)
        {
            return null;
        }
        final BlockPos center = anchor();
        final int radius = LabourSettings.gatherRadius();
        final AABB box = new AABB(center).inflate(radius, 64, radius);
        ItemEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive() && e.getItem().is(item) && !taken.contains(e.getId())))
        {
            if (entity.getOwner() != null || entity.tickCount < 600 || !ownedBy(entity.blockPosition()))
            {
                continue;
            }
            final double distance = entity.blockPosition().distSqr(from);
            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ tables and furnaces

    private void scanPosts()
    {
        final long now = now();
        if (now - postsAt < CACHE_TICKS && now >= postsAt)
        {
            return;
        }
        final List<BlockPos> foundTables = new ArrayList<>();
        final List<BlockPos> foundFurnaces = new ArrayList<>();
        final int range = LabourSettings.postRange();
        scan(range, state -> state.is(Blocks.CRAFTING_TABLE), pos -> {
            if (usablePost(pos))
            {
                foundTables.add(pos);
            }
        });
        scan(range, state -> state.is(Blocks.FURNACE), pos -> {
            if (usablePost(pos))
            {
                foundFurnaces.add(pos);
            }
        });
        tables = foundTables;
        furnaces = foundFurnaces;
        postsAt = now;
    }

    private boolean usablePost(final BlockPos pos)
    {
        final LabourData.TableFlag flag = colony.getLabourData().flagOf(pos);
        if (flag == LabourData.TableFlag.EXCLUDE)
        {
            return false;
        }
        if (LabourSettings.postMode() == LabourModes.PostMode.MARKED && flag != LabourData.TableFlag.PREFER)
        {
            return false;
        }
        return ownedBy(pos);
    }

    public boolean hasTable()
    {
        scanPosts();
        return !tables.isEmpty();
    }

    public boolean hasFurnace()
    {
        scanPosts();
        return !furnaces.isEmpty();
    }

    /**
     * @param from  where the labourer stands.
     * @param taken tables another labourer uses now.
     * @return the table to use: preferred ones first, then the nearest; null if none is free.
     */
    @Nullable
    public BlockPos table(final BlockPos from, final Set<BlockPos> taken)
    {
        scanPosts();
        return pickPost(tables, from, taken, Blocks.CRAFTING_TABLE);
    }

    @Nullable
    public BlockPos furnace(final BlockPos from, final Set<BlockPos> taken)
    {
        scanPosts();
        return pickPost(furnaces, from, taken, Blocks.FURNACE);
    }

    @Nullable
    private BlockPos pickPost(final List<BlockPos> posts, final BlockPos from, final Set<BlockPos> taken, final net.minecraft.world.level.block.Block block)
    {
        final ServerLevel level = level();
        BlockPos best = null;
        boolean bestPreferred = false;
        double bestDistance = Double.MAX_VALUE;
        for (final BlockPos pos : posts)
        {
            if (taken.contains(pos) || level == null || !level.getBlockState(pos).is(block))
            {
                continue;
            }
            final boolean preferred = colony.getLabourData().flagOf(pos) == LabourData.TableFlag.PREFER;
            final double distance = pos.distSqr(from);
            if ((preferred && !bestPreferred) || (preferred == bestPreferred && distance < bestDistance))
            {
                best = pos;
                bestPreferred = preferred;
                bestDistance = distance;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ warehouses

    /**
     * @return the colony's warehouses that are built.
     */
    public List<IWareHouse> warehouses()
    {
        final List<IWareHouse> list = new ArrayList<>();
        for (final IWareHouse wareHouse : colony.getServerBuildingManager().getWareHouses())
        {
            if (wareHouse.getBuildingLevel() > 0)
            {
                list.add(wareHouse);
            }
        }
        return list;
    }
}
