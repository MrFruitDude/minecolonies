package com.minecolonies.core.colony.labour;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.ldtteam.structurize.api.compat.itemhandler.IItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The hut items a colony still owes (Wave B, E2): a manager placed a hut from the overview with no hut item in hand and none in the warehouse, so the placement used a
 * hut item the colony did not have yet. The hut then asks for one with an ordinary request (warehouse, couriers, labour or the player answer it, as any request), and
 * the first hut item that lands in the hut's inventory is taken out again: the debt is paid and nothing was made from nothing.
 * <ul>
 *   <li>At most {@link #MAX_DEBTS} open debts per colony: a further placement without a hut item is refused.</li>
 *   <li>A hut that owes cannot be broken by a survival player, picked up, relocated or upgraded (its first build goes ahead).</li>
 *   <li>A debt is never forgiven when its hut goes some other way (an explosion, a creative player, a demolition): it stays with the colony, the town hall asks for the
 *   item, and the next one in the town hall or a warehouse pays it.</li>
 *   <li>A request that is cancelled is asked again no sooner than {@link #ASK_AGAIN_TICKS}.</li>
 * </ul>
 * Saved with the colony ({@link LabourData}); checked by the labour board's tick ({@link #sweep}). Server thread only.
 */
public final class HutDebts
{
    /** Most open debts of one colony. */
    public static final int MAX_DEBTS = 2;

    /** Ticks before a cancelled request for an owed item is made again. */
    public static final long ASK_AGAIN_TICKS = 1200L;

    private static final String TAG_DEBTS = "debts";
    private static final String TAG_POS   = "pos";
    private static final String TAG_ITEM  = "item";
    private static final String TAG_GONE  = "gone";

    /**
     * One owed hut item.
     *
     * @param hut  the hut that owes it (where it stood, when it is gone).
     * @param item the hut item.
     * @param gone whether the hut is gone: the colony owes it, the town hall asks.
     */
    public record Debt(BlockPos hut, Item item, boolean gone)
    {
        public Debt(final BlockPos hut, final Item item)
        {
            this(hut, item, false);
        }
    }

    private final List<Debt> debts = new ArrayList<>();
    /** When each debt last asked (runtime only). */
    private final Map<Debt, Long> askedAt = new HashMap<>();

    /**
     * @return the debts (a copy).
     */
    public List<Debt> debts()
    {
        return List.copyOf(debts);
    }

    public boolean isEmpty()
    {
        return debts.isEmpty();
    }

    public int count()
    {
        return debts.size();
    }

    /**
     * @return whether one more debt may be made.
     */
    public boolean room()
    {
        return debts.size() < MAX_DEBTS;
    }

    /**
     * @return whether the hut at the position (still standing) owes its hut item.
     */
    public boolean owes(final BlockPos hut)
    {
        for (final Debt debt : debts)
        {
            if (!debt.gone() && debt.hut().equals(hut))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the hut at the position owes its hut item, for any colony (the hut's own colony is found from the building).
     */
    public static boolean owes(@Nullable final IBuilding building)
    {
        return building != null && building.getColony() instanceof com.minecolonies.core.colony.Colony colony && colony.getLabourData().hutDebts().owes(building.getID());
    }

    /**
     * The hut owes a hut item: it is recorded and the hut asks for it now.
     *
     * @return false when the colony owes too many already (nothing changed).
     */
    boolean owe(final IBuilding building, final Item item, final long now)
    {
        if (!room() || item == Items.AIR)
        {
            return false;
        }
        final Debt debt = new Debt(building.getID().immutable(), item, false);
        debts.add(debt);
        ask(building, item);
        askedAt.put(debt, now);
        return true;
    }

    private static boolean asking(final IBuilding building, final Item item)
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

    private static void ask(final IBuilding building, final Item item)
    {
        if (!asking(building, item))
        {
            building.createRequest(new Stack(new ItemStack(item), 1, 1), false);
        }
    }

    /**
     * Pays what can be paid. A standing hut pays with a hut item in its own inventory and its request ends. A debt whose hut is gone passes to the colony: the town hall
     * asks, and a hut item in the town hall or a warehouse pays it. A debt that asks for nothing (its request was cancelled) asks again after {@link #ASK_AGAIN_TICKS}.
     *
     * @return whether something changed.
     */
    boolean sweep(final IColony colony, final long now)
    {
        boolean changed = false;
        final IBuilding hall = colony.getServerBuildingManager().getTownHall();
        for (final Debt debt : new ArrayList<>(debts))
        {
            Debt current = debt;
            IBuilding building = debt.gone() ? null : colony.getServerBuildingManager().getBuilding(debt.hut());
            if (!debt.gone() && building == null)
            {
                // the hut went another way (an explosion, a creative player, a demolition): the colony still owes the item
                current = new Debt(debt.hut(), debt.item(), true);
                debts.set(debts.indexOf(debt), current);
                askedAt.remove(debt);
                changed = true;
            }
            final IBuilding asker = current.gone() ? hall : building;
            if (paid(colony, current, asker))
            {
                debts.remove(current);
                askedAt.remove(current);
                changed = true;
                if (asker != null && !stillOwes(current, asker))
                {
                    end(colony, asker, current.item());
                }
                continue;
            }
            if (asker != null && !asking(asker, current.item()) && now - askedAt.getOrDefault(current, Long.MIN_VALUE / 2) >= ASK_AGAIN_TICKS)
            {
                ask(asker, current.item());
                askedAt.put(current, now);
            }
        }
        return changed;
    }

    private static boolean paid(final IColony colony, final Debt debt, @Nullable final IBuilding asker)
    {
        if (asker != null && asker.getItemHandlerCap() != null && take(asker.getItemHandlerCap(), debt.item()))
        {
            return true;
        }
        if (debt.gone())
        {
            for (final IWareHouse wareHouse : colony.getServerBuildingManager().getWareHouses())
            {
                if (wareHouse.getItemHandlerCap() != null && take(wareHouse.getItemHandlerCap(), debt.item()))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean stillOwes(final Debt paid, final IBuilding asker)
    {
        for (final Debt debt : debts)
        {
            if (debt.item() == paid.item() && (debt.gone() ? paid.gone() : debt.hut().equals(asker.getID())))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Takes one of the item out of the handler (any hut item of the kind: the owed one is not told apart from another).
     */
    private static boolean take(final IItemHandler handler, final Item item)
    {
        for (int slot = 0; slot < handler.getSlots(); slot++)
        {
            if (handler.getStackInSlot(slot).is(item) && !handler.extractItem(slot, 1, false).isEmpty())
            {
                return true;
            }
        }
        return false;
    }

    private static void end(final IColony colony, final IBuilding building, final Item item)
    {
        for (final IRequest<?> request : building.getOpenRequests(-1))
        {
            if (request.getRequest() instanceof IDeliverable deliverable && deliverable.matches(new ItemStack(item)))
            {
                colony.getRequestManager().updateRequestState(request.getId(), RequestState.CANCELLED);
            }
        }
    }

    @NotNull
    public CompoundTag write()
    {
        final CompoundTag tag = new CompoundTag();
        final ListTag list = new ListTag();
        for (final Debt debt : debts)
        {
            final CompoundTag item = new CompoundTag();
            item.putLong(TAG_POS, debt.hut().asLong());
            item.putString(TAG_ITEM, BuiltInRegistries.ITEM.getKey(debt.item()).toString());
            if (debt.gone())
            {
                item.putBoolean(TAG_GONE, true);
            }
            list.add(item);
        }
        tag.put(TAG_DEBTS, list);
        return tag;
    }

    public void read(@NotNull final CompoundTag tag)
    {
        debts.clear();
        askedAt.clear();
        final ListTag list = tag.getListOrEmpty(TAG_DEBTS);
        // (a save from before the cap keeps every debt it had: none is forgiven by a load)
        for (int i = 0; i < list.size(); i++)
        {
            final CompoundTag item = list.getCompoundOrEmpty(i);
            final Identifier id = Identifier.tryParse(item.getStringOr(TAG_ITEM, ""));
            final Item hut = id == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(id);
            if (hut != Items.AIR)
            {
                debts.add(new Debt(BlockPos.of(item.getLongOr(TAG_POS, 0L)), hut, item.getBooleanOr(TAG_GONE, false)));
            }
        }
    }
}
