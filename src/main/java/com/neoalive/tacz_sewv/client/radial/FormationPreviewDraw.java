package com.neoalive.tacz_sewv.client.radial;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleFormation;

/** Top-down white-dot preview of a formation shape for the Quick Wheel Formation submenu. */
final class FormationPreviewDraw {

    private static final int PANEL_W = 110;
    private static final int PANEL_H = 110;
    private static final int PAD = 10;
    private static final int DOT = 3;

    private FormationPreviewDraw() {}

    /**
     * Draws a translucent black panel to the right of the wheel with white dots for each slot.
     * Axis is drawn as "up" on screen (formation forward = screen -Y).
     */
    static void render(GuiGraphics g, Font font, int cx, int cy, int outerR, float menuAlpha,
                       FormationShape shape, int slotCount, double baseline,
                       float widthStretch, float lengthStretch, int rowSize,
                       int panelArgb, int labelArgb) {
        float a = Mth.clamp(menuAlpha, 0.0f, 1.0f);
        if (a <= 0.01f || slotCount <= 0) return;

        int left = cx + outerR + 18;
        int top = cy - PANEL_H / 2;
        int fill = scaleAlpha(panelArgb, a);
        int ink = scaleAlpha(labelArgb, a);

        g.fill(left, top, left + PANEL_W, top + PANEL_H, fill);

        Direction axis = Direction.NORTH; // preview always "faces up"
        Vec3 origin = Vec3.ZERO;
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        Vec3[] slots = new Vec3[slotCount];
        for (int i = 0; i < slotCount; i++) {
            Vec3 p = VehicleFormation.slotCenter(origin, axis, shape, i, rowSize,
                    baseline, widthStretch, lengthStretch);
            slots[i] = p;
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minZ = Math.min(minZ, p.z);
            maxZ = Math.max(maxZ, p.z);
        }
        // Include the commander anchor at (0,0) so the preview shows relative placement.
        minX = Math.min(minX, 0.0);
        maxX = Math.max(maxX, 0.0);
        minZ = Math.min(minZ, 0.0);
        maxZ = Math.max(maxZ, 0.0);

        double spanX = Math.max(maxX - minX, 1.0);
        double spanZ = Math.max(maxZ - minZ, 1.0);
        int drawW = PANEL_W - PAD * 2;
        int drawH = PANEL_H - PAD * 2 - 12;
        double scale = Math.min(drawW / spanX, drawH / spanZ) * 0.85;

        int midX = left + PANEL_W / 2;
        int midY = top + PAD + 6 + drawH / 2;

        // Anchor crosshair (commander).
        int ax = midX + (int) Math.round((0.0 - (minX + maxX) * 0.5) * scale);
        int ay = midY - (int) Math.round((0.0 - (minZ + maxZ) * 0.5) * scale);
        g.fill(ax - 2, ay, ax + 3, ay + 1, ink);
        g.fill(ax, ay - 2, ax + 1, ay + 3, ink);

        for (Vec3 p : slots) {
            int sx = midX + (int) Math.round((p.x - (minX + maxX) * 0.5) * scale);
            // Screen Y grows down; formation +Z (south when facing north) should go down.
            int sy = midY + (int) Math.round((p.z - (minZ + maxZ) * 0.5) * scale);
            g.fill(sx - DOT / 2, sy - DOT / 2, sx + DOT / 2 + 1, sy + DOT / 2 + 1, ink);
        }

        String caption = String.format("W %.1f  L %.1f", widthStretch, lengthStretch);
        g.drawCenteredString(font, caption, left + PANEL_W / 2, top + PANEL_H - 10, ink);
    }

    private static int scaleAlpha(int argb, float a) {
        int alpha = Mth.clamp(Math.round(((argb >>> 24) & 0xFF) * a), 0, 255);
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }
}
