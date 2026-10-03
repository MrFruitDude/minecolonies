package com.minecolonies.api.colony.managers.interfaces;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Colony package manager, responsible to update views etc.
 */
public interface IColonyPackageManager
{
    /**
     * Get the last contact in hours from the colony.
     *
     * @return the integer.
     */
    int getLastContactInHours();

    /**
     * Set the last contact in hours.
     *
     * @param lastContactInHours the number to set.
     */
    void setLastContactInHours(int lastContactInHours);

    /**
     * Get all subscribers.
     *
     * @return a copy of the hashset.
     */
    Set<ServerPlayer> getCloseSubscribers();

    /**
     * Update Subscribers with Colony, Citizen, and AbstractBuilding Views.
     */
    void updateSubscribers();

    /**
     * Updates the time the players were away from the colony.
     */
    void updateAwayTime();

    /**
     * Update the colony view.
     */
    void sendColonyViewPackets();

    /**
     * Sends packages to update the permissions.
     */
    void sendPermissionsPackets();

    /**
     * Sends packages to update the workOrders.
     */
    void sendWorkOrderPackets();

    /**
     * Mark the package manager dirty.
     */
    void setDirty();

    /**
     * A citizen changed. Its own view goes out with the citizen views; this only marks the colony view fields derived
     * from citizens (overall happiness) as stale, so an implementation may refresh them on a slower cadence than a real
     * colony change. The default treats it as a colony change.
     */
    default void markCitizenDerivedDirty()
    {
        setDirty();
    }

    /**
     * Add a new subscriber to the colony.
     *
     * @param subscriber the subscriber to add.
     */
    void addCloseSubscriber(@NotNull final ServerPlayer subscriber);

    /**
     * Adds a new global subscriber to the colony.
     *
     * @param subscriber the subscriber to add.
     */
    void addImportantColonyPlayer(@NotNull ServerPlayer subscriber);

    /**
     * Removes an global subscriber from the colony.
     *
     * @param subscriber the subscriber to remove.
     */
    void removeImportantColonyPlayer(@NotNull ServerPlayer subscriber);

    /**
     * Remove a subscriber from the colony.
     *
     * @param player the subscriber to remove.
     */
    void removeCloseSubscriber(@NotNull final ServerPlayer player);

    /**
     * Returns the global subscribers.
     *
     * @return global subscribers
     */
    Set<ServerPlayer> getImportantColonyPlayers();
}
