package com.minecolonies.core.colony.labour;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.idle.IIdleTaskSession;
import com.minecolonies.api.colony.idle.IdleStopReason;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.requestsystem.RsAccess;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationReason;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import com.minecolonies.core.util.ItemMover;
import com.minecolonies.core.util.WorkerUtil;
import com.minecolonies.core.util.citizenutils.CitizenItemUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An unemployed citizen working on one claim of the labour board: he asks the planner what to do next, walks there and does it (fells a tree, digs a block, picks up
 * an item, crafts at a table or in his pockets, loads a furnace, takes stock out of a warehouse), and finally brings the goods to the hut that asked (or the
 * warehouse a stock goal fills) and wakes the request. After every finished action the lease of the claim is renewed; the planner is asked again from the inventory
 * as it is, so nothing is lost when he is interrupted.
 */
public final class LabourSession implements IIdleTaskSession
{
    private enum Phase
    {
        THINK, GATHER, WITHDRAW, CRAFT, SMELT_LOAD, SMELT_WAIT, CUT, DELIVER, UNLOAD, END
    }

    /**
     * Ticks a single action may take before the labourer gives up on its target.
     */
    private static final int ACTION_TIMEOUT = 1800;

    /**
     * Thinks without any progress before he gives up.
     */
    private static final int MAX_THINKS = 60;

    private final String      taskId;
    private final Colony      colony;
    private final ICitizenData citizen;
    private final LabourBoard board;
    private final LabourClaim claim;

    private Phase  phase = Phase.THINK;
    private long   nextStep;
    private long   actionStart;
    private int    thinks;
    private int    strikes;
    private boolean ended;
    private boolean ok;
    private boolean stopped;
    /**
     * Round 11 (W4-10): the request was served by someone else while he worked on it; he brings what he carries of the basic goods to a warehouse before the
     * session ends.
     */
    private boolean stashing;

    /**
     * Sessions that took their leftover goods to a warehouse after their request was served elsewhere (for the tests).
     */
    public static final java.util.concurrent.atomic.AtomicInteger stashes = new java.util.concurrent.atomic.AtomicInteger();

    private LabourPlanner.Action<Item> action;

    // gather
    private LabourSites.TreeSite tree;
    private List<BlockPos>       logsLeft;
    /** The stripped states he set himself on the logs of the tree he works on (only those stripped logs are his to fell). */
    private final Map<BlockPos, BlockState> strippedHere = new HashMap<>();
    /** Whether the tree he works on passed the fresh look before its first log was changed. */
    private boolean                     treeVerified;
    private BlockPos             block;
    /** The block the top block was when he chose it (another block there is not his to dig). */
    private net.minecraft.world.level.block.Block blockChosen;
    private int                  looseId = -1;
    /** r4: his place in a stone shaft while he digs there, or null. */
    @Nullable
    private ShaftSites.Assignment shaftAt;
    private long                 workUntil;
    private long                 lastSwing;

    // craft and smelt
    private BlockPos post;
    private long     craftUntil;
    private long     smeltUntil;
    private int      smeltExpect;
    private Item     smeltOutput;

    // the Architect's Cutter: the job for the claim's exact stack and how many runs are wanted
    private LabourCutter.Job cutJob;
    private int              cutRuns;
    private long             cutUntil;

    // tool mode (a directed worker who has to get the tool of his order first): the tool kind, null otherwise
    @Nullable
    private final String toolKind;
    @Nullable
    private LabourPlanner.Why lastWhy;
    // in tool mode, the order and its area: the only raw goods he takes for the tool are the area's own natural trees
    private final int                  orderId;
    @Nullable
    private final DirectedOrder.Area   area;
    private final java.util.Set<BlockPos> leftTrees = new java.util.HashSet<>();

    public LabourSession(final String taskId, final Colony colony, final ICitizenData citizen, final LabourBoard board, final LabourClaim claim)
    {
        this(taskId, colony, citizen, board, claim, null, -1, null);
    }

    private LabourSession(final String taskId, final Colony colony, final ICitizenData citizen, final LabourBoard board, final LabourClaim claim, @Nullable final String toolKind,
      final int orderId, @Nullable final DirectedOrder.Area area)
    {
        this.toolKind = toolKind;
        this.orderId = orderId;
        this.area = area;
        this.taskId = taskId;
        this.colony = colony;
        this.citizen = citizen;
        this.board = board;
        this.claim = claim;
        this.actionStart = colony.getWorld() == null ? 0L : colony.getWorld().getGameTime();
        // (the board frees what this session took when the claim ends, also when the session is never stopped: a lease that runs out)
        board.holdSites(claim, this::cleanup);
    }

    // ------------------------------------------------------------------ the session contract

    /**
     * A session that only gets the citizen a tool of the kind for himself, on the claim of his directed order: a free one out of a warehouse first (reserved through
     * the ledger), else the basic tool made the labour way: from what he holds, what the warehouses give for covered goods, and the logs of natural trees standing wholly in
     * the order's area (the manager's choice bounds what he takes out of the world; nothing is felled, stripped or dug outside it). It keeps what it makes, hands nothing
     * in and never releases the claim; the directed session drives it ({@link #tick}) and ends it ({@link #abort}).
     */
    public static LabourSession forTool(final Colony colony, final ICitizenData citizen, final LabourBoard board, final LabourClaim claim, final String toolKind,
      final int orderId, final DirectedOrder.Area area)
    {
        return new LabourSession(DirectedTask.ID, colony, citizen, board, claim, toolKind, orderId, area);
    }

    /**
     * @return the colony as the planner sees it for the tool of a directed order: raw goods only from the natural trees standing wholly in the area.
     */
    public static LabourPlanner.Env<Item> toolEnv(final Colony colony, final LabourBoard board, final int orderId, final DirectedOrder.Area area)
    {
        final java.util.Set<Item> logs = board.sites().treeLogsIn(orderId, area);
        return LabourPlanner.withinArea(new LabourEnv(colony, board, true), logs::contains);
    }

    /**
     * @return whether the (tool) session ended with the tool in hand.
     */
    public boolean succeeded()
    {
        return ended && ok;
    }

    /**
     * @return why the planner could not go on, or null.
     */
    @Nullable
    public LabourPlanner.Why lastWhy()
    {
        return lastWhy;
    }

    /**
     * Ends a tool session: its targets are given back, the claim stays with the directed session.
     */
    public void abort()
    {
        cleanup();
        ended = true;
    }

    @Override
    public String getTaskId()
    {
        return taskId;
    }

    /**
     * @return the claim the citizen works on.
     */
    public LabourClaim claim()
    {
        return claim;
    }

    /**
     * @return whether the session is over (it ended, successfully or not; the owner stops it).
     */
    public boolean isEnded()
    {
        return ended;
    }

