package com.minecolonies.core.colony.requestsystem.priority;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Pickup;
import com.minecolonies.core.colony.requestsystem.management.IStandardRequestManager;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * RS3: which courier task goes first. The score is lexicographic in (importance, building priority, wait in seconds),
 * and the distance only decides between tasks that tie on all three. The old score added the three terms together and
 * let a long walk outweigh a priority, so a nearby top-up beat a faraway builder.
 * <p>
 * The pure {@link #score} has no game types so it can be tested on its own; {@link #courierScore} and {@link #explain}
 * read the colony.
 */
public final class RequestScoring
{
    /**
     * Wait is counted in whole seconds.
     */
    public static final long TICKS_PER_SECOND = 20L;

    /**
     * A target whose chunk is not loaded sorts below everything else, however long it waited.
     */
    private static final long UNLOADED_PENALTY = 1L << 60;

    /**
     * A pickup booked for a later day sorts below everything that is due, but above the unloaded.
     */
    private static final long NOT_DUE_PENALTY = 1L << 56;

    private static final int  PRIORITY_MAX = BuildingPriorities.ALARM_PRIORITY;
    private static final long WAIT_MAX     = (1L << 24) - 1;
    private static final int  DISTANCE_MAX = (1 << 16) - 1;

    private RequestScoring()
    {
    }

    /**
     * Why a courier task has the score it has.
     *
     * @param importance        the importance of the task's root request.
     * @param buildingPos       the building the task serves, null when none is known.
     * @param buildingPriority  the priority the scoring used, 1 to 9, 10 during an alarm.
     * @param alarm             true when the alarm decided the priority.
     * @param waitedTicks       how long the task has waited in the courier queue.
     * @param distance          the walking distance term (square root of the manhattan distance).
     * @param score             the score; the higher, the earlier.
     */
    public record Explanation(RequestImportance importance, @Nullable BlockPos buildingPos, int buildingPriority, boolean alarm, long waitedTicks, int distance, long score)
    {
    }

    /**
     * The score, higher is served first. Inputs outside their range are clamped to it.
     *
     * @param importance       the importance.
     * @param buildingPriority the effective building priority, 1 to 9 or 10 for an alarm.
     * @param waitedTicks      how long the task has waited, in game ticks.
     * @param distance         the distance term; nearer is better.
     * @return the score.
     */
    public static long score(@NotNull final RequestImportance importance, final int buildingPriority, final long waitedTicks, final int distance)
    {
        final long importanceTerm = RequestImportance.BACKGROUND.rank() - importance.rank();
        final long priorityTerm = Math.max(0, Math.min(PRIORITY_MAX, buildingPriority));
        final long waitTerm = Math.max(0L, Math.min(WAIT_MAX, waitedTicks / TICKS_PER_SECOND));
        final long distanceTerm = DISTANCE_MAX - Math.max(0, Math.min(DISTANCE_MAX, distance));
        return (((importanceTerm * (PRIORITY_MAX + 1) + priorityTerm) << 24 | waitTerm) << 16) | distanceTerm;
    }

    /**
     * The score of a task in a courier queue. Starts the task's wait clock when the task is seen for the first time.
     *
     * @param colony       the colony.
     * @param task         the delivery or pickup.
     * @param source       where it starts, null when unknown.
     * @param target       the building it serves (a pickup's building, a delivery's destination).
     * @param targetLoaded false when the target's chunk is not loaded.
     * @return the score.
     */
    public static long courierScore(@NotNull final IColony colony, @NotNull final IRequest<?> task, @Nullable final BlockPos source, @Nullable final BlockPos target,
      final boolean targetLoaded)
    {
        return compute(colony, task, source, target, targetLoaded, true).score();
    }

    /**
     * Same as {@link #courierScore}, with the terms: what a UI shows as "why is this served first". Read-only: a task a
     * courier queue has not seen yet has waited 0.
     */
    @NotNull
    public static Explanation explain(@NotNull final IColony colony, @NotNull final IRequest<?> task, @Nullable final BlockPos source, @Nullable final BlockPos target,
      final boolean targetLoaded)
    {
        return compute(colony, task, source, target, targetLoaded, false);
    }

    @NotNull
    private static Explanation compute(@NotNull final IColony colony, @NotNull final IRequest<?> task, @Nullable final BlockPos source, @Nullable final BlockPos target,
      final boolean targetLoaded, final boolean startClock)
    {
        final IRequestManager manager = colony.getRequestManager();
        final BuildingPriorities priorities = manager instanceof IStandardRequestManager standard ? standard.getBuildingPriorities() : null;
        final long now = colony.getWorld().getGameTime();

        final RequestImportance importance = RequestImportance.of(manager, task);
        final int priority = priorities == null || target == null ? BuildingPriorities.DEFAULT_PRIORITY : priorities.effective(target, now);
        final boolean alarm = priorities != null && target != null && priorities.isAlarm(target, now);
        final long waited = priorities == null ? 0L : startClock ? priorities.waited(task.getId(), now) : priorities.peekWaited(task.getId(), now);
        final int distance = source == null || target == null ? 0 : (int) Math.sqrt(source.distManhattan(target));

        long score = score(importance, priority, waited, distance);
        if (task.getRequest() instanceof Pickup pickup && pickup.getDay() > colony.getDay())
        {
            score -= NOT_DUE_PENALTY;
        }
        if (!targetLoaded)
        {
            score -= UNLOADED_PENALTY;
        }
        return new Explanation(importance, target, priority, alarm, waited, distance, score);
    }
}
