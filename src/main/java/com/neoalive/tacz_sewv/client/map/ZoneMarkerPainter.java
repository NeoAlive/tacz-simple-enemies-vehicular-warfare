package com.neoalive.tacz_sewv.client.map;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import com.neoalive.tacz_sewv.client.invasion.InvasionHudClient;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.invasion.InvasionHud;
import com.neoalive.tacz_sewv.map.InvasionZoneMarker;

/**
 * Team bases and capture points during an active invasion, for either map. Positions and capture
 * state come from {@link InvasionHudClient} (same S→C snapshot as the HUD).
 */
public final class ZoneMarkerPainter {

    public static final int HIT_BOX = 14;
    public static final int RENDER_BOX = 22;
    private static final int ICON = 16;
    private static final int BAR_H = 3;
    private static final String HOUSE = "⌂";

    /**
     * Markers built from the snapshot they came from. The minimap draws every frame, so the list is
     * rebuilt only when a new snapshot lands rather than on every element pass.
     */
    private static InvasionHud.Snapshot cachedFrom;
    private static List<InvasionZoneMarker> cached = List.of();

    private ZoneMarkerPainter() {}

    public static List<InvasionZoneMarker> markers() {
        InvasionHud.Snapshot snap = InvasionHudClient.snapshot();
        if (snap == cachedFrom) return cached;
        List<InvasionZoneMarker> list = new ArrayList<>();
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
        cachedFrom = snap;
        cached = List.copyOf(list);
        return cached;
    }

    public static void paint(GuiGraphics guiGraphics, InvasionZoneMarker marker, boolean hovered, MarkerStyle style) {
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.scale(style.scale(), style.scale(), 1.0F);

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

        // The point number is what tells two points apart, so it stays even without other labels.
        if (hovered || ClientConfig.MAP_SHOW_ICONS.get()) {
            Font font = Minecraft.getInstance().font;
            String label = marker.isBase() ? HOUSE : Integer.toString(pointLabel(marker));
            int lw = font.width(label);
            guiGraphics.drawString(font, label, -lw / 2, -HIT_BOX - font.lineHeight, 0xFFFFFFFF, true);
        }

        pose.popPose();
    }

    private static int pointLabel(InvasionZoneMarker marker) {
        // 1-based among KIND_POINT only — index is layout order.
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
}
