package com.minecolonies.core.gametest;

import com.google.common.reflect.TypeToken;
import com.ldtteam.structurize.api.util.Tuple;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.util.RotationMirror;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.workorders.IWorkManager;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.core.colony.buildings.moduleviews.BuildingResourcesModuleView;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import com.minecolonies.core.colony.workorders.WorkOrderDecoration;
import com.minecolonies.core.entity.ai.workers.util.BuildingProgressStage;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.NotNull;

import static com.ldtteam.structurize.placement.AbstractBlueprintIterator.NULL_POS;
import static com.minecolonies.api.util.constant.NbtTagConstants.TAG_FLUIDS_REMOVE;

/**
 * C0 of the builder collaboration work: failing-first GameTests for the work order bugs found in findings/17 (risks 4, 5,
 * 6, 7, 9, 10, 15). They use real colonies with builder huts and builders that exist as data only (no entity, so no AI
 * interferes), and drive {@code WorkManager.onColonyTick} by hand.
 */
public final class BuilderWorkOrderGameTests
{
    private BuilderWorkOrderGameTests()
    {
    }

    // ------------------------------------------------------------------ fixture

    /**
     * Colony with up to two builder huts that each have a data-only builder.
     */
    record Fx(GameTestHelper helper, ServerLevel level, IColony colony, BuildingBuilder a, BuildingBuilder b, ICitizenData ca, ICitizenData cb)
    {
        IWorkManager wm()
        {
            return colony.getWorkManager();
        }

        void tick()
        {
            wm().onColonyTick(colony);
        }
    }

