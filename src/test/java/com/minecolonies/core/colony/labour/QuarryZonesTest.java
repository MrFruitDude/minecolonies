package com.minecolonies.core.colony.labour;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The quarry zones' pure rules: size, count, overlap, removal, the terraces and the floor measured per column, uneven ground, and the saved form.
 */
public class QuarryZonesTest
{
    private static final DirectedOrder.Area AREA = DirectedOrder.Area.of(0, 0, 9, 9);

    private static int[] flat(final DirectedOrder.Area area, final int top)
    {
        final int[] tops = new int[area.width() * area.depth()];
        Arrays.fill(tops, top);
        return tops;
    }

    private static void set(final int[] tops, final DirectedOrder.Area area, final int x, final int z, final int top)
    {
        tops[(z - area.minZ()) * area.width() + (x - area.minX())] = top;
    }

    @Test
    public void terracesStepInOneBlockPerLayer()
    {
        final QuarryZones.Zone zone = new QuarryZones.Zone(1, AREA, flat(AREA, 64));
        // at and above the marked top: anywhere in the rectangle
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
        final DirectedOrder.Area big = DirectedOrder.Area.of(0, 0, 31, 31);
        final QuarryZones.Zone zone = new QuarryZones.Zone(1, big, flat(big, 64));
        assertEquals(57, zone.floor(15, 15, 8));
        assertTrue(QuarryZones.diggable(zone, new BlockPos(15, 57, 15), 8));
        assertFalse(QuarryZones.diggable(zone, new BlockPos(15, 56, 15), 8));
        assertFalse(QuarryZones.diggable(zone, new BlockPos(15, 63, 15), 1));
        assertTrue(QuarryZones.diggable(zone, new BlockPos(15, 64, 15), 1));
    }

    /**
     * Review C1 finding 3: one low column (a pit) no longer lowers the floor of the others: each column is measured from its own marked top.
     */
    @Test
    public void aPitDoesNotLowerTheOtherColumns()
    {
        final DirectedOrder.Area big = DirectedOrder.Area.of(0, 0, 31, 31);
        final int[] tops = flat(big, 70);
        set(tops, big, 10, 10, 66);
        final QuarryZones.Zone zone = new QuarryZones.Zone(1, big, tops);
        assertEquals(63, zone.floor(20, 20, 8));
        assertFalse(QuarryZones.diggable(zone, new BlockPos(20, 62, 20), 8));
        assertTrue(QuarryZones.diggable(zone, new BlockPos(20, 63, 20), 8));
        // the pit's own column: its top and down to its own floor
        assertEquals(59, zone.floor(10, 10, 8));
        assertTrue(QuarryZones.diggable(zone, new BlockPos(10, 66, 10), 8));
        assertFalse(QuarryZones.diggable(zone, new BlockPos(10, 58, 10), 8));
        // a column at 70 has no inset at 70 and above: the old rule (lowest column 66) let it go to 59
        assertTrue(QuarryZones.diggable(zone, new BlockPos(0, 70, 0), 8));
        assertFalse(QuarryZones.diggable(zone, new BlockPos(0, 69, 0), 8));
    }

    @Test
    public void unevenZonesAreRefused()
    {
        final QuarryZones zones = new QuarryZones();
        final int[] tops = flat(AREA, 64);
        set(tops, AREA, 4, 4, 55);
        assertEquals(9, QuarryZones.spread(tops));
        assertEquals(QuarryZones.Refusal.UNEVEN, zones.add(AREA, tops, 8).refusal());
        set(tops, AREA, 4, 4, 56);
        assertNotNull(zones.add(AREA, tops, 8).zone());
        assertEquals(QuarryZones.Refusal.BAD_AREA, new QuarryZones().add(AREA, new int[3], 8).refusal());
    }

    @Test
    public void sizeCountOverlapAndRemove()
    {
        final QuarryZones zones = new QuarryZones();
        final DirectedOrder.Area thin = DirectedOrder.Area.of(0, 0, 0, 5);
        final DirectedOrder.Area wide = DirectedOrder.Area.of(0, 0, 32, 5);
        assertEquals(QuarryZones.Refusal.BAD_AREA, zones.add(thin, flat(thin, 64), 8).refusal());
        assertEquals(QuarryZones.Refusal.BAD_AREA, zones.add(wide, flat(wide, 64), 8).refusal());
        final long before = zones.revision();
        final QuarryZones.Marked first = zones.add(AREA, flat(AREA, 64), 8);
        assertNotNull(first.zone());
        assertTrue(zones.revision() > before);
        final DirectedOrder.Area over = DirectedOrder.Area.of(9, 9, 12, 12);
        assertEquals(QuarryZones.Refusal.OVERLAP, zones.add(over, flat(over, 64), 8).refusal());
        assertNotNull(zones.at(new BlockPos(5, 0, 5)));
        assertNull(zones.at(new BlockPos(10, 0, 5)));
        for (int i = 1; i < QuarryZones.MAX_ZONES; i++)
        {
            final DirectedOrder.Area a = DirectedOrder.Area.of(100 * i, 0, 100 * i + 3, 3);
            assertNotNull(zones.add(a, flat(a, 64), 8).zone());
        }
        final DirectedOrder.Area extra = DirectedOrder.Area.of(-50, -50, -40, -40);
        assertEquals(QuarryZones.Refusal.TOO_MANY, zones.add(extra, flat(extra, 64), 8).refusal());
        assertTrue(zones.remove(first.zone().id()));
        assertFalse(zones.remove(first.zone().id()));
        assertNull(zones.at(new BlockPos(5, 0, 5)));
    }

