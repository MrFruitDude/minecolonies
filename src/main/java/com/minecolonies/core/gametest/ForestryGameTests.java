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
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    /**
     * The idle planting ring around {@link #HUT} reaches about 21 blocks out (hut box, gap, ring width): the test lays its ground over this square.
     */
    private static final int PLANT_RING_MIN = -20;
    private static final int PLANT_RING_MAX = 36;

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
        TESTS.put("forestry_replant_split_stacks", ForestryGameTests::replantSplitStacks);
        TESTS.put("forestry_changed_tree", ForestryGameTests::changedTree);
        TESTS.put("forestry_hedge_path", ForestryGameTests::hedgePath);
        TESTS.put("forestry_radius_branch", ForestryGameTests::radiusBranch);
        TESTS.put("forestry_touching_same_wood", ForestryGameTests::touchingSameWood);
        TESTS.put("forestry_touching_woods", ForestryGameTests::touchingWoods);
        TESTS.put("forestry_unreachable_give_up", ForestryGameTests::unreachableGiveUp);
        TESTS.put("forestry_sweep_near_build", ForestryGameTests::sweepNearBuild);
        TESTS.put("forestry_replant_protected", ForestryGameTests::replantProtected);
        TESTS.put("forestry_cap_decoration", ForestryGameTests::capDecoration);
        TESTS.put("forestry_leaf_wall", ForestryGameTests::leafWall);
        TESTS.put("forestry_same_wood_beam", ForestryGameTests::sameWoodBeam);
        TESTS.put("forestry_cap_added_fungus", ForestryGameTests::capAddedFungus);
        TESTS.put("forestry_joined_caps", ForestryGameTests::joinedCaps);

        TREECHOP_TESTS.put("forestry_treechop_fell", ForestryGameTests::treeChopFell);
        TREECHOP_TESTS.put("forestry_treechop_half_chopped", ForestryGameTests::treeChopHalfChopped);
        TREECHOP_TESTS.put("forestry_treechop_disabled_fallback", ForestryGameTests::treeChopDisabledFallback);
        TREECHOP_TESTS.put("forestry_treechop_setting_off", ForestryGameTests::treeChopSettingOff);
        TREECHOP_TESTS.put("forestry_treechop_stays_in_footprint", ForestryGameTests::treeChopStaysInFootprint);
    }

    static void register(final RegisterGameTestsEvent event)
    {
        register(event, TESTS);
    }

    static void registerTreeChop(final RegisterGameTestsEvent event)
    {
        // The mod list, not Compatibility.treeChop: the compat is swapped in when the server discovers its data, after the tests are registered
        if (!ModList.get().isLoaded("treechop"))
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
                 + ", at " + worker.getEntity().map(e -> e.blockPosition().toShortString()).orElse("?") + ", inventory " + inventory
                 + ", given up " + (ai == null ? "?" : ai.getTreesGivenUp() + " (" + ai.getGiveUpNote() + ")")
                 + (ai == null || ai.getWalkNoteAtGiveUp().isEmpty() ? "" : ", walk at give-up: " + ai.getWalkNoteAtGiveUp())
                 + (ai == null || ai.getSweepNote().isEmpty() ? "" : ", last cap sweep: " + ai.getSweepNote());
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
          // (177 logs: one stone axe lasts 131, so he carries a second; he takes it when the first breaks)
          List.of(new ItemStack(Items.SPRUCE_SAPLING, 8), new ItemStack(Items.JUNGLE_SAPLING, 4), new ItemStack(Items.STONE_AXE)),
          null, true);
    }

    private static void wholeDarkPale(final GameTestHelper helper)
    {
        // The town hall blueprint faces the other way, so its box (with the one-block margin of isInBuilding) reaches x 18: trees stay at x 21 or more
        wholeTrees(helper, "forestry_whole_dark_pale", List.of(
          new TreeSpec("dark oak", new BlockPos(24, 1, 10), true, Blocks.DARK_OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.DARK_OAK, s -> s.is(Blocks.DARK_OAK_SAPLING)),
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
        // The red cap hangs two blocks out from the stem: at x 22 it stays clear of the town hall's box (it reaches x 18, see wholeDarkPale), where a cap,
        // which never decays, is no block he may cut or clear
        wholeTrees(helper, "forestry_whole_mushroom", List.of(
          new TreeSpec("huge red mushroom", new BlockPos(22, 1, 8), false, Blocks.RED_MUSHROOM, Blocks.MYCELIUM, null, null),
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
        // The whole ring (it reaches past the test's own plot, onto whatever ground the scene has there): dirt, grass on it, a dirt path row and a
        // farmland patch through the ring, and nothing standing above it, so the planting search reads the same ground in every run. The hut boxes (the
        // town hall's too) are left as they are.
        final ServerLevel plotLevel = helper.getLevel();
        final List<IBuilding> huts = new ArrayList<>(colony.getServerBuildingManager().getBuildings().values());
        for (int x = PLANT_RING_MIN; x < PLANT_RING_MAX; x++)
        {
            for (int z = PLANT_RING_MIN; z < PLANT_RING_MAX; z++)
            {
                final BlockPos abs = helper.absolutePos(new BlockPos(x, 0, z));
                plotLevel.setChunkForced(abs.getX() >> 4, abs.getZ() >> 4, true);
                if (huts.stream().anyMatch(hut -> hut.isInBuilding(abs.above())))
                {
                    continue;
                }
                plotLevel.setBlock(abs.below(), Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
                plotLevel.setBlock(abs, z == 14 ? Blocks.DIRT_PATH.defaultBlockState() : (x >= 20 && x < 24 && z >= 18 && z < 22) ? Blocks.FARMLAND.defaultBlockState()
                                                                                       : Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 1; y <= 12; y++)
                {
                    if (!plotLevel.getBlockState(abs.above(y)).isAir())
                    {
                        plotLevel.setBlock(abs.above(y), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        InventoryUtils.forceItemStackToItemHandler(building.getItemHandlerCap(), new ItemStack(Items.OAK_SAPLING, 16), s -> true);
        final AABB plot = new AABB(Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(PLANT_RING_MIN, 1, PLANT_RING_MIN))),
          Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(PLANT_RING_MAX, 2, PLANT_RING_MAX))));

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
                last[0] = "saplings planted " + saplings.size() + " at " + saplings + ", ai count " + (ai == null ? -1 : ai.getPlantedTotal())
                            + ", planting " + (ai == null ? "?" : ai.getPlantNote()) + ", hut saplings "
                            + InventoryUtils.getCountFromBuilding(building, stack -> stack.is(Items.OAK_SAPLING));
                return saplings.size() >= 3;
            }, () -> last[0] + ", " + describe(worker), null, "forestry_idle_planting_ring");
        });
    }

    // ---------------------------------------------------------------- safety (improve-all r1)

    /**
     * Replanting pays one sapling per sapling placed: a felled dark oak (four stumps, the tree given to him with its logs and leaves already gone) and his
     * five dark oak saplings split 2 + 1 + 2 over three slots, a spruce stack between them. Afterwards the four stumps hold dark oak saplings, and the
     * saplings he holds, the hut holds, lie on the ground or stand planted add up to the five he started with, never more.
     */
    private static void replantSplitStacks(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry replant");
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        IColonyManager.getInstance().getCompatibilityManager().connectLeafToSapling(Blocks.DARK_OAK_LEAVES, new ItemStack(Items.DARK_OAK_SAPLING));
        final BlockPos base = helper.absolutePos(new BlockPos(20, 1, 16));
        level.setChunkForced(base.getX() >> 4, base.getZ() >> 4, true);
        for (int dx = -3; dx <= 4; dx++)
        {
            for (int dz = -3; dz <= 4; dz++)
            {
                level.setBlockAndUpdate(base.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        final List<BlockPos> stumps = List.of(base, base.east(), base.south(), base.south().east());
        for (final BlockPos stump : stumps)
        {
            for (int y = 0; y < 6; y++)
            {
                level.setBlockAndUpdate(stump.above(y), Blocks.DARK_OAK_LOG.defaultBlockState());
            }
        }
        for (int y = 4; y <= 6; y++)
        {
            for (int dx = -2; dx <= 3; dx++)
            {
                for (int dz = -2; dz <= 3; dz++)
                {
                    if (level.getBlockState(base.offset(dx, y, dz)).isAir())
                    {
                        level.setBlockAndUpdate(base.offset(dx, y, dz), Blocks.DARK_OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1));
                    }
                }
            }
        }
        final AABB plot = new AABB(Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(-8, 0, -8))), Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(40, 12, 40))));
        final Predicate<ItemStack> sapling = stack -> stack.is(Items.DARK_OAK_SAPLING);
        final int start = 5;

        helper.runAfterDelay(20, () -> {
            final Tree tree = new Tree(level, base.above(2), colony);
            helper.assertTrue(tree.isTree() && tree.getSkip() == TreeFootprint.Skip.NONE && tree.getStumpLocations().size() == 4
                                && tree.getSapling().is(Items.DARK_OAK_SAPLING),
              "the dark oak is no four-stump tree: tree " + tree.isTree() + " skip " + tree.getSkip() + " stumps " + tree.getStumpLocations() + " sapling " + tree.getSapling());
            // Felled already, without a drop: no sapling comes from its leaves, so the count only moves by replanting
            for (final BlockPos pos : BlockPos.betweenClosed(base.offset(-3, 0, -3), base.offset(4, 8, 4)))
            {
                final BlockState state = level.getBlockState(pos);
                if (state.is(Blocks.DARK_OAK_LOG) || state.is(Blocks.DARK_OAK_LEAVES))
                {
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                }
            }
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10),
              new ItemStack(Items.DARK_OAK_SAPLING, 2), new ItemStack(Items.SPRUCE_SAPLING, 3), new ItemStack(Items.DARK_OAK_SAPLING, 1),
              new ItemStack(Items.DARK_OAK_SAPLING, 2));
            helper.assertTrue(worker.getJob() instanceof JobLumberjack, "no lumberjack job");
            ((JobLumberjack) worker.getJob()).setTree(tree);
            final String[] last = {""};
            pump(helper, List.of(worker), 12_000, () -> {
                final int planted = count(level, plot, state -> state.is(Blocks.DARK_OAK_SAPLING));
                final int onStumps = (int) stumps.stream().filter(pos -> level.getBlockState(pos).is(Blocks.DARK_OAK_SAPLING)).count();
                final int held = InventoryUtils.getItemCountInItemHandler(worker.getInventory(), sapling) + InventoryUtils.getCountFromBuilding(building, sapling);
                final int onGround = level.getEntitiesOfClass(ItemEntity.class, plot).stream().filter(e -> sapling.test(e.getItem())).mapToInt(e -> e.getItem().getCount()).sum();
                final int total = planted + held + onGround;
                last[0] = "planted " + planted + " (on the stumps " + onStumps + "), held " + held + ", on the ground " + onGround + ", total " + total + "/" + start;
                helper.assertTrue(total <= start, "a sapling was planted without being paid: " + last[0]);
                return onStumps == 4 && total == start && tree(worker) == null;
            }, () -> last[0] + ", " + describe(worker), null, "forestry_replant_split_stacks");
        });
    }

    /**
     * A chosen tree is checked block by block right before each cut: three oaks; on the first he chooses, a player puts planks in place of the top log
     * before his first cut, so he leaves that tree whole; on the second, after his first cut, the base log becomes planks, so he leaves the planks and the
     * log on them; the third he fells. No planks are ever taken.
     */
    private static void changedTree(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry changed");
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        final List<Grown> oaks = List.of(
          grow(helper, new TreeSpec("oak a", new BlockPos(16, 1, 18), false, Blocks.OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.OAK, null), 61L),
          grow(helper, new TreeSpec("oak b", new BlockPos(26, 1, 18), false, Blocks.OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.OAK, null), 62L),
          grow(helper, new TreeSpec("oak c", new BlockPos(21, 1, 28), false, Blocks.OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.OAK, null), 63L));

        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10), new ItemStack(Items.OAK_SAPLING, 8));
            // In the order he chooses them: the tree changed before his first cut, the tree changed during the felling, the free tree
            final List<Grown> roles = new ArrayList<>();
            // Their own logs and base as he saw them (the boxes of neighbouring oaks overlap)
            final Map<Grown, List<BlockPos>> logsAtChoice = new HashMap<>();
            final Map<Grown, BlockPos> baseAtChoice = new HashMap<>();
            final java.util.function.ToIntFunction<Grown> standing = grown -> (int) logsAtChoice.get(grown).stream().filter(pos -> level.getBlockState(pos).is(Blocks.OAK_LOG)).count();
            final List<BlockPos> planks = new ArrayList<>();
            final boolean[] duringChanged = {false};
            final String[] last = {""};
            pump(helper, List.of(worker), FELL_CAP, () -> {
                for (final BlockPos pos : planks)
                {
                    helper.assertTrue(level.getBlockState(pos).is(Blocks.OAK_PLANKS), "the planks at " + pos.toShortString() + " were taken");
                }
                final EntityAIWorkLumberjack ai = ai(worker);
                final StringBuilder state = new StringBuilder("planks " + planks.size() + ", changed blocks left " + (ai == null ? -1 : ai.getChangedBlocksLeft())
                                                                + ", changed trees left " + (ai == null ? -1 : ai.getChangedTreesLeft()));
                for (int i = 0; i < roles.size(); i++)
                {
                    state.append(", tree ").append(i).append(' ').append(roles.get(i).spec().name()).append(" logs standing ")
                      .append(standing.applyAsInt(roles.get(i))).append('/').append(logsAtChoice.get(roles.get(i)).size());
                }
                last[0] = state.toString();
                if (roles.size() < 3 || !duringChanged[0])
                {
                    return false;
                }
                final Grown before = roles.get(0);
                final Grown during = roles.get(1);
                final Grown free = roles.get(2);
                helper.assertTrue(standing.applyAsInt(before) == logsAtChoice.get(before).size() - 1, "the tree changed before the first cut was cut: " + last[0]);
                final Tree current = tree(worker);
                final boolean duringDone = current == null || !current.getLocation().equals(baseAtChoice.get(during));
                return duringDone && standing.applyAsInt(free) == 0 && ai != null && ai.getChangedTreesLeft() >= 1 && building.getSkippedTreeCount() >= 1;
            }, () -> last[0] + ", " + describe(worker), () -> {
                final Tree current = tree(worker);
                if (current == null || current.getAllLogs().isEmpty())
                {
                    return;
                }
                final Grown grown = oaks.stream().min(java.util.Comparator.comparingDouble(o -> o.stumps().get(0).distSqr(current.getLocation()))).orElseThrow();
                if (!roles.contains(grown))
                {
                    roles.add(grown);
                    logsAtChoice.put(grown, List.copyOf(current.getAllLogs()));
                    baseAtChoice.put(grown, current.getLocation());
                    if (roles.size() == 1)
                    {
                        // Before his first cut: the top log becomes planks
                        final BlockPos top = current.getAllLogs().stream().max(java.util.Comparator.comparingInt(BlockPos::getY)).orElseThrow();
                        level.setBlockAndUpdate(top, Blocks.OAK_PLANKS.defaultBlockState());
                        planks.add(top);
                    }
                }
                if (roles.size() >= 2 && grown == roles.get(1) && !duringChanged[0] && current.getLogsLeft() < current.getAllLogs().size()
                      && level.getBlockState(current.getLocation()).is(Blocks.OAK_LOG))
                {
                    // During the felling: the base log becomes planks
                    level.setBlockAndUpdate(current.getLocation(), Blocks.OAK_PLANKS.defaultBlockState());
                    planks.add(current.getLocation());
                    duringChanged[0] = true;
                }
            }, "forestry_changed_tree");
        });
    }

    /**
     * A hedge of placed leaves rings an oak four blocks out, with a one-block gap on the side away from the hut: he walks around to the gap and fells the
     * oak, and never breaks a leaf of the hedge on the way (stuck recovery takes only natural leaves).
     */
    private static void hedgePath(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry hedge");
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        final Grown oak = grow(helper, new TreeSpec("oak", new BlockPos(22, 1, 18), false, Blocks.OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.OAK, null), 41L);
        final BlockPos trunk = oak.stumps().get(0);
        final List<BlockPos> hedge = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++)
        {
            for (int dz = -4; dz <= 4; dz++)
            {
                level.setBlockAndUpdate(trunk.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState());
                if (Math.max(Math.abs(dx), Math.abs(dz)) != 4 || (dx == 0 && dz == 4))
                {
                    continue;
                }
                for (int y = 0; y <= 1; y++)
                {
                    final BlockPos pos = trunk.offset(dx, y, dz);
                    final BlockState at = level.getBlockState(pos);
                    if (at.isAir() || at.is(BlockTags.LEAVES))
                    {
                        level.setBlockAndUpdate(pos, Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
                        hedge.add(pos);
                    }
                }
            }
        }
        helper.assertTrue(hedge.size() >= 60, "the hedge has only " + hedge.size() + " leaves");
        // A plain oak: no branch reaches the hedge (a log touching placed leaves would make it a tree next to a build)
        final int farLogs = count(level, oak.box(), state -> state.is(Blocks.OAK_LOG)) - count(level, new AABB(trunk).inflate(2, 0, 2).expandTowards(0, 30, 0), state -> state.is(Blocks.OAK_LOG));
        helper.assertTrue(farLogs == 0, "the oak has " + farLogs + " logs three or more blocks from its trunk");

        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10), new ItemStack(Items.OAK_SAPLING, 4));
            final String[] last = {""};
            pump(helper, List.of(worker), FELL_CAP, () -> {
                final long hedgeLost = hedge.stream().filter(pos -> !level.getBlockState(pos).is(Blocks.SPRUCE_LEAVES)).count();
                final int logs = count(level, oak.box(), s -> s.is(Blocks.OAK_LOG));
                last[0] = "hedge lost " + hedgeLost + "/" + hedge.size() + ", oak logs " + logs + "/" + oak.logs();
                helper.assertTrue(hedgeLost == 0, "the hedge was broken: " + last[0]);
                return logs == 0 && tree(worker) == null;
            }, () -> last[0] + ", " + describe(worker), null, "forestry_hedge_path");
        });
    }

    /**
     * A tree whose branch goes on past the footprint radius is left whole (too big), never cut up to the radius: a 1x1 oak with a branch of 9 logs
     * (past the radius of 8) in each direction in turn, and a branch of 8 that ends at the radius as the control.
     */
    private static void radiusBranch(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final List<String> failures = new ArrayList<>();
        final int budget = MineColonies.getConfig().getServer().maxTreeSize.get();
        // Trunks far enough apart that no two branches touch; UP stands for the control, an eastern branch of 8
        final Map<Direction, BlockPos> trunks = new LinkedHashMap<>();
        trunks.put(Direction.EAST, new BlockPos(5, 1, 4));
        trunks.put(Direction.WEST, new BlockPos(35, 1, 4));
        trunks.put(Direction.NORTH, new BlockPos(5, 1, 30));
        trunks.put(Direction.SOUTH, new BlockPos(35, 1, 14));
        trunks.put(Direction.UP, new BlockPos(20, 1, 36));
        for (final Map.Entry<Direction, BlockPos> entry : trunks.entrySet())
        {
            final Direction dir = entry.getKey();
            final Direction branch = dir == Direction.UP ? Direction.EAST : dir;
            final int length = dir == Direction.UP ? 8 : 9;
            final BlockPos trunk = helper.absolutePos(entry.getValue());
            level.setChunkForced(trunk.getX() >> 4, trunk.getZ() >> 4, true);
            level.setBlockAndUpdate(trunk.below(), Blocks.GRASS_BLOCK.defaultBlockState());
            for (int y = 0; y < 6; y++)
            {
                level.setBlockAndUpdate(trunk.above(y), Blocks.OAK_LOG.defaultBlockState());
            }
            for (int i = 1; i <= length; i++)
            {
                level.setBlockAndUpdate(trunk.above(4).relative(branch, i), Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, branch.getAxis()));
            }
            final TreeFootprint.Footprint print = TreeFootprint.scan(level, trunk.above(2), null, budget);
            final String what = (dir == Direction.UP ? "control branch of 8 east" : "branch of 9 " + dir) + ": "
                                  + (print == null ? "no footprint" : print.logs.size() + " logs, skip " + print.skip + " " + print.skipDetail);
            if (dir == Direction.UP)
            {
                if (print == null || print.skip != TreeFootprint.Skip.NONE || print.logs.size() != 6 + length)
                {
                    failures.add(what);
                }
            }
            else if (print == null || print.skip != TreeFootprint.Skip.TOO_BIG)
            {
                failures.add(what);
            }
        }
        Log.getLogger().info("[forestry_radius_branch] failures {}", failures);
        helper.assertTrue(failures.isEmpty(), "radius branch: " + failures);
        helper.succeed();
    }

    // ---------------------------------------------------------------- touching trees, give-up, protection (improve-all r2)

    /**
     * A plain synthetic tree on grass: a 1x1 trunk, leaves two out on the two layers below its top (no corners) and one out on top. The leaves are only
     * collected; {@link #placeLeaves} sets them with their vanilla distance once every log stands.
     */
    private static void synthTrunk(final ServerLevel level, final BlockPos base, final int height, final Block log, final Block leaf, final Map<BlockPos, Block> leaves)
    {
        level.setChunkForced(base.getX() >> 4, base.getZ() >> 4, true);
        for (int dx = -3; dx <= 3; dx++)
        {
            for (int dz = -3; dz <= 3; dz++)
            {
                level.setBlockAndUpdate(base.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        for (int y = 0; y < height; y++)
        {
            level.setBlockAndUpdate(base.above(y), log.defaultBlockState());
        }
        for (int y = height - 2; y <= height; y++)
        {
            for (int dx = -2; dx <= 2; dx++)
            {
                for (int dz = -2; dz <= 2; dz++)
                {
                    final boolean skip = y == height ? Math.abs(dx) + Math.abs(dz) > 1 : (Math.abs(dx) == 2 && Math.abs(dz) == 2) || (dx == 0 && dz == 0);
                    if (!skip)
                    {
                        leaves.putIfAbsent(base.offset(dx, y, dz), leaf);
                    }
                }
            }
        }
    }

    /**
     * Places collected leaves where the air is, each with the distance vanilla would give it (steps through leaves to the nearest log).
     */
    private static void placeLeaves(final ServerLevel level, final Map<BlockPos, Block> leaves)
    {
        leaves.keySet().removeIf(pos -> !level.getBlockState(pos).isAir());
        final Map<BlockPos, Integer> distance = new HashMap<>();
        final java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        for (final BlockPos pos : leaves.keySet())
        {
            for (final Direction dir : Direction.values())
            {
                if (level.getBlockState(pos.relative(dir)).is(BlockTags.LOGS))
                {
                    distance.put(pos, 1);
                    queue.add(pos);
                    break;
                }
            }
        }
        while (!queue.isEmpty())
        {
            final BlockPos pos = queue.poll();
            for (final Direction dir : Direction.values())
            {
                final BlockPos near = pos.relative(dir);
                if (leaves.containsKey(near) && !distance.containsKey(near))
                {
                    distance.put(near, Math.min(LeavesBlock.DECAY_DISTANCE, distance.get(pos) + 1));
                    queue.add(near);
                }
            }
        }
        for (final Map.Entry<BlockPos, Block> entry : leaves.entrySet())
        {
            level.setBlockAndUpdate(entry.getKey(), entry.getValue().defaultBlockState().setValue(LeavesBlock.DISTANCE, distance.getOrDefault(entry.getKey(), LeavesBlock.DECAY_DISTANCE)));
        }
    }

    private static AABB relBox(final GameTestHelper helper, final BlockPos from, final BlockPos to)
    {
        return new AABB(Vec3.atLowerCornerOf(helper.absolutePos(from)), Vec3.atLowerCornerOf(helper.absolutePos(to)));
    }

    /**
     * Four oaks of one wood in a row, five apart, each joined to the next by a branch: one cluster wider than a tree's radius. Each trunk scans as a tree of
     * its own, the scans share no log, and the Forester fells and replants all four; none is left standing as too big or as next to a build.
     */
    private static void touchingSameWood(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry touching");
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        IColonyManager.getInstance().getCompatibilityManager().connectLeafToSapling(Blocks.OAK_LEAVES, new ItemStack(Items.OAK_SAPLING));
        final List<BlockPos> trunks = new ArrayList<>();
        final Map<BlockPos, Block> leaves = new HashMap<>();
        for (int i = 0; i < 4; i++)
        {
            final BlockPos base = helper.absolutePos(new BlockPos(22 + 5 * i, 1, 20));
            synthTrunk(level, base, 6, Blocks.OAK_LOG, Blocks.OAK_LEAVES, leaves);
            trunks.add(base);
        }
        final BlockState branch = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        for (int i = 0; i < 3; i++)
        {
            for (int dx = 1; dx <= 4; dx++)
            {
                level.setBlockAndUpdate(trunks.get(i).offset(dx, 3, 0), branch);
            }
        }
        placeLeaves(level, leaves);
        final AABB strip = relBox(helper, new BlockPos(19, 1, 17), new BlockPos(42, 10, 24));
        final int totalLogs = count(level, strip, s -> s.is(Blocks.OAK_LOG));
        helper.assertTrue(totalLogs == 4 * 6 + 3 * 4, "the oak row has " + totalLogs + " logs");
        final Set<BlockPos> scanned = new java.util.HashSet<>();
        int scannedCount = 0;
        for (final BlockPos base : trunks)
        {
            final TreeFootprint.Footprint print = TreeFootprint.scan(level, base.above(1), colony, MineColonies.getConfig().getServer().maxTreeSize.get());
            helper.assertTrue(print != null && print.skip == TreeFootprint.Skip.NONE && print.base.equals(base),
              "the oak at " + base.toShortString() + " is no tree of its own: " + (print == null ? "no footprint" : print.skip + " " + print.skipDetail + ", base " + print.base));
            scanned.addAll(print.logs);
            scannedCount += print.logs.size();
        }
        helper.assertTrue(scannedCount == totalLogs && scanned.size() == totalLogs, "the touching oaks do not split by trunk: " + scannedCount + " logs in the scans, "
                                                                                    + scanned.size() + " different, of " + totalLogs);

        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10), new ItemStack(Items.OAK_SAPLING, 8));
            final String[] last = {""};
            pump(helper, List.of(worker), FELL_CAP, () -> {
                final int logs = count(level, strip, s -> s.is(Blocks.OAK_LOG));
                final long replanted = trunks.stream().filter(pos -> level.getBlockState(pos).is(Blocks.OAK_SAPLING)).count();
                last[0] = "oak logs " + logs + "/" + totalLogs + ", stumps replanted " + replanted + "/4, skipped next to builds " + building.getSkippedTreeCount()
                            + ", too big " + building.getSkippedTreeCount(BuildingLumberjack.SkipReason.TOO_BIG);
                helper.assertTrue(building.getSkippedTreeCount() == 0 && building.getSkippedTreeCount(BuildingLumberjack.SkipReason.TOO_BIG) == 0,
                  "a touching oak was left standing: " + last[0]);
                return logs == 0 && replanted == 4 && tree(worker) == null;
            }, () -> last[0] + ", standing " + standingTrunks(level, colony, building, worker, trunks) + ", " + describe(worker), null, "forestry_touching_same_wood");
        });
    }

    /**
     * Why each trunk still standing is no tree for the Forester: what the search check, the full scan, the claims and the colony border say (open since
     * round 3, the logs show none of it).
     */
    private static String standingTrunks(final ServerLevel level, final IColony colony, final BuildingLumberjack building, final ICitizenData worker,
      final List<BlockPos> trunks)
    {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < trunks.size(); i++)
        {
            final BlockPos base = trunks.get(i);
            if (!level.getBlockState(base).is(Blocks.OAK_LOG))
            {
                continue;
            }
            final TreeFootprint.Footprint print = TreeFootprint.scan(level, base, colony, MineColonies.getConfig().getServer().maxTreeSize.get());
            int blocked = 0;
            int taken = 0;
            if (print != null)
            {
                for (final BlockPos log : print.logs)
                {
                    blocked += com.minecolonies.core.entity.ai.workers.util.TreeClaims.isBlocked(level, log) ? 1 : 0;
                    taken += com.minecolonies.core.entity.ai.workers.util.TreeClaims.isTakenByOther(level, log, colony.getID(), worker.getId()) ? 1 : 0;
                }
            }
            final Tree tree = new Tree(level, base, colony);
            out.add("trunk " + i + " at " + base.toShortString() + ": search " + TreeFootprint.check(level, base, level, colony.getID(), worker.getId(), p -> true)
                      + ", in colony " + Tree.checkIfInColony(base, colony, level, false) + ", " + (int) Math.sqrt(base.distSqr(building.getPosition()))
                      + " from the hut, scan " + (print == null ? "none" : print.logs.size() + " logs " + print.leaves + " leaves " + print.skip + " " + print.skipDetail)
                      + ", tree " + tree.isTree() + " " + tree.getSkip() + ", logs blocked " + blocked + " taken " + taken);
        }
        return out.toString();
    }

    /**
     * An oak and a birch whose branches touch are two natural trees: both are felled, neither counts as next to a build. A third oak with a spruce beam
     * hanging on it (no ground, no spruce leaves) is a build: it stays whole and is counted.
     */
    private static void touchingWoods(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry woods");
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        IColonyManager.getInstance().getCompatibilityManager().connectLeafToSapling(Blocks.OAK_LEAVES, new ItemStack(Items.OAK_SAPLING));
        IColonyManager.getInstance().getCompatibilityManager().connectLeafToSapling(Blocks.BIRCH_LEAVES, new ItemStack(Items.BIRCH_SAPLING));
        final Map<BlockPos, Block> leaves = new HashMap<>();
        final BlockPos oak = helper.absolutePos(new BlockPos(22, 1, 20));
        final BlockPos birch = helper.absolutePos(new BlockPos(27, 1, 20));
        final BlockPos beamOak = helper.absolutePos(new BlockPos(34, 1, 28));
        synthTrunk(level, oak, 6, Blocks.OAK_LOG, Blocks.OAK_LEAVES, leaves);
        synthTrunk(level, birch, 6, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, leaves);
        synthTrunk(level, beamOak, 6, Blocks.OAK_LOG, Blocks.OAK_LEAVES, leaves);
        for (int dx = 1; dx <= 2; dx++)
        {
            level.setBlockAndUpdate(oak.offset(dx, 3, 0), Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
            level.setBlockAndUpdate(birch.offset(-dx, 3, 0), Blocks.BIRCH_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        }
        final List<BlockPos> beam = new ArrayList<>();
        for (int dx = 1; dx <= 3; dx++)
        {
            final BlockPos pos = beamOak.offset(dx, 3, 0);
            level.setBlockAndUpdate(pos, Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
            beam.add(pos);
        }
        placeLeaves(level, leaves);
        final int budget = MineColonies.getConfig().getServer().maxTreeSize.get();
        for (final BlockPos base : List.of(oak, birch))
        {
            final TreeFootprint.Footprint print = TreeFootprint.scan(level, base.above(1), colony, budget);
            helper.assertTrue(print != null && print.skip == TreeFootprint.Skip.NONE && print.logs.size() == 8,
              "the tree at " + base.toShortString() + " does not stand on its own: " + (print == null ? "no footprint" : print.logs.size() + " logs, " + print.skip + " " + print.skipDetail));
        }
        final TreeFootprint.Footprint beamPrint = TreeFootprint.scan(level, beamOak.above(1), colony, budget);
        helper.assertTrue(beamPrint != null && beamPrint.skip == TreeFootprint.Skip.BUILD, "the oak with the beam is no tree next to a build: "
                                                                                         + (beamPrint == null ? "no footprint" : beamPrint.skip + " " + beamPrint.skipDetail));
        final AABB pair = relBox(helper, new BlockPos(19, 1, 17), new BlockPos(31, 10, 24));
        final AABB beamBox = relBox(helper, new BlockPos(31, 1, 25), new BlockPos(38, 10, 32));

        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10), new ItemStack(Items.OAK_SAPLING, 4), new ItemStack(Items.BIRCH_SAPLING, 4));
            final String[] last = {""};
            pump(helper, List.of(worker), FELL_CAP, () -> {
                final int pairLogs = count(level, pair, s -> s.is(BlockTags.LOGS));
                final int beamOakLogs = count(level, beamBox, s -> s.is(Blocks.OAK_LOG));
                final long beamLeft = beam.stream().filter(pos -> level.getBlockState(pos).is(Blocks.SPRUCE_LOG)).count();
                final EntityAIWorkLumberjack ai = ai(worker);
                last[0] = "oak and birch logs " + pairLogs + ", beam oak logs " + beamOakLogs + "/6, beam " + beamLeft + "/3, skip oak " + building.getSkipReason(oak)
                            + " birch " + building.getSkipReason(birch) + " beam oak " + building.getSkipReason(beamOak) + ", stumps " + level.getBlockState(oak).getBlock()
                            + " " + level.getBlockState(birch).getBlock() + (ai == null ? "" : ", give-up note '" + ai.getGiveUpNote() + "', walk at give-up: "
                                                                                                    + ai.getWalkNoteAtGiveUp() + ", hut note '" + building.getLastSkipNote() + "'");
                helper.assertTrue(beamOakLogs == 6 && beamLeft == 3, "the oak with the beam was cut: " + last[0]);
                helper.assertTrue(building.getSkipReason(oak) == null && building.getSkipReason(birch) == null, "a natural tree was counted as left standing: " + last[0]);
                return pairLogs == 0 && level.getBlockState(oak).is(Blocks.OAK_SAPLING) && level.getBlockState(birch).is(Blocks.BIRCH_SAPLING)
                         && building.getSkipReason(beamOak) == BuildingLumberjack.SkipReason.BUILD && tree(worker) == null;
            }, () -> last[0] + ", " + describe(worker), null, "forestry_touching_woods");
        });
    }

    /**
     * An oak sealed in stone (no air around its trunk, the stone ends two above the canopy), given to the Forester: he cannot get to it and gives it up
     * after the give-up time, with the reason in the hut, instead of standing at it for ever. Nothing of it is cut.
     */
    private static void unreachableGiveUp(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry unreachable");
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        final BlockPos base = helper.absolutePos(new BlockPos(26, 1, 20));
        final Map<BlockPos, Block> leaves = new HashMap<>();
        synthTrunk(level, base, 6, Blocks.OAK_LOG, Blocks.OAK_LEAVES, leaves);
        placeLeaves(level, leaves);
        for (final BlockPos pos : BlockPos.betweenClosed(base.offset(-3, 0, -3), base.offset(3, 8, 3)))
        {
            if (level.getBlockState(pos).isAir())
            {
                level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            }
        }
        final AABB box = new AABB(base).inflate(3, 0, 3).expandTowards(0, 9, 0);

        helper.runAfterDelay(20, () -> {
            final Tree tree = new Tree(level, base.above(2), colony);
            helper.assertTrue(tree.isTree() && tree.getSkip() == TreeFootprint.Skip.NONE, "the sealed oak is no tree: " + tree.getSkip() + " " + tree.getSkipDetail());
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10), new ItemStack(Items.OAK_SAPLING, 4));
            helper.assertTrue(worker.getJob() instanceof JobLumberjack, "no lumberjack job");
            ((JobLumberjack) worker.getJob()).setTree(tree);
            final String[] last = {""};
            pump(helper, List.of(worker), 9_000, () -> {
                final EntityAIWorkLumberjack ai = ai(worker);
                final int logs = count(level, box, s -> s.is(Blocks.OAK_LOG));
                last[0] = "oak logs " + logs + "/6, given up " + (ai == null ? -1 : ai.getTreesGivenUp()) + " (" + (ai == null ? "" : ai.getGiveUpNote()) + "), hut reason "
                            + building.getSkipReason(base) + ", hut note '" + building.getLastSkipNote() + "'";
                helper.assertTrue(logs == 6, "the sealed oak was cut: " + last[0]);
                return ai != null && ai.getTreesGivenUp() >= 1 && building.getSkipReason(base) == BuildingLumberjack.SkipReason.UNREACHABLE
                         && !building.getLastSkipNote().isEmpty() && (tree(worker) == null || !tree(worker).getLocation().equals(base));
            }, () -> last[0] + ", " + describe(worker), null, "forestry_unreachable_give_up");
        });
    }

    /**
     * An oak with a fence beside a leaf at the edge of its canopy (the fence touches no log): he fells the oak, the sweep takes every doomed leaf but the
     * one touching the fence, and the fence stays.
     */
    private static void sweepNearBuild(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry sweep build");
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        IColonyManager.getInstance().getCompatibilityManager().connectLeafToSapling(Blocks.OAK_LEAVES, new ItemStack(Items.OAK_SAPLING));
        final BlockPos base = helper.absolutePos(new BlockPos(24, 1, 20));
        final Map<BlockPos, Block> leaves = new HashMap<>();
        synthTrunk(level, base, 6, Blocks.OAK_LOG, Blocks.OAK_LEAVES, leaves);
        placeLeaves(level, leaves);
        final BlockPos edgeLeaf = base.offset(2, 5, 0);
        final BlockPos fence = base.offset(3, 5, 0);
        helper.assertTrue(level.getBlockState(edgeLeaf).is(Blocks.OAK_LEAVES) && level.getBlockState(fence).isAir(), "no free canopy edge at " + edgeLeaf.toShortString());
        level.setBlockAndUpdate(fence, Blocks.OAK_FENCE.defaultBlockState());
        final AABB box = new AABB(base).inflate(3, 0, 3).expandTowards(0, 8, 0);

        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10), new ItemStack(Items.OAK_SAPLING, 4));
            final String[] last = {""};
            pump(helper, List.of(worker), FELL_CAP, () -> {
                final EntityAIWorkLumberjack ai = ai(worker);
                final int logs = count(level, box, s -> s.is(Blocks.OAK_LOG));
                final int naturalLeaves = count(level, box, ForestryGameTests::isTreeLeaf);
                last[0] = "oak logs " + logs + "/6, natural leaves " + naturalLeaves + ", edge leaf " + level.getBlockState(edgeLeaf).getBlock() + ", fence "
                            + level.getBlockState(fence).getBlock() + ", sweep kept " + (ai == null ? -1 : ai.getSweepKept());
                helper.assertTrue(level.getBlockState(fence).is(Blocks.OAK_FENCE), "the fence was taken: " + last[0]);
                helper.assertTrue(level.getBlockState(edgeLeaf).is(Blocks.OAK_LEAVES), "the leaf touching the fence was swept: " + last[0]);
                return logs == 0 && naturalLeaves == 1 && tree(worker) == null && ai != null && ai.getSweepKept() >= 1;
            }, () -> last[0] + ", " + describe(worker), null, "forestry_sweep_near_build");
        });
    }

    /**
     * A felled oak's stump falls inside the box of a hut placed after the felling: he does not replant it, the sapling stays with him, and he lets the
     * tree go.
     */
    private static void replantProtected(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry replant protected");
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        IColonyManager.getInstance().getCompatibilityManager().connectLeafToSapling(Blocks.OAK_LEAVES, new ItemStack(Items.OAK_SAPLING));
        final BlockPos rel = new BlockPos(24, 1, 22);
        final BlockPos base = helper.absolutePos(rel);
        final Map<BlockPos, Block> leaves = new HashMap<>();
        synthTrunk(level, base, 6, Blocks.OAK_LOG, Blocks.OAK_LEAVES, leaves);
        placeLeaves(level, leaves);
        final Predicate<ItemStack> sapling = stack -> stack.is(Items.OAK_SAPLING);
        final int start = 4;
        final AABB plot = relBox(helper, new BlockPos(-8, 0, -8), new BlockPos(40, 12, 40));

        helper.runAfterDelay(20, () -> {
            final Tree tree = new Tree(level, base.above(2), colony);
            helper.assertTrue(tree.isTree() && tree.getSkip() == TreeFootprint.Skip.NONE && tree.getStumpLocations().equals(List.of(base)),
              "the oak is no one-stump tree: " + tree.getSkip() + " stumps " + tree.getStumpLocations());
            // Felled already, without a drop; then a co-manager puts a hut whose box covers the stump
            for (final BlockPos pos : BlockPos.betweenClosed(base.offset(-3, 0, -3), base.offset(3, 8, 3)))
            {
                final BlockState state = level.getBlockState(pos);
                if (state.is(Blocks.OAK_LOG) || state.is(Blocks.OAK_LEAVES))
                {
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                }
            }
            final IBuilding newHut = MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockHutLumberjack, rel.offset(2, 0, 0), "fundamentals/lumberjack1.blueprint");
            helper.assertTrue(newHut.isInBuilding(base), "the new hut's box does not cover the stump at " + base.toShortString());
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10), new ItemStack(Items.OAK_SAPLING, start));
            helper.assertTrue(worker.getJob() instanceof JobLumberjack, "no lumberjack job");
            ((JobLumberjack) worker.getJob()).setTree(tree);
            final String[] last = {""};
            pump(helper, List.of(worker), 12_000, () -> {
                final int planted = count(level, plot, state -> state.is(Blocks.OAK_SAPLING));
                final int held = InventoryUtils.getItemCountInItemHandler(worker.getInventory(), sapling) + InventoryUtils.getCountFromBuilding(building, sapling);
                final int onGround = level.getEntitiesOfClass(ItemEntity.class, plot).stream().filter(e -> sapling.test(e.getItem())).mapToInt(e -> e.getItem().getCount()).sum();
                last[0] = "stump " + level.getBlockState(base).getBlock() + ", planted " + planted + ", held " + held + ", on the ground " + onGround;
                helper.assertTrue(!level.getBlockState(base).is(Blocks.OAK_SAPLING), "the stump inside the new hut's box was replanted: " + last[0]);
                return tree(worker) == null && planted == 0 && held + onGround == start;
            }, () -> last[0] + ", " + describe(worker), null, "forestry_replant_protected");
        });
    }

    // ---------------------------------------------------------------- player blocks on trees, leaf walls (improve-all r3)

    /**
     * r2 Sol H1: a player's decoration joined to a mushroom's cap is no cap of the mushroom. A huge red mushroom; against its cap a player built a row of
     * red mushroom blocks out to a plank on a fence post (the blocks next to the cap touch only caps and air), and put a shroomlight on top of the cap. He
     * fells the stem; the row, the plank, the post and the shroomlight all stay, though the first blocks of the row lie within the sweep's reach.
     */
    private static void capDecoration(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry cap decoration");
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        final Grown mushroom = grow(helper, new TreeSpec("huge red mushroom", new BlockPos(24, 1, 18), false, Blocks.RED_MUSHROOM, Blocks.MYCELIUM, null, null), 11L);
        final BlockPos stem = mushroom.stumps().get(0);
        // the cap's outer block on +x, on the highest level the cap reaches out there
        BlockPos edge = null;
        for (int y = 8; y >= 1 && edge == null; y--)
        {
            for (int dx = 4; dx >= 1; dx--)
            {
                if (level.getBlockState(stem.offset(dx, y, 0)).is(Blocks.RED_MUSHROOM_BLOCK))
                {
                    edge = stem.offset(dx, y, 0);
                    break;
                }
            }
        }
        helper.assertTrue(edge != null, "the mushroom has no cap on +x");
        final List<BlockPos> row = new ArrayList<>();
        for (int i = 1; i <= 4; i++)
        {
            final BlockPos pos = edge.east(i);
            level.setBlockAndUpdate(pos, Blocks.RED_MUSHROOM_BLOCK.defaultBlockState());
            row.add(pos);
        }
        final BlockPos plank = edge.east(5);
        level.setBlockAndUpdate(plank, Blocks.OAK_PLANKS.defaultBlockState());
        for (BlockPos post = plank.below(); post.getY() >= stem.getY(); post = post.below())
        {
            level.setBlockAndUpdate(post, Blocks.OAK_FENCE.defaultBlockState());
        }
        BlockPos top = stem.above();
        while (TreeFootprint.isTreeBlock(level.getBlockState(top)) || TreeFootprint.isCap(level.getBlockState(top)))
        {
            top = top.above();
        }
        final BlockPos shroomlight = top;
        level.setBlockAndUpdate(shroomlight, Blocks.SHROOMLIGHT.defaultBlockState());
        final AABB stemBox = new AABB(stem).expandTowards(0, 12, 0);

        helper.runAfterDelay(20, () -> {
            final Tree tree = new Tree(level, stem.above(1), colony);
            helper.assertTrue(tree.isTree() && tree.getSkip() == TreeFootprint.Skip.NONE, "the mushroom is no tree: " + tree.getSkip() + " " + tree.getSkipDetail());
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10));
            helper.assertTrue(worker.getJob() instanceof JobLumberjack, "no lumberjack job");
            ((JobLumberjack) worker.getJob()).setTree(tree);
            final String[] last = {""};
            pump(helper, List.of(worker), 12_000, () -> {
                final int stems = count(level, stemBox, TreeFootprint::isTreeBlock);
                final long rowLeft = row.stream().filter(pos -> level.getBlockState(pos).is(Blocks.RED_MUSHROOM_BLOCK)).count();
                last[0] = "stem blocks " + stems + ", decoration row " + rowLeft + "/4, plank " + level.getBlockState(plank).getBlock() + ", shroomlight "
                            + level.getBlockState(shroomlight).getBlock();
                helper.assertTrue(rowLeft == 4 && level.getBlockState(plank).is(Blocks.OAK_PLANKS), "the decoration joined to the cap was taken: " + last[0]);
                helper.assertTrue(level.getBlockState(shroomlight).is(Blocks.SHROOMLIGHT), "the shroomlight on the cap was taken: " + last[0]);
                return stems == 0 && tree(worker) == null;
            }, () -> last[0] + ", " + describe(worker), null, "forestry_cap_decoration");
        });
    }

    /**
     * r3 Sol H1: a cap a player adds to a fungus after the Forester chose it is the player's. A huge crimson fungus; once it is chosen a player puts a
     * shroomlight on top of its cap, and while he fells it a nether wart block against the cap (both of the fungus's own kinds, both in the sweep's reach,
     * touching only caps and air). The fungus goes, cap and all; the two added blocks stay.
     */
    private static void capAddedFungus(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry cap added");
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        final BlockPos base = helper.absolutePos(new BlockPos(24, 1, 20));
        for (int dx = -3; dx <= 4; dx++)
        {
            for (int dz = -3; dz <= 4; dz++)
            {
                level.setBlockAndUpdate(base.offset(dx, -1, dz), Blocks.CRIMSON_NYLIUM.defaultBlockState());
            }
        }
        level.setChunkForced(base.getX() >> 4, base.getZ() >> 4, true);
        level.setBlockAndUpdate(base, Blocks.CRIMSON_FUNGUS.defaultBlockState());
        ((net.minecraft.world.level.block.NetherFungusBlock) Blocks.CRIMSON_FUNGUS).performBonemeal(level, RandomSource.create(7L), base, level.getBlockState(base),
          net.minecraft.world.level.block.BonemealSource.INTERACTION);
        helper.assertTrue(TreeFootprint.isTreeBlock(level.getBlockState(base)), "the crimson fungus did not grow: " + level.getBlockState(base));
        final AABB area = new AABB(base).inflate(8, 0, 8).expandTowards(0, 30, 0);

        helper.runAfterDelay(20, () -> {
            final Tree tree = new Tree(level, base.above(1), colony);
            helper.assertTrue(tree.isTree() && tree.getSkip() == TreeFootprint.Skip.NONE && tree.isNetherTree(),
              "the fungus is no cap tree: " + tree.getSkip() + " " + tree.getSkipDetail() + " nether " + tree.isNetherTree());
            final BlockPos light = addedCapSpot(level, tree, null);
            helper.assertTrue(light != null, "no spot on the cap for the shroomlight");
            level.setBlockAndUpdate(light, Blocks.SHROOMLIGHT.defaultBlockState());
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10));
            helper.assertTrue(worker.getJob() instanceof JobLumberjack, "no lumberjack job");
            ((JobLumberjack) worker.getJob()).setTree(tree);
            final BlockPos[] wart = {null};
            final String[] last = {""};
            pump(helper, List.of(worker), 12_000, () -> {
                final int stems = count(level, area, TreeFootprint::isTreeBlock);
                final int caps = count(level, area, TreeFootprint::isCap);
                final boolean lightStays = level.getBlockState(light).is(Blocks.SHROOMLIGHT);
                final boolean wartStays = wart[0] != null && level.getBlockState(wart[0]).is(Blocks.NETHER_WART_BLOCK);
                last[0] = "stems " + stems + ", caps " + caps + ", added shroomlight " + level.getBlockState(light).getBlock() + ", added wart "
                            + (wart[0] == null ? "not yet" : level.getBlockState(wart[0]).getBlock());
                helper.assertTrue(lightStays, "the shroomlight added after the fungus was chosen was taken: " + last[0]);
                helper.assertTrue(wart[0] == null || wartStays, "the wart block added during the felling was taken: " + last[0]);
                // only the two added blocks are left of the cap
                return stems == 0 && tree(worker) == null && wartStays && caps == 2;
            }, () -> last[0] + ", " + describe(worker), () -> {
                if (wart[0] == null && tree.isStarted())
                {
                    // during the felling, a co-manager adds a wart block against the cap
                    wart[0] = addedCapSpot(level, tree, light);
                    if (wart[0] != null)
                    {
                        level.setBlockAndUpdate(wart[0], Blocks.NETHER_WART_BLOCK.defaultBlockState());
                    }
                }
            }, "forestry_cap_added_fungus");
        });
    }

    /**
     * An air block in the sweep's reach of the tree's stems, next to its cap (faces, edges or corners) and touching nothing but caps and air: where a player
     * adds a block of the cap's kind. The highest such spot.
     */
    private static BlockPos addedCapSpot(final ServerLevel level, final Tree tree, final BlockPos not)
    {
        final AABB box = tree.getBox();
        if (box == null)
        {
            return null;
        }
        BlockPos best = null;
        for (final BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX - 3, box.minY, box.minZ - 3), BlockPos.containing(box.maxX + 2, box.maxY + 3, box.maxZ + 2)))
        {
            if (!level.getBlockState(pos).isAir() || (not != null && pos.distManhattan(not) <= 2) || (best != null && pos.getY() <= best.getY()))
            {
                continue;
            }
            boolean nearCap = false;
            for (final BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1)))
            {
                nearCap |= TreeFootprint.isCap(level.getBlockState(near));
            }
            boolean clean = true;
            for (final Direction dir : Direction.values())
            {
                final BlockState state = level.getBlockState(pos.relative(dir));
                clean &= state.isAir() || TreeFootprint.isCap(state);
            }
            if (nearCap && clean)
            {
                best = pos.immutable();
            }
        }
        return best;
    }

    /**
     * r3 Fable N12: two huge brown mushrooms seven blocks apart, their flat caps grown into one. He fells both; the joined cap goes with the second, none of
     * it is left floating, and he gives neither up (the planner never walks him at a cap the clearing refuses).
     */
    private static void joinedCaps(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry joined caps");
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        // the same seed grows the same height, so the caps meet on one level
        final Grown a = grow(helper, new TreeSpec("brown mushroom a", new BlockPos(20, 1, 20), false, Blocks.BROWN_MUSHROOM, Blocks.MYCELIUM, null, null), 5L);
        final Grown b = grow(helper, new TreeSpec("brown mushroom b", new BlockPos(27, 1, 20), false, Blocks.BROWN_MUSHROOM, Blocks.MYCELIUM, null, null), 5L);
        final AABB both = a.box().minmax(b.box());
        final BlockPos stemA = a.stumps().get(0);
        final BlockPos stemB = b.stumps().get(0);
        boolean joined = false;
        for (int y = 3; y <= 14 && !joined; y++)
        {
            joined = level.getBlockState(stemA.offset(3, y, 0)).is(Blocks.BROWN_MUSHROOM_BLOCK) && level.getBlockState(stemB.offset(-3, y, 0)).is(Blocks.BROWN_MUSHROOM_BLOCK);
        }
        helper.assertTrue(joined, "fixture: the two caps do not meet");

        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10));
            final String[] last = {""};
            pump(helper, List.of(worker), 24_000, () -> {
                final int stems = count(level, both, TreeFootprint::isTreeBlock);
                final int caps = count(level, both, TreeFootprint::isCap);
                // which half the caps left stand over (r4 Fable N12: the sweep note says why)
                final double mid = (stemA.getX() + stemB.getX() + 1) / 2.0D;
                final int capsByA = count(level, new AABB(both.minX, both.minY, both.minZ, mid, both.maxY, both.maxZ), TreeFootprint::isCap);
                final EntityAIWorkLumberjack ai = ai(worker);
                last[0] = "stems " + stems + ", caps " + caps + " (" + capsByA + " on a's side), given up " + (ai == null ? "?" : ai.getTreesGivenUp() + " (" + ai.getGiveUpNote() + ")")
                            + (ai == null ? "" : ", last cap sweep: " + ai.getSweepNote());
                helper.assertTrue(ai == null || ai.getTreesGivenUp() == 0, "he gave a mushroom up: " + last[0]);
                return stems == 0 && caps == 0 && tree(worker) == null;
            }, () -> last[0] + ", " + describe(worker), null, "forestry_joined_caps");
        });
    }

    /**
     * r2 Fable N3: the planner never plans through a leaf he may not clear. An oak sealed in stone, its only way in a tunnel whose mouth is closed by two
     * natural oak leaves next to glass in the wall (a player's block: he may not clear them). He gives the tree up at once with that reason, not
     * after two minutes at the leaves, and a tree given up again is left be longer each time. Nothing is cut, the leaves and the glass stay.
     */
    private static void leafWall(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry leaf wall");
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        final BlockPos base = helper.absolutePos(new BlockPos(26, 1, 20));
        final Map<BlockPos, Block> leaves = new HashMap<>();
        synthTrunk(level, base, 6, Blocks.OAK_LOG, Blocks.OAK_LEAVES, leaves);
        placeLeaves(level, leaves);
        for (final BlockPos pos : BlockPos.betweenClosed(base.offset(-3, 0, -3), base.offset(3, 8, 3)))
        {
            if (level.getBlockState(pos).isAir())
            {
                level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            }
        }
        // the tunnel from the stump out to the east, its mouth closed by natural leaves, glass in the wall beside them
        for (int dx = 1; dx <= 3; dx++)
        {
            for (int dy = 0; dy <= 1; dy++)
            {
                level.setBlockAndUpdate(base.offset(dx, dy, 0), dx == 3 ? Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, LeavesBlock.DECAY_DISTANCE)
                                                                     : Blocks.AIR.defaultBlockState());
            }
        }
        final List<BlockPos> mouth = List.of(base.offset(3, 0, 0), base.offset(3, 1, 0));
        final BlockPos glass = base.offset(3, 0, 1);
        level.setBlockAndUpdate(glass, Blocks.GLASS.defaultBlockState());
        level.setBlockAndUpdate(glass.above(), Blocks.GLASS.defaultBlockState());
        final AABB box = new AABB(base).inflate(3, 0, 3).expandTowards(0, 9, 0);

        helper.runAfterDelay(20, () -> {
            final Tree tree = new Tree(level, base.above(2), colony);
            helper.assertTrue(tree.isTree() && tree.getSkip() == TreeFootprint.Skip.NONE, "the sealed oak is no tree: " + tree.getSkip() + " " + tree.getSkipDetail());
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10), new ItemStack(Items.OAK_SAPLING, 4));
            helper.assertTrue(worker.getJob() instanceof JobLumberjack, "no lumberjack job");
            ((JobLumberjack) worker.getJob()).setTree(tree);
            final long start = level.getGameTime();
            final String[] last = {""};
            pump(helper, List.of(worker), 6_000, () -> {
                final EntityAIWorkLumberjack ai = ai(worker);
                final int logs = count(level, box, s -> s.is(Blocks.OAK_LOG));
                final long mouthLeft = mouth.stream().filter(pos -> level.getBlockState(pos).is(Blocks.OAK_LEAVES)).count();
                last[0] = "oak logs " + logs + "/6, mouth leaves " + mouthLeft + "/2, glass " + level.getBlockState(glass).getBlock() + ", given up "
                            + (ai == null ? -1 : ai.getTreesGivenUp()) + " (" + (ai == null ? "" : ai.getGiveUpNote()) + "), hut reason " + building.getSkipReason(base);
                helper.assertTrue(logs == 6 && mouthLeft == 2 && level.getBlockState(glass).is(Blocks.GLASS), "the walled oak or its leaf wall was cut: " + last[0]);
                if (ai == null || ai.getTreesGivenUp() < 1)
                {
                    return false;
                }
                final long waited = level.getGameTime() - start;
                helper.assertTrue(ai.getGiveUpNote().contains("leaves"), "given up without the reason of the leaves: " + last[0]);
                helper.assertTrue(waited < 2 * 60 * 20L, "stood at the leaf wall for " + waited + " ticks before giving up");
                helper.assertTrue(building.getSkipReason(base) == BuildingLumberjack.SkipReason.UNREACHABLE, "the hut does not count it: " + last[0]);
                helper.assertTrue(com.minecolonies.core.entity.ai.workers.util.TreeClaims.giveUpBlockTicks(2)
                                    == 2 * com.minecolonies.core.entity.ai.workers.util.TreeClaims.giveUpBlockTicks(1), "a tree given up again is not left be longer");
                return true;
            }, () -> last[0] + ", " + describe(worker), null, "forestry_leaf_wall");
        });
    }

    /**
     * r2 Fable N5: a beam or floor of the tree's own wood resting on it is a player's work, as a post of another wood is. Oak A carries a 3 by 3 floor of
     * oak logs, oak B a level beam of five oak logs that ends in nothing; oak C has a natural branch of two level logs. A and B stay whole and count as next
     * to a build, C is felled and replanted. Oak D (scan only) has a level branch of seven logs that ends in its leaves, as cherry, fancy oak and giant branches
     * do: a whole tree, no beam.
     */
    private static void sameWoodBeam(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "forestry same wood beam");
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        IColonyManager.getInstance().getCompatibilityManager().connectLeafToSapling(Blocks.OAK_LEAVES, new ItemStack(Items.OAK_SAPLING));
        final Map<BlockPos, Block> leaves = new HashMap<>();
        final BlockPos floorOak = helper.absolutePos(new BlockPos(22, 1, 24));
        final BlockPos beamOak = helper.absolutePos(new BlockPos(28, 1, 14));
        final BlockPos branchOak = helper.absolutePos(new BlockPos(34, 1, 25));
        final BlockPos longOak = helper.absolutePos(new BlockPos(36, 1, 4));
        for (final BlockPos base : List.of(floorOak, beamOak, branchOak, longOak))
        {
            synthTrunk(level, base, 6, Blocks.OAK_LOG, Blocks.OAK_LEAVES, leaves);
        }
        final BlockState level_x = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        for (int dx = 1; dx <= 3; dx++)
        {
            for (int dz = -1; dz <= 1; dz++)
            {
                level.setBlockAndUpdate(floorOak.offset(dx, 3, dz), level_x);
            }
        }
        for (int dx = 1; dx <= TreeFootprint.BEAM_RUN; dx++)
        {
            level.setBlockAndUpdate(beamOak.offset(dx, 3, 0), level_x);
        }
        for (int dx = 1; dx <= 2; dx++)
        {
            level.setBlockAndUpdate(branchOak.offset(dx, 3, 0), level_x);
        }
        final int longRun = TreeFootprint.BEAM_RUN + 2;
        for (int dx = 1; dx <= longRun; dx++)
        {
            level.setBlockAndUpdate(longOak.offset(dx, 3, 0), level_x);
        }
        for (final BlockPos pos : BlockPos.betweenClosed(longOak.offset(longRun - 1, 3, -1), longOak.offset(longRun + 1, 5, 1)))
        {
            leaves.putIfAbsent(pos.immutable(), Blocks.OAK_LEAVES);
        }
        placeLeaves(level, leaves);
        final int budget = MineColonies.getConfig().getServer().maxTreeSize.get();
        for (final BlockPos base : List.of(floorOak, beamOak))
        {
            final TreeFootprint.Footprint print = TreeFootprint.scan(level, base.above(1), colony, budget);
            helper.assertTrue(print != null && print.skip == TreeFootprint.Skip.BUILD, "the oak at " + base.toShortString() + " with logs built on is no tree next to a build: "
                                                                                      + (print == null ? "no footprint" : print.skip + " " + print.skipDetail));
        }
        final TreeFootprint.Footprint natural = TreeFootprint.scan(level, branchOak.above(1), colony, budget);
        helper.assertTrue(natural != null && natural.skip == TreeFootprint.Skip.NONE && natural.logs.size() == 8,
          "the oak with a natural branch is not a whole tree: " + (natural == null ? "no footprint" : natural.logs.size() + " logs, " + natural.skip + " " + natural.skipDetail));
        final TreeFootprint.Footprint longBranch = TreeFootprint.scan(level, longOak.above(1), colony, budget);
        helper.assertTrue(longBranch != null && longBranch.skip == TreeFootprint.Skip.NONE && longBranch.logs.size() == 6 + longRun,
          "the oak with a long level branch that ends in its leaves is not a whole tree: "
            + (longBranch == null ? "no footprint" : longBranch.logs.size() + " logs, " + longBranch.skip + " " + longBranch.skipDetail));
        // (oak D was only for the scan: gone before the Forester comes)
        for (final BlockPos pos : BlockPos.betweenClosed(longOak.offset(-2, 0, -2), longOak.offset(longRun + 1, 6, 2)))
        {
            if (TreeFootprint.isTreeBlock(level.getBlockState(pos)) || level.getBlockState(pos).is(BlockTags.LEAVES))
            {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
        }
        final AABB floorBox = new AABB(floorOak).inflate(3, 0, 3).expandTowards(0, 8, 0);
        final AABB beamBox = new AABB(beamOak).inflate(3, 0, 3).expandTowards(TreeFootprint.BEAM_RUN, 8, 0);
        final AABB branchBox = new AABB(branchOak).inflate(3, 0, 3).expandTowards(0, 8, 0);

        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10), new ItemStack(Items.OAK_SAPLING, 4));
            final String[] last = {""};
            pump(helper, List.of(worker), FELL_CAP, () -> {
                final int floorLogs = count(level, floorBox, s -> s.is(Blocks.OAK_LOG));
                final int beamLogs = count(level, beamBox, s -> s.is(Blocks.OAK_LOG));
                final int branchLogs = count(level, branchBox, s -> s.is(Blocks.OAK_LOG));
                last[0] = "floor oak logs " + floorLogs + "/15, beam oak logs " + beamLogs + "/" + (6 + TreeFootprint.BEAM_RUN) + ", branch oak logs " + branchLogs
                            + ", skip floor " + building.getSkipReason(floorOak) + " beam " + building.getSkipReason(beamOak) + ", stump " + level.getBlockState(branchOak).getBlock();
                helper.assertTrue(floorLogs == 15 && beamLogs == 6 + TreeFootprint.BEAM_RUN, "an oak with logs built on was cut: " + last[0]);
                return branchLogs == 0 && level.getBlockState(branchOak).is(Blocks.OAK_SAPLING) && building.getSkipReason(floorOak) == BuildingLumberjack.SkipReason.BUILD
                         && building.getSkipReason(beamOak) == BuildingLumberjack.SkipReason.BUILD && tree(worker) == null;
            }, () -> last[0] + ", " + describe(worker), null, "forestry_same_wood_beam");
        });
    }

    // ---------------------------------------------------------------- TreeChop lane

    private static void resetTreeChop(final GameTestHelper helper)
    {
        helper.assertTrue(Compatibility.treeChop.isPresent(), "TreeChop is loaded but the Forester's TreeChop compat is not active");
        Compatibility.treeChop.setEnabled(true);
        MineColonies.getConfig().getServer().lumberjacksUseTreeChop.set(true);
    }

    private static boolean anyChoppedLog(final ServerLevel level, final AABB box)
    {
        return count(level, box, Compatibility.treeChop::isChoppedLog) > 0;
    }

    /**
     * One chop of the grown oak through TreeChop as a player would, with TreeChop on for the chop. A player chops at most once per game tick (TreeChop's
     * recursion guard cancels a second chop in the same tick), so the half chop is two chops on two ticks, and each test has its own player: the
     * tests of a batch start in the same tick.
     */
    private static void halfChop(final GameTestHelper helper, final String name, final Grown oak, final boolean last)
    {
        final ServerLevel level = helper.getLevel();
        final UUID player = UUID.nameUUIDFromBytes(("half chop " + name).getBytes(StandardCharsets.UTF_8));
        final BlockPos base = oak.stumps().get(0);
        final boolean enabled = Compatibility.treeChop.setEnabled(true);
        final TreeChopProxy.ChopOutcome outcome;
        try
        {
            outcome = Compatibility.treeChop.chop(level, base, player, Vec3.atCenterOf(base.north(2)), new ItemStack(Items.IRON_AXE), 1);
        }
        finally
        {
            Compatibility.treeChop.setEnabled(enabled);
        }
        helper.assertTrue(outcome == TreeChopProxy.ChopOutcome.CHOPPED, "the half chop gave " + outcome);
        if (last)
        {
            helper.assertTrue(anyChoppedLog(level, oak.box()), "no chopped log after the half chop");
        }
    }

    /**
     * TreeChop on: the Forester chops chop by chop (chopped logs seen at the base), the whole tree falls through TreeChop, every drop is his, the leaves
     * are gone, the stump is replanted, and the axe wears once per chop.
     */
    private static void treeChopFell(final GameTestHelper helper)
    {
        resetTreeChop(helper);
        treeChopRun(helper, "forestry_treechop_fell", false, true);
    }

    /**
     * A tree a player left half chopped (chopped logs at the base): the Forester finishes it through TreeChop, nothing floats, no chopped log is left.
     */
    private static void treeChopHalfChopped(final GameTestHelper helper)
    {
        resetTreeChop(helper);
        treeChopRun(helper, "forestry_treechop_half_chopped", true, true);
    }

    /**
     * TreeChop turned off in its own config: the Forester uses the normal path (no chop through TreeChop) and still removes the whole tree.
     */
    private static void treeChopDisabledFallback(final GameTestHelper helper)
    {
        resetTreeChop(helper);
        Compatibility.treeChop.setEnabled(false);
        treeChopRun(helper, "forestry_treechop_disabled_fallback", false, false);
    }

    /**
     * The fork setting {@code lumberjacksUseTreeChop} off and a half-chopped tree: the normal path mines the chopped logs too (they drop the original
     * log), so the tree is finished whole.
     */
    private static void treeChopSettingOff(final GameTestHelper helper)
    {
        resetTreeChop(helper);
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
            halfChop(helper, name, oak, false);
            helper.runAfterDelay(1, () -> halfChop(helper, name, oak, true));
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

    /**
     * TreeChop fells only the Forester's own tree, and the build guard keeps a player's logs out of every felling.
     * <ul>
     * <li>Oak A has a player's stripped oak log and a spruce log set at two corners of its trunk base. They touch the trunk only at an edge, so they are no
     * part of his tree and no build signal, but TreeChop takes logs through edges as well: when its tree reaches past his footprint he cuts the oak the
     * normal way. The oak is gone and replanted, both corner logs stand.</li>
     * <li>Oak B has a stripped oak log beam on a stripped post touching its trunk, and a spruce log post touching it on the other side (a player's work
     * built into the tree). Nothing of it is felled: not the oak, not the beam rooted on its stripped post, not the spruce post as a tree of its own.</li>
     * </ul>
     */
    private static void treeChopStaysInFootprint(final GameTestHelper helper)
    {
        resetTreeChop(helper);
        final ServerLevel level = helper.getLevel();
        final String name = "forestry_treechop_stays_in_footprint";
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, name);
        final BuildingLumberjack building = hut(helper, colony, HUT, false);
        final Grown oak = grow(helper, new TreeSpec("oak", new BlockPos(22, 1, 18), false, Blocks.OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.OAK, s -> s.is(Blocks.OAK_SAPLING)), 71L);
        final Grown built = grow(helper, new TreeSpec("built oak", new BlockPos(34, 1, 28), false, Blocks.OAK_SAPLING, Blocks.GRASS_BLOCK, TreeGrower.OAK, null), 72L);
        final BlockPos trunk = oak.stumps().get(0);
        final Map<BlockPos, BlockState> corners = new LinkedHashMap<>();
        corners.put(trunk.offset(1, 0, 1), Blocks.STRIPPED_OAK_LOG.defaultBlockState());
        corners.put(trunk.offset(-1, 0, -1), Blocks.SPRUCE_LOG.defaultBlockState());
        final BlockPos builtTrunk = built.stumps().get(0);
        final Map<BlockPos, BlockState> build = new LinkedHashMap<>();
        for (int i = 1; i <= 4; i++)
        {
            build.put(builtTrunk.offset(i, 1, 0), Blocks.STRIPPED_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        }
        build.put(builtTrunk.offset(4, 0, 0), Blocks.STRIPPED_OAK_LOG.defaultBlockState());
        for (int y = 0; y <= 2; y++)
        {
            build.put(builtTrunk.offset(-1, y, 0), Blocks.SPRUCE_LOG.defaultBlockState());
        }
        final Map<BlockPos, BlockState> all = new LinkedHashMap<>(corners);
        all.putAll(build);
        for (final Map.Entry<BlockPos, BlockState> entry : all.entrySet())
        {
            level.setBlockAndUpdate(entry.getKey(), entry.getValue());
        }
        final Set<BlockPos> treeChopLogs = Compatibility.treeChop.getTreeLogs(level, trunk);
        final boolean reachesPast = treeChopLogs == null || corners.keySet().stream().anyMatch(treeChopLogs::contains);
        Log.getLogger().info("[{}] TreeChop's tree: {} logs, reaches the corner logs: {}", name, treeChopLogs == null ? "unknown" : treeChopLogs.size(), reachesPast);

        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = forester(helper, colony, building, new BlockPos(14, 1, 10), new ItemStack(Items.OAK_SAPLING, 4));
            final String[] last = {""};
            pump(helper, List.of(worker), FELL_CAP, () -> {
                final EntityAIWorkLumberjack ai = ai(worker);
                final long lost = all.entrySet().stream().filter(e -> !level.getBlockState(e.getKey()).is(e.getValue().getBlock())).count();
                final int logs = count(level, oak.box(), s -> s.is(Blocks.OAK_LOG));
                final int builtLogs = count(level, built.box(), s -> s.is(Blocks.OAK_LOG));
                final boolean replanted = level.getBlockState(trunk).is(Blocks.OAK_SAPLING);
                final int refused = ai == null ? 0 : ai.getTreeChopRefusedCount();
                last[0] = "player logs lost " + lost + "/" + all.size() + ", oak logs " + logs + "/" + oak.logs() + ", built oak logs " + builtLogs + "/" + built.logs()
                            + ", replanted " + replanted + ", TreeChop chops " + (ai == null ? 0 : ai.getTreeChopChopCount()) + ", refused " + refused
                            + ", TreeChop's tree reaches the corner logs " + reachesPast;
                helper.assertTrue(lost == 0, "a player's log was felled: " + last[0]);
                helper.assertTrue(builtLogs == built.logs(), "the oak with a build in it was cut: " + last[0]);
                return logs == 0 && replanted && tree(worker) == null && (!reachesPast || refused >= 1);
            }, () -> last[0] + ", " + describe(worker), null, name);
        });
    }
}
