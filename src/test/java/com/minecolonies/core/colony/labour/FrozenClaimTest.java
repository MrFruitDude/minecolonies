package com.minecolonies.core.colony.labour;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Builder review c3 R3: a claim whose citizen stands where entities do not tick goes back after {@link LabourBoard#FROZEN_TICKS}.
 */
public class FrozenClaimTest
{
    @Test
    public void frozenAfterTheLimit()
    {
        assertFalse(LabourBoard.frozenUntilNow(1000L, 1000L));
        assertFalse(LabourBoard.frozenUntilNow(1000L, 1000L + LabourBoard.FROZEN_TICKS - 1));
        assertTrue(LabourBoard.frozenUntilNow(1000L, 1000L + LabourBoard.FROZEN_TICKS));
    }

    @Test
    public void onlyAWalkOutOfTheTickingAreaPutsTheRequestOnBackoff()
    {
        // builder review c5 C5-2 / review r9 c1 L2: the hut decides, else the colony centre; no level counts as not ticking
        final BlockPos hut = new BlockPos(10, 64, 10);
        final BlockPos centre = new BlockPos(0, 64, 0);
        final java.util.function.Predicate<BlockPos> onlyHut = hut::equals;
        final java.util.function.Predicate<BlockPos> onlyCentre = centre::equals;
        assertTrue(LabourBoard.destinationTicks(hut, centre, onlyHut));
        assertFalse(LabourBoard.destinationTicks(hut, centre, onlyCentre));
        assertTrue(LabourBoard.destinationTicks(null, centre, onlyCentre));
        assertFalse(LabourBoard.destinationTicks(null, centre, onlyHut));
        assertFalse(LabourBoard.destinationTicks(null, null, p -> true));
        assertFalse(LabourBoard.destinationTicks(hut, centre, null));
    }
}
