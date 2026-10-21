package com.minecolonies.core.colony.economy;

import com.minecolonies.core.colony.buildings.modules.LivingBuildingModule;
import com.minecolonies.core.colony.managers.CitizenManager;
import com.minecolonies.core.entity.citizen.citizenhandlers.CitizenHappinessHandler;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Wave E: residence beds (level plus the extra beds) and the guard security factor's grace for young colonies. C2: the free citizen floor apart from the
 * starting count, and happiness that never goes negative or undefined.
 */
public class EarlyEconomyTest
{
    @Test
    public void residenceHoldsItsLevelPlusTheExtraBeds()
    {
        assertEquals(0, LivingBuildingModule.beds(0, 1));
        assertEquals(2, LivingBuildingModule.beds(1, 1));
        assertEquals(6, LivingBuildingModule.beds(5, 1));
        // extra 0 is the plain MineColonies rule; a negative value cannot remove beds
        assertEquals(1, LivingBuildingModule.beds(1, 0));
        assertEquals(3, LivingBuildingModule.beds(3, -2));
    }

    @Test
    public void securityIsNeutralWhileTheColonyIsYoung()
    {
        // 8 citizens, no guard: the plain factor is 1 / 6, the grace of 15 lifts it to exactly 1.0 (a skipped modifier)
        assertEquals(1.0 / 6.0, CitizenHappinessHandler.guardFactor(0, 8, 0), 1e-9);
        assertEquals(1.0, CitizenHappinessHandler.guardFactor(0, 8, 15), 0.0);
        // from the grace size on, the lack of guards counts again
        assertEquals(1.0 / ((15 + 1.0) * 2 / 3), CitizenHappinessHandler.guardFactor(0, 15, 15), 1e-9);
        // guards above neutral keep their bonus inside the grace too
        assertTrue(CitizenHappinessHandler.guardFactor(3, 4, 15) > 1.0);
        assertEquals(CitizenHappinessHandler.guardFactor(3, 4, 0), CitizenHappinessHandler.guardFactor(3, 4, 15), 0.0);
        assertEquals(2.0, CitizenHappinessHandler.guardFactor(20, 1, 0), 0.0);
    }

    @Test
    public void newColoniesStartWithTheStartingCountThenRefillOnlyToTheFloor()
    {
        // shipped: start with 8, refill deaths for free up to 4
        assertEquals(8, CitizenManager.freeCitizenTarget(false, 8, 4));
        assertEquals(4, CitizenManager.freeCitizenTarget(true, 8, 4));
        // the two keys are independent
        assertEquals(4, CitizenManager.freeCitizenTarget(false, 4, 4));
        assertEquals(6, CitizenManager.freeCitizenTarget(true, 8, 6));
    }

    @Test
    public void aSaveWithoutTheStartFlagIsAnExistingColony()
    {
        // saves from before the flag: the colony has had its start, so only the floor refills it
        assertTrue(CitizenManager.startingDoneOnLoad(false, false));
        assertTrue(CitizenManager.startingDoneOnLoad(true, true));
        // a colony saved during its start keeps spawning to the starting count
        assertEquals(false, CitizenManager.startingDoneOnLoad(true, false));
    }

    @Test
    public void socialFactorStaysWithinZeroAndOne()
    {
        // c1 card fixture: 3 citizens, 2 jobless and 1 homeless: 0, as before
        assertEquals(0.0, CitizenHappinessHandler.socialFactor(3, 3), 0.0);
        assertEquals(0.5, CitizenHappinessHandler.socialFactor(2, 1), 1e-9);
        // one citizen counted jobless, homeless and hungry was -2 (the negative happiness of c1); now 0
        assertEquals(0.0, CitizenHappinessHandler.socialFactor(1, 3), 0.0);
        assertEquals(0.0, CitizenHappinessHandler.socialFactor(2, 3), 0.0);
        // no citizens: neutral, not 0 / 0
        assertEquals(1.0, CitizenHappinessHandler.socialFactor(0, 0), 0.0);
    }

    @Test
    public void happinessIsNeverNegativeOrUndefined()
    {
        // the c1 card's listed factors: homelessness 2/3 (weight 3), social 0 (weight 2): 4.0
        assertEquals(4.0, CitizenHappinessHandler.happiness(3 * (2.0 / 3) + 2 * 0.0, 5, 0), 1e-9);
        // the c1 value -10/7 needs a negative social factor (-0.5 with homelessness and unemployment 0); clamped it is 0
        assertEquals(-10.0 / 7, CitizenHappinessHandler.happiness(2 * -0.5, 7, 0), 1e-9);
        assertEquals(0.0, CitizenHappinessHandler.happiness(2 * CitizenHappinessHandler.socialFactor(2, 3), 7, 0), 0.0);
        // every factor neutral (all skipped, the security grace makes this common): content, not NaN
        assertEquals(10.0, CitizenHappinessHandler.happiness(0, 0, 0), 0.0);
        assertEquals(10.0, CitizenHappinessHandler.happiness(0, 0, 0.5), 0.0);
        assertEquals(5.0, CitizenHappinessHandler.happiness(2.0, 4, 0), 1e-9);
    }
}
