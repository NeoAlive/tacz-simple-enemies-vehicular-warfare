package com.neoalive.tacz_sewv.client;

import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
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
import org.joml.Quaternionf;

import com.neoalive.tacz_sewv.block.ZoneBeaconBlockEntity;
import com.neoalive.tacz_sewv.client.fpas.BedrockClip;
import com.neoalive.tacz_sewv.client.fpas.FpasPose;
import com.neoalive.tacz_sewv.init.ModSounds;

/**
 * The FPAS. Powering up plays {@code transitionopen}, powering down plays {@code transitionclose},
 * and in between {@code radar} spins (ECM, or MTI with nothing in the scan) or tracks the nearest
 * contact on azimuth only. {@link FpasPose} owns the sequencing; this class samples the clips and
 * writes the bones.
 *
 * <p>Clips are Bedrock data and are converted the way SBM bakes bones: X and Y rotation negated,
 * ZYX order, X position negated. The dish rests facing world -Z.
 */
public class ZoneBeaconRenderer implements BlockEntityRenderer<ZoneBeaconBlockEntity> {

    private static final String[] CLIP_BONES = {"dish", "hydraulicjacks", "gunmetal"};
    /** A loop outlives its last render by this many ticks, so an unseen FPAS goes quiet. */
    private static final int LOOP_GRACE_TICKS = 5;

    private static final class State {
        final FpasPose pose;
        float last;
        boolean azMoving;
        boolean hydraulics;
        @Nullable
        Loop azLoop;
        @Nullable
        Loop hydraulicsLoop;

        State(FpasPose pose, float now) {
            this.pose = pose;
            this.last = now;
        }
    }

    private static final Map<ZoneBeaconBlockEntity, State> states = new WeakHashMap<>();

    public ZoneBeaconRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ZoneBeaconBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        BakedModelInstance instance = FobBlockClient.instance(be);
        if (instance == null || be.getLevel() == null) return;
        instance.resetPose();
        Map<String, BedrockClip> clips = FobBlockClient.fpasClips();
        BedrockClip open = clips.get("transitionopen");
        BedrockClip close = clips.get("transitionclose");
        BedrockClip spin = clips.get("spin");
        BoneState radar = instance.getBone("radar");
        if (radar != null && open != null && close != null && spin != null) {
            float now = be.getLevel().getGameTime() + partialTick;
            State s = states.computeIfAbsent(be, k -> new State(
                    new FpasPose(open.length() * 20.0F, close.length() * 20.0F, spinRate(spin), be.powered()), now));
            float dt = Mth.clamp(now - s.last, 0.0F, 20.0F);
            s.last = now;

            Vec3 aim = be.mode() == ZoneBeaconBlockEntity.Mode.MTI ? be.aim() : null;
            float bearing = Float.NaN;
            if (aim != null) {
                double dx = aim.x - (be.getBlockPos().getX() + 0.5);
                double dz = aim.z - (be.getBlockPos().getZ() + 0.5);
                // Ry(t) takes the -Z rest boresight to (-sin t, -cos t).
                bearing = (float) Math.toDegrees(Math.atan2(-dx, -dz));
            }
            s.pose.step(be.powered(), bearing, dt);

            radar.rotation.mul(Axis.YP.rotationDegrees(s.pose.azimuth()));
            switch (s.pose.phase()) {
                case OPENING -> applyClip(instance, open, s.pose.clipTime() / 20.0F);
                case CLOSING -> applyClip(instance, close, s.pose.clipTime() / 20.0F);
                case CLOSED -> applyClip(instance, close, close.length());
                default -> {
                }
            }
            sounds(be, s, s.pose.azSlewing(aim != null), s.pose.hydraulicsMoving());
        }

        poseStack.pushPose();
        poseStack.translate(0.5D, 0.0D, 0.5D);
        FobBlockClient.render(instance, FobBlockClient.Kind.FPAS, poseStack, buffer, packedLight, packedOverlay);
        poseStack.popPose();
    }

    /** Degrees per tick, signed as the baked Y rotation. */
    private static float spinRate(BedrockClip spin) {
        float[] end = spin.sample("radar", BedrockClip.Channel.ROTATION, spin.length());
        return end == null || spin.length() <= 0.0F ? 0.0F : -end[1] / (spin.length() * 20.0F);
    }

    private static void applyClip(BakedModelInstance instance, BedrockClip clip, float seconds) {
        for (String name : CLIP_BONES) {
            BoneState bone = instance.getBone(name);
            if (bone == null) continue;
            float[] r = clip.sample(name, BedrockClip.Channel.ROTATION, seconds);
            if (r != null) {
                bone.rotation.mul(new Quaternionf().rotateZYX(
                        r[2] * Mth.DEG_TO_RAD, -r[1] * Mth.DEG_TO_RAD, -r[0] * Mth.DEG_TO_RAD));
            }
            float[] p = clip.sample(name, BedrockClip.Channel.POSITION, seconds);
            if (p != null) {
                bone.x -= p[0];
                bone.y += p[1];
                bone.z += p[2];
            }
        }
    }

    private static void sounds(ZoneBeaconBlockEntity be, State s, boolean azMoving, boolean hydraulics) {
        SoundManager manager = Minecraft.getInstance().getSoundManager();
        if (azMoving && (s.azLoop == null || s.azLoop.isStopped())) {
            s.azLoop = new Loop(ModSounds.FPAS_AZIMUTH.get(), be, s, true, 0.8F);
            manager.play(s.azLoop);
        }
        if (hydraulics && (s.hydraulicsLoop == null || s.hydraulicsLoop.isStopped())) {
            s.hydraulicsLoop = new Loop(ModSounds.FPAS_HYDRAULICS.get(), be, s, false, 0.6F);
            manager.play(s.hydraulicsLoop);
        }
        if ((s.azMoving && !azMoving) || (s.hydraulics && !hydraulics)) {
            Vec3 at = Vec3.atBottomCenterOf(be.getBlockPos()).add(0.0, 2.0, 0.0);
            manager.play(new SimpleSoundInstance(ModSounds.FPAS_STOP.get(), SoundSource.BLOCKS, 1.0F, 1.0F,
                    SoundInstance.createUnseededRandom(), at.x, at.y, at.z));
        }
        s.azMoving = azMoving;
        s.hydraulics = hydraulics;
    }

    /** One motor loop; stops itself once that motion ends or the FPAS is no longer drawn. */
    private static final class Loop extends AbstractTickableSoundInstance {
        private final ZoneBeaconBlockEntity be;
        private final State state;
        private final boolean azimuth;

        Loop(SoundEvent event, ZoneBeaconBlockEntity be, State state, boolean azimuth, float volume) {
            super(event, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
            this.be = be;
            this.state = state;
            this.azimuth = azimuth;
            this.looping = true;
            this.delay = 0;
            this.volume = volume;
            Vec3 at = Vec3.atBottomCenterOf(be.getBlockPos()).add(0.0, 2.0, 0.0);
            this.x = at.x;
            this.y = at.y;
            this.z = at.z;
        }

        @Override
        public void tick() {
            boolean moving = this.azimuth ? this.state.azMoving : this.state.hydraulics;
            if (this.be.isRemoved() || this.be.getLevel() == null || !moving
                    || this.be.getLevel().getGameTime() - this.state.last > LOOP_GRACE_TICKS) {
                stop();
            }
        }
    }
}
