package com.minecolonies.core.gametest;

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
import com.minecolonies.core.colony.labour.LabourData;
import com.minecolonies.core.colony.labour.LabourSession;
import com.minecolonies.core.colony.labour.LabourSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Labourers take natural resources only: a tree touching a building's box (not at its base), a stripped post or another wood joined to a trunk, a horizontal beam, a
 * tree whose log a player placed, and dirt a player (or a fake player) placed are never taken; a building that comes after a tree was chosen stops the felling at
 * once; automatic labour digs no ground unless allowed; the record of placed blocks survives a reload. Natural trees are still felled and stripped. Run with
 * {@code MINECOLONIES_GAMETEST_ONLY=natural}.
 */
final class NaturalSafetyGameTests
{
    private NaturalSafetyGameTests()
    {
    }

    private static final Map<GameTestHelper, List<Runnable>> RESTORE = new HashMap<>();

    private static void begin()
    {
        com.minecolonies.core.colony.labour.LabourStats.debug = true;
        LabourSettings.clearOverrides();
        LabourSettings.override("workScale", 8.0D);
        LabourSettings.override("keepTrees", 0);
        LabourSettings.override("stockpile", false);
        IdleCitizenSettings.overrideForTests(null, null, null);
    }

    private static void end(final GameTestHelper helper)
    {
        com.minecolonies.core.colony.labour.LabourStats.debug = false;
        LabourSettings.clearOverrides();
        final List<Runnable> undo = RESTORE.remove(helper);
        if (undo != null)
        {
            undo.forEach(Runnable::run);
        }
    }

