package com.neoalive.tacz_sewv.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.compat.ExterminationCompat;
import com.neoalive.tacz_sewv.compat.ExterminationShieldFx;
import com.neoalive.tacz_sewv.config.ClientConfig;

/**
 * Debug wireframe of the Tripod shield prolate spheroid (same math as
 * {@link ExterminationShieldFx#projectToSpheroid}). Cosmetic only — independent of damage cancel.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class ExterminationShieldDebugRenderer {

    private static final int MERIDIANS = 12;
    private static final int PARALLELS = 10;
    private static final int SEGMENTS = 24;

    private ExterminationShieldDebugRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        if (!ClientConfig.flag(ClientConfig.TRIPOD_SHIELD_WIREFRAME)) return;
        if (!ExterminationCompat.available()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        Vec3 cam = event.getCamera().getPosition();

        double range = 96.0;
        AABB box = mc.player.getBoundingBox().inflate(range);
        for (Entity entity : mc.level.getEntities(mc.player, box, ExterminationCompat::isShieldedPod)) {
            if (!(entity instanceof LivingEntity living)) continue;
            drawSpheroid(pose, lines, living, cam);
        }

        buffers.endBatch(RenderType.lines());
    }

    private static void drawSpheroid(PoseStack pose, VertexConsumer lines, LivingEntity pod, Vec3 cam) {
        Vec3 center = ExterminationShieldFx.spheroidCenter(pod);
        double a = ExterminationShieldFx.semiXz(pod);
        double c = ExterminationShieldFx.semiY(pod);
        float r = 0.2f;
        float g = 0.85f;
        float b = 1.0f;
        float alpha = 0.85f;

        for (int m = 0; m < MERIDIANS; m++) {
            double lon = (Math.PI * 2.0 * m) / MERIDIANS;
            double cosLon = Math.cos(lon);
            double sinLon = Math.sin(lon);
            Vec3 prev = null;
            for (int s = 0; s <= SEGMENTS; s++) {
                double lat = -Math.PI / 2.0 + (Math.PI * s) / SEGMENTS;
                double cosLat = Math.cos(lat);
                double sinLat = Math.sin(lat);
                Vec3 p = new Vec3(
                        center.x + a * cosLat * cosLon,
                        center.y + c * sinLat,
                        center.z + a * cosLat * sinLon);
                if (prev != null) {
                    line(pose, lines, prev, p, cam, r, g, b, alpha);
                }
                prev = p;
            }
        }

        for (int pIdx = 1; pIdx < PARALLELS; pIdx++) {
            double lat = -Math.PI / 2.0 + (Math.PI * pIdx) / PARALLELS;
            double cosLat = Math.cos(lat);
            double sinLat = Math.sin(lat);
            for (int s = 0; s < SEGMENTS; s++) {
                double lon0 = (Math.PI * 2.0 * s) / SEGMENTS;
                double lon1 = (Math.PI * 2.0 * (s + 1)) / SEGMENTS;
                Vec3 p0 = new Vec3(
                        center.x + a * cosLat * Math.cos(lon0),
                        center.y + c * sinLat,
                        center.z + a * cosLat * Math.sin(lon0));
                Vec3 p1 = new Vec3(
                        center.x + a * cosLat * Math.cos(lon1),
                        center.y + c * sinLat,
                        center.z + a * cosLat * Math.sin(lon1));
                line(pose, lines, p0, p1, cam, r, g, b, alpha);
            }
        }
    }

    private static void line(
            PoseStack pose,
            VertexConsumer lines,
            Vec3 from,
            Vec3 to,
            Vec3 cam,
            float r,
            float g,
            float b,
            float alpha) {
        Matrix4f matrix = pose.last().pose();
        Matrix3f normalMat = pose.last().normal();
        float dx = (float) (to.x - from.x);
        float dy = (float) (to.y - from.y);
        float dz = (float) (to.z - from.z);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6f) return;
        float nx = dx / len;
        float ny = dy / len;
        float nz = dz / len;

        float x0 = (float) (from.x - cam.x);
        float y0 = (float) (from.y - cam.y);
        float z0 = (float) (from.z - cam.z);
        float x1 = (float) (to.x - cam.x);
        float y1 = (float) (to.y - cam.y);
        float z1 = (float) (to.z - cam.z);

        lines.vertex(matrix, x0, y0, z0).color(r, g, b, alpha).normal(normalMat, nx, ny, nz).endVertex();
        lines.vertex(matrix, x1, y1, z1).color(r, g, b, alpha).normal(normalMat, nx, ny, nz).endVertex();
    }
}
