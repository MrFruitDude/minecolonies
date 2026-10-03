package com.minecolonies.api.entity.other;

import com.minecolonies.api.entity.pathfinding.IStuckHandlerEntity;
import com.minecolonies.api.util.EntityUtils;
import com.minecolonies.api.util.LookHandler;
import com.minecolonies.api.util.constant.ColonyConstants;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Special abstract minecolonies mob that overrides laggy vanilla behaviour.
 */
public abstract class AbstractFastMinecoloniesEntity extends PathfinderMob implements IStuckHandlerEntity
{
    /**
     * Whether this entity can be stuck for stuckhandling
     */
    private boolean canBeStuck = true;

    /**
     * Random update variance for this entity, used to spread out updates equalls
     */
    public final int randomVariance = ColonyConstants.rand.nextInt(20);

    /**
     * Cache fluid state
     */
    private boolean isInFluid = false;

    /**
     * Cache fire state
     */
    private boolean onFire = false;

    /**
     * Entity push cache.
     */
    private List<LivingEntity> entityPushCache = new ArrayList<>();

    /**
     * The timepoint at which the entity last collided
     */
    private long lastHorizontalCollision = 0;

    /**
     * Last knockback time
     */
    protected long lastKnockBack = 0;

    /**
     * CA-9: full travel runs at least once every this many ticks for an idle entity, spread by {@link #randomVariance}.
     */
    private static final int IDLE_TRAVEL_RECHECK = 10;

    /**
     * CA-9: whether the last full travel left this entity at rest, so the next ticks may skip it while nothing changed.
     */
    private boolean idleTravelArmed = false;

    /**
     * CA-9: the rest state the last full travel produced (position, motion, box) and the block states under the box.
     */
    private Vec3        idleRestPos    = Vec3.ZERO;
    private Vec3        idleRestDelta  = Vec3.ZERO;
    private AABB        idleRestBox    = null;
    private int         idleSupportX;
    private int         idleSupportY;
    private int         idleSupportZ;
    private int         idleSupportW;
    private int         idleSupportH;
    private int         idleSupportD;
    private BlockState[] idleSupport   = new BlockState[12];
    private final BlockPos.MutableBlockPos idleScanPos = new BlockPos.MutableBlockPos();

    /**
     * Create a new instance.
     *
     * @param type    from type.
     * @param worldIn the world.
     */
    protected AbstractFastMinecoloniesEntity(final EntityType<? extends PathfinderMob> type, final Level worldIn)
    {
        super(type, worldIn);
        lookControl = new LookHandler(this);
    }

    /**
     * Test seam (CA-9): when set, it sees every {@link #move} call on these entities before the move runs. It is null in
     * production, so the cost is one static read per move. Server thread only.
     */
    public static Consumer<AbstractFastMinecoloniesEntity> moveObserver = null;

    @Override
    public void move(final MoverType moverType, final Vec3 delta)
    {
        final Consumer<AbstractFastMinecoloniesEntity> observer = moveObserver;
        if (observer != null)
        {
            observer.accept(this);
        }
        super.move(moverType, delta);
    }

    /**
     * Whether this entity may skip vanilla travel while it stands idle (CA-9). Off here; citizens and visitors turn it on.
     *
     * @return true to allow the idle short-circuit.
     */
    protected boolean canSkipIdleTravel()
    {
        return false;
    }

    /**
     * CA-9: skips vanilla travel (gravity, {@link #move}, collision) while the entity stands at rest. A full travel that
     * left position and motion exactly as they were, with no input, on a supporting block, arms the skip. Vanilla travel
     * is then an identity on that state, so it is skipped while nothing it reads changed: no input or jump, same motion
     * (a push, knockback or any setDeltaMovement changes it), same position and box (pistons, teleports, pose changes),
     * still on ground with no path, not in fluid, not riding or ridden, not stuck in a block, no levitation or slow falling,
     * and the same block states under and around its feet (a block removed or replaced, a moving piston). What these
     * checks do not watch (attributes such as gravity, entity collisions) is picked up by a full travel every
     * {@link #IDLE_TRAVEL_RECHECK} ticks.
     */
    @Override
    public void travel(final Vec3 input)
    {
        if (idleTravelArmed)
        {
            if (isStillAtIdleRest(input))
            {
                return;
            }
            idleTravelArmed = false;
        }

        if (level().isClientSide() || !canSkipIdleTravel())
        {
            super.travel(input);
            return;
        }

        final Vec3 posBefore = position();
        final Vec3 deltaBefore = getDeltaMovement();
        super.travel(input);
        if (deltaBefore.x == 0 && deltaBefore.z == 0 && deltaBefore.equals(getDeltaMovement()) && posBefore.equals(position()) && isIdleCandidate(input))
        {
            armIdleTravel();
        }
    }

