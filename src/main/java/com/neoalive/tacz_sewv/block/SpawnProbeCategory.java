package com.neoalive.tacz_sewv.block;

/** Exclusive spawn_probe payload mode — vehicle pool XOR infantry rows. */
public enum SpawnProbeCategory {
    VEHICLE,
    INFANTRY;

    public static SpawnProbeCategory parse(String raw) {
        if (raw == null || raw.isEmpty()) return VEHICLE;
        try {
            return SpawnProbeCategory.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return VEHICLE;
        }
    }
}
