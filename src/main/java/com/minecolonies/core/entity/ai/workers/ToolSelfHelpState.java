package com.minecolonies.core.entity.ai.workers;

import com.minecolonies.api.entity.ai.statemachine.states.IAIState;

/**
 * Round 3 agency: the state of a worker who waited too long for a basic tool and gets one himself (from the colony's storage, or made from its materials).
 */
public enum ToolSelfHelpState implements IAIState
{
    /**
     * Fetches or makes the tool: a labour tool session runs inside the worker's AI. Hunger may interrupt it; he starts again later.
     */
    TOOL_SELF_HELP(true);

    private final boolean okayToEat;

    ToolSelfHelpState(final boolean okayToEat)
    {
        this.okayToEat = okayToEat;
    }

    @Override
    public boolean isOkayToEat()
    {
        return okayToEat;
    }
}
