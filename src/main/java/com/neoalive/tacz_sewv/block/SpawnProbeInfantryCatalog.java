package com.neoalive.tacz_sewv.block;

import java.util.List;
import java.util.Set;

/**
 * Fixed allow-list of SEM / sewv unit entity ids for the spawn_probe infantry picker.
 * Not a live registry scan — only these (or already-saved) ids are accepted on save.
 */
public final class SpawnProbeInfantryCatalog {

    public static final List<String> IDS = List.of(
            "simpleenemymod:ruunit",
            "simpleenemymod:usunit",
            "simpleenemymod:pmcunit",
            "tacz_sewv:ru_medic",
            "tacz_sewv:us_medic",
            "tacz_sewv:ru_engineer",
            "tacz_sewv:us_engineer",
            "tacz_sewv:ru_combat_engineer",
            "tacz_sewv:us_combat_engineer",
            "tacz_sewv:pmc_commander");

    private static final Set<String> ALLOWED = Set.copyOf(IDS);

    private SpawnProbeInfantryCatalog() {}

    public static boolean isAllowed(String id) {
        return id != null && ALLOWED.contains(id);
    }
}
