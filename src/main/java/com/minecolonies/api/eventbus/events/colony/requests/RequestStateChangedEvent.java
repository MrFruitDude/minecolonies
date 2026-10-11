package com.minecolonies.api.eventbus.events.colony.requests;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.request.WaitReason;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * RS2: the same notification as {@link RequestStateChangedModEvent}, posted on the NeoForge event bus (server side) for
 * mods that listen there. Not cancellable.
 */
public final class RequestStateChangedEvent extends Event
{
    private final IColony      colony;
    private final IToken<?>    request;
    @Nullable
    private final RequestState oldState;
    private final RequestState newState;
    private final WaitReason   oldReason;
    private final WaitReason   newReason;

    public RequestStateChangedEvent(
      @NotNull final IColony colony,
      @NotNull final IToken<?> request,
      @Nullable final RequestState oldState,
      @NotNull final RequestState newState,
      @NotNull final WaitReason oldReason,
      @NotNull final WaitReason newReason)
    {
        this.colony = colony;
        this.request = request;
        this.oldState = oldState;
        this.newState = newState;
        this.oldReason = oldReason;
        this.newReason = newReason;
    }

    @NotNull
    public IColony getColony()
    {
        return colony;
    }

    @NotNull
    public IToken<?> getRequest()
    {
        return request;
    }

    @Nullable
    public RequestState getOldState()
    {
        return oldState;
    }

    @NotNull
    public RequestState getNewState()
    {
        return newState;
    }

    @NotNull
    public WaitReason getOldReason()
    {
        return oldReason;
    }

    @NotNull
    public WaitReason getNewReason()
    {
        return newReason;
    }
}
