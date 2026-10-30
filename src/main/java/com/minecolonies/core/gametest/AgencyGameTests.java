package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.research.util.ResearchConstants;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;
import com.minecolonies.core.colony.idle.IdleCitizenArbiter;
import com.minecolonies.core.colony.idle.IdleCitizenSettings;
import com.minecolonies.core.colony.idle.WorkerHelp;
import com.minecolonies.core.colony.labour.LabourClaim;
import com.minecolonies.core.colony.labour.LabourSession;
import com.minecolonies.core.colony.labour.LabourSettings;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic;
import com.minecolonies.core.entity.ai.workers.CitizenAI;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.research.GlobalResearchEffect;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Round 3 agency (improve-all AI run, rounds/r1.md round 3 plan, lane C): citizens act on their own. Group {@code agency} ({@code MINECOLONIES_GAMETEST_ONLY=agency}):
 * <ul>
 *   <li>{@code agency_rain_research}: one rain rule (R2-F3): a jobless citizen does not labour in the rain while the workers would not work either, and labours as soon
 *   as the colony has the rain research;</li>
 *   <li>{@code agency_worker_helps_and_returns}: a builder without an order helps a hut nearby that asked for a pickaxe (labour, as a helper), and goes back to his hut
 *   the moment his hut gets an order;</li>
 *   <li>{@code agency_tool_from_storage}: a Forester without an axe that nobody brings fetches the stone axe out of the warehouse himself;</li>
 *   <li>{@code agency_tool_made_from_materials}: storage has no axe but planks: the Forester makes a wooden axe at the crafting table from them;</li>
 *   <li>{@code agency_no_tool_without_materials}: no axe, no materials: nothing is made, no tree is felled for it, and the Forester says what is missing.</li>
 * </ul>
 * Real colonies, real citizens with their AI; the colonies are ticked by the test.
 */
final class AgencyGameTests
{
    static final String[] NAMES = {"agency_rain_research", "agency_worker_helps_and_returns", "agency_tool_from_storage", "agency_tool_made_from_materials",
      "agency_no_tool_without_materials"};

    private AgencyGameTests()
    {
    }

    static Consumer<GameTestHelper> test(final String name)
    {
        return switch (name)
        {
            case "agency_rain_research" -> AgencyGameTests::rainResearch;
            case "agency_worker_helps_and_returns" -> AgencyGameTests::workerHelpsAndReturns;
            case "agency_tool_from_storage" -> AgencyGameTests::toolFromStorage;
            case "agency_tool_made_from_materials" -> AgencyGameTests::toolMadeFromMaterials;
            default -> AgencyGameTests::noToolWithoutMaterials;
        };
    }

    // ------------------------------------------------------------------ scene

    private static void begin()
    {
        LabourSettings.clearOverrides();
        LabourSettings.override("early", false);
        LabourSettings.override("workScale", 8.0D);
        LabourSettings.override("keepTrees", 0);
        LabourSettings.override("stockpile", false);
        IdleCitizenSettings.overrideForTests(null, null, null);
        IdleCitizenSettings.overrideWorkersHelpForTests(true);
        WorkerHelp.overrideIdleTicksForTests(100L);
        AbstractEntityAIBasic.overrideToolWaitForTests(100L);
    }

    private static Boolean rainWorkBefore;

    private static void end(final GameTestHelper helper)
    {
        LabourSettings.clearOverrides();
        IdleCitizenSettings.overrideWorkersHelpForTests(null);
        WorkerHelp.overrideIdleTicksForTests(null);
        AbstractEntityAIBasic.overrideToolWaitForTests(null);
        if (rainWorkBefore != null)
        {
            MineColonies.getConfig().getServer().workersAlwaysWorkInRain.set(rainWorkBefore);
            rainWorkBefore = null;
        }
        final var weather = helper.getLevel().getWeatherData();
        weather.setRaining(false);
        weather.setThundering(false);
        weather.setClearWeatherTime(1_000_000);
    }

    /**
     * The fixture colony (builder hut, warehouse with racks), auto hiring off, the plot cleared of trees, tables and loose items.
     */
    private static RsFixture found(final GameTestHelper helper, final String name)
    {
        final RsFixture f = RsFixture.found(helper, name, 2);
        final var hiring = f.colony.getSettings().getSetting(BuildingTownHall.AUTO_HIRING_MODE);
        if (hiring.getValue())
        {
            hiring.trigger();
        }
        final BlockPos min = helper.absolutePos(new BlockPos(-8, 0, -8));
        final BlockPos max = helper.absolutePos(new BlockPos(48, 24, 32));
        for (final BlockPos pos : BlockPos.betweenClosed(Math.min(min.getX(), max.getX()), min.getY(), Math.min(min.getZ(), max.getZ()), Math.max(min.getX(), max.getX()),
          max.getY(), Math.max(min.getZ(), max.getZ())))
        {
            final var state = f.level.getBlockState(pos);
            if (state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.LEAVES) || state.is(Blocks.CRAFTING_TABLE))
            {
                f.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
        }
        return f;
    }

