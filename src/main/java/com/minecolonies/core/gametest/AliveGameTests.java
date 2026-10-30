package com.minecolonies.core.gametest;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.colony.life.CitizenLife;
import com.minecolonies.core.colony.life.ColonyLife;
import com.minecolonies.core.colony.life.LifeTrait;
import com.minecolonies.core.entity.ai.minimal.EntityAICitizenLife;
import com.minecolonies.core.entity.ai.minimal.EntityAICitizenWander;
import com.minecolonies.core.entity.ai.workers.CitizenAI;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Round 4 lane C, life and personality: chats at leisure, cheers for a finished building, grief for a friend, relief after a raid, hurry in the rain, the morning
 * greeting, traits. Group "alive" (MINECOLONIES_GAMETEST_ONLY=alive, glob minecolonies:alive_*). Each test checks a trigger, its bounds, and that the citizen AI's
 * own decisions (food, work) still win at once.
 */
public final class AliveGameTests
{
    public static final String[] NAMES = {"alive_rules", "alive_chat", "alive_chat_yields", "alive_morning", "alive_building_cheer", "alive_grief", "alive_raid_relief",
      "alive_rain_hurry", "alive_rain_where", "alive_traits_stable", "alive_chat_pairs", "alive_calm_pace", "alive_morning_crowd", "alive_shelter_inside",
      "alive_no_cheer", "alive_shelter_dry", "alive_shelter_chat", "alive_chorus", "alive_lines_wait", "alive_morning_dawn"};

    /**
     * Tests whose citizens must stand in the rain: they run without the barrier ceiling of the GameTest frame, which stops the rain (vanilla's isRainingAt
     * reads the MOTION_BLOCKING heightmap) while it lets the sky light through.
     */
    public static final java.util.Set<String> NEEDS_SKY = java.util.Set.of("alive_rain_hurry", "alive_rain_where", "alive_shelter_inside", "alive_shelter_dry",
      "alive_shelter_chat", "alive_chorus", "alive_lines_wait");

    /**
     * A life moment must give way within this many ticks: the AI decides every 10 ticks, a life step runs every 20, the setting is read once a second.
     */
    private static final int YIELD_TICKS = 60;

    /**
     * The cheers of a finished building are said within this many ticks: the last is due 70 ticks out, a line is said at the citizen's next once-a-second tick,
     * and a fresh entity's first life tick can wait for its 40-tick start (r4 N34).
     */
    private static final int CHEER_WINDOW = 300;

    private AliveGameTests()
    {
    }

    public static Consumer<GameTestHelper> test(final String name)
    {
        return switch (name)
        {
            case "alive_rules" -> AliveGameTests::rules;
            case "alive_chat" -> AliveGameTests::chat;
            case "alive_chat_yields" -> AliveGameTests::chatYields;
            case "alive_morning" -> AliveGameTests::morning;
            case "alive_building_cheer" -> AliveGameTests::buildingCheer;
            case "alive_grief" -> AliveGameTests::grief;
            case "alive_raid_relief" -> AliveGameTests::raidRelief;
            case "alive_rain_where" -> AliveGameTests::rainWhere;
            case "alive_traits_stable" -> AliveGameTests::traitsStable;
            case "alive_chat_pairs" -> AliveGameTests::chatPairs;
            case "alive_calm_pace" -> AliveGameTests::calmPace;
            case "alive_morning_crowd" -> AliveGameTests::morningCrowd;
            case "alive_shelter_inside" -> AliveGameTests::shelterInside;
            case "alive_no_cheer" -> AliveGameTests::noCheer;
            case "alive_shelter_dry" -> AliveGameTests::shelterDry;
            case "alive_shelter_chat" -> AliveGameTests::shelterChat;
            case "alive_chorus" -> AliveGameTests::chorus;
            case "alive_lines_wait" -> AliveGameTests::linesWait;
            case "alive_morning_dawn" -> AliveGameTests::morningDawn;
            default -> AliveGameTests::rainHurry;
        };
    }

    // ------------------------------------------------------------------ pure rules

