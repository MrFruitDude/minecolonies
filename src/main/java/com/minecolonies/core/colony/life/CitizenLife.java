package com.minecolonies.core.colony.life;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.entity.ai.minimal.EntityAICitizenLife;
import com.minecolonies.core.entity.ai.minimal.EntityAICitizenWander;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Round 4 life: the small things one citizen does that make him look like a person, on top of MineColonies' leisure, mourning and sleep. Lives with the entity
 * (only the trait is saved, with the citizen's data). Server side only. Everything here is bounded: a line lasts {@link #SPEECH_TICKS}, a chat
 * {@link #CHAT_TICKS}, a morning greeting holds work back at most {@link #MORNING_BUDGET} ticks once a day, and nothing ever slows a citizen down outside his
 * free time. The server setting "citizenlifeextras" turns it all off.
 */
public final class CitizenLife
{
    /**
     * How long a line stays above the head, in ticks.
     */
    public static final int SPEECH_TICKS = 70;

    /**
     * The shortest gap between two remarks of a citizen (rain), in ticks. Chats, cheers, greetings and the raid warning are not held back by it.
     */
    public static final int REMARK_GAP = 400;

    /**
     * A chat: how long it lasts, how far the partner may stand, how often a free citizen looks for one, and when the partner answers.
     */
    public static final int CHAT_TICKS     = 100;
    public static final int CHAT_RANGE     = 6;
    public static final int CHAT_TRY_TICKS = 100;
    public static final int REPLY_AFTER    = 40;

    /**
     * The morning: some citizens greet (by the chance of their trait, a different set each day) before this time of day (dawn is 0). A greeting holds work back at
     * most {@link #MORNING_BUDGET} ticks and goes to a spot near a building within {@link #GATHER_RANGE} blocks (the town hall, else the citizen's hut, else his
     * home; at most {@link ColonyLife#MAX_GREETERS_PER_SPOT} at each) or where he stands. The line is said on arrival, within {@link #GREET_TALK_RANGE} blocks of
     * the spot, or at the latest {@link #GREET_LINGER} ticks before the budget ends.
     */
    public static final int MORNING_END      = 2000;
    public static final int MORNING_BUDGET   = 200;
    public static final int GATHER_RANGE     = 24;
    public static final int GREET_LINGER     = 40;
    public static final int GREET_TALK_RANGE = 6;

    /**
     * Rain: a citizen out in the rain without a hat walks this much faster (also at work), and says so once per rain spell.
     */
    public static final double RAIN_HURRY = 0.15D;

    /**
     * Grief: a grieving citizen walks this much slower, only in his free time (never while he flees, hides, heads for the hospital or works).
     */
    public static final double GRIEF_PACE = -0.15D;

    /**
     * The one walking speed modifier this class owns.
     */
    public static final Identifier PACE_ID = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "life_pace");

    /**
     * Lang keys and the sizes of their pools (keys end in 1 to N).
     */
    public static final String LINE            = "minecolonies.life.";
    public static final String CHAT            = LINE + "chat.";
    public static final String CHAT_GRIEF_OWN  = CHAT + "grief.own";
    public static final String GRIEF_LINE      = LINE + "grief";
    public static final String CHEER_BUILDING  = LINE + "cheer.building.";
    public static final int    CHEER_BUILDING_LINES = 3;
    public static final String RAID_START      = LINE + "raid.start";
    public static final String RAID_END        = LINE + "raid.end.";
    public static final int    RAID_END_LINES  = 2;
    public static final String RAID_END_GRIEF  = LINE + "raid.endgrief";
    public static final String RAID_END_QUIET  = LINE + "raid.endquiet";
    public static final String RAIN            = LINE + "rain.";
    public static final int    RAIN_LINES      = 3;
    public static final String RAIN_CALM       = LINE + "rain.calm";
    public static final String MORNING         = LINE + "morning.";
    public static final int    MORNING_LINES   = 4;

    /**
     * Each chat opener has this many replies that fit it (key of the opener + ".reply." + 1 to N).
     */
    public static final int CHAT_REPLIES = 2;

    /**
     * Free time from this time of day counts as the evening for the chat openers.
     */
    public static final int EVENING_FROM = 11000;

    /**
     * What a chat is about: each topic has its own openers (key {@link #CHAT} + topic + "." + 1 to N), each opener its own replies.
     */
    public enum ChatTopic
    {
        HAPPY(7),
        UNHAPPY(5),
        RAIN(4),
        COLD(3),
        EVENING(4),
        JOB(2),
        GRIEF(1);

        private final int lines;

        ChatTopic(final int lines)
        {
            this.lines = lines;
        }

        /**
         * @return how many openers the topic has.
         */
        public int lines()
        {
            return lines;
        }

        /**
         * @return the lang key of the topic's openers without their number.
         */
        public String prefix()
        {
            return CHAT + name().toLowerCase(java.util.Locale.ROOT) + ".";
        }
    }

    private final EntityCitizen citizen;

    private long speechUntil = 0;
    private long lastRemark  = Long.MIN_VALUE / 2;

    private String  pendingLine  = null;
    private long    pendingAt    = 0;
    private boolean pendingCheer = false;

    /**
     * A cheer or a grief line is not talked over by a chat until then.
     */
    private long lineHeldUntil = 0;

    private int                          chatPartnerId = 0;
    private WeakReference<EntityCitizen> chatPartner   = new WeakReference<>(null);
    private boolean                      chatLeader    = false;
    private long                         chatUntil     = 0;
    private long                         replyAt       = -1;
    private String                       replyTo       = "";
    private long                         nextChatAt    = 0;
    private long                         nextChatTry   = 0;

    private long     greetingDay   = -1;
    private long     greetingStart = 0;
    private boolean  greetingDone  = true;
    private BlockPos gatherSpot    = null;

    private boolean sawRaid       = false;
    private boolean rainLineSaid  = false;
    private long    shelterRetry  = 0;

    private double    appliedPace = 0;
    private LifeTrait trait       = null;

    public CitizenLife(final EntityCitizen citizen)
    {
        this.citizen = citizen;
    }

    /**
     * @return whether the server allows the life extras (setting "citizenlifeextras").
     */
    public static boolean enabled()
    {
        return MineColonies.getConfig().getServer().citizenLifeExtras.get();
    }

    private long now()
    {
        return citizen.level().getGameTime();
    }

    /**
     * @return the citizen's trait: saved with his data, picked once for his life ({@link LifeTrait#of(ICitizenData)}).
     */
    public LifeTrait trait()
    {
        if (trait == null)
        {
            final ICitizenData data = citizen.getCitizenData();
            if (data == null)
            {
                return LifeTrait.STEADY;
            }
            trait = LifeTrait.of(data);
        }
        return trait;
    }

    /**
     * Reads the saved trait again on the next ask (tests change it).
     */
    public void refreshTrait()
    {
        trait = null;
    }

    /**
     * @return whether the citizen mourns in MineColonies' own way (a family member or housemate died).
     */
    public boolean mourning()
    {
        final ICitizenData data = citizen.getCitizenData();
        return data != null && data.getCitizenMournHandler().isMourning();
    }

    /**
     * @return the name of the friend the citizen grieves for, null for none.
     */
    @Nullable
    public String grief()
    {
        final IColony colony = citizen.getCitizenColonyHandler().getColony();
        return colony == null ? null : ColonyLife.griefFor(colony, citizen.getCivilianID(), now());
    }

    // ---- speech -------------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Shows a line above the head for {@link #SPEECH_TICKS}.
     *
     * @param raw the lang key with its argument (see {@code AbstractEntityCitizen.DATA_SPEECH}).
     */
    public void say(final String raw)
    {
        citizen.setSpeech(raw);
        speechUntil = now() + SPEECH_TICKS;
    }

    /**
     * Says a remark unless the citizen made one in the last {@link #REMARK_GAP} ticks.
     *
     * @return true if said.
     */
    public boolean remark(final String raw)
    {
        final long now = now();
        if (now - lastRemark < REMARK_GAP)
        {
            return false;
        }
        lastRemark = now;
        say(raw);
        return true;
    }

    /**
     * Says a line (and cheers: a hop, an arm wave and sparkles) after a delay; a newer line replaces one that is still waiting.
     */
    public void schedule(final String raw, final int delay, final boolean cheer)
    {
        pendingLine = raw;
        pendingAt = now() + delay;
        pendingCheer = cheer;
    }

    /**
     * @return the line waiting to be said, null for none (for tests).
     */
    @Nullable
    public String pendingLine()
    {
        return pendingLine;
    }

    /**
     * @return whether a cheer or a grief line is up and no chat may talk over it.
     */
    public boolean lineHeld()
    {
        return now() < lineHeldUntil;
    }

    private void cheer(final String raw)
    {
        say(raw);
        lineHeldUntil = now() + SPEECH_TICKS;
        citizen.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        if (citizen.onGround())
        {
            citizen.getJumpControl().jump();
        }
        if (citizen.level() instanceof ServerLevel level)
        {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, citizen.getX(), citizen.getY() + citizen.getBbHeight() + 0.3D, citizen.getZ(), 5, 0.3D, 0.2D, 0.3D, 0.0D);
        }
    }

    // ---- once a second ------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Called by the citizen once a second (server): ends an old line, says a waiting one, notices a raid start and end, hurries in the rain, sets the pace.
     */
    public void tick20()
    {
        final long now = now();
        if (!enabled())
        {
            if (speechUntil != 0)
            {
                citizen.setSpeech("");
                speechUntil = 0;
            }
            pendingLine = null;
            chatPartnerId = 0;
            greetingDone = true;
            applyPace(0);
            return;
        }
        if (speechUntil != 0 && now >= speechUntil)
        {
            citizen.setSpeech("");
            speechUntil = 0;
        }

        final boolean asleep = citizen.getCitizenSleepHandler().isAsleep();
        final IColony colony = citizen.getCitizenColonyHandler().getColony();
        final boolean raided = colony != null && colony.getRaiderManager().isRaided();
        final String grief = grief();
        if (pendingLine != null && now >= pendingAt)
        {
            final String line = pendingLine;
            pendingLine = null;
            if (!asleep)
            {
                if (!pendingCheer)
                {
                    say(line);
                    lineHeldUntil = line.startsWith(GRIEF_LINE) ? now + SPEECH_TICKS : lineHeldUntil;
                }
                else if (!raided && grief == null && !mourning())
                {
                    // a cheer that comes due in a raid, a grief or a mourning is dropped
                    cheer(line);
                }
            }
        }

        if (raided && !sawRaid)
        {
            sawRaid = true;
            if (!asleep)
            {
                say(RAID_START);
            }
        }
        else if (!raided && sawRaid)
        {
            // relief: one after the other, spread by id over 3 seconds; a griever or a mourner only says a quiet line, without the cheer
            sawRaid = false;
            final boolean mourning = mourning();
            final String line = grief != null ? RAID_END_GRIEF + "|n:" + grief
                                  : mourning ? RAID_END_QUIET : RAID_END + (1 + citizen.getRandom().nextInt(RAID_END_LINES));
            schedule(line, 20 + Math.floorMod(citizen.getCivilianID() * 13, 60), grief == null && !mourning);
        }

        // rain: one line per rain spell, said when the citizen first stands in it; a steady citizen keeps his pace and says a calm word
        if (!citizen.level().isRaining())
        {
            rainLineSaid = false;
        }
        final boolean wet = !asleep && wetWithoutHat();
        if (wet && !rainLineSaid
              && remark(trait().mindsRain() ? RAIN + (1 + citizen.getRandom().nextInt(RAIN_LINES)) : RAIN_CALM))
        {
            rainLineSaid = true;
        }
        final boolean hurry = wet && trait().mindsRain();
        applyPace(pace(hurry, !raided && isFreeTime(citizen.getCitizenAI().getState()), trait(), grief != null));
    }

    /**
     * Whether rain falls on the citizen now: the world rains, his biome rains there (no rain in a desert, savanna or badlands, snow is not rain) and nothing
     * covers him (as vanilla's own wet check).
     */
    public boolean inRain()
    {
        final Level level = citizen.level();
        return level.isRainingAt(citizen.blockPosition())
                 || level.isRainingAt(BlockPos.containing(citizen.getX(), citizen.getBoundingBox().maxY, citizen.getZ()));
    }

    /**
     * Whether rain falls at a place, ignoring a roof: the world rains and the biome there rains (not snows, not dry).
     */
    public static boolean rainsAt(final Level level, final BlockPos pos)
    {
        return level.isRaining() && level.getBiome(pos).value().getPrecipitationAt(pos, level.getSeaLevel()) == Biome.Precipitation.RAIN;
    }

    private boolean wetWithoutHat()
    {
        return citizen.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && inRain();
    }

    /**
     * Whether the citizen hurries through the rain: rain falls on him, he has nothing on his head, and his trait minds the rain.
     */
    public boolean hurriesInRain()
    {
        return trait().mindsRain() && wetWithoutHat();
    }

    /**
     * @return whether the citizen said his rain line in this rain spell (for tests).
     */
    public boolean rainLineSaid()
    {
        return rainLineSaid;
    }

    /**
     * The walking speed change: a rain hurry always, the trait and grief only in free time (so work, flight, hiding and the way to the hospital are never slower).
     *
     * @param rainHurry whether he hurries through the rain.
     * @param freeTime  whether he is in his free time ({@link #isFreeTime}) and no raid is on.
     * @param trait     his trait.
     * @param grieving  whether he grieves.
     */
    public static double pace(final boolean rainHurry, final boolean freeTime, final LifeTrait trait, final boolean grieving)
    {
        double pace = rainHurry ? RAIN_HURRY : 0;
        if (freeTime)
        {
            pace += trait.offWorkPace() + (grieving ? GRIEF_PACE : 0);
        }
        return pace;
    }

    /**
     * @return whether the AI state is work (a job or an idle task).
     */
    public static boolean isAtWork(final IState state)
    {
        return state == CitizenAIState.WORK || state == CitizenAIState.WORKING || state == CitizenAIState.LABOUR;
    }

    /**
     * @return whether the AI state is free time where the trait and grief may set the pace: idle, the wander AI's leisure steps and the life moments. Not work,
     * flight, sleep or the way home to hide, eating, sickness or mourning.
     */
    public static boolean isFreeTime(final IState state)
    {
        return state == CitizenAIState.IDLE || state instanceof EntityAICitizenWander.WanderState || state instanceof EntityAICitizenLife.LifeState;
    }

    private void applyPace(final double pace)
    {
        final AttributeInstance speed = citizen.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null || (Math.abs(pace - appliedPace) < 1.0E-6 && (pace == 0) != speed.hasModifier(PACE_ID)))
        {
            return;
        }
        speed.removeModifier(PACE_ID);
        if (pace != 0)
        {
            speed.addTransientModifier(new AttributeModifier(PACE_ID, pace, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
        appliedPace = pace;
    }

    /**
     * @return the walking speed change applied now (for tests).
     */
    public double appliedPace()
    {
        return appliedPace;
    }

    // ---- chat ---------------------------------------------------------------------------------------------------------------------------------------------

    /**
     * @return whether the citizen is in a chat now.
     */
    public boolean chatting()
    {
        return chatPartnerId != 0 && now() < chatUntil;
    }

    /**
     * @return whether the citizen leads its chat (said the first line).
     */
    public boolean chatLeader()
    {
        return chatLeader;
    }

    /**
     * @return the citizen id of the chat partner, 0 for none.
     */
    public int chatPartnerId()
    {
        return chatting() ? chatPartnerId : 0;
    }

    /**
     * Whether another citizen may start a chat with this one now: free time (idle or at a leisure site), awake, not chatting, not a child.
     */
    public boolean canJoinChat()
    {
        final IState state = citizen.getCitizenAI().getState();
        return enabled() && !chatting() && now() >= nextChatAt - 200 && !citizen.isBaby() && !citizen.getCitizenSleepHandler().isAsleep() && !lineHeld() && !greetingActive()
                 && (state == CitizenAIState.IDLE || state == EntityAICitizenWander.WanderState.WANDER_AT_LEISURE_SITE) && !hurriesInRain();
    }

    /**
     * Free time: once every {@link #CHAT_TRY_TICKS} the citizen may start a chat with the nearest free citizen of his colony within {@link #CHAT_RANGE} blocks,
     * by the chance and the gap of his trait.
     *
     * @return true when a chat started.
     */
    public boolean tryStartChat()
    {
        final long now = now();
        if (!enabled() || now < nextChatTry || now < nextChatAt || citizen.isBaby() || lineHeld() || hurriesInRain())
        {
            return false;
        }
        nextChatTry = now + CHAT_TRY_TICKS;
        if (citizen.getRandom().nextInt(100) >= trait().chatChance())
        {
            return false;
        }
        final EntityCitizen partner = findChatPartner();
        if (partner == null)
        {
            return false;
        }
        startChat(partner);
        return true;
    }

    /**
     * @return the nearest citizen of the same colony within {@link #CHAT_RANGE} blocks who may chat, null for none.
     */
    @Nullable
    public EntityCitizen findChatPartner()
    {
        final int colonyId = citizen.getCitizenColonyHandler().getColonyId();
        final List<EntityCitizen> near = citizen.level().getEntitiesOfClass(EntityCitizen.class, citizen.getBoundingBox().inflate(CHAT_RANGE),
          other -> other != citizen && other.isAlive() && other.getCitizenColonyHandler().getColonyId() == colonyId && other.getLife().canJoinChat());
        EntityCitizen best = null;
        for (final EntityCitizen other : near)
        {
            if (best == null || other.distanceToSqr(citizen) < best.distanceToSqr(citizen))
            {
                best = other;
            }
        }
        return best;
    }

    /**
     * Starts a chat with a partner: both stop and face each other, this one speaks, the partner answers after {@link #REPLY_AFTER} ticks.
     */
    public void startChat(final EntityCitizen partner)
    {
        final String opener = chatLine(partner);
        joinChat(partner, true, "");
        partner.getLife().joinChat(citizen, false, opener);
        final IColony colony = citizen.getCitizenColonyHandler().getColony();
        if (colony != null)
        {
            ColonyLife.noteChat(colony, citizen.getCivilianID(), partner.getCivilianID());
        }
        say(opener);
    }

    private void joinChat(final EntityCitizen partner, final boolean leader, final String opener)
    {
        final long now = now();
        chatPartnerId = partner.getCivilianID();
        chatPartner = new WeakReference<>(partner);
        chatLeader = leader;
        chatUntil = now + CHAT_TICKS;
        replyAt = leader ? -1 : now + REPLY_AFTER;
        replyTo = opener;
        nextChatAt = now + trait().chatCooldown();
        citizen.getNavigation().stop();
    }

    /**
     * One step of a chat (every 20 ticks): face the partner, answer when it is time. The chat ends when its time is up or the partner left it (work, food, sleep).
     *
     * @return true while the chat goes on.
     */
    public boolean chatStep()
    {
        final EntityCitizen partner = chatPartner.get();
        if (!chatting() || partner == null || !partner.isAlive() || partner.getLife().chatPartnerId() != citizen.getCivilianID()
              || !inFreeTime(partner.getCitizenAI().getState()))
        {
            endChat();
            return false;
        }
        citizen.getNavigation().stop();
        citizen.getLookControl().setLookAt(partner, 30.0F, 30.0F);
        if (replyAt >= 0 && now() >= replyAt && !lineHeld())
        {
            replyAt = -1;
            final String grief = grief();
            say(grief != null && !replyTo.startsWith(ChatTopic.GRIEF.prefix()) ? CHAT_GRIEF_OWN + "|n:" + grief
                  : replyFor(replyTo, 1 + citizen.getRandom().nextInt(CHAT_REPLIES)));
        }
        return true;
    }

    /**
     * @return whether the AI state is free time a chat may run in (idle, at a leisure site, or in the chat itself).
     */
    public static boolean inFreeTime(final IState state)
    {
        return state == CitizenAIState.IDLE || state == EntityAICitizenWander.WanderState.WANDER_AT_LEISURE_SITE || state == EntityAICitizenLife.LifeState.CHAT;
    }

    /**
     * Ends the chat of this citizen.
     */
    public void endChat()
    {
        chatPartnerId = 0;
        chatPartner = new WeakReference<>(null);
        replyAt = -1;
    }

    /**
     * The opening line: a topic that fits the moment ({@link #topic}), the opener a line this pair has not used for the longest ({@link ColonyLife#pickLine}).
     */
    private String chatLine(final EntityCitizen partner)
    {
        final String grief = grief();
        final ICitizenData other = partner.getCitizenData();
        final boolean partnerJob = other != null && other.getJob() != null;
        final Level level = citizen.level();
        final Biome.Precipitation weather = level.isRaining()
                                              ? level.getBiome(citizen.blockPosition()).value().getPrecipitationAt(citizen.blockPosition(), level.getSeaLevel())
                                              : Biome.Precipitation.NONE;
        final ChatTopic topic = topic(grief != null, weather, level.getOverworldClockTime() % 24000L >= EVENING_FROM, happy(), partnerJob,
          citizen.getRandom().nextInt(100));
        final IColony colony = citizen.getCitizenColonyHandler().getColony();
        final int index = colony == null ? 1 + citizen.getRandom().nextInt(topic.lines())
                            : ColonyLife.pickLine(colony, citizen.getCivilianID(), partner.getCivilianID(), topic, now(), citizen.getRandom());
        final String key = topic.prefix() + index;
        return switch (topic)
        {
            case GRIEF -> key + "|n:" + grief;
            case JOB -> key + "|t:" + other.getJob().getJobRegistryEntry().getTranslationKey();
            default -> key;
        };
    }

    /**
     * What a chat opener is about: a friend's death first; now and then the partner's job; else the weather where it rains or snows; often the evening; else
     * the mood. Never a "lovely day" in the rain.
     *
     * @param grieving   whether the speaker grieves.
     * @param weather    what falls at the speaker's place now (ignoring a roof).
     * @param evening    whether it is evening or night.
     * @param happy      whether the speaker is happy.
     * @param partnerJob whether the partner has a job.
     * @param roll       a random number from 0 to 99.
     * @return the topic.
     */
    public static ChatTopic topic(final boolean grieving, final Biome.Precipitation weather, final boolean evening, final boolean happy, final boolean partnerJob,
      final int roll)
    {
        if (grieving)
        {
            return ChatTopic.GRIEF;
        }
        if (partnerJob && roll < 25)
        {
            return ChatTopic.JOB;
        }
        if (weather == Biome.Precipitation.RAIN)
        {
            return ChatTopic.RAIN;
        }
        if (weather == Biome.Precipitation.SNOW)
        {
            return ChatTopic.COLD;
        }
        if (evening && roll < 75)
        {
            return ChatTopic.EVENING;
        }
        return happy ? ChatTopic.HAPPY : ChatTopic.UNHAPPY;
    }

    /**
     * The reply that fits an opener: the opener's key + ".reply." + which, with the opener's argument.
     *
     * @param opener the opener as said ({@link AbstractEntityCitizen#DATA_SPEECH} form).
     * @param which  1 to {@link #CHAT_REPLIES}.
     * @return the reply.
     */
    public static String replyFor(final String opener, final int which)
    {
        final int bar = opener.indexOf('|');
        final String key = bar < 0 ? opener : opener.substring(0, bar);
        return key + ".reply." + which + (bar < 0 ? "" : opener.substring(bar));
    }

    private boolean happy()
    {
        final ICitizenData data = citizen.getCitizenData();
        final IColony colony = citizen.getCitizenColonyHandler().getColony();
        return data == null || colony == null || data.getCitizenHappinessHandler().getHappiness(colony, data) >= 6.0D;
    }

    // ---- morning ------------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Asked by the citizen's AI before it sends him to work or labour: on the first look of a morning (before {@link #MORNING_END}) he greets at a spot nearby;
     * while he greets (at most {@link #MORNING_BUDGET} ticks) this answers true and work waits.
     *
     * @param justWoke whether the citizen comes from sleep or free time (a citizen already at work is never pulled away).
     * @return true while the greeting holds work back.
     */
    public boolean greetingHolds(final boolean justWoke)
    {
        if (!enabled() || citizen.isBaby())
        {
            return false;
        }
        final long clock = citizen.level().getOverworldClockTime();
        final long day = clock / 24000L;
        if (greetingDay != day)
        {
            greetingDay = day;
            greetingStart = now();
            final IColony colony = citizen.getCitizenColonyHandler().getColony();
            gatherSpot = justWoke && clock % 24000L < MORNING_END && colony != null && !colony.getRaiderManager().isRaided()
                           && greetsOn(citizen.getCivilianID(), day, trait()) ? chooseGatherSpot(colony, day) : null;
            greetingDone = gatherSpot == null;
        }
        if (!greetingDone && now() - greetingStart >= MORNING_BUDGET)
        {
            greetingDone = true;
        }
        return !greetingDone;
    }

    /**
     * @return whether a greeting is under way (for the free-time AI).
     */
    public boolean greetingActive()
    {
        return enabled() && !greetingDone && now() - greetingStart < MORNING_BUDGET;
    }

    /**
     * Ends today's greeting.
     */
    public void finishGreeting()
    {
        greetingDone = true;
    }

    /**
     * @return when today's greeting started (game time), for tests.
     */
    public long greetingStart()
    {
        return greetingStart;
    }

    /**
     * @return where the citizen greets, null for nowhere.
     */
    @Nullable
    public BlockPos gatherSpot()
    {
        return gatherSpot;
    }

    /**
     * Whether a citizen greets on a given morning: the chance of his trait, a different set of citizens each day.
     *
     * @param citizenId the citizen id.
     * @param day       the day number.
     * @param trait     his trait.
     * @return true if he greets that morning.
     */
    public static boolean greetsOn(final int citizenId, final long day, final LifeTrait trait)
    {
        long h = citizenId * 0x9E3779B97F4A7C15L ^ (day + 1) * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return Math.floorMod(h, 100L) < trait.greetChance();
    }

    @Nullable
    private BlockPos chooseGatherSpot(final IColony colony, final long day)
    {
        final ICitizenData data = citizen.getCitizenData();
        if (data == null)
        {
            return null;
        }
        final IBuilding townHall = colony.getServerBuildingManager().getTownHall();
        for (final IBuilding spot : new IBuilding[] {townHall, data.getWorkBuilding(), data.getHomeBuilding()})
        {
            if (spot != null && spot.getPosition().distSqr(citizen.blockPosition()) <= GATHER_RANGE * GATHER_RANGE
                  && ColonyLife.claimGreetSpot(colony, spot.getPosition(), day))
            {
                // a few blocks off the hut block, by id, so the greeters of one spot do not stand on each other
                final int id = citizen.getCivilianID();
                return spot.getPosition().offset(Math.floorMod(id * 5, 7) - 3, 0, Math.floorMod(id * 3 + 1, 7) - 3);
            }
        }
        // every spot nearby has its greeters: he greets where he stands
        return citizen.blockPosition();
    }

    /**
     * The morning greeting line, in the voice of the citizen's trait now and then.
     */
    public String morningLine()
    {
        if (citizen.getRandom().nextInt(3) == 0)
        {
            return trait().nameKey() + ".morning";
        }
        return MORNING + (1 + citizen.getRandom().nextInt(MORNING_LINES));
    }

    // ---- shelter ------------------------------------------------------------------------------------------------------------------------------------------

    /**
     * An idle citizen in the rain without a hat goes home to wait it out (when the home is within 48 blocks).
     */
    public boolean wantsShelter()
    {
        if (!enabled() || now() < shelterRetry || citizen.isBaby() || !hurriesInRain())
        {
            return false;
        }
        final ICitizenData data = citizen.getCitizenData();
        final IBuilding home = data == null ? null : data.getHomeBuilding();
        return home != null && !home.isInBuilding(citizen.blockPosition()) && home.getPosition().distSqr(citizen.blockPosition()) <= 48 * 48;
    }

    /**
     * The walk home failed or the rain stopped: no new try for a minute.
     */
    public void shelterBackOff()
    {
        shelterRetry = now() + 1200;
    }
}
