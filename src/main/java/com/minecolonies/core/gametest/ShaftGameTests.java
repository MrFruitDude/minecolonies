package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.labour.LabourShaftEvents;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;
import com.minecolonies.core.colony.idle.IdleCitizenArbiter;
import com.minecolonies.core.colony.idle.IdleCitizenSettings;
import com.minecolonies.core.colony.labour.LabourClaim;
import com.minecolonies.core.colony.labour.LabourData;
import com.minecolonies.core.colony.labour.LabourSession;
import com.minecolonies.core.colony.labour.LabourSettings;
import com.minecolonies.core.colony.labour.LabourShafts;
import com.minecolonies.core.colony.labour.ShaftPlan;
import com.minecolonies.core.colony.labour.ShaftSites;
import com.minecolonies.core.colony.requestsystem.reservation.Reservation;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationReason;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Stone shafts (r4): labourers and stalled builders dig small stair shafts for cobblestone, only in ground the survey proves natural, and share them. Each test builds a
 * stone hill on the plot (the plot's ground is only one block thick) and works in it. Run with {@code MINECOLONIES_GAMETEST_ONLY=shafts}.
 */
final class ShaftGameTests
{
    /** The hill: relative x and z bounds, its top y. */
    private static final int HX0 = 24;
    private static final int HX1 = 58;
    private static final int HZ0 = 12;
    private static final int HZ1 = 46;
    private static final int TOP = 20;

    /** Four spots on the hill, far enough apart that their galleries (4 long in the tests) never meet. */
    private static final BlockPos S1 = new BlockPos(34, 0, 22);
    private static final BlockPos S2 = new BlockPos(48, 0, 22);
    private static final BlockPos S3 = new BlockPos(34, 0, 36);
    private static final BlockPos S4 = new BlockPos(48, 0, 36);

    /** The shaft events of the test that runs (tests run one after the other). */
    private static final List<LabourShaftEvents.Event> EVENTS = new CopyOnWriteArrayList<>();
    private static final java.util.function.Consumer<LabourShaftEvents.Event> LISTENER = EVENTS::add;

    private ShaftGameTests()
    {
    }

    // ------------------------------------------------------------------ scene

    private static void begin()
    {
        com.minecolonies.core.colony.labour.LabourStats.debug = true;
        LabourSettings.clearOverrides();
        LabourSettings.override("early", false);
        LabourSettings.override("workScale", 8.0D);
        LabourSettings.override("keepTrees", 0);
        LabourSettings.override("stockpile", false);
        LabourSettings.override("shaftLaps", 1);
        LabourSettings.override("shaftGallery", 0);
        LabourSettings.override("shaftSpacing", 8);
        LabourSettings.override("shaftMinY", -1_000_000);
        LabourSettings.override("shaftCooldownTicks", 0);
        IdleCitizenSettings.overrideForTests(null, null, null);
        EVENTS.clear();
        LabourShaftEvents.register(LISTENER);
    }

    private static void end()
    {
        com.minecolonies.core.colony.labour.LabourStats.debug = false;
        LabourSettings.clearOverrides();
        LabourShaftEvents.unregister(LISTENER);
    }

    /**
     * The fixture colony (builder hut, warehouse with two racks, an officer nearby), no automatic hiring, and the hill.
     */
    private static RsFixture found(final GameTestHelper helper, final String name)
    {
        final RsFixture f = RsFixture.found(helper, name, 2);
        helper.assertTrue(f.colony.getPermissions().addPlayer(f.player.nameAndId(), f.colony.getPermissions().getRankOfficer()), "the player could not join the colony");
        final var hiring = f.colony.getSettings().getSetting(BuildingTownHall.AUTO_HIRING_MODE);
        if (hiring.getValue())
        {
            hiring.trigger();
        }
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(HX1 + 4, 1, HZ1 + 4)));
        hill(helper);
        return f;
    }

    private static void forceArea(final ServerLevel level, final BlockPos min, final BlockPos max)
    {
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++)
        {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++)
            {
                level.setChunkForced(cx, cz, true);
            }
        }
    }

    /**
     * Natural stone from y 1 to {@link #TOP} over the hill's rectangle, open sky above it (the plot's barrier roof taken away there), no trees, tables or furnaces.
     */
    private static void hill(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        for (int x = HX0; x <= HX1; x++)
        {
            for (int z = HZ0; z <= HZ1; z++)
            {
                for (int y = 1; y <= TOP; y++)
                {
                    level.setBlock(helper.absolutePos(new BlockPos(x, y, z)), Blocks.STONE.defaultBlockState(), 2);
                }
                for (int y = TOP + 1; y <= 48; y++)
                {
                    final BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
                    if (!level.getBlockState(pos).isAir())
                    {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    private static BlockPos abs(final GameTestHelper helper, final BlockPos spot)
    {
        return helper.absolutePos(new BlockPos(spot.getX(), TOP, spot.getZ()));
    }

    /**
     * Places the block the way a player's hand does (the colony records it as placed).
     */
    private static void place(final GameTestHelper helper, final BlockPos rel, final BlockState state, final Entity by)
    {
        helper.setBlock(rel, state);
        final BlockPos abs = helper.absolutePos(rel);
        final ServerLevel level = helper.getLevel();
        NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, abs), level.getBlockState(abs.below()), by));
    }

    private static LabourShafts.Shaft mark(final GameTestHelper helper, final Colony colony, final BlockPos spot)
    {
        final ShaftSites.Marked marked = colony.getLabourBoard().markShaft(abs(helper, spot), 0);
        helper.assertTrue(marked.shaft() != null, "the spot " + spot + " was refused: " + marked.refusal());
        return marked.shaft();
    }

    private static IToken<?> ask(final IBuilding requester, final ItemStack stack)
    {
        return requester.createRequest(new Stack(stack, stack.getCount(), stack.getCount()), false);
    }

    private static int inHut(final IBuilding hut, final Item item)
    {
        return InventoryUtils.getItemCountInItemHandler(hut.getItemHandlerCap(), s -> s.is(item));
    }

    private static ICitizenData labourer(final RsFixture f, final BlockPos rel, final ItemStack... items)
    {
        final ICitizenData citizen = f.spawn(rel, false);
        for (final ItemStack item : items)
        {
            InventoryUtils.addItemStackToItemHandler(citizen.getInventory(), item.copy());
        }
        return citizen;
    }

    private static void poll(final GameTestHelper helper, final List<Colony> colonies, final int maxTicks, final BooleanSupplier check, final Runnable onDone,
      final Supplier<String> failure)
    {
        final int[] elapsed = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            helper.getLevel().getServer().clockManager().setTotalTicks(helper.getLevel().registryAccess().getOrThrow(WorldClocks.OVERWORLD), 6000L);
            for (final Colony colony : colonies)
            {
                colony.getRequestManager().tick();
                IdleCitizenArbiter.tick(colony);
                colony.getLabourBoard().tick();
            }
            if (check.getAsBoolean())
            {
                onDone.run();
                return;
            }
            if (elapsed[0] % 500 == 0)
            {
                Log.getLogger().info("SHAFTS poll t={} {}", elapsed[0], failure.get());
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

    private static String describe(final Colony colony, final List<ICitizenData> citizens)
    {
        final StringBuilder out = new StringBuilder();
        for (final LabourShafts.Shaft shaft : colony.getLabourData().shafts().shafts())
        {
            out.append("shaft ").append(shaft.id).append(' ').append(shaft.state()).append(" mined ").append(shaft.mined()).append(" well ").append(shaft.cursor(0))
              .append(" workers ").append(colony.getLabourBoard().shafts().workersOn(shaft.id)).append("; ");
        }
        out.append("search ").append(colony.getLabourBoard().shafts().search()).append("; claims ").append(colony.getLabourBoard().claims().size());
        for (final ICitizenData citizen : citizens)
        {
            final var session = IdleCitizenArbiter.sessionOf(citizen);
            out.append("; c").append(citizen.getId()).append(' ').append(session instanceof LabourSession s ? s.phaseName() : session == null ? "none" : session.getTaskId())
              .append(' ').append(LabourSession.inventoryOf(citizen));
        }
        return out.toString();
    }

    private static boolean dug(final ServerLevel level, final BlockPos pos)
    {
        final BlockState state = level.getBlockState(pos);
        return state.isAir() || state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH);
    }

    private static List<Reservation> labourReservations(final RsFixture f)
    {
        final List<Reservation> list = new ArrayList<>();
        for (final Reservation reservation : f.ledger().all())
        {
            if (reservation.reason() == ReservationReason.LABOUR)
            {
                list.add(reservation);
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ the rules (pure)

    /**
     * The plan, the laps rule, the limits and the saved record, without the world: every step of the well is one block down and next to the last, no two galleries
     * touch, the yield is what the settings say, a well is never planned below the floor or past a failed survey, the cap and the cooldown hold, and a record survives
     * a save and load (a save without shafts reads as none and writes nothing).
     */
    public static void planRules(final GameTestHelper helper)
    {
        for (int rotation = 0; rotation < 4; rotation++)
        {
            final ShaftPlan plan = new ShaftPlan(new BlockPos(100, 64, -40), rotation, 4, 24);
            helper.assertTrue(plan.size() == ShaftPlan.planBlocks(4, 24) && new HashSet<>(plan.cells()).size() == plan.size(), "plan blocks " + plan.size());
            for (int cell = 0; cell + 1 < 32; cell++)
            {
                final int lap = cell / 8;
                final int k = cell % 8;
                final int nextLap = (cell + 1) / 8;
                final int nextK = (cell + 1) % 8;
                final int[] a = plan.ring(k);
                final int[] b = plan.ring(nextK);
                helper.assertTrue(plan.floor(lap, k) - plan.floor(nextLap, nextK) == 1, "a step of the well is not one block down at cell " + cell);
                helper.assertTrue(Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) == 1, "two cells of the well are not next to each other at " + cell);
            }
            helper.assertTrue(plan.bottom() == 64 - 32, "the well is not 32 deep: " + plan.bottom());
            for (int s = 1; s < plan.segments(); s++)
            {
                for (int t = s + 1; t < plan.segments(); t++)
                {
                    for (int i = plan.start(s); i < plan.end(s); i++)
                    {
                        for (int j = plan.start(t); j < plan.end(t); j++)
                        {
                            helper.assertTrue(plan.cell(i).distManhattan(plan.cell(j)) > 1, "galleries " + s + " and " + t + " touch at " + plan.cell(i));
                        }
                    }
                }
            }
        }
        helper.assertTrue(ShaftSites.lapsFor(64, Integer.MIN_VALUE, 8, 4) == 4, "four laps in deep stone");
        helper.assertTrue(ShaftSites.lapsFor(64, 60, 8, 4) == 0, "a failure in the first lap is a refusal");
        helper.assertTrue(ShaftSites.lapsFor(64, 50, 8, 4) == 1, "a failure in the second lap keeps one");
        helper.assertTrue(ShaftSites.lapsFor(30, Integer.MIN_VALUE, 8, 4) == 2, "the floor bounds the laps: " + ShaftSites.lapsFor(30, Integer.MIN_VALUE, 8, 4));
        helper.assertTrue(!LabourShafts.cooledDown(1000, 1000 + 23999, 24000) && LabourShafts.cooledDown(1000, 1000 + 24000, 24000) && LabourShafts.cooledDown(5000, 10, 24000),
          "the cooldown");
        helper.assertTrue(ShaftSites.ring(0, 0, 12, 48, 4).get(0)[2] == 144, "the search starts nearest");

        final LabourData data = new LabourData();
        helper.assertTrue(!data.write().contains("shafts"), "a colony without shafts writes them");
        final LabourShafts record = data.shafts();
        final CompoundTag empty = new CompoundTag();
        record.read(empty);
        helper.assertTrue(record.shafts().isEmpty(), "an old save has shafts");

        final LabourData round = new LabourData();
        round.read(data.write());
        helper.assertTrue(round.shafts().shafts().isEmpty(), "shafts from nothing");
        helper.succeed();
    }

    // ------------------------------------------------------------------ opening

    /**
     * A builder's hut asks for cobblestone, nobody makes it, no dig zone: labour looks for a spot by itself, opens a shaft in the hill and a jobless citizen with a wooden
     * pickaxe digs it and hands the cobblestone in. Only blocks of the shaft's plan were dug; the managers were told once.
     */
    public static void opensOnACobbleRequest(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "shaft opens");
        final Colony colony = (Colony) f.colony;
        colony.getLabourBoard().shafts().resetSearch();
        colony.getLabourBoard().shafts().centreForTests(helper.absolutePos(new BlockPos((HX0 + HX1) / 2, 1, (HZ0 + HZ1) / 2)));
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = labourer(f, new BlockPos(14, 1, 16), new ItemStack(Items.WOODEN_PICKAXE));
            final IToken<?> cobble = ask(f.builder, new ItemStack(Items.COBBLESTONE, 6));
            poll(helper, List.of(colony), 9000, () -> inHut(f.builder, Items.COBBLESTONE) >= 6, () -> {
                final List<LabourShafts.Shaft> shafts = colony.getLabourData().shafts().shafts();
                helper.assertTrue(shafts.size() == 1 && !shafts.get(0).marked, "not one automatic shaft: " + shafts.size());
                final LabourShafts.Shaft shaft = shafts.get(0);
                final BlockPos low = helper.absolutePos(new BlockPos(HX0, 1, HZ0));
                final BlockPos high = helper.absolutePos(new BlockPos(HX1, TOP, HZ1));
                helper.assertTrue(shaft.entrance.getX() >= low.getX() && shaft.entrance.getX() <= high.getX() && shaft.entrance.getZ() >= low.getZ()
                                    && shaft.entrance.getZ() <= high.getZ() && shaft.entrance.getY() == high.getY(), "the shaft is not on the hill: " + shaft.entrance);
                final ShaftPlan plan = shaft.plan();
                for (int x = HX0; x <= HX1; x++)
                {
                    for (int z = HZ0; z <= HZ1; z++)
                    {
                        for (int y = 1; y <= TOP; y++)
                        {
                            final BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
                            helper.assertTrue(!f.level.getBlockState(pos).isAir() || plan.contains(pos), "a block outside the shaft's plan was dug: " + pos);
                        }
                    }
                }
                final long opened = EVENTS.stream().filter(e -> e.colony() == colony && e.kind() == LabourShaftEvents.Kind.OPENED).count();
                helper.assertTrue(opened == 1, "the opening was told " + opened + " times");
                end();
                helper.succeed();
            }, () -> f.describe(cobble) + " | " + describe(colony, List.of(idle)));
        });
    }

    /**
     * The survey refuses what is not natural ground: a block a player placed under the opening, an air room under it, water under it, a spot next to a hut, and a spot
     * in another colony's claim. A clean spot is accepted. The other colony never sees this colony's shaft.
     */
    public static void surveyRefusesBuildsCavesWaterHutsAndClaims(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "shaft survey");
        final Colony colony = (Colony) f.colony;
        final int dx = 240;
        final Colony other = foundAt(helper, "shaft survey b", dx);
        helper.runAfterDelay(100, () -> {
            place(helper, new BlockPos(S1.getX() + 1, TOP - 4, S1.getZ()), Blocks.STONE.defaultBlockState(), f.player);
            helper.assertTrue(colony.getLabourData().placements().placed(helper.absolutePos(new BlockPos(S1.getX() + 1, TOP - 4, S1.getZ())).asLong()), "the placed stone is not on record");
            expectRefusal(helper, colony, abs(helper, S1), LabourShafts.Refusal.NOT_NATURAL, "a placed block under the opening");
            for (int y = TOP - 6; y <= TOP - 5; y++)
            {
                helper.setBlock(new BlockPos(S2.getX() - 1, y, S2.getZ() + 1), Blocks.AIR.defaultBlockState());
            }
            expectRefusal(helper, colony, abs(helper, S2), LabourShafts.Refusal.NOT_NATURAL, "an air room under the opening");
            helper.setBlock(new BlockPos(S3.getX(), TOP - 3, S3.getZ() + 2), Blocks.WATER.defaultBlockState());
            expectRefusal(helper, colony, abs(helper, S3), LabourShafts.Refusal.NEAR_WATER, "water under the opening");
            expectRefusal(helper, colony, helper.absolutePos(new BlockPos(17, 0, 3)), LabourShafts.Refusal.NEAR_BUILDING, "a spot next to the warehouse");
            expectRefusal(helper, colony, helper.absolutePos(new BlockPos(dx + 12, 0, 12)), LabourShafts.Refusal.OUTSIDE, "a spot in the other colony's claim");
            final LabourShafts.Shaft shaft = mark(helper, colony, S4);
            helper.assertTrue(shaft.marked && shaft.laps == 1, "the clean spot");
            helper.assertTrue(EVENTS.stream().anyMatch(e -> e.colony() == colony && e.kind() == LabourShaftEvents.Kind.OPENED && e.marked()), "the marked shaft was not told");
            helper.assertTrue(other.getLabourData().shafts().shafts().isEmpty() && !other.getLabourBoard().shafts().hasWork(-1), "the other colony sees the shaft");
            end();
            helper.succeed();
        });
    }

    private static void expectRefusal(final GameTestHelper helper, final Colony colony, final BlockPos column, final LabourShafts.Refusal expected, final String what)
    {
        final ShaftSites.Marked marked = colony.getLabourBoard().markShaft(column, 0);
        helper.assertTrue(marked.shaft() == null && marked.refusal() == expected, what + ": " + (marked.shaft() != null ? "accepted" : marked.refusal()));
    }

    private static Colony foundAt(final GameTestHelper helper, final String name, final int dx)
    {
        final ServerLevel level = helper.getLevel();
        forceArea(level, helper.absolutePos(new BlockPos(dx - 8, 1, -8)), helper.absolutePos(new BlockPos(dx + 40, 1, 32)));
        for (int x = dx - 8; x < dx + 40; x++)
        {
            for (int z = -8; z < 32; z++)
            {
                level.setBlock(helper.absolutePos(new BlockPos(x, 0, z)), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 1; y < 8; y++)
                {
                    level.setBlock(helper.absolutePos(new BlockPos(x, y, z)), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        final BlockPos hallPos = helper.absolutePos(new BlockPos(dx + 2, 1, 2));
        level.setBlock(hallPos, ModBlocks.blockHutTownHall.defaultBlockState(), 3);
        final TileEntityColonyBuilding hall = (TileEntityColonyBuilding) level.getBlockEntity(hallPos);
        hall.setPackName("Minecolonies Original");
        hall.setBlueprintPath("fundamentals/townhall1.blueprint");
        final IColony colony = IColonyManager.getInstance().createColony(level, hallPos, FakePlayerFactory.getMinecraft(level), name, "Minecolonies Original");
        helper.assertTrue(colony != null, "second colony not created");
        final IBuilding townHall = colony.getServerBuildingManager().addNewBuilding(hall, level);
        helper.assertTrue(townHall != null, "second town hall not registered");
        townHall.setBuildingLevel(1);
        return (Colony) colony;
    }

    // ------------------------------------------------------------------ the fresh look

    /**
     * After the shaft was surveyed somebody opened a cave next to one block of the well and put water next to another. The worker digs down to the cave and stops: that
     * block and everything below stays, the water never moves, the well is left (ended), and the fresh look refuses both blocks.
     */
    public static void neverBreaksNextToUnknownAirOrFluid(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "shaft fresh look");
        final Colony colony = (Colony) f.colony;
        helper.runAfterDelay(100, () -> {
            final LabourShafts.Shaft shaft = mark(helper, colony, S1);
            final ShaftPlan plan = shaft.plan();
            final BlockPos cave = plan.cell(14);
            final BlockPos wet = plan.cell(20);
            final BlockPos pocket = outward(plan, cave);
            final BlockPos water = outward(plan, wet);
            f.level.setBlock(pocket, Blocks.AIR.defaultBlockState(), 2);
            f.level.setBlock(water, Blocks.WATER.defaultBlockState(), 2);
            helper.assertTrue(colony.getLabourBoard().shafts().check(shaft, cave) == ShaftSites.Safety.END, "the block next to the cave may be broken");
            helper.assertTrue(colony.getLabourBoard().shafts().check(shaft, wet) == ShaftSites.Safety.END, "the block next to the water may be broken");
            final ICitizenData idle = labourer(f, new BlockPos(14, 1, 16), new ItemStack(Items.WOODEN_PICKAXE));
            final IToken<?> cobble = ask(f.builder, new ItemStack(Items.COBBLESTONE, 24));
            poll(helper, List.of(colony), 8000, () -> {
                helper.assertTrue(!dug(f.level, cave) && !dug(f.level, wet), "a block next to the cave or the water was broken");
                return shaft.segmentDone(0);
            }, () -> {
                helper.assertTrue(shaft.segmentEnded(0), "the well was not left at the cave");
                helper.assertTrue(f.level.getFluidState(water).is(Fluids.WATER) && f.level.getFluidState(water).isSource(), "the water moved");
                for (final BlockPos pos : plan.cells())
                {
                    helper.assertTrue(f.level.getFluidState(pos).isEmpty(), "water came into the shaft at " + pos);
                }
                for (int i = 14; i < plan.end(0); i++)
                {
                    helper.assertTrue(!f.level.getBlockState(plan.cell(i)).isAir() || plan.cell(i).getY() > abs(helper, S1).getY(), "the well was dug below the cave at " + i);
                }
                end();
                helper.succeed();
            }, () -> f.describe(cobble) + " | " + describe(colony, List.of(idle)));
        });
    }

    /**
     * @return the shell block next to a well block, away from the pillar.
     */
    private static BlockPos outward(final ShaftPlan plan, final BlockPos cell)
    {
        final int dx = Integer.signum(cell.getX() - plan.entrance().getX());
        final int dz = Integer.signum(cell.getZ() - plan.entrance().getZ());
        return dx != 0 ? cell.offset(dx, 0, 0) : cell.offset(0, 0, dz);
    }

    // ------------------------------------------------------------------ sharing

    /**
     * One worker digs part of the well and is then given a job; another continues from where he stopped: no block before the cursor is left, and nobody dug past it.
     */
    public static void anotherWorkerResumes(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("total", 1);
        LabourSettings.override("colonyShafts", false);
        final RsFixture f = found(helper, "shaft resume");
        final Colony colony = (Colony) f.colony;
        helper.runAfterDelay(100, () -> {
            final LabourShafts.Shaft shaft = mark(helper, colony, S1);
            final ICitizenData first = labourer(f, new BlockPos(14, 1, 16), new ItemStack(Items.WOODEN_PICKAXE));
            final IToken<?> cobble = ask(f.builder, new ItemStack(Items.COBBLESTONE, 16));
            final int[] stoppedAt = {-1};
            final Set<Integer> diggers = new HashSet<>();
            final List<ICitizenData> shown = new ArrayList<>(List.of(first));
            poll(helper, List.of(colony), 9000, () -> {
                for (final Integer worker : colony.getLabourBoard().shafts().workersOn(shaft.id))
                {
                    diggers.add(worker);
                }
                if (stoppedAt[0] < 0)
                {
                    if (shaft.mined() >= 5 && colony.getLabourBoard().shafts().assignmentOf(first.getId()) != null)
                    {
                        helper.assertTrue(f.builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(first), "the first worker was not hired");
                        IdleCitizenArbiter.tick(colony);
                        helper.assertTrue(colony.getLabourBoard().shafts().assignmentOf(first.getId()) == null, "the hired worker still holds the shaft");
                        stoppedAt[0] = shaft.cursor(0);
                        shown.add(labourer(f, new BlockPos(14, 1, 18), new ItemStack(Items.WOODEN_PICKAXE)));
                    }
                    return false;
                }
                return shaft.cursor(0) >= stoppedAt[0] + 6;
            }, () -> {
                for (int i = 0; i < shaft.cursor(0); i++)
                {
                    helper.assertTrue(dug(f.level, shaft.plan().cell(i)), "a block before the cursor was skipped: " + i);
                }
                for (int i = shaft.cursor(0) + 1; i < shaft.plan().end(0); i++)
                {
                    helper.assertTrue(!f.level.getBlockState(shaft.plan().cell(i)).isAir() || shaft.plan().cell(i).getY() > abs(helper, S1).getY(),
                      "a block past the cursor was dug: " + i);
                }
                diggers.remove(first.getId());
                helper.assertTrue(!diggers.isEmpty(), "nobody else continued the shaft");
                end();
                helper.succeed();
            }, () -> f.describe(cobble) + " | stopped " + stoppedAt[0] + " | " + describe(colony, shown));
        });
    }

    /**
     * Three labourers want cobblestone and the colony has one shaft (with galleries): never more than two dig in it at once, never two in one segment, and two do dig at
     * once once the galleries are open.
     */
    public static void twoWorkersAtMost(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("shaftGallery", 4);
        LabourSettings.override("shaftWorkers", 2);
        LabourSettings.override("colonyShafts", false);
        LabourSettings.override("share", 100);
        final RsFixture f = found(helper, "shaft cap");
        final Colony colony = (Colony) f.colony;
        helper.runAfterDelay(100, () -> {
            final LabourShafts.Shaft shaft = mark(helper, colony, S1);
            final List<ICitizenData> workers = new ArrayList<>();
            for (int i = 0; i < 3; i++)
            {
                workers.add(labourer(f, new BlockPos(14, 1, 14 + 2 * i), new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.TORCH, 8)));
            }
            // (one request per worker: a claim takes up to a whole request)
            final IToken<?> cobble = ask(f.builder, new ItemStack(Items.COBBLESTONE, 12));
            ask(f.builder, new ItemStack(Items.COBBLESTONE, 12));
            ask(f.builder, new ItemStack(Items.COBBLESTONE, 12));
            final int[] most = {0};
            poll(helper, List.of(colony), 12000, () -> {
                final List<Integer> on = colony.getLabourBoard().shafts().workersOn(shaft.id);
                helper.assertTrue(on.size() <= 2, "more than two in the shaft: " + on);
                final java.util.Collection<Integer> holders = colony.getLabourBoard().shafts().takenForTests().values();
                helper.assertTrue(new HashSet<>(holders).size() == holders.size(), "a citizen holds two segments: " + holders);
                most[0] = Math.max(most[0], on.size());
                return most[0] == 2 && shaft.mined() >= 26;
            }, () -> {
                end();
                helper.succeed();
            }, () -> "most " + most[0] + " | " + f.describe(cobble) + " | " + describe(colony, workers));
        });
    }

    // ------------------------------------------------------------------ builders

    private static com.minecolonies.core.colony.workorders.WorkOrderDecoration cobbleFloor(final GameTestHelper helper, final Colony colony, final IBuilding hut, final int x,
      final int z, final int size)
    {
        final com.minecolonies.core.colony.workorders.WorkOrderDecoration order = com.minecolonies.core.colony.workorders.WorkOrderDecoration.create(
          com.minecolonies.api.colony.workorders.WorkOrderType.BUILD, "Minecolonies Original", "gametest/shaft-floor.blueprint", "gametest.shaft-floor",
          helper.absolutePos(new BlockPos(x, 1, z)), com.ldtteam.structurize.util.RotationMirror.NONE, 0);
        final com.ldtteam.structurize.blueprints.v1.Blueprint blueprint = new com.ldtteam.structurize.blueprints.v1.Blueprint((short) size, (short) 1, (short) size)
                                                                            .setName("shaft-floor").setFileName("shaft-floor").setPackName("Minecolonies Original");
        for (int dx = 0; dx < size; dx++)
        {
            for (int dz = 0; dz < size; dz++)
            {
                blueprint.addBlockState(new BlockPos(dx, 0, dz), Blocks.COBBLESTONE.defaultBlockState());
            }
        }
        blueprint.setCachePrimaryOffset(BlockPos.ZERO);
        order.setBlueprint(blueprint, helper.getLevel());
        colony.getWorkManager().addWorkOrder(order, true);
        helper.assertTrue(order.getID() > 0, "no id for the order");
        order.setClaimedBy(hut.getID());
        ((com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder) hut).setWorkOrder(order);
        return order;
    }

    /**
     * A builder stalled on cobblestone for his own build never opens a shaft: with labour's own openings off and no shaft he fetches nothing. Once a manager marks a
     * shaft he digs his cobblestone there himself, for his own hut only, and builds.
     */
    public static void builderUsesExistingShaftsOnly(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("graceTicks", 200);
        LabourSettings.override("total", 0);
        LabourSettings.override("colonyShafts", false);
        final RsFixture f = found(helper, "shaft builder");
        final Colony colony = (Colony) f.colony;
        helper.runAfterDelay(100, () -> {
            final ICitizenData builder = labourer(f, new BlockPos(12, 1, 8), new ItemStack(Items.WOODEN_PICKAXE));
            helper.assertTrue(f.builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(builder), "builder not assigned");
            final int size = 2;
            cobbleFloor(helper, colony, f.builder, 18, 14, size);
            final long start = f.level.getGameTime();
            final boolean[] marked = {false};
            final boolean[] fetched = {false};
            poll(helper, List.of(colony), 16000, () -> {
                if (!marked[0])
                {
                    helper.assertTrue(colony.getLabourData().shafts().shafts().isEmpty(), "a shaft was opened without a mark");
                    helper.assertTrue(colony.getLabourBoard().claimOf(builder.getId()) == null, "the builder found cobblestone with no shaft");
                    if (f.level.getGameTime() - start >= 1600)
                    {
                        mark(helper, colony, S1);
                        marked[0] = true;
                    }
                    return false;
                }
                for (final LabourClaim claim : colony.getLabourBoard().claims())
                {
                    helper.assertTrue(claim.source() == LabourClaim.Source.SELF && claim.destination().equals(f.builder.getID()), "a claim of another kind: " + claim.source());
                    fetched[0] = true;
                }
                int placed = 0;
                for (int dx = 0; dx < size; dx++)
                {
                    for (int dz = 0; dz < size; dz++)
                    {
                        placed += helper.getBlockState(new BlockPos(18 + dx, 1, 14 + dz)).is(Blocks.COBBLESTONE) ? 1 : 0;
                    }
                }
                return fetched[0] && placed == size * size;
            }, () -> {
                helper.assertTrue(colony.getLabourData().shafts().shafts().size() == 1, "the builder opened a shaft");
                end();
                helper.succeed();
            }, () -> "marked " + marked[0] + " | " + describe(colony, List.of(builder)));
        });
    }

    // ------------------------------------------------------------------ the miner

    /**
     * A built miner hut covers cobblestone: no shaft is looked for and nobody digs the open one, not even for the miner's own request; a spot near the miner's hut is
     * refused. The miner's own first cobblestone request is not covered for him (his hut is the only one that covers it): it is a labour candidate (a dig zone may
     * serve it) instead of a request nobody looks at.
     */
    public static void minerStopsNewShafts(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("shaftMinerClearance", 20);
        final RsFixture f = found(helper, "shaft miner");
        final Colony colony = (Colony) f.colony;
        helper.runAfterDelay(100, () -> {
            final LabourShafts.Shaft open = mark(helper, colony, new BlockPos(34, 0, 40));
            final IBuilding miner = f.place(ModBlocks.blockHutMiner, new BlockPos(50, 1, 2), "fundamentals/miner1.blueprint");
            miner.setBuildingLevel(1);
            colony.getLabourBoard().invalidate();
            helper.assertTrue(colony.getLabourBoard().coverage().covered(Items.COBBLESTONE), "the miner does not cover cobblestone");
            expectRefusal(helper, colony, abs(helper, new BlockPos(46, 0, 14)), LabourShafts.Refusal.NEAR_MINER, "a spot next to the miner");
            final IToken<?> own = ask(miner, new ItemStack(Items.COBBLESTONE, 32));
            helper.assertTrue(!colony.getLabourBoard().coverage().coveredFor(Items.COBBLESTONE, miner.getID()), "the miner's own cobblestone is covered for him");
            final ICitizenData idle = labourer(f, new BlockPos(14, 1, 16), new ItemStack(Items.WOODEN_PICKAXE));
            final IToken<?> cobble = ask(f.builder, new ItemStack(Items.COBBLESTONE, 8));
            final long start = f.level.getGameTime();
            poll(helper, List.of(colony), 4000, () -> {
                helper.assertTrue(colony.getLabourData().shafts().shafts().size() == 1, "a new shaft was opened while the miner covers cobblestone");
                helper.assertTrue(colony.getLabourBoard().shafts().search() != ShaftSites.Search.SEARCHING, "a spot is looked for while the miner covers cobblestone");
                for (final LabourClaim claim : colony.getLabourBoard().claims())
                {
                    helper.assertTrue(!cobble.equals(claim.request()), "the builder's covered cobblestone was claimed");
                }
                helper.assertTrue(inHut(f.builder, Items.COBBLESTONE) == 0 && open.mined() == 0, "the shaft was dug while the miner covers cobblestone");
                return f.level.getGameTime() - start >= 1500;
            }, () -> {
                helper.assertTrue(colony.getLabourBoard().candidate(own) != null, "the miner's own request is no labour candidate: " + f.describe(own));
                helper.assertTrue(colony.getLabourBoard().candidate(cobble) == null, "the builder's covered request is a labour candidate");
                helper.assertTrue(open.state() == LabourShafts.State.OPEN, "the open shaft changed: " + open.state());
                end();
                helper.succeed();
            }, () -> f.describe(cobble) + " | " + describe(colony, List.of(idle)));
        });
    }

    // ------------------------------------------------------------------ limits

    /**
     * At most {@code shaftsMax} shafts count (closed ones do not), a closed shaft opens again only while there is room, and labour opens at most one shaft by itself per
     * cooldown.
     */
    public static void limits(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("shaftsMax", 2);
        LabourSettings.override("shaftCooldownTicks", 24000);
        final RsFixture f = found(helper, "shaft limits");
        final Colony colony = (Colony) f.colony;
        final var board = colony.getLabourBoard();
        helper.runAfterDelay(100, () -> {
            final LabourShafts.Shaft a = mark(helper, colony, S1);
            mark(helper, colony, S2);
            expectRefusal(helper, colony, abs(helper, S3), LabourShafts.Refusal.TOO_MANY, "a third shaft");
            helper.assertTrue(board.closeShaft(a.id) == null && a.state() == LabourShafts.State.CLOSED, "close");
            final LabourShafts.Shaft c = mark(helper, colony, S3);
            helper.assertTrue(board.reopenShaft(a.id) == LabourShafts.Refusal.TOO_MANY, "a closed shaft opened again past the limit");
            helper.assertTrue(board.closeShaft(c.id) == null && board.reopenShaft(a.id) == null && a.state() == LabourShafts.State.OPEN, "reopen with room");
            helper.assertTrue(board.closeShaft(4711) == LabourShafts.Refusal.NO_SHAFT, "an unknown shaft");
            // the automatic openings: all closed, demand, a jobless citizen; the first opens at once, the second waits for the cooldown
            LabourSettings.override("shaftsMax", 6);
            for (final LabourShafts.Shaft shaft : board.shafts().record().shafts())
            {
                board.closeShaft(shaft.id);
            }
            board.shafts().resetSearch();
            board.shafts().centreForTests(helper.absolutePos(new BlockPos((HX0 + HX1) / 2, 1, (HZ0 + HZ1) / 2)));
            final ICitizenData idle = labourer(f, new BlockPos(14, 1, 16));
            final IToken<?> cobble = ask(f.builder, new ItemStack(Items.COBBLESTONE, 64));
            final int[] phase = {0};
            final long[] closedAt = {0};
            poll(helper, List.of(colony), 9000, () -> {
                final long auto = board.shafts().record().shafts().stream().filter(s -> !s.marked).count();
                if (phase[0] == 0)
                {
                    if (auto == 1)
                    {
                        for (final LabourShafts.Shaft shaft : board.shafts().record().shafts())
                        {
                            board.closeShaft(shaft.id);
                        }
                        phase[0] = 1;
                        closedAt[0] = f.level.getGameTime();
                    }
                    return false;
                }
                if (phase[0] == 1)
                {
                    helper.assertTrue(auto == 1, "a second shaft opened within the cooldown");
                    if (f.level.getGameTime() - closedAt[0] >= 2600)
                    {
                        LabourSettings.override("shaftCooldownTicks", 0);
                        phase[0] = 2;
                    }
                    return false;
                }
                return auto == 2;
            }, () -> {
                end();
                helper.succeed();
            }, () -> "phase " + phase[0] + " | " + f.describe(cobble) + " | " + describe(colony, List.of(idle)));
        });
    }

    // ------------------------------------------------------------------ ores and tools

    /**
     * Coal in the well is mined with a wooden pickaxe; iron in a gallery stops it (an ore face) and the other gallery goes on; the coal goes to the warehouse, the
     * cobblestone to the hut that asked. A worker with a stone pickaxe mines iron in another shaft. Each ore kind is told once per shaft.
     */
    public static void oresAndFinds(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("shaftGallery", 4);
        LabourSettings.override("colonyShafts", false);
        LabourSettings.override("total", 1);
        final RsFixture f = found(helper, "shaft ores");
        final Colony colony = (Colony) f.colony;
        helper.runAfterDelay(100, () -> {
            final LabourShafts.Shaft first = mark(helper, colony, S1);
            final ShaftPlan plan = first.plan();
            f.level.setBlock(plan.cell(5), Blocks.COAL_ORE.defaultBlockState(), 2);
            final BlockPos iron = plan.cell(plan.start(1) + 3);
            f.level.setBlock(iron, Blocks.IRON_ORE.defaultBlockState(), 2);
            final ICitizenData wood = labourer(f, new BlockPos(14, 1, 16), new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.TORCH, 4));
            final IToken<?> cobble = ask(f.builder, new ItemStack(Items.COBBLESTONE, 24));
            final int[] phase = {0};
            final LabourShafts.Shaft[] second = {null};
            final List<ICitizenData> shown = new ArrayList<>(List.of(wood));
            poll(helper, List.of(colony), 16000, () -> {
                if (phase[0] == 0)
                {
                    if (inHut(f.builder, Items.COBBLESTONE) < 24 || f.physical(s -> s.is(Items.COAL)) < 1)
                    {
                        return false;
                    }
                    helper.assertTrue(first.segmentEnded(1) && f.level.getBlockState(iron).is(Blocks.IRON_ORE), "the iron was not left as an ore face");
                    helper.assertTrue(first.oreFaces().size() == 1 && first.ores().contains("minecraft:iron_ore") && first.ores().contains("minecraft:coal_ore"), "the ores");
                    helper.assertTrue(f.builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(wood), "the first worker was not hired");
                    colony.getLabourBoard().closeShaft(first.id);
                    second[0] = mark(helper, colony, S4);
                    f.level.setBlock(second[0].plan().cell(5), Blocks.IRON_ORE.defaultBlockState(), 2);
                    shown.add(labourer(f, new BlockPos(14, 1, 18), new ItemStack(Items.STONE_PICKAXE)));
                    ask(f.builder, new ItemStack(Items.COBBLESTONE, 6));
                    phase[0] = 1;
                    return false;
                }
                return f.physical(s -> s.is(Items.RAW_IRON)) >= 1;
            }, () -> {
                helper.assertTrue(second[0].ores().contains("minecraft:iron_ore") && second[0].oreFaces().isEmpty(), "the stone pickaxe did not take the iron");
                final long told = EVENTS.stream().filter(e -> e.colony() == colony && e.kind() == LabourShaftEvents.Kind.ORE_FOUND).count();
                helper.assertTrue(told == 3, "ores told " + told + " times (coal and iron in the first shaft, iron in the second)");
                end();
                helper.succeed();
            }, () -> "phase " + phase[0] + " | " + f.describe(cobble) + " | coal " + f.physical(s -> s.is(Items.COAL)) + " | " + describe(colony, shown));
        });
    }

    /**
     * The warehouse holds an iron and a wooden pickaxe; the worker has none: he takes the wooden one for the shaft, never the iron one. When it breaks he makes a new
     * one at a table.
     */
    public static void toolRules(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("colonyShafts", false);
        LabourSettings.override("total", 1);
        final RsFixture f = found(helper, "shaft tools");
        final Colony colony = (Colony) f.colony;
        helper.setBlock(new BlockPos(20, 1, 12), Blocks.CRAFTING_TABLE.defaultBlockState());
        helper.runAfterDelay(100, () -> {
            mark(helper, colony, S1);
            final ItemStack worn = new ItemStack(Items.WOODEN_PICKAXE);
            worn.setDamageValue(worn.getMaxDamage() - 2);
            f.stock(0, new ItemStack(Items.IRON_PICKAXE));
            f.stock(1, worn);
            final ICitizenData idle = labourer(f, new BlockPos(14, 1, 16), new ItemStack(Items.OAK_LOG, 4));
            final IToken<?> cobble = ask(f.builder, new ItemStack(Items.COBBLESTONE, 8));
            poll(helper, List.of(colony), 12000, () -> inHut(f.builder, Items.COBBLESTONE) >= 8, () -> {
                helper.assertTrue(f.physical(s -> s.is(Items.IRON_PICKAXE)) == 1, "the iron pickaxe left the warehouse");
                helper.assertTrue(f.physical(s -> s.is(Items.WOODEN_PICKAXE)) == 0, "the wooden pickaxe was not taken");
                helper.assertTrue(InventoryUtils.getItemCountInItemHandler(idle.getInventory(), s -> s.is(Items.IRON_PICKAXE)) == 0, "the worker carries the iron pickaxe");
                helper.assertTrue(InventoryUtils.getItemCountInItemHandler(idle.getInventory(), s -> s.is(Items.WOODEN_PICKAXE)) == 1, "no new pickaxe after the old one broke");
                helper.assertTrue(labourReservations(f).isEmpty(), "ledger entries are left: " + labourReservations(f));
                end();
                helper.succeed();
            }, () -> f.describe(cobble) + " | " + describe(colony, List.of(idle)));
        });
    }

    // ------------------------------------------------------------------ close and save

    /**
     * Closing a shaft stops its work at once (no block is dug afterwards, the worker lets go); the closed shaft, its cursor, count and ores survive a save and load of
     * the colony's labour record.
     */
    public static void closeAndSave(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("colonyShafts", false);
        final RsFixture f = found(helper, "shaft save");
        final Colony colony = (Colony) f.colony;
        helper.runAfterDelay(100, () -> {
            final LabourShafts.Shaft shaft = mark(helper, colony, S1);
            final ICitizenData idle = labourer(f, new BlockPos(14, 1, 16), new ItemStack(Items.WOODEN_PICKAXE));
            final IToken<?> cobble = ask(f.builder, new ItemStack(Items.COBBLESTONE, 20));
            final long[] closedAt = {-1};
            final int[] minedAt = {0};
            poll(helper, List.of(colony), 8000, () -> {
                if (closedAt[0] < 0)
                {
                    if (shaft.mined() >= 4)
                    {
                        helper.assertTrue(colony.getLabourBoard().closeShaft(shaft.id) == null, "close");
                        closedAt[0] = f.level.getGameTime();
                        minedAt[0] = shaft.mined();
                    }
                    return false;
                }
                final long since = f.level.getGameTime() - closedAt[0];
                if (since >= 40)
                {
                    helper.assertTrue(shaft.mined() <= minedAt[0] + 1, "blocks were dug after the shaft was closed: " + shaft.mined() + " > " + minedAt[0]);
                    helper.assertTrue(colony.getLabourBoard().shafts().assignmentOf(idle.getId()) == null, "the worker still holds the closed shaft");
                }
                return since >= 400;
            }, () -> {
                final LabourData copy = new LabourData();
                copy.read(colony.getLabourData().write());
                final LabourShafts.Shaft read = copy.shafts().byId(shaft.id);
                helper.assertTrue(read != null && read.state() == LabourShafts.State.CLOSED && read.entrance.equals(shaft.entrance) && read.cursor(0) == shaft.cursor(0)
                                    && read.mined() == shaft.mined() && read.laps == shaft.laps && read.marked, "the shaft did not survive a save: " + (read == null ? "gone" : read.state()));
                helper.assertTrue(copy.shafts().revision() > colony.getLabourData().shafts().revision(), "a load did not move the revision on");
                helper.assertTrue(colony.getLabourBoard().reopenShaft(shaft.id) == null && shaft.state() == LabourShafts.State.OPEN, "reopen");
                end();
                helper.succeed();
            }, () -> f.describe(cobble) + " | " + describe(colony, List.of(idle)));
        });
    }

    /**
     * Torches go up on the pillar before the second lap is dug; a worker with no torch and no way to get one digs the first lap only and the shaft says it needs
     * torches; a hostile next to the work keeps the worker out even once he has torches.
     */
    public static void litAndSafe(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("shaftLaps", 2);
        LabourSettings.override("colonyShafts", false);
        LabourSettings.override("total", 1);
        final RsFixture f = found(helper, "shaft lit");
        final Colony colony = (Colony) f.colony;
        helper.runAfterDelay(100, () -> {
            final LabourShafts.Shaft lit = mark(helper, colony, S1);
            final LabourShafts.Shaft dark = mark(helper, colony, S4);
            helper.assertTrue(lit.laps == 2, "the hill holds two laps: " + lit.laps);
            colony.getLabourBoard().closeShaft(dark.id);
            final ICitizenData idle = labourer(f, new BlockPos(14, 1, 16), new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.TORCH, 4));
            final IToken<?> cobble = ask(f.builder, new ItemStack(Items.COBBLESTONE, 64));
            final int[] phase = {0};
            final long[] since = {0};
            final Entity[] spider = {null};
            poll(helper, List.of(colony), 16000, () -> {
                final var shafts = colony.getLabourBoard().shafts();
                if (phase[0] == 0)
                {
                    if (lit.cursor(0) < ShaftPlan.LAP_BLOCKS + 3)
                    {
                        return false;
                    }
                    for (final ShaftPlan.Torch torch : lit.plan().torches())
                    {
                        if (torch.neededFrom() <= ShaftPlan.LAP_BLOCKS)
                        {
                            helper.assertTrue(f.level.getBlockState(torch.pos()).is(Blocks.WALL_TORCH), "no torch at " + torch.pos());
                        }
                    }
                    // the dark shaft: no torch to be had
                    shafts.close(lit.id);
                    shafts.reopen(dark.id);
                    final var inventory = idle.getInventory();
                    for (int slot = 0; slot < inventory.getSlots(); slot++)
                    {
                        if (inventory.getStackInSlot(slot).is(Items.TORCH) || inventory.getStackInSlot(slot).is(Items.COBBLESTONE))
                        {
                            inventory.extractItem(slot, 64, false);
                        }
                    }
                    phase[0] = 1;
                    return false;
                }
                if (phase[0] == 1)
                {
                    helper.assertTrue(dark.cursor(0) <= ShaftPlan.LAP_BLOCKS, "the dark shaft was dug below its first lap");
                    if (!shafts.wantsTorches(dark.id))
                    {
                        return false;
                    }
                    // a hostile at the opening keeps him out, even with torches
                    shafts.resetSearch();
                    InventoryUtils.addItemStackToItemHandler(idle.getInventory(), new ItemStack(Items.TORCH, 8));
                    spider[0] = EntityTypes.SILVERFISH.spawn(f.level, dark.plan().cell(Math.max(0, dark.cursor(0) - 1)), EntitySpawnReason.COMMAND);
                    helper.assertTrue(spider[0] != null, "no silverfish");
                    if (spider[0] instanceof net.minecraft.world.entity.Mob mob)
                    {
                        mob.setNoAi(true);
                    }
                    since[0] = f.level.getGameTime();
                    phase[0] = 2;
                    return false;
                }
                helper.assertTrue(dark.cursor(0) <= ShaftPlan.LAP_BLOCKS, "he dug on with a hostile next to him");
                return f.level.getGameTime() - since[0] >= 600;
            }, () -> {
                helper.assertTrue(colony.getLabourBoard().shafts().hostileNear(dark.plan().cell(dark.cursor(0))), "the silverfish is gone");
                if (spider[0] != null)
                {
                    spider[0].discard();
                }
                end();
                helper.succeed();
            }, () -> "phase " + phase[0] + " | " + f.describe(cobble) + " | " + describe(colony, List.of(idle)));
        });
    }

    // ------------------------------------------------------------------ registration

    static final String[] NAMES = {"shaft_plan_rules", "shaft_opens_on_cobble_request", "shaft_survey_refuses", "shaft_never_breaks_next_to_air_or_fluid",
      "shaft_shared_resume", "shaft_two_workers_at_most", "shaft_builder_existing_only", "shaft_covered_by_miner", "shaft_limits", "shaft_ores_and_finds",
      "shaft_tool_rules", "shaft_close_and_save", "shaft_lit_and_safe"};

    static java.util.function.Consumer<GameTestHelper> test(final String name)
    {
        return switch (name)
        {
            case "shaft_plan_rules" -> ShaftGameTests::planRules;
            case "shaft_opens_on_cobble_request" -> ShaftGameTests::opensOnACobbleRequest;
            case "shaft_survey_refuses" -> ShaftGameTests::surveyRefusesBuildsCavesWaterHutsAndClaims;
            case "shaft_never_breaks_next_to_air_or_fluid" -> ShaftGameTests::neverBreaksNextToUnknownAirOrFluid;
            case "shaft_shared_resume" -> ShaftGameTests::anotherWorkerResumes;
            case "shaft_two_workers_at_most" -> ShaftGameTests::twoWorkersAtMost;
            case "shaft_builder_existing_only" -> ShaftGameTests::builderUsesExistingShaftsOnly;
            case "shaft_covered_by_miner" -> ShaftGameTests::minerStopsNewShafts;
            case "shaft_limits" -> ShaftGameTests::limits;
            case "shaft_ores_and_finds" -> ShaftGameTests::oresAndFinds;
            case "shaft_tool_rules" -> ShaftGameTests::toolRules;
            case "shaft_close_and_save" -> ShaftGameTests::closeAndSave;
            default -> ShaftGameTests::litAndSafe;
        };
    }
}
