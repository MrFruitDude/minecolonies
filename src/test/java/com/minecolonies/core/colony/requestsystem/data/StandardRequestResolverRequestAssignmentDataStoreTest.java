package com.minecolonies.core.colony.requestsystem.data;

import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.requestsystem.token.StandardToken;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The resolver to request assignment store answers "which resolver holds this request" on every
 * {@code isAssigned}, {@code getResolverForRequest} and save/load pass. It must not scan every resolver for it.
 */
public class StandardRequestResolverRequestAssignmentDataStoreTest
{
    private static IToken<?> token()
    {
        return new StandardToken();
    }

    private static StandardRequestResolverRequestAssignmentDataStore empty()
    {
        return new StandardRequestResolverRequestAssignmentDataStore(token(), new HashMap<>());
    }

    /**
     * What an NBT load produces: plain lists.
     */
    private static StandardRequestResolverRequestAssignmentDataStore loaded(final List<IToken<?>> resolvers, final List<List<IToken<?>>> requests)
    {
        final Map<IToken<?>, Collection<IToken<?>>> map = new HashMap<>();
        for (int i = 0; i < resolvers.size(); i++)
        {
            map.put(resolvers.get(i), new ArrayList<>(requests.get(i)));
        }
        return new StandardRequestResolverRequestAssignmentDataStore(token(), map);
    }

    @Test
    public void findsTheResolverOfLoadedAssignments()
    {
        final IToken<?> resolverA = token();
        final IToken<?> resolverB = token();
        final IToken<?> a1 = token();
        final IToken<?> b1 = token();
        final IToken<?> b2 = token();
        final StandardRequestResolverRequestAssignmentDataStore store = loaded(List.of(resolverA, resolverB), List.of(List.of(a1), List.of(b1, b2)));

        assertSame(resolverA, store.getAssignmentForValue(a1));
        assertSame(resolverB, store.getAssignmentForValue(b1));
        assertSame(resolverB, store.getAssignmentForValue(b2));
        assertNull(store.getAssignmentForValue(token()));
    }

    @Test
    public void followsEveryMutationTheRequestHandlersMake()
    {
        final StandardRequestResolverRequestAssignmentDataStore store = empty();
        final IToken<?> resolverA = token();
        final IToken<?> resolverB = token();
        final IToken<?> request = token();

        // ResolverHandler.addRequestToResolver
        store.getAssignments().put(resolverA, new HashSet<>());
        store.getAssignments().get(resolverA).add(request);
        assertSame(resolverA, store.getAssignmentForValue(request));

        // RequestHandler: remove the request, drop the resolver entry once it is empty
        store.getAssignments().get(resolverA).remove(request);
        assertNull("removed request still assigned", store.getAssignmentForValue(request));
        store.getAssignments().remove(resolverA);

        // reassign to another resolver
        store.getAssignments().put(resolverB, new HashSet<>());
        store.getAssignments().get(resolverB).add(request);
        assertSame(resolverB, store.getAssignmentForValue(request));

        // resolver removed with its requests (ResolverHandler.removeResolver)
        store.getAssignments().remove(resolverB);
        assertNull("request of a removed resolver still assigned", store.getAssignmentForValue(request));

        // a whole new collection replaces the old one
        final IToken<?> other = token();
        store.getAssignments().put(resolverA, new ArrayList<>(List.of(request, other)));
        store.getAssignments().put(resolverA, new ArrayList<>(List.of(other)));
        assertNull("replaced collection still indexed", store.getAssignmentForValue(request));
        assertSame(resolverA, store.getAssignmentForValue(other));

        store.getAssignments().clear();
        assertNull(store.getAssignmentForValue(other));
    }

    @Test
    public void aRequestMovedBetweenResolversIsFoundOnTheNewOne()
    {
        final StandardRequestResolverRequestAssignmentDataStore store = empty();
        final IToken<?> resolverA = token();
        final IToken<?> resolverB = token();
        final IToken<?> request = token();
        store.getAssignments().put(resolverA, new HashSet<>(List.of(request)));
        store.getAssignments().put(resolverB, new HashSet<>());

        store.getAssignments().get(resolverB).add(request);
        store.getAssignments().get(resolverA).remove(request);

        assertSame(resolverB, store.getAssignmentForValue(request));
    }

    /**
     * The cost of one lookup must not grow with the number of assigned requests. The old scan visits every resolver and
     * searches its list, so a lookup among 16 times the requests costs about 16 times as much.
     */
    @Test
    public void lookupCostDoesNotGrowWithTheNumberOfRequests()
    {
        final int resolvers = 100;
        double smallNanos = Double.MAX_VALUE;
        double largeNanos = Double.MAX_VALUE;
        // The first round warms the JIT up; the best of the others is kept: scaling, not absolute speed, is asserted.
        for (int round = 0; round < 4; round++)
        {
            final double small = nanosPerLookup(resolvers, 2_500);
            final double large = nanosPerLookup(resolvers, 40_000);
            if (round > 0)
            {
                smallNanos = Math.min(smallNanos, small);
                largeNanos = Math.min(largeNanos, large);
            }
        }
        final double ratio = largeNanos / smallNanos;
        System.out.printf("assignment lookup: %.0f ns with 2.5k requests, %.0f ns with 40k requests, ratio %.1f (hash lookup about 1-3, scan about 16)%n", smallNanos, largeNanos, ratio);
        assertTrue("a lookup among 16x the requests costs " + ratio + "x as much; a hash lookup costs about the same, the scan about 16x", ratio < 7);
    }

    /**
     * Ten thousand assigned requests: every one is found, none is missing, absent tokens are absent.
     */
    @Test
    public void tenThousandRequestsAreAllFound()
    {
        final List<IToken<?>> resolvers = new ArrayList<>();
        final List<List<IToken<?>>> requests = new ArrayList<>();
        for (int r = 0; r < 50; r++)
        {
            resolvers.add(token());
            requests.add(new ArrayList<>());
        }
        final List<IToken<?>> all = new ArrayList<>();
        for (int i = 0; i < 10_000; i++)
        {
            final IToken<?> request = token();
            all.add(request);
            requests.get(i % 50).add(request);
        }
        final StandardRequestResolverRequestAssignmentDataStore store = loaded(resolvers, requests);
        for (int i = 0; i < all.size(); i++)
        {
            assertEquals(resolvers.get(i % 50), store.getAssignmentForValue(all.get(i)));
        }
        assertNull(store.getAssignmentForValue(token()));
    }

    /**
     * Looks up every assigned request, repeating the pass until at least 20 ms have been spent.
     */
    private static double nanosPerLookup(final int resolvers, final int requests)
    {
        final List<IToken<?>> resolverTokens = new ArrayList<>();
        final List<List<IToken<?>>> perResolver = new ArrayList<>();
        for (int r = 0; r < resolvers; r++)
        {
            resolverTokens.add(token());
            perResolver.add(new ArrayList<>());
        }
        final List<IToken<?>> all = new ArrayList<>();
        for (int i = 0; i < requests; i++)
        {
            final IToken<?> request = token();
            all.add(request);
            perResolver.get(i % resolvers).add(request);
        }
        final StandardRequestResolverRequestAssignmentDataStore store = loaded(resolverTokens, perResolver);

        long lookups = 0;
        final long start = System.nanoTime();
        long elapsed;
        do
        {
            for (final IToken<?> request : all)
            {
                assertNotNull(store.getAssignmentForValue(request));
            }
            lookups += all.size();
            elapsed = System.nanoTime() - start;
        }
        while (elapsed < 20_000_000L);
        return (double) elapsed / lookups;
    }
}
