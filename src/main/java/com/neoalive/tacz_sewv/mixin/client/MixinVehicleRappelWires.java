package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.client.renderer.entity.VehicleRenderer;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.neoalive.tacz_sewv.client.HeliRunPhaseClient;
import com.neoalive.tacz_sewv.entity.ai.RappelSupport;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rappel ropes: vertical line strands on the hull's local X± faces, drawn inside
 * {@link VehicleRenderer}'s pushed/axis-rotated pose so they bank with the airframe.
 * Length is dynamic — local attach Y down to the same {@link RappelSupport#groundY}
 * the Stage-4 descent uses — so the rope reaches terrain rather than a fixed cage.
 * Gated solely on {@link HeliRunPhaseClient#isRappelling(int)}.
 */
@Mixin(value = VehicleRenderer.class, remap = false)
public abstract class MixinVehicleRappelWires {

    @Unique
    private static final float TACZ_SEWV$R = 0.42F;
    @Unique
    private static final float TACZ_SEWV$G = 0.38F;
    @Unique
    private static final float TACZ_SEWV$B = 0.30F;
    @Unique
    private static final float TACZ_SEWV$A = 1.0F;
    /** Strand offsets in local Z for a thin rope look (not a single hairline). */
    @Unique
    private static final float[] TACZ_SEWV$STRANDS = { -0.04F, 0.0F, 0.04F };

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/atsuishio/superbwarfare/client/renderer/entity/VehicleRenderer;renderCustomPart(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
                    shift = At.Shift.AFTER))
    private void tacz_sewv$drawRappelWires(
            VehicleEntity entity,
            float entityYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo ci) {
        if (!HeliRunPhaseClient.isRappelling(entity.getId())) return;

        double face = RappelSupport.localFaceX(entity);
        double attachY = RappelSupport.localAttachY(entity);
        VertexConsumer lines = buffer.getBuffer(RenderType.lines());

        tacz_sewv$drawRope(entity, poseStack, lines, -face, attachY);
        tacz_sewv$drawRope(entity, poseStack, lines, face, attachY);
    }

    /**
     * Rope from local face attach down to world ground under that column, expressed in
     * local Y (hover is level — local −Y ≈ world down; matches descent {@code groundY}).
     */
    @Unique
    private static void tacz_sewv$drawRope(
            VehicleEntity entity, PoseStack poseStack, VertexConsumer lines,
            double faceX, double attachY) {
        // World XZ of this face (same column troopers slide on), then surface Y.
        boolean plus = faceX >= 0.0;
        Vec3 top = RappelSupport.ropeTopWorld(entity, plus);
        double ground = RappelSupport.groundY(entity.level(), top.x, top.z);
        float bottomY = (float) (ground - entity.getY());
        float topY = (float) attachY;
        if (bottomY >= topY) return; // already on/below deck — nothing to draw

        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();
        float fx = (float) faceX;
        for (float zOff : TACZ_SEWV$STRANDS) {
            tacz_sewv$line(lines, matrix, normal, fx, topY, zOff, fx, bottomY, zOff);
        }
    }

    @Unique
    private static void tacz_sewv$line(
            VertexConsumer lines, Matrix4f matrix, Matrix3f normalMatrix,
            float x1, float y1, float z1, float x2, float y2, float z2) {
        Vector3f n = new Vector3f(x2 - x1, y2 - y1, z2 - z1);
        if (n.lengthSquared() < 1.0E-8F) return;
        n.normalize();
        normalMatrix.transform(n);
        lines.vertex(matrix, x1, y1, z1)
                .color(TACZ_SEWV$R, TACZ_SEWV$G, TACZ_SEWV$B, TACZ_SEWV$A)
                .normal(n.x(), n.y(), n.z())
                .endVertex();
        lines.vertex(matrix, x2, y2, z2)
                .color(TACZ_SEWV$R, TACZ_SEWV$G, TACZ_SEWV$B, TACZ_SEWV$A)
                .normal(n.x(), n.y(), n.z())
                .endVertex();
    }
}
