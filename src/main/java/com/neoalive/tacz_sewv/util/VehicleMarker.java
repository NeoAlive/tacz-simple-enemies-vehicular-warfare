package com.neoalive.tacz_sewv.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * One PMC vehicle as the map sees it. Common, not client-only: the server builds these
 * ({@code OwnedVehicleTracker}), the packet carries them, and the client's marker store holds them.
 *
 * <p>{@code driverId} is the unit an order names — SEM commands units, not hulls, and the drive
 * goal runs on the first passenger — while {@code vehicleId} identifies the hull itself.
 */
public record VehicleMarker(int driverId, int vehicleId, double x, double y, double z, float yaw,
                            VehicleMarker.Kind kind, ResourceKey<Level> dimension) {

    /**
     * Coarse vehicle class, mapped from SuperbWarfare's {@code EngineType} server-side. This is the
     * field a NATO Joint Military Symbology set would switch on; the wire carries <b>this</b>
     * ordinal and not SuperbWarfare's, so an upstream enum reorder cannot silently repaint every
     * marker.
     */
    public enum Kind {
        TRACK, WHEEL, SHIP, HELICOPTER, FIXED;

        private static final Kind[] VALUES = values();

        public static Kind byId(int id) {
            return id >= 0 && id < VALUES.length ? VALUES[id] : WHEEL;
        }
    }
}
