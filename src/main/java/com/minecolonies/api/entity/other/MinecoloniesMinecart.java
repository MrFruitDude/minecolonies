package com.minecolonies.api.entity.other;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

/**
 * Special minecolonies minecart that doesn't collide.
 */
public class MinecoloniesMinecart extends Minecart
{
    private static final Vec3 LOWERED_PASSENGER_ATTACHMENT = new Vec3(0.0, 0.0, 0.0);

    /**
     * Constructor to create the minecart.
     *
     * @param type  the entity type.
     * @param world the world.
     */
    public MinecoloniesMinecart(final EntityType<?> type, final Level world)
    {
        super(type, world);
    }

    @Override
    public InteractionResult interact(final Player player, final InteractionHand hand, final Vec3 location)
    {
        return InteractionResult.FAIL;
    }

    /**
     * Citizen carts are spawned per rail trip, so breaking one must not drop a minecart item (free carts).
     */
    @Override
    protected void destroy(@NotNull final ServerLevel level, @NotNull final DamageSource source)
    {
        this.kill(level);
    }

    @Override
    public void tick()
    {
        super.tick();
        // One cart is spawned per rail trip; an empty one is left over and must not pile up in chunks.
        if (!this.level().isClientSide() && this.tickCount % 20 == 19 && this.getPassengers().isEmpty())
        {
            this.discard();
        }
    }

    /**
     * Not rideable for vanilla purposes: stops the rail behaviour from scooping nearby mobs into a moving empty cart.
     * Citizens mount it explicitly through {@code startRiding}.
     */
    @Override
    public boolean isRideable()
    {
        return false;
    }

    @Override
    public boolean isPickable()
    {
        return false;
    }

    @Override
    public void push(@NotNull final Entity entity)
    {
        // Citizens use carts as a transport marker; vanilla pushing is unwanted.
    }

    @Override
    public void playerTouch(final Player entity)
    {
        // Do not pick citizens up on contact.
    }

    @Override
    public boolean isPushable()
    {
        return false;
    }

    @Override
    public boolean canCollideWith(final Entity entity)
    {
        return false;
    }

    @NotNull
    @Override
    protected Vec3 getPassengerAttachmentPoint(@NotNull final Entity passenger, @NotNull final EntityDimensions dimensions, final float scale)
    {
        return LOWERED_PASSENGER_ATTACHMENT;
    }
}
