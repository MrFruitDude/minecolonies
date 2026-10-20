package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.WaitReason;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;
import com.minecolonies.core.colony.idle.IdleCitizenArbiter;
import com.minecolonies.core.colony.idle.IdleCitizenSettings;
import com.minecolonies.core.colony.labour.LabourBoard;
import com.minecolonies.core.colony.labour.LabourClaim;
import com.minecolonies.core.colony.labour.LabourSession;
import com.minecolonies.core.colony.labour.LabourSettings;
import com.minecolonies.core.colony.requestsystem.reservation.Reservation;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationReason;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The labourers (LABOURERS.md): unemployed citizens gather and craft the basic goods that no building serves, at a plain crafting table of the colony, and hand them in
 * at the hut that asked. Real colonies, real citizens with their AI; the colonies are ticked by the test (a test colony has no player and so no tick of its own).
 */
final class LabourGameTests
{
    private static final String PACK = "Minecolonies Original";

    private LabourGameTests()
    {
    }

    // ------------------------------------------------------------------ scene

    private static void begin()
    {
        com.minecolonies.core.colony.labour.LabourStats.debug = true;
        LabourSettings.clearOverrides();
        LabourSettings.override("workScale", 8.0D);
        LabourSettings.override("keepTrees", 0);
        // (a test checks one behaviour at a time: the warehouse stock goals are switched on by the test that checks them)
        LabourSettings.override("stockpile", false);
        IdleCitizenSettings.overrideForTests(null, null, null);
    }

    /**
     * The fixture colony, with the automatic hiring switched off: the buildings of a test have no worker, and an unemployed citizen who is hired by one is no labourer any more.
     */
    private static RsFixture found(final GameTestHelper helper, final String name, final int racks)
    {
        final RsFixture f = RsFixture.found(helper, name, racks);
        clearTrees(helper);
        final var hiring = f.colony.getSettings().getSetting(com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall.AUTO_HIRING_MODE);
        if (hiring.getValue())
        {
            hiring.trigger();
        }
        return f;
    }

    private static void end()
    {
        com.minecolonies.core.colony.labour.LabourStats.debug = false;
        LabourSettings.clearOverrides();
    }

    /**
     * An oak tree a labourer may fell: dirt below, a trunk, leaves that nobody placed.
     */
    private static void tree(final GameTestHelper helper, final int x, final int z, final int height)
    {
        helper.setBlock(new BlockPos(x, 0, z), Blocks.DIRT.defaultBlockState());
        for (int y = 1; y <= height; y++)
        {
            helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG.defaultBlockState());
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

    private static int logsLeft(final GameTestHelper helper, final int x, final int z, final int height)
    {
        int n = 0;
        for (int y = 1; y <= height; y++)
        {
            if (helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.OAK_LOG))
            {
                n++;
            }
        }
        return n;
    }

    /**
     * Takes away the trees, tables and furnaces that earlier tests left within the labourers' reach (the tests of a run stand side by side in one world).
     */
    private static void clearTrees(final GameTestHelper helper)
    {
        final BlockPos center = helper.absolutePos(new BlockPos(20, 0, 16));
        final ServerLevel level = helper.getLevel();
        final int radius = 70;
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++)
        {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++)
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

    private static IToken<?> ask(final IBuilding requester, final ItemStack stack)
    {
        return requester.createRequest(new Stack(stack, stack.getCount(), stack.getCount()), false);
    }

    private static int inHut(final IBuilding hut, final Item item)
    {
        return InventoryUtils.getItemCountInItemHandler(hut.getItemHandlerCap(), s -> s.is(item));
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

    /**
     * Calls {@code check} every 20 ticks, after ticking the colonies and setting the day, until it returns true; fails with the message after {@code maxTicks}.
     */
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
                Log.getLogger().info("LABOUR poll t={} {}", elapsed[0], failure.get());
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
        return "request " + f.describe(token) + ", session " + (session == null ? "none" : session.getTaskId() + "/" + (session instanceof LabourSession s ? s.phaseName() : "?"))
                 + ", claims " + colony.getLabourBoard().claims().size() + ", ledger " + labourReservations(f).size()
                 + ", why " + colony.getLabourBoard().blockedWhy(token) + ", citizen at " + citizen.getEntity().map(e -> e.blockPosition().toShortString()).orElse("-")
                 + " inv " + LabourSession.inventoryOf(citizen);
    }

    // ------------------------------------------------------------------ the headline

