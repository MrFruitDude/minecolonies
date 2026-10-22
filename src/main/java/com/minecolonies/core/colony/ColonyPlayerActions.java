package com.minecolonies.core.colony;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.HiringMode;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.IAssignsCitizen;
import com.minecolonies.api.colony.buildings.modules.IAssignsJob;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.util.EntityUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.workerbuildings.Stash;
import com.minecolonies.core.util.TeleportHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Optional;

/**
 * Round 12 (M6, M7, M8): the citizen and worker actions of the MineColonies windows, callable from server code without a client message (Colony Command
 * uses them for its own panels). Each method checks what the matching message checks: the player needs {@link Action#MANAGE_HUTS} in the colony (the skill
 * cheat also needs creative mode), the building must be one of the colony's own and the citizen one of its citizens. A refused or invalid call changes
 * nothing and returns false (or 0). Server thread only.
 */
public final class ColonyPlayerActions
{
    private ColonyPlayerActions()
    {
    }

    /**
     * @param player the acting player.
     * @param colony the colony.
     * @return whether the player may run the actions below in this colony (the messages' MANAGE_HUTS check, on the server side).
     */
    public static boolean canManage(@Nullable final ServerPlayer player, @Nullable final IColony colony)
    {
        return player != null && colony != null && colony.getWorld() != null && !colony.getWorld().isClientSide()
                 && colony.getPermissions().hasPermission(player, Action.MANAGE_HUTS);
    }

    /**
     * Recalls one citizen to its workplace, else its home, else the town hall (RecallSingleCitizenMessage).
     *
     * @return whether the citizen is at the target now.
     */
    public static boolean recallCitizen(final ServerPlayer player, final IColony colony, final int citizenId)
    {
        final ICitizenData citizen = citizen(player, colony, citizenId);
        if (citizen == null)
        {
            return false;
        }
        IBuilding target = citizen.getWorkBuilding();
        if (target == null)
        {
            target = citizen.getHomeBuilding();
        }
        if (target == null)
        {
            target = colony.getServerBuildingManager().getTownHall();
        }
        return target != null && recallTo(colony, citizen, target);
    }

    /**
     * Recalls every citizen of the colony to the town hall.
     *
     * @return how many citizens arrived there (0 when refused or the colony has no town hall).
     */
    public static int recallAllToTownHall(final ServerPlayer player, final IColony colony)
    {
        if (!canManage(player, colony) || colony.getServerBuildingManager().getTownHall() == null)
        {
            return 0;
        }
        final IBuilding hall = colony.getServerBuildingManager().getTownHall();
        int recalled = 0;
        for (final ICitizenData citizen : new ArrayList<>(colony.getCitizenManager().getCitizens()))
        {
            if (recallTo(colony, citizen, hall))
            {
                recalled++;
            }
        }
        return recalled;
    }

    /**
     * Hires a citizen into one job module of a building (HireFireMessage, hire).
     *
     * @param building the building's position.
     * @param moduleId the id of its job module.
     */
    public static boolean hireWorker(final ServerPlayer player, final IColony colony, final BlockPos building, final int moduleId, final int citizenId)
    {
        final IBuilding hut = building(player, colony, building);
        final ICitizenData citizen = hut == null ? null : citizen(player, colony, citizenId);
        if (citizen == null || !(hut.getModule(moduleId) instanceof final IAssignsJob module))
        {
            return false;
        }
        citizen.setPaused(false);
        return module.assignCitizen(citizen);
    }

    /**
     * Fires (unassigns) a worker from the job it holds at its workplace (HireFireMessage, fire).
     *
     * @return whether the citizen had a job there and lost it.
     */
    public static boolean fireWorker(final ServerPlayer player, final IColony colony, final int citizenId)
    {
        final ICitizenData citizen = citizen(player, colony, citizenId);
        final IBuilding work = citizen == null ? null : citizen.getWorkBuilding();
        if (work == null)
        {
            return false;
        }
        for (final IAssignsJob module : work.getModules(IAssignsJob.class))
        {
            if (module.hasAssignedCitizen(citizen))
            {
                citizen.setPaused(false);
                return module.removeCitizen(citizen);
            }
        }
        return false;
    }

