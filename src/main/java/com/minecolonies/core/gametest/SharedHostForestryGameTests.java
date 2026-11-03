package com.minecolonies.core.gametest;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.grower.TreeGrower;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The v2 four-forester fixture with the v3 observer. */
final class SharedHostForestryGameTests
{
    private static final int[][] HUTS = {{32, 48}, {88, 48}, {32, 104}, {88, 104}};
    private static final int[][] ROOTS = {{32, 30}, {32, 14}, {88, 30}, {88, 14}, {32, 122}, {32, 138}, {88, 122}, {88, 138}};
    private static Session active;
    private SharedHostForestryGameTests() { }
    private static ItemStack axe()
    {
        final ItemStack stack = new ItemStack(Items.STONE_AXE);
        stack.set(net.minecraft.core.component.DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE);
        return stack;
    }

    static void run(final GameTestHelper helper)
    {
        helper.assertTrue(active == null, "shared forestry fixture already active");
        active = new Session(helper);
        try { active.setup(); }
        catch (final RuntimeException | Error failure)
        {
            try { clear(helper.getLevel()); } catch (final RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static void clear(final net.minecraft.server.level.ServerLevel level)
    {
        if (active != null && active.helper.getLevel() == level) { active.close(); }
    }

    private static final class Session implements SharedHostMeter.Activity
    {
        private final GameTestHelper helper;
        private final java.util.Set<net.minecraft.world.level.ChunkPos> forced = new java.util.LinkedHashSet<>();
        private List<ICitizenData> workers = List.of();
        private List<BlockPos> roots = List.of();
        private boolean closed;

        private Session(final GameTestHelper helper) { this.helper = helper; }

        private void setup()
        {
            ForestryGameTests.resetTreeChop(helper);
            final var level = helper.getLevel();
            final var colony = MinecoloniesGameTests.foundGameTestColony(helper, "paced forestry performance", new BlockPos(60, 1, 76));
            final List<com.minecolonies.core.colony.buildings.workerbuildings.BuildingLumberjack> huts = new ArrayList<>();
            for (int i = 0; i < 4; i++)
            {
                huts.add(ForestryGameTests.hut(helper, colony, new BlockPos(HUTS[i][0], 1, HUTS[i][1]), false));
            }
            for (int x = 0; x <= 120; x++)
            {
                for (int z = 0; z <= 152; z++)
                {
                    final BlockPos ground = helper.absolutePos(new BlockPos(x, 0, z));
                    if (level.setChunkForced(ground.getX() >> 4, ground.getZ() >> 4, true))
                    { forced.add(new net.minecraft.world.level.ChunkPos(ground.getX() >> 4, ground.getZ() >> 4)); }
                    // Huts beyond the common 40x32 floor need support inside their footprint too.
                    level.setBlockAndUpdate(ground, Blocks.GRASS_BLOCK.defaultBlockState());
                    if (colony.getServerBuildingManager().getBuildings().values().stream().anyMatch(b -> b.isInBuilding(ground.above())))
                    {
                        continue;
                    }
                    for (int y = 1; y <= 12; y++)
                    {
                        level.setBlockAndUpdate(ground.above(y), Blocks.AIR.defaultBlockState());
                    }
                }
            }
            roots = new ArrayList<>();
            for (int i = 0; i < 4; i++)
            {
                roots.add(helper.absolutePos(new BlockPos(ROOTS[i * 2][0], 1, ROOTS[i * 2][1])));
                roots.add(helper.absolutePos(new BlockPos(ROOTS[i * 2 + 1][0], 1, ROOTS[i * 2 + 1][1])));
                for (int spare = 0; spare < 16; spare++)
                {
                    helper.assertTrue(InventoryUtils.forceItemStackToItemHandler(huts.get(i).getItemHandlerCap(), axe(), item -> true).isEmpty(),
                      "unbreakable spare axe stock did not fit");
                }
            }
            for (int i = 0; i < roots.size(); i++)
            {
                for (int hut = 0; hut < huts.size(); hut++)
                {
                    if (hut != i / 2)
                    {
                        helper.assertTrue(huts.get(hut).getID().distSqr(roots.get(i)) > 48 * 48,
                          "another hut can search this worker's tree: hut=" + hut + " root=" + roots.get(i));
                    }
                }
                grow(helper, roots.get(i), 117L + i);
            }
            final Runnable[] ready = new Runnable[1];
            final int[] waited = {0};
            ready[0] = () -> {
                boolean ticking = true;
                final StringBuilder diagnostics = new StringBuilder();
                for (int i = 0; i < huts.size(); i++)
                {
                    final BlockPos anchor = helper.absolutePos(new BlockPos(HUTS[i][0] + 6, 1, HUTS[i][1] + 4));
                    final boolean entityTicking = com.minecolonies.api.util.WorldUtil.isEntityBlockLoaded(level, anchor);
                    final BlockPos calculated = com.minecolonies.api.util.EntityUtils.getSpawnPoint(level, anchor);
                    final var owner = com.minecolonies.api.colony.IColonyManager.getInstance().getColonyByPosFromWorld(level, anchor);
                    diagnostics.append(" worker=").append(i).append(" anchor=").append(anchor).append(" ticking=").append(entityTicking)
                      .append(" fullStatus=").append(level.getChunkAt(anchor).getFullStatus()).append(" calculated=").append(calculated)
                      .append(" feet=").append(level.getBlockState(anchor)).append(" head=").append(level.getBlockState(anchor.above()))
                      .append(" support=").append(level.getBlockState(anchor.below())).append(" owner=").append(owner == null ? "none" : owner.getID());
                    ticking &= entityTicking && calculated != null && owner == colony;
                }
                for (final BlockPos root : roots)
                {
                    final var owner = com.minecolonies.api.colony.IColonyManager.getInstance().getColonyByPosFromWorld(level, root);
                    if (owner != colony) { ticking = false; diagnostics.append(" rootOutsideColony=").append(root); }
                }
                final BlockPos low = helper.absolutePos(new BlockPos(0, 1, 0));
                final BlockPos high = helper.absolutePos(new BlockPos(120, 1, 152));
                for (int cx = low.getX() >> 4; cx <= high.getX() >> 4; cx++)
                {
                    for (int cz = low.getZ() >> 4; cz <= high.getZ() >> 4; cz++)
                    {
                        if (!com.minecolonies.api.util.WorldUtil.isEntityChunkLoaded(level, cx, cz))
                        {
                            ticking = false;
                            diagnostics.append(" notTickingWorkChunk=").append(new net.minecraft.world.level.ChunkPos(cx, cz));
                        }
                    }
                }
                if (waited[0] == 0 || ticking || waited[0] >= 200)
                {
                    Log.getLogger().info("FORESTRY PERF readiness waited={} ready={} {}", waited[0], ticking, diagnostics);
                }
                helper.assertTrue(ticking || waited[0] < 200, "forestry chunks/spawn points not ready after bounded wait: " + diagnostics);
                if (!ticking)
                {
                    waited[0] += 10;
                    helper.runAfterDelay(10, () -> ready[0].run());
                    return;
                }
                workers = new ArrayList<>();
                for (int i = 0; i < huts.size(); i++)
                {
                    final var worker = ForestryGameTests.forester(helper, colony, huts.get(i), new BlockPos(HUTS[i][0] + 6, 1, HUTS[i][1] + 4), new ItemStack(Items.OAK_SAPLING, 64));
                    Log.getLogger().info("FORESTRY PERF spawned index={} data={} entity={} added={} position={}", i, worker.getId(), worker.getEntity().isPresent(),
                      worker.getEntity().orElseThrow().isAddedToLevel(), worker.getEntity().orElseThrow().position());
                    worker.getInventory().setStackInSlot(0, axe());
                    worker.getEntity().orElseThrow().getRandom().setSeed(117L + i);
                    workers.add(worker);
                }
                new SharedHostMeter(helper, "shared_host_forestry", this).start();
            };
            helper.runAfterDelay(20, () -> ready[0].run());
        }

        private static void grow(final GameTestHelper helper, final BlockPos root, final long seed)
        {
            final var level = helper.getLevel();
            level.setBlockAndUpdate(root, Blocks.OAK_SAPLING.defaultBlockState());
            helper.assertTrue(TreeGrower.OAK.growTree(level, level.getChunkSource().getGenerator(), root, level.getBlockState(root), RandomSource.create(seed)),
              "paced forestry oak did not grow at " + root);
        }

        @Override
        public void maintain(final long tick)
        {
            final var level = helper.getLevel();
            level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
            for (final var worker : workers)
            {
                worker.setSaturation(20);
                helper.assertTrue(worker.getEntity().isPresent() && worker.getEntity().orElseThrow().isAlive(), "shared forestry lost worker");
            }
            if (tick % 400 == 0)
            {
                for (int i = 0; i < roots.size(); i++)
                {
                    final var root = roots.get(i);
                    final boolean activeTree = workers.stream().anyMatch(worker -> worker.getJob() instanceof com.minecolonies.core.colony.jobs.JobLumberjack job
                      && job.getTree() != null && job.getTree().getStumpLocations().contains(root));
                    if (!activeTree && level.getBlockState(root).is(Blocks.OAK_SAPLING)) { grow(helper, root, 117L + i); }
                }
            }
        }

        @Override
        public Map<String, Long> counters()
        {
            long fells = 0, chops = 0;
            for (final var worker : workers)
            {
                final var ai = ForestryGameTests.ai(worker);
                if (ai != null) { fells += ai.getTreeChopFellCount(); chops += ai.getTreeChopChopCount(); }
            }
            return Map.of("forester", fells, "chops", chops);
        }

        @Override
        public void verify()
        {
            helper.assertTrue(workers.size() == 4 && roots.size() == 8, "shared forestry fixture incomplete");
            for (final var worker : workers)
            {
                helper.assertTrue(worker.getEntity().isPresent() && worker.getEntity().orElseThrow().isAlive()
                  && helper.getLevel().isPositionEntityTicking(worker.getEntity().orElseThrow().blockPosition())
                  && ForestryGameTests.ai(worker) != null, "shared forestry worker unavailable");
            }
        }

        @Override
        public void close()
        {
            if (closed) { return; }
            closed = true;
            if (active == this) { active = null; }
            for (final var chunk : forced) { helper.getLevel().setChunkForced(chunk.x(), chunk.z(), false); }
            forced.clear();
        }

        @Override
        public String describe() { return "shared forestry workers=" + workers.size() + " roots=" + roots.size() + " counters=" + counters(); }
    }
}
