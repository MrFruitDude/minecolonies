package com.minecolonies.core.gametest;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.ICraftingBuildingModule;
import com.minecolonies.api.colony.requestsystem.StandardFactoryController;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.request.WaitReason;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.crafting.RecipeStorage;
import com.minecolonies.api.eventbus.events.colony.requests.RequestStateChangedEvent;
import com.minecolonies.api.eventbus.events.colony.requests.RequestStateChangedModEvent;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.requestsystem.RsFlags;
import com.minecolonies.core.colony.requestsystem.RsStats;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import com.minecolonies.core.colony.requestsystem.wait.CourierWatchdog;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * RS2: named wait reasons, event-driven re-evaluation, notifications, courier deadlines (glob {@code minecolonies:rs_*}).
 */
public final class RequestWaitGameTests
{
    private static final Predicate<ItemStack> LOGS = stack -> stack.is(Items.OAK_LOG);

    /**
     * Where the event listeners (registered once for the process) put what they hear. Null between tests.
     */
    private static volatile List<RequestStateChangedModEvent> modEvents;
    private static volatile List<RequestStateChangedEvent>    neoEvents;
    private static volatile int                               listenedColony = -1;
    private static boolean                                    listening;

    private RequestWaitGameTests()
    {
    }

    private static IToken<?> ask(final IBuilding requester, final ItemStack stack)
    {
        return requester.createRequest(new Stack(stack, stack.getCount(), stack.getCount()), false);
    }

    private static void flags(final boolean on)
    {
        RsFlags.overrideReservations(on);
        RsFlags.overrideSmartRetry(on);
    }

    private static synchronized void listenOnce()
    {
        if (listening)
        {
            return;
        }
        listening = true;
        IMinecoloniesAPI.getInstance().getEventBus().subscribe(RequestStateChangedModEvent.class, event -> {
            final List<RequestStateChangedModEvent> sink = modEvents;
            if (sink != null && event.getColony().getID() == listenedColony)
            {
                sink.add(event);
            }
        });
        NeoForge.EVENT_BUS.addListener(RequestStateChangedEvent.class, event -> {
            final List<RequestStateChangedEvent> sink = neoEvents;
            if (sink != null && event.getColony().getID() == listenedColony)
            {
                sink.add(event);
            }
        });
    }

    // ------------------------------------------------------------------ player fallback

    /**
     * An item that nothing in the colony can ever make goes to the player at once, with one notification, however often
     * the request system looks at it again.
     */
    public static void unproducibleGoesToPlayerOnce(final GameTestHelper helper)
    {
        flags(true);
        final RsFixture f = RsFixture.found(helper, "rs-unproducible", 2);
        RsStats.reset();

        final IToken<?> first = ask(f.builder, new ItemStack(Items.DIAMOND, 4));
        helper.assertTrue(f.isWithPlayer(first), "nothing can make diamonds here: the player has them");
        helper.assertTrue(f.request(first).getWaitReason() == WaitReason.PLAYER_REQUIRED, "named: " + f.request(first).getWaitReason());
        helper.assertTrue(f.tracker().notifier().sentCount() == 1, "one notification: " + f.tracker().notifier().recent());
        helper.assertTrue(RsStats.playerFallbacks == 1, "one fallback: " + RsStats.playerFallbacks);

        // Looking again, many times, changes nothing.
        for (int i = 0; i < 6; i++)
        {
            f.manager().onStockAvailable(new ItemStack(Items.DIAMOND));
            f.manager().onColonyUpdate(request -> true);
        }
        helper.assertTrue(f.isWithPlayer(first) && f.request(first).getWaitReason() == WaitReason.PLAYER_REQUIRED, "still with the player, same reason");
        helper.assertTrue(f.tracker().notifier().sentCount() == 1, "still one notification: " + f.tracker().notifier().recent());

        // A second request for the same item does not repeat the message within the rate limit.
        final IToken<?> second = ask(f.builder, new ItemStack(Items.DIAMOND, 2));
        helper.assertTrue(f.isWithPlayer(second), "also with the player");
        helper.assertTrue(f.tracker().notifier().sentCount() == 1, "same topic inside five minutes: still one notification: " + f.tracker().notifier().recent());

        helper.runAfterDelay(400, () -> {
            helper.assertTrue(f.tracker().notifier().sentCount() == 1, "no further notification after 400 ticks");
            helper.assertTrue(RsStats.playerFallbacks == 2, "two requests fell to the player: " + RsStats.playerFallbacks);
            helper.succeed();
        });
    }

