package com.minecolonies.core.client.gui.containers;

import com.minecolonies.api.colony.ICitizen;
import com.minecolonies.api.inventory.container.ContainerCitizenInventory;
import com.minecolonies.api.util.constant.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * ------------ Class not Documented ------------
 */
public class WindowCitizenInventory extends AbstractContainerScreen<ContainerCitizenInventory>
{
    /**
     * Texture res loc.
     */
    private static final Identifier TEXT = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/citizen_container.png");

    /**
     * Offset inside the texture to use.
     */
    private static final int TEXTURE_OFFSET = 130;

    /**
     * Offset of each slot.
     */
    private static final int SLOT_OFFSET = 18;

    /**
     * Size of the custom texture.
     */
    private static final int TEXTURE_SIZE = 350;

    /**
     * Offet of the screen for the texture.
     */
    private static final int TEXTURE_HEIGHT = 96;

    /**
     * General y offset.
     */
    private static final int Y_OFFSET = 114;

    /**
     * Amount of slots each row.
     */
    private static final int SLOTS_EACH_ROW = 9;

    /**
     * Current active citizen inventory window
     */
    public static WindowCitizenInventory activeCitizenInventory = null;

    /**
     * Citizen of this UI
     */
    private ICitizen citizenData;

    /**
     * window height is calculated with these values; the more rows, the heigher
     */
    private final int inventoryRows;

    public WindowCitizenInventory(final ContainerCitizenInventory container, final Inventory playerInventory, final Component iTextComponent)
    {
        super(container, playerInventory, iTextComponent, 245, Y_OFFSET + Math.min(SLOTS_EACH_ROW, (container.getItems().size() - 36) / 9) * SLOT_OFFSET);
        // A client can construct the menu during the short interval before its
        // colony view arrives.  The server-side container then has no citizen
        // slots; never turn that loading state into a negative screen height.
        this.inventoryRows = Math.max(0, (container.getItems().size() - 36) / 9);
        activeCitizenInventory = this;
        citizenData = container.getCitizenData();
    }

    @Override
    public void extractRenderState(@NotNull final GuiGraphicsExtractor stack, int x, int y, float z)
    {
        super.extractRenderState(stack, x, y, z);
        // tooltip extraction is handled by AbstractContainerScreen;
    }

    /**
     * Draw the foreground layer for the GuiContainer (everything in front of the items)
     */
    @Override
    protected void extractLabels(@NotNull final GuiGraphicsExtractor stack, final int mouseX, final int mouseY)
    {
        stack.text(this.font, this.menu.getDisplayName(), 80, 9, 0xFF404040, false);
        stack.text(this.font, this.playerInventoryTitle.getString(), 8, 25 + this.inventoryRows * SLOT_OFFSET, 0xFF404040, false);
    }

    /**
     * Draws the background layer of this container (behind the items).
     */
    @Override
    public void extractBackground(@NotNull final GuiGraphicsExtractor stack, final int mouseX, final int mouseY, final float partialTicks)
    {
        super.extractBackground(stack, mouseX, mouseY, partialTicks);

        final int i = this.leftPos;
        final int j = this.topPos;


        stack.blit(RenderPipelines.GUI_TEXTURED, TEXT, i, j, 0.0F, 0.0F, this.imageWidth, 10 + this.inventoryRows * SLOT_OFFSET + 12, TEXTURE_SIZE, TEXTURE_SIZE);

        stack.blit(RenderPipelines.GUI_TEXTURED,
          TEXT,
          i,
          j + 10 + this.inventoryRows * SLOT_OFFSET + 12,
          0.0F,
          TEXTURE_OFFSET,
          this.imageWidth,
          TEXTURE_HEIGHT,
          TEXTURE_SIZE,
          TEXTURE_SIZE);

        //stack.blit(TEXT, i, j, 0, 0, this.imageWidth,  this.inventoryRows * SLOT_OFFSET + 12, TEXTURE_SIZE, TEXTURE_SIZE);


        stack.blit(RenderPipelines.GUI_TEXTURED, TEXT, i + 172, j + 22, 0.0F, 227.0F, 49, 72, TEXTURE_SIZE, TEXTURE_SIZE);

        for (int index = 0; index < 4; index++)
        {
            stack.blit(RenderPipelines.GUI_TEXTURED, TEXT, i + 222, j + 22 + index * 18, 0.0F, 300.0F, 18, 18, TEXTURE_SIZE, TEXTURE_SIZE);
        }

        // The 26.2 picture-in-picture API treats x/y as the top-left of the
        // render rectangle and centers the entity inside it.  The old
        // renderer treated (i + 197, j + 88) as the entity's model origin,
        // which left the ported preview below and to the right of the paper
        // panel.  Use the panel bounds so the citizen is clipped and
        // centered in the intended preview area.
        renderEntityInInventoryFollowsMouse(
          stack,
          i + 172,
          j + 22,
          i + 221,
          j + 94,
          30,
          mouseX,
          mouseY,
          this.menu.getEntity());
    }


    public static void renderEntityInInventoryFollowsMouse(
      GuiGraphicsExtractor stack,
      int x0,
      int y0,
      int x1,
      int y1,
      int scale,
      float mouseX,
      float mouseY,
      Optional<? extends Entity> optionalEntity)
    {
        optionalEntity.ifPresent(entity -> {
            if (entity instanceof LivingEntity livingEntity)
            {
                // The 26.2 helper owns both the rectangle-center calculation
                // and the render-state extraction.  Passing precomputed
                // offsets here applies the old 1.21 origin twice and leaves
                // the citizen outside the preview panel.
                InventoryScreen.extractEntityInInventoryFollowsMouse(
                    stack,
                    x0,
                    y0,
                    x1,
                    y1,
                    scale,
                    0.0625F,
                    mouseX,
                    mouseY,
                    livingEntity);
            }
        });
    }

    public static void renderEntityInInventoryFollowsAngle(
      GuiGraphicsExtractor stack,
      int x0,
      int y0,
      int x1,
      int y1,
      int scale,
      float angleXComponent,
      float angleYComponent,
      LivingEntity entity)
    {
        float f = angleXComponent;
        float f1 = angleYComponent;
        InventoryScreen.renderEntityInInventoryFollowsAngle(
          stack, x0, y0, x1, y1, scale, 0.0625F, f, f1, entity);
    }

    public static void renderEntityInInventory(GuiGraphicsExtractor stack, int x, int y, int scale, Quaternionf quaternionf, @Nullable Quaternionf quaternionf1, LivingEntity entity) {
        final EntityRenderer<? super LivingEntity, ?> renderer =
          Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        final EntityRenderState renderState = renderer.createRenderState(entity, 1.0F);
        renderState.shadowPieces.clear();
        renderState.outlineColor = 0;

        stack.entity(
          renderState,
          scale,
          new Vector3f(0.0F, renderState.boundingBoxHeight / 2.0F + 0.0625F, 0.0F),
          quaternionf,
          quaternionf1 != null ? quaternionf1.conjugate() : null,
          x,
          y,
          x + scale,
          y + scale);
    }

    @Override
    public void onClose()
    {
        activeCitizenInventory = null;
        super.onClose();
    }

    /**
     * Get the citizen for this UI
     *
     * @return
     */
    public ICitizen getCitizenData()
    {
        return citizenData;
    }
}
