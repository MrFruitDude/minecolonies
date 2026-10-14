package com.minecolonies.core.gametest;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.util.RotationMirror;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.idle.IIdleTaskSession;
import com.minecolonies.api.colony.idle.IdleStopReason;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.modules.settings.IntSetting;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;
import com.minecolonies.core.colony.idle.IdleCitizenArbiter;
import com.minecolonies.core.colony.idle.IdleCitizenSettings;
import com.minecolonies.core.colony.workorders.WorkOrderDecoration;
import com.minecolonies.core.colony.workorders.collab.BuilderCollab;
import com.minecolonies.core.colony.workorders.collab.CollabViews;
import com.minecolonies.core.colony.workorders.collab.IdleHelperKey;
import com.minecolonies.core.colony.workorders.collab.WorkOrderCollab;
import com.minecolonies.core.entity.ai.idle.BuilderHelperTask;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Unemployed citizens help builders (the idle citizen arbiter and its first task, {@code BuilderHelperTask}): a citizen without a job joins an order that a
 * builder leads, builds with the builder's materials at a share of his pace, and leaves when he gets a job, when the night comes, when he has needs, when the
 * colony setting goes off or when the order is done. Real citizens with real AI; the colonies are ticked by the test (a test colony has no player and so no tick of
 * its own).
 */
final class IdleHelperGameTests
{
    private static final Block BUILD_BLOCK = Blocks.STONE_BRICKS;
    private static final Item  BUILD_ITEM  = Items.STONE_BRICKS;
    private static final int   SIZE        = 16;
    private static final int   SPARE       = 24;
    private static final String PACK       = "Minecolonies Original";

    private IdleHelperGameTests()
    {
    }

    // ------------------------------------------------------------------ scene

    /**
     * One colony of the scene: a town hall, a builder hut with a builder, and the unemployed citizens of the test.
     */
    private static final class Site
    {
        final GameTestHelper helper;
        final ServerLevel    level;
        final int            dx;
        Colony               colony;
        BuildingBuilder      hut;
        ICitizenData         builder;
        final List<ICitizenData> idle = new ArrayList<>();

        Site(final GameTestHelper helper, final int dx)
        {
            this.helper = helper;
            this.level = helper.getLevel();
            this.dx = dx;
        }

        BlockPos abs(final int x, final int y, final int z)
        {
            return helper.absolutePos(new BlockPos(x + dx, y, z));
        }

        WorkOrderDecoration order(final String name, final int x, final int z, final int size, final boolean claimByBuilder)
        {
            final WorkOrderDecoration order = WorkOrderDecoration.create(WorkOrderType.BUILD, PACK, "gametest/" + name + ".blueprint", "gametest." + name,
              abs(x, 1, z), RotationMirror.NONE, 0);
            order.setBlueprint(floor(name, size), level);
            colony.getWorkManager().addWorkOrder(order, true);
            helper.assertTrue(order.getID() > 0, "no id for " + name);
            if (claimByBuilder)
            {
                order.setClaimedBy(hut.getID());
                hut.setWorkOrder(order);
            }
            return order;
        }

        Blueprint floor(final String name, final int size)
        {
            final Blueprint blueprint = new Blueprint((short) size, (short) 1, (short) size).setName(name).setFileName(name).setPackName(PACK);
            for (int x = 0; x < size; x++)
            {
                for (int z = 0; z < size; z++)
                {
                    blueprint.addBlockState(new BlockPos(x, 0, z), BUILD_BLOCK.defaultBlockState());
                }
            }
            blueprint.setCachePrimaryOffset(BlockPos.ZERO);
            return blueprint;
        }

        void stock(final int amount)
        {
            int left = amount;
            while (left > 0)
            {
                final int n = Math.min(left, BUILD_ITEM.getDefaultMaxStackSize());
                helper.assertTrue(InventoryUtils.forceItemStackToItemHandler(hut.getItemHandlerCap(), new ItemStack(BUILD_ITEM, n), s -> true).isEmpty(), "no room in the hut");
                left -= n;
            }
        }

