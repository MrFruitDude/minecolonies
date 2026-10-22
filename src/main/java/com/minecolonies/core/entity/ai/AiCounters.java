package com.minecolonies.core.entity.ai;

import com.minecolonies.api.colony.IColony;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Round 13: per colony counters of the worker AI, for benchmarks and productivity views. Counted on the server thread by the worker AI
 * state machine (int and long adds only), read through {@link #snapshot(IColony)}.
 * <p>
 * Per job (the job registry key): game ticks spent in each AI state, ticks waiting for items (NEEDS_ITEM), walks to the own hut, inventory
 * dumps and the stacks they moved, request pickups (hut visits that took completed requests) and the requests taken. Per colony: courier
 * trips and the requests they served ({@link #courierTrip}, fed by the courier AI).
 * <p>
 * Runtime only: the counters start when the server starts (or at {@link #reset}) and are not saved; {@link #clearAll} drops them when the
 * server stops. Server thread only: the maps are not thread safe, so every call off the server thread counts nothing, and {@link #snapshot}
 * returns an empty copy there (a CC peripheral must read with mainThread=true). {@link #snapshot} copies the maps so a reader keeps a stable
 * view.
 */
public final class AiCounters
{
    /**
     * The name of the waiting-for-items state ({@code AIWorkerState.NEEDS_ITEM}).
     */
    private static final String NEEDS_ITEM = "NEEDS_ITEM";

    /**
     * The counters of every colony, by dimension and colony id.
     */
    private static final Map<String, ColonyCounters> COLONIES = new HashMap<>();

    private AiCounters()
    {
    }

    /**
     * The counters of one job in one colony. Mutable, owned by the server thread; the worker AI keeps a reference to add without lookups.
     */
    public static final class JobCounters
    {
        private final Map<String, long[]> stateTicks = new HashMap<>();
        private long needsItemTicks;
        private long hutWalks;
        private long dumps;
        private long dumpedStacks;
        private long pickups;
        private long pickupRequests;

        /**
         * Adds game ticks spent in a state.
         *
         * @param state the state name.
         * @param ticks the ticks.
         */
        public void addStateTicks(final String state, final int ticks)
        {
            final long[] total = stateTicks.computeIfAbsent(state, s -> new long[1]);
            total[0] += ticks;
            if (NEEDS_ITEM.equals(state))
            {
                needsItemTicks += ticks;
            }
        }

        /**
         * One walk to the own hut started.
         */
        public void hutWalk()
        {
            hutWalks++;
        }

        /**
         * One inventory dump finished.
         *
         * @param stacks the stacks it moved into the building.
         */
        public void dump(final int stacks)
        {
            dumps++;
            dumpedStacks += stacks;
        }

        /**
         * One hut visit that took completed requests.
         *
         * @param requests the requests taken in this visit.
         */
        public void pickup(final int requests)
        {
            pickups++;
            pickupRequests += requests;
        }

        private JobSnapshot snapshot()
        {
            final Map<String, Long> ticks = new TreeMap<>();
            for (final Map.Entry<String, long[]> entry : stateTicks.entrySet())
            {
                ticks.put(entry.getKey(), entry.getValue()[0]);
            }
            return new JobSnapshot(Collections.unmodifiableMap(ticks), needsItemTicks, hutWalks, dumps, dumpedStacks, pickups, pickupRequests);
        }
    }

    /**
     * The counters of one colony.
     */
    private static final class ColonyCounters
    {
        /**
         * The colony these counters belong to: a colony deleted and replaced by a new one with the same id does not inherit them.
         */
        private final java.lang.ref.WeakReference<IColony> owner;
        private final Map<String, JobCounters> jobs = new HashMap<>();
        private long courierTrips;
        private long courierRequests;
        private long since = -1L;

        private ColonyCounters(final IColony owner)
        {
            this.owner = new java.lang.ref.WeakReference<>(owner);
        }
    }

    /**
     * A copy of the counters of one job.
     *
     * @param stateTicks     game ticks per AI state name (sorted by name).
     * @param needsItemTicks game ticks spent waiting for items (NEEDS_ITEM).
     * @param hutWalks       walks to the own hut started.
     * @param dumps          inventory dumps finished.
     * @param dumpedStacks   stacks moved into a building by those dumps.
     * @param pickups        hut visits that took completed requests.
     * @param pickupRequests requests taken by those visits.
     */
    public record JobSnapshot(Map<String, Long> stateTicks, long needsItemTicks, long hutWalks, long dumps, long dumpedStacks, long pickups, long pickupRequests)
    {
        /**
         * @return all game ticks counted for this job.
         */
        public long totalTicks()
        {
            long total = 0;
            for (final long ticks : stateTicks.values())
            {
                total += ticks;
            }
            return total;
        }

        /**
         * @return a plain map (numbers, strings and maps only) for JSON exports.
         */
        public Map<String, Object> toMap()
        {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("stateTicks", stateTicks);
            map.put("totalTicks", totalTicks());
            map.put("needsItemTicks", needsItemTicks);
            map.put("hutWalks", hutWalks);
            map.put("dumps", dumps);
            map.put("dumpedStacks", dumpedStacks);
            map.put("pickups", pickups);
            map.put("pickupRequests", pickupRequests);
            return map;
        }
    }

    /**
     * A copy of the counters of one colony.
     *
     * @param colonyId        the colony id.
     * @param dimension       the colony dimension.
     * @param sinceGameTime   the game time of the first count (or of the last reset), -1 when nothing was counted.
     * @param jobs            the counters per job registry key (sorted by key).
     * @param courierTrips    courier trips finished.
     * @param courierRequests requests served by those trips.
     */
    public record Snapshot(int colonyId, String dimension, long sinceGameTime, Map<String, JobSnapshot> jobs, long courierTrips, long courierRequests)
    {
        /**
         * @return requests per courier trip, 0 without trips.
         */
        public double requestsPerCourierTrip()
        {
            return courierTrips == 0 ? 0.0D : (double) courierRequests / courierTrips;
        }

        /**
         * @return a plain map (numbers, strings and maps only) for JSON exports.
         */
        public Map<String, Object> toMap()
        {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("colonyId", colonyId);
            map.put("dimension", dimension);
            map.put("sinceGameTime", sinceGameTime);
            final Map<String, Object> jobMaps = new LinkedHashMap<>();
            for (final Map.Entry<String, JobSnapshot> entry : jobs.entrySet())
            {
                jobMaps.put(entry.getKey(), entry.getValue().toMap());
            }
            map.put("jobs", jobMaps);
            map.put("courierTrips", courierTrips);
            map.put("courierRequests", courierRequests);
            map.put("requestsPerCourierTrip", requestsPerCourierTrip());
            return map;
        }
    }

    private static String key(final IColony colony)
    {
        return colony.getDimension().identifier() + "#" + colony.getID();
    }

    /**
     * @param colony the colony.
     * @return whether the colony is on a server and the caller runs on that server's thread.
     */
    private static boolean onServerThread(@Nullable final IColony colony)
    {
        return colony != null && colony.getWorld() != null && !colony.getWorld().isClientSide() && colony.getWorld().getServer() != null
                 && colony.getWorld().getServer().isSameThread();
    }

    @Nullable
    private static ColonyCounters colony(@Nullable final IColony colony, final boolean create)
    {
        if (!onServerThread(colony))
        {
            return null;
        }
        final String key = key(colony);
        ColonyCounters counters = COLONIES.get(key);
        if (counters != null && counters.owner.get() != colony)
        {
            // another colony had this id before (deleted): its counters are not this colony's
            if (!create)
            {
                return null;
            }
            COLONIES.remove(key);
            generation++;
            counters = null;
        }
        if (counters == null && create)
        {
            counters = new ColonyCounters(colony);
            counters.since = colony.getWorld().getGameTime();
            COLONIES.put(key, counters);
        }
        return counters;
    }

    /**
     * The counters of a job in a colony, created on first use. The worker AI keeps the result to count without lookups.
     *
     * @param colony the colony.
     * @param job    the job registry key.
     * @return the counters, or null on a client or without a colony.
     */
    @Nullable
    public static JobCounters job(@Nullable final IColony colony, final String job)
    {
        final ColonyCounters counters = colony(colony, true);
        return counters == null ? null : counters.jobs.computeIfAbsent(job, j -> new JobCounters());
    }

    /**
     * One courier trip finished (fed by the courier AI).
     *
     * @param colony   the colony.
     * @param requests the requests the trip served.
     */
    public static void courierTrip(@Nullable final IColony colony, final int requests)
    {
        final ColonyCounters counters = colony(colony, true);
        if (counters != null)
        {
            counters.courierTrips++;
            counters.courierRequests += Math.max(0, requests);
        }
    }

    /**
     * A read-only copy of the counters of a colony. Server thread only.
     *
     * @param colony the colony.
     * @return the copy (empty when nothing was counted yet, or when called off the server thread).
     */
    public static Snapshot snapshot(final IColony colony)
    {
        final ColonyCounters counters = colony(colony, false);
        final String dimension = colony.getDimension().identifier().toString();
        if (counters == null)
        {
            return new Snapshot(colony.getID(), dimension, -1L, Collections.emptyMap(), 0L, 0L);
        }
        final Map<String, JobSnapshot> jobs = new TreeMap<>();
        for (final Map.Entry<String, JobCounters> entry : counters.jobs.entrySet())
        {
            jobs.put(entry.getKey(), entry.getValue().snapshot());
        }
        return new Snapshot(colony.getID(), dimension, counters.since, Collections.unmodifiableMap(jobs), counters.courierTrips, counters.courierRequests);
    }

    /**
     * Starts the counters of a colony again from zero. The worker AIs keep counting into fresh counters (they look them up again).
     *
     * @param colony the colony.
     */
    public static void reset(final IColony colony)
    {
        if (!onServerThread(colony))
        {
            return;
        }
        COLONIES.remove(key(colony));
        generation++;
    }

    /**
     * Drops the counters of every colony (server stop): a singleplayer world switch starts from zero.
     */
    public static void clearAll()
    {
        COLONIES.clear();
        generation++;
    }

    /**
     * Raised by {@link #reset}: a worker AI that cached its counters looks them up again when this changed.
     */
    private static int generation = 0;

    /**
     * @return the reset generation (see {@link #reset}).
     */
    public static int generation()
    {
        return generation;
    }
}
