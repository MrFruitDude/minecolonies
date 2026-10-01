package com.minecolonies.apiimp.initializer;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.items.ModItems;
import com.minecolonies.api.util.constant.Constants;
import net.minecraft.advancements.predicates.StatePropertiesPredicate;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.Compostable;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.storage.loot.predicates.MatchBlock;
import net.minecraft.world.level.storage.loot.providers.number.DispatcherProvider;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProvider;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders;
import net.minecraft.world.level.storage.loot.providers.number.ints.NumberDispatcher;
import net.neoforged.neoforge.event.ModifyDefaultComponentsEvent;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Makes MineColonies foods, crop seeds, mistletoe and composted dirt compostable in a vanilla composter.
 * <p>
 * 1.21 declared these in NeoForge's compostables data map (chance = nutrition / 6 for foods, nutrition / 10 for ingredients, capped
 * at 1; 0.5 for seeds and mistletoe; 1 for composted dirt). NeoForge 26.3 dropped that data map: compostability is now the vanilla
 * {@link DataComponents#COMPOSTABLE} item component, whose layer chance is a {@link ContextIntProvider} registry entry. Same values as
 * 1.21: chances that vanilla has no entry for get a {@code minecolonies:compostable/<n>_in_<d>} entry written by datagen.
 */
public final class ModCompostablesInitializer
{
    /**
     * The divisors 1.21 applies to a food's nutrition.
     */
    private static final int FOOD_DIVISOR       = 6;
    private static final int INGREDIENT_DIVISOR = 10;

    private ModCompostablesInitializer()
    {
    }

    /**
     * Mod-bus listener: add the compostable component to the default components of MineColonies' items.
     */
    public static void onModifyDefaultComponents(@NotNull final ModifyDefaultComponentsEvent event)
    {
        // these items aren't registered in "getAllFoods"
        compostFromNutrition(event, ModItems.milkyBread, FOOD_DIVISOR);
        compostFromNutrition(event, ModItems.sugaryBread, FOOD_DIVISOR);
        compostFromNutrition(event, ModItems.goldenBread, FOOD_DIVISOR);
        compostFromNutrition(event, ModItems.chorusBread, FOOD_DIVISOR);

        for (final Item item : ModItems.getAllIngredients())
        {
            compostFromNutrition(event, item, INGREDIENT_DIVISOR);
        }
        for (final Item item : ModItems.getAllFoods())
        {
            compostFromNutrition(event, item, FOOD_DIVISOR);
        }

        compost(event, ModItems.mistletoe, ContextIntProviders.COMPOSTABLE_LOW_MEDIUM);
        for (final Block block : ModBlocks.getCrops())
        {
            compost(event, block, ContextIntProviders.COMPOSTABLE_LOW_MEDIUM);
        }
        compost(event, ModBlocks.blockCompostedDirt, ContextIntProviders.COMPOSTABLE_ALWAYS_ADD_ONE);
    }

    /**
     * Datagen bootstrap for the layer-chance entries vanilla lacks: every chance a nutrition value can produce with 1.21's divisors.
     */
    public static void bootstrap(@NotNull final BootstrapContext<ContextIntProvider> context)
    {
        final HolderGetter<Block> blocks = context.lookup(Registries.BLOCK);
        final Set<ResourceKey<ContextIntProvider>> registered = new HashSet<>();
        for (final int divisor : new int[] {FOOD_DIVISOR, INGREDIENT_DIVISOR})
        {
            for (int nutrition = 1; nutrition < divisor; nutrition++)
            {
                final int gcd = gcd(nutrition, divisor);
                final ResourceKey<ContextIntProvider> key = layerChance(nutrition, divisor);
                if (key.identifier().getNamespace().equals(Constants.MOD_ID) && registered.add(key))
                {
                    context.register(key, compostable(blocks, nutrition / gcd, divisor / gcd));
                }
            }
        }
    }

    private static void compostFromNutrition(@NotNull final ModifyDefaultComponentsEvent event, @NotNull final ItemLike item, final int divisor)
    {
        event.modify(item, (builder, lookup, it) -> {
            final FoodProperties food = builder.get(DataComponents.FOOD);
            if (food != null && food.nutrition() > 0)
            {
                builder.set(DataComponents.COMPOSTABLE, new Compostable(layerChance(food.nutrition(), divisor)));
            }
        });
    }

    private static void compost(@NotNull final ModifyDefaultComponentsEvent event, @NotNull final ItemLike item, @NotNull final ResourceKey<ContextIntProvider> chance)
    {
        event.modify(item, (builder, lookup, it) -> builder.set(DataComponents.COMPOSTABLE, new Compostable(chance)));
    }

    /**
     * The layer-chance entry for a chance of {@code numerator / denominator} (capped at 1), reusing vanilla's entries where they match.
     */
    @NotNull
    private static ResourceKey<ContextIntProvider> layerChance(final int numerator, final int denominator)
    {
        if (numerator >= denominator)
        {
            return ContextIntProviders.COMPOSTABLE_ALWAYS_ADD_ONE;
        }
        final int gcd = gcd(numerator, denominator);
        final int n = numerator / gcd;
        final int d = denominator / gcd;
        if (n * 2 == d)
        {
            return ContextIntProviders.COMPOSTABLE_LOW_MEDIUM;
        }
        return ResourceKey.create(Registries.CONTEXT_INT_PROVIDER, Identifier.fromNamespaceAndPath(Constants.MOD_ID, "compostable/" + n + "_in_" + d));
    }

    /**
     * Same shape as vanilla's compostable entries: an empty composter always gains a layer, otherwise with chance n / d.
     */
    @NotNull
    private static ContextIntProvider compostable(@NotNull final HolderGetter<Block> blocks, final int numerator, final int denominator)
    {
        final DispatcherProvider.Case<ContextIntProvider> emptyCase = new DispatcherProvider.Case<>(
          Holder.direct(MatchBlock.blockMatches(blocks, Blocks.COMPOSTER,
            StatePropertiesPredicate.Builder.properties().hasProperty(ComposterBlock.LEVEL, 0)).build()),
          ContextIntProviders.exactly(1));
        final WeightedList<Holder<ContextIntProvider>> cases = WeightedList.<Holder<ContextIntProvider>>builder()
          .add(ContextIntProviders.exactly(1), numerator)
          .add(ContextIntProviders.exactly(0), denominator - numerator)
          .build();
        return new NumberDispatcher(List.of(emptyCase), ContextIntProviders.weighted(cases));
    }

    private static int gcd(final int a, final int b)
    {
        return b == 0 ? a : gcd(b, a % b);
    }
}
