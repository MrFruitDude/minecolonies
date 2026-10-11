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
 *   output. A pickup the player forced ("pick up now", priority at the building maximum) is {@link #NORMAL}.</li>
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
        final boolean minimumStock = asked instanceof MinimumStack;
        final boolean sweep = asked instanceof Pickup pickup && pickup.getPriority() < AbstractDeliverymanRequestable.getMaxBuildingPriority(true);
        if (minimumStock || sweep)
        {
            return BACKGROUND;
        }
        if (asked instanceof Pickup)
        {
            return NORMAL;
        }
        return classify(false, false, workerBlocked(manager, root));
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
