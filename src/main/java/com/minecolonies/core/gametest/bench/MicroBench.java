package com.minecolonies.core.gametest.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.requestsystem.token.StandardToken;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.crafting.RecipeStorage;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.WarehouseRequestQueueModule;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.requestsystem.RsFlags;
import com.minecolonies.core.colony.requestsystem.RsProbe;
import com.minecolonies.core.colony.requestsystem.RsStats;
import com.minecolonies.core.colony.requestsystem.management.manager.RequestSystemViewSync;
import com.minecolonies.core.colony.requestsystem.management.manager.StandardRequestManager;
import com.minecolonies.core.colony.requestsystem.reservation.Reservation;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationReason;
import com.minecolonies.core.gametest.BenchBridge;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * The micro tier: the real {@code StandardRequestManager} of a real (but never ticking) colony, driven by hand with a
 * scripted event stream at a given number of open requests. No citizen AI, no world simulation: every number is the cost
 * of the request system's own code, in server-thread CPU nanoseconds and allocated bytes per operation.
 * <p>
 * Operation classes: create, assign (resolver search), the idle manager tick, re-evaluation when stock arrives, the
 * courier's task pick, completion, cancel, ledger reserve / commit / release, view sync (snapshot, full payload, delta),
 * save and load. The run also checks: save then load gives an identical state, the ledger holds no orphan claim after
 * requests ended, and no request disappeared.
 * <p>
 * Each phase runs in its own game tick so the server watchdog stays quiet at 10 000 requests.
 */
public final class MicroBench
{
    private static final int KINDS      = 60;
    private static final int REQUESTERS = 8;

    private final GameTestHelper helper;
    private final String         testName;
    private final int            scale;
    private final int            rep;
    private final boolean        warmup;
    private final Random         rng;

    private BenchBridge.Fx            fx;
    private StandardRequestManager    manager;
    private RegistryAccess            ra;
    private final List<IBuilding>     requesters = new ArrayList<>();
    private final List<Item>          kinds      = new ArrayList<>();
    private final List<IToken<?>>     open       = new ArrayList<>();
    private JobDeliveryman            courierJob;
    private WarehouseRequestQueueModule queue;
    private IBuilding                 deliveryHut;

    private final JsonObject ops   = new JsonObject();
    private final JsonObject gates = new JsonObject();
    private final JsonObject info  = new JsonObject();
    private final JsonArray  errors = new JsonArray();
    private double[]         probePairNs;
    private double           idleTickCpu;

    private final Deque<Runnable> steps = new ArrayDeque<>();

    private final java.util.function.Consumer<String> done;

    private MicroBench(final GameTestHelper helper, final String testName, final int scale, final int rep, final boolean warmup, final java.util.function.Consumer<String> done)
    {
        this.done = done;
        this.helper = helper;
        this.testName = testName;
        this.scale = scale;
        this.rep = rep;
        this.warmup = warmup;
        this.rng = new Random(BenchEnv.seed() * 1_000_003L + scale);
    }

    public static void run(final GameTestHelper helper, final String testName, final int scale, final int rep, final boolean warmup, final java.util.function.Consumer<String> done)
    {
        new MicroBench(helper, testName, scale, rep, warmup, done).start();
    }

    // ------------------------------------------------------------------ driver

    private void start()
    {
        final boolean flags = BenchEnv.flagsOn();
        RsFlags.overrideReservations(flags);
        RsFlags.overrideSmartRetry(flags);
        RsStats.reset();
        StandardToken.seedForBenchmark(BenchEnv.seed() + scale);
        probePairNs = RsProbe.calibrate(200_000);

        steps.add(this::setup);
        steps.add(() -> fill(scale - measuredBatch()));
        steps.add(this::phaseIdle);
        steps.add(this::phaseCreate);
        steps.add(this::phaseIdle2);
        steps.add(this::phaseStock);
        steps.add(this::phaseCourier);
        steps.add(this::phaseComplete);
        steps.add(this::phaseCancel);
        steps.add(this::phaseLedger);
        steps.add(this::phaseView);
        steps.add(this::phaseSaveLoad);
        steps.add(this::finish);
        helper.runAfterDelay(1, this::next);
    }

