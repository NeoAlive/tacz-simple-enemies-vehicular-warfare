package com.neoalive.tacz_sewv.compat.lv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.neoalive.tacz_sewv.compat.lv.LvSteering.Keys;
import com.neoalive.tacz_sewv.compat.lv.LvSteering.Kind;

/**
 * Headless checks for the Limitless Vehicles layer. Run with {@code ./gradlew selfCheck}.
 *
 * <ol>
 *   <li>{@link LvSteering}: SBW key semantics translate to LV's exactly as its doc says.</li>
 *   <li>The isolation rule: {@code org.ywzj.*} is imported only under {@code compat/lv} (there
 *       are no mixins into LV — see {@code LvEvents}), and never by the classes shared code or the event bus can load without LV
 *       (the facade, the bus subscribers, the goal and command wiring, the pure steering table).
 *       A slip there is a {@code NoClassDefFoundError} on every install without LV.</li>
 * </ol>
 */
public final class LvSelfCheck {

    /** Loadable with LV absent — must not name an LV type. */
    private static final Set<String> LV_FREE = Set.of(
            "LvCompat.java", "LvEvents.java", "LvPendingCrews.java", "LvSteering.java",
            "LvGoals.java", "LvCommands.java");

    public static void main(String[] args) throws IOException {
        steering();
        isolation();
        System.out.println("LvSelfCheck OK");
    }

    private static void steering() {
        // Tracked: SBW inverts on the BACK key, LV on real motion astern — swap when they differ.
        check(Kind.TRACKED, true, false, true, false, 0.3, new Keys(true, false, true, false, false),
                "tracked ahead: keys pass through");
        check(Kind.TRACKED, false, true, true, false, -0.3, new Keys(false, true, true, false, false),
                "tracked under way astern with back held: both invert, pass through");
        check(Kind.TRACKED, false, true, true, false, 0.3, new Keys(false, true, false, true, false),
                "tracked back held but still rolling ahead: SBW inverts, LV not yet — swap");
        check(Kind.TRACKED, false, false, true, false, -0.3, new Keys(false, false, false, true, false),
                "tracked coasting astern with no back key: LV inverts, SBW does not — swap");
        check(Kind.TRACKED, false, false, true, false, 0.0, new Keys(false, false, true, false, false),
                "tracked pivot at rest: pass through");
        // Wheeled: both invert with real motion; an all-released frame pulls the handbrake.
        check(Kind.WHEELED, false, true, false, true, -0.3, new Keys(false, true, false, true, false),
                "wheeled reversing: pass through, no handbrake");
        check(Kind.WHEELED, false, false, false, false, 0.5, new Keys(false, false, false, false, true),
                "wheeled stop: handbrake on");
        // Vessel: pass through, never a handbrake.
        check(Kind.VESSEL, false, false, false, false, 0.5, new Keys(false, false, false, false, false),
                "vessel stop: no handbrake");
        check(Kind.VESSEL, false, true, false, true, -0.2, new Keys(false, true, false, true, false),
                "vessel astern: pass through");
    }

    private static void check(Kind kind, boolean f, boolean b, boolean l, boolean r, double speed,
                              Keys expected, String what) {
        Keys got = LvSteering.translate(kind, f, b, l, r, speed);
        if (!got.equals(expected)) {
            throw new AssertionError(what + ": expected " + expected + " got " + got);
        }
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
                boolean lvPackage = unix.contains("/compat/lv/");
                boolean mustBeFree = !lvPackage || LV_FREE.contains(p.getFileName().toString());
                if (!mustBeFree) return;
                try {
                    // Comments documenting the rule may name the package; code may not.
                    for (String line : Files.readString(p, StandardCharsets.UTF_8).split("\n")) {
                        String t = line.trim();
                        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
                        if (t.contains("org.ywzj.")) {
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
            throw new AssertionError("org.ywzj.* referenced where LV may be absent: " + hits);
        }
    }
}
