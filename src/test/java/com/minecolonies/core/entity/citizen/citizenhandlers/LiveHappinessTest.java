package com.minecolonies.core.entity.citizen.citizenhandlers;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.MinecoloniesAPIProxy;
import com.minecolonies.api.entity.citizen.happiness.HappinessRegistry;
import com.minecolonies.api.entity.citizen.happiness.IHappinessModifier;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Round 9: the live happiness for Colony Command's cards is computed from the factors as they are now, and leaves the cached value alone.
 */
public class LiveHappinessTest
{
    /** A modifier whose factor a test changes without telling the handler (as a dynamic supplier changes). */
    private static final class Factor implements IHappinessModifier
    {
        private final String id;
        private final double weight;
        double factor;

        Factor(final String id, final double weight, final double factor)
        {
            this.id = id;
            this.weight = weight;
            this.factor = factor;
        }

        @Override
        public String getId()
        {
            return id;
        }

        @Override
        public double getFactor(final ICitizenData data)
        {
            return factor;
        }

        @Override
        public double getWeight()
        {
            return weight;
        }

        @Override
        public void read(final HolderLookup.Provider provider, final CompoundTag compoundNBT, final boolean persist)
        {
        }

        @Override
        public void write(final HolderLookup.Provider provider, final CompoundTag compoundNBT, final boolean persist)
        {
        }
    }

    /** A colony whose research gives the happiness effect {@code strength}. */
    private static IColony colony(final double strength)
    {
        final Object effects = Proxy.newProxyInstance(LiveHappinessTest.class.getClassLoader(),
          new Class<?>[] {com.minecolonies.api.research.IResearchEffectManager.class},
          (proxy, method, args) -> method.getName().equals("getEffectStrength") ? strength : null);
        final Object research = Proxy.newProxyInstance(LiveHappinessTest.class.getClassLoader(), new Class<?>[] {com.minecolonies.api.research.IResearchManager.class},
          (proxy, method, args) -> method.getName().equals("getResearchEffects") ? effects : null);
        return (IColony) Proxy.newProxyInstance(LiveHappinessTest.class.getClassLoader(), new Class<?>[] {IColony.class},
          (proxy, method, args) -> method.getName().equals("getResearchManager") ? research : null);
    }

    @Test
    public void liveValueFollowsTheFactorsAndLeavesTheCacheAlone()
    {
        final CitizenHappinessHandler handler = new CitizenHappinessHandler();
        final Factor food = new Factor("food", 3.0, 0.5);
        final Factor security = new Factor("security", 1.0, 1.0);
        handler.addModifier(food);
        handler.addModifier(security);
        final IColony colony = colony(0.0);

        // only food counts (security is neutral): 10 * 0.5
        assertEquals(5.0, handler.getHappiness(colony, null), 1e-9);
        assertEquals(5.0, handler.getLiveHappiness(colony, null), 1e-9);

        // the factors change, nothing tells the handler: the cached value stays, the live one follows
        food.factor = 0.2;
        security.factor = 0.6;
        final double live = 10.0 * (0.2 * 3.0 + 0.6 * 1.0) / 4.0;
        assertEquals(live, handler.getLiveHappiness(colony, null), 1e-9);
        assertEquals(5.0, handler.getHappiness(colony, null), 1e-9);
        // asking for the live value did not renew the cache, and asking again gives the same
        assertEquals(live, handler.getLiveHappiness(colony, null), 1e-9);
        assertEquals(5.0, handler.getHappiness(colony, null), 1e-9);

        // a modifier change renews the cache as before, to the same value as the live one
        handler.addModifier(new Factor("social", 2.0, 1.0));
        assertEquals(live, handler.getHappiness(colony, null), 1e-9);
    }

    @Test
    public void liveValueUsesTheResearchAndTheSameBounds()
    {
        final CitizenHappinessHandler handler = new CitizenHappinessHandler();
        final Factor food = new Factor("food", 3.0, 0.5);
        handler.addModifier(food);
        assertEquals(10.0 * 0.5 * 1.2, handler.getLiveHappiness(colony(0.2), null), 1e-9);
        food.factor = 1.0;
        // every factor neutral: content, 10
        assertEquals(10.0, handler.getLiveHappiness(colony(0.2), null), 0.0);
        food.factor = 3.0;
        assertEquals(10.0, handler.getLiveHappiness(colony(0.0), null), 0.0);
    }

    @Test
    public void liveValueOnTheProductionHandlerStoresNothing()
    {
        // review r9 c1 M1: the production handler of a citizen (CitizenHappinessHandler(data), its real dynamic suppliers and time-based modifiers); the
        // happiness functions answer what the test says. CitizenData itself cannot be constructed in a plain JUnit run (its inventory needs the vanilla
        // bootstrap, which needs the FML loader), so the citizen is an interface stand-in.
        final double[] value = {0.5};
        final HappinessRegistry.HappinessFunctionEntry entry = new HappinessRegistry.HappinessFunctionEntry(d -> value[0]);
        final Object registry = Proxy.newProxyInstance(LiveHappinessTest.class.getClassLoader(), new Class<?>[] {Registry.class},
          (proxy, method, args) -> method.getName().equals("getValue") && args != null && args.length == 1 && args[0] instanceof Identifier ? entry : null);
        final IMinecoloniesAPI api = (IMinecoloniesAPI) Proxy.newProxyInstance(LiveHappinessTest.class.getClassLoader(), new Class<?>[] {IMinecoloniesAPI.class},
          (proxy, method, args) -> method.getName().equals("getHappinessFunctionRegistry") ? registry : null);
        MinecoloniesAPIProxy.getInstance().setApiInstance(api);
        try
        {
            final IColony colony = colony(0.0);
            final ICitizenData citizen = (ICitizenData) Proxy.newProxyInstance(LiveHappinessTest.class.getClassLoader(), new Class<?>[] {ICitizenData.class},
              (proxy, method, args) -> method.getName().equals("getColony") ? colony : method.getName().equals("getId") ? 1 : null);
            final CitizenHappinessHandler handler = new CitizenHappinessHandler(citizen);

            // every function answers 0.5 (the time-based modifiers weigh it by their days, so the total is not asserted, only compared)
            final double cached = handler.getHappiness(colony, citizen);
            final Map<String, Double> stored = lastValues(handler);

            value[0] = 0.25;
            final double live = handler.getLiveHappiness(colony, citizen);
            assertTrue("the live value follows the factors: " + live + " vs " + cached, live < cached);
            // nothing changed: the suppliers' last values, and the cached happiness
            assertEquals(stored, lastValues(handler));
            assertEquals(cached, handler.getHappiness(colony, citizen), 0.0);
            assertEquals(live, handler.getLiveHappiness(colony, citizen), 0.0);
            assertEquals(stored, lastValues(handler));

            // a null colony gives the cached value and does not throw
            assertEquals(cached, handler.getLiveHappiness(null, citizen), 0.0);

            // once the cache is renewed (a modifier change), the cached path gives the same value as the live one
            handler.addModifier(new Factor("neutral", 1.0, 1.0));
            assertEquals(live, handler.getHappiness(colony, citizen), 1e-9);
        }
        finally
        {
            MinecoloniesAPIProxy.getInstance().setApiInstance(null);
        }
    }

    /** The supplier value each modifier stored last (what client views and the save show). */
    private static Map<String, Double> lastValues(final CitizenHappinessHandler handler)
    {
        final Map<String, Double> out = new HashMap<>();
        for (final String id : handler.getModifiers())
        {
            out.put(id, handler.getModifier(id).getFactor(null));
        }
        return out;
    }
}
