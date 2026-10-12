package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.constant.Constants;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.requestsystem.RsFlags;
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

    static void register(final RegisterGameTestsEvent event)
    {
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
     * Measured: the longest run of ticks the courier spends in the decision state with a task waiting in its queue after it
     * finished the first task.
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
            final boolean[] afterFirst = {false};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                final int queued = job.getTaskQueue().size();
                final boolean deciding = job.getWorkerAI() != null && String.valueOf(job.getWorkerAI().getState()).equals("START_WORKING");
                if (queued == 1 && !afterFirst[0])
                {
                    afterFirst[0] = true;
                }
                if (afterFirst[0] && queued > 0 && deciding)
                {
                    longest[0] = Math.max(longest[0], ++run[0]);
                }
                else
                {
                    run[0] = 0;
                }
                final int inBuilder = InventoryUtils.getItemCountInItemHandler(f.builder.getItemHandlerCap(), s -> s.is(Items.OAK_LOG));
                final int inFar = InventoryUtils.getItemCountInItemHandler(far.getItemHandlerCap(), s -> s.is(Items.OAK_LOG));
                final long elapsed = f.level.getGameTime() - start;
                if (inBuilder >= 8 && inFar >= 8)
                {
                    Log.getLogger().info("RS_METRIC courier_next_task immediate={} ticks={} longestDecidingWithTaskQueued={}", immediate, elapsed, longest[0]);
                    helper.assertTrue(afterFirst[0], "the courier carried the two deliveries as separate tasks");
                    if (immediate)
                    {
                        helper.assertTrue(longest[0] < DECISION_DELAY / 4, "with the switch on the courier decides at once, but it waited " + longest[0] + " ticks with a task queued");
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
                    helper.fail("the deliveries did not arrive: builder " + inBuilder + ", far " + inFar + ", queued " + queued + ", state "
                                  + (job.getWorkerAI() == null ? "-" : job.getWorkerAI().getState()));
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }
}
