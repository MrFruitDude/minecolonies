package com.minecolonies.api.util;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities.Item;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import com.ldtteam.structurize.api.compat.itemhandler.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * Our class for to join {@link IItemHandler} providers, so we can have type independent code.
 */
@FunctionalInterface
public interface IItemHandlerCapProvider
{
    /**
     * For EntityCap register only
     */
    @Nullable
    default IItemHandler getItemHandlerCap(final Void nothing)
    {
        return getItemHandlerCap();
    }

    /**
     * @return direction-unaware itemHandler
     */
    @Nullable
    default IItemHandler getItemHandlerCap()
    {
        return getItemHandlerCap((Direction) null);
    }

    /**
     * @return direction-aware itemHandler
     */
    @Nullable
    IItemHandler getItemHandlerCap(final Direction direction);

    public static IItemHandlerCapProvider wrap(final BlockEntity blockEntity)
    {
        // Our own block entities register their capability as an adapter over this very handler; going through the
        // capability would wrap it twice (IItemHandler -> ResourceHandler -> IItemHandler) for every slot operation.
        if (blockEntity instanceof IItemHandlerCapProvider provider)
        {
            return provider;
        }
        return direction -> ofNullable(Item.BLOCK.getCapability(blockEntity.getLevel(), blockEntity.getBlockPos(), blockEntity.getBlockState(), blockEntity, direction));
    }

    /**
     * @param sided if true then will use Direction aware capability, roughly should be true for machine-entities and false for mobs
     */
    public static IItemHandlerCapProvider wrap(final Entity entity, final boolean sided)
    {
        // Citizens and visitors register only the unsided capability, as an adapter over their own handler.
        if (!sided && entity instanceof IItemHandlerCapProvider provider)
        {
            return provider;
        }
        return sided ? direction -> ofNullable(Item.ENTITY_AUTOMATION.getCapability(entity, direction)) :
            direction -> ofNullable(Item.ENTITY.getCapability(entity, null));
    }

    public static IItemHandlerCapProvider wrap(final ItemStack itemStack)
    {
        return direction -> ofNullable(Item.ITEM.getCapability(itemStack, null));
    }

    /**
     * Capabilities are absent for targets without an inventory; keep that as null (like 1.21) instead of letting
     * {@link IItemHandler#of} throw on it.
     */
    @Nullable
    private static IItemHandler ofNullable(@Nullable final ResourceHandler<ItemResource> handler)
    {
        return handler == null ? null : IItemHandler.of(handler);
    }
}
