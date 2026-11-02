package com.minecolonies.core.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.entity.pathfinding.Pathfinding;
import com.minecolonies.core.gametest.bench.BenchEnv;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.grower.TreeGrower;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/** Continuous forestry with native worker decisions, 20 TPS pacing, and explicit async path CPU accounting. */
final class ForestryPerformanceGameTests
{
    private static final int WARMUP = 2000;
    private static final int SAMPLES = 6000;

    private ForestryPerformanceGameTests() { }

    static void run(final GameTestHelper helper)
    {
        ForestryGameTests.resetTreeChop(helper);
        final var level = helper.getLevel();
        final var colony = MinecoloniesGameTests.foundGameTestColony(helper, "paced forestry performance");
        final List<com.minecolonies.core.colony.buildings.workerbuildings.BuildingLumberjack> huts = new ArrayList<>();
        for (int i = 0; i < 4; i++)
        {
            huts.add(ForestryGameTests.hut(helper, colony, new BlockPos(8 + 24 * i, 1, 8), false));
        }
        for (int x = 0; x <= 96; x++)
        {
            for (int z = 0; z <= 60; z++)
            {
                final BlockPos ground = helper.absolutePos(new BlockPos(x, 0, z));
                level.setChunkForced(ground.getX() >> 4, ground.getZ() >> 4, true);
                if (colony.getServerBuildingManager().getBuildings().values().stream().anyMatch(b -> b.isInBuilding(ground.above())))
                {
                    continue;
                }
                level.setBlockAndUpdate(ground, Blocks.GRASS_BLOCK.defaultBlockState());
                for (int y = 1; y <= 12; y++)
                {
                    level.setBlockAndUpdate(ground.above(y), Blocks.AIR.defaultBlockState());
                }
            }
        }
        final List<BlockPos> roots = new ArrayList<>();
        for (int i = 0; i < 4; i++)
        {
            roots.add(helper.absolutePos(new BlockPos(8 + 24 * i, 1, 26)));
            roots.add(helper.absolutePos(new BlockPos(8 + 24 * i, 1, 42)));
            InventoryUtils.forceItemStackToItemHandler(huts.get(i).getItemHandlerCap(), new ItemStack(Items.STONE_AXE, 16), s -> true);
        }
        for (int i = 0; i < roots.size(); i++)
        {
            grow(helper, roots.get(i), 117L + i);
        }
        helper.runAfterDelay(20, () -> {
            final List<ICitizenData> workers = new ArrayList<>();
            for (int i = 0; i < huts.size(); i++)
            {
                final var worker = ForestryGameTests.forester(helper, colony, huts.get(i), new BlockPos(14 + 24 * i, 1, 12), new ItemStack(Items.OAK_SAPLING, 64));
                worker.getEntity().orElseThrow().getRandom().setSeed(117L + i);
                workers.add(worker);
            }
            new Meter(helper, workers, roots).start();
        });
    }

    private static void grow(final GameTestHelper helper, final BlockPos root, final long seed)
    {
        final var level = helper.getLevel();
        level.setBlockAndUpdate(root, Blocks.OAK_SAPLING.defaultBlockState());
        helper.assertTrue(TreeGrower.OAK.growTree(level, level.getChunkSource().getGenerator(), root, level.getBlockState(root), RandomSource.create(seed)),
          "paced forestry oak did not grow at " + root);
    }

    private static final class Meter
    {
        private final GameTestHelper helper;
        private final List<ICitizenData> workers;
        private final List<BlockPos> roots;
        private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        private final Map<Long, Long> pathMarks = new HashMap<>();
        private final long[] serverCpu = new long[SAMPLES];
        private final long[] pathCpu = new long[SAMPLES];
        private final long[] combinedCpu = new long[SAMPLES];
        private final long[] wall = new long[SAMPLES];
        private long priorServer;
        private long priorWall;
        private long priorTick;
        private long began;
        private long deadline;
        private int samples;
        private int queueMax;
        private int activeMax;
        private int regrown;
        private int fellsAtWarmup;
        private int chopsAtWarmup;

        Meter(final GameTestHelper helper, final List<ICitizenData> workers, final List<BlockPos> roots)
        {
            this.helper = helper;
            this.workers = workers;
            this.roots = roots;
        }

        void start()
        {
            helper.assertTrue(threads.isThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled(), "thread CPU meter unavailable");
            began = helper.getLevel().getGameTime();
            priorTick = began;
            deadline = System.nanoTime();
            priorWall = deadline;
            pathDelta();
            priorServer = threads.getCurrentThreadCpuTime();
            helper.runAfterDelay(1, this::tick);
        }

        private long pathDelta()
        {
            final Map<Long, Long> now = new HashMap<>();
            long delta = 0;
            for (final long id : threads.getAllThreadIds())
            {
                final var info = threads.getThreadInfo(id);
                if (info != null && info.getThreadName().startsWith("Minecolonies Pathfinding Worker #"))
                {
                    final long cpu = threads.getThreadCpuTime(id);
                    helper.assertTrue(cpu >= 0, "pathfinder CPU clock unavailable");
                    now.put(id, cpu);
                    delta += cpu - pathMarks.getOrDefault(id, 0L);
                }
            }
            helper.assertTrue(now.keySet().containsAll(pathMarks.keySet()), "pathfinder thread ended: CPU accounting would be incomplete");
            pathMarks.clear();
            pathMarks.putAll(now);
            return delta;
        }

