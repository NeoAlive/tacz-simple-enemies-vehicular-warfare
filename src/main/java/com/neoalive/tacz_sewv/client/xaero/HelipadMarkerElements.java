package com.neoalive.tacz_sewv.client.xaero;

import java.util.Iterator;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
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
import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.map.FactionColors;
import com.neoalive.tacz_sewv.map.HelipadMarker;

/**
 * Cleared-helipad icon on Xaero's World Map. Placeholder art: swap the PNG, nothing else changes.
 */
public final class HelipadMarkerElements {

    private static final int HIT_BOX = 13;
    private static final int RENDER_BOX = 20;
    private static final int ICON = 22;
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(TaczSewv.MODID, "textures/map/xaeros_icon_helipad.png");

    public static final Renderer INSTANCE = new Renderer();

    private HelipadMarkerElements() {}

    public static final class Ctx {
        ResourceKey<Level> mapDimension;
    }

    public static final class Renderer extends ElementRenderer<HelipadMarker, Ctx, Renderer> {

        private Renderer() {
            super(new Ctx(), new Provider(), new Reader());
        }

        @Override
        public void preRender(ElementRenderInfo info, BufferSource buffers,
                              MultiTextureRenderTypeRendererProvider provider, boolean shadow) {
            this.context.mapDimension = info.mapDimension;
            RenderSystem.enableBlend();
            Minecraft.getInstance().getTextureManager().getTexture(TEXTURE).setFilter(true, false);
        }

        @Override
        public void postRender(ElementRenderInfo info, BufferSource buffers,
                               MultiTextureRenderTypeRendererProvider provider, boolean shadow) {
        }

        @Override
        public void renderElementShadow(HelipadMarker marker, boolean hovered, float optionalScale,
                                        double partialX, double partialY, ElementRenderInfo info,
                                        GuiGraphics guiGraphics, BufferSource buffers,
                                        MultiTextureRenderTypeRendererProvider provider) {
        }

        @Override
        public boolean renderElement(HelipadMarker marker, boolean hovered, double optionalDepth,
                                     float optionalScale, double partialX, double partialY,
                                     ElementRenderInfo info, GuiGraphics guiGraphics, BufferSource buffers,
                                     MultiTextureRenderTypeRendererProvider provider) {
            if (!ClientConfig.mapMarkersEnabled()) return false;
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.translate(partialX, partialY, optionalDepth);
            pose.scale(optionalScale, optionalScale, 1.0F);

            int color = FactionColors.configArgb(CrewFacts.Faction.PMC);
            if (hovered) {
                guiGraphics.fill(-HIT_BOX, -HIT_BOX, HIT_BOX, HIT_BOX, 0x60000000);
            }
            if (ClientConfig.MAP_SHOW_ICONS.get()) {
                guiGraphics.setColor(
                        (color >> 16 & 0xFF) / 255.0F,
                        (color >> 8 & 0xFF) / 255.0F,
                        (color & 0xFF) / 255.0F,
                        1.0F);
                guiGraphics.blit(TEXTURE, -ICON / 2, -ICON / 2, 0, 0, ICON, ICON, ICON, ICON);
                guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            }

            if (hovered) {
                Font font = Minecraft.getInstance().font;
                String label = "Helipad";
                guiGraphics.drawString(font, label, -font.width(label) / 2, -HIT_BOX - font.lineHeight, 0xFFFFFFFF, true);
            }

            pose.popPose();
            return true;
        }

        @Override
        public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
            return !shadow && ClientConfig.mapMarkersEnabled();
        }

        @Override
        public int getOrder() {
            return 250;
        }
    }

    private static final class Provider extends ElementRenderProvider<HelipadMarker, Ctx> {
        private Iterator<HelipadMarker> iterator;

        @Override
        public void begin(ElementRenderLocation location, Ctx context) {
            List<HelipadMarker> visible = new java.util.ArrayList<>();
            for (HelipadMarker marker : MapMarkers.helipads()) {
                if (context.mapDimension != null && context.mapDimension.equals(marker.dimension())) {
                    visible.add(marker);
                }
            }
            this.iterator = visible.iterator();
        }

        @Override
        public boolean hasNext(ElementRenderLocation location, Ctx context) {
            return this.iterator != null && this.iterator.hasNext();
        }

        @Override
        public HelipadMarker getNext(ElementRenderLocation location, Ctx context) {
            return this.iterator.next();
        }

        @Override
        public void end(ElementRenderLocation location, Ctx context) {
            this.iterator = null;
        }
    }

    private static final class Reader extends ElementReader<HelipadMarker, Ctx, Renderer> {

        @Override
        public boolean isHidden(HelipadMarker marker, Ctx context) {
            return context.mapDimension != null && !context.mapDimension.equals(marker.dimension());
        }

        @Override
        public double getRenderX(HelipadMarker marker, Ctx context, float partialTicks) {
            return marker.x();
        }

        @Override
        public double getRenderY(HelipadMarker marker, Ctx context, float partialTicks) {
            return marker.y();
        }

        @Override
        public double getRenderZ(HelipadMarker marker, Ctx context, float partialTicks) {
            return marker.z();
        }

        @Override
        public boolean hasYCoordinate() {
            return true;
        }

        @Override
        public boolean isInteractable(ElementRenderLocation location, HelipadMarker marker) {
            return false;
        }

        @Override
        public boolean isRightClickValid(HelipadMarker marker) {
            return false;
        }

        @Override
        public int getInteractionBoxLeft(HelipadMarker marker, Ctx context, float partialTicks) {
            return -HIT_BOX;
        }

        @Override
        public int getInteractionBoxRight(HelipadMarker marker, Ctx context, float partialTicks) {
            return HIT_BOX;
        }

        @Override
        public int getInteractionBoxTop(HelipadMarker marker, Ctx context, float partialTicks) {
            return -HIT_BOX;
        }

        @Override
        public int getInteractionBoxBottom(HelipadMarker marker, Ctx context, float partialTicks) {
            return HIT_BOX;
        }

        @Override
        public int getRenderBoxLeft(HelipadMarker marker, Ctx context, float partialTicks) {
            return -RENDER_BOX;
        }

        @Override
        public int getRenderBoxRight(HelipadMarker marker, Ctx context, float partialTicks) {
            return RENDER_BOX;
        }

        @Override
        public int getRenderBoxTop(HelipadMarker marker, Ctx context, float partialTicks) {
            return -RENDER_BOX;
        }

        @Override
        public int getRenderBoxBottom(HelipadMarker marker, Ctx context, float partialTicks) {
            return RENDER_BOX;
        }

        @Override
        public int getLeftSideLength(HelipadMarker marker, Minecraft mc) {
            return 9 + mc.font.width(getMenuName(marker));
        }

        @Override
        public String getMenuName(HelipadMarker marker) {
            return "Helipad";
        }

        @Override
        public String getFilterName(HelipadMarker marker) {
            return getMenuName(marker);
        }

        @Override
        public int getMenuTextFillLeftPadding(HelipadMarker marker) {
            return 0;
        }

        @Override
        public int getRightClickTitleBackgroundColor(HelipadMarker marker) {
            return FactionColors.configArgb(CrewFacts.Faction.PMC);
        }

        @Override
        public boolean shouldScaleBoxWithOptionalScale() {
            return true;
        }
    }
}
