package com.minecolonies.core.colony;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.storage.StructurePacks;
import com.ldtteam.structurize.util.RotationMirror;
import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.IRSComponent;
import com.minecolonies.api.colony.buildings.modules.IAssignsJob;
import com.minecolonies.api.colony.buildings.modules.ICommonSettingsModule;
import com.minecolonies.api.colony.buildings.modules.ISettingsModule;
import com.minecolonies.api.colony.buildings.modules.settings.ISetting;
import com.minecolonies.api.colony.buildings.modules.settings.ISettingKey;
import com.minecolonies.api.colony.faction.FactionActionResult;
import com.minecolonies.api.colony.faction.IFactionColonyActions;
import com.minecolonies.api.items.component.ColonyId;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import com.minecolonies.core.entity.ai.workers.util.ConstructionTapeHelper;
import com.minecolonies.core.placementhandlers.main.SurvivalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

/**
 * The server-side actions of a faction colony's governor; see {@link IFactionColonyActions} for the contract and the
 * trust boundary. Every method starts by checking that the colony is faction-owned.
 */
public final class FactionColonyActions implements IFactionColonyActions
{
    public static final FactionColonyActions INSTANCE = new FactionColonyActions();

    private FactionColonyActions()
    {
    }

    private static boolean isFaction(final IColony colony)
    {
        return colony != null && colony.getPermissions().isFactionOwned() && colony.getWorld() instanceof ServerLevel;
    }

    /**
     * @return true if the building is registered in a faction-owned colony.
     */
    private static boolean isFactionBuilding(final IBuilding building)
    {
        return building != null
                 && isFaction(building.getColony())
                 && building.getColony().getServerBuildingManager().getBuilding(building.getPosition()) == building;
    }

    @Override
    @NotNull
    public FactionActionResult placeHut(
      @NotNull final IColony colony,
      @NotNull final ItemStack hutItem,
      @NotNull final BlockPos pos,
      @NotNull final RotationMirror rotationMirror,
      @NotNull final String packName,
      @NotNull final String blueprintPath)
    {
        if (!isFaction(colony))
        {
            return FactionActionResult.NOT_FACTION_COLONY;
        }
        if (hutItem.isEmpty() || !(hutItem.getItem() instanceof final BlockItem blockItem) || !(blockItem.getBlock() instanceof final AbstractBlockHut<?> hutBlock))
        {
            return FactionActionResult.NO_HUT_ITEM;
        }
        final ColonyId bound = ColonyId.readFromItemStack(hutItem);
        if (bound.hasColonyId() && (bound.id() != colony.getID() || !bound.dimension().equals(colony.getDimension())))
        {
            return FactionActionResult.WRONG_COLONY;
        }

        final ServerLevel world = (ServerLevel) colony.getWorld();
        if (!colony.isCoordInColony(world, pos))
        {
            return FactionActionResult.OUTSIDE_COLONY;
        }
        if (!StructurePacks.hasPack(packName) || blueprintPath.startsWith("scans/"))
        {
            return FactionActionResult.BLUEPRINT_UNAVAILABLE;
        }
        final Blueprint blueprint = StructurePacks.getBlueprint(packName, blueprintPath);
        if (blueprint == null)
        {
            return FactionActionResult.BLUEPRINT_UNAVAILABLE;
        }
        blueprint.setRotationMirror(rotationMirror, world);
        final BlockState anchor = blueprint.getBlockState(blueprint.getPrimaryBlockOffset());
        if (anchor.getBlock() != hutBlock)
        {
            return FactionActionResult.BLUEPRINT_UNAVAILABLE;
        }
        if (!SurvivalHandler.isBlueprintInColony(blueprint, colony, pos))
        {
            return FactionActionResult.OUTSIDE_COLONY;
        }
        if (!hutBlock.canPlaceAt(world, pos, null))
        {
            return FactionActionResult.PLACEMENT_REFUSED;
        }

        world.destroyBlock(pos, true);
        world.setBlockAndUpdate(pos, anchor);
        hutBlock.onBlockPlacedByBuildTool(world, pos, anchor, null, null, rotationMirror, packName, blueprintPath);

        final IBuilding building = colony.getServerBuildingManager().getBuilding(pos);
        if (building == null)
        {
            Log.getLogger().error("Faction hut placement at {} did not register a building", pos, new Exception());
            return FactionActionResult.PLACEMENT_REFUSED;
        }
        if (building.getTileEntity() != null)
        {
            building.getTileEntity().setColony(colony);
        }
        building.setStructurePack(packName);
        building.setBlueprintPath(blueprintPath);
        building.setBuildingLevel(0);
        if (!(building instanceof IRSComponent))
        {
            ConstructionTapeHelper.placeConstructionTape(building.getCorners(), colony);
        }
        building.setRotationMirror(rotationMirror);

        hutItem.shrink(1);
        return FactionActionResult.OK;
    }

