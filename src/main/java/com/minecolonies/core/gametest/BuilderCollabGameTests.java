package com.minecolonies.core.gametest;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.util.RotationMirror;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.colony.workorders.IWorkOrderView;
import com.minecolonies.api.colony.workorders.WorkOrderRemovalReason;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.eventbus.events.colony.WorkOrderRemovedModEvent;
import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.core.entity.ai.workers.util.BuilderStageRules;
import com.minecolonies.core.entity.ai.workers.util.CollabStructureHandler;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.core.colony.workorders.AbstractWorkOrder;
import com.minecolonies.core.colony.workorders.WorkOrderDecoration;
import com.minecolonies.core.colony.workorders.collab.BuilderCollab;
import com.minecolonies.core.colony.workorders.collab.WorkOrderCollab;
import com.minecolonies.core.entity.ai.workers.util.BuildingProgressStage;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.ldtteam.structurize.placement.AbstractBlueprintIterator.NULL_POS;

/**
 * Builder collaboration (C1): the scheduler on data-only builders, and two builders on one order with real AI, hand-off to a new
 * order, a reload in the middle of helping, and the same order with collaboration off.
 */
public final class BuilderCollabGameTests
{
    private BuilderCollabGameTests()
    {
    }

    // ------------------------------------------------------------------ scheduler (data-only builders)

    private static BuilderWorkOrderGameTests.Fx fx(final GameTestHelper helper, final String name)
    {
        BuilderCollab.overrideForTests(true, 3, 40, 10, 100_000, 8, 2400);
        return BuilderWorkOrderGameTests.fixture(helper, name, true);
    }

    private static BuildingBuilder extraBuilder(final BuilderWorkOrderGameTests.Fx f, final int x)
    {
        final BuildingBuilder hut = (BuildingBuilder) MinecoloniesGameTests.placeProductionBuilding(f.helper(), f.colony(), ModBlocks.blockHutBuilder,
          new BlockPos(x, 1, 2), "fundamentals/builder1.blueprint");
        BuilderWorkOrderGameTests.hire(f.helper(), f.colony(), hut);
        return hut;
    }

    /**
     * Makes the order look like the lead has started it: a shared stage, block counts, an iterator.
     */
    private static void started(final WorkOrderDecoration order, final int total)
    {
        order.setIteratorType("default");
        order.getCollab().setProgress(NULL_POS, BuildingProgressStage.BUILD_SOLID);
        order.getCollab().setStageTotal(BuildingProgressStage.BUILD_SOLID, total);
    }

    private static int helpers(final BuilderWorkOrderGameTests.Fx f)
    {
        int n = 0;
        for (final IServerWorkOrder order : f.wm().getWorkOrders().values())
        {
            if (order instanceof IBuilderWorkOrder b)
            {
                n += b.getCollab().getAssistantCount();
            }
        }
        return n;
    }

    /**
     * Fewer orders than builders: the spare builder helps. A new order: the helper hands off and leads it. As many orders as builders:
     * nobody helps. Leads keep their orders.
     */
    public static void schedulerHelpsThenHandsOff(final GameTestHelper helper)
    {
        try
        {
            final BuilderWorkOrderGameTests.Fx f = fx(helper, "C1 scheduler");
            final WorkOrderDecoration o1 = BuilderWorkOrderGameTests.order(f, "s1", new BlockPos(30, 1, 10), 0);
            f.tick();
            helper.assertTrue(f.a().hasWorkOrder() && f.a().getWorkOrder() == o1 || f.b().hasWorkOrder() && f.b().getWorkOrder() == o1, "no builder leads the first order");
            final BuildingBuilder lead = f.a().hasWorkOrder() ? f.a() : f.b();
            final BuildingBuilder spare = lead == f.a() ? f.b() : f.a();

            helper.assertTrue(helpers(f) == 0, "a helper before the lead has started: " + helpers(f));
            started(o1, 100);
            f.tick();
            helper.assertTrue(o1.getCollab().hasAssistant(spare.getID()), "the spare builder does not help the only order");
            helper.assertTrue(f.wm().getAssistedOrder(spare.getID()) == o1, "the work manager does not know the helper's order");
            helper.assertTrue(!spare.hasWorkOrder(), "a helper got an order of his own");

            // a new order: the helper hands off
            final WorkOrderDecoration o2 = BuilderWorkOrderGameTests.order(f, "s2", new BlockPos(30, 1, 20), 0);
            f.tick();
            helper.assertTrue(spare.hasWorkOrder() && spare.getWorkOrder() == o2, "the helper did not take the new order: " + spare.getWorkOrder());
            helper.assertTrue(o1.getCollab().getAssistantCount() == 0, "the order still has a helper that leads another one");
            helper.assertTrue(lead.getWorkOrder() == o1, "the lead lost his order");
            helper.assertTrue(helpers(f) == 0, "2 orders for 2 builders but " + helpers(f) + " helpers");

            // more orders than builders: nothing changes
            BuilderWorkOrderGameTests.order(f, "s3", new BlockPos(30, 1, 28), 0);
            f.tick();
            helper.assertTrue(lead.getWorkOrder() == o1 && spare.getWorkOrder() == o2, "a lead was moved by a third order");
            helper.assertTrue(helpers(f) == 0, "3 orders for 2 builders but " + helpers(f) + " helpers");
            helper.succeed();
        }
        finally
        {
            BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
        }
    }

