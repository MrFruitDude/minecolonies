package com.minecolonies.core.entity.citizen.citizenhandlers;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.interactionhandling.ChatPriority;
import com.minecolonies.api.colony.interactionhandling.InteractionValidatorRegistry;
import com.minecolonies.api.entity.citizen.citizenhandlers.ICitizenFoodHandler;
import com.minecolonies.api.entity.citizen.citizenhandlers.ICitizenHappinessHandler;
import com.minecolonies.api.entity.citizen.happiness.*;
import com.ldtteam.structurize.api.util.Tuple;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.colony.interactionhandling.StandardInteraction;
import com.minecolonies.core.colony.jobs.AbstractJobGuard;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.minecolonies.api.entity.citizen.happiness.HappinessRegistry.*;
import static com.minecolonies.api.research.util.ResearchConstants.HAPPINESS;
import static com.minecolonies.api.research.util.ResearchConstants.TAG_ID;
import static com.minecolonies.api.util.constant.HappinessConstants.*;
import static com.minecolonies.api.util.constant.TranslationConstants.DEMANDS;
import static com.minecolonies.api.util.constant.TranslationConstants.NO;

/**
 * The new happiness handler for the citizen.
 */
public class CitizenHappinessHandler implements ICitizenHappinessHandler
{
    /**
     * The different happiness factor.
     */
    public Map<String, IHappinessModifier> happinessFactors = new HashMap<>();

    /**
     * The cached happiness value.
     */
    private double cachedHappiness = -1.0;

    /**
     * Create a new instance of the citizen happiness handler.
     *
     * @param data the data to handle.
     */
    public CitizenHappinessHandler(final ICitizenData data)
    {
        // Add static modifiers. These modifiers are on/off.
        addModifier(new StaticHappinessModifier(SCHOOL, 1.0, new DynamicHappinessSupplier(SCHOOL_FUNCTION)));
        addModifier(new StaticHappinessModifier(SECURITY, 4.0, new DynamicHappinessSupplier(SECURITY_FUNCTION)));
        addModifier(new StaticHappinessModifier(SOCIAL, 2.0, new DynamicHappinessSupplier(SOCIAL_FUNCTION)));
        addModifier(new StaticHappinessModifier(MYSTICAL_SITE, 1.0, new DynamicHappinessSupplier(MYSTICAL_SITE_FUNCTION)));
        addModifier(new StaticHappinessModifier(FOOD, 3.0, new DynamicHappinessSupplier(FOOD_FUNCTION)));

        // Add time based modifiers. These modifiers change their value over time.
        addModifier(new TimeBasedHappinessModifier(HOMELESSNESS,
          3.0,
          new DynamicHappinessSupplier(HOUSING_FUNCTION),
         new Tuple<>(COMPLAIN_DAYS_WITHOUT_HOUSE, 0.75), new Tuple<>(DEMANDS_DAYS_WITHOUT_HOUSE, 0.5)));

        addModifier(new TimeBasedHappinessModifier(UNEMPLOYMENT,
          2.0,
          new DynamicHappinessSupplier(UNEMPLOYMENT_FUNCTION),
          new Tuple<>(COMPLAIN_DAYS_WITHOUT_JOB, 0.75), new Tuple<>(DEMANDS_DAYS_WITHOUT_JOB, 0.5)));

        addModifier(new TimeBasedHappinessModifier(HEALTH,
          2.0,
          new DynamicHappinessSupplier(HEALTH_FUNCTION),
          new Tuple<>(COMPLAIN_DAYS_SICK, 0.5), new Tuple<>(DEMANDS_CURE_SICK, 0.1)));

        addModifier(new TimeBasedHappinessModifier(IDLEATJOB,
          1.0,
          new DynamicHappinessSupplier(IDLEATJOB_FUNCTION),
          new Tuple<>(IDLE_AT_JOB_COMPLAINS_DAYS, 0.5), new Tuple<>(IDLE_AT_JOB_DEMANDS_DAYS, 0.1)));

        addModifier(new TimeBasedHappinessModifier(SLEPTTONIGHT, 1.5, new DynamicHappinessSupplier(SLEPTTONIGHT_FUNCTION), (modifier, d) -> true, new Tuple<>(0, 2d), new Tuple<>(2, 1.6d), new Tuple<>(3, 1d)));
    }

    /**
     * Create an empty happiness handler for the client side.
     */
    public CitizenHappinessHandler()
    {
        super();
    }

    @Override
    public void addModifier(final IHappinessModifier modifier)
    {
        this.happinessFactors.put(modifier.getId(), modifier);
        cachedHappiness = -1;
    }

