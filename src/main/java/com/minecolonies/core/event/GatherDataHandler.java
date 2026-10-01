package com.minecolonies.core.event;

import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.generation.DatagenLootTableManager;
import com.minecolonies.core.generation.ItemNbtCalculator;
import com.minecolonies.core.generation.SimpleLootTableProvider;
import com.minecolonies.core.generation.defaults.*;
import com.minecolonies.core.generation.defaults.workers.*;
import com.minecolonies.core.util.SchemFixerUtil;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class GatherDataHandler
{
    /**
     * This method is for adding datagenerators. this does not run during normal client operations, only during building.
     *
     * @param event event sent when you run the "runData" gradle task
     */
    public static void dataGeneratorSetupServer(final GatherDataEvent.Server event)
    {
        final DataGenerator generator = event.getGenerator();
        // MC 26.3: enchantments are world-layer registry entries; advancements and recipes (with their unlock
        // advancements) are reloadable-layer registry bootstraps written by NeoForge's datapack registry providers.
        final Set<String> namespaces = Set.of(Constants.MOD_ID, "minecraft");
        event.createWorldRegistryObjects(new RegistrySetBuilder()
            .add(Registries.ENCHANTMENT, DefaultEnchantmentProvider::bootstrap), namespaces);
        event.createReloadableRegistryObjects(new RegistrySetBuilder()
            .add(Registries.ADVANCEMENT, DefaultAdvancementsProvider::bootstrap)
            .add(Registries.CONTEXT_INT_PROVIDER, com.minecolonies.apiimp.initializer.ModCompostablesInitializer::bootstrap)
            .add(RecipeProvider.asBootstrap(DefaultRecipeProvider::new)), namespaces);
        final CompletableFuture<HolderLookup.Provider> provider = event.getReloadableLookupProvider()
            .thenApply(p -> new DatagenLootTableManager(p, event.getResourceManager(PackType.SERVER_DATA)));

        final BlockTagsProvider blockTagsProvider = new DefaultBlockTagsProvider(generator.getPackOutput(), provider, provider);

        generator.addProvider(true, new DefaultSoundProvider(generator.getPackOutput()));
        generator.addProvider(true, new DefaultItemModelProvider(generator.getPackOutput()));
        generator.addProvider(true, new DefaultEntityIconProvider(generator));
        generator.addProvider(true, new DefaultStoriesProvider(generator.getPackOutput()));
        generator.addProvider(true, new QuestTranslationProvider(generator.getPackOutput()));

        generator.addProvider(true, new DefaultDamageTypeProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, blockTagsProvider);
        generator.addProvider(true, new DefaultItemTagsProvider(generator.getPackOutput(), provider, blockTagsProvider, provider));
        generator.addProvider(true, new DefaultEntityTypeTagsProvider(generator.getPackOutput(), provider, provider));
        generator.addProvider(true, new DefaultDamageTagsProvider(generator.getPackOutput(), provider, provider));
        generator.addProvider(true, new DefaultResearchProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultBiomeTagsProvider(generator.getPackOutput(), provider, provider));
        generator.addProvider(true, new DefaultLootModifiersProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultDataMapsProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultRecruitmentItemsProvider(generator.getPackOutput()));

        // workers
        generator.addProvider(true, new DefaultAlchemistCraftingProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultBakerCraftingProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultBlacksmithCraftingProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultConcreteMixerCraftingProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultChefCraftingProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultCrusherCraftingProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultDyerCraftingProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultEnchanterCraftingProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultFarmerCraftingProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new LootTableProviders(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultFletcherCraftingProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultGlassblowerCraftingProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultLumberjackCraftingProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultMechanicCraftingProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultNetherWorkerLootProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultPlanterCraftingProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultSawmillCraftingProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultSifterCraftingProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultStonemasonCraftingProvider(generator.getPackOutput(), provider));
        generator.addProvider(true, new DefaultStoneSmelteryCraftingProvider(generator.getPackOutput(), provider));

        generator.addProvider(true, new ItemNbtCalculator(generator.getPackOutput(), provider));

        SchemFixerUtil.fixSchematics(provider);
    }

    public static void dataGeneratorSetupClient(final GatherDataEvent.Client event)
    {
        // MineColonies currently generates the complete pack from the server data run; retain the client hook so
        // either NeoForge datagen target can be invoked without registering against the abstract base event.
    }

    /**
     * The mod's own loot tables. MC 26.3's LootTableProvider is a reloadable-registry bootstrap; MineColonies writes
     * its tables through {@link SimpleLootTableProvider} instead, the same path the crafter-recipe providers use.
     */
    private static final class LootTableProviders extends SimpleLootTableProvider
    {
        public LootTableProviders(final PackOutput packOutput, CompletableFuture<HolderLookup.Provider> provider)
        {
            super(packOutput, provider);
        }

        @NotNull
        @Override
        public List<LootTableProvider.SubProviderEntry> getTables()
        {
            return List.of(
                new LootTableProvider.SubProviderEntry(DefaultFishermanLootProvider::new, LootContextParamSets.FISHING),
                new LootTableProvider.SubProviderEntry(DefaultRecipeLootProvider::new, LootContextParamSets.ALL_PARAMS),
                new LootTableProvider.SubProviderEntry(DefaultSupplyLootProvider::new, LootContextParamSets.CHEST),
                new LootTableProvider.SubProviderEntry(DefaultCropsLootProvider::new, LootContextParamSets.BLOCK),
                new LootTableProvider.SubProviderEntry(DefaultEntityLootProvider::new, LootContextParamSets.ENTITY),
                new LootTableProvider.SubProviderEntry(DefaultBlockLootTableProvider::new, LootContextParamSets.BLOCK),
                new LootTableProvider.SubProviderEntry(DefaultLuckyOreLootProvider::new, LootContextParamSets.BLOCK));
        }
    }
}
