package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.compatibility.Compatibility;
import com.minecolonies.api.compatibility.treechop.TreeChopProxy;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.items.ModTags;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.ItemListModule;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingLumberjack;
import com.minecolonies.core.colony.jobs.JobLumberjack;
import com.minecolonies.core.entity.ai.workers.production.EntityAIWorkLumberjack;
import com.minecolonies.core.entity.ai.workers.util.Tree;
import com.minecolonies.core.entity.ai.workers.util.TreeFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Bees;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Forestry (round 3, the lumberjack and TreeChop design): the Forester removes whole natural trees and nothing else. Group
 * {@code MINECOLONIES_GAMETEST_ONLY=forestry} (vanilla trees, no extra mod) and {@code forestry_treechop} (needs TreeChop on the runtime classpath,
 * {@code MC_GT_LANE=treechop} in {@code toolchain/mc/gametest.sh}). Expanded Ecosphere trees are covered by the tag and synthetic-shape test, as its
 * jar is not in the test closure.
 */
public final class ForestryGameTests
{
    private ForestryGameTests()
    {
    }

    /**
     * Where the hut stands on the colony plot, and the tree search cap of the felling tests.
     */
    private static final BlockPos HUT       = new BlockPos(8, 1, 2);
    private static final int      FELL_CAP  = 36_000;

    static final Map<String, Consumer<GameTestHelper>> TESTS          = new LinkedHashMap<>();
    static final Map<String, Consumer<GameTestHelper>> TREECHOP_TESTS = new LinkedHashMap<>();
    static
    {
        TESTS.put("forestry_footprint_data", ForestryGameTests::footprintData);
        TESTS.put("forestry_whole_broadleaf", ForestryGameTests::wholeBroadleaf);
        TESTS.put("forestry_whole_conifer_jungle", ForestryGameTests::wholeConiferJungle);
        TESTS.put("forestry_whole_dark_pale", ForestryGameTests::wholeDarkPale);
        TESTS.put("forestry_whole_mangrove", ForestryGameTests::wholeMangrove);
        TESTS.put("forestry_whole_mushroom", ForestryGameTests::wholeMushroom);
        TESTS.put("forestry_sweep_neighbours_sapling", ForestryGameTests::sweepNeighboursSapling);
        TESTS.put("forestry_cabin_skip", ForestryGameTests::cabinSkip);
        TESTS.put("forestry_bee_nest", ForestryGameTests::beeNest);
        TESTS.put("forestry_drops_overflow", ForestryGameTests::dropsOverflow);
        TESTS.put("forestry_two_lumberjacks", ForestryGameTests::twoLumberjacks);
        TESTS.put("forestry_idle_planting_ring", ForestryGameTests::idlePlantingRing);

        TREECHOP_TESTS.put("forestry_treechop_fell", ForestryGameTests::treeChopFell);
        TREECHOP_TESTS.put("forestry_treechop_half_chopped", ForestryGameTests::treeChopHalfChopped);
        TREECHOP_TESTS.put("forestry_treechop_disabled_fallback", ForestryGameTests::treeChopDisabledFallback);
        TREECHOP_TESTS.put("forestry_treechop_setting_off", ForestryGameTests::treeChopSettingOff);
    }

    static void register(final RegisterGameTestsEvent event)
    {
        register(event, TESTS);
    }

    static void registerTreeChop(final RegisterGameTestsEvent event)
    {
        if (!Compatibility.treeChop.isPresent())
        {
            // The lane is misconfigured: say so instead of passing with no test.
            final Map<String, Consumer<GameTestHelper>> missing = new LinkedHashMap<>();
            missing.put("forestry_treechop_missing", helper -> {
                throw helper.assertionException("TreeChop is not loaded: run the lane with MC_GT_LANE=treechop");
            });
            register(event, missing);
            return;
        }
        register(event, TREECHOP_TESTS);
    }

    private static void register(final RegisterGameTestsEvent event, final Map<String, Consumer<GameTestHelper>> tests)
    {
        for (final Map.Entry<String, Consumer<GameTestHelper>> entry : tests.entrySet())
        {
            final Consumer<GameTestHelper> test = entry.getValue();
            event.registerTest(
              Identifier.fromNamespaceAndPath(Constants.MOD_ID, entry.getKey()),
              info -> new MinecoloniesGameTestInstance(info, test),
              MinecoloniesGameTestRegistrar.isolatedColonyData(event, entry.getKey()));
        }
    }

    // ---------------------------------------------------------------- fixture

    /**
     * One tree of a test: how to grow it, where, and what must hold afterwards.
     */
    private record TreeSpec(String name, BlockPos rel, boolean twoByTwo, Block sapling, Block ground, TreeGrower grower, Predicate<BlockState> replant)
    {
    }

    /**
     * A grown tree: its stumps and the box around it.
     */
    private record Grown(TreeSpec spec, List<BlockPos> stumps, AABB box, int logs)
    {
    }