    @Override
    public void resetModifier(final String name)
    {
        final IHappinessModifier modifier = happinessFactors.get(name);
        if (modifier instanceof ITimeBasedHappinessModifier timeBasedHappinessModifier)
        {
            timeBasedHappinessModifier.reset();
            cachedHappiness = -1;
        }
    }

    @Override
    public IHappinessModifier getModifier(final String name)
    {
        return happinessFactors.get(name);
    }

    @Override
    public void processDailyHappiness(final ICitizenData citizenData)
    {
        for (final IHappinessModifier happinessModifier : happinessFactors.values())
        {
            if (happinessModifier instanceof ITimeBasedHappinessModifier timeBasedHappinessModifier)
            {
                timeBasedHappinessModifier.dayEnd(citizenData);
            }
            if (InteractionValidatorRegistry.hasValidator(Component.translatableEscape(NO + happinessModifier.getId())))
            {
                citizenData.triggerInteraction(new StandardInteraction(Component.translatableEscape(NO + happinessModifier.getId()), ChatPriority.CHITCHAT));
            }
            if (InteractionValidatorRegistry.hasValidator(Component.translatableEscape(DEMANDS + happinessModifier.getId())))
            {
                citizenData.triggerInteraction(new StandardInteraction(Component.translatableEscape(DEMANDS + happinessModifier.getId()), ChatPriority.CHITCHAT));
            }
        }
        cachedHappiness = -1;
    }

    @Override
    public double getHappiness(final IColony colony, final ICitizenData citizenData)
    {
        if (cachedHappiness == -1)
        {
            cachedHappiness = getLiveHappiness(colony, citizenData);
        }
        return cachedHappiness;
    }

    @Override
    public double getLiveHappiness(final IColony colony, final ICitizenData citizenData)
    {
        double total = 0.0;
        double totalWeight = 0.0;
        for (final IHappinessModifier happinessModifier : happinessFactors.values())
        {
            final double factor = happinessModifier.getFactor(citizenData);
            if (factor == 1.0)
                continue;
            total += factor * happinessModifier.getWeight();
            totalWeight += happinessModifier.getWeight();
        }
        return happiness(total, totalWeight, colony.getResearchManager().getResearchEffects().getEffectStrength(HAPPINESS));
    }

    /**
     * Happiness from the weighted factors that are not neutral: {@code min(10 * total / totalWeight * (1 + research), 10)}. When every factor is neutral (1.0, so
     * none is counted, which the security grace makes common in a young colony) the colony is content: 10, not 0 / 0.
     *
     * @param total       the sum of factor * weight over the counted factors.
     * @param totalWeight the sum of their weights.
     * @param research    the happiness research effect.
     * @return the happiness, at most 10.
     */
    public static double happiness(final double total, final double totalWeight, final double research)
    {
        final double average = totalWeight > 0 ? total / totalWeight : 1.0;
        return Math.min(10.0 * average * (1 + research), 10);
    }

    @Override
    public void read(@NotNull final HolderLookup.Provider provider, final CompoundTag compound, final boolean persist)
    {
        // Only deserialize for new version. Old can keep the above defaults just fine.
        if (compound.contains(TAG_NEW_HAPPINESS))
        {
            final ListTag tag = compound.getListOrEmpty(TAG_NEW_HAPPINESS);
            for (int i = 0; i < tag.size(); i++)
            {
                final CompoundTag compoundTag = tag.getCompoundOrEmpty(i);
                final String id = compoundTag.getStringOr(TAG_ID, "");
                if (happinessFactors.containsKey(id))
                {
                    happinessFactors.get(id).read(provider, compoundTag, persist);
                }
                else if (VALID_HAPPINESS_MODIFIERS.contains(id))
                {
                    final IHappinessModifier modifier = HappinessRegistry.loadFrom(provider, compoundTag, persist);
                    if (modifier != null)
                    {
                        happinessFactors.put(modifier.getId(), modifier);
                    }
                }
            }
        }
    }

    @Override
    public void write(@NotNull final HolderLookup.Provider provider, final CompoundTag compound, final boolean persist)
    {
        final ListTag listTag = new ListTag();
        for (final IHappinessModifier happinessModifier : happinessFactors.values())
        {
            final CompoundTag compoundNbt = new CompoundTag();
            happinessModifier.write(provider, compoundNbt, persist);
            listTag.add(compoundNbt);
        }

        compound.put(TAG_NEW_HAPPINESS, listTag);
    }

    @Override
    public List<String> getModifiers()
    {
        return new ArrayList<>(happinessFactors.keySet());
    }

    // --------------------------------------------- Static Utility Methods --------------------------------------------- //

