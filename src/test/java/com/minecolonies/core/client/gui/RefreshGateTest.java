package com.minecolonies.core.client.gui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * FX2 R5 #16c: the builder's resource window re-read every resource 20 times a second. Through {@link RefreshGate} it
 * reads on a change of the view or the inventory, and otherwise once a second.
 */
public class RefreshGateTest
{
    @Test
    public void aQuietWindowReadsOncePerSecond()
    {
        final RefreshGate gate = new RefreshGate(20);
        gate.markRead(42);
        int reads = 0;
        for (int tick = 0; tick < 100; tick++)
        {
            if (gate.due(42))
            {
                reads++;
            }
        }
        assertEquals("reads over 100 quiet GUI ticks (5 s)", 5, reads);
    }

    @Test
    public void aChangedSignatureReadsAtOnce()
    {
        final RefreshGate gate = new RefreshGate(20);
        assertTrue("first tick without markRead", gate.due(1));
        assertFalse(gate.due(1));
        assertTrue("inventory or view changed", gate.due(2));
        assertFalse(gate.due(2));
        for (int i = 0; i < 18; i++)
        {
            assertFalse(gate.due(2));
        }
        assertTrue("the 20th quiet tick", gate.due(2));
        assertEquals("a gate of 0 ticks still waits one", true, new RefreshGate(0).due(5));
    }
}
