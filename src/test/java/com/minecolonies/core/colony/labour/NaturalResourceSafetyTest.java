package com.minecolonies.core.colony.labour;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The record of placed blocks: a placement is kept across a save and a reload, a save without the record reads as nothing placed, a player's break forgets it, and a
 * full record counts every position as placed (it never lets a placed block be taken). The trunk rule of a natural tree: only the base, or a square of four, stands
 * on the ground.
 */
public class NaturalResourceSafetyTest
{
    @Test
    public void placementsSurviveAReload()
    {
        final LabourData data = new LabourData();
        final BlockPos post = new BlockPos(12, 64, -40);
        assertTrue(data.placements().add(post.asLong()));
        assertFalse(data.placements().add(post.asLong()));
        final LabourData back = new LabourData();
        back.read(data.write());
        assertTrue(back.placements().placed(post.asLong()));
        assertFalse(back.placements().placed(post.above().asLong()));
        assertFalse(back.placements().full());
        assertArrayEquals(data.placements().toArray(), back.placements().toArray());
    }

    @Test
    public void anOldSaveHasNothingPlacedAndWritesAsBefore()
    {
        final CompoundTag old = new CompoundTag();
        old.put("flags", new net.minecraft.nbt.ListTag());
        final LabourData data = new LabourData();
        data.read(old);
        assertEquals(0, data.placements().size());
        assertFalse(data.placements().full());
        assertEquals(old, data.write());
    }

    @Test
    public void aBreakForgetsThePlacement()
    {
        final LabourPlacements placements = new LabourPlacements();
        placements.add(5L);
        assertTrue(placements.remove(5L));
        assertFalse(placements.placed(5L));
        assertFalse(placements.remove(5L));
    }

    @Test
    public void aFullRecordCountsEveryPositionAsPlacedAndSurvivesAReload()
    {
        final LabourData data = new LabourData();
        final LabourPlacements placements = data.placements();
        for (long i = 0; i < LabourPlacements.MAX; i++)
        {
            placements.add(i);
        }
        assertFalse(placements.full());
        assertFalse(placements.placed(-1L));
        // one more: the record stops growing and nothing is taken out of the world any more
        assertTrue(placements.add(-1L));
        assertTrue(placements.full());
        assertTrue(placements.placed(-1L));
        assertTrue(placements.placed(Long.MAX_VALUE));
        // a break of a placed block does not reopen a full record
        placements.remove(3L);
        assertTrue(placements.placed(3L));
        final LabourData back = new LabourData();
        back.read(data.write());
        assertTrue(back.placements().full());
        assertTrue(back.placements().placed(new BlockPos(1, 2, 3).asLong()));
    }

    @Test
    public void onlyTheTrunkStandsOnTheGround()
    {
        final BlockPos base = new BlockPos(10, 70, 10);
        assertTrue(LabourSites.trunkOnGround(base, List.of(base)));
        assertTrue(LabourSites.trunkOnGround(base, List.of(base, base.east(), base.south(), base.east().south())));
        assertTrue(LabourSites.trunkOnGround(base, List.of(base, base.west(), base.north(), base.west().north())));
        // a post of logs next to the trunk on the ground is built, not grown
        assertFalse(LabourSites.trunkOnGround(base, List.of(base, base.east())));
        assertFalse(LabourSites.trunkOnGround(base, List.of(base.east())));
        assertFalse(LabourSites.trunkOnGround(base, List.of(base, base.east(), base.east(2), base.east(3))));
        assertFalse(LabourSites.trunkOnGround(base, List.of(base, base.east(), base.south(), base.east().south().above())));
        assertFalse(LabourSites.trunkOnGround(base, List.of()));
    }
}
