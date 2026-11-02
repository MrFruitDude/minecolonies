package com.minecolonies.core.gametest;

import com.ldtteam.structurize.api.compat.itemhandler.IItemHandler;
import com.ldtteam.structurize.api.compat.itemhandler.wrapper.InvWrapper;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.tileentities.AbstractTileEntityRack;
import com.minecolonies.api.util.BuildingContainers;
import com.minecolonies.api.util.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Test-only physical stock ledger. Raw containers are counted once; aggregate hut handlers and delivery records are excluded. */
final class ProductionStockLedger
{
    private ProductionStockLedger() { }

    record Snapshot(int planks, Map<String, Integer> locations) { }

    static Snapshot capture(final GameTestHelper helper, final IColony colony, final ServerPlayer owner)
    {
        final var level = helper.getLevel();
        final BlockPos low = helper.absolutePos(new BlockPos(-8, 0, -8));
        final BlockPos high = helper.absolutePos(new BlockPos(64, 32, 48));
        final AABB area = new AABB(low.getX(), low.getY(), low.getZ(), high.getX() + 1, high.getY() + 1, high.getZ() + 1);
        final Map<String, Integer> locations = new LinkedHashMap<>();
        final Set<IItemHandler> inventories = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final var citizen : colony.getCitizenManager().getCitizens())
        {
            helper.assertTrue(citizen.getEntity().isPresent() && citizen.getEntity().orElseThrow().isAlive(),
              "physical ledger cannot observe citizen " + citizen.getId());
            helper.assertTrue(area.contains(citizen.getEntity().orElseThrow().position()),
              "physical ledger citizen outside the loaded fixture: " + citizen.getId() + " at " + citizen.getEntity().orElseThrow().position());
            add(helper, locations, inventories, "citizen/" + citizen.getId(), citizen.getInventory());
        }
        add(helper, locations, inventories, "owner/" + owner.getUUID(), new InvWrapper(owner.getInventory()));

        final Set<BlockPos> positions = new LinkedHashSet<>();
        for (final var building : colony.getServerBuildingManager().getBuildings().values())
        {
            positions.add(building.getID().immutable());
            for (final BlockPos pos : building.getContainers()) { positions.add(pos.immutable()); }
        }
        // Include unregistered physical containers too, without loading or generating any missing chunks.
        for (int cx = low.getX() >> 4; cx <= high.getX() >> 4; cx++)
        {
            for (int cz = low.getZ() >> 4; cz <= high.getZ() >> 4; cz++)
            {
                final var chunk = level.getChunkSource().getChunkNow(cx, cz);
                helper.assertTrue(chunk != null, "physical ledger fixture chunk unavailable: " + cx + "," + cz);
                for (final var tile : chunk.getBlockEntities().values())
                {
                    if (area.contains(net.minecraft.world.phys.Vec3.atCenterOf(tile.getBlockPos())) && (tile instanceof AbstractTileEntityRack || tile instanceof Container))
                    {
                        positions.add(tile.getBlockPos().immutable());
                    }
                }
            }
        }
        for (final BlockPos pos : positions)
        {
            helper.assertTrue(level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null,
              "physical ledger registered container chunk unavailable: " + pos);
            final var tile = level.getBlockEntity(pos);
            final IItemHandler inventory;
            if (tile instanceof AbstractTileEntityRack rack)
            {
                inventory = rack.getInventory();
            }
            else if (tile instanceof Container container)
            {
                // A vanilla chest exposes its own slots, not the double chest's aggregate inventory.
                inventory = new InvWrapper(container);
            }
            else
            {
                inventory = BuildingContainers.handler(tile);
            }
            helper.assertTrue(inventory != null, "physical ledger container inventory unavailable: " + pos + " tile=" + tile);
            add(helper, locations, inventories, "container/" + pos.toShortString(), inventory);
        }
        for (final ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, area))
        {
            locations.put("item/" + item.getUUID() + "@" + item.blockPosition().toShortString(),
              item.getItem().is(Items.OAK_PLANKS) ? item.getItem().getCount() : 0);
        }
        final Snapshot snapshot = new Snapshot(locations.values().stream().mapToInt(Integer::intValue).sum(), Map.copyOf(locations));
        Log.getLogger().info("P10C physical plank ledger total={} locations={}", snapshot.planks(), snapshot.locations());
        return snapshot;
    }

    private static void add(final GameTestHelper helper, final Map<String, Integer> locations, final Set<IItemHandler> seen,
      final String label, final IItemHandler inventory)
    {
        helper.assertTrue(inventory != null, "physical ledger inventory unavailable: " + label);
        if (!seen.add(inventory)) { return; }
        int count = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final var stack = inventory.getStackInSlot(slot);
            if (stack.is(Items.OAK_PLANKS)) { count += stack.getCount(); }
        }
        locations.put(label, count);
    }
}