    /**
     * A Forester hut with a worker who carries nothing (no axe).
     */
    private static ICitizenData forester(final GameTestHelper helper, final RsFixture f)
    {
        final IBuilding hut = f.place(ModBlocks.blockHutLumberjack, new BlockPos(8, 1, 24), "fundamentals/lumberjack1.blueprint");
        final ICitizenData worker = f.spawn(new BlockPos(14, 1, 16), false);
        helper.assertTrue(hut.getModule(BuildingModules.FORESTER_WORK).assignCitizen(worker), "forester assignment failed");
        for (int slot = 0; slot < worker.getInventory().getSlots(); slot++)
        {
            worker.getInventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
        return worker;
    }

    private static int carried(final ICitizenData citizen, final Item item)
    {
        return InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), s -> s.is(item));
    }

    private static int inHut(final IBuilding hut, final Item item)
    {
        return InventoryUtils.getItemCountInItemHandler(hut.getItemHandlerCap(), s -> s.is(item));
    }

    private static int logsLeft(final GameTestHelper helper, final int x, final int z, final int height)
    {
        int n = 0;
        for (int y = 1; y <= height; y++)
        {
            if (helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.OAK_LOG))
            {
                n++;
            }
        }
        return n;
    }

    private static AbstractEntityAIBasic<?, ?> ai(final ICitizenData citizen)
    {
        return citizen.getJob() != null && citizen.getJob().getWorkerAI() instanceof AbstractEntityAIBasic<?, ?> ai ? ai : null;
    }

    private static String describe(final RsFixture f, final ICitizenData citizen)
    {
        final Colony colony = (Colony) f.colony;
        final var session = IdleCitizenArbiter.sessionOf(citizen);
        final var ai = ai(citizen);
        final String state = citizen.getEntity().orElse(null) instanceof EntityCitizen e ? String.valueOf(e.getCitizenAI().getState()) : "-";
        return "citizen state " + state + ", ai " + (ai == null ? "-" : ai.getState() + " tool " + ai.toolSelfHelp()) + ", session "
                 + (session == null ? "none" : session.getTaskId() + (session instanceof LabourSession s ? "/" + s.phaseName() : "")) + ", claim "
                 + colony.getLabourBoard().claimOf(citizen.getId()) + ", inv " + LabourSession.inventoryOf(citizen) + ", at "
                 + citizen.getEntity().map(e -> e.blockPosition().toShortString()).orElse("-");
    }

    /**
     * Calls {@code check} every 20 ticks, after ticking the colonies and setting the day, until it returns true; fails with the message after {@code maxTicks}.
     */
    private static void poll(final GameTestHelper helper, final Colony colony, final int maxTicks, final BooleanSupplier check, final Runnable onDone,
      final Supplier<String> failure)
    {
        final int[] elapsed = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            helper.getLevel().getServer().clockManager().setTotalTicks(helper.getLevel().registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
            colony.getRequestManager().tick();
            IdleCitizenArbiter.tick(colony);
            colony.getLabourBoard().tick();
            if (check.getAsBoolean())
            {
                onDone.run();
                return;
            }
            if (elapsed[0] % 500 == 0)
            {
                Log.getLogger().info("AGENCY poll t={} {}", elapsed[0], failure.get());
            }
            elapsed[0] += 20;
            if (elapsed[0] >= maxTicks)
            {
                end(helper);
                helper.assertTrue(false, "timeout after " + maxTicks + " ticks: " + failure.get());
                return;
            }
            helper.runAfterDelay(20, () -> step[0].run());
        };
        helper.runAfterDelay(1, step[0]);
    }

    private static void rain(final GameTestHelper helper)
    {
        final var weather = helper.getLevel().getWeatherData();
        weather.setClearWeatherTime(0);
        weather.setRaining(true);
        weather.setThundering(false);
        weather.setRainTime(1_000_000);
    }

    // ------------------------------------------------------------------ one rain rule

    /**
     * Rain, and the server does not let workers work in the rain. Without the rain research a jobless citizen is not free (the same rule as for a worker) and takes no
     * labour; with the research he is free and starts on the request while it still rains.
     */
    public static void rainResearch(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "agency rain");
        final Colony colony = (Colony) f.colony;
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        LabourGameTests.tree(helper, 34, 20, 5);
        final var config = MineColonies.getConfig().getServer().workersAlwaysWorkInRain;
        if (rainWorkBefore == null)
        {
            rainWorkBefore = config.get();
        }
        config.set(false);
        rain(helper);
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final IToken<?> token = f.builder.createRequest(new Stack(new ItemStack(Items.WOODEN_PICKAXE), 1, 1), false);
            helper.assertTrue(helper.getLevel().isRaining(), "fixture: it does not rain");
            helper.assertTrue(!CitizenAI.worksInRain(colony, null), "fixture: the rain stops work without the research");
            final int[] ticks = {0};
            final boolean[] researched = {false};
            poll(helper, colony, 6000, () -> {
                rain(helper);
                ticks[0] += 20;
                if (!researched[0])
                {
                    helper.assertTrue(IdleCitizenArbiter.stoppedByRain(colony, idle), "the rain does not stop a jobless citizen although it stops the workers");
                    helper.assertTrue(IdleCitizenArbiter.sessionOf(idle) == null && colony.getLabourBoard().claimOf(idle.getId()) == null,
                      "a jobless citizen labours in a rain that stops the workers: " + describe(f, idle));
                    if (ticks[0] >= 400)
                    {
                        colony.getResearchManager().getResearchEffects().applyEffect(new GlobalResearchEffect(ResearchConstants.WORKING_IN_RAIN, "rain", "rain", 1.0D, 1.0D));
                        helper.assertTrue(CitizenAI.worksInRain(colony, null), "fixture: the research does not let work go on in the rain");
                        researched[0] = true;
                    }
                    return false;
                }
                helper.assertTrue(!IdleCitizenArbiter.stoppedByRain(colony, idle), "with the research the rain still stops the jobless");
                return IdleCitizenArbiter.sessionOf(idle) != null && colony.getLabourBoard().claimOf(idle.getId()) != null && helper.getLevel().isRaining();
            }, () -> {
                Log.getLogger().info("[agency_rain_research] labour started in the rain after the research: {}", describe(f, idle));
                end(helper);
                helper.succeed();
            }, () -> describe(f, idle) + ", request " + f.describe(token));
        });
    }

    // ------------------------------------------------------------------ a worker helps and goes back

    /**
     * A builder whose hut has no order helps after a while: another hut nearby asks for a wooden pickaxe that nobody makes, and he takes it on as a helper (his claim
     * says so, he shows the helping text, he gets on with it: a log of the tree is cut). Then his own hut gets an order: within seconds the help ends, nothing is held
     * for it, and he is back at his own work.
     */
    public static void workerHelpsAndReturns(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "agency helper");
        final Colony colony = (Colony) f.colony;
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        // (the tree stands clear of the asking hut: builder1 with its margin covers x 24 to 42, z 18 to 34, and labour never fells a tree in a hut's box)
        LabourGameTests.tree(helper, 44, 20, 6);
        final IBuilding asker = f.place(ModBlocks.blockHutBuilder, new BlockPos(36, 1, 24), "fundamentals/builder1.blueprint");
        helper.runAfterDelay(100, () -> {
            final ICitizenData builder = f.spawn(new BlockPos(12, 1, 8), false);
            helper.assertTrue(f.builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(builder), "builder not assigned");
            final IToken<?> token = asker.createRequest(new Stack(new ItemStack(Items.WOODEN_PICKAXE), 1, 1), false);
            final boolean[] ordered = {false};
            final int[] after = {0};
            poll(helper, colony, 9000, () -> {
                if (!ordered[0])
                {
                    final LabourClaim claim = colony.getLabourBoard().claimOf(builder.getId());
                    if (claim == null || !WorkerHelp.isHelping(builder))
                    {
                        return false;
                    }
                    helper.assertTrue(claim.byWorker() && claim.destination().equals(asker.getID()) && token.equals(claim.request()),
                      "the helper's claim is not the asking hut's request: " + claim.destination() + " " + claim.request());
                    final var session = IdleCitizenArbiter.sessionOf(builder);
                    helper.assertTrue(session != null && session.describe().getContents() instanceof TranslatableContents t
                                        && t.getKey().equals("com.minecolonies.coremod.labour.task.worker"), "the helper does not say that he helps out: " + describe(f, builder));
                    if (logsLeft(helper, 44, 20, 6) == 6)
                    {
                        return false;
                    }
                    // his own work comes back
                    LabourGameTests.planksFloor(helper, colony, f.builder, 22, 12, 1);
                    ordered[0] = true;
                    return false;
                }
                after[0] += 20;
                IdleCitizenArbiter.tick(colony);
                final boolean back = IdleCitizenArbiter.sessionOf(builder) == null && colony.getLabourBoard().claimOf(builder.getId()) == null
                                       && builder.getEntity().orElse(null) instanceof EntityCitizen e && e.getCitizenAI().getState() != CitizenAIState.LABOUR;
                helper.assertTrue(back || after[0] < 400, "the builder did not go back to his own work: " + describe(f, builder));
                return back;
            }, () -> {
                Log.getLogger().info("[agency_worker_helps_and_returns] back at work after {} ticks: {}", after[0], describe(f, builder));
                end(helper);
                helper.succeed();
            }, () -> describe(f, builder) + ", request " + f.describe(token) + ", logs left " + logsLeft(helper, 44, 20, 6));
        });
    }

    // ------------------------------------------------------------------ a worker gets his own tool

    /**
     * The warehouse holds a stone axe; the colony has no courier to bring it. After the wait the Forester walks to the warehouse and takes it himself: the axe is in
     * his hands, not in the warehouse any more, and nothing else was made.
     */
    public static void toolFromStorage(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "agency tool stock");
        final Colony colony = (Colony) f.colony;
        f.stock(0, new ItemStack(Items.STONE_AXE));
        helper.runAfterDelay(100, () -> {
            final ICitizenData worker = forester(helper, f);
            final boolean[] fetching = {false};
            poll(helper, colony, 6000, () -> {
                final var ai = ai(worker);
                fetching[0] |= ai != null && ai.toolSelfHelp() == AbstractEntityAIBasic.ToolSelfHelp.FETCH;
                helper.assertTrue(ai == null || ai.toolSelfHelp() != AbstractEntityAIBasic.ToolSelfHelp.MAKE, "he makes an axe although the warehouse holds one");
                return carried(worker, Items.STONE_AXE) == 1;
            }, () -> {
                helper.assertTrue(fetching[0], "he got the axe without fetching it himself (nobody else could bring it)");
                helper.assertTrue(f.physical(s -> s.is(Items.STONE_AXE)) == 0, "the stone axe is still in the warehouse");
                helper.assertTrue(carried(worker, Items.WOODEN_AXE) == 0, "a wooden axe was made");
                end(helper);
                helper.succeed();
            }, () -> describe(f, worker) + ", stone axes in the warehouse " + f.physical(s -> s.is(Items.STONE_AXE)));
        });
    }

    /**
     * No axe in storage, but planks in the warehouse and a crafting table: the Forester makes a wooden axe himself at the table from the stored planks.
     */
    public static void toolMadeFromMaterials(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "agency tool make");
        final Colony colony = (Colony) f.colony;
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        f.stock(0, new ItemStack(Items.OAK_PLANKS, 16));
        helper.runAfterDelay(100, () -> {
            final ICitizenData worker = forester(helper, f);
            final boolean[] making = {false};
            poll(helper, colony, 9000, () -> {
                final var ai = ai(worker);
                making[0] |= ai != null && ai.toolSelfHelp() == AbstractEntityAIBasic.ToolSelfHelp.MAKE;
                return carried(worker, Items.WOODEN_AXE) == 1;
            }, () -> {
                helper.assertTrue(making[0], "he did not make the axe himself");
                final int planks = f.physical(s -> s.is(Items.OAK_PLANKS));
                helper.assertTrue(planks <= 16 - 3, "the axe was not made from the stored planks: " + planks + " left of 16");
                end(helper);
                helper.succeed();
            }, () -> describe(f, worker) + ", planks in the warehouse " + f.physical(s -> s.is(Items.OAK_PLANKS)));
        });
    }

    /**
     * No axe and no materials in storage, a natural tree in reach and a crafting table: nothing is made, the tree stands, and the Forester says what is missing (and
     * holds no labour claim while he waits).
     */
    public static void noToolWithoutMaterials(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "agency tool none");
        final Colony colony = (Colony) f.colony;
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        LabourGameTests.tree(helper, 34, 20, 5);
        helper.runAfterDelay(100, () -> {
            final ICitizenData worker = forester(helper, f);
            final int[] since = {-1};
            final int[] ticks = {0};
            poll(helper, colony, 4000, () -> {
                ticks[0] += 20;
                final var ai = ai(worker);
                helper.assertTrue(carried(worker, Items.WOODEN_AXE) == 0 && carried(worker, Items.STONE_AXE) == 0, "an axe came out of nothing: " + describe(f, worker));
                helper.assertTrue(logsLeft(helper, 34, 20, 5) == 5, "a log was cut for the axe");
                if (since[0] < 0 && ai != null && ai.toolSelfHelp() == AbstractEntityAIBasic.ToolSelfHelp.NOTHING)
                {
                    since[0] = ticks[0];
                }
                if (since[0] >= 0)
                {
                    final LabourClaim claim = colony.getLabourBoard().claimOf(worker.getId());
                    helper.assertTrue(claim == null, "a claim is left after the attempt: " + claim);
                }
                return since[0] >= 0 && ticks[0] - since[0] >= 600;
            }, () -> {
                helper.assertTrue(inHut(f.builder, Items.WOODEN_AXE) == 0 && f.physical(s -> s.is(Items.WOODEN_AXE)) == 0, "a wooden axe was made somewhere");
                end(helper);
                helper.succeed();
            }, () -> describe(f, worker));
        });
    }
}
