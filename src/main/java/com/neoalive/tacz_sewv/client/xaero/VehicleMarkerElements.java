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

import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.client.map.MarkerMotion;
import com.neoalive.tacz_sewv.client.map.MarkerStyle;
import com.neoalive.tacz_sewv.client.map.VehicleMarkerPainter;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.map.VehicleMarker;

/**
 * Crewed vehicles as APP-6 symbols on Xaero's World Map: your own, plus whatever your side has
 * spotted. The pixels are {@link VehicleMarkerPainter}'s, shared with the minimap; this class is the
 * World Map's adapter.
 *
 * <p>Written against Xaero's element framework — a renderer, a reader and a provider — rather than
 * by injecting a draw call into the map screen. The framework is what supplies hover detection
 * (which is what makes the icons clickable at all: the hovered element lands in {@code GuiMap.viewed},
 * where {@code MixinGuiMap} picks it up), depth ordering against waypoints and player trackers, and
 * the map's own zoom transform. Extends {@link ElementRenderer}/{@link ElementReader} directly and
 * not Xaero's {@code MapElementRenderer}/{@code MapElementReader} subclasses, whose only addition is
 * a set of deprecated {@code int location} overloads we would have to implement and never call.
 *
 * <p>Positions and heading come from {@link MarkerMotion}: a unit the client tracks moves smoothly
 * instead of jumping once per sync.
 */
public final class VehicleMarkerElements {

    private static final int HIT_BOX = VehicleMarkerPainter.HIT_BOX;
    private static final int RENDER_BOX = VehicleMarkerPainter.RENDER_BOX;

    public static final Renderer INSTANCE = new Renderer();

    private VehicleMarkerElements() {}

    /**
     * Per-frame state the reader needs but is not handed: which dimension's map is on screen.
     * Filled in {@link Renderer#preRender} from Xaero's own render info, the same way Xaero's
     * tracked-player renderer carries its dimension across.
     */
    public static final class Ctx {
        ResourceKey<Level> mapDimension;
    }

    public static final class Renderer extends ElementRenderer<VehicleMarker, Ctx, Renderer> {

        private Renderer() {
            super(new Ctx(), new Provider(), new Reader());
        }

        @Override
        public void preRender(ElementRenderInfo info, BufferSource buffers,
                              MultiTextureRenderTypeRendererProvider provider, boolean shadow) {
            this.context.mapDimension = info.mapDimension;
            RenderSystem.enableBlend();
            VehicleMarkerPainter.prepareTextures();
        }

        @Override
        public void postRender(ElementRenderInfo info, BufferSource buffers,
                               MultiTextureRenderTypeRendererProvider provider, boolean shadow) {
        }

        @Override
        public void renderElementShadow(VehicleMarker marker, boolean hovered, float optionalScale,
                                        double partialX, double partialY, ElementRenderInfo info,
                                        GuiGraphics guiGraphics, BufferSource buffers,
                                        MultiTextureRenderTypeRendererProvider provider) {
            // Nothing: shouldRender refuses the shadow pass entirely.
        }

        @Override
        public boolean renderElement(VehicleMarker marker, boolean hovered, double optionalDepth,
                                     float optionalScale, double partialX, double partialY,
                                     ElementRenderInfo info, GuiGraphics guiGraphics, BufferSource buffers,
                                     MultiTextureRenderTypeRendererProvider provider) {
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.translate(partialX, partialY, optionalDepth);
            pose.scale(optionalScale, optionalScale, 1.0F);
            VehicleMarkerPainter.paint(guiGraphics, marker, MarkerMotion.yaw(marker, info.partialTicks),
                    hovered, MarkerStyle.WORLD_MAP, 0.0F);
            pose.popPose();
            return true;
        }

        @Override
        public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
            return !shadow && ClientConfig.mapMarkersEnabled();
        }

