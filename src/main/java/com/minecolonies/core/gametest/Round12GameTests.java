package com.minecolonies.core.gametest;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.HiringMode;
import com.minecolonies.api.colony.buildings.modules.IAssignsJob;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.ColonyPlayerActions;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Round 12 lane H: the server-side citizen and worker actions Colony Command calls ({@link ColonyPlayerActions}). One test: labour_player_actions.
 */
public final class Round12GameTests
{
    private Round12GameTests()
    {
    }

    /**
     * A player without MANAGE_HUTS changes nothing; an officer can hire, pause, set hiring mode and pickup priority, force a pickup, cancel a request, recall
     * one and all, and fire. The skill cheat needs creative mode.
     */
    public static void playerActions(final GameTestHelper helper)
    {
        final RsFixture f = RsFixture.found(helper, "r12-player-actions", 0);
        helper.runAfterDelay(100, () -> {
            final IColony colony = f.colony;
            final ServerPlayer player = f.player;
            final BlockPos hut = f.builder.getID();
            final int moduleId = BuildingModules.BUILDER_WORK.getRuntimeID();
            final ICitizenData citizen = f.spawn(new BlockPos(30, 1, 20), true);
            final int id = citizen.getId();

            // A stranger: every call is refused and changes nothing.
            helper.assertTrue(!colony.getPermissions().hasPermission(player, Action.MANAGE_HUTS), "precondition: the fixture player already manages huts");
            helper.assertTrue(!ColonyPlayerActions.canManage(player, colony), "canManage for a stranger");
            helper.assertTrue(!ColonyPlayerActions.hireWorker(player, colony, hut, moduleId, id) && citizen.getWorkBuilding() == null, "a stranger hired");
            helper.assertTrue(!ColonyPlayerActions.setWorkerPaused(player, colony, id, true) && !citizen.isPaused(), "a stranger paused");
            helper.assertTrue(ColonyPlayerActions.setHiringMode(player, colony, hut, HiringMode.MANUAL) == 0, "a stranger set the hiring mode");
            helper.assertTrue(!ColonyPlayerActions.alterPickUpPriority(player, colony, hut, 1), "a stranger changed the pickup priority");
            helper.assertTrue(!ColonyPlayerActions.forcePickup(player, colony, hut), "a stranger forced a pickup");
            helper.assertTrue(!ColonyPlayerActions.recallCitizen(player, colony, id) && ColonyPlayerActions.recallAllToTownHall(player, colony) == 0, "a stranger recalled");
            helper.assertTrue(!ColonyPlayerActions.fireWorker(player, colony, id), "a stranger fired");

            // An officer of the colony.
            colony.getPermissions().addPlayer(player.getUUID(), player.getGameProfile().name(), colony.getPermissions().getRankOfficer());
            helper.assertTrue(ColonyPlayerActions.canManage(player, colony), "an officer may not manage");
            helper.assertTrue(!ColonyPlayerActions.hireWorker(player, colony, hut, moduleId, -12345), "hired a citizen that does not exist");
            helper.assertTrue(!ColonyPlayerActions.hireWorker(player, colony, hut.above(40), moduleId, id), "hired at a position with no building");
            helper.assertTrue(ColonyPlayerActions.hireWorker(player, colony, hut, moduleId, id) && citizen.getWorkBuilding() == f.builder, "hire: work " + citizen.getWorkBuilding());

            helper.assertTrue(ColonyPlayerActions.setWorkerPaused(player, colony, id, true) && citizen.isPaused(), "pause");
            helper.assertTrue(ColonyPlayerActions.setWorkerPaused(player, colony, id, false) && !citizen.isPaused(), "resume");

            final int modes = ColonyPlayerActions.setHiringMode(player, colony, hut, HiringMode.MANUAL);
            helper.assertTrue(modes > 0 && f.builder.getModules(IAssignsJob.class).stream().allMatch(m -> m.getHiringMode() == HiringMode.MANUAL), "hiring mode: " + modes);

            helper.assertTrue(ColonyPlayerActions.alterPickUpPriority(player, colony, hut, -100), "pickup priority down");
            final int low = f.builder.getPickUpPriority();
            helper.assertTrue(ColonyPlayerActions.alterPickUpPriority(player, colony, hut, 1) && f.builder.getPickUpPriority() > low,
              "pickup priority up: " + low + " -> " + f.builder.getPickUpPriority());
            helper.assertTrue(!ColonyPlayerActions.alterPickUpPriority(player, colony, colony.getServerBuildingManager().getTownHall().getID(), 1),
              "pickup priority on a building without workers");
            helper.assertTrue(ColonyPlayerActions.forcePickup(player, colony, hut), "force pickup");

            final IToken<?> token = f.builder.createRequest(citizen, new Stack(new ItemStack(Items.DIRT), 4, 4), false);
            helper.assertTrue(!ColonyPlayerActions.cancelRequest(MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper), colony, token), "a stranger cancelled a request");
            helper.assertTrue(ColonyPlayerActions.cancelRequest(player, colony, token), "cancel request");
            final IRequest<?> cancelled = colony.getRequestManager().getRequestForToken(token);
            helper.assertTrue(cancelled == null || cancelled.getState() == RequestState.CANCELLED || cancelled.getState() == RequestState.COMPLETED,
              "request state after cancel: " + (cancelled == null ? null : cancelled.getState()));

            // Recall one goes to the town hall, as the town hall's "Recall one" does (not to the workplace).
            final BlockPos hall = colony.getServerBuildingManager().getTownHall().getID();
            final BlockPos away = helper.absolutePos(new BlockPos(30, 1, 20));
            final boolean one = ColonyPlayerActions.recallCitizen(player, colony, id);
            final double toHut = citizen.getEntity().map(e -> Math.sqrt(e.blockPosition().distSqr(hall))).orElse(-1D);
            helper.assertTrue(one && toHut >= 0 && toHut < 12, "recall one: " + one + " distance to the town hall " + toHut);
            citizen.getEntity().ifPresent(e -> e.teleportTo(away.getX() + 0.5D, away.getY(), away.getZ() + 0.5D));
            final int all = ColonyPlayerActions.recallAllToTownHall(player, colony);
            final double toHall = citizen.getEntity().map(e -> Math.sqrt(e.blockPosition().distSqr(hall))).orElse(-1D);
            helper.assertTrue(all >= 1 && toHall >= 0 && toHall < 12, "recall all: " + all + " distance " + toHall);

            final int before = citizen.getCitizenSkillHandler().getLevel(Skill.Strength);
            helper.assertTrue(!ColonyPlayerActions.adjustSkill(player, colony, id, Skill.Strength, 1), "skill cheat in survival");
            final ServerPlayer creative = MinecoloniesGameTests.makeConnectedCreativePlayer(helper);
            helper.assertTrue(!ColonyPlayerActions.adjustSkill(creative, colony, id, Skill.Strength, 1), "skill cheat by a creative stranger");
            colony.getPermissions().addPlayer(creative.getUUID(), creative.getGameProfile().name(), colony.getPermissions().getRankOfficer());
            helper.assertTrue(ColonyPlayerActions.adjustSkill(creative, colony, id, Skill.Strength, 1) && citizen.getCitizenSkillHandler().getLevel(Skill.Strength) == before + 1,
              "skill cheat in creative: " + before + " -> " + citizen.getCitizenSkillHandler().getLevel(Skill.Strength));

            helper.assertTrue(ColonyPlayerActions.fireWorker(player, colony, id) && citizen.getWorkBuilding() == null, "fire: work " + citizen.getWorkBuilding());
            helper.assertTrue(!ColonyPlayerActions.fireWorker(player, colony, id), "fired twice");
            // Colony B: the officer of A has no rights there, and B's ids mean nothing in A.
            final IColony other = foundSecondColony(helper, "r12-player-actions-b");
            // round 13: colony B's chunk was just force-loaded; it only ticks entities a few ticks later, and a citizen spawns only in an entity-ticking chunk
            // (launches 2 and 3 of lane mc-life: the test grid moved and the chunk was no longer loaded by a neighbouring plot)
            helper.runAfterDelay(20, () -> {
                final ICitizenData stranger = other.getCitizenManager().spawnOrCreateCivilian(null, helper.getLevel(), java.util.List.of(other.getCenter().above()), true);
                helper.assertTrue(stranger != null && stranger.getEntity().isPresent(), "colony B citizen did not spawn");
                final int sid = stranger.getId();
                final BlockPos otherHall = other.getServerBuildingManager().getTownHall().getID();
                final BlockPos where = stranger.getEntity().get().blockPosition();
                helper.assertTrue(!ColonyPlayerActions.canManage(player, other), "the officer of A manages B");
                helper.assertTrue(!ColonyPlayerActions.recallCitizen(player, other, sid) && ColonyPlayerActions.recallAllToTownHall(player, other) == 0, "recall in B");
                helper.assertTrue(!ColonyPlayerActions.setWorkerPaused(player, other, sid, true) && !stranger.isPaused(), "pause in B");
                helper.assertTrue(!ColonyPlayerActions.fireWorker(player, other, sid) && !ColonyPlayerActions.adjustSkill(creative, other, sid, Skill.Strength, 1), "fire or skill in B");
                helper.assertTrue(!ColonyPlayerActions.hireWorker(player, other, otherHall, moduleId, sid), "hire in B");
                helper.assertTrue(ColonyPlayerActions.setHiringMode(player, other, otherHall, HiringMode.MANUAL) == 0 && !ColonyPlayerActions.forcePickup(player, other, otherHall)
                                    && !ColonyPlayerActions.alterPickUpPriority(player, other, otherHall, 1), "building actions in B");
                // B's hut position passed with colony A resolves to no building of A.
                helper.assertTrue(!ColonyPlayerActions.hireWorker(player, colony, otherHall, moduleId, id) && ColonyPlayerActions.setHiringMode(player, colony, otherHall, HiringMode.AUTO) == 0
                                    && !ColonyPlayerActions.forcePickup(player, colony, otherHall) && !ColonyPlayerActions.alterPickUpPriority(player, colony, otherHall, 1), "B's hut through colony A");
                helper.assertTrue(stranger.getEntity().map(e -> e.blockPosition().equals(where)).orElse(false), "colony B citizen moved");
                Log.getLogger().info("R12 RESULT player_actions modes={} recall_one={} hut={} all={} hall={}", modes, one, toHut, all, toHall);
                helper.succeed();
            });
        });
    }

    /**
     * How far north of its test the colony B of {@link #foundSecondColony} stands.
     */
    private static final int SECOND_COLONY_NORTH = 20000;

    /**
     * A second colony far from the fixture, owned by the same fake player as the fixture colony (as foundGameTestColony does). Round 13: it stands on a
     * reserved spot 20000 blocks north of the test, outside the test grid (which grows to the south and east), so it can never touch another test's plot;
     * the helper changes nothing there but the stone pad and the hut, and only checks that the room above the pad is clear.
     */
    private static IColony foundSecondColony(final GameTestHelper helper, final String name)
    {
        final net.minecraft.server.level.ServerLevel level = helper.getLevel();
        final BlockPos column = helper.absolutePos(new BlockPos(2, 1, 2)).offset(0, 0, -SECOND_COLONY_NORTH);
        level.setChunkForced(column.getX() >> 4, column.getZ() >> 4, true);
        level.getChunk(column.getX() >> 4, column.getZ() >> 4);
        final BlockPos pos = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, column);
        for (int x = -2; x <= 2; x++)
        {
            for (int z = -2; z <= 2; z++)
            {
                for (int y = 0; y <= 3; y++)
                {
                    helper.assertTrue(level.getBlockState(pos.offset(x, y, z)).isAir(), "colony B spot is not clear at " + pos.offset(x, y, z) + ": "
                                                                                     + level.getBlockState(pos.offset(x, y, z)));
                }
                level.setBlock(pos.offset(x, -1, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            }
        }
        level.setBlock(pos, com.minecolonies.api.blocks.ModBlocks.blockHutTownHall.defaultBlockState(), 3);
        final com.minecolonies.core.tileentities.TileEntityColonyBuilding hut = (com.minecolonies.core.tileentities.TileEntityColonyBuilding) level.getBlockEntity(pos);
        hut.setPackName("Minecolonies Original");
        hut.setBlueprintPath("fundamentals/townhall1.blueprint");
        final IColony colony = com.minecolonies.api.colony.IColonyManager.getInstance()
          .createColony(level, pos, net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level), name, "Minecolonies Original");
        helper.assertTrue(colony != null, "colony B creation returned null");
        helper.assertTrue(colony.getServerBuildingManager().addNewBuilding(hut, level) != null && colony.getServerBuildingManager().hasTownHall(), "colony B town hall");
        return colony;
    }
}
