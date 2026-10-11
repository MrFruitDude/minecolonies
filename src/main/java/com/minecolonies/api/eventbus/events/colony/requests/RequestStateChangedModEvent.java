package com.minecolonies.api.eventbus.events.colony.requests;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.request.WaitReason;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.eventbus.events.colony.AbstractColonyModEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * RS2: raised on the mod event bus (server side) whenever a request changes state or its wait reason, so observers (a UI,
 * a planner) do not have to poll the request system.
 */
public final class RequestStateChangedModEvent extends AbstractColonyModEvent
{
    private final IToken<?>    request;
    @Nullable
    private final RequestState oldState;
    private final RequestState newState;
    private final WaitReason   oldReason;
    private final WaitReason   newReason;

    /**
     * @param colony    the colony.
     * @param request   the request token.
     * @param oldState  the state before, null for a new request.
     * @param newState  the state now.
     * @param oldReason the wait reason before.
     * @param newReason the wait reason now.
     */
    public RequestStateChangedModEvent(
      @NotNull final IColony colony,
      @NotNull final IToken<?> request,
      @Nullable final RequestState oldState,
      @NotNull final RequestState newState,
      @NotNull final WaitReason oldReason,
      @NotNull final WaitReason newReason)
    {
        super(colony);
        this.request = request;
        this.oldState = oldState;
        this.newState = newState;
        this.oldReason = oldReason;
        this.newReason = newReason;
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
