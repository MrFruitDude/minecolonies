package com.minecolonies.core.colony.labour;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertTrue;

/**
 * The pickaxe of a directed stone order, planned within the order's area ({@link LabourPlanner#withinArea}) on a made-up world of strings: a tree outside the area is
 * never felled for it, what he holds or the warehouses give still makes it, a tree in the area does, and nothing is dug or stripped. The usual labour reach is
 * unchanged.
 */
public class DirectedToolAreaTest
{
    private static final String OAK_LOG     = "oak_log";
    private static final String OAK_PLANKS  = "oak_planks";
    private static final String STICK       = "stick";
    private static final String PICKAXE     = "wooden_pickaxe";
    private static final String STONE_PICK  = "stone_pickaxe";
    private static final String COBBLESTONE = "cobblestone";

    private final Map<String, List<LabourPlanner.Recipe<String>>> recipes = new HashMap<>();
    private final Map<String, List<LabourPlanner.GatherDef<String>>> gathers = new HashMap<>();
    // sites within the usual labour reach (the whole colony's radius)
    private final Set<String> reach = new HashSet<>();
    // logs of the natural trees standing wholly in the order's area
    private final Set<String> area = new HashSet<>();
    private final Set<String> covered = new HashSet<>();
    private final Map<String, Integer> stock = new HashMap<>();

    private final LabourPlanner.Env<String> env = new LabourPlanner.Env<>()
    {
        @Override
        public List<LabourPlanner.Recipe<String>> recipesFor(final String output)
        {
            return recipes.getOrDefault(output, List.of());
        }

        @Override
        public List<LabourPlanner.GatherDef<String>> gathersOf(final String item)
        {
            return gathers.getOrDefault(item, List.of());
        }

        @Override
        public boolean canGather(final LabourPlanner.GatherDef<String> gather)
        {
            return reach.contains(gather.item());
        }

        @Override
        public boolean hasTable()
        {
            return true;
        }

        @Override
        public boolean hasFurnace()
        {
            return false;
        }

        @Override
        public boolean covered(final String item)
        {
            return covered.contains(item);
        }

        @Override
        public int withdrawable(final String item)
        {
            return stock.getOrDefault(item, 0);
        }

        @Override
        public boolean hasTool(final String tool, final Map<String, Integer> inventory)
        {
            return tool.equals("pickaxe") && (inventory.getOrDefault(PICKAXE, 0) > 0 || inventory.getOrDefault(STONE_PICK, 0) > 0);
        }

        @Override
        public String toolItem(final String tool)
        {
            return tool.equals("pickaxe") ? PICKAXE : null;
        }

        @Override
        public List<String> fuels()
        {
            return List.of();
        }

        @Override
        public double smeltsPerFuel(final String fuel)
        {
            return 1.0D;
        }

        @Override
        public boolean smeltAllowed(final LabourPlanner.Recipe<String> recipe)
        {
            return false;
        }
    };

    private final LabourPlanner.Env<String> bounded = LabourPlanner.withinArea(env, area::contains);

    private static LabourPlanner.Group<String> group(final int count, final String... alternatives)
    {
        return new LabourPlanner.Group<>(List.of(alternatives), count);
    }

    @SafeVarargs
    private void recipe(final String id, final String output, final int outputCount, final boolean small, final LabourPlanner.Group<String>... inputs)
    {
        recipes.computeIfAbsent(output, k -> new ArrayList<>()).add(new LabourPlanner.Recipe<>(id, List.of(inputs), output, outputCount, LabourPlanner.Kind.CRAFT, small));
    }

