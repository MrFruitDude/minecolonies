package com.minecolonies.api.util;

import com.minecolonies.api.crafting.RecipeUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.BrewingInput;
import net.minecraft.world.item.crafting.PotionIngredient;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

/**
 * Brewing helpers.
 * <p>
 * MC 26.3 removed {@code Level#potionBrewing()}; brewing is now driven by {@code minecraft:brewing}
 * recipes. On the server the recipe manager is queried; on the client the recipes synced through
 * {@code RecipesReceivedEvent} (requested in {@code DataPackSyncEventHandler}) are used.
 */
public final class BrewingUtils
{
    private BrewingUtils()
    {
        // utility class
    }

    /**
     * Whether the stack can be used as a brewing reagent (the ingredient slot).
     *
     * @param level the level.
     * @param stack the stack.
     * @return true if it is a reagent of any brewing recipe.
     */
    public static boolean isIngredient(@NotNull final Level level, @NotNull final ItemStack stack)
    {
        return level.recipeAccess().propertySet(RecipePropertySet.BREWING_REAGENTS).test(stack);
    }

    /**
     * Whether the stack can go into a potion slot of the brewing stand.
     *
     * @param level the level.
     * @param stack the stack.
     * @return true if it is a brewing input.
     */
    public static boolean isInput(@NotNull final Level level, @NotNull final ItemStack stack)
    {
        return PotionIngredient.isPotionInput(stack, level.recipeAccess());
    }

    /**
     * Brew the given potion with the given reagent.
     *
     * @param level      the level.
     * @param ingredient the reagent.
     * @param potion     the potion input.
     * @return the brewed result, or {@code potion} itself (same instance) when nothing matches, like 26.2's PotionBrewing#mix.
     */
    public static ItemStack mix(@NotNull final Level level, @NotNull final ItemStack ingredient, @NotNull final ItemStack potion)
    {
        final BrewingInput input = new BrewingInput(potion, ingredient);
        if (level instanceof ServerLevel serverLevel)
        {
            return serverLevel.recipeAccess()
                .getRecipeFor(RecipeType.BREWING, input, serverLevel)
                .map(holder -> holder.value().assemble(input))
                .orElse(potion);
        }

        final RecipeMap clientRecipes = RecipeUtils.clientSyncedRecipes();
        if (clientRecipes == null)
        {
            return potion;
        }
        return clientRecipes.getRecipesFor(RecipeType.BREWING, input, level)
            .findFirst()
            .map(holder -> holder.value().assemble(input))
            .orElse(potion);
    }
}
