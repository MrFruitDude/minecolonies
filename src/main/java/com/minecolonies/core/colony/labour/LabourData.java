package com.minecolonies.core.colony.labour;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * What the colony remembers about its labourers (saved with the colony): the flags the managers put on crafting tables and furnaces.
 * <ul>
 *   <li>{@link TableFlag#EXCLUDE}: labourers never use the table (a decorative one in a house);</li>
 *   <li>{@link TableFlag#PREFER}: labourers use it first (and, in the {@code MARKED} post mode, only these).</li>
 * </ul>
 * Nothing is stored for a table without a flag. Server thread only.
 */
public final class LabourData
{
    private static final String TAG_FLAGS = "flags";
    private static final String TAG_POS   = "pos";
    private static final String TAG_FLAG  = "flag";

    /**
     * What a manager says about a table or furnace.
     */
    public enum TableFlag
    {
        EXCLUDE,
        PREFER
    }

    private final Map<BlockPos, TableFlag> flags = new HashMap<>();

    /**
     * @param pos a crafting table or furnace.
     * @return its flag, or null for none.
     */
    @Nullable
    public TableFlag flagOf(@NotNull final BlockPos pos)
    {
        return flags.get(pos);
    }

    /**
     * @param pos  the block.
     * @param flag the flag, null to clear it.
     * @return true if something changed.
     */
    public boolean setFlag(@NotNull final BlockPos pos, @Nullable final TableFlag flag)
    {
        if (flag == null)
        {
            return flags.remove(pos) != null;
        }
        return flags.put(pos.immutable(), flag) != flag;
    }

    /**
     * @return a copy of the flags.
     */
    public Map<BlockPos, TableFlag> flags()
    {
        return new HashMap<>(flags);
    }

    @NotNull
    public CompoundTag write()
    {
        final CompoundTag tag = new CompoundTag();
        final ListTag list = new ListTag();
        for (final Map.Entry<BlockPos, TableFlag> entry : flags.entrySet())
        {
            final CompoundTag item = new CompoundTag();
            item.putLong(TAG_POS, entry.getKey().asLong());
            item.putString(TAG_FLAG, entry.getValue().name());
            list.add(item);
        }
        tag.put(TAG_FLAGS, list);
        return tag;
    }

    public void read(@NotNull final CompoundTag tag)
    {
        flags.clear();
        final ListTag list = tag.getListOrEmpty(TAG_FLAGS);
        for (int i = 0; i < list.size(); i++)
        {
            final CompoundTag item = list.getCompoundOrEmpty(i);
            try
            {
                flags.put(BlockPos.of(item.getLongOr(TAG_POS, 0L)), TableFlag.valueOf(item.getStringOr(TAG_FLAG, TableFlag.EXCLUDE.name())));
            }
            catch (final IllegalArgumentException ignored)
            {
                // an unknown flag of a newer version: dropped
            }
        }
    }
}