    /**
     * An item something can still gather is not handed to the player: it waits with a name, and says nothing.
     */
    public static void producibleWaitsQuietly(final GameTestHelper helper)
    {
        flags(true);
        final RsFixture f = RsFixture.found(helper, "rs-producible", 2);
        RsStats.reset();
        f.place(ModBlocks.blockHutLumberjack, new BlockPos(24, 1, 22), "fundamentals/lumberjack1.blueprint");

        final IToken<?> logs = ask(f.builder, new ItemStack(Items.OAK_LOG, 8));
        helper.assertTrue(f.isRetrying(logs), "a lumberjack may bring logs: the request waits");
        helper.assertTrue(f.request(logs).getWaitReason() == WaitReason.NO_SOURCE, "named: " + f.request(logs).getWaitReason());
        helper.assertTrue(f.tracker().notifier().sentCount() == 0, "nothing to tell the player yet");
        helper.assertTrue(RsStats.playerFallbacks == 0, "not a fallback");
        helper.assertTrue(f.tracker().waiterCount() == 1, "indexed as a waiter");
        helper.succeed();
    }

    // ------------------------------------------------------------------ events

    /**
     * State changes and wait-reason changes are posted on the mod event bus and on the NeoForge event bus.
     */
    public static void stateChangedEvents(final GameTestHelper helper)
    {
        flags(true);
        listenOnce();
        final RsFixture f = RsFixture.found(helper, "rs-events", 2);
        f.place(ModBlocks.blockHutLumberjack, new BlockPos(24, 1, 22), "fundamentals/lumberjack1.blueprint");
        final List<RequestStateChangedModEvent> mod = new CopyOnWriteArrayList<>();
        final List<RequestStateChangedEvent> neo = new CopyOnWriteArrayList<>();
        listenedColony = f.colony.getID();
        modEvents = mod;
        neoEvents = neo;

        final IToken<?> logs = ask(f.builder, new ItemStack(Items.OAK_LOG, 8));
        helper.assertTrue(mod.stream().anyMatch(e -> e.getRequest().equals(logs) && e.getNewReason() == WaitReason.NO_SOURCE && e.getOldReason() == WaitReason.NONE),
          "the wait reason change is announced on the mod bus: " + mod.size() + " events");
        helper.assertTrue(neo.stream().anyMatch(e -> e.getRequest().equals(logs) && e.getNewReason() == WaitReason.NO_SOURCE), "and on the NeoForge bus: " + neo.size() + " events");
        helper.assertTrue(mod.stream().anyMatch(e -> e.getRequest().equals(logs) && e.getNewState() == RequestState.IN_PROGRESS), "the state change is announced");
        helper.assertTrue(mod.size() == neo.size(), "the same events on both buses: " + mod.size() + " / " + neo.size());

        f.stock(0, new ItemStack(Items.OAK_LOG, 10));
        helper.succeedWhen(() -> {
            helper.assertTrue(mod.stream().anyMatch(e -> e.getRequest().equals(logs) && e.getNewState() == RequestState.FOLLOWUP_IN_PROGRESS && e.getNewReason() == WaitReason.AWAITING_DELIVERY
                                                              || e.getRequest().equals(logs) && e.getNewState() == RequestState.FOLLOWUP_IN_PROGRESS),
              "serving the request is announced: " + mod.stream().filter(e -> e.getRequest().equals(logs)).map(e -> e.getNewState() + "/" + e.getNewReason()).toList());
            helper.assertTrue(mod.stream().anyMatch(e -> e.getRequest().equals(logs) && e.getOldReason() == WaitReason.NO_SOURCE && e.getNewReason() != WaitReason.NO_SOURCE),
              "and that it no longer waits for a source");
            modEvents = null;
            neoEvents = null;
        });
    }

