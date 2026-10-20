package com.minecolonies.core.colony.labour;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The bookkeeping of a directed gathering order: what is gathered and handed in never passes the amount, whatever the number of workers; what a worker carries is
 * his when he leaves; the order survives a save and a reload, and a save without orders reads as none.
 */
public class DirectedOrderTest
{
    private static DirectedOrder order(final int amount)
    {
        final DirectedOrder order = new DirectedOrder(7, DirectedOrder.Resource.COBBLESTONE, amount, DirectedOrder.Area.of(10, 20, 0, 5), new BlockPos(1, 2, 3), 100L);
        assertTrue(order.assign(1));
        assertTrue(order.assign(2));
        return order;
    }

    @Test
    public void areaIsNormalisedAndInclusive()
    {
        final DirectedOrder.Area area = DirectedOrder.Area.of(10, 20, 0, 5);
        assertEquals(0, area.minX());
        assertEquals(5, area.minZ());
        assertEquals(11, area.width());
        assertEquals(16, area.depth());
        assertTrue(area.contains(new BlockPos(10, 64, 20)));
        assertTrue(area.contains(new BlockPos(0, -5, 5)));
        assertFalse(area.contains(new BlockPos(11, 64, 20)));
        assertFalse(area.contains(new BlockPos(5, 64, 21)));
    }

    @Test
    public void twoWorkersNeverGatherOrHandInPastTheAmount()
    {
        final DirectedOrder order = order(5);
        int reserved = 0;
        // both workers race for blocks: every reservation is one unit, committed never passes the amount
        for (int step = 0; step < 20; step++)
        {
            final int worker = step % 2 == 0 ? 1 : 2;
            if (order.reserve(worker))
            {
                reserved++;
                assertTrue("committed " + order.committed(), order.committed() <= 5);
                // a block may drop more than one: only the reserved unit counts
                assertEquals(1, order.gathered(worker, 3));
            }
        }
        assertEquals(5, reserved);
        assertEquals(5, order.committed());
        assertFalse(order.canReserve(1));
        assertFalse(order.canReserve(2));
        // each hands in more than he carried for the order: only his share counts
        final int carried1 = order.carried(1);
        final int carried2 = order.carried(2);
        assertEquals(5, carried1 + carried2);
        assertEquals(carried1, order.handedIn(1, carried1 + 10));
        assertTrue(order.active());
        assertEquals(carried2, order.handedIn(2, carried2 + 10));
        assertEquals(5, order.delivered());
        assertEquals(DirectedOrder.State.COMPLETED, order.state());
        assertEquals(0, order.handedIn(2, 4));
        assertEquals(5, order.delivered());
    }

    @Test
    public void goodsWithoutAReservationAreTheWorkersOwn()
    {
        final DirectedOrder order = order(3);
        // the rest of a started tree: no reservation, nothing counted
        assertEquals(0, order.gathered(1, 1));
        assertEquals(0, order.carried(1));
        assertTrue(order.reserve(1));
        // a gravel block that dropped flint: the reservation goes, nothing counted
        assertEquals(0, order.gathered(1, 0));
        assertEquals(0, order.committed());
    }

    @Test
    public void handInIsCappedByWhatHeCarriedForTheOrder()
    {
        final DirectedOrder order = order(10);
        assertTrue(order.reserve(1));
        order.gathered(1, 1);
        assertEquals(1, order.toHandIn(1));
        // he holds 40 logs of his own: one counts
        assertEquals(1, order.handedIn(1, 40));
        assertEquals(1, order.delivered());
        assertEquals(0, order.carried(1));
    }

    @Test
    public void theInventoryIsBelievedWhenHeUsedTheGoods()
    {
        final DirectedOrder order = order(10);
        for (int i = 0; i < 4; i++)
        {
            assertTrue(order.reserve(1));
            order.gathered(1, 1);
        }
        assertEquals(4, order.carried(1));
        order.clampCarried(1, 1);
        assertEquals(1, order.carried(1));
        order.clampCarried(1, 9);
        assertEquals(1, order.carried(1));
    }

    @Test
    public void aWorkerWhoLeavesTakesNothingOfTheOrderButFreesItsShare()
    {
        final DirectedOrder order = order(2);
        assertTrue(order.reserve(1));
        order.gathered(1, 1);
        assertTrue(order.reserve(2));
        order.gathered(2, 1);
        assertFalse(order.canReserve(1));
        assertTrue(order.unassign(2));
        assertEquals(1, order.committed());
        assertTrue(order.canReserve(1));
        assertFalse(order.has(2));
        assertEquals(0, order.handedIn(2, 1));
    }

    @Test
    public void aStoppedWorkerGivesBackHisReservation()
    {
        final DirectedOrder order = order(1);
        assertTrue(order.reserve(1));
        assertFalse(order.reserve(2));
        order.unreserve(1);
        assertTrue(order.reserve(2));
    }

    @Test
    public void finishingClearsTheWorkers()
    {
        final DirectedOrder order = order(4);
        order.finish(DirectedOrder.State.CANCELLED, 500L);
        assertFalse(order.active());
        assertTrue(order.workers().isEmpty());
        assertFalse(order.reserve(1));
        assertFalse(order.assign(3));
        assertEquals(500L, order.finished());
    }

