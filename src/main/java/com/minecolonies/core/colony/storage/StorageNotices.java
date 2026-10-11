package com.minecolonies.core.colony.storage;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.util.MessageUtils;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;
import com.minecolonies.core.colony.Colony;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import static com.minecolonies.api.util.constant.TranslationConstants.COM_MINECOLONIES_COREMOD_WAREHOUSE_NETWORK_FULL;

/**
 * Messages of the storage network.
 */
public final class StorageNotices
{
    /**
     * Where "the colony storage is full" goes instead of the chat line (Colony Command routes it to its own notification path).
     */
    @Nullable
    private static volatile BiConsumer<IColony, ItemStack> sink;

    private StorageNotices()
    {
    }

    /**
     * @param newSink replaces the chat message; null brings the chat message back.
     */
    public static void setSink(@Nullable final BiConsumer<IColony, ItemStack> newSink)
    {
        sink = newSink;
    }

    /**
     * No warehouse of the colony has room left.
     *
     * @param colony the colony.
     * @param stack  the stack that found no room.
     */
    static void colonyFull(@NotNull final Colony colony, @NotNull final ItemStack stack)
    {
        final BiConsumer<IColony, ItemStack> target = sink;
        if (target != null)
        {
            target.accept(colony, stack);
            return;
        }
        MessageUtils.format(COM_MINECOLONIES_COREMOD_WAREHOUSE_NETWORK_FULL, stack.getHoverName()).sendTo(colony).forAllPlayers();
    }
}
