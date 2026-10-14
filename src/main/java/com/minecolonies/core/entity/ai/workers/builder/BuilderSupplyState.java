package com.minecolonies.core.entity.ai.workers.builder;

import com.minecolonies.api.entity.ai.statemachine.states.IAIState;

/**
 * States of a builder who fetches basic materials for his own build while he is blocked on them (LABOURERS.md 6).
 */
public enum BuilderSupplyState implements IAIState
{
    /**
     * Makes a claim for one of the blocked requests of his own hut.
     */
    SUPPLY_PLAN(true),
    /**
     * Gathers, crafts and brings the goods: the labourer's session runs inside the builder's AI.
     */
    SUPPLY_RUN(false);

    private final boolean okayToEat;

    BuilderSupplyState(final boolean okayToEat)
    {
        this.okayToEat = okayToEat;
    }

    @Override
    public boolean isOkayToEat()
    {
        return okayToEat;
    }
}
