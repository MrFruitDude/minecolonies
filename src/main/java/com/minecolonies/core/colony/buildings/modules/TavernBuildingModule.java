package com.minecolonies.core.colony.buildings.modules;

import com.ldtteam.blockui.views.BOWindow;
import com.ldtteam.structurize.blockentities.interfaces.IBlueprintDataProviderBE;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.ICivilianData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.colony.IVisitorData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.ModBuildings;
import com.minecolonies.api.colony.buildings.modules.*;
import com.minecolonies.api.colony.buildings.modules.stat.IStat;
import com.minecolonies.api.colony.interactionhandling.ChatPriority;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.sounds.TavernSounds;
import com.minecolonies.api.util.MathUtils;
import com.minecolonies.api.util.StatsUtil;
import com.minecolonies.core.client.gui.huts.WindowHutLiving;
import com.minecolonies.core.colony.VisitorData;
import com.minecolonies.core.colony.buildings.views.LivingBuildingView;
import com.minecolonies.core.colony.interactionhandling.RecruitmentInteraction;
import com.minecolonies.core.datalistener.CustomVisitorListener;
import com.minecolonies.core.datalistener.RecruitmentItemsListener;
import com.minecolonies.core.network.messages.client.colony.PlayMusicAtPosMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.TickRateConstants.MAX_TICKRATE;
import static com.minecolonies.api.util.constant.CitizenConstants.MAX_CITIZEN_LEVEL;
import static com.minecolonies.api.util.constant.Constants.MAX_STORY;
import static com.minecolonies.api.util.constant.Constants.TAG_COMPOUND;
import static com.minecolonies.api.util.constant.NbtTagConstants.TAG_VISITORS;
import static com.minecolonies.api.util.constant.NbtTagConstants.TAG_WORK;
import static com.minecolonies.api.util.constant.SchematicTagConstants.*;
import static com.minecolonies.api.util.constant.StatisticsConstants.NEW_VISITORS;
/**
 * Tavern building for the colony. Houses 4 citizens Plays a tavern theme on entering Spawns/allows citizen recruitment Spawns trader/quest npcs
 */
