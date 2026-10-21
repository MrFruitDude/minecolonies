package com.minecolonies.core.colony.labour;

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
        // builder review c5 C5-2: his hut still ticks, he walked out: back-off; the hut stopped ticking too (a player walked away): neutral
        assertTrue(LabourBoard.frozenPenalized(true));
        assertFalse(LabourBoard.frozenPenalized(false));
    }
}
