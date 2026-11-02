package com.minecolonies.core.gametest;

import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.entity.pathfinding.navigation.MinecoloniesAdvancedPathNavigate;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Natural work decisions and physics; the fixture changes access, never the worker's state or position after spawn. */
final class RecoveryJourneyGameTests
{
    private RecoveryJourneyGameTests() { }

    static void hutSnapThenPlant(final GameTestHelper helper)
    {
        run(helper, false);
    }

    static void hutDoorReopens(final GameTestHelper helper)
    {
        run(helper, true);
    }

    private static void run(final GameTestHelper helper, final boolean reopenBeforeArrival)
    {
        final var level = helper.getLevel();
        final var colony = MinecoloniesGameTests.foundGameTestColony(helper, "natural hut return and plant");
        final BlockPos hutRelative = new BlockPos(8, 1, 2);
        final var hut = ForestryGameTests.hut(helper, colony, hutRelative, true);
        final BlockPos center = helper.absolutePos(hutRelative);
        // Extend supported, plantable ground across the whole planting ring, preserving building blocks.
        for (int x = -20; x < 36; x++)
        {
            for (int z = -20; z < 36; z++)
            {
                final BlockPos ground = helper.absolutePos(new BlockPos(x, 0, z));
                level.setChunkForced(ground.getX() >> 4, ground.getZ() >> 4, true);
                if (colony.getServerBuildingManager().getBuildings().values().stream().anyMatch(b -> b.isInBuilding(ground.above())))
                {
                    continue;
                }
                level.setBlockAndUpdate(ground, Blocks.GRASS_BLOCK.defaultBlockState());
                for (int y = 1; y <= 8; y++)
                {
                    level.setBlockAndUpdate(ground.above(y), Blocks.AIR.defaultBlockState());
                }
            }
        }
        final List<BlockPos> shell = new ArrayList<>();
        final List<BlockPos> door = List.of(center.offset(5, 0, 0), center.offset(5, 1, 0));
        for (int x = -5; x <= 5; x++)
        {
            for (int z = -5; z <= 5; z++)
            {
                for (int y = 0; y <= 4; y++)
                {
                    if (Math.abs(x) == 5 || Math.abs(z) == 5 || y == 4)
                    {
                        final BlockPos pos = center.offset(x, y, z);
                        shell.add(pos);
                        level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }
        InventoryUtils.forceItemStackToItemHandler(hut.getItemHandlerCap(), new ItemStack(Items.OAK_SAPLING, 16), stack -> true);
        helper.runAfterDelay(20, () -> {
            final var worker = ForestryGameTests.forester(helper, colony, hut, new BlockPos(30, 1, 20));
            final var entity = worker.getEntity().orElseThrow();
            final var nav = (MinecoloniesAdvancedPathNavigate) entity.getNavigation();
            nav.enableNavigationTrace();
            final Vec3 start = entity.position();
            final long began = level.getGameTime();
            final long[] arrived = {-1};
            final boolean[] moved = {false};
            final boolean[] fetched = {false};
            final boolean[] opened = {false};
            final int[] stationary = {0};
            final int[] blockedSamples = {0};
            final Vec3[] previous = {start};
            final String[] observation = {"not started"};
            ForestryGameTests.pump(helper, List.of(worker), 24_000, () -> {
                int saplings = 0;
                int outsideSaplings = 0;
                for (final BlockPos pos : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(-20, 1, -20)), helper.absolutePos(new BlockPos(35, 1, 35))))
                {
                    if (level.getBlockState(pos).is(Blocks.OAK_SAPLING))
                    {
                        saplings++;
                        if (Math.abs(pos.getX() - center.getX()) > 5 || Math.abs(pos.getZ() - center.getZ()) > 5)
                        {
                            outsideSaplings++;
                        }
                    }
                }
                final int inHut = InventoryUtils.getCountFromBuilding(hut, stack -> stack.is(Items.OAK_SAPLING));
                final int carried = InventoryUtils.getItemCountInItemHandler(worker.getInventory(), stack -> stack.is(Items.OAK_SAPLING));
                fetched[0] |= carried > 0 && inHut < 16;
                final var ai = ForestryGameTests.ai(worker);
                observation[0] = "elapsed=" + (level.getGameTime() - began) + " arrived=" + arrived[0] + " moved=" + moved[0]
                  + " fetched=" + fetched[0] + " hut=" + inHut + " carried=" + carried + " planted=" + saplings + " outside=" + outsideSaplings;
                helper.assertTrue(inHut + carried + saplings == 16, "saplings lost or duplicated: " + observation[0]);
                if (arrived[0] >= 0 && fetched[0] && outsideSaplings >= (reopenBeforeArrival ? 3 : 1)
                  && ai != null && ai.getPlantedTotal() >= (reopenBeforeArrival ? 3 : 1)
                  && (!reopenBeforeArrival || level.getGameTime() - arrived[0] >= 600))
                {
                    Log.getLogger().info("RECOVERY RESULT {} {}", observation[0], nav.navigationTraceHistory());
                    return true;
                }
                return false;
            }, () -> observation[0] + " " + ForestryGameTests.describe(worker) + " " + nav.navigationTraceHistory(), () -> {
                helper.assertTrue(entity.isAlive() && !entity.isRemoved(), "return journey lost its worker");
                moved[0] |= entity.position().distanceToSqr(start) >= 9 && !nav.isDone();
                stationary[0] = entity.position().distanceToSqr(previous[0]) < 0.001 ? stationary[0] + 1 : 0;
                if (!opened[0] && stationary[0] >= 40 && entity.position().distanceToSqr(start) >= 9)
                {
                    blockedSamples[0]++;
                }
                if (reopenBeforeArrival)
                {
                    helper.assertTrue(entity.position().distanceToSqr(previous[0]) < 16, "hut reroute teleported " + nav.navigationTraceHistory());
                    final var ai = ForestryGameTests.ai(worker);
                    helper.assertTrue(ai == null || ai.putBackCount() == 0, "hut reroute used the work-handler snap");
                    if (!opened[0] && level.getGameTime() - began == 400)
                    {
                        helper.assertTrue(moved[0] && blockedSamples[0] > 0 && arrived[0] < 0, "door reopened without a blocked approach");
                        opened[0] = true;
                        door.forEach(pos -> level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
                        Log.getLogger().info("RECOVERY DOOR opened at fixed elapsed=400 blockedSamples={}", blockedSamples[0]);
                    }
                }
                previous[0] = entity.position();
                if (arrived[0] < 0 && Math.abs(entity.getY() - center.getY()) < 1
                  && Math.abs(entity.getX() - center.getX()) < 4 && Math.abs(entity.getZ() - center.getZ()) < 4)
                {
                    helper.assertTrue(moved[0], "worker arrived without beginning a real journey");
                    helper.assertTrue(level.getGameTime() - began >= 200, "sealed hut was reached before a recovery episode");
                    arrived[0] = level.getGameTime();
                    helper.assertTrue(!reopenBeforeArrival || opened[0], "entered before the fixed door opening");
                    if (!reopenBeforeArrival)
                    {
                        // Legacy snap coverage only. This branch is not navigation recovery evidence.
                        opened[0] = true;
                        door.forEach(pos -> level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
                    }
                }
                for (final BlockPos pos : shell)
                {
                    helper.assertTrue(opened[0] && door.contains(pos) || level.getBlockState(pos).is(Blocks.STONE), "worker damaged the sealed hut at " + pos);
                }
            }, reopenBeforeArrival ? "forestry_hut_door_reroute_plant" : "forestry_hut_snap_exit_plant");
        });
    }
}
