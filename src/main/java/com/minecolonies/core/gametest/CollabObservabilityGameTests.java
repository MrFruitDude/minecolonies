package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.workorders.collab.ICollabBuilderView;
import com.minecolonies.api.colony.workorders.collab.ICollabView;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.core.colony.workorders.WorkOrderDecoration;
import com.minecolonies.core.colony.workorders.collab.BuilderCollab;
import com.minecolonies.core.colony.workorders.collab.CollabControls;
import com.minecolonies.core.colony.workorders.collab.CollabViews;
import com.minecolonies.core.entity.ai.workers.util.BuildingProgressStage;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;

import static com.ldtteam.structurize.placement.AbstractBlueprintIterator.NULL_POS;

/**
 * Observability and controls of the builder collaboration (the read view and the per-order limit / pin controls Colony Command's UI
 * uses). Data-only builders, as in the scheduler tests of {@link BuilderCollabGameTests}.
 */
public final class CollabObservabilityGameTests
{
    private CollabObservabilityGameTests()
    {
    }

    private static void reset()
    {
        BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
    }

    private static BuilderWorkOrderGameTests.Fx fx(final GameTestHelper helper, final String name, final int stayTicks)
    {
        BuilderCollab.overrideForTests(true, 3, 40, 10, stayTicks, 8, 2400);
        return BuilderWorkOrderGameTests.fixture(helper, name, true);
    }

    private static BuildingBuilder extraBuilder(final BuilderWorkOrderGameTests.Fx f, final int x)
    {
        final BuildingBuilder hut = (BuildingBuilder) MinecoloniesGameTests.placeProductionBuilding(f.helper(), f.colony(), ModBlocks.blockHutBuilder,
          new BlockPos(x, 1, 2), "fundamentals/builder1.blueprint");
        BuilderWorkOrderGameTests.hire(f.helper(), f.colony(), hut);
        return hut;
    }

    private static void started(final WorkOrderDecoration order, final int total)
    {
        order.setIteratorType("default");
        order.getCollab().setProgress(NULL_POS, BuildingProgressStage.BUILD_SOLID);
        order.getCollab().setStageTotal(BuildingProgressStage.BUILD_SOLID, total);
    }

    private static BuildingBuilder leadOf(final BuilderWorkOrderGameTests.Fx f, final WorkOrderDecoration order)
    {
        return f.a().hasWorkOrder() && f.a().getWorkOrder() == order ? f.a() : f.b();
    }

