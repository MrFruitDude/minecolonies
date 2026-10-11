package com.minecolonies.core.entity.ai.workers;

import com.google.common.reflect.TypeToken;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder;
import com.minecolonies.core.colony.workorders.scaffold.ScaffoldLedger;
import com.minecolonies.core.colony.workorders.scaffold.ScaffoldSweeper;
import com.minecolonies.core.entity.ai.workers.util.BuildReach;
import com.minecolonies.core.entity.ai.workers.util.ReachSpots;
import com.minecolonies.core.entity.ai.workers.util.ScaffoldPlanner;
import com.minecolonies.core.entity.ai.workers.util.WorkerSettings;
import com.minecolonies.core.util.ItemMover;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The scaffolding work of a builder. When a block has no spot to stand within reach of it (a high wall, a roof, an overhang, a gap), the
 * builder plans a tower next to it ({@link ScaffoldPlanner}), takes the scaffolding items from his hut (they are requested like any other
 * material), puts the tower up from the bottom, climbs it, and works from the top. A tower stays while it is of use; when it is not, or when the
 * stage ends, he takes it down from the top, one block at a time, and every block is recorded in the ledger of the work order, so whatever
 * happens to the builder, the colony can take it down ({@link ScaffoldSweeper}).
 * <p>
 * Everything that cannot be done (no scaffolding items for a while, no place for a tower, a tower that cannot be reached) ends in the
 * unstuck ladder of the structure AI (teleport, defer): the builder never waits for ever.
 */
public final class BuilderScaffolding
{
    private enum Phase
    {
        IDLE, WAIT_STOCK, GATHER, BUILD, DISMANTLE
    }

    /** Ticks a builder waits for scaffolding items that were requested, before he goes on without a tower. */
    static final int STOCK_TIMEOUT = 1200;

    /** After that long without a tower being possible, the next try. */
    static final int DENY_TICKS = 3000;

    /** Ticks a tower has to be of no use before it comes down. */
    static final int IDLE_GRACE = 100;

    /** A tower comes down in the middle of the stage only if the builder is this close to it (blocks). */
    static final double DISMANTLE_NEAR = 12.0D;

    /** Ticks without progress on one block before the colony takes the rest of a tower down without the builder. */
    static final int WORK_TIMEOUT = 700;

    private final AbstractEntityAIStructure<?, ?> ai;

    private Phase  phase = Phase.IDLE;
    private long   phaseSince = 0L;
    private long   deniedUntil = 0L;
    private long   nextMaintain = 0L;
    private long   stepSince = 0L;

    private ScaffoldPlanner.Plan plan = null;
    private int                  next = 0;
    private List<BlockPos>       cellsToTakeDown = new ArrayList<>();
    private boolean              needsRescan = false;
    private boolean              takeDownAll = false;
    private final Map<BlockPos, Long> idleSince = new HashMap<>();

    // for the tests and the logs
    public static volatile int towersBuilt = 0;
    public static volatile int towersTakenDown = 0;
    public static volatile int cellsPlaced = 0;
    public static final java.util.concurrent.ConcurrentHashMap<BlockPos, Integer> placedBy = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Only for tests: forgets the counters.
     */
    public static void resetCounters()
    {
        towersBuilt = 0;
        towersTakenDown = 0;
        cellsPlaced = 0;
        placedBy.clear();
    }

    public BuilderScaffolding(final AbstractEntityAIStructure<?, ?> ai)
    {
        this.ai = ai;
    }

    public String debug()
    {
        return phase + (plan == null ? "" : " plan=" + plan.cells().size() + "@" + next + " stand=" + plan.stand().toShortString()) + " down=" + cellsToTakeDown.size() + " denied=" + (deniedUntil > ai.world.getGameTime());
    }

    private void log(final String message)
    {
        if (com.minecolonies.core.entity.ai.workers.util.ReachAudit.isEnabled())
        {
            Log.getLogger().info("SCAFFOLD {} {}", ai.worker.getName().getString(), message);
        }
    }

    /**
     * Log line for the tests (when the reach audit is on).
     */
    public void note(final String message)
    {
        log(message);
    }

    public boolean busy()
    {
        return phase != Phase.IDLE;
    }

    /**
     * Whether the settings let builders use scaffolding at all.
     */
    public static boolean enabled()
    {
        return WorkerSettings.physicalReach() && WorkerSettings.scaffolding();
    }

