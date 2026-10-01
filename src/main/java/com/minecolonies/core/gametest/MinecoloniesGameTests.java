package com.minecolonies.core.gametest;

import com.mojang.authlib.GameProfile;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.core.tileentities.TileEntityDecorationController;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.colony.buildings.modules.ICraftingBuildingModule;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.requestable.crafting.PublicCrafting;
import com.minecolonies.api.colony.requestsystem.resolver.IQueuedRequestResolver;
import com.minecolonies.api.colony.requestsystem.resolver.IRequestResolver;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.requestsystem.token.StandardToken;
import com.minecolonies.api.colony.requestsystem.requestable.Tool;
import com.minecolonies.api.equipment.ModEquipmentTypes;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.crafting.RecipeStorage;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import com.minecolonies.core.tileentities.TileEntityRack;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingDeliveryman;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingSawmill;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.utils.BuildingBuilderResource;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder;
import com.minecolonies.core.util.WorkerUtil;
import com.minecolonies.core.network.messages.server.CreateColonyMessage;
import com.minecolonies.core.placementhandlers.main.SuppliesHandler;
import com.minecolonies.core.placementhandlers.main.SurvivalHandler;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.storage.StructurePacks;
import com.ldtteam.structurize.util.BlockInfo;
import com.ldtteam.structurize.util.PlacementSettings;
import com.ldtteam.structurize.util.RotationMirror;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.api.util.EntityUtils;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.IItemHandlerCapProvider;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.WorldUtil;
import com.ldtteam.multipiston.TileEntityMultiPiston;
import com.ldtteam.multipiston.network.MultiPistonChangeMessage;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.util.ProblemReporter;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * End-to-end server-side checks for the core colony lifecycle.
 */
public final class MinecoloniesGameTests
{
    private MinecoloniesGameTests()
    {
    }

