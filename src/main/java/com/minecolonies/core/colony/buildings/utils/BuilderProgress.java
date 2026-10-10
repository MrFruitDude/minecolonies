package com.minecolonies.core.colony.buildings.utils;

/**
 * How far a builder is with his order, as the builder windows show it.
 */
public final class BuilderProgress
{
    private BuilderProgress()
    {
    }

    /**
     * The part of the work that is still to do, from 1 (nothing done) to 0 (done).
     * <p>
     * An order with a material cost counts the materials that are still needed. An order without one (flattening land, for
     * example) has nothing to count there, so it counts blocks, and if the blocks are not known yet, stages. Counting nothing
     * must not read as done.
     *
     * @param amountOfResources the material count the order had at the start, 0 if it costs nothing.
     * @param remainingNeeded   the material count still needed.
     * @param placedBlocks      blocks done so far.
     * @param totalBlocks       blocks to do in all, 0 if not known.
     * @param finishedStages    stages finished so far.
     * @param totalStages       stages in all, 0 if not known.
     * @return the fraction still to do.
     */
    public static double remainingFraction(
      final int amountOfResources,
      final double remainingNeeded,
      final int placedBlocks,
      final int totalBlocks,
      final int finishedStages,
      final int totalStages)
    {
        if (amountOfResources > 0)
        {
            return remainingNeeded / amountOfResources;
        }
        if (totalBlocks > 0)
        {
            return 1.0D - clamp((double) placedBlocks / totalBlocks);
        }
        if (totalStages > 0)
        {
            return 1.0D - clamp((double) finishedStages / totalStages);
        }
        return 1.0D;
    }

    private static double clamp(final double fraction)
    {
        return Math.max(0.0D, Math.min(1.0D, fraction));
    }
}
