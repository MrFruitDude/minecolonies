package com.minecolonies.core.entity.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Whether a player would see an entity jump: a player (not a spectator) within {@link #RANGE} blocks of the start or the end of the jump, with
 * nothing that blocks the view between his eyes and that point. The unstuck steps that put a worker somewhere else (a teleport) wait while a
 * player watches and let the worker walk or give the block up instead: a citizen who blinks away in a puff of smoke in front of the player
 * looks broken. Only the true shut-in (no way out at all) is put out in sight.
 */
public final class PlayerWatch
{
    /**
     * How far a player sees a jump (blocks).
     */
    public static final double RANGE = 24.0D;

    /**
     * The mock players of the GameTests (one per scene, all in one world): they watch nothing, so the tests see the workers as before. A test of the
     * watch itself makes a player that is not in this set.
     */
    private static final java.util.Set<java.util.UUID> IGNORED_FOR_TESTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * @param player a GameTest mock player that does not count as a watcher.
     */
    public static void ignoreForTests(final java.util.UUID player)
    {
        IGNORED_FOR_TESTS.add(player);
    }

    private PlayerWatch()
    {
    }

    /**
     * @param level the world.
     * @param from  where the entity stands (feet).
     * @param to    where it would be put (feet), or null.
     * @return true if a player sees one of the two points.
     */
    public static boolean watched(final Level level, final Vec3 from, @Nullable final Vec3 to)
    {
        if (level.isClientSide())
        {
            return false;
        }
        for (final Player player : level.players())
        {
            if (player.isSpectator() || !player.isAlive() || IGNORED_FOR_TESTS.contains(player.getUUID()))
            {
                continue;
            }
            if (sees(level, player, from) || to != null && sees(level, player, to))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * @see #watched(Level, Vec3, Vec3)
     */
    public static boolean watched(final Level level, final Vec3 from, @Nullable final BlockPos to)
    {
        return watched(level, from, to == null ? null : Vec3.atBottomCenterOf(to));
    }

    private static boolean sees(final Level level, final Player player, final Vec3 feet)
    {
        final Vec3 body = feet.add(0.0D, 1.0D, 0.0D);
        final Vec3 eyes = player.getEyePosition();
        if (eyes.distanceToSqr(body) > RANGE * RANGE)
        {
            return false;
        }
        return level.clip(new ClipContext(eyes, body, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
    }
}
