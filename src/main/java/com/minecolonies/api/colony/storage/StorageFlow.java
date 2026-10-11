package com.minecolonies.api.colony.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * Items that moved between a building and a warehouse (or between warehouses), merged per route and item.
 *
 * @param from     where they were taken from (a warehouse or another building).
 * @param to       where they were put.
 * @param stack    the item (count 1).
 * @param count    how many.
 * @param lastTick game time of the latest move merged into this flow.
 */
public record StorageFlow(BlockPos from, BlockPos to, ItemStack stack, int count, long lastTick)
{
}
