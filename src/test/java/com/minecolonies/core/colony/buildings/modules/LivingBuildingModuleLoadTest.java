package com.minecolonies.core.colony.buildings.modules;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.managers.interfaces.ICitizenManager;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Review M2 (Wave E): a residence loaded with fewer beds than when it was saved keeps every saved resident instead of leaving the extra ones homeless.
 */
public class LivingBuildingModuleLoadTest
{
    /** An interface stub: identity equality, default answers, setHomeBuilding recorded. */
    @SuppressWarnings("unchecked")
    private static <T> T stub(final Class<T> type, final Object answer, final List<Object> homes)
    {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            switch (method.getName())
            {
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return type.getSimpleName() + "@" + System.identityHashCode(proxy);
                case "setHomeBuilding":
                    homes.add(args[0]);
                    return null;
                default:
                    break;
            }
            if (answer != null && method.getReturnType().isInstance(answer))
            {
                return answer;
            }
            final Class<?> r = method.getReturnType();
            if (r == boolean.class)
            {
                return false;
            }
            if (r == int.class || r == short.class || r == byte.class || r == char.class)
            {
                return r == char.class ? (Object) (char) 0 : r == int.class ? (Object) 0 : r == short.class ? (Object) (short) 0 : (Object) (byte) 0;
            }
            if (r == long.class)
            {
                return 0L;
            }
            if (r == double.class)
            {
                return 0.0;
            }
            if (r == float.class)
            {
                return 0.0f;
            }
            return null;
        });
    }

    @Test
    public void savedResidentsAboveTheBedsKeepTheirHome()
    {
        final List<Object> homes = new ArrayList<>();
        final ICitizenManager manager = stub(ICitizenManager.class, null, homes);
        final IColony colony = stub(IColony.class, manager, homes);
        final IBuilding building = stub(IBuilding.class, colony, homes);
        final LivingBuildingModule module = new LivingBuildingModule();
        module.setBuilding(building);
        final ICitizenData a = stub(ICitizenData.class, null, homes);
        final ICitizenData b = stub(ICitizenData.class, null, homes);

        // two residents saved (a level 1 residence with residenceextrabeds 1), loaded where it has one bed: both stay
        assertEquals(2, module.restoreResidents(List.of(a, b)));
        assertEquals(List.of(a, b), module.getAssignedCitizen());
        assertEquals(2, homes.size());
        assertSame(building, homes.get(0));
        assertSame(building, homes.get(1));
        // a resident listed twice, or a missing one, is not added again
        final List<ICitizenData> again = new ArrayList<>(List.of(a));
        again.add(null);
        assertEquals(2, module.restoreResidents(again));
        assertTrue(module.hasAssignedCitizen(a) && module.hasAssignedCitizen(b));
    }
}
