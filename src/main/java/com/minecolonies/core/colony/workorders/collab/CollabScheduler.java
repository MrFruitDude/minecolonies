package com.minecolonies.core.colony.workorders.collab;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.core.colony.workorders.WorkManager;
import com.minecolonies.core.entity.ai.workers.util.BuilderStageRules;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides which idle builders help which order, once a second per colony.
 * <p>
 * Rules (the user's): as long as there are fewer orders than builders, spare builders help; when orders are added, helpers hand
 * their work back and lead the new order. Free builders are given orders first, then helpers are taken from the orders they help.
 * Leads are never taken from their order. A helper stays with an order for a while before he is moved on, so builders do not
 * flap between orders.
 */
public final class CollabScheduler
{
    /**
     * Maximum distance between a helper's hut and the site (the builder range).
     */
    private static final double MAX_DISTANCE_SQ = 100 * 100;

    /**
     * Strikes (leases that ran out) after which a helper is sent away.
     */
    private static final int MAX_STRIKES = 2;

    private CollabScheduler()
    {
    }

    /**
     * Housekeeping that runs whether collaboration is on or not: leases that ran out, helpers that cannot help any longer.
     *
     * @param manager the work manager.
     * @param colony  the colony.
     * @param enabled whether collaboration is on; if not, all helpers are sent away.
     */
    public static void maintain(final WorkManager manager, final Colony colony, final boolean enabled)
    {
        if (colony.getWorld() == null)
        {
            return;
        }
        final long now = colony.getWorld().getGameTime();
        for (final IServerWorkOrder serverOrder : new ArrayList<>(manager.getWorkOrders().values()))
        {
            if (!(serverOrder instanceof IBuilderWorkOrder order))
            {
                continue;
            }
            final WorkOrderCollab collab = order.getCollab();
            for (final BlockPos owner : collab.expireLeases(now))
            {
                final WorkOrderCollab.Assistant assistant = collab.getAssistant(owner);
                if (assistant != null && ++assistant.strikes >= MAX_STRIKES)
                {
                    collab.removeAssistant(owner, now + BuilderCollab.stayTicks());
                    colony.markDirty();
                }
            }
            if (collab.getAssistantCount() == 0)
            {
                continue;
            }

            final AbstractBuildingStructureBuilder lead = leadOf(colony, order);
            final int cap = capFor(order);
            final List<BlockPos> huts = collab.getAssistantHuts();
            for (int i = 0; i < huts.size(); i++)
            {
                final BlockPos hutPos = huts.get(i);
                final WorkOrderCollab.Assistant assistant = collab.getAssistant(hutPos);
                final IBuilding building = colony.getServerBuildingManager().getBuilding(hutPos);
                final boolean stayedLongEnough = assistant == null || now - assistant.since >= BuilderCollab.stayTicks();
                boolean remove = !enabled || lead == null || !isUsableHelper(building) || building.equals(lead) || ((AbstractBuildingStructureBuilder) building).hasWorkOrder();
                long cooldown = 0;
                if (!remove && stayedLongEnough)
                {
                    final boolean nothingLeft = collab.hasStageTotals() && collab.getRemainingBlocks() < BuilderCollab.minRemaining();
                    final boolean exhausted = collab.isScanExhausted() && collab.getLeaseCountOf(hutPos) == 0;
                    final boolean overCap = i >= cap;
                    if (nothingLeft || exhausted || overCap)
                    {
                        remove = true;
                        cooldown = now + BuilderCollab.stayTicks();
                    }
                }
                if (remove)
                {
                    collab.removeAssistant(hutPos, cooldown);
                    colony.markDirty();
                }
            }
        }
    }

    /**
     * Preempts helpers for orders that no free builder took, then sends free builders to help.
     *
     * @param manager the work manager.
     * @param colony  the colony.
     */
    public static void assign(final WorkManager manager, final Colony colony)
    {
        if (colony.getWorld() == null)
        {
            return;
        }
        final long now = colony.getWorld().getGameTime();
        preempt(manager, colony, now);
        recruit(manager, colony, now);
    }

    /**
     * Unclaimed orders that no free builder took go to the helper whose leaving costs least.
     */
    private static void preempt(final WorkManager manager, final Colony colony, final long now)
    {
        for (final IServerWorkOrder unclaimed : manager.getOrderedList(o -> !o.isClaimed(), BlockPos.ZERO))
        {
            if (unclaimed.isClaimed())
            {
                continue;
            }
            BuildingBuilder best = null;
            IBuilderWorkOrder bestOrder = null;
            int bestLeases = Integer.MAX_VALUE;
            double bestDistance = Double.MAX_VALUE;
            for (final IServerWorkOrder other : manager.getWorkOrders().values())
            {
                if (!(other instanceof IBuilderWorkOrder helped))
                {
                    continue;
                }
                for (final BlockPos hutPos : helped.getCollab().getAssistantHuts())
                {
                    final IBuilding building = colony.getServerBuildingManager().getBuilding(hutPos);
                    if (!(building instanceof BuildingBuilder hut) || !isUsableHelper(hut) || !unclaimed.canBuild(hut))
                    {
                        continue;
                    }
                    final int leases = helped.getCollab().getLeaseCountOf(hutPos);
                    final double distance = hutPos.distSqr(unclaimed.getLocation());
                    if (leases < bestLeases || (leases == bestLeases && distance < bestDistance))
                    {
                        best = hut;
                        bestOrder = helped;
                        bestLeases = leases;
                        bestDistance = distance;
                    }
                }
            }
            if (best != null)
            {
                // he finishes the block he is at; the lease goes back and what he carries goes back to the lead's hut before he starts
                bestOrder.getCollab().removeAssistant(best.getID(), 0);
                best.setWorkOrder(unclaimed);
                unclaimed.setClaimedBy(best.getID());
                colony.markDirty();
            }
        }
    }

    /**
     * Free builders join the order with the most work left per builder.
     */
    private static void recruit(final WorkManager manager, final Colony colony, final long now)
    {
        final List<BuildingBuilder> free = new ArrayList<>();
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            if (building instanceof BuildingBuilder hut && isUsableHelper(hut) && !hut.hasWorkOrder() && manager.getAssistedOrder(hut.getID()) == null)
            {
                free.add(hut);
            }
        }
        free.sort(Comparator.comparingLong(h -> h.getID().asLong()));

        for (final BuildingBuilder hut : free)
        {
            IBuilderWorkOrder best = null;
            double bestScore = 0;
            for (final IServerWorkOrder candidate : manager.getWorkOrders().values())
            {
                if (!(candidate instanceof IBuilderWorkOrder order) || order.getWorkOrderType() == WorkOrderType.REMOVE)
                {
                    continue;
                }
                final AbstractBuildingStructureBuilder lead = leadOf(colony, order);
                if (lead == null || lead == hut)
                {
                    continue;
                }
                final WorkOrderCollab collab = order.getCollab();
                if (collab.isOnCooldown(hut.getID(), now) || collab.hasAssistant(hut.getID()))
                {
                    continue;
                }
                final var stage = collab.getProgressStage();
                if (stage == null || !BuilderStageRules.isSharedStage(stage) || !collab.hasStageTotals() || order.getIteratorType().isEmpty())
                {
                    continue;
                }
                final int remaining = collab.getRemainingBlocks();
                if (remaining < BuilderCollab.minRemaining() || collab.getAssistantCount() >= capFor(order))
                {
                    continue;
                }
                if (!order.canBuildIgnoringDistance(hut, hut.getPosition(), hut.getBuildingLevel()) || hut.getPosition().distSqr(order.getLocation()) > MAX_DISTANCE_SQ)
                {
                    continue;
                }
                double score = (double) remaining / (1 + collab.getAssistantCount());
                if (!order.getProjectId().isEmpty() && order.getProjectId().equals(hut.getLastProjectId()))
                {
                    // a builder stays with the project he worked on
                    score *= 1.5;
                }
                if (score > bestScore)
                {
                    best = order;
                    bestScore = score;
                }
            }
            if (best != null)
            {
                best.getCollab().addAssistant(hut.getID(), now);
                colony.markDirty();
            }
        }
    }

    /**
     * How many helpers an order gets at most: one per this many blocks left, and at least one.
     */
    static int capFor(final IBuilderWorkOrder order)
    {
        final int remaining = order.getCollab().getRemainingBlocks();
        if (remaining < BuilderCollab.minRemaining())
        {
            return 0;
        }
        final int perHelper = BuilderCollab.blocksPerHelper();
        return Math.min(BuilderCollab.maxHelpers(), Math.max(1, (remaining + perHelper - 1) / perHelper));
    }

    /**
     * The builder whose hut works on the order.
     */
    @Nullable
    private static AbstractBuildingStructureBuilder leadOf(final Colony colony, final IBuilderWorkOrder order)
    {
        if (!order.isClaimed())
        {
            return null;
        }
        final IBuilding hut = colony.getServerBuildingManager().getBuilding(order.getClaimedBy());
        if (hut instanceof AbstractBuildingStructureBuilder lead && lead.hasWorkOrder() && lead.getWorkOrder() == order)
        {
            return lead;
        }
        return null;
    }

    /**
     * A builder who may help: his hut has a builder, who is not set to manual mode.
     */
    private static boolean isUsableHelper(@Nullable final IBuilding building)
    {
        if (!(building instanceof BuildingBuilder hut))
        {
            return false;
        }
        final ICitizenData citizen = hut.getFirstModuleOccurance(WorkerBuildingModule.class).getFirstCitizen();
        return citizen != null && !hut.getManualMode();
    }
}
