package com.neoalive.tacz_sewv.entity.ai.navigation;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.OBB;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.joml.Vector3d;

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
    public static boolean isPeer(Entity self, AbstractUnit crew, VehicleEntity other) {
        if (other == self || !other.isAlive()) return false;
        if (other.isWreck()) return true;
        return other.getFirstPassenger() instanceof AbstractUnit driver
                && VehicleTargeting.isSameFaction(crew, driver);
    }

    /**
     * Steer-time ORCA set: every hull on the ground, crewed by anyone or empty, plus wrecks.
     * An empty or player driven hull is as solid as a crewed one. Drones fly over everything.
     * Soft A* spacing stays {@link #isPeer} so routes toward an enemy remain cheap.
     */
    public static boolean isCollisionPeer(Entity self, AbstractUnit crew, VehicleEntity other) {
        if (other == self || !other.isAlive()) return false;
        if (other.isWreck()) return true;
        if (other instanceof DroneEntity) return false;
        return other.getVehicle() == null;
    }

    /** Ground plan from the hull's collision box, or a round one of half its width without one. */
    public static VehicleOrca.Footprint footprintOf(Entity hull) {
        double half = hull.getBbWidth() * 0.5;
        if (!(hull instanceof VehicleEntity v)) return VehicleOrca.Footprint.round(half);
        OBB box;
        try {
            box = v.getCollisionOBB();
        } catch (Throwable broken) {
            return VehicleOrca.Footprint.round(half);
        }
        if (box == null) return VehicleOrca.Footprint.round(half);
        Vector3d ext = box.extents();
        Vector3d fwd = box.rotation().transform(new Vector3d(0.0, 0.0, 1.0));
        double len = Math.sqrt(fwd.x * fwd.x + fwd.z * fwd.z);
        if (len < 1.0E-6 || ext.x <= 0.0 || ext.z <= 0.0) return VehicleOrca.Footprint.round(half);
        return new VehicleOrca.Footprint(ext.x, ext.z, fwd.x / len, fwd.z / len, false);
    }
}
