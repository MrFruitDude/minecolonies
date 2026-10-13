package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.requestsystem.RsFlags;
import com.minecolonies.core.entity.ai.workers.service.EntityAIWorkDeliveryman;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClocks;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * RS4: what a courier does between two tasks (glob {@code minecolonies:rs_courier_*}).
 * <p>
 * The decision state of the courier only counts its delay down while the courier is in it, so after a finished task the
 * courier used to sit in the state for almost the whole delay (5 s) before it looked at its queue. With
 * {@code courierImmediateNextTask} it looks at once.
 */
final class RequestCourierGameTests
{
    /**
     * The decision delay of the courier AI, in ticks (5 s).
     */
    private static final int DECISION_DELAY = 100;

    private RequestCourierGameTests()
    {
    }

    /**
     * Ticks the delivery may take once the courier holds it, break or no break (a delivery to the builder hut, 8 blocks away, takes well under 600).
     */
    private static final int DELIVERY_BUDGET = 1500;

    static void register(final RegisterGameTestsEvent event)
    {
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_courier_leisure_keeps_task"),
          info -> new MinecoloniesGameTestInstance(info, RequestCourierGameTests::leisureKeepsTask),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, "rs_courier_leisure_keeps_task"));
        for (final Break kind : Break.values())
        {
            final String name = "rs_courier_finishes_delivery_before_" + kind.label;
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, helper -> finishFirst(helper, name, kind, true)),
              MinecoloniesGameTestRegistrar.isolatedColonyData(event, name));
        }
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_courier_finishes_delivery_off_leaves_at_once"),
          info -> new MinecoloniesGameTestInstance(info, helper -> finishFirst(helper, "rs-courier-finish-off", Break.MEAL, false)),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, "rs_courier_finishes_delivery_off_leaves_at_once"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_courier_critical_hunger_hands_task_back"),
          info -> new MinecoloniesGameTestInstance(info, RequestCourierGameTests::criticalHunger),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, "rs_courier_critical_hunger_hands_task_back"));
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, "rs_courier_long_task_hands_back"),
          info -> new MinecoloniesGameTestInstance(info, RequestCourierGameTests::longTaskHandsBack),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, "rs_courier_long_task_hands_back"));
        for (final boolean immediate : new boolean[] {true, false})
        {
            final String name = "rs_courier_next_task_" + (immediate ? "immediate" : "delayed");
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
              info -> new MinecoloniesGameTestInstance(info, helper -> nextTask(helper, immediate)),
              MinecoloniesGameTestRegistrar.isolatedColonyData(event, name));
        }
    }

    /**
     * Two deliveries to two buildings, so the courier has two separate tasks (deliveries to one building are carried together).
     * Measured: the longest run of ticks the courier spends in the decision state between the two tasks (the second one waiting
     * in the warehouse queue).
     *
     * @param immediate the {@code courierImmediateNextTask} switch.
     */
    static void nextTask(final GameTestHelper helper, final boolean immediate)
    {
        RsFlags.overrideCourierImmediateNextTask(immediate);
        final RsFixture f = RsFixture.found(helper, "rs-courier-next-" + immediate, 4);
        final IBuilding deliveryman = f.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");
        final IBuilding far = f.place(ModBlocks.blockHutLumberjack, new BlockPos(24, 1, 22), "fundamentals/lumberjack1.blueprint");
        f.stock(0, new ItemStack(Items.OAK_LOG, 60));
        helper.runAfterDelay(200, () -> {
            final ICitizenData courier = f.spawn(new BlockPos(34, 1, 8), false);
            helper.assertTrue(deliveryman.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier), "courier not assigned to the courier hut");
            helper.assertTrue(f.warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "courier not assigned to the warehouse");
            courier.getEntity().ifPresent(e -> e.setPos(f.warehouse.getID().getX() + 0.5D, f.warehouse.getID().getY(), f.warehouse.getID().getZ() + 0.5D));
            final JobDeliveryman job = courier.getJob(JobDeliveryman.class);
            f.builder.createRequest(new Stack(new ItemStack(Items.OAK_LOG, 8), 8, 8), false);
            far.createRequest(new Stack(new ItemStack(Items.OAK_LOG, 8), 8, 8), false);

            final long start = f.level.getGameTime();
            final int[] run = {0};
            final int[] longest = {0};
            final boolean[] firstDone = {false};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                final int inBuilder = InventoryUtils.getItemCountInItemHandler(f.builder.getItemHandlerCap(), s -> s.is(Items.OAK_LOG));
                final int inFar = InventoryUtils.getItemCountInItemHandler(far.getItemHandlerCap(), s -> s.is(Items.OAK_LOG));
                final long elapsed = f.level.getGameTime() - start;
                final boolean bothDone = inBuilder >= 8 && inFar >= 8;
                if ((inBuilder >= 8 || inFar >= 8) && !firstDone[0])
                {
                    firstDone[0] = true;
                }
                // The courier carried the first delivery and has the second one still waiting in the warehouse queue: how long does it sit in the decision state?
                final boolean deciding = job.getWorkerAI() != null && String.valueOf(job.getWorkerAI().getState()).equals("START_WORKING") && job.getTaskQueue().isEmpty();
                if (firstDone[0] && !bothDone && deciding)
                {
                    longest[0] = Math.max(longest[0], ++run[0]);
                }
                else
                {
                    run[0] = 0;
                }
                if (bothDone)
                {
                    Log.getLogger().info("RS_METRIC courier_next_task immediate={} ticks={} longestDecidingBetweenTasks={}", immediate, elapsed, longest[0]);
                    if (immediate)
                    {
                        helper.assertTrue(longest[0] < DECISION_DELAY / 4, "with the switch on the courier decides at once, but it waited " + longest[0] + " ticks before its next task");
                    }
                    else
                    {
                        helper.assertTrue(longest[0] > DECISION_DELAY / 2, "with the switch off the courier keeps the decision delay, but it waited " + longest[0] + " ticks");
                    }
                    RsFlags.overrideCourierImmediateNextTask(null);
                    helper.succeed();
                    return;
                }
                if (elapsed > 9000)
                {
                    helper.fail("the deliveries did not arrive: builder " + inBuilder + ", far " + inFar + ", state " + (job.getWorkerAI() == null ? "-" : job.getWorkerAI().getState()));
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    /**
     * The stuck courier of the benchmark: a courier that holds a task and then gets a leisure break (the citizen AI hands a worker 3 minutes of
     * walking about at random times, when the worker AI is in a state that "may be interrupted", and PREPARE_DELIVERY was one) stops
     * working on it, and the delivery, and whoever waits for it, stands still for the break while other couriers idle. A courier with a task
     * takes no leisure: the delivery arrives within {@link #DELIVERY_BUDGET} ticks although a break is due the moment the task is taken.
     */
    static void leisureKeepsTask(final GameTestHelper helper)
    {
        final RsFixture f = RsFixture.found(helper, "rs-courier-leisure", 4);
        final IBuilding deliveryman = f.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");
        f.stock(0, new ItemStack(Items.OAK_LOG, 60));
        helper.runAfterDelay(200, () -> {
            final ICitizenData courier = f.spawn(new BlockPos(34, 1, 8), false);
            helper.assertTrue(deliveryman.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier), "courier not assigned to the courier hut");
            helper.assertTrue(f.warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "courier not assigned to the warehouse");
            courier.getEntity().ifPresent(e -> e.setPos(f.warehouse.getID().getX() + 0.5D, f.warehouse.getID().getY(), f.warehouse.getID().getZ() + 0.5D));
            final JobDeliveryman job = courier.getJob(JobDeliveryman.class);
            f.builder.createRequest(new Stack(new ItemStack(Items.OAK_LOG, 8), 8, 8), false);

            final long start = f.level.getGameTime();
            final long[] tookAt = {-1};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                final long now = f.level.getGameTime();
                if (tookAt[0] < 0 && !job.getTaskQueue().isEmpty())
                {
                    tookAt[0] = now;
                    setLeisure(courier, 3600);
                }
                final int inBuilder = InventoryUtils.getItemCountInItemHandler(f.builder.getItemHandlerCap(), s -> s.is(Items.OAK_LOG));
                if (inBuilder >= 8)
                {
                    Log.getLogger().info("RS_METRIC courier_leisure took_task_at={} delivered_after={}", tookAt[0] - start, now - tookAt[0]);
                    helper.assertTrue(tookAt[0] >= 0, "the courier took the task");
                    helper.assertTrue(now - tookAt[0] <= DELIVERY_BUDGET, "the delivery took " + (now - tookAt[0]) + " ticks after the courier took it");
                    helper.succeed();
                    return;
                }
                if (tookAt[0] >= 0 && now - tookAt[0] > DELIVERY_BUDGET)
                {
                    helper.fail("the courier took the task " + (now - tookAt[0]) + " ticks ago and the delivery is not there: it is on a leisure break (leisure " + courier.getLeisureTime()
                                  + ", queue " + job.getTaskQueue().size() + ", worker AI " + (job.getWorkerAI() == null ? "-" : job.getWorkerAI().getState()) + ")");
                    return;
                }
                if (now - start > 9000)
                {
                    helper.fail("the courier never took the task");
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    /**
     * The breaks a courier that holds a task is sent on by the citizen AI.
     */
    enum Break
    {
        MEAL("meal", CitizenAIState.EATING),
        SLEEP("sleep", CitizenAIState.SLEEP),
        RAIN("rain", CitizenAIState.IDLE);

        final String  label;
        final IState state;

        Break(final String label, final IState state)
        {
            this.label = label;
            this.state = state;
        }

        /**
         * Makes the break due: hungry, night, rain.
         */
        void makeDue(final ICitizenData courier, final ServerLevel level)
        {
            switch (this)
            {
                case MEAL -> courier.setSaturation(2.0D);
                case SLEEP -> level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 18000L);
                case RAIN ->
                {
                    final var weather = level.getWeatherData();
                    weather.setClearWeatherTime(0);
                    weather.setRaining(true);
                    weather.setRainTime(1_000_000);
                }
            }
        }
    }

    private static void restoreWorld(final ServerLevel level)
    {
        level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
        final var weather = level.getWeatherData();
        weather.setRaining(false);
        weather.setThundering(false);
        weather.setClearWeatherTime(1_000_000);
    }

    @Nullable
    private static IState citizenState(final ICitizenData citizen)
    {
        return citizen.getEntity().isPresent() && citizen.getEntity().get() instanceof EntityCitizen entity ? entity.getCitizenAI().getState() : null;
    }

    /**
     * Sets the ticks the courier has held its head task, to the past (the cap of a long delivery).
     */
    private static void ageTask(final JobDeliveryman job, final long ticks)
    {
        job.currentTaskAge();   // makes the job note the task it holds (and when), so that the age below is the one that counts
        try
        {
            final java.lang.reflect.Field field = JobDeliveryman.class.getDeclaredField("headSince");
            field.setAccessible(true);
            field.setLong(job, field.getLong(job) - ticks);
        }
        catch (final ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A courier that holds a task and is due a meal, sleep or shelter from the rain (the moment it takes the task) delivers first and goes then; with the
     * switch off it goes at once, as before.
     *
     * @param kind the break that becomes due.
     * @param on   the {@code courierFinishesDelivery} switch.
     */
    static void finishFirst(final GameTestHelper helper, final String name, final Break kind, final boolean on)
    {
        RsFlags.overrideCourierFinishesDelivery(on);
        final RsFixture f = RsFixture.found(helper, name, 4);
        final IBuilding deliveryman = f.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");
        f.stock(0, new ItemStack(Items.OAK_LOG, 60));
        final Runnable cleanup = () -> {
            RsFlags.overrideCourierFinishesDelivery(null);
            restoreWorld(f.level);
        };
        helper.runAfterDelay(200, () -> {
            final ICitizenData courier = f.spawn(new BlockPos(34, 1, 8), false);
            helper.assertTrue(deliveryman.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier), "courier not assigned to the courier hut");
            helper.assertTrue(f.warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "courier not assigned to the warehouse");
            courier.getEntity().ifPresent(e -> e.setPos(f.warehouse.getID().getX() + 0.5D, f.warehouse.getID().getY(), f.warehouse.getID().getZ() + 0.5D));
            final JobDeliveryman job = courier.getJob(JobDeliveryman.class);
            f.builder.createRequest(new Stack(new ItemStack(Items.OAK_LOG, 8), 8, 8), false);

            final long start = f.level.getGameTime();
            final long[] at = {-1, -1, -1};   // took the task, delivered, break began
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                final long now = f.level.getGameTime();
                if (at[0] < 0 && !job.getTaskQueue().isEmpty())
                {
                    at[0] = now;
                    kind.makeDue(courier, f.level);
                }
                if (at[1] < 0 && InventoryUtils.getItemCountInItemHandler(f.builder.getItemHandlerCap(), s -> s.is(Items.OAK_LOG)) >= 8)
                {
                    at[1] = now;
                }
                if (at[0] >= 0 && at[2] < 0 && citizenState(courier) == kind.state)
                {
                    at[2] = now;
                }
                final String where = kind.label + " on=" + on + ": took the task at " + (at[0] - start) + ", delivered at " + (at[1] < 0 ? "-" : String.valueOf(at[1] - start)) + ", break began at "
                                       + (at[2] < 0 ? "-" : String.valueOf(at[2] - start)) + ", citizen state " + citizenState(courier) + ", queue " + job.getTaskQueue().size();
                if (on && at[2] >= 0 && at[1] < 0)
                {
                    cleanup.run();
                    helper.fail("the courier left for its " + where + " before the delivery was done");
                    return;
                }
                if (on && at[1] >= 0 && at[2] >= 0)
                {
                    Log.getLogger().info("RS_METRIC courier_finishes {}", where);
                    cleanup.run();
                    helper.succeed();
                    return;
                }
                if (!on && at[2] >= 0)
                {
                    Log.getLogger().info("RS_METRIC courier_finishes {}", where);
                    cleanup.run();
                    helper.assertTrue(at[1] < 0 || at[1] > at[2], "with the switch off the courier leaves at once: " + where);
                    helper.succeed();
                    return;
                }
                if (at[0] >= 0 && now - at[0] > DELIVERY_BUDGET + 600 || now - start > 9000)
                {
                    cleanup.run();
                    helper.fail("timeout: " + where);
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    /**
     * A courier that is critically hungry does not wait for its task: it goes to eat at once and the task goes back to the pool, where another courier
     * takes it. Nothing stays in the queue of the courier that is away.
     */
    static void criticalHunger(final GameTestHelper helper)
    {
        RsFlags.overrideCourierFinishesDelivery(true);
        twoCouriers(helper, "rs-courier-critical", (f, holder, other, job, tookAt) -> holder.setSaturation(0.5D), (f, holder, job, now) -> {
            // gone for its meal with an empty queue
            return citizenState(holder) == CitizenAIState.EATING && job.getTaskQueue().isEmpty();
        }, "critically hungry");
    }

    /**
     * A courier whose delivery has taken longer than the cap and who is due a meal goes, and the task goes back to the pool, where another courier finishes it.
     */
    static void longTaskHandsBack(final GameTestHelper helper)
    {
        RsFlags.overrideCourierFinishesDelivery(true);
        twoCouriers(helper, "rs-courier-long", (f, holder, other, job, tookAt) -> {
            holder.setSaturation(2.0D);
            ageTask(job, EntityAIWorkDeliveryman.FINISH_TASK_CAP + 600);
        }, (f, holder, job, now) -> citizenState(holder) == CitizenAIState.EATING && job.getTaskQueue().isEmpty(), "held too long");
    }

    @FunctionalInterface
    private interface Setup
    {
        void apply(RsFixture f, ICitizenData holder, ICitizenData other, JobDeliveryman job, long tookAt);
    }

    @FunctionalInterface
    private interface Left
    {
        boolean test(RsFixture f, ICitizenData holder, JobDeliveryman job, long now);
    }

    /**
     * Two couriers, one delivery. Whoever takes it gets {@code setup} applied; it must leave with an empty queue ({@code left}) and the other courier
     * must deliver within the budget.
     */
    private static void twoCouriers(final GameTestHelper helper, final String name, final Setup setup, final Left left, final String why)
    {
        final RsFixture f = RsFixture.found(helper, name, 4);
        final IBuilding hutA = f.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");
        final IBuilding hutB = f.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 20), "craftsmanship/storage/deliveryman1.blueprint");
        f.stock(0, new ItemStack(Items.OAK_LOG, 60));
        helper.runAfterDelay(200, () -> {
            final ICitizenData a = f.spawn(new BlockPos(34, 1, 8), false);
            final ICitizenData b = f.spawn(new BlockPos(36, 1, 8), false);
            for (final ICitizenData courier : new ICitizenData[] {a, b})
            {
                final IBuilding deliveryman = courier == a ? hutA : hutB;
                helper.assertTrue(deliveryman.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier), "courier not assigned to the courier hut");
                helper.assertTrue(f.warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "courier not assigned to the warehouse");
                courier.setSaturation(20.0D);
                courier.getEntity().ifPresent(e -> e.setPos(f.warehouse.getID().getX() + 0.5D, f.warehouse.getID().getY(), f.warehouse.getID().getZ() + 0.5D));
            }
            f.builder.createRequest(new Stack(new ItemStack(Items.OAK_LOG, 8), 8, 8), false);

            final long start = f.level.getGameTime();
            final ICitizenData[] holder = {null};
            final long[] tookAt = {-1};
            final boolean[] leftSeen = {false};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                final long now = f.level.getGameTime();
                if (holder[0] == null)
                {
                    for (final ICitizenData courier : new ICitizenData[] {a, b})
                    {
                        if (!courier.getJob(JobDeliveryman.class).getTaskQueue().isEmpty())
                        {
                            holder[0] = courier;
                            tookAt[0] = now;
                            setup.apply(f, courier, courier == a ? b : a, courier.getJob(JobDeliveryman.class), now);
                        }
                    }
                }
                final boolean delivered = InventoryUtils.getItemCountInItemHandler(f.builder.getItemHandlerCap(), s -> s.is(Items.OAK_LOG)) >= 8;
                if (holder[0] != null && !leftSeen[0] && left.test(f, holder[0], holder[0].getJob(JobDeliveryman.class), now))
                {
                    leftSeen[0] = true;
                }
                if (leftSeen[0] && delivered)
                {
                    Log.getLogger().info("RS_METRIC courier_hands_back {}: took the task at {}, left (queue empty) and delivered by the other courier at {}", why, tookAt[0] - start, now - start);
                    RsFlags.overrideCourierFinishesDelivery(null);
                    helper.succeed();
                    return;
                }
                if (delivered && !leftSeen[0])
                {
                    RsFlags.overrideCourierFinishesDelivery(null);
                    helper.fail("the delivery arrived but the courier never left for its meal (" + why + "), state " + citizenState(holder[0]));
                    return;
                }
                if (now - start > 6000)
                {
                    RsFlags.overrideCourierFinishesDelivery(null);
                    helper.fail("timeout (" + why + "): holder " + (holder[0] == null ? "none" : String.valueOf(citizenState(holder[0])) + " queue " + holder[0].getJob(JobDeliveryman.class).getTaskQueue().size())
                                  + ", left " + leftSeen[0] + ", delivered " + delivered);
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    private static void setLeisure(final ICitizenData citizen, final int ticks)
    {
        try
        {
            final java.lang.reflect.Field field = com.minecolonies.core.colony.CitizenData.class.getDeclaredField("leisureTime");
            field.setAccessible(true);
            field.setInt(citizen, ticks);
        }
        catch (final ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