    /**
     * Get the social modifier for the colony.
     *
     * @param colony the colony.
     * @return the factor.
     */
    public static double getSocialModifier(final IColony colony)
    {
        final double total = colony.getCitizenManager().getCitizens().size();
        double unemployment = 0;
        double homelessness = 0;
        double sickPeople = 0;
        double hungryPeople = 0;

        for (final ICitizenData citizen : colony.getCitizenManager().getCitizens())
        {
            if (!citizen.isChild() && citizen.getJob() == null)
            {
                unemployment++;
            }

            if (citizen.getHomeBuilding() == null)
            {
                homelessness++;
            }

            if (citizen.getEntity().isPresent() && citizen.getCitizenDiseaseHandler().isSick())
            {
                sickPeople++;
            }

            if (citizen.getSaturation() <= 1)
            {
                hungryPeople++;
            }
        }

        return socialFactor(total, unemployment + homelessness + sickPeople + hungryPeople);
    }

    /**
     * The social factor from the colony's counts: the share of citizens with no problem. One citizen can be counted for several problems (jobless, homeless,
     * sick, hungry), so the plain share can go below 0, which made a young colony's happiness negative: it is kept within 0..1, and an empty colony is neutral.
     *
     * @param citizens the colony's citizens.
     * @param problems the problems counted over them (a citizen may add several).
     * @return the factor, 0..1.
     */
    public static double socialFactor(final double citizens, final double problems)
    {
        if (citizens <= 0)
        {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, (citizens - problems) / citizens));
    }

    /**
     * Get the guard security happiness modifier from the colony.
     *
     * @param colony the colony.
     * @return the factor.
     */
    public static double getGuardFactor(final IColony colony)
    {
        int guards = 0;
        int others = 0;
        for (final ICitizenData citizen : colony.getCitizenManager().getCitizens())
        {
            if (citizen.getJob() instanceof AbstractJobGuard)
            {
                guards++;
            }
            else
            {
                others++;
            }
        }
        return guardFactor(guards, others, MineColonies.getConfig().getServer().securityGraceCitizens.get());
    }

    /**
     * The guard security factor from the colony's counts: {@code min((guards + 1) / ((others + 1) * 2 / 3), 2)}. A colony with fewer than
     * {@code graceCitizens} citizens never goes below neutral (1.0, which leaves happiness alone), so a young colony is not unhappy for lacking guards.
     *
     * @param guards        citizens with a guard job.
     * @param others        all other citizens.
     * @param graceCitizens the citizen count from which a lack of guards counts (0 = always).
     * @return the factor, 0..2.
     */
    public static double guardFactor(final int guards, final int others, final int graceCitizens)
    {
        final double factor = Math.min((guards + 1.0) / ((others + 1.0) * 2 / 3), 2);
        return guards + others < graceCitizens ? Math.max(1.0, factor) : factor;
    }

    /**
     * Get the food happiness modifier from the citizen.
     * Mininmal Happiness:
     * 1: 1 Different types of food, e.g. baked potato
     * 2: 2 Types of food baked potato + mushroom soup
     * 3: 3 Types food baked potato + mushroom soup + 1 Minecolonies food
     * 4: 4 Types food baked potato + mushroom soup + 2 Minecolonies food
     * 5: 5 Types food baked potato + mushroom soup + 3 Minecolonies food
     * @param citizenData the citizen.
     * @return the factor.
     */
    public static double getFoodFactor(final ICitizenData citizenData)
    {
        final int homeBuildingLevel = citizenData.getHomeBuilding() == null ? 0 : citizenData.getHomeBuilding().getBuildingLevelEquivalent();
        final ICitizenFoodHandler foodHandler = citizenData.getCitizenFoodHandler();
        if (homeBuildingLevel == 0 || !foodHandler.hasFullFoodHistory())
        {
            return 1.0;
        }

        ICitizenFoodHandler.CitizenFoodStats happinessStats = foodHandler.getFoodHappinessStats();

        final double diversityFactor = Math.min(5.0, (double) happinessStats.diversity() / homeBuildingLevel);
        final double qualityFactor = Math.min(5.0, (double) happinessStats.quality() / (Math.max(1.0, homeBuildingLevel - 2.0)));

        return (diversityFactor + qualityFactor) / 2.0;
    }

    /**
     *  Get the mystical site happiness modifier from the colony.
     *      Mystical site happiness is never negative :
     *      Supply vary from 1 to 3.5 max (1 + (Mystical site lvl 5 / 2))
     *
     * @param colony the colony.
     * @return the factor.
     */
    public static double getMysticalSiteFactor(final IColony colony)
    {
        return Math.max(1, ((double)colony.getServerBuildingManager().getMysticalSiteMaxBuildingLevel() / 2.0));
    }
}
