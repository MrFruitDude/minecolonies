package com.minecolonies.core.entity.ai.workers.util;

import com.minecolonies.api.items.ModTags;
import com.minecolonies.api.util.constant.Constants;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Forestry (r6 Sol H1, round 7 strategy): the cap blocks a player placed (huge mushroom blocks and stems, nether and warped wart blocks, shroomlight),
 * marked in saved per-chunk data. A Forester never cuts a marked block; the tree snapshot rules stay as a second guard. The mark is set when a player places
 * the block and cleared when it is broken or replaced; a piston carries it to the moved block and keeps it at the old place too (conservative). A lookup is
 * one hash probe in the block's chunk; nothing is scanned.
 */
public final class PlacedCaps
{
    /**
     * The chunk attachment's register (mod bus).
     */
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Constants.MOD_ID);

    /**
     * The marked positions of one chunk.
     */
    public static final Supplier<AttachmentType<Marks>> PLACED_CAPS = ATTACHMENTS.register("placed_caps", () -> AttachmentType.builder(Marks::new)
      .serialize(new IAttachmentSerializer<Marks>()
      {
          @Override
          public Marks read(final IAttachmentHolder holder, final ValueInput input)
          {
              final Marks marks = new Marks();
              input.getIntArray("pos").ifPresent(packed -> {
                  for (final int local : packed)
                  {
                      marks.set.add(local);
                  }
              });
              return marks;
          }

          @Override
          public boolean write(final Marks marks, final ValueOutput output)
          {
              final int[] packed = marks.toArray();
              if (packed.length == 0)
              {
                  return false;
              }
              output.putIntArray("pos", packed);
              return true;
          }
      })
      .build());

    private PlacedCaps()
    {
    }

    /**
     * The marks of one chunk: positions packed as (y, x within the chunk, z within the chunk).
     */
    public static final class Marks
    {
        private final IntOpenHashSet set = new IntOpenHashSet();

        private synchronized boolean contains(final int local)
        {
            return set.contains(local);
        }

        private synchronized boolean add(final int local)
        {
            return set.add(local);
        }

        private synchronized boolean remove(final int local)
        {
            return set.remove(local);
        }

        private synchronized boolean isEmpty()
        {
            return set.isEmpty();
        }

        private synchronized int[] toArray()
        {
            return set.toIntArray();
        }
    }

    private static int local(@NotNull final BlockPos pos)
    {
        return (pos.getY() << 8) | ((pos.getX() & 15) << 4) | (pos.getZ() & 15);
    }

    /**
     * @param state a block state.
     * @return true for the blocks a placement mark is kept for: huge mushroom blocks and stems, wart blocks, shroomlight.
     */
    public static boolean isMarkable(@NotNull final BlockState state)
    {
        return state.is(ModTags.hugeMushroomBlocks) || state.is(Blocks.MUSHROOM_STEM) || state.is(Blocks.RED_MUSHROOM_BLOCK) || state.is(Blocks.BROWN_MUSHROOM_BLOCK)
                 || state.is(BlockTags.WART_BLOCKS) || state.is(Blocks.SHROOMLIGHT);
    }

    /**
     * Whether a player placed the block there. A mark under a block that is no longer a markable one is stale (broken without an event: an explosion, a
     * command) and is dropped here, on the server thread.
     *
     * @param level the level.
     * @param pos   the block.
     * @return true if it is a player's.
     */
    public static boolean isPlaced(@NotNull final Level level, @NotNull final BlockPos pos)
    {
        return isPlaced(level, pos, level.getBlockState(pos));
    }

    /**
     * @param level the level.
     * @param pos   the block.
     * @param state the block there now.
     * @return true if a player placed it.
     */
    public static boolean isPlaced(@NotNull final Level level, @NotNull final BlockPos pos, @NotNull final BlockState state)
    {
        if (!(level instanceof ServerLevel server) || !level.isLoaded(pos))
        {
            return false;
        }
        final LevelChunk chunk = level.getChunkAt(pos);
        final Marks marks = chunk.getExistingDataOrNull(PLACED_CAPS.get());
        if (marks == null || !marks.contains(local(pos)))
        {
            return false;
        }
        if (isMarkable(state))
        {
            return true;
        }
        if (server.getServer().isSameThread())
        {
            clear(chunk, marks, pos);
        }
        return false;
    }

    /**
     * Mark a block as a player's (placement; tests).
     *
     * @param level the level.
     * @param pos   the block.
     */
    public static void mark(@NotNull final Level level, @NotNull final BlockPos pos)
    {
        if (!(level instanceof ServerLevel) || !level.isLoaded(pos))
        {
            return;
        }
        final LevelChunk chunk = level.getChunkAt(pos);
        if (chunk.getData(PLACED_CAPS.get()).add(local(pos)))
        {
            chunk.markUnsaved();
        }
    }

    /**
     * Clear a block's mark (broken, replaced).
     *
     * @param level the level.
     * @param pos   the block.
     */
    public static void unmark(@NotNull final Level level, @NotNull final BlockPos pos)
    {
        if (!(level instanceof ServerLevel) || !level.isLoaded(pos))
        {
            return;
        }
        final LevelChunk chunk = level.getChunkAt(pos);
        final Marks marks = chunk.getExistingDataOrNull(PLACED_CAPS.get());
        if (marks != null)
        {
            clear(chunk, marks, pos);
        }
    }

    private static void clear(@NotNull final LevelChunk chunk, @NotNull final Marks marks, @NotNull final BlockPos pos)
    {
        if (marks.remove(local(pos)))
        {
            if (marks.isEmpty())
            {
                chunk.removeData(PLACED_CAPS.get());
            }
            chunk.markUnsaved();
        }
    }

    /**
     * Whether any marked block lies in a box (reads the marks of the chunks it touches; no block is scanned).
     *
     * @param level the level.
     * @param min   the box's low corner.
     * @param max   the box's high corner.
     * @return true if a player's cap block may be in it.
     */
    public static boolean anyIn(@NotNull final Level level, @NotNull final BlockPos min, @NotNull final BlockPos max)
    {
        if (!(level instanceof ServerLevel))
        {
            return false;
        }
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++)
        {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++)
            {
                if (!level.hasChunk(cx, cz))
                {
                    continue;
                }
                final Marks marks = level.getChunk(cx, cz).getExistingDataOrNull(PLACED_CAPS.get());
                if (marks == null)
                {
                    continue;
                }
                for (final int local : marks.toArray())
                {
                    final int x = (cx << 4) | ((local >> 4) & 15);
                    final int z = (cz << 4) | (local & 15);
                    final int y = local >> 8;
                    if (x >= min.getX() && x <= max.getX() && y >= min.getY() && y <= max.getY() && z >= min.getZ() && z <= max.getZ())
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * @param level the level.
     * @param pos   a block.
     * @return true if its chunk or one next to it holds any mark (cheap test before a piston's structure is resolved).
     */
    private static boolean anyMarksNear(@NotNull final Level level, @NotNull final BlockPos pos)
    {
        final int cx = pos.getX() >> 4;
        final int cz = pos.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dz = -1; dz <= 1; dz++)
            {
                if (level.hasChunk(cx + dx, cz + dz) && level.getChunk(cx + dx, cz + dz).hasData(PLACED_CAPS.get()))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A player placed a block: a markable one is marked, anything else clears the mark of what it replaced. Runs last, only for a placement that stands.
     *
     * @param event the placement.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlace(@NotNull final BlockEvent.EntityPlaceEvent event)
    {
        if (event.isCanceled() || !(event.getLevel() instanceof final ServerLevel level))
        {
            return;
        }
        final List<BlockPos> placed = new ArrayList<>();
        if (event instanceof final BlockEvent.EntityMultiPlaceEvent multi)
        {
            for (final BlockSnapshot snapshot : multi.getReplacedBlockSnapshots())
            {
                placed.add(snapshot.getPos());
            }
        }
        else
        {
            placed.add(event.getPos());
        }
        final boolean byPlayer = event.getEntity() instanceof Player;
        for (final BlockPos pos : placed)
        {
            if (byPlayer && isMarkable(level.getBlockState(pos)))
            {
                mark(level, pos);
            }
            else
            {
                unmark(level, pos);
            }
        }
    }

    /**
     * A player broke a block: its mark goes.
     *
     * @param event the break.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(@NotNull final BreakBlockEvent event)
    {
        if (!event.isCanceled() && event.getLevel() instanceof final ServerLevel level)
        {
            unmark(level, event.getPos());
        }
    }

    /**
     * A piston moves marked blocks: the block's new place is marked, and the old place keeps its mark until something else stands there (a stale mark is
     * dropped at the next lookup). Blocks the piston destroys lose theirs.
     *
     * @param event the piston, before it moves anything.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPiston(@NotNull final PistonEvent.Pre event)
    {
        if (event.isCanceled() || !(event.getLevel() instanceof final ServerLevel level) || !anyMarksNear(level, event.getPos()))
        {
            return;
        }
        final PistonStructureResolver structure = event.getStructureHelper();
        if (structure == null || !structure.resolve())
        {
            return;
        }
        final Direction push = structure.getPushDirection();
        final List<BlockPos> moved = new ArrayList<>();
        for (final BlockPos pos : structure.getToPush())
        {
            if (isPlaced(level, pos))
            {
                moved.add(pos.relative(push));
            }
        }
        for (final BlockPos pos : structure.getToDestroy())
        {
            unmark(level, pos);
        }
        moved.forEach(pos -> mark(level, pos));
    }
}
