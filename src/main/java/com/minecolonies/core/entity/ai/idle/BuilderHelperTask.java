package com.minecolonies.core.entity.ai.idle;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.idle.IIdleCitizenTask;
import com.minecolonies.api.colony.idle.IIdleTaskSession;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.idle.IdleCitizenSettings;
import com.minecolonies.core.colony.workorders.collab.BuilderCollab;
import com.minecolonies.core.colony.workorders.collab.CollabScheduler;
import com.minecolonies.core.colony.workorders.collab.IdleHelperKey;
import com.minecolonies.core.colony.workorders.collab.WorkOrderCollab;
import com.minecolonies.core.entity.ai.workers.util.BuilderStageRules;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * The first idle citizen task: a citizen without a job lends a hand to a builder who is working on an order. Built on the builder collaboration (leases, territories,
 * the helper gate, the lead taking positions back): he joins the order as a helper under the lead's lease rules, known in it by his {@link IdleHelperKey}.
 * <ul>
 *   <li>He picks the nearest order that a builder leads, that is in a stage helpers work in, has enough left and few enough idle helpers (the colony setting), and
 *   passes the same gate as a builder's help (a structure that is too small gets none, unless the player set a helper limit for the order).</li>
 *   <li>He never creates or claims an order and never requests anything: he takes his materials from the lead's hut and the lead himself.</li>
 *   <li>He works at {@code builders.collaborationIdleHelperSpeed} of the pace of the builder, from the ground; what he cannot reach is left to the builder.</li>
 * </ul>
 */
public final class BuilderHelperTask implements IIdleCitizenTask
{
    /**
     * The id of the task.
     */
    public static final String ID = "builder_helper";

    /**
     * Builder helping comes first.
     */
    public static final int PRIORITY = 100;

    /**
     * The farthest an idle citizen goes to help: the building range of a builder.
     */
    private static final double MAX_DISTANCE_SQ = 96.0D * 96.0D;

    @Override
    public String getId()
    {
        return ID;
    }

    @Override
    public int getPriority()
    {
        return PRIORITY;
    }

    @Nullable
    @Override
    public IIdleTaskSession tryStart(final IColony colony, final ICitizenData citizen)
    {
        if (!(colony instanceof Colony) || !BuilderCollab.enabled() || colony.getWorld() == null
              || citizen.getColony() == null || citizen.getColony().getID() != colony.getID() || !citizen.getColony().getDimension().equals(colony.getDimension()))
        {
            // (a helper never helps the builder of another colony)
            return null;
        }
        final AbstractEntityCitizen entity = citizen.getEntity().orElse(null);
        if (entity == null)
        {
            return null;
        }
        final BlockPos key = IdleHelperKey.of(citizen.getId());
        final long now = colony.getWorld().getGameTime();

        // what was saved with the colony (a helper whose session did not survive the restart): his leases go back, he joins like anybody else
        final IBuilderWorkOrder stale = colony.getWorkManager().getAssistedOrder(key);
        if (stale != null)
        {
            stale.getCollab().removeAssistant(key, 0);
        }

        IBuilderWorkOrder best = null;
        double bestDistance = MAX_DISTANCE_SQ;
        for (final IServerWorkOrder candidate : colony.getWorkManager().getWorkOrders().values())
        {
            if (!(candidate instanceof IBuilderWorkOrder order) || !acceptsHelpers(colony, order, key, now))
            {
                continue;
            }
            final double distance = entity.blockPosition().distSqr(order.getLocation());
            if (distance < bestDistance)
            {
                best = order;
                bestDistance = distance;
            }
        }
        if (best == null)
        {
            return null;
        }
        best.getCollab().addAssistant(key, now);
        colony.markDirty();
        return new BuilderHelperSession(colony, citizen, best);
    }

    /**
     * Whether an order takes another idle helper now: a builder leads it, it is in a stage that is shared, has enough left, is big enough for a second pair of hands (or the
     * player asked for helpers), and has fewer idle helpers than the colony allows.
     */
    static boolean acceptsHelpers(final IColony colony, final IBuilderWorkOrder order, final BlockPos key, final long now)
    {
        if (order.getWorkOrderType() == WorkOrderType.REMOVE || CollabScheduler.leadOf(colony, order) == null)
        {
            return false;
        }
        final WorkOrderCollab collab = order.getCollab();
        if (collab.isOnCooldown(key, now) || collab.hasAssistant(key))
        {
            return false;
        }
        final var stage = collab.getProgressStage();
        if (stage == null || !BuilderStageRules.isSharedStage(stage) || !collab.hasStageTotals() || order.getIteratorType().isEmpty())
        {
            return false;
        }
        if (collab.getRemainingBlocks() < BuilderCollab.minRemaining()
              || collab.getIdleAssistantCount() >= CollabScheduler.idleCapFor(colony, order)
              || (collab.getMaxAssistantsSetting() < 0 && !BuilderCollab.worthAHelper(collab.getPlacementBlocks(), collab.getHighBlocks())))
        {
            return false;
        }
        return colony.getWorld() != null && WorldUtil.isBlockLoaded(colony.getWorld(), order.getLocation()) && IdleCitizenSettings.enabled(colony);
    }
}
