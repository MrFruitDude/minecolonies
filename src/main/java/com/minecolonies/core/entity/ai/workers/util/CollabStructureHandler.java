package com.minecolonies.core.entity.ai.workers.util;

import com.ldtteam.structurize.api.compat.itemhandler.IItemHandler;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.placement.structure.AbstractStructureHandler;
import com.ldtteam.structurize.util.BlockUtils;
import com.ldtteam.structurize.util.PlacementSettings;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.StatsUtil;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.minecolonies.api.util.constant.StatisticsConstants.BLOCKS_PLACED;

/**
 * Structure handler of a builder that works on a structure that another builder leads: the work order's blueprint, the
 * helper's own inventory. It never requests anything (the helper takes its materials from the lead's hut before it
 * places) and does not touch the stage of the work order.
 */
public class CollabStructureHandler extends AbstractStructureHandler
{
    private static final double XP_EACH_BLOCK = 0.05D;

    /**
     * The helper; null when the handler is only used to read requirements and leases.
     */
    @Nullable
    private final AbstractEntityCitizen worker;

    /**
     * The stage the handler works for.
     */
    private final BuildingProgressStage stage;

    /**
     * The block that replaces solid placeholders (the fill block setting of the lead).
     */
    private final Supplier<BlockState> fillBlock;

    /**
     * What the helper has consumed since the last call to {@link #takeConsumed()}.
     */
    private final Map<ItemStorage, Integer> consumed = new HashMap<>();

    private final IBuilding structureBuilding;

    public CollabStructureHandler(
      final Level world,
      final IBuilderWorkOrder order,
      final BuildingProgressStage stage,
      @Nullable final AbstractEntityCitizen worker,
      final Supplier<BlockState> fillBlock)
    {
        super(world,
          order.getLocation(),
          order.getBlueprint(),
          new PlacementSettings(order.getRotationMirror().mirror(), order.getRotationMirror().rotation()));
        this.stage = stage;
        this.worker = worker;
        this.fillBlock = fillBlock;
        final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(world, order.getLocation());
        this.structureBuilding = colony == null ? null : colony.getServerBuildingManager().getBuilding(order.getLocation());
    }

    /**
     * The items consumed so far, which also resets the record.
     */
    public Map<ItemStorage, Integer> takeConsumed()
    {
        final Map<ItemStorage, Integer> copy = new HashMap<>(consumed);
        consumed.clear();
        return copy;
    }

    @Override
    public void prePlacementLogic(final BlockPos worldPos, final BlockState blockState, final List<ItemStack> requiredItems)
    {
        if (worker == null)
        {
            return;
        }
        com.minecolonies.core.util.WorkerUtil.faceBlock(worldPos, worker);
        worker.setItemSlot(EquipmentSlot.MAINHAND, requiredItems.isEmpty() ? ItemStackUtils.EMPTY : requiredItems.get(0));

        if (Mth.floor(worker.getX()) == worldPos.getX()
              && Mth.abs(worldPos.getY() - (int) worker.getY()) <= 1
              && Mth.floor(worker.getZ()) == worldPos.getZ()
              && worker.getNavigation().isDone())
        {
            EntityNavigationUtils.walkAwayFrom(worker, worldPos, 1, 1.0);
        }

        worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
    }

    @Nullable
    @Override
    public IItemHandler getInventory()
    {
        return worker == null ? null : worker.getInventoryCitizen();
    }

