package com.minecolonies.core.colony.labour;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.idle.IIdleTaskSession;
import com.minecolonies.api.colony.idle.IdleStopReason;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.idle.IdleCitizenArbiter;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.ldtteam.structurize.api.compat.itemhandler.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The colony's book of directed gathering orders (DIRECTED-WORKERS-PROPOSAL): a manager picks jobless citizens, a resource, an amount and an area; the server checks
 * everything ({@link #start}) and the citizens then work the order through {@link DirectedTask} and {@link DirectedSession}, under the same caps, coverage, claims and
 * stop rules as the other labour. Cancel gives the citizens back to the automatic labour; what they carry stays theirs. The orders are saved with the colony
 * ({@link LabourData}). Server thread only.
 */
public final class DirectedOrders
{
    /** Most active orders of one colony. */
    public static final int MAX_ACTIVE = 8;
    /** Finished orders kept to be shown. */
    public static final int MAX_FINISHED = 6;
    /** Ticks a finished order is shown. */
    public static final long KEEP_FINISHED_TICKS = 24000L;

    /** Why an order was not accepted. */
    public enum Refusal
    {
        /** Labour is switched off (server or colony). */
        LABOUR_OFF,
        /** A citizen id is not a citizen of this colony. */
        NO_CITIZEN,
        /** A citizen is a child, has a job, or is already on an order (or no citizen was chosen, or one twice). */
        NOT_ELIGIBLE,
        /** The amount is out of range. */
        BAD_AMOUNT,
        /** The area is empty or bigger than {@link DirectedOrder#MAX_SIDE} a side. */
        BAD_AREA,
        /** Some of the area is not loaded or not part of this colony. */
        AREA_OUTSIDE,
        /** A specialist of the colony makes the resource. */
        COVERED,
        /** The resource needs a pickaxe that a chosen citizen does not carry. */
        NO_TOOL,
        /** The chosen building is not a built building of this colony, or the colony has neither a built warehouse nor a built town hall. */
        NO_DESTINATION,
        /** The destination has no room. */
        DESTINATION_FULL,
        /** More labourers than the colony's labour caps allow. */
        CAP,
        /** Too many orders, or too many workers on one. */
        TOO_MANY
    }

    /** The answer of {@link #start}: the order, or why there is none. */
    public record Start(@Nullable DirectedOrder order, @Nullable Refusal refusal)
    {
    }

    private final Colony      colony;
    private final LabourBoard board;
    /** Citizens who wait before their next try, after a block (no tool, a full destination, nothing left), by citizen id. Runtime only. */
    private final Map<Integer, Long> notBefore = new HashMap<>();

    DirectedOrders(final Colony colony, final LabourBoard board)
    {
        this.colony = colony;
        this.board = board;
    }

    private List<DirectedOrder> list()
    {
        return colony.getLabourData().directed();
    }

    private long now()
    {
        return colony.getWorld() == null ? 0L : colony.getWorld().getGameTime();
    }

    /**
     * @return the orders, active first, then the finished ones, newest first (a copy).
     */
    public List<DirectedOrder> orders()
    {
        final List<DirectedOrder> copy = new ArrayList<>(list());
        copy.sort(Comparator.comparing((DirectedOrder o) -> !o.active()).thenComparing(Comparator.comparingInt(DirectedOrder::id).reversed()));
        return copy;
    }

    @Nullable
    public DirectedOrder order(final int id)
    {
        for (final DirectedOrder order : list())
        {
            if (order.id() == id)
            {
                return order;
            }
        }
        return null;
    }

    /**
     * @return the active order the citizen works on, or null.
     */
    @Nullable
    public DirectedOrder orderOf(final int citizen)
    {
        for (final DirectedOrder order : list())
        {
            if (order.active() && order.has(citizen))
            {
                return order;
            }
        }
        return null;
    }

    /**
     * @return how many citizens are on active orders.
     */
    public int assigned()
    {
        int n = 0;
        for (final DirectedOrder order : list())
        {
            n += order.active() ? order.workers().size() : 0;
        }
        return n;
    }

    /**
     * @return the number a cancel must carry: it changes when an order starts, ends, or loses a worker.
     */
    public long revision()
    {
        return colony.getLabourData().directedRevision();
    }

    // ------------------------------------------------------------------ the resources

    /**
     * @return what counts as the resource when it is handed in: any log for wood, else the one item.
     */
    public static Predicate<ItemStack> matcher(final DirectedOrder.Resource resource)
    {
        return switch (resource)
        {
            case WOOD -> stack -> stack.is(ItemTags.LOGS);
            case COBBLESTONE -> stack -> stack.is(Items.COBBLESTONE);
            case DIRT -> stack -> stack.is(Items.DIRT);
            case GRAVEL -> stack -> stack.is(Items.GRAVEL);
        };
    }

    /**
     * @return the item that names the resource (and that the specialist coverage is asked about).
     */
    public static Item shown(final DirectedOrder.Resource resource)
    {
        return switch (resource)
        {
            case WOOD -> Items.OAK_LOG;
            case COBBLESTONE -> Items.COBBLESTONE;
            case DIRT -> Items.DIRT;
            case GRAVEL -> Items.GRAVEL;
        };
    }

    /**
     * @return the tool kind that makes the work faster ({@code pickaxe} is needed for stone, the others are optional).
     */
    public static String tool(final DirectedOrder.Resource resource)
    {
        return switch (resource)
        {
            case WOOD -> "axe";
            case COBBLESTONE -> "pickaxe";
            default -> "shovel";
        };
    }

    /**
     * Read-only, for status and UI: whether the order digs the ground (not wood) while digging the ground is off ({@link LabourSites#groundAllowed}). Such an order
     * waits with {@link DirectedOrder.Why#NO_SITE} because of the safety policy, not because the area holds none of its resource.
     */
    public boolean groundOff(final DirectedOrder order)
    {
        return groundOff(order.resource());
    }

    /**
     * @return {@link #groundOff(DirectedOrder)} for an order of the resource (the order page can warn before the area is chosen).
     */
    public boolean groundOff(final DirectedOrder.Resource resource)
    {
        return resource != DirectedOrder.Resource.WOOD && !board.sites().groundAllowed();
    }

    public static boolean needsTool(final DirectedOrder.Resource resource)
    {
        return resource == DirectedOrder.Resource.COBBLESTONE;
    }

    /**
     * @return whether a specialist of the colony makes the resource (labour leaves it to him).
     */
    public boolean covered(final DirectedOrder.Resource resource)
    {
        return board.coverage().covered(shown(resource));
    }

    /**
     * A hint before the area is chosen (the order page's chip): whether the citizen carries a tool of the kind or could get one: a free one lies in a warehouse, or labour
     * can make the basic one within its usual reach. A start checks the chosen area itself ({@link #canEquip(ICitizenData, String, DirectedOrder.Area)}).
     */
    public boolean canEquip(final ICitizenData citizen, final String tool)
    {
        if (carries(citizen, tool) || LabourSession.stockedTool(board, tool) != null)
        {
            return true;
        }
        final LabourPlanner.Result<net.minecraft.world.item.Item> plan = LabourPlanner.next(new LabourEnv(colony, board, true), LabourRules.toolItem(tool), 1,
          LabourSession.inventoryOf(citizen));
        return !(plan instanceof LabourPlanner.Blocked<?>);
    }

    /**
     * @return whether the citizen carries a tool of the kind or can get one for an order in the area before he works: a free one lies in a warehouse, or labour can
     *   make the basic one from what he holds, what the warehouses give for covered goods and the natural trees standing wholly in the area (nothing outside it).
     */
    public boolean canEquip(final ICitizenData citizen, final String tool, final DirectedOrder.Area area)
    {
        if (carries(citizen, tool) || LabourSession.stockedTool(board, tool) != null)
        {
            return true;
        }
        final LabourPlanner.Result<net.minecraft.world.item.Item> plan = LabourPlanner.next(LabourSession.toolEnv(colony, board, -1, area), LabourRules.toolItem(tool), 1,
          LabourSession.inventoryOf(citizen));
        return !(plan instanceof LabourPlanner.Blocked<?>);
    }

    /**
     * @return whether the citizen carries a tool of the kind.
     */
    public static boolean carries(final ICitizenData citizen, final String tool)
    {
        final var inventory = citizen.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && LabourRules.isTool(tool, stack.getItem()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether the citizen may be given an order: an adult of this colony without a job.
     */
    public static boolean eligible(@Nullable final ICitizenData citizen)
    {
        return citizen != null && !citizen.isChild() && citizen.getJob() == null;
    }

    // ------------------------------------------------------------------ the destination

    /**
     * @return where the order's goods go: the building the manager chose while it stands and is built; else the warehouse nearest to the area; else the town hall; null
     *   when there is none.
     */
    @Nullable
    public IBuilding destinationOf(final DirectedOrder order)
    {
        return destination(order.destination(), order.area());
    }

    @Nullable
    private IBuilding destination(@Nullable final BlockPos chosen, final DirectedOrder.Area area)
    {
        if (chosen != null)
        {
            final IBuilding building = colony.getServerBuildingManager().getBuilding(chosen);
            return building != null && building.getBuildingLevel() > 0 ? building : null;
        }
        final BlockPos center = area.center(colony.getCenter().getY());
        IWareHouse best = null;
        for (final IWareHouse wareHouse : board.sites().warehouses())
        {
            if (best == null || wareHouse.getID().distSqr(center) < best.getID().distSqr(center))
            {
                best = wareHouse;
            }
        }
        if (best != null)
        {
            return best;
        }
        final IBuilding hall = colony.getServerBuildingManager().getTownHall();
        return hall != null && hall.getBuildingLevel() > 0 ? hall : null;
    }

    /**
     * @return whether the handler can take at least one more of the resource.
     */
    public static boolean hasRoom(final IItemHandler handler, final Predicate<ItemStack> matcher)
    {
        for (int slot = 0; slot < handler.getSlots(); slot++)
        {
            final ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty() || (matcher.test(stack) && stack.getCount() < Math.min(stack.getMaxStackSize(), handler.getSlotLimit(slot))))
            {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the manager's controls

    /**
     * Starts an order after checking everything: labour is on, the citizens are adults of this colony without a job and on no other order, the amount and the area are
     * in bounds, the whole area is loaded and in the colony, no specialist makes the resource, stone workers carry a pickaxe, the destination stands and has room, and the
     * colony's labour caps leave room for the workers. Nothing changes when it is refused.
     *
     * @param citizens    the chosen citizens' ids.
     * @param resource    what to gather.
     * @param amount      how many to hand in.
     * @param area        where.
     * @param destination the building to bring the goods to, null for the automatic choice.
     */
    public Start start(final List<Integer> citizens, final DirectedOrder.Resource resource, final int amount, final DirectedOrder.Area area, @Nullable final BlockPos destination)
    {
        if (!LabourSettings.enabled(colony))
        {
            return refuse(Refusal.LABOUR_OFF);
        }
        if (amount < 1 || amount > DirectedOrder.MAX_AMOUNT)
        {
            return refuse(Refusal.BAD_AMOUNT);
        }
        if (area.width() > DirectedOrder.MAX_SIDE || area.depth() > DirectedOrder.MAX_SIDE || area.width() < 1 || area.depth() < 1)
        {
            return refuse(Refusal.BAD_AREA);
        }
        int active = 0;
        for (final DirectedOrder order : list())
        {
            active += order.active() ? 1 : 0;
        }
        if (active >= MAX_ACTIVE || citizens.size() > DirectedOrder.MAX_WORKERS)
        {
            return refuse(Refusal.TOO_MANY);
        }
        final Set<Integer> unique = new HashSet<>(citizens);
        if (citizens.isEmpty() || unique.size() != citizens.size())
        {
            return refuse(Refusal.NOT_ELIGIBLE);
        }
        final List<ICitizenData> chosen = new ArrayList<>();
        for (final int id : citizens)
        {
            final ICitizenData citizen = colony.getCitizenManager().getCivilian(id);
            if (citizen == null || citizen.getColony() == null || citizen.getColony().getID() != colony.getID())
            {
                return refuse(Refusal.NO_CITIZEN);
            }
            if (!eligible(citizen) || orderOf(id) != null)
            {
                return refuse(Refusal.NOT_ELIGIBLE);
            }
            chosen.add(citizen);
        }
        if (!board.sites().areaUsable(area))
        {
            return refuse(Refusal.AREA_OUTSIDE);
        }
        if (covered(resource))
        {
            return refuse(Refusal.COVERED);
        }
        if (needsTool(resource))
        {
            for (final ICitizenData citizen : chosen)
            {
                if (!canEquip(citizen, tool(resource), area))
                {
                    return refuse(Refusal.NO_TOOL);
                }
            }
        }
        final IBuilding target = destination(destination, area);
        if (target == null)
        {
            return refuse(Refusal.NO_DESTINATION);
        }
        if (!hasRoom(target.getItemHandlerCap(), matcher(resource)))
        {
            return refuse(Refusal.DESTINATION_FULL);
        }
        if (assigned() + chosen.size() > board.allowed())
        {
            return refuse(Refusal.CAP);
        }
        final DirectedOrder order = new DirectedOrder(colony.getLabourData().takeOrderId(), resource, amount, area, destination, now(),
          resource == DirectedOrder.Resource.WOOD ? DirectedOrder.NO_FLOOR : board.sites().lowestSurface(area) - DirectedOrder.MAX_DEPTH);
        for (final ICitizenData citizen : chosen)
        {
            order.assign(citizen.getId());
            notBefore.remove(citizen.getId());
            // the citizen leaves what he did on his own (an automatic labour claim, a builder he helped): the manager's order comes first
            final IIdleTaskSession session = IdleCitizenArbiter.sessionOf(citizen);
            if (session != null && !DirectedTask.ID.equals(session.getTaskId()))
            {
                IdleCitizenArbiter.stop(colony, citizen.getId(), IdleStopReason.SETTING_OFF);
            }
        }
        list().add(order);
        LabourStats.log("directed order {} started: {}x {} in {} by {}", order.id(), amount, resource, area, citizens);
        changed(true);
        return new Start(order, null);
    }

    private static Start refuse(final Refusal refusal)
    {
        return new Start(null, refusal);
    }

    /**
     * Cancels an active order: its workers go back to the automatic labour at once and keep what they carry; their claims and reserved blocks are given back.
     *
     * @return false when no active order has the id.
     */
    public boolean cancel(final int id)
    {
        final DirectedOrder order = order(id);
        if (order == null || !order.active())
        {
            return false;
        }
        final List<Integer> workers = order.workers();
        order.finish(DirectedOrder.State.CANCELLED, now());
        stopSessions(workers);
        board.sites().forgetArea(order.id());
        LabourStats.log("directed order {} cancelled with {} of {} delivered", order.id(), order.delivered(), order.amount());
        changed(true);
        return true;
    }

    /**
     * The last goods were handed in.
     */
    void completed(final DirectedOrder order)
    {
        final List<Integer> workers = order.workers();
        order.finish(DirectedOrder.State.COMPLETED, now());
        stopSessions(workers);
        board.sites().forgetArea(order.id());
        LabourStats.log("directed order {} completed: {}x {}", order.id(), order.delivered(), order.resource());
        changed(true);
    }

    private void stopSessions(final List<Integer> workers)
    {
        for (final int citizen : workers)
        {
            final IIdleTaskSession session = IdleCitizenArbiter.sessionOf(colony, citizen);
            if (session != null && DirectedTask.ID.equals(session.getTaskId()))
            {
                IdleCitizenArbiter.stop(colony, citizen, IdleStopReason.TASK_INVALID);
            }
        }
    }

    /**
     * A worker left his order for good (he got a job): the order forgets him and what he carried for it.
     */
    void left(final DirectedOrder order, final int citizen)
    {
        if (order.unassign(citizen))
        {
            notBefore.remove(citizen);
            if (order.workers().isEmpty())
            {
                order.blocked(DirectedOrder.Why.NO_WORKERS);
            }
            changed(true);
        }
    }

    /**
     * A worker could not go on (no tool, a full destination, nothing left in the area): the order shows why, and he waits before his next try.
     */
    void blocked(final DirectedOrder order, final int citizen, final DirectedOrder.Why why, final long waitTicks)
    {
        notBefore.put(citizen, now() + waitTicks);
        if (order.blocked(why))
        {
            changed(false);
        }
    }

    /**
     * @return whether the citizen waits after a block.
     */
    boolean waiting(final int citizen)
    {
        return now() < notBefore.getOrDefault(citizen, 0L);
    }

    /**
     * The order made progress: a reason it showed goes.
     */
    void progressed(final DirectedOrder order)
    {
        order.blocked(DirectedOrder.Why.NONE);
        changed(false);
    }

    /**
     * Something of the orders changed: the colony is saved; a change of structure (an order started or ended, a worker left) also changes the revision.
     */
    void changed(final boolean structure)
    {
        if (structure)
        {
            colony.getLabourData().bumpDirected();
        }
        colony.markDirty();
    }

    // ------------------------------------------------------------------ the once a second tick

    /**
     * Once a second: workers who got a job, grew away or are gone leave their order; an order without workers or with labour switched off shows why; finished orders
     * are forgotten after a while.
     */
    void tick()
    {
        final List<DirectedOrder> orders = list();
        if (orders.isEmpty())
        {
            return;
        }
        final long now = now();
        final boolean enabled = LabourSettings.enabled(colony);
        for (final DirectedOrder order : orders)
        {
            if (!order.active())
            {
                continue;
            }
            for (final int citizen : order.workers())
            {
                if (!eligible(colony.getCitizenManager().getCivilian(citizen)))
                {
                    left(order, citizen);
                }
            }
            if (order.workers().isEmpty())
            {
                if (order.blocked(DirectedOrder.Why.NO_WORKERS))
                {
                    changed(false);
                }
            }
            else if (!enabled)
            {
                if (order.blocked(DirectedOrder.Why.LABOUR_OFF))
                {
                    changed(false);
                }
            }
            else if (order.why() == DirectedOrder.Why.LABOUR_OFF)
            {
                progressed(order);
            }
        }
        final List<DirectedOrder> finished = new ArrayList<>();
        for (final DirectedOrder order : orders)
        {
            if (!order.active())
            {
                finished.add(order);
            }
        }
        finished.sort(Comparator.comparingLong(DirectedOrder::finished).reversed());
        boolean removed = false;
        for (int i = 0; i < finished.size(); i++)
        {
            final DirectedOrder order = finished.get(i);
            if (i >= MAX_FINISHED || now - order.finished() > KEEP_FINISHED_TICKS || now < order.finished())
            {
                orders.remove(order);
                board.sites().forgetArea(order.id());
                removed = true;
            }
        }
        if (removed)
        {
            changed(true);
        }
        notBefore.values().removeIf(until -> until < now);
    }
}
