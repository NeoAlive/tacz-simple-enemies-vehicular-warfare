package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.VehicleWeapons;
import com.neoalive.tacz_sewv.util.CrewRadio;
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

/**
 * The crew-event radio lines, all on {@code AbstractUnit}. In a hull, SEM's own shouted voicelines
 * are muted (a closed vehicle shouldn't shout like a field of infantry) and the "contact" callout is
 * emitted as radio traffic instead:
 * <ul>
 *   <li>hurt/death shout -- muted via the vanilla sound hooks;
 *   <li>alert shout -- muted via a {@link Redirect} of the bare {@code this.playSound(...)} in
 *       {@code setTarget} (its target owner is <b>AbstractUnit</b>, the receiver's compile-time type);
 *   <li><b>SPOTTED</b> -- played when any crewman's target first becomes an enemy vehicle. This hooks
 *       {@code setTarget} itself, not SEM's alert (capped at one per 200 ticks) nor the target scan
 *       (off under a player's ATTACK_THAT_TARGET order), so it catches every path a vehicle lock
 *       arrives through. Edge-detected against the old target so it fires on acquisition, not while
 *       engaging, and re-checks {@code isFriendly} so it is independent of the friendly-fire cancel's
 *       injection order in {@code MixinAbstractUnit}.
 * </ul>
 * "We're hit" is emitted separately from the hull's health ({@link MixinVehicleVoicelines}). On foot
 * (or with the feature off) SEM's own lines are untouched.
 */
@Mixin(AbstractUnit.class)
public abstract class MixinUnitVoicelines {

    @Inject(method = "getHurtSound", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$muteHurt(DamageSource source, CallbackInfoReturnable<SoundEvent> cir) {
        if (tacz_sewv$radio()) cir.setReturnValue(null); // vanilla null-guards the call site
    }

    @Inject(method = "getDeathSound", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$muteDeath(CallbackInfoReturnable<SoundEvent> cir) {
        if (tacz_sewv$radio()) cir.setReturnValue(null);
    }

    @Inject(method = "setTarget", at = @At("HEAD"))
    private void tacz_sewv$spotted(LivingEntity newTarget, CallbackInfo ci) {
        if (newTarget == null) return;
        AbstractUnit self = (AbstractUnit) (Object) this;
        if (!(self.getVehicle() instanceof VehicleEntity hull)) return; // on foot: not our concern
        if (VehicleTargeting.isFriendly(self, newTarget)) return;       // friendly targets get cancelled anyway
        if (VehicleWeapons.classifyTarget(newTarget) != VehicleWeapons.TargetCategory.VEHICLE) return;
        LivingEntity old = self.getTarget();
        if (old != null && VehicleWeapons.classifyTarget(old) == VehicleWeapons.TargetCategory.VEHICLE) return; // already on armour
        CrewRadio.play(hull, CrewRadio.Line.SPOTTED);
    }

    @Redirect(method = "setTarget",
            at = @At(value = "INVOKE",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/entity/unit/AbstractUnit;playSound(Lnet/minecraft/sounds/SoundEvent;FF)V"))
    private void tacz_sewv$muteAlert(AbstractUnit self, SoundEvent sound, float volume, float pitch) {
        // On foot / disabled: SEM's own alert shout. In a hull: muted -- SPOTTED covers "contact".
        if (!tacz_sewv$radio()) self.playSound(sound, volume, pitch);
    }

    /** In a hull with the feature on: mute SEM's line. On foot or disabled: leave it be. */
    @Unique
    private boolean tacz_sewv$radio() {
        return SewvConfig.VEHICLE_VOICELINES_ENABLED.get()
                && ((AbstractUnit) (Object) this).getVehicle() instanceof VehicleEntity;
    }
}
