package com.minecolonies.core.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Protocol-v3 observer. Test code only; missing or uncertain measurements fail closed. */
final class SharedHostNoiseMonitor
{
    private final ThreadMXBean clock = ManagementFactory.getThreadMXBean();
    private final int[] ring = new int[1 << 20];
    private final List<Long> canaries = new ArrayList<>();
    private final List<Long> frequencies = new ArrayList<>();
    private final JsonArray blocks = new JsonArray();
    private final JsonArray observations = new JsonArray();
    private JsonArray pending = new JsonArray();
    private final ArrayBlockingQueue<Write> writes = new ArrayBlockingQueue<>(256);
    private final Thread writer;
    private volatile Throwable writerFailure;
    private volatile long checksum;
    private volatile long writerTid;
    private volatile long writerStartTicks;
    private int cursor;
    private int frequencyBlockStart;
    private long majorStart;
    private long swapStart;
    private long classesStart;
    private long cpuTotal;
    private long blockCpu;
    private long psiStart;
    private long psiPrevious;
    private double hugeStart;
    private String identity;
    private String cgroup;
    private Path pressure;
    private PressureLocation pressureLocation;
    private int onlineCapacity;
    private int affinityCapacity;
    private long finalBlockPsiStart;
    private long finalBlockCpu;
    private Counters previous;
    private final long ticksPerSecond;
    private long blockCanaryMin = Long.MAX_VALUE;
    private long blockCanaryMax;
    private long blockFrequencyMin = Long.MAX_VALUE;
    private long blockFrequencyMax;
    private boolean measuring;
    private boolean closed;

    private record Write(String text, boolean terminal, CompletableFuture<Void> ack) { }
    private record Read(String raw, long before, long after) { }
    private record PressureLocation(Path file, String mountIdentity) { }
    private record Counters(long busy, long own, long before, long after, long uncertaintyNs) { }

    SharedHostNoiseMonitor()
    {
        ticksPerSecond = Long.parseLong(requiredEnvironment("MC_GT_CLK_TCK"));
        require(ticksPerSecond > 0, "invalid CLK_TCK");
        final Path directory = Path.of(requiredEnvironment("MC_GT_DURABLE_DIR"));
        final SplittableRandom random = new SplittableRandom(117);
        for (int i = 0; i < ring.length; i++) { ring[i] = i; }
        for (int i = ring.length - 1; i > 0; i--)
        {
            final int j = random.nextInt(i); // Sattolo: one cycle, not disconnected permutations.
            final int value = ring[i]; ring[i] = ring[j]; ring[j] = value;
        }
        writer = new Thread(() -> writeLoop(directory.resolve("shared-host-v3.jsonl")), "Forestry protocol v3 writer");
        writer.setDaemon(true);
        final JsonObject metadata = new JsonObject();
        metadata.addProperty("type", "start");
        metadata.addProperty("protocol", "shared-host-v3");
        metadata.addProperty("protocolSha256", requiredEnvironment("MC_SHARED_PROTOCOL_SHA"));
        metadata.addProperty("clkTck", ticksPerSecond);
        metadata.addProperty("javaVersion", System.getProperty("java.version"));
        metadata.addProperty("effectiveArguments", ManagementFactory.getRuntimeMXBean().getInputArguments().toString());
        metadata.addProperty("heapMaxBytes", Runtime.getRuntime().maxMemory());
        metadata.addProperty("heapCommittedBytes", Runtime.getRuntime().totalMemory());
        final var flags = ManagementFactory.getRuntimeMXBean().getInputArguments();
        require(flags.contains("-Xms2g") && flags.contains("-Xmx2g") && flags.contains("-XX:+AlwaysPreTouch"), "v3 fixed heap flags missing");
        require(Runtime.getRuntime().maxMemory() == 2L * 1024 * 1024 * 1024, "v3 effective maximum heap differs");
        require(Runtime.getRuntime().totalMemory() == Runtime.getRuntime().maxMemory(), "v3 heap was not fully committed");
        identity = identity(metadata);
        cgroup = read(Path.of("/proc/self/cgroup"), metadata).trim();
        pressureLocation = pressurePath(cgroup, metadata);
        pressure = pressureLocation.file();
        metadata.addProperty("cgroupPressureFile", pressure.toString());
        checkCgroup(metadata);
        pressureTotal(metadata); // capability check and class initialization before measurement
        hugeFraction(metadata);
        context(metadata);
        counters(metadata);
        metadata.deepCopy();
        median(new ArrayList<>(List.of(1L, 2L)).subList(0, 2));
        writer.start();
        try { emit(metadata, true, false); }
        catch (final RuntimeException | Error failure) { writer.interrupt(); throw failure; }
    }

