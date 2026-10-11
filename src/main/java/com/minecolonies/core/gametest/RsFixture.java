package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
import com.minecolonies.core.colony.requestsystem.management.IStandardRequestManager;
import com.minecolonies.core.colony.requestsystem.reservation.ReservationLedger;
import com.minecolonies.core.colony.requestsystem.wait.RequestWaitTracker;
import com.minecolonies.core.tileentities.TileEntityRack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A colony with a builder hut and a warehouse with racks, for the request-system redesign checks.
 * <p>
 * Layout (relative to the test): builder at (8,1,2), warehouse at (16,1,2) with its racks on a grid at y=1, z=10.., other
 * huts are placed by the test. The whole pad stays loaded.
 */
final class RsFixture
{
    final GameTestHelper     helper;
    final ServerLevel        level;
    final IColony            colony;
    final IBuilding          builder;
    final BuildingWareHouse  warehouse;
    final List<BlockPos>     racks = new ArrayList<>();

    /**
     * The mock player that keeps the colony active (its request system only ticks while a player is close).
     */
    net.minecraft.server.level.ServerPlayer player;

    private RsFixture(final GameTestHelper helper, final IColony colony, final IBuilding builder, final BuildingWareHouse warehouse)
    {
        this.helper = helper;
        this.level = helper.getLevel();
        this.colony = colony;
        this.builder = builder;
        this.warehouse = warehouse;
    }

    /**
     * @param helper    the test helper.
     * @param name      the colony name.
     * @param rackCount the number of racks in the warehouse.
     * @return the fixture.
     */
    static RsFixture found(final GameTestHelper helper, final String name, final int rackCount)
    {
        return found(helper, name, rackCount, true);
    }

