package com.neoalive.tacz_sewv.client.xaero;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.VehicleMarker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Crewed vehicles as APP-6 symbols on Xaero's World Map: your own, plus whatever your side has
 * spotted.
 *
 * <p>Written against Xaero's element framework — a renderer, a reader and a provider — rather than
 * by injecting a draw call into the map screen. The framework is what supplies hover detection
 * (which is what makes the icons clickable at all: the hovered element lands in {@code GuiMap.viewed},
 * where {@code MixinGuiMap} picks it up), depth ordering against waypoints and player trackers, and
 * the map's own zoom transform. Extends {@link ElementRenderer}/{@link ElementReader} directly and
 * not Xaero's {@code MapElementRenderer}/{@code MapElementReader} subclasses, whose only addition is
 * a set of deprecated {@code int location} overloads we would have to implement and never call.
 *
 * <p><b>The symbols are black-on-white art tinted by a plain colour multiply.</b> Each texture is
 * black strokes over a white fill on transparency, so {@code setColor} leaves the strokes black
 * (anything × 0 is 0) and turns the fill into the allegiance colour — no second texture per
 * faction, no shader, and a new symbol is a PNG rather than code. The textures are 256×256 drawn at
 * ~26px, so they are switched to <b>linear filtering</b>; at nearest-neighbour an 8:1 downscale
 * eats the dashed frames that carry half the meaning.
 *
 * <p>Symbols are deliberately drawn <b>upright</b>. APP-6 never rotates a symbol — heading is a
 * separate direction-of-movement line, which is what {@link Renderer#drawHeading} is.
 */
public final class VehicleMarkerElements {

    /** Drawn size of the symbol in map pixels, and the boxes that have to agree with it. */
    private static final int ICON_SIZE = 26;
    /**
     * Source art size. Kept close to the drawn size on purpose: GPU bilinear takes four texels, so
     * far past ~2:1 it steps straight over thin strokes and the dashed frames sparkle. The symbols
     * were authored at 256 and are stored area-averaged down to this, which is a filter that sees
     * every texel — quality the sampler cannot recover at draw time whatever the filter mode.
     */
    private static final int TEXTURE_SIZE = 64;
    private static final int HIT_BOX = 13;
    private static final int RENDER_BOX = 20;

    /** Direction-of-movement line: from the symbol's edge out to this radius, this thick. */
    private static final int HEADING_START = 12;
    private static final int HEADING_END = 19;
    private static final int HEADING_HALF_WIDTH = 1;

    private static final int SELECTION_COLOR = 0xFFFFFFFF;
    private static final int SELECTION_SHADE = 0x60000000;

    private static final Map<VehicleMarker.Kind, ResourceLocation> TEXTURES = new EnumMap<>(VehicleMarker.Kind.class);

    static {
        for (VehicleMarker.Kind kind : VehicleMarker.Kind.values()) {
            TEXTURES.put(kind, new ResourceLocation(TaczSewv.MODID, "textures/map/xaeros_icon_" + kind.textureName() + ".png"));
        }
    }

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
            // Re-asserted every pass rather than latched: a resource reload rebuilds the texture
            // objects with their metadata's default (nearest), and five binds a frame while a map
            // screen is open is not worth a listener to avoid.
            Minecraft mc = Minecraft.getInstance();
            TEXTURES.values().forEach(texture -> mc.getTextureManager().getTexture(texture).setFilter(true, false));
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

            boolean selectable = marker.allegiance() == VehicleMarker.Allegiance.OWN;
            if (hovered || (selectable && MapMarkers.isSelected(marker))) {
                guiGraphics.fill(-HIT_BOX, -HIT_BOX, HIT_BOX, HIT_BOX,
                        MapMarkers.isSelected(marker) ? SELECTION_COLOR : SELECTION_SHADE);
                guiGraphics.fill(-HIT_BOX + 1, -HIT_BOX + 1, HIT_BOX - 1, HIT_BOX - 1, SELECTION_SHADE);
            }

            int color = colorOf(marker.allegiance());
            drawHeading(guiGraphics, marker, color);
            drawSymbol(guiGraphics, marker, color);

            pose.popPose();
            return true;
        }

        /** The APP-6 symbol, tinted: black strokes survive the multiply, the white fill becomes {@code color}. */
        private void drawSymbol(GuiGraphics guiGraphics, VehicleMarker marker, int color) {
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            float scale = (float) ICON_SIZE / TEXTURE_SIZE;
            pose.scale(scale, scale, 1.0F);
            guiGraphics.setColor(
                    (color >> 16 & 0xFF) / 255.0F, (color >> 8 & 0xFF) / 255.0F, (color & 0xFF) / 255.0F, 1.0F);
            // The short blit overload hardcodes a 256x256 sheet, so the texture size goes in explicitly.
            guiGraphics.blit(TEXTURES.get(marker.kind()), -TEXTURE_SIZE / 2, -TEXTURE_SIZE / 2, 0,
                    0.0F, 0.0F, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);
            guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            pose.popPose();
        }

        /**
         * APP-6's direction-of-movement line: a stick out of the symbol along the hull's heading.
         * Yaw 0 faces south, which is down on the map, so the line is drawn pointing up and given a
         * half turn — map bearings run clockwise the same way yaw does, so nothing else is needed.
         */
        private void drawHeading(GuiGraphics guiGraphics, VehicleMarker marker, int color) {
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.mulPose(Axis.ZP.rotationDegrees(marker.yaw() + 180.0F));
            guiGraphics.fill(-HEADING_HALF_WIDTH, -HEADING_END, HEADING_HALF_WIDTH, -HEADING_START,
                    0xFF000000 | color);
            pose.popPose();
        }

        private static int colorOf(VehicleMarker.Allegiance allegiance) {
            return switch (allegiance) {
                case OWN -> SewvConfig.parseColor(SewvConfig.COLOR_PMC.get(), 0xFF55FF55);
                case FRIENDLY -> SewvConfig.parseColor(SewvConfig.MAP_COLOR_FRIENDLY.get(), 0xFF80D0FF);
                case HOSTILE -> SewvConfig.parseColor(SewvConfig.MAP_COLOR_HOSTILE.get(), 0xFFFF8080);
            };
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
            return Renderer.colorOf(marker.allegiance());
        }

        @Override
        public boolean shouldScaleBoxWithOptionalScale() {
            return true;
        }
    }
}
