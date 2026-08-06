package com.neoalive.tacz_sewv.debug;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.init.ModGameRules;
import com.neoalive.tacz_sewv.invasion.InvasionSession;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModFileInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * Read-only support dump for {@code /sewv debug dump}. Writes a plain-text report under
 * {@code logs/}; never mutates configs or gamerules.
 */
public final class SewvDebugDump {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String[] SOFT_DEPS = {
            "berezka_api", "xaeroworldmap", "openpartiesandclaims", "fcp", "mcsp", "ashvehicle"
    };

    private SewvDebugDump() {}

    /** Writes the report and returns its absolute path. */
    public static Path write(MinecraftServer server, ServerLevel level) throws IOException {
        Path logs = FMLPaths.getOrCreateGameRelativePath(Path.of("logs"));
        Path out = logs.resolve("sewv-debug-dump-" + LocalDateTime.now().format(STAMP) + ".txt");
        String report = build(server, level);
        Files.writeString(out, report, StandardCharsets.UTF_8);
        return out.toAbsolutePath().normalize();
    }

    static String build(MinecraftServer server, ServerLevel level) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("=== SEWV debug dump ===\n");
        sb.append("generated=").append(LocalDateTime.now()).append('\n');
        sb.append('\n');

        appendBuild(sb);
        appendConfigResolution(sb, server);
        appendLiveEventKnobs(sb);
        appendGamerules(sb, level);
        appendStaleArtifacts(sb, server);
        appendFeatureFlags(sb, level);
        appendSoftDeps(sb);
        return sb.toString();
    }

    private static void appendBuild(StringBuilder sb) {
        sb.append("--- Build identity ---\n");
        String version = "(unknown)";
        Path jarPath = null;
        try {
            version = ModList.get().getModContainerById(TaczSewv.MODID)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("(missing)");
            IModFileInfo fileInfo = ModList.get().getModFileById(TaczSewv.MODID);
            if (fileInfo != null) {
                jarPath = fileInfo.getFile().getFilePath();
            }
        } catch (RuntimeException e) {
            sb.append("error=").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
        }
        sb.append("mod_id=").append(TaczSewv.MODID).append('\n');
        sb.append("mod_version=").append(version).append('\n');
        sb.append("jar_path=").append(jarPath != null ? jarPath.toAbsolutePath().normalize() : "(unavailable)").append('\n');
        if (jarPath != null && Files.isRegularFile(jarPath)) {
            sb.append("jar_sha256=").append(sha256(jarPath)).append('\n');
        } else {
            sb.append("jar_sha256=skipped (exploded/dev or missing file)\n");
        }
        sb.append('\n');
    }

    private static void appendConfigResolution(StringBuilder sb, MinecraftServer server) {
        sb.append("--- Config resolution ---\n");
        sb.append("spec_type=SERVER\n");
        sb.append("spec_loaded=").append(SewvConfig.SPEC.isLoaded()).append('\n');
        sb.append("global_config_dir=").append(FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize()).append('\n');
        sb.append("note=Users often edit global config/; SewvConfig SERVER loads from per-world serverconfig/ only.\n");

        Path convention = server.getWorldPath(new LevelResource("serverconfig"))
                .resolve(TaczSewv.MODID + "-server.toml")
                .toAbsolutePath().normalize();
        sb.append("serverconfig_convention_path=").append(convention).append('\n');
        sb.append("serverconfig_exists=").append(Files.isRegularFile(convention)).append('\n');
        sb.append("serverconfig_readable=").append(Files.isReadable(convention)).append('\n');

        Path forgeResolved = resolveForgeServerConfigPath();
        if (forgeResolved != null) {
            sb.append("serverconfig_forge_full_path=").append(forgeResolved.toAbsolutePath().normalize()).append('\n');
        } else {
            sb.append("serverconfig_forge_full_path=(unavailable — use convention path)\n");
        }
        sb.append('\n');
    }

    private static void appendLiveEventKnobs(StringBuilder sb) {
        sb.append("--- Live event / spawn knobs (SewvConfig.*.get) ---\n");
        if (!SewvConfig.SPEC.isLoaded()) {
            sb.append("error=SPEC not loaded\n\n");
            return;
        }
        sb.append("tankSpawnChanceRu=").append(SewvConfig.TANK_SPAWN_CHANCE_RU.get()).append('\n');
        sb.append("tankSpawnChanceUs=").append(SewvConfig.TANK_SPAWN_CHANCE_US.get()).append('\n');
        sb.append("planesInEvents=").append(SewvConfig.PLANES_IN_EVENTS.get()).append('\n');
        sb.append("planeSpawnChanceRu=").append(SewvConfig.PLANE_SPAWN_CHANCE_RU.get()).append('\n');
        sb.append("planeSpawnChanceUs=").append(SewvConfig.PLANE_SPAWN_CHANCE_US.get()).append('\n');
        sb.append("convoyEventsEnabled=").append(SewvConfig.CONVOY_EVENTS_ENABLED.get()).append('\n');
        sb.append("convoyBaseChance=").append(SewvConfig.CONVOY_BASE_CHANCE.get()).append('\n');
        sb.append("largeCombatEventsEnabled=").append(SewvConfig.LARGE_COMBAT_EVENTS_ENABLED.get()).append('\n');
        sb.append("largeCombatBaseChance=").append(SewvConfig.LARGE_COMBAT_BASE_CHANCE.get()).append('\n');
        sb.append("navalEventsEnabled=").append(SewvConfig.NAVAL_EVENTS_ENABLED.get()).append('\n');
        sb.append("navalBaseChance=").append(SewvConfig.NAVAL_BASE_CHANCE.get()).append('\n');
        sb.append("invasionEventsEnabled=").append(SewvConfig.INVASION_EVENTS_ENABLED.get()).append('\n');
        sb.append("invasionBaseChance=").append(SewvConfig.INVASION_BASE_CHANCE.get()).append('\n');
        sb.append("shellingEventsEnabled=").append(SewvConfig.SHELLING_EVENTS_ENABLED.get()).append('\n');
        sb.append("shellingBaseChance=").append(SewvConfig.SHELLING_BASE_CHANCE.get()).append('\n');
        sb.append("derelictEventsEnabled=").append(SewvConfig.DERELICT_EVENTS_ENABLED.get()).append('\n');
        sb.append("derelictBaseChance=").append(SewvConfig.DERELICT_BASE_CHANCE.get()).append('\n');
        sb.append("garrisonVehiclesEnabled=").append(SewvConfig.GARRISON_VEHICLES_ENABLED.get()).append('\n');
        sb.append("garrisonVehicleChance=").append(SewvConfig.GARRISON_VEHICLE_CHANCE.get()).append('\n');
        sb.append('\n');
    }

    private static void appendGamerules(StringBuilder sb, ServerLevel level) {
        sb.append("--- Gamerules (spawn gates) ---\n");
        sb.append("sewvRuSpawns=").append(level.getGameRules().getBoolean(ModGameRules.RU_SPAWNS)).append('\n');
        sb.append("sewvUsSpawns=").append(level.getGameRules().getBoolean(ModGameRules.US_SPAWNS)).append('\n');
        sb.append("sewvPmcAmbientSpawns=").append(level.getGameRules().getBoolean(ModGameRules.PMC_AMBIENT_SPAWNS)).append('\n');
        sb.append("sewvTanksInEvents=").append(level.getGameRules().getBoolean(ModGameRules.TANKS_IN_EVENTS)).append('\n');
        if (ModGameRules.INVASION_OVERRIDES != null) {
            sb.append("sewvInvasionOverrides=")
                    .append(level.getGameRules().getBoolean(ModGameRules.INVASION_OVERRIDES))
                    .append('\n');
        }
        sb.append("note=Former toml [spawn_gates] / tanksInEvents — toggle with /gamerule, not config.\n");
        sb.append('\n');
    }

    private static void appendStaleArtifacts(StringBuilder sb, MinecraftServer server) {
        sb.append("--- Stale / misleading artifacts ---\n");
        Path configDir = FMLPaths.CONFIGDIR.get();
        Path orphanCommon = configDir.resolve("tacz_sewv-common.toml");
        boolean orphanPresent = Files.isRegularFile(orphanCommon);
        sb.append("orphan_common_toml=").append(orphanPresent);
        if (orphanPresent) {
            sb.append(" path=").append(orphanCommon.toAbsolutePath().normalize());
        }
        sb.append('\n');
        if (orphanPresent) {
            sb.append("WARNING: tacz_sewv-common.toml is NOT loaded for live SewvConfig ")
                    .append("(SERVER loads from per-world serverconfig/). ")
                    .append("If you relied on it for vehicle-pool seeding in NEW worlds, use /sewv pool instead. ")
                    .append("Existing worlds are unaffected (pools already seeded to per-world data). ")
                    .append("Run /sewv debug StartConfigFix to quarantine this file.\n");
        }

        Path misplacedServer = configDir.resolve("tacz_sewv-server.toml");
        sb.append("misplaced_global_server_toml=").append(Files.isRegularFile(misplacedServer));
        if (Files.isRegularFile(misplacedServer)) {
            sb.append(" path=").append(misplacedServer.toAbsolutePath().normalize());
        }
        sb.append('\n');

        sb.append("config_dir_sewv_backups:\n");
        boolean anyBak = false;
        if (Files.isDirectory(configDir)) {
            try (Stream<Path> stream = Files.list(configDir)) {
                for (Path p : stream.filter(SewvConfigFix::isQuarantineCandidate).sorted().toList()) {
                    if (p.getFileName().toString().equals("tacz_sewv-common.toml")
                            || p.getFileName().toString().equals("tacz_sewv-server.toml")) {
                        continue; // already reported above
                    }
                    anyBak = true;
                    sb.append("  ").append(p.getFileName()).append('\n');
                }
            } catch (IOException e) {
                sb.append("  error listing: ").append(e.getMessage()).append('\n');
            }
        }
        if (!anyBak) {
            sb.append("  (none)\n");
        }

        Path liveServer = server.getWorldPath(new LevelResource("serverconfig"))
                .resolve(TaczSewv.MODID + "-server.toml");
        boolean spawnGates = false;
        if (Files.isRegularFile(liveServer)) {
            try {
                String text = Files.readString(liveServer, StandardCharsets.UTF_8);
                spawnGates = text.contains("[spawn_gates]") || text.contains("ruSpawnsEnabled");
            } catch (IOException e) {
                sb.append("spawn_gates_in_live_serverconfig=error: ").append(e.getMessage()).append('\n');
            }
        }
        sb.append("spawn_gates_in_live_serverconfig=").append(spawnGates)
                .append(" (dead section — gamerules replaced it; dump-only, StartConfigFix will not rewrite live serverconfig)\n");
        sb.append('\n');
    }

    private static void appendFeatureFlags(StringBuilder sb, ServerLevel level) {
        sb.append("--- Feature flags (curated) ---\n");
        if (!SewvConfig.SPEC.isLoaded()) {
            sb.append("error=SPEC not loaded\n\n");
            return;
        }
        sb.append("autoBoardEnabled=").append(SewvConfig.AUTO_BOARD_ENABLED.get()).append('\n');
        sb.append("structureVehiclesEnabled=").append(SewvConfig.STRUCTURE_VEHICLES_ENABLED.get()).append('\n');
        sb.append("ifvDismountsEnabled=").append(SewvConfig.IFV_DISMOUNTS_ENABLED.get()).append('\n');
        sb.append("tankRiderDismountEnabled=").append(SewvConfig.TANK_RIDER_DISMOUNT_ENABLED.get()).append('\n');
        sb.append("npcArmorEnabled=").append(SewvConfig.NPC_ARMOR_ENABLED.get()).append('\n');
        sb.append("invasionSessionActive=").append(InvasionSession.isActive(level)).append('\n');
        sb.append('\n');
    }

    private static void appendSoftDeps(StringBuilder sb) {
        sb.append("--- Soft dependencies ---\n");
        for (String id : SOFT_DEPS) {
            sb.append(id).append("_loaded=").append(ModList.get().isLoaded(id)).append('\n');
        }
        sb.append('\n');
    }

    /** Forge-resolved path when the SERVER config is a real file config; otherwise null. */
    private static Path resolveForgeServerConfigPath() {
        try {
            ModConfig cfg = ConfigTracker.INSTANCE.fileMap().get(TaczSewv.MODID + "-server.toml");
            if (cfg == null) return null;
            CommentedConfig data = cfg.getConfigData();
            if (!(data instanceof CommentedFileConfig)) return null;
            return cfg.getFullPath();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            try (var in = Files.newInputStream(file)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    digest.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "error:" + e.getClass().getSimpleName();
        }
    }
}
