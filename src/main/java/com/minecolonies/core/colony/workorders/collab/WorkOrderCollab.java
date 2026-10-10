package com.minecolonies.core.colony.workorders.collab;

import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.core.entity.ai.workers.util.BuildingProgressStage;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The part of a builder work order that is shared between the builder that leads it and the builders that help it: the
 * progress cursor of the structure (formerly kept per hut), the positions that helpers have leased out of that cursor,
 * the helpers themselves and block counts for the progress display. Everything here is touched on the server thread
 * only.
 * <p>
 * A lease is a world position that a helper has taken out of the stream of positions the lead walks through. The lead's
 * iteration skips leased positions, the lead does not finish a stage while leases are open, and a lease that is released
 * or expires makes the lead scan the stage again from its start ({@link #isRescanRequested()}), so a position is never
 * placed twice nor skipped.
 */
public class WorkOrderCollab
{
    private static final String TAG_PROGRESS_POS   = "pos";
    private static final String TAG_PROGRESS_STAGE = "stage";
    private static final String TAG_STAGE_TOTALS   = "stageTotals";
    private static final String TAG_STAGE_DONE     = "stageDone";
    private static final String TAG_LEASES         = "leases";
    private static final String TAG_LEASE_POS      = "p";
    private static final String TAG_LEASE_OWNER    = "o";
    private static final String TAG_LEASE_EXPIRY   = "e";
    private static final String TAG_ASSISTANTS     = "assistants";
    private static final String TAG_ASSISTANT_POS  = "p";
    private static final String TAG_ASSISTANT_SINCE = "s";
    private static final String TAG_ASSISTANT_STRIKES = "x";
    private static final String TAG_SCAN_CURSOR    = "scan";
    private static final String TAG_SCAN_DONE      = "scanDone";
    private static final String TAG_RESCAN         = "rescan";
    private static final String TAG_PREVIOUS_LEAD  = "previousLead";
    private static final String TAG_COOLDOWNS      = "cooldowns";

    /**
     * The order of the stages for the progress display. Stages that do not take part in the stage list of an order have
     * no total and do not count.
     */
    private static final BuildingProgressStage[] DISPLAY_ORDER = {
      BuildingProgressStage.REMOVE_WATER, BuildingProgressStage.REMOVE,
      BuildingProgressStage.CLEAR, BuildingProgressStage.BUILD_SOLID, BuildingProgressStage.WEAK_SOLID,
      BuildingProgressStage.CLEAR_WATER, BuildingProgressStage.CLEAR_NON_SOLIDS, BuildingProgressStage.DECORATE, BuildingProgressStage.SPAWN};

    /**
     * A leased position.
     *
     * @param owner   the hut of the builder that works on it.
     * @param expiry  the game time after which the lease is void unless renewed.
     * @param transient_ true for a lease the lead holds on the position it is blocked on; it is not saved.
     */
    public record Lease(BlockPos owner, long expiry, boolean transient_)
    {
    }

    /**
     * A builder that helps with the order.
     */
    public static final class Assistant
    {
        public final BlockPos hut;
        public final long     since;
        public int            strikes;

        Assistant(final BlockPos hut, final long since, final int strikes)
        {
            this.hut = hut;
            this.since = since;
            this.strikes = strikes;
        }
    }

    // progress cursor of the lead (blueprint-local position and the stage it belongs to)
    @Nullable
    private BlockPos              progressPos;
    @Nullable
    private BuildingProgressStage progressStage;

    // block counts for the display
    private final int[] stageTotals = new int[BuildingProgressStage.values().length];
    private int stageDone;

    // leases and helpers
    private final Map<Long, Lease>       leases     = new LinkedHashMap<>();
    private final Map<BlockPos, Assistant> assistants = new LinkedHashMap<>();
    /**
     * Huts that left the order and may not come back before the stored game time (stops flapping).
     */
    private final Map<BlockPos, Long>    cooldowns  = new LinkedHashMap<>();

    @Nullable
    private BlockPos scanCursor;
    private boolean  scanExhausted;
    private boolean  rescan;

    /**
     * The hut that led the order before the current one, whose delivered materials the current lead may take over.
     */
    @Nullable
    private BlockPos previousLead;

    /**
     * Set whenever something changed that clients show (helpers, counts); read and cleared by the work order.
     */
    private boolean viewDirty;
    private int     lastSyncedPlaced = -1;

    // ------------------------------------------------------------------ progress cursor

    @Nullable
    public BlockPos getProgressPos()
    {
        return progressPos;
    }

    @Nullable
    public BuildingProgressStage getProgressStage()
    {
        return progressStage;
    }

    public boolean hasProgress()
    {
        return progressPos != null;
    }

    /**
     * Sets the cursor of the lead.
     *
     * @param pos   the last processed blueprint-local position or null for none.
     * @param stage its stage.
     * @return true if the stage changed.
     */
    public boolean setProgress(@Nullable final BlockPos pos, @Nullable final BuildingProgressStage stage)
    {
        final boolean stageChanged = stage != progressStage;
        this.progressPos = pos;
        this.progressStage = stage;
        if (stageChanged)
        {
            stageDone = 0;
            scanCursor = null;
            scanExhausted = false;
            viewDirty = true;
        }
        return stageChanged;
    }

    // ------------------------------------------------------------------ counts

    /**
     * Sets the block totals per stage (index = stage ordinal).
     */
    public void setStageTotals(final int[] totals)
    {
        System.arraycopy(totals, 0, stageTotals, 0, Math.min(totals.length, stageTotals.length));
        viewDirty = true;
    }

    public void setStageTotal(final BuildingProgressStage stage, final int total)
    {
        stageTotals[stage.ordinal()] = total;
        viewDirty = true;
    }

    public boolean hasStageTotals()
    {
        return getTotalBlocks() > 0;
    }

    public int getTotalBlocks()
    {
        int total = 0;
        for (final int t : stageTotals)
        {
            total += t;
        }
        return total;
    }

    /**
     * Counts one processed block in the current stage.
     */
    public void blockProcessed()
    {
        stageDone++;
    }

    /**
     * Blocks processed so far: the totals of the stages before the current one plus the blocks done in it.
     */
    public int getPlacedBlocks()
    {
        final BuildingProgressStage stage = progressStage;
        if (stage == null)
        {
            return 0;
        }
        int placed = 0;
        for (final BuildingProgressStage s : DISPLAY_ORDER)
        {
            if (s == stage)
            {
                placed += Math.min(stageDone, stageTotals[s.ordinal()]);
                break;
            }
            placed += stageTotals[s.ordinal()];
        }
        return placed;
    }

    public int getRemainingBlocks()
    {
        return Math.max(0, getTotalBlocks() - getPlacedBlocks());
    }

    /**
     * Whether the displayed numbers changed enough to be worth a client update.
     */
    public boolean consumeViewDirty()
    {
        final int placed = getPlacedBlocks();
        final int total = getTotalBlocks();
        final boolean progressed = total > 0 && (lastSyncedPlaced < 0 || Math.abs(placed - lastSyncedPlaced) * 50 >= total);
        if (viewDirty || progressed)
        {
            viewDirty = false;
            lastSyncedPlaced = placed;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ leases

    public static long key(final BlockPos worldPos)
    {
        return worldPos.asLong();
    }

    public boolean isLeased(final BlockPos worldPos)
    {
        return !leases.isEmpty() && leases.containsKey(key(worldPos));
    }

    /**
     * True if the position is leased to a builder other than the given hut.
     */
    public boolean isLeasedByOther(final BlockPos worldPos, final BlockPos me)
    {
        if (leases.isEmpty())
        {
            return false;
        }
        final Lease lease = leases.get(key(worldPos));
        return lease != null && !lease.owner().equals(me);
    }

    public int getLeaseCount()
    {
        return leases.size();
    }

    public int getLeaseCountOf(final BlockPos owner)
    {
        int n = 0;
        for (final Lease lease : leases.values())
        {
            if (lease.owner().equals(owner) && !lease.transient_())
            {
                n++;
            }
        }
        return n;
    }

    /**
     * Open leases held by builders other than the lead (these hold up the end of a stage).
     */
    public int getOpenLeasesExcluding(final BlockPos lead)
    {
        int n = 0;
        for (final Lease lease : leases.values())
        {
            if (!lease.owner().equals(lead))
            {
                n++;
            }
        }
        return n;
    }

    public List<BlockPos> getLeasedPositions(final BlockPos owner)
    {
        final List<BlockPos> list = new ArrayList<>();
        for (final Map.Entry<Long, Lease> entry : leases.entrySet())
        {
            if (entry.getValue().owner().equals(owner) && !entry.getValue().transient_())
            {
                list.add(BlockPos.of(entry.getKey()));
            }
        }
        return list;
    }

    public void lease(final BlockPos worldPos, final BlockPos owner, final long expiry, final boolean transientLease)
    {
        leases.put(key(worldPos), new Lease(owner, expiry, transientLease));
        viewDirty = true;
    }

    /**
     * A lease is done: the position was placed or mined (or turned out to need nothing).
     */
    public void completeLease(final BlockPos worldPos)
    {
        leases.remove(key(worldPos));
    }

    /**
     * The lead's lease on the position it is blocked on (one at a time); null clears it.
     */
    public void setLeadPending(final BlockPos lead, @Nullable final BlockPos worldPos)
    {
        leases.values().removeIf(l -> l.transient_() && l.owner().equals(lead));
        if (worldPos != null)
        {
            leases.put(key(worldPos), new Lease(lead, Long.MAX_VALUE, true));
        }
    }

    /**
     * Gives the leased position back unfinished: the lead scans the stage again.
     */
    public void releaseLease(final BlockPos worldPos)
    {
        if (leases.remove(key(worldPos)) != null)
        {
            rescan = true;
            viewDirty = true;
        }
    }

    /**
     * Gives back every lease of the hut.
     *
     * @return how many leases were open.
     */
    public int releaseAll(final BlockPos owner)
    {
        int n = 0;
        for (final Iterator<Lease> it = leases.values().iterator(); it.hasNext(); )
        {
            final Lease lease = it.next();
            if (lease.owner().equals(owner))
            {
                it.remove();
                n++;
            }
        }
        if (n > 0)
        {
            rescan = true;
            viewDirty = true;
        }
        return n;
    }

    /**
     * Extends all leases of the hut.
     */
    public void renew(final BlockPos owner, final long expiry)
    {
        leases.replaceAll((k, lease) -> lease.owner().equals(owner) && !lease.transient_() ? new Lease(owner, expiry, false) : lease);
    }

    /**
     * Drops leases that were not renewed in time.
     *
     * @return the owners of the expired leases.
     */
    public List<BlockPos> expireLeases(final long now)
    {
        List<BlockPos> owners = null;
        for (final Iterator<Lease> it = leases.values().iterator(); it.hasNext(); )
        {
            final Lease lease = it.next();
            if (lease.expiry() < now)
            {
                if (owners == null)
                {
                    owners = new ArrayList<>();
                }
                if (!owners.contains(lease.owner()))
                {
                    owners.add(lease.owner());
                }
                it.remove();
            }
        }
        if (owners == null)
        {
            return List.of();
        }
        rescan = true;
        viewDirty = true;
        return owners;
    }

    /**
     * True once if the lead has to scan the stage again from its start; the cursor then goes back to the start.
     */
    public boolean consumeRescan()
    {
        if (!rescan)
        {
            return false;
        }
        rescan = false;
        scanCursor = null;
        scanExhausted = false;
        return true;
    }

    public boolean isRescanRequested()
    {
        return rescan;
    }

    public void requestRescan()
    {
        rescan = true;
    }

    // scan cursor of the helpers

    @Nullable
    public BlockPos getScanCursor()
    {
        return scanCursor;
    }

    public void setScanCursor(@Nullable final BlockPos pos)
    {
        scanCursor = pos;
    }

    public boolean isScanExhausted()
    {
        return scanExhausted;
    }

    public void setScanExhausted(final boolean exhausted)
    {
        scanExhausted = exhausted;
    }

    // ------------------------------------------------------------------ helpers

    public boolean hasAssistant(final BlockPos hut)
    {
        return assistants.containsKey(hut);
    }

    public int getAssistantCount()
    {
        return assistants.size();
    }

    public List<BlockPos> getAssistantHuts()
    {
        return new ArrayList<>(assistants.keySet());
    }

    @Nullable
    public Assistant getAssistant(final BlockPos hut)
    {
        return assistants.get(hut);
    }

    public Iterable<Assistant> getAssistants()
    {
        return assistants.values();
    }

    public void addAssistant(final BlockPos hut, final long now)
    {
        if (!assistants.containsKey(hut))
        {
            assistants.put(hut, new Assistant(hut, now, 0));
            viewDirty = true;
        }
    }

    /**
     * Removes the helper, gives its leases back and keeps it from joining again until the cooldown is over.
     */
    public void removeAssistant(final BlockPos hut, final long cooldownUntil)
    {
        if (assistants.remove(hut) != null)
        {
            viewDirty = true;
        }
        releaseAll(hut);
        if (cooldownUntil > 0)
        {
            cooldowns.put(hut, cooldownUntil);
        }
    }

    public boolean isOnCooldown(final BlockPos hut, final long now)
    {
        final Long until = cooldowns.get(hut);
        if (until == null)
        {
            return false;
        }
        if (until <= now)
        {
            cooldowns.remove(hut);
            return false;
        }
        return true;
    }

    @Nullable
    public BlockPos getPreviousLead()
    {
        return previousLead;
    }

    public void setPreviousLead(@Nullable final BlockPos pos)
    {
        previousLead = pos;
    }

    // ------------------------------------------------------------------ persistence

    public void write(@NotNull final CompoundTag compound)
    {
        final CompoundTag tag = new CompoundTag();
        if (progressPos != null)
        {
            BlockPosUtil.write(tag, TAG_PROGRESS_POS, progressPos);
        }
        if (progressStage != null)
        {
            tag.putInt(TAG_PROGRESS_STAGE, progressStage.ordinal());
        }
        tag.putIntArray(TAG_STAGE_TOTALS, stageTotals);
        tag.putInt(TAG_STAGE_DONE, stageDone);

        final ListTag leaseList = new ListTag();
        for (final Map.Entry<Long, Lease> entry : leases.entrySet())
        {
            if (entry.getValue().transient_())
            {
                continue;
            }
            final CompoundTag lease = new CompoundTag();
            lease.putLong(TAG_LEASE_POS, entry.getKey());
            BlockPosUtil.write(lease, TAG_LEASE_OWNER, entry.getValue().owner());
            lease.putLong(TAG_LEASE_EXPIRY, entry.getValue().expiry());
            leaseList.add(lease);
        }
        tag.put(TAG_LEASES, leaseList);

        final ListTag assistantList = new ListTag();
        for (final Assistant assistant : assistants.values())
        {
            final CompoundTag a = new CompoundTag();
            BlockPosUtil.write(a, TAG_ASSISTANT_POS, assistant.hut);
            a.putLong(TAG_ASSISTANT_SINCE, assistant.since);
            a.putInt(TAG_ASSISTANT_STRIKES, assistant.strikes);
            assistantList.add(a);
        }
        tag.put(TAG_ASSISTANTS, assistantList);

        final ListTag cooldownList = new ListTag();
        for (final Map.Entry<BlockPos, Long> entry : cooldowns.entrySet())
        {
            final CompoundTag c = new CompoundTag();
            BlockPosUtil.write(c, TAG_ASSISTANT_POS, entry.getKey());
            c.putLong(TAG_LEASE_EXPIRY, entry.getValue());
            cooldownList.add(c);
        }
        tag.put(TAG_COOLDOWNS, cooldownList);

        if (scanCursor != null)
        {
            BlockPosUtil.write(tag, TAG_SCAN_CURSOR, scanCursor);
        }
        tag.putBoolean(TAG_SCAN_DONE, scanExhausted);
        tag.putBoolean(TAG_RESCAN, rescan);
        if (previousLead != null)
        {
            BlockPosUtil.write(tag, TAG_PREVIOUS_LEAD, previousLead);
        }
        compound.put("collab", tag);
    }

    public void read(@NotNull final CompoundTag compound)
    {
        final CompoundTag tag = compound.getCompoundOrEmpty("collab");
        progressPos = tag.contains(TAG_PROGRESS_POS) ? BlockPosUtil.read(tag, TAG_PROGRESS_POS) : null;
        progressStage = tag.contains(TAG_PROGRESS_STAGE) ? BuildingProgressStage.values()[tag.getIntOr(TAG_PROGRESS_STAGE, 0)] : null;
        final int[] totals = tag.getIntArray(TAG_STAGE_TOTALS).orElse(new int[0]);
        java.util.Arrays.fill(stageTotals, 0);
        System.arraycopy(totals, 0, stageTotals, 0, Math.min(totals.length, stageTotals.length));
        stageDone = tag.getIntOr(TAG_STAGE_DONE, 0);

        leases.clear();
        final ListTag leaseList = tag.getListOrEmpty(TAG_LEASES);
        for (int i = 0; i < leaseList.size(); i++)
        {
            final CompoundTag lease = leaseList.getCompoundOrEmpty(i);
            leases.put(lease.getLongOr(TAG_LEASE_POS, 0L), new Lease(BlockPosUtil.read(lease, TAG_LEASE_OWNER), lease.getLongOr(TAG_LEASE_EXPIRY, 0L), false));
        }

        assistants.clear();
        final ListTag assistantList = tag.getListOrEmpty(TAG_ASSISTANTS);
        for (int i = 0; i < assistantList.size(); i++)
        {
            final CompoundTag a = assistantList.getCompoundOrEmpty(i);
            final BlockPos hut = BlockPosUtil.read(a, TAG_ASSISTANT_POS);
            assistants.put(hut, new Assistant(hut, a.getLongOr(TAG_ASSISTANT_SINCE, 0L), a.getIntOr(TAG_ASSISTANT_STRIKES, 0)));
        }

        cooldowns.clear();
        final ListTag cooldownList = tag.getListOrEmpty(TAG_COOLDOWNS);
        for (int i = 0; i < cooldownList.size(); i++)
        {
            final CompoundTag c = cooldownList.getCompoundOrEmpty(i);
            cooldowns.put(BlockPosUtil.read(c, TAG_ASSISTANT_POS), c.getLongOr(TAG_LEASE_EXPIRY, 0L));
        }

        scanCursor = tag.contains(TAG_SCAN_CURSOR) ? BlockPosUtil.read(tag, TAG_SCAN_CURSOR) : null;
        scanExhausted = tag.getBooleanOr(TAG_SCAN_DONE, false);
        rescan = tag.getBooleanOr(TAG_RESCAN, false);
        previousLead = tag.contains(TAG_PREVIOUS_LEAD) ? BlockPosUtil.read(tag, TAG_PREVIOUS_LEAD) : null;
    }

    // ------------------------------------------------------------------ client view

    /**
     * What clients get to see; the citizen ids are those of the helpers' builders (-1 if a hut has none).
     */
    public void writeView(@NotNull final RegistryFriendlyByteBuf buf, final java.util.function.ToIntFunction<BlockPos> citizenOfHut)
    {
        buf.writeInt(getPlacedBlocks());
        buf.writeInt(getTotalBlocks());
        buf.writeInt(leases.size());
        buf.writeInt(assistants.size());
        for (final Assistant assistant : assistants.values())
        {
            buf.writeBlockPos(assistant.hut);
            buf.writeInt(citizenOfHut.applyAsInt(assistant.hut));
        }
    }
}
