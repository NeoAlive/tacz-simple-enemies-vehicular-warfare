package com.neoalive.tacz_sewv.flight;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.physics.McPose;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketHeliRoll;

/**
 * The entity side of an integrator-flown hull: keeps our integrated pose and the entity's agreeing.
 * Shared by the helicopter and fixed-wing runtimes, which own the physics state and hand this
 * class its position / velocity / attitude vectors to read and write.
 *
 * <p><b>The entity is authoritative for position.</b> {@code move()} resolves collision. A
 * collided axis loses its velocity (inelastic contact). Any jump we did not cause, or an attitude
 * we did not set, means someone else wrote the hull (helipad snap, spawners, carrier ops, ...) and
 * the owner must re-seat from the entity rather than fight it ({@link #reconcile} answers that).
 *
 * <p>Body rates and any controller memory are the owner's business: {@link #reseat} only writes
 * position, velocity and attitude, and zeroes the rates it is handed.
 */
public final class HullStateBridge {

    /** Publish deadband: an angle change smaller than this is not written (Komodo's ROT_EPS). */
    public static final double ANGLE_DEADBAND = 0.05;
    /**
     * Vanilla {@code Entity.move} drops any displacement with lengthSqr <= 1e-7 (|d| <= 3.16e-4 m,
     * javap-verified): the hull simply does not move. Below that we keep our own integrated
     * position and publish the accumulated residual next tick, so slow motion is never lost.
     */
    private static final double VANILLA_MIN_MOVE_SQR = 1.0E-7;
    /** An outside write: position off our prediction by this much without a collision, or angles by this. */
    private static final double RESEAT_DISTANCE = 0.5, RESEAT_ANGLE = 0.5;

    /** Our intended position after the last publish, and the entity position we published from. */
    private final Vector3d predicted = new Vector3d();
    private final Vector3d pubFrom = new Vector3d();
    private float pubYaw, pubPitch, pubRoll, sentRoll = Float.NaN;
    private Vector3d lastDm;

    /** Adopt the entity's pose into {@code p}/{@code v}/{@code q}; {@code w} is zeroed. */
    public void reseat(VehicleEntity hull, Vector3d p, Vector3d v, Quaterniond q, Vector3d w) {
        Vec3 dm = hull.getDeltaMovement();
        p.set(hull.getX(), hull.getY(), hull.getZ());
        v.set(dm.x * 20.0, dm.y * 20.0, dm.z * 20.0);
        q.set(McPose.toQuat(hull.getYRot(), hull.getXRot(), hull.getRoll()));
        w.zero();
        pubYaw = hull.getYRot();
        pubPitch = hull.getXRot();
        pubRoll = hull.getRoll();
        predicted.set(p);
        lastDm = null;
    }

    /**
     * Fold what happened to the entity since our last publish into {@code p}/{@code v}. Returns
     * true when someone else wrote the hull: the caller must then re-seat (its own re-seat, which
     * calls {@link #reseat} and resets whatever memory it keeps).
     */
    public boolean reconcile(VehicleEntity hull, Vector3d p, Vector3d v) {
        if (lastDm != null && lastDm.lengthSquared() <= VANILLA_MIN_MOVE_SQR
                && pubFrom.equals(hull.getX(), hull.getY(), hull.getZ())) {
            p.set(predicted); // vanilla dropped a sub-threshold move: keep the residual
            return false;
        }
        Vec3 dmNow = hull.getDeltaMovement();
        if (lastDm != null) {
            if (lastDm.x != 0.0 && dmNow.x == 0.0) v.x = 0.0;
            if (lastDm.z != 0.0 && dmNow.z == 0.0) v.z = 0.0;
            if (hull.verticalCollision && lastDm.y != 0.0 && dmNow.y == 0.0) v.y = 0.0;
        }
        boolean collided = hull.horizontalCollision || hull.verticalCollision;
        double jump = predicted.distance(hull.getX(), hull.getY(), hull.getZ());
        boolean attitudeWritten = Math.abs(McPose.wrapDeg(hull.getYRot() - pubYaw)) > RESEAT_ANGLE
                || Math.abs(hull.getXRot() - pubPitch) > RESEAT_ANGLE
                || Math.abs(McPose.wrapDeg(hull.getRoll() - pubRoll)) > RESEAT_ANGLE;
        if ((jump > RESEAT_DISTANCE && !collided) || attitudeWritten) {
            return true;
        }
        p.set(hull.getX(), hull.getY(), hull.getZ());
        return false;
    }

    /** Write our integrated position (as this tick's displacement) and attitude to the entity. */
    public void publishPose(VehicleEntity hull, Vector3d p, Quaterniond q) {
        publishPose(hull, p, q, false);
    }

    /**
     * As above; {@code heartbeat} resends the roll even when unchanged. A fixed-wing client only
     * cancels SBW's client engine while roll packets keep arriving (it cannot otherwise tell whether
     * the server is flying the hull), so the plane runtime asks for one every second.
     */
    public void publishPose(VehicleEntity hull, Vector3d p, Quaterniond q, boolean heartbeat) {
        // From the entity, not from p0: p0 may carry a residual vanilla dropped last tick.
        pubFrom.set(hull.getX(), hull.getY(), hull.getZ());
        Vector3d dm = new Vector3d(p).sub(pubFrom);
        hull.setDeltaMovement(dm.x, dm.y, dm.z);
        lastDm = dm;
        predicted.set(p);

        McPose.Euler e = McPose.toEuler(q, new McPose.Euler(pubYaw, pubPitch, pubRoll));
        if (Math.abs(McPose.wrapDeg(e.yawDeg() - pubYaw)) >= ANGLE_DEADBAND) {
            hull.setYRot((float) e.yawDeg());
            pubYaw = hull.getYRot();
        }
        if (Math.abs(e.pitchDeg() - pubPitch) >= ANGLE_DEADBAND) {
            hull.setXRot((float) e.pitchDeg());
            pubPitch = hull.getXRot();
        }
        if (Math.abs(McPose.wrapDeg(e.rollDeg() - pubRoll)) >= ANGLE_DEADBAND) {
            hull.setZRot((float) e.rollDeg());
            pubRoll = hull.getRoll();
        }
        if (heartbeat || Float.isNaN(sentRoll) || Math.abs(McPose.wrapDeg(pubRoll - sentRoll)) >= ANGLE_DEADBAND) {
            sentRoll = pubRoll;
            NetworkHandler.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> hull),
                    new PacketHeliRoll(hull.getId(), pubRoll));
        }
    }

    /** Tell tracking clients the integrator let go: a NaN roll, which clears their ownership mark. */
    public static void sendRelease(VehicleEntity hull) {
        NetworkHandler.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> hull),
                new PacketHeliRoll(hull.getId(), Float.NaN));
    }

    /** The owner is not integrating this tick (SBW holds the hull): forget the last displacement. */
    public void parked(VehicleEntity hull) {
        lastDm = null;
        predicted.set(hull.getX(), hull.getY(), hull.getZ());
    }

    /** Last published yaw, degrees (telemetry / heading fallbacks). */
    public float publishedYaw() {
        return pubYaw;
    }
}
