package com.neoalive.tacz_sewv.client.radial;

import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

final class RadialWheelDraw {

    /** The shared translucent plate: every wedge, the hub, and every panel around the wheel. */
    static final int PLATE = 0xB80F1317;

    /** Unicode icon scale relative to the default font size. */
    private static final float ICON_SCALE = 1.85f;

    /**
     * Per-glyph correction on {@link #ICON_SCALE}. A glyph's advance width is not its ink size, so
     * some read tiny and some huge at one scale; this is keyed on the glyph, not the entry, because
     * one glyph serves several entries (the airplane is on five).
     */
    private static final Map<String, Float> GLYPH_SCALE = Map.of(
            "☎", 1.3f,
            "◆", 1.35f,
            "◈", 1.2f,
            "□", 1.15f,
            "✈", 0.85f,
            "⚑", 0.85f,
            "⛰", 0.85f);

    /** How far the hot wedge's outer edge pushes out, as a fraction of the wheel radius. */
    private static final double HOT_GROW = 0.05;

    /** Clearance between a wedge's outer edge and the nearest edge of its label. */
    private static final int LABEL_GAP_MIN = 4;
    private static final double LABEL_GAP_FRAC = 0.08;

    /** How much category colour bleeds into the neutral plate; raise the hot mix, not the alpha, for more lift. */
    private static final float COLD_ACCENT_MIX = 0.08f;
    private static final float HOT_ACCENT_MIX = 0.18f;
    private static final int COLD_FILL_ALPHA = 0xB8;
    private static final int HOT_FILL_ALPHA = 0xE0;
    private static final int STRIP_COLD_ALPHA = 0x80;
    private static final int HOT_PLATE_RGB = 0x1C232B;
    private static final int DISABLED_ACCENT = 0x6A7380;
    private static final int DISABLED_LABEL = 0xFF7A828C;

    /** Screen-space extent of the labels, for UI placed clear of them. */
    record LabelBounds(int top, int bottom, int right) {}

    private record Box(int x, int y, int w, int h) {}

    private RadialWheelDraw() {}

