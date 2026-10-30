package com.minecolonies.core.entity.ai.workers.util;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Forestry: the stems of huge mushrooms and fungi felled lately, per level (r3 Fable N12). Two of them standing close grow their caps into one; the first
 * one felled leaves its half held by the other stem. When the second goes, the joined cap reaches past its own box: a cap within reach of a stem felled
 * here counts as natural too, so the sweep takes the whole joined cap instead of leaving it floating.
 * <p>
 * In memory only, on the level's game time; an entry holds no level, so an unloaded level drops its entries. Server thread.
 */
public final class FelledCapTrees
{
    /**
     * How long a felled stem is remembered, in game ticks (three days).
     */
    private static final long KEEP_TICKS = 3 * 24000L;

    /**
     * At most this many felled stems per level; the oldest go first.
     */
    private static final int MAX_ENTRIES = 256;

    private static final Map<Level, List<Felled>> FELLED = Collections.synchronizedMap(new WeakHashMap<>());

    private record Felled(AABB box, String family, long until)
    {
    }

    private FelledCapTrees()
    {
    }

    /**
     * A huge mushroom or fungus was felled.
     *
     * @param level  the level.
     * @param box    the box of its stems.
     * @param family its family.
     */
    public static void felled(@NotNull final Level level, @NotNull final AABB box, @NotNull final String family)
    {
        final long now = level.getGameTime();
        final List<Felled> list = FELLED.computeIfAbsent(level, l -> new ArrayList<>());
        synchronized (list)
        {
            list.removeIf(f -> f.until < now);
            if (list.size() >= MAX_ENTRIES)
            {
                list.remove(0);
            }
            list.add(new Felled(box, family, now + KEEP_TICKS));
        }
    }

    /**
     * The boxes, inflated by the cap's reach, a cap of the family may lie in and still be natural: this tree's own and those of the stems of its kind
     * felled here lately.
     *
     * @param level  the level.
     * @param logBox the box of this tree's logs.
     * @param family the tree's family.
     * @param reach  how far out of a stem's box a cap reaches.
     * @return the boxes.
     */
    @NotNull
    public static List<AABB> capBounds(@NotNull final Level level, @NotNull final AABB logBox, @NotNull final String family, final double reach)
    {
        final List<AABB> out = new ArrayList<>();
        out.add(logBox.inflate(reach));
        final List<Felled> list = FELLED.get(level);
        if (list == null)
        {
            return out;
        }
        final long now = level.getGameTime();
        // only stems whose caps could reach this tree's: a joined cap spans at most two reaches and a little
        final AABB near = logBox.inflate(reach * 3);
        synchronized (list)
        {
            for (final Felled f : list)
            {
                if (f.until >= now && f.family.equals(family) && f.box.intersects(near))
                {
                    out.add(f.box.inflate(reach));
                }
            }
        }
        return out;
    }

    /**
     * Forget everything (tests).
     *
     * @param level the level.
     */
    public static void clear(@NotNull final Level level)
    {
        FELLED.remove(level);
    }
}