    private static String requiredEnvironment(final String key)
    {
        final String value = System.getenv(key);
        require(value != null && !value.isBlank(), "missing v3 runner input " + key);
        return value;
    }

    static String[] stat(final String text)
    {
        final int end = text.lastIndexOf(')');
        require(end > 0, "invalid Linux stat comm field");
        final String[] fields = text.substring(end + 1).trim().split("\\s+");
        require(fields.length > 36, "short Linux stat");
        return fields;
    }

    private Read raw(final Path path)
    {
        try
        {
            final long before = System.nanoTime();
            final String value = Files.readString(path);
            return new Read(value, before, System.nanoTime());
        }
        catch (final IOException failure) { throw new IllegalStateException("v3 unreadable counter " + path, failure); }
    }

    private String read(final Path path, final JsonObject record)
    {
        final Read value = raw(path);
        final JsonObject item = new JsonObject();
        item.addProperty("beforeNs", value.before());
        item.addProperty("afterNs", value.after());
        item.addProperty("raw", value.raw());
        // Multiple reads of the same source in one sample retain independent entries.
        final String key = path.toString();
        final JsonArray entries;
        if (record.has(key)) { entries = record.getAsJsonArray(key); }
        else { entries = new JsonArray(); record.add(key, entries); }
        entries.add(item);
        return value.raw();
    }

    private String identity(final JsonObject record)
    {
        final String task = read(Path.of("/proc/thread-self/stat"), record);
        final String process = read(Path.of("/proc/self/stat"), record);
        final String status = read(Path.of("/proc/thread-self/status"), record);
        final String affinity = Arrays.stream(status.split("\n")).filter(x -> x.startsWith("Cpus_allowed_list:")).findFirst().orElseThrow();
        final String online = read(Path.of("/sys/devices/system/cpu/online"), record).trim();
        require(Thread.currentThread().getName().equals("Server thread"), "observer not on platform Server thread");
        record.addProperty("serverThreadName", Thread.currentThread().getName());
        onlineCapacity = cpuListCount(online);
        final java.util.BitSet processCpus = new java.util.BitSet();
        int vanishedTasks = 0;
        final String leaderId = process.substring(0, process.indexOf(' '));
        final String serverId = task.substring(0, task.indexOf(' '));
        try (final var tasks = Files.list(Path.of("/proc/self/task")))
        {
            for (final Path taskDirectory : tasks.sorted().toList())
            {
                final String taskStatus;
                try { taskStatus = read(taskDirectory.resolve("status"), record); }
                catch (final IllegalStateException failure)
                {
                    final String id = taskDirectory.getFileName().toString();
                    if (failure.getCause() instanceof java.nio.file.NoSuchFileException && !id.equals(leaderId) && !id.equals(serverId))
                    {
                        vanishedTasks++;
                        continue;
                    }
                    throw failure;
                }
                final String list = Arrays.stream(taskStatus.split("\n")).filter(x -> x.startsWith("Cpus_allowed_list:")).findFirst().orElse(null);
                if (list == null && !Files.exists(taskDirectory) && !taskDirectory.getFileName().toString().equals(leaderId)
                    && !taskDirectory.getFileName().toString().equals(serverId)) { vanishedTasks++; continue; }
                require(list != null, "live task lacks affinity");
                for (final String part : list.substring(list.indexOf(':') + 1).trim().split(","))
                {
                    final String[] range = part.split("-");
                    final int first = Integer.parseInt(range[0]);
                    final int last = range.length == 1 ? first : Integer.parseInt(range[1]);
                    processCpus.set(first, last + 1);
                }
            }
        }
        catch (final IOException failure) { throw new IllegalStateException("v3 process affinity unavailable", failure); }
        record.addProperty("vanishedTasksDuringAffinityRead", vanishedTasks);
        affinityCapacity = processCpus.cardinality();
        require(affinityCapacity > 0 && affinityCapacity <= onlineCapacity, "v3 process affinity capacity invalid");
        record.addProperty("processAffinityUnion", processCpus.toString());
        record.addProperty("onlineCpuCapacity", onlineCapacity);
        record.addProperty("affinityCpuCapacity", affinityCapacity);
        return task.substring(0, task.indexOf(' ')) + ":" + stat(task)[19] + ":"
          + process.substring(0, process.indexOf(' ')) + ":" + stat(process)[19] + ":" + affinity + ":" + processCpus + ":" + online;
    }

