package com.minecolonies.core.client.gui.containers;

/**
 * Teaching container screens that expose their GUI origin.
 * MC 26.3 removed AbstractContainerScreen#getGuiLeft/getGuiTop; the JEI ghost-ingredient handler needs them.
 */
public interface ITeachingContainerScreen
{
    /**
     * @return the x position of the GUI's left edge.
     */
    int getGuiLeft();

    /**
     * @return the y position of the GUI's top edge.
     */
    int getGuiTop();
}
