package com.minecolonies.core.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minecolonies.core.entity.pathfinding.Pathfinding;
import net.minecraft.gametest.framework.GameTestHelper;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

/** Shared-host v3 GameTest meter. The workload owns native activity and physical checks. */
final class SharedHostMeter
{
    interface Activity
    {
        void maintain(long tick);
        Map<String, Long> counters();
        void verify();
        void close();
        String describe();
        default void verifyAdmissionHeadroom() { }
    }

    private static final int INVOCATION = 20_000;
    private static final int SETTLE = 2_000;
    private static final int SAMPLES = 6_000;
    private static final long PERIOD = 50_000_000L;
    private final GameTestHelper helper;
    private final String workload;
    private final Activity activity;
    private final boolean timing;
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final Map<Long, Long> pathMarks = new HashMap<>();
    private final long[] serverCpu = new long[SAMPLES];
    private final long[] pathCpu = new long[SAMPLES];
    private final long[] wall = new long[SAMPLES];
    private final long[] busyWall = new long[SAMPLES];
    private final JsonArray blocks = new JsonArray();
    private SharedHostNoiseMonitor noise;
    private Map<String, Long> measurementStart;
    private Map<String, Long> previousCounters;
    private Map<String, String> nativePathIdentity;
    private long began, previousTick, warmupStart, deadline, priorCpu, priorWall, priorBusyWall;
    private long completedStart, startedStart, pathTotal, maxOverrun;
    private int samples, overruns;
    private boolean closed;

    SharedHostMeter(final GameTestHelper helper, final String workload, final Activity activity)
    {
        this.helper = helper;
        this.workload = workload;
        this.activity = activity;
        final String mode = System.getenv("MC_SHARED_MODE");
        timing = "timing".equals(mode);
        // Defer validation to start(), so even invalid configuration closes the activity.
    }

    void start()
    {
        try
        {
            final String mode = System.getenv("MC_SHARED_MODE");
            require("functional".equals(mode) || timing, "MC_SHARED_MODE must be functional or timing");
            require(workload != null && !workload.isBlank() && activity != null, "missing workload or activity");
            require(System.getenv("MC_SHARED_PROTOCOL_SHA") != null && !System.getenv("MC_SHARED_PROTOCOL_SHA").isBlank(), "missing protocol identity");
            warmupStart = System.nanoTime();
            if (timing)
            {
                require(threads.isThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled(), "thread CPU time unavailable");
                noise = new SharedHostNoiseMonitor();
                warmupStart = System.nanoTime();
            }
            began = helper.getLevel().getGameTime();
            previousTick = began;
            helper.runAfterDelay(1, this::tick);
            if (timing) { priorCpuMark = cpuMark(); priorCpu = priorCpuMark.get("cpuNs").getAsLong(); priorBusyWall = System.nanoTime(); priorWall = priorBusyWall; }
        }
        catch (final RuntimeException | Error failure) { abort(failure); throw failure; }
    }

    private void tick()
    {
        try { tickGuarded(); }
        catch (final RuntimeException | Error failure) { abort(failure); throw failure; }
    }

    private JsonObject priorCpuMark;

    private JsonObject cpuMark()
    {
        final JsonObject mark = new JsonObject();
        mark.addProperty("beforeNs", System.nanoTime());
        mark.addProperty("cpuNs", threads.getCurrentThreadCpuTime());
        mark.addProperty("afterNs", System.nanoTime());
        mark.addProperty("javaThreadId", Thread.currentThread().threadId());
        mark.addProperty("nativeIdentity", noise.serverIdentity());
        return mark;
    }

