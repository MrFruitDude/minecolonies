package com.minecolonies.api.entity.citizen.happiness;

import com.minecolonies.api.colony.ICitizenData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * Wrapper to deal with happiness suppliers.
 */
public interface IHappinessSupplierWrapper
{
    /**
     * Get the matching value.
     * @param citizenData the context.
     * @return the value.
     */
    double getValue(final ICitizenData citizenData);

    /**
     * Get the last cache value in absence of the citizen.
     * @return the last cached value.
     */
    double getLastCachedValue();

    /**
     * The current value, like {@link #getValue}, but nothing is stored: the last cached value stays as it is. Suppliers that store nothing return
     * {@link #getValue}.
     *
     * @param citizenData the citizen.
     * @return the current value.
     */
    default double peekValue(final ICitizenData citizenData)
    {
        return getValue(citizenData);
    }

    CompoundTag serializeNBT(HolderLookup.Provider provider);

    void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt);
}
