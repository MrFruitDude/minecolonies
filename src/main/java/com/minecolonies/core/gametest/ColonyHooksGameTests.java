package com.minecolonies.core.gametest;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyPaceProvider;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.eventbus.events.colony.buildings.BuildingUpgradeRequestModEvent;
import com.minecolonies.api.research.util.ResearchConstants;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import com.minecolonies.core.entity.ai.minimal.EntityAICitizenChild;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic;
import com.minecolonies.core.entity.ai.workers.crafting.AbstractEntityAICrafting;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import static com.minecolonies.core.gametest.MinecoloniesGameTests.foundGameTestColony;
import static com.minecolonies.core.gametest.MinecoloniesGameTests.makeConnectedSurvivalPlayer;
import static com.minecolonies.core.gametest.MinecoloniesGameTests.placeProductionBuilding;

/**
 * G0 (Colony Command game loop): GameTests for the two API hooks, H1 {@link IColonyPaceProvider} and H2
 * {@link BuildingUpgradeRequestModEvent}.
 */
public final class ColonyHooksGameTests
{
    private ColonyHooksGameTests()
    {
    }

    /**
     * A pace provider stub. It only answers for the one colony it was made for, so if a test dies without restoring
     * the previous provider, no other test's colony is affected.
     */
    private static final class StubPaceProvider implements IColonyPaceProvider
    {
        private final int colonyId;
        private double work = 1.0D;
        private double needs = 1.0D;
        private double growth = 1.0D;

        private StubPaceProvider(final int colonyId)
        {
            this.colonyId = colonyId;
        }

        @Override
        public double work(final IColony colony, final IJob<?> job)
        {
            return colony.getID() == colonyId ? work : 1.0D;
        }

        @Override
        public double needs(final IColony colony)
        {
            return colony.getID() == colonyId ? needs : 1.0D;
        }

        @Override
        public double growth(final IColony colony)
        {
            return colony.getID() == colonyId ? growth : 1.0D;
        }
    }

    private static AbstractEntityAIBasic<?, ?> workAi(final ICitizenData worker)
    {
        final Object ai = worker.getEntity().map(e -> (Object) e.getCitizenJobHandler().getWorkAI()).orElse(null);
        return ai instanceof AbstractEntityAIBasic<?, ?> basic ? basic : null;
    }

