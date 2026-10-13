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
        IDLE, WAIT_STOCK, GATHER, BUILD, EXIT, DISMANTLE
    }

    /** Ticks a builder waits for scaffolding items that were requested, before he goes on without a tower. */
    static final int STOCK_TIMEOUT = 600;

    /** After that long without a tower being possible, the next try. */
    static final int DENY_TICKS = 3000;

    /** After that long without a tower being possible while the towers of another builder stand: the next try. */
    static final int OTHERS_DENY_TICKS = 200;

    /** How many of the next blocks the plan of a tower looks at: a tower is picked for what is left to build, not for the next layer. */
    static final int PLAN_LOOKAHEAD = 160;

    /** Ticks a tower has to be of no use before it comes down. */
    static final int IDLE_GRACE = 1200;

    /** Ticks a tower of a helper without work has to be of no use before it comes down. */
    static final int HELPER_IDLE_GRACE = 60;

    /** A tower comes down in the middle of the stage only if the builder is this close to it (blocks). */
    static final double DISMANTLE_NEAR = 12.0D;

    /** Ticks without progress on one block before the colony takes the rest of a tower down without the builder. */
    static final int WORK_TIMEOUT = 500;

    /** Ticks without a spot to take a tower down from, before the colony takes it down. */
    static final int TAKE_DOWN_PATIENCE = 240;

    private final AbstractEntityAIStructure<?, ?> ai;

    private Phase  phase = Phase.IDLE;
    private long   phaseSince = 0L;
    private long   deniedUntil = 0L;
    private long   stockDeniedUntil = 0L;
    private long   escapeDeniedUntil = 0L;
    private BlockPos exitTo = null;
    private BlockPos standAfter = null;
    private long   nextMaintain = 0L;
    private long   stepSince = 0L;

    private ScaffoldPlanner.Plan plan = null;
    private int                  next = 0;
    private List<BlockPos>       cellsToTakeDown = new ArrayList<>();
    private boolean              needsRescan = false;
    private boolean              takeDownAll = false;
    /** A helper with nothing to lease: his towers come down after a short while, wherever he stands. */
    private boolean              idleHelper = false;
    private final Map<BlockPos, Long> idleSince = new HashMap<>();

    // for the tests and the logs
    public static volatile int towersBuilt = 0;
    public static volatile int towersTakenDown = 0;
    public static volatile int cellsPlaced = 0;
    public static volatile int escapes = 0;
    /** Only for tests: told about every scaffolding block that is put up. */
    public static volatile java.util.function.Consumer<BlockPos> cellProbe = null;
    public static final java.util.concurrent.ConcurrentHashMap<BlockPos, Integer> placedBy = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Only for tests: forgets the counters.
     */
    public static void resetCounters()
    {
        towersBuilt = 0;
        towersTakenDown = 0;
        cellsPlaced = 0;
        escapes = 0;
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
            Log.getLogger().info("SCAFFOLD t={} {} {}", ai.world.getGameTime(), ai.worker.getName().getString(), message);
        }
    }

    /**
     * Log line for the tests (when the reach audit is on).
     */
    public void note(final String message)
    {
        log(message);
    }

    /**
     * Another try at a tower is possible again (the items may have come).
     */
    /**
     * Whether the builder gave up waiting for scaffolding items a short while ago (and so builds nothing that needs a tower for now).
     */
    /**
     * Whether a tower is no way out right now: scaffolding is off, or a try to get one failed a short while ago.
     */
    public boolean unavailable()
    {
        return !enabled() || ai.world.getGameTime() < Math.max(deniedUntil, stockDeniedUntil);
    }

    /**
     * Whether there is no scaffolding to build a tower with: none on him, none in his hut, none standing in towers of his that could be taken down for it.
     */
    public boolean hasNoItems()
    {
        if (inInventory() + inHut() > 0)
        {
            return false;
        }
        final IBuilderWorkOrder order = ai.scaffoldOrder();
        return order == null || order.getScaffolds().components(ai.building.getPosition()).isEmpty();
    }

    public boolean denied()
    {
        return ai.world.getGameTime() < stockDeniedUntil;
    }

    public void clearDenial()
    {
        deniedUntil = 0L;
        stockDeniedUntil = 0L;
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
            return !state.is(Blocks.SCAFFOLDING) && state.isFaceSturdy(ai.world, pos, Direction.UP) && !ai.willBeRemoved(pos);
        }

        @Override
        public boolean scaffold(final BlockPos pos)
        {
            // (only what he put up himself counts as his support: another builder takes his own towers down when he is done with them, and a tower that
            // is extended by one and taken down by the other is where both of them lose their time)
            final ScaffoldLedger.Cell cell = ledger.get(pos);
            if (cell == null || !cell.hut().equals(ai.building.getPosition()))
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

    /**
     * The builder is at his hut and the structure is tall: he takes the scaffolding stock with him, so the first tower does not cost a walk
     * back to the hut.
     */
    public void prefetch()
    {
        if (!enabled())
        {
            return;
        }
        final AbstractBuildingStructureBuilder hut = ai.building;
        final int want = carryTarget() - inInventory();
        if (want > 0 && hut.getItemHandlerCap() != null)
        {
            ItemMover.move(hut.getItemHandlerCap(), ai.worker.getInventoryCitizen(), stack -> stack.is(Items.SCAFFOLDING), want);
        }
    }

    /**
     * How many scaffolding items he carries when he fetches them from the hut: two towers' worth (a trip to the hut for the next tower costs more than
     * the tower does).
     */
    private static int carryTarget()
    {
        return Math.min(64, 2 * WorkerSettings.scaffoldStock());
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
        final List<BlockPos> blocks = ai.workAhead(target, PLAN_LOOKAHEAD);
        ScaffoldPlanner.Plan found = ScaffoldPlanner.plan(new WorldGrid(order.getScaffolds()), blocks, WorkerSettings.reach(), ai.worker.getEyeHeight(), null, stock, ai.servesAnyOrder());
        if (found != null && !order.getScaffolds().isEmpty() && inInventory() + inHut() < found.cells().size())
        {
            // the items for the tower are not there: towers of his own that serve none of the blocks he works on next come down first (their items go
            // back to his hut) instead of waiting for items that nobody brings (everything he has is up in towers that are of no use now)
            reclaimIdleTowers(order, found, blocks);
        }
        if (found == null && !order.getScaffolds().isEmpty()
              && ScaffoldPlanner.plan(new WorldGrid(order.getScaffolds()), blocks, WorkerSettings.reach(), ai.worker.getEyeHeight(), null, 128, ai.servesAnyOrder()) != null)
        {
            // the stock is what is short: towers that nobody stands on or climbs come down first (their items go back to the hut), then another try
            final java.util.Set<BlockPos> occupied = occupiedCells();
            // whole towers only: a tower that loses its lower cells while somebody stands on its upper ones would collapse
            final java.util.Set<BlockPos> free = new java.util.HashSet<>();
            for (final List<ScaffoldLedger.Cell> tower : order.getScaffolds().components(null))
            {
                if (tower.stream().noneMatch(c -> occupied.contains(c.pos()) || occupied.contains(c.pos().above())))
                {
                    tower.forEach(c -> free.add(c.pos()));
                }
            }
            ScaffoldSweeper.sweep(ai.worker.getCitizenColonyHandler().getColonyOrRegister(), order.getScaffolds(), cell -> free.contains(cell.pos()), 256);
            found = ScaffoldPlanner.plan(new WorldGrid(order.getScaffolds()), blocks, WorkerSettings.reach(), ai.worker.getEyeHeight(), null, stock, ai.servesAnyOrder());
        }
        if (found == null)
        {
            log("no scaffolding possible for " + target + ": builder at " + ai.worker.getBlockX() + "," + ai.worker.getBlockY() + "," + ai.worker.getBlockZ() + " inv=" + inInventory() + " hut=" + inHut()
                  + " ledger=" + order.getScaffolds().size() + " stock=" + stock + " ahead=" + blocks.size() + " walkable=" + ai.canWalkTo(ai.worker.blockPosition()) + " below="
                  + ai.world.getBlockState(ai.worker.blockPosition().below()).getBlock());
            // (with the towers of another builder standing around, the place for a tower may come free any moment: a short denial)
            final boolean othersStand = order.getScaffolds().all().stream().anyMatch(c -> !c.hut().equals(ai.building.getPosition()));
            deniedUntil = now + (othersStand ? OTHERS_DENY_TICKS : DENY_TICKS);
            return false;
        }

        if (found.cells().isEmpty())
        {
            // the spot is there already, the builder just cannot get to it: a tower is no help
            final BlockPos st = found.stand();
            log("a spot " + st + " is there but cannot be reached: builder at " + ai.worker.blockPosition() + " canStand=" + com.minecolonies.core.entity.ai.workers.util.StandSpots.canStandAt(ai.world, st)
                  + " walk(stand)=" + ai.canWalkTo(st) + " walk(floor)=" + ai.canWalkTo(st.below()) + " floor=" + ai.world.getBlockState(st.below()) + " head=" + ai.world.getBlockState(st.above())
                  + " feet=" + ai.world.getBlockState(st) + " around=" + ai.world.getBlockState(st.north()).getBlock() + "," + ai.world.getBlockState(st.south()).getBlock() + ","
                  + ai.world.getBlockState(st.east()).getBlock() + "," + ai.world.getBlockState(st.west()).getBlock());
            deniedUntil = now + 200;
            // the spots that did not work out before get another chance
            ai.resetReachWalk();
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
        log("plan for " + target + ": cells=" + plan.cells() + " stand=" + plan.stand() + " covered=" + plan.covered() + "/" + blocks.size() + " phase=" + phase + " uncovered="
              + blocks.stream().filter(b -> !BuildReach.inReachFromFeet(plan.stand().below(), ai.worker.getEyeHeight(), b, Math.max(1.0D, WorkerSettings.reach() - ReachSpots.MARGIN))).limit(10).toList());
        return true;
    }

    /**
     * The cells where a citizen stands, or just below.
     */
    private java.util.Set<BlockPos> occupiedCells()
    {
        final java.util.Set<BlockPos> occupied = new java.util.HashSet<>();
        for (final com.minecolonies.api.entity.citizen.AbstractEntityCitizen citizen : ai.world.getEntitiesOfClass(com.minecolonies.api.entity.citizen.AbstractEntityCitizen.class,
          new net.minecraft.world.phys.AABB(ai.worker.blockPosition()).inflate(40.0D)))
        {
            occupied.add(citizen.blockPosition().below());
            occupied.add(citizen.blockPosition());
        }
        return occupied;
    }

    /**
     * Takes down the towers of this builder that nobody is on and that serve none of the next blocks, and are no support of the plan (so the
     * items are back in his hut).
     *
     * @return the number of scaffolding blocks taken down.
     */
    private int reclaimIdleTowers(final IBuilderWorkOrder order, final ScaffoldPlanner.Plan plan, final List<BlockPos> blocks)
    {
        final ScaffoldLedger ledger = order.getScaffolds();
        final java.util.Set<BlockPos> occupied = occupiedCells();
        final List<BlockPos> ahead = ai.peekWorkAhead(ai.servesAnyOrder() ? 48 : 4);
        final double eye = ai.worker.getEyeHeight();
        final double usable = Math.max(1.0D, WorkerSettings.reach() - ReachSpots.MARGIN);
        final BlockPos feet = ai.worker.blockPosition();
        final BlockPos hut = ai.building.getPosition();
        final java.util.Set<BlockPos> free = new java.util.HashSet<>();
        for (final List<ScaffoldLedger.Cell> tower : ledger.components(null))
        {
            boolean mine = true;
            boolean near = false;
            for (final ScaffoldLedger.Cell cell : tower)
            {
                mine &= cell.hut().equals(hut);
                near |= occupied.contains(cell.pos()) || occupied.contains(cell.pos().above());
                for (final BlockPos planned : plan.cells())
                {
                    near |= planned.distManhattan(cell.pos()) <= 1;
                }
                near |= plan.stand().below().distManhattan(cell.pos()) <= 1;
            }
            if (mine && !near && !standsOn(tower, feet) && served(tower, ahead, eye, usable) <= RECLAIM_SERVES)
            {
                tower.forEach(c -> free.add(c.pos()));
            }
        }
        if (free.isEmpty())
        {
            return 0;
        }
        // (taken down by him, as far as the items go: they are in his inventory, he does not have to fetch them from his hut)
        int removed = 0;
        for (final ScaffoldLedger.Cell cell : ledger.topDown(null))
        {
            if (!free.contains(cell.pos()))
            {
                continue;
            }
            if (ai.world.getBlockState(cell.pos()).is(Blocks.SCAFFOLDING))
            {
                ai.world.removeBlock(cell.pos(), false);
                removed++;
            }
            ledger.remove(cell.pos());
        }
        if (removed > 0)
        {
            final ItemStack rest = InventoryUtils.transferItemStackIntoNextBestSlotInItemHandlerWithResult(new ItemStack(Items.SCAFFOLDING, removed), ai.worker.getInventoryCitizen());
            if (!rest.isEmpty() && ai.building.getItemHandlerCap() != null)
            {
                InventoryUtils.transferItemStackIntoNextBestSlotInItemHandlerWithResult(rest, ai.building.getItemHandlerCap());
            }
        }
        log("took down " + removed + " blocks of idle towers for the plan at " + plan.stand() + " (hut " + inHut() + " inventory " + inInventory() + " needs " + plan.cells().size() + ")");
        ai.forgetWalkable();
        return removed;
    }

    /**
     * The builder is in a hole he cannot walk out of: puts up a column at the wall of the hole that reaches the level of the rim, next to a
     * spot on the rim, and climbs it. Nothing is done (and the unstuck ladder goes on to the teleport) without items or a place for the column.
     *
     * @return true if a tower is on its way.
     */
    public boolean startEscape()
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
        if (now < escapeDeniedUntil)
        {
            return false;
        }
        final IBuilderWorkOrder order = ai.scaffoldOrder();
        if (order == null)
        {
            return false;
        }
        final net.minecraft.world.level.Level level = ai.world;
        final BlockPos home = com.minecolonies.core.entity.ai.workers.util.StandSpots.nearest(level, ai.building.getPosition(), 8, 4, 4, null);
        if (home == null)
        {
            return false;
        }
        final java.util.Set<BlockPos> connected = com.minecolonies.core.entity.ai.workers.util.StandSpots.reachableFrom(level, home, 40, 40000);
        final BlockPos here = ai.worker.blockPosition();
        final List<BlockPos> rims = new ArrayList<>();
        for (final BlockPos spot : connected)
        {
            if (spot.getY() > here.getY() + 1 && spot.getY() <= here.getY() + 12 && Math.abs(spot.getX() - here.getX()) <= 8 && Math.abs(spot.getZ() - here.getZ()) <= 8
                  && com.minecolonies.core.entity.ai.workers.util.StandSpots.canStandAt(level, spot))
            {
                rims.add(spot);
            }
        }
        final ScaffoldPlanner.Plan found = rims.isEmpty() ? null : ScaffoldPlanner.planEscape(new WorldGrid(order.getScaffolds()), rims, WorkerSettings.scaffoldStock());
        if (found == null)
        {
            log("no way out of the hole at " + here + " with scaffolding (rims=" + rims.size() + ")");
            escapeDeniedUntil = now + 1000;
            return false;
        }
        plan = found;
        next = 0;
        escapes++;
        phaseSince = now;
        stepSince = now;
        ai.resetScaffoldWalk();
        log("escape from " + here + ": cells=" + plan.cells() + " stand=" + plan.stand());
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
            escapeDeniedUntil = now + 1000;
            plan = null;
            return false;
        }
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
            case EXIT:
                return exit(now);
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
            stockDeniedUntil = now + 2 * DENY_TICKS;
            abort();
            return false;
        }
        // the work goes on meanwhile (other blocks, the unstuck ladder): the tower comes when the items are there
        return false;
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
        final int want = Math.max(plan.cells().size(), Math.min(carryTarget(), inHut() + inInventory())) - inInventory();
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
            // not inside a cell that is to come (he would be shut in the column): only on top of what is there already
            if (!ai.walkIntoReachFor(cell, () -> List.of(cell), true, p -> !plan.cells().contains(p) || ledger.contains(p.below())))
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
            // the next cells too, as long as they are in reach from here: one wait for all of them
            int placed = 1;
            while (placed < 5 && next < plan.cells().size() && inInventory() > 0)
            {
                final BlockPos more = plan.cells().get(next);
                if (!new WorldGrid(ledger).free(more) || !ai.canReachNow(more) || ai.world.getBlockState(more).is(Blocks.SCAFFOLDING))
                {
                    break;
                }
                if (!place(more, ledger))
                {
                    break;
                }
                next++;
                placed++;
            }
            ai.setDelay(Math.max(1, (int) (ai.placementTicks() * placed)));
            return true;
        }
        // standing: from here the normal work goes on from the top of the tower
        final BlockPos stand = plan.stand();
        log("tower is up, stand " + stand + ", builder at " + ai.worker.blockPosition());
        plan = null;
        ai.resetReachWalk();
        ai.workFromScaffold(stand);
        // a builder who stands in a cell of the column steps out of it first, the climb starts from outside
        final BlockPos here = ai.worker.blockPosition();
        if (ai.world.getBlockState(here).is(Blocks.SCAFFOLDING) || ledger.contains(here))
        {
            for (final Direction dir : Direction.Plane.HORIZONTAL)
            {
                final BlockPos out = here.relative(dir);
                if (!ledger.contains(out) && com.minecolonies.core.entity.ai.workers.util.StandSpots.canStandAt(ai.world, out))
                {
                    exitTo = out;
                    standAfter = stand;
                    phase = Phase.EXIT;
                    phaseSince = now;
                    return true;
                }
            }
        }
        phase = Phase.IDLE;
        return false;
    }

    private boolean exit(final long now)
    {
        if (exitTo == null || now - phaseSince > 80 || com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils.walkToBlock(ai.worker, exitTo) != 0)
        {
            exitTo = null;
            phase = Phase.IDLE;
            ai.workFromScaffold(standAfter);
            return false;
        }
        return true;
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
        final java.util.function.Consumer<BlockPos> probe = cellProbe;
        if (probe != null)
        {
            probe.accept(cell.immutable());
        }
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
        final List<BlockPos> ahead = idleHelper ? ai.leadWorkAhead(48) : ai.peekWorkAhead(ai.servesAnyOrder() ? 48 : 4);
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
            if (now - since < (idleHelper ? HELPER_IDLE_GRACE : IDLE_GRACE))
            {
                continue;
            }
            // only when he is close, on the ground, and not on his way somewhere else with this tower (a helper who has nothing else to do takes the walk)
            if (!elevatedOn(tower, feet) && (idleHelper || nearTower(tower, feet)))
            {
                idleSince.remove(anchor);
                return startTakeDown(ledger, tower, now);
            }
        }
        return false;
    }

    private boolean inUse(final List<ScaffoldLedger.Cell> tower, final BlockPos feet, final List<BlockPos> ahead, final double eye, final double usable)
    {
        // another builder stands on it or climbs it: it stays
        net.minecraft.world.phys.AABB box = null;
        for (final ScaffoldLedger.Cell cell : tower)
        {
            final net.minecraft.world.phys.AABB one = new net.minecraft.world.phys.AABB(cell.pos());
            box = box == null ? one : box.minmax(one);
        }
        if (box != null && !ai.world.getEntitiesOfClass(com.minecolonies.api.entity.citizen.AbstractEntityCitizen.class, box.inflate(1.5D, 1.0D, 1.5D).expandTowards(0.0D, 3.0D, 0.0D),
          citizen -> citizen != ai.worker).isEmpty())
        {
            return true;
        }
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
            if (!ai.world.getBlockState(spot).isAir() || !ai.world.getBlockState(spot.above()).isAir())
            {
                continue;
            }
            // a spot on it serves the next block (any of the next ones if the worker takes what he reaches in any order)
            for (final BlockPos next : ai.servesAnyOrder() ? ahead : ahead.subList(0, 1))
            {
                if (BuildReach.inReachFromFeet(spot, eye, next, usable))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /** A tower that serves this many of the next blocks or fewer is given up for the items of a new one (when the items are short). */
    static final int RECLAIM_SERVES = 3;

    private boolean standsOn(final List<ScaffoldLedger.Cell> tower, final BlockPos feet)
    {
        for (final ScaffoldLedger.Cell cell : tower)
        {
            if (feet.below().equals(cell.pos()) || feet.equals(cell.pos()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * How many of the blocks a spot on the tower reaches.
     */
    private int served(final List<ScaffoldLedger.Cell> tower, final List<BlockPos> ahead, final double eye, final double usable)
    {
        final java.util.Set<BlockPos> serves = new java.util.HashSet<>();
        for (final ScaffoldLedger.Cell cell : tower)
        {
            final BlockPos spot = cell.pos().above();
            if (!ai.world.getBlockState(spot).isAir() || !ai.world.getBlockState(spot.above()).isAir())
            {
                continue;
            }
            for (final BlockPos next : ahead)
            {
                if (BuildReach.inReachFromFeet(spot, eye, next, usable))
                {
                    serves.add(next);
                }
            }
        }
        return serves.size();
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
    /**
     * A helper who has nothing to lease takes down the towers of his that the lead does not use, sooner than the others, and from wherever he is.
     */
    public void setIdleHelper(final boolean idle)
    {
        idleHelper = idle;
    }

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
            // everything of the order that is still up (helpers' towers too: the stage is over), the tower nearest to the builder first
            final BlockPos here = ai.worker.blockPosition();
            final List<List<ScaffoldLedger.Cell>> towers = new ArrayList<>(ledger.components(null));
            towers.sort(java.util.Comparator.comparingDouble(t -> t.stream().mapToDouble(c -> c.pos().distSqr(here)).min().orElse(0.0D)));
            for (final List<ScaffoldLedger.Cell> one : towers)
            {
                for (final ScaffoldLedger.Cell cell : one)
                {
                    list.add(cell.pos());
                }
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
        if (now - stepSince > TAKE_DOWN_PATIENCE || !WorldUtil.isBlockLoaded(ai.world, cell))
        {
            // he cannot get there: the colony takes the rest down
            log("cannot get to " + cell + " to take scaffolding down, builder at " + ai.worker.blockPosition());
            ScaffoldSweeper.sweep(ai.worker.getCitizenColonyHandler().getColonyOrRegister(), ledger, c -> cellsToTakeDown.contains(c.pos()), Integer.MAX_VALUE);
            noteRescan(cellsToTakeDown);
            cellsToTakeDown.clear();
            finishTakeDown();
            return false;
        }
        if (!ai.world.getEntitiesOfClass(com.minecolonies.api.entity.citizen.AbstractEntityCitizen.class, new net.minecraft.world.phys.AABB(cell).inflate(0.3D, 0.2D, 0.3D).expandTowards(0.0D, 1.2D, 0.0D),
              citizen -> citizen != ai.worker).isEmpty())
        {
            // somebody stands in it or on it: not now
            ai.setDelay(10);
            return true;
        }
        final BlockPos below = cell.below();
        final boolean descent = ledger.contains(below) || ai.world.getBlockState(below).isFaceSturdy(ai.world, below, Direction.UP);
        // on the tower itself (he goes down with it) or at the foot of it: never on a wall he would then be stranded on
        int base = cell.getY();
        for (final ScaffoldLedger.Cell other : ledger.all())
        {
            base = Math.min(base, other.pos().getY());
        }
        final int baseY = base;
        // the spot is picked for the next cells to come down, not just this one: from the top of the column he reaches the whole arm without a step
        if (!ai.walkIntoReachFor(cell, () -> new ArrayList<>(cellsToTakeDown.subList(0, Math.min(8, cellsToTakeDown.size()))), true,
          feet -> (!feet.below().equals(cell) || descent) && (ledger.contains(feet.below()) || ledger.contains(feet) || feet.getY() <= baseY + 1)))
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
        idleHelper = false;
        needsRescan = false;
        idleSince.clear();
        deniedUntil = 0L;
        stockDeniedUntil = 0L;
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
