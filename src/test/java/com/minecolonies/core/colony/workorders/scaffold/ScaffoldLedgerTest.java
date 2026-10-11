package com.minecolonies.core.colony.workorders.scaffold;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScaffoldLedgerTest
{
    private static final BlockPos HUT_A = new BlockPos(10, 64, 10);
    private static final BlockPos HUT_B = new BlockPos(20, 64, 10);

    @Test
    public void survivesASaveAndLoad()
    {
        final ScaffoldLedger ledger = new ScaffoldLedger();
        ledger.add(new BlockPos(1, 65, 1), HUT_A, 0);
        ledger.add(new BlockPos(1, 66, 1), HUT_A, 0);
        ledger.add(new BlockPos(2, 66, 1), HUT_B, 1);
        final CompoundTag tag = new CompoundTag();
        ledger.write(tag);

        final ScaffoldLedger loaded = new ScaffoldLedger();
        loaded.add(new BlockPos(99, 99, 99), HUT_A, 0);
        loaded.read(tag);
        assertEquals(3, loaded.size());
        assertFalse(loaded.contains(new BlockPos(99, 99, 99)));
        assertEquals(HUT_B, loaded.get(new BlockPos(2, 66, 1)).hut());
        assertEquals(1, loaded.get(new BlockPos(2, 66, 1)).distance());
        assertEquals(java.util.Set.of(HUT_A, HUT_B), loaded.owners());
    }

    @Test
    public void takesDownTopDownAndTheEndOfAnArmFirst()
    {
        final ScaffoldLedger ledger = new ScaffoldLedger();
        // a column of 3 and an arm of 2 at its top
        ledger.add(new BlockPos(0, 65, 0), HUT_A, 0);
        ledger.add(new BlockPos(0, 66, 0), HUT_A, 0);
        ledger.add(new BlockPos(0, 67, 0), HUT_A, 0);
        ledger.add(new BlockPos(1, 67, 0), HUT_A, 1);
        ledger.add(new BlockPos(2, 67, 0), HUT_A, 2);
        final List<BlockPos> order = ledger.topDown(null).stream().map(ScaffoldLedger.Cell::pos).toList();
        assertEquals(List.of(new BlockPos(2, 67, 0), new BlockPos(1, 67, 0), new BlockPos(0, 67, 0), new BlockPos(0, 66, 0), new BlockPos(0, 65, 0)), order);
    }

    @Test
    public void filtersByTheHutThatPlacedIt()
    {
        final ScaffoldLedger ledger = new ScaffoldLedger();
        ledger.add(new BlockPos(0, 65, 0), HUT_A, 0);
        ledger.add(new BlockPos(5, 65, 0), HUT_B, 0);
        assertEquals(1, ledger.topDown(HUT_B).size());
        assertEquals(2, ledger.topDown(null).size());
    }

    @Test
    public void tellsTheOwnerAboutChangesOnly()
    {
        final ScaffoldLedger ledger = new ScaffoldLedger();
        final int[] changes = {0};
        ledger.setOnChange(() -> changes[0]++);
        ledger.add(BlockPos.ZERO, HUT_A, 0);
        assertEquals(1, changes[0]);
        assertFalse(ledger.remove(new BlockPos(5, 5, 5)));
        assertEquals(1, changes[0]);
        assertTrue(ledger.remove(BlockPos.ZERO));
        assertEquals(2, changes[0]);
        assertTrue(ledger.isEmpty());
    }

    @Test
    public void handsOverToAnotherLedger()
    {
        final ScaffoldLedger a = new ScaffoldLedger();
        a.add(new BlockPos(1, 1, 1), HUT_A, 0);
        final ScaffoldLedger b = new ScaffoldLedger();
        b.addAll(a);
        a.clear();
        assertTrue(a.isEmpty());
        assertEquals(1, b.size());
    }

    @Test
    public void groupsCellsThatTouch()
    {
        final ScaffoldLedger ledger = new ScaffoldLedger();
        // a tower with an arm, and another tower far away
        ledger.add(new BlockPos(0, 65, 0), HUT_A, 0);
        ledger.add(new BlockPos(0, 66, 0), HUT_A, 0);
        ledger.add(new BlockPos(1, 66, 0), HUT_A, 1);
        ledger.add(new BlockPos(9, 65, 9), HUT_A, 0);
        final List<List<ScaffoldLedger.Cell>> groups = ledger.components(null);
        assertEquals(2, groups.size());
        assertEquals(3, groups.stream().filter(g -> g.size() == 3).findFirst().orElseThrow().size());
        // top down inside a group, the end of the arm before the column top
        final List<ScaffoldLedger.Cell> tower = groups.stream().filter(g -> g.size() == 3).findFirst().orElseThrow();
        assertEquals(new BlockPos(1, 66, 0), tower.get(0).pos());
        assertEquals(new BlockPos(0, 65, 0), tower.get(2).pos());
    }
}
