package com.minecolonies.core.gametest.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.WaitReason;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.requestsystem.token.StandardToken;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.requestsystem.RsFlags;
import com.minecolonies.core.colony.requestsystem.RsProbe;
import com.minecolonies.core.colony.requestsystem.RsStats;
import com.minecolonies.core.colony.requestsystem.management.manager.StandardRequestManager;
import com.minecolonies.core.colony.requestsystem.reservation.Reservation;
import com.minecolonies.core.colony.requestsystem.resolvers.StandardPlayerRequestResolver;
import com.minecolonies.core.gametest.BenchBridge;
import com.minecolonies.core.tileentities.TileEntityRack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One macro scenario run: a real colony in the GameTest server (real citizens with their AI, real couriers, builders,
 * crafters), a fixed seed, and a driver that calls the scenario once per game tick while a meter records what the request
 * system costs and what it achieves.
 * <p>
 * Per tick it measures: the server thread's CPU time for the whole tick (minus the benchmark's own bookkeeping), the part of
 * it spent inside the request system (from {@link RsProbe}), and allocated bytes. Per run it counts requests, deliveries,
 * courier tasks and walking distance, items delivered, player fallbacks, duplicate and stuck requests, and request
 * latency. Gates (item conservation, completion, no avoidable player fallback, no orphan reservation, save/load identity)
 * make the run fail when violated.
 */
public final class MacroRun
{
    /**
     * The scenario's side of a run.
     */
    public interface Scenario
    {
        /**
         * Builds the colony, buildings and workers. Called once, in the first tick.
         */
        void setup(MacroRun run);

        /**
         * Hires the workers. Called 200 ticks after {@link #setup}: citizens cannot spawn before the colony's chunks are ready.
         */
        default void staff(final MacroRun run)
        {
        }

        /**
         * Called when the measurement clock starts (tick 0): queue the work whose completion is measured.
         */
        default void begin(final MacroRun run)
        {
        }

        /**
         * Called every game tick after the colony ticked (tick 1 is the first one after setup).
         */
        void onTick(MacroRun run, long tick);

        /**
         * @return true once the work the scenario measures is finished (the run then drains and checks).
         */
        boolean done(MacroRun run, long tick);

        /**
         * Stop generating new demand; the run now drains.
         */
        default void stopDemand(final MacroRun run)
        {
        }

        /**
         * @return true if the scenario runs with the labourers ON (jobless citizens gather and craft what no building makes); every other scenario runs with labour off, so
         * its numbers stay what they were before the labourers existed.
         */
        default boolean labour()
        {
            return false;
        }

        /**
         * @return the tick budget after which the run fails.
         */
        int budget();

        /**
         * @return item descriptions (registry paths) the colony can never make: requests for them may fall back to the player.
         */
        default Set<String> unproducible()
        {
            return Set.of();
        }

        /**
         * @return the game tick at which the mid-run save/load identity check runs (0 = middle of the run once enough is open).
         */
        default int saveLoadCheckAt()
        {
            return 0;
        }
    }

    /**
     * A scripted gatherer: brings a batch of an item into the warehouse every period (the real gatherers' workers are not
     * simulated, their huts only exist so the colony can "still make" the item).
     */
    public record Supply(String name, ItemStack stack, int period, int phase, int maxTotal) { }

    public static final int STUCK_TICKS = 3000;

    // ------------------------------------------------------------------ state

    public final GameTestHelper helper;
    public final ServerLevel    level;
    public final String         testName;
    public final String         scenarioName;
    public final int            rep;
    public final boolean        warmup;
    public final Scenario       scenario;
    public final long           seed;

    public IColony                colony;
    public StandardRequestManager rm;
    public ServerPlayer           player;
    public final List<IBuilding>  warehouses = new ArrayList<>();
    public final Map<IBuilding, List<BlockPos>> rackPositions = new IdentityHashMap<>();
    public final List<ICitizenData> citizens = new ArrayList<>();
    public final List<Supply>     supplies = new ArrayList<>();
    private final Map<String, Integer> supplied = new HashMap<>();
    private int                   nextRack;

    /** Items scripted suppliers added and scripted sinks removed, in item units (see {@link ItemUnits}). */
    /** True while the scenario has the colony's chunks unloaded: nothing may touch the world then. */
    public boolean away;
    public int     cycles;
    private int    cures;
    public long injectedUnits;
    public long consumedUnits;
    /** Blocks the scenario expects in the world as the result of builds: position -> block. */
    public final Map<BlockPos, Block> sites = new HashMap<>();

    // meter
    private long[] tickCpu   = new long[4096];
    private long[] tickRs    = new long[4096];
    private long[] tickAlloc = new long[4096];
    private long[] tickRsAlloc = new long[4096];
    private int    samples;
    private long   lastMarkCpu;
    private long   lastMarkAlloc;
    private long   lastMarkRs;
    private long   lastMarkRsAlloc;
    private long   lastMarkOverhead;
    private long   harnessCpuPrev;
    private long   harnessAllocPrev;
    private long   lastGameTime;
    private long   rsOverheadTotal;
    private long   harnessCpuTotal;
    private long   startGameTime;

    // observers
    private final Map<Integer, Vec3> lastPos = new HashMap<>();
    private double courierDistance;
    private int    courierTeleports;
    /** root -> {fingerprint, tick of its last change, first seen, strict fingerprint, tick of its last change}; see {@link #scanRequests}. */
    private final Map<IToken<?>, long[]> rootFp = new HashMap<>();
    private final Set<IToken<?>> stuckRoots = new HashSet<>();
    /** The trees unchanged for {@link #STUCK_TICKS} by the strict fingerprint (the old definition: a flip between "couriers busy" and "awaiting delivery" counts as a change). */
    private final Set<IToken<?>> stuckRootsStrict = new HashSet<>();
    /** What every stuck tree looked like when it was first flagged (type, state, wait reason, resolver of each member): description -> count. */
    private final Map<String, Integer> stuckWhy = new java.util.TreeMap<>();
    /** The tick every stuck tree was first flagged at, bucketed per 1000 ticks (a measure of when the waiting happens). */
    private final Map<Long, Integer> stuckWhen = new java.util.TreeMap<>();
    /** Root -> tick it was flagged, to learn how long a flagged tree then still takes. */
    private final Map<IToken<?>, Long> stuckFlaggedAt = new HashMap<>();
    private final List<Long> stuckRemaining = new ArrayList<>();
    private final List<Long> latencies = new ArrayList<>();
    private long maxOpenAge;
    private long dupMax;
    private long dupSum;
    private int  dupScans;
    private int  openMax;
    private long openSum;
    private int  openScans;
    private int  fellBackSeen;
    private final List<String> fallbackItems = new ArrayList<>();
    private int    unexpectedFallbacks;
    private final List<String> errors = new ArrayList<>();
    /** Requests that never finished: a hang, which fails the run (no hang is tolerated). */
    private final List<String> liveness = new ArrayList<>();
    private final JsonObject   gates = new JsonObject();
    private final JsonObject   extra = new JsonObject();
    private boolean saveLoadDone;
    private java.util.Map<String, String> previousPlaces;
    private long previousTick;
    private long previousInjected;
    private long previousConsumed;
    private long    conservationChecks;
    private long    conservationMax;

    // phases
    private boolean draining;
    private long    drainStart;
    private long    doneTick = -1;
    private long    quiesceTick = -1;
    private long    requestsAtDone;
    private long    deliveriesAtDone;
    private long    tasksAtDone;
    private double  distanceAtDone;
    private long    deliveredItemsAtDone;

    private final java.util.function.Consumer<String> done;

