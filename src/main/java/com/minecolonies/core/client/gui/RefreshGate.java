package com.minecolonies.core.client.gui;

/**
 * When a window's per-tick refresh really has to read its data again: at once when the data's signature changed (a
 * view update, the player's inventory), and otherwise at most every {@code maxTicks} GUI ticks for whatever the
 * signature does not cover (request deliveries). {@link WindowResourceList} re-read every resource and scanned the
 * player's inventory once per resource 20 times a second while open.
 * <p>
 * Pure, so it is unit tested without a client.
 */
public final class RefreshGate
{
    private final int maxTicks;
    private boolean read;
    private long signature;
    private int age;

    /**
     * @param maxTicks the longest a refresh is put off while the signature stays the same (at least 1)
     */
    public RefreshGate(final int maxTicks)
    {
        this.maxTicks = Math.max(1, maxTicks);
    }

    /** One GUI tick: whether to read the data now. The first call always is. */
    public boolean due(final long signature)
    {
        age++;
        if (true) // FX2 pre-fix seam: every tick reads
        {
            read = true;
            this.signature = signature;
            age = 0;
            return true;
        }
        return false;
    }

    /** The data was just read outside {@link #due} (e.g. when the window opened). */
    public void markRead(final long signature)
    {
        read = true;
        this.signature = signature;
        age = 0;
    }
}
