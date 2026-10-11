package com.minecolonies.core.colony.requestsystem.priority;

import com.minecolonies.core.colony.requestsystem.requests.RsTestSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * RS3: per-building priority 1..9 and the alarm override.
 */
public class BuildingPrioritiesTest
{
    private static final BlockPos A = new BlockPos(10, 64, 10);
    private static final BlockPos B = new BlockPos(40, 64, -10);

    @Test
    public void defaultsAndClamp()
    {
        final BuildingPriorities p = new BuildingPriorities();
        assertEquals(5, p.priority(A));
        assertEquals(5, p.effective(A, 0));
        p.setPriority(A, 12);
        assertEquals(9, p.priority(A));
        p.setPriority(A, -4);
        assertEquals(1, p.priority(A));
        p.setPriority(A, 5);
        assertTrue("a default priority leaves no entry", p.snapshot().isEmpty());
    }

    @Test
    public void alarmOverridesAndExpires()
    {
        final BuildingPriorities p = new BuildingPriorities();
        p.setPriority(A, 2);
        p.setAlarm(A, true, 1000);
        assertTrue(p.isAlarm(A, 1000));
        assertEquals(1000 + BuildingPriorities.ALARM_TICKS, p.alarmUntil(A));
        assertEquals(BuildingPriorities.ALARM_PRIORITY, p.effective(A, 1000 + BuildingPriorities.ALARM_TICKS - 1));
        assertFalse(p.isAlarm(A, 1000 + BuildingPriorities.ALARM_TICKS));
        assertEquals(2, p.effective(A, 1000 + BuildingPriorities.ALARM_TICKS));
        assertEquals("the alarm does not touch the set priority", 2, p.priority(A));
    }

    @Test
    public void alarmOffAndCustomDuration()
    {
        final BuildingPriorities p = new BuildingPriorities();
        p.setAlarm(B, true, 0, 100);
        assertEquals(100, p.alarmUntil(B));
        p.setAlarm(B, false, 50);
        assertFalse(p.isAlarm(B, 50));
        assertTrue(p.snapshot().isEmpty());
    }

    @Test
    public void snapshotIsImmutableAndComplete()
    {
        final BuildingPriorities p = new BuildingPriorities();
        p.setPriority(A, 8);
        p.setAlarm(B, true, 10);
        final Map<BlockPos, BuildingPriorities.Entry> snap = p.snapshot();
        assertEquals(2, snap.size());
        assertEquals(new BuildingPriorities.Entry(8, 0), snap.get(A));
        assertEquals(new BuildingPriorities.Entry(5, 10 + BuildingPriorities.ALARM_TICKS), snap.get(B));
        try
        {
            snap.clear();
            throw new AssertionError("snapshot must be immutable");
        }
        catch (final UnsupportedOperationException expected)
        {
            // fine
        }
        p.setPriority(A, 1);
        assertEquals("a snapshot does not follow later writes", 8, snap.get(A).priority());
    }

    @Test
    public void pruneDropsRemovedBuildingsAndExpiredAlarms()
    {
        final BuildingPriorities p = new BuildingPriorities();
        p.setPriority(A, 8);
        p.setPriority(B, 3);
        assertEquals(1, p.prune(pos -> pos.equals(A)));
        assertEquals(8, p.priority(A));
        assertEquals(5, p.priority(B));

        p.setPriority(A, 5);
        p.setAlarm(A, true, 0, 10);
        assertEquals(1, p.expire(10));
        assertTrue(p.snapshot().isEmpty());
    }

    @Test
    public void revisionChangesOnlyOnRealChanges()
    {
        final BuildingPriorities p = new BuildingPriorities();
        final long r0 = p.revision();
        p.setPriority(A, 5);
        assertEquals(r0, p.revision());
        p.setPriority(A, 6);
        assertTrue(p.revision() > r0);
    }

    @Test
    public void nbtRoundTrip()
    {
        final BuildingPriorities p = new BuildingPriorities();
        p.setPriority(A, 9);
        p.setPriority(B, 1);
        p.setAlarm(B, true, 500);
        final CompoundTag tag = p.write();

        final BuildingPriorities q = new BuildingPriorities();
        q.setPriority(new BlockPos(1, 1, 1), 2);
        q.read(tag);
        assertEquals(p.snapshot(), q.snapshot());
        assertEquals(1, q.priority(B));
        assertTrue(q.isAlarm(B, 500 + BuildingPriorities.ALARM_TICKS - 1));
        assertEquals(5, q.priority(new BlockPos(1, 1, 1)));
    }

    @Test
    public void anOldSaveWithoutTheTagOrWithGarbageLoadsEmpty()
    {
        final BuildingPriorities p = new BuildingPriorities();
        p.setPriority(A, 9);
        p.read(new CompoundTag());
        assertTrue(p.snapshot().isEmpty());

        final CompoundTag garbage = new CompoundTag();
        garbage.putString("Entries", "not a list");
        p.read(garbage);
        assertTrue(p.snapshot().isEmpty());
    }

    @Test
    public void waitClockStartsAtFirstSightAndForgets()
    {
        final BuildingPriorities p = new BuildingPriorities();
        final var token = RsTestSupport.token();
        assertEquals(0, p.waited(token, 1000));
        assertEquals(250, p.waited(token, 1250));
        p.forgetWait(token);
        assertEquals(0, p.waited(token, 2000));
        final var other = RsTestSupport.token();
        p.waited(other, 2000);
        p.pruneWaits(Set.of(other)::contains);
        assertEquals(0, p.waited(token, 3000));
        assertEquals(1000, p.waited(other, 3000));
    }
}
