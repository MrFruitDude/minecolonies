package com.minecolonies.core.colony.labour;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The labourer's planner on a made-up world of strings: what he does next to get hold of a good by hand.
 */
public class LabourPlannerTest
{
    private static final String LOG        = "log";
    private static final String PLANKS     = "planks";
    private static final String STICK      = "stick";
    private static final String TABLE      = "table";
    private static final String WOOD_PICK  = "wooden_pickaxe";
    private static final String STONE_PICK = "stone_pickaxe";
    private static final String COBBLE     = "cobblestone";
    private static final String CHARCOAL   = "charcoal";
    private static final String STONE      = "stone";

    private final Map<String, List<LabourPlanner.Recipe<String>>> recipes = new HashMap<>();
    private final Map<String, List<LabourPlanner.GatherDef<String>>> gathers = new HashMap<>();
    private final Set<String> sites   = new HashSet<>();
    private final Set<String> covered = new HashSet<>();
    private final Map<String, Integer> stock = new HashMap<>();
    private boolean table;
    private boolean furnace;
    private boolean smelt = true;

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
            return sites.contains(gather.item());
        }

        @Override
        public boolean hasTable()
        {
            return table;
        }

        @Override
        public boolean hasFurnace()
        {
            return furnace;
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
            return inventory.getOrDefault(WOOD_PICK, 0) > 0 || inventory.getOrDefault(STONE_PICK, 0) > 0;
        }

        @Override
        public String toolItem(final String tool)
        {
            return WOOD_PICK;
        }

        @Override
        public List<String> fuels()
        {
            return List.of(CHARCOAL, LOG, PLANKS, STICK);
        }

        @Override
        public double smeltsPerFuel(final String fuel)
        {
            return fuel.equals(CHARCOAL) ? 8.0D : fuel.equals(STICK) ? 0.5D : 1.5D;
        }

        @Override
        public boolean smeltAllowed(final LabourPlanner.Recipe<String> recipe)
        {
            return smelt;
        }
    };

    private static LabourPlanner.Group<String> group(final int count, final String... alternatives)
    {
        return new LabourPlanner.Group<>(List.of(alternatives), count);
    }

    private void recipe(final String id, final String output, final int outputCount, final LabourPlanner.Kind kind, final boolean small, final LabourPlanner.Group<String>... inputs)
    {
        recipes.computeIfAbsent(output, k -> new ArrayList<>()).add(new LabourPlanner.Recipe<>(id, List.of(inputs), output, outputCount, kind, small));
    }

    @Before
    public void world()
    {
        recipe("planks", PLANKS, 4, LabourPlanner.Kind.CRAFT, true, group(1, LOG));
        recipe("sticks", STICK, 4, LabourPlanner.Kind.CRAFT, true, group(2, PLANKS));
        recipe("table", TABLE, 1, LabourPlanner.Kind.CRAFT, true, group(4, PLANKS));
        recipe("wooden_pickaxe", WOOD_PICK, 1, LabourPlanner.Kind.CRAFT, false, group(3, PLANKS), group(2, STICK));
        recipe("stone_pickaxe", STONE_PICK, 1, LabourPlanner.Kind.CRAFT, false, group(3, COBBLE), group(2, STICK));
        recipe("charcoal", CHARCOAL, 1, LabourPlanner.Kind.SMELT, false, group(1, LOG));
        recipe("stone", STONE, 1, LabourPlanner.Kind.SMELT, false, group(1, COBBLE));
        gathers.put(LOG, List.of(new LabourPlanner.GatherDef<>(LabourPlanner.GatherKind.LOG, LOG, null)));
        gathers.put(COBBLE, List.of(new LabourPlanner.GatherDef<>(LabourPlanner.GatherKind.STONE, COBBLE, "pickaxe")));
        sites.add(LOG);
        sites.add(COBBLE);
        table = true;
        furnace = true;
    }

    private LabourPlanner.Result<String> next(final String target, final int count, final Object... inventory)
    {
        final Map<String, Integer> inv = new HashMap<>();
        for (int i = 0; i < inventory.length; i += 2)
        {
            inv.put((String) inventory[i], (Integer) inventory[i + 1]);
        }
        return LabourPlanner.next(env, target, count, inv);
    }

    private static String describe(final LabourPlanner.Result<String> result)
    {
        if (result instanceof LabourPlanner.Do<String> d)
        {
            final LabourPlanner.Action<String> a = d.action();
            if (a instanceof LabourPlanner.Gather<String> g)
            {
                return "gather " + g.gather().item() + " until " + g.until();
            }
            if (a instanceof LabourPlanner.Craft<String> c)
            {
                return "craft " + c.recipe().id() + " x" + c.times();
            }
            if (a instanceof LabourPlanner.Smelt<String> s)
            {
                return "smelt " + s.recipe().id() + " x" + s.count() + " fuel " + s.fuel() + " x" + s.fuelCount();
            }
            if (a instanceof LabourPlanner.Withdraw<String> w)
            {
                return "withdraw " + w.item() + " x" + w.amount();
            }
        }
        if (result instanceof LabourPlanner.Blocked<String> b)
        {
            return "blocked " + b.why();
        }
        return "done";
    }

    @Test
    public void aWoodenPickaxeStartsWithALog()
    {
        assertEquals("gather log until 1", describe(next(WOOD_PICK, 1)));
    }

    @Test
    public void thePickaxeIsCraftedStepByStep()
    {
        // 1 log -> 4 planks: 3 for the head, 1 left
        assertEquals("craft planks x1", describe(next(WOOD_PICK, 1, LOG, 1)));
        // 4 planks: 3 reserved for the head, the sticks need 2 more planks than are left: another log is needed
        assertEquals("gather log until 1", describe(next(WOOD_PICK, 1, PLANKS, 4)));
        // 5 planks: 3 for the head, 2 for the sticks
        assertEquals("craft sticks x1", describe(next(WOOD_PICK, 1, PLANKS, 5)));
        assertEquals("craft wooden_pickaxe x1", describe(next(WOOD_PICK, 1, PLANKS, 3, STICK, 4)));
        assertEquals("done", describe(next(WOOD_PICK, 1, WOOD_PICK, 1)));
    }

    @Test
    public void aLargeRecipeNeedsATable()
    {
        table = false;
        assertEquals("blocked NO_TABLE", describe(next(WOOD_PICK, 1, PLANKS, 3, STICK, 4)));
        // planks and sticks are small recipes: pockets are enough
        assertEquals("craft planks x1", describe(next(PLANKS, 4, LOG, 1)));
    }

    @Test
    public void noTreeNoWork()
    {
        sites.remove(LOG);
        assertEquals("blocked NO_SITE", describe(next(WOOD_PICK, 1)));
    }

    @Test
    public void stoneNeedsAPickaxeFirst()
    {
        // the stone pickaxe wants cobblestone, which needs a pickaxe: the wooden one is made first (its log first)
        assertEquals("gather log until 1", describe(next(STONE_PICK, 1, STICK, 2)));
        assertEquals("gather cobblestone until 3", describe(next(STONE_PICK, 1, STICK, 2, WOOD_PICK, 1)));
        assertEquals("craft stone_pickaxe x1", describe(next(STONE_PICK, 1, STICK, 2, WOOD_PICK, 1, COBBLE, 3)));
    }

    @Test
    public void aCoveredGoodIsOnlyTakenFromStock()
    {
        covered.add(PLANKS);
        // the sawmill makes planks: a pickaxe is built from the stock of planks, or waits
        assertEquals("blocked COVERED", describe(next(WOOD_PICK, 1)));
        stock.put(PLANKS, 3);
        assertEquals("withdraw planks x3", describe(next(WOOD_PICK, 1, STICK, 4)));
        // sticks are made from planks, which are covered: the sticks need stock too, and 5 planks are not there
        stock.put(PLANKS, 5);
        assertEquals("withdraw planks x3", describe(next(WOOD_PICK, 1)));
    }

    @Test
    public void aCoveredIntermediateNeverFellsTheTree()
    {
        covered.add(PLANKS);
        covered.add(LOG);
        assertEquals("blocked COVERED", describe(next(STICK, 4)));
    }

    @Test
    public void charcoalIsSmeltedWithLogsAsInputAndFuel()
    {
        // two charcoal: two logs as input first, then two more as fuel (1.5 per log)
        assertEquals("gather log until 2", describe(next(CHARCOAL, 2)));
        assertEquals("gather log until 4", describe(next(CHARCOAL, 2, LOG, 2)));
        assertEquals("smelt charcoal x2 fuel log x2", describe(next(CHARCOAL, 2, LOG, 4)));
        // charcoal he holds counts toward the target, it is not burnt
        assertEquals("smelt charcoal x1 fuel log x1", describe(next(CHARCOAL, 2, LOG, 2, CHARCOAL, 1)));
        // charcoal in the inventory burns in the furnace of another product: 8 items per piece
        assertEquals("smelt stone x1 fuel charcoal x1", describe(next(STONE, 1, COBBLE, 1, CHARCOAL, 1)));
    }

    @Test
    public void smeltingOffBlocksTheFurnaceRecipes()
    {
        smelt = false;
        assertEquals("blocked SMELT_OFF", describe(next(CHARCOAL, 1, LOG, 5)));
        smelt = true;
        furnace = false;
        assertEquals("blocked NO_FURNACE", describe(next(CHARCOAL, 1, LOG, 5)));
    }

    @Test
    public void whatIsInTheInventoryIsNotMadeAgain()
    {
        assertEquals("done", describe(next(PLANKS, 3, PLANKS, 8)));
        assertEquals("craft planks x1", describe(next(PLANKS, 6, PLANKS, 2, LOG, 3)));
    }

    @Test
    public void aTableIsCraftedFromPlanksInThePockets()
    {
        table = false;
        assertEquals("craft table x1", describe(next(TABLE, 1, PLANKS, 4)));
        assertTrue(describe(next(TABLE, 1)).startsWith("gather"));
    }
}
