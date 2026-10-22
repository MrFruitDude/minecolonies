package com.minecolonies.core.gametest;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.HiringMode;
import com.minecolonies.api.colony.buildings.modules.IAssignsCitizen;
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
            helper.assertTrue(modes > 0 && f.builder.getModules(IAssignsCitizen.class).stream().allMatch(m -> m.getHiringMode() == HiringMode.MANUAL), "hiring mode: " + modes);

            helper.assertTrue(ColonyPlayerActions.alterPickUpPriority(player, colony, hut, -100), "pickup priority down");
            final int low = f.builder.getPickUpPriority();
            helper.assertTrue(ColonyPlayerActions.alterPickUpPriority(player, colony, hut, 1) && f.builder.getPickUpPriority() > low,
              "pickup priority up: " + low + " -> " + f.builder.getPickUpPriority());
            helper.assertTrue(!ColonyPlayerActions.alterPickUpPriority(player, colony, colony.getServerBuildingManager().getTownHall().getID(), 1),
              "pickup priority on a building without workers");
            helper.assertTrue(ColonyPlayerActions.forcePickup(player, colony, hut), "force pickup");

            final IToken<?> token = f.builder.createRequest(citizen, new Stack(new ItemStack(Items.DIRT), 4, 4), false);
            helper.assertTrue(ColonyPlayerActions.cancelRequest(player, colony, token), "cancel request");
            final IRequest<?> cancelled = colony.getRequestManager().getRequestForToken(token);
            helper.assertTrue(cancelled == null || cancelled.getState() == RequestState.CANCELLED || cancelled.getState() == RequestState.COMPLETED,
              "request state after cancel: " + (cancelled == null ? null : cancelled.getState()));

            final boolean one = ColonyPlayerActions.recallCitizen(player, colony, id);
            final double toHut = citizen.getEntity().map(e -> Math.sqrt(e.blockPosition().distSqr(hut))).orElse(-1D);
            helper.assertTrue(one && toHut >= 0 && toHut < 12, "recall one: " + one + " distance " + toHut);
            final int all = ColonyPlayerActions.recallAllToTownHall(player, colony);
            final BlockPos hall = colony.getServerBuildingManager().getTownHall().getID();
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
            Log.getLogger().info("R12 RESULT player_actions modes={} recall_one={} hut={} all={} hall={}", modes, one, toHut, all, toHall);
            helper.succeed();
        });
    }
}
