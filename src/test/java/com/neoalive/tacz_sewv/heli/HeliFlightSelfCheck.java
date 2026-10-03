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
        HeliStandoffSelfCheck.main(args);

        System.out.println("heli flight self-check: OK (D5, P7)");
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
