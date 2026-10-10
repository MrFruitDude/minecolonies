package com.minecolonies.core.colony.workorders.collab;

import com.minecolonies.core.entity.ai.workers.util.BuildingProgressStage;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The rules of leases, the rescan after a lease was given back, block counts and persistence.
 */
public class WorkOrderCollabTest
{
    private static final BlockPos LEAD   = new BlockPos(10, 64, 10);
    private static final BlockPos HELPER = new BlockPos(20, 64, 10);
    private static final BlockPos CELL   = new BlockPos(5, 65, 5);

    @Test
    public void aLeasedPositionBelongsToOthersOnly()
    {
        final WorkOrderCollab c = new WorkOrderCollab();
        c.lease(CELL, HELPER, 100, false);
        assertTrue(c.isLeased(CELL));
        assertTrue(c.isLeasedByOther(CELL, LEAD));
        assertFalse(c.isLeasedByOther(CELL, HELPER));
        c.completeLease(CELL);
        assertFalse(c.isLeased(CELL));
        assertFalse("finished work is not given back", c.isRescanRequested());
    }

    @Test
    public void givingBackALeaseAsksTheLeadToScanAgain()
    {
        final WorkOrderCollab c = new WorkOrderCollab();
        c.lease(CELL, HELPER, 100, false);
        c.releaseLease(CELL);
        assertTrue(c.isRescanRequested());
        assertTrue(c.consumeRescan());
        assertFalse("asked once", c.consumeRescan());
    }

    @Test
    public void leasesRunOutUnlessRenewed()
    {
        final WorkOrderCollab c = new WorkOrderCollab();
        c.lease(CELL, HELPER, 100, false);
        c.lease(CELL.above(), LEAD, 100, false);
        assertTrue(c.expireLeases(100).isEmpty());
        c.renew(HELPER, 300);
        final var owners = c.expireLeases(200);
        assertEquals(1, owners.size());
        assertEquals(LEAD, owners.get(0));
        assertTrue(c.isLeased(CELL));
        assertTrue(c.isRescanRequested());
    }

    @Test
    public void theLeadsPendingPositionIsOneAndNotSaved()
    {
        final WorkOrderCollab c = new WorkOrderCollab();
        c.setLeadPending(LEAD, CELL);
        c.setLeadPending(LEAD, CELL.above());
        assertFalse(c.isLeased(CELL));
        assertTrue(c.isLeased(CELL.above()));
        assertEquals("the lead does not hold up a stage with his own position", 0, c.getOpenLeasesExcluding(LEAD));
        final CompoundTag tag = new CompoundTag();
        c.write(tag);
        final WorkOrderCollab read = new WorkOrderCollab();
        read.read(tag);
        assertEquals(0, read.getLeaseCount());
    }

    @Test
    public void leavingGivesLeasesBackAndKeepsTheHelperAway()
    {
        final WorkOrderCollab c = new WorkOrderCollab();
        c.addAssistant(HELPER, 0);
        c.lease(CELL, HELPER, 100, false);
        c.removeAssistant(HELPER, 500);
        assertEquals(0, c.getAssistantCount());
        assertFalse(c.isLeased(CELL));
        assertTrue(c.isRescanRequested());
        assertTrue(c.isOnCooldown(HELPER, 400));
        assertFalse(c.isOnCooldown(HELPER, 500));
    }

    @Test
    public void blocksCountByStageThenWithinTheStage()
    {
        final WorkOrderCollab c = new WorkOrderCollab();
        c.setStageTotal(BuildingProgressStage.CLEAR, 100);
        c.setStageTotal(BuildingProgressStage.BUILD_SOLID, 200);
        c.setStageTotal(BuildingProgressStage.DECORATE, 50);
        assertEquals(350, c.getTotalBlocks());
        c.setProgress(BlockPos.ZERO, BuildingProgressStage.CLEAR);
        assertEquals(0, c.getPlacedBlocks());
        for (int i = 0; i < 30; i++)
        {
            c.blockProcessed();
        }
        assertEquals(30, c.getPlacedBlocks());
        c.setProgress(BlockPos.ZERO, BuildingProgressStage.BUILD_SOLID);
        assertEquals("finished stages count in full, even if blocks were skipped", 100, c.getPlacedBlocks());
        c.blockProcessed();
        assertEquals(101, c.getPlacedBlocks());
        c.setProgress(BlockPos.ZERO, BuildingProgressStage.DECORATE);
        assertEquals(300, c.getPlacedBlocks());
        assertEquals(50, c.getRemainingBlocks());
    }

    @Test
    public void everythingSurvivesSaving()
    {
        final WorkOrderCollab c = new WorkOrderCollab();
        c.setProgress(new BlockPos(3, 0, 4), BuildingProgressStage.WEAK_SOLID);
        c.setStageTotal(BuildingProgressStage.WEAK_SOLID, 12);
        c.blockProcessed();
        c.addAssistant(HELPER, 77);
        c.lease(CELL, HELPER, 900, false);
        c.setScanCursor(new BlockPos(1, 1, 1));
        c.setPreviousLead(LEAD);
        c.removeAssistant(new BlockPos(30, 64, 30), 1234);

        final CompoundTag tag = new CompoundTag();
        c.write(tag);
        final WorkOrderCollab r = new WorkOrderCollab();
        r.read(tag);

        assertEquals(new BlockPos(3, 0, 4), r.getProgressPos());
        assertEquals(BuildingProgressStage.WEAK_SOLID, r.getProgressStage());
        assertEquals(1, r.getPlacedBlocks());
        assertEquals(12, r.getTotalBlocks());
        assertTrue(r.hasAssistant(HELPER));
        assertEquals(77, r.getAssistant(HELPER).since);
        assertTrue(r.isLeasedByOther(CELL, LEAD));
        assertEquals(new BlockPos(1, 1, 1), r.getScanCursor());
        assertEquals(LEAD, r.getPreviousLead());
        assertTrue(r.isOnCooldown(new BlockPos(30, 64, 30), 1000));
        assertNull(new WorkOrderCollab().getPreviousLead());
    }
}
