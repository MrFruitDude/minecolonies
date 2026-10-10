package com.minecolonies.core.gametest;

import com.google.common.reflect.TypeToken;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.jobs.ModJobs;
import com.minecolonies.api.colony.requestsystem.StandardFactoryController;
import com.minecolonies.api.colony.requestsystem.location.ILocation;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.MinimumStack;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.requestable.crafting.PublicCrafting;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.api.colony.requestsystem.requester.IRequester;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.requestsystem.token.StandardToken;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.crafting.RecipeStorage;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.api.util.constant.TypeConstants;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.BuildingResourcesModule;
import com.minecolonies.core.colony.buildings.modules.MinimumStockModule;
import com.minecolonies.core.colony.buildings.modules.WarehouseRequestQueueModule;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.requestsystem.resolvers.PublicWorkerCraftingProductionResolver;
import com.minecolonies.core.colony.requestsystem.resolvers.PublicWorkerCraftingRequestResolver;
import com.minecolonies.core.colony.requestsystem.resolvers.WarehouseRequestResolver;
import com.minecolonies.core.colony.requestsystem.resolvers.core.AbstractWarehouseRequestResolver;
import com.minecolonies.core.entity.ai.workers.service.EntityAIWorkDeliveryman;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Request system defect checks (glob {@code minecolonies:rs_*}). Every test is written so that it fails on the code
 * with the defect and passes on the fix.
 */
public final class RequestSystemGameTests
{
    private RequestSystemGameTests()
    {
    }

    // ------------------------------------------------------------------ helpers

    private static IBuilding place(final GameTestHelper helper, final IColony colony, final net.minecraft.world.level.block.Block block, final BlockPos rel, final String blueprint)
    {
        return MinecoloniesGameTests.placeProductionBuilding(helper, colony, block, rel, blueprint);
    }

    private static void forceChunks(final ServerLevel level, final BlockPos... positions)
    {
        for (final BlockPos pos : positions)
        {
            level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
        }
    }

    private static IToken<?> newToken(final IColony colony)
    {
        return colony.getRequestManager().getFactoryController().getNewInstance(TypeConstants.ITOKEN);
    }

