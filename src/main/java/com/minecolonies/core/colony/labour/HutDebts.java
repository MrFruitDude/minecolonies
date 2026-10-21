package com.minecolonies.core.colony.labour;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The hut items a colony still owes (Wave B, E2): a manager placed a hut from the overview with no hut item in hand and none in the warehouse, so the placement used a
 * hut item the colony did not have yet. The hut then asks for one with an ordinary request (warehouse, couriers, labour or the player answer it, as any request), and
 * the first hut item that lands in the hut's inventory is taken out again: the debt is paid and nothing was made from nothing. Saved with the colony
 * ({@link LabourData}); checked by the labour board's tick ({@link #sweep}). Server thread only.
 */
public final class HutDebts
{
    /** Most debts of one colony (a colony at the limit places no more huts on credit). */
    public static final int MAX_DEBTS = 32;

    private static final String TAG_DEBTS = "debts";
    private static final String TAG_POS   = "pos";
    private static final String TAG_ITEM  = "item";

    /**
     * One owed hut item.
     *
     * @param hut  the hut that owes it.
     * @param item the hut item.
     */
    public record Debt(BlockPos hut, Item item)
    {
    }

    private final List<Debt> debts = new ArrayList<>();

    /**
     * @return the debts (a copy).
     */
    public List<Debt> debts()
    {
        return List.copyOf(debts);
    }

    /**
     * @return whether one more debt may be made.
     */
    public boolean room()
    {
        return debts.size() < MAX_DEBTS;
    }

    /**
     * The hut owes a hut item: it is recorded and the hut asks for it now.
     *
     * @return false when the colony owes too many already (nothing changed).
     */
    boolean owe(final IBuilding building, final Item item)
    {
        if (!room() || item == Items.AIR)
        {
            return false;
        }
        debts.add(new Debt(building.getID().immutable(), item));
        ask(building, item);
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
     * Pays what can be paid: a hut item in the indebted hut's inventory is taken out and the hut's open request for it ends. A hut that is gone owes nothing any more;
     * a hut that owes and does not ask (its request was cancelled) asks again.
     *
     * @return whether something changed.
     */
    boolean sweep(final IColony colony)
    {
        boolean changed = false;
        for (final Debt debt : new ArrayList<>(debts))
        {
            final IBuilding building = colony.getServerBuildingManager().getBuilding(debt.hut());
            if (building == null)
            {
                debts.remove(debt);
                changed = true;
                continue;
            }
            final IItemHandler handler = building.getItemHandlerCap();
            if (handler != null && take(handler, debt.item()))
            {
                debts.remove(debt);
                changed = true;
                if (!stillOwes(debt))
                {
                    end(colony, building, debt.item());
                }
                continue;
            }
            ask(building, debt.item());
        }
        return changed;
    }

    private boolean stillOwes(final Debt paid)
    {
        for (final Debt debt : debts)
        {
            if (debt.hut().equals(paid.hut()) && debt.item() == paid.item())
            {
                return true;
            }
        }
        return false;
    }

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
            list.add(item);
        }
        tag.put(TAG_DEBTS, list);
        return tag;
    }

    public void read(@NotNull final CompoundTag tag)
    {
        debts.clear();
        final ListTag list = tag.getListOrEmpty(TAG_DEBTS);
        for (int i = 0; i < list.size() && debts.size() < MAX_DEBTS; i++)
        {
            final CompoundTag item = list.getCompoundOrEmpty(i);
            final Identifier id = Identifier.tryParse(item.getStringOr(TAG_ITEM, ""));
            final Item hut = id == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(id);
            if (hut != Items.AIR)
            {
                debts.add(new Debt(BlockPos.of(item.getLongOr(TAG_POS, 0L)), hut));
            }
        }
    }
}
