package com.minecolonies.api.colony.workorders.collab;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A read-only snapshot of how builders share one work order (builder collaboration, config {@code builders.collaboration}): who leads, who
 * helps, which blocks are leased, how far the order is and how many helpers it may have. Taken on the server thread; the snapshot does not
 * change afterwards and holds no references into the colony. Build one with {@code CollabViews.of(...)}.
 */
public interface ICollabView
{
    /**
     * One builder that helps the lead.
     */
    interface IAssistantView
    {
        /**
         * @return the hut of the helper.
         */
        BlockPos hut();

        /**
         * @return the id of the citizen who works there, -1 if the hut has no builder.
         */
        int citizenId();

        /**
         * @return blocks the helper has leased out of the shared cursor and not placed yet.
         */
        int leasedBlocks();

        /**
         * @return the game time at which the helper joined.
         */
        long sinceTick();

        /**
         * @return ticks the helper has to stay before the scheduler may send him elsewhere because the order has enough hands (0 once that is over).
         */
        long stayLeftTicks(long now);

        /**
         * @return true if the player pinned the helper to this order: no new order, cap or stay time moves him ({@link #stayLeftTicks} is irrelevant).
         */
        boolean pinned();
    }

    /**
     * @return the work order id.
     */
    int orderId();

    /**
     * @return the hut of the lead builder, null while nobody leads (order not claimed or its hut does not hold it).
     */
    @Nullable
    BlockPos leadHut();

    /**
     * @return the citizen id of the lead, -1 if there is none.
     */
    int leadCitizenId();

    /**
     * @return the name of the stage the order is in ({@code BuildingProgressStage}), empty if it has not started.
     */
    String stage();

    /**
     * @return true if helpers may work in the current stage (the others - water, leftovers, entities, removal - are left to the lead).
     */
    boolean stageIsShared();

    /**
     * @return blocks done so far, over all builders.
     */
    int placedBlocks();

    /**
     * @return blocks of the order in all as estimated from the blueprint, 0 if not known yet.
     */
    int totalBlocks();

    /**
     * @return positions currently leased by helpers (not the lead's own).
     */
    int leasedBlocks();

    /**
     * @return the helpers the order has now, in the order they joined.
     */
    List<IAssistantView> assistants();

    /**
     * @return the most helpers the order may have right now (0 when too little is left or collaboration is off); pinned helpers are always kept.
     */
    int maxAssistants();

    /**
     * @return the player's limit for this order, -1 for automatic (one helper per so many blocks left, at most the configured maximum).
     */
    int maxAssistantsSetting();

    /**
     * @return the number the scheduler would allow without a player's limit.
     */
    int automaticMaxAssistants();

    /**
     * @return true if collaboration is switched on in the server config.
     */
    boolean collaborationEnabled();

    /**
     * @return the project the order belongs to, empty if none.
     */
    String projectId();
}
