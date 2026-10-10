package com.minecolonies.api.colony.workorders;

import com.minecolonies.api.colony.buildings.views.IBuildingView;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

public interface IWorkOrderView extends IWorkOrder
{
    /**
     * Whether this work order should be shown in a specific building.
     *
     * @param view the building view.
     * @return a boolean
     */
    boolean shouldShowIn(IBuildingView view);

    /**
     * Deserialize the attributes and variables from the given buffer
     *
     * @param buf Byte buffer to deserialize.
     */
    void deserialize(@NotNull RegistryFriendlyByteBuf buf);

    /**
     * Checks if a builder may accept this workOrder while ignoring the distance to the builder.
     *
     * @param builderLocation position of the builders own hut.
     * @param builderLevel    level of the builders hut.
     * @return true if so.
     */
    boolean canBuildIgnoringDistance(@NotNull final BlockPos builderLocation, final int builderLevel);

    /**
     * Blocks of the structure done so far (all builders together). 0 when not known.
     */
    default int getPlacedBlocks()
    {
        return 0;
    }

    /**
     * Blocks of the structure to do in all, as estimated from the blueprint. 0 when not known.
     */
    default int getTotalBlocks()
    {
        return 0;
    }

    /**
     * Positions currently leased by helpers.
     */
    default int getLeaseCount()
    {
        return 0;
    }

    /**
     * The huts of the builders that help the lead (the lead is {@link #getClaimedBy()}).
     */
    default java.util.List<BlockPos> getAssistantHuts()
    {
        return java.util.List.of();
    }

    /**
     * The citizen ids of the builders that help the lead, in the order of {@link #getAssistantHuts()}; -1 if a hut has no builder.
     */
    default java.util.List<Integer> getAssistantCitizenIds()
    {
        return java.util.List.of();
    }
}
