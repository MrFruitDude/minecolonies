package com.minecolonies.core.gametest;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.util.RotationMirror;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
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
                final CitizenAIState state = live.citizen.getEntity().map(e -> e instanceof EntityCitizen ec ? (CitizenAIState) ec.getCitizenAI().getState() : null).orElse(null);
                if (state == CitizenAIState.EATING && meals.isEmpty())
                {
                    meals.add("t=" + tick + " " + live.ai());
                }
                return live.citizen.getSaturation() >= CitizenSnack.UNTIL;
            }, () -> {
                final int bread = InventoryUtils.getItemCountInItemHandler(live.citizen.getInventory(), stack -> stack.is(Items.BREAD));
                final CitizenAIState state = live.citizen.getEntity().map(e -> e instanceof EntityCitizen ec ? (CitizenAIState) ec.getCitizenAI().getState() : null).orElse(null);
                Log.getLogger().info("WBENCH RESULT eat_at_work saturation={} bread={} eaten={} state={} meals={} {}", live.citizen.getSaturation(), bread,
                  CitizenSnack.eaten.get() - eaten0, state, meals, live.ai());
                helper.assertTrue(meals.isEmpty(), "the builder left his work for a meal: " + meals);
                helper.assertTrue(bread < 8 && CitizenSnack.eaten.get() > eaten0, "no bread eaten at work: bread=" + bread);
                helper.assertTrue(state == CitizenAIState.WORKING || BuilderScaffoldGameTests.built(live, wall, order.getLocation()), "not working after eating: " + state);
                helper.assertTrue(live.colony.getWorkManager().getWorkOrder(order.getID()) != null || BuilderScaffoldGameTests.built(live, wall, order.getLocation()),
                  "the order was dropped");
                WorkerSettings.clearOverrides();
                helper.succeed();
            }, () -> "saturation=" + live.citizen.getSaturation() + " eaten=" + (CitizenSnack.eaten.get() - eaten0) + " " + live.ai());
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
