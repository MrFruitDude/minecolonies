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
 *   <li>{@code agency_no_tool_without_materials}: no axe, no materials: nothing is made, no tree is felled for it, and the Forester says what is missing at
 *   once, without first saying he makes one;</li>
 *   <li>{@code agency_tool_help_stops_when_off} (round 4, r3 Sol H2): "Workers take initiative" turned off during the help stops it before anything more is
 *   taken or used; turned on again, he makes the tool, and no plank goes missing;</li>
 *   <li>{@code agency_tool_fetch_fails_retries} (round 4, r3 Fable N21): the axe he goes for is taken before he gets there: he says he could not get it now
 *   (not that the colony has nothing) and tries again soon, and fetches it once it is back;</li>
 *   <li>{@code agency_crafter_helps} (round 4, r3 Fable N16): an idle sawmill worker carrying seeds and a hoe helps a nearby hut (logs in his pockets keep him
 *   home), keeps his seeds, and leaves the help the moment his hut has a task.</li>
 * </ul>
 * Real colonies, real citizens with their AI; the colonies are ticked by the test.
 */
final class AgencyGameTests
{
    static final String[] NAMES = {"agency_rain_research", "agency_worker_helps_and_returns", "agency_tool_from_storage", "agency_tool_made_from_materials",
      "agency_no_tool_without_materials", "agency_tool_help_stops_when_off", "agency_tool_fetch_fails_retries", "agency_crafter_helps"};

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
            case "agency_tool_help_stops_when_off" -> AgencyGameTests::toolHelpStopsWhenOff;
            case "agency_tool_fetch_fails_retries" -> AgencyGameTests::toolFetchFailsRetries;
            case "agency_crafter_helps" -> AgencyGameTests::crafterHelps;
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
        IdleCitizenSettings.overrideForTests(null, null, null);
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

    /**
     * @return every labour claim of the colony with its citizen (to name who cut what).
     */
    private static String claims(final RsFixture f)
    {
        final Colony colony = (Colony) f.colony;
        final StringBuilder out = new StringBuilder();
        for (final ICitizenData citizen : colony.getCitizenManager().getCitizens())
        {
            final LabourClaim claim = colony.getLabourBoard().claimOf(citizen.getId());
            out.append(citizen.getName()).append(citizen.getJob() == null ? " (jobless)" : "").append(": ").append(claim == null ? "none" : claim.source() + " " + claim.target())
              .append("; ");
        }
        return out.toString();
    }

    /**
     * @return planks counted twice plus sticks plus eight per wooden axe, in the warehouse, the worker's pockets and his hut: 2 planks make 4 sticks, an axe takes
     * 3 planks and 2 sticks, so the sum stays the same however the planks are used.
     */
    private static int plankWorth(final RsFixture f, final ICitizenData worker)
    {
        final IBuilding hut = worker.getWorkBuilding();
        final java.util.function.ToIntFunction<java.util.function.Predicate<ItemStack>> all = p -> f.physical(p) + InventoryUtils.getItemCountInItemHandler(worker.getInventory(), p)
                                                                                                       + (hut == null ? 0 : InventoryUtils.getItemCountInItemHandler(hut.getItemHandlerCap(), p));
        return 2 * all.applyAsInt(s -> s.is(Items.OAK_PLANKS)) + all.applyAsInt(s -> s.is(Items.STICK)) + 8 * all.applyAsInt(s -> s.is(Items.WOODEN_AXE));
    }