    /**
     * @return a name of what he does now (for the views and the logs).
     */
    public String phaseName()
    {
        return phase.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public boolean isValid()
    {
        if (ended || !board.alive(claim))
        {
            return false;
        }
        if (claim.source() == LabourClaim.Source.SELF)
        {
            // the idle builder: his own build only, a few minutes at a time
            if (!LabourSettings.worldEnabled() || !LabourSettings.selfSupply(colony) || colony.getWorld() == null
                  || colony.getWorld().getGameTime() - claim.created() > LabourSettings.supplyMaxTicks())
            {
                return false;
            }
        }
        else if (!LabourSettings.enabled(colony))
        {
            return false;
        }
        if (stashing)
        {
            // on his way to the warehouse with the leftovers (the unload ends the session)
            return true;
        }
        if (claim.request() == null || board.stillWaiting(claim.request()))
        {
            return true;
        }
        // the request was served elsewhere: what he gathered for it goes to a warehouse first, if he carries such goods and the colony has one
        if (claim.source() != LabourClaim.Source.SELF && toolKind == null && phase != Phase.UNLOAD && phase != Phase.END && !surplus().isEmpty()
              && !board.sites().warehouses().isEmpty())
        {
            stashing = true;
            stashes.incrementAndGet();
            LabourStats.log("citizen {}: request served elsewhere, leftovers to a warehouse", citizen.getId());
            cleanup();
            phase = Phase.UNLOAD;
            actionStart = colony.getWorld() == null ? 0L : colony.getWorld().getGameTime();
            board.heartbeat(claim);
            return true;
        }
        return false;
    }

    @Override
    public Component describe()
    {
        final Component what = (claim.exact().isEmpty() ? new ItemStack(claim.target()) : claim.exact()).getHoverName();
        final String key = switch (phase)
        {
            case GATHER -> "com.minecolonies.coremod.labour.task.gather";
            case WITHDRAW -> "com.minecolonies.coremod.labour.task.withdraw";
            case CRAFT -> "com.minecolonies.coremod.labour.task.craft";
            case SMELT_LOAD, SMELT_WAIT -> "com.minecolonies.coremod.labour.task.smelt";
            case CUT -> "com.minecolonies.coremod.labour.task.cut";
            case DELIVER, UNLOAD, END -> "com.minecolonies.coremod.labour.task.deliver";
            default -> "com.minecolonies.coremod.labour.task.plan";
        };
        if (phase == Phase.GATHER && shaftAt != null)
        {
            return Component.translatable("com.minecolonies.coremod.labour.task.shaft", claim.amount(), what, shaftAt.shaft().id);
        }
        if (claim.source() == LabourClaim.Source.SELF)
        {
            return Component.translatable("com.minecolonies.coremod.labour.task.builder", claim.amount(), what);
        }
        return Component.translatable(key, claim.amount(), what);
    }

    @Override
    public void stop(final IdleStopReason reason)
    {
        if (stopped)
        {
            return;
        }
        stopped = true;
        LabourStats.log("session of citizen {} stops: {} (phase {}, ended {}, ok {})", citizen.getId(), reason, phase, ended, ok);
        cleanup();
        // (a stash after the request was served elsewhere is no failure of the request)
        board.release(claim, ok && !stashing, !ok && !stashing && reason == IdleStopReason.TASK_INVALID);
        final AbstractEntityCitizen worker = citizen.getEntity().orElse(null);
        if (worker != null && reason != IdleStopReason.CITIZEN_GONE)
        {
            worker.getNavigation().stop();
        }
    }

    private void cleanup()
    {
        if (tree != null)
        {
            board.takenTrees().remove(tree.base());
            tree = null;
        }
        strippedHere.clear();
        treeVerified = false;
        if (block != null)
        {
            board.takenSurface().remove(block);
            block = null;
        }
        blockChosen = null;
        if (looseId >= 0)
        {
            board.takenEntities().remove(looseId);
            looseId = -1;
        }
        if (shaftAt != null)
        {
            board.shafts().release(citizen.getId());
            shaftAt = null;
        }
        releasePost();
    }

    private void releasePost()
    {
        if (post != null)
        {
            board.takenTables().remove(post);
            board.takenFurnaces().remove(post);
            board.takenCutters().remove(post);
            post = null;
        }
    }

    private void end(final boolean success)
    {
        ended = true;
        ok = success;
    }

    // ------------------------------------------------------------------ inventory

    /**
     * @return what the citizen carries, by item.
     */
    public static Map<Item, Integer> inventoryOf(final ICitizenData citizen)
    {
        final Map<Item, Integer> counts = new HashMap<>();
        final var inventory = citizen.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty())
            {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    private int count(final Item item)
    {
        return InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), item);
    }