    private PressureLocation pressurePath(final String group, final JsonObject record)
    {
        final String path = Arrays.stream(group.split("\n")).filter(x -> x.startsWith("0::")).findFirst().orElseThrow().substring(3);
        for (final String mount : read(Path.of("/proc/self/mountinfo"), record).split("\n"))
        {
            if (!mount.contains(" - cgroup2 ")) { continue; }
            final String[] fields = mount.split(" ");
            final String root = fields[3];
            if (!root.equals("/") && !path.equals(root) && !path.startsWith(root + "/")) { continue; }
            final String suffix = root.equals("/") ? path : path.substring(root.length());
            return new PressureLocation(Path.of(fields[4]).resolve(suffix.replaceFirst("^/", "")).resolve("memory.pressure"), mount);
        }
        throw new IllegalStateException("v3 cgroup2 mount unavailable");
    }

    private long pressureTotal(final JsonObject record)
    {
        for (final String line : read(pressure, record).split("\n"))
        {
            if (!line.startsWith("some ")) { continue; }
            for (final String token : line.split(" "))
            {
                if (token.startsWith("total=")) { return Long.parseLong(token.substring(6)); }
            }
        }
        throw new IllegalStateException("v3 missing PSI some total");
    }

    private static long kilobytes(final String text, final String key)
    {
        final String line = Arrays.stream(text.split("\n")).filter(x -> x.startsWith(key + ":")).findFirst().orElseThrow();
        return Long.parseLong(line.substring(line.indexOf(':') + 1).trim().split("\\s+")[0]);
    }

    private double hugeFraction(final JsonObject record)
    {
        final String rollup = read(Path.of("/proc/self/smaps_rollup"), record);
        final long anonymous = kilobytes(rollup, "Anonymous");
        require(anonymous > 0, "v3 anonymous memory missing");
        return (double) kilobytes(rollup, "AnonHugePages") / anonymous;
    }

    private void context(final JsonObject record)
    {
        for (final String path : List.of("/proc/loadavg", "/proc/meminfo", "/proc/vmstat", "/sys/devices/system/cpu/cpu0/cpufreq/energy_performance_preference",
          "/sys/devices/system/cpu/intel_pstate/no_turbo", "/sys/devices/system/cpu/nohz_full", "/sys/kernel/mm/transparent_hugepage/enabled"))
        { read(Path.of(path), record); }
        record.addProperty("compileTimeMs", ManagementFactory.getCompilationMXBean().getTotalCompilationTime());
        record.addProperty("totalLoadedClasses", ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount());
        record.addProperty("heapCommittedBytes", Runtime.getRuntime().totalMemory());
        final JsonArray gc = new JsonArray();
        ManagementFactory.getGarbageCollectorMXBeans().forEach(bean -> {
            final JsonObject item = new JsonObject();
            item.addProperty("name", bean.getName()); item.addProperty("count", bean.getCollectionCount()); item.addProperty("timeMs", bean.getCollectionTime()); gc.add(item);
        });
        record.add("gc", gc);
    }

