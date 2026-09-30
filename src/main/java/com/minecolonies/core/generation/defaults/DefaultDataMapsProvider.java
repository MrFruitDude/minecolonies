package com.minecolonies.core.generation.defaults;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
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
        bindEmptyItemPrototypesForDatagen();
    }
    private static void bindEmptyItemPrototypesForDatagen()
    {
        // ItemStack's 26.2 constructors require a bound prototype component map.
        // Datagen runs before the normal server reload applies those maps, while
        // the custom recipe providers only serialize explicit item/count/patch
        // data. Bind an empty prototype for this isolated generator process so
        // those providers can continue to use ItemStack without changing runtime
        // component initialization.
        BuiltInRegistries.ITEM.listElements().forEach(holder -> holder.bindComponents(DataComponentMap.EMPTY));
    }
}
