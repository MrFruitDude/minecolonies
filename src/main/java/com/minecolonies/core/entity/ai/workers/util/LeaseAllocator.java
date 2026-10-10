package com.minecolonies.core.entity.ai.workers.util;

import com.ldtteam.structurize.api.util.TriPredicate;
import com.ldtteam.structurize.placement.AbstractBlueprintIterator;
import com.ldtteam.structurize.placement.BlockPlacementResult;
import com.ldtteam.structurize.placement.StructurePhasePlacementResult;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.ldtteam.structurize.placement.structure.IStructureHandler;
import com.ldtteam.structurize.util.BlueprintPositionInfo;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.core.colony.workorders.collab.WorkOrderCollab;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import static com.ldtteam.structurize.placement.AbstractBlueprintIterator.NULL_POS;

/**
 * Takes positions of a stage out of the stream the lead of an order walks through, for a helper to work on, and
 * works out which materials they need. Where the lead's stream is the structure iterator with the stage's skip rule,
 * the allocator runs the same iterator on its own scan cursor ahead of the lead and skips what is leased already.
 */
public final class LeaseAllocator
{
    /**
     * Candidate positions looked at per call at most.
     */
    private static final int MAX_EXAMINED = 400;

    private LeaseAllocator()
    {
    }

    /**
     * A leased position.
     *
     * @param local    the blueprint-local position.
     * @param world    the world position.
     * @param required the materials the position needs, in all of them one entry per item (count in the stack).
     */
    public record Planned(BlockPos local, BlockPos world, List<ItemStack> required)
    {
    }

    /**
     * Picks the next positions of the stage that a helper can do with the materials at hand.
     * <p>
     * The first positions after the lead's cursor are left to the lead. Positions that need more than is in stock are left to the
     * lead as well. The positions are not leased here; the caller leases them.
     *
     * @param world        the world.
     * @param order        the order.
     * @param stage        the stage to take positions from (a shared stage).
     * @param handler      the structure handler of the helper.
     * @param placer       a placer over the handler, with the order's iterator type.
     * @param max          the most positions to take.
     * @param leadBuffer   the number of positions right after the lead's cursor that are left to the lead.
     * @param stock        how many of an item the helper has in stock (lead's hut plus what the helper carries).
     * @param stackBudget  the most stacks the helper has room for.
     * @return the planned positions, possibly none.
     */
    public static List<Planned> plan(
      final Level world,
      final IBuilderWorkOrder order,
      final BuildingProgressStage stage,
      final CollabStructureHandler handler,
      final StructurePlacer placer,
      final int max,
      final int leadBuffer,
      final ToIntFunction<ItemStorage> stock,
      final int stackBudget)
    {
        final WorkOrderCollab collab = order.getCollab();
        final TriPredicate<BlueprintPositionInfo, BlockPos, IStructureHandler> rule = BuilderStageRules.skipRule(stage);
        final List<Planned> planned = new ArrayList<>();
        if (rule == null || collab.isScanExhausted())
        {
            return planned;
        }

        final boolean backwards = BuilderStageRules.walksBackwards(stage);
        final boolean placing = stage != BuildingProgressStage.CLEAR && !handler.isCreative();
        final TriPredicate<BlueprintPositionInfo, BlockPos, IStructureHandler> skip =
          (info, pos, h) -> collab.isLeased(pos) || rule.test(info, pos, h);

        final AbstractBlueprintIterator iterator = placer.getIterator();
        BlockPos start = collab.getScanCursor();
        final boolean fresh = start == null;
        if (fresh)
        {
            start = collab.getProgressPos() == null ? NULL_POS : collab.getProgressPos();
        }
        iterator.reset();
        iterator.setProgressPos(start);

        int toLeaveToLead = fresh ? leadBuffer : 0;
        BlockPos lastVisited = fresh ? null : start;
        final Map<ItemStorage, Integer> accumulated = new HashMap<>();
        int examined = 0;
        boolean exhausted = false;
        while (planned.size() < max && examined++ < MAX_EXAMINED)
        {
            final AbstractBlueprintIterator.Result result = backwards ? iterator.decrement(skip) : iterator.increment(skip);
            if (result == AbstractBlueprintIterator.Result.AT_END)
            {
                exhausted = true;
                break;
            }
            if (result == AbstractBlueprintIterator.Result.CONFIG_LIMIT)
            {
                lastVisited = iterator.getProgressPos();
                break;
            }

            final BlockPos local = iterator.getProgressPos();
            final BlockPos worldPos = handler.getProgressPosInWorld(local);
            lastVisited = local;
            if (toLeaveToLead > 0)
            {
                toLeaveToLead--;
                continue;
            }

            final List<ItemStack> required = placing ? requirements(world, placer, handler, local, worldPos) : List.of();
            if (!fits(accumulated, required, stock, stackBudget))
            {
                continue;
            }
            for (final ItemStack stack : required)
            {
                accumulated.merge(new ItemStorage(stack.copyWithCount(1)), stack.getCount(), Integer::sum);
            }
            planned.add(new Planned(local, worldPos, required));
        }

        collab.setScanExhausted(exhausted);
        collab.setScanCursor(exhausted ? null : lastVisited);
        return planned;
    }

    private static boolean fits(
      final Map<ItemStorage, Integer> accumulated,
      final List<ItemStack> required,
      final ToIntFunction<ItemStorage> stock,
      final int stackBudget)
    {
        final Map<ItemStorage, Integer> total = new HashMap<>(accumulated);
        for (final ItemStack stack : required)
        {
            total.merge(new ItemStorage(stack.copyWithCount(1)), stack.getCount(), Integer::sum);
        }
        int stacks = 0;
        for (final Map.Entry<ItemStorage, Integer> entry : total.entrySet())
        {
            if (stock.applyAsInt(entry.getKey()) < entry.getValue())
            {
                return false;
            }
            stacks += (entry.getValue() + entry.getKey().getItemStack().getMaxStackSize() - 1) / entry.getKey().getItemStack().getMaxStackSize();
        }
        return stacks <= stackBudget;
    }

    /**
     * The materials one position needs.
     */
    public static List<ItemStack> requirements(
      final Level world, final StructurePlacer placer, final IStructureHandler handler, final BlockPos local, final BlockPos worldPos)
    {
        final BlockState state = handler.getBluePrint().getBlockState(local);
        if (state == null)
        {
            return List.of();
        }
        final BlockPlacementResult result = placer.getResourceRequirements(world, worldPos, local, state, handler.getBluePrint().getTileEntityData(worldPos, local));
        return result.getRequiredItems();
    }

    /**
     * Does the work at exactly one position: the same step the lead does for the position the iterator hands it.
     *
     * @param placer    the placer.
     * @param world     the world.
     * @param local     the blueprint-local position.
     * @param operation the operation (block placement, or block removal for the clear stage).
     * @return the result of the step.
     */
    public static StructurePhasePlacementResult doPosition(
      final StructurePlacer placer, final Level world, final BlockPos local, final StructurePlacer.Operation operation)
    {
        final AbstractBlueprintIterator iterator = placer.getIterator();
        final boolean[] given = {false};
        return placer.executeStructureStep(world, null, NULL_POS, operation, () -> {
            if (given[0])
            {
                return AbstractBlueprintIterator.Result.AT_END;
            }
            given[0] = true;
            iterator.setProgressPos(local);
            return AbstractBlueprintIterator.Result.NEW_BLOCK;
        }, false);
    }
}
