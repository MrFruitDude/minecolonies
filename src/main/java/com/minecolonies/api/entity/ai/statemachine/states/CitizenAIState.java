package com.minecolonies.api.entity.ai.statemachine.states;

/**
 * AI States for citizen's state
 */
public enum CitizenAIState implements IState
{
    IDLE(),
    FLEE(),
    EATING(),
    SICK(),
    SLEEP,
    MOURN,
    WORK,
    WORKING,
    /**
     * An unemployed citizen works for an idle task (helps a builder, ...): the session of the idle citizen arbiter runs his steps.
     */
    LABOUR,
    INACTIVE();

    CitizenAIState()
    {

    }
}
