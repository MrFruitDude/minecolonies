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
    /** The block each builder (by entity id) works on from MineColonies' working range (the physical reach found no spot for it in time): not counted as a violation. */
    private static final java.util.Map<Integer, BlockPos> excused = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger fallbackActions = new java.util.concurrent.atomic.AtomicInteger();
    /** The builders whose excused block is placed from the limited working range (5 up or down), not MineColonies' default placement. */
    private static final java.util.Set<Integer> limitedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** The largest distance up or down from the eye to a block placed from the limited working range. */
    private static volatile double fallbackMaxVertical = 0.0D;
    /** The largest distance around (x plus z, MineColonies' measure) to a block placed by the default placement. */
    private static volatile long   defaultMax2D = 0L;
    /** MineColonies' default working radius around the builder. */
    static final int DEFAULT_RANGE = 5;

    public static void enable(final boolean on)
    {
        enabled = on;
        actions = 0;
        violations = 0;
        worst = 0.0D;
        examples.clear();
        excused.clear();
        limitedIds.clear();
        fallbackActions.set(0);
        fallbackMaxVertical = 0.0D;
        defaultMax2D = 0L;
    }

    /**
     * The block this builder now works on from MineColonies' working range (null: none): its placement is counted apart, not as a violation.
     */
    public static void excuse(final LivingEntity worker, final BlockPos block, final boolean limited)
    {
        if (block == null)
        {
            excused.remove(worker.getId());
            limitedIds.remove(worker.getId());
        }
        else
        {
            excused.put(worker.getId(), block.immutable());
            if (limited)
            {
                limitedIds.add(worker.getId());
            }
            else
            {
                limitedIds.remove(worker.getId());
            }
        }
    }

    /**
     * @return the blocks placed or broken from MineColonies' working range (the reach fallback).
     */
    public static int fallbackActions()
    {
        return fallbackActions.get();
    }

    /**
     * @return the largest distance up or down from the eye to a block placed or broken from the working range.
     */
    public static double fallbackMaxVertical()
    {
        return fallbackMaxVertical;
    }

    /**
     * @return the largest distance around (x plus z) to a block placed by the default placement.
     */
    public static long defaultMax2D()
    {
        return defaultMax2D;
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
        if (block.equals(excused.get(worker.getId())))
        {
            fallbackActions.incrementAndGet();
            if (!limitedIds.contains(worker.getId()))
            {
                // MineColonies' default placement: 5 around (its own walk radius), any height. Further away is a leak, not excused (builder review c3 R1)
                final long around = com.minecolonies.api.util.BlockPosUtil.getDistance2D(worker.blockPosition(), block);
                defaultMax2D = Math.max(defaultMax2D, around);
                if (around > DEFAULT_RANGE)
                {
                    violations++;
                    if (examples.size() < 5)
                    {
                        examples.add(block.toShortString() + " from " + worker.blockPosition().toShortString() + " default placement 2D=" + around);
                    }
                }
                return;
            }
            final double eye = worker.getEyeY();
            final double up = block.getY() > eye ? block.getY() - eye : (eye > block.getY() + 1 ? eye - (block.getY() + 1) : 0.0D);
            fallbackMaxVertical = Math.max(fallbackMaxVertical, up);
            return;
        }
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
