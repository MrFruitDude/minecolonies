package com.minecolonies.core.entity.ai.workers.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Counts the blocks workers place or break out of their physical reach. Off by default; the GameTests switch it on to check that a builder
 * never reaches further than the reach setting allows.
 */
public final class ReachAudit
{
    private ReachAudit()
    {
    }

    private static volatile boolean enabled = false;
    private static volatile int     actions = 0;
    private static volatile int     violations = 0;
    private static volatile double  worst = 0.0D;
    private static final List<String> examples = Collections.synchronizedList(new ArrayList<>());

    public static void enable(final boolean on)
    {
        enabled = on;
        actions = 0;
        violations = 0;
        worst = 0.0D;
        examples.clear();
    }

    public static boolean isEnabled()
    {
        return enabled;
    }

    /**
     * A worker placed or broke the block.
     */
    public static void record(final LivingEntity worker, final BlockPos block)
    {
        if (!enabled)
        {
            return;
        }
        actions++;
        final double dist = Math.sqrt(BuildReach.distSq(worker.getX(), worker.getEyeY(), worker.getZ(), block));
        final double reach = WorkerSettings.reach();
        if (dist > reach + 0.05D)
        {
            violations++;
            worst = Math.max(worst, dist - reach);
            if (examples.size() < 5)
            {
                examples.add(block.toShortString() + " from " + worker.blockPosition().toShortString() + " dist=" + String.format("%.2f", dist));
            }
        }
    }

    public static int actions()
    {
        return actions;
    }

    public static int violations()
    {
        return violations;
    }

    public static double worstExcess()
    {
        return worst;
    }

    public static List<String> examples()
    {
        return new ArrayList<>(examples);
    }
}
