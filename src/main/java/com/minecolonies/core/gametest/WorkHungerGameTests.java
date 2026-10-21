package com.minecolonies.core.gametest;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.util.RotationMirror;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.util.FoodUtils;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.CitizenData;
import com.minecolonies.core.colony.workorders.WorkOrderDecoration;
import com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder;
import com.minecolonies.core.entity.ai.workers.util.WorkerSettings;
import com.minecolonies.core.entity.citizen.CitizenSnack;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.jetbrains.annotations.Nullable;

import static com.minecolonies.api.colony.ICitizenData.MAX_SATURATION;

/**
 * Round 10 (work and hunger): the builder goes from one finished order straight to the next, a hungry worker eats what he carries without leaving his
 * work, and new citizens start with different saturations.
 */
public final class WorkHungerGameTests
{
    private WorkHungerGameTests()
    {
    }

    /**
     * Two small orders for one builder: the first in his hut, the second queued for his hut. Once the first is done he starts the second without a walk to
     * his hut (about 18 blocks away) in between.
     */
    public static void nextOrderWithoutHutWalk(final GameTestHelper helper)
    {
        // physical reach without scaffolding: two flat 3 by 3 floors need neither
        WorkerSettings.overrideReachForTests(null, true, false, 0);
        final int skipped0 = EntityAIStructureBuilder.hutWalksSkipped.get();
        WorkerEfficiencyGameTests.scene(helper, "WHUNGER next order", live -> {
            live.setSkills(40, 40);
            live.giveTools();
            final Blueprint first = BuilderScaffoldGameTests.shape("next_order_a", 3, 1, 3, (x, y, z) -> true);
            final Blueprint second = BuilderScaffoldGameTests.shape("next_order_b", 3, 1, 3, (x, y, z) -> true);
            // he carries the blocks of both: no trip for materials
            InventoryUtils.addItemStackToItemHandler(live.citizen.getInventory(), new ItemStack(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), 32));
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), WorkerEfficiencyGameTests.SPARE);
            final WorkOrderDecoration a = live.orderFor("next_order_a", new BlockPos(26, 1, 12), first);
            final WorkOrderDecoration b = WorkOrderDecoration.create(WorkOrderType.BUILD, "Minecolonies Original", "gametest/next_order_b.blueprint", "gametest.next_order_b",
              helper.absolutePos(new BlockPos(26, 1, 18)), RotationMirror.NONE, 0);
            b.setBlueprint(second, live.level);
            live.colony.getWorkManager().addWorkOrder(b, true);
            b.setClaimedBy(live.hut.getID());
            final BlockPos hut = live.hut.getPosition();
            final int[] aDone = {-1};
            final double[] minDistance = {Double.MAX_VALUE};
            WorkerEfficiencyGameTests.everyTick(live, 8000, tick -> {
                final boolean aGone = live.colony.getWorkManager().getWorkOrder(a.getID()) == null;
                final boolean bGone = live.colony.getWorkManager().getWorkOrder(b.getID()) == null;
                if (aGone && aDone[0] < 0)
                {
                    aDone[0] = tick;
                    Log.getLogger().info("WHUNGER next order: first done at t={} {}", tick, live.ai());
                }
                if (aDone[0] >= 0)
                {
                    final Vec3 at = live.citizen.getEntity().map(e -> e.position()).orElse(Vec3.ZERO);
                    minDistance[0] = Math.min(minDistance[0], Math.hypot(at.x - hut.getX() - 0.5D, at.z - hut.getZ() - 0.5D));
                }
                return aGone && bGone && !live.hut.hasWorkOrder();
            }, () -> {
                final int skipped = EntityAIStructureBuilder.hutWalksSkipped.get() - skipped0;
                Log.getLogger().info("WBENCH RESULT next_order firstDone={} skipped={} minDistanceToHut={}", aDone[0], skipped, String.format("%.1f", minDistance[0]));
                helper.assertTrue(BuilderScaffoldGameTests.built(live, first, a.getLocation()) && BuilderScaffoldGameTests.built(live, second, b.getLocation()), "an order is not built");
                helper.assertTrue(skipped >= 1, "the builder did not go straight to the next order");
                helper.assertTrue(minDistance[0] > 8.0D, "the builder walked to his hut between the orders: closest " + minDistance[0]);
                WorkerSettings.clearOverrides();
                helper.succeed();
            }, () -> "firstDone=" + aDone[0] + " minDistance=" + minDistance[0] + " " + live.ai());
        });
    }

    /**
     * A working builder at 25% saturation with bread in his pocket eats it at work: his saturation rises to at least half, he never enters the meal state
     * and keeps working on his order.
     */
    public static void eatsCarriedFoodAtWork(final GameTestHelper helper)
    {
        WorkerSettings.overrideReachForTests(null, true, false, 0);
        final int eaten0 = CitizenSnack.eaten.get();
        WorkerEfficiencyGameTests.scene(helper, "WHUNGER eat at work", live -> {
            live.setSkills(1, 1);
            live.giveTools();
            final Blueprint wall = BuilderScaffoldGameTests.shape("eat_at_work", 9, 4, 1, (x, y, z) -> true);
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), BuilderScaffoldGameTests.solidCount(wall) + WorkerEfficiencyGameTests.SPARE);
            final WorkOrderDecoration order = live.orderFor("eat_at_work", BuilderScaffoldGameTests.SITE, wall);
            InventoryUtils.addItemStackToItemHandler(live.citizen.getInventory(), new ItemStack(Items.BREAD, 8));
            live.citizen.setSaturation(MAX_SATURATION * 0.25D);
            final List<String> meals = new ArrayList<>();
            WorkerEfficiencyGameTests.everyTick(live, 1200, tick -> {
                final CitizenAIState state = live.citizen.getEntity().map(e -> e instanceof EntityCitizen ec && ec.getCitizenAI().getState() instanceof CitizenAIState s ? s : null).orElse(null);
                if (state == CitizenAIState.EATING && meals.isEmpty())
                {
                    meals.add("t=" + tick + " " + live.ai());
                }
                return live.citizen.getSaturation() >= CitizenSnack.UNTIL;
            }, () -> {
                final int bread = InventoryUtils.getItemCountInItemHandler(live.citizen.getInventory(), stack -> stack.is(Items.BREAD));
                final CitizenAIState state = live.citizen.getEntity().map(e -> e instanceof EntityCitizen ec && ec.getCitizenAI().getState() instanceof CitizenAIState s ? s : null).orElse(null);
                Log.getLogger().info("WBENCH RESULT eat_at_work saturation={} bread={} eaten={} state={} meals={} {}", live.citizen.getSaturation(), bread,
                  CitizenSnack.eaten.get() - eaten0, state, meals, live.ai());
                helper.assertTrue(meals.isEmpty(), "the builder left his work for a meal: " + meals);
                helper.assertTrue(bread < 8 && CitizenSnack.eaten.get() > eaten0, "no bread eaten at work: bread=" + bread);
                // (he kept working: his order is still his or done; his citizen AI state at this tick may be a sub state, so it is only logged)
                helper.assertTrue(live.hut.hasWorkOrder() || BuilderScaffoldGameTests.built(live, wall, order.getLocation()), "the order was dropped: " + state);
                WorkerSettings.clearOverrides();
                helper.succeed();
            }, () -> "saturation=" + live.citizen.getSaturation() + " eaten=" + (CitizenSnack.eaten.get() - eaten0) + " " + live.ai());
        });
    }

    /**
     * An order queued for a hut (claimed, not yet in the hut): the colony tick hands it over when the hut is free.
     */
    static WorkOrderDecoration queued(final WorkerEfficiencyGameTests.Live live, final String name, final BlockPos rel, final Blueprint blueprint, @Nullable final BlockPos claimant)
    {
        final WorkOrderDecoration order = WorkOrderDecoration.create(WorkOrderType.BUILD, "Minecolonies Original", "gametest/" + name + ".blueprint", "gametest." + name,
          live.helper.absolutePos(rel), RotationMirror.NONE, 0);
        order.setBlueprint(blueprint, live.level);
        live.colony.getWorkManager().addWorkOrder(order, true);
        live.helper.assertTrue(order.getID() > 0, "no id for " + name);
        if (claimant != null)
        {
            order.setClaimedBy(claimant);
        }
        return order;
    }

    static double distanceToHut(final ICitizenData citizen, final BlockPos hut)
    {
        final Vec3 at = citizen.getEntity().map(e -> e.position()).orElse(new Vec3(Double.MAX_VALUE / 4, 0, 0));
        return Math.hypot(at.x - hut.getX() - 0.5D, at.z - hut.getZ() - 0.5D);
    }

    static Blueprint floor(final String name)
    {
        return BuilderScaffoldGameTests.shape(name, 3, 1, 3, (x, y, z) -> true);
    }

    /**
     * Review c1 L1: the next order queued for his hut has no loaded blueprint (a queued order keeps only its box), and scaffolding is on: he may need
     * scaffolding from the hut, so he walks there before he starts it.
     */
    public static void nextOrderWithoutBlueprintWalksToHut(final GameTestHelper helper)
    {
        WorkerSettings.overrideReachForTests(null, true, true, 16);
        final int skipped0 = EntityAIStructureBuilder.hutWalksSkipped.get();
        WorkerEfficiencyGameTests.scene(helper, "WHUNGER next order no blueprint", live -> {
            live.setSkills(40, 40);
            live.giveTools();
            InventoryUtils.addItemStackToItemHandler(live.citizen.getInventory(), new ItemStack(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), 32));
            final Blueprint first = floor("next_nobp_a");
            final WorkOrderDecoration a = live.orderFor("next_nobp_a", new BlockPos(26, 1, 12), first);
            final WorkOrderDecoration b = queued(live, "next_nobp_b", new BlockPos(26, 1, 18), floor("next_nobp_b"), live.hut.getID());
            b.clearBlueprint();
            final BlockPos hut = live.hut.getPosition();
            final int[] aDone = {-1};
            final double[] minDistance = {Double.MAX_VALUE};
            WorkerEfficiencyGameTests.everyTick(live, 6000, tick -> {
                if (aDone[0] < 0 && live.colony.getWorkManager().getWorkOrder(a.getID()) == null)
                {
                    aDone[0] = tick;
                }
                if (aDone[0] >= 0)
                {
                    minDistance[0] = Math.min(minDistance[0], distanceToHut(live.citizen, hut));
                }
                return aDone[0] >= 0 && minDistance[0] <= 6.0D;
            }, () -> {
                Log.getLogger().info("WBENCH RESULT next_order_no_blueprint firstDone={} minDistanceToHut={} {}", aDone[0], String.format("%.1f", minDistance[0]), live.ai());
                helper.assertTrue(BuilderScaffoldGameTests.built(live, first, a.getLocation()), "the first order is not built");
                // he walked because of the missing blueprint, not because the order failed to load after a skip
                helper.assertTrue(EntityAIStructureBuilder.hutWalksSkipped.get() == skipped0, "the hut walk was skipped for an order without a blueprint");
                // the order has no blueprint file: it ends here
                live.colony.getWorkManager().removeWorkOrder(b.getID());
                WorkerSettings.clearOverrides();
                helper.succeed();
            }, () -> "firstDone=" + aDone[0] + " minDistance=" + minDistance[0] + " " + live.ai());
        });
    }

    /**
     * Review c1, co-op race: two builders finish their orders while one free order waits. Exactly one of them builds it, and both end at their huts
     * without an order (the other one does not stall in the short wait for a next order).
     */
    public static void twoBuildersRaceForOneOrder(final GameTestHelper helper)
    {
        WorkerSettings.overrideReachForTests(null, true, false, 0);
        WorkerEfficiencyGameTests.scene(helper, "WHUNGER next order race", live -> {
            live.setSkills(40, 40);
            live.giveTools();
            for (final ICitizenData builder : List.of(live.citizen, live.otherCitizen))
            {
                InventoryUtils.addItemStackToItemHandler(builder.getInventory(), new ItemStack(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), 32));
            }
            final Blueprint first = floor("race_a");
            final Blueprint second = floor("race_b");
            final Blueprint shared = floor("race_c");
            final WorkOrderDecoration a = live.orderFor("race_a", new BlockPos(26, 1, 12), first);
            final WorkOrderDecoration b = queued(live, "race_b", new BlockPos(32, 1, 12), second, live.otherHut.getID());
            live.otherHut.setWorkOrder(b);
            final WorkOrderDecoration c = queued(live, "race_c", new BlockPos(29, 1, 20), shared, null);
            final java.util.Set<BlockPos> claimants = new java.util.HashSet<>();
            WorkerEfficiencyGameTests.everyTick(live, 8000, tick -> {
                if (c.isClaimed())
                {
                    claimants.add(c.getClaimedBy());
                }
                final boolean allDone = live.colony.getWorkManager().getWorkOrder(a.getID()) == null && live.colony.getWorkManager().getWorkOrder(b.getID()) == null
                                          && live.colony.getWorkManager().getWorkOrder(c.getID()) == null;
                return allDone && !live.hut.hasWorkOrder() && !live.otherHut.hasWorkOrder()
                         && distanceToHut(live.citizen, live.hut.getPosition()) <= 6.0D && distanceToHut(live.otherCitizen, live.otherHut.getPosition()) <= 6.0D;
            }, () -> {
                Log.getLogger().info("WBENCH RESULT next_order_race claimants={} {}", claimants, live.ai());
                helper.assertTrue(BuilderScaffoldGameTests.built(live, first, a.getLocation()) && BuilderScaffoldGameTests.built(live, second, b.getLocation())
                                    && BuilderScaffoldGameTests.built(live, shared, c.getLocation()), "an order is not built");
                helper.assertTrue(claimants.size() == 1, "the shared order changed hands or was never claimed: " + claimants);
                WorkerSettings.clearOverrides();
                helper.succeed();
            }, () -> "claimants=" + claimants + " near=" + distanceToHut(live.citizen, live.hut.getPosition()) + "/" + distanceToHut(live.otherCitizen, live.otherHut.getPosition())
                       + " " + live.ai());
        });
    }

    /**
     * Review c1: the order queued for his hut is cancelled while he waits for it after his first order. He walks home and ends without an order.
     */
    public static void nextOrderCancelledDuringWait(final GameTestHelper helper)
    {
        WorkerSettings.overrideReachForTests(null, true, false, 0);
        WorkerEfficiencyGameTests.scene(helper, "WHUNGER next order cancelled", live -> {
            live.setSkills(40, 40);
            live.giveTools();
            InventoryUtils.addItemStackToItemHandler(live.citizen.getInventory(), new ItemStack(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), 32));
            final Blueprint first = floor("cancel_a");
            final WorkOrderDecoration a = live.orderFor("cancel_a", new BlockPos(26, 1, 12), first);
            final WorkOrderDecoration b = queued(live, "cancel_b", new BlockPos(26, 1, 18), floor("cancel_b"), live.hut.getID());
            final int[] cancelled = {-1};
            WorkerEfficiencyGameTests.everyTick(live, 6000, tick -> {
                if (cancelled[0] < 0 && live.colony.getWorkManager().getWorkOrder(a.getID()) == null)
                {
                    cancelled[0] = tick;
                    live.colony.getWorkManager().removeWorkOrder(b.getID());
                    Log.getLogger().info("WHUNGER cancel: queued order removed at t={} hutHasOrder={} {}", tick, live.hut.hasWorkOrder(), live.ai());
                }
                return cancelled[0] >= 0 && live.colony.getWorkManager().getWorkOrder(b.getID()) == null && !live.hut.hasWorkOrder()
                         && distanceToHut(live.citizen, live.hut.getPosition()) <= 6.0D;
            }, () -> {
                Log.getLogger().info("WBENCH RESULT next_order_cancelled at={} {}", cancelled[0], live.ai());
                helper.assertTrue(BuilderScaffoldGameTests.built(live, first, a.getLocation()), "the first order is not built");
                WorkerSettings.clearOverrides();
                helper.succeed();
            }, () -> "cancelled=" + cancelled[0] + " near=" + distanceToHut(live.citizen, live.hut.getPosition()) + " " + live.ai());
        });
    }

    /**
     * Review c1 M1: a worker in his hut at 25% with a full history of bread, a restaurant in the colony and bread in his hut's racks keeps that bread
     * for the restaurant (the brink of complaining rule): nothing leaves the racks and nothing is eaten at work.
     */
    public static void rejectedRackFoodStays(final GameTestHelper helper)
    {
        final int eaten0 = CitizenSnack.eaten.get();
        WorkerEfficiencyGameTests.scene(helper, "WHUNGER rack rejected", live -> {
            MinecoloniesGameTests.placeProductionBuilding(helper, live.colony, com.minecolonies.api.blocks.ModBlocks.blockHutCook, new BlockPos(2, 1, 22), "fundamentals/cook1.blueprint");
            live.giveTools();
            live.stock(Items.BREAD, 16);
            for (int i = 0; i < 12; i++)
            {
                live.citizen.getCitizenFoodHandler().addLastEaten(Items.BREAD);
            }
            // an order without materials in the hut: he waits for them at his hut, working
            final Blueprint wall = BuilderScaffoldGameTests.shape("rack_rejected", 3, 1, 1, (x, y, z) -> true);
            live.orderFor("rack_rejected", BuilderScaffoldGameTests.SITE, wall);
            live.citizen.setSaturation(MAX_SATURATION * 0.25D);
            helper.assertTrue(!FoodUtils.wouldEatOutsideRestaurant(live.citizen, new ItemStack(Items.BREAD)), "precondition: bread is not kept for the restaurant");
            helper.assertTrue(FoodUtils.checkForFoodInBuilding(live.citizen, null, live.hut) != null, "precondition: no bread in the hut's racks");
            final int[] atHut = {0};
            WorkerEfficiencyGameTests.everyTick(live, 3000, tick -> {
                final boolean working = live.citizen.getEntity().map(e -> e instanceof EntityCitizen ec && ec.getCitizenAI().getState() == CitizenAIState.WORKING).orElse(false);
                if (working && live.hut.isInBuilding(live.citizen.getEntity().get().blockPosition()))
                {
                    atHut[0]++;
                }
                live.citizen.setSaturation(MAX_SATURATION * 0.25D);
                return atHut[0] >= 4 * CitizenSnack.INTERVAL;
            }, () -> {
                final int inHut = InventoryUtils.getItemCountInItemHandler(live.hut.getItemHandlerCap(), stack -> stack.is(Items.BREAD));
                final int carried = InventoryUtils.getItemCountInItemHandler(live.citizen.getInventory(), stack -> stack.is(Items.BREAD));
                Log.getLogger().info("WBENCH RESULT rack_rejected inHut={} carried={} eaten={} {}", inHut, carried, CitizenSnack.eaten.get() - eaten0, live.ai());
                helper.assertTrue(inHut == 16 && carried == 0, "bread left the racks: hut=" + inHut + " carried=" + carried);
                helper.assertTrue(CitizenSnack.eaten.get() == eaten0, "bread was eaten at work");
                helper.succeed();
            }, () -> "atHut=" + atHut[0] + " " + live.ai());
        });
    }

    /**
     * Review c2 M1-residual: he carries bread the rule keeps for the restaurant (full history of vanilla food, a restaurant in the colony), and his hut's
     * racks hold cheese the rule accepts (MineColonies food). He takes cheese from the racks once, eats exactly that, and never takes again; the bread
     * stays in his pockets.
     */
    public static void rackFoodEatenOnceWithRejectedPocketFood(final GameTestHelper helper)
    {
        final int eaten0 = CitizenSnack.eaten.get();
        final int pulls0 = CitizenSnack.pulls.get();
        WorkerEfficiencyGameTests.scene(helper, "WHUNGER rack once", live -> {
            MinecoloniesGameTests.placeProductionBuilding(helper, live.colony, com.minecolonies.api.blocks.ModBlocks.blockHutCook, new BlockPos(2, 1, 22), "fundamentals/cook1.blueprint");
            live.giveTools();
            live.stock(com.minecolonies.api.items.ModItems.cheddar_cheese, 16);
            InventoryUtils.addItemStackToItemHandler(live.citizen.getInventory(), new ItemStack(Items.BREAD, 8));
            for (int i = 0; i < 12; i++)
            {
                live.citizen.getCitizenFoodHandler().addLastEaten(Items.COOKED_BEEF);
            }
            final Blueprint wall = BuilderScaffoldGameTests.shape("rack_once", 3, 1, 1, (x, y, z) -> true);
            live.orderFor("rack_once", BuilderScaffoldGameTests.SITE, wall);
            live.citizen.setSaturation(MAX_SATURATION * 0.25D);
            helper.assertTrue(!FoodUtils.wouldEatOutsideRestaurant(live.citizen, new ItemStack(Items.BREAD)), "precondition: the bread is not kept for the restaurant");
            helper.assertTrue(FoodUtils.wouldEatOutsideRestaurant(live.citizen, new ItemStack(com.minecolonies.api.items.ModItems.cheddar_cheese)), "precondition: the cheese is not accepted");
            helper.assertTrue(FoodUtils.getBestFoodForCitizen(live.citizen.getInventory(), live.citizen, null) == -1, "precondition: the pocket bread is eaten anyway");
            final java.util.function.IntSupplier rack = () -> InventoryUtils.getItemCountInItemHandler(live.hut.getItemHandlerCap(), stack -> stack.is(com.minecolonies.api.items.ModItems.cheddar_cheese));
            final int[] last = {rack.getAsInt()};
            final int[] drops = {0};
            final int[] after = {0};
            WorkerEfficiencyGameTests.everyTick(live, 4000, tick -> {
                final int now = rack.getAsInt();
                if (now < last[0])
                {
                    drops[0]++;
                }
                last[0] = now;
                if (live.citizen.getSaturation() >= CitizenSnack.UNTIL)
                {
                    after[0]++;
                }
                // a few more checks after he is fed: no further take
                return after[0] >= 3 * CitizenSnack.INTERVAL;
            }, () -> {
                // (a dump at the hut may move some of his bread into the racks: count both)
                final int bread = InventoryUtils.getItemCountInItemHandler(live.citizen.getInventory(), stack -> stack.is(Items.BREAD))
                                    + InventoryUtils.getItemCountInItemHandler(live.hut.getItemHandlerCap(), stack -> stack.is(Items.BREAD));
                final int cheese = InventoryUtils.getItemCountInItemHandler(live.citizen.getInventory(), stack -> stack.is(com.minecolonies.api.items.ModItems.cheddar_cheese));
                Log.getLogger().info("WBENCH RESULT rack_once rack={} drops={} pulls={} eaten={} bread={} cheeseCarried={} saturation={} {}", last[0], drops[0],
                  CitizenSnack.pulls.get() - pulls0, CitizenSnack.eaten.get() - eaten0, bread, cheese, live.citizen.getSaturation(), live.ai());
                helper.assertTrue(drops[0] == 1 && CitizenSnack.pulls.get() - pulls0 == 1, "the racks were taken from more than once: drops=" + drops[0]);
                helper.assertTrue(16 - last[0] == CitizenSnack.eaten.get() - eaten0 && cheese == 0, "taken cheese was not eaten: taken=" + (16 - last[0]) + " carried=" + cheese);
                helper.assertTrue(bread == 8, "the bread kept for the restaurant was eaten: " + bread);
                helper.succeed();
            }, () -> "rack=" + last[0] + " drops=" + drops[0] + " saturation=" + live.citizen.getSaturation() + " " + live.ai());
        });
    }

    /**
     * New citizens start between half and full saturation, not all at full; the colony-wide reset gives between 75% and full.
     */
    public static void startingSaturationSpread(final GameTestHelper helper)
    {
        final Random random = new Random(10);
        for (int i = 0; i < 1000; i++)
        {
            final double start = CitizenData.randomSaturation(random, CitizenData.START_SATURATION_MIN_SHARE);
            final double reset = CitizenData.randomSaturation(random, CitizenData.RESET_SATURATION_MIN_SHARE);
            helper.assertTrue(start >= MAX_SATURATION * 0.5D && start <= MAX_SATURATION, "start out of range: " + start);
            helper.assertTrue(reset >= MAX_SATURATION * 0.75D && reset <= MAX_SATURATION, "reset out of range: " + reset);
        }
        MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper);
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "WHUNGER start spread");
        helper.runAfterDelay(100, () -> {
            final BlockPos anchor = helper.absolutePos(new BlockPos(10, 1, 8));
            for (int i = 0; i < 8; i++)
            {
                helper.assertTrue(colony.getCitizenManager().spawnOrCreateCivilian(null, helper.getLevel(), List.of(anchor), true) != null, "citizen did not spawn");
            }
            final List<Double> values = new ArrayList<>();
            for (final ICitizenData citizen : colony.getCitizenManager().getCitizens())
            {
                values.add(citizen.getSaturation());
            }
            Log.getLogger().info("WBENCH RESULT start_spread saturations={}", values);
            helper.assertTrue(values.size() >= 8, "fewer than 8 citizens: " + values);
            for (final double value : values)
            {
                helper.assertTrue(value >= MAX_SATURATION * 0.5D && value <= MAX_SATURATION, "a new citizen outside 50% to 100%: " + values);
            }
            helper.assertTrue(values.stream().distinct().count() > 1, "all new citizens start alike: " + values);
            helper.succeed();
        });
    }
}
