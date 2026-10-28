package com.minecolonies.core.gametest;

import com.ldtteam.structurize.api.compat.itemhandler.wrapper.InvWrapper;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.research.IGlobalResearch;
import com.minecolonies.api.research.IGlobalResearchTree;
import com.minecolonies.api.research.IResearchEffect;
import com.minecolonies.api.research.util.ResearchConstants;
import com.minecolonies.api.research.util.ResearchState;
import com.minecolonies.api.util.BuildingContainers;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.AbstractBuildingContainer;
import com.minecolonies.core.colony.managers.ResearchManager;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import com.minecolonies.core.research.LocalResearch;
import com.minecolonies.core.util.ChunkDataHelper;
import com.minecolonies.api.colony.permissions.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Vanilla tie-in wave 2, the fork side (glob {@code minecolonies:vanilla2_*}, group {@code MINECOLONIES_GAMETEST_ONLY=vanilla2}): UD-19, the client research
 * view drops the effects of a research the colony undid at the next synced snapshot; V-7, a vanilla copper chest inside a building's corners is one of its
 * containers (its stock counts for the request system, the ledger and the hut inventory) and copper golems are kept out of it.
 */
public final class VanillaTieIn2GameTests
{
    private VanillaTieIn2GameTests()
    {
    }

    static final Map<String, Consumer<GameTestHelper>> TESTS = new LinkedHashMap<>();
    static
    {
        TESTS.put("vanilla2_research_snapshot_drops_undone_effects", VanillaTieIn2GameTests::researchSnapshotDropsUndoneEffects);
        TESTS.put("vanilla2_copper_chest_building_container", VanillaTieIn2GameTests::copperChestBuildingContainer);
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

    // ------------------------------------------------------------------ UD-19

    /**
     * The client reads every synced research snapshot into the same view's research manager: a research finished in one snapshot and gone (undone) in the next
     * leaves no effect behind, and reading the same snapshot twice gives the same strength.
     */
    public static void researchSnapshotDropsUndoneEffects(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "vanilla2 research");
        final IGlobalResearch rainman = researchWithEffect(ResearchConstants.WORKING_IN_RAIN);
        helper.assertTrue(rainman != null, "fixture: a research with an effect is loaded");
        final Identifier effect = effectOf(rainman, ResearchConstants.WORKING_IN_RAIN);

        final ResearchManager withIt = new ResearchManager(colony);
        final LocalResearch done = new LocalResearch(rainman.getId(), rainman.getBranch(), rainman.getDepth());
        done.setState(ResearchState.FINISHED);
        withIt.getResearchTree().addResearch(rainman.getBranch(), done);
        final CompoundTag snapshotWith = new CompoundTag();
        withIt.writeToNBT(level.registryAccess(), snapshotWith);
        final CompoundTag snapshotWithout = new CompoundTag();
        new ResearchManager(colony).writeToNBT(level.registryAccess(), snapshotWithout);

        // the view's manager, as ColonyView keeps it across snapshots
        final ResearchManager view = new ResearchManager(colony);
        view.readFromNBT(level.registryAccess(), snapshotWith);
        final double strength = view.getResearchEffects().getEffectStrength(effect);
        helper.assertTrue(strength > 0, "the finished research applies " + effect + ": " + strength);
        view.readFromNBT(level.registryAccess(), snapshotWith);
        helper.assertTrue(view.getResearchEffects().getEffectStrength(effect) == strength, "the same snapshot again: the same strength");

        view.readFromNBT(level.registryAccess(), snapshotWithout);
        helper.assertTrue(view.getResearchEffects().getEffectStrength(effect) == 0, "undone: the next snapshot drops " + effect + ", got "
                                                                                     + view.getResearchEffects().getEffectStrength(effect));
        view.readFromNBT(level.registryAccess(), snapshotWith);
        helper.assertTrue(view.getResearchEffects().getEffectStrength(effect) == strength, "researched again: the effect is back");
        helper.succeed();
    }

    /**
     * The research that grants an effect (any research with an effect when that one is not loaded), walking every branch from its roots.
     */
    private static IGlobalResearch researchWithEffect(final Identifier wanted)
    {
        final IGlobalResearchTree tree = IGlobalResearchTree.getInstance();
        IGlobalResearch any = null;
        for (final Identifier branch : tree.getBranches())
        {
            final Deque<Identifier> open = new ArrayDeque<>(tree.getPrimaryResearch(branch));
            while (!open.isEmpty())
            {
                final IGlobalResearch research = tree.getResearch(branch, open.poll());
                if (research == null)
                {
                    continue;
                }
                for (final IResearchEffect effect : research.getEffects())
                {
                    if (effect.getId().equals(wanted))
                    {
                        return research;
                    }
                    if (any == null)
                    {
                        any = research;
                    }
                }
                open.addAll(research.getChildren());
            }
        }
        return any;
    }