    @Test
    public void savedFormKeepsZonesTopsAndIds()
    {
        final QuarryZones zones = new QuarryZones();
        final int[] tops = flat(AREA, 70);
        set(tops, AREA, 3, 7, 66);
        zones.add(AREA, tops, 8);
        final DirectedOrder.Area second = DirectedOrder.Area.of(20, 20, 25, 25);
        final QuarryZones.Zone two = zones.add(second, flat(second, 60), 8).zone();
        final QuarryZones loaded = new QuarryZones();
        loaded.read(zones.write());
        assertEquals(zones.zones(), loaded.zones());
        assertArrayEquals(tops, loaded.zones().get(0).tops());
        assertTrue(loaded.revision() > zones.revision());
        final DirectedOrder.Area third = DirectedOrder.Area.of(40, 40, 45, 45);
        assertTrue(loaded.add(third, flat(third, 60), 8).zone().id() > two.id());
    }

    @Test
    public void aZoneSavedWithOneSurfaceReadsWithThatTopEverywhere()
    {
        final CompoundTag item = new CompoundTag();
        item.putInt("id", 3);
        item.putIntArray("area", new int[] {0, 0, 9, 9});
        item.putInt("surface", 64);
        final ListTag list = new ListTag();
        list.add(item);
        final CompoundTag tag = new CompoundTag();
        tag.put("zones", list);
        final QuarryZones zones = new QuarryZones();
        zones.read(tag);
        assertEquals(1, zones.zones().size());
        assertEquals(64, zones.zones().get(0).top(9, 9));
        assertTrue(zones.used());
    }

    private static QuarryZones legacy(final int surface)
    {
        final CompoundTag item = new CompoundTag();
        item.putInt("id", 3);
        item.putIntArray("area", new int[] {0, 0, 9, 9});
        item.putInt("surface", surface);
        final ListTag list = new ListTag();
        list.add(item);
        final CompoundTag tag = new CompoundTag();
        tag.put("zones", list);
        final QuarryZones zones = new QuarryZones();
        zones.read(tag);
        return zones;
    }

    @Test
    public void aZoneSavedWithOneSurfaceIsPendingAndKeepsItsOldForm()
    {
        final QuarryZones zones = legacy(64);
        assertTrue(zones.pending(3));
        final CompoundTag saved = zones.write().getListOrEmpty("zones").getCompoundOrEmpty(0);
        assertEquals(64, saved.getIntOr("surface", 0));
        assertFalse(saved.contains("tops"));
        final QuarryZones again = new QuarryZones();
        again.read(zones.write());
        assertTrue(again.pending(3));
        // a new zone is never pending
        final DirectedOrder.Area other = DirectedOrder.Area.of(20, 20, 25, 25);
        assertFalse(again.pending(again.add(other, flat(other, 60), 8).zone().id()));
    }

    @Test
    public void migrationNeverDigsDeeperThanTheOldRule()
    {
        assertArrayEquals(new int[] {64, 65, 70}, QuarryZones.migratedTops(new int[] {60, 65, 70}, 64));
        final QuarryZones zones = legacy(64);
        final int[] sampled = flat(AREA, 66);
        set(sampled, AREA, 2, 2, 61);
        assertTrue(zones.migrate(3, sampled, 8));
        assertFalse(zones.pending(3));
        final QuarryZones.Zone zone = zones.zones().get(0);
        assertEquals(64, zone.top(2, 2));
        assertEquals(66, zone.top(5, 5));
        for (int x = 0; x <= 9; x++)
        {
            for (int z = 0; z <= 9; z++)
            {
                assertTrue(zone.floor(x, z, 8) >= 64 - 8 + 1);
            }
        }
        // migrated once: saved with its tops, read back as a normal zone
        final QuarryZones loaded = new QuarryZones();
        loaded.read(zones.write());
        assertFalse(loaded.pending(3));
        assertEquals(zone, loaded.zones().get(0));
        assertFalse(loaded.migrate(3, sampled, 8));
    }

    @Test
    public void anUnevenOldZoneStaysPending()
    {
        final QuarryZones zones = legacy(64);
        final int[] sampled = flat(AREA, 64);
        set(sampled, AREA, 9, 9, 80);
        assertFalse(zones.migrate(3, sampled, 8));
        assertTrue(zones.pending(3));
        assertTrue(zones.remove(3));
        assertFalse(zones.pending(3));
    }
}