    private Counters counters(final JsonObject record)
    {
        final long before = System.nanoTime();
        final Read global = raw(Path.of("/proc/stat"));
        final Read own = raw(Path.of("/proc/self/stat"));
        final String[] cpu = global.raw().lines().findFirst().orElseThrow().trim().split("\\s+");
        long busy = 0;
        for (final int index : new int[]{1, 2, 3, 6, 7, 8}) { busy += Long.parseLong(cpu[index]); }
        final String[] fields = stat(own.raw());
        final long ownCpu = Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
        final JsonObject reads = new JsonObject();
        reads.addProperty("globalRaw", global.raw()); reads.addProperty("ownRaw", own.raw());
        reads.addProperty("globalBeforeNs", global.before()); reads.addProperty("globalAfterNs", global.after());
        reads.addProperty("ownBeforeNs", own.before()); reads.addProperty("ownAfterNs", own.after());
        record.add("cpuCounterReads", reads);
        return new Counters(busy, ownCpu, before, System.nanoTime(), System.nanoTime() - before);
    }

    void beginMeasurement()
    {
        exerciseRecordPaths(); // Before fault/pressure baselines and the loaded-class window.
        final JsonObject record = new JsonObject(); record.addProperty("type", "measurement-start");
        record.addProperty("writerNativeTid", writerTid);
        record.addProperty("writerStartTimeTicks", writerStartTicks);
        record.addProperty("cgroupIdentity", cgroup);
        require(writerTid > 0 && writerStartTicks > 0, "v3 writer identity missing");
        require(identity.equals(identity(record)), "v3 PID/TID/start/affinity/online identity changed");
        require(cgroup.equals(read(Path.of("/proc/self/cgroup"), record).trim()), "v3 cgroup changed");
        majorStart = Long.parseLong(stat(read(Path.of("/proc/thread-self/stat"), record))[9]);
        swapStart = kilobytes(read(Path.of("/proc/self/status"), record), "VmSwap");
        psiStart = pressureTotal(record); psiPrevious = psiStart;
        hugeStart = hugeFraction(record);
        context(record);
        previous = counters(record);
        emit(record, true, false);
        classesStart = ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount();
        measuring = true;
    }

    void sample(final long tick, final int sample, final long cpu, final long pathCpu, final long wall, final long busyWall)
    {
        require(measuring && sample >= 1 && sample <= 6000, "v3 sample outside measured window");
        require(cpu >= 0 && pathCpu >= 0, "v3 CPU counters decreased");
        cpuTotal += cpu; blockCpu += cpu;
        final JsonObject point = sampleRecord(tick, sample, cpu, pathCpu, wall, busyWall);
        pending.add(point);
        if (sample % 50 != 0) { checkWriter(); return; }
        final JsonObject record = new JsonObject(); record.addProperty("type", "samples"); record.add("samples", pending); pending = new JsonArray();
        record.addProperty("sample", sample);
        try
        {
            canary(record, true);
            final String[] task = stat(read(Path.of("/proc/thread-self/stat"), record));
            require(Long.parseLong(task[9]) == majorStart, "v3 Server major faults increased");
            require(kilobytes(read(Path.of("/proc/self/status"), record), "VmSwap") <= swapStart, "v3 process VmSwap grew");
            if (sample % 1000 == 0) { boundary(record, sample); }
            // Superseded quiet-host threshold: retained as context only.
            observations.add(record.deepCopy());
            emit(record, sample % 1000 == 0, false);
        }
        catch (final RuntimeException | Error failure)
        {
            record.addProperty("invalidReason", failure.toString()); emit(record, true, false); throw failure;
        }
    }

    void warmup(final long tick)
    {
        if (tick % 50 != 0) { return; }
        final JsonObject record = new JsonObject(); record.addProperty("type", "warmup-canary"); record.addProperty("tick", tick);
        canary(record, false);
        if (tick % 1000 == 0) { identity(record); counters(record); pressureTotal(record); hugeFraction(record); context(record); record.deepCopy(); }
        emit(record, false, false);
    }

