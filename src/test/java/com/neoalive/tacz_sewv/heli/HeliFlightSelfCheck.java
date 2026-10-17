package com.neoalive.tacz_sewv.heli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import com.neoalive.tacz_sewv.entity.ai.support.HeliStandoffSelfCheck;
import com.neoalive.tacz_sewv.heli.physics.McPose;

/**
 * Headless checks for the helicopter flight stack. Run via {@code ./gradlew selfCheckHeli}.
 *
 * <p>Groups are named after the plan's verification section. Present so far:
 * <ul>
 * <li><b>D5</b> — layering: the pure packages import nothing from Minecraft and call no
 *     non-strict transcendental (determinism rests on {@link StrictMath}).</li>
 * <li><b>P7</b> — attitude: {@link McPose} matches the matrix it claims to implement, has the
 *     right signs, and round-trips 10^4 random poses.</li>
 * </ul>
 * Also runs the older {@link HeliStandoffSelfCheck}.
 */
public final class HeliFlightSelfCheck {

    private static final Path PURE_ROOT = Path.of("src/main/java/com/neoalive/tacz_sewv/heli");
    private static final List<String> PURE_PACKAGES = List.of("physics", "control", "guidance", "avoid");
    private static final Pattern NON_STRICT_TRIG = Pattern.compile(
            "(?<!Strict)\\bMath\\.(sin|cos|tan|asin|acos|atan2?|exp|log|log10|pow|cbrt|hypot|expm1|log1p)\\(");

    public static void main(String[] args) throws IOException {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        layering();
        attitude();
        determinism();
        HeliPhysicsChecks.run();
        HeliControlChecks.run();
        HeliGuidanceChecks.run();
        HeliAvoidChecks.run();
        HeliGuidedChecks.run();
        HeliAttackChecks.run();
        HeliStandoffSelfCheck.main(args);

        System.out.println("heli flight self-check: OK (D1-D5, P1-P11, C1-C7, R1-R8, A1-A5, S1-S9, U1-U4, mission, "
                + "attack procedures, mutual avoidance)");
    }

    // --- D1-D3 ----------------------------------------------------------------------------------

    private static void determinism() throws IOException {
        // D1: the same scenario twice is bit-identical, every tick.
        Sim a = scenario(), b = scenario();
        HeliControlChecks.SquareRef ref = new HeliControlChecks.SquareRef(15.0, 100.0);
        for (int i = 0; i < 20 * 60; i++) {
            a.tick(ref);
            b.tick(ref);
            assert bits(a) .equals(bits(b)) : "D1: runs diverged at tick " + i;
        }

        // D2: the order the probe finds obstacles in cannot change the barrier force.
        List<com.neoalive.tacz_sewv.heli.avoid.ObstacleSet.Box> boxes = new java.util.ArrayList<>();
        SplittableRandom rng = new SplittableRandom(0xD2L);
        for (int i = 0; i < 12; i++) {
            double x = rng.nextDouble(-8, 8), y = rng.nextDouble(-8, 8), z = rng.nextDouble(-8, 8);
            boxes.add(new com.neoalive.tacz_sewv.heli.avoid.ObstacleSet.Box(rng.nextLong(), x, y, z, x + 1, y + 1, z + 1, 0, 0, 0));
        }
        List<com.neoalive.tacz_sewv.heli.avoid.ObstacleSet.Box> shuffled = new java.util.ArrayList<>(boxes);
        java.util.Collections.shuffle(shuffled, new java.util.Random(7));
        Vector3d f1 = barrierForce(boxes), f2 = barrierForce(shuffled);
        assert Double.doubleToRawLongBits(f1.x) == Double.doubleToRawLongBits(f2.x)
                && Double.doubleToRawLongBits(f1.y) == Double.doubleToRawLongBits(f2.y)
                && Double.doubleToRawLongBits(f1.z) == Double.doubleToRawLongBits(f2.z)
                : "D2: obstacle order changed the force: " + f1 + " vs " + f2;
        assert f1.lengthSquared() > 0 : "D2: the scenario must actually produce a force";

        // D3: key order inside the data file cannot change a row.
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(Files.readString(Sim.SHIPPED)).getAsJsonObject();
        var plain = com.neoalive.tacz_sewv.heli.data.AirframeData.parse(List.of(java.util.Map.entry("a", root)));
        var reversed = com.neoalive.tacz_sewv.heli.data.AirframeData.parse(
                List.of(java.util.Map.entry("a", reverseKeys(root).getAsJsonObject())));
        assert plain.classes().keySet().equals(reversed.classes().keySet()) : "D3: class set differs";
        for (String k : plain.classes().keySet()) {
            assert plain.classes().get(k).describe().equals(reversed.classes().get(k).describe())
                    : "D3: key order changed class " + k;
        }
    }

    private static Sim scenario() {
        Sim s = new HeliControlChecks.SquareRef(15.0, 100.0).startOnPath(new Sim("light"));
        s.wind.set(3, 0, -2);
        return s;
    }

    private static String bits(Sim s) {
        double[] v = {s.s.p.x, s.s.p.y, s.s.p.z, s.s.v.x, s.s.v.y, s.s.v.z, s.s.q.x, s.s.q.y, s.s.q.z, s.s.q.w,
                s.s.w.x, s.s.w.y, s.s.w.z, s.s.omega, s.s.theta0, s.s.pedal};
        StringBuilder b = new StringBuilder();
        for (double d : v) b.append(Long.toHexString(Double.doubleToRawLongBits(d))).append(',');
        return b.toString();
    }

