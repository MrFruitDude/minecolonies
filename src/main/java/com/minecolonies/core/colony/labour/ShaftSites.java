package com.minecolonies.core.colony.labour;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.labour.LabourExclusions;
import com.minecolonies.api.colony.labour.LabourShaftEvents;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingMiner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Where and how a colony's labourers dig its stone shafts ({@link LabourShafts}, {@link ShaftPlan}). Server thread only; runtime state only (a restart drops who works
 * where; the records and their cursors are saved).
 * <ul>
 *   <li><b>Survey</b> ({@link #survey}): before a shaft opens, the whole well to its depth plus a one-block shell is read. Every block must be natural ground
 *   ({@code labour/shaft_natural}), or the open sky over the opening; no fluid, block entity, placed block, building box or veto. Underground a player's build shows
 *   (planks, glass, chests, an air room), so the survey can say natural where the surface cannot. A failure in the first lap refuses the spot; a deeper one makes the
 *   well shorter.</li>
 *   <li><b>Fresh look</b> ({@link #check}): every block is looked at again right before it breaks, with its six neighbours: each is natural ground, air of this shaft's
 *   plan, or the open sky; no fluid, no falling block above. When that fails the block stays and the rest of its tunnel (or the well below) is left: a shaft never opens
 *   into a cave, water, lava, another claim or somebody's basement.</li>
 *   <li><b>Sharing</b>: any worker of the colony takes the next undone block of a free segment (the well, or one gallery), nearest shaft first; one worker per segment
 *   and at most {@link LabourSettings#shaftWorkers} per shaft.</li>
 *   <li><b>Search</b> ({@link #tick}): when cobblestone is wanted, nobody makes it and no shaft has work, a spot is looked for round the warehouse nearest the town hall
 *   (or the town hall), a few columns and at most two surveys per second, only in loaded, ticking chunks of this colony's claim. Never a chunk is loaded.</li>
 * </ul>
 */
public final class ShaftSites
{
    /** Ticks between two searches for a spot. */
    public static final long SEARCH_EVERY = 1200L;
    /** How long a spot that failed stays refused. */
    public static final long REJECT_TICKS = 24000L;
    /** The nearest a shaft's opening may lie to the search centre. */
    public static final int  MIN_RING = 12;
    /** The search grid. */
    public static final int  GRID = 4;
    /** Columns looked at per tick of the board, and surveys. */
    public static final int  COLUMNS_PER_TICK = 48;
    public static final int  SURVEYS_PER_TICK = 2;
    /** Most surveys of one search. */
    public static final int  MAX_SURVEYS = 32;
    /** Least distance of a shaft's well to a building's box. */
    public static final int  BUILDING_MARGIN = 4;
    /** How far round the opening no fluid may be. */
    public static final int  FLUID_MARGIN = 3;
    /** How near a hostile may come before a worker leaves the shaft. */
    public static final int  HOSTILE_RANGE = 6;
    /** How long a shaft that wanted torches nobody had is left alone. */
    public static final long TORCH_WAIT = 1200L;
    /** Plan blocks one look reads ahead of a segment's cursor. */
    private static final int  LOOK_AHEAD = 64;

    /** What the last search found (for the panel's help). */
    public enum Search
    {
        /** No search ran yet, or no shaft is wanted. */
        IDLE,
        /** A search runs. */
        SEARCHING,
        /** The last search found no spot. */
        NO_SPOT,
        /** The last search opened a shaft. */
        OPENED
    }

    /** A worker's place in a shaft. */
    public record Assignment(LabourShafts.Shaft shaft, int segment)
    {
    }

    /** The answer of the fresh look. */
    public enum Safety
    {
        OK,
        /** Not now (a chunk next to it is not loaded or does not tick): tried again later. */
        WAIT,
        /** Never: the block stays and the rest of its segment is left. */
        END
    }

    /** What a worker does next in his segment. */
    public sealed interface Step permits Dig, Light, Done, Ended, Wait
    {
    }

    /** Break the block at plan index {@code index}. */
    public record Dig(BlockPos pos, int index) implements Step
    {
    }

    /** Put a torch on its spot first. */
    public record Light(ShaftPlan.Torch torch) implements Step
    {
    }

    /** The segment is dug out. */
    public record Done() implements Step
    {
    }

    /** The segment was left at {@code pos}. */
    public record Ended(BlockPos pos) implements Step
    {
    }

    /** Nothing to do there now. */
    public record Wait() implements Step
    {
    }

    /** The answer of a mark: the shaft, or why there is none. */
    public record Marked(@Nullable LabourShafts.Shaft shaft, @Nullable LabourShafts.Refusal refusal)
    {
    }

    /** The survey of one spot: how many laps the well may have, or why none. */
    public record Survey(int laps, @Nullable LabourShafts.Refusal refusal)
    {
    }

    private final Colony      colony;
    private final LabourBoard board;

    /** Who digs which segment: {@code shaftId * 64 + segment} to citizen id. */
    private final Map<Long, Integer> taken = new HashMap<>();
    /** Pillar columns that failed, until when. */
    private final Map<Long, Long>    rejected = new HashMap<>();
    /** Shafts whose next step wanted a torch nobody had, until when they are left alone. */
    private final Map<Integer, Long> torchless = new HashMap<>();

    private List<long[]> queue = List.of();
    private int          queued;
    private int          surveys;
    private long         searchedAt = Long.MIN_VALUE / 2;
    private Search       search = Search.IDLE;

    public ShaftSites(final Colony colony, final LabourBoard board)
    {
        this.colony = colony;
        this.board = board;
    }

    // ------------------------------------------------------------------ access

    public LabourShafts record()
    {
        return colony.getLabourData().shafts();
    }

    @Nullable
    private ServerLevel level()
    {
        return colony.getWorld() instanceof ServerLevel server ? server : null;
    }

    private long now()
    {
        return colony.getWorld() == null ? 0L : colony.getWorld().getGameTime();
    }

    /**
     * @return what the last search found.
     */
    public Search search()
    {
        return search;
    }

    /**
     * @return whether the shaft waits for torches nobody had (shown to the managers).
     */
    public boolean wantsTorches(final int shaftId)
    {
        final Long until = torchless.get(shaftId);
        return until != null && now() < until;
    }

    /**
     * @return the citizens who dig in the shaft now.
     */
    public List<Integer> workersOn(final int shaftId)
    {
        final List<Integer> out = new ArrayList<>();
        for (final Map.Entry<Long, Integer> entry : taken.entrySet())
        {
            if (entry.getKey() / 64 == shaftId && !out.contains(entry.getValue()))
            {
                out.add(entry.getValue());
            }
        }
        return out;
    }

    /**
     * @return the shaft and segment the citizen digs now, or null.
     */
    @Nullable
    public Assignment assignmentOf(final int citizenId)
    {
        for (final Map.Entry<Long, Integer> entry : taken.entrySet())
        {
            if (entry.getValue() == citizenId)
            {
                final LabourShafts.Shaft shaft = record().byId((int) (entry.getKey() / 64));
                if (shaft != null)
                {
                    return new Assignment(shaft, (int) (entry.getKey() % 64));
                }
            }
        }
        return null;
    }

    private static long key(final int shaftId, final int segment)
    {
        return (long) shaftId * 64 + segment;
    }

    // ------------------------------------------------------------------ work

    /**
     * @param citizenId the worker asking (the segment he holds counts as free for him), or -1.
     * @return whether a shaft of the colony has a free segment with work a worker may take now (shafts are on in the world; a colony whose toggle is off still digs its
     *   shafts). What the planner asks for {@link LabourPlanner.GatherKind#SHAFT}.
     */
    public boolean hasWork(final int citizenId)
    {
        if (!LabourSettings.shaftsEnabled())
        {
            return false;
        }
        for (final LabourShafts.Shaft shaft : record().shafts())
        {
            if (freeSegment(shaft, citizenId) >= 0)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether an open shaft of the colony still has blocks to dig (taken or not): no new shaft is looked for then.
     */
    public boolean anyOpenWork()
    {
        for (final LabourShafts.Shaft shaft : record().shafts())
        {
            if (shaft.state() != LabourShafts.State.OPEN)
            {
                continue;
            }
            for (int s = 0; s < shaft.plan().segments(); s++)
            {
                if (!shaft.segmentDone(s) && reachable(shaft, s))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @param citizenId the worker asking, or -1.
     * @return the first segment of the shaft he may take now, or -1: the shaft is open, has room for another worker, the segment is not done, its gallery's landing is
     *   dug, nobody else digs it, and a look ahead finds a block to dig in it.
     */
    private int freeSegment(final LabourShafts.Shaft shaft, final int citizenId)
    {
        if (shaft.state() != LabourShafts.State.OPEN || wantsTorches(shaft.id))
        {
            return -1;
        }
        final List<Integer> workers = workersOn(shaft.id);
        if (!workers.contains(citizenId) && workers.size() >= LabourSettings.shaftWorkers())
        {
            return -1;
        }
        final ShaftPlan plan = shaft.plan();
        for (int s = 0; s < plan.segments(); s++)
        {
            final Integer holder = taken.get(key(shaft.id, s));
            if (shaft.segmentDone(s) || (holder != null && holder != citizenId) || !reachable(shaft, s))
            {
                continue;
            }
            if (advance(shaft, s))
            {
                return s;
            }
        }
        return -1;
    }

    /**
     * @return whether the segment may be dug: the well always, a gallery once the well is dug down to its landing.
     */
    private static boolean reachable(final LabourShafts.Shaft shaft, final int segment)
    {
        return segment == 0 || shaft.segmentDone(0) || shaft.cursors[0] >= shaft.plan().wellNeededFor(segment);
    }

    /**
     * Moves the segment's cursor past the blocks that are dug already (air or a torch), reading at most {@link #LOOK_AHEAD} blocks, never in a chunk that is not loaded.
     *
     * @return whether a block to dig is left in it (false also when the segment is done now).
     */
    private boolean advance(final LabourShafts.Shaft shaft, final int segment)
    {
        final ServerLevel level = level();
        if (level == null)
        {
            return false;
        }
        final ShaftPlan plan = shaft.plan();
        int i = Math.max(plan.start(segment), shaft.cursors[segment]);
        final int end = plan.end(segment);
        int read = 0;
        while (i < end)
        {
            final BlockPos pos = plan.cell(i);
            if (!LabourSites.loadedNow(level, pos))
            {
                shaft.cursors[segment] = i;
                return false;
            }
            if (!dug(level.getBlockState(pos)))
            {
                shaft.cursors[segment] = i;
                return true;
            }
            i++;
            if (++read >= LOOK_AHEAD)
            {
                shaft.cursors[segment] = i;
                return true;
            }
        }
        shaft.cursors[segment] = end;
        finish(shaft, segment, false);
        return false;
    }

    /**
     * @return whether a plan block counts as dug: air, or the torch labour put there.
     */
    static boolean dug(final BlockState state)
    {
        return state.isAir() || torch(state);
    }

    private static boolean torch(final BlockState state)
    {
        return state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH);
    }

    /**
     * Gives the worker a segment, nearest shaft first; the one he holds if he holds one with work.
     *
     * @return his place, or null when no shaft of the colony has free work for him.
     */
    @Nullable
    public Assignment assign(final int citizenId, final BlockPos from)
    {
        final Assignment held = assignmentOf(citizenId);
        if (held != null)
        {
            if (!held.shaft().segmentDone(held.segment()) && held.shaft().state() == LabourShafts.State.OPEN)
            {
                return held;
            }
            release(citizenId);
        }
        if (!LabourSettings.shaftsEnabled())
        {
            return null;
        }
        final List<LabourShafts.Shaft> shafts = new ArrayList<>(record().shafts());
        shafts.sort(Comparator.comparingDouble((LabourShafts.Shaft s) -> s.entrance.distSqr(from)).thenComparingInt(s -> s.id));
        for (final LabourShafts.Shaft shaft : shafts)
        {
            final int segment = freeSegment(shaft, citizenId);
            if (segment >= 0)
            {
                taken.put(key(shaft.id, segment), citizenId);
                return new Assignment(shaft, segment);
            }
        }
        return null;
    }

    /**
     * The worker leaves the shaft (his claim or step ended).
     */
    public void release(final int citizenId)
    {
        taken.values().removeIf(id -> id == citizenId);
    }

    /**
     * Nobody has a torch for the shaft: it is left alone for {@link #TORCH_WAIT} (the panel says it needs torches).
     */
    public void noTorches(final LabourShafts.Shaft shaft)
    {
        torchless.put(shaft.id, now() + TORCH_WAIT);
        record().bump();
    }

    /**
     * @return the worker's next step in his segment.
     */
    public Step next(final Assignment at)
    {
        final LabourShafts.Shaft shaft = at.shaft();
        final int segment = at.segment();
        final ServerLevel level = level();
        if (level == null || shaft.state() != LabourShafts.State.OPEN)
        {
            return new Wait();
        }
        if (shaft.segmentDone(segment))
        {
            return new Done();
        }
        if (!advance(shaft, segment))
        {
            return shaft.segmentDone(segment) ? new Done() : new Wait();
        }
        final ShaftPlan plan = shaft.plan();
        final int index = shaft.cursors[segment];
        final ShaftPlan.Torch torch = torchNeeded(level, plan, segment, index);
        if (torch != null)
        {
            return new Light(torch);
        }
        final BlockPos pos = plan.cell(index);
        return switch (check(shaft, pos))
        {
            case OK -> new Dig(pos, index);
            case WAIT -> new Wait();
            case END ->
            {
                end(shaft, segment, pos);
                yield new Ended(pos);
            }
        };
    }

    /**
     * @return the first torch spot the block at {@code index} needs lit that is not lit and where a torch can go now (air), or null.
     */
    @Nullable
    private static ShaftPlan.Torch torchNeeded(final ServerLevel level, final ShaftPlan plan, final int segment, final int index)
    {
        final int well = segment == 0 ? index : plan.wellNeededFor(segment);
        for (final ShaftPlan.Torch torch : plan.torches())
        {
            final boolean needed = torch.segment() == 0 ? torch.neededFrom() <= well : torch.segment() == segment && torch.neededFrom() <= index;
            if (!needed || !LabourSites.loadedNow(level, torch.pos()))
            {
                continue;
            }
            final BlockState state = level.getBlockState(torch.pos());
            if (state.isAir())
            {
                return torch;
            }
        }
        return null;
    }

    /**
     * A block of the segment was dug.
     */
    public void dugOne(final LabourShafts.Shaft shaft, final int segment, final int index)
    {
        if (shaft.cursors[segment] <= index)
        {
            shaft.cursors[segment] = index + 1;
        }
        record().mined(shaft);
        colony.markDirty();
    }

    /**
     * The segment is left at {@code pos} (the fresh look refused it for good): the well below it and the galleries of the landings it never reaches are left too.
     */
    public void end(final LabourShafts.Shaft shaft, final int segment, final BlockPos pos)
    {
        LabourStats.log("shaft {} of colony {}: segment {} left at {}", shaft.id, colony.getID(), segment, pos.toShortString());
        if (segment == 0)
        {
            final ShaftPlan plan = shaft.plan();
            for (int s = 1; s < plan.segments(); s++)
            {
                if (plan.wellNeededFor(s) > shaft.cursors[0])
                {
                    finish(shaft, s, true);
                }
            }
        }
        finish(shaft, segment, true);
    }

    /**
     * An ore the worker's pickaxe cannot take stops the segment there; the ore is remembered and told once per kind.
     */
    public void oreFace(final LabourShafts.Shaft shaft, final int segment, final BlockPos pos, final BlockState ore)
    {
        final String item = BuiltInRegistries.ITEM.getKey(ore.getBlock().asItem()).toString();
        record().oreFace(shaft, pos, item);
        found(shaft, pos, item, false);
        end(shaft, segment, pos);
    }

    /**
     * An ore was met (mined, or left as a face): told once per kind and shaft.
     */
    public void found(final LabourShafts.Shaft shaft, final BlockPos pos, final String item, final boolean mined)
    {
        if (record().foundOre(shaft, item))
        {
            colony.markDirty();
            LabourShaftEvents.fire(new LabourShaftEvents.Event(colony, LabourShaftEvents.Kind.ORE_FOUND, shaft.id, shaft.entrance, pos.immutable(), item, mined, shaft.depth(),
              shaft.marked));
        }
    }

    private void finish(final LabourShafts.Shaft shaft, final int segment, final boolean endedEarly)
    {
        final boolean full = record().segmentDone(shaft, segment, endedEarly);
        colony.markDirty();
        taken.remove(key(shaft.id, segment));
        if (full)
        {
            LabourStats.log("shaft {} of colony {} is worked out ({} blocks)", shaft.id, colony.getID(), shaft.mined());
            LabourShaftEvents.fire(new LabourShaftEvents.Event(colony, LabourShaftEvents.Kind.WORKED_OUT, shaft.id, shaft.entrance, shaft.entrance, "", false, shaft.depth(),
              shaft.marked));
        }
    }

    /**
     * @return whether a hostile mob is within {@link #HOSTILE_RANGE} of the block: the worker leaves.
     */
    public boolean hostileNear(final BlockPos pos)
    {
        final ServerLevel level = level();
        return level != null && !level.getEntitiesOfClass(LivingEntity.class, new AABB(pos).inflate(HOSTILE_RANGE), e -> e instanceof Enemy && e.isAlive()).isEmpty();
    }

    // ------------------------------------------------------------------ the fresh look

    /**
     * The fresh look before a block of the shaft breaks ({@link Safety}): {@link LabourSites#safeNow}, the block is natural ground with no block entity, and each of its
     * six neighbours is natural ground (no block entity, not placed), air of this plan, a torch of this plan, or the open sky; no neighbour holds a fluid, no falling
     * block lies on it.
     */
    public Safety check(final LabourShafts.Shaft shaft, final BlockPos pos)
    {
        final ServerLevel level = level();
        if (level == null || !LabourSites.simulated(level, pos) || !LabourSites.sidesLoaded(level, pos))
        {
            return Safety.WAIT;
        }
        final LabourSites sites = board.sites();
        if (!sites.safeNow(null, pos))
        {
            // another colony's claim, outside any claim, a building's box, a veto or a placed block: never
            return Safety.END;
        }
        final ShaftPlan plan = shaft.plan();
        final BlockState state = level.getBlockState(pos);
        if (!LabourRules.shaftNatural(state) || level.getBlockEntity(pos) != null || !level.getFluidState(pos).isEmpty())
        {
            return Safety.END;
        }
        for (final Direction direction : Direction.values())
        {
            final BlockPos next = pos.relative(direction);
            if (!level.getFluidState(next).isEmpty())
            {
                return Safety.END;
            }
            final BlockState near = level.getBlockState(next);
            if (direction == Direction.UP && near.getBlock() instanceof FallingBlock)
            {
                return Safety.END;
            }
            if (near.isAir())
            {
                if (!plan.contains(next) && !sky(level, next))
                {
                    return Safety.END;
                }
                continue;
            }
            if (torch(near) && plan.contains(next))
            {
                continue;
            }
            if (!LabourRules.shaftNatural(near) || level.getBlockEntity(next) != null || sites.placed(next))
            {
                return Safety.END;
            }
        }
        return Safety.OK;
    }

    /**
     * @return whether the air block lies under the open sky (at or above its column's top): no cave.
     */
    private static boolean sky(final ServerLevel level, final BlockPos pos)
    {
        return pos.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
    }

    // ------------------------------------------------------------------ the survey

    /**
     * The laps rule (pure): the most laps a well whose top is {@code top} may have when the block at {@code failY} failed the survey (or {@link Integer#MIN_VALUE} for
     * none), it may not go below {@code minY}, and the config allows {@code max}: the well's lowest floor and the shell under it stay above both.
     */
    public static int lapsFor(final int top, final int failY, final int minY, final int max)
    {
        int laps = Math.max(0, max);
        if (failY != Integer.MIN_VALUE)
        {
            laps = Math.min(laps, Math.max(0, Math.floorDiv(top - 2 - failY, ShaftPlan.LAP_DEPTH)));
        }
        return Math.min(laps, Math.max(0, Math.floorDiv(top - 1 - minY, ShaftPlan.LAP_DEPTH)));
    }

    /**
     * Reads the well of a shaft whose pillar stands at {@code entrance} (its top block) down to {@code maxLaps} laps plus the shell ({@link ShaftSites} class comment).
     */
    public Survey survey(final BlockPos entrance, final int maxLaps)
    {
        final ServerLevel level = level();
        if (level == null)
        {
            return new Survey(0, LabourShafts.Refusal.OUTSIDE);
        }
        final LabourSites sites = board.sites();
        final int top = entrance.getY();
        final int lowest = Math.max(level.getMinY(), top - ShaftPlan.LAP_DEPTH * Math.max(1, maxLaps) - 1);
        int failY = Integer.MIN_VALUE;
        LabourShafts.Refusal why = null;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        outer:
        for (int y = top; y >= lowest; y--)
        {
            for (int dx = -2; dx <= 2; dx++)
            {
                for (int dz = -2; dz <= 2; dz++)
                {
                    pos.set(entrance.getX() + dx, y, entrance.getZ() + dz);
                    if (!LabourSites.loadedNow(level, pos))
                    {
                        return new Survey(0, LabourShafts.Refusal.OUTSIDE);
                    }
                    if (!level.getFluidState(pos).isEmpty())
                    {
                        failY = y;
                        why = LabourShafts.Refusal.NEAR_WATER;
                        break outer;
                    }
                    final BlockState state = level.getBlockState(pos);
                    if (state.isAir())
                    {
                        if (sky(level, pos))
                        {
                            continue;
                        }
                        failY = y;
                        why = LabourShafts.Refusal.NOT_NATURAL;
                        break outer;
                    }
                    if (!LabourRules.shaftNatural(state) || level.getBlockEntity(pos) != null || sites.placed(pos) || LabourExclusions.excluded(colony, pos))
                    {
                        failY = y;
                        why = LabourShafts.Refusal.NOT_NATURAL;
                        break outer;
                    }
                }
            }
        }
        final int laps = lapsFor(top, failY, LabourSettings.shaftMinY(), maxLaps);
        if (laps <= 0)
        {
            return new Survey(0, why == null ? LabourShafts.Refusal.NOT_NATURAL : why);
        }
        return new Survey(laps, null);
    }

    // ------------------------------------------------------------------ choosing a spot

    /**
     * The quick look at a spot before its survey: the colony has room, the opening's 5x5 lies in loaded, ticking chunks of this colony, away from other shafts, miners,
     * buildings and fluid, its tops are level within a block and natural, and nothing grows over it.
     *
     * @param pillar the pillar's column (any y).
     * @return the pillar at the top block's y, or why not ({@link Marked#refusal}).
     */
    private Object spot(final ServerLevel level, final BlockPos pillar)
    {
        final LabourSites sites = board.sites();
        final int x = pillar.getX();
        final int z = pillar.getZ();
        for (final int[] corner : new int[][] {{-FLUID_MARGIN - 2, -FLUID_MARGIN - 2}, {FLUID_MARGIN + 2, FLUID_MARGIN + 2}, {-FLUID_MARGIN - 2, FLUID_MARGIN + 2},
          {FLUID_MARGIN + 2, -FLUID_MARGIN - 2}})
        {
            final BlockPos at = new BlockPos(x + corner[0], pillar.getY(), z + corner[1]);
            if (!LabourSites.loadedNow(level, at))
            {
                return LabourShafts.Refusal.OUTSIDE;
            }
        }
        for (int dx = -2; dx <= 2; dx += 2)
        {
            for (int dz = -2; dz <= 2; dz += 2)
            {
                if (!sites.workable(new BlockPos(x + dx, pillar.getY(), z + dz)))
                {
                    return LabourShafts.Refusal.OUTSIDE;
                }
            }
        }
        final int spacing = LabourSettings.shaftSpacing();
        for (final LabourShafts.Shaft other : record().shafts())
        {
            final long dx = other.entrance.getX() - x;
            final long dz = other.entrance.getZ() - z;
            if (dx * dx + dz * dz < (long) spacing * spacing)
            {
                return LabourShafts.Refusal.NEAR_SHAFT;
            }
        }
        final int clearance = LabourSettings.shaftMinerClearance();
        if (clearance > 0)
        {
            for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
            {
                if (building instanceof BuildingMiner miner)
                {
                    final BlockPos ladder = ladderOf(miner);
                    final long dx = ladder.getX() - x;
                    final long dz = ladder.getZ() - z;
                    if (dx * dx + dz * dz < (long) clearance * clearance)
                    {
                        return LabourShafts.Refusal.NEAR_MINER;
                    }
                }
            }
        }
        if (sites.nearProtected(x - 2, z - 2, x + 2, z + 2, BUILDING_MARGIN))
        {
            return LabourShafts.Refusal.NEAR_BUILDING;
        }
        final int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        for (int dx = -2; dx <= 2; dx++)
        {
            for (int dz = -2; dz <= 2; dz++)
            {
                final int cx = x + dx;
                final int cz = z + dz;
                final int columnTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz) - 1;
                if (Math.abs(columnTop - top) > 1)
                {
                    return LabourShafts.Refusal.UNEVEN;
                }
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz) != columnTop + 1)
                {
                    // leaves over it: a tree stands there
                    return LabourShafts.Refusal.NOT_NATURAL;
                }
                final BlockPos ground = new BlockPos(cx, columnTop, cz);
                if (LabourExclusions.excluded(colony, ground))
                {
                    return LabourShafts.Refusal.NEAR_BUILDING;
                }
                if (!LabourRules.shaftNatural(level.getBlockState(ground)) || sites.placed(ground))
                {
                    return LabourShafts.Refusal.NOT_NATURAL;
                }
            }
        }
        for (int dx = -2 - FLUID_MARGIN; dx <= 2 + FLUID_MARGIN; dx++)
        {
            for (int dz = -2 - FLUID_MARGIN; dz <= 2 + FLUID_MARGIN; dz++)
            {
                for (int dy = -1; dy <= 1; dy++)
                {
                    if (!level.getFluidState(new BlockPos(x + dx, top + dy, z + dz)).isEmpty())
                    {
                        return LabourShafts.Refusal.NEAR_WATER;
                    }
                }
            }
        }
        return new BlockPos(x, top, z);
    }

    private static BlockPos ladderOf(final BuildingMiner miner)
    {
        try
        {
            final BlockPos ladder = miner.getLadderLocation();
            return ladder == null ? miner.getPosition() : ladder;
        }
        catch (final RuntimeException e)
        {
            // (its hut is not loaded: the hut stands for the ladder)
            return miner.getPosition();
        }
    }

    /**
     * @return the rotation of a shaft at the column (deterministic, so shafts next to each other do not all turn alike).
     */
    static int rotationAt(final int x, final int z)
    {
        return Math.floorMod((x >> 2) + (z >> 2), 4);
    }

    /**
     * Surveys the spot and opens a shaft there when it passes.
     *
     * @param marked   whether a manager marked it (no cooldown, the colony toggle does not matter).
     * @param openedBy the citizen or player who opened it (0 for none).
     */
    private Marked tryOpen(final ServerLevel level, final BlockPos pillar, final boolean marked, final int openedBy)
    {
        if (!LabourSettings.shaftsEnabled())
        {
            return new Marked(null, LabourShafts.Refusal.OFF);
        }
        if (record().counted() >= LabourSettings.shaftsMax())
        {
            return new Marked(null, LabourShafts.Refusal.TOO_MANY);
        }
        final Object spot = spot(level, pillar);
        if (spot instanceof LabourShafts.Refusal refusal)
        {
            return new Marked(null, refusal);
        }
        final BlockPos entrance = (BlockPos) spot;
        final Survey survey = survey(entrance, LabourSettings.shaftLaps());
        if (survey.refusal() != null)
        {
            return new Marked(null, survey.refusal());
        }
        final int rotation = rotationAt(entrance.getX(), entrance.getZ());
        final int length = LabourSettings.shaftGalleryLength();
        final int preEnded = galleriesTowardOthers(level, new ShaftPlan(entrance, rotation, survey.laps(), length));
        final LabourShafts.Shaft shaft = record().open(entrance, rotation, survey.laps(), length, marked, openedBy, now(), LabourSettings.shaftsMax(), preEnded);
        if (shaft == null)
        {
            return new Marked(null, LabourShafts.Refusal.TOO_MANY);
        }
        colony.markDirty();
        LabourStats.log("shaft {} of colony {} opened at {} ({} laps, {}){}", shaft.id, colony.getID(), entrance.toShortString(), survey.laps(), marked ? "marked" : "auto",
          preEnded != 0 ? " some galleries face another claim" : "");
        LabourShaftEvents.fire(new LabourShaftEvents.Event(colony, LabourShaftEvents.Kind.OPENED, shaft.id, entrance, entrance, "", false, 0, marked));
        return new Marked(shaft, null);
    }

    /**
     * @return the gallery segments (as bits) that would run into another colony's claim (read in loaded chunks only): they are left from the start.
     */
    private int galleriesTowardOthers(final ServerLevel level, final ShaftPlan plan)
    {
        int bits = 0;
        for (int s = 1; s < plan.segments(); s++)
        {
            for (int i = plan.start(s); i < plan.end(s); i += 16)
            {
                final BlockPos pos = plan.cell(Math.min(plan.end(s) - 1, i + 15));
                if (!LabourSites.loadedNow(level, pos))
                {
                    continue;
                }
                final IColony owner = IColonyManager.getInstance().getColonyByPosFromWorld(level, pos);
                if (owner != null && (owner.getID() != colony.getID() || !owner.getDimension().equals(colony.getDimension())))
                {
                    bits |= 1 << s;
                    break;
                }
            }
        }
        return bits;
    }

    /**
     * A manager marks a spot: the same checks and survey as labour's own search, without the cooldown or the colony toggle.
     */
    public Marked mark(final BlockPos column, final int openedBy)
    {
        final ServerLevel level = level();
        if (level == null)
        {
            return new Marked(null, LabourShafts.Refusal.OUTSIDE);
        }
        if (!LabourSites.loadedNow(level, column))
        {
            // (the height is read only where the chunk is loaded: a look never loads one)
            return new Marked(null, LabourShafts.Refusal.OUTSIDE);
        }
        final BlockPos at = new BlockPos(column.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()) - 1, column.getZ());
        final Marked marked = tryOpen(level, at, true, openedBy);
        if (marked.shaft() != null)
        {
            search = Search.OPENED;
            board.invalidate();
        }
        return marked;
    }

    /**
     * A manager closes a shaft (nobody digs there any more).
     */
    @Nullable
    public LabourShafts.Refusal close(final int id)
    {
        final LabourShafts.Refusal refusal = record().close(id);
        if (refusal == null)
        {
            taken.keySet().removeIf(k -> k / 64 == id);
            colony.markDirty();
        }
        return refusal;
    }

    /**
     * A manager opens a closed shaft again.
     */
    @Nullable
    public LabourShafts.Refusal reopen(final int id)
    {
        if (!LabourSettings.shaftsEnabled())
        {
            return LabourShafts.Refusal.OFF;
        }
        final LabourShafts.Refusal refusal = record().reopen(id, LabourSettings.shaftsMax());
        if (refusal == null)
        {
            torchless.remove(id);
            colony.markDirty();
        }
        return refusal;
    }

    // ------------------------------------------------------------------ the search

    /**
     * @return where the search is centred: the warehouse nearest the town hall, else the town hall.
     */
    BlockPos searchCentre()
    {
        if (testCentre != null)
        {
            return testCentre;
        }
        final BlockPos hall = colony.getCenter();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final IWareHouse wareHouse : board.sites().warehouses())
        {
            final double distance = wareHouse.getID().distSqr(hall);
            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = wareHouse.getID();
            }
        }
        return best == null ? hall : best;
    }

    /**
     * The search's columns (pure): every grid column in the ring of {@code min} to {@code radius} round the centre, nearest first, then by x and z.
     */
    public static List<long[]> ring(final int cx, final int cz, final int min, final int radius, final int grid)
    {
        final List<long[]> out = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx += grid)
        {
            for (int dz = -radius; dz <= radius; dz += grid)
            {
                final long d = (long) dx * dx + (long) dz * dz;
                if (d >= (long) min * min && d <= (long) radius * radius)
                {
                    out.add(new long[] {cx + dx, cz + dz, d});
                }
            }
        }
        out.sort(Comparator.comparingLong((long[] a) -> a[2]).thenComparingLong(a -> a[0]).thenComparingLong(a -> a[1]));
        return out;
    }

    /**
     * Once a second (the board's tick): starts a search when one is due and runs a slice of it.
     */
    public void tick()
    {
        final long now = now();
        final ServerLevel level = level();
        if (level == null)
        {
            return;
        }
        torchless.values().removeIf(until -> until < now);
        if (rejected.size() > 4096)
        {
            rejected.values().removeIf(until -> until < now);
        }
        if (queue.isEmpty())
        {
            if (now - searchedAt >= SEARCH_EVERY || now < searchedAt)
            {
                if (searchWanted(now))
                {
                    searchedAt = now;
                    final BlockPos centre = searchCentre();
                    queue = ring(centre.getX(), centre.getZ(), MIN_RING, LabourSettings.shaftRadius(), GRID);
                    queued = 0;
                    surveys = 0;
                    search = Search.SEARCHING;
                }
                else if (search == Search.SEARCHING)
                {
                    search = Search.IDLE;
                }
            }
            return;
        }
        if (!searchWanted(now))
        {
            queue = List.of();
            search = Search.IDLE;
            return;
        }
        int looked = 0;
        int surveyed = 0;
        while (queued < queue.size() && looked < COLUMNS_PER_TICK && surveyed < SURVEYS_PER_TICK && surveys < MAX_SURVEYS)
        {
            final long[] column = queue.get(queued++);
            looked++;
            final long columnKey = BlockPos.asLong((int) column[0], 0, (int) column[1]);
            final Long until = rejected.get(columnKey);
            if (until != null && now < until)
            {
                continue;
            }
            final BlockPos pillar = new BlockPos((int) column[0], colony.getCenter().getY(), (int) column[1]);
            final Object spot = spot(level, pillar);
            if (spot instanceof LabourShafts.Refusal refusal)
            {
                if (refusal != LabourShafts.Refusal.OUTSIDE && refusal != LabourShafts.Refusal.TOO_MANY)
                {
                    rejected.put(columnKey, now + REJECT_TICKS);
                }
                continue;
            }
            surveyed++;
            surveys++;
            final Marked opened = tryOpen(level, (BlockPos) spot, false, 0);
            if (opened.shaft() != null)
            {
                queue = List.of();
                search = Search.OPENED;
                board.invalidate();
                return;
            }
            rejected.put(columnKey, now + REJECT_TICKS);
        }
        if (queued >= queue.size() || surveys >= MAX_SURVEYS)
        {
            LabourStats.log("colony {}: no shaft spot found ({} columns, {} surveys)", colony.getID(), queued, surveys);
            queue = List.of();
            search = Search.NO_SPOT;
        }
    }

    /**
     * Whether labour looks for a new spot: shafts are on for the colony, cobblestone is wanted and nobody makes it, a citizen without a job could dig, no open shaft has
     * work left, the colony has room and the last shaft it opened by itself is a day old.
     */
    private boolean searchWanted(final long now)
    {
        if (!LabourSettings.shafts(colony) || !LabourSettings.enabled(colony) || board.coverage().covered(Items.COBBLESTONE) || board.unemployed() <= 0)
        {
            return false;
        }
        final LabourShafts record = record();
        if (record.counted() >= LabourSettings.shaftsMax() || !LabourShafts.cooledDown(record.lastOpened(), now, LabourSettings.shaftCooldownTicks()))
        {
            return false;
        }
        return board.shaftDemand() && !anyOpenWork();
    }

    @Nullable
    private BlockPos testCentre;

    /**
     * Only for tests: the search is centred here (null: the warehouse or the town hall).
     */
    public void centreForTests(@Nullable final BlockPos centre)
    {
        testCentre = centre;
    }

    /**
     * Only for tests: forgets the refused columns and starts the next search at once.
     */
    public void resetSearch()
    {
        rejected.clear();
        queue = List.of();
        searchedAt = Long.MIN_VALUE / 2;
        search = Search.IDLE;
        torchless.clear();
    }

    /**
     * Only for tests: the shafts' taken segments, for the checks.
     */
    public Map<Long, Integer> takenForTests()
    {
        return Map.copyOf(taken);
    }

    /**
     * Forgets what is gone: the segments of citizens that hold no claim.
     */
    public void sweep(final java.util.function.IntPredicate holdsClaim)
    {
        for (final Iterator<Map.Entry<Long, Integer>> it = taken.entrySet().iterator(); it.hasNext(); )
        {
            if (!holdsClaim.test(it.next().getValue()))
            {
                it.remove();
            }
        }
    }
}
