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
 * Per-level tree claims, so two Foresters never work the same tree. A claim covers every log of the tree as the Forester saw it and is exclusive: it is
 * made only where no live claim of another Forester or block lies (r5 Sol M5). It is renewed while he works the tree, and only while it still holds every
 * log; it expires on its own when he is away (two minutes) and is dropped when he leaves the job or dies. After a reload he claims his saved tree again,
 * and leaves it if another Forester took it meanwhile. A blocked claim marks a tree that touches a player build: no
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
        /**
         * The level's claims (no reference to the level itself: the weak key stays weak).
         */
        private final Map<Long, Claim>   map;
        private final int                colonyId;
        private final int                citizenId;
        private final List<Long>         positions;
        private volatile long            expiresAt;

        private Claim(final Map<Long, Claim> map, final int colonyId, final int citizenId, final List<Long> positions, final long expiresAt)
        {
            this.map = map;
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
         * Keep the claim alive while the Forester works on the tree: only while it is live and still holds every log (no block or other claim took one).
         *
         * @param level the level.
         * @return false if the claim is lost: he must claim the tree again, or leave it.
         */
        public boolean renew(@NotNull final Level level)
        {
            if (isBlocked())
            {
                return false;
            }
            synchronized (map)
            {
                final long now = level.getGameTime();
                if (expired(now))
                {
                    return false;
                }
                for (final Long pos : positions)
                {
                    if (map.get(pos) != this)
                    {
                        return false;
                    }
                }
                expiresAt = now + WORK_CLAIM_TICKS;
                return true;
            }
        }
    }

    /**
     * Claim a tree for one Forester, if no other Forester holds any of its logs and none is blocked.
     *
     * @param level     the level.
     * @param logs      the logs of the tree.
     * @param colonyId  his colony.
     * @param citizenId his citizen id.
     * @return the claim, or null if the tree is taken or blocked.
     */
    @Nullable
    public static Claim claim(@NotNull final Level level, @NotNull final Collection<BlockPos> logs, final int colonyId, final int citizenId)
    {
        final Map<Long, Claim> map = CLAIMS.computeIfAbsent(level, k -> new ConcurrentHashMap<>());
        synchronized (map)
        {
            final long now = level.getGameTime();
            for (final BlockPos pos : logs)
            {
                final Claim held = map.get(pos.asLong());
                if (held != null && !held.expired(now) && (held.isBlocked() || held.colonyId != colonyId || held.citizenId != citizenId))
                {
                    return null;
                }
            }
            return put(level, logs, colonyId, citizenId, WORK_CLAIM_TICKS);
        }
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

    /**
     * Block a tree for a given time (a tree given up again and again is left be longer each time).
     *
     * @param level    the level.
     * @param logs     the logs of the tree.
     * @param colonyId the colony that found it.
     * @param ticks    how long.
     */
    public static void block(@NotNull final Level level, @NotNull final Collection<BlockPos> logs, final int colonyId, final long ticks)
    {
        put(level, logs, colonyId, BLOCKED, ticks);
    }

    /**
     * How long a tree is left be after its n-th give-up in a row (r2 Fable N3): {@link #BLOCKED_CLAIM_TICKS}, doubling up to eight times that.
     *
     * @param giveUps the give-ups of the tree so far, this one included.
     * @return the ticks.
     */
    public static long giveUpBlockTicks(final int giveUps)
    {
        return BLOCKED_CLAIM_TICKS << Math.min(Math.max(0, giveUps - 1), 3);
    }

    /**
     * A block takes every log, whoever held it (a tree next to a build is nobody's to work).
     */
    private static Claim put(final Level level, final Collection<BlockPos> logs, final int colonyId, final int citizenId, final long ttl)
    {
        final Map<Long, Claim> map = CLAIMS.computeIfAbsent(level, k -> new ConcurrentHashMap<>());
        synchronized (map)
        {
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
            final Claim claim = new Claim(map, colonyId, citizenId, positions, now + ttl);
            for (final Long pos : positions)
            {
                map.put(pos, claim);
            }
            return claim;
        }
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
        synchronized (claim.map)
        {
            for (final Long pos : claim.positions)
            {
                claim.map.remove(pos, claim);
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
