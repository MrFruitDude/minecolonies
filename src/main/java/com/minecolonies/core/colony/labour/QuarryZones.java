package com.minecolonies.core.colony.labour;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A colony's quarry zones: areas a manager marked where labour may dig the ground (sand, dirt, gravel, stone) even while
 * {@link LabourSettings#digUnprovenGround} is off. Marking the area is the manager's statement that its ground may go. Everything else still protects a block
 * inside a zone: the record of placed blocks, the buildings' boxes, the vetoes, the claim and the fresh look before each break ({@link LabourSites#safeNow}).
 * <p>
 * A zone is dug top down in terraces, measured per column from the top each column had when the zone was marked: a block {@code d} layers below its column's marked
 * top may only be taken {@code d} blocks or more inside the zone's edge, and never {@link LabourSettings#quarryDepth} layers or deeper below that top. A zone whose
 * marked tops differ by more than the depth is refused (a hole or a hillside would otherwise make the limit meaningless). So every layer is one step lower than the
 * last and a labourer can always walk out. Saved with the colony ({@link LabourData}); a zone belongs to one colony and is only ever read by its own labour. Server thread only.
 */
public final class QuarryZones
{
    /** Most zones of one colony. */
    public static final int MAX_ZONES = 6;
    /** Longest side of a zone. */
    public static final int MAX_SIDE  = 32;
    /** Shortest side of a zone. */
    public static final int MIN_SIDE  = 2;

    private static final String TAG_ZONES    = "zones";
    private static final String TAG_ID       = "id";
    private static final String TAG_AREA     = "area";
    private static final String TAG_SURFACE  = "surface";
    private static final String TAG_TOPS     = "tops";
    private static final String TAG_NEXT     = "next";
    private static final String TAG_REVISION = "revision";

    /**
     * One zone.
     *
     * @param id   its id in the colony.
     * @param area the marked rectangle.
     * @param tops the top block's y of every column when it was marked, row by row ({@code (z - minZ) * width + (x - minX)}).
     */
    public record Zone(int id, DirectedOrder.Area area, int[] tops)
    {
        /**
         * @return the marked top of the column (the column must lie in the area).
         */
        public int top(final int x, final int z)
        {
            return tops[(z - area.minZ()) * area.width() + (x - area.minX())];
        }

        /**
         * @return the highest marked top (where the overview draws the zone).
         */
        public int surface()
        {
            int max = Integer.MIN_VALUE;
            for (final int top : tops)
            {
                max = Math.max(max, top);
            }
            return max;
        }

        /**
         * @return the lowest y labour may dig in the column.
         */
        public int floor(final int x, final int z, final int depth)
        {
            return top(x, z) - Math.max(1, depth) + 1;
        }

        @Override
        public boolean equals(final Object o)
        {
            return o instanceof Zone other && other.id == id && other.area.equals(area) && java.util.Arrays.equals(other.tops, tops);
        }

        @Override
        public int hashCode()
        {
            return 31 * (31 * id + area.hashCode()) + java.util.Arrays.hashCode(tops);
        }
    }

    /** Why a zone was not marked or removed. */
    public enum Refusal
    {
        /** The area is too small or bigger than {@link #MAX_SIDE} a side. */
        BAD_AREA,
        /** Some of the area is not loaded or not part of this colony. */
        AREA_OUTSIDE,
        /** It overlaps another zone of the colony. */
        OVERLAP,
        /** The colony has {@link #MAX_ZONES} zones. */
        TOO_MANY,
        /** No zone of that id. */
        NO_ZONE,
        /** The marked tops of the zone's columns differ by more than the quarry depth. */
        UNEVEN
    }

    /** The answer of a mark: the zone, or why there is none. */
    public record Marked(@Nullable Zone zone, @Nullable Refusal refusal)
    {
    }

    private final List<Zone> zones = new ArrayList<>();
    private int  next = 1;
    private long revision;

    /**
     * @return the zones (a copy).
     */
    public List<Zone> zones()
    {
        return List.copyOf(zones);
    }

    /**
     * @return a number that changes with every change of the zones (what a remove must carry).
     */
    public long revision()
    {
        return revision;
    }

    /**
     * @return the zone whose rectangle holds the column, or null.
     */
    @Nullable
    public Zone at(final BlockPos pos)
    {
        for (final Zone zone : zones)
        {
            if (zone.area().contains(pos))
            {
                return zone;
            }
        }
        return null;
    }

    /**
     * @return whether a zone was ever marked (an old save without zones is written as before).
     */
    public boolean used()
    {
        return !zones.isEmpty() || next > 1;
    }

    /**
     * @return whether a zone's rectangle overlaps the area.
     */
    public boolean overlaps(final DirectedOrder.Area area)
    {
        for (final Zone zone : zones)
        {
            if (overlap(zone.area(), area))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean overlap(final DirectedOrder.Area a, final DirectedOrder.Area b)
    {
        return a.minX() <= b.maxX() && b.minX() <= a.maxX() && a.minZ() <= b.maxZ() && b.minZ() <= a.maxZ();
    }

    /**
     * The terrace rule (pure): whether labour may dig the block at {@code pos} of the zone: inside the rectangle, above the floor, and as many blocks inside the edge as
     * it lies layers below the zone's surface.
     */
    public static boolean diggable(final Zone zone, final BlockPos pos, final int depth)
    {
        final DirectedOrder.Area area = zone.area();
        if (!area.contains(pos) || pos.getY() < zone.floor(pos.getX(), pos.getZ(), depth))
        {
            return false;
        }
        final int inset = Math.max(0, zone.top(pos.getX(), pos.getZ()) - pos.getY());
        return pos.getX() >= area.minX() + inset && pos.getX() <= area.maxX() - inset && pos.getZ() >= area.minZ() + inset && pos.getZ() <= area.maxZ() - inset;
    }

    /**
     * @return whether the area's size is allowed for a zone.
     */
    public static boolean sizeAllowed(final DirectedOrder.Area area)
    {
        return area.width() >= MIN_SIDE && area.depth() >= MIN_SIDE && area.width() <= MAX_SIDE && area.depth() <= MAX_SIDE;
    }

    /**
     * Marks a zone whose loaded, owned area the caller checked; the size, overlap and count are checked here.
     */
    Marked add(final DirectedOrder.Area area, final int[] tops, final int depth)
    {
        if (!sizeAllowed(area) || tops.length != area.width() * area.depth())
        {
            return new Marked(null, Refusal.BAD_AREA);
        }
        if (spread(tops) > Math.max(1, depth))
        {
            return new Marked(null, Refusal.UNEVEN);
        }
        if (zones.size() >= MAX_ZONES)
        {
            return new Marked(null, Refusal.TOO_MANY);
        }
        if (overlaps(area))
        {
            return new Marked(null, Refusal.OVERLAP);
        }
        final Zone zone = new Zone(next++, area, tops.clone());
        zones.add(zone);
        revision++;
        return new Marked(zone, null);
    }

    /**
     * @return how far the highest and lowest top differ.
     */
    public static int spread(final int[] tops)
    {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (final int top : tops)
        {
            min = Math.min(min, top);
            max = Math.max(max, top);
        }
        return tops.length == 0 ? 0 : max - min;
    }

    /**
     * @return true if a zone of the id was removed.
     */
    boolean remove(final int id)
    {
        if (zones.removeIf(zone -> zone.id() == id))
        {
            revision++;
            return true;
        }
        return false;
    }

    @NotNull
    public CompoundTag write()
    {
        final CompoundTag tag = new CompoundTag();
        final ListTag list = new ListTag();
        for (final Zone zone : zones)
        {
            final CompoundTag item = new CompoundTag();
            item.putInt(TAG_ID, zone.id());
            item.putIntArray(TAG_AREA, new int[] {zone.area().minX(), zone.area().minZ(), zone.area().maxX(), zone.area().maxZ()});
            item.putIntArray(TAG_TOPS, zone.tops());
            list.add(item);
        }
        tag.put(TAG_ZONES, list);
        tag.putInt(TAG_NEXT, next);
        tag.putLong(TAG_REVISION, revision);
        return tag;
    }

    public void read(@NotNull final CompoundTag tag)
    {
        zones.clear();
        int highest = 0;
        final ListTag list = tag.getListOrEmpty(TAG_ZONES);
        for (int i = 0; i < list.size() && zones.size() < MAX_ZONES; i++)
        {
            final CompoundTag item = list.getCompoundOrEmpty(i);
            final int[] a = item.getIntArray(TAG_AREA).orElse(new int[0]);
            final int id = item.getIntOr(TAG_ID, 0);
            if (a.length != 4 || id <= 0)
            {
                continue;
            }
            final DirectedOrder.Area area = DirectedOrder.Area.of(a[0], a[1], a[2], a[3]);
            if (!sizeAllowed(area) || overlaps(area))
            {
                continue;
            }
            int[] tops = item.getIntArray(TAG_TOPS).orElse(new int[0]);
            if (tops.length != area.width() * area.depth())
            {
                // (a zone saved with one surface for all columns, before the tops were kept)
                tops = new int[area.width() * area.depth()];
                java.util.Arrays.fill(tops, item.getIntOr(TAG_SURFACE, 0));
            }
            zones.add(new Zone(id, area, tops));
            highest = Math.max(highest, id);
        }
        next = Math.max(highest + 1, tag.getIntOr(TAG_NEXT, 1));
        // (a client that saw the revision before the load must read again)
        revision = tag.getLongOr(TAG_REVISION, 0L) + 1;
    }
}
