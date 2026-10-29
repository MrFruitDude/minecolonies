package com.minecolonies.core.entity.ai.workers.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-level tree claims, so two Foresters never pick the same tree. A claim covers every log of the tree as the Forester saw it and expires on its own
 * (two minutes, refreshed while he works), so a lost claim never blocks a tree for long. A blocked claim marks a tree that touches a player build: no
 * Forester of any colony picks it until the claim expires and the tree is checked again. Claims belong to one loaded level (another world opened in the
 * same game starts with none) and run on its game time (a paused game does not let them expire).
 * <p>
 * The tree search runs off the server thread and only reads this map; claims are made and released on the server thread.
 */
public final class TreeClaims
{
    /**
     * How long a work claim lasts without a refresh, in game ticks (two minutes).
     */
    public static final long WORK_CLAIM_TICKS = 2 * 60 * 20L;

    /**
     * How long a tree next to a build stays blocked before it is checked again, in game ticks (ten minutes).
     */
    public static final long BLOCKED_CLAIM_TICKS = 10 * 60 * 20L;

    /**
     * Owner id of a blocked tree.
     */
    public static final int BLOCKED = -1;

    /**
     * Expired claims are swept when a level holds more positions than this.
     */
    private static final int SWEEP_SIZE = 16384;

    private static final Map<Level, Map<Long, Claim>> CLAIMS = Collections.synchronizedMap(new WeakHashMap<>());

    private TreeClaims()
    {
    }

    /**
     * One claim on one tree.
     */
    public static final class Claim
    {
        private final Level              level;
        private final int                colonyId;
        private final int                citizenId;
        private final List<Long>         positions;
        private volatile long            expiresAt;

        private Claim(final Level level, final int colonyId, final int citizenId, final List<Long> positions, final long expiresAt)
        {
            this.level = level;
            this.colonyId = colonyId;
            this.citizenId = citizenId;
            this.positions = positions;
            this.expiresAt = expiresAt;
        }

        private boolean expired(final long now)
        {
            return now > expiresAt;
        }

        /**
         * @return true for a tree next to a build.
         */
        public boolean isBlocked()
        {
            return citizenId == BLOCKED;
        }

        /**
         * Keep the claim alive while the Forester works on the tree.
         */
        public void refresh()
        {
            if (!isBlocked())
            {
                expiresAt = level.getGameTime() + WORK_CLAIM_TICKS;
            }
        }
    }

    /**
     * Claim a tree for one Forester.
     *
     * @param level     the level.
     * @param logs      the logs of the tree.
     * @param colonyId  his colony.
     * @param citizenId his citizen id.
     * @return the claim.
     */
    @NotNull
    public static Claim claim(@NotNull final Level level, @NotNull final Collection<BlockPos> logs, final int colonyId, final int citizenId)
    {
        return put(level, logs, colonyId, citizenId, WORK_CLAIM_TICKS);
    }

    /**
     * Block a tree that touches a build for every Forester.
     *
     * @param level    the level.
     * @param logs     the logs of the tree.
     * @param colonyId the colony that found it.
     */
    public static void block(@NotNull final Level level, @NotNull final Collection<BlockPos> logs, final int colonyId)
    {
        put(level, logs, colonyId, BLOCKED, BLOCKED_CLAIM_TICKS);
    }

    private static Claim put(final Level level, final Collection<BlockPos> logs, final int colonyId, final int citizenId, final long ttl)
    {
        final Map<Long, Claim> map = CLAIMS.computeIfAbsent(level, k -> new ConcurrentHashMap<>());
        final long now = level.getGameTime();
        if (map.size() > SWEEP_SIZE)
        {
            map.values().removeIf(c -> c.expired(now));
        }
        final List<Long> positions = new ArrayList<>(logs.size());
        for (final BlockPos pos : logs)
        {
            positions.add(pos.asLong());
        }
        final Claim claim = new Claim(level, colonyId, citizenId, positions, now + ttl);
        for (final Long pos : positions)
        {
            map.put(pos, claim);
        }
        return claim;
    }

    /**
     * Drop a claim (the tree is done or given up).
     *
     * @param claim the claim, may be null.
     */
    public static void release(@Nullable final Claim claim)
    {
        if (claim == null)
        {
            return;
        }
        final Map<Long, Claim> map = CLAIMS.get(claim.level);
        if (map != null)
        {
            for (final Long pos : claim.positions)
            {
                map.remove(pos, claim);
            }
        }
    }

    /**
     * Whether a log belongs to a tree another Forester works on, or to a blocked tree. Safe off the server thread.
     *
     * @param level     the level.
     * @param pos       the log.
     * @param colonyId  the asking Forester's colony.
     * @param citizenId the asking Forester's citizen id.
     * @return true if he must leave the tree alone.
     */
    public static boolean isTakenByOther(@NotNull final Level level, @NotNull final BlockPos pos, final int colonyId, final int citizenId)
    {
        final Map<Long, Claim> map = CLAIMS.get(level);
        if (map == null)
        {
            return false;
        }
        final Claim claim = map.get(pos.asLong());
        if (claim == null || claim.expired(level.getGameTime()))
        {
            return false;
        }
        return claim.isBlocked() || claim.colonyId != colonyId || claim.citizenId != citizenId;
    }

    /**
     * @param level the level.
     * @param pos   a log.
     * @return true if the log belongs to a blocked tree.
     */
    public static boolean isBlocked(@NotNull final Level level, @NotNull final BlockPos pos)
    {
        final Map<Long, Claim> map = CLAIMS.get(level);
        final Claim claim = map == null ? null : map.get(pos.asLong());
        return claim != null && claim.isBlocked() && !claim.expired(level.getGameTime());
    }
}
