package com.minecolonies.core.colony.requestsystem.wait;

import com.google.common.collect.ImmutableList;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.request.WaitReason;
import com.minecolonies.api.colony.requestsystem.requestable.IConcreteDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.crafting.AbstractCrafting;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Pickup;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.eventbus.events.colony.requests.RequestStateChangedEvent;
import com.minecolonies.api.eventbus.events.colony.requests.RequestStateChangedModEvent;
import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.WarehouseRequestQueueModule;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.core.colony.requestsystem.management.IStandardRequestManager;
import com.minecolonies.core.colony.requestsystem.resolvers.StandardPlayerRequestResolver;
import com.minecolonies.core.colony.requestsystem.resolvers.StandardRetryingRequestResolver;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RS2: the colony's waiting requests, by what they wait for.
 * <p>
 * A request that nothing can serve now is parked at the retrying resolver (or, when nothing can ever make it, at the
 * player). Instead of retrying on a fixed ladder, the tracker indexes them by item and wakes exactly those that an event
 * could unblock: stock added to a warehouse rack, a reservation released, a crafter finished, a courier freed. A woken
 * request is retried by the next request-system tick; exponential back-off at the retrying resolver stays as the fallback.
 * <p>
 * It also owns the wait reasons: setting them on requests, deriving a parent's from its children, and raising the
 * state-changed events.
 */
public final class RequestWaitTracker
{
    /**
     * Waiters across all colonies; lets hot hooks (rack changes) skip all work when nobody waits.
     */
    private static final AtomicInteger ACTIVE = new AtomicInteger();

    /**
     * Game ticks between two wake-driven retries of the same request.
     */
    public static final long WAKE_COOLDOWN = 40L;

    /**
     * Wakes dispatched per request-system tick.
     */
    private static final int MAX_WAKES_PER_TICK = 8;

    /**
     * Game ticks between two refreshes of the courier-related reasons.
     */
    private static final long REFRESH_INTERVAL = 200L;

    /**
     * Who holds a waiting request.
     */
    public enum Holder
    {
        RETRYING,
        PLAYER
    }

    /**
     * One waiting request.
     */
    public static final class Waiter
    {
        final IToken<?>     token;
        final Holder        holder;
        @Nullable
        final Set<Item>     items;
        final long          since;
        long                notBefore;
        int                 wakes;

        Waiter(final IToken<?> token, final Holder holder, @Nullable final Set<Item> items, final long since)
        {
            this.token = token;
            this.holder = holder;
            this.items = items;
            this.since = since;
        }

        public IToken<?> token()
        {
            return token;
        }

        public Holder holder()
        {
            return holder;
        }

        public long since()
        {
            return since;
        }

        public int wakes()
        {
            return wakes;
        }
    }

    private final IStandardRequestManager manager;
    private final RequestNotifier         notifier = new RequestNotifier();

    private final Map<IToken<?>, Waiter>      waiters  = new LinkedHashMap<>();
    private final Map<Item, Set<IToken<?>>>   byItem   = new HashMap<>();
    private final Set<IToken<?>>              wildcard = new LinkedHashSet<>();
    private final Set<IToken<?>>              due      = new LinkedHashSet<>();

    /**
     * Reasons a resolver gave while refusing a request (for instance a full destination), used when the request ends up
     * parked: the retrying resolver cannot know what the others saw.
     */
    private final Map<IToken<?>, WaitReason> hints = new HashMap<>();

    private long lastRefresh;

    /**
     * Requests woken by an event, over the life of the manager. Statistic for tests.
     */
    private long woken;

    /**
     * State-changed events raised. Statistic for tests.
     */
    private long eventsRaised;

    public RequestWaitTracker(@NotNull final IStandardRequestManager manager)
    {
        this.manager = manager;
    }

    /**
     * @param manager a request manager, possibly a wrapped one.
     * @return the tracker of the colony it manages, null when there is none (fake managers).
     */
    @Nullable
    public static RequestWaitTracker of(@NotNull final com.minecolonies.api.colony.requestsystem.manager.IRequestManager manager)
    {
        final IStandardRequestManager standard = com.minecolonies.core.colony.requestsystem.RsAccess.standard(manager);
        return standard == null ? null : standard.getWaitTracker();
    }

    /**
     * @return true when at least one request waits somewhere.
     */
    public static boolean anyWaiters()
    {
        return ACTIVE.get() > 0;
    }

    @NotNull
    public RequestNotifier notifier()
    {
        return notifier;
    }

    // ------------------------------------------------------------------ register

