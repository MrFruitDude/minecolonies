package com.minecolonies.core.gametest.bench;

import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.gametest.MinecoloniesGameTestInstance;
import com.minecolonies.core.gametest.MinecoloniesGameTestRegistrar;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Registers the request-system benchmark tests ({@code minecolonies:rsb_*}). Repeat 0 of every test is a warm-up whose
 * result the report discards; repeats 1..K are the measurements.
 */
public final class BenchRegistrar
{
    private BenchRegistrar()
    {
    }

    public static void register(final RegisterGameTestsEvent event)
    {
        final int reps = BenchEnv.reps();
        final boolean quick = BenchEnv.quick();

        final int[] scales = quick ? new int[] {100, 1000} : new int[] {100, 1000, 10000};
        // micro: scales ascending, each with its warm-up repeat first, all in one test
        add(event, "rsb_micro", 4000 * scales.length * (reps + 1), helper -> {
            final List<int[]> plan = new ArrayList<>();
            for (final int scale : scales)
            {
                for (int rep = 0; rep <= reps; rep++)
                {
                    plan.add(new int[] {scale, rep});
                }
            }
            RepeatRunner.run(helper, plan.size() - 1, () -> {
                final net.minecraft.core.BlockPos lo = helper.absolutePos(new net.minecraft.core.BlockPos(-10, -2, -10));
                final net.minecraft.core.BlockPos hi = helper.absolutePos(new net.minecraft.core.BlockPos(60, 30, 44));
                return new net.minecraft.world.phys.AABB(lo.getX(), lo.getY(), lo.getZ(), hi.getX(), hi.getY(), hi.getZ());
            }, i -> (index, done) -> {
                final int scale = plan.get(index)[0];
                final int rep = plan.get(index)[1];
                MicroBench.run(helper, "rsb_micro_" + scale + "_r" + rep, scale, rep, rep == 0, done);
            });
        });
        macro(event, "fresh", reps, () -> Scenarios.fresh(quick));
        macro(event, "busy", reps, () -> Scenarios.busy(quick));
        macro(event, "storm", reps, () -> Scenarios.storm(quick));
        if (!quick)
        {
            macro(event, "unload", reps, () -> Scenarios.unload(false));
        }
    }

    private static final net.minecraft.world.phys.AABB AABB_NONE = new net.minecraft.world.phys.AABB(0, 0, 0, 0, 0, 0);

    /** Unmeasured repeats before the measured ones: the JIT is still compiling the request system during the first one. */
    private static final int MACRO_WARMUPS = 2;

    private static void macro(final RegisterGameTestsEvent event, final String scenario, final int reps, final Supplier<MacroRun.Scenario> factory)
    {
        final String name = "rsb_" + scenario;
        add(event, name, (reps + MACRO_WARMUPS) * (factory.get().budget() + 15_000), helper -> {
            final MacroRun[] last = {null};
            final net.minecraft.server.level.ServerPlayer[] shared = {null};
            RepeatRunner.run(helper, reps + MACRO_WARMUPS - 1, () -> last[0] == null ? AABB_NONE : last[0].plotBox(), i -> (index, done) -> {
                final MacroRun run = new MacroRun(helper, name + "_r" + index, scenario, index, index < MACRO_WARMUPS, factory.get(), shared[0], failure -> {
                    shared[0] = last[0].player;
                    done.accept(failure);
                });
                last[0] = run;
                run.start();
            });
        });
    }

    private static void add(final RegisterGameTestsEvent event, final String name, final int maxTicks, final Consumer<GameTestHelper> body)
    {
        if (!BenchEnv.selected(name))
        {
            return;
        }
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
          info -> new MinecoloniesGameTestInstance(info, body),
          MinecoloniesGameTestRegistrar.longIsolatedColonyData(event, name, maxTicks));
    }
}
