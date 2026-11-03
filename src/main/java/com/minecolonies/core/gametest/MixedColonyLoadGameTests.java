package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.core.entity.pathfinding.PlayerWatch;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.network.messages.server.CreateColonyMessage;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.Collections;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.crafting.RecipeStorage;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.BuildingContainers;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingLumberjack;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import com.minecolonies.core.colony.requestsystem.management.IStandardRequestManager;
import com.minecolonies.core.colony.requestsystem.RsStats;
import com.minecolonies.api.tileentities.AbstractTileEntityRack;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.api.compat.itemhandler.IItemHandler;
import com.ldtteam.structurize.api.compat.itemhandler.wrapper.InvWrapper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.clock.WorldClocks;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Two finite native production/forestry windows with three PlayerWatch-enabled mock players; no timing instrumentation. */
final class MixedColonyLoadGameTests
{
    private static final String PACK = "Minecolonies Original";
    private static final int OBS_MIN = -32;
    private static final int OBS_MAX = 96;
    private static Session active;

    private MixedColonyLoadGameTests() { }

    static void register(final RegisterGameTestsEvent event)
    {
        final String name = "mixed_colony_native_windows";
        event.registerTest(Identifier.fromNamespaceAndPath("minecolonies", name),
          info -> new MinecoloniesGameTestInstance(info, MixedColonyLoadGameTests::run),
          MinecoloniesGameTestRegistrar.longIsolatedColonyData(event, name, 80500));
    }

    static void clear(final ServerLevel level)
    {
        if (active != null && active.level == level)
        {
            final Session previous = active;
            active = null;
            previous.close();
        }
    }

    private static void run(final GameTestHelper helper)
    {
        helper.assertTrue(active == null, "mixed fixture leaked a previous session");
        active = new Session(helper);
        active.guard(active::setup).run();
    }

    private static final class Session
    {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final List<ServerPlayer> players = new ArrayList<>();
        private final Set<ChunkPos> forced = new LinkedHashSet<>();
        private final List<ICitizenData> workers = new ArrayList<>();
        private final List<BlockPos> roots = new ArrayList<>();
        private final List<BlockPos> targets = new ArrayList<>();
        private IColony a, b;
        private IBuilding builder, sawmill, warehouse, delivery;
        private BuildingLumberjack lumber;
        private IToken<?> request;
        private WorkOrderBuilding order;
        private int window, fallbackSeen;
        private long began, windowStart, stable = -1;
        private int inputLogs;
        private boolean crafted, delivered;
        private boolean choppedObserved;
        private boolean courierCarried;

        private Session(final GameTestHelper helper) { this.helper = helper; this.level = helper.getLevel(); }