    /**
     * Setting default, traits, wake delays inside the old bound, no slower walk at work, speech parsing, the bounds of the moments.
     */
    public static void rules(final GameTestHelper helper)
    {
        helper.assertTrue(MineColonies.getConfig().getServer().citizenLifeExtras.getDefault(), "citizenlifeextras does not default to true");

        // traits: the strongest skill group decides; equal skills spread over all four by id
        helper.assertTrue(LifeTrait.of(1, skills(Skill.Athletics, Skill.Agility, Skill.Stamina)) == LifeTrait.BRISK, "brisk");
        helper.assertTrue(LifeTrait.of(1, skills(Skill.Adaptability, Skill.Creativity, Skill.Mana)) == LifeTrait.CHATTY, "chatty");
        helper.assertTrue(LifeTrait.of(1, skills(Skill.Intelligence, Skill.Knowledge, Skill.Focus)) == LifeTrait.THOUGHTFUL, "thoughtful");
        helper.assertTrue(LifeTrait.of(1, skills(Skill.Strength, Skill.Dexterity)) == LifeTrait.STEADY, "steady");
        final EnumSet<LifeTrait> seen = EnumSet.noneOf(LifeTrait.class);
        for (int id = 1; id <= 8; id++)
        {
            final LifeTrait t = LifeTrait.of(id, s -> 5);
            helper.assertTrue(t == LifeTrait.of(id, s -> 5), "trait not stable for " + id);
            seen.add(t);
        }
        helper.assertTrue(seen.size() == 4, "equal skills give only " + seen);
        helper.assertTrue(LifeTrait.of(3, (com.minecolonies.api.entity.citizen.citizenhandlers.ICitizenSkillHandler) null) == LifeTrait.STEADY, "no skills");

        // round 5: a trait is picked without the skills of the job, so a long career does not decide it
        final java.util.function.ToIntFunction<Skill> career = sk -> sk == Skill.Athletics || sk == Skill.Stamina ? 30
                                                                     : sk == Skill.Adaptability || sk == Skill.Creativity || sk == Skill.Mana ? 8 : 3;
        helper.assertTrue(LifeTrait.of(1, career) == LifeTrait.BRISK, "the job skills should decide without the round 5 rule");
        helper.assertTrue(LifeTrait.pick(1, career, java.util.Set.of(Skill.Athletics, Skill.Stamina)) == LifeTrait.CHATTY,
          "job skills decide the trait: " + LifeTrait.pick(1, career, java.util.Set.of(Skill.Athletics, Skill.Stamina)));
        helper.assertTrue(LifeTrait.pick(1, career, java.util.Set.of()) == LifeTrait.BRISK, "a jobless pick differs from the skill rule");
        for (final LifeTrait t : LifeTrait.values())
        {
            helper.assertTrue(LifeTrait.byName(t.name()) == t, "trait name " + t);
        }
        helper.assertTrue(LifeTrait.byName("") == null && LifeTrait.byName("NOPE") == null, "unknown trait names");

        // round 5: each trait has one habit a player can see
        helper.assertTrue(LifeTrait.BRISK.offWorkPace() >= 0.1D, "brisk is not quick");
        for (final LifeTrait t : LifeTrait.values())
        {
            helper.assertTrue(t.offWorkPace() >= 0, t + " walks slower");
            helper.assertTrue(t == LifeTrait.CHATTY || t.chatChance() < LifeTrait.CHATTY.chatChance(), t + " chats as much as a chatty one");
            helper.assertTrue(t == LifeTrait.THOUGHTFUL || t.readChance() < LifeTrait.THOUGHTFUL.readChance(), t + " reads as much as a thoughtful one");
            helper.assertTrue(t.mindsRain() == (t != LifeTrait.STEADY), t + " minds the rain " + t.mindsRain());
        }
        helper.assertTrue(LifeTrait.THOUGHTFUL.readChance() >= 20, "thoughtful barely reads");

        // wake: never beyond the old bound; brisk early, thoughtful late
        for (int id = 1; id <= 200; id++)
        {
            for (final LifeTrait t : LifeTrait.values())
            {
                final int d = t.wakeDelay(id);
                helper.assertTrue(d >= 0 && d <= CitizenAI.MAX_WAKE_DELAY, t + " wake delay " + d + " of " + id);
            }
            helper.assertTrue(LifeTrait.BRISK.wakeDelay(id) <= CitizenAI.MAX_WAKE_DELAY / 3, "brisk wakes late: " + LifeTrait.BRISK.wakeDelay(id));
            helper.assertTrue(LifeTrait.THOUGHTFUL.wakeDelay(id) >= CitizenAI.MAX_WAKE_DELAY * 2 / 3, "thoughtful wakes early: " + LifeTrait.THOUGHTFUL.wakeDelay(id));
            helper.assertTrue(LifeTrait.STEADY.wakeDelay(id) == CitizenAI.wakeDelay(id), "steady changed its wake time");
        }

        // pace: a rain hurry is faster, grief is slower in free time only; never slower at work, in flight, asleep or hiding, eating, sick or mourning
        for (final LifeTrait t : LifeTrait.values())
        {
            for (final boolean rain : new boolean[] {false, true})
            {
                for (final boolean grief : new boolean[] {false, true})
                {
                    helper.assertTrue(CitizenLife.pace(rain, false, t, grief) >= 0, "slower outside free time: " + t + " rain " + rain + " grief " + grief);
                }
            }
            helper.assertTrue(CitizenLife.pace(false, true, t, true) < 0, "grief not slower in free time for " + t);
            helper.assertTrue(CitizenLife.pace(true, false, t, false) > 0, "rain hurry not faster for " + t);
        }
        helper.assertTrue(CitizenLife.isAtWork(CitizenAIState.WORK) && CitizenLife.isAtWork(CitizenAIState.WORKING) && CitizenLife.isAtWork(CitizenAIState.LABOUR)
                            && !CitizenLife.isAtWork(CitizenAIState.IDLE), "at work states");
        final List<IState> busy = new ArrayList<>(List.of(CitizenAIState.WORK, CitizenAIState.WORKING, CitizenAIState.LABOUR, CitizenAIState.EATING,
          CitizenAIState.SLEEP, CitizenAIState.MOURN, CitizenAIState.SICK, CitizenAIState.FLEE));
        busy.addAll(List.of(com.minecolonies.core.entity.ai.minimal.EntityAICitizenAvoidEntity.FleeStates.values()));
        busy.addAll(List.of(com.minecolonies.core.entity.ai.minimal.EntityAISickTask.DiseaseState.values()));
        busy.addAll(List.of(com.minecolonies.core.entity.ai.minimal.EntityAISleep.SleepState.values()));
        busy.addAll(List.of(com.minecolonies.core.entity.ai.minimal.EntityAIEatTask.EatingState.values()));
        for (final IState state : busy)
        {
            helper.assertTrue(!CitizenLife.isFreeTime(state), "trait or grief pace in " + state);
        }
        helper.assertTrue(CitizenLife.isFreeTime(CitizenAIState.IDLE), "idle is free time");
        for (final IState state : EntityAICitizenWander.WanderState.values())
        {
            helper.assertTrue(CitizenLife.isFreeTime(state), state + " is free time");
        }
        for (final IState state : EntityAICitizenLife.LifeState.values())
        {
            helper.assertTrue(CitizenLife.isFreeTime(state), state + " is free time");
        }
        for (final IState state : new IState[] {CitizenAIState.WORK, CitizenAIState.WORKING, CitizenAIState.LABOUR, CitizenAIState.EATING, CitizenAIState.SLEEP,
          CitizenAIState.MOURN, CitizenAIState.SICK})
        {
            helper.assertTrue(!CitizenLife.inFreeTime(state), "a chat may run in " + state);
        }
        helper.assertTrue(CitizenLife.inFreeTime(CitizenAIState.IDLE) && CitizenLife.inFreeTime(EntityAICitizenWander.WanderState.WANDER_AT_LEISURE_SITE)
                            && CitizenLife.inFreeTime(EntityAICitizenLife.LifeState.CHAT) && CitizenLife.inFreeTime(EntityAICitizenLife.LifeState.SHELTER),
          "free time states");

        // round 5 chats: the topic fits the moment, the reply fits the opener
        final net.minecraft.world.level.biome.Biome.Precipitation none = net.minecraft.world.level.biome.Biome.Precipitation.NONE;
        final net.minecraft.world.level.biome.Biome.Precipitation rain = net.minecraft.world.level.biome.Biome.Precipitation.RAIN;
        final net.minecraft.world.level.biome.Biome.Precipitation snow = net.minecraft.world.level.biome.Biome.Precipitation.SNOW;
        for (int roll = 0; roll < 100; roll++)
        {
            helper.assertTrue(CitizenLife.topic(true, rain, true, true, true, roll) == CitizenLife.ChatTopic.GRIEF, "a griever talks of something else");
            final CitizenLife.ChatTopic wet = CitizenLife.topic(false, rain, false, true, false, roll);
            helper.assertTrue(wet == CitizenLife.ChatTopic.RAIN, "in the rain: " + wet);
            helper.assertTrue(CitizenLife.topic(false, snow, true, true, false, roll) == CitizenLife.ChatTopic.COLD, "in the snow");
            final CitizenLife.ChatTopic dry = CitizenLife.topic(false, none, false, false, false, roll);
            helper.assertTrue(dry == CitizenLife.ChatTopic.UNHAPPY, "an unhappy citizen on a dry day: " + dry);
            helper.assertTrue(CitizenLife.topic(false, none, false, true, false, roll) == CitizenLife.ChatTopic.HAPPY, "a happy citizen on a dry day");
            helper.assertTrue(CitizenLife.topic(false, rain, false, true, true, roll) != CitizenLife.ChatTopic.HAPPY, "a lovely day in the rain");
        }
        helper.assertTrue(CitizenLife.topic(false, none, false, true, true, 0) == CitizenLife.ChatTopic.JOB, "no job talk");
        helper.assertTrue(CitizenLife.topic(false, none, true, true, false, 50) == CitizenLife.ChatTopic.EVENING, "no evening talk");
        helper.assertTrue(CitizenLife.replyFor("minecolonies.life.chat.rain.2", 1).equals("minecolonies.life.chat.rain.2.reply.1"), "plain reply");

        // round 6: openers fit the time of day and the speaker
        for (final CitizenLife.ChatTopic topic : CitizenLife.ChatTopic.values())
        {
            boolean night = false;
            for (int i = 1; i <= topic.lines(); i++)
            {
                helper.assertTrue(topic.fits(i, true), topic + " " + i + " does not fit in daylight");
                night |= topic.fits(i, false);
            }
            helper.assertTrue(night, topic + " has no opener for the night");
        }
        helper.assertTrue(!CitizenLife.ChatTopic.HAPPY.fits(1, false), "\"Lovely day\" at night");
        helper.assertTrue(CitizenLife.morningLines(false) < CitizenLife.MORNING_JOB_LINE && CitizenLife.morningLines(true) == CitizenLife.MORNING_LINES,
          "the job line is in the jobless pool");
        helper.assertTrue(!CitizenLife.traitMorningFits(LifeTrait.STEADY, false) && CitizenLife.traitMorningFits(LifeTrait.STEADY, true)
                            && CitizenLife.traitMorningFits(LifeTrait.CHATTY, false), "trait morning lines for the jobless");
        final String griefOpener = CitizenLife.ChatTopic.GRIEF.prefix() + "1|n:Ann";
        helper.assertTrue(CitizenLife.griefReply(griefOpener, "Ann", 1) == 1 && CitizenLife.griefReply(griefOpener, null, 1) == 2
                            && CitizenLife.griefReply(griefOpener, "Bob", 1) == 2 && CitizenLife.griefReply(griefOpener, "Ann", 2) == 2, "grief replies");

        // round 6: the citizens of a crowd say their rain or raid line one after the other
        final java.util.Set<Integer> bins = new java.util.HashSet<>();
        for (int id = 1; id <= 12; id++)
        {
            final int d = CitizenLife.chorusDelay(id);
            helper.assertTrue(d >= 0 && d + 20 <= CitizenLife.CHORUS_SPREAD, "chorus delay " + d + " of " + id);
            bins.add(d / 20);
        }
        helper.assertTrue(bins.size() >= 3, "a crowd speaks at once: " + bins);
        helper.assertTrue(CitizenLife.replyFor("minecolonies.life.chat.job.1|t:com.minecolonies.job.builder", 2)
                            .equals("minecolonies.life.chat.job.1.reply.2|t:com.minecolonies.job.builder"), "reply keeps the argument");

        // every line the code can say has its words in the lang file
        final com.google.gson.JsonObject lang = langFile(helper);
        final List<String> keys = new ArrayList<>();
        for (final CitizenLife.ChatTopic topic : CitizenLife.ChatTopic.values())
        {
            for (int i = 1; i <= topic.lines(); i++)
            {
                keys.add(topic.prefix() + i);
                for (int r = 1; r <= CitizenLife.CHAT_REPLIES; r++)
                {
                    keys.add(CitizenLife.replyFor(topic.prefix() + i, r));
                }
            }
        }
        for (int i = 1; i <= CitizenLife.RAIN_LINES; i++)
        {
            keys.add(CitizenLife.RAIN + i);
        }
        for (int i = 1; i <= CitizenLife.MORNING_LINES; i++)
        {
            keys.add(CitizenLife.MORNING + i);
        }
        for (int i = 1; i <= CitizenLife.RAID_END_LINES; i++)
        {
            keys.add(CitizenLife.RAID_END + i);
        }
        for (int i = 1; i <= CitizenLife.CHEER_BUILDING_LINES; i++)
        {
            keys.add(CitizenLife.CHEER_BUILDING + i);
        }
        for (int i = 1; i <= CitizenLife.RAID_START_LINES; i++)
        {
            keys.add(CitizenLife.RAID_START + i);
        }
        keys.addAll(List.of(CitizenLife.RAIN_CALM, CitizenLife.RAID_END_GRIEF, CitizenLife.RAID_END_QUIET, CitizenLife.GRIEF_LINE,
          CitizenLife.CHAT_GRIEF_OWN));
        for (final LifeTrait t : LifeTrait.values())
        {
            keys.addAll(List.of(t.nameKey(), t.lineKey(), t.nameKey() + ".morning"));
        }
        final List<String> missing = keys.stream().filter(k -> !lang.has(k)).toList();
        helper.assertTrue(missing.isEmpty(), "lines without words: " + missing);
        for (final LifeTrait t : LifeTrait.values())
        {
            helper.assertTrue(lang.get(t.lineKey()).getAsString().length() <= 48, "the " + t + " trait line is long");
        }
        // round 6 (N42): the steady line promises only what the trait does (MineColonies' rain rule for work still applies)
        final String steady = lang.get(LifeTrait.STEADY.lineKey()).getAsString();
        helper.assertTrue(steady.contains("rain") && !steady.contains("even in the rain"), "the steady line: " + steady);
        helper.assertTrue(lang.get(CitizenLife.MORNING + CitizenLife.MORNING_JOB_LINE).getAsString().contains("job"), "the job morning line moved");

        // round 5 morning: a different set of citizens each day, by the chance of the trait
        int chatty = 0;
        int thoughtful = 0;
        boolean differs = false;
        for (int id = 1; id <= 200; id++)
        {
            chatty += CitizenLife.greetsOn(id, 7, LifeTrait.CHATTY) ? 1 : 0;
            thoughtful += CitizenLife.greetsOn(id, 7, LifeTrait.THOUGHTFUL) ? 1 : 0;
            differs |= CitizenLife.greetsOn(id, 7, LifeTrait.STEADY) != CitizenLife.greetsOn(id, 8, LifeTrait.STEADY);
        }
        helper.assertTrue(chatty > 100 && chatty < 180 && thoughtful > 5 && thoughtful < 60 && differs, "greeters chatty " + chatty + " thoughtful " + thoughtful
                                                                                                         + " differ by day " + differs);

        // speech: a key, a key with a name, a key with a lang key, nothing
        helper.assertTrue(AbstractEntityCitizen.speechComponent("") == null && AbstractEntityCitizen.speechComponent(null) == null, "empty speech");
        helper.assertTrue(key(AbstractEntityCitizen.speechComponent("minecolonies.life.morning.1")).equals("minecolonies.life.morning.1"), "plain key");
        final Component named = AbstractEntityCitizen.speechComponent(CitizenLife.GRIEF_LINE + "|n:Ann");
        helper.assertTrue(key(named).equals(CitizenLife.GRIEF_LINE) && args(named).length == 1 && ((Component) args(named)[0]).getString().equals("Ann"), "name arg");
        final String jobKey = CitizenLife.ChatTopic.JOB.prefix() + 1;
        final Component job = AbstractEntityCitizen.speechComponent(jobKey + "|t:com.minecolonies.job.builder");
        helper.assertTrue(key(job).equals(jobKey) && key((Component) args(job)[0]).equals("com.minecolonies.job.builder"), "lang key arg");
        helper.assertTrue(ColonyLife.firstName("Bonnie F. LaClair").equals("Bonnie") && ColonyLife.firstName("Ann").equals("Ann"), "first names");

        // bounds of the moments
        helper.assertTrue(CitizenLife.SPEECH_TICKS <= 100 && CitizenLife.CHAT_TICKS <= 100 && CitizenLife.MORNING_BUDGET <= 200 && CitizenLife.GREET_LINGER < CitizenLife.MORNING_BUDGET
                            && CitizenLife.MORNING_END <= 2400 && ColonyLife.MAX_CHEERERS <= 8 && ColonyLife.GRIEF_TICKS <= 6000 && ColonyLife.MAX_GREETERS_PER_SPOT <= 6,
          "bounds");
        helper.succeed();
    }

