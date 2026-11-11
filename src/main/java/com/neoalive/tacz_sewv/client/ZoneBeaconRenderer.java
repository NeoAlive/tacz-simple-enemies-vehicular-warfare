package com.neoalive.tacz_sewv.client;

import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import com.neoalive.tacz_sewv.block.ZoneBeaconBlockEntity;
import com.neoalive.tacz_sewv.init.ModSounds;

/**
 * The FPAS: {@code radar} turns on Y (azimuth), {@code dish} tilts on its Z (zenith) by at most
 * {@link #ZENITH_LIMIT_DEG} around its bind pose, past which it clips the supports. In MTI both slew
 * toward the server's last spotted hostile ({@link ZoneBeaconBlockEntity#aim}); in ECM the array
 * just sweeps slowly round.
 *
 * <p>The dish's rest facing is read off its bind rotation rather than assumed, so the mapping
 * survives SBM's axis conversion and future edits of the model.
 */
public class ZoneBeaconRenderer implements BlockEntityRenderer<ZoneBeaconBlockEntity> {

    private static final float ZENITH_LIMIT_DEG = 10.0F;
    private static final float AZIMUTH_DEG_PER_TICK = 3.0F;
    private static final float ZENITH_DEG_PER_TICK = 0.5F;
    /** ECM idle sweep, a full turn in about 45 s. */
    private static final float ECM_SPIN_DEG_PER_TICK = 0.4F;

    /** Remaining error below which an axis counts as settled, for the slew sounds. */
    private static final float AZIMUTH_SETTLED_DEG = 0.5F;
    private static final float ZENITH_SETTLED_DEG = 0.1F;
    /** A loop outlives its last render by this many ticks, so an unseen FPAS goes quiet. */
    private static final int LOOP_GRACE_TICKS = 5;

    private static final class Slew {
        float azimuth;
        float zenith;
        float last;
        boolean azMoving;
        boolean zenMoving;
        @Nullable
        SlewLoop azLoop;
        @Nullable
        SlewLoop zenLoop;

        Slew(float now) {
            this.last = now;
        }
    }

    private static final Map<ZoneBeaconBlockEntity, Slew> slew = new WeakHashMap<>();

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
            Slew s = slew.computeIfAbsent(be, k -> new Slew(now));
            float dt = Mth.clamp(now - s.last, 0.0F, 20.0F);
            s.last = now;
            boolean azMoving = false, zenMoving = false;

            // Dish boresight in the radar's frame at rest: the feed struts sit on its local +Y.
            Vector3f rest = dish.definition().bindRotation().transform(new Vector3f(0.0F, 1.0F, 0.0F));
            Vec3 aim = be.mode() == ZoneBeaconBlockEntity.Mode.MTI ? be.aim() : null;
            if (aim == null) {
                // ECM, or MTI with nothing seen yet: a slow sweep, dish settling level. No servo loop.
                s.azimuth = Mth.wrapDegrees(s.azimuth + ECM_SPIN_DEG_PER_TICK * dt);
                s.zenith -= Mth.clamp(s.zenith, -ZENITH_DEG_PER_TICK * dt, ZENITH_DEG_PER_TICK * dt);
            } else {
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
                float azErr = Mth.wrapDegrees(azimuth - s.azimuth);
                float zenErr = zenith - s.zenith;
                azMoving = Math.abs(azErr) > AZIMUTH_SETTLED_DEG;
                zenMoving = Math.abs(zenErr) > ZENITH_SETTLED_DEG;
                s.azimuth += Mth.clamp(azErr, -AZIMUTH_DEG_PER_TICK * dt, AZIMUTH_DEG_PER_TICK * dt);
                s.zenith += Mth.clamp(zenErr, -ZENITH_DEG_PER_TICK * dt, ZENITH_DEG_PER_TICK * dt);
            }
            sounds(be, s, azMoving, zenMoving);
            radar.rotation.mul(Axis.YP.rotationDegrees(s.azimuth));
            // SBM composes bind rotations Z-outermost, so the zenith goes on the left.
            dish.rotation.premul(Axis.ZP.rotationDegrees(s.zenith));
        }

        poseStack.pushPose();
        poseStack.translate(0.5D, 0.0D, 0.5D);
        FobBlockClient.render(instance, FobBlockClient.Kind.FPAS, poseStack, buffer, packedLight, packedOverlay);
        poseStack.popPose();
    }

    private static void sounds(ZoneBeaconBlockEntity be, Slew s, boolean azMoving, boolean zenMoving) {
        SoundManager manager = Minecraft.getInstance().getSoundManager();
        if (azMoving && (s.azLoop == null || s.azLoop.isStopped())) {
            s.azLoop = new SlewLoop(ModSounds.FPAS_AZIMUTH.get(), be, s, true);
            manager.play(s.azLoop);
        }
        if (zenMoving && (s.zenLoop == null || s.zenLoop.isStopped())) {
            s.zenLoop = new SlewLoop(ModSounds.FPAS_ZENITH.get(), be, s, false);
            manager.play(s.zenLoop);
        }
        if ((s.azMoving && !azMoving) || (s.zenMoving && !zenMoving)) {
            Vec3 at = Vec3.atBottomCenterOf(be.getBlockPos()).add(0.0, 2.0, 0.0);
            manager.play(new SimpleSoundInstance(ModSounds.FPAS_STOP.get(), SoundSource.BLOCKS, 1.0F, 1.0F,
                    SoundInstance.createUnseededRandom(), at.x, at.y, at.z));
        }
        s.azMoving = azMoving;
        s.zenMoving = zenMoving;
    }

    /** One axis' servo loop; stops itself once that axis settles or the FPAS is no longer drawn. */
    private static final class SlewLoop extends AbstractTickableSoundInstance {
        private final ZoneBeaconBlockEntity be;
        private final Slew slew;
        private final boolean azimuth;

        SlewLoop(SoundEvent event, ZoneBeaconBlockEntity be, Slew slew, boolean azimuth) {
            super(event, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
            this.be = be;
            this.slew = slew;
            this.azimuth = azimuth;
            this.looping = true;
            this.delay = 0;
            this.volume = azimuth ? 0.8F : 0.6F;
            Vec3 at = Vec3.atBottomCenterOf(be.getBlockPos()).add(0.0, 2.0, 0.0);
            this.x = at.x;
            this.y = at.y;
            this.z = at.z;
        }

        @Override
        public void tick() {
            boolean moving = this.azimuth ? this.slew.azMoving : this.slew.zenMoving;
            if (this.be.isRemoved() || this.be.getLevel() == null || !moving
                    || this.be.getLevel().getGameTime() - this.slew.last > LOOP_GRACE_TICKS) {
                stop();
            }
        }
    }
}
