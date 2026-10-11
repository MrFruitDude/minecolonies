package com.minecolonies.core.gametest;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * GameTest environment for tests that found a colony. Colonies claim chunks far beyond a test's grid cell and refuse a
 * new town hall within minColonyDistance of another one, so two colony fixtures cannot share a batch or inherit a
 * colony left behind by an earlier batch. Each colony test gets its own environment (and therefore its own batch), and
 * this environment deletes every colony in the level before and after that batch, and starts the batch at sunrise. The test name is part of the value
 * because the environment registry refuses two equal values, and one environment per test is what splits the batches.
 *
 * @param test the test this environment isolates.
 */
public record ColonyIsolationEnvironment(String test) implements TestEnvironmentDefinition<Unit>
{
    public static final MapCodec<ColonyIsolationEnvironment> CODEC = Codec.STRING.fieldOf("test")
      .xmap(ColonyIsolationEnvironment::new, ColonyIsolationEnvironment::test);

    @Override
    public Unit setup(final ServerLevel level)
    {
        // Tests of the request-system redesign switch the flags in code; none may leak into the next test.
        com.minecolonies.core.colony.requestsystem.RsFlags.overrideReservations(null);
        com.minecolonies.core.colony.requestsystem.RsFlags.overrideSmartRetry(null);
        com.minecolonies.core.colony.requestsystem.RsStats.reset();
        com.minecolonies.core.colony.ColonyLimits.overrideMaxPerPlayer(null);
        com.minecolonies.core.colony.FactionConfig.overrideDefaultPlayerRank(null);
        com.minecolonies.core.colony.FactionConfig.overrideCanBeRaided(null);
        deleteAllColonies(level);
        // A single run starts on a fresh world at sunrise; in a full batch the clock has run on through every earlier
        // batch and can land at night, when citizens without beds stop working. Start every colony test at sunrise too,
        // so it gets the same daylight budget as a single run.
        level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 0L);
        return Unit.INSTANCE;
    }

    @Override
    public void teardown(final ServerLevel level, final Unit saveData)
    {
        com.minecolonies.core.colony.requestsystem.RsFlags.overrideReservations(null);
        com.minecolonies.core.colony.requestsystem.RsFlags.overrideSmartRetry(null);
        com.minecolonies.core.colony.ColonyLimits.overrideMaxPerPlayer(null);
        com.minecolonies.core.colony.FactionConfig.overrideDefaultPlayerRank(null);
        com.minecolonies.core.colony.FactionConfig.overrideCanBeRaided(null);
        deleteAllColonies(level);
    }

    @Override
    public MapCodec<ColonyIsolationEnvironment> codec()
    {
        return CODEC;
    }

    private static void deleteAllColonies(final ServerLevel level)
    {
        final IColonyManager manager = IColonyManager.getInstance();
        final List<Entity> citizens = new ArrayList<>();
        for (final IColony colony : new ArrayList<>(manager.getColonies(level)))
        {
            for (final ICitizenData citizen : colony.getCitizenManager().getCitizens())
            {
                citizen.getEntity().ifPresent(citizens::add);
            }
            manager.deleteColonyByWorld(colony.getID(), false, level);
        }
        // Deleting a colony kills its citizens, and a dying citizen stays in the level for its death animation. The
        // next colony reuses the freed id, so a dying citizen with the same colony and citizen id would re-attach to
        // the new colony's first citizen. Remove them now so the next test starts with no stale citizen entities.
        citizens.forEach(Entity::discard);
    }
}
