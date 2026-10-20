package com.minecolonies.core.colony.labour;

import org.junit.After;
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
 * The expanded basic tier on a made-up world of strings: wood stairs, stripping with an axe (the tree's own species), the cutter's materials, a cutter batch that
 * keeps the goods, and the gathering radius.
 */
public class ExpandedLabourTest
{
    private static final String OAK_LOG       = "oak_log";
    private static final String BIRCH_LOG     = "birch_log";
    private static final String OAK_PLANKS    = "oak_planks";
    private static final String BIRCH_PLANKS  = "birch_planks";
    private static final String STICK         = "stick";
    private static final String OAK_STAIRS    = "oak_stairs";
    private static final String STRIPPED_OAK  = "stripped_oak_log";
    private static final String STRIPPED_BIRCH = "stripped_birch_log";
    private static final String WOOD_AXE      = "wooden_axe";
    private static final String AIR           = "air";

    private final Map<String, List<LabourPlanner.Recipe<String>>> recipes = new HashMap<>();
    private final Map<String, List<LabourPlanner.GatherDef<String>>> gathers = new HashMap<>();
    private final Set<String> sites = new HashSet<>();
    private boolean table = true;

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
            return false;
        }

        @Override
        public boolean covered(final String item)
        {
            return false;
        }

        @Override
        public int withdrawable(final String item)
        {
            return 0;
        }

        @Override
        public boolean hasTool(final String tool, final Map<String, Integer> inventory)
        {
            return tool.equals("axe") && inventory.getOrDefault(WOOD_AXE, 0) > 0;
        }

        @Override
        public String toolItem(final String tool)
        {
            return tool.equals("axe") ? WOOD_AXE : null;
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
        recipe("birch_planks", BIRCH_PLANKS, 4, true, group(1, BIRCH_LOG));
        recipe("sticks", STICK, 4, true, group(2, OAK_PLANKS, BIRCH_PLANKS));
        recipe("oak_stairs", OAK_STAIRS, 4, false, group(6, OAK_PLANKS));
        recipe("wooden_axe", WOOD_AXE, 1, false, group(3, OAK_PLANKS, BIRCH_PLANKS), group(2, STICK));
        gathers.put(OAK_LOG, List.of(new LabourPlanner.GatherDef<>(LabourPlanner.GatherKind.LOG, OAK_LOG, null)));
        gathers.put(BIRCH_LOG, List.of(new LabourPlanner.GatherDef<>(LabourPlanner.GatherKind.LOG, BIRCH_LOG, null)));
        gathers.put(STRIPPED_OAK, List.of(new LabourPlanner.GatherDef<>(LabourPlanner.GatherKind.STRIP, STRIPPED_OAK, "axe")));
        gathers.put(STRIPPED_BIRCH, List.of(new LabourPlanner.GatherDef<>(LabourPlanner.GatherKind.STRIP, STRIPPED_BIRCH, "axe")));
        sites.add(OAK_LOG);
        sites.add(STRIPPED_OAK);
    }

    @After
    public void settings()
    {
        LabourSettings.clearOverrides();
    }

    private LabourPlanner.Action<String> next(final String target, final int count, final Map<String, Integer> inventory)
    {
        final LabourPlanner.Result<String> result = LabourPlanner.next(env, target, count, inventory);
        assertTrue("expected an action, got " + result, result instanceof LabourPlanner.Do<String>);
        return ((LabourPlanner.Do<String>) result).action();
    }

    // ------------------------------------------------------------------ wood

    @Test
    public void stairsComeFromPlanksAtATable()
    {
        // 5 stairs: two makings (4 each) of 6 planks = 12 planks = 3 logs
        final LabourPlanner.Action<String> first = next(OAK_STAIRS, 5, Map.of());
        assertTrue(first instanceof LabourPlanner.Gather<String> g && g.gather().item().equals(OAK_LOG) && g.until() == 3);
        final LabourPlanner.Action<String> craft = next(OAK_STAIRS, 5, Map.of(OAK_PLANKS, 12));
        assertTrue(craft instanceof LabourPlanner.Craft<String> c && c.recipe().id().equals("oak_stairs") && c.times() == 2);
        table = false;
        final LabourPlanner.Result<String> noTable = LabourPlanner.next(env, OAK_STAIRS, 5, Map.of(OAK_PLANKS, 12));
        assertTrue(noTable instanceof LabourPlanner.Blocked<String> b && b.why() == LabourPlanner.Why.NO_TABLE);
    }

    @Test
    public void strippingNeedsAnAxeFirst()
    {
        // no axe: the planner makes one first (it starts with the logs for its planks)
        final LabourPlanner.Action<String> first = next(STRIPPED_OAK, 3, Map.of());
        assertTrue("got " + first, first instanceof LabourPlanner.Gather<String> g && g.gather().kind() == LabourPlanner.GatherKind.LOG);
        final LabourPlanner.Action<String> craftAxe = next(STRIPPED_OAK, 3, Map.of(OAK_PLANKS, 3, STICK, 2));
        assertTrue("got " + craftAxe, craftAxe instanceof LabourPlanner.Craft<String> c && c.recipe().output().equals(WOOD_AXE));
        // with an axe: strip the tree of the species
        final LabourPlanner.Action<String> strip = next(STRIPPED_OAK, 3, Map.of(WOOD_AXE, 1));
        assertTrue("got " + strip, strip instanceof LabourPlanner.Gather<String> g && g.gather().kind() == LabourPlanner.GatherKind.STRIP
                                      && g.gather().item().equals(STRIPPED_OAK) && g.until() == 3);
    }

    @Test
    public void strippingIsTheTreesOwnSpecies()
    {
        // only an oak stands: stripped birch is not had from it
        final LabourPlanner.Result<String> birch = LabourPlanner.next(env, STRIPPED_BIRCH, 1, Map.of(WOOD_AXE, 1));
        assertTrue("got " + birch, birch instanceof LabourPlanner.Blocked<String> b && b.why() == LabourPlanner.Why.NO_SITE);
        // and stripped oak in hand never counts for birch
        final LabourPlanner.Result<String> held = LabourPlanner.next(env, STRIPPED_BIRCH, 1, Map.of(WOOD_AXE, 1, STRIPPED_OAK, 5));
        assertTrue(held instanceof LabourPlanner.Blocked<String>);
    }

    // ------------------------------------------------------------------ cutter materials

    @Test
    public void cutterMaterialsAreGatheredAndCraftedLikeAnyGood()
    {
        // a two-slot block of oak planks and oak logs, three runs
        final List<LabourPlanner.Group<String>> inputs = List.of(group(1, OAK_PLANKS), group(1, OAK_LOG));
        final LabourPlanner.Result<String> none = LabourPlanner.nextInputs(env, inputs, 3, Map.of());
        assertTrue("got " + none, none instanceof LabourPlanner.Do<String> d && d.action() instanceof LabourPlanner.Gather<String>);
        // 3 planks held, but 3 logs are missing: the planks are not taken for the logs, he fells for the logs
        final LabourPlanner.Result<String> logs = LabourPlanner.nextInputs(env, inputs, 3, Map.of(OAK_PLANKS, 3));
        assertTrue("got " + logs, logs instanceof LabourPlanner.Do<String> d && d.action() instanceof LabourPlanner.Gather<String> g && g.gather().item().equals(OAK_LOG)
                                    && g.until() == 3);
        final LabourPlanner.Result<String> all = LabourPlanner.nextInputs(env, inputs, 3, Map.of(OAK_PLANKS, 3, OAK_LOG, 3));
        assertTrue(all instanceof LabourPlanner.Done<String>);
    }

    @Test
    public void theSameMaterialInTwoSlotsCountsTwice()
    {
        final List<LabourPlanner.Group<String>> inputs = List.of(group(1, OAK_PLANKS), group(1, OAK_PLANKS));
        final LabourPlanner.Result<String> short1 = LabourPlanner.nextInputs(env, inputs, 3, Map.of(OAK_PLANKS, 5));
        assertTrue("5 planks are not 6: " + short1, short1 instanceof LabourPlanner.Do<String>);
        assertTrue(LabourPlanner.nextInputs(env, inputs, 3, Map.of(OAK_PLANKS, 6)) instanceof LabourPlanner.Done<String>);
    }

    @Test
    public void anUngettableMaterialBlocks()
    {
        final LabourPlanner.Result<String> r = LabourPlanner.nextInputs(env, List.of(group(1, BIRCH_PLANKS)), 1, Map.of());
        assertTrue("got " + r, r instanceof LabourPlanner.Blocked<String> b && b.why() == LabourPlanner.Why.NO_SITE);
    }

    // ------------------------------------------------------------------ cutter batch

    @Test
    public void aWrongVariantTakesAndMakesNothing()
    {
        final LabourCutter.Batch<String> batch = LabourCutter.batch(List.of(OAK_PLANKS, OAK_LOG), Map.of(OAK_PLANKS, 9, OAK_LOG, 9), 3, 4, false, AIR);
        assertEquals(0, batch.runs());
        assertTrue(batch.take().isEmpty());
        assertEquals(0, batch.produced());
    }

    @Test
    public void aBatchKeepsTheGoods()
    {
        final Map<String, Integer> held = new HashMap<>(Map.of(OAK_PLANKS, 9, OAK_LOG, 2));
        final LabourCutter.Batch<String> batch = LabourCutter.batch(List.of(OAK_PLANKS, OAK_LOG), held, 3, 4, true, AIR);
        // two logs allow two runs: one plank and one log each, four blocks each
        assertEquals(2, batch.runs());
        assertEquals(Map.of(OAK_PLANKS, 2, OAK_LOG, 2), batch.take());
        assertEquals(8, batch.produced());
        int taken = 0;
        for (final Map.Entry<String, Integer> e : batch.take().entrySet())
        {
            assertTrue(held.get(e.getKey()) >= e.getValue());
            taken += e.getValue();
        }
        assertEquals("the materials of two runs and no more", 4, taken);
    }

    @Test
    public void twoSlotsOfOneMaterialAndAnEmptyOptionalSlot()
    {
        final LabourCutter.Batch<String> batch = LabourCutter.batch(List.of(OAK_PLANKS, OAK_PLANKS, AIR), Map.of(OAK_PLANKS, 5), 9, 2, true, AIR);
        assertEquals(2, batch.runs());
        assertEquals(Map.of(OAK_PLANKS, 4), batch.take());
        assertEquals(4, batch.produced());
    }

    @Test
    public void nothingHeldNothingMade()
    {
        final LabourCutter.Batch<String> batch = LabourCutter.batch(List.of(OAK_PLANKS), Map.of(), 2, 3, true, AIR);
        assertEquals(0, batch.runs());
        assertEquals(0, batch.produced());
        assertTrue(batch.take().isEmpty());
    }

    // ------------------------------------------------------------------ radius

    @Test
    public void radiusDefaultsTo96AndKeepsAnOld48()
    {
        assertEquals(96, LabourSettings.DEFAULT_GATHER_RADIUS);
        // without a readable config the fallback is the new default
        assertEquals(96, LabourSettings.gatherRadius());
        // a config that holds 48 stays 48: it is never silently raised
        LabourSettings.override("gatherRadius", 48);
        assertEquals(48, LabourSettings.gatherRadius());
        LabourSettings.override("gatherRadius", 500);
        assertEquals(LabourSettings.MAX_GATHER_RADIUS, LabourSettings.gatherRadius());
        LabourSettings.override("gatherRadius", 1);
        assertEquals(LabourSettings.MIN_GATHER_RADIUS, LabourSettings.gatherRadius());
    }
}
