package com.minecolonies.api.eventbus.events.colony;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.workorders.IWorkOrder;
import com.minecolonies.api.colony.workorders.WorkOrderRemovalReason;
import org.jetbrains.annotations.NotNull;

/**
 * Posted when a work order was removed from a colony's work manager, with the reason.
 */
public final class WorkOrderRemovedModEvent extends AbstractColonyModEvent
{
    private final IWorkOrder             workOrder;
    private final WorkOrderRemovalReason reason;

    public WorkOrderRemovedModEvent(@NotNull final IColony colony, @NotNull final IWorkOrder workOrder, @NotNull final WorkOrderRemovalReason reason)
    {
        super(colony);
        this.workOrder = workOrder;
        this.reason = reason;
    }

    /**
     * The order that was removed (already out of the work manager).
     *
     * @return the order.
     */
    @NotNull
    public IWorkOrder getWorkOrder()
    {
        return workOrder;
    }

    @NotNull
    public WorkOrderRemovalReason getReason()
    {
        return reason;
    }
}
