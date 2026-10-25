package com.neoalive.tacz_sewv.client.xaerominimap;

import java.util.Iterator;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.hud.minimap.element.render.MinimapElementReader;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRenderProvider;
import xaero.hud.minimap.element.render.MinimapElementRenderer;

import com.neoalive.tacz_sewv.client.map.MarkerStyle;
import com.neoalive.tacz_sewv.config.ClientConfig;

/**
 * One adapter from Xaero's Minimap element framework to our painters, parameterised by a
 * {@link Spec}. The minimap's framework is a separate class hierarchy from the World Map's (same
 * shape, different packages), so the World Map's renderers cannot be reused; instead of writing that
 * renderer/reader/provider triple once per marker kind, each kind is a {@code Spec} instance in
 * {@link MinimapRenderers}.
 *
 * <p>Every spec draws at exactly one {@link MinimapElementRenderLocation}. {@code IN_MINIMAP} draws
 * into the minimap's rotation framebuffer, which is clipped to the map shape on composite; Xaero
 * rotates the element's position but not the element, which is what APP-6 wants. {@code OVER_MINIMAP}
 * draws on top, unclipped, with out-of-range elements pinned to the rim — only the edge pins use it.
 */
public final class MinimapMarkerRenderer<E> extends MinimapElementRenderer<E, MinimapMarkerRenderer.Ctx> {

    private final Spec<E> spec;

    MinimapMarkerRenderer(Spec<E> spec) {
        super(new Reader<>(spec), new Provider<>(spec), new Ctx());
        this.spec = spec;
    }

    /** Per-frame state filled in {@link #preRender}: the frame's view of the map. */
    public static final class Ctx {
        ResourceKey<Level> mapDimension;
        Vec3 renderPos = Vec3.ZERO;
        float partialTicks;
        double coordinateScale = 1.0;
        /** The map's rotation, to add to north-up headings. */
        float bearingOffsetDeg;
        MarkerStyle style = MarkerStyle.WORLD_MAP;
    }

    /** What differs between marker kinds; everything else is this adapter. */
    abstract static class Spec<E> {
        private final MinimapElementRenderLocation location;
        private final int order;
        private final int box;

        Spec(MinimapElementRenderLocation location, int order, int box) {
            this.location = location;
            this.order = order;
            this.box = box;
        }

        abstract Iterable<E> elements();

        abstract ResourceKey<Level> dimension(E element);

        abstract double x(E element, Ctx ctx);

        abstract double y(E element, Ctx ctx);

        abstract double z(E element, Ctx ctx);

        /**
         * Draws the element centred on the pose origin; answers whether anything was drawn. An
         * {@code IN_MINIMAP} spec skips {@code outOfBounds} (it would be clipped anyway); the edge
         * pins draw <i>only</i> then.
         */
        abstract boolean paint(GuiGraphics guiGraphics, E element, boolean outOfBounds, Ctx ctx);

        abstract String name(E element);

        boolean enabled() {
            return true;
        }

        /** Once per frame before painting — texture filters and the like. */
        void prepare() {}
    }

    @Override
    public boolean renderElement(E element, boolean highlighted, boolean outOfBounds, double optionalDepth,
                                 float optionalScale, double partialX, double partialY,
                                 MinimapElementRenderInfo info, GuiGraphics guiGraphics, BufferSource buffers) {
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(partialX, partialY, optionalDepth);
        pose.scale(optionalScale, optionalScale, 1.0F);
        boolean drawn = this.spec.paint(guiGraphics, element, outOfBounds, this.context);
        pose.popPose();
        return drawn;
    }

    @Override
    public void preRender(MinimapElementRenderInfo info, BufferSource buffers,
                          MultiTextureRenderTypeRendererProvider provider) {
        Ctx ctx = this.context;
        ctx.mapDimension = info.mapDimension;
        ctx.renderPos = info.renderPos;
        ctx.partialTicks = info.partialTicks;
        ctx.coordinateScale = info.backgroundCoordinateScale;
        ctx.bearingOffsetDeg = XaeroMinimapCompat.bearingOffsetDeg();
        // No text labels at minimap size; the vitals bars keep their own World Map toggles.
        ctx.style = new MarkerStyle(ClientConfig.MINIMAP_ICON_SCALE.get().floatValue(), false, true);
        RenderSystem.enableBlend();
        this.spec.prepare();
    }

