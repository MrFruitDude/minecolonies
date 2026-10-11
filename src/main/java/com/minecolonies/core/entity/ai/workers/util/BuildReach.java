package com.minecolonies.core.entity.ai.workers.util;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * The physical reach of a builder: a block is placed or broken only when it lies within the reach distance (3D) of the builder's eye, measured
 * to the nearest point of the block (like the interaction range of a player). Pure math, so it can be tested without a world.
 */
public final class BuildReach
{
    private BuildReach()
    {
    }

    /**
     * Eye height of a standing citizen above his feet.
     */
    public static final double EYE_HEIGHT = 1.62D;

    /**
     * Squared distance from a point to the nearest point of a block.
     */
    public static double distSq(final double x, final double y, final double z, final BlockPos block)
    {
        final double dx = axis(x, block.getX());
        final double dy = axis(y, block.getY());
        final double dz = axis(z, block.getZ());
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axis(final double v, final int blockMin)
    {
        if (v < blockMin)
        {
            return blockMin - v;
        }
        if (v > blockMin + 1)
        {
            return v - (blockMin + 1);
        }
        return 0.0D;
    }

    /**
     * @param x     eye x.
     * @param y     eye y.
     * @param z     eye z.
     * @param block the block.
     * @param reach the reach in blocks.
     * @return true if the block lies within reach of the eye.
     */
    public static boolean inReach(final double x, final double y, final double z, final BlockPos block, final double reach)
    {
        return distSq(x, y, z, block) <= reach * reach;
    }

    /**
     * Whether a citizen standing in the middle of the feet block (so with the eye above it) reaches the block.
     */
    public static boolean inReachFromFeet(final BlockPos feet, final double eyeHeight, final BlockPos block, final double reach)
    {
        return inReach(feet.getX() + 0.5D, feet.getY() + eyeHeight, feet.getZ() + 0.5D, block, reach);
    }

    /**
     * How many of the blocks, from the first on and without a gap, are within reach of a citizen standing on the feet block.
     */
    public static int prefixInReach(final BlockPos feet, final double eyeHeight, final List<BlockPos> blocks, final double reach)
    {
        int n = 0;
        for (final BlockPos block : blocks)
        {
            if (!inReachFromFeet(feet, eyeHeight, block, reach))
            {
                break;
            }
            n++;
        }
        return n;
    }

    /**
     * The highest block (height above the feet of a citizen who stands right next to a wall, half a block from it) that is still in reach.
     */
    public static int maxHeightNextToWall(final double eyeHeight, final double reach)
    {
        int h = 0;
        while (inReachFromFeet(BlockPos.ZERO, eyeHeight, new BlockPos(1, h + 1, 0), reach))
        {
            h++;
        }
        return h;
    }
}
