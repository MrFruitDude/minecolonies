package com.minecolonies.api.colony;

import com.minecolonies.api.colony.jobs.IJob;
import org.jetbrains.annotations.NotNull;

/**
 * Lets another mod run a colony faster or slower than normal ("colony pace") without touching world time.
 * <p>
 * Each method returns a multiplier read at the call site every time it is used, so a provider may change its answer
 * at any moment (per colony, per job). 1.0 means unchanged; the default provider answers 1.0 everywhere and leaves
 * MineColonies exactly as it is without one. A value that is not a finite number above zero is ignored (treated as
 * 1.0), so a broken provider cannot stall or reverse a colony.
 * <p>
 * Register one with {@link com.minecolonies.api.IMinecoloniesAPI#setColonyPaceProvider(IColonyPaceProvider)}. There is
 * one provider at a time; the last one set wins.
 */
public interface IColonyPaceProvider
{
    /**
     * The provider used when no mod set one: 1.0 everywhere.
     */
    IColonyPaceProvider DEFAULT = new IColonyPaceProvider() {};

    /**
     * Speed of a worker's waits. A worker's AI counts its delay down by tick rate times this value each time it waits,
     * so 2.0 halves every wait (crafting hits, block placing and breaking, research...).
     *
     * @param colony the colony of the worker.
     * @param job    the worker's job.
     * @return the multiplier, default 1.0.
     */
    default double work(@NotNull final IColony colony, @NotNull final IJob<?> job)
    {
        return 1.0D;
    }

    /**
     * Speed of need decay: every saturation loss of a citizen in this colony is multiplied by this value.
     *
     * @param colony the colony.
     * @return the multiplier, default 1.0.
     */
    default double needs(@NotNull final IColony colony)
    {
        return 1.0D;
    }

    /**
     * Speed of child growth: multiplies the child growth modifier (on top of the growth research).
     *
     * @param colony the colony.
     * @return the multiplier, default 1.0.
     */
    default double growth(@NotNull final IColony colony)
    {
        return 1.0D;
    }

    /**
     * Turns a provider's answer into a usable multiplier: anything that is not a finite number above zero becomes 1.0.
     *
     * @param pace the provider's answer.
     * @return the multiplier to apply.
     */
    static double sanitize(final double pace)
    {
        return Double.isFinite(pace) && pace > 0.0D ? pace : 1.0D;
    }
}
