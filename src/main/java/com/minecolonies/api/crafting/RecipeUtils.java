package com.minecolonies.api.crafting;

import com.ldtteam.domumornamentum.recipe.ModRecipeTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.util.context.ContextMap;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.Level;

/**
 * Helpers for Minecraft 26.2 recipe APIs.
 */
public final class RecipeUtils
{
    /**
     * The recipe map delivered by NeoForge's client recipe-sync event.
     *
     * <p>26.2 no longer exposes the full map through {@code ClientLevel}; keep
     * the authoritative event payload here so MineColonies screens do not
     * depend on JEI's initialization order.</p>
     */
    @Nullable
    private static volatile RecipeMap clientRecipeMap;

    /**
     * Monotonic generation for the client recipe snapshot.
     *
     * <p>Client screens can open before NeoForge delivers
     * {@code RecipesReceivedEvent}.  Tracking the snapshot generation lets
     * those screens refresh once without polling the recipe map itself or
     * depending on JEI lifecycle events.</p>
     */
    private static volatile long clientRecipeGeneration;

    private RecipeUtils()
    {
        throw new UnsupportedOperationException("utility class");
    }

    /**
     * Stores the latest server-synchronised client recipe map.
     *
     * @param recipeMap map received from NeoForge.
     */
    public static void setClientSyncedRecipes(@NotNull final RecipeMap recipeMap)
    {
        clientRecipeMap = recipeMap;
        clientRecipeGeneration++;
    }

    /**
     * Clears the client recipe map when the client disconnects.
     */
    public static void clearClientSyncedRecipes()
    {
        clientRecipeMap = null;
        clientRecipeGeneration++;
    }

    /**
     * Builds a representative recipe output without requiring a populated input.
     * Vanilla output templates do not depend on the contents of that input.
     *
     * @param recipe recipe to inspect.
     * @return representative output, or an empty stack when the recipe type is unsupported.
     */
    @NotNull
    public static ItemStack getOutput(@NotNull final Recipe<?> recipe)
    {
        return getOutput(recipe, null);
    }

    /**
     * Builds a representative recipe output using the registry context from a
     * level when a display requires it (for example potion displays).
     *
     * @param recipe recipe to inspect.
     * @param level level whose registry context should resolve the display.
     * @return representative output, or an empty stack when the recipe type is unsupported.
     */
    @NotNull
    public static ItemStack getOutput(@NotNull final Recipe<?> recipe, @Nullable final Level level)
    {
        // Minecraft 26.2 recipes expose display output templates even when
        // their assemble method requires a fully populated input (for
        // example ImbueRecipe). Prefer that data so projections do not need
        // to manufacture an invalid crafting grid.
        final ContextMap context = level == null
                ? ContextMap.builder().buildAndValidate(SlotDisplayContext.CONTEXT)
                : SlotDisplayContext.fromLevel(level);
        for (final RecipeDisplay display : recipe.display())
        {
            final ItemStack output = display.result().resolveForFirstStack(context);
            if (!output.isEmpty())
            {
                return output;
            }
        }

        if (recipe instanceof final SingleItemRecipe singleItemRecipe)
        {
            return singleItemRecipe.assemble(new SingleRecipeInput(ItemStack.EMPTY));
        }

        if (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe)
        {
            return ((CraftingRecipe) recipe).assemble(CraftingInput.EMPTY);
        }

        return ItemStack.EMPTY;
    }

    /**
     * Gets recipe inputs without relying on the removed generic {@code Recipe#getIngredients} method.
     *
     * @param recipe recipe to inspect.
     * @return the known inputs.
     */
    @NotNull
    public static List<Ingredient> getIngredients(@NotNull final Recipe<?> recipe)
    {
        if (recipe instanceof final ShapedRecipe shapedRecipe)
        {
            return shapedRecipe.getIngredients().stream().flatMap(Optional::stream).toList();
        }
        if (recipe instanceof final ShapelessRecipe shapelessRecipe)
        {
            return shapelessRecipe.placementInfo().ingredients();
        }
        if (recipe instanceof final SingleItemRecipe singleItemRecipe)
        {
            return List.of(singleItemRecipe.input());
        }
        return recipe.placementInfo().ingredients();
    }

    /**
     * The recipe id that shares an item's registry id (for example {@code minecraft:bread}), the
     * lookup 1.21 did with {@code RecipeManager#byKey(itemId)}.
     *
     * @param item the item.
     * @return the recipe key.
     */
    @NotNull
    public static ResourceKey<Recipe<?>> itemRecipeKey(@NotNull final Item item)
    {
        return ResourceKey.create(Registries.RECIPE, BuiltInRegistries.ITEM.getKey(item));
    }

    /**
     * Recipe types that client screens read from {@link #clientSyncedRecipes()}. Since 1.21.2 the
     * server only sends the recipe types a mod asks for, so these are requested on datapack sync:
     * crafting + smelting (restaurant menu ingredient lookup), brewing ({@code BrewingUtils}) and
     * the Architect's Cutter (Domum crafting window).
     *
     * @return the recipe types to send to clients.
     */
    @NotNull
    public static List<RecipeType<?>> clientRecipeTypes()
    {
        return List.of(RecipeType.CRAFTING, RecipeType.SMELTING, RecipeType.BREWING, ModRecipeTypes.ARCHITECTS_CUTTER.get());
    }

    /**
     * Returns the latest server-synchronised client recipe map.
     *
     * <p>The map is populated by {@code RecipesReceivedEvent}. A null result
     * means the client has not received a recipe snapshot yet; it is not an
     * assertion that the world has no recipes.</p>
     *
     * @return the synchronized client recipe map, or {@code null} while it is
     *         still loading.
     */
    @Nullable
    public static RecipeMap clientSyncedRecipes()
    {
        return clientRecipeMap;
    }

    /**
     * Returns the generation of the latest client recipe snapshot.
     *
     * @return a value that changes whenever the snapshot is replaced or
     *         cleared.
     */
    public static long clientSyncedRecipesGeneration()
    {
        return clientRecipeGeneration;
    }
}
