package com.minecolonies.core.gametest;

import java.util.Set;
import java.util.HashSet;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationKind;
import com.minecolonies.core.colony.requestsystem.RsFlags;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.blocks.ModBlocks;
import com.ldtteam.structurize.api.util.Tuple;
import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.IColonyRulesProvider;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.IAssignsJob;
import com.minecolonies.api.colony.buildings.modules.IBuildingModule;
import com.minecolonies.api.colony.buildings.modules.IMinimumStockModule;
import com.minecolonies.api.colony.claim.IChunkClaimData;
import com.minecolonies.api.colony.jobs.registry.JobEntry;
import com.minecolonies.api.colony.requestsystem.StandardFactoryController;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.requestsystem.token.StandardToken;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.labour.LabourBoard;
import com.minecolonies.core.colony.labour.LabourDataListener;
import com.minecolonies.core.colony.labour.LabourRules;
import com.minecolonies.core.colony.requestsystem.management.RequestTargets;
import com.minecolonies.core.colony.requestsystem.reservation.Reservation;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationReason;
import com.minecolonies.core.colony.storage.DispatchCost;
import com.minecolonies.core.colony.storage.DispatchPaths;
import com.minecolonies.core.network.messages.server.colony.building.HireFireMessage;
import com.minecolonies.core.util.ChunkDataHelper;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.minecolonies.core.gametest.MinecoloniesGameTests.makeConnectedSurvivalPlayer;

/**
 * Cohesion wave (2026-10-25), the fork side of Colony Command's findings (glob {@code minecolonies:cohesion_*}, group
 * {@code MINECOLONIES_GAMETEST_ONLY=cohesion}): the emerald market is gone, the add-on rules ({@link IColonyRulesProvider}) apply and change nothing
 * when unset, the ledger's MAKE and BUILD reasons, labour stock goals from minimum stock, road-aware dispatch and the resolver dry run.
 * <p>
 * Each test sets the rules provider and puts the previous one back (an add-on's, when one is loaded) inside its one synchronous body, and its provider only
 * answers for its own colony, so tests of other groups in the same batch are not affected.
 */
public final class CohesionGameTests
{
    private CohesionGameTests()
    {
    }

    static final Map<String, Consumer<GameTestHelper>> TESTS = new LinkedHashMap<>();
    static
    {
        TESTS.put("cohesion_economy_unregistered", CohesionGameTests::economyUnregistered);
        TESTS.put("cohesion_raid_level_multiplier", CohesionGameTests::raidLevelMultiplier);
        TESTS.put("cohesion_claim_radius_limit", CohesionGameTests::claimRadiusLimit);
        TESTS.put("cohesion_ledger_make_build", CohesionGameTests::ledgerMakeBuild);
        TESTS.put("cohesion_labour_goals_from_min_stock", CohesionGameTests::labourGoalsFromMinStock);
        TESTS.put("cohesion_dispatch_roads", CohesionGameTests::dispatchRoads);
        TESTS.put("cohesion_request_targets", CohesionGameTests::requestTargets);
        TESTS.put("cohesion_hire_refusal", CohesionGameTests::hireRefusal);
        TESTS.put("cohesion_make_claim_handover", h -> makeClaimHandover(h, null));
        TESTS.put("cohesion_make_claim_handover_network", h -> makeClaimHandover(h, true));
        TESTS.put("cohesion_make_claim_handover_classic", h -> makeClaimHandover(h, false));
        TESTS.put("cohesion_claim_recompute_short", CohesionGameTests::claimRecomputeShort);
        TESTS.put("cohesion_claim_recompute_tokens", CohesionGameTests::claimRecomputeTokens);
        TESTS.put("cohesion_dispatch_hint_budget", CohesionGameTests::dispatchHintBudget);
        TESTS.put("cohesion_claim_for_use_arrival", CohesionGameTests::claimForUseArrival);
        TESTS.put("cohesion_claim_for_use_two_tokens", CohesionGameTests::claimForUseTwoTokens);
        TESTS.put("cohesion_claim_for_use_short", CohesionGameTests::claimForUseShort);
        TESTS.put("cohesion_claim_for_use_chain_hold", CohesionGameTests::claimForUseChainHold);
    }

