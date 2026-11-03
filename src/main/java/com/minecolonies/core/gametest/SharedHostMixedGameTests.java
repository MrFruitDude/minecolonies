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
import java.util.Map;
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

/** Sustained finite-input mixed workload. Test-only scripted demand, native worker progression. */
final class SharedHostMixedGameTests
{
    private static final int[] GRID = {20, 76};
    private static final int INITIAL_LOGS = 64;
    private static final int TARGET_LIMIT = 64;
    private static Session active;

    private SharedHostMixedGameTests() { }

    static void run(final GameTestHelper helper)
    {
        helper.assertTrue(active == null, "shared mixed session already active");
        active = new Session(helper);
        active.guarded(active::setup).run();
    }

    static void clear(final ServerLevel level)
    {
        if (active != null && active.level == level) { active.close(); }
    }

    private static ItemStack axe()
    {
        final ItemStack stack = new ItemStack(Items.STONE_AXE);
        stack.set(net.minecraft.core.component.DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE);
        return stack;
    }

    private static final class Session implements SharedHostMeter.Activity
    {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final List<ServerPlayer> players = new ArrayList<>();
        private final Set<ChunkPos> forced = new LinkedHashSet<>();
        private final List<Cell> cells = new ArrayList<>();
        private final List<ICitizenData> workers = new ArrayList<>();
        private IColony a, b;
        private int fallbackSeen;
        private long tick;
        private boolean closed;
        private final boolean functionalDiagnostic = "functional".equals(System.getenv("MC_SHARED_MODE"));

        private Session(final GameTestHelper helper) { this.helper = helper; level = helper.getLevel(); }

        private Runnable guarded(final Runnable action)
        {
            return () -> {
                try { action.run(); }
                catch (final RuntimeException | Error failure)
                {
                    try { close(); } catch (final RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
            };
        }

        private void pad(final int offset)
        {
            final BlockPos lo = helper.absolutePos(new BlockPos(offset - 16, 0, -16));
            final BlockPos hi = helper.absolutePos(new BlockPos(offset + 144, 0, 160));
            for (int x = lo.getX() >> 4; x <= hi.getX() >> 4; x++)
            {
                for (int z = lo.getZ() >> 4; z <= hi.getZ() >> 4; z++)
                {
                    if (level.setChunkForced(x, z, true)) { forced.add(new ChunkPos(x, z)); }
                }
            }
            for (int x = offset; x <= offset + 128; x++)
            {
                for (int z = 0; z <= 144; z++)
                {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
                    for (int y = 1; y <= 12; y++) { helper.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState()); }
                }
            }
        }

        private void setup()
        {
            ForestryGameTests.resetTreeChop(helper);
            pad(0); pad(512);
            for (int i = 0; i < 3; i++) { players.add(MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper)); }
            a = MixedColonyLoadGameTests.found(helper, players.get(0), new BlockPos(60, 1, 76), "shared mixed A");
            b = MixedColonyLoadGameTests.found(helper, players.get(1), new BlockPos(572, 1, 76), "shared mixed B");
            helper.assertTrue(a.getID() != b.getID() && !players.get(0).getUUID().equals(players.get(1).getUUID()), "shared mixed colonies/owners not distinct");
            helper.assertTrue(a.getPermissions().setPlayerRank(players.get(2).getUUID(), a.getPermissions().getRankOfficer(), level), "shared mixed officer assignment failed");
            for (final IColony colony : List.of(a, b)) { colony.getServerBuildingManager().getTownHall().setBuildingLevel(1); }
            int index = 0;
            for (final int x : GRID)
            {
                for (final int z : GRID) { cells.add(new Cell(index++, x, z)); }
            }
            waypoint(0);
            MixedColonyLoadGameTests.assertWatchers(helper, players);
            helper.runAfterDelay(200, guarded(this::staff));
        }

        private ICitizenData spawn(final IColony colony, final BlockPos relative)
        {
            final BlockPos at = helper.absolutePos(relative);
            helper.assertTrue(level.isPositionEntityTicking(at), "shared mixed worker anchor not ticking: " + at);
            final ICitizenData worker = colony.getCitizenManager().spawnOrCreateCivilian(null, level, List.of(at), true);
            helper.assertTrue(worker != null && worker.getEntity().isPresent(), "shared mixed spawn failed");
            worker.getEntity().orElseThrow().setPos(at.getX() + .5, at.getY(), at.getZ() + .5);
            worker.getEntity().orElseThrow().getRandom().setSeed(117L + workers.size());
            workers.add(worker);
            return worker;
        }

