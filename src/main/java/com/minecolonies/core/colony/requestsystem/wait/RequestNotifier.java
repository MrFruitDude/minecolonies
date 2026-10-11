package com.minecolonies.core.colony.requestsystem.wait;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.WaitReason;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.MessageUtils;
import com.minecolonies.core.colony.Colony;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RS2: rate-limited, deduplicated warnings about requests that cannot be served. Anno fires a missing-workforce alert at
 * most once per 300 s and tells Notification from Warning; this reports each stuck request once, only for the reasons
 * the player can act on, and not more often than once per topic per five minutes.
 */
public final class RequestNotifier
{
    /**
     * Game ticks before the same topic (reason and item) is reported again: five minutes.
     */
    public static final long REPEAT_TICKS = 6000L;

    private static final int LOG_SIZE = 64;

    /**
     * Requests already reported, by reason.
     */
    private final Map<IToken<?>, Set<WaitReason>> reported = new HashMap<>();

    private final Map<String, Long> lastByTopic = new HashMap<>();

    private final ArrayDeque<String> sentLog = new ArrayDeque<>();

    /**
     * Messages sent so far. Statistic for tests.
     */
    private int sent;

    /**
     * Reports a stuck request if it deserves a message, once.
     *
     * @param colony  the colony.
     * @param request the request.
     * @param reason  why it waits.
     * @param now     the game time.
     * @return true when a message went out.
     */
    public boolean report(@NotNull final IColony colony, @NotNull final IRequest<?> request, @NotNull final WaitReason reason, final long now)
    {
        if (reason.severity() != WaitReason.Severity.WARNING)
        {
            return false;
        }
        if (!reported.computeIfAbsent(request.getId(), t -> new HashSet<>(2)).add(reason))
        {
            return false;
        }

        final String what = describe(request);
        final String topic = reason + ":" + what;
        final Long last = lastByTopic.get(topic);
        if (last != null && now - last < REPEAT_TICKS)
        {
            return false;
        }
        lastByTopic.put(topic, now);

        sent++;
        if (sentLog.size() >= LOG_SIZE)
        {
            sentLog.pollFirst();
        }
        sentLog.addLast(reason + " " + what);
        try
        {
            if (colony instanceof Colony serverColony)
            {
                MessageUtils.format(Component.translatableEscape(reason.translationKey() + ".notify", what)).sendTo(serverColony).forAllPlayers();
            }
        }
        catch (final RuntimeException e)
        {
            Log.getLogger().warn("Could not send a request notification", e);
        }
        return true;
    }

    /**
     * Forgets a request (it ended).
     *
     * @param token the request token.
     */
    public void forget(@NotNull final IToken<?> token)
    {
        reported.remove(token);
    }

    public int sentCount()
    {
        return sent;
    }

    /**
     * @return the most recent messages, oldest first.
     */
    @NotNull
    public List<String> recent()
    {
        return List.copyOf(sentLog);
    }

    private static String describe(final IRequest<?> request)
    {
        try
        {
            final List<ItemStack> stacks = request.getDisplayStacks();
            if (!stacks.isEmpty())
            {
                return stacks.get(0).getHoverName().getString();
            }
            return request.getShortDisplayString().getString();
        }
        catch (final RuntimeException e)
        {
            return "?";
        }
    }
}
