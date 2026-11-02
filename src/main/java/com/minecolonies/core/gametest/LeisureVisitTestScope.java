package com.minecolonies.core.gametest;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.ICitizenLeisureProvider;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Fixture input: decline long leisure visits only for these exact citizens; retain native idle behavior. */
final class LeisureVisitTestScope implements AutoCloseable
{
    private final IMinecoloniesAPI api = IMinecoloniesAPI.getInstance();
    private final ICitizenLeisureProvider previous = api.getCitizenLeisureProvider();
    private final ICitizenLeisureProvider installed;
    private boolean active = true;

    LeisureVisitTestScope(final List<ICitizenData> citizens)
    {
        final Set<ICitizenData> targets = Collections.newSetFromMap(new IdentityHashMap<>());
        targets.addAll(citizens);
        installed = (citizen, proposed) -> active && targets.contains(citizen) ? Optional.empty()
          : ICitizenLeisureProvider.resolve(previous, citizen, proposed);
        api.setCitizenLeisureProvider(installed);
    }

    Runnable guard(final Runnable action)
    {
        return () -> {
            try { action.run(); }
            catch (final RuntimeException | Error failure)
            {
                try { close(); }
                catch (final RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        };
    }

    @Override
    public void close()
    {
        if (!active) { return; }
        active = false;
        // Never overwrite a provider installed by someone else.
        if (api.getCitizenLeisureProvider() == installed) { api.setCitizenLeisureProvider(previous); }
    }
    /** Exercise actual environment teardown without calling close first, as on an external test failure. */
    static void testCleanup(final net.minecraft.gametest.framework.GameTestHelper helper)
    {
        final var colony = MinecoloniesGameTests.foundGameTestColony(helper, "scope cleanup");
        helper.runAfterDelay(40, () -> {
            final var api = IMinecoloniesAPI.getInstance();
            final var previous = api.getCitizenLeisureProvider();
            final var state = new ColonyIsolationEnvironment.TreeChopState(
              com.minecolonies.api.compatibility.Compatibility.treeChop.isEnabled(),
              com.minecolonies.core.MineColonies.getConfig().getServer().lumberjacksUseTreeChop.get(), previous);
            final var at = helper.absolutePos(new net.minecraft.core.BlockPos(10, 1, 10));
            final ICitizenData citizen = colony.getCitizenManager().spawnOrCreateCivilian(null, helper.getLevel(), List.of(at), true);
            helper.assertTrue(citizen != null && citizen.getEntity().isPresent(), "cleanup fixture citizen missing");
            final var guarded = new LeisureVisitTestScope(List.of(citizen));
            final var sentinel = new IllegalStateException("intentional fixture callback failure");
            try { guarded.guard(() -> { throw sentinel; }).run(); }
            catch (final IllegalStateException failure) { helper.assertTrue(failure == sentinel, "cleanup replaced the original failure"); }
            helper.assertTrue(api.getCitizenLeisureProvider() == previous, "callback failure leaked leisure provider");

            final var replaced = new LeisureVisitTestScope(List.of(citizen));
            final ICitizenLeisureProvider replacement = (who, proposed) -> Optional.of(proposed);
            api.setCitizenLeisureProvider(replacement);
            try
            {
                replaced.close();
                helper.assertTrue(api.getCitizenLeisureProvider() == replacement, "scope overwrote a replacement provider");
            }
            finally { api.setCitizenLeisureProvider(previous); }

            final var external = new LeisureVisitTestScope(List.of(citizen));
            try
            {
                helper.assertTrue(api.getCitizenLeisureProvider() != previous
                  && ICitizenLeisureProvider.resolve(api.getCitizenLeisureProvider(), citizen, at).isEmpty(), "no active provider to test external cleanup");
                new ColonyIsolationEnvironment("scope cleanup component").teardown(helper.getLevel(), state);
                helper.assertTrue(api.getCitizenLeisureProvider() == previous, "environment teardown leaked leisure provider");
            }
            finally { external.close(); }
            com.minecolonies.api.util.Log.getLogger().info("[fixture leisure cleanup] callback, replacement and environment teardown passed");
            helper.succeed();
        });
    }

}
