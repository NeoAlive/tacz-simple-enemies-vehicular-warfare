package com.neoalive.tacz_sewv.entity.ai.support;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.ArtilleryEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.TrajectoryCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.joml.Vector3f;

import com.neoalive.tacz_sewv.bridge.FireMission;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;

/**
 * SEWV ballistics for SPH / coastal artillery ({@link ArtilleryEntity}), e.g. PLZ-05.
 *
 * <p>Uses the hull gun's projectile velocity and gravity with
 * {@link TrajectoryCalculator#calculateLaunchVector} — <b>not</b> mortar
 * {@code calculateShootVectors} / pitch floors. Writes {@code shootVec} on the artillery entity
 * for the native fire path.
 */
public final class ArtillerySupport {

    private static final String WEAPON = "Main";

    private ArtillerySupport() {}

    public static boolean isCrewing(AbstractUnit unit) {
        if (!(unit.getVehicle() instanceof VehicleEntity hull)) return false;
        if (!HullFacts.isArtilleryHull(hull)) return false;
        if (!(hull instanceof ArtilleryEntity)) return false;
        int seat = hull.getSeatIndex(unit);
        return seat >= 0 && (seat == 0 || seat == hull.getTurretControllerIndex());
    }

    public static boolean isArtilleryHull(VehicleEntity hull) {
        return HullFacts.isArtilleryHull(hull) && hull instanceof ArtilleryEntity;
    }

    /** True when the crew has a designation worth firing on. */
    public static boolean hasFireWork(AbstractUnit unit) {
        return aimpoint(unit) != null;
    }

    @Nullable
    public static FireMission fireMissionOf(AbstractUnit unit) {
        if (!(unit instanceof IMortarCrew crew)) return null;
        FireMission mission = crew.sewv$getFireMission();
        if (mission == null) return null;
        if (mission.isExpired(unit.level().getGameTime())) {
            crew.sewv$setFireMission(null);
            return null;
        }
        return mission;
    }

    @Nullable
    public static Vec3 aimpoint(AbstractUnit unit) {
        LivingEntity target = unit.getTarget();
        if (target != null && target.isAlive()) {
            return target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
        }
        FireMission mission = fireMissionOf(unit);
        return mission == null ? null : Vec3.atCenterOf(mission.pos());
    }

    /**
     * Compute launch vector from gun vel/grav and write it onto the artillery hull.
     * Returns the chosen vector, or null if unreachable / out of turret arc.
     */
    @Nullable
    public static Vec3 solveAndLay(ArtilleryEntity hull, LivingEntity shooter, Vec3 aimPos) {
        try {
            Vec3 from = hull.getShootPos(WEAPON, 1.0F);
            if (from == null) from = hull.getEyePosition();

            double v = hull.getProjectileVelocity(WEAPON);
            double g = hull.getProjectileGravity(WEAPON);
            // Prefer depressed (flat) when both exist; fall back to lofted.
            Vec3 launch = TrajectoryCalculator.calculateLaunchVector(from, aimPos, v, g, true);
            boolean depressed = true;
            if (launch == null) {
                launch = TrajectoryCalculator.calculateLaunchVector(from, aimPos, v, g, false);
                depressed = false;
            }
            if (launch == null) return null;

            float pitch = (float) -com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
                    .getXRotFromVector(launch);
            float max = hull.getTurretMaxPitch();
            float min = hull.getTurretMinPitch();
            // SBW stores max as the "most elevated" magnitude; compare like ArtilleryEntity.setTarget.
            if (pitch < -max || pitch > -min) {
                // Try the other arc.
                Vec3 alt = TrajectoryCalculator.calculateLaunchVector(from, aimPos, v, g, !depressed);
                if (alt == null) return null;
                float altPitch = (float) -com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
                        .getXRotFromVector(alt);
                if (altPitch < -max || altPitch > -min) return null;
                launch = alt;
                depressed = !depressed;
            }

            hull.setDepressed(depressed);
            hull.setTargetPos(BlockPos.containing(aimPos));
            hull.setRadius(0);
            hull.setShootVec(new Vector3f((float) launch.x, (float) launch.y, (float) launch.z));
            hull.setLockTurret(false);
            return launch;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Angle between current shoot vector and the laid demand, degrees. */
    public static double aimErrorDeg(ArtilleryEntity hull, Vec3 laid) {
        try {
            Vector3f current = hull.getShootVec();
            if (current == null) return 180.0;
            Vec3 cur = new Vec3(current.x, current.y, current.z).normalize();
            Vec3 want = laid.normalize();
            double dot = Math.max(-1.0, Math.min(1.0, cur.dot(want)));
            return Math.toDegrees(Math.acos(dot));
        } catch (Throwable ignored) {
            return 180.0;
        }
    }
}
