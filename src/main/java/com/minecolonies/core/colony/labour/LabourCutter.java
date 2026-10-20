package com.minecolonies.core.colony.labour;

import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock;
import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlockComponent;
import com.ldtteam.domumornamentum.client.model.data.MaterialTextureData;
import com.ldtteam.domumornamentum.recipe.architectscutter.ArchitectsCutterRecipe;
import com.ldtteam.domumornamentum.recipe.architectscutter.ArchitectsCutterRecipeInput;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.util.DomumOrnamentumUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Architect's Cutter as labour uses it: for one requested Domum Ornamentum stack (its block and its material components) the loaded cutter recipe that makes
 * exactly that stack, and the materials one run takes (one of each component's block, in the cutter's slot order). Nothing is made from the item alone: the result
 * is the recipe's own {@code assemble} of the real materials, and a result that is not the requested stack (another material, another component) is never made,
 * counted or handed in.
 */
public final class LabourCutter
{
    /**
     * One cutter recipe for one requested stack.
     *
     * @param recipeId the loaded recipe.
     * @param slots    the material of each slot, in the cutter's order (AIR for an optional slot left empty).
     * @param perRun   how many one run makes.
     */
    public record Job(String recipeId, List<Item> slots, int perRun)
    {
        /**
         * @return the materials of one run as planner groups (one per used slot, the same block twice is two groups).
         */
        public List<LabourPlanner.Group<Item>> inputs()
        {
            final List<LabourPlanner.Group<Item>> groups = new ArrayList<>();
            for (final Item item : slots)
            {
                if (item != Items.AIR)
                {
                    groups.add(new LabourPlanner.Group<>(List.of(item), 1));
                }
            }
            return groups;
        }

        /**
         * @return how many runs make {@code need} of the stack.
         */
        public int runsFor(final int need)
        {
            return Math.max(1, (need + perRun - 1) / Math.max(1, perRun));
        }
    }

    /**
     * Why there is no job.
     */
    public enum Refusal
    {
        /** Not a Domum Ornamentum block. */
        NOT_CUTTER,
        /** The stack names no material for a slot that needs one, or a material the slot does not take, or no recipe makes exactly it. */
        WRONG_VARIANT,
        /** A material is not basic (wood, plain stone). */
        INPUT
    }

    /**
     * The job, or why there is none.
     */
    public record Resolved(@Nullable Job job, @Nullable Refusal refusal)
    {
    }

    private LabourCutter()
    {
    }

    /**
     * @return whether the stack is a Domum Ornamentum block (made at the cutter).
     */
    public static boolean isCutterStack(final ItemStack stack)
    {
        return !stack.isEmpty() && DomumOrnamentumUtils.getBlock(stack) != null;
    }

    /**
     * Finds the loaded cutter recipe whose assembled result is exactly the requested stack (item and every component), from the materials the stack names.
     *
     * @param level     the level (its recipe manager).
     * @param requested the requested stack.
     * @return the job, or why there is none.
     */
    public static Resolved resolve(final ServerLevel level, final ItemStack requested)
    {
        final IMateriallyTexturedBlock block = DomumOrnamentumUtils.getBlock(requested);
        if (block == null)
        {
            return new Resolved(null, Refusal.NOT_CUTTER);
        }
        final List<Item> slots = slotsOf(block, requested);
        if (slots == null)
        {
            return new Resolved(null, Refusal.WRONG_VARIANT);
        }
        for (final Item item : slots)
        {
            if (item != Items.AIR && !LabourRules.cutterInput(item))
            {
                return new Resolved(null, Refusal.INPUT);
            }
        }
        final Block generated = ((BlockItem) requested.getItem()).getBlock();
        try
        {
            for (final RecipeHolder<?> holder : level.recipeAccess().getRecipes())
            {
                if (!(holder.value() instanceof final ArchitectsCutterRecipe recipe) || recipe.getBlock() != generated)
                {
                    continue;
                }
                final ArchitectsCutterRecipeInput input = input(slots, null);
                if (!recipe.matches(input, level))
                {
                    continue;
                }
                final ItemStack result = recipe.assemble(input);
                if (!result.isEmpty() && ItemStack.isSameItemSameComponents(result, requested))
                {
                    return new Resolved(new Job(holder.id().identifier().toString(), List.copyOf(slots), Math.max(1, result.getCount())), null);
                }
            }
        }
        catch (final RuntimeException e)
        {
            Log.getLogger().warn("Could not read the Architect's Cutter recipes for the labourers", e);
        }
        return new Resolved(null, Refusal.WRONG_VARIANT);
    }