    /**
     * @return how many of what the claim brings he holds (the exact variant when the claim names one).
     */
    private int countClaimed()
    {
        return InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), claim::accepts);
    }

    /**
     * Puts the stack into the citizen's inventory; what does not fit lies at his feet.
     */
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

    private void take(final Item item, final int amount)
    {
        int left = amount;
        final var inventory = citizen.getInventory();
        for (int slot = 0; slot < inventory.getSlots() && left > 0; slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.is(item))
            {
                left -= inventory.extractItem(slot, Math.min(left, stack.getCount()), false).getCount();
            }
        }
    }

    // ------------------------------------------------------------------ the steps

    @Override
    public void tick()
    {
        final AbstractEntityCitizen worker = citizen.getEntity().orElse(null);
        if (worker == null || worker.level().isClientSide() || ended || (claim.source() != LabourClaim.Source.SELF && citizen.getJob() != null)
              || !(worker.level() instanceof ServerLevel level))
        {
            return;
        }
        final long now = level.getGameTime();
        if (now < nextStep)
        {
            return;
        }
        if (!board.alive(claim))
        {
            return;
        }
        if (stashing && now - actionStart > ACTION_TIMEOUT)
        {
            // no way to the warehouse: he keeps the goods (later offers count them)
            end(false);
            return;
        }
        if (now - actionStart > ACTION_TIMEOUT && phase != Phase.SMELT_WAIT)
        {
            // the action does not get anywhere (no path, a target nobody reaches): drop its target and think again
            strikes++;
            cleanup();
            if (strikes >= 3)
            {
                end(false);
                return;
            }
            phase = Phase.THINK;
            actionStart = now;
        }
        switch (phase)
        {
            case THINK -> think(worker, now);
            case GATHER -> gather(worker, level, now);
            case WITHDRAW -> withdraw(worker, now);
            case CRAFT -> craft(worker, level, now);
            case SMELT_LOAD -> smeltLoad(worker, level, now);
            case SMELT_WAIT -> smeltWait(worker, level, now);
            case CUT -> cut(worker, level, now);
            case DELIVER -> deliver(worker, now);
            case UNLOAD -> unload(worker, now);
            case END -> end(true);
        }
    }

    private void go(final Phase next, final long now)
    {
        LabourStats.log("citizen {} phase {} -> {}", citizen.getId(), phase, next);
        phase = next;
        actionStart = now;
    }

    private void think(final AbstractEntityCitizen worker, final long now)
    {
        if (toolKind != null)
        {
            thinkTool(now);
            return;
        }
        // (a request of the hut that alone covers the good, a new miner's own cobblestone, is not covered for it)
        final BlockPos requester = claim.source() == LabourClaim.Source.REQUEST || claim.source() == LabourClaim.Source.SELF ? claim.destination() : null;
        if (++thinks > MAX_THINKS || board.coverage().coveredFor(claim.target(), requester))
        {
            end(false);
            return;
        }
        final LabourEnv env = new LabourEnv(colony, board, claim.source() != LabourClaim.Source.STOCKPILE, requester).forCitizen(citizen.getId());
        final boolean cutting = !claim.exact().isEmpty();
        if (!cutting && shaftToolFromStock(env, now))
        {
            return;
        }
        final int held = cutting ? countClaimed() : 0;
        final LabourPlanner.Result<Item> result = cutting ? board.planCut(env, claim.exact(), claim.amount(), held, materialsOf(citizen, claim))
                                                    : LabourPlanner.next(env, claim.target(), claim.amount(), inventoryOf(citizen));
        if (result instanceof LabourPlanner.Done<Item>)
        {
            if (cutting && held < claim.amount())
            {
                // every material for the runs is held: to the cutter
                cutJob = board.cutJob(claim.exact()).job();
                cutRuns = cutJob == null ? 0 : cutJob.runsFor(claim.amount() - held);
                cutUntil = 0L;
                board.heartbeat(claim);
                go(Phase.CUT, now);
                return;
            }
            go(Phase.DELIVER, now);
            return;
        }
        if (result instanceof LabourPlanner.Blocked<Item> blocked)
        {
            LabourStats.log("citizen {} is blocked: {} {}", citizen.getId(), blocked.why(), blocked.item());
            failed(blocked.why());
            end(false);
            return;
        }
        perform(((LabourPlanner.Do<Item>) result).action(), now);
    }

    /**
     * Tool mode: done once he holds a tool of the kind; a free stock tool first; else the planner's way to the basic one.
     */
    private void thinkTool(final long now)
    {
        if (DirectedOrders.carries(citizen, toolKind))
        {
            end(true);
            return;
        }
        if (++thinks > MAX_THINKS)
        {
            end(false);
            return;
        }
        final Item stocked = stockedTool(board, toolKind);
        if (stocked != null)
        {
            perform(new LabourPlanner.Withdraw<>(stocked, 1), now);
            return;
        }
        final LabourPlanner.Result<Item> result = LabourPlanner.next(toolEnv(colony, board, orderId, area), LabourRules.toolItem(toolKind), 1, inventoryOf(citizen));
        if (result instanceof LabourPlanner.Done<Item>)
        {
            end(true);
            return;
        }
        if (result instanceof LabourPlanner.Blocked<Item> blocked)
        {
            lastWhy = blocked.why();
            end(false);
            return;
        }
        perform(((LabourPlanner.Do<Item>) result).action(), now);
    }

    /**
     * @return a tool of the kind that lies in a warehouse free of reservations, or null.
     */
    @Nullable
    public static Item stockedTool(final LabourBoard board, final String toolKind)
    {
        final ReservationLedger ledger = RsAccess.ledger(board.colony().getRequestManager());
        for (final IWareHouse wareHouse : board.sites().warehouses())
        {
            final var handler = wareHouse.getItemHandlerCap();
            for (int slot = 0; handler != null && slot < handler.getSlots(); slot++)
            {
                final ItemStack stack = handler.getStackInSlot(slot);
                if (stack.isEmpty() || !LabourRules.isTool(toolKind, stack.getItem()))
                {
                    continue;
                }
                final Item item = stack.getItem();
                final int physical = InventoryUtils.getCountFromBuilding(wareHouse, s -> s.is(item));
                final int reserved = ledger == null ? 0 : ledger.reservedStock(wareHouse.getID(), s -> s.is(item));
                if (physical - reserved > 0)
                {
                    return item;
                }
            }
        }
        return null;
    }

    private void failed(final LabourPlanner.Why why)
    {
        lastWhy = why;
        board.planFailed(claim, why);
    }

    private void perform(final LabourPlanner.Action<Item> next, final long now)
    {
        action = next;
        LabourStats.log("citizen {} does {}", citizen.getId(), action);
        board.heartbeat(claim);
        if (action instanceof LabourPlanner.Gather<Item>)
        {
            go(Phase.GATHER, now);
        }
        else if (action instanceof LabourPlanner.Withdraw<Item>)
        {
            go(Phase.WITHDRAW, now);
        }
        else if (action instanceof LabourPlanner.Craft<Item>)
        {
            craftUntil = 0L;
            go(Phase.CRAFT, now);
        }
        else
        {
            smeltOutput = null;
            go(Phase.SMELT_LOAD, now);
        }
    }

    /**
     * What he holds that may go into the cutter: by item, but never the claim's own exact stacks (a made block is not a material).
     */
    private static Map<Item, Integer> materialsOf(final ICitizenData citizen, final LabourClaim claim)
    {
        final Map<Item, Integer> counts = new HashMap<>();
        final var inventory = citizen.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && !claim.accepts(stack) && stack.getComponentsPatch().isEmpty())
            {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    // ------------------------------------------------------------------ gather

    private void gather(final AbstractEntityCitizen worker, final ServerLevel level, final long now)
    {
        final LabourPlanner.Gather<Item> g = (LabourPlanner.Gather<Item>) action;
        final Item item = g.gather().item();
        if (count(item) >= g.until() && tree == null)
        {
            // (a tree that was started is felled to the last log: a trunk left standing in the air is no tree for anybody)
            cleanup();
            go(Phase.THINK, now);
            return;
        }
        if (area != null && g.gather().kind() != LabourPlanner.GatherKind.LOG)
        {
            // (the tool of a directed order: only the area's trees are felled, nothing else is taken out of the world)
            failed(LabourPlanner.Why.NO_SITE);
            end(false);
            return;
        }
        switch (g.gather().kind())
        {
            case LOG -> gatherLog(worker, level, item, now, false);
            case STRIP -> gatherLog(worker, level, item, now, true);
            case SURFACE, STONE -> gatherBlock(worker, level, g, now);
            case LOOSE -> gatherLoose(worker, level, item, now);
            case SHAFT -> gatherShaft(worker, level, now);
        }
    }

    /**
     * Fells a natural tree log by log. To strip ({@code strip}), each log is first stripped where it stands with his axe, through the axe's own transformer (the
     * axe wears by the transformer's damage per use), then felled: the drops are the stripped logs of the tree's species. A log the axe would make into anything else
     * than the wanted kind is not touched.
     */
    private void gatherLog(final AbstractEntityCitizen worker, final ServerLevel level, final Item item, final long now, final boolean strip)
    {
        if (tree == null)
        {
            final java.util.Set<BlockPos> skip = new java.util.HashSet<>(board.takenTrees());
            skip.addAll(leftTrees);
            if (area != null)
            {
                tree = board.sites().nearestToolTreeIn(orderId, area, item, worker.blockPosition(), skip);
            }
            else
            {
                tree = strip ? board.sites().nearestStripTree(item, worker.blockPosition(), skip) : board.sites().nearestTree(item, worker.blockPosition(), skip);
            }
            if (tree == null)
            {
                failed(LabourPlanner.Why.NO_SITE);
                end(false);
                return;
            }
            board.takenTrees().add(tree.base());
            logsLeft = new ArrayList<>(tree.logs());
            strippedHere.clear();
            treeVerified = false;
            workUntil = 0L;
        }
        // (a log in a chunk that is not loaded is not read: that would load it; the tree is left)
        for (final BlockPos pos : logsLeft)
        {
            if (!LabourSites.simulated(level, pos))
            {
                leaveTree("a log lies in a chunk that is not loaded or where entities do not tick", now);
                return;
            }
        }
        logsLeft.removeIf(pos -> level.getBlockState(pos).isAir());
        if (logsLeft.isEmpty())
        {
            plantSapling(worker, level, tree.base());
            board.sites().invalidate();
            board.takenTrees().remove(tree.base());
            tree = null;
            strippedHere.clear();
            treeVerified = false;
            board.heartbeat(claim);
            return;
        }
        if (!EntityNavigationUtils.walkToPos(worker, tree.base(), 3, true))
        {
            return;
        }
        if (!treeVerified)
        {
            // the scan may be old: the whole tree is proved natural once more, with the buildings, vetoes and placed blocks as they are now, before a log is changed
            if (!board.sites().verifyTree(tree, area))
            {
                leaveTree("the tree is no longer a natural tree to take", now);
                return;
            }
            treeVerified = true;
        }
        if (!board.sites().treeStillSafe(tree, logsLeft, area, strippedHere))
        {
            // a log grew into a building's box, a veto, a placed block, an unloaded chunk or out of the claim, or a log changed or something built touches the tree
            leaveTree("a log may no longer be taken", now);
            return;
        }
        final BlockPos log = logsLeft.get(0);
        int toolSlot = -1;
        if (strip)
        {
            toolSlot = bestToolSlot(level.getBlockState(log), "axe");
            if (toolSlot < 0)
            {
                // (the axe broke or is gone: the planner gets him one first)
                cleanup();
                go(Phase.THINK, now);
                return;
            }
            // (only a stripped log he made himself here is felled as such: a stripped log he did not make is never a tree's, and treeStillSafe refused it)
            if (!strippedHere.containsKey(log))
            {
                final int stripped = stripNow(worker, level, log, toolSlot, item, now);
                if (stripped == REFUSED)
                {
                    leaveTree("the log cannot be stripped to " + item, now);
                    return;
                }
                if (stripped == WORKING)
                {
                    return;
                }
                if (!board.sites().treeStillSafe(tree, logsLeft, area, strippedHere))
                {
                    leaveTree("a log may no longer be taken", now);
                    return;
                }
            }
        }
        final List<BlockPos> remaining = logsLeft;
        final int broken = breakWork(worker, level, log, toolSlot, now, pos -> board.sites().treeStillSafe(tree, remaining, area, strippedHere));
        if (broken == REFUSED)
        {
            leaveTree("the log did not break or may no longer be taken", now);
            return;
        }
        if (broken == DONE)
        {
            strippedHere.remove(log);
            logsLeft.remove(0);
            board.heartbeat(claim);
        }
    }

    /**
     * Leaves the rest of the tree he works on standing for good (this session and the scans until they run again) and thinks again.
     */
    private void leaveTree(final String why, final long now)
    {
        LabourStats.log("citizen {} leaves tree {}{}: {}", citizen.getId(), tree.base(), area == null ? "" : " of order " + orderId + " for the tool", why);
        leftTrees.add(tree.base());
        board.sites().dropTree(tree.base());
        cleanup();
        go(Phase.THINK, now);
    }

    private static final int WORKING = -1;
    private static final int REFUSED = -2;
    private static final int DONE    = 0;

    /**
     * Strips the log at the position with the axe in the slot. The block is changed only when it is still the tree's natural log and the axe's transformer makes the
     * wanted kind of it; the axe wears only when the world took the change, and the stripped state is remembered as his own.
     *
     * @return {@link #DONE} once the block is stripped to the wanted kind, {@link #WORKING} while he works on it, {@link #REFUSED} when it cannot be (nothing changed).
     */
    private int stripNow(final AbstractEntityCitizen worker, final ServerLevel level, final BlockPos pos, final int toolSlot, final Item wanted, final long now)
    {
        final ItemStack axe = citizen.getInventory().getStackInSlot(toolSlot);
        final BlockState before = level.getBlockState(pos);
        final LabourStrip.Result result = LabourStrip.stripped(level, pos, LabourStrip.of(axe));
        if (!LabourRules.naturalLog(before) || result == null || result.state().getBlock().asItem() != wanted || colony.getWorld() == null)
        {
            LabourStats.log("citizen {} cannot strip {} at {} to {}", citizen.getId(), before, pos, wanted);
            return REFUSED;
        }
        WorkerUtil.faceBlock(pos, worker);
        if (workUntil == 0L)
        {
            workUntil = now + Math.max(5, (long) (10 / LabourSettings.workScale()));
        }
        if (now < workUntil)
        {
            return WORKING;
        }
        workUntil = 0L;
        if (!board.sites().treeStillSafe(tree, logsLeft, area, strippedHere) || level.getBlockState(pos) != before)
        {
            return REFUSED;
        }
        if (!level.setBlock(pos, result.state(), 11) || !level.getBlockState(pos).equals(result.state()))
        {
            LabourStats.log("citizen {} could not strip {} at {}: the world refused", citizen.getId(), before, pos);
            return REFUSED;
        }
        strippedHere.put(pos.immutable(), result.state());
        if (result.damage() > 0)
        {
            CitizenItemUtils.setMainHeldItem(worker, toolSlot);
            CitizenItemUtils.damageItemInHand(worker, net.minecraft.world.InteractionHand.MAIN_HAND, result.damage());
        }
        return DONE;
    }

    private void plantSapling(final AbstractEntityCitizen worker, final ServerLevel level, final BlockPos base)
    {
        final var inventory = citizen.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.is(ItemTags.SAPLINGS) && stack.getItem() instanceof BlockItem blockItem && board.sites().safeNow(area, base) && level.getBlockState(base).isAir())
            {
                final BlockState sapling = blockItem.getBlock().defaultBlockState();
                if (sapling.canSurvive(level, base))
                {
                    // (the sapling leaves his hands only when the world took it)
                    if (level.setBlock(base, sapling, 3))
                    {
                        inventory.extractItem(slot, 1, false);
                    }
                }
                return;
            }
        }
    }

    private void gatherBlock(final AbstractEntityCitizen worker, final ServerLevel level, final LabourPlanner.Gather<Item> g, final long now)
    {
        final Item item = g.gather().item();
        if (block == null)
        {
            block = board.sites().nearestSurface(item, worker.blockPosition(), board.takenSurface());
            if (block == null)
            {
                failed(LabourPlanner.Why.NO_SITE);
                end(false);
                return;
            }
            board.takenSurface().add(block);
            blockChosen = level.getBlockState(block).getBlock();
            workUntil = 0L;
        }
        if (!board.sites().surfaceSafeNow(block, blockChosen))
        {
            // (it changed, was built over, a building or veto came, or digging the ground was switched off: it is not taken)
            leaveBlock();
            return;
        }
        if (!EntityNavigationUtils.walkToPos(worker, block.above(), 2, true))
        {
            return;
        }
        int toolSlot = -1;
        if (g.gather().tool() != null)
        {
            toolSlot = bestToolSlot(level.getBlockState(block), g.gather().tool());
        }
        final net.minecraft.world.level.block.Block chosen = blockChosen;
        final int broken = breakWork(worker, level, block, toolSlot, now, pos -> board.sites().surfaceSafeNow(pos, chosen));
        if (broken == REFUSED)
        {
            leaveBlock();
            return;
        }
        if (broken == DONE)
        {
            board.takenSurface().remove(block);
            block = null;
            blockChosen = null;
            board.heartbeat(claim);
        }
    }

    private void leaveBlock()
    {
        LabourStats.log("citizen {} leaves block {}: no longer to be taken", citizen.getId(), block.toShortString());
        board.sites().dropSurface(block);
        board.takenSurface().remove(block);
        block = null;
        blockChosen = null;
        workUntil = 0L;
    }

    // ------------------------------------------------------------------ stone shafts (r4)

    /**
     * Digs in one of the colony's stone shafts ({@link ShaftSites}): the next undone block of the segment he holds (the well or a gallery), nearest shaft first, with a
     * wood or stone pickaxe only. Before a block breaks it is looked at once more with its neighbours ({@link ShaftSites#check}); a block next to unknown air, water, a
     * placed block or another claim stays, and the rest of its segment is left. Torches go up on their spots before the work goes deeper; a hostile close by sends him
     * out. Ores his pickaxe takes are mined (their drops go to a warehouse with the other finds); an ore it cannot take stops the tunnel.
     */
    private void gatherShaft(final AbstractEntityCitizen worker, final ServerLevel level, final long now)
    {
        final ShaftSites sites = board.shafts();
        for (int tries = 0; tries < 3; tries++)
        {
            if (shaftAt == null)
            {
                shaftAt = sites.assign(citizen.getId(), worker.blockPosition());
                workUntil = 0L;
                if (shaftAt == null)
                {
                    failed(LabourPlanner.Why.NO_SITE);
                    end(false);
                    return;
                }
            }
            final ShaftSites.Step step = sites.next(shaftAt);
            if (step instanceof ShaftSites.Done || step instanceof ShaftSites.Ended)
            {
                // (the segment is dug out or left: the next one)
                sites.release(citizen.getId());
                shaftAt = null;
                continue;
            }
            if (step instanceof ShaftSites.Wait)
            {
                sites.release(citizen.getId());
                shaftAt = null;
                failed(LabourPlanner.Why.NO_SITE);
                end(false);
                return;
            }
            if (step instanceof ShaftSites.Light light)
            {
                lightShaft(worker, level, light.torch(), now);
                return;
            }
            digShaft(worker, level, (ShaftSites.Dig) step, now);
            return;
        }
    }

    private void digShaft(final AbstractEntityCitizen worker, final ServerLevel level, final ShaftSites.Dig dig, final long now)
    {
        final ShaftSites sites = board.shafts();
        final ShaftSites.Assignment at = shaftAt;
        if (sites.hostileNear(dig.pos()) || sites.hostileNear(worker.blockPosition()))
        {
            LabourStats.log("citizen {} leaves shaft {}: a hostile is near", citizen.getId(), at.shaft().id);
            sites.release(citizen.getId());
            shaftAt = null;
            failed(LabourPlanner.Why.NO_SITE);
            end(false);
            return;
        }
        final BlockState state = level.getBlockState(dig.pos());
        final int toolSlot = bestToolSlot(state, LabourRules.SHAFT_TOOL);
        if (toolSlot < 0)
        {
            if (LabourRules.shaftOre(state) && hasShaftPickaxe())
            {
                // his pickaxe cannot take this ore: the tunnel stops at it
                LabourStats.log("citizen {} meets {} in shaft {}: his pickaxe cannot take it", citizen.getId(), state.getBlock(), at.shaft().id);
                sites.oreFace(at.shaft(), at.segment(), dig.pos(), state);
                shaftAt = null;
                return;
            }
            // (the pickaxe broke or is gone: the planner gets him one first)
            cleanup();
            go(Phase.THINK, now);
            return;
        }
        if (!EntityNavigationUtils.walkToPos(worker, dig.pos(), 2, true))
        {
            return;
        }
        final int broken = breakWork(worker, level, dig.pos(), toolSlot, now,
          pos -> at.shaft().state() == LabourShafts.State.OPEN && sites.check(at.shaft(), pos) == ShaftSites.Safety.OK);
        if (broken == REFUSED)
        {
            if (sites.check(at.shaft(), dig.pos()) == ShaftSites.Safety.END)
            {
                sites.end(at.shaft(), at.segment(), dig.pos());
            }
            sites.release(citizen.getId());
            shaftAt = null;
            workUntil = 0L;
            return;
        }
        if (broken == DONE)
        {
            if (LabourRules.shaftOre(state))
            {
                sites.found(at.shaft(), dig.pos(), BuiltInRegistries.ITEM.getKey(state.getBlock().asItem()).toString(), true);
            }
            sites.dugOne(at.shaft(), at.segment(), dig.index());
            board.heartbeat(claim);
        }
    }

    /**
     * r4: cobblestone from a shaft needs a wood or stone pickaxe; one in the warehouse's free stock is taken before one is made (an iron one stays for the specialists).
     *
     * @return true when he goes to take one.
     */
    private boolean shaftToolFromStock(final LabourEnv env, final long now)
    {
        if (claim.target() != net.minecraft.world.item.Items.COBBLESTONE || count(claim.target()) >= claim.amount() || hasShaftPickaxe()
              || board.sites().hasSurface(net.minecraft.world.item.Items.COBBLESTONE)
              || !env.canGather(new LabourPlanner.GatherDef<>(LabourPlanner.GatherKind.SHAFT, net.minecraft.world.item.Items.COBBLESTONE, LabourRules.SHAFT_TOOL)))
        {
            return false;
        }
        final Item stocked = stockedTool(board, LabourRules.SHAFT_TOOL);
        if (stocked == null)
        {
            return false;
        }
        perform(new LabourPlanner.Withdraw<>(stocked, 1), now);
        return true;
    }

    private boolean hasShaftPickaxe()
    {
        final var inventory = citizen.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            if (LabourRules.isTool(LabourRules.SHAFT_TOOL, inventory.getStackInSlot(slot).getItem()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts a torch on its spot in the shaft: one he carries; else he makes some from coal or charcoal and a stick in his pockets, or takes some out of the warehouse's
     * free stock. Without any the shaft is left alone a while and says it needs torches.
     */
    private void lightShaft(final AbstractEntityCitizen worker, final ServerLevel level, final ShaftPlan.Torch torch, final long now)
    {
        final ShaftSites sites = board.shafts();
        if (count(net.minecraft.world.item.Items.TORCH) <= 0)
        {
            if (LabourSettings.pocketCrafting() && count(net.minecraft.world.item.Items.STICK) > 0
                  && (count(net.minecraft.world.item.Items.COAL) > 0 || count(net.minecraft.world.item.Items.CHARCOAL) > 0))
            {
                take(count(net.minecraft.world.item.Items.COAL) > 0 ? net.minecraft.world.item.Items.COAL : net.minecraft.world.item.Items.CHARCOAL, 1);
                take(net.minecraft.world.item.Items.STICK, 1);
                keep(worker, new ItemStack(net.minecraft.world.item.Items.TORCH, 4));
                return;
            }
            final int free = board.stock(net.minecraft.world.item.Items.TORCH);
            if (free > 0)
            {
                perform(new LabourPlanner.Withdraw<>(net.minecraft.world.item.Items.TORCH, Math.min(8, free)), now);
                return;
            }
            LabourStats.log("citizen {}: shaft {} needs torches and none are to be had", citizen.getId(), shaftAt.shaft().id);
            sites.noTorches(shaftAt.shaft());
            sites.release(citizen.getId());
            shaftAt = null;
            failed(LabourPlanner.Why.NO_SITE);
            end(false);
            return;
        }
        if (!EntityNavigationUtils.walkToPos(worker, torch.pos(), 2, true))
        {
            return;
        }
        final BlockState lit = torch.wall()
                                 ? net.minecraft.world.level.block.Blocks.WALL_TORCH.defaultBlockState()
                                     .setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, torch.facing())
                                 : net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState();
        if (!level.getBlockState(torch.pos()).isAir() || !board.sites().safeNow(null, torch.pos()) || !lit.canSurvive(level, torch.pos()))
        {
            // (the spot is taken or cannot hold a torch: the work goes on without it)
            LabourStats.log("citizen {} cannot put a torch at {} in shaft {}", citizen.getId(), torch.pos().toShortString(), shaftAt.shaft().id);
            sites.end(shaftAt.shaft(), shaftAt.segment(), torch.pos());
            shaftAt = null;
            return;
        }
        // (the torch leaves his hands only when the world took it)
        if (level.setBlock(torch.pos(), lit, 3))
        {
            take(net.minecraft.world.item.Items.TORCH, 1);
            board.heartbeat(claim);
        }
    }

    private int bestToolSlot(final BlockState state, final String tool)
    {
        final var inventory = citizen.getInventory();
        int best = -1;
        double bestSpeed = 0.0D;
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && LabourRules.isTool(tool, stack.getItem()) && stack.isCorrectToolForDrops(state))
            {
                final double speed = stack.getItem().getDestroySpeed(stack, state);
                if (speed > bestSpeed)
                {
                    bestSpeed = speed;
                    best = slot;
                }
            }
        }
        return best;
    }

    /**
     * Works on a block until it breaks (the time a hand or the tool takes). When it is due, the block is looked at once more ({@code safe}, read now); then the tool
     * wears and the block is removed through the citizens' path, and only when it is really gone do its real drops go into the inventory (what does not fit lies at
     * his feet).
     *
     * @return {@link #WORKING} while he works, {@link #REFUSED} when the block may no longer be taken or did not break (nothing gathered), {@link #DONE} once it is
     *   broken.
     */
    private int breakWork(final AbstractEntityCitizen worker, final ServerLevel level, final BlockPos pos, final int toolSlot, final long now, final java.util.function.Predicate<BlockPos> safe)
    {
        final BlockState state = level.getBlockState(pos);
        if (state.isAir())
        {
            return DONE;
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
            return WORKING;
        }
        workUntil = 0L;
        if (!safe.test(pos))
        {
            return REFUSED;
        }
        // the drops are read while the block stands, and kept only once it is gone
        final List<ItemStack> drops = new ArrayList<>();
        for (final ItemStack drop : BlockPosUtil.getBlockDrops(level, pos, 0, tool, worker))
        {
            drops.add(drop.copy());
        }
        CitizenItemUtils.breakBlockWearingToolOf(worker, pos, toolSlot < 0 ? null : worker, toolSlot);
        if (level.getBlockState(pos).getBlock() == state.getBlock())
        {
            LabourStats.log("citizen {} could not break {} at {}: nothing gathered", citizen.getId(), state.getBlock(), pos.toShortString());
            return REFUSED;
        }
        for (final ItemStack drop : drops)
        {
            LabourStats.gathered(drop);
            keep(worker, drop);
        }
        return DONE;
    }

    private void gatherLoose(final AbstractEntityCitizen worker, final ServerLevel level, final Item item, final long now)
    {
        ItemEntity entity = looseId < 0 ? null : level.getEntity(looseId) instanceof ItemEntity e && e.isAlive() ? e : null;
        if (entity == null)
        {
            board.takenEntities().remove(looseId);
            looseId = -1;
            entity = board.sites().nearestLoose(item, worker.blockPosition(), board.takenEntities());
            if (entity == null)
            {
                failed(LabourPlanner.Why.NO_SITE);
                end(false);
                return;
            }
            looseId = entity.getId();
            board.takenEntities().add(looseId);
        }
        if (!EntityNavigationUtils.walkToPos(worker, entity.blockPosition(), 1, true))
        {
            return;
        }
        final ItemStack stack = entity.getItem().copy();
        final ItemStack rest = InventoryUtils.transferItemStackIntoNextBestSlotInItemHandlerWithResult(stack, citizen.getInventory());
        if (rest == null || rest.isEmpty())
        {
            entity.discard();
        }
        else
        {
            entity.setItem(rest);
        }
        board.takenEntities().remove(looseId);
        looseId = -1;
        board.heartbeat(claim);
    }

    // ------------------------------------------------------------------ warehouse

    private void withdraw(final AbstractEntityCitizen worker, final long now)
    {
        if (toolKind != null && DirectedOrders.carries(citizen, toolKind))
        {
            end(true);
            return;
        }
        final LabourPlanner.Withdraw<Item> w = (LabourPlanner.Withdraw<Item>) action;
        final ReservationLedger ledger = RsAccess.ledger(colony.getRequestManager());
        IWareHouse best = null;
        int bestAvailable = 0;
        for (final IWareHouse wareHouse : board.sites().warehouses())
        {
            final int physical = InventoryUtils.getCountFromBuilding(wareHouse, stack -> stack.is(w.item()));
            final int reserved = ledger == null ? 0 : ledger.reservedStock(wareHouse.getID(), stack -> stack.is(w.item()));
            final int available = Math.max(0, physical - reserved);
            if (available > bestAvailable)
            {
                bestAvailable = available;
                best = wareHouse;
            }
        }
        if (best == null || bestAvailable < w.amount())
        {
            failed(LabourPlanner.Why.COVERED);
            end(false);
            return;
        }
        if (!EntityNavigationUtils.walkToBuilding(worker, best))
        {
            return;
        }
        final ItemStorage key = new ItemStorage(new ItemStack(w.item()));
        if (ledger != null)
        {
            ledger.reserveStock(best.getID(), null, key, w.amount(), claim.token(), "labourer " + citizen.getName(), ReservationReason.LABOUR, now);
        }
        final int moved = ItemMover.move(best.getItemHandlerCap(), citizen.getInventory(), stack -> stack.is(w.item()), w.amount());
        if (ledger != null && moved > 0)
        {
            ledger.commitStock(claim.token(), best.getID(), key, moved, now);
        }
        board.heartbeat(claim);
        go(Phase.THINK, now);
    }

    // ------------------------------------------------------------------ craft

    private void craft(final AbstractEntityCitizen worker, final ServerLevel level, final long now)
    {
        final LabourPlanner.Craft<Item> c = (LabourPlanner.Craft<Item>) action;
        final boolean pocket = c.recipe().small() && LabourSettings.pocketCrafting();
        if (!pocket)
        {
            if (post == null)
            {
                post = board.sites().table(worker.blockPosition(), board.takenTables());
                if (post == null)
                {
                    failed(LabourPlanner.Why.NO_TABLE);
                    end(false);
                    return;
                }
                board.takenTables().add(post);
            }
            if (!EntityNavigationUtils.walkToPos(worker, post, 2, true))
            {
                return;
            }
            WorkerUtil.faceBlock(post, worker);
        }
        if (craftUntil == 0L)
        {
            craftUntil = now + Math.max(5, (long) ((20 + 10 * c.times()) / LabourSettings.workScale()));
        }
        if (now < craftUntil)
        {
            if (now - lastSwing >= 15 && post != null)
            {
                lastSwing = now;
                CitizenItemUtils.hitBlockWithToolInHand(worker, post, false);
            }
            return;
        }
        craftUntil = 0L;
        if (!craftNow(worker, c))
        {
            if (++strikes >= 3)
            {
                end(false);
                return;
            }
        }
        releasePost();
        board.heartbeat(claim);
        go(Phase.THINK, now);
    }

    private boolean craftNow(final AbstractEntityCitizen worker, final LabourPlanner.Craft<Item> c)
    {
        final List<Item> picks = new ArrayList<>();
        for (final LabourPlanner.Group<Item> group : c.recipe().inputs())
        {
            final int need = group.count() * c.times();
            Item pick = null;
            int most = 0;
            for (final Item alt : group.alternatives())
            {
                final int have = count(alt);
                if (have >= need && have > most)
                {
                    pick = alt;
                    most = have;
                }
            }
            if (pick == null)
            {
                return false;
            }
            picks.add(pick);
        }
        // the same item may serve two groups: check the totals before taking anything
        final Map<Item, Integer> totals = new HashMap<>();
        for (int i = 0; i < picks.size(); i++)
        {
            totals.merge(picks.get(i), c.recipe().inputs().get(i).count() * c.times(), Integer::sum);
        }
        for (final Map.Entry<Item, Integer> entry : totals.entrySet())
        {
            if (count(entry.getKey()) < entry.getValue())
            {
                return false;
            }
        }
        for (final Map.Entry<Item, Integer> entry : totals.entrySet())
        {
            take(entry.getKey(), entry.getValue());
        }
        // what the recipe itself gives (with whatever it carries), never a stack made up from the item
        final ItemStack made = LabourRecipes.result(c.recipe().id());
        final ItemStack template = made.isEmpty() || !made.is(c.recipe().output()) ? new ItemStack(c.recipe().output()) : made;
        int left = c.recipe().outputCount() * c.times();
        while (left > 0)
        {
            final int n = Math.min(left, template.getMaxStackSize());
            keep(worker, template.copyWithCount(n));
            left -= n;
        }
        return true;
    }

    // ------------------------------------------------------------------ smelt

    @Nullable
    private AbstractFurnaceBlockEntity furnace(final ServerLevel level)
    {
        final BlockEntity entity = post == null ? null : level.getBlockEntity(post);
        return entity instanceof AbstractFurnaceBlockEntity furnace ? furnace : null;
    }

    private void smeltLoad(final AbstractEntityCitizen worker, final ServerLevel level, final long now)
    {
        final LabourPlanner.Smelt<Item> s = (LabourPlanner.Smelt<Item>) action;
        if (post == null)
        {
            post = board.sites().furnace(worker.blockPosition(), board.takenFurnaces());
            if (post == null)
            {
                LabourStats.log("no furnace found from {}", worker.blockPosition());
                failed(LabourPlanner.Why.NO_FURNACE);
                end(false);
                return;
            }
            board.takenFurnaces().add(post);
        }
        if (!EntityNavigationUtils.walkToPos(worker, post, 2, true))
        {
            return;
        }
        final AbstractFurnaceBlockEntity furnace = furnace(level);
        if (furnace == null)
        {
            releasePost();
            return;
        }
        if (!furnace.getItem(0).isEmpty() || !furnace.getItem(1).isEmpty() || !furnace.getItem(2).isEmpty())
        {
            // somebody's work is in it: not touched
            LabourStats.log("furnace {} is in use: {} / {} / {}", post, furnace.getItem(0), furnace.getItem(1), furnace.getItem(2));
            board.sites().invalidate();
            releasePost();
            failed(LabourPlanner.Why.NO_FURNACE);
            end(false);
            return;
        }
        Item input = null;
        for (final Item alt : s.recipe().inputs().get(0).alternatives())
        {
            if (count(alt) >= s.count() * s.recipe().inputs().get(0).count())
            {
                input = alt;
                break;
            }
        }
        if (input == null || count(s.fuel()) < s.fuelCount() + (input == s.fuel() ? s.count() * s.recipe().inputs().get(0).count() : 0))
        {
            releasePost();
            go(Phase.THINK, now);
            return;
        }
        final int inputCount = s.count() * s.recipe().inputs().get(0).count();
        take(input, inputCount);
        take(s.fuel(), s.fuelCount());
        furnace.setItem(0, new ItemStack(input, inputCount));
        furnace.setItem(1, new ItemStack(s.fuel(), s.fuelCount()));
        furnace.setChanged();
        smeltOutput = s.recipe().output();
        smeltExpect = s.count() * s.recipe().outputCount();
        smeltUntil = now + (long) s.count() * 200L + 600L;
        nextStep = now + 20;
        go(Phase.SMELT_WAIT, now);
    }

    private void smeltWait(final AbstractEntityCitizen worker, final ServerLevel level, final long now)
    {
        final AbstractFurnaceBlockEntity furnace = furnace(level);
        nextStep = now + 20;
        if (furnace == null)
        {
            releasePost();
            go(Phase.THINK, now);
            return;
        }
        final ItemStack result = furnace.getItem(2);
        final boolean done = result.getCount() >= smeltExpect || (furnace.getItem(0).isEmpty() && !result.isEmpty());
        if (!done && now < smeltUntil)
        {
            return;
        }
        if (!result.isEmpty() && result.is(smeltOutput))
        {
            keep(worker, furnace.removeItem(2, result.getCount()));
        }
        // what is left of the fuel and input comes back
        for (final int slot : new int[] {0, 1})
        {
            final ItemStack left = furnace.getItem(slot);
            if (!left.isEmpty())
            {
                keep(worker, furnace.removeItem(slot, left.getCount()));
            }
        }
        furnace.setChanged();
        releasePost();
        board.heartbeat(claim);
        go(Phase.THINK, now);
    }

    // ------------------------------------------------------------------ the Architect's Cutter

    /**
     * At a free cutter of the colony: as many runs as he holds the materials for, each the recipe's own result of his own material stacks. The materials are taken
     * only once the result is known to be exactly the requested stack; a wrong result takes nothing and makes nothing.
     */
    private void cut(final AbstractEntityCitizen worker, final ServerLevel level, final long now)
    {
        if (cutJob == null)
        {
            failed(LabourPlanner.Why.WRONG_VARIANT);
            end(false);
            return;
        }
        if (post == null)
        {
            post = board.sites().cutter(worker.blockPosition(), board.takenCutters());
            if (post == null)
            {
                failed(LabourPlanner.Why.NO_CUTTER);
                end(false);
                return;
            }
            board.takenCutters().add(post);
        }
        if (!LabourSites.cutterBlock(level.getBlockState(post)))
        {
            releasePost();
            go(Phase.THINK, now);
            return;
        }
        if (!EntityNavigationUtils.walkToPos(worker, post, 2, true))
        {
            return;
        }
        WorkerUtil.faceBlock(post, worker);
        if (cutUntil == 0L)
        {
            cutUntil = now + Math.max(5, (long) ((20 + 10 * cutRuns) / LabourSettings.workScale()));
        }
        if (now < cutUntil)
        {
            if (now - lastSwing >= 15)
            {
                lastSwing = now;
                CitizenItemUtils.hitBlockWithToolInHand(worker, post, false);
            }
            return;
        }
        cutUntil = 0L;
        final int made = cutNow(worker, level);
        releasePost();
        if (made <= 0)
        {
            if (++strikes >= 3)
            {
                failed(LabourPlanner.Why.WRONG_VARIANT);
                end(false);
                return;
            }
        }
        board.heartbeat(claim);
        go(Phase.THINK, now);
    }

    /**
     * @return how many of the exact stack were made.
     */
    private int cutNow(final AbstractEntityCitizen worker, final ServerLevel level)
    {
        final List<ItemStack> actual = new ArrayList<>();
        for (final Item slot : cutJob.slots())
        {
            actual.add(slot == net.minecraft.world.item.Items.AIR ? ItemStack.EMPTY : firstMaterial(slot));
        }
        final ItemStack result = LabourCutter.assemble(level, cutJob, actual, claim.exact());
        final LabourCutter.Batch<Item> batch = LabourCutter.batch(cutJob.slots(), materialsOf(citizen, claim), cutRuns, result.isEmpty() ? 0 : result.getCount(), !result.isEmpty(),
          net.minecraft.world.item.Items.AIR);
        if (batch.runs() <= 0)
        {
            LabourStats.log("citizen {} could not cut {} (result {})", citizen.getId(), claim.exact(), result);
            return 0;
        }
        for (final Map.Entry<Item, Integer> entry : batch.take().entrySet())
        {
            takeMaterial(entry.getKey(), entry.getValue());
        }
        int left = batch.produced();
        while (left > 0)
        {
            final int n = Math.min(left, result.getMaxStackSize());
            keep(worker, result.copyWithCount(n));
            left -= n;
        }
        LabourStats.log("citizen {} cut {} runs: {}x {}", citizen.getId(), batch.runs(), batch.produced(), claim.exact());
        return batch.produced();
    }

    private ItemStack firstMaterial(final Item item)
    {
        final var inventory = citizen.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.is(item) && !claim.accepts(stack) && stack.getComponentsPatch().isEmpty())
            {
                return stack.copyWithCount(1);
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Takes plain material stacks of the item (never the claim's own made stacks).
     */
    private void takeMaterial(final Item item, final int amount)
    {
        int left = amount;
        final var inventory = citizen.getInventory();
        for (int slot = 0; slot < inventory.getSlots() && left > 0; slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.is(item) && !claim.accepts(stack) && stack.getComponentsPatch().isEmpty())
            {
                left -= inventory.extractItem(slot, Math.min(left, stack.getCount()), false).getCount();
            }
        }
    }

    // ------------------------------------------------------------------ hand in

    @Nullable
    private IBuilding destination()
    {
        if (claim.source() == LabourClaim.Source.STOCKPILE)
        {
            final BlockPos from = citizen.getEntity().map(AbstractEntityCitizen::blockPosition).orElse(claim.destination());
            return board.sites().warehouses().stream().min(Comparator.comparingDouble(w -> w.getID().distSqr(from))).orElse(null);
        }
        return colony.getServerBuildingManager().getBuilding(claim.destination());
    }

    private void deliver(final AbstractEntityCitizen worker, final long now)
    {
        final IBuilding building = destination();
        if (building == null)
        {
            end(false);
            return;
        }
        if (!EntityNavigationUtils.walkToBuilding(worker, building))
        {
            return;
        }
        final int have = countClaimed();
        final int give = Math.min(have, claim.amount());
        if (give <= 0)
        {
            go(Phase.THINK, now);
            return;
        }
        final int moved = ItemMover.move(citizen.getInventory(), building.getItemHandlerCap(), claim::accepts, give);
        if (moved <= 0)
        {
            end(false);
            return;
        }
        board.handedIn(claim, moved);
        board.heartbeat(claim);
        go(Phase.UNLOAD, now);
    }

    /**
     * @return the stacks he carries of the basic goods (what labour gathers or makes, and what the claim brings), his working tools not counted.
     */
    private List<ItemStack> surplus()
    {
        final List<ItemStack> surplus = new ArrayList<>();
        final var inventory = citizen.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && (LabourRules.craftable(stack.getItem()) || LabourRules.looseItem(stack) || LabourRules.stripResult(stack.getItem()) || claim.accepts(stack)
                                       || LabourRules.smeltable(stack.getItem(), com.minecolonies.api.colony.labour.LabourModes.SmeltMode.IRON) || LabourRules.shaftFind(stack))
                  && !LabourRules.isTool("pickaxe", stack.getItem()) && !LabourRules.isTool("axe", stack.getItem()) && !LabourRules.isTool("shovel", stack.getItem()))
            {
                surplus.add(stack);
            }
        }
        return surplus;
    }

    /**
     * What the labourer still carries of the basic goods (surplus logs, planks he made too many of) goes into a warehouse, if the colony has one; his working tools stay.
     */
    private void unload(final AbstractEntityCitizen worker, final long now)
    {
        final List<ItemStack> surplus = surplus();
        final var inventory = citizen.getInventory();
        final IWareHouse warehouse = surplus.isEmpty() || claim.source() == LabourClaim.Source.SELF ? null
                                       : board.sites().warehouses().stream().min(Comparator.comparingDouble(w -> w.getID().distSqr(worker.blockPosition()))).orElse(null);
        if (warehouse == null)
        {
            end(true);
            return;
        }
        if (!EntityNavigationUtils.walkToBuilding(worker, warehouse))
        {
            return;
        }
        for (final ItemStack stack : surplus)
        {
            // the same item and components only: a made variant never travels as another
            final ItemStack sample = stack.copyWithCount(1);
            ItemMover.move(inventory, warehouse.getItemHandlerCap(), s -> ItemStack.isSameItemSameComponents(s, sample),
              InventoryUtils.getItemCountInItemHandler(inventory, s -> ItemStack.isSameItemSameComponents(s, sample)));
        }
        end(true);
    }
}
