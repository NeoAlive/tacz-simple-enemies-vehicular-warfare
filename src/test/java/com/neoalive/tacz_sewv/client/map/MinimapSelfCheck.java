package com.neoalive.tacz_sewv.client.map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Headless checks for the shared map-drawing layer and the minimap compat. Run with
 * {@code ./gradlew selfCheckMinimap}.
 *
 * <ol>
 *   <li>{@link MinimapMath}: the heading offset read back out of Xaero's {@code ps}/{@code pc} is 0
 *       when north-locked, matches the transform's own rotation, and makes a unit facing where the
 *       camera faces point straight up on a rotating map.</li>
 *   <li>The two projections: the minimap's degenerates to the World Map's when north-locked, and the
 *       World Map's culling is exactly the ±16 px rule it replaced.</li>
 *   <li>Isolation: Minimap types ({@code xaero.common}/{@code xaero.hud}) are named only in
 *       {@code client/xaerominimap} and the {@code MixinMinimap*} mixins, and nothing in
 *       {@code client/map} names any Xaero type — a slip is a {@code NoClassDefFoundError} on every
 *       install without that mod.</li>
 * </ol>
 */
public final class MinimapSelfCheck {

    private static final double EPS = 1e-6;

    public static void main(String[] args) throws IOException {
        bearing();
        projections();
        isolation();
        System.out.println("MinimapSelfCheck OK");
    }

    /** Xaero's ps/pc for a render angle in degrees (MinimapRenderer: sin/cos of π − angle). */
    private static double ps(double renderAngleDeg) {
        return Math.sin(Math.PI - Math.toRadians(renderAngleDeg));
    }

    private static double pc(double renderAngleDeg) {
        return Math.cos(Math.PI - Math.toRadians(renderAngleDeg));
    }

    private static void bearing() {
        // North-locked: Xaero's render angle is 90.
        check(Math.abs(MinimapMath.bearingOffsetDeg(ps(90), pc(90))) < EPS, "north-locked offset is 0");

        for (double camYaw = -180; camYaw <= 180; camYaw += 15) {
            // Unlocked: render angle = -90 - camera yaw.
            double angle = -90 - camYaw;
            double s = ps(angle), c = pc(angle);
            float offset = MinimapMath.bearingOffsetDeg(s, c);

            // The offset is the screen bearing of world north under the transform itself.
            double nx = MinimapMath.projectX(s, c, 1, 0, -1);
            double ny = MinimapMath.projectY(s, c, 1, 0, -1);
            check(angleDiff(Math.toDegrees(Math.atan2(nx, -ny)), offset) < 1e-3,
                    "offset = projected north bearing at camYaw " + camYaw);

            // A unit facing the camera's way points up: painter rotation is yaw + 180 + offset.
            check(angleDiff(camYaw + 180 + offset, 0) < 1e-3, "facing-forward heading points up at camYaw " + camYaw);

            // The transform is a rotation (no mirror), so one offset is right for every bearing.
            check(Math.abs(s * s + c * c - 1) < EPS, "ps/pc form a rotation");
        }
    }

    private static void projections() {
        // North-locked minimap = flat north-up map centred on the origin.
        RotatedMapProjection rot = new RotatedMapProjection(ps(90), pc(90), 2.0, 100, -50, 128);
        FlatMapProjection flat = new FlatMapProjection(100, -50, 2.0, 0, 0);
        for (int dx = -40; dx <= 40; dx += 8) {
            for (int dz = -40; dz <= 40; dz += 8) {
                int[] a = rot.toScreen(100 + dx, -50 + dz);
                int[] b = flat.toScreen(100 + dx, -50 + dz);
                check(a[0] == b[0] && a[1] == b[1], "north-locked minimap matches flat map at " + dx + "," + dz);
            }
        }
        int[] centre = rot.toScreen(100, -50);
        check(centre[0] == 0 && centre[1] == 0, "minimap origin is the framebuffer centre");
        check(Math.abs(rot.lengthPx(10) - 20) < EPS, "minimap length scales by zoom");
        check(rot.onScreen(new int[]{-128 - 16, 0}) && !rot.onScreen(new int[]{-128 - 17, 0}),
                "minimap culls at the centred half-view plus slack");

        // World Map: the old onScreen rule was -16 <= p <= size + 16 on both axes.
        FlatMapProjection screen = new FlatMapProjection(0, 0, 1.0, 320, 240);
        for (int x = -40; x <= 360; x += 3) {
            for (int y = -40; y <= 280; y += 3) {
                boolean old = x >= -16 && x <= 320 + 16 && y >= -16 && y <= 240 + 16;
                check(screen.onScreen(new int[]{x, y}) == old, "world map culling unchanged at " + x + "," + y);
            }
        }
        int[] mid = screen.toScreen(0, 0);
        check(mid[0] == 160 && mid[1] == 120, "world map camera is the screen centre");

        check(MarkerStyle.WORLD_MAP.equals(new MarkerStyle(1.0F, true, true)), "World Map style is today's look");
    }

    private static void isolation() throws IOException {
        Path root = Path.of("src/main/java");
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("expected src/main/java relative to cwd, got " + Path.of("").toAbsolutePath());
        }
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String unix = p.toString().replace('\\', '/');
                boolean minimapHome = unix.contains("/client/xaerominimap/")
                        || p.getFileName().toString().startsWith("MixinMinimap");
                boolean shared = unix.contains("/client/map/");
                try {
                    for (String line : Files.readAllLines(p)) {
                        String t = line.trim();
                        if (!t.startsWith("import ")) continue;
                        boolean minimapType = t.startsWith("import xaero.common.") || t.startsWith("import xaero.hud.");
                        if (minimapType && !minimapHome) hits.add(unix + ": " + t);
                        if (shared && t.startsWith("import xaero.")) hits.add(unix + ": " + t);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        if (!hits.isEmpty()) {
            throw new AssertionError("Xaero types outside their compat home:\n  " + String.join("\n  ", hits));
        }
    }

    private static double angleDiff(double a, double b) {
        double d = ((a - b) % 360 + 540) % 360 - 180;
        return Math.abs(d);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
