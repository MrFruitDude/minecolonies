package com.minecolonies.core.colony.labour;

import com.minecolonies.core.colony.Colony;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The colony as the planner sees it: the recipes the basic tier allows, the sites in reach, the tables, what a specialist covers, what the warehouses hold.
 */
public final class LabourEnv implements LabourPlanner.Env<Item>
{
    private final Colony       colony;
    private final LabourBoard  board;
    private final ServerLevel  level;
    private final List<Item>   fuels = LabourRules.fuels();
    private final boolean      withdraw;
    /** The hut whose request is planned, or null: a good only that hut itself covers is not covered for it (a new miner's own cobblestone). */
    @org.jetbrains.annotations.Nullable
    private final net.minecraft.core.BlockPos requester;
    /** The citizen whose plan this is (his own place in a shaft counts as free for him), or -1. */
    private int citizenId = -1;
    private Set<Item> surfaceDrops;
    private Set<Item> stoneDrops;

    /**
     * @param withdraw whether the labourer may take stock out of the warehouses for goods a specialist covers (requests and the builders' own builds may; the stock goals never
     *                 do: they only keep up what nobody makes, they do not take from what somebody does)
     */
    public LabourEnv(final Colony colony, final LabourBoard board, final boolean withdraw)
    {
        this(colony, board, withdraw, null);
    }

    /**
     * @param requester the hut whose request is planned (its own coverage does not count for it), or null.
     */
    public LabourEnv(final Colony colony, final LabourBoard board, final boolean withdraw, @org.jetbrains.annotations.Nullable final net.minecraft.core.BlockPos requester)
    {
        this.requester = requester;
        this.withdraw = withdraw;
        this.colony = colony;
        this.board = board;
        this.level = (ServerLevel) colony.getWorld();
    }

    /**
     * @return this colony view for a request of the hut ({@link #requester}).
     */
    public LabourEnv forRequester(@org.jetbrains.annotations.Nullable final net.minecraft.core.BlockPos hut)
    {
        if (java.util.Objects.equals(hut, requester))
        {
            return this;
        }
        final LabourEnv env = new LabourEnv(colony, board, withdraw, hut);
        env.citizenId = citizenId;
        return env;
    }

    /**
     * @return this colony view for the plan of the citizen (a shaft segment he digs counts as free for him).
     */
    public LabourEnv forCitizen(final int citizen)
    {
        this.citizenId = citizen;
        return this;
    }

    private static boolean stoneTool(final Item item)
    {
        final String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        return path.startsWith("stone_") && (new ItemStack(item).is(ItemTags.PICKAXES) || new ItemStack(item).is(ItemTags.AXES) || new ItemStack(item).is(ItemTags.SHOVELS)
                                                || new ItemStack(item).is(ItemTags.HOES) || new ItemStack(item).is(ItemTags.SWORDS));
    }

    @Override
    public List<LabourPlanner.Recipe<Item>> recipesFor(final Item output)
    {
        if (!LabourSettings.stoneTools() && stoneTool(output))
        {
            return List.of();
        }
        final List<LabourPlanner.Recipe<Item>> base = LabourRecipes.recipesFor(level, output);
        if (LabourSettings.pocketCrafting())
        {
            return base;
        }
        final List<LabourPlanner.Recipe<Item>> list = new ArrayList<>(base.size());
        for (final LabourPlanner.Recipe<Item> recipe : base)
        {
            list.add(recipe.kind() == LabourPlanner.Kind.CRAFT && recipe.small()
                       ? new LabourPlanner.Recipe<>(recipe.id(), recipe.inputs(), recipe.output(), recipe.outputCount(), recipe.kind(), false)
                       : recipe);
        }
        return list;
    }