    public static void colonyLifecycle(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        // A server-only lifecycle test needs a player object but no client connection.
        final ServerPlayer player = FakePlayerFactory.getMinecraft(level);
        final BlockPos relativeTownHallPos = new BlockPos(1, 1, 1);
        final BlockPos townHallPos = helper.absolutePos(relativeTownHallPos);

        // The empty test environment has no terrain. Give the citizen spawn
        // search a small, deterministic walkable platform beside the hall.
        clearColonyFixture(helper, -1, 40, 40);
        for (int x = -8; x < 40; x++)
        {
            for (int z = -8; z < 40; z++)
            {
                helper.setBlock(new BlockPos(x, -1, z), Blocks.STONE.defaultBlockState());
            }
        }
        helper.setBlock(relativeTownHallPos, ModBlocks.blockHutTownHall.defaultBlockState());
        helper.assertBlockPresent(ModBlocks.blockHutTownHall, relativeTownHallPos);

        final BlockEntity blockEntity = level.getBlockEntity(townHallPos);
        helper.assertTrue(blockEntity instanceof TileEntityColonyBuilding,
          "town hall placement did not create a MineColonies building block entity");
        final TileEntityColonyBuilding townHall = (TileEntityColonyBuilding) blockEntity;
        townHall.setPackName("Minecolonies Original");
        townHall.setBlueprintPath("fundamentals/townhall1.blueprint");

        final IColony colony = IColonyManager.getInstance().createColony(
          level,
          townHallPos,
          player,
          "MC26.2 GameTest Colony",
          "Minecolonies Original");
        helper.assertTrue(colony != null, "colony creation returned null");

        final IBuilding building = colony.getServerBuildingManager().addNewBuilding(
          townHall,
          level);
        helper.assertTrue(building != null, "town hall building registration returned null");
        helper.assertTrue(colony.getServerBuildingManager().hasTownHall(),
          "registered colony does not expose a town hall");

        final BlockPos relativeSpawnAnchor = new BlockPos(3, 1, 3);
        final BlockPos spawnAnchor = helper.absolutePos(relativeSpawnAnchor);
        level.setChunkForced(townHallPos.getX() >> 4, townHallPos.getZ() >> 4, true);
        level.setChunkForced(spawnAnchor.getX() >> 4, spawnAnchor.getZ() >> 4, true);
        // GameTest structures receive their entity-ticking chunk tickets at
        // the end of the setup tick. Continue after that boundary so a fresh
        // universe cannot race the citizen manager's loaded-chunk guard.
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(WorldUtil.isEntityBlockLoaded(level, townHallPos),
              "town hall chunk is not entity-ticking: " + townHallPos);
            helper.assertTrue(WorldUtil.isEntityBlockLoaded(level, spawnAnchor),
              "spawn anchor chunk is not entity-ticking: " + spawnAnchor);
            helper.assertTrue(level.getBlockState(spawnAnchor).isAir(),
              "spawn anchor occupied: " + level.getBlockState(spawnAnchor));
            helper.assertTrue(level.getBlockState(spawnAnchor.above()).isAir(),
              "spawn anchor head occupied: " + level.getBlockState(spawnAnchor.above()));
            helper.assertTrue(!level.getBlockState(spawnAnchor.below()).isAir()
                || !level.getBlockState(spawnAnchor.below(2)).isAir(),
              "spawn anchor has no supporting platform: below=" + level.getBlockState(spawnAnchor.below())
                + ", below2=" + level.getBlockState(spawnAnchor.below(2)));
            helper.assertTrue(EntityUtils.getSpawnPoint(level, spawnAnchor) != null,
              "spawn-point search failed at anchor " + spawnAnchor);
            final ICitizenData citizen = colony.getCitizenManager().spawnOrCreateCivilian(
              null,
              level,
              List.of(townHallPos, spawnAnchor),
              true);
            helper.assertTrue(citizen != null, "citizen creation returned null");
            helper.assertTrue(colony.getCitizenManager().getCitizens().contains(citizen),
              "created citizen was not registered with the colony");

        // Place a second hut through the same block/entity path used by a
        // player, register it with the colony, and assign the live citizen to
        // its worker module. This exercises building placement and the job
        // assignment/network-facing colony state without relying on a client.
        final BlockPos relativeBuilderPos = new BlockPos(6, 1, 1);
        final BlockPos builderPos = helper.absolutePos(relativeBuilderPos);
        level.setChunkForced(builderPos.getX() >> 4, builderPos.getZ() >> 4, true);
        helper.assertTrue(WorldUtil.isBlockLoaded(level, builderPos),
          "builder chunk is not fully loaded: " + builderPos);
        helper.setBlock(relativeBuilderPos, ModBlocks.blockHutBuilder.defaultBlockState());
        helper.assertBlockPresent(ModBlocks.blockHutBuilder, relativeBuilderPos);
        final BlockEntity builderBlockEntity = level.getBlockEntity(builderPos);
        helper.assertTrue(builderBlockEntity instanceof TileEntityColonyBuilding,
          "builder placement did not create a MineColonies building block entity");
        final TileEntityColonyBuilding builderHut = (TileEntityColonyBuilding) builderBlockEntity;
        builderHut.setPackName("Minecolonies Original");
        builderHut.setBlueprintPath("fundamentals/builder1.blueprint");
        final IBuilding builder = colony.getServerBuildingManager().addNewBuilding(builderHut, level);
        helper.assertTrue(builder instanceof BuildingBuilder,
          "builder hut did not create a worker building: " + (builder == null ? "null" : builder.getClass().getName()));
        final BuildingBuilder builderBuilding = (BuildingBuilder) builder;
        helper.assertTrue(builderBuilding.getModule(BuildingModules.BUILDER_WORK).assignCitizen(citizen),
          "builder worker could not be assigned to the spawned citizen");
        helper.assertTrue(citizen.getWorkBuilding() == builder,
          "citizen work building was not synchronized after assignment");
        // The real builder AI must clear the pre-existing platform before it
        // places the schematic. Give it the same starter tool a level-zero
        // builder can use; materials themselves remain supplied by the test
        // resource policy below.
        citizen.getInventory().setStackInSlot(0, new ItemStack(Items.WOODEN_PICKAXE));
        citizen.getInventory().setStackInSlot(1, new ItemStack(Items.WOODEN_AXE));
        citizen.getInventory().setStackInSlot(2, new ItemStack(Items.WOODEN_SHOVEL));
        citizen.getInventory().setStackInSlot(3, new ItemStack(Items.WOODEN_HOE));
        citizen.getInventory().setStackInSlot(4, new ItemStack(Items.SHEARS));

        final WorkOrderBuilding workOrder = WorkOrderBuilding.create(WorkOrderType.BUILD, builder);
        colony.getWorkManager().addWorkOrder(workOrder, false);
        helper.assertTrue(workOrder.getID() > 0, "building work order did not receive an id");
        helper.assertTrue(colony.getWorkManager().getWorkOrder(workOrder.getID()) == workOrder,
          "building work order was not registered");
        workOrder.setClaimedBy(builderBuilding.getID());
        builderBuilding.setWorkOrder(workOrder);
        final boolean previousInfiniteBuilderResources = Constants.BUILDER_INF_RESOURECES;
        Constants.BUILDER_INF_RESOURECES = true;
        workOrder.setRequested(true);
        workOrder.loadBlueprint(level, ignored -> { });

        helper.assertTrue(citizen.getEntity().isPresent(), "spawned citizen has no live entity");
        helper.assertTrue(citizen.getEntity().get().getCitizenJobHandler().getWorkAI() != null,
          "assigned builder citizen has no worker AI");

        // Exercise the same NBT round-trip used by the persistent colony
        // manager.  This catches registry/component schema regressions that a
        // live in-memory colony would otherwise hide.
        final CompoundTag saved = colony.write(new CompoundTag(), level.registryAccess());
        final Colony restored = Colony.loadColony(saved, level, level.registryAccess());
        helper.assertTrue(restored != null, "colony NBT could not be loaded");
        helper.assertTrue(restored.getID() == colony.getID(), "colony id was not preserved across reload");
        helper.assertTrue(restored.getCenter().equals(colony.getCenter()), "colony center was not preserved across reload");
        helper.assertTrue(restored.getName().equals(colony.getName()), "colony name was not preserved across reload");
        helper.assertTrue(restored.getServerBuildingManager().hasTownHall(), "town hall was not preserved across reload");
        helper.assertTrue(restored.getCitizenManager().getCitizens().size() == colony.getCitizenManager().getCitizens().size(),
          "citizens were not preserved across reload");
        helper.assertTrue(restored.getServerBuildingManager().getBuildings().size() == colony.getServerBuildingManager().getBuildings().size(),
          "buildings were not preserved across reload");
        helper.assertTrue(restored.getWorkManager().getWorkOrders().size() == colony.getWorkManager().getWorkOrders().size(),
          "work orders were not preserved across reload");

        // Blueprint loading is queued by Structurize. Wait for it before
        // completing the test so the construction bounding box is proven to
        // be available, not merely that an order object was allocated.
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(workOrder.getBlueprint() != null, "builder work order blueprint did not load");
            helper.assertTrue(!workOrder.getBoundingBox().equals(com.minecolonies.api.util.constant.Constants.EMPTY_AABB),
              "builder work order did not calculate a construction area");
            helper.assertTrue(builderBuilding.getModule(BuildingModules.BUILDER_WORK).hasAssignedCitizen(),
              "builder worker assignment was lost before construction started");
            final Blueprint loadedBuilderBlueprint = workOrder.getBlueprint();
            helper.assertTrue(loadedBuilderBlueprint.getPrimaryBlockOffset() != null,
              "loaded builder schematic did not expose an anchor");
            final EntityAIStructureBuilder workerAI = (EntityAIStructureBuilder) citizen.getEntity().get().getCitizenJobHandler().getWorkAI();
            citizen.getEntity().ifPresent(entity -> {
                // Keep the synthetic worker on the flat test pad while the
                // production pathfinder evaluates the construction site.
                entity.setPos(builderBuilding.getID().getX() + 0.5D,
                  builderBuilding.getID().getY(),
                  builderBuilding.getID().getZ() + 0.5D);
            });
            // Let any earlier state-machine load attempt settle, then invoke
            // the production loader against the completed blueprint and
            // re-arm the request on the following server tick.
            helper.runAfterDelay(100, () -> {
                workerAI.loadStructure(workOrder, builderPos, false);
                helper.runAfterDelay(1, () -> workOrder.setRequested(true));
            });
        });
            helper.runAfterDelay(40000, () -> {
            try
            {
                final String workerState = citizen.getEntity()
                  .map(entity -> entity.getCitizenJobHandler().getWorkAI() == null
                    ? "no-worker-ai"
                    : entity.getCitizenJobHandler().getWorkAI().getStateAI().getState().toString())
                  .orElse("no-entity");
                final String workerHistory = citizen.getEntity()
                  .map(entity -> entity.getCitizenJobHandler().getWorkAI() == null
                    ? "no-worker-ai"
                    : entity.getCitizenJobHandler().getWorkAI().getStateAI().getHistory().getString())
                  .orElse("no-entity");
                final BlockPos progressLocal = builderBuilding.getProgress() == null ? null : builderBuilding.getProgress().getA();
                final BlockPos progressWorld = progressLocal == null || workOrder.getBlueprint() == null
                  ? null
                  : builderBuilding.getID().subtract(workOrder.getBlueprint().getPrimaryBlockOffset()).offset(progressLocal);
                final String progressState = progressWorld == null ? "null" : level.getBlockState(progressWorld).toString();
                final String heldItem = citizen.getEntity().map(entity -> entity.getMainHandItem().toString()).orElse("no-entity");
                helper.assertTrue(builderBuilding.getBuildingLevel() >= 1,
                  "builder worker did not complete the level-one construction; level=" + builderBuilding.getBuildingLevel()
                    + ", ai=" + workerState + ", workOrder=" + builderBuilding.getWorkOrder()
                    + ", requested=" + workOrder.isRequested() + ", claimedBy=" + workOrder.getClaimedBy()
                    + ", blueprint=" + (workOrder.getBlueprint() == null ? "null" : workOrder.getBlueprint().getFileName())
                    + ", primary=" + (workOrder.getBlueprint() == null ? "null" : workOrder.getBlueprint().getPrimaryBlockOffset())
                    + ", size=" + (workOrder.getBlueprint() == null ? "null" : workOrder.getBlueprint().getSizeX() + "x" + workOrder.getBlueprint().getSizeY() + "x" + workOrder.getBlueprint().getSizeZ())
                    + ", bbox=" + workOrder.getBoundingBox()
                    + ", progress=" + builderBuilding.getProgress()
                    + ", progressWorld=" + progressWorld + ", progressState=" + progressState + ", held=" + heldItem
                    + ", history=" + workerHistory
                    + ", citizenPos=" + citizen.getEntity().map(entity -> entity.blockPosition()).orElse(null));
                helper.assertTrue(!builderBuilding.hasWorkOrder(),
                  "completed construction still has a builder work order");
                helper.assertTrue(colony.getWorkManager().getWorkOrder(workOrder.getID()) == null,
                  "completed construction work order was not removed");
                helper.succeed();
            }
            finally
            {
                Constants.BUILDER_INF_RESOURECES = previousInfiniteBuilderResources;
            }
            });
        });
    }

    /**
     * Exercise the first half of a real survival workflow without opening a
     * client GUI: a survival player places a finite town-hall item, the
     * production founding packet creates the colony, and the production
     * survival blueprint handler places a finite builder-hut item.  This is
     * deliberately separate from {@link #colonyLifecycle(GameTestHelper)},
     * whose direct colony/AI calls are retained as a lower-level regression
     * fixture and are not evidence of an unassisted player workflow.
     */
    public static void survivalPlayerActions(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);

        // The 26.2 equipment bridge must preserve the old TieredItem
        // semantics before a real builder request is created.  This is the
        // smallest regression guard for the finite tool-delivery path below.
        final ItemStack woodenPickaxe = new ItemStack(Items.WOODEN_PICKAXE);
        helper.assertTrue(new Tool(ModEquipmentTypes.pickaxe.get(), 0, 1)
            .matches(woodenPickaxe),
          "level-zero pickaxe request does not match a vanilla wooden pickaxe"
            + ":tag=" + woodenPickaxe.is(ItemTags.PICKAXES)
            + ":materialTag=" + woodenPickaxe.is(ItemTags.WOODEN_TOOL_MATERIALS)
            + ":toolComponent=" + (woodenPickaxe.get(net.minecraft.core.component.DataComponents.TOOL) != null)
            + ":isEquipment=" + ModEquipmentTypes.pickaxe.get().checkIsEquipment(woodenPickaxe)
            + ":miningLevel=" + ModEquipmentTypes.pickaxe.get().getMiningLevel(woodenPickaxe)
            + ":compatLevel=" + com.minecolonies.api.compatibility.Compatibility.getItemLevel(woodenPickaxe));

        clearColonyFixture(helper, 0, 40, 40);
        for (int x = -8; x < 40; x++)
        {
            for (int z = -8; z < 40; z++)
            {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }

        final BlockPos relativeTownHallPos = new BlockPos(2, 1, 2);
        final BlockPos relativeTownHallSupport = relativeTownHallPos.below();
        final BlockPos townHallPos = helper.absolutePos(relativeTownHallPos);
        player.setPos(helper.absoluteVec(net.minecraft.world.phys.Vec3.atBottomCenterOf(new BlockPos(2, 2, 4))));

        final ItemStack townHallStack = new ItemStack(ModBlocks.blockHutTownHall, 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, townHallStack);
        helper.placeAt(player, townHallStack, relativeTownHallSupport, Direction.UP);
        helper.assertBlockPresent(ModBlocks.blockHutTownHall, relativeTownHallPos);
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(),
          "survival town-hall placement did not consume its finite item stack");
        helper.assertTrue(level.getBlockEntity(townHallPos) instanceof TileEntityColonyBuilding,
          "survival town-hall placement did not create its tile entity");

        new TestCreateColonyMessage(
          townHallPos,
          false,
          "P10 survival fixture colony",
          "Minecolonies Original",
          "fundamentals/townhall1.blueprint").execute(player);

        final IColony colony = IColonyManager.getInstance().getIColonyByOwner(level, player);
        helper.assertTrue(colony != null, "production founding packet did not create a colony");
        helper.assertTrue(colony.getServerBuildingManager().hasTownHall(),
          "production founding packet did not register the town hall");

        // A normal client keeps the colony chunk entity-ticking while it is
        // nearby.  GameTest does not create that player ticket automatically,
        // so preserve the same loaded-world precondition explicitly; without
        // it CitizenManager creates data but cannot add the live entity.
        level.setChunkForced(townHallPos.getX() >> 4, townHallPos.getZ() >> 4, true);

        final String packName = "Minecolonies Original";
        final String builderPath = "fundamentals/builder1.blueprint";
        final Blueprint builderBlueprint = StructurePacks.getBlueprintFuture(packName, builderPath).join();
        helper.assertTrue(builderBlueprint != null, "builder blueprint could not be loaded from the selected pack");

        final BlockPos relativeBuilderPos = new BlockPos(8, 1, 2);
        final BlockPos builderPos = helper.absolutePos(relativeBuilderPos);
        level.setChunkForced(builderPos.getX() >> 4, builderPos.getZ() >> 4, true);
        final ItemStack builderStack = new ItemStack(ModBlocks.blockHutBuilder, 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, builderStack);

        // This is the same production handler used after a player chooses a
        // survival blueprint in the build-tool UI.  Calling the common handler
        // directly is necessary in GameTest because the test server has no
        // client-side handler registration or screen to click.
        new SurvivalHandler().handle(
          builderBlueprint,
          packName,
          builderPath,
          false,
          level,
          player,
          builderPos,
          new PlacementSettings());

        helper.runAfterDelay(100, () -> {
            helper.assertBlockPresent(ModBlocks.blockHutBuilder, relativeBuilderPos);
            helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(),
              "survival builder placement did not consume its finite item stack");
            final IBuilding builder = IColonyManager.getInstance().getBuilding(level, builderPos);
            helper.assertTrue(builder instanceof BuildingBuilder,
              "survival builder placement did not register a builder building: " + builder);
            helper.assertTrue(packName.equals(builder.getStructurePack()),
              "survival builder placement stored the wrong structure pack: " + builder.getStructurePack());
            helper.assertTrue(builderPath.equals(builder.getBlueprintPath()),
              "survival builder placement stored the wrong blueprint path: " + builder.getBlueprintPath());
            // Builder pickup uses the same rack locations that a normal
            // colony deliveryman uses.  Keep one real rack in the fixture so
            // the survival cycle proves the production rack round-trip and
            // the worker's normal material pickup path together.
            final BlockPos relativeBuilderRackPos = new BlockPos(20, 1, 2);
            final BlockPos builderRackPos = helper.absolutePos(relativeBuilderRackPos);
            level.setChunkForced(builderRackPos.getX() >> 4, builderRackPos.getZ() >> 4, true);
            helper.setBlock(relativeBuilderRackPos, ModBlocks.blockRack.defaultBlockState());
            helper.assertBlockPresent(ModBlocks.blockRack, relativeBuilderRackPos);
            final BlockEntity builderRackEntity = level.getBlockEntity(builderRackPos);
            helper.assertTrue(builderRackEntity instanceof TileEntityRack,
              "survival builder rack fixture did not create its rack block entity: " + builderRackEntity);
            ((BuildingBuilder) builder).addContainerPosition(builderRackPos);
            // Do not manufacture a citizen here.  The normal colony tick is
            // responsible for moving the first colonist in after the town
            // hall has been founded.  The production timer runs in 500-tick
            // colony intervals, so allow two intervals plus a safety margin.
            helper.runAfterDelay(1_200, () -> {
                helper.assertTrue(!colony.getCitizenManager().getCitizens().isEmpty(),
                  "the founded colony did not create its initial survival citizen; closeSubscribers="
                    + colony.getPackageManager().getCloseSubscribers().size()
                    + ", importantSubscribers=" + colony.getPackageManager().getImportantColonyPlayers().size()
                    + ", playerAlive=" + player.isAlive()
                    + ", playerLevel=" + (player.level() == level)
                    + ", playerChunkLoaded=" + WorldUtil.isChunkLoaded(level, player.chunkPosition())
                    + ", playerPos=" + player.blockPosition());
                final ICitizenData citizen = colony.getCitizenManager().getCitizens().getFirst();
                helper.assertTrue(citizen.getEntity().isPresent(),
                  "the initial survival citizen was not spawned into the loaded test world");
                final BuildingBuilder builderBuilding = (BuildingBuilder) builder;
                final var builderWork = builderBuilding.getModule(BuildingModules.BUILDER_WORK);
                final boolean assigned = builderWork.getAssignedCitizen().contains(citizen) || builderWork.assignCitizen(citizen);
                helper.assertTrue(assigned,
                  "the initial survival citizen could not be assigned to the placed builder hut; job="
                    + citizen.getJob() + ", workBuilding=" + citizen.getWorkBuilding()
                    + ", moduleFull=" + builderWork.isFull());
                helper.assertTrue(citizen.getWorkBuilding() == builder,
                  "builder assignment did not update the citizen's work building");

                // Request the first builder-hut construction through the
                // production building API.  This is the same server-side
                // operation triggered by the normal builder screen; the test
                // does not allocate a WorkOrderBuilding or call the worker AI
                // directly.
                builderBuilding.requestUpgrade(player, builderPos);
                helper.assertTrue(colony.getWorkManager().getWorkOrdersOfType(WorkOrderBuilding.class).stream()
                    .anyMatch(order -> builderPos.equals(order.getLocation())),
                  "the assigned builder did not accept the survival upgrade request");

                // WorkManager assigns claimed orders on its next slow colony
                // tick.  Leave enough time for that tick and for Structurize
                // to finish decoding the blueprint before inspecting the
                // finite resource plan.
                helper.runAfterDelay(700, () -> {
                    helper.assertTrue(builderBuilding.hasWorkOrder(),
                      "the claimed survival work order was not attached to the builder after a colony tick");
                    final WorkOrderBuilding workOrder = (WorkOrderBuilding) builderBuilding.getWorkOrder();
                    helper.assertTrue(workOrder.getBlueprint() != null,
                      "the survival builder work order did not load its blueprint");
                    helper.assertTrue(!builderBuilding.getNeededResources().isEmpty(),
                      "the survival builder did not calculate a finite resource plan");
                    final int levelBeforeSupply = builderBuilding.getBuildingLevel();
                    helper.runAfterDelay(200, () -> {
                        helper.assertTrue(builderBuilding.getBuildingLevel() == levelBeforeSupply,
                          "the builder advanced without any supplied construction resources");
                        helper.assertTrue(builderBuilding.hasWorkOrder(),
                          "the builder discarded its unsatisfied work order while resources were withheld");

                        // Feed the builder through the building's normal
                        // server-side delivery insertion path.  This keeps
                        // the fixture finite and preserves block components;
                        // it is intentionally not the old infinite-resource
                        // test flag and does not invoke completion directly.
                        final int[] delivered = {0};
                        final int[] pumpTicks = {0};
                        final Runnable[] pump = new Runnable[1];
                        pump[0] = () -> {
                            // Keep the bounded fixture in daylight, like the
                            // courier and restart tests.  It has no beds, so
                            // the normal night transition parks the builder in
                            // its sleep routine for ~12k ticks; whether the
                            // 30k-tick budget then covers the build depends on
                            // where in the job night falls (X-263-SLABSTALL).
                            level.getServer().clockManager().setTotalTicks(
                              level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
                            deliverFiniteBuilderResources(builderBuilding, citizen, level, delivered);
                            if (builderBuilding.getBuildingLevel() >= 1)
                            {
                                helper.assertTrue(delivered[0] > 0,
                                  "builder completed without accepting any finite delivered resources");
                                helper.assertTrue(!builderBuilding.hasWorkOrder(),
                                  "completed survival construction still has a builder work order");
                                helper.assertTrue(colony.getWorkManager().getWorkOrders().values().stream()
                                    .noneMatch(order -> builderPos.equals(order.getLocation())),
                                  "completed survival construction left its work order in the colony manager");
                                helper.succeed();
                            }
                            else if (pumpTicks[0] >= 30_000)
                            {
                              final BlockPos progressLocal = builderBuilding.getProgress() == null ? null : builderBuilding.getProgress().getA();
                              final BlockPos progressWorld = progressLocal == null || builderBuilding.getWorkOrder() == null
                                || builderBuilding.getWorkOrder().getBlueprint() == null
                                ? null
                                : builderBuilding.getID().subtract(builderBuilding.getWorkOrder().getBlueprint().getPrimaryBlockOffset()).offset(progressLocal);
                              final String progressState = progressWorld == null ? "null" : level.getBlockState(progressWorld).toString();
                              final String miningTool = progressWorld == null ? "null" : WorkerUtil.getBestToolForBlock(
                                level.getBlockState(progressWorld),
                                level.getBlockState(progressWorld).getDestroySpeed(level, progressWorld),
                                builderBuilding,
                                level,
                                progressWorld).getRegistryName().toString();
                              final int miningLevel = progressWorld == null ? -1 : WorkerUtil.getCorrectHarvestLevelForBlock(level.getBlockState(progressWorld));
                              helper.assertTrue(false,
                                "finite-resource survival construction did not complete; delivered=" + delivered[0]
                                  + ", level=" + builderBuilding.getBuildingLevel()
                                  + ", needed=" + builderBuilding.getNeededResources()
                                  + ", workOrder=" + builderBuilding.getWorkOrder()
                                  + ", requested=" + builderBuilding.getWorkOrder().isRequested()
                                  + ", openCitizenRequests=" + builderBuilding.getOpenRequests(citizen.getId()).stream()
                                    .map(request -> describeRequest(builderBuilding, request))
                                    .toList()
                                  + ", openBuildingRequests=" + builderBuilding.getOpenRequests(-1).stream()
                                    .map(request -> describeRequest(builderBuilding, request))
                                    .toList()
                                  + ", playerResolverRequests=" + builderBuilding.getColony().getRequestManager().getPlayerResolver().getAllAssignedRequests()
                                  + ", retryingResolverRequests=" + builderBuilding.getColony().getRequestManager().getRetryingRequestResolver().getAllAssignedRequests()
                                  + ", buildingResolvers=" + builderBuilding.getResolvers().stream()
                                    .map(resolver -> resolver.getClass().getSimpleName() + ":" + resolver.getId()
                                      + ":assigned=" + (resolver instanceof IQueuedRequestResolver queued
                                        ? queued.getAllAssignedRequests() : "unsupported"))
                                    .toList()
                                  + ", toolMatches=" + describeToolMatches(builderBuilding, citizen)
                                  + ", citizenInventory=" + describeInventory(citizen.getInventory())
                                  + ", buildingInventory=" + describeInventory(builderBuilding.getItemHandlerCap())
                                  + ", blueprint=" + builderBuilding.getWorkOrder().getBlueprint()
                                  + ", progress=" + builderBuilding.getProgress()
                                  + ", progressWorld=" + progressWorld
                                  + ", progressState=" + progressState
                                  + ", miningTool=" + miningTool
                                  + ", miningLevel=" + miningLevel
                                  + ", workerHeld=" + citizen.getEntity().map(entity -> entity.getMainHandItem()).orElse(ItemStack.EMPTY)
                                  + ", citizenSlots=" + citizen.getInventory().getSlots()
                                  + ", citizenHasSpace=" + citizen.getInventory().hasSpace()
                                  + ", citizenFull=" + citizen.getInventory().isFull()
                                  + ", workerState=" + citizen.getEntity().map(entity -> entity.getCitizenJobHandler().getWorkAI() == null
                                    ? "no-ai"
                                    : entity.getCitizenJobHandler().getWorkAI().getStateAI().getState()).orElse("no-entity")
                                  + ", workerHistory=" + citizen.getEntity().map(entity -> entity.getCitizenJobHandler().getWorkAI() == null
                                    ? "no-ai"
                                    : entity.getCitizenJobHandler().getWorkAI().getStateAI().getHistory().getString()).orElse("no-entity")
                                  + ", citizenPos=" + citizen.getEntity().map(entity -> entity.blockPosition()).orElse(null));
                            }
                            else
                            {
                                pumpTicks[0] += 200;
                                if (pumpTicks[0] % 2_000 == 0)
                                {
                                    Log.getLogger().info("P10B finite progress ticks={} level={} progress={} state={} citizenInventory={} buildingInventory={}",
                                      pumpTicks[0],
                                      builderBuilding.getBuildingLevel(),
                                      builderBuilding.getProgress(),
                                      citizen.getEntity().map(entity -> entity.getCitizenJobHandler().getWorkAI() == null
                                        ? "no-ai"
                                        : entity.getCitizenJobHandler().getWorkAI().getStateAI().getState()).orElse("no-entity"),
                                      describeInventory(citizen.getInventory()),
                                      describeInventory(builderBuilding.getItemHandlerCap()));
                                }
                                // Schedule a fresh Runnable instance.  The
                                // GameTest callback map keys by Runnable; if
                                // the same instance is reinserted while its
                                // entry is being removed, the next pump is
                                // silently removed as well.
                                helper.runAfterDelay(200, () -> pump[0].run());
                            }
                        };
                        helper.runAfterDelay(1, pump[0]);
                    });
                });
            });
        });
    }

    /**
     * Exercise the production-to-courier request chain with finite inventories.
     *
     * <p>The target is a real builder building with an open oak-plank request.
     * A real sawmill worker receives one oak log, crafts oak planks through a
     * registered recipe, and the warehouse's assigned courier must transport
     * the produced stack to the builder.  No request is overruled and no
     * infinite-resource setting is enabled in this fixture.</p>
     */
    public static void productionCourierBuilder(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);

        clearColonyFixture(helper, 0, 60, 32);
        for (int x = -8; x < 60; x++)
        {
            for (int z = -8; z < 32; z++)
            {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }

        final BlockPos relativeTownHall = new BlockPos(2, 1, 2);
        final BlockPos townHallPos = helper.absolutePos(relativeTownHall);
        helper.setBlock(relativeTownHall, ModBlocks.blockHutTownHall.defaultBlockState());
        helper.assertBlockPresent(ModBlocks.blockHutTownHall, relativeTownHall);
        final BlockEntity townHallEntity = level.getBlockEntity(townHallPos);
        helper.assertTrue(townHallEntity instanceof TileEntityColonyBuilding,
          "production fixture town hall did not create a building block entity: " + townHallEntity);
        final TileEntityColonyBuilding townHallHut = (TileEntityColonyBuilding) townHallEntity;
        townHallHut.setPackName("Minecolonies Original");
        townHallHut.setBlueprintPath("fundamentals/townhall1.blueprint");

        final IColony colony = IColonyManager.getInstance().createColony(
          level,
          townHallPos,
          player,
          "P10C production courier colony",
          "Minecolonies Original");
        helper.assertTrue(colony != null, "production fixture colony creation returned null");
        final IBuilding townHall = colony.getServerBuildingManager().addNewBuilding(townHallHut, level);
        helper.assertTrue(townHall != null && colony.getServerBuildingManager().hasTownHall(),
          "production fixture did not register the town hall");
        // A level-one hall gives the fixture enough citizen capacity for the
        // sawmill worker, builder worker, and courier used below.
        townHall.setBuildingLevel(1);

        final IBuilding builder = placeProductionBuilding(
          helper,
          colony,
          ModBlocks.blockHutBuilder,
          new BlockPos(10, 1, 2),
          "fundamentals/builder1.blueprint");
        final IBuilding sawmill = placeProductionBuilding(
          helper,
          colony,
          ModBlocks.blockHutSawmill,
          new BlockPos(20, 1, 2),
          "craftsmanship/carpentry/sawmill1.blueprint");
        final IBuilding warehouse = placeProductionBuilding(
          helper,
          colony,
          ModBlocks.blockHutWareHouse,
          new BlockPos(30, 1, 2),
          "craftsmanship/storage/warehouse1.blueprint");
        final IBuilding deliveryman = placeProductionBuilding(
          helper,
          colony,
          ModBlocks.blockHutDeliveryman,
          new BlockPos(40, 1, 2),
          "craftsmanship/storage/deliveryman1.blueprint");

        final BlockPos builderAnchor = helper.absolutePos(new BlockPos(12, 1, 8));
        final BlockPos sawmillAnchor = helper.absolutePos(new BlockPos(22, 1, 8));
        final BlockPos courierAnchor = helper.absolutePos(new BlockPos(42, 1, 8));
        // A worker is allowed to wander while its job AI is idle.  Force the
        // complete building volumes, rather than just the spawn anchors, so
        // a walk across a chunk boundary cannot stop ticking the entity before
        // the assigned request is observed.
        for (final IBuilding building : List.of(townHall, builder, sawmill, warehouse, deliveryman))
        {
            forceBuildingChunks(level, building);
        }
        for (final BlockPos anchor : List.of(builderAnchor, sawmillAnchor, courierAnchor))
        {
            level.setChunkForced(anchor.getX() >> 4, anchor.getZ() >> 4, true);
        }

        helper.runAfterDelay(200, () -> {
            for (final IBuilding building : List.of(townHall, builder, sawmill, warehouse, deliveryman))
            {
                forceBuildingChunks(level, building);
            }
            for (final BlockPos anchor : List.of(builderAnchor, sawmillAnchor, courierAnchor))
            {
                level.setChunkForced(anchor.getX() >> 4, anchor.getZ() >> 4, true);
            }
            for (final BlockPos anchor : List.of(builderAnchor, sawmillAnchor, courierAnchor))
            {
                helper.assertTrue(WorldUtil.isEntityBlockLoaded(level, anchor),
                  "production fixture citizen anchor is not entity-ticking: " + anchor);
                helper.assertTrue(level.getBlockState(anchor).isAir() && level.getBlockState(anchor.above()).isAir(),
                  "production fixture citizen anchor is occupied: " + anchor);
                helper.assertTrue(EntityUtils.getSpawnPoint(level, anchor) != null,
                  "production fixture citizen anchor has no valid spawn point: " + anchor);
            }
            final ICitizenData builderCitizen = colony.getCitizenManager().spawnOrCreateCivilian(
              null, level, List.of(builderAnchor), true);
            final ICitizenData sawmillCitizen = colony.getCitizenManager().spawnOrCreateCivilian(
              null, level, List.of(sawmillAnchor), true);
            final ICitizenData courierCitizen = colony.getCitizenManager().spawnOrCreateCivilian(
              null, level, List.of(courierAnchor), true);
            helper.assertTrue(builderCitizen != null && sawmillCitizen != null && courierCitizen != null,
              "production fixture could not create all workers: citizens=" + colony.getCitizenManager().getCitizens().size());
            helper.assertTrue(builderCitizen.getEntity().isPresent()
                && sawmillCitizen.getEntity().isPresent()
                && courierCitizen.getEntity().isPresent(),
              "production fixture workers did not spawn into the loaded world");

            final var builderWork = builder.getModule(BuildingModules.BUILDER_WORK);
            final var sawmillWork = sawmill.getModule(BuildingModules.SAWMILL_WORK);
            final var courierWork = deliveryman.getModule(BuildingModules.COURIER_WORK);
            final var warehouseCouriers = warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS);
            helper.assertTrue(builderWork.assignCitizen(builderCitizen),
              "builder worker assignment failed in production fixture");
            helper.assertTrue(sawmillWork.assignCitizen(sawmillCitizen),
              "sawmill worker assignment failed in production fixture");
            helper.assertTrue(courierWork.assignCitizen(courierCitizen),
              "delivery hut worker assignment failed in production fixture");
            helper.assertTrue(warehouseCouriers.assignCitizen(courierCitizen),
              "warehouse courier assignment failed in production fixture");

            builderCitizen.getEntity().ifPresent(entity -> entity.setPos(builder.getID().getX() + 0.5D, builder.getID().getY(), builder.getID().getZ() + 0.5D));
            sawmillCitizen.getEntity().ifPresent(entity -> entity.setPos(sawmill.getID().getX() + 0.5D, sawmill.getID().getY(), sawmill.getID().getZ() + 0.5D));
            courierCitizen.getEntity().ifPresent(entity -> entity.setPos(deliveryman.getID().getX() + 0.5D, deliveryman.getID().getY(), deliveryman.getID().getZ() + 0.5D));

            final ICraftingBuildingModule sawmillCraft = sawmill.getModule(BuildingModules.SAWMILL_CRAFT);
            final RecipeStorage recipe = RecipeStorage.builder()
              .withRecipeId(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "gametest/oak_planks"))
              .withInputs(List.of(new ItemStorage(new ItemStack(Items.OAK_LOG, 1))))
              .withPrimaryOutput(new ItemStack(Items.OAK_PLANKS, 4))
              .build();
            final IToken<?> recipeToken = IColonyManager.getInstance().getRecipeManager().checkOrAddRecipe(recipe);
            helper.assertTrue(sawmillCraft.addRecipe(recipeToken),
              "sawmill rejected the compatible GameTest oak-plank recipe");

            final IToken<?> targetRequestToken = builder.createRequest(
              new Stack(new ItemStack(Items.OAK_PLANKS, 1), 1, 1),
              true);
            final IRequest<?> targetRequest = colony.getRequestManager().getRequestForToken(targetRequestToken);
            helper.assertTrue(targetRequest != null && targetRequest.getState() != RequestState.FAILED,
              "builder oak-plank request was not registered: " + targetRequest);

            final com.ldtteam.structurize.api.compat.itemhandler.IItemHandler sawmillInventory = sawmill.getItemHandlerCap();
            final com.ldtteam.structurize.api.compat.itemhandler.IItemHandler builderInventory = builder.getItemHandlerCap();
            helper.assertTrue(sawmillInventory != null && builderInventory != null,
              "production fixture building inventory handler is unavailable: sawmill=" + sawmillInventory
                + ", builder=" + builderInventory);
            final ItemStack inputRemainder = InventoryUtils.forceItemStackToItemHandler(
              sawmillInventory,
              new ItemStack(Items.OAK_LOG, 1),
              stack -> true);
            helper.assertTrue(inputRemainder.isEmpty(),
              "production fixture could not insert its finite oak-log input: " + inputRemainder);
            Log.getLogger().info("P10C setup sawmill={} corners={} containers={} workTags={} inputChest={} buildingOakLogs={} handler={} citizen={}",
              sawmill.getID(),
              sawmill.getCorners(),
              sawmill.getContainers(),
              sawmill.getLocationsFromTag("work"),
              sawmill.getTileEntity() == null ? "no-tile" : sawmill.getTileEntity().getPositionOfChestWithItemStack(stack -> stack.is(Items.OAK_LOG)),
              InventoryUtils.getCountFromBuilding(sawmill, stack -> stack.is(Items.OAK_LOG)),
              describeInventory(sawmillInventory),
              describeCrafterCitizen(sawmillCitizen, sawmill));

            final int[] pollTicks = {0};
            final int[] previousSawmillLogCount = {InventoryUtils.getCountFromBuilding(sawmill, stack -> stack.is(Items.OAK_LOG))};
            final boolean[] sawmillInputConsumed = {false};
            final boolean[] productionObserved = {false};
            final boolean[] parentDeliveryObserved = {false};
            final boolean[] parentCompletedObserved = {false};
            final boolean[] courierDeliveryObserved = {false};
            final boolean[] constructionArmed = {false};
            final boolean[] constructionObserved = {false};
            final int[] constructionBaselineBuilderPlanks = {-1};
            final WorkOrderBuilding[] constructionOrder = {null};
            final BlockPos constructionTarget = builder.getID().offset(0, 0, 10);
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                // Keep the bounded fixture in daylight.  It has no beds, so
                // allowing the normal night transition would move the three
                // workers into FIND_BED before the request chain can finish.
                level.getServer().clockManager().setTotalTicks(
                  level.registryAccess().getOrThrow(WorldClocks.OVERWORLD),
                  6000L);
                final IRequest<?> current = colony.getRequestManager().getRequestForToken(targetRequestToken);
                final int sawmillLogCount = InventoryUtils.getCountFromBuilding(sawmill, stack -> stack.is(Items.OAK_LOG));
                final int builderPlanks = InventoryUtils.getItemCountInItemHandler(
                  builderInventory,
                  stack -> ItemStackUtils.compareItemStacksIgnoreStackSize(stack, new ItemStack(Items.OAK_PLANKS), true, true));
                final String courierDescription = describeCrafterCitizen(courierCitizen, deliveryman);
                final int parentDeliveredPlanks = targetRequest.getDeliveries().stream()
                  .filter(stack -> stack.is(Items.OAK_PLANKS))
                  .mapToInt(ItemStack::getCount)
                  .sum();
                sawmillInputConsumed[0] |= previousSawmillLogCount[0] > 0 && sawmillLogCount == 0;
                parentDeliveryObserved[0] |= parentDeliveredPlanks >= 4;
                parentCompletedObserved[0] |= targetRequest.getState() == RequestState.COMPLETED
                  || targetRequest.getState() == RequestState.RECEIVED;
                courierDeliveryObserved[0] |= courierDescription.contains("PREPARE_DELIVERY")
                  || courierDescription.contains("DELIVERY:");
                // PublicWorkerCraftingProductionResolver copies the
                // completed crafter output into the parent request.  The
                // retained parent request is authoritative even after the
                // request manager removes its terminal token.
                productionObserved[0] |= sawmillInputConsumed[0] && parentDeliveryObserved[0];
                previousSawmillLogCount[0] = sawmillLogCount;

                if (pollTicks[0] % 500 == 0)
                {
                    Log.getLogger().info("P10C production/courier ticks={} request={} retainedRequestState={} parentDeliveries={} inputConsumed={} produced={} parentDelivery={} courierDelivery={} builderPlanks={} constructionArmed={} constructionObserved={} constructionTarget={} constructionBlock={} constructionOrder={} sawmillLogs={} sawmillInventory={} builderInventory={} warehouseQueue={} sawmillCitizen={} courierCitizen={}",
                      pollTicks[0],
                      current == null ? "null" : current.getState(),
                      targetRequest.getState(),
                      targetRequest.getDeliveries(),
                      sawmillInputConsumed[0],
                      productionObserved[0],
                      parentDeliveryObserved[0],
                      courierDeliveryObserved[0],
                      builderPlanks,
                      constructionArmed[0],
                      constructionObserved[0],
                      constructionTarget,
                      level.getBlockState(constructionTarget),
                      constructionOrder[0] == null ? "null" : constructionOrder[0].getID(),
                      sawmillLogCount,
                      describeInventory(sawmillInventory),
                      describeInventory(builderInventory),
                      warehouse.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE).getMutableRequestList(),
                      describeCrafterCitizen(sawmillCitizen, sawmill),
                      courierDescription);
                }

                if (!constructionArmed[0]
                    && parentCompletedObserved[0]
                    && sawmillInputConsumed[0]
                    && sawmillLogCount == 0
                    && productionObserved[0]
                    && parentDeliveryObserved[0]
                    && courierDeliveryObserved[0]
                    && builderPlanks == 4
                    && warehouse.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE).getMutableRequestList().isEmpty())
                {
                    helper.assertTrue(level.getBlockState(constructionTarget).isAir(),
                      "bounded construction target is not empty before builder work order: "
                        + constructionTarget + " state=" + level.getBlockState(constructionTarget));
                    final BuildingBuilder builderBuilding = (BuildingBuilder) builder;
                    builderBuilding.resetNeededResources();
                    final WorkOrderBuilding workOrder = WorkOrderBuilding.create(WorkOrderType.BUILD, builder);
                    colony.getWorkManager().addWorkOrder(workOrder, false);
                    helper.assertTrue(workOrder.getID() > 0,
                      "bounded builder work order did not receive an id");
                    workOrder.setClaimedBy(builder.getID());
                    builderBuilding.setWorkOrder(workOrder);
                    final Blueprint boundedBlueprint = new Blueprint((short) 1, (short) 1, (short) 11)
                      .setName("P10C bounded oak plank construction")
                      .setFileName("p10c_bounded_oak_plank")
                      .setPackName("Minecolonies Original");
                    boundedBlueprint.addBlockState(new BlockPos(0, 0, 10), Blocks.OAK_PLANKS.defaultBlockState());
                    boundedBlueprint.setCachePrimaryOffset(BlockPos.ZERO);
                    workOrder.setBlueprint(boundedBlueprint, level);
                    workOrder.setRequested(false);
                    workOrder.setCleared(false);
                    constructionOrder[0] = workOrder;
                    constructionBaselineBuilderPlanks[0] = builderPlanks;
                    constructionArmed[0] = true;
                    Log.getLogger().info("P10C armed bounded builder construction order={} target={} blueprintSize={}x{}x{} baselineBuilderPlanks={} workOrder={}",
                      workOrder.getID(),
                      constructionTarget,
                      boundedBlueprint.getSizeX(),
                      boundedBlueprint.getSizeY(),
                      boundedBlueprint.getSizeZ(),
                      constructionBaselineBuilderPlanks[0],
                      workOrder);
                }

                constructionObserved[0] |= constructionArmed[0]
                  && level.getBlockState(constructionTarget).is(Blocks.OAK_PLANKS)
                  && constructionBaselineBuilderPlanks[0] >= 1
                  && builderPlanks < constructionBaselineBuilderPlanks[0];

                if (constructionObserved[0]
                    && constructionOrder[0] != null
                    && !((BuildingBuilder) builder).hasWorkOrder()
                    && colony.getWorkManager().getWorkOrder(constructionOrder[0].getID()) == null)
                {
                    helper.assertTrue(sawmillCitizen.getJob() != null && courierCitizen.getJob() != null,
                      "production/courier citizens lost their jobs after delivery");
                    helper.assertTrue(builderCitizen.getJob() != null,
                      "builder citizen lost its job after bounded construction");
                    helper.assertTrue(builderPlanks == constructionBaselineBuilderPlanks[0] - 1,
                      "bounded builder consumed an unexpected amount of oak planks: before="
                        + constructionBaselineBuilderPlanks[0] + ", after=" + builderPlanks);
                    helper.succeed();
                }
                else if (pollTicks[0] >= 40_000)
                {
                    helper.assertTrue(false,
                      "production/courier request did not complete; request=" + current
                        + ", retainedRequestState=" + targetRequest.getState()
                        + ", parentDeliveries=" + targetRequest.getDeliveries()
                        + ", sawmillLogs=" + sawmillLogCount
                        + ", inputConsumed=" + sawmillInputConsumed[0]
                        + ", produced=" + productionObserved[0]
                        + ", parentDelivery=" + parentDeliveryObserved[0]
                        + ", courierDelivery=" + courierDeliveryObserved[0]
                        + ", builderPlanks=" + builderPlanks
                        + ", constructionArmed=" + constructionArmed[0]
                        + ", constructionObserved=" + constructionObserved[0]
                        + ", constructionTarget=" + constructionTarget
                        + ", constructionBlock=" + level.getBlockState(constructionTarget)
                        + ", constructionOrder=" + constructionOrder[0]
                        + ", sawmillInventory=" + describeInventory(sawmillInventory)
                        + ", builderInventory=" + describeInventory(builderInventory)
                        + ", warehouseQueue=" + warehouse.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE).getMutableRequestList()
                        + ", builderWorkOrder=" + ((BuildingBuilder) builder).getWorkOrder()
                        + ", builderProgress=" + ((BuildingBuilder) builder).getProgress()
                        + ", sawmill=" + describeCrafterCitizen(sawmillCitizen, sawmill)
                        + ", courier=" + courierDescription);
                }
                else
                {
                    pollTicks[0] += 100;
                    helper.runAfterDelay(100, () -> poll[0].run());
                }
            };
            helper.runAfterDelay(100, poll[0]);
        });
    }

    /**
     * Phase A of the production/courier restart fixture.  This deliberately
     * stops after the real courier has delivered the finite output and the
     * builder work order is active, then saves through the server's normal
     * persistence path before the GameTest process exits.
     */
    public static void productionCourierBuilderRestartPrepare(final GameTestHelper helper)
    {
        final Path markerPath = restartMarkerPath();
        helper.assertTrue(markerPath != null,
          "P10C restart prepare requires MINECOLONIES_RESTART_MARKER to name an external marker file");
        setupRestartProductionFixture(helper, fixture -> {
            final ServerLevel level = fixture.level();
            final IColony colony = fixture.colony();
            final BuildingBuilder builder = (BuildingBuilder) fixture.builder();
            final int[] pollTicks = {0};
            final int[] previousSawmillLogCount = {InventoryUtils.getCountFromBuilding(
              fixture.sawmill(), stack -> stack.is(Items.OAK_LOG))};
            final boolean[] sawmillInputConsumed = {false};
            final boolean[] parentDeliveryObserved = {false};
            final boolean[] parentCompletedObserved = {false};
            final boolean[] courierDeliveryObserved = {false};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                level.getServer().clockManager().setTotalTicks(
                  level.registryAccess().getOrThrow(WorldClocks.OVERWORLD),
                  6000L);
                final IRequest<?> current = colony.getRequestManager().getRequestForToken(fixture.targetRequestToken());
                final int sawmillLogCount = InventoryUtils.getCountFromBuilding(
                  fixture.sawmill(), stack -> stack.is(Items.OAK_LOG));
                final int builderPlanks = InventoryUtils.getItemCountInItemHandler(
                  fixture.builderInventory(),
                  stack -> ItemStackUtils.compareItemStacksIgnoreStackSize(
                    stack, new ItemStack(Items.OAK_PLANKS), true, true));
                final String courierDescription = describeCrafterCitizen(
                  fixture.courierCitizen(), fixture.deliveryman());
                final int parentDeliveredPlanks = fixture.targetRequest().getDeliveries().stream()
                  .filter(stack -> stack.is(Items.OAK_PLANKS))
                  .mapToInt(ItemStack::getCount)
                  .sum();
                sawmillInputConsumed[0] |= previousSawmillLogCount[0] > 0 && sawmillLogCount == 0;
                parentDeliveryObserved[0] |= parentDeliveredPlanks >= 4;
                parentCompletedObserved[0] |= fixture.targetRequest().getState() == RequestState.COMPLETED
                  || fixture.targetRequest().getState() == RequestState.RECEIVED;
                courierDeliveryObserved[0] |= courierDescription.contains("PREPARE_DELIVERY")
                  || courierDescription.contains("DELIVERY:");
                previousSawmillLogCount[0] = sawmillLogCount;

                if (pollTicks[0] % 500 == 0)
                {
                    Log.getLogger().info("P10C restart prepare ticks={} request={} retainedRequestState={} parentDeliveries={} inputConsumed={} parentDelivery={} courierDelivery={} builderPlanks={} sawmillLogs={} builderInventory={} sawmillCitizen={} courierCitizen={}",
                      pollTicks[0],
                      current == null ? "null" : current.getState(),
                      fixture.targetRequest().getState(),
                      fixture.targetRequest().getDeliveries(),
                      sawmillInputConsumed[0],
                      parentDeliveryObserved[0],
                      courierDeliveryObserved[0],
                      builderPlanks,
                      sawmillLogCount,
                      describeInventory(fixture.builderInventory()),
                      describeCrafterCitizen(fixture.sawmillCitizen(), fixture.sawmill()),
                      courierDescription);
                }

                if (parentCompletedObserved[0]
                    && sawmillInputConsumed[0]
                    && sawmillLogCount == 0
                    && parentDeliveryObserved[0]
                    && courierDeliveryObserved[0]
                    && builderPlanks == 4
                    && fixture.warehouse().getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE)
                      .getMutableRequestList().isEmpty())
                {
                    final BlockPos constructionTarget = fixture.constructionTarget();
                    helper.assertTrue(level.getBlockState(constructionTarget).isAir(),
                      "restart prepare target is not empty before arming the work order: " + constructionTarget
                        + " state=" + level.getBlockState(constructionTarget));
                    builder.resetNeededResources();
                    /*
                     * WorkOrderBuilding validity is tied to a registered building at
                     * its location.  Keep the bounded real blueprint, but anchor the
                     * persisted order at the builder building so it survives the first
                     * post-load colony validation tick.
                     */
                    final BlockPos structureLocation = builder.getID();
                    final WorkOrderBuilding workOrder = createRestartWorkOrder(builder, structureLocation);
                    colony.getWorkManager().addWorkOrder(workOrder, false);
                    helper.assertTrue(workOrder.getID() > 0,
                      "restart prepare work order did not receive an id");
                    workOrder.setClaimedBy(builder.getID());
                    builder.setWorkOrder(workOrder);
                    final Blueprint persistedBlueprint = StructurePacks.getBlueprint(
                      "Minecolonies Original", "infrastructure/roads/lampposts/simple.blueprint");
                    helper.assertTrue(persistedBlueprint != null,
                      "restart prepare could not load the persisted simple lamppost blueprint");
                    helper.assertTrue(persistedBlueprint.getPrimaryBlockOffset().equals(new BlockPos(1, 0, 0)),
                      "restart prepare loaded an unexpected simple lamppost primary offset: "
                        + persistedBlueprint.getPrimaryBlockOffset());
                    final BlockPos fenceTarget = firstBlueprintBlockPosition(
                      persistedBlueprint, structureLocation, Blocks.OAK_FENCE);
                    final BlockPos lanternTarget = firstBlueprintBlockPosition(
                      persistedBlueprint, structureLocation, Blocks.LANTERN);
                    helper.assertTrue(fenceTarget != null && lanternTarget != null,
                      "restart prepare could not identify the persisted lamppost targets: fence="
                        + fenceTarget + ", lantern=" + lanternTarget);
                    helper.assertTrue(level.getBlockState(fenceTarget).isAir() && level.getBlockState(lanternTarget).isAir(),
                      "restart prepare lamppost targets are not empty: fence=" + level.getBlockState(fenceTarget)
                        + ", lantern=" + level.getBlockState(lanternTarget));
                    workOrder.setBlueprint(persistedBlueprint, level);
                    workOrder.setRequested(false);
                    workOrder.setCleared(false);

                    /*
                     * Keep the post-restart material flow normal: the builder only
                     * carries the four planks delivered by the production chain.
                     * Put the remaining finite lamppost inputs in the registered
                     * warehouse so phase B must create ordinary builder requests
                     * and let the real courier deliver them.
                     */
                    final ItemStack dirt = new ItemStack(Blocks.DIRT.asItem(), 1);
                    final ItemStack oakFence = new ItemStack(Blocks.OAK_FENCE.asItem(), 5);
                    final ItemStack lantern = new ItemStack(Blocks.LANTERN.asItem(), 1);
                    helper.assertTrue(InventoryUtils.forceItemStackToItemHandler(
                      fixture.warehouse().getItemHandlerCap(), dirt, stack -> true).isEmpty(),
                      "restart prepare could not stage the finite dirt source in the warehouse");
                    helper.assertTrue(InventoryUtils.forceItemStackToItemHandler(
                      fixture.warehouse().getItemHandlerCap(), oakFence, stack -> true).isEmpty(),
                      "restart prepare could not stage the finite oak-fence source in the warehouse");
                    helper.assertTrue(InventoryUtils.forceItemStackToItemHandler(
                      fixture.warehouse().getItemHandlerCap(), lantern, stack -> true).isEmpty(),
                      "restart prepare could not stage the finite lantern source in the warehouse");

                    final RestartMarker marker = new RestartMarker(
                      level.dimension().identifier().toString(),
                      colony.getID(),
                      fixture.player().getUUID().toString(),
                      fixture.townHall().getID(),
                      fixture.builder().getID(),
                      fixture.sawmill().getID(),
                      fixture.warehouse().getID(),
                      fixture.deliveryman().getID(),
                      fixture.builderCitizen().getId(),
                      fixture.sawmillCitizen().getId(),
                      fixture.courierCitizen().getId(),
                      workOrder.getID(),
                      fixture.targetRequestToken().getIdentifier().toString(),
                      fixture.townHallPos(),
                      fixture.builderAnchor(),
                      fixture.sawmillAnchor(),
                      fixture.courierAnchor(),
                      structureLocation,
                      fenceTarget,
                      lanternTarget,
                      builderPlanks,
                      InventoryUtils.getItemCountInItemHandler(
                        fixture.builderInventory(), stack -> stack.is(Blocks.OAK_FENCE.asItem())),
                      InventoryUtils.getItemCountInItemHandler(
                        fixture.builderInventory(), stack -> stack.is(Blocks.LANTERN.asItem())),
                      fixture.targetRequest().getState(),
                      fixture.targetRequest().getDeliveries());
                    final boolean saveResult = level.getServer().saveEverything(false, true, true);
                    helper.assertTrue(saveResult, "P10C restart prepare server saveEverything returned false");
                    /*
                     * Since 26.x GameTestInfo#succeed discards every non-player entity inside the test bounds, and the
                     * shutdown save that follows then writes the citizens' entity chunks back empty. Snapshot the world
                     * right after the flushed save instead, which is exactly what a real server restart would load.
                     */
                    final Path savedWorld = restartSavedWorldPath(markerPath);
                    try
                    {
                        copySavedWorld(level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT), savedWorld);
                    }
                    catch (final IOException exception)
                    {
                        helper.assertTrue(false,
                          "P10C restart prepare could not snapshot the saved world to " + savedWorld + ": " + exception);
                    }
                    try
                    {
                        writeRestartMarker(markerPath, marker);
                    }
                    catch (final IOException exception)
                    {
                        helper.assertTrue(false,
                          "P10C restart prepare could not write marker " + markerPath + ": " + exception);
                    }
                    Log.getLogger().info("P10C restart prepare saved universe marker={} colony={} workOrder={} request={} builderPlanks={} parentRequestState={} parentDeliveries={}",
                      markerPath,
                      colony.getID(),
                      workOrder.getID(),
                      fixture.targetRequestToken().getIdentifier(),
                      builderPlanks,
                      fixture.targetRequest().getState(),
                      fixture.targetRequest().getDeliveries());
                    helper.succeed();
                }
                else if (pollTicks[0] >= 40_000)
                {
                    helper.assertTrue(false,
                      "P10C restart prepare did not reach the active work-order boundary; request=" + current
                        + ", retainedRequestState=" + fixture.targetRequest().getState()
                        + ", parentDeliveries=" + fixture.targetRequest().getDeliveries()
                        + ", sawmillLogs=" + sawmillLogCount
                        + ", inputConsumed=" + sawmillInputConsumed[0]
                        + ", parentDelivery=" + parentDeliveryObserved[0]
                        + ", courierDelivery=" + courierDeliveryObserved[0]
                        + ", builderPlanks=" + builderPlanks
                        + ", target=" + fixture.constructionTarget()
                        + ", targetState=" + level.getBlockState(fixture.constructionTarget())
                        + ", builderInventory=" + describeInventory(fixture.builderInventory())
                        + ", sawmill=" + describeCrafterCitizen(fixture.sawmillCitizen(), fixture.sawmill())
                        + ", courier=" + courierDescription);
                }
                else
                {
                    pollTicks[0] += 100;
                    helper.runAfterDelay(100, () -> poll[0].run());
                }
            };
            helper.runAfterDelay(100, poll[0]);
        });
    }

    /**
     * Phase B of the production/courier restart fixture.  This is invoked by
     * a new GameTestServer process against the universe saved by phase A.
     */
    public static void productionCourierBuilderRestartResume(final GameTestHelper helper)
    {
        final Path markerPath = restartMarkerPath();
        helper.assertTrue(markerPath != null,
          "P10C restart resume requires MINECOLONIES_RESTART_MARKER to name the phase-A marker file");
        final RestartMarker marker;
        try
        {
            marker = readRestartMarker(markerPath);
        }
        catch (final IOException | IllegalArgumentException exception)
        {
            helper.assertTrue(false, "P10C restart resume could not read marker " + markerPath + ": " + exception);
            return;
        }
        final ServerLevel level = helper.getLevel();
        helper.assertTrue(level.dimension().identifier().toString().equals(marker.dimension()),
          "P10C restart resume loaded an unexpected dimension: " + level.dimension().identifier());
        for (final BlockPos pos : List.of(marker.townHallPos(), marker.builderPos(), marker.sawmillPos(),
          marker.warehousePos(), marker.deliverymanPos(), marker.builderAnchor(), marker.sawmillAnchor(),
          marker.courierAnchor(), marker.workOrderLocation(), marker.constructionTarget(), marker.secondaryConstructionTarget()))
        {
            level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
        }

        helper.runAfterDelay(100, () -> {
            final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, marker.townHallPos());
            helper.assertTrue(colony != null,
              "P10C restart resume did not load the saved colony at " + marker.townHallPos());
            helper.assertTrue(colony.getID() == marker.colonyId(),
              "P10C restart resume loaded the wrong colony id: expected=" + marker.colonyId()
                + ", actual=" + colony.getID());

            // Reconnect the same player identity after the fresh process has
            // loaded the saved colony. This uses the same supported embedded
            // play transport as the login fixture, but preserves the phase-A
            // UUID so ownership and login synchronization are exercised across
            // the process boundary instead of creating a new owner.
            final ServerPlayer reconnectedPlayer;
            try
            {
                reconnectedPlayer = makeConnectedSurvivalPlayer(
                  helper, UUID.fromString(marker.playerUuid()));
            }
            catch (final IllegalArgumentException exception)
            {
                helper.assertTrue(false, "P10C restart resume saved an invalid player UUID: " + marker.playerUuid());
                return;
            }
            helper.assertTrue(reconnectedPlayer.getUUID().toString().equals(marker.playerUuid()),
              "P10C restart resume connected a different player UUID: expected=" + marker.playerUuid()
                + ", actual=" + reconnectedPlayer.getUUID());
            final IColony ownerColony = IColonyManager.getInstance().getIColonyByOwner(level, reconnectedPlayer);
            helper.assertTrue(ownerColony != null && ownerColony.getID() == marker.colonyId(),
              "P10C restart resume did not preserve the reconnecting player's colony ownership: player="
                + reconnectedPlayer.getUUID() + ", ownerColony=" + ownerColony);
            helper.assertTrue(level.getServer().getPlayerList().getPlayer(reconnectedPlayer.getUUID()) == reconnectedPlayer,
              "P10C restart resume did not register the reconnecting player in PlayerList");
            Log.getLogger().info("P10C restart resume networked reconnect passed playerUuid={} colony={}",
              reconnectedPlayer.getUUID(), ownerColony.getID());
            final IBuilding builderBuilding = colony.getServerBuildingManager().getBuilding(marker.builderPos());
            final IBuilding sawmill = colony.getServerBuildingManager().getBuilding(marker.sawmillPos());
            final IBuilding warehouse = colony.getServerBuildingManager().getBuilding(marker.warehousePos());
            final IBuilding deliveryman = colony.getServerBuildingManager().getBuilding(marker.deliverymanPos());
            helper.assertTrue(builderBuilding instanceof BuildingBuilder,
              "P10C restart resume did not restore the builder building: " + builderBuilding);
            helper.assertTrue(sawmill instanceof BuildingSawmill && warehouse instanceof BuildingWareHouse
                && deliveryman instanceof BuildingDeliveryman,
              "P10C restart resume did not restore all worker buildings: sawmill=" + sawmill
                + ", warehouse=" + warehouse + ", deliveryman=" + deliveryman);
            helper.assertTrue(colony.getServerBuildingManager().getBuilding(marker.townHallPos()) != null,
              "P10C restart resume did not restore the town hall");

            final ICitizenData builderCitizen = colony.getCitizenManager().getCivilian(marker.builderCitizenId());
            final ICitizenData sawmillCitizen = colony.getCitizenManager().getCivilian(marker.sawmillCitizenId());
            final ICitizenData courierCitizen = colony.getCitizenManager().getCivilian(marker.courierCitizenId());
            helper.assertTrue(builderCitizen != null && sawmillCitizen != null && courierCitizen != null,
              "P10C restart resume did not restore all citizens: citizens="
                + colony.getCitizenManager().getCivilianDataMap().keySet());
            helper.assertTrue(builderCitizen.getJob() != null && sawmillCitizen.getJob() != null
                && courierCitizen.getJob() != null,
              "P10C restart resume restored citizens without their jobs: builder=" + builderCitizen.getJob()
                + ", sawmill=" + sawmillCitizen.getJob() + ", courier=" + courierCitizen.getJob());

            final BuildingBuilder builder = (BuildingBuilder) builderBuilding;
            final var loadedOrder = colony.getWorkManager().getWorkOrder(marker.workOrderId());
            Log.getLogger().info("P10C restart resume loaded order diagnostics id={} loadedOrder={} claimedBy={} builderId={} builderHasWorkOrder={} colonyState={} managerOrders={}",
              marker.workOrderId(), loadedOrder,
              loadedOrder == null ? "null" : loadedOrder.getClaimedBy(), builder.getID(), builder.hasWorkOrder(),
              colony.getState(), colony.getWorkManager().getWorkOrders().keySet());
            final IBuilderWorkOrder restoredOrder = builder.getWorkOrder();
            helper.assertTrue(restoredOrder != null && restoredOrder.getID() == marker.workOrderId(),
              "P10C restart resume did not restore the active builder work order: expected="
                + marker.workOrderId() + ", actual=" + restoredOrder);
            helper.assertTrue("infrastructure/roads/lampposts/simple.blueprint".equals(restoredOrder.getStructurePath()),
              "P10C restart resume restored the wrong structure path: " + restoredOrder.getStructurePath());
            helper.assertTrue(restoredOrder.getLocation().equals(marker.workOrderLocation()),
              "P10C restart resume restored the wrong structure location: expected="
                + marker.workOrderLocation() + ", actual=" + restoredOrder.getLocation());
            helper.assertTrue(colony.getWorkManager().getWorkOrder(marker.workOrderId()) != null,
              "P10C restart resume work manager lost the active order " + marker.workOrderId());
            final com.ldtteam.structurize.api.compat.itemhandler.IItemHandler builderInventory = builder.getItemHandlerCap();
            final com.ldtteam.structurize.api.compat.itemhandler.IItemHandler sawmillInventory = sawmill.getItemHandlerCap();
            final int builderPlanks = InventoryUtils.getItemCountInItemHandler(
              builderInventory,
              stack -> ItemStackUtils.compareItemStacksIgnoreStackSize(
                stack, new ItemStack(Items.OAK_PLANKS), true, true));
            final int builderOakFences = InventoryUtils.getItemCountInItemHandler(
              builderInventory,
              stack -> stack.is(Blocks.OAK_FENCE.asItem()));
            final int builderLanterns = InventoryUtils.getItemCountInItemHandler(
              builderInventory,
              stack -> stack.is(Blocks.LANTERN.asItem()));
            final int sawmillLogs = InventoryUtils.getCountFromBuilding(sawmill, stack -> stack.is(Items.OAK_LOG));
            helper.assertTrue(builderPlanks == marker.builderPlanksBeforeRestart(),
              "P10C restart resume changed builder inventory across save/load: expected="
                + marker.builderPlanksBeforeRestart() + ", actual=" + builderPlanks
                + ", inventory=" + describeInventory(builderInventory));
            helper.assertTrue(builderOakFences >= marker.builderOakFencesBeforeRestart()
                && builderLanterns >= marker.builderLanternsBeforeRestart(),
              "P10C restart resume lost persisted lamppost materials across save/load: fences=" + builderOakFences
                + ", lanterns=" + builderLanterns + ", inventory=" + describeInventory(builderInventory));
            helper.assertTrue(sawmillLogs == 0,
              "P10C restart resume resurrected consumed sawmill input: logs=" + sawmillLogs
                + ", inventory=" + describeInventory(sawmillInventory));
            final IRequest<?> restoredRequest = colony.getRequestManager().getRequestForToken(
              new StandardToken(UUID.fromString(marker.requestUuid())));
            helper.assertTrue(restoredRequest == null || restoredRequest.getState() != RequestState.FAILED,
              "P10C restart resume lost or failed the saved parent request: " + restoredRequest);
            helper.assertTrue(marker.parentRequestState() == RequestState.COMPLETED
                || marker.parentRequestState() == RequestState.RECEIVED
                || marker.parentRequestState() == RequestState.OVERRULED,
              "P10C restart prepare did not save a terminal parent request state: " + marker.parentRequestState());
            helper.assertTrue(marker.parentDeliveries().stream().mapToInt(ItemStack::getCount).sum() >= 4,
              "P10C restart prepare did not save the four-plank parent delivery: " + marker.parentDeliveries());

            helper.assertTrue(level.getBlockState(marker.constructionTarget()).isAir()
                && level.getBlockState(marker.secondaryConstructionTarget()).isAir(),
              "P10C restart resume targets were changed before normal continuation: fence="
                + level.getBlockState(marker.constructionTarget()) + ", lantern="
                + level.getBlockState(marker.secondaryConstructionTarget()));

            final int[] pollTicks = {0};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                level.getServer().clockManager().setTotalTicks(
                  level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
                final int currentBuilderPlanks = InventoryUtils.getItemCountInItemHandler(
                  builderInventory,
                  stack -> ItemStackUtils.compareItemStacksIgnoreStackSize(
                    stack, new ItemStack(Items.OAK_PLANKS), true, true));
                final boolean targetBuilt = level.getBlockState(marker.constructionTarget()).is(Blocks.OAK_FENCE)
                  && level.getBlockState(marker.secondaryConstructionTarget()).is(Blocks.LANTERN);
                final int currentBuilderOakFences = InventoryUtils.getItemCountInItemHandler(
                  builderInventory,
                  stack -> stack.is(Blocks.OAK_FENCE.asItem()));
                final int currentBuilderLanterns = InventoryUtils.getItemCountInItemHandler(
                  builderInventory,
                  stack -> stack.is(Blocks.LANTERN.asItem()));
                final boolean orderCleared = !builder.hasWorkOrder()
                  && colony.getWorkManager().getWorkOrder(marker.workOrderId()) == null;
                if (pollTicks[0] % 500 == 0)
                {
                    Log.getLogger().info("P10C restart resume ticks={} fenceTarget={} fenceState={} lanternTarget={} lanternState={} builderPlanks={} builderFences={} builderLanterns={} order={} managerOrder={} builder={} sawmill={} courier={}",
                      pollTicks[0], marker.constructionTarget(), level.getBlockState(marker.constructionTarget()),
                      marker.secondaryConstructionTarget(), level.getBlockState(marker.secondaryConstructionTarget()),
                      currentBuilderPlanks, currentBuilderOakFences, currentBuilderLanterns, builder.getWorkOrder(),
                      colony.getWorkManager().getWorkOrder(marker.workOrderId()),
                      describeCrafterCitizen(builderCitizen, builder),
                      describeCrafterCitizen(sawmillCitizen, sawmill),
                      describeCrafterCitizen(courierCitizen, deliveryman));
                    Log.getLogger().info("P10C restart resume requiredResources={}", builder.getNeededResources());
                }
                if (targetBuilt
                    && currentBuilderPlanks == marker.builderPlanksBeforeRestart()
                    && currentBuilderOakFences == 0
                    && currentBuilderLanterns == 0
                    && orderCleared)
                {
                    helper.assertTrue(builderCitizen.getJob() != null && sawmillCitizen.getJob() != null
                        && courierCitizen.getJob() != null,
                      "P10C restart resume lost a worker job after construction");
                    Log.getLogger().info("P10C restart resume continuation passed: target={} lantern={} builderPlanks={} builderFences={} builderLanterns={} parentRequest={}",
                      level.getBlockState(marker.constructionTarget()),
                      level.getBlockState(marker.secondaryConstructionTarget()),
                      currentBuilderPlanks, currentBuilderOakFences, currentBuilderLanterns, restoredRequest);
                    helper.succeed();
                }
                else if (pollTicks[0] >= 30_000)
                {
                    helper.assertTrue(false,
                      "P10C restart resume did not complete the saved work order; target="
                        + level.getBlockState(marker.constructionTarget())
                        + ", lanternTarget=" + marker.secondaryConstructionTarget()
                        + ", lanternState=" + level.getBlockState(marker.secondaryConstructionTarget())
                        + ", builderPlanks=" + currentBuilderPlanks
                        + ", builderFences=" + currentBuilderOakFences
                        + ", builderLanterns=" + currentBuilderLanterns
                        + ", builderInventory=" + describeInventory(builderInventory)
                        + ", builderWorkOrder=" + builder.getWorkOrder()
                        + ", managerOrder=" + colony.getWorkManager().getWorkOrder(marker.workOrderId())
                        + ", builder=" + describeCrafterCitizen(builderCitizen, builder)
                        + ", sawmill=" + describeCrafterCitizen(sawmillCitizen, sawmill)
                        + ", courier=" + describeCrafterCitizen(courierCitizen, deliveryman));
                }
                else
                {
                    pollTicks[0] += 100;
                    helper.runAfterDelay(100, () -> poll[0].run());
                }
            };
            helper.runAfterDelay(100, poll[0]);
        });
    }

    private static void setupRestartProductionFixture(
      final GameTestHelper helper,
      final Consumer<RestartProductionFixture> continuation)
    {
        final ServerLevel level = helper.getLevel();
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
        for (int x = -8; x < 60; x++)
        {
            for (int z = -8; z < 32; z++)
            {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }

        final BlockPos relativeTownHall = new BlockPos(2, 1, 2);
        final BlockPos townHallPos = helper.absolutePos(relativeTownHall);
        helper.setBlock(relativeTownHall, ModBlocks.blockHutTownHall.defaultBlockState());
        helper.assertBlockPresent(ModBlocks.blockHutTownHall, relativeTownHall);
        final BlockEntity townHallEntity = level.getBlockEntity(townHallPos);
        helper.assertTrue(townHallEntity instanceof TileEntityColonyBuilding,
          "restart fixture town hall did not create a building block entity: " + townHallEntity);
        final TileEntityColonyBuilding townHallHut = (TileEntityColonyBuilding) townHallEntity;
        townHallHut.setPackName("Minecolonies Original");
        townHallHut.setBlueprintPath("fundamentals/townhall1.blueprint");
        final IColony colony = IColonyManager.getInstance().createColony(
          level, townHallPos, player, "P10C restart production courier colony", "Minecolonies Original");
        helper.assertTrue(colony != null, "restart fixture colony creation returned null");
        final IBuilding townHall = colony.getServerBuildingManager().addNewBuilding(townHallHut, level);
        helper.assertTrue(townHall != null && colony.getServerBuildingManager().hasTownHall(),
          "restart fixture did not register the town hall");
        townHall.setBuildingLevel(1);

        final IBuilding builder = placeProductionBuilding(helper, colony, ModBlocks.blockHutBuilder,
          new BlockPos(10, 1, 2), "fundamentals/builder1.blueprint");
        final IBuilding sawmill = placeProductionBuilding(helper, colony, ModBlocks.blockHutSawmill,
          new BlockPos(20, 1, 2), "craftsmanship/carpentry/sawmill1.blueprint");
        final IBuilding warehouse = placeProductionBuilding(helper, colony, ModBlocks.blockHutWareHouse,
          new BlockPos(30, 1, 2), "craftsmanship/storage/warehouse1.blueprint");
        final IBuilding deliveryman = placeProductionBuilding(helper, colony, ModBlocks.blockHutDeliveryman,
          new BlockPos(40, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");
        final BlockPos builderAnchor = helper.absolutePos(new BlockPos(12, 1, 8));
        final BlockPos sawmillAnchor = helper.absolutePos(new BlockPos(22, 1, 8));
        final BlockPos courierAnchor = helper.absolutePos(new BlockPos(42, 1, 8));
        for (final IBuilding building : List.of(townHall, builder, sawmill, warehouse, deliveryman))
        {
            forceBuildingChunks(level, building);
        }
        for (final BlockPos anchor : List.of(builderAnchor, sawmillAnchor, courierAnchor))
        {
            level.setChunkForced(anchor.getX() >> 4, anchor.getZ() >> 4, true);
        }

        helper.runAfterDelay(100, () -> {
            for (final BlockPos anchor : List.of(builderAnchor, sawmillAnchor, courierAnchor))
            {
                helper.assertTrue(WorldUtil.isEntityBlockLoaded(level, anchor),
                  "restart fixture citizen anchor is not entity-ticking: " + anchor);
                helper.assertTrue(level.getBlockState(anchor).isAir() && level.getBlockState(anchor.above()).isAir(),
                  "restart fixture citizen anchor is occupied: " + anchor);
                helper.assertTrue(EntityUtils.getSpawnPoint(level, anchor) != null,
                  "restart fixture citizen anchor has no valid spawn point: " + anchor);
            }
            final ICitizenData builderCitizen = colony.getCitizenManager().spawnOrCreateCivilian(
              null, level, List.of(builderAnchor), true);
            final ICitizenData sawmillCitizen = colony.getCitizenManager().spawnOrCreateCivilian(
              null, level, List.of(sawmillAnchor), true);
            final ICitizenData courierCitizen = colony.getCitizenManager().spawnOrCreateCivilian(
              null, level, List.of(courierAnchor), true);
            helper.assertTrue(builderCitizen != null && sawmillCitizen != null && courierCitizen != null,
              "restart fixture could not create all workers");
            helper.assertTrue(builderCitizen.getEntity().isPresent() && sawmillCitizen.getEntity().isPresent()
                && courierCitizen.getEntity().isPresent(),
              "restart fixture workers did not spawn into the loaded world");
            final var builderWork = builder.getModule(BuildingModules.BUILDER_WORK);
            final var sawmillWork = sawmill.getModule(BuildingModules.SAWMILL_WORK);
            final var courierWork = deliveryman.getModule(BuildingModules.COURIER_WORK);
            final var warehouseCouriers = warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS);
            helper.assertTrue(builderWork.assignCitizen(builderCitizen), "restart builder assignment failed");
            helper.assertTrue(sawmillWork.assignCitizen(sawmillCitizen), "restart sawmill assignment failed");
            helper.assertTrue(courierWork.assignCitizen(courierCitizen), "restart courier assignment failed");
            helper.assertTrue(warehouseCouriers.assignCitizen(courierCitizen),
              "restart warehouse courier assignment failed");
            builderCitizen.getEntity().ifPresent(entity -> entity.setPos(builder.getID().getX() + 0.5D,
              builder.getID().getY(), builder.getID().getZ() + 0.5D));
            sawmillCitizen.getEntity().ifPresent(entity -> entity.setPos(sawmill.getID().getX() + 0.5D,
              sawmill.getID().getY(), sawmill.getID().getZ() + 0.5D));
            courierCitizen.getEntity().ifPresent(entity -> entity.setPos(deliveryman.getID().getX() + 0.5D,
              deliveryman.getID().getY(), deliveryman.getID().getZ() + 0.5D));

            final ICraftingBuildingModule sawmillCraft = sawmill.getModule(BuildingModules.SAWMILL_CRAFT);
            final RecipeStorage recipe = RecipeStorage.builder()
              .withRecipeId(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "gametest/oak_planks_restart"))
              .withInputs(List.of(new ItemStorage(new ItemStack(Items.OAK_LOG, 1))))
              .withPrimaryOutput(new ItemStack(Items.OAK_PLANKS, 4))
              .build();
            final IToken<?> recipeToken = IColonyManager.getInstance().getRecipeManager().checkOrAddRecipe(recipe);
            helper.assertTrue(sawmillCraft.addRecipe(recipeToken),
              "restart sawmill rejected the compatible oak-plank recipe");
            final IToken<?> targetRequestToken = builder.createRequest(
              new Stack(new ItemStack(Items.OAK_PLANKS, 1), 1, 1), true);
            final IRequest<?> targetRequest = colony.getRequestManager().getRequestForToken(targetRequestToken);
            helper.assertTrue(targetRequest != null && targetRequest.getState() != RequestState.FAILED,
              "restart builder request was not registered: " + targetRequest);
            final com.ldtteam.structurize.api.compat.itemhandler.IItemHandler sawmillInventory = sawmill.getItemHandlerCap();
            final com.ldtteam.structurize.api.compat.itemhandler.IItemHandler builderInventory = builder.getItemHandlerCap();
            helper.assertTrue(sawmillInventory != null && builderInventory != null,
              "restart fixture building inventory handler is unavailable");
            final ItemStack remainder = InventoryUtils.forceItemStackToItemHandler(
              sawmillInventory, new ItemStack(Items.OAK_LOG, 1), stack -> true);
            helper.assertTrue(remainder.isEmpty(), "restart fixture could not insert oak log: " + remainder);
            continuation.accept(new RestartProductionFixture(level, player, colony, townHall, builder, sawmill, warehouse,
              deliveryman, builderCitizen, sawmillCitizen, courierCitizen, targetRequestToken, targetRequest,
              townHallPos, builderAnchor, sawmillAnchor, courierAnchor, builder.getID().offset(0, 0, 10),
              builderInventory, sawmillInventory));
        });
    }

    /**
     * Creates the bounded restart fixture order with a stable, real blueprint
     * path.  WorkOrderBuilding.create() derives its path from the builder hut
     * (builder1.blueprint), which would make a fresh-process resume load the
     * entire hut instead of the small persisted lamppost fixture.  The production class keeps
     * its full constructor private, so this test-only fixture uses the same
     * constructor through reflection rather than adding a production setter
     * solely for test state.
     */
    private static WorkOrderBuilding createRestartWorkOrder(
      final BuildingBuilder builder,
      final BlockPos structureLocation)
    {
        try
        {
            final var constructor = WorkOrderBuilding.class.getDeclaredConstructor(
              String.class,
              String.class,
              String.class,
              WorkOrderType.class,
              BlockPos.class,
              RotationMirror.class,
              int.class,
              int.class);
            constructor.setAccessible(true);
            final WorkOrderBuilding workOrder = constructor.newInstance(
              "Minecolonies Original",
              "infrastructure/roads/lampposts/simple.blueprint",
              builder.getBuildingType().getTranslationKey(),
              WorkOrderType.BUILD,
              structureLocation,
              builder.getTileEntity() == null ? builder.getRotationMirror() : builder.getTileEntity().getRotationMirror(),
              builder.getBuildingLevel(),
              1);
            workOrder.setCustomName(builder);
            return workOrder;
        }
        catch (final ReflectiveOperationException exception)
        {
            throw new IllegalStateException("Could not create the bounded restart work order fixture", exception);
        }
    }

    private static BlockPos firstBlueprintBlockPosition(
      final Blueprint blueprint,
      final BlockPos structureLocation,
      final net.minecraft.world.level.block.Block block)
    {
        for (final BlockInfo blockInfo : blueprint.getBlockInfoAsList())
        {
            if (blockInfo.getState() != null && blockInfo.getState().is(block))
            {
                return structureLocation.subtract(blueprint.getPrimaryBlockOffset()).offset(blockInfo.getPos());
            }
        }
        return null;
    }

    private static Path restartMarkerPath()
    {
        final String raw = System.getenv("MINECOLONIES_RESTART_MARKER");
        return raw == null || raw.isBlank() ? null : Path.of(raw);
    }

    /**
     * Where restart prepare leaves its world snapshot: a "saved-world" folder next to the marker file. The resume run's
     * procedure copies this folder (not the prepare universe) into its own universe.
     */
    private static Path restartSavedWorldPath(final Path markerPath)
    {
        final Path parent = markerPath.toAbsolutePath().getParent();
        return parent.resolve("saved-world");
    }

    private static void copySavedWorld(final Path source, final Path target) throws IOException
    {
        final Path root = source.toAbsolutePath().normalize();
        if (Files.exists(target))
        {
            try (var paths = Files.walk(target))
            {
                for (final Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                {
                    Files.delete(path);
                }
            }
        }
        try (var paths = Files.walk(root))
        {
            for (final Path path : paths.toList())
            {
                final Path relative = root.relativize(path);
                if (relative.getFileName() != null && relative.getFileName().toString().equals("session.lock"))
                {
                    continue;
                }
                final Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(path))
                {
                    Files.createDirectories(destination);
                }
                else
                {
                    Files.copy(path, destination, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void writeRestartMarker(final Path path, final RestartMarker marker) throws IOException
    {
        final Path parent = path.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        Files.writeString(path, marker.serialize());
    }

    private static RestartMarker readRestartMarker(final Path path) throws IOException
    {
        final Map<String, String> values = Files.readAllLines(path).stream()
          .filter(line -> !line.isBlank() && line.indexOf('=') > 0)
          .map(line -> line.split("=", 2))
          .collect(java.util.stream.Collectors.toMap(parts -> parts[0], parts -> parts[1]));
        return RestartMarker.deserialize(values);
    }

    private record RestartProductionFixture(
      ServerLevel level,
      ServerPlayer player,
      IColony colony,
      IBuilding townHall,
      IBuilding builder,
      IBuilding sawmill,
      IBuilding warehouse,
      IBuilding deliveryman,
      ICitizenData builderCitizen,
      ICitizenData sawmillCitizen,
      ICitizenData courierCitizen,
      IToken<?> targetRequestToken,
      IRequest<?> targetRequest,
      BlockPos townHallPos,
      BlockPos builderAnchor,
      BlockPos sawmillAnchor,
      BlockPos courierAnchor,
      BlockPos constructionTarget,
      com.ldtteam.structurize.api.compat.itemhandler.IItemHandler builderInventory,
      com.ldtteam.structurize.api.compat.itemhandler.IItemHandler sawmillInventory)
    {
    }

    private record RestartMarker(
      String dimension,
      int colonyId,
      String playerUuid,
      BlockPos townHallPos,
      BlockPos builderPos,
      BlockPos sawmillPos,
      BlockPos warehousePos,
      BlockPos deliverymanPos,
      int builderCitizenId,
      int sawmillCitizenId,
      int courierCitizenId,
      int workOrderId,
      String requestUuid,
      BlockPos townHallAbsolutePos,
      BlockPos builderAnchor,
      BlockPos sawmillAnchor,
      BlockPos courierAnchor,
      BlockPos workOrderLocation,
      BlockPos constructionTarget,
      BlockPos secondaryConstructionTarget,
      int builderPlanksBeforeRestart,
      int builderOakFencesBeforeRestart,
      int builderLanternsBeforeRestart,
      RequestState parentRequestState,
      List<ItemStack> parentDeliveries)
    {
        private String serialize()
        {
            return String.join("\n",
              "dimension=" + dimension,
              "colonyId=" + colonyId,
              "playerUuid=" + playerUuid,
              "townHallPos=" + encode(townHallPos),
              "builderPos=" + encode(builderPos),
              "sawmillPos=" + encode(sawmillPos),
              "warehousePos=" + encode(warehousePos),
              "deliverymanPos=" + encode(deliverymanPos),
              "builderCitizenId=" + builderCitizenId,
              "sawmillCitizenId=" + sawmillCitizenId,
              "courierCitizenId=" + courierCitizenId,
              "workOrderId=" + workOrderId,
              "requestUuid=" + requestUuid,
              "townHallAbsolutePos=" + encode(townHallAbsolutePos),
              "builderAnchor=" + encode(builderAnchor),
              "sawmillAnchor=" + encode(sawmillAnchor),
              "courierAnchor=" + encode(courierAnchor),
              "workOrderLocation=" + encode(workOrderLocation),
              "constructionTarget=" + encode(constructionTarget),
              "secondaryConstructionTarget=" + encode(secondaryConstructionTarget),
              "builderPlanksBeforeRestart=" + builderPlanksBeforeRestart,
              "builderOakFencesBeforeRestart=" + builderOakFencesBeforeRestart,
              "builderLanternsBeforeRestart=" + builderLanternsBeforeRestart,
              "parentRequestState=" + parentRequestState.name(),
              "parentDeliveries=" + parentDeliveries.stream().map(RestartMarker::encodeStack)
                .collect(java.util.stream.Collectors.joining(",")))
              + "\n";
        }

        private static RestartMarker deserialize(final Map<String, String> values)
        {
            return new RestartMarker(
              required(values, "dimension"),
              Integer.parseInt(required(values, "colonyId")),
              required(values, "playerUuid"),
              decode(required(values, "townHallPos")),
              decode(required(values, "builderPos")),
              decode(required(values, "sawmillPos")),
              decode(required(values, "warehousePos")),
              decode(required(values, "deliverymanPos")),
              Integer.parseInt(required(values, "builderCitizenId")),
              Integer.parseInt(required(values, "sawmillCitizenId")),
              Integer.parseInt(required(values, "courierCitizenId")),
              Integer.parseInt(required(values, "workOrderId")),
              required(values, "requestUuid"),
              decode(required(values, "townHallAbsolutePos")),
              decode(required(values, "builderAnchor")),
              decode(required(values, "sawmillAnchor")),
              decode(required(values, "courierAnchor")),
              decode(required(values, "workOrderLocation")),
              decode(required(values, "constructionTarget")),
              decode(required(values, "secondaryConstructionTarget")),
              Integer.parseInt(required(values, "builderPlanksBeforeRestart")),
              Integer.parseInt(required(values, "builderOakFencesBeforeRestart")),
              Integer.parseInt(required(values, "builderLanternsBeforeRestart")),
              RequestState.valueOf(required(values, "parentRequestState")),
              decodeStacks(values.getOrDefault("parentDeliveries", "")));
        }

        private static String required(final Map<String, String> values, final String key)
        {
            final String value = values.get(key);
            if (value == null || value.isBlank())
            {
                throw new IllegalArgumentException("missing marker key " + key);
            }
            return value;
        }

        private static String encode(final BlockPos pos)
        {
            return pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }

        private static BlockPos decode(final String value)
        {
            final String[] parts = value.split(",", -1);
            if (parts.length != 3)
            {
                throw new IllegalArgumentException("invalid block position " + value);
            }
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }

        private static String encodeStack(final ItemStack stack)
        {
            return stack.getItem().builtInRegistryHolder().key().identifier() + ":" + stack.getCount();
        }

        private static List<ItemStack> decodeStacks(final String value)
        {
            if (value.isBlank())
            {
                return List.of();
            }
            return java.util.Arrays.stream(value.split(","))
              .map(entry -> {
                  final int separator = entry.lastIndexOf(':');
                  if (separator <= 0 || separator == entry.length() - 1)
                  {
                      throw new IllegalArgumentException("invalid stack " + entry);
                  }
                  final String itemId = entry.substring(0, separator);
                  final String count = entry.substring(separator + 1);
                  final ItemStack stack = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getValue(net.minecraft.resources.Identifier.parse(itemId)));
                  stack.setCount(Integer.parseInt(count));
                  return stack;
              })
              .toList();
        }
    }

    /**
     * Place and register a level-one worker/storage hut through the same
     * block-entity/building-manager path used by the production server.
     */
    private static IBuilding placeProductionBuilding(
      final GameTestHelper helper,
      final IColony colony,
      final net.minecraft.world.level.block.Block block,
      final BlockPos relativePos,
      final String blueprintPath)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos absolutePos = helper.absolutePos(relativePos);
        level.setChunkForced(absolutePos.getX() >> 4, absolutePos.getZ() >> 4, true);
        helper.setBlock(relativePos, block.defaultBlockState());
        helper.assertBlockPresent(block, relativePos);
        final BlockEntity blockEntity = level.getBlockEntity(absolutePos);
        helper.assertTrue(blockEntity instanceof TileEntityColonyBuilding,
          "production fixture hut did not create a colony building tile: block=" + block
            + ", pos=" + absolutePos + ", entity=" + blockEntity);
        final TileEntityColonyBuilding hut = (TileEntityColonyBuilding) blockEntity;
        hut.setPackName("Minecolonies Original");
        hut.setBlueprintPath(blueprintPath);
        final IBuilding building = colony.getServerBuildingManager().addNewBuilding(hut, level);
        helper.assertTrue(building != null,
          "production fixture hut was not registered: block=" + block + ", pos=" + absolutePos);
        building.setBuildingLevel(1);
        return building;
    }

    private static void forceBuildingChunks(final ServerLevel level, final IBuilding building)
    {
        final BlockPos first = building.getCorners().getA();
        final BlockPos second = building.getCorners().getB();
        final int minChunkX = Math.min(first.getX(), second.getX()) >> 4;
        final int maxChunkX = Math.max(first.getX(), second.getX()) >> 4;
        final int minChunkZ = Math.min(first.getZ(), second.getZ()) >> 4;
        final int maxChunkZ = Math.max(first.getZ(), second.getZ()) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++)
        {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++)
            {
                level.setChunkForced(chunkX, chunkZ, true);
            }
        }
    }

    /**
     * Supply one finite stack per currently requested resource and the basic
     * builder tools.  The worker may consume the stack between pump ticks, so
     * the caller repeats this operation while construction is in progress.
     */
    private static void deliverFiniteBuilderResources(
      final BuildingBuilder builder,
      final ICitizenData citizen,
      final ServerLevel level,
      final int[] delivered)
    {
        /*
         * A real deliveryman only supplies the active builder bucket.  The
         * old fixture iterated over the complete build resource map on every
         * pump tick, which overfilled the builder chest with future buckets
         * and repeatedly injected tools after their requests had completed.
         * That made the worker legitimately enter its full-inventory pickup
         * state, but the fixture had no deliveryman to resolve that pickup.
         * Keep this finite harness faithful to the production request flow:
         * supply only the remaining total build deficit and only tools that
         * still have an open citizen request.  The worker itself is limited
         * to the active bucket, so future materials may safely remain in the
         * building inventory without being carried prematurely.
         */
        final List<BuildingBuilderResource> resources = new java.util.ArrayList<>(builder.getNeededResources().values());
        for (final BuildingBuilderResource resource : resources)
        {
            final int available = InventoryUtils.getItemCountInItemHandler(builder.getItemHandlerCap(),
                stack -> ItemStackUtils.compareItemStacksIgnoreStackSize(stack, resource.getItemStack(), true, true))
              + InventoryUtils.getItemCountInItemHandler(citizen.getInventory(),
                stack -> ItemStackUtils.compareItemStacksIgnoreStackSize(stack, resource.getItemStack(), true, true));
            final int missing = Math.max(0, resource.getAmount() - available);
            if (missing <= 0)
            {
                continue;
            }

            final ItemStack supply = resource.getItemStack().copy();
            supply.setCount(Math.min(supply.getMaxStackSize(), missing));
            delivered[0] += transferAndOverrule(builder, citizen, level, supply);
        }

        delivered[0] += deliverRequestedTool(builder, citizen, level, new ItemStack(Items.WOODEN_PICKAXE));
        delivered[0] += deliverRequestedTool(builder, citizen, level, new ItemStack(Items.WOODEN_AXE));
        delivered[0] += deliverRequestedTool(builder, citizen, level, new ItemStack(Items.WOODEN_SHOVEL));
        delivered[0] += deliverRequestedTool(builder, citizen, level, new ItemStack(Items.WOODEN_HOE));
        delivered[0] += deliverRequestedTool(builder, citizen, level, new ItemStack(Items.SHEARS));
    }

    private static int deliverRequestedTool(
      final BuildingBuilder builder,
      final ICitizenData citizen,
      final ServerLevel level,
      final ItemStack toolStack)
    {
        final boolean requested = builder.getOpenRequests(citizen.getId()).stream()
          .map(IRequest::getRequest)
          .filter(Tool.class::isInstance)
          .map(Tool.class::cast)
          .anyMatch(tool -> tool.matches(toolStack));
        return requested ? transferAndOverrule(builder, citizen, level, toolStack) : 0;
    }

    private static int transferAndOverrule(
      final BuildingBuilder builder,
      final ICitizenData citizen,
      final ServerLevel level,
      final ItemStack supply)
    {
        final ItemStack remainder = builder.forceTransferStack(supply, level);
        final int inserted = supply.getCount() - remainder.getCount();
        if (inserted > 0)
        {
            // This is the same request bridge used by the real building
            // inventory/rack insertion path.  forceTransferStack only puts
            // items in the inventory; overruleNextOpenRequestWithStack is
            // what tells the request manager that the matching delivery was
            // actually supplied.
            final ItemStack accepted = supply.copy();
            accepted.setCount(inserted);
            builder.overruleNextOpenRequestWithStack(accepted);
            final boolean overruledBuilding = builder.overruleNextOpenRequestOfCitizenWithStack(citizen, accepted);
            if (accepted.is(Items.WOODEN_PICKAXE) || accepted.is(Items.WOODEN_AXE)
                || accepted.is(Items.WOODEN_SHOVEL) || accepted.is(Items.WOODEN_HOE)
                || accepted.is(Items.SHEARS))
            {
                Log.getLogger().info("P10A tool delivery stack={} inserted={} citizenOverruled={} openCitizenRequests={} citizenInventory={}",
                  accepted, inserted, overruledBuilding, builder.getOpenRequests(citizen.getId()).stream().map(request -> describeRequest(builder, request)).toList(),
                  describeInventory(citizen.getInventory()));
            }
        }
        return inserted;
    }

    private static String describeRequest(final IBuilding building, final IRequest<?> request)
    {
        final String requestValue;
        if (request.getRequest() instanceof Tool tool)
        {
            requestValue = "Tool[type=" + tool.getEquipmentType().getRegistryName()
              + ",min=" + tool.getMinLevel() + ",max=" + tool.getMaxLevel()
              + ",matchPickaxe=" + tool.matches(new ItemStack(Items.WOODEN_PICKAXE))
              + ",matchAxe=" + tool.matches(new ItemStack(Items.WOODEN_AXE)) + "]";
        }
        else
        {
            requestValue = String.valueOf(request.getRequest());
        }
        return request.getId() + ":" + request.getState()
          + ":requester=" + request.getRequester().getClass().getSimpleName() + "/" + request.getRequester().getId()
          + ":type=" + request.getType()
          + ":value=" + requestValue
          + ":deliveries=" + request.getDeliveries()
          + ":children=" + request.getChildren()
          + ":buildingId=" + building.getId();
    }

    private static String describeToolMatches(final IBuilding building, final ICitizenData citizen)
    {
        return building.getOpenRequests(citizen.getId()).stream()
          .filter(request -> request.getRequest() instanceof Tool)
          .map(request -> {
              final Tool tool = (Tool) request.getRequest();
              return tool.getEquipmentType().getRegistryName() + ":min=" + tool.getMinLevel()
                + ":max=" + tool.getMaxLevel()
                + ":pickaxe=" + tool.matches(new ItemStack(Items.WOODEN_PICKAXE))
                + ":axe=" + tool.matches(new ItemStack(Items.WOODEN_AXE))
                + ":shovel=" + tool.matches(new ItemStack(Items.WOODEN_SHOVEL))
                + ":hoe=" + tool.matches(new ItemStack(Items.WOODEN_HOE))
                + ":shears=" + tool.matches(new ItemStack(Items.SHEARS));
          })
          .toList()
          .toString();
    }

    private static String describeInventory(final com.ldtteam.structurize.api.compat.itemhandler.IItemHandler inventory)
    {
        final List<String> stacks = new java.util.ArrayList<>();
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty())
            {
                stacks.add(slot + "=" + stack);
            }
        }
        return stacks.toString();
    }

    private static String describeCrafterCitizen(final ICitizenData citizen, final IBuilding building)
    {
        return citizen.getEntity().map(entity -> {
            final var workAI = entity.getCitizenJobHandler().getWorkAI();
            final String aiState = workAI == null ? "no-ai" : String.valueOf(workAI.getStateAI().getState());
            final String aiHistory = workAI == null ? "no-ai" : workAI.getStateAI().getHistory().getString();
            final String aiTaskState;
            if (citizen.getJob() instanceof com.minecolonies.core.colony.jobs.AbstractJobCrafter crafter)
            {
                aiTaskState = ":queueSize=" + crafter.getTaskQueue().size()
                  + ":hasWork=" + (!crafter.getTaskQueue().isEmpty() && crafter.getCurrentTask() != null)
                  + ":actionsDone=" + crafter.getActionsDone();
            }
            else
            {
                aiTaskState = "";
            }
            final String task;
            if (citizen.getJob() instanceof com.minecolonies.core.colony.jobs.AbstractJobCrafter crafter)
            {
                final IRequest<?> currentTask = crafter.getCurrentTask();
                task = currentTask == null ? "null" : currentTask.getId() + ":" + currentTask.getState()
                  + ":request=" + currentTask.getRequest()
                  + ":recipe=" + (currentTask.getRequest() instanceof PublicCrafting crafting ? crafting.getRecipeID() : "not-public-crafting");
            }
            else
            {
                task = String.valueOf(citizen.getJob());
            }
            final String deliveryQueue;
            if (citizen.getJob() instanceof JobDeliveryman deliveryman)
            {
                deliveryQueue = ":deliveryQueue=" + deliveryman.getTaskQueue().stream()
                  .map(token -> {
                      final IRequest<?> request = citizen.getColony().getRequestManager().getRequestForToken(token);
                      return token + "=" + (request == null ? "null" : request.getState() + ":" + request.getDeliveries()
                        + ":request=" + request.getRequest());
                  })
                  .toList();
            }
            else
            {
                deliveryQueue = "";
            }
            return "uuid=" + entity.getUUID()
              + ":pos=" + entity.blockPosition()
              + ":inBuilding=" + building.isInBuilding(entity.blockPosition())
              + ":citizenState=" + (entity instanceof com.minecolonies.core.entity.citizen.EntityCitizen liveCitizen
                ? liveCitizen.getCitizenAI().getState() : "not-entity-citizen")
              + ":leisure=" + citizen.getLeisureTime()
              + ":jobInterruptible=" + (citizen.getJob() == null || citizen.getJob().canAIBeInterrupted())
              + ":workCanGoIdle=" + (workAI instanceof com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic<?, ?> basic && basic.canGoIdle())
              + ":navDone=" + entity.getNavigation().isDone()
              + ":path=" + entity.getNavigation().getPath()
              + ":pathResult=" + entity.getNavigation().getPathResult()
              + ":state=" + aiState
              + ":history=" + aiHistory
              + aiTaskState
              + ":task=" + task
              + deliveryQueue
              + ":corners=" + building.getCorners()
              + ":containers=" + building.getContainers()
              + ":workTags=" + building.getLocationsFromTag("work")
              + ":buildingOakLogs=" + InventoryUtils.getCountFromBuilding(building, stack -> stack.is(Items.OAK_LOG));
        }).orElse("no-entity");
    }

    /**
     * Clears everything above a colony fixture's floor, including the strip west and north of the test's own plot
     * that the fixture borrows. An earlier test in that strip may have left blocks behind, and a colony fixture must
     * start from empty air so its citizens and builder see the same world in a full batch as in a single run.
     */
    private static void clearColonyFixture(final GameTestHelper helper, final int floorY, final int maxX, final int maxZ)
    {
        for (int x = -8; x < maxX; x++)
        {
            for (int y = floorY + 1; y < 24; y++)
            {
                for (int z = -8; z < maxZ; z++)
                {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static ServerPlayer makeConnectedSurvivalPlayer(final GameTestHelper helper)
    {
        return makeConnectedSurvivalPlayer(helper, UUID.randomUUID());
    }

    private static ServerPlayer makeConnectedSurvivalPlayer(final GameTestHelper helper, final UUID playerUuid)
    {
        final ServerPlayer player = new ServerPlayer(
          helper.getLevel().getServer(),
          helper.getLevel(),
          new GameProfile(playerUuid, "test-mock-player"),
          ClientInformation.createDefault())
        {
            @Override
            public GameType gameMode()
            {
                return GameType.SURVIVAL;
            }

            @Override
            public boolean isClientAuthoritative()
            {
                return false;
            }
        };
        GameType.SURVIVAL.updatePlayerAbilities(player.getAbilities());
        final Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        // NeoForge provides a complete payload/channel setup for test
        // connections. Without it, the production PlayerLoggedInEvent sends
        // Structurize's ServerUUIDMessage before the mock has a client
        // play-phase channel and NeoForge rejects the payload. This is the
        // supported mock-connection path; do not swallow the packet in an
        // overridden listener method because that would hide a real channel
        // registration or phase defect.
        NetworkRegistry.configureMockConnection(connection);
        final CommonListenerCookie cookie = CommonListenerCookie.createInitial(player.getGameProfile(), false);
        // Use the normal server placement path so the login event and its
        // client-bound Structurize sync packet are actually fired. The
        // embedded channel is the supported no-socket test transport.
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    /**
     * Regression fixture for the 26.2 ValueInput/ValueOutput migration of
     * rack inventories.  A real rack is populated through its production
     * inventory handler, persisted through {@link BlockEntity#saveWithFullMetadata},
     * and restored through {@link BlockEntity#loadWithComponents}.  Empty
     * slots are intentionally left around the two populated slots: the old
     * port emitted an {@code {empty:1b}} entry for each of them and then
     * reported a missing item id while decoding the list.
     */
    public static void rackInventoryRoundTrip(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos relativeRackPos = new BlockPos(4, 1, 4);
        final BlockPos rackPos = helper.absolutePos(relativeRackPos);
        final net.minecraft.world.level.block.state.BlockState rackState = ModBlocks.blockRack.defaultBlockState();
        helper.setBlock(relativeRackPos, rackState);
        helper.assertBlockPresent(ModBlocks.blockRack, relativeRackPos);

        final BlockEntity blockEntity = level.getBlockEntity(rackPos);
        helper.assertTrue(blockEntity instanceof TileEntityRack,
          "rack placement did not create the MineColonies rack block entity: " + blockEntity);
        final TileEntityRack rack = (TileEntityRack) blockEntity;
        rack.getInventory().setStackInSlot(0, new ItemStack(Items.DIAMOND, 3));
        rack.getInventory().setStackInSlot(8, new ItemStack(Items.COBBLESTONE, 17));

        final CompoundTag saved = rack.saveWithFullMetadata(level.registryAccess());
        Log.getLogger().info("P10B rack round-trip serialized={}", saved);

        final TileEntityRack restored = new TileEntityRack(rackPos, rackState);
        restored.loadWithComponents(TagValueInput.create(
          ProblemReporter.DISCARDING,
          level.registryAccess(),
          saved));
        helper.assertTrue(restored.getInventory().getSlots() == rack.getInventory().getSlots(),
          "rack inventory size changed during persistent round-trip: saved=" + rack.getInventory().getSlots()
            + ", restored=" + restored.getInventory().getSlots());
        helper.assertTrue(restored.getInventory().getStackInSlot(0).is(Items.DIAMOND)
            && restored.getInventory().getStackInSlot(0).getCount() == 3,
          "rack slot 0 did not survive persistent round-trip: " + restored.getInventory().getStackInSlot(0));
        helper.assertTrue(restored.getInventory().getStackInSlot(8).is(Items.COBBLESTONE)
            && restored.getInventory().getStackInSlot(8).getCount() == 17,
          "rack slot 8 did not survive persistent round-trip: " + restored.getInventory().getStackInSlot(8));
        for (int slot = 1; slot < restored.getInventory().getSlots(); slot++)
        {
            if (slot != 8)
            {
                helper.assertTrue(restored.getInventory().getStackInSlot(slot).isEmpty(),
                  "rack empty slot was populated during persistent round-trip: slot=" + slot
                    + ", value=" + restored.getInventory().getStackInSlot(slot));
            }
        }

        // Preserve a fixture from the old port as well.  This entry is not
        // produced by the 26.2 encoder, but existing blueprints/worlds can
        // still contain it and must load without a ProblemReporter warning.
        final CompoundTag legacySaved = saved.copy();
        final ListTag legacyInventory = legacySaved.getListOrEmpty("inventory");
        final CompoundTag legacyEmpty = new CompoundTag();
        legacyEmpty.putBoolean("empty", true);
        legacyInventory.set(1, legacyEmpty);
        final TileEntityRack legacyRestored = new TileEntityRack(rackPos, rackState);
        legacyRestored.loadWithComponents(TagValueInput.create(
          ProblemReporter.DISCARDING,
          level.registryAccess(),
          legacySaved));
        helper.assertTrue(legacyRestored.getInventory().getStackInSlot(1).isEmpty(),
          "legacy rack empty-slot marker was not accepted: " + legacyRestored.getInventory().getStackInSlot(1));
        helper.assertTrue(legacyRestored.getInventory().getStackInSlot(8).is(Items.COBBLESTONE)
            && legacyRestored.getInventory().getStackInSlot(8).getCount() == 17,
          "legacy rack load corrupted a later populated slot: " + legacyRestored.getInventory().getStackInSlot(8));
        helper.succeed();
    }

    /**
     * Regression fixture for Domum Ornamentum's panel, post and shingle slab block
     * properties (review DPT-C05). The port registered all three with bare
     * {@code Properties.of()}, so they broke instantly, had stone-grey map colour,
     * and panels occluded neighbours and let mobs spawn on them. Expected values are
     * the 1.21 constructors': wood map colour, hardness 3, resistance 3 (panel, post)
     * or 1 (shingle slab), and for panels no occlusion and no mob spawns.
     */
    public static void domumBlockProperties(final GameTestHelper helper)
    {
        final com.ldtteam.domumornamentum.block.ModBlocks blocks = com.ldtteam.domumornamentum.block.ModBlocks.getInstance();
        final BlockPos pos = new BlockPos(1, 1, 1);
        final List<String> failures = new ArrayList<>();

        final Object[][] expected = {
          // block, hardness, resistance, occludes, zombies may spawn (null: no 1.21 override)
          {blocks.getPanel(), 3.0F, 3.0F, false, false},
          {blocks.getPost(), 3.0F, 3.0F, true, null},
          {blocks.getShingleSlab(), 3.0F, 1.0F, true, null}};
        for (final Object[] row : expected)
        {
            final Block block = (Block) row[0];
            final BlockState state = block.defaultBlockState();
            final String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
            if (block.defaultDestroyTime() != (float) row[1])
            {
                failures.add(id + " hardness " + block.defaultDestroyTime() + ", expected " + row[1]);
            }
            if (block.getExplosionResistance() != (float) row[2])
            {
                failures.add(id + " resistance " + block.getExplosionResistance() + ", expected " + row[2]);
            }
            if (state.canOcclude() != (boolean) row[3])
            {
                failures.add(id + " occludes " + state.canOcclude() + ", expected " + row[3]);
            }
            if (state.getMapColor(helper.getLevel(), helper.absolutePos(pos)) != net.minecraft.world.level.material.MapColor.WOOD)
            {
                failures.add(id + " map colour is not wood");
            }
            final boolean spawn = state.isValidSpawn(helper.getLevel(), helper.absolutePos(pos), net.minecraft.world.entity.EntityTypes.ZOMBIE);
            if (row[4] != null && spawn != (boolean) row[4])
            {
                failures.add(id + " zombie spawn " + spawn + ", expected " + row[4]);
            }
        }

        if (!failures.isEmpty())
        {
            throw helper.assertionException("Domum block properties wrong: " + String.join("; ", failures));
        }
        Log.getLogger().info("[domum_block_properties] panel/post/shingle_slab have their 1.21 block properties");
        helper.succeed();
    }

    /**
     * Regression fixture for review DPT-C13 (found on screen with the dev harness): a Domum block entity loaded
     * from a tag (world load, /setblock, the client's block entity data packet) must go through
     * updateTextureDataWith like 1.21. The port assigned the field directly, so unknown components were kept and,
     * on the client, the chunk section was never re-rendered: placed shingles kept their default clay look.
     * The server-observable half is the component filter; the re-render half is checked in the dev client.
     */
    public static void domumTextureDataLoad(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, com.ldtteam.domumornamentum.block.IModBlocks.getInstance().getShingle(com.ldtteam.domumornamentum.shingles.ShingleHeightType.DEFAULT));
        final BlockPos pos = helper.absolutePos(rel);
        if (!(level.getBlockEntity(pos) instanceof final com.ldtteam.domumornamentum.entity.block.MateriallyTexturedBlockEntity be))
        {
            throw helper.assertionException("shingle has no materially textured block entity");
        }
        final Identifier roof = Identifier.withDefaultNamespace("block/clay");
        final Identifier support = Identifier.withDefaultNamespace("block/oak_planks");
        final Identifier bogus = Identifier.withDefaultNamespace("block/not_a_shingle_component");
        final CompoundTag textures = new CompoundTag();
        textures.putString(roof.toString(), "minecraft:grass_block");
        textures.putString(support.toString(), "minecraft:oak_planks");
        textures.putString(bogus.toString(), "minecraft:stone");
        final CompoundTag tag = new CompoundTag();
        tag.put("textureData", textures);
        be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));

        final Map<Identifier, Block> loaded = be.getTextureData().getTexturedComponents();
        final List<String> failures = new ArrayList<>();
        if (loaded.get(roof) != Blocks.GRASS_BLOCK || loaded.get(support) != Blocks.OAK_PLANKS)
        {
            failures.add("valid components not loaded: " + loaded);
        }
        if (loaded.containsKey(bogus))
        {
            failures.add("unknown component kept, load bypassed updateTextureDataWith: " + loaded);
        }
        if (!failures.isEmpty())
        {
            throw helper.assertionException("Domum texture data load wrong: " + String.join("; ", failures));
        }
        Log.getLogger().info("[domum_texture_data_load] tag load goes through updateTextureDataWith");
        helper.succeed();
    }

    /**
     * Regression fixture for review DPT-C12: MultiPiston's recipe and block loot table must load on 26.3. The port kept them in the
     * pre-1.21 folders (recipes/, loot_tables/) and the pre-1.21 recipe result format, so the block had no recipe and dropped nothing.
     */
    public static void multipistonRecipeAndLoot(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final Block piston = com.ldtteam.multipiston.ModBlocks.multipiston.value();
        final net.minecraft.world.item.Item pistonItem = piston.asItem();

        final net.minecraft.resources.ResourceKey<net.minecraft.world.item.crafting.Recipe<?>> recipeKey =
          net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE,
            Identifier.fromNamespaceAndPath("multipiston", "multiblock"));
        helper.assertTrue(level.recipeAccess().recipeMap().byKey(recipeKey) != null, "Missing recipe multipiston:multiblock");

        final ItemStack s = new ItemStack(Items.STONE);
        final ItemStack r = new ItemStack(Items.REDSTONE_BLOCK);
        final ItemStack p = new ItemStack(Items.PISTON);
        final net.minecraft.world.item.crafting.CraftingInput grid = net.minecraft.world.item.crafting.CraftingInput.of(3, 3,
          List.of(s, s, s, r, ItemStack.EMPTY, r, p, p, p));
        final var match = level.recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, grid, level);
        helper.assertTrue(match.isPresent(), "Crafting grid stone/redstone block/piston matches no recipe");
        final ItemStack crafted = match.get().value().assemble(grid);
        helper.assertTrue(crafted.is(pistonItem) && crafted.getCount() == 1, "Recipe crafts " + crafted + " instead of 1 multipiston");

        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.storage.loot.LootTable> lootKey = piston.getLootTable().orElseThrow();
        helper.assertTrue(level.getServer().reloadableRegistries().getLootTable(lootKey) != net.minecraft.world.level.storage.loot.LootTable.EMPTY,
          "Missing loot table " + lootKey.identifier());

        final BlockPos rel = new BlockPos(1, 2, 1);
        helper.setBlock(rel, piston.defaultBlockState());
        final BlockPos abs = helper.absolutePos(rel);
        level.destroyBlock(abs, true);
        helper.succeedWhen(() -> {
            final List<net.minecraft.world.entity.item.ItemEntity> drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
              new net.minecraft.world.phys.AABB(abs).inflate(2), e -> e.getItem().is(pistonItem));
            helper.assertTrue(!drops.isEmpty(), "Broken multipiston dropped no item");
            Log.getLogger().info("[multipiston_recipe_and_loot] recipe crafts {}, broken block dropped {}", crafted, drops.get(0).getItem());
        });
    }
    /**
     * Regression fixture for review X-263-COMPATSYNC: the dev client logged "Synchronized 0 flowers", "Synchronized 0 monsters" and
     * "getCopyOfCompostRecipes when empty" after joining, although the server had discovered them. This runs the server's compat
     * manager through the same serialize -> deserialize path UpdateClientWithCompatibilityMessage uses, into a fresh manager, and
     * requires the flowers, monsters and compost recipes to survive the trip.
     */
    public static void compatSyncRoundTrip(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final com.minecolonies.api.compatibility.ICompatibilityManager server = IColonyManager.getInstance().getCompatibilityManager();
        final List<String> failures = new ArrayList<>();
        final int serverFlowers = server.getImmutableFlowers().size();
        final int serverMonsters = server.getAllMonsters().size();
        final int serverCompost = server.getCopyOfCompostRecipes().size();
        if (serverFlowers == 0 || serverMonsters == 0 || serverCompost == 0)
        {
            failures.add("server manager empty before sync: flowers=" + serverFlowers + " monsters=" + serverMonsters + " compost=" + serverCompost);
        }

        final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(new net.minecraft.network.FriendlyByteBuf(Unpooled.buffer()), level.registryAccess());
        server.serialize(buf);
        final com.minecolonies.api.compatibility.CompatibilityManager client = new com.minecolonies.api.compatibility.CompatibilityManager();
        client.deserialize(buf, level);

        if (client.getImmutableFlowers().size() != serverFlowers)
        {
            failures.add("flowers " + client.getImmutableFlowers().size() + " != server " + serverFlowers);
        }
        if (client.getAllMonsters().size() != serverMonsters)
        {
            failures.add("monsters " + client.getAllMonsters().size() + " != server " + serverMonsters);
        }
        if (client.getCopyOfCompostRecipes().size() != serverCompost)
        {
            failures.add("compost recipes " + client.getCopyOfCompostRecipes().size() + " != server " + serverCompost);
        }
        if (buf.readableBytes() != 0)
        {
            failures.add(buf.readableBytes() + " bytes left unread");
        }
        if (!failures.isEmpty())
        {
            helper.fail("compat sync lost data: " + String.join("; ", failures));
            return;
        }
        helper.succeed();
    }


    /**
     * Regression fixture for review X-263-COMPOST: MineColonies foods, crop seeds, mistletoe and composted dirt go into a vanilla
     * composter with 1.21's chances. 1.21 declared them in NeoForge's compostables data map (generated
     * data_maps/item/compostables.json); NeoForge 26.3 dropped that map in favour of the vanilla COMPOSTABLE item component, which the
     * port never set. The expected ids/chances below are copied from 1.21's generated file. Each item must carry the component, a
     * hopper-style insert into an empty composter must add a layer, and the layer chance on a non-empty composter must match 1.21.
     */
    public static void compostableItems(final GameTestHelper helper)
    {
        final Map<String, Double> expected = new java.util.LinkedHashMap<>();
        for (final String id : ("apple_pie baked_salmon borscht cabochis cheddar_cheese cheese_pizza cheese_ravioli chicken_broth composted_dirt "
          + "congee cooked_rice corn_chowder eggdrop_soup eggplant_dolma feta_cheese fish_dinner fish_n_chips flatbread fried_rice hand_pie "
          + "kebab kimchi lamb_stew lembas_scone manchet_bread meat_ravioli mint_jelly mint_tea mintchoco_cheesecake muffin mushroom_pizza "
          + "mutton_dinner pasta_plain pasta_tomato pea_soup pepper_hummus pierogi pita_hummus plain_cheesecake polenta potato_soup pottage "
          + "ramen rice_ball schnitzel spicy_eggplant spicy_grilled_chicken squash_soup steak_dinner stew_trencher stuffed_pepper stuffed_pita "
          + "sugary_bread sushi_roll tacos tofu tortillas veggie_quiche veggie_ravioli veggie_soup yogurt yogurt_with_berries").split(" "))
        {
            expected.put(id, 1.0);
        }
        for (final String id : ("bell_pepper butternut_squash cabbage chickpea corn durum eggplant garlic mint mistletoe nether_pepper onion "
          + "peas rice soybean tomato").split(" "))
        {
            expected.put(id, 0.5);
        }
        for (final String id : "chorus_bread golden_bread milky_bread".split(" "))
        {
            expected.put(id, 5.0 / 6.0);
        }

        final ServerLevel level = helper.getLevel();
        final BlockPos abs = helper.absolutePos(new BlockPos(1, 2, 1));
        final net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(42L);
        final List<String> failures = new ArrayList<>();
        final int samples = 4000;
        for (final Map.Entry<String, Double> entry : expected.entrySet())
        {
            final net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(Constants.MOD_ID, entry.getKey()));
            if (item == Items.AIR)
            {
                failures.add(entry.getKey() + ": not registered");
                continue;
            }
            final ItemStack stack = new ItemStack(item);
            final net.minecraft.world.item.component.Compostable compostable = stack.get(net.minecraft.core.component.DataComponents.COMPOSTABLE);
            if (compostable == null)
            {
                failures.add(entry.getKey() + ": no compostable component");
                continue;
            }

            final BlockState empty = Blocks.COMPOSTER.defaultBlockState();
            level.setBlockAndUpdate(abs, empty);
            final BlockState afterInsert = net.minecraft.world.level.block.ComposterBlock.insertItem(null, empty, level, stack.copy(), abs);
            if (afterInsert.getValue(net.minecraft.world.level.block.ComposterBlock.LEVEL) != 1)
            {
                failures.add(entry.getKey() + ": insert into an empty composter gave level "
                  + afterInsert.getValue(net.minecraft.world.level.block.ComposterBlock.LEVEL));
            }

            final BlockState oneLayer = empty.setValue(net.minecraft.world.level.block.ComposterBlock.LEVEL, 1);
            final net.minecraft.world.level.storage.loot.LootContext context = new net.minecraft.world.level.storage.loot.LootContext.Builder(
              new net.minecraft.world.level.storage.loot.LootParams.Builder(level)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_STATE, oneLayer)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, net.minecraft.world.phys.Vec3.atCenterOf(abs))
                .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.BLOCK_INTERACT))
              .withOptionalRandomSource(random)
              .create(java.util.Optional.empty());
            int added = 0;
            for (int i = 0; i < samples; i++)
            {
                added += compostable.layers().get(context, 0) > 0 ? 1 : 0;
            }
            final double observed = added / (double) samples;
            if (Math.abs(observed - entry.getValue()) > 0.03)
            {
                failures.add(String.format("%s: layer chance %.3f, 1.21 had %.3f", entry.getKey(), observed, entry.getValue()));
            }
        }
        level.setBlockAndUpdate(abs, Blocks.AIR.defaultBlockState());
        if (!failures.isEmpty())
        {
            throw helper.assertionException(failures.size() + "/" + expected.size() + " compostables wrong: " + String.join("; ", failures));
        }
        Log.getLogger().info("[compostable_items] {} MineColonies items compost with 1.21's chances", expected.size());
        helper.succeed();
    }

    /**
     * Regression fixture for Domum Ornamentum's extra blocks' tool tags (review DPT-C06).
     * 1.21 put every extra block into its category's mineable tag (bricks/slates:
     * pickaxe, thatched: hoe, paper/cactus: axe); the port's tag rewrite dropped that
     * loop, so tools mined them at hand speed. Checks each extra block is in its tag
     * and the matching iron tool is faster than a bare hand on it.
     */
    public static void domumExtraBlockMineableTags(final GameTestHelper helper)
    {
        final List<String> failures = new ArrayList<>();
        final List<com.ldtteam.domumornamentum.block.decorative.ExtraBlock> extras =
          com.ldtteam.domumornamentum.block.ModBlocks.getInstance().getExtraTopBlocks();
        for (final com.ldtteam.domumornamentum.block.decorative.ExtraBlock block : extras)
        {
            final BlockState state = block.defaultBlockState();
            final net.minecraft.tags.TagKey<Block> tag = block.getType().getCategory().getMineableTag();
            final String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
            if (!state.is(tag))
            {
                failures.add(id + " not in " + tag.location());
                continue;
            }
            final net.minecraft.world.item.Item tool = tag == net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE ? net.minecraft.world.item.Items.IRON_PICKAXE
              : tag == net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE ? net.minecraft.world.item.Items.IRON_AXE : net.minecraft.world.item.Items.IRON_HOE;
            if (new ItemStack(tool).getDestroySpeed(state) <= 1.0F)
            {
                failures.add(id + " " + tool + " mines at hand speed");
            }
        }
        if (extras.size() != 27)
        {
            failures.add("expected 27 extra blocks, found " + extras.size());
        }

        if (!failures.isEmpty())
        {
            throw helper.assertionException("Domum extra block tool tags wrong (" + failures.size() + "): " + String.join("; ", failures));
        }
        Log.getLogger().info("[domum_extra_block_mineable_tags] all " + extras.size() + " extra blocks are in their mineable tag");
        helper.succeed();
    }

    /**
     * Regression fixture for Domum Ornamentum's placement ghost (review DPT-C03/04).
     * The ghost must show the held stack's materials at the position the block will
     * be placed: the port built a block entity from the stack and then ignored it,
     * reading model data from the looked-at block instead, and drew the quads white
     * at a quarter alpha. The fixture aims a shingle skinned with grass block + oak
     * planks at the top of a stone block and checks where the preview goes, that the
     * preview's model data there is the stack's, and the ghost's tint colours.
     */
    public static void domumPlacementGhost(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE);
        final BlockPos target = helper.absolutePos(new BlockPos(2, 1, 2));

        final Block shingle = com.ldtteam.domumornamentum.block.ModBlocks.getInstance()
          .getShingle(com.ldtteam.domumornamentum.shingles.ShingleHeightType.DEFAULT);
        final com.ldtteam.domumornamentum.client.model.data.MaterialTextureData.Builder builder =
          com.ldtteam.domumornamentum.client.model.data.MaterialTextureData.builder();
        final Block[] skins = {Blocks.GRASS_BLOCK, Blocks.OAK_PLANKS};
        int skin = 0;
        for (final com.ldtteam.domumornamentum.block.IMateriallyTexturedBlockComponent component :
          ((com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock) shingle).getComponents())
        {
            builder.setComponent(component.getId(), skins[skin++ % skins.length]);
        }
        final com.ldtteam.domumornamentum.client.model.data.MaterialTextureData data = builder.build();
        final ItemStack stack = new ItemStack(shingle);
        stack.set(com.ldtteam.domumornamentum.component.ModDataComponents.TEXTURE_DATA.get(), data);

        final ServerPlayer player = FakePlayerFactory.getMinecraft(level);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        final net.minecraft.world.phys.BlockHitResult hit =
          new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(target).add(0, 0.5, 0), Direction.UP, target, false);

        final com.ldtteam.domumornamentum.client.render.ModelGhostRenderer.Preview preview =
          com.ldtteam.domumornamentum.client.render.ModelGhostRenderer.prepare(player, InteractionHand.MAIN_HAND, stack, hit,
            net.minecraft.client.renderer.block.BlockAndTintGetter.EMPTY);
        final List<String> failures = new ArrayList<>();
        if (preview == null)
        {
            throw helper.assertionException("Domum placement ghost: no preview for a shingle aimed at stone");
        }
        if (!preview.pos().equals(target.above()))
        {
            failures.add("preview at " + preview.pos() + ", expected placement position " + target.above());
        }
        if (preview.state().getBlock() != shingle)
        {
            failures.add("preview state " + preview.state());
        }
        final com.ldtteam.domumornamentum.client.model.data.MaterialTextureData previewData = preview.level().getModelData(preview.pos())
          .get(com.ldtteam.domumornamentum.client.model.properties.ModProperties.MATERIAL_TEXTURE_PROPERTY);
        if (previewData == null || !data.getTexturedComponents().equals(previewData.getTexturedComponents()))
        {
            failures.add("model data at the preview position is " + (previewData == null ? "none" : previewData.getTexturedComponents())
              + ", expected the stack's " + data.getTexturedComponents());
        }

        final it.unimi.dsi.fastutil.ints.IntList tints = it.unimi.dsi.fastutil.ints.IntList.of(0xFF112233, 0xFF445566);
        final int untinted = com.ldtteam.domumornamentum.client.render.ModelGhostRenderer.ghostColor(-1, tints);
        final int tinted = com.ldtteam.domumornamentum.client.render.ModelGhostRenderer.ghostColor(1, tints);
        if (untinted != 0x80FFFFFF)
        {
            failures.add(String.format("untinted quad colour %08x, expected 80ffffff", untinted));
        }
        if (tinted != 0x80445566)
        {
            failures.add(String.format("tint index 1 colour %08x, expected 80445566", tinted));
        }

        if (!failures.isEmpty())
        {
            throw helper.assertionException("Domum placement ghost wrong: " + String.join("; ", failures));
        }
        Log.getLogger().info("[domum_placement_ghost] preview at {} with the stack's materials; tinted quads keep their tint at half alpha", preview.pos());
        helper.succeed();
    }

    /**
     * Regression fixture for Domum Ornamentum material tinting (review DPT-C02).
     * A block skinned with grass/leaves must take the skin's tint, not white. The
     * fixture resolves each retextured quad's tint index the way the renderer
     * does (index into the block's tint values) and compares it with the skin
     * block's own tint source, for the item colours (level-independent) of a
     * shingle skinned with grass block + oak planks and one skinned with birch +
     * oak leaves. (Grass reads the colour map, which a server never loads, so
     * its expected colour is 0 here; it still must not be the old white.)
     */
    public static void domumMaterialTints(final GameTestHelper helper)
    {
        final net.minecraft.client.color.block.BlockColors colors = net.minecraft.client.color.block.BlockColors.createDefault();
        final Identifier roof = Identifier.withDefaultNamespace("block/clay");
        final Identifier support = Identifier.withDefaultNamespace("block/oak_planks");
        final List<String> failures = new ArrayList<>();

        final Block[][] skins = {{Blocks.GRASS_BLOCK, Blocks.OAK_PLANKS}, {Blocks.BIRCH_LEAVES, Blocks.OAK_LEAVES}};
        for (final Block[] pair : skins)
        {
            final com.ldtteam.domumornamentum.client.model.data.MaterialTextureData data =
              new com.ldtteam.domumornamentum.client.model.data.MaterialTextureData.Builder().setComponent(roof, pair[0]).setComponent(support, pair[1]).build();
            final it.unimi.dsi.fastutil.ints.IntArrayList tintValues = new it.unimi.dsi.fastutil.ints.IntArrayList();
            com.ldtteam.domumornamentum.client.color.MaterialTints.collect(data, colors, null, null, tintValues);

            for (final Block skin : pair)
            {
                final BlockState skinState = skin.defaultBlockState();
                final net.minecraft.client.color.block.BlockTintSource source = colors.getTintSource(skinState, 0);
                final int index = com.ldtteam.domumornamentum.client.color.MaterialTints.remapTintIndex(data, skin, source == null ? -1 : 0);
                if (source == null)
                {
                    if (index != -1)
                    {
                        failures.add(skin + ": untinted skin got tint index " + index);
                    }
                    continue;
                }
                final int expected = source.color(skinState);
                final int actual = index >= 0 && index < tintValues.size() ? tintValues.getInt(index) : -1;
                if (actual != expected)
                {
                    failures.add(String.format("%s: tint index %d -> %08x, expected skin colour %08x", skin, index, actual, expected));
                }
            }
        }

        if (!failures.isEmpty())
        {
            throw helper.assertionException("Domum material tint wrong: " + String.join("; ", failures));
        }
        Log.getLogger().info("[domum_material_tints] grass/birch/oak leaves skins resolve to their own tint colours");
        helper.succeed();
    }

    /**
     * Server-side regression fixture for Domum Ornamentum's Architect's Cutter
     * (review DPT-C01). Inserting a material used to throw on every input: the
     * recipe list was an unmodifiable {@code Stream.toList()} that was then
     * sorted, and it was looked up through {@code registryAccess()}, where
     * recipes are not a registry. The fixture fills the inputs with oak planks,
     * walks the cutter's groups and variants through the same button ids the
     * screen sends, and requires at least one real crafted output.
     */
    public static void architectsCutterRecipeLookup(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos cutterPos = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.setBlock(new BlockPos(2, 1, 2), com.ldtteam.domumornamentum.block.IModBlocks.getInstance().getArchitectsCutter());
        final ServerPlayer player = FakePlayerFactory.getMinecraft(level);
        final com.ldtteam.domumornamentum.container.ArchitectsCutterContainer menu =
          new com.ldtteam.domumornamentum.container.ArchitectsCutterContainer(1, player.getInventory(),
            net.minecraft.world.inventory.ContainerLevelAccess.create(level, cutterPos));

        for (int slot = 0; slot < menu.inputInventory.getContainerSize(); slot++)
        {
            menu.inputInventory.setItem(slot, new ItemStack(Items.OAK_PLANKS, 16));
        }

        final java.util.Map<Identifier, java.util.List<ItemStack>> groups =
          com.ldtteam.domumornamentum.block.ModBlocks.getInstance().getOrComputeItemGroups();
        helper.assertTrue(!groups.isEmpty(), "Architect's Cutter has no item groups");
        int groupIndex = 0;
        ItemStack crafted = ItemStack.EMPTY;
        String craftedFrom = "";
        for (final java.util.Map.Entry<Identifier, java.util.List<ItemStack>> group : groups.entrySet())
        {
            menu.clickMenuButton(player, groupIndex);
            for (int variant = 0; variant < group.getValue().size() && crafted.isEmpty(); variant++)
            {
                menu.clickMenuButton(player, groups.size() + variant);
                crafted = menu.outputInventorySlot.getItem().copy();
                craftedFrom = group.getKey() + "#" + variant;
            }
            if (!crafted.isEmpty())
            {
                break;
            }
            groupIndex++;
        }
        Log.getLogger().info("DPT-C01 architects cutter crafted={} from={}", crafted, craftedFrom);
        helper.assertTrue(!crafted.isEmpty(), "Architect's Cutter produced no output for oak-plank inputs in any group");
        helper.assertTrue(crafted.getItem() instanceof net.minecraft.world.item.BlockItem blockItem
            && blockItem.getBlock() instanceof com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock,
          "Architect's Cutter output is not a materially textured block: " + crafted);
        helper.succeed();
    }

    /**
     * Fixture for review X-263-CUTTERIN: a player must be able to put a valid
     * material into the Architect's Cutter inputs through the normal menu clicks
     * (pick up + place, and shift-click from the player inventory), both before a
     * group is chosen and after choosing a group and variant like the screen does.
     */
    public static void architectsCutterInputClicks(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos cutterPos = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.setBlock(new BlockPos(2, 1, 2), com.ldtteam.domumornamentum.block.IModBlocks.getInstance().getArchitectsCutter());
        final ServerPlayer player = FakePlayerFactory.getMinecraft(level);
        player.getInventory().clearContent();
        final com.ldtteam.domumornamentum.container.ArchitectsCutterContainer menu =
          new com.ldtteam.domumornamentum.container.ArchitectsCutterContainer(1, player.getInventory(),
            net.minecraft.world.inventory.ContainerLevelAccess.create(level, cutterPos));
        final int inputs = menu.inputInventory.getContainerSize();
        final int output = menu.outputInventorySlot.index;
        final java.util.List<String> failures = new java.util.ArrayList<>();
        Log.getLogger().info("X-263-CUTTERIN inputs={} outputSlot={} totalSlots={}", inputs, output, menu.slots.size());

        // 1) pick-up/place into input 0 with no group selected.
        menu.setCarried(new ItemStack(Items.OAK_PLANKS, 8));
        menu.clicked(0, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player);
        Log.getLogger().info("X-263-CUTTERIN pickup slot0 -> slot={} carried={}", menu.getSlot(0).getItem(), menu.getCarried());
        if (menu.getSlot(0).getItem().getItem() != Items.OAK_PLANKS)
        {
            failures.add("placing oak planks into input 0 was refused (no group)");
        }
        menu.setCarried(ItemStack.EMPTY);
        menu.getSlot(0).set(ItemStack.EMPTY);

        // 2) shift-click oak planks from the player's first main-inventory slot.
        final int playerFirst = output + 1;
        menu.getSlot(playerFirst).set(new ItemStack(Items.OAK_PLANKS, 8));
        menu.clicked(playerFirst, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, player);
        int movedToInputs = 0;
        for (int i = 0; i < inputs; i++)
        {
            movedToInputs += menu.getSlot(i).getItem().getCount();
        }
        Log.getLogger().info("X-263-CUTTERIN quickmove from {} -> inputs hold {}, source {}", playerFirst, movedToInputs, menu.getSlot(playerFirst).getItem());
        if (movedToInputs == 0)
        {
            failures.add("shift-click of oak planks from the player inventory moved nothing into the inputs");
        }
        for (int i = 0; i < inputs; i++)
        {
            menu.getSlot(i).set(ItemStack.EMPTY);
        }
        menu.getSlot(playerFirst).set(ItemStack.EMPTY);

        // 3) select the first group whose first variant accepts oak planks in slot 0, like the screen does, then place.
        final java.util.Map<Identifier, java.util.List<ItemStack>> groups =
          com.ldtteam.domumornamentum.block.ModBlocks.getInstance().getOrComputeItemGroups();
        int groupIndex = 0;
        for (final java.util.Map.Entry<Identifier, java.util.List<ItemStack>> group : groups.entrySet())
        {
            menu.clickMenuButton(player, groupIndex);
            menu.clickMenuButton(player, groups.size());
            menu.setCarried(new ItemStack(Items.OAK_PLANKS, 8));
            menu.clicked(0, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player);
            final boolean placed = menu.getSlot(0).getItem().getItem() == Items.OAK_PLANKS;
            Log.getLogger().info("X-263-CUTTERIN group {} variant0={} placed={} output={}", group.getKey(), menu.getCurrentVariant(), placed, menu.outputInventorySlot.getItem());
            menu.setCarried(ItemStack.EMPTY);
            menu.getSlot(0).set(ItemStack.EMPTY);
            if (group.getKey().getPath().contains("shingle") && !placed)
            {
                failures.add("shingle group " + group.getKey() + " refused oak planks in input 0");
            }
            // Like 1.21: the vanilla-compat stairs only take materials without vanilla stairs (stairs_materials tag),
            // so oak planks are refused there and hay is accepted. This is the screen's default selection.
            if (group.getKey().getPath().equals("avanilla"))
            {
                if (placed)
                {
                    failures.add("vanilla-compat stairs accepted oak planks, which already have vanilla stairs");
                }
                menu.setCarried(new ItemStack(Items.HAY_BLOCK, 8));
                menu.clicked(0, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player);
                if (menu.getSlot(0).getItem().getItem() != Items.HAY_BLOCK)
                {
                    failures.add("vanilla-compat stairs refused hay, which is in stairs_materials");
                }
                menu.setCarried(ItemStack.EMPTY);
                menu.getSlot(0).set(ItemStack.EMPTY);
            }
            groupIndex++;
        }

        helper.assertTrue(failures.isEmpty(), "Architect's Cutter input clicks: " + failures);
        helper.succeed();
    }

    /**
     * Regression fixture for review MC-C01: breaking a rack or grave must drop its
     * contents. Since MC 1.21.5 the block entity is removed before
     * {@code affectNeighborsAfterRemoval}, so drops coded there silently vanished.
     * Huts share the same inventory path ({@code AbstractTileEntityColonyBuilding}
     * extends {@code TileEntityRack}).
     */
    public static void containerContentsDropOnBreak(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final java.util.Map<String, Integer> dropped = new java.util.LinkedHashMap<>();
        final Object[][] cases = {
          { "rack", ModBlocks.blockRack, new BlockPos(1, 1, 1), Items.DIAMOND },
          { "grave", ModBlocks.blockGrave, new BlockPos(5, 1, 1), Items.EMERALD } };
        for (final Object[] testCase : cases)
        {
            final String name = (String) testCase[0];
            final BlockPos relative = (BlockPos) testCase[2];
            final net.minecraft.world.item.Item item = (net.minecraft.world.item.Item) testCase[3];
            final BlockPos pos = helper.absolutePos(relative);
            // The grave sits 5 blocks into the test's neighbour cell and can land in a chunk the test holds no ticket
            // for. Entities in a chunk below full status are hidden from entity queries, so the drops would not be
            // found at all. Force the chunk so the count sees what was really dropped.
            level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
            helper.setBlock(relative, (net.minecraft.world.level.block.Block) testCase[1]);
            helper.assertTrue(level.getBlockEntity(pos) instanceof TileEntityRack, name + " has no rack-style block entity");
            final TileEntityRack container = (TileEntityRack) level.getBlockEntity(pos);
            container.getInventory().setStackInSlot(0, new ItemStack(item, 7));
            level.destroyBlock(pos, false);
        }
        // Retried every tick until the test times out, so a forced chunk that is still being promoted gets its tick.
        helper.succeedWhen(() -> {
            for (final Object[] testCase : cases)
            {
                final String name = (String) testCase[0];
                final BlockPos pos = helper.absolutePos((BlockPos) testCase[2]);
                final net.minecraft.world.item.Item item = (net.minecraft.world.item.Item) testCase[3];
                final int count = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(2))
                  .stream()
                  .map(net.minecraft.world.entity.item.ItemEntity::getItem)
                  .filter(stack -> stack.is(item))
                  .mapToInt(ItemStack::getCount)
                  .sum();
                dropped.put(name, count);
            }
            dropped.forEach((name, count) -> helper.assertTrue(count == 7, "Breaking the " + name + " dropped " + count + "/7 stored items"));
            Log.getLogger().info("MC-C01 dropped contents on break: {}", dropped);
        });
    }

    /**
     * Regression fixture for review MC-C04: citizen rail travel spawns a fresh {@code MinecoloniesMinecart} per trip.
     * An empty cart must clean itself up, and destroying one must not drop a vanilla minecart item (free carts).
     */
    public static void citizenMinecartCleanup(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos idlePos = helper.absolutePos(new BlockPos(1, 2, 1));
        final BlockPos brokenPos = helper.absolutePos(new BlockPos(5, 2, 1));

        final com.minecolonies.api.entity.other.MinecoloniesMinecart idle =
          com.minecolonies.api.entity.ModEntities.MINECART.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        idle.setPos(idlePos.getX() + 0.5, idlePos.getY(), idlePos.getZ() + 0.5);
        final boolean idleAdded = level.addFreshEntity(idle);

        final com.minecolonies.api.entity.other.MinecoloniesMinecart broken =
          com.minecolonies.api.entity.ModEntities.MINECART.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        broken.setPos(brokenPos.getX() + 0.5, brokenPos.getY(), brokenPos.getZ() + 0.5);
        level.addFreshEntity(broken);
        broken.hurtServer(level, level.damageSources().generic(), 100.0F);

        final int freeCarts = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(brokenPos).inflate(3))
          .stream()
          .map(net.minecraft.world.entity.item.ItemEntity::getItem)
          .filter(stack -> stack.is(Items.MINECART))
          .mapToInt(ItemStack::getCount)
          .sum();
        Log.getLogger().info("MC-C04 destroyed cart removed: {}, minecart items dropped: {}", broken.isRemoved(), freeCarts);
        helper.assertTrue(broken.isRemoved(), "Destroyed citizen minecart was not removed");
        helper.assertTrue(freeCarts == 0, "Destroying a citizen minecart dropped " + freeCarts + " minecart item(s)");

        helper.assertTrue(idleAdded, "Could not spawn the idle citizen minecart");
        // Retried every tick until the test times out; an empty cart should go on its first 20-tick check.
        helper.succeedWhen(() -> {
            helper.assertTrue(idle.isRemoved(), "Empty citizen minecart still in the world after " + idle.tickCount + " ticks");
            Log.getLogger().info("MC-C04 empty cart removed after {} ticks", idle.tickCount);
        });
    }

    /**
     * Regression fixture for review MC-C05: left-clicking a block with the lumberjack scepter sets position A (the selection end)
     * and must not break the block. The server break path consults {@code Item#canDestroyBlock} via NeoForge's break event.
     */
    public static void lumberjackScepterLeftClick(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos target = helper.absolutePos(new BlockPos(2, 2, 2));
        level.setBlockAndUpdate(target, Blocks.OAK_LOG.defaultBlockState());

        final ServerPlayer player = FakePlayerFactory.getMinecraft(level);
        final ItemStack scepter = new ItemStack(com.minecolonies.api.items.ModItems.scepterLumberjack);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, scepter);
        try
        {
            player.gameMode.destroyBlock(target);
        }
        finally
        {
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }

        final boolean survived = level.getBlockState(target).is(Blocks.OAK_LOG);
        final com.ldtteam.structurize.api.util.Tuple<BlockPos, BlockPos> bounds = com.ldtteam.structurize.items.AbstractItemWithPosSelector.getBounds(scepter);
        Log.getLogger().info("MC-C05 block survived left-click: {}, scepter pos A={}", survived, bounds.getB());
        helper.assertTrue(survived, "Left-clicking with the lumberjack scepter broke the block");
        helper.assertTrue(target.equals(bounds.getB()), "Left-clicking with the lumberjack scepter did not set position A (got " + bounds.getB() + ")");
        helper.succeed();
    }

    /**
     * Regression fixture for review BS-C7 (found on screen in the dev client): a plain (not sneaking) left-click with a Structurize
     * position selector (scan tool, caliper) must set the selection start and leave the block alone, like 1.21's canAttackBlock.
     * The port only did that while sneaking, so a plain left-click broke the block and set nothing.
     */
    public static void structurizePosSelectorLeftClick(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final ServerPlayer player = FakePlayerFactory.getMinecraft(level);
        player.setShiftKeyDown(false);
        final List<String> failures = new java.util.ArrayList<>();
        int x = 1;
        for (final net.minecraft.world.item.Item tool : List.of(com.ldtteam.structurize.items.ModItems.scanTool.get(),
          com.ldtteam.structurize.items.ModItems.caliper.get()))
        {
            final BlockPos target = helper.absolutePos(new BlockPos(x++, 2, 2));
            level.setBlockAndUpdate(target, Blocks.GOLD_BLOCK.defaultBlockState());
            final ItemStack stack = new ItemStack(tool);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
            try
            {
                player.gameMode.destroyBlock(target);
            }
            finally
            {
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
            final boolean survived = level.getBlockState(target).is(Blocks.GOLD_BLOCK);
            final BlockPos start = com.ldtteam.structurize.items.AbstractItemWithPosSelector.getBounds(stack).getA();
            final String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(tool).toString();
            Log.getLogger().info("BS-C7 {}: block survived plain left-click: {}, start pos={}", id, survived, start);
            if (!survived)
            {
                failures.add(id + " broke the block");
            }
            if (!target.equals(start))
            {
                failures.add(id + " did not set the start position (got " + start + ")");
            }
        }
        helper.assertTrue(failures.isEmpty(), String.join("; ", failures));
        helper.succeed();
    }

    /**
     * Regression fixture for review MC-C06: the citizen inventory menu built from the open packet must have the server's slot layout
     * even when the citizen (or its colony view) is not known on the receiving side yet. The server's content packet sets every
     * server slot by index, so a 0-slot menu threw IndexOutOfBounds and disconnected the client.
     */
    public static void citizenInventoryMenuLayout(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final ServerPlayer player = FakePlayerFactory.getMinecraft(level);
        final int citizenSlots = new com.minecolonies.api.inventory.InventoryCitizen("", false).getSlots();
        // What the server sends for a default citizen: citizen slots, 4 armor slots, 27 + 9 player slots.
        final int serverSlots = citizenSlots + 4 + 36;

        // Open data in the menu's wire format: colony id (unknown here), citizen id, citizen inventory size, work building level.
        final net.minecraft.network.RegistryFriendlyByteBuf buf =
          new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), level.registryAccess());
        buf.writeVarInt(Integer.MAX_VALUE).writeVarInt(1).writeVarInt(citizenSlots).writeVarInt(0);
        final com.minecolonies.api.inventory.container.ContainerCitizenInventory menu =
          com.minecolonies.api.inventory.container.ContainerCitizenInventory.fromFriendlyByteBuf(1, player.getInventory(), buf);

        final List<ItemStack> contents = new java.util.ArrayList<>(java.util.Collections.nCopies(serverSlots, ItemStack.EMPTY));
        contents.set(0, new ItemStack(Items.OAK_LOG, 3));
        Log.getLogger().info("MC-C06 menu without citizen has {} slots (server sends {})", menu.slots.size(), serverSlots);
        String error = null;
        try
        {
            menu.initializeContents(0, contents, ItemStack.EMPTY);
        }
        catch (final RuntimeException e)
        {
            error = e.toString();
        }
        helper.assertTrue(error == null, "Applying the server's menu contents failed: " + error);
        helper.assertTrue(menu.slots.size() == serverSlots, "Menu has " + menu.slots.size() + " slots, server sends " + serverSlots);
        helper.assertTrue(menu.getSlot(0).getItem().is(Items.OAK_LOG), "Citizen slot 0 did not receive the synced stack");
        helper.succeed();
    }

    /**
     * Regression fixture for review MC-C03: the server resolves survival placement handlers by id
     * ({@code BlueprintPlacementHandling#process}). If they are registered client-only, hut and town
     * hall placement is a silent no-op on a dedicated server. GameTest servers run as DEDICATED_SERVER.
     */
    public static void survivalPlacementHandlersRegistered(final GameTestHelper helper)
    {
        for (final String id : new String[] { new SurvivalHandler().getId(), new SuppliesHandler().getId() })
        {
            final boolean registered = com.ldtteam.structurize.storage.SurvivalBlueprintHandlers.getHandler(id) != null;
            Log.getLogger().info("MC-C03 survival handler '{}' registered on server: {}", id, registered);
            helper.assertTrue(registered, "Survival placement handler '" + id + "' is not registered on the server");
        }
        helper.succeed();
    }

    /**
     * Regression fixture for X-STZ121 upstream #852: a structure pack name received from a server is used as a folder under the
     * client's blueprints directory and that folder is deleted first. Names that resolve to the folder itself or its parent
     * ("", ".", "..") or exceed a file-name length must be rejected (empty), otherwise a server could wipe the game directory.
     */
    public static void structurizeSafePackName(final GameTestHelper helper)
    {
        final java.util.Map<String, String> expected = new java.util.LinkedHashMap<>();
        expected.put("..", "");
        expected.put(".", "");
        expected.put("  ..  ", "");
        expected.put("", "");
        expected.put("a".repeat(256), "");
        expected.put("../evil", ".._evil");
        expected.put("Medieval Oak", "Medieval Oak");
        for (final var entry : expected.entrySet())
        {
            final String actual = com.ldtteam.structurize.api.util.Utils.getSafePackName(entry.getKey());
            Log.getLogger().info("STZ121-852 getSafePackName('{}') -> '{}'", entry.getKey().length() > 20 ? entry.getKey().length() + " chars" : entry.getKey(), actual);
            helper.assertTrue(entry.getValue().equals(actual),
              "getSafePackName('" + entry.getKey() + "') should be '" + entry.getValue() + "' but was '" + actual + "'");
        }
        helper.succeed();
    }

    /**
     * Regression fixture for X-STZ121 upstream #847: a falling block (sand) over air gets its support block from the placement
     * context's solid block. Non-blueprint contexts (SimplePlacementContext: scan window resource list, assistant hammer, ticked
     * world operations) have no blueprint, so the support lookup must not dereference one.
     */
    public static void structurizeFallingBlockSupport(final GameTestHelper helper)
    {
        final BlockPos sandRel = new BlockPos(1, 4, 1);
        final BlockPos sandPos = helper.absolutePos(sandRel);
        final ServerLevel level = helper.getLevel();
        final BlockState sand = Blocks.SAND.defaultBlockState();
        final com.ldtteam.structurize.placement.handlers.placement.IPlacementHandler handler =
          com.ldtteam.structurize.placement.handlers.placement.PlacementHandlers.getHandler(level, sandPos, sand);
        helper.assertTrue(handler instanceof com.ldtteam.structurize.placement.handlers.placement.PlacementHandlers.FallingBlockPlacementHandler,
          "sand should use the falling block handler but got " + handler);
        final com.ldtteam.structurize.placement.SimplePlacementContext context =
          new com.ldtteam.structurize.placement.SimplePlacementContext(false, new PlacementSettings());
        final List<ItemStack> items;
        final com.ldtteam.structurize.placement.handlers.placement.IPlacementHandler.ActionProcessingResult result;
        try
        {
            items = handler.getRequiredItems(level, sandPos, sand, null, context);
            result = handler.handle(level, sandPos, sand, null, context);
        }
        catch (final RuntimeException e)
        {
            Log.getLogger().info("STZ121-847 falling block handler threw {}", e.toString());
            helper.fail("falling block handler threw " + e);
            return;
        }
        Log.getLogger().info("STZ121-847 required {} result {} below {}", items, result, level.getBlockState(sandPos.below()));
        helper.assertTrue(items.stream().anyMatch(s -> s.is(Items.SAND)), "required items should contain sand: " + items);
        helper.assertTrue(items.stream().anyMatch(s -> s.is(Items.DIRT)), "required items should contain the dirt support: " + items);
        helper.assertBlockPresent(Blocks.DIRT, sandRel.below());
        helper.assertBlockPresent(Blocks.SAND, sandRel);
        helper.succeed();
    }

    /**
     * Regression fixture for X-STZ121 upstream #855: the container handler places the block together with its block entity
     * data (then setPlacedBy) instead of placing a bare block first. Contents and custom name from the blueprint must survive.
     */
    public static void structurizeContainerPlacement(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos sourceRel = new BlockPos(1, 2, 1);
        final BlockPos targetRel = new BlockPos(3, 2, 1);
        helper.setBlock(sourceRel, Blocks.CHEST);
        final net.minecraft.world.level.block.entity.ChestBlockEntity source =
          helper.getBlockEntity(sourceRel, net.minecraft.world.level.block.entity.ChestBlockEntity.class);
        source.setItem(0, new ItemStack(Items.DIAMOND, 3));
        source.setItem(26, new ItemStack(Items.OAK_LOG, 64));
        final net.minecraft.nbt.CompoundTag data = source.saveWithFullMetadata(level.registryAccess());
        data.putString("CustomName", "STZ121 chest");
        final BlockState chest = Blocks.CHEST.defaultBlockState();
        final BlockPos targetPos = helper.absolutePos(targetRel);
        data.putInt("x", targetPos.getX());
        data.putInt("y", targetPos.getY());
        data.putInt("z", targetPos.getZ());
        final com.ldtteam.structurize.placement.handlers.placement.IPlacementHandler handler =
          com.ldtteam.structurize.placement.handlers.placement.PlacementHandlers.getHandler(level, targetPos, chest);
        helper.assertTrue(handler instanceof com.ldtteam.structurize.placement.handlers.placement.PlacementHandlers.ContainerPlacementHandler,
          "chest should use the container handler but got " + handler);
        final com.ldtteam.structurize.placement.handlers.placement.IPlacementHandler.ActionProcessingResult result =
          handler.handle(level, targetPos, chest, data, new com.ldtteam.structurize.placement.SimplePlacementContext(false, new PlacementSettings()));
        final net.minecraft.world.level.block.entity.ChestBlockEntity placed =
          helper.getBlockEntity(targetRel, net.minecraft.world.level.block.entity.ChestBlockEntity.class);
        Log.getLogger().info("STZ121-855 result {} slot0 {} slot26 {} name {}", result, placed.getItem(0), placed.getItem(26), placed.getCustomName());
        helper.assertTrue(result == com.ldtteam.structurize.placement.handlers.placement.IPlacementHandler.ActionProcessingResult.SUCCESS, "container placement should succeed, got " + result);
        helper.assertTrue(placed.getItem(0).is(Items.DIAMOND) && placed.getItem(0).getCount() == 3, "slot 0 lost: " + placed.getItem(0));
        helper.assertTrue(placed.getItem(26).is(Items.OAK_LOG) && placed.getItem(26).getCount() == 64, "slot 26 lost: " + placed.getItem(26));
        helper.assertTrue(placed.getCustomName() != null && "STZ121 chest".equals(placed.getCustomName().getString()), "custom name lost: " + placed.getCustomName());
        helper.succeed();
    }

    /**
     * Regression fixture for X-STZ121 upstream #714 (bug 1): the Structurize manager must drain more than one queued scan-tool
     * operation per world tick (up to maxOperationsPerTick apply calls), so rapid clicks on "remove" do not wait one tick each
     * and an operation that needs several apply passes is not limited to one pass per tick.
     */
    public static void structurizeManagerDrainsQueuePerTick(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final int[] applies = new int[4];
        final boolean[] done = new boolean[4];
        for (int i = 0; i < 4; i++)
        {
            final int index = i;
            final int needed = i == 3 ? 3 : 1;
            final com.ldtteam.structurize.util.ChangeStorage storage = new com.ldtteam.structurize.util.ChangeStorage(
              net.minecraft.network.chat.Component.literal("stz121-714-" + i), java.util.UUID.randomUUID());
            com.ldtteam.structurize.management.Manager.addToQueue(new com.ldtteam.structurize.operations.ITickedWorldOperation()
            {
                @Override
                public boolean apply(final ServerLevel world)
                {
                    applies[index]++;
                    done[index] = applies[index] >= needed;
                    return done[index];
                }

                @Override
                public com.ldtteam.structurize.util.ChangeStorage getChangeStorage()
                {
                    return storage;
                }
            });
        }
        com.ldtteam.structurize.management.Manager.onWorldTick(level);
        Log.getLogger().info("STZ121-714 after one tick applies {} done {}", java.util.Arrays.toString(applies), java.util.Arrays.toString(done));
        if (!(done[0] && done[1] && done[2] && done[3]))
        {
            // Let the regular server ticks drain the rest so later tests start with an empty queue.
            throw helper.assertionException("one manager tick finished only " + java.util.Arrays.toString(done)
              + " of 4 queued operations (apply calls " + java.util.Arrays.toString(applies) + ")");
        }
        helper.succeed();
    }

    /**
     * Regression fixture for X-STZ121 upstream #699: Structurize's resource lists (ItemStackUtils, used for builder
     * requirements) must read contents like 1.21: a shulker box inside a chest also counts its own contents, block entities
     * that keep their item under another key than "Items" (decorated pot, jukebox) count it, a glow item frame needs a glow
     * item frame, an inventory-less vehicle (plain minecart) needs its item, and a mob never needs its spawn egg.
     */
    public static void structurizeItemExtraction(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final List<String> failures = new java.util.ArrayList<>();
        final java.util.function.Function<List<ItemStack>, String> describe = stacks -> stacks.stream()
          .map(s -> s.getCount() + "x" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()))
          .sorted()
          .collect(java.util.stream.Collectors.joining(","));
        final java.util.function.BiFunction<BlockPos, net.minecraft.world.level.block.state.BlockState, String> scan = (rel, state) -> {
            final BlockPos pos = helper.absolutePos(rel);
            final com.ldtteam.structurize.blueprints.v1.Blueprint blueprint = com.ldtteam.structurize.blueprints.v1.BlueprintUtil.createBlueprint(
              level, pos, false, (short) 1, (short) 1, (short) 1, "stz699", java.util.Optional.empty());
            return describe.apply(com.ldtteam.structurize.api.util.ItemStackUtils.getItemStacksOfTileEntity(blueprint.getTileEntities()[0][0][0], state));
        };
        final java.util.function.BiConsumer<String, String[]> check = (what, actualExpected) -> {
            Log.getLogger().info("STZ699 {} -> {}", what, actualExpected[0]);
            if (!actualExpected[0].equals(actualExpected[1]))
            {
                failures.add(what + ": got [" + actualExpected[0] + "] expected [" + actualExpected[1] + "]");
            }
        };

        // 1. Chest holding a filled shulker box: the shulker's own contents are part of the requirement.
        final ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(net.minecraft.core.component.DataComponents.CONTAINER,
          net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 5))));
        final BlockPos chestPos = new BlockPos(1, 1, 1);
        level.setBlockAndUpdate(helper.absolutePos(chestPos), Blocks.CHEST.defaultBlockState());
        if (level.getBlockEntity(helper.absolutePos(chestPos)) instanceof final ChestBlockEntity chest)
        {
            chest.setItem(0, shulker.copy());
            chest.setItem(1, new ItemStack(Items.STONE, 3));
        }
        check.accept("chest with shulker", new String[] {scan.apply(chestPos, Blocks.CHEST.defaultBlockState()),
          "1xminecraft:shulker_box,3xminecraft:stone,5xminecraft:diamond"});

        // 2. Decorated pot and jukebox keep their single item outside "Items".
        final BlockPos potPos = new BlockPos(2, 1, 1);
        level.setBlockAndUpdate(helper.absolutePos(potPos), Blocks.DECORATED_POT.defaultBlockState());
        if (level.getBlockEntity(helper.absolutePos(potPos)) instanceof final net.minecraft.world.level.block.entity.DecoratedPotBlockEntity pot)
        {
            pot.setTheItem(new ItemStack(Items.APPLE, 2));
        }
        check.accept("decorated pot", new String[] {scan.apply(potPos, Blocks.DECORATED_POT.defaultBlockState()), "2xminecraft:apple"});

        final BlockPos jukeboxPos = new BlockPos(3, 1, 1);
        level.setBlockAndUpdate(helper.absolutePos(jukeboxPos), Blocks.JUKEBOX.defaultBlockState());
        if (level.getBlockEntity(helper.absolutePos(jukeboxPos)) instanceof final net.minecraft.world.level.block.entity.JukeboxBlockEntity jukebox)
        {
            jukebox.setSongItemWithoutPlaying(new ItemStack(Items.MUSIC_DISC_CAT));
        }
        check.accept("jukebox", new String[] {scan.apply(jukeboxPos, level.getBlockState(helper.absolutePos(jukeboxPos))),
          "1xminecraft:music_disc_cat"});

        // 3. Entities.
        final BlockPos entityPos = helper.absolutePos(new BlockPos(1, 2, 2));
        final net.minecraft.world.entity.decoration.GlowItemFrame glowFrame =
          net.minecraft.world.entity.EntityTypes.GLOW_ITEM_FRAME.create(level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
        glowFrame.setItem(new ItemStack(Items.EMERALD));
        check.accept("glow item frame", new String[] {describe.apply(
          com.ldtteam.structurize.api.util.ItemStackUtils.getListOfStackForEntity(glowFrame, entityPos)),
          "1xminecraft:emerald,1xminecraft:glow_item_frame"});

        final net.minecraft.world.entity.vehicle.minecart.AbstractMinecart cart =
          net.minecraft.world.entity.EntityTypes.MINECART.create(level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
        check.accept("minecart", new String[] {describe.apply(
          com.ldtteam.structurize.api.util.ItemStackUtils.getListOfStackForEntity(cart, entityPos)), "1xminecraft:minecart"});

        final net.minecraft.world.entity.vehicle.minecart.MinecartChest chestCart =
          net.minecraft.world.entity.EntityTypes.CHEST_MINECART.create(level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
        chestCart.setItem(0, shulker.copy());
        check.accept("chest minecart", new String[] {describe.apply(
          com.ldtteam.structurize.api.util.ItemStackUtils.getListOfStackForEntity(chestCart, entityPos)),
          "1xminecraft:chest_minecart,1xminecraft:shulker_box,5xminecraft:diamond"});

        final net.minecraft.world.entity.decoration.ArmorStand stand =
          net.minecraft.world.entity.EntityTypes.ARMOR_STAND.create(level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
        stand.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        check.accept("armor stand", new String[] {describe.apply(
          com.ldtteam.structurize.api.util.ItemStackUtils.getListOfStackForEntity(stand, entityPos)),
          "1xminecraft:armor_stand,1xminecraft:iron_helmet"});

        final net.minecraft.world.entity.monster.zombie.Zombie zombie =
          net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
        check.accept("zombie", new String[] {describe.apply(
          com.ldtteam.structurize.api.util.ItemStackUtils.getListOfStackForEntity(zombie, entityPos)), ""});

        Log.getLogger().info("STZ699 failures {}", failures);
        if (!failures.isEmpty())
        {
            throw helper.assertionException(String.join("; ", failures));
        }
        helper.succeed();
    }

    /**
     * Regression fixture for X-STZ121 upstream #721: BlockUtils.handleCorrectBlockPlacement (replace tool / shape and fill
     * operations) must apply the placed stack's data components to the new block entity, like vanilla block placement.
     * Without it a named chest loses its name and a Domum block placed from a textured stack loses its materials.
     */
    public static void structurizeReplaceAppliesItemComponents(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final net.neoforged.neoforge.common.util.FakePlayer player = FakePlayerFactory.getMinecraft(level);
        final List<String> failures = new ArrayList<>();

        final BlockPos chestRel = new BlockPos(1, 2, 1);
        helper.setBlock(chestRel, Blocks.STONE);
        final ItemStack chestStack = new ItemStack(Items.CHEST);
        chestStack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("STZ121 721 chest"));
        com.ldtteam.structurize.util.BlockUtils.handleCorrectBlockPlacement(level, player, chestStack,
          level.getBlockState(helper.absolutePos(chestRel)), helper.absolutePos(chestRel));
        final net.minecraft.world.level.block.entity.ChestBlockEntity chest =
          helper.getBlockEntity(chestRel, net.minecraft.world.level.block.entity.ChestBlockEntity.class);
        Log.getLogger().info("STZ121-721 chest name {}", chest.getCustomName());
        if (chest.getCustomName() == null || !"STZ121 721 chest".equals(chest.getCustomName().getString()))
        {
            failures.add("chest custom name lost: " + chest.getCustomName());
        }

        final BlockPos shingleRel = new BlockPos(3, 2, 1);
        helper.setBlock(shingleRel, Blocks.STONE);
        final Block shingle = com.ldtteam.domumornamentum.block.ModBlocks.getInstance()
          .getShingle(com.ldtteam.domumornamentum.shingles.ShingleHeightType.DEFAULT);
        final com.ldtteam.domumornamentum.client.model.data.MaterialTextureData.Builder builder =
          com.ldtteam.domumornamentum.client.model.data.MaterialTextureData.builder();
        for (final com.ldtteam.domumornamentum.block.IMateriallyTexturedBlockComponent component :
          ((com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock) shingle).getComponents())
        {
            builder.setComponent(component.getId(), Blocks.BIRCH_PLANKS);
        }
        final com.ldtteam.domumornamentum.client.model.data.MaterialTextureData data = builder.build();
        final ItemStack shingleStack = new ItemStack(shingle);
        shingleStack.set(com.ldtteam.domumornamentum.component.ModDataComponents.TEXTURE_DATA.get(), data);
        com.ldtteam.structurize.util.BlockUtils.handleCorrectBlockPlacement(level, player, shingleStack,
          level.getBlockState(helper.absolutePos(shingleRel)), helper.absolutePos(shingleRel));
        final net.minecraft.world.level.block.entity.BlockEntity shingleBe = level.getBlockEntity(helper.absolutePos(shingleRel));
        final Object placedData = shingleBe instanceof com.ldtteam.domumornamentum.entity.block.MateriallyTexturedBlockEntity mtbe
          ? mtbe.getTextureData() : shingleBe;
        Log.getLogger().info("STZ121-721 shingle block {} data {}", level.getBlockState(helper.absolutePos(shingleRel)), placedData);
        if (!data.equals(placedData))
        {
            failures.add("Domum shingle texture data lost: expected " + data + " got " + placedData);
        }

        if (!failures.isEmpty())
        {
            throw helper.assertionException("replace placement dropped item components: " + String.join("; ", failures));
        }
        helper.succeed();
    }

    /**
     * Regression fixture for X-STZ121 upstream #710: TransferStructurePackToClient must send exactly the zipped bytes. The
     * server zips into a growable heap buffer, whose backing array is longer than its content, so writing payload.array()
     * shipped the unused capacity too (and the release made a second encode fail).
     */
    public static void structurizePackTransferPayload(final GameTestHelper helper)
    {
        final io.netty.buffer.ByteBuf source = io.netty.buffer.Unpooled.buffer();
        final byte[] content = new byte[300];
        for (int i = 0; i < content.length; i++)
        {
            content[i] = (byte) (i * 31 + 7);
        }
        source.writeBytes(content);
        final com.ldtteam.structurize.network.messages.TransferStructurePackToClient message =
          new com.ldtteam.structurize.network.messages.TransferStructurePackToClient("stz121pack", source, true);
        final java.util.List<Integer> received = new java.util.ArrayList<>();
        for (int round = 0; round < 2; round++)
        {
            final net.minecraft.network.RegistryFriendlyByteBuf buf =
              new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
            try
            {
                message.toBytes(buf);
            }
            catch (final RuntimeException e)
            {
                helper.fail("encoding round " + round + " threw " + e);
                return;
            }
            final com.ldtteam.structurize.network.messages.TransferStructurePackToClient decoded =
              new com.ldtteam.structurize.network.messages.TransferStructurePackToClient(buf);
            final io.netty.buffer.ByteBuf payload;
            try
            {
                final java.lang.reflect.Field field =
                  com.ldtteam.structurize.network.messages.TransferStructurePackToClient.class.getDeclaredField("payload");
                field.setAccessible(true);
                payload = (io.netty.buffer.ByteBuf) field.get(decoded);
            }
            catch (final ReflectiveOperationException e)
            {
                helper.fail("cannot read payload: " + e);
                return;
            }
            final byte[] got = new byte[payload.readableBytes()];
            payload.getBytes(payload.readerIndex(), got);
            received.add(got.length);
            Log.getLogger().info("STZ121-710 round {} source capacity {} received {} bytes, leftover {}", round, source.capacity(), got.length, buf.readableBytes());
            helper.assertTrue(java.util.Arrays.equals(content, got), "round " + round + ": received " + got.length + " bytes, expected exactly the 300 written");
            helper.assertTrue(buf.readableBytes() == 0, "round " + round + ": " + buf.readableBytes() + " bytes left unread");
        }
        helper.succeed();
    }

    /**
     * Regression fixture for X-STZ121 upstream #723 (unify entity logic): block placement spawns the blueprint entities at that
     * position through the same rules as handleEntitySpawn. The port kept a second inline copy in handleBlockPlacement that
     * missed #652's rule (text displays paste only in plain creative paste, never in fancy/survival placement), so a fancy paste
     * (and builders) spawned schematic label text displays into the world. Item frames still spawn, and only once.
     */
    public static void structurizeEntityPlacementRules(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final java.util.function.BiFunction<net.minecraft.world.entity.Entity, net.minecraft.world.phys.Vec3, net.minecraft.nbt.CompoundTag> save = (entity, pos) -> {
            entity.setPos(pos.x, pos.y, pos.z);
            final net.minecraft.nbt.CompoundTag tag = com.ldtteam.structurize.util.EntityNbtHelper.save(entity, level.registryAccess());
            entity.discard();
            return tag;
        };
        final net.minecraft.world.entity.Display.TextDisplay label =
          net.minecraft.world.entity.EntityTypes.TEXT_DISPLAY.create(level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
        final net.minecraft.world.entity.decoration.ItemFrame frame =
          net.minecraft.world.entity.EntityTypes.ITEM_FRAME.create(level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
        frame.setItem(new ItemStack(Items.DIAMOND));
        final net.minecraft.nbt.CompoundTag labelTag = save.apply(label, new net.minecraft.world.phys.Vec3(0.5, 0.5, 0.5));
        final net.minecraft.nbt.CompoundTag frameTag = save.apply(frame, new net.minecraft.world.phys.Vec3(0.5, 0.5, 0.5));
        helper.assertTrue(labelTag != null && frameTag != null, "could not serialize fixture entities");

        final int[] results = new int[4];
        final String[] names = {"fancy creative labels", "fancy creative frames", "plain creative labels", "plain creative frames"};
        for (int run = 0; run < 2; run++)
        {
            final boolean fancy = run == 0;
            final BlockPos rel = new BlockPos(1 + run * 3, 2, 2);
            final BlockPos worldPos = helper.absolutePos(rel);
            final Blueprint blueprint = new Blueprint((short) 1, (short) 1, (short) 1)
              .setName("STZ121 entity rules")
              .setFileName("stz121_entity_rules")
              .setPackName("Minecolonies Original");
            blueprint.addBlockState(BlockPos.ZERO, Blocks.AIR.defaultBlockState());
            blueprint.setEntities(new net.minecraft.nbt.CompoundTag[] {labelTag.copy(), frameTag.copy()});
            blueprint.setCachePrimaryOffset(BlockPos.ZERO);
            final com.ldtteam.structurize.placement.structure.CreativeStructureHandler handler =
              new com.ldtteam.structurize.placement.structure.CreativeStructureHandler(level, worldPos, blueprint, new PlacementSettings(), fancy);
            final com.ldtteam.structurize.placement.StructurePlacer placer = new com.ldtteam.structurize.placement.StructurePlacer(handler);
            placer.getIterator().includeEntities();
            // Place the same position twice: the second pass must find the frame already there.
            for (int pass = 0; pass < 2; pass++)
            {
                placer.handleBlockPlacement(level, worldPos, null,
                  new com.ldtteam.structurize.util.BlockInfo(BlockPos.ZERO, Blocks.AIR.defaultBlockState(), null));
            }
            final net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(worldPos).inflate(1.5);
            results[run * 2] = level.getEntitiesOfClass(net.minecraft.world.entity.Display.TextDisplay.class, box).size();
            results[run * 2 + 1] = level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, box).size();
        }
        Log.getLogger().info("STZ121-723 {}={} {}={} {}={} {}={}", names[0], results[0], names[1], results[1], names[2], results[2], names[3], results[3]);
        helper.assertTrue(results[0] == 0, "fancy placement must not paste text display labels (1.21 #652), got " + results[0]);
        helper.assertTrue(results[1] == 1, "fancy placement should place the item frame exactly once, got " + results[1]);
        helper.assertTrue(results[2] == 1, "plain creative paste should paste the text display once, got " + results[2]);
        helper.assertTrue(results[3] == 1, "plain creative paste should place the item frame exactly once, got " + results[3]);
        helper.succeed();
    }

    /**
     * Regression fixture for X-STZ121 upstream #814 (Structurize #813): rotating a blueprint holding a tag substitution block
     * whose replacement carries a blank block-entity tag crashed 1.21 (CapturedBlock palette index). The port rotates through
     * BlockEntityTagSubstitution.ReplacementBlock instead; guard that blank and real replacement data both rotate cleanly.
     */
    public static void structurizeTagSubstitutionRotation(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos chestRel = new BlockPos(1, 2, 1);
        helper.setBlock(chestRel, Blocks.CHEST);
        final net.minecraft.world.level.block.entity.ChestBlockEntity chest =
          helper.getBlockEntity(chestRel, net.minecraft.world.level.block.entity.ChestBlockEntity.class);
        chest.setItem(4, new ItemStack(Items.EMERALD, 5));
        final net.minecraft.nbt.CompoundTag chestData = chest.saveWithFullMetadata(level.registryAccess());

        final BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState()
          .setValue(net.minecraft.world.level.block.StairBlock.FACING, net.minecraft.core.Direction.NORTH);
        final BlockState chestState = Blocks.CHEST.defaultBlockState()
          .setValue(net.minecraft.world.level.block.ChestBlock.FACING, net.minecraft.core.Direction.NORTH);

        final String[] cases = {"blank BE tag", "chest BE tag"};
        for (final String name : cases)
        {
            final boolean blank = name.startsWith("blank");
            final net.minecraft.nbt.CompoundTag compound = new net.minecraft.nbt.CompoundTag();
            compound.putString("id", com.ldtteam.structurize.blockentities.ModBlockEntities.TAG_SUBSTITUTION.getId().toString());
            new com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution.ReplacementBlock(
              blank ? stairs : chestState, blank ? null : chestData, new ItemStack(blank ? Items.OAK_STAIRS : Items.CHEST)).write(compound);
            if (blank)
            {
                // #813: the replacement was present but its block entity tag was "{}".
                compound.getCompoundOrEmpty("replacement").put("e", new net.minecraft.nbt.CompoundTag());
            }
            final com.ldtteam.structurize.blueprints.v1.Blueprint blueprint = new com.ldtteam.structurize.blueprints.v1.Blueprint((short) 1, (short) 1, (short) 1);
            blueprint.addBlockState(BlockPos.ZERO, com.ldtteam.structurize.blocks.ModBlocks.blockTagSubstitution.get().defaultBlockState());
            blueprint.getTileEntities()[0][0][0] = compound;
            blueprint.setCachePrimaryOffset(BlockPos.ZERO);
            try
            {
                blueprint.setRotationMirrorRelative(com.ldtteam.structurize.util.RotationMirror.R90, level);
            }
            catch (final RuntimeException e)
            {
                Log.getLogger().error("STZ121-814 " + name + " rotation threw", e);
                helper.fail(name + ": rotation threw " + e);
                return;
            }
            final net.minecraft.nbt.CompoundTag rotated = blueprint.getTileEntities()[0][0][0];
            helper.assertTrue(rotated != null, name + ": tag substitution data lost on rotation");
            final com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution.ReplacementBlock replacement =
              new com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution.ReplacementBlock(rotated);
            final BlockState expected = (blank ? stairs : chestState).rotate(net.minecraft.world.level.block.Rotation.CLOCKWISE_90);
            Log.getLogger().info("STZ121-814 {}: replacement {} be {}", name, replacement.getBlockState(), replacement.getBlockEntityTag());
            helper.assertTrue(replacement.getBlockState().equals(expected), name + ": replacement state " + replacement.getBlockState() + " expected " + expected);
            if (blank)
            {
                helper.assertTrue(replacement.getBlockEntityTag().isEmpty(), name + ": blank tag should stay empty, got " + replacement.getBlockEntityTag());
            }
            else
            {
                helper.assertTrue(replacement.getBlockEntityTag().toString().contains("minecraft:emerald"), name + ": chest contents lost: " + replacement.getBlockEntityTag());
            }
        }
        helper.succeed();
    }

    /**
     * Regression fixture for X-STZ121 #704: tag substitution replacements stored in blueprints must load. Shipped packs store them as
     * "replacement":{b:{Name,Properties},e,i} (pre-26.x block-state keys) and Structurize 1.21 after #704 writes
     * "captured_block":{state:{Name,Properties},entity,item}; both must give the captured block, not air.
     */
    public static void structurizeTagSubstitutionLegacyFormats(final GameTestHelper helper)
    {
        final Blueprint blueprint = StructurePacks.getBlueprintFuture("Medieval Spruce", "infrastructure/mineshafts/minerx3topright.blueprint").join();
        helper.assertTrue(blueprint != null, "pack blueprint could not be loaded");
        final String tagId = com.ldtteam.structurize.blockentities.ModBlockEntities.TAG_SUBSTITUTION.getId().toString();
        int total = 0;
        final List<String> air = new ArrayList<>();
        for (final net.minecraft.nbt.CompoundTag[][] plane : blueprint.getTileEntities())
        {
            for (final net.minecraft.nbt.CompoundTag[] row : plane)
            {
                for (final net.minecraft.nbt.CompoundTag tag : row)
                {
                    if (tag == null || !tagId.equals(tag.getStringOr("id", "")) || !tag.contains("replacement"))
                    {
                        continue;
                    }
                    total++;
                    final com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution.ReplacementBlock replacement =
                      new com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution.ReplacementBlock(tag);
                    if (replacement.getBlockState().isAir())
                    {
                        air.add(tag.getCompoundOrEmpty("replacement").getCompoundOrEmpty("b").toString());
                    }
                    else if (!replacement.getBlockEntityTag().isEmpty() && replacement.createBlockEntity(BlockPos.ZERO) == null)
                    {
                        air.add("block entity lost for " + replacement.getBlockState() + ": " + replacement.getBlockEntityTag());
                    }
                }
            }
        }
        Log.getLogger().info("STZ121-704 pack replacements: {} total, {} read as air, e.g. {}", total, air.size(), air.stream().limit(3).toList());
        helper.assertTrue(total > 0, "fixture blueprint has no tag substitution replacements");
        helper.assertTrue(air.isEmpty(), air.size() + " of " + total + " pack replacements read as air or lost their block entity, e.g. " + (air.isEmpty() ? "" : air.get(0)));

        // Structurize 1.21 (#704) format, as written by CapturedBlock.CODEC on 1.21.1.
        final net.minecraft.nbt.CompoundTag compound = new net.minecraft.nbt.CompoundTag();
        compound.putString("id", tagId);
        final net.minecraft.nbt.CompoundTag captured = new net.minecraft.nbt.CompoundTag();
        final net.minecraft.nbt.CompoundTag state = new net.minecraft.nbt.CompoundTag();
        state.putString("Name", "minecraft:oak_stairs");
        final net.minecraft.nbt.CompoundTag props = new net.minecraft.nbt.CompoundTag();
        props.putString("facing", "east");
        props.putString("half", "bottom");
        props.putString("shape", "straight");
        props.putString("waterlogged", "false");
        state.put("Properties", props);
        captured.put("state", state);
        final net.minecraft.nbt.CompoundTag item = new net.minecraft.nbt.CompoundTag();
        item.putString("id", "minecraft:oak_stairs");
        item.putInt("count", 1);
        captured.put("item", item);
        compound.put("captured_block", captured);
        final com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution.ReplacementBlock modern =
          new com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution.ReplacementBlock(compound);
        final BlockState expected = Blocks.OAK_STAIRS.defaultBlockState()
          .setValue(net.minecraft.world.level.block.StairBlock.FACING, net.minecraft.core.Direction.EAST);
        Log.getLogger().info("STZ121-704 captured_block: state {} item {}", modern.getBlockState(), modern.getItemStack());
        helper.assertTrue(modern.getBlockState().equals(expected), "captured_block state " + modern.getBlockState() + " expected " + expected);
        helper.assertTrue(modern.getItemStack().is(Items.OAK_STAIRS), "captured_block item lost: " + modern.getItemStack());
        helper.succeed();
    }

    /**
     * Regression fixture for review MC-C08: IItemHandlerCapProvider.wrap must return null for targets without an item capability
     * (1.21 behaviour, callers null-check), not throw from IItemHandler.of(null). A chest is the positive control.
     */
    public static void itemHandlerWrapNullCapability(final GameTestHelper helper)
    {
        final BlockPos signPos = new BlockPos(1, 2, 1);
        final BlockPos chestPos = new BlockPos(2, 2, 1);
        helper.setBlock(signPos, Blocks.OAK_SIGN);
        helper.setBlock(chestPos, Blocks.CHEST);
        final BlockEntity sign = helper.getBlockEntity(signPos, BlockEntity.class);
        final BlockEntity chest = helper.getBlockEntity(chestPos, BlockEntity.class);
        final net.minecraft.world.entity.Entity orb =
          helper.spawn(net.minecraft.world.entity.EntityTypes.EXPERIENCE_ORB, new BlockPos(1, 3, 2));

        final java.util.Map<String, java.util.function.Supplier<Object>> noInventory = new java.util.LinkedHashMap<>();
        noInventory.put("sign block entity", () -> IItemHandlerCapProvider.wrap(sign).getItemHandlerCap());
        noInventory.put("sign block entity (north)", () -> IItemHandlerCapProvider.wrap(sign).getItemHandlerCap(net.minecraft.core.Direction.NORTH));
        noInventory.put("experience orb (unsided)", () -> IItemHandlerCapProvider.wrap(orb, false).getItemHandlerCap());
        noInventory.put("experience orb (sided)", () -> IItemHandlerCapProvider.wrap(orb, true).getItemHandlerCap(net.minecraft.core.Direction.UP));
        noInventory.put("dirt item stack", () -> IItemHandlerCapProvider.wrap(new ItemStack(Items.DIRT)).getItemHandlerCap());
        for (final var entry : noInventory.entrySet())
        {
            final Object handler;
            try
            {
                handler = entry.getValue().get();
            }
            catch (final RuntimeException e)
            {
                Log.getLogger().info("MC-C08 {} threw {}", entry.getKey(), e.toString());
                helper.fail("wrap(" + entry.getKey() + ") threw " + e);
                return;
            }
            Log.getLogger().info("MC-C08 {} -> {}", entry.getKey(), handler);
            helper.assertTrue(handler == null, "wrap(" + entry.getKey() + ") should have no item handler but returned " + handler);
        }

        final Object chestHandler = IItemHandlerCapProvider.wrap(chest).getItemHandlerCap();
        Log.getLogger().info("MC-C08 chest -> {}", chestHandler);
        helper.assertTrue(chestHandler != null, "wrap(chest) lost its item handler");
        helper.succeed();
    }

    /**
     * Regression fixture for review CP-ENCH: every Enchanter loot table must hand out enchanted books that actually
     * carry stored enchantments. The port's datagen bound empty default components, so enchanting a book was a no-op
     * and all five tables generated bare books.
     */
    public static void enchanterBooksHaveEnchantments(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final net.minecraft.world.level.storage.loot.LootParams params =
          new net.minecraft.world.level.storage.loot.LootParams.Builder(level)
            .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.EMPTY);
        for (int buildingLevel = 1; buildingLevel <= 5; buildingLevel++)
        {
            final net.minecraft.resources.ResourceKey<net.minecraft.world.level.storage.loot.LootTable> key =
              net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE,
                Identifier.fromNamespaceAndPath(com.minecolonies.api.util.constant.Constants.MOD_ID, "recipes/enchanter" + buildingLevel));
            final net.minecraft.world.level.storage.loot.LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
            helper.assertTrue(table != net.minecraft.world.level.storage.loot.LootTable.EMPTY, "Missing loot table " + key.identifier());
            int books = 0;
            int enchanted = 0;
            final java.util.Set<String> seen = new java.util.TreeSet<>();
            for (int roll = 0; roll < 200; roll++)
            {
                for (final ItemStack stack : table.getRandomItems(params))
                {
                    books++;
                    final net.minecraft.world.item.enchantment.ItemEnchantments stored =
                      stack.getOrDefault(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS,
                        net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
                    if (stack.is(Items.ENCHANTED_BOOK) && !stored.isEmpty())
                    {
                        enchanted++;
                        stored.keySet().forEach(e -> seen.add(e.getRegisteredName()));
                    }
                }
            }
            Log.getLogger().info("CP-ENCH enchanter{}: {} books, {} with stored enchantments, {} distinct: {}", buildingLevel, books, enchanted, seen.size(), seen);
            helper.assertTrue(books > 0 && enchanted == books,
              "enchanter" + buildingLevel + ": only " + enchanted + " of " + books + " books carry stored enchantments");
        }
        helper.succeed();
    }

    /**
     * Regression fixture for review CP-MATCH: the generated item-matching table (compatibility/itemnbtmatching) decides which
     * components a colony request compares. The port's table skipped two creative tabs (functional blocks, ingredients) that
     * failed to populate in datagen, and dropped enchantments/repair cost for tools without the 26.x enchantable/repairable
     * components, so a request accepted an enchanted/anvil-worked tool or a filled furnace as a plain one.
     */
    public static void itemNbtMatchingTable(final GameTestHelper helper)
    {
        final net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> efficiency = helper.getLevel().registryAccess()
          .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY);

        final ItemStack workedBow = new ItemStack(Items.BOW);
        workedBow.set(net.minecraft.core.component.DataComponents.REPAIR_COST, 3);
        final ItemStack enchantedShears = new ItemStack(Items.SHEARS);
        enchantedShears.enchant(efficiency, 1);
        final ItemStack filledFurnace = new ItemStack(Items.FURNACE);
        filledFurnace.set(net.minecraft.core.component.DataComponents.CONTAINER,
          net.minecraft.world.item.component.ItemContainerContents.fromItems(java.util.List.of(new ItemStack(Items.COAL))));
        // Paintings come from the functional-blocks tab, which failed to populate in the port's datagen.
        final net.minecraft.core.Registry<net.minecraft.world.entity.decoration.painting.PaintingVariant> paintings =
          helper.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.PAINTING_VARIANT);
        final ItemStack kebab = new ItemStack(Items.PAINTING);
        kebab.set(net.minecraft.core.component.DataComponents.PAINTING_VARIANT,
          paintings.getOrThrow(net.minecraft.world.entity.decoration.painting.PaintingVariants.KEBAB));
        final ItemStack aztec = new ItemStack(Items.PAINTING);
        aztec.set(net.minecraft.core.component.DataComponents.PAINTING_VARIANT,
          paintings.getOrThrow(net.minecraft.world.entity.decoration.painting.PaintingVariants.AZTEC));

        final java.util.List<String> wronglyMatched = new java.util.ArrayList<>();
        final java.util.Map<String, ItemStack[]> cases = new java.util.LinkedHashMap<>();
        cases.put("bow with repair cost", new ItemStack[] {new ItemStack(Items.BOW), workedBow});
        cases.put("enchanted shears", new ItemStack[] {new ItemStack(Items.SHEARS), enchantedShears});
        cases.put("furnace with contents", new ItemStack[] {new ItemStack(Items.FURNACE), filledFurnace});
        cases.put("different painting variants", new ItemStack[] {kebab, aztec});
        cases.forEach((name, pair) -> {
            if (ItemStackUtils.compareItemStacksIgnoreStackSize(pair[0], pair[1], false, true))
            {
                wronglyMatched.add(name);
            }
        });
        Log.getLogger().info("CP-MATCH table has {} items; bow keys {}, furnace keys {}; wrongly matched: {}",
          ItemStackUtils.CHECKED_NBT_KEYS.size(), ItemStackUtils.CHECKED_NBT_KEYS.get(Items.BOW), ItemStackUtils.CHECKED_NBT_KEYS.get(Items.FURNACE), wronglyMatched);
        helper.assertTrue(ItemStackUtils.compareItemStacksIgnoreStackSize(new ItemStack(Items.BOW), new ItemStack(Items.BOW), false, true),
          "two plain bows no longer match");
        helper.assertTrue(wronglyMatched.isEmpty(), "plain stacks matched modified ones: " + wronglyMatched);
        helper.succeed();
    }

    /**
     * Regression fixture for review CP-ARMOR: since 1.21.4 worn armor is drawn from an equipment asset
     * (assets/<ns>/equipment/<asset>.json) whose layers point at textures/entity/equipment/<layer>/<texture>.png. The port
     * kept only the 1.21 textures/models/armor/*_layer_N.png files, so every MineColonies armor piece rendered untextured.
     * Checks each MineColonies item with an equippable asset has the asset file, the layer its slot draws, and that layer's
     * texture in the mod's client resources.
     */
    public static void armorEquipmentAssets(final GameTestHelper helper)
    {
        final java.util.List<String> problems = new java.util.ArrayList<>();
        int checked = 0;
        try (final net.minecraft.server.packs.PackResources pack = com.minecolonies.core.generation.ItemNbtCalculator.openModPack(Constants.MOD_ID))
        {
            for (final net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM)
            {
                final Identifier itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
                final net.minecraft.world.item.equipment.Equippable equippable =
                  new ItemStack(item).get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
                if (!itemId.getNamespace().equals(Constants.MOD_ID) || equippable == null || equippable.assetId().isEmpty())
                {
                    continue;
                }
                checked++;
                final Identifier asset = equippable.assetId().get().identifier();
                final String layer = equippable.slot() == net.minecraft.world.entity.EquipmentSlot.LEGS ? "humanoid_leggings" : "humanoid";
                final Identifier assetFile = asset.withPath(p -> "equipment/" + p + ".json");
                final net.minecraft.server.packs.resources.IoSupplier<java.io.InputStream> json =
                  pack.getResource(net.minecraft.server.packs.PackType.CLIENT_RESOURCES, assetFile);
                if (json == null)
                {
                    problems.add(itemId + ": missing " + assetFile);
                    continue;
                }
                try (final java.io.Reader reader = new java.io.InputStreamReader(json.get(), java.nio.charset.StandardCharsets.UTF_8))
                {
                    final com.google.gson.JsonObject layers = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("layers");
                    if (layers == null || !layers.has(layer) || layers.getAsJsonArray(layer).isEmpty())
                    {
                        problems.add(itemId + ": " + assetFile + " has no " + layer + " layer");
                        continue;
                    }
                    for (final com.google.gson.JsonElement entry : layers.getAsJsonArray(layer))
                    {
                        final Identifier texture = Identifier.parse(entry.getAsJsonObject().get("texture").getAsString())
                          .withPath(p -> "textures/entity/equipment/" + layer + "/" + p + ".png");
                        if (pack.getResource(net.minecraft.server.packs.PackType.CLIENT_RESOURCES, texture) == null)
                        {
                            problems.add(itemId + ": missing " + texture);
                        }
                    }
                }
            }
        }
        catch (final java.io.IOException e)
        {
            throw new IllegalStateException("Could not read equipment assets", e);
        }
        Log.getLogger().info("CP-ARMOR checked {} equippable MineColonies items; problems: {}", checked, problems);
        helper.assertTrue(checked > 0, "no MineColonies equippable items found");
        helper.assertTrue(problems.isEmpty(), "armor equipment assets broken: " + problems);
        helper.succeed();
    }

    /**
     * Regression fixture for review MC-C07: since 1.21.2 the server sends clients only the recipe types a mod requests, so
     * without JEI the Domum crafting window, restaurant menu and brewing lookups had no recipes. Also checks the restaurant's
     * dish lookup key is the item's registry id (1.21 behaviour), not its description id.
     */
    public static void clientRecipeSync(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final net.neoforged.neoforge.event.OnDatapackSyncEvent event =
          new net.neoforged.neoforge.event.OnDatapackSyncEvent(level.getServer().getPlayerList(), FakePlayerFactory.getMinecraft(level));
        com.minecolonies.core.event.DataPackSyncEventHandler.ServerEvents.onDataPackSync(event);
        final java.util.Set<net.minecraft.world.item.crafting.RecipeType<?>> sent = event.getRecipeTypesToSend();
        Log.getLogger().info("MC-C07 recipe types sent to client: {}", sent);
        for (final net.minecraft.world.item.crafting.RecipeType<?> type : java.util.List.of(
          net.minecraft.world.item.crafting.RecipeType.CRAFTING,
          net.minecraft.world.item.crafting.RecipeType.SMELTING,
          net.minecraft.world.item.crafting.RecipeType.BREWING,
          com.ldtteam.domumornamentum.recipe.ModRecipeTypes.ARCHITECTS_CUTTER.get()))
        {
            helper.assertTrue(sent.contains(type), "Recipe type " + type + " is not sent to clients");
        }

        for (final net.minecraft.world.item.Item dish : new net.minecraft.world.item.Item[] { Items.BREAD, Items.COOKED_BEEF })
        {
            final var key = com.minecolonies.api.crafting.RecipeUtils.itemRecipeKey(dish);
            final boolean found = level.recipeAccess().recipeMap().byKey(key) != null;
            Log.getLogger().info("MC-C07 restaurant recipe key {} found: {}", key.identifier(), found);
            helper.assertTrue(found, "No recipe for restaurant lookup key " + key.identifier());
        }
        helper.succeed();
    }

    /**
     * Regression fixture for review BS-C1: Structurize scan-tool state (bounds, anchor, name, slot storage) must persist on the
     * item stack. Since item data became immutable components, writing into a copied tag silently drops the change.
     */
    public static void structurizeToolDataPersists(final GameTestHelper helper)
    {
        final ItemStack tool = new ItemStack(com.ldtteam.structurize.items.ModItems.scanTool.get());
        final BlockPos start = new BlockPos(1, 2, 3);
        final BlockPos end = new BlockPos(4, 5, 6);
        final BlockPos anchor = new BlockPos(2, 3, 4);

        com.ldtteam.structurize.items.AbstractItemWithPosSelector.setBounds(tool, start, end);
        com.ldtteam.structurize.items.ItemScanTool.setAnchorPos(tool, anchor);
        com.ldtteam.structurize.items.ItemScanTool.setStructureName(tool, "bsc1");
        final com.ldtteam.structurize.api.util.Tuple<BlockPos, BlockPos> bounds = com.ldtteam.structurize.items.AbstractItemWithPosSelector.getBounds(tool);
        Log.getLogger().info("BS-C1 bounds={}..{} anchor={} name='{}'", bounds.getA(), bounds.getB(),
          com.ldtteam.structurize.items.ItemScanTool.getAnchorPos(tool), com.ldtteam.structurize.items.ItemScanTool.getStructureName(tool));
        helper.assertTrue(start.equals(bounds.getA()) && end.equals(bounds.getB()), "scan tool bounds were not saved on the stack");
        helper.assertTrue(anchor.equals(com.ldtteam.structurize.items.ItemScanTool.getAnchorPos(tool)), "scan tool anchor was not saved on the stack");
        helper.assertTrue("bsc1".equals(com.ldtteam.structurize.items.ItemScanTool.getStructureName(tool)), "scan tool name was not saved on the stack");

        // Slot switch as done by scroll / command-block copy: select slot 3 and store a slot there, then read it back from the stack.
        final com.ldtteam.structurize.util.ScanToolData data = new com.ldtteam.structurize.util.ScanToolData(
          tool.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag());
        data.moveTo(3);
        data.setCurrentSlotData(new com.ldtteam.structurize.util.ScanToolData.Slot("slot3",
          new com.ldtteam.structurize.client.rendertask.tasks.BoxPreviewData(end, start, java.util.Optional.empty())));
        com.ldtteam.structurize.items.ModItems.scanTool.get().loadSlot(data, tool);

        final com.ldtteam.structurize.util.ScanToolData reloaded = new com.ldtteam.structurize.util.ScanToolData(
          tool.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag());
        Log.getLogger().info("BS-C1 reloaded slot={} name='{}' anchor={}", reloaded.getCurrentSlotId(), reloaded.getCurrentSlotData().getName(),
          com.ldtteam.structurize.items.ItemScanTool.getAnchorPos(tool));
        helper.assertTrue(reloaded.getCurrentSlotId() == 3, "scan tool current slot was not saved on the stack");
        helper.assertTrue("slot3".equals(reloaded.getCurrentSlotData().getName()), "scan tool slot data was not saved on the stack");
        helper.assertTrue(com.ldtteam.structurize.items.ItemScanTool.getAnchorPos(tool) == null, "loading an anchor-less slot must clear the anchor");
        helper.succeed();
    }

    /**
     * Regression fixture for review BS-C3: blueprint-data block entities (Structurize tag substitution, MineColonies decoration
     * controller) must load the single-nested format that blueprints, rotation ({@code Blueprint#rotateWithMirror}) and
     * {@code BlueprintTagUtils} use, and must save it back in the same shape.
     */
    public static void blueprintDataBlockEntityFormat(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        final String dataKey = com.ldtteam.structurize.blockentities.interfaces.IBlueprintDataProviderBE.TAG_BLUEPRINTDATA;
        final Map<BlockPos, List<String>> tags = Map.of(BlockPos.ZERO, List.of("bsc3"));

        // Legacy/blueprint shape, written by the shared default writers onto a root compound.
        final com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution donor = new com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution(pos,
          com.ldtteam.structurize.blocks.ModBlocks.blockTagSubstitution.get().defaultBlockState());
        donor.setSchematicName("bsc3name");
        donor.setSchematicCorners(new BlockPos(-1, 0, -2), new BlockPos(3, 4, 5));
        donor.setPositionedTags(tags);
        donor.setPackName("bsc3pack");
        donor.setBlueprintPath("bsc3/path");
        final CompoundTag legacy = new CompoundTag();
        donor.writeSchematicDataToNBT(legacy);
        new com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution.ReplacementBlock(Blocks.STONE.defaultBlockState(), (CompoundTag) null,
          new ItemStack(Items.STONE)).write(legacy);
        Log.getLogger().info("BS-C3 legacy fixture={}", legacy);

        final com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution sub = new com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution(pos,
          com.ldtteam.structurize.blocks.ModBlocks.blockTagSubstitution.get().defaultBlockState());
        sub.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), legacy));
        Log.getLogger().info("BS-C3 substitution loaded name='{}' corners={} tags={} replacement={}", sub.getSchematicName(),
          sub.getSchematicCorners().getA(), sub.getPositionedTags(), sub.getReplacement().getBlockState());
        helper.assertTrue("bsc3name".equals(sub.getSchematicName()), "tag substitution lost the schematic name");
        helper.assertTrue(new BlockPos(-1, 0, -2).equals(sub.getSchematicCorners().getA()), "tag substitution lost the schematic corners");
        helper.assertTrue(tags.equals(sub.getPositionedTags()), "tag substitution lost its positioned tags");
        helper.assertTrue(sub.getReplacement().getBlockState().is(Blocks.STONE), "tag substitution lost its replacement block");

        final CompoundTag saved = sub.saveWithoutMetadata(level.registryAccess());
        Log.getLogger().info("BS-C3 substitution saved={}", saved);
        helper.assertTrue(saved.getCompoundOrEmpty(dataKey).contains(com.ldtteam.structurize.blockentities.interfaces.IBlueprintDataProviderBE.TAG_SCHEMATIC_NAME),
          "tag substitution saved blueprint data double-nested");
        helper.assertTrue(com.ldtteam.structurize.blockentities.interfaces.IBlueprintDataProviderBE.readTagPosMapFrom(saved.getCompoundOrEmpty(dataKey)).equals(tags),
          "BlueprintTagUtils cannot read the tags a tag substitution saves");
        helper.assertTrue(saved.getCompoundOrEmpty("replacement").contains("b"), "tag substitution saved its replacement double-nested");

        final TileEntityDecorationController deco = new TileEntityDecorationController(pos, ModBlocks.blockDecorationPlaceholder.defaultBlockState());
        deco.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), legacy));
        Log.getLogger().info("BS-C3 decoration controller loaded name='{}' pack='{}' path='{}' tags={}", deco.getSchematicName(), deco.getPackName(),
          deco.getBlueprintPath(), deco.getPositionedTags());
        helper.assertTrue("bsc3name".equals(deco.getSchematicName()), "decoration controller lost the schematic name");
        helper.assertTrue("bsc3pack".equals(deco.getPackName()) && "bsc3/path.blueprint".equals(deco.getBlueprintPath()), "decoration controller lost pack/path");
        helper.assertTrue(tags.equals(deco.getPositionedTags()), "decoration controller lost its positioned tags");
        final CompoundTag decoSaved = deco.saveWithoutMetadata(level.registryAccess());
        helper.assertTrue(decoSaved.getCompoundOrEmpty(dataKey).contains(com.ldtteam.structurize.blockentities.interfaces.IBlueprintDataProviderBE.TAG_SCHEMATIC_NAME),
          "decoration controller saved blueprint data double-nested");

        // Hut and plantation-field block entities share the same persistence path.
        final com.minecolonies.core.tileentities.TileEntityColonyBuilding hut = new com.minecolonies.core.tileentities.TileEntityColonyBuilding(pos,
          ModBlocks.blockHutBuilder.defaultBlockState());
        hut.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), legacy));
        Log.getLogger().info("BS-C3 hut loaded name='{}' tags={}", hut.getSchematicName(), hut.getPositionedTags());
        helper.assertTrue("bsc3name".equals(hut.getSchematicName()) && tags.equals(hut.getPositionedTags()), "hut block entity lost its blueprint data");
        helper.assertTrue(hut.saveWithoutMetadata(level.registryAccess()).getCompoundOrEmpty(dataKey)
          .contains(com.ldtteam.structurize.blockentities.interfaces.IBlueprintDataProviderBE.TAG_SCHEMATIC_NAME), "hut block entity saved blueprint data double-nested");

        final com.minecolonies.core.tileentities.TileEntityPlantationField field = new com.minecolonies.core.tileentities.TileEntityPlantationField(pos,
          ModBlocks.blockPlantationField.defaultBlockState());
        field.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), legacy));
        Log.getLogger().info("BS-C3 plantation field loaded name='{}' tags={}", field.getSchematicName(), field.getPositionedTags());
        helper.assertTrue("bsc3name".equals(field.getSchematicName()) && tags.equals(field.getPositionedTags()), "plantation field lost its blueprint data");
        final com.minecolonies.core.tileentities.TileEntityPlantationField fieldDirect = new com.minecolonies.core.tileentities.TileEntityPlantationField(pos,
          ModBlocks.blockPlantationField.defaultBlockState());
        fieldDirect.readSchematicDataFromNBT(legacy);
        helper.assertTrue(tags.equals(fieldDirect.getPositionedTags()), "plantation field readSchematicDataFromNBT ignores blueprint tile data");
        helper.succeed();
    }

    /**
     * Regression fixture for review BS-C5: Structurize must (de)serialize items that reference datapack registries (enchantments,
     * banner patterns, trims) with the live registries. With only the built-in registries these items were dropped from scanned
     * blueprints, broke the builder's chest-content lookup, and could not be sent in Structurize packets.
     */
    public static void structurizeDynamicRegistryItems(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> sharpness = level.registryAccess()
          .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
          .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS);
        final ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.enchant(sharpness, 3);
        final List<String> failures = new java.util.ArrayList<>();

        // 1. Item NBT helpers (tag substitution replacement item, builder resource lists).
        try
        {
            final ItemStack parsed = com.ldtteam.structurize.api.util.ItemStackUtils.getItemStackFromNbt(
              com.ldtteam.structurize.api.util.ItemStackUtils.writeToNbt(sword));
            Log.getLogger().info("BS-C5 item nbt round trip -> {} {}", parsed, parsed.getEnchantments());
            if (!ItemStack.isSameItemSameComponents(sword, parsed))
            {
                failures.add("item nbt round trip lost the enchantment (" + parsed + ")");
            }
        }
        catch (final RuntimeException e)
        {
            failures.add("item nbt round trip threw " + e);
        }

        // 2. Scan a chest holding the sword, then read the chest contents back from the blueprint like the builder does.
        final BlockPos chestPos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        if (level.getBlockEntity(chestPos) instanceof final ChestBlockEntity chest)
        {
            chest.setItem(0, sword.copy());
        }
        try
        {
            final com.ldtteam.structurize.blueprints.v1.Blueprint blueprint = com.ldtteam.structurize.blueprints.v1.BlueprintUtil.createBlueprint(
              level, chestPos, false, (short) 1, (short) 1, (short) 1, "bsc5", java.util.Optional.empty());
            final CompoundTag chestTag = blueprint.getTileEntities()[0][0][0];
            final List<ItemStack> contents = com.ldtteam.structurize.api.util.ItemStackUtils.getItemStacksOfTileEntity(chestTag,
              Blocks.CHEST.defaultBlockState());
            Log.getLogger().info("BS-C5 scanned chest tag={} contents={}", chestTag, contents);
            if (contents.size() != 1 || !ItemStack.isSameItemSameComponents(sword, contents.get(0)))
            {
                failures.add("scanned chest lost the enchanted sword (" + contents + ")");
            }
        }
        catch (final RuntimeException e)
        {
            failures.add("scanning / reading the chest threw " + e);
        }

        // 3. Tag substitution replacement block holding that chest.
        try
        {
            final com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution.ReplacementBlock replacement =
              new com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution.ReplacementBlock(Blocks.CHEST.defaultBlockState(),
                level.getBlockEntity(chestPos), new ItemStack(Items.CHEST));
            final BlockEntity restored = replacement.createBlockEntity(chestPos);
            final ItemStack restoredItem = restored instanceof final ChestBlockEntity restoredChest ? restoredChest.getItem(0) : ItemStack.EMPTY;
            Log.getLogger().info("BS-C5 replacement block restored {}", restoredItem);
            if (!ItemStack.isSameItemSameComponents(sword, restoredItem))
            {
                failures.add("tag substitution replacement lost the chest's enchanted sword (" + restoredItem + ")");
            }
        }
        catch (final RuntimeException e)
        {
            failures.add("tag substitution replacement threw " + e);
        }

        // 4. Network item codec used by AbsorbBlock / ReplaceBlock messages.
        final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try
        {
            com.ldtteam.structurize.util.ItemStackNbtHelper.writeNetworkStack(buf, sword);
            final ItemStack decoded = com.ldtteam.structurize.util.ItemStackNbtHelper.readNetworkStack(buf);
            Log.getLogger().info("BS-C5 network round trip -> {}", decoded);
            if (!ItemStack.isSameItemSameComponents(sword, decoded))
            {
                failures.add("network round trip lost the enchantment (" + decoded + ")");
            }
        }
        catch (final RuntimeException e)
        {
            failures.add("network round trip threw " + e);
        }
        finally
        {
            buf.release();
        }

        Log.getLogger().info("BS-C5 failures={}", failures);
        helper.assertTrue(failures.isEmpty(), "dynamic-registry items dropped: " + failures);
        helper.succeed();
    }

    private static final class TestCreateColonyMessage extends CreateColonyMessage
    {
        private TestCreateColonyMessage(
          final BlockPos townHall,
          final boolean claim,
          final String colonyName,
          final String packName,
          final String pathName)
        {
            super(townHall, claim, colonyName, packName, pathName);
        }

        private void execute(final ServerPlayer player)
        {
            onExecute(null, player);
        }
    }

    /**
     * Server-only coverage for the bundled Multi-Piston dependency.  This is
     * deliberately a small fixture: it proves the packet authority boundary,
     * a real block-entity move, and persisted configuration. Obstruction is
     * covered by the companion {@link #multiPistonObstruction(GameTestHelper)}
     * fixture so a failed push cannot silently destroy an inventory.
     */
    public static void multiPistonLifecycle(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos relativePistonPos = new BlockPos(5, 1, 5);
        final BlockPos pistonPos = helper.absolutePos(relativePistonPos);
        final BlockPos relativeWestPos = relativePistonPos.relative(Direction.WEST);
        final BlockPos relativeEastPos = relativePistonPos.relative(Direction.EAST);
        final BlockPos westPos = helper.absolutePos(relativeWestPos);
        final BlockPos eastPos = helper.absolutePos(relativeEastPos);

        helper.setBlock(relativePistonPos, com.ldtteam.multipiston.ModBlocks.multipiston.value().defaultBlockState());
        helper.setBlock(relativeEastPos, Blocks.CHEST.defaultBlockState());
        helper.assertBlockPresent(com.ldtteam.multipiston.ModBlocks.multipiston.value(), relativePistonPos);
        helper.assertBlockPresent(Blocks.CHEST, relativeEastPos);

        final BlockEntity blockEntity = level.getBlockEntity(pistonPos);
        helper.assertTrue(blockEntity instanceof TileEntityMultiPiston,
          "multipiston placement did not create its block entity");
        final TileEntityMultiPiston piston = (TileEntityMultiPiston) blockEntity;
        final BlockEntity chestEntity = level.getBlockEntity(eastPos);
        helper.assertTrue(chestEntity instanceof ChestBlockEntity,
          "chest fixture did not create its block entity");
        final ChestBlockEntity chest = (ChestBlockEntity) chestEntity;
        chest.setItem(0, new ItemStack(Items.DIAMOND, 3));

        piston.setInput(Direction.WEST);
        piston.setOutput(Direction.EAST);
        piston.setRange(1);
        piston.setSpeed(TileEntityMultiPiston.MAX_SPEED);
        helper.assertTrue(piston.getRange() == 1, "test piston range was not applied");

        // Use the server-only fake player used by the colony fixture.  A
        // networked mock player fires Structurize's login sync path, which is
        // a separate channel-direction fixture and would obscure this test.
        final ServerPlayer player = FakePlayerFactory.getMinecraft(level);
        final IPayloadContext context = new ImmediatePayloadContext(player);

        // A normal serverbound update is accepted and applied on the main
        // thread, including a bounded range and speed.
        MultiPistonChangeMessage.onExecute(
          new MultiPistonChangeMessage(pistonPos, Direction.WEST, Direction.EAST, 1, TileEntityMultiPiston.MIN_SPEED),
          context);
        helper.assertTrue(piston.getSpeed() == TileEntityMultiPiston.MIN_SPEED,
          "valid multipiston packet did not update the server block entity");

        // Decode an invalid direction ordinal through the real payload codec.
        // The decoder must produce a rejected message, not throw on the server.
        final RegistryFriendlyByteBuf malformedBuffer = new RegistryFriendlyByteBuf(
          Unpooled.buffer(), level.registryAccess());
        malformedBuffer.writeBlockPos(pistonPos);
        malformedBuffer.writeInt(Integer.MAX_VALUE);
        malformedBuffer.writeInt(Direction.EAST.ordinal());
        malformedBuffer.writeInt(1);
        malformedBuffer.writeInt(TileEntityMultiPiston.MIN_SPEED);
        final MultiPistonChangeMessage malformed = MultiPistonChangeMessage.CODEC.decode(malformedBuffer);
        MultiPistonChangeMessage.onExecute(malformed, context);
        helper.assertTrue(piston.getSpeed() == TileEntityMultiPiston.MIN_SPEED,
          "malformed multipiston packet changed server state");

        // Out-of-range values are rejected before any world mutation.
        MultiPistonChangeMessage.onExecute(
          new MultiPistonChangeMessage(pistonPos, Direction.WEST, Direction.EAST,
            TileEntityMultiPiston.MAX_RANGE + 1, TileEntityMultiPiston.MIN_SPEED),
          context);
        helper.assertTrue(piston.getRange() == 1,
          "out-of-range multipiston packet changed server state");

        // Trigger one legal movement.  The chest is in the moveable-entity
        // tag and must retain its inventory when copied to the destination.
        piston.handleRedstone(true);
        piston.tick();
        helper.assertTrue(level.getBlockState(eastPos).isAir(),
          "multipiston did not clear the source block: state=" + level.getBlockState(eastPos)
            + ", pistonState=" + level.getBlockState(pistonPos)
            + ", levelAttached=" + (piston.getLevel() == level)
            + ", on=" + piston.isOn()
            + ", range=" + piston.getRange()
            + ", speed=" + piston.getSpeed()
            + ", input=" + piston.getInput()
            + ", output=" + piston.getOutput()
            + ", pushReaction=" + level.getBlockState(eastPos).getPistonPushReaction()
            + ", moveableTag=" + level.getBlockState(eastPos).is(com.ldtteam.multipiston.ModBlocks.MOVEABLE_ENTITY_BLOCKS));
        helper.assertBlockPresent(Blocks.CHEST, relativeWestPos);
        final BlockEntity movedEntity = level.getBlockEntity(westPos);
        helper.assertTrue(movedEntity instanceof Container,
          "multipiston did not create the destination inventory block entity");
        helper.assertTrue(((Container) movedEntity).getItem(0).is(Items.DIAMOND)
            && ((Container) movedEntity).getItem(0).getCount() == 3,
          "multipiston lost chest inventory contents during movement");

        // Verify the same configuration through the component/NBT update path
        // used by client updates and save/reload.
        final TileEntityMultiPiston restored = new TileEntityMultiPiston(
          pistonPos, com.ldtteam.multipiston.ModBlocks.multipiston.value().defaultBlockState());
        restored.handleUpdateTag(TagValueInput.create(
          ProblemReporter.DISCARDING,
          level.registryAccess(),
          piston.getUpdateTag(level.registryAccess())));
        helper.assertTrue(restored.getInput() == Direction.WEST
            && restored.getOutput() == Direction.EAST
            && restored.getRange() == 1
            && restored.getSpeed() == TileEntityMultiPiston.MIN_SPEED,
          "multipiston configuration did not survive NBT round-trip");

        // Exercise the actual persistent block-entity save/load entry points
        // as well as the client update-tag path above. The two paths must
        // preserve the same legal configuration across a world reload.
        final TagValueOutput persistentOutput = TagValueOutput.createWithContext(
          ProblemReporter.DISCARDING,
          level.registryAccess());
        piston.saveWithFullMetadata(persistentOutput);
        final TileEntityMultiPiston persistent = new TileEntityMultiPiston(
          pistonPos, com.ldtteam.multipiston.ModBlocks.multipiston.value().defaultBlockState());
        persistent.loadWithComponents(TagValueInput.create(
          ProblemReporter.DISCARDING,
          level.registryAccess(),
          persistentOutput.buildResult()));
        helper.assertTrue(persistent.getInput() == Direction.WEST
            && persistent.getOutput() == Direction.EAST
            && persistent.getRange() == 1
            && persistent.getSpeed() == TileEntityMultiPiston.MIN_SPEED,
          "multipiston persistent save/load did not preserve configuration");
        helper.succeed();
    }

    /**
     * The piston must leave both the source inventory and the obstruction
     * untouched when its destination is occupied. This is intentionally a
     * one-tick fixture: range one should attempt exactly one move and then
     * finish without deleting either block.
     */
    public static void multiPistonObstruction(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos relativePistonPos = new BlockPos(5, 1, 5);
        final BlockPos pistonPos = helper.absolutePos(relativePistonPos);
        final BlockPos relativeWestPos = relativePistonPos.relative(Direction.WEST);
        final BlockPos relativeEastPos = relativePistonPos.relative(Direction.EAST);
        final BlockPos westPos = helper.absolutePos(relativeWestPos);
        final BlockPos eastPos = helper.absolutePos(relativeEastPos);

        helper.setBlock(relativePistonPos, com.ldtteam.multipiston.ModBlocks.multipiston.value().defaultBlockState());
        helper.setBlock(relativeWestPos, Blocks.STONE.defaultBlockState());
        helper.setBlock(relativeEastPos, Blocks.CHEST.defaultBlockState());
        helper.assertBlockPresent(com.ldtteam.multipiston.ModBlocks.multipiston.value(), relativePistonPos);
        helper.assertBlockPresent(Blocks.STONE, relativeWestPos);
        helper.assertBlockPresent(Blocks.CHEST, relativeEastPos);

        final BlockEntity blockEntity = level.getBlockEntity(pistonPos);
        helper.assertTrue(blockEntity instanceof TileEntityMultiPiston,
          "obstruction fixture did not create its block entity");
        final TileEntityMultiPiston piston = (TileEntityMultiPiston) blockEntity;
        final BlockEntity chestEntity = level.getBlockEntity(eastPos);
        helper.assertTrue(chestEntity instanceof ChestBlockEntity,
          "obstruction fixture did not create its chest block entity");
        final ChestBlockEntity chest = (ChestBlockEntity) chestEntity;
        chest.setItem(0, new ItemStack(Items.DIAMOND, 7));

        piston.setInput(Direction.WEST);
        piston.setOutput(Direction.EAST);
        piston.setRange(1);
        piston.setSpeed(TileEntityMultiPiston.MIN_SPEED);
        piston.handleRedstone(true);
        piston.tick();

        helper.assertBlockPresent(Blocks.STONE, relativeWestPos);
        helper.assertBlockPresent(Blocks.CHEST, relativeEastPos);
        final BlockEntity remainingChestEntity = level.getBlockEntity(eastPos);
        helper.assertTrue(remainingChestEntity instanceof Container,
          "blocked multipiston removed the source inventory");
        helper.assertTrue(((Container) remainingChestEntity).getItem(0).is(Items.DIAMOND)
            && ((Container) remainingChestEntity).getItem(0).getCount() == 7,
          "blocked multipiston changed the source inventory");
        helper.assertTrue(level.getBlockState(westPos).is(Blocks.STONE),
          "blocked multipiston changed the obstruction block");
        helper.succeed();
    }

    private static final class ImmediatePayloadContext implements IPayloadContext
    {
        private final ServerPlayer player;

        private ImmediatePayloadContext(final ServerPlayer player)
        {
            this.player = player;
        }

        @Override
        public net.neoforged.neoforge.common.extensions.ICommonPacketListener listener()
        {
            return null;
        }

        @Override
        public net.minecraft.world.entity.player.Player player()
        {
            return player;
        }

        @Override
        public CompletableFuture<Void> enqueueWork(final Runnable task)
        {
            task.run();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> CompletableFuture<T> enqueueWork(final Supplier<T> task)
        {
            return CompletableFuture.completedFuture(task.get());
        }

        @Override
        public PacketFlow flow()
        {
            return PacketFlow.SERVERBOUND;
        }

        @Override
        public void handle(final net.minecraft.network.protocol.common.custom.CustomPacketPayload payload)
        {
        }

        @Override
        public void finishCurrentTask(final net.minecraft.server.network.ConfigurationTask.Type type)
        {
        }
    }

    /**
     * X-263-TESTSYNC: a world with MineColonies' GameTests loaded (any dev run) sends every test instance to
     * joining clients. Its type must be in the test_instance_type registry, otherwise encoding throws
     * "Unregistered holder" and the client never gets past the loading screen.
     */
    public static void gameTestInstanceTypeRegistered(final GameTestHelper helper)
    {
        final net.minecraft.core.RegistryAccess access = helper.getLevel().registryAccess();
        final net.minecraft.gametest.framework.GameTestInstance instance = access.lookupOrThrow(net.minecraft.core.registries.Registries.TEST_INSTANCE)
          .getValue(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "colony_lifecycle"));
        helper.assertTrue(instance != null, "Test instance minecolonies:colony_lifecycle not registered");
        helper.assertTrue(net.minecraft.core.registries.BuiltInRegistries.TEST_INSTANCE_TYPE.getKey(instance.codec()) != null,
          "MineColonies test instance type is not in the test_instance_type registry");
        final var encoded = net.minecraft.gametest.framework.GameTestInstance.DIRECT_CODEC.encodeStart(
          net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, access), instance);
        helper.assertTrue(encoded.isSuccess(), "Test instance cannot be encoded for clients: "
          + encoded.error().map(e -> e.message()).orElse(""));
        helper.succeed();
    }
}
