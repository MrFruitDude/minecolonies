package com.minecolonies.api.colony.requestsystem.request;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * RS2: the wait reasons are stored by ordinal in saves and network buffers.
 */
public class WaitReasonTest
{
    @Test
    public void everyReasonSurvivesItsOrdinal()
    {
        for (final WaitReason reason : WaitReason.values())
        {
            assertEquals(reason, WaitReason.fromOrdinal(reason.ordinal()));
        }
    }

    @Test
    public void anUnknownOrdinalMeansNotWaiting()
    {
        assertEquals(WaitReason.NONE, WaitReason.fromOrdinal(-1));
        assertEquals(WaitReason.NONE, WaitReason.fromOrdinal(WaitReason.values().length));
        assertEquals(WaitReason.NONE, WaitReason.fromOrdinal(127));
    }

    @Test
    public void noneIsTheZeroOrdinal()
    {
        // Old saves and old packets carry no reason: that must read as NONE.
        assertEquals(0, WaitReason.NONE.ordinal());
        assertFalse(WaitReason.NONE.isWaiting());
        assertTrue(WaitReason.NO_SOURCE.isWaiting());
    }

    @Test
    public void everyReasonHasItsOwnTranslationKey()
    {
        final Set<String> keys = new HashSet<>();
        for (final WaitReason reason : WaitReason.values())
        {
            assertTrue(reason.translationKey(), reason.translationKey().startsWith("com.minecolonies.coremod.request.wait."));
            assertTrue("duplicate key " + reason.translationKey(), keys.add(reason.translationKey()));
        }
    }

    @Test
    public void onlyActionableReasonsAreWarnings()
    {
        assertEquals(WaitReason.Severity.WARNING, WaitReason.PLAYER_REQUIRED.severity());
        assertEquals(WaitReason.Severity.WARNING, WaitReason.NO_CRAFTER.severity());
        assertEquals(WaitReason.Severity.WARNING, WaitReason.NO_COURIER.severity());
        assertEquals(WaitReason.Severity.INFO, WaitReason.NO_SOURCE.severity());
        assertEquals(WaitReason.Severity.INFO, WaitReason.AWAITING_CRAFT.severity());
        assertEquals(WaitReason.Severity.NONE, WaitReason.NONE.severity());
    }
}
