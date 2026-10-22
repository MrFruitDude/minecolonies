package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.ICraftingBuildingModule;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.managers.interfaces.IStatisticsManager;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.crafting.RecipeStorage;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.api.util.constant.StatisticsConstants;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.jobs.AbstractJob;
import com.minecolonies.core.colony.jobs.AbstractJobCrafter;
import com.minecolonies.core.colony.labour.LabourBoard;
import com.minecolonies.core.entity.ai.AiCounters;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic;
import com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder;
import com.minecolonies.core.entity.ai.workers.crafting.AbstractEntityAICrafting;
import com.minecolonies.core.entity.ai.workers.guard.AbstractEntityAIGuard;
import com.minecolonies.core.entity.ai.workers.production.EntityAIStructureMiner;
import com.minecolonies.core.entity.ai.workers.production.agriculture.EntityAIWorkFarmer;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import com.minecolonies.core.entity.ai.workers.util.BuildingProgressStage;
import com.minecolonies.core.entity.ai.workers.util.WorkerSettings;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Round 13 lane mc-work: worker loops (dumps, pickups, crafter queue and gathering, guard patrol) and the AI counters. Group {@code r13work}
 * (glob {@code minecolonies:r13w_*}).
 */
public final class Round13WorkGameTests
{
    private Round13WorkGameTests()
    {
    }

    static void register(final RegisterGameTestsEvent event)
    {
        final String[] names = {"dump_all_one_call", "dump_full_hut_keeps", "pickup_all_requests", "crafter_queue", "crafter_gathers_all", "guard_patrol_target", "rules", "counters_api",
          "pickup_full_inventory", "cook_staggered_furnaces", "smelter_request", "smelter_cancel", "cook_serve_keeps_food"};
        final List<Consumer<GameTestHelper>> bodies = List.of(Round13WorkGameTests::dumpAllInOneCall, Round13WorkGameTests::dumpFullHutKeeps, Round13WorkGameTests::pickupAllRequests,
          Round13WorkGameTests::crafterQueue, Round13WorkGameTests::crafterGathersAll, Round13WorkGameTests::guardPatrolTarget, Round13WorkGameTests::rules,
          Round13WorkGameTests::countersApi, Round13WorkGameTests::pickupFullInventory, Round13WorkGameTests::cookStaggeredFurnaces, Round13WorkGameTests::smelterRequest,
          Round13WorkGameTests::smelterCancel, Round13WorkGameTests::cookServeKeepsFood);
        for (int i = 0; i < names.length; i++)
        {
            final String name = "r13w_" + names[i];
            final Consumer<GameTestHelper> body = bodies.get(i);
            event.registerTest(Identifier.fromNamespaceAndPath(Constants.MOD_ID, name), info -> new MinecoloniesGameTestInstance(info, body),
              MinecoloniesGameTestRegistrar.isolatedColonyData(event, name));
        }
    }

    @Nullable
    static AbstractEntityAIBasic<?, ?> workAi(final ICitizenData citizen)
    {
        final Object ai = citizen.getEntity().map(e -> (Object) e.getCitizenJobHandler().getWorkAI()).orElse(null);
        return ai instanceof AbstractEntityAIBasic<?, ?> basic ? basic : null;
    }

    static AiCounters.JobSnapshot jobSnapshot(final IColony colony, final ICitizenData citizen)
    {
        final String key = citizen.getJob().getJobRegistryEntry().getKey().toString();
        final AiCounters.JobSnapshot snap = AiCounters.snapshot(colony).jobs().get(key);
        return snap == null ? new AiCounters.JobSnapshot(Map.of(), 0, 0, 0, 0, 0, 0) : snap;
    }

    private static int skippedPickupDumps(final ICitizenData citizen)
    {
        return workAi(citizen) instanceof EntityAIStructureBuilder builder ? builder.getSkippedPickupDumps() : 0;
    }

    private static int finishedWithoutDump(final ICitizenData citizen)
    {
        return workAi(citizen) instanceof AbstractEntityAICrafting<?, ?> crafter ? crafter.getFinishedWithoutDump() : 0;
    }

