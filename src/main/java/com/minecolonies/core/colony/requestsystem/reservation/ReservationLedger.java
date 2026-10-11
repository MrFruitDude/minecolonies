package com.minecolonies.core.colony.requestsystem.reservation;

import com.minecolonies.api.colony.requestsystem.factory.IFactoryController;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * RS1: the colony's reservation ledger, after the reserve-then-commit transaction of Anno's storage nodes.
 * <p>
 * A warehouse that promises stock to a request {@link #reserveStock reserves} it at the source, a delivery
 * {@link #reserveSpace reserves} the room at its destination. {@code available = physical - reserved}, so a second
 * request is never promised what the first one holds. A courier {@link #commit commits} the claim when the items leave
 * the source (stock) or arrive (space); {@link #release release} ends it when the request is cancelled, fails or
 * completes. Every write is tagged with the requester and the reason and kept in a bounded history.
 * <p>
 * Self-healing: {@link #sweep} drops claims whose request token is gone, and {@link #available} never goes below zero
 * when the physical stock fell under the reserved amount (somebody took it outside the request system).
 * <p>
 * Server thread only.
 */
public final class ReservationLedger
{
    /**
     * Events kept in the history.
     */
    public static final int HISTORY_SIZE = 256;

    private static final String NBT_RESERVATIONS = "Reservations";
    private static final String NBT_KIND         = "Kind";
    private static final String NBT_SCOPE         = "Scope";
    private static final String NBT_CONTAINER     = "Container";
    private static final String NBT_STACK        = "Stack";
    private static final String NBT_AMOUNT       = "Amount";
    private static final String NBT_TOKEN        = "Token";
    private static final String NBT_REQUESTER    = "Requester";
    private static final String NBT_REASON       = "Reason";
    private static final String NBT_TICK         = "Tick";

    /**
     * All open claims by request token.
     */
    private final Map<IToken<?>, List<Reservation>> byToken = new LinkedHashMap<>();

    /**
     * Open claims by building, per kind.
     */
    private final Map<BlockPos, List<Reservation>> stockByScope = new HashMap<>();
    private final Map<BlockPos, List<Reservation>> spaceByScope = new HashMap<>();

    private final ArrayDeque<LedgerEvent> history = new ArrayDeque<>();

    /**
     * Told about every claim that ended without a physical change: that stock is available again (stock), that room is
     * free again (space).
     */
    @Nullable
    private Consumer<Reservation> onFreed;

    /**
     * Bumped on every change, so a cached answer can tell that it is stale.
     */
    private long revision;

    public void setOnFreed(@Nullable final Consumer<Reservation> onFreed)
    {
        this.onFreed = onFreed;
    }

    public long revision()
    {
        return revision;
    }

    // ------------------------------------------------------------------ writes

    /**
     * Reserves stock at a source for a request.
     *
     * @param scope     the building holding the stock (the warehouse).
     * @param container the rack the stock sits in, or null.
     * @param key       the item.
     * @param amount    how many.
     * @param token     the request token the claim belongs to.
     * @param requester who asks, for the insight views.
     * @param reason    why.
     * @param tick      the game time.
     * @return the amount reserved.
     */
    public int reserveStock(
      @NotNull final BlockPos scope,
      @Nullable final BlockPos container,
      @NotNull final ItemStorage key,
      final int amount,
      @NotNull final IToken<?> token,
      @NotNull final String requester,
      @NotNull final ReservationReason reason,
      final long tick)
    {
        return add(ReservationKind.STOCK, scope, container, key, amount, token, requester, reason, tick);
    }

    /**
     * Reserves room at a destination for a request.
     *
     * @param destination the building that will receive the items.
     * @param key         the item.
     * @param amount      how many.
     * @param token       the request token the claim belongs to.
     * @param requester   who asks.
     * @param reason      why.
     * @param tick        the game time.
     * @return the amount reserved.
     */
    public int reserveSpace(
      @NotNull final BlockPos destination,
      @NotNull final ItemStorage key,
      final int amount,
      @NotNull final IToken<?> token,
      @NotNull final String requester,
      @NotNull final ReservationReason reason,
      final long tick)
    {
        return add(ReservationKind.SPACE, destination, null, key, amount, token, requester, reason, tick);
    }

    private int add(
      final ReservationKind kind,
      final BlockPos scope,
      @Nullable final BlockPos container,
      final ItemStorage key,
      final int amount,
      final IToken<?> token,
      final String requester,
      final ReservationReason reason,
      final long tick)
    {
        if (amount <= 0)
        {
            return 0;
        }
        final ItemStorage stored = normalized(key);
        final List<Reservation> mine = byToken.computeIfAbsent(token, t -> new ArrayList<>(2));
        for (final Reservation existing : mine)
        {
            if (existing.kind() == kind && existing.scope().equals(scope) && Objects.equals(existing.container(), container) && existing.key().equals(stored))
            {
                existing.setAmount(existing.amount() + amount);
                record(tick, LedgerEvent.Op.RESERVE, existing, amount);
                revision++;
                return amount;
            }
        }
        final Reservation reservation = new Reservation(kind, scope, container, stored, amount, token, requester, reason, tick);
        mine.add(reservation);
        (kind == ReservationKind.STOCK ? stockByScope : spaceByScope).computeIfAbsent(scope, s -> new ArrayList<>(2)).add(reservation);
        record(tick, LedgerEvent.Op.RESERVE, reservation, amount);
        revision++;
        return amount;
    }

    /**
     * The items left the source (stock) or arrived at the destination (space): the claims of that kind for the token
     * become a physical change and are dropped. Not reported as freed, nothing became available.
     *
     * @param token the request token.
     * @param kind  the kind to commit.
     * @param tick  the game time.
     * @return the amount committed.
     */
    public int commit(@NotNull final IToken<?> token, @NotNull final ReservationKind kind, final long tick)
    {
        return end(token, kind, tick, LedgerEvent.Op.COMMIT, false, r -> true);
    }

    /**
     * Ends every claim of a token without a physical change.
     *
     * @param token the request token.
     * @param tick  the game time.
     * @return the amount released.
     */
    public int release(@NotNull final IToken<?> token, final long tick)
    {
        return end(token, null, tick, LedgerEvent.Op.RELEASE, true, r -> true);
    }

    /**
     * Forgets every claim (the request system was reset: no request survives).
     */
    public void clear()
    {
        byToken.clear();
        stockByScope.clear();
        spaceByScope.clear();
        history.clear();
        revision++;
    }

    /**
     * Ends every claim of a token without telling anyone that stock became available: the claims are being replaced by
     * others for the same items (a request's hold turning into the holds of its deliveries).
     *
     * @param token the request token.
     * @param tick  the game time.
     * @return the amount released.
     */
    public int releaseQuiet(@NotNull final IToken<?> token, final long tick)
    {
        return end(token, null, tick, LedgerEvent.Op.RELEASE, false, r -> true);
    }

    /**
     * Ends the claims of a token that a request ending in a given way no longer needs: a delivery's claims end when
     * the request completes, but items handed out to a requester stay held until the requester received them.
     *
     * @param token           the request token.
     * @param tick            the game time.
     * @param includeHandouts whether the claims on items handed out to the requester end too.
     * @return the amount released.
     */
    public int release(@NotNull final IToken<?> token, final long tick, final boolean includeHandouts)
    {
        return end(token, null, tick, LedgerEvent.Op.RELEASE, true, r -> includeHandouts || r.reason() != ReservationReason.BUILDING_HANDOUT);
    }

    private int end(
      final IToken<?> token,
      @Nullable final ReservationKind kind,
      final long tick,
      final LedgerEvent.Op op,
      final boolean freed,
      final Predicate<Reservation> which)
    {
        final List<Reservation> mine = byToken.get(token);
        if (mine == null)
        {
            return 0;
        }
        int total = 0;
        final List<Reservation> ended = new ArrayList<>(mine.size());
        for (final Iterator<Reservation> it = mine.iterator(); it.hasNext(); )
        {
            final Reservation reservation = it.next();
            if ((kind == null || reservation.kind() == kind) && which.test(reservation))
            {
                it.remove();
                unindex(reservation);
                total += reservation.amount();
                ended.add(reservation);
            }
        }
        if (mine.isEmpty())
        {
            byToken.remove(token);
        }
        for (final Reservation reservation : ended)
        {
            record(tick, op, reservation, reservation.amount());
            if (freed && onFreed != null)
            {
                onFreed.accept(reservation);
            }
        }
        if (!ended.isEmpty())
        {
            revision++;
        }
        return total;
    }

    private void unindex(final Reservation reservation)
    {
        final Map<BlockPos, List<Reservation>> index = reservation.kind() == ReservationKind.STOCK ? stockByScope : spaceByScope;
        final List<Reservation> list = index.get(reservation.scope());
        if (list != null)
        {
            list.remove(reservation);
            if (list.isEmpty())
            {
                index.remove(reservation.scope());
            }
        }
    }

    /**
     * Self-healing: drops the claims of tokens that no longer have a live request behind them.
     *
     * @param alive whether a request token is still live.
     * @param tick  the game time.
     * @return the number of claims dropped.
     */
    public int sweep(@NotNull final Predicate<IToken<?>> alive, final long tick)
    {
        int dropped = 0;
        for (final IToken<?> token : new ArrayList<>(byToken.keySet()))
        {
            if (!alive.test(token))
            {
                final List<Reservation> mine = byToken.get(token);
                dropped += mine == null ? 0 : mine.size();
                end(token, null, tick, LedgerEvent.Op.ORPHAN, true, r -> true);
            }
        }
        return dropped;
    }

    /**
     * Self-healing: drops claims on items handed out to a requester that never took them, after {@code maxAge} ticks.
     *
     * @param tick   the game time.
     * @param maxAge the age in ticks after which such a claim is dropped.
     * @return the number of claims dropped.
     */
    public int sweepStaleHandouts(final long tick, final long maxAge)
    {
        int dropped = 0;
        for (final IToken<?> token : new ArrayList<>(byToken.keySet()))
        {
            final List<Reservation> mine = byToken.get(token);
            if (mine == null)
            {
                continue;
            }
            for (final Reservation reservation : mine)
            {
                if (reservation.reason() == ReservationReason.BUILDING_HANDOUT && tick - reservation.tick() > maxAge)
                {
                    dropped += end(token, null, tick, LedgerEvent.Op.ORPHAN, true, r -> r.reason() == ReservationReason.BUILDING_HANDOUT && tick - r.tick() > maxAge) > 0 ? 1 : 0;
                    break;
                }
            }
        }
        return dropped;
    }

    // ------------------------------------------------------------------ reads

    /**
     * @param scope the building.
     * @param key   the item.
     * @return the stock reserved at the building for exactly this item.
     */
    public int reservedStock(@NotNull final BlockPos scope, @NotNull final ItemStorage key)
    {
        return reservedStock(scope, stack -> key.equals(new ItemStorage(stack)));
    }

    /**
     * @param scope     the building.
     * @param predicate which items.
     * @return the stock reserved at the building for items matching the predicate.
     */
    public int reservedStock(@NotNull final BlockPos scope, @NotNull final Predicate<ItemStack> predicate)
    {
        return sum(stockByScope.get(scope), null, predicate);
    }

    /**
     * @param scope     the building.
     * @param predicate which items.
     * @param excluded  a request token whose own claims do not count.
     * @return the stock reserved at the building for items matching the predicate by anyone but the excluded token.
     */
    public int reservedStockExcluding(@NotNull final BlockPos scope, @NotNull final Predicate<ItemStack> predicate, @NotNull final IToken<?> excluded)
    {
        final List<Reservation> list = stockByScope.get(scope);
        if (list == null)
        {
            return 0;
        }
        int total = 0;
        for (final Reservation reservation : list)
        {
            if (!excluded.equals(reservation.token()) && predicate.test(reservation.key().getItemStack()))
            {
                total += reservation.amount();
            }
        }
        return total;
    }

    /**
     * @param container the rack.
     * @param key       the item.
     * @return the stock reserved in exactly this rack.
     */
    public int reservedInContainer(@NotNull final BlockPos container, @NotNull final ItemStorage key)
    {
        int total = 0;
        for (final List<Reservation> list : stockByScope.values())
        {
            total += sum(list, container, stack -> key.equals(new ItemStorage(stack)));
        }
        return total;
    }

    /**
     * @param destination the building.
     * @param predicate   which items.
     * @return the room reserved at the destination for items matching the predicate.
     */
    public int reservedSpace(@NotNull final BlockPos destination, @NotNull final Predicate<ItemStack> predicate)
    {
        return sum(spaceByScope.get(destination), null, predicate);
    }

    private static int sum(@Nullable final List<Reservation> list, @Nullable final BlockPos container, final Predicate<ItemStack> predicate)
    {
        if (list == null)
        {
            return 0;
        }
        int total = 0;
        for (final Reservation reservation : list)
        {
            if ((container == null || container.equals(reservation.container())) && predicate.test(reservation.key().getItemStack()))
            {
                total += reservation.amount();
            }
        }
        return total;
    }

    /**
     * {@code available = physical - reserved}, never below zero.
     *
     * @param scope    the building.
     * @param key      the item.
     * @param physical how many are physically there.
     * @return the amount nobody holds.
     */
    public int available(@NotNull final BlockPos scope, @NotNull final ItemStorage key, final int physical)
    {
        return Math.max(0, physical - reservedStock(scope, key));
    }

    /**
     * @param token a request token.
     * @return the open claims of the token.
     */
    @NotNull
    public List<Reservation> reservationsOf(@NotNull final IToken<?> token)
    {
        final List<Reservation> mine = byToken.get(token);
        return mine == null ? List.of() : List.copyOf(mine);
    }

    /**
     * @return every open claim.
     */
    @NotNull
    public List<Reservation> all()
    {
        final List<Reservation> all = new ArrayList<>();
        for (final List<Reservation> list : byToken.values())
        {
            all.addAll(list);
        }
        return all;
    }

    /**
     * @return the number of request tokens holding claims.
     */
    public int tokenCount()
    {
        return byToken.size();
    }

    public boolean isEmpty()
    {
        return byToken.isEmpty();
    }

    /**
     * "Who holds / took X": the open claims on items matching the predicate.
     *
     * @param predicate which items.
     * @return the claims.
     */
    @NotNull
    public List<Reservation> whoHolds(@NotNull final Predicate<ItemStack> predicate)
    {
        final List<Reservation> found = new ArrayList<>();
        for (final List<Reservation> list : byToken.values())
        {
            for (final Reservation reservation : list)
            {
                if (predicate.test(reservation.key().getItemStack()))
                {
                    found.add(reservation);
                }
            }
        }
        return found;
    }

    /**
     * The recent writes on items matching the predicate, oldest first.
     *
     * @param predicate which items.
     * @return the events.
     */
    @NotNull
    public List<LedgerEvent> history(@NotNull final Predicate<ItemStack> predicate)
    {
        final List<LedgerEvent> found = new ArrayList<>();
        for (final LedgerEvent event : history)
        {
            if (predicate.test(event.key().getItemStack()))
            {
                found.add(event);
            }
        }
        return found;
    }

    private void record(final long tick, final LedgerEvent.Op op, final Reservation reservation, final int amount)
    {
        if (history.size() >= HISTORY_SIZE)
        {
            history.pollFirst();
        }
        history.addLast(new LedgerEvent(tick, op, reservation.kind(), reservation.token(), reservation.requester(), reservation.reason(), reservation.scope(), reservation.key(), amount));
    }

    /**
     * One storage per item: the key without amount, as the racks key their content.
     */
    private static ItemStorage normalized(final ItemStorage key)
    {
        return new ItemStorage(key.getItemStack().copyWithCount(1));
    }

    // ------------------------------------------------------------------ persistence

    @NotNull
    public CompoundTag write(@NotNull final HolderLookup.Provider provider, @NotNull final IFactoryController controller)
    {
        final CompoundTag tag = new CompoundTag();
        final ListTag list = new ListTag();
        for (final Reservation reservation : all())
        {
            final CompoundTag entry = new CompoundTag();
            entry.putString(NBT_KIND, reservation.kind().name());
            entry.putLong(NBT_SCOPE, reservation.scope().asLong());
            if (reservation.container() != null)
            {
                entry.putLong(NBT_CONTAINER, reservation.container().asLong());
            }
            entry.put(NBT_STACK, ItemStackUtils.serializeOptional(reservation.key().getItemStack(), provider));
            entry.putInt(NBT_AMOUNT, reservation.amount());
            entry.put(NBT_TOKEN, controller.serializeTag(provider, reservation.token()));
            entry.putString(NBT_REQUESTER, reservation.requester());
            entry.putString(NBT_REASON, reservation.reason().name());
            entry.putLong(NBT_TICK, reservation.tick());
            list.add(entry);
        }
        tag.put(NBT_RESERVATIONS, list);
        return tag;
    }

    /**
     * Replaces the content with what {@link #write} wrote. A claim that cannot be read is dropped (and logged): the
     * ledger heals, it must never stop a colony from loading.
     */
    public void read(@NotNull final HolderLookup.Provider provider, @NotNull final IFactoryController controller, @NotNull final CompoundTag tag)
    {
        byToken.clear();
        stockByScope.clear();
        spaceByScope.clear();
        history.clear();
        revision++;
        final ListTag list = tag.getListOrEmpty(NBT_RESERVATIONS);
        for (int i = 0; i < list.size(); i++)
        {
            try
            {
                final CompoundTag entry = list.getCompoundOrEmpty(i);
                final ReservationKind kind = ReservationKind.valueOf(entry.getStringOr(NBT_KIND, ReservationKind.STOCK.name()));
                final BlockPos scope = BlockPos.of(entry.getLongOr(NBT_SCOPE, 0L));
                final BlockPos container = entry.contains(NBT_CONTAINER) ? BlockPos.of(entry.getLongOr(NBT_CONTAINER, 0L)) : null;
                final ItemStack stack = ItemStackUtils.parseOptional(provider, entry.getCompoundOrEmpty(NBT_STACK));
                final IToken<?> token = controller.deserializeTag(provider, entry.getCompoundOrEmpty(NBT_TOKEN));
                if (stack.isEmpty() || token == null)
                {
                    continue;
                }
                add(kind, scope, container, new ItemStorage(stack), entry.getIntOr(NBT_AMOUNT, 0), token, entry.getStringOr(NBT_REQUESTER, ""),
                  ReservationReason.valueOf(entry.getStringOr(NBT_REASON, ReservationReason.OTHER.name())), entry.getLongOr(NBT_TICK, 0L));
            }
            catch (final RuntimeException e)
            {
                Log.getLogger().warn("Dropped an unreadable reservation", e);
            }
        }
        history.clear();
    }
}