        private Runnable guard(final Runnable action)
        {
            return () -> {
                try { action.run(); }
                catch (final RuntimeException | Error failure)
                {
                    try { clear(level); } catch (final RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
            };
        }

        private void later(final int ticks, final Runnable action) { helper.runAfterDelay(ticks, guard(action)); }

        private void pad(final int offset)
        {
            // Observe native idle walks beyond the work pad without moving or confining citizens.
            final BlockPos lo = helper.absolutePos(new BlockPos(offset + OBS_MIN, 0, OBS_MIN));
            final BlockPos hi = helper.absolutePos(new BlockPos(offset + OBS_MAX, 0, OBS_MAX));
            for (int x = lo.getX() >> 4; x <= hi.getX() >> 4; x++)
            {
                for (int z = lo.getZ() >> 4; z <= hi.getZ() >> 4; z++)
                {
                    if (level.setChunkForced(x, z, true)) { forced.add(new ChunkPos(x, z)); }
                }
            }
            for (int x = offset - 8; x <= offset + 60; x++)
            {
                for (int z = -8; z <= 40; z++)
                {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
                    for (int y = 1; y <= 12; y++) { helper.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState()); }
                }
            }
        }

        private void setup()
        {
            began = level.getGameTime();
            pad(0); pad(512);
            for (int i = 0; i < 3; i++) { players.add(MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper)); }
            a = found(helper, players.get(0), new BlockPos(2, 1, 2), "mixed A");
            b = found(helper, players.get(1), new BlockPos(514, 1, 2), "mixed B");
            helper.assertTrue(a.getID() != b.getID() && !players.get(0).getUUID().equals(players.get(1).getUUID()), "mixed owners/colonies not distinct");
            helper.assertTrue(a.getPermissions().setPlayerRank(players.get(2).getUUID(), a.getPermissions().getRankOfficer(), level), "co-op officer assignment failed");
            helper.assertTrue(a.getPermissions().getRank(players.get(2)).isColonyManager(), "co-op officer rank not active");
            for (final IColony colony : List.of(a, b)) { colony.getServerBuildingManager().getTownHall().setBuildingLevel(1); }
            builder = MinecoloniesGameTests.placeProductionBuilding(helper, a, ModBlocks.blockHutBuilder, new BlockPos(10, 1, 2), "fundamentals/builder1.blueprint");
            sawmill = MinecoloniesGameTests.placeProductionBuilding(helper, a, ModBlocks.blockHutSawmill, new BlockPos(20, 1, 2), "craftsmanship/carpentry/sawmill1.blueprint");
            warehouse = MinecoloniesGameTests.placeProductionBuilding(helper, a, ModBlocks.blockHutWareHouse, new BlockPos(30, 1, 2), "craftsmanship/storage/warehouse1.blueprint");
            delivery = MinecoloniesGameTests.placeProductionBuilding(helper, a, ModBlocks.blockHutDeliveryman, new BlockPos(40, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");
            lumber = ForestryGameTests.hut(helper, b, new BlockPos(520, 1, 2), false);
            waypoint(0);
            assertWatchers(helper, players);
            later(200, this::staff);
        }

        private ICitizenData spawn(final IColony colony, final BlockPos relative)
        {
            final BlockPos at = helper.absolutePos(relative);
            helper.assertTrue(level.isPositionEntityTicking(at), "mixed worker anchor not ticking: " + at);
            final ICitizenData citizen = colony.getCitizenManager().spawnOrCreateCivilian(null, level, List.of(at), true);
            helper.assertTrue(citizen != null && citizen.getEntity().isPresent(), "mixed worker spawn failed at " + at);
            citizen.getEntity().orElseThrow().setPos(at.getX() + .5, at.getY(), at.getZ() + .5);
            workers.add(citizen);
            return citizen;
        }

        private void staff()
        {
            final ICitizenData builderWorker = spawn(a, new BlockPos(12, 1, 8));
            final ICitizenData crafter = spawn(a, new BlockPos(22, 1, 8));
            final ICitizenData courier = spawn(a, new BlockPos(42, 1, 8));
            helper.assertTrue(builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(builderWorker), "mixed builder assignment failed");
            helper.assertTrue(sawmill.getModule(BuildingModules.SAWMILL_WORK).assignCitizen(crafter), "mixed sawmill assignment failed");
            helper.assertTrue(delivery.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier)
              && warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "mixed courier assignment failed");
            final var forester = ForestryGameTests.forester(helper, b, lumber, new BlockPos(522, 1, 8), new ItemStack(Items.BIRCH_SAPLING, 16));
            workers.add(forester);
            final var recipe = RecipeStorage.builder().withRecipeId(null).withInputs(List.of(new ItemStorage(new ItemStack(Items.OAK_LOG))))
              .withPrimaryOutput(new ItemStack(Items.OAK_PLANKS, 4)).build();
            helper.assertTrue(sawmill.getModule(BuildingModules.SAWMILL_CRAFT).addRecipe(IColonyManager.getInstance().getRecipeManager().checkOrAddRecipe(recipe)), "mixed recipe rejected");
            helper.assertTrue(InventoryUtils.forceItemStackToItemHandler(sawmill.getItemHandlerCap(), new ItemStack(Items.OAK_LOG, 3), item -> true).isEmpty(), "mixed input did not fit");
            fallbackSeen = RsStats.fellBack().size();
            startWindow();
            later(1, this::poll);
        }

        private void waypoint(final int phase)
        {
            final BlockPos[] at = {new BlockPos(16 + phase * 4, 1, 12), new BlockPos(526 + phase * 4, 1, 12), new BlockPos(24 - phase * 4, 1, 12)};
            for (int i = 0; i < players.size(); i++)
            {
                final ServerPlayer player = players.get(i);
                final BlockPos pos = helper.absolutePos(at[i]);
                player.teleportTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
                PlayerWatch.watchForTests(player.getUUID());
                final IColony colony = i == 1 ? b : a;
                colony.getPackageManager().addCloseSubscriber(player);
                helper.assertTrue(IColonyManager.getInstance().getColonyByPosFromWorld(level, pos) == colony, "mixed waypoint has wrong claim");
                helper.assertTrue(colony.getPackageManager().getCloseSubscribers().contains(player), "mixed subscriber absent");
            }
        }

        private void startWindow()
        {
            windowStart = level.getGameTime();
            stable = -1; order = null; crafted = false; delivered = false; courierCarried = false;
            inputLogs = craftingLogs();
            final BlockPos root = helper.absolutePos(new BlockPos(534 + window * 8, 1, 12));
            helper.assertTrue(IColonyManager.getInstance().getColonyByPosFromWorld(level, root) == b
              && IColonyManager.getInstance().getColonyByPosFromWorld(level, builder.getID()) == a, "mixed work areas have wrong claims");
            roots.add(root);
            for (int x = -3; x <= 3; x++)
            {
                for (int z = -3; z <= 3; z++) { level.setBlockAndUpdate(root.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState()); }
            }
            for (int y = 0; y < 4; y++) { level.setBlockAndUpdate(root.above(y), Blocks.BIRCH_LOG.defaultBlockState()); }
            for (int y = 2; y <= 4; y++)
            {
                for (int x = -2; x <= 2; x++)
                {
                    for (int z = -2; z <= 2; z++)
                    {
                        final BlockPos leaf = root.offset(x, y, z);
                        if (level.getBlockState(leaf).isAir()) { level.setBlockAndUpdate(leaf, Blocks.BIRCH_LEAVES.defaultBlockState()); }
                    }
                }
            }
            request = builder.createRequest(new Stack(new ItemStack(Items.OAK_PLANKS), 4, 4), true);
            helper.assertTrue(a.getRequestManager().getRequestForToken(request) != null, "mixed request absent");
            Log.getLogger().info("MIXED window={} start={} inputLogs={} root={} request={}", window, windowStart - began, inputLogs, root, request);
        }

        private void armConstruction()
        {
            final BuildingBuilder hut = (BuildingBuilder) builder;
            hut.resetNeededResources();
            order = WorkOrderBuilding.create(WorkOrderType.BUILD, builder);
            a.getWorkManager().addWorkOrder(order, false);
            helper.assertTrue(order.getID() > 0, "mixed build order absent");
            order.setClaimedBy(builder.getID()); hut.setWorkOrder(order);
            final int distance = 10 + window * 2;
            final BlockPos target = builder.getID().offset(0, 0, distance);
            helper.assertTrue(level.getBlockState(target).isAir(), "mixed construction target occupied");
            targets.add(target);
            final Blueprint blueprint = new Blueprint((short) 1, (short) 1, (short) (distance + 1))
              .setName("mixed bounded plank " + window).setFileName("mixed_bounded_" + window).setPackName(PACK);
            blueprint.addBlockState(new BlockPos(0, 0, distance), Blocks.OAK_PLANKS.defaultBlockState());
            blueprint.setCachePrimaryOffset(BlockPos.ZERO);
            order.setBlueprint(blueprint, level); order.setRequested(false); order.setCleared(false);
        }

        private void poll()
        {
            final long now = level.getGameTime();
            level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
            for (final ICitizenData worker : workers)
            {
                worker.setSaturation(20);
                helper.assertTrue(worker.getEntity().isPresent() && worker.getEntity().orElseThrow().isAlive(), "mixed worker disappeared");
                helper.assertTrue(level.isPositionEntityTicking(worker.getEntity().orElseThrow().blockPosition()), "mixed worker stopped ticking");
            }
            if ((now - windowStart) % 80 == 0) { waypoint((int) (((now - windowStart) / 80) % 2)); assertWatchers(helper, players); }
            final int logs = craftingLogs();
            final int planks = InventoryUtils.getItemCountInItemHandler(builder.getItemHandlerCap(), item -> item.is(Items.OAK_PLANKS))
              + InventoryUtils.getItemCountInItemHandler(workers.get(0).getInventory(), item -> item.is(Items.OAK_PLANKS));
            crafted |= logs < inputLogs;
            courierCarried |= InventoryUtils.getItemCountInItemHandler(workers.get(2).getInventory(), item -> item.is(Items.OAK_PLANKS)) > 0;
            delivered |= crafted && courierCarried && planks >= 4 + window * 3;
            final var current = a.getRequestManager().getRequestForToken(request);
            helper.assertTrue(current == null || current.getState() != RequestState.FAILED, "mixed production request failed");
            if (order == null && delivered && (current == null || current.getState() == RequestState.RECEIVED || current.getState() == RequestState.COMPLETED)) { armConstruction(); }
            if ((now - windowStart) % 20 == 0) { ledger(); assertRequests(); }
            final boolean built = order != null && level.getBlockState(targets.get(window)).is(Blocks.OAK_PLANKS)
              && !((BuildingBuilder) builder).hasWorkOrder() && a.getWorkManager().getWorkOrder(order.getID()) == null;
            final boolean felled = roots.stream().allMatch(root -> java.util.stream.IntStream.range(0, 4).allMatch(y -> !standingBirch(root.above(y))));
            final int kept = InventoryUtils.getItemCountInItemHandler(workers.get(3).getInventory(), item -> item.is(Items.BIRCH_LOG))
              + InventoryUtils.getItemCountInItemHandler(lumber.getItemHandlerCap(), item -> item.is(Items.BIRCH_LOG));
            if ((now - windowStart) % 200 == 0)
            {
                Log.getLogger().info("MIXED progress window={} elapsed={} inputLogs={} builderHutAndWorkerPlanks={} crafted={} courierCarried={} delivered={} built={} felled={} birchKept={} citizenStates={}",
                  window, now - windowStart, logs, planks, crafted, courierCarried, delivered, built, felled, kept,
                  workers.stream().map(worker -> worker.getId() + ":" + ((EntityCitizen) worker.getEntity().orElseThrow()).getCitizenAI().getState()).toList());
            }
            if (built && crafted && delivered && felled && kept == (window + 1) * 4)
            {
                if (stable < 0) { stable = now; }
                if (now - stable >= 160)
                {
                    for (final BlockPos target : targets)
                    {
                        helper.assertTrue(level.getBlockState(target).is(Blocks.OAK_PLANKS), "mixed completed target no longer placed: " + target);
                    }
                    ledger(); assertRequests();
                    helper.assertTrue(a.getRequestManager().getRequestForToken(request) == null, "mixed completed request still open");
                    Log.getLogger().info("MIXED window={} complete={} birchKept={} builderPlanks={} inputLogs={} persistentTargets={} custody=A:12oak,0birch;B:0oak,{}birch",
                      window, now - windowStart, kept, planks, logs, targets.size(), roots.size() * 4);
                    if (++window == 2)
                    {
                        helper.assertTrue(logs == 1 && planks == 6, "mixed residual stock differs after two windows");
                        clear(level); helper.succeed(); return;
                    }
                    startWindow();
                }
            }
            else { stable = -1; }
            helper.assertTrue(now - windowStart < 40000 && now - began < 80300, "mixed window deadline: window=" + window
              + " logs=" + logs + " planks=" + planks + " crafted=" + crafted + " delivered=" + delivered + " built=" + built + " felled=" + felled
              + " birchKept=" + kept + " request=" + current + " forester=" + ForestryGameTests.describe(workers.get(3)));
            later(1, this::poll);
        }

        private void assertRequests()
        {
            final var fellBack = RsStats.fellBack();
            for (int i = fallbackSeen; i < fellBack.size(); i++)
            {
                helper.assertTrue(!fellBack.get(i).equals(request), "mixed production parent fell back to player");
                for (final IColony colony : List.of(a, b))
                {
                    final var req = colony.getRequestManager().getRequestForToken(fellBack.get(i));
                    helper.assertTrue(req == null || !(req.getRequest() instanceof Stack stack) || !stack.getStack().is(Items.OAK_PLANKS), "producible mixed planks fell back to player");
                }
            }
            fallbackSeen = fellBack.size();
            for (final IColony colony : List.of(a, b))
            {
                final var manager = (IStandardRequestManager) colony.getRequestManager();
                for (final var reservation : manager.getReservationLedger().all())
                {
                    final var req = manager.getRequestIdentitiesDataStore().getIdentities().get(reservation.token());
                    helper.assertTrue(req != null, "mixed orphan reservation");
                    helper.assertTrue(req.getState() != RequestState.CANCELLED && req.getState() != RequestState.FAILED
                      && req.getState() != RequestState.RECEIVED && req.getState() != RequestState.OVERRULED, "mixed reservation retained terminal request");
                }
            }
        }

        /** Oak is counted in plank equivalents; birch counts the finite seeded standing logs plus physical harvest. */
        private void ledger()
        {
            final Set<IItemHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            final int[][] custody = {{0, 0}, {0, 0}};
            for (final IColony colony : List.of(a, b))
            {
                final int[] totals = custody[colony == a ? 0 : 1];
                for (final var citizen : colony.getCitizenManager().getCitizens())
                {
                    helper.assertTrue(citizen.getEntity().isPresent() && citizen.getEntity().orElseThrow().isAlive(), "mixed ledger citizen unavailable");
                    final var entity = citizen.getEntity().orElseThrow();
                    final BlockPos relative = entity.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO));
                    final int offset = colony == a ? 0 : 512;
                    helper.assertTrue(relative.getX() >= offset + OBS_MIN && relative.getX() <= offset + OBS_MAX && relative.getZ() >= OBS_MIN
                      && relative.getZ() <= OBS_MAX && relative.getY() >= 0 && relative.getY() < 32 && level.isPositionEntityTicking(entity.blockPosition()),
                      "mixed ledger citizen outside observable ticking area: colony=" + colony.getID() + " citizen=" + citizen.getId()
                        + " job=" + citizen.getJob() + " position=" + entity.blockPosition());
                    count(citizen.getInventory(), seen, totals);
                }
                final Set<BlockPos> containers = new LinkedHashSet<>();
                for (final var building : colony.getServerBuildingManager().getBuildings().values())
                {
                    containers.add(building.getID()); containers.addAll(building.getContainers());
                }
                for (final BlockPos position : containers)
                {
                    final var tile = level.getBlockEntity(position);
                    final IItemHandler inventory = tile instanceof AbstractTileEntityRack rack ? rack.getInventory()
                      : tile instanceof Container container ? new InvWrapper(container) : BuildingContainers.handler(tile);
                    helper.assertTrue(inventory != null, "mixed ledger container unavailable: " + position);
                    count(inventory, seen, totals);
                }
            }
            for (int i = 0; i < players.size(); i++)
            {
                final ServerPlayer player = players.get(i);
                final IColony colony = i == 1 ? b : a;
                helper.assertTrue(IColonyManager.getInstance().getColonyByPosFromWorld(level, player.blockPosition()) == colony,
                  "mixed player left assigned colony: " + player.getUUID());
                count(new InvWrapper(player.getInventory()), seen, custody[i == 1 ? 1 : 0]);
            }
            for (final int offset : new int[] {0, 512})
            {
                final int[] totals = custody[offset == 0 ? 0 : 1];
                final AABB area = new AABB(Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(offset + OBS_MIN, 0, OBS_MIN))),
                  Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(offset + OBS_MAX + 1, 32, OBS_MAX + 1))));
                for (final ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, area)) { count(item.getItem(), totals); }
            }
            for (final BlockPos target : targets) { if (level.getBlockState(target).is(Blocks.OAK_PLANKS)) { custody[0][0]++; } }
            for (final BlockPos root : roots)
            {
                for (int y = 0; y < 4; y++) { if (standingBirch(root.above(y))) { custody[1][1]++; } }
            }
            helper.assertTrue(custody[0][0] == 12 && custody[0][1] == 0 && custody[1][0] == 0 && custody[1][1] == roots.size() * 4,
              "mixed physical custody failed: A[oak,birch]=" + java.util.Arrays.toString(custody[0])
              + " B[oak,birch]=" + java.util.Arrays.toString(custody[1]) + " roots=" + roots.stream().map(root -> java.util.stream.IntStream.range(0, 4)
                .mapToObj(y -> root.above(y) + "=" + level.getBlockState(root.above(y))).toList()).toList()
              + " forester=" + ForestryGameTests.describe(workers.get(3)));
        }

        private boolean standingBirch(final BlockPos position)
        {
            final var state = level.getBlockState(position);
            if (state.is(Blocks.BIRCH_LOG)) { return true; }
            final var treeChop = com.minecolonies.api.compatibility.Compatibility.treeChop;
            if (!treeChop.isChoppedLog(state)) { return false; }
            final var original = treeChop.getOriginalLogState(level, position);
            helper.assertTrue(original != null && original.is(Blocks.BIRCH_LOG), "mixed seeded log has unknown/wrong TreeChop original: " + position + " " + original);
            if (!choppedObserved)
            {
                choppedObserved = true;
                Log.getLogger().info("MIXED standing chopped-log position={} state={} original={}", position, state, original);
            }
            return true;
        }

        private int craftingLogs()
        {
            return InventoryUtils.getItemCountInItemHandler(sawmill.getItemHandlerCap(), item -> item.is(Items.OAK_LOG))
              + InventoryUtils.getItemCountInItemHandler(workers.get(1).getInventory(), item -> item.is(Items.OAK_LOG));
        }

        private void count(final IItemHandler inventory, final Set<IItemHandler> seen, final int[] totals)
        {
            if (!seen.add(inventory)) { return; }
            for (int slot = 0; slot < inventory.getSlots(); slot++) { count(inventory.getStackInSlot(slot), totals); }
        }

        private void count(final ItemStack item, final int[] totals)
        {
            if (item.is(Items.OAK_LOG)) { totals[0] += item.getCount() * 4; }
            if (item.is(Items.OAK_PLANKS)) { totals[0] += item.getCount(); }
            if (item.is(Items.BIRCH_LOG)) { totals[1] += item.getCount(); }
        }

        private void close()
        {
            RuntimeException error = null;
            for (final ServerPlayer player : players)
            {
                try { PlayerWatch.watchForTests(player.getUUID()); MinecoloniesGameTests.removeConnectedTestPlayer(player); }
                catch (final RuntimeException failure) { if (error == null) { error = failure; } else { error.addSuppressed(failure); } }
            }
            for (final ChunkPos chunk : forced)
            {
                try { level.setChunkForced(chunk.x(), chunk.z(), false); }
                catch (final RuntimeException failure) { if (error == null) { error = failure; } else { error.addSuppressed(failure); } }
            }
            if (error != null) { throw error; }
        }
    }

    private static final class FoundingMessage extends CreateColonyMessage
    {
        private FoundingMessage(final BlockPos hall, final String name)
        {
            super(hall, false, name, PACK, "fundamentals/townhall1.blueprint");
        }

        private void execute(final ServerPlayer player)
        {
            onExecute(null, player);
        }
    }

    /** Caller owns the forced chunk and must release it on every exit. */
    static IColony found(final GameTestHelper helper, final ServerPlayer owner, final BlockPos relativeHall, final String name)
    {
        final var level = helper.getLevel();
        final BlockPos hall = helper.absolutePos(relativeHall);
        level.setChunkForced(hall.getX() >> 4, hall.getZ() >> 4, true);
        for (int x = -2; x <= 2; x++)
        {
            for (int z = -2; z <= 2; z++)
            {
                level.setBlock(hall.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        level.setBlock(hall, ModBlocks.blockHutTownHall.defaultBlockState(), 3);
        final var tile = level.getBlockEntity(hall);
        helper.assertTrue(tile instanceof TileEntityColonyBuilding, "mixed fixture town hall tile missing at " + hall);
        final var hut = (TileEntityColonyBuilding) tile;
        hut.setPackName(PACK);
        hut.setBlueprintPath("fundamentals/townhall1.blueprint");
        owner.teleportTo(hall.getX() + 0.5D, hall.getY(), hall.getZ() + 2.5D);
        new FoundingMessage(hall, name).execute(owner);
        final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, hall);
        helper.assertTrue(colony != null && colony.getName().equals(name)
            && owner.getUUID().equals(colony.getPermissions().getOwner()), "mixed fixture founding failed: " + name);
        return colony;
    }

    /** Verify each mock can independently activate the production watcher before work starts. */
    static void assertWatchers(final GameTestHelper helper, final List<ServerPlayer> actors)
    {
        try
        {
            for (final ServerPlayer actor : actors)
            {
                for (final ServerPlayer other : actors) { PlayerWatch.ignoreForTests(other.getUUID()); }
                helper.assertTrue(!PlayerWatch.watched(helper.getLevel(), actor.position(), (net.minecraft.world.phys.Vec3) null),
                  "another player watches the mixed fixture actor: " + actor.getUUID());
                PlayerWatch.watchForTests(actor.getUUID());
                helper.assertTrue(PlayerWatch.watched(helper.getLevel(), actor.position(), (net.minecraft.world.phys.Vec3) null),
                  "mock player does not activate PlayerWatch: " + actor.getUUID());
            }
        }
        finally
        {
            for (final ServerPlayer actor : actors) { PlayerWatch.watchForTests(actor.getUUID()); }
        }
    }
}
