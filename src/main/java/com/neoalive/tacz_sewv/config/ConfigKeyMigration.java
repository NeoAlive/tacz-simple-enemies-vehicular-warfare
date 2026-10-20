package com.neoalive.tacz_sewv.config;

import java.util.LinkedHashMap;
import java.util.Map;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Carries renamed {@link SewvConfig} keys over to their new names in an existing toml.
 *
 * <p>Forge's {@code ModConfigSpec} <em>removes</em> every key its spec no longer names the moment
 * it corrects a loaded file, so a plain rename silently resets an edited value to the new default.
 * {@code MixinForgeConfigSpec} calls {@link #apply} on the raw config just before that correction,
 * which moves each old value under its new path; the correction then keeps it. A key is moved only
 * when the new path is absent, so an install that already has the new key is never overwritten.
 *
 * <p>Add a line here for every renamed key; never reuse an old path for a different setting.
 */
public final class ConfigKeyMigration {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Old dotted path → new dotted path. */
    private static final Map<String, String> RENAMES = new LinkedHashMap<>();

    static {
        // tank → ground, ship → surface (pool and event naming).
        RENAMES.put("events.tankSpawnChanceRu", "events.groundSpawnChanceRu");
        RENAMES.put("events.tankSpawnChanceUs", "events.groundSpawnChanceUs");
        RENAMES.put("events.largeCombatVehicles", "events.largeCombatGroundVehicles");
        RENAMES.put("events.navalShipsPerSide", "events.navalSurfacePerSide");
        RENAMES.put("balancing.easyNoFarCombatTanks", "balancing.easyNoFarCombatVehicles");
    }

    private ConfigKeyMigration() {}

    /** Moves renamed keys in place. Returns how many it moved. */
    public static int apply(CommentedConfig config) {
        int moved = 0;
        for (Map.Entry<String, String> e : RENAMES.entrySet()) {
            String from = e.getKey();
            String to = e.getValue();
            if (!config.contains(from)) continue;
            Object value = config.get(from);
            config.remove(from);
            if (config.contains(to)) continue; // the new key was already set; the old one is just dropped
            config.set(to, value);
            moved++;
            LOGGER.info("[sewv] config key '{}' renamed to '{}' (value {} kept)", from, to, value);
        }
        return moved;
    }

    /** The rename table, for the self-check. */
    public static Map<String, String> renames() {
        return Map.copyOf(RENAMES);
    }
}
