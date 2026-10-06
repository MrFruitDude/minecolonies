package com.minecolonies.core.client.render.worldevent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * FX2 R5 #16a: {@code WorldEventContext.submit} looked up the nearest colony every frame. Through {@link PerTickGate}
 * it is once per game tick, and at once on a new level.
 */
public class PerTickGateTest
{
    @Test
    public void threeHundredFramesOverTwentyTicksAreTwentyLookups()
    {
        final PerTickGate gate = new PerTickGate();
        final Object level = new Object();
        int due = 0;
        for (int frame = 0; frame < 300; frame++)
        {
            if (gate.due(level, 1000 + frame / 15))
            {
                due++;
            }
        }
        assertEquals("lookups over 300 frames at 15 frames per tick", 20, due);
    }

    @Test
    public void aNewLevelOrResetIsDueAtOnce()
    {
        final PerTickGate gate = new PerTickGate();
        final Object overworld = new Object();
        assertTrue("first frame", gate.due(overworld, 5));
        assertFalse("same tick", gate.due(overworld, 5));
        assertTrue("another level in the same tick", gate.due(new Object(), 5));
        assertTrue("back, same tick number", gate.due(overworld, 5));
        assertFalse(gate.due(overworld, 5));
        gate.reset();
        assertTrue("after reset", gate.due(overworld, 5));
        assertTrue("game time going back (a new world) is a new tick", gate.due(overworld, 4));
    }
}
