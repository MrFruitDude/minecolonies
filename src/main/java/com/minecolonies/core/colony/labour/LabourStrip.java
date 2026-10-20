package com.minecolonies.core.colony.labour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.BlockTransformer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockTransformers;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Stripping a log the way an axe does: the axe's own block transformer (its {@code block_transformer} component, data driven) says what the log at a position
 * becomes, so the species and the axis are the game's, not a name guess. Nothing here changes the world; the session sets the block and wears the axe.
 */
public final class LabourStrip
{
    private LabourStrip()
    {
    }

    /**
     * What the transformer makes of a block.
     *
     * @param state  the stripped state.
     * @param damage the wear one use costs the tool.
     */
    public record Result(BlockState state, int damage)
    {
    }

    /**
     * @return the world's standard axe transformer (what planning assumes), or null when the registry has none.
     */
    @Nullable
    public static Holder<BlockTransformer> standardAxe(final ServerLevel level)
    {
        try
        {
            return level.registryAccess().lookupOrThrow(Registries.BLOCK_TRANSFORMER).get(BlockTransformers.AXE).orElse(null);
        }
        catch (final RuntimeException e)
        {
            return null;
        }
    }

    /**
     * @return the transformer the tool uses on blocks, or null (not an axe of this kind).
     */
    @Nullable
    public static Holder<BlockTransformer> of(final ItemStack tool)
    {
        return tool.isEmpty() ? null : tool.get(DataComponents.BLOCK_TRANSFORMER);
    }

    /**
     * @param level       the level.
     * @param pos         the block.
     * @param transformer the transformer.
     * @return what the transformer turns the block into (a different block), or null.
     */
    @Nullable
    public static Result stripped(final ServerLevel level, final BlockPos pos, @Nullable final Holder<BlockTransformer> transformer)
    {
        if (transformer == null || !level.isLoaded(pos))
        {
            return null;
        }
        final BlockState current = level.getBlockState(pos);
        try
        {
            for (final BlockTransformer.BlockTransformData data : transformer.value().transforms())
            {
                final BlockState next = data.blockStateProvider().value().getOptionalState(level, level.getRandom(), pos);
                if (next != null && !next.isAir() && next.getBlock() != current.getBlock())
                {
                    return new Result(next, Math.max(0, data.itemDamagePerUse()));
                }
            }
        }
        catch (final RuntimeException e)
        {
            return null;
        }
        return null;
    }
}
