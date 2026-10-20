package com.minecolonies.core.gametest;

import com.ldtteam.domumornamentum.IDomumOrnamentumApi;
import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock;
import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlockComponent;
import com.ldtteam.domumornamentum.recipe.architectscutter.ArchitectsCutterRecipe;
import com.ldtteam.domumornamentum.recipe.architectscutter.ArchitectsCutterRecipeInput;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;
import com.minecolonies.core.colony.idle.IdleCitizenArbiter;
import com.minecolonies.core.colony.idle.IdleCitizenSettings;
import com.minecolonies.core.colony.labour.DirectedOrder;
import com.minecolonies.core.colony.labour.DirectedOrders;
import com.minecolonies.core.colony.labour.LabourClaim;
import com.minecolonies.core.colony.labour.LabourCutter;
import com.minecolonies.core.colony.labour.LabourPlanner;
import com.minecolonies.core.colony.labour.LabourRules;
import com.minecolonies.core.colony.labour.LabourSession;
import com.minecolonies.core.colony.labour.LabourSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The expanded basic tier (EXPANDED LABOUR, 2026-10-20): wood stairs made from a tree at a table, stripped logs of the tree's own species with an axe, a Domum
 * Ornamentum block made at a physical Architect's Cutter as exactly the requested variant (and a variant of a material labour does not get refused with its reason),
 * and a directed stone order whose worker takes a pickaxe out of the warehouse first, or makes one from a tree in the order's area only. Real colonies and citizens, ticked by the test. Run with
 * {@code MINECOLONIES_GAMETEST_ONLY=expanded}.
 */
final class ExpandedLabourGameTests
{
    private ExpandedLabourGameTests()
    {
    }

    private static void begin()
    {
        com.minecolonies.core.colony.labour.LabourStats.debug = true;
        LabourSettings.clearOverrides();
        LabourSettings.override("workScale", 8.0D);
        LabourSettings.override("keepTrees", 0);
        LabourSettings.override("stockpile", false);
        IdleCitizenSettings.overrideForTests(null, null, null);
    }

    private static void end()
    {
        com.minecolonies.core.colony.labour.LabourStats.debug = false;
        LabourSettings.clearOverrides();
    }

