package com.minecolonies.core.client.gui.modules.building;

import com.ldtteam.blockui.controls.Text;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.client.gui.AbstractModuleWindow;
import com.minecolonies.core.colony.buildings.moduleviews.ToolModuleView;
import com.minecolonies.core.network.messages.server.colony.building.GiveToolMessage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class ToolModuleWindow extends AbstractModuleWindow<ToolModuleView>
{
    /**
     * ID of the button to give tool
     */
    private static final String BUTTON_GIVE_TOOL = "giveTool";

    /**
     * Constructor for the minimum stock window view.
     *
     * @param moduleView the module view.
     */
    public ToolModuleWindow(final ToolModuleView moduleView)
    {
        super(moduleView,  Identifier.fromNamespaceAndPath(Constants.MOD_ID, "gui/layouthuts/layouttool.xml"));

        final net.minecraft.network.chat.MutableComponent desc =
          Component.translatableEscape("com.minecolonies.coremod.gui.tooldesc." + BuiltInRegistries.ITEM.getKey(moduleView.getTool()).getPath());
        if (buildingView instanceof com.minecolonies.core.colony.buildings.workerbuildings.BuildingLumberjack.View forester)
        {
            // Forestry: trees the Forester leaves standing, by reason
            if (forester.getSkippedTrees() > 0)
            {
                desc.append("\n\n").append(Component.translatableEscape("com.minecolonies.coremod.gui.lumberjack.skippedtrees", forester.getSkippedTrees()));
            }
            if (forester.getSkippedTooBig() > 0)
            {
                desc.append("\n\n").append(Component.translatableEscape("com.minecolonies.coremod.gui.lumberjack.skippedtrees.toobig", forester.getSkippedTooBig()));
            }
            if (forester.getSkippedUnreachable() > 0)
            {
                desc.append("\n\n").append(Component.translatableEscape("com.minecolonies.coremod.gui.lumberjack.skippedtrees.unreachable", forester.getSkippedUnreachable()));
            }
            if (!forester.getLastSkipNote().isEmpty())
            {
                desc.append("\n").append(Component.literal(forester.getLastSkipNote()));
            }
        }
        findPaneOfTypeByID("desc", Text.class).setText(desc);
        registerButton(BUTTON_GIVE_TOOL, this::givePlayerScepter);
    }

    /**
     * Send message to player to add scepter to his inventory.
     */
    private void givePlayerScepter()
    {
        new GiveToolMessage(buildingView, moduleView.getTool()).sendToServer();
    }
}
