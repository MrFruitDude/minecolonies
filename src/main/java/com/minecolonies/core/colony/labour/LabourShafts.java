package com.minecolonies.core.colony.labour;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A colony's stone shafts: small mines its labourers dig for cobblestone (a spiral stair well and galleries, {@link ShaftPlan}). Labour opens one by itself when cobblestone is
 * wanted and nobody makes it, or a manager marks a spot; any labourer of the colony (and a builder stalled on his own build) continues the same plan, at most
 * {@link LabourSettings#shaftWorkers} at once. A shaft is never shared with another colony. Saved with the colony ({@link LabourData}); a save without shafts reads as
 * none. Server thread only.
 * <ul>
 *   <li>{@link State#OPEN}: dug while cobblestone is wanted;</li>
 *   <li>{@link State#FULL}: every block of the plan is dug or was left (a cave, water, another claim next to it); still counts toward {@link LabourSettings#shaftsMax};</li>
 *   <li>{@link State#CLOSED}: a manager closed it; nobody digs there and it does not count. A manager may open it again while the colony has room.</li>
 * </ul>
 */
public final class LabourShafts
{
    /** Most records kept (closed ones beyond this are forgotten, oldest first). */
    public static final int MAX_RECORDS = 16;
    /** Most ore faces remembered per shaft. */
    public static final int MAX_ORE_FACES = 4;
    /** Most ore kinds remembered per shaft (each told once). */
    public static final int MAX_ORE_KINDS = 8;

    private static final String TAG_SHAFTS   = "shafts";
    private static final String TAG_NEXT     = "next";
    private static final String TAG_REVISION = "revision";
    private static final String TAG_LAST     = "lastOpened";
    private static final String TAG_ID       = "id";
    private static final String TAG_POS      = "pos";
    private static final String TAG_ROTATION = "rotation";
    private static final String TAG_LAPS     = "laps";
    private static final String TAG_LENGTH   = "gallery";
    private static final String TAG_CURSORS  = "cursors";
    private static final String TAG_DONE     = "done";
    private static final String TAG_ENDED    = "ended";
    private static final String TAG_STATE    = "state";
    private static final String TAG_MARKED   = "marked";
    private static final String TAG_BY       = "openedBy";
    private static final String TAG_AT       = "openedAt";
    private static final String TAG_MINED    = "mined";
    private static final String TAG_FACES    = "oreFaces";
    private static final String TAG_ORES     = "ores";

    /** What a shaft is. */
    public enum State
    {
        OPEN,
        FULL,
        CLOSED
    }

    /** Why a shaft was not opened, closed or opened again. */
    public enum Refusal
    {
        /** The spot is not loaded, not where citizens work, or not this colony's claim. */
        OUTSIDE,
        /** Too close to a building, a road, a plot or a decoration. */
        NEAR_BUILDING,
        /** Water or lava near the opening. */
        NEAR_WATER,
        /** The ground there or below is not natural stone (a cave, a placed block, a chest, a tree over it). */
        NOT_NATURAL,
        /** The ground round the opening is not level. */
        UNEVEN,
        /** Too close to another shaft of the colony. */
        NEAR_SHAFT,
        /** Too close to a miner's hut. */
        NEAR_MINER,
        /** The colony has {@link LabourSettings#shaftsMax} shafts. */
        TOO_MANY,
        /** No shaft of that id. */
        NO_SHAFT,
        /** Shafts are off in this world. */
        OFF
    }

    /**
     * One shaft (the live record; {@link ShaftSites} moves its cursors and flags).
     */
    public static final class Shaft
    {
        public final int      id;
        public final BlockPos entrance;
        public final int      rotation;
        public final int      laps;
        public final int      galleryLength;
        /** Per segment: the first plan index that may still be undone (a hint; the world is read from here on). */
        final int[] cursors;
        /** Segments with nothing left to dig (dug out or left). */
        int done;
        /** Segments left before their end (a cave, water, a placed block or another claim next to them). */
        int ended;
        State state = State.OPEN;
        public final boolean marked;
        public final int     openedBy;
        public final long    openedAt;
        int mined;
        /** Where a gallery stopped at an ore its workers cannot take, with the ore's item id. */
        final List<OreFace> oreFaces = new ArrayList<>();
        /** The ore kinds found (item ids), each told once. */
        final Set<String> ores = new LinkedHashSet<>();
        @Nullable
        private ShaftPlan plan;

        Shaft(final int id, final BlockPos entrance, final int rotation, final int laps, final int galleryLength, final boolean marked, final int openedBy, final long openedAt)
        {
            this.id = id;
            this.entrance = entrance.immutable();
            this.rotation = rotation;
            this.laps = Math.max(1, laps);
            this.galleryLength = Math.max(0, galleryLength);
            this.marked = marked;
            this.openedBy = openedBy;
            this.openedAt = openedAt;
            this.cursors = new int[ShaftPlan.segments(this.laps)];
        }

        /**
         * @return its plan (built once).
         */
        public ShaftPlan plan()
        {
            if (plan == null)
            {
                plan = new ShaftPlan(entrance, rotation, laps, galleryLength);
                for (int s = 0; s < cursors.length; s++)
                {
                    cursors[s] = Math.max(plan.start(s), Math.min(plan.end(s), cursors[s]));
                }
            }
            return plan;
        }

        public State state()
        {
            return state;
        }

        /**
         * @return the segment's cursor: every plan block before it is dug (or was, when somebody filled it in since).
         */
        public int cursor(final int segment)
        {
            return cursors[segment];
        }

        public int mined()
        {
            return mined;
        }

        public boolean segmentDone(final int segment)
        {
            return (done & (1 << segment)) != 0;
        }

        public boolean segmentEnded(final int segment)
        {
            return (ended & (1 << segment)) != 0;
        }

        /**
         * @return how deep the well is dug now: from the entrance down to the floor of the deepest cell whose blocks are all past the well's cursor (0 before the first lap).
         */
        public int depth()
        {
            final ShaftPlan p = plan();
            final int cursor = segmentDone(0) ? p.end(0) : cursors[0];
            final int cellsDone = (cursor - p.start(0)) / 3;
            if (cellsDone <= 0)
            {
                return 0;
            }
            final int lap = (cellsDone - 1) / 8;
            final int k = (cellsDone - 1) % 8;
            return entrance.getY() - p.floor(lap, k) - 1;
        }

        public List<OreFace> oreFaces()
        {
            return List.copyOf(oreFaces);
        }

        public Set<String> ores()
        {
            return Set.copyOf(ores);
        }

        /**
         * @return whether every segment is done.
         */
        boolean allDone()
        {
            final int all = (1 << cursors.length) - 1;
            return (done & all) == all;
        }
    }

    /** An ore a gallery stopped at (the pickaxes labour may use cannot take it). */
    public record OreFace(BlockPos pos, String item)
    {
    }

    private final List<Shaft> shafts = new ArrayList<>();
    private int  next = 1;
    private long revision;
    /** When labour last opened a shaft by itself (game time). */
    private long lastOpened = Long.MIN_VALUE / 2;

    /**
     * @return the shafts (a copy of the list; the records are live).
     */
    public List<Shaft> shafts()
    {
        return List.copyOf(shafts);
    }

    /**
     * @return a number that changes with every change a manager sees (open, close, state, depth steps, ores).
     */
    public long revision()
    {
        return revision;
    }

    void bump()
    {
        revision++;
    }

    @Nullable
    public Shaft byId(final int id)
    {
        for (final Shaft shaft : shafts)
        {
            if (shaft.id == id)
            {
                return shaft;
            }
        }
        return null;
    }

    /**
     * @return how many shafts count toward the colony's limit (open and dug out; not closed).
     */
    public int counted()
    {
        int n = 0;
        for (final Shaft shaft : shafts)
        {
            if (shaft.state != State.CLOSED)
            {
                n++;
            }
        }
        return n;
    }

    /**
     * @return whether a shaft was ever opened (an old save without shafts is written as before).
     */
    public boolean used()
    {
        return !shafts.isEmpty() || next > 1;
    }

    public long lastOpened()
    {
        return lastOpened;
    }

    /**
     * The cooldown rule (pure): labour opens a shaft by itself at most once per {@code cooldown} ticks (or when the clock went back).
     */
    public static boolean cooledDown(final long last, final long now, final long cooldown)
    {
        return now - last >= cooldown || now < last;
    }

    /**
     * Records a shaft the caller surveyed. The count is checked here; the cooldown only for an automatic one.
     *
     * @return the shaft, or null when the colony has {@code max} shafts.
     */
    @Nullable
    Shaft open(final BlockPos entrance, final int rotation, final int laps, final int galleryLength, final boolean marked, final int openedBy, final long now, final int max,
      final int preEnded)
    {
        if (counted() >= max)
        {
            return null;
        }
        final Shaft shaft = new Shaft(next++, entrance, rotation, laps, galleryLength, marked, openedBy, now);
        shaft.ended = preEnded;
        shaft.done = preEnded;
        shafts.add(shaft);
        while (shafts.size() > MAX_RECORDS)
        {
            // (the oldest closed records make room; the limit keeps the others below MAX_RECORDS)
            final Shaft old = oldestClosed();
            if (old == null)
            {
                break;
            }
            shafts.remove(old);
        }
        if (!marked)
        {
            lastOpened = now;
        }
        revision++;
        return shaft;
    }

    @Nullable
    private Shaft oldestClosed()
    {
        for (final Shaft shaft : shafts)
        {
            if (shaft.state == State.CLOSED)
            {
                return shaft;
            }
        }
        return null;
    }

    /**
     * A manager closes a shaft: nobody digs there any more and it does not count toward the limit.
     *
     * @return null when closed, or why not.
     */
    @Nullable
    Refusal close(final int id)
    {
        final Shaft shaft = byId(id);
        if (shaft == null)
        {
            return Refusal.NO_SHAFT;
        }
        if (shaft.state != State.CLOSED)
        {
            shaft.state = State.CLOSED;
            revision++;
        }
        return null;
    }

    /**
     * A manager opens a closed shaft again (dug out stays dug out), when the colony has room.
     *
     * @return null when open, or why not.
     */
    @Nullable
    Refusal reopen(final int id, final int max)
    {
        final Shaft shaft = byId(id);
        if (shaft == null)
        {
            return Refusal.NO_SHAFT;
        }
        if (shaft.state != State.CLOSED)
        {
            return null;
        }
        if (counted() >= max)
        {
            return Refusal.TOO_MANY;
        }
        shaft.state = shaft.allDone() ? State.FULL : State.OPEN;
        revision++;
        return null;
    }

    /**
     * Marks a segment done (and ended when it was left before its end); a shaft whose segments are all done is dug out.
     *
     * @return true when the shaft became {@link State#FULL} now.
     */
    boolean segmentDone(final Shaft shaft, final int segment, final boolean endedEarly)
    {
        final int bit = 1 << segment;
        if ((shaft.done & bit) != 0)
        {
            return false;
        }
        shaft.done |= bit;
        if (endedEarly)
        {
            shaft.ended |= bit;
        }
        revision++;
        if (shaft.state == State.OPEN && shaft.allDone())
        {
            shaft.state = State.FULL;
            return true;
        }
        return false;
    }

    /**
     * @return true when the ore kind is new for the shaft (it is told once).
     */
    boolean foundOre(final Shaft shaft, final String item)
    {
        if (shaft.ores.size() >= MAX_ORE_KINDS || !shaft.ores.add(item))
        {
            return false;
        }
        revision++;
        return true;
    }

    void oreFace(final Shaft shaft, final BlockPos pos, final String item)
    {
        if (shaft.oreFaces.size() < MAX_ORE_FACES)
        {
            shaft.oreFaces.add(new OreFace(pos.immutable(), item));
            revision++;
        }
    }

    /**
     * A block of the shaft was dug: its count, and the panel's depth every full cell of the well.
     */
    void mined(final Shaft shaft)
    {
        shaft.mined++;
        if (shaft.mined % 3 == 0)
        {
            revision++;
        }
    }

    @NotNull
    public CompoundTag write()
    {
        final CompoundTag tag = new CompoundTag();
        final ListTag list = new ListTag();
        for (final Shaft shaft : shafts)
        {
            final CompoundTag item = new CompoundTag();
            item.putInt(TAG_ID, shaft.id);
            item.putLong(TAG_POS, shaft.entrance.asLong());
            item.putInt(TAG_ROTATION, shaft.rotation);
            item.putInt(TAG_LAPS, shaft.laps);
            item.putInt(TAG_LENGTH, shaft.galleryLength);
            item.putIntArray(TAG_CURSORS, shaft.cursors.clone());
            item.putInt(TAG_DONE, shaft.done);
            item.putInt(TAG_ENDED, shaft.ended);
            item.putString(TAG_STATE, shaft.state.name());
            item.putBoolean(TAG_MARKED, shaft.marked);
            item.putInt(TAG_BY, shaft.openedBy);
            item.putLong(TAG_AT, shaft.openedAt);
            item.putInt(TAG_MINED, shaft.mined);
            final ListTag faces = new ListTag();
            for (final OreFace face : shaft.oreFaces)
            {
                final CompoundTag f = new CompoundTag();
                f.putLong(TAG_POS, face.pos().asLong());
                f.putString(TAG_ORES, face.item());
                faces.add(f);
            }
            item.put(TAG_FACES, faces);
            final ListTag ores = new ListTag();
            for (final String ore : shaft.ores)
            {
                ores.add(StringTag.valueOf(ore));
            }
            item.put(TAG_ORES, ores);
            list.add(item);
        }
        tag.put(TAG_SHAFTS, list);
        tag.putInt(TAG_NEXT, next);
        tag.putLong(TAG_REVISION, revision);
        tag.putLong(TAG_LAST, lastOpened);
        return tag;
    }

    public void read(@NotNull final CompoundTag tag)
    {
        shafts.clear();
        int highest = 0;
        final ListTag list = tag.getListOrEmpty(TAG_SHAFTS);
        for (int i = 0; i < list.size() && shafts.size() < MAX_RECORDS; i++)
        {
            final CompoundTag item = list.getCompoundOrEmpty(i);
            final int id = item.getIntOr(TAG_ID, 0);
            if (id <= 0 || byId(id) != null)
            {
                continue;
            }
            final Shaft shaft = new Shaft(id, BlockPos.of(item.getLongOr(TAG_POS, 0L)), item.getIntOr(TAG_ROTATION, 0), item.getIntOr(TAG_LAPS, 1), item.getIntOr(TAG_LENGTH, 0),
              item.getBooleanOr(TAG_MARKED, false), item.getIntOr(TAG_BY, 0), item.getLongOr(TAG_AT, 0L));
            final int[] cursors = item.getIntArray(TAG_CURSORS).orElse(new int[0]);
            System.arraycopy(cursors, 0, shaft.cursors, 0, Math.min(cursors.length, shaft.cursors.length));
            shaft.done = item.getIntOr(TAG_DONE, 0);
            shaft.ended = item.getIntOr(TAG_ENDED, 0);
            try
            {
                shaft.state = State.valueOf(item.getStringOr(TAG_STATE, State.OPEN.name()));
            }
            catch (final IllegalArgumentException e)
            {
                // a state of a newer version: closed, so nothing is dug by mistake
                shaft.state = State.CLOSED;
            }
            shaft.mined = item.getIntOr(TAG_MINED, 0);
            final ListTag faces = item.getListOrEmpty(TAG_FACES);
            for (int f = 0; f < faces.size() && shaft.oreFaces.size() < MAX_ORE_FACES; f++)
            {
                final CompoundTag face = faces.getCompoundOrEmpty(f);
                shaft.oreFaces.add(new OreFace(BlockPos.of(face.getLongOr(TAG_POS, 0L)), face.getStringOr(TAG_ORES, "")));
            }
            final ListTag ores = item.getListOrEmpty(TAG_ORES);
            for (int o = 0; o < ores.size() && shaft.ores.size() < MAX_ORE_KINDS; o++)
            {
                ores.getString(o).ifPresent(shaft.ores::add);
            }
            shafts.add(shaft);
            highest = Math.max(highest, id);
        }
        next = Math.max(highest + 1, tag.getIntOr(TAG_NEXT, 1));
        lastOpened = tag.getLongOr(TAG_LAST, Long.MIN_VALUE / 2);
        // (a client that saw the revision before the load must read again)
        revision = tag.getLongOr(TAG_REVISION, 0L) + 1;
    }
}
