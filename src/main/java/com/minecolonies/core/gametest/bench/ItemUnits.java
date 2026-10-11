package com.minecolonies.core.gametest.bench;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.tileentities.TileEntityRack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Item accounting for the conservation gate. Crafting turns items into other items, so conservation is checked in units
 * that every recipe the benchmark uses preserves (1 log = 4 planks = 8 units, 2 planks = 4 sticks, 1 cobblestone = 1 stone
 * brick, 3 wheat = 1 bread): the sum over everything in the colony must equal what the scripted suppliers added minus what
 * the scripted sinks took. A duplicated item shows as a surplus, a lost one as a deficit.
 */
public final class ItemUnits
{
    private static final Map<Item, Integer> UNITS = Map.ofEntries(
      Map.entry(Items.OAK_LOG, 8),
      Map.entry(Items.OAK_PLANKS, 2),
      Map.entry(Items.STICK, 1),
      Map.entry(Items.OAK_FENCE, 4),
      Map.entry(Items.COBBLESTONE, 1),
      Map.entry(Items.STONE_BRICKS, 1),
      Map.entry(Items.WHEAT, 1),
      Map.entry(Items.BREAD, 3),
      Map.entry(Items.COOKED_BEEF, 4),
      Map.entry(Items.STONE_PICKAXE, 10),
      Map.entry(Items.STONE_AXE, 10),
      Map.entry(Items.SAND, 1),
      Map.entry(Items.GLASS, 1));

    private ItemUnits()
    {
    }

    public static int perItem(final ItemStack stack)
    {
        return UNITS.getOrDefault(stack.getItem(), 0);
    }

    public static boolean tracked(final Item item)
    {
        return UNITS.containsKey(item);
    }

    public static long units(final ItemStack stack)
    {
        return stack.isEmpty() ? 0 : (long) perItem(stack) * stack.getCount();
    }

    /**
     * Everything in the colony: racks of every building, citizen inventories, loose item entities, and the blocks of finished builds.
     */
    public static long unitsInWorld(final MacroRun run)
    {
        return scan(run, null);
    }

    /**
     * Same as {@link #unitsInWorld} with a breakdown per place, for failure messages.
     */
    public static String breakdown(final MacroRun run)
    {
        final StringBuilder out = new StringBuilder();
        scan(run, out);
        return out.toString();
    }

    /**
     * Where every tracked unit sits, keyed by place ("rack@pos#slot", "citizenN#slot", "entity@pos", "site@pos"): the next
     * snapshot differs from this one by exactly what moved, which names the culprit when conservation breaks.
     */
    public static java.util.Map<String, String> places(final MacroRun run)
    {
        final StringBuilder out = new StringBuilder();
        scan(run, out);
        final java.util.Map<String, String> map = new java.util.TreeMap<>();
        for (final String entry : out.toString().split(";"))
        {
            final int eq = entry.indexOf('=');
            if (eq > 0)
            {
                map.merge(entry.substring(0, eq).trim(), entry.substring(eq + 1).trim(), (a, b) -> a + "+" + b);
            }
        }
        return map;
    }

    private static long scan(final MacroRun run, final StringBuilder detail)
    {
        long total = 0;
        final Set<BlockPos> seen = new HashSet<>();
        for (final IBuilding building : run.colony.getServerBuildingManager().getBuildings().values())
        {
            for (final BlockPos pos : building.getContainers())
            {
                if (!seen.add(pos))
                {
                    continue;
                }
                final BlockEntity entity = run.level.getBlockEntity(pos);
                if (entity instanceof TileEntityRack rack)
                {
                    for (int slot = 0; slot < rack.getInventory().getSlots(); slot++)
                    {
                        final long u = units(rack.getInventory().getStackInSlot(slot));
                        total += u;
                        if (detail != null && u > 0)
                        {
                            detail.append(" rack@").append(building.getSchematicName()).append(pos.toShortString()).append("#").append(slot).append('=').append(rack.getInventory().getStackInSlot(slot)).append(';');
                        }
                    }
                }
            }
        }
        for (final ICitizenData citizen : run.colony.getCitizenManager().getCitizens())
        {
            final var inventory = citizen.getInventory();
            for (int slot = 0; slot < inventory.getSlots(); slot++)
            {
                final long u = units(inventory.getStackInSlot(slot));
                total += u;
                if (detail != null && u > 0)
                {
                    detail.append(" citizen").append(citizen.getId()).append("#").append(slot).append('=').append(inventory.getStackInSlot(slot)).append(';');
                }
            }
        }
        for (final ItemEntity entity : run.level.getEntitiesOfClass(ItemEntity.class, run.plotBox()))
        {
            total += units(entity.getItem());
            if (detail != null && units(entity.getItem()) > 0)
            {
                detail.append(" entity@").append(entity.blockPosition().toShortString()).append('=').append(entity.getItem()).append(';');
            }
        }
        for (final Map.Entry<BlockPos, Block> site : run.sites.entrySet())
        {
            if (run.level.getBlockState(site.getKey()).is(site.getValue()))
            {
                total += perItem(new ItemStack(site.getValue().asItem()));
                if (detail != null)
                {
                    detail.append(" site@").append(site.getKey().toShortString()).append('=').append(site.getValue()).append(';');
                }
            }
        }
        return total;
    }
}
