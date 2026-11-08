package com.neoalive.tacz_sewv.mixin.perf;

import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.client.perf.VehicleClientLod;
import com.neoalive.tacz_sewv.config.PerfFlags;

/**
 * Client-side SBW vehicle simulation thinning (see {@link VehicleClientLod}). Client mixins also
 * patch the integrated server's copy of the class, so every hook checks the side first.
 */
@Mixin(value = VehicleEntity.class, remap = false)
public abstract class MixinVehicleClientSim {

    private static final int FAR_INTERPOLATION_STEPS = 3;
    private static final int FAR_TERRAIN_STRIDE = 4;

    @Shadow(remap = false)
    private int interpolationSteps;

    @Inject(method = "move", at = @At("HEAD"), cancellable = true, remap = true, require = 0)
    private void tacz_sewv$skipClientPrediction(MoverType type, Vec3 motion, CallbackInfo ci) {
        VehicleEntity self = (VehicleEntity) (Object) this;
        if (!self.level().isClientSide() || type != MoverType.SELF) return;
        if (!VehicleClientLod.skipMove(self)) return;
        Vec3 delta = self.getDeltaMovement();
        if (delta.y != 0.0) {
            self.setDeltaMovement(delta.x, 0.0, delta.z);
        }
        ci.cancel();
    }

    @Inject(method = "lerpTo", at = @At("RETURN"), remap = true, require = 0)
    private void tacz_sewv$tightFarLerp(double x, double y, double z, float yRot, float xRot, int steps,
                                        boolean teleport, CallbackInfo ci) {
        VehicleEntity self = (VehicleEntity) (Object) this;
        if (!self.level().isClientSide() || VehicleClientLod.isLocal(self)) return;
        if (interpolationSteps > FAR_INTERPOLATION_STEPS && VehicleClientLod.far(self)) {
            interpolationSteps = FAR_INTERPOLATION_STEPS;
        }
    }

    @Inject(method = "terrainCompact", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void tacz_sewv$throttleFarTerrain(List<Vec3> points, CallbackInfo ci) {
        VehicleEntity self = (VehicleEntity) (Object) this;
        if (!self.level().isClientSide()) return;
        if ((self.tickCount + self.getId()) % FAR_TERRAIN_STRIDE == 0) return;
        if (!VehicleClientLod.isLocal(self) && VehicleClientLod.far(self)) {
            ci.cancel();
        }
    }

    @Redirect(
            method = "turretTurnSound",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V",
                    remap = true),
            remap = false,
            require = 0)
    private void tacz_sewv$cullFarTurnSound(Level level, double x, double y, double z, SoundEvent sound,
                                            SoundSource source, float volume, float pitch, boolean delayed) {
        if (VehicleClientLod.beyondListener(x, y, z, PerfFlags.vehicleTurnSoundDistance())) return;
        level.playLocalSound(x, y, z, sound, source, volume, pitch, delayed);
    }
}
