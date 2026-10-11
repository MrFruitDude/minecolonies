package com.minecolonies.core.gametest.bench;

import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.gametest.MinecoloniesGameTestInstance;
import com.minecolonies.core.gametest.MinecoloniesGameTestRegistrar;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.util.function.Consumer;

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
        for (final int scale : scales)
        {
            for (int rep = 0; rep <= reps; rep++)
            {
                final String name = "rsb_micro_" + scale + "_r" + rep;
                final int r = rep;
                add(event, name, 3000, helper -> MicroBench.run(helper, name, scale, r, r == 0));
            }
        }

        for (int rep = 0; rep <= reps; rep++)
        {
            final String name = "rsb_fresh_r" + rep;
            final int r = rep;
            add(event, name, 70_000, helper -> new MacroRun(helper, name, "fresh", r, r == 0, Scenarios.fresh(quick)).start());
        }
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
