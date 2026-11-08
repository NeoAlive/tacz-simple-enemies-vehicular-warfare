package com.neoalive.tacz_sewv.client.perf;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Distance tests against the render camera for client LOD decisions. */
public final class CameraDistance {

    private CameraDistance() {}

    /** True when the point is farther than {@code distance} blocks from the camera. 0 never is. */
    public static boolean beyond(double x, double y, double z, int distance) {
        if (distance <= 0) return false;
        return beyond(cameraPos(), x, y, z, distance);
    }

    static boolean beyond(Vec3 from, double x, double y, double z, int distance) {
        if (from == null) return false;
        double dx = x - from.x;
        double dy = y - from.y;
        double dz = z - from.z;
        return dx * dx + dy * dy + dz * dz > (double) distance * distance;
    }

    /**
     * Same measure TaCZ uses for its own LOD: the view-space translation of a pose matrix, valid
     * inside world rendering where the PoseStack starts at the camera.
     */
    public static boolean viewSpaceBeyond(Matrix4f pose, int distance) {
        if (distance <= 0) return false;
        float x = pose.m30();
        float y = pose.m31();
        float z = pose.m32();
        return x * x + y * y + z * z > (float) distance * distance;
    }

    static Vec3 cameraPos() {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        return camera.isInitialized() ? camera.getPosition() : null;
    }
}
