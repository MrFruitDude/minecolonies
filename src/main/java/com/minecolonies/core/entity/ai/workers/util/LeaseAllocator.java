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
import org.jetbrains.annotations.Nullable;

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
        return plan(world, order, stage, handler, placer, max, leadBuffer, stock, stackBudget, pos -> false);
    }

    /**
     * Like {@link #plan(Level, IBuilderWorkOrder, BuildingProgressStage, CollabStructureHandler, StructurePlacer, int, int, ToIntFunction, int)},
     * but the helper leaves positions alone that he gave up on (he cannot get to them).
     *
     * @param helperSkips positions the helper does not take.
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
      final int stackBudget,
      final java.util.function.Predicate<BlockPos> helperSkips)
    {
        return plan(world, order, stage, handler, placer, max, leadBuffer, stock, stackBudget, helperSkips, null);
    }

    /**
     * A candidate position of the scan.
     */
    private record Candidate(int index, BlockPos local, BlockPos world, @Nullable BlockPos before)
    {
    }

    /**
     * Like {@link #plan(Level, IBuilderWorkOrder, BuildingProgressStage, CollabStructureHandler, StructurePlacer, int, int, ToIntFunction, int, java.util.function.Predicate)},
     * but the positions are not the next ones in the order of the stage but a cluster of them: of the next few hundred, the ones next to each other, around the
     * one nearest to the helper. (The next positions in the order of the stage are spread over a whole layer of the structure: a helper who works through them
     * walks round the building for every layer, where a column of the wall is in reach from a spot or a tower.)
     *
     * @param near where the helper is, or null for the next positions of the order.
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
      final int stackBudget,
      final java.util.function.Predicate<BlockPos> helperSkips,
      @Nullable final BlockPos near)
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
          (info, pos, h) -> collab.isLeased(pos) || helperSkips.test(pos) || rule.test(info, pos, h);

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
        boolean exhausted = false;
        final int window = near == null ? max : Math.min(MAX_EXAMINED, Math.max(max, CLUSTER_WINDOW * max));

        // the scan: the next positions in the order of the stage
        final List<Candidate> candidates = new ArrayList<>();
        int examined = 0;
        while (candidates.size() < window && examined++ < MAX_EXAMINED)
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
            final BlockPos before = lastVisited;
            lastVisited = local;
            if (toLeaveToLead > 0)
            {
                toLeaveToLead--;
                continue;
            }
            candidates.add(new Candidate(candidates.size(), local, worldPos, before));
        }

        // the choice: in the order of the scan, or a cluster around the position nearest to the helper
        final List<Candidate> order2 = new ArrayList<>(candidates);
        if (near != null && candidates.size() > max)
        {
            Candidate seed = candidates.get(0);
            double best = Double.MAX_VALUE;
            for (final Candidate candidate : candidates)
            {
                final double d = clusterDistance(candidate.world(), near);
                if (d < best)
                {
                    best = d;
                    seed = candidate;
                }
            }
            final BlockPos seedPos = seed.world();
            order2.sort(java.util.Comparator.comparingDouble((Candidate c) -> clusterDistance(c.world(), seedPos)).thenComparingInt(Candidate::index));
        }

        // where the scan has to resume from because a position was passed over (for lack of stock, or because it is not in the cluster): it is not leased,
        // the scan must come back
        final Map<ItemStorage, Integer> accumulated = new HashMap<>();
        final boolean[] taken = new boolean[candidates.size()];
        for (final Candidate candidate : order2)
        {
            if (planned.size() >= max)
            {
                break;
            }
            final List<ItemStack> required = placing ? requirements(world, placer, handler, candidate.local(), candidate.world()) : List.of();
            if (!fits(accumulated, required, stock, stackBudget))
            {
                continue;
            }
            for (final ItemStack stack : required)
            {
                accumulated.merge(new ItemStorage(stack.copyWithCount(1)), stack.getCount(), Integer::sum);
            }
            planned.add(new Planned(candidate.local(), candidate.world(), required));
            taken[candidate.index()] = true;
        }

        BlockPos resumeFrom = null;
        boolean passedOver = false;
        for (final Candidate candidate : candidates)
        {
            if (!taken[candidate.index()])
            {
                passedOver = true;
                resumeFrom = candidate.before();
                break;
            }
        }
        if (passedOver)
        {
            // The positions that were not taken are still open: the scan is not over, and picks them up again (when the stock has grown, or for the next
            // cluster). (Without this a helper that looks while the materials are still on their way would never get anything to do.)
            exhausted = false;
            lastVisited = resumeFrom;
        }
        collab.setScanExhausted(exhausted);
        collab.setScanCursor(exhausted ? null : lastVisited);
        return planned;
    }

    /** How many times the lease size the scan looks at when the positions are picked as a cluster. */
    private static final int CLUSTER_WINDOW = 6;

    /**
     * How far apart two positions are for the cluster of a lease: a builder reaches higher than wide on a tower, so the height counts half.
     */
    private static double clusterDistance(final BlockPos a, final BlockPos b)
    {
        final double dx = a.getX() - b.getX();
        final double dz = a.getZ() - b.getZ();
        final double dy = a.getY() - b.getY();
        return dx * dx + dz * dz + 0.5D * dy * dy;
    }

    /**
     * Divides the rest of a stage between the lead and his helpers: slabs along the longer side of what is left to do, with about the same number of
     * positions in each. The lead gets the slab nearest to his hut. Too little left to divide: the stage is not shared that way.
     *
     * @param world        the world.
     * @param order        the order.
     * @param stage        the stage.
     * @param handler      the structure handler of the helper.
     * @param placer       a placer over the handler.
     * @param participants the huts of the lead (first) and of his helpers.
     */
    public static void divideStage(
      final Level world,
      final IBuilderWorkOrder order,
      final BuildingProgressStage stage,
      final CollabStructureHandler handler,
      final StructurePlacer placer,
      final List<BlockPos> participants)
    {
        final WorkOrderCollab collab = order.getCollab();
        final TriPredicate<BlueprintPositionInfo, BlockPos, IStructureHandler> rule = BuilderStageRules.skipRule(stage);
        if (rule == null || participants.size() < 2)
        {
            collab.skipTerritory();
            return;
        }
        final boolean backwards = BuilderStageRules.walksBackwards(stage);
        final AbstractBlueprintIterator iterator = placer.getIterator();
        iterator.reset();
        iterator.setProgressPos(NULL_POS);
        final List<BlockPos> remaining = new ArrayList<>();
        int guard = 0;
        while (guard++ < MAX_DIVIDE)
        {
            final AbstractBlueprintIterator.Result result = backwards ? iterator.decrement(rule) : iterator.increment(rule);
            if (result == AbstractBlueprintIterator.Result.AT_END || result == AbstractBlueprintIterator.Result.CONFIG_LIMIT)
            {
                break;
            }
            remaining.add(handler.getProgressPosInWorld(iterator.getProgressPos()));
        }
        if (remaining.size() < MIN_TO_DIVIDE)
        {
            collab.skipTerritory();
            return;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (final BlockPos pos : remaining)
        {
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        final int axis = maxX - minX >= maxZ - minZ ? 0 : 1;
        final int[] coordinates = new int[remaining.size()];
        for (int i = 0; i < coordinates.length; i++)
        {
            coordinates[i] = axis == 0 ? remaining.get(i).getX() : remaining.get(i).getZ();
        }
        java.util.Arrays.sort(coordinates);
        final int slabs = participants.size();
        final int[] bounds = new int[slabs - 1];
        for (int i = 0; i < bounds.length; i++)
        {
            bounds[i] = coordinates[Math.min(coordinates.length - 1, (i + 1) * coordinates.length / slabs)];
            if (i > 0 && bounds[i] <= bounds[i - 1])
            {
                // all the positions of a slab share a coordinate: no clean cut
                collab.skipTerritory();
                return;
            }
        }
        if (bounds.length > 0 && (bounds[0] <= coordinates[0]))
        {
            collab.skipTerritory();
            return;
        }
        // the lead gets the slab nearest to his hut, the helpers the others in the order they joined
        final BlockPos lead = participants.get(0);
        final int leadCoordinate = axis == 0 ? lead.getX() : lead.getZ();
        int leadSlab = 0;
        while (leadSlab < bounds.length && leadCoordinate >= bounds[leadSlab])
        {
            leadSlab++;
        }
        final List<BlockPos> owners = new ArrayList<>();
        int nextHelper = 1;
        for (int slab = 0; slab < slabs; slab++)
        {
            owners.add(slab == leadSlab ? lead : participants.get(nextHelper++));
        }
        collab.setTerritory(axis, bounds, owners, lead);
    }

    /** The most positions looked at when the stage is divided. */
    private static final int MAX_DIVIDE = 200_000;

    /** Less than this left in the stage: it is not divided. */
    private static final int MIN_TO_DIVIDE = 24;

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