    /**
     * The cheap per-tick part of the idle test (CA-9), shared by arming and skipping.
     */
    private boolean isIdleCandidate(final Vec3 input)
    {
        return input.x == 0 && input.y == 0 && input.z == 0 && !jumping
                 && onGround() && mainSupportingBlockPos.isPresent()
                 && !noPhysics && !isInFluid && !isFallFlying()
                 && !isPassenger() && !isVehicle()
                 && stuckSpeedMultiplier.lengthSqr() <= 1.0E-7
                 && !hasEffect(MobEffects.LEVITATION) && !hasEffect(MobEffects.SLOW_FALLING)
                 && getNavigation().isDone();
    }

    /**
     * Whether the entity is still in the rest state the last full travel armed, and this is not a re-check tick.
     */
    private boolean isStillAtIdleRest(final Vec3 input)
    {
        return tickCount % IDLE_TRAVEL_RECHECK != randomVariance % IDLE_TRAVEL_RECHECK
                 && getDeltaMovement().equals(idleRestDelta)
                 && position().equals(idleRestPos)
                 && getBoundingBox().equals(idleRestBox)
                 && isIdleCandidate(input)
                 && idleSupportUnchanged();
    }

    /**
     * Records the rest state, and the block states of every block whose collision shape the next travel's downward probe
     * could touch: the columns under the box, from the feet layer down past a 1.5-high block (fence, wall) below.
     */
    private void armIdleTravel()
    {
        final AABB box = getBoundingBox();
        idleSupportX = Mth.floor(box.minX - 1.0E-7);
        idleSupportY = Mth.floor(box.minY - 1.6D);
        idleSupportZ = Mth.floor(box.minZ - 1.0E-7);
        idleSupportW = Mth.floor(box.maxX + 1.0E-7) - idleSupportX + 1;
        idleSupportH = Mth.floor(box.minY + 1.0E-7) - idleSupportY + 1;
        idleSupportD = Mth.floor(box.maxZ + 1.0E-7) - idleSupportZ + 1;
        final int size = idleSupportW * idleSupportH * idleSupportD;
        if (size > idleSupport.length)
        {
            idleSupport = new BlockState[size];
        }

        final Level level = level();
        int i = 0;
        for (int y = 0; y < idleSupportH; y++)
        {
            for (int x = 0; x < idleSupportW; x++)
            {
                for (int z = 0; z < idleSupportD; z++)
                {
                    idleSupport[i++] = level.getBlockState(idleScanPos.set(idleSupportX + x, idleSupportY + y, idleSupportZ + z));
                }
            }
        }

        idleRestPos = position();
        idleRestDelta = getDeltaMovement();
        idleRestBox = box;
        idleTravelArmed = true;
    }

