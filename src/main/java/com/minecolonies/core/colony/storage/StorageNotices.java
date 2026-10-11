package com.minecolonies.core.colony.storage;

import com.minecolonies.api.util.MessageUtils;
import com.minecolonies.core.colony.Colony;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import static com.minecolonies.api.util.constant.TranslationConstants.COM_MINECOLONIES_COREMOD_WAREHOUSE_NETWORK_FULL;

/**
 * Messages of the storage network.
 */
final class StorageNotices
{
    private StorageNotices()
    {
    }

    /**
     * No warehouse of the colony has room left.
     *
     * @param colony the colony.
     * @param stack  the stack that found no room.
     */
    static void colonyFull(@NotNull final Colony colony, @NotNull final ItemStack stack)
    {
        MessageUtils.format(COM_MINECOLONIES_COREMOD_WAREHOUSE_NETWORK_FULL, stack.getHoverName()).sendTo(colony).forAllPlayers();
    }
}
