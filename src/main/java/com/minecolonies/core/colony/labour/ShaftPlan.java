package com.minecolonies.core.colony.labour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The plan of one labour shaft (pure, no world access): a spiral stair well round a stone pillar, and straight galleries at its landings. Everything is a function of
 * the entrance (the pillar's column and the top block's y there), the rotation, the laps and the gallery length, so any worker of the colony finds the same next block.
 * <pre>
 *   Top view (rotation 0)     cell k of lap L stands on y = top - 1 - k - 8L and is mined 3 high above it;
 *     0 1 2                   one step down per cell round the pillar P, so nobody falls more than a block
 *     7 P 3                   and no ladder is needed. After each lap (a landing) two galleries, 1 wide and
 *     6 5 4                   2 high, leave the side cells straight outwards, east and west, then north and
 *                             south at the next landing, so the galleries of two landings never meet.
 * </pre>
 * The dig order is the well lap by lap (each cell top block first), then the galleries, shallowest landing first, each block by block outwards. The plan is cut into
 * segments: the well is segment 0; the galleries of landing L are segments {@code 1 + 2L} and {@code 2 + 2L}. A gallery may only be dug once the well down to its landing
 * is done. Torches go on the pillar twice a lap and on a gallery's floor every {@link #GALLERY_TORCH} blocks.
 */
public final class ShaftPlan
{
    /** The ring round the pillar, in walking order, for rotation 0 (north-west corner first). */
    private static final int[][] RING = {{-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}};

    /** Blocks of the well per lap (8 cells, 3 high). */
    public static final int LAP_BLOCKS = 24;
    /** Depth of one lap. */
    public static final int LAP_DEPTH  = 8;
    /** A gallery torch every this many blocks. */
    public static final int GALLERY_TORCH = 8;

    /**
     * A torch spot: where it goes, the block it hangs on ({@code facing} away from it; {@code null} for a torch standing on the floor), and the first plan index that needs
     * it lit.
     */
    public record Torch(BlockPos pos, Direction facing, boolean wall, int neededFrom, int segment)
    {
    }

    private final BlockPos entrance;
    private final int      rotation;
    private final int      laps;
    private final int      galleryLength;

    private final List<BlockPos>      cells    = new ArrayList<>();
    private final List<Integer>       segments = new ArrayList<>();
    private final Map<BlockPos, Integer> index = new HashMap<>();
    private final int[]               segmentStart;
    private final int[]               segmentEnd;
    private final List<Torch>         torches  = new ArrayList<>();

    /**
     * @param entrance      the pillar's column, at the y of the top block there.
     * @param rotation      0 to 3 (a quarter turn each).
     * @param laps          the well's depth in laps of {@link #LAP_DEPTH}, at least 1.
     * @param galleryLength how long each gallery is, at least 0.
     */
    public ShaftPlan(final BlockPos entrance, final int rotation, final int laps, final int galleryLength)
    {
        this.entrance = entrance.immutable();
        this.rotation = Math.floorMod(rotation, 4);
        this.laps = Math.max(1, laps);
        this.galleryLength = Math.max(0, galleryLength);
        this.segmentStart = new int[segments()];
        this.segmentEnd = new int[segments()];
        build();
    }

    /**
     * @return how many segments a plan of {@code laps} laps has (the well and two galleries per landing).
     */
    public static int segments(final int laps)
    {
        return 1 + 2 * Math.max(1, laps);
    }

    public int segments()
    {
        return segments(laps);
    }

    public BlockPos entrance()
    {
        return entrance;
    }

    public int laps()
    {
        return laps;
    }

    public int galleryLength()
    {
        return galleryLength;
    }

    /**
     * @return the ring offset {x, z} of cell k (0 to 7) for this rotation.
     */
    public int[] ring(final int k)
    {
        return RING[(2 * rotation + Math.floorMod(k, 8)) % 8];
    }

    /**
     * @return the y of the block cell k of lap L stands on.
     */
    public int floor(final int lap, final int k)
    {
        return entrance.getY() - 1 - k - LAP_DEPTH * lap;
    }

    /**
     * @return the lowest floor of the well (what the last cell stands on).
     */
    public int bottom()
    {
        return floor(laps - 1, 7);
    }

    private void add(final BlockPos pos, final int segment)
    {
        if (index.putIfAbsent(pos, cells.size()) == null)
        {
            cells.add(pos);
            segments.add(segment);
        }
    }