    /**
     * Reads the AI's private wait counter (AbstractEntityAIBasic#delay).
     */
    private static int delayOf(final AbstractEntityAIBasic<?, ?> ai)
    {
        try
        {
            final Field delay = AbstractEntityAIBasic.class.getDeclaredField("delay");
            delay.setAccessible(true);
            return delay.getInt(ai);
        }
        catch (final ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Sets a long wait on the real AI, runs its own wait step (AbstractEntityAIBasic#waitingForSomething) {@code steps}
     * times in a row and returns how far the wait counted down. Clears the wait afterwards.
     */
    private static int countDown(final AbstractEntityAIBasic<?, ?> ai, final int steps)
    {
        final int start = 1_000_000;
        ai.setDelay(start);
        try
        {
            final Method wait = AbstractEntityAIBasic.class.getDeclaredMethod("waitingForSomething");
            wait.setAccessible(true);
            for (int i = 0; i < steps; i++)
            {
                wait.invoke(ai);
            }
        }
        catch (final ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
        final int counted = start - delayOf(ai);
        ai.setDelay(0);
        return counted;
    }

    /**
     * Saturation one {@code decreaseSaturation(amount)} call takes from a citizen at 50.
     */
    private static double saturationLoss(final ICitizenData citizen, final double amount)
    {
        citizen.setSaturation(50);
        citizen.decreaseSaturation(amount);
        final double loss = 50 - citizen.getSaturation();
        citizen.setSaturation(20);
        return loss;
    }

    /**
     * Founds the colony, places a level 1 sawmill and, 20 ticks later, runs {@code body} with a spawned sawmill worker
     * (a crafter) whose real work AI exists.
     */
    private static void crafterFixture(final GameTestHelper helper, final String name, final BiConsumer<IColony, ICitizenData> body)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = foundGameTestColony(helper, name);
        final IBuilding sawmill = placeProductionBuilding(helper, colony, ModBlocks.blockHutSawmill, new BlockPos(20, 1, 2),
          "craftsmanship/carpentry/sawmill1.blueprint");
        final BlockPos anchor = helper.absolutePos(new BlockPos(22, 1, 10));
        level.setChunkForced(anchor.getX() >> 4, anchor.getZ() >> 4, true);
        // A colony only runs need decay while active, which takes a player close by (a connected mock player, as in
        // the production tests; FakePlayers are refused as subscribers). Its state is re-evaluated every 100 ticks.
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
        player.teleportTo(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D);
        colony.getPackageManager().addCloseSubscriber(player);
        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = colony.getCitizenManager().spawnOrCreateCivilian(null, level, List.of(anchor), true);
            helper.assertTrue(worker != null && worker.getEntity().isPresent(), "sawmill citizen did not spawn");
            helper.assertTrue(sawmill.getModule(BuildingModules.SAWMILL_WORK).assignCitizen(worker), "sawmill assignment failed");
            worker.getEntity().get().setPos(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D);
            helper.assertTrue(workAi(worker) instanceof AbstractEntityAICrafting<?, ?>, "sawmill worker has no crafter AI: " + workAi(worker));
            final int[] waited = {0};
            final Runnable[] untilActive = new Runnable[1];
            untilActive[0] = () -> {
                if (colony.isActive())
                {
                    body.accept(colony, worker);
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

    /**
     * H1: a colony pace provider at 2x halves a crafter's waits, measured on the sawmill worker's real AI. First its own
     * wait step called directly (exact counts, including a fractional pace that only adds up with the rounding carry),
     * then live: game ticks from setDelay until the ticking AI has waited it out, at 1x and at 2x. Also checks that
     * needs (saturation loss) and child growth follow the provider.
     */
    public static void paceProviderScalesAiDelay(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        crafterFixture(helper, "G0 pace colony", (colony, worker) -> {
            final IMinecoloniesAPI api = IMinecoloniesAPI.getInstance();
            final IColonyPaceProvider previous = api.getColonyPaceProvider();
            final StubPaceProvider stub = new StubPaceProvider(colony.getID());
            api.setColonyPaceProvider(stub);
            final AbstractEntityAIBasic<?, ?> ai = workAi(worker);
            final int tickRate = ai.getTickRate();
            final double halfStep = (tickRate + 0.5D) / tickRate;
            final List<String> failures = new ArrayList<>();
            try
            {
                helper.assertTrue(api.getColonyPaceProvider() == stub, "the API did not keep the provider that was set");
                stub.work = 1.0D;
                final int at1 = countDown(ai, 40);
                stub.work = 2.0D;
                final int at2 = countDown(ai, 40);
                // tickRate * pace = tickRate + 0.5: whole steps only add up to 40.5 tick rates per 40 calls with the carry.
                stub.work = halfStep;
                final int atHalf = countDown(ai, 40);
                stub.work = 1.0D;
                if (at1 != 40 * tickRate)
                {
                    failures.add("1x wait counted " + at1 + " in 40 steps, expected " + 40 * tickRate);
                }
                if (at2 != 80 * tickRate)
                {
                    failures.add("2x wait counted " + at2 + " in 40 steps, expected " + 80 * tickRate);
                }
                if (Math.abs(atHalf - (40 * tickRate + 20)) > 1)
                {
                    failures.add("pace " + halfStep + " wait counted " + atHalf + " in 40 steps, expected " + (40 * tickRate + 20) + " +-1 (rounding carry)");
                }

                helper.assertTrue(colony.isActive(), "fixture: colony is not active, saturation would not change");
                stub.needs = 1.0D;
                final double loss1 = saturationLoss(worker, 1.0D);
                stub.needs = 2.0D;
                final double loss2 = saturationLoss(worker, 1.0D);
                stub.needs = 1.0D;
                if (!(loss1 > 0) || Math.abs(loss2 - 2 * loss1) > 1e-9)
                {
                    failures.add("needs 2x saturation loss " + loss2 + ", expected twice the 1x loss " + loss1);
                }

                stub.growth = 1.0D;
                final double growth1 = EntityAICitizenChild.getGrowthModifier(colony);
                stub.growth = 2.0D;
                final double growth2 = EntityAICitizenChild.getGrowthModifier(colony);
                stub.growth = 1.0D;
                if (Math.abs(growth2 - 2 * growth1) > 1e-9)
                {
                    failures.add("growth 2x modifier " + growth2 + ", expected twice " + growth1);
                }
                Log.getLogger().info("[pace_provider_scales_ai_delay] tick rate {}, 40 direct steps at 1x/2x/{}x counted {}/{}/{}, "
                  + "saturation loss 1x/2x {}/{}, growth 1x/2x {}/{}", tickRate, halfStep, at1, at2, atHalf, loss1, loss2, growth1, growth2);
                helper.assertTrue(failures.isEmpty(), "pace provider not applied: " + failures);
            }
            catch (final RuntimeException e)
            {
                api.setColonyPaceProvider(previous);
                throw e;
            }

            // Live: the AI ticks by itself; count game ticks until it waited out the same delay at 1x, then at 2x.
            final int waitTicks = 600;
            final double[] paces = {1.0D, 2.0D};
            final int[] measured = new int[paces.length];
            final int[] phase = {0};
            final int[] ticks = {0};
            final int[] elapsed = {0};
            // The live part pins daylight (the fixture has no beds); afterwards the clock is put back where it would be
            // without the pin, so the tests that run later in the batch see the same day/night timeline as before.
            final long clockAtStart = level.getOverworldClockTime();
            final Runnable restoreClock = () -> level.getServer().clockManager()
              .setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), clockAtStart + elapsed[0]);
            final Runnable[] pump = new Runnable[1];
            stub.work = paces[0];
            ai.setDelay(waitTicks);
            pump[0] = () -> {
                try
                {
                    // Daylight and a full belly: the fixture has no beds and no restaurant.
                    level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
                    worker.setSaturation(20);
                    ticks[0]++;
                    elapsed[0]++;
                    if (delayOf(ai) <= 0)
                    {
                        measured[phase[0]] = ticks[0];
                        phase[0]++;
                        ticks[0] = 0;
                        if (phase[0] == paces.length)
                        {
                            api.setColonyPaceProvider(previous);
                            restoreClock.run();
                            final double ratio = (double) measured[1] / measured[0];
                            Log.getLogger().info("[pace_provider_scales_ai_delay] live wait of {} took {} ticks at 1x and {} at 2x (ratio {})",
                              waitTicks, measured[0], measured[1], ratio);
                            helper.assertTrue(ratio >= 0.4D && ratio <= 0.6D,
                              "2x pace did not halve the live wait: " + measured[0] + " ticks at 1x, " + measured[1] + " at 2x");
                            helper.succeed();
                            return;
                        }
                        stub.work = paces[phase[0]];
                        ai.setDelay(waitTicks);
                    }
                    else if (ticks[0] >= 6_000)
                    {
                        throw helper.assertionException("live wait at " + paces[phase[0]] + "x never finished: delay " + delayOf(ai)
                          + " after " + ticks[0] + " ticks, ai " + ai.getState() + ", measured " + Arrays.toString(measured));
                    }
                }
                catch (final RuntimeException e)
                {
                    api.setColonyPaceProvider(previous);
                    restoreClock.run();
                    throw e;
                }
                // A fresh Runnable each time: the GameTest callback map is keyed by Runnable.
                helper.runAfterDelay(1, () -> pump[0].run());
            };
            helper.runAfterDelay(1, () -> pump[0].run());
        });
    }

    /**
     * H1: with no provider set, MineColonies is exactly as before. The API answers the default provider, which says 1.0
     * everywhere; a crafter's wait counts down by exactly its tick rate per step; saturation loss is bit-for-bit
     * {@code |amount * foodModifier|}; the child growth modifier is exactly 1 + the growth research. Bad provider
     * answers (NaN, infinite, zero, negative) count as 1.0.
     */
    public static void paceDefaultProviderUnchanged(final GameTestHelper helper)
    {
        crafterFixture(helper, "G0 default pace colony", (colony, worker) -> {
            final List<String> failures = new ArrayList<>();
            final IColonyPaceProvider provider = IMinecoloniesAPI.getInstance().getColonyPaceProvider();
            if (provider != IColonyPaceProvider.DEFAULT)
            {
                failures.add("no mod set a provider, but the API answers " + provider);
            }
            final IColonyPaceProvider def = IColonyPaceProvider.DEFAULT;
            if (def.work(colony, worker.getJob()) != 1.0D || def.needs(colony) != 1.0D || def.growth(colony) != 1.0D)
            {
                failures.add("default provider is not 1.0: work " + def.work(colony, worker.getJob()) + " needs " + def.needs(colony) + " growth " + def.growth(colony));
            }
            for (final double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0.0D, -2.0D})
            {
                if (IColonyPaceProvider.sanitize(bad) != 1.0D)
                {
                    failures.add("sanitize(" + bad + ") = " + IColonyPaceProvider.sanitize(bad));
                }
            }
            if (IColonyPaceProvider.sanitize(2.5D) != 2.5D)
            {
                failures.add("sanitize(2.5) = " + IColonyPaceProvider.sanitize(2.5D));
            }

            final AbstractEntityAIBasic<?, ?> ai = workAi(worker);
            for (final int steps : new int[] {1, 7, 40})
            {
                final int counted = countDown(ai, steps);
                if (counted != steps * ai.getTickRate())
                {
                    failures.add(steps + " wait steps counted " + counted + ", expected exactly " + steps * ai.getTickRate());
                }
            }

            final double foodModifier = MineColonies.getConfig().getServer().foodModifier.get();
            for (final double amount : new double[] {1.0D, 0.0123D, 0.3D, -0.7D, 1.0D / 3.0D})
            {
                // Today's formula, saturation starting at 50.
                final double expected = 50 - Math.max(0, 50 - Math.abs(amount * foodModifier));
                final double loss = saturationLoss(worker, amount);
                if (Double.doubleToLongBits(loss) != Double.doubleToLongBits(expected))
                {
                    failures.add("saturation loss for " + amount + " was " + loss + ", expected bit-for-bit " + expected);
                }
            }

            final double growth = EntityAICitizenChild.getGrowthModifier(colony);
            final double research = 1 + colony.getResearchManager().getResearchEffects().getEffectStrength(ResearchConstants.GROWTH);
            if (Double.doubleToLongBits(growth) != Double.doubleToLongBits(research))
            {
                failures.add("growth modifier " + growth + ", expected exactly " + research);
            }
            Log.getLogger().info("[pace_default_provider_unchanged] tick rate {}, food modifier {}, growth {}, failures {}",
              ai.getTickRate(), foodModifier, growth, failures);
            helper.assertTrue(failures.isEmpty(), "default pace changed behaviour: " + failures);
            helper.succeed();
        });
    }

    /**
     * The colony the single upgrade-request listener refuses for, or -1 (disarmed). The mod event bus has no
     * unsubscribe, so the listener is registered once and only acts while armed.
     */
    private static final AtomicInteger REFUSE_COLONY = new AtomicInteger(-1);

    /**
     * Events the armed listener saw.
     */
    private static final List<BuildingUpgradeRequestModEvent> SEEN = Collections.synchronizedList(new ArrayList<>());

    private static boolean listenerRegistered = false;

    /**
     * H2: requestUpgrade posts BuildingUpgradeRequestModEvent before the work order exists. Cancelled, it leaves no
     * work order (BUILD of a level 0 builder hut and UPGRADE of the level 1 town hall); with the listener disarmed, the
     * same request makes the work order as before.
     */
    public static void upgradeRequestEventCancels(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = foundGameTestColony(helper, "G0 upgrade colony");
        final IBuilding builder = placeProductionBuilding(helper, colony, ModBlocks.blockHutBuilder, new BlockPos(10, 1, 2),
          "fundamentals/builder1.blueprint");
        builder.setBuildingLevel(0);
        final BlockPos builderPos = builder.getID();
        final IBuilding townHall = colony.getServerBuildingManager().getTownHall();
        if (!listenerRegistered)
        {
            listenerRegistered = true;
            IMinecoloniesAPI.getInstance().getEventBus().subscribe(BuildingUpgradeRequestModEvent.class, event -> {
                if (event.getColony().getID() != REFUSE_COLONY.get())
                {
                    return;
                }
                SEEN.add(event);
                event.cancel(Component.literal("G0 test refused this upgrade"));
            });
        }

        helper.runAfterDelay(5, () -> {
            final Player player = FakePlayerFactory.getMinecraft(level);
            final Predicate<BlockPos> hasOrder = pos -> colony.getWorkManager().getWorkOrdersOfType(WorkOrderBuilding.class).stream()
              .anyMatch(order -> pos.equals(order.getLocation()));
            final List<String> failures = new ArrayList<>();
            SEEN.clear();
            REFUSE_COLONY.set(colony.getID());
            try
            {
                builder.requestUpgrade(player, builderPos);
                if (hasOrder.test(builderPos))
                {
                    failures.add("cancelled BUILD request still created a work order");
                }
                townHall.requestUpgrade(player, BlockPos.ZERO);
                if (hasOrder.test(townHall.getID()))
                {
                    failures.add("cancelled UPGRADE request still created a work order");
                }
            }
            finally
            {
                REFUSE_COLONY.set(-1);
            }
            final List<String> seen = SEEN.stream().map(e -> e.getType() + "->" + e.getTargetLevel() + "@" + e.getBuilding().getID()
              + (e.getPlayer() == player ? " by player" : " by " + e.getPlayer()) + " builder " + e.getBuilder()).toList();
            if (SEEN.size() != 2
              || SEEN.get(0).getType() != WorkOrderType.BUILD || SEEN.get(0).getTargetLevel() != 1
              || SEEN.get(0).getBuilding() != builder || SEEN.get(0).getPlayer() != player || !builderPos.equals(SEEN.get(0).getBuilder())
              || SEEN.get(1).getType() != WorkOrderType.UPGRADE || SEEN.get(1).getTargetLevel() != 2
              || SEEN.get(1).getBuilding() != townHall)
            {
                failures.add("listener saw " + seen + ", expected BUILD->1 of the builder hut, then UPGRADE->2 of the town hall");
            }
            if (!colony.getWorkManager().getWorkOrders().isEmpty())
            {
                failures.add("work orders after the cancelled requests: " + colony.getWorkManager().getWorkOrders().size());
            }

            // No listener acting: the same request makes the work order exactly as before.
            builder.requestUpgrade(player, builderPos);
            if (!hasOrder.test(builderPos))
            {
                failures.add("uncancelled BUILD request made no work order");
            }
            Log.getLogger().info("[upgrade_request_event_cancels] events {}, work orders after the uncancelled request {}, failures {}",
              seen, colony.getWorkManager().getWorkOrders().size(), failures);
            helper.assertTrue(failures.isEmpty(), "upgrade request event wrong: " + failures);
            helper.succeed();
        });
    }
}
