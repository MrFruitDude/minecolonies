package com.minecolonies.core.client.render.worldevent;

import com.ldtteam.structurize.client.rendertask.util.BufferSourceCompat;
import com.ldtteam.structurize.client.rendertask.util.WorldRenderMacros;
import com.ldtteam.structurize.client.BlueprintHandler;
import com.ldtteam.structurize.storage.rendering.types.BlueprintPreviewData;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.core.client.render.TileEntityColonySignRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class WorldEventContext
{
    public static final WorldEventContext INSTANCE = new WorldEventContext();
    public static final float DEFAULT_LINE_WIDTH = 0.025F;
    public static final net.minecraft.client.renderer.rendertype.RenderType LINES_WITH_WIDTH =
        WorldRenderMacros.LINES_WITH_WIDTH;

    private WorldEventContext()
    {
    }

    public SubmitCustomGeometryEvent submitEvent;
    public BufferSourceCompat bufferSource;
    public PoseStack poseStack;
    public ClientLevel clientLevel;
    public LocalPlayer clientPlayer;
    public ItemStack mainHandItem;
    public int clientRenderDist;

    @Nullable
    public IColonyView nearestColony;

    /** Once per game tick for the per-frame nearest-colony lookup in {@link #submit}. */
    private final PerTickGate nearbyColonyGate = new PerTickGate();

    boolean hasNearestColony()
    {
        return nearestColony != null;
    }

    /**
     * Submits colony blueprints and every colony overlay (borders, waypoints, boxes, debug text) while
     * Minecraft collects the current frame's feature nodes. Since 26.x anything submitted from a
     * RenderLevelStageEvent is too late for the frame and is never drawn.
     */
    public void submit(final SubmitCustomGeometryEvent event)
    {
        final Minecraft mc = Minecraft.getInstance();
        clientLevel = mc.level;
        clientPlayer = mc.player;
        if (clientPlayer == null || clientLevel == null)
        {
            return;
        }

        submitEvent = event;
        poseStack = event.getPoseStack();
        mainHandItem = clientPlayer.getMainHandItem();
        clientRenderDist = mc.options.renderDistance().get();
        // The nearest colony changes no faster than the ticks that move the player: look it up once per tick, not per frame
        // (outside claimed chunks the lookup scans every colony view).
        if (nearbyColonyGate.due(clientLevel, clientLevel.getGameTime()))
        {
            checkNearbyColony(clientLevel);
        }

        try (final Gizmos.TemporaryCollection ignored = mc.levelRenderer.collectPerFrameRenderThreadGizmos())
        {
            ColonyBlueprintRenderer.renderBlueprints(this);

            bufferSource = WorldRenderMacros.getBufferSource();
            final Vec3 cameraPosition = mc.gameRenderer.mainCamera().position();
            poseStack.pushPose();
            poseStack.translate(-cameraPosition.x(), -cameraPosition.y(), -cameraPosition.z());
            renderOverlays();
            poseStack.popPose();
            bufferSource.endBatch(event.getSubmitNodeCollector());
        }
        finally
        {
            bufferSource = null;
            submitEvent = null;
        }
    }

    private void renderOverlays()
    {
        ColonyBorderRenderer.render(this);
        ColonyWaypointRenderer.render(this);
        ColonyPatrolPointRenderer.render(this);
        GuardTowerRallyBannerRenderer.render(this);
        PathfindingDebugRenderer.render(this);
        ColonyBlueprintRenderer.renderBoxes(this);
        ItemOverlayBoxesRenderer.render(this);
        HighlightManager.render(this);
        TileEntityColonySignRenderer.renderSignHover(this);
    }

    public void checkNearbyColony(final Level level)
    {
        if (clientPlayer != null)
        {
            nearestColony = IColonyManager.getInstance().getClosestColonyView(level, clientPlayer.blockPosition());
        }
    }

    public void renderLineBoxWithShadow(final BlockPos pos, final int argbColor, final float lineWidth)
    {
        WorldRenderMacros.renderLineBox(
            poseStack,
            bufferSource,
            new AABB(pos),
            lineWidth,
            withHalfAlpha(argbColor),
            true);
        WorldRenderMacros.renderLineBox(
            poseStack,
            bufferSource,
            new AABB(pos),
            lineWidth,
            argbColor,
            false);
    }

    public void renderLineBox(final BlockPos position, final int argbColor, final float lineWidth)
    {
        WorldRenderMacros.renderLineBox(poseStack, bufferSource, new AABB(position), lineWidth, argbColor, false);
    }

    public void renderLineAABBWithShadow(final AABB aabb, final int argbColor, final float lineWidth)
    {
        WorldRenderMacros.renderLineBox(
            poseStack,
            bufferSource,
            aabb,
            lineWidth,
            withHalfAlpha(argbColor),
            true);
        WorldRenderMacros.renderLineBox(
            poseStack,
            bufferSource,
            aabb,
            lineWidth,
            argbColor,
            false);
    }

    public void renderLineAABB(final AABB aabb, final int argbColor, final float lineWidth)
    {
        WorldRenderMacros.renderLineBox(poseStack, bufferSource, aabb, lineWidth, argbColor, false);
    }

    public void pushPoseCameraToPos(final BlockPos position)
    {
        poseStack.pushPose();
        poseStack.translate(position.getX(), position.getY(), position.getZ());
    }

    public void popPose()
    {
        poseStack.popPose();
    }

    public void renderBlueprint(final BlueprintPreviewData data, final List<BlockPos> positions)
    {
        if (data == null || data.getBlueprint() == null || submitEvent == null)
        {
            return;
        }

        for (final BlockPos position : positions)
        {
            BlueprintHandler.getInstance().internalBackportDraw(data, position, submitEvent);
        }
    }

    public void renderLineBox(final Object ignoredRenderType,
        final BlockPos first,
        final BlockPos second,
        final int argbColor,
        final float lineWidth)
    {
        WorldRenderMacros.renderLineBox(
            poseStack,
            bufferSource,
            new AABB(first),
            lineWidth,
            argbColor,
            false);
    }

    public void renderDebugText(final BlockPos position, final List<String> text, final boolean forceWhite, final int mergeEvery)
    {
        WorldRenderMacros.renderDebugText(position, text, poseStack, forceWhite, mergeEvery, bufferSource);
    }

    private static int withHalfAlpha(final int argbColor)
    {
        return (ARGB.alpha(argbColor) / 2) << 24 | (argbColor & 0x00ffffff);
    }
}
