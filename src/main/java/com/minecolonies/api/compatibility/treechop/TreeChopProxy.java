package com.minecolonies.api.compatibility.treechop;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The TreeChop compatibility for when TreeChop is not present. It names no TreeChop type, so this class loads without the mod; {@link TreeChopCompat} replaces
 * it when the mod is loaded.
 */
public class TreeChopProxy
{
    /**
     * What one chop through TreeChop did.
     */
    public enum ChopOutcome
    {
        /**
         * TreeChop did not take the chop (not a tree for TreeChop, tool not allowed, protected spot, or TreeChop absent or off). Use the normal path.
         */
        IGNORED,
        /**
         * The log was chopped and the tree still stands: chop again.
         */
        CHOPPED,
        /**
         * The whole tree fell.
         */
        FELLED,
        /**
         * TreeChop threw. Use the normal path for this tree.
         */
        FAILED
    }

    /**
     * @return true if TreeChop is loaded.
     */
    public boolean isPresent()
    {
        return false;
    }

    /**
     * @return true if TreeChop is loaded and its server config has chopping enabled.
     */
    public boolean isEnabled()
    {
        return false;
    }

    /**
     * Turn TreeChop's server config switch on or off (GameTests).
     *
     * @param enabled the new value.
     * @return the old value.
     */
    public boolean setEnabled(final boolean enabled)
    {
        return false;
    }

    /**
     * @param state a block state.
     * @return true if the state is TreeChop's chopped log.
     */
    public boolean isChoppedLog(@NotNull final BlockState state)
    {
        return false;
    }

    /**
     * The log a chopped log was made from. Server thread only: it reads the block entity.
     *
     * @param level the level.
     * @param pos   the position.
     * @return the original log state, or null if the block is not a chopped log or the state is unknown.
     */
    @Nullable
    public BlockState getOriginalLogState(@NotNull final BlockGetter level, @NotNull final BlockPos pos)
    {
        return null;
    }

    /**
     * @param level the level.
     * @param pos   the position.
     * @return true if TreeChop can chop the block there.
     */
    public boolean isChoppable(@NotNull final BlockGetter level, @NotNull final BlockPos pos)
    {
        return false;
    }

    /**
     * How many chops one action of a citizen counts, so a tree never needs much more than one and a half actions per log however TreeChop counts chops.
     *
     * @param level the level.
     * @param pos   the log to chop.
     * @param logs  the logs of the tree as the Forester sees it.
     * @return at least 1.
     */
    public int chopsPerAction(@NotNull final ServerLevel level, @NotNull final BlockPos pos, final int logs)
    {
        return 1;
    }

    /**
     * Chop once through TreeChop, as a server-side fake player that never joins the player list. The caller captures the drops (they spawn in the world).
     * The tool is a copy; the caller wears the real tool.
     *
     * @param level    the level.
     * @param pos      the log to chop.
     * @param agentId  the fake player's id, one per agent: TreeChop takes one chop per player and game tick.
     * @param agentPos where the fake player stands.
     * @param tool     a copy of the citizen's axe.
     * @param numChops the chops of this action.
     * @return what happened.
     */
    @NotNull
    public ChopOutcome chop(
      @NotNull final ServerLevel level,
      @NotNull final BlockPos pos,
      @NotNull final UUID agentId,
      @NotNull final Vec3 agentPos,
      @NotNull final ItemStack tool,
      final int numChops)
    {
        return ChopOutcome.IGNORED;
    }
}