public class TavernBuildingModule extends AbstractBuildingModule
  implements IDefinesCoreBuildingStatsModule, IBuildingEventsModule, IPersistentModule, ITickingModule, ITavernVisitorModule
{
    /**
     * Schematic name
     */
    public static final String TAG_VISITOR_ID = "visitor";

    /**
     * Music interval
     */
    private static final int    TWENTY_MINUTES  = 20 * 60 * 20;
    private static final String TAG_NOVISITTIME = "novisit";

    /**
     * Cooldown for the music, to not play it too much/not overlap with itself
     */
    private int musicCooldown = 0;

    /**
     * List of additional citizens
     */
    private final List<Integer> externalCitizens = new ArrayList<>();

    /**
     * List of sitting positions for this building
     */
    private final List<BlockPos> sitPositions = new ArrayList<>();

    /**
     * List of work positions for this building
     */
    private final List<BlockPos> workPositions = new ArrayList<>();

    private boolean initTags = false;

    /**
     * Penalty for not spawning visitors after a death
     */
    private int noVisitorTime = 10000;

    @Override
    public IStat<Integer> getMaxInhabitants()
    {
        if (building.getBuildingLevel() <= 0)
        {
            return (prev) -> 0;
        }

        return (prev) -> 4;
    }

    @Override
    public void onPlayerEnterBuilding(final Player player)
    {
        if (musicCooldown <= 0 && building.getBuildingLevel() > 0 && !building.getColony().isDay())
        {
            int count = 0;
            BlockPos avg = BlockPos.ZERO;
            for (final Integer id : externalCitizens)
            {
                final IVisitorData data = building.getColony().getVisitorManager().getVisitor(id);
                if (data != null)
                {
                    if (!data.getSittingPosition().equals(BlockPos.ZERO))
                    {
                        count++;
                        avg = avg.offset(data.getSittingPosition());
                    }
                }
            }

            if (count < 2)
            {
                return;
            }

            avg = new BlockPos(avg.getX() / count, avg.getY() / count, avg.getZ() / count);
            final PlayMusicAtPosMessage message = new PlayMusicAtPosMessage(TavernSounds.tavernTheme, avg, building.getColony().getWorld(), 0.7f, 1.0f);
            message.sendToPlayer(building.getColony().getPackageManager().getCloseSubscribers());
            musicCooldown = TWENTY_MINUTES;
        }
    }

    @Override
    public void onColonyTick(@NotNull final IColony colony)
    {
        if (musicCooldown > 0)
        {
            musicCooldown -= MAX_TICKRATE;
        }

        externalCitizens.removeIf(id -> colony.getVisitorManager().getVisitor(id) == null);

        if (noVisitorTime > 0)
        {
            noVisitorTime -= 500;
        }

        if (noVisitorTime <= 0 && hasVisitorRoom())
        {
            spawnVisitorInternal();
            noVisitorTime = colony.getWorld().getRandom().nextInt(3000)
                + (6000 / building.getBuildingLevel())
                * colony.getCitizenManager().getCurrentCitizenCount()
                / colony.getCitizenManager().getMaxCitizens();
        }
    }

    @Override
    public void onUpgradeComplete(final int newlevel)
    {
        initTags = false;
    }

    /**
     * Spawns a visitor specifically for the tavern logic.
     */
    private void spawnVisitorInternal()
    {
        final IVisitorData visitorData = spawnVisitor();

        if (visitorData != null && !CustomVisitorListener.chanceCustomVisitors(visitorData))
        {
            visitorData.triggerInteraction(new RecruitmentInteraction(Component.translatable(
                "com.minecolonies.coremod.gui.chat.recruitstory" + (building.getColony().getWorld().getRandom().nextInt(MAX_STORY) + 1), visitorData.getName().split(" ")[0]),
                ChatPriority.IMPORTANT));
        }
    }

    @Override
    public boolean hasVisitorRoom()
    {
        if (building.getBuildingLevel() <= 0)
        {
            return false;
        }
        int present = 0;
        for (final Integer id : externalCitizens)
        {
            if (building.getColony().getVisitorManager().getVisitor(id) != null)
            {
                present++;
            }
        }
        return present < 3 * building.getBuildingLevel();
    }

    @Override
    @Nullable
    public IVisitorData spawnVisitorIfRoom(@NotNull final Seed seed)
    {
        externalCitizens.removeIf(id -> building.getColony().getVisitorManager().getVisitor(id) == null);
        if (!hasVisitorRoom())
        {
            return null;
        }

        final IVisitorData visitorData = spawnVisitor(seed);
        if (visitorData != null)
        {
            final Component story = seed.story() != null ? seed.story() : Component.translatable(
              "com.minecolonies.coremod.gui.chat.recruitstory" + (building.getColony().getWorld().getRandom().nextInt(MAX_STORY) + 1), visitorData.getName().split(" ")[0]);
            visitorData.triggerInteraction(new RecruitmentInteraction(story, ChatPriority.IMPORTANT));
        }
        return visitorData;
    }

    /**
     * Spawns a visitor citizen that can be recruited. Does not check the tavern's visitor cap (see {@link #spawnVisitorIfRoom(Seed)}).
     */
    @Nullable
    public IVisitorData spawnVisitor()
    {
        return spawnVisitor(Seed.DEFAULT);
    }

    /**
     * Spawns a seeded visitor citizen that can be recruited, without checking the cap.
     *
     * @param seed name, skills and recruit tier.
     */
    @Nullable
    private IVisitorData spawnVisitor(@NotNull final Seed seed)
    {
        final RecruitmentItemsListener.RecruitCost cost = seed.recruitTier() > 0
          ? RecruitmentItemsListener.getRandomRecruitCostAtTier(seed.recruitTier())
          : RecruitmentItemsListener.getRandomRecruitCost(building.getBuildingLevel());
        if (cost == null)
        {
            return null;
        }

        final IVisitorData newCitizen = (IVisitorData) building.getColony().getVisitorManager().createAndRegisterCivilianData();
        newCitizen.setBedPos(building.getPosition());
        newCitizen.setHomeBuilding(building);
        newCitizen.getCitizenSkillHandler().init(cost.recruitLevel());
        final String name = sanitizeVisitorName(seed.name());
        if (!name.isEmpty() && !isNameTaken(building.getColony(), name, newCitizen.getId()))
        {
            newCitizen.setName(name);
        }
        if (seed.skills() != null)
        {
            // The tier's top roll: CitizenSkillHandler.init(cap) rolls 1..cap-1.
            final int skillCap = Math.max(1, Math.min(MAX_CITIZEN_LEVEL, cost.recruitLevel() - 1));
            for (final Map.Entry<Skill, Integer> skill : seed.skills().entrySet())
            {
                if (skill.getKey() != null && skill.getValue() != null && newCitizen.getCitizenSkillHandler().getSkills().containsKey(skill.getKey()))
                {
                    newCitizen.getCitizenSkillHandler().getSkills().get(skill.getKey()).setLevel(Math.max(1, Math.min(skillCap, skill.getValue())));
                }
            }
        }
        if (newCitizen instanceof VisitorData visitor)
        {
            // Kept on the data, so an entity spawned later (colony not loaded now) wears them too.
            visitor.setTierBoots(cost.boots());
        }

        final ItemStack recruitCostItem = cost.recruitItem().copy();
        recruitCostItem.setCount(Math.min(cost.recruitItem().getMaxStackSize(), recruitCostItem.getCount() + MathUtils.RANDOM.nextInt(3)));
        newCitizen.setRecruitCosts(recruitCostItem);

        List<BlockPos> spawnPositions = new ArrayList<>();
        final BlockPos gatePos = building.getColony().getServerBuildingManager().getRandomBuilding(b -> b.getBuildingType() == ModBuildings.gateHouse.get());
        if (gatePos != null)
        {
            final IBuilding gateHouseBuilding = building.getColony().getServerBuildingManager().getBuilding(gatePos);
            if (gateHouseBuilding != null)
            {
                final List<BlockPos> gatePositions = gateHouseBuilding.getLocationsFromTag(TAG_GATE);
                if (gatePositions.isEmpty())
                {
                    spawnPositions.add(gatePos);
                }
                else
                {
                    spawnPositions.add(gatePositions.get(MathUtils.RANDOM.nextInt(gatePositions.size())));
                }
            }
        }

        spawnPositions.add(building.getPosition());
        building.getColony().getVisitorManager().spawnOrCreateCivilian(newCitizen, building.getColony().getWorld(), spawnPositions, true);

        StatsUtil.trackStat(building, NEW_VISITORS, 1);

        externalCitizens.add(newCitizen.getId());
        return newCitizen;
    }

    /**
     * A seeded visitor name made safe to show: formatting codes ({@code §x}) and control characters dropped, runs of whitespace made one space,
     * trimmed, and cut to {@link ITavernVisitorModule#MAX_NAME_LENGTH} code points.
     *
     * @param raw the requested name, may be null.
     * @return the clean name, empty if nothing is left.
     */
    @NotNull
    public static String sanitizeVisitorName(@Nullable final String raw)
    {
        if (raw == null)
        {
            return "";
        }
        final StringBuilder clean = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < raw.length(); )
        {
            final int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\u00a7')
            {
                // A formatting code: drop it and the code character after it.
                if (i < raw.length())
                {
                    i += Character.charCount(raw.codePointAt(i));
                }
                continue;
            }
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp))
            {
                space = clean.length() > 0;
                continue;
            }
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT)
            {
                continue;
            }
            if (space)
            {
                clean.append(' ');
                space = false;
            }
            clean.appendCodePoint(cp);
        }
        String name = clean.toString();
        if (name.codePointCount(0, name.length()) > MAX_NAME_LENGTH)
        {
            name = name.substring(0, name.offsetByCodePoints(0, MAX_NAME_LENGTH)).strip();
        }
        return name;
    }

    /**
     * Whether a citizen or another visitor of the colony already has this name (MineColonies keeps names unique, as it tracks parents by name).
     */
    private static boolean isNameTaken(@NotNull final IColony colony, @NotNull final String name, final int selfId)
    {
        for (final ICitizenData citizen : colony.getCitizenManager().getCitizens())
        {
            if (citizen != null && name.equals(citizen.getName()))
            {
                return true;
            }
        }
        for (final ICivilianData visitor : colony.getVisitorManager().getCivilianDataMap().values())
        {
            if (visitor != null && visitor.getId() != selfId && name.equals(visitor.getName()))
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public void serializeNBT(@NotNull final HolderLookup.Provider provider, final CompoundTag nbt)
    {
        final ListTag visitorlist = new ListTag();
        for (final Integer id : externalCitizens)
        {
            CompoundTag visitorCompound = new CompoundTag();
            visitorCompound.putInt(TAG_VISITOR_ID, id);
            visitorlist.add(visitorCompound);
        }

        nbt.put(TAG_VISITORS, visitorlist);
        nbt.putInt(TAG_NOVISITTIME, noVisitorTime);
    }

    @Override
    public void deserializeNBT(@NotNull final HolderLookup.Provider provider, final CompoundTag nbt)
    {
        final ListTag visitorlist = nbt.getListOrEmpty(TAG_VISITORS);
        for (final Tag data : visitorlist)
        {
            final int id = ((CompoundTag) data).getIntOr(TAG_VISITOR_ID, 0);
            final ICitizenData citizenData = building.getColony().getVisitorManager().getCivilian(id);
            if (citizenData != null)
            {
                externalCitizens.add(id);
                citizenData.setHomeBuilding(building);
            }
        }
        noVisitorTime = nbt.getIntOr(TAG_NOVISITTIME, 0);
    }

    /**
     * Gets a free sitting position
     *
     * @return a blockpos to sit at
     */
    public BlockPos getFreeSitPosition()
    {
        final List<BlockPos> positions = new ArrayList<>(getSitPositions());

        if (positions.isEmpty())
        {
            return null;
        }

        for (final Integer id : externalCitizens)
        {
            final IVisitorData data = building.getColony().getVisitorManager().getVisitor(id);
            if (data != null)
            {
                positions.remove(data.getSittingPosition());
            }
        }

        if (!positions.isEmpty())
        {
            return positions.get(building.getColony().getWorld().getRandom().nextInt(positions.size()));
        }

        return null;
    }

    @Override
    public void onDestroyed()
    {
        for (final Integer id : externalCitizens)
        {
            building.getColony().getVisitorManager().removeCivilian(building.getColony().getVisitorManager().getVisitor(id));
        }
    }

    /**
     * Gets the assigned visitor ids
     *
     * @return list of ids
     */
    public List<Integer> getExternalCitizens()
    {
        return externalCitizens;
    }

    /**
     * Get a random work position
     *
     * @return a random work pos
     */
    public BlockPos getWorkPos()
    {
        if (!getWorkPositions().isEmpty())
        {
            return workPositions.get(building.getColony().getWorld().getRandom().nextInt(workPositions.size()));
        }
        return null;
    }

    /**
     * Get the list of sitting positions
     *
     * @return sit pos list
     */
    private List<BlockPos> getSitPositions()
    {
        initTagPositions();
        return sitPositions;
    }

    /**
     * Get the list of work positions
     *
     * @return work pos list
     */
    private List<BlockPos> getWorkPositions()
    {
        initTagPositions();
        return workPositions;
    }

    /**
     * Initializes the sitting and work position lists
     */
    public void initTagPositions()
    {
        if (initTags)
        {
            return;
        }

        final IBlueprintDataProviderBE te = building.getTileEntity();
        if (te != null)
        {
            initTags = true;
            for (final Map.Entry<BlockPos, List<String>> entry : te.getWorldTagPosMap().entrySet())
            {
                if (entry.getValue().contains(TAG_SITTING))
                {
                    sitPositions.add(entry.getKey());
                }
                if (entry.getValue().contains(TAG_SIT_IN))
                {
                    sitPositions.add(entry.getKey());
                }
                if (entry.getValue().contains(TAG_SIT_OUT))
                {
                    sitPositions.add(entry.getKey());
                }

                if (entry.getValue().contains(TAG_WORK))
                {
                    workPositions.add(entry.getKey());
                }
            }
        }
    }

    /**
     * Removes the given citizen id
     *
     * @param id to remove
     */
    public boolean removeCitizen(final Integer id)
    {
        externalCitizens.remove(id);
        return false;
    }

    /**
     * Sets the delay time before a new visitor spawns
     *
     * @param noVisitorTime time in ticks
     */
    public void setNoVisitorTime(final int noVisitorTime)
    {
        this.noVisitorTime = noVisitorTime;
    }

    /**
     * ClientSide representation of the building.
     */
    public static class View extends LivingBuildingView
    {
        /**
         * Instantiates the view of the building.
         *
         * @param c the colonyView.
         * @param l the location of the block.
         */
        public View(final IColonyView c, final BlockPos l)
        {
            super(c, l);
        }

        @NotNull
        @Override
        public BOWindow getWindow()
        {
            return new WindowHutLiving(this);
        }
    }
}
