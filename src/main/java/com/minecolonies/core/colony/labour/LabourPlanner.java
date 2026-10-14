package com.minecolonies.core.colony.labour;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a labourer does next to get hold of some goods with his hands: gather it, take it from a warehouse (only what a specialist covers), craft it at a table or
 * in his pockets, smelt it in a furnace. Pure logic over an {@link Env} that answers what the colony has and allows; {@code K} is the kind of item (the game's
 * {@code Item}, a string in the tests).
 * <p>
 * The planner keeps no plan. Asked "what now, to have {@code count} of {@code target}" with the inventory as it is, it answers with the next single action, DONE, or
 * why it cannot: so a labourer that was interrupted, that gained or lost items, or whose colony got a specialist meanwhile, simply asks again. Ingredients that an
 * earlier sibling already holds are reserved in a virtual inventory, so the same stack is never counted for two ingredients.
 * <p>
 * Rules (LABOURERS.md, 4.4): an item a specialist covers is never made by labour, not even as an intermediate, it is only taken from stock; plans are at most
 * {@link #MAX_DEPTH} deep.
 *
 * @param <K> the kind of item.
 */
public final class LabourPlanner<K>
{
    /**
     * Deepest chain: stone pickaxe, cobblestone, wooden pickaxe, planks, log ...
     */
    public static final int MAX_DEPTH = 6;

    /**
     * Why there is no next action.
     */
    public enum Why
    {
        /**
         * Nothing to gather within reach (no tree, block, or loose item).
         */
        NO_SITE,
        NO_TABLE,
        NO_FURNACE,
        /**
         * A specialist covers it and the warehouses hold too little.
         */
        COVERED,
        NO_RECIPE,
        TOO_DEEP,
        SMELT_OFF
    }

    /**
     * Ingredient group of a recipe: {@code count} of any of the alternatives.
     */
    public record Group<K>(List<K> alternatives, int count)
    {
    }

    /**
     * How a recipe is made.
     */
    public enum Kind
    {
        CRAFT,
        SMELT
    }

    /**
     * A recipe a labourer may use.
     *
     * @param small crafted in the 2x2 grid (needs no table).
     */
    public record Recipe<K>(String id, List<Group<K>> inputs, K output, int outputCount, Kind kind, boolean small)
    {
        /**
         * @param need how many of the output are missing.
         * @return how many times the recipe has to be made.
         */
        public int timesFor(final int need)
        {
            return Math.max(1, (need + outputCount - 1) / outputCount);
        }
    }

    /**
     * How a good is gathered by hand.
     */
    public enum GatherKind
    {
        /**
         * Fell a natural tree.
         */
        LOG,
        /**
         * Dig a surface block (sand, dirt, gravel).
         */
        SURFACE,
        /**
         * Break a surface stone block (needs a pickaxe).
         */
        STONE,
        /**
         * Pick up an item lying on the ground.
         */
        LOOSE
    }

    /**
     * A way to gather a good.
     *
     * @param tool the tool kind the work needs ({@code pickaxe}), or null.
     */
    public record GatherDef<K>(GatherKind kind, K item, @Nullable String tool)
    {
    }

    /**
     * What the planner asks the colony.
     */
    public interface Env<K>
    {
        /**
         * @param output an item.
         * @return the recipes a labourer may use to make it (the allowed ones only), preferred first.
         */
        List<Recipe<K>> recipesFor(K output);

        /**
         * @param item an item.
         * @return the ways to gather it by hand, preferred first.
         */
        List<GatherDef<K>> gathersOf(K item);

        /**
         * @return whether the gathering has a site: a tree with such a log, a surface block, a loose item.
         */
        boolean canGather(GatherDef<K> gather);

        boolean hasTable();

        boolean hasFurnace();

        /**
         * @return whether a specialist building covers the item (labour keeps off).
         */
        boolean covered(K item);

        /**
         * @return how many of the item the warehouses hold, free of reservations.
         */
        int withdrawable(K item);

        /**
         * @param tool      a tool kind ({@code pickaxe}).
         * @param inventory the inventory of the labourer.
         * @return whether he holds a usable one.
         */
        boolean hasTool(String tool, Map<K, Integer> inventory);

        /**
         * @return the cheapest item to craft for the tool kind, null for none.
         */
        @Nullable
        K toolItem(String tool);

        /**
         * @return the items that burn in a furnace, preferred first.
         */
        List<K> fuels();

        /**
         * @return how many items one piece of the fuel smelts.
         */
        double smeltsPerFuel(K fuel);

        /**
         * @return whether the smelting recipe is allowed (the smelt mode).
         */
        boolean smeltAllowed(Recipe<K> recipe);
    }

    /**
     * The next action.
     */
    public sealed interface Action<K> permits Gather, Withdraw, Craft, Smelt
    {
    }

    /**
     * Gather until the inventory holds {@code until} of the item.
     */
    public record Gather<K>(GatherDef<K> gather, int until) implements Action<K>
    {
    }

    /**
     * Take the item out of a warehouse.
     */
    public record Withdraw<K>(K item, int amount) implements Action<K>
    {
    }

    /**
     * Make the recipe {@code times} times.
     */
    public record Craft<K>(Recipe<K> recipe, int times) implements Action<K>
    {
    }

    /**
     * Smelt {@code count} items with {@code fuelCount} of the fuel.
     */
    public record Smelt<K>(Recipe<K> recipe, int count, K fuel, int fuelCount) implements Action<K>
    {
    }

    /**
     * The answer.
     */
    public sealed interface Result<K> permits Done, Do, Blocked
    {
    }

    /**
     * Everything is in the inventory.
     */
    public record Done<K>() implements Result<K>
    {
    }

    /**
     * Do this next.
     */
    public record Do<K>(Action<K> action) implements Result<K>
    {
    }

    /**
     * Cannot be done by hand now.
     *
     * @param item the item that could not be had.
     */
    public record Blocked<K>(Why why, @Nullable K item) implements Result<K>
    {
    }

    private final Env<K>      env;
    private final Map<K, Integer> actual;
    private final Set<K>      visiting = new HashSet<>();

    private LabourPlanner(final Env<K> env, final Map<K, Integer> actual)
    {
        this.env = env;
        this.actual = actual;
    }

    /**
     * @param env       the colony.
     * @param target    the item wanted.
     * @param count     how many.
     * @param inventory what the labourer holds now.
     * @return the next action, DONE if he holds enough, or why he cannot.
     */
    public static <K> Result<K> next(final Env<K> env, final K target, final int count, final Map<K, Integer> inventory)
    {
        final LabourPlanner<K> planner = new LabourPlanner<>(env, inventory);
        return planner.need(target, count, new HashMap<>(inventory), 0);
    }

    private Result<K> need(final K item, final int n, final Map<K, Integer> have, final int depth)
    {
        final int h = have.getOrDefault(item, 0);
        if (h >= n)
        {
            have.merge(item, -n, Integer::sum);
            return new Done<>();
        }
        if (depth > MAX_DEPTH)
        {
            return new Blocked<>(Why.TOO_DEEP, item);
        }
        if (!visiting.add(item))
        {
            return new Blocked<>(Why.NO_RECIPE, item);
        }
        try
        {
            final int missing = n - h;
            if (env.covered(item))
            {
                if (env.withdrawable(item) >= missing)
                {
                    return new Do<>(new Withdraw<>(item, missing));
                }
                return new Blocked<>(Why.COVERED, item);
            }
            Blocked<K> firstBlock = null;

            for (final GatherDef<K> gather : env.gathersOf(item))
            {
                if (!env.canGather(gather))
                {
                    firstBlock = firstBlock == null ? new Blocked<>(Why.NO_SITE, item) : firstBlock;
                    continue;
                }
                if (gather.tool() != null && !env.hasTool(gather.tool(), have))
                {
                    final K tool = env.toolItem(gather.tool());
                    if (tool == null)
                    {
                        firstBlock = firstBlock == null ? new Blocked<>(Why.NO_RECIPE, item) : firstBlock;
                        continue;
                    }
                    final Map<K, Integer> copy = new HashMap<>(have);
                    final Result<K> toolResult = need(tool, 1, copy, depth + 1);
                    if (toolResult instanceof Do<K>)
                    {
                        return toolResult;
                    }
                    if (toolResult instanceof Blocked<K> blocked)
                    {
                        firstBlock = firstBlock == null ? blocked : firstBlock;
                        continue;
                    }
                    // (the tool is held after all: it is used, not used up)
                }
                final int until = actual.getOrDefault(item, 0) + missing;
                return new Do<>(new Gather<>(gather, until));
            }

            for (final Recipe<K> recipe : env.recipesFor(item))
            {
                if (recipe.kind() == Kind.SMELT)
                {
                    if (!env.smeltAllowed(recipe))
                    {
                        firstBlock = firstBlock == null ? new Blocked<>(Why.SMELT_OFF, item) : firstBlock;
                        continue;
                    }
                    if (!env.hasFurnace())
                    {
                        firstBlock = firstBlock == null ? new Blocked<>(Why.NO_FURNACE, item) : firstBlock;
                        continue;
                    }
                }
                else if (!recipe.small() && !env.hasTable())
                {
                    firstBlock = firstBlock == null ? new Blocked<>(Why.NO_TABLE, item) : firstBlock;
                    continue;
                }
                final Result<K> r = viaRecipe(recipe, missing, have, depth);
                if (r instanceof Do<K>)
                {
                    return r;
                }
                if (r instanceof Blocked<K> blocked)
                {
                    firstBlock = firstBlock == null ? blocked : firstBlock;
                }
            }
            return firstBlock == null ? new Blocked<>(Why.NO_RECIPE, item) : firstBlock;
        }
        finally
        {
            visiting.remove(item);
        }
    }

    /**
     * The next step toward making the missing outputs with the recipe: the step toward an input (an ingredient or the fuel) that is not held yet, or the recipe
     * itself when everything it takes is held.
     */
    private Result<K> viaRecipe(final Recipe<K> recipe, final int missing, final Map<K, Integer> have, final int depth)
    {
        final int times = recipe.timesFor(missing);
        final Map<K, Integer> work = new HashMap<>(have);
        for (final Group<K> group : recipe.inputs())
        {
            final Result<K> r = needAny(group.alternatives(), group.count() * times, work, depth + 1);
            if (!(r instanceof Done<K>))
            {
                return r;
            }
        }
        if (recipe.kind() == Kind.CRAFT)
        {
            return new Do<>(new Craft<>(recipe, times));
        }
        // fuel: what he holds first, then what can be had, in the order of the colony
        final List<K> fuels = new ArrayList<>(env.fuels());
        fuels.sort((a, b) -> Integer.compare(work.getOrDefault(b, 0) > 0 ? 1 : 0, work.getOrDefault(a, 0) > 0 ? 1 : 0));
        Blocked<K> firstBlock = null;
        for (final K fuel : fuels)
        {
            final int needFuel = (int) Math.ceil(times / env.smeltsPerFuel(fuel));
            final Map<K, Integer> copy = new HashMap<>(work);
            final Result<K> r = needAny(List.of(fuel), needFuel, copy, depth + 1);
            if (r instanceof Done<K>)
            {
                return new Do<>(new Smelt<>(recipe, times, fuel, needFuel));
            }
            if (r instanceof Do<K>)
            {
                return r;
            }
            if (r instanceof Blocked<K> blocked && firstBlock == null)
            {
                firstBlock = blocked;
            }
        }
        return firstBlock == null ? new Blocked<>(Why.NO_RECIPE, recipe.output()) : firstBlock;
    }

    /**
     * Needs {@code n} of any of the items: the ones already held first, then the others in order.
     */
    private Result<K> needAny(final List<K> alternatives, final int n, final Map<K, Integer> work, final int depth)
    {
        for (final K alt : alternatives)
        {
            if (work.getOrDefault(alt, 0) >= n)
            {
                work.merge(alt, -n, Integer::sum);
                return new Done<>();
            }
        }
        Blocked<K> firstBlock = null;
        for (final K alt : alternatives)
        {
            final Map<K, Integer> copy = new HashMap<>(work);
            final Result<K> r = need(alt, n, copy, depth);
            if (r instanceof Done<K>)
            {
                work.clear();
                work.putAll(copy);
                return r;
            }
            if (r instanceof Do<K>)
            {
                return r;
            }
            if (r instanceof Blocked<K> blocked && firstBlock == null)
            {
                firstBlock = blocked;
            }
        }
        return firstBlock == null ? new Blocked<>(Why.NO_RECIPE, null) : firstBlock;
    }
}
