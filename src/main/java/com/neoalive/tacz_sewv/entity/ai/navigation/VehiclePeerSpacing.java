package com.neoalive.tacz_sewv.entity.ai.navigation;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Soft standoff between allied / wreck hulls — preference only, never a hard block.
 * Used by {@link GroundVehicleNodeEvaluator} (path cost). The sensor's moving-peer
 * half is RVO ({@link GroundRvo}) over the same {@link #isPeer} set; the path
 * cost here is still a static bubble so A* prefers routes that already leave room.
 */
public final class VehiclePeerSpacing {

    /** Prefer this much clear space beyond contact inflate; routes stay feasible inside it. */
    public static final double SOFT_DISTANCE = 8.0;

    /** Extra path cost at zero separation; falls off linearly to 0 at {@link #SOFT_DISTANCE}. */
    public static final float PATH_PENALTY = 3.0F;

    private VehiclePeerSpacing() {}

    /** Wrecks and allied crewed hulls — same set the sensor feeds RVO. */
    public static boolean isPeer(VehicleEntity self, AbstractUnit crew, VehicleEntity other) {
        if (other == self || !other.isAlive()) return false;
        if (other.isWreck()) return true;
        return other.getFirstPassenger() instanceof AbstractUnit driver
                && VehicleTargeting.isSameFaction(crew, driver);
    }
}