    /**
     * @param sharedPlayer the mock player of the enclosing test (created on the first run, reused by the following ones), may be null
     * @param done         called once with null when the run passed all gates, else with the failure
     */
    public MacroRun(final GameTestHelper helper, final String testName, final String scenarioName, final int rep, final boolean warmup, final Scenario scenario,
                    final ServerPlayer sharedPlayer, final java.util.function.Consumer<String> done)
    {
        this.player = sharedPlayer;
        this.done = done;
        this.helper = helper;
        this.level = helper.getLevel();
        this.testName = testName;
        this.scenarioName = scenarioName;
        this.rep = rep;
        this.warmup = warmup;
        this.scenario = scenario;
        this.seed = BenchEnv.seed();
    }

    // ------------------------------------------------------------------ driver

    public void start()
    {
        final boolean flags = BenchEnv.flagsOn();
        RsFlags.overrideReservations(flags);
        RsFlags.overrideSmartRetry(flags);
        RsStats.reset();
        StandardToken.seedForBenchmark(seed);
        // labour is off in every scenario but the ones that exercise it (or the inert gate), so the old scenarios measure what they always measured
        final boolean labourOn = scenario.labour() || BenchEnv.labourInert();
        com.minecolonies.core.colony.labour.LabourSettings.override("worldEnabled", labourOn);
        if (labourOn && !scenario.labour())
        {
            // the inert gate: the scenarios' tool requests are scripted goods nobody can make (their supplier is a script); stone tools are not the basic tier here
            com.minecolonies.core.colony.labour.LabourSettings.override("stoneTools", false);
        }
        com.minecolonies.core.colony.labour.LabourStats.reset();
        com.minecolonies.core.colony.labour.LabourStats.gatherProbe = labourOn ? stack -> injectedUnits += ItemUnits.units(stack) : null;
        if (BenchEnv.pinWorkers())
        {
            // 1.0 speed and real walking: the pace the request system is judged at does not move with the worker-efficiency settings
            com.minecolonies.core.entity.ai.workers.util.WorkerSettings.overrideForTests(1.0D, 1.0D, null, com.minecolonies.api.colony.WorkerWalking.OFF, null, null);
        }
        final double[] pair = RsProbe.calibrate(200_000);
        extra.addProperty("probePairCoarseNs", pair[0]);
        extra.addProperty("probePairFineNs", pair[1]);
        try
        {
            scenario.setup(this);
        }
        catch (final Throwable t)
        {
            abort("setup failed: " + t, t);
            return;
        }
        rm = (StandardRequestManager) colony.getRequestManager();
        helper.runAfterDelay(200, () -> {
            try
            {
                scenario.staff(this);
            }
            catch (final Throwable t)
            {
                abort("staffing failed: " + t, t);
                return;
            }
            if (BenchEnv.labourInert())
            {
                // the inert gate: six jobless citizens, and still nothing may be claimed (a building covers every good the scenario asks for)
                for (int i = 0; i < 6; i++)
                {
                    final ICitizenData jobless = BenchBridge.spawnCitizen(helper, colony, new BlockPos(Scenarios.TOWN_HALL.getX() - 6 + 2 * i, 1, Scenarios.TOWN_HALL.getZ() - 8), false);
                    for (final com.minecolonies.api.entity.citizen.Skill skill : com.minecolonies.api.entity.citizen.Skill.values())
                    {
                        final var data = jobless.getCitizenSkillHandler().getSkills().get(skill);
                        data.setLevel(8);
                        data.setExperience(0);
                    }
                    citizens.add(jobless);
                }
            }
            // Let the new workers settle before the clock starts, so every run starts from the same state.
            helper.runAfterDelay(100, () -> {
                try
                {
                    // whatever an earlier run left lying in the plot is not ours
                    for (final ItemEntity stray : level.getEntitiesOfClass(ItemEntity.class, plotBox()))
                    {
                        stray.discard();
                    }
                    // what the workers already carry (they may have picked up something an earlier repeat left) is the starting stock
                    injectedUnits = ItemUnits.unitsInWorld(this);
                    scenario.begin(this);
                }
                catch (final Throwable t)
                {
                    abort("begin failed: " + t, t);
                    return;
                }
                RsStats.reset();
                RsProbe.start();
                startGameTime = level.getGameTime();
                lastGameTime = startGameTime;
                lastMarkCpu = ThreadCost.cpuNs();
                lastMarkAlloc = ThreadCost.allocBytes();
                lastMarkRs = RsProbe.totalCpuNs();
                lastMarkRsAlloc = RsProbe.totalAllocBytes();
                lastMarkOverhead = RsProbe.overheadNs();
                helper.runAfterDelay(1, this::tickLoop);
            });
        });
    }

    private void abort(final String message, final Throwable t)
    {
        com.minecolonies.core.entity.ai.workers.util.WorkerSettings.clearOverrides();
        com.minecolonies.core.colony.labour.LabourSettings.clearOverrides();
        com.minecolonies.core.colony.labour.LabourStats.gatherProbe = null;
        RsProbe.stop();
        StandardToken.seedForBenchmark(null);
        if (t != null)
        {
            t.printStackTrace();
        }
        done.accept("macro bench " + testName + ": " + message);
    }

    private void tickLoop()
    {
        try
        {
            if (!oneTick())
            {
                return;
            }
        }
        catch (final Throwable t)
        {
            abort("tick failed: " + t, t);
            return;
        }
        helper.runAfterDelay(1, this::tickLoop);
    }

    /**
     * @return false when the run has ended.
     */
    /**
     * Pathfinding runs on worker threads and its results are picked up whenever they are ready, so under machine load the
     * same colony would see its paths a tick or two later than on an idle machine. Waiting (parked, so no CPU time is
     * charged to the server thread) until the workers are idle before each tick makes every path arrive at the same tick.
     */
    private void awaitPathing()
    {
        final java.util.concurrent.ThreadPoolExecutor pool = com.minecolonies.core.entity.pathfinding.Pathfinding.getExecutor();
        final long deadline = System.nanoTime() + 200_000_000L;
        while ((!pool.getQueue().isEmpty() || pool.getActiveCount() > 0) && System.nanoTime() < deadline)
        {
            java.util.concurrent.locks.LockSupport.parkNanos(20_000L);
        }
    }

    private boolean oneTick()
    {
        awaitPathing();
        final long cpuNow = ThreadCost.cpuNs();
        final long allocNow = ThreadCost.allocBytes();
        final long rsNow = RsProbe.totalCpuNs();
        final long rsAllocNow = RsProbe.totalAllocBytes();
        final long overheadNow = RsProbe.overheadNs();
        final long gameTime = level.getGameTime();
        final long tick = gameTime - startGameTime;
        final long elapsed = Math.max(1, gameTime - lastGameTime);
        // The window since the last call: whole server ticks, minus the bookkeeping this class did in its last call.
        final long cpu = Math.max(0, cpuNow - lastMarkCpu - harnessCpuPrev);
        final long alloc = Math.max(0, allocNow - lastMarkAlloc - harnessAllocPrev);
        final long overhead = Math.max(0, overheadNow - lastMarkOverhead);
        final long rs = Math.max(0, rsNow - lastMarkRs - overhead);
        final long rsAlloc = Math.max(0, rsAllocNow - lastMarkRsAlloc);
        record(cpu / elapsed, rs / elapsed, alloc / elapsed, rsAlloc / elapsed, elapsed);
        rsOverheadTotal += overhead;
        lastGameTime = gameTime;

        // Next window starts here (the previous window ended at this call's first lines).
        lastMarkCpu = cpuNow;
        lastMarkAlloc = allocNow;
        lastMarkRs = rsNow;
        lastMarkRsAlloc = rsAllocNow;
        lastMarkOverhead = overheadNow;

        final boolean wasEnabled = RsProbe.enabled;
        boolean running = true;
        ThreadCost.Mark harness = null;
        try
        {
            housekeeping(tick);
            // scripted suppliers and the scenario's own drivers use the request system the way workers do, so they stay measured
            supply(tick);
            scenario.onTick(this, tick);
            // from here on it is the benchmark's own bookkeeping: not part of the server tick, and the probe is off
            harness = ThreadCost.mark();
            RsProbe.enabled = false;
            observe(tick);
            RsProbe.enabled = wasEnabled;
            running = progress(tick);
        }
        finally
        {
            RsProbe.enabled = wasEnabled;
        }
        harnessCpuPrev = harness == null ? 0 : harness.cpuNs();
        harnessAllocPrev = harness == null ? 0 : harness.allocBytes();
        harnessCpuTotal += harnessCpuPrev;
        return running;
    }

