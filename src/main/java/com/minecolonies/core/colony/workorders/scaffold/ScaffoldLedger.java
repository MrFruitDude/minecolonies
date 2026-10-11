package com.minecolonies.core.colony.workorders.scaffold;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The scaffolding blocks the builders of a work order have put up and not taken down yet: where each one is, which hut's builder placed it
 * (the lead or a helper who works on the order), and the stability distance it had when it was placed (to take down the arms from the
 * outside in). It lives in the NBT of the work order, so a save in the middle of a build keeps it, and the colony sweeps what is left of it
 * when the order ends, is released or is handed over.
 */
public final class ScaffoldLedger
{
    private static final String TAG_CELLS = "scaffolds";
    private static final String TAG_POS    = "p";
    private static final String TAG_HUT    = "h";
    private static final String TAG_DIST   = "d";

    /**
     * One scaffolding block.
     *
     * @param pos      where it is.
     * @param hut      the position of the hut of the builder who placed it.
     * @param distance the stability distance it had when it was placed.
     */
    public record Cell(BlockPos pos, BlockPos hut, int distance)
    {
    }

    private final Map<BlockPos, Cell> cells = new LinkedHashMap<>();
    private boolean dirty = false;
    private Runnable onChange = null;

    /**
     * The owner (the work order) hears about every change, so it is saved.
     */
    public void setOnChange(final Runnable onChange)
    {
        this.onChange = onChange;
    }

    private void changed()
    {
        dirty = true;
        if (onChange != null)
        {
            onChange.run();
        }
    }

    public boolean isEmpty()
    {
        return cells.isEmpty();
    }

    public int size()
    {
        return cells.size();
    }

    public boolean contains(final BlockPos pos)
    {
        return cells.containsKey(pos);
    }

    @Nullable
    public Cell get(final BlockPos pos)
    {
        return cells.get(pos);
    }

    public Collection<Cell> all()
    {
        return List.copyOf(cells.values());
    }

    /**
     * Records a block that was put up.
     */
    public void add(final BlockPos pos, final BlockPos hut, final int distance)
    {
        cells.put(pos.immutable(), new Cell(pos.immutable(), hut.immutable(), distance));
        changed();
    }

    /**
     * Records that a block is gone.
     *
     * @return true if it was in the ledger.
     */
    public boolean remove(final BlockPos pos)
    {
        final boolean removed = cells.remove(pos) != null;
        if (removed)
        {
            changed();
        }
        return removed;
    }

    /**
     * Moves everything of another ledger into this one (an order that ends hands what is left to the colony's backstop).
     */
    public void addAll(final ScaffoldLedger other)
    {
        for (final Cell cell : other.cells.values())
        {
            cells.put(cell.pos(), cell);
        }
        if (!other.cells.isEmpty())
        {
            changed();
        }
    }

    public void clear()
    {
        if (!cells.isEmpty())
        {
            changed();
        }
        cells.clear();
    }

    /**
     * The huts whose builders placed something that still stands.
     */
    public Set<BlockPos> owners()
    {
        final Set<BlockPos> owners = new LinkedHashSet<>();
        for (final Cell cell : cells.values())
        {
            owners.add(cell.hut());
        }
        return owners;
    }

    /**
     * The cells in the order they can be taken down without the rest losing its support: top down, and on one level the ones furthest from
     * the ground (the end of an arm) first.
     *
     * @param hut only the cells placed by the builders of this hut, or null for all.
     */
    public List<Cell> topDown(@Nullable final BlockPos hut)
    {
        final List<Cell> list = new ArrayList<>();
        for (final Cell cell : cells.values())
        {
            if (hut == null || hut.equals(cell.hut()))
            {
                list.add(cell);
            }
        }
        list.sort(Comparator.<Cell>comparingInt(c -> -c.pos().getY()).thenComparingInt(c -> -c.distance()));
        return list;
    }

    /**
     * Whether the ledger changed since the flag was reset (the work order is saved again).
     */
    public boolean consumeDirty()
    {
        final boolean was = dirty;
        dirty = false;
        return was;
    }

    public void write(final CompoundTag compound)
    {
        final ListTag list = new ListTag();
        for (final Cell cell : cells.values())
        {
            final CompoundTag tag = new CompoundTag();
            tag.putLong(TAG_POS, cell.pos().asLong());
            tag.putLong(TAG_HUT, cell.hut().asLong());
            tag.putInt(TAG_DIST, cell.distance());
            list.add(tag);
        }
        compound.put(TAG_CELLS, list);
    }

    public void read(final CompoundTag compound)
    {
        cells.clear();
        final ListTag list = compound.getListOrEmpty(TAG_CELLS);
        for (int i = 0; i < list.size(); i++)
        {
            final CompoundTag tag = list.getCompoundOrEmpty(i);
            final BlockPos pos = BlockPos.of(tag.getLongOr(TAG_POS, 0L));
            cells.put(pos, new Cell(pos, BlockPos.of(tag.getLongOr(TAG_HUT, 0L)), tag.getIntOr(TAG_DIST, 0)));
        }
    }
}