        int bricks()
        {
            int n = InventoryUtils.getItemCountInItemHandler(hut.getItemHandlerCap(), s -> s.is(BUILD_ITEM));
            n += InventoryUtils.getItemCountInItemHandler(builder.getInventory(), s -> s.is(BUILD_ITEM));
            for (final ICitizenData citizen : idle)
            {
                n += InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), s -> s.is(BUILD_ITEM));
            }
            return n;
        }

        ICitizenData spawnIdle(final int x, final int z)
        {
            final ICitizenData citizen = colony.getCitizenManager().spawnOrCreateCivilian(null, level, List.of(abs(x, 1, z)), true);
            helper.assertTrue(citizen != null && citizen.getEntity().isPresent(), "idle citizen did not spawn");
            idle.add(citizen);
            return citizen;
        }

        void tick()
        {
            colony.getWorkManager().onColonyTick(colony);
            IdleCitizenArbiter.tick(colony);
        }
    }

    private static Colony foundAt(final GameTestHelper helper, final String name, final int dx)
    {
        if (dx == 0)
        {
            return (Colony) MinecoloniesGameTests.foundGameTestColony(helper, name);
        }
        final ServerLevel level = helper.getLevel();
        forceArea(level, helper.absolutePos(new BlockPos(dx - 8, 1, -8)), helper.absolutePos(new BlockPos(dx + 40, 1, 32)));
        for (int x = dx - 8; x < dx + 40; x++)
        {
            for (int z = -8; z < 32; z++)
            {
                level.setBlock(helper.absolutePos(new BlockPos(x, 0, z)), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 1; y < 6; y++)
                {
                    level.setBlock(helper.absolutePos(new BlockPos(x, y, z)), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        final BlockPos hallPos = helper.absolutePos(new BlockPos(dx + 2, 1, 2));
        level.setBlock(hallPos, ModBlocks.blockHutTownHall.defaultBlockState(), 3);
        final TileEntityColonyBuilding hall = (TileEntityColonyBuilding) level.getBlockEntity(hallPos);
        hall.setPackName(PACK);
        hall.setBlueprintPath("fundamentals/townhall1.blueprint");
        final IColony colony = IColonyManager.getInstance().createColony(level, hallPos, FakePlayerFactory.getMinecraft(level), name, PACK);
        helper.assertTrue(colony != null, "second colony not created");
        final IBuilding townHall = colony.getServerBuildingManager().addNewBuilding(hall, level);
        helper.assertTrue(townHall != null, "second town hall not registered");
        townHall.setBuildingLevel(1);
        return (Colony) colony;
    }

    private static void forceArea(final ServerLevel level, final BlockPos min, final BlockPos max)
    {
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++)
        {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++)
            {
                level.setChunkForced(cx, cz, true);
            }
        }
    }

    /**
     * A colony with a builder (hut and citizen with AI) and no one else yet; {@code ready} runs once the builder exists (after the same wait the production fixtures use).
     */
    private static Site site(final GameTestHelper helper, final String name, final int dx, final boolean withBuilder, final java.util.function.Consumer<Site> ready)
    {
        final Site site = new Site(helper, dx);
        site.colony = foundAt(helper, name, dx);
        forceArea(site.level, site.abs(-8, 1, -8), site.abs(40, 1, 32));
        if (withBuilder)
        {
            site.hut = (BuildingBuilder) MinecoloniesGameTests.placeProductionBuilding(helper, site.colony, ModBlocks.blockHutBuilder, new BlockPos(8 + dx, 1, 2),
              "fundamentals/builder1.blueprint");
        }
        helper.runAfterDelay(200, () -> {
            forceArea(site.level, site.abs(-8, 1, -8), site.abs(40, 1, 32));
            if (withBuilder)
            {
                site.builder = site.colony.getCitizenManager().spawnOrCreateCivilian(null, site.level, List.of(site.abs(10, 1, 8)), true);
                helper.assertTrue(site.builder != null && site.builder.getEntity().isPresent(), "builder did not spawn");
                helper.assertTrue(site.hut.getModule(BuildingModules.BUILDER_WORK).assignCitizen(site.builder), "builder not assigned");
                site.builder.getEntity().ifPresent(e -> e.setPos(site.hut.getID().getX() + 0.5D, site.hut.getID().getY(), site.hut.getID().getZ() + 0.5D));
            }
            ready.accept(site);
        });
        return site;
    }

    /**
     * The colony setting, set the way the settings window does it (an update of the settings module).
     */
    private static void setIdleHelpers(final Colony colony, final boolean on)
    {
        colony.getSettings().updateSetting(BuildingTownHall.IDLE_HELPERS, new BoolSetting(on), null);
    }

    private static void setPerBuilder(final Colony colony, final int limit)
    {
        colony.getSettings().updateSetting(BuildingTownHall.IDLE_HELPERS_PER_BUILDER, new IntSetting(limit), null);
    }

    private static final Map<BlockPos, List<BlockPos>> PLACEMENTS = new HashMap<>();

    private static void begin()
    {
        PLACEMENTS.clear();
        BuilderCollab.overrideForTests(true, 3, 10_000, 10, 100_000, 8, 2400);
        IdleCitizenSettings.overrideForTests(null, null, null);
        BuilderCollab.placementProbe = (pos, hut) -> PLACEMENTS.computeIfAbsent(pos.immutable(), p -> new ArrayList<>()).add(hut.immutable());
    }

    private static void end()
    {
        BuilderCollab.placementProbe = null;
        BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
        IdleCitizenSettings.overrideForTests(null, null, null);
        PLACEMENTS.clear();
    }

    /**
     * Calls {@code check} every 20 ticks (after ticking the colonies and setting the day) until it returns true; fails with the message after {@code maxTicks}.
     */
    private static void poll(final GameTestHelper helper, final List<Site> sites, final int maxTicks, final BooleanSupplier check, final Runnable onDone, final Supplier<String> failure)
    {
        final int[] elapsed = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            helper.getLevel().getServer().clockManager().setTotalTicks(helper.getLevel().registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
            for (final Site site : sites)
            {
                site.tick();
            }
            if (check.getAsBoolean())
            {
                onDone.run();
                return;
            }
            if (elapsed[0] % 500 == 0)
            {
                Log.getLogger().info("IDLE HELPER poll t={} {}", elapsed[0], failure.get());
            }
            if (elapsed[0] >= maxTicks)
            {
                end();
                helper.assertTrue(false, "timeout after " + maxTicks + " ticks: " + failure.get());
                return;
            }
            elapsed[0] += 20;
            helper.runAfterDelay(20, () -> step[0].run());
        };
        helper.runAfterDelay(1, step[0]);
    }

    private static String describe(final Site site)
    {
        final StringBuilder sb = new StringBuilder();
        for (final IServerWorkOrder order : site.colony.getWorkManager().getWorkOrders().values())
        {
            if (order instanceof IBuilderWorkOrder b)
            {
                final WorkOrderCollab c = b.getCollab();
                sb.append("[order ").append(b.getID()).append(" claimedBy=").append(b.getClaimedBy()).append(" stage=").append(c.getProgressStage()).append(" placed=")
                  .append(c.getPlacedBlocks()).append('/').append(c.getTotalBlocks()).append(" leases=").append(c.getLeaseCount()).append(" assistants=").append(c.getAssistantCount())
                  .append(" idle=").append(c.getIdleAssistantCount()).append("] ");
            }
        }
        if (site.hut != null)
        {
            sb.append("hutOrder=").append(site.hut.getWorkOrder() == null ? null : site.hut.getWorkOrder().getID()).append(' ');
        }
        for (final ICitizenData citizen : site.idle)
        {
            final IIdleTaskSession session = IdleCitizenArbiter.sessionOf(citizen);
            sb.append("[idle ").append(citizen.getId()).append(" job=").append(citizen.getJob()).append(" session=").append(session == null ? "none" : session.getTaskId()).append(" ai=")
              .append(citizen.getEntity().map(e -> e instanceof EntityCitizen ec ? String.valueOf(ec.getCitizenAI().getState()) : "?").orElse("-")).append(" at=")
              .append(citizen.getEntity().map(e -> e.blockPosition().toShortString()).orElse("-")).append(" sat=").append(citizen.getSaturation()).append("] ");
        }
        return sb.toString();
    }

    private static boolean floorBuilt(final Site site, final WorkOrderDecoration order, final int size)
    {
        for (int x = 0; x < size; x++)
        {
            for (int z = 0; z < size; z++)
            {
                if (!site.level.getBlockState(order.getLocation().offset(x, 0, z)).is(BUILD_BLOCK))
                {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean orderGone(final Site site, final WorkOrderDecoration order)
    {
        return site.colony.getWorkManager().getWorkOrder(order.getID()) == null && !site.hut.hasWorkOrder();
    }

    private static int placedBy(final BlockPos key)
    {
        int n = 0;
        for (final List<BlockPos> by : PLACEMENTS.values())
        {
            for (final BlockPos placer : by)
            {
                if (placer.equals(key))
                {
                    n++;
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ a helper joins and the build is faster; the setting off means no helper

    /**
     * Two builds of the same floor by the same builder, one after the other, with an unemployed citizen next to the site. First the colony setting is off: nobody helps
     * (no session, no helper in the order), the builder builds alone. Then the setting is on: the citizen joins, places blocks of his own, the floor is complete with
     * no position placed twice, every brick is accounted for, and the build takes clearly less time than the first.
     */
    public static void helperJoinsAndBuildIsFaster(final GameTestHelper helper)
    {
        begin();
        site(helper, "idle helper faster", 0, true, site -> {
            final ICitizenData idle = site.spawnIdle(12, 14);
            final BlockPos key = IdleHelperKey.of(idle.getId());
            setIdleHelpers(site.colony, false);
            site.stock(SIZE * SIZE + SPARE);
            final WorkOrderDecoration first = site.order("alone", 20, 8, SIZE, true);
            final long[] start = {site.level.getGameTime()};
            final long[] aloneTicks = {-1};
            final WorkOrderDecoration[] second = {null};
            final long[] helpedTicks = {-1};
            final boolean[] sawSession = {false};
            poll(helper, List.of(site), 40_000, () -> {
                if (aloneTicks[0] < 0)
                {
                    helper.assertTrue(first.getCollab().getAssistantCount() == 0 && IdleCitizenArbiter.sessionOf(idle) == null, "a helper with the setting off: " + describe(site));
                    if (orderGone(site, first))
                    {
                        helper.assertTrue(floorBuilt(site, first, SIZE), "first floor not complete");
                        helper.assertTrue(placedBy(key) == 0, "the idle citizen placed blocks with the setting off");
                        aloneTicks[0] = site.level.getGameTime() - start[0];
                        Log.getLogger().info("IDLE HELPER the builder alone: {} ticks", aloneTicks[0]);
                        // the setting on, a second order of the same size
                        setIdleHelpers(site.colony, true);
                        site.stock(SIZE * SIZE);
                        second[0] = site.order("helped", 20, 8 + SIZE + 2, SIZE, true);
                        start[0] = site.level.getGameTime();
                    }
                    return false;
                }
                if (IdleCitizenArbiter.sessionOf(idle) != null)
                {
                    sawSession[0] = true;
                    helper.assertTrue(site.colony.getWorkManager().getAssistedOrder(key) == second[0], "the helper helps another order: " + describe(site));
                    helper.assertTrue(idle.getJob() == null, "the helper got a job");
                }
                if (orderGone(site, second[0]))
                {
                    helpedTicks[0] = site.level.getGameTime() - start[0];
                    return true;
                }
                return false;
            }, () -> {
                helper.assertTrue(sawSession[0], "the idle citizen never joined");
                helper.assertTrue(floorBuilt(site, second[0], SIZE), "second floor not complete");
                final int byHelper = placedBy(key);
                Log.getLogger().info("IDLE HELPER with an idle citizen: {} ticks (alone {}), the helper placed {} of {} blocks", helpedTicks[0], aloneTicks[0], byHelper, SIZE * SIZE);
                helper.assertTrue(byHelper > 0, "the idle citizen placed no block");
                for (int x = 0; x < SIZE; x++)
                {
                    for (int z = 0; z < SIZE; z++)
                    {
                        final List<BlockPos> by = PLACEMENTS.get(second[0].getLocation().offset(x, 0, z));
                        helper.assertTrue(by != null && by.size() == 1, "position " + x + "," + z + " of the second floor was placed " + (by == null ? 0 : by.size()) + " times: " + by);
                    }
                }
                helper.assertTrue(helpedTicks[0] < aloneTicks[0] * 0.92D, "the build with a helper took " + helpedTicks[0] + " ticks, alone " + aloneTicks[0]);
                // the bricks are all there (24 spare)
                helper.assertTrue(site.bricks() == SPARE, "bricks not conserved: " + site.bricks() + ", expected " + SPARE);
                end();
                helper.succeed();
            }, () -> describe(site) + " alone=" + aloneTicks[0]);
        });
    }

    // ------------------------------------------------------------------ nothing to help with

    /**
     * Watches an unemployed citizen for 600 ticks: nothing happens (no session, no helper in any order, no order created, nobody takes an order).
     */
    private static void watchNothing(final GameTestHelper helper, final Site site, final ICitizenData idle, final int expectedOrders)
    {
        final int[] ticks = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            helper.getLevel().getServer().clockManager().setTotalTicks(helper.getLevel().registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
            site.tick();
            helper.assertTrue(IdleCitizenArbiter.sessionOf(idle) == null, "a session: " + describe(site));
            helper.assertTrue(idle.getJob() == null, "the citizen got a job");
            helper.assertTrue(site.colony.getWorkManager().getWorkOrders().size() == expectedOrders, "orders: " + site.colony.getWorkManager().getWorkOrders().size() + ", expected " + expectedOrders);
            for (final IServerWorkOrder order : site.colony.getWorkManager().getWorkOrders().values())
            {
                helper.assertTrue(!order.isClaimed() || site.hut == null || order.getClaimedBy().equals(site.hut.getID()), "the order was claimed by " + order.getClaimedBy());
                helper.assertTrue(!(order instanceof IBuilderWorkOrder b) || b.getCollab().getAssistantCount() == 0, "the order has a helper: " + describe(site));
            }
            if ((ticks[0] += 20) < 600)
            {
                helper.runAfterDelay(20, () -> step[0].run());
                return;
            }
            end();
            helper.succeed();
        };
        helper.runAfterDelay(1, step[0]);
    }

    /**
     * A builder with nothing to build and an unemployed citizen: the citizen does nothing, and no order appears.
     */
    public static void noOrderNothingHappens(final GameTestHelper helper)
    {
        begin();
        site(helper, "idle helper no order", 0, true, site -> watchNothing(helper, site, site.spawnIdle(12, 14), 0));
    }

    /**
     * An order that no builder leads (the colony has no builder at all) and an unemployed citizen: he never claims or helps it, and creates nothing.
     */
    public static void unledOrderIsNotClaimed(final GameTestHelper helper)
    {
        begin();
        site(helper, "idle helper unled order", 0, false, site -> {
            final ICitizenData idle = site.spawnIdle(12, 14);
            site.order("unled", 20, 8, 20, false);
            watchNothing(helper, site, idle, 1);
        });
    }

    // ------------------------------------------------------------------ leaving

    /**
     * Gets a helper at work on a big order: waits until he has placed some blocks.
     */
    private static void withWorkingHelper(final GameTestHelper helper, final String name, final int helpers, final java.util.function.BiConsumer<Site, WorkOrderDecoration> body)
    {
        begin();
        site(helper, name, 0, true, site -> {
            for (int i = 0; i < helpers; i++)
            {
                site.spawnIdle(12 + 2 * i, 14);
            }
            site.stock(30 * 30 + SPARE);
            final WorkOrderDecoration order = site.order("big", 20, 8, 30, true);
            poll(helper, List.of(site), 12_000, () -> {
                final int onTheWay = (int) site.idle.stream().filter(c -> IdleCitizenArbiter.sessionOf(c) != null).count();
                return onTheWay == helpers && site.idle.stream().allMatch(c -> placedBy(IdleHelperKey.of(c.getId())) > 0);
            }, () -> body.accept(site, order), () -> describe(site));
        });
    }

    /**
     * A helper at work gets a job (a sawmill): he stops helping, his leases go back, the lead goes on and finishes the order.
     */
    public static void helperLeavesWhenGivenAJob(final GameTestHelper helper)
    {
        withWorkingHelper(helper, "idle helper job", 1, (site, order) -> {
            final ICitizenData idle = site.idle.get(0);
            final BlockPos key = IdleHelperKey.of(idle.getId());
            helper.assertTrue(order.getCollab().hasAssistant(key), "the helper is not listed in the order: " + describe(site));
            final IBuilding sawmill = MinecoloniesGameTests.placeProductionBuilding(helper, site.colony, ModBlocks.blockHutSawmill, new BlockPos(8, 1, 22), "craftsmanship/carpentry/sawmill1.blueprint");
            helper.assertTrue(sawmill.getModule(BuildingModules.SAWMILL_WORK).assignCitizen(idle), "the citizen was not hired by the sawmill");
            final int[] placedAtJob = {placedBy(key)};
            poll(helper, List.of(site), 600, () -> IdleCitizenArbiter.sessionOf(idle) == null && !order.getCollab().hasAssistant(key), () -> {
                helper.assertTrue(order.getCollab().getLeaseCountOf(key) == 0, "his leases are still open: " + describe(site));
                helper.assertTrue(idle.getJob() != null, "he has no job");
                final long placedAfter = placedBy(key);
                // (what he had in his hands when the job came may still land: no more than the small batch)
                helper.assertTrue(placedAfter - placedAtJob[0] <= 8, "he went on placing after the job: " + placedAfter + " vs " + placedAtJob[0]);
                helper.assertTrue(site.colony.getWorkManager().getAssistedOrder(key) == null, "the work manager still has him as a helper");
                // a citizen with a job is not taken again, and the lead goes on alone
                helper.runAfterDelay(120, () -> {
                    site.tick();
                    helper.assertTrue(IdleCitizenArbiter.sessionOf(idle) == null && !order.getCollab().hasAssistant(key), "he came back: " + describe(site));
                    helper.assertTrue(site.hut.hasWorkOrder() && site.hut.getWorkOrder() == order, "the lead lost his order: " + describe(site));
                    end();
                    helper.succeed();
                });
            }, () -> describe(site));
        });
    }

    /**
     * The night comes: the helper stops (the work hours are over), and is not offered work again until the day.
     */
    public static void helperLeavesAtNight(final GameTestHelper helper)
    {
        withWorkingHelper(helper, "idle helper night", 1, (site, order) -> {
            final ICitizenData idle = site.idle.get(0);
            final BlockPos key = IdleHelperKey.of(idle.getId());
            final int[] waited = {0};
            final Runnable[] step = new Runnable[1];
            step[0] = () -> {
                helper.getLevel().getServer().clockManager().setTotalTicks(helper.getLevel().registryAccess().getOrThrow(WorldClocks.OVERWORLD), 11_500L);
                site.tick();
                if (IdleCitizenArbiter.sessionOf(idle) == null && !order.getCollab().hasAssistant(key))
                {
                    helper.assertTrue(order.getCollab().getLeaseCountOf(key) == 0, "his leases are still open at night");
                    // nothing new for him while the night lasts
                    helper.runAfterDelay(200, () -> {
                        helper.getLevel().getServer().clockManager().setTotalTicks(helper.getLevel().registryAccess().getOrThrow(WorldClocks.OVERWORLD), 11_500L);
                        site.tick();
                        helper.assertTrue(IdleCitizenArbiter.sessionOf(idle) == null, "work offered at night");
                        end();
                        helper.succeed();
                    });
                    return;
                }
                if ((waited[0] += 20) > 400)
                {
                    end();
                    helper.fail("the helper did not stop at night: " + describe(site));
                    return;
                }
                helper.runAfterDelay(20, () -> step[0].run());
            };
            step[0].run();
        });
    }

    /**
     * A helper gets hungry: the citizen AI sends him to eat (his task ends, he is in no labour state), and the order does not keep his leases.
     */
    public static void helperLeavesForNeeds(final GameTestHelper helper)
    {
        withWorkingHelper(helper, "idle helper needs", 1, (site, order) -> {
            final ICitizenData idle = site.idle.get(0);
            final BlockPos key = IdleHelperKey.of(idle.getId());
            idle.setSaturation(0.0D);
            final int[] waited = {0};
            final Runnable[] step = new Runnable[1];
            step[0] = () -> {
                helper.getLevel().getServer().clockManager().setTotalTicks(helper.getLevel().registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
                site.tick();
                idle.setSaturation(0.0D);
                if (IdleCitizenArbiter.sessionOf(idle) == null && !order.getCollab().hasAssistant(key))
                {
                    helper.assertTrue(order.getCollab().getLeaseCountOf(key) == 0, "his leases are still open: " + describe(site));
                    final boolean labouring = idle.getEntity().map(e -> e instanceof EntityCitizen ec && ec.getCitizenAI().getState() == CitizenAIState.LABOUR).orElse(false);
                    helper.assertTrue(!labouring, "he is still in the labour state");
                    end();
                    helper.succeed();
                    return;
                }
                if ((waited[0] += 20) > 600)
                {
                    end();
                    helper.fail("the helper did not leave when hungry: " + describe(site));
                    return;
                }
                helper.runAfterDelay(20, () -> step[0].run());
            };
            step[0].run();
        });
    }

    // ------------------------------------------------------------------ the setting and the cap

    /**
     * The colony setting goes off while citizens help: they all stop within a second, the order keeps no helper and no lease of theirs; on again, they come back.
     */
    public static void settingOffSendsHelpersAway(final GameTestHelper helper)
    {
        withWorkingHelper(helper, "idle helper setting", 2, (site, order) -> {
            setIdleHelpers(site.colony, false);
            site.tick();
            for (final ICitizenData idle : site.idle)
            {
                final BlockPos key = IdleHelperKey.of(idle.getId());
                helper.assertTrue(IdleCitizenArbiter.sessionOf(idle) == null && !order.getCollab().hasAssistant(key) && order.getCollab().getLeaseCountOf(key) == 0,
                  "a helper with the setting off: " + describe(site));
            }
            helper.assertTrue(order.getCollab().getIdleAssistantCount() == 0, "idle helpers in the order with the setting off");
            setIdleHelpers(site.colony, true);
            poll(helper, List.of(site), 1_500, () -> order.getCollab().getIdleAssistantCount() == 2, () -> {
                end();
                helper.succeed();
            }, () -> describe(site));
        });
    }

    /**
     * The colony's limit of idle helpers per builder: three citizens, a limit of one (then two): exactly that many help; the limit of the player for the order counts
     * them with the builders'.
     */
    public static void perBuilderCap(final GameTestHelper helper)
    {
        begin();
        site(helper, "idle helper cap", 0, true, site -> {
            for (int i = 0; i < 3; i++)
            {
                site.spawnIdle(12 + 2 * i, 14);
            }
            site.stock(30 * 30 + SPARE);
            final WorkOrderDecoration order = site.order("cap", 20, 8, 30, true);
            setPerBuilder(site.colony, 1);
            final int[] phase = {0};
            poll(helper, List.of(site), 6_000, () -> {
                final int helping = order.getCollab().getIdleAssistantCount();
                helper.assertTrue(helping <= IdleCitizenSettings.helpersPerBuilder(site.colony), "more idle helpers (" + helping + ") than the limit " + IdleCitizenSettings.helpersPerBuilder(site.colony) + ": " + describe(site));
                if (phase[0] == 0 && helping == 1)
                {
                    setPerBuilder(site.colony, 2);
                    phase[0] = 1;
                }
                else if (phase[0] == 1 && helping == 2)
                {
                    // the player's limit for the order: one in all (the idle ones count): the newest go
                    order.getCollab().setMaxAssistantsSetting(1);
                    phase[0] = 2;
                }
                else if (phase[0] == 2 && helping == 1)
                {
                    order.getCollab().setMaxAssistantsSetting(0);
                    phase[0] = 3;
                }
                else if (phase[0] == 3 && helping == 0)
                {
                    return true;
                }
                return false;
            }, () -> {
                final var view = CollabViews.of(site.colony, order);
                helper.assertTrue(view.assistants().isEmpty() && view.maxIdleAssistants() == 0, "view: " + view.assistants() + " idle cap " + view.maxIdleAssistants());
                end();
                helper.succeed();
            }, () -> "phase " + phase[0] + " " + describe(site));
        });
    }

    // ------------------------------------------------------------------ colonies are isolated

    /**
     * Two colonies in one world, each with a builder, an order and an unemployed citizen: every citizen helps the builder of his own colony and only him. A citizen of one
     * colony offered to the task of the other is refused, and turning the setting off in one colony leaves the other's helper at work.
     */
    public static void coloniesAreIsolated(final GameTestHelper helper)
    {
        begin();
        final Site[] sites = new Site[2];
        final int[] ready = {0};
        final Runnable[] go = new Runnable[1];
        go[0] = () -> {
            for (final Site site : sites)
            {
                site.spawnIdle(12, 14);
                site.stock(30 * 30 + SPARE);
            }
            final WorkOrderDecoration a = sites[0].order("iso-a", 20, 8, 30, true);
            final WorkOrderDecoration b = sites[1].order("iso-b", 20, 8, 30, true);
            final boolean[] checkedRefusal = {false};
            final boolean[] switchedOff = {false};
            poll(helper, List.of(sites[0], sites[1]), 14_000, () -> {
                final ICitizenData ia = sites[0].idle.get(0);
                final ICitizenData ib = sites[1].idle.get(0);
                // never a helper of the other colony in an order: the only helpers are the colony's own citizens (their keys are per colony, so check the citizens)
                helper.assertTrue(a.getCollab().getIdleAssistantCount() <= 1 && b.getCollab().getIdleAssistantCount() <= 1, "more than one helper: " + describe(sites[0]) + " | " + describe(sites[1]));
                if (!checkedRefusal[0])
                {
                    // a citizen of colony 1 is not taken by the builder helper task of colony 0, though the order of 0 has room
                    helper.assertTrue(new BuilderHelperTask().tryStart(sites[0].colony, ib) == null, "colony 0's task took a citizen of colony 1");
                    helper.assertTrue(new BuilderHelperTask().tryStart(sites[1].colony, ia) == null, "colony 1's task took a citizen of colony 0");
                    checkedRefusal[0] = true;
                }
                final boolean aHelps = IdleCitizenArbiter.sessionOf(ia) != null && placedBy(IdleHelperKey.of(ia.getId())) > 0;
                final boolean bHelps = IdleCitizenArbiter.sessionOf(ib) != null && placedBy(IdleHelperKey.of(ib.getId())) > 0;
                if (!switchedOff[0] && aHelps && bHelps)
                {
                    helper.assertTrue(sites[0].colony.getWorkManager().getAssistedOrder(IdleHelperKey.of(ia.getId())) == a, "colony 0's helper helps another order");
                    helper.assertTrue(sites[1].colony.getWorkManager().getAssistedOrder(IdleHelperKey.of(ib.getId())) == b, "colony 1's helper helps another order");
                    setIdleHelpers(sites[0].colony, false);
                    switchedOff[0] = true;
                }
                if (switchedOff[0])
                {
                    return IdleCitizenArbiter.sessionOf(ia) == null && a.getCollab().getIdleAssistantCount() == 0 && IdleCitizenArbiter.sessionOf(ib) != null
                             && b.getCollab().getIdleAssistantCount() == 1;
                }
                return false;
            }, () -> {
                end();
                helper.succeed();
            }, () -> describe(sites[0]) + " | " + describe(sites[1]));
        };
        sites[0] = site(helper, "idle helper iso 0", 0, true, s -> {
            if (++ready[0] == 2)
            {
                go[0].run();
            }
        });
        sites[1] = site(helper, "idle helper iso 1", 200, true, s -> {
            if (++ready[0] == 2)
            {
                go[0].run();
            }
        });
    }

    /**
     * The arbiter's API: another task can be registered, is offered the free citizens by priority, and a session it hands out is stopped with the reason the citizen
     * lost his freedom for.
     */
    public static void arbiterOffersTasksByPriority(final GameTestHelper helper)
    {
        begin();
        final List<String> offered = new ArrayList<>();
        final List<IdleStopReason> stopped = new ArrayList<>();
        final com.minecolonies.api.colony.idle.IIdleCitizenTask high = new com.minecolonies.api.colony.idle.IIdleCitizenTask()
        {
            @Override
            public String getId()
            {
                return "test_high";
            }

            @Override
            public int getPriority()
            {
                return 1000;
            }

            @Override
            public IIdleTaskSession tryStart(final IColony colony, final ICitizenData citizen)
            {
                offered.add("high:" + citizen.getId());
                return null;
            }
        };
        final com.minecolonies.api.colony.idle.IIdleCitizenTask low = new com.minecolonies.api.colony.idle.IIdleCitizenTask()
        {
            @Override
            public String getId()
            {
                return "test_low";
            }

            @Override
            public int getPriority()
            {
                return -5;
            }

            @Override
            public IIdleTaskSession tryStart(final IColony colony, final ICitizenData citizen)
            {
                offered.add("low:" + citizen.getId());
                return new IIdleTaskSession()
                {
                    @Override
                    public String getTaskId()
                    {
                        return "test_low";
                    }

                    @Override
                    public boolean isValid()
                    {
                        return true;
                    }

                    @Override
                    public void tick()
                    {
                    }

                    @Override
                    public void stop(final IdleStopReason reason)
                    {
                        stopped.add(reason);
                    }

                    @Override
                    public net.minecraft.network.chat.Component describe()
                    {
                        return net.minecraft.network.chat.Component.literal("test");
                    }
                };
            }
        };
        IdleCitizenArbiter.register(high);
        IdleCitizenArbiter.register(low);
        site(helper, "idle arbiter api", 0, false, site -> {
            final ICitizenData idle = site.spawnIdle(12, 14);
            helper.getLevel().getServer().clockManager().setTotalTicks(helper.getLevel().registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
            IdleCitizenArbiter.tick(site.colony);
            final List<String> order = new ArrayList<>(offered);
            helper.assertTrue(IdleCitizenArbiter.tasks().get(0) == high && IdleCitizenArbiter.tasks().stream().map(com.minecolonies.api.colony.idle.IIdleCitizenTask::getId).toList()
                                .containsAll(List.of("builder_helper", "test_high", "test_low")), "tasks: " + IdleCitizenArbiter.tasks().stream().map(com.minecolonies.api.colony.idle.IIdleCitizenTask::getId).toList());
            helper.assertTrue(order.size() >= 2 && order.get(0).startsWith("high:") && order.get(1).startsWith("low:"), "offered in the wrong order: " + order);
            helper.assertTrue(IdleCitizenArbiter.sessionOf(idle) != null && IdleCitizenArbiter.sessionOf(idle).getTaskId().equals("test_low"), "no session of the low task");
            // he gets a job: the session ends with GOT_JOB (the task of a lower priority than the builder helper is no different)
            final IBuilding sawmill = MinecoloniesGameTests.placeProductionBuilding(helper, site.colony, ModBlocks.blockHutSawmill, new BlockPos(8, 1, 22), "craftsmanship/carpentry/sawmill1.blueprint");
            helper.assertTrue(sawmill.getModule(BuildingModules.SAWMILL_WORK).assignCitizen(idle), "not hired");
            IdleCitizenArbiter.tick(site.colony);
            helper.assertTrue(IdleCitizenArbiter.sessionOf(idle) == null && stopped.equals(List.of(IdleStopReason.GOT_JOB)), "stop reasons: " + stopped);
            // (the test tasks stay registered in this JVM only until the next registration of the same ids: unregister them by replacing with refusing tasks)
            IdleCitizenArbiter.unregister("test_high");
            IdleCitizenArbiter.unregister("test_low");
            end();
            helper.succeed();
        });
    }
}