    /**
     * Whether every block state recorded by {@link #armIdleTravel()} is still the same (block states are interned).
     */
    private boolean idleSupportUnchanged()
    {
        final Level level = level();
        int i = 0;
        for (int y = 0; y < idleSupportH; y++)
        {
            for (int x = 0; x < idleSupportW; x++)
            {
                for (int z = 0; z < idleSupportD; z++)
                {
                    if (level.getBlockState(idleScanPos.set(idleSupportX + x, idleSupportY + y, idleSupportZ + z)) != idleSupport[i++])
                    {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public boolean canBeLeashed()
    {
        return false;
    }

    @Override
    public boolean canBeStuck()
    {
        return canBeStuck;
    }

    /**
     * Sets whether the entity currently can be stuck
     *
     * @param canBeStuck
     */
    public void setCanBeStuck(final boolean canBeStuck)
    {
        this.canBeStuck = canBeStuck;
    }

    @Override
    protected boolean isHorizontalCollisionMinor(Vec3 vec3)
    {
        lastHorizontalCollision = level().getGameTime();
        return super.isHorizontalCollisionMinor(vec3);
    }

    /**
     * Whether the citizen collided in the last 10 ticks
     *
     * @return
     */
    public boolean hadHorizontalCollission()
    {
        return level().getGameTime() - lastHorizontalCollision < 10;
    }

    @Override
    public boolean checkBedExists()
    {
        return false;
    }

    @Override
    protected void removeFrost()
    {

    }

    @Override
    protected void tryAddFrost()
    {

    }

    @Override
    public void onInsideBubbleColumn(boolean down)
    {

    }

    @Override
    protected int decreaseAirSupply(final int supply)
    {
        return supply - 1;
    }

    @Override
    protected int increaseAirSupply(final int supply)
    {
        return supply + 1;
    }

    @Override
    protected void onChangedBlock(final ServerLevel level, final BlockPos pos)
    {
        // This just tries to apply soulspeed or frostwalker
    }

    /**
     * Ignores cramming
     */
    @Override
    public void pushEntities()
    {
        if (this.level().isClientSide())
        {
            this.level().getEntities(EntityTypeTest.forClass(Player.class), this.getBoundingBox(), EntityUtils.pushableBy()).forEach(this::doPush);
        }
        else
        {
            if (this.tickCount % 10 == randomVariance % 10)
            {
                entityPushCache = level().getEntitiesOfClass(LivingEntity.class, getBoundingBox(), EntitySelector.pushableBy(this));
            }

            if (!entityPushCache.isEmpty())
            {
                for (int i = 0, entityPushCacheSize = entityPushCache.size(); i < entityPushCacheSize; i++)
                {
                    final Entity entity = entityPushCache.get(i);
                    if (entity != this && getBoundingBox().contains(entity.position()))
                    {
                        this.doPush(entity);
                    }
                }
            }
        }
    }

    /**
     * Prevent citizens and visitors from travelling to other dimensions through portals.
     */
    @Nullable
    @Override
    public Entity teleport(final TeleportTransition dimensionTransition)
    {
        return null;
    }

    @Override
    public boolean canSpawnSprintParticle()
    {
        return false;
    }

    @Override
    public void setSharedFlagOnFire(boolean newState)
    {
        if (newState != onFire)
        {
            super.setSharedFlagOnFire(newState);
            onFire = newState;
        }
    }

    @Override
    protected void handlePortal()
    {
        // Noop our entities dont use portals
    }

    @Override
    public void updateSwimming()
    {
        // Noop our entities dont swim
    }

    /**
     * Throttled fluid scan. 26.x merged 1.21's {@code updateInWaterStateAndDoFluidPushing} (every 10 ticks) and
     * {@code updateFluidOnEyes} (every 20 ticks) into this one method, which scans every fluid block the bounding box touches.
     * Run it on one tick in ten, spread by {@link #randomVariance}, and report the cached result in between.
     */
    @Override
    protected boolean updateFluidInteraction()
    {
        if (tickCount % 10 == randomVariance % 10)
        {
            isInFluid = super.updateFluidInteraction();
        }

        return isInFluid;
    }

    @Override
    public boolean isInWall()
    {
        if (tickCount % 10 == randomVariance % 10)
        {
            return super.isInWall();
        }

        return false;
    }

    @Override
    public boolean isInWaterOrRain()
    {
        // Used to extinguish fire, only check if on fire
        if (getRemainingFireTicks() > 0 || level().isClientSide())
        {
            return super.isInWaterOrRain();
        }

        return false;
    }

    @Override
    public void updateFallFlying()
    {
        // Simplified updateFallflying to only set flags when they did change
        if (!this.level().isClientSide() && tickCount % 5 == randomVariance % 5)
        {
            boolean flag = this.getSharedFlag(7);
            if (!flag || this.onGround() || this.isPassenger() || this.hasEffect(MobEffects.LEVITATION))
            {
                flag = false;
                this.setSharedFlag(7, flag);
            }
        }
    }

    @Override
    public void setTicksFrozen(int p_146918_)
    {

    }

    @Override
    public void setShiftKeyDown(boolean enable)
    {
        if (enable != isShiftKeyDown())
        {
            super.setShiftKeyDown(enable);
        }
    }

    @Override
    public void knockback(double power, double xRatio, double zRatio, DamageSource source, float damage)
    {
        if (level().getGameTime() - lastKnockBack > 20 * 3)
        {
            lastKnockBack = level().getGameTime();
            super.knockback(power, xRatio, zRatio, source, damage);
        }
    }

    @Override
    public boolean hurtServer(final ServerLevel level, final DamageSource dmgSource, final float dmg)
    {
        if (dmgSource.getEntity() instanceof AbstractFastMinecoloniesEntity otherFastMinecolEntity && otherFastMinecolEntity.getTeamId() == getTeamId())
        {
            return false;
        }
        return super.hurtServer(level, dmgSource, dmg);
    }

    /**
     * Get the team name of this entity.
     * todo sam make colony ids unique across dimensions.
     * @return the team name.
     */
    public abstract int getTeamId();
}
