package com.minecolonies.core.colony.buildings;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.jobs.registry.JobEntry;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.colony.workorders.IWorkOrder;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.equipment.ModEquipmentTypes;
import com.minecolonies.api.equipment.registry.EquipmentTypeEntry;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.ldtteam.structurize.api.util.Tuple;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.BuildingResourcesModule;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.utils.BuilderBucket;
import com.minecolonies.core.colony.buildings.utils.BuildingBuilderResource;
import com.minecolonies.core.colony.workorders.collab.AssistReservations;
import com.minecolonies.core.colony.workorders.collab.WorkOrderCollab;
import com.minecolonies.core.util.ItemMover;
import com.minecolonies.core.colony.jobs.AbstractJobStructure;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructureWithWorkOrder;
import com.minecolonies.core.entity.ai.workers.util.BuildingProgressStage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;

import static com.minecolonies.api.util.constant.EquipmentLevelConstants.TOOL_LEVEL_WOOD_OR_GOLD;
import static com.minecolonies.api.util.constant.NbtTagConstants.*;
import static com.minecolonies.core.colony.jobs.AbstractJobStructure.TAG_WORK_ORDER;

/**
 * The structureBuilder building.
 */
public abstract class AbstractBuildingStructureBuilder extends AbstractBuilding
{
    /**
     * The maximum upgrade of the building.
     */
    public static final int MAX_BUILDING_LEVEL = 5;

    /**
     * Progress amount to mark building dirty.
     */
    private static final int COUNT_TO_STORE_POS = 50;

    /**
     * Progress position of the builder.
     */
    private BlockPos progressPos;

    /**
     * Progress stage of the builder.
     */
    private BuildingProgressStage progressStage;

    /**
     * The progress counter of the builder.
     */
    private int progressCounter = 0;

    /**
     * What this builder carries for a structure that another builder leads: the hut of that builder and the materials taken
     * from it, which are given back when the builder stops helping.
     */
    @Nullable
    private BlockPos carryLead;

    private final Map<ItemStorage, Integer> carry = new LinkedHashMap<>();

    /**
     * The project of the last order this hut worked on (builders prefer the next section of the same project).
     */
    private String lastProjectId = "";

    /**
     * What the builder cannot go on without right now (item kind, count), empty if he is not blocked on materials.
     */
    private final Map<ItemStorage, Integer> waitingFor = new LinkedHashMap<>();

    /**
     * The order the legacy progress above was saved for (hut-level progress from before it moved to the order).
     */
    private int legacyProgressOrderId = 0;

    /**
     * The id of the current workOrder.
     */
    private int workOrderId;

    private static final String TAG_LAST_PROJECT      = "lastProject";
    private static final String TAG_COLLAB_CARRY_LEAD = "collabCarryLead";
    private static final String TAG_COLLAB_CARRY      = "collabCarry";
    private static final String TAG_COLLAB_STACK      = "stack";
    private static final String TAG_COLLAB_COUNT      = "count";

    /**
     * Public constructor of the building, creates an object of the building.
     *
     * @param c the colony.
     * @param l the position.
     */
    public AbstractBuildingStructureBuilder(final IColony c, final BlockPos l)
    {
        super(c, l);
    }

    /**
     * Getter of the max building level.
     *
     * @return the integer.
     */
    @Override
    public int getMaxBuildingLevel()
    {
        return MAX_BUILDING_LEVEL;
    }