    /**
     * Two builders and one order: one lead, one helper, with counts, stage, leases and the cap in the snapshot; a new order hands the
     * helper off and the snapshot follows.
     */
    public static void viewShowsLeadAssistantAndHandOff(final GameTestHelper helper)
    {
        try
        {
            final BuilderWorkOrderGameTests.Fx f = fx(helper, "C2 view", 100_000);
            final WorkOrderDecoration o1 = BuilderWorkOrderGameTests.order(f, "v1", new BlockPos(30, 1, 10), 0);
            f.tick();
            final BuildingBuilder lead = leadOf(f, o1);
            final BuildingBuilder spare = lead == f.a() ? f.b() : f.a();
            final var spareCitizen = spare == f.a() ? f.ca() : f.cb();
            final var leadCitizen = lead == f.a() ? f.ca() : f.cb();

            ICollabView v = CollabViews.of(f.colony(), o1);
            helper.assertTrue(v.orderId() == o1.getID(), "order id " + v.orderId());
            helper.assertTrue(lead.getID().equals(v.leadHut()) && v.leadCitizenId() == leadCitizen.getId(), "lead " + v.leadHut() + "/" + v.leadCitizenId());
            helper.assertTrue(v.assistants().isEmpty(), "helpers before the lead started: " + v.assistants().size());
            helper.assertTrue(v.collaborationEnabled(), "not enabled");
            helper.assertTrue(v.stage().isEmpty() && !v.stageIsShared(), "stage before start: '" + v.stage() + "'");

            started(o1, 100);
            for (int i = 0; i < 7; i++)
            {
                o1.getCollab().blockProcessed();
            }
            f.tick();
            o1.getCollab().lease(f.helper().absolutePos(new BlockPos(31, 1, 11)), spare.getID(), Long.MAX_VALUE / 2, false);
            o1.getCollab().lease(f.helper().absolutePos(new BlockPos(32, 1, 11)), spare.getID(), Long.MAX_VALUE / 2, false);
            v = CollabViews.of(f.colony(), o1);
            helper.assertTrue(v.assistants().size() == 1, "helpers " + v.assistants().size());
            final ICollabView.IAssistantView a = v.assistants().get(0);
            helper.assertTrue(a.hut().equals(spare.getID()) && a.citizenId() == spareCitizen.getId(), "helper " + a.hut() + "/" + a.citizenId());
            helper.assertTrue(a.leasedBlocks() == 2 && v.leasedBlocks() == 2, "leases " + a.leasedBlocks() + "/" + v.leasedBlocks());
            helper.assertTrue(!a.pinned(), "pinned by itself");
            final long now = f.level().getGameTime();
            helper.assertTrue(a.stayLeftTicks(now) > 90_000 && a.stayLeftTicks(now + 200_000) == 0, "stay left " + a.stayLeftTicks(now));
            helper.assertTrue("BUILD_SOLID".equals(v.stage()) && v.stageIsShared(), "stage '" + v.stage() + "'");
            helper.assertTrue(v.placedBlocks() == 7 && v.totalBlocks() == 100, "blocks " + v.placedBlocks() + "/" + v.totalBlocks());
            helper.assertTrue(v.automaticMaxAssistants() == 3 && v.maxAssistants() == 3 && v.maxAssistantsSetting() == -1, "caps " + v.automaticMaxAssistants() + "/" + v.maxAssistants() + "/" + v.maxAssistantsSetting());

            var builders = CollabViews.builders(f.colony());
            helper.assertTrue(builders.size() == 2, "builders " + builders.size());
            for (final ICollabBuilderView b : builders)
            {
                if (b.hut().equals(lead.getID()))
                {
                    helper.assertTrue(b.role() == ICollabBuilderView.Role.LEAD && b.orderId() == o1.getID() && b.citizenId() == leadCitizen.getId(), "lead role " + b.role());
                }
                else
                {
                    helper.assertTrue(b.role() == ICollabBuilderView.Role.ASSIST && b.orderId() == o1.getID() && lead.getID().equals(b.leadHut()), "helper role " + b.role());
                }
            }
            helper.assertTrue(CollabViews.orders(f.colony()).size() == 1, "orders " + CollabViews.orders(f.colony()).size());

            // new order: hand-off
            final WorkOrderDecoration o2 = BuilderWorkOrderGameTests.order(f, "v2", new BlockPos(30, 1, 20), 0);
            f.tick();
            v = CollabViews.of(f.colony(), o1);
            helper.assertTrue(v.assistants().isEmpty(), "the old order still shows a helper");
            final ICollabView v2 = CollabViews.of(f.colony(), o2);
            helper.assertTrue(spare.getID().equals(v2.leadHut()) && v2.leadCitizenId() == spareCitizen.getId(), "the helper does not lead the new order in the view: " + v2.leadHut());
            builders = CollabViews.builders(f.colony());
            helper.assertTrue(builders.stream().allMatch(b -> b.role() == ICollabBuilderView.Role.LEAD), "roles after the hand-off: " + builders.stream().map(b -> b.role().name()).toList());
            helper.assertTrue(CollabViews.orders(f.colony()).size() == 2, "orders " + CollabViews.orders(f.colony()).size());
            helper.succeed();
        }
        finally
        {
            reset();
        }
    }

