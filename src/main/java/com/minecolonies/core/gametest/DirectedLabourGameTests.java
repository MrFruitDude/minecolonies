package com.minecolonies.core.gametest;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.idle.IIdleTaskSession;
import com.minecolonies.api.colony.labour.ILabourExclusion;
import com.minecolonies.api.colony.labour.LabourExclusions;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.modules.settings.IntSetting;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;
import com.minecolonies.core.colony.idle.IdleCitizenArbiter;
import com.minecolonies.core.colony.idle.IdleCitizenSettings;
import com.minecolonies.core.colony.labour.DirectedOrder;
import com.minecolonies.core.colony.labour.DirectedOrders;
import com.minecolonies.core.colony.labour.DirectedSession;
import com.minecolonies.core.colony.labour.DirectedTask;
import com.minecolonies.core.colony.labour.LabourSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Directed gathering orders (DIRECTED-WORKERS-PROPOSAL): a manager sends chosen jobless citizens to gather a resource inside an area and bring an amount to a
 * building. Real colonies and citizens; the colonies are ticked by the test. Run with {@code MINECOLONIES_GAMETEST_ONLY=directed} (or {@code labour}).
 */
final class DirectedLabourGameTests
{
    private DirectedLabourGameTests()
    {
    }

    private static void begin()
    {
        com.minecolonies.core.colony.labour.LabourStats.debug = true;
        LabourSettings.clearOverrides();
        LabourSettings.override("workScale", 8.0D);
        LabourSettings.override("keepTrees", 0);
        LabourSettings.override("stockpile", false);
        IdleCitizenSettings.overrideForTests(null, null, null);
    }

    /** What a test changed beyond its blocks (a veto, a moved building box, a test breaker), put back by {@link #end}; by test, as tests run side by side. */
    private static final Map<GameTestHelper, List<Runnable>> RESTORE = new HashMap<>();

    private static void restore(final GameTestHelper helper, final Runnable undo)
    {
        RESTORE.computeIfAbsent(helper, h -> new ArrayList<>()).add(undo);
    }

    private static void end(final GameTestHelper helper)
    {
        com.minecolonies.core.colony.labour.LabourStats.debug = false;
        LabourSettings.clearOverrides();
        final List<Runnable> undo = RESTORE.remove(helper);
        if (undo != null)
        {
            undo.forEach(Runnable::run);
        }
    }

    private static boolean gathering(final ICitizenData citizen)
    {
        return IdleCitizenArbiter.sessionOf(citizen) instanceof DirectedSession d && d.phase() == DirectedSession.Phase.GATHER;
    }

    /**
     * Moves the building's box (with its margin of 2) over the two corners; {@link #end} puts it back.
     */
    private static void moveBox(final GameTestHelper helper, final IBuilding building, final BlockPos a, final BlockPos b)
    {
        final BlockPos oldA = building.getCorners().getA();
        final BlockPos oldB = building.getCorners().getB();
        building.setCorners(a, b);
        restore(helper, () -> building.setCorners(oldA, oldB));
    }

