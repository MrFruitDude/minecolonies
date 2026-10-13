package com.minecolonies.core.colony.requestsystem.resolvers;

import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.requestsystem.token.StandardToken;
import com.minecolonies.core.colony.requestsystem.requests.RsTestSupport;
import com.minecolonies.core.colony.requestsystem.requests.RsTestSupport.Payload;
import com.minecolonies.core.colony.requestsystem.requests.RsTestSupport.TestRequest;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Retrying resolver behaviour on a fake request manager.
 */
public class StandardRetryingRequestResolverTest
{
    private static StandardRetryingRequestResolver resolver(final IToken<?>... due)
    {
        final StandardRetryingRequestResolver resolver = new StandardRetryingRequestResolver(RsTestSupport.token(), null);
        final Map<IToken<?>, Integer> attempts = new HashMap<>();
        final Map<IToken<?>, Integer> delays = new HashMap<>();
        for (final IToken<?> token : due)
        {
            attempts.put(token, 0);
            // One tick left: the resolver's next tick takes it to zero and retries it.
            delays.put(token, 1);
        }
        resolver.updateData(attempts, delays);
        return resolver;
    }

    /**
     * The reassignment log used to name the requests that were reassigned as the failures.
     */
    @Test
    public void failedReassignmentIsLoggedForTheRequestThatFailed()
    {
        final IToken<?> failing = RsTestSupport.token();
        final IToken<?> succeeding = RsTestSupport.token();
        final IToken<?> otherResolver = RsTestSupport.token();
        final List<String> logs = new ArrayList<>();
        final IRequestManager manager = RsTestSupport.manager(logs, new HashMap<>(), (token, blacklist) -> token.equals(failing) ? null : otherResolver);

        final StandardRetryingRequestResolver resolver = resolver(failing, succeeding);
        resolver.updateManager(manager);
        resolver.tick();

        final List<String> failures = logs.stream().filter(line -> line.contains("Failed to reassign")).toList();
        assertEquals("one request failed to reassign: " + logs, 1, failures.size());
        assertTrue("the failure line must name the failed request " + failing + ": " + failures, failures.get(0).contains(failing.toString()));
    }

    @Test
    public void nothingIsLoggedAsFailedWhenEveryReassignmentSucceeds()
    {
        final IToken<?> first = RsTestSupport.token();
        final IToken<?> second = RsTestSupport.token();
        final IToken<?> otherResolver = RsTestSupport.token();
        final List<String> logs = new ArrayList<>();
        final IRequestManager manager = RsTestSupport.manager(logs, new HashMap<>(), (token, blacklist) -> otherResolver);

        final StandardRetryingRequestResolver resolver = resolver(first, second);
        resolver.updateManager(manager);
        resolver.tick();

        assertTrue("successful reassignments were logged as failures: " + logs, logs.stream().noneMatch(line -> line.contains("Failed to reassign")));
        assertTrue("both requests left the retrying resolver", resolver.getAssignedRequests().isEmpty());
    }

    /**
     * A parent that is reassigned back onto this very resolver has not moved: equal tokens must compare equal even
     * when the manager hands back a different token instance.
     */
    @Test
    public void colonyUpdateKeepsTheRequestWhenTheParentLandsBackOnTheRetryingResolver()
    {
        final StandardRetryingRequestResolver resolver = new StandardRetryingRequestResolver(RsTestSupport.token(), null);
        final TestRequest parent = new TestRequest(RsTestSupport.token(), new Payload());
        final TestRequest child = new TestRequest(RsTestSupport.token(), new Payload());
        child.setParent(parent.getId());

        final Map<IToken<?>, IRequest<?>> requests = new HashMap<>();
        requests.put(parent.getId(), parent);
        requests.put(child.getId(), child);
        final IToken<?> sameResolverOtherInstance = new StandardToken((java.util.UUID) resolver.getId().getIdentifier());
        final IRequestManager manager = RsTestSupport.manager(new ArrayList<>(), requests, (token, blacklist) -> sameResolverOtherInstance);

        final Map<IToken<?>, Integer> attempts = new HashMap<>();
        attempts.put(child.getId(), 0);
        resolver.updateData(attempts, new HashMap<>());

        resolver.onColonyUpdate(manager, request -> request == parent);

        assertTrue("the request left the retrying resolver although its parent was reassigned onto the same resolver",
          resolver.getAssignedRequests().containsKey(child.getId()));
    }

    /**
     * RS2 fallback: the back-off doubles from 20 request-system ticks and stops at 600.
     */
    @Test
    public void backoffDoublesAndIsCapped()
    {
        assertEquals(20, StandardRetryingRequestResolver.backoff(1));
        assertEquals(40, StandardRetryingRequestResolver.backoff(2));
        assertEquals(80, StandardRetryingRequestResolver.backoff(3));
        assertEquals(160, StandardRetryingRequestResolver.backoff(4));
        assertEquals(320, StandardRetryingRequestResolver.backoff(5));
        assertEquals(600, StandardRetryingRequestResolver.backoff(6));
        assertEquals(600, StandardRetryingRequestResolver.backoff(1000));
        assertEquals("a bogus attempt number does not overflow", 20, StandardRetryingRequestResolver.backoff(0));
    }