        private void staff()
        {
            for (final Cell cell : cells) { cell.staff(); }
            fallbackSeen = RsStats.fellBack().size();
            helper.assertTrue(workers.size() == 16 && a.getServerBuildingManager().getBuildings().size()
              + b.getServerBuildingManager().getBuildings().size() >= 22, "shared mixed scale missing");
            verify();
            new SharedHostMeter(helper, "shared_host_mixed", this).start();
        }

        private void waypoint(final int phase)
        {
            final BlockPos[] relative = {new BlockPos(44 + phase * 8, 1, 44), new BlockPos(556 + phase * 8, 1, 44), new BlockPos(88 - phase * 8, 1, 100)};
            for (int i = 0; i < players.size(); i++)
            {
                final ServerPlayer player = players.get(i);
                final BlockPos at = helper.absolutePos(relative[i]);
                player.teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5);
                PlayerWatch.watchForTests(player.getUUID());
                final IColony colony = i == 1 ? b : a;
                colony.getPackageManager().addCloseSubscriber(player);
                helper.assertTrue(IColonyManager.getInstance().getColonyByPosFromWorld(level, at) == colony, "shared mixed waypoint outside claim");
            }
        }

        @Override public void maintain(final long now)
        {
            tick = now;
            level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
            for (final ICitizenData worker : workers)
            {
                worker.setSaturation(20);
                helper.assertTrue(worker.getEntity().isPresent() && worker.getEntity().orElseThrow().isAlive()
                  && level.isPositionEntityTicking(worker.getEntity().orElseThrow().blockPosition()), "shared mixed worker unavailable");
            }
            if (now % 80 == 0) { waypoint((int) ((now / 80) % 2)); }
            for (final Cell cell : cells)
            {
                cell.maintain(now);
                if (functionalDiagnostic) { cell.diagnosticTick(now); }
            }
        }

        @Override public void verifyAdmissionHeadroom()
        {
            for (final Cell cell : cells)
            {
                helper.assertTrue(cell.completed <= TARGET_LIMIT - 16, "shared mixed admission lacks 16 target slots of headroom: cell=" + cell.index);
            }
        }

        @Override public Map<String, Long> counters()
        {
            long built = 0, fells = 0;
            final Map<String, Long> progress = new java.util.HashMap<>();
            for (final Cell cell : cells)
            {
                built += cell.completed;
                final var ai = ForestryGameTests.ai(cell.forester);
                final long cellFells = ai == null ? 0 : ai.getTreeChopFellCount();
                fells += cellFells;
                progress.put("cell" + cell.index + "Windows", (long) cell.completed);
                progress.put("cell" + cell.index + "Fells", cellFells);
            }
            // Coupled window proxies, not independent per-worker craft/delivery counters.
            progress.put("builder", built); progress.put("crafter", built);
            progress.put("courier", built); progress.put("forester", fells);
            return progress;
        }

        private void count(final ItemStack item, final int[] totals)
        {
            if (item.is(Items.OAK_LOG)) { totals[0] += 4 * item.getCount(); }
            if (item.is(Items.OAK_PLANKS)) { totals[0] += item.getCount(); }
            if (item.is(Items.BIRCH_LOG)) { totals[1] += item.getCount(); }
        }

        private void count(final IItemHandler inventory, final Set<IItemHandler> seen, final int[] totals)
        {
            if (!seen.add(inventory)) { return; }
            for (int slot = 0; slot < inventory.getSlots(); slot++) { count(inventory.getStackInSlot(slot), totals); }
        }

        private boolean inArea(final BlockPos at, final int offset)
        {
            final BlockPos relative = at.subtract(helper.absolutePos(BlockPos.ZERO));
            return relative.getX() >= offset - 16 && relative.getX() <= offset + 144 && relative.getZ() >= -16
              && relative.getZ() <= 160 && relative.getY() >= 0 && relative.getY() < 32;
        }

