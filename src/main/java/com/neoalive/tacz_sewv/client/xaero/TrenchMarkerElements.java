package com.neoalive.tacz_sewv.client.xaero;

import java.util.Iterator;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
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

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.MapTrenchMarkers;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.map.TrenchMarker;

/**
 * Trench-network centroids on Xaero's World Map — same element-renderer hook as vehicles.
 */
public final class TrenchMarkerElements {

    private static final int ICON_SIZE = 26;
    private static final int TEXTURE_SIZE = 64;
    private static final int HIT_BOX = 13;
    private static final int RENDER_BOX = 20;

    private static final int TRENCH_COLOR = 0xFFC4A35A;

    private static final ResourceLocation TEXTURE_TRENCH =
            new ResourceLocation(TaczSewv.MODID, "textures/map/xaeros_icon_trench.png");
    private static final ResourceLocation TEXTURE_EMPLACEMENT =
            new ResourceLocation(TaczSewv.MODID, "textures/map/xaeros_icon_trench_emplacement.png");

    public static final Renderer INSTANCE = new Renderer();

    private TrenchMarkerElements() {}

    public static final class Ctx {
        ResourceKey<Level> mapDimension;
    }

    public static final class Renderer extends ElementRenderer<TrenchMarker, Ctx, Renderer> {

        private Renderer() {
            super(new Ctx(), new Provider(), new Reader());
        }

        @Override
        public void preRender(ElementRenderInfo info, BufferSource buffers,
                              MultiTextureRenderTypeRendererProvider provider, boolean shadow) {
            this.context.mapDimension = info.mapDimension;
            RenderSystem.enableBlend();
            Minecraft mc = Minecraft.getInstance();
            mc.getTextureManager().getTexture(TEXTURE_TRENCH).setFilter(true, false);
            mc.getTextureManager().getTexture(TEXTURE_EMPLACEMENT).setFilter(true, false);
        }

        @Override
        public void postRender(ElementRenderInfo info, BufferSource buffers,
                               MultiTextureRenderTypeRendererProvider provider, boolean shadow) {
        }

        @Override
        public void renderElementShadow(TrenchMarker marker, boolean hovered, float optionalScale,
                                        double partialX, double partialY, ElementRenderInfo info,
                                        GuiGraphics guiGraphics, BufferSource buffers,
                                        MultiTextureRenderTypeRendererProvider provider) {
        }

        @Override
        public boolean renderElement(TrenchMarker marker, boolean hovered, double optionalDepth,
                                     float optionalScale, double partialX, double partialY,
                                     ElementRenderInfo info, GuiGraphics guiGraphics, BufferSource buffers,
                                     MultiTextureRenderTypeRendererProvider provider) {
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.translate(partialX, partialY, optionalDepth);
            pose.scale(optionalScale, optionalScale, 1.0F);

            if (hovered) {
                guiGraphics.fill(-HIT_BOX, -HIT_BOX, HIT_BOX, HIT_BOX, 0x60000000);
            }

            if (ClientConfig.MAP_SHOW_ICONS.get()) {
                drawSymbol(guiGraphics, marker);
            }

            pose.popPose();
            return true;
        }

        private void drawSymbol(GuiGraphics guiGraphics, TrenchMarker marker) {
            ResourceLocation texture = marker.hasEmplacement() ? TEXTURE_EMPLACEMENT : TEXTURE_TRENCH;
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            float scale = (float) ICON_SIZE / TEXTURE_SIZE;
            pose.scale(scale, scale, 1.0F);
            int color = TRENCH_COLOR;
            guiGraphics.setColor(
                    (color >> 16 & 0xFF) / 255.0F, (color >> 8 & 0xFF) / 255.0F, (color & 0xFF) / 255.0F, 1.0F);
            guiGraphics.blit(texture, -TEXTURE_SIZE / 2, -TEXTURE_SIZE / 2, 0,
                    0.0F, 0.0F, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);
            guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            pose.popPose();
        }

