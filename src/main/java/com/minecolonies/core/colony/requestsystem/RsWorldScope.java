package com.minecolonies.core.colony.requestsystem;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The scope of the courier state kept in memory (refusal pauses, short-load credit): one loaded world (a server run) and one dimension. Two worlds opened
 * one after the other in the same game, or two dimensions with equal colony ids and positions, never share a pause or a credit (r2 Sol H3). The state is
 * dropped when a server starts and when it stops.
 */
public final class RsWorldScope
{
    /**
     * Counts server starts in this JVM: a key of an earlier world never matches one of the current world.
     */
    private static volatile int epoch = 0;

    private RsWorldScope()
    {
    }

    /**
     * A server starts: a new world, nothing of the last one holds.
     */
    public static void serverStarting()
    {
        epoch++;
        clear();
    }

    /**
     * The server stops: the state of its world goes.
     */
    public static void serverStopping()
    {
        clear();
    }

    private static void clear()
    {
        RefusedTargets.clear();
        RsCourierHooks.clearCredit();
    }

    /**
     * @param dimension a dimension, or null if unknown.
     * @return the scope key of the dimension in the current world.
     */
    @NotNull
    public static String of(@Nullable final ResourceKey<Level> dimension)
    {
        return epoch + "|" + (dimension == null ? "?" : dimension.identifier().toString());
    }

    /**
     * @param level a level.
     * @return the scope key of its dimension in the current world.
     */
    @NotNull
    public static String of(@NotNull final Level level)
    {
        return of(level.dimension());
    }

    /**
     * @param manager a request manager.
     * @return the scope key of its colony's dimension in the current world.
     */
    @NotNull
    public static String of(@NotNull final IRequestManager manager)
    {
        final IColony colony = manager.getColony();
        return of(colony == null ? null : colony.getDimension());
    }
}
