package com.neoalive.tacz_sewv.skin;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.crew.LogoPoolIndex;

/**
 * Converts logo PNGs into SBW's 16×16 dogTag pixel grid (palette indices 0–15, {@code -1} =
 * transparent). Palette matches {@code DogTagEditorScreen.getColorByNum} in Superb Warfare.
 *
 * <p>Downscale is coverage-aware area averaging, not nearest-neighbour: logo PNGs are thin line
 * art on a black/transparent field, and a 1:1 sample into 16×16 either drops strokes or mixes
 * them with backdrop into muddy greys that then quantize badly.
 */
public final class PmcLogoEncoder {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SIZE = 16;
    /** Fraction of opaque ink samples required before a cell stamps (keeps thin strokes alive). */
    private static final float MIN_COVERAGE = 0.12f;
    /** Luminance below this after averaging is treated as leftover backdrop, not ink. */
    private static final int MIN_INK_LUMA = 48;

    /** ARGB palette RGB components 0–15 — from Superb Warfare DogTagEditorScreen.getColorByNum. */
    private static final int[] PALETTE = new int[16];

    static {
        int[] kotlin = {
                -0x1000000, -0x1, -0x7f7f80, -0x2bdbdc, -0x5600, -0x100, -0xc31fc4, -0x993301,
                -0xc5b001, -0x49ab01, -0x82a7bf, -0x6859, -0x896ba2, -0x3c00, -0xb3bda5, -0x71b30
        };
        for (int i = 0; i < kotlin.length; i++) {
            PALETTE[i] = kotlin[i] & 0xFFFFFF;
        }
    }

    private static final Map<String, List<List<Short>>> CACHE = new HashMap<>();

    private PmcLogoEncoder() {
    }

    public static void invalidateCache() {
        CACHE.clear();
    }

    @Nullable
    public static List<List<Short>> encode(String poolId, String iconId) {
        String key = poolId + "/" + iconId;
        return CACHE.computeIfAbsent(key, k -> encodeFresh(poolId, iconId));
    }

    @Nullable
    private static List<List<Short>> encodeFresh(String poolId, String iconId) {
        try (InputStream in = LogoPoolIndex.openIcon(poolId, iconId)) {
            if (in == null) return null;
            NativeImage src = NativeImage.read(in);
            try {
                return rasterizeArea(src);
            } finally {
                src.close();
            }
        } catch (Exception e) {
            LOGGER.warn("[sewv-pmc-logo] could not encode {}/{}: {}", poolId, iconId, e.toString());
            return null;
        }
    }

    /**
     * For each destination cell, average opaque ink over the corresponding source rectangle.
     * Near-black / transparent source samples are backdrop and do not dilute the ink colour.
     */
    private static List<List<Short>> rasterizeArea(NativeImage src) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        List<List<Short>> rows = new ArrayList<>(SIZE);
        for (int x = 0; x < SIZE; x++) {
            List<Short> col = new ArrayList<>(SIZE);
            int x0 = x * sw / SIZE;
            int x1 = Math.max(x0 + 1, (x + 1) * sw / SIZE);
            for (int y = 0; y < SIZE; y++) {
                int y0 = y * sh / SIZE;
                int y1 = Math.max(y0 + 1, (y + 1) * sh / SIZE);
                col.add(sampleCell(src, x0, x1, y0, y1));
            }
            rows.add(col);
        }
        return rows;
    }

    private static short sampleCell(NativeImage src, int x0, int x1, int y0, int y1) {
        long sumR = 0;
        long sumG = 0;
        long sumB = 0;
        int ink = 0;
        int total = 0;
        for (int sy = y0; sy < y1; sy++) {
            for (int sx = x0; sx < x1; sx++) {
                total++;
                int abgr = src.getPixelRGBA(sx, sy);
                int a = (abgr >> 24) & 0xFF;
                int r = abgr & 0xFF;
                int g = (abgr >> 8) & 0xFF;
                int b = (abgr >> 16) & 0xFF;
                if (a < 128 || r + g + b < 24) {
                    continue;
                }
                sumR += r;
                sumG += g;
                sumB += b;
                ink++;
            }
        }
        if (total == 0 || ink == 0) {
            return -1;
        }
        float coverage = (float) ink / (float) total;
        if (coverage < MIN_COVERAGE) {
            return -1;
        }
        int r = (int) (sumR / ink);
        int g = (int) (sumG / ink);
        int b = (int) (sumB / ink);
        int luma = (r * 30 + g * 59 + b * 11) / 100;
        if (luma < MIN_INK_LUMA) {
            return -1;
        }
        // Pure-ish white line art snaps to palette white; avoids grey anti-alias mush.
        if (luma >= 200 && Math.abs(r - g) < 24 && Math.abs(g - b) < 24) {
            return 1;
        }
        return (short) nearestPaletteIndex((r << 16) | (g << 8) | b);
    }

    /** Skip palette 0 (black) — black ink on a dark hull is invisible; map to nearest non-black. */
    private static int nearestPaletteIndex(int rgb) {
        int best = 1;
        long bestDist = Long.MAX_VALUE;
        for (int i = 1; i < PALETTE.length; i++) {
            int pr = (PALETTE[i] >> 16) & 0xFF;
            int pg = (PALETTE[i] >> 8) & 0xFF;
            int pb = PALETTE[i] & 0xFF;
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            long dr = r - pr;
            long dg = g - pg;
            long db = b - pb;
            long dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }
}
