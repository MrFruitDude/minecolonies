package com.minecolonies.core.entity.citizen.citizenhandlers;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.citizenhandlers.ICitizenDiseaseHandler;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingHospital;
import com.minecolonies.core.colony.jobs.AbstractJobGuard;
import com.minecolonies.core.colony.jobs.JobHealer;
import com.minecolonies.core.datalistener.model.Disease;
import com.minecolonies.core.datalistener.DiseasesListener;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import static com.minecolonies.api.research.util.ResearchConstants.MASKS;
import static com.minecolonies.api.research.util.ResearchConstants.VACCINES;
import static com.minecolonies.api.util.constant.CitizenConstants.*;
import static com.minecolonies.api.util.constant.Constants.ONE_HUNDRED_PERCENT;
import static com.minecolonies.api.util.constant.StatisticsConstants.CITIZENS_HEALED;

/**
 * Handler taking care of citizens getting stuck.
 */
public class CitizenDiseaseHandler implements ICitizenDiseaseHandler
{
    /**
     * Health at which citizens seek a doctor.
     */
    public static final double SEEK_DOCTOR_HEALTH = 6.0;

    /**
     * Base likelihood of a citizen getting a disease.
     */
    private static final int DISEASE_FACTOR = 100000 / 3;

    /**
     * Number of ticks after recovering a citizen is immune against any illness. 90 Minutes currently.
     */
    private static final int IMMUNITY_TIME = 20 * 60 * 90;

    /**
     * Collision cooldown to avoid triggering too aggressively. 30 seconds currently.
     */
    private static final int COLLISION_COOLDOWN = 20 * 30;

    /**
     * Additional immunity time through vaccines.
     */
    private static final int VACCINE_MODIFIER = 10;

    /**
     * The citizen assigned to this manager.
     */
    private final ICitizenData citizenData;

    /**
     * The disease the citizen has, empty if none.
     */
    @Nullable
    private Disease disease;

    /**
     * Special immunity time after being cured.
     */
    private int immunityTicks = 0;

    /**
     * Whether the citizen sleeps at the hostpital
     */
    private boolean sleepsAtHospital = false;

    /**
     * B2: ticks this citizen has been sick while the colony had no hospital.
     */
    private int sickWithoutHospitalTicks = 0;

    /**
     * B2 (round 13 decision): a sick citizen of a colony without a hospital recovers at home after one game day.
     */
    public static final int HOME_RECOVERY_TICKS = 24000;

    private static final String TAG_SICK_NO_HOSPITAL = "sickNoHospital";


    /**
     * The population at or below which citizens do not fall sick: the free citizen floor (respawncitizenamount, at most the starting count).
     */
    private static int minPopulationForDisease()
    {
        return com.minecolonies.core.colony.managers.CitizenManager.configuredFreeFloor();
    }

    /**
     * Constructor for the experience handler.
     *
     * @param citizen the citizen owning the handler.
     */
    public CitizenDiseaseHandler(final ICitizenData citizen)
    {
        this.citizenData = citizen;
    }

    /**
     * Called in the citizen every few ticks to check for illness. Called every 60 ticks
     */
    @Override
    public void update(final int tickRate)
    {
        if (canBecomeSick())
        {
            final double citizenModifier = citizenData.getDiseaseModifier();
            final int configModifier = MineColonies.getConfig().getServer().diseaseModifier.get();

            // normally it's one in 5 x 10.000

            if (citizenData.getRandom().nextInt(configModifier * DISEASE_FACTOR) < citizenModifier * 10)
            {
                this.disease = DiseasesListener.getRandomDisease(citizenData.getEntity().map(AbstractEntityCitizen::getRandom).orElse(RandomSource.create()));
                this.sickWithoutHospitalTicks = 0;
            }
        }

        if (immunityTicks > 0)
        {
            immunityTicks -= tickRate;
        }

        if (isSick() && !colonyHasHospital())
        {
            sickWithoutHospitalTicks += tickRate;
            if (sickWithoutHospitalTicks >= HOME_RECOVERY_TICKS)
            {
                // B2: no hospital to go to, the citizen got over it at home
                cure();
                citizenData.getEntity().ifPresent(entity -> entity.setHealth(entity.getMaxHealth()));
            }
        }
    }

    /**
     * @return true when the colony has a built hospital.
     */
    private boolean colonyHasHospital()
    {
        return citizenData.getColony() != null
                 && citizenData.getColony().getServerBuildingManager().getFirstBuildingMatching(CitizenDiseaseHandler::isUsableHospital) != null;
    }

    /**
     * B2: the one hospital check for both the home recovery count and the sick AI: a hospital that is built (level above 0).
     *
     * @param building the building.
     * @return true when the building is a built hospital.
     */
    public static boolean isUsableHospital(final IBuilding building)
    {
        return building instanceof BuildingHospital && building.getBuildingLevel() > 0;
    }

