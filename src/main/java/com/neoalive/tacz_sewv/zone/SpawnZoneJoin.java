package com.neoalive.tacz_sewv.zone;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Last-resort veto for units that reach the level by a path no spawner gate saw: spawn eggs,
 * structure templates, SEM's own spawns, other mods. Only a first join is judged. A unit loaded
 * from disk, changing dimension, or riding a hull that already joined always passes, and every
 * admitted unit is stamped so it is never judged again.
 */
public final class SpawnZoneJoin {

    private static final String ADMITTED = "sewv:zone_admitted";

    private SpawnZoneJoin() {}

    /** True if the join must be cancelled. Stamps the unit when it is let in. */
    public static boolean refuses(ServerLevel level, Entity unit, boolean loadedFromDisk) {
        var data = unit.getPersistentData();
        if (data.getBoolean(ADMITTED)) return false;
        if (!loadedFromDisk && unit.getVehicle() == null && !SpawnZones.allows(level, unit.blockPosition())) {
            return true;
        }
        data.putBoolean(ADMITTED, true);
        return false;
    }
}
