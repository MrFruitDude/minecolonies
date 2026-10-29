package com.minecolonies.core.client.gui.townhall;

import com.minecolonies.api.colony.buildings.modules.ICommonSettingsModule;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;

import static com.minecolonies.api.util.constant.WindowConstants.BUTTON_SETTINGS;

/**
 * BOWindow for the town hall.
 */
public class WindowSettings extends AbstractWindowTownHall
{
    /**
     * Module
     */
    private final ICommonSettingsModule moduleView;

    /**
     * Constructor for the town hall window.
     *
     * @param townHall {@link BuildingTownHall.View}.
     */
    public WindowSettings(final BuildingTownHall.View townHall)
    {
        super(townHall, "layoutsettings.xml");

        this.moduleView = buildingView.getColony().getSettings();

        moduleView.getSetting(BuildingTownHall.AUTO_HIRING_MODE)
          .setupHandler(BuildingTownHall.AUTO_HIRING_MODE, window.findPaneByID("job"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.MOVE_IN)
          .setupHandler(BuildingTownHall.MOVE_IN, window.findPaneByID("movein"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.AUTO_HOUSING_MODE)
          .setupHandler(BuildingTownHall.AUTO_HOUSING_MODE, window.findPaneByID("housing"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.ENTER_LEAVE_MESSAGES)
          .setupHandler(BuildingTownHall.ENTER_LEAVE_MESSAGES, window.findPaneByID("entermessages"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.CONSTRUCTION_TAPE)
          .setupHandler(BuildingTownHall.CONSTRUCTION_TAPE, window.findPaneByID("tape"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.IDLE_HELPERS)
          .setupHandler(BuildingTownHall.IDLE_HELPERS, window.findPaneByID("idlehelpers"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.IDLE_HELPERS_PER_BUILDER)
          .setupHandler(BuildingTownHall.IDLE_HELPERS_PER_BUILDER, window.findPaneByID("idlehelperscap"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.LABOUR)
          .setupHandler(BuildingTownHall.LABOUR, window.findPaneByID("labour"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.LABOUR_SHARE)
          .setupHandler(BuildingTownHall.LABOUR_SHARE, window.findPaneByID("labourshare"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.LABOUR_CAP)
          .setupHandler(BuildingTownHall.LABOUR_CAP, window.findPaneByID("labourcap"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.LABOUR_PER_REQUEST)
          .setupHandler(BuildingTownHall.LABOUR_PER_REQUEST, window.findPaneByID("labourperrequest"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.LABOUR_STOCKPILE)
          .setupHandler(BuildingTownHall.LABOUR_STOCKPILE, window.findPaneByID("labourstockpile"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.LABOUR_SELF_SUPPLY)
          .setupHandler(BuildingTownHall.LABOUR_SELF_SUPPLY, window.findPaneByID("labourselfsupply"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.LABOUR_SHAFTS)
          .setupHandler(BuildingTownHall.LABOUR_SHAFTS, window.findPaneByID("labourshafts"), moduleView, buildingView, WindowSettings.this);
    }

    /**
     * Executed when <code>WindowTownHall</code> is opened. Does tasks like setting buttons.
     */
    @Override
    public void onOpened()
    {
        super.onOpened();
    }

    @Override
    public void onUpdate()
    {
        moduleView.getSetting(BuildingTownHall.AUTO_HIRING_MODE)
          .render(BuildingTownHall.AUTO_HIRING_MODE, window.findPaneByID("job"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.MOVE_IN)
          .render(BuildingTownHall.MOVE_IN, window.findPaneByID("movein"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.AUTO_HOUSING_MODE)
          .render(BuildingTownHall.AUTO_HOUSING_MODE, window.findPaneByID("housing"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.ENTER_LEAVE_MESSAGES)
          .render(BuildingTownHall.ENTER_LEAVE_MESSAGES, window.findPaneByID("entermessages"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.CONSTRUCTION_TAPE)
          .render(BuildingTownHall.CONSTRUCTION_TAPE, window.findPaneByID("tape"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.IDLE_HELPERS)
          .render(BuildingTownHall.IDLE_HELPERS, window.findPaneByID("idlehelpers"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.IDLE_HELPERS_PER_BUILDER)
          .render(BuildingTownHall.IDLE_HELPERS_PER_BUILDER, window.findPaneByID("idlehelperscap"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.LABOUR)
          .render(BuildingTownHall.LABOUR, window.findPaneByID("labour"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.LABOUR_SHARE)
          .render(BuildingTownHall.LABOUR_SHARE, window.findPaneByID("labourshare"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.LABOUR_CAP)
          .render(BuildingTownHall.LABOUR_CAP, window.findPaneByID("labourcap"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.LABOUR_PER_REQUEST)
          .render(BuildingTownHall.LABOUR_PER_REQUEST, window.findPaneByID("labourperrequest"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.LABOUR_STOCKPILE)
          .render(BuildingTownHall.LABOUR_STOCKPILE, window.findPaneByID("labourstockpile"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.LABOUR_SELF_SUPPLY)
          .render(BuildingTownHall.LABOUR_SELF_SUPPLY, window.findPaneByID("labourselfsupply"), moduleView, buildingView, WindowSettings.this);
        moduleView.getSetting(BuildingTownHall.LABOUR_SHAFTS)
          .render(BuildingTownHall.LABOUR_SHAFTS, window.findPaneByID("labourshafts"), moduleView, buildingView, WindowSettings.this);
    }

    @Override
    protected String getWindowId()
    {
        return BUTTON_SETTINGS;
    }
}
