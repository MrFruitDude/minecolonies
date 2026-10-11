package com.minecolonies.core.colony.workorders.collab;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.entity.ai.workers.util.BuilderStageRules;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Write side of the builder collaboration: what a player may set on an order, persisted with the order.
 * <ul>
 *   <li><b>Helper limit</b> ({@link #setMaxAssistants}): the most helpers one order gets. -1 is automatic (one per
 *   {@code blocksPerHelper} blocks left, at most {@code maxHelpers}); 0..{@value #LIMIT} replaces that formula for this order. An order with
 *   less than {@code minRemaining} blocks left gets no new helpers whatever the limit says. Helpers beyond a lowered limit leave once their stay time is
 *   over (not pinned ones), and do not come back.</li>
 *   <li><b>Pin</b> ({@link #pin}): a helper pinned to an order stays: a new order does not take him (so with a pin there can be more orders than
 *   builders who lead - the one invariant of the scheduler, "as many orders as builders means nobody helps", has this one documented exception: pinned
 *   helpers), a lowered limit and the stay time do not move him, and the limit does not stop a pin from adding a free builder. A pinned helper still goes when
 *   the order has nothing left to share, its lead is gone, his hut changes or collaboration is switched off, and the pin goes with him. Pinning a builder
 *   who is free and not yet helping brings him to the order (it must be led, in a shared stage, and the builder must be able to build it).</li>
 * </ul>
 * Server thread only.
 */
public final class CollabControls
{
    /**
     * The highest per-order limit that can be set.
     */
    public static final int LIMIT = 8;

    /**
     * Why a control was refused.
     */
    public enum Result
    {
        OK,
        /** Builder collaboration is switched off in the server config. */
        DISABLED,
        NO_SUCH_ORDER,
        /** The order has no lead (not claimed, or its hut does not hold it). */
        NOT_LED,
        /** The hut is not a builder's with a builder in it. */
        NOT_A_BUILDER,
        /** The builder leads an order or helps another one. */
        BUSY,
        /** The builder cannot build the order (level, distance). */
        CANNOT_BUILD,
        /** The order is not in a stage helpers may work in (yet). */
        STAGE_NOT_SHARED,
        /** The hut is no (pinned) helper of the order. */
        NOT_AN_ASSISTANT,
        BAD_VALUE
    }

    private CollabControls()
    {
    }

    @Nullable
    private static IBuilderWorkOrder order(final IColony colony, final int orderId)
    {
        final IServerWorkOrder order = colony.getWorkManager().getWorkOrder(orderId);
        return order instanceof IBuilderWorkOrder builderOrder ? builderOrder : null;
    }

    /**
     * Sets the helper limit of an order.
     *
     * @param max -1 for automatic, else 0..{@link #LIMIT}.
     */
    public static Result setMaxAssistants(final IColony colony, final int orderId, final int max)
    {
        if (!BuilderCollab.enabled())
        {
            return Result.DISABLED;
        }
        final IBuilderWorkOrder order = order(colony, orderId);
        if (order == null)
        {
            return Result.NO_SUCH_ORDER;
        }
        if (max < -1 || max > LIMIT)
        {
            return Result.BAD_VALUE;
        }
        order.getCollab().setMaxAssistantsSetting(max);
        colony.markDirty();
        return Result.OK;
    }

    /**
     * Pins a builder to the order he helps with, or brings a free builder to it as a pinned helper.
     */
    public static Result pin(final IColony colony, final int orderId, final BlockPos hutPos)
    {
        if (!BuilderCollab.enabled())
        {
            return Result.DISABLED;
        }
        final IBuilderWorkOrder order = order(colony, orderId);
        if (order == null)
        {
            return Result.NO_SUCH_ORDER;
        }
        final AbstractBuildingStructureBuilder lead = CollabScheduler.leadOf(colony, order);
        if (lead == null)
        {
            return Result.NOT_LED;
        }
        final IBuilding building = colony.getServerBuildingManager().getBuilding(hutPos);
        if (!(building instanceof BuildingBuilder hut) || hut.getFirstModuleOccurance(WorkerBuildingModule.class).getFirstCitizen() == null)
        {
            return Result.NOT_A_BUILDER;
        }
        final WorkOrderCollab collab = order.getCollab();
        if (hut == lead || hut.hasWorkOrder())
        {
            return Result.BUSY;
        }
        final IBuilderWorkOrder helped = colony.getWorkManager().getAssistedOrder(hut.getID());
        if (helped != null && helped != order)
        {
            return Result.BUSY;
        }
        if (helped == null)
        {
            final var stage = collab.getProgressStage();
            if (stage == null || !BuilderStageRules.isSharedStage(stage) || !collab.hasStageTotals() || order.getIteratorType().isEmpty())
            {
                return Result.STAGE_NOT_SHARED;
            }
            if (!order.canBuildIgnoringDistance(hut, hut.getPosition(), hut.getBuildingLevel()) || !CollabScheduler.inRange(hut, order))
            {
                return Result.CANNOT_BUILD;
            }
            collab.addAssistant(hut.getID(), colony.getWorld() == null ? 0 : colony.getWorld().getGameTime());
            collab.clearCooldown(hut.getID());
        }
        collab.setPinned(hut.getID(), true);
        colony.markDirty();
        return Result.OK;
    }

    /**
     * Lets a pinned helper go back to the normal rules.
     */
    public static Result unpin(final IColony colony, final int orderId, final BlockPos hutPos)
    {
        if (!BuilderCollab.enabled())
        {
            return Result.DISABLED;
        }
        final IBuilderWorkOrder order = order(colony, orderId);
        if (order == null)
        {
            return Result.NO_SUCH_ORDER;
        }
        final WorkOrderCollab.Assistant assistant = order.getCollab().getAssistant(hutPos);
        if (assistant == null || !assistant.isPinned())
        {
            return Result.NOT_AN_ASSISTANT;
        }
        order.getCollab().setPinned(hutPos, false);
        colony.markDirty();
        return Result.OK;
    }
}
