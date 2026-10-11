package com.minecolonies.core.gametest;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.ICitizenLeisureProvider;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.core.entity.ai.minimal.EntityAICitizenWander;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.minecolonies.core.gametest.MinecoloniesGameTests.foundGameTestColony;

/**
 * LOGISTICS-AND-WORKERS B3: the citizen leisure hook. MineColonies picks the leisure site an idle citizen walks to; a mod's {@link ICitizenLeisureProvider}
 * may keep it, change it or veto it. One glob: {@code minecolonies:leisure_*}.
 */
public final class LeisureGameTests
{
    private LeisureGameTests()
    {
    }

    /**
     * The default provider leaves the pick alone, the API holds it until a mod sets another, and null puts it back.
     */
    public static void default_provider_keeps_the_site(final GameTestHelper helper)
    {
        final IMinecoloniesAPI api = IMinecoloniesAPI.getInstance();
        final BlockPos site = new BlockPos(1, 2, 3);
        helper.assertTrue(api.getCitizenLeisureProvider() == ICitizenLeisureProvider.DEFAULT, "the default provider is in use at the start");
        helper.assertTrue(ICitizenLeisureProvider.resolve(api.getCitizenLeisureProvider(), null, site).equals(Optional.of(site)), "the default keeps the site");
        helper.assertTrue(ICitizenLeisureProvider.resolve(null, null, site).equals(Optional.of(site)), "no provider keeps the site");
        final ICitizenLeisureProvider mine = (citizen, proposed) -> Optional.empty();
        api.setCitizenLeisureProvider(mine);
        try
        {
            helper.assertTrue(api.getCitizenLeisureProvider() == mine, "the provider that was set is the one in use");
        }
        finally
        {
            api.setCitizenLeisureProvider(null);
        }
        helper.assertTrue(api.getCitizenLeisureProvider() == ICitizenLeisureProvider.DEFAULT, "null puts the default back");
        helper.succeed();
    }

    /**
     * A provider may replace the site or say no visit; one that throws or answers null changes nothing.
     */
    public static void provider_replaces_vetoes_and_cannot_break_it(final GameTestHelper helper)
    {
        final BlockPos site = new BlockPos(1, 2, 3);
        final BlockPos other = new BlockPos(40, 2, 50);
        helper.assertTrue(ICitizenLeisureProvider.resolve((citizen, proposed) -> Optional.of(other), null, site).equals(Optional.of(other)), "a provider can send the citizen elsewhere");
        helper.assertTrue(ICitizenLeisureProvider.resolve((citizen, proposed) -> Optional.empty(), null, site).isEmpty(), "a provider can say no visit now");
        helper.assertTrue(ICitizenLeisureProvider.resolve((citizen, proposed) -> Optional.of(proposed), null, site).equals(Optional.of(site)), "or keep the pick");
        helper.assertTrue(ICitizenLeisureProvider.resolve((citizen, proposed) ->
        {
            throw new IllegalStateException("broken");
        }, null, site).equals(Optional.of(site)), "a provider that throws is ignored");
        helper.assertTrue(ICitizenLeisureProvider.resolve((citizen, proposed) -> null, null, site).equals(Optional.of(site)), "a null answer keeps the pick");
        helper.succeed();
    }

    /**
     * A real citizen's wander AI asks the provider when it picks a leisure site: with no leisure site and no home MineColonies proposes the colony's
     * centre; the provider sees that and the citizen, and what it answers is where the citizen goes (or nowhere).
     */
    public static void wander_asks_the_provider(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = foundGameTestColony(helper, "Leisure hook test");
        final BlockPos min = helper.absolutePos(new BlockPos(-8, 0, -8));
        final BlockPos max = helper.absolutePos(new BlockPos(40, 0, 32));
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++)
        {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++)
            {
                level.setChunkForced(cx, cz, true);
            }
        }
        final IMinecoloniesAPI api = IMinecoloniesAPI.getInstance();
        helper.runAfterDelay(20, () -> {
            try
            {
                final ICitizenData data = colony.getCitizenManager().spawnOrCreateCivilian(null, level, List.of(helper.absolutePos(new BlockPos(8, 1, 8))), true);
                helper.assertTrue(data != null && data.getEntity().isPresent(), "citizen did not spawn");
                final EntityCitizen citizen = (EntityCitizen) data.getEntity().get();
                final EntityAICitizenWander wander = new EntityAICitizenWander(citizen, 1.0);
                final BlockPos elsewhere = helper.absolutePos(new BlockPos(20, 1, 20));
                final List<String> asked = new ArrayList<>();
                final BlockPos[] proposedSeen = new BlockPos[1];

                helper.assertTrue(wander.chooseLeisureSite().equals(Optional.of(colony.getCenter())), "without a provider: the colony's centre, MineColonies' own fallback");

                api.setCitizenLeisureProvider((who, proposed) -> {
                    asked.add("citizen " + who.getId());
                    proposedSeen[0] = proposed;
                    return Optional.of(elsewhere);
                });
                helper.assertTrue(wander.chooseLeisureSite().equals(Optional.of(elsewhere)), "the provider's site is where the citizen goes");
                helper.assertTrue(asked.equals(List.of("citizen " + data.getId())) && colony.getCenter().equals(proposedSeen[0]),
                  "the provider was asked once, about this citizen, with MineColonies' pick: " + asked + " " + proposedSeen[0]);

                api.setCitizenLeisureProvider((who, proposed) -> Optional.empty());
                helper.assertTrue(wander.chooseLeisureSite().isEmpty(), "a veto means no visit");
            }
            finally
            {
                api.setCitizenLeisureProvider(null);
                colony.getCitizenManager().getCitizens().forEach(citizen -> citizen.getEntity().ifPresent(net.minecraft.world.entity.Entity::discard));
                com.minecolonies.api.colony.IColonyManager.getInstance().deleteColonyByDimension(colony.getID(), false, colony.getDimension());
                for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++)
                {
                    for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++)
                    {
                        level.setChunkForced(cx, cz, false);
                    }
                }
            }
            helper.succeed();
        });
    }
}
