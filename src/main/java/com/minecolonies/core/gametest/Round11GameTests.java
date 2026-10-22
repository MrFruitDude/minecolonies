package com.minecolonies.core.gametest;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.pathfinding.IRouteHintProvider;
import com.minecolonies.api.pathfinding.RouteHints;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.utils.SurplusToWarehouse;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.labour.LabourSettings;
import com.minecolonies.core.colony.workorders.AbstractWorkOrder;
import com.minecolonies.core.colony.workorders.WorkOrderDecoration;
import com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder;
import com.minecolonies.core.entity.ai.workers.util.WorkerSettings;
import com.minecolonies.core.entity.citizen.CitizenSnack;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.minecolonies.api.colony.ICitizenData.MAX_SATURATION;

/**
 * Round 11 polish (lane A): the work snack never eats a courier's cargo (W1-02), scans the hut racks with a back-off (W4-07); the hut-walk skip judges an order
 * by its saved box (W4-04); route hint queries have a per-tick budget (W4-05); the early labour pace eases out (W2-10); surplus goods move from a hut to the
 * warehouse (W4-06, D11 b).
 */
public final class Round11GameTests
{
    private Round11GameTests()
    {
    }

    /**
     * W1-02: a hungry courier (saturation 5) carries 16 bread for a builder's request. Nothing of his cargo is eaten: all 16 arrive.
     */
    public static void courierKeepsCargo(final GameTestHelper helper)
    {
        final RsFixture f = RsFixture.found(helper, "r11-courier-cargo", 4);
        final IBuilding deliveryman = f.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");
        f.stock(0, new ItemStack(Items.BREAD, 16));
        helper.runAfterDelay(200, () -> {
            final ICitizenData courier = f.spawn(new BlockPos(34, 1, 8), false);
            helper.assertTrue(deliveryman.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier), "courier not assigned to the courier hut");
            helper.assertTrue(f.warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "courier not assigned to the warehouse");
            courier.getEntity().ifPresent(e -> e.setPos(f.warehouse.getID().getX() + 0.5D, f.warehouse.getID().getY(), f.warehouse.getID().getZ() + 0.5D));
            final JobDeliveryman job = courier.getJob(JobDeliveryman.class);
            final int eaten0 = CitizenSnack.eaten.get();
            f.builder.createRequest(new Stack(new ItemStack(Items.BREAD, 16), 16, 16), false);
            final long start = f.level.getGameTime();
            final long[] carried = {0};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                courier.setSaturation(MAX_SATURATION * 0.25D);
                if (!job.getTaskQueue().isEmpty())
                {
                    carried[0] = Math.max(carried[0], InventoryUtils.getItemCountInItemHandler(courier.getInventory(), s -> s.is(Items.BREAD)));
                }
                final int inBuilder = InventoryUtils.getItemCountInItemHandler(f.builder.getItemHandlerCap(), s -> s.is(Items.BREAD));
                if (inBuilder >= 16)
                {
                    Log.getLogger().info("R11 RESULT courier_cargo delivered={} carriedMax={} eaten={} after={}", inBuilder, carried[0], CitizenSnack.eaten.get() - eaten0,
                      f.level.getGameTime() - start);
                    helper.assertTrue(carried[0] >= 16, "the courier never carried the bread: " + carried[0]);
                    helper.assertTrue(CitizenSnack.eaten.get() == eaten0, "the courier ate at work while carrying cargo: " + (CitizenSnack.eaten.get() - eaten0));
                    helper.succeed();
                    return;
                }
                if (f.level.getGameTime() - start > 6000)
                {
                    helper.fail("the bread did not arrive: builder " + inBuilder + ", carried max " + carried[0] + ", eaten " + (CitizenSnack.eaten.get() - eaten0) + ", queue "
                                  + job.getTaskQueue().size());
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    /**
     * W4-07: a hungry builder works in his hut, with no food in his pockets or racks. The racks are scanned once per back-off, not every check; food put into a
     * rack is found at the next check.
     */
    public static void rackScanBackoff(final GameTestHelper helper)
    {
        WorkerEfficiencyGameTests.scene(helper, "R11 rack backoff", live -> {
            live.giveTools();
            // an order without materials in the hut: he waits for them at his hut, working
            final Blueprint wall = BuilderScaffoldGameTests.shape("rack_backoff", 3, 1, 1, (x, y, z) -> true);
            live.orderFor("rack_backoff", BuilderScaffoldGameTests.SITE, wall);
            live.citizen.setSaturation(MAX_SATURATION * 0.25D);
            final int[] atHut = {0};
            final int[] scans0 = {-1};
            final int[] scansEmpty = {-1};
            final int[] eaten0 = {-1};
            WorkerEfficiencyGameTests.everyTick(live, 6000, tick -> {
                final boolean working = live.citizen.getEntity().map(e -> e instanceof EntityCitizen ec && ec.getCitizenAI().getState() == CitizenAIState.WORKING).orElse(false);
                if (working && live.hut.isInBuilding(live.citizen.getEntity().get().blockPosition()))
                {
                    if (scans0[0] < 0)
                    {
                        scans0[0] = CitizenSnack.rackScans.get();
                    }
                    atHut[0]++;
                }
                if (scansEmpty[0] < 0)
                {
                    live.citizen.setSaturation(MAX_SATURATION * 0.25D);
                    if (atHut[0] >= 1200)
                    {
                        scansEmpty[0] = CitizenSnack.rackScans.get() - scans0[0];
                        eaten0[0] = CitizenSnack.eaten.get();
                        // food arrives in a rack of his hut: the changed rack ends the back-off
                        live.stock(Items.BREAD, 8);
                    }
                    return false;
                }
                return CitizenSnack.eaten.get() > eaten0[0];
            }, () -> {
                Log.getLogger().info("R11 RESULT rack_backoff scansOver1200={} {}", scansEmpty[0], live.ai());
                helper.assertTrue(scansEmpty[0] >= 1 && scansEmpty[0] <= 4, "rack scans over 1200 ticks with empty racks: " + scansEmpty[0] + " (back-off " + CitizenSnack.RACK_BACKOFF + ")");
                helper.succeed();
            }, () -> "atHut=" + atHut[0] + " scans=" + (CitizenSnack.rackScans.get() - Math.max(0, scans0[0])) + " " + live.ai());
        });
    }

    /**
     * W4-04: the next order queued for his hut has no loaded blueprint (as after a restart), but its saved box (whole coordinates, as read from a save) says it
     * is a flat floor: he goes straight to it, no walk to the hut for scaffolding.
     */
    public static void nextOrderBoxOnlySkipsHutWalk(final GameTestHelper helper)
    {
        WorkerSettings.overrideReachForTests(null, true, true, 16);
        final int skipped0 = EntityAIStructureBuilder.hutWalksSkipped.get();
        WorkerEfficiencyGameTests.scene(helper, "R11 next order box only", live -> {
            live.setSkills(40, 40);
            live.giveTools();
            InventoryUtils.addItemStackToItemHandler(live.citizen.getInventory(), new ItemStack(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), 32));
            final Blueprint first = WorkHungerGameTests.floor("next_box_a");
            final WorkOrderDecoration a = live.orderFor("next_box_a", new BlockPos(26, 1, 12), first);
            final WorkOrderDecoration b = WorkHungerGameTests.queued(live, "next_box_b", new BlockPos(26, 1, 18), WorkHungerGameTests.floor("next_box_b"), live.hut.getID());
            b.clearBlueprint();
            final AABB box = b.getBoundingBox();
            setBox(b, new AABB((int) box.minX, (int) box.minY, (int) box.minZ, (int) box.maxX, (int) box.maxY, (int) box.maxZ));
            helper.assertTrue(!EntityAIStructureBuilder.mayNeedScaffoldingByBox(b), "precondition: a flat saved box should not need scaffolding: " + b.getBoundingBox());
            WorkerEfficiencyGameTests.everyTick(live, 6000, tick -> EntityAIStructureBuilder.hutWalksSkipped.get() > skipped0, () -> {
                Log.getLogger().info("R11 RESULT next_order_box_only skipped={} {}", EntityAIStructureBuilder.hutWalksSkipped.get() - skipped0, live.ai());
                helper.assertTrue(BuilderScaffoldGameTests.built(live, first, a.getLocation()), "the first order is not built");
                // the order has no blueprint file: it ends here
                live.colony.getWorkManager().removeWorkOrder(b.getID());
                WorkerSettings.clearOverrides();
                helper.succeed();
            }, () -> "skipped=" + (EntityAIStructureBuilder.hutWalksSkipped.get() - skipped0) + " " + live.ai());
        });
    }

    /**
     * Sets a work order's saved box (the field a save restores), for the tests.
     */
    static void setBox(final AbstractWorkOrder order, final AABB box)
    {
        try
        {
            final java.lang.reflect.Field field = AbstractWorkOrder.class.getDeclaredField("box");
            field.setAccessible(true);
            field.set(order, box);
        }
        catch (final ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * W4-05: the route hint queries of one server tick are limited (calls and provider time), and the turns are shared between the colonies that asked.
     */
    public static void routeHintTickBudget(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "R11 route budget");
        final AtomicInteger calls = new AtomicInteger();
        final IRouteHintProvider fast = (l, c, from, to) -> {
            calls.incrementAndGet();
            return null;
        };
        final IRouteHintProvider slow = (l, c, from, to) -> {
            calls.incrementAndGet();
            final long until = System.nanoTime() + 600_000L;
            while (System.nanoTime() < until)
            {
                Thread.onSpinWait();
            }
            return null;
        };
        final BlockPos from = helper.absolutePos(new BlockPos(1, 1, 1));
        final BlockPos to = from.offset(80, 0, 0);
        RouteHints.register(fast);
        try
        {
            // tick 1: one colony asks 40 times; only the tick's calls get through
            for (int i = 0; i < 40; i++)
            {
                RouteHints.query(level, colony, from, to);
            }
            helper.assertTrue(calls.get() == RouteHints.TICK_CALLS, "calls in one tick: " + calls.get());
        }
        catch (final RuntimeException e)
        {
            RouteHints.unregister(fast);
            throw e;
        }
        helper.runAfterDelay(1, () -> {
            // tick 2: the colony and a walker without a colony both ask (two askers for the next tick)
            RouteHints.query(level, colony, from, to);
            RouteHints.query(level, null, from, to);
            helper.runAfterDelay(1, () -> {
                try
                {
                    // tick 3: the colony asks 20 times first, then the walker without a colony: each gets half of the turns
                    calls.set(0);
                    for (int i = 0; i < 20; i++)
                    {
                        RouteHints.query(level, colony, from, to);
                    }
                    final int first = calls.get();
                    for (int i = 0; i < 20; i++)
                    {
                        RouteHints.query(level, null, from, to);
                    }
                    final int second = calls.get() - first;
                    Log.getLogger().info("R11 RESULT route_budget shared colony={} none={}", first, second);
                    helper.assertTrue(first == RouteHints.TICK_CALLS / 2 && second == RouteHints.TICK_CALLS / 2, "the turns were not shared: colony " + first + ", none " + second);
                }
                finally
                {
                    RouteHints.unregister(fast);
                }
                RouteHints.register(slow);
                helper.runAfterDelay(1, () -> {
                    try
                    {
                        // a slow provider (0.6 ms a call): the provider time of the tick ends the queries
                        calls.set(0);
                        for (int i = 0; i < 40; i++)
                        {
                            RouteHints.query(level, colony, from, to);
                        }
                        final long nanos = RouteHints.tickNanos();
                        Log.getLogger().info("R11 RESULT route_budget slow calls={} nanos={} refusals={}", calls.get(), nanos, RouteHints.budgetRefusals.get());
                        helper.assertTrue(calls.get() >= 3 && calls.get() <= 5, "slow calls in one tick: " + calls.get());
                        helper.assertTrue(nanos <= RouteHints.TICK_NANOS + 2_000_000L, "provider time in one tick: " + nanos);
                    }
                    finally
                    {
                        RouteHints.unregister(slow);
                    }
                    helper.succeed();
                });
            });
        });
    }

    /**
     * W2-10: the early labour pace eases out over the buildings from earlyBuildings to twice that, instead of ending at once.
     */
    public static void earlyPaceEases(final GameTestHelper helper)
    {
        helper.assertTrue(LabourSettings.easeFor(0, 10) == 0.0D && LabourSettings.easeFor(9, 10) == 0.0D, "young colonies are not at the full early pace");
        helper.assertTrue(Math.abs(LabourSettings.easeFor(10, 10) - 0.1D) < 1e-9 && Math.abs(LabourSettings.easeFor(14, 10) - 0.5D) < 1e-9, "the ease does not step per building");
        helper.assertTrue(LabourSettings.easeFor(19, 10) == 1.0D && LabourSettings.easeFor(40, 10) == 1.0D, "the normal pace is not reached at 19 buildings");
        helper.assertTrue(LabourSettings.easeFor(0, 0) == 1.0D, "earlyBuildings 0 does not switch the early pace off");
        int last = -1;
        double lastShare = 2.0D;
        for (int built = 0; built <= 25; built++)
        {
            final double ease = LabourSettings.easeFor(built, 10);
            final int grace = LabourSettings.graceFor(ease, 200, 1200);
            final double share = LabourSettings.shareFor(ease, 0.5D);
            helper.assertTrue(grace >= last && share <= lastShare, "the pace jumps back at " + built + " buildings");
            helper.assertTrue(last < 0 || grace - last <= 100, "the grace jumps by " + (grace - last) + " ticks at " + built + " buildings");
            last = grace;
            lastShare = share;
        }
        helper.assertTrue(LabourSettings.graceFor(0.0D, 200, 1200) == 200 && LabourSettings.graceFor(1.0D, 200, 1200) == 1200
                            && LabourSettings.graceFor(0.5D, 200, 1200) == 700, "the grace curve");
        helper.assertTrue(LabourSettings.graceFor(0.0D, 2000, 1200) == 1200, "an early grace above the normal one is used");
        helper.assertTrue(LabourSettings.shareFor(0.0D, 0.5D) == 1.0D && LabourSettings.shareFor(1.0D, 0.5D) == 0.5D, "the share curve");
        helper.succeed();
    }

    /**
     * W4-06 (D11 b): the soil in a builder's hut moves to the warehouse, other goods stay; the warehouse never loses what it cannot take, and a call for nothing
     * moves nothing.
     */
    public static void surplusSoilToWarehouse(final GameTestHelper helper)
    {
        final RsFixture f = RsFixture.found(helper, "r11-surplus-soil", 2);
        helper.runAfterDelay(100, () -> {
            InventoryUtils.addItemStackToItemHandler(f.builder.getItemHandlerCap(), new ItemStack(Items.DIRT, 40));
            InventoryUtils.addItemStackToItemHandler(f.builder.getItemHandlerCap(), new ItemStack(Items.COBBLESTONE, 10));
            final java.util.function.ToIntFunction<net.minecraft.world.item.Item> inHut = item -> InventoryUtils.getItemCountInItemHandler(f.builder.getItemHandlerCap(), s -> s.is(item));
            final java.util.function.IntSupplier inWarehouse = () -> InventoryUtils.getItemCountInItemHandler(f.warehouse.getItemHandlerCap(), s -> s.is(Items.DIRT));
            final int dirt0 = inHut.applyAsInt(Items.DIRT);
            helper.assertTrue(dirt0 == 40, "precondition: dirt in the hut " + dirt0);
            helper.assertTrue(SurplusToWarehouse.toWarehouse(f.colony, f.builder, SurplusToWarehouse.SOIL, 0) == 0, "max 0 moved something");
            helper.assertTrue(SurplusToWarehouse.toWarehouse(f.colony, f.warehouse, SurplusToWarehouse.SOIL, 64) == 0, "the warehouse moved into itself");
            final int part = SurplusToWarehouse.toWarehouse(f.colony, f.builder, SurplusToWarehouse.SOIL, 15);
            final int rest = SurplusToWarehouse.toWarehouse(f.colony, f.builder, SurplusToWarehouse.SOIL, 64);
            Log.getLogger().info("R11 RESULT surplus_soil part={} rest={} hut={} warehouse={}", part, rest, inHut.applyAsInt(Items.DIRT), inWarehouse.getAsInt());
            helper.assertTrue(part == 15 && rest == 25, "moved " + part + " then " + rest);
            helper.assertTrue(inHut.applyAsInt(Items.DIRT) == 0 && inWarehouse.getAsInt() == 40, "dirt hut " + inHut.applyAsInt(Items.DIRT) + " warehouse " + inWarehouse.getAsInt());
            helper.assertTrue(inHut.applyAsInt(Items.COBBLESTONE) == 10, "other goods left the hut");
            helper.succeed();
        });
    }
}
