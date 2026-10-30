package com.minecolonies.core.colony.life;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.api.colony.buildings.IBuildingWorkerModule;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.entity.citizen.citizenhandlers.ICitizenSkillHandler;
import com.minecolonies.core.entity.ai.workers.CitizenAI;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Round 4 life, made stable in round 5: the one trait a citizen shows, picked once from his skills (the skills of his job do not count) and saved with him, so it
 * never drifts as his job trains him. Each trait has one habit a player can see; work itself is never slowed.
 */
public enum LifeTrait
{
    /**
     * Athletics, agility and stamina: up first in the morning and quick on his feet off work.
     */
    BRISK(0.12D, 20, 1200, 5, 45, true),
    /**
     * Adaptability, creativity and mana: stops for a word with anyone and greets most mornings.
     */
    CHATTY(0.0D, 40, 600, 5, 70, true),
    /**
     * Intelligence, knowledge and focus: quiet, sleeps in, often found reading at the library.
     */
    THOUGHTFUL(0.0D, 10, 2400, 25, 15, true),
    /**
     * Strength and dexterity: unbothered by the rain (no hurry, no walk home, a calm word instead).
     */
    STEADY(0.0D, 25, 1200, 5, 30, false);

    /**
     * The spread of the wake delay of a brisk or a thoughtful citizen (a third of {@link CitizenAI#MAX_WAKE_DELAY}).
     */
    private static final int WAKE_BAND = CitizenAI.MAX_WAKE_DELAY / 3;

    private final double  offWorkPace;
    private final int     chatChance;
    private final int     chatCooldown;
    private final int     readChance;
    private final int     greetChance;
    private final boolean mindsRain;

    LifeTrait(final double offWorkPace, final int chatChance, final int chatCooldown, final int readChance, final int greetChance, final boolean mindsRain)
    {
        this.offWorkPace = offWorkPace;
        this.chatChance = chatChance;
        this.chatCooldown = chatCooldown;
        this.readChance = readChance;
        this.greetChance = greetChance;
        this.mindsRain = mindsRain;
    }

    /**
     * @return the walking speed change in free time, as a fraction of the base speed (never applied at work, never below 0).
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
     * @return the chance in percent that a citizen at a library or university sits down with a book, per look (MineColonies' own is 5).
     */
    public int readChance()
    {
        return readChance;
    }

    /**
     * @return the chance in percent that the citizen greets the others on a given morning.
     */
    public int greetChance()
    {
        return greetChance;
    }

    /**
     * @return whether the rain bothers the citizen (a hurry and a walk home without a hat); a steady one walks on.
     */
    public boolean mindsRain()
    {
        return mindsRain;
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
     * The trait from skill levels: the skill group with the highest sum; a tie goes by the citizen id, so equal citizens still differ.
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
     * The trait to pick for a citizen: as {@link #of(int, ToIntFunction)}, but the skills his job trains count as the mean of his other skills, so a long career
     * does not decide who he is.
     *
     * @param citizenId the citizen id.
     * @param level     the level of each skill.
     * @param jobSkills the skills of his job (none for a jobless citizen).
     * @return the trait.
     */
    public static LifeTrait pick(final int citizenId, final ToIntFunction<Skill> level, final Set<Skill> jobSkills)
    {
        if (jobSkills.isEmpty())
        {
            return of(citizenId, level);
        }
        int sum = 0;
        int count = 0;
        for (final Skill skill : Skill.values())
        {
            if (!jobSkills.contains(skill))
            {
                sum += level.applyAsInt(skill);
                count++;
            }
        }
        final int mean = count == 0 ? 0 : sum / count;
        return of(citizenId, skill -> jobSkills.contains(skill) ? mean : level.applyAsInt(skill));
    }

    /**
     * The citizen's trait: the saved one, else picked now from his skills and saved (once for his life).
     *
     * @param data the citizen.
     * @return the trait.
     */
    public static LifeTrait of(final ICitizenData data)
    {
        final LifeTrait saved = byName(data.getLifeTrait());
        if (saved != null)
        {
            return saved;
        }
        final Set<Skill> jobSkills = EnumSet.noneOf(Skill.class);
        if (data.getJob() != null && data.getJob().getWorkModule() instanceof IBuildingWorkerModule module)
        {
            jobSkills.add(module.getPrimarySkill());
            jobSkills.add(module.getSecondarySkill());
        }
        final ICitizenSkillHandler skills = data.getCitizenSkillHandler();
        final LifeTrait picked = skills == null ? STEADY : pick(data.getId(), skills::getLevel, jobSkills);
        data.setLifeTrait(picked.name());
        return picked;
    }

    /**
     * The trait a client view shows: the one the server saved, else as read from the view's skills.
     *
     * @param view the citizen view.
     * @return the trait.
     */
    public static LifeTrait of(final ICitizenDataView view)
    {
        final LifeTrait saved = byName(view.getLifeTrait());
        return saved != null ? saved : of(view.getId(), view.getCitizenSkillHandler());
    }

    /**
     * The trait from its saved name.
     *
     * @param name the name, "" or null for none.
     * @return the trait, null for none or an unknown name.
     */
    @Nullable
    public static LifeTrait byName(@Nullable final String name)
    {
        if (name == null || name.isEmpty())
        {
            return null;
        }
        for (final LifeTrait trait : values())
        {
            if (trait.name().equals(name))
            {
                return trait;
            }
        }
        return null;
    }

    /**
     * The trait from a skill handler (server data or client view).
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