    private static Identifier effectOf(final IGlobalResearch research, final Identifier wanted)
    {
        for (final IResearchEffect effect : research.getEffects())
        {
            if (effect.getId().equals(wanted))
            {
                return wanted;
            }
        }
        return research.getEffects().get(0).getId();
    }

    // ------------------------------------------------------------------ V-7

    /**
     * Every copper chest variant is recognised; one placed inside a hut's corners (the placement event, as a player's hand posts it) and one from a blueprint
     * scan become the hut's containers; their items count for the building (request system counts, reservation ledger, hut inventory, worker transfer); copper
     * golems are kept out (the chest reads as locked, which is what their transport behaviour checks) while every hand still opens it; a chest outside any
     * building and a chest in the warehouse are not containers; breaking the chest takes it out and lets golems in again.
     */
    public static void copperChestBuildingContainer(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final RsFixture f = RsFixture.found(helper, "vanilla2 copper", 0, false);
        final IBuilding hut = f.builder;
        final FakePlayer owner = FakePlayerFactory.getMinecraft(level);
        ChunkDataHelper.claimBuildingChunks((Colony) f.colony, true, hut.getID(), 1, hut.getCorners());

        final List<Block> variants = Blocks.COPPER_CHEST.asList();
        helper.assertTrue(variants.size() == 8, "fixture: 4 oxidation stages, waxed and not: " + variants.size());
        for (final Block block : variants)
        {
            helper.assertTrue(BuildingContainers.isCopperChest(block), block + " is a copper chest");
        }
        helper.assertTrue(!BuildingContainers.isCopperChest(Blocks.CHEST) && !BuildingContainers.isCopperChest(Blocks.BARREL), "plain chests are not");

        final int freeBefore = InventoryUtils.countEmptySlotsInBuilding(hut);

        // a waxed oxidized chest placed by hand next to the hut (inside the corners)
        final BlockPos placed = hut.getID().east();
        final BlockState waxed = variants.get(variants.size() - 1).defaultBlockState();
        helper.assertTrue(BuildingContainers.isCopperChest(waxed), "fixture: the last variant is a copper chest");
        level.setBlockAndUpdate(placed, waxed);
        NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, placed), level.getBlockState(placed.below()), owner));
        helper.assertTrue(hut.getContainers().contains(placed), "the placed copper chest is a container of the hut: " + hut.getContainers());

        // a fresh one from the blueprint path (the builder registers each placed block)
        final BlockPos scanned = hut.getID().west();
        level.setBlockAndUpdate(scanned, variants.get(0).defaultBlockState());
        hut.registerBlockPosition(level.getBlockState(scanned), scanned, level);
        helper.assertTrue(hut.getContainers().contains(scanned), "the scanned copper chest is a container of the hut");

        final ChestBlockEntity chest = BuildingContainers.copperChest(level.getBlockEntity(placed));
        helper.assertTrue(chest != null, "fixture: a chest block entity at " + placed);
        helper.assertTrue(chest.isLocked() && BuildingContainers.keepsGolemsOut(chest), "copper golems skip a building's copper chest (locked)");
        helper.assertTrue(chest.canOpen(owner), "an empty hand still opens it");
        owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT));
        helper.assertTrue(chest.canOpen(owner), "a hand with an item still opens it");
        owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.assertTrue(InventoryUtils.countEmptySlotsInBuilding(hut) == freeBefore + 2 * chest.getContainerSize(),
          "the free slots of both chests count: " + freeBefore + " -> " + InventoryUtils.countEmptySlotsInBuilding(hut));

        // stock in the chest is building stock
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        final ItemStorage iron = new ItemStorage(new ItemStack(Items.IRON_INGOT));
        helper.assertTrue(InventoryUtils.getCountFromBuilding(hut, iron) == 5, "counted for the building: " + InventoryUtils.getCountFromBuilding(hut, iron));
        helper.assertTrue(InventoryUtils.hasBuildingEnoughElseCount(hut, iron, 5) >= 5, "enough for a request of 5");
        helper.assertTrue(InventoryUtils.getCountFromBuilding(hut, (java.util.function.Predicate<ItemStack>) s -> s.is(Items.IRON_INGOT)) == 5, "predicate count");
        helper.assertTrue(ReservationLedger.atBuilding(hut, iron) == 5, "the ledger sees it at the building: " + ReservationLedger.atBuilding(hut, iron));
        helper.assertTrue(InventoryUtils.getBuildingInventory(hut).stream().anyMatch(s -> s.is(Items.IRON_INGOT)), "in the building inventory list");
        helper.assertTrue(((AbstractBuildingContainer) hut).getTileEntity().getPositionOfChestWithItemStack(s -> s.is(Items.IRON_INGOT)) != null,
          "the hut finds the chest holding it");
        final SimpleContainer worker = new SimpleContainer(9);
        helper.assertTrue(InventoryUtils.transferItemStackIntoNextBestSlotInItemHandler(hut, iron, 2, new InvWrapper(worker)), "a worker takes 2 out of it");
        helper.assertTrue(BuildingContainers.count(chest, iron) == 3 && worker.countItem(Items.IRON_INGOT) == 2, "3 left in the chest");

        // outside every building and in the warehouse: not a container, golems not kept out
        final BlockPos outside = helper.absolutePos(new BlockPos(36, 1, 28));
        level.setBlockAndUpdate(outside, variants.get(1).defaultBlockState());
        NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, outside), level.getBlockState(outside.below()), owner));
        final ChestBlockEntity free = BuildingContainers.copperChest(level.getBlockEntity(outside));
        helper.assertTrue(free != null && !free.isLocked(), "a copper chest outside the buildings stays the golems'");
        final BlockPos inWarehouse = f.warehouse.getID().east();
        level.setBlockAndUpdate(inWarehouse, variants.get(2).defaultBlockState());
        f.warehouse.registerBlockPosition(level.getBlockState(inWarehouse), inWarehouse, level);
        helper.assertTrue(!f.warehouse.getContainers().contains(inWarehouse), "the warehouse keeps racks only");
        helper.assertTrue(!BuildingContainers.copperChest(level.getBlockEntity(inWarehouse)).isLocked(), "and leaves the chest alone");

        // a double chest: the half joined onto a container is one too; a player without permission cannot join one onto it (also from outside)
        final BlockPos max = new BlockPos(Math.max(hut.getCorners().getA().getX(), hut.getCorners().getB().getX()), hut.getID().getY(),
          Math.max(hut.getCorners().getA().getZ(), hut.getCorners().getB().getZ()));
        final BlockPos first = max.offset(1, 0, 1);
        final BlockPos second = first.east();
        final Block copper = variants.get(0);
        level.setBlockAndUpdate(first, copper.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
        NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, first), level.getBlockState(first.below()), owner));
        helper.assertTrue(hut.getContainers().contains(first), "fixture: the first half (inside the corners' margin) is a container");
        BlockState joined = null;
        for (final ChestType type : new ChestType[] {ChestType.LEFT, ChestType.RIGHT})
        {
            final BlockState candidate = copper.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, type);
            if (ChestBlock.getConnectedDirection(candidate) == Direction.WEST)
            {
                joined = candidate;
            }
        }
        helper.assertTrue(joined != null, "fixture: a half that joins to the west");
        level.setBlockAndUpdate(second, joined);
        final net.minecraft.server.level.ServerPlayer rival = MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper);
        helper.assertTrue(!f.colony.getPermissions().hasPermission(rival, Action.PLACE_BLOCKS), "fixture: the rival may not place blocks here");
        final BlockEvent.EntityPlaceEvent byRival = new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, second), level.getBlockState(second.below()), rival);
        ((Colony) f.colony).getCopperChestHandler().refuseForeignCopperChestJoin(byRival);
        helper.assertTrue(byRival.isCanceled(), "a rival cannot join a chest onto a building's copper chest");
        NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, second), level.getBlockState(second.below()), owner));
        helper.assertTrue(hut.getContainers().contains(second) && BuildingContainers.copperChest(level.getBlockEntity(second)).isLocked(),
          "the owner's joined half is a container too, golems kept out of it");

        // the hut's combined inventory (rebuilt on the hut's next tick) holds the chest's stock
        helper.runAfterDelay(2, () -> {
            final int inHut = InventoryUtils.getItemCountInItemHandler(hut.getItemHandlerCap(), s -> s.is(Items.IRON_INGOT));
            helper.assertTrue(inHut == 3, "the hut inventory holds the chest's stock: " + inHut);

            // broken by hand: no longer a container, golems may use it again (cleared before it drops)
            NeoForge.EVENT_BUS.post(new BreakBlockEvent(level, placed, level.getBlockState(placed), owner));
            helper.assertTrue(!hut.getContainers().contains(placed), "a broken chest leaves the hut");
            helper.assertTrue(!chest.isLocked(), "and is open to copper golems again");
            hut.removeContainerPosition(scanned);
            helper.assertTrue(!BuildingContainers.copperChest(level.getBlockEntity(scanned)).isLocked(), "removing a container lets golems in again");
            helper.succeed();
        });
    }
}
