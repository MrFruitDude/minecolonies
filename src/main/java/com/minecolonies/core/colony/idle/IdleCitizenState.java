package com.minecolonies.core.colony.idle;

import com.minecolonies.api.colony.idle.IIdleTaskSession;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * What the unemployed citizens of one colony are doing right now (not saved: a task that has state of its own, like the builder helping, keeps it with the colony data
 * and gives the citizen his session back when he is offered to it again after a reload). Server thread only.
 */
public final class IdleCitizenState
{
    /**
     * A citizen at work for a task.
     */
    record Entry(IIdleTaskSession session, long since)
    {
    }

    private final Map<Integer, Entry> sessions = new HashMap<>();

    /**
     * A citizen that left a task for his needs does not start another one before this game time (he would be sent to bed or to the table again at once).
     */
    private final Map<Integer, Long> cooldowns = new HashMap<>();

    /**
     * Since when an employed worker has had nothing to do at his hut, by citizen id (see {@link WorkerHelp}).
     */
    private final Map<Integer, Long> outOfWorkSince = new HashMap<>();

    /**
     * Keeps the clock of a worker without work: started the first time he is seen without work, dropped as soon as he has work again.
     *
     * @param citizenId the citizen.
     * @param outOfWork whether he has nothing to do now.
     * @param now       the game time.
     * @return how long he has been without work, 0 when he has work.
     */
    long outOfWorkFor(final int citizenId, final boolean outOfWork, final long now)
    {
        if (!outOfWork)
        {
            outOfWorkSince.remove(citizenId);
            return 0L;
        }
        final long since = outOfWorkSince.computeIfAbsent(citizenId, k -> now);
        return since > now ? 0L : now - since;
    }

    @Nullable
    public IIdleTaskSession session(final int citizenId)
    {
        final Entry entry = sessions.get(citizenId);
        return entry == null ? null : entry.session();
    }

    Map<Integer, Entry> sessions()
    {
        return sessions;
    }

    void put(final int citizenId, final IIdleTaskSession session, final long now)
    {
        sessions.put(citizenId, new Entry(session, now));
    }

    @Nullable
    Entry remove(final int citizenId)
    {
        return sessions.remove(citizenId);
    }

    void cooldown(final int citizenId, final long until)
    {
        cooldowns.put(citizenId, until);
    }

    boolean onCooldown(final int citizenId, final long now)
    {
        final Long until = cooldowns.get(citizenId);
        if (until == null)
        {
            return false;
        }
        if (until <= now)
        {
            cooldowns.remove(citizenId);
            return false;
        }
        return true;
    }

    /**
     * @return how many citizens work for tasks.
     */
    public int size()
    {
        return sessions.size();
    }

    /**
     * @param taskId the task.
     * @return how many citizens work for the task.
     */
    public int countOf(final String taskId)
    {
        int n = 0;
        for (final Entry entry : sessions.values())
        {
            if (entry.session().getTaskId().equals(taskId))
            {
                n++;
            }
        }
        return n;
    }
}