    private void tickGuarded()
    {
        final long nowTick = helper.getLevel().getGameTime();
        require(nowTick - previousTick == 1, "nonconsecutive GameTest tick");
        previousTick = nowTick;
        final long tick = nowTick - began;
        require(tick >= 1 && tick <= INVOCATION + SETTLE + SAMPLES, "unexpected phase tick");
        // Fixture calls into production are part of the registered Server CPU interval.
        activity.maintain(tick);
        final JsonObject endCpuMark = timing ? cpuMark() : null;
        final long nowCpu = timing ? endCpuMark.get("cpuNs").getAsLong() : 0;
        final long nowWall = timing ? System.nanoTime() : 0;
        // Read-only ledger scans must not occupy exactly the top 5% of CPU samples.
        if (tick % 20 == 0) { activity.verify(); }
        if (tick > INVOCATION + SETTLE)
        {
            final long async = paths(true);
            if (timing)
            {
                final long value = nowCpu - priorCpu;
                require(value >= 0 && async >= 0 && nowWall >= priorWall && nowWall >= priorBusyWall, "counter decreased");
                serverCpu[samples] = value;
                pathCpu[samples] = async;
                pathTotal += async;
                wall[samples] = nowWall - priorWall;
                busyWall[samples] = nowWall - priorBusyWall;
                noise.sample(nowTick, samples + 1, value, async, wall[samples], busyWall[samples], priorCpuMark, endCpuMark);
            }
            samples++;
        }
        if (tick <= INVOCATION && timing) { noise.warmup(tick); }
        if (tick == INVOCATION + SETTLE)
        {
            activity.verify();
            measurementStart = snapshot();
            previousCounters = measurementStart;
            paths(false);
            require(!pathMarks.isEmpty(), "no native path workers at measurement start");
            completedStart = Pathfinding.getExecutor().getCompletedTaskCount();
            startedStart = Pathfinding.Stats.started.get();
            if (timing)
            {
                noise.beginMeasurement();
                nativePathIdentity = nativePaths(0);
                require(nativePathIdentity.size() == pathMarks.size(), "native and Java path worker counts differ");
            }
        }
        if (tick > INVOCATION + SETTLE && samples % 1000 == 0)
        {
            activity.verify();
            if (timing) { require(nativePathIdentity.equals(nativePaths(samples)), "native path worker identity changed"); }
            final Map<String, Long> current = snapshot();
            final JsonObject block = new JsonObject();
            block.addProperty("sample", samples);
            final JsonObject delta = new JsonObject();
            for (final String role : requiredRoles())
            {
                final long count = current.get(role) - previousCounters.get(role);
                require(count > 0, "no native " + role + " activity in block");
                delta.addProperty(role, count);
            }
            block.add("roleDeltas", delta);
            blocks.add(block);
            previousCounters = current;
        }
        if (samples == SAMPLES) { finish(); return; }
        helper.runAfterDelay(1, this::tick);
        if (tick <= INVOCATION)
        {
            require(System.nanoTime() - warmupStart <= 120_000_000_000L, "invocation warmup exceeded 120 seconds");
            final long limit = System.nanoTime() + 200_000_000L;
            final var pool = Pathfinding.getExecutor();
            while ((!pool.getQueue().isEmpty() || pool.getActiveCount() > 0) && System.nanoTime() < limit) { LockSupport.parkNanos(20_000L); }
            require(System.nanoTime() - warmupStart <= 120_000_000_000L, "invocation warmup exceeded 120 seconds");
            if (timing)
            {
                if (tick == INVOCATION) { deadline = System.nanoTime(); }
            }
        }
        else if (timing)
        {
            deadline += PERIOD;
            final long lag = System.nanoTime() - deadline;
            if (lag > 0) { overruns++; maxOverrun = Math.max(maxOverrun, lag); }
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0) { LockSupport.parkNanos(remaining); }
        }
        if (timing) { priorCpuMark = cpuMark(); priorCpu = priorCpuMark.get("cpuNs").getAsLong(); priorBusyWall = System.nanoTime(); priorWall = nowWall; }
    }

    private Set<String> requiredRoles()
    {
        return workload.contains("forestry") ? Set.of("forester", "chops") : Set.of("builder", "crafter", "courier", "forester");
    }

    private Map<String, Long> snapshot()
    {
        final Map<String, Long> current = new HashMap<>(activity.counters());
        require(current.keySet().containsAll(requiredRoles()), "missing canonical role counter");
        if (workload.contains("forestry")) { require(current.containsKey("chops"), "missing chops counter"); }
        for (final var entry : current.entrySet())
        {
            require(entry.getValue() != null && entry.getValue() >= 0, "invalid activity counter");
            if (previousCounters != null) { require(previousCounters.containsKey(entry.getKey()) && entry.getValue() >= previousCounters.get(entry.getKey()), "activity counter changed or decreased"); }
        }
        if (previousCounters != null) { require(current.keySet().equals(previousCounters.keySet()), "activity counter keys changed"); }
        return current;
    }

    private long paths(final boolean measured)
    {
        final Map<Long, Long> now = new HashMap<>();
        long delta = 0;
        for (final long id : threads.getAllThreadIds())
        {
            final var info = threads.getThreadInfo(id);
            if (info == null || !info.getThreadName().startsWith("Minecolonies Pathfinding Worker #")) { continue; }
            final long cpu = timing ? threads.getThreadCpuTime(id) : 0;
            require(cpu >= 0, "path CPU unavailable");
            now.put(id, cpu);
            if (measured)
            {
                final Long previous = pathMarks.get(id);
                require(previous != null && cpu >= previous, "new path thread or decreasing counter");
                delta += cpu - previous;
            }
        }
        if (measured) { require(now.keySet().equals(pathMarks.keySet()), "path thread disappeared"); }
        pathMarks.clear(); pathMarks.putAll(now);
        return delta;
    }

    private Map<String, String> nativePaths(final int sample)
    {
        final Map<String, String> identities = new HashMap<>();
        final JsonObject record = new JsonObject();
        record.addProperty("type", "path-native-identities");
        record.addProperty("sample", sample);
        final JsonArray entries = new JsonArray();
        try (final var tasks = Files.list(Path.of("/proc/self/task")))
        {
            for (final Path task : tasks.sorted().toList())
            {
                final String stat;
                final long before = System.nanoTime();
                try { stat = Files.readString(task.resolve("stat")); }
                catch (final java.nio.file.NoSuchFileException vanished) { continue; }
                final long after = System.nanoTime();
                final int open = stat.indexOf('('), end = stat.lastIndexOf(')');
                require(open > 0 && end > open, "invalid task stat");
                if (!stat.substring(open + 1, end).startsWith("Minecolonies Pa")) { continue; }
                final String tid = stat.substring(0, open).trim();
                final String start = SharedHostNoiseMonitor.stat(stat)[19];
                identities.put(tid, start);
                final JsonObject entry = new JsonObject();
                entry.addProperty("tid", tid); entry.addProperty("startTimeTicks", start);
                entry.addProperty("beforeNs", before); entry.addProperty("afterNs", after);
                entry.addProperty("statRaw", stat); entries.add(entry);
            }
        }
        catch (final IOException failure) { throw new IllegalStateException("native path identities unavailable", failure); }
        record.add("threads", entries);
        noise.evidence(record);
        return identities;
    }

    private void finish()
    {
        final long completed = Pathfinding.getExecutor().getCompletedTaskCount() - completedStart;
        final long started = Pathfinding.Stats.started.get() - startedStart;
        require(completed > 0 && started >= 0, "no completed path jobs or decreasing path counter");
        if (timing) { require(pathTotal > 0, "no measured path CPU"); }
        if (!timing) { activity.verifyAdmissionHeadroom(); }
        final JsonObject result = new JsonObject();
        result.addProperty("phaseElapsedNs", System.nanoTime() - warmupStart);
        result.addProperty("admissionHeadroomPassed", true);
        result.addProperty("workload", workload);
        result.addProperty("mode", timing ? "timing" : "functional");
        result.addProperty("protocolSha256", System.getenv("MC_SHARED_PROTOCOL_SHA"));
        result.addProperty("description", activity.describe());
        result.addProperty("invocationWarmupTicks", INVOCATION);
        result.addProperty("pacedSettleTicks", SETTLE);
        result.addProperty("measuredTicks", samples);
        result.addProperty("pathJobsCompleted", completed);
        result.addProperty("pathJobsStarted", started);
        result.addProperty("pathThreadCount", pathMarks.size());
        result.add("roleBlocks", blocks);
        final JsonObject roles = new JsonObject();
        final Map<String, Long> current = snapshot();
        for (final var entry : current.entrySet()) { roles.addProperty(entry.getKey(), entry.getValue() - measurementStart.get(entry.getKey())); }
        if (workload.contains("mixed"))
        {
            for (int i = 0; i < 4; i++)
            {
                for (final String suffix : List.of("Windows", "Fells"))
                {
                    final String key = "cell" + i + suffix;
                    require(current.get(key) - measurementStart.get(key) > 0, "inactive mixed cell during measurement: " + key);
                }
            }
        }
        result.add("roleCounters", roles);
        if (timing)
        {
            result.addProperty("pathCpuNsTotal", pathTotal);
            result.addProperty("overruns", overruns);
            result.addProperty("maxOverrunNs", maxOverrun);
            series(result, "serverCpuNs", serverCpu);
            series(result, "pathCpuNs", pathCpu);
            series(result, "wallNs", wall);
            series(result, "busyWallNs", busyWall);
            final JsonArray blockP95 = new JsonArray();
            final JsonArray phaseP95 = new JsonArray();
            for (int block = 0; block < 6; block++)
            {
                final long[] values = Arrays.copyOfRange(serverCpu, block * 1000, (block + 1) * 1000);
                Arrays.sort(values); blockP95.add(values[950]);
            }
            for (int phase = 0; phase < 5; phase++)
            {
                final long[] values = new long[SAMPLES / 5];
                for (int i = 0; i < values.length; i++) { values[i] = serverCpu[i * 5 + phase]; }
                Arrays.sort(values); phaseP95.add(values[(int) Math.floor(.95 * values.length)]);
            }
            result.add("serverCpuP95NsPer1000Ticks", blockP95);
            result.add("serverCpuP95NsPerPhaseMod5", phaseP95);
            result.add("observer", noise.finish());
        }
        closeActivity();
        result.addProperty("normalFinish", true);
        if (timing) { noise.complete(result); }
        else
        {
            final JsonObject record = new JsonObject();
            record.addProperty("type", "complete");
            record.addProperty("provisional", true);
            record.add("result", result);
            final Path destination = Path.of(System.getenv("MC_GT_DURABLE_DIR")).resolve("shared-host-v3.jsonl");
            try (final java.nio.channels.FileChannel file = java.nio.channels.FileChannel.open(destination,
              java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE))
            {
                final java.nio.ByteBuffer bytes = java.nio.charset.StandardCharsets.UTF_8.encode(record.toString() + "\n");
                while (bytes.hasRemaining()) { file.write(bytes); }
                file.force(true);
            }
            catch (final IOException failure) { throw new IllegalStateException("functional terminal could not be retained", failure); }
        }
        helper.succeed();
    }

    private static void series(final JsonObject out, final String key, final long[] values)
    {
        final JsonArray raw = new JsonArray();
        for (final long value : values) { raw.add(value); }
        out.add(key + "Raw", raw);
        final long[] sorted = values.clone(); Arrays.sort(sorted);
        out.addProperty(key + "P50", sorted[(int) Math.floor(.5 * sorted.length)]);
        out.addProperty(key + "P95", sorted[(int) Math.floor(.95 * sorted.length)]);
    }

    private void closeActivity()
    {
        if (!closed) { closed = true; if (activity != null) { activity.close(); } }
    }

    private void abort(final Throwable failure)
    {
        try { closeActivity(); } catch (final Throwable closeFailure) { failure.addSuppressed(closeFailure); }
        if (noise != null) { noise.abort(failure); }
    }

    private static void require(final boolean condition, final String message)
    {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
