package com.minecolonies.core.colony.requestsystem.priority;

import com.minecolonies.api.colony.requestsystem.token.IToken;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * RS3: the player-set delivery priority of each building (1 to 9, 5 by default, higher is served first) and the temporary
 * alarm that overrides it, after Anno's consumer priority and its alarmed priority.
 * <p>
 * {@link #effective} is what the courier scoring reads: the alarm counts as {@value #ALARM_PRIORITY}, above every
 * setting. An alarm ends by itself after {@value #ALARM_TICKS} game ticks (five minutes) unless the player switches it
 * off earlier. Only buildings that differ from the defaults take space; entries of buildings that no longer exist are
 * dropped by {@link #prune}.
 * <p>
 * Also keeps, in memory only, when a courier first saw a request in its queue ({@link #waited}): the request itself has
 * no creation time. After a restart that wait starts again, while importance and priority, which dominate the score, are
 * persisted.
 * <p>
 * Server thread only. The Colony Command UI reads it through its own sync ({@link #snapshot}, {@link #revision}).
 */
public final class BuildingPriorities
{
    /**
     * Lowest priority a building can be given.
     */
    public static final int MIN_PRIORITY = 1;

    /**
     * Highest priority a building can be given.
     */
    public static final int MAX_PRIORITY = 9;

    /**
     * Priority of a building nobody touched.
     */
    public static final int DEFAULT_PRIORITY = 5;

    /**
     * What an alarmed building counts as: above every setting.
     */
    public static final int ALARM_PRIORITY = 10;

    /**
     * How long an alarm lasts when nobody switches it off: five minutes.
     */
    public static final long ALARM_TICKS = 6000L;

    private static final String NBT_ENTRIES = "Entries";
    private static final String NBT_POS      = "Pos";
    private static final String NBT_PRIORITY = "Priority";
    private static final String NBT_ALARM    = "AlarmUntil";

    /**
     * One building's setting.
     *
     * @param priority   the set priority, 1 to 9.
     * @param alarmUntil the game time the alarm ends, 0 for no alarm. The alarm is on while the game time is below it.
     */
    public record Entry(int priority, long alarmUntil)
    {
    }

    private final Map<BlockPos, Entry> entries = new HashMap<>();

    /**
     * When each request was first seen in a courier's queue.
     */
    private final Map<IToken<?>, Long> firstSeen = new HashMap<>();

    private long revision;

    /**
     * @param pos a building position.
     * @return the set priority, 1 to 9 (5 when never set). The alarm is not part of it.
     */
    public int priority(@NotNull final BlockPos pos)
    {
        final Entry entry = entries.get(pos);
        return entry == null ? DEFAULT_PRIORITY : entry.priority;
    }

    /**
     * Sets a building's priority.
     *
     * @param pos      the building position.
     * @param priority the priority; clamped to 1 to 9.
     */
    public void setPriority(@NotNull final BlockPos pos, final int priority)
    {
        final Entry old = entries.get(pos);
        put(pos, new Entry(Math.max(MIN_PRIORITY, Math.min(MAX_PRIORITY, priority)), old == null ? 0L : old.alarmUntil));
    }

    /**
     * Switches a building's alarm on for {@value #ALARM_TICKS} ticks, or off.
     *
     * @param pos the building position.
     * @param on  the alarm.
     * @param now the game time.
     */
    public void setAlarm(@NotNull final BlockPos pos, final boolean on, final long now)
    {
        setAlarm(pos, on, now, ALARM_TICKS);
    }

    /**
     * Switches a building's alarm on for the given time, or off.
     *
     * @param pos           the building position.
     * @param on            the alarm.
     * @param now           the game time.
     * @param durationTicks how long it lasts.
     */
    public void setAlarm(@NotNull final BlockPos pos, final boolean on, final long now, final long durationTicks)
    {
        final Entry old = entries.get(pos);
        put(pos, new Entry(old == null ? DEFAULT_PRIORITY : old.priority, on ? now + Math.max(1L, durationTicks) : 0L));
    }

    /**
     * @param pos a building position.
     * @param now the game time.
     * @return true while the building's alarm is on.
     */
    public boolean isAlarm(@NotNull final BlockPos pos, final long now)
    {
        final Entry entry = entries.get(pos);
        return entry != null && now < entry.alarmUntil;
    }

    /**
     * @param pos a building position.
     * @return the game time the alarm ends; 0 when none was set. It may lie in the past.
     */
    public long alarmUntil(@NotNull final BlockPos pos)
    {
        final Entry entry = entries.get(pos);
        return entry == null ? 0L : entry.alarmUntil;
    }

    /**
     * @param pos a building position.
     * @param now the game time.
     * @return the priority the scoring uses: {@value #ALARM_PRIORITY} during an alarm, else the set priority.
     */
    public int effective(@NotNull final BlockPos pos, final long now)
    {
        final Entry entry = entries.get(pos);
        if (entry == null)
        {
            return DEFAULT_PRIORITY;
        }
        return now < entry.alarmUntil ? ALARM_PRIORITY : entry.priority;
    }

    /**
     * @return every building that has a non-default priority or an alarm (maybe expired), as an immutable copy.
     */
    @NotNull
    public Map<BlockPos, Entry> snapshot()
    {
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /**
     * @return a number that grows with every change, for UIs that sync on change.
     */
    public long revision()
    {
        return revision;
    }

    /**
     * @return true when no building has a setting.
     */
    public boolean isEmpty()
    {
        return entries.isEmpty();
    }

    /**
     * Drops the settings of buildings that are gone.
     *
     * @param exists tells whether a building still stands at the position.
     * @return how many were dropped.
     */
    public int prune(@NotNull final Predicate<BlockPos> exists)
    {
        int dropped = 0;
        for (final Iterator<BlockPos> it = entries.keySet().iterator(); it.hasNext(); )
        {
            if (!exists.test(it.next()))
            {
                it.remove();
                dropped++;
            }
        }
        if (dropped > 0)
        {
            revision++;
        }
        return dropped;
    }

    /**
     * Ends the alarms that ran out, and forgets the settings that are back to the defaults.
     *
     * @param now the game time.
     * @return how many entries changed.
     */
    public int expire(final long now)
    {
        int changed = 0;
        for (final Iterator<Map.Entry<BlockPos, Entry>> it = entries.entrySet().iterator(); it.hasNext(); )
        {
            final Map.Entry<BlockPos, Entry> e = it.next();
            if (e.getValue().alarmUntil != 0L && now >= e.getValue().alarmUntil)
            {
                changed++;
                if (e.getValue().priority == DEFAULT_PRIORITY)
                {
                    it.remove();
                }
                else
                {
                    e.setValue(new Entry(e.getValue().priority, 0L));
                }
            }
        }
        if (changed > 0)
        {
            revision++;
        }
        return changed;
    }

    /**
     * Forgets everything.
     */
    public void clear()
    {
        entries.clear();
        firstSeen.clear();
        revision++;
    }

    private void put(final BlockPos pos, final Entry entry)
    {
        final boolean isDefault = entry.priority == DEFAULT_PRIORITY && entry.alarmUntil == 0L;
        final Entry old = isDefault ? entries.remove(pos) : entries.put(pos.immutable(), entry);
        if (!java.util.Objects.equals(old, isDefault ? null : entry))
        {
            revision++;
        }
    }

    // ------------------------------------------------------------------ wait clock

    /**
     * How long a courier queue has held a request: the first call for a token starts its clock.
     *
     * @param token the request token.
     * @param now   the game time.
     * @return the ticks since the first call, 0 on the first.
     */
    public long waited(@NotNull final IToken<?> token, final long now)
    {
        final Long first = firstSeen.putIfAbsent(token, now);
        return first == null ? 0L : Math.max(0L, now - first);
    }

    /**
     * Like {@link #waited}, without starting a clock.
     *
     * @param token the request token.
     * @param now   the game time.
     * @return the ticks since the clock started, 0 when it did not.
     */
    public long peekWaited(@NotNull final IToken<?> token, final long now)
    {
        final Long first = firstSeen.get(token);
        return first == null ? 0L : Math.max(0L, now - first);
    }

    /**
     * Ends a request's clock (a courier took it).
     *
     * @param token the request token.
     */
    public void forgetWait(@NotNull final IToken<?> token)
    {
        firstSeen.remove(token);
    }

    /**
     * Forgets the clocks of requests that are gone.
     *
     * @param alive tells whether a request token still exists.
     */
    public void pruneWaits(@NotNull final Predicate<IToken<?>> alive)
    {
        firstSeen.keySet().removeIf(token -> !alive.test(token));
    }

    // ------------------------------------------------------------------ persistence

    /**
     * @return the settings as NBT.
     */
    @NotNull
    public CompoundTag write()
    {
        final CompoundTag tag = new CompoundTag();
        final ListTag list = new ListTag();
        for (final Map.Entry<BlockPos, Entry> e : entries.entrySet())
        {
            final CompoundTag entry = new CompoundTag();
            entry.putLong(NBT_POS, e.getKey().asLong());
            entry.putInt(NBT_PRIORITY, e.getValue().priority);
            if (e.getValue().alarmUntil != 0L)
            {
                entry.putLong(NBT_ALARM, e.getValue().alarmUntil);
            }
            list.add(entry);
        }
        tag.put(NBT_ENTRIES, list);
        return tag;
    }

    /**
     * Replaces the settings with the ones in the tag. A missing or unreadable tag (a save from before RS3) means no settings.
     *
     * @param tag the NBT written by {@link #write}.
     */
    public void read(@NotNull final CompoundTag tag)
    {
        entries.clear();
        firstSeen.clear();
        revision++;
        final ListTag list = tag.getListOrEmpty(NBT_ENTRIES);
        for (int i = 0; i < list.size(); i++)
        {
            final CompoundTag entry = list.getCompoundOrEmpty(i);
            if (!entry.contains(NBT_POS))
            {
                continue;
            }
            final BlockPos pos = BlockPos.of(entry.getLongOr(NBT_POS, 0L));
            final int priority = Math.max(MIN_PRIORITY, Math.min(MAX_PRIORITY, entry.getIntOr(NBT_PRIORITY, DEFAULT_PRIORITY)));
            final long alarmUntil = Math.max(0L, entry.getLongOr(NBT_ALARM, 0L));
            if (priority != DEFAULT_PRIORITY || alarmUntil != 0L)
            {
                entries.put(pos, new Entry(priority, alarmUntil));
            }
        }
    }
}
