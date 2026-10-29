package com.minecolonies.api.colony.buildings.modules;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IVisitorData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.Skill;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The tavern's visitor slots, for add-ons that bring a recruit to a colony (free towns: a villager invited from a village). Server side, server thread
 * only: the visitor lists are plain collections.
 * <p>
 * A tavern holds at most {@code 3 * building level} visitors, the same cap its own visitor spawning keeps.
 */
public interface ITavernVisitorModule extends IBuildingModule
{
    /**
     * Longest visitor name a seed may set; longer names are cut.
     */
    int MAX_NAME_LENGTH = 48;

    /**
     * Whether this tavern has room for one more visitor: built (level 1 or more) and fewer than {@code 3 * level} visitors. Only visitors still
     * known to the colony's visitor manager count, so a slot is free as soon as its visitor is recruited, dies or leaves.
     *
     * @return true if {@link #spawnVisitorIfRoom(Seed)} has a slot (it can still return null when no recruit cost is loaded).
     */
    boolean hasVisitorRoom();

    /**
     * Spawns one recruitable visitor, seeded, only when the tavern has room. The visitor appears at a gate house of the colony or at the tavern and costs
     * the normal recruit item. When none of those spots is loaded the visitor exists as data only and its entity spawns when the colony loads; it still
     * gets its tier's boots then.
     *
     * @param seed name, skills, recruit tier and story line of the new visitor.
     * @return the new visitor, or null if there is no room or no recruit cost is loaded (then nothing changed).
     */
    @Nullable
    IVisitorData spawnVisitorIfRoom(@NotNull Seed seed);

    /**
     * The first tavern of the colony with room for one more visitor.
     *
     * @param colony a server colony.
     * @return the tavern module, or null if no tavern has room.
     */
    @Nullable
    static ITavernVisitorModule withRoom(@NotNull final IColony colony)
    {
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            for (final ITavernVisitorModule module : building.getModules(ITavernVisitorModule.class))
            {
                if (module.hasVisitorRoom())
                {
                    return module;
                }
            }
        }
        return null;
    }

    /**
     * What a seeded visitor starts with.
     *
     * @param name        the visitor's name; formatting codes ({@code §x}) and control characters are dropped, runs of spaces become one, and a
     *                    name longer than {@link #MAX_NAME_LENGTH} code points is cut. Null, blank, or a name a citizen or visitor of the colony
     *                    already has keeps the generated one (MineColonies keeps names unique: it tracks parents by name).
     * @param skills      skill levels to set after the random roll; null or empty keeps the roll. Seed skills are absolute but never above what
     *                    the recruit tier allows: each is held to 1 up to the tier's top roll ({@code recruit level - 1}, as the tavern rolls them).
     * @param recruitTier MineColonies' recruit rarity 1..9 (higher: better skills, a dearer recruit item, better boots), held to that range; 0 or less
     *                    rolls it from the tavern level as the tavern does.
     * @param story       the recruit story line shown when the player talks to the visitor; null uses one of the tavern's random stories.
     */
    record Seed(@Nullable String name, @Nullable Map<Skill, Integer> skills, int recruitTier, @Nullable Component story)
    {
        /**
         * A seed that changes nothing: the visitor is the same as one the tavern spawns itself.
         */
        public static final Seed DEFAULT = new Seed(null, null, 0, null);
    }
}
