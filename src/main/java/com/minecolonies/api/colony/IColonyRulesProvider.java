package com.minecolonies.api.colony;

import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.api.colony.jobs.registry.JobEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lets another mod (Colony Command) set colony rules that MineColonies applies at its own call sites: the raid level, how far
 * building claims reach, who may be hired, where the quest log opens.
 * <p>
 * Every method is read at the call site each time it is used, so a provider may change its answer at any moment. The
 * default provider answers "unchanged" everywhere and leaves MineColonies exactly as it is without one.
 * <p>
 * Register one with {@link com.minecolonies.api.IMinecoloniesAPI#setColonyRulesProvider(IColonyRulesProvider)}. There is
 * one provider at a time; the last one set wins.
 */
public interface IColonyRulesProvider
{
    /**
     * The provider used when no mod set one: nothing changes.
     */
    IColonyRulesProvider DEFAULT = new IColonyRulesProvider() {};

    /**
     * Server. Multiplies the colony's raid level (citizens, building levels, research) before raids are sized and chosen.
     * A value that is not a finite number of at least zero is ignored (treated as 1.0).
     *
     * @param colony the colony.
     * @return the multiplier, default 1.0.
     */
    default double raidLevelMultiplier(@NotNull final IColony colony)
    {
        return 1.0D;
    }

    /**
     * Server. The farthest a building's claim may reach, in chunks from the chunk of the colony's centre (the larger of
     * the x and z distance, so the claim area is a square). A building never claims a chunk beyond it, unclaiming is not
     * limited, and the founding claim and direct {@code tryClaimBuilding} calls (an add-on's own ring purchase) are not
     * limited either. MineColonies' {@code maxColonySize} still applies.
     *
     * @param colony the colony.
     * @return the limit in chunks, or a negative number for no limit (the default).
     */
    default int claimRadiusLimit(@NotNull final IColony colony)
    {
        return -1;
    }

    /**
     * Server. Whether a citizen may not be hired for a job in a building, checked when a player hires through
     * MineColonies' hire window. A refusal leaves the citizen unassigned and shows the reason to the player.
     *
     * @param citizen  the citizen.
     * @param building the building.
     * @param job      the job of the module the citizen would be assigned to.
     * @return the reason shown to the player, or null when the hire is allowed (the default).
     */
    @Nullable
    default Component hireRefusal(@NotNull final ICitizenData citizen, @NotNull final IBuilding building, @NotNull final JobEntry job)
    {
        return null;
    }

    /**
     * Client. The same question for MineColonies' hire window: a refused citizen is greyed out, cannot be hired from the
     * list, and the reason is shown on its row. Answer from data synced to the client.
     *
     * @param citizen  the citizen view.
     * @param building the building view.
     * @param job      the job of the module the citizen would be assigned to.
     * @return the reason, or null when the hire is allowed (the default).
     */
    @Nullable
    default Component hireRefusalView(@NotNull final ICitizenDataView citizen, @NotNull final IBuildingView building, @NotNull final JobEntry job)
    {
        return null;
    }

    /**
     * Client. Called when a player uses the quest log item, before MineColonies opens its quest log window.
     *
     * @param player   the client player.
     * @param colony   the colony the item is linked to, null when it is not linked to a loaded colony.
     * @param questLog the item.
     * @return true when the provider opened its own screen (MineColonies then opens nothing), false for MineColonies'
     * window (the default).
     */
    default boolean openQuestLog(@NotNull final Player player, @Nullable final IColonyView colony, @NotNull final ItemStack questLog)
    {
        return false;
    }
}
