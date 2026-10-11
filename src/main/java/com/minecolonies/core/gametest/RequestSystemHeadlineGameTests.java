package com.minecolonies.core.gametest;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.ICraftingBuildingModule;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.crafting.RecipeStorage;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.core.colony.requestsystem.RsFlags;
import com.minecolonies.core.colony.requestsystem.RsStats;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * The acceptance test of the request-system redesign: a fresh colony, a hut placed with zero materials in stock, and the
 * materials get crafted and gathered until the hut is done. No request is overruled, nothing is created by the test
 * except what a lumberjack and a miner would bring to the warehouse.
 * <p>
 * The "hut" is a small bounded blueprint (3 cobblestone, 6 oak planks) so the test is about the request chain, not the
 * builder's speed: the planks must be crafted from logs by the sawmill's worker, the cobblestone must come from the
 * warehouse once the miner delivered it, and the courier must carry every stack to the builder. The gatherers are played by
 * the test: the logs reach the warehouse after {@link #LOGS_AT} ticks, the cobblestone after {@link #COBBLE_AT}.
 * <p>
 * It reports, per run, the time to completion, the requests created, the deliveries created, the courier tasks finished and
 * the number of requests that fell back to the player. Two registrations (flags off, flags on) share this body.
 */
public final class RequestSystemHeadlineGameTests
{
    /**
     * Ticks after the work order was armed at which the lumberjack's logs reach the warehouse.
     */
    private static final int LOGS_AT = 600;

    /**
     * Ticks after the work order was armed at which the miner's cobblestone reaches the warehouse.
     */
    private static final int COBBLE_AT = 1500;

    private static final int COBBLE = 3;
    private static final int PLANKS = 6;
    private static final int LOGS    = 2;

    private RequestSystemHeadlineGameTests()
    {
    }

    public static void headline(final GameTestHelper helper, final boolean redesign)
    {
        headline(helper, redesign, false);
    }

    /**
     * @param redesign    whether the reservation ledger and the smart retry are switched on.
     * @param splitSupply whether the gatherers bring their goods in two trips (one log, then one; two cobblestone, then one)
     *                    instead of all at once: a stock that is short for a while, as it is in a real colony.
     */
    public static void headline(final GameTestHelper helper, final boolean redesign, final boolean splitSupply)
    {
        RsFlags.overrideReservations(redesign);
        RsFlags.overrideSmartRetry(redesign);
        RsStats.reset();

        final RsFixture f = RsFixture.found(helper, "rs-headline-" + (splitSupply ? "split-" : "") + (redesign ? "on" : "off"), 6);
        final IBuilding sawmill = f.place(ModBlocks.blockHutSawmill, new BlockPos(8, 1, 22), "craftsmanship/carpentry/sawmill1.blueprint");
        final IBuilding deliveryman = f.place(ModBlocks.blockHutDeliveryman, new BlockPos(32, 1, 2), "craftsmanship/storage/deliveryman1.blueprint");
        // The colony has a lumberjack and a miner (their workers are played by the test, see LOGS_AT and COBBLE_AT).
        f.place(ModBlocks.blockHutLumberjack, new BlockPos(24, 1, 22), "fundamentals/lumberjack1.blueprint");
        f.place(ModBlocks.blockHutMiner, new BlockPos(32, 1, 22), "fundamentals/miner1.blueprint");
        for (final IBuilding building : List.of(f.builder, sawmill, deliveryman, f.warehouse))
        {
            final BlockPos a = building.getCorners().getA();
            final BlockPos b = building.getCorners().getB();
            for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++)
            {
                for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++)
                {
                    f.level.setChunkForced(cx, cz, true);
                }
            }
        }

        helper.runAfterDelay(200, () -> {
            final ICitizenData builderCitizen = f.spawn(new BlockPos(12, 1, 8), false);
            final ICitizenData sawmillCitizen = f.spawn(new BlockPos(10, 1, 28), false);
            final ICitizenData courierCitizen = f.spawn(new BlockPos(34, 1, 8), false);
            helper.assertTrue(f.builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(builderCitizen), "builder not assigned");
            helper.assertTrue(sawmill.getModule(BuildingModules.SAWMILL_WORK).assignCitizen(sawmillCitizen), "sawmill worker not assigned");
            helper.assertTrue(deliveryman.getModule(BuildingModules.COURIER_WORK).assignCitizen(courierCitizen), "courier not assigned to the courier hut");
            helper.assertTrue(f.warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courierCitizen), "courier not assigned to the warehouse");
            builderCitizen.getEntity().ifPresent(e -> e.setPos(f.builder.getID().getX() + 0.5D, f.builder.getID().getY(), f.builder.getID().getZ() + 0.5D));
            sawmillCitizen.getEntity().ifPresent(e -> e.setPos(sawmill.getID().getX() + 0.5D, sawmill.getID().getY(), sawmill.getID().getZ() + 0.5D));
            courierCitizen.getEntity().ifPresent(e -> e.setPos(deliveryman.getID().getX() + 0.5D, deliveryman.getID().getY(), deliveryman.getID().getZ() + 0.5D));

            final ICraftingBuildingModule craft = sawmill.getModule(BuildingModules.SAWMILL_CRAFT);
            final RecipeStorage recipe = RecipeStorage.builder()
              .withRecipeId(null) // no source: the colony view would drop a recipe the datapack does not know
              .withInputs(List.of(new ItemStorage(new ItemStack(Items.OAK_LOG, 1))))
              .withPrimaryOutput(new ItemStack(Items.OAK_PLANKS, 4))
              .build();
            helper.assertTrue(craft.addRecipe(IColonyManager.getInstance().getRecipeManager().checkOrAddRecipe(recipe)), "sawmill rejected the recipe");

            // The hut: a line of 3 cobblestone and 6 planks. Nothing is in stock anywhere.
            final BuildingBuilder builderBuilding = (BuildingBuilder) f.builder;
            final List<BlockPos> cells = new ArrayList<>();
            for (int i = 0; i < COBBLE + PLANKS; i++)
            {
                cells.add(f.builder.getID().offset(0, 0, 1 + i));
            }
            for (final BlockPos cell : cells)
            {
                helper.assertTrue(f.level.getBlockState(cell).isAir(), "the hut site is not empty at " + cell + ": " + f.level.getBlockState(cell));
            }
            builderBuilding.resetNeededResources();
            final WorkOrderBuilding order = WorkOrderBuilding.create(WorkOrderType.BUILD, f.builder);
            f.colony.getWorkManager().addWorkOrder(order, false);
            order.setClaimedBy(f.builder.getID());
            builderBuilding.setWorkOrder(order);
            final Blueprint blueprint = new Blueprint((short) 1, (short) 1, (short) 12)
              .setName("RS headline hut")
              .setFileName("rs_headline_hut")
              .setPackName("Minecolonies Original");
            for (int i = 0; i < COBBLE + PLANKS; i++)
            {
                blueprint.addBlockState(new BlockPos(0, 0, 1 + i), i < COBBLE ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState());
            }
            blueprint.setCachePrimaryOffset(BlockPos.ZERO);
            order.setBlueprint(blueprint, f.level);
            order.setRequested(false);
            order.setCleared(false);

            final long start = f.level.getGameTime();
            final boolean[] logsSent = {false, false};
            final boolean[] cobbleSent = {false, false};
            final long[] lastLog = {start};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                f.level.getServer().clockManager().setTotalTicks(f.level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
                final long elapsed = f.level.getGameTime() - start;
                if (!logsSent[0] && elapsed >= LOGS_AT)
                {
                    logsSent[0] = true;
                    f.stock(0, new ItemStack(Items.OAK_LOG, splitSupply ? LOGS / 2 : LOGS));
                }
                if (splitSupply && !logsSent[1] && elapsed >= LOGS_AT + 400)
                {
                    logsSent[1] = true;
                    f.stock(0, new ItemStack(Items.OAK_LOG, LOGS - LOGS / 2));
                }
                if (!cobbleSent[0] && elapsed >= COBBLE_AT)
                {
                    cobbleSent[0] = true;
                    f.stock(1, new ItemStack(Items.COBBLESTONE, splitSupply ? COBBLE - 1 : COBBLE));
                }
                if (splitSupply && !cobbleSent[1] && elapsed >= COBBLE_AT + 400)
                {
                    cobbleSent[1] = true;
                    f.stock(1, new ItemStack(Items.COBBLESTONE, 1));
                }
                int placed = 0;
                for (int i = 0; i < cells.size(); i++)
                {
                    final Block want = i < COBBLE ? Blocks.COBBLESTONE : Blocks.OAK_PLANKS;
                    if (f.level.getBlockState(cells.get(i)).is(want))
                    {
                        placed++;
                    }
                }
                if (f.level.getGameTime() - lastLog[0] >= 500)
                {
                    lastLog[0] = f.level.getGameTime();
                    Log.getLogger().info("RS headline progress flags={} t={} placed={}/{} requests={} deliveries={} courierTasks={} playerFallbacks={} open={}",
                      redesign ? "on" : "off", elapsed, placed, cells.size(), RsStats.requestsCreated, RsStats.deliveriesCreated, RsStats.courierTasksFinished, RsStats.playerFallbacks,
                      openRequests(f));
                }
                if (placed == cells.size() && !builderBuilding.hasWorkOrder() && f.colony.getWorkManager().getWorkOrder(order.getID()) == null)
                {
                    Log.getLogger().info("RS_HEADLINE supply={} flags={} ticksToComplete={} requestsCreated={} deliveriesCreated={} courierTasks={} playerFallbacks={}",
                      splitSupply ? "split" : "whole", redesign ? "on" : "off", elapsed, RsStats.requestsCreated, RsStats.deliveriesCreated, RsStats.courierTasksFinished, RsStats.playerFallbacks);
                    // The old system hands a request to the player when only part of its stock has arrived; the redesign keeps waiting.
                    // Flags off is a measurement, flags on is the requirement.
                    helper.assertTrue(!redesign || RsStats.playerFallbacks == 0,
                      "the hut was finished without the player supplying anything, but " + RsStats.playerFallbacks + " requests fell back to the player");
                    helper.succeed();
                    return;
                }
                if (elapsed > 55_000)
                {
                    helper.fail("the hut was not finished after " + elapsed + " ticks: placed " + placed + "/" + cells.size() + ", open requests " + openRequests(f)
                                  + ", requests=" + RsStats.requestsCreated + ", deliveries=" + RsStats.deliveriesCreated + ", fallbacks=" + RsStats.playerFallbacks);
                    return;
                }
                helper.runAfterDelay(20, () -> poll[0].run());
            };
            helper.runAfterDelay(20, () -> poll[0].run());
        });
    }

    private static String openRequests(final RsFixture f)
    {
        final List<String> open = new ArrayList<>();
        for (final IRequest<?> request : f.standard().getRequestIdentitiesDataStore().getIdentities().values())
        {
            open.add(request.getRequest().getClass().getSimpleName() + ":" + request.getState() + "/" + request.getWaitReason());
        }
        return open.toString();
    }
}
