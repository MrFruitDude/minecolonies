package com.minecolonies.core.generation.defaults;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.DataMapProvider;
import org.jetbrains.annotations.NotNull;
import java.util.concurrent.CompletableFuture;
/**
 * Datagen for data maps.
 */
public class DefaultDataMapsProvider extends DataMapProvider
{
    public DefaultDataMapsProvider(@NotNull final PackOutput packOutput,
                                   @NotNull final CompletableFuture<HolderLookup.Provider> lookupProvider)
    {
        super(packOutput, lookupProvider);
    }
    @Override
    protected void gather(@NotNull final HolderLookup.Provider provider)
    {
        // MC 26.3: compostability is the vanilla COMPOSTABLE item component (a registry-keyed layer provider) and
        // NeoForge dropped its compostables data map, so MineColonies' nutrition-based compost values no longer
        // belong here. Tracked as backlog X-263-COMPOST (set the component on the items instead).
        bindDefaultComponentsForDatagen(provider);
    }

    /**
     * Since 26.2 ItemStacks need their item's default components bound, and datagen never runs the server reload
     * that binds them. Bind the real vanilla/mod defaults (the same snapshot the reload and FMLEventHandler apply):
     * the crafting/loot providers that run after this one rely on them, e.g. enchanting an enchanted book needs its
     * default stored_enchantments, and ItemNbtCalculator derives the item matching keys from each item's defaults.
     * Binding empty maps here instead silently produced blank Enchanter books and a gutted matching table.
     * <p>
     * NeoForge's dev-only component check (CommonHooks.validateComponent, active when IS_RUNNING_IN_IDE) rejects
     * vanilla's own banner-pattern items here: in datagen their provides_banner_patterns tag resolves to the
     * lookup's unbound placeholder HolderSet, an identity-compared singleton that is not a record. That is a
     * datagen artefact, not a bad component, so the check is paused for this one bind.
     */
    private static void bindDefaultComponentsForDatagen(@NotNull final HolderLookup.Provider provider)
    {
        final boolean inIde = SharedConstants.IS_RUNNING_IN_IDE;
        SharedConstants.IS_RUNNING_IN_IDE = false;
        try
        {
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(provider).forEach(DataComponentInitializers.PendingComponents::apply);
        }
        finally
        {
            SharedConstants.IS_RUNNING_IN_IDE = inIde;
        }
    }
}