    /**
     * Free builders are given orders before helpers are taken from theirs, in priority order.
     */
    public static void schedulerFreeBuilderBeforeHelper(final GameTestHelper helper)
    {
        try
        {
            final BuilderWorkOrderGameTests.Fx f = fx(helper, "C1 free first");
            final BuildingBuilder third = extraBuilder(f, 24);
            final WorkOrderDecoration o1 = BuilderWorkOrderGameTests.order(f, "f1", new BlockPos(30, 1, 10), 0);
            f.tick();
            final BuildingBuilder lead = f.a().hasWorkOrder() ? f.a() : f.b().hasWorkOrder() ? f.b() : third;
            started(o1, 1000);
            f.tick();
            helper.assertTrue(helpers(f) == 2, "two spare builders should both help the big order, helpers: " + helpers(f));

            // one new order, two helpers: only one of them is taken, the other stays
            final WorkOrderDecoration o2 = BuilderWorkOrderGameTests.order(f, "f2", new BlockPos(30, 1, 20), 0);
            f.tick();
            helper.assertTrue(o1.getCollab().getAssistantCount() == 1, "one helper should stay: " + o1.getCollab().getAssistantCount());
            helper.assertTrue(o2.isClaimed() && !o2.getClaimedBy().equals(lead.getID()), "the new order has no new lead");
            helper.succeed();
        }
        finally
        {
            BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
        }
    }

    /**
     * Helpers per order: one per 40 blocks left, at most three; none when too little is left; level and the stay time are respected.
     */
    public static void schedulerLimitsHelpers(final GameTestHelper helper)
    {
        try
        {
            final BuilderWorkOrderGameTests.Fx f = fx(helper, "C1 limits");
            extraBuilder(f, 24);
            extraBuilder(f, 32);
            final WorkOrderDecoration o1 = BuilderWorkOrderGameTests.order(f, "l1", new BlockPos(30, 1, 10), 0);
            f.tick();
            started(o1, 70);
            f.tick();
            helper.assertTrue(o1.getCollab().getAssistantCount() == 2, "70 blocks left at 40 per helper should give 2 helpers, got " + o1.getCollab().getAssistantCount());

            // too little left: the helpers stay while their stay time runs, and go when it is over
            o1.getCollab().setStageTotal(BuildingProgressStage.BUILD_SOLID, 5);
            f.tick();
            helper.assertTrue(o1.getCollab().getAssistantCount() == 2, "helpers left before their stay time was over");
            BuilderCollab.overrideForTests(true, 3, 40, 10, 0, 8, 2400);
            f.tick();
            helper.assertTrue(o1.getCollab().getAssistantCount() == 0, "helpers stayed on an order with nothing left");
            f.tick();
            helper.assertTrue(o1.getCollab().getAssistantCount() == 0, "helpers came back at once (flapping)");

            // collaboration off: no helpers at all
            started(o1, 1000);
            BuilderCollab.overrideForTests(true, 3, 40, 10, 100_000, 8, 2400);
            f.tick();
            helper.assertTrue(helpers(f) > 0, "fixture: no helpers to switch off");
            BuilderCollab.overrideForTests(false, 3, 40, 10, 100_000, 8, 2400);
            f.tick();
            helper.assertTrue(helpers(f) == 0, "helpers remain with collaboration off");
            helper.succeed();
        }
        finally
        {
            BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
        }
    }

    /**
     * A hut level too low for the order does not help with it.
     */
    public static void schedulerRespectsLevel(final GameTestHelper helper)
    {
        try
        {
            final BuilderWorkOrderGameTests.Fx f = fx(helper, "C1 level");
            final var warehouse = MinecoloniesGameTests.placeProductionBuilding(helper, f.colony(), ModBlocks.blockHutWareHouse, new BlockPos(24, 1, 2),
              "craftsmanship/storage/warehouse1.blueprint");
            warehouse.setBuildingLevel(2);
            final var upgrade = com.minecolonies.core.colony.workorders.WorkOrderBuilding.create(WorkOrderType.UPGRADE, warehouse);
            upgrade.setBlueprint(BuilderWorkOrderGameTests.smallBlueprint("lvl"), f.level());
            upgrade.setIteratorType("default");
            f.a().setBuildingLevel(5);
            f.wm().addWorkOrder(upgrade, true);
            f.tick();
            helper.assertTrue(f.a().hasWorkOrder() && f.a().getWorkOrder() == upgrade, "the level 5 builder should lead the upgrade: " + upgrade.getClaimedBy());
            upgrade.getCollab().setProgress(NULL_POS, BuildingProgressStage.BUILD_SOLID);
            upgrade.getCollab().setStageTotal(BuildingProgressStage.BUILD_SOLID, 500);
            f.tick();
            helper.assertTrue(upgrade.getCollab().getAssistantCount() == 0, "a level 1 builder helps with a level 3 upgrade");
            f.b().setBuildingLevel(3);
            f.tick();
            helper.assertTrue(upgrade.getCollab().getAssistantCount() == 1, "a level 3 builder does not help with a level 3 upgrade");
            helper.succeed();
        }
        finally
        {
            BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
        }
    }

    // ------------------------------------------------------------------ persistence and views