    private void record(final long cpu, final long rs, final long alloc, final long rsAlloc, final long elapsed)
    {
        for (long i = 0; i < Math.min(elapsed, 4); i++)
        {
            if (samples == tickCpu.length)
            {
                tickCpu = java.util.Arrays.copyOf(tickCpu, samples * 2);
                tickRs = java.util.Arrays.copyOf(tickRs, samples * 2);
                tickAlloc = java.util.Arrays.copyOf(tickAlloc, samples * 2);
                tickRsAlloc = java.util.Arrays.copyOf(tickRsAlloc, samples * 2);
            }
            tickCpu[samples] = cpu;
            tickRs[samples] = rs;
            tickAlloc[samples] = alloc;
            tickRsAlloc[samples] = rsAlloc;
            samples++;
        }
    }

    // ------------------------------------------------------------------ per-tick work

    private void housekeeping(final long tick)
    {
        if (tick % 20 == 0)
        {
            // Midday, always: citizens stop working at night and in the rain, which would only add noise.
            level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
            // ... and never rain: couriers do not work in the rain, and a long run reaches the first rain by chance
            final var weather = level.getWeatherData();
            final boolean rain = BenchEnv.breaks() && tick % 3000 < 300;
            weather.setRaining(rain);
            weather.setThundering(false);
            weather.setClearWeatherTime(rain ? 0 : 1_000_000);
        }
        if (tick % 100 == 0)
        {
            for (final ICitizenData citizen : citizens)
            {
                citizen.setSaturation(20.0D);
                // a sick citizen walks off to a hospital that does not exist and stops working for good
                if (citizen.getCitizenDiseaseHandler().isSick())
                {
                    citizen.getCitizenDiseaseHandler().cure();
                    cures++;
                }
            }
        }
    }

    private void supply(final long tick)
    {
        if (draining || away)
        {
            return;
        }
        for (final Supply s : supplies)
        {
            if (tick >= s.phase() && (tick - s.phase()) % s.period() == 0)
            {
                final int already = supplied.getOrDefault(s.name(), 0);
                if (s.maxTotal() > 0 && already >= s.maxTotal())
                {
                    continue;
                }
                final int give = s.maxTotal() > 0 ? Math.min(s.stack().getCount(), s.maxTotal() - already) : s.stack().getCount();
                final int in = stockWarehouse(s.stack().copyWithCount(give));
                supplied.merge(s.name(), in, Integer::sum);
            }
        }
    }

    /**
     * Puts items into the warehouse racks the way a courier dumping a gatherer's haul would (item handler insert, so every
     * rack hook fires). Counts what went in as injected.
     *
     * @return how many items went in.
     */
    public int stockWarehouse(final ItemStack stack)
    {
        final List<BlockPos> racks = new ArrayList<>();
        for (final IBuilding wh : warehouses)
        {
            racks.addAll(rackPositions.getOrDefault(wh, List.of()));
        }
        ItemStack rest = stack.copy();
        final int total = stack.getCount();
        for (int attempt = 0; attempt < racks.size() && !rest.isEmpty(); attempt++)
        {
            final BlockEntity entity = level.getBlockEntity(racks.get((nextRack + attempt) % racks.size()));
            if (entity instanceof TileEntityRack rack)
            {
                for (int slot = 0; slot < rack.getInventory().getSlots() && !rest.isEmpty(); slot++)
                {
                    rest = rack.getInventory().insertItem(slot, rest, false);
                }
            }
        }
        nextRack++;
        final int in = total - rest.getCount();
        injectedUnits += (long) ItemUnits.perItem(stack) * in;
        return in;
    }

    /**
     * Takes items out of a building's racks and counts them as consumed (a scripted sink: the cook eats, the miner wears a tool out).
     *
     * @return how many items were taken.
     */
    public int sink(final IBuilding building, final java.util.function.Predicate<ItemStack> match, final int max)
    {
        int taken = 0;
        for (final BlockPos pos : building.getContainers())
        {
            if (taken >= max)
            {
                break;
            }
            if (level.getBlockEntity(pos) instanceof TileEntityRack rack)
            {
                for (int slot = 0; slot < rack.getInventory().getSlots() && taken < max; slot++)
                {
                    final ItemStack in = rack.getInventory().getStackInSlot(slot);
                    if (!in.isEmpty() && match.test(in))
                    {
                        final ItemStack out = rack.getInventory().extractItem(slot, max - taken, false);
                        taken += out.getCount();
                        consumedUnits += (long) ItemUnits.perItem(out) * out.getCount();
                    }
                }
            }
        }
        return taken;
    }

    // ------------------------------------------------------------------ observers

    private void observe(final long tick)
    {
        // courier walking, every tick
        for (final ICitizenData citizen : citizens)
        {
            if (!(citizen.getJob() instanceof JobDeliveryman) || citizen.getEntity().isEmpty())
            {
                continue;
            }
            final Vec3 now = citizen.getEntity().get().position();
            final Vec3 before = lastPos.put(citizen.getId(), now);
            if (before != null)
            {
                final double d = before.distanceTo(now);
                if (d < 6.0D)
                {
                    courierDistance += d;
                }
                else
                {
                    courierTeleports++;
                }
            }
        }
        if (tick % 10 == 0)
        {
            scanRequests(tick);
        }
        if (tick % 2000 == 0)
        {
            com.minecolonies.api.util.Log.getLogger().info("RSB {} t={} open={} pending={} created={} deliveries={} tasks={} delivered={} fallbacks={} dist={}", testName, tick,
              openRequestCount(), pendingRequests(), RsStats.requestsCreated, RsStats.deliveriesCreated, RsStats.courierTasksFinished, RsStats.itemsDelivered, RsStats.playerFallbacks,
              (long) courierDistance);
        }
        if (tick % 20 == 0 && !away)
        {
            checkConservation(tick, false);
        }
        if (!saveLoadDone && !draining && !away)
        {
            final int at = scenario.saveLoadCheckAt();
            if (at > 0 ? tick >= at : tick >= 600 && openRequestCount() >= 3 && tick % 100 == 0)
            {
                saveLoadDone = true;
                checkSaveLoad("midrun@" + tick);
            }
        }
    }

    private int openRequestCount()
    {
        return rm.getRequestIdentitiesDataStore().getIdentities().size();
    }