    /**
     * Pauses or resumes a worker (PauseCitizenMessage, but explicit instead of a toggle).
     */
    public static boolean setWorkerPaused(final ServerPlayer player, final IColony colony, final int citizenId, final boolean paused)
    {
        final ICitizenData citizen = citizen(player, colony, citizenId);
        if (citizen == null)
        {
            return false;
        }
        citizen.setPaused(paused);
        return true;
    }

    /**
     * Sets the hiring mode of every assignment module of a building (BuildingHiringModeMessage does one module).
     *
     * @return how many modules changed (0 when refused or the building has none).
     */
    public static int setHiringMode(final ServerPlayer player, final IColony colony, final BlockPos building, final HiringMode mode)
    {
        final IBuilding hut = building(player, colony, building);
        if (hut == null || mode == null)
        {
            return 0;
        }
        int changed = 0;
        for (final IAssignsCitizen module : hut.getModules(IAssignsCitizen.class))
        {
            module.setHiringMode(mode);
            changed++;
        }
        return changed;
    }

    /**
     * Changes the courier pickup priority of a worker hut or stash by a step (ChangeDeliveryPriorityMessage sends +1 or -1). The building clamps the value.
     *
     * @return whether the building takes a pickup priority.
     */
    public static boolean alterPickUpPriority(final ServerPlayer player, final IColony colony, final BlockPos building, final int delta)
    {
        final IBuilding hut = building(player, colony, building);
        if (hut == null || !(hut.hasModule(WorkerBuildingModule.class) || hut instanceof Stash))
        {
            return false;
        }
        hut.alterPickUpPriority(delta);
        hut.markDirty();
        return true;
    }

    /**
     * Asks a courier to empty the building now (ForcePickupMessage).
     *
     * @return whether a pickup request was made.
     */
    public static boolean forcePickup(final ServerPlayer player, final IColony colony, final BlockPos building)
    {
        final IBuilding hut = building(player, colony, building);
        if (hut == null || !hut.createPickupRequest(Constants.STACKSIZE, true))
        {
            return false;
        }
        hut.markDirty();
        return true;
    }

    /**
     * Cancels a request of the colony (UpdateRequestStateMessage with CANCELLED).
     *
     * @return whether the request existed and was cancelled.
     */
    public static boolean cancelRequest(final ServerPlayer player, final IColony colony, final IToken<?> token)
    {
        if (!canManage(player, colony) || token == null)
        {
            return false;
        }
        try
        {
            if (colony.getRequestManager().getRequestForToken(token) == null)
            {
                return false;
            }
            colony.getRequestManager().updateRequestState(token, RequestState.CANCELLED);
            return true;
        }
        catch (final IllegalArgumentException e)
        {
            Log.getLogger().debug("cancelRequest: unknown request {}", token, e);
            return false;
        }
    }

    /**
     * Raises or lowers a citizen's skill level (AdjustSkillCitizenMessage): a creative-mode cheat, the citizen must be loaded.
     */
    public static boolean adjustSkill(final ServerPlayer player, final IColony colony, final int citizenId, final Skill skill, final int levels)
    {
        final ICitizenData citizen = citizen(player, colony, citizenId);
        if (citizen == null || skill == null || !player.isCreative() || citizen.getEntity().isEmpty())
        {
            return false;
        }
        citizen.getCitizenSkillHandler().incrementLevel(skill, levels);
        citizen.markDirty(0);
        return true;
    }

    @Nullable
    private static ICitizenData citizen(final ServerPlayer player, final IColony colony, final int citizenId)
    {
        return canManage(player, colony) ? colony.getCitizenManager().getCivilian(citizenId) : null;
    }

    @Nullable
    private static IBuilding building(final ServerPlayer player, final IColony colony, final BlockPos pos)
    {
        return canManage(player, colony) && pos != null ? colony.getServerBuildingManager().getBuilding(pos) : null;
    }

    /**
     * The recall of RecallSingleCitizenMessage and RecallCitizenMessage: respawn a missing entity at the building, then teleport.
     */
    private static boolean recallTo(final IColony colony, final ICitizenData citizen, final IBuilding building)
    {
        Optional<AbstractEntityCitizen> entity = citizen.getEntity();
        if (entity.isEmpty())
        {
            citizen.setNextRespawnPosition(EntityUtils.getSpawnPoint(colony.getWorld(), building.getPosition()));
            citizen.updateEntityIfNecessary();
            entity = citizen.getEntity();
        }
        return entity.isPresent() && TeleportHelper.teleportCitizen(entity.get(), colony.getWorld(), building.getPosition());
    }
}
