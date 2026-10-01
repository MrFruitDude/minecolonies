package com.minecolonies.core.placementhandlers.main;

import com.minecolonies.api.colony.IColonyManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * Client-only part of {@link SurvivalHandler}.
 *
 * <p>Since 26.x {@code @OnlyIn} no longer strips members at runtime, so a client-typed
 * argument handed to a {@code Level} parameter inside the shared handler makes the bytecode
 * verifier load {@link ClientLevel} when the handler is constructed, which crashes a
 * dedicated server. Keeping that call in this class means it is only loaded on the client.</p>
 */
final class SurvivalHandlerClient
{
    private SurvivalHandlerClient()
    {
    }

    /**
     * @return true if there is a colony view near the given position.
     */
    static boolean hasColonyViewNear(final ClientLevel clientLevel, final BlockPos blockPos)
    {
        return IColonyManager.getInstance().getClosestColonyView(clientLevel, blockPos) != null;
    }
}
