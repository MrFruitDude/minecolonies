package com.minecolonies.core.network.messages.server.colony.building;

import com.ldtteam.common.network.PlayMessageType;
import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.IAssignsJob;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.network.messages.server.AbstractBuildingServerMessage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * Message class which manages the messages hiring or firing of citizens.
 */
public class HireFireMessage extends AbstractBuildingServerMessage<IBuilding>
{
    public static final PlayMessageType<?> TYPE = PlayMessageType.forServer(Constants.MOD_ID, "hire_fire", HireFireMessage::new);

    /**
     * If hiring (true) else firing.
     */
    private final boolean hire;

    /**
     * The citizen to hire/fire.
     */
    private final int citizenID;

    /**
     * The module id
     */
    private final int moduleId;

    /**
     * Creates object for the player to hire or fire a citizen.
     *
     * @param building  view of the building to read data from
     * @param hire      hire or fire the citizens
     * @param citizenID the id of the citizen to fill the job.
     */
    public HireFireMessage(@NotNull final IBuildingView building, final boolean hire, final int citizenID, final int moduleId)
    {
        super(TYPE, building);
        this.hire = hire;
        this.citizenID = citizenID;
        this.moduleId = moduleId;
    }

    /**
     * Transformation from a byteStream to the variables.
     *
     * @param buf the used byteBuffer.
     */
    protected HireFireMessage(final RegistryFriendlyByteBuf buf, final PlayMessageType<?> type)
    {
        super(buf, type);
        hire = buf.readBoolean();
        citizenID = buf.readInt();
        moduleId = buf.readInt();
    }

    /**
     * Transformation to a byteStream.
     *
     * @param buf the used byteBuffer.
     */
    @Override
    protected void toBytes(@NotNull final RegistryFriendlyByteBuf buf)
    {
        super.toBytes(buf);
        buf.writeBoolean(hire);
        buf.writeInt(citizenID);
        buf.writeInt(moduleId);
    }

    /**
     * Whether an add-on refuses the hire ({@link com.minecolonies.api.colony.IColonyRulesProvider#hireRefusal}, e.g. a workforce tier); the player is told
     * why instead of nothing happening.
     *
     * @param player   the player who hires.
     * @param citizen  the citizen.
     * @param building the building.
     * @param module   the module the citizen would be assigned to.
     * @return true when the hire is refused.
     */
    public static boolean refused(final ServerPlayer player, final ICitizenData citizen, final IBuilding building, final IAssignsJob module)
    {
        final Component refusal = IMinecoloniesAPI.getInstance().getColonyRulesProvider().hireRefusal(citizen, building, module.getJobEntry());
        if (refusal == null)
        {
            return false;
        }
        player.sendOverlayMessage(refusal);
        // the window may have updated its view already (a stale synced reason): send the true state back
        building.markDirty();
        citizen.markDirty(0);
        return true;
    }

    @Override
    protected void onExecute(final IPayloadContext ctxIn, final ServerPlayer player, final IColony colony, final IBuilding building)
    {
        execute(player, colony, building, hire, citizenID, moduleId);
    }

    /**
     * What the message does on the server: hires (unless an add-on refuses, see {@link #refused}) or fires a citizen for a module of the building.
     *
     * @param player    the player who sent it.
     * @param colony    the colony.
     * @param building  the building.
     * @param hire      hire, else fire.
     * @param citizenID the citizen.
     * @param moduleId  the module's runtime id.
     */
    public static void execute(final ServerPlayer player, final IColony colony, final IBuilding building, final boolean hire, final int citizenID, final int moduleId)
    {
        if (building.getModule(moduleId) instanceof final IAssignsJob module)
        {
            final ICitizenData citizen = colony.getCitizenManager().getCivilian(citizenID);
            if (hire && citizen != null && refused(player, citizen, building, module))
            {
                return;
            }
            citizen.setPaused(false);
            if (hire)
            {
                module.assignCitizen(citizen);
            }
            else
            {
                module.removeCitizen(citizen);
            }
        }
    }
}
