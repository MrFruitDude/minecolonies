package com.minecolonies.core.colony.buildings.modules;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.HiringMode;
import com.minecolonies.api.colony.buildings.modules.*;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static com.minecolonies.api.util.constant.NbtTagConstants.TAG_LIVING_RESIDENTS;
import static com.minecolonies.api.util.constant.NbtTagConstants.TAG_RESIDENTS;

/**
 * The living module for citizen to call their home.
 */
public class LivingBuildingModule extends AbstractAssignedCitizenModule implements IAssignsCitizen, IBuildingEventsModule, ITickingModule, IPersistentModule
{
    @Override
    public void deserializeNBT(@NotNull final HolderLookup.Provider provider, final CompoundTag compound)
    {
        super.deserializeNBT(provider, compound);
        if (compound.contains(TAG_RESIDENTS) || compound.contains(TAG_LIVING_RESIDENTS))
        {
            final int[] residentIds = compound.getIntArray(compound.contains(TAG_RESIDENTS) ? TAG_RESIDENTS : TAG_LIVING_RESIDENTS).orElse(new int[0]);
            final List<ICitizenData> residents = new ArrayList<>();
            for (final int citizenId : residentIds)
            {
                final ICitizenData citizen = building.getColony().getCitizenManager().getCivilian(citizenId);
                if (citizen != null)
                {
                    residents.add(citizen);
                }
            }
            final int beds = getModuleMax();
            if (restoreResidents(residents) > beds)
            {
                Log.getLogger().info("Residence {} keeps {} saved residents with {} beds (fewer beds than when saved); no new resident moves in until one leaves",
                  building.getID(), assignedCitizen.size(), beds);
            }
        }
    }

    /**
     * Puts the saved residents back on load, all of them: a residence with fewer beds than when it was saved (a lower residenceextrabeds, or a save from a
     * jar with more beds) keeps its residents rather than leaving the extra ones homeless. It takes no new resident until it is below its beds again.
     *
     * @param residents the saved residents, in saved order.
     * @return the residents it holds afterwards.
     */
    int restoreResidents(final List<ICitizenData> residents)
    {
        for (final ICitizenData citizen : residents)
        {
            if (citizen != null && !assignedCitizen.contains(citizen))
            {
                assignedCitizen.add(citizen);
                onAssignment(citizen);
                markDirty();
            }
        }
        return assignedCitizen.size();
    }

    @Override
    public void serializeNBT(@NotNull final HolderLookup.Provider provider, CompoundTag compound)
    {
        super.serializeNBT(provider, compound);
        if (!assignedCitizen.isEmpty())
        {
            final int[] residentIds = new int[assignedCitizen.size()];
            for (int i = 0; i < assignedCitizen.size(); ++i)
            {
                residentIds[i] = assignedCitizen.get(i).getId();
            }
            compound.putIntArray(TAG_LIVING_RESIDENTS, residentIds);
        }
    }

    @Override
    public void onColonyTick(@NotNull final IColony colony)
    {
        if (!isFull() && ((this.getHiringMode() == HiringMode.DEFAULT && building.getColony().getSettings().getSetting(BuildingTownHall.AUTO_HOUSING_MODE).getValue()) || getHiringMode() == HiringMode.AUTO))
        {
            // 'Capture' as many citizens into this house as possible
            for (@NotNull final ICitizenData citizen : building.getColony().getCitizenManager().getCitizens())
            {
                if (isFull())
                {
                    break;
                }

                if (citizen.getHomeBuilding() == null)
                {
                    assignCitizen(citizen);
                }
            }
        }
    }

    @Override
    void onAssignment(final ICitizenData citizen)
    {
        citizen.setHomeBuilding(building);
        building.getColony().getCitizenManager().calculateMaxCitizens();
    }

    @Override
    void onRemoval(final ICitizenData citizen)
    {
        citizen.setHomeBuilding(null);
        building.getColony().getCitizenManager().calculateMaxCitizens();
    }

    @Override
    public int getModuleMax()
    {
        return beds(building.getBuildingLevel(), MineColonies.getConfig().getServer().residenceExtraBeds.get());
    }

    /**
     * Beds of a residence: its level plus the configured extra beds once it is built, none before.
     *
     * @param level the building level.
     * @param extra the extra beds (server config residenceextrabeds).
     * @return the number of citizens it can house.
     */
    public static int beds(final int level, final int extra)
    {
        return level > 0 ? level + Math.max(0, extra) : 0;
    }

    @Override
    public void onUpgradeComplete(final int newLevel)
    {
        for (final Optional<AbstractEntityCitizen> entityCitizen : Objects.requireNonNull(getAssignedEntities()))
        {
            if (entityCitizen.isPresent() && entityCitizen.get().getCitizenJobHandler().getColonyJob() == null)
            {
                entityCitizen.get().getCitizenJobHandler().setModelDependingOnJob(null);
            }
        }
        building.getColony().getCitizenManager().calculateMaxCitizens();
    }

    @Override
    protected String getModuleSerializationIdentifier()
    {
        return "living";
    }
}
