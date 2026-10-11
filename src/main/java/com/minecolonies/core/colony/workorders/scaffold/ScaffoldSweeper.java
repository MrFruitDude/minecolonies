package com.minecolonies.core.colony.workorders.scaffold;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Takes scaffolding down that no builder is going to take down: when an order ends, is released or handed over, and as a backstop on the
 * colony tick (scaffolding in chunks that were not loaded when the order ended, after a load, a builder hut that is gone). The scaffolding
 * comes down top down, outer arms first, without drops, and the items go to the hut of the builder who placed them (into the world at the
 * spot if the hut is gone or full), so nothing is lost and nothing is duplicated.
 */
public final class ScaffoldSweeper
{
    private ScaffoldSweeper()
    {
    }

    /**
     * Most blocks taken down per call on the colony tick.
     */
    public static final int TICK_BUDGET = 48;

    /**
     * Ticks a builder's hut has to be off an order before its scaffolding is taken down (a load binds the huts to their orders a moment
     * after the orders are read).
     */
    public static final int GRACE_TICKS = 200;

    /**
     * Takes down scaffolding of the ledger.
     *
     * @param colony the colony.
     * @param ledger the ledger; what is taken down (or is not scaffolding any more) leaves it.
     * @param which  which cells, or null for all.
     * @param budget the most blocks to take down.
     * @return the number of scaffolding blocks taken down.
     */
    public static int sweep(final IColony colony, final ScaffoldLedger ledger, @Nullable final Predicate<ScaffoldLedger.Cell> which, final int budget)
    {
        final Level level = colony.getWorld();
        final Map<BlockPos, Integer> itemsPerHut = new HashMap<>();
        final Map<BlockPos, BlockPos> dropAt = new HashMap<>();
        int removed = 0;
        for (final ScaffoldLedger.Cell cell : ledger.topDown(null))
        {
            if (removed >= budget)
            {
                break;
            }
            if (which != null && !which.test(cell))
            {
                continue;
            }
            if (!WorldUtil.isBlockLoaded(level, cell.pos()))
            {
                continue;
            }
            final BlockState state = level.getBlockState(cell.pos());
            if (state.is(Blocks.SCAFFOLDING))
            {
                level.removeBlock(cell.pos(), false);
                removed++;
                itemsPerHut.merge(cell.hut(), 1, Integer::sum);
                dropAt.putIfAbsent(cell.hut(), cell.pos());
            }
            // anything else is not ours any more (mined by the builder, who has the item): the ledger forgets it
            ledger.remove(cell.pos());
        }
        for (final Map.Entry<BlockPos, Integer> entry : itemsPerHut.entrySet())
        {
            giveBack(colony, entry.getKey(), entry.getValue(), dropAt.get(entry.getKey()));
        }
        if (removed > 0)
        {
            Log.getLogger().debug("Took down {} scaffolding blocks of colony {}", removed, colony.getID());
        }
        return removed;
    }

    /**
     * Puts scaffolding items into the hut of the builder; what does not fit (or all of it without a hut) is dropped at the spot.
     */
    private static void giveBack(final IColony colony, final BlockPos hutPos, final int count, final BlockPos where)
    {
        ItemStack rest = new ItemStack(Items.SCAFFOLDING, count);
        final IBuilding hut = colony.getServerBuildingManager().getBuilding(hutPos);
        if (hut != null && hut.getItemHandlerCap() != null)
        {
            rest = InventoryUtils.transferItemStackIntoNextBestSlotInItemHandlerWithResult(rest, hut.getItemHandlerCap());
        }
        if (!rest.isEmpty())
        {
            Block.popResource(colony.getWorld(), where, rest);
        }
    }

    /**
     * Whether a builder of the hut works on the order (leads it or helps with it).
     */
    public static boolean isActive(final IColony colony, final IBuilderWorkOrder order, final BlockPos hutPos)
    {
        final IBuilding hut = colony.getServerBuildingManager().getBuilding(hutPos);
        if (!(hut instanceof AbstractBuildingStructureBuilder builder))
        {
            return false;
        }
        return (builder.hasWorkOrder() && builder.getWorkOrder() == order) || order.getCollab().hasAssistant(hutPos);
    }

    /**
     * The backstop on the colony tick.
     *
     * @param colony   the colony.
     * @param orders   its work orders.
     * @param orphans  the scaffolding of orders that ended (persisted by the work manager).
     * @param inactive when a hut was first seen off its order (per order id and hut), kept between the calls.
     */
    public static void tick(
      final IColony colony,
      final Collection<IServerWorkOrder> orders,
      final ScaffoldLedger orphans,
      final Map<Long, Long> inactive)
    {
        final long now = colony.getWorld().getGameTime();
        int budget = TICK_BUDGET;
        if (!orphans.isEmpty())
        {
            budget -= sweep(colony, orphans, null, budget);
        }
        for (final IServerWorkOrder order : orders)
        {
            if (!(order instanceof IBuilderWorkOrder builderOrder) || builderOrder.getScaffolds().isEmpty())
            {
                continue;
            }
            final Predicate<ScaffoldLedger.Cell> leftBehind = cell ->
            {
                final long key = ((long) order.getID() << 32) ^ cell.hut().asLong();
                if (isActive(colony, builderOrder, cell.hut()))
                {
                    inactive.remove(key);
                    return false;
                }
                final long since = inactive.computeIfAbsent(key, k -> now);
                return now - since >= GRACE_TICKS;
            };
            if (budget > 0)
            {
                budget -= sweep(colony, builderOrder.getScaffolds(), leftBehind, budget);
            }
            else
            {
                // keep the grace book up to date
                for (final ScaffoldLedger.Cell cell : builderOrder.getScaffolds().all())
                {
                    leftBehind.test(cell);
                }
            }
        }
    }
}
