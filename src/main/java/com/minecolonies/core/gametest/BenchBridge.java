package com.minecolonies.core.gametest;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Public doors into the package-private GameTest fixtures for the request-system benchmark in
 * {@code com.minecolonies.core.gametest.bench}. Nothing here adds behaviour; it only lets the benchmark reuse the exact
 * colony founding, hut placement and mock player the other request-system GameTests use.
 */
public final class BenchBridge
{
    private BenchBridge()
    {
    }

    /**
     * Founds a colony with its town hall at {@code townHallRel}, on a stone floor (y = 0) that covers the given area, with
     * the air above it cleared to y = 24.
     */
    public static IColony foundColony(
      final GameTestHelper helper,
      final String name,
      final BlockPos townHallRel,
      final int minX,
      final int maxX,
      final int minZ,
      final int maxZ)
    {
        final ServerLevel level = helper.getLevel();
        for (int cx = helper.absolutePos(new BlockPos(minX, 0, minZ)).getX() >> 4; cx <= helper.absolutePos(new BlockPos(maxX, 0, maxZ)).getX() >> 4; cx++)
        {
            for (int cz = helper.absolutePos(new BlockPos(minX, 0, minZ)).getZ() >> 4; cz <= helper.absolutePos(new BlockPos(maxX, 0, maxZ)).getZ() >> 4; cz++)
            {
                level.setChunkForced(cx, cz, true);
            }
        }
        for (int x = minX; x < maxX; x++)
        {
            for (int z = minZ; z < maxZ; z++)
            {
                for (int y = 1; y < 24; y++)
                {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
        final BlockPos townHallPos = helper.absolutePos(townHallRel);
        helper.setBlock(townHallRel, com.minecolonies.api.blocks.ModBlocks.blockHutTownHall.defaultBlockState());
        final com.minecolonies.core.tileentities.TileEntityColonyBuilding hut = (com.minecolonies.core.tileentities.TileEntityColonyBuilding) level.getBlockEntity(townHallPos);
        hut.setPackName("Minecolonies Original");
        hut.setBlueprintPath("fundamentals/townhall1.blueprint");
        final IColony colony = com.minecolonies.api.colony.IColonyManager.getInstance()
          .createColony(level, townHallPos, net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level), name, "Minecolonies Original");
        helper.assertTrue(colony != null, "colony creation returned null");
        final IBuilding townHall = colony.getServerBuildingManager().addNewBuilding(hut, level);
        helper.assertTrue(townHall != null && colony.getServerBuildingManager().hasTownHall(), "town hall not registered");
        townHall.setBuildingLevel(1);
        return colony;
    }

    public static IBuilding place(final GameTestHelper helper, final IColony colony, final Block hut, final BlockPos rel, final String blueprint)
    {
        return MinecoloniesGameTests.placeProductionBuilding(helper, colony, hut, rel, blueprint);
    }

    /**
     * The request-system fixture (a colony with a builder hut and a warehouse with racks) the other request-system
     * GameTests use, with the parts the benchmark needs.
     */
    public static final class Fx
    {
        public final IColony                                                           colony;
        public final IBuilding                                                         builder;
        public final com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse warehouse;
        public final List<BlockPos>                                                    racks;
        public final net.minecraft.server.level.ServerPlayer                           player;
        private final RsFixture                                                        fixture;

        private Fx(final RsFixture fixture)
        {
            this.fixture = fixture;
            this.colony = fixture.colony;
            this.builder = fixture.builder;
            this.warehouse = fixture.warehouse;
            this.racks = fixture.racks;
            this.player = fixture.player;
        }

        public void stock(final int rackIndex, final net.minecraft.world.item.ItemStack stack)
        {
            fixture.stock(rackIndex, stack);
        }

        public IBuilding place(final Block hut, final BlockPos rel, final String blueprint)
        {
            return fixture.place(hut, rel, blueprint);
        }

        public ICitizenData spawn(final BlockPos rel, final boolean noAi)
        {
            return fixture.spawn(rel, noAi);
        }

        public com.minecolonies.core.tileentities.TileEntityRack rack(final int index)
        {
            return fixture.rack(index);
        }

        public com.minecolonies.core.colony.requestsystem.management.IStandardRequestManager standard()
        {
            return fixture.standard();
        }
    }

    public static Fx fixture(final GameTestHelper helper, final String name, final int rackCount, final boolean active)
    {
        return new Fx(RsFixture.found(helper, name, rackCount, active));
    }

    public static ServerPlayer connectedPlayer(final GameTestHelper helper)
    {
        return MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper);
    }

    /**
     * Spawns a citizen entity (with AI unless {@code noAi}) at a position relative to the test.
     */
    public static ICitizenData spawnCitizen(final GameTestHelper helper, final IColony colony, final BlockPos rel, final boolean noAi)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos anchor = helper.absolutePos(rel);
        level.setChunkForced(anchor.getX() >> 4, anchor.getZ() >> 4, true);
        final ICitizenData citizen = colony.getCitizenManager().spawnOrCreateCivilian(null, level, List.of(anchor), true);
        helper.assertTrue(citizen != null && citizen.getEntity().isPresent(), "could not spawn a citizen at " + anchor);
        if (noAi)
        {
            citizen.getEntity().get().setNoAi(true);
        }
        return citizen;
    }
}
