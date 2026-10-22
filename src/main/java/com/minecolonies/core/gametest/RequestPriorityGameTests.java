package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.MinimumStack;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.requestsystem.RsFlags;
import com.minecolonies.core.colony.requestsystem.management.manager.StandardRequestManager;
import com.minecolonies.core.colony.requestsystem.priority.BuildingPriorities;
import com.minecolonies.core.colony.requestsystem.priority.RequestImportance;
import com.minecolonies.core.colony.requestsystem.priority.RequestScoring;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Consumer;

/**
 * RS3: the courier serves by importance, then building priority (alarm above all), then wait; distance only breaks ties
 * (glob {@code minecolonies:rs_*}).
 * <p>
 * Layout: warehouse at (16,1,2), the builder hut "near" at (8,1,2), a lumberjack hut "far" at (24,1,22), the courier hut at
 * (32,1,2). "near" asks first, so the old score (queue order plus distance) takes it first.
 */
public final class RequestPriorityGameTests
{
    private RequestPriorityGameTests()
    {
    }

    /**
     * What every test needs.
     */
    private record Scene(RsFixture f, IBuilding near, IBuilding far, JobDeliveryman job, ICitizenData courier)
    {
    }

    private static void flags(final boolean priorityClasses)
    {
        RsFlags.overrideReservations(true);
        RsFlags.overrideSmartRetry(true);
        RsFlags.overridePriorityClasses(priorityClasses);
    }

    private static void finish(final GameTestHelper helper)
    {
        RsFlags.overridePriorityClasses(null);
        helper.succeed();
    }