    private static Vector3d barrierForce(List<com.neoalive.tacz_sewv.heli.avoid.ObstacleSet.Box> boxes) {
        var set = new com.neoalive.tacz_sewv.heli.avoid.ObstacleSet(boxes, -2.0);
        var f = new com.neoalive.tacz_sewv.heli.avoid.AvoidForce(10, 25, 5, 2, 2, 3, 1, 0.05);
        Vector3d p = new Vector3d(0.3, 0.1, -0.2), v = new Vector3d(4, -3, 2);
        f.update(set, p, v);
        return f.force(set, p, v, 1400, new Vector3d());
    }

    private static com.google.gson.JsonElement reverseKeys(com.google.gson.JsonElement e) {
        if (e.isJsonObject()) {
            List<String> keys = new java.util.ArrayList<>(e.getAsJsonObject().keySet());
            java.util.Collections.reverse(keys);
            com.google.gson.JsonObject out = new com.google.gson.JsonObject();
            for (String k : keys) out.add(k, reverseKeys(e.getAsJsonObject().get(k)));
            return out;
        }
        if (e.isJsonArray()) {
            com.google.gson.JsonArray out = new com.google.gson.JsonArray();
            for (com.google.gson.JsonElement x : e.getAsJsonArray()) out.add(reverseKeys(x));
            return out;
        }
        return e;
    }

    // --- D5 -------------------------------------------------------------------------------------

    private static void layering() throws IOException {
        for (String pkg : PURE_PACKAGES) {
            Path dir = PURE_ROOT.resolve(pkg);
            assert Files.isDirectory(dir) : "D5: missing pure package " + dir.toAbsolutePath();
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String src = Files.readString(file);
                    assert !src.contains("import net.minecraft")
                            : "D5: " + file + " imports Minecraft";
                    assert !src.contains("Mth.") : "D5: " + file + " uses vanilla Mth (float LUT)";
                    assert !NON_STRICT_TRIG.matcher(src).find()
                            : "D5: " + file + " uses a non-strict Math transcendental";
                }
            }
        }
    }

    // --- P7 -------------------------------------------------------------------------------------

    private static void attitude() {
        // Signs, straight from the conventions table.
        assertVec(fwd(0, 0, 0), 0, 0, 1, "identity nose is +Z (south)");
        assertVec(fwd(90, 0, 0), -1, 0, 0, "yaw 90 faces west, like vanilla");
        assert fwd(0, 30, 0).y < 0 : "positive pitch must be nose down";
        assert up(0, 0, 30).x < 0 : "positive roll must drop the starboard (-X) side";

        // McPose must equal the explicit matrix R_Y(-yaw) R_X(pitch) R_Z(roll).
        SplittableRandom rng = new SplittableRandom(0x5E57L);
        for (int i = 0; i < 1000; i++) {
            double yaw = rng.nextDouble(-180, 180), pitch = rng.nextDouble(-89, 89), roll = rng.nextDouble(-180, 180);
            double[][] m = mul(mul(rotY(-yaw), rotX(pitch)), rotZ(roll));
            Quaterniond q = McPose.toQuat(yaw, pitch, roll);
            for (int c = 0; c < 3; c++) {
                Vector3d col = q.transform(new Vector3d(c == 0 ? 1 : 0, c == 1 ? 1 : 0, c == 2 ? 1 : 0));
                assertVec(col, m[0][c], m[1][c], m[2][c], "matrix column " + c + " at " + yaw + "/" + pitch + "/" + roll);
            }
        }

        // Round trip over 10^4 poses away from the gimbal: angles -> q -> angles.
        for (int i = 0; i < 10_000; i++) {
            double yaw = rng.nextDouble(-180, 180), pitch = rng.nextDouble(-80, 80), roll = rng.nextDouble(-180, 180);
            Quaterniond q = McPose.toQuat(yaw, pitch, roll);
            assert Math.abs(q.lengthSquared() - 1.0) < 1.0E-12 : "P7: quaternion not unit";
            McPose.Euler e = McPose.toEuler(q, null);
            assert angleClose(e.yawDeg(), yaw) && angleClose(e.pitchDeg(), pitch) && angleClose(e.rollDeg(), roll)
                    : "P7: round trip " + yaw + "/" + pitch + "/" + roll + " -> " + e;
        }

        // Wrap is (-180, 180], matching SBW's baseTick.
        assert McPose.wrapDeg(180.0) == 180.0 && McPose.wrapDeg(-180.0) == 180.0 && McPose.wrapDeg(540.0) == 180.0
                && McPose.wrapDeg(-190.0) == 170.0 : "P7: wrap range";
    }

    private static Vector3d fwd(double yaw, double pitch, double roll) {
        return McPose.toQuat(yaw, pitch, roll).transform(new Vector3d(0, 0, 1));
    }

    private static Vector3d up(double yaw, double pitch, double roll) {
        return McPose.toQuat(yaw, pitch, roll).transform(new Vector3d(0, 1, 0));
    }

    private static boolean angleClose(double a, double b) {
        return Math.abs(McPose.wrapDeg(a - b)) < 1.0E-7;
    }

    private static void assertVec(Vector3d v, double x, double y, double z, String what) {
        assert Math.abs(v.x - x) < 1.0E-12 && Math.abs(v.y - y) < 1.0E-12 && Math.abs(v.z - z) < 1.0E-12
                : "P7: " + what + ": got " + v + ", want (" + x + ", " + y + ", " + z + ")";
    }

    // Hand-written rotation matrices (right-hand rule), independent of the quaternion code.
    private static double[][] rotX(double deg) {
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        return new double[][] {{1, 0, 0}, {0, c, -s}, {0, s, c}};
    }

    private static double[][] rotY(double deg) {
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        return new double[][] {{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
    }

    private static double[][] rotZ(double deg) {
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        return new double[][] {{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                for (int k = 0; k < 3; k++) r[i][j] += a[i][k] * b[k][j];
        return r;
    }
}
