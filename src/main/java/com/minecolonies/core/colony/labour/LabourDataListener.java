package com.minecolonies.core.colony.labour;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.minecolonies.api.util.Log;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The data files of the labourers under {@code data/<namespace>/labour/}:
 * <ul>
 *   <li>{@code coverage.json}: {@code {"#minecraft:logs": ["minecolonies:lumberjack"], "minecraft:cobblestone": ["minecolonies:miner"]}}: the buildings that gather a
 *   raw good (an item tag with a leading {@code #}, or an item id). A colony that has such a building covers the good and its labourers keep off it;</li>
 *   <li>{@code stockpile.json}: {@code [{"id": "logs", "item": "#minecraft:logs", "start_below": 16, "target": 32}]}: the early-game stock goals.</li>
 * </ul>
 * Files of several namespaces merge (a later entry with the same key replaces an earlier one).
 */
public class LabourDataListener extends SimpleJsonResourceReloadListener<JsonElement>
{
    /**
     * One stock goal.
     *
     * @param id         its name.
     * @param item       an item id or an item tag with a leading {@code #}.
     * @param startBelow the labourers start when the stock is below this.
     * @param target     they stop when the stock reaches this.
     */
    public record StockpileGoal(String id, String item, int startBelow, int target)
    {
    }

    private static volatile Map<String, List<String>> coverage  = defaultCoverage();
    private static volatile List<StockpileGoal>       stockpile = defaultStockpile();
    private static volatile int                       generation;

    public LabourDataListener()
    {
        super(ExtraCodecs.JSON, FileToIdConverter.json("labour"));
    }

    /**
     * @return the gatherer buildings by good.
     */
    public static Map<String, List<String>> coverage()
    {
        return coverage;
    }

    /**
     * @return the stock goals.
     */
    public static List<StockpileGoal> stockpile()
    {
        final List<StockpileGoal> forced = stockpileOverride;
        return forced != null ? forced : stockpile;
    }

    /**
     * @return a number that moves whenever the data was loaded again (caches built from it are dropped).
     */
    public static int generation()
    {
        return generation;
    }

    /**
     * Only for tests: the stock goals (null for the data).
     */
    public static void overrideStockpileForTests(final List<StockpileGoal> goals)
    {
        stockpileOverride = goals;
    }

    private static volatile List<StockpileGoal> stockpileOverride;

    private static Map<String, List<String>> defaultCoverage()
    {
        final Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("#minecraft:logs", List.of("minecolonies:lumberjack"));
        map.put("#minecraft:saplings", List.of("minecolonies:lumberjack"));
        map.put("minecraft:cobblestone", List.of("minecolonies:miner", "minecolonies:simplequarry", "minecolonies:mediumquarry", "minecolonies:largequarry"));
        map.put("minecraft:stone", List.of("minecolonies:miner", "minecolonies:simplequarry", "minecolonies:mediumquarry", "minecolonies:largequarry"));
        return map;
    }

    private static List<StockpileGoal> defaultStockpile()
    {
        return List.of(
          new StockpileGoal("logs", "#minecraft:logs", 16, 32),
          new StockpileGoal("planks", "#minecraft:planks", 16, 32),
          new StockpileGoal("sticks", "minecraft:stick", 8, 16),
          new StockpileGoal("wooden_axe", "minecraft:wooden_axe", 1, 1),
          new StockpileGoal("wooden_pickaxe", "minecraft:wooden_pickaxe", 1, 1),
          new StockpileGoal("wooden_shovel", "minecraft:wooden_shovel", 1, 1),
          new StockpileGoal("cobblestone", "minecraft:cobblestone", 16, 32));
    }

    /**
     * @param key  an item id, or an item tag with a leading {@code #}.
     * @param item an item.
     * @return whether the item is meant by the key.
     */
    public static boolean matches(final String key, final Item item)
    {
        if (key.startsWith("#"))
        {
            return new ItemStack(item).is(TagKey.create(Registries.ITEM, Identifier.parse(key.substring(1))));
        }
        return BuiltInRegistries.ITEM.getKey(item).toString().equals(key);
    }

    @Override
    protected void apply(final @NotNull Map<Identifier, JsonElement> files, final @NotNull ResourceManager resourceManager, final @NotNull ProfilerFiller profiler)
    {
        final Map<String, List<String>> newCoverage = new LinkedHashMap<>();
        final Map<String, StockpileGoal> newStockpile = new LinkedHashMap<>();
        boolean any = false;
        for (final Map.Entry<Identifier, JsonElement> entry : new TreeMap<>(files).entrySet())
        {
            try
            {
                if (entry.getKey().getPath().equals("coverage") && entry.getValue().isJsonObject())
                {
                    any = true;
                    for (final Map.Entry<String, JsonElement> e : entry.getValue().getAsJsonObject().entrySet())
                    {
                        final List<String> buildings = new ArrayList<>();
                        e.getValue().getAsJsonArray().forEach(b -> buildings.add(b.getAsString()));
                        newCoverage.put(e.getKey(), buildings);
                    }
                }
                else if (entry.getKey().getPath().equals("stockpile") && entry.getValue().isJsonArray())
                {
                    any = true;
                    for (final JsonElement element : entry.getValue().getAsJsonArray())
                    {
                        final JsonObject o = element.getAsJsonObject();
                        final String id = GsonHelper.getAsString(o, "id");
                        newStockpile.put(id, new StockpileGoal(id, GsonHelper.getAsString(o, "item"), Math.max(0, GsonHelper.getAsInt(o, "start_below")),
                          Math.max(1, GsonHelper.getAsInt(o, "target"))));
                    }
                }
            }
            catch (final RuntimeException e)
            {
                Log.getLogger().warn("Unreadable labour data file " + entry.getKey(), e);
            }
        }
        if (any)
        {
            coverage = newCoverage.isEmpty() ? defaultCoverage() : newCoverage;
            stockpile = newStockpile.isEmpty() ? defaultStockpile() : List.copyOf(newStockpile.values());
        }
        generation++;
    }
}
