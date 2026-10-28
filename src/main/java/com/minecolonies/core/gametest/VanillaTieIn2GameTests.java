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
import com.minecolonies.core.colony.buildings.CopperChests;
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
        TESTS.put("vanilla2_copper_chest_double_chest_is_held_whole", VanillaTieIn2GameTests::copperChestDoubleChestIsHeldWhole);
        TESTS.put("vanilla2_copper_chest_removal_reload_and_release", VanillaTieIn2GameTests::copperChestRemovalReloadAndRelease);
        TESTS.put("vanilla2_rack_position_and_copper_stock_view", VanillaTieIn2GameTests::rackPositionAndCopperStockView);
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

    /**
     * A copper chest state of the given block, facing north, that joins its other half in the given direction.
     */
    private static BlockState half(final GameTestHelper helper, final Block copper, final Direction toward)
    {
        for (final ChestType type : new ChestType[] {ChestType.LEFT, ChestType.RIGHT})
        {
            final BlockState candidate = copper.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, type);
            if (ChestBlock.getConnectedDirection(candidate) == toward)
            {
                return candidate;
            }
        }
        helper.fail("fixture: no half that joins to the " + toward);
        return null;
    }

    private static BlockEvent.EntityPlaceEvent place(final ServerLevel level, final BlockPos pos, final net.minecraft.world.entity.Entity placer)
    {
        final BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, pos), level.getBlockState(pos.below()), placer);
        NeoForge.EVENT_BUS.post(event);
        return event;
    }

    private static boolean locked(final ServerLevel level, final BlockPos pos)
    {
        final ChestBlockEntity chest = BuildingContainers.copperChest(level.getBlockEntity(pos));
        return chest != null && BuildingContainers.keepsGolemsOut(chest);
    }

    /**
     * How many of an item lie on the ground around a position (and removes them).
     */
    private static int pickUp(final ServerLevel level, final BlockPos pos, final net.minecraft.world.item.Item item)
    {
        int count = 0;
        for (final net.minecraft.world.entity.item.ItemEntity entity : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
          new net.minecraft.world.phys.AABB(pos).inflate(8)))
        {
            if (entity.getItem().is(item))
            {
                count += entity.getItem().getCount();
                entity.discard();
            }
        }
        return count;
    }

    /**
     * V-7, review M-1/S1 and L-3: a double copper chest is held whole or not at all. A half placed inside the corners onto a free half just outside them, and
     * a half placed just outside onto a free half inside, both take both halves (both locked, so no golem reaches the stock through an open half); a chest
     * placed before the building took it (no placement event) is taken at the building's check; a half joined onto a container chest from a chunk the colony
     * does not own is refused, and a half placed onto a free chest across the border leaves both halves plain chests.
     */
    public static void copperChestDoubleChestIsHeldWhole(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final RsFixture f = RsFixture.found(helper, "vanilla2 copper double", 0, false);
        final AbstractBuildingContainer hut = (AbstractBuildingContainer) f.builder;
        final FakePlayer owner = FakePlayerFactory.getMinecraft(level);
        ChunkDataHelper.claimBuildingChunks((Colony) f.colony, true, hut.getID(), 1, hut.getCorners());
        final Block copper = Blocks.COPPER_CHEST.asList().get(0);
        final BlockPos a = hut.getCorners().getA();
        final BlockPos b = hut.getCorners().getB();
        final int minX = Math.min(a.getX(), b.getX());
        final int maxX = Math.max(a.getX(), b.getX());
        final int minZ = Math.min(a.getZ(), b.getZ());
        final int y = hut.getID().getY();
        final BlockState single = copper.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        // fixture hygiene: the test's chest spots are no building's containers (a blueprint rack there would be one)
        for (final BlockPos spot : new BlockPos[] {new BlockPos(minX - 1, y, minZ), new BlockPos(minX - 2, y, minZ), new BlockPos(minX - 1, y, minZ + 2),
          new BlockPos(minX - 2, y, minZ + 2), new BlockPos(minX - 1, y, minZ + 4)})
        {
            CopperChests.forget(f.colony, spot, null);
        }

        // order 1: a free half just outside the corners' margin, then a half inside joined onto it (west of the hut, away from the warehouse)
        final BlockPos in1 = new BlockPos(minX - 1, y, minZ);
        final BlockPos out1 = in1.west();
        level.setBlockAndUpdate(out1, single);
        helper.assertTrue(CopperChests.holder(f.colony, out1, null) == null, "fixture: a free chest outside the corners is not a container");
        level.setBlockAndUpdate(in1, half(helper, copper, Direction.WEST));
        helper.assertTrue(!place(level, in1, owner).isCanceled(), "the owner's join stands");
        // the smallest building whose corners contain the inside half holds the pair (the town hall stands close by)
        final AbstractBuildingContainer holder1 = CopperChests.holder(f.colony, in1, null);
        helper.assertTrue(holder1 != null && holder1 == CopperChests.holder(f.colony, out1, null), "one building holds both halves: " + holder1);
        helper.assertTrue(locked(level, in1) && locked(level, out1), "both halves keep golems out");

        // order 2: a free half inside the corners, then a half just outside joined onto it
        final BlockPos in2 = new BlockPos(minX - 1, y, minZ + 2);
        final BlockPos out2 = in2.west();
        level.setBlockAndUpdate(in2, single);
        level.setBlockAndUpdate(out2, half(helper, copper, Direction.EAST));
        helper.assertTrue(!place(level, out2, owner).isCanceled(), "the owner's join stands");
        final AbstractBuildingContainer holder2 = CopperChests.holder(f.colony, in2, null);
        helper.assertTrue(holder2 != null && holder2 == CopperChests.holder(f.colony, out2, null) && locked(level, in2) && locked(level, out2),
          "one building holds both halves, both locked: " + holder2);

        // a copper chest that stood inside the corners before (no placement event) is taken at the building's check; the pairs stay
        final BlockPos pre = new BlockPos(minX - 1, y, minZ + 4);
        level.setBlockAndUpdate(pre, single);
        helper.assertTrue(CopperChests.holder(f.colony, pre, null) == null, "fixture: not a container yet");
        for (final IBuilding building : new java.util.ArrayList<>(f.colony.getServerBuildingManager().getBuildings().values()))
        {
            if (building instanceof final AbstractBuildingContainer container)
            {
                CopperChests.reconcile(container);
            }
        }
        helper.assertTrue(CopperChests.holder(f.colony, pre, null) != null && locked(level, pre), "the buildings' check takes the chest that was there before");
        helper.assertTrue(CopperChests.holder(f.colony, in1, null) == holder1 && CopperChests.holder(f.colony, out1, null) == holder1
                            && CopperChests.holder(f.colony, in2, null) == holder2 && CopperChests.holder(f.colony, out2, null) == holder2
                            && locked(level, out1) && locked(level, out2), "the pairs stay with their buildings");

        // across a border: a chunk next to the hut's chunk owned by another colony
        final int top = Math.max(a.getY(), b.getY()) + 3;
        final BlockPos p = new BlockPos(((hut.getID().getX() >> 4) << 4) + 15, top, hut.getID().getZ());
        final BlockPos q = p.east();
        final net.minecraft.world.level.chunk.LevelChunk foreignChunk = level.getChunkAt(q);
        final com.minecolonies.api.colony.claim.IChunkClaimData claim =
          com.minecolonies.api.colony.IColonyManager.getInstance().getClaimData(level.dimension(), foreignChunk.getPos());
        final int ownerBefore = claim == null ? 0 : claim.getOwningColony();
        level.setBlockAndUpdate(p, single);
        helper.assertTrue(hut.addCopperChest(p, level) && locked(level, p), "fixture: a container chest at the edge of the hut's chunk");
        if (claim != null)
        {
            claim.setOwningColony(f.colony.getID() + 7777, foreignChunk);
        }
        try
        {
            level.setBlockAndUpdate(q, half(helper, copper, Direction.WEST));
            helper.assertTrue(place(level, q, owner).isCanceled(), "a half joined onto a container chest from across the border is refused, also for the owner");
            helper.assertTrue(CopperChests.holder(f.colony, q, null) == null, "and is no container");
            level.setBlockAndUpdate(q, Blocks.AIR.defaultBlockState());
            hut.removeContainerPosition(p);
            helper.assertTrue(!locked(level, p), "fixture: the edge chest is free again");

            // a free chest across the border, the owner's half joined onto it from the colony's side: neither half is the colony's
            level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(q, single);
            level.setBlockAndUpdate(p, half(helper, copper, Direction.EAST));
            place(level, p, owner);
            helper.assertTrue(CopperChests.holder(f.colony, p, null) == null && CopperChests.holder(f.colony, q, null) == null && !locked(level, p) && !locked(level, q),
              "a double chest reaching across the border stays two plain halves");
        }
        finally
        {
            if (claim != null)
            {
                claim.setOwningColony(ownerBefore, foreignChunk);
            }
        }
        helper.succeed();
    }

    /**
     * V-7, review M-2, L-1, L-2, L-5, L-7, S2: the building lock survives a save and load of the chest; a chest removed without a player (block destroyed, an
     * explosion) leaves its items on the ground once (none lost, none doubled) and leaves the list at the building's check; an unregistered copper chest on a
     * stale position is no stock; a building lock no building holds is cleared when the chunk loads (a hut destroyed while the chunk was unloaded); a lock
     * stays while another building still lists the chest; content keys are copies.
     */
    public static void copperChestRemovalReloadAndRelease(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final RsFixture f = RsFixture.found(helper, "vanilla2 copper removal", 0, false);
        final AbstractBuildingContainer hut = (AbstractBuildingContainer) f.builder;
        final FakePlayer owner = FakePlayerFactory.getMinecraft(level);
        ChunkDataHelper.claimBuildingChunks((Colony) f.colony, true, hut.getID(), 1, hut.getCorners());
        final BlockState single = Blocks.COPPER_CHEST.asList().get(0).defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        final ItemStorage iron = new ItemStorage(new ItemStack(Items.IRON_INGOT));

        final int top = Math.max(hut.getCorners().getA().getY(), hut.getCorners().getB().getY()) + 4;
        // an explosion high above the hut (first, away from the other chests) takes a listed chest (and its 4 ingots, once); the check drops the empty position
        final BlockPos e = new BlockPos(hut.getID().getX(), top + 8, hut.getID().getZ());
        level.setBlockAndUpdate(e, single);
        helper.assertTrue(hut.addCopperChest(e, level), "fixture: a listed chest above the hut");
        BuildingContainers.copperChest(level.getBlockEntity(e)).setItem(0, new ItemStack(Items.IRON_INGOT, 4));
        final float power = 0.6F * (single.getBlock().getExplosionResistance() + 0.3F) + 0.6F;
        // colonies keep blocks safe from explosions by default: let this one break blocks
        final var explosions = com.minecolonies.core.MineColonies.getConfig().getServer().turnOffExplosionsInColonies;
        final var explosionsBefore = explosions.get();
        explosions.set(com.minecolonies.api.colony.permissions.Explosions.DAMAGE_EVERYTHING);
        try
        {
            level.explode(null, e.getX() + 0.5D, e.getY() + 0.5D, e.getZ() + 0.5D, power, net.minecraft.world.level.Level.ExplosionInteraction.BLOCK);
        }
        finally
        {
            explosions.set(explosionsBefore);
        }
        helper.assertTrue(!BuildingContainers.isCopperChest(level.getBlockState(e)), "fixture: the explosion took the chest (power " + power + ")");
        helper.assertTrue(pickUp(level, e, Items.IRON_INGOT) == 4, "its 4 ingots are on the ground, once");
        CopperChests.reconcile(hut);
        helper.assertTrue(!hut.listsContainer(e), "the check drops the position of the exploded chest");

        // reload: the lock is saved and read back as the building lock
        final BlockPos a = hut.getID().east();
        level.setBlockAndUpdate(a, single);
        place(level, a, owner);
        helper.assertTrue(hut.listsContainer(a) && locked(level, a), "fixture: a container chest");
        final ChestBlockEntity chest = BuildingContainers.copperChest(level.getBlockEntity(a));
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        final CompoundTag saved = chest.saveWithFullMetadata(level.registryAccess());
        final net.minecraft.world.level.block.entity.BlockEntity loaded =
          net.minecraft.world.level.block.entity.BlockEntity.loadStatic(a, level.getBlockState(a), saved, level.registryAccess());
        helper.assertTrue(loaded instanceof final ChestBlockEntity again && BuildingContainers.keepsGolemsOut(again) && BuildingContainers.count(again, iron) == 5,
          "after a save and load the chest keeps golems out and holds its stock: " + saved);

        // content keys are copies: emptying the slot leaves the key whole
        final ItemStorage key = BuildingContainers.content(chest).keySet().iterator().next();
        chest.setItem(0, ItemStack.EMPTY);
        helper.assertTrue(key.getItemStack().is(Items.IRON_INGOT) && !key.getItemStack().isEmpty(), "the content key is a copy");
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 5));

        // removed without a player: the items drop once, the stale position is no stock, the building's check drops it
        level.destroyBlock(a, true);
        helper.assertTrue(InventoryUtils.getCountFromBuilding(hut, iron) == 0, "a destroyed chest's items are no stock");
        helper.assertTrue(pickUp(level, a, Items.IRON_INGOT) == 5, "its 5 ingots are on the ground, once");
        level.setBlockAndUpdate(a, single);
        BuildingContainers.copperChest(level.getBlockEntity(a)).setItem(0, new ItemStack(Items.GOLD_INGOT, 3));
        final ItemStorage gold = new ItemStorage(new ItemStack(Items.GOLD_INGOT));
        helper.assertTrue(InventoryUtils.getCountFromBuilding(hut, gold) == 0 && ReservationLedger.atBuilding(hut, gold) == 0,
          "an unregistered copper chest on a stale position is no stock");
        CopperChests.reconcile(hut);
        helper.assertTrue(hut.listsContainer(a) && locked(level, a) && InventoryUtils.getCountFromBuilding(hut, gold) == 3,
          "inside the corners the check takes it as the building's chest");

        // a hut destroyed while the chunk was unloaded leaves a lock no building holds: cleared when the chunk loads; a listed chest keeps its lock
        final BlockPos r = new BlockPos(hut.getID().getX(), top + 2, hut.getID().getZ() + 2);
        level.setBlockAndUpdate(r, single);
        BuildingContainers.keepGolemsOut(level.getBlockEntity(r));
        helper.assertTrue(locked(level, r), "fixture: a lock left behind");
        CopperChests.onChunkLoad(level.getChunkAt(r), level);
        CopperChests.onChunkLoad(level.getChunkAt(a), level);
        helper.assertTrue(!locked(level, r), "the chunk load clears the lock no building holds");
        helper.assertTrue(locked(level, a), "and keeps the lock of a listed chest");
        level.setBlockAndUpdate(r, Blocks.AIR.defaultBlockState());

        // two buildings list one chest: the lock stays until the last one lets go
        f.warehouse.addContainerPosition(a);
        hut.removeContainerPosition(a);
        helper.assertTrue(locked(level, a), "the lock stays while another building lists the chest");
        CopperChests.reconcile((AbstractBuildingContainer) f.warehouse);
        helper.assertTrue(!f.warehouse.getContainers().contains(a) && !locked(level, a), "the warehouse's check drops it (racks only) and the lock goes");
        helper.succeed();
    }

    /**
     * V-7, review c2 M-1, L-2, GPT S4: a rack broken and placed back by hand at the same spot is still a container after the building's check (an empty rack
     * position stays listed, as before); the copper chest fingerprint sees an equal-count swap with a different name; a partial view sync writes the copper
     * stock only after a change, a full sync always.
     */
    public static void rackPositionAndCopperStockView(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final RsFixture f = RsFixture.found(helper, "vanilla2 rack and stock view", 0, false);
        final AbstractBuildingContainer hut = (AbstractBuildingContainer) f.builder;
        final FakePlayer owner = FakePlayerFactory.getMinecraft(level);
        ChunkDataHelper.claimBuildingChunks((Colony) f.colony, true, hut.getID(), 1, hut.getCorners());
        final ItemStorage emerald = new ItemStorage(new ItemStack(Items.EMERALD));

        // a listed rack, broken: the check keeps its position; placed back by hand it is a container again and its items are stock
        final BlockPos rack = hut.getID().north();
        level.setBlockAndUpdate(rack, ModBlocks.blockRack.defaultBlockState());
        hut.registerBlockPosition(ModBlocks.blockRack, rack, level);
        helper.assertTrue(hut.listsContainer(rack), "fixture: a listed rack");
        level.destroyBlock(rack, false);
        helper.assertTrue(level.getBlockEntity(rack) == null, "fixture: the rack is gone");
        CopperChests.reconcile(hut);
        helper.assertTrue(hut.listsContainer(rack), "the check keeps the empty rack position");
        level.setBlockAndUpdate(rack, ModBlocks.blockRack.defaultBlockState());
        place(level, rack, owner);
        CopperChests.reconcile(hut);
        helper.assertTrue(hut.getContainers().contains(rack), "the rack placed back by hand is a container: " + hut.getContainers());
        final int before = InventoryUtils.getCountFromBuilding(hut, emerald);
        helper.assertTrue(level.getBlockEntity(rack) instanceof final com.minecolonies.core.tileentities.TileEntityRack placedBack
          && placedBack.getInventory().insertItem(0, new ItemStack(Items.EMERALD, 2), false).isEmpty(), "fixture: the rack takes 2 emeralds");
        helper.assertTrue(InventoryUtils.getCountFromBuilding(hut, emerald) == before + 2, "its items are the building's stock");

        // the copper chest fingerprint sees components: a renamed stack of equal count resyncs the view
        final BlockPos c = hut.getID().south();
        level.setBlockAndUpdate(c, Blocks.COPPER_CHEST.asList().get(0).defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
        helper.assertTrue(hut.addCopperChest(c, level), "fixture: a listed copper chest");
        final ChestBlockEntity chest = BuildingContainers.copperChest(level.getBlockEntity(c));
        final ItemStack named = new ItemStack(Items.IRON_INGOT, 3);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("first"));
        chest.setItem(0, named);
        hut.checkCopperStock(false);
        helper.assertTrue(hut.writesCopperStock(false), "a partial sync after a change writes the stock");
        helper.assertTrue(!hut.writesCopperStock(false), "the next partial sync does not");
        helper.assertTrue(!hut.checkCopperStock(false) && !hut.writesCopperStock(false), "no change: no stock in the partial sync");
        helper.assertTrue(hut.writesCopperStock(true) && !hut.writesCopperStock(false), "a full sync always writes it, and leaves the partial mark alone");
        final long print = BuildingContainers.contentPrint(chest);
        final ItemStack renamed = new ItemStack(Items.IRON_INGOT, 3);
        renamed.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("second"));
        chest.setItem(0, renamed);
        helper.assertTrue(BuildingContainers.contentPrint(chest) != print, "a renamed stack of equal count changes the fingerprint");
        helper.assertTrue(hut.checkCopperStock(false) && hut.writesCopperStock(false), "and the next partial sync writes the stock");
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        helper.assertTrue(BuildingContainers.contentPrint(chest) != print && hut.checkCopperStock(false), "a plain stack of equal count too");

        // a copper chest gone without a player still leaves the list at the check
        level.setBlockAndUpdate(c, Blocks.AIR.defaultBlockState());
        CopperChests.reconcile(hut);
        helper.assertTrue(!hut.listsContainer(c) && hut.listsContainer(rack), "the check drops the copper chest position, the rack stays");
        helper.succeed();
    }
}