    private static RsFixture found(final GameTestHelper helper, final String name)
    {
        final RsFixture f = RsFixture.found(helper, name, 2);
        // (the fixture's player places blocks in the claim: a stranger's placement there is cancelled by the colony's protection, so he is made an officer)
        helper.assertTrue(f.colony.getPermissions().addPlayer(f.player.nameAndId(), f.colony.getPermissions().getRankOfficer()), "the player could not join the colony");
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

    /**
     * A tree as the world grows it: dirt below, a trunk of the log, leaves nobody placed around the top.
     */
    private static void tree(final GameTestHelper helper, final int x, final int z, final int height, final Block log)
    {
        helper.setBlock(new BlockPos(x, 0, z), Blocks.DIRT.defaultBlockState());
        for (int y = 1; y <= height; y++)
        {
            helper.setBlock(new BlockPos(x, y, z), log.defaultBlockState());
        }
        final BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false).setValue(LeavesBlock.DISTANCE, 1);
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

    private static int count(final GameTestHelper helper, final Block block, final int x0, final int z0, final int x1, final int z1, final int y0, final int y1)
    {
        int n = 0;
        for (int x = x0; x <= x1; x++)
        {
            for (int z = z0; z <= z1; z++)
            {
                for (int y = y0; y <= y1; y++)
                {
                    if (helper.getBlockState(new BlockPos(x, y, z)).is(block))
                    {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /**
     * Places the block the way a player's hand does: the block is set, then the game's placement event is posted with the entity (a player or a fake player).
     */
    private static void place(final GameTestHelper helper, final BlockPos rel, final BlockState state, final Entity by)
    {
        helper.setBlock(rel, state);
        final BlockPos abs = helper.absolutePos(rel);
        final ServerLevel level = helper.getLevel();
        NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, abs), level.getBlockState(abs.below()), by));
    }

    private static boolean placed(final Colony colony, final GameTestHelper helper, final BlockPos rel)
    {
        return colony.getLabourData().placements().placed(helper.absolutePos(rel).asLong());
    }

    private static void moveBox(final GameTestHelper helper, final IBuilding building, final BlockPos a, final BlockPos b)
    {
        final BlockPos oldA = building.getCorners().getA();
        final BlockPos oldB = building.getCorners().getB();
        building.setCorners(a, b);
        RESTORE.computeIfAbsent(helper, h -> new ArrayList<>()).add(() -> building.setCorners(oldA, oldB));
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
                Log.getLogger().info("NATURAL poll t={} {}", elapsed[0], failure.get());
            }
            elapsed[0] += 20;
            if (elapsed[0] >= maxTicks)
            {
                end(helper);
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
        return "request " + (token == null ? "-" : f.describe(token)) + ", session " + (session == null ? "none" : session.getTaskId() + "/" + (session instanceof LabourSession l
                     ? l.phaseName() : session instanceof com.minecolonies.core.colony.labour.DirectedSession d ? d.phaseName() : "?")) + ", why "
                 + (token == null ? "-" : colony.getLabourBoard().blockedWhy(token)) + ", carries " + LabourSession.inventoryOf(citizen);
    }

    // ------------------------------------------------------------------ automatic felling

    /**
     * Automatic labour asks for oak logs. Five oak trunks look like trees but are not natural ones to take: a branch reaching into a building's box (not the base), a
     * horizontal beam of seven logs on the trunk, a spruce log joined to the trunk, a stripped oak post next to the trunk on the ground, and a trunk whose base log a
     * player placed (recorded through the placement event). All five stand whole; only the clean oak is felled and its logs are handed in.
     */
    public static void automaticFellsOnlyTheNaturalTree(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "natural auto");
        final Colony colony = (Colony) f.colony;
        // 1: branch into a building
        tree(helper, 30, 16, 5, Blocks.OAK_LOG);
        for (int z = 17; z <= 19; z++)
        {
            helper.setBlock(new BlockPos(30, 4, z), Blocks.OAK_LOG.defaultBlockState());
        }
        // 2: a beam
        tree(helper, 36, 16, 5, Blocks.OAK_LOG);
        for (int x = 37; x <= 43; x++)
        {
            helper.setBlock(new BlockPos(x, 3, 16), Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        }
        // 3: another wood joined
        tree(helper, 30, 26, 5, Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(31, 2, 26), Blocks.SPRUCE_LOG.defaultBlockState());
        // 4: a stripped post by the trunk
        tree(helper, 36, 26, 5, Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(37, 0, 26), Blocks.DIRT.defaultBlockState());
        helper.setBlock(new BlockPos(37, 1, 26), Blocks.STRIPPED_OAK_LOG.defaultBlockState());
        // 5: a player placed the base log
        tree(helper, 42, 24, 5, Blocks.OAK_LOG);
        place(helper, new BlockPos(42, 1, 24), Blocks.OAK_LOG.defaultBlockState(), f.player);
        helper.assertTrue(placed(colony, helper, new BlockPos(42, 1, 24)), "the player's placement of a log was not recorded");
        // the clean one
        tree(helper, 44, 32, 5, Blocks.OAK_LOG);
        helper.runAfterDelay(100, () -> {
            // (the box with its margin of 2 holds the branch's last log, not the trunk or the base)
            moveBox(helper, f.builder, helper.absolutePos(new BlockPos(30, 4, 21)), helper.absolutePos(new BlockPos(30, 4, 21)));
            colony.getLabourBoard().invalidate();
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final IToken<?> token = ask(f.builder, new ItemStack(Items.OAK_LOG), 3);
            poll(helper, colony, 9000, () -> {
                helper.assertTrue(count(helper, Blocks.OAK_LOG, 30, 16, 30, 19, 1, 5) == 8, "the tree reaching into the building was cut");
                helper.assertTrue(count(helper, Blocks.OAK_LOG, 36, 16, 43, 16, 1, 5) == 12, "the tree with a beam was cut");
                helper.assertTrue(count(helper, Blocks.OAK_LOG, 30, 26, 30, 26, 1, 5) == 5 && helper.getBlockState(new BlockPos(31, 2, 26)).is(Blocks.SPRUCE_LOG),
                  "the tree joined to a spruce log was cut");
                helper.assertTrue(count(helper, Blocks.OAK_LOG, 36, 26, 36, 26, 1, 5) == 5 && helper.getBlockState(new BlockPos(37, 1, 26)).is(Blocks.STRIPPED_OAK_LOG),
                  "the tree with a stripped post was cut");
                helper.assertTrue(count(helper, Blocks.OAK_LOG, 42, 24, 42, 24, 1, 5) == 5, "the tree with a placed log was cut");
                return in(f.builder, s -> s.is(Items.OAK_LOG)) >= 3;
            }, () -> {
                helper.assertTrue(count(helper, Blocks.OAK_LOG, 44, 32, 44, 32, 1, 5) < 5, "the natural tree was not felled");
                end(helper);
                helper.succeed();
            }, () -> describe(f, token, idle));
        });
    }

    /**
     * Stripped oak logs are asked for; the labourer has an axe. A stripped oak post stands joined to an oak trunk, and a "tree" of stripped oak logs carries natural
     * leaves: neither is taken (a stripped log is never a tree's, and only a stripped log he made himself is felled). A natural oak is stripped where it stands and
     * felled.
     */
    public static void strippingNeverTakesStrippedWood(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "natural strip");
        final Colony colony = (Colony) f.colony;
        tree(helper, 30, 16, 5, Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(31, 0, 16), Blocks.DIRT.defaultBlockState());
        helper.setBlock(new BlockPos(31, 1, 16), Blocks.STRIPPED_OAK_LOG.defaultBlockState());
        tree(helper, 36, 16, 5, Blocks.STRIPPED_OAK_LOG);
        tree(helper, 40, 28, 5, Blocks.OAK_LOG);
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            InventoryUtils.addItemStackToItemHandler(idle.getInventory(), new ItemStack(Items.WOODEN_AXE));
            final IToken<?> token = ask(f.builder, new ItemStack(Items.STRIPPED_OAK_LOG), 2);
            poll(helper, colony, 9000, () -> {
                helper.assertTrue(count(helper, Blocks.OAK_LOG, 30, 16, 30, 16, 1, 5) == 5 && helper.getBlockState(new BlockPos(31, 1, 16)).is(Blocks.STRIPPED_OAK_LOG),
                  "the oak with a stripped post was taken");
                helper.assertTrue(count(helper, Blocks.STRIPPED_OAK_LOG, 36, 16, 36, 16, 1, 5) == 5, "a trunk of stripped wood was taken");
                return in(f.builder, s -> s.is(Items.STRIPPED_OAK_LOG)) >= 2;
            }, () -> {
                helper.assertTrue(count(helper, Blocks.OAK_LOG, 40, 28, 40, 28, 1, 5) < 5, "the natural oak was not stripped and felled");
                end(helper);
                helper.succeed();
            }, () -> describe(f, token, idle));
        });
    }

