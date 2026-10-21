package com.minecolonies.api.entity.citizen.citizenhandlers;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.happiness.IHappinessModifier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The citizen happiness handler interface.
 */
public interface ICitizenHappinessHandler
{
    /**
     * Add the modifier to the handler.
     *
     * @param modifier the modifier.
     */
    void addModifier(IHappinessModifier modifier);

    /**
     * Reset a modifier.
     *
     * @param name the name of the modifier.
     */
    void resetModifier(final String name);

    /**
     * Get a modifier by the name.
     *
     * @param name the name of the modifier to return.
     * @return the modifier.
     */
    IHappinessModifier getModifier(String name);

    /**
     * Process the happiness factors daily.
     *
     * @param citizenData the citizen to process it for.
     */
    void processDailyHappiness(final ICitizenData citizenData);

    /**
     * Get the computed happiness of the citizen.
     *
     * @param colony the colony.
     * @return the happiness.
     */
    double getHappiness(final IColony colony, final ICitizenData citizenData);

    /**
     * The happiness of the citizen as it is now: computed from the factors at this moment, without reading or changing the cached value that
     * {@link #getHappiness} returns (that one is only renewed when a modifier changes or a day ends). Server side, for displays that show the live value.
     * Handlers that keep no cache return {@link #getHappiness}.
     *
     * @param colony      the colony.
     * @param citizenData the citizen.
     * @return the happiness, 0 to 10.
     */
    default double getLiveHappiness(final IColony colony, final ICitizenData citizenData)
    {
        return getHappiness(colony, citizenData);
    }

    /**
     * Read the handler from NBT.
     *
     * @param compound the compound to read it from.
     * @param persist  whether we're reading from persisted data or from networking.
     */
    void read(@NotNull final HolderLookup.Provider provider, CompoundTag compound, final boolean persist);

    /**
     * Write the handler to NBT.
     *
     * @param compound the compound to write it to.
     * @param persist  whether we're reading from persisted data or from networking.
     */
    void write(@NotNull final HolderLookup.Provider provider, CompoundTag compound, final boolean persist);

    /**
     * Get a list of all modifiers.
     *
     * @return the list.
     */
    List<String> getModifiers();
}
