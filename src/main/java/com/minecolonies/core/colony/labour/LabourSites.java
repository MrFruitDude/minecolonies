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
    private List<BlockPos> allTables   = List.of();
    private List<BlockPos> allFurnaces = List.of();
    private long           postsAt     = Long.MIN_VALUE / 2;

    private List<BoundingBox> boxes   = List.of();
    private long              boxesAt = Long.MIN_VALUE / 2;

    /**
     * The scans of the directed orders' areas, by order id.
     */
    private final Map<Integer, AreaScan> areas = new HashMap<>();

    /**
     * What one directed order's area holds of its resource.
     */
    private record AreaScan(long at, List<TreeSite> trees, List<BlockPos> blocks)
    {
    }

    /**
     * How long the scan of an area is reused.
     */
    public static final int AREA_CACHE_TICKS = 200;

    /**
     * Most blocks an area scan keeps.
     */
    public static final int AREA_MAX_BLOCKS = 512;

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
        areas.clear();
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
            list.add(box(building));
        }
        boxes = list;
        boxesAt = now;
        return list;
    }

    /**
     * @return the building's box with its 2 block margin.
     */
    private static BoundingBox box(final IBuilding building)
    {
        final BlockPos a = building.getCorners().getA();
        final BlockPos b = building.getCorners().getB();
        return new BoundingBox(Math.min(a.getX(), b.getX()) - 2, Math.min(a.getY(), b.getY()) - 2, Math.min(a.getZ(), b.getZ()) - 2,
          Math.max(a.getX(), b.getX()) + 2, Math.max(a.getY(), b.getY()) + 2, Math.max(a.getZ(), b.getZ()) + 2);
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
        final List<BlockPos> everyTable = new ArrayList<>();
        final List<BlockPos> everyFurnace = new ArrayList<>();
        final int range = LabourSettings.postRange();
        scan(range, state -> state.is(Blocks.CRAFTING_TABLE), pos -> {
            if (ownedBy(pos))
            {
                everyTable.add(pos);
                if (usablePost(pos))
                {
                    foundTables.add(pos);
                }
            }
        });
        scan(range, state -> state.is(Blocks.FURNACE), pos -> {
            if (ownedBy(pos))
            {
                everyFurnace.add(pos);
                if (usablePost(pos))
                {
                    foundFurnaces.add(pos);
                }
            }
        });
        tables = foundTables;
        furnaces = foundFurnaces;
        allTables = everyTable;
        allFurnaces = everyFurnace;
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
        return true;
    }

    /**
     * @return every crafting table in the colony's claim within the post range, flagged or not (the flags are the managers' to change).
     */
    public List<BlockPos> allTables()
    {
        scanPosts();
        return allTables;
    }

    /**
     * @return every furnace in the colony's claim within the post range.
     */
    public List<BlockPos> allFurnaces()
    {
        scanPosts();
        return allFurnaces;
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

    // ------------------------------------------------------------------ a directed order's area

    /**
     * @param resource a resource of a directed order.
     * @param state    a block.
     * @return whether a worker of the order may take the block: natural stone (never cobblestone, which somebody may have placed), grass or dirt, gravel. Logs are
     *   taken only as a whole natural tree ({@link #nearestTreeIn}).
     */
    public static boolean directedBlock(final DirectedOrder.Resource resource, final BlockState state)
    {
        return switch (resource)
        {
            case WOOD -> false;
            case COBBLESTONE -> state.is(Blocks.STONE);
            case DIRT -> state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK);
            case GRAVEL -> state.is(Blocks.GRAVEL);
        };
    }

    /**
     * @param area an area of the world.
     * @return whether every chunk of the area is loaded and part of this colony's claim (nothing is loaded for the check).
     */
    public boolean areaUsable(final DirectedOrder.Area area)
    {
        final ServerLevel level = level();
        if (level == null)
        {
            return false;
        }
        final int y = anchor().getY();
        for (int cx = area.minX() >> 4; cx <= area.maxX() >> 4; cx++)
        {
            for (int cz = area.minZ() >> 4; cz <= area.maxZ() >> 4; cz++)
            {
                final int x = Math.max(area.minX(), Math.min(area.maxX(), cx << 4));
                final int z = Math.max(area.minZ(), Math.min(area.maxZ(), cz << 4));
                if (level.getChunkSource().getChunkNow(cx, cz) == null || !ownedBy(new BlockPos(x, y, z)))
                {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Forgets the scan of one order's area (the order ended).
     */
    public void forgetArea(final int orderId)
    {
        areas.remove(orderId);
    }

    private AreaScan scanArea(final int orderId, final DirectedOrder.Area area, final DirectedOrder.Resource resource, final int floor)
    {
        final long now = now();
        final AreaScan cached = areas.get(orderId);
        if (cached != null && now - cached.at() < AREA_CACHE_TICKS && now >= cached.at())
        {
            return cached;
        }
        final ServerLevel level = level();
        final List<TreeSite> trees = new ArrayList<>();
        final List<BlockPos> blocks = new ArrayList<>();
        if (level != null)
        {
            final Map<Long, Boolean> chunkOwned = new HashMap<>();
            final Set<BlockPos> seen = new HashSet<>();
            for (int x = area.minX(); x <= area.maxX(); x++)
            {
                for (int z = area.minZ(); z <= area.maxZ(); z++)
                {
                    if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null)
                    {
                        continue;
                    }
                    final BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
                    final long key = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
                    if (!chunkOwned.computeIfAbsent(key, k -> ownedBy(top)))
                    {
                        continue;
                    }
                    final BlockState state = level.getBlockState(top);
                    if (resource == DirectedOrder.Resource.WOOD)
                    {
                        if (!LabourRules.log(state) || seen.contains(top))
                        {
                            continue;
                        }
                        BlockPos base = top;
                        while (LabourRules.log(level.getBlockState(base.below())) && top.getY() - base.getY() < MAX_TREE_LOGS)
                        {
                            base = base.below();
                        }
                        if (seen.contains(base) || !level.getBlockState(base.below()).is(BlockTags.DIRT))
                        {
                            continue;
                        }
                        seen.add(base);
                        final TreeSite tree = treeInArea(level, base, area);
                        if (tree != null)
                        {
                            seen.addAll(tree.logs());
                            trees.add(tree);
                        }
                        continue;
                    }
                    if (blocks.size() >= AREA_MAX_BLOCKS || !directedBlock(resource, state) || top.getY() < floor || makesPit(level, top))
                    {
                        continue;
                    }
                    if (!level.getBlockState(top.above()).isAir() || level.getBlockState(top.below()).isAir() || !level.getFluidState(top.below()).isEmpty()
                          || exposedToFluid(level, top) || protectedBlock(top))
                    {
                        continue;
                    }
                    blocks.add(top);
                }
            }
        }
        final AreaScan scan = new AreaScan(now, List.copyOf(trees), List.copyOf(blocks));
        areas.put(orderId, scan);
        return scan;
    }

    /**
     * @param orderId the order (its area's scan is shared by its workers).
     * @param area    the order's area.
     * @param from    where the worker stands.
     * @param taken   bases of trees another labourer is felling.
     * @return the nearest natural tree standing wholly in the area, still there, or null.
     */
    @Nullable
    public TreeSite nearestTreeIn(final int orderId, final DirectedOrder.Area area, final BlockPos from, final Set<BlockPos> taken)
    {
        final TreeSite found = nearestTreeInScan(orderId, area, from, taken);
        return found != null || !rescan(orderId) ? found : nearestTreeInScan(orderId, area, from, taken);
    }

    /**
     * Nothing was found in a scan that is a second old or more: the area is scanned again once (blocks dug since the scan uncover the next ones).
     */
    private boolean rescan(final int orderId)
    {
        final AreaScan cached = areas.get(orderId);
        if (cached == null || now() - cached.at() < 20L)
        {
            return false;
        }
        areas.remove(orderId);
        return true;
    }

    @Nullable
    private TreeSite nearestTreeInScan(final int orderId, final DirectedOrder.Area area, final BlockPos from, final Set<BlockPos> taken)
    {
        final ServerLevel level = level();
        TreeSite best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final TreeSite tree : scanArea(orderId, area, DirectedOrder.Resource.WOOD, DirectedOrder.NO_FLOOR).trees())
        {
            if (taken.contains(tree.base()) || level == null || !level.isLoaded(tree.base()) || !LabourRules.log(level.getBlockState(tree.base())))
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

    /**
     * @param orderId  the order.
     * @param area     the order's area.
     * @param resource what the order gathers (not wood).
     * @param floor    the lowest y the order may dig at.
     * @param from     where the worker stands.
     * @param taken    blocks another labourer is digging.
     * @return the nearest top block of the resource in the area that is still there, or null.
     */
    @Nullable
    public BlockPos nearestBlockIn(final int orderId, final DirectedOrder.Area area, final DirectedOrder.Resource resource, final int floor, final BlockPos from,
      final Set<BlockPos> taken)
    {
        final BlockPos found = nearestBlockInScan(orderId, area, resource, floor, from, taken);
        return found != null || !rescan(orderId) ? found : nearestBlockInScan(orderId, area, resource, floor, from, taken);
    }

    @Nullable
    private BlockPos nearestBlockInScan(final int orderId, final DirectedOrder.Area area, final DirectedOrder.Resource resource, final int floor, final BlockPos from,
      final Set<BlockPos> taken)
    {
        final ServerLevel level = level();
        if (level == null)
        {
            return null;
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final BlockPos pos : scanArea(orderId, area, resource, floor).blocks())
        {
            if (taken.contains(pos) || !level.isLoaded(pos) || !directedBlock(resource, level.getBlockState(pos)) || !level.getBlockState(pos.above()).isAir())
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

    /**
     * @return the lowest top block of the loaded columns of the area (the order's digging floor is measured from it), or the anchor's y when none is loaded.
     */
    public int lowestSurface(final DirectedOrder.Area area)
    {
        final ServerLevel level = level();
        if (level == null)
        {
            return anchor().getY();
        }
        int lowest = Integer.MAX_VALUE;
        for (int x = area.minX(); x <= area.maxX(); x++)
        {
            for (int z = area.minZ(); z <= area.maxZ(); z++)
            {
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null)
                {
                    lowest = Math.min(lowest, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1);
                }
            }
        }
        return lowest == Integer.MAX_VALUE ? anchor().getY() : lowest;
    }

    /**
     * @return whether digging the top block would leave a hole two or more blocks deep next to a neighbour (an order takes the ground off in layers, never a shaft); a
     *   neighbour in a chunk that is not loaded counts as a pit (it is never loaded to look).
     */
    private static boolean makesPit(final ServerLevel level, final BlockPos top)
    {
        for (final net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL)
        {
            final BlockPos side = top.relative(direction);
            if (level.getChunkSource().getChunkNow(side.getX() >> 4, side.getZ() >> 4) == null
                  || level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, side.getX(), side.getZ()) - 1 >= top.getY() + 2)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * A natural tree for a directed order: its logs and the leaves around them are read only in loaded chunks, every log stands in the area and in this colony, the base
     * is no building's, and enough leaves nobody placed hang on it. A log found outside the area (a trunk or branch that leaves it) rejects the whole tree.
     *
     * @return the tree, or null.
     */
    @Nullable
    private TreeSite treeInArea(final ServerLevel level, final BlockPos base, final DirectedOrder.Area area)
    {
        final BlockState baseState = level.getBlockState(base);
        if (!LabourRules.log(baseState) || !area.contains(base))
        {
            return null;
        }
        final List<BlockPos> logs = new ArrayList<>();
        final Set<BlockPos> visited = new HashSet<>();
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(base);
        visited.add(base);
        final Map<Long, Boolean> owned = new HashMap<>();
        while (!queue.isEmpty())
        {
            final BlockPos current = queue.poll();
            if (!area.contains(current) || logs.size() >= MAX_TREE_LOGS)
            {
                return null;
            }
            final long key = ((long) (current.getX() >> 4) << 32) ^ ((current.getZ() >> 4) & 0xffffffffL);
            // every log, not just the base: a natural tree that touches a building's logs is the building's
            if (!owned.computeIfAbsent(key, k -> ownedBy(current)) || protectedBlock(current))
            {
                return null;
            }
            logs.add(current);
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = 0; dy <= 1; dy++)
                {
                    for (int dz = -1; dz <= 1; dz++)
                    {
                        final BlockPos next = current.offset(dx, dy, dz);
                        if (!visited.add(next))
                        {
                            continue;
                        }
                        if (!level.isLoaded(next))
                        {
                            // (what lies there is not known without loading it: the tree is left alone)
                            return null;
                        }
                        if (LabourRules.log(level.getBlockState(next)))
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
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = -1; dy <= 1; dy++)
                {
                    for (int dz = -1; dz <= 1; dz++)
                    {
                        final BlockPos near = log.offset(dx, dy, dz);
                        if (!level.isLoaded(near))
                        {
                            continue;
                        }
                        final BlockState state = level.getBlockState(near);
                        if (state.getBlock() instanceof LeavesBlock && !state.getValue(LeavesBlock.PERSISTENT))
                        {
                            leaves.add(near);
                        }
                    }
                }
            }
        }
        if (leaves.size() < MIN_LEAVES)
        {
            return null;
        }
        logs.sort(Comparator.comparingInt(BlockPos::getY));
        return new TreeSite(base, logs, baseState.getBlock().asItem());
    }

    // ------------------------------------------------------------------ the last look before a directed worker breaks a block

    /**
     * Whether a directed worker may still break the block now, though borders, buildings and vetoes may have changed since the area was scanned: it lies in the area,
     * in a loaded chunk (nothing is loaded to look) and in this colony's claim, and in no building's box (read from the buildings now, not the scan's cache) and no
     * veto. Called once per block, when it breaks.
     */
    public boolean directedSafe(final DirectedOrder.Area area, final BlockPos pos)
    {
        final ServerLevel level = level();
        if (level == null || !area.contains(pos) || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null || !ownedBy(pos))
        {
            return false;
        }
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            if (box(building).isInside(pos))
            {
                return false;
            }
        }
        return !LabourExclusions.excluded(colony, pos);
    }

    /**
     * {@link #directedSafe} for a top block of a directed order: still the resource, open above, standing on solid ground above the floor, no fluid next to it and no
     * pit left by digging it.
     */
    public boolean directedSurfaceSafe(final DirectedOrder.Area area, final DirectedOrder.Resource resource, final int floor, final BlockPos pos)
    {
        if (!directedSafe(area, pos))
        {
            return false;
        }
        final ServerLevel level = level();
        // (makesPit before the fluid check: it refuses a neighbour in a chunk that is not loaded, which the fluid check would load)
        return pos.getY() >= floor && directedBlock(resource, level.getBlockState(pos)) && level.getBlockState(pos.above()).isAir() && !level.getBlockState(pos.below()).isAir()
                 && level.getFluidState(pos.below()).isEmpty() && !makesPit(level, pos) && !exposedToFluid(level, pos);
    }
}
