package com.minecolonies.api.compatibility.treechop;

import com.minecolonies.api.util.Log;
import com.mojang.authlib.GameProfile;
import ht.treechop.api.TreeData;
import ht.treechop.common.block.ChoppedLogBlock;
import ht.treechop.common.chop.ChopUtil;
import ht.treechop.common.config.ConfigHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Lets a Forester chop through TreeChop. Only created when TreeChop is loaded ({@code CompatibilityManager}); every TreeChop type stays inside method bodies.
 * <p>
 * Citizens break blocks without a break event, so TreeChop never sees them. Here a server-side fake player (one per Forester, never in the player
 * list) calls TreeChop's chop with the server config: tree size, leaves strategy, the correct tool rule and the item list apply as for a player. The fake player holds a
 * copy of the citizen's axe; TreeChop's wear lands on the copy, and the caller wears the real axe with MineColonies' own rule.
 */
public final class TreeChopCompat extends TreeChopProxy
{
    /**
     * The fake players' name. Each Forester has his own id: TreeChop's chop recursion guard ({@code preventChopRecursion}, on by default) cancels a
     * second chop of the same player in one game tick, so two Foresters chopping in the same tick as one shared player would lose a chop.
     */
    private static final String AGENT_NAME = "minecolonies_lumberjack";

    /**
     * Chops per action never exceed one and a half per log.
     */
    private static final double ACTIONS_PER_LOG = 1.5D;

    /**
     * Only the first TreeChop failure is logged with its stack trace.
     */
    private boolean warned = false;

    @Override
    public boolean isPresent()
    {
        return true;
    }

    @Override
    public boolean isEnabled()
    {
        try
        {
            return ConfigHandler.COMMON.enabled.get();
        }
        catch (final RuntimeException e)
        {
            // The config is not loaded yet.
            return false;
        }
    }

    @Override
    public boolean setEnabled(final boolean enabled)
    {
        final boolean old = isEnabled();
        ConfigHandler.COMMON.enabled.set(enabled);
        return old;
    }

    @Override
    public boolean isChoppedLog(@NotNull final BlockState state)
    {
        return state.getBlock() instanceof ChoppedLogBlock;
    }

    @Nullable
    @Override
    public BlockState getOriginalLogState(@NotNull final BlockGetter level, @NotNull final BlockPos pos)
    {
        return level.getBlockEntity(pos) instanceof ChoppedLogBlock.MyEntity entity ? entity.getOriginalState() : null;
    }

    @Override
    public boolean isChoppable(@NotNull final BlockGetter level, @NotNull final BlockPos pos)
    {
        try
        {
            return ChopUtil.isBlockChoppable(level, pos, level.getBlockState(pos));
        }
        catch (final RuntimeException e)
        {
            return false;
        }
    }

    @Nullable
    @Override
    public Set<BlockPos> getTreeLogs(@NotNull final ServerLevel level, @NotNull final BlockPos pos)
    {
        try
        {
            final Set<BlockPos> logs = new HashSet<>();
            ChopUtil.getTree(level, pos).getLogBlocksOrEmpty().forEach(log -> logs.add(log.immutable()));
            return logs.isEmpty() ? null : logs;
        }
        catch (final RuntimeException e)
        {
            return null;
        }
    }

    @Override
    public int chopsPerAction(@NotNull final ServerLevel level, @NotNull final BlockPos pos, final int logs)
    {
        try
        {
            final TreeData tree = ChopUtil.getTree(level, pos);
            final int needed = Math.max(0, tree.numChopsNeededToFell() - tree.getChops());
            return Math.max(1, (int) Math.ceil(needed / (Math.max(1, logs) * ACTIONS_PER_LOG)));
        }
        catch (final RuntimeException e)
        {
            return 1;
        }
    }

    @NotNull
    @Override
    public ChopOutcome chop(
      @NotNull final ServerLevel level,
      @NotNull final BlockPos pos,
      @NotNull final UUID agentId,
      @NotNull final Vec3 agentPos,
      @NotNull final ItemStack tool,
      final int numChops)
    {
        final BlockState state = level.getBlockState(pos);
        if (!isEnabled() || !ChopUtil.isBlockChoppable(level, pos, state))
        {
            return ChopOutcome.IGNORED;
        }

        // A felling breaks every log of the tree but the chopped one; these tell a felling from an ignored chop when the tool takes no wear.
        final List<BlockPos> neighbours = new ArrayList<>();
        for (final BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1)))
        {
            if (!near.equals(pos) && ChopUtil.isBlockChoppable(level, near, level.getBlockState(near)))
            {
                neighbours.add(near.immutable());
            }
        }

        final FakePlayer agent = FakePlayerFactory.get(level, new GameProfile(agentId, AGENT_NAME));
        agent.setPos(agentPos.x, agentPos.y, agentPos.z);
        agent.setItemInHand(InteractionHand.MAIN_HAND, tool);
        final int damage = tool.getDamageValue();
        final boolean chopped;
        try
        {
            chopped = ChopUtil.chop(agent, level, pos, state, tool, agent, Math.max(1, numChops), true);
        }
        catch (final Throwable e)
        {
            if (!warned)
            {
                warned = true;
                Log.getLogger().warn("TreeChop failed for a Forester at {}, using the normal path for this tree", pos, e);
            }
            else
            {
                Log.getLogger().debug("TreeChop failed for a Forester at {}: {}", pos, e.toString());
            }
            return ChopOutcome.FAILED;
        }
        finally
        {
            agent.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }

        if (chopped)
        {
            return ChopOutcome.CHOPPED;
        }

        boolean felled = tool.getDamageValue() != damage;
        for (final BlockPos near : neighbours)
        {
            felled |= !ChopUtil.isBlockChoppable(level, near, level.getBlockState(near));
        }
        if (!felled)
        {
            return ChopOutcome.IGNORED;
        }

        // For a player the chopped block breaks after the event; here nothing breaks it, so harvest it like TreeChop harvests the other logs.
        final BlockState left = level.getBlockState(pos);
        if (!left.isAir())
        {
            left.getBlock().destroy(level, pos, left);
            Block.dropResources(left, level, pos, level.getBlockEntity(pos), null, tool);
            level.setBlockAndUpdate(pos, level.getFluidState(pos).createLegacyBlock());
        }
        return ChopOutcome.FELLED;
    }
}
