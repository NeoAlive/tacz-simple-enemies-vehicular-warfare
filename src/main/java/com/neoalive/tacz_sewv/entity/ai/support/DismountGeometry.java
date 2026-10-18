package com.neoalive.tacz_sewv.entity.ai.support;

/**
 * Where a dismounting squad steps off a hull, in pure doubles so it runs headless
 * ({@code ./gradlew selfCheckDismount}). {@link DismountSequence} owns the world side.
 *
 * <p>The exit is a REAR CORNER, never a door: SBW hulls put their troop doors at the back or the
 * sides with nothing in the data saying which, and the corner is the average of the two — a man
 * stepping out of it reads as having come out of either.
 *
 * <p>Frame: {@code (fwdX, fwdZ)} is the hull's horizontal forward unit vector and "right" is
 * {@code (-fwdZ, fwdX)}, which is the hull's right-hand side in Minecraft's left-handed XZ (a hull
 * facing +Z has its right on -X). {@code side} is {@code +1} right, {@code -1} left.
 */
public final class DismountGeometry {

    /**
     * Blocks a corner sits outside the footprint on both axes: a unit's half-width (0.3) plus
     * clearance. It is what keeps the unit's feet out of every hull OBB — SBW's OBB support lifts
     * an entity whose feet are embedded, the trap {@code RappelSupport.ON_ROPE} documents — and it
     * holds by construction, which is why the exit probe never tests the OBBs themselves.
     */
    public static final double MARGIN = 0.75;
    /** Game ticks between exit steps: a quarter second. */
    public static final int EXIT_INTERVAL_TICKS = 5;

    private DismountGeometry() {}

    /** Hull extent in its own frame: {@code rear} is the most negative forward reach (<= 0). */
    public record Footprint(double rear, double halfWidth) {}

    /**
     * Footprint from {@code count} XZ points (flat pairs, relative to the hull centre) projected
     * onto the hull's own axes. No points falls back to a square of {@code fallbackHalf}.
     */
    public static Footprint footprint(double[] xz, int count, double fwdX, double fwdZ, double fallbackHalf) {
        if (count <= 0) return new Footprint(-fallbackHalf, fallbackHalf);
        double rightX = -fwdZ;
        double rightZ = fwdX;
        double rear = 0.0;
        double half = 0.0;
        for (int i = 0; i < count; i++) {
            double px = xz[2 * i];
            double pz = xz[2 * i + 1];
            rear = Math.min(rear, px * fwdX + pz * fwdZ);
            half = Math.max(half, Math.abs(px * rightX + pz * rightZ));
        }
        return new Footprint(rear, half);
    }

    /** World XZ offset from the hull centre of the {@code side} rear corner. */
    public static double[] cornerOffset(Footprint fp, int side, double fwdX, double fwdZ) {
        return toWorld(side * (fp.halfWidth() + MARGIN), fp.rear() - MARGIN, fwdX, fwdZ);
    }

    /** Unit vector pointing out of the {@code side} rear corner, 45 degrees between back and side. */
    public static double[] outward(int side, double fwdX, double fwdZ) {
        double[] d = toWorld(side, -1.0, fwdX, fwdZ);
        double inv = 1.0 / Math.sqrt(2.0);
        return new double[] {d[0] * inv, d[1] * inv};
    }

    /**
     * The side facing away from a threat at {@code (toX, toZ)} relative to the hull, so the hull
     * stands between the file and the gun. {@code 0} = dead ahead or astern, no preference.
     */
    public static int awaySide(double fwdX, double fwdZ, double toX, double toZ) {
        double lateral = toX * -fwdZ + toZ * fwdX;
        if (Math.abs(lateral) < 1.0E-6) return 0;
        return lateral > 0.0 ? -1 : 1;
    }

    /** No-threat fallback by entity id, the same parity convention the flank direction uses. */
    public static int paritySide(int entityId) {
        return (entityId & 1) == 0 ? 1 : -1;
    }

    /** Exit steps for {@code n} men: pairs from both corners when even, single file when odd. */
    public static int steps(int n) {
        return n % 2 == 0 ? n / 2 : n;
    }

    private static double[] toWorld(double lateral, double forward, double fwdX, double fwdZ) {
        return new double[] {
                forward * fwdX + lateral * -fwdZ,
                forward * fwdZ + lateral * fwdX
        };
    }
}
