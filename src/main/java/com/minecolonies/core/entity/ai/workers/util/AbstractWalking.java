package com.minecolonies.core.entity.ai.workers.util;

import com.minecolonies.api.colony.WorkerWalking;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.colony.jobs.AbstractJobGuard;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Abstract walking (server setting {@code workers.abstractWalking}): a long walk of a worker is replaced by a timed relocation. The worker
 * stays where he is for as long as the walk would take (the path length times the time a block takes, divided by the worker speed of the
 * settings), with a few puffs of smoke, and then appears on a safe spot next to the destination, in a cloud of smoke. It only happens when
 * the destination and the start are loaded and a standable spot exists next to the destination; otherwise the worker walks as usual.
 * <p>
 * It saves the path searches, the chunk loads along the way and the long walks that block a worker for minutes (and are the usual place
 * for a worker to get stuck). It is state of the moment: nothing is saved, and a worker that is moved away from his start (or whose walk
 * target changes) while he waits walks normally.
 */
public final class AbstractWalking
{
    private AbstractWalking()
    {
    }

    /**
     * Ticks a block of walking takes. A citizen walks about 0.5 blocks per tick on a straight path; paths are not straight and he has to
     * wait for the path search, so the time stays well above that.
     */
    public static final double TICKS_PER_BLOCK = 2.5D;

    /**
     * Straight line to path length.
     */
    public static final double PATH_FACTOR = 1.25D;

    /**
     * The shortest time of a relocation, in ticks.
     */
    public static final int MIN_TICKS = 20;

    /**
     * ALWAYS replaces every walk beyond this many blocks.
     */
    public static final int ALWAYS_MIN_BLOCKS = 12;

    private record Relocation(BlockPos destination, BlockPos stand, Vec3 origin, long endTick)
    {
    }

    private record Declined(BlockPos destination, long untilTick)
    {
    }

    private static final Map<Mob, Relocation> ACTIVE   = new WeakHashMap<>();
    private static final Map<Mob, Declined>   DECLINED = new WeakHashMap<>();

    /**
     * How long a relocation of this length takes.
     *
     * @param distance the straight line distance in blocks.
     * @param speed    the worker speed multiplier.
     * @return the ticks.
     */
    public static int travelTicks(final double distance, final double speed)
    {
        return Math.max(MIN_TICKS, (int) Math.ceil(distance * PATH_FACTOR * TICKS_PER_BLOCK / Math.max(0.1D, speed)));
    }

    /**
     * Whether a walk of this length is replaced in this mode.
     *
     * @param mode      the setting.
     * @param distance  the straight line distance to the destination.
     * @param reach     how close counts as arrived.
     * @param minBlocks the setting for long walks.
     * @return true if it is abstracted.
     */
    public static boolean applies(final WorkerWalking mode, final double distance, final int reach, final int minBlocks)
    {
        switch (mode)
        {
            case ALWAYS:
                return distance > Math.max(ALWAYS_MIN_BLOCKS, reach + 4);
            case LONG_ONLY:
                return distance > Math.max(minBlocks, reach + 4);
            default:
                return false;
        }
    }

    /**
     * Called by the walking helpers before they start a path.
     *
     * @param entity      the walking entity.
     * @param destination where it walks to.
     * @param reach       how close counts as arrived.
     * @return null if the walk is done as usual, FALSE while the worker waits out the relocation, TRUE when he arrived (he stands next to the
     * destination now).
     */
    @Nullable
    public static Boolean tryRelocate(final Mob entity, final BlockPos destination, final int reach)
    {
        final WorkerWalking mode = WorkerSettings.abstractWalking();
        final Relocation active = ACTIVE.get(entity);
        if (mode == WorkerWalking.OFF)
        {
            if (active != null)
            {
                ACTIVE.remove(entity);
            }
            return null;
        }
        if (!(entity.level() instanceof ServerLevel level) || !isWorking(entity))
        {
            ACTIVE.remove(entity);
            return null;
        }

        final long now = level.getGameTime();
        if (active != null)
        {
            if (!active.destination().equals(destination) || entity.position().distanceToSqr(active.origin()) > 9.0D || entity.getVehicle() != null)
            {
                // the walk target changed or the worker was moved: this relocation is over
                ACTIVE.remove(entity);
            }
            else if (now >= active.endTick())
            {
                if (!WorldUtil.isEntityBlockLoaded(level, active.stand()) || !StandSpots.canStandAt(level, active.stand()))
                {
                    ACTIVE.remove(entity);
                    DECLINED.put(entity, new Declined(destination, now + 200L));
                    return null;
                }
                ACTIVE.remove(entity);
                puff(level, entity.position());
                entity.getNavigation().stop();
                entity.snapTo(active.stand().getX() + 0.5D, active.stand().getY(), active.stand().getZ() + 0.5D);
                puff(level, entity.position());
                return Boolean.TRUE;
            }
            else
            {
                if ((now & 15L) == 0L)
                {
                    level.sendParticles(ParticleTypes.CLOUD, entity.getX(), entity.getY() + 1.0D, entity.getZ(), 2, 0.25D, 0.4D, 0.25D, 0.0D);
                }
                return Boolean.FALSE;
            }
        }

        final double distance = Math.sqrt(BlockPosUtil.getDistanceSquared(entity.blockPosition(), destination));
        if (!applies(mode, distance, reach, WorkerSettings.abstractWalkingMinBlocks()))
        {
            return null;
        }
        final Declined declined = DECLINED.get(entity);
        if (declined != null && declined.destination().equals(destination) && now < declined.untilTick())
        {
            return null;
        }
        if (!WorldUtil.isEntityBlockLoaded(level, destination) || !WorldUtil.isEntityBlockLoaded(level, entity.blockPosition()))
        {
            return null;
        }
        final BlockPos stand = StandSpots.nearest(level, destination, Math.max(1, Math.min(reach, 3)), 3, 3, null);
        if (stand == null)
        {
            DECLINED.put(entity, new Declined(destination, now + 200L));
            return null;
        }

        entity.getNavigation().stop();
        ACTIVE.put(entity, new Relocation(destination.immutable(), stand, entity.position(), now + travelTicks(distance, WorkerSettings.speedMultiplier())));
        puff(level, entity.position());
        return Boolean.FALSE;
    }

    /**
     * Whether a relocation of this entity is under way.
     */
    public static boolean isRelocating(final Mob entity)
    {
        return ACTIVE.containsKey(entity);
    }

    private static boolean isWorking(final Mob entity)
    {
        if (!(entity instanceof AbstractEntityCitizen citizen) || citizen.getCitizenData() == null || citizen.getCitizenData().getJob() == null)
        {
            return false;
        }
        if (citizen.getCitizenData().getJob() instanceof AbstractJobGuard<?>)
        {
            return false;
        }
        return citizen instanceof EntityCitizen entityCitizen && entityCitizen.getCitizenAI().getState() == CitizenAIState.WORKING;
    }

    private static void puff(final ServerLevel level, final Vec3 position)
    {
        level.sendParticles(ParticleTypes.POOF, position.x, position.y + 0.5D, position.z, 10, 0.3D, 0.4D, 0.3D, 0.02D);
    }
}
