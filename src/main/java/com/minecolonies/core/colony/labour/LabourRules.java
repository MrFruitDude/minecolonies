package com.minecolonies.core.colony.labour;

import com.minecolonies.api.util.constant.Constants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * The basic tier of the labourers: what they may make and gather, as data. The sets are tags (datapacks and modpacks change them):
 * <ul>
 *   <li>{@code minecolonies:labour/craft_results}: what may be crafted (planks, sticks, tools of wood and stone, a crafting table, a chest, torches ...);</li>
 *   <li>{@code minecolonies:labour/never}: vetoes the result tag (iron and better, enchanted things, books ...);</li>
 *   <li>{@code minecolonies:labour/smelt_basic} and {@code labour/smelt_iron}: what may come out of a furnace in the smelt modes BASIC and IRON;</li>
 *   <li>{@code minecolonies:labour/loose}: what a labourer picks up when it lies on the ground;</li>
 *   <li>block tags {@code labour/gather_surface} (sand, dirt, gravel) and {@code labour/gather_stone} (surface stone, needs a pickaxe);</li>
 *   <li>{@code labour/woodcraft}: the basic wood results (planks, sticks, stairs, slabs, doors, trapdoors, fences, gates, plates, buttons, wood and bark blocks); a
 *   recipe for one of them is used only with inputs of {@code labour/basic_wood} (logs, planks, sticks, stripped wood), so a mod's recipe that makes planks out of
 *   something else is not labour's;</li>
 *   <li>{@code labour/strip_results}: what an axe makes of a log (stripped logs and wood, the tree's own species);</li>
 *   <li>{@code labour/cutter_inputs}: the materials labour brings to an Architect's Cutter (wood and plain stone).</li>
 * </ul>
 */
public final class LabourRules
{
    public static final TagKey<Item>  CRAFT_RESULTS = item("labour/craft_results");
    public static final TagKey<Item>  NEVER         = item("labour/never");
    public static final TagKey<Item>  SMELT_BASIC   = item("labour/smelt_basic");
    public static final TagKey<Item>  SMELT_IRON    = item("labour/smelt_iron");
    public static final TagKey<Item>  LOOSE         = item("labour/loose");
    public static final TagKey<Item>  SELF_SUPPLY   = item("labour/self_supply");
    public static final TagKey<Block> GATHER_SURFACE = block("labour/gather_surface");
    public static final TagKey<Block> GATHER_STONE   = block("labour/gather_stone");
    public static final TagKey<Item>  WOODCRAFT      = item("labour/woodcraft");
    public static final TagKey<Item>  BASIC_WOOD     = item("labour/basic_wood");
    public static final TagKey<Item>  STRIP_RESULTS  = item("labour/strip_results");
    public static final TagKey<Item>  CUTTER_INPUTS  = item("labour/cutter_inputs");
    public static final TagKey<Item>  STRIPPED_LOGS  = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", "stripped_logs"));
    public static final TagKey<Block> STRIPPED_LOG_BLOCKS  = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", "stripped_logs"));
    public static final TagKey<Block> STRIPPED_WOOD_BLOCKS = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", "stripped_woods"));

    /**
     * The longest straight run of horizontal logs a natural tree has (a longer one is a beam somebody built).
     */
    public static final int MAX_BRANCH_RUN = 5;

    private LabourRules()
    {
    }

    private static TagKey<Item> item(final String path)
    {
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Constants.MOD_ID, path));
    }

    private static TagKey<Block> block(final String path)
    {
        return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(Constants.MOD_ID, path));
    }

    /**
     * @return whether a labourer may craft the item.
     */
    public static boolean craftable(final Item item)
    {
        final ItemStack stack = new ItemStack(item);
        return stack.is(CRAFT_RESULTS) && !stack.is(NEVER);
    }

    /**
     * @return whether the item is a basic wood result: its recipes are used with basic wood inputs only.
     */
    public static boolean woodcraft(final Item item)
    {
        return new ItemStack(item).is(WOODCRAFT);
    }

    /**
     * @return whether the item may go into a basic wood recipe (logs, planks, sticks, stripped wood).
     */
    public static boolean basicWood(final Item item)
    {
        final ItemStack stack = new ItemStack(item);
        return stack.is(BASIC_WOOD) && !stack.is(NEVER);
    }

    /**
     * @return whether the item is a stripped log or wood that labour may make with an axe.
     */
    public static boolean stripResult(final Item item)
    {
        final ItemStack stack = new ItemStack(item);
        return stack.is(STRIP_RESULTS) && !stack.is(NEVER);
    }

    /**
     * @return whether the item is a stripped log labour gets by stripping a tree (stripped wood is crafted from those).
     */
    public static boolean strippedLog(final Item item)
    {
        return stripResult(item) && new ItemStack(item).is(STRIPPED_LOGS);
    }

    /**
     * @return whether labour brings the item to an Architect's Cutter as a material.
     */
    public static boolean cutterInput(final Item item)
    {
        final ItemStack stack = new ItemStack(item);
        return stack.is(CUTTER_INPUTS) && !stack.is(NEVER);
    }

    /**
     * @return whether the item may come out of a furnace in the smelt mode.
     */
    public static boolean smeltable(final Item item, final com.minecolonies.api.colony.labour.LabourModes.SmeltMode mode)
    {
        final ItemStack stack = new ItemStack(item);
        if (stack.is(NEVER))
        {
            return false;
        }
        return switch (mode)
        {
            case OFF -> false;
            case BASIC -> stack.is(SMELT_BASIC);
            case IRON -> stack.is(SMELT_BASIC) || stack.is(SMELT_IRON);
        };
    }

    /**
     * @return whether an idle builder may fetch the item himself for his own build (logs, planks, sticks, cobblestone, sand, dirt, gravel).
     */
    public static boolean selfSupplyItem(final Item item)
    {
        final ItemStack stack = new ItemStack(item);
        return stack.is(SELF_SUPPLY) && !stack.is(NEVER);
    }

    /**
     * @return whether the item is one a labourer picks up from the ground.
     */
    public static boolean looseItem(final ItemStack stack)
    {
        return stack.is(LOOSE) && !stack.is(NEVER);
    }

    /**
     * What a block gives when a labourer digs it by hand (or with the pickaxe): stone gives cobblestone, grass gives dirt, the rest itself.
     */
    public static Item dropOf(final BlockState state)
    {
        if (state.is(Blocks.STONE))
        {
            return Items.COBBLESTONE;
        }
        if (state.is(Blocks.GRASS_BLOCK))
        {
            return Items.DIRT;
        }
        return state.getBlock().asItem();
    }

    /**
     * @return the items every craftable result, the gatherable and the smeltable goods together: what a request may be answered with.
     */
    public static List<Item> candidateItems()
    {
        final List<Item> items = new ArrayList<>();
        for (final TagKey<Item> tag : List.of(CRAFT_RESULTS, SMELT_BASIC, SMELT_IRON, LOOSE, STRIP_RESULTS))
        {
            BuiltInRegistries.ITEM.getTagOrEmpty(tag).forEach(holder -> {
                final Item value = holder.value();
                if (value != Items.AIR && !items.contains(value) && !new ItemStack(value).is(NEVER))
                {
                    items.add(value);
                }
            });
        }
        return items;
    }

    /**
     * How many items one piece of the fuel smelts (vanilla: a furnace burns 200 ticks per item).
     */
    public static double smeltsPerFuel(final Item fuel)
    {
        final ItemStack stack = new ItemStack(fuel);
        if (fuel == Items.CHARCOAL || fuel == Items.COAL)
        {
            return 8.0D;
        }
        if (stack.is(ItemTags.LOGS_THAT_BURN) || stack.is(ItemTags.PLANKS))
        {
            return 1.5D;
        }
        if (fuel == Items.STICK)
        {
            return 0.5D;
        }
        return 0.5D;
    }

    /**
     * @return what a labourer may burn: charcoal, logs, planks, sticks (never coal from the stock), preferred first.
     */
    public static List<Item> fuels()
    {
        final List<Item> fuels = new ArrayList<>();
        fuels.add(Items.CHARCOAL);
        BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.LOGS_THAT_BURN).forEach(h -> fuels.add(h.value()));
        BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.PLANKS).forEach(h -> fuels.add(h.value()));
        fuels.add(Items.STICK);
        return fuels;
    }

    /**
     * @param tool a tool kind ({@code pickaxe}).
     * @return whether the item is such a tool.
     */
    public static boolean isTool(final String tool, final Item item)
    {
        final ItemStack stack = new ItemStack(item);
        return switch (tool)
        {
            case "pickaxe" -> stack.is(ItemTags.PICKAXES);
            case "axe" -> stack.is(ItemTags.AXES);
            case "shovel" -> stack.is(ItemTags.SHOVELS);
            default -> false;
        };
    }

    /**
     * @return the item to craft first for the tool kind.
     */
    public static Item toolItem(final String tool)
    {
        return switch (tool)
        {
            case "axe" -> Items.WOODEN_AXE;
            case "shovel" -> Items.WOODEN_SHOVEL;
            default -> Items.WOODEN_PICKAXE;
        };
    }

    /**
     * @return whether the block is one labourers dig at the surface (sand, dirt, gravel).
     */
    public static boolean surface(final BlockState state)
    {
        return state.is(GATHER_SURFACE);
    }

    /**
     * @return whether the block is surface stone.
     */
    public static boolean stone(final BlockState state)
    {
        return state.is(GATHER_STONE);
    }

    /**
     * @return whether the block is a log (the base of a natural tree).
     */
    public static boolean log(final BlockState state)
    {
        return state.is(BlockTags.LOGS);
    }

    /**
     * @return whether the block is a log as a tree grows it: a log, never stripped and never a bark block ({@code _wood}, {@code _hyphae}: crafted, no tree of the
     *   world grows them). Whether the axe would still strip it is the site's check ({@link LabourSites}).
     */
    public static boolean naturalLog(final BlockState state)
    {
        if (!state.is(BlockTags.LOGS) || state.is(STRIPPED_LOG_BLOCKS) || state.is(STRIPPED_WOOD_BLOCKS))
        {
            return false;
        }
        final String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return !path.startsWith("stripped_") && !path.contains("_stripped") && !path.endsWith("_wood") && !path.endsWith("_hyphae");
    }

    /**
     * @return whether the block next to a log shows that people built there: planks, stairs, slabs, fences, gates, doors, trapdoors, walls, beds, wool, carpets, signs,
     *   banners, buttons, plates, rails, cobblestone, glass, leaves somebody placed, or any block with a block entity but a bee nest. A tree touching one is not felled.
     */
    public static boolean construction(final BlockState state)
    {
        if (state.isAir())
        {
            return false;
        }
        if (state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock)
        {
            return state.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT);
        }
        if (state.hasBlockEntity())
        {
            return !state.is(Blocks.BEE_NEST);
        }
        return state.is(BlockTags.PLANKS) || state.is(BlockTags.STAIRS) || state.is(BlockTags.SLABS) || state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES)
                 || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.WALLS) || state.is(BlockTags.BEDS) || state.is(BlockTags.WOOL)
                 || state.is(BlockTags.WOOL_CARPETS) || state.is(BlockTags.ALL_SIGNS) || state.is(BlockTags.BANNERS) || state.is(BlockTags.BUTTONS)
                 || state.is(BlockTags.PRESSURE_PLATES) || state.is(BlockTags.RAILS) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.GLASS)
                 || state.getBlock() instanceof net.minecraft.world.level.block.TransparentBlock || state.getBlock() instanceof net.minecraft.world.level.block.StainedGlassPaneBlock
                 || state.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock;
    }

    /**
     * @return whether a placement of the block is recorded for the labourers (what they could otherwise take: logs, saplings, sand, dirt, gravel, stone).
     */
    public static boolean recordsPlacement(final BlockState state)
    {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.SAPLINGS) || surface(state) || stone(state) || state.is(Blocks.STONE) || state.is(Blocks.DIRT)
                 || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.GRAVEL);
    }
}