    /**
     * @param outerR current wheel outer radius (screen already chose size)
     * @param innerR current wheel inner radius
     * @param hotStrengths 0..1 per wedge (fade toward selection); length must match wedges
     * @param menuAlpha 0..1 whole-ring opacity (open / submenu fade)
     * @param checked per wedge, a toggle that is currently on (Select leaves); null for none
     */
    static void renderRing(GuiGraphics g, Font font, int cx, int cy, int innerR, int outerR,
                           List<WedgeEntry> wedges, float[] hotStrengths, float menuAlpha,
                           int labelArgb, int labelHotArgb, boolean[] enabled, boolean[] checked,
                           double pointerX, double pointerY) {
        int n = wedges.size();
        float ringA = Mth.clamp(menuAlpha, 0.0f, 1.0f);
        if (n <= 0) {
            drawHub(g, cx, cy, innerR, pointerX, pointerY, scaleAlpha(PLATE, ringA),
                    scaleAlpha(labelArgb, ringA * 0.6f), scaleAlpha(labelHotArgb, ringA));
            return;
        }

        int denom = Math.max(n, 3);
        double rad = (Math.PI * 2.0) / denom;
        double innerGap = Math.PI * 0.007;
        double categoryOuter = innerR + Math.max(2.0, (outerR - innerR) * 0.12);

        for (int i = 0; i < n; i++) {
            WedgeEntry entry = wedges.get(i);
            boolean on = enabled == null || i >= enabled.length || enabled[i];
            int accent = entry.accentRgb();
            int fillArgb;
            int hotArgb;
            int stripCold;
            int stripHot;
            if (on) {
                fillArgb = withAlpha(lerpArgb(0xFF000000 | PLATE, 0xFF000000 | accent, COLD_ACCENT_MIX),
                        COLD_FILL_ALPHA);
                hotArgb = withAlpha(lerpArgb(0xFF000000 | HOT_PLATE_RGB, 0xFF000000 | accent, HOT_ACCENT_MIX),
                        HOT_FILL_ALPHA);
                stripCold = withAlpha(accent, STRIP_COLD_ALPHA);
                stripHot = withAlpha(accent, 0xFF);
            } else {
                fillArgb = hotArgb = PLATE;
                stripCold = stripHot = withAlpha(DISABLED_ACCENT, STRIP_COLD_ALPHA);
            }
            int coldLabel = on ? labelArgb : DISABLED_LABEL;
            int hotLabel = on ? labelHotArgb : DISABLED_LABEL;
            int hotIcon = on ? withAccentTint(labelHotArgb, accent) : DISABLED_LABEL;

            // The hot wedge pushes its outer edge outward, eased off the same lerped strength that
            // colours it, so it grows and settles back with the fade. The angular gap is rescaled
            // to keep the same pixel gap, so the sides stay parallel to the neighbours' and nothing
            // overlaps: growth is purely radial and never enters a neighbour's sector.
            float hotNow = i < hotStrengths.length ? Mth.clamp(hotStrengths[i], 0.0f, 1.0f) : 0.0f;
            double grow = outerR * HOT_GROW * (hotNow * hotNow * (3.0f - 2.0f * hotNow));
            double wedgeOuter = outerR + grow;
            double outerGap = innerGap * (double) innerR / wedgeOuter;

            double lRad = (i - 0.5) * rad - Math.PI / 2.0;
            double rRad = (i + 0.5) * rad - Math.PI / 2.0;

            double x1m1 = Math.cos(lRad + innerGap) * innerR;
            double x2m1 = Math.cos(rRad - innerGap) * innerR;
            double y1m1 = Math.sin(lRad + innerGap) * innerR;
            double y2m1 = Math.sin(rRad - innerGap) * innerR;
            double x1m2 = Math.cos(lRad + outerGap) * wedgeOuter;
            double x2m2 = Math.cos(rRad - outerGap) * wedgeOuter;
            double y1m2 = Math.sin(lRad + outerGap) * wedgeOuter;
            double y2m2 = Math.sin(rRad - outerGap) * wedgeOuter;

            float hot = i < hotStrengths.length ? Mth.clamp(hotStrengths[i], 0.0f, 1.0f) : 0.0f;
            int color = scaleAlpha(lerpArgb(fillArgb, hotArgb, hot), ringA);
            renderQuad(g,
                    (float) (cx + x1m1), (float) (cy + y1m1),
                    (float) (cx + x2m1), (float) (cy + y2m1),
                    (float) (cx + x2m2), (float) (cy + y2m2),
                    (float) (cx + x1m2), (float) (cy + y1m2),
                    color);

            double x1m3 = Math.cos(lRad + innerGap) * categoryOuter;
            double x2m3 = Math.cos(rRad - innerGap) * categoryOuter;
            double y1m3 = Math.sin(lRad + innerGap) * categoryOuter;
            double y2m3 = Math.sin(rRad - innerGap) * categoryOuter;
            boolean isChecked = isChecked(checked, i);
            // A checked toggle keeps its band lit, so the on-state reads without hovering it.
            int strip = scaleAlpha(isChecked ? stripHot : lerpArgb(stripCold, stripHot, hot), ringA);
            renderQuad(g,
                    (float) (cx + x1m1), (float) (cy + y1m1),
                    (float) (cx + x2m1), (float) (cy + y2m1),
                    (float) (cx + x2m3), (float) (cy + y2m3),
                    (float) (cx + x1m3), (float) (cy + y1m3),
                    strip);

            double x1 = Math.cos(lRad);
            double x2 = Math.cos(rRad);
            double y1 = Math.sin(lRad);
            double y2 = Math.sin(rRad);
            double iconR = wedgeOuter * 0.55 + innerR * 0.45;
            double iconX = (x1 + x2) * 0.5 * iconR;
            double iconY = (y1 + y2) * 0.5 * iconR;
            int ix = cx + (int) Math.round(iconX);
            int iy = cy + (int) Math.round(iconY);
            int iconColor = scaleAlpha(lerpArgb(coldLabel, hotIcon, hot), ringA);
            drawScaledIcon(g, font, entry.icon(), ix, iy, iconColor);

            String label = labelText(entry, isChecked);
            Box box = labelBox(font, label, i, denom, cx, cy, wedgeOuter, outerR);
            g.drawString(font, label, box.x(), box.y(),
                    scaleAlpha(lerpArgb(coldLabel, hotLabel, hot), ringA), true);
        }

        // The ring and dot take the hot wedge's tint once the pointer leaves the deadzone.
        float hubHot = 0.0f;
        for (float h : hotStrengths) hubHot = Math.max(hubHot, h);
        int hotIdx = hottestIndex(hotStrengths, n);
        boolean hubOn = enabled == null || hotIdx >= enabled.length || enabled[hotIdx];
        int hubAccent = wedges.get(hotIdx).accentRgb();
        boolean lit = hubHot > 0.35f;
        int ring = lit ? (hubOn ? 0xFF000000 | hubAccent : DISABLED_LABEL) : scaleAlpha(labelArgb, 0.6f);
        int dot = lit ? (hubOn ? withAccentTint(labelHotArgb, hubAccent) : DISABLED_LABEL) : labelHotArgb;
        drawHub(g, cx, cy, innerR, pointerX, pointerY, scaleAlpha(PLATE, ringA),
                scaleAlpha(ring, ringA), scaleAlpha(dot, ringA));
    }

