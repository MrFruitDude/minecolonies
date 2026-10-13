package com.minecolonies.core.colony.workorders.collab;

import com.minecolonies.core.entity.ai.workers.util.BuildingProgressStage;
import net.minecraft.nbt.CompoundTag;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Whether a second builder pays off for a structure: the shapes of the scaffold benchmark are the cases (a helper is no help for a small ring or a flat wall,
 * and one for a tall ring is).
 */
public class BuilderCollabHelperGateTest
{
    @After
    public void clear()
    {
        BuilderCollab.overrideMinOrderScoreForTests(null);
    }

    @Test
    public void aSmallRingAndAFlatWallGetNoHelper()
    {
        // the 7 by 7 ring of 11: 264 blocks, 5 layers of 24 above the reach from the ground
        assertFalse(BuilderCollab.worthAHelper(264, 120));
        // the 30 by 6 wall of 3: 540 blocks, nothing out of reach
        assertFalse(BuilderCollab.worthAHelper(540, 0));
    }

    @Test
    public void aTallRingGetsOne()
    {
        // the 11 by 11 ring of 12, the 9 by 9 ring of 16, the slim ring of 16
        assertTrue(BuilderCollab.worthAHelper(480, 240));
        assertTrue(BuilderCollab.worthAHelper(512, 320));
        assertTrue(BuilderCollab.worthAHelper(384, 240));
    }

    @Test
    public void aBigFlatStructureGetsOneToo()
    {
        assertTrue(BuilderCollab.worthAHelper(1200, 0));
    }

    @Test
    public void aTestCanSwitchTheGateOff()
    {
        BuilderCollab.overrideMinOrderScoreForTests(0);
        assertTrue(BuilderCollab.worthAHelper(5, 0));
    }

    @Test
    public void theScoreCountsTheBlocksToPlaceAndTheHighOnesTwiceMore()
    {
        final WorkOrderCollab c = new WorkOrderCollab();
        c.setStageTotal(BuildingProgressStage.CLEAR, 5000);
        c.setStageTotal(BuildingProgressStage.BUILD_SOLID, 400);
        c.setStageTotal(BuildingProgressStage.WEAK_SOLID, 20);
        c.setStageTotal(BuildingProgressStage.DECORATE, 30);
        c.setHighBlocks(100);
        assertEquals("what is cleared away is no work for the helper's score", 450, c.getPlacementBlocks());
        assertEquals(450 + 200, c.getHelperScore());
    }

    @Test
    public void theHighBlocksAreSaved()
    {
        final WorkOrderCollab c = new WorkOrderCollab();
        c.setStageTotal(BuildingProgressStage.BUILD_SOLID, 480);
        c.setHighBlocks(240);
        final CompoundTag tag = new CompoundTag();
        c.write(tag);
        final WorkOrderCollab back = new WorkOrderCollab();
        back.read(tag);
        assertEquals(240, back.getHighBlocks());
        assertEquals(c.getHelperScore(), back.getHelperScore());
    }
}
