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
    /** Longest side (blocks, without margin) of a decoration controller's box that is taken as a decoration. */
    public static final int MAX_DECORATION_SIDE = 128;

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
    private List<BlockPos> cutters     = List.of();
    private List<BlockPos> allCutters  = List.of();
    private long           postsAt     = Long.MIN_VALUE / 2;

    /**
     * What the world's axe makes of each kind of log, read at a tree of that kind (dropped with the tree scan).
     */
    private final Map<Item, Item> strippedOf = new HashMap<>();

    /**
     * Most trees a scan keeps (the scan over a wide gathering radius stops looking once it has these).
     */
    public static final int MAX_TREES = 256;

    private List<BoundingBox> boxes   = List.of();
    private long              boxesAt = Long.MIN_VALUE / 2;
    /** The boxes of the finished decorations (their controllers) in reach, cached like the buildings' boxes. */
    private List<BoundingBox> decorations   = List.of();
    private long              decorationsAt = Long.MIN_VALUE / 2;

    /**
     * The scans of the directed orders' areas, by order id.
     */
    private final Map<Integer, AreaScan> areas = new HashMap<>();

    /** Ticks before a work order whose blueprint did not load is asked for its box again. */
    private static final long BOX_RETRY_TICKS = 1200L;

    /** Work orders whose box did not load: id to {misses, tick of the last miss}. */
    private final Map<Integer, long[]> boxMisses = new HashMap<>();

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
        decorationsAt = Long.MIN_VALUE / 2;
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
     * Whether a labourer may go there: the chunk is loaded and entities tick in it. A chunk that is only loaded (at the edge of the simulation distance, or
     * a player walked away) stops the labourer who walks in: his session would never end (builder review c3 R3).
     */
    public static boolean simulated(final ServerLevel level, final BlockPos pos)
    {
        return level.isLoaded(pos) && level.isPositionEntityTicking(pos);
    }

    /**
     * @param pos a position.
     * @return whether it lies inside this colony's claim (and in a chunk where entities tick: see {@link #simulated}).
     */
    public boolean ownedBy(final BlockPos pos)
    {
        final ServerLevel level = level();
        if (level == null || !simulated(level, pos))
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
        list.addAll(orderBoxes());
        list.addAll(decorationBoxes());
        boxes = list;
        boxesAt = now;
        return list;
    }

    /**
     * @return the boxes of the colony's queued and running work orders (decorations and road pieces included), with a 1 block margin: what a builder will place
     *   there is the colony's own, so labour leaves it alone.
     */
    private List<BoundingBox> orderBoxes()
    {
        final List<BoundingBox> list = new ArrayList<>();
        final long now = now();
        final Map<Integer, com.minecolonies.api.colony.workorders.IServerWorkOrder> orders = colony.getWorkManager().getWorkOrders();
        boxMisses.keySet().retainAll(orders.keySet());
        for (final com.minecolonies.api.colony.workorders.IServerWorkOrder order : orders.values())
        {
            final long[] miss = boxMisses.get(order.getID());
            if (miss != null && miss[0] >= 2 && now - miss[1] >= 0 && now - miss[1] < BOX_RETRY_TICKS)
            {
                // its blueprint did not load: not asked again on every check
                continue;
            }
            try
            {
                final AABB box = order.getBoundingBox();
                if (box == null || box == com.minecolonies.api.util.constant.Constants.EMPTY_AABB)
                {
                    // the first ask queues the load; a second miss means the blueprint is missing, asked again after BOX_RETRY_TICKS
                    boxMisses.put(order.getID(), new long[] {miss == null ? 1 : miss[0] + 1, now});
                    continue;
                }
                boxMisses.remove(order.getID());
                list.add(new BoundingBox((int) Math.floor(box.minX) - 1, (int) Math.floor(box.minY) - 1, (int) Math.floor(box.minZ) - 1,
                  (int) Math.ceil(box.maxX) + 1, (int) Math.ceil(box.maxY) + 1, (int) Math.ceil(box.maxZ) + 1));
            }
            catch (final RuntimeException e)
            {
                // one bad order does not drop the others' protection
                boxMisses.put(order.getID(), new long[] {2, now});
            }
        }
        return list;
    }

    /**
     * @return the boxes, with a 1 block margin, of the finished decorations and road pieces whose decoration controller stands in a loaded chunk of this colony's claim
     *   within reach or in one of its quarry zones. MineColonies keeps no list of finished decorations, but each keeps its controller block with the blueprint's corners:
     *   this protects the decorations finished before the builders recorded the blocks they place (Wave B). A decoration with no controller left is not seen here.
     */
    private List<BoundingBox> decorationBoxes()
    {
        final long now = now();
        if (now - decorationsAt < CACHE_TICKS && now >= decorationsAt)
        {
            return decorations;
        }
        final List<BoundingBox> list = new ArrayList<>();
        final ServerLevel level = level();
        if (level != null)
        {
            final Set<Long> chunks = new HashSet<>();
            final BlockPos hall = anchor();
            final int radius = LabourSettings.gatherRadius();
            for (int cx = (hall.getX() - radius) >> 4; cx <= (hall.getX() + radius) >> 4; cx++)
            {
                for (int cz = (hall.getZ() - radius) >> 4; cz <= (hall.getZ() + radius) >> 4; cz++)
                {
                    chunks.add(((long) cx << 32) | (cz & 0xffffffffL));
                }
            }
            for (final QuarryZones.Zone zone : colony.getLabourData().quarries().zones())
            {
                for (int cx = zone.area().minX() >> 4; cx <= zone.area().maxX() >> 4; cx++)
                {
                    for (int cz = zone.area().minZ() >> 4; cz <= zone.area().maxZ() >> 4; cz++)
                    {
                        chunks.add(((long) cx << 32) | (cz & 0xffffffffL));
                    }
                }
            }
            for (final long key : chunks)
            {
                final LevelChunk chunk = level.getChunkSource().getChunkNow((int) (key >> 32), (int) key);
                if (chunk == null)
                {
                    continue;
                }
                for (final net.minecraft.world.level.block.entity.BlockEntity entity : chunk.getBlockEntities().values())
                {
                    if (entity instanceof com.minecolonies.core.tileentities.TileEntityDecorationController controller && ownedBy(entity.getBlockPos()))
                    {
                        final BoundingBox box = decorationBox(controller);
                        if (box != null)
                        {
                            list.add(box);
                        }
                    }
                }
            }
        }
        decorations = list;
        decorationsAt = now;
        return list;
    }

    /**
     * @return the decoration's box from its controller's blueprint corners (relative to the controller), with a 1 block margin; the controller alone when it has none;
     *   null when a side is longer than {@link #MAX_DECORATION_SIDE} (not taken as a decoration).
     */
    @Nullable
    static BoundingBox decorationBox(final com.minecolonies.core.tileentities.TileEntityDecorationController controller)
    {
        final BlockPos at = controller.getBlockPos();
        final var corners = controller.getSchematicCorners();
        if (corners.getA().equals(at) && corners.getB().equals(at))
        {
            // (no corners kept: getSchematicCorners answers the controller's own position)
            return new BoundingBox(at.getX() - 1, at.getY() - 1, at.getZ() - 1, at.getX() + 1, at.getY() + 1, at.getZ() + 1);
        }
        final long[] a = {(long) at.getX() + corners.getA().getX(), (long) at.getY() + corners.getA().getY(), (long) at.getZ() + corners.getA().getZ()};
        final long[] b = {(long) at.getX() + corners.getB().getX(), (long) at.getY() + corners.getB().getY(), (long) at.getZ() + corners.getB().getZ()};
        for (int i = 0; i < 3; i++)
        {
            if (Math.abs(a[i] - b[i]) + 1 > MAX_DECORATION_SIDE)
            {
                // (corners no decoration has: corrupted data; not taken as a decoration, so it cannot freeze a whole quarry zone)
                return null;
            }
        }
        return new BoundingBox((int) Math.min(a[0], b[0]) - 1, (int) Math.min(a[1], b[1]) - 1, (int) Math.min(a[2], b[2]) - 1,
          (int) Math.max(a[0], b[0]) + 1, (int) Math.max(a[1], b[1]) + 1, (int) Math.max(a[2], b[2]) + 1);
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
        return LabourExclusions.excluded(colony, pos) || placed(pos);
    }

    /**
     * {@link #protectedBlock} read now: the buildings' boxes from the buildings themselves (a building placed since the cached scan counts).
     */
    public boolean protectedNow(final BlockPos pos)
    {
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            if (box(building).isInside(pos))
            {
                return true;
            }
        }
        for (final BoundingBox box : orderBoxes())
        {
            if (box.isInside(pos))
            {
                return true;
            }
        }
        for (final BoundingBox box : decorationBoxes())
        {
            if (box.isInside(pos))
            {
                return true;
            }
        }
        return LabourExclusions.excluded(colony, pos) || placed(pos);
    }

    /**
     * @return whether somebody placed a block there since the colony records placements (or the record is full: then every block counts as placed).
     */
    public boolean placed(final BlockPos pos)
    {
        return colony.getLabourData().placements().placed(pos.asLong());
    }

    /**
     * The last look before a labourer changes a block (strips, breaks, plants): it lies in the area (when there is one), in a loaded chunk (nothing is loaded to look)
     * and in this colony's claim, in no building's box read now, under no veto and is not on the record of placed blocks.
     */
    public boolean safeNow(@Nullable final DirectedOrder.Area area, final BlockPos pos)
    {
        final ServerLevel level = level();
        if (level == null || (area != null && !area.contains(pos)) || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null || !ownedBy(pos))
        {
            return false;
        }
        return !protectedNow(pos);
    }

    private static boolean loadedNow(final ServerLevel level, final BlockPos pos)
    {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
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
                if (found.size() < MAX_TREES && !seen.contains(pos) && level.getBlockState(pos.below()).is(BlockTags.DIRT))
                {
                    final TreeSite tree = naturalTree(level, pos, null, false);
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
        strippedOf.clear();
        return usable;
    }

    /**
     * The proof of a natural tree, the same for the automatic scan and a directed order's area. Fail closed: a doubt leaves the whole tree standing.
     * <ul>
     *   <li>the base is a natural log ({@link LabourRules#naturalLog}: never stripped, never a bark block) that the world's axe would still strip, on dirt;</li>
     *   <li>every log connected to it (all 26 neighbours) is of the base's very block: a stripped log, another wood or a bark block touching it means built work or
     *   another tree, and nothing is felled. A connected log below the base, or past {@link #MAX_TREE_LOGS}, rejects too;</li>
     *   <li>every log is read only in loaded chunks (a neighbour in a chunk that is not loaded rejects the tree), lies in this colony's claim, in the area when there is
     *   one, in no building's box, under no veto and not on the record of placed blocks ({@code fresh}: the boxes are read from the buildings now);</li>
     *   <li>only the trunk stands on the ground: on dirt at the base's height, one log or a square of four; any other log rests on its own tree, air, natural leaves or a
     *   plant, never on something built;</li>
     *   <li>no block next to a log is construction ({@link LabourRules#construction}), and no straight run of horizontal logs is longer than
     *   {@link LabourRules#MAX_BRANCH_RUN} (a beam);</li>
     *   <li>at least {@link #MIN_LEAVES} leaves nobody placed hang on it.</li>
     * </ul>
     * A log that the world grew and a log somebody placed before the colony recorded placements look the same: such a log is only refused by the rules above.
     *
     * @return the tree, or null.
     */
    @Nullable
    private TreeSite naturalTree(final ServerLevel level, final BlockPos base, @Nullable final DirectedOrder.Area area, final boolean fresh)
    {
        if (!loadedNow(level, base) || (area != null && !area.contains(base)))
        {
            return null;
        }
        final BlockState baseState = level.getBlockState(base);
        if (!LabourRules.naturalLog(baseState) || !level.getBlockState(base.below()).is(BlockTags.DIRT)
              || LabourStrip.stripped(level, base, LabourStrip.standardAxe(level)) == null)
        {
            return null;
        }
        final net.minecraft.world.level.block.Block species = baseState.getBlock();
        final List<BlockPos> logs = new ArrayList<>();
        final Set<BlockPos> visited = new HashSet<>();
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(base);
        visited.add(base);
        final Map<Long, Boolean> owned = new HashMap<>();
        while (!queue.isEmpty())
        {
            final BlockPos current = queue.poll();
            if ((area != null && !area.contains(current)) || logs.size() >= MAX_TREE_LOGS)
            {
                return null;
            }
            final long key = ((long) (current.getX() >> 4) << 32) ^ ((current.getZ() >> 4) & 0xffffffffL);
            // every log, not just the base: a natural tree that touches a building's logs is the building's
            if (!owned.computeIfAbsent(key, k -> ownedBy(current)) || (fresh ? protectedNow(current) : protectedBlock(current)))
            {
                return null;
            }
            logs.add(current);
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = -1; dy <= 1; dy++)
                {
                    for (int dz = -1; dz <= 1; dz++)
                    {
                        final BlockPos next = current.offset(dx, dy, dz);
                        if (!visited.add(next))
                        {
                            continue;
                        }
                        if (!loadedNow(level, next))
                        {
                            // (what lies there is not known without loading it: the tree is left alone)
                            return null;
                        }
                        final BlockState state = level.getBlockState(next);
                        if (!state.is(BlockTags.LOGS))
                        {
                            continue;
                        }
                        if (state.getBlock() != species || next.getY() < base.getY())
                        {
                            return null;
                        }
                        queue.add(next);
                    }
                }
            }
        }
        final Set<BlockPos> inTree = new HashSet<>(logs);
        final List<BlockPos> ground = new ArrayList<>();
        final Set<BlockPos> leaves = new HashSet<>();
        for (final BlockPos log : logs)
        {
            final BlockPos below = log.below();
            if (!inTree.contains(below))
            {
                final BlockState under = level.getBlockState(below);
                if (under.is(BlockTags.DIRT))
                {
                    ground.add(log);
                }
                else if (!under.isAir() && !under.canBeReplaced() && !(under.getBlock() instanceof LeavesBlock && !under.getValue(LeavesBlock.PERSISTENT)))
                {
                    return null;
                }
            }
            if (branchRun(level, log, inTree) > LabourRules.MAX_BRANCH_RUN)
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
                        if (inTree.contains(near) || !loadedNow(level, near))
                        {
                            continue;
                        }
                        final BlockState state = level.getBlockState(near);
                        if (LabourRules.construction(state))
                        {
                            return null;
                        }
                        if (state.getBlock() instanceof LeavesBlock && !state.getValue(LeavesBlock.PERSISTENT))
                        {
                            leaves.add(near);
                        }
                    }
                }
            }
        }
        if (leaves.size() < MIN_LEAVES || !trunkOnGround(base, ground))
        {
            return null;
        }
        logs.sort(Comparator.comparingInt(BlockPos::getY));
        return new TreeSite(base, logs, species.asItem());
    }

    /**
     * @return whether the logs standing on the ground are the trunk: the base alone, or a square of four at its height.
     */
    static boolean trunkOnGround(final BlockPos base, final List<BlockPos> ground)
    {
        if (ground.size() == 1)
        {
            return ground.get(0).equals(base);
        }
        if (ground.size() != 4)
        {
            return false;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (final BlockPos pos : ground)
        {
            if (pos.getY() != base.getY())
            {
                return false;
            }
            minX = Math.min(minX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
        }
        for (final BlockPos pos : ground)
        {
            if (pos.getX() - minX > 1 || pos.getZ() - minZ > 1)
            {
                return false;
            }
        }
        return new HashSet<>(ground).size() == 4;
    }

    /**
     * @return the length of the straight run of logs lying along the log's own horizontal axis through it (0 for an upright log).
     */
    private static int branchRun(final ServerLevel level, final BlockPos log, final Set<BlockPos> inTree)
    {
        final BlockState state = level.getBlockState(log);
        if (!state.hasProperty(net.minecraft.world.level.block.RotatedPillarBlock.AXIS))
        {
            return 0;
        }
        final net.minecraft.core.Direction.Axis axis = state.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS);
        if (axis == net.minecraft.core.Direction.Axis.Y)
        {
            return 0;
        }
        int run = 1;
        for (final net.minecraft.core.Direction.AxisDirection sign : net.minecraft.core.Direction.AxisDirection.values())
        {
            final net.minecraft.core.Direction step = net.minecraft.core.Direction.fromAxisAndDirection(axis, sign);
            BlockPos next = log.relative(step);
            while (inTree.contains(next) && level.getBlockState(next).getOptionalValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS).orElse(null) == axis)
            {
                run++;
                next = next.relative(step);
            }
        }
        return run;
    }

    /**
     * The proof of {@link #naturalTree} once more, read now, before a labourer changes the first log of a tree that came out of a scan: the same logs must still be a
     * natural tree with the buildings, vetoes, claim and placed blocks as they are now.
     */
    public boolean verifyTree(final TreeSite tree, @Nullable final DirectedOrder.Area area)
    {
        final ServerLevel level = level();
        if (level == null)
        {
            return false;
        }
        final TreeSite now = naturalTree(level, tree.base(), area, true);
        return now != null && now.log() == tree.log() && new HashSet<>(now.logs()).equals(new HashSet<>(tree.logs()));
    }

    /**
     * Whether a labourer may still change the next log of a tree he works on, read now before each strip and each break: every log still to take is
     * {@link #safeNow}, is still the tree's own natural block (or exactly the stripped state the labourer set there himself, {@code ours}), and no log or construction
     * that is not one of the logs still to take touches one of them (one standing where a log of the tree was felled counts) (a chunk that is not loaded next to one counts as such). Otherwise the rest of the tree stands.
     */
    public boolean treeStillSafe(final TreeSite tree, final List<BlockPos> remaining, @Nullable final DirectedOrder.Area area, final Map<BlockPos, BlockState> ours)
    {
        final ServerLevel level = level();
        final net.minecraft.world.level.block.Block species = net.minecraft.world.level.block.Block.byItem(tree.log());
        if (level == null || species == Blocks.AIR)
        {
            return false;
        }
        // (only the logs still to take are skipped as neighbours: a log or construction now standing where a log of the tree was already felled is new and stops the rest)
        final Set<BlockPos> left = new HashSet<>(remaining);
        for (final BlockPos pos : remaining)
        {
            if (!safeNow(area, pos))
            {
                return false;
            }
            final BlockState state = level.getBlockState(pos);
            final BlockState mine = ours.get(pos);
            if (mine != null ? !state.equals(mine) : state.getBlock() != species)
            {
                return false;
            }
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = -1; dy <= 1; dy++)
                {
                    for (int dz = -1; dz <= 1; dz++)
                    {
                        final BlockPos near = pos.offset(dx, dy, dz);
                        if (left.contains(near))
                        {
                            continue;
                        }
                        if (!loadedNow(level, near))
                        {
                            return false;
                        }
                        final BlockState state2 = level.getBlockState(near);
                        if (state2.is(BlockTags.LOGS) || LabourRules.construction(state2))
                        {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Forgets a tree the fresh look refused (it is out of the scans until they run again).
     */
    public void dropTree(final BlockPos base)
    {
        final List<TreeSite> kept = new ArrayList<>(trees);
        kept.removeIf(t -> t.base().equals(base));
        trees = kept;
        for (final Map.Entry<Integer, AreaScan> entry : areas.entrySet())
        {
            final List<TreeSite> list = new ArrayList<>(entry.getValue().trees());
            if (list.removeIf(t -> t.base().equals(base)))
            {
                entry.setValue(new AreaScan(entry.getValue().at(), List.copyOf(list), entry.getValue().blocks()));
            }
        }
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

    /**
     * @return what the world's standard axe makes of the tree's logs (read at its base, the species of the game's own transformer), or null.
     */
    @Nullable
    public Item strippedOf(final TreeSite tree)
    {
        final ServerLevel level = level();
        if (level == null)
        {
            return null;
        }
        final Item known = strippedOf.get(tree.log());
        if (known != null || strippedOf.containsKey(tree.log()))
        {
            return known;
        }
        final LabourStrip.Result result = LabourStrip.stripped(level, tree.base(), LabourStrip.standardAxe(level));
        final Item stripped = result == null ? null : result.state().getBlock().asItem();
        final Item allowed = stripped != null && LabourRules.stripResult(stripped) ? stripped : null;
        strippedOf.put(tree.log(), allowed);
        return allowed;
    }

    /**
     * @param stripped a kind of stripped log.
     * @return whether a tree stands in reach whose logs an axe strips to it.
     */
    public boolean hasStripTree(final Item stripped)
    {
        for (final TreeSite tree : scanTrees())
        {
            if (strippedOf(tree) == stripped)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the nearest tree, still standing and not taken, whose logs an axe strips to the kind, or null.
     */
    @Nullable
    public TreeSite nearestStripTree(final Item stripped, final BlockPos from, final Set<BlockPos> taken)
    {
        final ServerLevel level = level();
        TreeSite best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final TreeSite tree : scanTrees())
        {
            if (taken.contains(tree.base()) || level == null || !LabourRules.log(level.getBlockState(tree.base())) || strippedOf(tree) != stripped)
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
        // (whether a block of the ground is the world's or a player's own building cannot be told from the block: unless the server owner allows it, automatic labour
        // digs no ground at all but in the quarry zones its managers marked)
        final boolean everywhere = LabourSettings.digUnprovenGround(colony);
        if (level != null && everywhere)
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
                          || !sidesLoaded(level, pos) || exposedToFluid(level, pos) || protectedBlock(pos))
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
        if (level != null)
        {
            scanQuarries(level, everywhere);
        }
        surfaceAt = now;
        return surface;
    }

    /**
     * Adds the top blocks of the colony's quarry zones that labour may dig now ({@link #quarryAllows}): the checks of the automatic scan, in the zone's terraces. With
     * {@code everywhere} (digging allowed in reach anyway) the columns the reach scan saw are not added twice.
     */
    private void scanQuarries(final ServerLevel level, final boolean everywhere)
    {
        final List<QuarryZones.Zone> zones = colony.getLabourData().quarries().zones();
        if (zones.isEmpty())
        {
            return;
        }
        final Set<BlockPos> known = new HashSet<>();
        if (everywhere)
        {
            surface.values().forEach(known::addAll);
        }
        final int depth = LabourSettings.quarryDepth();
        final Map<Long, Boolean> chunkOwned = new HashMap<>();
        final QuarryZones quarries = colony.getLabourData().quarries();
        for (final QuarryZones.Zone old : zones)
        {
            QuarryZones.Zone zone = old;
            if (quarries.pending(zone.id()))
            {
                // a zone saved before the tops were kept: its tops are sampled once, when all of it is loaded and owned; until then nothing is dug there.
                // An uneven sample is remembered and tried again only after MIGRATION_RETRY_TICKS, or at once after the area was not all loaded.
                if (!areaUsable(zone.area()))
                {
                    quarries.areaNotLoaded(zone.id());
                    continue;
                }
                if (!quarries.migrationDue(zone.id(), now()))
                {
                    continue;
                }
                if (!quarries.migrate(zone.id(), columnTops(zone.area()), depth, now()))
                {
                    LabourStats.log("quarry zone {} of colony {}: saved before the tops were kept and its ground is uneven; mark it again", zone.id(), colony.getID());
                    continue;
                }
                colony.markDirty();
                LabourStats.log("quarry zone {} of colony {}: tops sampled from the ground (saved before the tops were kept)", zone.id(), colony.getID());
                zone = quarries.byId(zone.id());
                if (zone == null)
                {
                    continue;
                }
            }
            final DirectedOrder.Area area = zone.area();
            for (int x = area.minX(); x <= area.maxX(); x++)
            {
                for (int z = area.minZ(); z <= area.maxZ(); z++)
                {
                    if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null)
                    {
                        continue;
                    }
                    final BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
                    if (known.contains(pos) || !QuarryZones.diggable(zone, pos, depth))
                    {
                        continue;
                    }
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
                          || !sidesLoaded(level, pos) || exposedToFluid(level, pos) || protectedBlock(pos))
                    {
                        continue;
                    }
                    final List<BlockPos> list = surface.computeIfAbsent(LabourRules.dropOf(state), k -> new ArrayList<>());
                    if (list.size() < 512)
                    {
                        list.add(pos);
                    }
                }
            }
        }
    }

    /**
     * @return whether the block lies in one of the colony's quarry zones where labour may dig it now (inside the zone's terraces, above its floor). The other checks
     *   (claim, buildings, vetoes, placed blocks, the block itself) are the caller's.
     */
    public boolean quarryAllows(final BlockPos pos)
    {
        final QuarryZones quarries = colony.getLabourData().quarries();
        final QuarryZones.Zone zone = quarries.at(pos);
        return zone != null && !quarries.pending(zone.id()) && QuarryZones.diggable(zone, pos, LabourSettings.quarryDepth());
    }

    /**
     * @return whether labour may dig the ground at the block: digging is allowed in the whole reach ({@link #groundAllowed}), or the block lies in a quarry zone's
     *   terraces ({@link #quarryAllows}).
     */
    public boolean groundAllowedAt(final BlockPos pos)
    {
        return groundAllowed() || quarryAllows(pos);
    }

    /**
     * @return whether some of the area lies in a quarry zone of the colony.
     */
    public boolean quarryIn(final DirectedOrder.Area area)
    {
        return colony.getLabourData().quarries().overlaps(area);
    }

    /**
     * @return whether the four sides of the block lie in loaded chunks (a look at them must not load one).
     */
    private static boolean sidesLoaded(final ServerLevel level, final BlockPos pos)
    {
        for (final net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL)
        {
            if (!loadedNow(level, pos.relative(direction)))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * The last look before automatic labour digs a top block: digging the ground is allowed there ({@link #groundAllowedAt}: everywhere by the server config, or in a
     * quarry zone's terraces, read now), the block is
     * {@link #safeNow}, it is still the very block chosen ({@code chosen}) and still a block labour digs, open above, on solid ground, with no fluid under or next to it.
     */
    public boolean surfaceSafeNow(final BlockPos pos, final net.minecraft.world.level.block.Block chosen)
    {
        final ServerLevel level = level();
        if (level == null || !groundAllowedAt(pos) || !safeNow(null, pos) || !sidesLoaded(level, pos))
        {
            return false;
        }
        final BlockState state = level.getBlockState(pos);
        return state.getBlock() == chosen && (LabourRules.surface(state) || LabourRules.stone(state)) && level.getBlockState(pos.above()).isAir()
                 && !level.getBlockState(pos.below()).isAir() && level.getFluidState(pos.below()).isEmpty() && !exposedToFluid(level, pos);
    }

    /**
     * Forgets a top block the fresh look refused.
     */
    public void dropSurface(final BlockPos pos)
    {
        for (final List<BlockPos> list : surface.values())
        {
            list.remove(pos);
        }
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
        final boolean everywhere = groundAllowed();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int bestLayer = Integer.MIN_VALUE;
        for (final BlockPos pos : list)
        {
            if (taken.contains(pos))
            {
                continue;
            }
            // (a quarry zone is taken off top down: its highest blocks first)
            final boolean quarry = quarryAllows(pos);
            if (!everywhere && !quarry)
            {
                continue;
            }
            final BlockState state = level.getBlockState(pos);
            if ((!LabourRules.surface(state) && !LabourRules.stone(state)) || LabourRules.dropOf(state) != drop || !level.getBlockState(pos.above()).isAir())
            {
                continue;
            }
            final int layer = quarry ? pos.getY() : Integer.MIN_VALUE;
            final double distance = pos.distSqr(from);
            if (layer > bestLayer || (layer == bestLayer && distance < bestDistance))
            {
                bestLayer = layer;
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
        final List<BlockPos> foundCutters = new ArrayList<>();
        final List<BlockPos> everyCutter = new ArrayList<>();
        scan(range, LabourSites::cutterBlock, pos -> {
            if (ownedBy(pos))
            {
                everyCutter.add(pos);
                if (usablePost(pos))
                {
                    foundCutters.add(pos);
                }
            }
        });
        tables = foundTables;
        furnaces = foundFurnaces;
        cutters = foundCutters;
        allTables = everyTable;
        allFurnaces = everyFurnace;
        allCutters = everyCutter;
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

    /**
     * @return whether the block is an Architect's Cutter.
     */
    public static boolean cutterBlock(final BlockState state)
    {
        return state.getBlock() instanceof com.ldtteam.domumornamentum.block.ArchitectsCutterBlock;
    }

    /**
     * @return every Architect's Cutter in the colony's claim within the post range.
     */
    public List<BlockPos> allCutters()
    {
        scanPosts();
        return allCutters;
    }

    public boolean hasCutter()
    {
        scanPosts();
        return !cutters.isEmpty();
    }

    /**
     * @return the cutter to use: preferred ones first, then the nearest; null if none is free.
     */
    @Nullable
    public BlockPos cutter(final BlockPos from, final Set<BlockPos> taken)
    {
        scanPosts();
        return pickPost(cutters, from, taken, LabourSites::cutterBlock);
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
        return pickPost(tables, from, taken, state -> state.is(Blocks.CRAFTING_TABLE));
    }

    @Nullable
    public BlockPos furnace(final BlockPos from, final Set<BlockPos> taken)
    {
        scanPosts();
        return pickPost(furnaces, from, taken, state -> state.is(Blocks.FURNACE));
    }

    @Nullable
    private BlockPos pickPost(final List<BlockPos> posts, final BlockPos from, final Set<BlockPos> taken, final Predicate<BlockState> block)
    {
        final ServerLevel level = level();
        BlockPos best = null;
        boolean bestPreferred = false;
        double bestDistance = Double.MAX_VALUE;
        for (final BlockPos pos : posts)
        {
            if (taken.contains(pos) || level == null || !simulated(level, pos) || !block.test(level.getBlockState(pos)))
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
        areas.remove(toolKey(orderId));
    }

    /**
     * @return the key of the trees of an order's area that his workers may fell for the tool of the order (kept apart from the order's own scan, which may be stone).
     */
    private static int toolKey(final int orderId)
    {
        return -1 - orderId;
    }

    private AreaScan scanArea(final int key, final DirectedOrder.Area area, final DirectedOrder.Resource resource, final int floor)
    {
        final long now = now();
        final AreaScan cached = areas.get(key);
        if (cached != null && now - cached.at() < AREA_CACHE_TICKS && now >= cached.at())
        {
            return cached;
        }
        final AreaScan scan = scanAreaNow(now, area, resource, floor);
        areas.put(key, scan);
        return scan;
    }

    private AreaScan scanAreaNow(final long now, final DirectedOrder.Area area, final DirectedOrder.Resource resource, final int floor)
    {
        final ServerLevel level = level();
        final List<TreeSite> trees = new ArrayList<>();
        final List<BlockPos> blocks = new ArrayList<>();
        // (the manager's area does not make its ground provably natural: a directed order digs only where digging the ground is allowed, like automatic labour: in the
        // whole reach by the server config, or in a quarry zone)
        final boolean digGround = groundAllowed();
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
                        final TreeSite tree = naturalTree(level, base, area, false);
                        if (tree != null)
                        {
                            seen.addAll(tree.logs());
                            trees.add(tree);
                        }
                        continue;
                    }
                    if ((!digGround && !quarryAllows(top)) || blocks.size() >= AREA_MAX_BLOCKS || !directedBlock(resource, state) || top.getY() < floor || makesPit(level, top))
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
        return new AreaScan(now, List.copyOf(trees), List.copyOf(blocks));
    }

    /**
     * The kinds of log of the natural trees standing wholly in the area, with the checks of a directed wood order: what a directed worker may fell to make the tool of his
     * order (the wider labour reach is never used for it).
     *
     * @param orderId the order, or a negative id before it exists (then the area is scanned once, not cached).
     * @param area    the order's area.
     * @return the log kinds.
     */
    public Set<Item> treeLogsIn(final int orderId, final DirectedOrder.Area area)
    {
        final AreaScan scan = orderId < 0 ? scanAreaNow(now(), area, DirectedOrder.Resource.WOOD, DirectedOrder.NO_FLOOR)
                                : scanArea(toolKey(orderId), area, DirectedOrder.Resource.WOOD, DirectedOrder.NO_FLOOR);
        final ServerLevel level = level();
        final Set<Item> logs = new HashSet<>();
        for (final TreeSite tree : scan.trees())
        {
            if (level != null && simulated(level, tree.base()) && LabourRules.log(level.getBlockState(tree.base())))
            {
                logs.add(tree.log());
            }
        }
        return logs;
    }

    /**
     * {@link #nearestTreeIn} for the tool of an order: the nearest natural tree of the log standing wholly in the area, still there, or null.
     */
    @Nullable
    public TreeSite nearestToolTreeIn(final int orderId, final DirectedOrder.Area area, final Item log, final BlockPos from, final Set<BlockPos> taken)
    {
        final TreeSite found = nearestTreeInScan(toolKey(orderId), area, log, from, taken);
        return found != null || !rescan(toolKey(orderId)) ? found : nearestTreeInScan(toolKey(orderId), area, log, from, taken);
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
        final TreeSite found = nearestTreeInScan(orderId, area, null, from, taken);
        return found != null || !rescan(orderId) ? found : nearestTreeInScan(orderId, area, null, from, taken);
    }

    /**
     * Nothing was found in a scan that is a second old or more: the area is scanned again once (blocks dug since the scan uncover the next ones).
     */
    private boolean rescan(final int key)
    {
        final AreaScan cached = areas.get(key);
        if (cached == null || now() - cached.at() < 20L)
        {
            return false;
        }
        areas.remove(key);
        return true;
    }

    @Nullable
    private TreeSite nearestTreeInScan(final int key, final DirectedOrder.Area area, @Nullable final Item log, final BlockPos from, final Set<BlockPos> taken)
    {
        final ServerLevel level = level();
        TreeSite best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final TreeSite tree : scanArea(key, area, DirectedOrder.Resource.WOOD, DirectedOrder.NO_FLOOR).trees())
        {
            if ((log != null && tree.log() != log) || taken.contains(tree.base()) || level == null || !simulated(level, tree.base()) || !LabourRules.log(level.getBlockState(tree.base())))
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
            if (taken.contains(pos) || !simulated(level, pos) || !groundAllowedAt(pos) || !directedBlock(resource, level.getBlockState(pos)) || !level.getBlockState(pos.above()).isAir())
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
     * @return the ground's top of every column of the area, row by row ({@code (z - minZ) * width + (x - minX)}): the top block, below any logs, leaves, plants and
     *   other blocks that are not ground (a tree standing in a quarry zone does not lift its column). A column in a chunk that is not loaded reads as the anchor's y.
     */
    public int[] columnTops(final DirectedOrder.Area area)
    {
        final ServerLevel level = level();
        final int[] tops = new int[area.width() * area.depth()];
        for (int z = area.minZ(); z <= area.maxZ(); z++)
        {
            for (int x = area.minX(); x <= area.maxX(); x++)
            {
                int y = anchor().getY();
                if (level != null && level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null)
                {
                    y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    for (int i = 0; i < 64 && y > level.getMinY(); i++)
                    {
                        final BlockState state = level.getBlockState(new BlockPos(x, y, z));
                        if (!state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES) && !state.canBeReplaced() && state.isSolid())
                        {
                            break;
                        }
                        y--;
                    }
                }
                tops[(z - area.minZ()) * area.width() + (x - area.minX())] = y;
            }
        }
        return tops;
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
     * Read-only, for status and UI: whether this colony's labour may dig the ground now ({@link LabourSettings#digUnprovenGround(IColony)}). When false, no sand, dirt,
     * gravel or stone is dug, automatic or directed: a request or order waiting for ground waits because of this safety policy, not because none lies there.
     */
    public boolean groundAllowed()
    {
        return LabourSettings.digUnprovenGround(colony);
    }

    // ------------------------------------------------------------------ the last look before a directed worker breaks a block

    /**
     * Whether a directed worker may still break the block now, though borders, buildings and vetoes may have changed since the area was scanned: it lies in the area,
     * in a loaded chunk (nothing is loaded to look) and in this colony's claim, and in no building's box (read from the buildings now, not the scan's cache), no
     * veto and not on the record of placed blocks ({@link #safeNow}). Called once per block, when it breaks.
     */
    public boolean directedSafe(final DirectedOrder.Area area, final BlockPos pos)
    {
        return area != null && safeNow(area, pos);
    }

    /**
     * {@link #directedSafe} for a top block of a directed order: digging the ground is still allowed there ({@link #groundAllowedAt}, read now), still the resource, open above, standing on solid ground above the floor, no fluid next to it and no
     * pit left by digging it.
     */
    public boolean directedSurfaceSafe(final DirectedOrder.Area area, final DirectedOrder.Resource resource, final int floor, final BlockPos pos)
    {
        if (!groundAllowedAt(pos) || !directedSafe(area, pos))
        {
            return false;
        }
        final ServerLevel level = level();
        // (makesPit before the fluid check: it refuses a neighbour in a chunk that is not loaded, which the fluid check would load)
        return pos.getY() >= floor && directedBlock(resource, level.getBlockState(pos)) && level.getBlockState(pos.above()).isAir() && !level.getBlockState(pos.below()).isAir()
                 && level.getFluidState(pos.below()).isEmpty() && !makesPit(level, pos) && !exposedToFluid(level, pos);
    }
}