    private void scanRequests(final long tick)
    {
        final Map<IToken<?>, IRequest<?>> all = rm.getRequestIdentitiesDataStore().getIdentities();
        final Map<IToken<?>, long[]> seen = new HashMap<>();
        final Map<IToken<?>, List<IRequest<?>>> members = new HashMap<>();
        final Map<String, Integer> keys = new HashMap<>();
        int open = 0;
        for (final IRequest<?> request : all.values())
        {
            open++;
            IToken<?> root = request.getId();
            IRequest<?> cursor = request;
            int guard = 0;
            while (cursor.hasParent() && guard++ < 16)
            {
                final IRequest<?> parent = rm.getRequestForToken(cursor.getParent());
                if (parent == null)
                {
                    break;
                }
                cursor = parent;
                root = parent.getId();
            }
            final IToken<?> resolver = rm.getRequestResolverRequestAssignmentDataStore().getAssignmentForValue(request.getId());
            // A tree "changes" when a member's state, resolver or number of children does. The wait reason of a queued delivery flips between COURIER_BUSY (every
            // courier holds a task) and AWAITING_DELIVERY (one is between two tasks) with the courier's rhythm, although the delivery has not moved in its queue:
            // that flip is not progress, so the fingerprint treats the two as one reason. The strict fingerprint keeps them apart (the definition until now).
            final WaitReason reason = request.getWaitReason() == WaitReason.COURIER_BUSY ? WaitReason.AWAITING_DELIVERY : request.getWaitReason();
            final long base = request.getState().ordinal() * 31L * 131L + request.getChildren().size() * 17L + (resolver == null ? 0 : resolver.hashCode());
            final long fp = base + reason.ordinal() * 131L;
            final long fpStrict = base + request.getWaitReason().ordinal() * 131L;
            final long[] sums = seen.computeIfAbsent(root, r -> new long[2]);
            sums[0] += fp * 1_000_003L + request.getId().hashCode();
            sums[1] += fpStrict * 1_000_003L + request.getId().hashCode();
            members.computeIfAbsent(root, r -> new ArrayList<>()).add(request);
            if (!request.hasParent() && request.getRequest() instanceof IDeliverable deliverable)
            {
                String key = null;
                if (request.getRequest() instanceof Stack stack)
                {
                    key = stack.getStack().getItem().toString();
                }
                if (key != null)
                {
                    keys.merge(request.getRequester().getId().getIdentifier() + "|" + key, 1, Integer::sum);
                }
            }
        }
        long dups = 0;
        for (final int n : keys.values())
        {
            dups += Math.max(0, n - 1);
        }
        dupMax = Math.max(dupMax, dups);
        dupSum += dups;
        dupScans++;
        openMax = Math.max(openMax, open);
        openSum += open;
        openScans++;

        for (final Map.Entry<IToken<?>, long[]> e : seen.entrySet())
        {
            final long[] known = rootFp.get(e.getKey());
            if (known == null)
            {
                rootFp.put(e.getKey(), new long[] {e.getValue()[0], tick, tick, e.getValue()[1], tick});
            }
            else
            {
                if (known[0] != e.getValue()[0])
                {
                    known[0] = e.getValue()[0];
                    known[1] = tick;
                }
                if (known[3] != e.getValue()[1])
                {
                    known[3] = e.getValue()[1];
                    known[4] = tick;
                }
                else if (tick - known[4] >= STUCK_TICKS)
                {
                    stuckRootsStrict.add(e.getKey());
                }
                if (tick - known[1] >= STUCK_TICKS && known[0] == e.getValue()[0])
                {
                    if (stuckRoots.add(e.getKey()))
                    {
                        stuckFlaggedAt.put(e.getKey(), tick);
                        stuckWhen.merge(tick / 1000, 1, Integer::sum);
                        stuckWhy.merge(describeTree(members.get(e.getKey())), 1, Integer::sum);
                    }
                }
                maxOpenAge = Math.max(maxOpenAge, tick - known[2]);
            }
        }
        for (final java.util.Iterator<Map.Entry<IToken<?>, long[]>> it = rootFp.entrySet().iterator(); it.hasNext(); )
        {
            final Map.Entry<IToken<?>, long[]> e = it.next();
            if (!seen.containsKey(e.getKey()))
            {
                latencies.add(tick - e.getValue()[2]);
                final Long flagged = stuckFlaggedAt.remove(e.getKey());
                if (flagged != null)
                {
                    stuckRemaining.add(tick - flagged);
                }
                it.remove();
            }
        }

        // new player fallbacks
        final List<IToken<?>> fellBack = RsStats.fellBack();
        for (int i = fellBackSeen; i < fellBack.size(); i++)
        {
            final IRequest<?> request = rm.getRequestForToken(fellBack.get(i));
            String what = "?";
            if (request != null && request.getRequest() instanceof Stack stack)
            {
                what = BuiltInRegistries.ITEM.getKey(stack.getStack().getItem()).getPath();
            }
            else if (request != null)
            {
                what = request.getRequest().getClass().getSimpleName();
            }
            fallbackItems.add(what);
            if (!scenario.unproducible().contains(what))
            {
                unexpectedFallbacks++;
            }
        }
        fellBackSeen = fellBack.size();
    }

    /**
     * A sorted, short description of a request tree (type, state, wait reason and holder of every member), the key of the "why were these trees stuck" histogram.
     */
    private String describeTree(final List<IRequest<?>> tree)
    {
        if (tree == null)
        {
            return "?";
        }
        final List<String> parts = new ArrayList<>();
        for (final IRequest<?> request : tree)
        {
            String holder = "none";
            final IToken<?> resolver = rm.getRequestResolverRequestAssignmentDataStore().getAssignmentForValue(request.getId());
            if (resolver != null)
            {
                try
                {
                    holder = rm.getResolverHandler().getResolver(resolver).getClass().getSimpleName();
                }
                catch (final RuntimeException ex)
                {
                    holder = "?";
                }
            }
            parts.add((request.hasParent() ? "child " : "root ") + request.getRequest().getClass().getSimpleName() + " " + request.getState() + "/" + request.getWaitReason() + " @" + holder);
        }
        java.util.Collections.sort(parts);
        return String.join(" | ", parts.size() > 6 ? parts.subList(0, 6) : parts) + (parts.size() > 6 ? " +" + (parts.size() - 6) : "");
    }

    // ------------------------------------------------------------------ gates

    /**
     * Item conservation: the item units found in the world equal what scripted suppliers added minus what scripted sinks took.
     */
    private void checkConservation(final long tick, final boolean last)
    {
        final long found = ItemUnits.unitsInWorld(this);
        final long expected = injectedUnits - consumedUnits;
        conservationChecks++;
        final long drift = found - expected;
        if (drift != 0 && previousPlaces != null)
        {
            // what moved since the last check that passed: names the place the surplus or deficit appeared at
            final java.util.Map<String, String> now = ItemUnits.places(this);
            final StringBuilder moved = new StringBuilder();
            int shown = 0;
            for (final java.util.Map.Entry<String, String> e : now.entrySet())
            {
                if (!e.getValue().equals(previousPlaces.get(e.getKey())) && shown++ < 14)
                {
                    moved.append(" +").append(e.getKey()).append('=').append(e.getValue()).append(" (was ").append(previousPlaces.get(e.getKey())).append(");");
                }
            }
            for (final java.util.Map.Entry<String, String> e : previousPlaces.entrySet())
            {
                if (!now.containsKey(e.getKey()) && shown++ < 14)
                {
                    moved.append(" -").append(e.getKey()).append("=").append(e.getValue()).append(";");
                }
            }
            if (errors.size() < 3)
            {
                errors.add("moved since the last good check at tick " + previousTick + " (injected " + (injectedUnits - previousInjected) + " units, consumed " + (consumedUnits - previousConsumed) + "):" + moved);
            }
        }
        if (drift == 0)
        {
            previousPlaces = ItemUnits.places(this);
            previousTick = tick;
            previousInjected = injectedUnits;
            previousConsumed = consumedUnits;
        }
        conservationMax = Math.max(conservationMax, Math.abs(drift));
        if (drift != 0 && errors.size() < 6)
        {
            final String where = errors.isEmpty() ? " [" + ItemUnits.breakdown(this) + " ]" : "";
            errors.add("item conservation violated at tick " + tick + (last ? " (final)" : "") + ": world holds " + found + " units, suppliers - sinks = " + expected + " (drift " + drift
                         + (drift > 0 ? ": items appeared" : ": items vanished") + ")" + where);
        }
    }

    private void checkSaveLoad(final String when)
    {
        final net.minecraft.core.RegistryAccess ra = level.registryAccess();
        final net.minecraft.nbt.CompoundTag saved = rm.serializeNBT(ra);
        final StandardRequestManager loaded = new StandardRequestManager(colony);
        loaded.deserializeNBT(ra, saved);
        final RsDigest before = RsDigest.of(rm, ra);
        final RsDigest after = RsDigest.of(loaded, ra);
        final String diff = before.diff(after);
        extra.addProperty("saveLoadRequests@" + when, before.requests.size());
        if (diff != null)
        {
            errors.add("save/load not identical (" + when + "): " + diff);
        }
        gates.addProperty("saveLoadIdentical", diff == null && !(gates.has("saveLoadIdentical") && !gates.get("saveLoadIdentical").getAsBoolean()));
    }