    private static com.google.gson.JsonObject langFile(final GameTestHelper helper)
    {
        try (final java.io.InputStream in = AliveGameTests.class.getResourceAsStream("/assets/minecolonies/lang/en_us.json"))
        {
            helper.assertTrue(in != null, "no lang file");
            return com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        catch (final java.io.IOException e)
        {
            throw new IllegalStateException("lang file", e);
        }
    }

    private static java.util.function.ToIntFunction<Skill> skills(final Skill... high)
    {
        final List<Skill> list = List.of(high);
        return s -> list.contains(s) ? 20 : 3;
    }

    private static String key(final Component c)
    {
        return c != null && c.getContents() instanceof TranslatableContents t ? t.getKey() : "";
    }

    private static Object[] args(final Component c)
    {
        return c != null && c.getContents() instanceof TranslatableContents t ? t.getArgs() : new Object[0];
    }

    // ------------------------------------------------------------------ chat

    /**
     * Two chatty citizens 3 blocks apart in free time: a chat starts on its own, both stop and face each other, the leader speaks, the partner answers, it ends within
     * its time and no new chat follows before the cooldown.
     */
    public static void chat(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-chat");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            final EntityCitizen a = entity(spawn(helper, colony, new BlockPos(16, 1, 16)));
            final EntityCitizen b = entity(spawn(helper, colony, new BlockPos(19, 1, 16)));
            makeTrait(a, LifeTrait.CHATTY);
            makeTrait(b, LifeTrait.CHATTY);
            final long[] start = {-1};
            final long[] end = {-1};
            final boolean[] replied = {false};
            final boolean[] leaderSpoke = {false};
            final int[] statesInChat = {0};
            final List<String> log = new ArrayList<>();
            final Runnable[] poll = new Runnable[1];
            final int[] ticks = {0};
            poll[0] = () -> {
                ticks[0]++;
                keepFed(a, b);
                final long now = helper.getLevel().getGameTime();
                if (start[0] < 0 && (a.getLife().chatting() || b.getLife().chatting()))
                {
                    start[0] = now;
                    final EntityCitizen leader = a.getLife().chatLeader() ? a : b;
                    final EntityCitizen partner = leader == a ? b : a;
                    helper.assertTrue(partner.getLife().chatPartnerId() == leader.getCivilianID() && leader.getLife().chatPartnerId() == partner.getCivilianID(),
                      "partners do not match");
                    leaderSpoke[0] = !leader.getSpeech().isEmpty();
                    log.add("start " + leader.getSpeech());
                }
                if (start[0] >= 0 && end[0] < 0)
                {
                    final EntityCitizen leader = a.getLife().chatLeader() ? a : b;
                    final EntityCitizen partner = leader == a ? b : a;
                    if (!partner.getSpeech().isEmpty() && partner.getSpeech().startsWith(CitizenLife.LINE))
                    {
                        replied[0] = true;
                    }
                    if (a.getCitizenAI().getState() == EntityAICitizenLife.LifeState.CHAT && b.getCitizenAI().getState() == EntityAICitizenLife.LifeState.CHAT)
                    {
                        statesInChat[0]++;
                        helper.assertTrue(a.getNavigation().isDone() && b.getNavigation().isDone(), "a chatter is walking");
                    }
                    if (!a.getLife().chatting() && !b.getLife().chatting())
                    {
                        end[0] = now;
                        log.add("end " + (end[0] - start[0]));
                    }
                }
                if (end[0] >= 0)
                {
                    helper.assertTrue(end[0] - start[0] <= CitizenLife.CHAT_TICKS + 20, "chat too long " + (end[0] - start[0]));
                    if (now - start[0] < 400)
                    {
                        helper.assertTrue(!a.getLife().chatting() && !b.getLife().chatting(), "a new chat before the cooldown " + (now - start[0]));
                    }
                    else
                    {
                        helper.assertTrue(leaderSpoke[0] && replied[0] && statesInChat[0] > 0, "leader spoke " + leaderSpoke[0] + " reply " + replied[0] + " both in CHAT "
                                                                                              + statesInChat[0] + " " + log);
                        Log.getLogger().info("ALIVE RESULT chat start_after={} length={} both_in_chat_ticks={}", start[0], end[0] - start[0], statesInChat[0]);
                        helper.succeed();
                        return;
                    }
                }
                helper.assertTrue(ticks[0] < 2400, "no chat started in 2400 ticks " + log);
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    /**
     * A chat never holds a citizen back: the partner gets hungry and leaves for food within {@link #YIELD_TICKS}, the leader's chat ends too; with the setting off a chat
     * ends and no line stays.
     */
    public static void chatYields(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-chat-yields");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            final EntityCitizen a = entity(spawn(helper, colony, new BlockPos(16, 1, 16)));
            final EntityCitizen b = entity(spawn(helper, colony, new BlockPos(19, 1, 16)));
            final EntityCitizen c = entity(spawn(helper, colony, new BlockPos(16, 1, 19)));
            final EntityCitizen d = entity(spawn(helper, colony, new BlockPos(19, 1, 19)));
            keepFed(a, b, c, d);
            helper.runAfterDelay(30, () -> {
                a.getLife().startChat(b);
                c.getLife().startChat(d);
                helper.assertTrue(a.getLife().chatting() && b.getLife().chatting() && !a.getSpeech().isEmpty(), "chat did not start");
                helper.runAfterDelay(30, () -> {
                    keepFed(a, c, d);
                    b.getCitizenData().setSaturation(0);
                    final long hungryAt = helper.getLevel().getGameTime();
                    final Boolean before = MineColonies.getConfig().getServer().citizenLifeExtras.get();
                    MineColonies.getConfig().getServer().citizenLifeExtras.set(false);
                    final Runnable[] poll = new Runnable[1];
                    poll[0] = () -> {
                        final long waited = helper.getLevel().getGameTime() - hungryAt;
                        keepFed(a, c, d);
                        final boolean bLeft = b.getCitizenAI().getState() != EntityAICitizenLife.LifeState.CHAT;
                        final boolean aDone = !a.getLife().chatting() || a.getCitizenAI().getState() != EntityAICitizenLife.LifeState.CHAT;
                        final boolean offDone = c.getCitizenAI().getState() != EntityAICitizenLife.LifeState.CHAT && d.getCitizenAI().getState() != EntityAICitizenLife.LifeState.CHAT
                                                  && c.getSpeech().isEmpty() && d.getSpeech().isEmpty();
                        if (bLeft && aDone && offDone && waited >= 1)
                        {
                            MineColonies.getConfig().getServer().citizenLifeExtras.set(before);
                            // any step of the eat AI counts: without a restaurant MineColonies looks for food at the hut first (GO_TO_HUT)
                            final IState went = b.getCitizenAI().getState();
                            helper.assertTrue(went == CitizenAIState.EATING || went instanceof com.minecolonies.core.entity.ai.minimal.EntityAIEatTask.EatingState
                                                || b.getCitizenData().getSaturation() > 0, "hungry partner went to " + went);
                            Log.getLogger().info("ALIVE RESULT chat_yields left_after={}", waited);
                            helper.succeed();
                            return;
                        }
                        if (waited > YIELD_TICKS)
                        {
                            MineColonies.getConfig().getServer().citizenLifeExtras.set(before);
                            helper.fail("chat held on: hungry partner " + b.getCitizenAI().getState() + " leader " + a.getCitizenAI().getState() + " setting-off pair "
                                          + c.getCitizenAI().getState() + "/" + d.getCitizenAI().getState() + " lines '" + c.getSpeech() + "' '" + d.getSpeech() + "'");
                            return;
                        }
                        helper.runAfterDelay(1, () -> poll[0].run());
                    };
                    helper.runAfterDelay(1, () -> poll[0].run());
                });
            });
        });
    }

    // ------------------------------------------------------------------ morning

    /**
     * Next morning: a citizen who just woke greets at the town hall (a line), the hold on work lasts at most {@link CitizenLife#MORNING_BUDGET} ticks and comes once a
     * day; a citizen whose first look of the day comes from work is never held.
     */
    public static void morning(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-morning");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            final EntityCitizen a = entity(spawn(helper, colony, new BlockPos(12, 1, 12)));
            final EntityCitizen worker = entity(spawn(helper, colony, new BlockPos(18, 1, 12)));
            // round 6: a greeting needs a listener; one stands by the town hall
            final BlockPos listenerAt = new BlockPos(5, 1, 6);
            final EntityCitizen listener = entity(spawn(helper, colony, listenerAt));
            keepFed(a, worker, listener);
            final ServerLevel level = helper.getLevel();
            // round 5: only some citizens greet each morning; take the next morning this one does
            long day = level.getOverworldClockTime() / 24000L + 1;
            while (!CitizenLife.greetsOn(a.getCivilianID(), day, a.getLife().trait()))
            {
                day++;
            }
            setClock(helper, day * 24000L + 100L);
            // the worker's first look today comes from work: no greeting, all day
            helper.assertTrue(!worker.getLife().greetingHolds(false) && !worker.getLife().greetingHolds(true), "a citizen at work was held for a greeting");
            final long t0 = level.getGameTime();
            final long[] held = {0};
            final boolean[] greeted = {false};
            final boolean[] gathered = {false};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                keepFed(a, worker, listener);
                pin(helper, listener, listenerAt);
                final long now = level.getGameTime();
                if (a.getLife().greetingHolds(true))
                {
                    held[0] = now - a.getLife().greetingStart();
                }
                gathered[0] |= a.getCitizenAI().getState() == EntityAICitizenLife.LifeState.MORNING_GATHER;
                greeted[0] |= a.getSpeech().contains(".morning");
                helper.assertTrue(held[0] <= CitizenLife.MORNING_BUDGET, "greeting held work " + held[0] + " ticks");
                if (now - t0 > CitizenLife.MORNING_BUDGET + 60)
                {
                    helper.assertTrue(gathered[0] && greeted[0], "no greeting: gathered " + gathered[0] + " line '" + a.getSpeech() + "' state " + a.getCitizenAI().getState());
                    helper.assertTrue(!a.getLife().greetingHolds(true) && a.getCitizenAI().getState() != EntityAICitizenLife.LifeState.MORNING_GATHER,
                      "greeting did not end: " + a.getCitizenAI().getState());
                    Log.getLogger().info("ALIVE RESULT morning held={} spot={}", held[0], a.getLife().gatherSpot());
                    helper.succeed();
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    // ------------------------------------------------------------------ colony events

    /**
     * A finished building: the nearest {@link ColonyLife#MAX_CHEERERS} citizens within {@link ColonyLife#CHEER_RANGE} blocks cheer one after the other, a far one
     * does not, nobody leaves free time or work for it; with the setting off nobody cheers.
     */
    public static void buildingCheer(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-cheer");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            final IBuilding townHall = colony.getServerBuildingManager().getTownHall();
            helper.assertTrue(townHall != null, "no town hall");
            final List<EntityCitizen> near = new ArrayList<>();
            for (int i = 0; i < 10; i++)
            {
                near.add(entity(spawn(helper, colony, new BlockPos(4 + (i % 5) * 2, 1, 6 + (i / 5) * 3))));
            }
            final EntityCitizen far = entity(spawn(helper, colony, new BlockPos(36, 1, 30)));
            final Boolean before = MineColonies.getConfig().getServer().citizenLifeExtras.get();
            MineColonies.getConfig().getServer().citizenLifeExtras.set(false);
            final int whenOff = ColonyLife.onBuildingFinished(townHall);
            MineColonies.getConfig().getServer().citizenLifeExtras.set(before);
            helper.assertTrue(whenOff == 0, "cheered with the setting off: " + whenOff);
            helper.assertTrue(far.blockPosition().distSqr(townHall.getPosition()) > ColonyLife.CHEER_RANGE * ColonyLife.CHEER_RANGE, "far citizen is not far");

            final int count = ColonyLife.onBuildingFinished(townHall);
            int pending = 0;
            for (final EntityCitizen c : near)
            {
                if (c.getLife().pendingLine() != null && c.getLife().pendingLine().startsWith(CitizenLife.CHEER_BUILDING))
                {
                    pending++;
                }
            }
            helper.assertTrue(count == ColonyLife.MAX_CHEERERS && pending == ColonyLife.MAX_CHEERERS, "cheerers " + count + " pending " + pending);
            helper.assertTrue(far.getLife().pendingLine() == null, "the far citizen cheers");
            final boolean[] spoke = new boolean[near.size()];
            final long t0 = helper.getLevel().getGameTime();
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                int count2 = 0;
                for (int i = 0; i < near.size(); i++)
                {
                    keepFed(near.get(i));
                    spoke[i] |= near.get(i).getSpeech().startsWith(CitizenLife.CHEER_BUILDING);
                    count2 += spoke[i] ? 1 : 0;
                }
                helper.assertTrue(!far.getSpeech().startsWith(CitizenLife.CHEER_BUILDING), "the far citizen cheers");
                if (count2 >= ColonyLife.MAX_CHEERERS)
                {
                    helper.assertTrue(count2 == ColonyLife.MAX_CHEERERS, "more cheerers than the cap: " + count2);
                    Log.getLogger().info("ALIVE RESULT cheer count={} all_spoke_after={}", count, helper.getLevel().getGameTime() - t0);
                    helper.succeed();
                    return;
                }
                if (helper.getLevel().getGameTime() - t0 >= CHEER_WINDOW)
                {
                    final List<String> missed = new ArrayList<>();
                    for (int i = 0; i < near.size(); i++)
                    {
                        final EntityCitizen c = near.get(i);
                        if (!spoke[i])
                        {
                            missed.add(c.getCivilianID() + " " + c.getCitizenAI().getState() + " pending '" + c.getLife().pendingLine() + "' says '" + c.getSpeech()
                                         + "' asleep " + c.getCitizenSleepHandler().isAsleep());
                        }
                    }
                    helper.fail("only " + count2 + " cheered aloud in " + CHEER_WINDOW + " ticks; not yet: " + missed);
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    /**
     * A friend dies: family, co-workers and chat partners grieve (a line, a slower walk off work), a stranger does not, the grief ends after
     * {@link ColonyLife#GRIEF_TICKS}; with the setting off nobody grieves.
     */
    public static void grief(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-grief");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            final ICitizenData friendData = spawn(helper, colony, new BlockPos(14, 1, 14));
            makeTrait(entity(friendData), LifeTrait.STEADY);
            final ICitizenData deadData = spawn(helper, colony, new BlockPos(16, 1, 14));
            final ICitizenData strangerData = spawn(helper, colony, new BlockPos(30, 1, 24));
            final EntityCitizen friend = entity(friendData);
            keepFed(friend, entity(strangerData));
            ColonyLife.noteChat(colony, friendData.getId(), deadData.getId());

            final Boolean before = MineColonies.getConfig().getServer().citizenLifeExtras.get();
            MineColonies.getConfig().getServer().citizenLifeExtras.set(false);
            final List<Integer> whenOff = ColonyLife.onCitizenDied(colony, deadData);
            MineColonies.getConfig().getServer().citizenLifeExtras.set(before);
            helper.assertTrue(whenOff.isEmpty(), "grief with the setting off: " + whenOff);
            ColonyLife.noteChat(colony, friendData.getId(), deadData.getId());

            final List<Integer> grieving = ColonyLife.onCitizenDied(colony, deadData);
            final long now = helper.getLevel().getGameTime();
            helper.assertTrue(grieving.contains(friendData.getId()) && !grieving.contains(strangerData.getId()), "grieving " + grieving);
            final String deadName = ColonyLife.firstName(deadData.getName());
            helper.assertTrue(deadName.equals(ColonyLife.griefFor(colony, friendData.getId(), now)), "no grief for the friend");
            helper.assertTrue(ColonyLife.griefFor(colony, strangerData.getId(), now) == null, "the stranger grieves");
            helper.assertTrue((CitizenLife.GRIEF_LINE + "|n:" + deadName).equals(friend.getLife().pendingLine()), "no grief line: " + friend.getLife().pendingLine());
            helper.runAfterDelay(80, () -> {
                keepFed(friend);
                final IState state = friend.getCitizenAI().getState();
                helper.assertTrue(friend.getSpeech().startsWith(CitizenLife.GRIEF_LINE), "the friend said '" + friend.getSpeech() + "'");
                helper.assertTrue(CitizenLife.isFreeTime(state) ? friend.getLife().appliedPace() < 0 : friend.getLife().appliedPace() >= 0,
                  "pace " + friend.getLife().appliedPace() + " in " + state);
                helper.assertTrue(ColonyLife.onBuildingFinished(colony.getServerBuildingManager().getTownHall()) >= 0
                                    && friend.getLife().pendingLine() == null, "a griever cheers: " + friend.getLife().pendingLine());
                final long later = helper.getLevel().getGameTime() + ColonyLife.GRIEF_TICKS;
                helper.assertTrue(ColonyLife.griefFor(colony, friendData.getId(), later) == null, "grief never ends");
                Log.getLogger().info("ALIVE RESULT grief grieving={} pace={}", grieving, friend.getLife().appliedPace());
                helper.succeed();
            });
        });
    }

    /**
     * A raid passes: citizens notice it (a line), and when it is over they say they are relieved and cheer, one after the other, within 4 seconds.
     */
    public static void raidRelief(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-raid");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            final EntityCitizen a = entity(spawn(helper, colony, new BlockPos(12, 1, 18)));
            final EntityCitizen b = entity(spawn(helper, colony, new BlockPos(20, 1, 18)));
            keepFed(a, b);
            helper.runAfterDelay(25, () -> {
                colony.getRaiderManager().setPassThroughRaid();
                final boolean[] warned = {false};
                final long[] over = {-1};
                final boolean[] relief = {false, false};
                final Runnable[] poll = new Runnable[1];
                final long t0 = helper.getLevel().getGameTime();
                poll[0] = () -> {
                    keepFed(a, b);
                    final long now = helper.getLevel().getGameTime();
                    warned[0] |= a.getSpeech().startsWith(CitizenLife.RAID_START) || b.getSpeech().startsWith(CitizenLife.RAID_START);
                    if (over[0] < 0 && !colony.getRaiderManager().isRaided())
                    {
                        over[0] = now;
                    }
                    if (over[0] >= 0)
                    {
                        relief[0] |= a.getSpeech().startsWith(CitizenLife.RAID_END);
                        relief[1] |= b.getSpeech().startsWith(CitizenLife.RAID_END);
                        if (relief[0] && relief[1])
                        {
                            helper.assertTrue(warned[0], "nobody noticed the raid");
                            helper.assertTrue(now - over[0] <= 140, "relief came late: " + (now - over[0]));
                            Log.getLogger().info("ALIVE RESULT raid relief_after={}", now - over[0]);
                            helper.succeed();
                            return;
                        }
                        helper.assertTrue(now - over[0] <= 140, "no relief within 140 ticks: " + relief[0] + "/" + relief[1]);
                    }
                    helper.assertTrue(now - t0 < 1000, "raid never ended");
                    helper.runAfterDelay(1, () -> poll[0].run());
                };
                helper.runAfterDelay(1, () -> poll[0].run());
            });
        });
    }

    /**
     * Rain: a citizen out in the open without a hat walks faster and says so; with a hat on he keeps his own pace; without a home he does not look for shelter.
     */
    public static void rainHurry(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-rain");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            fillBiome(helper, new BlockPos(-8, 0, -8), new BlockPos(39, 12, 31), net.minecraft.world.level.biome.Biomes.PLAINS);
            final EntityCitizen bare = entity(spawn(helper, colony, new BlockPos(12, 1, 18)));
            final EntityCitizen hat = entity(spawn(helper, colony, new BlockPos(24, 1, 18)));
            makeTrait(bare, LifeTrait.BRISK);
            makeTrait(hat, LifeTrait.CHATTY);
            // round 6 (N40): the hat goes into the citizen's own armor inventory, the one EntityCitizen.getItemBySlot reads (not the vanilla slot)
            hat.getCitizenData().getInventory().forceArmorStackToSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
            helper.assertTrue(!hat.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "precondition failed: the hat is not on");
            keepFed(bare, hat);
            final var weather = helper.getLevel().getWeatherData();
            weather.setClearWeatherTime(0);
            weather.setRaining(true);
            weather.setThundering(false);
            weather.setRainTime(1_000_000);
            final Runnable[] poll = new Runnable[1];
            final long t0 = helper.getLevel().getGameTime();
            final boolean[] said = {false};
            poll[0] = () -> {
                keepFed(bare, hat);
                if (hat.getItemBySlot(EquipmentSlot.HEAD).isEmpty())
                {
                    hat.getCitizenData().getInventory().forceArmorStackToSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                }
                final long now = helper.getLevel().getGameTime();
                said[0] |= bare.getSpeech().startsWith(CitizenLife.RAIN);
                final boolean hurrying = bare.getLife().appliedPace() >= CitizenLife.RAIN_HURRY + bare.getLife().trait().offWorkPace() - 1.0E-6;
                if (helper.getLevel().isRaining() && hurrying && said[0])
                {
                    helper.assertTrue(hat.getLife().appliedPace() < CitizenLife.RAIN_HURRY - 0.05D, "the hat wearer hurries: " + hat.getLife().appliedPace());
                    helper.assertTrue(!bare.getLife().wantsShelter(), "shelter without a home");
                    weather.setRaining(false);
                    weather.setRainTime(0);
                    weather.setClearWeatherTime(1_000_000);
                    Log.getLogger().info("ALIVE RESULT rain hurry_after={} pace={}", now - t0, bare.getLife().appliedPace());
                    helper.succeed();
                    return;
                }
                if (now - t0 > 400)
                {
                    weather.setRaining(false);
                    weather.setClearWeatherTime(1_000_000);
                    helper.fail("no hurry in the rain: raining " + helper.getLevel().isRaining() + " pace " + bare.getLife().appliedPace() + " line '" + bare.getSpeech() + "'");
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    // ------------------------------------------------------------------ round 5

    /**
     * Rain by place: in the rain, a hatless citizen under the open sky of a plains biome hurries and says one rain line for the whole spell; one in a desert,
     * one in snow and one under a roof neither hurry nor say a rain line. When the rain stops, the line may come again with the next spell.
     */
    public static void rainWhere(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-rain-where");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            fillBiome(helper, new BlockPos(-8, 0, -8), new BlockPos(39, 12, 31), net.minecraft.world.level.biome.Biomes.PLAINS);
            fillBiome(helper, new BlockPos(2, 0, 16), new BlockPos(10, 12, 24), net.minecraft.world.level.biome.Biomes.DESERT);
            fillBiome(helper, new BlockPos(14, 0, 16), new BlockPos(22, 12, 24), net.minecraft.world.level.biome.Biomes.SNOWY_PLAINS);
            for (int x = 24; x <= 32; x++)
            {
                for (int z = 16; z <= 24; z++)
                {
                    helper.setBlock(new BlockPos(x, 4, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                }
            }
            final BlockPos[] spots = {new BlockPos(28, 1, 6), new BlockPos(6, 1, 20), new BlockPos(18, 1, 20), new BlockPos(28, 1, 20)};
            final String[] names = {"open", "desert", "snow", "roof"};
            final EntityCitizen[] c = new EntityCitizen[spots.length];
            for (int i = 0; i < spots.length; i++)
            {
                c[i] = entity(spawn(helper, colony, spots[i]));
                makeTrait(c[i], LifeTrait.BRISK);
            }
            rain(helper, true);
            final long t0 = helper.getLevel().getGameTime();
            final int[] lines = {0};
            final boolean[] wasRainLine = {false};
            final long[] firstLine = {-1};
            final boolean[] stopped = {false};
            final long[] stoppedAt = {-1};
            final long[] dryAt = {-1};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                final long now = helper.getLevel().getGameTime();
                for (int i = 0; i < spots.length; i++)
                {
                    keepFed(c[i]);
                    pin(helper, c[i], spots[i]);
                }
                final boolean rainLine = c[0].getSpeech().startsWith(CitizenLife.RAIN);
                if (rainLine && !wasRainLine[0])
                {
                    lines[0]++;
                    firstLine[0] = firstLine[0] < 0 ? now : firstLine[0];
                }
                wasRainLine[0] = rainLine;
                for (int i = 1; i < spots.length; i++)
                {
                    helper.assertTrue(!c[i].getSpeech().startsWith(CitizenLife.RAIN), "the " + names[i] + " citizen talks of rain: " + c[i].getSpeech());
                    helper.assertTrue(!c[i].getLife().inRain() && !c[i].getLife().hurriesInRain(), "rain falls on the " + names[i] + " citizen");
                    helper.assertTrue(c[i].getLife().appliedPace() < CitizenLife.RAIN_HURRY, "the " + names[i] + " citizen hurries: " + c[i].getLife().appliedPace());
                }
                if (!stopped[0])
                {
                    helper.assertTrue(lines[0] <= 1, "a second rain line in one spell after " + (now - firstLine[0]) + " ticks");
                    if (firstLine[0] >= 0 && now - firstLine[0] > CitizenLife.REMARK_GAP + 60)
                    {
                        helper.assertTrue(c[0].getLife().hurriesInRain() && c[0].getLife().appliedPace() >= CitizenLife.RAIN_HURRY, "the open citizen does not hurry");
                        rain(helper, false);
                        stopped[0] = true;
                        stoppedAt[0] = now;
                    }
                    helper.assertTrue(now - t0 < 300 || firstLine[0] >= 0, "no rain line under the open sky in 300 ticks: '" + c[0].getSpeech() + "'");
                }
                else if (!helper.getLevel().isRaining() && dryAt[0] < 0)
                {
                    dryAt[0] = now;
                }
                else if (dryAt[0] >= 0 && now - dryAt[0] >= 25 && now - stoppedAt[0] > 40)
                {
                    // round 6 (N40): the rain level fades over many ticks after the stop, and the line is made ready at the citizen's next once-a-second
                    // tick, up to 19 ticks after the world reads dry
                    helper.assertTrue(!c[0].getLife().rainLineSaid(), "the rain line is not ready for the next spell");
                    Log.getLogger().info("ALIVE RESULT rain_where first_line_after={} lines={}", firstLine[0] - t0, lines[0]);
                    helper.succeed();
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    /**
     * A trait is picked once and saved: growing other skills does not change it, it is in the citizen's save data, and the saved trait wins over the skills.
     */
    public static void traitsStable(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-traits");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            final ICitizenData data = spawn(helper, colony, new BlockPos(16, 1, 16));
            final EntityCitizen citizen = entity(data);
            final LifeTrait first = citizen.getLife().trait();
            helper.assertTrue(first.name().equals(data.getLifeTrait()), "the trait was not saved: '" + data.getLifeTrait() + "' for " + first);
            // train the group of another trait far beyond the rest, as a long career does
            final LifeTrait other = first == LifeTrait.BRISK ? LifeTrait.THOUGHTFUL : LifeTrait.BRISK;
            for (final Skill skill : other == LifeTrait.BRISK ? new Skill[] {Skill.Athletics, Skill.Agility, Skill.Stamina}
                                                               : new Skill[] {Skill.Intelligence, Skill.Knowledge, Skill.Focus})
            {
                data.getCitizenSkillHandler().incrementLevel(skill, 60);
            }
            helper.assertTrue(LifeTrait.of(data.getId(), data.getCitizenSkillHandler()) == other, "the skills did not move to " + other);
            citizen.getLife().refreshTrait();
            helper.assertTrue(citizen.getLife().trait() == first && LifeTrait.of(data) == first, "the trait drifted to " + citizen.getLife().trait());
            final net.minecraft.nbt.CompoundTag saved = data.serializeNBT(helper.getLevel().registryAccess());
            helper.assertTrue(first.name().equals(saved.getStringOr("lifetrait", "")), "the trait is not in the save data: " + saved.getStringOr("lifetrait", ""));
            data.setLifeTrait(LifeTrait.STEADY.name());
            citizen.getLife().refreshTrait();
            helper.assertTrue(citizen.getLife().trait() == LifeTrait.STEADY, "a saved trait does not win: " + citizen.getLife().trait());
            Log.getLogger().info("ALIVE RESULT traits first={} skills_say={}", first, other);
            helper.succeed();
        });
    }

    /**
     * Chats fit: in the rain the opener is about the rain and the partner answers that opener; one pair does not repeat an opener while others are left; another
     * pair keeps its own memory.
     */
    public static void chatPairs(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-chat-pairs");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            fillBiome(helper, new BlockPos(-8, 0, -8), new BlockPos(39, 12, 31), net.minecraft.world.level.biome.Biomes.PLAINS);
            final EntityCitizen a = entity(spawn(helper, colony, new BlockPos(16, 1, 16)));
            final EntityCitizen b = entity(spawn(helper, colony, new BlockPos(34, 1, 28)));
            final EntityCitizen c = entity(spawn(helper, colony, new BlockPos(16, 1, 19)));
            for (final EntityCitizen e : new EntityCitizen[] {a, b, c})
            {
                makeTrait(e, LifeTrait.STEADY);
                keepFed(e);
            }

            // no repeat within one pair while openers are left, then the one used longest ago
            final long now0 = helper.getLevel().getGameTime();
            final CitizenLife.ChatTopic happy = CitizenLife.ChatTopic.HAPPY;
            final java.util.Set<Integer> seen = new java.util.HashSet<>();
            int firstPick = -1;
            for (int i = 0; i < happy.lines(); i++)
            {
                final int pick = ColonyLife.pickLine(colony, a.getCivilianID(), b.getCivilianID(), happy, now0 + i, a.getRandom());
                firstPick = firstPick < 0 ? pick : firstPick;
                helper.assertTrue(seen.add(pick), "opener " + pick + " again after " + seen);
            }
            helper.assertTrue(ColonyLife.pickLine(colony, b.getCivilianID(), a.getCivilianID(), happy, now0 + 10, b.getRandom()) == firstPick,
              "the pair did not come back to the oldest opener");
            helper.assertTrue(ColonyLife.pickLine(colony, a.getCivilianID(), b.getCivilianID(), happy, now0 + 10 + ColonyLife.CHAT_REPEAT_WINDOW, a.getRandom()) >= 1,
              "the pair memory does not expire");
            // round 6: at night a pair never picks an opener about the day, even once the others are used up
            for (int i = 0; i < 2 * happy.lines(); i++)
            {
                final int night = ColonyLife.pickLine(colony, a.getCivilianID(), c.getCivilianID(), happy, now0 + i, a.getRandom(), line -> happy.fits(line, false));
                helper.assertTrue(happy.fits(night, false), "a day opener at night: " + night);
            }

            // in the rain (once it really rains, with both in their free time): a rain opener, and the reply is one of that opener's own
            rain(helper, true);
            final String[] opener = {""};
            final Runnable[] poll = new Runnable[1];
            final long t0 = helper.getLevel().getGameTime();
            poll[0] = () -> {
                keepFed(a, c);
                final long now = helper.getLevel().getGameTime();
                if (opener[0].isEmpty())
                {
                    if (helper.getLevel().isRaining() && CitizenLife.inFreeTime(a.getCitizenAI().getState()) && CitizenLife.inFreeTime(c.getCitizenAI().getState()))
                    {
                        a.getLife().startChat(c);
                        opener[0] = a.getSpeech();
                        helper.assertTrue(opener[0].startsWith(CitizenLife.ChatTopic.RAIN.prefix()), "a dry opener in the rain: " + opener[0]);
                    }
                    helper.assertTrue(now - t0 < 600, "no rain or no free time: raining " + helper.getLevel().isRaining() + " states " + a.getCitizenAI().getState() + " "
                                                        + c.getCitizenAI().getState());
                }
                else if (c.getSpeech().startsWith(opener[0] + ".reply."))
                {
                    rain(helper, false);
                    Log.getLogger().info("ALIVE RESULT chat_pairs opener={} reply={}", opener[0], c.getSpeech());
                    helper.succeed();
                    return;
                }
                else if (now - t0 > 600 + CitizenLife.REPLY_AFTER + 60)
                {
                    rain(helper, false);
                    helper.fail("no fitting reply to '" + opener[0] + "': partner " + c.getCitizenAI().getState() + " says '" + c.getSpeech() + "'");
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    /**
     * Grief slows only the free-time stroll: a grieving citizen who flees walks at least at his normal pace, and so does everyone while the colony is raided.
     */
    public static void calmPace(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-calm-pace");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            final ICitizenData friendData = spawn(helper, colony, new BlockPos(14, 1, 14));
            final ICitizenData deadData = spawn(helper, colony, new BlockPos(30, 1, 24));
            final EntityCitizen friend = entity(friendData);
            makeTrait(friend, LifeTrait.STEADY);
            ColonyLife.noteChat(colony, friendData.getId(), deadData.getId());
            helper.assertTrue(ColonyLife.onCitizenDied(colony, deadData).contains(friendData.getId()), "the friend does not grieve");
            final int[] phase = {0};
            final int[] streak = {0};
            final long t0 = helper.getLevel().getGameTime();
            final List<String> log = new ArrayList<>();
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                keepFed(friend);
                final IState state = friend.getCitizenAI().getState();
                final double pace = friend.getLife().appliedPace();
                final long waited = helper.getLevel().getGameTime() - t0;
                switch (phase[0])
                {
                    case 0 -> {
                        // a grieving stroll is slower
                        streak[0] = CitizenLife.isFreeTime(state) ? streak[0] + 1 : 0;
                        if (streak[0] > 25 && pace < 0)
                        {
                            log.add("stroll " + pace + " in " + state);
                            friend.getCitizenAI().addTransition(new com.minecolonies.api.entity.ai.statemachine.AIOneTimeEventTarget<>(CitizenAIState.FLEE));
                            phase[0] = 1;
                            streak[0] = 0;
                        }
                    }
                    case 1 -> {
                        // in flight he is not slowed
                        streak[0] = CitizenLife.isFreeTime(state) ? 0 : streak[0] + 1;
                        if (streak[0] > 25)
                        {
                            helper.assertTrue(pace >= 0, "a grieving citizen flees slower: " + pace + " in " + state);
                            log.add("flee " + pace + " in " + state);
                            colony.getRaiderManager().setPassThroughRaid();
                            phase[0] = 2;
                            streak[0] = 0;
                        }
                    }
                    default -> {
                        // nobody is slowed while raiders are about
                        helper.assertTrue(colony.getRaiderManager().isRaided(), "the raid ended early");
                        if (++streak[0] > 25)
                        {
                            helper.assertTrue(pace >= 0, "a grieving citizen is slowed in a raid: " + pace + " in " + state);
                            log.add("raid " + pace + " in " + state);
                            Log.getLogger().info("ALIVE RESULT calm_pace {}", log);
                            helper.succeed();
                            return;
                        }
                    }
                }
                helper.assertTrue(waited < 900, "phase " + phase[0] + " never came: state " + state + " pace " + pace + " " + log);
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    /**
     * A morning with twelve citizens near the town hall: only some greet, at most {@link ColonyLife#MAX_GREETERS_PER_SPOT} at the town hall (the rest where they
     * stand), each greeter says his line, nobody is held beyond {@link CitizenLife#MORNING_BUDGET}, and the others are never held. Round 6: no line without an
     * awake listener within {@link CitizenLife#GREET_HEAR_RANGE} blocks, and a greeter alone, far from everyone, neither walks nor speaks.
     */
    public static void morningCrowd(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-morning-crowd");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            final BlockPos townHall = colony.getServerBuildingManager().getTownHall().getPosition();
            final List<EntityCitizen> all = new ArrayList<>();
            for (int i = 0; i < 12; i++)
            {
                final EntityCitizen c = entity(spawn(helper, colony, new BlockPos(6 + (i % 4) * 3, 1, 8 + (i / 4) * 3)));
                makeTrait(c, LifeTrait.CHATTY);
                keepFed(c);
                all.add(c);
            }
            final BlockPos lonerAt = new BlockPos(36, 1, 29);
            final EntityCitizen loner = entity(spawn(helper, colony, lonerAt));
            makeTrait(loner, LifeTrait.CHATTY);
            keepFed(loner);
            // a morning where more than one spot's worth of them greets, but not everyone, and the loner would greet too
            final ServerLevel level = helper.getLevel();
            long day = level.getOverworldClockTime() / 24000L + 1;
            for (int guard = 0; guard < 500; guard++, day++)
            {
                final long d = day;
                final long count = all.stream().filter(c -> CitizenLife.greetsOn(c.getCivilianID(), d, LifeTrait.CHATTY)).count();
                if (count > ColonyLife.MAX_GREETERS_PER_SPOT && count < all.size() && CitizenLife.greetsOn(loner.getCivilianID(), d, LifeTrait.CHATTY))
                {
                    break;
                }
            }
            setClock(helper, day * 24000L + 100L);
            helper.assertTrue(!loner.getLife().greetingHolds(true) && loner.getLife().gatherSpot() == null, "a greeter with nobody near walks off to greet: "
                                                                                                             + loner.getLife().gatherSpot());
            final List<EntityCitizen> greeters = new ArrayList<>();
            int atHall = 0;
            for (final EntityCitizen c : all)
            {
                final BlockPos stood = c.blockPosition();
                if (c.getLife().greetingHolds(true))
                {
                    greeters.add(c);
                    final BlockPos spot = c.getLife().gatherSpot();
                    helper.assertTrue(spot != null, "a greeter without a spot");
                    atHall += !spot.equals(stood) && Math.abs(spot.getX() - townHall.getX()) <= 3 && Math.abs(spot.getZ() - townHall.getZ()) <= 3 ? 1 : 0;
                }
            }
            helper.assertTrue(greeters.size() > ColonyLife.MAX_GREETERS_PER_SPOT && greeters.size() < all.size(), "greeters " + greeters.size() + " of " + all.size());
            helper.assertTrue(atHall == ColonyLife.MAX_GREETERS_PER_SPOT, "at the town hall: " + atHall);
            final int hall = atHall;
            final long t0 = level.getGameTime();
            final boolean[] said = new boolean[all.size()];
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                final long now = level.getGameTime();
                keepFed(loner);
                pin(helper, loner, lonerAt);
                helper.assertTrue(!loner.getSpeech().contains(".morning") && loner.getCitizenAI().getState() != EntityAICitizenLife.LifeState.MORNING_GATHER,
                  "the loner greets nobody: '" + loner.getSpeech() + "'");
                for (int i = 0; i < all.size(); i++)
                {
                    final EntityCitizen c = all.get(i);
                    keepFed(c);
                    if (!said[i] && c.getSpeech().contains(".morning"))
                    {
                        helper.assertTrue(c.getLife().someoneToGreet(c.blockPosition()), "citizen " + c.getCivilianID() + " greets with nobody within "
                                                                                       + CitizenLife.GREET_HEAR_RANGE + " blocks");
                    }
                    said[i] |= c.getSpeech().contains(".morning");
                    if (!greeters.contains(c))
                    {
                        helper.assertTrue(!c.getLife().greetingHolds(true) && c.getCitizenAI().getState() != EntityAICitizenLife.LifeState.MORNING_GATHER,
                          "a citizen who does not greet today was held");
                    }
                    else if (c.getLife().greetingHolds(true))
                    {
                        helper.assertTrue(now - c.getLife().greetingStart() <= CitizenLife.MORNING_BUDGET, "a greeting held work " + (now - c.getLife().greetingStart()));
                    }
                }
                if (now - t0 > CitizenLife.MORNING_BUDGET + 40)
                {
                    final List<Integer> mute = new ArrayList<>();
                    int spoke = 0;
                    for (int i = 0; i < all.size(); i++)
                    {
                        // a greeter who found nobody near on arrival rightly says nothing (round 6)
                        if (greeters.contains(all.get(i)) && !said[i] && !all.get(i).getLife().greetSkipped())
                        {
                            mute.add(all.get(i).getCivilianID());
                        }
                        spoke += said[i] ? 1 : 0;
                    }
                    helper.assertTrue(mute.isEmpty(), "greeters who never said their line: " + mute);
                    helper.assertTrue(spoke >= ColonyLife.MAX_GREETERS_PER_SPOT, "only " + spoke + " greeted in a crowd");
                    Log.getLogger().info("ALIVE RESULT morning_crowd greeters={} at_hall={}", greeters.size(), hall);
                    helper.succeed();
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    /**
     * Shelter: an idle citizen out in the rain walks home and ends inside the house (within its corners), not at its door, and stays there.
     */
    public static void shelterInside(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-shelter");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            fillBiome(helper, new BlockPos(-8, 0, -8), new BlockPos(39, 12, 31), net.minecraft.world.level.biome.Biomes.PLAINS);
            final IBuilding home = MinecoloniesGameTests.placeProductionBuilding(helper, colony, com.minecolonies.api.blocks.ModBlocks.blockHutHome, new BlockPos(26, 1, 20),
              "fundamentals/residence1.blueprint");
            final var corners = home.getCorners();
            helper.assertTrue(!corners.getA().equals(corners.getB()), "precondition failed: the house has no corners " + corners.getA());
            final ICitizenData data = spawn(helper, colony, new BlockPos(6, 1, 24));
            if (data.getHomeBuilding() != null)
            {
                data.getHomeBuilding().getFirstModuleOccurance(com.minecolonies.core.colony.buildings.modules.LivingBuildingModule.class).removeCitizen(data);
            }
            home.getFirstModuleOccurance(com.minecolonies.core.colony.buildings.modules.LivingBuildingModule.class).assignCitizen(data);
            helper.assertTrue(data.getHomeBuilding() == home, "precondition failed: no home");
            final BlockPos inside = EntityAICitizenLife.insideSpot(home, data.getId());
            helper.assertTrue(home.isInBuilding(inside), "the shelter spot " + inside + " is outside the house " + corners.getA() + " " + corners.getB());
            final EntityCitizen citizen = entity(data);
            makeTrait(citizen, LifeTrait.BRISK);
            rain(helper, true);
            final long t0 = helper.getLevel().getGameTime();
            final boolean[] sheltering = {false};
            final long[] insideSince = {-1};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                keepFed(citizen);
                final long now = helper.getLevel().getGameTime();
                sheltering[0] |= citizen.getCitizenAI().getState() == EntityAICitizenLife.LifeState.SHELTER;
                final boolean in = sheltering[0] && home.isInBuilding(citizen.blockPosition());
                insideSince[0] = in ? (insideSince[0] < 0 ? now : insideSince[0]) : -1;
                if (insideSince[0] >= 0 && now - insideSince[0] >= 60)
                {
                    helper.assertTrue(citizen.getCitizenAI().getState() == EntityAICitizenLife.LifeState.SHELTER, "he left the shelter: " + citizen.getCitizenAI().getState());
                    rain(helper, false);
                    Log.getLogger().info("ALIVE RESULT shelter inside_after={} at={} spot={}", insideSince[0] - t0, citizen.blockPosition(), inside);
                    helper.succeed();
                    return;
                }
                if (now - t0 > 1000)
                {
                    rain(helper, false);
                    helper.fail("not inside: sheltering " + sheltering[0] + " state " + citizen.getCitizenAI().getState() + " at " + citizen.blockPosition() + " house "
                                  + corners.getA() + " " + corners.getB() + " spot " + inside);
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    /**
     * No cheer at the wrong moment: a mourner does not cheer a finished building, nobody cheers during a raid (a cheer that comes due then is dropped), and when
     * the raid ends a griever says a quiet line and a mourner a calm one, never "We made it!", while the others are relieved. Round 6 (N38): a chat under way
     * ends when the raid starts, and no chat starts while it lasts.
     */
    public static void noCheer(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-no-cheer");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            final IBuilding townHall = colony.getServerBuildingManager().getTownHall();
            final ICitizenData mournerData = spawn(helper, colony, new BlockPos(6, 1, 8));
            final ICitizenData grieverData = spawn(helper, colony, new BlockPos(10, 1, 8));
            final ICitizenData gladData = spawn(helper, colony, new BlockPos(14, 1, 8));
            final ICitizenData deadData = spawn(helper, colony, new BlockPos(34, 1, 28));
            final EntityCitizen mourner = entity(mournerData);
            final EntityCitizen griever = entity(grieverData);
            final EntityCitizen glad = entity(gladData);
            final EntityCitizen talkA = entity(spawn(helper, colony, new BlockPos(20, 1, 8)));
            final EntityCitizen talkB = entity(spawn(helper, colony, new BlockPos(23, 1, 8)));
            keepFed(mourner, griever, glad, talkA, talkB);
            mournerData.getCitizenMournHandler().setMourning(true);
            helper.assertTrue(mourner.getLife().mourning(), "precondition failed: not mourning");

            // a mourner does not cheer, the others do
            helper.assertTrue(ColonyLife.onBuildingFinished(townHall) >= 2, "nobody cheers");
            helper.assertTrue(mourner.getLife().pendingLine() == null, "a mourner cheers: " + mourner.getLife().pendingLine());
            helper.assertTrue(glad.getLife().pendingLine() != null && glad.getLife().pendingLine().startsWith(CitizenLife.CHEER_BUILDING), "no cheer");

            // a chat is under way when the raid comes
            talkA.getLife().startChat(talkB);
            helper.assertTrue(talkA.getLife().chatting() && talkB.getLife().chatting(), "precondition failed: no chat");

            // the raid comes before the cheers are said: they are dropped, and no new cheer starts
            colony.getRaiderManager().setPassThroughRaid();
            helper.assertTrue(colony.getRaiderManager().isRaided(), "precondition failed: no raid");
            helper.assertTrue(ColonyLife.onBuildingFinished(townHall) == 0, "cheers during a raid");
            ColonyLife.noteChat(colony, grieverData.getId(), deadData.getId());
            helper.assertTrue(ColonyLife.onCitizenDied(colony, deadData).contains(grieverData.getId()), "precondition failed: no griever");
            final String grieved = ColonyLife.firstName(deadData.getName());
            final long t0 = helper.getLevel().getGameTime();
            final long[] over = {-1};
            final String[] end = {"", "", ""};
            final EntityCitizen[] who = {mourner, griever, glad};
            final EntityCitizen[] everyone = {mourner, griever, glad, talkA, talkB};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                keepFed(everyone);
                final long now = helper.getLevel().getGameTime();
                final boolean raided = colony.getRaiderManager().isRaided();
                if (raided && now - t0 > 60)
                {
                    // past a once-a-second tick and a chat step: the chat has ended, and nobody may start or join one
                    for (final EntityCitizen e : everyone)
                    {
                        helper.assertTrue(!e.getLife().chatting() && !e.getLife().canJoinChat() && e.getCitizenAI().getState() != EntityAICitizenLife.LifeState.CHAT,
                          "a chat during the raid: citizen " + e.getCivilianID() + " in " + e.getCitizenAI().getState() + " says '" + e.getSpeech() + "'");
                    }
                }
                for (int i = 0; i < who.length; i++)
                {
                    final String said = who[i].getSpeech();
                    if (raided)
                    {
                        helper.assertTrue(!said.startsWith(CitizenLife.CHEER_BUILDING), "a cheer during the raid: " + said);
                    }
                    else if (end[i].isEmpty() && said.startsWith(CitizenLife.LINE + "raid.end"))
                    {
                        end[i] = said;
                    }
                }
                if (!raided && over[0] < 0)
                {
                    over[0] = now;
                }
                if (over[0] >= 0 && (!end[0].isEmpty() && !end[1].isEmpty() && !end[2].isEmpty() || now - over[0] > 140))
                {
                    helper.assertTrue(end[0].equals(CitizenLife.RAID_END_QUIET), "the mourner said '" + end[0] + "'");
                    helper.assertTrue(end[1].equals(CitizenLife.RAID_END_GRIEF + "|n:" + grieved), "the griever said '" + end[1] + "'");
                    helper.assertTrue(end[2].startsWith(CitizenLife.RAID_END) && !end[2].startsWith(CitizenLife.RAID_END_GRIEF), "the others said '" + end[2] + "'");
                    Log.getLogger().info("ALIVE RESULT no_cheer ends={}", List.of(end));
                    helper.succeed();
                    return;
                }
                helper.assertTrue(now - t0 < 1000, "the raid never ended");
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    // ------------------------------------------------------------------ round 6

    /**
     * N39: a citizen in the rain whose home stands in a dry biome (a desert) does not set off for it: he never enters the shelter, so he does not flicker into it
     * and out again, while he still hurries through the rain.
     */
    public static void shelterDry(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-shelter-dry");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            fillBiome(helper, new BlockPos(-8, 0, -8), new BlockPos(39, 12, 31), net.minecraft.world.level.biome.Biomes.PLAINS);
            final IBuilding home = MinecoloniesGameTests.placeProductionBuilding(helper, colony, com.minecolonies.api.blocks.ModBlocks.blockHutHome, new BlockPos(26, 1, 20),
              "fundamentals/residence1.blueprint");
            fillBiome(helper, new BlockPos(18, 0, 12), new BlockPos(39, 12, 31), net.minecraft.world.level.biome.Biomes.DESERT);
            final BlockPos at = new BlockPos(6, 1, 24);
            final ICitizenData data = spawn(helper, colony, at);
            if (data.getHomeBuilding() != null)
            {
                data.getHomeBuilding().getFirstModuleOccurance(com.minecolonies.core.colony.buildings.modules.LivingBuildingModule.class).removeCitizen(data);
            }
            home.getFirstModuleOccurance(com.minecolonies.core.colony.buildings.modules.LivingBuildingModule.class).assignCitizen(data);
            helper.assertTrue(data.getHomeBuilding() == home, "precondition failed: no home");
            final EntityCitizen citizen = entity(data);
            makeTrait(citizen, LifeTrait.BRISK);
            rain(helper, true);
            final long t0 = helper.getLevel().getGameTime();
            final int[] wet = {0};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                keepFed(citizen);
                pin(helper, citizen, at);
                final long now = helper.getLevel().getGameTime();
                helper.assertTrue(citizen.getCitizenAI().getState() != EntityAICitizenLife.LifeState.SHELTER, "he set off for a dry home");
                if (helper.getLevel().isRaining() && citizen.getLife().hurriesInRain())
                {
                    helper.assertTrue(!CitizenLife.rainsAt(helper.getLevel(), home.getPosition()), "precondition failed: it rains at the desert home");
                    helper.assertTrue(!citizen.getLife().wantsShelter(), "he wants to shelter in a dry home");
                    if (++wet[0] >= 200)
                    {
                        rain(helper, false);
                        Log.getLogger().info("ALIVE RESULT shelter_dry wet_ticks={}", wet[0]);
                        helper.succeed();
                        return;
                    }
                }
                if (now - t0 > 800)
                {
                    rain(helper, false);
                    helper.fail("he was not out in the rain long enough: " + wet[0] + " ticks, raining " + helper.getLevel().isRaining());
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    /**
     * R5-F5: two chatty housemates wait out the rain inside their home and chat there (about the rain), then go back to waiting it out. They chat only where
     * they are dry, so the fixture house (a hut block on the pad) gets a roof.
     */
    public static void shelterChat(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-shelter-chat");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            fillBiome(helper, new BlockPos(-8, 0, -8), new BlockPos(39, 12, 31), net.minecraft.world.level.biome.Biomes.PLAINS);
            final IBuilding home = MinecoloniesGameTests.placeProductionBuilding(helper, colony, com.minecolonies.api.blocks.ModBlocks.blockHutHome, new BlockPos(26, 1, 20),
              "fundamentals/residence1.blueprint");
            home.setBuildingLevel(2);
            // the fixture house is only its hut block: roof its footprint (and the one-block margin isInBuilding allows) so the housemates are dry inside, as
            // in a built house. Round 7 (N51, R6-F2): the roof is laid in world coordinates (helper.relativePos mirrors x and z here, so the round 6 roof was
            // never placed) and clipped to the test plot; the housemates start outside it, in the rain, and walk in
            final net.minecraft.server.level.ServerLevel level = helper.getLevel();
            final BlockPos plotA = helper.absolutePos(new BlockPos(-8, 0, -8));
            final BlockPos plotB = helper.absolutePos(new BlockPos(39, 0, 31));
            final BlockPos cA = home.getCorners().getA();
            final BlockPos cB = home.getCorners().getB();
            final int roofY = home.getPosition().getY() + 4;
            final int x0 = Math.max(Math.min(plotA.getX(), plotB.getX()), Math.min(cA.getX(), cB.getX()) - 1);
            final int x1 = Math.min(Math.max(plotA.getX(), plotB.getX()), Math.max(cA.getX(), cB.getX()) + 1);
            final int z0 = Math.max(Math.min(plotA.getZ(), plotB.getZ()), Math.min(cA.getZ(), cB.getZ()) - 1);
            final int z1 = Math.min(Math.max(plotA.getZ(), plotB.getZ()), Math.max(cA.getZ(), cB.getZ()) + 1);
            helper.assertTrue(x0 <= x1 && z0 <= z1, "precondition failed: the house " + cA + " " + cB + " is off the plot " + plotA + " " + plotB);
            for (int x = x0; x <= x1; x++)
            {
                for (int z = z0; z <= z1; z++)
                {
                    level.setBlock(new BlockPos(x, roofY, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
                }
            }
            // the roof is sky light: canSeeSky reads it, and the light engine settles the new roof a few ticks later, not on the tick it is laid
            final BlockPos inside = EntityAICitizenLife.insideSpot(home, 0);
            final long roofedAt = level.getGameTime();
            final Runnable[] lit = new Runnable[1];
            lit[0] = () -> {
                if (level.canSeeSky(inside.above()) && level.getGameTime() - roofedAt < 100)
                {
                    helper.runAfterDelay(1, () -> lit[0].run());
                    return;
                }
                helper.assertTrue(home.isInBuilding(inside) && !level.canSeeSky(inside.above()), "precondition failed: the house is not roofed at " + inside + " roof y " + roofY
                                                                                                 + " x " + x0 + ".." + x1 + " z " + z0 + ".." + z1);
                for (int i = 0; i < 2; i++)
                {
                    final BlockPos start = helper.absolutePos(new BlockPos(6 + i * 2, 1, 24));
                    helper.assertTrue(!home.isInBuilding(start) && level.canSeeSky(start.above()), "precondition failed: the roof covers the start " + start + " roof x "
                                                                                                  + x0 + ".." + x1 + " z " + z0 + ".." + z1);
                }
                final EntityCitizen[] mates = new EntityCitizen[2];
                for (int i = 0; i < 2; i++)
                {
                    final ICitizenData data = spawn(helper, colony, new BlockPos(6 + i * 2, 1, 24));
                    if (data.getHomeBuilding() != null)
                    {
                        data.getHomeBuilding().getFirstModuleOccurance(com.minecolonies.core.colony.buildings.modules.LivingBuildingModule.class).removeCitizen(data);
                    }
                    home.getFirstModuleOccurance(com.minecolonies.core.colony.buildings.modules.LivingBuildingModule.class).assignCitizen(data);
                    helper.assertTrue(data.getHomeBuilding() == home, "precondition failed: no home for housemate " + i);
                    mates[i] = entity(data);
                    makeTrait(mates[i], LifeTrait.CHATTY);
                }
                final EntityCitizen a = mates[0];
                final EntityCitizen b = mates[1];
                // round 7 (R6-F4): a chatty guest who does not live here stands dry inside the house; nobody sheltering there chats with him
                final ICitizenData guestData = spawn(helper, colony, new BlockPos(10, 1, 24));
                if (guestData.getHomeBuilding() != null)
                {
                    guestData.getHomeBuilding().getFirstModuleOccurance(com.minecolonies.core.colony.buildings.modules.LivingBuildingModule.class).removeCitizen(guestData);
                }
                helper.assertTrue(guestData.getHomeBuilding() == null, "precondition failed: the guest has a home");
                final EntityCitizen guest = entity(guestData);
                makeTrait(guest, LifeTrait.CHATTY);
                BlockPos guestSpot = null;
                for (final BlockPos at : new BlockPos[] {inside.offset(2, 0, 0), inside.offset(-2, 0, 0), inside.offset(0, 0, 2), inside.offset(0, 0, -2), inside.offset(1, 0, 1)})
                {
                    if (guestSpot == null && at.getX() >= x0 && at.getX() <= x1 && at.getZ() >= z0 && at.getZ() <= z1 && !level.canSeeSky(at.above()))
                    {
                        guestSpot = at;
                    }
                }
                helper.assertTrue(guestSpot != null, "precondition failed: no dry spot for the guest by " + inside);
                final BlockPos guestAt = guestSpot;
                rain(helper, true);
                final long t0 = helper.getLevel().getGameTime();
                final String[] opener = {""};
                final long[] chatEnd = {-1};
                final Runnable[] poll = new Runnable[1];
                poll[0] = () -> {
                    keepFed(a, b, guest);
                    if (guestData.getHomeBuilding() != null)
                    {
                        // the colony may give the homeless guest a free bed; he stays a guest
                        guestData.getHomeBuilding().getFirstModuleOccurance(com.minecolonies.core.colony.buildings.modules.LivingBuildingModule.class).removeCitizen(guestData);
                    }
                    guest.getNavigation().stop();
                    if (!guest.blockPosition().equals(guestAt))
                    {
                        guest.teleportTo(guestAt.getX() + 0.5D, guestAt.getY(), guestAt.getZ() + 0.5D);
                    }
                    helper.assertTrue(!guest.getLife().chatting(), "a sheltering housemate chats with a guest: " + guest.getSpeech());
                    final long now = helper.getLevel().getGameTime();
                    if (opener[0].isEmpty())
                    {
                        if (a.getLife().chatting() && b.getLife().chatting() && a.getLife().chatPartnerId() == b.getCivilianID())
                        {
                            helper.assertTrue(home.isInBuilding(a.blockPosition()) && home.isInBuilding(b.blockPosition()), "the chat is not inside the house");
                            final EntityCitizen leader = a.getLife().chatLeader() ? a : b;
                            opener[0] = leader.getSpeech();
                            helper.assertTrue(opener[0].startsWith(CitizenLife.ChatTopic.RAIN.prefix()), "a dry opener in a rain shelter: " + opener[0]);
                        }
                    }
                    else if (chatEnd[0] < 0 && !a.getLife().chatting() && !b.getLife().chatting())
                    {
                        chatEnd[0] = now;
                    }
                    else if (chatEnd[0] >= 0 && now - chatEnd[0] >= 60)
                    {
                        helper.assertTrue(a.getCitizenAI().getState() == EntityAICitizenLife.LifeState.SHELTER || b.getCitizenAI().getState() == EntityAICitizenLife.LifeState.SHELTER,
                          "after the chat nobody waits out the rain: " + a.getCitizenAI().getState() + " / " + b.getCitizenAI().getState());
                        rain(helper, false);
                        Log.getLogger().info("ALIVE RESULT shelter_chat chat_after={} opener={}", chatEnd[0] - t0, opener[0]);
                        helper.succeed();
                        return;
                    }
                    if (now - t0 > 2000)
                    {
                        rain(helper, false);
                        helper.fail("no chat in the shelter: states " + a.getCitizenAI().getState() + " / " + b.getCitizenAI().getState() + " at " + a.blockPosition() + " "
                                      + b.blockPosition() + " inside " + a.getLife().shelteredAtHome() + "/" + b.getLife().shelteredAtHome());
                        return;
                    }
                    helper.runAfterDelay(1, () -> poll[0].run());
                };
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> lit[0].run());
        });
    }

    /**
     * Round 7 (R6-F3, N48): a dawn with neighbours waking at different times. A brisk first riser with only sleepers around him waits within his hold and
     * greets the first one up, never before; a greeter whose only neighbour sleeps through the wait gives up without a word and is not held past
     * {@link CitizenLife#GREET_WAIT}.
     */
    public static void morningDawn(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-morning-dawn");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            final BlockPos greeterAt = new BlockPos(10, 1, 10);
            final EntityCitizen greeter = entity(spawn(helper, colony, greeterAt));
            makeTrait(greeter, LifeTrait.BRISK);
            final BlockPos lateAt = new BlockPos(36, 1, 29);
            final EntityCitizen late = entity(spawn(helper, colony, lateAt));
            makeTrait(late, LifeTrait.BRISK);
            // neighbours of the first riser wake one after the other; the late greeter's only neighbour sleeps through the test
            final BlockPos[] sleeperAt = {new BlockPos(12, 1, 10), new BlockPos(10, 1, 13), new BlockPos(13, 1, 12), new BlockPos(34, 1, 28)};
            final int[] wakeAfter = {40, 100, Integer.MAX_VALUE, Integer.MAX_VALUE};
            final EntityCitizen[] sleepers = new EntityCitizen[sleeperAt.length];
            for (int i = 0; i < sleepers.length; i++)
            {
                sleepers[i] = entity(spawn(helper, colony, sleeperAt[i]));
                makeTrait(sleepers[i], LifeTrait.THOUGHTFUL);
            }
            final Runnable holdSleep = () -> {
                for (int i = 0; i < sleepers.length; i++)
                {
                    if (sleepers[i].getCitizenData().isAsleep())
                    {
                        // a sleeper's AI would wake him at its next look in daylight; it rests until the test wakes him
                        sleepers[i].getCitizenAI().setCurrentDelay(20);
                        pin(helper, sleepers[i], sleeperAt[i]);
                    }
                }
            };
            for (final EntityCitizen sleeper : sleepers)
            {
                sleeper.getCitizenData().setAsleep(true);
            }
            holdSleep.run();
            final ServerLevel level = helper.getLevel();
            long day = level.getOverworldClockTime() / 24000L + 1;
            for (int guard = 0; guard < 1000 && !(CitizenLife.greetsOn(greeter.getCivilianID(), day, LifeTrait.BRISK)
                                                   && CitizenLife.greetsOn(late.getCivilianID(), day, LifeTrait.BRISK)); guard++)
            {
                day++;
            }
            helper.assertTrue(CitizenLife.greetsOn(greeter.getCivilianID(), day, LifeTrait.BRISK) && CitizenLife.greetsOn(late.getCivilianID(), day, LifeTrait.BRISK),
              "precondition failed: no morning where both greet");
            setClock(helper, day * 24000L + 100L);
            for (final EntityCitizen c : new EntityCitizen[] {greeter, late})
            {
                helper.assertTrue(!c.getLife().someoneToGreet(c.blockPosition()), "precondition failed: someone is awake by " + c.getCivilianID());
                helper.assertTrue(c.getLife().greetingHolds(true) && c.getLife().gatherSpot() == null && c.getLife().awaitingListener(),
                  "a first riser among sleepers does not wait: holds " + c.getLife().greetingHolds(true) + " spot " + c.getLife().gatherSpot());
            }
            final long t0 = level.getGameTime();
            final long[] greetedAt = {-1};
            final long[] lateHeld = {0};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                final long now = level.getGameTime();
                keepFed(greeter, late);
                keepFed(sleepers);
                for (int i = 0; i < sleepers.length; i++)
                {
                    if (sleepers[i].getCitizenData().isAsleep() && now - t0 >= wakeAfter[i])
                    {
                        sleepers[i].getCitizenSleepHandler().onWakeUp();
                    }
                }
                holdSleep.run();
                if (greetedAt[0] < 0 && greeter.getSpeech().contains(".morning"))
                {
                    greetedAt[0] = now;
                    helper.assertTrue(now - t0 >= wakeAfter[0], "the first riser greeted before anyone woke, at " + (now - t0));
                    helper.assertTrue(greeter.getLife().someoneToGreet(greeter.blockPosition()), "the first riser greets with nobody awake near");
                }
                if (greeter.getLife().greetingHolds(true))
                {
                    helper.assertTrue(now - greeter.getLife().greetingStart() <= CitizenLife.MORNING_BUDGET, "the greeting held work past the budget");
                }
                helper.assertTrue(!late.getSpeech().contains(".morning"), "the late greeter greeted a sleeper: '" + late.getSpeech() + "'");
                helper.assertTrue(late.getLife().gatherSpot() == null, "the late greeter walks off to greet: " + late.getLife().gatherSpot());
                if (late.getLife().greetingHolds(true))
                {
                    lateHeld[0] = now - late.getLife().greetingStart();
                    helper.assertTrue(lateHeld[0] <= CitizenLife.GREET_WAIT, "the late greeter waited " + lateHeld[0] + " ticks");
                }
                if (now - t0 > CitizenLife.MORNING_BUDGET + 60)
                {
                    helper.assertTrue(greetedAt[0] >= 0, "the first riser never greeted: state " + greeter.getCitizenAI().getState() + " waiting "
                                                         + greeter.getLife().awaitingListener() + " skipped " + greeter.getLife().greetSkipped());
                    helper.assertTrue(late.getLife().greetSkipped() && !late.getLife().greetingHolds(true), "the late greeter's wait did not end");
                    Log.getLogger().info("ALIVE RESULT morning_dawn greeted_after={} late_held={}", greetedAt[0] - t0, lateHeld[0]);
                    helper.succeed();
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    /**
     * R5-F4: when rain starts, and again when a raid starts, six citizens standing together do not all speak in the same second, and not all with the same line.
     */
    public static void chorus(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-chorus");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            fillBiome(helper, new BlockPos(-8, 0, -8), new BlockPos(39, 12, 31), net.minecraft.world.level.biome.Biomes.PLAINS);
            final int n = 6;
            final EntityCitizen[] c = new EntityCitizen[n];
            final BlockPos[] at = new BlockPos[n];
            for (int i = 0; i < n; i++)
            {
                at[i] = new BlockPos(12 + i * 3, 1, 16);
                c[i] = entity(spawn(helper, colony, at[i]));
                makeTrait(c[i], LifeTrait.BRISK);
            }
            rain(helper, true);
            final long[] first = new long[n];
            final String[] line = new String[n];
            java.util.Arrays.fill(first, -1);
            final int[] phase = {0};
            final long[] phaseStart = {helper.getLevel().getGameTime()};
            final List<String> log = new ArrayList<>();
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                final long now = helper.getLevel().getGameTime();
                final String prefix = phase[0] == 0 ? CitizenLife.RAIN : CitizenLife.RAID_START;
                int done = 0;
                for (int i = 0; i < n; i++)
                {
                    keepFed(c[i]);
                    pin(helper, c[i], at[i]);
                    if (first[i] < 0 && c[i].getSpeech().startsWith(prefix))
                    {
                        first[i] = now;
                        line[i] = c[i].getSpeech();
                    }
                    done += first[i] >= 0 ? 1 : 0;
                }
                if (done == n)
                {
                    final long spread = java.util.Arrays.stream(first).max().getAsLong() - java.util.Arrays.stream(first).min().getAsLong();
                    final long distinct = java.util.Arrays.stream(line).distinct().count();
                    log.add(prefix + " spread " + spread + " lines " + distinct);
                    helper.assertTrue(spread >= 20, "a chorus: all six said '" + prefix + "' within " + spread + " ticks");
                    helper.assertTrue(distinct >= 2, "all six said the same line: " + line[0]);
                    if (phase[0] == 1)
                    {
                        Log.getLogger().info("ALIVE RESULT chorus {}", log);
                        helper.succeed();
                        return;
                    }
                    rain(helper, false);
                    colony.getRaiderManager().setPassThroughRaid();
                    java.util.Arrays.fill(first, -1);
                    phase[0] = 1;
                    phaseStart[0] = now;
                }
                if (now - phaseStart[0] > 400)
                {
                    rain(helper, false);
                    helper.fail("only " + done + " of " + n + " said a line in phase " + phase[0] + " " + log);
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    /**
     * N41: lines do not talk over each other. Rain that starts mid-chat gets its remark only after the chat; a cheer due mid-chat waits for its end; a cheer
     * does not push out a waiting relief line, and a rain word waits until the relief line was said.
     */
    public static void linesWait(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "alive-lines-wait");
        midday(helper);
        helper.runAfterDelay(40, () -> {
            fillBiome(helper, new BlockPos(-8, 0, -8), new BlockPos(39, 12, 31), net.minecraft.world.level.biome.Biomes.PLAINS);
            final EntityCitizen a = entity(spawn(helper, colony, new BlockPos(16, 1, 16)));
            final EntityCitizen b = entity(spawn(helper, colony, new BlockPos(19, 1, 16)));
            final BlockPos cAt = new BlockPos(32, 1, 27);
            final EntityCitizen c = entity(spawn(helper, colony, cAt));
            makeTrait(a, LifeTrait.BRISK);
            makeTrait(b, LifeTrait.BRISK);
            makeTrait(c, LifeTrait.STEADY);
            keepFed(a, b, c);

            // a relief line waits; a cheer that comes after it is dropped, not said over it
            c.getLife().schedule(CitizenLife.RAID_END + "1", 40, true);
            c.getLife().schedule(CitizenLife.CHEER_BUILDING + "1", 10, true);
            helper.assertTrue(c.getLife().pendingLine() != null && c.getLife().pendingLine().startsWith(CitizenLife.RAID_END), "the cheer pushed out the relief: "
                                                                                                                                + c.getLife().pendingLine());

            a.getLife().startChat(b);
            helper.assertTrue(a.getLife().chatting() && b.getLife().chatting(), "precondition failed: no chat");
            b.getLife().schedule(CitizenLife.CHEER_BUILDING + "2", 0, true);
            rain(helper, true);
            final long t0 = helper.getLevel().getGameTime();
            final long[] chatEnd = {-1};
            final long[] rainAfter = {-1};
            final long[] cheerAfter = {-1};
            final boolean[] relief = {false};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                keepFed(a, b, c);
                pin(helper, c, cAt);
                final long now = helper.getLevel().getGameTime();
                final boolean talking = a.getLife().chatting() || b.getLife().chatting();
                if (talking)
                {
                    helper.assertTrue(!a.getSpeech().startsWith(CitizenLife.RAIN) && !b.getSpeech().startsWith(CitizenLife.RAIN), "a rain remark over the chat: '"
                                                                                                                              + a.getSpeech() + "' '" + b.getSpeech() + "'");
                    helper.assertTrue(!b.getSpeech().startsWith(CitizenLife.CHEER_BUILDING), "a cheer over the chat");
                }
                else if (chatEnd[0] < 0)
                {
                    chatEnd[0] = now;
                }
                if (chatEnd[0] >= 0)
                {
                    rainAfter[0] = rainAfter[0] < 0 && a.getSpeech().startsWith(CitizenLife.RAIN) ? now : rainAfter[0];
                    cheerAfter[0] = cheerAfter[0] < 0 && b.getSpeech().startsWith(CitizenLife.CHEER_BUILDING) ? now : cheerAfter[0];
                }
                helper.assertTrue(!c.getSpeech().startsWith(CitizenLife.CHEER_BUILDING), "the dropped cheer was said");
                relief[0] |= c.getSpeech().startsWith(CitizenLife.RAID_END);
                helper.assertTrue(relief[0] || !c.getSpeech().equals(CitizenLife.RAIN_CALM), "the rain word came before the relief line");
                if (chatEnd[0] >= 0 && rainAfter[0] >= 0 && cheerAfter[0] >= 0 && relief[0])
                {
                    helper.assertTrue(cheerAfter[0] - chatEnd[0] <= 60, "the waiting cheer came late: " + (cheerAfter[0] - chatEnd[0]));
                    rain(helper, false);
                    Log.getLogger().info("ALIVE RESULT lines_wait chat_end={} rain_after={} cheer_after={}", chatEnd[0] - t0, rainAfter[0] - chatEnd[0],
                      cheerAfter[0] - chatEnd[0]);
                    helper.succeed();
                    return;
                }
                if (now - t0 > 600)
                {
                    rain(helper, false);
                    helper.fail("lines missing: chat end " + chatEnd[0] + " rain " + rainAfter[0] + " cheer " + cheerAfter[0] + " relief " + relief[0] + " a '"
                                  + a.getSpeech() + "' b '" + b.getSpeech() + "' c '" + c.getSpeech() + "'");
                    return;
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }

    // ------------------------------------------------------------------ helpers

    private static void midday(final GameTestHelper helper)
    {
        final long clock = helper.getLevel().getOverworldClockTime();
        setClock(helper, clock / 24000L * 24000L + 6000L);
    }

    private static void setClock(final GameTestHelper helper, final long ticks)
    {
        final ServerLevel level = helper.getLevel();
        level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), ticks);
    }

    private static void keepFed(final EntityCitizen... citizens)
    {
        for (final EntityCitizen c : citizens)
        {
            if (c.getCitizenData() != null)
            {
                c.getCitizenData().setSaturation(20);
            }
        }
    }

    private static void makeTrait(final EntityCitizen citizen, final LifeTrait trait)
    {
        citizen.getCitizenData().setLifeTrait(trait.name());
        citizen.getLife().refreshTrait();
        if (citizen.getLife().trait() != trait)
        {
            throw new IllegalStateException("trait " + citizen.getLife().trait() + " instead of " + trait);
        }
    }

    private static void fillBiome(final GameTestHelper helper, final BlockPos from, final BlockPos to,
      final net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome> biome)
    {
        final ServerLevel level = helper.getLevel();
        final var holder = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME).getOrThrow(biome);
        final var result = net.minecraft.server.commands.FillBiomeCommand.fill(level, helper.absolutePos(from), helper.absolutePos(to), holder);
        helper.assertTrue(result.left().isPresent(), "could not set the biome " + biome + ": " + result.right().map(Exception::getMessage).orElse(""));
    }

    /**
     * Keeps a citizen standing on its spot (a test about a place must not let him wander off it).
     */
    private static void pin(final GameTestHelper helper, final EntityCitizen citizen, final BlockPos rel)
    {
        final BlockPos at = helper.absolutePos(rel);
        citizen.getNavigation().stop();
        if (!citizen.blockPosition().equals(at))
        {
            citizen.teleportTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
        }
    }

    private static void rain(final GameTestHelper helper, final boolean on)
    {
        final var weather = helper.getLevel().getWeatherData();
        weather.setRaining(on);
        weather.setThundering(false);
        weather.setRainTime(on ? 1_000_000 : 0);
        weather.setClearWeatherTime(on ? 0 : 1_000_000);
    }

    private static EntityCitizen entity(final ICitizenData data)
    {
        return (EntityCitizen) data.getEntity().get();
    }

    private static ICitizenData spawn(final GameTestHelper helper, final IColony colony, final BlockPos rel)
    {
        final BlockPos anchor = helper.absolutePos(rel);
        helper.getLevel().setChunkForced(anchor.getX() >> 4, anchor.getZ() >> 4, true);
        final ICitizenData citizen = colony.getCitizenManager().spawnOrCreateCivilian(null, helper.getLevel(), List.of(anchor), true);
        helper.assertTrue(citizen != null && citizen.getEntity().isPresent() && citizen.getEntity().get() instanceof EntityCitizen,
          "could not spawn a citizen at " + anchor);
        citizen.setSaturation(20);
        return citizen;
    }

}
