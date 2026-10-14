package com.minecolonies.core.colony.labour;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.idle.IIdleCitizenTask;
import com.minecolonies.api.colony.idle.IIdleTaskSession;
import com.minecolonies.core.colony.Colony;
import org.jetbrains.annotations.Nullable;

/**
 * Labour as a task of the idle citizen arbiter (LABOURERS.md 9): an unemployed citizen who is free is offered a unit of work from the colony's labour board. Three
 * tasks share the board, so that the arbiter's priorities give the order of the design: work a builder is blocked on (110), then helping a builder (the
 * builder helper, 100), then the other requests no building serves (90), then the stock goals of the warehouse (80).
 */
public final class LabourTask implements IIdleCitizenTask
{
    public static final String ID_URGENT    = "labour_urgent";
    public static final String ID_REQUEST   = "labour_request";
    public static final String ID_STOCKPILE = "labour_stockpile";

    public static final int PRIORITY_URGENT    = 110;
    public static final int PRIORITY_REQUEST   = 90;
    public static final int PRIORITY_STOCKPILE = 80;

    private final String  id;
    private final int     priority;
    private final boolean urgentOnly;
    private final boolean requests;
    private final boolean stockpile;

    private LabourTask(final String id, final int priority, final boolean urgentOnly, final boolean requests, final boolean stockpile)
    {
        this.id = id;
        this.priority = priority;
        this.urgentOnly = urgentOnly;
        this.requests = requests;
        this.stockpile = stockpile;
    }

    /**
     * @return the three labour tasks, to register with the arbiter.
     */
    public static LabourTask[] all()
    {
        return new LabourTask[] {
          new LabourTask(ID_URGENT, PRIORITY_URGENT, true, true, false),
          new LabourTask(ID_REQUEST, PRIORITY_REQUEST, false, true, false),
          new LabourTask(ID_STOCKPILE, PRIORITY_STOCKPILE, false, false, true)};
    }

    /**
     * @param taskId an id of a task of the arbiter.
     * @return whether it is a labour task.
     */
    public static boolean isLabour(final String taskId)
    {
        return ID_URGENT.equals(taskId) || ID_REQUEST.equals(taskId) || ID_STOCKPILE.equals(taskId);
    }

    @Override
    public String getId()
    {
        return id;
    }

    @Override
    public int getPriority()
    {
        return priority;
    }

    @Nullable
    @Override
    public IIdleTaskSession tryStart(final IColony colony, final ICitizenData citizen)
    {
        if (!(colony instanceof Colony serverColony) || colony.getWorld() == null || colony.getWorld().isClientSide() || citizen.getColony() == null
              || citizen.getColony().getID() != colony.getID() || !citizen.getColony().getDimension().equals(colony.getDimension()))
        {
            // (a labourer never works for another colony)
            return null;
        }
        if (!LabourSettings.enabled(serverColony))
        {
            return null;
        }
        final LabourBoard board = serverColony.getLabourBoard();
        final LabourClaim claim = board.offer(citizen, urgentOnly, requests, stockpile);
        return claim == null ? null : new LabourSession(id, serverColony, citizen, board, claim);
    }
}