    private static RsFixture found(final GameTestHelper helper, final String name)
    {
        final RsFixture f = RsFixture.found(helper, name, 2);
        final var hiring = f.colony.getSettings().getSetting(BuildingTownHall.AUTO_HIRING_MODE);
        if (hiring.getValue())
        {
            hiring.trigger();
        }
        final BlockPos min = helper.absolutePos(new BlockPos(-8, 1, -8));
        final BlockPos max = helper.absolutePos(new BlockPos(48, 1, 40));
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++)
        {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++)
            {
                f.level.setChunkForced(cx, cz, true);
            }
        }
        return f;
    }

    private static void tree(final GameTestHelper helper, final int x, final int z, final int height)
    {
        helper.setBlock(new BlockPos(x, 0, z), Blocks.DIRT.defaultBlockState());
        for (int y = 1; y <= height; y++)
        {
            helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG.defaultBlockState());
        }
        final BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false).setValue(LeavesBlock.DISTANCE, 1);
        for (int y = height - 1; y <= height; y++)
        {
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    if (dx != 0 || dz != 0)
                    {
                        helper.setBlock(new BlockPos(x + dx, y, z + dz), leaves);
                    }
                }
            }
        }
        helper.setBlock(new BlockPos(x, height + 1, z), leaves);
    }

    private static int count(final GameTestHelper helper, final Block block, final int x0, final int z0, final int x1, final int z1, final int y0, final int y1)
    {
        int n = 0;
        for (int x = x0; x <= x1; x++)
        {
            for (int z = z0; z <= z1; z++)
            {
                for (int y = y0; y <= y1; y++)
                {
                    if (helper.getBlockState(new BlockPos(x, y, z)).is(block))
                    {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private static DirectedOrder.Area area(final GameTestHelper helper, final int x0, final int z0, final int x1, final int z1)
    {
        final BlockPos a = helper.absolutePos(new BlockPos(x0, 1, z0));
        final BlockPos b = helper.absolutePos(new BlockPos(x1, 1, z1));
        return DirectedOrder.Area.of(a.getX(), a.getZ(), b.getX(), b.getZ());
    }

    private static int inBuilding(final IBuilding building, final Predicate<ItemStack> matcher)
    {
        return InventoryUtils.getItemCountInItemHandler(building.getItemHandlerCap(), matcher);
    }

    private static int carrying(final ICitizenData citizen, final Predicate<ItemStack> matcher)
    {
        return InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), matcher);
    }

    private static void poll(final GameTestHelper helper, final Colony colony, final int maxTicks, final BooleanSupplier check, final Runnable onDone, final Supplier<String> failure)
    {
        final int[] elapsed = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            helper.getLevel().getServer().clockManager().setTotalTicks(helper.getLevel().registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
            colony.getRequestManager().tick();
            IdleCitizenArbiter.tick(colony);
            colony.getLabourBoard().tick();
            if (check.getAsBoolean())
            {
                onDone.run();
                return;
            }
            if (elapsed[0] % 500 == 0)
            {
                Log.getLogger().info("DIRECTED poll t={} {}", elapsed[0], failure.get());
            }
            elapsed[0] += 20;
            if (elapsed[0] >= maxTicks)
            {
                end(helper);
                helper.assertTrue(false, "timeout after " + maxTicks + " ticks: " + failure.get());
                return;
            }
            helper.runAfterDelay(20, () -> step[0].run());
        };
        helper.runAfterDelay(1, step[0]);
    }

    private static String describe(final Colony colony, final DirectedOrder order, final ICitizenData... citizens)
    {
        final StringBuilder sb = new StringBuilder("order ").append(order.id()).append(' ').append(order.state()).append(' ').append(order.delivered()).append('/')
                                   .append(order.amount()).append(" why ").append(order.why()).append(" committed ").append(order.committed());
        for (final ICitizenData citizen : citizens)
        {
            final IIdleTaskSession session = IdleCitizenArbiter.sessionOf(citizen);
            sb.append(", citizen ").append(citizen.getId()).append(' ').append(session == null ? "-" : session.getTaskId())
              .append(session instanceof DirectedSession d ? "/" + d.phaseName() : "").append(" carried ").append(order.carried(citizen.getId()))
              .append(" at ").append(citizen.getEntity().map(e -> e.blockPosition().toShortString()).orElse("-"));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ the headline

    /**
     * One citizen fells the tree inside the area and brings exactly the amount of logs to the warehouse. A tree outside the area, and a tree whose branch reaches out
     * of the area, stand untouched. The order completes, its claim is gone, and the citizen goes back to the automatic labour.
     */
    public static void woodInTheAreaOnly(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "directed wood");
        final Colony colony = (Colony) f.colony;
        tree(helper, 34, 20, 5);
        tree(helper, 44, 20, 5);
        // the straddling tree: its trunk stands in the area, a branch log leaves it
        tree(helper, 38, 30, 5);
        helper.setBlock(new BlockPos(38, 4, 33), Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(new BlockPos(38, 4, 32), Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(new BlockPos(38, 4, 31), Blocks.OAK_LOG.defaultBlockState());
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final Predicate<ItemStack> logs = s -> s.is(ItemTags.LOGS);
            final int before = inBuilding(f.warehouse, logs);
            final DirectedOrders.Start start = colony.getLabourBoard().directed().start(List.of(idle.getId()), DirectedOrder.Resource.WOOD, 3, area(helper, 30, 16, 41, 32), null);
            helper.assertTrue(start.order() != null, "refused: " + start.refusal());
            final DirectedOrder order = start.order();
            poll(helper, colony, 9000, () -> !order.active(), () -> {
                helper.assertTrue(order.state() == DirectedOrder.State.COMPLETED, "the order ended as " + order.state());
                helper.assertTrue(order.delivered() == 3, "delivered " + order.delivered());
                helper.assertTrue(inBuilding(f.warehouse, logs) - before == 3, "logs in the warehouse: " + (inBuilding(f.warehouse, logs) - before));
                helper.assertTrue(count(helper, Blocks.OAK_LOG, 44, 20, 44, 20, 1, 5) == 5, "the tree outside the area was cut");
                helper.assertTrue(count(helper, Blocks.OAK_LOG, 38, 30, 38, 33, 1, 5) == 8, "the tree reaching out of the area was cut");
                helper.assertTrue(colony.getLabourBoard().claimOf(idle.getId()) == null, "the claim is left");
                helper.assertTrue(colony.getLabourBoard().directed().orderOf(idle.getId()) == null, "the citizen is still on the order");
                helper.assertTrue(colony.getLabourBoard().takenTrees().isEmpty(), "a tree is still taken");
                end(helper);
                helper.succeed();
            }, () -> describe(colony, order, idle));
        });
    }

    // ------------------------------------------------------------------ two workers

    /**
     * Two citizens dig dirt in one area for one order of six: exactly six blocks are dug and six are handed in (never one more, though both race for the blocks), and
     * no block is dug twice or outside the area.
     */
    public static void twoWorkersShareWithoutExtra(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "directed dirt");
        final Colony colony = (Colony) f.colony;
        for (int x = 30; x <= 35; x++)
        {
            for (int z = 20; z <= 23; z++)
            {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.DIRT.defaultBlockState());
            }
        }
        // dirt outside the area
        for (int z = 20; z <= 23; z++)
        {
            helper.setBlock(new BlockPos(37, 1, z), Blocks.DIRT.defaultBlockState());
        }
        helper.runAfterDelay(100, () -> {
            final ICitizenData a = f.spawn(new BlockPos(14, 1, 16), false);
            final ICitizenData b = f.spawn(new BlockPos(15, 1, 16), false);
            final Predicate<ItemStack> dirt = s -> s.is(Items.DIRT);
            final int before = inBuilding(f.warehouse, dirt);
            final int carriedBefore = carrying(a, dirt) + carrying(b, dirt);
            colony.getSettings().updateSetting(BuildingTownHall.LABOUR_SHARE, new IntSetting(100), null);
            final DirectedOrders.Start start = colony.getLabourBoard().directed().start(List.of(a.getId(), b.getId()), DirectedOrder.Resource.DIRT, 6, area(helper, 30, 20, 35, 23),
              null);
            helper.assertTrue(start.order() != null, "refused: " + start.refusal());
            final DirectedOrder order = start.order();
            poll(helper, colony, 9000, () -> {
                helper.assertTrue(order.committed() <= 6, "the order spoke for more than it asked: " + order.committed());
                helper.assertTrue(count(helper, Blocks.DIRT, 37, 20, 37, 23, 1, 1) == 4, "dirt outside the area was dug");
                return !order.active();
            }, () -> {
                helper.assertTrue(order.state() == DirectedOrder.State.COMPLETED, "the order ended as " + order.state());
                helper.assertTrue(inBuilding(f.warehouse, dirt) - before == 6, "dirt in the warehouse: " + (inBuilding(f.warehouse, dirt) - before));
                helper.assertTrue(count(helper, Blocks.DIRT, 30, 20, 35, 23, 1, 1) == 24 - 6, "dug: " + (24 - count(helper, Blocks.DIRT, 30, 20, 35, 23, 1, 1)));
                helper.assertTrue(carrying(a, dirt) + carrying(b, dirt) == carriedBefore, "the workers kept dirt of the order");
                helper.assertTrue(colony.getLabourBoard().takenSurface().isEmpty(), "a block is still taken");
                end(helper);
                helper.succeed();
            }, () -> describe(colony, order, a, b));
        });
    }

    // ------------------------------------------------------------------ cancel and restart

    /**
     * Cancel mid-work: the order ends, the claim and the taken block are given back, and the dirt the citizen dug stays in his inventory (nothing vanishes, nothing
     * is made). A reload in between keeps the order and what he carries for it; the session that a restart drops starts again.
     */
    public static void reloadThenCancelConserves(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "directed cancel");
        final Colony colony = (Colony) f.colony;
        for (int x = 30; x <= 37; x++)
        {
            for (int z = 20; z <= 25; z++)
            {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.DIRT.defaultBlockState());
            }
        }
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final Predicate<ItemStack> dirt = s -> s.is(Items.DIRT);
            final int carriedBefore = carrying(idle, dirt);
            final DirectedOrders.Start start = colony.getLabourBoard().directed().start(List.of(idle.getId()), DirectedOrder.Resource.DIRT, 40, area(helper, 30, 20, 37, 25), null);
            helper.assertTrue(start.order() != null, "refused: " + start.refusal());
            final int id = start.order().id();
            final boolean[] reloaded = {false};
            poll(helper, colony, 9000, () -> {
                final DirectedOrder order = colony.getLabourBoard().directed().order(id);
                if (!reloaded[0] && order != null && order.carried(idle.getId()) >= 2)
                {
                    // a restart: the sessions and claims go, the colony's saved labour data is read back
                    reloaded[0] = true;
                    final int carried = order.carried(idle.getId());
                    IdleCitizenArbiter.stopAll(colony, com.minecolonies.api.colony.idle.IdleStopReason.CITIZEN_GONE);
                    final CompoundTag saved = colony.getLabourData().write();
                    colony.getLabourData().read(saved);
                    final DirectedOrder back = colony.getLabourBoard().directed().order(id);
                    helper.assertTrue(back != null && back.active() && back.has(idle.getId()) && back.carried(idle.getId()) == carried,
                      "the reload lost the order or its cargo: " + (back == null ? "none" : describe(colony, back, idle)));
                    helper.assertTrue(colony.getLabourBoard().claimOf(idle.getId()) == null, "a claim survived the restart");
                    return false;
                }
                if (reloaded[0] && order != null && IdleCitizenArbiter.sessionOf(idle) != null && DirectedTask.ID.equals(IdleCitizenArbiter.sessionOf(idle).getTaskId())
                      && order.carried(idle.getId()) >= 3)
                {
                    final int carried = order.carried(idle.getId());
                    final int holding = carrying(idle, dirt);
                    helper.assertTrue(colony.getLabourBoard().directed().cancel(id), "cancel refused");
                    helper.assertTrue(!colony.getLabourBoard().directed().cancel(id), "a second cancel was accepted");
                    IdleCitizenArbiter.tick(colony);
                    helper.assertTrue(colony.getLabourBoard().directed().order(id).state() == DirectedOrder.State.CANCELLED, "not cancelled");
                    helper.assertTrue(colony.getLabourBoard().claimOf(idle.getId()) == null, "the claim survived the cancel");
                    helper.assertTrue(IdleCitizenArbiter.sessionOf(idle) == null || !DirectedTask.ID.equals(IdleCitizenArbiter.sessionOf(idle).getTaskId()),
                      "the directed session survived the cancel");
                    helper.assertTrue(colony.getLabourBoard().takenSurface().isEmpty(), "a block is still taken");
                    helper.assertTrue(carrying(idle, dirt) == holding && holding - carriedBefore >= carried, "the cancel lost dirt: " + holding + " / " + carried);
                    helper.assertTrue(colony.getLabourBoard().directed().orderOf(idle.getId()) == null, "he is still on the order");
                    return true;
                }
                return false;
            }, () -> {
                end(helper);
                helper.succeed();
            }, () -> {
                final DirectedOrder order = colony.getLabourBoard().directed().order(id);
                return order == null ? "no order" : describe(colony, order, idle) + " reloaded " + reloaded[0];
            });
        });
    }

    // ------------------------------------------------------------------ a job ends it

    /**
     * Mid-order the citizen gets a job: he leaves the order (it shows that no worker is left), his claim goes, the job's AI keeps him, and the goods he carries stay
     * his.
     */
    public static void jobEndsTheOrderForHim(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "directed job");
        final Colony colony = (Colony) f.colony;
        for (int x = 30; x <= 37; x++)
        {
            for (int z = 20; z <= 25; z++)
            {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.DIRT.defaultBlockState());
            }
        }
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final Predicate<ItemStack> dirt = s -> s.is(Items.DIRT);
            final DirectedOrders.Start start = colony.getLabourBoard().directed().start(List.of(idle.getId()), DirectedOrder.Resource.DIRT, 40, area(helper, 30, 20, 37, 25), null);
            helper.assertTrue(start.order() != null, "refused: " + start.refusal());
            final DirectedOrder order = start.order();
            poll(helper, colony, 9000, () -> {
                if (order.carried(idle.getId()) < 1)
                {
                    return false;
                }
                final int holding = carrying(idle, dirt);
                helper.assertTrue(f.builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(idle), "builder not assigned");
                IdleCitizenArbiter.tick(colony);
                colony.getLabourBoard().tick();
                helper.assertTrue(!order.has(idle.getId()), "he is still on the order with a job");
                helper.assertTrue(order.why() == DirectedOrder.Why.NO_WORKERS, "the order shows " + order.why());
                helper.assertTrue(colony.getLabourBoard().claimOf(idle.getId()) == null, "the claim survived the job");
                helper.assertTrue(IdleCitizenArbiter.sessionOf(idle) == null, "a session survived the job");
                helper.assertTrue(carrying(idle, dirt) == holding, "the job took his dirt");
                return true;
            }, () -> {
                end(helper);
                helper.succeed();
            }, () -> describe(colony, order, idle));
        });
    }

    // ------------------------------------------------------------------ what the server refuses

    /**
     * Every check of a start: nothing is created by a refused order, and a citizen with a job is never put on one.
     */
    public static void refusals(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "directed refusals");
        final Colony colony = (Colony) f.colony;
        for (int x = 30; x <= 33; x++)
        {
            helper.setBlock(new BlockPos(x, 1, 20), Blocks.STONE.defaultBlockState());
        }
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final ICitizenData worker = f.spawn(new BlockPos(15, 1, 16), false);
            helper.assertTrue(f.builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(worker), "builder not assigned");
            final DirectedOrders orders = colony.getLabourBoard().directed();
            final DirectedOrder.Area ok = area(helper, 28, 18, 36, 24);
            colony.getSettings().updateSetting(BuildingTownHall.LABOUR_SHARE, new IntSetting(100), null);
            expect(helper, orders.start(List.of(), DirectedOrder.Resource.DIRT, 4, ok, null), DirectedOrders.Refusal.NOT_ELIGIBLE, "nobody chosen");
            expect(helper, orders.start(List.of(idle.getId(), idle.getId()), DirectedOrder.Resource.DIRT, 4, ok, null), DirectedOrders.Refusal.NOT_ELIGIBLE, "twice");
            expect(helper, orders.start(List.of(worker.getId()), DirectedOrder.Resource.DIRT, 4, ok, null), DirectedOrders.Refusal.NOT_ELIGIBLE, "a worker with a job");
            expect(helper, orders.start(List.of(987654), DirectedOrder.Resource.DIRT, 4, ok, null), DirectedOrders.Refusal.NO_CITIZEN, "an unknown citizen");
            expect(helper, orders.start(List.of(idle.getId()), DirectedOrder.Resource.DIRT, 0, ok, null), DirectedOrders.Refusal.BAD_AMOUNT, "no amount");
            expect(helper, orders.start(List.of(idle.getId()), DirectedOrder.Resource.DIRT, DirectedOrder.MAX_AMOUNT + 1, ok, null), DirectedOrders.Refusal.BAD_AMOUNT, "too many");
            expect(helper, orders.start(List.of(idle.getId()), DirectedOrder.Resource.DIRT, 4, area(helper, 0, 0, 60, 4), null), DirectedOrders.Refusal.BAD_AREA, "a huge area");
            expect(helper, orders.start(List.of(idle.getId()), DirectedOrder.Resource.DIRT, 4, area(helper, 4000, 4000, 4010, 4010), null), DirectedOrders.Refusal.AREA_OUTSIDE,
              "an unloaded area outside the colony");
            expect(helper, orders.start(List.of(idle.getId()), DirectedOrder.Resource.COBBLESTONE, 4, ok, null), DirectedOrders.Refusal.NO_TOOL, "stone without a pickaxe");
            expect(helper, orders.start(List.of(idle.getId()), DirectedOrder.Resource.DIRT, 4, ok, helper.absolutePos(new BlockPos(30, 1, 30))), DirectedOrders.Refusal.NO_DESTINATION,
              "a destination that is no building");
            colony.getSettings().updateSetting(BuildingTownHall.LABOUR, new BoolSetting(false), null);
            expect(helper, orders.start(List.of(idle.getId()), DirectedOrder.Resource.DIRT, 4, ok, null), DirectedOrders.Refusal.LABOUR_OFF, "labour off");
            colony.getSettings().updateSetting(BuildingTownHall.LABOUR, new BoolSetting(true), null);
            colony.getSettings().updateSetting(BuildingTownHall.LABOUR_CAP, new IntSetting(0), null);
            expect(helper, orders.start(List.of(idle.getId()), DirectedOrder.Resource.DIRT, 4, ok, null), DirectedOrders.Refusal.CAP, "over the cap");
            helper.assertTrue(orders.orders().isEmpty(), "a refused start left an order: " + orders.orders().size());
            helper.assertTrue(worker.getJob() != null && orders.orderOf(worker.getId()) == null, "the employed citizen was touched");
            colony.getSettings().updateSetting(BuildingTownHall.LABOUR_CAP, new IntSetting(8), null);
            // the chosen hut as destination: accepted, and the same citizen cannot be put on a second order
            final DirectedOrders.Start first = orders.start(List.of(idle.getId()), DirectedOrder.Resource.DIRT, 4, ok, f.builder.getID());
            helper.assertTrue(first.order() != null, "a chosen built hut was refused: " + first.refusal());
            expect(helper, orders.start(List.of(idle.getId()), DirectedOrder.Resource.GRAVEL, 4, ok, null), DirectedOrders.Refusal.NOT_ELIGIBLE, "a second order");
            helper.assertTrue(orders.cancel(first.order().id()), "cancel");
            helper.assertTrue(!orders.cancel(424242), "a cancel of no order was accepted");
            end(helper);
            helper.succeed();
        });
    }

    private static void expect(final GameTestHelper helper, final DirectedOrders.Start start, final DirectedOrders.Refusal refusal, final String what)
    {
        helper.assertTrue(start.order() == null && start.refusal() == refusal, what + ": expected " + refusal + " but got " + (start.order() != null ? "an order" : start.refusal()));
    }

    // ------------------------------------------------------------------ the target changes while he walks

    /**
     * A veto comes over the tree after he chose it and before the first log breaks: not one log is felled, nothing is counted or carried, the tree and the block he
     * spoke for are given back, and the order says that no site is left.
     */
    public static void vetoedBeforeTheBreak(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "directed veto");
        final Colony colony = (Colony) f.colony;
        tree(helper, 34, 20, 5);
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final Predicate<ItemStack> logs = s -> s.is(ItemTags.LOGS);
            final int holding = carrying(idle, logs);
            final DirectedOrder.Area area = area(helper, 30, 16, 41, 26);
            final boolean[] vetoed = {false};
            final ILabourExclusion veto = (c, pos) -> vetoed[0] && c.getID() == colony.getID() && area.contains(pos);
            LabourExclusions.register(veto);
            restore(helper, () -> LabourExclusions.unregister(veto));
            final DirectedOrders.Start start = colony.getLabourBoard().directed().start(List.of(idle.getId()), DirectedOrder.Resource.WOOD, 3, area, null);
            helper.assertTrue(start.order() != null, "refused: " + start.refusal());
            final DirectedOrder order = start.order();
            final long[] since = {0L};
            poll(helper, colony, 6000, () -> {
                if (!vetoed[0])
                {
                    if (!gathering(idle))
                    {
                        return false;
                    }
                    helper.assertTrue(count(helper, Blocks.OAK_LOG, 34, 20, 34, 20, 1, 5) == 5, "a log fell before the veto came");
                    vetoed[0] = true;
                    since[0] = helper.getLevel().getGameTime();
                    return false;
                }
                helper.assertTrue(count(helper, Blocks.OAK_LOG, 34, 20, 34, 20, 1, 5) == 5, "a vetoed log was felled");
                helper.assertTrue(order.carried(idle.getId()) == 0 && order.delivered() == 0, "a vetoed log was counted");
                return helper.getLevel().getGameTime() - since[0] >= 400 && order.why() == DirectedOrder.Why.NO_SITE;
            }, () -> {
                helper.assertTrue(carrying(idle, logs) == holding, "he carries logs he may not have: " + carrying(idle, logs));
                helper.assertTrue(order.committed() == 0, "the order still speaks for " + order.committed());
                helper.assertTrue(colony.getLabourBoard().takenTrees().isEmpty(), "the vetoed tree is still taken");
                helper.assertTrue(colony.getLabourBoard().directed().cancel(order.id()), "cancel");
                end(helper);
                helper.succeed();
            }, () -> describe(colony, order, idle) + " vetoed " + vetoed[0]);
        });
    }

    /**
     * A building's box comes over the dirt after he chose a block and before it breaks: the check at the break reads the buildings as they are (not the scan's cache
     * of them), so not one block is dug and nothing is counted.
     */
    public static void buildingBeforeTheBreak(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "directed building");
        final Colony colony = (Colony) f.colony;
        for (int x = 30; x <= 35; x++)
        {
            for (int z = 20; z <= 23; z++)
            {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.DIRT.defaultBlockState());
            }
        }
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final Predicate<ItemStack> dirt = s -> s.is(Items.DIRT);
            final int holding = carrying(idle, dirt);
            final DirectedOrders.Start start = colony.getLabourBoard().directed().start(List.of(idle.getId()), DirectedOrder.Resource.DIRT, 4, area(helper, 30, 20, 35, 23), null);
            helper.assertTrue(start.order() != null, "refused: " + start.refusal());
            final DirectedOrder order = start.order();
            final boolean[] built = {false};
            final long[] since = {0L};
            poll(helper, colony, 6000, () -> {
                if (!built[0])
                {
                    if (!gathering(idle))
                    {
                        return false;
                    }
                    helper.assertTrue(count(helper, Blocks.DIRT, 30, 20, 35, 23, 1, 1) == 24, "dirt was dug before the building came");
                    built[0] = true;
                    since[0] = helper.getLevel().getGameTime();
                    moveBox(helper, f.builder, helper.absolutePos(new BlockPos(32, 1, 21)), helper.absolutePos(new BlockPos(33, 1, 22)));
                    return false;
                }
                helper.assertTrue(count(helper, Blocks.DIRT, 30, 20, 35, 23, 1, 1) == 24, "dirt in the building's box was dug");
                helper.assertTrue(order.carried(idle.getId()) == 0 && order.delivered() == 0, "dirt of the building was counted");
                return helper.getLevel().getGameTime() - since[0] >= 600;
            }, () -> {
                helper.assertTrue(carrying(idle, dirt) == holding, "he carries dirt he may not have: " + carrying(idle, dirt));
                helper.assertTrue(colony.getLabourBoard().directed().cancel(order.id()), "cancel");
                end(helper);
                helper.succeed();
            }, () -> describe(colony, order, idle) + " built " + built[0]);
        });
    }

    /**
     * A natural tree whose branch reaches into a building's box is the building's: it stands whole, the clean tree next to it is felled for the order. (Only the base
     * was looked at before; every log is now.)
     */
    public static void branchInABuilding(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "directed branch");
        final Colony colony = (Colony) f.colony;
        tree(helper, 34, 20, 5);
        helper.setBlock(new BlockPos(34, 4, 21), Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(new BlockPos(34, 4, 22), Blocks.OAK_LOG.defaultBlockState());
        helper.setBlock(new BlockPos(34, 4, 23), Blocks.OAK_LOG.defaultBlockState());
        tree(helper, 39, 18, 5);
        helper.runAfterDelay(100, () -> {
            // the box (margin 2) holds the branch's last two logs, not the trunk or the base
            moveBox(helper, f.builder, helper.absolutePos(new BlockPos(34, 4, 24)), helper.absolutePos(new BlockPos(34, 4, 24)));
            colony.getLabourBoard().sites().invalidate();
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final Predicate<ItemStack> logs = s -> s.is(ItemTags.LOGS);
            final int before = inBuilding(f.warehouse, logs);
            final DirectedOrders.Start start = colony.getLabourBoard().directed().start(List.of(idle.getId()), DirectedOrder.Resource.WOOD, 3, area(helper, 30, 14, 41, 26), null);
            helper.assertTrue(start.order() != null, "refused: " + start.refusal());
            final DirectedOrder order = start.order();
            poll(helper, colony, 9000, () -> {
                helper.assertTrue(count(helper, Blocks.OAK_LOG, 34, 20, 34, 23, 1, 5) == 8, "the tree reaching into the building was cut");
                return !order.active();
            }, () -> {
                helper.assertTrue(order.state() == DirectedOrder.State.COMPLETED, "the order ended as " + order.state());
                helper.assertTrue(inBuilding(f.warehouse, logs) - before == 3, "logs in the warehouse: " + (inBuilding(f.warehouse, logs) - before));
                helper.assertTrue(count(helper, Blocks.OAK_LOG, 39, 18, 39, 18, 1, 5) < 5, "the clean tree was not felled");
                end(helper);
                helper.succeed();
            }, () -> describe(colony, order, idle));
        });
    }

    /**
     * The block does not break (something refused the removal): its drops are never made, nothing is counted or carried, and he leaves the block.
     */
    public static void failedBreakMintsNothing(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "directed failed break");
        final Colony colony = (Colony) f.colony;
        for (int x = 30; x <= 35; x++)
        {
            for (int z = 20; z <= 23; z++)
            {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.DIRT.defaultBlockState());
            }
        }
        final int[] tries = {0};
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            // only his breaks fail: the workers of tests running beside this one break as always
            DirectedSession.breakerForTests((worker, pos, owner, slot) -> {
                if (idle.getEntity().orElse(null) == worker)
                {
                    tries[0]++;
                    return;
                }
                DirectedSession.REAL_BREAKER.breakBlock(worker, pos, owner, slot);
            });
            restore(helper, () -> DirectedSession.breakerForTests(null));
            final Predicate<ItemStack> dirt = s -> s.is(Items.DIRT);
            final int holding = carrying(idle, dirt);
            final int before = inBuilding(f.warehouse, dirt);
            final DirectedOrders.Start start = colony.getLabourBoard().directed().start(List.of(idle.getId()), DirectedOrder.Resource.DIRT, 4, area(helper, 30, 20, 35, 23), null);
            helper.assertTrue(start.order() != null, "refused: " + start.refusal());
            final DirectedOrder order = start.order();
            poll(helper, colony, 6000, () -> {
                helper.assertTrue(carrying(idle, dirt) == holding, "a block that did not break gave dirt: " + carrying(idle, dirt));
                helper.assertTrue(order.carried(idle.getId()) == 0 && order.delivered() == 0, "a block that did not break was counted");
                return tries[0] >= 3;
            }, () -> {
                helper.assertTrue(count(helper, Blocks.DIRT, 30, 20, 35, 23, 1, 1) == 24, "a block broke past the test breaker");
                helper.assertTrue(inBuilding(f.warehouse, dirt) == before, "dirt reached the warehouse");
                helper.assertTrue(colony.getLabourBoard().directed().cancel(order.id()), "cancel");
                end(helper);
                helper.succeed();
            }, () -> describe(colony, order, idle) + " tries " + tries[0]);
        });
    }

    // ------------------------------------------------------------------ registration

    static final String[] NAMES = {"labour_directed_wood_area", "labour_directed_two_workers", "labour_directed_reload_cancel", "labour_directed_job", "labour_directed_refusals",
      "labour_directed_veto_before_break", "labour_directed_building_before_break", "labour_directed_branch_in_building", "labour_directed_failed_break"};

    static java.util.function.Consumer<GameTestHelper> test(final String name)
    {
        return switch (name)
        {
            case "labour_directed_wood_area" -> DirectedLabourGameTests::woodInTheAreaOnly;
            case "labour_directed_two_workers" -> DirectedLabourGameTests::twoWorkersShareWithoutExtra;
            case "labour_directed_reload_cancel" -> DirectedLabourGameTests::reloadThenCancelConserves;
            case "labour_directed_job" -> DirectedLabourGameTests::jobEndsTheOrderForHim;
            case "labour_directed_veto_before_break" -> DirectedLabourGameTests::vetoedBeforeTheBreak;
            case "labour_directed_building_before_break" -> DirectedLabourGameTests::buildingBeforeTheBreak;
            case "labour_directed_branch_in_building" -> DirectedLabourGameTests::branchInABuilding;
            case "labour_directed_failed_break" -> DirectedLabourGameTests::failedBreakMintsNothing;
            default -> DirectedLabourGameTests::refusals;
        };
    }
}
