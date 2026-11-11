package com.neoalive.tacz_sewv.client;

import java.util.Map;
import java.util.WeakHashMap;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import com.neoalive.tacz_sewv.block.ZoneBeaconBlockEntity;

/**
 * The FPAS: {@code radar} turns on Y (azimuth), {@code dish} tilts on its Z (zenith) by at most
 * {@link #ZENITH_LIMIT_DEG} around its bind pose, past which it clips the supports. Both slew
 * toward the server's last spotted hostile ({@link ZoneBeaconBlockEntity#aim}).
 *
 * <p>The dish's rest facing is read off its bind rotation rather than assumed, so the mapping
 * survives SBM's axis conversion and future edits of the model.
 */
public class ZoneBeaconRenderer implements BlockEntityRenderer<ZoneBeaconBlockEntity> {

    private static final float ZENITH_LIMIT_DEG = 10.0F;
    private static final float AZIMUTH_DEG_PER_TICK = 3.0F;
    private static final float ZENITH_DEG_PER_TICK = 0.5F;

    /** {azimuth deg, zenith offset deg, last render time in ticks}. */
    private static final Map<ZoneBeaconBlockEntity, float[]> slew = new WeakHashMap<>();

    public ZoneBeaconRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ZoneBeaconBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        BakedModelInstance instance = FobBlockClient.instance(be);
        if (instance == null || be.getLevel() == null) return;
        instance.resetPose();
        var radar = instance.getBone("radar");
        var dish = instance.getBone("dish");
        if (radar != null && dish != null) {
            float now = be.getLevel().getGameTime() + partialTick;
            float[] s = slew.computeIfAbsent(be, k -> new float[] {0.0F, 0.0F, now});
            float dt = Mth.clamp(now - s[2], 0.0F, 20.0F);
            s[2] = now;

            // Dish boresight in the radar's frame at rest: the feed struts sit on its local +Y.
            Vector3f rest = dish.definition().bindRotation().transform(new Vector3f(0.0F, 1.0F, 0.0F));
            Vec3 aim = be.aim();
            if (aim != null) {
                double dx = aim.x - (be.getBlockPos().getX() + 0.5);
                double dz = aim.z - (be.getBlockPos().getZ() + 0.5);
                double dy = aim.y - (be.getBlockPos().getY() + Math.abs(dish.definition().pivotY()) / 16.0);
                // Ry(t) turns a horizontal bearing atan2(z, x) by -t.
                float azimuth = (float) Math.toDegrees(Math.atan2(rest.z, rest.x) - Math.atan2(dz, dx));
                float restElev = (float) Math.toDegrees(Math.atan2(rest.y, Math.hypot(rest.x, rest.z)));
                float wantElev = (float) Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
                // A world-Z turn raises the boresight when it points +X, lowers it when -X.
                float zenith = Mth.clamp(Math.signum(rest.x) * (wantElev - restElev),
                        -ZENITH_LIMIT_DEG, ZENITH_LIMIT_DEG);
                s[0] += Mth.clamp(Mth.wrapDegrees(azimuth - s[0]), -AZIMUTH_DEG_PER_TICK * dt,
                        AZIMUTH_DEG_PER_TICK * dt);
                s[1] += Mth.clamp(zenith - s[1], -ZENITH_DEG_PER_TICK * dt, ZENITH_DEG_PER_TICK * dt);
            }
            radar.rotation.mul(Axis.YP.rotationDegrees(s[0]));
            // SBM composes bind rotations Z-outermost, so the zenith goes on the left.
            dish.rotation.premul(Axis.ZP.rotationDegrees(s[1]));
        }

        poseStack.pushPose();
        poseStack.translate(0.5D, 0.0D, 0.5D);
        FobBlockClient.render(instance, FobBlockClient.Kind.FPAS, poseStack, buffer, packedLight, packedOverlay);
        poseStack.popPose();
    }
}
