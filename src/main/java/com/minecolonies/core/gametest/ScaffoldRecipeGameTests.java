package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.ICraftingBuildingModule;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.crafting.IRecipeStorage;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.crafting.CustomRecipe;
import com.minecolonies.core.colony.crafting.CustomRecipeManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.List;

/**
 * Scaffolding from sticks alone. The builders place and request {@code minecraft:scaffolding}; the vanilla recipe wants bamboo and string, so the fork
 * adds {@code minecolonies:scaffolding_from_sticks} (crafting table, seven sticks in a gantry shape, "SSS/S S/S S": four pieces) and the same recipe for the
 * sawmill's worker ({@code crafterrecipes/sawmill/scaffolding_from_sticks.json}, which the sawmill knows without being taught).
 */
final class ScaffoldRecipeGameTests
{
    private static final Identifier RECIPE_ID = Identifier.fromNamespaceAndPath("minecolonies", "scaffolding_from_sticks");

    private ScaffoldRecipeGameTests()
    {
    }

    private static ItemStack stick()
    {
        return new ItemStack(Items.STICK);
    }

    /**
     * The recipe is in the data, crafts four scaffolding from the pattern and nothing from other arrangements; the vanilla recipe is still there.
     */
    public static void tableRecipe(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final ItemStack none = ItemStack.EMPTY;
        helper.assertTrue(level.recipeAccess().recipeMap().byKey(ResourceKey.create(Registries.RECIPE, RECIPE_ID)) != null, "no recipe " + RECIPE_ID);

        final CraftingInput ring = CraftingInput.of(3, 3, List.of(stick(), stick(), stick(), stick(), none, stick(), stick(), none, stick()));
        final var match = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, ring, level);
        helper.assertTrue(match.isPresent(), "seven sticks in the pattern match no recipe");
        helper.assertTrue(match.get().id().identifier().equals(RECIPE_ID), "the pattern matched " + match.get().id().identifier());
        final ItemStack crafted = match.get().value().assemble(ring);
        helper.assertTrue(crafted.is(Items.SCAFFOLDING) && crafted.getCount() == 4, "the recipe crafts " + crafted + " instead of 4 scaffolding");

