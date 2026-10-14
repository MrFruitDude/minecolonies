package com.minecolonies.core.colony.labour;

import net.minecraft.world.item.ItemStack;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Counters of the labourers' work, for the request-system benchmark and the logs: how many claims were made and how they ended, what was gathered and handed in. Process
 * wide; the benchmark resets them for each run. The gather probe is told every stack a labourer gets out of the world (a felled log), so that the benchmark's item accounting
 * can count it as supplied.
 */
public final class LabourStats
{
    public static final AtomicLong CLAIMS         = new AtomicLong();
    public static final AtomicLong CLAIMS_OK      = new AtomicLong();
    public static final AtomicLong CLAIMS_FAILED  = new AtomicLong();
    public static final AtomicLong CLAIMS_EXPIRED = new AtomicLong();
    /** Claims made for a good a specialist covered (never expected: a failure of the coverage rule). */
    public static final AtomicLong CLAIMS_ON_COVERED = new AtomicLong();
    /** Goods handed in at the huts that asked, and gathered out of the world, in items. */
    public static final AtomicLong HANDED_IN = new AtomicLong();
    public static final AtomicLong GATHERED  = new AtomicLong();

    /** Only for tests and the benchmark: told every stack that comes out of the world into a labourer's hands. */
    public static volatile Consumer<ItemStack> gatherProbe;

    /** Only for tests: log the life of claims and sessions. */
    public static volatile boolean debug;

    public static void log(final String message, final Object... args)
    {
        if (debug)
        {
            com.minecolonies.api.util.Log.getLogger().info("LABOUR " + message, args);
        }
    }

    private LabourStats()
    {
    }

    public static void reset()
    {
        CLAIMS.set(0);
        CLAIMS_OK.set(0);
        CLAIMS_FAILED.set(0);
        CLAIMS_EXPIRED.set(0);
        CLAIMS_ON_COVERED.set(0);
        HANDED_IN.set(0);
        GATHERED.set(0);
    }

    /** A labourer got these items out of the world (a log from a felled tree, cobblestone from a dug block). */
    public static void gathered(final ItemStack stack)
    {
        GATHERED.addAndGet(stack.getCount());
        final Consumer<ItemStack> probe = gatherProbe;
        if (probe != null)
        {
            probe.accept(stack);
        }
    }
}
