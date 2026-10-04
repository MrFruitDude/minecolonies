package com.minecolonies.core.gametest;

import com.minecolonies.api.equipment.ModEquipmentTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.BlockTransformer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockTransformers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.neoforged.neoforge.common.DataMapHooks;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NF48 (NeoForge 26.3.0.48-beta, #3575): {@code DataMapHooks.appendDatamapTransformers} now takes the transformer holder instead
 * of the item stack. {@link ModEquipmentTypes#simulateBlockTransform} (farmer tilling, quarrier/miner/nether tool checks) was
 * moved to {@code DataMapHooks.getAllTransformers(holder)}. This test pins what that call site must still do:
 * <ul>
 * <li>vanilla transforms of the tool's own transformer (hoe: dirt -> farmland, coarse dirt -> dirt; axe: log -> stripped log),</li>
 * <li>the transformer's disallowed faces (hoe clicked from below does nothing),</li>
 * <li>no component (a stick) -> null,</li>
 * <li>and data-map-added transforms of that transformer (a test-only axe transform clay -> dirt, injected into NeoForge's
 *     data-map transformer table for the duration of the test and removed after).</li>
 * </ul>
 */
public final class EquipmentTransformGameTests
{
    private EquipmentTransformGameTests()
    {
    }

    public static void simulateBlockTransform(final GameTestHelper helper)
    {
        final List<String> failures = new ArrayList<>();

        expect(helper, failures, "hoe on dirt (face up)", Items.IRON_HOE, Blocks.DIRT, Direction.UP, Blocks.FARMLAND);
        expect(helper, failures, "hoe on coarse dirt (face up)", Items.IRON_HOE, Blocks.COARSE_DIRT, Direction.UP, Blocks.DIRT);
        expect(helper, failures, "hoe on dirt (face down, disallowed)", Items.IRON_HOE, Blocks.DIRT, Direction.DOWN, null);
        expect(helper, failures, "axe on oak log", Items.IRON_AXE, Blocks.OAK_LOG, Direction.UP, Blocks.STRIPPED_OAK_LOG);
        expect(helper, failures, "stick on dirt (no transformer)", Items.STICK, Blocks.DIRT, Direction.UP, null);
        expect(helper, failures, "axe on clay (no data-map transform yet)", Items.IRON_AXE, Blocks.CLAY, Direction.UP, null);

        // Data-map path: add a test-only axe transform clay -> dirt the way DataMapHooks.onDataMapsUpdated does.
        final List<BlockTransformer.BlockTransformData> added;
        final BlockTransformer.BlockTransformData extra =
          BlockTransformer.BlockTransformData.builder(BlockPredicate.matchesBlocks(Blocks.CLAY), Blocks.DIRT).build();
        final Map<ResourceKey<BlockTransformer>, List<BlockTransformer.BlockTransformData>> table = datamapTransformers(helper);
        final boolean hadAxe = table.containsKey(BlockTransformers.AXE);
        added = table.computeIfAbsent(BlockTransformers.AXE, k -> new ArrayList<>());
        added.add(extra);
        try
        {
            expect(helper, failures, "axe on clay (data-map transform)", Items.IRON_AXE, Blocks.CLAY, Direction.UP, Blocks.DIRT);
            expect(helper, failures, "hoe on clay (data-map transform is axe only)", Items.IRON_HOE, Blocks.CLAY, Direction.UP, null);
        }
        finally
        {
            added.remove(extra);
            if (!hadAxe && added.isEmpty())
            {
                table.remove(BlockTransformers.AXE);
            }
        }

        if (!failures.isEmpty())
        {
            throw helper.assertionException(Component.literal("simulateBlockTransform: " + String.join("; ", failures)));
        }
        helper.succeed();
    }

    private static void expect(
      final GameTestHelper helper,
      final List<String> failures,
      final String what,
      final net.minecraft.world.item.Item tool,
      final Block block,
      final Direction face,
      final Block expected)
    {
        final BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative.above(), Blocks.AIR);
        helper.setBlock(relative, block);
        final BlockPos pos = helper.absolutePos(relative);
        final BlockState result = ModEquipmentTypes.simulateBlockTransform(new ItemStack(tool), helper.getLevel(), pos, face);
        final Block got = result == null ? null : result.getBlock();
        if (got != expected)
        {
            failures.add(what + ": expected " + expected + " got " + result);
        }
        if (result != null && block == Blocks.OAK_LOG && result.getValue(RotatedPillarBlock.AXIS) != helper.getLevel().getBlockState(pos).getValue(RotatedPillarBlock.AXIS))
        {
            failures.add(what + ": log axis not kept: " + result);
        }
        // simulate must not change the world
        if (helper.getLevel().getBlockState(pos).getBlock() != block)
        {
            failures.add(what + ": simulate changed the block to " + helper.getLevel().getBlockState(pos));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<ResourceKey<BlockTransformer>, List<BlockTransformer.BlockTransformData>> datamapTransformers(final GameTestHelper helper)
    {
        try
        {
            final Field field = DataMapHooks.class.getDeclaredField("DATAMAP_BLOCK_TRANSFORMERS");
            field.setAccessible(true);
            return (Map<ResourceKey<BlockTransformer>, List<BlockTransformer.BlockTransformData>>) field.get(null);
        }
        catch (final ReflectiveOperationException | RuntimeException e)
        {
            throw helper.assertionException(Component.literal("fixture: cannot reach DataMapHooks.DATAMAP_BLOCK_TRANSFORMERS: " + e));
        }
    }
}