    /**
     * Where the labels reach, measured with every wedge fully grown so hovering never moves the UI
     * placed around them. Top and bottom are floored at a label sitting on the vertical axis, so that
     * UI holds still between sparse and dense menus and only moves for a label that needs the room.
     * Pass the same {@code checked} array as {@link #renderRing}: it changes label widths.
     */
    static LabelBounds computeLabelBounds(Font font, int cx, int cy, int outerR,
                                          List<WedgeEntry> wedges, boolean[] checked) {
        double grownR = outerR * (1.0 + HOT_GROW);
        int axis = (int) Math.ceil(grownR + labelGap(outerR)) + font.lineHeight - 1;
        int top = cy - axis;
        int bottom = cy + axis;
        int right = cx + (int) Math.ceil(grownR);
        int n = wedges.size();
        int denom = Math.max(n, 3);
        for (int i = 0; i < n; i++) {
            Box box = labelBox(font, labelText(wedges.get(i), isChecked(checked, i)), i, denom,
                    cx, cy, grownR, outerR);
            top = Math.min(top, box.y());
            bottom = Math.max(bottom, box.y() + box.h());
            right = Math.max(right, box.x() + box.w());
        }
        return new LabelBounds(top, bottom, right);
    }

    /**
     * A label's text box, pushed out along its wedge's bisector by the box's own extent toward the
     * centre ({@code hw|ux| + hh|uy|}), so the box's nearest supporting line clears the wedge edge by
     * the same gap at every angle, with no alignment thresholds to snap between.
     * ponytail: exact on the axes, slightly loose on diagonals for wide labels (the nearest corner sits
     * past the supporting line); solve d for the Euclidean centre-to-rect distance if that shows.
     */
    private static Box labelBox(Font font, String label, int i, int denom, int cx, int cy,
                                double edgeR, int outerR) {
        double mid = (Math.PI * 2.0) * i / denom - Math.PI / 2.0;
        double ux = Math.cos(mid);
        double uy = Math.sin(mid);
        int w = font.width(label);
        int h = font.lineHeight - 1;
        double hw = w / 2.0;
        double hh = h / 2.0;
        double d = edgeR + labelGap(outerR) + hw * Math.abs(ux) + hh * Math.abs(uy);
        return new Box(cx + (int) Math.round(ux * d - hw), cy + (int) Math.round(uy * d - hh), w, h);
    }

    private static String labelText(WedgeEntry entry, boolean checked) {
        return checked ? "[x] " + entry.label() : entry.label();
    }

    private static boolean isChecked(boolean[] checked, int i) {
        return checked != null && i < checked.length && checked[i];
    }

    private static double labelGap(int outerR) {
        return Math.max(LABEL_GAP_MIN, outerR * LABEL_GAP_FRAC);
    }

