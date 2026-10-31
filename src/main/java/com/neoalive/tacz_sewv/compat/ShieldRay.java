package com.neoalive.tacz_sewv.compat;

/**
 * Does a barrel's line meet a tripod's shield? The shield is an ellipsoid, radii {@code rx, ry, rx}
 * round its centre (the mod's {@code TripodShield.metric}). Scaling y by {@code rx / ry} turns it
 * into a sphere of radius {@code rx}, and the rest is the usual ray-sphere quadratic. Pure, so
 * {@code ./gradlew selfCheckShieldRay} runs it headless.
 */
public final class ShieldRay {

    private ShieldRay() {}

    /**
     * True when the ray from {@code p} along {@code d} (any length) meets the ellipsoid ahead of
     * {@code p}, or {@code p} is already inside it: from inside, every direction hits the shell.
     */
    public static boolean hits(double px, double py, double pz, double dx, double dy, double dz,
                               double cx, double cy, double cz, double rx, double ry) {
        if (rx <= 0.0 || ry <= 0.0) return false;
        double k = rx / ry;
        double ox = px - cx, oy = (py - cy) * k, oz = pz - cz;
        double vy = dy * k;
        double a = dx * dx + vy * vy + dz * dz;
        if (a < 1.0E-12) return false;
        double c = ox * ox + oy * oy + oz * oz - rx * rx;
        if (c <= 0.0) return true;
        double b = 2.0 * (ox * dx + oy * vy + oz * dz);
        // Outside the shell both roots share a sign, so the hit is ahead exactly when b < 0.
        return b < 0.0 && b * b - 4.0 * a * c >= 0.0;
    }
}