    private void next()
    {
        final Runnable step = steps.poll();
        if (step == null)
        {
            return;
        }
        try
        {
            step.run();
        }
        catch (final Throwable t)
        {
            StandardToken.seedForBenchmark(null);
            RsProbe.stop();
            done.accept("micro bench " + testName + " failed: " + t);
            t.printStackTrace();
            return;
        }
        if (!steps.isEmpty())
        {
            helper.runAfterDelay(1, this::next);
        }
    }

    private int measuredBatch()
    {
        return Math.max(50, Math.min(500, scale / 10));
    }

    // ------------------------------------------------------------------ steps

    private void setup()
    {
        fx = BenchBridge.fixture(helper, "rsb-micro-" + scale, 12, false);
        manager = (StandardRequestManager) fx.standard();
        ra = helper.getLevel().registryAccess();
        queue = fx.warehouse.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE);

        deliveryHut = fx.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");
        final IBuilding sawmill = fx.place(ModBlocks.blockHutSawmill, new BlockPos(8, 1, 22), "craftsmanship/carpentry/sawmill1.blueprint");
        // A gatherer: the colony can "still make" anything, so unmet requests wait instead of falling back to the player.
        fx.place(ModBlocks.blockHutLumberjack, new BlockPos(24, 1, 22), "fundamentals/lumberjack1.blueprint");
        final com.minecolonies.api.colony.buildings.modules.ICraftingBuildingModule craft = sawmill.getModule(BuildingModules.SAWMILL_CRAFT);
        final RecipeStorage recipe = RecipeStorage.builder()
          .withRecipeId(null)
          .withInputs(List.of(new ItemStorage(new ItemStack(Items.OAK_LOG, 1))))
          .withPrimaryOutput(new ItemStack(Items.OAK_PLANKS, 4))
          .build();
        craft.addRecipe(IColonyManager.getInstance().getRecipeManager().checkOrAddRecipe(recipe));

