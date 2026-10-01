package com.minecolonies.core.datalistener;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.event.DefaultDataComponentsBoundEvent;
import net.neoforged.neoforge.resource.ContextAwareReloadListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the parse step of reload listeners that build ItemStacks once default item components are bound.
 * <p>
 * Since 1.21.2 the server binds default item components only after every reload listener has applied,
 * so decoding an ItemStack inside {@code apply()} fails ("Item ... does not have components yet").
 * Listeners call {@link #defer} from {@code apply()} with the raw json; the parse runs on the
 * {@link DefaultDataComponentsBoundEvent} that follows the same reload, with the condition context and
 * registry lookup of that reload re-injected (NeoForge clears both once the reload finishes).
 */
public final class DeferredDataApply
{
    /**
     * Pending parse work by listener class, in registration order. A new reload replaces the entry of the
     * same class, so a reload that failed before binding components leaves nothing stale behind.
     */
    private static final Map<Class<?>, Runnable> PENDING = new LinkedHashMap<>();

    private DeferredDataApply()
    {
    }

    /**
     * Queue a listener's parse step until default components are bound.
     *
     * @param listener the listener; its context is restored while {@code work} runs.
     * @param work     the parse step.
     */
    public static void defer(final ContextAwareReloadListener listener, final ICondition.IContext context, final HolderLookup.Provider lookup, final Runnable work)
    {
        synchronized (PENDING)
        {
            PENDING.put(listener.getClass(), () -> {
                listener.injectContext(context, lookup);
                try
                {
                    work.run();
                }
                finally
                {
                    listener.injectContext(ICondition.IContext.EMPTY, RegistryAccess.EMPTY);
                }
            });
        }
    }

    /**
     * @return how many parse steps are still waiting for components to be bound.
     */
    public static int pendingCount()
    {
        synchronized (PENDING)
        {
            return PENDING.size();
        }
    }

    /**
     * Run all queued parse steps. Only the server data load counts; the client-side event in single player
     * shares the static data the server just built.
     */
    public static void onComponentsBound(final DefaultDataComponentsBoundEvent event)
    {
        if (event.getUpdateCause() != DefaultDataComponentsBoundEvent.UpdateCause.SERVER_DATA_LOAD)
        {
            return;
        }
        final List<Runnable> work;
        synchronized (PENDING)
        {
            work = new ArrayList<>(PENDING.values());
            PENDING.clear();
        }
        work.forEach(Runnable::run);
    }
}
