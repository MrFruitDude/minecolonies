package com.minecolonies.core.entity.ai.workers.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ScaffoldPlannerTest
{
    private static final double REACH = 5.0D;
    private static final double EYE = BuildReach.EYE_HEIGHT;

    /** A world of solid blocks and own scaffolding. */
    private static final class World implements ScaffoldPlanner.Grid
    {
        final Set<BlockPos> solid = new HashSet<>();
        final Map<BlockPos, Integer> scaffold = new HashMap<>();
        final Set<BlockPos> costly = new HashSet<>();
        /** Air inside the volume of the building: a little in the way (2), like the structure AI rates it. */
        final Set<BlockPos> volume = new HashSet<>();
        Predicate<BlockPos> walkable = p -> true;

        @Override
        public boolean reachable(final BlockPos feet)
        {
            return walkable.test(feet);
        }

        /** Solid ground: a floor at y = 0 for every x in the range, or only where the predicate says so. */
        World ground(final Predicate<BlockPos> where)
        {
            for (int x = -30; x <= 30; x++)
            {
                for (int z = -30; z <= 30; z++)
                {
                    final BlockPos p = new BlockPos(x, 0, z);
                    if (where.test(p))
                    {
                        solid.add(p);
                    }
                }
            }
            return this;
        }

        World wall(final int x, final int z, final int from, final int to)
        {
            for (int y = from; y <= to; y++)
            {
                solid.add(new BlockPos(x, y, z));
            }
            return this;
        }

        @Override
        public boolean free(final BlockPos pos)
        {
            return !solid.contains(pos) && pos.getY() >= minY() && pos.getY() <= maxY() && !scaffold.containsKey(pos);
        }

        @Override
        public boolean ground(final BlockPos pos)
        {
            return solid.contains(pos);
        }

        @Override
        public boolean scaffold(final BlockPos pos)
        {
            return scaffold.getOrDefault(pos, -1) == 0;
        }

        @Override
        public int penalty(final BlockPos pos)
        {
            return costly.contains(pos) ? 40 : volume.contains(pos) ? 2 : 0;
        }

        @Override
        public int minY()
        {
            return -10;
        }

        @Override
        public int maxY()
        {
            return 100;
        }

        /** The vanilla stability distance of a scaffolding block put at pos right now. */
        int distanceAt(final BlockPos pos)
        {
            int distance = 7;
            final BlockPos below = pos.below();
            if (scaffold.containsKey(below))
            {
                distance = scaffold.get(below);
            }
            else if (solid.contains(below))
            {
                return 0;
            }
            for (final Direction dir : Direction.Plane.HORIZONTAL)
            {
                final Integer d = scaffold.get(pos.relative(dir));
                if (d != null)
                {
                    distance = Math.min(distance, d + 1);
                }
            }
            return distance;
        }

        /** Puts the plan up cell by cell like the builder does, and checks that every block stands (distance under 7). */
        void build(final ScaffoldPlanner.Plan plan)
        {
            for (final BlockPos cell : plan.cells())
            {
                assertTrue("cell is free " + cell, free(cell));
                final int distance = distanceAt(cell);
                assertTrue("cell " + cell + " would fall (distance " + distance + ")", distance < 7);
                scaffold.put(cell, distance);
            }
            // nothing that was put up first may have lost its support
            for (final Map.Entry<BlockPos, Integer> e : scaffold.entrySet())
            {
                assertTrue("stable " + e.getKey(), e.getValue() < 7);
            }
        }
    }

    private static final Predicate<BlockPos> EVERYWHERE = p -> true;

    @Test
    public void aTallWallGetsATowerNextToItThatEndsUnderTheSpot()
    {
        final World world = new World().ground(EVERYWHERE).wall(0, 0, 1, 9);
        final BlockPos target = new BlockPos(0, 10, 0);
        final ScaffoldPlanner.Plan plan = ScaffoldPlanner.plan(world, List.of(target), REACH, EYE, null, 32);
        assertNotNull(plan);
        world.build(plan);
        assertTrue(BuildReach.inReachFromFeet(plan.stand(), EYE, target, REACH));
        assertEquals("the spot is on top of the last cell", plan.stand().below(), plan.cells().get(plan.cells().size() - 1));
        // bottom up, each on the one before, the first on the ground
        assertTrue(world.solid.contains(plan.cells().get(0).below()));
        for (int i = 1; i < plan.cells().size(); i++)
        {
            assertEquals(plan.cells().get(i - 1).above(), plan.cells().get(i));
        }
        // the lowest spot that reaches the block: not a tower to the sky
        assertTrue("small tower: " + plan.cells(), plan.cells().size() <= 6);
        assertFalse(plan.cells().contains(target));
    }

    @Test
    public void towerStandsOutsideTheWallNotInIt()
    {
        final World world = new World().ground(EVERYWHERE).wall(0, 0, 1, 9);
        final ScaffoldPlanner.Plan plan = ScaffoldPlanner.plan(world, List.of(new BlockPos(0, 10, 0)), REACH, EYE, null, 32);
        assertNotNull(plan);
        for (final BlockPos cell : plan.cells())
        {
            assertFalse(world.solid.contains(cell));
        }
    }

    @Test
    public void aBlockOverAGapIsReachedWithAnArmFromTheEdge()
    {
        // ground only for x <= 0 (a lake or a gap beyond); the target hangs 8 blocks out at y = 3
        final World world = new World().ground(p -> p.getX() <= 0);
        final BlockPos target = new BlockPos(9, 3, 0);
        final ScaffoldPlanner.Plan plan = ScaffoldPlanner.plan(world, List.of(target), REACH, EYE, null, 64);
        assertNotNull("a spot within 6 of the edge and 5 of the block", plan);
        world.build(plan);
        assertTrue(BuildReach.inReachFromFeet(plan.stand(), EYE, target, REACH));
        // there is an arm: cells at the height of the floor cell that are not above ground
        final boolean arm = plan.cells().stream().anyMatch(c -> c.getX() > 0 && !world.solid.contains(c.below()) && !plan.cells().contains(c.below()));
        assertTrue("an arm over the gap: " + plan.cells(), arm);
    }

    @Test
    public void noPlanWhenTheGroundIsTooFarForTheArmAndTheReach()
    {
        // 6 (arm) + 5 (reach) = 11 blocks from the edge at most
        final World world = new World().ground(p -> p.getX() <= 0);
        assertNull(ScaffoldPlanner.plan(world, List.of(new BlockPos(20, 3, 0)), REACH, EYE, null, 64));
    }

    @Test
    public void noPlanOverALavaLakeWithoutGround()
    {
        final World world = new World().ground(p -> false);
        assertNull(ScaffoldPlanner.plan(world, List.of(new BlockPos(0, 3, 0)), REACH, EYE, null, 64));
    }

    @Test
    public void cellsOfTheBuildingAreAvoidedWhenThereIsAnotherWay()
    {
        final World world = new World().ground(EVERYWHERE).wall(0, 0, 1, 9);
        // the cells next to the wall on the east side belong to the building (they get blocks later): the planner builds on the west side
        for (int y = 1; y <= 12; y++)
        {
            world.costly.add(new BlockPos(1, y, 0));
            world.costly.add(new BlockPos(1, y, 1));
            world.costly.add(new BlockPos(1, y, -1));
        }
        final ScaffoldPlanner.Plan plan = ScaffoldPlanner.plan(world, List.of(new BlockPos(0, 10, 0)), REACH, EYE, null, 32);
        assertNotNull(plan);
        for (final BlockPos cell : plan.cells())
        {
            assertFalse("cell of the building " + cell, world.costly.contains(cell));
        }
    }

    @Test
    public void anExistingTowerIsExtendedNotBuiltAgain()
    {
        final World world = new World().ground(EVERYWHERE).wall(0, 0, 1, 9);
        // an own tower right next to the wall, 4 high
        for (int y = 1; y <= 4; y++)
        {
            world.scaffold.put(new BlockPos(-1, y, 0), 0);
        }
        final ScaffoldPlanner.Plan plan = ScaffoldPlanner.plan(world, List.of(new BlockPos(0, 10, 0)), REACH, EYE, null, 32);
        assertNotNull(plan);
        for (final BlockPos cell : plan.cells())
        {
            assertFalse("not built twice " + cell, world.scaffold.containsKey(cell));
        }
        assertTrue("short: " + plan.cells(), plan.cells().size() <= 3);
        world.build(plan);
    }

    @Test
    public void theStockLimitsThePlan()
    {
        final World world = new World().ground(EVERYWHERE).wall(0, 0, 1, 20);
        assertNull(ScaffoldPlanner.plan(world, List.of(new BlockPos(0, 21, 0)), REACH, EYE, null, 4));
        assertNotNull(ScaffoldPlanner.plan(world, List.of(new BlockPos(0, 21, 0)), REACH, EYE, null, 64));
    }

    @Test
    public void theSpotKeepsClearOfTheBlocksThatAreWorkedOnSoon()
    {
        final World world = new World().ground(EVERYWHERE).wall(0, 0, 1, 9);
        final List<BlockPos> next = List.of(new BlockPos(0, 10, 0), new BlockPos(1, 10, 0), new BlockPos(-1, 10, 0), new BlockPos(0, 11, 0));
        final ScaffoldPlanner.Plan plan = ScaffoldPlanner.plan(world, next, REACH, EYE, null, 32);
        assertNotNull(plan);
        for (final BlockPos block : next)
        {
            assertFalse(plan.cells().contains(block));
            assertFalse(plan.stand().equals(block) || plan.stand().above().equals(block));
        }
        assertTrue("serves several of the next blocks", plan.covered() >= 2);
    }

    @Test
    public void anArmNeverGetsLongerThanSix()
    {
        for (int x = 3; x <= 12; x++)
        {
            // targets from near to out of range: every plan that is made stays up, and none has an arm over 6
            final World world = new World().ground(p -> p.getX() <= 0);
            final ScaffoldPlanner.Plan plan = ScaffoldPlanner.plan(world, List.of(new BlockPos(x, 6, 0)), REACH, EYE, null, 64);
            if (plan != null)
            {
                world.build(plan);
                final long overGap = plan.cells().stream().filter(c -> c.getX() > 0 && !world.solid.contains(c.below()) && !plan.cells().contains(c.below())).count();
                assertTrue("arm of " + overGap, overGap <= ScaffoldPlanner.MAX_ARM);
            }
            else
            {
                assertTrue("a target " + x + " blocks out is beyond arm and reach", x > ScaffoldPlanner.MAX_ARM + 3);
            }
        }
    }

    @Test
    public void aTowerStartsWhereTheBuilderCanGetTo()
    {
        // the east side of the wall is shut off (a courtyard behind a wall): the tower goes up on the west side
        final World world = new World().ground(EVERYWHERE).wall(0, 0, 1, 9);
        world.walkable = p -> p.getX() <= 0;
        final ScaffoldPlanner.Plan plan = ScaffoldPlanner.plan(world, List.of(new BlockPos(0, 10, 0)), REACH, EYE, null, 32);
        assertNotNull(plan);
        assertTrue("starts on the open side: " + plan.cells(), world.walkable.test(plan.cells().get(0)));
        // nothing is reachable at all: no plan
        world.walkable = p -> false;
        assertNull(ScaffoldPlanner.plan(world, List.of(new BlockPos(0, 10, 0)), REACH, EYE, null, 32));
    }

    @Test
    public void oneTowerServesAWholeSlab()
    {
        // a wall of 8 and a slab on top of it, 9 by 5, still to be built: one tower under the middle serves most of it
        final World world = new World().ground(EVERYWHERE);
        for (int z = 0; z < 5; z++)
        {
            world.wall(0, z, 1, 8);
        }
        final List<BlockPos> slab = new java.util.ArrayList<>();
        for (int x = 0; x < 9; x++)
        {
            for (int z = 0; z < 5; z++)
            {
                slab.add(new BlockPos(x, 9, z));
            }
        }
        // the cells of the slab are the building's (a high penalty), the air under it is inside its volume (a small one)
        for (int x = 0; x < 9; x++)
        {
            for (int z = 0; z < 5; z++)
            {
                world.costly.add(new BlockPos(x, 9, z));
            }
        }
        final ScaffoldPlanner.Plan plan = ScaffoldPlanner.plan(world, slab, REACH, EYE, null, 32, true);
        assertNotNull(plan);
        assertTrue("serves most of the slab: " + plan.covered(), plan.covered() >= 25);
    }

    @Test
    public void aTowerUnderTheSlabServesAllOfItInsteadOfSeveralTowersOutside()
    {
        // as above, with the air under the slab inside the volume of the building (a small penalty for every cell of the tower) and a plan made when the
        // wall is built: a tower on the outside reaches only the near rows and leaves the rest to other towers, one in the middle reaches everything
        final World world = new World().ground(EVERYWHERE);
        // (nobody walks onto the top of the wall)
        world.walkable = p -> p.getY() <= 1;
        for (int z = 0; z < 5; z++)
        {
            world.wall(0, z, 1, 8);
        }
        final List<BlockPos> slab = new java.util.ArrayList<>();
        for (int x = 0; x < 9; x++)
        {
            for (int z = 0; z < 5; z++)
            {
                slab.add(new BlockPos(x, 9, z));
                world.costly.add(new BlockPos(x, 9, z));
                for (int y = 1; y < 9; y++)
                {
                    if (x != 0)
                    {
                        world.volume.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        final ScaffoldPlanner.Plan plan = ScaffoldPlanner.plan(world, slab, REACH, EYE, null, 32, true);
        assertNotNull(plan);
        assertEquals("one tower for the whole slab: " + plan, slab.size(), plan.covered());
        world.build(plan);
    }
}
