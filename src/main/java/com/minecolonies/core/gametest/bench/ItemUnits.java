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
                        total += units(rack.getInventory().getStackInSlot(slot));
                    }
                }
            }
        }
        for (final ICitizenData citizen : run.colony.getCitizenManager().getCitizens())
        {
            final var inventory = citizen.getInventory();
            for (int slot = 0; slot < inventory.getSlots(); slot++)
            {
                total += units(inventory.getStackInSlot(slot));
            }
        }
        final BlockPos c = run.colony.getCenter();
        final AABB box = new AABB(c.getX() - 160, c.getY() - 8, c.getZ() - 160, c.getX() + 160, c.getY() + 40, c.getZ() + 160);
        for (final ItemEntity entity : run.level.getEntitiesOfClass(ItemEntity.class, box))
        {
            total += units(entity.getItem());
        }
        for (final Map.Entry<BlockPos, Block> site : run.sites.entrySet())
        {
            if (run.level.getBlockState(site.getKey()).is(site.getValue()))
            {
                total += perItem(new ItemStack(site.getValue().asItem()));
            }
        }
        return total;
    }
}
