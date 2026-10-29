package com.minecolonies.api.colony.labour;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.util.Log;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * What happens to a colony's stone shafts that its managers should hear of (an addon tells them: an inbox line, a toast). Fired on the server thread, once per event; a
 * listener must be quick and must not change the colony's labour. A listener that throws is logged and skipped.
 */
public final class LabourShaftEvents
{
    /** What happened. */
    public enum Kind
    {
        /** Labourers opened a shaft by themselves, or a manager marked one ({@link Event#marked}). */
        OPENED,
        /** Everything of the shaft is dug, or was left where it would have opened into a cave, water or another claim. */
        WORKED_OUT,
        /** Labourers met an ore kind in the shaft for the first time ({@link Event#item}); mined if their pickaxe takes it, else the tunnel stops at it. */
        ORE_FOUND
    }

    /**
     * @param shaft    the shaft's id in its colony.
     * @param entrance the shaft's pillar at the ground.
     * @param at       where it happened (the entrance, or the ore).
     * @param item     the ore's item id, or empty.
     * @param mined    whether the ore was mined (else the tunnel stops there).
     * @param depth    how deep the shaft is dug now.
     * @param marked   whether a manager marked the spot.
     */
    public record Event(IColony colony, Kind kind, int shaft, BlockPos entrance, BlockPos at, String item, boolean mined, int depth, boolean marked)
    {
    }

    private static final List<Consumer<Event>> LISTENERS = new CopyOnWriteArrayList<>();

    private LabourShaftEvents()
    {
    }

    public static void register(final Consumer<Event> listener)
    {
        if (!LISTENERS.contains(listener))
        {
            LISTENERS.add(listener);
        }
    }

    /**
     * Only for tests.
     */
    public static void unregister(final Consumer<Event> listener)
    {
        LISTENERS.remove(listener);
    }

    public static void fire(final Event event)
    {
        for (final Consumer<Event> listener : LISTENERS)
        {
            try
            {
                listener.accept(event);
            }
            catch (final RuntimeException | LinkageError e)
            {
                Log.getLogger().error("labour shaft listener {} failed on {}", listener, event.kind(), e);
            }
        }
    }
}
