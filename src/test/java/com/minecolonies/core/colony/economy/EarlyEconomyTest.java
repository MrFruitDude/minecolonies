package com.minecolonies.core.colony.economy;

import com.minecolonies.core.colony.buildings.modules.LivingBuildingModule;
import com.minecolonies.core.entity.citizen.citizenhandlers.CitizenHappinessHandler;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Wave E: residence beds (level plus the extra beds) and the guard security factor's grace for young colonies.
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
}