    /**
     * A request is now parked: nothing can serve it at the moment.
     *
     * @param request the request.
     * @param holder  who holds it.
     * @param reason  why it waits.
     */
    public void register(@NotNull final IRequest<?> request, @NotNull final Holder holder, @NotNull final WaitReason reason)
    {
        final long now = now();
        final Waiter previous = waiters.get(request.getId());
        final Set<Item> items = interestOf(request);
        final Waiter waiter = new Waiter(request.getId(), holder, items, previous == null ? now : previous.since);
        waiter.wakes = previous == null ? 0 : previous.wakes;
        waiter.notBefore = previous == null ? 0 : previous.notBefore;
        if (previous != null)
        {
            index(previous, false);
        }
        else
        {
            ACTIVE.incrementAndGet();
        }
        waiters.put(request.getId(), waiter);
        index(waiter, true);
        final WaitReason hinted = hints.remove(request.getId());
        final WaitReason shown = hinted != null && reason != WaitReason.PLAYER_REQUIRED ? hinted : reason;
        setReason(request, shown);
        notifier.report(manager.getColony(), request, shown, now);
    }

    /**
     * A resolver refused a request for a reason that the parked request should show.
     *
     * @param token  the request token.
     * @param reason the reason.
     */
    public void hint(@NotNull final IToken<?> token, @NotNull final WaitReason reason)
    {
        hints.put(token, reason);
    }

    /**
     * The request is no longer parked (it was reassigned, finished or cancelled).
     *
     * @param token the request token.
     */
    public void unregister(@NotNull final IToken<?> token)
    {
        final Waiter waiter = waiters.remove(token);
        if (waiter != null)
        {
            index(waiter, false);
            due.remove(token);
            ACTIVE.decrementAndGet();
        }
    }

    /**
     * Drops everything (the request system is reset).
     */
    public void clear()
    {
        ACTIVE.addAndGet(-waiters.size());
        waiters.clear();
        byItem.clear();
        wildcard.clear();
        due.clear();
        hints.clear();
    }

    private void index(final Waiter waiter, final boolean add)
    {
        if (waiter.items == null)
        {
            if (add)
            {
                wildcard.add(waiter.token);
            }
            else
            {
                wildcard.remove(waiter.token);
            }
            return;
        }
        for (final Item item : waiter.items)
        {
            if (add)
            {
                byItem.computeIfAbsent(item, i -> new LinkedHashSet<>()).add(waiter.token);
            }
            else
            {
                final Set<IToken<?>> set = byItem.get(item);
                if (set != null)
                {
                    set.remove(waiter.token);
                    if (set.isEmpty())
                    {
                        byItem.remove(item);
                    }
                }
            }
        }
    }

    @Nullable
    private static Set<Item> interestOf(final IRequest<?> request)
    {
        if (request.getRequest() instanceof final IConcreteDeliverable concrete)
        {
            final Set<Item> items = new HashSet<>();
            for (final ItemStack stack : concrete.getRequestedItems())
            {
                items.add(stack.getItem());
            }
            return items.isEmpty() ? null : items;
        }
        return null;
    }

    // ------------------------------------------------------------------ wake

    /**
     * Stock of these items showed up (a warehouse rack gained them) or was released from a reservation.
     *
     * @param samples one stack per item that appeared.
     */
    public void itemsAvailable(@NotNull final Collection<ItemStack> samples)
    {
        if (waiters.isEmpty() || samples.isEmpty())
        {
            return;
        }
        for (final ItemStack sample : samples)
        {
            final Set<IToken<?>> exact = byItem.get(sample.getItem());
            if (exact != null)
            {
                due.addAll(exact);
            }
            for (final IToken<?> token : wildcard)
            {
                final IRequest<?> request = manager.getRequestForToken(token);
                if (request != null && request.getRequest() instanceof final IDeliverable deliverable && deliverable.matches(sample))
                {
                    due.add(token);
                }
            }
        }
    }

    /**
     * Wakes every waiter whose reason is one of the given ones (a crafter finished, a courier freed up).
     *
     * @param reasons the reasons that the event could have resolved.
     */
    public void wakeByReason(@NotNull final Set<WaitReason> reasons)
    {
        if (waiters.isEmpty())
        {
            return;
        }
        for (final Waiter waiter : waiters.values())
        {
            final IRequest<?> request = manager.getRequestForToken(waiter.token);
            if (request != null && reasons.contains(request.getWaitReason()))
            {
                due.add(waiter.token);
            }
        }
    }

    /**
     * Wakes a single waiter.
     *
     * @param token the request token.
     */
    public void wake(@NotNull final IToken<?> token)
    {
        if (waiters.containsKey(token))
        {
            due.add(token);
        }
    }