    /**
     * Spawns a citizen, at the given position relative to the test, with no AI of its own: the test drives it.
     */
    private static ICitizenData spawn(final GameTestHelper helper, final IColony colony, final BlockPos rel)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos anchor = helper.absolutePos(rel);
        forceChunks(level, anchor);
        final ICitizenData citizen = colony.getCitizenManager().spawnOrCreateCivilian(null, level, List.of(anchor), true);
        helper.assertTrue(citizen != null && citizen.getEntity().isPresent(), "could not spawn a citizen at " + anchor);
        citizen.getEntity().get().setNoAi(true);
        return citizen;
    }

    @SuppressWarnings("unchecked")
    private static IRequestManager forwarding(final IRequestManager real, final String intercepted, final java.util.function.Function<Object[], Object> handler)
    {
        return (IRequestManager) Proxy.newProxyInstance(RequestSystemGameTests.class.getClassLoader(), new Class<?>[] {IRequestManager.class}, (proxy, method, args) -> {
            if (method.getName().equals(intercepted))
            {
                return handler.apply(args);
            }
            try
            {
                return method.invoke(real, args);
            }
            catch (final InvocationTargetException e)
            {
                throw e.getCause();
            }
        });
    }

    // ------------------------------------------------------------------ 1: batch size zero

    /**
     * {@code createRequestsForRecipe}: a recipe whose single execution does not fit the crafter's inventory used to
     * floor the batch size to 0 and then loop forever creating requests. The counting manager stops the runaway at 50
     * requests so a failure is reported instead of hanging the server.
     */
    public static void craftRecipeTooBigForOneBatch(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "rs-craft-zero");
        final IBuilding sawmill = place(helper, colony, ModBlocks.blockHutSawmill, new BlockPos(8, 1, 2), "craftsmanship/carpentry/sawmill1.blueprint");

        // One execution needs the output slot plus 30 unstackable ingredients: more than the 24 slots of a base crafter.
        final List<ItemStorage> inputs = new ArrayList<>();
        for (final Item item : BuiltInRegistries.ITEM)
        {
            if (inputs.size() < 30 && item != Items.AIR && new ItemStack(item).getMaxStackSize() == 1)
            {
                inputs.add(new ItemStorage(new ItemStack(item)));
            }
        }
        helper.assertTrue(inputs.size() == 30, "need 30 unstackable items, found " + inputs.size());
        final RecipeStorage recipe = RecipeStorage.builder()
          .withRecipeId(net.minecraft.resources.Identifier.fromNamespaceAndPath(Constants.MOD_ID, "gametest/rs_too_big"))
          .withInputs(inputs)
          .withPrimaryOutput(new ItemStack(Items.STICK, 1))
          .build();

        final ExposedCraftingResolver resolver = new ExposedCraftingResolver(sawmill.getRequester().getLocation(), newToken(colony));
        final AtomicInteger created = new AtomicInteger();
        final IRequestManager counting = forwarding(colony.getRequestManager(), "createRequest", args -> {
            if (created.incrementAndGet() > 50)
            {
                throw new IllegalStateException("runaway");
            }
            return new StandardToken();
        });

        final List<IToken<?>> result;
        try
        {
            result = resolver.batches(counting, recipe, 1, 1);
        }
        catch (final IllegalStateException e)
        {
            helper.fail("createRequestsForRecipe kept creating crafting requests (more than 50 for a request of 1): the batch size is 0");
            return;
        }
        helper.assertTrue(result == null, "a recipe that cannot fit one execution into the crafter must not be accepted, got " + result);
        helper.assertTrue(created.get() == 0, "no crafting request may be created for it, created " + created.get());
        helper.succeed();
    }

    private static final class ExposedCraftingResolver extends PublicWorkerCraftingRequestResolver
    {
        ExposedCraftingResolver(final ILocation location, final IToken<?> token)
        {
            super(location, token, ModJobs.sawmill.get());
        }

        List<IToken<?>> batches(final IRequestManager manager, final RecipeStorage recipe, final int count, final int minCount)
        {
            return createRequestsForRecipe(manager, recipe, count, minCount);
        }
    }

    // ------------------------------------------------------------------ 2: completed requests listed twice

    public static void completedRequestsAreListedOnce(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "rs-completed-once");
        final AbstractBuilding builder = (AbstractBuilding) place(helper, colony, ModBlocks.blockHutBuilder, new BlockPos(8, 1, 2), "fundamentals/builder1.blueprint");
        final ICitizenData citizen = colony.getCitizenManager().createAndRegisterCivilianData();
        final IRequestManager manager = colony.getRequestManager();

        final IToken<?> token = builder.createRequest(citizen, new Stack(new ItemStack(Items.DIRT), 4, 4), false);
        final IRequest<?> request = manager.getRequestForToken(token);
        helper.assertTrue(request != null, "request was not created");
        builder.onRequestedRequestComplete(manager, request);

        final Collection<IRequest<?>> all = builder.getCompletedRequestsOfCitizenOrBuilding(citizen);
        final List<IRequest<?>> filtered = builder.getCompletedRequestsOfCitizenOrBuilding(citizen, r -> true);
        helper.assertTrue(all.size() == 1, "expected exactly one completed request, got " + all.size());
        helper.assertTrue(filtered.size() == 1, "the filtered list returned " + filtered.size() + " entries for one completed request (every match is returned twice)");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 3: minimum stock removal

    public static void minimumStockRemovalCancelsItsRequest(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "rs-minstock-remove");
        forceChunks(helper.getLevel(), helper.absolutePos(new BlockPos(16, 1, 2)));
        final AbstractBuilding warehouse = (AbstractBuilding) place(helper, colony, ModBlocks.blockHutWareHouse, new BlockPos(16, 1, 2), "craftsmanship/storage/warehouse1.blueprint");
        final MinimumStockModule stock = warehouse.getModule(BuildingModules.MIN_STOCK);
        final ItemStack dirt = new ItemStack(Items.DIRT);

        // An ordinary request for the same item, which the minimum stock does not own.
        final IToken<?> ordinary = warehouse.createRequest(new Stack(new ItemStack(Items.DIRT), 5, 5), false);

        stock.addMinimumStock(dirt, 1);
        stock.onColonyTick(colony);
        final List<IToken<?>> minimum = new ArrayList<>(warehouse.getOpenRequestsByRequestableType().getOrDefault(TypeToken.of(MinimumStack.class), List.of()));
        helper.assertTrue(minimum.size() == 1, "the minimum stock did not create its request: " + warehouse.getOpenRequestsByRequestableType());

        stock.removeMinimumStock(dirt);

        final Collection<IToken<?>> stillMinimum = warehouse.getOpenRequestsByRequestableType().getOrDefault(TypeToken.of(MinimumStack.class), List.of());
        helper.assertTrue(stillMinimum.isEmpty(), "removing the minimum stock left its request open: " + stillMinimum);
        final IRequest<?> ordinaryRequest = colony.getRequestManager().getRequestForToken(ordinary);
        helper.assertTrue(ordinaryRequest != null && ordinaryRequest.getState() != RequestState.CANCELLED,
          "removing the minimum stock cancelled an unrelated request for the same item: " + (ordinaryRequest == null ? "gone" : ordinaryRequest.getState()));
        helper.succeed();
    }

    // ------------------------------------------------------------------ 4: delivery to a full building

    public static void deliveryToFullBuildingIsNotResolved(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "rs-delivery-full");
        final ServerLevel level = helper.getLevel();
        final IBuilding warehouse = place(helper, colony, ModBlocks.blockHutWareHouse, new BlockPos(24, 1, 2), "craftsmanship/storage/warehouse1.blueprint");
        final IBuilding deliveryman = place(helper, colony, ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");
        final IBuilding target = place(helper, colony, ModBlocks.blockHutBuilder, new BlockPos(10, 1, 2), "fundamentals/builder1.blueprint");
        forceChunks(level, helper.absolutePos(new BlockPos(24, 1, 2)), helper.absolutePos(new BlockPos(32, 1, 2)), helper.absolutePos(new BlockPos(10, 1, 2)));

        helper.runAfterDelay(200, () -> {
            final ICitizenData courier = spawn(helper, colony, new BlockPos(34, 1, 8));
            final ICitizenData worker = spawn(helper, colony, new BlockPos(12, 1, 8));
            helper.assertTrue(deliveryman.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier), "courier not assigned to the courier hut");
            helper.assertTrue(warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "courier not assigned to the warehouse");
            helper.assertTrue(target.getModule(BuildingModules.BUILDER_WORK).assignCitizen(worker), "worker not assigned to the target building");

            final JobDeliveryman job = courier.getJob(JobDeliveryman.class);
            final EntityAIWorkDeliveryman ai = job.generateAI();
            final var courierInventory = courier.getEntity().get().getInventoryCitizen();
            final var targetInventory = target.getItemHandlerCap();
            helper.assertTrue(job != null && targetInventory != null, "fixture is missing the courier job or the target inventory");

            // Two stacks the target treats as promised to its worker, so a delivery may not push them out.
            final IToken<?> promise = ((AbstractBuilding) target).createRequest(worker, new Stack(new ItemStack(Items.COBBLESTONE), 64, 1), false);
            final IRequest<?> promised = colony.getRequestManager().getRequestForToken(promise);
            promised.addDelivery(new ItemStack(Items.COBBLESTONE, 64));
            promised.addDelivery(new ItemStack(Items.DIRT, 64));

            final List<String> failures = new ArrayList<>();
            runDelivery(helper, colony, job, ai, warehouse, target, courierInventory, targetInventory, Scenario.ROOM, failures);
            runDelivery(helper, colony, job, ai, warehouse, target, courierInventory, targetInventory, Scenario.FULL, failures);
            runDelivery(helper, colony, job, ai, warehouse, target, courierInventory, targetInventory, Scenario.PARTIAL, failures);
            if (!failures.isEmpty())
            {
                helper.fail(String.join("; ", failures));
                return;
            }
            helper.succeed();
        });
    }

    private enum Scenario
    {
        /** The target has plenty of room: the delivery must be resolved. */
        ROOM,
        /** Every slot of the target holds a promised stack: nothing fits, the delivery must not be resolved. */
        FULL,
        /** The target can take 4 of the 10: the delivery must not be resolved, and nothing may be lost. */
        PARTIAL
    }

    private static final int DELIVERED = 10;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void runDelivery(
      final GameTestHelper helper,
      final IColony colony,
      final JobDeliveryman job,
      final EntityAIWorkDeliveryman ai,
      final IBuilding warehouse,
      final IBuilding target,
      final com.minecolonies.api.inventory.InventoryCitizen courierInventory,
      final com.ldtteam.structurize.api.compat.itemhandler.IItemHandler targetInventory,
      final Scenario scenario,
      final List<String> failures)
    {
        final IRequestManager manager = colony.getRequestManager();
        // Empty the courier and shape the target.
        for (int i = 0; i < courierInventory.getSlots(); i++)
        {
            courierInventory.setStackInSlot(i, ItemStack.EMPTY);
        }
        for (int i = 0; i < targetInventory.getSlots(); i++)
        {
            final ItemStack content = switch (scenario)
            {
                case ROOM -> ItemStack.EMPTY;
                case FULL -> new ItemStack(Items.COBBLESTONE, 64);
                case PARTIAL -> i == 0 ? new ItemStack(Items.DIRT, 60) : new ItemStack(Items.COBBLESTONE, 64);
            };
            targetInventory.extractItem(i, Integer.MAX_VALUE, false);
            if (!content.isEmpty())
            {
                targetInventory.insertItem(i, content, false);
            }
        }
        final int dirtInTargetBefore = countDirt(targetInventory);

        // The courier carries the goods and stands at the target.
        courierInventory.setStackInSlot(0, new ItemStack(Items.DIRT, DELIVERED));
        job.getCitizen().getEntity().ifPresent(e -> e.setPos(target.getPosition().getX() + 0.5D, target.getPosition().getY(), target.getPosition().getZ() + 0.5D));

        final Delivery delivery = new Delivery(warehouse.getLocation(), target.getLocation(), new ItemStack(Items.DIRT, DELIVERED), 13);
        // Warehouse deliveries are requested by the warehouse's resolvers, not by the building.
        final IRequester warehouseResolver = warehouse.getResolvers().stream().filter(r -> r instanceof AbstractWarehouseRequestResolver).findFirst().orElseThrow();
        final IToken<?> token = manager.createRequest(warehouseResolver, delivery);
        final IRequest<?> request = manager.getRequestForToken(token);
        manager.assignRequest(token);
        if (request.getState() != RequestState.IN_PROGRESS)
        {
            failures.add(scenario + ": the delivery request is " + request.getState() + " after assignment (needs IN_PROGRESS)");
            return;
        }
        final IRequest<?> task = job.getCurrentTask();
        if (task == null || !task.getId().equals(token))
        {
            failures.add(scenario + ": the courier did not take the delivery: " + task);
            return;
        }
        job.addConcurrentDelivery(token);

        try
        {
            final Method deliver = EntityAIWorkDeliveryman.class.getDeclaredMethod("deliver");
            deliver.setAccessible(true);
            Log.getLogger().info("RS delivery {}: deliver() returned {}", scenario, deliver.invoke(ai));
        }
        catch (final ReflectiveOperationException e)
        {
            failures.add(scenario + ": could not run deliver(): " + e + " / " + e.getCause());
            return;
        }

        final RequestState state = request.getState();
        final boolean resolved = Set.of(RequestState.RESOLVED, RequestState.COMPLETED, RequestState.FOLLOWUP_IN_PROGRESS, RequestState.RECEIVED).contains(state);
        final int carried = countDirt(courierInventory);
        final int delivered = countDirt(targetInventory) - dirtInTargetBefore;
        Log.getLogger().info("RS delivery {}: state={} carried={} delivered={}", scenario, state, carried, delivered);

        switch (scenario)
        {
            case ROOM ->
            {
                if (!resolved || delivered != DELIVERED || carried != 0)
                {
                    failures.add("ROOM: state " + state + ", delivered " + delivered + ", carried " + carried + " (want resolved, 10, 0)");
                }
            }
            case FULL ->
            {
                if (resolved)
                {
                    failures.add("FULL: the delivery was " + state + " although nothing fitted into the target");
                }
                if (delivered != 0 || carried != DELIVERED)
                {
                    failures.add("FULL: delivered " + delivered + ", carried " + carried + " (want 0 and 10: nothing lost)");
                }
            }
            case PARTIAL ->
            {
                if (resolved)
                {
                    failures.add("PARTIAL: the delivery was " + state + " although only " + delivered + " of " + DELIVERED + " fitted");
                }
                if (delivered + carried != DELIVERED)
                {
                    failures.add("PARTIAL: delivered " + delivered + " + carried " + carried + " != " + DELIVERED + ": items were lost");
                }
                if (delivered != 4)
                {
                    failures.add("PARTIAL: expected the 4 free spaces to be filled, delivered " + delivered);
                }
            }
        }
        // Whatever the outcome, the courier's queue must not keep the request.
        if (job.getTaskQueue().contains(token))
        {
            failures.add(scenario + ": the finished delivery is still in the courier's task queue");
        }
    }

    private static int countDirt(final com.ldtteam.structurize.api.compat.itemhandler.IItemHandler handler)
    {
        int n = 0;
        for (int i = 0; i < handler.getSlots(); i++)
        {
            final ItemStack stack = handler.getStackInSlot(i);
            if (stack.is(Items.DIRT))
            {
                n += stack.getCount();
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 5: reserved items in the building resolver

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void buildingResolverLeavesReservedItems(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "rs-building-reserved");
        final IBuilding sawmill = place(helper, colony, ModBlocks.blockHutSawmill, new BlockPos(10, 1, 2), "craftsmanship/carpentry/sawmill1.blueprint");
        forceChunks(helper.getLevel(), helper.absolutePos(new BlockPos(10, 1, 2)));

        helper.runAfterDelay(200, () -> {
            final ICitizenData crafter = spawn(helper, colony, new BlockPos(12, 1, 8));
            helper.assertTrue(sawmill.getModule(BuildingModules.SAWMILL_WORK).assignCitizen(crafter), "crafter not assigned to the sawmill");

            // 4 crafts of 1 oak log are queued in the sawmill: 4 logs are reserved for them.
            final var recipe = RecipeStorage.builder()
              .withRecipeId(net.minecraft.resources.Identifier.fromNamespaceAndPath(Constants.MOD_ID, "gametest/rs_reserved_planks"))
              .withInputs(List.of(new ItemStorage(new ItemStack(Items.OAK_LOG, 1))))
              .withPrimaryOutput(new ItemStack(Items.OAK_PLANKS, 4))
              .build();
            final IToken<?> recipeToken = IColonyManager.getInstance().getRecipeManager().checkOrAddRecipe(recipe);
            helper.assertTrue(sawmill.getModule(BuildingModules.SAWMILL_CRAFT).addRecipe(recipeToken), "sawmill rejected the recipe");
            final IToken<?> crafting = colony.getRequestManager().createRequest(sawmill.getRequester(),
              new PublicCrafting(new ItemStack(Items.OAK_PLANKS), 4, 4, recipeToken));
            crafter.getJob(com.minecolonies.core.colony.jobs.AbstractJobCrafter.class).onTaskBeingScheduled(crafting);

            // 6 logs in the hut, 4 of them reserved: 2 are free.
            final var inventory = sawmill.getItemHandlerCap();
            helper.assertTrue(com.minecolonies.api.util.InventoryUtils.forceItemStackToItemHandler(inventory, new ItemStack(Items.OAK_LOG, 6), s -> true).isEmpty(), "could not stock the sawmill");
            final IToken<?> probe = colony.getRequestManager().createRequest(sawmill.getRequester(), new Stack(new ItemStack(Items.OAK_LOG), 2, 2));
            final java.util.Map<ItemStorage, Integer> reservedMap = sawmill.reservedStacksExcluding((IRequest) colony.getRequestManager().getRequestForToken(probe));
            final int reserved = reservedMap.values().stream().mapToInt(Integer::intValue).sum();
            helper.assertTrue(reserved == 4, "fixture error: reserved logs = " + reserved);

            // The sawmill's citizen asks for 2 logs: the attempt accepts (2 free), the resolve step must hand out only those.
            final IToken<?> asked = ((AbstractBuilding) sawmill).createRequest(crafter, new Stack(new ItemStack(Items.OAK_LOG), 2, 2), false);
            final List<IRequest<?>> completed = ((AbstractBuilding) sawmill).getCompletedRequestsOfCitizenOrBuilding(crafter).stream().filter(r -> r.getId().equals(asked)).toList();
            helper.assertTrue(completed.size() == 1, "the request for 2 free logs was not resolved by the building: "
                                                       + colony.getRequestManager().getRequestForToken(asked));
            final int handedOut = completed.get(0).getDeliveries().stream().mapToInt(ItemStack::getCount).sum();
            helper.assertTrue(handedOut <= 2, "the building resolver handed out " + handedOut + " logs although only 2 of the 6 are not reserved");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ 9: null safety

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void nullSafety(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "rs-null-safety");
        final ServerLevel level = helper.getLevel();
        final IRequestManager manager = colony.getRequestManager();
        final IBuilding builder = place(helper, colony, ModBlocks.blockHutBuilder, new BlockPos(8, 1, 2), "fundamentals/builder1.blueprint");
        final BuildingWareHouse warehouse = (BuildingWareHouse) place(helper, colony, ModBlocks.blockHutWareHouse, new BlockPos(16, 1, 2), "craftsmanship/storage/warehouse1.blueprint");
        final List<String> failures = new ArrayList<>();

        // A location where no building stands.
        final ILocation empty = StandardFactoryController.getInstance().getNewInstance(TypeConstants.ILOCATION, helper.absolutePos(new BlockPos(30, 1, 20)), level.dimension());
        final IRequester ghost = (IRequester) Proxy.newProxyInstance(RequestSystemGameTests.class.getClassLoader(), new Class<?>[] {IRequester.class}, (proxy, method, args) -> switch (method.getName())
        {
            case "getLocation" -> empty;
            case "getId" -> new StandardToken();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "GhostRequester";
            default -> throw new UnsupportedOperationException(method.getName());
        });

        // AbstractWarehouseRequestResolver.canResolveRequest: a MinimumStack from a requester whose building is gone.
        check(failures, "warehouse canResolveRequest (requester building gone)", () -> {
            final IToken<?> token = manager.createRequest(ghost, new MinimumStack(new ItemStack(Items.DIRT), 4, 4));
            final IRequest<?> request = manager.getRequestForToken(token);
            final WarehouseRequestResolver resolver = new WarehouseRequestResolver(warehouse.getRequester().getLocation(), newToken(colony));
            resolver.canResolveRequest(manager, (IRequest) request);
        });

        final WarehouseRequestResolver unbound = new WarehouseRequestResolver(empty, newToken(colony));
        final IToken<?> plain = builder.createRequest(new Stack(new ItemStack(Items.DIRT), 4, 4), false);
        final IRequest<?> plainRequest = manager.getRequestForToken(plain);

        // AbstractWarehouseRequestResolver.attemptResolveRequest: the resolver's warehouse is gone.
        check(failures, "warehouse attemptResolveRequest (own building gone)", () -> unbound.attemptResolveRequest(manager, (IRequest) plainRequest));
        // AbstractWarehouseRequestResolver.getFollowupRequestForCompletion: same.
        check(failures, "warehouse getFollowupRequestForCompletion (own building gone)", () -> unbound.getFollowupRequestForCompletion(manager, (IRequest) plainRequest));

        // BuildingResourcesModule.addNeededResource: nobody builds yet.
        check(failures, "BuildingResourcesModule.addNeededResource (no builder assigned)",
          () -> builder.getFirstModuleOccurance(BuildingResourcesModule.class).addNeededResource(new ItemStack(Items.DIRT), 10));

        // PublicWorkerCraftingProductionResolver.getFollowupRequestForCompletion: a crafting request without a parent.
        check(failures, "PublicWorkerCraftingProductionResolver.getFollowupRequestForCompletion (no parent)", () -> {
            final var sawmill = place(helper, colony, ModBlocks.blockHutSawmill, new BlockPos(24, 1, 2), "craftsmanship/carpentry/sawmill1.blueprint");
            final PublicWorkerCraftingProductionResolver resolver = new PublicWorkerCraftingProductionResolver(sawmill.getRequester().getLocation(), newToken(colony), ModJobs.sawmill.get());
            final IToken<?> token = manager.createRequest(sawmill.getRequester(), new PublicCrafting(new ItemStack(Items.OAK_PLANKS), 1, 1, newToken(colony)));
            final IRequest<?> crafting = manager.getRequestForToken(token);
            helper.assertTrue(!crafting.hasParent(), "fixture error: crafting request has a parent");
            resolver.getFollowupRequestForCompletion(manager, (IRequest) crafting);
        });

        if (!failures.isEmpty())
        {
            helper.fail(String.join("; ", failures));
            return;
        }
        helper.succeed();
    }

    private static void check(final List<String> failures, final String what, final Runnable body)
    {
        try
        {
            body.run();
        }
        catch (final RuntimeException e)
        {
            failures.add(what + " threw " + e);
        }
    }

}