    static void register(final RegisterGameTestsEvent event)
    {
        for (final Map.Entry<String, Consumer<GameTestHelper>> entry : TESTS.entrySet())
        {
            final Consumer<GameTestHelper> test = entry.getValue();
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, entry.getKey()),
              info -> new MinecoloniesGameTestInstance(info, test),
              MinecoloniesGameTestRegistrar.isolatedColonyData(event, entry.getKey()));
        }
    }

    /**
     * Runs the body with the rules provider set (null: the default) and puts the previous provider back.
     */
    private static void withRules(final IColonyRulesProvider provider, final Runnable body)
    {
        final IColonyRulesProvider previous = IMinecoloniesAPI.getInstance().getColonyRulesProvider();
        IMinecoloniesAPI.getInstance().setColonyRulesProvider(provider);
        try
        {
            body.run();
        }
        finally
        {
            IMinecoloniesAPI.getInstance().setColonyRulesProvider(previous);
        }
    }

    private static IToken<?> ask(final IBuilding requester, final Item item, final int count)
    {
        return requester.createRequest(new Stack(new ItemStack(item, count), count, count), false);
    }

    // ------------------------------------------------------------------ A-1

    /** The emerald market's command is not registered: no {@code colony economy} node, the other colony commands are there. */
    public static void economyUnregistered(final GameTestHelper helper)
    {
        final var root = helper.getLevel().getServer().getCommands().getDispatcher().getRoot();
        for (final String alias : new String[] {Constants.MOD_ID, "mc"})
        {
            final CommandNode<CommandSourceStack> mod = root.getChild(alias);
            helper.assertTrue(mod != null, "no /" + alias + " command");
            final CommandNode<CommandSourceStack> colony = mod.getChild("colony");
            helper.assertTrue(colony != null && !colony.getChildren().isEmpty(), "no /" + alias + " colony subtree");
            helper.assertTrue(colony.getChild("economy") == null, "/" + alias + " colony economy is still registered");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ A-5

    /** The raid level is multiplied for the provider's colony (capped at 10); 0 gives 0, an unusable answer and no provider leave it unchanged. */
    public static void raidLevelMultiplier(final GameTestHelper helper)
    {
        final RsFixture f = RsFixture.found(helper, "cohesion raid", 0, false);
        f.spawn(new BlockPos(4, 1, 6), true);
        f.spawn(new BlockPos(6, 1, 6), true);
        final IColony colony = f.colony;
        final int[] base = {0};
        withRules(null, () -> base[0] = colony.getRaiderManager().getColonyRaidLevel());
        helper.assertTrue(base[0] > 0, "the fixture has no raid level: " + base[0]);
        final double[] answer = {2.0D};
        withRules(new IColonyRulesProvider()
        {
            @Override
            public double raidLevelMultiplier(@NotNull final IColony c)
            {
                return c == colony ? answer[0] : 1.0D;
            }
        }, () -> {
            final int doubled = colony.getRaiderManager().getColonyRaidLevel();
            helper.assertTrue(doubled >= 2 * base[0] && doubled <= 2 * base[0] + 1, "x2: " + base[0] + " -> " + doubled);
            answer[0] = 0.0D;
            helper.assertTrue(colony.getRaiderManager().getColonyRaidLevel() == 0, "x0 gives 0");
            answer[0] = 50.0D;
            final int capped = colony.getRaiderManager().getColonyRaidLevel();
            helper.assertTrue(capped >= 10 * base[0] && capped <= 10 * base[0] + 1, "x50 is capped at x10: " + capped);
            for (final double broken : new double[] {Double.NaN, -1.0D, Double.POSITIVE_INFINITY})
            {
                answer[0] = broken;
                helper.assertTrue(colony.getRaiderManager().getColonyRaidLevel() == base[0], "an unusable answer " + broken + " is ignored");
            }
        });
        withRules(null, () -> {
            helper.assertTrue(IMinecoloniesAPI.getInstance().getColonyRulesProvider() == IColonyRulesProvider.DEFAULT, "null puts the default in");
            helper.assertTrue(colony.getRaiderManager().getColonyRaidLevel() == base[0], "no provider: unchanged");
        });
        helper.succeed();
    }

    // ------------------------------------------------------------------ A-6

    private static boolean buildingClaims(final ServerLevel level, final IColony colony, final ChunkPos chunk)
    {
        final IChunkClaimData data = IColonyManager.getInstance().getClaimData(level.dimension(), chunk);
        return data != null && data.getAllClaimingBuildings().containsKey(colony.getID());
    }

    /**
     * A building's radius claim stops at the provider's radius (chunks from the centre chunk, square); its footprint box does not; without a limit it reaches
     * its full radius; a direct {@code tryClaimBuilding} (an add-on's ring purchase) and unclaiming are not limited.
     */
    public static void claimRadiusLimit(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "cohesion claims");
        final BlockPos hall = colony.getCenter();
        final ChunkPos center = ChunkPos.containing(hall);
        final ChunkPos two = new ChunkPos(center.x() + 2, center.z());
        final ChunkPos three = new ChunkPos(center.x() + 3, center.z() - 3);
        final ChunkPos threeBox = new ChunkPos(center.x() - 3, center.z());
        final ChunkPos threeDirect = new ChunkPos(center.x() - 3, center.z() + 3);
        final ChunkPos four = new ChunkPos(center.x(), center.z() + 4);
        final IColonyRulesProvider limit = new IColonyRulesProvider()
        {
            @Override
            public int claimRadiusLimit(@NotNull final IColony c)
            {
                return c == colony ? 2 : -1;
            }
        };
        withRules(limit, () -> {
            ChunkDataHelper.claimBuildingChunks((Colony) colony, true, hall, 4, null);
            helper.assertTrue(buildingClaims(level, colony, two), "inside the limit the building claims");
            helper.assertTrue(!buildingClaims(level, colony, three) && !buildingClaims(level, colony, four), "past the limit the radius claims nothing");
            helper.assertTrue(ChunkDataHelper.beyondClaimLimit(colony, center, three) && !ChunkDataHelper.beyondClaimLimit(colony, center, two), "limit check is square");

            // a footprint that straddles the limit is claimed whole
            final BlockPos corner = new BlockPos(threeBox.getMinBlockX(), hall.getY(), threeBox.getMinBlockZ());
            ChunkDataHelper.claimBuildingChunks((Colony) colony, true, hall, 0, new Tuple<>(corner, corner.offset(15, 4, 15)));
            helper.assertTrue(buildingClaims(level, colony, threeBox), "the footprint box is not limited");

            // the ring purchase path claims directly, past the building limit
            helper.assertTrue(ChunkDataHelper.tryClaimBuilding(level, threeDirect, true, (Colony) colony, hall) && buildingClaims(level, colony, threeDirect),
              "a direct claim is not limited");
        });

        // no limit: the full radius, as before
        withRules(null, () -> {
            ChunkDataHelper.claimBuildingChunks((Colony) colony, true, hall, 4, null);
            helper.assertTrue(buildingClaims(level, colony, four), "without a limit the building claims its whole radius");
        });

        // unclaiming is never limited
        withRules(limit, () -> {
            ChunkDataHelper.claimBuildingChunks((Colony) colony, false, hall, 4, null);
            helper.assertTrue(!buildingClaims(level, colony, four) && !buildingClaims(level, colony, two), "unclaiming reaches past the limit");
        });
        helper.succeed();
    }

    // ------------------------------------------------------------------ B-2

    /** MAKE and BUILD claims: setStock grows, shrinks and ends; per item and per reason reads; the request sweep leaves them; sweepStale and saves. */
    public static void ledgerMakeBuild(final GameTestHelper helper)
    {
        helper.assertTrue(ReservationReason.OTHER.ordinal() == 5 && ReservationReason.MAKE.ordinal() == 6 && ReservationReason.BUILD.ordinal() == 7,
          "the older reasons keep their ordinals");
        helper.assertTrue(ReservationReason.MAKE.withoutRequest() && ReservationReason.BUILD.withoutRequest() && !ReservationReason.WAREHOUSE_DELIVERY.withoutRequest(),
          "MAKE and BUILD have no request behind them");

        final BlockPos warehouseA = new BlockPos(10, 64, 10);
        final BlockPos warehouseB = new BlockPos(40, 64, 10);
        final BlockPos rack = new BlockPos(11, 64, 10);
        final ItemStorage logs = new ItemStorage(new ItemStack(Items.OAK_LOG));
        final ItemStorage planks = new ItemStorage(new ItemStack(Items.OAK_PLANKS));
        final IToken<?> make = new StandardToken();
        final IToken<?> build = new StandardToken();
        final IToken<?> courier = new StandardToken();
        final ReservationLedger ledger = new ReservationLedger();
        final List<Reservation> freed = new ArrayList<>();
        ledger.setOnFreed(freed::add);

        helper.assertTrue(ledger.setStock(warehouseA, logs, 10, make, "make 4 planks", ReservationReason.MAKE, 1L) == 10, "setStock grows from nothing");
        helper.assertTrue(ledger.reservedStock(warehouseA, logs) == 10 && ledger.available(warehouseA, logs, 12) == 2, "a MAKE claim lowers available");
        helper.assertTrue(ledger.setStock(warehouseA, logs, 4, make, "make 4 planks", ReservationReason.MAKE, 2L) == 4 && ledger.reservedStock(warehouseA, logs) == 4,
          "setStock shrinks");
        helper.assertTrue(freed.size() == 1, "the shrunk part is reported as freed");
        helper.assertTrue(ledger.setStock(warehouseA, logs, 4, make, "make 4 planks", ReservationReason.MAKE, 3L) == 4 && freed.size() == 1, "the same amount changes nothing");
        ledger.reserveStock(warehouseA, rack, logs, 3, make, "make", ReservationReason.MAKE, 3L);
        helper.assertTrue(ledger.setStock(warehouseA, logs, 0, make, "make", ReservationReason.MAKE, 4L) == 0, "0 ends the claim");
        helper.assertTrue(ledger.reservationsOf(make).size() == 1 && ledger.reservationsOf(make).get(0).container() != null, "a rack claim of the token is left alone");
        ledger.release(make, 5L);

        ledger.setStock(warehouseA, logs, 6, make, "make", ReservationReason.MAKE, 10L);
        ledger.setStock(warehouseB, logs, 5, build, "build", ReservationReason.BUILD, 10L);
        ledger.setStock(warehouseB, planks, 7, build, "build", ReservationReason.BUILD, 10L);
        ledger.reserveStock(warehouseA, null, logs, 2, courier, "courier", ReservationReason.WAREHOUSE_DELIVERY, 10L);
        helper.assertTrue(ledger.byReason(ReservationReason.BUILD).size() == 2 && ledger.byReason(ReservationReason.MAKE).size() == 1, "byReason");
        helper.assertTrue(ledger.reservedStock(logs, null) == 13 && ledger.reservedStock(logs, ReservationReason.BUILD) == 5, "reservedStock per item over every source");
        final Map<ItemStorage, Integer> byItem = ledger.reservedStockByItem(null);
        helper.assertTrue(byItem.getOrDefault(logs, 0) == 13 && byItem.getOrDefault(planks, 0) == 7, "reservedStockByItem: " + byItem);
        helper.assertTrue(ledger.reservedStockByItem(ReservationReason.MAKE).getOrDefault(logs, 0) == 6, "reservedStockByItem per reason");

        // saved and read back with their reasons
        final ServerLevel level = helper.getLevel();
        final CompoundTag tag = ledger.write(level.registryAccess(), StandardFactoryController.getInstance());
        final ReservationLedger read = new ReservationLedger();
        read.read(level.registryAccess(), StandardFactoryController.getInstance(), tag);
        helper.assertTrue(read.byReason(ReservationReason.BUILD).size() == 2 && read.reservedStock(logs, ReservationReason.MAKE) == 6, "MAKE and BUILD survive a save");

        // the request sweep drops the courier claim (its request is gone) but not the add-on's claims; sweepStale drops those of unknown tokens
        helper.assertTrue(ledger.sweep(t -> false, 20L) == 1, "the request sweep drops only the delivery claim");
        helper.assertTrue(ledger.reservedStock(logs, null) == 11, "MAKE and BUILD survive the request sweep");
        helper.assertTrue(ledger.sweepStale(ReservationReason.MAKE, t -> t.equals(make), 30L, 0L) == 0, "a live MAKE token is kept");
        helper.assertTrue(ledger.sweepStale(ReservationReason.BUILD, t -> false, 15L, 10L) == 0, "a young BUILD claim is kept");
        helper.assertTrue(ledger.sweepStale(ReservationReason.BUILD, t -> false, 30L, 10L) == 1 && ledger.byReason(ReservationReason.BUILD).isEmpty(), "stale BUILD claims go");
        helper.assertTrue(ledger.byReason(ReservationReason.MAKE).size() == 1, "the MAKE claim is still there");

        // one claim per reason (L7) and per beneficiary; a beneficiary's own requests do not count its claim; handOver shrinks it quietly
        final BlockPos hut = new BlockPos(70, 64, 10);
        final IToken<?> both = new StandardToken();
        final ReservationLedger second = new ReservationLedger();
        final List<Reservation> freedToo = new ArrayList<>();
        second.setOnFreed(freedToo::add);
        second.setStock(warehouseA, logs, 3, both, "x", ReservationReason.MAKE, 1L);
        second.setStock(warehouseA, logs, 2, both, "x", ReservationReason.BUILD, 1L);
        helper.assertTrue(second.reservationsOf(both).size() == 2 && second.reservedStock(logs, ReservationReason.MAKE) == 3, "two reasons, two claims");
        final IToken<?> forHut = new StandardToken();
        helper.assertTrue(second.setStock(warehouseA, logs, 8, forHut, "make", ReservationReason.MAKE, 2L, hut) == 8, "a claim for a building");
        helper.assertTrue(second.reservedStockWhere(warehouseA, stack -> stack.is(Items.OAK_LOG), ReservationLedger.countsAgainst(Set.of(hut))) == 5,
          "the beneficiary's requests do not count its own claim");
        helper.assertTrue(second.reservedStockWhere(warehouseA, stack -> stack.is(Items.OAK_LOG), ReservationLedger.countsAgainst(Set.of(warehouseB))) == 13,
          "anyone else's do");
        helper.assertTrue(second.heldForOthers(warehouseA, logs, ReservationLedger.countsAgainst(Set.of(hut))) == 5, "heldForOthers skips the beneficiary");
        final CompoundTag forTag = second.write(level.registryAccess(), StandardFactoryController.getInstance());
        final ReservationLedger readFor = new ReservationLedger();
        readFor.read(level.registryAccess(), StandardFactoryController.getInstance(), forTag);
        helper.assertTrue(readFor.reservationsOf(forHut).size() == 1 && hut.equals(readFor.reservationsOf(forHut).get(0).forBuilding()), "the beneficiary survives a save");
        helper.assertTrue(second.handOver(warehouseA, logs, 6, Set.of(hut), 3L) == 6 && second.reservationsOf(forHut).get(0).amount() == 2 && freedToo.isEmpty(),
          "handOver shrinks the beneficiary's claim without freeing");
        helper.assertTrue(second.handOver(warehouseA, logs, 6, Set.of(warehouseB), 3L) == 0, "nothing is handed over to another building");
        helper.assertTrue(second.handOver(warehouseA, logs, 6, Set.of(hut), 4L) == 2 && second.reservationsOf(forHut).isEmpty(), "handing over the rest ends the claim");

        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "cohesion ledger");
        helper.assertTrue(ReservationLedger.of(colony) != null, "a server colony has a ledger");
        RsFlags.overrideReservations(false);
        helper.assertTrue(ReservationLedger.of(colony) == null, "with reservations off there is no ledger to count (L6)");
        RsFlags.overrideReservations(null);
        helper.succeed();
    }

    // ------------------------------------------------------------------ B-3

    /**
     * A warehouse minimum-stock entry for a basic good becomes a labour stock goal (quantity in stacks, start below it) and replaces the seed goal for that
     * exact item; an entry for a good labourers cannot make is ignored; without entries the goals are the seed.
     */
    public static void labourGoalsFromMinStock(final GameTestHelper helper)
    {
        final RsFixture f = RsFixture.found(helper, "cohesion goals", 1, false);
        final LabourBoard board = ((Colony) f.colony).getLabourBoard();
        final List<Item> basic = LabourRules.candidateItems();
        helper.assertTrue(!basic.isEmpty(), "no basic goods");
        final Item good = basic.contains(Items.STICK) ? Items.STICK : basic.get(0);
        final Item other = !basic.contains(Items.DIAMOND) ? Items.DIAMOND : Items.NETHER_STAR;
        final String goodId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(good).toString();
        final IMinimumStockModule module = f.warehouse.getModule(IMinimumStockModule.class);
        helper.assertTrue(module != null, "the warehouse has no minimum-stock module");

        final List<String> seed = LabourDataListener.stockpile().stream().map(LabourDataListener.StockpileGoal::id).toList();
        helper.assertTrue(board.stockGoals().stream().map(LabourDataListener.StockpileGoal::id).toList().equals(seed), "without entries the goals are the seed");

        module.addMinimumStock(new ItemStack(good), 2);
        module.addMinimumStock(new ItemStack(other), 1);
        helper.assertTrue(module.getMinimumStock().size() == 2, "the warehouse took both entries: " + module.getMinimumStock());
        final List<LabourDataListener.StockpileGoal> goals = board.stockGoals();
        final LabourDataListener.StockpileGoal mine = goals.stream().filter(g -> g.id().equals(LabourBoard.MIN_STOCK_GOAL + goodId)).findFirst().orElse(null);
        final int target = 2 * new ItemStack(good).getMaxStackSize();
        helper.assertTrue(mine != null && mine.target() == target && mine.startBelow() == target - Math.max(1, target / 4) && mine.item().equals(goodId),
          "the entry is a goal, starting below three quarters: " + goals);
        helper.assertTrue(goals.stream().noneMatch(g -> !g.id().startsWith(LabourBoard.MIN_STOCK_GOAL) && g.item().equals(goodId)), "the seed goal for the same item is replaced");
        helper.assertTrue(goals.stream().noneMatch(g -> g.item().contains(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(other).getPath())),
          "a good labourers cannot make is no goal");
        helper.assertTrue(goals.size() >= seed.size() - 1, "the other seed goals stay: " + goals);

        final List<LabourBoard.StockGoalView> views = board.stockGoalViews();
        helper.assertTrue(views.size() == goals.size(), "one view per goal");
        final LabourBoard.StockGoalView view = views.stream().filter(v -> v.goal().id().equals(mine.id())).findFirst().orElse(null);
        helper.assertTrue(view != null && view.warehouse() && view.stock() == 0 && view.inFlight() == 0, "the view: " + view);

        // hysteresis and the least lot (M2): 2 stacks of sticks, target 128, start below 96
        final LabourDataListener.StockpileGoal sticks = new LabourDataListener.StockpileGoal(LabourBoard.MIN_STOCK_GOAL + "minecraft:stick", "minecraft:stick", 96, 128);
        helper.assertTrue(!LabourBoard.goalActive(sticks, 127, false) && !LabourBoard.goalActive(sticks, 100, false), "127 or 100 sticks start nothing");
        helper.assertTrue(LabourBoard.goalActive(sticks, 95, false) && LabourBoard.goalLot(sticks, 95, 0) == 33, "95 sticks: a lot of 33");
        helper.assertTrue(LabourBoard.goalActive(sticks, 127, true) && !LabourBoard.goalActive(sticks, 128, true), "active until 128");
        helper.assertTrue(!LabourBoard.goalActive(sticks, 96, false) && LabourBoard.goalActive(sticks, 95, false), "then inactive until under 96");
        helper.assertTrue(LabourBoard.goalLot(sticks, 95, 20) == 0 && LabourBoard.goalLot(sticks, 112, 0) == 16, "no lot under 16 items");
        final LabourDataListener.StockpileGoal seedSticks = new LabourDataListener.StockpileGoal("sticks", "minecraft:stick", 8, 16);
        helper.assertTrue(LabourBoard.goalLot(seedSticks, 15, 0) == 1, "seed goals keep their lots");
        module.removeMinimumStock(new ItemStack(good));
        helper.assertTrue(board.stockGoals().stream().noneMatch(g -> g.id().equals(mine.id())), "removing the entry drops the goal (the cache follows the module)");
        helper.succeed();
    }

    // ------------------------------------------------------------------ B-4

    /**
     * Off road the estimate is the straight line (exactly {@link DispatchCost#PATH_FACTOR}); a road along the way makes it cheaper; a road change outside a
     * pair's corridor leaves it alone, one on its line marks it stale; over the budget a stale pair answers with its last factor.
     */
    public static void dispatchRoads(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        MinecoloniesGameTests.foundGameTestColony(helper, "cohesion roads");
        final BlockPos from = helper.absolutePos(new BlockPos(0, 1, 6));
        final BlockPos to = helper.absolutePos(new BlockPos(36, 1, 6));
        for (int x = from.getX() >> 4; x <= to.getX() >> 4; x++)
        {
            for (int z = (from.getZ() - 2) >> 4; z <= (from.getZ() + 20) >> 4; z++)
            {
                level.setChunkForced(x, z, true);
            }
        }
        DispatchPaths.invalidate(level);
        DispatchPaths.resetTickBudget();
        final double offRoad = DispatchPaths.factor(level, null, from, to);
        helper.assertTrue(offRoad == DispatchCost.PATH_FACTOR, "off road: " + offRoad);
        helper.assertTrue(DispatchPaths.travelTicks(null, from, to) == DispatchCost.travelTicks(from, to), "no colony: the straight line");

        // a road on the floor along the whole way; placed without an event, so the cached answer stays
        for (int x = from.getX(); x <= to.getX(); x++)
        {
            level.setBlock(new BlockPos(x, from.getY() - 1, from.getZ()), Blocks.POLISHED_ANDESITE.defaultBlockState(), 3);
        }
        helper.assertTrue(DispatchPaths.factor(level, null, from, to) == DispatchCost.PATH_FACTOR && DispatchPaths.cachedPairs(level) >= 1, "cached");
        final int stale = DispatchPaths.stalePairs(level);

        // a road block changed outside the corridor (planks are path blocks): the pair is untouched
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
        DispatchPaths.onBreak(new BreakBlockEvent(level, from.offset(0, -1, 12), Blocks.OAK_PLANKS.defaultBlockState(), player));
        helper.assertTrue(DispatchPaths.stalePairs(level) == stale && DispatchPaths.factor(level, null, from, to) == DispatchCost.PATH_FACTOR, "outside the corridor: still cached");

        // one on its line: marked stale, kept, recomputed when asked
        final int cached = DispatchPaths.cachedPairs(level);
        DispatchPaths.onBreak(new BreakBlockEvent(level, from.offset(10, -1, 0), Blocks.POLISHED_ANDESITE.defaultBlockState(), player));
        helper.assertTrue(DispatchPaths.stalePairs(level) == stale + 1 && DispatchPaths.cachedPairs(level) == cached, "on the line: stale, not dropped");
        DispatchPaths.resetTickBudget();
        final double onRoad = DispatchPaths.factor(level, null, from, to);
        helper.assertTrue(Math.abs(onRoad - DispatchCost.PATH_FACTOR / 1.3D) < 0.01D, "on a road the walk counts 1.3x faster: " + onRoad);

        // half of it road
        for (int x = from.getX() + 18; x <= to.getX(); x++)
        {
            level.setBlock(new BlockPos(x, from.getY() - 1, from.getZ()), Blocks.STONE.defaultBlockState(), 3);
        }
        DispatchPaths.invalidate(level);
        DispatchPaths.resetTickBudget();
        final double half = DispatchPaths.factor(level, null, from, to);
        helper.assertTrue(half > onRoad && half < offRoad, "half on road is in between: " + half);

        // over the budget a stale pair answers with its last factor, not the off-road one
        for (int x = from.getX(); x <= to.getX(); x++)
        {
            level.setBlock(new BlockPos(x, from.getY() - 1, from.getZ()), Blocks.STONE.defaultBlockState(), 3);
        }
        DispatchPaths.invalidate(level);
        DispatchPaths.resetTickBudget();
        for (int k = 0; k < DispatchPaths.FRESH_PER_TICK; k++)
        {
            DispatchPaths.factor(level, null, from.offset(0, 0, 4 + k), to.offset(0, 0, 4 + k));
        }
        helper.assertTrue(DispatchPaths.factor(level, null, from, to) == half, "budget spent: the stale factor");
        DispatchPaths.resetTickBudget();
        helper.assertTrue(DispatchPaths.factor(level, null, from, to) == DispatchCost.PATH_FACTOR, "with budget: recomputed, the road is gone");
        helper.succeed();
    }

    // ------------------------------------------------------------------ B-6

    private static Map<IToken<?>, Set<IToken<?>>> assignments(final RsFixture f)
    {
        final Map<IToken<?>, Set<IToken<?>>> copy = new LinkedHashMap<>();
        f.standard().getRequestResolverRequestAssignmentDataStore().getAssignments().forEach((k, v) -> copy.put(k, new HashSet<>(v)));
        return copy;
    }

    private static String said(final List<RequestTargets.Candidate> found)
    {
        final StringBuilder out = new StringBuilder();
        for (final RequestTargets.Candidate c : found)
        {
            out.append(c.resolver().getClass().getSimpleName()).append('@').append(c.building()).append(" p").append(c.priority()).append(" m").append(c.metric()).append("; ");
        }
        return out.toString();
    }

    /**
     * The dry run changes nothing, and its first candidate is the resolver the request system then really assigns: a stocked warehouse (logs), a crafter
     * when the warehouse has none (planks from a staffed sawmill), the retrying or player resolver when nothing can make it (diamonds).
     */
    public static void requestTargets(final GameTestHelper helper)
    {
        final RsFixture f = RsFixture.found(helper, "cohesion targets", 2, false);
        f.stock(0, new ItemStack(Items.OAK_LOG, 16));
        LogisticsGameTests.plankSawmill(f, true, false);
        helper.assertTrue(RequestTargets.forItem(f.colony, f.builder, ItemStack.EMPTY, 1).isEmpty(), "an empty stack has no target");

        for (final Item item : new Item[] {Items.OAK_LOG, Items.OAK_PLANKS, Items.DIAMOND})
        {
            final long revision = f.ledger().revision();
            final int requests = f.standard().getRequestIdentitiesDataStore().getIdentities().size();
            final Map<IToken<?>, Set<IToken<?>>> before = assignments(f);
            final List<RequestTargets.Candidate> found = RequestTargets.forItem(f.colony, f.builder, new ItemStack(item), 4);
            helper.assertTrue(!found.isEmpty(), "nobody would take a request for " + item);
            helper.assertTrue(f.ledger().revision() == revision, "the dry run reserved nothing");
            helper.assertTrue(f.standard().getRequestIdentitiesDataStore().getIdentities().size() == requests, "the dry run registered no request");
            helper.assertTrue(assignments(f).equals(before), "the dry run assigned nothing");

            final IToken<?> real = ask(f.builder, item, 4);
            final var assigned = f.manager().getResolverForRequest(real);
            helper.assertTrue(assigned != null && assigned.getId().equals(found.get(0).resolver().getId()),
              item + ": the request system chose " + (assigned == null ? "nobody" : assigned.getClass().getSimpleName()) + ", the dry run said " + said(found));
            if (item == Items.OAK_LOG)
            {
                helper.assertTrue(f.warehouse.getID().equals(found.get(0).building()) || found.get(0).resolver().getClass().getSimpleName().contains("Warehouse"),
                  "logs come from the warehouse: " + said(found));
            }
            if (item == Items.DIAMOND)
            {
                helper.assertTrue(found.get(0).resolver().getId().equals(f.manager().getRetryingRequestResolver().getId())
                                    || found.get(0).resolver().getId().equals(f.manager().getPlayerResolver().getId()), "diamonds: retry or player first: " + said(found));
            }
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ C-4, C-6

    /**
     * The provider refuses a hire through MineColonies' hire message: the citizen stays unassigned and the module unchanged; without a provider the same message
     * hires; the client defaults change nothing.
     */
    public static void hireRefusal(final GameTestHelper helper)
    {
        final RsFixture f = RsFixture.found(helper, "cohesion hire", 0, false);
        final ICitizenData citizen = f.spawn(new BlockPos(4, 1, 6), true);
        IAssignsJob found = null;
        for (final IBuildingModule m : f.builder.getModules())
        {
            if (m instanceof final IAssignsJob assigns)
            {
                found = assigns;
                break;
            }
        }
        helper.assertTrue(found != null, "the builder has no job module");
        final IAssignsJob module = found;
        final int moduleId = ((IBuildingModule) module).getProducer().getRuntimeID();
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
        final IColony colony = f.colony;
        withRules(new IColonyRulesProvider()
        {
            @Override
            public Component hireRefusal(@NotNull final ICitizenData c, @NotNull final IBuilding building, @NotNull final JobEntry job)
            {
                return building.getColony() == colony ? Component.literal("needs a tier 2 citizen") : null;
            }
        }, () -> {
            HireFireMessage.execute(player, colony, f.builder, true, citizen.getId(), moduleId);
            helper.assertTrue(citizen.getWorkBuilding() == null && module.getAssignedCitizen().isEmpty(), "the refused citizen is not hired: " + module.getAssignedCitizen());
        });
        withRules(null, () -> {
            HireFireMessage.execute(player, colony, f.builder, true, citizen.getId(), moduleId);
            helper.assertTrue(citizen.getWorkBuilding() == f.builder && module.getAssignedCitizen().contains(citizen), "without a provider the message hires");
        });
        final IColonyRulesProvider rules = IColonyRulesProvider.DEFAULT;
        helper.assertTrue(rules.claimRadiusLimit(colony) < 0 && rules.raidLevelMultiplier(colony) == 1.0D && rules.hireRefusal(citizen, f.builder, module.getJobEntry()) == null,
          "the default changes nothing");
        helper.assertTrue(!rules.openQuestLog(player, null, ItemStack.EMPTY), "the default quest log opens MineColonies' window");
        helper.succeed();
    }

    // ------------------------------------------------------------------ H1

    /**
     * A MAKE claim held for a building: another building's request is not served from it; the building's own request is, the claim hands its stock over to the
     * delivery (held all the way), and the ledger is empty once the delivery is done. Without a beneficiary the claim keeps everyone off, as before.
     *
     * @param network storage network on, off, or the default (null).
     */
    public static void makeClaimHandover(final GameTestHelper helper, final Boolean network)
    {
        RsFlags.overrideReservations(true);
        RsFlags.overrideStorageNetwork(network);
        try
        {
            final RsFixture f = RsFixture.found(helper, "cohesion handover " + network, 4);
            final IBuilding other = f.place(ModBlocks.blockHutBuilder, new BlockPos(24, 1, 2), "fundamentals/builder1.blueprint");
            f.stock(0, new ItemStack(Items.OAK_LOG, 8));
            final ReservationLedger ledger = f.ledger();
            final ItemStorage logs = new ItemStorage(new ItemStack(Items.OAK_LOG));
            final long now = helper.getLevel().getGameTime();
            final IToken<?> make = new StandardToken();
            ledger.setStock(f.warehouse.getID(), logs, 8, make, "make for the builder", ReservationReason.MAKE, now, f.builder.getID());

            // (b) another building is not served from the held logs
            final IToken<?> theirs = ask(other, Items.OAK_LOG, 8);
            helper.assertTrue(!f.request(theirs).hasChildren() && ledger.reservedSpace(other.getID(), stack -> true) == 0,
              "the other building gets no delivery of the held logs: " + f.describe(theirs));
            helper.assertTrue(ledger.reservedStock(logs, ReservationReason.MAKE) == 8, "the MAKE claim still holds 8");
            f.manager().updateRequestState(theirs, RequestState.CANCELLED);

            // (a) the beneficiary is served from them, and the claim hands over to the delivery
            final IToken<?> mine = ask(f.builder, Items.OAK_LOG, 8);
            final var request = f.request(mine);
            helper.assertTrue(request.getChildren().size() == 1, "the builder's request is served by one delivery: " + f.describe(mine) + " " + ledger.all());
            final IToken<?> delivery = request.getChildren().iterator().next();
            helper.assertTrue(ledger.byReason(ReservationReason.MAKE).isEmpty(), "the MAKE claim handed over: " + ledger.all());
            helper.assertTrue(ledger.reservedStock(f.warehouse.getID(), stack -> stack.is(Items.OAK_LOG)) == 8 && ledger.reservedSpace(f.builder.getID(), stack -> true) == 8,
              "the delivery holds the 8 logs and the room: " + ledger.all());
            ledger.commit(delivery, ReservationKind.STOCK, now + 1);
            ledger.commit(delivery, ReservationKind.SPACE, now + 2);
            helper.assertTrue(ledger.isEmpty(), "the ledger is empty after the delivery: " + ledger.all());

            // (c) without a beneficiary the claim keeps everyone off, the builder too
            final IToken<?> anyone = new StandardToken();
            ledger.setStock(f.warehouse.getID(), logs, 8, anyone, "make", ReservationReason.MAKE, now + 3);
            final IToken<?> again = ask(f.builder, Items.OAK_LOG, 8);
            helper.assertTrue(!f.request(again).hasChildren() && ledger.reservedStock(logs, ReservationReason.MAKE) == 8,
              "a claim for nobody keeps the builder off: " + f.describe(again));
        }
        finally
        {
            RsFlags.overrideStorageNetwork(null);
            RsFlags.overrideReservations(null);
        }
        helper.succeed();
    }
    // ------------------------------------------------------------------ mc-c2 M1: the recompute contract

    private static int claimedFor(final ReservationLedger ledger, final BlockPos building)
    {
        int total = 0;
        for (final Reservation r : ledger.byReason(ReservationReason.MAKE))
        {
            if (building.equals(r.forBuilding()))
            {
                total += r.amount();
            }
        }
        return total;
    }

    /**
     * Short stock (acceptance 1): 3 logs in store, a MAKE claim of 8 for the builder, the builder asks 8 (minimum 1): the warehouse serves the 3 and hands them over;
     * after the recompute the claims plus what is on its way for the builder are exactly the 8 needed, not 11.
     */
    public static void claimRecomputeShort(final GameTestHelper helper)
    {
        RsFlags.overrideReservations(true);
        try
        {
            final RsFixture f = RsFixture.found(helper, "cohesion recompute short", 4);
            f.stock(0, new ItemStack(Items.OAK_LOG, 3));
            final ReservationLedger ledger = f.ledger();
            final ItemStorage logs = new ItemStorage(new ItemStack(Items.OAK_LOG));
            final BlockPos x = f.builder.getID();
            final long now = helper.getLevel().getGameTime();
            final List<ReservationLedger.Need> needs = List.of(new ReservationLedger.Need(new StandardToken(), 8));
            helper.assertTrue(ledger.recomputeFor(f.manager(), f.warehouse.getID(), x, logs, needs, "make", ReservationReason.MAKE, now) == 8, "nothing on its way: 8 claimed");
            f.builder.createRequest(new Stack(new ItemStack(Items.OAK_LOG, 8), 8, 1), false);
            final int onItsWay = ledger.inFlightFor(f.manager(), x, logs);
            helper.assertTrue(onItsWay == 3, "the 3 in store are on their way or held for the builder: " + onItsWay + " " + ledger.all());
            ledger.recomputeFor(f.manager(), f.warehouse.getID(), x, logs, needs, "make", ReservationReason.MAKE, now + 1);
            helper.assertTrue(claimedFor(ledger, x) + ledger.inFlightFor(f.manager(), x, logs) == 8, "claims plus on its way are 8: " + ledger.all());
        }
        finally
        {
            RsFlags.overrideReservations(null);
        }
        helper.succeed();
    }

    /**
     * Two tokens of 8 logs for one building (acceptance 3), one request of 8 served: after the recompute exactly 8 stay claimed for it. The delivery arrives and
     * its logs wait at the building as a handout (acceptance 2): the recompute does not claim them again.
     */
    public static void claimRecomputeTokens(final GameTestHelper helper)
    {
        RsFlags.overrideReservations(true);
        try
        {
            final RsFixture f = RsFixture.found(helper, "cohesion recompute tokens", 4);
            f.stock(0, new ItemStack(Items.OAK_LOG, 16));
            final ReservationLedger ledger = f.ledger();
            final ItemStorage logs = new ItemStorage(new ItemStack(Items.OAK_LOG));
            final BlockPos x = f.builder.getID();
            final long now = helper.getLevel().getGameTime();
            final List<ReservationLedger.Need> needs = List.of(new ReservationLedger.Need(new StandardToken(), 8), new ReservationLedger.Need(new StandardToken(), 8));
            helper.assertTrue(ledger.recomputeFor(f.manager(), f.warehouse.getID(), x, logs, needs, "make", ReservationReason.MAKE, now) == 16, "two orders: 16 claimed");

            final IToken<?> mine = ask(f.builder, Items.OAK_LOG, 8);
            final var request = f.request(mine);
            helper.assertTrue(request.getChildren().size() == 1, "the builder's request is served: " + f.describe(mine) + " " + ledger.all());
            ledger.recomputeFor(f.manager(), f.warehouse.getID(), x, logs, needs, "make", ReservationReason.MAKE, now + 1);
            helper.assertTrue(claimedFor(ledger, x) == 8, "exactly 8 stay claimed for the second order: " + ledger.all());

            // the delivery arrives: stock and room committed, the logs wait at the building for the request (as RsCourierHooks records them)
            final IToken<?> delivery = request.getChildren().iterator().next();
            ledger.commit(delivery, ReservationKind.STOCK, now + 2);
            ledger.commit(delivery, ReservationKind.SPACE, now + 3);
            ledger.reserveStock(x, null, logs, 8, mine, "builder", ReservationReason.BUILDING_HANDOUT, now + 3);
            helper.assertTrue(ledger.inFlightFor(f.manager(), x, logs) == 8, "the arrived logs count as there: " + ledger.all());
            ledger.recomputeFor(f.manager(), f.warehouse.getID(), x, logs, needs, "make", ReservationReason.MAKE, now + 4);
            helper.assertTrue(claimedFor(ledger, x) == 8, "the recompute does not claim the arrived logs again: " + ledger.all());
        }
        finally
        {
            RsFlags.overrideReservations(null);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ mc-c2 L1

    /** With the hint budget spent, a stale pair whose factor came from a route keeps that factor instead of falling back to the straight line. */
    public static void dispatchHintBudget(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        MinecoloniesGameTests.foundGameTestColony(helper, "cohesion hints");
        final BlockPos from = helper.absolutePos(new BlockPos(-6, 1, 0));
        final BlockPos to = helper.absolutePos(new BlockPos(38, 1, 30));
        final BlockPos bend = helper.absolutePos(new BlockPos(20, 1, 7));
        for (int cx = (from.getX() - 2) >> 4; cx <= (to.getX() + 2) >> 4; cx++)
        {
            for (int cz = (from.getZ() - 2) >> 4; cz <= (to.getZ() + 2) >> 4; cz++)
            {
                level.setChunkForced(cx, cz, true);
            }
        }
        // a road along from -> bend -> to, away from the straight line except near the ends
        for (final BlockPos[] leg : new BlockPos[][] {{from, bend}, {bend, to}})
        {
            for (int s = 0; s <= 200; s++)
            {
                final double t = s / 200.0D;
                final int px = (int) Math.floor(leg[0].getX() + (leg[1].getX() - leg[0].getX()) * t);
                final int pz = (int) Math.floor(leg[0].getZ() + (leg[1].getZ() - leg[0].getZ()) * t);
                level.setBlock(new BlockPos(px, from.getY() - 1, pz), Blocks.POLISHED_ANDESITE.defaultBlockState(), 3);
            }
        }
        final com.minecolonies.api.pathfinding.IRouteHintProvider hints = (l, c, a, b) -> a.equals(from) && b.equals(to) ? List.of(bend) : null;
        com.minecolonies.api.pathfinding.RouteHints.register(hints);
        try
        {
            DispatchPaths.invalidate(level);
            DispatchPaths.resetTickBudget();
            final double route = DispatchPaths.factor(level, null, from, to);
            helper.assertTrue(route < 1.15D, "the route over the road is cheap: " + route);
            DispatchPaths.invalidate(level);
            for (int i = 0; i < 64 && com.minecolonies.api.pathfinding.RouteHints.admit(level, null); i++)
            {
                com.minecolonies.api.pathfinding.RouteHints.query(level, null, from, to);
            }
            helper.assertTrue(!com.minecolonies.api.pathfinding.RouteHints.admit(level, null), "the hint budget of this tick is spent");
            DispatchPaths.resetTickBudget();
            helper.assertTrue(DispatchPaths.factor(level, null, from, to) == route && DispatchPaths.stalePairs(level) >= 1, "no hint turn: the stale pair keeps its route factor");
        }
        finally
        {
            com.minecolonies.api.pathfinding.RouteHints.unregister(hints);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ mc-c3 M1: claimForUse

    /** Moves {@code count} logs from rack 0 into the builder's hut, as a courier's pickup and drop, through the real ledger hooks. */
    @SuppressWarnings("unchecked")
    private static void carry(final RsFixture f, final IToken<?> delivery, final int count)
    {
        com.minecolonies.core.colony.requestsystem.RsCourierHooks.stockTaken(f.manager(), f.request(delivery));
        int left = count;
        final var rack = f.rack(0).getInventory();
        for (int slot = 0; slot < rack.getSlots() && left > 0; slot++)
        {
            left -= rack.extractItem(slot, left, false).getCount();
        }
        f.helper.assertTrue(left == 0, "the rack did not have " + count + " logs");
        final ItemStack rest = com.minecolonies.api.util.InventoryUtils.addItemStackToItemHandlerWithResult(f.builder.getTileEntity().getInventory(), new ItemStack(Items.OAK_LOG, count));
        f.helper.assertTrue(rest.isEmpty(), "the hut could not take the logs");
        com.minecolonies.core.colony.requestsystem.RsCourierHooks.arrived(f.manager(),
          (com.minecolonies.api.colony.requestsystem.request.IRequest<? extends com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery>) f.request(delivery));
    }

    /** Takes {@code count} logs out of the builder's hut (the order used them). */
    private static void consume(final RsFixture f, final int count)
    {
        int left = count;
        final var hut = f.builder.getTileEntity().getInventory();
        for (int slot = 0; slot < hut.getSlots() && left > 0; slot++)
        {
            if (hut.getStackInSlot(slot).is(Items.OAK_LOG))
            {
                left -= hut.extractItem(slot, left, false).getCount();
            }
        }
        f.helper.assertTrue(left == 0, "the hut did not have " + count + " logs");
    }

    private static IToken<?> servedDelivery(final RsFixture f, final IToken<?> request)
    {
        final var r = f.request(request);
        f.helper.assertTrue(r != null && r.getChildren().size() == 1, "the request is served by one delivery: " + f.describe(request) + " " + f.ledger().all());
        return r.getChildren().iterator().next();
    }

    /**
     * One token that will still use 8 logs, one delivery of 8 arrives through the courier hooks: the claim is 0 while the delivery is on its way, once it
     * arrived (handout open, not counted twice) and after the requester received it (logs in the hut, no handout).
     */
    public static void claimForUseArrival(final GameTestHelper helper)
    {
        RsFlags.overrideReservations(true);
        try
        {
            final RsFixture f = RsFixture.found(helper, "cohesion use arrival", 4);
            f.stock(0, new ItemStack(Items.OAK_LOG, 8));
            final ReservationLedger ledger = f.ledger();
            final ItemStorage logs = new ItemStorage(new ItemStack(Items.OAK_LOG));
            final long now = helper.getLevel().getGameTime();
            final List<ReservationLedger.Need> order = List.of(new ReservationLedger.Need(new StandardToken(), 8));
            helper.assertTrue(ledger.claimForUse(f.manager(), f.warehouse.getID(), f.builder, logs, order, "make", ReservationReason.MAKE, now) == 8, "8 claimed");
            final IToken<?> mine = ask(f.builder, Items.OAK_LOG, 8);
            final IToken<?> delivery = servedDelivery(f, mine);
            helper.assertTrue(ledger.claimForUse(f.manager(), f.warehouse.getID(), f.builder, logs, order, "make", ReservationReason.MAKE, now + 1) == 0, "on its way: 0");
            carry(f, delivery, 8);
            helper.assertTrue(ledger.reservedStock(f.builder.getID(), stack -> stack.is(Items.OAK_LOG)) == 8, "the arrival opened a handout: " + ledger.all());
            helper.assertTrue(ledger.claimForUse(f.manager(), f.warehouse.getID(), f.builder, logs, order, "make", ReservationReason.MAKE, now + 2) == 0,
              "arrived, handout open: 0, not re-claimed: " + ledger.all());
            f.manager().updateRequestState(mine, RequestState.RECEIVED);
            helper.assertTrue(ledger.reservedStock(f.builder.getID(), stack -> stack.is(Items.OAK_LOG)) == 0, "received: no handout");
            helper.assertTrue(ledger.claimForUse(f.manager(), f.warehouse.getID(), f.builder, logs, order, "make", ReservationReason.MAKE, now + 3) == 0,
              "received, logs in the hut: 0");
        }
        finally
        {
            RsFlags.overrideReservations(null);
        }
        helper.succeed();
    }

    /**
     * Two tokens that will use 8 logs each, one delivery of 8 arrives: 8 stay claimed (the arrived logs are neither claimed again nor counted twice), also after
     * the requester received them; once the first order used its 8 and the second still needs 8, 8 are claimed.
     */
    public static void claimForUseTwoTokens(final GameTestHelper helper)
    {
        RsFlags.overrideReservations(true);
        try
        {
            final RsFixture f = RsFixture.found(helper, "cohesion use tokens", 4);
            f.stock(0, new ItemStack(Items.OAK_LOG, 16));
            final ReservationLedger ledger = f.ledger();
            final ItemStorage logs = new ItemStorage(new ItemStack(Items.OAK_LOG));
            final long now = helper.getLevel().getGameTime();
            final IToken<?> first = new StandardToken();
            final IToken<?> second = new StandardToken();
            final List<ReservationLedger.Need> both = List.of(new ReservationLedger.Need(first, 8), new ReservationLedger.Need(second, 8));
            helper.assertTrue(ledger.claimForUse(f.manager(), f.warehouse.getID(), f.builder, logs, both, "make", ReservationReason.MAKE, now) == 16, "16 claimed");
            final IToken<?> mine = ask(f.builder, Items.OAK_LOG, 8);
            final IToken<?> delivery = servedDelivery(f, mine);
            helper.assertTrue(ledger.claimForUse(f.manager(), f.warehouse.getID(), f.builder, logs, both, "make", ReservationReason.MAKE, now + 1) == 8, "8 on its way, 8 claimed");
            carry(f, delivery, 8);
            helper.assertTrue(ledger.claimForUse(f.manager(), f.warehouse.getID(), f.builder, logs, both, "make", ReservationReason.MAKE, now + 2) == 8,
              "arrived: 8 claimed, not 16 (re-claimed) nor 0 (counted twice): " + ledger.all());
            f.manager().updateRequestState(mine, RequestState.RECEIVED);
            helper.assertTrue(ledger.claimForUse(f.manager(), f.warehouse.getID(), f.builder, logs, both, "make", ReservationReason.MAKE, now + 3) == 8, "received: still 8");
            consume(f, 8);
            final List<ReservationLedger.Need> after = List.of(new ReservationLedger.Need(first, 0), new ReservationLedger.Need(second, 8));
            helper.assertTrue(ledger.claimForUse(f.manager(), f.warehouse.getID(), f.builder, logs, after, "make", ReservationReason.MAKE, now + 4) == 8,
              "the first order used its 8, the second still needs 8: 8 claimed");
            helper.assertTrue(ledger.reservationsOf(first).isEmpty() && ledger.reservationsOf(second).get(0).amount() == 8, "on the second token: " + ledger.all());
        }
        finally
        {
            RsFlags.overrideReservations(null);
        }
        helper.succeed();
    }

    /** Short stock: 3 logs in store, a token that will use 8, the builder asks 8 (minimum 1): the 3 are on their way and 5 are claimed, 8 in all. */
    public static void claimForUseShort(final GameTestHelper helper)
    {
        RsFlags.overrideReservations(true);
        try
        {
            final RsFixture f = RsFixture.found(helper, "cohesion use short", 4);
            f.stock(0, new ItemStack(Items.OAK_LOG, 3));
            final ReservationLedger ledger = f.ledger();
            final ItemStorage logs = new ItemStorage(new ItemStack(Items.OAK_LOG));
            final long now = helper.getLevel().getGameTime();
            final List<ReservationLedger.Need> order = List.of(new ReservationLedger.Need(new StandardToken(), 8));
            ledger.claimForUse(f.manager(), f.warehouse.getID(), f.builder, logs, order, "make", ReservationReason.MAKE, now);
            f.builder.createRequest(new Stack(new ItemStack(Items.OAK_LOG, 8), 8, 1), false);
            final int claimed = ledger.claimForUse(f.manager(), f.warehouse.getID(), f.builder, logs, order, "make", ReservationReason.MAKE, now + 1);
            final int coming = ledger.comingTo(f.manager(), f.builder.getID(), logs);
            helper.assertTrue(coming == 3 && claimed == 5, "3 on their way, 5 claimed: " + coming + " / " + claimed + " " + ledger.all());
        }
        finally
        {
            RsFlags.overrideReservations(null);
        }
        helper.succeed();
    }

    /**
     * L1: a warehouse hold for a request of building B made to fill building A's request counts as coming to B only. Claims for both buildings, short stock:
     * claims plus the hold equal the sum of the needs.
     */
    public static void claimForUseChainHold(final GameTestHelper helper)
    {
        RsFlags.overrideReservations(true);
        try
        {
            final RsFixture f = RsFixture.found(helper, "cohesion use chain", 4);
            final IBuilding b = f.place(ModBlocks.blockHutBuilder, new BlockPos(24, 1, 2), "fundamentals/builder1.blueprint");
            final ReservationLedger ledger = f.ledger();
            final ItemStorage logs = new ItemStorage(new ItemStack(Items.OAK_LOG));
            final long now = helper.getLevel().getGameTime();
            final IToken<?> top = ask(f.builder, Items.OAK_PLANKS, 4);
            final IToken<?> child = ask(b, Items.OAK_LOG, 8);
            f.request(child).setParent(top);
            helper.assertTrue(ReservationLedger.requesterChain(f.manager(), f.request(child)).contains(f.builder.getID()), "the chain reaches the first building");
            ledger.reserveStock(f.warehouse.getID(), f.racks.get(0), logs, 3, child, "hold", ReservationReason.WAREHOUSE_DELIVERY, now);
            helper.assertTrue(ledger.comingTo(f.manager(), b.getID(), logs) == 3 && ledger.comingTo(f.manager(), f.builder.getID(), logs) == 0,
              "the hold comes to its own requester only");
            final int forA = ledger.claimForUse(f.manager(), f.warehouse.getID(), f.builder, logs, List.of(new ReservationLedger.Need(new StandardToken(), 8)), "a",
              ReservationReason.MAKE, now);
            final int forB = ledger.claimForUse(f.manager(), f.warehouse.getID(), b, logs, List.of(new ReservationLedger.Need(new StandardToken(), 8)), "b",
              ReservationReason.MAKE, now);
            helper.assertTrue(forA + forB + 3 == 16, "claims plus the hold are the needs: " + forA + " + " + forB + " + 3");
        }
        finally
        {
            RsFlags.overrideReservations(null);
        }
        helper.succeed();
    }
}
