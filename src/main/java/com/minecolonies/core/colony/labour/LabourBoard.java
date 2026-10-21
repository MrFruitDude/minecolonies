package com.minecolonies.core.colony.labour;

import com.google.common.collect.ImmutableList;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
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
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationReason;
import com.minecolonies.core.colony.requestsystem.resolvers.StandardPlayerRequestResolver;
import com.minecolonies.core.colony.requestsystem.resolvers.StandardRetryingRequestResolver;
import com.minecolonies.core.colony.requestsystem.wait.RequestWaitTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
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
     * Ticks a citizen rests after a claim ended before he takes the next.
     */
    public static final int CITIZEN_COOLDOWN = 600;

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
     * Labourers of all colonies by colony key; the world cap shares what is left.
     */
    private static final Map<String, Integer> ACTIVE = new HashMap<>();

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
            if (claim.source() == source)
            {
                n++;
            }
        }
        return n;
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
        int cap = LabourSettings.total(colony);
        final int worldMax = LabourSettings.worldMax();
        synchronized (ACTIVE)
        {
            final int colonies = Math.max(1, ACTIVE.size() + (ACTIVE.containsKey(key()) ? 0 : 1));
            cap = Math.min(cap, Math.max(1, worldMax / colonies));
        }
        if (worldMax <= 0)
        {
            return 0;
        }
        final int byShare = (int) Math.floor(unemployed * LabourSettings.share(colony));
        return Math.min(cap, Math.max(Math.min(1, unemployed), byShare));
    }

    private boolean capReached()
    {
        // (the citizens on a manager's directed order count against the same caps)
        return claimsOf(LabourClaim.Source.REQUEST) + claimsOf(LabourClaim.Source.STOCKPILE) + Math.max(claimsOf(LabourClaim.Source.DIRECTED), directed.assigned()) >= allowed();
    }

    private void count(final int delta)
    {
        synchronized (ACTIVE)
        {
            final int next = ACTIVE.getOrDefault(key(), 0) + delta;
            if (next <= 0)
            {
                ACTIVE.remove(key());
            }
            else
            {
                ACTIVE.put(key(), next);
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
                    if (!coverage.covered(item))
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
                    if (coverage.covered(wanted.getItem()))
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
        int others = 0;
        for (final LabourClaim claim : byCitizen.values())
        {
            if (candidate.token().equals(claim.request()))
            {
                others += claim.amount();
            }
        }
        final int remaining = candidate.count() - delivered.getOrDefault(candidate.token(), 0) - others;
        if (remaining <= 0)
        {
            return null;
        }
        final int lot = Math.max(1, Math.min(remaining, MAX_LOT));
        Item chosen = null;
        LabourPlanner.Why why = LabourPlanner.Why.NO_RECIPE;
        if (!candidate.exact().isEmpty())
        {
            final LabourPlanner.Result<Item> result = planCut(env, candidate.exact(), lot, 0, inventory);
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
            final LabourPlanner.Result<Item> result = LabourPlanner.next(env, item, lot, inventory);
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
            blocked(candidate.token(), why);
            return null;
        }
        final LabourClaim claim = new LabourClaim(source, candidate.token(), null, citizen.getId(), chosen, lot, candidate.hut(), now, now + LabourSettings.leaseTicks(), 0,
          candidate.exact());
        if (coverage.covered(chosen))
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

    @Nullable
    private LabourClaim offerStockpile(final ICitizenData citizen, final LabourEnv env, final Map<Item, Integer> inventory, final long now)
    {
        if (sites.warehouses().isEmpty() || claimsOf(LabourClaim.Source.STOCKPILE) >= Math.max(1, (allowed() + 1) / 2))
        {
            return null;
        }
        for (final LabourDataListener.StockpileGoal goal : LabourDataListener.stockpile())
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
            boolean active = goalActive.getOrDefault(goal.id(), false);
            if (!active && stock < goal.startBelow())
            {
                active = true;
            }
            else if (active && stock >= goal.target())
            {
                active = false;
            }
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
            final int lot = Math.min(MAX_LOT, goal.target() - stock - inFlight);
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
     * A claim for a citizen on a manager's directed order (the order was checked against the caps when it was accepted). Holds no ledger entry: the goods are gathered,
     * never taken from stock.
     *
     * @return the claim, or null when he holds one already or the order has no destination.
     */
    @Nullable
    public LabourClaim claimDirected(final ICitizenData citizen, final DirectedOrder order)
    {
        if (byCitizen.containsKey(citizen.getId()) || !order.active() || !order.has(citizen.getId()))
        {
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

    private void register(final LabourClaim claim)
    {
        byCitizen.put(claim.citizenId(), claim);
        LabourStats.log("claim made by citizen {} for {}x {} ({})", claim.citizenId(), claim.amount(), claim.target(), claim.source());
        LabourStats.CLAIMS.incrementAndGet();
        count(1);
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
        final BlockPos at = claim.destination() != null ? claim.destination() : colony.getCenter();
        return entity != null && entity.level() instanceof net.minecraft.server.level.ServerLevel level && at != null && LabourSites.simulated(level, at);
    }

    /**
     * Whether the release of a frozen claim counts against its request (back-off): only when the citizen walked out of the ticking area while his hut still
     * ticks, not when the whole area left the simulation (builder review c5 C5-2).
     *
     * @param destinationTicks whether the hut the claim delivers to still ticks.
     */
    static boolean frozenPenalized(final boolean destinationTicks)
    {
        return destinationTicks;
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
        count(-1);
        final long now = now();
        final ReservationLedger ledger = RsAccess.ledger(colony.getRequestManager());
        if (ledger != null)
        {
            ledger.release(claim.token(), now);
        }
        cooldown.put(claim.citizenId(), now + CITIZEN_COOLDOWN);
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
        final IToken<?> request = claim.request();
        if (request == null || n <= 0)
        {
            return;
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
                    release(claim, false, frozenPenalized(walkedOut));
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
        }
    }
}
