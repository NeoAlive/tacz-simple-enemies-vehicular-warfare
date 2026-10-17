package com.neoalive.tacz_sewv.entity.ai.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import com.atsuishio.superbwarfare.tools.TrajectoryCalculator;
import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.entity.ai.support.BallisticArc.Aim;

/**
 * Headless checks for {@link BallisticArc}. Run via {@code ./gradlew selfCheckArtillery}.
 * The reference is SBW's real {@code TrajectoryCalculator} off the test classpath, so "bit-exact"
 * is checked against the code the mortar used to call, not against a second transcription.
 */
public final class BallisticArcSelfCheck {

    private static final Path PURE_SRC = Path.of("src/main/java/com/neoalive/tacz_sewv/entity/ai/support/BallisticArc.java");
    private static final Pattern NON_STRICT = Pattern.compile("(?<!Strict)\\bMath\\.");

    // PLZ-05 and mortar-ish numbers (blocks/tick, blocks/tick²).
    private static final double V_PLZ = 18.0, G_PLZ = 0.06;
    private static final double V_MORTAR = 8.0, G_MORTAR = 0.1;

    public static void main(String[] args) throws IOException {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        checkBitExactAgainstSbw();   // 1
        checkHighFirst();            // 2
        checkNearRootRejected();     // 3
        checkUnreachable();          // 4
        checkDegenerate();           // 5
        checkEnvelopeBoundaries();   // 6
        checkBearingContinuity();    // 7
        checkFallback();             // 8
        checkDeterminism();          // 9
        checkPurity();               // 10
        System.out.println("BallisticArcSelfCheck OK");
    }

    /** 1. Every root bit-identical to SBW's over a range × height × velocity sweep. */
    private static void checkBitExactAgainstSbw() {
        int compared = 0;
        double[][] guns = {{V_PLZ, G_PLZ}, {V_MORTAR, G_MORTAR}, {15.0, 0.05}, {7.0, 0.05}};
        for (double[] gun : guns) {
            for (double range = 0.5; range < 6000.0; range = range * 1.17 + 0.3) {
                for (double dy = -80.0; dy <= 80.0; dy += 13.7) {
                    for (double bearing = 0.0; bearing < 360.0; bearing += 47.0) {
                        double b = StrictMath.toRadians(bearing);
                        double dx = range * StrictMath.sin(b), dz = range * StrictMath.cos(b);
                        Vec3 from = new Vec3(103.25, 64.6, -817.5);
                        Vec3 to = new Vec3(from.x + dx, from.y + dy, from.z + dz);
                        List<Vec3> ref = TrajectoryCalculator.INSTANCE.calculateShootVectors(from, to, gun[0], gun[1]);
                        double[][] ours = BallisticArc.roots(to.x - from.x, to.y - from.y, to.z - from.z, gun[0], gun[1]);
                        assert ref.size() == ours.length : "root count range=" + range + " dy=" + dy;
                        for (int i = 0; i < ours.length; i++) {
                            Vec3 r = ref.get(i);
                            assert Double.doubleToRawLongBits(r.x) == Double.doubleToRawLongBits(ours[i][0])
                                    && Double.doubleToRawLongBits(r.y) == Double.doubleToRawLongBits(ours[i][1])
                                    && Double.doubleToRawLongBits(r.z) == Double.doubleToRawLongBits(ours[i][2])
                                    : "root " + i + " differs range=" + range + " dy=" + dy;
                            compared++;
                        }
                    }
                }
            }
        }
        assert compared > 1000 : "sweep compared too few roots: " + compared;
    }

    /** 2. Index 0 is the steeper (longer-flight) root whenever there are two. */
    private static void checkHighFirst() {
        for (double range = 5.0; range < 3000.0; range += 37.0) {
            double[][] r = BallisticArc.roots(range, 0.0, 0.0, V_PLZ, G_PLZ);
            if (r.length < 2) continue;
            assert BallisticArc.elevDeg(r[0][0], r[0][1], r[0][2]) > BallisticArc.elevDeg(r[1][0], r[1][1], r[1][2])
                    : "high root not first at " + range;
        }
        Aim a = BallisticArc.solve(300.0, 0.0, 0.0, V_MORTAR, G_MORTAR, -90.0, 90.0);
        assert a != null && a.high() && a.elevDeg() > 45.0 : "open envelope must take the lob";
    }

    /** 3. PLZ-05 at radio ranges: the lob is over the 65° stop, the low root is taken. */
    private static void checkNearRootRejected() {
        Aim a = BallisticArc.solve(352.0, 0.0, 0.0, V_PLZ, G_PLZ, -3.0, 65.0);
        assert a != null && !a.high() && a.elevDeg() < 10.0 : "expected the low root at 352";
        // Mortar floor 20°: the flat root is refused, the lob kept.
        Aim m = BallisticArc.solve(200.0, 0.0, 0.0, V_MORTAR, G_MORTAR, 20.0, 89.0);
        assert m != null && m.high() : "mortar must keep the lob";
        // Target far below at short range: both roots outside [-3, 65] (the hull #2 log case).
        assert BallisticArc.solve(60.0, -19.0, 0.0, V_PLZ, G_PLZ, -3.0, 65.0) == null : "both roots out of envelope";
    }