        private int fells()
        {
            return workers.stream().map(ForestryGameTests::ai).filter(java.util.Objects::nonNull).mapToInt(ai -> ai.getTreeChopFellCount()).sum();
        }

        private int chops()
        {
            return workers.stream().map(ForestryGameTests::ai).filter(java.util.Objects::nonNull).mapToInt(ai -> ai.getTreeChopChopCount()).sum();
        }

        private void tick()
        {
            final long nowWall = System.nanoTime();
            final long nowServer = threads.getCurrentThreadCpuTime();
            final long async = pathDelta();
            final var level = helper.getLevel();
            final long nowTick = level.getGameTime();
            final long tick = nowTick - began;
            helper.assertTrue(nowTick - priorTick == 1, "paced meter skipped a game tick");
            if (tick > WARMUP)
            {
                serverCpu[samples] = nowServer - priorServer;
                pathCpu[samples] = async;
                combinedCpu[samples] = serverCpu[samples] + async;
                wall[samples++] = nowWall - priorWall;
            }
            priorTick = nowTick;
            priorWall = nowWall;
            final var pool = Pathfinding.getExecutor();
            queueMax = Math.max(queueMax, pool.getQueue().size());
            activeMax = Math.max(activeMax, pool.getActiveCount());
            level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
            for (final var worker : workers)
            {
                worker.setSaturation(20);
                helper.assertTrue(worker.getEntity().isPresent() && worker.getEntity().orElseThrow().isAlive(), "paced forestry lost a worker");
            }
            if (tick == WARMUP)
            {
                fellsAtWarmup = fells();
                chopsAtWarmup = chops();
            }
            if (tick % 400 == 0)
            {
                for (int i = 0; i < roots.size(); i++)
                {
                    final var root = roots.get(i);
                    if (level.getBlockState(root).is(Blocks.OAK_SAPLING))
                    {
                        grow(helper, root, 117L + i);
                        regrown++;
                    }
                }
            }
            if (tick % 1000 == 0)
            {
                Log.getLogger().info("FORESTRY PERF progress tick={} samples={} fells={} chops={} regrown={} queue={} active={}", tick, samples, fells(), chops(), regrown, pool.getQueue().size(), pool.getActiveCount());
            }
            if (samples == SAMPLES)
            {
                finish();
                return;
            }
            helper.runAfterDelay(1, this::tick);
            deadline += 50_000_000L;
            while (System.nanoTime() < deadline)
            {
                LockSupport.parkNanos(deadline - System.nanoTime());
            }
            // Exclude all fixture bookkeeping and pacing from the next server-thread CPU window.
            priorServer = threads.getCurrentThreadCpuTime();
        }

        private void finish()
        {
            final JsonObject out = new JsonObject();
            out.addProperty("samples", samples);
            out.addProperty("warmupTicks", WARMUP);
            out.addProperty("targetTickNs", 50_000_000L);
            out.addProperty("worldSeed", helper.getLevel().getSeed());
            out.addProperty("fixtureSeed", 117);
            out.addProperty("workers", workers.size());
            out.addProperty("fellsWarmup", fellsAtWarmup);
            out.addProperty("fellsMeasured", fells() - fellsAtWarmup);
            out.addProperty("chopsMeasured", chops() - chopsAtWarmup);
            out.addProperty("regrown", regrown);
            out.addProperty("pathThreads", pathMarks.size());
            out.addProperty("queueMax", queueMax);
            out.addProperty("activeMax", activeMax);
            series(out, "serverCpu", serverCpu);
            series(out, "pathCpu", pathCpu);
            series(out, "combinedCpu", combinedCpu);
            series(out, "wall", wall);
            out.addProperty("pathStats", Pathfinding.Stats.summary());
            final boolean active = fells() - fellsAtWarmup >= 8 && chops() - chopsAtWarmup >= 16 && pathMarks.size() > 0;
            out.addProperty("activeWorkGate", active);
            BenchEnv.write("ai_forestry_20tps", out);
            helper.assertTrue(active, "paced forestry did not exercise enough TreeChop felling: " + out);
            Log.getLogger().info("FORESTRY PERF result samples={} measuredFells={} serverP95={} pathP95={} combinedP95={}", samples,
              fells() - fellsAtWarmup, percentile(serverCpu, .95), percentile(pathCpu, .95), percentile(combinedCpu, .95));
            helper.succeed();
        }

        private static void series(final JsonObject out, final String name, final long[] values)
        {
            final JsonArray raw = new JsonArray();
            for (final long value : values) { raw.add(value); }
            out.add(name + "NsRaw", raw);
            out.addProperty(name + "P50Ns", percentile(values, .5));
            out.addProperty(name + "P95Ns", percentile(values, .95));
        }

        private static long percentile(final long[] values, final double q)
        {
            final long[] sorted = values.clone();
            Arrays.sort(sorted);
            return sorted[Math.min(sorted.length - 1, (int) Math.floor(q * sorted.length))];
        }
    }
}
