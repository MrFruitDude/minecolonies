package com.minecolonies.core.entity.ai.idle;

import com.ldtteam.structurize.placement.StructurePhasePlacementResult;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.IColonyPaceProvider;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.idle.IIdleTaskSession;
import com.minecolonies.api.colony.idle.IdleStopReason;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.constant.TranslationConstants;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.idle.IdleCitizenArbiter;
import com.minecolonies.core.colony.idle.IdleCitizenSettings;
import com.minecolonies.core.colony.workorders.collab.AssistReservations;
import com.minecolonies.core.colony.workorders.collab.BuilderCollab;
import com.minecolonies.core.colony.workorders.collab.CollabScheduler;
import com.minecolonies.core.colony.workorders.collab.IdleHelperKey;
import com.minecolonies.core.colony.workorders.collab.WorkOrderCollab;
import com.minecolonies.core.entity.ai.workers.util.BuildReach;
import com.minecolonies.core.entity.ai.workers.util.BuilderStageRules;
import com.minecolonies.core.entity.ai.workers.util.BuildingProgressStage;
import com.minecolonies.core.entity.ai.workers.util.CollabStructureHandler;
import com.minecolonies.core.entity.ai.workers.util.LeaseAllocator;
import com.minecolonies.core.entity.ai.workers.util.ReachSpots;
import com.minecolonies.core.entity.ai.workers.util.StandSpots;
import com.minecolonies.core.entity.ai.workers.util.WorkTiming;
import com.minecolonies.core.entity.ai.workers.util.WorkerSettings;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import com.minecolonies.core.util.ItemMover;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static com.minecolonies.api.research.util.ResearchConstants.BLOCK_BREAK_SPEED;
import static com.minecolonies.api.research.util.ResearchConstants.BLOCK_PLACE_SPEED;

/**
 * A citizen without a job helps the builder who leads an order (see {@link BuilderHelperTask}). The work is the helper's work of a builder, on the shared state of the order
 * ({@link WorkOrderCollab}): he leases a few positions of the stage in his part of the structure, takes the materials for them from the lead's hut or from the lead,
 * walks to a spot on the ground from which he reaches each position and places or breaks it, then leases the next ones. What he cannot do (out of reach from the ground,
 * a material the lead does not have) goes back to the lead.
 * <p>
 * He is slower than a builder: he earns work time at {@link IdleCitizenSettings#helperSpeed()} of the time that passes, and a block costs him what it would cost the lead.
 * He never asks for anything: a request is the lead's. When he stops, for whatever reason, his leases go back and what he carries goes back into the lead's hut.
 */
public final class BuilderHelperSession implements IIdleTaskSession
{
    private enum Phase
    {
        PLAN,
        GATHER,
        WORK,
        MINE
    }

    /**
     * Positions right after the lead's cursor that helpers leave to the lead.
     */
    private static final int LEAD_BUFFER = 4;

    /**
     * The smallest lease towards the end of a stage.
     */
    private static final int MIN_SHARE = 2;

    /**
     * Free inventory slots he keeps open.
     */
    private static final int OPEN_SLOTS = 3;

    /**
     * Steps (ten ticks each) he tries to get his materials before the positions go back.
     */
    private static final int MAX_GATHER_STEPS = 120;

    /**
     * Ticks between two looks at the order while nothing is to be done.
     */
    private static final int IDLE_WAIT = 40;

    /**
     * The most blocks he places in one step.
     */
    private static final int MAX_PER_STEP = 6;

    /**
     * The fastest tool he borrows from the lead is a given up as an assumption: at least this destroy speed (a hand is no tool for a wall).
     */
    private static final double MIN_TOOL_SPEED = 2.0D;

    /**
     * Ticks after which a spot he walks to that he does not reach is given up.
     */
    private static final long SPOT_BUDGET = 400L;

    private final Colony       colony;
    private final ICitizenData citizen;
    private final BlockPos     key;

    private Phase                              phase        = Phase.PLAN;
    private IBuilderWorkOrder                  order;
    private CollabStructureHandler             handler;
    private StructurePlacer                    placer;
    private BuildingProgressStage              batchStage;
    private final List<LeaseAllocator.Planned> batch        = new ArrayList<>();
    private final Set<BlockPos>                gaveUp       = new HashSet<>();
    private BlockPos                           mining;
    private long                               nextStep     = 0L;
    private long                               nextRescanAt = 0L;
    private int                                gatherSteps  = 0;
    private boolean                            gatherFromLead = false;

    /**
     * What he took for the order (out of the hut and the lead) and has not used: it goes back into the lead's hut when he stops; and what he got from breaking blocks.
     */
    private final Map<ItemStorage, Integer> carry = new HashMap<>();

    // reach: the spot he walks to, since when, and the spots that did not work
    private BlockPos            spot;
    private long                spotSince;
    private final Set<BlockPos> badSpots = new HashSet<>();

