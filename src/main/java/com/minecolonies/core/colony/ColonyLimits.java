package com.minecolonies.core.colony;

import com.minecolonies.core.MineColonies;
import org.jetbrains.annotations.Nullable;

/**
 * The per-player colony limit: the {@code colonies.maxPerPlayer} config, which a GameTest can override in code (the
 * config is read through its spec, which a test must not change).
 */
public final class ColonyLimits
{
    @Nullable
    private static volatile Integer override = null;

    private ColonyLimits()
    {
    }

    /**
     * @return how many colonies one player may own, at least 1.
     */
    public static int maxPerPlayer()
    {
        final Integer forced = override;
        if (forced != null)
        {
            return Math.max(1, forced);
        }
        return Math.max(1, MineColonies.getConfig().getServer().maxColoniesPerPlayer.get());
    }

    /**
     * Test hook.
     *
     * @param max the limit to use, or null for the config value.
     */
    public static void overrideMaxPerPlayer(@Nullable final Integer max)
    {
        override = max;
    }
}