    // ------------------------------------------------------------------ the world as the planner sees it

    private final class WorldGrid implements ScaffoldPlanner.Grid
    {
        private final ScaffoldLedger ledger;

        private WorldGrid(final ScaffoldLedger ledger)
        {
            this.ledger = ledger;
        }

        @Override
        public boolean free(final BlockPos pos)
        {
            if (!WorldUtil.isBlockLoaded(ai.world, pos) || !WorldUtil.isInWorldHeight(pos.getY(), ai.world))
            {
                return false;
            }
            final BlockState state = ai.world.getBlockState(pos);
            if (state.is(Blocks.SCAFFOLDING))
            {
                return false;
            }
            if (state.getFluidState().is(FluidTags.LAVA) || ai.world.getFluidState(pos.below()).is(FluidTags.LAVA))
            {
                // never in lava, and never a bridge right over it
                return false;
            }
            return state.isAir() || (state.canBeReplaced() && state.getCollisionShape(ai.world, pos).isEmpty());
        }

        @Override
        public boolean ground(final BlockPos pos)
        {
            if (!WorldUtil.isBlockLoaded(ai.world, pos))
            {
                return false;
            }
            final BlockState state = ai.world.getBlockState(pos);
            return !state.is(Blocks.SCAFFOLDING) && state.isFaceSturdy(ai.world, pos, Direction.UP);
        }

        @Override
        public boolean scaffold(final BlockPos pos)
        {
            if (!ledger.contains(pos))
            {
                return false;
            }
            final BlockState state = ai.world.getBlockState(pos);
            return state.is(Blocks.SCAFFOLDING) && state.getValue(ScaffoldingBlock.DISTANCE) == 0;
        }

        @Override
        public boolean reachable(final BlockPos feet)
        {
            return ai.canWalkTo(feet);
        }

        @Override
        public int penalty(final BlockPos pos)
        {
            return ai.scaffoldPenalty(pos);
        }

        @Override
        public int minY()
        {
            return ai.world.getMinY();
        }

        @Override
        public int maxY()
        {
            return ai.world.getMaxY();
        }
    }

    // ------------------------------------------------------------------ items

    private int inInventory()
    {
        return InventoryUtils.getItemCountInItemHandler(ai.worker.getInventoryCitizen(), stack -> stack.is(Items.SCAFFOLDING));
    }

    private int inHut()
    {
        final AbstractBuildingStructureBuilder hut = ai.building;
        return hut.getItemHandlerCap() == null ? 0 : InventoryUtils.getItemCountInItemHandler(hut.getItemHandlerCap(), stack -> stack.is(Items.SCAFFOLDING));
    }

    /**
     * Makes sure scaffolding items are on their way for a structure that is tall: they are requested like any other material (the colony
     * delivers or crafts them), so they are in the hut when the first tower is needed.
     */
    public void ensureStock(final int wanted)
    {
        if (!enabled() || inHut() + inInventory() >= wanted)
        {
            return;
        }
        final AbstractBuildingStructureBuilder hut = ai.building;
        for (final IRequest<? extends Stack> request : hut.getOpenRequestsOfType(-1, TypeToken.of(Stack.class)))
        {
            if (request.getRequest().getStack().is(Items.SCAFFOLDING))
            {
                return;
            }
        }
        final int missing = wanted - inHut() - inInventory();
        hut.createRequest(new Stack(new ItemStack(Items.SCAFFOLDING), Math.max(1, missing), 1), true);
    }

    // ------------------------------------------------------------------ starting a tower

