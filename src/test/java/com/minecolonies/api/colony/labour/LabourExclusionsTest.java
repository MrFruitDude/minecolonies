package com.minecolonies.api.colony.labour;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The vetoes of other mods: a hook that throws holds (fail closed) and breaks no other hook or scan; its failures are logged at most once a minute.
 */
public class LabourExclusionsTest
{
    @Test
    public void aThrowingHookExcludesTheBlockAndDoesNotEscape()
    {
        final ILabourExclusion never = (colony, pos) -> false;
        final ILabourExclusion broken = (colony, pos) -> {
            throw new IllegalStateException("broken hook");
        };
        LabourExclusions.register(never);
        try
        {
            assertFalse(LabourExclusions.excluded(null, BlockPos.ZERO));
            LabourExclusions.register(broken);
            assertTrue(LabourExclusions.excluded(null, BlockPos.ZERO));
            assertTrue(LabourExclusions.excluded(null, new BlockPos(5, 64, 5)));
            LabourExclusions.unregister(broken);
            assertFalse(LabourExclusions.excluded(null, BlockPos.ZERO));
        }
        finally
        {
            LabourExclusions.unregister(broken);
            LabourExclusions.unregister(never);
        }
    }

    @Test
    public void aFailingHookIsLoggedAtMostOnceAMinute()
    {
        final ILabourExclusion hook = (colony, pos) -> false;
        try
        {
            assertTrue(LabourExclusions.shouldLog(hook, 1_000L));
            assertFalse(LabourExclusions.shouldLog(hook, 1_000L + LabourExclusions.LOG_EVERY_MS - 1));
            assertTrue(LabourExclusions.shouldLog(hook, 1_000L + LabourExclusions.LOG_EVERY_MS));
            // the clock went back: logged again
            assertTrue(LabourExclusions.shouldLog(hook, 10L));
        }
        finally
        {
            LabourExclusions.unregister(hook);
        }
    }
}