    /**
     * @return whether the crafter's 16 seeds are all with him or in his own hut (a dump of his own), none in the asking hut or the warehouse.
     */
    private static boolean seedsHis(final RsFixture f, final ICitizenData crafter, final IBuilding hut, final IBuilding asker)
    {
        return carried(crafter, Items.WHEAT_SEEDS) + inHut(hut, Items.WHEAT_SEEDS) == 16 && inHut(asker, Items.WHEAT_SEEDS) == 0
                 && f.physical(s -> s.is(Items.WHEAT_SEEDS)) == 0;
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
     * holds no labour claim while he waits). He says it at once: no "I make one" first (r3 Fable N17).
     * <p>
     * Round 4 (r3 Fable N14): the jobless labour of the colony is off here. A colony that grows a citizen during the test (run 029: a newcomer appeared) has a
     * free labourer, and he takes the axe request the Forester's AI makes again and fells the tree for it, which is ordinary labour, not the self-help. With
     * nobody else to do it, a felled log can only be the self-help's.
     */
    public static void noToolWithoutMaterials(final GameTestHelper helper)
    {
        begin();
        IdleCitizenSettings.overrideForTests(false, null, null);
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
                helper.assertTrue(logsLeft(helper, 34, 20, 5) == 5, "a log was cut for the axe: " + describe(f, worker) + "; claims " + claims(f));
                helper.assertTrue(ai == null || ai.toolSelfHelp() != AbstractEntityAIBasic.ToolSelfHelp.MAKE, "he says he makes an axe with nothing to make it from");
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

    // ------------------------------------------------------------------ round 4: opt-out, failed fetch, more helpers

    /**
     * r3 Sol H2: planks in the warehouse, no axe. Once he has taken planks out for his axe, the player turns "Workers take initiative" off: the help stops at
     * once (no session, no claim), nothing more leaves the warehouse and nothing is crafted while it is off. Turned on again, he makes the axe; not a plank went
     * missing on the way and he holds no second request for it.
     */
    public static void toolHelpStopsWhenOff(final GameTestHelper helper)
    {
        begin();
        // (nobody else: a newcomer labouring on the axe request would move the planks too)
        IdleCitizenSettings.overrideForTests(false, null, null);
        final RsFixture f = found(helper, "agency tool off");
        final Colony colony = (Colony) f.colony;
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        f.stock(0, new ItemStack(Items.OAK_PLANKS, 16));
        helper.runAfterDelay(100, () -> {
            final ICitizenData worker = forester(helper, f);
            final int worth = plankWorth(f, worker);
            helper.assertTrue(worth == 32, "fixture: plank worth " + worth);
            final int[] offAt = {-1};
            final int[] ticks = {0};
            final int[] warehouseAtOff = {0};
            final boolean[] on = {false};
            poll(helper, colony, 12000, () -> {
                ticks[0] += 20;
                final var ai = ai(worker);
                if (offAt[0] < 0)
                {
                    if (ai != null && ai.isGettingOwnTool() && carried(worker, Items.OAK_PLANKS) > 0 && carried(worker, Items.WOODEN_AXE) == 0)
                    {
                        IdleCitizenSettings.overrideWorkersHelpForTests(false);
                        offAt[0] = ticks[0];
                        warehouseAtOff[0] = f.physical(s -> s.is(Items.OAK_PLANKS));
                        Log.getLogger().info("[agency_tool_help_stops_when_off] turned off: {}", describe(f, worker));
                    }
                    return false;
                }
                if (!on[0])
                {
                    helper.assertTrue(ai == null || !ai.isGettingOwnTool(), "the tool help goes on with initiative off: " + describe(f, worker));
                    helper.assertTrue(colony.getLabourBoard().claimOf(worker.getId()) == null, "a claim is held with initiative off: " + describe(f, worker));
                    helper.assertTrue(f.physical(s -> s.is(Items.OAK_PLANKS)) == warehouseAtOff[0], "planks left the warehouse with initiative off: "
                                                                                                        + f.physical(s -> s.is(Items.OAK_PLANKS)) + " of " + warehouseAtOff[0]);
                    helper.assertTrue(carried(worker, Items.WOODEN_AXE) == 0, "the axe was made with initiative off");
                    helper.assertTrue(plankWorth(f, worker) == 32, "planks went missing when the help stopped: worth " + plankWorth(f, worker));
                    if (ticks[0] - offAt[0] >= 400)
                    {
                        IdleCitizenSettings.overrideWorkersHelpForTests(true);
                        on[0] = true;
                    }
                    return false;
                }
                return carried(worker, Items.WOODEN_AXE) == 1;
            }, () -> {
                helper.assertTrue(plankWorth(f, worker) == 32, "planks went missing on the way to the axe: worth " + plankWorth(f, worker));
                final IBuilding hut = worker.getWorkBuilding();
                helper.assertTrue(hut == null || hut.getOpenRequestsOfTypeFiltered(worker, com.google.common.reflect.TypeToken.of(com.minecolonies.api.colony.requestsystem.requestable.Tool.class),
                  r -> true).isEmpty(), "a tool request is left open after he made the axe");
                end(helper);
                helper.succeed();
            }, () -> describe(f, worker) + ", planks in the warehouse " + f.physical(s -> s.is(Items.OAK_PLANKS)) + ", worth " + plankWorth(f, worker));
        });
    }

    /**
     * r3 Fable N21: a stone axe in the warehouse. He sets out to fetch it; before he gets there the axe is taken out. He does not claim the colony has nothing
     * to make one from: he says he could not get it now and tries again soon. The axe is put back, and he fetches it.
     */
    public static void toolFetchFailsRetries(final GameTestHelper helper)
    {
        begin();
        IdleCitizenSettings.overrideForTests(false, null, null);
        final RsFixture f = found(helper, "agency tool retry");
        final Colony colony = (Colony) f.colony;
        f.stock(0, new ItemStack(Items.STONE_AXE));
        helper.runAfterDelay(100, () -> {
            final ICitizenData worker = forester(helper, f);
            final boolean[] taken = {false};
            final boolean[] retry = {false};
            poll(helper, colony, 9000, () -> {
                final var ai = ai(worker);
                final var help = ai == null ? AbstractEntityAIBasic.ToolSelfHelp.NONE : ai.toolSelfHelp();
                if (!taken[0])
                {
                    if (help == AbstractEntityAIBasic.ToolSelfHelp.FETCH && carried(worker, Items.STONE_AXE) == 0)
                    {
                        // somebody takes the axe out while he walks there
                        final var rack = f.rack(0).getInventory();
                        for (int slot = 0; slot < rack.getSlots(); slot++)
                        {
                            if (rack.getStackInSlot(slot).is(Items.STONE_AXE))
                            {
                                rack.extractItem(slot, 1, false);
                            }
                        }
                        helper.assertTrue(f.physical(s -> s.is(Items.STONE_AXE)) == 0, "fixture: the axe is still in the warehouse");
                        taken[0] = true;
                    }
                    return false;
                }
                helper.assertTrue(help != AbstractEntityAIBasic.ToolSelfHelp.NOTHING || retry[0], "a failed fetch says there is nothing to make a tool from: " + describe(f, worker));
                if (!retry[0])
                {
                    if (help == AbstractEntityAIBasic.ToolSelfHelp.RETRY)
                    {
                        retry[0] = true;
                        f.stock(0, new ItemStack(Items.STONE_AXE));
                    }
                    return false;
                }
                return carried(worker, Items.STONE_AXE) == 1;
            }, () -> {
                helper.assertTrue(com.minecolonies.api.colony.interactionhandling.InteractionValidatorRegistry.getStandardInteractionValidatorPredicate(
                  net.minecraft.network.chat.Component.translatableEscape(AbstractEntityAIBasic.TOOL_LATER_NOTICE)) != null, "no validator for the could-not-get-it notice");
                end(helper);
                helper.succeed();
            }, () -> describe(f, worker) + ", stone axes in the warehouse " + f.physical(s -> s.is(Items.STONE_AXE)) + ", taken " + taken[0] + ", retry " + retry[0]);
        });
    }

    /**
     * r3 Fable N16: not only builders help. A sawmill worker with no task carries seeds and a stone hoe of his own: he still helps another hut that asked for a
     * wooden pickaxe (his claim says he is a helper), and his seeds stay with him through it. Oak logs in his pockets keep him home until they are gone. A task
     * for his own hut ends the help at once.
     */
    public static void crafterHelps(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "agency crafter helps");
        final Colony colony = (Colony) f.colony;
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        LabourGameTests.tree(helper, 44, 20, 6);
        final IBuilding asker = f.place(ModBlocks.blockHutBuilder, new BlockPos(36, 1, 24), "fundamentals/builder1.blueprint");
        final IBuilding sawmill = f.place(ModBlocks.blockHutSawmill, new BlockPos(8, 1, 24), "craftsmanship/carpentry/sawmill1.blueprint");
        helper.runAfterDelay(100, () -> {
            final ICitizenData crafter = f.spawn(new BlockPos(12, 1, 18), false);
            helper.assertTrue(sawmill.getModule(BuildingModules.SAWMILL_WORK).assignCitizen(crafter), "sawmill worker not assigned");
            for (int slot = 0; slot < crafter.getInventory().getSlots(); slot++)
            {
                crafter.getInventory().setStackInSlot(slot, ItemStack.EMPTY);
            }
            crafter.getInventory().setStackInSlot(0, new ItemStack(Items.WHEAT_SEEDS, 16));
            crafter.getInventory().setStackInSlot(1, new ItemStack(Items.STONE_HOE));
            crafter.getInventory().setStackInSlot(2, new ItemStack(Items.OAK_LOG, 4));
            helper.assertTrue(!WorkerHelp.carriesNoLabourGoods(crafter), "logs in his pockets do not keep him home");
            final IToken<?> token = asker.createRequest(new Stack(new ItemStack(Items.WOODEN_PICKAXE), 1, 1), false);
            final int[] ticks = {0};
            final boolean[] logsGone = {false};
            poll(helper, colony, 9000, () -> {
                ticks[0] += 20;
                final LabourClaim claim = colony.getLabourBoard().claimOf(crafter.getId());
                if (!logsGone[0])
                {
                    helper.assertTrue(carried(crafter, Items.OAK_LOG) == 0 || (claim == null && !WorkerHelp.isHelping(crafter)),
                      "he helps with logs of his own in his pockets: " + describe(f, crafter));
                    if (ticks[0] >= 400 || carried(crafter, Items.OAK_LOG) == 0)
                    {
                        crafter.getInventory().setStackInSlot(2, ItemStack.EMPTY);
                        helper.assertTrue(WorkerHelp.carriesNoLabourGoods(crafter), "seeds and a hoe keep him home");
                        logsGone[0] = true;
                    }
                    return false;
                }
                if (claim == null || !WorkerHelp.isHelping(crafter))
                {
                    return false;
                }
                helper.assertTrue(claim.byWorker() && claim.destination().equals(asker.getID()) && token.equals(claim.request()),
                  "the crafter's claim is not the asking hut's request: " + claim.destination() + " " + claim.request());
                helper.assertTrue(seedsHis(f, crafter, sawmill, asker), "his seeds went into the help: " + describe(f, crafter));
                // a task for his own hut: the help ends at once (a token in his queue; taken out again before his crafting AI runs on it)
                final var job = (com.minecolonies.core.colony.jobs.AbstractJobCrafter<?, ?>) crafter.getJob();
                job.addRequest(token);
                IdleCitizenArbiter.tick(colony);
                final boolean ended = IdleCitizenArbiter.sessionOf(crafter) == null && colony.getLabourBoard().claimOf(crafter.getId()) == null;
                job.onTaskDeletion(token);
                helper.assertTrue(ended, "a task for his own hut did not end the help: " + describe(f, crafter));
                helper.assertTrue(seedsHis(f, crafter, sawmill, asker), "his seeds were handed in when the help ended: " + describe(f, crafter));
                return true;
            }, () -> {
                Log.getLogger().info("[agency_crafter_helps] helped and stopped: {}", describe(f, crafter));
                end(helper);
                helper.succeed();
            }, () -> describe(f, crafter) + ", request " + f.describe(token) + ", logs left " + logsLeft(helper, 44, 20, 6));
        });
    }
}
