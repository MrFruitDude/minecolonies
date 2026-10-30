package com.minecolonies.core.colony.life;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
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
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Round 4 life: the small things one citizen does that make him look like a person, on top of MineColonies' leisure, mourning and sleep. Not saved; lives with the
 * entity. Server side only. Everything here is bounded: a line lasts {@link #SPEECH_TICKS}, a chat {@link #CHAT_TICKS}, a morning greeting holds work back at most
 * {@link #MORNING_BUDGET} ticks once a day, and nothing slows a citizen at work. The server setting "citizenlifeextras" turns it all off.
 */
public final class CitizenLife
{
    /**
     * How long a line stays above the head, in ticks.
     */
    public static final int SPEECH_TICKS = 70;

    /**
     * The shortest gap between two remarks of a citizen (rain, raid), in ticks. Chats, cheers and greetings are not held back by it.
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
     * The morning: a greeting happens before this time of day (dawn is 0), holds work back at most {@link #MORNING_BUDGET} ticks, and goes to a spot within
     * {@link #GATHER_RANGE} blocks (the town hall, else the citizen's hut, else his home, else where he stands).
     */
    public static final int MORNING_END    = 2000;
    public static final int MORNING_BUDGET = 300;
    public static final int GATHER_RANGE   = 24;
    public static final int GREET_LINGER   = 80;

    /**
     * Rain: a citizen out in the rain without a hat walks this much faster (also at work), and says so at most this often.
     */
    public static final double RAIN_HURRY    = 0.15D;
    public static final int    RAIN_LINE_GAP = 2400;

    /**
     * Grief: a grieving citizen walks this much slower, off work only.
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
    public static final String CHAT_HAPPY      = LINE + "chat.happy.";
    public static final int    CHAT_HAPPY_LINES = 4;
    public static final String CHAT_UNHAPPY    = LINE + "chat.unhappy.";
    public static final int    CHAT_UNHAPPY_LINES = 3;
    public static final String CHAT_JOB        = LINE + "chat.job";
    public static final String CHAT_GRIEF      = LINE + "chat.grief";
    public static final String CHAT_REPLY      = LINE + "chat.reply.";
    public static final int    CHAT_REPLY_LINES = 4;
    public static final String GRIEF_LINE      = LINE + "grief";
    public static final String CHEER_BUILDING  = LINE + "cheer.building.";
    public static final int    CHEER_BUILDING_LINES = 3;
    public static final String RAID_START      = LINE + "raid.start";
    public static final String RAID_END        = LINE + "raid.end.";
    public static final int    RAID_END_LINES  = 2;
    public static final String RAIN            = LINE + "rain.";
    public static final int    RAIN_LINES      = 2;
    public static final String MORNING         = LINE + "morning.";
    public static final int    MORNING_LINES   = 3;

    private final EntityCitizen citizen;

    private long speechUntil = 0;
    private long lastRemark  = Long.MIN_VALUE / 2;

    private String  pendingLine  = null;
    private long    pendingAt    = 0;
    private boolean pendingCheer = false;

    private int                          chatPartnerId = 0;
    private WeakReference<EntityCitizen> chatPartner   = new WeakReference<>(null);
    private boolean                      chatLeader    = false;
    private long                         chatUntil     = 0;
    private long                         replyAt       = -1;
    private long                         nextChatAt    = 0;
    private long                         nextChatTry   = 0;

    private long     greetingDay   = -1;
    private long     greetingStart = 0;
    private boolean  greetingDone  = true;
    private BlockPos gatherSpot    = null;

    private boolean sawRaid      = false;
    private long    lastRainLine = Long.MIN_VALUE / 2;
    private long    shelterRetry = 0;

    private double    appliedPace = 0;
    private LifeTrait trait       = null;
    private long      traitUntil  = 0;

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
     * @return the citizen's trait, read again from the skills once a minute.
     */
    public LifeTrait trait()
    {
        final long now = now();
        if (trait == null || now >= traitUntil)
        {
            final ICitizenData data = citizen.getCitizenData();
            trait = LifeTrait.of(citizen.getCivilianID(), data == null ? null : data.getCitizenSkillHandler());
            traitUntil = now + 1200;
        }
        return trait;
    }

    /**
     * Reads the trait again from the skills on the next ask (after a skill change).
     */
    public void refreshTrait()
    {
        trait = null;
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

    private void cheer(final String raw)
    {
        say(raw);
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
        if (pendingLine != null && now >= pendingAt)
        {
            final String line = pendingLine;
            pendingLine = null;
            if (!asleep)
            {
                if (pendingCheer)
                {
                    cheer(line);
                }
                else
                {
                    say(line);
                }
            }
        }

        final IColony colony = citizen.getCitizenColonyHandler().getColony();
        if (colony != null)
        {
            final boolean raided = colony.getRaiderManager().isRaided();
            if (raided && !sawRaid)
            {
                sawRaid = true;
                if (!asleep)
                {
                    remark(RAID_START);
                }
            }
            else if (!raided && sawRaid)
            {
                // relief: one after the other, spread by id over 3 seconds; a griever only says it
                sawRaid = false;
                schedule(RAID_END + (1 + citizen.getRandom().nextInt(RAID_END_LINES)), 20 + Math.floorMod(citizen.getCivilianID() * 13, 60), grief() == null);
            }
        }

        final boolean hurry = !asleep && hurriesInRain();
        if (hurry && now - lastRainLine >= RAIN_LINE_GAP && remark(RAIN + (1 + citizen.getRandom().nextInt(RAIN_LINES))))
        {
            lastRainLine = now;
        }
        applyPace(pace(hurry, isAtWork(citizen.getCitizenAI().getState()), trait(), grief() != null));
    }

    /**
     * Whether the citizen stands under the open sky in the rain with nothing on his head. Rain as MineColonies' own rain rule sees it (the world's weather, not the
     * biome), so a citizen hurries when the colony's rain rule applies.
     */
    public boolean hurriesInRain()
    {
        return citizen.level().isRaining() && citizen.level().canSeeSky(citizen.blockPosition().above()) && citizen.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
    }

    /**
     * The walking speed change: a rain hurry always, the trait and grief only off work (so work is never slower).
     */
    public static double pace(final boolean rainHurry, final boolean atWork, final LifeTrait trait, final boolean grieving)
    {
        double pace = rainHurry ? RAIN_HURRY : 0;
        if (!atWork)
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
        return enabled() && !chatting() && now() >= nextChatAt - 200 && !citizen.isBaby() && !citizen.getCitizenSleepHandler().isAsleep()
                 && (state == CitizenAIState.IDLE || state == EntityAICitizenWander.WanderState.WANDER_AT_LEISURE_SITE);
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
        if (!enabled() || now < nextChatTry || now < nextChatAt || citizen.isBaby())
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
        joinChat(partner, true);
        partner.getLife().joinChat(citizen, false);
        final IColony colony = citizen.getCitizenColonyHandler().getColony();
        if (colony != null)
        {
            ColonyLife.noteChat(colony, citizen.getCivilianID(), partner.getCivilianID());
        }
        say(chatLine(partner));
    }

    private void joinChat(final EntityCitizen partner, final boolean leader)
    {
        final long now = now();
        chatPartnerId = partner.getCivilianID();
        chatPartner = new WeakReference<>(partner);
        chatLeader = leader;
        chatUntil = now + CHAT_TICKS;
        replyAt = leader ? -1 : now + REPLY_AFTER;
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
        if (replyAt >= 0 && now() >= replyAt)
        {
            replyAt = -1;
            final String grief = grief();
            say(grief != null ? CHAT_GRIEF + "|n:" + grief : CHAT_REPLY + (1 + citizen.getRandom().nextInt(CHAT_REPLY_LINES)));
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
     * The opening line: about a friend he grieves for, else about the partner's job now and then, else by his mood.
     */
    private String chatLine(final EntityCitizen partner)
    {
        final String grief = grief();
        if (grief != null)
        {
            return CHAT_GRIEF + "|n:" + grief;
        }
        final ICitizenData other = partner.getCitizenData();
        if (other != null && other.getJob() != null && citizen.getRandom().nextInt(3) == 0)
        {
            return CHAT_JOB + "|t:" + other.getJob().getJobRegistryEntry().getTranslationKey();
        }
        return happy() ? CHAT_HAPPY + (1 + citizen.getRandom().nextInt(CHAT_HAPPY_LINES)) : CHAT_UNHAPPY + (1 + citizen.getRandom().nextInt(CHAT_UNHAPPY_LINES));
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
            gatherSpot = justWoke && clock % 24000L < MORNING_END ? chooseGatherSpot() : null;
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

    @Nullable
    private BlockPos chooseGatherSpot()
    {
        final ICitizenData data = citizen.getCitizenData();
        final IColony colony = citizen.getCitizenColonyHandler().getColony();
        if (data == null || colony == null)
        {
            return null;
        }
        final IBuilding townHall = colony.getServerBuildingManager().getTownHall();
        for (final IBuilding spot : new IBuilding[] {townHall, data.getWorkBuilding(), data.getHomeBuilding()})
        {
            if (spot != null && spot.getPosition().distSqr(citizen.blockPosition()) <= GATHER_RANGE * GATHER_RANGE)
            {
                return spot.getPosition();
            }
        }
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
