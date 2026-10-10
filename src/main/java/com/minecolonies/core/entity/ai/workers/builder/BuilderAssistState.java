package com.minecolonies.core.entity.ai.workers.builder;

import com.minecolonies.api.entity.ai.statemachine.states.IAIState;

/**
 * States of a builder who helps another builder with an order.
 */
public enum BuilderAssistState implements IAIState
{
    /**
     * Looks at the order he helps with and leases the next positions.
     */
    ASSIST_PLAN(true),
    /**
     * Takes the materials for his positions out of the hut of the lead.
     */
    ASSIST_GATHER(false),
    /**
     * Places the leased positions one after the other.
     */
    ASSIST_WORK(false),
    /**
     * Mines the position he is clearing.
     */
    ASSIST_MINE(false),
    /**
     * Gives what is left of the lead's materials back to the lead's hut.
     */
    ASSIST_RETURN(true);

    private final boolean okayToEat;

    BuilderAssistState(final boolean okayToEat)
    {
        this.okayToEat = okayToEat;
    }

    @Override
    public boolean isOkayToEat()
    {
        return okayToEat;
    }
}
