package com.minecolonies.core.colony.requestsystem.resolvers;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeToken;
import com.minecolonies.api.colony.requestsystem.factory.IFactoryController;
import com.minecolonies.api.colony.requestsystem.location.ILocation;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.IRetryable;
import com.minecolonies.api.colony.requestsystem.resolver.retrying.IRetryingRequestResolver;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.util.constant.TypeConstants;
import com.minecolonies.core.colony.requestsystem.RsFlags;
import com.minecolonies.core.colony.requestsystem.management.IStandardRequestManager;
import com.minecolonies.core.colony.requestsystem.wait.RequestWaitTracker;
import com.minecolonies.core.colony.requestsystem.wait.WaitDiagnosis;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.minecolonies.api.util.constant.RSConstants.CONST_RETRYING_RESOLVER_PRIORITY;

public class StandardRetryingRequestResolver implements IRetryingRequestResolver
{

    private static final Integer CONST_RETRYING_ID_SCALE = -20000;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY = 1200;

    /**
     * RS2 fallback back-off, in request-system ticks (11 game ticks each): the first retry after 20 (220 game ticks), then
     * twice as long each time, at most 600 (6600 game ticks). Events retry sooner.
     */
    private static final int BACKOFF_FIRST = 20;
    private static final int BACKOFF_MAX   = 600;

    private       IRequestManager             manager;
    private final ILocation                   location;
    private final IToken<?>                   id;
    private       IToken<?>                   current;
    private final HashMap<IToken<?>, Integer> assignedRequests = new HashMap<>();

    /**
     * Retry clock: counts request-system ticks. A request's delay is kept as the clock value it falls due at, so a tick
     * does not touch the requests that are still waiting.
     */
    private long ticks;

    /**
     * Request to the clock value it falls due at.
     */
    private final HashMap<IToken<?>, Long> dueAt = new HashMap<>();

    /**
     * Clock value to the requests that fall due then (in the order they were set).
     */
    private final HashMap<Long, LinkedHashSet<IToken<?>>> dueBuckets = new HashMap<>();

    public StandardRetryingRequestResolver(final IFactoryController factoryController, final IRequestManager manager)
    {
        this.updateManager(manager);

        this.id = factoryController.getNewInstance(TypeConstants.ITOKEN, manager.getColony().getID() * CONST_RETRYING_ID_SCALE);
        this.location = factoryController.getNewInstance(TypeConstants.ILOCATION, manager.getColony().getCenter(), manager.getColony().getDimension());
    }

    public StandardRetryingRequestResolver(final IToken<?> id, final ILocation location)
    {
        this.id = id;
        this.location = location;
    }

    @Override
    public void updateManager(final IRequestManager manager)
    {
        this.manager = manager;
    }

    @Override
    public int getMaximalTries()
    {
        return MAX_RETRIES;
    }

    @Override
    public int getMaximalDelayBetweenRetriesInTicks()
    {
        return RETRY_DELAY;
    }

    @Override
    public int getCurrentReassignmentAttempt()
    {
        return isReassigning() ? -1 : assignedRequests.get(getCurrentlyBeingReassignedRequest()) + 1;
    }

    @Nullable
    @Override
    public IToken<?> getCurrentlyBeingReassignedRequest()
    {
        return current;
    }

    @Override
    public TypeToken<? extends IRetryable> getRequestType()
    {
        return TypeConstants.RETRYABLE;
    }

    @Override
    public boolean canResolveRequest(@NotNull final IRequestManager manager, final IRequest<? extends IRetryable> requestToCheck)
    {
        if (RsFlags.smartRetry())
        {
            // Wait for anything that something in the colony can still make or gather; only the rest goes to the player.
            return !WaitDiagnosis.neverProducible(manager, requestToCheck);
        }
        return getCurrentlyBeingReassignedRequest() == null || requestToCheck.getId() != getCurrentlyBeingReassignedRequest()
                 || getCurrentReassignmentAttempt() < getMaximalTries();
    }

    @Nullable
    @Override
    public List<IToken<?>> attemptResolveRequest(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends IRetryable> request)
    {
        return ImmutableList.of();
    }