    /**
     * A builder's hut asks for a wooden pickaxe; nobody in the colony makes tools (no blacksmith, no sawmill). A citizen without a job fells a tree, makes planks and
     * sticks in his pockets, crafts the pickaxe at the plain crafting table, and puts it into the hut: the request is served by the building's own resolver and nothing
     * was added to the request system on the way.
     */
    public static void craftsWoodenTools(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "labour tools", 2);
        final Colony colony = (Colony) f.colony;
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        tree(helper, 34, 20, 5);
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final IToken<?> token = ask(f.builder, new ItemStack(Items.WOODEN_PICKAXE));
            helper.assertTrue(f.isWithPlayer(token) || f.isRetrying(token), "the request is not parked at the player or the retrying resolver: " + f.describe(token));
            final int requests = f.standard().getRequestIdentitiesDataStore().getIdentities().size();
            poll(helper, List.of(colony), 9000, () -> inHut(f.builder, Items.WOODEN_PICKAXE) >= 1 && !f.isWithPlayer(token) && !f.isRetrying(token), () -> {
                helper.assertTrue(f.standard().getRequestIdentitiesDataStore().getIdentities().size() <= requests,
                  "labour added requests to the request system: " + f.standard().getRequestIdentitiesDataStore().getIdentities().size() + " > " + requests);
                helper.assertTrue(logsLeft(helper, 34, 20, 5) == 0, "the tree is still there");
                helper.assertTrue(inHut(f.builder, Items.WOODEN_PICKAXE) == 1, "pickaxes in the hut: " + inHut(f.builder, Items.WOODEN_PICKAXE));
                end();
                helper.succeed();
            }, () -> describe(f, token, idle) + ", logs left " + logsLeft(helper, 34, 20, 5));
        });
    }

    // ------------------------------------------------------------------ covered goods

    /**
     * A lumberjack hut covers the logs: a request for logs is not labour's, and neither is a request for planks, which would need a tree to be felled: no tree falls, and
     * the request says that labour is blocked (waiting for the specialist).
     */
    public static void coveredIsLeftAlone(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "labour covered", 2);
        final Colony colony = (Colony) f.colony;
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        f.place(ModBlocks.blockHutLumberjack, new BlockPos(8, 1, 24), "fundamentals/lumberjack1.blueprint");
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        tree(helper, 34, 20, 5);
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final IToken<?> logs = ask(f.builder, new ItemStack(Items.OAK_LOG, 4));
            final IToken<?> planks = ask(f.builder, new ItemStack(Items.OAK_PLANKS, 4));
            final int[] ticks = {0};
            poll(helper, List.of(colony), 2400, () -> {
                ticks[0] += 20;
                helper.assertTrue(colony.getLabourBoard().claims().isEmpty(), "a claim on a covered good: " + describe(f, planks, idle));
                helper.assertTrue(logsLeft(helper, 34, 20, 5) == 5, "a tree was felled although the lumberjack covers logs");
                helper.assertTrue(colony.getLabourBoard().candidate(logs) == null, "the request for logs is a candidate of labour");
                return ticks[0] >= 1800;
            }, () -> {
                helper.assertTrue(colony.getLabourBoard().blockedWhy(planks) != null, "labour does not say why it is blocked on the planks");
                helper.assertTrue(f.request(planks).getWaitReason() == WaitReason.LABOUR_BLOCKED, "the planks request shows " + f.request(planks).getWaitReason());
                end();
                helper.succeed();
            }, () -> describe(f, planks, idle));
        });
    }

    // ------------------------------------------------------------------ the job ends it

    /**
     * Mid-task the citizen gets a job: his claim and its ledger entries are gone within a second and the job's AI takes over.
     */
    public static void stopsWhenGivenAJob(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "labour job", 2);
        final Colony colony = (Colony) f.colony;
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        tree(helper, 34, 20, 5);
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final IToken<?> token = ask(f.builder, new ItemStack(Items.WOODEN_PICKAXE));
            final boolean[] hired = {false};
            poll(helper, List.of(colony), 4000, () -> {
                if (!hired[0])
                {
                    final var session = IdleCitizenArbiter.sessionOf(idle);
                    if (session != null && colony.getLabourBoard().claimOf(idle.getId()) != null && logsLeft(helper, 34, 20, 5) < 5)
                    {
                        helper.assertTrue(f.builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(idle), "builder not assigned");
                        hired[0] = true;
                        IdleCitizenArbiter.tick(colony);
                        helper.assertTrue(IdleCitizenArbiter.sessionOf(idle) == null, "the session survived the job");
                        helper.assertTrue(colony.getLabourBoard().claimOf(idle.getId()) == null, "the claim survived the job");
                        helper.assertTrue(labourReservations(f).isEmpty(), "ledger entries of the claim are left: " + labourReservations(f));
                        return false;
                    }
                    return false;
                }
                // he stays a builder: no new claim, no session
                helper.assertTrue(IdleCitizenArbiter.sessionOf(idle) == null && colony.getLabourBoard().claimOf(idle.getId()) == null, "a citizen with a job labours again");
                return true;
            }, () -> {
                end();
                helper.succeed();
            }, () -> describe(f, token, idle));
        });
    }

    // ------------------------------------------------------------------ the setting

    /**
     * The colony setting off: nothing happens (no claim, no tree falls); a labourer at work stops within a second.
     */
    public static void settingOff(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "labour setting", 2);
        final Colony colony = (Colony) f.colony;
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        tree(helper, 34, 20, 5);
        colony.getSettings().updateSetting(BuildingTownHall.LABOUR, new BoolSetting(false), null);
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final IToken<?> token = ask(f.builder, new ItemStack(Items.WOODEN_PICKAXE));
            final int[] ticks = {0};
            final boolean[] switchedOn = {false};
            poll(helper, List.of(colony), 5000, () -> {
                ticks[0] += 20;
                if (!switchedOn[0])
                {
                    helper.assertTrue(colony.getLabourBoard().claims().isEmpty() && IdleCitizenArbiter.sessionOf(idle) == null, "labour with the setting off: " + describe(f, token, idle));
                    helper.assertTrue(logsLeft(helper, 34, 20, 5) == 5, "a tree fell with the setting off");
                    if (ticks[0] >= 1200)
                    {
                        colony.getSettings().updateSetting(BuildingTownHall.LABOUR, new BoolSetting(true), null);
                        switchedOn[0] = true;
                        ticks[0] = 0;
                    }
                    return false;
                }
                if (IdleCitizenArbiter.sessionOf(idle) != null && colony.getLabourBoard().claimOf(idle.getId()) != null)
                {
                    colony.getSettings().updateSetting(BuildingTownHall.LABOUR, new BoolSetting(false), null);
                    IdleCitizenArbiter.tick(colony);
                    helper.assertTrue(IdleCitizenArbiter.sessionOf(idle) == null && colony.getLabourBoard().claimOf(idle.getId()) == null, "the session survived the switch: " + describe(f, token, idle));
                    return true;
                }
                return false;
            }, () -> {
                end();
                helper.succeed();
            }, () -> describe(f, token, idle));
        });
    }

    // ------------------------------------------------------------------ colonies are separate

    /**
     * Two colonies in one world: the table and the tree stand inside the other colony's claim, which one colony's labourers cannot see, though they are within their range.
     * The other colony's own labourer works on its own request with its own table and tree.
     */
    public static void coloniesAreIsolated(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("gatherRadius", 400);
        LabourSettings.override("postRange", 400);
        final RsFixture a = found(helper, "labour iso a", 2);
        final Colony colonyA = (Colony) a.colony;
        final int dx = 240;
        final Colony colonyB = foundAt(helper, "labour iso b", dx);
        forceArea(a.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(dx + 48, 1, 32)));
        helper.runAfterDelay(100, () -> {
            final IBuilding hutB = MinecoloniesGameTests.placeProductionBuilding(helper, colonyB, ModBlocks.blockHutBuilder, new BlockPos(dx + 8, 1, 2), "fundamentals/builder1.blueprint");
            // inside B's claim only: a table and a tree next to B's town hall
            helper.setBlock(new BlockPos(dx + 12, 1, 14), Blocks.CRAFTING_TABLE.defaultBlockState());
            tree(helper, dx + 20, 14, 5);
            helper.runAfterDelay(100, () -> {
                final ICitizenData idleA = a.spawn(new BlockPos(14, 1, 16), false);
                final ICitizenData idleB = colonyB.getCitizenManager().spawnOrCreateCivilian(null, a.level, List.of(helper.absolutePos(new BlockPos(dx + 14, 1, 16))), true);
                helper.assertTrue(idleB != null && idleB.getEntity().isPresent(), "citizen of B did not spawn");
                final IToken<?> askA = ask(a.builder, new ItemStack(Items.WOODEN_PICKAXE));
                final IToken<?> askB = ask(hutB, new ItemStack(Items.WOODEN_PICKAXE));
                helper.assertTrue(colonyA.getLabourBoard().sites().ownedBy(helper.absolutePos(new BlockPos(dx + 12, 1, 14))) == false, "A owns B's table");
                helper.assertTrue(colonyB.getLabourBoard().sites().ownedBy(helper.absolutePos(new BlockPos(dx + 12, 1, 14))), "B does not own its own table");
                poll(helper, List.of(colonyA, colonyB), 9000, () -> {
                    helper.assertTrue(!colonyA.getLabourBoard().sites().hasTable() && !colonyA.getLabourBoard().sites().hasTree(Items.OAK_LOG),
                      "A sees B's table or tree: table " + colonyA.getLabourBoard().sites().hasTable());
                    helper.assertTrue(colonyA.getLabourBoard().claims().isEmpty(), "A's labourer found a plan with nothing of its own in reach");
                    helper.assertTrue(inHut(a.builder, Items.WOODEN_PICKAXE) == 0, "A got a pickaxe made from B's table");
                    return inHut(hutB, Items.WOODEN_PICKAXE) >= 1;
                }, () -> {
                    helper.assertTrue(colonyA.getLabourBoard().blockedWhy(askA) != null, "A shows no reason");
                    end();
                    helper.succeed();
                }, () -> "A: " + describe(a, askA, idleA) + " | B inv " + LabourSession.inventoryOf(idleB) + " claims " + colonyB.getLabourBoard().claims().size());
            });
        });
    }

    // ------------------------------------------------------------------ the idle builder

    private static com.minecolonies.core.colony.workorders.WorkOrderDecoration planksFloor(final GameTestHelper helper, final Colony colony, final IBuilding hut, final int x,
      final int z, final int size)
    {
        final com.minecolonies.core.colony.workorders.WorkOrderDecoration order = com.minecolonies.core.colony.workorders.WorkOrderDecoration.create(
          com.minecolonies.api.colony.workorders.WorkOrderType.BUILD, PACK, "gametest/labour-floor.blueprint", "gametest.labour-floor", helper.absolutePos(new BlockPos(x, 1, z)),
          com.ldtteam.structurize.util.RotationMirror.NONE, 0);
        final com.ldtteam.structurize.blueprints.v1.Blueprint blueprint = new com.ldtteam.structurize.blueprints.v1.Blueprint((short) size, (short) 1, (short) size)
                                                                            .setName("labour-floor").setFileName("labour-floor").setPackName(PACK);
        for (int dx = 0; dx < size; dx++)
        {
            for (int dz = 0; dz < size; dz++)
            {
                blueprint.addBlockState(new BlockPos(dx, 0, dz), Blocks.OAK_PLANKS.defaultBlockState());
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
     * The builder leads an order of oak planks and the colony has none, nobody makes them (no sawmill, no lumberjack). After the grace time he fetches the wood himself:
     * he fells the tree, makes planks in his pockets, brings them to HIS hut and goes back to building. He never takes a request of another hut (the town hall asks for
     * planks too), and every claim he holds is for his own hut.
     */
    public static void builderFetchesOwnBasics(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("graceTicks", 200);
        // (the colony spawns citizens of its own: only the builder works in this test)
        LabourSettings.override("total", 0);
        final RsFixture f = found(helper, "labour builder", 2);
        final Colony colony = (Colony) f.colony;
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        tree(helper, 34, 20, 5);
        helper.runAfterDelay(100, () -> {
            final ICitizenData builder = f.spawn(new BlockPos(12, 1, 8), false);
            helper.assertTrue(f.builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(builder), "builder not assigned");
            final int size = 4;
            planksFloor(helper, colony, f.builder, 22, 12, size);
            final IToken<?> others = colony.getServerBuildingManager().getTownHall().createRequest(new Stack(new ItemStack(Items.OAK_PLANKS, 4), 4, 4), false);
            final boolean[] fetched = {false};
            poll(helper, List.of(colony), 16000, () -> {
                for (final LabourClaim claim : colony.getLabourBoard().claims())
                {
                    helper.assertTrue(claim.source() == LabourClaim.Source.SELF && claim.destination().equals(f.builder.getID()),
                      "a claim of another kind or for another hut: " + claim.source() + " " + claim.destination());
                    fetched[0] = true;
                }
                int planks = 0;
                for (int dx = 0; dx < size; dx++)
                {
                    for (int dz = 0; dz < size; dz++)
                    {
                        planks += helper.getBlockState(new BlockPos(22 + dx, 1, 12 + dz)).is(Blocks.OAK_PLANKS) ? 1 : 0;
                    }
                }
                return fetched[0] && planks == size * size;
            }, () -> {
                helper.assertTrue(f.isWithPlayer(others) || f.isRetrying(others), "the request of the town hall was served by the builder: " + f.describe(others));
                end();
                helper.succeed();
            }, () -> "builder inv " + LabourSession.inventoryOf(builder) + " claims " + colony.getLabourBoard().claims().size() + " logs left " + logsLeft(helper, 34, 20, 5)
                       + " ai " + (builder.getJob() == null ? "no job" : builder.getJob().getWorkerAI() == null ? "no ai" : builder.getJob().getWorkerAI().getState())
                       + " citizen " + builder.getEntity().map(e -> e.blockPosition() + " alive " + e.isAlive() + " "
                                                                       + (e instanceof com.minecolonies.core.entity.citizen.EntityCitizen c ? c.getCitizenAI().getState() : "?")).orElse("no entity")
                       + " time " + f.level.getGameTime() % 24000);
        });
    }

    /**
     * The builder whose order has three episodes behind it that brought nothing is not given up on (the old limit stopped him for good): he waits the bounded back-off,
     * makes no claim before it, and then fetches the wood and builds.
     */
    public static void builderTriesAgainAfterEmptyEpisodes(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("graceTicks", 200);
        LabourSettings.override("total", 0);
        final RsFixture f = found(helper, "labour builder retry", 2);
        final Colony colony = (Colony) f.colony;
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        tree(helper, 34, 20, 5);
        helper.runAfterDelay(100, () -> {
            final ICitizenData builder = f.spawn(new BlockPos(12, 1, 8), false);
            helper.assertTrue(f.builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(builder), "builder not assigned");
            final int size = 2;
            final var order = planksFloor(helper, colony, f.builder, 22, 12, size);
            for (int i = 0; i < 3; i++)
            {
                colony.getLabourBoard().selfSupplyEnded(order.getID(), com.minecolonies.core.colony.labour.LabourSelfSupply.Outcome.FAILED);
            }
            final long retryAt = colony.getLabourBoard().selfSupplyRetryAt(order.getID());
            helper.assertTrue(retryAt > f.level.getGameTime(), "no back-off after three empty episodes");
            helper.assertTrue(retryAt - f.level.getGameTime() <= com.minecolonies.core.colony.labour.LabourSelfSupply.MAX_WAIT, "back-off beyond its bound: " + retryAt);
            final boolean[] fetched = {false};
            poll(helper, List.of(colony), 19000, () -> {
                for (final LabourClaim claim : colony.getLabourBoard().claims())
                {
                    helper.assertTrue(claim.source() == LabourClaim.Source.SELF && claim.destination().equals(f.builder.getID()), "a claim of another kind: " + claim.source());
                    helper.assertTrue(f.level.getGameTime() >= retryAt, "claimed during the back-off at " + f.level.getGameTime() + " < " + retryAt);
                    fetched[0] = true;
                }
                return fetched[0] && floorPlaced(helper, 22, 12, size);
            }, () -> {
                end();
                helper.succeed();
            }, () -> "builder inv " + LabourSession.inventoryOf(builder) + " claims " + colony.getLabourBoard().claims().size() + " retry at " + retryAt + " now "
                       + f.level.getGameTime() + " ai " + (builder.getJob() == null || builder.getJob().getWorkerAI() == null ? "-" : builder.getJob().getWorkerAI().getState()));
        });
    }

    /**
     * The order is cancelled while the builder fetches for it: his session ends at once, the claim and its reservation go, he does not start again for the gone order,
     * and the wood he took is all still there (in his pockets, the hut, or as planks made from it): nothing is made twice or lost.
     */
    public static void builderLetsGoWhenTheOrderEnds(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("graceTicks", 200);
        LabourSettings.override("total", 0);
        final RsFixture f = found(helper, "labour builder order gone", 2);
        final Colony colony = (Colony) f.colony;
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        tree(helper, 34, 20, 5);
        helper.runAfterDelay(100, () -> {
            final ICitizenData builder = f.spawn(new BlockPos(12, 1, 8), false);
            helper.assertTrue(f.builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(builder), "builder not assigned");
            final var order = planksFloor(helper, colony, f.builder, 22, 12, 4);
            final long[] cancelledAt = {-1L};
            poll(helper, List.of(colony), 16000, () -> {
                final long now = f.level.getGameTime();
                if (cancelledAt[0] < 0)
                {
                    final LabourClaim claim = colony.getLabourBoard().claimOf(builder.getId());
                    if (claim != null && claim.source() == LabourClaim.Source.SELF && logsLeft(helper, 34, 20, 5) < 5)
                    {
                        colony.getWorkManager().removeWorkOrder(order.getID());
                        cancelledAt[0] = now;
                    }
                    return false;
                }
                final int taken = (5 - logsLeft(helper, 34, 20, 5)) * 4;
                final int had = (count(LabourSession.inventoryOf(builder), Items.OAK_LOG) + inHut(f.builder, Items.OAK_LOG)) * 4
                                  + count(LabourSession.inventoryOf(builder), Items.OAK_PLANKS) + inHut(f.builder, Items.OAK_PLANKS)
                                  + floorCount(helper, 22, 12, 4);
                helper.assertTrue(had <= taken, "more wood than was felled: " + had + " planks' worth from " + taken);
                if (now - cancelledAt[0] > 300)
                {
                    helper.assertTrue(colony.getLabourBoard().claimOf(builder.getId()) == null, "the claim outlived the order");
                    helper.assertTrue(labourReservations(f).isEmpty(), "labour reservations outlived the order: " + labourReservations(f).size());
                    final var state = builder.getJob() == null || builder.getJob().getWorkerAI() == null ? null : builder.getJob().getWorkerAI().getState();
                    helper.assertTrue(state != com.minecolonies.core.entity.ai.workers.builder.BuilderSupplyState.SUPPLY_RUN, "still fetching for a gone order");
                }
                return now - cancelledAt[0] > 900;
            }, () -> {
                end();
                helper.succeed();
            }, () -> "builder inv " + LabourSession.inventoryOf(builder) + " claims " + colony.getLabourBoard().claims().size() + " cancelled at " + cancelledAt[0]
                       + " logs left " + logsLeft(helper, 34, 20, 5));
        });
    }

    /**
     * The builder's hut asks for a wooden axe and nobody makes tools: the builder makes it himself (a tree, planks, sticks, a table or his pockets) and hands it in.
     */
    public static void builderMakesHisOwnTool(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("graceTicks", 200);
        LabourSettings.override("total", 0);
        final RsFixture f = found(helper, "labour builder tool", 2);
        final Colony colony = (Colony) f.colony;
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        tree(helper, 34, 20, 6);
        helper.runAfterDelay(100, () -> {
            final ICitizenData builder = f.spawn(new BlockPos(12, 1, 8), false);
            helper.assertTrue(f.builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(builder), "builder not assigned");
            planksFloor(helper, colony, f.builder, 22, 12, 1);
            final IToken<?> axe = ask(f.builder, new ItemStack(Items.WOODEN_AXE));
            final boolean[] forAxe = {false};
            poll(helper, List.of(colony), 16000, () -> {
                for (final LabourClaim claim : colony.getLabourBoard().claims())
                {
                    helper.assertTrue(claim.source() == LabourClaim.Source.SELF && claim.destination().equals(f.builder.getID()), "a claim of another kind: " + claim.source());
                    forAxe[0] |= claim.target() == Items.WOODEN_AXE && axe.equals(claim.request());
                }
                return forAxe[0] && !f.isWithPlayer(axe) && !f.isRetrying(axe);
            }, () -> {
                end();
                helper.succeed();
            }, () -> describe(f, axe, builder));
        });
    }

    /**
     * Scaffolding takes bamboo and string; there is none and labour does not invent them. The builder makes no claim for it, the request stays with the player and
     * says why labour is blocked.
     */
    public static void builderMakesNoScaffoldingFromNothing(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("graceTicks", 200);
        LabourSettings.override("total", 0);
        final RsFixture f = found(helper, "labour builder scaffolding", 2);
        final Colony colony = (Colony) f.colony;
        helper.runAfterDelay(100, () -> {
            final ICitizenData builder = f.spawn(new BlockPos(12, 1, 8), false);
            helper.assertTrue(f.builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(builder), "builder not assigned");
            planksFloor(helper, colony, f.builder, 22, 12, 1);
            final IToken<?> scaffolding = ask(f.builder, new ItemStack(Items.SCAFFOLDING, 4));
            poll(helper, List.of(colony), 6000, () -> {
                for (final LabourClaim claim : colony.getLabourBoard().claims())
                {
                    helper.assertTrue(!scaffolding.equals(claim.request()), "a claim for scaffolding without bamboo: " + claim.target());
                }
                return colony.getLabourBoard().blockedWhy(scaffolding) != null;
            }, () -> {
                helper.assertTrue(f.isWithPlayer(scaffolding) || f.isRetrying(scaffolding), "scaffolding was served: " + f.describe(scaffolding));
                end();
                helper.succeed();
            }, () -> describe(f, scaffolding, builder));
        });
    }

    private static int count(final java.util.Map<Item, Integer> inventory, final Item item)
    {
        return inventory.getOrDefault(item, 0);
    }

    private static int floorCount(final GameTestHelper helper, final int x, final int z, final int size)
    {
        int planks = 0;
        for (int dx = 0; dx < size; dx++)
        {
            for (int dz = 0; dz < size; dz++)
            {
                planks += helper.getBlockState(new BlockPos(x + dx, 1, z + dz)).is(Blocks.OAK_PLANKS) ? 1 : 0;
            }
        }
        return planks;
    }

    private static boolean floorPlaced(final GameTestHelper helper, final int x, final int z, final int size)
    {
        return floorCount(helper, x, z, size) == size * size;
    }

    // ------------------------------------------------------------------ the whitelist, tools that wear

    /**
     * An iron pickaxe is not labour's: no claim, no candidate. A stone pickaxe is: the labourer makes a wooden pickaxe first (planks and sticks from a tree), mines three
     * cobblestone with it (the pickaxe wears through the citizens' damage path), crafts the stone pickaxe at the table and brings it.
     */
    public static void stoneToolsNeedAWoodenPickaxe(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "labour stone", 2);
        final Colony colony = (Colony) f.colony;
        // (automatic labour digs no ground unless allowed: this colony's own test floor of stone is allowed)
        LabourSettings.allowDiggingForTest(colony.getID(), true);
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        tree(helper, 34, 20, 5);
        // (the stone a labourer digs needs ground under it, and the test floor is a single layer over the void: two layers of stone)
        for (int x = 18; x <= 30; x++)
        {
            for (int z = 22; z <= 30; z++)
            {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
                helper.setBlock(new BlockPos(x, -1, z), Blocks.STONE.defaultBlockState());
                // (the test box has a barrier roof: the surface scan, which looks from the sky down, would see only that)
                for (int y = 20; y <= 60; y++)
                {
                    if (helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.BARRIER))
                    {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            {
                final BlockPos probe = helper.absolutePos(new BlockPos(24, 0, 26));
                final var sites = colony.getLabourBoard().sites();
                for (int y = probe.getY() + 60; y > probe.getY(); y--)
                {
                    final BlockState above = f.level.getBlockState(new BlockPos(probe.getX(), y, probe.getZ()));
                    if (!above.isAir())
                    {
                        com.minecolonies.core.colony.labour.LabourStats.log("stone probe: above the pad at y {}: {}", y, above);
                        break;
                    }
                }
                com.minecolonies.core.colony.labour.LabourStats.log("stone probe: y-1 {} y0 {} y1 {} height {} owned {} protected {} surface {} center {}", helper.getBlockState(new BlockPos(24, -1, 26)),
                  helper.getBlockState(new BlockPos(24, 0, 26)), helper.getBlockState(new BlockPos(24, 1, 26)),
                  f.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe.getX(), probe.getZ()), sites.ownedBy(probe), sites.protectedBlock(probe),
                  sites.hasSurface(Items.COBBLESTONE), colony.getCenter());
            }
            final IToken<?> iron = ask(f.builder, new ItemStack(Items.IRON_PICKAXE));
            final IToken<?> stone = ask(f.builder, new ItemStack(Items.STONE_PICKAXE));
            final int[] worn = {0};
            poll(helper, List.of(colony), 12000, () -> {
                helper.assertTrue(colony.getLabourBoard().candidate(iron) == null, "the iron pickaxe is a candidate");
                for (final LabourClaim claim : colony.getLabourBoard().claims())
                {
                    helper.assertTrue(claim.target() != Items.IRON_PICKAXE, "a claim for the iron pickaxe");
                }
                final var inventory = idle.getInventory();
                for (int slot = 0; slot < inventory.getSlots(); slot++)
                {
                    final ItemStack stack = inventory.getStackInSlot(slot);
                    if (stack.is(Items.WOODEN_PICKAXE) && stack.getDamageValue() > 0)
                    {
                        worn[0] = Math.max(worn[0], stack.getDamageValue());
                    }
                }
                return inHut(f.builder, Items.STONE_PICKAXE) >= 1;
            }, () -> {
                helper.assertTrue(worn[0] > 0 || LabourSession.inventoryOf(idle).getOrDefault(Items.WOODEN_PICKAXE, 0) == 0,
                  "the wooden pickaxe did not wear while mining cobblestone");
                helper.assertTrue(f.isWithPlayer(iron) || f.isRetrying(iron), "the iron pickaxe request was touched: " + f.describe(iron));
                LabourSettings.allowDiggingForTest(colony.getID(), false);
                end();
                helper.succeed();
            }, () -> describe(f, stone, idle));
        });
    }

    // ------------------------------------------------------------------ nobody else's inventory, covered stock

    /**
     * A sawmill covers planks. The pickaxe a builder hut asks for is made from planks out of the WAREHOUSE (reserved in the ledger as labour, then committed), the labourer
     * saws nothing and fells no tree, and the planks lying in the sawmill's own hut stay where they are.
     */
    public static void coveredGoodsComeFromTheWarehouseOnly(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "labour withdraw", 2);
        final Colony colony = (Colony) f.colony;
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        final IBuilding sawmill = f.place(ModBlocks.blockHutSawmill, new BlockPos(8, 1, 24), "craftsmanship/carpentry/sawmill1.blueprint");
        final var craft = sawmill.getModule(BuildingModules.SAWMILL_CRAFT);
        final com.minecolonies.api.crafting.RecipeStorage recipe = com.minecolonies.api.crafting.RecipeStorage.builder()
          .withRecipeId(null)
          .withInputs(List.of(new com.minecolonies.api.crafting.ItemStorage(new ItemStack(Items.OAK_LOG, 1))))
          .withPrimaryOutput(new ItemStack(Items.OAK_PLANKS, 4))
          .build();
        helper.assertTrue(craft.addRecipe(IColonyManager.getInstance().getRecipeManager().checkOrAddRecipe(recipe)), "the sawmill rejected the recipe");
        helper.assertTrue(colony.getLabourBoard().coverage().covered(Items.OAK_PLANKS), "a built sawmill with the recipe does not cover planks");
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        tree(helper, 34, 20, 5);
        f.stock(0, new ItemStack(Items.OAK_PLANKS, 5));
        helper.assertTrue(InventoryUtils.forceItemStackToItemHandler(sawmill.getItemHandlerCap(), new ItemStack(Items.OAK_PLANKS, 10), s -> true).isEmpty(), "the sawmill hut took no planks");
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final IToken<?> token = ask(f.builder, new ItemStack(Items.WOODEN_PICKAXE));
            poll(helper, List.of(colony), 9000, () -> {
                helper.assertTrue(logsLeft(helper, 34, 20, 5) == 5, "a tree was felled although the sawmill covers planks");
                helper.assertTrue(inHut(sawmill, Items.OAK_PLANKS) == 10, "the sawmill's own planks were touched: " + inHut(sawmill, Items.OAK_PLANKS));
                return inHut(f.builder, Items.WOODEN_PICKAXE) >= 1;
            }, () -> {
                final boolean committed = f.ledger().history(s -> s.is(Items.OAK_PLANKS)).stream()
                                            .anyMatch(e -> e.reason() == ReservationReason.LABOUR && e.op() == com.minecolonies.core.colony.requestsystem.reservation.LedgerEvent.Op.COMMIT);
                helper.assertTrue(committed, "the planks were not taken through the ledger as labour");
                helper.assertTrue(f.physical(s -> s.is(Items.OAK_PLANKS)) < 5, "the warehouse still holds all planks");
                end();
                helper.succeed();
            }, () -> describe(f, token, idle));
        });
    }

    // ------------------------------------------------------------------ the stock goal

    /**
     * With the stock goal on (a goal of planks: start below 4, target 8) a jobless citizen keeps the warehouse supplied while nobody makes planks: he stops when the stock
     * is there, and what he made beyond it goes into the warehouse too.
     */
    public static void stockpileFillsAndStops(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("stockpile", true);
        com.minecolonies.core.colony.labour.LabourDataListener.overrideStockpileForTests(
          List.of(new com.minecolonies.core.colony.labour.LabourDataListener.StockpileGoal("planks", "minecraft:oak_planks", 4, 8)));
        final RsFixture f = found(helper, "labour stock", 2);
        final Colony colony = (Colony) f.colony;
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        tree(helper, 34, 20, 5);
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final int[] quiet = {0};
            poll(helper, List.of(colony), 9000, () -> {
                final int planks = f.physical(s -> s.is(Items.OAK_PLANKS));
                if (planks >= 8 && colony.getLabourBoard().claims().isEmpty() && IdleCitizenArbiter.sessionOf(idle) == null)
                {
                    quiet[0] += 20;
                }
                return quiet[0] >= 800;
            }, () -> {
                final int planks = f.physical(s -> s.is(Items.OAK_PLANKS));
                helper.assertTrue(planks >= 8 && planks <= 12, "the stock is " + planks);
                com.minecolonies.core.colony.labour.LabourDataListener.overrideStockpileForTests(null);
                end();
                helper.succeed();
            }, () -> "planks " + f.physical(s -> s.is(Items.OAK_PLANKS)) + " claims " + colony.getLabourBoard().claims().size() + " inv " + LabourSession.inventoryOf(idle));
        });
    }

    // ------------------------------------------------------------------ the furnace

    /**
     * Two charcoal are asked for: the labourer fells logs, loads a plain furnace with logs and fuel, waits, takes the charcoal and brings it. A furnace with somebody's
     * things in it is left alone.
     */
    public static void charcoalAtAFurnace(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "labour charcoal", 2);
        final Colony colony = (Colony) f.colony;
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.FURNACE.defaultBlockState());
        tree(helper, 34, 20, 5);
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final IToken<?> token = ask(f.builder, new ItemStack(Items.CHARCOAL, 2));
            poll(helper, List.of(colony), 9000, () -> inHut(f.builder, Items.CHARCOAL) >= 2, () -> {
                end();
                helper.succeed();
            }, () -> describe(f, token, idle));
        });
    }

    public static void furnaceWithThingsInItIsLeftAlone(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "labour furnace", 2);
        final Colony colony = (Colony) f.colony;
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        final BlockPos furnacePos = new BlockPos(22, 1, 20);
        helper.setBlock(furnacePos, Blocks.FURNACE.defaultBlockState());
        final var furnace = (net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(furnacePos));
        furnace.setItem(1, new ItemStack(Items.COAL, 3));
        tree(helper, 34, 20, 5);
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final IToken<?> token = ask(f.builder, new ItemStack(Items.CHARCOAL, 2));
            poll(helper, List.of(colony), 6000, () -> colony.getLabourBoard().blockedWhy(token) != null, () -> {
                helper.assertTrue(furnace.getItem(1).getCount() == 3 && furnace.getItem(1).is(Items.COAL), "the player's coal was touched: " + furnace.getItem(1));
                helper.assertTrue(inHut(f.builder, Items.CHARCOAL) == 0, "charcoal appeared");
                end();
                helper.succeed();
            }, () -> describe(f, token, idle));
        });
    }

    // ------------------------------------------------------------------ trees, coverage, ledger, caps

    /**
     * Only natural trees are felled: a log cabin (no leaves) and a trunk with leaves that a player placed are no trees; the trees nearest to the town hall stay.
     */
    public static void naturalTreesOnly(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "labour trees", 1);
        final Colony colony = (Colony) f.colony;
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        // a cabin of logs
        for (int y = 1; y <= 3; y++)
        {
            for (int x = 30; x <= 32; x++)
            {
                for (int z = 16; z <= 18; z++)
                {
                    if (x != 31 || z != 17)
                    {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG.defaultBlockState());
                    }
                }
            }
        }
        // a trunk with leaves that a player placed
        helper.setBlock(new BlockPos(38, 0, 20), Blocks.DIRT.defaultBlockState());
        for (int y = 1; y <= 4; y++)
        {
            helper.setBlock(new BlockPos(38, y, 20), Blocks.OAK_LOG.defaultBlockState());
        }
        final BlockState placed = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dz = -1; dz <= 1; dz++)
            {
                if (dx != 0 || dz != 0)
                {
                    helper.setBlock(new BlockPos(38 + dx, 4, 20 + dz), placed);
                }
            }
        }
        colony.getLabourBoard().invalidate();
        helper.assertTrue(!colony.getLabourBoard().sites().hasTree(Items.OAK_LOG), "a cabin or a tree with placed leaves counts as a tree");
        tree(helper, 40, 24, 5);
        colony.getLabourBoard().invalidate();
        helper.assertTrue(colony.getLabourBoard().sites().hasTree(Items.OAK_LOG), "a natural tree is not found");
        LabourSettings.override("keepTrees", 1);
        colony.getLabourBoard().invalidate();
        helper.assertTrue(colony.getLabourBoard().sites().hasTree(Items.OAK_LOG), "the only tree beyond 24 blocks of the hall is kept");
        end();
        helper.succeed();
    }

    /**
     * What a specialist covers: a built sawmill with the recipe covers planks without a worker (BUILT) and not in STAFFED mode; a covered good stays covered for the grace
     * time after the building is gone.
     */
    public static void coverageRules(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "labour coverage", 1);
        final Colony colony = (Colony) f.colony;
        final var coverage = colony.getLabourBoard().coverage();
        helper.assertTrue(!coverage.covered(Items.OAK_PLANKS) && !coverage.covered(Items.OAK_LOG), "something is covered in an empty colony");
        final IBuilding sawmill = f.place(ModBlocks.blockHutSawmill, new BlockPos(8, 1, 24), "craftsmanship/carpentry/sawmill1.blueprint");
        final var craft = sawmill.getModule(BuildingModules.SAWMILL_CRAFT);
        final com.minecolonies.api.crafting.RecipeStorage recipe = com.minecolonies.api.crafting.RecipeStorage.builder()
          .withRecipeId(null)
          .withInputs(List.of(new com.minecolonies.api.crafting.ItemStorage(new ItemStack(Items.OAK_LOG, 1))))
          .withPrimaryOutput(new ItemStack(Items.OAK_PLANKS, 4))
          .build();
        helper.assertTrue(craft.addRecipe(IColonyManager.getInstance().getRecipeManager().checkOrAddRecipe(recipe)), "the sawmill rejected the recipe");
        LabourSettings.override("coverageMode", com.minecolonies.api.colony.labour.LabourModes.CoverageMode.STAFFED);
        LabourSettings.override("coverageGrace", 0);
        coverage.invalidate();
        helper.assertTrue(!coverage.covered(Items.OAK_PLANKS), "an unstaffed sawmill covers planks in the STAFFED mode");
        LabourSettings.override("coverageMode", com.minecolonies.api.colony.labour.LabourModes.CoverageMode.BUILT);
        LabourSettings.override("coverageGrace", 2400);
        coverage.invalidate();
        helper.assertTrue(coverage.covered(Items.OAK_PLANKS), "a built sawmill with the recipe, without a worker, does not cover planks");
        helper.assertTrue(!coverage.covered(Items.OAK_LOG), "the sawmill covers logs");
        // the sawmill goes: planks stay covered for the grace time, then not
        sawmill.setBuildingLevel(0);
        coverage.invalidate();
        helper.assertTrue(coverage.covered(Items.OAK_PLANKS), "the good is not covered for the grace time after the sawmill is gone");
        helper.assertTrue(!coverage.covered(Items.OAK_LOG), "logs are covered");
        end();
        helper.succeed();
    }

    /**
     * The ledger drops labour claims that no claim of the board holds any more (the board is not saved: after a restart nothing holds them).
     */
    public static void ledgerSweepsOrphanedLabourClaims(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "labour sweep", 1);
        final Colony colony = (Colony) f.colony;
        final IToken<?> orphan = new com.minecolonies.api.colony.requestsystem.token.StandardToken(java.util.UUID.randomUUID());
        final long now = helper.getLevel().getGameTime();
        f.ledger().reserveStock(f.warehouse.getID(), null, new com.minecolonies.api.crafting.ItemStorage(new ItemStack(Items.OAK_PLANKS)), 4, orphan, "test",
          ReservationReason.LABOUR, now - 1000L);
        helper.assertTrue(labourReservations(f).size() == 1, "the test claim is not in the ledger");
        // (the request sweep leaves labour claims alone: they have no request)
        ((com.minecolonies.core.colony.requestsystem.management.manager.StandardRequestManager) f.standard()).sweepReservations();
        helper.assertTrue(labourReservations(f).isEmpty(), "the orphaned labour claim survived the sweep: " + labourReservations(f));
        end();
        helper.succeed();
    }

    /**
     * Four jobless citizens, five requests: with half the jobless allowed to labour two at a time, and one claim per request, no more than that ever works.
     */
    public static void capsAndExclusiveClaims(final GameTestHelper helper)
    {
        begin();
        LabourSettings.override("perRequest", 1);
        final RsFixture f = found(helper, "labour caps", 2);
        final Colony colony = (Colony) f.colony;
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        tree(helper, 34, 20, 5);
        tree(helper, 38, 24, 5);
        tree(helper, 42, 20, 5);
        helper.runAfterDelay(100, () -> {
            final List<ICitizenData> idle = new ArrayList<>();
            for (int i = 0; i < 4; i++)
            {
                idle.add(f.spawn(new BlockPos(12 + 2 * i, 1, 16), false));
            }
            final List<IToken<?>> tokens = new ArrayList<>();
            for (int i = 0; i < 5; i++)
            {
                tokens.add(ask(f.builder, new ItemStack(Items.STICK, 4 + i)));
            }
            final int[] peak = {0};
            poll(helper, List.of(colony), 9000, () -> {
                final List<LabourClaim> claims = colony.getLabourBoard().claims();
                peak[0] = Math.max(peak[0], claims.size());
                helper.assertTrue(claims.size() <= 2, "more labourers than the share allows: " + claims.size());
                for (final IToken<?> token : tokens)
                {
                    helper.assertTrue(colony.getLabourBoard().claimsOn(token) <= 1, "two claims on one request");
                }
                int served = 0;
                for (final IToken<?> token : tokens)
                {
                    served += !f.isWithPlayer(token) && !f.isRetrying(token) ? 1 : 0;
                }
                return served == tokens.size();
            }, () -> {
                helper.assertTrue(peak[0] == 2, "never two labourers at once: " + peak[0]);
                end();
                helper.succeed();
            }, () -> "claims " + colony.getLabourBoard().claims().size() + " peak " + peak[0]);
        });
    }

    /**
     * A farmer hut makes the RS keep a request for a pickaxe waiting at the retrying resolver (something could still bring raw goods); the labourer serves it all the same.
     */
    public static void retryingHeldRequestIsServed(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "labour retrying", 2);
        final Colony colony = (Colony) f.colony;
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        f.place(ModBlocks.blockHutFarmer, new BlockPos(8, 1, 24), "fundamentals/farmer1.blueprint");
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        tree(helper, 34, 20, 5);
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final IToken<?> token = ask(f.builder, new ItemStack(Items.WOODEN_PICKAXE));
            helper.assertTrue(f.isRetrying(token), "the request is not held by the retrying resolver: " + f.describe(token));
            poll(helper, List.of(colony), 9000, () -> inHut(f.builder, Items.WOODEN_PICKAXE) >= 1 && !f.isRetrying(token) && !f.isWithPlayer(token), () -> {
                end();
                helper.succeed();
            }, () -> describe(f, token, idle));
        });
    }

    /**
     * At nightfall a labourer stops (the work hours of the jobless end before the night), his claim is given back without a mark against the request.
     */
    public static void nightEndsTheWork(final GameTestHelper helper)
    {
        begin();
        final RsFixture f = found(helper, "labour night", 2);
        final Colony colony = (Colony) f.colony;
        forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        helper.setBlock(new BlockPos(22, 1, 20), Blocks.CRAFTING_TABLE.defaultBlockState());
        tree(helper, 34, 20, 5);
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), false);
            final IToken<?> token = ask(f.builder, new ItemStack(Items.WOODEN_PICKAXE));
            final boolean[] night = {false};
            poll(helper, List.of(colony), 4000, () -> {
                if (!night[0])
                {
                    if (colony.getLabourBoard().claimOf(idle.getId()) != null && IdleCitizenArbiter.sessionOf(idle) != null)
                    {
                        helper.getLevel().getServer().clockManager().setTotalTicks(helper.getLevel().registryAccess().getOrThrow(WorldClocks.OVERWORLD), 14000L);
                        IdleCitizenArbiter.tick(colony);
                        helper.assertTrue(IdleCitizenArbiter.sessionOf(idle) == null && colony.getLabourBoard().claimOf(idle.getId()) == null, "the labourer works at night");
                        night[0] = true;
                    }
                    return false;
                }
                return true;
            }, () -> {
                helper.assertTrue(labourReservations(f).isEmpty(), "ledger entries are left: " + labourReservations(f));
                end();
                helper.succeed();
            }, () -> describe(f, token, idle));
        });
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
}
