package com.minecolonies.api.colony.faction;

import com.ldtteam.structurize.util.RotationMirror;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.settings.ISetting;
import com.minecolonies.api.colony.buildings.modules.settings.ISettingKey;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * What a player does in a colony, for the colony of an NPC faction, where there is no player: place a hut, ask for it to be
 * built, upgraded, repaired or removed, hire and fire, change settings. Get it with {@link IColonyManager#getFactionColonyActions()}.
 * <p>
 * <b>Trust boundary.</b> These calls do what the permission-checked network messages of a player do, without the permission
 * checks and without a player. They are plain server-side Java calls, there is no packet, command or event behind them, so
 * they are as trusted as the mod code that calls them (a governor of an AI neighbour, a test). Every call refuses, with
 * {@link FactionActionResult#NOT_FACTION_COLONY}, a colony that is not faction-owned
 * ({@link com.minecolonies.api.colony.permissions.IPermissions#isFactionOwned()}): they can never be used to act in the
 * colony of a player. The caller supplies the resources: a hut is placed from an {@link ItemStack} the caller gives (one is
 * used up), the actions here create no items. Everything that is checked for a player and not about permissions (the claim,
 * the hut's placement rules, the builder rules, research requirements) is checked here too. Call on the server thread.
 * <p>
 * Where MineColonies tells the colony's players what happened, nobody is told: there are none. Listeners of
 * {@code BuildingUpgradeRequestModEvent} see a null player for the requests made here.
 */
public interface IFactionColonyActions
{
    /**
     * Places a hut (and registers its building at level 0, to be built through {@link #requestUpgrade}), as the survival
     * placement of a player does: the hut block is set where the blueprint's primary block is, the building is
     * registered with the structure pack and blueprint, construction tape marks the site. The hut level of the item is
     * ignored; the building starts at level 0.
     *
     * @param colony        a faction-owned colony.
     * @param hutItem       the hut item, one is used up when the hut is placed ({@code hutItem.shrink(1)}); the caller's stack.
     * @param pos           where the primary block of the blueprint goes; inside the colony's claim.
     * @param rotationMirror rotation and mirror of the blueprint.
     * @param packName      the structure pack, e.g. the colony's style pack.
     * @param blueprintPath the blueprint in the pack, e.g. {@code "infrastructure/builder1.blueprint"}.
     * @return {@link FactionActionResult#OK}, or why not. The building is then {@code colony.getServerBuildingManager().getBuilding(pos)}.
     */
    @NotNull
    FactionActionResult placeHut(@NotNull IColony colony, @NotNull ItemStack hutItem, @NotNull BlockPos pos, @NotNull RotationMirror rotationMirror,
      @NotNull String packName, @NotNull String blueprintPath);

    /**
     * Asks for a building to be built (level 0) or upgraded: creates the work order a builder takes. Like
     * {@code IBuilding#requestUpgrade(Player, BlockPos)} for a player, which also accepts a null player now.
     *
     * @param building the building.
     * @param builder  the builder hut that should build it, {@link BlockPos#ZERO} for any builder.
     * @return OK if a work order exists now, ALREADY_REQUESTED if there was one, REFUSED if the request led to none.
     */
    @NotNull
    FactionActionResult requestUpgrade(@NotNull IBuilding building, @NotNull BlockPos builder);

    /**
     * Asks for a building to be repaired.
     *
     * @param building the building (level above 0).
     * @param builder  the builder hut or {@link BlockPos#ZERO}.
     * @return see {@link #requestUpgrade}.
     */
    @NotNull
    FactionActionResult requestRepair(@NotNull IBuilding building, @NotNull BlockPos builder);

    /**
     * Asks for a building to be removed (deconstructed by a builder). A building that is deconstructed already is
     * picked up with {@link #pickUpHut} instead.
     *
     * @param building the building.
     * @param builder  the builder hut or {@link BlockPos#ZERO}.
     * @return see {@link #requestUpgrade}; NOT_POSSIBLE if the building is deconstructed already or can not be removed.
     */
    @NotNull
    FactionActionResult requestRemoval(@NotNull IBuilding building, @NotNull BlockPos builder);

    /**
     * Picks up a deconstructed hut: the building is destroyed, the block removed, and the hut item (with its level and
     * colony) is returned for the caller to keep.
     *
     * @param building the deconstructed building without a parent.
     * @return the hut item, {@link ItemStack#EMPTY} if the building is not a faction colony's, not deconstructed or has a parent.
     */
    @NotNull
    ItemStack pickUpHut(@NotNull IBuilding building);

    /**
     * Hires a citizen into a job of a building, as the hire button of a player does.
     *
     * @param building the building.
     * @param moduleId the id of its job module ({@code IAssignsJob}), {@code module.getProducer().getRuntimeID()}.
     * @param citizen  the citizen.
     * @return OK, NOT_POSSIBLE if the module does not exist or the citizen could not be assigned.
     */
    @NotNull
    FactionActionResult hire(@NotNull IBuilding building, int moduleId, @NotNull ICitizenData citizen);

    /**
     * Fires a citizen from a job of a building.
     *
     * @param building the building.
     * @param moduleId the id of its job module ({@code IAssignsJob}), {@code module.getProducer().getRuntimeID()}.
     * @param citizen  the citizen.
     * @return OK, NOT_POSSIBLE if the module does not exist or the citizen did not have the job.
     */
    @NotNull
    FactionActionResult fire(@NotNull IBuilding building, int moduleId, @NotNull ICitizenData citizen);

    /**
     * Changes a colony setting (the settings of the town hall's colony settings window).
     *
     * @param colony a faction-owned colony.
     * @param key    the setting.
     * @param value  the new value, an instance of the setting's type holding the value.
     * @return OK, NOT_POSSIBLE if the colony has no such setting.
     */
    @NotNull
    FactionActionResult setColonySetting(@NotNull IColony colony, @NotNull ISettingKey<?> key, @NotNull ISetting<?> value);

    /**
     * Changes a setting of a building (the settings tab of its window). Settings that follow a player have none to follow.
     *
     * @param building the building.
     * @param key      the setting.
     * @param value    the new value.
     * @return OK, NOT_POSSIBLE if the building has no settings module or no such setting.
     */
    @NotNull
    FactionActionResult setBuildingSetting(@NotNull IBuilding building, @NotNull ISettingKey<?> key, @NotNull ISetting<?> value);
}
