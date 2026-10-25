package com.minecolonies.core.colony.requestsystem.management;

import com.google.common.reflect.TypeToken;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.manager.RequestMappingHandler;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.IRequestable;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.requester.IRequester;
import com.minecolonies.api.colony.requestsystem.resolver.IRequestResolver;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.util.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static com.minecolonies.api.util.constant.Suppression.RAWTYPES;
import static com.minecolonies.api.util.constant.Suppression.UNCHECKED;

/**
 * "Which resolver would take a request for X?" (B-6, for an add-on's chain view): the request system's own assignment order
 * ({@code RequestHandler#assignRequestDefault}: every resolver of the request's types, highest priority first, then the
 * request type order) run as a dry run. A request for the item is built but never registered, only
 * {@code canResolveRequest} and {@code getSuitabilityMetric} are asked, nothing is attempted, so no child request,
 * reservation or state change happens.
 * <p>
 * The answer is what the request system would try first: the real assignment also attempts the resolver, and an attempt
 * that fails moves on to the next candidate. Server thread only.
 */
public final class RequestTargets
{
    /**
     * A resolver that could take the request.
     *
     * @param resolver the resolver.
     * @param name     its display name for this request (the building's name for a building's resolver).
     * @param building the position of the colony building the resolver belongs to, null when it is not a building's (player, retrying).
     * @param priority the resolver's priority (higher is asked first).
     * @param metric   the suitability metric within the first priority (lower wins), {@link Integer#MAX_VALUE} for later priorities.
     */
    public record Candidate(IRequestResolver<?> resolver, Component name, @Nullable BlockPos building, int priority, int metric)
    {
    }

    private RequestTargets()
    {
    }

    /**
     * The resolvers that could take a request for an item from a building, in the order the request system tries them: the
     * first one is the one it would choose.
     *
     * @param colony    the colony.
     * @param requester the building that would ask.
     * @param stack     the item.
     * @param count     how many.
     * @return the candidates, the chosen one first; empty when none can resolve it (or the colony has no standard request manager).
     */
    @NotNull
    public static List<Candidate> forItem(@NotNull final IColony colony, @NotNull final IBuilding requester, @NotNull final ItemStack stack, final int count)
    {
        if (stack.isEmpty() || count <= 0)
        {
            return List.of();
        }
        final ItemStack one = stack.copyWithCount(1);
        return forRequestable(colony, requester.getRequester(), new Stack(one, count, 1));
    }

    /**
     * The same for any requestable (a tool, a tag, food...).
     *
     * @param colony      the colony.
     * @param requester   who would ask.
     * @param requestable what would be asked for.
     * @return the candidates, the chosen one first.
     */
    @NotNull
    @SuppressWarnings({RAWTYPES, UNCHECKED})
    public static List<Candidate> forRequestable(@NotNull final IColony colony, @NotNull final IRequester requester, @NotNull final IRequestable requestable)
    {
        if (!(colony.getRequestManager() instanceof final IStandardRequestManager manager))
        {
            return List.of();
        }
        final Class<?> requestClass = RequestMappingHandler.getRequestableMappings().get(requestable.getClass());
        if (requestClass == null)
        {
            return List.of();
        }
        final IToken<?> token = manager.getTokenHandler().generateNewToken();
        final IRequest<?> request = (IRequest<?>) manager.getFactoryController().getNewInstance(TypeToken.of((Class) requestClass), requestable, token, requester);

        final List<TypeToken<?>> typeOrder = new ArrayList<>(request.getSuperClasses());
        final Map<IToken<?>, int[]> order = new LinkedHashMap<>();
        final Map<IToken<?>, IRequestResolver<?>> resolvers = new HashMap<>();
        for (final TypeToken<?> type : typeOrder)
        {
            final Collection<IToken<?>> tokens = manager.getRequestableTypeRequestResolverAssignmentDataStore().getAssignments().get(type);
            if (tokens == null)
            {
                continue;
            }
            for (final IToken<?> resolverToken : tokens)
            {
                if (order.containsKey(resolverToken) || manager.getResolverHandler().isBeingRemoved(resolverToken))
                {
                    continue;
                }
                final IRequestResolver<?> resolver = manager.getResolverHandler().getResolver(resolverToken);
                if (typeOrder.contains(resolver.getRequestType()))
                {
                    order.put(resolverToken, new int[] {resolver.getPriority(), typeOrder.indexOf(resolver.getRequestType())});
                    resolvers.put(resolverToken, resolver);
                }
            }
        }
        final List<IToken<?>> sorted = new ArrayList<>(order.keySet());
        sorted.sort(Comparator.comparingInt((IToken<?> t) -> -order.get(t)[0]).thenComparingInt(t -> order.get(t)[1]));

        final List<Candidate> found = new ArrayList<>();
        Integer firstPriority = null;
        for (final IToken<?> resolverToken : sorted)
        {
            final IRequestResolver resolver = resolvers.get(resolverToken);
            final boolean can;
            try
            {
                can = resolver.canResolveRequest(manager, request);
            }
            catch (final RuntimeException e)
            {
                Log.getLogger().debug("RequestTargets: " + resolver.getClass().getSimpleName() + " failed on a dry-run request", e);
                continue;
            }
            if (!can)
            {
                continue;
            }
            if (firstPriority == null)
            {
                firstPriority = resolver.getPriority();
            }
            int metric = Integer.MAX_VALUE;
            if (resolver.getPriority() == firstPriority)
            {
                try
                {
                    metric = resolver.getSuitabilityMetric(manager, request);
                }
                catch (final RuntimeException e)
                {
                    Log.getLogger().debug("RequestTargets: no metric from " + resolver.getClass().getSimpleName(), e);
                }
            }
            final BlockPos pos = resolver.getLocation() == null ? null : resolver.getLocation().getInDimensionLocation();
            final BlockPos building = pos != null && colony.getServerBuildingManager().getBuilding(pos) != null ? pos : null;
            Component name;
            try
            {
                name = resolver.getRequesterDisplayName(manager, request);
            }
            catch (final RuntimeException e)
            {
                name = Component.literal(resolver.getClass().getSimpleName());
            }
            found.add(new Candidate(resolver, name, building, resolver.getPriority(), metric));
        }
        // within the first priority the lowest metric wins (a stable sort keeps the search order on ties), later priorities follow in search order
        final Integer top = firstPriority;
        found.sort(Comparator.comparingInt((Candidate c) -> top != null && c.priority() == top ? 0 : 1).thenComparingInt(c -> top != null && c.priority() == top ? c.metric() : 0));
        return found;
    }

    /**
     * @param colony    the colony.
     * @param requester the building that would ask.
     * @param stack     the item.
     * @return the building whose resolver would take a request for one of the item, null when none or the chosen resolver is not a building's.
     */
    @Nullable
    public static BlockPos buildingFor(@NotNull final IColony colony, @NotNull final IBuilding requester, @NotNull final ItemStack stack)
    {
        final List<Candidate> candidates = forItem(colony, requester, stack, 1);
        return candidates.isEmpty() ? null : candidates.get(0).building();
    }
}
