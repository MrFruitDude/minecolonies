package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;
import com.minecolonies.core.colony.idle.IdleCitizenArbiter;
import com.minecolonies.core.colony.idle.IdleCitizenSettings;
import com.minecolonies.core.colony.labour.DirectedOrder;
import com.minecolonies.core.colony.labour.DirectedOrders;
import com.minecolonies.core.colony.labour.LabourClaim;
import com.minecolonies.core.colony.labour.LabourSession;
import com.minecolonies.core.colony.labour.LabourSettings;
import com.minecolonies.core.colony.labour.QuarryZones;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Wave B of the early loop: quarry zones (labour digs the ground only inside a zone a manager marked, never a placed block, never in another colony's zone), sand
 * smelted to glass, the early pace of a young colony (a builder's request is fetched after the short grace), and a hut placed on credit whose owed hut item labour
 * makes from the warehouse's stock. Run with {@code MINECOLONIES_GAMETEST_ONLY=waveb}.
 */
final class WaveBLabourGameTests
{
    private static final String PACK = "Minecolonies Original";

    private WaveBLabourGameTests()
    {
    }

    // ------------------------------------------------------------------ scene

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
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 40)));
        clearPosts(helper);
        // (the plot's barrier roof would be the top block of every column: taken away over the test's ground, as the natural-safety tests do)
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
     * Logs, leaves, tables and furnaces left around the plot by other tests are taken away: each test sets its own.
     */
    private static void clearPosts(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos center = helper.absolutePos(new BlockPos(20, 0, 16));
        for (int x = center.getX() - 70; x <= center.getX() + 70; x++)
        {
            for (int z = center.getZ() - 70; z <= center.getZ() + 70; z++)
            {
                if (!level.hasChunk(x >> 4, z >> 4))
                {
                    continue;
                }
                for (int y = center.getY() - 2; y <= center.getY() + 24; y++)
                {
                    final BlockPos pos = new BlockPos(x, y, z);
                    final BlockState state = level.getBlockState(pos);
                    if (state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.LEAVES) || state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.FURNACE))
                    {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    /**
     * Places the block the way a player's hand does: the block is set, then the game's placement event is posted.
     */
    private static void place(final GameTestHelper helper, final BlockPos rel, final BlockState state, final Entity by)
    {
        helper.setBlock(rel, state);
        final BlockPos abs = helper.absolutePos(rel);
        final ServerLevel level = helper.getLevel();
        NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, abs), level.getBlockState(abs.below()), by));
    }

    private static DirectedOrder.Area area(final GameTestHelper helper, final int x0, final int z0, final int x1, final int z1)
    {
        final BlockPos a = helper.absolutePos(new BlockPos(x0, 1, z0));
        final BlockPos b = helper.absolutePos(new BlockPos(x1, 1, z1));
        return DirectedOrder.Area.of(a.getX(), a.getZ(), b.getX(), b.getZ());
    }

    /**
     * A flat slab of stone at y 0 under the rectangle, air above it up to y 4.
     */
    private static void ground(final GameTestHelper helper, final int x0, final int z0, final int x1, final int z1)
    {
        for (int x = x0; x <= x1; x++)
        {
            for (int z = z0; z <= z1; z++)
            {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
                for (int y = 1; y <= 4; y++)
                {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static int count(final GameTestHelper helper, final Block block, final int x0, final int z0, final int x1, final int z1, final int y)
    {
        int n = 0;
        for (int x = x0; x <= x1; x++)
        {
            for (int z = z0; z <= z1; z++)
            {
                n += helper.getBlockState(new BlockPos(x, y, z)).is(block) ? 1 : 0;
            }
        }
        return n;
    }

    private static IToken<?> ask(final IBuilding requester, final ItemStack stack)
    {
        return requester.createRequest(new Stack(stack, stack.getCount(), stack.getCount()), false);
    }

    private static int inHut(final IBuilding hut, final Item item)
    {
        return InventoryUtils.getItemCountInItemHandler(hut.getItemHandlerCap(), s -> s.is(item));
    }

    private static boolean asks(final IBuilding building, final Item item)
    {
        for (final IRequest<?> request : building.getOpenRequests(-1))
        {
            if (request.getRequest() instanceof IDeliverable deliverable && deliverable.matches(new ItemStack(item)))
            {
                return true;
            }
        }
        return false;
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
                Log.getLogger().info("WAVEB poll t={} {}", elapsed[0], failure.get());
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
        return "request " + (token == null ? "-" : f.describe(token)) + ", session " + (session == null ? "none" : session.getTaskId() + "/"
                 + (session instanceof LabourSession s ? s.phaseName() : session instanceof com.minecolonies.core.colony.labour.DirectedSession d ? d.phaseName() : "?"))
                 + ", claims " + colony.getLabourBoard().claims().size() + ", why " + (token == null ? "-" : colony.getLabourBoard().blockedWhy(token))
                 + ", carries " + LabourSession.inventoryOf(citizen);
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
        hall.setPackName(PACK);
        hall.setBlueprintPath("fundamentals/townhall1.blueprint");
        final IColony colony = IColonyManager.getInstance().createColony(level, hallPos, FakePlayerFactory.getMinecraft(level), name, PACK);
        helper.assertTrue(colony != null, "second colony not created");
        final IBuilding townHall = colony.getServerBuildingManager().addNewBuilding(hall, level);
        helper.assertTrue(townHall != null, "second town hall not registered");
        townHall.setBuildingLevel(1);
        return (Colony) colony;
    }

    // ------------------------------------------------------------------ quarry zones

    /**
     * Digging the ground is off (the default). Sand and stone lie inside a marked quarry zone and outside it; one sand and one stone inside the zone were placed by a
     * player (on record). Asked for sand and cobblestone, the labourer digs only the zone's natural blocks: everything outside and the placed blocks stay. Removing
     * the zone leaves no ground to dig.
     */
    public static void quarryDigsOnlyInsideTheZone(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "waveb quarry");
        final Colony colony = (Colony) f.colony;
        ground(helper, 29, 19, 36, 30);
        for (int x = 30; x <= 32; x++)
        {
            helper.setBlock(new BlockPos(x, 1, 21), Blocks.SAND.defaultBlockState());
            helper.setBlock(new BlockPos(x, 1, 22), Blocks.STONE.defaultBlockState());
            helper.setBlock(new BlockPos(x, 1, 27), Blocks.SAND.defaultBlockState());
            helper.setBlock(new BlockPos(x, 1, 28), Blocks.STONE.defaultBlockState());
        }
        place(helper, new BlockPos(33, 1, 21), Blocks.SAND.defaultBlockState(), f.player);
        place(helper, new BlockPos(33, 1, 22), Blocks.STONE.defaultBlockState(), f.player);
        helper.assertTrue(colony.getLabourData().placements().placed(helper.absolutePos(new BlockPos(33, 1, 21)).asLong()), "the placed sand is not on record");
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(!colony.getLabourBoard().sites().groundAllowed(), "digging the ground is allowed in this world");
            final QuarryZones.Marked marked = colony.getLabourBoard().markQuarry(area(helper, 30, 20, 35, 23));
            helper.assertTrue(marked.zone() != null, "the zone was refused: " + marked.refusal());
            helper.assertTrue(colony.getLabourBoard().markQuarry(area(helper, 34, 22, 36, 25)).refusal() == QuarryZones.Refusal.OVERLAP, "an overlapping zone was marked");
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            InventoryUtils.addItemStackToItemHandler(idle.getInventory(), new ItemStack(Items.WOODEN_PICKAXE));
            final IToken<?> sand = ask(f.builder, new ItemStack(Items.SAND, 3));
            final IToken<?> cobble = ask(f.builder, new ItemStack(Items.COBBLESTONE, 3));
            poll(helper, List.of(colony), 9000, () -> {
                helper.assertTrue(count(helper, Blocks.SAND, 30, 27, 32, 27, 1) == 3 && count(helper, Blocks.STONE, 30, 28, 32, 28, 1) == 3
                                    && count(helper, Blocks.STONE, 29, 26, 36, 30, 0) == 40, "ground outside the zone was dug");
                helper.assertTrue(helper.getBlockState(new BlockPos(33, 1, 21)).is(Blocks.SAND) && helper.getBlockState(new BlockPos(33, 1, 22)).is(Blocks.STONE),
                  "a placed block in the zone was dug");
                return inHut(f.builder, Items.SAND) >= 3 && inHut(f.builder, Items.COBBLESTONE) >= 3;
            }, () -> {
                helper.assertTrue(count(helper, Blocks.SAND, 30, 21, 32, 21, 1) == 0, "the zone's sand is still there");
                helper.assertTrue(colony.getLabourBoard().removeQuarry(marked.zone().id()) && colony.getLabourBoard().quarries().zones().isEmpty(), "remove");
                colony.getLabourBoard().invalidate();
                helper.assertTrue(!colony.getLabourBoard().sites().hasSurface(Items.SAND) && !colony.getLabourBoard().sites().hasSurface(Items.COBBLESTONE),
                  "ground is still in reach after the zone was removed");
                end();
                helper.succeed();
            }, () -> "sand " + describe(f, sand, idle) + " | cobble " + f.describe(cobble));
        });
    }

    /**
     * A directed dirt order whose area reaches half into a quarry zone: with digging off, the worker digs only the half in the zone.
     */
    public static void quarryAllowsADirectedOrder(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "waveb quarry directed");
        final Colony colony = (Colony) f.colony;
        ground(helper, 29, 19, 36, 24);
        for (int x = 30; x <= 35; x++)
        {
            for (int z = 20; z <= 23; z++)
            {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.DIRT.defaultBlockState());
            }
        }
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(colony.getLabourBoard().markQuarry(area(helper, 30, 20, 32, 23)).zone() != null, "the zone was refused");
            final ICitizenData digger = f.spawn(new BlockPos(14, 1, 16), false);
            final DirectedOrders orders = colony.getLabourBoard().directed();
            final DirectedOrders.Start start = orders.start(List.of(digger.getId()), DirectedOrder.Resource.DIRT, 4, area(helper, 30, 20, 35, 23), null);
            helper.assertTrue(start.order() != null, "refused: " + start.refusal());
            helper.assertTrue(!orders.groundOff(start.order()) && orders.groundOff(DirectedOrder.Resource.DIRT), "the order does not know of the zone");
            poll(helper, List.of(colony), 9000, () -> {
                helper.assertTrue(count(helper, Blocks.DIRT, 33, 20, 35, 23, 1) == 12, "dirt outside the zone was dug");
                return start.order().delivered() >= 4;
            }, () -> {
                helper.assertTrue(count(helper, Blocks.DIRT, 30, 20, 32, 23, 1) <= 8, "the zone's dirt is still there");
                orders.cancel(start.order().id());
                end();
                helper.succeed();
            }, () -> "order " + start.order().state() + "/" + start.order().why() + " delivered " + start.order().delivered() + ", " + describe(f, null, digger));
        });
    }

    /**
     * Two colonies in one world. B marks a zone in its own claim with sand in it; A's labourer is asked for sand and never digs B's zone (A's labour knows only A's
     * zones). Neither colony may mark a zone in the other's claim.
     */
    public static void quarryOfAnotherColonyIsIgnored(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("gatherRadius", 400);
        LabourSettings.override("postRange", 400);
        final RsFixture a = found(helper, "waveb quarry iso a");
        final Colony colonyA = (Colony) a.colony;
        final int dx = 240;
        final Colony colonyB = foundAt(helper, "waveb quarry iso b", dx);
        helper.runAfterDelay(100, () -> {
            for (int x = dx + 20; x <= dx + 23; x++)
            {
                helper.setBlock(new BlockPos(x, 1, 14), Blocks.SAND.defaultBlockState());
            }
            final QuarryZones.Marked inB = colonyB.getLabourBoard().markQuarry(area(helper, dx + 19, 12, dx + 24, 16));
            helper.assertTrue(inB.zone() != null, "B's zone was refused: " + inB.refusal());
            helper.assertTrue(colonyA.getLabourBoard().markQuarry(area(helper, dx + 19, 12, dx + 24, 16)).refusal() == QuarryZones.Refusal.AREA_OUTSIDE,
              "A marked a zone in B's claim");
            helper.assertTrue(colonyB.getLabourBoard().markQuarry(area(helper, 30, 20, 35, 23)).refusal() == QuarryZones.Refusal.AREA_OUTSIDE, "B marked a zone in A's claim");
            helper.assertTrue(!colonyA.getLabourBoard().sites().quarryAllows(helper.absolutePos(new BlockPos(dx + 20, 1, 14))), "A's labour may dig in B's zone");
            helper.assertTrue(colonyB.getLabourBoard().sites().quarryAllows(helper.absolutePos(new BlockPos(dx + 20, 1, 14))), "B's labour may not dig in its own zone");
            final ICitizenData idle = a.spawn(new BlockPos(14, 1, 16), false);
            final IToken<?> sand = ask(a.builder, new ItemStack(Items.SAND, 2));
            final long start = helper.getLevel().getGameTime();
            poll(helper, List.of(colonyA, colonyB), 3000, () -> {
                helper.assertTrue(count(helper, Blocks.SAND, dx + 20, 14, dx + 23, 14, 1) == 4, "sand of B's zone was dug");
                helper.assertTrue(!colonyA.getLabourBoard().sites().hasSurface(Items.SAND) && inHut(a.builder, Items.SAND) == 0, "A sees B's sand");
                return helper.getLevel().getGameTime() - start >= 1200;
            }, () -> {
                end();
                helper.succeed();
            }, () -> describe(a, sand, idle));
        });
    }

    // ------------------------------------------------------------------ glass

    /**
     * Glass is asked for. Sand lies in a quarry zone and an oak stands nearby (labour takes goods it makes itself from the world, not from the warehouse): the labourer
     * digs the sand, fells wood for fuel, smelts at the colony's furnace and hands in the glass.
     */
    public static void sandIsSmeltedToGlass(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "waveb glass");
        final Colony colony = (Colony) f.colony;
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.FURNACE.defaultBlockState());
        ground(helper, 29, 19, 36, 24);
        for (int x = 30; x <= 33; x++)
        {
            helper.setBlock(new BlockPos(x, 1, 21), Blocks.SAND.defaultBlockState());
        }
        LabourGameTests.tree(helper, 40, 30, 5);
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(colony.getLabourBoard().markQuarry(area(helper, 30, 20, 35, 23)).zone() != null, "the zone was refused");
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final IToken<?> token = ask(f.builder, new ItemStack(Items.GLASS, 2));
            poll(helper, List.of(colony), 9000, () -> inHut(f.builder, Items.GLASS) >= 2, () -> {
                end();
                helper.succeed();
            }, () -> describe(f, token, idle));
        });
    }

    // ------------------------------------------------------------------ the early pace

    /**
     * A young colony (fewer built buildings than labourers.earlyBuildings): the builder's own request is fetched after the early grace, not the configured one (no
     * test override of the grace).
     */
    public static void earlyGraceLetsTheBuilderFetchSoon(final GameTestHelper helper)
    {
        begin();
        // (the colony spawns citizens of its own: only the builder works in this test)
        LabourSettings.override("total", 0);
        final RsFixture f = found(helper, "waveb early");
        final Colony colony = (Colony) f.colony;
        LabourGameTests.tree(helper, 34, 20, 5);
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(LabourSettings.early(colony) && LabourSettings.supplyGraceTicks(colony) == LabourSettings.earlyGraceTicks()
                                && LabourSettings.supplyGraceTicks() > LabourSettings.earlyGraceTicks(), "not the early pace: early " + LabourSettings.early(colony)
                                                                                                     + " grace " + LabourSettings.supplyGraceTicks(colony) + "/" + LabourSettings.supplyGraceTicks());
            final ICitizenData builder = f.spawn(new BlockPos(12, 1, 8), false);
            helper.assertTrue(f.builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(builder), "builder not assigned");
            LabourGameTests.planksFloor(helper, colony, f.builder, 22, 12, 4);
            final long[] waited = {-1L};
            poll(helper, List.of(colony), 6000, () -> {
                for (final LabourClaim claim : colony.getLabourBoard().claims())
                {
                    if (claim.source() == LabourClaim.Source.SELF && claim.request() != null && waited[0] < 0)
                    {
                        waited[0] = helper.getLevel().getGameTime() - colony.getLabourBoard().waitingSince(claim.request());
                    }
                }
                return waited[0] >= 0;
            }, () -> {
                helper.assertTrue(waited[0] >= LabourSettings.earlyGraceTicks() && waited[0] < LabourSettings.supplyGraceTicks(),
                  "the builder's request waited " + waited[0] + " ticks before he fetched it");
                end();
                helper.succeed();
            }, () -> "builder inv " + LabourSession.inventoryOf(builder) + " claims " + colony.getLabourBoard().claims().size());
        });
    }

    // ------------------------------------------------------------------ a hut on credit

    /**
     * The town hall owes a builder's hut item (a hut was placed on credit). It asks for one; an oak stands in reach and stone lies in a quarry zone, so labour fells wood,
     * digs a cobblestone and crafts planks, sticks, the build tool, a door and the hut at the colony's table, and hands it in. The debt takes the hut item out again and
     * the request ends: the colony paid with real goods.
     */
    public static void owedHutItemIsMadeByLabour(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "waveb hut debt");
        final Colony colony = (Colony) f.colony;
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        ground(helper, 29, 19, 36, 24);
        for (int x = 30; x <= 32; x++)
        {
            helper.setBlock(new BlockPos(x, 1, 21), Blocks.STONE.defaultBlockState());
        }
        LabourGameTests.tree(helper, 40, 30, 6);
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(colony.getLabourBoard().markQuarry(area(helper, 30, 20, 35, 23)).zone() != null, "the zone was refused");
            final IBuilding hall = colony.getServerBuildingManager().getTownHall();
            final Item hut = ModBlocks.blockHutBuilder.asItem();
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            InventoryUtils.addItemStackToItemHandler(idle.getInventory(), new ItemStack(Items.WOODEN_PICKAXE));
            helper.assertTrue(colony.getLabourBoard().oweHut(hall, hut), "the debt was refused");
            helper.assertTrue(asks(hall, hut) && colony.getLabourData().hutDebts().debts().size() == 1, "the town hall does not ask for the hut item");
            final boolean[] claimed = {false};
            poll(helper, List.of(colony), 12000, () -> {
                claimed[0] |= !colony.getLabourBoard().claims().isEmpty();
                return colony.getLabourData().hutDebts().debts().isEmpty();
            }, () -> {
                helper.assertTrue(claimed[0], "nobody worked on the hut item");
                helper.assertTrue(inHut(hall, hut) == 0 && !asks(hall, hut), "the hut item stayed in the town hall or it still asks: " + inHut(hall, hut));
                helper.assertTrue(!helper.getBlockState(new BlockPos(40, 1, 30)).is(Blocks.OAK_LOG), "the hut item was not made from the tree's wood");
                end();
                helper.succeed();
            }, () -> "debts " + colony.getLabourData().hutDebts().debts().size() + ", asks " + asks(hall, hut) + ", " + describe(f, null, idle));
        });
    }

    // ------------------------------------------------------------------ review C1: debts cannot be laundered

    private static int heldOrDropped(final GameTestHelper helper, final RsFixture f, final Item item, final BlockPos rel)
    {
        int n = 0;
        for (int slot = 0; slot < f.player.getInventory().getContainerSize(); slot++)
        {
            n += f.player.getInventory().getItem(slot).is(item) ? f.player.getInventory().getItem(slot).getCount() : 0;
        }
        final BlockPos abs = helper.absolutePos(rel);
        for (final net.minecraft.world.entity.item.ItemEntity entity : f.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
          new net.minecraft.world.phys.AABB(abs).inflate(16)))
        {
            n += entity.getItem().is(item) ? entity.getItem().getCount() : 0;
        }
        return n;
    }

    /**
     * A hut placed on credit (it owes its hut item). The officer cannot break it (the event is cancelled: no block gone, no hut item dropped), cannot pick it up and
     * cannot upgrade it; nobody got a hut item. When the hut goes another way (here taken out of the colony directly), the colony still owes the item: the town hall
     * asks, and a hut item stored in the warehouse pays the debt.
     */
    public static void owedHutCannotBeLaundered(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "waveb hut owed");
        final Colony colony = (Colony) f.colony;
        final BlockPos rel = new BlockPos(8, 1, 24);
        final IBuilding hut = f.place(ModBlocks.blockHutLumberjack, rel, "fundamentals/lumberjack1.blueprint");
        final Item item = ModBlocks.blockHutLumberjack.asItem();
        hut.setBuildingLevel(1);
        f.player.getInventory().clearContent();
        helper.assertTrue(colony.getLabourBoard().oweHut(hut, item), "the debt was refused");
        helper.assertTrue(com.minecolonies.core.colony.labour.HutDebts.owes(hut), "the hut does not owe");
        // break
        final boolean broken = f.player.gameMode.destroyBlock(hut.getID());
        helper.assertTrue(!broken && colony.getServerBuildingManager().getBuilding(hut.getID()) != null && helper.getLevel().getBlockState(hut.getID()).is(ModBlocks.blockHutLumberjack),
          "the owing hut was broken: " + broken);
        // pick up
        hut.pickUp(f.player);
        helper.assertTrue(colony.getServerBuildingManager().getBuilding(hut.getID()) != null, "the owing hut was picked up");
        // upgrade
        final int orders = colony.getWorkManager().getWorkOrders().size();
        hut.requestUpgrade(f.player, BlockPos.ZERO);
        helper.assertTrue(colony.getWorkManager().getWorkOrders().size() == orders, "the owing hut got an upgrade order");
        helper.assertTrue(heldOrDropped(helper, f, item, rel) == 0, "a hut item reached the player or the ground");
        // gone another way: the debt stays with the colony
        hut.destroy();
        helper.setBlock(rel, Blocks.AIR.defaultBlockState());
        helper.runAfterDelay(20, () -> poll(helper, List.of(colony), 2000, () -> {
            final var debts = colony.getLabourData().hutDebts().debts();
            helper.assertTrue(debts.size() == 1, "the debt was forgiven when the hut went: " + debts);
            return debts.get(0).gone() && asks(colony.getServerBuildingManager().getTownHall(), item);
        }, () -> {
            f.stock(0, new ItemStack(item));
            poll(helper, List.of(colony), 2000, () -> colony.getLabourData().hutDebts().isEmpty(), () -> {
                helper.assertTrue(f.physical(s -> s.is(item)) == 0, "the stored hut item was not taken to pay the debt");
                end();
                helper.succeed();
            }, () -> "debts " + colony.getLabourData().hutDebts().debts());
        }, () -> "debts " + colony.getLabourData().hutDebts().debts()));
    }

    /**
     * An owed hut item survives a save and load of the colony's labour record: the same debt, and the hut keeps asking for it.
     */
    public static void owedHutSurvivesAReload(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "waveb hut reload");
        final Colony colony = (Colony) f.colony;
        final IBuilding hall = colony.getServerBuildingManager().getTownHall();
        final Item item = ModBlocks.blockHutBuilder.asItem();
        helper.assertTrue(colony.getLabourBoard().oweHut(hall, item), "the debt was refused");
        final var before = colony.getLabourData().hutDebts().debts();
        final net.minecraft.nbt.CompoundTag saved = colony.getLabourData().write();
        final com.minecolonies.core.colony.labour.LabourData fresh = new com.minecolonies.core.colony.labour.LabourData();
        fresh.read(saved);
        helper.assertTrue(fresh.hutDebts().debts().equals(before), "a fresh load reads other debts: " + fresh.hutDebts().debts());
        colony.getLabourData().read(saved);
        helper.assertTrue(colony.getLabourData().hutDebts().owes(hall.getID()) && colony.getLabourData().hutDebts().count() == 1, "the debt was lost by the reload");
        poll(helper, List.of(colony), 400, () -> asks(hall, item) && colony.getLabourData().hutDebts().owes(hall.getID()), () -> {
            end();
            helper.succeed();
        }, () -> "debts " + colony.getLabourData().hutDebts().debts() + " asks " + asks(hall, item));
    }

    // ------------------------------------------------------------------ review C1: depth per column, roads in zones

    /**
     * Uneven ground: a 4x3 zone of dirt on stone with one pit column (its dirt missing), quarry depth 1. Every column is measured from its own marked top: the dirt
     * is taken, the pit's stone is taken, the stone under the dirt columns stays (with the old rule, measured from the pit, it went too).
     */
    public static void aPitDoesNotDeepenTheQuarry(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("quarryDepth", 1);
        final RsFixture f = found(helper, "waveb quarry pit");
        final Colony colony = (Colony) f.colony;
        ground(helper, 29, 19, 35, 24);
        for (int x = 30; x <= 33; x++)
        {
            for (int z = 20; z <= 22; z++)
            {
                if (x != 31 || z != 21)
                {
                    helper.setBlock(new BlockPos(x, 1, z), Blocks.DIRT.defaultBlockState());
                }
            }
        }
        // (solid ground under the pit's stone, so that it may be dug)
        helper.setBlock(new BlockPos(31, -1, 21), Blocks.STONE.defaultBlockState());
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(colony.getLabourBoard().markQuarry(area(helper, 30, 20, 33, 22)).zone() != null, "the zone was refused");
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            InventoryUtils.addItemStackToItemHandler(idle.getInventory(), new ItemStack(Items.WOODEN_PICKAXE));
            final IToken<?> dirt = ask(f.builder, new ItemStack(Items.DIRT, 11));
            final IToken<?> cobble = ask(f.builder, new ItemStack(Items.COBBLESTONE, 2));
            final long[] doneAt = {-1L};
            poll(helper, List.of(colony), 9000, () -> {
                helper.assertTrue(count(helper, Blocks.STONE, 30, 20, 33, 22, 0) >= 11, "stone under a dirt column was dug: "
                                                                                             + count(helper, Blocks.STONE, 30, 20, 33, 22, 0));
                if (doneAt[0] < 0)
                {
                    if (inHut(f.builder, Items.DIRT) >= 11 && inHut(f.builder, Items.COBBLESTONE) >= 1)
                    {
                        doneAt[0] = helper.getLevel().getGameTime();
                    }
                    return false;
                }
                return helper.getLevel().getGameTime() - doneAt[0] >= 800;
            }, () -> {
                helper.assertTrue(!helper.getBlockState(new BlockPos(31, 0, 21)).is(Blocks.STONE) && count(helper, Blocks.STONE, 30, 20, 33, 22, 0) == 11,
                  "not exactly the pit's stone was dug: stone left " + count(helper, Blocks.STONE, 30, 20, 33, 22, 0));
                helper.assertTrue(inHut(f.builder, Items.COBBLESTONE) == 1, "more cobblestone than the pit holds: " + inHut(f.builder, Items.COBBLESTONE));
                end();
                helper.succeed();
            }, () -> "dirt " + describe(f, dirt, idle) + " | cobble " + f.describe(cobble));
        });
    }

    /**
     * A road (a decoration work order) is queued through a quarry zone: labour digs the zone's dirt only outside the order's box.
     */
    public static void aRoadInAZoneIsLeftAlone(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "waveb quarry road");
        final Colony colony = (Colony) f.colony;
        ground(helper, 29, 19, 36, 24);
        for (int x = 30; x <= 35; x++)
        {
            for (int z = 20; z <= 23; z++)
            {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.DIRT.defaultBlockState());
            }
        }
        helper.runAfterDelay(100, () -> {
            LabourGameTests.planksFloor(helper, colony, f.builder, 33, 20, 3);
            helper.assertTrue(colony.getLabourBoard().markQuarry(area(helper, 30, 20, 35, 23)).zone() != null, "the zone was refused");
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final IToken<?> dirt = ask(f.builder, new ItemStack(Items.DIRT, 6));
            poll(helper, List.of(colony), 9000, () -> {
                helper.assertTrue(count(helper, Blocks.DIRT, 32, 19, 35, 23, 1) == 16, "dirt of the road's box was dug: " + count(helper, Blocks.DIRT, 32, 19, 35, 23, 1));
                return inHut(f.builder, Items.DIRT) >= 6;
            }, () -> {
                end();
                helper.succeed();
            }, () -> describe(f, dirt, idle));
        });
    }

    // ------------------------------------------------------------------ registration

    static final String[] NAMES = {"labour_quarry_auto", "labour_quarry_directed", "labour_quarry_isolated", "labour_glass", "labour_early_grace", "labour_hut_debt",
      "labour_hut_owed_rules", "labour_hut_debt_reload", "labour_quarry_pit", "labour_quarry_road"};

    static java.util.function.Consumer<GameTestHelper> test(final String name)
    {
        return switch (name)
        {
            case "labour_quarry_auto" -> WaveBLabourGameTests::quarryDigsOnlyInsideTheZone;
            case "labour_quarry_directed" -> WaveBLabourGameTests::quarryAllowsADirectedOrder;
            case "labour_quarry_isolated" -> WaveBLabourGameTests::quarryOfAnotherColonyIsIgnored;
            case "labour_glass" -> WaveBLabourGameTests::sandIsSmeltedToGlass;
            case "labour_early_grace" -> WaveBLabourGameTests::earlyGraceLetsTheBuilderFetchSoon;
            case "labour_hut_owed_rules" -> WaveBLabourGameTests::owedHutCannotBeLaundered;
            case "labour_hut_debt_reload" -> WaveBLabourGameTests::owedHutSurvivesAReload;
            case "labour_quarry_pit" -> WaveBLabourGameTests::aPitDoesNotDeepenTheQuarry;
            case "labour_quarry_road" -> WaveBLabourGameTests::aRoadInAZoneIsLeftAlone;
            default -> WaveBLabourGameTests::owedHutItemIsMadeByLabour;
        };
    }
}