    static int count(final ICitizenData citizen, final Item item)
    {
        return InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), stack -> stack.is(item));
    }

    // ------------------------------------------------------------------ builder at work

    /**
     * The builder of the scene at work on a 9 by 4 wall (a builder without an order is idle and his work AI does not run). His bricks are in
     * his hut, or with him when {@code carry}.
     */
    private static void atWork(final WorkerEfficiencyGameTests.Live live, final String name, final boolean carry)
    {
        atWork(live, name, carry, () -> {});
    }

    /**
     * {@link #atWork}, with {@code beforeBricks} run after the tools are given and before the bricks are.
     */
    private static void atWork(final WorkerEfficiencyGameTests.Live live, final String name, final boolean carry, final Runnable beforeBricks)
    {
        WorkerSettings.overrideReachForTests(null, true, false, 0);
        live.setSkills(1, 1);
        live.giveTools();
        beforeBricks.run();
        final Blueprint wall = BuilderScaffoldGameTests.shape(name, 9, 4, 1, (x, y, z) -> true);
        final int bricks = BuilderScaffoldGameTests.solidCount(wall) + WorkerEfficiencyGameTests.SPARE;
        if (carry)
        {
            InventoryUtils.addItemStackToItemHandler(live.citizen.getInventory(), new ItemStack(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), 64));
        }
        else
        {
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), bricks);
        }
        live.orderFor(name, BuilderScaffoldGameTests.SITE, wall);
    }

    /**
     * Fills the slots from {@code first} with 64 of an item.
     */
    private static void fill(final ICitizenData citizen, final int first, final int count, final Item item)
    {
        for (int slot = first; slot < first + count; slot++)
        {
            citizen.getInventory().insertItem(slot, new ItemStack(item, 64), false);
        }
    }

    // ------------------------------------------------------------------ W1, B7

    /**
     * W1: a dump due (the action count) moves every stack into the hut in one AI step after arrival (it was one slot per 20 ticks). B7: the last
     * slot is filled and the one before it empty (the old skip loop read past the end there). The counters see the dump and the state ticks.
     */
    public static void dumpAllInOneCall(final GameTestHelper helper)
    {
        WorkerEfficiencyGameTests.scene(helper, "R13 dump all", live -> {
            atWork(live, "r13_dump_all", false);
            final int slots = live.citizen.getInventory().getSlots();
            final int firstFree = firstFreeSlot(live.citizen);
            fill(live.citizen, firstFree, 10, Items.DIRT);
            live.citizen.getInventory().insertItem(slots - 1, new ItemStack(Items.DIRT, 32), false);
            helper.assertTrue(live.citizen.getInventory().getStackInSlot(slots - 2).isEmpty(), "fixture: the slot before the last is not empty");
            final int dirt0 = count(live.citizen, Items.DIRT);
            helper.assertTrue(dirt0 == 672, "fixture: dirt " + dirt0);
            final AiCounters.JobSnapshot before = jobSnapshot(live.colony, live.citizen);
            ((AbstractJob<?, ?>) live.citizen.getJob()).incrementActionsDone(64);
            WorkerEfficiencyGameTests.everyTick(live, 1200, tick -> count(live.citizen, Items.DIRT) == 0, () -> {
                final AbstractEntityAIBasic<?, ?> ai = workAi(live.citizen);
                final AiCounters.JobSnapshot after = jobSnapshot(live.colony, live.citizen);
                final int inHut = InventoryUtils.getItemCountInItemHandler(live.hut.getItemHandlerCap(), stack -> stack.is(Items.DIRT));
                Log.getLogger().info("R13W dump_all calls={} dumps {}->{} stacks {}->{} inHut={} ticks {}->{} {}", ai == null ? -1 : ai.getLastDumpCalls(), before.dumps(), after.dumps(),
                  before.dumpedStacks(), after.dumpedStacks(), inHut, before.totalTicks(), after.totalTicks(), after.stateTicks());
                helper.assertTrue(ai != null && ai.getLastDumpCalls() == 1, "the dump took more than one step after arrival: " + (ai == null ? "no ai" : ai.getLastDumpCalls()));
                helper.assertTrue(inHut == dirt0, "items were lost or kept: in hut " + inHut + " of " + dirt0);
                helper.assertTrue(after.dumps() > before.dumps() && after.dumpedStacks() - before.dumpedStacks() >= 11,
                  "the counters did not see the dump of 11 stacks: " + before + " -> " + after);
                helper.assertTrue(after.totalTicks() > before.totalTicks(), "no state ticks counted for the builder");
                WorkerSettings.clearOverrides();
                helper.succeed();
            }, () -> "dirt left " + count(live.citizen, Items.DIRT) + " " + live.ai());
        });
    }

    private static int firstFreeSlot(final ICitizenData citizen)
    {
        for (int slot = 0; slot < citizen.getInventory().getSlots(); slot++)
        {
            if (citizen.getInventory().getStackInSlot(slot).isEmpty())
            {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Extra E1: the hut fills up during a dump: the stack it cannot take stays with the worker (the old dump dropped the rest of the stack).
     * The sawmill worker with nothing to craft dumps three stacks of dirt into a hut with one free slot.
     */
    public static void dumpFullHutKeeps(final GameTestHelper helper)
    {
        crafterScene(helper, "R13 dump full hut", (colony, worker) -> {
            final IBuilding sawmill = worker.getWorkBuilding();
            final var cap = sawmill.getItemHandlerCap();
            for (int slot = 0; slot < cap.getSlots(); slot++)
            {
                cap.insertItem(slot, new ItemStack(Items.COBBLESTONE, 64), false);
            }
            // one free slot
            cap.extractItem(0, 64, false);
            helper.assertTrue(InventoryUtils.openSlotCount(cap) == 1, "fixture: the hut should have exactly one free slot, has " + InventoryUtils.openSlotCount(cap));
            fill(worker, firstFreeSlot(worker), 3, Items.DIRT);
            final long dumps0 = jobSnapshot(colony, worker).dumps();
            ((AbstractJob<?, ?>) worker.getJob()).incrementActionsDone(1);
            poll(helper, worker, 2000, tick -> jobSnapshot(colony, worker).dumps() > dumps0, () -> {
                final int inHut = InventoryUtils.getItemCountInItemHandler(cap, stack -> stack.is(Items.DIRT));
                final int carried = count(worker, Items.DIRT);
                Log.getLogger().info("R13W dump_full_hut inHut={} carried={}", inHut, carried);
                helper.assertTrue(inHut + carried == 192, "dirt was lost in the dump: hut " + inHut + " + carried " + carried + " of 192");
                helper.assertTrue(inHut == 64, "the free slot was not used: " + inHut);
                helper.succeed();
            }, () -> "no dump finished " + crafterState(worker));
        });
    }

    // ------------------------------------------------------------------ W6, W7

    /**
     * W6: three completed requests at the hut are taken in one visit (it was one per 40 ticks). W7: after that pickup the builder (past the
     * clearing stage) does not dump everything again.
     */
    public static void pickupAllRequests(final GameTestHelper helper)
    {
        WorkerEfficiencyGameTests.scene(helper, "R13 pickup all", live -> {
            atWork(live, "r13_pickup_all", false);
            final IRequestManager manager = live.colony.getRequestManager();
            final List<Item> items = List.of(Items.DIRT, Items.SAND, Items.GRAVEL);
            final AiCounters.JobSnapshot[] before = {null};
            final int[] skipped0 = {0};
            final int[] madeAt = {-1};
            final int[] doneAt = {-1};
            final long[] dumpsAtPickup = {-1};
            WorkerEfficiencyGameTests.everyTick(live, 3000, tick -> {
                final var progress = live.hut.getProgress();
                if (madeAt[0] < 0 && progress != null && progress.getB() != BuildingProgressStage.CLEAR)
                {
                    // past the clearing: three requests, completed at once, their goods in the hut
                    madeAt[0] = tick;
                    final List<IRequest<?>> requests = new ArrayList<>();
                    for (final Item item : items)
                    {
                        final IToken<?> token = live.hut.createRequest(live.citizen, new Stack(new ItemStack(item), 16, 16), false);
                        final IRequest<?> request = manager.getRequestForToken(token);
                        helper.assertTrue(request != null, "request not created for " + item);
                        request.addDelivery(new ItemStack(item, 16));
                        requests.add(request);
                    }
                    for (final IRequest<?> request : requests)
                    {
                        live.hut.onRequestedRequestComplete(manager, request);
                    }
                    for (final Item item : items)
                    {
                        live.stock(item, 16);
                    }
                    helper.assertTrue(live.hut.getCompletedRequestsOfCitizenOrBuilding(live.citizen).size() == 3, "fixture: not three completed requests");
                    before[0] = jobSnapshot(live.colony, live.citizen);
                    skipped0[0] = skippedPickupDumps(live.citizen);
                    return false;
                }
                if (madeAt[0] >= 0 && doneAt[0] < 0 && live.hut.getCompletedRequestsOfCitizenOrBuilding(live.citizen).isEmpty())
                {
                    doneAt[0] = tick;
                    dumpsAtPickup[0] = jobSnapshot(live.colony, live.citizen).dumps();
                    // (the actions of the wall do not start a dump in the next ticks)
                    ((AbstractJob<?, ?>) live.citizen.getJob()).clearActionsDone();
                    for (final Item item : items)
                    {
                        helper.assertTrue(count(live.citizen, item) >= 16, "the builder did not take the delivered " + item);
                    }
                }
                return doneAt[0] >= 0 && tick - doneAt[0] >= 60;
            }, () -> {
                final AbstractEntityAIBasic<?, ?> ai = workAi(live.citizen);
                final AiCounters.JobSnapshot after = jobSnapshot(live.colony, live.citizen);
                Log.getLogger().info("R13W pickup_all madeAt={} doneAt={} last={} pickups {}->{} requests {}->{} skippedDumps={} dumps {}->{}", madeAt[0], doneAt[0],
                  ai == null ? -1 : ai.getLastPickupRequests(), before[0].pickups(), after.pickups(), before[0].pickupRequests(), after.pickupRequests(),
                  skippedPickupDumps(live.citizen) - skipped0[0], dumpsAtPickup[0], after.dumps());
                // (the builder may have completed requests of his own at the hut: they come along in the same visit)
                helper.assertTrue(after.pickups() - before[0].pickups() == 1 && after.pickupRequests() - before[0].pickupRequests() >= 3,
                  "the three requests were not taken in one visit: " + before[0] + " -> " + after);
                helper.assertTrue(skippedPickupDumps(live.citizen) > skipped0[0], "the builder did not skip the dump after the pickup");
                helper.assertTrue(after.dumps() == dumpsAtPickup[0], "the builder dumped right after the pickup: " + dumpsAtPickup[0] + " -> " + after.dumps());
                WorkerSettings.clearOverrides();
                helper.succeed();
            }, () -> "madeAt=" + madeAt[0] + " completed left " + live.hut.getCompletedRequestsOfCitizenOrBuilding(live.citizen).size() + " progress=" + live.hut.getProgress() + " " + live.ai());
        });
    }

    // ------------------------------------------------------------------ crafter fixture

    /**
     * A colony with a level 1 sawmill and a builder hut, a live sawmill worker and a close subscriber (the colony runs), as the crafter fixture
     * of the hook tests.
     */
    private static void crafterScene(final GameTestHelper helper, final String name, final BiConsumer<IColony, ICitizenData> body)
    {
        workerScene(helper, name, ModBlocks.blockHutSawmill, "craftsmanship/carpentry/sawmill1.blueprint",
          (sawmill, worker) -> sawmill.getModule(BuildingModules.SAWMILL_WORK).assignCitizen(worker), (colony, sawmill, worker) -> {
              helper.assertTrue(workAi(worker) instanceof AbstractEntityAICrafting<?, ?>, "sawmill worker has no crafter AI: " + workAi(worker));
              body.accept(colony, worker);
          });
    }

    /**
     * The body of a {@link #workerScene}.
     */
    private interface SceneBody
    {
        void run(IColony colony, IBuilding hut, ICitizenData worker);
    }

    /**
     * A colony with a level 1 hut at (20, 1, 2), a live worker assigned to it and a close subscriber (the colony runs).
     */
    private static void workerScene(final GameTestHelper helper, final String name, final net.minecraft.world.level.block.Block hutBlock, final String blueprint,
      final java.util.function.BiPredicate<IBuilding, ICitizenData> assign, final SceneBody body)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, name);
        final IBuilding sawmill = MinecoloniesGameTests.placeProductionBuilding(helper, colony, hutBlock, new BlockPos(20, 1, 2), blueprint);
        final BlockPos anchor = helper.absolutePos(new BlockPos(22, 1, 10));
        level.setChunkForced(anchor.getX() >> 4, anchor.getZ() >> 4, true);
        final ServerPlayer player = MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper);
        player.teleportTo(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D);
        colony.getPackageManager().addCloseSubscriber(player);
        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = colony.getCitizenManager().spawnOrCreateCivilian(null, level, List.of(anchor), true);
            helper.assertTrue(worker != null && worker.getEntity().isPresent(), name + ": citizen did not spawn");
            helper.assertTrue(assign.test(sawmill, worker), name + ": assignment failed");
            worker.getEntity().get().setPos(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D);
            final int[] waited = {0};
            final Runnable[] untilActive = new Runnable[1];
            untilActive[0] = () -> {
                if (colony.isActive())
                {
                    body.run(colony, sawmill, worker);
                    return;
                }
                if (++waited[0] > 400)
                {
                    throw helper.assertionException("fixture: colony never became active with a close subscriber");
                }
                worker.setSaturation(20);
                helper.runAfterDelay(1, () -> untilActive[0].run());
            };
            untilActive[0].run();
        });
    }

    private static void teach(final GameTestHelper helper, final IBuilding sawmill, final List<ItemStorage> inputs, final ItemStack output)
    {
        final ICraftingBuildingModule craft = sawmill.getModule(BuildingModules.SAWMILL_CRAFT);
        final RecipeStorage recipe = RecipeStorage.builder().withRecipeId(null).withInputs(inputs).withPrimaryOutput(output).build();
        helper.assertTrue(craft.addRecipe(IColonyManager.getInstance().getRecipeManager().checkOrAddRecipe(recipe)), "the sawmill rejected the recipe for " + output);
    }

    private static void put(final GameTestHelper helper, final IBuilding building, final Item item, final int amount)
    {
        final ItemStack left = InventoryUtils.forceItemStackToItemHandler(building.getItemHandlerCap(), new ItemStack(item, amount), stack -> true);
        helper.assertTrue(left == null || left.isEmpty(), "no room for " + item);
    }

    private static int crafted(final IBuilding sawmill, final Item item)
    {
        final IStatisticsManager stats = sawmill.getModule(BuildingModules.STATS_MODULE).getBuildingStatisticsManager();
        return stats.getStatTotal(StatisticsConstants.ITEMS_CRAFTED_DETAIL + ";" + new ItemStack(item).getHoverName().getString());
    }

    /**
     * Polls every tick up to {@code max} ticks.
     */
    private static void poll(final GameTestHelper helper, final ICitizenData worker, final int max, final java.util.function.IntPredicate step, final Runnable done,
      final java.util.function.Supplier<String> failure)
    {
        final int[] elapsed = {0};
        final Runnable[] next = new Runnable[1];
        next[0] = () -> {
            elapsed[0]++;
            worker.setSaturation(20);
            if (step.test(elapsed[0]))
            {
                done.run();
            }
            else if (elapsed[0] >= max)
            {
                helper.fail("timeout after " + max + " ticks: " + failure.get());
            }
            else
            {
                helper.runAfterDelay(1, () -> next[0].run());
            }
        };
        helper.runAfterDelay(1, () -> next[0].run());
    }

    private static String crafterState(final ICitizenData worker)
    {
        final AbstractEntityAIBasic<?, ?> ai = workAi(worker);
        return ai == null ? "no-ai" : ai.getState() + " queue=" + ((AbstractJobCrafter<?, ?>) worker.getJob()).getTaskQueue().size() + " history=" + ai.getStateAI().getHistory().getString();
    }

    // ------------------------------------------------------------------ W3, W4

    /**
     * W3: an idle crafter starts within a few seconds when tasks come in (it slept 20 s blind). W4: with more tasks queued he puts the outputs
     * of a finished task into the hut and goes on without a full dump; all requests are crafted.
     */
    public static void crafterQueue(final GameTestHelper helper)
    {
        crafterScene(helper, "R13 crafter queue", (colony, worker) -> {
            final IBuilding sawmill = worker.getWorkBuilding();
            final IBuilding builder = MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockHutBuilder, new BlockPos(8, 1, 2), "fundamentals/builder1.blueprint");
            teach(helper, sawmill, List.of(new ItemStorage(new ItemStack(Items.OAK_LOG, 2))), new ItemStack(Items.OAK_PLANKS, 4));
            put(helper, sawmill, Items.OAK_LOG, 6);
            final int without0 = finishedWithoutDump(worker);
            final int[] queuedAt = {-1};
            final int[] wokeAt = {-1};
            final int[] maxQueue = {0};
            // idle first: he sits out his idle steps
            helper.runAfterDelay(200, () -> {
                final String idleState = crafterState(worker);
                for (int i = 0; i < 3; i++)
                {
                    builder.createRequest(new Stack(new ItemStack(Items.OAK_PLANKS, 4), 4, 4), true);
                }
                poll(helper, worker, 3000, tick -> {
                    final AbstractEntityAIBasic<?, ?> ai = workAi(worker);
                    final int queue = ((AbstractJobCrafter<?, ?>) worker.getJob()).getTaskQueue().size();
                    maxQueue[0] = Math.max(maxQueue[0], queue);
                    if (queuedAt[0] < 0 && queue > 0)
                    {
                        queuedAt[0] = tick;
                    }
                    if (queuedAt[0] >= 0 && wokeAt[0] < 0 && ai != null && ai.getState() != AIWorkerState.IDLE)
                    {
                        wokeAt[0] = tick;
                    }
                    return crafted(sawmill, Items.OAK_PLANKS) >= 12;
                }, () -> {
                    final int without = finishedWithoutDump(worker) - without0;
                    Log.getLogger().info("R13W crafter_queue idleState={} queuedAt={} wokeAt={} maxQueue={} withoutDump={} crafted={} {}", idleState, queuedAt[0], wokeAt[0],
                      maxQueue[0], without, crafted(sawmill, Items.OAK_PLANKS), crafterState(worker));
                    helper.assertTrue(wokeAt[0] >= 0 && wokeAt[0] - queuedAt[0] <= 100, "the idle crafter did not start within 100 ticks of the task: queued " + queuedAt[0]
                                                                                          + " woke " + wokeAt[0]);
                    helper.assertTrue(maxQueue[0] >= 2, "fixture: never two tasks queued at once (" + maxQueue[0] + ")");
                    helper.assertTrue(without >= 1, "every task ended with a full dump");
                    helper.succeed();
                }, () -> "crafted " + crafted(sawmill, Items.OAK_PLANKS) + " queuedAt=" + queuedAt[0] + " wokeAt=" + wokeAt[0] + " " + crafterState(worker));
            });
        });
    }

    // ------------------------------------------------------------------ W5

    /**
     * W5: a recipe with two inputs the crafter does not carry: both are gathered in one pass (the second waits in the queue of the pass).
     */
    public static void crafterGathersAll(final GameTestHelper helper)
    {
        crafterScene(helper, "R13 crafter gathers", (colony, worker) -> {
            final IBuilding sawmill = worker.getWorkBuilding();
            final IBuilding builder = MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockHutBuilder, new BlockPos(8, 1, 2), "fundamentals/builder1.blueprint");
            teach(helper, sawmill, List.of(new ItemStorage(new ItemStack(Items.OAK_PLANKS, 4)), new ItemStorage(new ItemStack(Items.STICK, 2))), new ItemStack(Items.OAK_FENCE, 3));
            put(helper, sawmill, Items.OAK_PLANKS, 4);
            put(helper, sawmill, Items.STICK, 2);
            builder.createRequest(new Stack(new ItemStack(Items.OAK_FENCE, 3), 3, 3), true);
            final int[] maxPending = {0};
            poll(helper, worker, 3000, tick -> {
                final AbstractEntityAIBasic<?, ?> ai = workAi(worker);
                if (ai != null)
                {
                    maxPending[0] = Math.max(maxPending[0], ai.pendingNeedCount());
                }
                return crafted(sawmill, Items.OAK_FENCE) >= 3;
            }, () -> {
                Log.getLogger().info("R13W crafter_gathers maxPending={} crafted={} {}", maxPending[0], crafted(sawmill, Items.OAK_FENCE), crafterState(worker));
                helper.assertTrue(maxPending[0] >= 1, "the two inputs were not gathered in one pass");
                helper.succeed();
            }, () -> "crafted " + crafted(sawmill, Items.OAK_FENCE) + " maxPending=" + maxPending[0] + " " + crafterState(worker));
        });
    }

    // ------------------------------------------------------------------ W10

    /**
     * W10: the random patrol point searched for the next leg is taken as soon as its search is done (it waited for the next leg change).
     */
    public static void guardPatrolTarget(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "R13 guard patrol");
        final IBuilding tower = MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockHutGuardTower, new BlockPos(8, 1, 2), "military/guardtower1.blueprint");
        final BlockPos anchor = helper.absolutePos(new BlockPos(8, 1, 8));
        WorkerEfficiencyGameTests.forceArea(level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(40, 1, 32)));
        helper.runAfterDelay(20, () -> {
            final ICitizenData guard = colony.getCitizenManager().spawnOrCreateCivilian(null, level, List.of(anchor), true);
            helper.assertTrue(guard != null && guard.getEntity().isPresent(), "guard citizen did not spawn");
            helper.assertTrue(tower.getModule(BuildingModules.KNIGHT_TOWER_WORK).assignCitizen(guard), "knight assignment failed");
            guard.getEntity().get().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            final AbstractBuildingGuards guards = (AbstractBuildingGuards) tower;
            final BlockPos first = guards.getNextPatrolTarget(false);
            helper.assertTrue(first != null, "no first patrol point");
            final int early0 = guards.getPatrolTargetsTakenEarly();
            final int[] elapsed = {0};
            final Runnable[] next = new Runnable[1];
            next[0] = () -> {
                elapsed[0]++;
                if (elapsed[0] % 40 == 1)
                {
                    // a new leg: half of the time it starts a search for a random point
                    guards.getNextPatrolTarget(true);
                }
                guards.getNextPatrolTarget(false);
                if (guards.getPatrolTargetsTakenEarly() > early0)
                {
                    Log.getLogger().info("R13W guard_patrol taken after {} ticks: {} -> {}", elapsed[0], first, guards.getNextPatrolTarget(false));
                    // review c1: the early take respects manual patrol with points set
                    helper.assertTrue(guards.randomPatrolAllowed(), "random patrol not allowed in auto mode");
                    guards.getSetting(AbstractBuildingGuards.PATROL_MODE).set(com.minecolonies.core.colony.buildings.modules.settings.GuardPatrolModeSetting.MANUAL);
                    helper.assertTrue(guards.randomPatrolAllowed(), "random patrol not allowed in manual mode without points");
                    guards.addPatrolTarget(anchor);
                    helper.assertTrue(!guards.randomPatrolAllowed(), "random patrol allowed in manual mode with points");
                    guards.getSetting(AbstractBuildingGuards.PATROL_MODE).set(com.minecolonies.core.colony.buildings.modules.settings.GuardPatrolModeSetting.AUTO);
                    guards.resetPatrolTargets();
                    helper.succeed();
                }
                else if (elapsed[0] > 1200)
                {
                    helper.fail("no searched patrol point was taken before the next leg in 1200 ticks");
                }
                else
                {
                    helper.runAfterDelay(1, () -> next[0].run());
                }
            };
            helper.runAfterDelay(1, () -> next[0].run());
        });
    }

    // ------------------------------------------------------------------ rules

    /**
     * The decision rules of round 13 (no world needed): B3 back-off, W2 rests, W4 dump ratio, W8 gatherer and farmer dumps, W10 sleep, W11
     * requests ahead.
     */
    public static void rules(final GameTestHelper helper)
    {
        final List<String> failures = new ArrayList<>();
        // B3: doubling, capped at 64, never overflowing, and 1 again after 5 quiet minutes
        int timer = 1;
        final List<Integer> used = new ArrayList<>();
        for (int error = 0; error < 40; error++)
        {
            timer = AbstractEntityAIBasic.exceptionBackoff(timer, 100L);
            used.add(timer);
            timer = Math.min(timer * 2, AbstractEntityAIBasic.EXCEPTION_BACKOFF_CAP);
        }
        if (used.get(0) != 1 || used.get(1) != 2 || used.get(6) != 64 || used.get(39) != 64 || used.stream().anyMatch(t -> t < 1 || t > 64))
        {
            failures.add("B3 back-off ladder " + used);
        }
        if (AbstractEntityAIBasic.exceptionBackoff(64, AbstractEntityAIBasic.EXCEPTION_QUIET_RESET) != 1 || AbstractEntityAIBasic.exceptionBackoff(64, Long.MAX_VALUE) != 1
              || AbstractEntityAIBasic.exceptionBackoff(32, AbstractEntityAIBasic.EXCEPTION_QUIET_RESET - 1) != 32)
        {
            failures.add("B3 reset after a quiet spell");
        }
        // B3 (review c1): an AI that fails again one tick after every pause climbs to the cap and holds there (the 6400 tick pause at the cap
        // does not count as quiet time), and starts again at 1 only after 6000 quiet ticks past the end of a pause
        long pauseEnd = -1L;
        int multiplier = 1;
        final List<Integer> ladder = new ArrayList<>();
        final List<Integer> pauses = new ArrayList<>();
        for (int error = 0; error < 12; error++)
        {
            final long now = pauseEnd < 0 ? 1000L : pauseEnd + 1;
            final AbstractEntityAIBasic.ExceptionStep step = AbstractEntityAIBasic.exceptionStep(multiplier, pauseEnd, now, true);
            ladder.add(step.used());
            pauses.add(step.pause());
            if (step.pauseEnd() != now + step.pause())
            {
                failures.add("B3 pause end " + step);
            }
            pauseEnd = step.pauseEnd();
            multiplier = step.next();
        }
        if (!ladder.equals(List.of(1, 2, 4, 8, 16, 32, 64, 64, 64, 64, 64, 64)) || pauses.get(11) != 6400)
        {
            failures.add("B3 constant failure ladder " + ladder + " pauses " + pauses);
        }
        if (AbstractEntityAIBasic.exceptionStep(multiplier, pauseEnd, pauseEnd + AbstractEntityAIBasic.EXCEPTION_QUIET_RESET - 1, true).used() != 64
              || AbstractEntityAIBasic.exceptionStep(multiplier, pauseEnd, pauseEnd + AbstractEntityAIBasic.EXCEPTION_QUIET_RESET, true).used() != 1
              || AbstractEntityAIBasic.exceptionStep(1, -1L, 5L, true).used() != 1
              || AbstractEntityAIBasic.exceptionStep(multiplier, pauseEnd, pauseEnd + 1, false).pause() != 100)
        {
            failures.add("B3 quiet reset from the end of the pause");
        }
        // builder keep (review c1 note): the part of a stack over the need is dumped (it was a negative number, so nothing)
        if (com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder.amountOverKeep(10, 20, 16) != 14
              || com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder.amountOverKeep(0, 20, 16) != 4
              || com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder.amountOverKeep(10, 4, 16) != 0)
        {
            failures.add("builder keep math");
        }
        // W4 furnace users: a quarter stack of products
        if (com.minecolonies.core.entity.ai.workers.AbstractEntityAIUsesFurnace.PRODUCT_DUMP_ITEMS != 16)
        {
            failures.add("W4 product threshold");
        }
        // W4 (review r13 Medium 1): only furnace output counts, never with a full hut or during the backoff
        if (com.minecolonies.core.entity.ai.workers.AbstractEntityAIUsesFurnace.productDumpDue(0, 24, false)
              || !com.minecolonies.core.entity.ai.workers.AbstractEntityAIUsesFurnace.productDumpDue(16, 16, false)
              || com.minecolonies.core.entity.ai.workers.AbstractEntityAIUsesFurnace.productDumpDue(20, 10, false)
              || com.minecolonies.core.entity.ai.workers.AbstractEntityAIUsesFurnace.productDumpDue(20, 20, true))
        {
            failures.add("W4 product dump rule");
        }
        // W2
        if (LabourBoard.restAfter(true) != 40 || LabourBoard.restAfter(false) != 600)
        {
            failures.add("W2 rests " + LabourBoard.restAfter(true) + "/" + LabourBoard.restAfter(false));
        }
        // W4: more than 70% of the slots
        if (AbstractEntityAIBasic.overDumpRatio(25, 36) || !AbstractEntityAIBasic.overDumpRatio(26, 36) || AbstractEntityAIBasic.overDumpRatio(0, 36))
        {
            failures.add("W4 ratio");
        }
        // W8
        if (AbstractEntityAIBasic.gathererShouldDump(63) || !AbstractEntityAIBasic.gathererShouldDump(64))
        {
            failures.add("W8 gatherer threshold");
        }
        if (EntityAIWorkFarmer.farmerShouldDumpAfterField(1, 3, 10) || !EntityAIWorkFarmer.farmerShouldDumpAfterField(3, 3, 10)
              || EntityAIWorkFarmer.farmerShouldDumpAfterField(3, 3, 0) || !EntityAIWorkFarmer.farmerShouldDumpAfterField(1, 3, 64)
              || !EntityAIWorkFarmer.farmerShouldDumpAfterField(1, 0, 5))
        {
            failures.add("W8 farmer round");
        }
        // W10
        if (AbstractEntityAIGuard.maySleep(true, false) || !AbstractEntityAIGuard.maySleep(false, false) || AbstractEntityAIGuard.maySleep(false, true))
        {
            failures.add("W10 sleep rule");
        }
        // W11
        if (!EntityAIStructureMiner.shouldRequestAhead(3, EntityAIStructureMiner.LADDER_LOW_WATER, false)
              || EntityAIStructureMiner.shouldRequestAhead(3, EntityAIStructureMiner.LADDER_LOW_WATER, true)
              || EntityAIStructureMiner.shouldRequestAhead(EntityAIStructureMiner.LADDER_LOW_WATER, EntityAIStructureMiner.LADDER_LOW_WATER, false))
        {
            failures.add("W11 request ahead");
        }
        helper.assertTrue(failures.isEmpty(), "rules: " + failures);
        helper.succeed();
    }

    // ------------------------------------------------------------------ counters

    /**
     * The counters API: per colony and per job, a stable read-only snapshot, separate colonies in one world kept apart, reset of one colony only.
     */
    public static void countersApi(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony a = MinecoloniesGameTests.foundGameTestColony(helper, "R13 counters A");
        final BlockPos hallB = helper.absolutePos(new BlockPos(30, 1, 24));
        level.setChunkForced(hallB.getX() >> 4, hallB.getZ() >> 4, true);
        helper.setBlock(new BlockPos(30, 1, 24), ModBlocks.blockHutTownHall.defaultBlockState());
        final TileEntityColonyBuilding hut = (TileEntityColonyBuilding) level.getBlockEntity(hallB);
        hut.setPackName("Minecolonies Original");
        hut.setBlueprintPath("fundamentals/townhall1.blueprint");
        final IColony b = IColonyManager.getInstance().createColony(level, hallB, FakePlayerFactory.getMinecraft(level), "R13 counters B", "Minecolonies Original");
        helper.assertTrue(b != null && b.getID() != a.getID(), "fixture: no second colony");

        final AiCounters.JobCounters ja = AiCounters.job(a, "minecolonies:test_job");
        helper.assertTrue(ja != null && AiCounters.job(a, "minecolonies:test_job") == ja, "the job counters are not kept per colony and job");
        ja.addStateTicks("NEEDS_ITEM", 5);
        ja.addStateTicks("NEEDS_ITEM", 5);
        ja.addStateTicks("IDLE", 20);
        ja.hutWalk();
        ja.dump(7);
        ja.pickup(3);
        AiCounters.courierTrip(a, 4);
        AiCounters.courierTrip(a, 2);
        final AiCounters.Snapshot snap = AiCounters.snapshot(a);
        final AiCounters.JobSnapshot job = snap.jobs().get("minecolonies:test_job");
        final List<String> failures = new ArrayList<>();
        if (job == null || job.needsItemTicks() != 10 || job.stateTicks().get("IDLE") != 20 || job.totalTicks() != 30 || job.hutWalks() != 1 || job.dumps() != 1
              || job.dumpedStacks() != 7 || job.pickups() != 1 || job.pickupRequests() != 3)
        {
            failures.add("job snapshot " + job);
        }
        if (snap.courierTrips() != 2 || snap.courierRequests() != 6 || snap.requestsPerCourierTrip() != 3.0D || snap.colonyId() != a.getID())
        {
            failures.add("colony snapshot " + snap);
        }
        // the snapshot is a copy: later counts do not change it, and it cannot be changed
        ja.addStateTicks("IDLE", 20);
        if (snap.jobs().get("minecolonies:test_job").stateTicks().get("IDLE") != 20)
        {
            failures.add("the snapshot changed after a later count");
        }
        try
        {
            snap.jobs().clear();
            failures.add("the snapshot can be changed");
        }
        catch (final UnsupportedOperationException expected)
        {
            // read only
        }
        final Map<String, Object> map = snap.toMap();
        if (!map.containsKey("jobs") || !map.containsKey("requestsPerCourierTrip") || !(map.get("jobs") instanceof Map<?, ?>))
        {
            failures.add("toMap " + map.keySet());
        }
        // the other colony in the same world has its own counters
        if (AiCounters.snapshot(b).jobs().containsKey("minecolonies:test_job") || AiCounters.snapshot(b).courierTrips() != 0)
        {
            failures.add("colony B sees colony A's counters");
        }
        // review c1: off the server thread nothing is read or counted (the maps are not thread safe)
        final Object[] offThread = {null, "unset"};
        final Thread reader = new Thread(() -> {
            offThread[0] = AiCounters.snapshot(a);
            offThread[1] = AiCounters.job(a, "minecolonies:test_job");
        }, "r13w-counters-reader");
        reader.start();
        try
        {
            reader.join(5000);
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        if (!(offThread[0] instanceof AiCounters.Snapshot offSnap) || !offSnap.jobs().isEmpty() || offThread[1] != null)
        {
            failures.add("off-thread access was not refused: " + offThread[0] + " / " + offThread[1]);
        }
        final AiCounters.JobCounters jb = AiCounters.job(b, "minecolonies:test_job");
        jb.dump(1);
        AiCounters.reset(a);
        if (!AiCounters.snapshot(a).jobs().isEmpty() || AiCounters.snapshot(b).jobs().get("minecolonies:test_job").dumps() != 1)
        {
            failures.add("reset of A touched B or did not clear A");
        }
        IColonyManager.getInstance().deleteColonyByWorld(b.getID(), false, level);
        helper.assertTrue(failures.isEmpty(), "counters: " + failures);
        helper.succeed();
    }

    // ------------------------------------------------------------------ W6 with a full inventory

    /**
     * W6 (review c1): three completed requests at the hut and room for one stack: the first is taken, the loop stops, the other two stay
     * completed with their goods in the hut (never accepted without their items). With room again, the next visit takes both.
     */
    public static void pickupFullInventory(final GameTestHelper helper)
    {
        WorkerEfficiencyGameTests.scene(helper, "R13 pickup full", live -> {
            atWork(live, "r13_pickup_full", false);
            final AbstractEntityAIBasic<?, ?> ai = workAi(live.citizen);
            helper.assertTrue(ai != null, "no work AI");
            final IRequestManager manager = live.colony.getRequestManager();
            final List<Item> items = List.of(Items.SAND, Items.GRAVEL, Items.CLAY_BALL);
            final List<IRequest<?>> requests = new ArrayList<>();
            for (final Item item : items)
            {
                final IToken<?> token = live.hut.createRequest(live.citizen, new Stack(new ItemStack(item), 16, 16), false);
                final IRequest<?> request = manager.getRequestForToken(token);
                helper.assertTrue(request != null, "request not created for " + item);
                request.addDelivery(new ItemStack(item, 16));
                requests.add(request);
            }
            for (final IRequest<?> request : requests)
            {
                live.hut.onRequestedRequestComplete(manager, request);
            }
            for (final Item item : items)
            {
                live.stock(item, 16);
            }
            // every slot but one full
            final int slots = live.citizen.getInventory().getSlots();
            final List<Integer> filled = new ArrayList<>();
            for (int slot = 0; slot < slots; slot++)
            {
                if (live.citizen.getInventory().getStackInSlot(slot).isEmpty())
                {
                    filled.add(slot);
                }
            }
            helper.assertTrue(filled.size() >= 4, "fixture: too few free slots " + filled.size());
            final int free = filled.remove(filled.size() - 1);
            for (final int slot : filled)
            {
                live.citizen.getInventory().insertItem(slot, new ItemStack(Items.DIRT, 64), false);
            }
            final net.minecraft.world.level.block.entity.BlockEntity hutEntity = live.level.getBlockEntity(live.hut.getPosition());
            final int taken = ai.takeCompletedRequestsHere(hutEntity, requests);
            final var completed = live.hut.getCompletedRequestsOfCitizenOrBuilding(live.citizen);
            final List<String> failures = new ArrayList<>();
            if (taken != 1)
            {
                failures.add("taken " + taken + " (free slot " + free + ")");
            }
            for (int i = 0; i < items.size(); i++)
            {
                final Item item = items.get(i);
                final boolean accepted = !completed.contains(requests.get(i));
                final int carried = count(live.citizen, item);
                final int inHut = InventoryUtils.getItemCountInItemHandler(live.hut.getItemHandlerCap(), stack -> stack.is(item));
                if (accepted ? carried < 16 : (carried != 0 || inHut < 16))
                {
                    failures.add(item + " accepted=" + accepted + " carried=" + carried + " inHut=" + inHut);
                }
                if (i == 0 != accepted)
                {
                    failures.add(item + " accepted=" + accepted);
                }
            }
            // room again: the rest in the next visit
            for (int k = 0; k < 4; k++)
            {
                live.citizen.getInventory().extractItem(filled.get(k), 64, false);
            }
            final int takenLater = ai.takeCompletedRequestsHere(hutEntity, List.of(requests.get(1), requests.get(2)));
            if (takenLater != 2 || count(live.citizen, Items.GRAVEL) < 16 || count(live.citizen, Items.CLAY_BALL) < 16)
            {
                failures.add("second visit took " + takenLater);
            }
            Log.getLogger().info("R13W pickup_full taken={} later={} failures={}", taken, takenLater, failures);
            WorkerSettings.clearOverrides();
            helper.assertTrue(failures.isEmpty(), "pickup with a full inventory: " + failures);
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ W4 furnace users

    /**
     * Places furnaces at relative positions and registers them with the hut.
     */
    private static List<BlockPos> placeFurnaces(final GameTestHelper helper, final IBuilding hut, final BlockPos... relative)
    {
        final List<BlockPos> furnaces = new ArrayList<>();
        for (final BlockPos rel : relative)
        {
            helper.setBlock(rel, net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState());
            final BlockPos abs = helper.absolutePos(rel);
            hut.getModule(BuildingModules.FURNACE).onBlockPlacedInBuilding(helper.getLevel().getBlockState(abs), abs, helper.getLevel());
            furnaces.add(abs);
        }
        helper.assertTrue(hut.getModule(BuildingModules.FURNACE).getFurnaces().size() == relative.length, "fixture: furnaces not registered");
        return furnaces;
    }

    private static net.minecraft.world.level.block.entity.FurnaceBlockEntity furnace(final GameTestHelper helper, final BlockPos pos)
    {
        return (net.minecraft.world.level.block.entity.FurnaceBlockEntity) helper.getLevel().getBlockEntity(pos);
    }

    /**
     * W4 (review c1): a cook with two furnaces where one always has food ready (each is refilled as soon as he empties it) still brings the food
     * to the hut, after a quarter stack, long before his inventory is 70% full (c1 held it until then).
     */
    public static void cookStaggeredFurnaces(final GameTestHelper helper)
    {
        workerScene(helper, "R13 cook furnaces", ModBlocks.blockHutCook, "fundamentals/cook1.blueprint",
          (hut, worker) -> hut.getModule(BuildingModules.COOK_WORK).assignCitizen(worker), (colony, hut, worker) -> {
              hut.getModule(BuildingModules.ITEMLIST_FUEL).addItem(new ItemStorage(new ItemStack(Items.COAL)));
              final List<BlockPos> furnaces = placeFurnaces(helper, hut, new BlockPos(22, 1, 5), new BlockPos(25, 1, 5));
              final int[] refills = {0};
              final int[] maxSlots = {0};
              final int[] slotsAtDelivery = {-1};
              final int[] firstRefillTick = {-1};
              final int[] deliveredAt = {-1};
              poll(helper, worker, 2400, tick -> {
                  for (final BlockPos pos : furnaces)
                  {
                      final var furnace = furnace(helper, pos);
                      if (furnace.getItem(Constants.RESULT_SLOT).isEmpty())
                      {
                          furnace.setItem(Constants.RESULT_SLOT, new ItemStack(Items.COOKED_BEEF, 4));
                          refills[0]++;
                          if (firstRefillTick[0] < 0 && refills[0] > furnaces.size())
                          {
                              firstRefillTick[0] = tick;
                          }
                      }
                  }
                  int slots = 0;
                  for (int slot = 0; slot < worker.getInventory().getSlots(); slot++)
                  {
                      if (worker.getInventory().getStackInSlot(slot).is(Items.COOKED_BEEF))
                      {
                          slots++;
                      }
                  }
                  maxSlots[0] = Math.max(maxSlots[0], slots);
                  final int inHut = InventoryUtils.getItemCountInItemHandler(hut.getItemHandlerCap(), stack -> stack.is(Items.COOKED_BEEF));
                  if (inHut >= 16 && deliveredAt[0] < 0)
                  {
                      deliveredAt[0] = tick;
                      slotsAtDelivery[0] = maxSlots[0];
                  }
                  return deliveredAt[0] >= 0;
              }, () -> {
                  final boolean oneReady = furnaces.stream().anyMatch(pos -> !furnace(helper, pos).getItem(Constants.RESULT_SLOT).isEmpty());
                  Log.getLogger().info("R13W cook_furnaces refills={} firstRetrieve={} deliveredAt={} maxSlots={} oneReady={}", refills[0], firstRefillTick[0], deliveredAt[0],
                    slotsAtDelivery[0], oneReady);
                  helper.assertTrue(oneReady, "fixture: no furnace ready at the delivery");
                  helper.assertTrue(slotsAtDelivery[0] < 18, "the cook held the food until his inventory filled: " + slotsAtDelivery[0] + " slots of food");
                  helper.succeed();
              }, () -> "refills=" + refills[0] + " maxSlots=" + maxSlots[0] + " " + stateOf(worker));
          });
    }

    /**
     * W4 (review r13 Medium 1): a cook carrying 24 cooked beef fetched to serve keeps it (the product dump counts only what he took out of a
     * furnace) and serves a hungry citizen in the hut. Then, with a full hut, 20 beef taken out of a furnace cause at most one dump in 800
     * ticks (no walk to the full hut every 100 ticks).
     */
    public static void cookServeKeepsFood(final GameTestHelper helper)
    {
        workerScene(helper, "R13 cook serve", ModBlocks.blockHutCook, "fundamentals/cook1.blueprint",
          (hut, worker) -> hut.getModule(BuildingModules.COOK_WORK).assignCitizen(worker), (colony, hut, worker) -> {
              helper.assertTrue(workAi(worker) instanceof com.minecolonies.core.entity.ai.workers.AbstractEntityAIUsesFurnace<?, ?>, "no furnace user AI: " + workAi(worker));
              final var ai = (com.minecolonies.core.entity.ai.workers.AbstractEntityAIUsesFurnace<?, ?>) workAi(worker);
              hut.getModule(BuildingModules.ITEMLIST_FUEL).addItem(new ItemStorage(new ItemStack(Items.COAL)));
              hut.getModule(BuildingModules.RESTAURANT_MENU).addMenuItem(new ItemStack(Items.COOKED_BEEF));
              final List<BlockPos> furnaces = placeFurnaces(helper, hut, new BlockPos(22, 1, 5));
              // a free spot in the hut for the hungry citizen
              BlockPos spot = null;
              for (int dy = 0; dy <= 2 && spot == null; dy++)
              {
                  for (int dx = -4; dx <= 4 && spot == null; dx++)
                  {
                      for (int dz = -4; dz <= 4 && spot == null; dz++)
                      {
                          final BlockPos pos = hut.getPosition().offset(dx, dy, dz);
                          if (hut.isInBuilding(pos) && helper.getLevel().getBlockState(pos).isAir() && helper.getLevel().getBlockState(pos.above()).isAir()
                                && !helper.getLevel().getBlockState(pos.below()).isAir())
                          {
                              spot = pos;
                          }
                      }
                  }
              }
              helper.assertTrue(spot != null, "fixture: no free spot in the cook hut");
              final ICitizenData hungry = colony.getCitizenManager().spawnOrCreateCivilian(null, helper.getLevel(), List.of(spot), true);
              helper.assertTrue(hungry != null && hungry.getEntity().isPresent(), "fixture: hungry citizen did not spawn");
              final BlockPos at = spot;
              hungry.getEntity().get().setPos(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
              hungry.setSaturation(2);
              hungry.setJustAte(false);
              ((AbstractJob<?, ?>) worker.getJob()).clearActionsDone();
              InventoryUtils.addItemStackToItemHandler(worker.getInventory(), new ItemStack(Items.COOKED_BEEF, 24));
              final int[] dumpRule = {0};
              poll(helper, worker, 1600, tick -> {
                  if (ai.productsToDump())
                  {
                      dumpRule[0]++;
                  }
                  hungry.getEntity().ifPresent(e -> e.setPos(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D));
                  return count(hungry, Items.COOKED_BEEF) > 0 || hungry.getSaturation() > 2;
              }, () -> {
                  final int inHut = InventoryUtils.getItemCountInItemHandler(hut.getItemHandlerCap(), stack -> stack.is(Items.COOKED_BEEF));
                  Log.getLogger().info("R13W cook_serve served={} cookKeeps={} inHut={} dumpRule={} taken={}", count(hungry, Items.COOKED_BEEF), count(worker, Items.COOKED_BEEF),
                    inHut, dumpRule[0], ai.getProductsTakenSinceDump());
                  helper.assertTrue(dumpRule[0] == 0, "the product dump counted the food fetched to serve: " + dumpRule[0] + " ticks");
                  helper.assertTrue(inHut == 0, "the cook took the food to serve back to the hut: " + inHut);
                  helper.assertTrue(count(worker, Items.COOKED_BEEF) > 0, "the cook kept no food after serving");
                  fullHutPart(helper, colony, hut, worker, ai, furnaces.get(0));
              }, () -> "served=" + count(hungry, Items.COOKED_BEEF) + " sat=" + hungry.getSaturation() + " cook=" + count(worker, Items.COOKED_BEEF) + " " + stateOf(worker));
          });
    }

    /**
     * Second part of {@link #cookServeKeepsFood}: a full hut and 20 beef in the furnace.
     */
    private static void fullHutPart(final GameTestHelper helper, final IColony colony, final IBuilding hut, final ICitizenData worker,
      final com.minecolonies.core.entity.ai.workers.AbstractEntityAIUsesFurnace<?, ?> ai, final BlockPos furnacePos)
    {
        final var cap = hut.getItemHandlerCap();
        for (int slot = 0; slot < cap.getSlots(); slot++)
        {
            cap.insertItem(slot, new ItemStack(Items.COBBLESTONE, 64), false);
        }
        helper.assertTrue(InventoryUtils.isBuildingFull(hut), "fixture: the hut is not full");
        furnace(helper, furnacePos).setItem(Constants.RESULT_SLOT, new ItemStack(Items.COOKED_BEEF, 20));
        final int[] taken = {-1};
        final long[] dumps0 = {-1};
        poll(helper, worker, 1400, tick -> {
            if (taken[0] < 0)
            {
                if (furnace(helper, furnacePos).getItem(Constants.RESULT_SLOT).isEmpty())
                {
                    taken[0] = tick;
                    dumps0[0] = jobSnapshot(colony, worker).dumps();
                }
                return false;
            }
            return tick - taken[0] >= 800;
        }, () -> {
            final long dumps = jobSnapshot(colony, worker).dumps() - dumps0[0];
            Log.getLogger().info("R13W cook_serve fullHut dumps={} taken={} carried={} rule={}", dumps, ai.getProductsTakenSinceDump(), count(worker, Items.COOKED_BEEF),
              ai.productsToDump());
            helper.assertTrue(dumps <= 1, "the cook walked to the full hut again and again: " + dumps + " dumps in 800 ticks");
            helper.assertTrue(!ai.productsToDump(), "the product dump is still due with a full hut");
            helper.assertTrue(count(worker, Items.COOKED_BEEF) >= 20, "food was lost at the full hut: " + count(worker, Items.COOKED_BEEF));
            helper.succeed();
        }, () -> "furnace not emptied: " + stateOf(worker));
    }

    private static String stateOf(final ICitizenData worker)
    {
        final AbstractEntityAIBasic<?, ?> ai = workAi(worker);
        return ai == null ? "no-ai" : ai.getState() + " history=" + ai.getStateAI().getHistory().getString();
    }

    // ------------------------------------------------------------------ W9 request smelter

    /**
     * A stone smeltery with two furnaces, stone and coal in its hut and a stone to smooth stone furnace recipe; a builder hut that asks for
     * four smooth stone.
     */
    private static void smelterScene(final GameTestHelper helper, final String name, final SceneBody body)
    {
        workerScene(helper, name, ModBlocks.blockHutStoneSmeltery, "craftsmanship/masonry/stonesmeltery1.blueprint",
          (hut, worker) -> hut.getModule(BuildingModules.STONESMELTER_WORK).assignCitizen(worker), (colony, hut, worker) -> {
              helper.assertTrue(workAi(worker) instanceof com.minecolonies.core.entity.ai.workers.crafting.AbstractEntityAIRequestSmelter<?, ?>,
                "no request smelter AI: " + workAi(worker));
              hut.getModule(BuildingModules.ITEMLIST_FUEL).addItem(new ItemStorage(new ItemStack(Items.COAL)));
              placeFurnaces(helper, hut, new BlockPos(22, 1, 5), new BlockPos(25, 1, 5));
              final RecipeStorage recipe = RecipeStorage.builder().withRecipeId(null).withInputs(List.of(new ItemStorage(new ItemStack(Items.STONE, 1))))
                .withPrimaryOutput(new ItemStack(Items.SMOOTH_STONE, 1)).withIntermediate(net.minecraft.world.level.block.Blocks.FURNACE).build();
              helper.assertTrue(hut.getModule(BuildingModules.STONESMELTER_SMELTING).addRecipe(IColonyManager.getInstance().getRecipeManager().checkOrAddRecipe(recipe)),
                "the smeltery rejected the recipe");
              put(helper, hut, Items.STONE, 16);
              put(helper, hut, Items.COAL, 16);
              body.run(colony, hut, worker);
          });
    }

    private static int smoothStone(final IBuilding hut, final ICitizenData worker)
    {
        return InventoryUtils.getItemCountInItemHandler(hut.getItemHandlerCap(), stack -> stack.is(Items.SMOOTH_STONE)) + count(worker, Items.SMOOTH_STONE);
    }

    /**
     * W9 (review c1): the smeltery worker finishes a furnace request; while every usable furnace burns he waits next to one (no walk back to
     * the hut block).
     */
    public static void smelterRequest(final GameTestHelper helper)
    {
        smelterScene(helper, "R13 smelter request", (colony, hut, worker) -> {
            final IBuilding builder = MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockHutBuilder, new BlockPos(8, 1, 2), "fundamentals/builder1.blueprint");
            builder.createRequest(new Stack(new ItemStack(Items.SMOOTH_STONE, 4), 4, 4), true);
            final AbstractJobCrafter<?, ?> job = (AbstractJobCrafter<?, ?>) worker.getJob();
            final int[] taskAt = {-1};
            final int[] waitingTicks = {0};
            final int[] litMax = {0};
            final double[] waitDistance = {-1};
            poll(helper, worker, 4000, tick -> {
                if (taskAt[0] < 0 && job.getCurrentTask() != null)
                {
                    taskAt[0] = tick;
                }
                int lit = 0;
                for (final BlockPos pos : hut.getModule(BuildingModules.FURNACE).getFurnaces())
                {
                    if (furnace(helper, pos).getBlockState().getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT))
                    {
                        lit++;
                    }
                }
                litMax[0] = Math.max(litMax[0], lit);
                if (workAi(worker) instanceof com.minecolonies.core.entity.ai.workers.crafting.AbstractEntityAIRequestSmelter<?, ?> smelter && smelter.isWaitingAtFurnace())
                {
                    waitingTicks[0]++;
                    final BlockPos at = worker.getEntity().get().blockPosition();
                    double best = Double.MAX_VALUE;
                    for (final BlockPos pos : hut.getModule(BuildingModules.FURNACE).getFurnaces())
                    {
                        best = Math.min(best, Math.sqrt(at.distSqr(pos)));
                    }
                    waitDistance[0] = Math.max(waitDistance[0], best);
                }
                return taskAt[0] >= 0 && smoothStone(hut, worker) >= 4 && job.getCurrentTask() == null;
            }, () -> {
                Log.getLogger().info("R13W smelter_request taskAt={} smooth={} litMax={} waitingTicks={} waitDistance={}", taskAt[0], smoothStone(hut, worker), litMax[0],
                  waitingTicks[0], waitDistance[0]);
                helper.assertTrue(litMax[0] >= 1, "no furnace ever burned");
                helper.assertTrue(waitingTicks[0] > 0, "the worker never waited at a burning furnace");
                helper.assertTrue(waitDistance[0] <= 6.0D, "he waited away from the furnaces: " + waitDistance[0]);
                helper.assertTrue(workAi(worker).getExceptionTimer() == 1, "the AI threw");
                helper.succeed();
            }, () -> "taskAt=" + taskAt[0] + " smooth=" + smoothStone(hut, worker) + " litMax=" + litMax[0] + " waiting=" + waitingTicks[0] + " task="
                       + job.getCurrentTask() + " " + stateOf(worker));
        });
    }

    /**
     * W9 (review c1): the furnace task is cancelled while a furnace burns: the worker drops it (no craft step left on a cancelled request, no
     * exception) and the task leaves his queue.
     */
    public static void smelterCancel(final GameTestHelper helper)
    {
        smelterScene(helper, "R13 smelter cancel", (colony, hut, worker) -> {
            final IBuilding builder = MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockHutBuilder, new BlockPos(8, 1, 2), "fundamentals/builder1.blueprint");
            builder.createRequest(new Stack(new ItemStack(Items.SMOOTH_STONE, 4), 4, 4), true);
            final AbstractJobCrafter<?, ?> job = (AbstractJobCrafter<?, ?>) worker.getJob();
            final IToken<?>[] cancelled = {null};
            final int[] cancelledAt = {-1};
            poll(helper, worker, 4000, tick -> {
                if (cancelled[0] == null)
                {
                    final IRequest<?> task = job.getCurrentTask();
                    boolean burning = false;
                    for (final BlockPos pos : hut.getModule(BuildingModules.FURNACE).getFurnaces())
                    {
                        final var furnace = furnace(helper, pos);
                        burning |= furnace.getBlockState().getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT) && !furnace.getItem(Constants.SMELTABLE_SLOT).isEmpty();
                    }
                    if (task != null && burning)
                    {
                        cancelled[0] = task.getId();
                        cancelledAt[0] = tick;
                        colony.getRequestManager().updateRequestState(task.getId(), com.minecolonies.api.colony.requestsystem.request.RequestState.CANCELLED);
                    }
                    return false;
                }
                final AbstractEntityAIBasic<?, ?> ai = workAi(worker);
                return tick - cancelledAt[0] >= 40 && !job.getTaskQueue().contains(cancelled[0]) && ai != null && ai.getState() != AIWorkerState.CRAFT;
            }, () -> {
                Log.getLogger().info("R13W smelter_cancel cancelledAt={} queue={} {}", cancelledAt[0], job.getTaskQueue().size(), stateOf(worker));
                helper.assertTrue(workAi(worker).getExceptionTimer() == 1, "the AI threw after the cancel");
                helper.succeed();
            }, () -> "cancelledAt=" + cancelledAt[0] + " queue=" + job.getTaskQueue() + " " + stateOf(worker));
        });
    }
}