    /**
     * The wait reason travels with the request: through NBT (save) and through the network buffer (the client view sync).
     */
    public static void waitReasonIsSynced(final GameTestHelper helper)
    {
        flags(true);
        final RsFixture f = RsFixture.found(helper, "rs-view", 2);
        f.place(ModBlocks.blockHutLumberjack, new BlockPos(24, 1, 22), "fundamentals/lumberjack1.blueprint");
        final IToken<?> logs = ask(f.builder, new ItemStack(Items.OAK_LOG, 8));
        final IRequest<?> request = f.request(logs);
        helper.assertTrue(request.getWaitReason() == WaitReason.NO_SOURCE, "fixture: waiting for a source");

        final var controller = StandardFactoryController.getInstance();
        final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), f.level.registryAccess());
        controller.serialize(buf, request);
        final IRequest<?> viewed = controller.deserialize(buf);
        helper.assertTrue(viewed.getWaitReason() == WaitReason.NO_SOURCE, "the client view knows why: " + viewed.getWaitReason());
        helper.assertTrue(viewed.getState() == request.getState(), "and the state");

        final var tag = controller.serializeTag(f.level.registryAccess(), request);
        final IRequest<?> loaded = controller.deserializeTag(f.level.registryAccess(), tag);
        helper.assertTrue(loaded.getWaitReason() == WaitReason.NO_SOURCE, "the save keeps it: " + loaded.getWaitReason());

        // A request without a reason costs nothing extra on disk.
        final IToken<?> other = ask(f.builder, new ItemStack(Items.OAK_LOG, 1));
        f.stock(0, new ItemStack(Items.OAK_LOG, 10));
        helper.succeedWhen(() -> {
            helper.assertTrue(f.request(logs).getWaitReason() != WaitReason.NO_SOURCE, "reason cleared once served: " + f.request(logs).getWaitReason());
        });
    }

    /**
     * How long a request for an item that nothing in the colony can make waits before it reaches the player: the old retry
     * ladder (three rounds of 1200 request-system ticks) against the immediate hand-over. Reports the ticks.
     */
    public static void unproducibleLadder(final GameTestHelper helper, final boolean smart)
    {
        flags(smart);
        final RsFixture f = RsFixture.found(helper, "rs-ladder-" + (smart ? "on" : "off"), 2);
        final long start = f.level.getGameTime();
        final IToken<?> token = ask(f.builder, new ItemStack(Items.DIAMOND, 4));
        if (smart)
        {
            helper.assertTrue(f.isWithPlayer(token), "with the player at once");
            logMetric("never_producible_request_reaches_player", true, f.level.getGameTime() - start);
            helper.succeed();
            return;
        }
        helper.assertTrue(f.isRetrying(token), "fixture: the old system parks it at the retrying resolver");
        helper.succeedWhen(() -> {
            helper.assertTrue(f.isWithPlayer(token), "still going through the retry ladder");
            logMetric("never_producible_request_reaches_player", false, f.level.getGameTime() - start);
        });
    }

    // ------------------------------------------------------------------ waking

    private static void logMetric(final String what, final boolean smart, final long ticks)
    {
        Log.getLogger().info("RS_METRIC {} flags={} ticks={}", what, smart ? "on" : "off", ticks);
    }

    /**
     * Logs arrive in the warehouse while a request waits for them: how many ticks until the request is served.
     */
    public static void wakeOnStock(final GameTestHelper helper, final boolean smart)
    {
        flags(smart);
        final RsFixture f = RsFixture.found(helper, "rs-wake-stock-" + (smart ? "on" : "off"), 4);
        f.place(ModBlocks.blockHutLumberjack, new BlockPos(24, 1, 22), "fundamentals/lumberjack1.blueprint");
        final IToken<?> logs = ask(f.builder, new ItemStack(Items.OAK_LOG, 8));
        helper.assertTrue(f.isRetrying(logs), "fixture: the request waits");

        helper.runAfterDelay(300, () -> {
            helper.assertTrue(f.isRetrying(logs), "still waiting after 300 ticks");
            final long start = f.level.getGameTime();
            f.stock(0, new ItemStack(Items.OAK_LOG, 10));
            helper.succeedWhen(() -> {
                final IRequest<?> r = f.request(logs);
                helper.assertTrue(r.getState() == RequestState.FOLLOWUP_IN_PROGRESS, "not served yet: " + r.getState());
                final long ticks = f.level.getGameTime() - start;
                logMetric("stock_arrives_request_served", smart, ticks);
                if (smart)
                {
                    helper.assertTrue(ticks <= 60, "served within 60 ticks of the stock arriving: " + ticks);
                }
            });
        });
    }

    /**
     * Half of the logs arrive, then the other half. The request must not be handed to the player in between.
     */
    public static void partialArrival(final GameTestHelper helper, final boolean smart)
    {
        flags(smart);
        final RsFixture f = RsFixture.found(helper, "rs-partial-" + (smart ? "on" : "off"), 4);
        f.place(ModBlocks.blockHutLumberjack, new BlockPos(24, 1, 22), "fundamentals/lumberjack1.blueprint");
        RsStats.reset();
        final IToken<?> logs = ask(f.builder, new ItemStack(Items.OAK_LOG, 8));
        helper.assertTrue(f.isRetrying(logs), "fixture: the request waits");

        helper.runAfterDelay(100, () -> {
            f.stock(0, new ItemStack(Items.OAK_LOG, 4));
            helper.runAfterDelay(120, () -> {
                final long fallbacks = RsStats.playerFallbacks;
                Log.getLogger().info("RS_METRIC half_the_stock_arrived player_fallbacks flags={} count={}", smart ? "on" : "off", fallbacks);
                if (smart)
                {
                    helper.assertTrue(fallbacks == 0 && f.isRetrying(logs), "half the logs: the request keeps waiting, it is not handed to the player (fallbacks=" + fallbacks + ")");
                }
                final long start = f.level.getGameTime();
                f.stock(1, new ItemStack(Items.OAK_LOG, 4));
                helper.succeedWhen(() -> {
                    final IRequest<?> r = f.request(logs);
                    helper.assertTrue(r.getState() == RequestState.FOLLOWUP_IN_PROGRESS, "not served yet: " + r.getState());
                    logMetric("second_half_arrives_request_served", smart, f.level.getGameTime() - start);
                });
            });
        });
    }

    /**
     * A recipe exists, nobody is assigned to craft: the request waits and says so. When a worker is hired, how many ticks
     * until the crafting starts.
     */
    public static void crafterHiredLate(final GameTestHelper helper, final boolean smart)
    {
        flags(smart);
        final RsFixture f = RsFixture.found(helper, "rs-crafter-" + (smart ? "on" : "off"), 4);
        RsStats.reset();
        final IBuilding sawmill = f.place(ModBlocks.blockHutSawmill, new BlockPos(8, 1, 22), "craftsmanship/carpentry/sawmill1.blueprint");
        final ICraftingBuildingModule craft = sawmill.getModule(BuildingModules.SAWMILL_CRAFT);
        final RecipeStorage recipe = RecipeStorage.builder()
          .withRecipeId(null) // no source: the colony view would drop a recipe the datapack does not know
          .withInputs(List.of(new ItemStorage(new ItemStack(Items.OAK_LOG, 1))))
          .withPrimaryOutput(new ItemStack(Items.OAK_PLANKS, 4))
          .build();
        final IToken<?> recipeToken = IColonyManager.getInstance().getRecipeManager().checkOrAddRecipe(recipe);
        helper.assertTrue(craft.addRecipe(recipeToken), "sawmill rejected the recipe");
        f.stock(0, new ItemStack(Items.OAK_LOG, 8));

        final IToken<?> planks = ask(f.builder, new ItemStack(Items.OAK_PLANKS, 4));
        helper.assertTrue(f.isRetrying(planks), "no worker in the sawmill: the request waits at the retrying resolver");
        if (smart)
        {
            helper.assertTrue(f.request(planks).getWaitReason() == WaitReason.NO_CRAFTER, "named: " + f.request(planks).getWaitReason());
            helper.assertTrue(f.tracker().notifier().sentCount() == 1, "the player is told once to assign a worker: " + f.tracker().notifier().recent());
        }

        helper.runAfterDelay(200, () -> {
            final ICitizenData worker = f.spawn(new BlockPos(10, 1, 28), true);
            final long start = f.level.getGameTime();
            helper.assertTrue(sawmill.getModule(BuildingModules.SAWMILL_WORK).assignCitizen(worker), "worker not assigned");
            helper.succeedWhen(() -> {
                final IRequest<?> r = f.request(planks);
                helper.assertTrue(r.hasChildren() || r.getState() == RequestState.FOLLOWUP_IN_PROGRESS, "no crafting yet: " + f.describe(planks) + " sawmill: " + f.resolversSay(sawmill, planks)
                                                                                                                     + " workers=" + sawmill.getModule(BuildingModules.SAWMILL_WORK).getAssignedCitizen().size()
                                                                                                                     + " recipes=" + craft.getRecipes().size()
                                                                                                                     + " first=" + (craft.getFirstRecipe(stack -> stack.is(Items.OAK_PLANKS)) != null));
                final long ticks = f.level.getGameTime() - start;
                logMetric("crafter_hired_crafting_started", smart, ticks);
                if (smart)
                {
                    helper.assertTrue(ticks <= 80, "crafting started within 80 ticks of the worker arriving: " + ticks);
                }
            });
        });
    }

    // ------------------------------------------------------------------ courier

    /**
     * A courier that does not get anywhere gives its task up; the delivery fails, its promises end, and the request is served again.
     */
    public static void stuckCourierGivesUp(final GameTestHelper helper)
    {
        flags(true);
        final RsFixture f = RsFixture.found(helper, "rs-stuck", 4);
        final IBuilding deliveryman = f.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");
        helper.runAfterDelay(200, () -> {
            final ICitizenData courier = f.spawn(new BlockPos(34, 1, 8), true);
            helper.assertTrue(deliveryman.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier), "courier not assigned to the courier hut");
            helper.assertTrue(f.warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "courier not assigned to the warehouse");
            f.stock(0, new ItemStack(Items.OAK_LOG, 10));
            final IToken<?> parent = ask(f.builder, new ItemStack(Items.OAK_LOG, 8));
            final IToken<?> delivery = f.request(parent).getChildren().iterator().next();
            final JobDeliveryman job = courier.getJob(JobDeliveryman.class);
            final ReservationLedger ledger = f.ledger();
            helper.assertTrue(ledger.reservedStock(f.warehouse.getID(), LOGS) == 8, "fixture: the delivery holds 8 logs");

            helper.assertTrue(job.getCurrentTask() != null && job.getCurrentTask().getId().equals(delivery), "the courier took the delivery");
            final long taken = f.level.getGameTime();
            final net.minecraft.world.phys.Vec3 spot = courier.getEntity().get().position();
            helper.succeedWhen(() -> {
                // Nobody drives the courier: it does not move. Looking at the task is what its AI does every few ticks; the
                // citizen counts as working (the idle limit would otherwise give the task up first).
                courier.setWorking(true);
                // The courier's own AI tries to walk to the rack; whatever it does, it ends up where it stood (a blocked path).
                courier.getEntity().get().setPos(spot.x, spot.y, spot.z);
                job.getCurrentTask();
                final long waited = f.level.getGameTime() - taken;
                helper.assertTrue(waited > CourierWatchdog.STUCK_TICKS, "not stuck long enough yet: " + waited);
                final IRequest<?> failed = f.request(delivery);
                helper.assertTrue(failed == null || failed.getState() == RequestState.FAILED || failed.getState() == RequestState.CANCELLED, "the delivery was given up: " + (failed == null ? "gone" : failed.getState()));
                helper.assertTrue(f.tracker().notifier().recent().stream().anyMatch(m -> m.startsWith("COURIER_STUCK")), "the player was told: " + f.tracker().notifier().recent());
                helper.assertTrue(ledger.reservationsOf(delivery).isEmpty(), "the failed delivery's promises ended");
                final IRequest<?> served = f.request(parent);
                helper.assertTrue(served != null && served.getState() == RequestState.FOLLOWUP_IN_PROGRESS && !served.getChildren().contains(delivery), "the request is served by a new delivery");
                Log.getLogger().info("RS_METRIC courier_stuck_task_given_up flags=on ticks={}", waited);
            });
        });
    }
}
