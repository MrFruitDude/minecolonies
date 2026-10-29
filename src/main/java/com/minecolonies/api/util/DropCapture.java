package com.minecolonies.api.util;

import com.ldtteam.structurize.api.compat.itemhandler.IItemHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Catches the items a block break drops into the world, so a worker gets them instead of leaving them on the ground (a TreeChop felling, cocoa that pops
 * off a log). A scope lives for one synchronous call on the server thread: inside it, item entities that would join the level inside the box are cancelled
 * and their stacks kept, and experience orbs inside the box are cancelled (citizens have no use for them, and a citizen's normal block break drops none).
 * Nothing outside the box, the level or the call is touched. The caller hands the stacks on with {@link #deliver}, which never deletes an item.
 */
public final class DropCapture
{
    private static final List<Scope> SCOPES = new ArrayList<>();

    private DropCapture()
    {
    }

    private record Scope(Level level, AABB box, List<ItemStack> items)
    {
    }

    /**
     * Run an action and return the stacks it dropped inside the box.
     *
     * @param level  the level.
     * @param box    where drops are caught.
     * @param action the action, run at once.
     * @return the caught stacks (empty on the client).
     */
    @NotNull
    public static List<ItemStack> capture(@NotNull final Level level, @NotNull final AABB box, @NotNull final Runnable action)
    {
        if (level.isClientSide())
        {
            action.run();
            return new ArrayList<>();
        }
        final Scope scope = new Scope(level, box, new ArrayList<>());
        SCOPES.add(scope);
        try
        {
            action.run();
        }
        finally
        {
            SCOPES.remove(scope);
        }
        return scope.items();
    }

    /**
     * Put stacks into an inventory; whatever does not fit drops at the given spot, so nothing is ever deleted. Call outside a capture scope.
     *
     * @param level   the level.
     * @param handler the inventory.
     * @param stacks  the stacks.
     * @param feet    where the rest drops (the worker's feet).
     * @return the number of items that did not fit and were dropped.
     */
    public static int deliver(@NotNull final Level level, @NotNull final IItemHandler handler, @NotNull final List<ItemStack> stacks, @NotNull final Vec3 feet)
    {
        int dropped = 0;
        for (final ItemStack stack : stacks)
        {
            if (stack.isEmpty())
            {
                continue;
            }
            final ItemStack rest = InventoryUtils.transferItemStackIntoNextBestSlotInItemHandlerWithResult(stack, handler);
            if (!rest.isEmpty())
            {
                dropped += rest.getCount();
                InventoryUtils.spawnItemStack(level, feet.x, feet.y, feet.z, rest);
            }
        }
        return dropped;
    }

    /**
     * Highest priority listener (registered in MineColonies' constructor).
     *
     * @param event the event.
     */
    public static void onEntityJoin(final EntityJoinLevelEvent event)
    {
        if (SCOPES.isEmpty() || event.loadedFromDisk() || event.isCanceled())
        {
            return;
        }
        final Entity entity = event.getEntity();
        if (!(entity instanceof ItemEntity) && !(entity instanceof ExperienceOrb))
        {
            return;
        }
        for (final Scope scope : SCOPES)
        {
            if (scope.level() == event.getLevel() && scope.box().contains(entity.position()))
            {
                if (entity instanceof ItemEntity item && !item.getItem().isEmpty())
                {
                    scope.items().add(item.getItem().copy());
                }
                event.setCanceled(true);
                return;
            }
        }
    }
}
