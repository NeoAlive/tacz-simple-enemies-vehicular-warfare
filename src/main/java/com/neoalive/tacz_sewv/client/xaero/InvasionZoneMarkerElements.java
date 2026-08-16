package com.neoalive.tacz_sewv.client.xaero;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
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
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.invasion.InvasionHud;
import com.neoalive.tacz_sewv.map.InvasionZoneMarker;

/**
 * Team bases and capture points on Xaero's World Map during an active invasion.
 * Positions and capture state come from {@link InvasionHudClient} (same S→C snapshot as the HUD).
 */
public final class InvasionZoneMarkerElements {

    private static final int HIT_BOX = 14;
    private static final int RENDER_BOX = 22;
    private static final int ICON = 16;
    private static final int BAR_H = 3;
    private static final String HOUSE = "\u2302";

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

            long flashPhase = System.currentTimeMillis() / 250L;
            boolean flashOn = (flashPhase & 1L) == 0L;
            int fill = marker.capturing()
                    ? (flashOn ? 0xFFFFFFFF : marker.conquerArgb())
                    : marker.ownerArgb();

            if (hovered) {
                guiGraphics.fill(-HIT_BOX, -HIT_BOX, HIT_BOX, HIT_BOX, 0x60000000);
            }

            drawIcon(guiGraphics, marker, fill);

            if (marker.capturing()) {
                int barW = ICON + 6;
                int bx = -barW / 2;
                int by = ICON / 2 + 2;
                guiGraphics.fill(bx, by, bx + barW, by + BAR_H, 0x88000000);
                int filled = Math.max(1, Math.round(barW * Math.max(0f, Math.min(1f, marker.progress()))));
                guiGraphics.fill(bx, by, bx + filled, by + BAR_H, marker.conquerArgb());
            }

            if (hovered || ClientConfig.MAP_SHOW_ICONS.get()) {
                Font font = Minecraft.getInstance().font;
                String label = marker.isBase() ? HOUSE : Integer.toString(pointLabel(marker));
                int lw = font.width(label);
                guiGraphics.drawString(font, label, -lw / 2, -HIT_BOX - font.lineHeight, 0xFFFFFFFF, true);
            }

            pose.popPose();
            return true;
        }

        private static int pointLabel(InvasionZoneMarker marker) {
            // 1-based among KIND_POINT only — rebuilt each begin(), index is layout order.
            InvasionHud.Snapshot snap = InvasionHudClient.snapshot();
            if (snap == null) return marker.index() + 1;
            int n = 0;
            for (int i = 0; i <= marker.index() && i < snap.slots().size(); i++) {
                if (snap.slots().get(i).kind() == InvasionHud.KIND_POINT) n++;
            }
            return Math.max(1, n);
        }

        private static void drawIcon(GuiGraphics g, InvasionZoneMarker marker, int argb) {
            int half = ICON / 2;
            if (marker.isBase()) {
                // House-ish: filled square + roof triangle (two fills).
                g.fill(-half, -half + 3, half, half, argb);
                g.fill(-half - 1, -half + 3, half + 1, -half + 5, argb);
                g.fill(-2, -half - 1, 2, -half + 3, argb);
            } else {
                g.fill(-half + 2, -half + 2, half - 2, half - 2, argb);
                g.fill(-half, -2, half, 2, argb);
                g.fill(-2, -half, 2, half, argb);
            }
            // Dark outline
            g.fill(-half - 1, -half - 1, half + 1, -half, 0xFF000000);
            g.fill(-half - 1, half, half + 1, half + 1, 0xFF000000);
            g.fill(-half - 1, -half, -half, half, 0xFF000000);
            g.fill(half, -half, half + 1, half, 0xFF000000);
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
            List<InvasionZoneMarker> list = new ArrayList<>();
            InvasionHud.Snapshot snap = InvasionHudClient.snapshot();
            if (snap != null) {
                int n = snap.slots().size();
                for (int i = 0; i < n; i++) {
                    InvasionHud.Slot slot = snap.slots().get(i);
                    InvasionHud.SlotState state = snap.states().get(i);
                    list.add(new InvasionZoneMarker(
                            i, slot.kind(), slot.pos(),
                            state.ownerSide(), state.conquerSide(), state.progress(), state.capturing(),
                            snap.colorA(), snap.colorB(), snap.colorNeutral(),
                            snap.teamA(), snap.teamB()));
                }
            }
            this.iterator = list.iterator();
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
