package com.neoalive.tacz_sewv.entity.ai.navigation;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Soft standoff between allied / wreck hulls — preference only, never a hard block.
 * Used by {@link GroundVehicleNodeEvaluator} (path cost via {@link #isPeer}). Steer-time
 * ORCA ({@link VehicleOrca}) uses the wider {@link #isCollisionPeer} set so hostiles are
 * hard-avoided without raising A* cost toward an engagement.
 */
public final class VehiclePeerSpacing {

    /** Prefer this much clear space beyond contact inflate; routes stay feasible inside it.
     * Scaled with {@link VehicleOrca#CLEARANCE_SCALE} so path cost and steer-time ORCA agree
     * on how much breathing room a peer wants. */
    public static final double SOFT_DISTANCE = 8.0 * VehicleOrca.CLEARANCE_SCALE;

    /** Extra path cost at zero separation; falls off linearly to 0 at {@link #SOFT_DISTANCE}. */
    public static final float PATH_PENALTY = 3.0F;

    private VehiclePeerSpacing() {}

    /** Wrecks and allied crewed hulls — soft path cost only. */
    public static boolean isPeer(VehicleEntity self, AbstractUnit crew, VehicleEntity other) {
        if (other == self || !other.isAlive()) return false;
        if (other.isWreck()) return true;
        return other.getFirstPassenger() instanceof AbstractUnit driver
                && VehicleTargeting.isSameFaction(crew, driver);
    }

    /**
     * Steer-time ORCA set: any crewed hull or wreck, including hostiles.
     * Soft A* spacing stays {@link #isPeer} so routes toward an enemy remain cheap.
     */
    public static boolean isCollisionPeer(VehicleEntity self, AbstractUnit crew, VehicleEntity other) {
        if (other == self || !other.isAlive()) return false;
        if (other.isWreck()) return true;
        return other.getFirstPassenger() instanceof AbstractUnit;
    }
}
