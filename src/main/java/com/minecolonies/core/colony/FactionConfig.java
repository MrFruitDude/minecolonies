package com.minecolonies.core.colony;

import com.minecolonies.api.colony.permissions.FactionPlayerRank;
import com.minecolonies.core.MineColonies;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The faction colony settings ({@code factions.*} in the server config), which a GameTest can override in code (the
 * config is read through its spec, which a test must not change).
 */
public final class FactionConfig
{
    @Nullable
    private static volatile FactionPlayerRank rankOverride   = null;
    @Nullable
    private static volatile Boolean           raidedOverride = null;

    private FactionConfig()
    {
    }

    /**
     * @return the rank players have in a faction colony unless given another: neutral, friend or hostile, never a manager rank.
     */
    @NotNull
    public static FactionPlayerRank defaultPlayerRank()
    {
        final FactionPlayerRank forced = rankOverride;
        if (forced != null)
        {
            return forced;
        }
        final FactionPlayerRank configured = MineColonies.getConfig().getServer().factionColonyDefaultPlayerRank.get();
        return configured == null ? FactionPlayerRank.NEUTRAL : configured;
    }

    /**
     * @return true if faction colonies may be raided (default false).
     */
    public static boolean canBeRaided()
    {
        final Boolean forced = raidedOverride;
        return forced != null ? forced : MineColonies.getConfig().getServer().factionColoniesCanBeRaided.get();
    }

    /**
     * Test hook.
     *
     * @param rank the rank to use, or null for the config value.
     */
    public static void overrideDefaultPlayerRank(@Nullable final FactionPlayerRank rank)
    {
        rankOverride = rank;
    }

    /**
     * Test hook.
     *
     * @param raided the value to use, or null for the config value.
     */
    public static void overrideCanBeRaided(@Nullable final Boolean raided)
    {
        raidedOverride = raided;
    }
}
