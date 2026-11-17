package com.neoalive.tacz_sewv.client.perf;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.config.PerfFlags;

/**
 * Which client-side SBW vehicle simulation can be skipped. SBW predicts every vehicle on the
 * client (its local-control check is always false there) and corrects towards server packets, so
 * for a hull nobody here drives, skipping prediction only costs smoothness, never state.
 */
public final class VehicleClientLod {

    static final double REST_EPS_SQR = 1.0E-6;

    private VehicleClientLod() {}

    /** The local player rides this hull, views through it, or it is a drone. */
    public static boolean isLocal(VehicleEntity vehicle) {
        if (vehicle instanceof DroneEntity) return true;
        Minecraft mc = Minecraft.getInstance();
        Entity player = mc.player;
        if (player != null && player.getRootVehicle() == vehicle) return true;
        Entity camera = mc.getCameraEntity();
        return camera != null && (camera == vehicle || camera.getRootVehicle() == vehicle);
    }

    /** Parked: on the ground with no horizontal motion and no position change this tick. */
    public static boolean atRest(VehicleEntity vehicle) {
        Vec3 motion = vehicle.getDeltaMovement();
        return atRest(vehicle.onGround(), motion.x, motion.z,
                vehicle.getX() - vehicle.xo, vehicle.getY() - vehicle.yo, vehicle.getZ() - vehicle.zo);
    }

    static boolean atRest(boolean onGround, double motionX, double motionZ, double dx, double dy, double dz) {
        if (!onGround) return false;
        if (motionX * motionX + motionZ * motionZ > REST_EPS_SQR) return false;
        return dx * dx + dy * dy + dz * dz <= REST_EPS_SQR;
    }

    public static boolean far(VehicleEntity vehicle) {
        int distance = PerfFlags.vehicleFarSimDistance();
        if (distance <= 0) return false;
        return CameraDistance.beyond(vehicle.getX(), vehicle.getY(), vehicle.getZ(), distance);
    }

    /** Whether client prediction (collision and step-up) can be skipped for this hull this tick. */
    public static boolean skipMove(VehicleEntity vehicle) {
        if (isLocal(vehicle)) return false;
        if (PerfFlags.vehicleRestSkip() && atRest(vehicle)) return true;
        return far(vehicle);
    }

    public static boolean beyondListener(double x, double y, double z, int distance) {
        return CameraDistance.beyond(x, y, z, distance);
    }

    static boolean beyond(Vec3 from, double x, double y, double z, int distance) {
        return CameraDistance.beyond(from, x, y, z, distance);
    }
}
