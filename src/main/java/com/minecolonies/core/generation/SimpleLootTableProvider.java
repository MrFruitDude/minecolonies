package com.minecolonies.core.generation;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.data.loot.LootTableSubProvider;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.EmptyLootItem;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.UniformContainerBase;
import net.minecraft.world.level.storage.loot.functions.SetComponentsFunction;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Wrapper around the vanilla loot table provider which makes it easier to use.
 * Just override getName and registerTables
 */
public abstract class SimpleLootTableProvider implements DataProvider
{
    private final PackOutput.PathProvider pathProvider;
    private final CompletableFuture<HolderLookup.Provider> registries;

    /**
     * MC 26.3: vanilla's LootTableProvider became a reloadable-registry bootstrap and no longer writes files itself.
     * MineColonies' generators run their loot tables as part of other data providers, so this provider writes the
     * tables directly (same output path and codec as the 26.2 LootTableProvider).
     */
    protected SimpleLootTableProvider(@NotNull final PackOutput output,
                                      @NotNull final CompletableFuture<HolderLookup.Provider> provider)
    {
        this.pathProvider = output.createRegistryElementsPathProvider(Registries.LOOT_TABLE);
        this.registries = provider;
    }

    /**
     * @return the sub providers to generate.
     */
    @NotNull
    public abstract List<LootTableProvider.SubProviderEntry> getTables();

    @NotNull
    @Override
    public String getName()
    {
        return "MineColonies Loot Tables";
    }

    @NotNull
    @Override
    public CompletableFuture<?> run(@NotNull final CachedOutput cache)
    {
        return registries.thenCompose(provider -> writeTables(cache, pathProvider, provider, getTables()));
    }

    /**
     * Generate and write the loot tables of the given sub providers.
     *
     * @param cache        the output cache.
     * @param pathProvider the loot table path provider.
     * @param provider     the registry lookup.
     * @param subProviders the sub providers.
     * @return a future completing when all files are written.
     */
    public static CompletableFuture<?> writeTables(
      @NotNull final CachedOutput cache,
      @NotNull final PackOutput.PathProvider pathProvider,
      @NotNull final HolderLookup.Provider provider,
      @NotNull final List<LootTableProvider.SubProviderEntry> subProviders)
    {
        // Like vanilla's registry bootstrap: nested table references resolve lazily against this registry, so a table
        // may reference one generated later (or one from vanilla / another pack) without it being loaded here.
        final MappedRegistry<LootTable> tables = new MappedRegistry<>(Registries.LOOT_TABLE, Lifecycle.experimental());
        final HolderGetter<LootTable> tableRefs = tables.createRegistrationLookup();
        final List<ResourceKey<LootTable>> generated = new ArrayList<>();
        for (final LootTableProvider.SubProviderEntry subProvider : subProviders)
        {
            subProvider.bootstrap().create(new DatagenContext(provider, tableRefs)
            {
                @Override
                public Holder.Reference<LootTable> accept(final ResourceKey<LootTable> key, final LootTable.Builder builder)
                {
                    final LootTable table = builder.setRandomSequence(key.identifier()).setParamSet(subProvider.paramSet()).build();
                    generated.add(key);
                    return tables.register(key, table, RegistrationInfo.BUILT_IN);
                }
            }).run();
        }
        // Serialize with this registry as the loot table owner, so the lazy references above encode as their ids.
        final HolderLookup.Provider output = HolderLookup.Provider.create(Stream.concat(
          provider.listRegistries().filter(lookup -> !lookup.key().equals(Registries.LOOT_TABLE)),
          Stream.of(tables)));
        return CompletableFuture.allOf(generated.stream()
          .map(key -> DataProvider.saveStable(cache, output, LootTable.DIRECT_CODEC, tables.getValueOrThrow(key), pathProvider.json(key.identifier())))
          .toArray(CompletableFuture[]::new));
    }

    /**
     * Loot sub-provider context backed by a full registry lookup, so MineColonies sub providers can still reach
     * {@link HolderLookup.Provider} (26.2 passed it to their constructors).
     */
    public abstract static class DatagenContext implements LootTableSubProvider.Context
    {
        private final HolderLookup.Provider provider;
        private final HolderGetter<LootTable> lootTables;

        protected DatagenContext(@NotNull final HolderLookup.Provider provider, @NotNull final HolderGetter<LootTable> lootTables)
        {
            this.provider = provider;
            this.lootTables = lootTables;
        }

        /**
         * @return the registry lookup.
         */
        public HolderLookup.Provider provider()
        {
            return provider;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <S> HolderGetter<S> lookup(final ResourceKey<? extends Registry<? extends S>> key)
        {
            if (key.equals(Registries.LOOT_TABLE))
            {
                return (HolderGetter<S>) lootTables;
            }
            return provider.lookupOrThrow(key);
        }

        @Deprecated
        @Override
        public <S> Stream<Holder.Reference<S>> listContextElements(final ResourceKey<? extends Registry<? extends S>> key)
        {
            return provider.lookupOrThrow(key).listElements();
        }

        /**
         * Get the registry lookup behind a context created by {@link #writeTables}.
         *
         * @param context the context.
         * @return the registry lookup.
         */
        public static HolderLookup.Provider registries(@NotNull final LootTableSubProvider.Context context)
        {
            return ((DatagenContext) context).provider();
        }
    }

    /**
     * Create a loot table resource key.
     * @param id the location.
     * @return the resource key.
     */
    public static ResourceKey<LootTable> table(@NotNull final Identifier id)
    {
        return ResourceKey.create(Registries.LOOT_TABLE, id);
    }

    /**
     * Helper method to make a loot entry builder for an ItemStack
     * @param stack The loot ItemStack
     * @return A loot entry builder for this stack
     */
    public static UniformContainerBase.Builder<?> itemStack(@NotNull final ItemStack stack)
    {
        if (!stack.isEmpty())
        {
            final UniformContainerBase.Builder<?> builder = LootItem.lootTableItem(stack.getItem());
            if (!stack.isComponentsPatchEmpty())
            {
                final DataComponentPatch patch = stack.getComponentsPatch();
                for (final DataComponentType<?> type : patch.keySet())
                {
                    final Object value = patch.getPatch(type);
                    if (value != null)
                    {
                        setComponent(type, builder).accept(value);
                    }
                }
            }
            if (stack.getCount() > 1)
            {
                builder.apply(SetItemCountFunction.setCount(ContextIntProviders.exactly(stack.getCount())));
            }
            return builder;
        }
        return EmptyLootItem.emptyItem();
    }

    @NotNull
    private static <T> Consumer<T> setComponent(DataComponentType<?> type, UniformContainerBase.Builder<?> builder)
    {
        // idk if there's a better way to do this generic ... but SetComponentsFunction is Mojank anyway because there's
        // no method to set multiple components at once, short of hacking the constructor directly.
        return value -> builder.apply(SetComponentsFunction.setComponent((DataComponentType<T>) type, value));
    }
}
