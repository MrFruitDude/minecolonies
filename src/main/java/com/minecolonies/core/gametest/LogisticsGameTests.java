package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.ICraftingBuildingModule;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.request.WaitReason;
import com.minecolonies.api.colony.requestsystem.requestable.MinimumStack;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Pickup;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.crafting.RecipeStorage;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.MinimumStockModule;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.requestsystem.priority.BuildingPriorities;
import com.minecolonies.core.colony.requestsystem.priority.RequestImportance;
import com.minecolonies.core.colony.requestsystem.priority.RequestScoring;
import com.minecolonies.core.colony.requestsystem.resolvers.core.AbstractWarehouseRequestResolver;
import com.minecolonies.core.colony.requestsystem.wait.RequestWaitTracker;
import com.minecolonies.core.entity.ai.workers.service.EntityAIWorkDeliveryman;
import com.google.common.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Round 13 lane mc-logistics: couriers and pickups (glob {@code minecolonies:lg_*}; group {@code MINECOLONIES_GAMETEST_ONLY=logistics}).
 * <p>
 * The scoring checks read {@link RequestScoring#explain} rather than a courier's pick, because other tests in the same JVM switch the
 * {@code priorityClasses} flag for their own checks. Layout as {@link RsFixture}: builder (8,1,2), warehouse (16,1,2), a lumberjack hut "far"
 * at (24,1,22), the courier hut at (32,1,2).
 */
final class LogisticsGameTests
{
    private LogisticsGameTests()
    {
    }

    static void register(final RegisterGameTestsEvent event)
    {
        reg(event, "lg_carry_and_batch", LogisticsGameTests::carryAndBatch);
        reg(event, "lg_wait_buckets", LogisticsGameTests::waitBuckets);
        reg(event, "lg_pickup_rules", LogisticsGameTests::pickupRules);
        reg(event, "lg_refill_rules", LogisticsGameTests::refillRules);
        reg(event, "lg_force_pickup_raises", LogisticsGameTests::forcePickupRaises);
        reg(event, "lg_pickup_slider_and_urgent", LogisticsGameTests::pickupSliderAndUrgent);
        reg(event, "lg_hut_full_pickup", LogisticsGameTests::hutFullPickup);
        reg(event, "lg_courier_wakes", LogisticsGameTests::courierWakes);
        reg(event, "lg_bulk_pickup", LogisticsGameTests::bulkPickup);
        reg(event, "lg_requeue_on_break", LogisticsGameTests::requeueOnBreak);
        reg(event, "lg_wake_by_free_couriers", LogisticsGameTests::wakeByFreeCouriers);
        reg(event, "lg_remainder_to_crafters", helper -> remainderToCrafters(helper, REMAINDER_CRAFTER));
        reg(event, "lg_remainder_no_crafter", helper -> remainderToCrafters(helper, REMAINDER_NO_CRAFTER));
        reg(event, "lg_remainder_no_inputs", helper -> remainderToCrafters(helper, REMAINDER_NO_INPUTS));
        reg(event, "lg_remainder_builder_sawmill", LogisticsGameTests::remainderBuilderSawmill);
        reg(event, "lg_unloaded_hut_not_full", LogisticsGameTests::unloadedHutNotFull);
        reg(event, "lg_batch_by_stacks", LogisticsGameTests::batchByStacks);
        reg(event, "lg_min_stock_refill", LogisticsGameTests::minimumStockRefill);
    }

    private static void reg(final RegisterGameTestsEvent event, final String name, final Consumer<GameTestHelper> test)
    {
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
          info -> new MinecoloniesGameTestInstance(info, test),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, name));
    }

    // ------------------------------------------------------------------ pure rules

    /**
     * L6 carry floor and L2 batching by carry capacity.
     */
    static void carryAndBatch(final GameTestHelper helper)
    {
        helper.assertTrue(JobDeliveryman.carryStacks(1, 5) == 4, "level 1 carries 4 stacks (decision), was 2: " + JobDeliveryman.carryStacks(1, 5));
        helper.assertTrue(JobDeliveryman.carryStacks(2, 5) == 4, "level 2: " + JobDeliveryman.carryStacks(2, 5));
        helper.assertTrue(JobDeliveryman.carryStacks(3, 5) == 5, "level 3: " + JobDeliveryman.carryStacks(3, 5));
        helper.assertTrue(JobDeliveryman.carryStacks(4, 5) == 9, "level 4: " + JobDeliveryman.carryStacks(4, 5));
        helper.assertTrue(JobDeliveryman.carryStacks(5, 5) == Integer.MAX_VALUE, "the top level has no limit");
        helper.assertTrue(JobDeliveryman.maxParallelDeliveries(1, 1, 5) == 4, "a novice courier batches by its carry capacity: " + JobDeliveryman.maxParallelDeliveries(1, 1, 5));
        helper.assertTrue(JobDeliveryman.maxParallelDeliveries(1, 5, 5) == JobDeliveryman.MAX_CAPACITY_BATCH, "no limit is capped");
        helper.assertTrue(JobDeliveryman.maxParallelDeliveries(11, 1, 5) == 11, "skill may allow more");
        helper.assertTrue(JobDeliveryman.maxParallelDeliveries(1, 1, 5) >= JobDeliveryman.MIN_BATCH, "floor 3");
        helper.succeed();
    }

    /**
     * L3: buckets, the courier's walk inside a bucket, and the starvation cap.
     */
    static void waitBuckets(final GameTestHelper helper)
    {
        final long s = RequestScoring.TICKS_PER_SECOND;
        helper.assertTrue(RequestScoring.waitTerm(0) == 0 && RequestScoring.waitTerm(44 * s) == 0, "first bucket");
        helper.assertTrue(RequestScoring.waitTerm(45 * s) == 45 && RequestScoring.waitTerm(179 * s) == 135, "buckets of 45 s");
        helper.assertTrue(RequestScoring.waitTerm(180 * s) == 180 && RequestScoring.waitTerm(200 * s) == 200, "exact from the cap");
        long last = -1;
        for (long t = 0; t < 400 * s; t += s)
        {
            final long term = RequestScoring.waitTerm(t);
            helper.assertTrue(term >= last, "the wait term never drops: " + t);
            last = term;
        }
        final RequestImportance n = RequestImportance.NORMAL;
        // Same bucket: the shorter walk wins although the other waited longer.
        helper.assertTrue(RequestScoring.score(n, 5, 10 * s, 20) > RequestScoring.score(n, 5, 40 * s, 300), "inside a bucket the nearer task first");
        // Different buckets: the older one wins whatever the walk.
        helper.assertTrue(RequestScoring.score(n, 5, 50 * s, 3000) > RequestScoring.score(n, 5, 10 * s, 5), "an older bucket first");
        // Starvation cap: a faraway task that waited 3 minutes beats every newcomer.
        helper.assertTrue(RequestScoring.score(n, 5, 181 * s, 60000) > RequestScoring.score(n, 5, 179 * s, 0), "past the cap the exact wait counts");
        // Priority and importance still dominate.
        helper.assertTrue(RequestScoring.score(n, 6, 0, 60000) > RequestScoring.score(n, 5, 300 * s, 0), "priority before wait");
        helper.assertTrue(RequestScoring.score(RequestImportance.URGENT, 1, 0, 60000) > RequestScoring.score(n, 9, 300 * s, 0), "importance before priority");
        // The courier's own walk: to the source, then to the target.
        final BlockPos courier = new BlockPos(0, 0, 0);
        helper.assertTrue(RequestScoring.etaBlocks(courier, new BlockPos(10, 0, 0), new BlockPos(10, 0, 5), null) == 15, "delivery walk");
        helper.assertTrue(RequestScoring.etaBlocks(courier, null, new BlockPos(0, 0, 7), new BlockPos(0, 0, 3)) == 11, "pickup walk: to the hut and back to the warehouse");
        helper.succeed();
    }

    /**
     * L1 and B1 rules: pickup importance, the pickup slider as the building term, the forced raise, aging never lowers a forced pickup.
     */
    static void pickupRules(final GameTestHelper helper)
    {
        helper.assertTrue(RequestImportance.ofPickup(5, false) == RequestImportance.BACKGROUND, "a sweep is background");
        helper.assertTrue(RequestImportance.ofPickup(10, false) == RequestImportance.NORMAL, "an aged sweep is normal");
        helper.assertTrue(RequestImportance.ofPickup(5, true) == RequestImportance.URGENT, "a blocking pickup is urgent");
        helper.assertTrue(RequestScoring.pickupPriorityTerm(15, false) == BuildingPriorities.MAX_PRIORITY, "forced clamps to 9");
        helper.assertTrue(RequestScoring.pickupPriorityTerm(3, false) == 3 && RequestScoring.pickupPriorityTerm(0, false) == 1, "the slider counts");
        helper.assertTrue(RequestScoring.pickupPriorityTerm(3, true) == BuildingPriorities.ALARM_PRIORITY, "the alarm wins");

        final Pickup open = new Pickup(5, 7, 16);
        AbstractBuilding.raisePickup(open, 16, true, 15, 3);
        helper.assertTrue(open.getPriority() == 15 && open.getDay() == 3 && open.getQuantity() == 32, "forced: priority up, due today, more items: " + open.getPriority() + "/" + open.getDay());
        helper.assertTrue(RequestImportance.isForcedPickup(open), "it reads as forced");
        open.incrementPriorityDueToAging();
        helper.assertTrue(open.getPriority() == 15, "aging never lowers a forced pickup: " + open.getPriority());
        final Pickup sweep = new Pickup(5, 9, 16);
        sweep.incrementPriorityDueToAging();
        helper.assertTrue(sweep.getPriority() == 6 && !RequestImportance.isForcedPickup(sweep), "a sweep still ages");
        AbstractBuilding.raisePickup(sweep, 16, false, 5, 3);
        helper.assertTrue(sweep.getDay() < 9 && sweep.getPriority() == 6, "unforced: a little earlier, priority kept: " + sweep.getDay());
        helper.succeed();
    }

    /**
     * L5 refill size and L7's pure rule.
     */
    static void refillRules(final GameTestHelper helper)
    {
        helper.assertTrue(MinimumStockModule.refillCount(64, 300) == 256, "at most 4 stacks: " + MinimumStockModule.refillCount(64, 300));
        helper.assertTrue(MinimumStockModule.refillCount(64, 10) == 10 && MinimumStockModule.refillCount(1, 5) == 4, "the missing count, within 4 stacks");
        helper.assertTrue(MinimumStockModule.refillMinimum(64, 256) == 16 && MinimumStockModule.refillMinimum(64, 10) == 2, "a quarter stack");
        helper.assertTrue(MinimumStockModule.refillMinimum(1, 4) == 1 && MinimumStockModule.refillMinimum(16, 3) == 1, "at least one");
        helper.assertTrue(AbstractWarehouseRequestResolver.remainderWorthCrafting(true, 2, 64), "little in stock: craft the rest");
        helper.assertTrue(!AbstractWarehouseRequestResolver.remainderWorthCrafting(true, 16, 64), "a quarter is handed out");
        helper.assertTrue(!AbstractWarehouseRequestResolver.remainderWorthCrafting(false, 2, 64), "never without the ledger");
        helper.assertTrue(!AbstractWarehouseRequestResolver.remainderWorthCrafting(true, 0, 64) && !AbstractWarehouseRequestResolver.remainderWorthCrafting(true, 64, 64),
          "nothing or everything is not a remainder");
        helper.succeed();
    }

    // ------------------------------------------------------------------ scenes

    private record Scene(RsFixture f, IBuilding courierHut, IBuilding far, ICitizenData courier, JobDeliveryman job)
    {
    }

    /**
     * The fixture with the lumberjack hut and one courier (assigned to the courier hut and the warehouse) at the warehouse.
     */
    private static void scene(final GameTestHelper helper, final String name, final boolean courierAi, final Consumer<Scene> body)
    {
        final RsFixture f = RsFixture.found(helper, name, 4);
        final IBuilding courierHut = f.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");
        final IBuilding far = f.place(ModBlocks.blockHutLumberjack, new BlockPos(24, 1, 22), "fundamentals/lumberjack1.blueprint");
        f.stock(0, new ItemStack(Items.OAK_LOG, 60));
        helper.runAfterDelay(200, () -> {
            final ICitizenData courier = f.spawn(new BlockPos(34, 1, 8), !courierAi);
            helper.assertTrue(courierHut.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier), "courier not assigned to the courier hut");
            helper.assertTrue(f.warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "courier not assigned to the warehouse");
            courier.setSaturation(20.0D);
            courier.getEntity().ifPresent(e -> e.setPos(f.warehouse.getID().getX() + 0.5D, f.warehouse.getID().getY(), f.warehouse.getID().getZ() + 0.5D));
            body.accept(new Scene(f, courierHut, far, courier, courier.getJob(JobDeliveryman.class)));
        });
    }

    @SuppressWarnings("unchecked")
    private static IRequest<Pickup> openPickup(final RsFixture f, final IBuilding hut)
    {
        final var tokens = ((AbstractBuilding) hut).getOpenRequestsByRequestableType().getOrDefault(com.minecolonies.api.util.constant.TypeConstants.PICKUP, List.of());
        f.helper.assertTrue(tokens.size() == 1, "one open pickup at " + hut.getBuildingType() + ": " + tokens.size());
        final IRequest<?> request = f.request(tokens.iterator().next());
        f.helper.assertTrue(request != null && request.getRequest() instanceof Pickup, "it is a pickup");
        return (IRequest<Pickup>) request;
    }

    private static boolean queued(final RsFixture f, final IToken<?> token)
    {
        return f.warehouse.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE).getMutableRequestList().contains(token);
    }

    private static RequestScoring.Explanation why(final RsFixture f, final IRequest<?> task, final IBuilding target)
    {
        return RequestScoring.explain(f.colony, task, f.warehouse.getID(), target.getID(), true);
    }

    /**
     * B1: forcing a pickup that is already queued raises it (it never did: the old check tested the token, not the request).
     */
    static void forcePickupRaises(final GameTestHelper helper)
    {
        scene(helper, "lg-force", false, s -> {
            helper.assertTrue(s.f().builder.createPickupRequest(16, false), "the sweep was created");
            final IRequest<Pickup> pickup = openPickup(s.f(), s.f().builder);
            helper.assertTrue(pickup.getState() == RequestState.IN_PROGRESS && queued(s.f(), pickup.getId()), "queued at the warehouse: " + s.f().describe(pickup.getId()));
            helper.assertTrue(RequestImportance.of(s.f().manager(), pickup) == RequestImportance.BACKGROUND, "a sweep is background: " + RequestImportance.of(s.f().manager(), pickup));
            final int day = s.f().colony.getDay();
            helper.assertTrue(pickup.getRequest().getDay() > day, "fixture: a sweep is booked for a later day: " + pickup.getRequest().getDay() + " today " + day);

            helper.assertTrue(!s.f().builder.createPickupRequest(16, true), "no second pickup");
            final IRequest<Pickup> after = openPickup(s.f(), s.f().builder);
            helper.assertTrue(after.getId().equals(pickup.getId()), "the same request");
            final Pickup raised = after.getRequest();
            Log.getLogger().info("LG_METRIC force_pickup priority={} day={} today={} qty={}", raised.getPriority(), raised.getDay(), day, raised.getQuantity());
            helper.assertTrue(RequestImportance.isForcedPickup(raised) && raised.getDay() <= day && raised.getQuantity() == 32, "raised: priority " + raised.getPriority() + ", day "
                                                                                                                                + raised.getDay() + ", qty " + raised.getQuantity());
            helper.assertTrue(why(s.f(), after, s.f().builder).importance() == RequestImportance.URGENT, "now urgent: " + why(s.f(), after, s.f().builder));
            helper.succeed();
        });
    }

    /**
     * L1: the pickup slider is the building term (the far hut's 9 beats the near hut's 2), and a forced pickup beats an ordinary delivery.
     */
    static void pickupSliderAndUrgent(final GameTestHelper helper)
    {
        scene(helper, "lg-slider", false, s -> {
            final RsFixture f = s.f();
            ((AbstractBuilding) f.builder).alterPickUpPriority(-3);
            ((AbstractBuilding) s.far()).alterPickUpPriority(4);
            helper.assertTrue(f.builder.createPickupRequest(64, false) && s.far().createPickupRequest(64, false), "two sweeps");
            final IRequest<Pickup> near = openPickup(f, f.builder);
            final IRequest<Pickup> far = openPickup(f, s.far());
            near.getRequest().setDay(f.colony.getDay());
            far.getRequest().setDay(f.colony.getDay());
            final var whyNear = why(f, near, f.builder);
            final var whyFar = why(f, far, s.far());
            Log.getLogger().info("LG_METRIC pickup_slider near={} far={}", whyNear, whyFar);
            helper.assertTrue(whyNear.buildingPriority() == 2 && whyFar.buildingPriority() == 9, "the slider is the term: " + whyNear.buildingPriority() + "/" + whyFar.buildingPriority());
            helper.assertTrue(whyFar.score() > whyNear.score(), "the far hut with the higher slider first");

            // A delivery to the builder (nobody works there: normal) against the far hut's forced pickup.
            final IToken<?> ask = f.builder.createRequest(new Stack(new ItemStack(Items.OAK_LOG, 8), 8, 8), false);
            final IRequest<?> parent = f.request(ask);
            helper.assertTrue(parent != null && parent.hasChildren(), "the ask got a delivery: " + f.describe(ask));
            final IRequest<?> delivery = f.request(parent.getChildren().iterator().next());
            helper.assertTrue(delivery.getRequest() instanceof Delivery, "fixture: a delivery");
            helper.assertTrue(!s.far().createPickupRequest(64, true), "forced into the open pickup");
            final var whyDelivery = RequestScoring.explain(f.colony, delivery, f.warehouse.getID(), f.builder.getID(), true);
            final var whyForced = why(f, far, s.far());
            helper.assertTrue(whyForced.importance() == RequestImportance.URGENT && whyDelivery.importance() == RequestImportance.NORMAL, "urgent against normal: " + whyForced + " / " + whyDelivery);
            helper.assertTrue(whyForced.score() > whyDelivery.score(), "the forced pickup first");
            helper.succeed();
        });
    }

    /**
     * L1: a full hut's sweep is urgent, and the queue says why ("hut full, pickup queued").
     */
    static void hutFullPickup(final GameTestHelper helper)
    {
        scene(helper, "lg-hutfull", false, s -> {
            final RsFixture f = s.f();
            helper.assertTrue(s.far().createPickupRequest(64, false), "a sweep");
            final IRequest<Pickup> pickup = openPickup(f, s.far());
            helper.assertTrue(RequestImportance.of(f.manager(), pickup) == RequestImportance.BACKGROUND, "not full yet: background");
            helper.assertTrue(RequestWaitTracker.queuedReason(f.colony, pickup, WaitReason.COURIER_BUSY) == WaitReason.COURIER_BUSY, "not full: the usual reason");
            final var handler = s.far().getItemHandlerCap();
            for (int slot = 0; slot < handler.getSlots(); slot++)
            {
                handler.insertItem(slot, new ItemStack(Items.COBBLESTONE, 64), false);
            }
            helper.assertTrue(InventoryUtils.isBuildingFull(s.far()), "fixture: the hut is full");
            helper.assertTrue(RequestImportance.of(f.manager(), pickup) == RequestImportance.URGENT, "full: urgent");
            helper.assertTrue(RequestWaitTracker.queuedReason(f.colony, pickup, WaitReason.COURIER_BUSY) == WaitReason.HUT_FULL_PICKUP_QUEUED, "full: named");
            helper.assertTrue(WaitReason.fromOrdinal(WaitReason.HUT_FULL_PICKUP_QUEUED.ordinal()) == WaitReason.HUT_FULL_PICKUP_QUEUED
                                && WaitReason.HUT_FULL_PICKUP_QUEUED.ordinal() == WaitReason.values().length - 1, "appended last (the wire carries the ordinal)");
            helper.succeed();
        });
    }

    private static Object decisionTarget(final EntityAIWorkDeliveryman ai)
    {
        try
        {
            final var field = EntityAIWorkDeliveryman.class.getDeclaredField("decisionTarget");
            field.setAccessible(true);
            return field.get(ai);
        }
        catch (final ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * L4: a courier that waits for work takes a new task within a few ticks, not at the end of its 5 s decision delay.
     */
    static void courierWakes(final GameTestHelper helper)
    {
        scene(helper, "lg-wake", true, s -> helper.runAfterDelay(60, () -> {
            final RsFixture f = s.f();
            helper.assertTrue(s.job().getTaskQueue().isEmpty(), "fixture: nothing to do");
            final EntityAIWorkDeliveryman ai = (EntityAIWorkDeliveryman) s.job().getWorkerAI();
            helper.assertTrue(ai != null, "the courier has its AI");
            // As if it had just decided: its next look is a whole decision delay away.
            ((com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.ITickingTransition<?>) decisionTarget(ai)).setTicksToUpdate(100);
            final long start = f.level.getGameTime();
            f.builder.createRequest(new Stack(new ItemStack(Items.OAK_LOG, 8), 8, 8), false);
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                final long ticks = f.level.getGameTime() - start;
                if (!s.job().getTaskQueue().isEmpty())
                {
                    Log.getLogger().info("LG_METRIC courier_wake ticks={}", ticks);
                    helper.assertTrue(ticks <= 40, "the courier took the new task after " + ticks + " ticks (the decision delay is 100)");
                    helper.succeed();
                    return;
                }
                if (ticks > 200)
                {
                    helper.fail("the courier never took the task; state " + ai.getState());
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        }));
    }

    /**
     * L4 and L6: a courier at level 1 takes 4 stacks from a hut in one step (it used to take one slot every 5 ticks, and stop at 2 stacks).
     */
    static void bulkPickup(final GameTestHelper helper)
    {
        scene(helper, "lg-bulk", true, s -> {
            final RsFixture f = s.f();
            final var handler = s.far().getItemHandlerCap();
            for (int slot = 0; slot < 12; slot++)
            {
                handler.insertItem(slot, new ItemStack(Items.COBBLESTONE, 64), false);
            }
            final int carry = JobDeliveryman.carryStacks(s.courierHut().getBuildingLevel(), s.courierHut().getMaxBuildingLevel());
            final int expected = Math.min(carry, EntityAIWorkDeliveryman.PICKUP_SLOTS_PER_STEP);
            s.courier().getEntity().ifPresent(e -> e.setPos(s.far().getID().getX() + 1.5D, s.far().getID().getY(), s.far().getID().getZ() + 1.5D));
            helper.assertTrue(s.far().createPickupRequest(64, true), "the pickup was requested");
            // B1: a new forced pickup is due today (it used to be booked for a later day by the slider formula, and scored below every due task).
            final IRequest<Pickup> forced = openPickup(f, s.far());
            helper.assertTrue(RequestImportance.isForcedPickup(forced.getRequest()) && forced.getRequest().getDay() <= f.colony.getDay(),
              "forced and due today: priority " + forced.getRequest().getPriority() + ", day " + forced.getRequest().getDay() + ", today " + f.colony.getDay());
            final long start = f.level.getGameTime();
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                final long ticks = f.level.getGameTime() - start;
                final int stacks = InventoryUtils.getAmountOfStacksInItemHandler(s.courier().getInventory());
                if (stacks > 0)
                {
                    Log.getLogger().info("LG_METRIC bulk_pickup first_stacks={} carry={} level={} ticks={}", stacks, carry, s.courierHut().getBuildingLevel(), ticks);
                    helper.assertTrue(stacks >= Math.min(4, expected), "one step took " + stacks + " stacks, expected " + expected + " (carry " + carry + ")");
                    helper.succeed();
                    return;
                }
                if (ticks > 1200)
                {
                    helper.fail("the courier took nothing; queue " + s.job().getTaskQueue().size() + ", state " + (s.job().getWorkerAI() == null ? "-" : s.job().getWorkerAI().getState()));
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    /**
     * L11: a courier that leaves for a break puts its untouched task back at the head of the queue, with its wait and its promises, and another
     * courier takes it.
     */
    static void requeueOnBreak(final GameTestHelper helper)
    {
        scene(helper, "lg-requeue", false, s -> {
            final RsFixture f = s.f();
            final ICitizenData other = f.spawn(new BlockPos(36, 1, 8), true);
            helper.assertTrue(f.warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(other), "second courier assigned to the warehouse");
            final IBuilding hutB = f.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 20), "craftsmanship/storage/deliveryman1.blueprint");
            helper.assertTrue(hutB.getModule(BuildingModules.COURIER_WORK).assignCitizen(other), "second courier assigned to its hut");
            final IToken<?> ask = f.builder.createRequest(new Stack(new ItemStack(Items.OAK_LOG, 8), 8, 8), false);
            final IToken<?> delivery = f.request(ask).getChildren().iterator().next();
            final long now = f.level.getGameTime();
            final BuildingPriorities priorities = f.standard().getBuildingPriorities();
            priorities.waited(delivery, now - 400);
            helper.assertTrue(f.ledger().reservedStock(f.warehouse.getID(), stack -> stack.is(Items.OAK_LOG)) == 8, "fixture: the delivery holds 8 logs");

            final IRequest<?> taken = s.job().getCurrentTask();
            helper.assertTrue(taken != null && taken.getId().equals(delivery) && !queued(f, delivery), "the first courier took the delivery");
            final int handedBack = s.job().giveBackTasks();
            helper.assertTrue(handedBack == 1 && s.job().getTaskQueue().isEmpty(), "handed back: " + handedBack);
            final List<IToken<?>> queue = f.warehouse.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE).getMutableRequestList();
            helper.assertTrue(!queue.isEmpty() && queue.get(0).equals(delivery), "back at the head of the queue: " + queue);
            helper.assertTrue(f.request(delivery).getState() == RequestState.IN_PROGRESS, "not failed: " + f.describe(delivery));
            helper.assertTrue(f.ledger().reservedStock(f.warehouse.getID(), stack -> stack.is(Items.OAK_LOG)) == 8, "the promise stays");
            helper.assertTrue(priorities.peekWaited(delivery, f.level.getGameTime()) >= 400, "the wait stays: " + priorities.peekWaited(delivery, f.level.getGameTime()));
            final IRequest<?> second = other.getJob(JobDeliveryman.class).getCurrentTask();
            helper.assertTrue(second != null && second.getId().equals(delivery), "the other courier takes it");
            helper.succeed();
        });
    }

    /**
     * L9: a free courier wakes as many waiters as there are free couriers, not all of them.
     */
    static void wakeByFreeCouriers(final GameTestHelper helper)
    {
        final RsFixture f = RsFixture.found(helper, "lg-free", 4);
        f.stock(0, new ItemStack(Items.OAK_LOG, 60));
        helper.assertTrue(JobDeliveryman.freeCouriers(f.colony) == 0, "no couriers");
        final List<IToken<?>> asks = new ArrayList<>();
        for (int i = 0; i < 3; i++)
        {
            asks.add(f.builder.createRequest(new Stack(new ItemStack(Items.OAK_LOG, 4 + i), 4 + i, 4 + i), false));
        }
        final RequestWaitTracker tracker = f.tracker();
        int noCourier = 0;
        for (final RequestWaitTracker.Waiter waiter : tracker.waiters())
        {
            final IRequest<?> request = f.request(waiter.token());
            if (request != null && request.getWaitReason() == WaitReason.NO_COURIER)
            {
                noCourier++;
            }
        }
        helper.assertTrue(noCourier >= 2, "fixture: deliveries wait for a courier: " + noCourier + " of " + tracker.waiterCount() + " " + f.describe(asks.get(0)));
        final int before = tracker.dueCount();
        final int woken = tracker.wakeByReason(Set.of(WaitReason.NO_COURIER, WaitReason.COURIER_BUSY), 1);
        helper.assertTrue(woken == 1 && tracker.dueCount() <= before + 1, "one free courier wakes one waiter: " + woken + ", due " + before + " -> " + tracker.dueCount());
        final int all = tracker.wakeByReason(Set.of(WaitReason.NO_COURIER, WaitReason.COURIER_BUSY), Integer.MAX_VALUE);
        helper.assertTrue(all >= noCourier, "without a limit every one: " + all);
        helper.succeed();
    }

    private static final int REMAINDER_NO_CRAFTER = 0;
    private static final int REMAINDER_CRAFTER     = 1;
    private static final int REMAINDER_NO_INPUTS   = 2;

    /**
     * A sawmill that makes 4 planks from a log, with a worker unless there is no crafter.
     */
    private static IBuilding plankSawmill(final RsFixture f, final boolean staffed, final boolean ai)
    {
        final IBuilding sawmill = f.place(ModBlocks.blockHutSawmill, new BlockPos(8, 1, 22), "craftsmanship/carpentry/sawmill1.blueprint");
        final ICraftingBuildingModule craft = sawmill.getModule(BuildingModules.SAWMILL_CRAFT);
        final RecipeStorage recipe = RecipeStorage.builder()
          .withRecipeId(null)
          .withInputs(List.of(new ItemStorage(new ItemStack(Items.OAK_LOG, 1))))
          .withPrimaryOutput(new ItemStack(Items.OAK_PLANKS, 4))
          .build();
        final IToken<?> recipeToken = IColonyManager.getInstance().getRecipeManager().checkOrAddRecipe(recipe);
        f.helper.assertTrue(craft.addRecipe(recipeToken), "sawmill rejected the recipe");
        if (staffed)
        {
            final ICitizenData worker = f.spawn(new BlockPos(10, 1, 28), !ai);
            f.helper.assertTrue(sawmill.getModule(BuildingModules.SAWMILL_WORK).assignCitizen(worker), "worker not assigned");
            worker.getEntity().ifPresent(e -> e.setPos(sawmill.getID().getX() + 0.5D, sawmill.getID().getY(), sawmill.getID().getZ() + 0.5D));
        }
        return sawmill;
    }

    /**
     * Round 13 (L7, c2): the warehouse's open remainder for the item, across its resolvers.
     */
    private static IToken<?> openRemainder(final RsFixture f, final com.minecolonies.api.colony.requestsystem.requestable.IDeliverable wanted)
    {
        for (final var resolver : ((AbstractBuilding) f.warehouse).getResolvers())
        {
            if (resolver instanceof final AbstractWarehouseRequestResolver warehouse)
            {
                final IToken<?> token = warehouse.openRemainder(f.manager(), wanted);
                if (token != null)
                {
                    return token;
                }
            }
        }
        return null;
    }

    private static boolean hasDeliveryChild(final RsFixture f, final IRequest<?> request)
    {
        for (final IToken<?> child : request.getChildren())
        {
            final IRequest<?> c = f.request(child);
            if (c != null && c.getRequest() instanceof Delivery)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * L7 (c2 decision): two planks in stock and a request for 64 (minimum 1), ledger on. The two are always handed out at once (never held). With a
     * staffed sawmill that can make planks (logs in store) the other 62 go to it as a separate request; without a crafter, or when the crafter has
     * no logs and nobody makes them, nothing else happens.
     */
    static void remainderToCrafters(final GameTestHelper helper, final int mode)
    {
        com.minecolonies.core.colony.requestsystem.RsFlags.overrideReservations(true);
        final RsFixture f = RsFixture.found(helper, "lg-remainder-" + mode, 4);
        plankSawmill(f, mode != REMAINDER_NO_CRAFTER, false);
        f.stock(0, new ItemStack(Items.OAK_PLANKS, 2));
        if (mode != REMAINDER_NO_INPUTS)
        {
            f.stock(1, new ItemStack(Items.OAK_LOG, 32));
        }
        final long scans = com.minecolonies.core.colony.requestsystem.wait.WaitDiagnosis.recipeScans;
        final Stack planks = new Stack(new ItemStack(Items.OAK_PLANKS, 64), 64, 1);
        for (int i = 0; i < 5; i++)
        {
            com.minecolonies.core.colony.requestsystem.wait.WaitDiagnosis.recipeSupport((com.minecolonies.core.colony.Colony) f.colony, planks);
        }
        helper.assertTrue(com.minecolonies.core.colony.requestsystem.wait.WaitDiagnosis.recipeScans - scans <= 1,
          "recipe support scans the buildings once per item per tick: " + (com.minecolonies.core.colony.requestsystem.wait.WaitDiagnosis.recipeScans - scans));
        final long sent = AbstractWarehouseRequestResolver.remaindersSent;
        final IToken<?> ask = f.builder.createRequest(planks, false);
        final IRequest<?> request = f.request(ask);
        helper.assertTrue(request != null, "the request exists");
        final boolean delivery = hasDeliveryChild(f, request);
        final IToken<?> remainder = openRemainder(f, planks);
        final int held = f.ledger().reservedStock(f.warehouse.getID(), stack -> stack.is(Items.OAK_PLANKS));
        Log.getLogger().info("LG_METRIC remainder mode={} state={} delivery={} remainder={} held={}", mode, request.getState(), delivery,
          remainder == null ? "none" : f.describe(remainder), held);
        helper.assertTrue(delivery, "the two planks are handed out at once: " + f.describe(ask));
        helper.assertTrue(held == 2, "only the delivery holds the two: " + held);
        if (mode == REMAINDER_CRAFTER)
        {
            helper.assertTrue(remainder != null && AbstractWarehouseRequestResolver.remaindersSent == sent + 1, "the remainder goes to the sawmill");
            final IRequest<?> r = f.request(remainder);
            helper.assertTrue(r != null && !r.hasParent() && r.getRequest() instanceof com.minecolonies.api.colony.requestsystem.requestable.IDeliverable d && d.getCount() == 62,
              "a separate request for 62: " + f.describe(remainder));
            helper.assertTrue(f.manager().getResolverForRequest(remainder) instanceof com.minecolonies.core.colony.requestsystem.resolvers.core.AbstractCraftingRequestResolver,
              "a crafter took it: " + f.describe(remainder));
            // A second ask while the remainder is open sends no second remainder.
            f.stock(2, new ItemStack(Items.OAK_PLANKS, 2));
            final IToken<?> again = f.builder.createRequest(new Stack(new ItemStack(Items.OAK_PLANKS, 64), 64, 1), false);
            helper.assertTrue(hasDeliveryChild(f, f.request(again)) && AbstractWarehouseRequestResolver.remaindersSent == sent + 1, "one open remainder per item");
        }
        else
        {
            helper.assertTrue(remainder == null && AbstractWarehouseRequestResolver.remaindersSent == sent, "no remainder: " + (remainder == null ? "none" : f.describe(remainder)));
        }
        helper.succeed();
    }

    /**
     * L7 (c2), integration: ledger on, a builder hut asks for 16 planks (minimum 1), the warehouse has 2 planks and logs, a sawmill and a courier
     * work with their own AI. The builder gets the 2 at once, the sawmill crafts the remainder into the warehouse, and the builder's next ask is
     * served from that stock.
     */
    static void remainderBuilderSawmill(final GameTestHelper helper)
    {
        com.minecolonies.core.colony.requestsystem.RsFlags.overrideReservations(true);
        final RsFixture f = RsFixture.found(helper, "lg-remainder-chain", 4);
        final IBuilding courierHut = f.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");
        helper.runAfterDelay(200, () -> {
            final IBuilding sawmill = plankSawmill(f, true, true);
            final ICitizenData courier = f.spawn(new BlockPos(34, 1, 8), false);
            helper.assertTrue(courierHut.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier), "courier not assigned to the courier hut");
            helper.assertTrue(f.warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "courier not assigned to the warehouse");
            courier.getEntity().ifPresent(e -> e.setPos(courierHut.getID().getX() + 0.5D, courierHut.getID().getY(), courierHut.getID().getZ() + 0.5D));
            f.stock(0, new ItemStack(Items.OAK_PLANKS, 2));
            f.stock(1, new ItemStack(Items.OAK_LOG, 16));
            final Stack planks = new Stack(new ItemStack(Items.OAK_PLANKS, 16), 16, 1);
            final IToken<?> ask = f.builder.createRequest(planks, false);
            helper.assertTrue(hasDeliveryChild(f, f.request(ask)), "the 2 planks go out at once: " + f.describe(ask));
            final IToken<?> remainder = openRemainder(f, planks);
            helper.assertTrue(remainder != null, "the 14 go to the sawmill");
            final long start = f.level.getGameTime();
            final boolean[] second = {false};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                f.level.getServer().clockManager().setTotalTicks(f.level.registryAccess().getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD), 6000L);
                final long elapsed = f.level.getGameTime() - start;
                final int inHut = InventoryUtils.getItemCountInItemHandler(f.builder.getItemHandlerCap(), stack -> stack.is(Items.OAK_PLANKS));
                final int inStore = f.physical(stack -> stack.is(Items.OAK_PLANKS));
                final boolean crafted = openRemainder(f, planks) == null && inStore >= 14;
                if (!second[0] && inHut >= 2 && crafted)
                {
                    Log.getLogger().info("LG_METRIC remainder_chain builder={} store={} ticks={}", inHut, inStore, elapsed);
                    second[0] = true;
                    // The builder used the two (else its own hut would serve the next ask and the request would be gone at once).
                    final var hut = f.builder.getItemHandlerCap();
                    for (int slot = 0; slot < hut.getSlots(); slot++)
                    {
                        if (hut.getStackInSlot(slot).is(Items.OAK_PLANKS))
                        {
                            hut.extractItem(slot, Integer.MAX_VALUE, false);
                        }
                    }
                    final IToken<?> next = f.builder.createRequest(new Stack(new ItemStack(Items.OAK_PLANKS, 14), 14, 1), false);
                    helper.assertTrue(f.request(next) != null && hasDeliveryChild(f, f.request(next)), "the next ask is served from the crafted stock: " + f.describe(next));
                    helper.succeed();
                    return;
                }
                if (elapsed > 24_000)
                {
                    helper.fail("remainder chain stuck after " + elapsed + " ticks: builder " + inHut + ", warehouse planks " + inStore + ", logs " + f.physical(s -> s.is(Items.OAK_LOG))
                                  + ", remainder " + (openRemainder(f, planks) == null ? "closed" : f.describe(openRemainder(f, planks))) + ", sawmill planks "
                                  + InventoryUtils.getItemCountInItemHandler(sawmill.getItemHandlerCap(), stack -> stack.is(Items.OAK_PLANKS)));
                    return;
                }
                helper.runAfterDelay(20, () -> poll[0].run());
            };
            helper.runAfterDelay(20, () -> poll[0].run());
        });
    }

    /**
     * c2: a hut whose racks are not loaded is not full, so its pickup is not urgent and the queue does not call it "hut full"; an unloaded hut
     * reads as unloaded.
     */
    static void unloadedHutNotFull(final GameTestHelper helper)
    {
        scene(helper, "lg-unloaded", false, s -> {
            final RsFixture f = s.f();
            helper.assertTrue(s.far().createPickupRequest(64, false), "a sweep");
            final IRequest<Pickup> pickup = openPickup(f, s.far());
            final List<BlockPos> containers = new ArrayList<>(s.far().getContainers());
            for (final BlockPos pos : containers)
            {
                s.far().removeContainerPosition(pos);
            }
            // A rack position in a chunk nobody loads.
            final BlockPos away = s.far().getPosition().offset(300_000, 0, 300_000);
            s.far().addContainerPosition(away);
            try
            {
                helper.assertTrue(!com.minecolonies.api.util.WorldUtil.isBlockLoaded(f.level, away), "fixture: the rack is not loaded");
                helper.assertTrue(!InventoryUtils.isBuildingFull(s.far()), "no loaded rack: not full");
                helper.assertTrue(RequestImportance.of(f.manager(), pickup) != RequestImportance.URGENT, "not urgent: " + RequestImportance.of(f.manager(), pickup));
                helper.assertTrue(RequestWaitTracker.queuedReason(f.colony, pickup, WaitReason.COURIER_BUSY) == WaitReason.COURIER_BUSY, "not called hut full");
                helper.assertTrue(RequestWaitTracker.pickupReason(false, true, WaitReason.COURIER_BUSY) == WaitReason.UNLOADED, "unloaded is checked first");
                helper.assertTrue(RequestWaitTracker.pickupReason(true, true, WaitReason.COURIER_BUSY) == WaitReason.HUT_FULL_PICKUP_QUEUED, "loaded and full");
                helper.assertTrue(RequestWaitTracker.pickupReason(true, false, WaitReason.COURIER_BUSY) == WaitReason.COURIER_BUSY, "loaded, room left");
            }
            finally
            {
                s.far().removeContainerPosition(away);
                for (final BlockPos pos : containers)
                {
                    s.far().addContainerPosition(pos);
                }
            }
            helper.succeed();
        });
    }

    /**
     * L2 (c2): a batch is limited by the stacks it carries, summed over its tasks, not by its task count.
     */
    static void batchByStacks(final GameTestHelper helper)
    {
        helper.assertTrue(JobDeliveryman.stacksOf(new ItemStack(Items.DIRT, 1)) == 1 && JobDeliveryman.stacksOf(new ItemStack(Items.DIRT, 64)) == 1
                            && JobDeliveryman.stacksOf(new ItemStack(Items.DIRT, 65)) == 2 && JobDeliveryman.stacksOf(new ItemStack(Items.DIRT, 256)) == 4, "stacks by count");
        helper.assertTrue(JobDeliveryman.stacksOf(new ItemStack(Items.DIAMOND_SWORD, 3)) == 3, "unstackable items take a stack each");
        final int carry = JobDeliveryman.carryStacks(1, 5);
        // Level 1: one four-stack refill fills the bag; a second does not fit, while four one-stack deliveries do.
        helper.assertTrue(JobDeliveryman.fitsCarry(0, 4, carry) && !JobDeliveryman.fitsCarry(4, 4, carry), "a second 4-stack refill does not fit at level 1");
        int carried = 0;
        int tasks = 0;
        while (JobDeliveryman.fitsCarry(carried, 1, carry))
        {
            carried++;
            tasks++;
        }
        helper.assertTrue(tasks == 4, "four one-stack tasks fit: " + tasks);
        helper.assertTrue(JobDeliveryman.fitsCarry(100, 100, JobDeliveryman.carryStacks(5, 5)), "the top level has no limit");
        helper.succeed();
    }

    /**
     * L5: a minimum stock of 5 stacks with nothing in store asks for 4 stacks (what a courier carries) with a minimum of a quarter stack.
     */
    static void minimumStockRefill(final GameTestHelper helper)
    {
        final RsFixture f = RsFixture.found(helper, "lg-minstock", 4);
        final MinimumStockModule stock = f.warehouse.getModule(BuildingModules.MIN_STOCK);
        stock.addMinimumStock(new ItemStack(Items.DIRT), 5);
        stock.onColonyTick(f.colony);
        final List<IToken<?>> minimum = new ArrayList<>(f.warehouse.getOpenRequestsByRequestableType().getOrDefault(TypeToken.of(MinimumStack.class), List.of()));
        helper.assertTrue(minimum.size() == 1, "one refill: " + minimum.size());
        final IRequest<?> request = f.request(minimum.get(0));
        final MinimumStack asked = (MinimumStack) request.getRequest();
        Log.getLogger().info("LG_METRIC min_stock_refill count={} min={}", asked.getCount(), asked.getMinimumCount());
        helper.assertTrue(asked.getCount() == 256 && asked.getMinimumCount() == 16, "4 stacks, minimum 16: " + asked.getCount() + "/" + asked.getMinimumCount());
        stock.removeMinimumStock(new ItemStack(Items.DIRT));
        helper.succeed();
    }
}
