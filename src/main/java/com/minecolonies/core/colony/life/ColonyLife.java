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

    private static final Map<IColony, ColonyLife> BY_COLONY = new WeakHashMap<>();

    /**
     * A grief: whom the citizen mourns, and until when.
     */
    public record Grief(String name, long until)
    {
    }

    private final Map<Integer, Grief>         grief       = new HashMap<>();
    private final Map<Integer, Deque<Integer>> chatFriends = new HashMap<>();

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
            life.grief.put(other.getId(), new Grief(dead.getName(), now + GRIEF_TICKS));
            grieving.add(other.getId());
            final Optional<AbstractEntityCitizen> entity = other.getEntity();
            if (entity.isPresent() && entity.get() instanceof EntityCitizen citizen && !other.isAsleep())
            {
                citizen.getLife().schedule(CitizenLife.GRIEF_LINE + "|n:" + dead.getName(), 20 + grieving.size() * 20, false);
            }
        }
        return grieving;
    }

    /**
     * A building was built or upgraded: the citizens nearby cheer (the nearest {@link #MAX_CHEERERS} within {@link #CHEER_RANGE} blocks, awake, not grieving), one
     * after the other. Nobody stops working: a cheer is a line, a hop and a few sparkles.
     *
     * @param building the building.
     * @return how many citizens cheer.
     */
    public static int onBuildingFinished(@Nullable final IBuilding building)
    {
        if (building == null || building.getColony() == null || !CitizenLife.enabled())
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
                  && citizen.blockPosition().distSqr(at) <= CHEER_RANGE * CHEER_RANGE && griefFor(colony, data.getId(), now) == null)
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
