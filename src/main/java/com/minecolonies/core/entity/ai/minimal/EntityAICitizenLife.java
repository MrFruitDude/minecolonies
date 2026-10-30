package com.minecolonies.core.entity.ai.minimal;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.ai.IStateAI;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.TickingTransition;
import com.minecolonies.core.colony.life.CitizenLife;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;

import java.util.List;

/**
 * Round 4 life: free-time moments of a citizen (see {@link CitizenLife}): a short chat with a neighbour, the morning greeting, waiting out the rain at home. It
 * runs only in free time (IDLE, a leisure site) and is registered before the wander AI, so its checks come first there. The citizen AI's own decisions run as
 * events above all of this, so work, food, sleep and danger end any of these moments at once.
 */
public class EntityAICitizenLife implements IStateAI
{
    /**
     * The free-time moments.
     */
    public enum LifeState implements IState
    {
        CHAT,
        MORNING_GATHER,
        SHELTER
    }

    /**
     * Longest walk home from the rain, in ticks.
     */
    private static final int SHELTER_WALK_TICKS = 600;

    private final EntityCitizen citizen;
    private final CitizenLife   life;

    /**
     * Where the citizen goes back to after a chat (IDLE or the leisure site he was at).
     */
    private IState afterChat = CitizenAIState.IDLE;

    private long arrivedAt    = -1;
    private long shelterSince = -1;

    public EntityAICitizenLife(final EntityCitizen citizen)
    {
        this.citizen = citizen;
        this.life = citizen.getLife();

        citizen.getCitizenAI().addTransition(new TickingTransition<>(CitizenAIState.IDLE, this::wantsChat, () -> enterChat(CitizenAIState.IDLE), 20));
        citizen.getCitizenAI().addTransition(new TickingTransition<>(EntityAICitizenWander.WanderState.WANDER_AT_LEISURE_SITE, this::wantsChat,
          () -> enterChat(EntityAICitizenWander.WanderState.WANDER_AT_LEISURE_SITE), 20));
        citizen.getCitizenAI().addTransition(new TickingTransition<>(CitizenAIState.IDLE, life::greetingActive, this::startGathering, 20));
        citizen.getCitizenAI().addTransition(new TickingTransition<>(CitizenAIState.IDLE, life::wantsShelter, this::startShelter, 40));

        citizen.getCitizenAI().addTransition(new TickingTransition<>(LifeState.CHAT, () -> true, this::chat, 20));
        citizen.getCitizenAI().addTransition(new TickingTransition<>(LifeState.MORNING_GATHER, () -> true, this::gather, 20));
        citizen.getCitizenAI().addTransition(new TickingTransition<>(LifeState.SHELTER, () -> true, this::shelter, 20));
    }

    private boolean wantsChat()
    {
        return life.chatting() || life.tryStartChat();
    }

    private IState enterChat(final IState from)
    {
        afterChat = from;
        citizen.getNavigation().stop();
        return LifeState.CHAT;
    }

    private IState chat()
    {
        return life.chatStep() ? LifeState.CHAT : afterChat;
    }

    private IState startGathering()
    {
        arrivedAt = -1;
        return LifeState.MORNING_GATHER;
    }

    /**
     * The morning greeting: walk to the spot, greet the nearest citizen (or the town), linger a little, then free the citizen for work. Bounded by
     * {@link CitizenLife#MORNING_BUDGET}.
     */
    private IState gather()
    {
        if (!life.greetingActive() || life.gatherSpot() == null)
        {
            life.finishGreeting();
            return CitizenAIState.IDLE;
        }
        final long now = citizen.level().getGameTime();
        if (arrivedAt < 0)
        {
            if (!EntityNavigationUtils.walkToPos(citizen, life.gatherSpot(), 4, true))
            {
                return LifeState.MORNING_GATHER;
            }
            arrivedAt = now;
            final List<EntityCitizen> near = citizen.level().getEntitiesOfClass(EntityCitizen.class, citizen.getBoundingBox().inflate(8),
              other -> other != citizen && other.isAlive() && other.getCitizenColonyHandler().getColonyId() == citizen.getCitizenColonyHandler().getColonyId());
            EntityCitizen nearest = null;
            for (final EntityCitizen other : near)
            {
                if (nearest == null || other.distanceToSqr(citizen) < nearest.distanceToSqr(citizen))
                {
                    nearest = other;
                }
            }
            if (nearest != null)
            {
                citizen.getLookControl().setLookAt(nearest, 30.0F, 30.0F);
            }
            citizen.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            life.say(life.morningLine());
        }
        if (now - arrivedAt >= CitizenLife.GREET_LINGER)
        {
            life.finishGreeting();
            return CitizenAIState.IDLE;
        }
        return LifeState.MORNING_GATHER;
    }

    private IState startShelter()
    {
        shelterSince = citizen.level().getGameTime();
        return LifeState.SHELTER;
    }

    /**
     * Out of the rain: walk home, then stay inside while it rains. Gives up after {@link #SHELTER_WALK_TICKS} on the way.
     */
    private IState shelter()
    {
        final ICitizenData data = citizen.getCitizenData();
        final IBuilding home = data == null ? null : data.getHomeBuilding();
        if (!CitizenLife.enabled() || home == null || !citizen.level().isRaining())
        {
            life.shelterBackOff();
            return CitizenAIState.IDLE;
        }
        if (home.isInBuilding(citizen.blockPosition()))
        {
            citizen.getNavigation().stop();
            return LifeState.SHELTER;
        }
        if (citizen.level().getGameTime() - shelterSince > SHELTER_WALK_TICKS)
        {
            life.shelterBackOff();
            return CitizenAIState.IDLE;
        }
        EntityNavigationUtils.walkToBuilding(citizen, home);
        return LifeState.SHELTER;
    }
}
