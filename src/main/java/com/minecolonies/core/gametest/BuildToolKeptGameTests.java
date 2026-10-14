package com.minecolonies.core.gametest;

import com.ldtteam.structurize.api.compat.itemhandler.wrapper.InvWrapper;
import com.ldtteam.structurize.items.ModItems;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.crafting.RecipeStorage;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.core.network.messages.server.SwitchBuildingWithToolMessage;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.List;

/**
 * The build tool ({@code structurize:sceptergold}) is a tool, never a material: the hut recipes list it, and nothing that works from a recipe or moves
 * items may use it up. Colony Command's GameTests cover the player side (placing, crafting by hand); these cover what the colony does: a crafter working a
 * recipe that lists the tool, and the build tool switch of the hut window.
 */
public final class BuildToolKeptGameTests
{
    private BuildToolKeptGameTests()
    {
    }

    private static ItemStack tool()
    {
        return new ItemStack(ModItems.buildTool.get());
    }

    private static int tools(final SimpleContainer container)
    {
        int n = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++)
        {
            n += container.getItem(slot).is(ModItems.buildTool.get()) ? container.getItem(slot).getCount() : 0;
        }
        return n;
    }

    /**
     * A colony crafter works the builder's hut recipe as the crafting table lists it (the tool beside seven planks and a door). The recipe keeps the tool out of what it
     * needs (it asks for no build tool, so no request and no courier can ever be sent for one), out of its tools and secondary outputs, a crafter without a tool
     * can work it, and one that carries a build tool still has it afterwards.
     */
    public static void colonyCrafterLeavesTheBuildTool(final GameTestHelper helper)
    {
        final RecipeStorage recipe = RecipeStorage.builder()
          .withRecipeId(null)
          .withInputs(List.of(new ItemStorage(tool()), new ItemStorage(new ItemStack(Items.OAK_PLANKS, 7)), new ItemStorage(new ItemStack(Items.OAK_DOOR))))
          .withPrimaryOutput(new ItemStack(ModBlocks.blockHutBuilder))
          .withSecondaryOutputs(List.of(tool()))
          .withGridSize(9)
          .build();
        helper.assertTrue(recipe.getCleanedInput().stream().noneMatch(storage -> storage.getItem() == ModItems.buildTool.get()), "the recipe needs the build tool: " + recipe.getCleanedInput());
        helper.assertTrue(recipe.getCraftingTools().stream().noneMatch(stack -> stack.is(ModItems.buildTool.get())), "the recipe's tools list the build tool: " + recipe.getCraftingTools());
        helper.assertTrue(recipe.getSecondaryOutputs().stream().noneMatch(stack -> stack.is(ModItems.buildTool.get())),
          "the recipe hands out a build tool: " + recipe.getSecondaryOutputs());

        // Without the tool the crafter can work it (nothing asks for one) and the hut comes out.
        final SimpleContainer without = new SimpleContainer(27);
        without.addItem(new ItemStack(Items.OAK_PLANKS, 7));
        without.addItem(new ItemStack(Items.OAK_DOOR));
        helper.assertTrue(recipe.canFullFillRecipe(1, java.util.Collections.emptyMap(), new InvWrapper(without)), "a crafter without the build tool cannot work the recipe");
        helper.assertTrue(recipe.fullfillRecipe(helper.getLevel(), List.of(new InvWrapper(without))), "the recipe was not worked without the tool");
        helper.assertTrue(InventoryUtils.getItemCountInItemHandler(new InvWrapper(without), stack -> stack.is(ModBlocks.blockHutBuilder.asItem())) == 1
                            && InventoryUtils.getItemCountInItemHandler(new InvWrapper(without), stack -> stack.is(Items.OAK_PLANKS)) == 0, "the hut did not come out of the planks and the door");
        helper.assertTrue(tools(without) == 0, "a build tool came out of a recipe that had none");

        // With the tool in the inventory it is not touched: not taken, not damaged, not replaced.
        final SimpleContainer with = new SimpleContainer(27);
        with.addItem(tool());
        with.addItem(new ItemStack(Items.OAK_PLANKS, 14));
        with.addItem(new ItemStack(Items.OAK_DOOR, 2));
        final ItemStack before = with.getItem(0).copy();
        helper.assertTrue(recipe.fullfillRecipe(helper.getLevel(), List.of(new InvWrapper(with))), "the recipe was not worked with the tool in the inventory");
        helper.assertTrue(tools(with) == 1 && ItemStack.matches(with.getItem(0), before), "the build tool in the crafter's inventory changed: " + with.getItem(0));
        helper.assertTrue(InventoryUtils.getItemCountInItemHandler(new InvWrapper(with), stack -> stack.is(Items.OAK_PLANKS)) == 7, "the recipe took the wrong amount of planks");
        helper.succeed();
    }

    /**
     * The hut window's "use the build tool" switch swaps the hut item in the hotbar with the build tool in the inventory: afterwards there is still exactly one build tool and the
     * hut item, whole stack, is where the tool was.
     */
    public static void switchWithToolKeepsTheTool(final GameTestHelper helper)
    {
        final ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("buildtool-switch".getBytes()),
          "buildtool-switch"));
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.getInventory().setItem(20, tool());
        player.getInventory().setItem(3, new ItemStack(ModBlocks.blockHutBuilder, 3));
        try
        {
            final java.lang.reflect.Method execute = SwitchBuildingWithToolMessage.class.getDeclaredMethod("onExecute", net.neoforged.neoforge.network.handling.IPayloadContext.class,
              ServerPlayer.class);
            execute.setAccessible(true);
            execute.invoke(new SwitchBuildingWithToolMessage(new ItemStack(ModBlocks.blockHutBuilder)), null, player);
        }
        catch (final ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
        int toolCount = 0;
        int huts = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
        {
            final ItemStack stack = player.getInventory().getItem(slot);
            toolCount += stack.is(ModItems.buildTool.get()) ? stack.getCount() : 0;
            huts += stack.is(ModBlocks.blockHutBuilder.asItem()) ? stack.getCount() : 0;
        }
        helper.assertTrue(toolCount == 1, "build tools after the switch: " + toolCount);
        helper.assertTrue(huts == 3, "hut items after the switch: " + huts);
        helper.assertTrue(player.getInventory().getItem(3).is(ModItems.buildTool.get()), "the tool is not in the hut item's slot: " + player.getInventory().getItem(3));
        player.getInventory().clearContent();
        helper.succeed();
    }
}
