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

import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Mounted-crew target callouts and SEM shout muting while in a hull. On foot, SEM's own voicelines
 * run untouched — this mixin must not speak for infantry.
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
        // Mounted only — on-foot infantry keep SEM's alert shouts.
        if (!(self.getVehicle() instanceof VehicleEntity hull)) return;
        if (!CrewRadio.enabled()) return;
        if (VehicleTargeting.isFriendly(self, newTarget)) return;
        LivingEntity old = self.getTarget();
        if (old == newTarget) return;
        // Already locked onto something: only re-announce when the previous lock was cleared.
        if (old != null && old.isAlive()) return;

        CrewRadio.play(hull, tacz_sewv$targetLine(newTarget));
    }

    @Redirect(method = "setTarget",
            at = @At(value = "INVOKE",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/entity/unit/AbstractUnit;playSound(Lnet/minecraft/sounds/SoundEvent;FF)V"))
    private void tacz_sewv$muteAlert(AbstractUnit self, SoundEvent sound, float volume, float pitch) {
        // On foot / feature off: SEM's own alert shout. In a hull with radio on: muted.
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

    /** In a hull with the feature on: mute SEM's line. On foot or disabled: leave it be. */
    @Unique
    private boolean tacz_sewv$radio() {
        return CrewRadio.enabled()
                && ((AbstractUnit) (Object) this).getVehicle() instanceof VehicleEntity;
    }
}
