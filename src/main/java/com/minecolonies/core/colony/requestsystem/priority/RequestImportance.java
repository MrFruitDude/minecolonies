package com.minecolonies.core.colony.requestsystem.priority;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.MinimumStack;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.AbstractDeliverymanRequestable;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Pickup;
import com.minecolonies.api.colony.requestsystem.requester.IRequester;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * RS3: how badly a courier task is needed, after Anno's split of "a worker stands idle" from "keep the shelf full".
 * <p>
 * The importance belongs to the <em>root</em> request of a courier task, the one somebody asked for. A delivery is a
 * child of the request it serves (and of the crafting requests below that), so {@link #of} walks the parent chain up to
 * the request that has none and decides on that one:
 * <ul>
 *   <li>{@link #BACKGROUND}: the root asks for a {@link MinimumStack}, i.e. a minimum-stock top-up of a warehouse, post box
 *   or any other building with the minimum-stock module; or the root is a {@link Pickup}, the periodic sweep of a building's
 *   output. A sweep that aged to the building maximum is {@link #NORMAL}.</li>
 *   <li>Round 13 (L1): a pickup is {@link #URGENT} when it was forced (a full hut whose worker could not dump, the player's
 *   "pick up now", a stash; see {@link #isForcedPickup}) or when the hut is full right now: its worker stands still until the
 *   courier comes.</li>
 *   <li>{@link #URGENT}: the root was asked by a building that has at least one assigned worker (a
 *   {@link WorkerBuildingModule} with a citizen): the builder's current bucket, the crafter's current recipe, a miner's pickaxe.
 *   That worker's AI is parked until the request is served.</li>
 *   <li>{@link #NORMAL}: everything else (requests of buildings nobody works in, the home buildings, requests whose root
 *   or requester building cannot be found).</li>
 * </ul>
 * The derivation reads existing request state only, so nothing is stored on requests, and it allocates nothing but the
 * parent walk's lookups.
 */
public enum RequestImportance
{
    /**
     * A worker is blocked on it right now.
     */
    URGENT(0),
    /**
     * An ordinary request.
     */
    NORMAL(1),
    /**
     * Top-ups and sweeps: nothing stands still without them.
     */
    BACKGROUND(2);

    /**
     * Longest parent chain followed (a guard against a corrupt cycle; real chains are a handful deep).
     */
    private static final int MAX_DEPTH = 32;

    private final int rank;

    RequestImportance(final int rank)
    {
        this.rank = rank;
    }

    /**
     * @return the rank; a lower rank is served first.
     */
    public int rank()
    {
        return rank;
    }

    /**
     * @return the language key of the importance's name.
     */
    @NotNull
    public String translationKey()
    {
        return "com.minecolonies.coremod.request.importance." + name().toLowerCase(Locale.ROOT);
    }

    /**
     * The decision itself, on facts.
     *
     * @param minimumStock the root asks for a minimum-stock top-up.
     * @param sweep        the root is an unforced pickup sweep.
     * @param workerBlocked the requester building has an assigned worker.
     * @return the importance.
     */
    @NotNull
    public static RequestImportance classify(final boolean minimumStock, final boolean sweep, final boolean workerBlocked)
    {
        if (minimumStock || sweep)
        {
            return BACKGROUND;
        }
        return workerBlocked ? URGENT : NORMAL;
    }

    /**
     * The importance of a courier task (a delivery or a pickup) or of any request.
     *
     * @param manager the colony's request manager.
     * @param request the request.
     * @return the importance, never null.
     */
    @NotNull
    public static RequestImportance of(@NotNull final IRequestManager manager, @NotNull final IRequest<?> request)
    {
        IRequest<?> root = request;
        for (int depth = 0; depth < MAX_DEPTH; depth++)
        {
            final IToken<?> parentToken = root.getParent();
            if (parentToken == null)
            {
                break;
            }
            final IRequest<?> parent = manager.getRequestForToken(parentToken);
            if (parent == null)
            {
                break;
            }
            root = parent;
        }

        final Object asked = root.getRequest();
        if (asked instanceof MinimumStack)
        {
            return BACKGROUND;
        }
        if (asked instanceof Pickup pickup)
        {
            return ofPickup(pickup.getPriority(), isForcedPickup(pickup) || hutFull(manager, root));
        }
        return classify(false, false, workerBlocked(manager, root));
    }

    /**
     * Round 13 (L1): the importance of a pickup, on facts.
     *
     * @param priority the pickup's priority (aging raises it).
     * @param blocking the pickup was forced, or the hut is full now.
     * @return URGENT when blocking, NORMAL for a sweep that aged to the building maximum, else BACKGROUND.
     */
    @NotNull
    public static RequestImportance ofPickup(final int priority, final boolean blocking)
    {
        if (blocking)
        {
            return URGENT;
        }
        return priority >= AbstractDeliverymanRequestable.getMaxBuildingPriority(true) ? NORMAL : BACKGROUND;
    }

    /**
     * Round 13 (L1, B1): a forced pickup carries the player-action priority, which aging never reaches.
     *
     * @param pickup a pickup.
     * @return true when it was forced.
     */
    public static boolean isForcedPickup(@NotNull final Pickup pickup)
    {
        return pickup.getPriority() >= AbstractDeliverymanRequestable.getPlayerActionPriority(true);
    }

    /**
     * @return true when the building that asked for the pickup has no free slot left (its worker cannot dump).
     */
    private static boolean hutFull(final IRequestManager manager, final IRequest<?> root)
    {
        final IColony colony = manager.getColony();
        final IRequester requester = root.getRequester();
        if (colony == null || requester == null || colony.getWorld() == null || colony.getWorld().isClientSide())
        {
            return false;
        }
        return loadedHutFull(colony, colony.getServerBuildingManager().getBuilding(requester.getLocation().getInDimensionLocation()));
    }

    /**
     * Round 13 (c2): a hut counts as full only when it is loaded and its loaded racks have no free slot. Unloaded is checked first, so an
     * unloaded hut never makes a pickup URGENT or {@code HUT_FULL_PICKUP_QUEUED}.
     *
     * @param colony   the colony.
     * @param building the hut, may be null.
     * @return true when it is loaded and full.
     */
    public static boolean loadedHutFull(@NotNull final IColony colony, @Nullable final IBuilding building)
    {
        return building != null && !building.getContainers().isEmpty()
                 && com.minecolonies.api.util.WorldUtil.isBlockLoaded(colony.getWorld(), building.getPosition())
                 && com.minecolonies.api.util.InventoryUtils.isBuildingFull(building);
    }

    private static boolean workerBlocked(final IRequestManager manager, final IRequest<?> root)
    {
        final IColony colony = manager.getColony();
        final IRequester requester = root.getRequester();
        if (colony == null || requester == null)
        {
            return false;
        }
        final IRequester building = colony.getRequesterBuildingForPosition(requester.getLocation().getInDimensionLocation());
        return building instanceof IBuilding b && hasWorker(b);
    }

    /**
     * @param building a building.
     * @return true when a citizen works in it.
     */
    static boolean hasWorker(@Nullable final IBuilding building)
    {
        return building != null && building.hasModule(WorkerBuildingModule.class, module -> !module.getAssignedCitizen().isEmpty());
    }
}