    private void canary(final JsonObject record, final boolean measured)
    {
        final int cpuBefore = Integer.parseInt(stat(read(Path.of("/proc/thread-self/stat"), record))[36]);
        final long primerStart = clock.getCurrentThreadCpuTime();
        int index = cursor;
        for (int i = 0; i < 65_536; i++) { index = ring[index]; }
        final long timedStart = clock.getCurrentThreadCpuTime();
        for (int i = 0; i < 65_536; i++) { index = ring[index]; }
        final long end = clock.getCurrentThreadCpuTime();
        cursor = index; checksum = checksum * 31 + index;
        final int cpuAfter = Integer.parseInt(stat(read(Path.of("/proc/thread-self/stat"), record))[36]);
        final long frequency = Long.parseLong(read(Path.of("/sys/devices/system/cpu/cpu" + cpuAfter + "/cpufreq/cpuinfo_avg_freq"), record).trim());
        final int cpuFinal = Integer.parseInt(stat(read(Path.of("/proc/thread-self/stat"), record))[36]);
        final long duration = end - timedStart;
        require(frequency > 0 && duration >= 0 && timedStart >= primerStart, "v3 invalid canary or frequency clock");
        final boolean eligible = cpuBefore == cpuAfter && cpuAfter == cpuFinal && duration >= 500_000 && end - primerStart >= 1_000_000;
        record.addProperty("canaryCpuNs", duration); record.addProperty("primerAndCanaryCpuNs", end - primerStart);
        record.addProperty("checksum", checksum); record.addProperty("frequencyKHz", frequency);
        record.addProperty("cpuBefore", cpuBefore); record.addProperty("cpuAfter", cpuAfter); record.addProperty("cpuFinal", cpuFinal);
        record.addProperty("frequencyEligible", eligible);
        if (measured) { canaries.add(duration); if (eligible) { frequencies.add(frequency); } }
    }

    private void boundary(final JsonObject record, final int sample)
    {
        require(identity.equals(identity(record)), "v3 process/thread identity changed");
        checkCgroup(record);
        final Counters now = counters(record);
        cpuInterval(record, previous, now, onlineCapacity, affinityCapacity, ticksPerSecond);
        previous = now;
        final long psi = pressureTotal(record);
        if (sample == 6000) { finalBlockPsiStart = psiPrevious; finalBlockCpu = blockCpu; }
        require(psi >= psiPrevious, "v3 PSI counter decreased");
        record.addProperty("memoryPressureDeltaUs", psi - psiPrevious);
        record.addProperty("registeredBlockCpuNs", blockCpu);
        // Superseded quiet-host threshold: retained as context only.
        // Superseded quiet-host threshold: retained as context only.
        psiPrevious = psi; blockCpu = 0;
        // Superseded quiet-host threshold: retained as context only.
        final long canaryMedian = median(canaries.subList(canaries.size() - 20, canaries.size()));
        final long frequencyMedian = frequencyBlockStart == frequencies.size() ? 0 : median(frequencies.subList(frequencyBlockStart, frequencies.size()));
        frequencyBlockStart = frequencies.size();
        blockCanaryMin = Math.min(blockCanaryMin, canaryMedian); blockCanaryMax = Math.max(blockCanaryMax, canaryMedian);
        blockFrequencyMin = Math.min(blockFrequencyMin, frequencyMedian); blockFrequencyMax = Math.max(blockFrequencyMax, frequencyMedian);
        record.addProperty("canaryMedianNs", canaryMedian); record.addProperty("frequencyMedianKHz", frequencyMedian);
        // Superseded quiet-host threshold: retained as context only.
        // Superseded quiet-host threshold: retained as context only.
        context(record);
        record.addProperty("hugePageFraction", hugeFraction(record));
        blocks.add(record.deepCopy());
    }

    JsonObject finish()
    {
        require(canaries.size() == 120 && blocks.size() == 6, "v3 incomplete observer window");
        final JsonObject result = new JsonObject();
        result.addProperty("protocol", "shared-host-v3"); result.addProperty("canarySamples", canaries.size());
        result.addProperty("frequencyMedianKHz", frequencies.isEmpty() ? 0 : median(frequencies)); result.addProperty("canaryMedianNs", median(canaries));
        result.addProperty("hugePageStartFraction", hugeStart); result.addProperty("serverMajorFaultsDelta", 0);
        result.addProperty("serverRegisteredCpuNs", cpuTotal); result.addProperty("memoryPressureDeltaUs", psiPrevious - psiStart);
        result.addProperty("writerNativeTid", writerTid); result.add("blocks", blocks); result.add("observations", observations);
        result.addProperty("resourceIntegrityChecked", true);
        result.addProperty("type", "observer-complete"); emit(result, true, false);
        return result;
    }

