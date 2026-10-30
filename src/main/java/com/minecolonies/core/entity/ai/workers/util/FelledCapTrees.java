package com.minecolonies.core.entity.ai.workers.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiPredicate;

/**
 * Forestry: the stems of huge mushrooms and fungi felled lately, per level and colony (r3 Fable N12, r4 Fable N33). Two of them standing close grow their
 * caps into one; the first one felled leaves its half held by the other stem. When the second goes, the joined cap reaches past its own box: a cap within
 * reach of a stem the same colony felled here counts as natural too, so the sweep takes the whole joined cap instead of leaving it floating.
 * <p>
 * A remembered box is no proof of who a cap belongs to (r4 Sol H1): each felled stem keeps the caps its Forester saw when he chose it, and a cap in its
 * reach is the next tree's only if it was one of them, as it was. A wart block or shroomlight added since is somebody's.
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

    /**
     * @param colony the colony whose Forester felled it.
     * @param caps   the caps he saw around it when he chose it, by position (empty when unknown: then none in its reach is proven natural).
     */
    private record Felled(int colony, AABB box, String family, long until, Map<Long, Block> caps)
    {
    }

    private FelledCapTrees()
    {
    }

    /**
     * A huge mushroom or fungus was felled.
     *
     * @param level  the level.
     * @param colony the colony whose Forester felled it.
     * @param box    the box of its stems.
     * @param family its family.
     * @param caps   the caps he saw when he chose it, or null if unknown.
     */
    public static void felled(@NotNull final Level level, final int colony, @NotNull final AABB box, @NotNull final String family, @Nullable final Map<Long, Block> caps)
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
            list.add(new Felled(colony, box, family, now + KEEP_TICKS, caps == null ? Map.of() : Map.copyOf(caps)));
        }
    }

    /**
     * The boxes, inflated by the cap's reach, a cap of the family may lie in and still be natural: this tree's own and those of the stems of its kind
     * the same colony felled here lately.
     *
     * @param level  the level.
     * @param colony the Forester's colony (none felled by another colony widens it).
     * @param logBox the box of this tree's logs.
     * @param family the tree's family.
     * @param reach  how far out of a stem's box a cap reaches.
     * @return the boxes.
     */
    @NotNull
    public static List<AABB> capBounds(@NotNull final Level level, final int colony, @NotNull final AABB logBox, @NotNull final String family, final double reach)
    {
        final List<AABB> out = new ArrayList<>();
        out.add(logBox.inflate(reach));
        for (final Felled f : near(level, colony, logBox, family, reach))
        {
            out.add(f.box.inflate(reach));
        }
        return out;
    }

    /**
     * Whether a cap found when a tree is chosen may be counted as natural (r4 Sol H1): outside the reach of every stem this colony felled near, it is a
     * fresh choice; inside one, it must be one of the caps that stem's Forester saw, as it was.
     *
     * @param level  the level.
     * @param colony the Forester's colony.
     * @param logBox the box of the new tree's logs.
     * @param family the tree's family.
     * @param reach  how far out of a stem's box a cap reaches.
     * @return the test for a cap and what is there now: false if it lies by a felled stem that never saw it so.
     */
    @NotNull
    public static BiPredicate<BlockPos, BlockState> provenNatural(
      @NotNull final Level level,
      final int colony,
      @NotNull final AABB logBox,
      @NotNull final String family,
      final double reach)
    {
        final List<Felled> near = near(level, colony, logBox, family, reach);
        if (near.isEmpty())
        {
            return (pos, state) -> true;
        }
        final List<AABB> boxes = new ArrayList<>();
        for (final Felled f : near)
        {
            boxes.add(f.box.inflate(reach));
        }
        return (pos, state) -> {
            boolean inReach = false;
            for (int i = 0; i < near.size(); i++)
            {
                if (!TreeFootprint.inAny(List.of(boxes.get(i)), pos))
                {
                    continue;
                }
                inReach = true;
                final Block seen = near.get(i).caps.get(pos.asLong());
                if (seen != null && state.is(seen))
                {
                    return true;
                }
            }
            return !inReach;
        };
    }

    /**
     * @return the stems of the family the colony felled here lately whose caps could reach this tree's: a joined cap spans at most two reaches and a
     * little.
     */
    @NotNull
    private static List<Felled> near(@NotNull final Level level, final int colony, @NotNull final AABB logBox, @NotNull final String family, final double reach)
    {
        final List<Felled> list = FELLED.get(level);
        if (list == null || colony < 0)
        {
            return List.of();
        }
        final long now = level.getGameTime();
        final AABB near = logBox.inflate(reach * 3);
        final List<Felled> out = new ArrayList<>();
        synchronized (list)
        {
            for (final Felled f : list)
            {
                if (f.until >= now && f.colony == colony && f.family.equals(family) && f.box.intersects(near))
                {
                    out.add(f);
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