    /**
     * @return the ticks the citizen has been sick while the colony had no hospital (B2).
     */
    public int getSickWithoutHospitalTicks()
    {
        return sickWithoutHospitalTicks;
    }

    @Override
    public boolean setDisease(final @Nullable Disease disease)
    {
        if (canBecomeSick())
        {
            this.disease = disease;
            this.sickWithoutHospitalTicks = 0;
            return true;
        }
        return false;
    }

    /**
     * Check if the citizen may become sick.
     *
     * @return true if so.
     */
    private boolean canBecomeSick()
    {
        return !isSick()
            && citizenData.getEntity().isPresent()
            && citizenData.getColony().isActive()
            && !(citizenData.getJob() instanceof JobHealer)
                 && immunityTicks <= 0
            && citizenData.getColony().getCitizenManager().getCurrentCitizenCount() > minPopulationForDisease();
    }

    @Override
    public void onCollission(final ICitizenData citizen)
    {
        if (citizen.getCitizenDiseaseHandler().isSick()
              && canBecomeSick()
              && citizen.getRandom().nextInt(ONE_HUNDRED_PERCENT) < 1)
        {
            final double masks = citizen.getColony().getResearchManager().getResearchEffects().getEffectStrength(MASKS);
            if (masks <= 0 || citizen.getRandom().nextBoolean())
            {
                this.disease = citizen.getCitizenDiseaseHandler().getDisease();
                this.sickWithoutHospitalTicks = 0;
            }
            else
            {
                if (citizenData.getColony().getResearchManager().getResearchEffects().getEffectStrength(VACCINES) > 0)
                {
                    immunityTicks = Math.max(immunityTicks, COLLISION_COOLDOWN * 2);
                }
                else
                {
                    immunityTicks = Math.max(immunityTicks, COLLISION_COOLDOWN);
                }
            }
        }
    }

    @Override
    public boolean isHurt()
    {
        return citizenData.getEntity().isPresent() && !(citizenData.getJob() instanceof AbstractJobGuard) && citizenData.getEntity().get().getHealth() < SEEK_DOCTOR_HEALTH
            && citizenData.getSaturation() > LOW_SATURATION;
    }

    @Override
    public boolean isSick()
    {
        return disease != null;
    }

    @Override
    public void write(final CompoundTag compound)
    {
        CompoundTag diseaseTag = new CompoundTag();
        if (disease != null)
        {
            diseaseTag.putString(TAG_DISEASE_ID, disease.id().toString());
        }
        diseaseTag.putInt(TAG_IMMUNITY, immunityTicks);
        diseaseTag.putInt(TAG_SICK_NO_HOSPITAL, sickWithoutHospitalTicks);
        compound.put(TAG_DISEASE, diseaseTag);
    }

    @Override
    public void read(final CompoundTag compound)
    {
        if (!compound.contains(TAG_DISEASE))
        {
            return;
        }

        CompoundTag diseaseTag = compound.getCompoundOrEmpty(TAG_DISEASE);
        if (diseaseTag.contains(TAG_DISEASE_ID))
        {
            this.disease = DiseasesListener.getDisease(Identifier.parse(diseaseTag.getStringOr(TAG_DISEASE_ID, "")));
        }
        this.immunityTicks = diseaseTag.getIntOr(TAG_IMMUNITY, 0);
        this.sickWithoutHospitalTicks = diseaseTag.getIntOr(TAG_SICK_NO_HOSPITAL, 0);
    }

    @Override
    @Nullable
    public Disease getDisease()
    {
        return this.disease;
    }

    @Override
    public void cure()
    {
        this.disease = null;
        sleepsAtHospital = false;
        sickWithoutHospitalTicks = 0;
        if (citizenData.isAsleep() && citizenData.getEntity().isPresent())
        {
            citizenData.getEntity().get().stopSleeping();
            final BlockPos hospitalPos = citizenData.getColony().getServerBuildingManager().getBestBuilding(citizenData.getEntity().get(), BuildingHospital.class);
            final IColony colony = citizenData.getColony();
            final IBuilding hospital = colony.getServerBuildingManager().getBuilding(hospitalPos);
            if (hospital != null)
            {
                hospital.onWakeUp();
            }

            if (citizenData.getColony().getResearchManager().getResearchEffects().getEffectStrength(VACCINES) > 0)
            {
                immunityTicks = IMMUNITY_TIME * VACCINE_MODIFIER;
            }
            else
            {
                immunityTicks = IMMUNITY_TIME;
            }
        }
        else
        {
            // Less immunity time if not cored in bed, but still have immunity time.
            immunityTicks = IMMUNITY_TIME / 2;
        }

        citizenData.getColony().getStatisticsManager().increment(CITIZENS_HEALED, citizenData.getColony().getDay());
        citizenData.markDirty(0);
    }

    @Override
    public boolean sleepsAtHospital()
    {
        return sleepsAtHospital;
    }

    @Override
    public void setSleepsAtHospital(final boolean isAtHospital)
    {
        sleepsAtHospital = isAtHospital;
    }
}
