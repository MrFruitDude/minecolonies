package com.minecolonies.core.colony.labour;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * Forgiving one hut debt: exactly that debt goes, the other stays as it was (its re-ask timer included), and a second forgive finds nothing. (The items are not
 * needed for these rules and are left null: no registry in a unit test.)
 */
public class HutDebtsTest
{
    private static final BlockPos A = new BlockPos(10, 64, 10);
    private static final BlockPos B = new BlockPos(30, 64, 10);

    @Test
    public void forgivingOneOfTwoKeepsTheOtherExactly()
    {
        final HutDebts debts = new HutDebts();
        final HutDebts.Debt a = new HutDebts.Debt(A, null, false);
        final HutDebts.Debt b = new HutDebts.Debt(B, null, true);
        debts.record(a, 1000L);
        debts.record(b, 2500L);
        assertEquals(a, debts.removeOne(A));
        assertEquals(List.of(b), debts.debts());
        assertEquals(Long.valueOf(2500L), debts.askedAt(b));
        assertNull(debts.askedAt(a));
        // a second forgive of the same hut finds nothing and changes nothing
        assertNull(debts.removeOne(A));
        assertEquals(List.of(b), debts.debts());
        assertEquals(Long.valueOf(2500L), debts.askedAt(b));
    }

    @Test
    public void aStandingHutsOwnDebtGoesBeforeAGoneOneAtTheSamePlace()
    {
        final HutDebts debts = new HutDebts();
        final HutDebts.Debt gone = new HutDebts.Debt(A, null, true);
        final HutDebts.Debt standing = new HutDebts.Debt(A, null, false);
        debts.record(gone, 100L);
        debts.record(standing, 200L);
        assertEquals(standing, debts.removeOne(A));
        assertEquals(List.of(gone), debts.debts());
        assertEquals(Long.valueOf(100L), debts.askedAt(gone));
        assertEquals(gone, debts.removeOne(A));
        assertNull(debts.removeOne(A));
        assertEquals(0, debts.count());
    }

    @Test
    public void noServerColonyForgivesNothing()
    {
        assertFalse(HutDebts.forgive(null, A));
    }
}
