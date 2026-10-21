package com.minecolonies.core.entity.citizen.citizenhandlers;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.happiness.IHappinessModifier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;

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
}
