package com.minecolonies.core.commands.generalcommands;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.util.MessageUtils;
import com.minecolonies.core.commands.commandTypes.IMCCommand;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

import static com.minecolonies.api.util.constant.translation.CommandTranslationConstants.COMMAND_WHO_AM_I_HAS_COLONY;
import static com.minecolonies.api.util.constant.translation.CommandTranslationConstants.COMMAND_WHO_AM_I_NO_COLONY;

public class CommandWhoAmI implements IMCCommand
{
    /**
     * What happens when the command is executed
     *
     * @param context the context of the command execution
     */
    @Override
    public int onExecute(final CommandContext<CommandSourceStack> context)
    {
        final Entity sender = context.getSource().getEntity();
        if (!(sender instanceof Player))
        {
            return 0;
        }

        // Every colony the player owns, the selected one first.
        final List<IColony> owned = new ArrayList<>(IColonyManager.getInstance().getIColoniesByOwner(sender.getUUID()));
        final IColony selected = IColonyManager.getInstance().getSelectedColony(sender.getUUID());
        if (selected != null && owned.remove(selected))
        {
            owned.add(0, selected);
        }

        if (owned.isEmpty())
        {
            MessageUtils.format(COMMAND_WHO_AM_I_NO_COLONY).sendTo((Player) sender);
            return 0;
        }

        final String playerName = sender.getDisplayName().getString();
        for (final IColony colony : owned)
        {
            final BlockPos pos = colony.getCenter();
            final String posString = "x: " + pos.getX() + " y: " + pos.getY() + " z: " + pos.getZ();
            MessageUtils.format(COMMAND_WHO_AM_I_HAS_COLONY, playerName, colony.getName(), colony.getID(), posString).sendTo((Player) sender);
        }
        return 1;
    }

    /**
     * Name string of the command.
     */
    @Override
    public String getName()
    {
        return "whoami";
    }
}
