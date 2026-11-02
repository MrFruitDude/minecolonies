package com.minecolonies.core.gametest;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.ai.statemachine.AIOneTimeEventTarget;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.IStateSupplier;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.entity.ai.minimal.EntityAICitizenWander;
import com.minecolonies.core.entity.ai.workers.CitizenAI;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.pathfinding.navigation.MinecoloniesAdvancedPathNavigate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One controlled leisure destination per leg; native AI, navigation and movement do the round trip. */
final class ObstacleJourneyGameTests
{
    static final String[] NAMES = {"journey_door", "journey_gate", "journey_ladder", "journey_water", "journey_scaffold"};

    static void register(final RegisterGameTestsEvent event)
    {
        for (final String name : NAMES)
        {
            event.registerTest(Identifier.fromNamespaceAndPath("minecolonies", name),
              info -> new MinecoloniesGameTestInstance(info, helper -> trip(helper, name)),
              MinecoloniesGameTestRegistrar.isolatedColonyData(event, name));
        }
    }

    private static void trip(final GameTestHelper helper, final String name)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, name);
        final var level = helper.getLevel();
        level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
        level.getWeatherData().setRaining(false);
        level.setRainLevel(0);
        final boolean raised = name.equals("journey_ladder") || name.equals("journey_scaffold");
        final Map<BlockPos, Block> shell = new LinkedHashMap<>();
        final Map<BlockPos, Block> obstacle = new LinkedHashMap<>();
        // A roofed, enclosed corridor rules out walking around or over the obstacle.
        for (int x = 5; x <= 35; x++)
        {
            for (int z = 14; z <= 18; z++)
            {
                for (int y = -2; y <= 11; y++)
                {
                    final boolean solid = x == 5 || x == 35 || z == 14 || z == 18 || y <= 0 || y == 11 || (raised && x >= 20 && y <= 6);
                    helper.setBlock(new BlockPos(x, y, z), (solid ? Blocks.STONE : Blocks.AIR).defaultBlockState());
                    if (solid) { shell.put(helper.absolutePos(new BlockPos(x, y, z)), Blocks.STONE); }
                }
            }
        }
        if (name.equals("journey_door") || name.equals("journey_gate"))
        {
            for (int z = 15; z <= 17; z++)
            {
                for (int y = 1; y <= 10; y++)
                {
                    final BlockPos at = new BlockPos(20, y, z);
                    helper.setBlock(at, Blocks.STONE.defaultBlockState());
                    shell.put(helper.absolutePos(at), Blocks.STONE);
                }
            }
            final BlockPos at = new BlockPos(20, 1, 16);
            shell.remove(helper.absolutePos(at));
            shell.remove(helper.absolutePos(at.above()));
            if (name.equals("journey_door"))
            {
                final var door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
                helper.setBlock(at, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
                helper.setBlock(at.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
                obstacle.put(helper.absolutePos(at), Blocks.OAK_DOOR);
                obstacle.put(helper.absolutePos(at.above()), Blocks.OAK_DOOR);
            }
            else
            {
                helper.setBlock(at, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.EAST));
                helper.setBlock(at.above(), Blocks.AIR.defaultBlockState());
                obstacle.put(helper.absolutePos(at), Blocks.OAK_FENCE_GATE);
            }
        }
        if (raised)
        {
            for (int y = 1; y <= 6; y++)
            {
                final BlockPos at = new BlockPos(19, y, 16);
                final Block block = name.equals("journey_ladder") ? Blocks.LADDER : Blocks.SCAFFOLDING;
                helper.setBlock(at, block == Blocks.LADDER ? block.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST) : block.defaultBlockState());
                obstacle.put(helper.absolutePos(at), block);
            }
        }
        if (name.equals("journey_water"))
        {
            for (int x = 16; x <= 24; x++)
            {
                for (int z = 15; z <= 17; z++)
                {
                    for (int y = -1; y <= 1; y++)
                    {
                        final BlockPos at = new BlockPos(x, y, z);
                        shell.remove(helper.absolutePos(at));
                        helper.setBlock(at, Blocks.WATER.defaultBlockState());
                        obstacle.put(helper.absolutePos(at), Blocks.WATER);
                    }
                }
            }
        }
        final BlockPos start = helper.absolutePos(new BlockPos(8, 1, 16));
        final BlockPos destination = helper.absolutePos(new BlockPos(32, raised ? 7 : 1, 16));
        helper.runAfterDelay(40, () -> {
            final var spawned = colony.getCitizenManager().spawnOrCreateCivilian(null, level, List.of(start), true);
            helper.assertTrue(spawned instanceof ICitizenData && spawned.getEntity().isPresent(), "obstacle traveller did not spawn");
            final ICitizenData data = (ICitizenData) spawned;
            final EntityCitizen citizen = (EntityCitizen) data.getEntity().orElseThrow();
            data.setSaturation(20);
            final long preparedAt = level.getGameTime();
            final Runnable[] ready = new Runnable[1];
            ready[0] = () -> {
                helper.assertTrue(level.getGameTime() - preparedAt < 200, "traveller AI did not initialize");
                if (citizen.getDecisionAI() == null)
                {
                    helper.runAfterDelay(1, () -> ready[0].run());
                    return;
                }
                citizen.getNavigation().stop();
                // Initial fixture placement only. No movement, state or navigation changes after a leg starts.
                citizen.teleportTo(start.getX() + .5, start.getY(), start.getZ() + .5);
                final var nav = (MinecoloniesAdvancedPathNavigate) citizen.getNavigation();
                nav.enableNavigationTrace();
                final long began = level.getGameTime();
                final boolean[] events = {false, false};
                final boolean[] crossed = {false, false};
                final boolean[] touched = {false, false};
                final boolean[] opened = {false, false};
                final boolean toggle = name.equals("journey_door") || name.equals("journey_gate");
                final BlockPos toggleAt = helper.absolutePos(new BlockPos(20, 1, 16));
                final long[] returnReady = {-1};
                final boolean[] returnQueued = {false};
                final int[] leg = {0};
                final Vec3[] prior = {citizen.position()};
                enterVisit(helper, name, citizen, destination, events, 0);
                final Runnable[] poll = new Runnable[1];
                poll[0] = () -> {
                    data.setSaturation(20);
                    final BlockPos feet = citizen.blockPosition();
                    final BlockPos rel = feet.subtract(helper.absolutePos(BlockPos.ZERO));
                    final int which = leg[0];
                    helper.assertTrue(citizen.isAlive() && citizen.getHealth() >= citizen.getMaxHealth(), name + " injured traveller");
                    helper.assertTrue(citizen.position().distanceToSqr(prior[0]) < 2.25, name + " jumped between ticks: " + prior[0] + " -> " + citizen.position());
                    prior[0] = citizen.position();
                    for (final var block : obstacle.entrySet())
                    {
                        helper.assertTrue(level.getBlockState(block.getKey()).is(block.getValue()), name + " obstacle removed at " + block.getKey());
                    }
                    if ((level.getGameTime() - began) % 20 == 0)
                    {
                        for (final var block : shell.entrySet())
                        {
                            helper.assertTrue(level.getBlockState(block.getKey()).is(block.getValue()), name + " corridor altered at " + block.getKey());
                        }
                    }
                    if (which == 1 && !returnQueued[0])
                    {
                        // Let the native toggle interaction close the first visit before supplying the return destination.
                        if (toggle && level.getBlockState(toggleAt).getValue(BlockStateProperties.OPEN))
                        {
                            helper.assertTrue(level.getGameTime() - returnReady[0] < 200, name + " did not close after the outward leg");
                        }
                        else
                        {
                            enterVisit(helper, name, citizen, start, events, 1);
                            returnQueued[0] = true;
                        }
                    }
                    if (toggle && events[which] && level.getBlockState(toggleAt).getValue(BlockStateProperties.OPEN)
                          && citizen.position().distanceToSqr(Vec3.atCenterOf(toggleAt)) <= 16)
                    {
                        opened[which] = true;
                    }
                    crossed[which] |= which == 0 ? rel.getX() >= 21 : rel.getX() <= 18;
                    touched[which] |= switch (name)
                    {
                        case "journey_door", "journey_gate" -> rel.getX() == 20 && rel.getZ() == 16;
                        case "journey_ladder" -> level.getBlockState(feet).is(Blocks.LADDER);
                        case "journey_scaffold" -> level.getBlockState(feet).is(Blocks.SCAFFOLDING);
                        default -> !level.getFluidState(feet).isEmpty() && rel.getX() >= 16 && rel.getX() <= 24;
                    };
                    if (events[which] && citizen.getCitizenAI().getState() == EntityAICitizenWander.WanderState.WANDER_AT_LEISURE_SITE)
                    {
                        final BlockPos goal = which == 0 ? destination : start;
                        helper.assertTrue(events[which] && crossed[which] && touched[which] && citizen.position().distanceToSqr(Vec3.atCenterOf(goal)) <= 16,
                          name + " arrival bypassed obstacle: leg=" + which + " at=" + feet + " touched=" + touched[which]);
                        helper.assertTrue(!toggle || opened[which], name + " was not seen opening near the traveller on leg " + which);
                        final String trace = nav.navigationTraceHistory();
                        helper.assertTrue(trace.startsWith("actions=[]"), name + " used a stuck-handler action: " + trace);
                        Log.getLogger().info("JOURNEY obstacle={} leg={} elapsed={} at={} health={} touched=true opened={} trace={}",
                          name, which, level.getGameTime() - began, feet, citizen.getHealth(), opened[which], trace);
                        if (which == 0)
                        {
                            leg[0] = 1;
                            returnReady[0] = level.getGameTime();
                        }
                        else
                        {
                            for (final var block : shell.entrySet()) { helper.assertTrue(level.getBlockState(block.getKey()).is(block.getValue()), name + " altered corridor"); }
                            helper.succeed();
                            return;
                        }
                    }
                    helper.assertTrue(level.getGameTime() - began < 3000,
                      name + " trip failed: leg=" + which + " at=" + feet + " state=" + citizen.getCitizenAI().getState() + " trace=" + nav.navigationTraceHistory());
                    helper.runAfterDelay(1, () -> poll[0].run());
                };
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            helper.runAfterDelay(1, () -> ready[0].run());
        });
    }

    private static void enterVisit(final GameTestHelper helper, final String name, final EntityCitizen citizen, final BlockPos goal, final boolean[] seen, final int index)
    {
        if (name.equals("journey_door") || name.equals("journey_gate"))
        {
            final var state = helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(20, 1, 16)));
            helper.assertTrue(!state.getValue(BlockStateProperties.OPEN), name + " was open at start of leg " + index);
            Log.getLogger().info("JOURNEY obstacle={} startLeg={} open=false", name, index);
        }
        citizen.getCitizenAI().addTransition(new AIOneTimeEventTarget<IState>((IStateSupplier<IState>) () -> {
            try
            {
                final var minimal = CitizenAI.class.getDeclaredField("minimalAI");
                minimal.setAccessible(true);
                final var wander = ((List<?>) minimal.get(citizen.getDecisionAI())).stream().filter(EntityAICitizenWander.class::isInstance).findFirst().orElseThrow();
                final var site = EntityAICitizenWander.class.getDeclaredField("leisureSite");
                site.setAccessible(true);
                site.set(wander, goal);
                seen[index] = true;
                return EntityAICitizenWander.WanderState.GO_TO_LEISURE_SITE;
            }
            catch (final ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }));
    }
}
