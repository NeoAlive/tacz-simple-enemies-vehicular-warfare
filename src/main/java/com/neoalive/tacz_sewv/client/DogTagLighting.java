package com.neoalive.tacz_sewv.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Light for Superb Warfare's dog-tag overlay quad. The quad is drawn with the vehicle's
 * light-probe sample, which on a large hull is often open sky while the mark sits on a
 * shaded face. Sample the quad center instead.
 *
 * <p>The four corners SBW submits average to bone-local {@code (-0.5, -0.25, 0)} after the
 * offsets inside {@code GeoVehicleRenderer.Companion.vertex}. One sample there, not a corner,
 * so a logo on a block border does not flicker. Recomputed per vertex: the pose matrix is
 * overwritten in place between bones, so a cache keyed on the matrix object would be stale.
 */
public final class DogTagLighting {

    /** Quad center in the bone space {@code vertex} actually draws. */
    private static final float CENTER_X = -0.5F;
    private static final float CENTER_Y = -0.25F;

    private static final Vector3f SAMPLE = new Vector3f();
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    private DogTagLighting() {
    }

    /**
     * @param pose     camera-relative bone pose the quad is transformed by
     * @param incoming packed light SBW was about to write; kept when the hull is full-bright
     *                 (on fire) or when the world camera is not available
     */
    public static int atDecal(Matrix4f pose, int incoming) {
        if (incoming == LightTexture.FULL_BRIGHT || pose == null) {
            return incoming;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return incoming;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (camera == null) {
            return incoming;
        }
        SAMPLE.set(CENTER_X, CENTER_Y, 0.0F);
        pose.transformPosition(SAMPLE);
        Vec3 cam = camera.getPosition();
        POS.set(SAMPLE.x + cam.x, SAMPLE.y + cam.y, SAMPLE.z + cam.z);
        return LevelRenderer.getLightColor(minecraft.level, POS);
    }
}