    @Override
    public void resolveRequest(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends IRetryable> request) throws RuntimeException
    {
        final int attempt = assignedRequests.containsKey(request.getId()) ? assignedRequests.get(request.getId()) + 1 : 1;
        assignedRequests.put(request.getId(), attempt);
        if (RsFlags.smartRetry())
        {
            setDelay(request.getId(), backoff(attempt));
            final RequestWaitTracker tracker = RequestWaitTracker.of(manager);
            if (tracker != null)
            {
                tracker.register(request, RequestWaitTracker.Holder.RETRYING, WaitDiagnosis.forStarvedRequest(manager, request));
            }
            return;
        }
        setDelay(request.getId(), getMaximalDelayBetweenRetriesInTicks());
    }

    /**
     * Sets the number of ticks until the request is retried.
     */
    private void setDelay(final IToken<?> token, final int delay)
    {
        removeDelay(token);
        final long due = ticks + delay;
        dueAt.put(token, due);
        dueBuckets.computeIfAbsent(due, k -> new LinkedHashSet<>()).add(token);
    }

    private void removeDelay(final IToken<?> token)
    {
        final Long due = dueAt.remove(token);
        if (due != null)
        {
            final LinkedHashSet<IToken<?>> bucket = dueBuckets.get(due);
            if (bucket != null)
            {
                bucket.remove(token);
                if (bucket.isEmpty())
                {
                    dueBuckets.remove(due);
                }
            }
        }
    }

    /**
     * @param attempt the number of the attempt that just failed (1 for the first).
     * @return the request-system ticks to wait before the next one.
     */
    static int backoff(final int attempt)
    {
        return (int) Math.min(BACKOFF_MAX, (long) BACKOFF_FIRST << Math.min(Math.max(attempt - 1, 0), 10));
    }

    /**
     * RS2: an event could have unblocked this request: retry it on the next tick instead of when its delay runs out.
     *
     * @param token the request token.
     */
    public void expedite(@NotNull final IToken<?> token)
    {
        if (assignedRequests.containsKey(token))
        {
            setDelay(token, 1);
        }
    }

    /**
     * @param token a request token.
     * @return true when this resolver currently holds the request.
     */
    public boolean isHolding(@NotNull final IToken<?> token)
    {
        return assignedRequests.containsKey(token);
    }

    @Nullable
    @Override
    public List<IRequest<?>> getFollowupRequestForCompletion(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends IRetryable> completedRequest)
    {
        //Gets never called, since these will never get completed, only overruled or reassigned.
        return null;
    }

    @Nullable
    @Override
    public void onAssignedRequestBeingCancelled(@NotNull final IRequestManager manager, @NotNull final IRequest<? extends IRetryable> request)
    {
        if (assignedRequests.containsKey(request.getId()))
        {
            removeDelay(request.getId());
            assignedRequests.remove(request.getId());
        }
    }

    @Override
    public void onAssignedRequestCancelled(
      @NotNull final IRequestManager manager, @NotNull final IRequest<? extends IRetryable> request)
    {

    }

    @Override
    public int getPriority()
    {
        return CONST_RETRYING_RESOLVER_PRIORITY;
    }

    @Override
    public void tick()
    {
        manager.log("Starting reassignment.");

        //Lets advance the clock; the requests whose delay runs out now are the ones due at this value
        ticks++;
        final LinkedHashSet<IToken<?>> bucket = dueBuckets.remove(ticks);
        if (bucket == null)
        {
            manager.log("Finished reassignment.");
            return;
        }
        final Set<IToken<?>> retryables = new LinkedHashSet<>();
        for (final IToken<?> t : bucket)
        {
            // as before, only a request that is still held can be retried
            if (assignedRequests.containsKey(t))
            {
                retryables.add(t);
            }
            dueAt.remove(t);
        }
        final Set<IToken<?>> successfully = retryables.stream().filter(t -> {
            final Set<IToken<?>> blackList = RsFlags.smartRetry() || assignedRequests.get(t) < getMaximalTries() ? ImmutableSet.of() : ImmutableSet.of(id);

            Integer currentAttempt = assignedRequests.get(t);

            this.setCurrent(t);
            final IToken<?> resultingResolver;

            try
            {
                resultingResolver = manager.reassignRequest(t, blackList);
            }
            catch (Exception ex)
            {
                assignedRequests.remove(t);
                removeDelay(t);
                return false;
            }

            this.setCurrent(null);

            assignedRequests.put(t, ++currentAttempt);

            if (resultingResolver != null && !resultingResolver.equals(getId()))
            {
                assignedRequests.remove(t);
                removeDelay(t);
            }
            else if (resultingResolver != null && RsFlags.smartRetry())
            {
                // Back here: wait twice as long before the next fallback retry (events still wake it sooner).
                setDelay(t, backoff(currentAttempt));
            }

            return resultingResolver != null;
        }).collect(Collectors.toSet());

        retryables.stream().filter(t -> !successfully.contains(t)).forEach(t -> {
            manager.log("Failed to reassign a retryable request: " + t);
        });

        manager.log("Finished reassignment.");
    }