    static Fx fixture(final GameTestHelper helper, final String name, final boolean secondBuilder)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, name);
        final BuildingBuilder a = (BuildingBuilder) MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockHutBuilder,
          new BlockPos(8, 1, 2), "fundamentals/builder1.blueprint");
        final BuildingBuilder b = (BuildingBuilder) MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockHutBuilder,
          new BlockPos(16, 1, 2), "fundamentals/builder1.blueprint");
        final ICitizenData ca = hire(helper, colony, a);
        final ICitizenData cb = secondBuilder ? hire(helper, colony, b) : null;
        return new Fx(helper, level, colony, a, b, ca, cb);
    }

    static ICitizenData hire(final GameTestHelper helper, final IColony colony, final BuildingBuilder hut)
    {
        final ICitizenData citizen = colony.getCitizenManager().createAndRegisterCivilianData();
        helper.assertTrue(hut.getModule(BuildingModules.BUILDER_WORK).assignCitizen(citizen), "could not assign data-only builder to " + hut.getID());
        return citizen;
    }

    /**
     * A decoration order with a small in-memory blueprint, added the way the work manager reads orders from NBT (no
     * chunk or construction tape checks).
     */
    static WorkOrderDecoration order(final Fx f, final String name, final BlockPos rel, final int priority)
    {
        final WorkOrderDecoration order = WorkOrderDecoration.create(WorkOrderType.BUILD, "Minecolonies Original", "gametest/" + name + ".blueprint", "gametest." + name,
          f.helper().absolutePos(rel), RotationMirror.NONE, 0);
        order.setBlueprint(smallBlueprint(name), f.level());
        order.setPriority(priority);
        f.wm().addWorkOrder(order, true);
        f.helper().assertTrue(order.getID() > 0, "order " + name + " got no id");
        return order;
    }

    static Blueprint smallBlueprint(final String name)
    {
        final Blueprint blueprint = new Blueprint((short) 3, (short) 2, (short) 3).setName(name).setFileName(name).setPackName("Minecolonies Original");
        blueprint.addBlockState(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        blueprint.addBlockState(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
        blueprint.addBlockState(new BlockPos(1, 0, 1), Blocks.STONE.defaultBlockState());
        blueprint.setCachePrimaryOffset(BlockPos.ZERO);
        return blueprint;
    }

    static void claim(final BuildingBuilder hut, final WorkOrderDecoration order)
    {
        order.setClaimedBy(hut.getID());
        hut.setWorkOrder(order);
    }

    // ------------------------------------------------------------------ risk 4

    /**
     * A removed builder unclaims his order, the order keeps its progress for the next builder, and the old hut keeps no
     * stale progress for whatever order it gets next.
     */
    public static void unclaimMovesProgressWithOrder(final GameTestHelper helper)
    {
        final Fx f = fixture(helper, "C0 progress", true);
        final WorkOrderDecoration o1 = order(f, "o1", new BlockPos(30, 1, 10), 0);
        claim(f.a(), o1);
        f.a().setProgressPos(new BlockPos(1, 0, 1), BuildingProgressStage.BUILD_SOLID);
        helper.assertTrue(f.a().getProgress() != null, "hut A has no progress after setProgressPos");

        f.colony().getCitizenManager().removeCivilian(f.ca());
        helper.assertTrue(!o1.isClaimed(), "the order is still claimed by the hut whose builder was removed: " + o1.getClaimedBy());

        f.tick();
        helper.assertTrue(o1.getClaimedBy().equals(f.b().getID()), "builder B did not take over the unclaimed order: " + o1.getClaimedBy());
        final Tuple<BlockPos, BuildingProgressStage> moved = f.b().getProgress();
        helper.assertTrue(moved != null && moved.getA().equals(new BlockPos(1, 0, 1)) && moved.getB() == BuildingProgressStage.BUILD_SOLID,
          "progress did not move with the order to B: " + moved);

        hire(helper, f.colony(), f.a());
        final WorkOrderDecoration o2 = order(f, "o2", new BlockPos(30, 1, 20), 0);
        f.tick();
        helper.assertTrue(f.a().hasWorkOrder() && f.a().getWorkOrder().getID() == o2.getID(), "hut A did not get the second order: " + f.a().getWorkOrder());
        helper.assertTrue(f.a().getProgress() == null, "hut A resumed the second order at stale progress of the first: " + f.a().getProgress());
        helper.succeed();
    }

    // ------------------------------------------------------------------ risk 6

    /**
     * An order test double that can turn invalid and counts its removal callbacks.
     */
    static final class TestDecoration extends WorkOrderDecoration
    {
        boolean valid   = true;
        int     removed = 0;

        @Override
        public boolean isValid(final IColony colony)
        {
            return valid;
        }

        @Override
        public void onRemoved(final IColony colony)
        {
            removed++;
        }
    }

    /**
     * Invalid orders leave through removeWorkOrder: the claiming hut cancels its work and onRemoved runs.
     */
    public static void invalidOrderRemovedThroughOnRemoved(final GameTestHelper helper)
    {
        final Fx f = fixture(helper, "C0 invalid", false);
        final WorkOrderDecoration source = order(f, "inv", new BlockPos(30, 1, 10), 0);
        final CompoundTag tag = new CompoundTag();
        source.write(tag);
        f.wm().removeWorkOrder(source);
        final TestDecoration order = new TestDecoration();
        order.setColony(f.colony());
        order.read(tag, f.wm());
        order.setID(0);
        order.setBlueprint(smallBlueprint("inv"), f.level());
        f.wm().addWorkOrder(order, true);
        claim(f.a(), order);
        f.a().addNeededResource(new ItemStack(Items.STONE), 5);
        helper.assertTrue(!f.a().getNeededResources().isEmpty(), "fixture: hut A has no needed resources");

        order.valid = false;
        f.tick();

        helper.assertTrue(f.wm().getWorkOrder(order.getID()) == null, "the invalid order was not removed");
        helper.assertTrue(order.removed == 1, "onRemoved ran " + order.removed + " times for an order removed as invalid");
        helper.assertTrue(f.a().getNeededResources().isEmpty(), "the claiming hut kept the resources of a removed order");
        helper.succeed();
    }

    // ------------------------------------------------------------------ risk 7

    /**
     * A busy hut may queue an order only if it could build it, and a queued claim does not outlive the hut's builder.
     */
    public static void manualClaimChecksLevelAndReleasesWithoutWorker(final GameTestHelper helper)
    {
        final Fx f = fixture(helper, "C0 manual claim", true);
        final ServerPlayer player = MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper);
        final WorkOrderDecoration o1 = order(f, "m1", new BlockPos(30, 1, 10), 0);
        claim(f.a(), o1);

        final IBuilding warehouse = MinecoloniesGameTests.placeProductionBuilding(helper, f.colony(), ModBlocks.blockHutWareHouse, new BlockPos(24, 1, 2),
          "craftsmanship/storage/warehouse1.blueprint");
        warehouse.setBuildingLevel(2);
        final WorkOrderBuilding upgrade = WorkOrderBuilding.create(WorkOrderType.UPGRADE, warehouse);
        upgrade.setBlueprint(smallBlueprint("upgrade"), f.level());
        f.wm().addWorkOrder(upgrade, true);
        helper.assertTrue(upgrade.getTargetLevel() == 3, "fixture: upgrade order targets level " + upgrade.getTargetLevel());

        f.a().setWorkOrder(upgrade.getID(), player);
        helper.assertTrue(!upgrade.isClaimed(), "a level 1 hut queued a level 3 order it cannot build: " + upgrade.getClaimedBy());

        final WorkOrderDecoration o2 = order(f, "m2", new BlockPos(30, 1, 20), 0);
        f.a().setWorkOrder(o2.getID(), player);
        helper.assertTrue(o2.getClaimedBy().equals(f.a().getID()), "the busy hut could not queue an order it can build: " + o2.getClaimedBy());

        f.a().getModule(BuildingModules.BUILDER_WORK).removeCitizen(f.ca());
        f.tick();
        helper.assertTrue(!o2.getClaimedBy().equals(f.a().getID()), "the queued claim is stranded on a hut without a builder: " + o2.getClaimedBy());
        helper.succeed();
    }

    // ------------------------------------------------------------------ risk 5

    /**
     * Auto-assignment hands out the highest priority order first.
     */
    public static void assignmentFollowsPriority(final GameTestHelper helper)
    {
        final Fx f = fixture(helper, "C0 priority", false);
        final WorkOrderDecoration low = order(f, "low", new BlockPos(30, 1, 10), 1);
        final WorkOrderDecoration high = order(f, "high", new BlockPos(30, 1, 20), 5);
        f.tick();
        helper.assertTrue(f.a().hasWorkOrder() && f.a().getWorkOrder().getID() == high.getID(),
          "the only builder took order " + (f.a().getWorkOrder() == null ? null : f.a().getWorkOrder().getID()) + " instead of the priority 5 order " + high.getID()
            + " (low=" + low.getID() + ")");
        helper.succeed();
    }

    // ------------------------------------------------------------------ risk 9

    /**
     * The builder resources window must not show 100% for an order that costs nothing.
     */
    public static void zeroCostOrderProgress(final GameTestHelper helper)
    {
        final Fx f = fixture(helper, "C0 zero cost", false);
        final WorkOrderDecoration order = order(f, "zero", new BlockPos(30, 1, 10), 0);
        claim(f.a(), order);
        helper.assertTrue(order.getAmountOfResources() == 0, "fixture: order has resource cost " + order.getAmountOfResources());
        f.a().setTotalStages(6);
        for (int i = 0; i < 3; i++)
        {
            f.a().nextStage();
        }
        final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), f.level().registryAccess());
        f.a().getModule(BuildingModules.BUILDING_RESOURCES).serializeToView(buf);
        final BuildingResourcesModuleView view = new BuildingResourcesModuleView();
        view.deserialize(buf);
        helper.assertTrue(view.getWorkOrderId() == order.getID(), "view does not show the order: " + view.getWorkOrderId());
        helper.assertTrue(view.getProgress() == 50, "half way through the stages of a zero-cost order the window shows " + view.getProgress() + "%");
        helper.succeed();
    }

    // ------------------------------------------------------------------ risk 10

    /**
     * Materials delivered to the hut of a builder who lost his order are used by the next builder instead of being
     * requested again.
     */
    public static void reassignedOrderReusesMaterials(final GameTestHelper helper)
    {
        final Fx f = fixture(helper, "C0 handover", true);
        final WorkOrderDecoration order = order(f, "handover", new BlockPos(30, 1, 10), 0);
        claim(f.a(), order);
        final ItemStack stone = new ItemStack(Items.STONE, 10);
        f.a().addNeededResource(stone, 10);
        helper.assertTrue(InventoryUtils.forceItemStackToItemHandler(f.a().getItemHandlerCap(), stone.copy(), s -> true).isEmpty(), "fixture: stone did not fit hut A");

        f.colony().getCitizenManager().removeCivilian(f.ca());
        f.tick();
        helper.assertTrue(f.b().hasWorkOrder() && f.b().getWorkOrder().getID() == order.getID(), "builder B did not take over: " + f.b().getWorkOrder());

        f.b().addNeededResource(stone, 10);
        f.b().setProgressPos(new BlockPos(0, 0, 0), BuildingProgressStage.BUILD_SOLID);
        helper.assertTrue(f.b().getRequiredResources() != null, "fixture: B has no active bucket");
        f.b().checkOrRequestBucket(f.b().getRequiredResources(), f.cb());

        final java.util.List<IRequest<? extends Stack>> requests = f.b().getOpenRequestsOfType(-1, TypeToken.of(Stack.class));
        helper.assertTrue(requests.isEmpty(), "B requested the materials again although hut A holds them: " + requests);
        final int atB = InventoryUtils.getItemCountInItemHandler(f.b().getItemHandlerCap(), s -> ItemStackUtils.compareItemStacksIgnoreStackSize(s, stone));
        final int atA = InventoryUtils.getItemCountInItemHandler(f.a().getItemHandlerCap(), s -> ItemStackUtils.compareItemStacksIgnoreStackSize(s, stone));
        helper.assertTrue(atB == 10 && atA == 0, "stone after handover: hut B " + atB + ", hut A " + atA);
        helper.succeed();
    }

    // ------------------------------------------------------------------ risk 15

    /**
     * The never-filled fluids-to-remove state is no longer saved.
     */
    public static void deadFluidsStateNotPersisted(final GameTestHelper helper)
    {
        final Fx f = fixture(helper, "C0 fluids", false);
        final CompoundTag tag = f.a().serializeNBT(f.level().registryAccess());
        helper.assertTrue(!tag.contains(TAG_FLUIDS_REMOVE), "the builder hut still saves the dead fluidsToRemove state");
        helper.succeed();
    }

    @NotNull
    static BlockPos nullPos()
    {
        return NULL_POS;
    }
}
