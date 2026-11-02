package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.buildingextensions.FarmField;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import java.util.ArrayList;
import java.util.List;

/** Representative native work journeys on the shipping production jar, without forced work states or movement. */
final class WorkerJourneyGameTests
{
    private WorkerJourneyGameTests() { }

    static void farmerHarvest(final GameTestHelper helper)
    {
        final RsFixture fixture = RsFixture.found(helper, "farmer harvest journey", 2);
        final var hiring = fixture.colony.getSettings().getSetting(BuildingTownHall.AUTO_HIRING_MODE);
        if (hiring.getValue()) { hiring.trigger(); }
        helper.assertTrue(WorkerJourneyGameTests.class.getClassLoader().getResource("blueprints/minecolonies/original/agriculture/horticulture/farmer1.blueprint") != null, "farmer fixture blueprint missing from runtime");
        final var hut = fixture.place(ModBlocks.blockHutFarmer, new BlockPos(8, 1, 24), "agriculture/horticulture/farmer1.blueprint");
        final BlockPos scarecrow = helper.absolutePos(new BlockPos(26, 1, 20));
        fixture.level.setBlockAndUpdate(scarecrow, ModBlocks.blockScarecrow.defaultBlockState());
        final FarmField field = FarmField.create(scarecrow, fixture.level);
        helper.assertTrue(field != null, "native farm field creation failed");
        field.setSeed(new ItemStack(Items.WHEAT_SEEDS));
        for (final Direction direction : Direction.Plane.HORIZONTAL) { field.setRadius(direction, 1); }
        field.setFieldStage(FarmField.Stage.PLANTED);
        helper.assertTrue(fixture.colony.getServerBuildingManager().addBuildingExtension(field), "field registration failed");
        helper.assertTrue(hut.getModule(BuildingModules.FARMER_FIELDS).assignExtension(field), "farmer field assignment failed");
        final List<BlockPos> crops = new ArrayList<>();
        for (int x = -1; x <= 1; x++)
        {
            for (int z = -1; z <= 1; z++)
            {
                if (x == 0 && z == 0) { continue; }
                final BlockPos crop = scarecrow.offset(x, 0, z);
                fixture.level.setBlockAndUpdate(crop.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7));
                fixture.level.setBlockAndUpdate(crop, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
                crops.add(crop);
            }
        }
        helper.assertTrue(InventoryUtils.forceItemStackToItemHandler(hut.getItemHandlerCap(), new ItemStack(Items.STONE_HOE), stack -> true).isEmpty(), "hoe did not fit in farmer hut");
        helper.assertTrue(InventoryUtils.getCountFromBuilding(hut, stack -> stack.is(Items.WHEAT)) == 0, "farmer fixture starts with wheat stock");
        helper.runAfterDelay(100, () -> {
            final var citizen = fixture.spawn(new BlockPos(14, 1, 16), false);
            for (int slot = 0; slot < citizen.getInventory().getSlots(); slot++) { citizen.getInventory().setStackInSlot(slot, ItemStack.EMPTY); }
            helper.assertTrue(hut.getModule(BuildingModules.FARMER_CRAFT).assignCitizen(citizen), "native farmer assignment failed");
            final var entity = (com.minecolonies.core.entity.citizen.EntityCitizen) citizen.getEntity().orElseThrow();
            final var start = entity.position();
            final boolean[] fetchedHoe = {false};
            final boolean[] moved = {false};
            final String[] last = {"not observed"};
            ForestryGameTests.pump(helper, List.of(citizen), 16_000, () -> {
                final int mature = (int) crops.stream().filter(pos -> fixture.level.getBlockState(pos).is(Blocks.WHEAT)
                  && fixture.level.getBlockState(pos).getValue(CropBlock.AGE) == 7).count();
                final int carried = InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), stack -> stack.is(Items.WHEAT));
                final int stored = InventoryUtils.getCountFromBuilding(hut, stack -> stack.is(Items.WHEAT));
                final var ai = entity.getCitizenJobHandler().getWorkAI();
                last[0] = "mature=" + mature + " carried=" + carried + " stored=" + stored + " fetchedHoe=" + fetchedHoe[0]
                  + " moved=" + moved[0] + " citizenState=" + entity.getCitizenAI().getState() + " dumpFlag=" + readFlag(ai, "shouldDumpInventory") + " state=" + (ai == null ? "none" : ai.getState()) + " position=" + entity.position() + " field=" + field.getFieldStage();
                if (mature == 0 && stored == 8 && carried == 0 && fetchedHoe[0] && moved[0])
                {
                    helper.assertTrue(fixture.level.getBlockState(scarecrow).is(ModBlocks.blockScarecrow), "farmer broke the scarecrow");
                    helper.assertTrue(citizen.getJob() instanceof com.minecolonies.core.colony.jobs.JobFarmer, "farmer lost its job");
                    Log.getLogger().info("[farmer harvest journey] {}", last[0]);
                    return true;
                }
                return false;
            }, () -> last[0], () -> {
                helper.assertTrue(entity.isAlive(), "farmer died during native work");
                fetchedHoe[0] |= InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), stack -> stack.is(Items.STONE_HOE)) == 1;
                moved[0] |= entity.position().distanceToSqr(start) >= 9;
                final long elapsed = fixture.level.getGameTime();
                if (elapsed % 1000 == 0) { Log.getLogger().info("[farmer harvest progress] {}", last[0]); }
            }, "farmer_harvest_journey");
        });
    }

    private static String readFlag(final Object object, final String name)
    {
        if (object == null) { return "no AI"; }
        try
        {
            final var field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return String.valueOf(field.get(object));
        }
        catch (final ReflectiveOperationException failure) { throw new IllegalStateException("farmer diagnostic field missing", failure); }
    }
}
