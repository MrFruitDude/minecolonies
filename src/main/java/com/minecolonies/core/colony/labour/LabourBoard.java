package com.minecolonies.core.colony.labour;

import com.google.common.collect.ImmutableList;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.IMinimumStockModule;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.request.WaitReason;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.requestsystem.RsAccess;
import com.minecolonies.core.colony.requestsystem.RsFlags;
import com.minecolonies.core.colony.requestsystem.priority.RequestImportance;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationKind;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationReason;
import com.minecolonies.core.colony.requestsystem.resolvers.StandardPlayerRequestResolver;
import com.minecolonies.core.colony.requestsystem.resolvers.StandardRetryingRequestResolver;
import com.minecolonies.core.colony.requestsystem.wait.RequestWaitTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The labourers' board of one colony (LABOURERS.md 3): the requests that no building serves, the stock goals of the warehouse, and the claims labourers hold on
 * them. It owns the caps, the claims and their leases, the back-off after failures, and the ledger entries of the claims. Runtime state only (a restart drops the claims;
 * the ledger's stale-labour sweep drops what they held). Server thread only.
 * <p>
 * A request is a candidate when it waits at the player or the retrying resolver (so no building resolver serves it), is not covered by a specialist
 * ({@link LabourCoverage}), is no top-up (not BACKGROUND), and the planner finds a way to get hold of it by hand. The board creates no request and no courier task:
 * a labourer brings the goods to the hut that asked and wakes the request.
 */
public final class LabourBoard
{
    /**
     * Ticks between two rebuilds of the candidate list.
     */
    public static final int REBUILD_TICKS = 100;

    /**
     * Candidates kept.
     */
    public static final int MAX_CANDIDATES = 64;

    /**
     * Most goods in one lot.
     */
    public static final int MAX_LOT = 64;

    /**
     * Ticks a citizen rests after a claim failed (or was given up) before he takes the next.
     */
    public static final int CITIZEN_COOLDOWN = 600;

    /**
     * W2: ticks a citizen rests after a claim that handed in its goods (it was {@link #CITIZEN_COOLDOWN} after every claim).
     */
    public static final int CITIZEN_COOLDOWN_SUCCESS = 40;

    /**
     * W2: the rest after a claim.
     *
     * @param success whether the goods were handed in.
     * @return the ticks.
     */
    public static int restAfter(final boolean success)
    {
        return success ? CITIZEN_COOLDOWN_SUCCESS : CITIZEN_COOLDOWN;
    }

    /**
     * W2: the game time until which a citizen rests after his last claim (0 when he does not rest).
     *
     * @param citizenId the citizen id.
     * @return the game time.
     */
    public long restsUntil(final int citizenId)
    {
        return cooldown.getOrDefault(citizenId, 0L);
    }

    /**
     * Ticks after which a citizen whose offer found nothing asks again.
     */
    public static final int NO_OFFER_WAIT = 100;

    /**
     * Back-off ladder of a request after a failed claim: 220, 440, ... game ticks, at most 6600 (the same ladder as the retrying resolver).
     */
    private static final int BACKOFF_FIRST = 220;
    private static final int BACKOFF_MAX   = 6600;

    /**
     * The labour places each colony holds, by colony key (its claims that take a place and its citizens on a directed order who hold no claim yet, see
     * {@link #places()}); the world cap shares what is left. Each colony writes its own entry whenever its places change ({@link #syncPlaces()}).
     */
    private static final Map<String, Integer> ACTIVE = new HashMap<>();

    /**
     * Round 7 teamwork (N47): the colonies that asked for a labour place and found none, by colony key: {first refusal, last refusal} in game time. While a colony
     * waits, another colony that holds as many places or more takes no new one (see {@link #capacity()}), so the places freed go to the colony that waits.
     */
    private static final Map<String, long[]> WANTS = new HashMap<>();

    /**
     * A colony that has not asked again for this long no longer waits for a place.
     */
    private static final long WANT_TICKS = 600L;

    /**
     * The colony behind each key of {@link #ACTIVE}, to drop the count of a colony that is gone (deleted or unloaded while it had claims).
     */
    private static final Map<String, java.lang.ref.WeakReference<Colony>> OWNERS = new HashMap<>();

    /**
     * @return whether the colony is still the colony manager's colony of its id and dimension.
     */
    private static boolean live(final java.lang.ref.WeakReference<Colony> ref)
    {
        final Colony colony = ref.get();
        return colony != null && com.minecolonies.api.colony.IColonyManager.getInstance().getColonyByDimension(colony.getID(), colony.getDimension()) == colony;
    }

    /**
     * A request that waits for labour.
     */
    public record Candidate(IToken<?> token, BlockPos hut, IDeliverable deliverable, List<Item> targets, RequestImportance importance, long since, int count, int minimum,
                            ItemStack exact)
    {
        public Candidate(final IToken<?> token, final BlockPos hut, final IDeliverable deliverable, final List<Item> targets, final RequestImportance importance, final long since,
          final int count, final int minimum)
        {
            this(token, hut, deliverable, targets, importance, since, count, minimum, ItemStack.EMPTY);
        }
    }

    /**
     * A request labour could make but leaves alone because a specialist covers it (shown, so the player sees why nobody labours on it).
     */
    public record Covered(IToken<?> token, ItemStack stack, int count)
    {
    }

    /**
     * Most covered requests kept for the panel.
     */
    public static final int MAX_COVERED = 16;

    private List<Covered> covered = List.of();

    private record CutKey(Item item, DataComponentPatch components)
    {
    }

    private final Map<CutKey, LabourCutter.Resolved> cutJobs = new HashMap<>();
    private Object cutJobsKey;

    private final Colony       colony;
    private final LabourSites  sites;
    private final LabourCoverage coverage;
    private final DirectedOrders directed;
    private final ShaftSites     shafts;

    private final Map<Integer, LabourClaim>      byCitizen = new LinkedHashMap<>();
    private final Map<IToken<?>, Integer>        delivered = new HashMap<>();
    private final Map<IToken<?>, Long>           notBefore = new HashMap<>();
    private final Map<IToken<?>, Integer>        failures  = new HashMap<>();
    private final Map<Integer, Long>             cooldown  = new HashMap<>();
    /** Since when the citizen of a claim stands where entities do not tick (he does not move or act there; builder review c3 R3). */
    private final Map<Integer, Long>             frozenSince = new HashMap<>();
    /** Claims given back because their citizen stopped ticking: the request is on back-off, the session ends neutrally ({@link #releasedUnticked}). */
    private final java.util.Set<LabourClaim>     releasedFrozen = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** How long a citizen may stand where entities do not tick before his claim is given back. */
    static final long FROZEN_TICKS = 200L;
    private final Map<Integer, Long>             noOffer   = new HashMap<>();
    private final Map<String, Boolean>           goalActive = new HashMap<>();
    private final Map<IToken<?>, Long>           pendingWake = new HashMap<>();
    private final Map<IToken<?>, LabourPlanner.Why> blockedWhy = new HashMap<>();

    /** What frees the sites a claim's session took (registered by the session; run when the claim is released). */
    private final Map<LabourClaim, Runnable> sitesOf = new java.util.IdentityHashMap<>();

    private final Set<BlockPos> takenTrees    = new HashSet<>();
    private final Set<BlockPos> takenSurface  = new HashSet<>();
    private final Set<BlockPos> takenTables   = new HashSet<>();
    private final Set<BlockPos> takenFurnaces = new HashSet<>();
    private final Set<BlockPos> takenCutters  = new HashSet<>();
    private final Set<Integer>  takenEntities = new HashSet<>();

    private final Map<IToken<?>, Long>           firstSeen   = new HashMap<>();
    private final LabourSelfSupply               selfSupply = new LabourSelfSupply();

    private List<Candidate> candidates = List.of();
    private long            builtAt    = Long.MIN_VALUE / 2;
    private long            lastReassert;
    private boolean         seenSweep;
    private long            lastSweep;
    private long            lastDebtSweep = Long.MIN_VALUE / 2;

    public LabourBoard(final Colony colony)
    {
        this.colony = colony;
        this.sites = new LabourSites(colony);
        this.coverage = new LabourCoverage(colony);
        this.directed = new DirectedOrders(colony, this);
        this.shafts = new ShaftSites(colony, this);
    }

    // ------------------------------------------------------------------ access

    /**
     * @param colony a colony.
     * @param token  a ledger token.
     * @return whether a live labour claim holds the token.
     */
    public static boolean isLive(final com.minecolonies.api.colony.IColony colony, final IToken<?> token)
    {
        return colony instanceof Colony c && c.getLabourBoard().holds(token);
    }

    public boolean holds(final IToken<?> token)
    {
        for (final LabourClaim claim : byCitizen.values())
        {
            if (claim.token().equals(token))
            {
                return true;
            }
        }
        return false;
    }

    public Colony colony()
    {
        return colony;
    }

    public LabourSites sites()
    {
        return sites;
    }

    public LabourCoverage coverage()
    {
        return coverage;
    }

    /**
     * @return the managers' directed gathering orders.
     */
    public DirectedOrders directed()
    {
        return directed;
    }

    /**
     * @return the colony's stone shafts: who digs where, the search for a spot, the managers' marks.
     */
    public ShaftSites shafts()
    {
        return shafts;
    }

    public Set<BlockPos> takenTrees()
    {
        return takenTrees;
    }

    public Set<BlockPos> takenSurface()
    {
        return takenSurface;
    }

    public Set<BlockPos> takenTables()
    {
        return takenTables;
    }

    public Set<BlockPos> takenFurnaces()
    {
        return takenFurnaces;
    }

    public Set<BlockPos> takenCutters()
    {
        return takenCutters;
    }

    public Set<Integer> takenEntities()
    {
        return takenEntities;
    }

    /**
     * @param citizenId a citizen.
     * @return his claim, or null.
     */
    @Nullable
    public LabourClaim claimOf(final int citizenId)
    {
        return byCitizen.get(citizenId);
    }

    /**
     * @return the live claims (a copy).
     */
    public List<LabourClaim> claims()
    {
        return new ArrayList<>(byCitizen.values());
    }

    /**
     * @return how many claims serve the request.
     */
    public int claimsOn(final IToken<?> request)
    {
        int n = 0;
        for (final LabourClaim claim : byCitizen.values())
        {
            if (request.equals(claim.request()))
            {
                n++;
            }
        }
        return n;
    }

    private int claimsOnGoal(final String goal)
    {
        int n = 0;
        for (final LabourClaim claim : byCitizen.values())
        {
            if (goal.equals(claim.goalId()))
            {
                n++;
            }
        }
        return n;
    }

    private int claimsOf(final LabourClaim.Source source)
    {
        int n = 0;
        for (final LabourClaim claim : byCitizen.values())
        {
            // (a worker helping out does not take a jobless labourer's place, nor does a hand-in of goods already carried; every claim counts against the
            // colony's labour places, see places())
            if (claim.source() == source && !claim.byWorker() && !claim.cargo())
            {
                n++;
            }
        }
        return n;
    }

    /**
     * Round 6 teamwork, one admission policy: whether a claim takes one of the colony's labour places. Every claim does (a jobless labourer's, a helping worker's, a
     * directed order's) except the idle builder's own supply and a worker's own tool (round 7, R6-F1/N46: both are the worker's own need, never held back by
     * optional help) and a hand-in of goods already carried ({@link LabourCargo}), which must never be held back.
     */
    static boolean takesPlace(final LabourClaim claim)
    {
        return claim.source() != LabourClaim.Source.SELF && claim.source() != LabourClaim.Source.TOOL && !claim.cargo();
    }

    /**
     * @return the claims that take a place (the citizens on a directed order who hold no claim yet not counted).
     */
    private int claimedPlaces()
    {
        int n = 0;
        for (final LabourClaim claim : byCitizen.values())
        {
            if (takesPlace(claim))
            {
                n++;
            }
        }
        return n;
    }

    /**
     * @return the labour places taken now: the claims that take a place, and the citizens on a directed order who hold no claim yet.
     */
    public int places()
    {
        return claimedPlaces() + Math.max(0, directed.assigned() - claimsOf(LabourClaim.Source.DIRECTED));
    }

    /**
     * @return the labour places of the colony: its own cap, never more than the world cap leaves after the other colonies' places (their claims and their pending
     *   directed citizens), and, while another colony waits for a place (round 7, N47), no more than that colony holds (one more when this colony has waited
     *   longer): the world cap is shared, a late colony is not starved while another holds every place. With a world cap of 1 the one place goes to whichever colony
     *   waited first once it is free.
     */
    public int capacity()
    {
        return capacity(true);
    }

    /**
     * @param fair whether a colony that waits for a place holds this one back (round 7, N47); without it only the colony's own cap and what the world cap leaves
     *             count (a directed citizen's place, reserved when his order was accepted, is not given up to a colony that waits later).
     * @return the labour places of the colony.
     */
    private int capacity(final boolean fair)
    {
        final int worldMax = LabourSettings.worldMax();
        if (worldMax <= 0)
        {
            return 0;
        }
        int cap = LabourSettings.total(colony);
        syncPlaces();
        synchronized (ACTIVE)
        {
            dropGone();
            final long now = now();
            WANTS.values().removeIf(w -> now - w[1] > WANT_TICKS || now < w[1]);
            final long[] mine = WANTS.get(key());
            int others = 0;
            for (final Map.Entry<String, Integer> entry : ACTIVE.entrySet())
            {
                if (!entry.getKey().equals(key()))
                {
                    others += entry.getValue();
                }
            }
            cap = Math.min(cap, Math.max(0, worldMax - others));
            for (final Map.Entry<String, long[]> want : fair ? WANTS.entrySet() : java.util.Set.<Map.Entry<String, long[]>>of())
            {
                if (want.getKey().equals(key()))
                {
                    continue;
                }
                // (ties go to the colony that waited first; a colony that does not wait loses them)
                final boolean first = mine != null && (mine[0] < want.getValue()[0] || (mine[0] == want.getValue()[0] && key().compareTo(want.getKey()) < 0));
                cap = Math.min(cap, ACTIVE.getOrDefault(want.getKey(), 0) + (first ? 1 : 0));
            }
        }
        return Math.max(0, cap);
    }

    /**
     * Drops the places of a colony that is gone (deleted or unloaded with live claims). Called under the lock of {@link #ACTIVE}.
     */
    private void dropGone()
    {
        ACTIVE.keySet().removeIf(k -> !k.equals(key()) && OWNERS.containsKey(k) && !live(OWNERS.get(k)));
        WANTS.keySet().removeIf(k -> !k.equals(key()) && OWNERS.containsKey(k) && !live(OWNERS.get(k)));
        OWNERS.keySet().retainAll(java.util.stream.Stream.concat(ACTIVE.keySet().stream(), WANTS.keySet().stream()).collect(java.util.stream.Collectors.toSet()));
    }

    /**
     * @return whether no labour place is free.
     */
    public boolean placesFull()
    {
        return places() >= capacity();
    }

    /**
     * {@link #placesFull()} for a citizen of this colony who asks for a place now: when none is free while the colony is under its own cap (the world cap or another
     * colony holds them), the colony waits for a place (round 7, N47).
     */
    private boolean full()
    {
        if (!placesFull())
        {
            return false;
        }
        if (places() < LabourSettings.total(colony) && LabourSettings.worldMax() > 0)
        {
            final long now = now();
            synchronized (ACTIVE)
            {
                final long[] want = WANTS.computeIfAbsent(key(), k -> new long[] {now, now});
                want[1] = now;
                OWNERS.putIfAbsent(key(), new java.lang.ref.WeakReference<>(colony));
            }
        }
        return true;
    }

    /**
     * Round 7 (R6-F5): why a jobless citizen of this colony stands idle when it is only for want of a labour place: every place of the colony or the world is
     * taken. For the views (a client shows it as the citizen's reason).
     *
     * @param citizen a citizen.
     * @return the reason, or null when he holds a claim, is no jobless adult, labour is off, or a place is free.
     */
    @Nullable
    public Component noPlaceReason(final ICitizenData citizen)
    {
        if (citizen.getJob() != null || citizen.isChild() || byCitizen.containsKey(citizen.getId()) || directed.orderOf(citizen.getId()) != null
              || !LabourSettings.enabled(colony) || !placesFull())
        {
            return null;
        }
        return Component.translatable("com.minecolonies.coremod.labour.noplace", places(), capacity());
    }

    private long now()
    {
        return colony.getWorld() == null ? 0L : colony.getWorld().getGameTime();
    }

    private String key()
    {
        return colony.getDimension().identifier() + "/" + colony.getID();
    }

    /**
     * A manager flags a crafting table or furnace of the colony (exclude, prefer) or clears the flag.
     *
     * @param pos  the block.
     * @param flag the flag, null to clear it.
     * @return false when the block is no table or furnace of this colony's claim (nothing changed).
     */
    public boolean flagTable(final BlockPos pos, @Nullable final LabourData.TableFlag flag)
    {
        if (!sites.allTables().contains(pos) && !sites.allFurnaces().contains(pos))
        {
            return false;
        }
        if (colony.getLabourData().setFlag(pos, flag))
        {
            colony.markDirty();
            sites.invalidate();
        }
        return true;
    }

    // ------------------------------------------------------------------ quarry zones and owed hut items (Wave B)

    /**
     * @return the colony's quarry zones (read-only use; mark and remove through the board).
     */
    public QuarryZones quarries()
    {
        return colony.getLabourData().quarries();
    }

    /**
     * A manager marks a quarry zone. The server checks the size, that every chunk of it is loaded and part of THIS colony's claim, the overlap and the count; every column's
     * top now is kept (its depth and terraces are measured from it) and a zone whose tops differ by more than the quarry depth is refused.
     *
     * @return the zone, or why there is none (nothing changed then).
     */
    public QuarryZones.Marked markQuarry(final DirectedOrder.Area area)
    {
        if (!QuarryZones.sizeAllowed(area))
        {
            return new QuarryZones.Marked(null, QuarryZones.Refusal.BAD_AREA);
        }
        if (!sites.areaUsable(area))
        {
            return new QuarryZones.Marked(null, QuarryZones.Refusal.AREA_OUTSIDE);
        }
        final QuarryZones.Marked marked = quarries().add(area, sites.columnTops(area), LabourSettings.quarryDepth());
        if (marked.zone() != null)
        {
            colony.markDirty();
            sites.invalidate();
        }
        return marked;
    }

    /**
     * A manager removes a quarry zone; labour digs no further in it (a labourer at work there stops at his next fresh look).
     *
     * @return false when the colony has no zone of the id.
     */
    public boolean removeQuarry(final int id)
    {
        if (!quarries().remove(id))
        {
            return false;
        }
        colony.markDirty();
        sites.invalidate();
        return true;
    }

    /**
     * A hut was placed on credit: it owes its hut item and asks for it with an ordinary request ({@link HutDebts}).
     *
     * @return false when the colony owes too many hut items already (nothing changed).
     */
    public boolean oweHut(final com.minecolonies.api.colony.buildings.IBuilding building, final Item hut)
    {
        if (!colony.getLabourData().hutDebts().owe(building, hut, now()))
        {
            return false;
        }
        colony.markDirty();
        return true;
    }

    // ------------------------------------------------------------------ stone shafts (r4)

    /**
     * A manager marks a spot for a stone shaft: the server checks the claim, the room, the ground and surveys it ({@link ShaftSites#mark}).
     */
    public ShaftSites.Marked markShaft(final BlockPos column, final int openedBy)
    {
        return shafts.mark(column, openedBy);
    }

    /**
     * A manager closes a shaft (nobody digs there any more; a worker in it stops at his next look).
     */
    @Nullable
    public LabourShafts.Refusal closeShaft(final int id)
    {
        return shafts.close(id);
    }

    /**
     * A manager opens a closed shaft again (while the colony has room).
     */
    @Nullable
    public LabourShafts.Refusal reopenShaft(final int id)
    {
        final LabourShafts.Refusal refusal = shafts.reopen(id);
        if (refusal == null)
        {
            invalidate();
        }
        return refusal;
    }

    /**
     * @return whether cobblestone is wanted from labour: a request waits for it (nobody covers it for the hut that asks), or the stock goal for it is below its start
     *   (or still filling up to its target).
     */
    public boolean shaftDemand()
    {
        for (final Candidate candidate : candidates())
        {
            if (candidate.targets().contains(net.minecraft.world.item.Items.COBBLESTONE))
            {
                return true;
            }
        }
        if (!LabourSettings.stockpile(colony) || sites.warehouses().isEmpty() || coverage.covered(net.minecraft.world.item.Items.COBBLESTONE))
        {
            return false;
        }
        for (final LabourDataListener.StockpileGoal goal : stockGoals())
        {
            if (LabourDataListener.matches(goal.item(), net.minecraft.world.item.Items.COBBLESTONE))
            {
                final int stock = stock(net.minecraft.world.item.Items.COBBLESTONE);
                if (goalActive(goal, stock, goalActive.getOrDefault(goal.id(), false)))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A session took sites (trees, blocks, posts, items) for the claim: {@code free} gives them back when the claim is released, whoever releases it.
     */
    public void holdSites(final LabourClaim claim, final Runnable free)
    {
        if (alive(claim))
        {
            sitesOf.put(claim, free);
        }
    }

    // ------------------------------------------------------------------ caps

    /**
     * @return how many unemployed adults the colony has.
     */
    public int unemployed()
    {
        int n = 0;
        for (final ICitizenData citizen : colony.getCitizenManager().getCitizens())
        {
            if (citizen.getJob() == null && !citizen.isChild())
            {
                n++;
            }
        }
        return n;
    }

    /**
     * @return how many labourers (unemployed citizens) the colony may have at once: the share of the unemployed under the colony cap and the colony's part of the
     *   world cap.
     */
    public int allowed()
    {
        final int unemployed = unemployed();
        final int cap = capacity();
        if (cap <= 0)
        {
            return 0;
        }
        final int byShare = (int) Math.floor(unemployed * LabourSettings.share(colony));
        return Math.min(cap, Math.max(Math.min(1, unemployed), byShare));
    }

    private boolean capReached()
    {
        // (the citizens on a manager's directed order count against the same caps; the jobless share, and all places together: workers helping out and
        // workers' own tools take places too)
        return claimsOf(LabourClaim.Source.REQUEST) + claimsOf(LabourClaim.Source.STOCKPILE) + Math.max(claimsOf(LabourClaim.Source.DIRECTED), directed.assigned()) >= allowed()
                 || full();
    }

    /**
     * Writes the colony's places into {@link #ACTIVE}, so the other colonies count them (round 7, Sol H2: its pending directed citizens included).
     */
    void syncPlaces()
    {
        final int places = places();
        synchronized (ACTIVE)
        {
            final java.lang.ref.WeakReference<Colony> owner = OWNERS.get(key());
            if (owner == null || owner.get() != colony)
            {
                // (a colony with the id of one that is gone starts from its own places, not the old one's)
                ACTIVE.remove(key());
                WANTS.remove(key());
                OWNERS.put(key(), new java.lang.ref.WeakReference<>(colony));
            }
            if (places <= 0)
            {
                ACTIVE.remove(key());
            }
            else
            {
                ACTIVE.put(key(), places);
            }
        }
    }

    // ------------------------------------------------------------------ stock

    /**
     * @param item an item.
     * @return how many of it the warehouses hold that no reservation holds.
     */
    public int stock(final Item item)
    {
        final ReservationLedger ledger = RsAccess.ledger(colony.getRequestManager());
        int total = 0;
        for (final IWareHouse wareHouse : sites.warehouses())
        {
            final int physical = InventoryUtils.getCountFromBuilding(wareHouse, stack -> stack.is(item));
            final int reserved = ledger == null ? 0 : ledger.reservedStock(wareHouse.getID(), stack -> stack.is(item));
            total += Math.max(0, physical - reserved);
        }
        return total;
    }

    // ------------------------------------------------------------------ candidates

    /**
     * @return the requests that wait for labour (rebuilt every {@link #REBUILD_TICKS} ticks), most urgent first.
     */
    public List<Candidate> candidates()
    {
        final long now = now();
        if (now - builtAt < REBUILD_TICKS && now >= builtAt)
        {
            return candidates;
        }
        builtAt = now;
        candidates = build();
        return candidates;
    }

    /**
     * Forgets the candidate list (a test changed the requests).
     */
    public void invalidate()
    {
        builtAt = Long.MIN_VALUE / 2;
        coverage.invalidate();
        sites.invalidate();
    }

    private List<Candidate> build()
    {
        final IRequestManager manager = colony.getRequestManager();
        if (manager == null)
        {
            return List.of();
        }
        final List<IToken<?>> tokens = new ArrayList<>();
        if (manager.getPlayerResolver() instanceof StandardPlayerRequestResolver player)
        {
            tokens.addAll(player.getAllAssignedRequests());
        }
        if (manager.getRetryingRequestResolver() instanceof StandardRetryingRequestResolver retrying)
        {
            tokens.addAll(retrying.getAllAssignedRequests());
        }
        if (tokens.isEmpty())
        {
            return List.of();
        }
        final RequestWaitTracker tracker = RequestWaitTracker.of(manager);
        final List<Item> items = LabourRules.candidateItems();
        final List<Candidate> list = new ArrayList<>();
        final List<Covered> coveredNow = new ArrayList<>();
        for (final IToken<?> token : tokens)
        {
            final IRequest<?> request;
            try
            {
                request = manager.getRequestForToken(token);
            }
            catch (final IllegalArgumentException e)
            {
                continue;
            }
            if (request == null || !(request.getRequest() instanceof IDeliverable deliverable) || !isOpen(request.getState()))
            {
                continue;
            }
            final BlockPos hut = request.getRequester().getLocation().getInDimensionLocation();
            if (colony.getServerBuildingManager().getBuilding(hut) == null)
            {
                continue;
            }
            final List<Item> targets = new ArrayList<>();
            Item coveredItem = null;
            for (final Item item : items)
            {
                if (deliverable.matches(new ItemStack(item)))
                {
                    // (a good only the asking hut itself covers is not covered for its own request: a new miner's cobblestone)
                    if (!coverage.coveredFor(item, hut))
                    {
                        targets.add(item);
                    }
                    else if (coveredItem == null)
                    {
                        coveredItem = item;
                    }
                }
            }
            ItemStack exact = ItemStack.EMPTY;
            if (targets.isEmpty() && coveredItem == null)
            {
                // a Domum Ornamentum block: made at an Architect's Cutter, exactly the requested variant
                final ItemStack wanted = com.minecolonies.core.util.DomumOrnamentumUtils.getRequestedStack(request);
                if (LabourCutter.isCutterStack(wanted) && deliverable.matches(wanted))
                {
                    if (coverage.coveredFor(wanted.getItem(), hut))
                    {
                        coveredItem = wanted.getItem();
                        exact = wanted.copyWithCount(1);
                    }
                    else
                    {
                        exact = wanted.copyWithCount(1);
                        targets.add(wanted.getItem());
                    }
                }
            }
            if (targets.isEmpty())
            {
                if (coveredItem != null && coveredNow.size() < MAX_COVERED)
                {
                    coveredNow.add(new Covered(token, exact.isEmpty() ? new ItemStack(coveredItem) : exact, Math.max(1, deliverable.getCount())));
                }
                continue;
            }
            targets.sort(Comparator.comparingInt(LabourBoard::tier));
            final RequestWaitTracker.Waiter waiter = tracker == null ? null : tracker.waiter(token);
            list.add(new Candidate(token, hut.immutable(), deliverable, targets, RequestImportance.of(manager, request), waiter == null ? 0L : waiter.since(),
              Math.max(1, deliverable.getCount()), Math.max(1, deliverable.getMinimumCount()), exact));
        }
        list.sort(Comparator.comparingInt((Candidate c) -> c.importance().rank()).thenComparingLong(Candidate::since).thenComparing(c -> c.token().toString()));
        covered = List.copyOf(coveredNow);
        return list.size() > MAX_CANDIDATES ? new ArrayList<>(list.subList(0, MAX_CANDIDATES)) : list;
    }

    /**
     * @return the requests labour could make but leaves to a specialist that covers them (as of the last candidate list).
     */
    public List<Covered> covered()
    {
        candidates();
        return covered;
    }

    /**
     * The cutter recipe for an exact Domum Ornamentum stack, resolved once per recipe manager.
     */
    public LabourCutter.Resolved cutJob(final ItemStack exact)
    {
        final ServerLevel level = colony.getWorld() instanceof ServerLevel server ? server : null;
        if (level == null)
        {
            return new LabourCutter.Resolved(null, LabourCutter.Refusal.WRONG_VARIANT);
        }
        if (cutJobsKey != level.recipeAccess())
        {
            cutJobs.clear();
            cutJobsKey = level.recipeAccess();
        }
        final CutKey key = new CutKey(exact.getItem(), exact.getComponentsPatch());
        LabourCutter.Resolved resolved = cutJobs.get(key);
        if (resolved == null)
        {
            if (cutJobs.size() > 256)
            {
                cutJobs.clear();
            }
            resolved = LabourCutter.resolve(level, exact);
            cutJobs.put(key, resolved);
        }
        return resolved;
    }

    /**
     * The next step toward holding {@code lot} of the exact stack: the cutter's materials (planned like any good), DONE when they are held for every run.
     *
     * @param held how many of the exact stack he holds already.
     */
    public LabourPlanner.Result<Item> planCut(final LabourEnv env, final ItemStack exact, final int lot, final int held, final Map<Item, Integer> inventory)
    {
        final LabourCutter.Resolved resolved = cutJob(exact);
        if (resolved.job() == null)
        {
            return new LabourPlanner.Blocked<>(resolved.refusal() == LabourCutter.Refusal.INPUT ? LabourPlanner.Why.CUTTER_INPUT : LabourPlanner.Why.WRONG_VARIANT,
              exact.getItem());
        }
        if (held >= lot)
        {
            return new LabourPlanner.Done<>();
        }
        if (!sites.hasCutter())
        {
            return new LabourPlanner.Blocked<>(LabourPlanner.Why.NO_CUTTER, exact.getItem());
        }
        return LabourPlanner.nextInputs(env, resolved.job().inputs(), resolved.job().runsFor(lot - held), inventory);
    }

    private static boolean isOpen(final RequestState state)
    {
        return state != RequestState.COMPLETED && state != RequestState.CANCELLED && state != RequestState.FAILED && state != RequestState.RECEIVED
                 && state != RequestState.OVERRULED && state != RequestState.RESOLVED && state != RequestState.FINALIZING;
    }

    /**
     * Wood before stone before the rest: the cheaper way to make a request first.
     */
    private static int tier(final Item item)
    {
        final String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
        if (path.startsWith("wooden_") || path.contains("planks") || path.contains("log") || path.equals("stick"))
        {
            return 0;
        }
        if (path.startsWith("stone_") || path.equals("cobblestone"))
        {
            return 1;
        }
        return 2;
    }

    /**
     * @return the candidate for a request, or null when it does not wait for labour.
     */
    @Nullable
    public Candidate candidate(final IToken<?> request)
    {
        for (final Candidate candidate : candidates())
        {
            if (candidate.token().equals(request))
            {
                return candidate;
            }
        }
        return null;
    }

    /**
     * @param request a request token.
     * @return whether the request still waits at the player or the retrying resolver (so that no building serves it).
     */
    public boolean stillWaiting(final IToken<?> request)
    {
        final IRequestManager manager = colony.getRequestManager();
        if (manager == null)
        {
            return false;
        }
        return (manager.getPlayerResolver() instanceof StandardPlayerRequestResolver player && player.isHolding(request))
                 || (manager.getRetryingRequestResolver() instanceof StandardRetryingRequestResolver retrying && retrying.isHolding(request));
    }

    // ------------------------------------------------------------------ offers

    /**
     * Offers an unemployed citizen the next unit of work: a request first (the urgent ones first of those), then, when {@code stockpile} is set, a stock goal.
     *
     * @param citizen    the citizen.
     * @param urgentOnly only requests a worker is blocked on.
     * @param requests   whether requests are offered.
     * @param stockpile  whether stock goals are offered.
     * @return the claim, or null for nothing.
     */
    @Nullable
    public LabourClaim offer(final ICitizenData citizen, final boolean urgentOnly, final boolean requests, final boolean stockpile)
    {
        final int offerKey = citizen.getId() * 4 + (urgentOnly ? 0 : requests ? 1 : 2);
        if (!LabourSettings.enabled(colony) || citizen.getJob() != null || byCitizen.containsKey(citizen.getId()) || directed.orderOf(citizen.getId()) != null)
        {
            return null;
        }
        final LabourClaim handIn = cargoClaim(citizen);
        if (handIn != null)
        {
            // what he carries for a request comes first, whatever the caps: those goods are gathered already
            return handIn;
        }
        final long now = now();
        if (now < cooldown.getOrDefault(citizen.getId(), 0L) || now < noOffer.getOrDefault(offerKey, 0L) || capReached())
        {
            return null;
        }
        final Map<Item, Integer> inventory = LabourSession.inventoryOf(citizen);
        final LabourEnv env = new LabourEnv(colony, this, true);
        if (requests)
        {
            int tries = 0;
            for (final Candidate candidate : candidates())
            {
                if (urgentOnly && candidate.importance() != RequestImportance.URGENT)
                {
                    continue;
                }
                if (candidate.importance() == RequestImportance.BACKGROUND || now < notBefore.getOrDefault(candidate.token(), 0L) || claimsOn(candidate.token()) >= LabourSettings.perRequest(colony)
                      || !stillWaiting(candidate.token()))
                {
                    continue;
                }
                if (tries++ >= 4)
                {
                    break;
                }
                final LabourClaim claim = tryClaim(citizen, candidate, LabourClaim.Source.REQUEST, env, inventory, now);
                if (claim != null)
                {
                    return claim;
                }
            }
        }
        if (stockpile && LabourSettings.stockpile(colony) && !urgentOnly)
        {
            final LabourClaim claim = offerStockpile(citizen, new LabourEnv(colony, this, false), inventory, now);
            if (claim != null)
            {
                return claim;
            }
        }
        noOffer.put(offerKey, now + NO_OFFER_WAIT);
        return null;
    }

    /**
     * How far from his own hut a worker helps out: a hut that asked within this many blocks.
     */
    public static final int WORKER_HELP_RANGE = 64;

    /**
     * Round 3 agency: offers an employed worker who has nothing to do at his hut a request of another hut nearby that no building serves (the stock goals stay with the
     * jobless). The same rules as for a labourer (his own colony's requests only, the claims per request, the back-off), and: never a request of his own hut (that is
     * his own work), never one for something he carries (his own tools), at most one worker per hut at once, and all labour claims together stay under the colony's
     * labour cap.
     *
     * @param worker     the worker (see {@link com.minecolonies.core.colony.idle.WorkerHelp}).
     * @param urgentOnly only requests a worker is blocked on (a builder's).
     * @return the claim, or null for nothing.
     */
    @Nullable
    public LabourClaim offerToWorker(final ICitizenData worker, final boolean urgentOnly)
    {
        final var hut = worker.getWorkBuilding();
        if (!LabourSettings.enabled(colony) || worker.getJob() == null || hut == null || worker.getColony() == null || worker.getColony().getID() != colony.getID()
              || byCitizen.containsKey(worker.getId()) || directed.orderOf(worker.getId()) != null)
        {
            return null;
        }
        final LabourClaim handIn = cargoClaim(worker);
        if (handIn != null)
        {
            handIn.markWorker();
            return handIn;
        }
        final int offerKey = worker.getId() * 4 + (urgentOnly ? 0 : 1);
        final long now = now();
        if (now < cooldown.getOrDefault(worker.getId(), 0L) || now < noOffer.getOrDefault(offerKey, 0L) || workerCapReached(hut.getID()))
        {
            return null;
        }
        final Map<Item, Integer> inventory = LabourSession.inventoryOf(worker);
        final LabourEnv env = new LabourEnv(colony, this, true);
        final long range = (long) WORKER_HELP_RANGE * WORKER_HELP_RANGE;
        int tries = 0;
        for (final Candidate candidate : candidates())
        {
            if ((urgentOnly && candidate.importance() != RequestImportance.URGENT) || candidate.importance() == RequestImportance.BACKGROUND
                  || candidate.hut().equals(hut.getID()) || candidate.hut().distSqr(hut.getID()) > range)
            {
                continue;
            }
            if (now < notBefore.getOrDefault(candidate.token(), 0L) || claimsOn(candidate.token()) >= LabourSettings.perRequest(colony) || !stillWaiting(candidate.token()))
            {
                continue;
            }
            boolean his = false;
            for (final Item target : candidate.targets())
            {
                his |= inventory.getOrDefault(target, 0) > 0;
            }
            if (his)
            {
                continue;
            }
            if (tries++ >= 4)
            {
                break;
            }
            final LabourClaim claim = tryClaim(worker, candidate, LabourClaim.Source.REQUEST, env, inventory, now);
            if (claim != null)
            {
                claim.markWorker();
                return claim;
            }
        }
        noOffer.put(offerKey, now + NO_OFFER_WAIT);
        return null;
    }

    /**
     * @param hut the hut of a worker.
     * @return whether no other worker may help now: one of the hut's workers helps already, or no labour place is free ({@link #capacity}).
     */
    private boolean workerCapReached(final BlockPos hut)
    {
        for (final LabourClaim claim : byCitizen.values())
        {
            if (claim.byWorker() && !claim.cargo())
            {
                final ICitizenData other = colony.getCitizenManager().getCivilian(claim.citizenId());
                if (other != null && other.getWorkBuilding() != null && hut.equals(other.getWorkBuilding().getID()))
                {
                    return true;
                }
            }
        }
        return full();
    }

    /**
     * Round 3 agency: a claim for a worker who waits for a basic tool and gets it himself ({@link LabourSession#forWorkerTool}). It serves no request (the worker's own
     * tool request is withdrawn by his AI) and holds no ledger entry until he takes something out of a warehouse.
     *
     * @param worker the worker.
     * @param tool   the tool he will hold (the item shown for the claim).
     * @return the claim, or null when he holds one already or has no hut of this colony.
     */
    @Nullable
    public LabourClaim claimForTool(final ICitizenData worker, final Item tool)
    {
        final var hut = worker.getWorkBuilding();
        if (byCitizen.containsKey(worker.getId()) || hut == null || worker.getColony() == null || worker.getColony().getID() != colony.getID())
        {
            // (round 7, R6-F1/N46: his tool is his own need and takes no labour place; helpers on optional jobs never hold it back)
            return null;
        }
        final long now = now();
        final LabourClaim claim = new LabourClaim(LabourClaim.Source.TOOL, null, null, worker.getId(), tool, 1, hut.getID(), now, now + LabourSettings.leaseTicks());
        register(claim);
        return claim;
    }

    /**
     * @param request a request token.
     * @return the game time at which the request was first seen waiting for labour.
     */
    public long waitingSince(final IToken<?> request)
    {
        return firstSeen.computeIfAbsent(request, k -> now());
    }

    private Candidate selfCandidate(final BlockPos hut, final int orderId, final long now)
    {
        if (!LabourSettings.worldEnabled() || !LabourSettings.selfSupply(colony) || !selfSupply.mayStart(orderId, now))
        {
            return null;
        }
        for (final Candidate candidate : candidates())
        {
            if (candidate.hut().equals(hut) && candidate.importance() == RequestImportance.URGENT && claimsOn(candidate.token()) == 0 && stillWaiting(candidate.token())
                  && now >= notBefore.getOrDefault(candidate.token(), 0L) && now - waitingSince(candidate.token()) >= LabourSettings.supplyGraceTicks(colony)
                  && !ownTargets(candidate).isEmpty())
            {
                return candidate;
            }
        }
        return null;
    }

    /**
     * What the builder may fetch himself of a candidate: the self-supply goods among its targets (basics, what labour crafts, stripped wood), or the exact Architect's
     * Cutter variant it asks for (made only from that variant's own recipe and materials).
     */
    private static List<Item> ownTargets(final Candidate candidate)
    {
        if (!candidate.exact().isEmpty())
        {
            // the exact Architect's Cutter variant, unless a pack vetoes it for labour
            return candidate.exact().is(LabourRules.NEVER) ? List.of() : candidate.targets();
        }
        final List<Item> own = new ArrayList<>();
        for (final Item item : candidate.targets())
        {
            if (LabourRules.selfSupplyItem(item))
            {
                own.add(item);
            }
        }
        return own;
    }

    /**
     * @param hut     the hut of a builder with an active order.
     * @param orderId his order.
     * @return whether one of the hut's own requests is blocked on basics that the builder may fetch himself (cheap; makes no claim).
     */
    public boolean selfSupplyWanted(final BlockPos hut, final int orderId)
    {
        return selfCandidate(hut, orderId, now()) != null;
    }

    /**
     * The idle builder blocked on basic materials for his own build: a claim for one request of his own hut, with the goods he may fetch himself.
     *
     * @return the claim, or null when none of his requests qualifies (they are not blocked long enough, are covered, have no plan, or his last episode brought nothing a
     *         short while ago: see {@link LabourSelfSupply}).
     */
    @Nullable
    public LabourClaim claimForBuilder(final ICitizenData builder, final BlockPos hut, final int orderId)
    {
        final long now = now();
        if (byCitizen.containsKey(builder.getId()))
        {
            return null;
        }
        final Candidate candidate = selfCandidate(hut, orderId, now);
        if (candidate == null)
        {
            return null;
        }
        final Candidate restricted = new Candidate(candidate.token(), candidate.hut(), candidate.deliverable(), ownTargets(candidate), candidate.importance(), candidate.since(),
          candidate.count(), candidate.minimum(), candidate.exact());
        final LabourClaim claim = tryClaim(builder, restricted, LabourClaim.Source.SELF, new LabourEnv(colony, this, true), LabourSession.inventoryOf(builder), now);
        if (claim != null)
        {
            selfSupply.started(orderId, now);
        }
        return claim;
    }

    /**
     * r4: the citizen cannot plan cobblestone now (no room in the shafts, no pickaxe and no table or wood to make one), but the colony's shafts still have blocks left
     * that somebody else digs, or that another jobless adult with a shaft pickaxe could dig: one who has no pickaxe must not keep the one who has one off the request.
     */
    private boolean shaftWorkForOthers(final ICitizenData citizen, final Candidate candidate)
    {
        if (!candidate.exact().isEmpty() || !candidate.targets().contains(net.minecraft.world.item.Items.COBBLESTONE)
              || coverage.covered(net.minecraft.world.item.Items.COBBLESTONE) || !shafts.undugWork())
        {
            return false;
        }
        if (shafts.othersDigging(citizen.getId()))
        {
            return true;
        }
        for (final ICitizenData other : colony.getCitizenManager().getCitizens())
        {
            if (other.getId() != citizen.getId() && DirectedOrders.eligible(other) && directed.orderOf(other.getId()) == null
                  && DirectedOrders.carries(other, LabourRules.SHAFT_TOOL))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The builder's episode for his order ended (his session stopped): when he may go again.
     */
    public void selfSupplyEnded(final int orderId, final LabourSelfSupply.Outcome outcome)
    {
        selfSupply.ended(orderId, outcome, now());
    }

    /**
     * @return the game time from which the builder of the order may fetch materials himself again (0 when he may now; his requests also need their grace time).
     */
    public long selfSupplyRetryAt(final int orderId)
    {
        return selfSupply.retryAt(orderId);
    }

    /**
     * Makes a claim for one request, if a plan exists.
     *
     * @return the claim, or null when the request cannot be served by hand now (it is put on back-off and shows why).
     */
    @Nullable
    public LabourClaim tryClaim(final ICitizenData citizen, final Candidate candidate, final LabourClaim.Source source, final LabourEnv env, final Map<Item, Integer> inventory,
      final long now)
    {
        final LabourEnv forHut = env.forRequester(candidate.hut());
        int others = 0;
        for (final LabourClaim claim : byCitizen.values())
        {
            if (candidate.token().equals(claim.request()))
            {
                others += claim.amount();
            }
        }
        // (round 7, N45: goods a recalled helper still carries for it are on their way in: never gathered twice)
        others += carriedOnRecordFor(candidate.token(), citizen.getId());
        final int remaining = candidate.count() - deliveredFor(candidate.token()) - others;
        if (remaining <= 0)
        {
            // (the whole request is in other labourers' lots or in the hut already: a task takes no more labourers than it can use)
            return null;
        }
        final int lot = Math.max(1, Math.min(remaining, MAX_LOT));
        Item chosen = null;
        LabourPlanner.Why why = LabourPlanner.Why.NO_RECIPE;
        if (!candidate.exact().isEmpty())
        {
            final LabourPlanner.Result<Item> result = planCut(forHut, candidate.exact(), lot, 0, inventory);
            if (result instanceof LabourPlanner.Blocked<Item> blocked)
            {
                why = blocked.why();
            }
            else
            {
                chosen = candidate.exact().getItem();
            }
        }
        for (final Item item : candidate.exact().isEmpty() ? candidate.targets() : List.<Item>of())
        {
            final LabourPlanner.Result<Item> result = LabourPlanner.next(forHut, item, lot, inventory);
            if (result instanceof LabourPlanner.Blocked<Item> blocked)
            {
                if (chosen == null)
                {
                    why = blocked.why();
                }
                continue;
            }
            chosen = item;
            break;
        }
        if (chosen == null)
        {
            if (shaftWorkForOthers(citizen, candidate))
            {
                // (what he lacks is his own, not the request's: no back-off, the next labourer is asked)
                return null;
            }
            blocked(candidate.token(), why);
            return null;
        }
        final LabourClaim claim = new LabourClaim(source, candidate.token(), null, citizen.getId(), chosen, lot, candidate.hut(), now, now + LabourSettings.leaseTicks(), 0,
          candidate.exact());
        if (coverage.coveredFor(chosen, candidate.hut()))
        {
            LabourStats.CLAIMS_ON_COVERED.incrementAndGet();
        }
        register(claim);
        final ReservationLedger ledger = RsFlags.reservations() ? RsAccess.ledger(colony.getRequestManager()) : null;
        if (ledger != null)
        {
            ledger.reserveSpace(candidate.hut(), new ItemStorage(candidate.exact().isEmpty() ? new ItemStack(chosen) : candidate.exact()), lot, claim.token(), "labourer " + citizen.getName(),
              ReservationReason.LABOUR, now);
        }
        blockedWhy.remove(candidate.token());
        setReason(candidate.token(), WaitReason.AWAITING_LABOUR);
        return claim;
    }

    private List<LabourDataListener.StockpileGoal> goalsCache;
    private long                                   goalsSignature;
    private List<LabourDataListener.StockpileGoal> goalsSeed;

    /**
     * Prefix of the ids of the stock goals taken from a warehouse's minimum-stock entries.
     */
    public static final String MIN_STOCK_GOAL = "minstock:";

    /**
     * The colony's labour stock goals: every minimum-stock entry of its warehouses for a basic good labourers can make
     * ({@link LabourRules#candidateItems()}; keep from the entry's quantity in stacks, start below it), then the data pack's stockpile goals
     * ({@code labour/stockpile.json}, the default seed) except those naming exactly an item a warehouse entry already sets. Cached until a warehouse or entry changes. Goods a building covers
     * stay in the list; the board does not gather them (see {@link #stockGoalViews()}).
     *
     * @return the goals, warehouse entries first.
     */
    public List<LabourDataListener.StockpileGoal> stockGoals()
    {
        // the goods filter comes from item tags: a /reload with other tags rebuilds the goals
        long signature = com.minecolonies.api.items.ModTags.tagsEpoch;
        for (final IWareHouse wareHouse : sites.warehouses())
        {
            final IMinimumStockModule module = wareHouse.getModule(IMinimumStockModule.class);
            signature = signature * 31 + wareHouse.getID().asLong() * 17 + (module == null ? -1 : module.getMinimumStockRevision());
        }
        final List<LabourDataListener.StockpileGoal> cached = goalsCache;
        if (cached != null && signature == goalsSignature && LabourDataListener.stockpile() == goalsSeed)
        {
            return cached;
        }
        goalsSeed = LabourDataListener.stockpile();
        goalsCache = List.copyOf(buildStockGoals());
        goalsSignature = signature;
        return goalsCache;
    }

    /**
     * {@link #stockGoals()} rebuilt: only when a warehouse, a minimum-stock entry or the data pack seed changed.
     */
    private List<LabourDataListener.StockpileGoal> buildStockGoals()
    {
        final Map<Item, Integer> fromWarehouses = new LinkedHashMap<>();
        Set<Item> basic = null;
        for (final IWareHouse wareHouse : sites.warehouses())
        {
            final IMinimumStockModule module = wareHouse.getModule(IMinimumStockModule.class);
            final Map<ItemStorage, Integer> entries = module == null ? Map.of() : module.getMinimumStock();
            if (entries.isEmpty())
            {
                continue;
            }
            if (basic == null)
            {
                basic = new HashSet<>(LabourRules.candidateItems());
            }
            for (final Map.Entry<ItemStorage, Integer> entry : entries.entrySet())
            {
                final ItemStack stack = entry.getKey().getItemStack();
                if (!stack.isEmpty() && entry.getValue() > 0 && basic.contains(stack.getItem()))
                {
                    fromWarehouses.merge(stack.getItem(), entry.getValue() * stack.getMaxStackSize(), Math::max);
                }
            }
        }
        final List<LabourDataListener.StockpileGoal> goals = new ArrayList<>();
        final Set<String> set = new HashSet<>();
        for (final Map.Entry<Item, Integer> entry : fromWarehouses.entrySet())
        {
            final String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.getKey()).toString();
            set.add(id);
            // start below three quarters of the target (as a minimum-stock refill), so one item taken out does not send a labourer for one item
            final int target = entry.getValue();
            goals.add(new LabourDataListener.StockpileGoal(MIN_STOCK_GOAL + id, id, target - Math.max(1, target / 4), target));
        }
        for (final LabourDataListener.StockpileGoal goal : LabourDataListener.stockpile())
        {
            if (!set.contains(goal.item()))
            {
                goals.add(goal);
            }
        }
        return goals;
    }

    /**
     * Whether the board works on a goal: it starts once the stock drops below the goal's start and stops once the stock reaches its target.
     *
     * @param goal   the goal.
     * @param stock  the matching stock nobody holds.
     * @param active whether it was active.
     * @return whether it is active now.
     */
    public static boolean goalActive(final LabourDataListener.StockpileGoal goal, final int stock, final boolean active)
    {
        return active ? stock < goal.target() : stock < goal.startBelow();
    }

    /**
     * The lot a labourer is offered for an active goal: what is missing after what is on its way, at most {@link #MAX_LOT}; a warehouse goal offers nothing below
     * a quarter of its target (at most 16), so labourers are not sent for one or two items.
     *
     * @param goal     the goal.
     * @param stock    the matching stock nobody holds.
     * @param inFlight what labourers are bringing for it now.
     * @return the lot, 0 for none.
     */
    public static int goalLot(final LabourDataListener.StockpileGoal goal, final int stock, final int inFlight)
    {
        final int lot = Math.min(MAX_LOT, goal.target() - stock - inFlight);
        final int least = goal.id().startsWith(MIN_STOCK_GOAL) ? Math.max(1, Math.min(16, goal.target() / 4)) : 1;
        return lot >= least ? lot : 0;
    }

    /**
     * One stock goal as a panel shows it.
     *
     * @param goal      the goal (id, item or #tag, start below, target).
     * @param stock     the matching stock in the warehouses nobody holds.
     * @param inFlight  what labourers are bringing for it now.
     * @param active    whether the board works on it (it dropped below its start and has not reached its target yet).
     * @param covered   whether every matching good is made by a building of the colony, so labourers leave it alone.
     * @param warehouse whether it comes from a warehouse's minimum-stock entry (else from the data pack seed).
     */
    public record StockGoalView(LabourDataListener.StockpileGoal goal, int stock, int inFlight, boolean active, boolean covered, boolean warehouse)
    {
    }

    /**
     * @return the stock goals with their stock and state, for display; server thread.
     */
    public List<StockGoalView> stockGoalViews()
    {
        final List<StockGoalView> views = new ArrayList<>();
        for (final LabourDataListener.StockpileGoal goal : stockGoals())
        {
            int stock = 0;
            boolean covered = true;
            for (final Item item : LabourRules.candidateItems())
            {
                if (LabourDataListener.matches(goal.item(), item))
                {
                    stock += stock(item);
                    covered &= coverage.covered(item);
                }
            }
            int inFlight = 0;
            for (final LabourClaim claim : byCitizen.values())
            {
                if (goal.id().equals(claim.goalId()))
                {
                    inFlight += claim.amount();
                }
            }
            views.add(new StockGoalView(goal, stock, inFlight, goalActive.getOrDefault(goal.id(), false), covered, goal.id().startsWith(MIN_STOCK_GOAL)));
        }
        return views;
    }

    @Nullable
    private LabourClaim offerStockpile(final ICitizenData citizen, final LabourEnv env, final Map<Item, Integer> inventory, final long now)
    {
        if (sites.warehouses().isEmpty() || claimsOf(LabourClaim.Source.STOCKPILE) >= Math.max(1, (allowed() + 1) / 2))
        {
            return null;
        }
        for (final LabourDataListener.StockpileGoal goal : stockGoals())
        {
            final List<Item> items = new ArrayList<>();
            int stock = 0;
            for (final Item item : LabourRules.candidateItems())
            {
                if (LabourDataListener.matches(goal.item(), item))
                {
                    stock += stock(item);
                    if (!coverage.covered(item))
                    {
                        items.add(item);
                    }
                }
            }
            final boolean active = goalActive(goal, stock, goalActive.getOrDefault(goal.id(), false));
            goalActive.put(goal.id(), active);
            if (!active || items.isEmpty() || claimsOnGoal(goal.id()) >= 2)
            {
                continue;
            }
            int inFlight = 0;
            for (final LabourClaim claim : byCitizen.values())
            {
                if (goal.id().equals(claim.goalId()))
                {
                    inFlight += claim.amount();
                }
            }
            final int lot = goalLot(goal, stock, inFlight);
            if (lot <= 0)
            {
                continue;
            }
            items.sort(Comparator.comparingInt(LabourBoard::tier));
            for (final Item item : items)
            {
                if (LabourPlanner.next(env, item, lot, inventory) instanceof LabourPlanner.Blocked<Item>)
                {
                    continue;
                }
                final BlockPos warehouse = sites.warehouses().get(0).getID();
                final LabourClaim claim = new LabourClaim(LabourClaim.Source.STOCKPILE, null, goal.id(), citizen.getId(), item, lot, warehouse, now, now + LabourSettings.leaseTicks());
                register(claim);
                return claim;
            }
        }
        return null;
    }

    /**
     * Round 7 (Sol H2): whether a directed order may take these citizens now: the free places of the colony and the world (every claim and pending directed
     * citizen counted, the places the chosen give back by leaving their own claims not) and the jobless share leave room for each of them.
     *
     * @param chosen the citizens.
     * @return true if they fit.
     */
    boolean roomForDirected(final List<ICitizenData> chosen)
    {
        int freed = 0;
        int joblessFreed = 0;
        for (final ICitizenData citizen : chosen)
        {
            final LabourClaim claim = byCitizen.get(citizen.getId());
            if (claim != null && takesPlace(claim))
            {
                freed++;
                if (!claim.byWorker() && (claim.source() == LabourClaim.Source.REQUEST || claim.source() == LabourClaim.Source.STOCKPILE))
                {
                    joblessFreed++;
                }
            }
        }
        final int jobless = claimsOf(LabourClaim.Source.REQUEST) + claimsOf(LabourClaim.Source.STOCKPILE) - joblessFreed
                              + Math.max(claimsOf(LabourClaim.Source.DIRECTED), directed.assigned());
        if (places() - freed + chosen.size() > capacity())
        {
            full();
            return false;
        }
        if (jobless + chosen.size() > allowed())
        {
            return false;
        }
        synchronized (ACTIVE)
        {
            // the colony got its places: it waits no more
            WANTS.remove(key());
        }
        return true;
    }

    /**
     * Forgets which colonies wait for a labour place (round 7, N47). For the tests: a test colony refused a place does not hold back the colonies of the next test.
     */
    public static void forgetWaiting()
    {
        synchronized (ACTIVE)
        {
            WANTS.clear();
        }
    }

    /**
     * A claim for a citizen on a manager's directed order. The order reserved his place when it was accepted ({@link #roomForDirected}); the claim takes it over and
     * is refused when the claims alone would pass the colony's or the world's places (another colony took them while he had none yet, a reload or a lower cap).
     * Holds no ledger entry: the goods are gathered, never taken from stock.
     *
     * @return the claim, or null when he holds one already, no place is free or the order has no destination.
     */
    @Nullable
    public LabourClaim claimDirected(final ICitizenData citizen, final DirectedOrder order)
    {
        if (byCitizen.containsKey(citizen.getId()) || !order.active() || !order.has(citizen.getId()))
        {
            return null;
        }
        if (claimedPlaces() + 1 > capacity(false))
        {
            full();
            directed.blocked(order, citizen.getId(), DirectedOrder.Why.NO_PLACE, 200L);
            return null;
        }
        final IBuilding destination = directed.destinationOf(order);
        if (destination == null)
        {
            directed.blocked(order, citizen.getId(), DirectedOrder.Why.NO_DESTINATION, 200L);
            return null;
        }
        final long now = now();
        final LabourClaim claim = new LabourClaim(LabourClaim.Source.DIRECTED, null, null, citizen.getId(), DirectedOrders.shown(order.resource()), order.amount(),
          destination.getID(), now, now + LabourSettings.leaseTicks(), order.id());
        register(claim);
        return claim;
    }

    /**
     * @return how many of the request's goods labour handed in at its hut: what this board counted, or (after a reload, when that count is gone) what the ledger
     *   still holds there for the request.
     */
    private int deliveredFor(final IToken<?> request)
    {
        return Math.max(delivered.getOrDefault(request, 0), com.minecolonies.core.colony.requestsystem.RsCourierHooks.handedOutFor(colony.getRequestManager(), request));
    }

    // ------------------------------------------------------------------ cargo (round 6 teamwork)

    /**
     * A stock goal id for the hand-in of carried goods whose request is gone: they go to a warehouse.
     */
    public static final String CARGO_GOAL = "cargo:";

    /**
     * Hand-ins of carried goods that did not get there, by citizen (after {@link #CARGO_TRIES} the record is dropped and he keeps the goods, as before).
     */
    private final Map<Integer, Integer> cargoFailures = new HashMap<>();
    private static final int CARGO_TRIES = 3;

    private LabourCargo cargo()
    {
        return colony.getLabourData().cargo();
    }

    /**
     * @return how many of the good he carries, counted as the claim counts them when there is one.
     */
    private static int carried(final ICitizenData citizen, final java.util.function.Predicate<ItemStack> accepts)
    {
        return InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), accepts);
    }

    /**
     * Round 7 teamwork (N45): what citizens carry on record for a request while they hold no claim (a helper recalled by his own work before the hand-in, a
     * labourer called away): those goods are handed in at the next idle minute and count for the request like a live lot, at most one lot each.
     *
     * @param token   the request.
     * @param exclude a citizen not counted (the one who asks now).
     * @return the amount.
     */
    int carriedOnRecordFor(final IToken<?> token, final int exclude)
    {
        int n = 0;
        for (final Map.Entry<Integer, LabourCargo.Entry> e : cargo().all().entrySet())
        {
            final LabourCargo.Entry entry = e.getValue();
            if (e.getKey() == exclude || entry.request() == null || !entry.request().equals(token.getIdentifier()) || byCitizen.containsKey(e.getKey()))
            {
                continue;
            }
            final ICitizenData holder = colony.getCitizenManager().getCivilian(e.getKey());
            if (holder != null)
            {
                n += Math.min(MAX_LOT, carried(holder, s -> s.is(entry.item())));
            }
        }
        return n;
    }

    /**
     * @param citizenId a citizen.
     * @return what he carries goods for, or null (for the views and the tests).
     */
    @Nullable
    public LabourCargo.Entry cargoOf(final int citizenId)
    {
        return cargo().of(citizenId);
    }

    /**
     * The hand-in of goods the citizen carries from an earlier claim (he was called away, or the world was reloaded): a claim of exactly what he carries, for the
     * request while it waits and has room left in it (the lots of other labourers and what is in the hut already counted), else for a warehouse. It plans nothing,
     * takes no labour place and has no cooldown.
     *
     * @return the claim, or null when he carries nothing on record (the record goes) or nowhere takes it now.
     */
    @Nullable
    private LabourClaim cargoClaim(final ICitizenData citizen)
    {
        final LabourCargo.Entry entry = cargo().of(citizen.getId());
        if (entry == null)
        {
            return null;
        }
        final long now = now();
        if (entry.request() != null)
        {
            final IToken<?> token = new com.minecolonies.api.colony.requestsystem.token.StandardToken(entry.request());
            final Candidate candidate = stillWaiting(token) ? candidate(token) : null;
            if (candidate != null && candidate.hut().equals(entry.destination()))
            {
                final ItemStack exact = candidate.exact();
                final int held = carried(citizen, s -> exact.isEmpty() ? s.is(entry.item()) : ItemStack.isSameItemSameComponents(s, exact));
                int others = 0;
                for (final LabourClaim claim : byCitizen.values())
                {
                    if (token.equals(claim.request()))
                    {
                        others += claim.amount();
                    }
                }
                final int lot = Math.min(Math.min(held, MAX_LOT), candidate.count() - deliveredFor(token) - others);
                if (lot > 0)
                {
                    final LabourClaim claim = new LabourClaim(LabourClaim.Source.REQUEST, token, null, citizen.getId(), entry.item(), lot, candidate.hut(), now,
                      now + LabourSettings.leaseTicks(), 0, exact);
                    claim.markCargo();
                    register(claim);
                    final ReservationLedger ledger = RsFlags.reservations() ? RsAccess.ledger(colony.getRequestManager()) : null;
                    if (ledger != null)
                    {
                        ledger.reserveSpace(candidate.hut(), new ItemStorage(exact.isEmpty() ? new ItemStack(entry.item()) : exact), lot, claim.token(),
                          "labourer " + citizen.getName(), ReservationReason.LABOUR, now);
                    }
                    setReason(token, WaitReason.AWAITING_LABOUR);
                    LabourStats.log("citizen {} hands in {}x {} he carried for request {}", citizen.getId(), lot, entry.item(), token);
                    return claim;
                }
            }
        }
        final int held = carried(citizen, s -> s.is(entry.item()));
        if (held <= 0)
        {
            forgetCargo(citizen.getId());
            return null;
        }
        if (sites.warehouses().isEmpty())
        {
            return null;
        }
        // the request was served or is gone, or the rest of it is in other hands: the goods go to the nearest warehouse
        final LabourClaim claim = new LabourClaim(LabourClaim.Source.STOCKPILE, null, CARGO_GOAL + entry.item(), citizen.getId(), entry.item(), held,
          sites.warehouses().get(0).getID(), now, now + LabourSettings.leaseTicks());
        claim.markCargo();
        register(claim);
        LabourStats.log("citizen {} takes {}x {} he carried to a warehouse", citizen.getId(), held, entry.item());
        return claim;
    }

    private void forgetCargo(final int citizenId)
    {
        cargoFailures.remove(citizenId);
        if (cargo().remove(citizenId))
        {
            colony.markDirty();
        }
    }

    /**
     * The claim ended: what its citizen still carries of its goods stays on record (for the hand-in the next time he is free), unless the goods were handed in, he
     * carries none, he is gone, or the hand-ins failed too often.
     */
    private void cargoAfter(final LabourClaim claim, final boolean success)
    {
        if (claim.source() != LabourClaim.Source.REQUEST && claim.source() != LabourClaim.Source.STOCKPILE)
        {
            return;
        }
        final ICitizenData citizen = colony.getCitizenManager().getCivilian(claim.citizenId());
        if (success || citizen == null || carried(citizen, claim::accepts) <= 0)
        {
            forgetCargo(claim.citizenId());
            return;
        }
        if (claim.cargo() && cargoFailures.merge(claim.citizenId(), 1, Integer::sum) >= CARGO_TRIES)
        {
            LabourStats.log("citizen {} could not hand in what he carries {} times: he keeps it", claim.citizenId(), CARGO_TRIES);
            forgetCargo(claim.citizenId());
        }
    }

    private void register(final LabourClaim claim)
    {
        final LabourClaim old = byCitizen.get(claim.citizenId());
        if (old != null && old != claim)
        {
            // one claim per citizen: an older one is given back first (no caller makes two, this keeps the counts right if one ever does)
            release(old, false, false);
        }
        byCitizen.put(claim.citizenId(), claim);
        LabourStats.log("claim made by citizen {} for {}x {} ({}{})", claim.citizenId(), claim.amount(), claim.target(), claim.source(), claim.cargo() ? ", cargo" : "");
        LabourStats.CLAIMS.incrementAndGet();
        if (takesPlace(claim))
        {
            syncPlaces();
            synchronized (ACTIVE)
            {
                // the colony got a place: it waits no more
                WANTS.remove(key());
            }
        }
        if (claim.source() == LabourClaim.Source.REQUEST || claim.source() == LabourClaim.Source.STOCKPILE)
        {
            // what he gathers for it stays tied to it in the save (a claim is not saved)
            final LabourCargo.Entry kept = cargo().of(claim.citizenId());
            final java.util.UUID request = claim.request() == null ? null : claim.request().getIdentifier() instanceof java.util.UUID id ? id : null;
            if (cargo().put(claim.citizenId(), new LabourCargo.Entry(request, claim.target(), claim.destination(), kept != null && claim.cargo() ? kept.since() : now())))
            {
                colony.markDirty();
            }
        }
    }

    // ------------------------------------------------------------------ the life of a claim

    /**
     * The labourer renews the lease of the claim (he finished an action).
     */
    public void heartbeat(final LabourClaim claim)
    {
        claim.renew(now() + LabourSettings.leaseTicks());
    }

    /**
     * Whether the claim is still held by the board (not expired, not released).
     */
    public boolean alive(final LabourClaim claim)
    {
        return byCitizen.get(claim.citizenId()) == claim;
    }

    /**
     * @return whether the citizen has stood for {@link #FROZEN_TICKS} where entities do not tick (the clock starts on the first such tick).
     */
    private boolean frozen(final int citizenId, final ICitizenData citizen, final long now)
    {
        final var entity = citizen.getEntity().orElse(null);
        if (entity == null || !(entity.level() instanceof net.minecraft.server.level.ServerLevel level) || LabourSites.simulated(level, entity.blockPosition()))
        {
            frozenSince.remove(citizenId);
            return false;
        }
        return frozenUntilNow(frozenSince.computeIfAbsent(citizenId, k -> now), now);
    }

    /**
     * @return whether the hut the claim delivers to (the colony centre if it has none) stands where entities tick.
     */
    private boolean destinationTicks(final LabourClaim claim, final ICitizenData citizen)
    {
        final var entity = citizen.getEntity().orElse(null);
        final java.util.function.Predicate<BlockPos> ticking = entity != null && entity.level() instanceof net.minecraft.server.level.ServerLevel level
                                                                 ? p -> LabourSites.simulated(level, p) : null;
        return destinationTicks(claim.destination(), colony.getCenter(), ticking);
    }

    /**
     * Whether the release of a frozen claim counts against its request (back-off): only when the citizen walked out of the ticking area while his hut still
     * ticks, not when the whole area left the simulation (builder review c5 C5-2).
     *
     * @param destination the hut the claim delivers to, null if none.
     * @param centre      the colony centre, used when there is no hut.
     * @param ticking     whether entities tick at a position in the citizen's level; null if he has no entity or level (counts as not ticking).
     * @return whether the hut (else the centre) ticks.
     */
    static boolean destinationTicks(@Nullable final BlockPos destination, @Nullable final BlockPos centre, @Nullable final java.util.function.Predicate<BlockPos> ticking)
    {
        final BlockPos at = destination != null ? destination : centre;
        return ticking != null && at != null && ticking.test(at);
    }

    /**
     * @return whether a citizen not ticking since {@code since} is frozen at {@code now}.
     */
    static boolean frozenUntilNow(final long since, final long now)
    {
        return now - since >= FROZEN_TICKS;
    }

    /**
     * Whether the claim was given back because its citizen stopped ticking (asked once by the session that held it: it ends neutrally; the request is on back-off only if he walked out while his hut still ticks).
     */
    public boolean releasedUnticked(final LabourClaim claim)
    {
        return releasedFrozen.remove(claim);
    }

    /**
     * Ends a claim: its ledger entries go, the citizen rests, and a failed claim puts the request on back-off.
     *
     * @param claim    the claim.
     * @param success  whether the goods were handed in.
     * @param penalize whether a failure counts against the request (back-off); a labourer who was called away by his needs or a job does not.
     */
    public void release(final LabourClaim claim, final boolean success, final boolean penalize)
    {
        if (!alive(claim))
        {
            return;
        }
        LabourStats.log("claim of citizen {} released: success {} penalize {}", claim.citizenId(), success, penalize);
        byCitizen.remove(claim.citizenId());
        frozenSince.remove(claim.citizenId());
        // the trees, blocks, posts and items the claim's session had taken are free again, however the claim ends (a lease that ran out included)
        final Runnable sites = sitesOf.remove(claim);
        if (sites != null)
        {
            sites.run();
        }
        (success ? LabourStats.CLAIMS_OK : LabourStats.CLAIMS_FAILED).incrementAndGet();
        if (takesPlace(claim))
        {
            syncPlaces();
        }
        cargoAfter(claim, success);
        final long now = now();
        final ReservationLedger ledger = RsAccess.ledger(colony.getRequestManager());
        if (ledger != null)
        {
            ledger.release(claim.token(), now);
        }
        cooldown.put(claim.citizenId(), now + restAfter(success));
        final IToken<?> request = claim.request();
        if (request != null)
        {
            if (success)
            {
                failures.remove(request);
                notBefore.remove(request);
            }
            else
            {
                if (penalize)
                {
                    backoff(request, now);
                }
                if (stillWaiting(request) && claimsOn(request) == 0 && !blockedWhy.containsKey(request))
                {
                    setReason(request, WaitReason.NO_SOURCE);
                }
            }
        }
    }

    /**
     * The planner found no way to go on with the claim: the request shows why labour is blocked, and waits before the next try.
     */
    public void planFailed(final LabourClaim claim, final LabourPlanner.Why why)
    {
        LabourStats.log("plan failed for {}: {}", claim.target(), why);
        if (claim.request() != null && alive(claim))
        {
            blocked(claim.request(), why);
        }
    }

    /**
     * @return whether the citizen is away for his needs now (eating, sleeping, sick, mourning, fleeing): his job AI does not run and cannot renew a lease.
     */
    public static boolean awayForNeeds(final ICitizenData citizen)
    {
        if (!(citizen.getEntity().orElse(null) instanceof com.minecolonies.core.entity.citizen.EntityCitizen entity))
        {
            return false;
        }
        final var state = entity.getCitizenAI().getState();
        return state == com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState.EATING
                 || state == com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState.SLEEP
                 || state == com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState.SICK
                 || state == com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState.MOURN
                 || state == com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState.FLEE;
    }

    /**
     * @return the game time before which labour does not take the request again (its back-off after failures), or 0 when it is on none.
     */
    public long backoffUntil(final IToken<?> request)
    {
        return notBefore.getOrDefault(request, 0L);
    }

    private void backoff(final IToken<?> request, final long now)
    {
        final int n = failures.merge(request, 1, Integer::sum);
        notBefore.put(request, now + (long) Math.min(BACKOFF_MAX, (long) BACKOFF_FIRST << Math.min(n - 1, 5)));
    }

    /**
     * A request that could not be planned: on back-off, and it shows that labour is blocked and why.
     */
    private void blocked(final IToken<?> request, final LabourPlanner.Why why)
    {
        final long now = now();
        backoff(request, now);
        if (blockedWhy.put(request, why) != why)
        {
            setReason(request, WaitReason.LABOUR_BLOCKED);
        }
    }

    /**
     * @param request a request token.
     * @return why labour is blocked on the request, or null.
     */
    @Nullable
    public LabourPlanner.Why blockedWhy(final IToken<?> request)
    {
        return blockedWhy.get(request);
    }

    private void setReason(final IToken<?> token, final WaitReason reason)
    {
        final IRequestManager manager = colony.getRequestManager();
        if (manager == null)
        {
            return;
        }
        try
        {
            final IRequest<?> request = manager.getRequestForToken(token);
            final RequestWaitTracker tracker = RequestWaitTracker.of(manager);
            if (request != null && tracker != null)
            {
                tracker.hint(token, reason);
                tracker.setReason(request, reason);
            }
        }
        catch (final IllegalArgumentException ignored)
        {
            // the request is gone
        }
    }

    /**
     * Goods were handed in at the hut of the request: when enough are there, the request is woken so that the building's own resolver takes it.
     *
     * @param claim the claim.
     * @param n     how many were put into the hut.
     */
    public void handedIn(final LabourClaim claim, final int n)
    {
        if (n <= 0)
        {
            return;
        }
        // the goods are in the hut: the room the claim held there is used now (the claim lives on while he takes his leftovers to a warehouse)
        final ReservationLedger ledger = RsAccess.ledger(colony.getRequestManager());
        if (ledger != null)
        {
            ledger.commit(claim.token(), ReservationKind.SPACE, now());
        }
        final IToken<?> request = claim.request();
        if (request == null)
        {
            return;
        }
        final ReservationLedger holds = RsFlags.reservations() ? ledger : null;
        if (holds != null && !(colony.getServerBuildingManager().getBuilding(claim.destination()) instanceof IWareHouse))
        {
            // round 6 teamwork: what he put into the hut is the request's (as a courier's delivery is): another request of the hut cannot be handed it, and the
            // rest is asked for once, also after a reload (deliveredFor)
            holds.reserveStock(claim.destination(), null, new ItemStorage(claim.exact().isEmpty() ? new ItemStack(claim.target()) : claim.exact()), n, request,
              "labourer", ReservationReason.BUILDING_HANDOUT, now());
        }
        LabourStats.HANDED_IN.addAndGet(n);
        final int total = delivered.merge(request, n, Integer::sum);
        final Candidate candidate = candidate(request);
        if (candidate == null)
        {
            wake(request);
            return;
        }
        if (total >= candidate.count() || (claimsOn(request) <= 1 && total >= candidate.minimum()))
        {
            wake(request);
        }
    }

    private void wake(final IToken<?> request)
    {
        final IRequestManager manager = colony.getRequestManager();
        if (manager == null)
        {
            return;
        }
        try
        {
            if (manager.getRetryingRequestResolver() instanceof StandardRetryingRequestResolver retrying && retrying.isHolding(request))
            {
                retrying.expedite(request);
            }
            else if (manager.getPlayerResolver() instanceof StandardPlayerRequestResolver player && player.isHolding(request))
            {
                manager.reassignRequest(request, ImmutableList.of());
            }
            pendingWake.put(request, now() + 400L);
        }
        catch (final RuntimeException e)
        {
            com.minecolonies.api.util.Log.getLogger().warn("Could not wake the request " + request, e);
        }
    }

    // ------------------------------------------------------------------ the once a second tick

    /**
     * Once a second: gives back expired claims, keeps the reasons of the served requests, forgets what is gone, and sweeps the ledger.
     */
    public void tick()
    {
        final long now = now();
        directed.tick();
        // (pending directed citizens restored from the save, an order that ended: the other colonies see this colony's places)
        syncPlaces();
        shafts.tick();
        if (!colony.getLabourData().hutDebts().isEmpty() && (now - lastDebtSweep >= 100L || now < lastDebtSweep))
        {
            lastDebtSweep = now;
            if (colony.getLabourData().hutDebts().sweep(colony, now))
            {
                colony.markDirty();
            }
        }
        if (!byCitizen.isEmpty())
        {
            for (final LabourClaim claim : new ArrayList<>(byCitizen.values()))
            {
                final ICitizenData citizen = colony.getCitizenManager().getCivilian(claim.citizenId());
                if (now > claim.leaseUntil() || citizen == null || citizen.getEntity().isEmpty())
                {
                    LabourStats.CLAIMS_EXPIRED.incrementAndGet();
                    // a lease that ran out while he ate, slept, was sick, mourned or fled is no failure of the request: given back without back-off
                    final boolean needs = citizen != null && awayForNeeds(citizen);
                    if (needs)
                    {
                        LabourStats.log("claim of citizen {} ran out during his needs: no back-off", claim.citizenId());
                    }
                    release(claim, false, !needs);
                    continue;
                }
                if (!claim.byWorker() && (claim.source() == LabourClaim.Source.REQUEST || claim.source() == LabourClaim.Source.STOCKPILE) && citizen.getJob() != null)
                {
                    // a jobless labourer got a job: the claim goes at once (his session ends by itself too); what he carries stays on record
                    LabourStats.log("claim of citizen {} given back: he got a job", claim.citizenId());
                    release(claim, false, false);
                    continue;
                }
                if (frozen(claim.citizenId(), citizen, now))
                {
                    // he stands where entities do not tick: his session cannot end by itself, so the claim goes back now; his own session still ends
                    // neutrally when he ticks again (releasedUnticked). He walked out of the ticking area while his hut still ticks: the request goes on the
                    // normal back-off, so the same site is not picked again at once and he does not walk out and freeze again (builder review c4). The hut
                    // stopped ticking too (a player walked away): no failure of the request, no back-off (builder review c5 C5-2)
                    final boolean walkedOut = destinationTicks(claim, citizen);
                    LabourStats.log("claim of citizen {} given back: he stopped ticking at {} ({})", claim.citizenId(), citizen.getEntity().get().blockPosition().toShortString(),
                      walkedOut ? "walked out, back-off" : "area left the simulation, no back-off");
                    releasedFrozen.add(claim);
                    release(claim, false, walkedOut);
                }
            }
        }
        if (now - lastReassert >= 100L)
        {
            // the request system names its own reason whenever it parks a request again; what labour knows about its own requests is said again
            lastReassert = now;
            for (final LabourClaim claim : byCitizen.values())
            {
                if (claim.request() != null && stillWaiting(claim.request()))
                {
                    setReason(claim.request(), WaitReason.AWAITING_LABOUR);
                }
            }
            for (final IToken<?> token : new ArrayList<>(blockedWhy.keySet()))
            {
                if (claimsOn(token) == 0 && stillWaiting(token))
                {
                    setReason(token, WaitReason.LABOUR_BLOCKED);
                }
            }
        }
        if (!pendingWake.isEmpty())
        {
            for (final Map.Entry<IToken<?>, Long> entry : new ArrayList<>(pendingWake.entrySet()))
            {
                if (now >= entry.getValue())
                {
                    pendingWake.remove(entry.getKey());
                    if (stillWaiting(entry.getKey()) && claimsOn(entry.getKey()) == 0)
                    {
                        // the goods are in the hut but the request is not served (its stock is promised elsewhere): do not bring more
                        delivered.remove(entry.getKey());
                        notBefore.put(entry.getKey(), now + 3000L);
                    }
                    else if (!stillWaiting(entry.getKey()))
                    {
                        delivered.remove(entry.getKey());
                    }
                }
            }
        }
        if (!seenSweep || now - lastSweep >= 1200L)
        {
            seenSweep = true;
            lastSweep = now;
            final ReservationLedger ledger = RsAccess.ledger(colony.getRequestManager());
            if (ledger != null)
            {
                ledger.sweepStaleLabour(this::holds, now, 400L);
            }
            notBefore.keySet().removeIf(token -> !stillWaiting(token));
            firstSeen.keySet().removeIf(token -> !stillWaiting(token));
            failures.keySet().removeIf(token -> !stillWaiting(token));
            blockedWhy.keySet().removeIf(token -> !stillWaiting(token));
            delivered.keySet().removeIf(token -> !stillWaiting(token) && !pendingWake.containsKey(token));
            cooldown.values().removeIf(until -> until < now);
            noOffer.values().removeIf(until -> until < now);
            selfSupply.sweep(now);
            shafts.sweep(byCitizen::containsKey);
            for (final Map.Entry<Integer, LabourCargo.Entry> entry : cargo().all().entrySet())
            {
                // nobody left to carry it, or he carries none of it any more (and holds no claim that is still gathering it)
                final ICitizenData citizen = colony.getCitizenManager().getCivilian(entry.getKey());
                if (citizen == null || (!byCitizen.containsKey(entry.getKey()) && carried(citizen, st -> st.is(entry.getValue().item())) <= 0))
                {
                    forgetCargo(entry.getKey());
                }
            }
        }
    }
}
