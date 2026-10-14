package com.minecolonies.core.gametest;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.storage.StructurePacks;
import com.ldtteam.structurize.util.RotationMirror;
import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import com.minecolonies.core.tileentities.TileEntityStash;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.util.function.Consumer;

/**
 * A blank blueprint path is legitimate for source stashes and postboxes (they have no schematic). It must stay blank on NBT load
 * (it used to become the file ".blueprint"), must not be looked up for the rotation, and must not break work orders.
 * One glob: {@code minecolonies:emptybp_*}.
 */
final class EmptyBlueprintGameTests
{
    private static final String PACK = "Minecolonies Original";

    private EmptyBlueprintGameTests()
    {
    }

    static void register(final RegisterGameTestsEvent event)
    {
        add(event, "emptybp_stash_load_keeps_blank_path", EmptyBlueprintGameTests::stashLoadKeepsBlankPath);
        add(event, "emptybp_cook_blueprint_stash_keeps_empty_path", EmptyBlueprintGameTests::cookBlueprintStashKeepsEmptyPath);
        add(event, "emptybp_nonblank_path_still_gets_extension", EmptyBlueprintGameTests::nonBlankPathStillGetsExtension);
        add(event, "emptybp_hut_rotation_blank_path", EmptyBlueprintGameTests::hutRotationBlankPath);
        add(event, "emptybp_workorder_blank_path", EmptyBlueprintGameTests::workOrderBlankPath);
    }

