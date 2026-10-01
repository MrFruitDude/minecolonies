package com.minecolonies.api.util.capability;

import net.minecraft.world.item.ItemStack;
import com.ldtteam.structurize.api.compat.itemhandler.IItemHandler;
import com.ldtteam.structurize.api.compat.itemhandler.IItemHandlerModifiable;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Exposes MineColonies' existing item handlers through NeoForge's transactional resource-handler API.
 */
public class ItemHandlerResourceHandlerAdapter implements ResourceHandler<ItemResource>
{
    private final IItemHandlerModifiable handler;

    /**
     * Lazily created per-slot journals, grown when the handler gains slots.
     */
    private SlotJournal[] journals = new SlotJournal[0];

    private ItemHandlerResourceHandlerAdapter(@NotNull final IItemHandlerModifiable handler)
    {
        this.handler = handler;
    }

    @Nullable
    public static ItemHandlerResourceHandlerAdapter of(@Nullable final IItemHandler handler)
    {
        // Capability providers are queried while entities and block entities are
        // still being constructed.  A missing or read-only handler means that
        // this capability is unavailable; it must not turn a harmless Jade probe
        // into an exception on the render thread.
        if (handler == null)
        {
            return null;
        }
        if (handler instanceof IItemHandlerModifiable modifiable)
        {
            return new ItemHandlerResourceHandlerAdapter(modifiable);
        }
        return null;
    }

    @Override
    public int size()
    {
        return handler.getSlots();
    }

    @NotNull
    @Override
    public ItemResource getResource(final int index)
    {
        return ItemResource.of(handler.getStackInSlot(index));
    }

    @Override
    public long getAmountAsLong(final int index)
    {
        return handler.getStackInSlot(index).getCount();
    }

    @Override
    public long getCapacityAsLong(final int index, @NotNull final ItemResource resource)
    {
        return isValid(index, resource) ? handler.getSlotLimit(index) : 0;
    }

    @Override
    public boolean isValid(final int index, @NotNull final ItemResource resource)
    {
        if (index < 0 || index >= handler.getSlots() || resource.isEmpty())
        {
            return false;
        }
        return handler.isItemValid(index, resource.toStack(1));
    }

    @Override
    public int insert(final int index, @NotNull final ItemResource resource, final int amount, @NotNull final TransactionContext context)
    {
        if (!isValid(index, resource) || amount <= 0)
        {
            return 0;
        }

        final ItemStack toInsert = resource.toStack(amount);
        final ItemStack simulatedRemainder = handler.insertItem(index, toInsert, true);
        final int moved = toInsert.getCount() - simulatedRemainder.getCount();
        if (moved <= 0)
        {
            return 0;
        }

        trackSlot(context, index);
        final ItemStack remainder = handler.insertItem(index, resource.toStack(moved), false);
        return moved - remainder.getCount();
    }

    @Override
    public int extract(final int index, @NotNull final ItemResource resource, final int amount, @NotNull final TransactionContext context)
    {
        if (amount <= 0 || !resource.matches(handler.getStackInSlot(index)))
        {
            return 0;
        }

        final ItemStack simulated = handler.extractItem(index, amount, true);
        if (simulated.isEmpty())
        {
            return 0;
        }

        trackSlot(context, index);
        return handler.extractItem(index, amount, false).getCount();
    }

    private void trackSlot(@NotNull final TransactionContext context, final int index)
    {
        if (index >= journals.length)
        {
            journals = java.util.Arrays.copyOf(journals, Math.max(index + 1, handler.getSlots()));
        }
        SlotJournal journal = journals[index];
        if (journal == null)
        {
            journal = new SlotJournal(index);
            journals[index] = journal;
        }
        journal.updateSnapshots(context);
    }

    /**
     * One reusable journal per slot: it snapshots the slot once per transaction depth, however many operations touch it.
     */
    private final class SlotJournal extends SnapshotJournal<ItemStack>
    {
        private final int index;

        private SlotJournal(final int index)
        {
            this.index = index;
        }

        @Override
        protected ItemStack createSnapshot()
        {
            return handler.getStackInSlot(index).copy();
        }

        @Override
        protected void revertToSnapshot(@NotNull final ItemStack snapshot)
        {
            handler.setStackInSlot(index, snapshot);
        }
    }
}
