package com.minecolonies.core.colony.life;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.eventbus.events.colony.buildings.BuildingConstructionModEvent;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Round 4 life: what one colony's citizens feel about colony events (a finished building, a friend's death). Kept per colony and not saved: a grief lasts minutes,
 * a server restart ends it. Server thread only; a colony never touches another colony's citizens.
 */
public final class ColonyLife
{
    /**
     * How long a friend's death weighs on a citizen, in ticks (5 minutes).
     */
    public static final int GRIEF_TICKS = 6000;

    /**
     * Citizens within this many blocks of a finished building cheer, at most {@link #MAX_CHEERERS} of them, the nearest first.
     */
    public static final int CHEER_RANGE  = 20;
    public static final int MAX_CHEERERS = 8;

    /**
     * The last chat partners a citizen remembers as friends.
     */
    public static final int CHAT_FRIENDS = 3;

    /**
     * Round 5: two citizens do not use the same chat opener again within this many ticks (a day), while they have others left.
     */
    public static final int CHAT_REPEAT_WINDOW = 24000;

    /**
     * Round 5: at most this many citizens greet the morning at one building's spot; the others go to their hut, their home or stay where they are.
     */
    public static final int MAX_GREETERS_PER_SPOT = 4;

    private static final Map<IColony, ColonyLife> BY_COLONY = new WeakHashMap<>();

    /**
     * A grief: whom the citizen mourns, and until when.
     */
    public record Grief(String name, long until)
    {
    }

    private final Map<Integer, Grief>         grief       = new HashMap<>();
    private final Map<Integer, Deque<Integer>> chatFriends = new HashMap<>();

    /**
     * The chat openers each pair of citizens used (smaller id in the high half of the key), with when.
     */
    private final Map<Long, Map<String, Long>> pairLines = new HashMap<>();

    /**
     * The morning greeters per building spot, for {@link #greetDay} only.
     */
    private final Map<BlockPos, Integer> greeters = new HashMap<>();
    private long                         greetDay = -1;

    private ColonyLife()
    {
    }

    private static ColonyLife of(final IColony colony)
    {
        return BY_COLONY.computeIfAbsent(colony, c -> new ColonyLife());
    }

    /**
     * Listens for finished buildings (called once at mod setup).
     */
    public static void subscribe()
    {
        IMinecoloniesAPI.getInstance().getEventBus().subscribe(BuildingConstructionModEvent.class, event -> {
            final WorkOrderType type = event.getWorkOrder() == null ? null : event.getWorkOrder().getWorkOrderType();
            if (type == WorkOrderType.BUILD || type == WorkOrderType.UPGRADE)
            {
                onBuildingFinished(event.getBuilding());
            }
        });
    }

    /**
     * @param colony    the colony.
     * @param citizenId the citizen.
     * @param now       the game time.
     * @return the name of the friend the citizen grieves for now, null for none.
     */
    @Nullable
    public static String griefFor(final IColony colony, final int citizenId, final long now)
    {
        final ColonyLife life = BY_COLONY.get(colony);
        if (life == null)
        {
            return null;
        }
        final Grief g = life.grief.get(citizenId);
        if (g == null)
        {
            return null;
        }
        if (now >= g.until())
        {
            life.grief.remove(citizenId);
            return null;
        }
        return g.name();
    }

    /**
     * Two citizens chatted: each remembers the other as a friend (the last {@link #CHAT_FRIENDS}).
     */
    public static void noteChat(final IColony colony, final int a, final int b)
    {
        final ColonyLife life = of(colony);
        remember(life.chatFriends.computeIfAbsent(a, k -> new ArrayDeque<>()), b);
        remember(life.chatFriends.computeIfAbsent(b, k -> new ArrayDeque<>()), a);
    }

    /**
     * Picks the opener of a topic that this pair of citizens has not used within {@link #CHAT_REPEAT_WINDOW} (at random among those), else the one used longest
     * ago, and remembers it.
     *
     * @param colony the colony.
     * @param a      one citizen.
     * @param b      the other.
     * @param topic  the topic.
     * @param now    the game time.
     * @param random the speaker's random.
     * @return the opener's number, 1 to the topic's lines.
     */
    public static int pickLine(final IColony colony, final int a, final int b, final CitizenLife.ChatTopic topic, final long now, final RandomSource random)
    {
        final ColonyLife life = of(colony);
        final Map<String, Long> used = life.pairLines.computeIfAbsent(pairKey(a, b), k -> new HashMap<>());
        used.values().removeIf(at -> now - at >= CHAT_REPEAT_WINDOW);
        final List<Integer> fresh = new ArrayList<>();
        int oldest = 1;
        long oldestAt = Long.MAX_VALUE;
        for (int i = 1; i <= topic.lines(); i++)
        {
            final Long at = used.get(topic.prefix() + i);
            if (at == null)
            {
                fresh.add(i);
            }
            else if (at < oldestAt)
            {
                oldestAt = at;
                oldest = i;
            }
        }
        final int pick = fresh.isEmpty() ? oldest : fresh.get(random.nextInt(fresh.size()));
        used.put(topic.prefix() + pick, now);
        if (life.pairLines.size() > 4096)
        {
            life.pairLines.values().removeIf(lines -> lines.values().stream().allMatch(at -> now - at >= CHAT_REPEAT_WINDOW));
        }
        return pick;
    }

