package com.neoalive.tacz_sewv.client.xaero;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.VehicleMarker;
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

import java.util.Iterator;
import java.util.List;

/**
 * Your own PMC vehicles as icons on Xaero's World Map.
 *
 * <p>Written against Xaero's element framework — a renderer, a reader and a provider — rather than
 * by injecting a draw call into the map screen. The framework is what supplies hover detection
 * (which is what makes the icons clickable at all: the hovered element lands in {@code GuiMap.viewed},
 * where {@code MixinGuiMap} picks it up), depth ordering against waypoints and player trackers, and
 * the map's own zoom transform. Extends {@link ElementRenderer}/{@link ElementReader} directly and
 * not Xaero's {@code MapElementRenderer}/{@code MapElementReader} subclasses, whose only addition is
 * a set of deprecated {@code int location} overloads we would have to implement and never call.
 *
 * <p>The markers themselves are plain coloured shapes drawn with {@link GuiGraphics#fill}: no
 * texture, no atlas, no asset to keep in sync. That is deliberately the placeholder — a NATO Joint
 * Military Symbology set slots in by replacing {@link Renderer#drawSymbol}, switching on
 * {@link VehicleMarker.Kind}, which the server already sends for exactly this purpose.
 */
public final class VehicleMarkerElements {

    /** Icon half-size in map pixels, and the interaction box that has to agree with it. */
    private static final int BODY_HALF_WIDTH = 6;
    private static final int BODY_HALF_LENGTH = 9;
    private static final int NOSE_HALF_WIDTH = 2;
    private static final int NOSE_LENGTH = 5;
    private static final int HIT_BOX = 14;
    private static final int RENDER_BOX = 19;

    private static final int OUTLINE_COLOR = 0xFFFFFFFF;
    private static final int SHADOW_COLOR = 0x80000000;

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

            boolean selected = MapMarkers.isSelected(marker);
            if (selected || hovered) {
                // Unrotated ring, so a selected hull reads the same whichever way it is pointing.
                guiGraphics.fill(-HIT_BOX, -HIT_BOX, HIT_BOX, HIT_BOX,
                        selected ? OUTLINE_COLOR : SHADOW_COLOR);
                guiGraphics.fill(-HIT_BOX + 1, -HIT_BOX + 1, HIT_BOX - 1, HIT_BOX - 1, SHADOW_COLOR);
            }

            // Entity yaw 0 faces south, which is DOWN on the map, and the symbol is drawn nose-up:
            // hence the half turn. Map bearings run clockwise the same way yaw does, so no other
            // correction is needed.
            pose.mulPose(Axis.ZP.rotationDegrees(marker.yaw() + 180.0F));
            drawSymbol(guiGraphics, marker, SewvConfig.parseColor(SewvConfig.COLOR_PMC.get(), 0xFF55FF55));

            pose.popPose();
            return true;
        }

        /** The placeholder symbol: a hull-shaped block with a nose. Replace wholesale for real symbology. */
        private void drawSymbol(GuiGraphics guiGraphics, VehicleMarker marker, int color) {
            guiGraphics.fill(-BODY_HALF_WIDTH, -BODY_HALF_LENGTH, BODY_HALF_WIDTH, BODY_HALF_LENGTH, color);
            guiGraphics.fill(-NOSE_HALF_WIDTH, -BODY_HALF_LENGTH - NOSE_LENGTH, NOSE_HALF_WIDTH, -BODY_HALF_LENGTH, color);
        }

        @Override
        public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
            return !shadow && SewvConfig.MAP_MARKERS_ENABLED.get();
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
            List<VehicleMarker> markers = MapMarkers.markers();
            this.iterator = markers.iterator();
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
            return marker.x();
        }

        @Override
        public double getRenderY(VehicleMarker marker, Ctx context, float partialTicks) {
            return marker.y();
        }

        @Override
        public double getRenderZ(VehicleMarker marker, Ctx context, float partialTicks) {
            return marker.z();
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
         * False on purpose: right-clicking an icon then falls through to the map's own menu, which
         * is where "move the selected units here" lives — the destination is the point of that
         * click, not the icon. Flip this (and override {@code getRightClickOptions}) to give a
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
        public int getRenderBoxBottom(VehicleMarker marker, Ctx context, float partialTicks) { return RENDER_BOX; }

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
            return SewvConfig.parseColor(SewvConfig.COLOR_PMC.get(), 0xFF55FF55);
        }

        @Override
        public boolean shouldScaleBoxWithOptionalScale() {
            return true;
        }
    }
}
