package com.neoalive.tacz_sewv.client.radial;

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

/**
 * Effortless-inspired radial draw: one gapped trapezoid quad per wedge (not tessellated arcs).
 * Selection / size / mouse math stay in {@link RadialInputState} and the screen; this is paint only.
 */
final class RadialWheelDraw {

    private RadialWheelDraw() {}

    /**
     * @param outerR current wheel outer radius (screen already chose size)
     * @param innerR current wheel inner radius
     * @param hotStrengths 0..1 per wedge (fade toward selection); length must match wedges
     * @param menuAlpha 0..1 whole-ring opacity (open / submenu fade)
     */
    static void renderRing(GuiGraphics g, Font font, int cx, int cy, int innerR, int outerR,
                           java.util.List<WedgeEntry> wedges, float[] hotStrengths, float menuAlpha,
                           int fillArgb, int hotArgb, int hubArgb, int labelArgb, int labelHotArgb) {
        int n = wedges.size();
        float ringA = Mth.clamp(menuAlpha, 0.0f, 1.0f);
        if (n <= 0) {
            drawHub(g, font, cx, cy, innerR, scaleAlpha(hubArgb, ringA), "•",
                    scaleAlpha(labelHotArgb, ringA));
            return;
        }

        // Effortless: keep wedges from getting huge when few slots by flooring divisor.
        int denom = Math.max(n, 3);
        double rad = (Math.PI * 2.0) / denom;
        double innerGap = Math.PI * 0.007;
        double outerGap = innerGap * (double) innerR / (double) outerR;
        double categoryOuter = innerR + Math.max(2.0, (outerR - innerR) * 0.12);

        for (int i = 0; i < n; i++) {
            // Slot 0 at top; wedge centred on i (same polar layout as Effortless).
            double lRad = (i - 0.5) * rad - Math.PI / 2.0;
            double rRad = (i + 0.5) * rad - Math.PI / 2.0;

            double x1m1 = Math.cos(lRad + innerGap) * innerR;
            double x2m1 = Math.cos(rRad - innerGap) * innerR;
            double y1m1 = Math.sin(lRad + innerGap) * innerR;
            double y2m1 = Math.sin(rRad - innerGap) * innerR;
            double x1m2 = Math.cos(lRad + outerGap) * outerR;
            double x2m2 = Math.cos(rRad - outerGap) * outerR;
            double y1m2 = Math.sin(lRad + outerGap) * outerR;
            double y2m2 = Math.sin(rRad - outerGap) * outerR;

            float hot = i < hotStrengths.length ? Mth.clamp(hotStrengths[i], 0.0f, 1.0f) : 0.0f;
            int color = scaleAlpha(lerpArgb(fillArgb, hotArgb, hot), ringA);
            renderQuad(g,
                    (float) (cx + x1m1), (float) (cy + y1m1),
                    (float) (cx + x2m1), (float) (cy + y2m1),
                    (float) (cx + x2m2), (float) (cy + y2m2),
                    (float) (cx + x1m2), (float) (cy + y1m2),
                    color);

            // Thin inner category band (Effortless tint strip) — brighter when hot.
            double x1m3 = Math.cos(lRad + innerGap) * categoryOuter;
            double x2m3 = Math.cos(rRad - innerGap) * categoryOuter;
            double y1m3 = Math.sin(lRad + innerGap) * categoryOuter;
            double y2m3 = Math.sin(rRad - innerGap) * categoryOuter;
            int strip = scaleAlpha(lerpArgb(0x885A6A7A, 0xEE5A9A7A, hot), ringA);
            renderQuad(g,
                    (float) (cx + x1m1), (float) (cy + y1m1),
                    (float) (cx + x2m1), (float) (cy + y2m1),
                    (float) (cx + x2m3), (float) (cy + y2m3),
                    (float) (cx + x1m3), (float) (cy + y1m3),
                    strip);

            WedgeEntry entry = wedges.get(i);
            // Effortless places the icon on the average of the edge unit vectors (not a
            // renormalised bisector) — same visual for 3–8 wedges, cheaper.
            double x1 = Math.cos(lRad);
            double x2 = Math.cos(rRad);
            double y1 = Math.sin(lRad);
            double y2 = Math.sin(rRad);
            double iconR = outerR * 0.55 + innerR * 0.45;
            double iconX = (x1 + x2) * 0.5 * iconR;
            double iconY = (y1 + y2) * 0.5 * iconR;
            int ix = cx + (int) Math.round(iconX);
            int iy = cy + (int) Math.round(iconY);
            String icon = entry.icon();
            int iw = font.width(icon);
            g.drawString(font, icon, ix - iw / 2, iy - 4,
                    scaleAlpha(lerpArgb(labelArgb, labelHotArgb, hot), ringA), false);

            double bx = (x1 + x2) * 0.5;
            double by = (y1 + y2) * 0.5;
            double textR = outerR + Math.max(10, outerR * 0.22);
            // Scale the average vector out to textR (same shape as Effortless TEXT_DISTANCE).
            double avgLen = Math.hypot(bx, by);
            double txOff = avgLen > 1.0e-6 ? (bx / avgLen) * textR : 0;
            double tyOff = avgLen > 1.0e-6 ? (by / avgLen) * textR : -textR;
            int tx = cx + (int) Math.round(txOff);
            int ty = cy + (int) Math.round(tyOff);
            String label = entry.label();
            int tw = font.width(label);
            int drawX;
            if (bx < -0.2) {
                drawX = tx - tw;
            } else if (bx > 0.2) {
                drawX = tx;
            } else {
                drawX = tx - tw / 2;
            }
            g.drawString(font, label, drawX, ty - 4,
                    scaleAlpha(lerpArgb(labelArgb, labelHotArgb, hot), ringA), false);
        }

        float hubHot = 0.0f;
        for (float h : hotStrengths) hubHot = Math.max(hubHot, h);
        String hub = hubHot > 0.35f && n > 0
                ? wedges.get(hottestIndex(hotStrengths, n)).icon() : "•";
        drawHub(g, font, cx, cy, innerR, scaleAlpha(hubArgb, ringA), hub,
                scaleAlpha(labelHotArgb, ringA));
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

    private static void drawHub(GuiGraphics g, Font font, int cx, int cy, int innerR,
                                int hubArgb, String glyph, int glyphArgb) {
        // Soft disc via triangle fan (hub stays hollow-looking relative to the ring).
        int steps = Math.max(16, innerR);
        float r = Math.max(0, innerR - 3);
        Matrix4f matrix = g.pose().last().pose();
        float a = ((hubArgb >> 24) & 0xFF) / 255f;
        float rc = ((hubArgb >> 16) & 0xFF) / 255f;
        float gc = ((hubArgb >> 8) & 0xFF) / 255f;
        float bc = (hubArgb & 0xFF) / 255f;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        buffer.vertex(matrix, cx, cy, 0).color(rc, gc, bc, a).endVertex();
        for (int s = 0; s <= steps; s++) {
            double t = (Math.PI * 2.0) * s / steps;
            buffer.vertex(matrix, (float) (cx + Math.cos(t) * r), (float) (cy + Math.sin(t) * r), 0)
                    .color(rc, gc, bc, a).endVertex();
        }
        BufferUploader.drawWithShader(buffer.end());

        int gw = font.width(glyph);
        g.drawString(font, glyph, cx - gw / 2, cy - 4, glyphArgb, false);
    }

    /** One Effortless-style trapezoid (GUI has no cull on these shaders). */
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
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buffer.vertex(matrix, x1, y1, 0).color(r, gc, b, a).endVertex();
        buffer.vertex(matrix, x2, y2, 0).color(r, gc, b, a).endVertex();
        buffer.vertex(matrix, x3, y3, 0).color(r, gc, b, a).endVertex();
        buffer.vertex(matrix, x4, y4, 0).color(r, gc, b, a).endVertex();
        BufferUploader.drawWithShader(buffer.end());
    }
}
