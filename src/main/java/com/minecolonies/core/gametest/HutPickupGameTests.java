package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.items.component.ColonyId;
import com.minecolonies.api.items.component.HutBlockData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * The hut window's "Pick up" button (BuildPickUpMessage -> AbstractBuilding#pickUp) with a connected player: the hut item
 * lands in the live inventory and reaches the client, exactly once, with its level and colony; a full inventory keeps the
 * building; creative is not a way to lose it.
 */
final class HutPickupGameTests
{
    private static final String BLUEPRINT = "fundamentals/lumberjack1.blueprint";

    private HutPickupGameTests()
    {
    }

    public static void hutPickupNativePath(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "hut pickup");
        final Block hut = ModBlocks.blockHutLumberjack;

        // Survival, level 0, empty inventory: one hut item with level 0 and this colony, in the player's own inventory
        // and in the slot packets the client gets; building and block gone.
        final ServerPlayer survival = MinecoloniesGameTests.makeConnectedPlayer(helper, GameType.SURVIVAL);
        final IBuilding planned = place(helper, colony, hut, new BlockPos(14, 1, 4), 0);
        final BlockPos plannedPos = planned.getPosition();
        survival.getInventory().clearContent();
        survival.containerMenu.broadcastChanges();
        MinecoloniesGameTests.drainOutbound(survival);
        request(colony, plannedPos, survival);
        helper.assertTrue(colony.getServerBuildingManager().getBuilding(plannedPos) == null, "level-0 building still registered after pick up");
        helper.assertTrue(helper.getLevel().getBlockState(plannedPos).isAir(), "hut block still placed after pick up");
        expectOneHut(helper, survival, hut, 0, colony, "survival pick up");
        survival.containerMenu.broadcastChanges();
        final List<ItemStack> sent = sentInventoryStacks(survival);
        helper.assertTrue(sent.stream().filter(stack -> stack.is(hut.asItem())).count() == 1, "client was not sent the hut item: " + sent);

        // The same request again (a double click, a second packet): the building is gone, nothing more is handed out.
        request(colony, plannedPos, survival);
        expectOneHut(helper, survival, hut, 0, colony, "repeated request");

        // A deconstructed level-2 building comes back at level 2.
        final IBuilding down = place(helper, colony, hut, new BlockPos(24, 1, 4), 2);
        down.setDeconstructed();
        survival.getInventory().clearContent();
        request(colony, down.getPosition(), survival);
        helper.assertTrue(colony.getServerBuildingManager().getBuilding(down.getPosition()) == null, "deconstructed building still registered");
        expectOneHut(helper, survival, hut, 2, colony, "deconstructed pick up");

        // Full inventory (every slot a hut could go into): the building, its block and the inventory stay as they were.
        final IBuilding kept = place(helper, colony, hut, new BlockPos(14, 1, 14), 0);
        final Inventory inventory = survival.getInventory();
        inventory.clearContent();
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++)
        {
            inventory.getNonEquipmentItems().set(slot, new ItemStack(Items.DIRT));
        }
        inventory.setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.DIRT));
        request(colony, kept.getPosition(), survival);
        request(colony, kept.getPosition(), survival);
        helper.assertTrue(colony.getServerBuildingManager().getBuilding(kept.getPosition()) == kept, "full inventory removed the building");
        helper.assertTrue(helper.getLevel().getBlockState(kept.getPosition()).is(hut), "full inventory removed the hut block");
        helper.assertTrue(count(survival, hut) == 0, "full inventory still got a hut item");
        inventory.clearContent();
        request(colony, kept.getPosition(), survival);
        expectOneHut(helper, survival, hut, 0, colony, "pick up after making room");

        // Creative: the hut is handed over like in survival, not dropped.
        final ServerPlayer creative = MinecoloniesGameTests.makeConnectedPlayer(helper, GameType.CREATIVE);
        helper.assertTrue(creative.isCreative(), "fixture: the creative player is not creative");
        final IBuilding creativeHut = place(helper, colony, hut, new BlockPos(24, 1, 14), 0);
        creative.getInventory().clearContent();
        request(colony, creativeHut.getPosition(), creative);
        helper.assertTrue(colony.getServerBuildingManager().getBuilding(creativeHut.getPosition()) == null, "creative pick up kept the building");
        expectOneHut(helper, creative, hut, 0, colony, "creative pick up");

        helper.succeed();
    }

    private static IBuilding place(final GameTestHelper helper, final IColony colony, final Block hut, final BlockPos rel, final int level)
    {
        final IBuilding building = MinecoloniesGameTests.placeProductionBuilding(helper, colony, hut, rel, BLUEPRINT);
        building.setBuildingLevel(level);
        return building;
    }

    /** What BuildPickUpMessage does on the server: look the building up by position, pick it up if it is still there. */
    private static void request(final IColony colony, final BlockPos pos, final ServerPlayer player)
    {
        final IBuilding building = colony.getServerBuildingManager().getBuilding(pos);
        if (building != null)
        {
            building.pickUp(player);
        }
    }

    private static int count(final ServerPlayer player, final Block hut)
    {
        int count = 0;
        final Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++)
        {
            final ItemStack stack = inventory.getItem(slot);
            if (stack.is(hut.asItem()))
            {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void expectOneHut(final GameTestHelper helper, final ServerPlayer player, final Block hut, final int level, final IColony colony, final String what)
    {
        helper.assertTrue(count(player, hut) == 1, what + ": expected exactly one hut item, found " + count(player, hut));
        final ItemStack stack = player.getInventory().getNonEquipmentItems().stream().filter(s -> s.is(hut.asItem())).findFirst()
                                  .orElse(player.getInventory().getItem(Inventory.SLOT_OFFHAND));
        helper.assertTrue(stack.is(hut.asItem()), what + ": hut item is not in a main or offhand slot");
        final HutBlockData data = HutBlockData.readFromItemStack(stack);
        helper.assertTrue(data != null && data.level() == level && !data.pastable(), what + ": wrong hut data " + data);
        final ColonyId id = ColonyId.readFromItemStack(stack);
        helper.assertTrue(id.hasColonyId() && id.id() == colony.getID(), what + ": wrong colony on the hut item " + id);
    }

    /** The inventory-menu stacks the server sent to the player since the last drain (slot and content packets). */
    private static List<ItemStack> sentInventoryStacks(final ServerPlayer player)
    {
        final List<ItemStack> out = new ArrayList<>();
        final int menu = player.inventoryMenu.containerId;
        for (final Object packet : MinecoloniesGameTests.drainOutbound(player))
        {
            collect(packet, menu, out);
        }
        return out;
    }

    private static void collect(final Object packet, final int menu, final List<ItemStack> out)
    {
        if (packet instanceof final ClientboundContainerSetSlotPacket slot && slot.getContainerId() == menu)
        {
            out.add(slot.getItem());
        }
        else if (packet instanceof final ClientboundContainerSetContentPacket content && content.containerId() == menu)
        {
            out.addAll(content.items());
        }
        else if (packet instanceof final BundlePacket<?> bundle)
        {
            bundle.subPackets().forEach(sub -> collect(sub, menu, out));
        }
    }
}
