package com.minecolonies.core.colony.workorders.collab;

import com.minecolonies.api.MinecoloniesAPIProxy;
import com.minecolonies.api.configuration.ServerConfiguration;

import java.util.function.Function;

/**
 * Settings of the builder collaboration (server config category {@code builders}). Idle builders help the builder who leads
 * an order; when a new order arrives and no builder is free, a helper hands his work back and leads the new order.
 */
public final class BuilderCollab
{
    private BuilderCollab()
    {
    }

    /**
     * Game test overrides; null means the config decides.
     */
    private static volatile Boolean enabledOverride;
    private static volatile Integer maxHelpersOverride;
    private static volatile Integer blocksPerHelperOverride;
    private static volatile Integer minRemainingOverride;
    private static volatile Integer stayTicksOverride;
    private static volatile Integer leaseSizeOverride;
    private static volatile Integer leaseTicksOverride;
    private static volatile Integer clusterMinOverride;
    private static volatile Integer minOrderScoreOverride;
    private static volatile Integer leaseCapFactorOverride;

    /**
     * The biggest lease is this many times the configured lease size.
     */
    private static final int LEASE_CAP_FACTOR = 4;

    /**
     * Only for tests: the factor of the biggest lease.
     */
    public static void overrideLeaseCapFactorForTests(final Integer factor)
    {
        leaseCapFactorOverride = factor;
    }

    /**
     * Only for tests: overrides the config. Null values go back to the config.
     */
    public static void overrideForTests(
      final Boolean enabled,
      final Integer maxHelpers,
      final Integer blocksPerHelper,
      final Integer minRemaining,
      final Integer stayTicks,
      final Integer leaseSize,
      final Integer leaseTicks)
    {
        enabledOverride = enabled;
        maxHelpersOverride = maxHelpers;
        blocksPerHelperOverride = blocksPerHelper;
        minRemainingOverride = minRemaining;
        stayTicksOverride = stayTicks;
        leaseSizeOverride = leaseSize;
        leaseTicksOverride = leaseTicks;
    }

    /**
     * The helper score from which an order gets helpers (see {@link #worthAHelper}) when nothing else is configured.
     */
    public static final int DEFAULT_MIN_ORDER_SCORE = 600;

    /**
     * Only for tests: the helper score from which an order gets helpers; null for the config (or, in a test that switched the collaboration on by hand, none: the
     * tests with small structures expect their helpers).
     */
    public static void overrideMinOrderScoreForTests(final Integer score)
    {
        minOrderScoreOverride = score;
    }

    /**
     * The helper score an order needs to get helpers at all.
     */
    public static int minOrderScore()
    {
        final Integer override = minOrderScoreOverride;
        if (override != null)
        {
            return override;
        }
        if (enabledOverride != null)
        {
            return 0;
        }
        return value(null, c -> c.builderCollaborationMinOrderScore.get(), DEFAULT_MIN_ORDER_SCORE);
    }

    /**
     * Whether a second builder pays off for the structure. A helper starts late (he walks to the site and fetches materials first), takes his materials
     * from the lead in trips, and when the structure is almost done the two get in each other's way; all that costs about as much as the second builder saves on a
     * small structure. Measured with the scaffold benchmark (16 runs each, one builder against two): a 7 by 7 ring of 11 (264 blocks, 120 of them high) finishes
     * in the same time with a helper (median -4 percent) but a third of the runs take 25 to 60 percent longer, a 30 by 6 wall of 3 (540 blocks, flat) is
     * 5 percent slower with the helper and a tail of +30 percent, an 11 by 11 ring of 12 (480, 240 high) takes 30 percent less, a 9 by 9 ring of 16 (512, 320
     * high) 30 percent less. Blocks that need a tower count three times: {@code blocks + 2 * high >= 600} puts the line between them.
     *
     * @param blocks the blocks to place.
     * @param high   how many of them are out of reach from the ground.
     */
    public static boolean worthAHelper(final int blocks, final int high)
    {
        return blocks + 2 * high >= minOrderScore();
    }

    /**
     * Only for tests: the number of blocks from which a structure is divided into parts for the builders.
     */
    public static void overrideClusterMinForTests(final Integer blocks)
    {
        clusterMinOverride = blocks;
    }

