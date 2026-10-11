package com.neoalive.tacz_sewv.entity.ai.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import com.neoalive.tacz_sewv.entity.ai.support.ReducedCharge.Charge;

/**
 * Headless checks for {@link ReducedCharge}. Run via {@code ./gradlew selfCheckReducedCharge}.
 * "Reaches the target" is checked against SBW's own tick loop ({@code FastThrowableProjectile
 * .projectileMove}: move by velocity, then subtract gravity), not the textbook parabola.
 */
public final class ReducedChargeSelfCheck {

    private static final Path PURE_SRC = Path.of("src/main/java/com/neoalive/tacz_sewv/entity/ai/support/ReducedCharge.java");
    private static final Pattern NON_STRICT = Pattern.compile("(?<!Strict)\\bMath\\.");
    private static final double G = 0.06, V_PLZ = 18.0;

    public static void main(String[] args) throws IOException {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        double[] worst = checkReachesTarget(); // 1
        checkPreferredReturned();              // 2
        checkClamped();                        // 3
        checkAboveApex();                      // 4
        checkNonPositive();                    // 5
        checkBearingContinuity();              // 6
        checkDeterminism();                    // 7
        checkPurity();                         // 8
        System.out.printf("ReducedChargeSelfCheck OK — worst miss %.3f blocks; 45deg flight 300/500/1000 = %.0f/%.0f/%.0f ticks%n",
                worst[0], flight(300), flight(500), flight(1000));
    }

    private static double flight(double dh) {
        return ReducedCharge.solve(dh, 0, 0, G, 45, -3, 65, V_PLZ).flightTicks();
    }

    /** Vertical miss where SBW's tick polyline crosses the target's horizontal distance. */
    private static double missAt(Charge c, double dh, double dy) {
        double vh = c.velocity() * StrictMath.hypot(c.x(), c.z());
        double vy = c.velocity() * c.y();
        double h = 0, y = 0;
        for (int n = 0; n < 100_000; n++) {
            double nh = h + vh, ny = y + vy; // move...
            vy -= G;                         // ...then gravity
            if (nh >= dh) {
                double t = (dh - h) / vh;
                return y + t * (ny - y) - dy;
            }
            h = nh;
            y = ny;
        }
        throw new AssertionError("never reached dh=" + dh);
    }

    /** 1. Over a (dh, dy, elevation) sweep, the shell's path passes through the target. */
    private static double[] checkReachesTarget() {
        double worst = 0;
        int solved = 0;
        for (double dh = 20; dh <= 2048; dh += 37) {
            for (double dy = -60; dy <= 60; dy += 15) {
                for (double elev = 15; elev <= 80; elev += 5) {
                    Charge c = ReducedCharge.solve(dh, dy, 0, G, elev, -90, 90, 1e9);
                    if (c == null) continue;
                    solved++;
                    double miss = StrictMath.abs(missAt(c, dh, dy));
                    // Chords of a parabola sag at most g/8 under it; allow float noise on top.
                    assert miss <= G / 8 + 1e-6 : "miss " + miss + " dh=" + dh + " dy=" + dy + " elev=" + elev;
                    worst = StrictMath.max(worst, miss);
                    double expectT = dh / (c.velocity() * StrictMath.hypot(c.x(), c.z()));
                    assert StrictMath.abs(c.flightTicks() - expectT) < 1e-9 : "flight time";
                }
            }
        }
        assert solved > 1000 : "sweep solved too few: " + solved;
        return new double[]{worst};
    }

    /** 2. The preferred elevation is used when it fits; the real launch is a hair under it. */
    private static void checkPreferredReturned() {
        Charge c = ReducedCharge.solve(500, 0, 0, G, 45, -3, 65, V_PLZ);
        assert c != null && c.arcElevDeg() == 45.0 : "preferred elevation";
        assert c.elevDeg() < 45.0 && c.elevDeg() > 44.0 : "launch elevation is the g/2-corrected one: " + c.elevDeg();
        assert StrictMath.abs(c.velocity() - StrictMath.sqrt(G * 500)) < 0.05 : "~sqrt(g*dh) at 45: " + c.velocity();
    }

    /** 3. Outside the stops the elevation clamps to the nearer one. */
    private static void checkClamped() {
        assert ReducedCharge.solve(500, 0, 0, G, 45, -5, 30, V_PLZ).arcElevDeg() == 30.0 : "ceiling (mle_1934)";
        assert ReducedCharge.solve(500, 0, 0, G, 10, 20, 60, V_PLZ).arcElevDeg() == 20.0 : "floor";
    }

    /** 4. A target at or above the apex reachable at that elevation has no charge. */
    private static void checkAboveApex() {
        // tan(45) * dh == dy: exactly on the asymptote.
        assert ReducedCharge.solve(100, 100, 0, G, 45, -90, 90, 1e9) == null : "on the 45deg line";
        assert ReducedCharge.solve(100, 150, 0, G, 45, -90, 90, 1e9) == null : "above it";
    }

    /** 5. Degenerate or over-charge inputs are null, never NaN. */
    private static void checkNonPositive() {
        assert ReducedCharge.solve(500, 0, 0, 0.0, 45, -90, 90, 1e9) == null : "g = 0";
        assert ReducedCharge.solve(500, 0, 0, -G, 45, -90, 90, 1e9) == null : "g < 0";
        assert ReducedCharge.solve(Double.NaN, 0, 0, G, 45, -90, 90, 1e9) == null : "NaN";
        assert ReducedCharge.solve(0, -10, 0, G, 45, -90, 90, 1e9) == null : "no horizontal distance";
        // TOS-1A: v=7 at 45 reaches ~817 blocks; past that a reduced charge would be a bigger one.
        assert ReducedCharge.solve(700, 0, 0, G, 45, 0, 50, 7.0) != null : "inside full-charge reach";
        assert ReducedCharge.solve(900, 0, 0, G, 45, 0, 50, 7.0) == null : "past full-charge reach";
    }

    /** 6. Horizontal bearing equals the bearing to the target at every range and bearing. */
    private static void checkBearingContinuity() {
        for (double bearing = 0; bearing < 360; bearing += 11) {
            double b = StrictMath.toRadians(bearing);
            for (double r = 2; r < 2048; r += 23) {
                Charge c = ReducedCharge.solve(r * StrictMath.sin(b), 5, r * StrictMath.cos(b), G, 45, -3, 65, V_PLZ);
                if (c == null) continue;
                double err = StrictMath.abs(StrictMath.IEEEremainder(StrictMath.atan2(c.x(), c.z()) - b, 2 * StrictMath.PI));
                assert err < 1e-9 : "bearing jumps at r=" + r + " bearing=" + bearing;
            }
        }
    }

    /** 7. Repeated calls are bit-identical. */
    private static void checkDeterminism() {
        Charge a = ReducedCharge.solve(1234.5, -7.25, -811.0, G, 45, -3, 65, V_PLZ);
        for (int i = 0; i < 100; i++) {
            assert a.equals(ReducedCharge.solve(1234.5, -7.25, -811.0, G, 45, -3, 65, V_PLZ));
        }
    }

    /** 8. No Minecraft/SBW imports, no non-strict Math. */
    private static void checkPurity() throws IOException {
        String src = Files.readString(PURE_SRC);
        assert !src.contains("import net.minecraft") && !src.contains("import com.atsuishio") : "imports MC/SBW";
        assert !NON_STRICT.matcher(src).find() : "non-strict Math";
    }
}