    @Before
    public void world()
    {
        recipe("oak_planks", OAK_PLANKS, 4, true, group(1, OAK_LOG));
        recipe("sticks", STICK, 4, true, group(2, OAK_PLANKS));
        recipe("wooden_pickaxe", PICKAXE, 1, false, group(3, OAK_PLANKS), group(2, STICK));
        recipe("stone_pickaxe", STONE_PICK, 1, false, group(3, COBBLESTONE), group(2, STICK));
        gathers.put(OAK_LOG, List.of(new LabourPlanner.GatherDef<>(LabourPlanner.GatherKind.LOG, OAK_LOG, null)));
        gathers.put(COBBLESTONE, List.of(new LabourPlanner.GatherDef<>(LabourPlanner.GatherKind.STONE, COBBLESTONE, "pickaxe")));
        // an oak and stone stand in the colony, outside the order's area
        reach.add(OAK_LOG);
        reach.add(COBBLESTONE);
    }

    @Test
    public void aTreeOutsideTheAreaIsNotFelledForThePickaxe()
    {
        final LabourPlanner.Result<String> result = LabourPlanner.next(bounded, PICKAXE, 1, Map.of());
        assertTrue("got " + result, result instanceof LabourPlanner.Blocked<String> b && b.why() == LabourPlanner.Why.NO_SITE);
        // the general labour reach still fells it (an unemployed labourer serving a request)
        final LabourPlanner.Result<String> general = LabourPlanner.next(env, PICKAXE, 1, Map.of());
        assertTrue("got " + general, general instanceof LabourPlanner.Do<String> d && d.action() instanceof LabourPlanner.Gather<String> g
                                       && g.gather().item().equals(OAK_LOG));
    }

    @Test
    public void heldGoodsStillMakeThePickaxe()
    {
        final LabourPlanner.Result<String> result = LabourPlanner.next(bounded, PICKAXE, 1, Map.of(OAK_PLANKS, 3, STICK, 2));
        assertTrue("got " + result, result instanceof LabourPlanner.Do<String> d && d.action() instanceof LabourPlanner.Craft<String> c
                                      && c.recipe().output().equals(PICKAXE));
        // logs he carries become planks in his pockets, no tree needed
        final LabourPlanner.Result<String> logs = LabourPlanner.next(bounded, PICKAXE, 1, Map.of(OAK_LOG, 2));
        assertTrue("got " + logs, logs instanceof LabourPlanner.Do<String> d && d.action() instanceof LabourPlanner.Craft<String> c
                                    && c.recipe().output().equals(OAK_PLANKS));
    }

    @Test
    public void warehousePlanksStillMakeThePickaxe()
    {
        covered.add(OAK_PLANKS);
        stock.put(OAK_PLANKS, 64);
        final LabourPlanner.Result<String> result = LabourPlanner.next(bounded, PICKAXE, 1, Map.of());
        assertTrue("got " + result, result instanceof LabourPlanner.Do<String> d && d.action() instanceof LabourPlanner.Withdraw<String> w
                                      && w.item().equals(OAK_PLANKS));
    }

    @Test
    public void aTreeInTheAreaIsFelled()
    {
        area.add(OAK_LOG);
        final LabourPlanner.Result<String> result = LabourPlanner.next(bounded, PICKAXE, 1, Map.of());
        assertTrue("got " + result, result instanceof LabourPlanner.Do<String> d && d.action() instanceof LabourPlanner.Gather<String> g
                                      && g.gather().kind() == LabourPlanner.GatherKind.LOG && g.gather().item().equals(OAK_LOG));
    }

    @Test
    public void nothingIsDugForATool()
    {
        // stone stands in reach and he holds a wooden pickaxe: within the area nothing but its trees is taken
        final LabourPlanner.Result<String> result = LabourPlanner.next(bounded, STONE_PICK, 1, Map.of(PICKAXE, 1, STICK, 2));
        assertTrue("got " + result, result instanceof LabourPlanner.Blocked<String> b && b.why() == LabourPlanner.Why.NO_SITE);
        final LabourPlanner.Result<String> general = LabourPlanner.next(env, STONE_PICK, 1, Map.of(PICKAXE, 1, STICK, 2));
        assertTrue("got " + general, general instanceof LabourPlanner.Do<String> d && d.action() instanceof LabourPlanner.Gather<String> g
                                       && g.gather().kind() == LabourPlanner.GatherKind.STONE);
    }
}