    /**
     * The per-order limit: lowers, raises and goes back to automatic; helpers beyond it leave once their stay time is over and do not
     * come back; the setting is saved with the order.
     */
    public static void controlsMaxAssistants(final GameTestHelper helper)
    {
        try
        {
            final BuilderWorkOrderGameTests.Fx f = fx(helper, "C2 cap", 0);
            extraBuilder(f, 24);
            extraBuilder(f, 32);
            final WorkOrderDecoration o1 = BuilderWorkOrderGameTests.order(f, "c1", new BlockPos(30, 1, 10), 0);
            f.tick();
            started(o1, 1000);
            f.tick();
            helper.assertTrue(o1.getCollab().getAssistantCount() == 3, "fixture: 3 spare builders should help, got " + o1.getCollab().getAssistantCount());

            helper.assertTrue(CollabControls.setMaxAssistants(f.colony(), o1.getID(), 1) == CollabControls.Result.OK, "set 1");
            f.tick();
            helper.assertTrue(o1.getCollab().getAssistantCount() == 1, "limit 1 but helpers: " + o1.getCollab().getAssistantCount());
            ICollabView v = CollabViews.of(f.colony(), o1);
            helper.assertTrue(v.maxAssistants() == 1 && v.maxAssistantsSetting() == 1 && v.automaticMaxAssistants() == 3, "view caps " + v.maxAssistants() + "/" + v.maxAssistantsSetting() + "/" + v.automaticMaxAssistants());
            f.tick();
            helper.assertTrue(o1.getCollab().getAssistantCount() == 1, "helpers came back above the limit");

            // saved with the order
            final CompoundTag tag = new CompoundTag();
            o1.write(tag);
            final WorkOrderDecoration copy = new WorkOrderDecoration();
            copy.setColony(f.colony());
            copy.read(tag, f.wm());
            helper.assertTrue(copy.getCollab().getMaxAssistantsSetting() == 1, "limit not saved: " + copy.getCollab().getMaxAssistantsSetting());

            helper.assertTrue(CollabControls.setMaxAssistants(f.colony(), o1.getID(), 0) == CollabControls.Result.OK, "set 0");
            f.tick();
            helper.assertTrue(o1.getCollab().getAssistantCount() == 0, "limit 0 but helpers: " + o1.getCollab().getAssistantCount());
            helper.assertTrue(CollabViews.of(f.colony(), o1).maxAssistants() == 0, "view cap with limit 0");

            helper.assertTrue(CollabControls.setMaxAssistants(f.colony(), o1.getID(), 2) == CollabControls.Result.OK, "set 2");
            f.tick();
            f.tick();
            helper.assertTrue(o1.getCollab().getAssistantCount() == 2, "limit 2 but helpers: " + o1.getCollab().getAssistantCount());

            // a limit above the configured maximum is honoured (up to the hard limit)
            BuilderCollab.overrideForTests(true, 1, 40, 10, 0, 8, 2400);
            helper.assertTrue(CollabViews.of(f.colony(), o1).automaticMaxAssistants() == 1, "automatic cap with maxHelpers 1");
            helper.assertTrue(CollabControls.setMaxAssistants(f.colony(), o1.getID(), 3) == CollabControls.Result.OK, "set 3");
            f.tick();
            f.tick();
            helper.assertTrue(o1.getCollab().getAssistantCount() == 3, "limit 3 above the configured maximum 1 but helpers: " + o1.getCollab().getAssistantCount());
            BuilderCollab.overrideForTests(true, 3, 40, 10, 0, 8, 2400);

            // back to automatic
            helper.assertTrue(CollabControls.setMaxAssistants(f.colony(), o1.getID(), -1) == CollabControls.Result.OK, "set auto");
            f.tick();
            f.tick();
            helper.assertTrue(o1.getCollab().getAssistantCount() == 3, "automatic but helpers: " + o1.getCollab().getAssistantCount());

            helper.assertTrue(CollabControls.setMaxAssistants(f.colony(), o1.getID(), -2) == CollabControls.Result.BAD_VALUE, "-2 accepted");
            helper.assertTrue(CollabControls.setMaxAssistants(f.colony(), o1.getID(), CollabControls.LIMIT + 1) == CollabControls.Result.BAD_VALUE, "above the limit accepted");
            helper.assertTrue(CollabControls.setMaxAssistants(f.colony(), 9999, 1) == CollabControls.Result.NO_SUCH_ORDER, "unknown order accepted");
            helper.succeed();
        }
        finally
        {
            reset();
        }
    }

