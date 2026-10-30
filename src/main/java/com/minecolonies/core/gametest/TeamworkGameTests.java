package com.minecolonies.core.gametest;

import com.google.common.collect.ImmutableList;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.requestsystem.token.StandardToken;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.labour.DirectedOrder;
import com.minecolonies.core.colony.labour.DirectedOrders;
import com.minecolonies.core.colony.labour.LabourBoard;
import com.minecolonies.core.colony.labour.LabourCargo;
import com.minecolonies.core.colony.labour.LabourClaim;
import com.minecolonies.core.colony.labour.LabourSession;
import com.minecolonies.core.colony.labour.LabourSettings;
import com.minecolonies.core.colony.requestsystem.RsCourierHooks;
import com.minecolonies.core.colony.requestsystem.RsFlags;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.util.List;

/**
 * Round 6 teamwork (group {@code teamwork}, MINECOLONIES_GAMETEST_ONLY=teamwork): two workers never hold one lot, goods carried for a request stay tied to it
 * across an interruption and a save, one admission policy for every labour claim with the world cap, and what a short courier load brought is the request's own.
 * Round 7: directed orders take free places only, a worker's own tool takes none, a recalled helper's goods count for the request, the world cap is shared.
 * Round 8: automatic labour waits for a world place like an order, a colony keeps its equal share of the world cap, carried goods keep their record through
 * neutral interruptions and beyond the lot, a short receipt stays the request's while it waits.
 * Exact item counts before and after.
 */
final class TeamworkGameTests
{
    private TeamworkGameTests()
    {
    }

