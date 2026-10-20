package com.minecolonies.core.colony.labour;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.idle.IIdleTaskSession;
import com.minecolonies.api.colony.idle.IdleStopReason;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import com.minecolonies.core.util.ItemMover;
import com.minecolonies.core.util.WorkerUtil;
import com.minecolonies.core.util.citizenutils.CitizenItemUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A jobless citizen working on a manager's directed order ({@link DirectedOrder}): he finds the nearest natural tree or top block of the resource inside the order's
 * area, walks there, fells or digs it with his own hands or tool (the real drops, the tool wears), and when he carries his lot (or the order needs no more) brings
 * the goods to the order's building. A block he cannot use makes him wait and the order say why; he never leaves the area for the resource, never digs a building,
 * and never takes more than the order still needs. After every finished action the claim's lease is renewed.
 */
public final class DirectedSession implements IIdleTaskSession
{
    /** What he does now. */
    public enum Phase
    {
        FIND, GATHER, DELIVER, WAIT
    }

    /** Most he gathers before he walks to hand in. */
    public static final int LOT = 32;

    private static final int  ACTION_TIMEOUT = 1800;
    private static final long WAIT_TICKS     = 200L;

    private final Colony       colony;
    private final ICitizenData citizen;
    private final LabourBoard  board;
    private final LabourClaim  claim;
    private final int          orderId;

    private Phase   phase = Phase.FIND;
    private long    nextStep;
    private long    actionStart;
    private int     strikes;
    private boolean ended;
    private boolean ok;
    private boolean stopped;

    private LabourSites.TreeSite tree;
    private List<BlockPos>       logsLeft;
    private BlockPos             block;
    private long                 workUntil;
    private long                 lastSwing;
    private final Set<BlockPos>  unreachable = new HashSet<>();

    public DirectedSession(final Colony colony, final ICitizenData citizen, final LabourBoard board, final LabourClaim claim)
    {
        this.colony = colony;
        this.citizen = citizen;
        this.board = board;
        this.claim = claim;
        this.orderId = claim.orderId();
        this.actionStart = colony.getWorld() == null ? 0L : colony.getWorld().getGameTime();
    }

    @Override
    public String getTaskId()
    {
        return DirectedTask.ID;
    }

    public LabourClaim claim()
    {
        return claim;
    }

    public int orderId()
    {
        return orderId;
    }

    public Phase phase()
    {
        return phase;
    }

    public String phaseName()
    {
        return phase.name().toLowerCase(Locale.ROOT);
    }

    private DirectedOrders orders()
    {
        return board.directed();
    }

    private DirectedOrder order()
    {
        final DirectedOrder order = orders().order(orderId);
        return order != null && order.active() && order.has(citizen.getId()) ? order : null;
    }

    @Override
    public boolean isValid()
    {
        return !ended && board.alive(claim) && LabourSettings.enabled(colony) && order() != null;
    }

    @Override
    public Component describe()
    {
        final DirectedOrder order = orders().order(orderId);
        final Component what = new ItemStack(DirectedOrders.shown(order == null ? DirectedOrder.Resource.WOOD : order.resource())).getHoverName();
        final String key = switch (phase)
        {
            case DELIVER -> "com.minecolonies.coremod.labour.task.directed.deliver";
            case WAIT -> "com.minecolonies.coremod.labour.task.directed.wait";
            default -> "com.minecolonies.coremod.labour.task.directed.gather";
        };
        return Component.translatable(key, order == null ? 0 : order.remaining(), what);
    }

    @Override
    public void stop(final IdleStopReason reason)
    {
        if (stopped)
        {
            return;
        }
        stopped = true;
        LabourStats.log("directed session of citizen {} stops: {} (phase {}, ended {}, ok {})", citizen.getId(), reason, phase, ended, ok);
        dropTarget();
        final DirectedOrder order = orders().order(orderId);
        if (order != null)
        {
            order.unreserve(citizen.getId());
            if (reason == IdleStopReason.GOT_JOB && order.active())
            {
                // a job ends the order for him; the goods he carries stay in his inventory
                orders().left(order, citizen.getId());
            }
        }
        board.release(claim, ok, false);
        final AbstractEntityCitizen worker = citizen.getEntity().orElse(null);
        if (worker != null && reason != IdleStopReason.CITIZEN_GONE)
        {
            worker.getNavigation().stop();
        }
    }

