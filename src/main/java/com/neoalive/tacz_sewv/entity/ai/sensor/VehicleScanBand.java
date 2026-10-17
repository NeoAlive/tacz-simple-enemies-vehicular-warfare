package com.neoalive.tacz_sewv.entity.ai.sensor;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.level.levelgen.Heightmap;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;

/**
 * Vertical half of the vehicle target-scan cylinder. Horizontal radius stays elsewhere;
 * this only answers how far above / below an observer may still see a subject.
 *
 * <p><b>Ground observers</b> get a taller upward reach ({@link SewvConfig#VEHICLE_TARGET_SCAN_AIR_HEIGHT})
 * so AA / SPAA can lock aircraft. <b>Airborne observers</b> keep the symmetric
 * {@link SewvConfig#VEHICLE_TARGET_SCAN_HEIGHT} band (plus existing downward altitude slack) —
 * pinning their floor to the ground is deliberately not done here (breaks non-CAS planes).
 */
public final class VehicleScanBand {

    private VehicleScanBand() {}

    /** Half of {@code vehicleTargetScanHeight}: downward for everyone, upward for air. */
    public static double halfHeight() {
        try {
            return SewvConfig.VEHICLE_TARGET_SCAN_HEIGHT.get() / 2.0;
        } catch (Throwable unbaked) {
            return 64.0;
        }
    }

    /**
     * Upward reach for a <em>ground</em> observer (tanks, AA, emplacements). Never smaller than
     * {@link #halfHeight()} so a misconfigured air height cannot shrink the old band.
     */
    public static double groundUpward() {
        double half = halfHeight();
        try {
            return Math.max(half, SewvConfig.VEHICLE_TARGET_SCAN_AIR_HEIGHT.get());
        } catch (Throwable unbaked) {
            return Math.max(half, 192.0);
        }
    }

    /** True when this hull uses the air scan band (heli / plane), including when parked. */
    public static boolean isAirObserver(VehicleEntity v) {
        EngineType type = HullFacts.engineType(v);
        return type == EngineType.HELICOPTER || type == EngineType.AIRCRAFT;
    }

    /**
     * How far above the observer a subject may sit. Air keeps {@link #halfHeight()}; ground uses
     * {@link #groundUpward()}.
     */
    public static double upward(VehicleEntity v) {
        return isAirObserver(v) ? halfHeight() : groundUpward();
    }

    /**
     * How far below the observer a subject may sit: half-height plus air altitude slack
     * (capped in {@link #altitudeSlack} the same way {@link HullLocalScan} always has).
     */
    public static double downward(VehicleEntity v) {
        return halfHeight() + altitudeSlack(v);
    }

    /**
     * Extra downward reach for flying vehicles: height above the terrain surface, capped so a
     * high cruise does not inflate the close-scan list unboundedly. Zero for ground hulls.
     */
    public static double altitudeSlack(VehicleEntity v) {
        if (!isAirObserver(v)) return 0.0;
        int surface = v.level().getHeight(Heightmap.Types.WORLD_SURFACE, v.getBlockX(), v.getBlockZ());
        return Math.min(64.0, Math.max(0.0, v.getY() - surface));
    }

    /**
     * Subject inside the observer's vertical band. {@code observerSlack > 0} means an airborne
     * observer (from {@link CombatantIndex.Entry#altitudeSlack}): upward stays {@code halfHeight}.
     * Ground observers ({@code observerSlack == 0}) use {@code groundUpward}.
     */
    public static boolean inBand(double observerY, double observerSlack, double subjectY,
                                 double halfHeight, double groundUpward) {
        double dy = subjectY - observerY;
        double up = observerSlack > 0.0 ? halfHeight : groundUpward;
        return dy <= up && -dy <= halfHeight + observerSlack;
    }

    public static boolean inBand(VehicleEntity observer, double subjectY) {
        double dy = subjectY - observer.getY();
        return dy <= upward(observer) && -dy <= downward(observer);
    }
}
