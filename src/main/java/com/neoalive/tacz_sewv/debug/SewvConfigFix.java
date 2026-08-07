package com.neoalive.tacz_sewv.debug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Quarantine-only cleanup for {@code /sewv debug StartConfigFix}.
 * Moves known-stale files under global {@code config/} to recoverable {@code .ignored-*} names.
 * Never touches per-world {@code serverconfig/} or any file Forge currently loads for SewvConfig.
 */
public final class SewvConfigFix {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public record Move(Path from, Path to, String reason) {}

    public record Result(List<Move> moved, List<String> notes) {}

    private SewvConfigFix() {}

    public static Result quarantine() throws IOException {
        Path configDir = FMLPaths.CONFIGDIR.get();
        List<Move> moved = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        String stamp = LocalDateTime.now().format(STAMP);

        if (!Files.isDirectory(configDir)) {
            notes.add("config dir missing: " + configDir.toAbsolutePath());
            return new Result(moved, notes);
        }

        List<Path> candidates;
        try (Stream<Path> stream = Files.list(configDir)) {
            candidates = stream.filter(SewvConfigFix::isQuarantineCandidate).sorted().toList();
        }

        for (Path from : candidates) {
            if (!isUnderConfigDir(from, configDir)) {
                notes.add("SKIP (not under config/): " + from);
                continue;
            }
            if (looksLikeServerConfig(from)) {
                notes.add("SKIP (refuses serverconfig path): " + from);
                continue;
            }

            String reason = reasonFor(from.getFileName().toString());
            Path to = from.resolveSibling(from.getFileName() + ".ignored-" + stamp);
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFail) {
                Files.move(from, to);
            }
            moved.add(new Move(from.toAbsolutePath().normalize(), to.toAbsolutePath().normalize(), reason));

            if (from.getFileName().toString().equals("tacz_sewv-common.toml")) {
                notes.add("Quarantined tacz_sewv-common.toml — this file is NOT loaded for live config. "
                        + "If you relied on it for vehicle-pool seeding in NEW worlds, use /sewv pool instead. "
                        + "Existing worlds are unaffected (pools already seeded to per-world data).");
            }
        }

        if (moved.isEmpty() && notes.isEmpty()) {
            notes.add("Nothing to quarantine under " + configDir.toAbsolutePath().normalize());
        }
        notes.add("Live world serverconfig was not touched (including any dead [spawn_gates] section).");
        return new Result(moved, notes);
    }

    /**
     * Files under global config/ that SewvConfig never loads as SERVER, or backups of the orphan common.
     * Does not include client.toml or the vehicle_skins directory.
     */
    static boolean isQuarantineCandidate(Path path) {
        if (!Files.isRegularFile(path)) return false;
        String name = path.getFileName().toString();
        if (name.equals("tacz_sewv-client.toml")) return false;
        if (name.contains(".ignored-")) return false; // already quarantined
        if (name.equals("tacz_sewv-common.toml")) return true;
        if (name.equals("tacz_sewv-server.toml")) return true; // misplaced in global config/
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("tacz_sewv")) return false;
        // Backups: tacz_sewv-common-1.toml.bak, tacz_sewv-common.toml.bak
        return lower.contains("common") && (lower.endsWith(".bak") || lower.contains(".toml.bak"));
    }

    private static String reasonFor(String name) {
        if (name.equals("tacz_sewv-common.toml")) {
            return "orphan COMMON toml — SewvConfig is SERVER; file is not loaded for live knobs "
                    + "(legacy vehicle-pool seed only on first WorldVehiclePools create)";
        }
        if (name.equals("tacz_sewv-server.toml")) {
            return "misplaced SERVER toml in global config/ — live file is per-world serverconfig/";
        }
        return "stale SEWV backup / leftover under config/";
    }

    private static boolean isUnderConfigDir(Path path, Path configDir) {
        Path abs = path.toAbsolutePath().normalize();
        Path root = configDir.toAbsolutePath().normalize();
        return abs.startsWith(root) && abs.getNameCount() == root.getNameCount() + 1;
    }

    /** Extra belt: never rename anything whose path contains a serverconfig segment. */
    private static boolean looksLikeServerConfig(Path path) {
        for (Path part : path.toAbsolutePath().normalize()) {
            if (part.toString().equals("serverconfig")) return true;
        }
        return false;
    }
}