    @Override
    public int buildingRequiresCertainAmountOfItem(final ItemStack stack, final List<ItemStorage> localAlreadyKept, final boolean inventory, final JobEntry jobEntry)
    {
        if (inventory)
        {
            final int hashCode = stack.getComponentsPatch().hashCode();
            final String key = stack.getItem().getDescriptionId() + "-" + hashCode;
            if (getRequiredResources() != null && getRequiredResources().getResourceMap().containsKey(key))
            {
                final int qtyToKeep = getRequiredResources().getResourceMap().get(key);
                if (localAlreadyKept.contains(new ItemStorage(stack)))
                {
                    for (final ItemStorage storage : localAlreadyKept)
                    {
                        if (storage.equals(new ItemStorage(stack)))
                        {
                            if (storage.getAmount() >= qtyToKeep)
                            {
                                return stack.getCount();
                            }
                            final int kept = storage.getAmount();
                            if (qtyToKeep >= kept + stack.getCount())
                            {
                                storage.setAmount(kept + stack.getCount());
                                return 0;
                            }
                            else
                            {
                                storage.setAmount(qtyToKeep);
                                return qtyToKeep - kept - stack.getCount();
                            }
                        }
                    }
                }
                else
                {
                    if (qtyToKeep >= stack.getCount())
                    {
                        localAlreadyKept.add(new ItemStorage(stack));
                        return 0;
                    }
                    else
                    {
                        localAlreadyKept.add(new ItemStorage(stack, qtyToKeep, false));
                        return stack.getCount() - qtyToKeep;
                    }
                }
            }
            if (checkIfShouldKeepEquipment(ModEquipmentTypes.pickaxe.get(), stack, localAlreadyKept)
                  || checkIfShouldKeepEquipment(ModEquipmentTypes.shovel.get(), stack, localAlreadyKept)
                  || checkIfShouldKeepEquipment(ModEquipmentTypes.axe.get(), stack, localAlreadyKept)
                  || checkIfShouldKeepEquipment(ModEquipmentTypes.hoe.get(), stack, localAlreadyKept))
            {
                localAlreadyKept.add(new ItemStorage(stack, 1, true));
                return 0;
            }
        }
        return super.buildingRequiresCertainAmountOfItem(stack, localAlreadyKept, inventory, jobEntry);
    }