    private static long pairKey(final int a, final int b)
    {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xFFFFFFFFL);
    }

    /**
     * A morning greeter asks for a place at a building's spot: true and counted while the spot has fewer than {@link #MAX_GREETERS_PER_SPOT} greeters today.
     *
     * @param colony the colony.
     * @param spot   the building's position.
     * @param day    the day number.
     * @return true if he may greet there.
     */
    public static boolean claimGreetSpot(final IColony colony, final BlockPos spot, final long day)
    {
        final ColonyLife life = of(colony);
        if (life.greetDay != day)
        {
            life.greetDay = day;
            life.greeters.clear();
        }
        final int count = life.greeters.getOrDefault(spot, 0);
        if (count >= MAX_GREETERS_PER_SPOT)
        {
            return false;
        }
        life.greeters.put(spot, count + 1);
        return true;
    }

    /**
     * @return the first name of a citizen's full name, as friends say it.
     */
    public static String firstName(final String name)
    {
        final int space = name.indexOf(' ');
        return space > 0 ? name.substring(0, space) : name;
    }

    private static void remember(final Deque<Integer> friends, final int id)
    {
        friends.remove(id);
        friends.addFirst(id);
        while (friends.size() > CHAT_FRIENDS)
        {
            friends.removeLast();
        }
    }

    /**
     * Whether a citizen counts as a friend of another: family, a housemate, a co-worker at the same hut, or one of his last chat partners.
     */
    public static boolean areFriends(final IColony colony, final ICitizenData a, final ICitizenData b)
    {
        if (a == b || a.getId() == b.getId())
        {
            return false;
        }
        if (a.isRelatedTo(b) || a.doesLiveWith(b))
        {
            return true;
        }
        if (a.getWorkBuilding() != null && a.getWorkBuilding() == b.getWorkBuilding())
        {
            return true;
        }
        final ColonyLife life = BY_COLONY.get(colony);
        final Deque<Integer> friends = life == null ? null : life.chatFriends.get(a.getId());
        return friends != null && friends.contains(b.getId());
    }

    /**
     * A citizen died: his friends grieve for {@link #GRIEF_TICKS} (a slower walk off work, a sad line, no cheering); the ones nearby in the world say so now.
     *
     * @param colony the colony.
     * @param dead   the citizen who died.
     * @return the ids of the citizens who grieve.
     */
    public static List<Integer> onCitizenDied(final IColony colony, final ICitizenData dead)
    {
        final ColonyLife life = of(colony);
        life.chatFriends.remove(dead.getId());
        life.grief.remove(dead.getId());
        life.pairLines.keySet().removeIf(key -> (int) (key >>> 32) == dead.getId() || (int) (long) key == dead.getId());
        if (!CitizenLife.enabled())
        {
            return List.of();
        }
        final long now = colony.getWorld().getGameTime();
        final List<Integer> grieving = new ArrayList<>();
        for (final ICitizenData other : colony.getCitizenManager().getCitizens())
        {
            if (!areFriends(colony, other, dead))
            {
                continue;
            }
            life.grief.put(other.getId(), new Grief(firstName(dead.getName()), now + GRIEF_TICKS));
            grieving.add(other.getId());
            final Optional<AbstractEntityCitizen> entity = other.getEntity();
            if (entity.isPresent() && entity.get() instanceof EntityCitizen citizen && !other.isAsleep())
            {
                citizen.getLife().schedule(CitizenLife.GRIEF_LINE + "|n:" + firstName(dead.getName()), 20 + grieving.size() * 20, false);
            }
        }
        return grieving;
    }

    /**
     * A building was built or upgraded: the citizens nearby cheer (the nearest {@link #MAX_CHEERERS} within {@link #CHEER_RANGE} blocks, awake, not grieving or
     * mourning), one after the other. Nobody cheers while the colony is raided. Nobody stops working: a cheer is a line, a hop and a few sparkles.
     *
     * @param building the building.
     * @return how many citizens cheer.
     */
    public static int onBuildingFinished(@Nullable final IBuilding building)
    {
        if (building == null || building.getColony() == null || !CitizenLife.enabled() || building.getColony().getRaiderManager().isRaided())
        {
            return 0;
        }
        final IColony colony = building.getColony();
        final BlockPos at = building.getPosition();
        final long now = colony.getWorld().getGameTime();
        final List<EntityCitizen> near = new ArrayList<>();
        for (final ICitizenData data : colony.getCitizenManager().getCitizens())
        {
            final Optional<AbstractEntityCitizen> entity = data.getEntity();
            if (entity.isPresent() && entity.get() instanceof EntityCitizen citizen && !data.isAsleep() && !citizen.isBaby()
                  && citizen.blockPosition().distSqr(at) <= CHEER_RANGE * CHEER_RANGE && griefFor(colony, data.getId(), now) == null
                  && !data.getCitizenMournHandler().isMourning())
            {
                near.add(citizen);
            }
        }
        near.sort(Comparator.comparingDouble(c -> c.blockPosition().distSqr(at)));
        final int count = Math.min(MAX_CHEERERS, near.size());
        for (int i = 0; i < count; i++)
        {
            final EntityCitizen citizen = near.get(i);
            citizen.getLife().schedule(CitizenLife.CHEER_BUILDING + (1 + citizen.getRandom().nextInt(CitizenLife.CHEER_BUILDING_LINES)), i * 10, true);
        }
        return count;
    }
}