        private boolean standing(final BlockPos at)
        {
            final var state = level.getBlockState(at);
            if (state.is(Blocks.BIRCH_LOG)) { return true; }
            final var proxy = com.minecolonies.api.compatibility.Compatibility.treeChop;
            if (!proxy.isChoppedLog(state)) { return false; }
            final var original = proxy.getOriginalLogState(level, at);
            helper.assertTrue(original != null && original.is(Blocks.BIRCH_LOG), "shared mixed unknown wrapped log: " + at);
            return true;
        }

        @Override public void verify()
        {
            final int[][] custody = {{0, 0}, {0, 0}};
            final Set<IItemHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            final Set<BlockPos> allContainers = new LinkedHashSet<>();
            for (final IColony colony : List.of(a, b))
            {
                final int index = colony == a ? 0 : 1;
                final int offset = index * 512;
                for (final ICitizenData citizen : colony.getCitizenManager().getCitizens())
                {
                    helper.assertTrue(citizen.getEntity().isPresent() && citizen.getEntity().orElseThrow().isAlive(), "shared mixed missing ledger citizen");
                    final BlockPos at = citizen.getEntity().orElseThrow().blockPosition();
                    helper.assertTrue(inArea(at, offset) && level.isPositionEntityTicking(at), "shared mixed citizen outside observation area: " + at);
                    count(citizen.getInventory(), seen, custody[index]);
                }
                for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
                {
                    final Set<BlockPos> containers = new LinkedHashSet<>(building.getContainers());
                    containers.add(building.getID());
                    for (final BlockPos at : containers)
                    {
                        if (!allContainers.add(at)) { continue; }
                        final var tile = level.getBlockEntity(at);
                        final IItemHandler inventory = tile instanceof AbstractTileEntityRack rack ? rack.getInventory()
                          : tile instanceof Container container ? new InvWrapper(container) : BuildingContainers.handler(tile);
                        helper.assertTrue(inventory != null && inArea(at, offset), "shared mixed unavailable/outside container: " + at);
                        count(inventory, seen, custody[index]);
                    }
                }
                final var manager = (IStandardRequestManager) colony.getRequestManager();
                for (final var reservation : manager.getReservationLedger().all())
                {
                    final var req = manager.getRequestIdentitiesDataStore().getIdentities().get(reservation.token());
                    helper.assertTrue(req != null && req.getState() != RequestState.CANCELLED && req.getState() != RequestState.FAILED
                      && req.getState() != RequestState.RECEIVED && req.getState() != RequestState.OVERRULED, "shared mixed orphan/terminal reservation");
                }
                final AABB area = new AABB(Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(offset - 16, 0, -16))),
                  Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(offset + 145, 32, 161))));
                for (final ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, area)) { count(item.getItem(), custody[index]); }
            }
            for (int i = 0; i < players.size(); i++)
            {
                final ServerPlayer player = players.get(i);
                final int index = i == 1 ? 1 : 0;
                helper.assertTrue(inArea(player.blockPosition(), index * 512)
                  && IColonyManager.getInstance().getColonyByPosFromWorld(level, player.blockPosition()) == (index == 0 ? a : b), "shared mixed player left assigned claim");
                count(new InvWrapper(player.getInventory()), seen, custody[index]);
            }
            int seeded = 0;
            for (final Cell cell : cells)
            {
                seeded += cell.seeded;
                for (int i = 0; i < cell.targets.size(); i++)
                {
                    final BlockPos at = cell.targets.get(i);
                    if (i < cell.completed) { helper.assertTrue(level.getBlockState(at).is(Blocks.OAK_PLANKS), "shared mixed completed target vanished"); }
                    if (level.getBlockState(at).is(Blocks.OAK_PLANKS)) { custody[0][0]++; }
                }
                for (int y = 0; y < 4; y++) { if (standing(cell.root.above(y))) { custody[1][1]++; } }
                final var entity = cell.forester.getEntity().orElseThrow();
                int axes = InventoryUtils.getItemCountInItemHandler(cell.forester.getInventory(), this::undamagedAxe)
                  + InventoryUtils.getItemCountInItemHandler(cell.lumber.getItemHandlerCap(), this::undamagedAxe);
                helper.assertTrue(axes == 17 && entity.isAlive(), "shared mixed lost/damaged axe stock");
            }
            helper.assertTrue(custody[0][0] == 4 * INITIAL_LOGS * cells.size() && custody[0][1] == 0 && custody[1][0] == 0 && custody[1][1] == seeded,
              "shared mixed custody mismatch tick=" + tick + " A=" + java.util.Arrays.toString(custody[0])
                + " B=" + java.util.Arrays.toString(custody[1]) + " birchSeeded=" + seeded);
            final var fallback = RsStats.fellBack();
            for (int i = fallbackSeen; i < fallback.size(); i++)
            {
                for (final Cell cell : cells) { helper.assertTrue(!fallback.get(i).equals(cell.request), "shared mixed parent fell back"); }
                final var req = a.getRequestManager().getRequestForToken(fallback.get(i));
                helper.assertTrue(req == null || !(req.getRequest() instanceof Stack stack) || !stack.getStack().is(Items.OAK_PLANKS), "shared mixed resolvable plank fallback");
            }
            fallbackSeen = fallback.size();
        }

        private boolean undamagedAxe(final ItemStack stack)
        {
            return stack.is(Items.STONE_AXE) && stack.has(net.minecraft.core.component.DataComponents.UNBREAKABLE) && stack.getDamageValue() == 0;
        }

        @Override public String describe()
        {
            final String base = "mixed tick=" + tick + " workers=" + workers.size() + " counters=" + counters();
            if (!functionalDiagnostic) { return base; }
            final StringBuilder detail = new StringBuilder(base);
            for (final Cell cell : cells) { detail.append("\ncell=").append(cell.index).append(" ").append(cell.diagnosticState()).append(" events=").append(cell.diagnosticEvents); }
            return detail.toString();
        }

        @Override public void close()
        {
            if (closed) { return; }
            closed = true;
            if (active == this) { active = null; }
            Throwable error = null;
            for (final ServerPlayer player : players)
            {
                try { MinecoloniesGameTests.removeConnectedTestPlayer(player); }
                catch (final RuntimeException | Error failure) { if (error == null) { error = failure; } else { error.addSuppressed(failure); } }
            }
            for (final ChunkPos chunk : forced)
            {
                try { level.setChunkForced(chunk.x(), chunk.z(), false); }
                catch (final RuntimeException | Error failure) { if (error == null) { error = failure; } else { error.addSuppressed(failure); } }
            }
            if (error instanceof RuntimeException failure) { throw failure; }
            if (error instanceof Error failure) { throw failure; }
        }

        private final class Cell
        {
            private final int index, x, z;
            private final IBuilding builder, sawmill, warehouse, delivery;
            private final BuildingLumberjack lumber;
            private final List<BlockPos> targets = new ArrayList<>();
            private final BlockPos root;
            private ICitizenData builderWorker, crafter, courier, forester;
            private IToken<?> request;
            private WorkOrderBuilding order;
            private int completed, seeded, startingLogs;
            private long windowStart;
            private long diagnosticFells, lastFellTick;
            private String diagnosticPipeline = "";
            private final java.util.ArrayDeque<String> diagnosticEvents = new java.util.ArrayDeque<>();

            private void diagnosticEvent(final String reason)
            {
                final String event = "tick=" + tick + " reason=" + reason + " " + diagnosticState();
                if (diagnosticEvents.size() == 16) { diagnosticEvents.removeFirst(); }
                diagnosticEvents.addLast(event);
                Log.getLogger().info("SHARED MIXED DIAGNOSTIC cell={} {}", index, event);
            }

            private void diagnosticTick(final long now)
            {
                final var ai = ForestryGameTests.ai(forester);
                final long fells = ai == null ? 0 : ai.getTreeChopFellCount();
                if (fells != diagnosticFells)
                {
                    diagnosticFells = fells; lastFellTick = now; diagnosticEvent("fell-counter-change");
                }
                final var current = request == null ? null : a.getRequestManager().getRequestForToken(request);
                final String pipeline = completed + ":" + request + ":" + crafted + ":" + carried + ":" + delivered + ":" + courierEmptyObserved
                  + ":" + (current == null ? "gone" : current.getState()) + ":" + (order == null ? "none" : order.getID());
                if (!pipeline.equals(diagnosticPipeline)) { diagnosticPipeline = pipeline; diagnosticEvent("pipeline-transition"); }
                if (now % 400 == 0) { diagnosticEvent("regrow-check"); }
            }

            private String diagnosticRefusedOutcome(final Object ai)
            {
                if (ai == null) { return "no-ai"; }
                try { return String.valueOf(ai.getClass().getMethod("getTreeChopRefusedOutcomeCount").invoke(ai)); }
                catch (final ReflectiveOperationException unavailable) { return "unavailable-on-this-jar"; }
            }

            private String diagnosticWorker(final ICitizenData citizen)
            {
                return citizen.getEntity().map(entity -> "status=" + citizen.getStatus().getTranslationKey() + " leisure=" + citizen.getLeisureTime()
                  + " lifeState=" + (entity instanceof com.minecolonies.core.entity.citizen.EntityCitizen concrete ? concrete.getCitizenAI().getState() : "unavailable") + " pos=" + entity.blockPosition()
                  + " state=" + (entity.getCitizenJobHandler().getWorkAI() == null ? "no-ai" : entity.getCitizenJobHandler().getWorkAI().getStateAI().getState())
                  + " navDone=" + entity.getNavigation().isDone() + " path=" + entity.getNavigation().getPath()).orElse("no-entity");
            }

            private String diagnosticRequest(final IToken<?> token, final int depth)
            {
                if (token == null) { return "none"; }
                final var manager = a.getRequestManager();
                final var current = manager.getRequestForToken(token);
                if (current == null) { return token + "=gone"; }
                String resolver;
                try
                {
                    final var assigned = manager.getResolverForRequest(token);
                    resolver = assigned == null ? "unassigned" : assigned.getClass().getSimpleName() + "@" + assigned.getLocation().getInDimensionLocation();
                }
                catch (final IllegalArgumentException missing) { resolver = "unavailable"; }
                final StringBuilder result = new StringBuilder().append(token).append("=").append(current.getState())
                  .append(" type=").append(current.getRequest().getClass().getSimpleName()).append(" resolver=").append(resolver);
                if (depth < 2)
                {
                    int count = 0;
                    for (final IToken<?> child : current.getChildren())
                    {
                        if (count++ == 8) { result.append(" [more children]"); break; }
                        result.append(" [").append(diagnosticRequest(child, depth + 1)).append("]");
                    }
                }
                return result.toString();
            }

            private String diagnosticProduction()
            {
                final int hut = InventoryUtils.getItemCountInItemHandler(builder.getItemHandlerCap(), item -> item.is(Items.OAK_PLANKS));
                final int held = InventoryUtils.getItemCountInItemHandler(builderWorker.getInventory(), item -> item.is(Items.OAK_PLANKS));
                final StringBuilder warehouses = new StringBuilder();
                for (final Cell cell : cells) { warehouses.append(cell.index).append(":").append(InventoryUtils.getItemCountInItemHandler(cell.warehouse.getItemHandlerCap(), item -> item.is(Items.OAK_PLANKS))).append(" "); }
                final StringBuilder crafting = new StringBuilder();
                if (crafter.getJob() instanceof com.minecolonies.core.colony.jobs.AbstractJobCrafter<?, ?> job)
                {
                    int count = 0;
                    for (final IToken<?> token : job.getTaskQueue())
                    {
                        if (count++ == 8) { crafting.append(" [more tasks]"); break; }
                        crafting.append(" [queued ").append(diagnosticRequest(token, 0)).append("]");
                    }
                    count = 0;
                    for (final IToken<?> token : job.getAssignedTasks())
                    {
                        if (count++ == 8) { crafting.append(" [more assignments]"); break; }
                        crafting.append(" [assigned ").append(diagnosticRequest(token, 0)).append("]");
                    }
                }
                final StringBuilder queue = new StringBuilder();
                if (courier.getJob() instanceof com.minecolonies.core.colony.jobs.JobDeliveryman job)
                {
                    int count = 0;
                    for (final IToken<?> token : job.getTaskQueue())
                    {
                        if (count++ == 8) { queue.append(" [more tasks]"); break; }
                        queue.append(" [").append(diagnosticRequest(token, 0)).append("]");
                    }
                }
                return "window=" + completed + " windowStart=" + windowStart + " flags=" + crafted + "/" + carried + "/" + delivered + "/" + courierEmptyObserved
                  + " logs=" + logs() + "/" + startingLogs + " planksHut=" + hut + " planksBuilder=" + held + " required=" + (4 + completed * 3)
                  + " courierPlanks=" + InventoryUtils.getItemCountInItemHandler(courier.getInventory(), item -> item.is(Items.OAK_PLANKS))
                  + " craftingQueue=" + crafting + " warehousePlanks=" + warehouses + " request=" + diagnosticRequest(request, 0) + " queue=" + queue
                  + " order=" + (order == null ? "none" : order.getID()) + " builderHasOrder=" + ((BuildingBuilder) builder).hasWorkOrder()
                  + " target=" + (targets.size() <= completed ? "not-armed" : level.getBlockState(targets.get(completed)))
                  + " builder[" + diagnosticWorker(builderWorker) + "] crafter[" + diagnosticWorker(crafter) + "] courier[" + diagnosticWorker(courier) + "]";
            }

            private String diagnosticState()
            {
                final var ai = ForestryGameTests.ai(forester);
                final StringBuilder blocks = new StringBuilder();
                for (int y = 0; y < 4; y++)
                {
                    final BlockPos at = root.above(y);
                    blocks.append(" y").append(y).append("=").append(level.getBlockState(at))
                      .append(" blocked=").append(com.minecolonies.core.entity.ai.workers.util.TreeClaims.isBlocked(level, at))
                      .append(" taken=").append(com.minecolonies.core.entity.ai.workers.util.TreeClaims.isTakenByOther(level, at, b.getID(), forester.getId()));
                }
                final var tree = forester.getJob() instanceof com.minecolonies.core.colony.jobs.JobLumberjack job ? job.getTree() : null;
                return diagnosticProduction() + " seeded=" + seeded + " regrows=" + Math.max(0, seeded / 4 - 1) + " lastFell=" + lastFellTick + blocks
                  + " stumpOwnsRoot=" + (tree != null && tree.getStumpLocations().contains(root))
                  + " fells=" + (ai == null ? 0 : ai.getTreeChopFellCount()) + " chops=" + (ai == null ? 0 : ai.getTreeChopChopCount())
                  + " refusals=" + (ai == null ? 0 : ai.getTreeChopRefusedCount()) + " markRefused=" + (ai == null ? 0 : ai.getTreeChopMarkRefusedCount())
                  + " refusedOutcome=" + diagnosticRefusedOutcome(ai) + " skipped=" + lumber.getSkippedTreeCount()
                  + " hutSaplings=" + InventoryUtils.getItemCountInItemHandler(lumber.getItemHandlerCap(), item -> item.is(Items.BIRCH_SAPLING))
                  + " hutAxes=" + InventoryUtils.getItemCountInItemHandler(lumber.getItemHandlerCap(), item -> item.is(Items.STONE_AXE))
                  + " forester[" + diagnosticWorker(forester) + "] " + ForestryGameTests.describe(forester);
            }
            private boolean crafted, carried, delivered, courierEmptyObserved;

            private Cell(final int index, final int x, final int z)
            {
                this.index = index; this.x = x; this.z = z;
                builder = MinecoloniesGameTests.placeProductionBuilding(helper, a, ModBlocks.blockHutBuilder, new BlockPos(x, 1, z), "fundamentals/builder1.blueprint");
                sawmill = MinecoloniesGameTests.placeProductionBuilding(helper, a, ModBlocks.blockHutSawmill, new BlockPos(x + 10, 1, z), "craftsmanship/carpentry/sawmill1.blueprint");
                warehouse = MinecoloniesGameTests.placeProductionBuilding(helper, a, ModBlocks.blockHutWareHouse, new BlockPos(x + 20, 1, z), "craftsmanship/storage/warehouse1.blueprint");
                delivery = MinecoloniesGameTests.placeProductionBuilding(helper, a, ModBlocks.blockHutDeliveryman, new BlockPos(x + 30, 1, z), "craftsmanship/storage/deliveryman1.blueprint");
                lumber = ForestryGameTests.hut(helper, b, new BlockPos(512 + x, 1, z), false);
                root = helper.absolutePos(new BlockPos(512 + x + (x == 20 ? -18 : 18), 1, z));
                helper.assertTrue(IColonyManager.getInstance().getColonyByPosFromWorld(level, builder.getID()) == a
                  && IColonyManager.getInstance().getColonyByPosFromWorld(level, root) == b, "shared mixed work area outside claim");
            }

            private void staff()
            {
                builderWorker = spawn(a, new BlockPos(x + 2, 1, z + 4));
                crafter = spawn(a, new BlockPos(x + 12, 1, z + 4));
                courier = spawn(a, new BlockPos(x + 32, 1, z + 4));
                helper.assertTrue(builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(builderWorker)
                  && sawmill.getModule(BuildingModules.SAWMILL_WORK).assignCitizen(crafter)
                  && delivery.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier)
                  && warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "shared mixed role assignment failed");
                forester = ForestryGameTests.forester(helper, b, lumber, new BlockPos(512 + x + 6, 1, z + 4), new ItemStack(Items.BIRCH_SAPLING, 64));
                workers.add(forester);
                forester.getEntity().orElseThrow().getRandom().setSeed(121L + index);
                forester.getInventory().setStackInSlot(0, axe());
                for (int i = 0; i < 16; i++)
                {
                    helper.assertTrue(InventoryUtils.forceItemStackToItemHandler(lumber.getItemHandlerCap(), axe(), item -> true).isEmpty(), "shared mixed axe stock did not fit");
                }
                final var recipe = RecipeStorage.builder().withRecipeId(null).withInputs(List.of(new ItemStorage(new ItemStack(Items.OAK_LOG))))
                  .withPrimaryOutput(new ItemStack(Items.OAK_PLANKS, 4)).build();
                helper.assertTrue(sawmill.getModule(BuildingModules.SAWMILL_CRAFT).addRecipe(IColonyManager.getInstance().getRecipeManager().checkOrAddRecipe(recipe)), "shared mixed recipe rejected");
                helper.assertTrue(InventoryUtils.forceItemStackToItemHandler(sawmill.getItemHandlerCap(), new ItemStack(Items.OAK_LOG, INITIAL_LOGS), item -> true).isEmpty(), "shared mixed initial stock did not fit");
                plantTree();
            }

            private void plantTree()
            {
                for (int x = -3; x <= 3; x++)
                {
                    for (int z = -3; z <= 3; z++) { level.setBlockAndUpdate(root.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState()); }
                }
                for (int y = 0; y < 4; y++) { level.setBlockAndUpdate(root.above(y), Blocks.BIRCH_LOG.defaultBlockState()); }
                for (int y = 2; y <= 4; y++)
                {
                    for (int dx = -2; dx <= 2; dx++)
                    {
                        for (int dz = -2; dz <= 2; dz++)
                        {
                            final BlockPos leaf = root.offset(dx, y, dz);
                            if (level.getBlockState(leaf).isAir()) { level.setBlockAndUpdate(leaf, Blocks.BIRCH_LEAVES.defaultBlockState()); }
                        }
                    }
                }
                seeded += 4;
                if (functionalDiagnostic && forester != null) { diagnosticEvent("planted-tree"); }
            }

            private int logs()
            {
                return InventoryUtils.getItemCountInItemHandler(sawmill.getItemHandlerCap(), item -> item.is(Items.OAK_LOG))
                  + InventoryUtils.getItemCountInItemHandler(crafter.getInventory(), item -> item.is(Items.OAK_LOG));
            }

            private void begin(final long now)
            {
                helper.assertTrue(completed < TARGET_LIMIT, "shared mixed exhausted finite target/stock schedule");
                startingLogs = logs(); windowStart = now;
                crafted = false; carried = false; delivered = false; order = null;
                courierEmptyObserved = InventoryUtils.getItemCountInItemHandler(courier.getInventory(), item -> item.is(Items.OAK_PLANKS)) == 0;
                // Each window demands new delivery; saved stock belongs to earlier windows.
                final Stack demand = new Stack(new ItemStack(Items.OAK_PLANKS), 4, 4);
                demand.setCanBeResolvedByBuilding(false);
                final int hutPlanks = InventoryUtils.getItemCountInItemHandler(builder.getItemHandlerCap(), item -> item.is(Items.OAK_PLANKS));
                final int workerPlanks = InventoryUtils.getItemCountInItemHandler(builderWorker.getInventory(), item -> item.is(Items.OAK_PLANKS));
                request = builder.createRequest(demand, true);
                helper.assertTrue(a.getRequestManager().getRequestForToken(request) != null,
                  "shared mixed request absent: cell=" + index + " completed=" + completed + " startingLogs=" + startingLogs + " hutPlanks=" + hutPlanks + " workerPlanks=" + workerPlanks);
            }

            private void arm()
            {
                final BuildingBuilder hut = (BuildingBuilder) builder;
                hut.resetNeededResources();
                order = WorkOrderBuilding.create(WorkOrderType.BUILD, builder);
                a.getWorkManager().addWorkOrder(order, false);
                helper.assertTrue(order.getID() > 0, "shared mixed order absent");
                order.setClaimedBy(builder.getID()); hut.setWorkOrder(order);
                final int dx = (completed / 8) * 2, dz = 8 + (completed % 8) * 2;
                final BlockPos target = builder.getID().offset(dx, 0, dz);
                helper.assertTrue(level.getBlockState(target).isAir(), "shared mixed finite target occupied");
                targets.add(target);
                final Blueprint blueprint = new Blueprint((short) (dx + 1), (short) 1, (short) (dz + 1))
                  .setName("shared mixed " + index + " " + completed).setFileName("shared_mixed_" + index + "_" + completed).setPackName("Minecolonies Original");
                blueprint.addBlockState(new BlockPos(dx, 0, dz), Blocks.OAK_PLANKS.defaultBlockState());
                blueprint.setCachePrimaryOffset(BlockPos.ZERO);
                order.setBlueprint(blueprint, level); order.setRequested(false); order.setCleared(false);
            }

            private void maintain(final long now)
            {
                if (request == null)
                {
                    if (now >= 1 + index * 100) { begin(now); }
                }
                else
                {
                    crafted |= logs() < startingLogs;
                    final int courierPlanks = InventoryUtils.getItemCountInItemHandler(courier.getInventory(), item -> item.is(Items.OAK_PLANKS));
                    courierEmptyObserved |= courierPlanks == 0;
                    carried |= courierEmptyObserved && courierPlanks > 0;
                    final int planks = InventoryUtils.getItemCountInItemHandler(builder.getItemHandlerCap(), item -> item.is(Items.OAK_PLANKS))
                      + InventoryUtils.getItemCountInItemHandler(builderWorker.getInventory(), item -> item.is(Items.OAK_PLANKS));
                    delivered |= crafted && carried && planks >= 4 + completed * 3;
                    final var current = a.getRequestManager().getRequestForToken(request);
                    helper.assertTrue(current == null || current.getState() != RequestState.FAILED, "shared mixed request failed");
                    if (order == null && delivered && (current == null || current.getState() == RequestState.RECEIVED || current.getState() == RequestState.COMPLETED)) { arm(); }
                    if (order != null && level.getBlockState(targets.get(completed)).is(Blocks.OAK_PLANKS)
                      && !((BuildingBuilder) builder).hasWorkOrder() && a.getWorkManager().getWorkOrder(order.getID()) == null
                      && a.getRequestManager().getRequestForToken(request) == null)
                    {
                        completed++;
                        request = null; order = null;
                    }
                    helper.assertTrue(now - windowStart < 4000, "shared mixed native production stalled: cell=" + index + " window=" + completed);
                }
                if (now % 400 == 0 && level.getBlockState(root).is(Blocks.BIRCH_SAPLING))
                {
                    final boolean activeTree = workers.stream().anyMatch(worker -> worker.getJob() instanceof com.minecolonies.core.colony.jobs.JobLumberjack job
                      && job.getTree() != null && job.getTree().getStumpLocations().contains(root));
                    if (!activeTree && java.util.stream.IntStream.range(0, 4).noneMatch(y -> standing(root.above(y)))) { plantTree(); }
                }
            }
        }
    }
}