    private void loadDrops()
    {
        if (surfaceDrops != null)
        {
            return;
        }
        surfaceDrops = new HashSet<>();
        stoneDrops = new HashSet<>();
        BuiltInRegistries.BLOCK.getTagOrEmpty(LabourRules.GATHER_SURFACE).forEach(h -> surfaceDrops.add(LabourRules.dropOf(h.value().defaultBlockState())));
        BuiltInRegistries.BLOCK.getTagOrEmpty(LabourRules.GATHER_STONE).forEach(h -> {
            final BlockState state = h.value().defaultBlockState();
            stoneDrops.add(LabourRules.dropOf(state));
        });
    }

    @Override
    public List<LabourPlanner.GatherDef<Item>> gathersOf(final Item item)
    {
        loadDrops();
        final List<LabourPlanner.GatherDef<Item>> defs = new ArrayList<>(2);
        final ItemStack stack = new ItemStack(item);
        if (LabourRules.looseItem(stack))
        {
            defs.add(new LabourPlanner.GatherDef<>(LabourPlanner.GatherKind.LOOSE, item, null));
        }
        if (LabourRules.strippedLog(item))
        {
            // (a stripped log is a log too: it is had by stripping a tree of its species with an axe, never by felling another kind)
            defs.add(new LabourPlanner.GatherDef<>(LabourPlanner.GatherKind.STRIP, item, "axe"));
        }
        else if (stack.is(ItemTags.LOGS))
        {
            defs.add(new LabourPlanner.GatherDef<>(LabourPlanner.GatherKind.LOG, item, null));
        }
        else if (surfaceDrops.contains(item))
        {
            defs.add(new LabourPlanner.GatherDef<>(LabourPlanner.GatherKind.SURFACE, item, null));
        }
        else if (stoneDrops.contains(item))
        {
            defs.add(new LabourPlanner.GatherDef<>(LabourPlanner.GatherKind.STONE, item, "pickaxe"));
        }
        if (item == net.minecraft.world.item.Items.COBBLESTONE)
        {
            // r4: the colony's stone shafts, dug with a wood or stone pickaxe only (after a dig zone, which needs no walk underground)
            defs.add(new LabourPlanner.GatherDef<>(LabourPlanner.GatherKind.SHAFT, item, LabourRules.SHAFT_TOOL));
        }
        return defs;
    }

    @Override
    public boolean canGather(final LabourPlanner.GatherDef<Item> gather)
    {
        final LabourSites sites = board.sites();
        return switch (gather.kind())
        {
            case LOG -> sites.hasTree(gather.item());
            case STRIP -> sites.hasStripTree(gather.item());
            case SURFACE, STONE -> sites.hasSurface(gather.item());
            case LOOSE -> sites.nearestLoose(gather.item(), sites.anchor(), board.takenEntities()) != null;
            // (once a miner or quarry covers cobblestone no shaft is dug, not even for a request it does not cover: the shafts stay as they are)
            case SHAFT -> !board.coverage().covered(net.minecraft.world.item.Items.COBBLESTONE) && board.shafts().hasWork(citizenId);
        };
    }

    @Override
    public boolean hasTable()
    {
        return board.sites().hasTable();
    }

    @Override
    public boolean hasFurnace()
    {
        return board.sites().hasFurnace();
    }

    @Override
    public boolean covered(final Item item)
    {
        return board.coverage().coveredFor(item, requester);
    }

    @Override
    public int withdrawable(final Item item)
    {
        return withdraw ? board.stock(item) : 0;
    }

    @Override
    public boolean hasTool(final String tool, final Map<Item, Integer> inventory)
    {
        for (final Map.Entry<Item, Integer> entry : inventory.entrySet())
        {
            if (entry.getValue() > 0 && LabourRules.isTool(tool, entry.getKey()))
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public Item toolItem(final String tool)
    {
        return LabourRules.toolItem(tool);
    }

    @Override
    public List<Item> fuels()
    {
        return fuels;
    }

    @Override
    public double smeltsPerFuel(final Item fuel)
    {
        return LabourRules.smeltsPerFuel(fuel);
    }

    @Override
    public boolean smeltAllowed(final LabourPlanner.Recipe<Item> recipe)
    {
        return LabourRules.smeltable(recipe.output(), LabourSettings.smelt());
    }
}
