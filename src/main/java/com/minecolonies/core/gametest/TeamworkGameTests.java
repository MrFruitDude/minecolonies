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
     * One admission policy (Sol r5): a worker's own tool takes a labour place like a jobless labourer, and the world cap is a hard total across colonies. World cap
     * 1, two colonies: whichever asks first holds the one place, in both orders. World cap 0: nobody. World cap 2: one place per colony, a second claim in one
     * colony is refused.
     */
    static void admissionWorldCap(final GameTestHelper helper)
    {
        LabourGameTests.begin();
        LabourSettings.override("total", 8);
        LabourSettings.override("worldMax", 1);
        final RsFixture a = LabourGameTests.found(helper, "teamwork cap a", 2);
        final Colony colonyA = (Colony) a.colony;
        final int dx = 240;
        final Colony colonyB = LabourGameTests.foundAt(helper, "teamwork cap b", dx);
        helper.runAfterDelay(100, () -> {
            final IBuilding hutB = MinecoloniesGameTests.placeProductionBuilding(helper, colonyB, ModBlocks.blockHutBuilder, new BlockPos(dx + 8, 1, 2),
              "fundamentals/builder1.blueprint");
            helper.runAfterDelay(40, () -> {
                final ICitizenData wa = a.spawn(new BlockPos(14, 1, 16), true);
                final ICitizenData wb = colonyB.getCitizenManager().spawnOrCreateCivilian(null, a.level, List.of(helper.absolutePos(new BlockPos(dx + 14, 1, 16))), true);
                helper.assertTrue(wb != null && wb.getEntity().isPresent(), "citizen of B did not spawn");
                wb.getEntity().get().setNoAi(true);
                helper.assertTrue(a.builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(wa), "A's worker not assigned");
                helper.assertTrue(hutB.getModule(BuildingModules.BUILDER_WORK).assignCitizen(wb), "B's worker not assigned");
                final LabourBoard ba = colonyA.getLabourBoard();
                final LabourBoard bb = colonyB.getLabourBoard();

                // world cap 1, A first
                final LabourClaim ca = ba.claimForTool(wa, Items.WOODEN_PICKAXE);
                helper.assertTrue(ca != null, "A got no place in an empty world");
                helper.assertTrue(bb.capacity() == 0 && bb.claimForTool(wb, Items.WOODEN_PICKAXE) == null, "B got a second place under a world cap of 1: " + bb.capacity());
                helper.assertTrue(ba.placesFull() && ba.allowed() <= 1, "A's places: " + ba.places() + " of " + ba.capacity());
                ba.release(ca, false, false);

                // world cap 1, B first
                final LabourClaim cb = bb.claimForTool(wb, Items.WOODEN_PICKAXE);
                helper.assertTrue(cb != null, "B got no place after A gave its place back");
                helper.assertTrue(ba.capacity() == 0 && ba.allowed() == 0 && ba.claimForTool(wa, Items.WOODEN_PICKAXE) == null, "A got a second place under a world cap of 1");
                bb.release(cb, false, false);

                // world cap 0: nobody
                LabourSettings.override("worldMax", 0);
                helper.assertTrue(ba.claimForTool(wa, Items.WOODEN_PICKAXE) == null && bb.claimForTool(wb, Items.WOODEN_PICKAXE) == null, "a place under a world cap of 0");

                // world cap 2: one place per colony while both hold claims
                LabourSettings.override("worldMax", 2);
                final LabourClaim ca2 = ba.claimForTool(wa, Items.WOODEN_PICKAXE);
                final LabourClaim cb2 = bb.claimForTool(wb, Items.WOODEN_PICKAXE);
                helper.assertTrue(ca2 != null && cb2 != null, "two colonies under a world cap of 2 did not get one place each");
                helper.assertTrue(ba.placesFull() && bb.placesFull() && ba.capacity() == 1 && bb.capacity() == 1,
                  "a colony may take a second place under a world cap of 2 shared by two: A " + ba.capacity() + ", B " + bb.capacity());
                ba.release(ca2, false, false);
                bb.release(cb2, false, false);
                helper.assertTrue(ba.places() == 0 && bb.places() == 0, "places left after every claim went");
                LabourGameTests.end();
                helper.succeed();
            });
        });
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
}