    @Override
    public ImmutableList<IToken<?>> getAllAssignedRequests()
    {
        return ImmutableList.copyOf(assignedRequests.keySet());
    }

    @Override
    public void onSystemReset()
    {
        assignedRequests.clear();
        dueAt.clear();
        dueBuckets.clear();
    }

    public void setCurrent(@Nullable final IToken<?> token)
    {
        this.current = token;
    }

    @Override
    public IToken<?> getId()
    {
        return id;
    }

    @NotNull
    @Override
    public ILocation getLocation()
    {
        return location;
    }

    @Override
    public void onRequestedRequestComplete(@NotNull final IRequestManager manager, @NotNull final IRequest<?> request)
    {
        //Noop, we do not schedule child requests. So this is never called.
    }

    @Override
    public void onRequestedRequestCancelled(@NotNull final IRequestManager manager, @NotNull final IRequest<?> request)
    {
        //Noop, see onRequestComplete.
    }

    @NotNull
    @Override
    public MutableComponent getRequesterDisplayName(@NotNull final IRequestManager manager, @NotNull final IRequest<?> request)
    {
        return Component.literal("Player");
    }

    public void updateData(@NotNull final Map<IToken<?>, Integer> newAssignedRequests, @NotNull final Map<IToken<?>, Integer> newDelays)
    {
        this.assignedRequests.clear();
        this.assignedRequests.putAll(newAssignedRequests);

        this.dueAt.clear();
        this.dueBuckets.clear();
        newDelays.forEach(this::setDelay);
    }

    /**
     * @return the ticks left until each request is retried (a copy).
     */
    public Map<IToken<?>, Integer> getDelays()
    {
        final Map<IToken<?>, Integer> delays = new HashMap<>();
        dueAt.forEach((token, due) -> delays.put(token, (int) (due - ticks)));
        return delays;
    }

    public Map<IToken<?>, Integer> getAssignedRequests()
    {
        return assignedRequests;
    }

    @Override
    public void onColonyUpdate(@NotNull final IRequestManager manager, @NotNull final Predicate<IRequest<?>> shouldTriggerReassign)
    {
        new ArrayList<>(assignedRequests.keySet()).stream()
          .map(manager::getRequestForToken)
          .forEach(request ->
          {
              if (request != null)
              {
                  if (shouldTriggerReassign.test(request))
                  {
                      // RS2: a request that still cannot be served stays here, instead of being handed to the player.
                      final IToken<?> newResolverToken = manager.reassignRequest(request.getId(), RsFlags.smartRetry() ? ImmutableList.of() : ImmutableList.of(getId()));
                      if (newResolverToken != null && !newResolverToken.equals(getId()))
                      {
                          assignedRequests.remove(request.getId());
                      }
                  }
                  else
                  {
                      IRequest<?> req = request;
                      while (req.hasParent())
                      {
                          req = manager.getRequestForToken(req.getParent());
                          if (req != null && shouldTriggerReassign.test(req))
                          {
                              if (req.hasChildren())
                              {
                                  final ImmutableCollection<IToken<?>> currentChildren = req.getChildren();
                                  currentChildren.forEach(((IStandardRequestManager) manager).getRequestHandler()::onRequestCancelledDirectly);
                              }

                              IToken<?> newResolverToken = manager.reassignRequest(req.getId(), ImmutableList.of(getId()));
                              if (!getId().equals(newResolverToken))
                              {
                                  assignedRequests.remove(request.getId());
                                  break;
                              }
                          }
                      }
                  }
              }
          });
    }

    @Override
    public boolean isValid()
    {
        // Always valid
        return true;
    }
}
