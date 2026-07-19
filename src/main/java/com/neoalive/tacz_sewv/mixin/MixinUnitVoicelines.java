package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.init.ModSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes a vehicle crew speak as a vehicle: one voice on the radio instead of a hull full of men
 * shouting over each other.
 *
 * <p>SimpleEnemyMod's three voicelines (hurt, death, alert) are per-unit with per-unit cooldowns
 * and no squad-level throttle of any kind — the death line has no cooldown at all. So a shell
 * that kills a loaded troop carrier fires one death line <b>per rider</b>, at the same instant,
 * from the same block. Beyond the overlap, a shouted infantry callout is the wrong sound to hear
 * coming out of a closed vehicle in the first place, which is why the fix is a substitution
 * rather than just a mute.
 *
 * <h2>The rule, applied identically by all three injections</h2>
 * <ul>
 *   <li><b>On foot</b> — do nothing at all. SEM's own line plays exactly as before.
 *   <li><b>A passenger, but not the driver</b> — silent.
 *   <li><b>The driver</b> — speaks for the whole vehicle, with a radio sound.
 * </ul>
 *
 * <p>The radio pools are per faction: RU has its own, and <b>PMC uses the US pool</b> (no PMC lines
 * were recorded). Only two pools exist — {@code damaged} for being hit and {@code identified} for
 * spotting a target — so <b>death is silent inside a vehicle</b>, which is also the most complete
 * answer to the overlap this class was written for.
 * The driver is {@code getFirstPassenger()}, this codebase's established "who owns this hull"
 * idiom (see {@code VehicleWeapons}, {@code DriveVehicleGoal}, {@code MixinVehicleFactionEnergy}),
 * and it needs no new state or timers: seat order is stable because {@code TankSpawner} mounts in
 * order, so the designated speaker does not flicker between ticks.
 *
 * <h2>Why three injections and not one</h2>
 * All three lines do funnel through {@code Entity#playSound}, and a single mixin there was the
 * obvious move — but it is wrong twice over. It sits on a path walked by every entity in the game
 * for every footstep and splash, and filtering it back down to voicelines needs a namespace test
 * on the {@code SoundEvent} — which <b>silently misses PMC units entirely</b>, because their
 * voicelines are vanilla {@code VILLAGER_*} sounds, not {@code simpleenemymod:} ones. These three
 * seams cost one extra injection and are exact.
 *
 * <p>Two of them are vanilla hooks ({@code getHurtSound}/{@code getDeathSound}) that SEM overrides,
 * so {@code remap} stays on. Returning null from either is safe — vanilla null-guards both call
 * sites. The alert line is not a vanilla hook: SEM calls {@code playSound} directly inside
 * {@code setTarget}, so that one needs a {@link Redirect}. Injecting at HEAD also skips SEM's
 * {@code lastHurtSoundTick} bookkeeping, which is harmless: that timestamp only throttles a line
 * we are replacing outright.
 */
@Mixin(AbstractUnit.class)
public abstract class MixinUnitVoicelines {

    @Inject(method = "getHurtSound", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$radioHurtSound(DamageSource source, CallbackInfoReturnable<SoundEvent> cir) {
        tacz_sewv$substitute(cir, tacz_sewv$damagedLine());
    }

    /**
     * Death is <b>silent</b> inside a vehicle — for the speaker as much as for everyone else.
     *
     * <p>Not an oversight: there is no death audio, only the damaged/identified pools. Passing null
     * here mutes the whole hull, which is also the cleanest possible answer to the original
     * complaint (a shell on a loaded carrier used to fire one death line per rider at once). The
     * damaged line already covers the crew being hit on the way down.
     */
    @Inject(method = "getDeathSound", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$radioDeathSound(CallbackInfoReturnable<SoundEvent> cir) {
        if (!tacz_sewv$inVehicle()) return; // on foot: SEM's own death cry, untouched
        cir.setReturnValue(null);
    }

    /**
     * The alert ("contact") line, which SEM plays directly rather than through a vanilla hook.
     *
     * <p>Redirected rather than injected because {@code MixinAbstractUnit} already owns
     * {@code setTarget}'s HEAD for the friendly-fire gate, and cancelling there would suppress the
     * targeting itself rather than just the shout. Not calling through is the mute.
     *
     * <p>The target owner is <b>AbstractUnit, not Entity</b>. SEM writes {@code this.playSound(...)},
     * and javac emits the invocation against the receiver's compile-time type — so the bytecode
     * says {@code AbstractUnit.playSound}, and naming the declaring class instead fails the
     * injection outright.
     */
    @Redirect(method = "setTarget",
            at = @At(value = "INVOKE",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/entity/unit/AbstractUnit;playSound(Lnet/minecraft/sounds/SoundEvent;FF)V"))
    private void tacz_sewv$radioAlertSound(AbstractUnit self, SoundEvent sound, float volume, float pitch) {
        if (!tacz_sewv$inVehicle()) {
            self.playSound(sound, volume, pitch); // on foot: SEM's line, untouched
            return;
        }
        if (!tacz_sewv$isSpeaker()) return; // a passenger who isn't the driver stays off the net
        self.playSound(tacz_sewv$identifiedLine(), volume, pitch);
    }

    /**
     * The faction's "we're hit" and "target identified" lines.
     *
     * <p>RU has its own recordings; US and <b>PMC share the US pool</b> — no PMC lines exist, and a
     * PMC crew reading as western is the intended stand-in rather than a gap. Selected per call
     * rather than cached because it costs one instanceof and the unit's class never changes anyway.
     */
    @Unique
    private SoundEvent tacz_sewv$damagedLine() {
        return tacz_sewv$isRu() ? ModSounds.RADIO_RU_DAMAGED.get() : ModSounds.RADIO_US_DAMAGED.get();
    }

    @Unique
    private SoundEvent tacz_sewv$identifiedLine() {
        return tacz_sewv$isRu() ? ModSounds.RADIO_RU_IDENTIFIED.get() : ModSounds.RADIO_US_IDENTIFIED.get();
    }

    @Unique
    private boolean tacz_sewv$isRu() {
        return (Object) this instanceof RUunitEntity;
    }

    /** Mute, substitute, or leave alone — the shared decision for the two vanilla hooks. */
    @Unique
    private void tacz_sewv$substitute(CallbackInfoReturnable<SoundEvent> cir, SoundEvent radio) {
        if (!tacz_sewv$inVehicle()) return;
        // Vanilla null-guards both call sites (`if (soundevent != null)`), so null is a clean mute.
        cir.setReturnValue(tacz_sewv$isSpeaker() ? radio : null);
    }

    @Unique
    private boolean tacz_sewv$inVehicle() {
        return SewvConfig.VEHICLE_VOICELINES_ENABLED.get()
                && ((AbstractUnit) (Object) this).getVehicle() instanceof VehicleEntity;
    }

    /** One voice per hull: the driver speaks, everyone else is silent. */
    @Unique
    private boolean tacz_sewv$isSpeaker() {
        AbstractUnit self = (AbstractUnit) (Object) this;
        Entity vehicle = self.getVehicle();
        return vehicle != null && vehicle.getFirstPassenger() == self;
    }
}