    // work time
    private double bank     = 0.0D;
    private long   lastBank = -1L;

    BuilderHelperSession(final IColony colony, final ICitizenData citizen, final IBuilderWorkOrder order)
    {
        this.colony = (Colony) colony;
        this.citizen = citizen;
        this.key = IdleHelperKey.of(citizen.getId());
        this.order = order;
    }

    // ------------------------------------------------------------------ the session

    @Override
    public String getTaskId()
    {
        return BuilderHelperTask.ID;
    }

    @Override
    public boolean isValid()
    {
        final IBuilderWorkOrder current = colony.getWorkManager().getAssistedOrder(key);
        return current != null && CollabScheduler.leadOf(colony, current) != null;
    }

    @Override
    public Component describe()
    {
        return Component.translatable(TranslationConstants.COM_MINECOLONIES_COREMOD_IDLE_HELPER);
    }

    /**
     * @return the order he helps with, null if he does not any more.
     */
    @Nullable
    public IBuilderWorkOrder getOrder()
    {
        return colony.getWorkManager().getAssistedOrder(key);
    }

    /**
     * @return what he carries for the order (taken from the lead, not used yet; and what he got from breaking blocks), by item.
     */
    public Map<ItemStorage, Integer> carry()
    {
        return carry;
    }

    /**
     * The lead took materials back from him (he was short of them himself).
     *
     * @param kind   the item.
     * @param amount how many.
     */
    public void reduceCarry(final ItemStorage kind, final int amount)
    {
        carry.computeIfPresent(kind, (k, have) -> have - amount > 0 ? have - amount : null);
    }

