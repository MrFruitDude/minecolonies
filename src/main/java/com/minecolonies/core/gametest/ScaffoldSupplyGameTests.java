package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.crafting.IGenericRecipe;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.crafting.RecipeAnalyzer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Where scaffolding items come from in a real colony: a request of the builder's hut is delivered from the warehouse by a courier, and the
 * recipes of the crafters are audited for scaffolding (the audit is logged: {@code SCAFFOLD SUPPLY ...}).
 */
final class ScaffoldSupplyGameTests
{
    private ScaffoldSupplyGameTests()
    {
    }

    /**
     * The scaffolding request of a builder hut (the kind the builder AI creates) is delivered from a warehouse by a courier, with no help from the
     * test.
     */
    public static void warehouseDelivers(final GameTestHelper helper)
    {
        final RsFixture f = RsFixture.found(helper, "scaffold-supply", 4);
        final IBuilding deliveryman = f.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");
        helper.runAfterDelay(200, () -> {
            final ICitizenData courier = f.spawn(new BlockPos(34, 1, 8), false);
            helper.assertTrue(deliveryman.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier), "courier not assigned to the courier hut");
            helper.assertTrue(f.warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "courier not assigned to the warehouse");
            courier.getEntity().ifPresent(e -> e.setPos(deliveryman.getID().getX() + 0.5D, deliveryman.getID().getY(), deliveryman.getID().getZ() + 0.5D));
            f.stock(0, new ItemStack(Items.SCAFFOLDING, 32));
            f.builder.createRequest(new Stack(new ItemStack(Items.SCAFFOLDING), 32, 1), true);
            final int[] waited = {0};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                final int inHut = InventoryUtils.getItemCountInItemHandler(f.builder.getItemHandlerCap(), stack -> stack.is(Items.SCAFFOLDING));
                if (inHut >= 32)
                {
                    Log.getLogger().info("SCAFFOLD SUPPLY delivered 32 scaffolding to the builder hut after {} ticks", waited[0]);
                    helper.succeed();
                    return;
                }
                waited[0] += 20;
                if (waited[0] > 9000)
                {
                    helper.fail("the scaffolding request was not delivered: hut has " + inHut + ", warehouse has " + f.physical(s -> s.is(Items.SCAFFOLDING)));
                    return;
                }
                helper.runAfterDelay(20, () -> poll[0].run());
            };
            helper.runAfterDelay(20, () -> poll[0].run());
        });
    }

    /**
     * Which crafters can be taught the scaffolding recipe (bamboo and string), by the rules of the crafters. Logged, and at least one has to.
     */
    public static void craftersThatKnowScaffolding(final GameTestHelper helper)
    {
        final var server = helper.getLevel().getServer();
        final var vanilla = RecipeAnalyzer.buildVanillaRecipesMap(server.getRecipeManager(), server.overworld());
        final List<String> able = new ArrayList<>();
        for (final String key : com.minecolonies.api.colony.buildings.registry.BuildingEntry.getALlModuleProducers().keySet())
        {
            final var module = com.minecolonies.api.colony.buildings.registry.BuildingEntry.produceModuleWithoutBuilding(key);
            if (module instanceof com.minecolonies.api.colony.buildings.modules.ICraftingBuildingModule crafting)
            {
                for (final IGenericRecipe recipe : RecipeAnalyzer.findRecipes(vanilla, crafting, server.overworld()))
                {
                    if (recipe.getPrimaryOutput().is(Items.SCAFFOLDING))
                    {
                        able.add(crafting.getCustomRecipeKey() + ":" + recipe.getInputs());
                        break;
                    }
                }
            }
        }
        Log.getLogger().info("SCAFFOLD SUPPLY crafters that can learn the scaffolding recipe: {}", able);
        helper.assertTrue(!able.isEmpty(), "no crafter of the colony can learn the scaffolding recipe");
        helper.succeed();
    }
}
