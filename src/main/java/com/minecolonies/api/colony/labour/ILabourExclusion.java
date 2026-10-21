package com.minecolonies.api.colony.labour;

import com.minecolonies.api.colony.IColony;
import net.minecraft.core.BlockPos;

/**
 * A mod's veto on the blocks labourers may dig: roads, plots, farmland, anything the player has laid out and that must not get a hole. Register through
 * {@link LabourExclusions#register}.
 * <p>
 * Rules for hook authors:
 * <ul>
 *   <li>Called on the server thread only, once per scanned block and before every labour action: it must be a constant-time lookup (a set or map keyed by the
 *   position or its column), with no scan, no allocation-heavy work and no I/O.</li>
 *   <li>No world access: do not read blocks, block entities or chunks, and never load a chunk. The position may lie in an unloaded chunk.</li>
 *   <li>Filter by {@code colony.getID()} (and dimension where it matters): answer only for the colony whose records you hold; any other colony is not yours.</li>
 *   <li>Do not throw. A hook that throws is read as "excluded" for that block (fail closed) and the error is logged, rate limited.</li>
 * </ul>
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