        // sticks in any other arrangement make no scaffolding
        final CraftingInput row = CraftingInput.of(3, 3, List.of(stick(), stick(), stick(), stick(), stick(), stick(), stick(), none, none));
        final var other = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, row, level);
        helper.assertTrue(other.isEmpty() || !other.get().value().assemble(row).is(Items.SCAFFOLDING), "seven sticks in a block make scaffolding");
        final CraftingInput six = CraftingInput.of(3, 3, List.of(stick(), stick(), stick(), stick(), none, stick(), stick(), none, none));
        final var short1 = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, six, level);
        helper.assertTrue(short1.isEmpty() || !short1.get().value().assemble(six).is(Items.SCAFFOLDING), "six sticks make scaffolding");

        // the ladder (seven sticks in another shape) is untouched: it must not turn into scaffolding
        final CraftingInput ladder = CraftingInput.of(3, 3, List.of(stick(), none, stick(), stick(), stick(), stick(), stick(), none, stick()));
        final var ladderMatch = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, ladder, level);
        helper.assertTrue(ladderMatch.isPresent() && ladderMatch.get().value().assemble(ladder).is(Items.LADDER) && ladderMatch.get().value().assemble(ladder).getCount() == 3,
          "the ladder recipe is gone or changed: " + ladderMatch);

        // the vanilla recipe is untouched: six bamboo and a string still make six
        final ItemStack bamboo = new ItemStack(Items.BAMBOO);
        final CraftingInput vanilla = CraftingInput.of(3, 3, List.of(bamboo, new ItemStack(Items.STRING), bamboo, bamboo, none, bamboo, bamboo, none, bamboo));
        final var vanillaMatch = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, vanilla, level);
        helper.assertTrue(vanillaMatch.isPresent() && !vanillaMatch.get().id().identifier().equals(RECIPE_ID), "the vanilla scaffolding recipe is gone: " + vanillaMatch);
        final ItemStack vanillaOut = vanillaMatch.get().value().assemble(vanilla);
        helper.assertTrue(vanillaOut.is(Items.SCAFFOLDING) && vanillaOut.getCount() == 6, "vanilla recipe crafts " + vanillaOut);
        helper.succeed();
    }

    /**
     * The sawmill's recipe list holds the sticks recipe (a custom recipe of the data), and a real sawmill building has it without being taught.
     */
    public static void sawmillKnowsIt(final GameTestHelper helper)
    {
        final CustomRecipe custom = CustomRecipeManager.getInstance().getRecipes("sawmill_crafting").stream()
                                      .filter(r -> r.getRecipeStorage().getPrimaryOutput().is(Items.SCAFFOLDING)).findFirst().orElse(null);
        helper.assertTrue(custom != null, "the sawmill has no custom recipe for scaffolding: " + CustomRecipeManager.getInstance().getAllRecipes().keySet());
        final IRecipeStorage storage = custom.getRecipeStorage();
        helper.assertTrue(storage.getPrimaryOutput().getCount() == 4, "output " + storage.getPrimaryOutput());
        helper.assertTrue(storage.getCleanedInput().size() == 1 && storage.getCleanedInput().get(0).getItem() == Items.STICK && storage.getCleanedInput().get(0).getAmount() == 7,
          "inputs " + storage.getCleanedInput());

        final RsFixture f = RsFixture.found(helper, "scaffold-sawmill-recipe", 2);
        final IBuilding sawmill = f.place(ModBlocks.blockHutSawmill, new BlockPos(8, 1, 22), "craftsmanship/carpentry/sawmill1.blueprint");
        final ICraftingBuildingModule craft = sawmill.getModule(BuildingModules.SAWMILL_CRAFT);
        craft.checkForWorkerSpecificRecipes();
        final IRecipeStorage known = craft.getFirstRecipe(stack -> stack.is(Items.SCAFFOLDING));
        helper.assertTrue(known != null, "a sawmill does not know the scaffolding recipe: " + craft.getRecipes().size() + " recipes");
        helper.assertTrue(known.getPrimaryOutput().getCount() == 4 && known.getCleanedInput().size() == 1 && known.getCleanedInput().get(0).getItem() == Items.STICK
                            && known.getCleanedInput().get(0).getAmount() == 7, "the sawmill's recipe: " + known.getCleanedInput() + " -> " + known.getPrimaryOutput());
        helper.succeed();
    }

    /**
     * A builder hut asks for scaffolding, nobody has any, but the warehouse has sticks: the sawmill's worker crafts it and the courier brings it to the
     * hut, with no recipe taught and no help from the test.
     */
    public static void sawmillCraftsRequestedScaffolding(final GameTestHelper helper)
    {
        final RsFixture f = RsFixture.found(helper, "scaffold-sawmill-chain", 4);
        final IBuilding sawmill = f.place(ModBlocks.blockHutSawmill, new BlockPos(8, 1, 22), "craftsmanship/carpentry/sawmill1.blueprint");
        final IBuilding deliveryman = f.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");
        for (final IBuilding building : List.of(f.builder, sawmill, deliveryman, f.warehouse))
        {
            final BlockPos a = building.getCorners().getA();
            final BlockPos b = building.getCorners().getB();
            for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++)
            {
                for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++)
                {
                    f.level.setChunkForced(cx, cz, true);
                }
            }
        }
        helper.runAfterDelay(200, () -> {
            final ICitizenData sawmillCitizen = f.spawn(new BlockPos(10, 1, 28), false);
            final ICitizenData courier = f.spawn(new BlockPos(34, 1, 8), false);
            helper.assertTrue(sawmill.getModule(BuildingModules.SAWMILL_WORK).assignCitizen(sawmillCitizen), "sawmill worker not assigned");
            helper.assertTrue(deliveryman.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier), "courier not assigned to the courier hut");
            helper.assertTrue(f.warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "courier not assigned to the warehouse");
            sawmillCitizen.getEntity().ifPresent(e -> e.setPos(sawmill.getID().getX() + 0.5D, sawmill.getID().getY(), sawmill.getID().getZ() + 0.5D));
            courier.getEntity().ifPresent(e -> e.setPos(deliveryman.getID().getX() + 0.5D, deliveryman.getID().getY(), deliveryman.getID().getZ() + 0.5D));
            // (the colony tick of this test colony is not running every second: the recipes of the data reach the sawmill like they do in a running colony)
            sawmill.getModule(BuildingModules.SAWMILL_CRAFT).checkForWorkerSpecificRecipes();

            f.stock(0, new ItemStack(Items.STICK, 14));
            f.builder.createRequest(new Stack(new ItemStack(Items.SCAFFOLDING), 4, 1), true);
            final long start = f.level.getGameTime();
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                f.level.getServer().clockManager().setTotalTicks(f.level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
                final int inHut = InventoryUtils.getItemCountInItemHandler(f.builder.getItemHandlerCap(), stack -> stack.is(Items.SCAFFOLDING));
                final long elapsed = f.level.getGameTime() - start;
                if (inHut >= 4)
                {
                    Log.getLogger().info("SCAFFOLD RECIPE the sawmill crafted {} scaffolding from sticks and the courier delivered it after {} ticks", inHut, elapsed);
                    final int sticksLeft = f.physical(s -> s.is(Items.STICK));
                    helper.assertTrue(sticksLeft <= 14 - 7, "the crafting used no sticks: " + sticksLeft + " left of 14");
                    helper.succeed();
                    return;
                }
                if (elapsed > 12_000)
                {
                    helper.fail("the scaffolding was not delivered after " + elapsed + " ticks: hut " + inHut + ", warehouse sticks " + f.physical(s -> s.is(Items.STICK))
                                  + ", warehouse scaffolding " + f.physical(s -> s.is(Items.SCAFFOLDING)) + ", recipes " + sawmill.getModule(BuildingModules.SAWMILL_CRAFT).getRecipes().size());
                    return;
                }
                helper.runAfterDelay(20, () -> poll[0].run());
            };
            helper.runAfterDelay(20, () -> poll[0].run());
        });
    }
}
