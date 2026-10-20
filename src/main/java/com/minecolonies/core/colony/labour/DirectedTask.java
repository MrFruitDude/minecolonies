package com.minecolonies.core.colony.labour;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.idle.IIdleCitizenTask;
import com.minecolonies.api.colony.idle.IIdleTaskSession;
import com.minecolonies.core.colony.Colony;
import org.jetbrains.annotations.Nullable;

/**
 * A manager's directed gathering order as a task of the idle citizen arbiter: a free jobless citizen who is on an active order works on it before anything else (the
 * highest priority), so the arbiter's rules decide when he may (an adult with no job, awake, healthy, fed, in the work hours) and when he stops.
 */
public final class DirectedTask implements IIdleCitizenTask
{
    public static final String ID       = "labour_directed";
    public static final int    PRIORITY = 120;

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
        if (!(colony instanceof Colony serverColony) || colony.getWorld() == null || colony.getWorld().isClientSide() || citizen.getColony() == null
              || citizen.getColony().getID() != colony.getID() || !citizen.getColony().getDimension().equals(colony.getDimension()) || citizen.getJob() != null)
        {
            return null;
        }
        if (!LabourSettings.enabled(serverColony))
        {
            return null;
        }
        final LabourBoard board = serverColony.getLabourBoard();
        final DirectedOrder order = board.directed().orderOf(citizen.getId());
        if (order == null)
        {
            return null;
        }
        final LabourClaim claim = board.claimDirected(citizen, order);
        return claim == null ? null : new DirectedSession(serverColony, citizen, board, claim);
    }
}
