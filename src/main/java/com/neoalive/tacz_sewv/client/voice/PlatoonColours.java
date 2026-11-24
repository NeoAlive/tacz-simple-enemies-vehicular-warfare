package com.neoalive.tacz_sewv.client.voice;

import java.util.List;

/**
 * Names a platoon colour so it can be spoken. Platoon colours are a hue rolled from the platoon id
 * ({@code Platoon}, saturation 0.65, brightness 0.95), so the name is the nearest of eight hue bands.
 * Two platoons can share a name; an order to that name reaches both.
 */
public final class PlatoonColours {

    public static final List<String> NAMES = List.of("red", "orange", "yellow", "green", "cyan", "blue", "purple", "pink");
    /** Upper hue bound of each band, in degrees; the last band wraps back to red. */
    private static final float[] BOUNDS = {15, 45, 70, 160, 200, 255, 290, 340};

    private PlatoonColours() {}

    public static String nameOf(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float hue;
        if (d == 0) hue = 0;
        else if (max == r) hue = 60 * (((g - b) / d) % 6);
        else if (max == g) hue = 60 * ((b - r) / d + 2);
        else hue = 60 * ((r - g) / d + 4);
        if (hue < 0) hue += 360;
        for (int i = 0; i < BOUNDS.length; i++) if (hue < BOUNDS[i]) return NAMES.get(i);
        return NAMES.get(0);
    }
}