    /**
     * Check if certain equipment should be kept or dumped.
     *
     * @param type             the type of the equipment.
     * @param stack            the stack to check.
     * @param localAlreadyKept the already kept stacks.
     * @return true if should keep.
     */
    private boolean checkIfShouldKeepEquipment(final EquipmentTypeEntry type, final ItemStack stack, final List<ItemStorage> localAlreadyKept)
    {
        if (ItemStackUtils.hasEquipmentLevel(stack, type, TOOL_LEVEL_WOOD_OR_GOLD, getMaxEquipmentLevel()))
        {
            for (final ItemStorage storage : localAlreadyKept)
            {
                if (type.getMiningLevel(stack) <= type.getMiningLevel(storage.getItemStack()))
                {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public Map<Predicate<ItemStack>, com.ldtteam.structurize.api.util.Tuple<Integer, Boolean>> getRequiredItemsAndAmount()
    {
        final Map<Predicate<ItemStack>, com.ldtteam.structurize.api.util.Tuple<Integer, Boolean>> toKeep = new HashMap<>(super.getRequiredItemsAndAmount());

        for (final BuildingBuilderResource stack : getModule(BuildingModules.BUILDING_RESOURCES).getNeededResources().values())
        {
            toKeep.put(itemstack -> ItemStackUtils.compareItemStacksIgnoreStackSize(stack.getItemStack(), itemstack),
              new com.ldtteam.structurize.api.util.Tuple<>(stack.getAmount(), true));
        }

        return toKeep;
    }

    @Override
    public ItemStack forceTransferStack(final ItemStack stack, final Level world)
    {
        final ItemStack itemStack = super.forceTransferStack(stack, world);
        if (ItemStackUtils.isEmpty(itemStack))
        {
            this.markDirty();
        }

        return itemStack;
    }

    @Override
    public void deserializeNBT(@NotNull final HolderLookup.Provider provider, final CompoundTag compound)
    {
        super.deserializeNBT(provider, compound);
        if (compound.contains(TAG_PROGRESS_POS))
        {
            progressPos = BlockPosUtil.read(compound, TAG_PROGRESS_POS);
            progressStage = BuildingProgressStage.values()[compound.getIntOr(TAG_PROGRESS_STAGE, 0)];
        }

        if (compound.contains(TAG_WORK_ORDER))
        {
            this.workOrderId = compound.getIntOr(TAG_WORK_ORDER, 0);
        }
        lastProjectId = compound.getStringOr(TAG_LAST_PROJECT, "");
        carry.clear();
        carryLead = compound.contains(TAG_COLLAB_CARRY_LEAD) ? BlockPosUtil.read(compound, TAG_COLLAB_CARRY_LEAD) : null;
        final ListTag carryList = compound.getListOrEmpty(TAG_COLLAB_CARRY);
        for (int i = 0; i < carryList.size(); i++)
        {
            final CompoundTag entry = carryList.getCompoundOrEmpty(i);
            final ItemStack stack = ItemStackUtils.deserializeFromNBT(entry.getCompoundOrEmpty(TAG_COLLAB_STACK), provider);
            if (!stack.isEmpty())
            {
                carry.merge(new ItemStorage(stack.copyWithCount(1)), entry.getIntOr(TAG_COLLAB_COUNT, 0), Integer::sum);
            }
        }
        // progress saved with the hut belongs to the order the hut had then; it moves to that order when it is read
        legacyProgressOrderId = progressPos == null ? 0 : workOrderId;
    }

    @Override
    public CompoundTag serializeNBT(@NotNull final HolderLookup.Provider provider)
    {
        final CompoundTag compound = super.serializeNBT(provider);
        if (progressPos != null)
        {
            BlockPosUtil.write(compound, TAG_PROGRESS_POS, progressPos);
            compound.putInt(TAG_PROGRESS_STAGE, progressStage.ordinal());
        }

        if (workOrderId != 0)
        {
            compound.putInt(TAG_WORK_ORDER, workOrderId);
        }

        if (!lastProjectId.isEmpty())
        {
            compound.putString(TAG_LAST_PROJECT, lastProjectId);
        }
        if (carryLead != null && !carry.isEmpty())
        {
            BlockPosUtil.write(compound, TAG_COLLAB_CARRY_LEAD, carryLead);
            final ListTag carryList = new ListTag();
            for (final Map.Entry<ItemStorage, Integer> entry : carry.entrySet())
            {
                final CompoundTag tag = new CompoundTag();
                tag.put(TAG_COLLAB_STACK, ItemStackUtils.serializeOptional(entry.getKey().getItemStack().copyWithCount(1), provider));
                tag.putInt(TAG_COLLAB_COUNT, entry.getValue());
                carryList.add(tag);
            }
            compound.put(TAG_COLLAB_CARRY, carryList);
        }

        return compound;
    }

    /**
     * Method to serialize data to send it to the view.
     *
     * @param buf the used ByteBuffer.
     */
    @Override
    public void serializeToView(@NotNull final RegistryFriendlyByteBuf buf, final boolean fullSync)
    {
        super.serializeToView(buf, fullSync);

        final WorkerBuildingModule module = getFirstModuleOccurance(WorkerBuildingModule.class);
        buf.writeUtf(module.getFirstCitizen() != null ? module.getFirstCitizen().getName() : "");
    }

    /**
     * Get the needed resources for the current build.
     *
     * @return a new Hashmap.
     */
    public Map<String, BuildingBuilderResource> getNeededResources()
    {
        return getModule(BuildingModules.BUILDING_RESOURCES).getNeededResources();
    }

    /**
     * Get the needed resources for the current build.
     *
     * @return the bucket.
     */
    @Nullable
    public BuilderBucket getRequiredResources()
    {
        return getModule(BuildingModules.BUILDING_RESOURCES).getRequiredResources();
    }

    /**
     * Check if the resources are in the bucket.
     *
     * @param stack the stack to check.
     * @return true if so.
     */
    public boolean hasResourceInBucket(final ItemStack stack)
    {
        final int hashCode = stack.getComponentsPatch().hashCode();
        final String key = stack.getItem().getDescriptionId() + "-" + hashCode;
        return getRequiredResources() != null && getRequiredResources().getResourceMap().containsKey(key);
    }

    /**
     * Add a new resource to the needed list.
     *
     * @param res    the resource.
     * @param amount the amount.
     */
    public void addNeededResource(@Nullable final ItemStack res, final int amount)
    {
        if (res != null)
        {
            final ItemStack copy = res.copy();
            copy.setCount(1);
            getModule(BuildingModules.BUILDING_RESOURCES).addNeededResource(copy, amount);
            this.markDirty();
        }
    }

    /**
     * Reduce a resource of the needed list.
     *
     * @param res    the resource.
     * @param amount the amount.
     */
    public void reduceNeededResource(final ItemStack res, final int amount)
    {
        getModule(BuildingModules.BUILDING_RESOURCES).reduceNeededResource(res, amount);
        this.markDirty();
    }

    /**
     * Resets the needed resources completely.
     */
    public void resetNeededResources()
    {
        getModule(BuildingModules.BUILDING_RESOURCES).resetNeededResources();
        this.markDirty();
    }

    /**
     * Check if the structureBuilder requires a certain ItemStack for the current construction.
     *
     * @param stack the stack to test.
     * @return true if so.
     */
    public boolean requiresResourceForBuilding(final ItemStack stack)
    {
        return getModule(BuildingModules.BUILDING_RESOURCES).requiresResourceForBuilding(stack);
    }

    /**
     * Set the progress position of the builder.
     *
     * @param blockPos the last blockPos.
     * @param stage    the stage to set.
     */
    public void setProgressPos(final BlockPos blockPos, final BuildingProgressStage stage)
    {
        if (usesOrderProgress())
        {
            final IBuilderWorkOrder order = getWorkOrder();
            if (order != null)
            {
                final boolean stageChanged = order.getCollab().setProgress(blockPos, stage);
                // whatever the hut saved before is out of date now
                this.progressPos = null;
                this.progressStage = null;
                this.legacyProgressOrderId = 0;
                if (this.progressCounter > COUNT_TO_STORE_POS || blockPos == null || stageChanged)
                {
                    getColony().markDirty();
                    this.progressCounter = 0;
                }
                else
                {
                    this.progressCounter++;
                }
            }
            // without an order there is nothing to keep progress for
            return;
        }

        this.progressPos = blockPos;
        if (this.progressCounter > COUNT_TO_STORE_POS || blockPos == null || stage != progressStage)
        {
            this.markDirty();
            this.progressCounter = 0;
        }
        else
        {
            this.progressCounter++;
        }
        this.progressStage = stage;
    }

    /**
     * Getter for the progress position.
     *
     * @return the current progress and stage.
     */
    @Nullable
    public Tuple<BlockPos, BuildingProgressStage> getProgress()
    {
        if (usesOrderProgress())
        {
            final IBuilderWorkOrder order = getWorkOrder();
            if (order == null)
            {
                return null;
            }
            final WorkOrderCollab collab = order.getCollab();
            if (this.progressPos != null)
            {
                // progress from a save made before it moved to the order: take it over if it is this order's
                if (!collab.hasProgress() && legacyProgressOrderId == order.getID())
                {
                    collab.setProgress(this.progressPos, this.progressStage);
                }
                this.progressPos = null;
                this.progressStage = null;
                this.legacyProgressOrderId = 0;
            }
            return collab.hasProgress() ? new Tuple<>(collab.getProgressPos(), collab.getProgressStage()) : null;
        }

        if (this.progressPos == null)
        {
            return null;
        }
        return new Tuple<>(this.progressPos, this.progressStage);
    }

    /**
     * Batch size to request for resources, used by the Miner to get multiple nodes of supplies
     */
    public int getResourceBatchMultiplier()
    {
        return 1;
    }

    /**
     * Check or request if the contents of a specific batch are in the inventory of the building. This ignores the worker inventory (that is remaining stuff from previous rounds,
     * or already belongs to another bucket)
     *
     * @param requiredResources the bucket to check and request.
     * @param worker            the worker.
     */
    public void checkOrRequestBucket(@Nullable final BuilderBucket requiredResources, final ICitizenData worker)
    {
        getFirstModuleOccurance(BuildingResourcesModule.class).checkOrRequestBucket(requiredResources, worker);
    }

    /**
     * Go to the next stage.
     */
    public void nextStage()
    {
        getFirstModuleOccurance(BuildingResourcesModule.class).nextStage();
    }

    /**
     * Set the total number of stages.
     * @param total the total.
     */
    public void setTotalStages(final int total)
    {
        getFirstModuleOccurance(BuildingResourcesModule.class).setTotalStages(total);
    }

    /**
     * Return the next bucket to work on.
     *
     * @return the next bucket or a tuple with null inside if non available.
     */
    @Nullable
    public BuilderBucket getNextBucket()
    {
        return getFirstModuleOccurance(BuildingResourcesModule.class).getNextBucket();
    }

    /**
     * Handle workorder cancellation, reset requests and progress.
     * @param workOrder the cancelled workorder.
     */
    public void onWorkOrderCancellation(final IWorkOrder workOrder)
    {
        if (workOrderId != workOrder.getID())
        {
            return;
        }
        for (final ICitizenData citizen : getAllAssignedCitizen())
        {
            if (citizen.getJob() instanceof AbstractJobStructure<?, ?> abstractJobStructure)
            {
                this.cancelAllRequestsOfCitizenOrBuilding(citizen);
                if (abstractJobStructure.getWorkerAI() instanceof AbstractEntityAIStructureWithWorkOrder<?, ?> abstractEntityAIStructure)
                {
                    abstractEntityAIStructure.resetCurrentStructure();
                }
            }
        }

        setWorkOrder(null);
        resetNeededResources();
        this.setProgressPos(null, null);
        this.cancelAllRequestsOfCitizenOrBuilding(null);
    }

    /**
     * The hut lets go of an order that continues without it (its builder is gone): the hut forgets the order and what it
     * had gathered for it. The order keeps its progress for the next builder.
     *
     * @param workOrder the order.
     */
    public void onWorkOrderReleased(final IWorkOrder workOrder)
    {
        if (workOrderId != workOrder.getID())
        {
            return;
        }
        setWorkOrder(null);
        for (final ICitizenData citizen : getAllAssignedCitizen())
        {
            if (citizen.getJob() instanceof AbstractJobStructure<?, ?> abstractJobStructure)
            {
                this.cancelAllRequestsOfCitizenOrBuilding(citizen);
                if (abstractJobStructure.getWorkerAI() instanceof AbstractEntityAIStructureWithWorkOrder<?, ?> abstractEntityAIStructure)
                {
                    abstractEntityAIStructure.resetCurrentStructure();
                }
            }
        }
        this.cancelAllRequestsOfCitizenOrBuilding(null);
        this.markDirty();
    }

    /**
     * Takes over materials that were delivered to the hut of the builder who led the current order before, as far as that hut does
     * not need them for what it builds now. The builder then does not request them a second time.
     *
     * @param stack   the kind of item (the count is ignored).
     * @param missing how many are missing.
     * @return how many items moved into this hut.
     */
    public int takeOverMaterials(final ItemStack stack, final int missing)
    {
        final IBuilderWorkOrder order = getWorkOrder();
        if (order == null || missing <= 0)
        {
            return 0;
        }
        final BlockPos from = order.getCollab().getPreviousLead();
        if (from == null || from.equals(getID()))
        {
            return 0;
        }
        final IBuilding source = getColony().getServerBuildingManager().getBuilding(from);
        if (!(source instanceof AbstractBuildingStructureBuilder old) || old == this || old.getItemHandlerCap() == null || getItemHandlerCap() == null)
        {
            return 0;
        }

        final Predicate<ItemStack> matches = candidate -> ItemStackUtils.compareItemStacksIgnoreStackSize(candidate, stack, true, true);
        int spare = InventoryUtils.getItemCountInItemHandler(old.getItemHandlerCap(), matches);
        final int hashCode = stack.getComponentsPatch().hashCode();
        final BuildingBuilderResource stillNeeded = old.getNeededResources().get(stack.getItem().getDescriptionId() + "-" + hashCode);
        if (stillNeeded != null)
        {
            spare -= stillNeeded.getAmount();
        }
        // stock the ledger holds in the old hut for other requests (items handed to a requester, items a helper is about to take) stays
        spare -= AssistReservations.held(old, stack);
        if (spare <= 0)
        {
            return 0;
        }
        return ItemMover.move(old.getItemHandlerCap(), getItemHandlerCap(), matches, Math.min(spare, missing));
    }

    /**
     * The project of the last order of this hut.
     *
     * @return the project id, empty if none.
     */
    public String getLastProjectId()
    {
        return lastProjectId;
    }

    /**
     * What the builder is waiting for right now: the materials the block he cannot place needs (kind to count). Empty while he is not
     * blocked on materials. Server side only; clients get it with the work order view.
     *
     * @return a copy.
     */
    public Map<ItemStorage, Integer> getWaitingFor()
    {
        return new LinkedHashMap<>(waitingFor);
    }

    /**
     * Sets what the builder waits for (empty for nothing) and tells clients if it changed.
     *
     * @param items the materials the blocked position needs.
     */
    public void setWaitingFor(final Map<ItemStorage, Integer> items)
    {
        if (waitingFor.equals(items))
        {
            return;
        }
        waitingFor.clear();
        waitingFor.putAll(items);
        final IBuilderWorkOrder order = getWorkOrder();
        if (order != null)
        {
            order.getCollab().touchView();
        }
    }

    // ------------------------------------------------------------------ cargo of a helping builder

    /**
     * Whether this builder carries materials of another builder's structure.
     *
     * @return true if so.
     */
    public boolean hasCarry()
    {
        return !carry.isEmpty();
    }

    /**
     * The hut the carried materials belong to.
     *
     * @return the hut position or null.
     */
    @Nullable
    public BlockPos getCarryLead()
    {
        return carryLead;
    }

    /**
     * What is carried: item (count 1) to count taken from the lead's hut and not placed or given back yet.
     *
     * @return a copy.
     */
    public Map<ItemStorage, Integer> getCarry()
    {
        return new LinkedHashMap<>(carry);
    }

    /**
     * Notes materials taken from the hut of the lead.
     *
     * @param lead   the hut of the lead.
     * @param stack  the kind of item (the count is ignored).
     * @param amount how many were taken.
     */
    public void addCarry(final BlockPos lead, final ItemStack stack, final int amount)
    {
        if (amount <= 0)
        {
            return;
        }
        if (carryLead != null && !carryLead.equals(lead) && !carry.isEmpty())
        {
            throw new IllegalStateException("builder " + getID() + " still carries materials of " + carryLead + " and may not take from " + lead);
        }
        carryLead = lead;
        carry.merge(new ItemStorage(stack.copyWithCount(1)), amount, Integer::sum);
        markDirty();
    }

    /**
     * Notes placed materials, which are no longer carried.
     *
     * @param consumed the materials that were used up.
     */
    public void reduceCarry(final Map<ItemStorage, Integer> consumed)
    {
        for (final Map.Entry<ItemStorage, Integer> entry : consumed.entrySet())
        {
            carry.computeIfPresent(entry.getKey(), (key, have) -> have > entry.getValue() ? have - entry.getValue() : null);
        }
        if (carry.isEmpty())
        {
            carryLead = null;
        }
        markDirty();
    }

    /**
     * Sets the carried count of one item (e.g. what really is in the inventory).
     *
     * @param key   the item.
     * @param count the count, 0 or less removes it.
     */
    public void setCarry(final ItemStorage key, final int count)
    {
        if (count <= 0)
        {
            carry.remove(key);
        }
        else
        {
            carry.put(key, count);
        }
        if (carry.isEmpty())
        {
            carryLead = null;
        }
        markDirty();
    }

    /**
     * Forgets what is carried.
     */
    public void clearCarry()
    {
        carry.clear();
        carryLead = null;
        markDirty();
    }

    /**
     * How many of an item builders that help this hut's order carry for it.
     *
     * @param stack the kind of item.
     * @return the count.
     */
    public int itemsCarriedByHelpers(final ItemStack stack)
    {
        int count = 0;
        final ItemStorage key = new ItemStorage(stack.copyWithCount(1));
        for (final IBuilding other : getColony().getServerBuildingManager().getBuildings().values())
        {
            if (other != this && other instanceof AbstractBuildingStructureBuilder helper && getID().equals(helper.carryLead))
            {
                count += helper.carry.getOrDefault(key, 0);
            }
        }
        return count;
    }

    /**
     * Whether the progress of the structure is kept on the work order (builders) or in the hut.
     *
     * @return true if on the work order.
     */
    protected boolean usesOrderProgress()
    {
        return false;
    }

    /**
     * Get the Work Order ID for this Job.
     *
     * @return UUID of the Work Order claimed by this Job, or null
     */
    private int getWorkOrderId()
    {
        return workOrderId;
    }

    /**
     * Does this job have a Work Order it has claimed?
     *
     * @return true if there is a Work Order claimed by this Job
     */
    public boolean hasWorkOrder()
    {
        if (workOrderId == 0 || getWorkOrder() == null)
        {
            workOrderId = 0;
            return false;
        }
        return true;
    }


    /**
     * Get the Work Order for the Job. Warning: WorkOrder is not cached
     *
     * @return WorkOrderBuildDecoration for the Build
     */
    public IBuilderWorkOrder getWorkOrder()
    {
        final @Nullable IBuilderWorkOrder workOrder = getColony().getWorkManager().getWorkOrder(workOrderId, IBuilderWorkOrder.class);
        if (workOrder == null)
        {
            return null;
        }
        else if (!workOrder.getClaimedBy().equals(getID()))
        {
            workOrderId = 0;
            return null;
        }
        return workOrder;
    }

    /**
     * Set a Work Order for this Job.
     *
     * @param order Work Order to associate with this job, or null
     */
    public void setWorkOrder(@Nullable final IWorkOrder order)
    {
        if (order == null)
        {
            workOrderId = 0;
            resetNeededResources();
            waitingFor.clear();
        }
        else
        {
            if (order instanceof IBuilderWorkOrder builderOrder && !builderOrder.getProjectId().isEmpty())
            {
                lastProjectId = builderOrder.getProjectId();
            }
            if (workOrderId != order.getID())
            {
                // whatever was gathered for another order is not this order's list
                resetNeededResources();
            }
            workOrderId = order.getID();
        }
    }

    /**
     * Do final completion when the Job's current work is complete.
     */
    public void complete(ICitizenData citizen)
    {
        getWorkOrder().onCompleted(colony, citizen);
        setWorkOrder(null);
    }

    /**
     * @deprecated
     * Set workorder ID. Only for backwards compatibility.
     * @param id the work order id.
     */
    public void setWorkOrderId(final int id)
    {
        this.workOrderId = id;
    }
}
