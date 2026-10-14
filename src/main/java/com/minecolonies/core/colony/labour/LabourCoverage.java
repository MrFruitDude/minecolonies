package com.minecolonies.core.colony.labour;

import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.ICraftingBuildingModule;
import com.minecolonies.api.colony.labour.LabourModes;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Does a specialist cover this good?" (LABOURERS.md 3.3). Labourers never make a good that is covered, not even as an intermediate.
 * <ul>
 *   <li>Craft coverage: a building of the colony (level 1 or more) has a crafting module with a recipe for the item. In the {@code BUILT} mode that is enough, the
 *   worker may be missing, asleep or busy; in the {@code STAFFED} mode the building needs a worker.</li>
 *   <li>Gather coverage: for a raw good, a gatherer building of its class ({@code labour/coverage.json}) of level 1 or more.</li>
 * </ul>
 * The answer is memoised per item for {@link #TTL} ticks and dropped when the data reload; a good that was covered stays covered for the coverage grace
 * ({@link LabourSettings#coverageGrace}) after the last covering building is gone or loses its worker, so that a demolish and rebuild or an upgrade does not make
 * the labourers flap. A good that becomes covered is covered at the next look.
 */
public final class LabourCoverage
{
    /**
     * Ticks an answer is reused.
     */
    public static final int TTL = 100;

    private static final class Entry
    {
        boolean raw;
        long    at;
        long    coveredUntil;
    }

    private final Colony colony;
    private final Map<Item, Entry> cache = new HashMap<>();
    private int generation = -1;

    public LabourCoverage(final Colony colony)
    {
        this.colony = colony;
    }

    /**
     * Forgets the memo (a building was built or removed, a test changed the colony).
     */
    public void invalidate()
    {
        cache.clear();
    }

    /**
     * @param item an item.
     * @return whether a specialist of the colony covers it.
     */
    public boolean covered(final Item item)
    {
        if (colony.getWorld() == null)
        {
            return false;
        }
        final long now = colony.getWorld().getGameTime();
        if (generation != LabourDataListener.generation())
        {
            cache.clear();
            generation = LabourDataListener.generation();
        }
        Entry entry = cache.get(item);
        if (entry == null || now - entry.at >= TTL || now < entry.at)
        {
            final Entry fresh = new Entry();
            fresh.raw = raw(item);
            fresh.at = now;
            fresh.coveredUntil = fresh.raw ? now + LabourSettings.coverageGrace() : entry == null ? 0L : entry.coveredUntil;
            cache.put(item, fresh);
            entry = fresh;
        }
        return entry.raw || now < entry.coveredUntil;
    }

    private boolean raw(final Item item)
    {
        final boolean staffed = LabourSettings.coverageMode() == LabourModes.CoverageMode.STAFFED;
        final ItemStack probe = new ItemStack(item);
        List<String> gatherers = List.of();
        for (final Map.Entry<String, List<String>> entry : LabourDataListener.coverage().entrySet())
        {
            if (LabourDataListener.matches(entry.getKey(), item))
            {
                gatherers = entry.getValue();
                break;
            }
        }
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            if (building.getBuildingLevel() <= 0)
            {
                continue;
            }
            if (!gatherers.isEmpty() && gatherers.contains(building.getBuildingType().getRegistryName().toString())
                  && (!staffed || hasWorker(building, null)))
            {
                return true;
            }
            for (final ICraftingBuildingModule module : building.getModulesByType(ICraftingBuildingModule.class))
            {
                if (module.getFirstRecipe(stack -> stack.is(probe.getItem())) != null && (!staffed || hasWorker(building, module)))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasWorker(final IBuilding building, final ICraftingBuildingModule module)
    {
        for (final WorkerBuildingModule worker : building.getModulesByType(WorkerBuildingModule.class))
        {
            if (worker.hasAssignedCitizen() && (module == null || module.getCraftingJob() == null || worker.getJobEntry() == module.getCraftingJob().getJobRegistryEntry()))
            {
                return true;
            }
        }
        return false;
    }
}