    /** 4. Past maximum range there is no root at all. */
    private static void checkUnreachable() {
        double maxRange = V_PLZ * V_PLZ / G_PLZ; // flat-ground vacuum maximum
        assert BallisticArc.roots(maxRange * 1.01, 0.0, 0.0, V_PLZ, G_PLZ).length == 0 : "beyond max range";
        assert BallisticArc.solve(maxRange * 1.01, 0.0, 0.0, V_PLZ, G_PLZ, -90.0, 90.0) == null;
        assert BallisticArc.roots(maxRange * 0.99, 0.0, 0.0, V_PLZ, G_PLZ).length == 2 : "inside max range";
    }

    /** 5. Degenerate inputs are empty, never NaN. */
    private static void checkDegenerate() {
        assert BallisticArc.roots(100, 0, 0, 0.0, G_PLZ).length == 0;
        assert BallisticArc.roots(100, 0, 0, -1.0, G_PLZ).length == 0;
        assert BallisticArc.roots(100, 0, 0, V_PLZ, 0.0).length == 0;
        assert BallisticArc.roots(Double.NaN, 0, 0, V_PLZ, G_PLZ).length == 0;
        assert BallisticArc.roots(100, 0, 0, Double.POSITIVE_INFINITY, G_PLZ).length == 0;
        assert BallisticArc.solve(100, 0, 0, 0.0, G_PLZ, -90, 90) == null;
    }

    /** 6. Inclusive stops: exactly at a stop fits, 1e-9 beyond does not. */
    private static void checkEnvelopeBoundaries() {
        double[][] r = BallisticArc.roots(352.0, 0.0, 0.0, V_PLZ, G_PLZ);
        double low = BallisticArc.elevDeg(r[1][0], r[1][1], r[1][2]);
        assert BallisticArc.solve(352.0, 0.0, 0.0, V_PLZ, G_PLZ, low, 65.0) != null : "at min stop";
        assert BallisticArc.solve(352.0, 0.0, 0.0, V_PLZ, G_PLZ, low + 1e-9, 65.0) == null : "above min stop";
        assert BallisticArc.solve(352.0, 0.0, 0.0, V_PLZ, G_PLZ, -3.0, low) != null : "at max stop";
        assert BallisticArc.solve(352.0, 0.0, 0.0, V_PLZ, G_PLZ, -3.0, low - 1e-9) == null : "below max stop";
    }

    /** 7. The horizontal bearing of every root is the bearing to the target, at every range. */
    private static void checkBearingContinuity() {
        for (double bearing = 0.0; bearing < 360.0; bearing += 11.0) {
            double b = StrictMath.toRadians(bearing);
            for (double range = 2.0; range < 5000.0; range += 23.0) {
                double[][] r = BallisticArc.roots(range * StrictMath.sin(b), 5.0, range * StrictMath.cos(b), V_PLZ, G_PLZ);
                for (double[] d : r) {
                    double got = StrictMath.atan2(d[0], d[2]);
                    double err = StrictMath.abs(StrictMath.IEEEremainder(got - b, 2.0 * StrictMath.PI));
                    assert err < 1e-9 : "bearing jumps at range=" + range + " bearing=" + bearing;
                }
            }
        }
    }

    /** 8. Fallback: low root clamped when roots exist, 45° clamped when none. */
    private static void checkFallback() {
        double f = BallisticArc.fallbackElevDeg(60.0, -19.0, 0.0, V_PLZ, G_PLZ, -3.0, 65.0);
        assert f == -3.0 : "below depression stop must hold at the stop, got " + f;
        double maxRange = V_PLZ * V_PLZ / G_PLZ;
        assert BallisticArc.fallbackElevDeg(maxRange * 2, 0, 0, V_PLZ, G_PLZ, -3.0, 65.0) == 45.0 : "out of range = 45";
        assert BallisticArc.fallbackElevDeg(maxRange * 2, 0, 0, V_PLZ, G_PLZ, 0.0, 30.0) == 30.0 : "45 clamped to max";
    }

    /** 9. Repeated calls are bit-identical. */
    private static void checkDeterminism() {
        Aim a = BallisticArc.solve(1234.5, -7.25, -811.0, V_PLZ, G_PLZ, -3.0, 65.0);
        for (int i = 0; i < 100; i++) assert a.equals(BallisticArc.solve(1234.5, -7.25, -811.0, V_PLZ, G_PLZ, -3.0, 65.0));
    }

    /** 10. No Minecraft/SBW imports, no non-strict Math. */
    private static void checkPurity() throws IOException {
        String src = Files.readString(PURE_SRC);
        assert !src.contains("import net.minecraft") && !src.contains("import com.atsuishio") : "BallisticArc imports MC/SBW";
        assert !NON_STRICT.matcher(src).find() : "BallisticArc uses non-strict Math";
    }
}
