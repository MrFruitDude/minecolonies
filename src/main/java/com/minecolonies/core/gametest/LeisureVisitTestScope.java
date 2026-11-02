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
}