    /**
     * A block has no spot within reach: puts up a tower for it.
     *
     * @param target the block.
     * @return true if a tower is on its way (the work goes on in {@link #tick()}).
     */
    public boolean start(final BlockPos target)
    {
        if (!enabled())
        {
            return false;
        }
        if (phase != Phase.IDLE)
        {
            return true;
        }
        final long now = ai.world.getGameTime();
        if (now < deniedUntil)
        {
            return false;
        }
        final IBuilderWorkOrder order = ai.scaffoldOrder();
        if (order == null)
        {
            return false;
        }

        final int stock = WorkerSettings.scaffoldStock();
        final List<BlockPos> blocks = ai.workAhead(target);
        ScaffoldPlanner.Plan found = ScaffoldPlanner.plan(new WorldGrid(order.getScaffolds()), blocks, WorkerSettings.reach(), ai.worker.getEyeHeight(), null, stock);
        if (found == null && !order.getScaffolds().isEmpty())
        {
            // no room in the stock: towers that are of no use come down first (their items go back to the hut), then another try
            ScaffoldSweeper.sweep(ai.worker.getCitizenColonyHandler().getColonyOrRegister(), order.getScaffolds(), cell -> true, 64);
            found = ScaffoldPlanner.plan(new WorldGrid(order.getScaffolds()), blocks, WorkerSettings.reach(), ai.worker.getEyeHeight(), null, stock);
        }
        if (found == null)
        {
            log("no scaffolding possible for " + target);
            deniedUntil = now + DENY_TICKS;
            return false;
        }

        if (found.cells().isEmpty())
        {
            // the spot is there already, the builder just cannot get to it: a tower is no help
            log("a spot " + found.stand() + " is there but cannot be reached");
            deniedUntil = now + 200;
            return false;
        }
        plan = found;
        next = 0;
        towersBuilt++;
        phaseSince = now;
        stepSince = now;
        ai.resetScaffoldWalk();
        if (inInventory() >= plan.cells().size())
        {
            phase = Phase.BUILD;
        }
        else if (inInventory() + inHut() >= plan.cells().size())
        {
            phase = Phase.GATHER;
        }
        else
        {
            ensureStock(Math.max(stock, plan.cells().size()));
            phase = Phase.WAIT_STOCK;
        }
        ai.worker.getCitizenData().setStatusPosition(target);
        log("plan for " + target + ": cells=" + plan.cells() + " stand=" + plan.stand() + " covered=" + plan.covered() + " phase=" + phase);
        return true;
    }

    // ------------------------------------------------------------------ the AI step

    /**
     * The scaffolding work of this AI step.
     *
     * @return true if it took the step (the builder is busy with scaffolding).
     */
    public boolean tick()
    {
        final IBuilderWorkOrder order = ai.scaffoldOrder();
        if (order == null)
        {
            reset();
            return false;
        }
        final ScaffoldLedger ledger = order.getScaffolds();
        final long now = ai.world.getGameTime();
        switch (phase)
        {
            case WAIT_STOCK:
                return waitForStock(now);
            case GATHER:
                return gather(now);
            case BUILD:
                return build(order, ledger, now);
            case DISMANTLE:
                return takeDown(order, ledger, now);
            default:
                return maintain(order, ledger, now);
        }
    }

    private boolean waitForStock(final long now)
    {
        if (plan == null)
        {
            phase = Phase.IDLE;
            return false;
        }
        if (inInventory() + inHut() >= plan.cells().size())
        {
            phase = Phase.GATHER;
            phaseSince = now;
            return true;
        }
        if (now - phaseSince > STOCK_TIMEOUT)
        {
            log("no scaffolding items after " + STOCK_TIMEOUT + " ticks, going on without a tower");
            deniedUntil = now + DENY_TICKS;
            abort();
            return false;
        }
        ai.setDelay(20);
        return true;
    }

    private boolean gather(final long now)
    {
        if (plan == null)
        {
            phase = Phase.IDLE;
            return false;
        }
        if (!ai.walkToBuilding())
        {
            if (now - phaseSince > STOCK_TIMEOUT)
            {
                deniedUntil = now + DENY_TICKS;
                abort();
                return false;
            }
            return true;
        }
        final AbstractBuildingStructureBuilder hut = ai.building;
        final int want = Math.max(plan.cells().size(), Math.min(WorkerSettings.scaffoldStock(), inHut() + inInventory())) - inInventory();
        if (want > 0 && hut.getItemHandlerCap() != null)
        {
            ItemMover.move(hut.getItemHandlerCap(), ai.worker.getInventoryCitizen(), stack -> stack.is(Items.SCAFFOLDING), want);
        }
        if (inInventory() < plan.cells().size())
        {
            // somebody else took them: ask again
            phase = Phase.WAIT_STOCK;
            phaseSince = now;
            ensureStock(Math.max(WorkerSettings.scaffoldStock(), plan.cells().size()));
            return true;
        }
        phase = Phase.BUILD;
        phaseSince = now;
        stepSince = now;
        return true;
    }

