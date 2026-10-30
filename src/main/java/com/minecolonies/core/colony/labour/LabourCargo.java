package com.minecolonies.core.colony.labour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Round 6 teamwork: what a labourer carries for a request or a stock goal, by citizen (saved with the colony, in {@link LabourData}). A claim is not saved and ends
 * when he is called away (a meal, the night, a raid, his own work, a job) or the world is reloaded; the goods he gathered for it stay tied to what they were for. The
 * next time he is free he only hands them in ({@link LabourBoard#offer}, {@link LabourBoard#offerToWorker}): to the hut that asked while the request still waits,
 * else to a warehouse. Nothing is lost and nothing is brought twice. Server thread only.
 */
public final class LabourCargo
{
    private static final String TAG_LIST    = "cargo";
    private static final String TAG_CITIZEN = "citizen";
    private static final String TAG_REQUEST = "request";
    private static final String TAG_ITEM    = "item";
    private static final String TAG_TO      = "to";
    private static final String TAG_SINCE   = "since";

    /**
     * What one citizen carries goods for.
     *
     * @param request     the request the goods are for, null for a stock goal.
     * @param item        the good.
     * @param destination the hut that asked (the warehouse of a stock goal).
     * @param since       the game time the record was made.
     */
    public record Entry(@Nullable UUID request, Item item, BlockPos destination, long since)
    {
    }

    private final Map<Integer, Entry> byCitizen = new HashMap<>();

    /**
     * @return what the citizen carries goods for, or null.
     */
    @Nullable
    public Entry of(final int citizenId)
    {
        return byCitizen.get(citizenId);
    }

    /**
     * @return true if the record changed.
     */
    public boolean put(final int citizenId, @NotNull final Entry entry)
    {
        return !entry.equals(byCitizen.put(citizenId, entry));
    }

    /**
     * @return true if there was a record.
     */
    public boolean remove(final int citizenId)
    {
        return byCitizen.remove(citizenId) != null;
    }

    /**
     * @return a copy of the records.
     */
    public Map<Integer, Entry> all()
    {
        return new HashMap<>(byCitizen);
    }

    public boolean isEmpty()
    {
        return byCitizen.isEmpty();
    }

    @NotNull
    public ListTag write()
    {
        final ListTag list = new ListTag();
        for (final Map.Entry<Integer, Entry> e : byCitizen.entrySet())
        {
            final CompoundTag tag = new CompoundTag();
            tag.putInt(TAG_CITIZEN, e.getKey());
            if (e.getValue().request() != null)
            {
                tag.putString(TAG_REQUEST, e.getValue().request().toString());
            }
            tag.putString(TAG_ITEM, BuiltInRegistries.ITEM.getKey(e.getValue().item()).toString());
            tag.putLong(TAG_TO, e.getValue().destination().asLong());
            tag.putLong(TAG_SINCE, e.getValue().since());
            list.add(tag);
        }
        return list;
    }

    public void read(@NotNull final ListTag list)
    {
        byCitizen.clear();
        for (int i = 0; i < list.size(); i++)
        {
            final CompoundTag tag = list.getCompoundOrEmpty(i);
            final Identifier id = Identifier.tryParse(tag.getStringOr(TAG_ITEM, ""));
            final Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(id);
            if (item == Items.AIR)
            {
                // an item of a removed mod: what he carries stays his
                continue;
            }
            UUID request = null;
            final String token = tag.getStringOr(TAG_REQUEST, "");
            if (!token.isEmpty())
            {
                try
                {
                    request = UUID.fromString(token);
                }
                catch (final IllegalArgumentException ignored)
                {
                    // a broken token: the goods go to a warehouse
                }
            }
            byCitizen.put(tag.getIntOr(TAG_CITIZEN, -1), new Entry(request, item, BlockPos.of(tag.getLongOr(TAG_TO, 0L)), tag.getLongOr(TAG_SINCE, 0L)));
        }
        byCitizen.remove(-1);
    }
}
