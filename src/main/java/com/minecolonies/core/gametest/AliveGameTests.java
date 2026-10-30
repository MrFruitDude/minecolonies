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
      "alive_rain_hurry"};

    /**
     * A life moment must give way within this many ticks: the AI decides every 10 ticks, a life step runs every 20, the setting is read once a second.
     */
    private static final int YIELD_TICKS = 60;

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

        // pace: a rain hurry is faster, grief is slower off work, nothing is ever slower at work
        for (final LifeTrait t : LifeTrait.values())
        {
            for (final boolean rain : new boolean[] {false, true})
            {
                for (final boolean grief : new boolean[] {false, true})
                {
                    helper.assertTrue(CitizenLife.pace(rain, true, t, grief) >= 0, "slower at work: " + t + " rain " + rain + " grief " + grief);
                }
            }
            helper.assertTrue(CitizenLife.pace(false, false, t, true) < 0, "grief not slower off work for " + t);
            helper.assertTrue(CitizenLife.pace(true, false, t, false) > 0, "rain hurry not faster for " + t);
        }
        helper.assertTrue(CitizenLife.isAtWork(CitizenAIState.WORK) && CitizenLife.isAtWork(CitizenAIState.WORKING) && CitizenLife.isAtWork(CitizenAIState.LABOUR)
                            && !CitizenLife.isAtWork(CitizenAIState.IDLE), "at work states");
        for (final IState state : new IState[] {CitizenAIState.WORK, CitizenAIState.WORKING, CitizenAIState.LABOUR, CitizenAIState.EATING, CitizenAIState.SLEEP,
          CitizenAIState.MOURN, CitizenAIState.SICK})
        {
            helper.assertTrue(!CitizenLife.inFreeTime(state), "a chat may run in " + state);
        }
        helper.assertTrue(CitizenLife.inFreeTime(CitizenAIState.IDLE) && CitizenLife.inFreeTime(EntityAICitizenWander.WanderState.WANDER_AT_LEISURE_SITE)
                            && CitizenLife.inFreeTime(EntityAICitizenLife.LifeState.CHAT), "free time states");

        // speech: a key, a key with a name, a key with a lang key, nothing
        helper.assertTrue(AbstractEntityCitizen.speechComponent("") == null && AbstractEntityCitizen.speechComponent(null) == null, "empty speech");
        helper.assertTrue(key(AbstractEntityCitizen.speechComponent("minecolonies.life.morning.1")).equals("minecolonies.life.morning.1"), "plain key");
        final Component named = AbstractEntityCitizen.speechComponent(CitizenLife.GRIEF_LINE + "|n:Ann Smith");
        helper.assertTrue(key(named).equals(CitizenLife.GRIEF_LINE) && args(named).length == 1 && ((Component) args(named)[0]).getString().equals("Ann Smith"), "name arg");
        final Component job = AbstractEntityCitizen.speechComponent(CitizenLife.CHAT_JOB + "|t:com.minecolonies.job.builder");
        helper.assertTrue(key(job).equals(CitizenLife.CHAT_JOB) && key((Component) args(job)[0]).equals("com.minecolonies.job.builder"), "lang key arg");

        // bounds of the moments
        helper.assertTrue(CitizenLife.SPEECH_TICKS <= 100 && CitizenLife.CHAT_TICKS <= 100 && CitizenLife.MORNING_BUDGET <= 300 && CitizenLife.GREET_LINGER < CitizenLife.MORNING_BUDGET
                            && CitizenLife.MORNING_END <= 2400 && ColonyLife.MAX_CHEERERS <= 8 && ColonyLife.GRIEF_TICKS <= 6000, "bounds");
        helper.succeed();
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
                helper.runAfterDelay(1, poll[0]);
            };
            helper.runAfterDelay(1, poll[0]);
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
                            helper.assertTrue(b.getCitizenAI().getState() == CitizenAIState.EATING || b.getCitizenData().getSaturation() > 0,
                              "hungry partner went to " + b.getCitizenAI().getState());
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
                        helper.runAfterDelay(1, poll[0]);
                    };
                    helper.runAfterDelay(1, poll[0]);
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
            keepFed(a, worker);
            final ServerLevel level = helper.getLevel();
            final long nextMorning = (level.getOverworldClockTime() / 24000L + 1) * 24000L + 100L;
            setClock(helper, nextMorning);
            // the worker's first look today comes from work: no greeting, all day
            helper.assertTrue(!worker.getLife().greetingHolds(false) && !worker.getLife().greetingHolds(true), "a citizen at work was held for a greeting");
            final long t0 = level.getGameTime();
            final long[] held = {0};
            final boolean[] greeted = {false};
            final boolean[] gathered = {false};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                keepFed(a, worker);
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
                helper.runAfterDelay(1, poll[0]);
            };
            helper.runAfterDelay(1, poll[0]);
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
                helper.assertTrue(helper.getLevel().getGameTime() - t0 < 160, "only " + count2 + " cheered aloud in 160 ticks");
                helper.runAfterDelay(1, poll[0]);
            };
            helper.runAfterDelay(1, poll[0]);
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
            helper.assertTrue(deadData.getName().equals(ColonyLife.griefFor(colony, friendData.getId(), now)), "no grief for the friend");
            helper.assertTrue(ColonyLife.griefFor(colony, strangerData.getId(), now) == null, "the stranger grieves");
            helper.assertTrue((CitizenLife.GRIEF_LINE + "|n:" + deadData.getName()).equals(friend.getLife().pendingLine()), "no grief line: " + friend.getLife().pendingLine());
            helper.runAfterDelay(80, () -> {
                keepFed(friend);
                final IState state = friend.getCitizenAI().getState();
                helper.assertTrue(friend.getSpeech().startsWith(CitizenLife.GRIEF_LINE), "the friend said '" + friend.getSpeech() + "'");
                helper.assertTrue(CitizenLife.isAtWork(state) ? friend.getLife().appliedPace() >= 0 : friend.getLife().appliedPace() < 0,
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
                    warned[0] |= a.getSpeech().equals(CitizenLife.RAID_START) || b.getSpeech().equals(CitizenLife.RAID_START);
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
                    helper.runAfterDelay(1, poll[0]);
                };
                helper.runAfterDelay(1, poll[0]);
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
            final EntityCitizen bare = entity(spawn(helper, colony, new BlockPos(12, 1, 18)));
            final EntityCitizen hat = entity(spawn(helper, colony, new BlockPos(24, 1, 18)));
            hat.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
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
                hat.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
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
                helper.runAfterDelay(1, poll[0]);
            };
            helper.runAfterDelay(1, poll[0]);
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
        final var skills = citizen.getCitizenData().getCitizenSkillHandler();
        for (final Skill s : Skill.values())
        {
            skills.incrementLevel(s, -1000);
        }
        final Skill[] group = switch (trait)
        {
            case BRISK -> new Skill[] {Skill.Athletics, Skill.Agility, Skill.Stamina};
            case CHATTY -> new Skill[] {Skill.Adaptability, Skill.Creativity, Skill.Mana};
            case THOUGHTFUL -> new Skill[] {Skill.Intelligence, Skill.Knowledge, Skill.Focus};
            case STEADY -> new Skill[] {Skill.Strength, Skill.Dexterity};
        };
        for (final Skill s : group)
        {
            skills.incrementLevel(s, 30);
        }
        citizen.getLife().refreshTrait();
        if (citizen.getLife().trait() != trait)
        {
            throw new IllegalStateException("trait " + citizen.getLife().trait() + " instead of " + trait);
        }
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