    private int ledgerOrphans()
    {
        int orphans = 0;
        for (final Reservation r : rm.getReservationLedger().all())
        {
            final IRequest<?> request = rm.getRequestIdentitiesDataStore().getIdentities().get(r.token());
            if (request == null)
            {
                orphans++;
            }
            else
            {
                switch (request.getState())
                {
                    case CANCELLED, FAILED, RECEIVED, OVERRULED -> orphans++;
                    default ->
                    {
                    }
                }
            }
        }
        return orphans;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * @return requests still open that are not a player-held request for something unproducible.
     */
    public int pendingRequests()
    {
        int n = 0;
        final StandardPlayerRequestResolver player = (StandardPlayerRequestResolver) rm.getPlayerResolver();
        for (final IRequest<?> request : rm.getRequestIdentitiesDataStore().getIdentities().values())
        {
            if (request.getState() == com.minecolonies.api.colony.requestsystem.request.RequestState.COMPLETED
                  || request.getState() == com.minecolonies.api.colony.requestsystem.request.RequestState.RECEIVED)
            {
                // finished from the request system's side; the requester has only not picked the result up yet
                continue;
            }
            if (player.isHolding(request.getId()) && request.getRequest() instanceof Stack stack
                  && scenario.unproducible().contains(BuiltInRegistries.ITEM.getKey(stack.getStack().getItem()).getPath()))
            {
                continue;
            }
            n++;
        }
        return n;
    }

    /**
     * @return a short description of the open requests (state, wait reason, item, requester), for failure messages.
     */
    public String describeOpen(final int max)
    {
        final StringBuilder out = new StringBuilder();
        int n = 0;
        for (final IRequest<?> request : rm.getRequestIdentitiesDataStore().getIdentities().values())
        {
            if (n++ >= max)
            {
                out.append(" ...");
                break;
            }
            String what = request.getRequest().getClass().getSimpleName();
            if (request.getRequest() instanceof Stack stack)
            {
                what += ":" + BuiltInRegistries.ITEM.getKey(stack.getStack().getItem()).getPath() + "x" + stack.getCount();
            }
            else if (request.getRequest() instanceof com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery delivery)
            {
                what += ":" + BuiltInRegistries.ITEM.getKey(delivery.getStack().getItem()).getPath() + "x" + delivery.getStack().getCount();
            }
            IToken<?> resolver = rm.getRequestResolverRequestAssignmentDataStore().getAssignmentForValue(request.getId());
            String holder = "none";
            if (resolver != null)
            {
                try
                {
                    holder = rm.getResolverHandler().getResolver(resolver).getClass().getSimpleName();
                }
                catch (final RuntimeException e)
                {
                    holder = "?";
                }
            }
            out.append("\n  [").append(what).append(' ').append(request.getState()).append('/').append(request.getWaitReason()).append(" at ").append(holder)
              .append(" parent=").append(request.hasParent()).append(" children=").append(request.getChildren().size()).append(']');
        }
        return out.toString();
    }

    private final StringBuilder courierTrail = new StringBuilder();
    private int courierTrailLines;

    /**
     * Remembers where the couriers that hold a task are and what they do; a request that never drains is then explained by a trail, not a snapshot.
     */
    private void recordCourierTrail(final long tick)
    {
        if (courierTrailLines > 90)
        {
            return;
        }
        for (final ICitizenData citizen : citizens)
        {
            if (citizen.getJob() instanceof JobDeliveryman job && !job.getTaskQueue().isEmpty() && citizen.getEntity().isPresent())
            {
                final var e = citizen.getEntity().get();
                courierTrail.append("\n    t=").append(tick - drainStart).append(" #").append(citizen.getId()).append(" at ").append(e.blockPosition().toShortString()).append(" ai=")
                  .append(job.getWorkerAI() == null ? "-" : job.getWorkerAI().getState()).append(" navDone=").append(e.getNavigation().isDone());
                if (e.getNavigation() instanceof com.minecolonies.core.entity.pathfinding.navigation.AbstractAdvancedPathNavigate nav && nav.getPathResult() != null)
                {
                    final var pr = nav.getPathResult();
                    out_path(courierTrail, pr);
                }
                courierTrailLines++;
            }
        }
    }

    /**
     * What the couriers did while some request tree stood still: sampled every 50 ticks once a tree went unchanged for 1500 ticks (half of what counts as stuck),
     * so a courier that recovers by itself after 5000 ticks leaves the same evidence as one that never does.
     */
    private final StringBuilder stallTrail = new StringBuilder();
    private int stallTrailLines;
    private final Map<Integer, BlockPos> stallLastPos = new HashMap<>();
    private static final int STALL_SAMPLE_TICKS = 1500;

    private void recordStallTrail(final long tick)
    {
        if (stallTrailLines > 1200 || tick % 50 != 0)
        {
            return;
        }
        int stalled = 0;
        long oldest = 0;
        for (final long[] known : rootFp.values())
        {
            if (tick - known[1] >= STALL_SAMPLE_TICKS)
            {
                stalled++;
                oldest = Math.max(oldest, tick - known[1]);
            }
        }
        if (stalled == 0)
        {
            return;
        }
        stallTrail.append("\n  t=").append(tick).append(" stalledRoots=").append(stalled).append(" oldestUnchanged=").append(oldest);
        for (final ICitizenData citizen : citizens)
        {
            if (citizen.getJob() instanceof JobDeliveryman job && citizen.getEntity().isPresent())
            {
                final var e = citizen.getEntity().get();
                final BlockPos here = e.blockPosition();
                final BlockPos before = stallLastPos.put(citizen.getId(), here);
                stallTrail.append("\n    #").append(citizen.getId()).append(" at ").append(here.toShortString()).append(" moved=").append(before == null ? "?" : (int) Math.sqrt(here.distSqr(before)))
                  .append(" queue=").append(job.getTaskQueue().size()).append(" ai=").append(job.getWorkerAI() == null ? "-" : job.getWorkerAI().getState()).append(" navDone=").append(e.getNavigation().isDone());
                if (!job.getTaskQueue().isEmpty())
                {
                    final IRequest<?> task = rm.getRequestForToken(job.getTaskQueue().get(0));
                    if (task != null && task.getRequest() instanceof com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery delivery)
                    {
                        final BlockPos target = delivery.getTarget().getInDimensionLocation();
                        final BlockPos start = delivery.getStart().getInDimensionLocation();
                        stallTrail.append(" task=").append(BuiltInRegistries.ITEM.getKey(delivery.getStack().getItem()).getPath()).append('x').append(delivery.getStack().getCount()).append(' ').append(task.getState())
                          .append(" start=").append(start.toShortString()).append(" distToStart=").append((int) Math.sqrt(here.distSqr(start))).append(" target=").append(target.toShortString())
                          .append(" distToTarget=").append((int) Math.sqrt(here.distSqr(target)));
                    }
                }
                if (e.getNavigation() instanceof com.minecolonies.core.entity.pathfinding.navigation.AbstractAdvancedPathNavigate nav && nav.getPathResult() != null)
                {
                    final var pr = nav.getPathResult();
                    out_path(stallTrail, pr);
                    stallTrail.append(" reaches=").append(pr.isPathReachingDestination()).append(" job=").append(pr.getJob() == null ? "-" : pr.getJob().getClass().getSimpleName());
                }
                stallTrailLines++;
            }
        }
    }

    private static void out_path(final StringBuilder out, final com.minecolonies.core.entity.pathfinding.pathresults.PathResult<?> pr)
    {
        out.append(" path=").append(pr.getStatus()).append('/').append(pr.hasPath() ? pr.getPathLength() : -1);
    }

    /**
     * @return what the couriers are doing, for failure messages.
     */
    private String describeCouriers()
    {
        final StringBuilder out = new StringBuilder("\n  couriers:");
        for (final ICitizenData citizen : citizens)
        {
            if (citizen.getJob() instanceof JobDeliveryman job)
            {
                out.append(" [#").append(citizen.getId()).append(" entity=").append(citizen.getEntity().isPresent()).append(" queue=").append(job.getTaskQueue().size());
                if (!job.getTaskQueue().isEmpty())
                {
                    final IRequest<?> task = rm.getRequestForToken(job.getTaskQueue().get(0));
                    if (task != null && task.getRequest() instanceof com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery delivery)
                    {
                        final BlockPos target = delivery.getTarget().getInDimensionLocation();
                        out.append(" task=").append(BuiltInRegistries.ITEM.getKey(delivery.getStack().getItem()).getPath()).append('x').append(delivery.getStack().getCount())
                          .append(" ").append(task.getState()).append(" to ").append(target.toShortString());
                        citizen.getEntity().ifPresent(e -> {
                            out.append(" dist=").append((int) Math.sqrt(e.blockPosition().distSqr(target))).append(" navDone=").append(e.getNavigation().isDone());
                            final BlockPos start = delivery.getStart().getInDimensionLocation();
                            out.append(" start=").append(start.toShortString()).append(" distToStart=").append((int) Math.sqrt(e.blockPosition().distSqr(start)));
                            if (e.getNavigation() instanceof com.minecolonies.core.entity.pathfinding.navigation.AbstractAdvancedPathNavigate nav && nav.getPathResult() != null)
                            {
                                final var pr = nav.getPathResult();
                                out.append(" pathStatus=").append(pr.getStatus()).append(" pathLen=").append(pr.hasPath() ? pr.getPathLength() : -1).append(" reaches=").append(pr.isPathReachingDestination())
                                  .append(" job=").append(pr.getJob() == null ? "-" : pr.getJob().getClass().getSimpleName());
                            }
                        });
                    }
                    else
                    {
                        out.append(" task=").append(task == null ? "gone" : task.getRequest().getClass().getSimpleName());
                    }
                }
                citizen.getEntity().ifPresent(e -> out.append(" at ").append(e.blockPosition().toShortString()).append(" ai=").append(job.getWorkerAI() == null ? "-" : job.getWorkerAI().getState())
                  .append(" citizenAi=").append(e instanceof com.minecolonies.core.entity.citizen.EntityCitizen ec ? ec.getCitizenAI().getState() : "-").append(" paused=").append(citizen.isPaused()).append(" sick=")
                  .append(citizen.getCitizenDiseaseHandler().isSick()).append(" sat=").append((int) citizen.getSaturation()).append(" raining=").append(level.isRaining()));
                out.append(']');
            }
        }
        out.append("\n  workers:");
        for (final ICitizenData citizen : citizens)
        {
            if (citizen.getJob() != null && !(citizen.getJob() instanceof JobDeliveryman))
            {
                out.append(" [#").append(citizen.getId()).append(' ').append(citizen.getJob().getClass().getSimpleName()).append(" entity=").append(citizen.getEntity().isPresent());
                if (citizen.getJob() instanceof com.minecolonies.core.colony.jobs.AbstractJobCrafter<?, ?> crafter)
                {
                    out.append(" craftQueue=").append(crafter.getTaskQueue().size());
                }
                citizen.getEntity().ifPresent(e -> out.append(" ai=").append(citizen.getJob().getWorkerAI() == null ? "-" : citizen.getJob().getWorkerAI().getState()).append(" at ").append(e.blockPosition().toShortString()));
                out.append(']');
            }
        }
        out.append("\n  recipes:");
        for (final IBuilding b : colony.getServerBuildingManager().getBuildings().values())
        {
            for (final com.minecolonies.api.colony.buildings.modules.ICraftingBuildingModule module : b.getModulesByType(com.minecolonies.api.colony.buildings.modules.ICraftingBuildingModule.class))
            {
                out.append(" [").append(b.getSchematicName()).append(" level=").append(b.getBuildingLevel()).append(':');
                for (final IToken<?> token : module.getRecipes())
                {
                    final var storage = com.minecolonies.api.colony.IColonyManager.getInstance().getRecipeManager().getRecipes().get(token);
                    out.append(' ').append(storage == null ? "MISSING-IN-GLOBAL-MANAGER" : BuiltInRegistries.ITEM.getKey(storage.getPrimaryOutput().getItem()).getPath());
                }
                out.append(']');
            }
        }
        out.append("\n  waitDiagnosis:");
        int shown = 0;
        for (final IRequest<?> request : rm.getRequestIdentitiesDataStore().getIdentities().values())
        {
            if (request.getRequest() instanceof Stack stack && request.getState() == com.minecolonies.api.colony.requestsystem.request.RequestState.IN_PROGRESS && !request.hasChildren() && shown++ < 8)
            {
                out.append(" [").append(BuiltInRegistries.ITEM.getKey(stack.getStack().getItem()).getPath()).append(" recipeSupport=")
                  .append(com.minecolonies.core.colony.requestsystem.wait.WaitDiagnosis.recipeSupport((com.minecolonies.core.colony.Colony) colony, stack))
                  .append(" physicalStock=").append(com.minecolonies.core.colony.requestsystem.wait.WaitDiagnosis.physicalStock((com.minecolonies.core.colony.Colony) colony, stack))
                  .append(" reason=").append(request.getWaitReason()).append(']');
            }
        }
        final int[] ghosts = {0, 0};
        for (final net.minecraft.world.entity.Entity e : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, plotBox()))
        {
            if (e instanceof com.minecolonies.core.entity.citizen.EntityCitizen citizen)
            {
                ghosts[0]++;
                if (citizen.getCitizenData() == null || citizens.stream().noneMatch(c -> c.getEntity().orElse(null) == citizen))
                {
                    ghosts[1]++;
                }
            }
        }
        out.append(" citizenEntitiesInPlot=").append(ghosts[0]).append(" notOurs=").append(ghosts[1]).append(" pathing[queued=").append(com.minecolonies.core.entity.pathfinding.Pathfinding.getExecutor().getQueue().size())
          .append(" running=").append(com.minecolonies.core.entity.pathfinding.Pathfinding.Stats.running.get()).append(" submitted=").append(com.minecolonies.core.entity.pathfinding.Pathfinding.Stats.submitted.get())
          .append(" rejected=").append(com.minecolonies.core.entity.pathfinding.Pathfinding.Stats.rejected.get()).append(']');
        final StringBuilder queues = new StringBuilder();
        for (final IBuilding wh : warehouses)
        {
            final var module = wh.getModule(com.minecolonies.core.colony.buildings.modules.BuildingModules.WAREHOUSE_REQUEST_QUEUE);
            queues.append(" warehouse").append(wh.getID().toShortString()).append(" queue=").append(module.getMutableRequestList().size());
        }
        return out.append(queues).toString();
    }

