package com.minecolonies.api.colony.labour;

import com.minecolonies.api.colony.IColony;
import net.minecraft.core.BlockPos;

/**
 * A mod's veto on the blocks labourers may dig: roads, plots, farmland, anything the player has laid out and that must not get a hole. Register through
 * {@link LabourExclusions#register}.
 */
@FunctionalInterface
public interface ILabourExclusion
{
    /**
     * @param colony the colony whose labourer wants the block.
     * @param pos    the block (a surface block, or the base of a tree).
     * @return true if labourers must leave the block alone.
     */
    boolean excluded(IColony colony, BlockPos pos);
}