    /**
     * Structures with at least this many blocks are divided into parts for the builders, and the helper leases clusters of positions.
     */
    public static int clusterMinBlocks()
    {
        final Integer override = clusterMinOverride;
        return override != null ? override : 400;
    }

    /**
     * Only for tests: told about every block a builder places (position, hut of the builder).
     */
    public static volatile java.util.function.BiConsumer<net.minecraft.core.BlockPos, net.minecraft.core.BlockPos> placementProbe;

    /**
     * Reports a placed block to the test probe.
     */
    public static void placed(final net.minecraft.core.BlockPos worldPos, final net.minecraft.core.BlockPos hut)
    {
        final java.util.function.BiConsumer<net.minecraft.core.BlockPos, net.minecraft.core.BlockPos> probe = placementProbe;
        if (probe != null)
        {
            probe.accept(worldPos, hut);
        }
    }

    private static volatile Boolean fastClearOverride;

    /**
     * Only for tests: overrides the fast clear setting.
     */
    public static void overrideFastClearForTests(final Boolean fastClear)
    {
        fastClearOverride = fastClear;
    }

    /**
     * Whether the clear stage skips blocks that already match the blueprint.
     */
    public static boolean fastClear()
    {
        final Boolean override = fastClearOverride;
        if (override != null)
        {
            return override;
        }
        return com.minecolonies.core.entity.ai.workers.util.WorkerSettings.fastClear();
    }

    public static boolean enabled()
    {
        final Boolean override = enabledOverride;
        if (override != null)
        {
            return override;
        }
        try
        {
            return MinecoloniesAPIProxy.getInstance().getConfig().getServer().builderCollaboration.get();
        }
        catch (final RuntimeException e)
        {
            // config not loaded (yet)
            return false;
        }
    }

    /**
     * Most helpers one order gets.
     */
    public static int maxHelpers()
    {
        return value(maxHelpersOverride, c -> c.builderCollaborationMaxHelpers.get(), 3);
    }

    /**
     * An order gets one helper per this many blocks still to do (and at least one while it has enough left).
     */
    public static int blocksPerHelper()
    {
        return value(blocksPerHelperOverride, c -> c.builderCollaborationBlocksPerHelper.get(), 1500);
    }

    /**
     * An order with less than this left gets no new helpers.
     */
    public static int minRemaining()
    {
        return value(minRemainingOverride, c -> c.builderCollaborationMinRemaining.get(), 64);
    }

    /**
     * Ticks a helper stays with an order before he may be moved on (a new order still takes him at once).
     */
    public static int stayTicks()
    {
        return value(stayTicksOverride, c -> c.builderCollaborationStaySeconds.get() * 20, 2400);
    }

    /**
     * Positions a helper leases at once.
     */
    public static int leaseSize()
    {
        return value(leaseSizeOverride, c -> c.builderCollaborationLeaseSize.get(), 12);
    }

    /**
     * How many positions a helper leases at once when he has to walk a distance to the hut of the lead for the materials: the configured size, more
     * when the hut is far (a lease of a few blocks that takes longer to fetch than to place is no help), at most four times the configured size.
     *
     * @param base     the configured size.
     * @param distance the distance to the hut of the lead, in blocks.
     * @return the number of positions to lease.
     */
    public static int scaledLeaseSize(final int base, final double distance)
    {
        final int size = Math.max(1, base);
        final Integer override = leaseCapFactorOverride;
        return (int) Math.min((long) (override != null ? override : LEASE_CAP_FACTOR) * size, size + Math.round(2.0D * Math.max(0.0D, distance)));
    }

    /**
     * Ticks a lease lives without the helper showing life.
     */
    public static int leaseTicks()
    {
        return value(leaseTicksOverride, c -> c.builderCollaborationLeaseSeconds.get() * 20, 2400);
    }

    private static int value(final Integer override, final Function<ServerConfiguration, Integer> config, final int fallback)
    {
        if (override != null)
        {
            return override;
        }
        try
        {
            return config.apply(MinecoloniesAPIProxy.getInstance().getConfig().getServer());
        }
        catch (final RuntimeException e)
        {
            return fallback;
        }
    }
}