    private boolean progress(final long tick)
    {
        recordStallTrail(tick);
        if (!draining)
        {
            if (scenario.done(this, tick))
            {
                draining = true;
                drainStart = tick;
                doneTick = tick;
                requestsAtDone = RsStats.requestsCreated;
                deliveriesAtDone = RsStats.deliveriesCreated;
                tasksAtDone = RsStats.courierTasksFinished;
                deliveredItemsAtDone = RsStats.itemsDelivered;
                distanceAtDone = courierDistance;
                scenario.stopDemand(this);
            }
            else if (tick > scenario.budget())
            {
                liveness.add("the scenario did not finish within its budget of " + scenario.budget() + " ticks; " + pendingRequests() + " requests are still open:" + describeOpen(14)
                               + describeCouriers());
                doneTick = -1;
                requestsAtDone = RsStats.requestsCreated;
                deliveriesAtDone = RsStats.deliveriesCreated;
                tasksAtDone = RsStats.courierTasksFinished;
                deliveredItemsAtDone = RsStats.itemsDelivered;
                distanceAtDone = courierDistance;
                finish(tick);
                return false;
            }
            return true;
        }
        // Drain: the demand is off; wait for every request to finish.
        if (pendingRequests() == 0 && quiesceTick < 0)
        {
            quiesceTick = tick;
        }
        if (quiesceTick < 0 && (tick - drainStart) % 50 == 0)
        {
            recordCourierTrail(tick);
        }
        if (quiesceTick >= 0 && tick - quiesceTick >= 400 || tick - drainStart > 4000)
        {
            if (quiesceTick < 0)
            {
                liveness.add("requests did not drain within 4000 ticks after the scenario finished: " + pendingRequests() + " still open:" + describeOpen(14) + describeCouriers()
                               + "\n  courierTrail (every 50 ticks of the drain, couriers with a task: position, AI state, navigation):" + courierTrail);
            }
            finish(tick);
            return false;
        }
        return true;
    }