    @Test
    public void boundsAreEnforced()
    {
        final DirectedOrder big = new DirectedOrder(1, DirectedOrder.Resource.DIRT, 99999, DirectedOrder.Area.of(0, 0, 1, 1), null, 0L);
        assertEquals(DirectedOrder.MAX_AMOUNT, big.amount());
        final DirectedOrder none = new DirectedOrder(1, DirectedOrder.Resource.DIRT, -3, DirectedOrder.Area.of(0, 0, 1, 1), null, 0L);
        assertEquals(1, none.amount());
        final DirectedOrder crowd = new DirectedOrder(1, DirectedOrder.Resource.DIRT, 4, DirectedOrder.Area.of(0, 0, 1, 1), null, 0L);
        for (int i = 0; i < DirectedOrder.MAX_WORKERS; i++)
        {
            assertTrue(crowd.assign(i + 1));
        }
        assertFalse(crowd.assign(99));
        assertFalse(crowd.assign(1));
    }

    @Test
    public void saveAndReloadKeepsTheOrderButNotThePendingBlock()
    {
        final DirectedOrder order = order(6);
        assertTrue(order.reserve(1));
        order.gathered(1, 1);
        assertTrue(order.reserve(1));
        order.gathered(1, 1);
        assertEquals(2, order.handedIn(1, 2));
        assertTrue(order.reserve(2));
        order.gathered(2, 1);
        assertTrue(order.reserve(2));
        order.blocked(DirectedOrder.Why.NO_TOOL);

        final DirectedOrder back = DirectedOrder.read(order.write());
        assertNotNull(back);
        assertEquals(7, back.id());
        assertEquals(DirectedOrder.Resource.COBBLESTONE, back.resource());
        assertEquals(6, back.amount());
        assertEquals(order.area(), back.area());
        assertEquals(new BlockPos(1, 2, 3), back.destination());
        assertEquals(2, back.delivered());
        assertEquals(DirectedOrder.Why.NO_TOOL, back.why());
        assertEquals(Map.of(1, 0, 2, 1), back.carriedByWorker());
        // the block he was breaking is not saved: committed is what was delivered and carried
        assertEquals(3, back.committed());
        assertEquals(java.util.List.of(1, 2), back.workers());
    }

    @Test
    public void automaticDestinationAndFinishedOrdersSurviveAReload()
    {
        final DirectedOrder order = new DirectedOrder(3, DirectedOrder.Resource.WOOD, 8, DirectedOrder.Area.of(0, 0, 3, 3), null, 0L);
        order.assign(5);
        order.finish(DirectedOrder.State.CANCELLED, 900L);
        final DirectedOrder back = DirectedOrder.read(order.write());
        assertNotNull(back);
        assertNull(back.destination());
        assertEquals(DirectedOrder.State.CANCELLED, back.state());
        assertEquals(900L, back.finished());
        assertTrue(back.workers().isEmpty());
    }

    @Test
    public void theDiggingFloorIsKeptAndAnOldSaveHasNone()
    {
        final DirectedOrder order = new DirectedOrder(4, DirectedOrder.Resource.GRAVEL, 8, DirectedOrder.Area.of(0, 0, 3, 3), null, 0L, 61);
        final DirectedOrder back = DirectedOrder.read(order.write());
        assertNotNull(back);
        assertEquals(61, back.floor());
        final CompoundTag old = order.write();
        old.remove("floor");
        assertEquals(DirectedOrder.NO_FLOOR, DirectedOrder.read(old).floor());
    }

    @Test
    public void foreignOrBrokenTagsAreDropped()
    {
        final CompoundTag tag = order(3).write();
        tag.putString("res", "DIAMONDS");
        assertNull(DirectedOrder.read(tag));
        final CompoundTag big = order(3).write();
        big.putIntArray("area", new int[] {0, 0, 1000, 5});
        assertNull(DirectedOrder.read(big));
        final CompoundTag broken = order(3).write();
        broken.remove("area");
        assertNull(DirectedOrder.read(broken));
        final CompoundTag state = order(3).write();
        state.putString("state", "SOMETHING_NEWER");
        state.putInt("done", 999);
        final DirectedOrder clamped = DirectedOrder.read(state);
        assertNotNull(clamped);
        assertEquals(DirectedOrder.State.ACTIVE, clamped.state());
        assertEquals(3, clamped.delivered());
    }

    @Test
    public void labourDataWithoutOrdersIsReadAsBeforeAndWritesNoNewTag()
    {
        final CompoundTag old = new CompoundTag();
        final ListTag flags = new ListTag();
        final CompoundTag flag = new CompoundTag();
        flag.putLong("pos", new BlockPos(4, 5, 6).asLong());
        flag.putString("flag", "PREFER");
        flags.add(flag);
        old.put("flags", flags);
        final LabourData data = new LabourData();
        data.read(old);
        assertTrue(data.directed().isEmpty());
        assertEquals(LabourData.TableFlag.PREFER, data.flagOf(new BlockPos(4, 5, 6)));
        assertEquals(old, data.write());
        assertEquals(1, data.takeOrderId());
    }

    @Test
    public void labourDataKeepsOrdersAndTheirIdsAcrossAReload()
    {
        final LabourData data = new LabourData();
        final int first = data.takeOrderId();
        final DirectedOrder order = new DirectedOrder(first, DirectedOrder.Resource.GRAVEL, 12, DirectedOrder.Area.of(0, 0, 9, 9), null, 0L);
        order.assign(4);
        data.directed().add(order);
        data.bumpDirected();
        final long revision = data.directedRevision();

        final LabourData back = new LabourData();
        back.read(data.write());
        assertEquals(1, back.directed().size());
        assertEquals(first, back.directed().get(0).id());
        assertTrue(back.directed().get(0).has(4));
        // a new id never repeats a saved one; the revision changes so a stale cancel from before the reload is refused
        assertTrue(back.takeOrderId() > first);
        assertTrue(back.directedRevision() != revision);
    }
}
