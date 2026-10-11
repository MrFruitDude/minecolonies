package com.minecolonies.core.gametest.bench;

import com.minecolonies.api.colony.requestsystem.StandardFactoryController;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.core.colony.requestsystem.management.manager.StandardRequestManager;
import com.minecolonies.core.colony.requestsystem.reservation.Reservation;
import net.minecraft.core.HolderLookup;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * An order-independent picture of everything the request system holds: every request (as the hash of its saved form),
 * every resolver, every request-to-resolver and provider-to-resolver assignment, every ledger reservation. Two managers
 * with equal digests are the same state; used for the save/load identity gate.
 */
public final class RsDigest
{
    public final Map<String, Integer>       requests     = new TreeMap<>();
    public final TreeSet<String>            resolvers    = new TreeSet<>();
    public final TreeSet<String>            assignments  = new TreeSet<>();
    public final TreeSet<String>            providers    = new TreeSet<>();
    public final TreeSet<String>            reservations = new TreeSet<>();

    private RsDigest()
    {
    }

    public static RsDigest of(final StandardRequestManager manager, final HolderLookup.Provider provider)
    {
        final RsDigest digest = new RsDigest();
        final StandardFactoryController controller = StandardFactoryController.getInstance();
        for (final Map.Entry<IToken<?>, IRequest<?>> e : manager.getRequestIdentitiesDataStore().getIdentities().entrySet())
        {
            digest.requests.put(String.valueOf(e.getKey().getIdentifier()), controller.serializeTag(provider, e.getValue()).hashCode());
        }
        for (final IToken<?> resolver : manager.getRequestResolverIdentitiesDataStore().getIdentities().keySet())
        {
            digest.resolvers.add(String.valueOf(resolver.getIdentifier()));
        }
        for (final Map.Entry<IToken<?>, Collection<IToken<?>>> e : manager.getRequestResolverRequestAssignmentDataStore().getAssignments().entrySet())
        {
            for (final IToken<?> request : e.getValue())
            {
                digest.assignments.add(e.getKey().getIdentifier() + ">" + request.getIdentifier());
            }
        }
        for (final Map.Entry<IToken<?>, Collection<IToken<?>>> e : manager.getProviderResolverAssignmentDataStore().getAssignments().entrySet())
        {
            for (final IToken<?> resolver : e.getValue())
            {
                digest.providers.add(e.getKey().getIdentifier() + ">" + resolver.getIdentifier());
            }
        }
        for (final Reservation r : manager.getReservationLedger().all())
        {
            digest.reservations.add(r.kind() + "|" + r.scope() + "|" + r.container() + "|" + r.key().getItem() + "|" + r.amount() + "|" + r.token().getIdentifier() + "|" + r.reason());
        }
        return digest;
    }

    /**
     * @return null when equal, else a short description of the first differences.
     */
    public String diff(final RsDigest other)
    {
        final StringBuilder out = new StringBuilder();
        if (!requests.equals(other.requests))
        {
            int shown = 0;
            for (final Map.Entry<String, Integer> e : requests.entrySet())
            {
                if (!e.getValue().equals(other.requests.get(e.getKey())) && shown++ < 3)
                {
                    out.append("request ").append(e.getKey()).append(other.requests.containsKey(e.getKey()) ? " differs; " : " missing after load; ");
                }
            }
            out.append("requests ").append(requests.size()).append(" vs ").append(other.requests.size()).append("; ");
        }
        part(out, "resolvers", resolvers, other.resolvers);
        part(out, "assignments", assignments, other.assignments);
        part(out, "providers", providers, other.providers);
        part(out, "reservations", reservations, other.reservations);
        return out.isEmpty() ? null : out.toString();
    }

    private static void part(final StringBuilder out, final String what, final TreeSet<String> a, final TreeSet<String> b)
    {
        if (a.equals(b))
        {
            return;
        }
        final TreeSet<String> onlyA = new TreeSet<>(a);
        onlyA.removeAll(b);
        final TreeSet<String> onlyB = new TreeSet<>(b);
        onlyB.removeAll(a);
        out.append(what).append(": ").append(onlyA.size()).append(" only before").append(onlyA.isEmpty() ? "" : " (e.g. " + onlyA.first() + ")")
          .append(", ").append(onlyB.size()).append(" only after").append(onlyB.isEmpty() ? "" : " (e.g. " + onlyB.first() + ")").append("; ");
    }
}
