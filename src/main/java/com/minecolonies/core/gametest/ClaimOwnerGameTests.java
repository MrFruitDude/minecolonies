package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.claim.IChunkClaimData;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.ColonyLimits;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import com.minecolonies.core.util.ChunkDataHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

import static com.minecolonies.core.gametest.MinecoloniesGameTests.makeConnectedSurvivalPlayer;

/**
 * MP-D (multiplayer, land): a building's claim never takes chunks another owner's colony already holds. Two players each found a colony a few chunks apart; the
 * second player's building claims a radius that reaches into the first colony's land: the chunks the first colony holds stay its own, the second colony's own side is claimed, and a
 * colony of one owner may still claim its own land.
 */
public final class ClaimOwnerGameTests
{
    private ClaimOwnerGameTests()
    {
    }

    private static final String PACK = "Minecolonies Original";

    private static BlockPos placeTownHall(final GameTestHelper helper, final int relX, final int relZ)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos pos = helper.absolutePos(new BlockPos(relX, 1, relZ));
        level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
        for (int x = -2; x <= 2; x++)
        {
            for (int z = -2; z <= 2; z++)
            {
                level.setBlock(pos.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        level.setBlock(pos, ModBlocks.blockHutTownHall.defaultBlockState(), 3);
        final TileEntityColonyBuilding hut = (TileEntityColonyBuilding) level.getBlockEntity(pos);
        helper.assertTrue(hut != null, "town hall at " + pos + " has no tile entity");
        hut.setPackName(PACK);
        hut.setBlueprintPath("fundamentals/townhall1.blueprint");
        return pos;
    }

    /** A claim in a chunk of another owner's colony is refused; the same owner's colony and an unclaimed chunk are fine. */
    public static void building_claim_skips_other_owners_chunks(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        ColonyLimits.overrideMaxPerPlayer(2);
        final ServerPlayer first = makeConnectedSurvivalPlayer(helper);
        final ServerPlayer second = makeConnectedSurvivalPlayer(helper);
        // 140 blocks apart: more than the minimum distance of 8 chunks; the initial claims of 4 chunks do not touch, B's building claim of 6 chunks reaches A's land.
        final BlockPos hallA = placeTownHall(helper, 2, 2);
        final BlockPos hallB = placeTownHall(helper, 140, 2);
        final IColony a = IColonyManager.getInstance().createColony(level, hallA, first, "claims A", PACK);
        final IColony b = IColonyManager.getInstance().createColony(level, hallB, second, "claims B", PACK);
        helper.assertTrue(a != null && b != null, "colonies not created: " + a + " " + b);
        // The town halls are registered as buildings (a building's claim is what is under test).
        a.getServerBuildingManager().addNewBuilding((TileEntityColonyBuilding) level.getBlockEntity(hallA), level);
        b.getServerBuildingManager().addNewBuilding((TileEntityColonyBuilding) level.getBlockEntity(hallB), level);
        helper.assertTrue(!a.getPermissions().getOwner().equals(b.getPermissions().getOwner()), "same owner");

        final ChunkPos chunkA = ChunkPos.containing(hallA);
        final IChunkClaimData claimA = IColonyManager.getInstance().getClaimData(level.dimension(), chunkA);
        helper.assertTrue(claimA != null && claimA.getOwningColony() == a.getID(), "colony A owns its hall chunk");

        // B's building claims 6 chunks around its hall: that reaches the chunks A owns (A's claim reaches 4 chunks from its hall, B's hall is 8 to 9 chunks away).
        ChunkDataHelper.claimBuildingChunks((Colony) b, true, hallB, 6, null);
        final ChunkPos chunkB = ChunkPos.containing(hallB);
        int inReach = 0;
        for (int x = chunkA.x(); x <= chunkA.x() + 8; x++)
        {
            final IChunkClaimData data = IColonyManager.getInstance().getClaimData(level.dimension(), new ChunkPos(x, chunkA.z()));
            if (data != null && data.getOwningColony() == a.getID())
            {
                helper.assertTrue(!data.getAllClaimingBuildings().containsKey(b.getID()), "B's building claim took chunk x=" + x + " of A: " + data.getAllClaimingBuildings());
                inReach += x >= chunkB.x() - 6 ? 1 : 0;
            }
        }
        helper.assertTrue(inReach > 0, "the test did not reach A's land (B's chunk x=" + chunkB.x() + ", A's x=" + chunkA.x() + ")");
        final IChunkClaimData after = IColonyManager.getInstance().getClaimData(level.dimension(), chunkA);
        helper.assertTrue(after.getOwningColony() == a.getID(), "A still owns its chunk: " + after.getOwningColony());
        helper.assertTrue(!ChunkDataHelper.claimedByOtherOwner(level, after, (Colony) a), "A is not blocked by itself");
        helper.assertTrue(ChunkDataHelper.claimedByOtherOwner(level, after, (Colony) b), "B is blocked by A");

        // B's own side is claimed as before (the chunk next to its hall).
        final IChunkClaimData own = IColonyManager.getInstance().getClaimData(level.dimension(), new ChunkPos(chunkB.x() + 5, chunkB.z()));
        helper.assertTrue(own != null && own.getAllClaimingBuildings().containsKey(b.getID()), "B's own side is claimed");

        // A's own building claim still works in its own chunks.
        ChunkDataHelper.claimBuildingChunks((Colony) a, true, hallA, 2, null);
        helper.assertTrue(IColonyManager.getInstance().getClaimData(level.dimension(), chunkA).getAllClaimingBuildings().containsKey(a.getID()), "A claims its own land");

        // Unclaiming never refuses.
        ChunkDataHelper.claimBuildingChunks((Colony) b, false, hallB, 6, null);
        ColonyLimits.overrideMaxPerPlayer(null);
        level.setChunkForced(hallA.getX() >> 4, hallA.getZ() >> 4, false);
        level.setChunkForced(hallB.getX() >> 4, hallB.getZ() >> 4, false);
        helper.succeed();
    }
}