    private static void drawScaledIcon(GuiGraphics g, Font font, String icon, int cx, int cy,
                                       int argb) {
        float scale = ICON_SCALE * GLYPH_SCALE.getOrDefault(icon.replace("️", ""), 1.0f);
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().scale(scale, scale, 1.0f);
        int iw = font.width(icon);
        g.drawString(font, icon, -iw / 2, -4, argb, false);
        g.pose().popPose();
    }

    private static int withAccentTint(int labelArgb, int accentRgb) {
        return lerpArgb(labelArgb, 0xFF000000 | (accentRgb & 0xFFFFFF), 0.35f);
    }

    private static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private static int hottestIndex(float[] hot, int n) {
        int best = 0;
        float bestV = -1.0f;
        for (int i = 0; i < n && i < hot.length; i++) {
            if (hot[i] > bestV) {
                bestV = hot[i];
                best = i;
            }
        }
        return best;
    }

    /** Channel-wise lerp of two ARGB colours; {@code t} in 0..1. */
    private static int lerpArgb(int from, int to, float t) {
        t = Mth.clamp(t, 0.0f, 1.0f);
        int a = (int) Mth.lerp(t, (from >> 24) & 0xFF, (to >> 24) & 0xFF);
        int r = (int) Mth.lerp(t, (from >> 16) & 0xFF, (to >> 16) & 0xFF);
        int g = (int) Mth.lerp(t, (from >> 8) & 0xFF, (to >> 8) & 0xFF);
        int b = (int) Mth.lerp(t, from & 0xFF, to & 0xFF);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int scaleAlpha(int argb, float factor) {
        int a = Math.round(((argb >> 24) & 0xFF) * Mth.clamp(factor, 0.0f, 1.0f));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * A plate disc filling the hub out to the wedges, then a thin ring and a dot showing the pointer
     * delta to scale: the ring's edge is {@link RadialInputState#MAX_RADIUS}, so the dot touches it at
     * full deflection.
     */
    private static void drawHub(GuiGraphics g, int cx, int cy, int innerR, double pointerX, double pointerY,
                                int plateArgb, int ringArgb, int dotArgb) {
        fillAnnulus(g, cx, cy, 0.0f, innerR - 1.0f, plateArgb);
        float ringR = Math.max(4.0f, innerR * 0.42f);
        fillAnnulus(g, cx, cy, ringR - 1.0f, ringR, ringArgb);
        float dotR = Math.max(1.25f, innerR * 0.08f);
        double scale = (ringR - 1.0f - dotR) / RadialInputState.MAX_RADIUS;
        fillAnnulus(g, (float) (cx + pointerX * scale), (float) (cy + pointerY * scale), 0.0f, dotR, dotArgb);
    }

    /** Filled ring between two radii as a triangle strip; {@code rIn = 0} gives a disc. */
    private static void fillAnnulus(GuiGraphics g, float cx, float cy, float rIn, float rOut, int argb) {
        int steps = Math.max(24, (int) (rOut * 2.0f));
        Matrix4f matrix = g.pose().last().pose();
        float a = ((argb >> 24) & 0xFF) / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float gc = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int s = 0; s <= steps; s++) {
            double t = (Math.PI * 2.0) * s / steps;
            float cos = (float) Math.cos(t);
            float sin = (float) Math.sin(t);
            buffer.addVertex(matrix, cx + cos * rOut, cy + sin * rOut, 0).setColor(r, gc, b, a);
            buffer.addVertex(matrix, cx + cos * rIn, cy + sin * rIn, 0).setColor(r, gc, b, a);
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    static void renderQuad(GuiGraphics g,
                           float x1, float y1, float x2, float y2,
                           float x3, float y3, float x4, float y4, int argb) {
        Matrix4f matrix = g.pose().last().pose();
        float a = ((argb >> 24) & 0xFF) / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float gc = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buffer.addVertex(matrix, x1, y1, 0).setColor(r, gc, b, a);
        buffer.addVertex(matrix, x2, y2, 0).setColor(r, gc, b, a);
        buffer.addVertex(matrix, x3, y3, 0).setColor(r, gc, b, a);
        buffer.addVertex(matrix, x4, y4, 0).setColor(r, gc, b, a);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }
}
