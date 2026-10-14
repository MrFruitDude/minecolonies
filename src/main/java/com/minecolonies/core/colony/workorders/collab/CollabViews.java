package com.minecolonies.core.colony.workorders.collab;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.colony.workorders.collab.ICollabBuilderView;
import com.minecolonies.api.colony.workorders.collab.ICollabView;
import com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.core.entity.ai.workers.util.BuilderStageRules;
import com.minecolonies.core.entity.ai.workers.util.BuildingProgressStage;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Read side of the builder collaboration: snapshots of who leads and who helps, for UIs and statistics. Everything here is read on the
 * server thread and returns copies. The write side is {@link CollabControls}.
 */
public final class CollabViews
{
    private CollabViews()
    {
    }

    private record AssistantView(BlockPos hut, int citizenId, int leasedBlocks, long sinceTick, long stayTicks, boolean pinned, boolean idleCitizen) implements ICollabView.IAssistantView
    {
        @Override
        public long stayLeftTicks(final long now)
        {
            return Math.max(0, sinceTick + stayTicks - now);
        }
    }

    private record OrderView(int orderId, @Nullable BlockPos leadHut, int leadCitizenId, String stage, boolean stageIsShared, int placedBlocks, int totalBlocks, int leasedBlocks,
                             List<ICollabView.IAssistantView> assistants, int maxAssistants, int maxAssistantsSetting, int automaticMaxAssistants, boolean collaborationEnabled,
                             String projectId, int maxIdleAssistants) implements ICollabView
    {
    }

    private record BuilderView(BlockPos hut, int citizenId, Role role, int orderId, boolean pinned, @Nullable BlockPos leadHut) implements ICollabBuilderView
    {
    }

    /**
     * The collaboration on one order.
     */
    public static ICollabView of(final IColony colony, final IBuilderWorkOrder order)
    {
        final WorkOrderCollab collab = order.getCollab();
        final long stay = BuilderCollab.stayTicks();
        final AbstractBuildingStructureBuilder lead = CollabScheduler.leadOf(colony, order);
        final List<ICollabView.IAssistantView> assistants = new ArrayList<>();
        for (final WorkOrderCollab.Assistant assistant : collab.getAssistants())
        {
            final boolean idle = IdleHelperKey.is(assistant.hut);
            assistants.add(new AssistantView(assistant.hut.immutable(), idle ? IdleHelperKey.citizenId(assistant.hut) : citizenOf(colony.getServerBuildingManager().getBuilding(assistant.hut)),
              collab.getLeaseCountOf(assistant.hut), assistant.since, stay, assistant.isPinned(), idle));
        }
        final BuildingProgressStage stage = collab.getProgressStage();
        return new OrderView(order.getID(), lead == null ? null : lead.getID().immutable(), lead == null ? -1 : citizenOf(lead), stage == null ? "" : stage.name(),
          stage != null && BuilderStageRules.isSharedStage(stage), collab.getPlacedBlocks(), collab.getTotalBlocks(), collab.getLeaseCount() - leadLeases(collab, lead),
          List.copyOf(assistants), CollabScheduler.capFor(order), collab.getMaxAssistantsSetting(), CollabScheduler.automaticCapFor(order), BuilderCollab.enabled(), order.getProjectId(),
          CollabScheduler.idleCapFor(colony, order));
    }

    /**
     * The collaboration on every builder order of the colony, by order id.
     */
    public static List<ICollabView> orders(final IColony colony)
    {
        final List<ICollabView> out = new ArrayList<>();
        for (final IServerWorkOrder order : colony.getWorkManager().getWorkOrders().values())
        {
            if (order instanceof IBuilderWorkOrder builderOrder)
            {
                out.add(of(colony, builderOrder));
            }
        }
        out.sort(Comparator.comparingInt(ICollabView::orderId));
        return out;
    }

    /**
     * What every builder hut of the colony is doing: leading, helping, idle, or without a builder. In hut position order.
     */
    public static List<ICollabBuilderView> builders(final IColony colony)
    {
        final List<ICollabBuilderView> out = new ArrayList<>();
        final List<BuildingBuilder> huts = new ArrayList<>();
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            if (building instanceof BuildingBuilder hut)
            {
                huts.add(hut);
            }
        }
        huts.sort(Comparator.comparingLong(h -> h.getID().asLong()));
        for (final BuildingBuilder hut : huts)
        {
            final int citizen = citizenOf(hut);
            final BlockPos pos = hut.getID().immutable();
            if (citizen < 0)
            {
                out.add(new BuilderView(pos, -1, ICollabBuilderView.Role.VACANT, -1, false, null));
            }
            else if (hut.hasWorkOrder())
            {
                out.add(new BuilderView(pos, citizen, ICollabBuilderView.Role.LEAD, hut.getWorkOrder().getID(), false, null));
            }
            else
            {
                final IBuilderWorkOrder helped = colony.getWorkManager().getAssistedOrder(hut.getID());
                if (helped != null)
                {
                    final WorkOrderCollab.Assistant assistant = helped.getCollab().getAssistant(hut.getID());
                    out.add(new BuilderView(pos, citizen, ICollabBuilderView.Role.ASSIST, helped.getID(), assistant != null && assistant.isPinned(),
                      helped.isClaimed() ? helped.getClaimedBy().immutable() : null));
                }
                else
                {
                    out.add(new BuilderView(pos, citizen, ICollabBuilderView.Role.IDLE, -1, false, null));
                }
            }
        }
        return out;
    }

    private static int leadLeases(final WorkOrderCollab collab, @Nullable final AbstractBuildingStructureBuilder lead)
    {
        return lead == null ? 0 : collab.getLeaseCountOf(lead.getID());
    }

    private static int citizenOf(@Nullable final IBuilding building)
    {
        if (building == null)
        {
            return -1;
        }
        final WorkerBuildingModule module = building.getFirstModuleOccurance(WorkerBuildingModule.class);
        final ICitizenData citizen = module == null ? null : module.getFirstCitizen();
        return citizen == null ? -1 : citizen.getId();
    }
}