        @Override
        public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
            return !shadow && ClientConfig.mapMarkersEnabled() && ClientConfig.mapTrenchMarkersEnabled();
        }

        @Override
        public int getOrder() {
            return 290;
        }
    }

    private static final class Provider extends ElementRenderProvider<TrenchMarker, Ctx> {
        private Iterator<TrenchMarker> iterator;

        @Override
        public void begin(ElementRenderLocation location, Ctx context) {
            this.iterator = MapTrenchMarkers.markers().iterator();
        }

        @Override
        public boolean hasNext(ElementRenderLocation location, Ctx context) {
            return this.iterator != null && this.iterator.hasNext();
        }

        @Override
        public TrenchMarker getNext(ElementRenderLocation location, Ctx context) {
            return this.iterator.next();
        }

        @Override
        public void end(ElementRenderLocation location, Ctx context) {
            this.iterator = null;
        }
    }

    private static final class Reader extends ElementReader<TrenchMarker, Ctx, Renderer> {

        @Override
        public boolean isHidden(TrenchMarker marker, Ctx context) {
            return context.mapDimension != null && !context.mapDimension.equals(marker.dimension());
        }

        @Override
        public double getRenderX(TrenchMarker marker, Ctx context, float partialTicks) {
            return marker.x();
        }

        @Override
        public double getRenderY(TrenchMarker marker, Ctx context, float partialTicks) {
            return marker.y();
        }

        @Override
        public double getRenderZ(TrenchMarker marker, Ctx context, float partialTicks) {
            return marker.z();
        }

        @Override
        public boolean hasYCoordinate() {
            return true;
        }

        @Override
        public boolean isInteractable(ElementRenderLocation location, TrenchMarker marker) {
            return true;
        }

        @Override
        public boolean isRightClickValid(TrenchMarker marker) {
            return false;
        }

        @Override
        public int getInteractionBoxLeft(TrenchMarker marker, Ctx context, float partialTicks) {
            return -HIT_BOX;
        }

        @Override
        public int getInteractionBoxRight(TrenchMarker marker, Ctx context, float partialTicks) {
            return HIT_BOX;
        }

        @Override
        public int getInteractionBoxTop(TrenchMarker marker, Ctx context, float partialTicks) {
            return -HIT_BOX;
        }

        @Override
        public int getInteractionBoxBottom(TrenchMarker marker, Ctx context, float partialTicks) {
            return HIT_BOX;
        }

        @Override
        public int getRenderBoxLeft(TrenchMarker marker, Ctx context, float partialTicks) {
            return -RENDER_BOX;
        }

        @Override
        public int getRenderBoxRight(TrenchMarker marker, Ctx context, float partialTicks) {
            return RENDER_BOX;
        }

        @Override
        public int getRenderBoxTop(TrenchMarker marker, Ctx context, float partialTicks) {
            return -RENDER_BOX;
        }

        @Override
        public int getRenderBoxBottom(TrenchMarker marker, Ctx context, float partialTicks) {
            return RENDER_BOX;
        }

        @Override
        public int getLeftSideLength(TrenchMarker marker, Minecraft mc) {
            return 9 + mc.font.width(getMenuName(marker));
        }

        @Override
        public String getMenuName(TrenchMarker marker) {
            if (marker.cellCount() <= 0) return "Emplacement";
            return marker.hasEmplacement() ? "Trench+" : "Trench";
        }

        @Override
        public String getFilterName(TrenchMarker marker) {
            return getMenuName(marker);
        }

        @Override
        public int getMenuTextFillLeftPadding(TrenchMarker marker) {
            return 0;
        }

        @Override
        public int getRightClickTitleBackgroundColor(TrenchMarker marker) {
            return TRENCH_COLOR;
        }

        @Override
        public boolean shouldScaleBoxWithOptionalScale() {
            return true;
        }
    }
}