    /**
     * The fixture colony without automatic hiring, its area forced loaded for the test and the box's barrier roof gone where the work is (the sky-down scans).
     */
    private static RsFixture found(final GameTestHelper helper, final String name)
    {
        final RsFixture f = RsFixture.found(helper, name, 2);
        final var hiring = f.colony.getSettings().getSetting(BuildingTownHall.AUTO_HIRING_MODE);
        if (hiring.getValue())
        {
            hiring.trigger();
        }
        final BlockPos min = helper.absolutePos(new BlockPos(-8, 1, -8));
        final BlockPos max = helper.absolutePos(new BlockPos(48, 1, 40));
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++)
        {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++)
            {
                f.level.setChunkForced(cx, cz, true);
            }
        }
        for (int x = 18; x <= 46; x++)
        {
            for (int z = 12; z <= 36; z++)
            {
                for (int y = 20; y <= 60; y++)
                {
                    if (helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.BARRIER))
                    {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        return f;
    }

    private static void tree(final GameTestHelper helper, final int x, final int z, final int height, final Block log, final Block leavesBlock)
    {
        helper.setBlock(new BlockPos(x, 0, z), Blocks.DIRT.defaultBlockState());
        for (int y = 1; y <= height; y++)
        {
            helper.setBlock(new BlockPos(x, y, z), log.defaultBlockState());
        }
        final BlockState leaves = leavesBlock.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false).setValue(LeavesBlock.DISTANCE, 1);
        for (int y = height - 1; y <= height; y++)
        {
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    if (dx != 0 || dz != 0)
                    {
                        helper.setBlock(new BlockPos(x + dx, y, z + dz), leaves);
                    }
                }
            }
        }
        helper.setBlock(new BlockPos(x, height + 1, z), leaves);
    }

    private static int blocks(final GameTestHelper helper, final Block block, final int x, final int z, final int y0, final int y1)
    {
        int n = 0;
        for (int y = y0; y <= y1; y++)
        {
            if (helper.getBlockState(new BlockPos(x, y, z)).is(block))
            {
                n++;
            }
        }
        return n;
    }

    private static IToken<?> ask(final IBuilding requester, final ItemStack stack, final int count)
    {
        return requester.createRequest(new Stack(stack.copyWithCount(1), count, count, true), false);
    }

    private static int in(final IBuilding building, final Predicate<ItemStack> matcher)
    {
        return InventoryUtils.getItemCountInItemHandler(building.getItemHandlerCap(), matcher);
    }

    private static int carrying(final ICitizenData citizen, final Predicate<ItemStack> matcher)
    {
        return InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), matcher);
    }

    private static void give(final ICitizenData citizen, final ItemStack stack)
    {
        InventoryUtils.addItemStackToItemHandler(citizen.getInventory(), stack);
    }

    private static void poll(final GameTestHelper helper, final Colony colony, final int maxTicks, final BooleanSupplier check, final Runnable onDone,
      final Supplier<String> failure)
    {
        final int[] elapsed = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            helper.getLevel().getServer().clockManager().setTotalTicks(helper.getLevel().registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
            colony.getRequestManager().tick();
            IdleCitizenArbiter.tick(colony);
            colony.getLabourBoard().tick();
            if (check.getAsBoolean())
            {
                onDone.run();
                return;
            }
            if (elapsed[0] % 500 == 0)
            {
                Log.getLogger().info("EXPANDED poll t={} {}", elapsed[0], failure.get());
            }
            elapsed[0] += 20;
            if (elapsed[0] >= maxTicks)
            {
                end();
                helper.assertTrue(false, "timeout after " + maxTicks + " ticks: " + failure.get());
                return;
            }
            helper.runAfterDelay(20, () -> step[0].run());
        };
        helper.runAfterDelay(1, step[0]);
    }

    private static String describe(final RsFixture f, final IToken<?> token, final ICitizenData citizen)
    {
        final var session = IdleCitizenArbiter.sessionOf(citizen);
        final Colony colony = (Colony) f.colony;
        final LabourClaim claim = colony.getLabourBoard().claimOf(citizen.getId());
        return "request " + (token == null ? "-" : f.describe(token)) + ", session " + (session == null ? "none" : session.getTaskId() + "/" + (session instanceof LabourSession l ? l.phaseName()
                     : session instanceof com.minecolonies.core.colony.labour.DirectedSession d ? d.phaseName() : "?")) + ", claim "
                 + (claim == null ? "none" : claim.amount() + "x " + claim.target() + " exact " + claim.exact()) + ", why "
                 + (token == null ? "-" : colony.getLabourBoard().blockedWhy(token)) + ", carries " + LabourSession.inventoryOf(citizen);
    }

    // ------------------------------------------------------------------ the cutter's variants

    /**
     * A loaded cutter recipe whose every component takes both materials (two slots at most, none optional): its own assembled result with the material in each slot.
     */
    private static ItemStack assemble(final ServerLevel level, final Block material, final Block other, final Block firstSlot)
    {
        for (final RecipeHolder<?> holder : level.recipeAccess().getRecipes())
        {
            if (!(holder.value() instanceof final ArchitectsCutterRecipe recipe) || !(recipe.getBlock() instanceof final IMateriallyTexturedBlock block))
            {
                continue;
            }
            final List<IMateriallyTexturedBlockComponent> components = new ArrayList<>(block.getComponents());
            if (components.isEmpty() || components.size() > 2)
            {
                continue;
            }
            boolean ok = true;
            for (final IMateriallyTexturedBlockComponent component : components)
            {
                ok &= !component.isOptional() && material.defaultBlockState().is(component.getValidSkins()) && other.defaultBlockState().is(component.getValidSkins())
                        && firstSlot.defaultBlockState().is(components.get(0).getValidSkins());
            }
            if (!ok)
            {
                continue;
            }
            final SimpleContainer container = new SimpleContainer(components.size());
            for (int i = 0; i < components.size(); i++)
            {
                container.setItem(i, new ItemStack(i == 0 ? firstSlot : material));
            }
            final ArchitectsCutterRecipeInput input = new ArchitectsCutterRecipeInput(container);
            if (recipe.matches(input, level))
            {
                final ItemStack result = recipe.assemble(input);
                if (!result.isEmpty())
                {
                    return result;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * A builder hut asks for a Domum Ornamentum block in oak planks. The labourer holds oak and spruce planks; he walks to the colony's Architect's Cutter, makes the
     * block from his oak planks through the loaded recipe and hands in exactly that variant. The spruce planks are untouched, no spruce variant exists anywhere, and the
     * oak planks taken are exactly one per slot and run (what was made is in the hut, the warehouse or his hands).
     */
    public static void cutterMakesTheExactVariant(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "expanded cutter");
        final Colony colony = (Colony) f.colony;
        final ItemStack oak = assemble(f.level, Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.OAK_PLANKS);
        final ItemStack spruce = assemble(f.level, Blocks.SPRUCE_PLANKS, Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS);
        helper.assertTrue(!oak.isEmpty() && !spruce.isEmpty(), "no cutter recipe takes oak and spruce planks");
        helper.assertTrue(oak.is(spruce.getItem()) && !ItemStack.isSameItemSameComponents(oak, spruce), "the two variants are not told apart: " + oak + " / " + spruce);
        helper.setBlock(new BlockPos(22, 1, 20), IDomumOrnamentumApi.getInstance().getBlocks().getArchitectsCutter().defaultBlockState());
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            give(idle, new ItemStack(Items.OAK_PLANKS, 16));
            give(idle, new ItemStack(Items.SPRUCE_PLANKS, 16));
            final Predicate<ItemStack> exact = s -> ItemStack.isSameItemSameComponents(s, oak);
            final Predicate<ItemStack> wrong = s -> s.is(oak.getItem()) && !ItemStack.isSameItemSameComponents(s, oak);
            final int wanted = oak.getCount();
            final IToken<?> token = ask(f.builder, oak, wanted);
            poll(helper, colony, 9000, () -> in(f.builder, exact) >= wanted, () -> {
                helper.assertTrue(in(f.builder, wrong) == 0 && in(f.warehouse, wrong) == 0 && carrying(idle, wrong) == 0, "a wrong variant was made");
                helper.assertTrue(carrying(idle, s -> s.is(Items.SPRUCE_PLANKS)) == 16, "spruce planks were used: " + carrying(idle, s -> s.is(Items.SPRUCE_PLANKS)));
                final int made = in(f.builder, exact) + in(f.warehouse, exact) + carrying(idle, exact);
                final int used = 16 - carrying(idle, s -> s.is(Items.OAK_PLANKS));
                final LabourCutter.Job job = colony.getLabourBoard().cutJob(oak).job();
                helper.assertTrue(job != null, "no job for the variant");
                final int slots = job.inputs().size();
                helper.assertTrue(used % slots == 0 && made == used / slots * job.perRun(), "planks used " + used + " for " + made + " made (" + slots + " slots, " + job.perRun() + " per run)");
                end();
                helper.succeed();
            }, () -> describe(f, token, idle));
        });
    }

    /**
     * A Domum Ornamentum block whose first slot is a material labour does not get (not wood or plain stone): no claim is made and the request says why
     * ({@link LabourPlanner.Why#CUTTER_INPUT}); the labourer's planks stay.
     */
    public static void cutterRefusesAnUnsupportedMaterial(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "expanded cutter input");
        final Colony colony = (Colony) f.colony;
        Block odd = null;
        for (final Block candidate : List.of(Blocks.GOLD_BLOCK, Blocks.IRON_BLOCK, Blocks.BRICKS, Blocks.QUARTZ_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK))
        {
            if (!LabourRules.cutterInput(candidate.asItem()) && !assemble(f.level, Blocks.OAK_PLANKS, Blocks.OAK_PLANKS, candidate).isEmpty())
            {
                odd = candidate;
                break;
            }
        }
        helper.assertTrue(odd != null, "no cutter recipe takes an unsupported material next to oak planks");
        final ItemStack stack = assemble(f.level, Blocks.OAK_PLANKS, Blocks.OAK_PLANKS, odd);
        helper.setBlock(new BlockPos(22, 1, 20), IDomumOrnamentumApi.getInstance().getBlocks().getArchitectsCutter().defaultBlockState());
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            give(idle, new ItemStack(Items.OAK_PLANKS, 16));
            final IToken<?> token = ask(f.builder, stack, stack.getCount());
            poll(helper, colony, 3000, () -> colony.getLabourBoard().blockedWhy(token) == LabourPlanner.Why.CUTTER_INPUT, () -> {
                helper.assertTrue(colony.getLabourBoard().claimOf(idle.getId()) == null || colony.getLabourBoard().claimOf(idle.getId()).exact().isEmpty(), "a claim for the block");
                helper.assertTrue(carrying(idle, s -> s.is(Items.OAK_PLANKS)) == 16, "planks were used");
                helper.assertTrue(in(f.builder, s -> s.is(stack.getItem())) == 0, "the block was handed in");
                end();
                helper.succeed();
            }, () -> describe(f, token, idle));
        });
    }

    // ------------------------------------------------------------------ wood

    /**
     * A builder hut asks for oak stairs; nothing in the colony makes them. The labourer fells the oak, makes planks, and at the table the stairs: the recipe's own
     * result, the plain item.
     */
    public static void stairsFromATree(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "expanded stairs");
        final Colony colony = (Colony) f.colony;
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        tree(helper, 34, 20, 5, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final IToken<?> token = ask(f.builder, new ItemStack(Items.OAK_STAIRS), 4);
            poll(helper, colony, 9000, () -> in(f.builder, s -> s.is(Items.OAK_STAIRS)) >= 4, () -> {
                helper.assertTrue(in(f.builder, s -> s.is(Items.OAK_STAIRS) && !ItemStack.isSameItemSameComponents(s, new ItemStack(Items.OAK_STAIRS))) == 0,
                  "stairs with made-up components");
                end();
                helper.succeed();
            }, () -> describe(f, token, idle));
        });
    }

    /**
     * A builder hut asks for stripped oak logs. The labourer has a wooden axe; an oak and a birch stand. He strips the oak's logs where they stand with the axe's own
     * transformer, fells them and hands in stripped oak; the birch stands whole, and the axe wore.
     */
    public static void strippedLogsOfTheTreesSpecies(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "expanded strip");
        final Colony colony = (Colony) f.colony;
        tree(helper, 34, 20, 5, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
        tree(helper, 40, 28, 5, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            give(idle, new ItemStack(Items.WOODEN_AXE));
            final IToken<?> token = ask(f.builder, new ItemStack(Items.STRIPPED_OAK_LOG), 2);
            poll(helper, colony, 9000, () -> in(f.builder, s -> s.is(Items.STRIPPED_OAK_LOG)) >= 2, () -> {
                helper.assertTrue(blocks(helper, Blocks.BIRCH_LOG, 40, 28, 1, 5) == 5, "the birch was touched");
                helper.assertTrue(in(f.builder, s -> s.is(Items.STRIPPED_BIRCH_LOG)) == 0, "stripped birch was handed in");
                int wear = 0;
                for (int slot = 0; slot < idle.getInventory().getSlots(); slot++)
                {
                    final ItemStack stack = idle.getInventory().getStackInSlot(slot);
                    if (stack.is(Items.WOODEN_AXE))
                    {
                        wear = Math.max(wear, stack.getDamageValue());
                    }
                }
                helper.assertTrue(wear > 0 || carrying(idle, s -> s.is(Items.WOODEN_AXE)) == 0, "the axe did not wear");
                end();
                helper.succeed();
            }, () -> describe(f, token, idle));
        });
    }

    // ------------------------------------------------------------------ directed stone

    /**
     * A directed cobblestone order for a citizen without a pickaxe is accepted because a stone pickaxe lies in the warehouse: he takes it (through the ledger), digs
     * the stone inside the area and hands in the amount.
     */
    public static void directedStoneTakesAPickaxeFromStock(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "expanded directed pickaxe");
        final Colony colony = (Colony) f.colony;
        // (digging the ground is off by default, for directed orders too: allowed for this colony only)
        LabourSettings.allowDiggingForTest(colony.getID(), true);
        for (int x = 30; x <= 33; x++)
        {
            for (int z = 20; z <= 23; z++)
            {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
            }
        }
        f.stock(0, new ItemStack(Items.STONE_PICKAXE));
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final Predicate<ItemStack> cobble = s -> s.is(Items.COBBLESTONE);
            final int before = in(f.warehouse, cobble);
            final BlockPos a = helper.absolutePos(new BlockPos(29, 1, 19));
            final BlockPos b = helper.absolutePos(new BlockPos(34, 1, 24));
            final DirectedOrders.Start start = colony.getLabourBoard().directed().start(List.of(idle.getId()), DirectedOrder.Resource.COBBLESTONE, 4,
              DirectedOrder.Area.of(a.getX(), a.getZ(), b.getX(), b.getZ()), null);
            helper.assertTrue(start.order() != null, "refused: " + start.refusal());
            final DirectedOrder order = start.order();
            poll(helper, colony, 9000, () -> !order.active(), () -> {
                helper.assertTrue(order.state() == DirectedOrder.State.COMPLETED, "the order ended as " + order.state() + " (" + order.why() + ")");
                helper.assertTrue(in(f.warehouse, cobble) - before == 4, "cobblestone in the warehouse: " + (in(f.warehouse, cobble) - before));
                helper.assertTrue(carrying(idle, s -> s.is(Items.STONE_PICKAXE)) == 1 && f.physical(s -> s.is(Items.STONE_PICKAXE)) == 0, "the stock pickaxe was not taken");
                LabourSettings.allowDiggingForTest(colony.getID(), false);
                end();
                helper.succeed();
            }, () -> "order " + order.state() + " " + order.why() + " delivered " + order.delivered() + ", " + describe(f, null, idle));
        });
    }

    /**
     * The manager's area bounds the pickaxe: a citizen without one, no pickaxe and no planks in stock, an oak standing in the colony but outside the order's area. The
     * stone order is refused with NO_TOOL (the usual labour reach would fell that oak, the order may not), nothing is started and the oak stands whole.
     */
    public static void directedToolOnlyOutsideTheArea(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "expanded directed tool outside");
        final Colony colony = (Colony) f.colony;
        stoneField(helper);
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        tree(helper, 34, 32, 5, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final DirectedOrders orders = colony.getLabourBoard().directed();
            // (the order page's hint, before an area is chosen, still sees the oak)
            helper.assertTrue(orders.canEquip(idle, "pickaxe"), "the hint does not see the oak in reach");
            final DirectedOrders.Start start = orders.start(List.of(idle.getId()), DirectedOrder.Resource.COBBLESTONE, 4, toolArea(helper), null);
            helper.assertTrue(start.order() == null && start.refusal() == DirectedOrders.Refusal.NO_TOOL, "not refused with NO_TOOL: " + start.refusal());
            helper.assertTrue(orders.orders().isEmpty(), "a refused start left an order");
            helper.runAfterDelay(200, () -> {
                helper.assertTrue(blocks(helper, Blocks.OAK_LOG, 34, 32, 1, 5) == 5, "the oak outside the area was touched");
                helper.assertTrue(carrying(idle, s -> s.is(Items.OAK_LOG) || s.is(Items.WOODEN_PICKAXE)) == 0, "he got logs or a pickaxe");
                end();
                helper.succeed();
            });
        });
    }

    /**
     * The same, with a second oak inside the area: the order is accepted, he fells only the oak in the area to make his wooden pickaxe at the table, digs the stone and
     * hands in 4 cobblestone. The oak outside stands whole.
     */
    public static void directedToolFromATreeInTheArea(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "expanded directed tool inside");
        final Colony colony = (Colony) f.colony;
        // (digging the ground is off by default, for directed orders too: allowed for this colony only)
        LabourSettings.allowDiggingForTest(colony.getID(), true);
        stoneField(helper);
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        tree(helper, 34, 32, 5, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
        tree(helper, 38, 24, 5, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final Predicate<ItemStack> cobble = s -> s.is(Items.COBBLESTONE);
            final int before = in(f.warehouse, cobble);
            final DirectedOrders.Start start = colony.getLabourBoard().directed().start(List.of(idle.getId()), DirectedOrder.Resource.COBBLESTONE, 4, toolArea(helper), null);
            helper.assertTrue(start.order() != null, "refused: " + start.refusal());
            final DirectedOrder order = start.order();
            poll(helper, colony, 12000, () -> !order.active(), () -> {
                helper.assertTrue(order.state() == DirectedOrder.State.COMPLETED, "the order ended as " + order.state() + " (" + order.why() + ")");
                helper.assertTrue(in(f.warehouse, cobble) - before == 4, "cobblestone in the warehouse: " + (in(f.warehouse, cobble) - before));
                helper.assertTrue(blocks(helper, Blocks.OAK_LOG, 34, 32, 1, 5) == 5, "the oak outside the area was touched");
                helper.assertTrue(blocks(helper, Blocks.OAK_LOG, 38, 24, 1, 5) == 0, "the oak in the area was not felled");
                helper.assertTrue(carrying(idle, s -> s.is(ItemTags.PICKAXES)) >= 1, "he has no pickaxe");
                LabourSettings.allowDiggingForTest(colony.getID(), false);
                end();
                helper.succeed();
            }, () -> "order " + order.state() + " " + order.why() + " delivered " + order.delivered() + ", " + describe(f, null, idle));
        });
    }

    private static void stoneField(final GameTestHelper helper)
    {
        for (int x = 30; x <= 33; x++)
        {
            for (int z = 20; z <= 23; z++)
            {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
            }
        }
    }

    /**
     * @return the stone orders' area: x 28..40, z 18..26 (the stone field, room for one oak at 38/24; the oak at 34/32 stands outside).
     */
    private static DirectedOrder.Area toolArea(final GameTestHelper helper)
    {
        final BlockPos a = helper.absolutePos(new BlockPos(28, 1, 18));
        final BlockPos b = helper.absolutePos(new BlockPos(40, 1, 26));
        return DirectedOrder.Area.of(a.getX(), a.getZ(), b.getX(), b.getZ());
    }

    // ------------------------------------------------------------------ registration

    static final String[] NAMES = {"labour_expanded_cutter_variant", "labour_expanded_cutter_input", "labour_expanded_stairs", "labour_expanded_strip",
      "labour_expanded_directed_pickaxe", "labour_expanded_directed_tool_outside", "labour_expanded_directed_tool_inside"};

    static java.util.function.Consumer<GameTestHelper> test(final String name)
    {
        return switch (name)
        {
            case "labour_expanded_cutter_variant" -> ExpandedLabourGameTests::cutterMakesTheExactVariant;
            case "labour_expanded_cutter_input" -> ExpandedLabourGameTests::cutterRefusesAnUnsupportedMaterial;
            case "labour_expanded_stairs" -> ExpandedLabourGameTests::stairsFromATree;
            case "labour_expanded_strip" -> ExpandedLabourGameTests::strippedLogsOfTheTreesSpecies;
            case "labour_expanded_directed_tool_outside" -> ExpandedLabourGameTests::directedToolOnlyOutsideTheArea;
            case "labour_expanded_directed_tool_inside" -> ExpandedLabourGameTests::directedToolFromATreeInTheArea;
            default -> ExpandedLabourGameTests::directedStoneTakesAPickaxeFromStock;
        };
    }
}