    private boolean build(final IBuilderWorkOrder order, final ScaffoldLedger ledger, final long now)
    {
        if (plan == null)
        {
            phase = Phase.IDLE;
            return false;
        }
        while (next < plan.cells().size())
        {
            final BlockPos cell = plan.cells().get(next);
            final BlockState state = ai.world.getBlockState(cell);
            if (state.is(Blocks.SCAFFOLDING) && ledger.contains(cell))
            {
                next++;
                continue;
            }
            if (!new WorldGrid(ledger).free(cell))
            {
                // something is in the way now
                log("abort: the place for scaffolding at " + cell + " is taken: " + ai.world.getBlockState(cell));
                abort();
                return false;
            }
            if (inInventory() <= 0)
            {
                phase = Phase.WAIT_STOCK;
                phaseSince = now;
                plan = replanFrom(cell, ledger);
                ensureStock(WorkerSettings.scaffoldStock());
                return true;
            }
            if (now - stepSince > WORK_TIMEOUT)
            {
                log("abort: cannot get to " + cell + " to put up scaffolding, builder at " + ai.worker.blockPosition());
                deniedUntil = now + DENY_TICKS;
                abort();
                return false;
            }
            if (!ai.walkIntoReachFor(cell, () -> List.of(cell), true, null))
            {
                return true;
            }
            if (!place(cell, ledger))
            {
                log("abort: cannot place at " + cell);
                abort();
                return false;
            }
            stepSince = now;
            next++;
            ai.setDelay(Math.max(1, (int) ai.placementTicks()));
            return true;
        }
        // standing: from here the normal work goes on from the top of the tower
        final BlockPos stand = plan.stand();
        log("tower is up, stand " + stand + ", builder at " + ai.worker.blockPosition());
        plan = null;
        phase = Phase.IDLE;
        ai.resetReachWalk();
        ai.workFromScaffold(stand);
        return false;
    }

    /**
     * The remaining cells of the plan if the stock ran out in the middle: the plan stays what it was, only the cells already standing are
     * dropped from it.
     */
    private ScaffoldPlanner.Plan replanFrom(final BlockPos cell, final ScaffoldLedger ledger)
    {
        final List<BlockPos> rest = new ArrayList<>(plan.cells().subList(Math.max(0, next), plan.cells().size()));
        next = 0;
        return new ScaffoldPlanner.Plan(rest, plan.stand(), plan.covered(), plan.cost());
    }

    /**
     * Puts a scaffolding block into the cell: the state the item would give it in the world (stability distance, bottom, water).
     */
    private boolean place(final BlockPos cell, final ScaffoldLedger ledger)
    {
        final int distance = ScaffoldingBlock.getDistance(ai.world, cell);
        if (distance >= ScaffoldingBlock.STABILITY_MAX_DISTANCE)
        {
            return false;
        }
        final BlockState state = Blocks.SCAFFOLDING.defaultBlockState()
                                   .setValue(ScaffoldingBlock.DISTANCE, distance)
                                   .setValue(ScaffoldingBlock.BOTTOM, distance > 0 && !ai.world.getBlockState(cell.below()).is(Blocks.SCAFFOLDING))
                                   .setValue(ScaffoldingBlock.WATERLOGGED, ai.world.getFluidState(cell).is(Fluids.WATER));
        com.minecolonies.core.entity.ai.workers.util.ReachAudit.record(ai.worker, cell);
        InventoryUtils.reduceStackInItemHandler(ai.worker.getInventoryCitizen(), new ItemStack(Items.SCAFFOLDING), 1);
        ai.world.setBlock(cell, state, 3);
        ledger.add(cell, ai.building.getPosition(), distance);
        cellsPlaced++;
        ai.forgetWalkable();
        placedBy.merge(ai.building.getPosition(), 1, Integer::sum);
        ai.worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        ai.worker.queueSound(state.getSoundType().getPlaceSound(), cell, 10, 0);
        return true;
    }

    // ------------------------------------------------------------------ taking it down

