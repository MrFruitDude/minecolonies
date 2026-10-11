package com.minecolonies.api.colony;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Lets another mod steer where an idle citizen spends its free time, without taking the decision away: MineColonies still decides <em>that</em> a citizen goes
 * to a leisure site now (the idle wander chance), and picks a site; the provider then answers where the citizen really goes. Used by Colony Command to send
 * citizens to the service buildings that cover their home (a library, a tavern, a university), capped and only close by, as a purely cosmetic visit.
 * <p>
 * The citizen walks to the returned hut position and wanders there as for any leisure site (so it must be a building's hut or a leisure site). The default
 * provider returns the proposed site unchanged, which leaves MineColonies exactly as it is without one. A provider that throws is ignored for that decision
 * (the proposed site is used), so a broken provider cannot stop citizens from idling.
 * <p>
 * Register one with {@link com.minecolonies.api.IMinecoloniesAPI#setCitizenLeisureProvider(ICitizenLeisureProvider)}. There is one provider at a time; the
 * last one set wins. Called on the server thread, from the citizen's AI.
 */
@FunctionalInterface
public interface ICitizenLeisureProvider
{
    /**
     * The provider used when no mod set one: the proposed site, unchanged.
     */
    ICitizenLeisureProvider DEFAULT = (citizen, proposed) -> Optional.of(proposed);

    /**
     * Where an idle citizen that wants a leisure site goes.
     *
     * @param citizen  the citizen (its colony, home and work are reachable from it).
     * @param proposed the site MineColonies picked (a hut position or a leisure site; never null).
     * @return the site to walk to ({@code proposed} to keep it), or empty for no visit this time (the citizen takes a short random walk instead).
     */
    @NotNull
    Optional<BlockPos> chooseSite(@NotNull ICitizenData citizen, @NotNull BlockPos proposed);

    /**
     * Asks a provider, never letting it break the citizen: an exception or a null answer keeps the proposed site.
     *
     * @param provider the provider (null = the default).
     * @param citizen  the citizen.
     * @param proposed the site MineColonies picked.
     * @return the answer.
     */
    @NotNull
    static Optional<BlockPos> resolve(@Nullable final ICitizenLeisureProvider provider, @NotNull final ICitizenData citizen, @NotNull final BlockPos proposed)
    {
        if (provider == null || provider == DEFAULT)
        {
            return Optional.of(proposed);
        }
        try
        {
            final Optional<BlockPos> answer = provider.chooseSite(citizen, proposed);
            return answer == null ? Optional.of(proposed) : answer;
        }
        catch (final RuntimeException e)
        {
            return Optional.of(proposed);
        }
    }
}
