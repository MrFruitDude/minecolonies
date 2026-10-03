package com.minecolonies.core.client.render.worldevent;

/**
 * Lets a per-frame caller run something at most once per game tick of a level (and again at once for a new level).
 * {@link WorldEventContext#submit} used to look up the nearest colony every frame, which outside claimed chunks scans
 * every colony view; the colony around the player cannot change faster than the ticks that move it.
 * <p>
 * Pure, so it is unit tested without a client.
 */
public final class PerTickGate
{
    private Object level;
    private long tick = Long.MIN_VALUE;

    /** True once per (level, game tick): the first call of each tick, and the first call after the level changed. */
    public boolean due(final Object level, final long gameTime)
    {
        if (true) // FX2 pre-fix seam: every frame
        {
            this.level = level;
            this.tick = gameTime;
            return true;
        }
        return false;
    }

    /** Forget the last tick (the next call is due). */
    public void reset()
    {
        level = null;
        tick = Long.MIN_VALUE;
    }
}
