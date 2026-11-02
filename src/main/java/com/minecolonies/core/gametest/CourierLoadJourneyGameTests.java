package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.ldtteam.structurize.api.compat.itemhandler.IItemHandler;
import com.ldtteam.structurize.api.compat.itemhandler.wrapper.InvWrapper;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Finite multi-stack demand at two huts, served by one native courier without injected stock or work states. */
final class CourierLoadJourneyGameTests
{
    static void register(final RegisterGameTestsEvent event)
    {
        final String name = "journey_courier_multistack";
        event.registerTest(Identifier.fromNamespaceAndPath("minecolonies", name),
          info -> new MinecoloniesGameTestInstance(info, CourierLoadJourneyGameTests::run),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, name));
    }

    private static boolean goods(final ItemStack item)
    {
        return item.is(Items.OAK_LOG) || item.is(Items.BIRCH_LOG);
    }

    private static int count(final IItemHandler inventory)
    {
        return InventoryUtils.getItemCountInItemHandler(inventory, CourierLoadJourneyGameTests::goods);
    }

    private static void run(final GameTestHelper helper)
    {
        final RsFixture f = RsFixture.found(helper, "courier multi-stack", 4);
        final var deliveryHut = f.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");
        final var far = f.place(ModBlocks.blockHutBuilder, new BlockPos(4, 1, 26), "fundamentals/builder1.blueprint");
        f.stock(0, new ItemStack(Items.OAK_LOG, 64));
        f.stock(1, new ItemStack(Items.OAK_LOG, 64));
        f.stock(2, new ItemStack(Items.BIRCH_LOG, 64));
        helper.runAfterDelay(200, () -> {
            final var courier = f.spawn(new BlockPos(18, 1, 8), false);
            helper.assertTrue(deliveryHut.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier), "courier assignment failed");
            helper.assertTrue(f.warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier), "warehouse courier assignment failed");
            courier.setSaturation(20);
            final JobDeliveryman job = courier.getJob(JobDeliveryman.class);
            helper.assertTrue(count(f.builder.getItemHandlerCap()) == 0 && count(far.getItemHandlerCap()) == 0 && count(courier.getInventory()) == 0,
              "multi-stack targets or courier already hold logs");
            final var oakRequest = f.builder.createRequest(new Stack(new ItemStack(Items.OAK_LOG), 128, 128), false);
            final var birchRequest = far.createRequest(new Stack(new ItemStack(Items.BIRCH_LOG), 64, 64), false);
            final long start = f.level.getGameTime();
            final int[] maxCarried = {0};
            final long[] finished = {-1};
            final String[] prior = {""};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                courier.setSaturation(20);
                helper.assertTrue(courier.getEntity().isPresent() && courier.getEntity().orElseThrow().isAlive(), "courier disappeared");
                final int oak = InventoryUtils.getItemCountInItemHandler(f.builder.getItemHandlerCap(), s -> s.is(Items.OAK_LOG));
                final int birch = InventoryUtils.getItemCountInItemHandler(far.getItemHandlerCap(), s -> s.is(Items.BIRCH_LOG));
                final int carried = count(courier.getInventory());
                maxCarried[0] = Math.max(maxCarried[0], carried);
                final Map<String, Integer> physical = new LinkedHashMap<>();
                for (int i = 0; i < f.racks.size(); i++) { physical.put("warehouse-rack/" + i, count(f.rack(i).getInventory())); }
                physical.put("builder", count(f.builder.getItemHandlerCap()));
                physical.put("far", count(far.getItemHandlerCap()));
                physical.put("delivery-hut", count(deliveryHut.getItemHandlerCap()));
                physical.put("courier", carried);
                physical.put("owner", count(new InvWrapper(f.player.getInventory())));
                final AABB area = new AABB(Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(-8, 0, -8))), Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(48, 32, 32))));
                int dropped = 0;
                for (final ItemEntity item : f.level.getEntitiesOfClass(ItemEntity.class, area))
                {
                    if (goods(item.getItem())) { dropped += item.getItem().getCount(); }
                }
                physical.put("dropped", dropped);
                helper.assertTrue(physical.values().stream().mapToInt(Integer::intValue).sum() == 192, "multi-stack physical conservation failed: " + physical);
                final String state = "oak=" + oak + " birch=" + birch + " carried=" + carried + " queue=" + job.getTaskQueue().size()
                  + " work=" + (job.getWorkerAI() == null ? "none" : job.getWorkerAI().getState());
                if (!state.equals(prior[0]))
                {
                    prior[0] = state;
                    Log.getLogger().info("JOURNEY courier elapsed={} {}", f.level.getGameTime() - start, state);
                }
                helper.assertTrue(oak <= 128 && birch <= 64, "courier over-delivered competing requests: " + state);
                if (oak == 128 && birch == 64 && carried == 0)
                {
                    if (finished[0] < 0) { finished[0] = f.level.getGameTime(); }
                    if (f.level.getGameTime() - finished[0] >= 160)
                    {
                        helper.assertTrue(maxCarried[0] >= 128, "courier completed demand without carrying the two-stack load: peak=" + maxCarried[0]);
                        helper.assertTrue(physical.get("dropped") == 0 && physical.get("delivery-hut") == 0,
                          "delivered stock is elsewhere: " + physical);
                        Log.getLogger().info("JOURNEY courier complete elapsed={} peakCarried={} physical={} requests={} / {}",
                          f.level.getGameTime() - start, maxCarried[0], physical, f.describe(oakRequest), f.describe(birchRequest));
                        helper.succeed();
                        return;
                    }
                }
                helper.assertTrue(f.level.getGameTime() - start < 9000, "competing multi-stack jobs did not finish: " + state
                  + " requests=" + f.describe(oakRequest) + " / " + f.describe(birchRequest));
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> poll[0].run());
        });
    }
}
