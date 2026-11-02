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
    private static final int INVOCATION_WARMUP = 20_000;
    private static final int SETTLE = 2000;
    private static final int WARMUP = INVOCATION_WARMUP + SETTLE;
    private static final long WARMUP_CAP_NS = 120_000_000_000L;
    private static final int SAMPLES = 6000;
    private static final int[][] HUTS = {{32, 48}, {88, 48}, {32, 104}, {88, 104}};
    private static final int[][] ROOTS = {{32, 30}, {32, 14}, {88, 30}, {88, 14}, {32, 122}, {32, 138}, {88, 122}, {88, 138}};

    private static ItemStack axe()
    {
        final ItemStack stack = new ItemStack(Items.STONE_AXE);
        stack.set(net.minecraft.core.component.DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE);
        return stack;
    }

    private ForestryPerformanceGameTests() { }

    static void run(final GameTestHelper helper)
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
                level.setChunkForced(ground.getX() >> 4, ground.getZ() >> 4, true);
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
        final List<BlockPos> roots = new ArrayList<>();
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
            final List<ICitizenData> workers = new ArrayList<>();
            for (int i = 0; i < huts.size(); i++)
            {
                final var worker = ForestryGameTests.forester(helper, colony, huts.get(i), new BlockPos(HUTS[i][0] + 6, 1, HUTS[i][1] + 4), new ItemStack(Items.OAK_SAPLING, 64));
                Log.getLogger().info("FORESTRY PERF spawned index={} data={} entity={} added={} position={}", i, worker.getId(), worker.getEntity().isPresent(),
                  worker.getEntity().orElseThrow().isAddedToLevel(), worker.getEntity().orElseThrow().position());
                worker.getInventory().setStackInSlot(0, axe());
                worker.getEntity().orElseThrow().getRandom().setSeed(117L + i);
                workers.add(worker);
            }
            new Meter(helper, workers, huts, roots).start();
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

    private static final class Meter
    {
        private final GameTestHelper helper;
        private final List<ICitizenData> workers;
        private final List<BlockPos> roots;
        private final List<com.minecolonies.core.colony.buildings.workerbuildings.BuildingLumberjack> huts;
        private long swapInAtWarmup;
        private ForestryNoiseMonitor noise;
        private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        private final Map<Long, Long> pathMarks = new HashMap<>();
        private final long[] serverCpu = new long[SAMPLES];
        private final long[] pathCpu = new long[SAMPLES];
        private final long[] combinedCpu = new long[SAMPLES];
        private final long[] wall = new long[SAMPLES];
        private final long[] busyWall = new long[SAMPLES];
        private final JsonArray telemetry = new JsonArray();
        private long warmupStartedWall;
        private long priorBusyWall;
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
        private final int[] workerFellsAtWarmup = new int[4];
        private final int[] blockFells = new int[6];
        private int lastBlockFells;
        private long completedAtWarmup;
        private long startedAtWarmup;
        private int newThreadsMeasured;
        private long unassignedFirstThreadCpu;
        private int overrunsMeasured;
        private long maxOverrunNs;

        Meter(final GameTestHelper helper, final List<ICitizenData> workers,
          final List<com.minecolonies.core.colony.buildings.workerbuildings.BuildingLumberjack> huts, final List<BlockPos> roots)
        {
            this.helper = helper;
            this.workers = workers;
            this.roots = roots;
            this.huts = huts;
        }

        void start()
        {
            helper.assertTrue(threads.isThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled(), "thread CPU meter unavailable");
            noise = new ForestryNoiseMonitor();
            began = helper.getLevel().getGameTime();
            priorTick = began;
            deadline = System.nanoTime();
            warmupStartedWall = deadline;
            priorBusyWall = deadline;
            priorWall = deadline;
            recordTelemetry(0);
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
                    final Long previous = pathMarks.get(id);
                    if (previous == null)
                    {
                        // Its earlier lifetime does not belong to this interval. Invalidate measured windows with a new thread.
                        unassignedFirstThreadCpu += cpu;
                        if (helper.getLevel().getGameTime() - began > WARMUP) { newThreadsMeasured++; }
                    }
                    else
                    {
                        delta += cpu - previous;
                    }
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
            try { tickMeasured(); }
            catch (final RuntimeException | Error failure)
            {
                noise.abort(failure);
                throw failure;
            }
        }

        private void tickMeasured()
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
                busyWall[samples] = nowWall - priorBusyWall;
                wall[samples++] = nowWall - priorWall;
            }
            // Retain the failing boundary's worker state before any resource gate can abort.
            if (tick > WARMUP && (tick - WARMUP) % 1000 == 0) { recordTelemetry(tick); }
            if (tick <= WARMUP) { noise.warmup(tick); }
            else { noise.sample(nowTick, samples, serverCpu[samples - 1], pathCpu[samples - 1], wall[samples - 1], busyWall[samples - 1]); }
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
                recordTelemetry(tick);
                stationarityPrefix(new long[] {1L, 1L}, 2);
                noise.beginMeasurement();
                swapInAtWarmup = hostCounter("pswpin");
                fellsAtWarmup = fells();
                chopsAtWarmup = chops();
                lastBlockFells = fellsAtWarmup;
                completedAtWarmup = pool.getCompletedTaskCount();
                startedAtWarmup = Pathfinding.Stats.started.get();
                for (int i = 0; i < workers.size(); i++)
                {
                    final var ai = ForestryGameTests.ai(workers.get(i));
                    workerFellsAtWarmup[i] = ai == null ? 0 : ai.getTreeChopFellCount();
                }
            }
            if (tick > WARMUP && (tick - WARMUP) % 1000 == 0)
            {
                final int fellNow = fells();
                blockFells[(int) (tick - WARMUP) / 1000 - 1] = fellNow - lastBlockFells;
                lastBlockFells = fellNow;

                helper.assertTrue(blockFells[(int) (tick - WARMUP) / 1000 - 1] > 0, "v2 block has no fells");
                final double spread = stationarityPrefix(serverCpu, samples);
                helper.assertTrue(spread <= 1.10, "v2 stationarity block spread already exceeds 1.10");
            }
            if (tick % 400 == 0)
            {
                for (int i = 0; i < roots.size(); i++)
                {
                    final var root = roots.get(i);
                    final boolean activeTree = workers.stream().anyMatch(worker -> worker.getJob() instanceof com.minecolonies.core.colony.jobs.JobLumberjack job
                      && job.getTree() != null && job.getTree().getStumpLocations().contains(root));
                    if (!activeTree && level.getBlockState(root).is(Blocks.OAK_SAPLING))
                    {
                        grow(helper, root, 117L + i);
                        regrown++;
                    }
                }
            }
            if (tick % 1000 == 0)
            {
                if (tick <= WARMUP) { recordTelemetry(tick); }
                Log.getLogger().info("FORESTRY PERF progress tick={} samples={} fells={} chops={} regrown={} queue={} active={}", tick, samples, fells(), chops(), regrown, pool.getQueue().size(), pool.getActiveCount());
            }
            if (samples == SAMPLES)
            {
                finish();
                return;
            }
            helper.runAfterDelay(1, this::tick);
            if (tick < INVOCATION_WARMUP)
            {
                helper.assertTrue(System.nanoTime() - warmupStartedWall <= WARMUP_CAP_NS, "invocation warmup exceeded fixed 120-second cap");
                // Same bounded drain as MacroRun, only in unmeasured invocation warmup.
                final long pathDeadline = System.nanoTime() + 200_000_000L;
                while ((!pool.getQueue().isEmpty() || pool.getActiveCount() > 0) && System.nanoTime() < pathDeadline)
                {
                    LockSupport.parkNanos(20_000L);
                }
                priorServer = threads.getCurrentThreadCpuTime();
                priorBusyWall = System.nanoTime();
                return;
            }
            if (tick == INVOCATION_WARMUP)
            {
                helper.assertTrue(System.nanoTime() - warmupStartedWall <= WARMUP_CAP_NS, "invocation warmup exceeded fixed 120-second cap");
                deadline = System.nanoTime();
            }
            deadline += 50_000_000L;
            final long lag = System.nanoTime() - deadline;
            if (tick > WARMUP && lag > 0)
            {
                overrunsMeasured++;
                maxOverrunNs = Math.max(maxOverrunNs, lag);
            }
            while (System.nanoTime() < deadline)
            {
                LockSupport.parkNanos(deadline - System.nanoTime());
            }
            // Exclude all fixture bookkeeping and pacing from the next server-thread CPU window.
            priorServer = threads.getCurrentThreadCpuTime();
            priorBusyWall = System.nanoTime();
        }

        private void finish()
        {
            final JsonObject out = new JsonObject();
            out.addProperty("samples", samples);
            out.addProperty("warmupTicks", WARMUP);
            out.addProperty("invocationWarmupTicks", INVOCATION_WARMUP);
            out.addProperty("pacedSettleTicks", SETTLE);
            out.addProperty("invocationWarmupCapNs", WARMUP_CAP_NS);
            out.add("jvmTelemetryPer1000Ticks", telemetry);
            final long swappedIn = hostCounter("pswpin") - swapInAtWarmup;
            // Global swap is context only under protocol v2, never a validity gate.
            out.addProperty("hostSwapInPagesMeasured", swappedIn);
            out.addProperty("hostSwapContextOnly", true);
            out.add("protocolV2Noise", noise.finish());
            final JsonArray warmupFells = new JsonArray();
            for (final int count : workerFellsAtWarmup) { warmupFells.add(count); }
            out.add("warmupFellsPerWorker", warmupFells);
            int axes = 0;
            boolean axesIntact = true;
            for (int i = 0; i < workers.size(); i++)
            {
                for (final var inventory : List.of(workers.get(i).getInventory(), huts.get(i).getItemHandlerCap()))
                {
                    for (int slot = 0; slot < inventory.getSlots(); slot++)
                    {
                        final ItemStack stack = inventory.getStackInSlot(slot);
                        if (stack.is(Items.STONE_AXE))
                        {
                            axes += stack.getCount();
                            axesIntact &= stack.has(net.minecraft.core.component.DataComponents.UNBREAKABLE) && stack.getDamageValue() == 0;
                        }
                    }
                }
            }
            axesIntact &= axes == 68;
            out.addProperty("axesRetained", axes);
            out.addProperty("zeroAxeBreaksGate", axesIntact);
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
            out.addProperty("newThreadsMeasured", newThreadsMeasured);
            out.addProperty("unassignedFirstThreadCpuNs", unassignedFirstThreadCpu);
            out.addProperty("overrunsMeasured", overrunsMeasured);
            out.addProperty("maxOverrunNs", maxOverrunNs);
            series(out, "serverCpu", serverCpu);
            series(out, "pathCpu", pathCpu);
            series(out, "combinedCpu", combinedCpu);
            series(out, "wall", wall);
            series(out, "busyWall", busyWall);
            out.addProperty("wallIntervalsOver55ms", Arrays.stream(wall).filter(n -> n > 55_000_000L).count());
            out.addProperty("wallMaxNs", Arrays.stream(wall).max().orElse(0));
            final JsonArray phaseP95 = new JsonArray();
            for (int phase = 0; phase < 5; phase++)
            {
                final long[] values = new long[SAMPLES / 5];
                for (int i = 0; i < values.length; i++) { values[i] = serverCpu[i * 5 + phase]; }
                phaseP95.add(percentile(values, .95));
            }
            out.add("serverCpuP95NsPerPhaseMod5", phaseP95);
            final long[] blockP95 = new long[6];
            final JsonArray blockValues = new JsonArray();
            for (int i = 0; i < 6; i++)
            {
                blockP95[i] = percentile(Arrays.copyOfRange(serverCpu, i * 1000, (i + 1) * 1000), .95);
                blockValues.add(blockP95[i]);
            }
            final double spread = (double) Arrays.stream(blockP95).max().orElseThrow() / Arrays.stream(blockP95).min().orElseThrow();
            final double trend = (double) percentile(Arrays.copyOfRange(blockP95, 3, 6), .5) / percentile(Arrays.copyOfRange(blockP95, 0, 3), .5);
            final boolean stationary = spread <= 1.10 && trend >= .95 && trend <= 1.05;
            out.add("serverCpuP95NsPer1000Ticks", blockValues);
            out.addProperty("stationarityMaxMinRatio", spread);
            out.addProperty("stationarityLastFirstMedianRatio", trend);
            out.addProperty("stationarityGate", stationary);
            out.addProperty("pathStats", Pathfinding.Stats.summary());
            final long pathTotal = Arrays.stream(pathCpu).sum();
            final long completed = Pathfinding.getExecutor().getCompletedTaskCount() - completedAtWarmup;
            out.addProperty("pathCpuNsTotalMeasured", pathTotal);
            out.addProperty("pathJobsStartedMeasured", Pathfinding.Stats.started.get() - startedAtWarmup);
            out.addProperty("pathJobsCompletedMeasured", completed);
            out.addProperty("pathCpuNsPerCompletedJob", completed == 0 ? 0 : pathTotal / completed);
            final JsonArray perWorker = new JsonArray();
            boolean allWorkers = true;
            for (int i = 0; i < workers.size(); i++)
            {
                final var ai = ForestryGameTests.ai(workers.get(i));
                final int fell = (ai == null ? 0 : ai.getTreeChopFellCount()) - workerFellsAtWarmup[i];
                perWorker.add(fell);
                allWorkers &= fell >= 2;
            }
            out.add("measuredFellsPerWorker", perWorker);
            final JsonArray perBlock = new JsonArray();
            for (final int count : blockFells) { perBlock.add(count); }
            out.add("measuredFellsPer1000Ticks", perBlock);
            out.addProperty("registeredDecisionMetric", "serverCpuP95Ns; async CPU totals and per-completed-job cost reported separately");
            final boolean active = allWorkers && Arrays.stream(blockFells).allMatch(count -> count > 0)
              && fells() - fellsAtWarmup >= 8 && chops() - chopsAtWarmup >= 16 && pathMarks.size() > 0
              && pathTotal > 0 && completed > 0 && newThreadsMeasured == 0 && axesIntact;
            out.addProperty("activeWorkGate", active);
            BenchEnv.write("ai_forestry_20tps", out);
            helper.assertTrue(active, "paced forestry activity/accounting gate failed: perWorker=" + perWorker + " perBlock=" + perBlock
              + " pathCpu=" + pathTotal + " completed=" + completed + " newThreads=" + newThreadsMeasured);
            Log.getLogger().info("FORESTRY PERF result samples={} measuredFells={} serverP95={} pathCpuTotal={} completedJobs={}", samples,
              fells() - fellsAtWarmup, percentile(serverCpu, .95), pathTotal, completed);
            helper.assertTrue(stationary, "forestry baseline validity failed: blockP95=" + blockValues + " spread=" + spread + " trend=" + trend);
            noise.complete();
            helper.succeed();
        }

        private double stationarityPrefix(final long[] values, final int length)
        {
            final int blockSize = length < 1000 ? 1 : 1000;
            final long[] prefix = new long[length / blockSize];
            for (int i = 0; i < prefix.length; i++) { prefix[i] = percentile(Arrays.copyOfRange(values, i * blockSize, (i + 1) * blockSize), .95); }
            long minimum = Long.MAX_VALUE;
            long maximum = 0;
            for (final long value : prefix) { minimum = Math.min(minimum, value); maximum = Math.max(maximum, value); }
            final double spread = (double) maximum / minimum;
            final JsonObject record = new JsonObject(); record.addProperty("type", "stationarity-prefix");
            record.addProperty("blocks", prefix.length); record.addProperty("maxMin", spread); noise.evidence(record);
            return spread;
        }

        private void recordTelemetry(final long tick)
        {
            final JsonObject point = new JsonObject();
            point.addProperty("tick", tick);
            final var compilation = ManagementFactory.getCompilationMXBean();
            point.addProperty("compileTimeMs", compilation.isCompilationTimeMonitoringSupported() ? compilation.getTotalCompilationTime() : -1);
            point.addProperty("loadedClasses", ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
            point.addProperty("totalLoadedClasses", ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount());
            point.addProperty("heapCommittedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted());
            point.addProperty("heapUsedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            long count = 0;
            long time = 0;
            for (final var gc : ManagementFactory.getGarbageCollectorMXBeans())
            {
                count += gc.getCollectionCount();
                time += gc.getCollectionTime();
            }
            point.addProperty("gcCount", count);
            point.addProperty("gcTimeMs", time);
            point.addProperty("hostSwapInPages", hostCounter("pswpin"));
            point.addProperty("hostSwapOutPages", hostCounter("pswpout"));
            point.addProperty("hostMajorFaults", hostCounter("pgmajfault"));
            try { point.addProperty("hostLoadavg", java.nio.file.Files.readString(java.nio.file.Path.of("/proc/loadavg")).trim()); }
            catch (final java.io.IOException failure) { throw new IllegalStateException(failure); }
            final JsonArray citizens = new JsonArray();
            for (int i = 0; i < workers.size(); i++)
            {
                final var citizen = workers.get(i);
                final var ai = ForestryGameTests.ai(citizen);
                final JsonObject worker = new JsonObject();
                worker.addProperty("id", citizen.getId());
                worker.addProperty("fells", ai == null ? -1 : ai.getTreeChopFellCount());
                worker.addProperty("chops", ai == null ? -1 : ai.getTreeChopChopCount());
                worker.addProperty("aiState", ai == null ? "none" : String.valueOf(ai.getState()));
                worker.addProperty("position", citizen.getEntity().orElseThrow().position().toString());
                worker.addProperty("tree", citizen.getJob() instanceof com.minecolonies.core.colony.jobs.JobLumberjack job && job.getTree() != null
                  ? job.getTree().getLocation().toString() : "none");
                final ItemStack held = citizen.getEntity().orElseThrow().getMainHandItem();
                worker.addProperty("held", held.toString());
                worker.addProperty("heldDamage", held.getDamageValue());
                worker.addProperty("axesInInventory", InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), stack -> stack.is(Items.STONE_AXE)));
                worker.addProperty("axesInHut", InventoryUtils.getItemCountInItemHandler(huts.get(i).getItemHandlerCap(), stack -> stack.is(Items.STONE_AXE)));
                int free = 0;
                for (int slot = 0; slot < citizen.getInventory().getSlots(); slot++)
                {
                    if (citizen.getInventory().getStackInSlot(slot).isEmpty()) { free++; }
                }
                worker.addProperty("freeSlots", free);
                citizens.add(worker);
            }
            point.add("workers", citizens);
            telemetry.add(point);
            point.addProperty("type", "worker-and-jvm-telemetry");
            noise.evidence(point);
            Log.getLogger().info("FORESTRY PERF jvm {}", point);
        }

        private static long hostCounter(final String key)
        {
            try
            {
                for (final String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/vmstat")))
                {
                    if (line.startsWith(key + " ")) { return Long.parseLong(line.substring(key.length()).trim()); }
                }
                throw new IllegalStateException("missing host counter: " + key);
            }
            catch (final java.io.IOException failure) { throw new IllegalStateException(failure); }
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