    @Override
    public void triggerSuccess(final BlockPos pos, final List<ItemStack> list, final boolean placement)
    {
        final BlockPos worldPos = getProgressPosInWorld(pos);
        final BlockState state = getBluePrint().getBlockState(pos);
        if (structureBuilding != null)
        {
            structureBuilding.registerBlockPosition(state, worldPos, this.getWorld());
        }

        if (placement && worker != null)
        {
            com.minecolonies.core.colony.workorders.collab.BuilderCollab.placed(worldPos, worker.getCitizenData().getWorkBuilding().getID());
            worker.getCitizenExperienceHandler().addExperience(XP_EACH_BLOCK);
            for (final ItemStack ignored : list)
            {
                StatsUtil.trackStat(worker.getCitizenData().getWorkBuilding(), BLOCKS_PLACED, 1);
                worker.getCitizenColonyHandler().getColonyOrRegister().getStatisticsManager()
                  .increment(BLOCKS_PLACED, worker.getCitizenColonyHandler().getColonyOrRegister().getDay());
            }
            worker.queueSound(state.getSoundType().getPlaceSound(), worldPos, 10, 0);
        }

        if (state.getBlock() == ModBlocks.blockWayPoint && worker != null)
        {
            worker.getCitizenColonyHandler().getColonyOrRegister().addWayPoint(worldPos, state);
        }
    }

    @Override
    public void triggerEntitySuccess(final BlockPos blockPos, final List<ItemStack> list, final boolean placement)
    {
    }

    @Override
    public boolean hasRequiredItems(@NotNull final List<ItemStack> requiredItems)
    {
        final IItemHandler inventory = getInventory();
        if (inventory == null)
        {
            return false;
        }
        final Map<ItemStorage, Integer> needed = new HashMap<>();
        for (final ItemStack stack : requiredItems)
        {
            if (!stack.isEmpty())
            {
                needed.merge(new ItemStorage(stack.copyWithCount(1)), stack.getCount(), Integer::sum);
            }
        }
        for (final Map.Entry<ItemStorage, Integer> entry : needed.entrySet())
        {
            final int have = InventoryUtils.getItemCountInItemHandler(inventory, s -> ItemStackUtils.compareItemStacksIgnoreStackSize(entry.getKey().getItemStack(), s));
            if (have < entry.getValue())
            {
                return false;
            }
        }
        return true;
    }

    @Override
    public void consume(final List<ItemStack> requiredItems)
    {
        final IItemHandler inventory = getInventory();
        if (inventory == null)
        {
            return;
        }
        for (final ItemStack stack : requiredItems)
        {
            if (!ItemStackUtils.isEmpty(stack))
            {
                InventoryUtils.reduceBucketAwareStackInItemHandler(inventory, stack, stack.getCount());
                consumed.merge(new ItemStorage(stack.copyWithCount(1)), stack.getCount(), Integer::sum);
            }
        }
    }

    @Override
    public boolean isCreative()
    {
        return Constants.BUILDER_INF_RESOURECES;
    }

    @Override
    public int getStepsPerCall()
    {
        return 1;
    }

    @Override
    public int getMaxBlocksCheckedPerCall()
    {
        return 10000;
    }

    @Override
    public boolean isStackFree(@Nullable final ItemStack itemStack)
    {
        return itemStack == null
                 || itemStack.isEmpty()
                 || itemStack.is(ItemTags.LEAVES)
                 || itemStack.getItem() == new ItemStack(ModBlocks.blockDecorationPlaceholder, 1).getItem();
    }

    @Override
    public boolean allowReplace()
    {
        return stage != BuildingProgressStage.CLEAR;
    }

    @Override
    public ItemStack getHeldItem()
    {
        return worker == null ? ItemStack.EMPTY : worker.getMainHandItem();
    }

    @Override
    public BlockState getSolidBlockForPos(final BlockPos worldPos, @Nullable final Function<BlockPos, BlockState> virtualBlocks)
    {
        return fillBlock.get();
    }

    @Override
    public BlockState getSolidBlockForPos(final BlockPos worldPos)
    {
        return fillBlock.get();
    }

    @Override
    public boolean replaceWithSolidBlock(final BlockState blockState)
    {
        return !BlockUtils.isGoodFloorBlock(blockState);
    }

    @Override
    public boolean fancyPlacement()
    {
        return true;
    }

    @Override
    public boolean shouldBlocksBeConsideredEqual(final BlockState state1, final BlockState state2)
    {
        return false;
    }

    public Blueprint blueprint()
    {
        return getBluePrint();
    }
}
