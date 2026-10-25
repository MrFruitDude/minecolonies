package com.minecolonies.core.gametest;

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
 * Each test sets the rules provider and puts the default back inside its one synchronous body, and its provider only answers for its own colony, so
 * tests of other groups in the same batch are not affected.
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

    private static void setRules(final IColonyRulesProvider provider)
    {
        IMinecoloniesAPI.getInstance().setColonyRulesProvider(provider);
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

    /** The raid level is multiplied for the provider's colony; 0 gives 0, an unusable answer and no provider leave it unchanged. */
    public static void raidLevelMultiplier(final GameTestHelper helper)
    {
        final RsFixture f = RsFixture.found(helper, "cohesion raid", 0, false);
        f.spawn(new BlockPos(4, 1, 6), true);
        f.spawn(new BlockPos(6, 1, 6), true);
        final IColony colony = f.colony;
        try
        {
            final int base = colony.getRaiderManager().getColonyRaidLevel();
            helper.assertTrue(base > 0, "the fixture has no raid level: " + base);
            final double[] answer = {2.0D};
            setRules(new IColonyRulesProvider()
            {
                @Override
                public double raidLevelMultiplier(@NotNull final IColony c)
                {
                    return c == colony ? answer[0] : 1.0D;
                }
            });
            final int doubled = colony.getRaiderManager().getColonyRaidLevel();
            helper.assertTrue(doubled >= 2 * base && doubled <= 2 * base + 1, "x2: " + base + " -> " + doubled);
            answer[0] = 0.0D;
            helper.assertTrue(colony.getRaiderManager().getColonyRaidLevel() == 0, "x0 gives 0");
            for (final double broken : new double[] {Double.NaN, -1.0D, Double.POSITIVE_INFINITY})
            {
                answer[0] = broken;
                helper.assertTrue(colony.getRaiderManager().getColonyRaidLevel() == base, "an unusable answer " + broken + " is ignored");
            }
            setRules(null);
            helper.assertTrue(colony.getRaiderManager().getColonyRaidLevel() == base, "no provider: unchanged");
            helper.assertTrue(IMinecoloniesAPI.getInstance().getColonyRulesProvider() == IColonyRulesProvider.DEFAULT, "null puts the default back");
        }
        finally
        {
            setRules(null);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ A-6

    private static boolean buildingClaims(final ServerLevel level, final IColony colony, final ChunkPos chunk)
    {
        final IChunkClaimData data = IColonyManager.getInstance().getClaimData(level.dimension(), chunk);
        return data != null && data.getAllClaimingBuildings().containsKey(colony.getID());
    }

    /**
     * A building claim stops at the provider's radius (chunks from the centre chunk, square); without a limit it reaches its full radius; a direct
     * {@code tryClaimBuilding} (an add-on's ring purchase) and unclaiming are not limited.
     */
    public static void claimRadiusLimit(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "cohesion claims");
        final BlockPos hall = colony.getCenter();
        final ChunkPos center = ChunkPos.containing(hall);
        final ChunkPos two = new ChunkPos(center.x() + 2, center.z());
        final ChunkPos three = new ChunkPos(center.x() + 3, center.z() - 3);
        final ChunkPos four = new ChunkPos(center.x(), center.z() + 4);
        try
        {
            setRules(new IColonyRulesProvider()
            {
                @Override
                public int claimRadiusLimit(@NotNull final IColony c)
                {
                    return c == colony ? 2 : -1;
                }
            });
            ChunkDataHelper.claimBuildingChunks((Colony) colony, true, hall, 4, null);
            helper.assertTrue(buildingClaims(level, colony, two), "inside the limit the building claims");
            helper.assertTrue(!buildingClaims(level, colony, three) && !buildingClaims(level, colony, four), "past the limit the building claims nothing");
            helper.assertTrue(ChunkDataHelper.beyondClaimLimit(colony, center, three) && !ChunkDataHelper.beyondClaimLimit(colony, center, two), "limit check is square");

            // the ring purchase path claims directly, past the building limit
            helper.assertTrue(ChunkDataHelper.tryClaimBuilding(level, three, true, (Colony) colony, hall) && buildingClaims(level, colony, three),
              "a direct claim is not limited");

            // no limit: the full radius, as before
            setRules(null);
            ChunkDataHelper.claimBuildingChunks((Colony) colony, true, hall, 4, null);
            helper.assertTrue(buildingClaims(level, colony, four), "without a limit the building claims its whole radius");

            // unclaiming is never limited
            setRules(new IColonyRulesProvider()
            {
                @Override
                public int claimRadiusLimit(@NotNull final IColony c)
                {
                    return c == colony ? 2 : -1;
                }
            });
            ChunkDataHelper.claimBuildingChunks((Colony) colony, false, hall, 4, null);
            helper.assertTrue(!buildingClaims(level, colony, four) && !buildingClaims(level, colony, two), "unclaiming reaches past the limit");
        }
        finally
        {
            setRules(null);
        }
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

        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "cohesion ledger");
        helper.assertTrue(ReservationLedger.of(colony) != null, "a server colony has a ledger");
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
        helper.assertTrue(mine != null && mine.target() == target && mine.startBelow() == target && mine.item().equals(goodId), "the entry is a goal: " + goals);
        helper.assertTrue(goals.stream().noneMatch(g -> !g.id().startsWith(LabourBoard.MIN_STOCK_GOAL) && g.item().equals(goodId)), "the seed goal for the same item is replaced");
        helper.assertTrue(goals.stream().noneMatch(g -> g.item().contains(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(other).getPath())),
          "a good labourers cannot make is no goal");
        helper.assertTrue(goals.size() >= seed.size() - 1, "the other seed goals stay: " + goals);

        final List<LabourBoard.StockGoalView> views = board.stockGoalViews();
        helper.assertTrue(views.size() == goals.size(), "one view per goal");
        final LabourBoard.StockGoalView view = views.stream().filter(v -> v.goal().id().equals(mine.id())).findFirst().orElse(null);
        helper.assertTrue(view != null && view.warehouse() && view.stock() == 0 && view.inFlight() == 0, "the view: " + view);
        helper.succeed();
    }

    // ------------------------------------------------------------------ B-4

    /**
     * Off road the estimate is the straight line (exactly {@link DispatchCost#PATH_FACTOR}); a road along the way makes it cheaper; the answer is cached until a
     * road block changes or the cache is dropped.
     */
    public static void dispatchRoads(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        MinecoloniesGameTests.foundGameTestColony(helper, "cohesion roads");
        final BlockPos from = helper.absolutePos(new BlockPos(0, 1, 6));
        final BlockPos to = helper.absolutePos(new BlockPos(36, 1, 6));
        for (int x = from.getX() >> 4; x <= to.getX() >> 4; x++)
        {
            level.setChunkForced(x, from.getZ() >> 4, true);
        }
        DispatchPaths.invalidate(level);
        final double offRoad = DispatchPaths.factor(level, null, from, to);
        helper.assertTrue(offRoad == DispatchCost.PATH_FACTOR, "off road: " + offRoad);
        helper.assertTrue(DispatchPaths.travelTicks(null, from, to) == DispatchCost.travelTicks(from, to), "no colony: the straight line");

        // a road on the floor along the whole way; placed without an event, so the cached answer stays until the cache is dropped
        for (int x = from.getX(); x <= to.getX(); x++)
        {
            level.setBlock(new BlockPos(x, from.getY() - 1, from.getZ()), Blocks.POLISHED_ANDESITE.defaultBlockState(), 3);
        }
        helper.assertTrue(DispatchPaths.factor(level, null, from, to) == DispatchCost.PATH_FACTOR && DispatchPaths.cachedPairs(level) >= 1, "cached");
        DispatchPaths.invalidate(level);
        final double onRoad = DispatchPaths.factor(level, null, from, to);
        helper.assertTrue(Math.abs(onRoad - DispatchCost.PATH_FACTOR / 1.3D) < 0.01D, "on a road the walk counts 1.3x faster: " + onRoad);

        // half of it road
        for (int x = from.getX() + 18; x <= to.getX(); x++)
        {
            level.setBlock(new BlockPos(x, from.getY() - 1, from.getZ()), Blocks.STONE.defaultBlockState(), 3);
        }
        DispatchPaths.invalidate(level);
        final double half = DispatchPaths.factor(level, null, from, to);
        helper.assertTrue(half > onRoad && half < offRoad, "half on road is in between: " + half);

        // breaking a road block drops the cache
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
        DispatchPaths.onBreak(new BreakBlockEvent(level, from.below(), Blocks.POLISHED_ANDESITE.defaultBlockState(), player));
        helper.assertTrue(DispatchPaths.cachedPairs(level) == 0, "a road change drops the cache");
        DispatchPaths.onBreak(new BreakBlockEvent(level, from.below(), Blocks.STONE.defaultBlockState(), player));
        helper.succeed();
    }

    // ------------------------------------------------------------------ B-6

    /** The dry run names the resolvers that could take a request for logs, the stocked warehouse among them, and changes nothing. */
    public static void requestTargets(final GameTestHelper helper)
    {
        final RsFixture f = RsFixture.found(helper, "cohesion targets", 1, false);
        f.stock(0, new ItemStack(Items.OAK_LOG, 16));
        final long revision = f.ledger().revision();
        final int requests = f.standard().getRequestIdentitiesDataStore().getIdentities().size();

        final List<RequestTargets.Candidate> found = RequestTargets.forItem(f.colony, f.builder, new ItemStack(Items.OAK_LOG), 4);
        final StringBuilder said = new StringBuilder();
        for (final RequestTargets.Candidate c : found)
        {
            said.append(c.resolver().getClass().getSimpleName()).append('@').append(c.building()).append(" p").append(c.priority()).append(" m").append(c.metric()).append("; ");
        }
        helper.assertTrue(!found.isEmpty(), "nobody would take a request for logs");
        helper.assertTrue(found.stream().anyMatch(c -> f.warehouse.getID().equals(c.building()) || c.resolver().getClass().getSimpleName().contains("Warehouse")),
          "the stocked warehouse is a candidate: " + said);
        helper.assertTrue(f.ledger().revision() == revision, "the dry run reserved nothing");
        helper.assertTrue(f.standard().getRequestIdentitiesDataStore().getIdentities().size() == requests, "the dry run registered no request");
        helper.assertTrue(RequestTargets.forItem(f.colony, f.builder, ItemStack.EMPTY, 1).isEmpty(), "an empty stack has no target");
        helper.succeed();
    }

    // ------------------------------------------------------------------ C-4, C-6

    /** The provider refuses a hire through MineColonies' hire message (the citizen stays unassigned); no provider, no refusal; the client defaults change nothing. */
    public static void hireRefusal(final GameTestHelper helper)
    {
        final RsFixture f = RsFixture.found(helper, "cohesion hire", 0, false);
        final ICitizenData citizen = f.spawn(new BlockPos(4, 1, 6), true);
        IAssignsJob module = null;
        for (final IBuildingModule m : f.builder.getModules())
        {
            if (m instanceof final IAssignsJob assigns)
            {
                module = assigns;
                break;
            }
        }
        helper.assertTrue(module != null, "the builder has no job module");
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
        final IColony colony = f.colony;
        try
        {
            helper.assertTrue(!HireFireMessage.refused(player, citizen, f.builder, module), "no provider: no refusal");
            setRules(new IColonyRulesProvider()
            {
                @Override
                public Component hireRefusal(@NotNull final ICitizenData c, @NotNull final IBuilding building, @NotNull final JobEntry job)
                {
                    return building.getColony() == colony ? Component.literal("needs a tier 2 citizen") : null;
                }
            });
            helper.assertTrue(HireFireMessage.refused(player, citizen, f.builder, module), "the provider refuses");
            helper.assertTrue(citizen.getWorkBuilding() == null, "the refused citizen is not hired");
        }
        finally
        {
            setRules(null);
        }
        final IColonyRulesProvider rules = IColonyRulesProvider.DEFAULT;
        helper.assertTrue(rules.claimRadiusLimit(colony) < 0 && rules.raidLevelMultiplier(colony) == 1.0D && rules.hireRefusal(citizen, f.builder, module.getJobEntry()) == null,
          "the default changes nothing");
        helper.assertTrue(!rules.openQuestLog(player, null, ItemStack.EMPTY), "the default quest log opens MineColonies' window");
        helper.succeed();
    }
}