    private static double quantile(final long[] data, final int n, final double q)
    {
        if (n == 0)
        {
            return 0;
        }
        final long[] copy = java.util.Arrays.copyOf(data, n);
        java.util.Arrays.sort(copy);
        return copy[Math.min(n - 1, (int) Math.floor(q * n))];
    }

    private void finish(final long tick)
    {
        RsProbe.enabled = false;
        final ThreadCost.Mark end = ThreadCost.mark();
        checkConservation(tick, true);
        checkSaveLoad("final@" + tick);
        final int orphans = ledgerOrphans();
        gates.addProperty("ledgerOrphans", orphans);
        gates.addProperty("ledgerLeftoverClaims", rm.getReservationLedger().all().size());
        gates.addProperty("conservationChecks", conservationChecks);
        gates.addProperty("conservationMaxDrift", conservationMax);
        gates.addProperty("completedInBudget", doneTick >= 0);
        gates.addProperty("drained", quiesceTick >= 0);
        gates.addProperty("playerFallbacks", RsStats.playerFallbacks);
        gates.addProperty("unexpectedPlayerFallbacks", unexpectedFallbacks);
        if (orphans != 0)
        {
            errors.add(orphans + " ledger reservations belong to requests that ended");
        }
        if (unexpectedFallbacks != 0)
        {
            errors.add(unexpectedFallbacks + " requests fell back to the player although the colony can produce them: " + fallbackItems);
        }

        final JsonObject out = new JsonObject();
        out.addProperty("kind", "macro");
        out.addProperty("test", testName);
        out.addProperty("scenario", scenarioName);
        out.addProperty("rep", rep);
        out.addProperty("warmup", warmup);
        out.addProperty("flags", BenchEnv.flagsOn() ? "on" : "off");
        out.addProperty("seed", seed);
        out.addProperty("workersPinned", BenchEnv.pinWorkers());
        out.addProperty("ticksRun", tick);
        out.addProperty("ticksToCompletion", doneTick);
        out.addProperty("ticksToQuiesce", quiesceTick);

        double sumCpu = 0;
        double sumRs = 0;
        double sumAlloc = 0;
        double sumRsAlloc = 0;
        int ticksWithRs = 0;
        for (int i = 0; i < samples; i++)
        {
            sumCpu += tickCpu[i];
            sumRs += tickRs[i];
            sumAlloc += tickAlloc[i];
            sumRsAlloc += tickRsAlloc[i];
            if (tickRs[i] > 0)
            {
                ticksWithRs++;
            }
        }
        final JsonObject meter = new JsonObject();
        meter.addProperty("ticks", samples);
        meter.addProperty("serverCpuNsPerTick", samples == 0 ? 0 : sumCpu / samples);
        meter.addProperty("rsCpuNsPerTick", samples == 0 ? 0 : sumRs / samples);
        meter.addProperty("rsShare", sumCpu == 0 ? 0 : sumRs / sumCpu);
        meter.addProperty("rsCpuP50", quantile(tickRs, samples, 0.5));
        meter.addProperty("rsCpuP99", quantile(tickRs, samples, 0.99));
        meter.addProperty("rsCpuMax", quantile(tickRs, samples, 1.0));
        meter.addProperty("serverCpuP50", quantile(tickCpu, samples, 0.5));
        meter.addProperty("serverCpuP99", quantile(tickCpu, samples, 0.99));
        meter.addProperty("ticksWithRs", ticksWithRs);
        meter.addProperty("allocBytesPerTick", samples == 0 ? 0 : sumAlloc / samples);
        meter.addProperty("rsAllocBytesPerTick", samples == 0 ? 0 : sumRsAlloc / samples);
        meter.addProperty("probeOverheadNsPerTick", samples == 0 ? 0 : (double) rsOverheadTotal / samples);
        meter.addProperty("harnessCpuNsPerTick", samples == 0 ? 0 : (double) harnessCpuTotal / samples);
        meter.addProperty("rsCpuNsTotal", sumRs);
        out.add("meter", meter);

        final JsonObject scopes = new JsonObject();
        for (int s = 0; s < RsProbe.SCOPES; s++)
        {
            if (RsProbe.count(s) == 0)
            {
                continue;
            }
            final JsonObject scope = new JsonObject();
            scope.addProperty("calls", RsProbe.count(s));
            scope.addProperty("inclCpuNs", RsProbe.inclCpuNs(s));
            scope.addProperty("selfCpuNs", RsProbe.selfCpuNs(s));
            scope.addProperty("inclAllocB", RsProbe.inclAllocBytes(s));
            scope.addProperty("p50Ns", RsProbe.quantileNs(s, 0.5));
            scope.addProperty("p99Ns", RsProbe.quantileNs(s, 0.99));
            scopes.add(RsProbe.NAMES[s], scope);
        }
        out.add("scopes", scopes);
        final JsonArray classes = new JsonArray();
        for (final Object[] row : RsProbe.byClassRows())
        {
            final JsonObject o = new JsonObject();
            o.addProperty("class", (String) row[0]);
            o.addProperty("scope", (String) row[1]);
            o.addProperty("calls", (Long) row[2]);
            o.addProperty("cpuNs", (Long) row[3]);
            o.addProperty("allocB", (Long) row[4]);
            classes.add(o);
        }
        out.add("byResolver", classes);

        final JsonObject work = new JsonObject();
        work.addProperty("requestsCreated", RsStats.requestsCreated);
        work.addProperty("deliveriesCreated", RsStats.deliveriesCreated);
        work.addProperty("deliveriesArrived", RsStats.deliveriesArrived);
        work.addProperty("courierTasks", RsStats.courierTasksFinished);
        work.addProperty("itemsDelivered", RsStats.itemsDelivered);
        work.addProperty("courierDistance", courierDistance);
        work.addProperty("courierTeleports", courierTeleports);
        work.addProperty("unloadCycles", cycles);
        work.addProperty("sicknessCures", cures);
        work.addProperty("sicknessCures", cures);
        work.addProperty("requestsCreatedAtDone", requestsAtDone);
        work.addProperty("deliveriesCreatedAtDone", deliveriesAtDone);
        work.addProperty("courierTasksAtDone", tasksAtDone);
        work.addProperty("itemsDeliveredAtDone", deliveredItemsAtDone);
        work.addProperty("courierDistanceAtDone", distanceAtDone);
        work.addProperty("viewBytes", RsProbe.sentBytes());
        work.addProperty("viewMessages", RsProbe.sentMessages());
        work.addProperty("openMax", openMax);
        work.addProperty("openMean", openScans == 0 ? 0 : (double) openSum / openScans);
        work.addProperty("duplicatesMax", dupMax);
        work.addProperty("duplicatesMean", dupScans == 0 ? 0 : (double) dupSum / dupScans);
        work.addProperty("breaks", BenchEnv.breaks() ? 1 : 0);
        work.addProperty("stuckRoots", stuckRoots.size());
        work.addProperty("stuckRootsStrict", stuckRootsStrict.size());
        work.addProperty("maxOpenAge", maxOpenAge);
        {
            final JsonObject why = new JsonObject();
            stuckWhy.forEach(why::addProperty);
            extra.add("stuckWhy", why);
            final JsonObject when = new JsonObject();
            stuckWhen.forEach((k, v) -> when.addProperty(String.valueOf(k * 1000), v));
            extra.add("stuckWhenFlagged", when);
            final long[] rem = stuckRemaining.stream().mapToLong(Long::longValue).toArray();
            extra.addProperty("stuckRemainingP50", quantile(rem, rem.length, 0.5));
            extra.addProperty("stuckRemainingMax", rem.length == 0 ? 0 : java.util.Arrays.stream(rem).max().getAsLong());
            extra.addProperty("stuckStillOpenAtEnd", stuckFlaggedAt.size());
        }
        final long[] lat = latencies.stream().mapToLong(Long::longValue).toArray();
        work.addProperty("requestsFinished", lat.length);
        work.addProperty("latencyP50", quantile(lat, lat.length, 0.5));
        work.addProperty("latencyP99", quantile(lat, lat.length, 0.99));
        work.addProperty("latencyMean", lat.length == 0 ? 0 : java.util.Arrays.stream(lat).average().orElse(0));
        out.add("work", work);
        if (stallTrail.length() > 0)
        {
            out.addProperty("stallTrail", stallTrail.toString());
            Log.getLogger().info("RS_BENCH_STALL_TRAIL {}{}", testName, stallTrail);
        }
        final JsonObject labourStats = new JsonObject();
        labourStats.addProperty("claims", com.minecolonies.core.colony.labour.LabourStats.CLAIMS.get());
        labourStats.addProperty("claimsOk", com.minecolonies.core.colony.labour.LabourStats.CLAIMS_OK.get());
        labourStats.addProperty("claimsFailed", com.minecolonies.core.colony.labour.LabourStats.CLAIMS_FAILED.get());
        labourStats.addProperty("claimsExpired", com.minecolonies.core.colony.labour.LabourStats.CLAIMS_EXPIRED.get());
        labourStats.addProperty("claimsOnCovered", com.minecolonies.core.colony.labour.LabourStats.CLAIMS_ON_COVERED.get());
        labourStats.addProperty("handedIn", com.minecolonies.core.colony.labour.LabourStats.HANDED_IN.get());
        labourStats.addProperty("gathered", com.minecolonies.core.colony.labour.LabourStats.GATHERED.get());
        extra.add("labour", labourStats);
        gates.addProperty("labourClaimsOnCovered", com.minecolonies.core.colony.labour.LabourStats.CLAIMS_ON_COVERED.get());
        if (com.minecolonies.core.colony.labour.LabourStats.CLAIMS_ON_COVERED.get() != 0)
        {
            errors.add(com.minecolonies.core.colony.labour.LabourStats.CLAIMS_ON_COVERED.get() + " labour claims were made on goods a specialist covers");
        }
        if (BenchEnv.labourInert() && !scenario.labour() && com.minecolonies.core.colony.labour.LabourStats.CLAIMS.get() != 0)
        {
            errors.add("the inert gate: " + com.minecolonies.core.colony.labour.LabourStats.CLAIMS.get() + " labour claims in a colony whose chains cover every good it asks for");
        }
        out.add("gates", gates);
        out.add("extra", extra);
        final JsonArray errs = new JsonArray();
        errors.forEach(errs::add);
        out.add("errors", errs);
        final JsonArray hangs = new JsonArray();
        liveness.forEach(hangs::add);
        out.add("liveness", hangs);
        gates.addProperty("incomplete", !liveness.isEmpty());

        RsProbe.stop();
        StandardToken.seedForBenchmark(null);
        com.minecolonies.core.entity.ai.workers.util.WorkerSettings.clearOverrides();
        com.minecolonies.core.colony.labour.LabourSettings.clearOverrides();
        com.minecolonies.core.colony.labour.LabourStats.gatherProbe = null;
        BenchEnv.write(testName, out);
        harnessCpuTotal += end.cpuNs();
        done.accept(errors.isEmpty() ? null : "macro bench " + testName + " gates failed: " + errors);
    }