    private static void add(final RegisterGameTestsEvent event, final String name, final Consumer<GameTestHelper> test)
    {
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
          info -> new MinecoloniesGameTestInstance(info, test),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, name));
    }

    private static CompoundTag stashNbt(final String pack, final String path)
    {
        final CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecolonies:stash");
        if (pack != null)
        {
            nbt.putString("pack", pack);
        }
        if (path != null)
        {
            nbt.putString("path", path);
        }
        return nbt;
    }

    private static TileEntityStash loadStash(final GameTestHelper helper, final CompoundTag nbt)
    {
        final BlockEntity loaded = BlockEntity.loadStatic(helper.absolutePos(new BlockPos(1, 1, 1)), ModBlocks.blockStash.defaultBlockState(),
          nbt, helper.getLevel().registryAccess());
        helper.assertTrue(loaded instanceof TileEntityStash, "stash NBT did not load as TileEntityStash: " + nbt);
        return (TileEntityStash) loaded;
    }

    /** A stash saved without a blueprint path (or with a blank one) loads with a blank path and the neutral rotation. */
    static void stashLoadKeepsBlankPath(final GameTestHelper helper)
    {
        for (final String[] packAndPath : new String[][] {{"", ""}, {PACK, ""}, {PACK, null}, {null, null}, {PACK, "   "}})
        {
            final String label = "pack=" + packAndPath[0] + ", path=" + packAndPath[1];
            final TileEntityStash stash = loadStash(helper, stashNbt(packAndPath[0], packAndPath[1]));
            helper.assertTrue(stash.getBlueprintPath() != null && stash.getBlueprintPath().isBlank(),
              "loading a stash with a blank path invented '" + stash.getBlueprintPath() + "' (" + label + ")");
            stash.setRotationMirror(null);
            helper.assertTrue(stash.getRotationMirror() == RotationMirror.NONE,
              "a stash without a blueprint must use the neutral rotation, got " + stash.getRotationMirror() + " (" + label + ")");
        }
        helper.succeed();
    }

    /** The source stash NBT of the shipped Medieval Oak cook5 blueprint has no path and must keep none. */
    static void cookBlueprintStashKeepsEmptyPath(final GameTestHelper helper)
    {
        final Blueprint blueprint = StructurePacks.getBlueprint("Medieval Oak", "fundamentals/cook5.blueprint");
        helper.assertTrue(blueprint != null, "Medieval Oak cook5 source blueprint did not load");

        int loadedStashes = 0;
        for (final var info : blueprint.getBlockInfoAsList())
        {
            final var sourceNbt = info.getTileEntityData();
            if (sourceNbt == null || !"minecolonies:stash".equals(sourceNbt.getStringOr("id", "")))
            {
                continue;
            }
            helper.assertTrue(sourceNbt.getStringOr("path", "").isBlank(), "expected source stash NBT to have no blueprint path: " + sourceNbt);
            final BlockEntity loaded = BlockEntity.loadStatic(helper.absolutePos(info.getPos()), info.getState(), sourceNbt, helper.getLevel().registryAccess());
            helper.assertTrue(loaded instanceof TileEntityStash, "cook5 source stash NBT did not load as TileEntityStash: " + sourceNbt);
            final TileEntityStash stash = (TileEntityStash) loaded;
            helper.assertTrue(stash.getBlueprintPath().isBlank(), "loading the empty source stash path invented " + stash.getBlueprintPath());
            stash.setRotationMirror(null);
            helper.assertTrue(stash.getRotationMirror() == RotationMirror.NONE, "source stash without a blueprint should use neutral rotation, got " + stash.getRotationMirror());
            loadedStashes++;
        }
        helper.assertTrue(loadedStashes > 0, "cook5 source blueprint contains no stash block entity NBT");
        helper.succeed();
    }

    /** Control: a real path without the extension still gets it. */
    static void nonBlankPathStillGetsExtension(final GameTestHelper helper)
    {
        final TileEntityStash stash = loadStash(helper, stashNbt(PACK, "fundamentals/builder1"));
        helper.assertTrue("fundamentals/builder1.blueprint".equals(stash.getBlueprintPath()), "extension not added: " + stash.getBlueprintPath());
        final TileEntityStash complete = loadStash(helper, stashNbt(PACK, "fundamentals/builder1.blueprint"));
        helper.assertTrue("fundamentals/builder1.blueprint".equals(complete.getBlueprintPath()), "extension doubled: " + complete.getBlueprintPath());
        helper.succeed();
    }

    /** getRotationMirror with a null or blank path does not throw and is neutral; a valid schematic still decides the rotation. */
    static void hutRotationBlankPath(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos pos = new BlockPos(1, 1, 1);
        final String blueprintPath = "fundamentals/builder1.blueprint";
        final Blueprint blueprint = StructurePacks.getBlueprint(PACK, blueprintPath);
        helper.assertTrue(blueprint != null, "rotation control blueprint did not load: " + blueprintPath);
        final var primary = blueprint.getBlockInfoAsMap().get(blueprint.getPrimaryBlockOffset());
        helper.assertTrue(primary != null && primary.getState().getBlock() instanceof AbstractBlockHut, "rotation control blueprint has no hut at its primary offset");
        final int sourceFacing = primary.getState().getValue(AbstractBlockHut.FACING).get2DDataValue();
        helper.setBlock(pos, ModBlocks.blockHutBuilder.defaultBlockState()
          .setValue(AbstractBlockHut.FACING, net.minecraft.core.Direction.from2DDataValue((sourceFacing + 1) % 4)));
        final TileEntityColonyBuilding hut = (TileEntityColonyBuilding) level.getBlockEntity(helper.absolutePos(pos));
        helper.assertTrue(hut != null, "builder hut tile entity was not created");
        hut.setPackName(PACK);

        for (final String blank : new String[] {null, "", "  ", "\t"})
        {
            hut.setBlueprintPath(blank);
            hut.setRotationMirror(null);
            final RotationMirror rotation = hut.getRotationMirror();
            helper.assertTrue(rotation == RotationMirror.NONE, "hut with path '" + blank + "' must use neutral rotation, got " + rotation);
        }

        hut.setBlueprintPath(blueprintPath);
        hut.setRotationMirror(null);
        final RotationMirror expected = RotationMirror.of(Rotation.CLOCKWISE_90, Mirror.NONE);
        helper.assertTrue(expected.equals(hut.getRotationMirror()), "valid hut blueprint rotation changed: expected=" + expected + ", actual=" + hut.getRotationMirror());
        helper.succeed();
    }

    /** A building with a blank path (a stash) makes no work order and building one does not throw. */
    static void workOrderBlankPath(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "emptybp work order");
        final IBuilding stash = MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockStash, new BlockPos(8, 1, 2), "");
        helper.assertTrue(stash.getBlueprintPath().isBlank(), "fixture: the stash building has path '" + stash.getBlueprintPath() + "'");

        for (final WorkOrderType type : WorkOrderType.values())
        {
            try
            {
                WorkOrderBuilding.create(type, stash);
            }
            catch (final RuntimeException e)
            {
                helper.fail("creating a " + type + " work order for a blank-path building threw " + e);
                return;
            }
        }

        final int before = colony.getWorkManager().getWorkOrdersOfType(WorkOrderBuilding.class).size();
        try
        {
            stash.requestRemoval(null, BlockPos.ZERO);
            stash.requestRepair(BlockPos.ZERO);
        }
        catch (final RuntimeException e)
        {
            helper.fail("requesting work for a blank-path building threw " + e);
            return;
        }
        helper.assertTrue(colony.getWorkManager().getWorkOrdersOfType(WorkOrderBuilding.class).size() == before,
          "a work order was queued for a building without a blueprint");
        helper.succeed();
    }
}
