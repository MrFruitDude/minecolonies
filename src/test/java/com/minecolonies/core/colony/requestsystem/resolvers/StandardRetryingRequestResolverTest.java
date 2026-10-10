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
}
