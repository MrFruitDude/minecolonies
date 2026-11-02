package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.entity.citizen.VisibleCitizenStatus;
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
        farmerHarvest(helper, false);
    }

    static void farmerHarvestLeisure(final GameTestHelper helper)
    {
        farmerHarvest(helper, true);
    }

    private static void farmerHarvest(final GameTestHelper helper, final boolean earnedLeisure)
    {
        final RsFixture fixture = RsFixture.found(helper, "farmer harvest journey", 2);
        final var hiring = fixture.colony.getSettings().getSetting(BuildingTownHall.AUTO_HIRING_MODE);
        if (hiring.getValue()) { hiring.trigger(); }
        helper.assertTrue(WorkerJourneyGameTests.class.getResource("/blueprints/minecolonies/original/agriculture/horticulture/farmer1.blueprint") != null, "farmer fixture blueprint missing from runtime");
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
            final boolean[] leisureSeeded = {false};
            final boolean[] heldHarvestStatusObserved = {false};
            ForestryGameTests.pump(helper, List.of(citizen), 16_000, () -> {
                final int mature = (int) crops.stream().filter(pos -> fixture.level.getBlockState(pos).is(Blocks.WHEAT)
                  && fixture.level.getBlockState(pos).getValue(CropBlock.AGE) == 7).count();
                final int carried = InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), stack -> stack.is(Items.WHEAT));
                final int stored = InventoryUtils.getCountFromBuilding(hut, stack -> stack.is(Items.WHEAT));
                final var ai = entity.getCitizenJobHandler().getWorkAI();
                last[0] = "mature=" + mature + " carried=" + carried + " stored=" + stored + " fetchedHoe=" + fetchedHoe[0]
                  + " moved=" + moved[0] + " visibleStatus=" + statusLabel(citizen.getStatus()) + " heldStatusObserved=" + heldHarvestStatusObserved[0] + " citizenState=" + entity.getCitizenAI().getState() + " dumpFlag=" + readFlag(ai, "shouldDumpInventory") + " gainBaseline=" + readFlag(ai, "itemsAfterLastDump")
                  + " fieldSteps=" + readFlag(ai, "fieldStepsSinceDump") + " colonyDay=" + fixture.colony.getDay() + " state=" + (ai == null ? "none" : ai.getState()) + " position=" + entity.position() + " field=" + field.getFieldStage();
                if (mature == 0 && stored == 8 && carried == 0 && fetchedHoe[0] && moved[0])
                {
                    helper.assertTrue(!earnedLeisure || leisureSeeded[0] && citizen.getLeisureTime() > 0,
                      "harvest was not delivered while earned leisure remained: seeded=" + leisureSeeded[0] + " remaining=" + citizen.getLeisureTime());
                    helper.assertTrue(fixture.level.getBlockState(scarecrow).is(ModBlocks.blockScarecrow), "farmer broke the scarecrow");
                    helper.assertTrue(citizen.getJob() instanceof com.minecolonies.core.colony.jobs.JobFarmer, "farmer lost its job");
                    if (earnedLeisure)
                    {
                        helper.assertTrue(heldHarvestStatusObserved[0], "farmer never showed its work status while holding its harvest");
                        // Let the native citizen decision publish the break status after delivery.
                        if (citizen.getStatus() != VisibleCitizenStatus.LEISURE) { return false; }
                    }
                    Log.getLogger().info("[farmer harvest journey] {}", last[0]);
                    return true;
                }
                return false;
            }, () -> last[0], () -> {
                helper.assertTrue(entity.isAlive(), "farmer died during native work");
                final var work = (com.minecolonies.core.entity.ai.workers.production.agriculture.EntityAIWorkFarmer) entity.getCitizenJobHandler().getWorkAI();
                if (earnedLeisure && !leisureSeeded[0] && "true".equals(readFlag(work, "shouldDumpInventory")))
                {
                    helper.assertTrue(work.getState() != com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState.INVENTORY_FULL,
                      "leisure fixture missed the pending window before active dumping");
                    final var status = citizen.getStatus();
                    helper.assertTrue(status == VisibleCitizenStatus.WORKING || status != null
                        && "com.minecolonies.gui.visiblestatus.farmer".equals(status.getTranslationKey())
                        && "minecolonies:textures/icons/work/farmer.png".equals(status.getIcon().toString()),
                      "pending harvest showed " + statusLabel(status) + " instead of its working or farming status");
                    Log.getLogger().info("[farmer held harvest status] {}", statusLabel(status));
                    heldHarvestStatusObserved[0] = true;
                    seedLeisure(citizen, 3600);
                    leisureSeeded[0] = true;
                    helper.assertTrue(citizen.getLeisureTime() == 3600 && citizen.getJob().canAIBeInterrupted(), "leisure fixture did not activate the earned break branch");
                    Log.getLogger().info("[farmer leisure boundary] state={} citizenState={} pending={} leisure={} canTakeLeisure={}",
                      work.getState(), entity.getCitizenAI().getState(), readFlag(work, "shouldDumpInventory"), citizen.getLeisureTime(), work.canTakeLeisure());
                    helper.assertTrue(!work.canTakeLeisure(), "farmer accepted leisure with pending harvest");
                }
                fetchedHoe[0] |= InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), stack -> stack.is(Items.STONE_HOE)) == 1;
                moved[0] |= entity.position().distanceToSqr(start) >= 9;
                final long elapsed = fixture.level.getGameTime();
                if (elapsed % 1000 == 0) { Log.getLogger().info("[farmer harvest progress] {}", last[0]); }
            }, earnedLeisure ? "farmer_harvest_leisure" : "farmer_harvest_journey");
        });
    }

    private static String statusLabel(final VisibleCitizenStatus status)
    {
        return status == null ? "none" : status.getId() + ":" + status.getTranslationKey() + ":" + status.getIcon();
    }

    /** Seed an earned break once; leave state choice, physics, elapsed leisure and dumping to native AI. */
    private static void seedLeisure(final Object citizen, final int ticks)
    {
        for (Class<?> type = citizen.getClass(); type != null; type = type.getSuperclass())
        {
            try
            {
                final var field = type.getDeclaredField("leisureTime");
                field.setAccessible(true);
                field.setInt(citizen, ticks);
                return;
            }
            catch (final NoSuchFieldException missing) { /* Look through citizen hierarchy. */ }
            catch (final ReflectiveOperationException failure) { throw new IllegalStateException("cannot seed earned leisure", failure); }
        }
        throw new IllegalStateException("citizen leisure field missing");
    }

    private static String readFlag(final Object object, final String name)
    {
        if (object == null) { return "no AI"; }
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass())
        {
            try
            {
                final var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return String.valueOf(field.get(object));
            }
            catch (final NoSuchFieldException missing) { /* Look through the AI hierarchy without running stateful getters. */ }
            catch (final ReflectiveOperationException | RuntimeException failure) { return "unavailable:" + failure.getClass().getSimpleName(); }
        }
        return "missing:" + name;
    }
}
