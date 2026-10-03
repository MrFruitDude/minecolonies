package com.minecolonies.core.gametest;

import com.google.common.reflect.TypeToken;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.MinimumStack;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.modules.MinimumStockModule;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;
import com.minecolonies.core.colony.managers.RegisteredStructureManager;
import com.minecolonies.core.tileentities.TileEntityRack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CA-5: the 500-tick building/module colony tick is spread round-robin over 25 slots of 20 ticks instead of running
 * for every building in the same server tick. {@code colony_tick_spread} times every {@link Colony#onWorldTick} of a
 * 60-building colony (server-thread CPU time) and records every building colony tick; {@code colony_tick_spread_min_stock}
 * checks that minimum-stock requests are still created, sized and cancelled as before.
 */
public final class ColonyTickSpreadGameTests
{
    /**
     * Buildings placed besides the town hall.
     */
    private static final int BUILDINGS = 60;

    /**
     * Ticks recorded after the colony went active and warmed up (three full 500-tick cycles).
     */
    private static final int RECORD_TICKS = 1500;

    /**
     * Ticks the colony runs active before recording: the first cycle creates the min-stock requests (0.5-4.5 ms per
     * slot), and the second still runs partly cold code (one builder at ~0.6 ms), so recording starts in the third.
     */
    private static final int WARMUP_TICKS = 1100;

    private static final int CYCLE = 500;

    private static final int CYCLE_MAX = CYCLE + CYCLE / 20;

    private static final int WINDOW = 20;

    /**
     * Max single tick / median 20-tick window. Measured (CA-5 run dir): before the fix 43-65x (all buildings in one
     * tick); after it at most 3.25x over 15 runs, the max then being a slot tick with a cache-cold first building
     * (150-420 us) or a subscriber sync tick (200-610 us, not building work). The audit's 3x sits inside that noise, so
     * the bar is 6x: still an order of magnitude under the pre-fix spike.
     */
    private static final int SPIKE_FACTOR = 6;

    /**
     * Min-stock items per building (a level-3 hut holds 15 entries): the first {@code STOCKED} are kept in the
     * building's racks, the rest are missing.
     */
    private static final Item[] STOCK = {
      Items.STONE, Items.COBBLESTONE, Items.GRANITE, Items.DIORITE, Items.ANDESITE, Items.STONE_BRICKS, Items.BRICKS, Items.SANDSTONE,
      Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS, Items.TUFF};

    private static final int STOCK_LEVEL = 3;

    private static final int STOCKED = 12;

    private ColonyTickSpreadGameTests()
    {
    }

    private static void pinClock(final ServerLevel level)
    {
        level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
    }

    private static void noMoveIn(final IColony colony)
    {
        final BoolSetting moveIn = colony.getSettings().getSetting(BuildingTownHall.MOVE_IN);
        if (moveIn.getValue())
        {
            moveIn.trigger();
        }
    }

    private static void forcePad(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos min = helper.absolutePos(new BlockPos(-8, 0, -8));
        final BlockPos max = helper.absolutePos(new BlockPos(40, 0, 32));
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++)
        {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++)
            {
                level.setChunkForced(cx, cz, true);
            }
        }
    }

    private static TileEntityRack placeRack(final GameTestHelper helper, final IBuilding building, final BlockPos rel)
    {
        helper.setBlock(rel, ModBlocks.blockRack.defaultBlockState());
        final BlockPos abs = helper.absolutePos(rel);
        building.registerBlockPosition(ModBlocks.blockRack, abs, helper.getLevel());
        final BlockEntity entity = helper.getLevel().getBlockEntity(abs);
        if (!(entity instanceof final TileEntityRack rack))
        {
            throw helper.assertionException("no rack at " + abs + ": " + entity);
        }
        return rack;
    }

    private static long median(final long[] values)
    {
        final long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long gcCount()
    {
        long n = 0;
        for (final GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans())
        {
            n += Math.max(0, gc.getCollectionCount());
        }
        return n;
    }

    // ------------------------------------------------------------------ colony_tick_spread

    /**
     * B=60 colony (60 level-3 builder huts, each with four racks and 15 min-stock entries, 12 stocked and 3 missing) kept
     * active by a close subscriber. After a warm-up cycle, records 1,500 ticks of {@link Colony#onWorldTick}
     * (server-thread CPU nanos; ticks with a GC are left out of the max) and every building colony tick, then asserts:
     * <ul>
     *     <li>the max single tick is under {@link #SPIKE_FACTOR}x the median 20-tick window (the median single tick of a
     *     mostly idle colony is the bare state-machine check, so the work done once per window is the fair baseline);</li>
     *     <li>no tick runs more than ceil(B/25) building colony ticks;</li>
     *     <li>every building ticks exactly once per slow colony cycle (500 ticks plus the state machine's skips).</li>
     * </ul>
     * Before the fix every building ticks in one server tick every 500, which fails the first two.
     */
    public static void colonyTickSpread(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final Colony colony = (Colony) MinecoloniesGameTests.foundGameTestColony(helper, "CA5 tick spread");
        noMoveIn(colony);
        forcePad(helper);

        final List<IBuilding> buildings = new ArrayList<>();
        int placed = 0;
        for (int row = 0; row < 6 && placed < BUILDINGS; row++)
        {
            for (int col = 0; col < 12 && placed < BUILDINGS; col++)
            {
                final int x = -6 + 4 * col;
                final int z = 8 + 4 * row;
                final IBuilding building = MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockHutBuilder,
                  new BlockPos(x, 1, z), "fundamentals/builder1.blueprint");
                // Four racks, no two side by side (that would make a double rack).
                final TileEntityRack[] racks = {
                  placeRack(helper, building, new BlockPos(x + 1, 1, z)),
                  placeRack(helper, building, new BlockPos(x, 1, z + 1)),
                  placeRack(helper, building, new BlockPos(x + 2, 1, z + 1)),
                  placeRack(helper, building, new BlockPos(x + 1, 1, z + 2))};
                building.setBuildingLevel(STOCK_LEVEL);
                final MinimumStockModule stock = building.getFirstModuleOccurance(MinimumStockModule.class);
                helper.assertTrue(stock != null, "builder has no min-stock module: " + building);
                for (int i = 0; i < STOCK.length; i++)
                {
                    stock.addMinimumStock(new ItemStack(STOCK[i]), 1);
                    if (i < STOCKED)
                    {
                        // A quarter in each rack, so the count has to visit all four.
                        for (final TileEntityRack rack : racks)
                        {
                            rack.getInventory().setStackInSlot(i, new ItemStack(STOCK[i], 16));
                        }
                    }
                }
                helper.assertTrue(stock.isStocked(new ItemStack(STOCK[STOCK.length - 1])), "min-stock entry " + STOCK.length + " refused at level " + STOCK_LEVEL);
                buildings.add(building);
                placed++;
            }
        }
        final List<IBuilding> all = new ArrayList<>(colony.getServerBuildingManager().getBuildings().values());
        helper.assertTrue(all.size() == BUILDINGS + 1, "expected " + (BUILDINGS + 1) + " buildings, have " + all.size());

        final ServerPlayer player = MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper);
        final BlockPos anchor = colony.getCenter();
        player.teleportTo(anchor.getX() + 0.5D, anchor.getY() + 1, anchor.getZ() + 0.5D);
        colony.getPackageManager().addCloseSubscriber(player);

        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        final boolean cpuTime = threads.isCurrentThreadCpuTimeSupported();
        if (cpuTime && !threads.isThreadCpuTimeEnabled())
        {
            threads.setThreadCpuTimeEnabled(true);
        }

        final long[] cpu = new long[RECORD_TICKS];
        final long[] wall = new long[RECORD_TICKS];
        final boolean[] gc = new boolean[RECORD_TICKS];
        final int[] buildingTicksAt = new int[RECORD_TICKS];
        final Map<IBuilding, List<Long>> ticksOf = new HashMap<>();
        final long[] recordStart = {-1};
        final long[] activeSince = {-1};
        // Diagnostics: CPU nanos from one building tick's start to the next one's (or the tick's end), per tick.
        // The measuring code runs from activation on, through the warm-up, so its own first-use cost (lambda and
        // MXBean warm-up) is never recorded; it only stores while recording, and builds no strings until evaluate.
        final List<List<Map.Entry<IBuilding, Long>>> costsAt = new ArrayList<>();
        for (int i = 0; i < RECORD_TICKS; i++)
        {
            costsAt.add(new ArrayList<>());
        }
        final Object[] open = new Object[1];
        final long[] openAt = new long[1];
        // Nothing may load a class or bootstrap a lambda for the first time while recording: pre-create every list.
        for (final IBuilding building : all)
        {
            ticksOf.put(building, new ArrayList<>());
        }
        costsAt.get(0).add(new java.util.AbstractMap.SimpleEntry<>(all.get(0), 0L));
        costsAt.get(0).clear();
        final java.util.function.LongConsumer closeOpen = now -> {
            final long t = recordStart[0] < 0 ? -1 : level.getGameTime() - recordStart[0];
            if (open[0] != null && t >= 0 && t < RECORD_TICKS)
            {
                costsAt.get((int) t).add(new java.util.AbstractMap.SimpleEntry<>((IBuilding) open[0], now - openAt[0]));
            }
            open[0] = null;
        };

        final Runnable cleanup = () -> {
            Colony.worldTickWrapper = null;
            RegisteredStructureManager.buildingTickRecorder = null;
            colony.getPackageManager().removeCloseSubscriber(player);
        };

        RegisteredStructureManager.buildingTickRecorder = (c, building) -> {
            if (c != colony || activeSince[0] < 0)
            {
                return;
            }
            final long now = cpuTime ? threads.getCurrentThreadCpuTime() : System.nanoTime();
            closeOpen.accept(now);
            open[0] = building;
            openAt[0] = now;
            final long t = recordStart[0] < 0 ? -1 : level.getGameTime() - recordStart[0];
            if (t >= 0 && t < RECORD_TICKS)
            {
                buildingTicksAt[(int) t]++;
                final List<Long> list = ticksOf.get(building);
                if (list != null)
                {
                    list.add(level.getGameTime());
                }
            }
        };
        Colony.worldTickWrapper = (c, body) -> {
            if (c != colony || activeSince[0] < 0)
            {
                body.run();
                return;
            }
            final long gc0 = gcCount();
            final long c0 = cpuTime ? threads.getCurrentThreadCpuTime() : 0;
            final long w0 = System.nanoTime();
            body.run();
            final long w = System.nanoTime() - w0;
            final long c1 = cpuTime ? threads.getCurrentThreadCpuTime() : System.nanoTime();
            closeOpen.accept(c1);
            final long t = recordStart[0] < 0 ? -1 : level.getGameTime() - recordStart[0];
            if (t >= 0 && t < RECORD_TICKS)
            {
                wall[(int) t] = w;
                cpu[(int) t] = cpuTime ? c1 - c0 : w;
                gc[(int) t] = gcCount() != gc0;
            }
        };

        final Runnable[] pump = new Runnable[1];
        pump[0] = () -> {
            pinClock(level);
            final long now = level.getGameTime();
            if (activeSince[0] < 0)
            {
                if (colony.isActive())
                {
                    activeSince[0] = now;
                }
                else if (helper.getTick() > 2000)
                {
                    cleanup.run();
                    throw helper.assertionException("colony never went active");
                }
            }
            else if (recordStart[0] < 0 && now - activeSince[0] >= WARMUP_TICKS)
            {
                recordStart[0] = now + 1;
            }
            else if (recordStart[0] >= 0 && now >= recordStart[0] + RECORD_TICKS)
            {
                cleanup.run();
                evaluate(helper, all, cpu, wall, gc, buildingTicksAt, costsAt, ticksOf, recordStart[0], cpuTime);
                return;
            }
            helper.runAfterDelay(1, () -> pump[0].run());
        };
        pump[0].run();
    }

    private static void evaluate(
      final GameTestHelper helper,
      final List<IBuilding> all,
      final long[] cpu,
      final long[] wall,
      final boolean[] gc,
      final int[] buildingTicksAt,
      final List<List<Map.Entry<IBuilding, Long>>> costsAt,
      final Map<IBuilding, List<Long>> ticksOf,
      final long start,
      final boolean cpuTime)
    {
        final List<String> failures = new ArrayList<>();

        // Timing: max single tick (GC ticks left out) against the median 20-tick window.
        long maxTick = 0;
        int maxAt = -1;
        int gcTicks = 0;
        for (int t = 0; t < cpu.length; t++)
        {
            if (gc[t])
            {
                gcTicks++;
                continue;
            }
            if (cpu[t] > maxTick)
            {
                maxTick = cpu[t];
                maxAt = t;
            }
        }
        final long[] windows = new long[cpu.length / WINDOW];
        for (int w = 0; w < windows.length; w++)
        {
            for (int t = w * WINDOW; t < (w + 1) * WINDOW; t++)
            {
                windows[w] += cpu[t];
            }
        }
        final long medianWindow = median(windows);
        final long medianTick = median(cpu);
        long maxWall = 0;
        for (final long v : wall)
        {
            maxWall = Math.max(maxWall, v);
        }
        if (maxTick >= SPIKE_FACTOR * medianWindow)
        {
            failures.add("max single colony tick " + maxTick + " ns (tick +" + maxAt + ", " + (maxAt >= 0 ? buildingTicksAt[maxAt] : 0)
                           + " building ticks) >= " + SPIKE_FACTOR + "x the median 20-tick window " + medianWindow + " ns");
        }

        // Spread: no tick runs more than ceil(B / 25) building ticks.
        final int b = all.size();
        final int perSlot = (b + 24) / 25;
        int maxBuildingTicks = 0;
        int busiestAt = -1;
        for (int t = 0; t < buildingTicksAt.length; t++)
        {
            if (buildingTicksAt[t] > maxBuildingTicks)
            {
                maxBuildingTicks = buildingTicksAt[t];
                busiestAt = t;
            }
        }
        if (maxBuildingTicks > perSlot)
        {
            failures.add(maxBuildingTicks + " building colony ticks in one server tick (+" + busiestAt + "), want at most ceil(" + b + "/25) = " + perSlot);
        }

        // Cadence: every building ticks exactly once per slow colony cycle. The colony state machine runs its 500-tick
        // transitions 500 of ITS ticks apart, and it skips the rest of a tick whenever the state update (every 100
        // ticks) fires, so a cycle is 500 server ticks plus those skips: 505 today. Allow up to 5% on top of 500.
        final long end = start + cpu.length;
        int cadenceBad = 0;
        final List<String> cadence = new ArrayList<>();
        final java.util.TreeSet<Long> gaps = new java.util.TreeSet<>();
        for (final IBuilding building : all)
        {
            final List<Long> ticks = ticksOf.getOrDefault(building, List.of());
            boolean ok = !ticks.isEmpty() && ticks.get(0) - start < CYCLE_MAX && end - ticks.get(ticks.size() - 1) <= CYCLE_MAX;
            for (int i = 1; i < ticks.size(); i++)
            {
                final long gap = ticks.get(i) - ticks.get(i - 1);
                gaps.add(gap);
                ok &= gap >= CYCLE && gap <= CYCLE_MAX;
            }
            if (!ok)
            {
                cadenceBad++;
                if (cadence.size() < 6)
                {
                    cadence.add(building.getBuildingType().getRegistryName().getPath() + "@" + building.getID().toShortString() + " ticks at "
                                  + ticks.stream().map(v -> v - start).toList());
                }
            }
        }
        if (cadenceBad > 0)
        {
            failures.add(cadenceBad + " of " + b + " buildings did not tick exactly once per " + CYCLE + ".." + CYCLE_MAX + " ticks: " + cadence);
        }

        final long[] sortedWindows = windows.clone();
        Arrays.sort(sortedWindows);
        Log.getLogger().info("CA5 spread: B=" + b + " recorded " + cpu.length + " ticks (" + (cpuTime ? "thread CPU" : "wall") + " ns, " + gcTicks
                               + " GC ticks left out): max tick " + maxTick + " at +" + maxAt + " (" + (maxAt >= 0 ? buildingTicksAt[maxAt] : 0)
                               + " building ticks), median tick " + medianTick + ", median 20-tick window " + medianWindow + ", window p90 "
                               + sortedWindows[sortedWindows.length * 9 / 10] + ", max window " + sortedWindows[sortedWindows.length - 1]
                               + ", max wall tick " + maxWall + ", max/median window " + String.format("%.2f", (double) maxTick / Math.max(1, medianWindow))
                               + "x; max building ticks per server tick " + maxBuildingTicks + " (limit " + perSlot
                               + "); cadence bad " + cadenceBad + ", building tick gaps " + gaps);
        final Integer[] order = new Integer[cpu.length];
        for (int t = 0; t < order.length; t++)
        {
            order[t] = t;
        }
        Arrays.sort(order, (x, y) -> Long.compare(cpu[y], cpu[x]));
        final List<String> top = new ArrayList<>();
        for (int i = 0; i < 5; i++)
        {
            final int t = order[i];
            top.add("+" + t + " " + cpu[t] + "ns gc=" + gc[t] + " " + costsAt.get(t).stream()
              .map(e -> e.getKey().getBuildingType().getRegistryName().getPath() + "@" + e.getKey().getID().toShortString() + "=" + e.getValue()).toList());
        }
        Log.getLogger().info("CA5 spread top ticks (recording starts at game time " + start + "): " + top);
        helper.assertTrue(failures.isEmpty(), String.join(" | ", failures));
        helper.succeed();
    }

    // ------------------------------------------------------------------ colony_tick_spread_prestige

    /**
     * The prestige round analyses the blueprint off the server thread now; the building still ends up with exactly the
     * score the old on-thread {@code calculatePrestige} computes.
     */
    public static void colonyTickSpreadPrestige(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        pinClock(level);
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "CA5 prestige");
        noMoveIn(colony);
        final IBuilding builder = MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockHutBuilder,
          new BlockPos(8, 1, 2), "fundamentals/builder1.blueprint");
        final com.ldtteam.structurize.blueprints.v1.Blueprint blueprint =
          com.ldtteam.structurize.storage.StructurePacks.getBlueprint(builder.getStructurePack(), builder.getBlueprintPath());
        helper.assertTrue(blueprint != null, "no blueprint for " + builder.getStructurePack() + " " + builder.getBlueprintPath());
        final int expected = com.minecolonies.core.util.SchemAnalyzerUtil.analyzeSchematic(blueprint, level.registryAccess()).costScore;
        helper.assertTrue(expected > 0 && builder.getPrestige() != expected, "fixture: expected score " + expected + ", prestige already " + builder.getPrestige());
        builder.asyncPrestigeRecalc();
        final long start = level.getGameTime();
        final Runnable[] pump = new Runnable[1];
        pump[0] = () -> {
            builder.onColonyTick(colony);
            if (builder.getPrestige() == expected)
            {
                Log.getLogger().info("CA5 prestige: score " + expected + " applied after " + (level.getGameTime() - start) + " ticks");
                helper.succeed();
                return;
            }
            if (level.getGameTime() - start > 400)
            {
                throw helper.assertionException("prestige " + builder.getPrestige() + " never became " + expected + " within 400 ticks");
            }
            helper.runAfterDelay(1, () -> pump[0].run());
        };
        pump[0].run();
    }

    // ------------------------------------------------------------------ colony_tick_spread_min_stock

    /**
     * Min-stock requests are created, sized and cancelled exactly as the per-entry building count says, for a
     * warehouse (counted from its rack index) and a builder (one-shot index): stocked entries make no request, a short
     * entry asks for the shortfall (capped at a stack), a missing entry asks for a stack, a request whose entry became
     * stocked is cancelled, and a second tick does not duplicate open requests.
     */
    public static void colonyTickSpreadMinStock(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        pinClock(level);
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "CA5 min stock");
        noMoveIn(colony);
        forcePad(helper);
        final IBuilding warehouse = MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockHutWareHouse,
          new BlockPos(16, 1, 2), "craftsmanship/storage/warehouse1.blueprint");
        final IBuilding builder = MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockHutBuilder,
          new BlockPos(8, 1, 2), "fundamentals/builder1.blueprint");

        final List<String> failures = new ArrayList<>();
        final List<String> report = new ArrayList<>();
        int z = 10;
        for (final IBuilding building : List.of(warehouse, builder))
        {
            final String name = building.getBuildingType().getRegistryName().getPath();
            final TileEntityRack r1 = placeRack(helper, building, new BlockPos(-6, 1, z));
            final TileEntityRack r2 = placeRack(helper, building, new BlockPos(-4, 1, z));
            placeRack(helper, building, new BlockPos(-2, 1, z));
            z += 4;
            final MinimumStockModule stock = building.getFirstModuleOccurance(MinimumStockModule.class);
            helper.assertTrue(stock != null, name + " has no min-stock module");
            // stocked (64 of 64 over two racks), short (20 of 64), missing, later-stocked (missing now), over-stocked (2 of 1 stack).
            stock.addMinimumStock(new ItemStack(Items.STONE), 1);
            stock.addMinimumStock(new ItemStack(Items.COBBLESTONE), 1);
            stock.addMinimumStock(new ItemStack(Items.GRANITE), 1);
            stock.addMinimumStock(new ItemStack(Items.DIORITE), 1);
            stock.addMinimumStock(new ItemStack(Items.ANDESITE), 1);
            r1.getInventory().setStackInSlot(0, new ItemStack(Items.STONE, 40));
            r2.getInventory().setStackInSlot(0, new ItemStack(Items.STONE, 24));
            r2.getInventory().setStackInSlot(1, new ItemStack(Items.COBBLESTONE, 20));
            r1.getInventory().setStackInSlot(1, new ItemStack(Items.ANDESITE, 64));
            r2.getInventory().setStackInSlot(2, new ItemStack(Items.ANDESITE, 64));

            // Old algorithm, inline: what each entry counts.
            final Map<Item, Integer> refCount = new HashMap<>();
            for (final Item item : List.of(Items.STONE, Items.COBBLESTONE, Items.GRANITE, Items.DIORITE, Items.ANDESITE))
            {
                refCount.put(item, InventoryUtils.hasBuildingEnoughElseCount(building, new ItemStorage(new ItemStack(item), true), 64));
            }
            final List<Integer> want = List.of(64, 20, 0, 0, 64);
            if (!refCount.get(Items.STONE).equals(want.get(0)) || !refCount.get(Items.COBBLESTONE).equals(want.get(1))
                  || refCount.get(Items.GRANITE) != 0 || refCount.get(Items.DIORITE) != 0 || refCount.get(Items.ANDESITE) < 64)
            {
                failures.add(name + " fixture counts " + refCount);
            }

            stock.onColonyTick(colony);
            Map<Item, List<MinimumStack>> open = openMinStock(colony, building);
            report.add(name + " tick1 " + summary(open));
            expect(failures, name + " tick1", open, Map.of(Items.COBBLESTONE, 44, Items.GRANITE, 64, Items.DIORITE, 64));
            final IToken<?> dioriteToken = tokenFor(colony, building, Items.DIORITE);

            // Diorite now stocked: its request is cancelled; a second tick leaves the other two alone.
            r1.getInventory().setStackInSlot(2, new ItemStack(Items.DIORITE, 64));
            stock.onColonyTick(colony);
            open = openMinStock(colony, building);
            report.add(name + " tick2 " + summary(open));
            expect(failures, name + " tick2", open, Map.of(Items.COBBLESTONE, 44, Items.GRANITE, 64));
            if (dioriteToken != null)
            {
                final IRequest<?> request = colony.getRequestManager().getRequestForToken(dioriteToken);
                if (request != null && request.getState() != RequestState.CANCELLED && request.getState() != RequestState.COMPLETED
                      && request.getState() != RequestState.RECEIVED)
                {
                    failures.add(name + " diorite request still " + request.getState());
                }
            }
        }
        Log.getLogger().info("CA5 min stock: " + report + (failures.isEmpty() ? "" : " FAIL " + failures));
        helper.assertTrue(failures.isEmpty(), String.join(" | ", failures));
        helper.succeed();
    }

    private static Map<Item, List<MinimumStack>> openMinStock(final IColony colony, final IBuilding building)
    {
        final Map<Item, List<MinimumStack>> open = new HashMap<>();
        final Collection<IToken<?>> tokens = building.getOpenRequestsByRequestableType().getOrDefault(TypeToken.of(MinimumStack.class), List.of());
        for (final IToken<?> token : tokens)
        {
            final IRequest<?> request = colony.getRequestManager().getRequestForToken(token);
            if (request != null && request.getRequest() instanceof final MinimumStack stack && request.getState() != RequestState.CANCELLED)
            {
                open.computeIfAbsent(stack.getStack().getItem(), k -> new ArrayList<>()).add(stack);
            }
        }
        return open;
    }

    private static IToken<?> tokenFor(final IColony colony, final IBuilding building, final Item item)
    {
        final Collection<IToken<?>> tokens = building.getOpenRequestsByRequestableType().getOrDefault(TypeToken.of(MinimumStack.class), List.of());
        for (final IToken<?> token : tokens)
        {
            final IRequest<?> request = colony.getRequestManager().getRequestForToken(token);
            if (request != null && request.getRequest() instanceof final MinimumStack stack
                  && ItemStackUtils.compareItemStacksIgnoreStackSize(stack.getStack(), new ItemStack(item)))
            {
                return token;
            }
        }
        return null;
    }

    private static String summary(final Map<Item, List<MinimumStack>> open)
    {
        final List<String> parts = new ArrayList<>();
        open.forEach((item, list) -> parts.add(item + "=" + list.stream().map(MinimumStack::getCount).toList()));
        parts.sort(String::compareTo);
        return parts.toString();
    }

    private static void expect(final List<String> failures, final String what, final Map<Item, List<MinimumStack>> open, final Map<Item, Integer> want)
    {
        for (final Map.Entry<Item, Integer> entry : want.entrySet())
        {
            final List<MinimumStack> list = open.getOrDefault(entry.getKey(), List.of());
            if (list.size() != 1 || list.get(0).getCount() != entry.getValue())
            {
                failures.add(what + ": " + entry.getKey() + " want one request of " + entry.getValue() + ", have " + list.stream().map(MinimumStack::getCount).toList());
            }
        }
        for (final Item item : open.keySet())
        {
            if (!want.containsKey(item))
            {
                failures.add(what + ": unexpected request for " + item + " " + open.get(item).stream().map(MinimumStack::getCount).toList());
            }
        }
    }
}