    private static void scene(final GameTestHelper helper, final String name, final boolean priorityClasses, final Consumer<Scene> body)
    {
        flags(priorityClasses);
        final RsFixture f = RsFixture.found(helper, name, 4);
        final IBuilding deliveryman = f.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");
        final IBuilding far = f.place(ModBlocks.blockHutLumberjack, new BlockPos(24, 1, 22), "fundamentals/lumberjack1.blueprint");
        f.stock(0, new ItemStack(Items.OAK_LOG, 60));
        helper.runAfterDelay(200, () -> {
            final ICitizenData courier = f.spawn(new BlockPos(34, 1, 8), true);
            helper.assertTrue(deliveryman.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier), "courier not assigned to the courier hut");
            helper.assertTrue(f.warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "courier not assigned to the warehouse");
            body.accept(new Scene(f, f.builder, far, courier.getJob(JobDeliveryman.class), courier));
        });
    }

    private static IToken<?> ask(final IBuilding requester, final int logs)
    {
        return requester.createRequest(new Stack(new ItemStack(Items.OAK_LOG, logs), logs, logs), false);
    }

    private static IToken<?> askMinimumStock(final IBuilding requester, final int logs)
    {
        final MinimumStack stack = new MinimumStack(new ItemStack(Items.OAK_LOG), false, true, ItemStackUtils.EMPTY, logs, 1);
        stack.setCanBeResolvedByBuilding(false);
        return requester.createRequest(stack, true);
    }

    /**
     * Puts a worker in the building, so that what it asks for blocks somebody.
     */
    private static void employ(final RsFixture f, final IBuilding building)
    {
        final ICitizenData worker = f.spawn(new BlockPos(30, 1, 28), true);
        f.helper.assertTrue(building.getFirstModuleOccurance(WorkerBuildingModule.class).assignCitizen(worker), "worker not assigned to " + building.getBuildingType());
    }

    private static IRequest<?> deliveryOf(final RsFixture f, final IToken<?> parent)
    {
        final IRequest<?> request = f.request(parent);
        f.helper.assertTrue(request != null && request.hasChildren(), "the request got a delivery: " + f.describe(parent));
        return f.request(request.getChildren().iterator().next());
    }

    private static BlockPos target(final IRequest<?> delivery)
    {
        return ((Delivery) delivery.getRequest()).getTarget().getInDimensionLocation();
    }

    private static String where(final Scene s, final BlockPos pos)
    {
        return pos.equals(s.near().getID()) ? "near" : pos.equals(s.far().getID()) ? "far" : String.valueOf(pos);
    }

    /**
     * Both ask for logs, the nearer one first. The courier takes what the test says it should.
     */
    private static void twoRequesters(final GameTestHelper helper, final String name, final boolean flag, final Consumer<Scene> setup, final String expectFirst)
    {
        scene(helper, name, flag, s -> {
            setup.accept(s);
            final IToken<?> nearAsk = ask(s.near(), 8);
            final IToken<?> farAsk = ask(s.far(), 8);
            final IRequest<?> nearDelivery = deliveryOf(s.f(), nearAsk);
            final IRequest<?> farDelivery = deliveryOf(s.f(), farAsk);
            helper.assertTrue(target(nearDelivery).equals(s.near().getID()) && target(farDelivery).equals(s.far().getID()), "fixture: the deliveries go to their buildings");

            final IRequest<?> first = s.job().getCurrentTask();
            helper.assertTrue(first != null, "the courier took a task");
            final String got = where(s, target(first));
            Log.getLogger().info("RS_METRIC priority_pick test={} flag={} first={}", name, flag, got);
            helper.assertTrue(got.equals(expectFirst), "first served: " + got + ", expected " + expectFirst);
            finish(helper);
        });
    }

    // ------------------------------------------------------------------ order

    /**
     * Flag on: the faraway building with priority 9 is served before the nearby one with 5.
     */
    public static void farNineBeforeNearFive(final GameTestHelper helper)
    {
        twoRequesters(helper, "rs-prio-nine", true, s -> s.f().standard().getBuildingPriorities().setPriority(s.far().getID(), 9), "far");
    }

    /**
     * Flag on, no priorities set: ties on every class, the older request goes first, then the nearer. Round 13 (L3): both wait less than one
     * 45 s bucket, so they tie on the wait too and the courier's walk decides; the nearer one is also the older one here.
     */
    public static void equalClassesOldestFirst(final GameTestHelper helper)
    {
        twoRequesters(helper, "rs-prio-equal", true, s -> {
        }, "near");
    }

    /**
     * Flag on: an alarm beats priority 9 on the other building.
     */
    public static void alarmBeatsNine(final GameTestHelper helper)
    {
        twoRequesters(helper, "rs-prio-alarm", true, s -> {
            final BuildingPriorities p = s.f().standard().getBuildingPriorities();
            p.setPriority(s.near().getID(), 9);
            p.setAlarm(s.far().getID(), true, s.f().level.getGameTime());
        }, "far");
    }

    /**
     * Flag on: an alarm that ran out no longer counts.
     */
    public static void expiredAlarmIsIgnored(final GameTestHelper helper)
    {
        twoRequesters(helper, "rs-prio-alarm-expired", true, s -> {
            final BuildingPriorities p = s.f().standard().getBuildingPriorities();
            p.setPriority(s.near().getID(), 6);
            p.setAlarm(s.far().getID(), true, s.f().level.getGameTime() - 500, 100);
        }, "near");
    }

    /**
     * Flag on: an urgent request (a worker waits) goes before a background top-up, whatever the priorities and distances.
     */
    public static void urgentBeforeBackground(final GameTestHelper helper)
    {
        scene(helper, "rs-prio-importance", true, s -> {
            employ(s.f(), s.far());
            s.f().standard().getBuildingPriorities().setPriority(s.near().getID(), 9);
            // The nearer building, with the higher priority, asks for a top-up first; the faraway worker asks second.
            final IToken<?> topUp = askMinimumStock(s.near(), 8);
            final IToken<?> need = ask(s.far(), 8);
            final IRequest<?> topUpDelivery = deliveryOf(s.f(), topUp);
            final IRequest<?> needDelivery = deliveryOf(s.f(), need);

            helper.assertTrue(RequestImportance.of(s.f().manager(), topUpDelivery) == RequestImportance.BACKGROUND, "the top-up is background: " + RequestImportance.of(s.f().manager(), topUpDelivery));
            helper.assertTrue(RequestImportance.of(s.f().manager(), needDelivery) == RequestImportance.URGENT, "a worker waits: " + RequestImportance.of(s.f().manager(), needDelivery));

            final var why = RequestScoring.explain(s.f().colony, needDelivery, s.f().warehouse.getID(), s.far().getID(), true);
            helper.assertTrue(why.importance() == RequestImportance.URGENT && why.buildingPriority() == 5 && !why.alarm(), "explained: " + why);

            final IRequest<?> first = s.job().getCurrentTask();
            helper.assertTrue(first != null && target(first).equals(s.far().getID()), "the worker is served first, not " + (first == null ? "nothing" : where(s, target(first))));
            finish(helper);
        });
    }

    /**
     * The derivation: a request of a building nobody works in is normal, one of a building with a worker is urgent.
     */
    public static void importanceDerivation(final GameTestHelper helper)
    {
        scene(helper, "rs-prio-derivation", true, s -> {
            employ(s.f(), s.far());
            final IRequest<?> idle = deliveryOf(s.f(), ask(s.near(), 4));
            final IRequest<?> busy = deliveryOf(s.f(), ask(s.far(), 4));
            helper.assertTrue(RequestImportance.of(s.f().manager(), idle) == RequestImportance.NORMAL, "no worker in the builder hut: " + RequestImportance.of(s.f().manager(), idle));
            helper.assertTrue(RequestImportance.of(s.f().manager(), busy) == RequestImportance.URGENT, "a worker in the lumberjack hut: " + RequestImportance.of(s.f().manager(), busy));
            helper.assertTrue(RequestImportance.of(s.f().manager(), s.f().request(busy.getParent())) == RequestImportance.URGENT, "the parent request too");
            finish(helper);
        });
    }

    /**
     * Flag off: the old score, the nearer building first, whatever the priority.
     */
    public static void flagOffKeepsNearerFirst(final GameTestHelper helper)
    {
        twoRequesters(helper, "rs-prio-off", false, s -> s.f().standard().getBuildingPriorities().setPriority(s.far().getID(), 9), "near");
    }

    /**
     * Flag off: importance does not matter either.
     */
    public static void flagOffIgnoresImportance(final GameTestHelper helper)
    {
        scene(helper, "rs-prio-off-importance", false, s -> {
            employ(s.f(), s.far());
            askMinimumStock(s.near(), 8);
            ask(s.far(), 8);
            final IRequest<?> first = s.job().getCurrentTask();
            helper.assertTrue(first != null && target(first).equals(s.near().getID()), "old behaviour: the nearer top-up first, not " + (first == null ? "nothing" : where(s, target(first))));
            finish(helper);
        });
    }

    // ------------------------------------------------------------------ persistence

    /**
     * Priorities and the alarm survive a save and load of the colony.
     */
    public static void saveLoadKeepsPriorities(final GameTestHelper helper)
    {
        flags(true);
        final RsFixture f = RsFixture.found(helper, "rs-prio-saveload", 2);
        final long now = f.level.getGameTime();
        final BuildingPriorities p = f.standard().getBuildingPriorities();
        p.setPriority(f.builder.getID(), 8);
        p.setAlarm(f.warehouse.getID(), true, now);
        p.setPriority(f.warehouse.getID(), 2);

        final var saved = ((Colony) f.colony).write(new net.minecraft.nbt.CompoundTag(), f.level.registryAccess());
        final Colony restored = Colony.loadColony(saved, f.level, f.level.registryAccess());
        helper.assertTrue(restored != null, "colony could not be loaded");
        final BuildingPriorities after = ((StandardRequestManager) restored.getRequestManager()).getBuildingPriorities();
        helper.assertTrue(after.priority(f.builder.getID()) == 8, "builder priority kept: " + after.snapshot());
        helper.assertTrue(after.priority(f.warehouse.getID()) == 2, "warehouse priority kept");
        helper.assertTrue(after.isAlarm(f.warehouse.getID(), now + 100) && after.alarmUntil(f.warehouse.getID()) == now + BuildingPriorities.ALARM_TICKS, "alarm kept: " + after.snapshot());
        helper.assertTrue(after.effective(f.warehouse.getID(), now + BuildingPriorities.ALARM_TICKS) == 2, "the alarm ends on time");
        RsFlags.overridePriorityClasses(null);
        helper.succeed();
    }

    /**
     * A colony saved before RS3 (no tag) loads with the defaults.
     */
    public static void oldSaveLoadsWithDefaults(final GameTestHelper helper)
    {
        flags(true);
        final RsFixture f = RsFixture.found(helper, "rs-prio-oldsave", 2);
        helper.assertTrue(f.standard().getBuildingPriorities().isEmpty(), "nothing set: nothing written");
        final var saved = ((Colony) f.colony).write(new net.minecraft.nbt.CompoundTag(), f.level.registryAccess());
        final Colony restored = Colony.loadColony(saved, f.level, f.level.registryAccess());
        helper.assertTrue(restored != null, "colony could not be loaded");
        final BuildingPriorities after = ((StandardRequestManager) restored.getRequestManager()).getBuildingPriorities();
        helper.assertTrue(after.isEmpty() && after.priority(f.builder.getID()) == 5, "defaults");
        RsFlags.overridePriorityClasses(null);
        helper.succeed();
    }
}