    /**
     * A tall natural oak is chosen and felling starts; then a building's box comes over its top logs. No further log breaks: the rest of the tree stands.
     */
    public static void buildingAfterTheChoiceStopsTheFelling(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "natural fresh building");
        final Colony colony = (Colony) f.colony;
        tree(helper, 34, 20, 9, Blocks.OAK_LOG);
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final IToken<?> token = ask(f.builder, new ItemStack(Items.OAK_LOG), 9);
            final int[] atMove = {-1};
            final long[] since = {0L};
            poll(helper, colony, 9000, () -> {
                final int logs = count(helper, Blocks.OAK_LOG, 34, 20, 34, 20, 1, 9);
                if (atMove[0] < 0)
                {
                    if (logs == 9)
                    {
                        return false;
                    }
                    // (the box with its margin of 2 holds the logs from y 7 up)
                    moveBox(helper, f.builder, helper.absolutePos(new BlockPos(34, 9, 20)), helper.absolutePos(new BlockPos(34, 9, 20)));
                    atMove[0] = logs;
                    since[0] = helper.getLevel().getGameTime();
                    helper.assertTrue(logs >= 3, "the test came too late: only " + logs + " logs stand");
                    return false;
                }
                helper.assertTrue(logs == atMove[0], "a log broke after the building came: " + logs + " of " + atMove[0]);
                return helper.getLevel().getGameTime() - since[0] >= 600;
            }, () -> {
                helper.assertTrue(count(helper, Blocks.OAK_LOG, 34, 20, 34, 20, 7, 9) == 3, "a log in the building's box was cut");
                end(helper);
                helper.succeed();
            }, () -> describe(f, token, idle) + " moved at " + atMove[0]);
        });
    }

    // ------------------------------------------------------------------ the ground

    /**
     * Automatic labour asks for dirt; a patch of dirt lies on the ground, two blocks of it placed (a player, a fake player). While the colony is not allowed to dig the
     * ground nothing is dug at all; once allowed, he digs dirt, never the two placed blocks.
     */
    public static void automaticDigsNoUnprovenGround(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "natural ground");
        final Colony colony = (Colony) f.colony;
        for (int x = 30; x <= 35; x++)
        {
            for (int z = 20; z <= 23; z++)
            {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.DIRT.defaultBlockState());
            }
        }
        place(helper, new BlockPos(30, 1, 20), Blocks.DIRT.defaultBlockState(), f.player);
        place(helper, new BlockPos(35, 1, 23), Blocks.DIRT.defaultBlockState(), FakePlayerFactory.getMinecraft(f.level));
        helper.assertTrue(placed(colony, helper, new BlockPos(30, 1, 20)) && placed(colony, helper, new BlockPos(35, 1, 23)), "a placement was not recorded");
        // a stranger's placement in the claim is cancelled by the colony's protection (the game takes the block back): nothing is recorded for it
        place(helper, new BlockPos(32, 1, 21), Blocks.DIRT.defaultBlockState(), MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper));
        helper.assertTrue(!placed(colony, helper, new BlockPos(32, 1, 21)), "a cancelled placement was recorded");
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final IToken<?> token = ask(f.builder, new ItemStack(Items.DIRT), 4);
            final long start = helper.getLevel().getGameTime();
            final boolean[] allowed = {false};
            poll(helper, colony, 9000, () -> {
                helper.assertTrue(helper.getBlockState(new BlockPos(30, 1, 20)).is(Blocks.DIRT) && helper.getBlockState(new BlockPos(35, 1, 23)).is(Blocks.DIRT),
                  "placed dirt was dug");
                if (!allowed[0])
                {
                    helper.assertTrue(count(helper, Blocks.DIRT, 30, 20, 35, 23, 1, 1) == 24 && carrying(idle, s -> s.is(Items.DIRT)) == 0,
                      "the ground was dug though the colony may not dig it");
                    if (helper.getLevel().getGameTime() - start >= 800)
                    {
                        allowed[0] = true;
                        LabourSettings.allowDiggingForTest(colony.getID(), true);
                        colony.getLabourBoard().invalidate();
                    }
                    return false;
                }
                return in(f.builder, s -> s.is(Items.DIRT)) >= 4;
            }, () -> {
                LabourSettings.allowDiggingForTest(colony.getID(), false);
                end(helper);
                helper.succeed();
            }, () -> describe(f, token, idle) + " allowed " + allowed[0]);
        });
    }

    /**
     * A directed dirt order: two blocks of the area were placed (recorded through the placement event), and the colony's record is saved and read back before the
     * order starts. The order completes from the other dirt; the two placed blocks stay.
     */
    public static void directedLeavesPlacedBlocksAfterAReload(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "natural directed");
        final Colony colony = (Colony) f.colony;
        for (int x = 30; x <= 35; x++)
        {
            for (int z = 20; z <= 23; z++)
            {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.DIRT.defaultBlockState());
            }
        }
        place(helper, new BlockPos(30, 1, 20), Blocks.DIRT.defaultBlockState(), FakePlayerFactory.getMinecraft(f.level));
        place(helper, new BlockPos(33, 1, 22), Blocks.DIRT.defaultBlockState(), f.player);
        final CompoundTag saved = colony.getLabourData().write();
        final LabourData copy = new LabourData();
        copy.read(saved);
        helper.assertTrue(copy.placements().placed(helper.absolutePos(new BlockPos(30, 1, 20)).asLong()), "the record was not saved");
        colony.getLabourData().read(saved);
        helper.assertTrue(placed(colony, helper, new BlockPos(30, 1, 20)) && placed(colony, helper, new BlockPos(33, 1, 22)), "the record was lost on reading it back");
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final BlockPos a = helper.absolutePos(new BlockPos(30, 1, 20));
            final BlockPos b = helper.absolutePos(new BlockPos(35, 1, 23));
            final DirectedOrders.Start start = colony.getLabourBoard().directed().start(List.of(idle.getId()), DirectedOrder.Resource.DIRT, 4,
              DirectedOrder.Area.of(a.getX(), a.getZ(), b.getX(), b.getZ()), null);
            helper.assertTrue(start.order() != null, "refused: " + start.refusal());
            final DirectedOrder order = start.order();
            poll(helper, colony, 9000, () -> {
                helper.assertTrue(helper.getBlockState(new BlockPos(30, 1, 20)).is(Blocks.DIRT) && helper.getBlockState(new BlockPos(33, 1, 22)).is(Blocks.DIRT),
                  "placed dirt was dug");
                return !order.active();
            }, () -> {
                helper.assertTrue(order.state() == DirectedOrder.State.COMPLETED, "the order ended as " + order.state());
                end(helper);
                helper.succeed();
            }, () -> "order " + order.state() + " delivered " + order.delivered() + " carries " + LabourSession.inventoryOf(idle));
        });
    }

    // ------------------------------------------------------------------ registration

    static final String[] NAMES = {"labour_natural_auto_guards", "labour_natural_strip_guards", "labour_natural_fresh_building", "labour_natural_ground",
      "labour_natural_directed_placed"};

    static java.util.function.Consumer<GameTestHelper> test(final String name)
    {
        return switch (name)
        {
            case "labour_natural_auto_guards" -> NaturalSafetyGameTests::automaticFellsOnlyTheNaturalTree;
            case "labour_natural_strip_guards" -> NaturalSafetyGameTests::strippingNeverTakesStrippedWood;
            case "labour_natural_fresh_building" -> NaturalSafetyGameTests::buildingAfterTheChoiceStopsTheFelling;
            case "labour_natural_ground" -> NaturalSafetyGameTests::automaticDigsNoUnprovenGround;
            default -> NaturalSafetyGameTests::directedLeavesPlacedBlocksAfterAReload;
        };
    }
}