        final ICitizenData courier = fx.spawn(new BlockPos(34, 1, 8), true);
        helper.assertTrue(deliveryHut.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier), "courier not assigned to its hut");
        helper.assertTrue(fx.warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "courier not assigned to the warehouse");
        courierJob = courier.getJob(JobDeliveryman.class);

        requesters.add(fx.builder);
        for (int i = 1; i < REQUESTERS; i++)
        {
            requesters.add(fx.place(ModBlocks.blockHutBuilder, new BlockPos(2 + 6 * i, 1, 27), "fundamentals/builder1.blueprint"));
        }

        final List<Item> candidates = new ArrayList<>();
        for (final Item item : BuiltInRegistries.ITEM)
        {
            final ItemStack sample = new ItemStack(item);
            if (!sample.isEmpty() && sample.getMaxStackSize() == 64 && !sample.isDamageableItem() && item != Items.OAK_LOG && item != Items.OAK_PLANKS)
            {
                candidates.add(item);
            }
        }
        candidates.sort(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()));
        final int stride = Math.max(1, candidates.size() / KINDS);
        for (int i = 0; i < KINDS && i * stride < candidates.size(); i++)
        {
            kinds.add(candidates.get(i * stride));
        }
        info.addProperty("itemKinds", kinds.size());
        info.addProperty("requesters", requesters.size());
        info.addProperty("probePairCoarseNs", probePairNs[0]);
        info.addProperty("probePairFineNs", probePairNs[1]);

        RsProbe.start();
    }

    private IToken<?> createOne()
    {
        final IBuilding requester = requesters.get(rng.nextInt(requesters.size()));
        final Item item = kinds.get(rng.nextInt(kinds.size()));
        final int count = 1 + rng.nextInt(16);
        final IToken<?> token = requester.createRequest(new Stack(new ItemStack(item, count), count, count), false);
        open.add(token);
        return token;
    }

    private void fill(final int count)
    {
        // The pre-fill is not measured: the probe is on but its numbers are read as differences of snapshots.
        for (int i = 0; i < count; i++)
        {
            createOne();
            if (i % 250 == 249)
            {
                // keep the time budget of a tick: continue next tick
                final int remaining = count - i - 1;
                if (remaining > 0)
                {
                    steps.addFirst(() -> fill(remaining));
                }
                return;
            }
        }
    }

    private JsonObject stats(final RsProbe.Snap a, final RsProbe.Snap b, final int scope, final long divisor)
    {
        final long calls = b.count[scope] - a.count[scope];
        final JsonObject out = new JsonObject();
        out.addProperty("calls", calls);
        out.addProperty("cpuNs", calls == 0 ? 0 : (double) (b.inclCpu[scope] - a.inclCpu[scope]) / calls);
        out.addProperty("selfCpuNs", calls == 0 ? 0 : (double) (b.selfCpu[scope] - a.selfCpu[scope]) / calls);
        out.addProperty("allocB", calls == 0 ? 0 : (double) (b.inclAlloc[scope] - a.inclAlloc[scope]) / calls);
        out.addProperty("p50Ns", b.quantileNs(scope, 0.5, a));
        out.addProperty("p99Ns", b.quantileNs(scope, 0.99, a));
        out.addProperty("totalCpuNs", b.inclCpu[scope] - a.inclCpu[scope]);
        if (divisor > 0)
        {
            out.addProperty("perUnitCpuNs", (double) (b.inclCpu[scope] - a.inclCpu[scope]) / divisor);
        }
        return out;
    }

    private JsonObject wallStats(final ThreadCost.Mark mark, final long units)
    {
        final JsonObject out = new JsonObject();
        out.addProperty("units", units);
        out.addProperty("cpuNs", units == 0 ? 0 : (double) mark.cpuNs() / units);
        out.addProperty("allocB", units == 0 ? 0 : (double) mark.allocBytes() / units);
        return out;
    }

    private void phaseIdle()
    {
        // The idle tick at about the full scale: nothing changes, everything waits.
        final RsProbe.Snap a = RsProbe.snap();
        for (int i = 0; i < 30; i++)
        {
            manager.tick();
        }
        final RsProbe.Snap b = RsProbe.snap();
        final JsonObject idle = stats(a, b, RsProbe.MGR_TICK, 0);
        ops.add("idleTick", idle);
        idleTickCpu = idle.get("cpuNs").getAsDouble();
        info.addProperty("openAtIdle", open.size());
    }

    private void phaseCreate()
    {
        final int batch = measuredBatch();
        final RsProbe.Snap a = RsProbe.snap();
        final ThreadCost.Mark mark = ThreadCost.mark();
        for (int i = 0; i < batch; i++)
        {
            createOne();
        }
        final RsProbe.Snap b = RsProbe.snap();
        ops.add("create", stats(a, b, RsProbe.CREATE, 0));
        ops.add("assign", stats(a, b, RsProbe.ASSIGN, 0));
        ops.add("canResolve", stats(a, b, RsProbe.CAN_RESOLVE, 0));
        ops.add("attempt", stats(a, b, RsProbe.ATTEMPT, 0));
        ops.add("createAndAssignTotal", wallStats(mark, batch));
        info.addProperty("openAfterCreate", open.size());
    }

    private void phaseIdle2()
    {
        // Settle: the retry delays of the new requests run out one by one.
        for (int i = 0; i < 12; i++)
        {
            manager.tick();
        }
    }

    private int openCount()
    {
        int n = 0;
        for (final IRequest<?> request : manager.getRequestIdentitiesDataStore().getIdentities().values())
        {
            if (request.getRequest() instanceof Stack && !request.hasParent())
            {
                n++;
            }
        }
        return n;
    }

    private void phaseStock()
    {
        final int events = 12;
        final int drain = 25;
        final long deliveriesBefore = RsStats.deliveriesCreated;
        final RsProbe.Snap a = RsProbe.snap();
        final ThreadCost.Mark mark = ThreadCost.mark();
        final long before1 = RsProbe.totalCpuNs();
        for (int e = 0; e < events; e++)
        {
            final Item item = kinds.get(rng.nextInt(kinds.size()));
            fx.stock(e % fx.racks.size(), new ItemStack(item, 64));
            for (int t = 0; t < drain; t++)
            {
                manager.tick();
            }
        }
        final RsProbe.Snap b = RsProbe.snap();
        final long window = RsProbe.totalCpuNs() - before1;
        final JsonObject gross = new JsonObject();
        gross.addProperty("events", events);
        gross.addProperty("cpuNs", (double) window / events);
        gross.addProperty("netOfIdleTicksCpuNs", (double) window / events - drain * idleTickCpu);
        gross.addProperty("allocB", (double) mark.allocBytes() / events);
        gross.addProperty("probeOverheadNs", (double) (b.overhead - a.overhead) / events);
        ops.add("stockEvent", gross);
        ops.add("stockEventInline", stats(a, b, RsProbe.STOCK_EVENT, 0));
        ops.add("reevalInTick", stats(a, b, RsProbe.REEVAL, 0));
        info.addProperty("deliveriesByStock", RsStats.deliveriesCreated - deliveriesBefore);
        // The resolved requests' deliveries now wait for a courier in the warehouse queue.
        info.addProperty("queueAfterStock", queue.getMutableRequestList().size());
    }

    private void phaseCourier()
    {
        // Top the warehouse queue up to the scale with plain deliveries (what many resolved requests leave there).
        final int target = Math.max(scale, 100);
        int guard = 0;
        while (queue.getMutableRequestList().size() < target && guard++ < target)
        {
            final IBuilding to = requesters.get(rng.nextInt(requesters.size()));
            // The deliveries resolver adds an assigned delivery to the warehouse queue itself, as it does for real ones.
            manager.createAndAssignRequest(fx.warehouse.getRequester(), new Delivery(fx.warehouse.getLocation(), to.getLocation(), new ItemStack(Items.DIRT, 1), 13));
        }
        info.addProperty("courierQueue", queue.getMutableRequestList().size());
        final int picks = 100;
        final RsProbe.Snap a = RsProbe.snap();
        final ThreadCost.Mark mark = ThreadCost.mark();
        int got = 0;
        for (int i = 0; i < picks; i++)
        {
            final IRequest<?> task = courierJob.getCurrentTask();
            if (task != null)
            {
                got++;
            }
            for (final IToken<?> token : courierJob.getTaskQueue())
            {
                courierJob.onTaskDeletion(token);
            }
        }
        final RsProbe.Snap b = RsProbe.snap();
        ops.add("courierPick", stats(a, b, RsProbe.COURIER_PICK, 0));
        ops.add("courierPickTotal", wallStats(mark, picks));
        info.addProperty("courierPicksWithTask", got);
    }

    private void phaseComplete()
    {
        // Deliveries finishing: the courier resolves them, the parents complete and are received.
        final List<IRequest<?>> deliveries = new ArrayList<>();
        for (final IRequest<?> request : manager.getRequestIdentitiesDataStore().getIdentities().values())
        {
            if (request.getRequest() instanceof Delivery && request.getState() == RequestState.IN_PROGRESS && request.hasParent())
            {
                deliveries.add(request);
                if (deliveries.size() >= 150)
                {
                    break;
                }
            }
        }
        final RsProbe.Snap a = RsProbe.snap();
        final ThreadCost.Mark mark = ThreadCost.mark();
        for (final IRequest<?> delivery : deliveries)
        {
            manager.updateRequestState(delivery.getId(), RequestState.RESOLVED);
        }
        final RsProbe.Snap b = RsProbe.snap();
        final JsonObject stats = stats(a, b, RsProbe.STATE, 0);
        ops.add("complete", stats);
        ops.add("completeTotal", wallStats(mark, Math.max(1, deliveries.size())));
        info.addProperty("completedDeliveries", deliveries.size());
    }

    private void phaseCancel()
    {
        final int cancels = Math.max(60, Math.min(300, scale / 10));
        final List<IToken<?>> targets = new ArrayList<>();
        for (int i = 0; i < cancels && !open.isEmpty(); i++)
        {
            final IToken<?> token = open.remove(rng.nextInt(open.size()));
            if (manager.getRequestForToken(token) != null)
            {
                targets.add(token);
            }
        }
        final RsProbe.Snap a = RsProbe.snap();
        final ThreadCost.Mark mark = ThreadCost.mark();
        for (final IToken<?> token : targets)
        {
            manager.updateRequestState(token, RequestState.CANCELLED);
        }
        final RsProbe.Snap b = RsProbe.snap();
        ops.add("cancel", stats(a, b, RsProbe.STATE, 0));
        ops.add("cancelTotal", wallStats(mark, Math.max(1, targets.size())));

        // Gate: nothing promised for a request that no longer exists or has ended.
        int orphans = 0;
        for (final Reservation r : manager.getReservationLedger().all())
        {
            final IRequest<?> request = manager.getRequestIdentitiesDataStore().getIdentities().get(r.token());
            if (request == null || request.getState() == RequestState.CANCELLED || request.getState() == RequestState.FAILED || request.getState() == RequestState.RECEIVED
                  || request.getState() == RequestState.OVERRULED)
            {
                orphans++;
            }
        }
        gates.addProperty("ledgerOrphans", orphans);
        gates.addProperty("ledgerClaims", manager.getReservationLedger().all().size());
        for (final IToken<?> token : targets)
        {
            final IRequest<?> request = manager.getRequestForToken(token);
            if (request != null && request.getState() != RequestState.CANCELLED && request.getState() != RequestState.RECEIVED && request.getState() != RequestState.FAILED)
            {
                errors.add("cancelled request is still " + request.getState() + ": " + token);
                break;
            }
        }
    }

    private void phaseLedger()
    {
        final ReservationLedger ledger = new ReservationLedger();
        final BlockPos scope = fx.warehouse.getID();
        final List<IToken<?>> tokens = new ArrayList<>();
        final int fillCount = scale;
        for (int i = 0; i < fillCount; i++)
        {
            final IToken<?> token = new StandardToken();
            tokens.add(token);
            ledger.reserveStock(scope, fx.racks.get(i % fx.racks.size()), new ItemStorage(new ItemStack(kinds.get(i % kinds.size()))), 1 + i % 8, token, "bench", ReservationReason.OTHER, i);
        }
        final int m = Math.max(50, Math.min(500, scale / 10));
        final RsProbe.Snap a = RsProbe.snap();
        final List<IToken<?>> fresh = new ArrayList<>();
        for (int i = 0; i < m; i++)
        {
            final IToken<?> token = new StandardToken();
            fresh.add(token);
            ledger.reserveStock(scope, fx.racks.get(i % fx.racks.size()), new ItemStorage(new ItemStack(kinds.get((i * 7) % kinds.size()))), 3, token, "bench", ReservationReason.OTHER, fillCount + i);
        }
        final RsProbe.Snap b = RsProbe.snap();
        ops.add("ledgerReserve", stats(a, b, RsProbe.LEDGER, 0));
        final RsProbe.Snap c = RsProbe.snap();
        for (int i = 0; i < m; i++)
        {
            ledger.available(scope, new ItemStorage(new ItemStack(kinds.get((i * 11) % kinds.size()))), 100);
        }
        final RsProbe.Snap d = RsProbe.snap();
        ops.add("ledgerRead", stats(c, d, RsProbe.LEDGER_READ, 0));
        final RsProbe.Snap e = RsProbe.snap();
        for (int i = 0; i < m; i++)
        {
            ledger.commitStock(fresh.get(i), scope, new ItemStorage(new ItemStack(kinds.get((i * 7) % kinds.size()))), 3, fillCount + m + i);
        }
        final RsProbe.Snap f = RsProbe.snap();
        ops.add("ledgerCommit", stats(e, f, RsProbe.LEDGER, 0));
        final RsProbe.Snap g = RsProbe.snap();
        for (int i = 0; i < m; i++)
        {
            ledger.release(tokens.get(i), fillCount + 2L * m + i);
        }
        final RsProbe.Snap h = RsProbe.snap();
        ops.add("ledgerRelease", stats(g, h, RsProbe.LEDGER, 0));
    }

    private void phaseView()
    {
        final ThreadCost.Mark m1 = ThreadCost.mark();
        final RequestSystemViewSync.Snapshot first = RequestSystemViewSync.snapshot(manager, ra);
        final JsonObject snapshot = wallStats(m1, 1);
        snapshot.addProperty("bytes", first.size());
        ops.add("viewSnapshot", snapshot);

        final ThreadCost.Mark m2 = ThreadCost.mark();
        final RegistryFriendlyByteBuf full = new RegistryFriendlyByteBuf(Unpooled.buffer(), ra);
        RequestSystemViewSync.writeFull(full, first, 1);
        final JsonObject fullStats = wallStats(m2, 1);
        fullStats.addProperty("bytes", full.readableBytes());
        ops.add("viewFull", fullStats);

        // One percent of the requests change: some cancelled, as many created.
        final int changes = Math.max(2, scale / 100);
        for (int i = 0; i < changes && !open.isEmpty(); i++)
        {
            final IToken<?> token = open.remove(rng.nextInt(open.size()));
            if (manager.getRequestForToken(token) != null)
            {
                manager.updateRequestState(token, RequestState.CANCELLED);
            }
        }
        for (int i = 0; i < changes; i++)
        {
            createOne();
        }
        final ThreadCost.Mark m3 = ThreadCost.mark();
        final RequestSystemViewSync.Snapshot second = RequestSystemViewSync.snapshot(manager, ra);
        final JsonObject snapshot2 = wallStats(m3, 1);
        ops.add("viewSnapshot2", snapshot2);
        final ThreadCost.Mark m4 = ThreadCost.mark();
        final RegistryFriendlyByteBuf delta = new RegistryFriendlyByteBuf(Unpooled.buffer(), ra);
        final boolean wrote = RequestSystemViewSync.writeDelta(delta, first, 1, second, 2);
        final JsonObject deltaStats = wallStats(m4, 1);
        deltaStats.addProperty("bytes", wrote ? delta.readableBytes() : full.readableBytes());
        deltaStats.addProperty("changes", changes);
        ops.add("viewDelta", deltaStats);
        // The cost of keeping a close player current once per dirty tick: snapshot + delta.
        final JsonObject perDirtyTick = new JsonObject();
        perDirtyTick.addProperty("cpuNs", snapshot2.get("cpuNs").getAsDouble() + deltaStats.get("cpuNs").getAsDouble());
        perDirtyTick.addProperty("allocB", snapshot2.get("allocB").getAsDouble() + deltaStats.get("allocB").getAsDouble());
        perDirtyTick.addProperty("bytes", deltaStats.get("bytes").getAsInt());
        ops.add("viewPerDirtyTick", perDirtyTick);
    }

    private void phaseSaveLoad()
    {
        final ThreadCost.Mark m1 = ThreadCost.mark();
        final CompoundTag saved = manager.serializeNBT(ra);
        final JsonObject save = wallStats(m1, 1);
        try
        {
            final ByteArrayOutputStream raw = new ByteArrayOutputStream();
            NbtIo.write(saved, new DataOutputStream(raw));
            save.addProperty("bytes", raw.size());
            final ByteArrayOutputStream gz = new ByteArrayOutputStream();
            NbtIo.writeCompressed(saved, gz);
            save.addProperty("bytesCompressed", gz.size());
        }
        catch (final java.io.IOException e)
        {
            errors.add("nbt size: " + e);
        }
        ops.add("save", save);

        final StandardRequestManager loaded = new StandardRequestManager(fx.colony);
        final ThreadCost.Mark m2 = ThreadCost.mark();
        loaded.deserializeNBT(ra, saved);
        ops.add("load", wallStats(m2, 1));

        final RsDigest before = RsDigest.of(manager, ra);
        final RsDigest after = RsDigest.of(loaded, ra);
        final String diff = before.diff(after);
        gates.addProperty("saveLoadIdentical", diff == null);
        if (diff != null)
        {
            errors.add("save/load differs: " + diff);
        }
        gates.addProperty("requestsAtSave", before.requests.size());
    }

    private void finish()
    {
        RsProbe.stop();
        StandardToken.seedForBenchmark(null);
        final JsonObject out = new JsonObject();
        out.addProperty("kind", "micro");
        out.addProperty("test", testName);
        out.addProperty("scale", scale);
        out.addProperty("rep", rep);
        out.addProperty("warmup", warmup);
        out.addProperty("flags", BenchEnv.flagsOn() ? "on" : "off");
        out.addProperty("seed", BenchEnv.seed());
        out.add("info", info);
        out.add("ops", ops);
        out.add("gates", gates);
        out.add("errors", errors);
        BenchEnv.write(testName, out);
        final boolean bad = !errors.isEmpty() || gates.get("ledgerOrphans").getAsInt() != 0 || !gates.get("saveLoadIdentical").getAsBoolean();
        done.accept(bad ? "micro bench " + testName + " gates failed: " + errors + " gates=" + gates : null);
    }
}
