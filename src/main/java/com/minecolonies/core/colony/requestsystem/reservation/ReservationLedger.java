package com.minecolonies.core.colony.requestsystem.reservation;

import com.minecolonies.core.colony.requestsystem.RsProbe;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.factory.IFactoryController;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.core.colony.requestsystem.RsAccess;
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
    private static final String NBT_FOR          = "For";

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

    /**
     * @param colony a server colony.
     * @return its reservation ledger, null when reservations are off ({@code RsFlags.reservations()}: couriers and warehouses ignore the ledger then) or the
     * colony's request manager has none (a view, or not loaded yet). The same ledger the warehouses read.
     */
    @Nullable
    public static ReservationLedger of(@NotNull final IColony colony)
    {
        return RsAccess.ledger(colony.getRequestManager());
    }

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
        return add(ReservationKind.STOCK, scope, container, key, amount, token, requester, reason, tick, null);
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
        return add(ReservationKind.SPACE, destination, null, key, amount, token, requester, reason, tick, null);
    }

    /**
     * Sets a token's stock claim on one item at one source to exactly {@code amount}: grows it as {@link #reserveStock} does, shrinks it (the shrunk part is
     * reported as freed), or ends it at 0. For holders that recompute what they still need, like an add-on's make orders and builds
     * ({@link ReservationReason#MAKE}, {@link ReservationReason#BUILD}). The claim holds the stock for nobody in particular: every request is kept off it.
     *
     * @param scope     the building holding the stock (the warehouse).
     * @param key       the item.
     * @param amount    the amount the token should hold, 0 to end the claim.
     * @param token     the token the claim belongs to.
     * @param requester who asks, for the insight views.
     * @param reason    why.
     * @param tick      the game time.
     * @return the amount the token holds afterwards.
     */
    public int setStock(
      @NotNull final BlockPos scope,
      @NotNull final ItemStorage key,
      final int amount,
      @NotNull final IToken<?> token,
      @NotNull final String requester,
      @NotNull final ReservationReason reason,
      final long tick)
    {
        return setStock(scope, key, amount, token, requester, reason, tick, null);
    }

    /**
     * {@link #setStock(BlockPos, ItemStorage, int, IToken, String, ReservationReason, long)} for a beneficiary: the stock is held <em>for</em> a building (the
     * producer of a make order, the builder of a build). That building's own requests (and every request in a chain it started) are served from the held stock
     * as if it were free, and when a warehouse resolver reserves stock for such a request the claim hands that amount over to the delivery quietly
     * ({@link #handOver}), so the items stay held all the way. Other requests are kept off it. One claim per token, source, item, reason and beneficiary.
     * <p>
     * Recompute per (building, item) over all of the holder's tokens, never per token: {@link #claimForUse} does exactly that. The claim protects the building's
     * pool of the item: every request of that building draws on it, also its own work outside the holder (native orders), and every delivery of the item to the
     * building lowers it.
     *
     * @param forBuilding the building the stock is held for, null for nobody in particular.
     * @return the amount the token holds afterwards.
     */
    public int setStock(
      @NotNull final BlockPos scope,
      @NotNull final ItemStorage key,
      final int amount,
      @NotNull final IToken<?> token,
      @NotNull final String requester,
      @NotNull final ReservationReason reason,
      final long tick,
      @Nullable final BlockPos forBuilding)
    {
        final ItemStorage stored = normalized(key);
        final int want = Math.max(0, amount);
        final List<Reservation> mine = byToken.get(token);
        int held = 0;
        Reservation claim = null;
        if (mine != null)
        {
            for (final Reservation reservation : mine)
            {
                if (reservation.kind() == ReservationKind.STOCK && reservation.scope().equals(scope) && reservation.container() == null && reservation.key().equals(stored)
                      && reservation.reason() == reason && Objects.equals(reservation.forBuilding(), forBuilding))
                {
                    claim = reservation;
                    held = reservation.amount();
                    break;
                }
            }
        }
        if (want > held)
        {
            return held + add(ReservationKind.STOCK, scope, null, stored, want - held, token, requester, reason, tick, forBuilding);
        }
        if (claim != null && want < held)
        {
            shrink(claim, held - want, tick, true);
        }
        return want;
    }

    /**
     * One token's share of a building's need for an item, for {@link #recomputeFor}.
     *
     * @param token  the holder's token (one make order, one build).
     * @param amount how many of the item it still needs.
     */
    public record Need(@NotNull IToken<?> token, int amount)
    {
    }

    /**
     * What is on its way to a building for an item or handed out there: {@link #comingTo} plus the items handed out at the building and not taken yet
     * ({@link ReservationReason#BUILDING_HANDOUT}, physically in the hut already).
     *
     * @param manager  the request manager (to read the requests behind holds).
     * @param building the building.
     * @param item     the item.
     * @return the amount.
     */
    public int inFlightFor(@NotNull final IRequestManager manager, @NotNull final BlockPos building, @NotNull final ItemStorage item)
    {
        final ItemStorage stored = normalized(item);
        int total = comingTo(manager, building, item);
        final List<Reservation> there = stockByScope.get(building);
        if (there != null)
        {
            for (final Reservation r : there)
            {
                if (r.reason() == ReservationReason.BUILDING_HANDOUT && r.key().equals(stored))
                {
                    total += r.amount();
                }
            }
        }
        return total;
    }

    /**
     * What is on its way to a building for an item and not there yet: room reserved at the building (deliveries planned or carried) and stock held at a source
     * by the open hold of a request the building itself made (its deliveries will go there; a hold of a request further down its chain goes elsewhere).
     *
     * @param manager  the request manager (to read the requests behind holds).
     * @param building the building.
     * @param item     the item.
     * @return the amount.
     */
    public int comingTo(@NotNull final IRequestManager manager, @NotNull final BlockPos building, @NotNull final ItemStorage item)
    {
        final ItemStorage stored = normalized(item);
        int total = 0;
        final List<Reservation> room = spaceByScope.get(building);
        if (room != null)
        {
            for (final Reservation r : room)
            {
                if (r.key().equals(stored))
                {
                    total += r.amount();
                }
            }
        }
        for (final Map.Entry<IToken<?>, List<Reservation>> entry : byToken.entrySet())
        {
            int held = 0;
            boolean delivery = false;
            for (final Reservation r : entry.getValue())
            {
                if (r.kind() == ReservationKind.SPACE)
                {
                    // a delivery: its room is counted above
                    delivery = true;
                    break;
                }
                if (r.reason() == ReservationReason.WAREHOUSE_DELIVERY && !r.scope().equals(building) && r.key().equals(stored))
                {
                    held += r.amount();
                }
            }
            if (delivery || held == 0)
            {
                continue;
            }
            IRequest<?> request;
            try
            {
                request = manager.getRequestForToken(entry.getKey());
            }
            catch (final RuntimeException e)
            {
                request = null;
            }
            if (request != null && request.getRequester() != null && request.getRequester().getLocation() != null
                  && building.equals(request.getRequester().getLocation().getInDimensionLocation()))
            {
                total += held;
            }
        }
        return total;
    }

    /**
     * How many of an item are at a building: in the hut, in every rack or container registered to it (a copper chest too), and in the inventories of its assigned workers. Handed-out
     * items are among them (they sit in the hut).
     *
     * @param building the building.
     * @param item     the item, compared exactly.
     * @return the count.
     */
    public static int atBuilding(@NotNull final IBuilding building, @NotNull final ItemStorage item)
    {
        final ItemStorage stored = normalized(item);
        final java.util.function.Predicate<ItemStack> matches = stack -> !stack.isEmpty() && stored.equals(new ItemStorage(stack.copyWithCount(1)));
        int total = 0;
        final net.minecraft.world.level.Level world = building.getColony().getWorld();
        final Set<BlockPos> containers = new LinkedHashSet<>(building.getContainers());
        containers.add(building.getID());
        for (final BlockPos pos : containers)
        {
            // a rack, the hut, or (V-7) a copper chest in the building
            final com.ldtteam.structurize.api.compat.itemhandler.IItemHandler handler = world != null && com.minecolonies.api.util.WorldUtil.isBlockLoaded(world, pos)
              ? com.minecolonies.api.util.BuildingContainers.handler(world.getBlockEntity(pos)) : null;
            if (handler != null)
            {
                total += com.minecolonies.api.util.InventoryUtils.getItemCountInItemHandler(handler, matches);
            }
        }
        for (final com.minecolonies.api.colony.ICitizenData worker : building.getAllAssignedCitizen())
        {
            total += com.minecolonies.api.util.InventoryUtils.getItemCountInItemHandler(worker.getInventory(), matches);
        }
        return total;
    }

    /**
     * Sets a holder's MAKE or BUILD claims for one building and item at one source from what its commitments will still use. For each token pass how many of
     * the item that commitment will still take out of the building's stock before it is done; MineColonies subtracts what is at the building
     * ({@link #atBuilding}) and on its way ({@link #comingTo}): claimed in total = max(0, sum of the amounts - atBuilding - comingTo), given to the tokens in
     * their order (each up to its amount, the rest 0), each through {@link #setStock(BlockPos, ItemStorage, int, IToken, String, ReservationReason, long,
     * BlockPos) setStock for the building}. Hold one (building, item) at one source; to move it, call this for the old source with every amount 0 first.
     *
     * @param manager   the request manager.
     * @param scope     the source (warehouse).
     * @param building  the building the stock is held for.
     * @param key       the item.
     * @param stillToUse every token of the holder that uses the item at the building, in priority order, with what it will still use.
     * @param requester who asks, for the insight views.
     * @param reason    {@link ReservationReason#MAKE} or {@link ReservationReason#BUILD}.
     * @param tick      the game time.
     * @return the amount claimed in total afterwards.
     */
    public int claimForUse(
      @NotNull final IRequestManager manager,
      @NotNull final BlockPos scope,
      @NotNull final IBuilding building,
      @NotNull final ItemStorage key,
      @NotNull final List<Need> stillToUse,
      @NotNull final String requester,
      @NotNull final ReservationReason reason,
      final long tick)
    {
        return split(scope, building.getID(), key, stillToUse, atBuilding(building, key) + comingTo(manager, building.getID(), key), requester, reason, tick);
    }

    private int split(
      final BlockPos scope,
      final BlockPos building,
      final ItemStorage key,
      final List<Need> needs,
      final int covered,
      final String requester,
      final ReservationReason reason,
      final long tick)
    {
        int needed = 0;
        for (final Need need : needs)
        {
            needed += Math.max(0, need.amount());
        }
        int left = Math.max(0, needed - covered);
        int claimed = 0;
        for (final Need need : needs)
        {
            final int amount = Math.min(Math.max(0, need.amount()), left);
            claimed += setStock(scope, key, amount, need.token(), requester, reason, tick, building);
            left -= amount;
        }
        return claimed;
    }

    /**
     * The older form of {@link #claimForUse}, kept working: the needs are "still needed" amounts from which the caller has already taken what is at the building
     * outside the ledger; only {@link #inFlightFor} is subtracted here. Prefer {@link #claimForUse}.
     * <p>
     * Recomputes a holder's claims for one building and item at one source: claimed in total = max(0, sum of the needs - {@link #inFlightFor}), given to the
     * needs in their order (each up to its amount, the rest 0), each through {@link #setStock(BlockPos, ItemStorage, int, IToken, String, ReservationReason, long,
     * BlockPos) setStock for the building}. Hold one (building, item) at one source; to move it, set the old source's claims to 0 first.
     *
     * @param manager   the request manager.
     * @param scope     the source (warehouse).
     * @param building  the building the stock is held for.
     * @param key       the item.
     * @param needs     every token of the holder that needs the item for the building, in priority order.
     * @param requester who asks, for the insight views.
     * @param reason    {@link ReservationReason#MAKE} or {@link ReservationReason#BUILD}.
     * @param tick      the game time.
     * @return the amount claimed in total afterwards.
     */
    public int recomputeFor(
      @NotNull final IRequestManager manager,
      @NotNull final BlockPos scope,
      @NotNull final BlockPos building,
      @NotNull final ItemStorage key,
      @NotNull final List<Need> needs,
      @NotNull final String requester,
      @NotNull final ReservationReason reason,
      final long tick)
    {
        return split(scope, building, key, needs, inFlightFor(manager, building, key), requester, reason, tick);
    }

    /**
     * Shrinks one claim by {@code by} (ends it when nothing is left).
     */
    private void shrink(final Reservation claim, final int by, final long tick, final boolean freed)
    {
        if (by >= claim.amount())
        {
            end(claim.token(), claim.kind(), tick, LedgerEvent.Op.RELEASE, freed, r -> r == claim);
            return;
        }
        claim.setAmount(claim.amount() - by);
        record(tick, LedgerEvent.Op.RELEASE, claim, by);
        revision++;
        if (freed && onFreed != null)
        {
            onFreed.accept(claim);
        }
    }

    /**
     * A warehouse resolver reserved {@code amount} of an item at a source for a request of a beneficiary: its {@link ReservationReason#MAKE} and
     * {@link ReservationReason#BUILD} claims there shrink by that amount, without telling anyone that stock became free (the delivery holds it now).
     *
     * @param scope         the source.
     * @param key           the item.
     * @param amount        how many the delivery reserved.
     * @param beneficiaries the buildings of the request's chain ({@link #requesterChain}).
     * @param tick          the game time.
     * @return the amount handed over.
     */
    public int handOver(@NotNull final BlockPos scope, @NotNull final ItemStorage key, final int amount, @NotNull final Set<BlockPos> beneficiaries, final long tick)
    {
        final List<Reservation> list = stockByScope.get(scope);
        if (list == null || amount <= 0 || beneficiaries.isEmpty())
        {
            return 0;
        }
        final ItemStorage stored = normalized(key);
        int left = amount;
        for (final Reservation claim : new ArrayList<>(list))
        {
            if (left <= 0)
            {
                break;
            }
            if ((claim.reason() == ReservationReason.MAKE || claim.reason() == ReservationReason.BUILD) && claim.container() == null && claim.forBuilding() != null
                  && beneficiaries.contains(claim.forBuilding()) && claim.key().equals(stored))
            {
                final int used = Math.min(left, claim.amount());
                shrink(claim, used, tick, false);
                left -= used;
            }
        }
        return amount - left;
    }

    /**
     * @param manager the request manager.
     * @param request a request.
     * @return the requester buildings of the request and of every parent up the chain: the beneficiaries whose held stock serves it.
     */
    @NotNull
    public static Set<BlockPos> requesterChain(@NotNull final IRequestManager manager, @NotNull final IRequest<?> request)
    {
        final Set<BlockPos> chain = new HashSet<>(2);
        IRequest<?> at = request;
        for (int depth = 0; at != null && depth < 32; depth++)
        {
            if (at.getRequester() != null && at.getRequester().getLocation() != null)
            {
                chain.add(at.getRequester().getLocation().getInDimensionLocation());
            }
            if (!at.hasParent())
            {
                break;
            }
            try
            {
                at = manager.getRequestForToken(at.getParent());
            }
            catch (final RuntimeException e)
            {
                break;
            }
        }
        return chain;
    }

    /**
     * @param chain the requester chain of a request ({@link #requesterChain}).
     * @return whether a claim keeps stock from that request: every claim except one held for a building of the chain.
     */
    @NotNull
    public static Predicate<Reservation> countsAgainst(@NotNull final Set<BlockPos> chain)
    {
        return chain.isEmpty() ? r -> true : r -> r.forBuilding() == null || !chain.contains(r.forBuilding());
    }

    /**
     * @param scope  the source.
     * @param key    the item.
     * @param counts which claims count ({@link #countsAgainst}).
     * @return the amount of the source-wide (rack-less) {@link ReservationReason#MAKE} and {@link ReservationReason#BUILD} claims on the item that count.
     */
    public int heldForOthers(@NotNull final BlockPos scope, @NotNull final ItemStorage key, @NotNull final Predicate<Reservation> counts)
    {
        final List<Reservation> list = stockByScope.get(scope);
        if (list == null)
        {
            return 0;
        }
        final ItemStorage stored = normalized(key);
        int total = 0;
        for (final Reservation claim : list)
        {
            if ((claim.reason() == ReservationReason.MAKE || claim.reason() == ReservationReason.BUILD) && claim.container() == null && claim.key().equals(stored)
                  && counts.test(claim))
            {
                total += claim.amount();
            }
        }
        return total;
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
      final long tick,
      @Nullable final BlockPos beneficiary)
    {
        if (!RsProbe.enabled)
        {
            return add0(kind, scope, container, key, amount, token, requester, reason, tick, beneficiary);
        }
        RsProbe.begin(RsProbe.LEDGER);
        try
        {
            return add0(kind, scope, container, key, amount, token, requester, reason, tick, beneficiary);
        }
        finally
        {
            RsProbe.end(RsProbe.LEDGER);
        }
    }

    private int add0(
      final ReservationKind kind,
      final BlockPos scope,
      @Nullable final BlockPos container,
      final ItemStorage key,
      final int amount,
      final IToken<?> token,
      final String requester,
      final ReservationReason reason,
      final long tick,
      @Nullable final BlockPos beneficiary)
    {
        if (amount <= 0)
        {
            return 0;
        }
        final ItemStorage stored = normalized(key);
        final List<Reservation> mine = byToken.computeIfAbsent(token, t -> new ArrayList<>(2));
        for (final Reservation existing : mine)
        {
            // one claim per slot, reason and beneficiary: a token that claims the same item for two reasons keeps two claims
            if (existing.kind() == kind && existing.scope().equals(scope) && Objects.equals(existing.container(), container) && existing.key().equals(stored)
                  && existing.reason() == reason && Objects.equals(existing.forBuilding(), beneficiary))
            {
                existing.setAmount(existing.amount() + amount);
                record(tick, LedgerEvent.Op.RESERVE, existing, amount);
                revision++;
                return amount;
            }
        }
        final Reservation reservation = new Reservation(kind, scope, container, stored, amount, token, requester, reason, tick, beneficiary);
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
     * Part of the stock claimed by a token left the source: the claim shrinks by that amount (and is dropped when it is used up).
     * Not reported as freed, nothing became available.
     *
     * @param token  the request token.
     * @param scope  the building holding the stock.
     * @param key    the item.
     * @param amount how many items left.
     * @param tick   the game time.
     * @return the amount committed (less than asked when the token claimed less).
     */
    public int commitStock(@NotNull final IToken<?> token, @NotNull final BlockPos scope, @NotNull final ItemStorage key, final int amount, final long tick)
    {
        if (!RsProbe.enabled)
        {
            return commitStock0(token, scope, key, amount, tick);
        }
        RsProbe.begin(RsProbe.LEDGER);
        try
        {
            return commitStock0(token, scope, key, amount, tick);
        }
        finally
        {
            RsProbe.end(RsProbe.LEDGER);
        }
    }

    private int commitStock0(@NotNull final IToken<?> token, @NotNull final BlockPos scope, @NotNull final ItemStorage key, final int amount, final long tick)
    {
        final List<Reservation> mine = byToken.get(token);
        if (mine == null || amount <= 0)
        {
            return 0;
        }
        final ItemStorage stored = normalized(key);
        int left = amount;
        for (final Iterator<Reservation> it = mine.iterator(); it.hasNext() && left > 0; )
        {
            final Reservation reservation = it.next();
            if (reservation.kind() != ReservationKind.STOCK || !reservation.scope().equals(scope) || !reservation.key().equals(stored))
            {
                continue;
            }
            final int used = Math.min(left, reservation.amount());
            left -= used;
            record(tick, LedgerEvent.Op.COMMIT, reservation, used);
            if (used == reservation.amount())
            {
                it.remove();
                unindex(reservation);
            }
            else
            {
                reservation.setAmount(reservation.amount() - used);
            }
        }
        if (mine.isEmpty())
        {
            byToken.remove(token);
        }
        if (left != amount)
        {
            revision++;
        }
        return amount - left;
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
     * {@link #releaseQuiet(IToken, long)}, but the items already handed out to the requester stay held for it ({@link ReservationReason#BUILDING_HANDOUT}): a
     * request handed out again after a short load keeps what arrived of it (round 6 teamwork).
     *
     * @param token the request token.
     * @param tick  the game time.
     * @return the amount released.
     */
    public int releaseQuietKeepingHandouts(@NotNull final IToken<?> token, final long tick)
    {
        return end(token, null, tick, LedgerEvent.Op.RELEASE, false, r -> r.reason() != ReservationReason.BUILDING_HANDOUT);
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
        if (!RsProbe.enabled)
        {
            return end0(token, kind, tick, op, freed, which);
        }
        RsProbe.begin(RsProbe.LEDGER);
        try
        {
            return end0(token, kind, tick, op, freed, which);
        }
        finally
        {
            RsProbe.end(RsProbe.LEDGER);
        }
    }

    private int end0(
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
        if (!RsProbe.enabled)
        {
            return sweep0(alive, tick);
        }
        RsProbe.begin(RsProbe.LEDGER);
        try
        {
            return sweep0(alive, tick);
        }
        finally
        {
            RsProbe.end(RsProbe.LEDGER);
        }
    }

    private int sweep0(@NotNull final Predicate<IToken<?>> alive, final long tick)
    {
        int dropped = 0;
        for (final IToken<?> token : new ArrayList<>(byToken.keySet()))
        {
            if (!alive.test(token) && !isAssistOnly(byToken.get(token)))
            {
                final List<Reservation> mine = byToken.get(token);
                dropped += mine == null ? 0 : mine.size();
                end(token, null, tick, LedgerEvent.Op.ORPHAN, true, r -> true);
            }
        }
        return dropped;
    }

    /**
     * Claims of helping builders have no request behind them: they are not orphans of the request sweep, see {@link #sweepStaleAssists}.
     */
    private static boolean isAssistOnly(@Nullable final List<Reservation> claims)
    {
        if (claims == null || claims.isEmpty())
        {
            return false;
        }
        for (final Reservation reservation : claims)
        {
            if (!reservation.reason().withoutRequest())
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Self-healing: drops the claims of helping builders that were not renewed or ended for {@code maxAge} ticks (the helper
     * vanished, his colony part unloaded, the world was reloaded in the middle of it).
     *
     * @param tick   the game time.
     * @param maxAge the age in ticks after which such a claim is dropped.
     * @return the number of tokens whose claims were dropped.
     */
    public int sweepStaleAssists(final long tick, final long maxAge)
    {
        if (!RsProbe.enabled)
        {
            return sweepStaleAssists0(tick, maxAge);
        }
        RsProbe.begin(RsProbe.LEDGER);
        try
        {
            return sweepStaleAssists0(tick, maxAge);
        }
        finally
        {
            RsProbe.end(RsProbe.LEDGER);
        }
    }

    private int sweepStaleAssists0(final long tick, final long maxAge)
    {
        int dropped = 0;
        for (final IToken<?> token : new ArrayList<>(byToken.keySet()))
        {
            final List<Reservation> mine = byToken.get(token);
            if (mine == null)
            {
                continue;
            }
            boolean stale = false;
            for (final Reservation reservation : mine)
            {
                if (reservation.reason() == ReservationReason.BUILD_ASSIST && tick - reservation.tick() > maxAge)
                {
                    stale = true;
                    break;
                }
            }
            if (stale)
            {
                end(token, null, tick, LedgerEvent.Op.ORPHAN, true, r -> r.reason() == ReservationReason.BUILD_ASSIST && tick - r.tick() > maxAge);
                dropped++;
            }
        }
        return dropped;
    }

    /**
     * Self-healing: drops the labourers' claims whose token no claim of the colony's labour board holds any more (the labourer vanished, the colony part was
     * unloaded, the world was reloaded in the middle of it: the board is not saved).
     *
     * @param live whether a labour claim token is still held by a live claim.
     * @param tick the game time.
     * @param minAge claims younger than this are left alone (a claim is written before its board entry is visible to another thread of the tick).
     * @return the number of tokens whose claims were dropped.
     */
    public int sweepStaleLabour(@NotNull final Predicate<IToken<?>> live, final long tick, final long minAge)
    {
        return sweepStale(ReservationReason.LABOUR, live, tick, minAge);
    }

    /**
     * Self-healing for claims without a request behind them ({@link ReservationReason#withoutRequest()}): drops the claims of one reason whose token its holder
     * no longer knows. An add-on that holds {@link ReservationReason#MAKE} or {@link ReservationReason#BUILD} claims calls this with its own live tokens.
     *
     * @param reason the reason of the claims to look at.
     * @param live   whether a token is still held by its owner.
     * @param tick   the game time.
     * @param minAge claims younger than this are left alone.
     * @return the number of tokens whose claims were dropped.
     */
    public int sweepStale(@NotNull final ReservationReason reason, @NotNull final Predicate<IToken<?>> live, final long tick, final long minAge)
    {
        int dropped = 0;
        for (final IToken<?> token : new ArrayList<>(byToken.keySet()))
        {
            final List<Reservation> mine = byToken.get(token);
            if (mine == null || live.test(token))
            {
                continue;
            }
            boolean stale = false;
            for (final Reservation reservation : mine)
            {
                if (reservation.reason() == reason && tick - reservation.tick() > minAge)
                {
                    stale = true;
                    break;
                }
            }
            if (stale)
            {
                end(token, null, tick, LedgerEvent.Op.ORPHAN, true, r -> r.reason() == reason && tick - r.tick() > minAge);
                dropped++;
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
        return sweepStaleHandouts(tick, maxAge, token -> false);
    }

    /**
     * Self-healing: drops claims on items handed out to a requester that never took them, after {@code maxAge} ticks; never while the request they are held for still
     * waits for the rest (round 8, Sol M3: a short receipt is part of the request's progress, it does not age out while the request is open).
     *
     * @param tick    the game time.
     * @param maxAge  the age in ticks after which such a claim is dropped.
     * @param waiting whether a request still waits for its items.
     * @return the number of claims dropped.
     */
    public int sweepStaleHandouts(final long tick, final long maxAge, @NotNull final Predicate<IToken<?>> waiting)
    {
        if (!RsProbe.enabled)
        {
            return sweepStaleHandouts0(tick, maxAge, waiting);
        }
        RsProbe.begin(RsProbe.LEDGER);
        try
        {
            return sweepStaleHandouts0(tick, maxAge, waiting);
        }
        finally
        {
            RsProbe.end(RsProbe.LEDGER);
        }
    }

    private int sweepStaleHandouts0(final long tick, final long maxAge, final Predicate<IToken<?>> waiting)
    {
        int dropped = 0;
        for (final IToken<?> token : new ArrayList<>(byToken.keySet()))
        {
            final List<Reservation> mine = byToken.get(token);
            if (mine == null || waiting.test(token))
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
     * @param scope  the building.
     * @param items  which items.
     * @param counts which claims count.
     * @return the stock reserved at the building for items matching the predicate by the claims that pass the filter.
     */
    public int reservedStockWhere(@NotNull final BlockPos scope, @NotNull final Predicate<ItemStack> items, @NotNull final Predicate<Reservation> counts)
    {
        final List<Reservation> list = stockByScope.get(scope);
        if (list == null)
        {
            return 0;
        }
        int total = 0;
        for (final Reservation reservation : list)
        {
            if (counts.test(reservation) && items.test(reservation.key().getItemStack()))
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
        if (!RsProbe.enabled)
        {
            return sum0(list, container, predicate);
        }
        RsProbe.begin(RsProbe.LEDGER_READ);
        try
        {
            return sum0(list, container, predicate);
        }
        finally
        {
            RsProbe.end(RsProbe.LEDGER_READ);
        }
    }

    private static int sum0(@Nullable final List<Reservation> list, @Nullable final BlockPos container, final Predicate<ItemStack> predicate)
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
     * @param reason a reason.
     * @return the open claims made for that reason, stock and space.
     */
    @NotNull
    public List<Reservation> byReason(@NotNull final ReservationReason reason)
    {
        final List<Reservation> found = new ArrayList<>();
        for (final List<Reservation> list : byToken.values())
        {
            for (final Reservation reservation : list)
            {
                if (reservation.reason() == reason)
                {
                    found.add(reservation);
                }
            }
        }
        return found;
    }

    /**
     * The colony's reserved stock per item over every source, for one reason or for all.
     *
     * @param reason the reason to count, null for every reason.
     * @return item key (amount 1) to the stock reserved for it.
     */
    @NotNull
    public Map<ItemStorage, Integer> reservedStockByItem(@Nullable final ReservationReason reason)
    {
        final Map<ItemStorage, Integer> out = new HashMap<>();
        for (final List<Reservation> list : stockByScope.values())
        {
            for (final Reservation reservation : list)
            {
                if (reason == null || reservation.reason() == reason)
                {
                    out.merge(reservation.key(), reservation.amount(), Integer::sum);
                }
            }
        }
        return out;
    }

    /**
     * @param key    the item, compared exactly.
     * @param reason the reason to count, null for every reason.
     * @return the stock reserved for the item over every source of the colony.
     */
    public int reservedStock(@NotNull final ItemStorage key, @Nullable final ReservationReason reason)
    {
        final ItemStorage stored = normalized(key);
        int total = 0;
        for (final List<Reservation> list : stockByScope.values())
        {
            for (final Reservation reservation : list)
            {
                if ((reason == null || reservation.reason() == reason) && reservation.key().equals(stored))
                {
                    total += reservation.amount();
                }
            }
        }
        return total;
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
            if (reservation.forBuilding() != null)
            {
                entry.putLong(NBT_FOR, reservation.forBuilding().asLong());
            }
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
                  ReservationReason.valueOf(entry.getStringOr(NBT_REASON, ReservationReason.OTHER.name())), entry.getLongOr(NBT_TICK, 0L),
                  entry.contains(NBT_FOR) ? BlockPos.of(entry.getLongOr(NBT_FOR, 0L)) : null);
            }
            catch (final RuntimeException e)
            {
                Log.getLogger().warn("Dropped an unreadable reservation", e);
            }
        }
        history.clear();
    }
}
