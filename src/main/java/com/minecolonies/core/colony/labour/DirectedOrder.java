package com.minecolonies.core.colony.labour;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A manager's directed gathering order (DIRECTED-WORKERS-PROPOSAL): chosen jobless citizens gather one basic resource inside one area of the colony and bring a
 * bounded amount to one building. The order is saved with the colony ({@link LabourData}); what a worker is doing right now is not (his session restarts).
 * <p>
 * The bookkeeping is the conservation rule: {@link #delivered} counts what was put into the destination, each worker's {@code carried} what he gathered for the
 * order and still holds, and {@code pending} the block he is breaking now. A worker may start on a block only while delivered + carried + pending of all workers
 * stays below the amount, so the order never gathers or hands in more than it asked for, whatever the number of workers. Pure data, server thread only.
 */
public final class DirectedOrder
{
    /** Most goods one order asks for. */
    public static final int MAX_AMOUNT = 256;
    /** Longest side of the area, in blocks. */
    public static final int MAX_SIDE = 48;
    /** Most workers on one order. */
    public static final int MAX_WORKERS = 8;
    /** How far below the area's lowest surface (when the order started) its workers may dig. */
    public static final int MAX_DEPTH = 3;
    /** No floor (a save made before floors, or an area with no loaded surface). */
    public static final int NO_FLOOR = Integer.MIN_VALUE;

    /** What the workers gather. */
    public enum Resource
    {
        WOOD,
        COBBLESTONE,
        DIRT,
        GRAVEL
    }

    public enum State
    {
        ACTIVE,
        COMPLETED,
        CANCELLED
    }

    /** Why an active order makes no progress now (shown with the order; cleared by the next progress). */
    public enum Why
    {
        NONE,
        /** Every worker left (got a job, moved away, died). */
        NO_WORKERS,
        /** Nothing of the resource is left in the area that may be taken (natural, loaded, not a building). */
        NO_SITE,
        /** The resource needs a pickaxe and the worker has none. */
        NO_TOOL,
        /** The destination is gone or no longer built. */
        NO_DESTINATION,
        /** The destination has no room. */
        DESTINATION_FULL,
        /** The worker's inventory is full of other things. */
        INVENTORY_FULL,
        /** Labour was switched off. */
        LABOUR_OFF,
        /** A specialist of the colony now makes the resource. */
        COVERED
    }

    /** The area in world x/z, inclusive. */
    public record Area(int minX, int minZ, int maxX, int maxZ)
    {
        public static Area of(final int x0, final int z0, final int x1, final int z1)
        {
            return new Area(Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1));
        }

        public boolean contains(final BlockPos pos)
        {
            return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        public int width()
        {
            return maxX - minX + 1;
        }

        public int depth()
        {
            return maxZ - minZ + 1;
        }

        public BlockPos center(final int y)
        {
            return new BlockPos((minX + maxX) >> 1, y, (minZ + maxZ) >> 1);
        }
    }

    /** One worker's share: what he gathered and still carries for the order (saved), and the block he breaks now (not saved). */
    static final class Assignment
    {
        int carried;
        int pending;
    }

    private final int                         id;
    private final Resource                    resource;
    private final int                         amount;
    private final Area                        area;
    @Nullable
    private final BlockPos                    destination;
    private final long                        created;
    private final int                         floor;
    private final Map<Integer, Assignment>    workers = new LinkedHashMap<>();
    private int                               delivered;
    private State                             state = State.ACTIVE;
    private Why                               why   = Why.NONE;
    private long                              finished;

    DirectedOrder(final int id, final Resource resource, final int amount, final Area area, @Nullable final BlockPos destination, final long created)
    {
        this(id, resource, amount, area, destination, created, NO_FLOOR);
    }

    /**
     * @param floor the lowest y the workers may dig at ({@link #NO_FLOOR} for none): an order never digs a pit into the ground.
     */
    DirectedOrder(final int id, final Resource resource, final int amount, final Area area, @Nullable final BlockPos destination, final long created, final int floor)
    {
        this.floor = floor;
        this.id = id;
        this.resource = resource;
        this.amount = Math.max(1, Math.min(MAX_AMOUNT, amount));
        this.area = area;
        this.destination = destination == null ? null : destination.immutable();
        this.created = created;
    }

    public int id()
    {
        return id;
    }

    public Resource resource()
    {
        return resource;
    }

    public int amount()
    {
        return amount;
    }

    public Area area()
    {
        return area;
    }

    /**
     * @return the building the manager chose, or null for the automatic choice (a warehouse, else the town hall).
     */
    @Nullable
    public BlockPos destination()
    {
        return destination;
    }

    public long created()
    {
        return created;
    }

    /**
     * @return the lowest y a worker may dig at, {@link #NO_FLOOR} for none.
     */
    public int floor()
    {
        return floor;
    }

    public int delivered()
    {
        return delivered;
    }

    public State state()
    {
        return state;
    }

    public boolean active()
    {
        return state == State.ACTIVE;
    }

    public Why why()
    {
        return why;
    }

    public long finished()
    {
        return finished;
    }

    /**
     * @return the workers by citizen id (a copy of the ids, in the order they were assigned).
     */
    public java.util.List<Integer> workers()
    {
        return new java.util.ArrayList<>(workers.keySet());
    }

    public boolean has(final int citizen)
    {
        return workers.containsKey(citizen);
    }

    /**
     * @return what the citizen gathered for the order and still carries.
     */
    public int carried(final int citizen)
    {
        final Assignment a = workers.get(citizen);
        return a == null ? 0 : a.carried;
    }

    /**
     * @return delivered, carried and pending together: what the order has already spoken for.
     */
    public int committed()
    {
        int n = delivered;
        for (final Assignment a : workers.values())
        {
            n += a.carried + a.pending;
        }
        return n;
    }

    /**
     * @return how many are still to be handed in.
     */
    public int remaining()
    {
        return Math.max(0, amount - delivered);
    }

    // ------------------------------------------------------------------ changes (package: the order book makes them)

    boolean assign(final int citizen)
    {
        if (!active() || workers.containsKey(citizen) || workers.size() >= MAX_WORKERS)
        {
            return false;
        }
        workers.put(citizen, new Assignment());
        return true;
    }

    /**
     * The citizen leaves the order; what he carries stays his (his inventory), the order forgets it.
     */
    boolean unassign(final int citizen)
    {
        return workers.remove(citizen) != null;
    }

    /**
     * The worker is about to break a block: the order speaks for one more unit if it can.
     *
     * @return whether he may (false: the order has all it needs in hand or delivered).
     */
    boolean reserve(final int citizen)
    {
        final Assignment a = workers.get(citizen);
        if (!active() || a == null)
        {
            return false;
        }
        if (a.pending > 0)
        {
            return true;
        }
        if (committed() >= amount)
        {
            return false;
        }
        a.pending = 1;
        return true;
    }

    /**
     * @return whether the order could speak for one more unit now (no change).
     */
    boolean canReserve(final int citizen)
    {
        final Assignment a = workers.get(citizen);
        return active() && a != null && (a.pending > 0 || committed() < amount);
    }

    /**
     * The block broke: {@code matched} of its drops are the resource. At most the reserved unit is counted; the rest is the worker's own surplus.
     *
     * @return how many were counted.
     */
    int gathered(final int citizen, final int matched)
    {
        final Assignment a = workers.get(citizen);
        if (a == null)
        {
            return 0;
        }
        final int counted = a.pending > 0 ? Math.min(a.pending, Math.max(0, matched)) : 0;
        a.pending = 0;
        a.carried += counted;
        return counted;
    }

    /**
     * The worker dropped the block he was breaking (a timeout, a stop): the reservation goes.
     */
    void unreserve(final int citizen)
    {
        final Assignment a = workers.get(citizen);
        if (a != null)
        {
            a.pending = 0;
        }
    }

    /**
     * The worker's inventory holds fewer than he carried for the order (he used them elsewhere): the order believes the inventory.
     */
    void clampCarried(final int citizen, final int held)
    {
        final Assignment a = workers.get(citizen);
        if (a != null && a.carried > held)
        {
            a.carried = Math.max(0, held);
        }
    }

    /**
     * {@code moved} were put into the destination by the worker.
     *
     * @return how many were counted (never more than he carried for the order, never past the amount).
     */
    int handedIn(final int citizen, final int moved)
    {
        final Assignment a = workers.get(citizen);
        if (a == null || moved <= 0)
        {
            return 0;
        }
        final int counted = Math.min(Math.min(moved, a.carried), remaining());
        a.carried -= counted;
        delivered += counted;
        if (delivered >= amount)
        {
            state = State.COMPLETED;
            why = Why.NONE;
        }
        return counted;
    }

    /**
     * @return how many the worker should hand in now: what he carries for the order, never past what is missing.
     */
    int toHandIn(final int citizen)
    {
        return Math.min(carried(citizen), remaining());
    }

    boolean blocked(final Why next)
    {
        if (why == next)
        {
            return false;
        }
        why = next;
        return true;
    }

    void finish(final State end, final long now)
    {
        state = end;
        finished = now;
        for (final Assignment a : workers.values())
        {
            a.pending = 0;
        }
        workers.clear();
    }

    // ------------------------------------------------------------------ saving

    private static final String TAG_ID = "id", TAG_RES = "res", TAG_AMOUNT = "amount", TAG_AREA = "area", TAG_DEST = "dest", TAG_CREATED = "created", TAG_DONE = "done",
      TAG_STATE = "state", TAG_WHY = "why", TAG_FINISHED = "finished", TAG_WORKERS = "workers", TAG_CITIZEN = "c", TAG_CARRIED = "n", TAG_FLOOR = "floor";

    CompoundTag write()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_ID, id);
        tag.putString(TAG_RES, resource.name());
        tag.putInt(TAG_AMOUNT, amount);
        tag.putIntArray(TAG_AREA, new int[] {area.minX(), area.minZ(), area.maxX(), area.maxZ()});
        if (destination != null)
        {
            tag.putLong(TAG_DEST, destination.asLong());
        }
        tag.putLong(TAG_CREATED, created);
        tag.putInt(TAG_FLOOR, floor);
        tag.putInt(TAG_DONE, delivered);
        tag.putString(TAG_STATE, state.name());
        tag.putString(TAG_WHY, why.name());
        tag.putLong(TAG_FINISHED, finished);
        final ListTag list = new ListTag();
        for (final Map.Entry<Integer, Assignment> entry : workers.entrySet())
        {
            final CompoundTag w = new CompoundTag();
            w.putInt(TAG_CITIZEN, entry.getKey());
            w.putInt(TAG_CARRIED, entry.getValue().carried);
            list.add(w);
        }
        tag.put(TAG_WORKERS, list);
        return tag;
    }

    /**
     * @return the order, or null when the tag is not one (an unknown resource of a newer version, a broken area).
     */
    @Nullable
    static DirectedOrder read(final CompoundTag tag)
    {
        final Resource resource;
        try
        {
            resource = Resource.valueOf(tag.getStringOr(TAG_RES, ""));
        }
        catch (final IllegalArgumentException e)
        {
            return null;
        }
        final int[] a = tag.getIntArray(TAG_AREA).orElse(new int[0]);
        if (a.length != 4)
        {
            return null;
        }
        final Area area = Area.of(a[0], a[1], a[2], a[3]);
        if (area.width() > MAX_SIDE || area.depth() > MAX_SIDE)
        {
            return null;
        }
        final BlockPos dest = tag.getLong(TAG_DEST).map(BlockPos::of).orElse(null);
        final DirectedOrder order = new DirectedOrder(tag.getIntOr(TAG_ID, 0), resource, tag.getIntOr(TAG_AMOUNT, 1), area, dest, tag.getLongOr(TAG_CREATED, 0L),
          tag.getIntOr(TAG_FLOOR, NO_FLOOR));
        order.delivered = Math.max(0, Math.min(order.amount, tag.getIntOr(TAG_DONE, 0)));
        order.state = enumOr(State.class, tag.getStringOr(TAG_STATE, ""), State.ACTIVE);
        order.why = enumOr(Why.class, tag.getStringOr(TAG_WHY, ""), Why.NONE);
        order.finished = tag.getLongOr(TAG_FINISHED, 0L);
        if (order.active())
        {
            final ListTag list = tag.getListOrEmpty(TAG_WORKERS);
            for (int i = 0; i < list.size() && order.workers.size() < MAX_WORKERS; i++)
            {
                final CompoundTag w = list.getCompoundOrEmpty(i);
                final Assignment assignment = new Assignment();
                assignment.carried = Math.max(0, Math.min(order.amount, w.getIntOr(TAG_CARRIED, 0)));
                order.workers.put(w.getIntOr(TAG_CITIZEN, -1), assignment);
            }
            order.workers.remove(-1);
        }
        return order;
    }

    private static <E extends Enum<E>> E enumOr(final Class<E> type, final String name, final E fallback)
    {
        try
        {
            return Enum.valueOf(type, name);
        }
        catch (final IllegalArgumentException e)
        {
            return fallback;
        }
    }

    /**
     * @return the workers' carried counts (tests).
     */
    Map<Integer, Integer> carriedByWorker()
    {
        final Map<Integer, Integer> map = new HashMap<>();
        workers.forEach((c, a) -> map.put(c, a.carried));
        return map;
    }
}
