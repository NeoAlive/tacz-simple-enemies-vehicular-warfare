package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Crew target callouts and SEM shout muting while mounted. Target lines classify the enemy hull
 * (heli / plane / ship / tank) or fall back to generic for infantry and other cases.
 */
@Mixin(AbstractUnit.class)
public abstract class MixinUnitVoicelines {

    @Inject(method = "getHurtSound", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$muteHurt(DamageSource source, CallbackInfoReturnable<SoundEvent> cir) {
        if (tacz_sewv$radio()) cir.setReturnValue(null);
    }

    @Inject(method = "getDeathSound", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$muteDeath(CallbackInfoReturnable<SoundEvent> cir) {
        if (tacz_sewv$radio()) cir.setReturnValue(null);
    }

    @Inject(method = "setTarget", at = @At("HEAD"))
    private void tacz_sewv$targetVoice(LivingEntity newTarget, CallbackInfo ci) {
        if (newTarget == null) return;
        AbstractUnit self = (AbstractUnit) (Object) this;
        if (VehicleTargeting.isFriendly(self, newTarget)) return;
        LivingEntity old = self.getTarget();
        if (old == newTarget) return;
        // Already locked onto something: only re-announce when the previous lock was cleared.
        if (old != null && old.isAlive()) return;

        CrewRadio.Line line = tacz_sewv$targetLine(newTarget);
        if (self.getVehicle() instanceof VehicleEntity hull) {
            CrewRadio.play(hull, line);
        } else {
            CrewRadio.speakUnit(self, line);
        }
    }

    @Redirect(method = "setTarget",
            at = @At(value = "INVOKE",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/entity/unit/AbstractUnit;playSound(Lnet/minecraft/sounds/SoundEvent;FF)V"))
    private void tacz_sewv$muteAlert(AbstractUnit self, SoundEvent sound, float volume, float pitch) {
        if (!tacz_sewv$radio()) self.playSound(sound, volume, pitch);
    }

    @Unique
    private static CrewRadio.Line tacz_sewv$targetLine(LivingEntity target) {
        if (target.getVehicle() instanceof VehicleEntity v) {
            if (HullFacts.isHelicopterHull(v)) return CrewRadio.Line.TARGET_HELICOPTER;
            if (HullFacts.isPlaneHull(v)) return CrewRadio.Line.TARGET_PLANE;
            if (HullFacts.isShipHull(v)) return CrewRadio.Line.TARGET_SHIP;
            if (HullFacts.isGroundMobileHull(v) && !HullFacts.isIfvHull(v)) {
                return CrewRadio.Line.TARGET_TANK;
            }
        }
        return CrewRadio.Line.TARGET_GENERIC;
    }

    @Unique
    private boolean tacz_sewv$radio() {
        return SewvConfig.VEHICLE_VOICELINES_ENABLED.get()
                && ((AbstractUnit) (Object) this).getVehicle() instanceof VehicleEntity;
    }
}