    private void dropTarget()
    {
        if (tree != null)
        {
            board.takenTrees().remove(tree.base());
            tree = null;
            logsLeft = null;
        }
        if (block != null)
        {
            board.takenSurface().remove(block);
            block = null;
        }
        workUntil = 0L;
    }

    private void end(final boolean success)
    {
        ended = true;
        ok = success;
    }

    private void go(final Phase next, final long now)
    {
        phase = next;
        actionStart = now;
    }

    /**
     * He cannot go on now: the order says why and he stands and waits before he looks again.
     */
    private void waitFor(final DirectedOrder order, final DirectedOrder.Why why, final AbstractEntityCitizen worker, final long now)
    {
        dropTarget();
        order.unreserve(citizen.getId());
        if (why != DirectedOrder.Why.NONE)
        {
            orders().blocked(order, citizen.getId(), why, WAIT_TICKS);
        }
        worker.getNavigation().stop();
        nextStep = now + WAIT_TICKS;
        strikes = 0;
        go(Phase.WAIT, now);
    }

    private int held(final Predicate<ItemStack> matcher)
    {
        return InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), matcher);
    }

    // ------------------------------------------------------------------ the steps

    @Override
    public void tick()
    {
        final AbstractEntityCitizen worker = citizen.getEntity().orElse(null);
        if (worker == null || worker.level().isClientSide() || ended || citizen.getJob() != null || !(worker.level() instanceof ServerLevel level))
        {
            return;
        }
        final long now = level.getGameTime();
        if (now < nextStep || !board.alive(claim))
        {
            return;
        }
        final DirectedOrder order = order();
        if (order == null)
        {
            end(false);
            return;
        }
        if (phase != Phase.WAIT && now - actionStart > ACTION_TIMEOUT)
        {
            // the target cannot be reached: it is left for good, the next nearest is tried
            if (tree != null)
            {
                unreachable.add(tree.base());
            }
            if (block != null)
            {
                unreachable.add(block);
            }
            dropTarget();
            order.unreserve(citizen.getId());
            if (++strikes >= 3)
            {
                waitFor(order, DirectedOrder.Why.NO_SITE, worker, now);
                return;
            }
            go(Phase.FIND, now);
        }
        switch (phase)
        {
            case FIND -> find(order, worker, level, now);
            case GATHER -> gather(order, worker, level, now);
            case DELIVER -> deliver(order, worker, now);
            case WAIT ->
            {
                board.heartbeat(claim);
                go(Phase.FIND, now);
            }
        }
    }

    private void find(final DirectedOrder order, final AbstractEntityCitizen worker, final ServerLevel level, final long now)
    {
        final Predicate<ItemStack> matcher = DirectedOrders.matcher(order.resource());
        order.clampCarried(citizen.getId(), held(matcher));
        final int carried = order.carried(citizen.getId());
        final boolean more = order.canReserve(citizen.getId());
        if (carried >= LOT || (carried > 0 && !more))
        {
            go(Phase.DELIVER, now);
            return;
        }
        if (!more)
        {
            // the others carry what is still missing: he waits until they hand in or the order ends
            waitFor(order, DirectedOrder.Why.NONE, worker, now);
            return;
        }
        if (orders().covered(order.resource()))
        {
            waitFor(order, DirectedOrder.Why.COVERED, worker, now);
            return;
        }
        if (DirectedOrders.needsTool(order.resource()) && toolSlot(level, null, order.resource()) < 0)
        {
            if (carried > 0)
            {
                go(Phase.DELIVER, now);
                return;
            }
            waitFor(order, DirectedOrder.Why.NO_TOOL, worker, now);
            return;
        }
        if (InventoryUtils.isItemHandlerFull(citizen.getInventory()))
        {
            if (carried > 0)
            {
                go(Phase.DELIVER, now);
                return;
            }
            waitFor(order, DirectedOrder.Why.INVENTORY_FULL, worker, now);
            return;
        }
        final Set<BlockPos> skip = new HashSet<>(unreachable);
        if (order.resource() == DirectedOrder.Resource.WOOD)
        {
            skip.addAll(board.takenTrees());
            tree = board.sites().nearestTreeIn(order.id(), order.area(), worker.blockPosition(), skip);
            if (tree != null)
            {
                board.takenTrees().add(tree.base());
                logsLeft = new ArrayList<>(tree.logs());
            }
        }
        else
        {
            skip.addAll(board.takenSurface());
            block = board.sites().nearestBlockIn(order.id(), order.area(), order.resource(), order.floor(), worker.blockPosition(), skip);
            if (block != null)
            {
                board.takenSurface().add(block);
            }
        }
        if (tree == null && block == null)
        {
            if (carried > 0)
            {
                go(Phase.DELIVER, now);
                return;
            }
            waitFor(order, DirectedOrder.Why.NO_SITE, worker, now);
            return;
        }
        workUntil = 0L;
        board.heartbeat(claim);
        go(Phase.GATHER, now);
    }

    // ------------------------------------------------------------------ gather

    private void gather(final DirectedOrder order, final AbstractEntityCitizen worker, final ServerLevel level, final long now)
    {
        final Predicate<ItemStack> matcher = DirectedOrders.matcher(order.resource());
        if (tree != null)
        {
            logsLeft.removeIf(pos -> !level.isLoaded(pos) || !LabourRules.log(level.getBlockState(pos)));
            if (logsLeft.isEmpty())
            {
                plantSapling(level, tree.base());
                board.sites().invalidate();
                dropTarget();
                board.heartbeat(claim);
                go(Phase.FIND, now);
                return;
            }
            if (!EntityNavigationUtils.walkToPos(worker, tree.base(), 3, true))
            {
                return;
            }
            final BlockPos log = logsLeft.get(0);
            // (a tree that was started is felled to the last log: the logs past what the order needs are the worker's own)
            order.reserve(citizen.getId());
            final int matched = breakWork(worker, level, log, toolSlot(level, level.getBlockState(log), order.resource()), now, matcher);
            if (matched >= 0)
            {
                logsLeft.remove(0);
                counted(order, matched);
            }
            return;
        }
        if (block == null)
        {
            go(Phase.FIND, now);
            return;
        }
        if (!level.isLoaded(block) || block.getY() < order.floor() || !LabourSites.directedBlock(order.resource(), level.getBlockState(block)) || !level.getBlockState(block.above()).isAir())
        {
            dropTarget();
            order.unreserve(citizen.getId());
            go(Phase.FIND, now);
            return;
        }
        if (!EntityNavigationUtils.walkToPos(worker, block.above(), 2, true))
        {
            return;
        }
        if (!order.reserve(citizen.getId()))
        {
            dropTarget();
            go(Phase.FIND, now);
            return;
        }
        final int slot = toolSlot(level, level.getBlockState(block), order.resource());
        if (DirectedOrders.needsTool(order.resource()) && slot < 0)
        {
            go(Phase.FIND, now);
            return;
        }
        final int matched = breakWork(worker, level, block, slot, now, matcher);
        if (matched >= 0)
        {
            dropTarget();
            counted(order, matched);
            go(Phase.FIND, now);
        }
    }

    private void counted(final DirectedOrder order, final int matched)
    {
        if (order.gathered(citizen.getId(), matched) > 0)
        {
            orders().progressed(order);
        }
        board.heartbeat(claim);
    }

    /**
     * @return the slot of his best tool of the resource's kind that harvests the block (any of the kind when the block is null), or -1.
     */
    private int toolSlot(final ServerLevel level, final BlockState state, final DirectedOrder.Resource resource)
    {
        final String tool = DirectedOrders.tool(resource);
        final var inventory = citizen.getInventory();
        int best = -1;
        double bestSpeed = 0.0D;
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || !LabourRules.isTool(tool, stack.getItem()) || (state != null && !stack.isCorrectToolForDrops(state) && state.requiresCorrectToolForDrops()))
            {
                continue;
            }
            final double speed = state == null ? 1.0D : stack.getItem().getDestroySpeed(stack, state);
            if (best < 0 || speed > bestSpeed)
            {
                bestSpeed = speed;
                best = slot;
            }
        }
        return best;
    }

    /**
     * Works on a block until it breaks (the time a hand or the tool takes); then its real drops go into his inventory (what does not fit lies at his feet) and the tool
     * wears through the citizens' damage path.
     *
     * @return -1 while he still works, else how many of the drops are the resource.
     */
    private int breakWork(final AbstractEntityCitizen worker, final ServerLevel level, final BlockPos pos, final int toolSlot, final long now, final Predicate<ItemStack> matcher)
    {
        final BlockState state = level.getBlockState(pos);
        if (state.isAir())
        {
            return 0;
        }
        final ItemStack tool = toolSlot >= 0 ? citizen.getInventory().getStackInSlot(toolSlot) : ItemStack.EMPTY;
        if (workUntil == 0L)
        {
            final float hardness = state.getDestroySpeed(level, pos);
            final float speed = tool.isEmpty() ? 1.0F : Math.max(1.0F, tool.getItem().getDestroySpeed(tool, state));
            final boolean harvest = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
            final double seconds = Math.max(0.05D, hardness) * (harvest ? 1.5D : 5.0D) / speed;
            workUntil = now + Math.max(5, Math.min(600, (long) (seconds * 20.0D / LabourSettings.workScale())));
        }
        WorkerUtil.faceBlock(pos, worker);
        if (now < workUntil)
        {
            if (now - lastSwing >= 15)
            {
                lastSwing = now;
                CitizenItemUtils.hitBlockWithToolInHand(worker, pos, false);
            }
            return -1;
        }
        workUntil = 0L;
        int matched = 0;
        for (final ItemStack drop : BlockPosUtil.getBlockDrops(level, pos, 0, tool, worker))
        {
            LabourStats.gathered(drop);
            if (matcher.test(drop))
            {
                matched += drop.getCount();
            }
            keep(worker, drop);
        }
        CitizenItemUtils.breakBlockWearingToolOf(worker, pos, toolSlot < 0 ? null : worker, toolSlot);
        return matched;
    }

    private void keep(final AbstractEntityCitizen worker, final ItemStack stack)
    {
        ItemStack rest = stack.copy();
        if (!rest.isEmpty())
        {
            rest = InventoryUtils.transferItemStackIntoNextBestSlotInItemHandlerWithResult(rest, citizen.getInventory());
        }
        if (rest != null && !rest.isEmpty())
        {
            CitizenItemUtils.entityDropItem(worker, rest);
        }
    }

    private void plantSapling(final ServerLevel level, final BlockPos base)
    {
        final var inventory = citizen.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.is(ItemTags.SAPLINGS) && stack.getItem() instanceof BlockItem blockItem && level.getBlockState(base).isAir())
            {
                final BlockState sapling = blockItem.getBlock().defaultBlockState();
                if (sapling.canSurvive(level, base))
                {
                    level.setBlock(base, sapling, 3);
                    inventory.extractItem(slot, 1, false);
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------ hand in

    private void deliver(final DirectedOrder order, final AbstractEntityCitizen worker, final long now)
    {
        final IBuilding building = orders().destinationOf(order);
        if (building == null)
        {
            waitFor(order, DirectedOrder.Why.NO_DESTINATION, worker, now);
            return;
        }
        if (!EntityNavigationUtils.walkToBuilding(worker, building))
        {
            return;
        }
        final Predicate<ItemStack> matcher = DirectedOrders.matcher(order.resource());
        order.clampCarried(citizen.getId(), held(matcher));
        final int give = order.toHandIn(citizen.getId());
        if (give <= 0)
        {
            go(Phase.FIND, now);
            return;
        }
        final int moved = ItemMover.move(citizen.getInventory(), building.getItemHandlerCap(), matcher, give);
        if (moved <= 0)
        {
            waitFor(order, DirectedOrder.Why.DESTINATION_FULL, worker, now);
            return;
        }
        final int counted = order.handedIn(citizen.getId(), moved);
        LabourStats.HANDED_IN.addAndGet(counted);
        LabourStats.log("citizen {} handed in {} of order {} ({}/{})", citizen.getId(), counted, order.id(), order.delivered(), order.amount());
        board.heartbeat(claim);
        if (!order.active())
        {
            ok = true;
            orders().completed(order);
            end(true);
            return;
        }
        orders().progressed(order);
        go(Phase.FIND, now);
    }
}
