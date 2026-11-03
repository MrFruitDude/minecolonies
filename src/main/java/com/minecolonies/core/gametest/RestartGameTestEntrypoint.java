package com.minecolonies.core.gametest;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.startup.Entrypoint;

/** Same FML startup as its GameTestServer entrypoint, selecting the snapshot-restoring test main. */
public final class RestartGameTestEntrypoint extends Entrypoint
{
    private RestartGameTestEntrypoint() { }

    public static void main(final String[] args)
    {
        try (final var result = startup(args, true, Dist.DEDICATED_SERVER, false))
        {
            final var main = createMainMethodCallable(result, "com.minecolonies.core.gametest.RestartGameTestMain");
            main.invokeExact(result.loader().getProgramArgs().getArguments());
            final Thread server = findThread("Server thread");
            if (server == null) { throw new IllegalStateException("Restart GameTest server did not start"); }
            server.join();
        }
        catch (final Throwable error)
        {
            error.printStackTrace();
            System.exit(1);
        }
    }
}
