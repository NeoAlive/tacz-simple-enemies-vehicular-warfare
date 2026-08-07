package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.client.renderer.entity.VehicleRenderer;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.client.HeliRunPhaseClient;
import com.neoalive.tacz_sewv.entity.ai.support.RappelSupport;

/**
 * Rappel ropes: thin lit ribbons on the hull's local X± faces, drawn inside
 * {@link VehicleRenderer}'s pushed/axis-rotated pose so they bank with the airframe.
 * Length is {@code min(distance to ground, }{@link #TACZ_SEWV$RAPPEL_WIRE_MAX_LENGTH}{@code)} —
 * short drops reach terrain; tall drops (cliffs/valleys) cap instead of a huge streamer.
 * Gated solely on {@link HeliRunPhaseClient#isRappelling(int)}.
 *
 * <p>Uses {@link RenderType#leash()} ({@code POSITION_COLOR_LIGHTMAP} triangle strip) so the
 * rope takes the vehicle's {@code packedLight} and darkens in shadow instead of glowing
 * fullbright like {@link RenderType#lines()}.
 */
@Mixin(value = VehicleRenderer.class, remap = false)
public abstract class MixinVehicleRappelWires {

    @Unique
    private static final float TACZ_SEWV$R = 0.42F;
    @Unique
    private static final float TACZ_SEWV$G = 0.38F;
    @Unique
    private static final float TACZ_SEWV$B = 0.30F;
    /** Half-width of the leash-style ribbon (blocks), matching vanilla lead thickness. */
    @Unique
    private static final float TACZ_SEWV$HALF_W = 0.025F;
    /** Segments along the rope — enough for leash banding, cheap for an 8-block max. */
    @Unique
    private static final int TACZ_SEWV$STEPS = 12;
    /** Max drawn rope length (blocks). Short drops reach ground; tall drops stop here. */
    @Unique
    private static final float TACZ_SEWV$RAPPEL_WIRE_MAX_LENGTH = 8.0F;

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
        VertexConsumer leash = buffer.getBuffer(RenderType.leash());
        Matrix4f matrix = poseStack.last().pose();

        // Same RenderType shares one BufferBuilder — break the strip between faces so the
        // two ropes do not span a lit sheet across the cabin.
        boolean drew = tacz_sewv$drawRope(entity, leash, matrix, packedLight, -face, attachY);
        if (drew) {
            tacz_sewv$breakStrip(leash, matrix, packedLight, (float) -face, (float) attachY);
        }
        tacz_sewv$drawRope(entity, leash, matrix, packedLight, face, attachY);
    }

    /**
     * Rope from local face attach down toward world ground under that column, capped at
     * {@link #TACZ_SEWV$RAPPEL_WIRE_MAX_LENGTH}. Descent still uses full {@code groundY};
     * this is draw length only.
     *
     * @return {@code true} if anything was drawn (so the caller can break the strip)
     */
    @Unique
    private static boolean tacz_sewv$drawRope(
            VehicleEntity entity, VertexConsumer leash, Matrix4f matrix, int packedLight,
            double faceX, double attachY) {
        boolean plus = faceX >= 0.0;
        Vec3 top = RappelSupport.ropeTopWorld(entity, plus);
        double ground = RappelSupport.groundY(entity.level(), top.x, top.z);
        float bottomY = (float) (ground - entity.getY());
        float topY = (float) attachY;
        if (bottomY >= topY) return false;

        float lengthToGround = topY - bottomY;
        float length = Math.min(lengthToGround, TACZ_SEWV$RAPPEL_WIRE_MAX_LENGTH);
        bottomY = topY - length;

        float fx = (float) faceX;
        // Vanilla leash builds a closed triangle-strip tube: forward then reverse.
        for (int i = 0; i <= TACZ_SEWV$STEPS; i++) {
            tacz_sewv$leashPair(leash, matrix, fx, topY, bottomY, packedLight, i, false);
        }
        for (int i = TACZ_SEWV$STEPS; i >= 0; i--) {
            tacz_sewv$leashPair(leash, matrix, fx, topY, bottomY, packedLight, i, true);
        }
        return true;
    }

    /** Degenerate verts so the next strip does not connect to this one. */
    @Unique
    private static void tacz_sewv$breakStrip(
            VertexConsumer leash, Matrix4f matrix, int packedLight, float fx, float y) {
        tacz_sewv$vert(leash, matrix, fx, y, 0.0F, 1.0F, packedLight);
        tacz_sewv$vert(leash, matrix, fx, y, 0.0F, 1.0F, packedLight);
    }

    /** One leash strip pair at step {@code i} — mirrors {@code MobRenderer.addVertexPair}. */
    @Unique
    private static void tacz_sewv$leashPair(
            VertexConsumer leash, Matrix4f matrix,
            float fx, float topY, float bottomY, int packedLight,
            int i, boolean reverse) {
        float t = (float) i / (float) TACZ_SEWV$STEPS;
        float y = Mth.lerp(t, topY, bottomY);
        float shade = i % 2 == (reverse ? 1 : 0) ? 0.7F : 1.0F;
        float side = reverse ? 0.0F : TACZ_SEWV$HALF_W;
        tacz_sewv$vert(leash, matrix, fx - TACZ_SEWV$HALF_W, y + side, 0.0F, shade, packedLight);
        tacz_sewv$vert(leash, matrix, fx + TACZ_SEWV$HALF_W, y + TACZ_SEWV$HALF_W - side, 0.0F, shade, packedLight);
    }

    @Unique
    private static void tacz_sewv$vert(
            VertexConsumer leash, Matrix4f matrix,
            float x, float y, float z, float shade, int packedLight) {
        leash.vertex(matrix, x, y, z)
                .color(TACZ_SEWV$R * shade, TACZ_SEWV$G * shade, TACZ_SEWV$B * shade, 1.0F)
                .uv2(packedLight)
                .endVertex();
    }
}
