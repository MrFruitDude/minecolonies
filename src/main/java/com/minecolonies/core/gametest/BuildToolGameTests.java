package com.minecolonies.core.gametest;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.network.messages.BuildToolPlacementMessage;
import com.ldtteam.structurize.storage.BlueprintPlacementHandling;
import com.ldtteam.structurize.storage.StructurePacks;
import com.ldtteam.structurize.util.BlockInfo;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.util.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * Slice bug 1: the build tool's Minecolonies Original town hall (fundamentals/townhall1) showed no preview and its
 * creative paste placed nothing, while the supply camp from the same tool worked.
 */
public final class BuildToolGameTests
{
    private static final String PACK = "Minecolonies Original";
    private static final String TOWN_HALL_1 = "fundamentals/townhall1.blueprint";

    private BuildToolGameTests()
    {
    }

    /**
     * The shipped town hall blueprint loads with its blocks, and the build tool's creative "Complete" paste (the same
     * server path the green check -> Schematic Paste takes) places the hut block and the building around it.
     */
    public static void buildToolTownHallPaste(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final Blueprint blueprint = StructurePacks.getBlueprintFuture(PACK, TOWN_HALL_1).join();
        helper.assertTrue(blueprint != null, "town hall blueprint did not load from " + PACK);

        int solid = 0;
        int huts = 0;
        for (final BlockInfo info : blueprint.getBlockInfoAsList())
        {
            if (info.getState() != null && !info.getState().isAir())
            {
                solid++;
            }
            if (info.getState() != null && info.getState().is(ModBlocks.blockHutTownHall))
            {
                huts++;
            }
        }
        final BlockPos primary = blueprint.getPrimaryBlockOffset();
        Log.getLogger().info("BUG1 townhall1: size {}x{}x{}, {} block infos, {} non-air, {} town hall blocks, primary {} = {}",
          blueprint.getSizeX(), blueprint.getSizeY(), blueprint.getSizeZ(), blueprint.getBlockInfoAsList().size(), solid, huts, primary,
          blueprint.getBlockState(primary));
        helper.assertTrue(solid > 1000, "town hall blueprint has only " + solid + " non-air blocks");
        helper.assertTrue(huts == 1, "town hall blueprint has " + huts + " town hall blocks");
        helper.assertTrue(blueprint.getBlockState(primary).is(ModBlocks.blockHutTownHall),
          "town hall blueprint anchor is " + blueprint.getBlockState(primary));

        final ServerPlayer player = FakePlayerFactory.getMinecraft(level);
        player.setGameMode(GameType.CREATIVE);
        final BlockPos hut = helper.absolutePos(new BlockPos(16, 5, 12));
        player.setPos(hut.getX() + 0.5, hut.getY() + 30, hut.getZ() + 0.5);

        final BuildToolPlacementMessage message =
          new BuildToolPlacementMessage(BuildToolPlacementMessage.HandlerType.Complete, "", PACK, TOWN_HALL_1, hut, Rotation.NONE, Mirror.NONE);
        message.world = level;
        message.player = player;
        BlueprintPlacementHandling.handlePlacement(message);

        helper.runAfterDelay(200, () -> {
            final BlockPos origin = hut.subtract(primary);
            int placed = 0;
            for (final BlockInfo info : blueprint.getBlockInfoAsList())
            {
                final var state = level.getBlockState(origin.offset(info.getPos()));
                if (!state.isAir() && !state.is(Blocks.STONE))
                {
                    placed++;
                }
            }
            Log.getLogger().info("BUG1 townhall1 paste: {} non-air blocks in the footprint, block at hut = {}", placed, level.getBlockState(hut));
            helper.assertTrue(level.getBlockState(hut).is(ModBlocks.blockHutTownHall),
              "creative paste did not place the town hall hut block, found " + level.getBlockState(hut));
            helper.assertTrue(placed > 500, "creative paste placed only " + placed + " blocks of the town hall");
            helper.succeed();
        });
    }
}
