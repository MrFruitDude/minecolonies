package com.minecolonies.core.colony.labour;

import com.minecolonies.api.colony.labour.LabourModes;
import com.minecolonies.api.crafting.IGenericRecipe;
import com.minecolonies.api.crafting.ModCraftingTypes;
import com.minecolonies.api.util.Log;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The vanilla recipes labourers may use, read from the server's recipe manager: shaped and shapeless crafting recipes whose result the basic tier allows, and the
 * smelting recipes of the smelt mode. Built once per recipe manager (a reload makes a new one) and per smelt mode.
 */
public final class LabourRecipes
{
    private static Object                                    cacheKey;
    private static LabourModes.SmeltMode                     cacheMode;
    private static Map<Item, List<LabourPlanner.Recipe<Item>>> cache = new HashMap<>();

    private LabourRecipes()
    {
    }

    /**
     * @param level  the level.
     * @param output an item.
     * @return the recipes that make it (crafting first, smaller inputs first).
     */
    public static synchronized List<LabourPlanner.Recipe<Item>> recipesFor(final ServerLevel level, final Item output)
    {
        final LabourModes.SmeltMode mode = LabourSettings.smelt();
        if (cacheKey != level.recipeAccess() || cacheMode != mode || cache.isEmpty())
        {
            cache = build(level, mode);
            cacheKey = level.recipeAccess();
            cacheMode = mode;
        }
        return cache.getOrDefault(output, List.of());
    }

    private static Map<Item, List<LabourPlanner.Recipe<Item>>> build(final ServerLevel level, final LabourModes.SmeltMode mode)
    {
        final Map<Item, List<LabourPlanner.Recipe<Item>>> map = new HashMap<>();
        try
        {
            final var holders = level.recipeAccess().getRecipes();
            final List<IGenericRecipe> crafting = new ArrayList<>(ModCraftingTypes.SMALL_CRAFTING.get().findRecipes(holders, level));
            crafting.addAll(ModCraftingTypes.LARGE_CRAFTING.get().findRecipes(holders, level));
            for (final IGenericRecipe generic : crafting)
            {
                final LabourPlanner.Recipe<Item> recipe = convert(generic, LabourPlanner.Kind.CRAFT);
                if (recipe != null && LabourRules.craftable(recipe.output()))
                {
                    map.computeIfAbsent(recipe.output(), k -> new ArrayList<>()).add(recipe);
                }
            }
            if (mode != LabourModes.SmeltMode.OFF)
            {
                for (final IGenericRecipe generic : ModCraftingTypes.SMELTING.get().findRecipes(holders, level))
                {
                    final LabourPlanner.Recipe<Item> recipe = convert(generic, LabourPlanner.Kind.SMELT);
                    if (recipe != null && LabourRules.smeltable(recipe.output(), mode))
                    {
                        map.computeIfAbsent(recipe.output(), k -> new ArrayList<>()).add(recipe);
                    }
                }
            }
        }
        catch (final RuntimeException e)
        {
            Log.getLogger().warn("Could not read the recipes for the labourers", e);
        }
        for (final List<LabourPlanner.Recipe<Item>> list : map.values())
        {
            list.sort(Comparator.comparingInt((LabourPlanner.Recipe<Item> r) -> r.kind().ordinal()).thenComparingInt(LabourRecipes::weight).thenComparing(LabourPlanner.Recipe::id));
        }
        return map;
    }

    private static int weight(final LabourPlanner.Recipe<Item> recipe)
    {
        int total = 0;
        for (final LabourPlanner.Group<Item> group : recipe.inputs())
        {
            total += group.count();
        }
        return total;
    }

    @Nullable
    private static LabourPlanner.Recipe<Item> convert(final IGenericRecipe generic, final LabourPlanner.Kind kind)
    {
        final ItemStack out = generic.getPrimaryOutput();
        if (out.isEmpty() || out.getItem() == Items.AIR)
        {
            return null;
        }
        final List<LabourPlanner.Group<Item>> groups = new ArrayList<>();
        for (final List<ItemStack> alternatives : generic.getInputs())
        {
            if (alternatives.isEmpty())
            {
                return null;
            }
            final List<Item> items = new ArrayList<>();
            for (final ItemStack stack : alternatives)
            {
                if (!items.contains(stack.getItem()))
                {
                    items.add(stack.getItem());
                }
            }
            groups.add(new LabourPlanner.Group<>(items, Math.max(1, alternatives.get(0).getCount())));
        }
        if (groups.isEmpty())
        {
            return null;
        }
        final String id = generic.getRecipeId() == null ? out.getItem().toString() : generic.getRecipeId().toString();
        return new LabourPlanner.Recipe<>(id, groups, out.getItem(), Math.max(1, out.getCount()), kind, generic.getGridSize() <= 2);
    }
}