    /**
     * What helpers and leases have is saved with the order, the carried materials with the hut, and clients get the numbers.
     */
    public static void collabStatePersistsAndSyncs(final GameTestHelper helper)
    {
        try
        {
            final BuilderWorkOrderGameTests.Fx f = fx(helper, "C1 persist");
            final WorkOrderDecoration order = BuilderWorkOrderGameTests.order(f, "p1", new BlockPos(30, 1, 10), 0);
            f.tick();
            final BuildingBuilder lead = f.a().hasWorkOrder() ? f.a() : f.b();
            final BuildingBuilder spare = lead == f.a() ? f.b() : f.a();
            started(order, 100);
            f.tick();
            helper.assertTrue(order.getCollab().hasAssistant(spare.getID()), "fixture: no helper");

            final WorkOrderCollab c = order.getCollab();
            final BlockPos leased = f.helper().absolutePos(new BlockPos(31, 1, 11));
            c.lease(leased, spare.getID(), 123_456L, false);
            c.setProgress(new BlockPos(2, 0, 3), BuildingProgressStage.BUILD_SOLID);
            c.setScanCursor(new BlockPos(5, 0, 5));
            for (int i = 0; i < 7; i++)
            {
                c.blockProcessed();
            }
            spare.addCarry(lead.getID(), new ItemStack(Items.STONE_BRICKS), 12);

            final CompoundTag tag = new CompoundTag();
            order.write(tag);
            final WorkOrderDecoration copy = new WorkOrderDecoration();
            copy.setColony(f.colony());
            copy.read(tag, f.wm());
            final WorkOrderCollab r = copy.getCollab();
            helper.assertTrue(r.hasAssistant(spare.getID()) && r.getAssistantCount() == 1, "helper not saved");
            helper.assertTrue(r.isLeasedByOther(leased, lead.getID()) && !r.isLeasedByOther(leased, spare.getID()), "lease not saved");
            helper.assertTrue(new BlockPos(2, 0, 3).equals(r.getProgressPos()) && r.getProgressStage() == BuildingProgressStage.BUILD_SOLID, "cursor not saved: " + r.getProgressPos());
            helper.assertTrue(new BlockPos(5, 0, 5).equals(r.getScanCursor()), "scan cursor not saved");
            helper.assertTrue(r.getPlacedBlocks() == 7 && r.getTotalBlocks() == 100, "counts not saved: " + r.getPlacedBlocks() + "/" + r.getTotalBlocks());

            final CompoundTag hutTag = spare.serializeNBT(f.level().registryAccess());
            spare.clearCarry();
            helper.assertTrue(!spare.hasCarry(), "fixture: carry not cleared");
            spare.deserializeNBT(f.level().registryAccess(), hutTag);
            helper.assertTrue(spare.hasCarry() && lead.getID().equals(spare.getCarryLead()), "carry lead not saved");
            helper.assertTrue(spare.getCarry().values().stream().mapToInt(Integer::intValue).sum() == 12, "carry not saved: " + spare.getCarry());

            order.setProjectId("terraform-7");
            lead.setWaitingFor(Map.of(new ItemStorage(new ItemStack(Items.STONE)), 5));

            // what clients see
            final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), f.level().registryAccess());
            order.serializeViewNetworkData(buf);
            final IWorkOrderView view = AbstractWorkOrder.createWorkOrderView(buf);
            helper.assertTrue(view != null, "the view could not be read");
            helper.assertTrue(view.getPlacedBlocks() == 7 && view.getTotalBlocks() == 100, "view counts " + view.getPlacedBlocks() + "/" + view.getTotalBlocks());
            helper.assertTrue(view.getLeaseCount() == 1, "view leases " + view.getLeaseCount());
            helper.assertTrue(view.getAssistantHuts().equals(List.of(spare.getID())), "view helpers " + view.getAssistantHuts());
            helper.assertTrue(view.getAssistantCitizenIds().get(0) == (spare == f.a() ? f.ca().getId() : f.cb().getId()), "view helper citizen " + view.getAssistantCitizenIds());
            helper.assertTrue("terraform-7".equals(view.getProjectId()), "view project " + view.getProjectId());
            helper.assertTrue(view.getWaitingFor().size() == 1 && view.getWaitingFor().get(0).is(Items.STONE) && view.getWaitingFor().get(0).getCount() == 5, "view waiting " + view.getWaitingFor());
            lead.setWaitingFor(Map.of());
            final RegistryFriendlyByteBuf buf2 = new RegistryFriendlyByteBuf(Unpooled.buffer(), f.level().registryAccess());
            order.serializeViewNetworkData(buf2);
            helper.assertTrue(AbstractWorkOrder.createWorkOrderView(buf2).getWaitingFor().isEmpty(), "waiting not cleared");
            helper.succeed();
        }
        finally
        {
            BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
        }
    }

    // ------------------------------------------------------------------ two builders on one order, real AI

    private static final Block   BUILD_BLOCK = Blocks.STONE_BRICKS;
    private static final Item    BUILD_ITEM  = Items.STONE_BRICKS;
    private static final int     SIZE        = 12;
    private static final int     SPARE_ITEMS = 20;

    /**
     * The scene: a colony with two builders (hut + builder with AI each), a flat stone floor, a 12x12 floor of stone bricks to build
     * and enough bricks in builder A's hut (plus a little more, to see none lost).
     */
    private static final class Live
    {
        final GameTestHelper helper;
        final ServerLevel    level;
        IColony              colony;
        BuildingBuilder      a;
        BuildingBuilder      b;
        ICitizenData         ca;
        ICitizenData         cb;
        final Map<BlockPos, List<BlockPos>> placements = new HashMap<>();
        final List<BlockPos>                chunksForced = new ArrayList<>();

        Live(final GameTestHelper helper)
        {
            this.helper = helper;
            this.level = helper.getLevel();
        }

        WorkOrderDecoration order(final String name, final BlockPos rel, final Block block, final int size)
        {
            return order(name, rel, block, size, null);
        }

        /**
         * @param lead the builder that is to lead the order (his hut holds the materials), or null for whoever the work manager picks.
         */
        WorkOrderDecoration order(final String name, final BlockPos rel, final Block block, final int size, final BuildingBuilder lead)
        {
            final WorkOrderDecoration order = WorkOrderDecoration.create(WorkOrderType.BUILD, "Minecolonies Original", "gametest/" + name + ".blueprint", "gametest." + name,
              helper.absolutePos(rel), RotationMirror.NONE, 0);
            order.setBlueprint(floor(name, block, size), level);
            colony.getWorkManager().addWorkOrder(order, true);
            helper.assertTrue(order.getID() > 0, "no id for " + name);
            if (lead != null)
            {
                order.setClaimedBy(lead.getID());
                lead.setWorkOrder(order);
            }
            return order;
        }

        Blueprint floor(final String name, final Block block, final int size)
        {
            final Blueprint blueprint = new Blueprint((short) size, (short) 1, (short) size).setName(name).setFileName(name).setPackName("Minecolonies Original");
            for (int x = 0; x < size; x++)
            {
                for (int z = 0; z < size; z++)
                {
                    blueprint.addBlockState(new BlockPos(x, 0, z), block.defaultBlockState());
                }
            }
            blueprint.setCachePrimaryOffset(BlockPos.ZERO);
            return blueprint;
        }

        int count(final Item item)
        {
            int n = 0;
            for (final BuildingBuilder hut : List.of(a, b))
            {
                n += InventoryUtils.getItemCountInItemHandler(hut.getItemHandlerCap(), s -> s.is(item));
            }
            for (final ICitizenData citizen : List.of(ca, cb))
            {
                n += InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), s -> s.is(item));
            }
            return n;
        }

        void stock(final BuildingBuilder hut, final Item item, int amount)
        {
            while (amount > 0)
            {
                final int n = Math.min(amount, item.getDefaultMaxStackSize());
                helper.assertTrue(InventoryUtils.forceItemStackToItemHandler(hut.getItemHandlerCap(), new ItemStack(item, n), s -> true).isEmpty(), "no room in hut " + hut.getID() + " for " + item);
                amount -= n;
            }
        }
    }

    /**
     * Builds the scene and calls {@code ready} once both builders exist (after the same wait the production fixtures use).
     */
    private static void scene(final GameTestHelper helper, final String name, final java.util.function.Consumer<Live> ready)
    {
        MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper);
        final Live live = new Live(helper);
        live.colony = MinecoloniesGameTests.foundGameTestColony(helper, name);
        live.a = (BuildingBuilder) MinecoloniesGameTests.placeProductionBuilding(helper, live.colony, ModBlocks.blockHutBuilder, new BlockPos(8, 1, 2), "fundamentals/builder1.blueprint");
        live.b = (BuildingBuilder) MinecoloniesGameTests.placeProductionBuilding(helper, live.colony, ModBlocks.blockHutBuilder, new BlockPos(16, 1, 2), "fundamentals/builder1.blueprint");
        final BlockPos anchorA = helper.absolutePos(new BlockPos(10, 1, 8));
        final BlockPos anchorB = helper.absolutePos(new BlockPos(18, 1, 8));
        forceArea(live.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(40, 1, 32)));
        helper.runAfterDelay(200, () -> {
            forceArea(live.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(40, 1, 32)));
            live.ca = live.colony.getCitizenManager().spawnOrCreateCivilian(null, live.level, List.of(anchorA), true);
            live.cb = live.colony.getCitizenManager().spawnOrCreateCivilian(null, live.level, List.of(anchorB), true);
            helper.assertTrue(live.ca != null && live.cb != null && live.ca.getEntity().isPresent() && live.cb.getEntity().isPresent(), "builders did not spawn");
            helper.assertTrue(live.a.getModule(BuildingModules.BUILDER_WORK).assignCitizen(live.ca), "builder A not assigned");
            helper.assertTrue(live.b.getModule(BuildingModules.BUILDER_WORK).assignCitizen(live.cb), "builder B not assigned");
            live.ca.getEntity().ifPresent(e -> e.setPos(live.a.getID().getX() + 0.5D, live.a.getID().getY(), live.a.getID().getZ() + 0.5D));
            live.cb.getEntity().ifPresent(e -> e.setPos(live.b.getID().getX() + 0.5D, live.b.getID().getY(), live.b.getID().getZ() + 0.5D));
            BuilderCollab.placementProbe = (pos, hut) -> live.placements.computeIfAbsent(pos, p -> new ArrayList<>()).add(hut);
            ready.accept(live);
        });
    }

    private static void forceArea(final ServerLevel level, final BlockPos min, final BlockPos max)
    {
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++)
        {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++)
            {
                level.setChunkForced(cx, cz, true);
            }
        }
    }

    /**
     * Calls {@code check} every 100 ticks until it returns true; fails the test with the message after the given number of ticks.
     */
    private static void poll(final Live live, final int maxTicks, final BooleanSupplier check, final Runnable onDone, final Supplier<String> failure)
    {
        final int[] elapsed = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            // no night: the builders have no beds
            live.level.getServer().clockManager().setTotalTicks(live.level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
            // the test colony has no player close by and so no colony tick of its own: the work manager is ticked here, once a second like in a running colony
            live.colony.getWorkManager().onColonyTick(live.colony);
            if (check.getAsBoolean())
            {
                onDone.run();
                return;
            }
            if (elapsed[0] % 500 == 0)
            {
                Log.getLogger().info("C1 poll t={} {}", elapsed[0], failure.get());
            }
            if (elapsed[0] >= maxTicks)
            {
                live.helper.assertTrue(false, "timeout after " + maxTicks + " ticks: " + failure.get());
                return;
            }
            elapsed[0] += 20;
            live.helper.runAfterDelay(20, () -> step[0].run());
        };
        live.helper.runAfterDelay(1, step[0]);
    }

    private static String describe(final Live live, final IBuilderWorkOrder... orders)
    {
        final StringBuilder sb = new StringBuilder();
        for (final IBuilderWorkOrder order : orders)
        {
            final WorkOrderCollab c = order.getCollab();
            sb.append("order ").append(order.getID()).append(" claimedBy=").append(order.getClaimedBy()).append(" stage=").append(c.getProgressStage())
              .append(" placed=").append(c.getPlacedBlocks()).append('/').append(c.getTotalBlocks()).append(" leases=").append(c.getLeaseCount())
              .append(" helpers=").append(c.getAssistantHuts()).append("; ");
        }
        sb.append("A.order=").append(live.a.getWorkOrder() == null ? null : live.a.getWorkOrder().getID()).append(" B.order=").append(live.b.getWorkOrder() == null ? null : live.b.getWorkOrder().getID())
          .append(" B.carry=").append(live.b.getCarry().size()).append(" placements=").append(live.placements.size())
          .append(" stateA=").append(live.ca.getEntity().map(e -> e.getCitizenJobHandler().getWorkAI() == null ? "no-ai" : String.valueOf(e.getCitizenJobHandler().getWorkAI().getStateAI().getState())).orElse("-"))
          .append(" stateB=").append(live.cb.getEntity().map(e -> e.getCitizenJobHandler().getWorkAI() == null ? "no-ai" : String.valueOf(e.getCitizenJobHandler().getWorkAI().getStateAI().getState())).orElse("-"))
          .append(" bricks=").append(live.count(BUILD_ITEM))
          .append(" (hutA=").append(InventoryUtils.getItemCountInItemHandler(live.a.getItemHandlerCap(), x -> x.is(BUILD_ITEM)))
          .append(" invA=").append(InventoryUtils.getItemCountInItemHandler(live.ca.getInventory(), x -> x.is(BUILD_ITEM)))
          .append(" hutB=").append(InventoryUtils.getItemCountInItemHandler(live.b.getItemHandlerCap(), x -> x.is(BUILD_ITEM)))
          .append(" invB=").append(InventoryUtils.getItemCountInItemHandler(live.cb.getInventory(), x -> x.is(BUILD_ITEM)))
          .append(" carryB=").append(live.b.getCarry().values()).append(')');
        for (final ICitizenData citizen : List.of(live.ca, live.cb))
        {
            sb.append(" [citizen ").append(citizen.getId()).append(" job=").append(citizen.getJob() == null ? null : citizen.getJob().getClass().getSimpleName())
              .append(" workBuilding=").append(citizen.getWorkBuilding() == null ? null : citizen.getWorkBuilding().getID())
              .append(" entity=").append(citizen.getEntity().map(e -> e.blockPosition() + (e.isAlive() ? "" : " dead")).orElse("none"))
              .append(" history=").append(citizen.getEntity().map(e -> e.getCitizenJobHandler().getWorkAI() == null ? "no-ai" : e.getCitizenJobHandler().getWorkAI().getStateAI().getHistory().getString()).orElse("-"))
              .append(']');
        }
        return sb.toString();
    }

    private static boolean floorBuilt(final Live live, final WorkOrderDecoration order, final Block block, final int size)
    {
        for (int x = 0; x < size; x++)
        {
            for (int z = 0; z < size; z++)
            {
                if (!live.level.getBlockState(order.getLocation().offset(x, 0, z)).is(block))
                {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean idle(final Live live)
    {
        return !live.a.hasWorkOrder() && !live.b.hasWorkOrder() && !live.a.hasCarry() && !live.b.hasCarry()
                 && live.colony.getWorkManager().getAssistedOrder(live.a.getID()) == null && live.colony.getWorkManager().getAssistedOrder(live.b.getID()) == null;
    }

    private static void checkPlacedOnce(final Live live, final WorkOrderDecoration order, final int size, final boolean requireBoth)
    {
        final GameTestHelper helper = live.helper;
        final Map<BlockPos, Integer> byBuilder = new HashMap<>();
        for (int x = 0; x < size; x++)
        {
            for (int z = 0; z < size; z++)
            {
                final BlockPos pos = order.getLocation().offset(x, 0, z);
                final List<BlockPos> placed = live.placements.get(pos);
                helper.assertTrue(placed != null && placed.size() == 1, "position " + pos + " was placed " + (placed == null ? 0 : placed.size()) + " times: " + placed);
                byBuilder.merge(placed.get(0), 1, Integer::sum);
            }
        }
        if (requireBoth)
        {
            helper.assertTrue(byBuilder.getOrDefault(live.a.getID(), 0) > 0 && byBuilder.getOrDefault(live.b.getID(), 0) > 0,
              "both builders should have placed blocks, but: A=" + byBuilder.getOrDefault(live.a.getID(), 0) + " B=" + byBuilder.getOrDefault(live.b.getID(), 0));
        }
        Log.getLogger().info("C1 placements by builder: A={} B={}", byBuilder.getOrDefault(live.a.getID(), 0), byBuilder.getOrDefault(live.b.getID(), 0));
    }

    private static void finish(final Live live)
    {
        BuilderCollab.placementProbe = null;
        BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
        live.helper.succeed();
    }

    /**
     * Two builders, one order: both place, no position twice, and every item is accounted for.
     */
    public static void twoBuildersOneOrder(final GameTestHelper helper)
    {
        BuilderCollab.overrideForTests(true, 3, 10_000, 10, 100_000, 8, 2400);
        scene(helper, "C1 two builders", live -> {
            live.stock(live.a, BUILD_ITEM, SIZE * SIZE + SPARE_ITEMS);
            final int before = live.count(BUILD_ITEM);
            final WorkOrderDecoration order = live.order("two", new BlockPos(24, 1, 10), BUILD_BLOCK, SIZE, live.a);
            poll(live, 30_000, () -> live.colony.getWorkManager().getWorkOrder(order.getID()) == null && idle(live), () -> {
                helper.assertTrue(floorBuilt(live, order, BUILD_BLOCK, SIZE), "floor not complete");
                checkPlacedOnce(live, order, SIZE, true);
                helper.assertTrue(before == SIZE * SIZE + SPARE_ITEMS, "fixture: stocked " + before);
                helper.assertTrue(live.count(BUILD_ITEM) == SPARE_ITEMS, "items not conserved: " + live.count(BUILD_ITEM) + " left of " + before + ", expected " + SPARE_ITEMS);
                finish(live);
            }, () -> describe(live, order));
        });
    }

    /**
     * A new order arrives while a builder helps: he hands off (leases back, materials back in the lead's hut) and leads the new order.
     */
    public static void newOrderTakesTheHelper(final GameTestHelper helper)
    {
        BuilderCollab.overrideForTests(true, 3, 10_000, 10, 100_000, 8, 2400);
        scene(helper, "C1 hand off", live -> {
            live.stock(live.a, BUILD_ITEM, SIZE * SIZE + SPARE_ITEMS);
            live.stock(live.b, Items.COBBLESTONE, 6 * 6 + 4);
            final WorkOrderDecoration first = live.order("first", new BlockPos(24, 1, 10), BUILD_BLOCK, SIZE, live.a);
            final WorkOrderDecoration[] second = {null};
            final boolean[] handedOff = {false};
            final int[] helpedBeforeHandoff = {0};
            final int[] stepsSinceSecond = {0};
            poll(live, 30_000, () -> {
                final boolean bHelps = live.colony.getWorkManager().getAssistedOrder(live.b.getID()) == first;
                if (second[0] == null && bHelps && live.placements.values().stream().filter(l -> l.contains(live.b.getID())).count() >= 8)
                {
                    helpedBeforeHandoff[0] = (int) live.placements.values().stream().filter(l -> l.contains(live.b.getID())).count();
                    second[0] = live.order("second", new BlockPos(24, 1, 24), Blocks.COBBLESTONE, 6);
                }
                if (second[0] != null && !handedOff[0] && live.b.hasWorkOrder() && live.b.getWorkOrder() == second[0])
                {
                    handedOff[0] = true;
                    helper.assertTrue(first.getCollab().getAssistantCount() == 0, "the helper is still listed on the first order");
                    helper.assertTrue(first.getCollab().getLeaseCountOf(live.b.getID()) == 0, "the helper still holds leases on the first order");
                }
                if (second[0] != null && stepsSinceSecond[0]++ >= 2 && live.colony.getWorkManager().getWorkOrder(second[0].getID()) != null)
                {
                    helper.assertTrue(first.getCollab().getAssistantCount() == 0, "2 orders for 2 builders but a helper exists");
                }
                return second[0] != null && live.colony.getWorkManager().getWorkOrder(first.getID()) == null
                         && live.colony.getWorkManager().getWorkOrder(second[0].getID()) == null && idle(live);
            }, () -> {
                helper.assertTrue(handedOff[0], "the helper never took the new order");
                helper.assertTrue(helpedBeforeHandoff[0] >= 8, "fixture: the helper placed " + helpedBeforeHandoff[0] + " blocks before the hand-off");
                helper.assertTrue(floorBuilt(live, first, BUILD_BLOCK, SIZE), "first floor not complete");
                helper.assertTrue(floorBuilt(live, second[0], Blocks.COBBLESTONE, 6), "second floor not complete");
                checkPlacedOnce(live, first, SIZE, true);
                helper.assertTrue(live.count(BUILD_ITEM) == SPARE_ITEMS, "bricks not conserved over the hand-off: " + live.count(BUILD_ITEM) + ", expected " + SPARE_ITEMS);
                helper.assertTrue(live.count(Items.COBBLESTONE) == 4, "cobblestone not conserved: " + live.count(Items.COBBLESTONE) + ", expected 4");
                finish(live);
            }, () -> describe(live, first)
                       + (second[0] == null ? "" : " " + describe(live, first, second[0])));
        });
    }

    /**
     * The shared state is reloaded in the middle of helping (work orders written and read back, the helper's hut saved and loaded):
     * helping goes on from what was saved, nothing is placed twice and nothing is lost.
     */
    public static void reloadWhileHelping(final GameTestHelper helper)
    {
        BuilderCollab.overrideForTests(true, 3, 10_000, 10, 100_000, 8, 2400);
        scene(helper, "C1 reload", live -> {
            live.stock(live.a, BUILD_ITEM, SIZE * SIZE + SPARE_ITEMS);
            final WorkOrderDecoration order = live.order("reload", new BlockPos(24, 1, 10), BUILD_BLOCK, SIZE, live.a);
            final boolean[] reloaded = {false};
            poll(live, 30_000, () -> {
                final IBuilderWorkOrder current = live.colony.getWorkManager().getWorkOrder(order.getID(), IBuilderWorkOrder.class);
                if (!reloaded[0] && current != null && current.getCollab().getLeaseCountOf(live.b.getID()) > 0 && live.b.hasCarry()
                      && current.getCollab().getPlacedBlocks() > 10)
                {
                    reloaded[0] = true;
                    final int leasesBefore = current.getCollab().getLeaseCountOf(live.b.getID());
                    final Map<?, Integer> carryBefore = live.b.getCarry();
                    final CompoundTag workTag = new CompoundTag();
                    live.colony.getWorkManager().write(workTag);
                    final CompoundTag hutTag = live.b.serializeNBT(live.level.registryAccess());
                    live.colony.getWorkManager().read(workTag);
                    live.b.clearCarry();
                    live.b.deserializeNBT(live.level.registryAccess(), hutTag);
                    final IBuilderWorkOrder back = live.colony.getWorkManager().getWorkOrder(order.getID(), IBuilderWorkOrder.class);
                    helper.assertTrue(back != null && back != current, "the order was not read back");
                    back.setBlueprint(live.floor("reload", BUILD_BLOCK, SIZE), live.level);
                    helper.assertTrue(back.getCollab().getLeaseCountOf(live.b.getID()) == leasesBefore, "leases after reload: " + back.getCollab().getLeaseCountOf(live.b.getID()) + ", before " + leasesBefore);
                    helper.assertTrue(back.getCollab().hasAssistant(live.b.getID()), "helper lost in the reload");
                    helper.assertTrue(live.b.getCarry().equals(carryBefore), "carry changed in the reload: " + live.b.getCarry() + " vs " + carryBefore);
                    helper.assertTrue(back.getCollab().getProgressPos() != null, "cursor lost in the reload");
                }
                return reloaded[0] && live.colony.getWorkManager().getWorkOrder(order.getID()) == null && idle(live);
            }, () -> {
                helper.assertTrue(floorBuilt(live, order, BUILD_BLOCK, SIZE), "floor not complete");
                checkPlacedOnce(live, order, SIZE, true);
                helper.assertTrue(live.count(BUILD_ITEM) == SPARE_ITEMS, "items not conserved over the reload: " + live.count(BUILD_ITEM) + ", expected " + SPARE_ITEMS);
                finish(live);
            }, () -> {
                final IBuilderWorkOrder current = live.colony.getWorkManager().getWorkOrder(order.getID(), IBuilderWorkOrder.class);
                return "reloaded=" + reloaded[0] + " " + (current == null ? "order gone" : describe(live, current));
            });
        });
    }

    /**
     * With collaboration off the second builder does not help, and the first builds alone as before.
     */
    public static void collaborationOffChangesNothing(final GameTestHelper helper)
    {
        BuilderCollab.overrideForTests(false, 3, 10_000, 10, 100_000, 8, 2400);
        scene(helper, "C1 off", live -> {
            live.stock(live.a, BUILD_ITEM, SIZE * SIZE + SPARE_ITEMS);
            final WorkOrderDecoration order = live.order("off", new BlockPos(24, 1, 10), BUILD_BLOCK, SIZE, live.a);
            poll(live, 30_000, () -> {
                helper.assertTrue(order.getCollab().getAssistantCount() == 0 && order.getCollab().getOpenLeasesExcluding(live.a.getID()) == 0, "a helper or lease of a helper with collaboration off");
                return live.colony.getWorkManager().getWorkOrder(order.getID()) == null && idle(live);
            }, () -> {
                helper.assertTrue(floorBuilt(live, order, BUILD_BLOCK, SIZE), "floor not complete");
                checkPlacedOnce(live, order, SIZE, false);
                for (final List<BlockPos> huts : live.placements.values())
                {
                    helper.assertTrue(huts.size() == 1 && huts.get(0).equals(live.a.getID()), "a block was placed by " + huts + " with collaboration off");
                }
                helper.assertTrue(live.count(BUILD_ITEM) == SPARE_ITEMS, "items not conserved: " + live.count(BUILD_ITEM));
                finish(live);
            }, () -> describe(live, order));
        });
    }

    // ------------------------------------------------------------------ small additions for the terraform work

    private static final List<WorkOrderRemovedModEvent> REMOVED = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static boolean subscribed = false;

    private static WorkOrderRemovalReason removalReasonOf(final int colonyId, final IBuilderWorkOrder order)
    {
        for (final WorkOrderRemovedModEvent event : REMOVED)
        {
            if (event.getColony().getID() == colonyId && event.getWorkOrder() == order)
            {
                return event.getReason();
            }
        }
        return null;
    }

    /**
     * Removed orders say why: completed, cancelled, invalid, replaced.
     */
    public static void removalReasons(final GameTestHelper helper)
    {
        if (!subscribed)
        {
            subscribed = true;
            IMinecoloniesAPI.getInstance().getEventBus().subscribe(WorkOrderRemovedModEvent.class, REMOVED::add);
        }
        final BuilderWorkOrderGameTests.Fx f = BuilderWorkOrderGameTests.fixture(helper, "C1 removal reasons", false);
        final int id = f.colony().getID();
        final WorkOrderDecoration cancelled = BuilderWorkOrderGameTests.order(f, "r1", new BlockPos(30, 1, 10), 0);
        f.wm().removeWorkOrder(cancelled.getID());
        helper.assertTrue(removalReasonOf(id, cancelled) == WorkOrderRemovalReason.CANCELLED, "cancelled: " + removalReasonOf(id, cancelled));

        final WorkOrderDecoration completed = BuilderWorkOrderGameTests.order(f, "r2", new BlockPos(30, 1, 18), 0);
        completed.onCompleted(f.colony(), f.ca());
        helper.assertTrue(removalReasonOf(id, completed) == WorkOrderRemovalReason.COMPLETED, "completed: " + removalReasonOf(id, completed));

        final WorkOrderDecoration replaced = BuilderWorkOrderGameTests.order(f, "r3", new BlockPos(30, 1, 26), 0);
        final WorkOrderDecoration again = WorkOrderDecoration.create(WorkOrderType.BUILD, "Minecolonies Original", "gametest/r3.blueprint", "gametest.r3",
          helper.absolutePos(new BlockPos(30, 1, 26)), RotationMirror.NONE, 0);
        again.setBlueprint(BuilderWorkOrderGameTests.smallBlueprint("r3"), f.level());
        f.wm().addWorkOrder(again, true);
        helper.assertTrue(removalReasonOf(id, replaced) == WorkOrderRemovalReason.REPLACED, "replaced: " + removalReasonOf(id, replaced));

        final var warehouse = MinecoloniesGameTests.placeProductionBuilding(helper, f.colony(), ModBlocks.blockHutWareHouse, new BlockPos(24, 1, 2), "craftsmanship/storage/warehouse1.blueprint");
        final var invalid = com.minecolonies.core.colony.workorders.WorkOrderBuilding.create(WorkOrderType.UPGRADE, warehouse);
        invalid.setBlueprint(BuilderWorkOrderGameTests.smallBlueprint("r4"), f.level());
        f.wm().addWorkOrder(invalid, true);
        f.colony().getServerBuildingManager().removeBuilding(warehouse, java.util.Collections.emptySet());
        f.tick();
        helper.assertTrue(removalReasonOf(id, invalid) == WorkOrderRemovalReason.INVALID, "invalid: " + removalReasonOf(id, invalid));
        helper.succeed();
    }

    /**
     * Quiet add: the order is in, no tape, and orders outside the colony are refused without a message.
     */
    public static void quietAdd(final GameTestHelper helper)
    {
        final BuilderWorkOrderGameTests.Fx f = BuilderWorkOrderGameTests.fixture(helper, "C1 quiet add", false);
        final WorkOrderDecoration inside = WorkOrderDecoration.create(WorkOrderType.BUILD, "Minecolonies Original", "fundamentals/builder1.blueprint", "gametest.quiet",
          helper.absolutePos(new BlockPos(24, 1, 12)), RotationMirror.NONE, 0);
        helper.assertTrue(f.wm().addWorkOrderQuietly(inside) && inside.getID() > 0 && f.wm().getWorkOrder(inside.getID()) == inside, "order inside the colony not added");
        final WorkOrderDecoration outside = WorkOrderDecoration.create(WorkOrderType.BUILD, "Minecolonies Original", "fundamentals/builder1.blueprint", "gametest.far",
          helper.absolutePos(new BlockPos(600, 1, 12)), RotationMirror.NONE, 0);
        helper.assertTrue(!f.wm().addWorkOrderQuietly(outside) && f.wm().getWorkOrder(outside.getID()) == null, "order outside the colony added");
        helper.succeed();
    }

    /**
     * The next section of a project goes to the builder who did the last one, if collaboration is on.
     */
    public static void projectAffinity(final GameTestHelper helper)
    {
        try
        {
            BuilderCollab.overrideForTests(true, 3, 40, 10, 100_000, 8, 2400);
            for (int round = 0; round < 2; round++)
            {
                final BuilderWorkOrderGameTests.Fx f = BuilderWorkOrderGameTests.fixture(helper, "C1 affinity " + round, true);
                final BuildingBuilder first = round == 0 ? f.a() : f.b();
                final WorkOrderDecoration s1 = BuilderWorkOrderGameTests.order(f, "a1r" + round, new BlockPos(30, 1, 10), 0);
                s1.setProjectId("terraform-1");
                BuilderWorkOrderGameTests.claim(first, s1);
                f.wm().removeWorkOrder(s1.getID(), WorkOrderRemovalReason.COMPLETED);
                first.setWorkOrder(null);
                helper.assertTrue("terraform-1".equals(first.getLastProjectId()), "the hut does not remember its project");

                final WorkOrderDecoration s2 = BuilderWorkOrderGameTests.order(f, "a3r" + round, new BlockPos(30, 1, 28), 0);
                s2.setProjectId("terraform-1");
                BuilderCollab.overrideForTests(true, 3, 40, 10, 100_000, 8, 2400);
                f.tick();
                helper.assertTrue(s2.getClaimedBy().equals(first.getID()), "round " + round + ": the next section went to " + s2.getClaimedBy() + " not to the builder of the project " + first.getID());
            }
            helper.succeed();
        }
        finally
        {
            BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
        }
    }

    /**
     * Fast clear: a block that already is what the blueprint wants is not mined, if the setting is on.
     */
    public static void fastClear(final GameTestHelper helper)
    {
        try
        {
            final BuilderWorkOrderGameTests.Fx f = BuilderWorkOrderGameTests.fixture(helper, "C1 fast clear", false);
            final WorkOrderDecoration order = BuilderWorkOrderGameTests.order(f, "fc", new BlockPos(24, 1, 10), 0);
            final BlockPos stoneCell = new BlockPos(0, 0, 0);
            final BlockPos grassCell = new BlockPos(2, 1, 2);
            final Blueprint withAir = BuilderWorkOrderGameTests.smallBlueprint("fc2");
            withAir.addBlockState(grassCell, Blocks.AIR.defaultBlockState());
            order.setBlueprint(withAir, f.level());
            final CollabStructureHandler handler = new CollabStructureHandler(f.level(), order, BuildingProgressStage.CLEAR, null, () -> Blocks.DIRT.defaultBlockState());
            f.level().setBlock(handler.getProgressPosInWorld(stoneCell), Blocks.STONE.defaultBlockState(), 3);
            f.level().setBlock(handler.getProgressPosInWorld(grassCell), Blocks.DIRT.defaultBlockState(), 3);
            final var stoneInfo = order.getBlueprint().getBluePrintPositionInfo(stoneCell, false);
            final var otherInfo = order.getBlueprint().getBluePrintPositionInfo(grassCell, false);
            BuilderCollab.overrideFastClearForTests(false);
            helper.assertTrue(!BuilderStageRules.skipClearing(stoneInfo, handler.getProgressPosInWorld(stoneCell), handler), "a stone to build over stone is skipped with fast clear off");
            BuilderCollab.overrideFastClearForTests(true);
            helper.assertTrue(BuilderStageRules.skipClearing(stoneInfo, handler.getProgressPosInWorld(stoneCell), handler), "a stone to build over stone is mined with fast clear on");
            helper.assertTrue(!BuilderStageRules.skipClearing(otherInfo, handler.getProgressPosInWorld(grassCell), handler), "dirt where the blueprint wants air is skipped (it must be mined)");
            helper.succeed();
        }
        finally
        {
            BuilderCollab.overrideFastClearForTests(null);
        }
    }
}
