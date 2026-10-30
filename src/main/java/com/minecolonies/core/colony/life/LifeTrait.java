package com.minecolonies.core.colony.life;

import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.entity.citizen.citizenhandlers.ICitizenSkillHandler;
import com.minecolonies.core.entity.ai.workers.CitizenAI;
import org.jetbrains.annotations.Nullable;

import java.util.function.ToIntFunction;

/**
 * Round 4 life: the one trait a citizen shows, read from the skills MineColonies already gives him (no new saved data). Two citizens with the same job differ a
 * little in their free time: how fast they walk off work, how often they chat, when they wake. Work itself is never slowed.
 */
public enum LifeTrait
{
    /**
     * Athletics, agility and stamina: walks a bit faster off work and is up first.
     */
    BRISK(0.08D, 20, 1200),
    /**
     * Adaptability, creativity and mana: chats most.
     */
    CHATTY(0.0D, 40, 600),
    /**
     * Intelligence, knowledge and focus: quiet, a slower stroll, sleeps in a little.
     */
    THOUGHTFUL(-0.05D, 10, 2400),
    /**
     * Strength and dexterity: the even-paced middle.
     */
    STEADY(0.0D, 25, 1200);

    /**
     * The spread of the wake delay of a brisk or a thoughtful citizen (a third of {@link CitizenAI#MAX_WAKE_DELAY}).
     */
    private static final int WAKE_BAND = CitizenAI.MAX_WAKE_DELAY / 3;

    private final double offWorkPace;
    private final int    chatChance;
    private final int    chatCooldown;

    LifeTrait(final double offWorkPace, final int chatChance, final int chatCooldown)
    {
        this.offWorkPace = offWorkPace;
        this.chatChance = chatChance;
        this.chatCooldown = chatCooldown;
    }

    /**
     * @return the walking speed change off work, as a fraction of the base speed (never applied at work).
     */
    public double offWorkPace()
    {
        return offWorkPace;
    }

    /**
     * @return the chance in percent that the citizen starts a chat when a neighbour is free, per try.
     */
    public int chatChance()
    {
        return chatChance;
    }

    /**
     * @return the ticks between two chats the citizen starts.
     */
    public int chatCooldown()
    {
        return chatCooldown;
    }

    /**
     * The extra sleep after dawn: a brisk citizen wakes in the first third of the usual spread, a thoughtful one in the last third, the others as before. Never
     * beyond {@link CitizenAI#MAX_WAKE_DELAY}.
     *
     * @param citizenId the citizen id.
     * @return 0 to {@link CitizenAI#MAX_WAKE_DELAY} ticks.
     */
    public int wakeDelay(final int citizenId)
    {
        return switch (this)
        {
            case BRISK -> Math.floorMod(citizenId * 7919, WAKE_BAND + 1);
            case THOUGHTFUL -> CitizenAI.MAX_WAKE_DELAY - Math.floorMod(citizenId * 7919, WAKE_BAND + 1);
            default -> CitizenAI.wakeDelay(citizenId);
        };
    }

    /**
     * @return the lang key of the trait's name.
     */
    public String nameKey()
    {
        return "minecolonies.life.trait." + name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * @return the lang key of the one short line that describes the trait.
     */
    public String lineKey()
    {
        return nameKey() + ".line";
    }

    /**
     * The trait of a citizen: the skill group with the highest sum; a tie goes by the citizen id, so equal citizens still differ.
     *
     * @param citizenId the citizen id.
     * @param level     the level of each skill.
     * @return the trait.
     */
    public static LifeTrait of(final int citizenId, final ToIntFunction<Skill> level)
    {
        final int[] score = {
          level.applyAsInt(Skill.Athletics) + level.applyAsInt(Skill.Agility) + level.applyAsInt(Skill.Stamina),
          level.applyAsInt(Skill.Adaptability) + level.applyAsInt(Skill.Creativity) + level.applyAsInt(Skill.Mana),
          level.applyAsInt(Skill.Intelligence) + level.applyAsInt(Skill.Knowledge) + level.applyAsInt(Skill.Focus),
          (level.applyAsInt(Skill.Strength) + level.applyAsInt(Skill.Dexterity)) * 3 / 2};
        final LifeTrait[] traits = values();
        final int start = Math.floorMod(citizenId, traits.length);
        int best = start;
        for (int i = 1; i < traits.length; i++)
        {
            final int at = (start + i) % traits.length;
            if (score[at] > score[best])
            {
                best = at;
            }
        }
        return traits[best];
    }

    /**
     * The trait of a citizen from its skill handler (server data or client view).
     *
     * @param citizenId the citizen id.
     * @param skills    the skill handler, null for none.
     * @return the trait, {@link #STEADY} without skills.
     */
    public static LifeTrait of(final int citizenId, @Nullable final ICitizenSkillHandler skills)
    {
        return skills == null ? STEADY : of(citizenId, skills::getLevel);
    }
}