    /**
     * Retries the requests an event woke. Runs from the request system tick.
     */
    public void tick()
    {
        final long now = now();
        if (!due.isEmpty())
        {
            int dispatched = 0;
            for (final IToken<?> token : ImmutableList.copyOf(due))
            {
                final Waiter waiter = waiters.get(token);
                if (waiter == null)
                {
                    due.remove(token);
                    continue;
                }
                if (now < waiter.notBefore)
                {
                    continue;
                }
                if (dispatched >= MAX_WAKES_PER_TICK)
                {
                    break;
                }
                due.remove(token);
                dispatched++;
                waiter.notBefore = now + WAKE_COOLDOWN;
                waiter.wakes++;
                woken++;
                dispatch(waiter);
            }
        }

        if (now - lastRefresh >= REFRESH_INTERVAL)
        {
            lastRefresh = now;
            dropStaleWaiters();
            // A destination frees room without any event we could hear (its worker takes items): look again now and then.
            wakeByReason(Set.of(WaitReason.TARGET_FULL));
            refreshCourierReasons();
        }
    }

    /**
     * A request that left the retrying resolver or the player (it found a resolver, or became a parent) is no waiter.
     */
    private void dropStaleWaiters()
    {
        for (final Waiter waiter : ImmutableList.copyOf(waiters.values()))
        {
            if (!holds(waiter))
            {
                unregister(waiter.token);
            }
        }
    }

    private boolean holds(final Waiter waiter)
    {
        if (waiter.holder == Holder.RETRYING)
        {
            return manager.getRetryingRequestResolver() instanceof final StandardRetryingRequestResolver retrying && retrying.isHolding(waiter.token);
        }
        return manager.getPlayerResolver() instanceof final StandardPlayerRequestResolver player && player.isHolding(waiter.token);
    }

    private void dispatch(final Waiter waiter)
    {
        final IRequest<?> request = manager.getRequestForToken(waiter.token);
        if (request == null || !holds(waiter))
        {
            unregister(waiter.token);
            return;
        }
        try
        {
            if (waiter.holder == Holder.RETRYING && manager.getRetryingRequestResolver() instanceof final StandardRetryingRequestResolver retrying)
            {
                // The retry tick that follows reassigns it.
                retrying.expedite(waiter.token);
            }
            else
            {
                manager.reassignRequest(waiter.token, ImmutableList.of());
            }
        }
        catch (final RuntimeException e)
        {
            Log.getLogger().warn("Could not retry the waiting request " + waiter.token, e);
        }
    }

    // ------------------------------------------------------------------ reasons

    /**
     * Sets a request's wait reason and raises the event when it changed.
     *
     * @param request the request.
     * @param reason  the new reason.
     */
    public void setReason(@NotNull final IRequest<?> request, @NotNull final WaitReason reason)
    {
        final WaitReason old = request.getWaitReason();
        if (request.setWaitReason(reason))
        {
            manager.markDirty();
            raise(request, request.getState(), request.getState(), old, reason);
            refreshAncestors(request);
        }
    }

    /**
     * A request changed state. Clears its reason once it is past waiting, derives it from the children while it waits for
     * them, and raises the event.
     *
     * @param request  the request.
     * @param oldState the state before.
     */
    public void onStateChanged(@NotNull final IRequest<?> request, @Nullable final RequestState oldState)
    {
        final RequestState state = request.getState();
        final WaitReason old = request.getWaitReason();
        WaitReason next = old;
        switch (state)
        {
            case RESOLVED, FOLLOWUP_IN_PROGRESS, COMPLETED, RECEIVED, CANCELLED, FAILED, OVERRULED, FINALIZING ->
            {
                next = request.hasChildren() && state == RequestState.FOLLOWUP_IN_PROGRESS ? fromChildren(request) : WaitReason.NONE;
                unregister(request.getId());
                hints.remove(request.getId());
                if (state != RequestState.RESOLVED && state != RequestState.FOLLOWUP_IN_PROGRESS)
                {
                    notifier.forget(request.getId());
                }
            }
            case IN_PROGRESS ->
            {
                if (request.hasChildren())
                {
                    next = fromChildren(request);
                }
            }
            default ->
            {
            }
        }
        final boolean reasonChanged = request.setWaitReason(next);
        if (reasonChanged)
        {
            manager.markDirty();
        }
        if (oldState != state || reasonChanged)
        {
            raise(request, oldState, state, old, next);
        }
        if (oldState != state || reasonChanged)
        {
            refreshAncestors(request);
        }
    }