    @Override
    public void stop(final IdleStopReason reason)
    {
        final IBuilderWorkOrder current = colony.getWorkManager().getAssistedOrder(key);
        final long now = colony.getWorld() == null ? 0L : colony.getWorld().getGameTime();
        if (current != null)
        {
            // his leases go back to the lead (who scans the stage again for them); he does not come back for a while after a need
            current.getCollab().removeAssistant(key, reason == IdleStopReason.TASK_INVALID || reason == IdleStopReason.CITIZEN_GONE ? 0 : now + 200L);
            colony.markDirty();
        }
        AssistReservations.releaseAll(colony, key, false);
        batch.clear();
        mining = null;
        giveBackToLead(current);
        final AbstractEntityCitizen worker = citizen.getEntity().orElse(null);
        if (worker != null)
        {
            worker.getNavigation().stop();
            worker.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    // ------------------------------------------------------------------ the steps

    @Override
    public void tick()
    {
        final AbstractEntityCitizen worker = citizen.getEntity().orElse(null);
        if (worker == null || worker.level().isClientSide())
        {
            return;
        }
        final Level world = worker.level();
        order = colony.getWorkManager().getAssistedOrder(key);
        final AbstractBuildingStructureBuilder lead = order == null ? null : CollabScheduler.leadOf(colony, order);
        if (order == null || lead == null || citizen.getJob() != null)
        {
            // the arbiter ends the session within a second
            return;
        }
        final long now = world.getGameTime();
        final WorkOrderCollab collab = order.getCollab();
        collab.renew(key, now + BuilderCollab.leaseTicks());
        earnWorkTime(now, lead);

        if (now < nextStep)
        {
            return;
        }
        switch (phase)
        {
            case PLAN -> plan(worker, world, lead, collab, now);
            case GATHER -> gather(worker, world, lead, collab, now);
            case WORK -> work(worker, world, lead, collab, now);
            case MINE -> mine(worker, world, lead, collab, now);
        }
    }

    private void waitTicks(final long now, final int ticks)
    {
        nextStep = now + ticks;
    }

    // ------------------------------------------------------------------ time

    private double leadPlaceTicks(final AbstractBuildingStructureBuilder lead)
    {
        final WorkerBuildingModule module = lead.getFirstModuleOccurance(WorkerBuildingModule.class);
        final ICitizenData leadCitizen = module.getFirstCitizen();
        final int level = leadCitizen == null ? 1 : leadCitizen.getCitizenSkillHandler().getLevel(module.getPrimarySkill());
        final double research = colony.getResearchManager().getResearchEffects().getEffectStrength(BLOCK_PLACE_SPEED);
        return WorkTiming.placeTicks(level, research, WorkerSettings.builderSpeed());
    }

    private double leadBreakTicks(final AbstractBuildingStructureBuilder lead, final BlockState state, final BlockPos pos, final ItemStack tool)
    {
        final WorkerBuildingModule module = lead.getFirstModuleOccurance(WorkerBuildingModule.class);
        final ICitizenData leadCitizen = module.getFirstCitizen();
        final int level = leadCitizen == null ? 1 : leadCitizen.getCitizenSkillHandler().getLevel(module.getSecondarySkill());
        final double research = colony.getResearchManager().getResearchEffects().getEffectStrength(BLOCK_BREAK_SPEED);
        final double toolSpeed = Math.max(MIN_TOOL_SPEED, tool.isEmpty() ? 1.0D : tool.getItem().getDestroySpeed(tool, state));
        // (a builder breaks at half the time of other workers)
        return WorkTiming.breakTicks(level, state.getDestroySpeed(colony.getWorld(), pos), toolSpeed, 0.5D, research, WorkerSettings.builderSpeed());
    }

    /**
     * The pace the lead works at: the colony pace of the pace provider for his job, times the worker speed of the settings (the same pace his own AI counts its waits with).
     */
    private double leadPace(final AbstractBuildingStructureBuilder lead)
    {
        final ICitizenData leadCitizen = leadCitizen(lead);
        final double provider = leadCitizen == null || leadCitizen.getJob() == null ? 1.0D
                                  : IColonyPaceProvider.sanitize(IMinecoloniesAPI.getInstance().getColonyPaceProvider().work(colony, leadCitizen.getJob()));
        return WorkerSettings.composePace(provider, WorkerSettings.speedMultiplier());
    }

    /**
     * The work time bank: it earns the time that passes at the pace of the lead times the helper's share ({@link IdleCitizenSettings#helperSpeed()}); a block costs what it
     * costs the lead (so he places and breaks at that share of the builder's speed).
     */
    private void earnWorkTime(final long now, final AbstractBuildingStructureBuilder lead)
    {
        final double pace = leadPace(lead) * IdleCitizenSettings.helperSpeed();
        final double cap = Math.max(20.0D, 3.0D * leadPlaceTicks(lead));
        bank = lastBank < 0 ? 0.0D : Math.min(cap, bank + (now - lastBank) * pace);
        lastBank = now;
    }

    // ------------------------------------------------------------------ plan

    private void plan(final AbstractEntityCitizen worker, final Level world, final AbstractBuildingStructureBuilder lead, final WorkOrderCollab collab, final long now)
    {
        if (order.getBlueprint() == null)
        {
            order.loadBlueprint(world, blueprint -> { });
            waitTicks(now, 40);
            return;
        }
        final BuildingProgressStage stage = collab.getProgressStage();
        if (stage == null || !BuilderStageRules.isSharedStage(stage) || order.getIteratorType().isEmpty())
        {
            // nothing to share right now: he waits at the site
            dropBatch(collab);
            idleAtTheSite(worker, now);
            return;
        }
        ensureHandler(worker, world, lead, stage, collab);

        if (batch.isEmpty())
        {
            // leases that survived a reload
            for (final BlockPos leased : collab.getLeasedPositions(key))
            {
                final BlockPos local = handler.getStructurePosFromWorld(leased);
                batch.add(new LeaseAllocator.Planned(local, leased, LeaseAllocator.requirements(world, placer, handler, local, leased)));
            }
        }
        if (batch.isEmpty() && !collab.isTerritoryDissolved() && collab.getTotalBlocks() >= BuilderCollab.clusterMinBlocks())
        {
            final List<BlockPos> participants = new ArrayList<>();
            participants.add(lead.getID());
            participants.addAll(collab.getAssistantHuts());
            if (!collab.territoryCovers(participants))
            {
                // the lead and the helpers divide the rest of the stage between them
                LeaseAllocator.divideStage(world, order, stage, handler, placer, participants);
                collab.setScanCursor(null);
                collab.setScanExhausted(false);
            }
        }
        if (batch.isEmpty())
        {
            if (collab.isScanExhausted() && collab.hasTerritory())
            {
                // nothing left in his part: the parts are given up
                collab.dissolveTerritory();
            }
            if (collab.isScanExhausted())
            {
                if (collab.getUnleasedStageRemaining() > 2 * LEAD_BUFFER && now >= nextRescanAt)
                {
                    nextRescanAt = now + 100L;
                    collab.setScanCursor(null);
                    collab.setScanExhausted(false);
                }
                else
                {
                    idleAtTheSite(worker, now);
                    return;
                }
            }
            final Map<ItemStorage, Integer> stockMemo = new HashMap<>();
            final int room = (int) Math.max(1, InventoryUtils.openSlotCount(worker.getInventoryCitizen()) - OPEN_SLOTS);
            final double reachHeight = reachLimitY();
            final List<LeaseAllocator.Planned> plan = LeaseAllocator.plan(world, order, stage, handler, placer, leaseSizeFor(collab, lead, worker), collab.hasTerritory() ? 0 : LEAD_BUFFER,
              k -> stockMemo.computeIfAbsent(k, kind -> Math.max(0, InventoryUtils.getItemCountInItemHandler(lead.getItemHandlerCap(), sameAs(kind.getItemStack()))
                                                                      - AssistReservations.heldByOthers(lead, key, kind.getItemStack()))
                                                        + leadCarried(lead, kind.getItemStack())
                                                        + InventoryUtils.getItemCountInItemHandler(worker.getInventoryCitizen(), sameAs(kind.getItemStack()))),
              room,
              pos -> gaveUp.contains(pos) || pos.getY() > reachHeight || collab.isInOthersTerritory(pos, key),
              collab.getTotalBlocks() < BuilderCollab.clusterMinBlocks() ? null : worker.blockPosition());
            if (plan.isEmpty())
            {
                idleAtTheSite(worker, now);
                return;
            }
            gatherFromLead = false;
            gatherSteps = 0;
            final long expiry = now + BuilderCollab.leaseTicks();
            for (final LeaseAllocator.Planned planned : plan)
            {
                collab.lease(planned.world(), key, expiry, false);
            }
            batch.addAll(plan);
        }
        for (final LeaseAllocator.Planned planned : batch)
        {
            if (!planned.required().isEmpty())
            {
                reserveBatchStock(lead, now);
                phase = Phase.GATHER;
                waitTicks(now, 10);
                return;
            }
        }
        phase = Phase.WORK;
    }

    /**
     * The highest world y a helper takes positions at: what he reaches from the ground at the foot of the structure (builders raise scaffolding for the rest). No limit
     * when the reach of builders is not physical.
     */
    private double reachLimitY()
    {
        if (!WorkerSettings.physicalReach())
        {
            return Double.MAX_VALUE;
        }
        return order.getLocation().getY() + BuildReach.maxHeightNextToWall(BuildReach.EYE_HEIGHT, WorkerSettings.reach()) + 1;
    }

    /**
     * How many positions he leases at once: the configured size, more when the hut of the lead is far (every lease costs a walk to the hut and back, and a lease of a few blocks
     * takes less to place than to fetch), like the builders who help; towards the end of the stage a share of what is left for each of the builders, twice over.
     */
    private int leaseSizeFor(final WorkOrderCollab collab, final AbstractBuildingStructureBuilder lead, final AbstractEntityCitizen worker)
    {
        final int size = BuilderCollab.scaledLeaseSize(BuilderCollab.leaseSize(), Math.sqrt(worker.blockPosition().distSqr(lead.getPosition())));
        final int parts = 2 * (collab.getAssistantCount() + 1);
        return Math.max(1, Math.min(size, Math.max(MIN_SHARE, collab.getUnleasedStageRemaining() / parts)));
    }

    private void ensureHandler(final AbstractEntityCitizen worker, final Level world, final AbstractBuildingStructureBuilder lead, final BuildingProgressStage stage,
      final WorkOrderCollab collab)
    {
        if (handler == null || placer == null || batchStage != stage || handler.getBluePrint() != order.getBlueprint())
        {
            if (batchStage != stage)
            {
                dropBatch(collab);
            }
            batchStage = stage;
            handler = new CollabStructureHandler(world, order, stage, worker, () -> lead.getSetting(com.minecolonies.core.colony.buildings.workerbuildings.BuildingMiner.FILL_BLOCK).getValue().getBlock().defaultBlockState());
            placer = new StructurePlacer(handler, order.getIteratorType());
        }
    }

    private void dropBatch(final WorkOrderCollab collab)
    {
        collab.releaseAll(key);
        batch.clear();
        mining = null;
        spot = null;
        AssistReservations.releaseAll(colony, key, false);
    }

    private void idleAtTheSite(final AbstractEntityCitizen worker, final long now)
    {
        // near the site, not on it
        if (BlockPosUtil.getDistance2D(worker.blockPosition(), order.getLocation()) > 14)
        {
            EntityNavigationUtils.walkToPos(worker, order.getLocation(), 10, true);
        }
        phase = Phase.PLAN;
        waitTicks(now, IDLE_WAIT);
    }

    // ------------------------------------------------------------------ materials

    private static Predicate<ItemStack> sameAs(final ItemStack stack)
    {
        return candidate -> ItemStackUtils.compareItemStacksIgnoreStackSize(stack, candidate);
    }

    private static ICitizenData leadCitizen(final AbstractBuildingStructureBuilder lead)
    {
        return lead.getFirstModuleOccurance(WorkerBuildingModule.class).getFirstCitizen();
    }

    private static int leadCarried(final AbstractBuildingStructureBuilder lead, final ItemStack kind)
    {
        final ICitizenData leadCitizen = leadCitizen(lead);
        return leadCitizen == null ? 0 : InventoryUtils.getItemCountInItemHandler(leadCitizen.getInventory(), sameAs(kind));
    }

    private Map<ItemStorage, Integer> batchNeeds()
    {
        final Map<ItemStorage, Integer> needs = new HashMap<>();
        for (final LeaseAllocator.Planned planned : batch)
        {
            for (final ItemStack stack : planned.required())
            {
                needs.merge(new ItemStorage(stack.copyWithCount(1)), stack.getCount(), Integer::sum);
            }
        }
        return needs;
    }

    /**
     * What the batch needs and he does not have yet.
     */
    private Map<ItemStorage, Integer> missingForBatch(final AbstractEntityCitizen worker)
    {
        final Map<ItemStorage, Integer> missing = new HashMap<>();
        for (final Map.Entry<ItemStorage, Integer> need : batchNeeds().entrySet())
        {
            final int have = InventoryUtils.getItemCountInItemHandler(worker.getInventoryCitizen(), sameAs(need.getKey().getItemStack()));
            if (have < need.getValue())
            {
                missing.put(need.getKey(), need.getValue() - have);
            }
        }
        return missing;
    }

    private void reserveBatchStock(final AbstractBuildingStructureBuilder lead, final long now)
    {
        final AbstractEntityCitizen worker = citizen.getEntity().orElse(null);
        if (worker == null)
        {
            return;
        }
        AssistReservations.releaseAll(colony, key, true);
        for (final Map.Entry<ItemStorage, Integer> missing : missingForBatch(worker).entrySet())
        {
            final ItemStack kind = missing.getKey().getItemStack();
            final int inHut = InventoryUtils.getItemCountInItemHandler(lead.getItemHandlerCap(), sameAs(kind)) - AssistReservations.heldByOthers(lead, key, kind);
            AssistReservations.reserve(key, lead, kind, Math.min(missing.getValue(), inHut), now);
        }
    }

    /**
     * Takes the materials for the batch out of the lead's hut (what is claimed for others stays), then from the lead himself, who keeps the bucket of materials he works
     * with in his inventory. Positions the stock does not cover go back to the lead.
     */
    private void gather(final AbstractEntityCitizen worker, final Level world, final AbstractBuildingStructureBuilder lead, final WorkOrderCollab collab, final long now)
    {
        if (pruneBatch(collab) && batch.isEmpty())
        {
            phase = Phase.PLAN;
            return;
        }
        final boolean giveUp = ++gatherSteps > MAX_GATHER_STEPS;
        Map<ItemStorage, Integer> missing = missingForBatch(worker);
        if (!giveUp && !missing.isEmpty() && !gatherFromLead)
        {
            if (!hutHoldsAnyOf(lead, missing) || leadCarriesAll(lead, missing))
            {
                gatherFromLead = true;
            }
            else
            {
                if (!EntityNavigationUtils.walkToBuilding(worker, lead))
                {
                    waitTicks(now, 10);
                    return;
                }
                takeFrom(worker, lead.getItemHandlerCap(), lead, missing, true, now);
                if (!missingForBatch(worker).isEmpty())
                {
                    gatherFromLead = true;
                    waitTicks(now, 10);
                    return;
                }
            }
        }
        missing = missingForBatch(worker);
        if (!giveUp && gatherFromLead && !missing.isEmpty())
        {
            final ICitizenData leadCitizen = leadCitizen(lead);
            if (leadCitizen != null)
            {
                if (leadCitizen.getEntity().isPresent() && !walkToLeadForHandOver(worker, world, leadCitizen.getEntity().get()))
                {
                    waitTicks(now, 10);
                    return;
                }
                takeFrom(worker, leadCitizen.getInventory(), lead, missing, false, now);
            }
        }
        gatherFromLead = false;
        gatherSteps = 0;
        // what is taken is taken: what was claimed and not taken is free again
        AssistReservations.releaseAll(colony, key, false);

        // positions the stock did not cover go back to the lead
        final Map<ItemStorage, Integer> available = new HashMap<>();
        for (final Iterator<LeaseAllocator.Planned> it = batch.iterator(); it.hasNext(); )
        {
            final LeaseAllocator.Planned planned = it.next();
            final Map<ItemStorage, Integer> wanted = new HashMap<>();
            for (final ItemStack stack : planned.required())
            {
                wanted.merge(new ItemStorage(stack.copyWithCount(1)), stack.getCount(), Integer::sum);
            }
            boolean covered = true;
            for (final Map.Entry<ItemStorage, Integer> entry : wanted.entrySet())
            {
                final int left = available.computeIfAbsent(entry.getKey(), k -> InventoryUtils.getItemCountInItemHandler(worker.getInventoryCitizen(), sameAs(k.getItemStack())));
                if (left < entry.getValue())
                {
                    covered = false;
                    break;
                }
            }
            if (covered)
            {
                wanted.forEach((k, count) -> available.merge(k, -count, Integer::sum));
            }
            else
            {
                collab.releaseLease(planned.world());
                it.remove();
            }
        }
        phase = batch.isEmpty() ? Phase.PLAN : Phase.WORK;
        waitTicks(now, batch.isEmpty() ? IDLE_WAIT : 0);
    }

    private void takeFrom(final AbstractEntityCitizen worker, final com.ldtteam.structurize.api.compat.itemhandler.IItemHandler source, final AbstractBuildingStructureBuilder lead,
      final Map<ItemStorage, Integer> missing, final boolean fromHut, final long now)
    {
        if (source == null)
        {
            return;
        }
        for (final Map.Entry<ItemStorage, Integer> need : missing.entrySet())
        {
            final ItemStack kind = need.getKey().getItemStack();
            int wanted = need.getValue();
            if (fromHut)
            {
                // what the ledger holds in the hut for others (requests that were handed items, other helpers) is not for him
                wanted = Math.min(wanted, Math.max(0, InventoryUtils.getItemCountInItemHandler(source, sameAs(kind)) - AssistReservations.heldByOthers(lead, key, kind)));
            }
            final int taken = wanted <= 0 ? 0 : ItemMover.move(source, worker.getInventoryCitizen(), sameAs(kind), wanted);
            if (taken > 0)
            {
                carry.merge(need.getKey(), taken, Integer::sum);
            }
            if (fromHut)
            {
                AssistReservations.commit(key, lead, kind, taken, now);
            }
        }
    }

    private boolean hutHoldsAnyOf(final AbstractBuildingStructureBuilder lead, final Map<ItemStorage, Integer> missing)
    {
        for (final ItemStorage storage : missing.keySet())
        {
            final ItemStack kind = storage.getItemStack();
            if (InventoryUtils.getItemCountInItemHandler(lead.getItemHandlerCap(), sameAs(kind)) - AssistReservations.heldByOthers(lead, key, kind) > 0)
            {
                return true;
            }
        }
        return false;
    }

    private boolean leadCarriesAll(final AbstractBuildingStructureBuilder lead, final Map<ItemStorage, Integer> missing)
    {
        for (final Map.Entry<ItemStorage, Integer> need : missing.entrySet())
        {
            if (leadCarried(lead, need.getKey().getItemStack()) < need.getValue())
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Gets him to the lead for the hand-over of materials: close enough to hand things over (the lead may stand on a tower: the helper waits at the foot of it).
     *
     * @return true once he is there.
     */
    private boolean walkToLeadForHandOver(final AbstractEntityCitizen worker, final Level world, final net.minecraft.world.entity.LivingEntity lead)
    {
        final BlockPos at = lead.blockPosition();
        final double horizontal = Math.hypot(worker.getX() - lead.getX(), worker.getZ() - lead.getZ());
        if (horizontal <= 4.0D && Math.abs(worker.getY() - lead.getY()) <= 10.0D)
        {
            return true;
        }
        final var ledger = order == null ? null : order.getScaffolds();
        BlockPos ground = at;
        for (int i = 0; i < 40 && ledger != null && (ledger.contains(ground) || ledger.contains(ground.below()) || world.getBlockState(ground.below()).isAir()); i++)
        {
            ground = ground.below();
        }
        return EntityNavigationUtils.walkToPos(worker, ground, 3, false);
    }

    /**
     * Drops the positions of the batch that the lead took back (he had nothing left to do): they are not leased to this helper any more.
     *
     * @return true if something was dropped.
     */
    private boolean pruneBatch(final WorkOrderCollab collab)
    {
        return batch.removeIf(planned -> !collab.isLeasedTo(planned.world(), key));
    }

    // ------------------------------------------------------------------ work

    private void work(final AbstractEntityCitizen worker, final Level world, final AbstractBuildingStructureBuilder lead, final WorkOrderCollab collab, final long now)
    {
        if (batch.isEmpty())
        {
            phase = Phase.PLAN;
            return;
        }
        if (collab.getProgressStage() != batchStage)
        {
            dropBatch(collab);
            phase = Phase.PLAN;
            return;
        }
        if (!worker.getInventoryCitizen().hasSpace())
        {
            giveBackToLead(order);
            if (!worker.getInventoryCitizen().hasSpace())
            {
                IdleCitizenArbiter.stop(colony, citizen.getId(), IdleStopReason.TASK_INVALID);
                return;
            }
        }
        if (pruneBatch(collab) && batch.isEmpty())
        {
            phase = Phase.PLAN;
            return;
        }
        final double cost = leadPlaceTicks(lead);
        int placedHere = 0;
        while (!batch.isEmpty() && placedHere < MAX_PER_STEP)
        {
            final LeaseAllocator.Planned planned = batch.get(0);
            final WorkOrderCollab.Assistant self = collab.getAssistant(key);
            if (self != null)
            {
                self.active = planned.world();
            }
            final BlockState worldState = world.getBlockState(planned.world());
            if (batchStage == BuildingProgressStage.CLEAR ? (worldState.isAir() || !worldState.getFluidState().isEmpty()) : worldState == handler.getBluePrint().getBlockState(planned.local()))
            {
                finishPosition(planned, lead, collab, now, false);
                continue;
            }
            if (bank < cost)
            {
                // he is slower than a builder: waits for the time the block takes
                waitTicks(now, 5);
                return;
            }
            if (!inReach(worker, world, planned.world(), collab))
            {
                return;
            }
            final StructurePhasePlacementResult result = LeaseAllocator.doPosition(placer, world, planned.local(),
              batchStage == BuildingProgressStage.CLEAR ? StructurePlacer.Operation.BLOCK_REMOVAL : StructurePlacer.Operation.BLOCK_PLACEMENT);
            switch (result.getBlockResult().getResult())
            {
                case BREAK_BLOCK:
                    mining = result.getBlockResult().getWorldPos();
                    phase = Phase.MINE;
                    return;
                case MISSING_ITEMS:
                case FAIL:
                case LIMIT_REACHED:
                    // not possible for him right now: the lead gets the position back
                    collab.releaseLease(planned.world());
                    batch.remove(0);
                    continue;
                default:
                    finishPosition(planned, lead, collab, now, true);
                    bank -= cost;
                    if (placedHere == 0)
                    {
                        worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                        worker.queueSound(SoundEvents.BAMBOO_HIT, worker.blockPosition(), 10, 0, 0.5f, 0.1f);
                    }
                    placedHere++;
            }
        }
        if (batch.isEmpty())
        {
            phase = Phase.PLAN;
        }
    }

    private void mine(final AbstractEntityCitizen worker, final Level world, final AbstractBuildingStructureBuilder lead, final WorkOrderCollab collab, final long now)
    {
        if (batch.isEmpty() || mining == null || collab.getProgressStage() != batchStage)
        {
            mining = null;
            phase = batch.isEmpty() ? Phase.PLAN : Phase.WORK;
            return;
        }
        final LeaseAllocator.Planned planned = batch.get(0);
        final BlockState state = world.getBlockState(mining);
        if (state.isAir() || !state.getFluidState().isEmpty() || state.getBlock() == Blocks.BEDROCK
              || state.getBlock() instanceof com.minecolonies.api.entity.ai.workers.util.IBuilderUndestroyable)
        {
            if (!state.getFluidState().isEmpty() && !state.isAir())
            {
                world.removeBlock(mining, false);
            }
            mining = null;
            finishPosition(planned, lead, collab, now, false);
            phase = Phase.WORK;
            return;
        }
        if (!inReach(worker, world, mining, collab))
        {
            return;
        }
        final ItemStack tool = bestToolOfLead(lead, state);
        final double cost = leadBreakTicks(lead, state, mining, tool);
        if (bank < cost)
        {
            waitTicks(now, 5);
            return;
        }
        bank -= cost;
        if (!IColonyManager.getInstance().getCompatibilityManager().isOre(state))
        {
            for (final ItemStack drop : com.minecolonies.api.util.BlockPosUtil.getBlockDrops(world, mining, 0, tool, worker))
            {
                final ItemStack copy = drop.copy();
                final int before = copy.getCount();
                final ItemStack rest = InventoryUtils.transferItemStackIntoNextBestSlotInItemHandlerWithResult(copy, worker.getInventoryCitizen());
                final int kept = before - (rest == null ? 0 : rest.getCount());
                if (kept > 0)
                {
                    carry.merge(new ItemStorage(drop.copyWithCount(1)), kept, Integer::sum);
                }
            }
        }
        com.minecolonies.core.util.WorkerUtil.faceBlock(mining, worker);
        com.minecolonies.core.util.citizenutils.CitizenItemUtils.breakBlockWithToolInHand(worker, mining);
        worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        worker.getCitizenExperienceHandler().addExperience(0.05D);
        mining = null;
        finishPosition(planned, lead, collab, now, true);
        phase = Phase.WORK;
    }

    /**
     * The best tool for a block among what the lead carries (a helper uses the tools of the builder, he has none of his own); empty for none.
     */
    private ItemStack bestToolOfLead(final AbstractBuildingStructureBuilder lead, final BlockState state)
    {
        final ICitizenData leadCitizen = leadCitizen(lead);
        ItemStack best = ItemStack.EMPTY;
        double bestSpeed = 1.0D;
        if (leadCitizen != null)
        {
            for (int slot = 0; slot < leadCitizen.getInventory().getSlots(); slot++)
            {
                final ItemStack stack = leadCitizen.getInventory().getStackInSlot(slot);
                if (stack.isEmpty() || !stack.isCorrectToolForDrops(state))
                {
                    continue;
                }
                final double speed = stack.getItem().getDestroySpeed(stack, state);
                if (speed > bestSpeed)
                {
                    bestSpeed = speed;
                    best = stack;
                }
            }
        }
        return best;
    }

    private void finishPosition(final LeaseAllocator.Planned planned, final AbstractBuildingStructureBuilder lead, final WorkOrderCollab collab, final long now, final boolean counted)
    {
        collab.completeLease(planned.world());
        if (counted)
        {
            collab.blockProcessed();
        }
        final Map<ItemStorage, Integer> consumed = handler.takeConsumed();
        for (final Map.Entry<ItemStorage, Integer> entry : consumed.entrySet())
        {
            carry.computeIfPresent(entry.getKey(), (k, have) -> have - entry.getValue() > 0 ? have - entry.getValue() : null);
            lead.reduceNeededResource(entry.getKey().getItemStack(), entry.getValue());
        }
        batch.remove(planned);
        collab.renew(key, now + BuilderCollab.leaseTicks());
        spot = null;
    }

    // ------------------------------------------------------------------ reach

    /**
     * Gets him where he reaches the block: stays if it is in reach, else walks to the best spot on the ground; no spot (the block is too high, walled in): the position goes
     * back to the lead.
     *
     * @return true if the block is in reach now.
     */
    private boolean inReach(final AbstractEntityCitizen worker, final Level world, final BlockPos target, final WorkOrderCollab collab)
    {
        if (!WorkerSettings.physicalReach())
        {
            if (BlockPosUtil.getDistance2D(worker.blockPosition(), target) <= 5 && !(worker.blockPosition().getX() == target.getX() && worker.blockPosition().getZ() == target.getZ()
                                                                                       && worker.blockPosition().getY() >= target.getY()))
            {
                worker.getNavigation().stop();
                return true;
            }
            EntityNavigationUtils.walkToPos(worker, target, 4, true);
            return false;
        }
        final double reach = WorkerSettings.reach();
        if (BuildReach.inReach(worker.getX(), worker.getEyeY(), worker.getZ(), target, reach))
        {
            if (spot != null)
            {
                worker.getNavigation().stop();
                spot = null;
            }
            return true;
        }
        final long now = world.getGameTime();
        if (spot != null && (now - spotSince > SPOT_BUDGET || !StandSpots.canStandAt(world, spot) || !BuildReach.inReachFromFeet(spot, worker.getEyeHeight(), target, reach - ReachSpots.MARGIN)))
        {
            badSpots.add(spot);
            worker.getNavigation().stop();
            spot = null;
        }
        if (spot == null)
        {
            final double limit = reachLimitY();
            final List<BlockPos> ahead = new ArrayList<>();
            ahead.add(target);
            for (final LeaseAllocator.Planned planned : batch)
            {
                if (!planned.world().equals(target) && ahead.size() < 8)
                {
                    ahead.add(planned.world());
                }
            }
            final List<ReachSpots.Spot> spots = ReachSpots.best(world, ahead, reach, worker.getEyeHeight(), worker.blockPosition(),
              p -> !badSpots.contains(p) && p.getY() <= limit && !world.getBlockState(p.below()).is(Blocks.SCAFFOLDING), 1);
            if (spots.isEmpty())
            {
                giveUpPosition(target, collab);
                return false;
            }
            spot = spots.get(0).feet();
            spotSince = now;
        }
        if (EntityNavigationUtils.walkToBlock(worker, spot) == -1)
        {
            badSpots.add(spot);
            spot = null;
        }
        return false;
    }

    /**
     * He cannot get to the position: the lead gets it back, and he does not take it again.
     */
    private void giveUpPosition(final BlockPos target, final WorkOrderCollab collab)
    {
        gaveUp.add(target.immutable());
        for (final Iterator<LeaseAllocator.Planned> it = batch.iterator(); it.hasNext(); )
        {
            final LeaseAllocator.Planned planned = it.next();
            if (planned.world().equals(target))
            {
                collab.releaseLease(planned.world());
                it.remove();
            }
        }
        if (target.equals(mining))
        {
            mining = null;
        }
        spot = null;
        if (batch.isEmpty())
        {
            phase = Phase.PLAN;
        }
        else if (phase == Phase.MINE)
        {
            phase = Phase.WORK;
        }
    }

    // ------------------------------------------------------------------ leaving

    /**
     * What he carries for the order (and what he got from breaking blocks) goes back into the lead's hut.
     */
    private void giveBackToLead(@Nullable final IBuilderWorkOrder current)
    {
        final AbstractEntityCitizen worker = citizen.getEntity().orElse(null);
        if (worker == null || carry.isEmpty())
        {
            carry.clear();
            return;
        }
        IBuilding hut = null;
        if (current != null && current.isClaimed())
        {
            hut = colony.getServerBuildingManager().getBuilding(current.getClaimedBy());
        }
        if (hut == null || hut.getItemHandlerCap() == null)
        {
            // the lead's hut is gone: what he holds is his own now
            carry.clear();
            return;
        }
        final Map<ItemStorage, Integer> left = new HashMap<>();
        for (final Map.Entry<ItemStorage, Integer> entry : carry.entrySet())
        {
            final ItemStack kind = entry.getKey().getItemStack();
            final int have = InventoryUtils.getItemCountInItemHandler(worker.getInventoryCitizen(), sameAs(kind));
            final int give = Math.min(have, entry.getValue());
            final int moved = give <= 0 ? 0 : ItemMover.move(worker.getInventoryCitizen(), hut.getItemHandlerCap(), sameAs(kind), give);
            if (moved < entry.getValue() && have > moved)
            {
                left.put(entry.getKey(), Math.min(have, entry.getValue()) - moved);
            }
        }
        carry.clear();
        carry.putAll(left);
    }
}