    // ------------------------------------------------------------------ world building helpers (used by scenarios)

    /**
     * @return the box of the test plot, in world coordinates.
     */
    public AABB plotBox()
    {
        final BlockPos a = helper.absolutePos(new BlockPos(Scenarios.X0, -4, Scenarios.Z0));
        final BlockPos b = helper.absolutePos(new BlockPos(Scenarios.X1, 40, Scenarios.Z1));
        return new AABB(a.getX(), a.getY(), a.getZ(), b.getX() + 1, b.getY(), b.getZ() + 1);
    }

    public void found(final String name, final BlockPos townHallRel, final int minX, final int maxX, final int minZ, final int maxZ)
    {
        // items a previous test left lying around in this plot must not be counted as ours
        for (final ItemEntity stray : level.getEntitiesOfClass(ItemEntity.class, plotBox()))
        {
            stray.discard();
        }
        colony = BenchBridge.foundColony(helper, name, townHallRel, minX, maxX, minZ, maxZ);
        // clearing the plot broke the racks of the previous repeat: what dropped out of them is nobody's stock
        for (final ItemEntity stray : level.getEntitiesOfClass(ItemEntity.class, plotBox()))
        {
            stray.discard();
        }
        if (player == null)
        {
            player = BenchBridge.connectedPlayer(helper);
        }
        final BlockPos anchor = colony.getCenter();
        player.teleportTo(anchor.getX() + 0.5D, anchor.getY() + 1, anchor.getZ() + 0.5D);
        colony.getPackageManager().addCloseSubscriber(player);
    }

    public IBuilding place(final Block hut, final int x, final int z, final String blueprint)
    {
        return BenchBridge.place(helper, colony, hut, new BlockPos(x, 1, z), blueprint);
    }

    /**
     * Racks next to a building, registered to it (what the blueprint's racks are in a real hut).
     */
    public void racks(final IBuilding building, final int count)
    {
        final BlockPos base = building.getID();
        final List<BlockPos> list = rackPositions.computeIfAbsent(building, b -> new ArrayList<>());
        for (int i = 0; i < count; i++)
        {
            final BlockPos pos = base.offset(2 + (i % 6), 0, 2 + 2 * (i / 6));
            level.setBlock(pos, ModBlocks.blockRack.defaultBlockState(), 3);
            building.registerBlockPosition(ModBlocks.blockRack.defaultBlockState(), pos, level);
            list.add(pos);
        }
    }

    public ICitizenData hire(final IBuilding building, final Supplier<WorkerBuildingModule> module)
    {
        final BlockPos near = building.getID().offset(1, 0, 4);
        final BlockPos rel = near.subtract(helper.absolutePos(BlockPos.ZERO));
        final ICitizenData citizen = BenchBridge.spawnCitizen(helper, colony, rel, false);
        // citizens are born with random skills, which sets how fast they work: the same hands for every repeat
        for (final com.minecolonies.api.entity.citizen.Skill skill : com.minecolonies.api.entity.citizen.Skill.values())
        {
            final var data = citizen.getCitizenSkillHandler().getSkills().get(skill);
            data.setLevel(8);
            data.setExperience(0);
        }
        helper.assertTrue(module.get().assignCitizen(citizen), "could not assign a worker to " + building.getSchematicName());
        citizen.getEntity().ifPresent(e -> {
            e.setPos(building.getID().getX() + 0.5D, building.getID().getY(), building.getID().getZ() + 0.5D);
            e.getRandom().setSeed(seed * 31L + citizens.size());
        });
        citizens.add(citizen);
        return citizen;
    }
}
