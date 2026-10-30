package com.minecolonies.core.client.gui.citizen;

import com.ldtteam.blockui.PaneBuilders;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Image;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.View;
import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.entity.citizen.VisibleCitizenStatus;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.life.LifeTrait;
import com.minecolonies.core.network.messages.server.colony.citizen.AdjustSkillCitizenMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import static com.minecolonies.api.util.constant.WindowConstants.*;

/**
 * BOWindow for the citizen.
 */
//TODO: Rework the layout, skills should be icons or shortform text with amount, left to right. This leaves more space and allows us to add missing important information such as current Job, home/work location etc
public class MainWindowCitizen extends AbstractWindowCitizen
{
    /**
     * Tick function for updating every second.
     */
    private int tick = 0;

    /**
     * Constructor to initiate the citizen windows.
     *
     * @param citizen citizen to bind the window to.
     */
    public MainWindowCitizen(final ICitizenDataView citizen)
    {
        super(citizen, Identifier.fromNamespaceAndPath(Constants.MOD_ID, "gui/citizen/main.xml"));

        final Image statusIcon = findPaneOfTypeByID(STATUS_ICON, Image.class);
        // (round 8, R7-F5: a jobless citizen who waits for labour says why, under his status; with no status he shows the idle one)
        final Component labourWait = citizen.getLabourWait();
        final VisibleCitizenStatus status = citizen.getVisibleStatus() == null && labourWait != null ? VisibleCitizenStatus.HOUSE : citizen.getVisibleStatus();
        if (status == null)
        {
            statusIcon.hide();
        }
        else
        {
            statusIcon.show();
            statusIcon.setImage(status.getIcon(), false);
            final var tooltip = PaneBuilders.tooltipBuilder().append(Component.translatable(status.getTranslationKey()));
            if (labourWait != null)
            {
                tooltip.paragraphBreak().append(labourWait);
            }
            tooltip.hoverPane(statusIcon).build();
        }
    }

    public ICitizenDataView getCitizen()
    {
        return citizen;
    }

    @Override
    public void onUpdate()
    {
        super.onUpdate();

        if (tick++ == 20)
        {
            tick = 0;
            CitizenWindowUtils.createSkillContent(citizen, this);
        }
    }

    /**
     * Called when the gui is opened by an player.
     */
    @Override
    public void onOpened()
    {
        super.onOpened();
        final Text name = findPaneOfTypeByID(WINDOW_ID_NAME, Text.class);
        name.setText(Component.literal(citizen.getName()));
        // round 4 life: the citizen's one trait (saved by the server since round 5), as a short line on the name
        PaneBuilders.tooltipBuilder()
          .append(Component.translatable(LifeTrait.of(citizen).lineKey()))
          .hoverPane(name)
          .build();

        CitizenWindowUtils.createHealthBar(citizen, findPaneOfTypeByID(WINDOW_ID_HEALTHBAR, View.class));
        CitizenWindowUtils.createSaturationBar(citizen, this);
        CitizenWindowUtils.createHappinessBar(citizen, this);
        CitizenWindowUtils.createSkillContent(citizen, this);

        //Tool of class:§rwith minimal level:§rWood or Gold§r and§rwith maximal level:§rWood or Gold§r

        if (citizen.isFemale())
        {
            findPaneOfTypeByID(WINDOW_ID_GENDER, Image.class).setImage(Identifier.parse(FEMALE_SOURCE), false);
        }
    }

    /**
     * Called when a button in the citizen has been clicked.
     *
     * @param button the clicked button.
     */
    @Override
    public void onButtonClicked(@NotNull final Button button)
    {
        super.onButtonClicked(button);
        if (button.getID().contains(PLUS_PREFIX))
        {
            final String label = button.getID().replace(PLUS_PREFIX, "");
            final Skill skill = Skill.valueOf(StringUtils.capitalize(label));

            new AdjustSkillCitizenMessage(colony, citizen, 1, skill).sendToServer();
        }
        else if (button.getID().contains(MINUS_PREFIX))
        {
            final String label = button.getID().replace(MINUS_PREFIX, "");
            final Skill skill = Skill.valueOf(StringUtils.capitalize(label));

            new AdjustSkillCitizenMessage(colony, citizen, -1, skill).sendToServer();
        }
    }
}
