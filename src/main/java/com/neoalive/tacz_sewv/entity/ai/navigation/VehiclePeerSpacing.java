package com.neoalive.tacz_sewv.entity.ai.navigation;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Soft standoff between allied / wreck hulls — preference only, never a hard block.
 * Used by {@link GroundVehicleNodeEvaluator} (path cost) and
 * {@link com.neoalive.tacz_sewv.entity.ai.sensor.GroundTerrainSensor} (context-map ranking).
 * Same peer set as the whiskers' hard contact rule ({@code isWreck} + same-faction crew).
 */
public final class VehiclePeerSpacing {

    /** Prefer this much clear space beyond contact inflate; routes stay feasible inside it. */
    public static final double SOFT_DISTANCE = 8.0;

    /** Extra path cost at zero separation; falls off linearly to 0 at {@link #SOFT_DISTANCE}. */
    public static final float PATH_PENALTY = 3.0F;

    private VehiclePeerSpacing() {}

    /** Wrecks and allied crewed hulls — same doctrine as ground whisker obstacles. */
    public static boolean isPeer(VehicleEntity self, AbstractUnit crew, VehicleEntity other) {
        if (other == self || !other.isAlive()) return false;
        if (other.isWreck()) return true;
        return other.getFirstPassenger() instanceof AbstractUnit driver
                && VehicleTargeting.isSameFaction(crew, driver);
    }
}
