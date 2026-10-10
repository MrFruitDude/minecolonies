package com.minecolonies.core.entity.ai.workers.util;

import com.ldtteam.structurize.blocks.schematic.BlockFluidSubstitution;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.placement.structure.IStructureHandler;
import com.ldtteam.structurize.util.BlockUtils;
import com.ldtteam.structurize.util.BlueprintPositionInfo;
import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.entity.ai.workers.util.IBuilderUndestroyable;
import com.minecolonies.api.items.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.ldtteam.structurize.api.util.TriPredicate;

/**
 * Which positions a stage of a structure build works on. The builder AI and the builders that help it use the same
 * rules, so a leased position is one the lead would have processed too.
 */
public final class BuilderStageRules
{
    private BuilderStageRules()
    {
    }

    /**
     * Positions nobody may touch.
     */
    public static boolean dontTouch(final BlueprintPositionInfo info, final BlockPos worldPos, final IStructureHandler handler)
    {
        final BlockState worldState = handler.getWorld().getBlockState(worldPos);

        return worldState.getBlock() instanceof IBuilderUndestroyable
                 || worldState.getBlock() == Blocks.BEDROCK
                 || (info.getBlockInfo().getState().getBlock() instanceof AbstractBlockHut && handler.getCenterPos().equals(worldPos)
                       && worldState.getBlock() instanceof AbstractBlockHut);
    }

    /**
     * Blocks that count as decoration.
     */
    public static boolean isDecoItem(final Block block)
    {
        return block.defaultBlockState().is(ModTags.decorationItems) || block instanceof BlockFluidSubstitution;
    }

    public static boolean skipBuilding(final BlueprintPositionInfo info, final BlockPos pos, final IStructureHandler handler)
    {
        final BlockState blockInfoState = info.getBlockInfo().getState();
        return !BlockUtils.canBlockFloatInAir(blockInfoState)
                 || isDecoItem(blockInfoState.getBlock())
                 || dontTouch(info, pos, handler);
    }

    public static boolean skipWeakSolid(final BlueprintPositionInfo info, final BlockPos pos, final IStructureHandler handler)
    {
        return !BlockUtils.isWeakSolidBlock(info.getBlockInfo().getState()) || dontTouch(info, pos, handler);
    }

    public static boolean skipDecorate(final BlueprintPositionInfo info, final BlockPos pos, final IStructureHandler handler)
    {
        final BlockState blockInfoState = info.getBlockInfo().getState();
        return (!isDecoItem(blockInfoState.getBlock()) && BlockUtils.isAnySolid(blockInfoState)) || dontTouch(info, pos, handler);
    }

    public static boolean skipClearing(final BlueprintPositionInfo info, final BlockPos pos, final IStructureHandler handler)
    {
        if (info.getBlockInfo().getState().getBlock() == com.ldtteam.structurize.blocks.ModBlocks.blockFluidSubstitution.get())
        {
            return true;
        }

        final BlockState state = handler.getWorld().getBlockState(pos);
        return state.getBlock() instanceof IBuilderUndestroyable
                 || state.getBlock() == Blocks.BEDROCK
                 || state.isAir()
                 || !state.getFluidState().isEmpty();
    }

    /**
     * The stages that builders may share. The others (water, leftovers, entities, removal) are left to the lead.
     */
    public static boolean isSharedStage(final BuildingProgressStage stage)
    {
        return stage == BuildingProgressStage.CLEAR
                 || stage == BuildingProgressStage.BUILD_SOLID
                 || stage == BuildingProgressStage.WEAK_SOLID
                 || stage == BuildingProgressStage.DECORATE;
    }

    /**
     * The skip rule of a shared stage, or null for the others.
     */
    public static TriPredicate<BlueprintPositionInfo, BlockPos, IStructureHandler> skipRule(final BuildingProgressStage stage)
    {
        return switch (stage)
        {
            case CLEAR -> BuilderStageRules::skipClearing;
            case BUILD_SOLID -> BuilderStageRules::skipBuilding;
            case WEAK_SOLID -> BuilderStageRules::skipWeakSolid;
            case DECORATE -> BuilderStageRules::skipDecorate;
            default -> null;
        };
    }

    /**
     * Whether the stage walks the structure backwards (top down).
     */
    public static boolean walksBackwards(final BuildingProgressStage stage)
    {
        return stage == BuildingProgressStage.CLEAR
                 || stage == BuildingProgressStage.CLEAR_NON_SOLIDS
                 || stage == BuildingProgressStage.REMOVE
                 || stage == BuildingProgressStage.REMOVE_WATER;
    }

    /**
     * How many positions of the blueprint a stage works on, judged from the blueprint alone (the world is not read).
     * Used for the block counts of the progress display. Positions that already match the world are counted too; the
     * display makes up for them when a stage ends.
     *
     * @param blueprint the blueprint.
     * @param stage     the stage.
     * @return the estimate.
     */
    public static int estimateBlocks(final Blueprint blueprint, final BuildingProgressStage stage)
    {
        if (blueprint == null || !isSharedStage(stage))
        {
            return 0;
        }
        final Block fluidSubstitution = com.ldtteam.structurize.blocks.ModBlocks.blockFluidSubstitution.get();
        final Block substitution = com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get();
        int count = 0;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < blueprint.getSizeY(); y++)
        {
            for (int z = 0; z < blueprint.getSizeZ(); z++)
            {
                for (int x = 0; x < blueprint.getSizeX(); x++)
                {
                    final BlockState state = blueprint.getBlockState(pos.set(x, y, z));
                    if (state == null || state.getBlock() == substitution)
                    {
                        continue;
                    }
                    final boolean air = state.getBlock() instanceof AirBlock;
                    switch (stage)
                    {
                        case CLEAR:
                            if (state.getBlock() != fluidSubstitution)
                            {
                                count++;
                            }
                            break;
                        case BUILD_SOLID:
                            if (!air && BlockUtils.canBlockFloatInAir(state) && !isDecoItem(state.getBlock()))
                            {
                                count++;
                            }
                            break;
                        case WEAK_SOLID:
                            if (BlockUtils.isWeakSolidBlock(state))
                            {
                                count++;
                            }
                            break;
                        default:
                            if (!air && (isDecoItem(state.getBlock()) || !BlockUtils.isAnySolid(state)))
                            {
                                count++;
                            }
                            break;
                    }
                }
            }
        }
        return count;
    }
}
