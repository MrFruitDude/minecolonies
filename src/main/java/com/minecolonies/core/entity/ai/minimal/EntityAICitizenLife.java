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
import com.ldtteam.structurize.api.util.Tuple;
import com.minecolonies.api.util.constant.SchematicTagConstants;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;

import java.util.ArrayList;
import java.util.List;

/**
 * Round 4 life: free-time moments of a citizen (see {@link CitizenLife}): a short chat with a neighbour, the morning greeting, waiting out the rain at home
 * (where housemates may chat, round 6). It runs only in free time (IDLE, a leisure site, the shelter) and is registered before the wander AI, so its checks
 * come first there. The citizen AI's own decisions run as
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

    private long     lineAt       = -1;
    private long     shelterSince = -1;
    private BlockPos shelterSpot  = null;

    public EntityAICitizenLife(final EntityCitizen citizen)
    {
        this.citizen = citizen;
        this.life = citizen.getLife();

        citizen.getCitizenAI().addTransition(new TickingTransition<>(CitizenAIState.IDLE, this::wantsChat, () -> enterChat(CitizenAIState.IDLE), 20));
        citizen.getCitizenAI().addTransition(new TickingTransition<>(EntityAICitizenWander.WanderState.WANDER_AT_LEISURE_SITE, this::wantsChat,
          () -> enterChat(EntityAICitizenWander.WanderState.WANDER_AT_LEISURE_SITE), 20));
        citizen.getCitizenAI().addTransition(new TickingTransition<>(CitizenAIState.IDLE, life::greetingActive, this::startGathering, 20));
        citizen.getCitizenAI().addTransition(new TickingTransition<>(CitizenAIState.IDLE, life::wantsShelter, this::startShelter, 40));
        // round 6: citizens sheltering together may chat (before the shelter step, which always runs); the chat ends back in the shelter
        citizen.getCitizenAI().addTransition(new TickingTransition<>(LifeState.SHELTER, this::wantsShelterChat, () -> enterChat(LifeState.SHELTER), 20));

        citizen.getCitizenAI().addTransition(new TickingTransition<>(LifeState.CHAT, () -> true, this::chat, 20));
        citizen.getCitizenAI().addTransition(new TickingTransition<>(LifeState.MORNING_GATHER, () -> true, this::gather, 20));
        citizen.getCitizenAI().addTransition(new TickingTransition<>(LifeState.SHELTER, () -> true, this::shelter, 20));
    }

    private boolean wantsChat()
    {
        // a citizen who would rather get out of the rain or is off to greet the morning does not stop for a chat
        return life.chatting() || (!life.greetingActive() && !life.wantsShelter() && life.tryStartChat());
    }

    private boolean wantsShelterChat()
    {
        return life.shelteredAtHome() && (life.chatting() || (!life.greetingActive() && life.tryStartChat()));
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
        lineAt = -1;
        return LifeState.MORNING_GATHER;
    }

    /**
     * The morning greeting: walk to the spot and greet the nearest citizen on arrival, close to the spot, or at the latest {@link CitizenLife#GREET_LINGER} ticks
     * before the budget ends; linger a little, then free the citizen for work. Round 6: with nobody awake within {@link CitizenLife#GREET_HEAR_RANGE} blocks
     * there is no line and no linger, he goes on with his day. Round 7: while he waits for a sleeping neighbour to wake he stands where he is. Bounded by
     * {@link CitizenLife#MORNING_BUDGET}.
     */
    private IState gather()
    {
        final BlockPos spot = life.gatherSpot();
        if (spot == null && life.awaitingListener())
        {
            citizen.getNavigation().stop();
            return LifeState.MORNING_GATHER;
        }
        if (!life.greetingActive() || spot == null)
        {
            life.finishGreeting();
            return CitizenAIState.IDLE;
        }
        final long now = citizen.level().getGameTime();
        if (lineAt < 0)
        {
            final boolean arrived = EntityNavigationUtils.walkToPos(citizen, spot, 3, true);
            final boolean close = citizen.blockPosition().distSqr(spot) <= CitizenLife.GREET_TALK_RANGE * CitizenLife.GREET_TALK_RANGE;
            final boolean late = now - life.greetingStart() >= CitizenLife.MORNING_BUDGET - CitizenLife.GREET_LINGER - 20;
            if (!arrived && !close && !late)
            {
                return LifeState.MORNING_GATHER;
            }
            citizen.getNavigation().stop();
            final List<EntityCitizen> near = citizen.level().getEntitiesOfClass(EntityCitizen.class, citizen.getBoundingBox().inflate(CitizenLife.GREET_HEAR_RANGE),
              other -> other != citizen && other.isAlive() && other.getCitizenColonyHandler().getColonyId() == citizen.getCitizenColonyHandler().getColonyId()
                         && !other.getCitizenSleepHandler().isAsleep() && other.distanceToSqr(citizen) <= CitizenLife.GREET_HEAR_RANGE * CitizenLife.GREET_HEAR_RANGE);
            EntityCitizen nearest = null;
            for (final EntityCitizen other : near)
            {
                if (nearest == null || other.distanceToSqr(citizen) < nearest.distanceToSqr(citizen))
                {
                    nearest = other;
                }
            }
            if (nearest == null)
            {
                life.skipGreeting();
                return CitizenAIState.IDLE;
            }
            lineAt = now;
            citizen.getLookControl().setLookAt(nearest, 30.0F, 30.0F);
            citizen.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            life.say(life.morningLine());
        }
        if (now - lineAt >= CitizenLife.GREET_LINGER)
        {
            life.finishGreeting();
            return CitizenAIState.IDLE;
        }
        return LifeState.MORNING_GATHER;
    }

    private IState startShelter()
    {
        shelterSince = citizen.level().getGameTime();
        shelterSpot = null;
        return LifeState.SHELTER;
    }

    /**
     * Out of the rain: walk home and into the house (a stand or sit spot of the home's blueprint, else a bed, else the middle of the house), not just to its
     * door, then stay inside while it rains there. Gives up after {@link #SHELTER_WALK_TICKS} on the way.
     */
    private IState shelter()
    {
        final ICitizenData data = citizen.getCitizenData();
        final IBuilding home = data == null ? null : data.getHomeBuilding();
        if (!CitizenLife.enabled() || home == null || !CitizenLife.rainsAt(citizen.level(), home.getPosition()))
        {
            life.shelterBackOff();
            return CitizenAIState.IDLE;
        }
        final boolean inside = home.isInBuilding(citizen.blockPosition());
        if (inside && !citizen.level().canSeeSky(citizen.blockPosition().above()))
        {
            citizen.getNavigation().stop();
            return LifeState.SHELTER;
        }
        if (shelterSpot == null)
        {
            shelterSpot = insideSpot(home, citizen.getCivilianID());
        }
        if (EntityNavigationUtils.walkToPosInBuilding(citizen, shelterSpot, home, 2) && inside)
        {
            // at his place inside the walls, even where the sky is open (a courtyard): he stays
            citizen.getNavigation().stop();
            return LifeState.SHELTER;
        }
        if (citizen.level().getGameTime() - shelterSince > SHELTER_WALK_TICKS)
        {
            life.shelterBackOff();
            return CitizenAIState.IDLE;
        }
        return LifeState.SHELTER;
    }

    /**
     * A place inside a house to wait out the rain: a stand or sit spot of its blueprint, else one of its beds, else its middle at the hut block's height; picked
     * by citizen id so housemates spread.
     *
     * @param home      the house.
     * @param citizenId the citizen id.
     * @return the place, always within the house's corners.
     */
    public static BlockPos insideSpot(final IBuilding home, final int citizenId)
    {
        final List<BlockPos> spots = new ArrayList<>();
        for (final String tag : new String[] {SchematicTagConstants.TAG_STAND_IN, SchematicTagConstants.TAG_SIT_IN})
        {
            for (final BlockPos pos : home.getLocationsFromTag(tag))
            {
                if (home.isInBuilding(pos))
                {
                    spots.add(pos);
                }
            }
        }
        if (spots.isEmpty() && home.hasModule(BuildingModules.BED))
        {
            for (final BlockPos pos : home.getModule(BuildingModules.BED).getRegisteredBlocks())
            {
                if (home.isInBuilding(pos))
                {
                    spots.add(pos);
                }
            }
        }
        if (!spots.isEmpty())
        {
            return spots.get(Math.floorMod(citizenId, spots.size()));
        }
        final Tuple<BlockPos, BlockPos> corners = home.getCorners();
        return new BlockPos((corners.getA().getX() + corners.getB().getX()) / 2, home.getPosition().getY(), (corners.getA().getZ() + corners.getB().getZ()) / 2);
    }
}