    /**
     * A pinned helper stays when new orders arrive although that leaves an order without a lead; the other helper is taken as before; unpinned,
     * the helper is taken by the waiting order. The pin is saved with the order.
     */
    public static void controlsPinSurvivesNewOrders(final GameTestHelper helper)
    {
        try
        {
            final BuilderWorkOrderGameTests.Fx f = fx(helper, "C2 pin", 100_000);
            extraBuilder(f, 24);
            final WorkOrderDecoration o1 = BuilderWorkOrderGameTests.order(f, "p1", new BlockPos(30, 1, 10), 0);
            f.tick();
            started(o1, 1000);
            f.tick();
            helper.assertTrue(o1.getCollab().getAssistantCount() == 2, "fixture: two helpers, got " + o1.getCollab().getAssistantCount());
            final BlockPos lead = o1.getClaimedBy();
            BlockPos keep = null;
            BlockPos other = null;
            for (final BlockPos hut : o1.getCollab().getAssistantHuts())
            {
                if (keep == null)
                {
                    keep = hut;
                }
                else
                {
                    other = hut;
                }
            }
            helper.assertTrue(CollabControls.pin(f.colony(), o1.getID(), keep) == CollabControls.Result.OK, "pin");
            helper.assertTrue(CollabViews.of(f.colony(), o1).assistants().stream().filter(ICollabView.IAssistantView::pinned).map(ICollabView.IAssistantView::hut).toList().equals(java.util.List.of(keep)),
              "view does not show the pin");
            helper.assertTrue(CollabViews.builders(f.colony()).stream().filter(ICollabBuilderView::pinned).count() == 1, "builder view does not show the pin");

            final CompoundTag tag = new CompoundTag();
            o1.write(tag);
            final WorkOrderDecoration copy = new WorkOrderDecoration();
            copy.setColony(f.colony());
            copy.read(tag, f.wm());
            helper.assertTrue(copy.getCollab().getAssistant(keep) != null && copy.getCollab().getAssistant(keep).isPinned() && !copy.getCollab().getAssistant(other).isPinned(), "pin not saved");

            // one new order: the unpinned helper takes it
            final WorkOrderDecoration o2 = BuilderWorkOrderGameTests.order(f, "p2", new BlockPos(30, 1, 20), 0);
            f.tick();
            helper.assertTrue(o2.isClaimed() && o2.getClaimedBy().equals(other), "the unpinned helper did not take the new order: " + o2.getClaimedBy());
            helper.assertTrue(o1.getCollab().hasAssistant(keep), "the pinned helper was taken");

            // a third order: 3 orders for 3 builders, yet one builder is pinned and the order waits
            final WorkOrderDecoration o3 = BuilderWorkOrderGameTests.order(f, "p3", new BlockPos(30, 1, 30), 0);
            f.tick();
            f.tick();
            helper.assertTrue(!o3.isClaimed(), "the pinned helper led the third order: " + o3.getClaimedBy());
            helper.assertTrue(o1.getCollab().hasAssistant(keep) && o1.getCollab().getAssistantCount() == 1, "the pinned helper moved");
            helper.assertTrue(o1.getClaimedBy().equals(lead), "lead changed");

            // unpinned, the waiting order takes him
            helper.assertTrue(CollabControls.unpin(f.colony(), o1.getID(), keep) == CollabControls.Result.OK, "unpin");
            f.tick();
            helper.assertTrue(o3.isClaimed() && o3.getClaimedBy().equals(keep), "the waiting order did not take the unpinned helper: " + o3.getClaimedBy());
            helper.assertTrue(o1.getCollab().getAssistantCount() == 0, "helper still on the first order");
            helper.succeed();
        }
        finally
        {
            reset();
        }
    }

    /**
     * Pinning a builder who is not helping yet puts him on the order (past the limit); a pin does not survive the helper leaving.
     */
    public static void controlsPinBringsABuilder(final GameTestHelper helper)
    {
        try
        {
            final BuilderWorkOrderGameTests.Fx f = fx(helper, "C2 pin new", 100_000);
            final WorkOrderDecoration o1 = BuilderWorkOrderGameTests.order(f, "n1", new BlockPos(30, 1, 10), 0);
            f.tick();
            final BuildingBuilder lead = leadOf(f, o1);
            final BuildingBuilder spare = lead == f.a() ? f.b() : f.a();
            helper.assertTrue(CollabControls.pin(f.colony(), o1.getID(), spare.getID()) == CollabControls.Result.STAGE_NOT_SHARED, "pin before the lead started");
            started(o1, 1000);
            helper.assertTrue(CollabControls.setMaxAssistants(f.colony(), o1.getID(), 0) == CollabControls.Result.OK, "cap 0");
            f.tick();
            helper.assertTrue(o1.getCollab().getAssistantCount() == 0, "limit 0 but a helper");
            helper.assertTrue(CollabControls.pin(f.colony(), o1.getID(), spare.getID()) == CollabControls.Result.OK, "pin a free builder");
            helper.assertTrue(o1.getCollab().hasAssistant(spare.getID()) && o1.getCollab().getAssistant(spare.getID()).isPinned(), "builder not pinned to the order");
            f.tick();
            f.tick();
            helper.assertTrue(o1.getCollab().hasAssistant(spare.getID()), "the pinned builder left although the limit is 0");
            helper.assertTrue(f.wm().getAssistedOrder(spare.getID()) == o1, "the work manager does not see the pinned helper");

            // the order runs out of work: the pinned helper goes too, and the pin with him
            BuilderCollab.overrideForTests(true, 3, 40, 10, 0, 8, 2400);
            o1.getCollab().setStageTotal(BuildingProgressStage.BUILD_SOLID, 5);
            f.tick();
            helper.assertTrue(!o1.getCollab().hasAssistant(spare.getID()), "pinned helper stayed on an order with nothing left");
            helper.succeed();
        }
        finally
        {
            reset();
        }
    }