    @Override
    public void postRender(MinimapElementRenderInfo info, BufferSource buffers,
                           MultiTextureRenderTypeRendererProvider provider) {
    }

    @Override
    public boolean shouldRender(MinimapElementRenderLocation location) {
        return location == this.spec.location && ClientConfig.minimapMarkersEnabled() && this.spec.enabled();
    }

    @Override
    public int getOrder() {
        return this.spec.order;
    }

    private static final class Provider<E> extends MinimapElementRenderProvider<E, Ctx> {
        private final Spec<E> spec;
        private Iterator<E> iterator;

        Provider(Spec<E> spec) {
            this.spec = spec;
        }

        @Override
        public void begin(MinimapElementRenderLocation location, Ctx context) {
            this.iterator = this.spec.elements().iterator();
        }

        @Override
        public boolean hasNext(MinimapElementRenderLocation location, Ctx context) {
            return this.iterator != null && this.iterator.hasNext();
        }

        @Override
        public E getNext(MinimapElementRenderLocation location, Ctx context) {
            return this.iterator.next();
        }

        @Override
        public void end(MinimapElementRenderLocation location, Ctx context) {
            this.iterator = null;
        }
    }

    private static final class Reader<E> extends MinimapElementReader<E, Ctx> {
        private final Spec<E> spec;

        Reader(Spec<E> spec) {
            this.spec = spec;
        }

        /**
         * Hidden unless the map on screen is the element's own dimension — compared against the map's
         * dimension, not the player's, since Xaero can show another dimension's map. A null element
         * dimension means "any" (the overlay anchor filters per overlay itself).
         */
        @Override
        public boolean isHidden(E element, Ctx context) {
            ResourceKey<Level> dimension = this.spec.dimension(element);
            return dimension != null && context.mapDimension != null && !context.mapDimension.equals(dimension);
        }

        /**
         * Our coordinates are native to the map's own dimension (anything else is hidden), so they
         * scale exactly as the map does — Xaero's default would scale by the PLAYER's dimension and
         * spread an Overworld map's markers eightfold while the player stands in the Nether.
         */
        @Override
        public double getCoordinateScale(E element, Ctx context, MinimapElementRenderInfo info) {
            return info.backgroundCoordinateScale;
        }

        @Override
        public double getRenderX(E element, Ctx context, float partialTicks) {
            return this.spec.x(element, context);
        }

        @Override
        public double getRenderY(E element, Ctx context, float partialTicks) {
            return this.spec.y(element, context);
        }

        @Override
        public double getRenderZ(E element, Ctx context, float partialTicks) {
            return this.spec.z(element, context);
        }

        @Override
        public int getInteractionBoxLeft(E element, Ctx context, float partialTicks) { return -this.spec.box; }

        @Override
        public int getInteractionBoxRight(E element, Ctx context, float partialTicks) { return this.spec.box; }

        @Override
        public int getInteractionBoxTop(E element, Ctx context, float partialTicks) { return -this.spec.box; }

        @Override
        public int getInteractionBoxBottom(E element, Ctx context, float partialTicks) { return this.spec.box; }

        @Override
        public int getRenderBoxLeft(E element, Ctx context, float partialTicks) { return -this.spec.box; }

        @Override
        public int getRenderBoxRight(E element, Ctx context, float partialTicks) { return this.spec.box; }

        @Override
        public int getRenderBoxTop(E element, Ctx context, float partialTicks) { return -this.spec.box; }

        @Override
        public int getRenderBoxBottom(E element, Ctx context, float partialTicks) { return this.spec.box; }

        @Override
        public int getLeftSideLength(E element, Minecraft mc) {
            return 9 + mc.font.width(getMenuName(element));
        }

        @Override
        public String getMenuName(E element) {
            return this.spec.name(element);
        }

        @Override
        public String getFilterName(E element) {
            return getMenuName(element);
        }

        @Override
        public int getMenuTextFillLeftPadding(E element) {
            return 0;
        }

        @Override
        public int getRightClickTitleBackgroundColor(E element) {
            return 0xFF000000;
        }

        /** Nothing on the minimap is interactive; the World Map is where orders are given. */
        @Override
        public boolean isInteractable(MinimapElementRenderLocation location, E element) {
            return false;
        }

        @Override
        public boolean shouldScaleBoxWithOptionalScale() {
            return true;
        }
    }
}
