package com.minecolonies.api.colony.requestsystem.request;

import net.minecraft.network.RegistryFriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * RS2: why an open request is not moving. Anno pauses a building in a named state ("Awaiting Materials", "No Warehouse
 * in Range", "Full Output Storage"); every open request has one of these instead of a silent stall. The reason is
 * synced to the client with the request, so a UI can show it.
 */
public enum WaitReason
{
    /**
     * Not waiting: the request is being worked on, or finished.
     */
    NONE(Severity.NONE),
    /**
     * No warehouse holds the item and nothing can make it yet. The request is retried when stock arrives.
     */
    NO_SOURCE(Severity.INFO),
    /**
     * The item is in stock, but every piece of it is reserved for other requests.
     */
    RESERVED_ELSEWHERE(Severity.INFO),
    /**
     * A crafter will make it; waiting for the craft (or for the crafter to be free).
     */
    AWAITING_CRAFT(Severity.INFO),
    /**
     * A recipe for it exists, but no worker is assigned to the building that has it.
     */
    NO_CRAFTER(Severity.WARNING),
    /**
     * The items are on their way: promised, picked up or being carried.
     */
    AWAITING_DELIVERY(Severity.INFO),
    /**
     * Nobody can carry it: the warehouse has no active courier.
     */
    NO_COURIER(Severity.WARNING),
    /**
     * Couriers exist, but they are all busy; the delivery waits in the queue.
     */
    COURIER_BUSY(Severity.INFO),
    /**
     * The destination has no room.
     */
    TARGET_FULL(Severity.WARNING),
    /**
     * The delivery target is in an unloaded chunk.
     */
    UNLOADED(Severity.INFO),
    /**
     * Nothing in the colony can ever make this item: the player has to bring it.
     */
    PLAYER_REQUIRED(Severity.WARNING),
    /**
     * The courier took the task but did not make progress; the task was given up.
     */
    COURIER_STUCK(Severity.WARNING),
    /**
     * A labourer (a citizen without a job, or a builder for his own build) has taken the request: no building can make the item, he gathers or crafts it by hand.
     */
    AWAITING_LABOUR(Severity.INFO),
    /**
     * Labourers could serve the request but cannot: no tree or block in reach, no table, no tool material, a covered intermediate nobody has, or every labourer is busy.
     * The hint of the request says which.
     */
    LABOUR_BLOCKED(Severity.WARNING),
    /**
     * Round 13: a pickup for a hut that is full (its worker cannot dump) waits for a courier. The pickup is urgent.
     * Appended last: the reason travels as its ordinal, and older readers show an unknown ordinal as {@link #NONE}.
     */
    HUT_FULL_PICKUP_QUEUED(Severity.INFO);

    /**
     * How loud a reason is.
     */
    public enum Severity
    {
        NONE,
        INFO,
        WARNING
    }

    private final Severity severity;

    WaitReason(final Severity severity)
    {
        this.severity = severity;
    }

    @NotNull
    public Severity severity()
    {
        return severity;
    }

    /**
     * @return the translation key of the short description.
     */
    @NotNull
    public String translationKey()
    {
        return "com.minecolonies.coremod.request.wait." + name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * @return true when the request is waiting for something.
     */
    public boolean isWaiting()
    {
        return this != NONE;
    }

    public void serialize(@NotNull final RegistryFriendlyByteBuf buffer)
    {
        buffer.writeByte(ordinal());
    }

    @NotNull
    public static WaitReason deserialize(@NotNull final RegistryFriendlyByteBuf buffer)
    {
        return fromOrdinal(buffer.readByte());
    }

    @NotNull
    public static WaitReason fromOrdinal(final int ordinal)
    {
        final WaitReason[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NONE;
    }
}