    private static boolean hasWorkOrder(final IColony colony, final IBuilding building)
    {
        for (final WorkOrderBuilding order : colony.getWorkManager().getWorkOrdersOfType(WorkOrderBuilding.class))
        {
            if (order.getLocation().equals(building.getID()))
            {
                return true;
            }
        }
        return false;
    }

    private interface Request
    {
        void run(IBuilding building);
    }

    private static FactionActionResult request(final IBuilding building, final Request request)
    {
        if (!isFactionBuilding(building))
        {
            return building != null && !isFaction(building.getColony()) ? FactionActionResult.NOT_FACTION_COLONY : FactionActionResult.NO_SUCH_BUILDING;
        }
        final IColony colony = building.getColony();
        if (hasWorkOrder(colony, building))
        {
            return FactionActionResult.ALREADY_REQUESTED;
        }
        request.run(building);
        return hasWorkOrder(colony, building) ? FactionActionResult.OK : FactionActionResult.REFUSED;
    }

    @Override
    @NotNull
    public FactionActionResult requestUpgrade(@NotNull final IBuilding building, @NotNull final BlockPos builder)
    {
        return request(building, b -> b.requestUpgrade(null, builder));
    }

    @Override
    @NotNull
    public FactionActionResult requestRepair(@NotNull final IBuilding building, @NotNull final BlockPos builder)
    {
        return request(building, b -> b.requestRepair(builder));
    }

    @Override
    @NotNull
    public FactionActionResult requestRemoval(@NotNull final IBuilding building, @NotNull final BlockPos builder)
    {
        if (isFactionBuilding(building) && building.isDeconstructed())
        {
            return FactionActionResult.NOT_POSSIBLE;
        }
        return request(building, b -> b.requestRemoval(null, builder));
    }

    @Override
    @NotNull
    public ItemStack pickUpHut(@NotNull final IBuilding building)
    {
        if (!isFactionBuilding(building) || !building.isDeconstructed() || building.hasParent())
        {
            return ItemStack.EMPTY;
        }
        final IColony colony = building.getColony();
        final ItemStack stack = new ItemStack(colony.getWorld().getBlockState(building.getPosition()).getBlock(), 1);
        colony.writeToItemStack(stack);
        new com.minecolonies.api.items.component.HutBlockData(building.getBuildingLevel(), false).writeToItemStack(stack);
        building.destroy();
        colony.getWorld().destroyBlock(building.getPosition(), false);
        return stack;
    }

    @Override
    @NotNull
    public FactionActionResult hire(@NotNull final IBuilding building, final int moduleId, @NotNull final ICitizenData citizen)
    {
        if (!isFactionBuilding(building))
        {
            return building != null && !isFaction(building.getColony()) ? FactionActionResult.NOT_FACTION_COLONY : FactionActionResult.NO_SUCH_BUILDING;
        }
        if (!(building.getModule(moduleId) instanceof final IAssignsJob module) || citizen.getColony() != building.getColony())
        {
            return FactionActionResult.NOT_POSSIBLE;
        }
        citizen.setPaused(false);
        return module.assignCitizen(citizen) ? FactionActionResult.OK : FactionActionResult.NOT_POSSIBLE;
    }

    @Override
    @NotNull
    public FactionActionResult fire(@NotNull final IBuilding building, final int moduleId, @NotNull final ICitizenData citizen)
    {
        if (!isFactionBuilding(building))
        {
            return building != null && !isFaction(building.getColony()) ? FactionActionResult.NOT_FACTION_COLONY : FactionActionResult.NO_SUCH_BUILDING;
        }
        if (!(building.getModule(moduleId) instanceof final IAssignsJob module) || citizen.getColony() != building.getColony())
        {
            return FactionActionResult.NOT_POSSIBLE;
        }
        citizen.setPaused(false);
        return module.removeCitizen(citizen) ? FactionActionResult.OK : FactionActionResult.NOT_POSSIBLE;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static FactionActionResult update(final ICommonSettingsModule module, final ISettingKey<?> key, final ISetting<?> value)
    {
        if (module == null || module.getSetting((ISettingKey) key) == null)
        {
            return FactionActionResult.NOT_POSSIBLE;
        }
        // No sender: a setting that follows its sender has nobody to follow.
        module.updateSetting(key, value, null);
        return FactionActionResult.OK;
    }

    @Override
    @NotNull
    public FactionActionResult setColonySetting(@NotNull final IColony colony, @NotNull final ISettingKey<?> key, @NotNull final ISetting<?> value)
    {
        if (!isFaction(colony))
        {
            return FactionActionResult.NOT_FACTION_COLONY;
        }
        return update(colony.getSettings(), key, value);
    }

    @Override
    @NotNull
    public FactionActionResult setBuildingSetting(@NotNull final IBuilding building, @NotNull final ISettingKey<?> key, @NotNull final ISetting<?> value)
    {
        if (!isFactionBuilding(building))
        {
            return building != null && !isFaction(building.getColony()) ? FactionActionResult.NOT_FACTION_COLONY : FactionActionResult.NO_SUCH_BUILDING;
        }
        return update(building.getModule(ISettingsModule.class), key, value);
    }
}
