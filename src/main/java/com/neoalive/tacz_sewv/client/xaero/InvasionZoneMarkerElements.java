package com.neoalive.tacz_sewv.client.xaero;

import java.util.Iterator;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

import com.neoalive.tacz_sewv.client.invasion.InvasionHudClient;
import com.neoalive.tacz_sewv.client.map.MarkerStyle;
import com.neoalive.tacz_sewv.client.map.ZoneMarkerPainter;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.invasion.InvasionHud;
import com.neoalive.tacz_sewv.map.InvasionZoneMarker;

/**
 * Team bases and capture points on Xaero's World Map during an active invasion. The pixels are
 * {@link ZoneMarkerPainter}'s, shared with the minimap; this class is the World Map's adapter.
 */
public final class InvasionZoneMarkerElements {

    private static final int HIT_BOX = ZoneMarkerPainter.HIT_BOX;
    private static final int RENDER_BOX = ZoneMarkerPainter.RENDER_BOX;

    public static final Renderer INSTANCE = new Renderer();

    private InvasionZoneMarkerElements() {}

    public static final class Ctx {
        ResourceKey<Level> mapDimension;
    }

    public static final class Renderer extends ElementRenderer<InvasionZoneMarker, Ctx, Renderer> {

        private Renderer() {
            super(new Ctx(), new Provider(), new Reader());
        }

        @Override
        public void preRender(ElementRenderInfo info, BufferSource buffers,
                              MultiTextureRenderTypeRendererProvider provider, boolean shadow) {
            this.context.mapDimension = info.mapDimension;
            RenderSystem.enableBlend();
        }

        @Override
        public void postRender(ElementRenderInfo info, BufferSource buffers,
                               MultiTextureRenderTypeRendererProvider provider, boolean shadow) {
        }

        @Override
        public void renderElementShadow(InvasionZoneMarker marker, boolean hovered, float optionalScale,
                                        double partialX, double partialY, ElementRenderInfo info,
                                        GuiGraphics guiGraphics, BufferSource buffers,
                                        MultiTextureRenderTypeRendererProvider provider) {
        }

        @Override
        public boolean renderElement(InvasionZoneMarker marker, boolean hovered, double optionalDepth,
                                     float optionalScale, double partialX, double partialY,
                                     ElementRenderInfo info, GuiGraphics guiGraphics, BufferSource buffers,
                                     MultiTextureRenderTypeRendererProvider provider) {
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.translate(partialX, partialY, optionalDepth);
            pose.scale(optionalScale, optionalScale, 1.0F);
            ZoneMarkerPainter.paint(guiGraphics, marker, hovered, MarkerStyle.WORLD_MAP);
            pose.popPose();
            return true;
        }

        @Override
        public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
            return !shadow && ClientConfig.mapMarkersEnabled() && InvasionHudClient.isActive();
        }

        @Override
        public int getOrder() {
            return 295;
        }
    }

    private static final class Provider extends ElementRenderProvider<InvasionZoneMarker, Ctx> {
        private Iterator<InvasionZoneMarker> iterator;

        @Override
        public void begin(ElementRenderLocation location, Ctx context) {
            this.iterator = ZoneMarkerPainter.markers().iterator();
        }

        @Override
        public boolean hasNext(ElementRenderLocation location, Ctx context) {
            return this.iterator != null && this.iterator.hasNext();
        }

        @Override
        public InvasionZoneMarker getNext(ElementRenderLocation location, Ctx context) {
            return this.iterator.next();
        }

        @Override
        public void end(ElementRenderLocation location, Ctx context) {
            this.iterator = null;
        }
    }

    private static final class Reader extends ElementReader<InvasionZoneMarker, Ctx, Renderer> {

        @Override
        public boolean isHidden(InvasionZoneMarker marker, Ctx context) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || context.mapDimension == null) return false;
            return !context.mapDimension.equals(mc.level.dimension());
        }

        @Override
        public double getRenderX(InvasionZoneMarker marker, Ctx context, float partialTicks) {
            return marker.x();
        }

        @Override
        public double getRenderY(InvasionZoneMarker marker, Ctx context, float partialTicks) {
            return marker.y();
        }

        @Override
        public double getRenderZ(InvasionZoneMarker marker, Ctx context, float partialTicks) {
            return marker.z();
        }

        @Override
        public boolean hasYCoordinate() {
            return true;
        }

        @Override
        public boolean isInteractable(ElementRenderLocation location, InvasionZoneMarker marker) {
            return true;
        }

        @Override
        public boolean isRightClickValid(InvasionZoneMarker marker) {
            return false;
        }

        @Override
        public int getInteractionBoxLeft(InvasionZoneMarker marker, Ctx context, float partialTicks) {
            return -HIT_BOX;
        }

        @Override
        public int getInteractionBoxRight(InvasionZoneMarker marker, Ctx context, float partialTicks) {
            return HIT_BOX;
        }

        @Override
        public int getInteractionBoxTop(InvasionZoneMarker marker, Ctx context, float partialTicks) {
            return -HIT_BOX;
        }

        @Override
        public int getInteractionBoxBottom(InvasionZoneMarker marker, Ctx context, float partialTicks) {
            return HIT_BOX;
        }

        @Override
        public int getRenderBoxLeft(InvasionZoneMarker marker, Ctx context, float partialTicks) {
            return -RENDER_BOX;
        }

        @Override
        public int getRenderBoxRight(InvasionZoneMarker marker, Ctx context, float partialTicks) {
            return RENDER_BOX;
        }

        @Override
        public int getRenderBoxTop(InvasionZoneMarker marker, Ctx context, float partialTicks) {
            return -RENDER_BOX;
        }

        @Override
        public int getRenderBoxBottom(InvasionZoneMarker marker, Ctx context, float partialTicks) {
            return RENDER_BOX;
        }

        @Override
        public int getLeftSideLength(InvasionZoneMarker marker, Minecraft mc) {
            return 9 + mc.font.width(getMenuName(marker));
        }

        @Override
        public String getMenuName(InvasionZoneMarker marker) {
            if (marker.isBase()) {
                String team = teamName(marker, marker.ownerSide());
                return team.isEmpty() ? "Team Base" : "Base: " + team;
            }
            if (marker.capturing()) {
                return "Capturing " + Math.round(marker.progress() * 100f) + "%";
            }
            String team = teamName(marker, marker.ownerSide());
            return team.isEmpty() ? "Capture Point" : "Held: " + team;
        }

        private static String teamName(InvasionZoneMarker marker, byte side) {
            return switch (side) {
                case InvasionHud.SIDE_A -> marker.teamA() == null ? "" : marker.teamA();
                case InvasionHud.SIDE_B -> marker.teamB() == null ? "" : marker.teamB();
                default -> "";
            };
        }

        @Override
        public String getFilterName(InvasionZoneMarker marker) {
            return marker.isBase() ? "Team Base" : "Capture Point";
        }

        @Override
        public int getMenuTextFillLeftPadding(InvasionZoneMarker marker) {
            return 0;
        }

        @Override
        public int getRightClickTitleBackgroundColor(InvasionZoneMarker marker) {
            return marker.ownerArgb();
        }

        @Override
        public boolean shouldScaleBoxWithOptionalScale() {
            return true;
        }
    }
}