    private void build()
    {
        final int px = entrance.getX();
        final int pz = entrance.getZ();
        segmentStart[0] = 0;
        for (int lap = 0; lap < laps; lap++)
        {
            for (int k = 0; k < 8; k++)
            {
                final int[] o = ring(k);
                final int f = floor(lap, k);
                for (int dy = 3; dy >= 1; dy--)
                {
                    add(new BlockPos(px + o[0], f + dy, pz + o[1]), 0);
                }
                if (k == 1 || k == 5)
                {
                    // half a lap: a torch on the pillar's side, head high, lit before the next lap is dug
                    torches.add(new Torch(new BlockPos(px + o[0], f + 2, pz + o[1]), horizontal(o[0], o[1]), true, LAP_BLOCKS * (lap + 1), 0));
                }
            }
        }
        segmentEnd[0] = cells.size();
        for (int lap = 0; lap < laps; lap++)
        {
            for (int side = 0; side < 2; side++)
            {
                final int segment = 1 + 2 * lap + side;
                // even landings leave the east and west side cells (3 and 7), odd ones the north and south ones (1 and 5)
                final int k = lap % 2 == 0 ? (side == 0 ? 3 : 7) : (side == 0 ? 1 : 5);
                final int[] o = ring(k);
                final int f = floor(lap, k);
                segmentStart[segment] = cells.size();
                for (int s = 1; s <= galleryLength; s++)
                {
                    final int x = px + o[0] * (1 + s);
                    final int z = pz + o[1] * (1 + s);
                    if (s % GALLERY_TORCH == 0 && s < galleryLength)
                    {
                        torches.add(new Torch(new BlockPos(x, f + 1, z), Direction.UP, false, cells.size() + 2, segment));
                    }
                    add(new BlockPos(x, f + 2, z), segment);
                    add(new BlockPos(x, f + 1, z), segment);
                }
                segmentEnd[segment] = cells.size();
            }
        }
    }

    /**
     * @return every plan block, in dig order.
     */
    public List<BlockPos> cells()
    {
        return cells;
    }

    public int size()
    {
        return cells.size();
    }

    public BlockPos cell(final int i)
    {
        return cells.get(i);
    }

    /**
     * @return the segment of plan index {@code i}.
     */
    public int segmentOf(final int i)
    {
        return segments.get(i);
    }

    /**
     * @return the first plan index of the segment.
     */
    public int start(final int segment)
    {
        return segmentStart[segment];
    }

    /**
     * @return one past the last plan index of the segment.
     */
    public int end(final int segment)
    {
        return segmentEnd[segment];
    }

    /**
     * @return the lap (landing) a gallery segment leaves from; -1 for the well.
     */
    public static int landingOf(final int segment)
    {
        return segment == 0 ? -1 : (segment - 1) / 2;
    }

    /**
     * @return the well's plan index a gallery may start from: every well block of its landing's lap and above is before it.
     */
    public int wellNeededFor(final int segment)
    {
        return segment == 0 ? 0 : LAP_BLOCKS * (landingOf(segment) + 1);
    }

    /**
     * @return the plan index of the block, or -1 when it is no block of the plan.
     */
    public int indexOf(final BlockPos pos)
    {
        final Integer i = index.get(pos);
        return i == null ? -1 : i;
    }

    public boolean contains(final BlockPos pos)
    {
        return index.containsKey(pos);
    }

    /**
     * @return the torch spots, well first.
     */
    public List<Torch> torches()
    {
        return torches;
    }

    /**
     * @return how far from the pillar the plan reaches horizontally.
     */
    public int reach()
    {
        return 1 + galleryLength;
    }

    /**
     * @return whether the column lies in the well's 5x5 (the ring and its shell).
     */
    public boolean inWellColumn(final int x, final int z)
    {
        return Math.abs(x - entrance.getX()) <= 2 && Math.abs(z - entrance.getZ()) <= 2;
    }

    /**
     * @return the outward direction of a gallery segment.
     */
    public Direction galleryDirection(final int segment)
    {
        final int lap = landingOf(segment);
        final int side = (segment - 1) % 2;
        final int k = lap % 2 == 0 ? (side == 0 ? 3 : 7) : (side == 0 ? 1 : 5);
        final int[] o = ring(k);
        return horizontal(o[0], o[1]);
    }

    /**
     * @return the horizontal direction of a unit offset.
     */
    static Direction horizontal(final int dx, final int dz)
    {
        return dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST : dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * @return how many blocks a full plan takes out (the blocks above the ground, already air, included).
     */
    public static int planBlocks(final int laps, final int galleryLength)
    {
        return LAP_BLOCKS * Math.max(1, laps) + 2 * Math.max(1, laps) * 2 * Math.max(0, galleryLength);
    }
}
