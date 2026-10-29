package com.neoalive.tacz_sewv.client.radial;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.entity.ai.support.FormationComposition;
import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleFormation;

/** Top-down white-dot preview + subject ribbon + kind filter for the Quick Wheel Formation submenu. */
final class FormationPreviewDraw {

    static final int PANEL_W = 110;
    static final int PANEL_H = 110;
    /** Same width as the preview; one quarter its height. */
    static final int FILTER_H = PANEL_H / 4;
    private static final int PAD = 10;
    private static final int DOT = 3;
    /** Space between the detached subject ribbon and the kind strip under it. */
    static final int SUBJECT_GAP = 4;
    /** Past wedge labels that stick out past the ring. */
    static final int RIGHT_OF_RING_GAP = 58;

    private static final String[] MODE_LABELS = {"INFY.", "GRND.", "SHIP."};
    private static final String[] SUBJECT_LABELS = {"PLYR.", "UNIT."};

    private FormationPreviewDraw() {}

    /**
     * Draws the subject ribbon, kind-filter strip, slot preview, and hint text to the right of the wheel.
     * {@code filterLerp} / {@code subjectLerp} are continuous highlight positions; {@code filterKind} /
     * {@code subjectIndex} the discrete selections used for labels. Under the UNIT subject (index 1) the
     * anchor is drawn as the leader, and {@code slotCount} already excludes it.
     */
    static void render(GuiGraphics g, Font font, int cx, int cy, int outerR, float menuAlpha,
                       FormationShape shape, int slotCount, double baseline,
                       float widthStretch, float lengthStretch, int rowSize,
                       FormationComposition.Kind filterKind, float filterLerp,
                       int subjectIndex, float subjectLerp,
                       int panelArgb, int labelArgb) {
        float a = Mth.clamp(menuAlpha, 0.0f, 1.0f);
        if (a <= 0.01f) return;

        int left = cx + outerR + RIGHT_OF_RING_GAP;
        int subjectTop = cy - (FILTER_H + SUBJECT_GAP + FILTER_H + PANEL_H) / 2;
        int filterTop = subjectTop + FILTER_H + SUBJECT_GAP;
        int panelTop = filterTop + FILTER_H;
        int fill = scaleAlpha(panelArgb, a);
        int ink = scaleAlpha(labelArgb, a);
        int muted = scaleAlpha(0xFF8B98A5, a);
        int hiFill = scaleAlpha(0x66A06BD4, a);

        // --- Subject ribbon (detached) + kind filter strip ---
        drawStrip(g, font, left, subjectTop, SUBJECT_LABELS, subjectIndex, subjectLerp, fill, hiFill, ink, muted);
        drawStrip(g, font, left, filterTop, MODE_LABELS, filterKind.ordinal(), filterLerp, fill, hiFill, ink, muted);
        boolean led = subjectIndex == 1;

        // --- Slot preview ---
        g.fill(left, panelTop, left + PANEL_W, panelTop + PANEL_H, fill);

        if (slotCount > 0 && shape != null) {
            Direction axis = Direction.NORTH;
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
            minX = Math.min(minX, 0.0);
            maxX = Math.max(maxX, 0.0);
            minZ = Math.min(minZ, 0.0);
            maxZ = Math.max(maxZ, 0.0);

            double spanX = Math.max(maxX - minX, 1.0);
            double spanZ = Math.max(maxZ - minZ, 1.0);
            int drawW = PANEL_W - PAD * 2;
            int drawH = PANEL_H - PAD * 2 - 12;
            double scale = Math.min(drawW / spanX, drawH / spanZ) * 0.85;

            double midWorldX = (minX + maxX) * 0.5;
            double midWorldZ = (minZ + maxZ) * 0.5;
            int midX = left + PANEL_W / 2;
            int midY = panelTop + PAD + 6 + drawH / 2;

            int ax = midX + (int) Math.round((0.0 - midWorldX) * scale);
            int ay = midY + (int) Math.round((0.0 - midWorldZ) * scale);
            if (led) {
                // The elected leader stands on the anchor: a filled diamond, not the player's cross.
                for (int r = 0; r <= 3; r++) {
                    g.fill(ax - (3 - r), ay - r, ax + (3 - r) + 1, ay - r + 1, ink);
                    g.fill(ax - (3 - r), ay + r, ax + (3 - r) + 1, ay + r + 1, ink);
                }
            } else {
                g.fill(ax - 2, ay, ax + 3, ay + 1, ink);
                g.fill(ax, ay - 2, ax + 1, ay + 3, ink);
            }

            for (Vec3 p : slots) {
                int sx = midX + (int) Math.round((p.x - midWorldX) * scale);
                int sy = midY + (int) Math.round((p.z - midWorldZ) * scale);
                g.fill(sx - DOT / 2, sy - DOT / 2, sx + DOT / 2 + 1, sy + DOT / 2 + 1, ink);
            }
        }

        String caption = String.format("W %.1f  L %.1f", widthStretch, lengthStretch);
        g.drawCenteredString(font, caption, left + PANEL_W / 2, panelTop + PANEL_H - 10, ink);

        // --- Hint (no background) ---
        String hint = I18n.get("gui.tacz_sewv.formation.hint");
        int hintY = panelTop + PANEL_H + 4;
        for (String line : hint.split("\n")) {
            g.drawCenteredString(font, line, left + PANEL_W / 2, hintY, muted);
            hintY += font.lineHeight + 1;
        }
    }

    /** One labelled selector strip, the highlight sliding between cells at {@code lerp}. */
    private static void drawStrip(GuiGraphics g, Font font, int left, int top, String[] labels, int selected,
                                  float lerp, int fill, int hiFill, int ink, int muted) {
        g.fill(left, top, left + PANEL_W, top + FILTER_H, fill);
        int cellW = PANEL_W / labels.length;
        int hiX = left + Math.round(Mth.clamp(lerp, 0.0f, labels.length - 1.0f) * cellW);
        g.fill(hiX, top + 1, hiX + cellW, top + FILTER_H - 1, hiFill);
        for (int i = 0; i < labels.length; i++) {
            g.drawCenteredString(font, labels[i], left + i * cellW + cellW / 2, top + (FILTER_H - 8) / 2,
                    i == selected ? ink : muted);
        }
    }

    static int scaleAlpha(int argb, float a) {
        int alpha = Mth.clamp(Math.round(((argb >>> 24) & 0xFF) * a), 0, 255);
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }
}