        /** Above Xaero's tracked players (200) — your own units are what you opened the map for. */
        @Override
        public int getOrder() {
            return 300;
        }
    }

    private static final class Provider extends ElementRenderProvider<VehicleMarker, Ctx> {
        private Iterator<VehicleMarker> iterator;

        @Override
        public void begin(ElementRenderLocation location, Ctx context) {
            this.iterator = MapMarkers.markers().iterator();
        }

        @Override
        public boolean hasNext(ElementRenderLocation location, Ctx context) {
            return this.iterator != null && this.iterator.hasNext();
        }

        @Override
        public VehicleMarker getNext(ElementRenderLocation location, Ctx context) {
            return this.iterator.next();
        }

        @Override
        public void end(ElementRenderLocation location, Ctx context) {
            this.iterator = null;
        }
    }

    private static final class Reader extends ElementReader<VehicleMarker, Ctx, Renderer> {

        /**
         * Hidden unless the map on screen is the marker's own dimension. Compared against the map's
         * dimension rather than the player's: looking at the Nether map from the Overworld must not
         * scatter Overworld hulls across it at raw coordinates.
         */
        @Override
        public boolean isHidden(VehicleMarker marker, Ctx context) {
            return context.mapDimension != null && !context.mapDimension.equals(marker.dimension());
        }

        @Override
        public double getRenderX(VehicleMarker marker, Ctx context, float partialTicks) {
            return MarkerMotion.x(marker, partialTicks);
        }

        @Override
        public double getRenderY(VehicleMarker marker, Ctx context, float partialTicks) {
            return MarkerMotion.y(marker, partialTicks);
        }

        @Override
        public double getRenderZ(VehicleMarker marker, Ctx context, float partialTicks) {
            return MarkerMotion.z(marker, partialTicks);
        }

        @Override
        public boolean hasYCoordinate() {
            return true;
        }

        @Override
        public boolean isInteractable(ElementRenderLocation location, VehicleMarker marker) {
            return true;
        }

        /**
         * False on purpose: right-clicking a symbol then falls through to the map's own menu, which
         * is where "move the selected units here" lives — the destination is the point of that
         * click, not the symbol. Flip this (and override {@code getRightClickOptions}) to give a
         * single hull its own menu.
         */
        @Override
        public boolean isRightClickValid(VehicleMarker marker) {
            return false;
        }

        @Override
        public int getInteractionBoxLeft(VehicleMarker marker, Ctx context, float partialTicks) { return -HIT_BOX; }

        @Override
        public int getInteractionBoxRight(VehicleMarker marker, Ctx context, float partialTicks) { return HIT_BOX; }

        @Override
        public int getInteractionBoxTop(VehicleMarker marker, Ctx context, float partialTicks) { return -HIT_BOX; }

        @Override
        public int getInteractionBoxBottom(VehicleMarker marker, Ctx context, float partialTicks) { return HIT_BOX; }

        @Override
        public int getRenderBoxLeft(VehicleMarker marker, Ctx context, float partialTicks) { return -RENDER_BOX; }

        @Override
        public int getRenderBoxRight(VehicleMarker marker, Ctx context, float partialTicks) { return RENDER_BOX; }

        @Override
        public int getRenderBoxTop(VehicleMarker marker, Ctx context, float partialTicks) { return -RENDER_BOX; }

        @Override
        public int getRenderBoxBottom(VehicleMarker marker, Ctx context, float partialTicks) {
            // Extra room for PMC ♥/⚡ bars under the symbol.
            return RENDER_BOX + 14;
        }

        @Override
        public int getLeftSideLength(VehicleMarker marker, Minecraft mc) {
            return 9 + mc.font.width(getMenuName(marker));
        }

        @Override
        public String getMenuName(VehicleMarker marker) {
            return marker.kind().name();
        }

        @Override
        public String getFilterName(VehicleMarker marker) {
            return getMenuName(marker);
        }

        @Override
        public int getMenuTextFillLeftPadding(VehicleMarker marker) {
            return 0;
        }

        @Override
        public int getRightClickTitleBackgroundColor(VehicleMarker marker) {
            return VehicleMarkerPainter.markerColor(marker);
        }

        @Override
        public boolean shouldScaleBoxWithOptionalScale() {
            return true;
        }
    }
}