    /**
     * A parent that waits for its children is described by them.
     */
    @NotNull
    private WaitReason fromChildren(final IRequest<?> parent)
    {
        WaitReason best = WaitReason.NONE;
        boolean crafting = false;
        boolean delivering = false;
        for (final IToken<?> childToken : parent.getChildren())
        {
            final IRequest<?> child = manager.getRequestForToken(childToken);
            if (child == null)
            {
                continue;
            }
            final WaitReason reason = child.getWaitReason();
            if (reason.isWaiting() && reason.severity().ordinal() > best.severity().ordinal())
            {
                best = reason;
            }
            else if (reason.isWaiting() && best == WaitReason.NONE)
            {
                best = reason;
            }
            crafting |= child.getRequest() instanceof AbstractCrafting;
            delivering |= child.getRequest() instanceof Delivery;
        }
        if (best != WaitReason.NONE)
        {
            return best;
        }
        if (crafting)
        {
            return WaitReason.AWAITING_CRAFT;
        }
        return delivering ? WaitReason.AWAITING_DELIVERY : WaitReason.NONE;
    }

    private void refreshAncestors(final IRequest<?> request)
    {
        IRequest<?> current = request;
        for (int depth = 0; depth < 32 && current.hasParent(); depth++)
        {
            final IRequest<?> parent = manager.getRequestForToken(current.getParent());
            if (parent == null || !parent.hasChildren())
            {
                return;
            }
            final RequestState state = parent.getState();
            if (state != RequestState.IN_PROGRESS && state != RequestState.FOLLOWUP_IN_PROGRESS)
            {
                return;
            }
            final WaitReason old = parent.getWaitReason();
            final WaitReason next = fromChildren(parent);
            if (!parent.setWaitReason(next))
            {
                return;
            }
            manager.markDirty();
            raise(parent, state, state, old, next);
            current = parent;
        }
    }

    private void raise(final IRequest<?> request, @Nullable final RequestState oldState, final RequestState newState, final WaitReason oldReason, final WaitReason newReason)
    {
        final IColony colony = manager.getColony();
        if (colony.getWorld().isClientSide())
        {
            return;
        }
        eventsRaised++;
        try
        {
            IMinecoloniesAPI.getInstance().getEventBus().post(new RequestStateChangedModEvent(colony, request.getId(), oldState, newState, oldReason, newReason));
            NeoForge.EVENT_BUS.post(new RequestStateChangedEvent(colony, request.getId(), oldState, newState, oldReason, newReason));
        }
        catch (final RuntimeException e)
        {
            Log.getLogger().warn("A request state listener failed", e);
        }
    }

    /**
     * Deliveries and pickups in the warehouses' queues: names why they are not being carried.
     */
    private void refreshCourierReasons()
    {
        if (!(manager.getColony() instanceof final Colony colony) || colony.getWorld().isClientSide())
        {
            return;
        }
        for (final IWareHouse wareHouse : colony.getServerBuildingManager().getWareHouses())
        {
            final WarehouseRequestQueueModule queue = wareHouse.getModule(BuildingModules.WAREHOUSE_REQUEST_QUEUE);
            if (queue == null)
            {
                continue;
            }
            for (final IToken<?> token : ImmutableList.copyOf(queue.getMutableRequestList()))
            {
                final IRequest<?> request = manager.getRequestForToken(token);
                if (request == null || !(request.getRequest() instanceof Delivery || request.getRequest() instanceof Pickup))
                {
                    continue;
                }
                final WaitReason reason = allCouriersBusy(wareHouse, colony) ? WaitReason.COURIER_BUSY : WaitReason.AWAITING_DELIVERY;
                setReason(request, request.getRequest() instanceof Delivery && WaitDiagnosis.forCourierTask(colony, request) == WaitReason.UNLOADED ? WaitReason.UNLOADED : reason);
            }
        }
    }

    private static boolean allCouriersBusy(final IWareHouse wareHouse, final Colony colony)
    {
        final var couriers = wareHouse.getModule(BuildingModules.WAREHOUSE_COURIERS).getAssignedCitizen();
        if (couriers.isEmpty())
        {
            return false;
        }
        for (final var courier : couriers)
        {
            if (courier.getJob() instanceof final JobDeliveryman job && job.getTaskQueue().isEmpty())
            {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ views

    public int waiterCount()
    {
        return waiters.size();
    }

    /**
     * @return the waiting requests, longest waiting first.
     */
    @NotNull
    public List<Waiter> waiters()
    {
        return ImmutableList.copyOf(waiters.values());
    }

    @Nullable
    public Waiter waiter(@NotNull final IToken<?> token)
    {
        return waiters.get(token);
    }

    public long wokenCount()
    {
        return woken;
    }

    public long eventCount()
    {
        return eventsRaised;
    }

    private long now()
    {
        return manager.getColony().getWorld().getGameTime();
    }
}