    /**
     * No random ticks in the forestry tests: leaves never decay by themselves (so a clean canopy proves the sweep) and replanted saplings never grow
     * back into a tree before the test is done.
     */
    private static void noRandomTicks(final GameTestHelper helper)
    {
        helper.getLevel().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED, 0, helper.getLevel().getServer());
    }

    private static BuildingLumberjack hut(final GameTestHelper helper, final IColony colony, final BlockPos rel, final boolean plantForest)
    {
        noRandomTicks(helper);
        final IBuilding building = MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockHutLumberjack, rel, "fundamentals/lumberjack1.blueprint");
        helper.assertTrue(building instanceof BuildingLumberjack, "the lumberjack hut is not a BuildingLumberjack: " + building);
        final BuildingLumberjack lumberjack = (BuildingLumberjack) building;
        final BoolSetting plant = lumberjack.getSetting(BuildingLumberjack.PLANT_FOREST);
        if (plant.getValue() != plantForest)
        {
            plant.trigger();
        }
        return lumberjack;
    }

    private static ICitizenData forester(final GameTestHelper helper, final IColony colony, final BuildingLumberjack building, final BlockPos rel, final ItemStack... extra)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos anchor = helper.absolutePos(rel);
        final ICitizenData worker = colony.getCitizenManager().spawnOrCreateCivilian(null, level, List.of(anchor), true);
        helper.assertTrue(worker != null && worker.getEntity().isPresent(), "forester did not spawn");
        helper.assertTrue(building.getModule(BuildingModules.FORESTER_WORK).assignCitizen(worker), "forester assignment failed");
        worker.getEntity().get().setPos(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D);
        worker.getInventory().setStackInSlot(0, new ItemStack(Items.STONE_AXE));
        worker.getInventory().setStackInSlot(1, new ItemStack(Items.STONE_HOE));
        worker.getInventory().setStackInSlot(2, new ItemStack(Items.SHEARS));
        for (int i = 0; i < extra.length; i++)
        {
            worker.getInventory().setStackInSlot(3 + i, extra[i]);
        }
        return worker;
    }

    private static EntityAIWorkLumberjack ai(final ICitizenData worker)
    {
        return worker.getEntity().map(e -> (Object) e.getCitizenJobHandler().getWorkAI()).filter(EntityAIWorkLumberjack.class::isInstance)
          .map(EntityAIWorkLumberjack.class::cast).orElse(null);
    }

    private static Tree tree(final ICitizenData worker)
    {
        return worker.getJob() instanceof JobLumberjack job ? job.getTree() : null;
    }

    private static String describe(final ICitizenData worker)
    {
        final EntityAIWorkLumberjack ai = ai(worker);
        final Tree tree = tree(worker);
        final StringBuilder inventory = new StringBuilder();
        for (int i = 0; i < worker.getInventory().getSlots(); i++)
        {
            final ItemStack stack = worker.getInventory().getStackInSlot(i);
            if (!stack.isEmpty())
            {
                inventory.append(stack.getCount()).append('x').append(stack.getItem()).append(' ');
            }
        }
        return "ai " + (ai == null ? "none" : ai.getState() + " history " + ai.getStateAI().getHistory().getString())
                 + ", tree " + (tree == null ? "none" : tree.getLocation() + " logs left " + tree.getLogsLeft() + " skip " + tree.getSkip() + " sapling " + tree.getSapling()
                 + " stumps " + tree.getStumpLocations())
                 + ", at " + worker.getEntity().map(e -> e.blockPosition().toShortString()).orElse("?") + ", inventory " + inventory;
    }

    /**
     * Grow one tree from its sapling(s) with vanilla's tree grower and a fixed seed.
     */
    private static Grown grow(final GameTestHelper helper, final TreeSpec spec, final long seed)
    {
        final ServerLevel level = helper.getLevel();
        final List<BlockPos> stumps = new ArrayList<>();
        final List<BlockPos> rels = spec.twoByTwo()
          ? List.of(spec.rel(), spec.rel().east(), spec.rel().south(), spec.rel().south().east())
          : List.of(spec.rel());
        final BlockPos base = helper.absolutePos(spec.rel());
        for (int dx = -3; dx <= 4; dx++)
        {
            for (int dz = -3; dz <= 4; dz++)
            {
                level.setBlockAndUpdate(base.offset(dx, -1, dz), spec.ground().defaultBlockState());
            }
        }
        for (final BlockPos rel : rels)
        {
            final BlockPos pos = helper.absolutePos(rel);
            level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
            level.setBlockAndUpdate(pos, spec.sapling().defaultBlockState());
            stumps.add(pos);
        }
        final RandomSource random = RandomSource.create(seed);
        final boolean grown;
        if (spec.grower() != null)
        {
            grown = spec.grower().growTree(level, level.getChunkSource().getGenerator(), base, level.getBlockState(base), random);
        }
        else
        {
            grown = ((MushroomBlock) spec.sapling()).growMushroom(level, base, level.getBlockState(base), random);
        }
        helper.assertTrue(grown, spec.name() + " did not grow");
        final int half = spec.twoByTwo() ? 11 : spec.sapling() == Blocks.MANGROVE_PROPAGULE ? 12 : 8;
        final AABB box = new AABB(base.getX() - half, base.getY() - 6, base.getZ() - half, base.getX() + half + 1, base.getY() + 36, base.getZ() + half + 1);
        final int logs = count(level, box, state -> state.is(BlockTags.LOGS));
        helper.assertTrue(count(level, box, TreeFootprint::isTreeBlock) > 0, spec.name() + " has no trunk");
        return new Grown(spec, stumps, box, logs);
    }

    private static int count(final ServerLevel level, final AABB box, final Predicate<BlockState> what)
    {
        int found = 0;
        for (final BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1)))
        {
            if (what.test(level.getBlockState(pos)))
            {
                found++;
            }
        }
        return found;
    }

    /**
     * Leaves vanilla would decay or caps: what must be gone after a felling.
     */
    private static boolean isTreeLeaf(final BlockState state)
    {
        return (TreeFootprint.isDecayingLeaves(state) && !state.getValue(LeavesBlock.PERSISTENT)) || TreeFootprint.isCap(state);
    }

    private static int items(final ServerLevel level, final AABB box)
    {
        return level.getEntitiesOfClass(ItemEntity.class, box).size();
    }

    private static int logsKept(final ICitizenData worker, final IBuilding building)
    {
        final Predicate<ItemStack> log = stack -> stack.is(ItemTags.LOGS);
        return InventoryUtils.getItemCountInItemHandler(worker.getInventory(), log) + InventoryUtils.getCountFromBuilding(building, log);
    }

    /**
     * Pump the fixture every tick (daylight, full belly) until the check holds (asked every 20 ticks) or the cap is hit.
     */
    private static void pump(
      final GameTestHelper helper,
      final List<ICitizenData> workers,
      final int cap,
      final BooleanSupplier done,
      final Supplier<String> failure,
      final Runnable everyTick,
      final String name)
    {
        final ServerLevel level = helper.getLevel();
        final int[] ticks = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
            for (final ICitizenData worker : workers)
            {
                worker.setSaturation(20);
            }
            ticks[0]++;
            if (everyTick != null)
            {
                everyTick.run();
            }
            if (ticks[0] % 20 == 0 && done.getAsBoolean())
            {
                Log.getLogger().info("[{}] passed in {} ticks", name, ticks[0]);
                helper.succeed();
                return;
            }
            if (ticks[0] >= cap)
            {
                throw helper.assertionException(name + ": " + failure.get() + " (after " + cap + " ticks)");
            }
            // A fresh Runnable each time: the GameTest callback map is keyed by Runnable.
            helper.runAfterDelay(1, () -> step[0].run());
        };
        helper.runAfterDelay(1, () -> step[0].run());
    }

    // ---------------------------------------------------------------- whole trees

    private static void wholeBroadleaf(final GameTestHelper helper)
    {
        wholeTrees(helper, "forestry_whole_broadleaf", List.of(
          new TreeSpec("oak", new BlockPos(20, 1, 6), false, Blocks.OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.OAK, s -> s.is(Blocks.OAK_SAPLING)),
          new TreeSpec("birch", new BlockPos(36, 1, 6), false, Blocks.BIRCH_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.BIRCH, s -> s.is(Blocks.BIRCH_SAPLING)),
          new TreeSpec("cherry", new BlockPos(20, 1, 24), false, Blocks.CHERRY_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.CHERRY, s -> s.is(Blocks.CHERRY_SAPLING)),
          new TreeSpec("acacia", new BlockPos(36, 1, 24), false, Blocks.ACACIA_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.ACACIA, s -> s.is(Blocks.ACACIA_SAPLING)),
          new TreeSpec("azalea", new BlockPos(3, 1, 24), false, Blocks.AZALEA, Blocks.GRASS_BLOCK, TreeGrower.AZALEA, null)),
          List.of(new ItemStack(Items.OAK_SAPLING, 4), new ItemStack(Items.BIRCH_SAPLING, 4), new ItemStack(Items.CHERRY_SAPLING, 4), new ItemStack(Items.ACACIA_SAPLING, 4)),
          null, true);
    }

    private static void wholeConiferJungle(final GameTestHelper helper)
    {
        wholeTrees(helper, "forestry_whole_conifer_jungle", List.of(
          new TreeSpec("spruce", new BlockPos(20, 1, 6), false, Blocks.SPRUCE_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.SPRUCE, s -> s.is(Blocks.SPRUCE_SAPLING)),
          new TreeSpec("mega spruce", new BlockPos(36, 1, 6), true, Blocks.SPRUCE_SAPLING, Blocks.PODZOL, TreeGrower.SPRUCE, s -> s.is(Blocks.SPRUCE_SAPLING)),
          new TreeSpec("mega jungle", new BlockPos(26, 1, 26), true, Blocks.JUNGLE_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.JUNGLE, s -> s.is(Blocks.JUNGLE_SAPLING))),
          List.of(new ItemStack(Items.SPRUCE_SAPLING, 8), new ItemStack(Items.JUNGLE_SAPLING, 4)),
          null, true);
    }

    private static void wholeDarkPale(final GameTestHelper helper)
    {
        wholeTrees(helper, "forestry_whole_dark_pale", List.of(
          new TreeSpec("dark oak", new BlockPos(18, 1, 8), true, Blocks.DARK_OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.DARK_OAK, s -> s.is(Blocks.DARK_OAK_SAPLING)),
          new TreeSpec("pale oak", new BlockPos(34, 1, 24), true, Blocks.PALE_OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.PALE_OAK, s -> s.is(Blocks.PALE_OAK_SAPLING))),
          List.of(new ItemStack(Items.DARK_OAK_SAPLING, 4), new ItemStack(Items.PALE_OAK_SAPLING, 4)),
          grown -> {
              // A creaking heart between two pale oak logs of the trunk goes with the tree.
              final Grown pale = grown.get(1);
              final BlockPos heart = pale.stumps().get(0).above(2);
              helper.getLevel().setBlockAndUpdate(heart, Blocks.CREAKING_HEART.defaultBlockState());
              helper.assertTrue(helper.getLevel().getBlockState(heart.above()).is(Blocks.PALE_OAK_LOG), "no pale oak log above the creaking heart");
          }, true);
    }

    private static void wholeMangrove(final GameTestHelper helper)
    {
        wholeTrees(helper, "forestry_whole_mangrove", List.of(
          new TreeSpec("mangrove", new BlockPos(24, 1, 18), false, Blocks.MANGROVE_PROPAGULE, Blocks.MUD, TreeGrower.MANGROVE, null)),
          List.of(new ItemStack(Items.MANGROVE_PROPAGULE, 4)), null, true);
    }

    private static void wholeMushroom(final GameTestHelper helper)
    {
        wholeTrees(helper, "forestry_whole_mushroom", List.of(
          new TreeSpec("huge red mushroom", new BlockPos(20, 1, 8), false, Blocks.RED_MUSHROOM, Blocks.MYCELIUM, null, null),
          new TreeSpec("huge brown mushroom", new BlockPos(34, 1, 24), false, Blocks.BROWN_MUSHROOM, Blocks.MYCELIUM, null, null)),
          List.of(), null, false);
    }

    /**
     * Grows the trees, lets one Forester (planting off) fell them, and checks each: no tree block left in its box (logs, wood, roots, creaking heart),
     * no leaves vanilla would decay and no caps, no item left on the ground, the stumps replanted with the tree's own sapling (where the tree type
     * replants on its ground), and at least 95 percent of the logs kept.
     */
    private static void wholeTrees(
      final GameTestHelper helper,
      final String name,
      final List<TreeSpec> specs,
      final List<ItemStack> saplings,
      final Consumer<List<Grown>> afterGrow,
      final boolean checkLogs)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, name);
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        final List<Grown> grown = new ArrayList<>();
        long seed = 11L;
        for (final TreeSpec spec : specs)
        {
            grown.add(grow(helper, spec, seed++));
        }
        if (afterGrow != null)
        {
            afterGrow.accept(grown);
        }
        final int totalLogs = grown.stream().mapToInt(Grown::logs).sum();

        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(8, 1, 10), saplings.toArray(new ItemStack[0]));
            final boolean[] felled = new boolean[grown.size()];
            final boolean[] clean = new boolean[grown.size()];
            final boolean[] replanted = new boolean[grown.size()];
            final String[] last = new String[grown.size()];
            pump(helper, List.of(worker), FELL_CAP, () -> {
                boolean all = true;
                for (int i = 0; i < grown.size(); i++)
                {
                    final Grown tree = grown.get(i);
                    final int left = count(level, tree.box(), TreeFootprint::isTreeBlock);
                    final int leaves = count(level, tree.box(), ForestryGameTests::isTreeLeaf);
                    final int dropped = items(level, tree.box());
                    felled[i] |= left == 0;
                    clean[i] |= felled[i] && leaves == 0 && dropped == 0;
                    replanted[i] |= tree.spec().replant() == null || tree.stumps().stream().allMatch(pos -> tree.spec().replant().test(level.getBlockState(pos)));
                    last[i] = tree.spec().name() + ": tree blocks " + left + ", leaves " + leaves + ", items " + dropped
                                + ", stumps " + tree.stumps().stream().map(pos -> level.getBlockState(pos).getBlock().toString()).toList();
                    all &= felled[i] && clean[i] && replanted[i];
                }
                return all && (!checkLogs || logsKept(worker, building) * 100 >= totalLogs * 95);
            }, () -> "felled " + Arrays.toString(felled) + " clean " + Arrays.toString(clean) + " replanted " + Arrays.toString(replanted)
                       + ", logs kept " + logsKept(worker, building) + "/" + totalLogs + ", " + String.join("; ", Arrays.asList(last)) + ", " + describe(worker),
              null, name);
        });
    }

    // ---------------------------------------------------------------- tags and synthetic shapes

    /**
     * Tags and the footprint on shapes no vanilla sapling grows: the Expanded Ecosphere giants (a 3x3 trunk 40 high with long branch arms) and banyans
     * (mangrove wood and roots over mud) are one tree each; bamboo next to jungle leaves is no tree; a tree over the budget is left whole.
     */
    private static void footprintData(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final List<String> failures = new ArrayList<>();
        final BiCheck check = (ok, what) -> {
            if (!ok)
            {
                failures.add(what);
            }
        };

        check.test(Blocks.BAMBOO.defaultBlockState().is(ModTags.treeExcluded) && !TreeFootprint.isTreeBlock(Blocks.BAMBOO.defaultBlockState()), "bamboo is a tree block");
        for (final Block block : List.of(Blocks.MANGROVE_LOG, Blocks.MANGROVE_WOOD, Blocks.MANGROVE_ROOTS, Blocks.MUDDY_MANGROVE_ROOTS))
        {
            check.test(TreeFootprint.isTreeBlock(block.defaultBlockState()), block + " is no tree block");
        }
        check.test(TreeFootprint.isTreeBlock(Blocks.CREAKING_HEART.defaultBlockState()), "the creaking heart is no tree block");
        final BlockPos probe = helper.absolutePos(new BlockPos(0, 1, 0));
        final java.util.function.Function<Block, String> fam = block -> TreeFootprint.family(level, probe, block.defaultBlockState(), true);
        check.test(fam.apply(Blocks.MANGROVE_WOOD).equals(fam.apply(Blocks.MUDDY_MANGROVE_ROOTS)), "mangrove wood and muddy roots are two families");
        check.test(fam.apply(Blocks.PALE_OAK_LOG).equals(fam.apply(Blocks.CREAKING_HEART)), "pale oak and the creaking heart are two families");
        check.test(fam.apply(Blocks.OAK_LOG).equals(fam.apply(Blocks.STRIPPED_OAK_WOOD)), "oak log and stripped oak wood are two families");
        check.test(!fam.apply(Blocks.OAK_LOG).equals(fam.apply(Blocks.BIRCH_LOG)), "oak and birch are one family");
        check.test(!fam.apply(Blocks.OAK_LOG).equals(fam.apply(Blocks.DARK_OAK_LOG)), "oak and dark oak are one family");
        check.test(new ItemStack(Items.DARK_OAK_SAPLING).is(ModTags.fourSaplingTrees) && new ItemStack(Items.PALE_OAK_SAPLING).is(ModTags.fourSaplingTrees)
                     && !new ItemStack(Items.SPRUCE_SAPLING).is(ModTags.fourSaplingTrees), "four sapling tag wrong");

        // Synthetic giant: a 3x3 spruce trunk 40 high, two branch arms 12 out at heights 25 and 32, leaves on the arms' ends.
        final BlockPos giant = helper.absolutePos(new BlockPos(14, 1, 12));
        for (int dx = -2; dx <= 2; dx++)
        {
            for (int dz = -2; dz <= 2; dz++)
            {
                level.setBlockAndUpdate(giant.offset(dx, -1, dz), Blocks.PODZOL.defaultBlockState());
            }
        }
        int giantLogs = 0;
        for (int y = 0; y < 40; y++)
        {
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    level.setBlockAndUpdate(giant.offset(dx, y, dz), Blocks.SPRUCE_LOG.defaultBlockState());
                    giantLogs++;
                }
            }
        }
        for (int i = 2; i <= 12; i++)
        {
            level.setBlockAndUpdate(giant.offset(i, 25, 0), Blocks.SPRUCE_WOOD.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
            level.setBlockAndUpdate(giant.offset(0, 32, -i), Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z));
            giantLogs += 2;
        }
        for (final BlockPos tip : List.of(giant.offset(12, 26, 0), giant.offset(0, 33, -12), giant.offset(0, 40, 0)))
        {
            for (final Direction dir : Direction.values())
            {
                if (level.getBlockState(tip.relative(dir)).isAir())
                {
                    level.setBlockAndUpdate(tip.relative(dir), Blocks.SPRUCE_LEAVES.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(tip, Blocks.SPRUCE_LEAVES.defaultBlockState());
        }
        final TreeFootprint.Footprint giantPrint = TreeFootprint.scan(level, giant.above(3), null, MineColonies.getConfig().getServer().maxTreeSize.get());
        check.test(giantPrint != null && giantPrint.skip == TreeFootprint.Skip.NONE && giantPrint.logs.size() == giantLogs && giantPrint.baseLayer.size() == 9,
          "giant: " + (giantPrint == null ? "no footprint" : giantPrint.logs.size() + "/" + giantLogs + " logs, base " + giantPrint.baseLayer.size() + ", skip "
                                                              + giantPrint.skip + " " + giantPrint.skipDetail));
        check.test(giantPrint != null && giantPrint.base.getY() == giant.getY(), "giant base not at the bottom: " + (giantPrint == null ? null : giantPrint.base));

        // Banyan-like: a mangrove wood trunk on roots and muddy roots over mud, mangrove leaves: one tree.
        final BlockPos banyan = helper.absolutePos(new BlockPos(30, 1, 22));
        for (int dx = -3; dx <= 3; dx++)
        {
            for (int dz = -3; dz <= 3; dz++)
            {
                level.setBlockAndUpdate(banyan.offset(dx, -1, dz), Blocks.MUD.defaultBlockState());
            }
        }
        int banyanBlocks = 0;
        for (final BlockPos root : List.of(banyan.offset(-2, 0, 0), banyan.offset(2, 0, 0), banyan.offset(0, 0, -2), banyan.offset(0, 0, 2)))
        {
            level.setBlockAndUpdate(root, Blocks.MUDDY_MANGROVE_ROOTS.defaultBlockState());
            final BlockPos up = root.above().offset(Integer.signum(banyan.getX() - root.getX()), 0, Integer.signum(banyan.getZ() - root.getZ()));
            level.setBlockAndUpdate(up, Blocks.MANGROVE_ROOTS.defaultBlockState());
            banyanBlocks += 2;
        }
        for (int y = 2; y < 10; y++)
        {
            level.setBlockAndUpdate(banyan.above(y), Blocks.MANGROVE_WOOD.defaultBlockState());
            banyanBlocks++;
        }
        for (final Direction dir : Direction.values())
        {
            final BlockPos leaf = banyan.above(9).relative(dir);
            if (level.getBlockState(leaf).isAir())
            {
                level.setBlockAndUpdate(leaf, Blocks.MANGROVE_LEAVES.defaultBlockState());
            }
        }
        final TreeFootprint.Footprint banyanPrint = TreeFootprint.scan(level, banyan.above(5), null, 1024);
        check.test(banyanPrint != null && banyanPrint.skip == TreeFootprint.Skip.NONE && banyanPrint.logs.size() == banyanBlocks,
          "banyan: " + (banyanPrint == null ? "no footprint" : banyanPrint.logs.size() + "/" + banyanBlocks + " blocks, skip " + banyanPrint.skip + " " + banyanPrint.skipDetail));

        // Bamboo beside jungle leaves is no tree (Expanded Ecosphere tags bamboo as a jungle log).
        final BlockPos bamboo = helper.absolutePos(new BlockPos(22, 1, 4));
        level.setBlockAndUpdate(bamboo.below(), Blocks.GRASS_BLOCK.defaultBlockState());
        for (int y = 0; y < 6; y++)
        {
            level.setBlockAndUpdate(bamboo.above(y), Blocks.BAMBOO.defaultBlockState());
            level.setBlockAndUpdate(bamboo.above(y).east(), Blocks.JUNGLE_LEAVES.defaultBlockState());
        }
        check.test(!Tree.checkTree(level, bamboo.above(1), List.of(), 0), "bamboo next to jungle leaves is a tree");
        check.test(TreeFootprint.scan(level, bamboo.above(1), null, 1024) == null, "bamboo has a footprint");

        // Over the budget: the giant with a budget of 300 is left whole.
        final TreeFootprint.Footprint small = TreeFootprint.scan(level, giant.above(3), null, 300);
        check.test(small != null && small.skip == TreeFootprint.Skip.TOO_BIG, "a tree over the budget is not skipped: " + (small == null ? null : small.skip));

        Log.getLogger().info("[forestry_footprint_data] failures {}", failures);
        helper.assertTrue(failures.isEmpty(), "forestry data: " + failures);
        helper.succeed();
    }

    @FunctionalInterface
    private interface BiCheck
    {
        void test(boolean ok, String what);
    }

    // ---------------------------------------------------------------- sweep, neighbours, sapling choice

    /**
     * A birch with its canopy in an oak the Forester must not cut, and a hedge of placed leaves under the birch's canopy. After the birch: the oak's
     * logs and every oak leaf stay, every placed leaf stays, no leaf vanilla would decay is left, and the stump gets a birch sapling although he also
     * carries oak saplings.
     */
    private static void sweepNeighboursSapling(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry sweep");
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        building.getModuleMatching(ItemListModule.class, m -> m.getId().equals(EntityAIWorkLumberjack.SAPLINGS_LIST)).addItem(new ItemStorage(new ItemStack(Items.OAK_SAPLING)));
        IColonyManager.getInstance().getCompatibilityManager().connectLeafToSapling(Blocks.BIRCH_LEAVES, new ItemStack(Items.BIRCH_SAPLING));
        IColonyManager.getInstance().getCompatibilityManager().connectLeafToSapling(Blocks.OAK_LEAVES, new ItemStack(Items.OAK_SAPLING));

        final Grown birch = grow(helper, new TreeSpec("birch", new BlockPos(20, 1, 16), false, Blocks.BIRCH_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.BIRCH, null), 21L);
        final Grown oak = grow(helper, new TreeSpec("oak", new BlockPos(24, 1, 16), false, Blocks.OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.OAK, null), 22L);
        final BlockPos birchBase = birch.stumps().get(0);
        // The hedge: placed (persistent) birch leaves three blocks west of the trunk, under and into the canopy, not touching a log.
        final List<BlockPos> hedge = new ArrayList<>();
        for (int y = 0; y <= 4; y++)
        {
            for (int dz = -2; dz <= 2; dz++)
            {
                final BlockPos pos = birchBase.offset(-3, y, dz);
                if (level.getBlockState(pos).isAir() || level.getBlockState(pos).is(BlockTags.LEAVES))
                {
                    level.setBlockAndUpdate(pos, Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
                    hedge.add(pos);
                }
            }
        }
        final AABB both = birch.box().minmax(oak.box());
        final Map<BlockPos, Block> oakBlocks = new HashMap<>();
        for (final BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(both.minX, both.minY, both.minZ), BlockPos.containing(both.maxX - 1, both.maxY - 1, both.maxZ - 1)))
        {
            final BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.OAK_LOG) || state.is(Blocks.OAK_LEAVES))
            {
                oakBlocks.put(pos.immutable(), state.getBlock());
            }
        }
        final boolean touching = oakBlocks.keySet().stream().anyMatch(pos -> pos.distManhattan(birchBase) <= 6 && level.getBlockState(pos).is(Blocks.OAK_LEAVES));
        Log.getLogger().info("[forestry_sweep_neighbours_sapling] oak canopy near the birch: {}", touching);

        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10),
              new ItemStack(Items.OAK_SAPLING, 8), new ItemStack(Items.BIRCH_SAPLING, 8));
            final String[] last = {""};
            pump(helper, List.of(worker), FELL_CAP, () -> {
                final int birchLogs = count(level, birch.box(), s -> s.is(Blocks.BIRCH_LOG));
                final int doomed = count(level, both, s -> TreeFootprint.isDecayingLeaves(s) && !s.getValue(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.DISTANCE) == 7);
                final int birchLeaves = count(level, both, s -> s.is(Blocks.BIRCH_LEAVES) && !s.getValue(LeavesBlock.PERSISTENT));
                final long oakLost = oakBlocks.keySet().stream().filter(pos -> !level.getBlockState(pos).is(oakBlocks.get(pos))).count();
                final long hedgeLost = hedge.stream().filter(pos -> !level.getBlockState(pos).is(Blocks.BIRCH_LEAVES)).count();
                final BlockState stump = level.getBlockState(birchBase);
                last[0] = "birch logs " + birchLogs + ", birch leaves " + birchLeaves + ", doomed leaves " + doomed + ", oak blocks lost " + oakLost + "/" + oakBlocks.size()
                            + ", hedge lost " + hedgeLost + "/" + hedge.size() + ", stump " + stump.getBlock();
                helper.assertTrue(oakLost == 0, "the oak was touched: " + last[0]);
                helper.assertTrue(hedgeLost == 0, "placed leaves were broken: " + last[0]);
                helper.assertTrue(!stump.is(Blocks.OAK_SAPLING), "the birch stump got an oak sapling");
                // Birch leaves within reach of the oak's logs are held by it, as in vanilla, and stay.
                return birchLogs == 0 && doomed == 0 && stump.is(Blocks.BIRCH_SAPLING) && tree(worker) == null;
            }, () -> last[0] + ", " + describe(worker), null, "forestry_sweep_neighbours_sapling");
        });
    }

    // ---------------------------------------------------------------- build guard

    /**
     * An oak grown against a player's oak log cabin (planks floor and roof, a glass pane, a trapdoor, a torch) is left whole, the hut window counts it, and the
     * Forester fells another oak instead.
     */
    private static void cabinSkip(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry cabin");
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        final Grown cabinTree = grow(helper, new TreeSpec("cabin oak", new BlockPos(20, 1, 16), false, Blocks.OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.OAK, null), 31L);
        final Grown freeTree = grow(helper, new TreeSpec("free oak", new BlockPos(32, 1, 28), false, Blocks.OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.OAK, null), 32L);
        final BlockPos trunk = cabinTree.stumps().get(0);

        // The cabin: oak log walls from x+1 (touching the trunk) to x+5, z-2..z+2, three high, planks floor and roof, a glass pane, a door, a torch.
        final Map<BlockPos, BlockState> cabin = new HashMap<>();
        final Consumer<Map.Entry<BlockPos, BlockState>> put = entry -> {
            level.setBlockAndUpdate(entry.getKey(), entry.getValue());
            cabin.put(entry.getKey(), level.getBlockState(entry.getKey()));
        };
        for (int dx = 1; dx <= 5; dx++)
        {
            for (int dz = -2; dz <= 2; dz++)
            {
                put.accept(Map.entry(trunk.offset(dx, -1, dz), Blocks.OAK_PLANKS.defaultBlockState()));
                put.accept(Map.entry(trunk.offset(dx, 3, dz), Blocks.OAK_PLANKS.defaultBlockState()));
                final boolean wall = dx == 1 || dx == 5 || dz == -2 || dz == 2;
                for (int y = 0; y < 3; y++)
                {
                    put.accept(Map.entry(trunk.offset(dx, y, dz), wall ? Blocks.OAK_LOG.defaultBlockState() : Blocks.AIR.defaultBlockState()));
                }
            }
        }
        put.accept(Map.entry(trunk.offset(3, 1, 2), Blocks.GLASS_PANE.defaultBlockState()));
        put.accept(Map.entry(trunk.offset(3, 0, -2), Blocks.OAK_TRAPDOOR.defaultBlockState()));
        put.accept(Map.entry(trunk.offset(3, 0, 0), Blocks.TORCH.defaultBlockState()));
        helper.assertTrue(level.getBlockState(trunk.east()).is(Blocks.OAK_LOG) && level.getBlockState(trunk).is(Blocks.OAK_LOG), "the cabin does not touch the trunk");
        final int cabinTreeLogs = count(level, cabinTree.box(), s -> s.is(Blocks.OAK_LOG));

        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10), new ItemStack(Items.OAK_SAPLING, 8));
            final String[] last = {""};
            pump(helper, List.of(worker), FELL_CAP, () -> {
                final long cabinChanged = cabin.entrySet().stream().filter(e -> !level.getBlockState(e.getKey()).equals(e.getValue())).count();
                final int cabinLogs = count(level, cabinTree.box(), s -> s.is(Blocks.OAK_LOG));
                final int freeLogs = count(level, freeTree.box(), s -> s.is(Blocks.OAK_LOG));
                last[0] = "cabin blocks changed " + cabinChanged + ", cabin-side oak logs " + cabinLogs + "/" + cabinTreeLogs + ", free oak logs " + freeLogs
                            + ", skipped trees " + building.getSkippedTreeCount();
                helper.assertTrue(cabinChanged == 0 && cabinLogs == cabinTreeLogs, "the cabin or its tree was cut: " + last[0]);
                return freeLogs == 0 && building.getSkippedTreeCount() >= 1;
            }, () -> last[0] + ", " + describe(worker), null, "forestry_cabin_skip");
        });
    }

    // ---------------------------------------------------------------- bee nest

    /**
     * A natural bee nest with two bees on an oak: the Forester takes it whole (the bees stay inside, none come out) and keeps it.
     */
    private static void beeNest(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry bees");
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        final Grown oak = grow(helper, new TreeSpec("oak", new BlockPos(22, 1, 18), false, Blocks.OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.OAK, null), 41L);
        final BlockPos nest = oak.stumps().get(0).above(2).south();
        level.setBlockAndUpdate(nest, Blocks.BEE_NEST.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH));
        helper.assertTrue(level.getBlockEntity(nest) instanceof BeehiveBlockEntity, "no bee nest block entity");
        final BeehiveBlockEntity hive = (BeehiveBlockEntity) level.getBlockEntity(nest);
        for (int i = 0; i < 2; i++)
        {
            final Bee bee = net.minecraft.world.entity.EntityTypes.BEE.create(level, EntitySpawnReason.TRIGGERED);
            helper.assertTrue(bee != null, "no bee");
            bee.setPos(Vec3.atCenterOf(nest));
            // They stay in the nest for the whole test: a bee only leaves on its own after its minimum time inside.
            final BeehiveBlockEntity.Occupant occupant = BeehiveBlockEntity.Occupant.of(bee);
            hive.storeBee(new BeehiveBlockEntity.Occupant(occupant.entityData(), 0, 1_000_000));
        }
        helper.assertTrue(hive.getOccupantCount() == 2, "the nest holds " + hive.getOccupantCount() + " bees");
        final AABB area = oak.box().inflate(8);

        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10), new ItemStack(Items.OAK_SAPLING, 8));
            final String[] last = {""};
            pump(helper, List.of(worker), FELL_CAP, () -> {
                final int bees = level.getEntitiesOfClass(Bee.class, area).size();
                final Predicate<ItemStack> nestWithBees = stack -> stack.is(Items.BEE_NEST) && stack.getOrDefault(DataComponents.BEES, Bees.EMPTY).bees().size() == 2;
                final int kept = InventoryUtils.getItemCountInItemHandler(worker.getInventory(), nestWithBees) + InventoryUtils.getCountFromBuilding(building, nestWithBees);
                final int logs = count(level, oak.box(), s -> s.is(Blocks.OAK_LOG));
                last[0] = "bees out " + bees + ", nest block " + level.getBlockState(nest).getBlock() + ", nests with 2 bees kept " + kept + ", oak logs " + logs;
                helper.assertTrue(bees == 0, "bees came out of the nest: " + last[0]);
                return kept == 1 && logs == 0 && !level.getBlockState(nest).is(Blocks.BEE_NEST);
            }, () -> last[0] + ", " + describe(worker), null, "forestry_bee_nest");
        });
    }

    // ---------------------------------------------------------------- drops and overflow

    /**
     * With his inventory filled to the last slot as he starts the tree, the logs that do not fit drop at his feet: none is deleted (inventory, hut and
     * ground together hold every log).
     */
    private static void dropsOverflow(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry overflow");
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        final Grown oak = grow(helper, new TreeSpec("oak", new BlockPos(22, 1, 18), false, Blocks.OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.OAK, null), 51L);
        final AABB plot = new AABB(Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(-8, 0, -8))), Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(40, 40, 40))));

        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10), new ItemStack(Items.OAK_SAPLING, 1));
            final boolean[] filled = {false};
            final boolean[] overflowSeen = {false};
            final String[] last = {""};
            pump(helper, List.of(worker), FELL_CAP, () -> {
                final Predicate<ItemStack> log = stack -> stack.is(Items.OAK_LOG);
                final int onGround = level.getEntitiesOfClass(ItemEntity.class, plot).stream().filter(e -> log.test(e.getItem())).mapToInt(e -> e.getItem().getCount()).sum();
                final int held = InventoryUtils.getItemCountInItemHandler(worker.getInventory(), log) + InventoryUtils.getCountFromBuilding(building, log);
                final int logs = count(level, oak.box(), s -> s.is(Blocks.OAK_LOG));
                overflowSeen[0] |= onGround > 0;
                last[0] = "filled " + filled[0] + ", oak logs standing " + logs + "/" + oak.logs() + ", held " + held + ", on the ground " + onGround;
                return filled[0] && overflowSeen[0] && logs == 0 && held + onGround >= oak.logs();
            }, () -> last[0] + ", " + describe(worker), () -> {
                // Fill every free slot once he has chosen the tree, so the felling (which he does not interrupt) overflows.
                final Tree tree = tree(worker);
                if (!filled[0] && tree != null && tree.getLogsLeft() > 0)
                {
                    for (int i = 0; i < worker.getInventory().getSlots(); i++)
                    {
                        if (worker.getInventory().getStackInSlot(i).isEmpty())
                        {
                            worker.getInventory().setStackInSlot(i, new ItemStack(Items.COBBLESTONE, 64));
                        }
                    }
                    filled[0] = true;
                }
            }, "forestry_drops_overflow");
        });
    }

    // ---------------------------------------------------------------- two lumberjacks

    /**
     * Two lumberjack huts of one colony, three oaks: never do both Foresters work on the same tree, and all three are felled.
     */
    private static void twoLumberjacks(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry two");
        final BuildingLumberjack first = hut(helper, colony, HUT, false);
        final BuildingLumberjack second = hut(helper, colony, new BlockPos(34, 1, 2), false);
        final List<Grown> oaks = List.of(
          grow(helper, new TreeSpec("oak a", new BlockPos(16, 1, 18), false, Blocks.OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.OAK, null), 61L),
          grow(helper, new TreeSpec("oak b", new BlockPos(26, 1, 18), false, Blocks.OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.OAK, null), 62L),
          grow(helper, new TreeSpec("oak c", new BlockPos(21, 1, 28), false, Blocks.OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.OAK, null), 63L));

        helper.runAfterDelay(20, () -> {
            final ICitizenData a = forester(helper, colony, first, new BlockPos(12, 1, 10), new ItemStack(Items.OAK_SAPLING, 8));
            final ICitizenData b = forester(helper, colony, second, new BlockPos(30, 1, 10), new ItemStack(Items.OAK_SAPLING, 8));
            final String[] last = {""};
            pump(helper, List.of(a, b), FELL_CAP, () -> {
                final int standing = oaks.stream().mapToInt(o -> count(level, o.box(), s -> s.is(Blocks.OAK_LOG)) > 0 ? 1 : 0).sum();
                last[0] = "oaks standing " + standing;
                return standing == 0;
            }, () -> last[0] + ", A " + describe(a) + ", B " + describe(b), () -> {
                final Tree ta = tree(a);
                final Tree tb = tree(b);
                if (ta != null && tb != null)
                {
                    final boolean shared = ta.getLocation().equals(tb.getLocation()) || ta.getAllLogs().stream().anyMatch(tb.getAllLogs()::contains);
                    helper.assertTrue(!shared, "both Foresters work on the tree at " + ta.getLocation() + " / " + tb.getLocation());
                }
            }, "forestry_two_lumberjacks");
        });
    }

    // ---------------------------------------------------------------- idle planting

    /**
     * No tree to cut and saplings in the hut: he plants them on a four-block grid in a ring around the hut, never on or next to the path or the
     * farmland, never in the hut box.
     */
    private static void idlePlantingRing(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry planting");
        final BuildingLumberjack building = hut(helper, colony, HUT, true);
        // Grass around the hut, a dirt path row and a farmland patch through the ring.
        for (int x = -8; x < 40; x++)
        {
            for (int z = -8; z < 32; z++)
            {
                final BlockPos rel = new BlockPos(x, 0, z);
                final BlockPos abs = helper.absolutePos(rel);
                if (!building.isInBuilding(abs.above()))
                {
                    helper.setBlock(rel, z == 14 ? Blocks.DIRT_PATH.defaultBlockState() : (x >= 20 && x < 24 && z >= 18 && z < 22) ? Blocks.FARMLAND.defaultBlockState()
                                                                                         : Blocks.GRASS_BLOCK.defaultBlockState());
                }
            }
        }
        InventoryUtils.forceItemStackToItemHandler(building.getItemHandlerCap(), new ItemStack(Items.OAK_SAPLING, 16), s -> true);
        final AABB plot = new AABB(Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(-8, 1, -8))), Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(40, 2, 32))));

        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(8, 1, 10));
            final String[] last = {""};
            pump(helper, List.of(worker), 24_000, () -> {
                final List<BlockPos> saplings = new ArrayList<>();
                for (final BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(plot.minX, plot.minY, plot.minZ), BlockPos.containing(plot.maxX - 1, plot.maxY - 1, plot.maxZ - 1)))
                {
                    if (level.getBlockState(pos).is(Blocks.OAK_SAPLING))
                    {
                        saplings.add(pos.immutable());
                    }
                }
                for (final BlockPos pos : saplings)
                {
                    helper.assertTrue(Math.floorMod(pos.getX(), 4) == 0 && Math.floorMod(pos.getZ(), 4) == 0, "sapling off the grid at " + pos);
                    helper.assertTrue(level.getBlockState(pos.below()).is(Blocks.GRASS_BLOCK), "sapling not on grass at " + pos);
                    helper.assertTrue(!building.isInBuilding(pos), "sapling in the hut box at " + pos);
                    for (final BlockPos near : BlockPos.betweenClosed(pos.offset(-2, -1, -2), pos.offset(2, -1, 2)))
                    {
                        final BlockState ground = level.getBlockState(near);
                        helper.assertTrue(!ground.is(Blocks.DIRT_PATH) && !ground.is(Blocks.FARMLAND), "sapling next to a path or farmland at " + pos);
                    }
                    for (final BlockPos other : saplings)
                    {
                        helper.assertTrue(other.equals(pos) || other.distManhattan(pos) >= 4, "saplings too close: " + pos + " " + other);
                    }
                }
                final EntityAIWorkLumberjack ai = ai(worker);
                last[0] = "saplings planted " + saplings.size() + " at " + saplings + ", ai count " + (ai == null ? -1 : ai.getPlantedTotal());
                return saplings.size() >= 3;
            }, () -> last[0] + ", " + describe(worker), null, "forestry_idle_planting_ring");
        });
    }

    // ---------------------------------------------------------------- TreeChop lane

    private static void resetTreeChop()
    {
        Compatibility.treeChop.setEnabled(true);
        MineColonies.getConfig().getServer().lumberjacksUseTreeChop.set(true);
    }

    private static boolean anyChoppedLog(final ServerLevel level, final AABB box)
    {
        return count(level, box, Compatibility.treeChop::isChoppedLog) > 0;
    }

    /**
     * Chop the grown oak twice through TreeChop as a player would, so it is half chopped.
     */
    private static void halfChop(final GameTestHelper helper, final Grown oak)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos base = oak.stumps().get(0);
        for (int i = 0; i < 2; i++)
        {
            final TreeChopProxy.ChopOutcome outcome = Compatibility.treeChop.chop(level, base, Vec3.atCenterOf(base.north(2)), new ItemStack(Items.IRON_AXE), 1);
            helper.assertTrue(outcome == TreeChopProxy.ChopOutcome.CHOPPED, "the half chop gave " + outcome);
        }
        helper.assertTrue(anyChoppedLog(level, oak.box()), "no chopped log after the half chop");
    }

    /**
     * TreeChop on: the Forester chops chop by chop (chopped logs seen at the base), the whole tree falls through TreeChop, every drop is his, the leaves
     * are gone, the stump is replanted, and the axe wears once per chop.
     */
    private static void treeChopFell(final GameTestHelper helper)
    {
        resetTreeChop();
        treeChopRun(helper, "forestry_treechop_fell", false, true);
    }

    /**
     * A tree a player left half chopped (chopped logs at the base): the Forester finishes it through TreeChop, nothing floats, no chopped log is left.
     */
    private static void treeChopHalfChopped(final GameTestHelper helper)
    {
        resetTreeChop();
        treeChopRun(helper, "forestry_treechop_half_chopped", true, true);
    }

    /**
     * TreeChop turned off in its own config: the Forester uses the normal path (no chop through TreeChop) and still removes the whole tree.
     */
    private static void treeChopDisabledFallback(final GameTestHelper helper)
    {
        resetTreeChop();
        Compatibility.treeChop.setEnabled(false);
        treeChopRun(helper, "forestry_treechop_disabled_fallback", false, false);
    }

    /**
     * The fork setting {@code lumberjacksUseTreeChop} off and a half-chopped tree: the normal path mines the chopped logs too (they drop the original
     * log), so the tree is finished whole.
     */
    private static void treeChopSettingOff(final GameTestHelper helper)
    {
        resetTreeChop();
        MineColonies.getConfig().getServer().lumberjacksUseTreeChop.set(false);
        treeChopRun(helper, "forestry_treechop_setting_off", true, false);
    }

    private static void treeChopRun(final GameTestHelper helper, final String name, final boolean halfChopped, final boolean expectTreeChop)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, name);
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        final Grown oak = grow(helper, new TreeSpec("oak", new BlockPos(22, 1, 18), false, Blocks.OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.OAK, s -> s.is(Blocks.OAK_SAPLING)), 71L);
        if (halfChopped)
        {
            final boolean enabled = Compatibility.treeChop.setEnabled(true);
            halfChop(helper, oak);
            Compatibility.treeChop.setEnabled(enabled);
        }

        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10), new ItemStack(Items.OAK_SAPLING, 4));
            final boolean[] choppedSeen = {false};
            final String[] last = {""};
            pump(helper, List.of(worker), FELL_CAP, () -> {
                final EntityAIWorkLumberjack ai = ai(worker);
                final int left = count(level, oak.box(), TreeFootprint::isTreeBlock);
                final int leaves = count(level, oak.box(), ForestryGameTests::isTreeLeaf);
                final int dropped = items(level, oak.box());
                final int chops = ai == null ? 0 : ai.getTreeChopChopCount();
                final int fellings = ai == null ? 0 : ai.getTreeChopFellCount();
                final ItemStack axe = worker.getInventory().getStackInSlot(0);
                final boolean replanted = level.getBlockState(oak.stumps().get(0)).is(Blocks.OAK_SAPLING);
                last[0] = "tree blocks " + left + ", leaves " + leaves + ", items " + dropped + ", chops " + chops + ", fellings " + fellings + ", chopped seen "
                            + choppedSeen[0] + ", axe damage " + axe.getDamageValue() + ", logs kept " + logsKept(worker, building) + "/" + oak.logs() + ", stump "
                            + level.getBlockState(oak.stumps().get(0)).getBlock();
                if (!expectTreeChop)
                {
                    helper.assertTrue(chops == 0, "chopped through TreeChop although it is off: " + last[0]);
                }
                final boolean whole = left == 0 && leaves == 0 && dropped == 0 && !anyChoppedLog(level, oak.box()) && replanted
                                        && logsKept(worker, building) * 10 >= oak.logs() * 9;
                if (expectTreeChop)
                {
                    return whole && fellings >= 1 && (halfChopped || (chops >= 2 && choppedSeen[0])) && (!axe.is(Items.STONE_AXE) || axe.getDamageValue() >= chops);
                }
                return whole;
            }, () -> last[0] + ", " + describe(worker), () -> {
                final EntityAIWorkLumberjack ai = ai(worker);
                if (ai != null && ai.getTreeChopChopCount() > 0 && ai.getTreeChopFellCount() == 0)
                {
                    choppedSeen[0] |= anyChoppedLog(level, oak.box());
                }
            }, name);
        });
    }
}
