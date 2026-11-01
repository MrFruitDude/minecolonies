package com.minecolonies.core.gametest;

import com.minecolonies.api.compatibility.Compatibility;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingLumberjack;
import com.minecolonies.core.colony.jobs.JobLumberjack;
import com.minecolonies.core.entity.ai.workers.production.EntityAIWorkLumberjack;
import com.minecolonies.core.entity.ai.workers.util.Tree;
import com.minecolonies.core.entity.ai.workers.util.TreeClaims;

import com.minecolonies.api.compatibility.treechop.TreeChopProxy;
import com.minecolonies.api.util.Log;
import ht.treechop.api.ChopEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Loaded only by the TreeChop test lane; the production mod never includes GameTest classes. */
final class TreeChopGuardGameTests
{
    private static VetoCase active;

    private static final class VetoCase
    {
        final ServerLevel level;
        final BlockPos target;
        final UUID actor;
        final String mode;
        int damageBeforeVeto = -1;
        boolean seen;

        VetoCase(final ServerLevel level, final BlockPos target, final UUID actor, final String mode)
        {
            this.level = level;
            this.target = target;
            this.actor = actor;
            this.mode = mode;
        }
    }

    static
    {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, true, (ChopEvent.BeforeFellEvent event) -> veto(event, false));
        // Registered after the production listener: exercise cancellation by a later listener as well.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, (ChopEvent.BeforeFellEvent event) -> veto(event, true));
    }

    static void clear()
    {
        active = null;
    }

    private static void veto(final ChopEvent.BeforeFellEvent event, final boolean late)
    {
        final VetoCase test = active;
        if (test == null || event.getLevel() != test.level || !event.getChoppedBlockPos().equals(test.target)
              || !event.getPlayer().getUUID().equals(test.actor) || late != test.mode.equals("late"))
        {
            return;
        }
        test.seen = true;
        test.damageBeforeVeto = event.getPlayer().getMainHandItem().getDamageValue();
        if (test.mode.equals("mismatch"))
        {
            final Set<BlockPos> changed = new HashSet<>(event.getFellData().getTree().getLogBlocksOrEmpty());
            changed.add(test.target.offset(10, 0, 0));
            event.getFellData().getTree().setLogBlocks(changed);
        }
        else
        {
            event.setCanceled(true);
            if (test.mode.equals("throw"))
            {
                throw new IllegalStateException("GameTest cancelled TreeChop dispatch");
            }
        }
    }

    private static BlockPos grow(final GameTestHelper helper, final BlockPos relative, final long seed)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos base = helper.absolutePos(relative);
        level.setBlockAndUpdate(base.below(), Blocks.GRASS_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(base, Blocks.OAK_SAPLING.defaultBlockState());
        helper.assertTrue(TreeGrower.OAK.growTree(level, level.getChunkSource().getGenerator(), base, level.getBlockState(base), RandomSource.create(seed)),
          "guard fixture oak failed to grow");
        return base;
    }

    static void workerTest(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "worker veto");
        final ServerLevel level = helper.getLevel();
        final BuildingLumberjack hut = ForestryGameTests.hut(helper, colony, new BlockPos(8, 1, 2), false);
        ForestryGameTests.resetTreeChop(helper);
        final BlockPos base = grow(helper, new BlockPos(24, 1, 10), 61L);
        final BlockPos next = grow(helper, new BlockPos(32, 1, 24), 62L);
        final Set<BlockPos> protectedLogs = Set.copyOf(Compatibility.treeChop.getTreeLogs(level, base));
        final Set<BlockPos> nextLogs = Set.copyOf(Compatibility.treeChop.getTreeLogs(level, next));
        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = ForestryGameTests.forester(helper, colony, hut, new BlockPos(21, 1, 10));
            final UUID actor = UUID.nameUUIDFromBytes(("minecolonies_lumberjack:" + colony.getID() + ":" + worker.getId()).getBytes(StandardCharsets.UTF_8));
            final VetoCase test = new VetoCase(level, base, actor, "early");
            active = test;
            ((JobLumberjack) worker.getJob()).setTree(new Tree(level, base.above(), colony));
            final long[] refusedAt = {-1};
            ForestryGameTests.pump(helper, java.util.List.of(worker), 24_000, () -> {
                final EntityAIWorkLumberjack ai = ForestryGameTests.ai(worker);
                if (ai == null || ai.getTreeChopRefusedCount() < 1)
                {
                    return false;
                }
                if (refusedAt[0] < 0)
                {
                    refusedAt[0] = level.getGameTime();
                    helper.assertTrue(test.seen && hut.getSkipReason(base) != null, "worker veto was not recorded by hut");
                    helper.assertTrue(TreeClaims.isBlocked(level, base), "refused tree was not blocked");
                }
                return level.getGameTime() - refusedAt[0] >= TreeClaims.BLOCKED_CLAIM_TICKS
                  && nextLogs.stream().noneMatch(p -> level.getBlockState(p).is(Blocks.OAK_LOG) || Compatibility.treeChop.isChoppedLog(level.getBlockState(p)))
                  && ai.getTreeChopFellCount() >= 1;
            }, () -> ForestryGameTests.describe(worker) + ", veto observed " + test.seen, () -> {
                if (refusedAt[0] < 0 || level.getGameTime() - refusedAt[0] < TreeClaims.BLOCKED_CLAIM_TICKS)
                {
                    for (final BlockPos log : protectedLogs)
                    {
                        helper.assertTrue(level.getBlockState(log).is(Blocks.OAK_LOG) || Compatibility.treeChop.isChoppedLog(level.getBlockState(log)),
                          "worker fell back to cutting vetoed log " + log);
                    }
                }
            }, "forestry_treechop_veto_worker");
        });
    }

    static void test(final GameTestHelper helper, final String mode)
    {
        MinecoloniesGameTests.foundGameTestColony(helper, "treechop veto " + mode);
        final ServerLevel level = helper.getLevel();
        ForestryGameTests.resetTreeChop(helper);
        final BlockPos base = grow(helper, new BlockPos(16, 1, 16), 61L);
        final BlockPos next = grow(helper, new BlockPos(30, 1, 16), 62L);
        final Set<BlockPos> logs = Compatibility.treeChop.getTreeLogs(level, base);
        helper.assertTrue(logs != null && logs.contains(base), "no original TreeChop logs");
        final UUID actor = UUID.nameUUIDFromBytes(("veto " + mode).getBytes(StandardCharsets.UTF_8));
        final ItemStack axe = new ItemStack(Items.IRON_AXE);
        final VetoCase test = new VetoCase(level, base, actor, mode);
        active = test;
        final int[] ticks = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            final TreeChopProxy.ChopOutcome outcome = Compatibility.treeChop.chop(level, base, actor, Vec3.atCenterOf(base.north(2)), axe, 1);
            if (test.seen)
            {
                helper.assertTrue(outcome == TreeChopProxy.ChopOutcome.REFUSED, "veto reported " + outcome + " instead of REFUSED");
                if (!mode.equals("throw"))
                {
                    helper.assertTrue(axe.getDamageValue() > test.damageBeforeVeto, "fixture did not exercise cancelled chop tool wear");
                }
                for (final BlockPos log : logs)
                {
                    helper.assertTrue(level.getBlockState(log).is(Blocks.OAK_LOG) || Compatibility.treeChop.isChoppedLog(level.getBlockState(log)),
                      "cancelled felling removed original log " + log + ": " + level.getBlockState(log));
                }
                clear();
                helper.runAfterDelay(1, () -> {
                    final TreeChopProxy.ChopOutcome fresh = Compatibility.treeChop.chop(level, next, actor, Vec3.atCenterOf(next.north(2)), axe, 1);
                    helper.assertTrue(fresh == TreeChopProxy.ChopOutcome.CHOPPED || fresh == TreeChopProxy.ChopOutcome.FELLED,
                      "call context leaked after refusal: " + fresh);
                    Log.getLogger().info("[treechop veto] mode={} damageBefore={} damageAfter={} cleanup={}", mode, test.damageBeforeVeto, axe.getDamageValue(), fresh);
                    helper.succeed();
                });
                return;
            }
            helper.assertTrue(outcome != TreeChopProxy.ChopOutcome.FELLED && ++ticks[0] < 100, "veto felling event was not observed");
            helper.runAfterDelay(1, () -> step[0].run());
        };
        helper.runAfterDelay(1, () -> step[0].run());
    }
}