    void complete(final JsonObject terminal)
    {
        final JsonObject record = new JsonObject(); record.addProperty("type", "final-observations");
        try
        {
            context(record);
            require(identity.equals(identity(record)), "v3 final process identity changed");
            checkCgroup(record);
            emit(record.deepCopy(), true, false); // Include outstanding observer I/O before the final pressure reading.
            final long finalMajor = Long.parseLong(stat(read(Path.of("/proc/thread-self/stat"), record))[9]);
            final long finalSwap = kilobytes(read(Path.of("/proc/self/status"), record), "VmSwap");
            final long finalPressure = pressureTotal(record);
            record.addProperty("finalMajorFaultDelta", finalMajor - majorStart);
            record.addProperty("finalMemoryPressureDeltaUs", finalPressure - psiStart);
            require(finalMajor == majorStart, "v3 final observer work incurred a major fault");
            require(finalSwap <= swapStart, "v3 final observer work grew VmSwap");
            require(finalPressure >= psiPrevious, "v3 final PSI counter decreased");
            // Superseded quiet-host threshold: retained as context only.
            // Superseded quiet-host threshold: retained as context only.
            record.addProperty("type", "complete"); record.addProperty("observerChecksPassed", true); record.addProperty("provisional", true); record.add("result", terminal);
            emit(record, true, true); closed = true;
        }
        catch (final RuntimeException | Error failure)
        {
            record.addProperty("invalidReason", failure.toString()); emit(record, true, false); throw failure;
        }
    }

    void evidence(final JsonObject record)
    {
        emit(record.deepCopy(), true, false);
    }

    private void checkCgroup(final JsonObject record)
    {
        final String current = read(Path.of("/proc/self/cgroup"), record).trim();
        require(cgroup.equals(current), "v3 cgroup path changed");
        require(pressureLocation.equals(pressurePath(current, record)), "v3 resolved cgroup mount identity changed");
    }

    static int cpuListCount(final String value)
    {
        int total = 0;
        for (final String part : value.trim().split(","))
        {
            final String[] range = part.split("-");
            final int first = Integer.parseInt(range[0]);
            final int last = range.length == 1 ? first : Integer.parseInt(range[1]);
            require(range.length <= 2 && first >= 0 && last >= first, "invalid CPU range");
            total += last - first + 1;
        }
        require(total > 0, "empty CPU capacity");
        return total;
    }

    private static JsonObject sampleRecord(final long tick, final int sample, final long cpu, final long pathCpu, final long wall, final long busyWall)
    {
        final JsonObject point = new JsonObject();
        point.addProperty("tick", tick); point.addProperty("sample", sample); point.addProperty("timestampNs", System.nanoTime());
        point.addProperty("serverCpuNs", cpu); point.addProperty("pathCpuNs", pathCpu); point.addProperty("wallNs", wall); point.addProperty("busyWallNs", busyWall);
        point.addProperty("immediatelyAfterCanary", sample % 50 == 1);
        return point;
    }