    /**
     * Every refusal has a reason.
     */
    public static void controlsRefusals(final GameTestHelper helper)
    {
        try
        {
            final BuilderWorkOrderGameTests.Fx f = fx(helper, "C2 refusals", 100_000);
            final var warehouse = MinecoloniesGameTests.placeProductionBuilding(helper, f.colony(), ModBlocks.blockHutWareHouse, new BlockPos(24, 1, 2),
              "craftsmanship/storage/warehouse1.blueprint");
            final WorkOrderDecoration o1 = BuilderWorkOrderGameTests.order(f, "r1", new BlockPos(30, 1, 10), 0);
            // not yet claimed: no lead
            helper.assertTrue(CollabControls.pin(f.colony(), o1.getID(), f.b().getID()) == CollabControls.Result.NOT_LED, "pin on an order without a lead");
            f.tick();
            final BuildingBuilder lead = leadOf(f, o1);
            final BuildingBuilder spare = lead == f.a() ? f.b() : f.a();
            started(o1, 1000);
            helper.assertTrue(CollabControls.pin(f.colony(), o1.getID(), warehouse.getID()) == CollabControls.Result.NOT_A_BUILDER, "pin a warehouse");
            helper.assertTrue(CollabControls.pin(f.colony(), o1.getID(), lead.getID()) == CollabControls.Result.BUSY, "pin the lead");
            helper.assertTrue(CollabControls.unpin(f.colony(), o1.getID(), spare.getID()) == CollabControls.Result.NOT_AN_ASSISTANT, "unpin a builder that does not help");
            f.tick();
            helper.assertTrue(o1.getCollab().hasAssistant(spare.getID()), "fixture: spare does not help");
            helper.assertTrue(CollabControls.unpin(f.colony(), o1.getID(), spare.getID()) == CollabControls.Result.NOT_AN_ASSISTANT, "unpin a helper that is not pinned");
            helper.assertTrue(CollabControls.pin(f.colony(), 9999, spare.getID()) == CollabControls.Result.NO_SUCH_ORDER, "pin on an unknown order");

            // a second order: the helper takes it and leads it, so he cannot be pinned to the first any more
            final WorkOrderDecoration o2 = BuilderWorkOrderGameTests.order(f, "r2", new BlockPos(30, 1, 20), 0);
            f.tick();
            helper.assertTrue(o2.isClaimed() && o2.getClaimedBy().equals(spare.getID()), "fixture: the helper did not take the second order");
            helper.assertTrue(CollabControls.pin(f.colony(), o1.getID(), spare.getID()) == CollabControls.Result.BUSY, "pin a builder who leads another order");

            BuilderCollab.overrideForTests(false, 3, 40, 10, 100_000, 8, 2400);
            helper.assertTrue(CollabControls.setMaxAssistants(f.colony(), o1.getID(), 1) == CollabControls.Result.DISABLED, "limit with collaboration off");
            helper.assertTrue(CollabControls.pin(f.colony(), o1.getID(), spare.getID()) == CollabControls.Result.DISABLED, "pin with collaboration off");
            helper.assertTrue(!CollabViews.of(f.colony(), o1).collaborationEnabled(), "view says enabled");
            helper.succeed();
        }
        finally
        {
            reset();
        }
    }
}