    /**
     * @return the material of each of the block's components as the stack names it (AIR for an optional one it leaves out), or null when a needed one is missing or
     * not a valid skin of its slot.
     */
    @Nullable
    private static List<Item> slotsOf(final IMateriallyTexturedBlock block, final ItemStack requested)
    {
        final Map<net.minecraft.resources.Identifier, Block> textures = MaterialTextureData.readFromItemStack(requested).getTexturedComponents();
        final List<Item> slots = new ArrayList<>();
        for (final IMateriallyTexturedBlockComponent component : block.getComponents())
        {
            final Block material = textures.get(component.getId());
            if (material == null)
            {
                if (!component.isOptional())
                {
                    return null;
                }
                slots.add(Items.AIR);
                continue;
            }
            final Item item = material.asItem();
            if (!(item instanceof BlockItem) || !material.defaultBlockState().is(component.getValidSkins()))
            {
                return null;
            }
            slots.add(item);
        }
        return slots.isEmpty() ? null : slots;
    }

    /**
     * @param slots  the materials.
     * @param actual the stacks to put in (the labourer's own, one each), or null for fresh stacks of the items.
     */
    private static ArchitectsCutterRecipeInput input(final List<Item> slots, @Nullable final List<ItemStack> actual)
    {
        final SimpleContainer container = new SimpleContainer(slots.size());
        for (int i = 0; i < slots.size(); i++)
        {
            if (slots.get(i) == Items.AIR)
            {
                continue;
            }
            container.setItem(i, actual == null ? new ItemStack(slots.get(i)) : actual.get(i).copyWithCount(1));
        }
        return new ArchitectsCutterRecipeInput(container);
    }

    /**
     * Assembles one run from the labourer's own material stacks with the job's recipe as it is loaded now.
     *
     * @param level     the level.
     * @param job       the job.
     * @param actual    one stack per slot (EMPTY for an empty optional slot), as he holds them.
     * @param requested the requested stack.
     * @return the result, or EMPTY when the recipe is gone or the result is not exactly the requested stack.
     */
    public static ItemStack assemble(final ServerLevel level, final Job job, final List<ItemStack> actual, final ItemStack requested)
    {
        try
        {
            for (final RecipeHolder<?> holder : level.recipeAccess().getRecipes())
            {
                if (holder.value() instanceof final ArchitectsCutterRecipe recipe && holder.id().identifier().toString().equals(job.recipeId()))
                {
                    final ArchitectsCutterRecipeInput input = input(job.slots(), actual);
                    if (!recipe.matches(input, level))
                    {
                        return ItemStack.EMPTY;
                    }
                    final ItemStack result = recipe.assemble(input);
                    return !result.isEmpty() && ItemStack.isSameItemSameComponents(result, requested) ? result : ItemStack.EMPTY;
                }
            }
        }
        catch (final RuntimeException e)
        {
            Log.getLogger().warn("Could not use the Architect's Cutter recipe " + job.recipeId(), e);
        }
        return ItemStack.EMPTY;
    }

    /**
     * What a batch at the cutter takes and makes, before anything is taken (pure: {@code K} is the kind of item).
     *
     * @param runs     how many runs may be made (the materials held allow at most this many).
     * @param take     what the runs take, by item.
     * @param produced how many of the requested stack they make.
     */
    public record Batch<K>(int runs, Map<K, Integer> take, int produced)
    {
    }

    /**
     * @param slots     the materials of one run (null or {@code empty} for an empty slot).
     * @param held      what the labourer holds, by item.
     * @param wanted    how many runs are wanted.
     * @param perRun    how many one run makes.
     * @param variantOk whether the recipe's own result is the requested stack (a wrong one is never made).
     * @param empty     the item of an empty slot.
     * @return the batch: as many runs as wanted and held, taking one of each slot's material per run.
     */
    public static <K> Batch<K> batch(final List<K> slots, final Map<K, Integer> held, final int wanted, final int perRun, final boolean variantOk, @Nullable final K empty)
    {
        final Map<K, Integer> perRunTake = new LinkedHashMap<>();
        for (final K slot : slots)
        {
            if (slot != null && !slot.equals(empty))
            {
                perRunTake.merge(slot, 1, Integer::sum);
            }
        }
        if (!variantOk || perRunTake.isEmpty() || wanted <= 0)
        {
            return new Batch<>(0, Map.of(), 0);
        }
        int runs = wanted;
        for (final Map.Entry<K, Integer> entry : perRunTake.entrySet())
        {
            runs = Math.min(runs, held.getOrDefault(entry.getKey(), 0) / entry.getValue());
        }
        final Map<K, Integer> take = new HashMap<>();
        if (runs > 0)
        {
            for (final Map.Entry<K, Integer> entry : perRunTake.entrySet())
            {
                take.put(entry.getKey(), entry.getValue() * runs);
            }
        }
        return new Batch<>(runs, take, runs * perRun);
    }

    /**
     * @return the registry name of the item, for logs.
     */
    static String name(final Item item)
    {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
