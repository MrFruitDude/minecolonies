package com.minecolonies.api.compatibility.treechop;

import com.minecolonies.api.util.Log;
import com.mojang.authlib.GameProfile;
import ht.treechop.api.TreeData;
import ht.treechop.api.ChopEvent;
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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

/**
 * Lets a Forester chop through TreeChop. Only created when TreeChop is loaded ({@code CompatibilityManager}); every TreeChop type stays inside method bodies.
 * <p>
 * Citizens break blocks without a break event, so TreeChop never sees them. Here a server-side fake player (one per Forester, never in the player
 * list) calls TreeChop's chop with the server config for tree size, tools and item lists. Leaf removal stays with the forester's guarded sweep. The fake player holds a
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

    private static final class ForesterChop
    {
        private final ServerLevel level;
        private final BlockPos target;
        private final Set<BlockPos> logs;
        private ChopEvent.BeforeFellEvent event;
        private boolean refused;

        private ForesterChop(final ServerLevel level, final BlockPos target, final Set<BlockPos> logs)
        {
            this.level = level;
            this.target = target;
            this.logs = logs;
        }
    }

    // One listener across server discovery/reloads. Entries live only for a synchronous server-thread call.
    private static final Map<FakePlayer, ForesterChop> ACTIVE_CHOPS = new IdentityHashMap<>();
    static
    {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, TreeChopCompat::protectForesterLeaves);
    }

    private static void protectForesterLeaves(final ChopEvent.BeforeFellEvent event)
    {
        final ForesterChop chop = ACTIVE_CHOPS.get(event.getPlayer());
        if (chop == null)
        {
            return;
        }
        chop.event = event;
        event.getFellData().setBreakLeaves(false);
        if (event.isCanceled() || event.getLevel() != chop.level || !event.getChoppedBlockPos().equals(chop.target)
              || !event.getFellData().getTree().getLogBlocksOrEmpty().equals(chop.logs))
        {
            chop.refused = true;
            event.setCanceled(true);
            return;
        }
        // The pinned pack has no later BeforeFell listener. Arbitrary added mods can change event data after LOWEST.
        // TreeChop owns the approved logs. The forester's guarded sweep owns all leaves and caps.
        event.getFellData().setBreakLeaves(false);
    }

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

        final Set<BlockPos> approvedLogs = getTreeLogs(level, pos);
        if (approvedLogs == null)
        {
            return ChopOutcome.IGNORED;
        }
        final FakePlayer agent = FakePlayerFactory.get(level, new GameProfile(agentId, AGENT_NAME));
        if (ACTIVE_CHOPS.containsKey(agent))
        {
            return ChopOutcome.IGNORED;
        }
        agent.setPos(agentPos.x, agentPos.y, agentPos.z);
        agent.setItemInHand(InteractionHand.MAIN_HAND, tool);
        final int damage = tool.getDamageValue();
        final boolean chopped;
        final ForesterChop context = new ForesterChop(level, pos.immutable(), Set.copyOf(approvedLogs));
        ACTIVE_CHOPS.put(agent, context);
        try
        {
            chopped = ChopUtil.chop(agent, level, pos, state, tool, agent, Math.max(1, numChops), true);
        }
        catch (final Throwable e)
        {
            if (!warned)
            {
                warned = true;
                Log.getLogger().warn("TreeChop failed for a Forester at {}, leaving this tree alone", pos, e);
            }
            else
            {
                Log.getLogger().debug("TreeChop failed for a Forester at {}: {}", pos, e.toString());
            }
            // Dispatch can throw before our listener observes a cancellation. An uncertain chop must never fall back to cutting.
            return ChopOutcome.REFUSED;
        }
        finally
        {
            ACTIVE_CHOPS.remove(agent);
            agent.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }

        // Cancellation still applies chops and can wear the fake axe. Never infer felling from that wear.
        // Keep the event reference so a later listener's cancellation is observed too.
        if (context.refused || (context.event != null && context.event.isCanceled()))
        {
            return ChopOutcome.REFUSED;
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
