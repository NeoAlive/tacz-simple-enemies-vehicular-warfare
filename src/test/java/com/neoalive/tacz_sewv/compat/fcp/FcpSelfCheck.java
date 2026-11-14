package com.neoalive.tacz_sewv.compat.fcp;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import com.neoalive.tacz_sewv.mixin.fcp.FcpMixinPlugin;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.VehiclePackPresets;
import com.neoalive.tacz_sewv.util.VehiclePackPresets.Pack;
import com.neoalive.tacz_sewv.util.WorldVehiclePools.Category;

/**
 * Headless checks for the FCP 2.0 layer. Run with {@code ./gradlew selfCheckFcp}.
 * frontline.* may only be named by FcpHulls: everything else loads on installs without FCP.
 */
public final class FcpSelfCheck {

    public static void main(String[] args) throws Exception {
        versions();
        roster();
        isolation();
        System.out.println("FcpSelfCheck OK");
    }

    private static void versions() throws Exception {
        check(!FcpCompat.isSupportedVersion(new DefaultArtifactVersion("1.2.1")), "1.2.1 refused");
        check(!FcpCompat.isSupportedVersion(new DefaultArtifactVersion("1.9.9")), "1.9.9 refused");
        check(FcpCompat.isSupportedVersion(new DefaultArtifactVersion("2.0.0")), "2.0.0 accepted");
        check(FcpCompat.isSupportedVersion(new DefaultArtifactVersion("2.1.3")), "2.1.3 accepted");
        check(!FcpCompat.isSupportedVersion(null), "null refused");
        Field f = FcpMixinPlugin.class.getDeclaredField("MIN");
        f.setAccessible(true);
        ArtifactVersion pluginMin = (ArtifactVersion) f.get(null);
        check(pluginMin.compareTo(FcpCompat.MIN_VERSION) == 0, "mixin plugin floor matches FcpCompat");
    }

    private static void roster() {
        Map<TankFaction, Map<Category, List<String>>> t = VehiclePackPresets.tables(Pack.FCP);
        for (Map<Category, List<String>> byCat : t.values()) {
            for (List<String> ids : byCat.values()) {
                check(!ids.contains("fcp:huey_rockets"), "removed huey_rockets absent");
            }
        }
        check(t.get(TankFaction.RU).get(Category.GROUND).contains("fcp:t90m"), "RU t90m");
        check(t.get(TankFaction.US).get(Category.GROUND).contains("fcp:m1a2"), "US m1a2");
        check(t.get(TankFaction.US).get(Category.ROTOR).contains("fcp:ch47"), "US ch47");
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
                if (unix.endsWith("/compat/fcp/FcpHulls.java")) return;
                try {
                    for (String line : Files.readString(p, StandardCharsets.UTF_8).split("\n")) {
                        String t = line.trim();
                        if (t.startsWith("import frontline.") || t.startsWith("import static frontline.")) {
                            hits.add(unix);
                            break;
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        if (!hits.isEmpty()) {
            throw new AssertionError("frontline.* imported where FCP may be absent: " + hits);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FcpSelfCheck failed: " + what);
    }
}