    /**
     * @param active whether a player is close to the colony, so that it ticks (its request system included).
     */
    static RsFixture found(final GameTestHelper helper, final String name, final int rackCount, final boolean active)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, name);
        final BlockPos min = helper.absolutePos(new BlockPos(-8, 0, -8));
        final BlockPos max = helper.absolutePos(new BlockPos(48, 0, 32));
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++)
        {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++)
            {
                level.setChunkForced(cx, cz, true);
            }
        }
        final IBuilding builder = MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockHutBuilder, new BlockPos(8, 1, 2), "fundamentals/builder1.blueprint");
        final IBuilding house = MinecoloniesGameTests.placeProductionBuilding(helper, colony, ModBlocks.blockHutWareHouse, new BlockPos(16, 1, 2),
          "craftsmanship/storage/warehouse1.blueprint");
        helper.assertTrue(house instanceof BuildingWareHouse, "warehouse is " + house);
        final RsFixture fixture = new RsFixture(helper, colony, builder, (BuildingWareHouse) house);
        for (int r = 0; r < rackCount; r++)
        {
            final BlockPos rel = new BlockPos(-6 + 2 * (r % 12), 1, 10 + 2 * (r / 12));
            helper.setBlock(rel, ModBlocks.blockRack.defaultBlockState());
            final BlockPos abs = helper.absolutePos(rel);
            fixture.warehouse.registerBlockPosition(ModBlocks.blockRack, abs, level);
            fixture.racks.add(abs);
        }
        if (active)
        {
            fixture.player = MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper);
            final BlockPos anchor = colony.getCenter();
            fixture.player.teleportTo(anchor.getX() + 0.5D, anchor.getY() + 1, anchor.getZ() + 0.5D);
            colony.getPackageManager().addCloseSubscriber(fixture.player);
        }
        return fixture;
    }

    IBuilding place(final net.minecraft.world.level.block.Block block, final BlockPos rel, final String blueprint)
    {
        return MinecoloniesGameTests.placeProductionBuilding(helper, colony, block, rel, blueprint);
    }

    /**
     * Spawns a citizen with no AI of its own at the given position: the test drives it.
     */
    ICitizenData spawn(final BlockPos rel, final boolean noAi)
    {
        final BlockPos anchor = helper.absolutePos(rel);
        level.setChunkForced(anchor.getX() >> 4, anchor.getZ() >> 4, true);
        final ICitizenData citizen = colony.getCitizenManager().spawnOrCreateCivilian(null, level, List.of(anchor), true);
        helper.assertTrue(citizen != null && citizen.getEntity().isPresent(), "could not spawn a citizen at " + anchor);
        if (noAi)
        {
            citizen.getEntity().get().setNoAi(true);
        }
        return citizen;
    }

    TileEntityRack rack(final int index)
    {
        final BlockEntity entity = level.getBlockEntity(racks.get(index));
        helper.assertTrue(entity instanceof TileEntityRack, "no rack at " + racks.get(index) + ": " + entity);
        return (TileEntityRack) entity;
    }

    /**
     * Puts a stack into a rack through the item handler, so every hook an ordinary insert fires, fires.
     */
    void stock(final int rackIndex, final ItemStack stack)
    {
        final TileEntityRack rack = rack(rackIndex);
        ItemStack rest = stack.copy();
        for (int slot = 0; slot < rack.getInventory().getSlots() && !rest.isEmpty(); slot++)
        {
            rest = rack.getInventory().insertItem(slot, rest, false);
        }
        helper.assertTrue(rest.isEmpty(), "the rack could not take " + stack);
    }

    /**
     * @param predicate which items.
     * @return how many items matching the predicate sit in the warehouse racks.
     */
    int physical(final Predicate<ItemStack> predicate)
    {
        return warehouse.hasEnoughElseCount(predicate, Integer.MAX_VALUE);
    }

    IRequestManager manager()
    {
        return colony.getRequestManager();
    }

    IStandardRequestManager standard()
    {
        return (IStandardRequestManager) colony.getRequestManager();
    }

    ReservationLedger ledger()
    {
        return standard().getReservationLedger();
    }

    RequestWaitTracker tracker()
    {
        return standard().getWaitTracker();
    }

    IRequest<?> request(final IToken<?> token)
    {
        return manager().getRequestForToken(token);
    }

    /**
     * @param token a request token.
     * @return the request's state, wait reason and the resolver that holds it, for failure messages.
     */
    String describe(final IToken<?> token)
    {
        final IRequest<?> request = request(token);
        if (request == null)
        {
            return "gone";
        }
        String holder;
        try
        {
            holder = manager().getResolverForRequest(token).getClass().getSimpleName();
        }
        catch (final RuntimeException e)
        {
            holder = "none";
        }
        return request.getState() + "/" + request.getWaitReason() + " at " + holder + " children=" + request.getChildren().size();
    }

    /**
     * @param building a building.
     * @param token    a request token.
     * @return what each of the building's resolvers says about the request, for failure messages.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    String resolversSay(final IBuilding building, final IToken<?> token)
    {
        final IRequest request = request(token);
        final StringBuilder out = new StringBuilder();
        for (final var resolver : building.getResolvers())
        {
            String answer;
            try
            {
                answer = String.valueOf(((com.minecolonies.api.colony.requestsystem.resolver.IRequestResolver) resolver).canResolveRequest(manager(), request));
            }
            catch (final RuntimeException e)
            {
                answer = e.getClass().getSimpleName();
            }
            out.append(resolver.getClass().getSimpleName()).append('=').append(answer).append(' ');
        }
        return out.toString();
    }

    /**
     * @param token a request token.
     * @return true when the retrying resolver holds the request.
     */
    boolean isRetrying(final IToken<?> token)
    {
        final var assigned = standard().getRequestResolverRequestAssignmentDataStore().getAssignmentForValue(token);
        return assigned != null && assigned.equals(manager().getRetryingRequestResolver().getId());
    }

    /**
     * @param token a request token.
     * @return true when the player resolver holds the request.
     */
    boolean isWithPlayer(final IToken<?> token)
    {
        final var assigned = standard().getRequestResolverRequestAssignmentDataStore().getAssignmentForValue(token);
        return assigned != null && assigned.equals(manager().getPlayerResolver().getId());
    }
}
