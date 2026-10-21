package com.minecolonies.core.colony.labour;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The quarry zones' pure rules: size, count, overlap, removal, the terraces and the floor, and the saved form.
 */
public class QuarryZonesTest
{
    private static final DirectedOrder.Area AREA = DirectedOrder.Area.of(0, 0, 9, 9);

    @Test
    public void terracesStepInOneBlockPerLayer()
    {
        final QuarryZones.Zone zone = new QuarryZones.Zone(1, AREA, 64);
        // at and above the surface: anywhere in the rectangle
        assertTrue(QuarryZones.diggable(zone, new BlockPos(0, 64, 0), 8));
        assertTrue(QuarryZones.diggable(zone, new BlockPos(9, 66, 9), 8));
        // one layer down: not on the edge, one block in
        assertFalse(QuarryZones.diggable(zone, new BlockPos(0, 63, 5), 8));
        assertTrue(QuarryZones.diggable(zone, new BlockPos(1, 63, 5), 8));
        // three layers down: three blocks in
        assertFalse(QuarryZones.diggable(zone, new BlockPos(2, 61, 5), 8));
        assertTrue(QuarryZones.diggable(zone, new BlockPos(3, 61, 6), 8));
        // outside the rectangle never
        assertFalse(QuarryZones.diggable(zone, new BlockPos(10, 64, 5), 8));
    }

    @Test
    public void neverBelowTheDepth()
    {
        final QuarryZones.Zone zone = new QuarryZones.Zone(1, DirectedOrder.Area.of(0, 0, 31, 31), 64);
        assertEquals(57, zone.floor(8));
        assertTrue(QuarryZones.diggable(zone, new BlockPos(15, 57, 15), 8));
        assertFalse(QuarryZones.diggable(zone, new BlockPos(15, 56, 15), 8));
        assertFalse(QuarryZones.diggable(zone, new BlockPos(15, 63, 15), 1));
        assertTrue(QuarryZones.diggable(zone, new BlockPos(15, 64, 15), 1));
    }

    @Test
    public void sizeCountOverlapAndRemove()
    {
        final QuarryZones zones = new QuarryZones();
        assertEquals(QuarryZones.Refusal.BAD_AREA, zones.add(DirectedOrder.Area.of(0, 0, 0, 5), 64).refusal());
        assertEquals(QuarryZones.Refusal.BAD_AREA, zones.add(DirectedOrder.Area.of(0, 0, 32, 5), 64).refusal());
        final long before = zones.revision();
        final QuarryZones.Marked first = zones.add(AREA, 64);
        assertNotNull(first.zone());
        assertTrue(zones.revision() > before);
        assertEquals(QuarryZones.Refusal.OVERLAP, zones.add(DirectedOrder.Area.of(9, 9, 12, 12), 64).refusal());
        assertNotNull(zones.at(new BlockPos(5, 0, 5)));
        assertNull(zones.at(new BlockPos(10, 0, 5)));
        for (int i = 1; i < QuarryZones.MAX_ZONES; i++)
        {
            assertNotNull(zones.add(DirectedOrder.Area.of(100 * i, 0, 100 * i + 3, 3), 64).zone());
        }
        assertEquals(QuarryZones.Refusal.TOO_MANY, zones.add(DirectedOrder.Area.of(-50, -50, -40, -40), 64).refusal());
        assertTrue(zones.remove(first.zone().id()));
        assertFalse(zones.remove(first.zone().id()));
        assertNull(zones.at(new BlockPos(5, 0, 5)));
    }

    @Test
    public void savedFormKeepsZonesAndIds()
    {
        final QuarryZones zones = new QuarryZones();
        zones.add(AREA, 70);
        final QuarryZones.Zone second = zones.add(DirectedOrder.Area.of(20, 20, 25, 25), 60).zone();
        final QuarryZones loaded = new QuarryZones();
        loaded.read(zones.write());
        assertEquals(zones.zones(), loaded.zones());
        assertTrue(loaded.revision() > zones.revision());
        // a new zone after the load takes a fresh id
        final QuarryZones.Zone third = loaded.add(DirectedOrder.Area.of(40, 40, 45, 45), 60).zone();
        assertTrue(third.id() > second.id());
    }
}
