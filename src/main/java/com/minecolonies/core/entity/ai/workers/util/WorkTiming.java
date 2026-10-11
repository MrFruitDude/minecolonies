package com.minecolonies.core.entity.ai.workers.util;

/**
 * How long a worker takes for one block: the formulas of placing and breaking in ticks, pure so they can be tested without a world.
 * All results are in ticks at pace 1.0 (the pace of the colony divides the wait, see {@code AbstractEntityAIBasic}).
 */
public final class WorkTiming
{
    private WorkTiming()
    {
    }

    /**
     * Ticks to place one block at skill level 0 before the level scaling.
     */
    public static final int BUILD_BLOCK_DELAY = 15;

    /**
     * The skill divisor of the placing and progress formulas.
     */
    public static final int PROGRESS_MULTIPLIER = 10;

    /**
     * Base ticks to mine a block of hardness 1 with a tool of speed 1 at skill level 0.
     */
    public static final int BLOCK_MINING_DELAY = 500;

    /**
     * Mining time factor per two skill levels.
     */
    public static final double LEVEL_MODIFIER = 0.85D;

    /**
     * The fastest a builder places a block, in ticks.
     */
    public static final double MIN_PLACE_TICKS = 1.0D;

    /**
     * The fastest a builder breaks a block, in ticks.
     */
    public static final double MIN_BREAK_TICKS = 2.0D;

    /**
     * What is left of a delay after a research bonus (the research effect strength, 0 for none, 1 for +100%).
     *
     * @param strength the research effect strength.
     * @return the factor for the delay.
     */
    public static double researchFactor(final double strength)
    {
        return 1.0D - strength;
    }

    /**
     * Ticks to place one block.
     *
     * @param placeLevel      the placing skill level of the builder.
     * @param researchStrength the research effect strength of block placing speed.
     * @param builderSpeed    the builder speed multiplier of the settings (1.0 for none).
     * @return the ticks, fractional, never below {@link #MIN_PLACE_TICKS}.
     */
    public static double placeTicks(final int placeLevel, final double researchStrength, final double builderSpeed)
    {
        final double base = (double) BUILD_BLOCK_DELAY * PROGRESS_MULTIPLIER / (placeLevel / 2 + PROGRESS_MULTIPLIER);
        return base * researchFactor(researchStrength) / builderSpeed;
    }

    /**
     * Ticks to mine one block.
     *
     * @param breakLevel       the breaking skill level.
     * @param hardness         the hardness (destroy speed) of the block.
     * @param toolSpeed        the destroy speed of the held tool for this block.
     * @param roleFactor       a factor of the job (builders: 0.5).
     * @param researchStrength the research effect strength of block breaking speed.
     * @param builderSpeed     the builder speed multiplier of the settings (1.0 for none and for other jobs).
     * @return the ticks, fractional.
     */
    public static double breakTicks(
      final int breakLevel,
      final double hardness,
      final double toolSpeed,
      final double roleFactor,
      final double researchStrength,
      final double builderSpeed)
    {
        return BLOCK_MINING_DELAY * Math.pow(LEVEL_MODIFIER, breakLevel / 2.0) * hardness / toolSpeed * roleFactor * researchFactor(researchStrength) / builderSpeed;
    }
}
