package com.minecolonies.core.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Which colony each player has selected. One server wide file in the overworld data storage; a colony id is only unique
 * per dimension, so the selection is a (dimension, id) pair. Only explicit selections are stored: a player without one
 * (or whose selected colony is gone) falls back to the oldest colony they own.
 */
public final class SelectedColonySavedData extends SavedData
{
    /**
     * A selected colony.
     *
     * @param dimension the dimension of the colony.
     * @param id        the colony id.
     */
    public record Selection(ResourceKey<Level> dimension, int id)
    {
        public static final Codec<Selection> CODEC = RecordCodecBuilder.create(instance -> instance.group(
          Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Selection::dimension),
          Codec.INT.fieldOf("id").forGetter(Selection::id)
        ).apply(instance, Selection::new));
    }

    public static final Codec<SelectedColonySavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
      Codec.unboundedMap(UUIDUtil.STRING_CODEC, Selection.CODEC)
        .optionalFieldOf("selected", Map.of())
        .forGetter(SelectedColonySavedData::selections)
    ).apply(instance, SelectedColonySavedData::new));

    public static final SavedDataType<SelectedColonySavedData> TYPE = new SavedDataType<>(
      Identifier.fromNamespaceAndPath("minecolonies", "selected_colonies"), SelectedColonySavedData::new, CODEC);

    private final Map<UUID, Selection> selected;

    public SelectedColonySavedData()
    {
        this(Map.of());
    }

    public SelectedColonySavedData(final Map<UUID, Selection> selected)
    {
        this.selected = new HashMap<>(selected);
    }

    /**
     * @param server the server.
     * @return the data of the server.
     */
    public static SelectedColonySavedData get(final MinecraftServer server)
    {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public Map<UUID, Selection> selections()
    {
        return Map.copyOf(selected);
    }

    @Nullable
    public Selection get(final UUID owner)
    {
        return selected.get(owner);
    }

    public void set(final UUID owner, final Selection selection)
    {
        if (!selection.equals(selected.put(owner, selection)))
        {
            setDirty();
        }
    }

    public void remove(final UUID owner)
    {
        if (selected.remove(owner) != null)
        {
            setDirty();
        }
    }
}
