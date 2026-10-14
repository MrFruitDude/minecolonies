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
        THINK, GATHER, WITHDRAW, CRAFT, SMELT_LOAD, SMELT_WAIT, DELIVER, UNLOAD, END
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

    private LabourPlanner.Action<Item> action;

    // gather
    private LabourSites.TreeSite tree;
    private List<BlockPos>       logsLeft;
    private BlockPos             block;
    private int                  looseId = -1;
    private long                 workUntil;
    private long                 lastSwing;

    // craft and smelt
    private BlockPos post;
    private long     craftUntil;
    private long     smeltUntil;
    private int      smeltExpect;
    private Item     smeltOutput;

    public LabourSession(final String taskId, final Colony colony, final ICitizenData citizen, final LabourBoard board, final LabourClaim claim)
    {
        this.taskId = taskId;
        this.colony = colony;
        this.citizen = citizen;
        this.board = board;
        this.claim = claim;
        this.actionStart = colony.getWorld() == null ? 0L : colony.getWorld().getGameTime();
    }

    // ------------------------------------------------------------------ the session contract

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
        return claim.request() == null || board.stillWaiting(claim.request());
    }

    @Override
    public Component describe()
    {
        final Component what = new ItemStack(claim.target()).getHoverName();
        final String key = switch (phase)
        {
            case GATHER -> "com.minecolonies.coremod.labour.task.gather";
            case WITHDRAW -> "com.minecolonies.coremod.labour.task.withdraw";
            case CRAFT -> "com.minecolonies.coremod.labour.task.craft";
            case SMELT_LOAD, SMELT_WAIT -> "com.minecolonies.coremod.labour.task.smelt";
            case DELIVER, UNLOAD, END -> "com.minecolonies.coremod.labour.task.deliver";
            default -> "com.minecolonies.coremod.labour.task.plan";
        };
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
        cleanup();
        board.release(claim, ok, !ok && reason == IdleStopReason.TASK_INVALID);
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
        if (block != null)
        {
            board.takenSurface().remove(block);
            block = null;
        }
        if (looseId >= 0)
        {
            board.takenEntities().remove(looseId);
            looseId = -1;
        }
        releasePost();
    }

    private void releasePost()
    {
        if (post != null)
        {
            board.takenTables().remove(post);
            board.takenFurnaces().remove(post);
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
            case DELIVER -> deliver(worker, now);
            case UNLOAD -> unload(worker, now);
            case END -> end(true);
        }
    }

    private void go(final Phase next, final long now)
    {
        phase = next;
        actionStart = now;
    }

    private void think(final AbstractEntityCitizen worker, final long now)
    {
        if (++thinks > MAX_THINKS || board.coverage().covered(claim.target()))
        {
            end(false);
            return;
        }
        final LabourEnv env = new LabourEnv(colony, board, claim.source() != LabourClaim.Source.STOCKPILE);
        final LabourPlanner.Result<Item> result = LabourPlanner.next(env, claim.target(), claim.amount(), inventoryOf(citizen));
        if (result instanceof LabourPlanner.Done<Item>)
        {
            go(Phase.DELIVER, now);
            return;
        }
        if (result instanceof LabourPlanner.Blocked<Item> blocked)
        {
            board.planFailed(claim, blocked.why());
            end(false);
            return;
        }
        action = ((LabourPlanner.Do<Item>) result).action();
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

    // ------------------------------------------------------------------ gather

    private void gather(final AbstractEntityCitizen worker, final ServerLevel level, final long now)
    {
        final LabourPlanner.Gather<Item> g = (LabourPlanner.Gather<Item>) action;
        final Item item = g.gather().item();
        if (count(item) >= g.until())
        {
            cleanup();
            go(Phase.THINK, now);
            return;
        }
        switch (g.gather().kind())
        {
            case LOG -> gatherLog(worker, level, item, now);
            case SURFACE, STONE -> gatherBlock(worker, level, g, now);
            case LOOSE -> gatherLoose(worker, level, item, now);
        }
    }

    private void gatherLog(final AbstractEntityCitizen worker, final ServerLevel level, final Item item, final long now)
    {
        if (tree == null)
        {
            tree = board.sites().nearestTree(item, worker.blockPosition(), board.takenTrees());
            if (tree == null)
            {
                board.planFailed(claim, LabourPlanner.Why.NO_SITE);
                end(false);
                return;
            }
            board.takenTrees().add(tree.base());
            logsLeft = new ArrayList<>(tree.logs());
            workUntil = 0L;
        }
        logsLeft.removeIf(pos -> !LabourRules.log(level.getBlockState(pos)));
        if (logsLeft.isEmpty())
        {
            plantSapling(worker, level, tree.base());
            board.sites().invalidate();
            board.takenTrees().remove(tree.base());
            tree = null;
            board.heartbeat(claim);
            return;
        }
        if (!EntityNavigationUtils.walkToPos(worker, tree.base(), 3, true))
        {
            return;
        }
        final BlockPos log = logsLeft.get(0);
        if (breakWork(worker, level, log, -1, now))
        {
            logsLeft.remove(0);
            board.heartbeat(claim);
        }
    }

    private void plantSapling(final AbstractEntityCitizen worker, final ServerLevel level, final BlockPos base)
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

    private void gatherBlock(final AbstractEntityCitizen worker, final ServerLevel level, final LabourPlanner.Gather<Item> g, final long now)
    {
        final Item item = g.gather().item();
        if (block == null)
        {
            block = board.sites().nearestSurface(item, worker.blockPosition(), board.takenSurface());
            if (block == null)
            {
                board.planFailed(claim, LabourPlanner.Why.NO_SITE);
                end(false);
                return;
            }
            board.takenSurface().add(block);
            workUntil = 0L;
        }
        if (!LabourRules.surface(level.getBlockState(block)) && !LabourRules.stone(level.getBlockState(block)))
        {
            board.takenSurface().remove(block);
            block = null;
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
        if (breakWork(worker, level, block, toolSlot, now))
        {
            board.takenSurface().remove(block);
            block = null;
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
     * Works on a block until it breaks: the time a hand or the tool takes, then the drops go into the inventory and the tool wears through the citizens' damage path.
     *
     * @return true once the block is broken.
     */
    private boolean breakWork(final AbstractEntityCitizen worker, final ServerLevel level, final BlockPos pos, final int toolSlot, final long now)
    {
        final BlockState state = level.getBlockState(pos);
        if (state.isAir())
        {
            return true;
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
            return false;
        }
        workUntil = 0L;
        for (final ItemStack drop : BlockPosUtil.getBlockDrops(level, pos, 0, tool, worker))
        {
            LabourStats.gathered(drop);
            keep(worker, drop);
        }
        CitizenItemUtils.breakBlockWearingToolOf(worker, pos, toolSlot < 0 ? null : worker, toolSlot);
        return true;
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
                board.planFailed(claim, LabourPlanner.Why.NO_SITE);
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
            board.planFailed(claim, LabourPlanner.Why.COVERED);
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
                    board.planFailed(claim, LabourPlanner.Why.NO_TABLE);
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
        int left = c.recipe().outputCount() * c.times();
        while (left > 0)
        {
            final int n = Math.min(left, c.recipe().output().getDefaultMaxStackSize());
            keep(worker, new ItemStack(c.recipe().output(), n));
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
                board.planFailed(claim, LabourPlanner.Why.NO_FURNACE);
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
            board.sites().invalidate();
            releasePost();
            board.planFailed(claim, LabourPlanner.Why.NO_FURNACE);
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
        final int have = count(claim.target());
        final int give = Math.min(have, claim.amount());
        if (give <= 0)
        {
            go(Phase.THINK, now);
            return;
        }
        final int moved = ItemMover.move(citizen.getInventory(), building.getItemHandlerCap(), stack -> stack.is(claim.target()), give);
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
     * What the labourer still carries of the basic goods (surplus logs, planks he made too many of) goes into a warehouse, if the colony has one; his working tools stay.
     */
    private void unload(final AbstractEntityCitizen worker, final long now)
    {
        final List<ItemStack> surplus = new ArrayList<>();
        final var inventory = citizen.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && (LabourRules.craftable(stack.getItem()) || LabourRules.looseItem(stack) || LabourRules.smeltable(stack.getItem(), com.minecolonies.api.colony.labour.LabourModes.SmeltMode.IRON))
                  && !LabourRules.isTool("pickaxe", stack.getItem()) && !LabourRules.isTool("axe", stack.getItem()) && !LabourRules.isTool("shovel", stack.getItem()))
            {
                surplus.add(stack);
            }
        }
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
            final Item item = stack.getItem();
            ItemMover.move(inventory, warehouse.getItemHandlerCap(), s -> s.is(item), count(item));
        }
        end(true);
    }
}
