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
 * <p>Shipped logos are authored at 16×16 and map 1:1. Larger pack icons still go through
 * coverage-aware area averaging as a fallback.
 *
 * <p>Transparency is alpha-only — opaque black ink is real ink (palette 0), not discarded
 * backdrop. Near-white ink is stamped as palette grey (index 2), not pure white (index 1):
 * SBW draws the overlay as a flat lit quad, so full-white pixels read as glowing against hull camo.
 */
public final class PmcLogoEncoder {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SIZE = 16;
    /** Fraction of opaque ink samples required before a cell stamps (larger-than-16 fallback). */
    private static final float MIN_COVERAGE = 0.12f;
    /** SBW palette: 0 = black, 1 = pure white, 2 = mid grey — prefer grey over white for hulls. */
    private static final short PALETTE_BLACK = 0;
    private static final short PALETTE_WHITE = 1;
    private static final short PALETTE_GREY = 2;

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
                if (src.getWidth() == SIZE && src.getHeight() == SIZE) {
                    return rasterizeExact(src);
                }
                return rasterizeArea(src);
            } finally {
                src.close();
            }
        } catch (Exception e) {
            LOGGER.warn("[sewv-pmc-logo] could not encode {}/{}: {}", poolId, iconId, e.toString());
            return null;
        }
    }

    /** 1:1 palette map for authored 16×16 boards. */
    private static List<List<Short>> rasterizeExact(NativeImage image) {
        List<List<Short>> rows = new ArrayList<>(SIZE);
        for (int x = 0; x < SIZE; x++) {
            List<Short> col = new ArrayList<>(SIZE);
            for (int y = 0; y < SIZE; y++) {
                col.add(toPalette(image.getPixelRGBA(x, y)));
            }
            rows.add(col);
        }
        return rows;
    }

    /**
     * Fallback for pack icons larger than 16×16: average opaque ink over each destination cell.
     * Transparent source samples are backdrop and do not dilute the ink colour.
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
                if (a < 128) {
                    continue;
                }
                sumR += abgr & 0xFF;
                sumG += (abgr >> 8) & 0xFF;
                sumB += (abgr >> 16) & 0xFF;
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
        if (luma >= 200 && Math.abs(r - g) < 24 && Math.abs(g - b) < 24) {
            return PALETTE_GREY;
        }
        return toneDownWhite(nearestPaletteIndex((r << 16) | (g << 8) | b));
    }

    private static short toPalette(int abgr) {
        int a = (abgr >> 24) & 0xFF;
        if (a < 128) {
            return -1;
        }
        int r = abgr & 0xFF;
        int g = (abgr >> 8) & 0xFF;
        int b = (abgr >> 16) & 0xFF;
        int luma = (r * 30 + g * 59 + b * 11) / 100;
        if (luma >= 200 && Math.abs(r - g) < 24 && Math.abs(g - b) < 24) {
            return PALETTE_GREY;
        }
        return toneDownWhite(nearestPaletteIndex((r << 16) | (g << 8) | b));
    }

    /** Pure white on a flat overlay reads as emissive — substitute mid grey. */
    private static short toneDownWhite(int index) {
        return index == PALETTE_WHITE ? PALETTE_GREY : (short) index;
    }

    private static int nearestPaletteIndex(int rgb) {
        int best = PALETTE_BLACK;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < PALETTE.length; i++) {
            if (i == PALETTE_WHITE) continue;
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
