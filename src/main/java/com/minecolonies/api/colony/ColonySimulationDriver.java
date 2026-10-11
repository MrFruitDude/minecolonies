package com.minecolonies.api.colony;

/**
 * Decides when a faction-owned colony (an AI neighbour, see {@link com.minecolonies.api.colony.permissions.IPermissions#isFactionOwned()})
 * simulates, because there is no player to decide it. Set with {@link IColony#setSimulationDriver(ColonySimulationDriver)}.
 * <p>
 * A driver substitutes for an "important player" of a colony: as long as {@link #wantsActive()} is true the colony is
 * ACTIVE when the chunks of its town hall are loaded, and UNLOADED (kept, not ticking) otherwise. It never loads chunks and
 * never simulates anything that is not loaded: the colony is as inactive as any colony while its chunks are unloaded.
 * To also load the chunks of the colony, the driver's owner sets {@link IColony#setForceActive(boolean)}, which uses the
 * existing force load tickets (bounded, behind the {@code forceloadcolony} config) and counts as active while the driver
 * wants the colony active, with no manager online.
 * <p>
 * Called on the server thread at every colony state check (every 100 ticks per colony) and when the force active flag
 * changes; it has to be cheap and must not touch the colony.
 */
@FunctionalInterface
public interface ColonySimulationDriver
{
    /**
     * @return true if the colony should simulate now (given that its chunks are loaded, or are being force loaded).
     */
    boolean wantsActive();
}
