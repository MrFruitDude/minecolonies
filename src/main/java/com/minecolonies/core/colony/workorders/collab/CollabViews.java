package com.minecolonies.core.colony.workorders.collab;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.colony.workorders.collab.ICollabBuilderView;
import com.minecolonies.api.colony.workorders.collab.ICollabView;

import java.util.List;

/**
 * Read side of the builder collaboration: snapshots for UIs and statistics. (Stub: the tests come first.)
 */
public final class CollabViews
{
    private CollabViews()
    {
    }

    public static ICollabView of(final IColony colony, final IBuilderWorkOrder order)
    {
        throw new UnsupportedOperationException("stub");
    }

    public static List<ICollabView> orders(final IColony colony)
    {
        return List.of();
    }

    public static List<ICollabBuilderView> builders(final IColony colony)
    {
        return List.of();
    }
}