    /**
     * RS2: an event makes the request due on the next tick, only if the resolver holds it.
     */
    @Test
    public void expediteBringsTheRetryForward()
    {
        final IToken<?> waiting = RsTestSupport.token();
        final IToken<?> stranger = RsTestSupport.token();
        final IToken<?> otherResolver = RsTestSupport.token();
        final List<IToken<?>> retried = new ArrayList<>();
        final IRequestManager manager = RsTestSupport.manager(new ArrayList<>(), new HashMap<>(), (token, blacklist) -> {
            retried.add(token);
            return otherResolver;
        });

        final StandardRetryingRequestResolver resolver = new StandardRetryingRequestResolver(RsTestSupport.token(), null);
        final Map<IToken<?>, Integer> attempts = new HashMap<>();
        final Map<IToken<?>, Integer> delays = new HashMap<>();
        attempts.put(waiting, 2);
        delays.put(waiting, 500);
        resolver.updateData(attempts, delays);
        resolver.updateManager(manager);

        resolver.expedite(stranger);
        resolver.tick();
        assertEquals("nothing is due yet", 0, retried.size());
        assertEquals("a stranger is not adopted", false, resolver.isHolding(stranger));

        resolver.expedite(waiting);
        resolver.tick();
        assertEquals("woken: retried on the next tick", List.of(waiting), retried);
    }

    /**
     * What a client can see of the resolver is the set of held requests and their attempts. The delays count down every tick, the client
     * does not read them: a tick that only counts down, and an event that only brings a retry forward, must not move the view version (the
     * view sync serializes the entry again whenever it moves), while the saved delays still count down.
     */
    @Test
    public void countdownAndExpediteAreNoChangeOfTheClientView()
    {
        final IToken<?> waiting = RsTestSupport.token();
        final IToken<?> other = RsTestSupport.token();
        final IRequestManager manager = RsTestSupport.manager(new ArrayList<>(), new HashMap<>(), (token, blacklist) -> null);
        final StandardRetryingRequestResolver resolver = new StandardRetryingRequestResolver(RsTestSupport.token(), null);
        final Map<IToken<?>, Integer> attempts = new HashMap<>();
        final Map<IToken<?>, Integer> delays = new HashMap<>();
        attempts.put(waiting, 1);
        delays.put(waiting, 40);
        resolver.updateData(attempts, delays);
        resolver.updateManager(manager);

        final long version = resolver.viewVersion();
        resolver.tick();
        resolver.tick();
        resolver.tick();
        assertEquals("the delay still counts down (it is saved)", 37, (int) resolver.getDelays().get(waiting));
        assertEquals("a tick that only counted down changed what the client sees", version, resolver.viewVersion());

        resolver.expedite(waiting);
        assertEquals("the retry is due next tick", 1, (int) resolver.getDelays().get(waiting));
        assertEquals("bringing a retry forward changed what the client sees", version, resolver.viewVersion());

        resolver.expedite(other);
        assertEquals("a stranger changed nothing", version, resolver.viewVersion());
    }

    /**
     * A retry that fails again raises the attempt count, a request that leaves is no longer held: both are visible to a client.
     */
    @Test
    public void attemptsAndLeavingRequestsMoveTheClientView()
    {
        final IToken<?> waiting = RsTestSupport.token();
        final IToken<?> otherResolver = RsTestSupport.token();
        final StandardRetryingRequestResolver resolver = new StandardRetryingRequestResolver(RsTestSupport.token(), null);
        final Map<IToken<?>, Integer> attempts = new HashMap<>();
        final Map<IToken<?>, Integer> delays = new HashMap<>();
        attempts.put(waiting, 1);
        delays.put(waiting, 1);
        resolver.updateData(attempts, delays);
        // the retry ends up on this resolver again: one more attempt
        final StandardRetryingRequestResolver[] self = {resolver};
        resolver.updateManager(RsTestSupport.manager(new ArrayList<>(), new HashMap<>(), (token, blacklist) -> self[0].getId()));

        final long before = resolver.viewVersion();
        resolver.tick();
        assertEquals("one more attempt", 2, (int) resolver.getAssignedRequests().get(waiting));
        assertTrue("a new attempt is visible to a client", resolver.viewVersion() > before);

        final long mid = resolver.viewVersion();
        resolver.updateManager(RsTestSupport.manager(new ArrayList<>(), new HashMap<>(), (token, blacklist) -> otherResolver));
        resolver.expedite(waiting);
        resolver.tick();
        assertTrue("the request left", resolver.getAssignedRequests().isEmpty());
        assertTrue("a request that left is visible to a client", resolver.viewVersion() > mid);
    }
}
