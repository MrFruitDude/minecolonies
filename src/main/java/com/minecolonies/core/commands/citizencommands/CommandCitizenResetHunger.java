package com.minecolonies.core.commands.citizencommands;
import net.minecraft.server.permissions.Permissions;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.core.colony.CitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.constant.translation.CommandTranslationConstants;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.commands.arguments.ColonyIdArgument;
import com.minecolonies.core.commands.commandTypes.IMCColonyOfficerCommand;
import com.minecolonies.core.commands.commandTypes.IMCCommand;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.minecolonies.api.colony.ICitizenData.MAX_SATURATION;
import static com.minecolonies.core.commands.CommandArgumentNames.CITIZENID_ARG;
import static com.minecolonies.core.commands.CommandArgumentNames.COLONYID_ARG;

/**
 * Fills the saturation of one citizen, or raises every citizen of a colony to a random value between 75% and full (never lower than he is; so their meals do not all come at once).
 */
public class CommandCitizenResetHunger implements IMCColonyOfficerCommand
{
    @Override
    public int onExecute(@NotNull final CommandContext<CommandSourceStack> context)
    {
        return 0;
    }

    @NotNull
    @Override
    public String getName()
    {
        return "resetHunger";
    }

    @NotNull
    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build()
    {
        return IMCCommand.newLiteral(getName())
                 .then(IMCCommand.newArgument(COLONYID_ARG, ColonyIdArgument.id())
                         .executes(ctx -> reset(ctx, null))
                         .then(IMCCommand.newArgument(CITIZENID_ARG, IntegerArgumentType.integer(1))
                                 .executes(ctx -> reset(ctx, IntegerArgumentType.getInteger(ctx, CITIZENID_ARG)))));
    }

    private int reset(@NotNull final CommandContext<CommandSourceStack> context, @Nullable final Integer citizenId)
    {
        try
        {
            if (!checkPreCondition(context))
            {
                return 0;
            }

            if (!context.getSource().permissions().hasPermission(Permissions.COMMANDS_OWNER) && !MineColonies.getConfig().getServer().canPlayerUseModifyCitizensCommand.get())
            {
                context.getSource().sendSuccess(() -> Component.translatable(CommandTranslationConstants.COMMAND_DISABLED_IN_CONFIG), true);
                return 0;
            }

            final IColony colony = ColonyIdArgument.getColony(context, COLONYID_ARG);
            final List<ICitizenData> citizens;
            if (citizenId == null)
            {
                citizens = colony.getCitizenManager().getCitizens();
            }
            else
            {
                final ICitizenData citizenData = colony.getCitizenManager().getCivilian(citizenId);
                if (citizenData == null)
                {
                    context.getSource().sendSuccess(() -> Component.translatable(CommandTranslationConstants.COMMAND_CITIZEN_NOT_FOUND), true);
                    return 0;
                }
                citizens = List.of(citizenData);
            }

            // one citizen is filled up; a whole colony gets random values between 75% and full, so its meals spread out again (nobody gets less than he has)
            final java.util.Random random = new java.util.Random();
            for (final ICitizenData citizen : citizens)
            {
                citizen.setSaturation(citizenId == null
                                        ? Math.max(citizen.getSaturation(), CitizenData.randomSaturation(random, CitizenData.RESET_SATURATION_MIN_SHARE))
                                        : MAX_SATURATION);
                citizen.markDirty(0);
            }

            final int count = citizens.size();
            context.getSource().sendSuccess(() -> Component.translatable(CommandTranslationConstants.COMMAND_CITIZEN_RESET_HUNGER_SUCCESS, count, colony.getName()), true);
            return count;
        }
        catch (Throwable e)
        {
            Log.getLogger().warn("Error during running command:", e);
            return 0;
        }
    }
}