    private static void cpuInterval(final JsonObject record, final Counters previous, final Counters now,
      final int online, final int affinity, final long hz)
    {
        require(now.busy() >= previous.busy() && now.own() >= previous.own(), "v3 CPU counters decreased");
        final double minSeconds = (now.before() - previous.after()) / 1e9;
        final double maxSeconds = (now.after() - previous.before()) / 1e9;
        require(minSeconds > 0 && maxSeconds >= minSeconds, "v3 counter timestamp order");
        final double spans = (now.uncertaintyNs() + previous.uncertaintyNs()) / 1e9;
        // Global busy covers all online CPUs; own process covers its affinity. Full read spans include the gap between files.
        final double errorSeconds = online * (spans + 2.0 / hz) + affinity * (spans + 2.0 / hz);
        final double deltaSeconds = (double) (now.busy() - previous.busy() - now.own() + previous.own()) / hz;
        final double low = Math.min((deltaSeconds - errorSeconds) / minSeconds, (deltaSeconds - errorSeconds) / maxSeconds);
        final double high = Math.max((deltaSeconds + errorSeconds) / minSeconds, (deltaSeconds + errorSeconds) / maxSeconds);
        final double signed = deltaSeconds / ((minSeconds + maxSeconds) / 2);
        record.addProperty("nonSelfCoresLower", low); record.addProperty("nonSelfCoresUpper", high);
        record.addProperty("nonSelfSignedCpuSeconds", deltaSeconds); record.addProperty("nonSelfSignedCores", signed);
        record.addProperty("nonSelfCores", Math.max(0, signed)); record.addProperty("counterUncertaintyCpuSeconds", errorSeconds);
        record.addProperty("onlineCpuCapacity", online); record.addProperty("affinityCpuCapacity", affinity);
        // Superseded quiet-host threshold: retained as context only.
    }

    /** Synthetic producer/arithmetic exercise only. No real sample, observer state or resource-gate bypass. */
    static void exerciseRecordPaths()
    {
        for (int block = 0; block < 2; block++)
        {
            final JsonArray points = new JsonArray();
            for (int i = 1; i <= 1000; i++) { points.add(sampleRecord(i + block * 1000L, i, 200_000, 1000, 50_000_000, 200_000)); }
            final JsonObject record = new JsonObject(); record.add("samples", points);
            cpuInterval(record, new Counters(100, 50, 0, 1000, 1000),
              new Counters(200, 150, 50_000_000_000L, 50_000_001_000L, 1000), 6, 2, 100);
            record.deepCopy().toString();
        }
    }

    void abort(final Throwable failure)
    {
        if (closed) { return; }
        try
        {
            final JsonObject record = new JsonObject(); record.addProperty("type", "invalid"); record.addProperty("reason", failure.toString());
            record.add("unflushedSamples", pending); emit(record, true, true);
        }
        catch (final RuntimeException ignored) { /* Runner retains abnormal termination and the last durable prefix. */ }
        writer.interrupt();
        closed = true;
    }

    static long median(final List<Long> input)
    {
        require(!input.isEmpty(), "empty v3 median");
        final long[] values = input.stream().mapToLong(Long::longValue).sorted().toArray();
        return values[values.length / 2];
    }

    private void emit(final JsonObject object, final boolean wait, final boolean terminal)
    {
        checkWriter();
        final CompletableFuture<Void> ack = new CompletableFuture<>();
        require(writes.offer(new Write(object.toString() + "\n", terminal, ack)), "v3 durable writer queue overflow");
        if (wait)
        {
            try { ack.get(2, TimeUnit.SECONDS); }
            catch (final Exception failure) { throw new IllegalStateException("v3 durable acknowledgement failed", failure); }
        }
    }

    private void checkWriter()
    {
        if (writerFailure != null) { throw new IllegalStateException("v3 durable writer failed", writerFailure); }
    }

    private void writeLoop(final Path destination)
    {
        try (FileChannel file = FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
        {
            final String writerStat = Files.readString(Path.of("/proc/thread-self/stat"));
            writerStartTicks = Long.parseLong(stat(writerStat)[19]);
            writerTid = Long.parseLong(writerStat.split(" ", 2)[0]);
            while (true)
            {
                final Write item = writes.take();
                try
                {
                    final ByteBuffer bytes = ByteBuffer.wrap(item.text().getBytes(StandardCharsets.UTF_8));
                    while (bytes.hasRemaining()) { file.write(bytes); }
                    file.force(true);
                    item.ack().complete(null);
                    if (item.terminal()) { return; }
                }
                catch (final Throwable failure) { item.ack().completeExceptionally(failure); throw failure; }
            }
        }
        catch (final Throwable failure) { writerFailure = failure; }
    }

    private static void require(final boolean condition, final String message)
    {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