    /**
     * Idle and in the middle of the stage: decides if a tower comes down.
     */
    private boolean maintain(final IBuilderWorkOrder order, final ScaffoldLedger ledger, final long now)
    {
        if (takeDownAll)
        {
            return startTakeDown(ledger, null, now);
        }
        if (now < nextMaintain)
        {
            return false;
        }
        nextMaintain = now + 10;
        final BlockPos hut = ai.building.getPosition();
        final List<List<ScaffoldLedger.Cell>> towers = ledger.components(hut);
        if (towers.isEmpty())
        {
            idleSince.clear();
            return false;
        }
        final List<BlockPos> ahead = ai.peekWorkAhead(4);
        final double eye = ai.worker.getEyeHeight();
        final double usable = Math.max(1.0D, WorkerSettings.reach() - ReachSpots.MARGIN);
        final BlockPos feet = ai.worker.blockPosition();
        for (final List<ScaffoldLedger.Cell> tower : towers)
        {
            final BlockPos anchor = tower.get(tower.size() - 1).pos();
            if (inUse(tower, feet, ahead, eye, usable))
            {
                idleSince.remove(anchor);
                continue;
            }
            final long since = idleSince.computeIfAbsent(anchor, k -> now);
            if (now - since < IDLE_GRACE)
            {
                continue;
            }
            // only when he is close, on the ground, and not on his way somewhere else with this tower
            if (!elevatedOn(tower, feet) && nearTower(tower, feet))
            {
                idleSince.remove(anchor);
                return startTakeDown(ledger, tower, now);
            }
        }
        return false;
    }

    private boolean inUse(final List<ScaffoldLedger.Cell> tower, final BlockPos feet, final List<BlockPos> ahead, final double eye, final double usable)
    {
        for (final ScaffoldLedger.Cell cell : tower)
        {
            // he stands on it
            if (feet.below().equals(cell.pos()) || feet.equals(cell.pos()))
            {
                return true;
            }
        }
        if (ahead.isEmpty())
        {
            return false;
        }
        for (final ScaffoldLedger.Cell cell : tower)
        {
            final BlockPos spot = cell.pos().above();
            if (BuildReach.inReachFromFeet(spot, eye, ahead.get(0), usable) && ai.world.getBlockState(spot).isAir() && ai.world.getBlockState(spot.above()).isAir())
            {
                // a spot on it serves the next block
                return true;
            }
        }
        return false;
    }

    private boolean elevatedOn(final List<ScaffoldLedger.Cell> tower, final BlockPos feet)
    {
        int base = Integer.MAX_VALUE;
        for (final ScaffoldLedger.Cell cell : tower)
        {
            base = Math.min(base, cell.pos().getY());
        }
        return feet.getY() > base + 2;
    }

