package com.minecolonies.core.gametest.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Settings of a benchmark run, all from environment variables so the runner script and the GameTest registrar agree:
 * <ul>
 * <li>{@code RS_BENCH_ONLY=1}: register only the benchmark tests (otherwise the whole GameTest suite runs too).</li>
 * <li>{@code RS_BENCH_MODE=quick|full}: which scenarios and how long (default full).</li>
 * <li>{@code RS_BENCH_REPS=n}: measured repeats per test (default 5 for full, 2 for quick); one extra warm-up repeat precedes them.</li>
 * <li>{@code RS_BENCH_SEED=n}: seed of every random choice (default 117).</li>
 * <li>{@code RS_BENCH_FLAGS=on|off}: the RS reservation + smart-retry switches (default on, the shipped default).</li>
 * <li>{@code RS_BENCH_WORKERS=pinned|default}: pin worker speed (1.0) and walking (always walk) for the macro scenarios (default pinned).</li>
 * <li>{@code RS_BENCH_BREAKS=1}: it rains 300 ticks of every 3000, so the citizen AI wants to send the workers, couriers that hold a task among them, to shelter (default
 * off: midday, clear). (Hunger is not simulated: a citizen whose saturation the harness restores in the middle of a meal stays in the eating state for good.)</li>
 * <li>{@code RS_BENCH_LABOUR=on}: the inert gate: the macro scenarios run with the labourers ON and six jobless citizens added; their chains cover every good, so nothing may be claimed
 * (default off: labour is off in every scenario but {@code early}).</li>
 * <li>{@code RS_BENCH_OUT=dir}: where the per-test JSON files go (default {@code rs-bench} in the run directory).</li>
 * <li>{@code RS_BENCH_ONLY_TESTS=a,b}: restrict to tests whose name contains one of the words.</li>
 * </ul>
 */
public final class BenchEnv
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();

    private BenchEnv()
    {
    }

    private static String get(final String name, final String fallback)
    {
        final String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public static boolean only()
    {
        return "1".equals(get("RS_BENCH_ONLY", "0"));
    }

    public static boolean quick()
    {
        return "quick".equalsIgnoreCase(get("RS_BENCH_MODE", "full"));
    }

    public static String mode()
    {
        return quick() ? "quick" : "full";
    }

    public static int reps()
    {
        return Integer.parseInt(get("RS_BENCH_REPS", quick() ? "2" : "5"));
    }

    public static long seed()
    {
        return Long.parseLong(get("RS_BENCH_SEED", "117"));
    }

    public static boolean flagsOn()
    {
        return !"off".equalsIgnoreCase(get("RS_BENCH_FLAGS", "on"));
    }

    /**
     * @return true (default) to pin the worker settings for the macro scenarios: speed 1.0, builder speed 1.0, workers always walk.
     * {@code RS_BENCH_WORKERS=default} runs with the server's own settings.
     */
    public static boolean pinWorkers()
    {
        return !"default".equalsIgnoreCase(get("RS_BENCH_WORKERS", "pinned"));
    }

    public static boolean breaks()
    {
        return "1".equals(get("RS_BENCH_BREAKS", "0"));
    }

    /**
     * @return true to run the macro scenarios with the labourers on and jobless citizens present (the inert gate).
     */
    public static boolean labourInert()
    {
        return "on".equalsIgnoreCase(get("RS_BENCH_LABOUR", "off"));
    }

    public static Path outDir()
    {
        return Paths.get(get("RS_BENCH_OUT", "rs-bench"));
    }

    public static Set<String> onlyTests()
    {
        final String value = get("RS_BENCH_ONLY_TESTS", "");
        return value.isEmpty() ? Set.of() : new HashSet<>(Arrays.asList(value.split(",")));
    }

    public static boolean selected(final String testName)
    {
        final Set<String> only = onlyTests();
        return only.isEmpty() || only.stream().anyMatch(testName::contains);
    }

    /**
     * Writes one test's result next to its siblings: {@code <out>/<test>.json}.
     */
    public static void write(final String testName, final JsonElement json)
    {
        try
        {
            final Path dir = outDir().toAbsolutePath();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(testName + ".json"), GSON.toJson(json), StandardCharsets.UTF_8);
        }
        catch (final IOException e)
        {
            throw new IllegalStateException("cannot write the benchmark result of " + testName, e);
        }
    }
}