    static void register(final RegisterGameTestsEvent event)
    {
        event.registerTest(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "teamwork_cargo_reload"),
          info -> new MinecoloniesGameTestInstance(info, TeamworkGameTests::cargoAcrossReload),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, "teamwork_cargo_reload"));
        event.registerTest(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "teamwork_admission_world_cap"),
          info -> new MinecoloniesGameTestInstance(info, TeamworkGameTests::admissionWorldCap),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, "teamwork_admission_world_cap"));
        event.registerTest(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "teamwork_partial_receipt"),
          info -> new MinecoloniesGameTestInstance(info, TeamworkGameTests::partialReceipt),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, "teamwork_partial_receipt"));
        event.registerTest(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "teamwork_directed_cap"),
          info -> new MinecoloniesGameTestInstance(info, TeamworkGameTests::directedAtTheCap),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, "teamwork_directed_cap"));
        event.registerTest(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "teamwork_own_tool_full"),
          info -> new MinecoloniesGameTestInstance(info, TeamworkGameTests::ownToolUnderAFullCap),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, "teamwork_own_tool_full"));
        event.registerTest(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "teamwork_recalled_cargo"),
          info -> new MinecoloniesGameTestInstance(info, TeamworkGameTests::recalledCargoCounts),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, "teamwork_recalled_cargo"));
        event.registerTest(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "teamwork_world_cap_fair"),
          info -> new MinecoloniesGameTestInstance(info, TeamworkGameTests::worldCapShared),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, "teamwork_world_cap_fair"));
        event.registerTest(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "teamwork_auto_wait"),
          info -> new MinecoloniesGameTestInstance(info, TeamworkGameTests::automaticLabourWaits),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, "teamwork_auto_wait"));
        event.registerTest(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "teamwork_equal_share"),
          info -> new MinecoloniesGameTestInstance(info, TeamworkGameTests::equalShareFloor),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, "teamwork_equal_share"));
        event.registerTest(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "teamwork_cargo_custody"),
          info -> new MinecoloniesGameTestInstance(info, TeamworkGameTests::cargoCustody),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, "teamwork_cargo_custody"));
        event.registerTest(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "teamwork_receipt_waits"),
          info -> new MinecoloniesGameTestInstance(info, TeamworkGameTests::receiptWaits),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, "teamwork_receipt_waits"));
    }

    /** The second colony of the two-colony tests stands this far east of the fixture. */
    private static final int DX = 240;

    /** A wood area of the fixture colony, in the test plot. */
    private static DirectedOrder.Area areaA(final GameTestHelper helper)
    {
        return area(helper, 30, 16, 41, 32);
    }

    /** A wood area of the second colony. */
    private static DirectedOrder.Area areaB(final GameTestHelper helper)
    {
        return area(helper, DX + 14, 12, DX + 26, 24);
    }

    private static DirectedOrder.Area area(final GameTestHelper helper, final int x0, final int z0, final int x1, final int z1)
    {
        final BlockPos a = helper.absolutePos(new BlockPos(x0, 1, z0));
        final BlockPos b = helper.absolutePos(new BlockPos(x1, 1, z1));
        return DirectedOrder.Area.of(a.getX(), a.getZ(), b.getX(), b.getZ());
    }

    /** A directed order of 4 logs for one citizen (no claim yet: his place is reserved by the order). */
    private static DirectedOrders.Start order(final LabourBoard board, final ICitizenData citizen, final DirectedOrder.Area area)
    {
        return board.directed().start(List.of(citizen.getId()), DirectedOrder.Resource.WOOD, 4, area, null);
    }

    /** A jobless citizen of the second colony, without AI (every step of these tests is made by hand). */
    private static ICitizenData spawnB(final GameTestHelper helper, final Colony colony, final int rx, final int rz)
    {
        final ICitizenData citizen = colony.getCitizenManager().spawnOrCreateCivilian(null, helper.getLevel(), List.of(helper.absolutePos(new BlockPos(rx, 1, rz))), true);
        helper.assertTrue(citizen != null && citizen.getEntity().isPresent(), "citizen of " + colony.getName() + " did not spawn");
        citizen.getEntity().get().setNoAi(true);
        return citizen;
    }

    /** Every live claim of the two colonies that takes a labour place, and their pending directed citizens: never more than the world cap. */
    private static int worldPlaces(final LabourBoard a, final LabourBoard b)
    {
        return a.places() + b.places();
    }

    private static int logs(final ICitizenData citizen)
    {
        return InventoryUtils.getItemCountInItemHandler(citizen.getInventory(), s -> s.is(Items.OAK_LOG));
    }

    /**
     * A labourer has 5 of the 8 logs a builder hut asked for when he is called away (his claim's lease ran out while he slept): the claim goes, the 5 logs stay
     * tied to the request, also through a save and load of the colony's labour record. A second labourer competes for the request the whole time. The first one,
     * free again, hands in exactly his 5 (no new gathering for it), the second brings exactly the missing 3; the lots on the request never add up to more than 8.
     * Before: 5 carried + 5 in the tree. After: 8 in the hut, the other 2 in the warehouse, a bag or on the ground, none made or lost.
     */
    static void cargoAcrossReload(final GameTestHelper helper)
    {
        LabourGameTests.begin();
        LabourSettings.override("perRequest", 2);
        // (both jobless citizens may labour at once: the request's own limits are what is tested)
        LabourSettings.override("share", 100);
        final RsFixture f = LabourGameTests.found(helper, "teamwork cargo", 2);
        final Colony colony = (Colony) f.colony;
        LabourGameTests.forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        helper.setBlock(new BlockPos(22, 1, 20), net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState());
        LabourGameTests.tree(helper, 34, 20, 5);
        helper.runAfterDelay(100, () -> {
            final ICitizenData first = f.spawn(new BlockPos(14, 1, 16), false);
            final ICitizenData second = f.spawn(new BlockPos(18, 1, 16), false);
            helper.assertTrue(InventoryUtils.addItemStackToItemHandlerWithResult(first.getInventory(), new ItemStack(Items.OAK_LOG, 5)).isEmpty(), "no room for 5 logs");
            final IToken<?> token = LabourGameTests.ask(f.builder, new ItemStack(Items.OAK_LOG, 8));
            final LabourBoard board = colony.getLabourBoard();
            board.invalidate();
            final LabourClaim claim = board.offer(first, false, true, false);
            helper.assertTrue(claim != null && token.equals(claim.request()) && claim.amount() == 8, "no lot of 8 for the first labourer: " + claim);
            helper.assertTrue(board.offer(second, false, true, false) == null && board.claimsOn(token) == 1, "a second lot on a request that is all in the first one");

            // called away: the claim goes, the logs stay his and tied to the request
            board.release(claim, false, false);
            helper.assertTrue(board.claimOf(first.getId()) == null, "the claim outlived the interruption");
            final LabourCargo.Entry kept = board.cargoOf(first.getId());
            helper.assertTrue(kept != null && token.getIdentifier().equals(kept.request()) && kept.item() == Items.OAK_LOG, "the 5 logs are not on record for the request: " + kept);

            // save and load
            final CompoundTag saved = colony.getLabourData().write();
            colony.getLabourData().read(saved);
            helper.assertTrue(board.cargoOf(first.getId()) != null && token.getIdentifier().equals(board.cargoOf(first.getId()).request()), "the record did not survive the save");
            helper.assertTrue(logs(first) == 5 && LabourGameTests.inHut(f.builder, Items.OAK_LOG) == 0, "before: first carries " + logs(first) + ", hut " + LabourGameTests.inHut(f.builder, Items.OAK_LOG));

            final int[] cargoLot = {-1};
            final int[] peakLots = {0};
            LabourGameTests.poll(helper, List.of(colony), 9000, () -> {
                int lots = 0;
                for (final LabourClaim c : board.claims())
                {
                    if (token.equals(c.request()))
                    {
                        lots += c.amount();
                        if (c.cargo() && c.citizenId() == first.getId() && cargoLot[0] < 0)
                        {
                            cargoLot[0] = c.amount();
                        }
                    }
                }
                peakLots[0] = Math.max(peakLots[0], lots);
                helper.assertTrue(lots <= 8, "the lots on the request add up to " + lots + " for 8");
                helper.assertTrue(board.claimsOn(token) <= 2, "more labourers than the request admits");
                final int hut = LabourGameTests.inHut(f.builder, Items.OAK_LOG);
                helper.assertTrue(hut <= 8, "the hut got " + hut + " logs for a request of 8");
                return hut == 8 && board.claimsOn(token) == 0 && !f.isWithPlayer(token) && !f.isRetrying(token);
            }, () -> {
                final int hut = LabourGameTests.inHut(f.builder, Items.OAK_LOG);
                final int warehouse = f.physical(s -> s.is(Items.OAK_LOG));
                final int bags = logs(first) + logs(second);
                int ground = 0;
                final BlockPos lo = helper.absolutePos(new BlockPos(-8, 0, -8));
                final BlockPos hi = helper.absolutePos(new BlockPos(48, 30, 32));
                final AABB area = new AABB(Math.min(lo.getX(), hi.getX()), lo.getY(), Math.min(lo.getZ(), hi.getZ()), Math.max(lo.getX(), hi.getX()) + 1, hi.getY(),
                  Math.max(lo.getZ(), hi.getZ()) + 1);
                for (final ItemEntity item : f.level.getEntitiesOfClass(ItemEntity.class, area))
                {
                    ground += item.getItem().is(Items.OAK_LOG) ? item.getItem().getCount() : 0;
                }
                final int standing = LabourGameTests.logsLeft(helper, 34, 20, 5);
                final String totals = "hut " + hut + " warehouse " + warehouse + " bags " + bags + " ground " + ground + " tree " + standing;
                com.minecolonies.api.util.Log.getLogger().info("TEAMWORK cargo_reload {} cargo lot {} peak lots {}", totals, cargoLot[0], peakLots[0]);
                helper.assertTrue(cargoLot[0] == 5, "the first labourer's hand-in was " + cargoLot[0] + " logs, not the 5 he carried");
                helper.assertTrue(hut == 8, "after: " + totals);
                helper.assertTrue(hut + warehouse + bags + ground + standing == 10, "logs made or lost: " + totals + " (10 before)");
                helper.assertTrue(board.cargoOf(first.getId()) == null, "the record outlived the hand-in");
                LabourGameTests.end();
                helper.succeed();
            }, () -> "hut " + LabourGameTests.inHut(f.builder, Items.OAK_LOG) + " first " + LabourSession.inventoryOf(first) + " second " + LabourSession.inventoryOf(second)
                       + " claims " + board.claims().size() + " cargo " + board.cargoOf(first.getId()) + " request " + f.describe(token));
        });
    }

    /**
     * One admission policy (Sol r5, round 7): the world cap is a hard total across colonies. World cap 1, two colonies: whichever takes the place first holds it, in
     * both orders. World cap 0: nobody. World cap 2: one place per colony, a second in one colony is refused. The places are taken by directed orders (a worker's own
     * tool takes none since round 7, see {@link #ownToolUnderAFullCap}).
     */
    static void admissionWorldCap(final GameTestHelper helper)
    {
        LabourGameTests.begin();
        LabourSettings.override("total", 8);
        LabourSettings.override("worldMax", 1);
        final RsFixture a = LabourGameTests.found(helper, "teamwork cap a", 2);
        final Colony colonyA = (Colony) a.colony;
        LabourGameTests.forceArea(a.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        final Colony colonyB = LabourGameTests.foundAt(helper, "teamwork cap b", DX);
        helper.runAfterDelay(100, () -> {
            final ICitizenData ia1 = a.spawn(new BlockPos(14, 1, 16), true);
            final ICitizenData ia2 = a.spawn(new BlockPos(16, 1, 16), true);
            final ICitizenData ib1 = spawnB(helper, colonyB, DX + 14, 16);
            final LabourBoard ba = colonyA.getLabourBoard();
            final LabourBoard bb = colonyB.getLabourBoard();

            // world cap 1, A first
            final DirectedOrders.Start sa = order(ba, ia1, areaA(helper));
            helper.assertTrue(sa.order() != null, "A got no place in an empty world: " + sa.refusal());
            helper.assertTrue(bb.capacity() == 0 && order(bb, ib1, areaB(helper)).refusal() == DirectedOrders.Refusal.CAP, "B got a second place under a world cap of 1");
            helper.assertTrue(ba.placesFull() && worldPlaces(ba, bb) == 1, "A's places: " + ba.places() + " of " + ba.capacity());
            ba.directed().cancel(sa.order().id());
            // (B asked while A held the place, so B waits for the next one: the fair turn is teamwork_world_cap_fair, here the plain order is checked)
            LabourBoard.forgetWaiting();

            // world cap 1, B first
            final DirectedOrders.Start sb = order(bb, ib1, areaB(helper));
            helper.assertTrue(sb.order() != null, "B got no place after A gave its place back: " + sb.refusal());
            helper.assertTrue(ba.capacity() == 0 && ba.allowed() == 0 && order(ba, ia1, areaA(helper)).refusal() == DirectedOrders.Refusal.CAP, "A got a second place under a world cap of 1");
            bb.directed().cancel(sb.order().id());
            LabourBoard.forgetWaiting();

            // world cap 0: nobody
            LabourSettings.override("worldMax", 0);
            helper.assertTrue(order(ba, ia1, areaA(helper)).order() == null && order(bb, ib1, areaB(helper)).order() == null, "a place under a world cap of 0");

            // world cap 2: one place per colony while both hold one
            LabourSettings.override("worldMax", 2);
            LabourBoard.forgetWaiting();
            final DirectedOrders.Start sa2 = order(ba, ia1, areaA(helper));
            final DirectedOrders.Start sb2 = order(bb, ib1, areaB(helper));
            helper.assertTrue(sa2.order() != null && sb2.order() != null, "two colonies under a world cap of 2 did not get one place each: " + sa2.refusal() + " " + sb2.refusal());
            helper.assertTrue(ba.placesFull() && bb.placesFull() && ba.capacity() == 1 && bb.capacity() == 1,
              "a colony may take a second place under a world cap of 2 shared by two: A " + ba.capacity() + ", B " + bb.capacity());
            helper.assertTrue(order(ba, ia2, areaA(helper)).refusal() == DirectedOrders.Refusal.CAP && worldPlaces(ba, bb) == 2, "a third place under a world cap of 2");
            ba.directed().cancel(sa2.order().id());
            bb.directed().cancel(sb2.order().id());
            helper.assertTrue(ba.places() == 0 && bb.places() == 0, "places left after every order ended");
            LabourGameTests.end();
            helper.succeed();
        });
    }

    /**
     * Sol H2: a directed order takes a free place, counted with every claim and pending directed citizen of the colony and the world. World cap 1: A's order, before
     * its citizen holds a claim, keeps B's order out; A's second order is refused; the claim takes over the reserved place without a second one. World cap 2: B gets
     * the other place. The world cap is lowered to 1 (as a new setting after a reload): B's pending citizen gets no claim while A's holds the place (the order shows
     * why), and the claims of both colonies never pass the cap; once A's place is free, B's citizen takes it.
     */
    static void directedAtTheCap(final GameTestHelper helper)
    {
        LabourGameTests.begin();
        LabourSettings.override("total", 8);
        LabourSettings.override("worldMax", 1);
        final RsFixture a = LabourGameTests.found(helper, "teamwork directed a", 2);
        final Colony colonyA = (Colony) a.colony;
        LabourGameTests.forceArea(a.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        final Colony colonyB = LabourGameTests.foundAt(helper, "teamwork directed b", DX);
        helper.runAfterDelay(100, () -> {
            final ICitizenData ia1 = a.spawn(new BlockPos(14, 1, 16), true);
            final ICitizenData ia2 = a.spawn(new BlockPos(16, 1, 16), true);
            final ICitizenData ib1 = spawnB(helper, colonyB, DX + 14, 16);
            final LabourBoard ba = colonyA.getLabourBoard();
            final LabourBoard bb = colonyB.getLabourBoard();

            // a pending directed citizen holds his place in the world before he holds a claim
            final DirectedOrders.Start sa = order(ba, ia1, areaA(helper));
            helper.assertTrue(sa.order() != null, "A's order refused in an empty world: " + sa.refusal());
            helper.assertTrue(ba.claimOf(ia1.getId()) == null && ba.places() == 1, "A's pending citizen holds " + ba.places() + " places");
            helper.assertTrue(bb.capacity() == 0 && order(bb, ib1, areaB(helper)).refusal() == DirectedOrders.Refusal.CAP, "B's order passed A's pending place: " + bb.capacity());
            // the colony's own occupied place counts too
            helper.assertTrue(order(ba, ia2, areaA(helper)).refusal() == DirectedOrders.Refusal.CAP, "A's second order passed its own occupied place");

            // the claim takes over the reserved place: no second one
            final LabourClaim ca = ba.claimDirected(ia1, sa.order());
            helper.assertTrue(ca != null, "A's citizen got no claim on his reserved place");
            helper.assertTrue(ba.places() == 1 && worldPlaces(ba, bb) == 1, "the claim counts beside its reservation: A " + ba.places());

            // world cap 2: B gets the other place, A no third
            LabourSettings.override("worldMax", 2);
            final DirectedOrders.Start sb = order(bb, ib1, areaB(helper));
            helper.assertTrue(sb.order() != null, "B got no place under a world cap of 2: " + sb.refusal());
            helper.assertTrue(order(ba, ia2, areaA(helper)).refusal() == DirectedOrders.Refusal.CAP && worldPlaces(ba, bb) == 2, "a third place under a world cap of 2");

            // the world cap goes down to 1: B's pending citizen gets no claim, the claims stay under the cap
            LabourSettings.override("worldMax", 1);
            helper.assertTrue(bb.claimDirected(ib1, sb.order()) == null, "B's citizen got a claim past the world cap");
            helper.assertTrue(sb.order().why() == DirectedOrder.Why.NO_PLACE, "B's order does not say why it waits: " + sb.order().why());
            helper.assertTrue(ba.claims().size() + bb.claims().size() == 1, "claims in the world: " + (ba.claims().size() + bb.claims().size()));

            // A's place is free: B's citizen takes it over
            ba.release(ca, false, false);
            ba.directed().cancel(sa.order().id());
            final LabourClaim cb = bb.claimDirected(ib1, sb.order());
            helper.assertTrue(cb != null && ba.claims().isEmpty() && bb.claims().size() == 1, "B's citizen did not get the freed place");
            bb.release(cb, false, false);
            bb.directed().cancel(sb.order().id());
            helper.assertTrue(ba.places() == 0 && bb.places() == 0, "places left after every order ended: A " + ba.places() + ", B " + bb.places());
            LabourGameTests.end();
            helper.succeed();
        });
    }

    /**
     * R6-F1 / N46: a worker's own tool takes no labour place. The colony's one place is held by a directed order; the worker's tool claim is granted all the same and
     * the places stay at 1 of 1 (a jobless citizen is still kept out, and is told why: places used of the cap). A second tool claim while he holds one is refused
     * (his request is cancelled only after a claim succeeds, see AbstractEntityAIBasic).
     */
    static void ownToolUnderAFullCap(final GameTestHelper helper)
    {
        LabourGameTests.begin();
        LabourSettings.override("total", 1);
        final RsFixture f = LabourGameTests.found(helper, "teamwork own tool", 2);
        final Colony colony = (Colony) f.colony;
        LabourGameTests.forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        helper.runAfterDelay(100, () -> {
            final ICitizenData idle = f.spawn(new BlockPos(14, 1, 16), true);
            final ICitizenData other = f.spawn(new BlockPos(16, 1, 16), true);
            final ICitizenData worker = f.spawn(new BlockPos(18, 1, 16), true);
            helper.assertTrue(f.builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(worker), "the worker was not assigned");
            final LabourBoard board = colony.getLabourBoard();

            final DirectedOrders.Start start = order(board, idle, areaA(helper));
            helper.assertTrue(start.order() != null, "the order was refused: " + start.refusal());
            helper.assertTrue(board.placesFull() && board.places() == 1 && board.capacity() == 1, "places " + board.places() + " of " + board.capacity());
            helper.assertTrue(board.noPlaceReason(other) != null, "the jobless citizen kept out by the full places has no reason");
            helper.assertTrue(board.noPlaceReason(idle) == null && board.noPlaceReason(worker) == null, "a reason for a citizen on an order or with a job");

            final LabourClaim tool = board.claimForTool(worker, Items.WOODEN_PICKAXE);
            helper.assertTrue(tool != null && tool.source() == LabourClaim.Source.TOOL, "the worker's own tool waits behind the full places: " + tool);
            helper.assertTrue(board.places() == 1, "the tool took a labour place: " + board.places());
            helper.assertTrue(board.offer(other, false, true, true) == null, "a jobless citizen got a place past the cap");
            helper.assertTrue(board.claimForTool(worker, Items.WOODEN_PICKAXE) == null, "a second claim for one worker");

            board.release(tool, false, false);
            board.directed().cancel(start.order().id());
            helper.assertTrue(board.places() == 0 && board.noPlaceReason(other) == null, "places left: " + board.places());
            LabourGameTests.end();
            helper.succeed();
        });
    }

    /**
     * N45: a helper recalled before his hand-in keeps the goods he carries for the request on record; they count for it until he hands them in. A request of 8 logs,
     * the first helper carries 5 and is recalled: the second gets a lot of exactly 3, the first's hand-in is exactly his 5, the lots add up to the 8 asked. Nothing is
     * moved by the claims: 5 logs on the first, none on the second, none in the hut, the tree whole.
     */
    static void recalledCargoCounts(final GameTestHelper helper)
    {
        LabourGameTests.begin();
        LabourSettings.override("perRequest", 2);
        LabourSettings.override("share", 100);
        final RsFixture f = LabourGameTests.found(helper, "teamwork recalled", 2);
        final Colony colony = (Colony) f.colony;
        LabourGameTests.forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        helper.setBlock(new BlockPos(22, 1, 20), net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState());
        LabourGameTests.tree(helper, 34, 20, 5);
        helper.runAfterDelay(100, () -> {
            final ICitizenData first = f.spawn(new BlockPos(14, 1, 16), true);
            final ICitizenData second = f.spawn(new BlockPos(18, 1, 16), true);
            helper.assertTrue(InventoryUtils.addItemStackToItemHandlerWithResult(first.getInventory(), new ItemStack(Items.OAK_LOG, 5)).isEmpty(), "no room for 5 logs");
            final IToken<?> token = LabourGameTests.ask(f.builder, new ItemStack(Items.OAK_LOG, 8));
            final LabourBoard board = colony.getLabourBoard();
            board.invalidate();
            final LabourClaim claim = board.offer(first, false, true, false);
            helper.assertTrue(claim != null && token.equals(claim.request()) && claim.amount() == 8, "no lot of 8 for the first helper: " + claim);

            // recalled: the claim goes, the logs stay his and on record for the request
            board.release(claim, false, false);
            final LabourCargo.Entry kept = board.cargoOf(first.getId());
            helper.assertTrue(kept != null && token.getIdentifier().equals(kept.request()), "the 5 logs are not on record for the request: " + kept);

            // never gathered twice: the second helper's lot is the missing 3
            final LabourClaim rest = board.offer(second, false, true, false);
            helper.assertTrue(rest != null && token.equals(rest.request()) && rest.amount() == 3, "the second helper's lot is not the missing 3: " + rest);

            // the hand-in is exactly what he carries
            final LabourClaim handIn = board.offer(first, false, true, false);
            helper.assertTrue(handIn != null && handIn.cargo() && token.equals(handIn.request()) && handIn.amount() == 5, "the hand-in is not his 5: " + handIn);
            int lots = 0;
            for (final LabourClaim c : board.claims())
            {
                lots += token.equals(c.request()) ? c.amount() : 0;
            }
            helper.assertTrue(lots == 8, "the lots on the request add up to " + lots + " for 8");
            helper.assertTrue(logs(first) == 5 && logs(second) == 0 && LabourGameTests.inHut(f.builder, Items.OAK_LOG) == 0 && LabourGameTests.logsLeft(helper, 34, 20, 5) == 5,
              "goods moved: first " + logs(first) + ", second " + logs(second) + ", hut " + LabourGameTests.inHut(f.builder, Items.OAK_LOG));

            board.release(rest, false, false);
            board.release(handIn, false, false);
            LabourGameTests.end();
            helper.succeed();
        });
    }

    /**
     * N47: the world cap is shared between colonies. World cap 2: A takes both places first; B asks and waits; the place A frees goes to B (A takes no new one while B
     * waits), then each holds one. The same with the colonies swapped. World cap 1: the place goes back and forth, to whichever colony waits, and never more than 1
     * is taken.
     */
    static void worldCapShared(final GameTestHelper helper)
    {
        LabourGameTests.begin();
        LabourSettings.override("total", 8);
        LabourSettings.override("worldMax", 2);
        // (both jobless citizens of a colony may labour at once: the default share lets one of two, and the world cap is what is tested)
        LabourSettings.override("share", 100);
        final RsFixture a = LabourGameTests.found(helper, "teamwork fair a", 2);
        final Colony colonyA = (Colony) a.colony;
        LabourGameTests.forceArea(a.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        final Colony colonyB = LabourGameTests.foundAt(helper, "teamwork fair b", DX);
        helper.runAfterDelay(100, () -> {
            final ICitizenData ia1 = a.spawn(new BlockPos(14, 1, 16), true);
            final ICitizenData ia2 = a.spawn(new BlockPos(16, 1, 16), true);
            final ICitizenData ib1 = spawnB(helper, colonyB, DX + 14, 16);
            final ICitizenData ib2 = spawnB(helper, colonyB, DX + 16, 16);
            final LabourBoard ba = colonyA.getLabourBoard();
            final LabourBoard bb = colonyB.getLabourBoard();
            sharedTwo(helper, ba, bb, ia1, ia2, ib1, areaA(helper), areaB(helper), "A first");
            LabourBoard.forgetWaiting();
            sharedTwo(helper, bb, ba, ib1, ib2, ia1, areaB(helper), areaA(helper), "B first");
            LabourBoard.forgetWaiting();

            // world cap 1: back and forth
            LabourSettings.override("worldMax", 1);
            final DirectedOrders.Start a1 = order(ba, ia1, areaA(helper));
            helper.assertTrue(a1.order() != null && order(bb, ib1, areaB(helper)).refusal() == DirectedOrders.Refusal.CAP, "cap 1: A first: " + a1.refusal());
            ba.directed().cancel(a1.order().id());
            helper.assertTrue(ba.capacity() == 0 && bb.capacity() == 1, "cap 1: the freed place is not B's: A " + ba.capacity() + ", B " + bb.capacity());
            final DirectedOrders.Start b1 = order(bb, ib1, areaB(helper));
            helper.assertTrue(b1.order() != null && order(ba, ia1, areaA(helper)).refusal() == DirectedOrders.Refusal.CAP && worldPlaces(ba, bb) == 1, "cap 1: B's turn: " + b1.refusal());
            bb.directed().cancel(b1.order().id());
            helper.assertTrue(bb.capacity() == 0 && ba.capacity() == 1, "cap 1: the freed place is not A's: A " + ba.capacity() + ", B " + bb.capacity());
            final DirectedOrders.Start a2 = order(ba, ia1, areaA(helper));
            helper.assertTrue(a2.order() != null && worldPlaces(ba, bb) == 1, "cap 1: A's turn: " + a2.refusal());
            ba.directed().cancel(a2.order().id());
            helper.assertTrue(ba.places() == 0 && bb.places() == 0, "places left after every order ended");
            LabourGameTests.end();
            helper.succeed();
        });
    }

    /**
     * World cap 2: {@code first} takes both places, {@code late} asks and waits, the place {@code first} frees is {@code late}'s, then each holds one. Every order ends.
     */
    private static void sharedTwo(final GameTestHelper helper, final LabourBoard first, final LabourBoard late, final ICitizenData f1, final ICitizenData f2,
      final ICitizenData l1, final DirectedOrder.Area firstArea, final DirectedOrder.Area lateArea, final String what)
    {
        final DirectedOrders.Start s1 = order(first, f1, firstArea);
        final DirectedOrders.Start s2 = order(first, f2, firstArea);
        helper.assertTrue(s1.order() != null && s2.order() != null && first.places() == 2, what + ": the first colony did not get both free places: " + s1.refusal() + " " + s2.refusal());
        helper.assertTrue(order(late, l1, lateArea).refusal() == DirectedOrders.Refusal.CAP && late.capacity() == 0, what + ": a place past the world cap");
        // the first colony frees a place: it goes to the colony that waits
        first.directed().cancel(s2.order().id());
        // (round 8, N54: the first colony keeps its equal share, 1 of 2, and takes no new place)
        helper.assertTrue(first.places() == 1 && late.capacity() == 1 && first.capacity() == 1 && first.placesFull(),
          what + ": the freed place is not the waiting colony's: first " + first.capacity() + ", late " + late.capacity());
        final DirectedOrders.Start l = order(late, l1, lateArea);
        helper.assertTrue(l.order() != null, what + ": the waiting colony got no place: " + l.refusal());
        helper.assertTrue(first.capacity() == 1 && late.capacity() == 1 && worldPlaces(first, late) == 2, what + ": not one place each: first " + first.capacity() + ", late "
                                                                                                            + late.capacity());
        first.directed().cancel(s1.order().id());
        late.directed().cancel(l.order().id());
    }

    /**
     * A courier's short load (Sol r5): 10 of 16 logs went into the builder hut. They are held for the request they were fetched for: a second request of the hut
     * for 10 logs is not handed them, also after the ledger is saved and loaded and the memory of the short load is gone. The rest is asked for once: exactly 6,
     * and a full delivery after the short one keeps both counted. Before: 16 in the rack. After: 16 in the hut, 14 in the rack (20 added, 6 left the bag).
     */
    @SuppressWarnings("unchecked")
    static void partialReceipt(final GameTestHelper helper)
    {
        RsFlags.overrideReservations(true);
        try
        {
            final RsFixture f = RsFixture.found(helper, "teamwork partial", 4);
            f.stock(0, new ItemStack(Items.OAK_LOG, 16));
            final ReservationLedger ledger = f.ledger();
            final BlockPos hutPos = f.builder.getID();
            final IToken<?> mine = f.builder.createRequest(new Stack(new ItemStack(Items.OAK_LOG, 16), 16, 16), false);
            final IRequest<?> request = f.request(mine);
            helper.assertTrue(request != null && request.getChildren().size() == 1, "the request is not served by one delivery: " + f.describe(mine));
            final IToken<?> delivery = request.getChildren().iterator().next();

            // the courier took 16, 10 went in
            RsCourierHooks.stockTaken(f.manager(), f.request(delivery));
            int left = 16;
            final var rack = f.rack(0).getInventory();
            for (int slot = 0; slot < rack.getSlots() && left > 0; slot++)
            {
                left -= rack.extractItem(slot, left, false).getCount();
            }
            helper.assertTrue(left == 0, "the rack did not have 16 logs");
            helper.assertTrue(InventoryUtils.addItemStackToItemHandlerWithResult(f.builder.getTileEntity().getInventory(), new ItemStack(Items.OAK_LOG, 10)).isEmpty(),
              "the hut could not take 10 logs");
            RsCourierHooks.arrivedPartly(f.manager(), (IRequest<? extends Delivery>) f.request(delivery), 10);
            helper.assertTrue(RsCourierHooks.handedOutFor(f.manager(), mine) == 10, "the 10 that arrived are not held for the request: " + ledger.reservationsOf(mine));
            helper.assertTrue(ledger.reservedStockExcluding(hutPos, s -> s.is(Items.OAK_LOG), new StandardToken()) == 10, "another request could be handed the 10");
            helper.assertTrue(RsCourierHooks.outstanding(f.manager(), (IRequest<? extends com.minecolonies.api.colony.requestsystem.requestable.IDeliverable>) f.request(mine)) == 6,
              "the rest is not 6");

            // the short delivery fails, as the courier's does: the request is handed out again and keeps its hold
            f.manager().updateRequestState(delivery, RequestState.FAILED);
            helper.assertTrue(RsCourierHooks.handedOutFor(f.manager(), mine) == 10, "the hold went with the failed delivery: " + ledger.reservationsOf(mine));

            // save and load the ledger; the memory of the short load is gone
            final CompoundTag saved = ledger.write(helper.getLevel().registryAccess(), f.manager().getFactoryController());
            ledger.read(helper.getLevel().registryAccess(), f.manager().getFactoryController(), saved);
            RsCourierHooks.forgetCredit(f.manager(), mine);
            helper.assertTrue(RsCourierHooks.outstanding(f.manager(), (IRequest<? extends com.minecolonies.api.colony.requestsystem.requestable.IDeliverable>) f.request(mine)) == 6,
              "after the reload the rest is not 6");

            // a competing request of the same hut: the 10 in the hut are not its
            final IToken<?> rival = f.builder.createRequest(new Stack(new ItemStack(Items.OAK_LOG, 10), 10, 10), false);
            final IRequest<?> rivalRequest = f.request(rival);
            helper.assertTrue(rivalRequest != null && rivalRequest.getState() != RequestState.RESOLVED && rivalRequest.getState() != RequestState.COMPLETED
                                && rivalRequest.getChildren().isEmpty(), "the rival was handed the logs held for the first request: " + f.describe(rival));
            helper.assertTrue(LabourGameTests.inHut(f.builder, Items.OAK_LOG) == 10, "the hut has " + LabourGameTests.inHut(f.builder, Items.OAK_LOG));

            // the rest comes once: one delivery of exactly 6
            f.stock(0, new ItemStack(Items.OAK_LOG, 20));
            f.manager().reassignRequest(mine, ImmutableList.of());
            final IRequest<?> again = f.request(mine);
            helper.assertTrue(again != null && again.getChildren().size() == 1, "the rest is not one delivery: " + f.describe(mine));
            final IToken<?> rest = again.getChildren().iterator().next();
            final int restCount = ((IRequest<? extends Delivery>) f.request(rest)).getRequest().getStack().getCount();
            helper.assertTrue(restCount == 6, "the rest delivery brings " + restCount + ", not the missing 6");

            // it arrives in full: both arrivals count for the request
            RsCourierHooks.stockTaken(f.manager(), f.request(rest));
            left = 6;
            for (int slot = 0; slot < rack.getSlots() && left > 0; slot++)
            {
                left -= rack.extractItem(slot, left, false).getCount();
            }
            helper.assertTrue(left == 0, "the rack did not have 6 logs");
            helper.assertTrue(InventoryUtils.addItemStackToItemHandlerWithResult(f.builder.getTileEntity().getInventory(), new ItemStack(Items.OAK_LOG, 6)).isEmpty(),
              "the hut could not take 6 logs");
            RsCourierHooks.arrived(f.manager(), (IRequest<? extends Delivery>) f.request(rest));
            helper.assertTrue(RsCourierHooks.handedOutFor(f.manager(), mine) == 16, "after the full delivery the request holds " + RsCourierHooks.handedOutFor(f.manager(), mine));
            helper.assertTrue(RsCourierHooks.creditOf(f.manager(), mine) == 6, "the full delivery wiped or miscounted the credit: " + RsCourierHooks.creditOf(f.manager(), mine));
            final int hut = LabourGameTests.inHut(f.builder, Items.OAK_LOG);
            final int stock = f.physical(s -> s.is(Items.OAK_LOG));
            helper.assertTrue(hut == 16 && stock == 14, "after: hut " + hut + " rack " + stock + " (16 and 14 expected)");
            RsCourierHooks.forgetCredit(f.manager(), mine);
        }
        finally
        {
            RsFlags.overrideReservations(null);
        }
        helper.succeed();
    }

    /**
     * Sol H1 and R7-F3: automatic labour only, no directed order. Share 0 (one jobless labours at once): the next one waits for his turn with the share reason, not
     * the place reason. World cap 2: A's automatic claims hold both places; B's jobless citizen asks and is refused, and B waits (A is held at its equal share of
     * 1 at once); A's freed place is B's, A's next citizen does not get it. World cap 1: the same, A is held at 0.
     */
    static void automaticLabourWaits(final GameTestHelper helper)
    {
        LabourGameTests.begin();
        LabourSettings.override("total", 8);
        LabourSettings.override("worldMax", 2);
        LabourSettings.override("share", 0);
        final RsFixture a = LabourGameTests.found(helper, "teamwork auto a", 2);
        final Colony colonyA = (Colony) a.colony;
        LabourGameTests.forceArea(a.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        helper.setBlock(new BlockPos(22, 1, 20), net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState());
        LabourGameTests.tree(helper, 34, 20, 5);
        final Colony colonyB = LabourGameTests.foundAt(helper, "teamwork auto b", DX);
        helper.runAfterDelay(100, () -> {
            final ICitizenData ia1 = a.spawn(new BlockPos(14, 1, 16), true);
            final ICitizenData ia2 = a.spawn(new BlockPos(16, 1, 16), true);
            final ICitizenData ia3 = a.spawn(new BlockPos(18, 1, 16), true);
            final ICitizenData ib1 = spawnB(helper, colonyB, DX + 14, 16);
            final LabourBoard ba = colonyA.getLabourBoard();
            final LabourBoard bb = colonyB.getLabourBoard();
            LabourGameTests.ask(a.builder, new ItemStack(Items.OAK_LOG, 4));
            LabourGameTests.ask(a.builder, new ItemStack(Items.OAK_LOG, 4));
            ba.invalidate();

            // R7-F3: places are free, the jobless share is at work: the reason is the share
            final LabourClaim c1 = ba.offer(ia1, false, true, false);
            helper.assertTrue(c1 != null && ba.places() == 1, "A's first labourer got no claim: " + c1);
            helper.assertTrue(ba.offer(ia2, false, true, false) == null, "a second labourer past a share of one at once");
            helper.assertTrue(ba.shareReason(ia2) != null && ba.noPlaceReason(ia2) == null && ba.labourWaitReason(ia2) != null,
              "the share wait has no share reason: share " + ba.shareReason(ia2) + ", place " + ba.noPlaceReason(ia2));
            helper.assertTrue(ba.shareReason(ia1) == null, "a share reason for the citizen who labours");

            // world cap 2: A's automatic claims take both places
            LabourSettings.override("share", 100);
            final LabourClaim c2 = ba.offer(ia2, false, true, false);
            helper.assertTrue(c2 != null && ba.places() == 2, "A's second labourer got no claim: " + c2);
            helper.assertTrue(ba.shareReason(ia3) == null && ba.noPlaceReason(ia3) != null, "the place wait shows the share reason or none");

            // H1: B's automatic labour asks while A holds every place: B waits for one
            helper.assertTrue(bb.offer(ib1, false, true, false) == null && bb.capacity() == 0, "B got a place past the world cap");
            helper.assertTrue(ba.capacity() == 1 && ba.heldForWaiting(), "B's refused automatic labour does not wait: A's places " + ba.capacity());
            // A frees a place: it is B's, A's next labourer does not take it
            ba.release(c2, false, false);
            helper.assertTrue(bb.capacity() == 1 && !bb.placesFull(), "the freed place is not B's: B " + bb.capacity());
            helper.assertTrue(ba.offer(ia3, false, true, false) == null && ba.places() == 1 && ba.placesFull(), "A took back the place B waits for: A " + ba.places());

            // world cap 1: A holds its one place, B asks and waits, the place A frees is B's
            LabourBoard.forgetWaiting();
            LabourSettings.override("worldMax", 1);
            helper.assertTrue(bb.offer(ib1, false, true, false) == null && bb.capacity() == 0, "cap 1: B got a place past the world cap");
            helper.assertTrue(ba.capacity() == 0, "cap 1: B's refused automatic labour does not wait: A " + ba.capacity());
            ba.release(c1, false, false);
            helper.assertTrue(bb.capacity() == 1 && ba.capacity() == 0, "cap 1: the freed place is not B's: A " + ba.capacity() + ", B " + bb.capacity());
            helper.assertTrue(ba.offer(ia3, false, true, false) == null && ba.places() == 0, "cap 1: A took back the place B waits for");
            LabourGameTests.end();
            helper.succeed();
        });
    }

    /**
     * N54: the wait rule never takes a colony below its equal share. World cap 8: A holds all 8 places (four orders of two); B's automatic labour asks and waits.
     * A is held at 4, not 0: every place it frees is B's until it is down to 4, and B keeps its turn after its first place, so A refills none of them. Both end
     * with 4, after one drain of A's leases.
     */
    static void equalShareFloor(final GameTestHelper helper)
    {
        LabourGameTests.begin();
        LabourSettings.override("total", 16);
        LabourSettings.override("worldMax", 8);
        LabourSettings.override("share", 100);
        final RsFixture a = LabourGameTests.found(helper, "teamwork share a", 2);
        final Colony colonyA = (Colony) a.colony;
        LabourGameTests.forceArea(a.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        final Colony colonyB = LabourGameTests.foundAt(helper, "teamwork share b", DX);
        helper.runAfterDelay(100, () -> {
            final List<ICitizenData> as = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++)
            {
                as.add(a.spawn(new BlockPos(10 + i, 1, 16), true));
            }
            final List<ICitizenData> bs = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++)
            {
                bs.add(spawnB(helper, colonyB, DX + 10 + i, 16));
            }
            final LabourBoard ba = colonyA.getLabourBoard();
            final LabourBoard bb = colonyB.getLabourBoard();
            final List<DirectedOrder> ordersA = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++)
            {
                final DirectedOrders.Start start = ba.directed().start(List.of(as.get(2 * i).getId(), as.get(2 * i + 1).getId()), DirectedOrder.Resource.WOOD, 4, areaA(helper), null);
                helper.assertTrue(start.order() != null, "A's order " + i + " refused: " + start.refusal());
                ordersA.add(start.order());
            }
            helper.assertTrue(ba.places() == 8, "A holds " + ba.places() + " places, not 8");

            // B's automatic labour asks and waits: A is held at its equal share, not at 0
            helper.assertTrue(bb.offer(bs.get(0), false, true, false) == null && bb.capacity() == 0, "B got a place past the world cap");
            helper.assertTrue(ba.capacity() == 4, "A is not held at its equal share of 4: " + ba.capacity());

            // A drains: each freed place is B's, A takes none back
            ba.directed().cancel(ordersA.get(0).id());
            helper.assertTrue(ba.places() == 6 && bb.capacity() == 2 && ba.capacity() == 4, "after one order: A " + ba.places() + " of " + ba.capacity() + ", B " + bb.capacity());
            ba.directed().cancel(ordersA.get(1).id());
            helper.assertTrue(ba.places() == 4 && bb.capacity() == 4 && ba.capacity() == 4, "after two orders: A " + ba.places() + " of " + ba.capacity() + ", B " + bb.capacity());

            // B takes its first place and keeps its turn: A refills nothing
            final DirectedOrders.Start b1 = order(bb, bs.get(0), areaB(helper));
            helper.assertTrue(b1.order() != null, "B got no freed place: " + b1.refusal());
            helper.assertTrue(ba.capacity() == 4 && order(ba, as.get(0), areaA(helper)).refusal() == DirectedOrders.Refusal.CAP, "A refilled a place B waits for: A " + ba.capacity());
            final DirectedOrders.Start b3 = bb.directed().start(List.of(bs.get(1).getId(), bs.get(2).getId(), bs.get(3).getId()), DirectedOrder.Resource.WOOD, 4, areaB(helper), null);
            helper.assertTrue(b3.order() != null, "B got no second to fourth place: " + b3.refusal());
            helper.assertTrue(ba.places() == 4 && bb.places() == 4 && ba.capacity() == 4 && bb.capacity() == 4 && worldPlaces(ba, bb) == 8,
              "not 4 each: A " + ba.places() + " of " + ba.capacity() + ", B " + bb.places() + " of " + bb.capacity());

            ba.directed().cancel(ordersA.get(2).id());
            ba.directed().cancel(ordersA.get(3).id());
            bb.directed().cancel(b1.order().id());
            bb.directed().cancel(b3.order().id());
            helper.assertTrue(ba.places() == 0 && bb.places() == 0, "places left after every order ended");
            LabourGameTests.end();
            helper.succeed();
        });
    }

    /**
     * Sol M2, N55 and R7-F4: a helper carries 20 logs for a request of 24 and hands in 12. The 8 he still carries stay on record (the request needs 12 more): his
     * own hut's dump leaves him all 8, the other helper's lot is the missing 4, his hand-in is his 8. Three neutral ends of the hand-in (a meal, the night, his own
     * work) keep the record. Once the request is gone, nothing is kept out of his hut. Before: 20 logs on him. After: 12 in the hut, 8 on him, none made or lost.
     */
    static void cargoCustody(final GameTestHelper helper)
    {
        LabourGameTests.begin();
        LabourSettings.override("perRequest", 2);
        LabourSettings.override("share", 100);
        final RsFixture f = LabourGameTests.found(helper, "teamwork custody", 2);
        final Colony colony = (Colony) f.colony;
        LabourGameTests.forceArea(f.level, helper.absolutePos(new BlockPos(-8, 1, -8)), helper.absolutePos(new BlockPos(48, 1, 32)));
        helper.setBlock(new BlockPos(22, 1, 20), net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState());
        LabourGameTests.tree(helper, 34, 20, 5);
        helper.runAfterDelay(100, () -> {
            final ICitizenData carrier = f.spawn(new BlockPos(14, 1, 16), true);
            final ICitizenData other = f.spawn(new BlockPos(18, 1, 16), true);
            helper.assertTrue(InventoryUtils.addItemStackToItemHandlerWithResult(carrier.getInventory(), new ItemStack(Items.OAK_LOG, 20)).isEmpty(), "no room for 20 logs");
            final IToken<?> token = LabourGameTests.ask(f.builder, new ItemStack(Items.OAK_LOG, 24));
            final LabourBoard board = colony.getLabourBoard();
            board.invalidate();
            final LabourClaim claim = board.offer(carrier, false, true, false);
            helper.assertTrue(claim != null && token.equals(claim.request()) && claim.amount() == 24, "no lot of 24: " + claim);
            helper.assertTrue(board.handInFor(claim) == 24, "a recalled hand-in would give " + board.handInFor(claim) + ", not what the request needs");

            // he hands in 12 of his 20
            int left = 12;
            final var bag = carrier.getInventory();
            for (int slot = 0; slot < bag.getSlots() && left > 0; slot++)
            {
                if (bag.getStackInSlot(slot).is(Items.OAK_LOG))
                {
                    final ItemStack taken = bag.extractItem(slot, left, false);
                    left -= taken.getCount();
                    helper.assertTrue(InventoryUtils.addItemStackToItemHandlerWithResult(f.builder.getItemHandlerCap(), taken).isEmpty(), "the hut took no logs");
                }
            }
            board.handedIn(claim, 12);
            board.release(claim, true, false);

            // N55: the 8 beyond the hand-in stay on record while the request needs 12 more; his own hut's dump keeps them out
            final LabourCargo.Entry kept = board.cargoOf(carrier.getId());
            helper.assertTrue(kept != null && token.getIdentifier().equals(kept.request()), "the 8 beyond the hand-in lost their record: " + kept);
            helper.assertTrue(board.cargoKeep(carrier) == 8, "his own hut's dump would take " + (8 - board.cargoKeep(carrier)) + " of the 8");

            // never gathered twice: the other helper's lot is the missing 4
            final LabourClaim rest = board.offer(other, false, true, false);
            helper.assertTrue(rest != null && token.equals(rest.request()) && rest.amount() == 4, "the other helper's lot is not the missing 4: " + rest);

            // M2: three neutral ends of his hand-in keep the record
            for (int i = 0; i < 3; i++)
            {
                final LabourClaim handIn = board.offer(carrier, false, true, false);
                helper.assertTrue(handIn != null && handIn.cargo() && handIn.amount() == 8 && board.handInFor(handIn) == 8, "hand-in " + i + " is not his 8: " + handIn);
                board.release(handIn, false, false);
                helper.assertTrue(board.cargoOf(carrier.getId()) != null, "a neutral end " + (i + 1) + " of the hand-in dropped the record");
            }

            // R7-F4: once the request is gone, nothing of it is kept out of his hut
            board.release(rest, false, false);
            f.manager().updateRequestState(token, RequestState.CANCELLED);
            board.invalidate();
            helper.assertTrue(board.cargoKeep(carrier) == 0, "he keeps " + board.cargoKeep(carrier) + " logs back for a request that is gone");
            helper.assertTrue(logs(carrier) == 8 && logs(other) == 0 && LabourGameTests.inHut(f.builder, Items.OAK_LOG) == 12,
              "goods moved: carrier " + logs(carrier) + ", other " + logs(other) + ", hut " + LabourGameTests.inHut(f.builder, Items.OAK_LOG));
            LabourGameTests.end();
            helper.succeed();
        });
    }

    /**
     * Sol M3: 10 of 16 logs arrived in a short load. A day later the request still waits for its last 6: the 10 stay held for it (another request of the hut is not
     * handed them, the rest is still 6). Once the request no longer waits, the old hold ages out as before.
     */
    @SuppressWarnings("unchecked")
    static void receiptWaits(final GameTestHelper helper)
    {
        RsFlags.overrideReservations(true);
        try
        {
            final RsFixture f = RsFixture.found(helper, "teamwork receipt waits", 4);
            f.stock(0, new ItemStack(Items.OAK_LOG, 16));
            final ReservationLedger ledger = f.ledger();
            final IToken<?> mine = f.builder.createRequest(new Stack(new ItemStack(Items.OAK_LOG, 16), 16, 16), false);
            final IRequest<?> request = f.request(mine);
            helper.assertTrue(request != null && request.getChildren().size() == 1, "the request is not served by one delivery: " + f.describe(mine));
            final IToken<?> delivery = request.getChildren().iterator().next();
            RsCourierHooks.stockTaken(f.manager(), f.request(delivery));
            int left = 16;
            final var rack = f.rack(0).getInventory();
            for (int slot = 0; slot < rack.getSlots() && left > 0; slot++)
            {
                left -= rack.extractItem(slot, left, false).getCount();
            }
            helper.assertTrue(left == 0, "the rack did not have 16 logs");
            helper.assertTrue(InventoryUtils.addItemStackToItemHandlerWithResult(f.builder.getTileEntity().getInventory(), new ItemStack(Items.OAK_LOG, 10)).isEmpty(),
              "the hut could not take 10 logs");
            RsCourierHooks.arrivedPartly(f.manager(), (IRequest<? extends Delivery>) f.request(delivery), 10);
            f.manager().updateRequestState(delivery, RequestState.FAILED);
            helper.assertTrue(RsCourierHooks.handedOutFor(f.manager(), mine) == 10, "the 10 that arrived are not held for the request: " + ledger.reservationsOf(mine));

            // a day later: the request still waits, the hold stays (the memory of the short load is gone too)
            final var manager = (com.minecolonies.core.colony.requestsystem.management.manager.StandardRequestManager) f.manager();
            final long maxAge = com.minecolonies.core.colony.requestsystem.management.manager.StandardRequestManager.HANDOUT_MAX_AGE;
            final long late = helper.getLevel().getGameTime() + maxAge + 1;
            helper.assertTrue(manager.waitsForItems(mine), "the request does not wait: " + f.describe(mine));
            ledger.sweepStaleHandouts(late, maxAge, manager::waitsForItems);
            RsCourierHooks.forgetCredit(f.manager(), mine);
            helper.assertTrue(RsCourierHooks.handedOutFor(f.manager(), mine) == 10, "the hold of a waiting request aged out: " + ledger.reservationsOf(mine));
            helper.assertTrue(RsCourierHooks.outstanding(f.manager(), (IRequest<? extends com.minecolonies.api.colony.requestsystem.requestable.IDeliverable>) f.request(mine)) == 6,
              "a day later the rest is not 6");

            // the request no longer waits: the old hold ages out
            f.manager().updateRequestState(mine, RequestState.CANCELLED);
            ledger.sweepStaleHandouts(late, maxAge, manager::waitsForItems);
            helper.assertTrue(RsCourierHooks.handedOutFor(f.manager(), mine) == 0, "the hold of a cancelled request stays: " + ledger.reservationsOf(mine));
            helper.assertTrue(LabourGameTests.inHut(f.builder, Items.OAK_LOG) == 10, "the hut has " + LabourGameTests.inHut(f.builder, Items.OAK_LOG));
        }
        finally
        {
            RsFlags.overrideReservations(null);
        }
        helper.succeed();
    }
}