    private boolean nearTower(final List<ScaffoldLedger.Cell> tower, final BlockPos feet)
    {
        for (final ScaffoldLedger.Cell cell : tower)
        {
            if (Math.sqrt(cell.pos().distSqr(feet)) <= DISMANTLE_NEAR)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Asks for the whole scaffolding of this builder to come down (the stage ends, the structure is done).
     */
    public void requestTakeDownAll()
    {
        takeDownAll = true;
    }

    /**
     * Whether the builder still has scaffolding up or is busy with it.
     */
    public boolean hasScaffolding(final IBuilderWorkOrder order)
    {
        return phase != Phase.IDLE || (order != null && !order.getScaffolds().components(ai.building.getPosition()).isEmpty());
    }

    private boolean startTakeDown(final ScaffoldLedger ledger, @Nullable final List<ScaffoldLedger.Cell> tower, final long now)
    {
        final List<BlockPos> list = new ArrayList<>();
        if (tower != null)
        {
            for (final ScaffoldLedger.Cell cell : tower)
            {
                list.add(cell.pos());
            }
        }
        else
        {
            // everything of the order that is still up (helpers' towers too: the stage is over)
            for (final ScaffoldLedger.Cell cell : ledger.topDown(null))
            {
                list.add(cell.pos());
            }
        }
        if (list.isEmpty())
        {
            takeDownAll = false;
            return false;
        }
        cellsToTakeDown = list;
        log("taking down " + list.size() + " blocks, builder at " + ai.worker.blockPosition() + " all=" + (tower == null));
        phase = Phase.DISMANTLE;
        phaseSince = now;
        stepSince = now;
        ai.resetScaffoldWalk();
        towersTakenDown++;
        return true;
    }

    private boolean takeDown(final IBuilderWorkOrder order, final ScaffoldLedger ledger, final long now)
    {
        // forget what is gone
        while (!cellsToTakeDown.isEmpty() && !(ledger.contains(cellsToTakeDown.get(0)) && ai.world.getBlockState(cellsToTakeDown.get(0)).is(Blocks.SCAFFOLDING)))
        {
            ledger.remove(cellsToTakeDown.remove(0));
            stepSince = now;
        }
        if (cellsToTakeDown.isEmpty())
        {
            finishTakeDown();
            return false;
        }
        final BlockPos cell = cellsToTakeDown.get(0);
        if (now - stepSince > WORK_TIMEOUT || !WorldUtil.isBlockLoaded(ai.world, cell))
        {
            // he cannot get there: the colony takes the rest down
            log("cannot get to " + cell + " to take scaffolding down, builder at " + ai.worker.blockPosition());
            ScaffoldSweeper.sweep(ai.worker.getCitizenColonyHandler().getColonyOrRegister(), ledger, c -> cellsToTakeDown.contains(c.pos()), Integer.MAX_VALUE);
            noteRescan(cellsToTakeDown);
            cellsToTakeDown.clear();
            finishTakeDown();
            return false;
        }
        final BlockPos below = cell.below();
        final boolean descent = ledger.contains(below) || ai.world.getBlockState(below).isFaceSturdy(ai.world, below, Direction.UP);
        if (!ai.walkIntoReachFor(cell, () -> List.of(cell), true, feet -> !feet.below().equals(cell) || descent))
        {
            return true;
        }
        // down it comes: no drop, the item goes into his inventory
        com.minecolonies.core.entity.ai.workers.util.ReachAudit.record(ai.worker, cell);
        ai.world.removeBlock(cell, false);
        ai.forgetWalkable();
        ledger.remove(cell);
        cellsToTakeDown.remove(0);
        noteRescan(List.of(cell));
        final ItemStack rest = InventoryUtils.transferItemStackIntoNextBestSlotInItemHandlerWithResult(new ItemStack(Items.SCAFFOLDING), ai.worker.getInventoryCitizen());
        if (!rest.isEmpty())
        {
            net.minecraft.world.level.block.Block.popResource(ai.world, cell, rest);
        }
        stepSince = now;
        ai.worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        ai.setDelay(Math.max(2, (int) ai.placementTicks() / 2));
        if (cellsToTakeDown.isEmpty())
        {
            finishTakeDown();
        }
        return true;
    }

    private void noteRescan(final List<BlockPos> cells)
    {
        for (final BlockPos cell : cells)
        {
            if (ai.scaffoldPenalty(cell) >= 40)
            {
                // a position of the building was held back because the scaffolding stood in it
                needsRescan = true;
            }
        }
    }

    private void finishTakeDown()
    {
        cellsToTakeDown = new ArrayList<>();
        phase = Phase.IDLE;
        takeDownAll = false;
        ai.resetScaffoldWalk();
        ai.resetReachWalk();
    }

    /**
     * Whether positions of the building were held back by scaffolding that came down now, so the stage has to look for them again.
     */
    public boolean consumeRescan()
    {
        final boolean was = needsRescan;
        needsRescan = false;
        return was;
    }

    // ------------------------------------------------------------------ end of the work

    private void abort()
    {
        plan = null;
        phase = Phase.IDLE;
        cellsToTakeDown = new ArrayList<>();
        ai.resetScaffoldWalk();
        ai.resetReachWalk();
    }

    /**
     * The structure is done, cancelled or released: forgets what the builder was doing. What stands in the world is in the ledger, and
     * comes down with the order; the scaffolding items he carries go back to his hut.
     */
    public void reset()
    {
        plan = null;
        phase = Phase.IDLE;
        cellsToTakeDown = new ArrayList<>();
        takeDownAll = false;
        needsRescan = false;
        idleSince.clear();
        deniedUntil = 0L;
        nextMaintain = 0L;
    }

    /**
     * Moves the scaffolding items of the builder's inventory into his hut.
     *
     * @return the number of items moved.
     */
    public int returnStock()
    {
        final AbstractBuildingStructureBuilder hut = ai.building;
        if (hut == null || hut.getItemHandlerCap() == null)
        {
            return 0;
        }
        return ItemMover.move(ai.worker.getInventoryCitizen(), hut.getItemHandlerCap(), stack -> stack.is(Items.SCAFFOLDING), Integer.MAX_VALUE);
    }
}
