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

}
