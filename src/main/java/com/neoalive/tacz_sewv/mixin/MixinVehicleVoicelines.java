package com.neoalive.tacz_sewv.mixin;

import java.util.UUID;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;

/**
 * Hull-event voicelines: hits taken and landed, component damage, low health, kills, smoke, fire
 * callouts on actual AI shots and the gun coming back up after a reload.
 */
@Mixin(VehicleEntity.class)
public abstract class MixinVehicleVoicelines {

    /** Game time of the last AI cannon shot, MIN when the gun-ready call has been made. */
    @Unique
    private long tacz_sewv$cannonFiredAt = Long.MIN_VALUE;

    @Inject(method = "onHurt", at = @At("TAIL"), remap = false)
    private void tacz_sewv$hurtVoice(float amount, Entity attacker, boolean bl, CallbackInfo ci) {
        if (!CrewRadio.enabled()) return;
        VehicleEntity hull = (VehicleEntity) (Object) this;
        if (hull.level().isClientSide) return;
        CrewRadio.onHullHurt(hull, amount);
        if (attacker == null || hull.getHealth() <= 0) return;
        VehicleEntity shooter = CrewRadio.crewedHullOf(attacker);
        if (shooter != null && shooter != hull) CrewRadio.onHitLanded(shooter, CrewRadio.penetrated(hull, amount));
    }

    @Inject(method = "destroy", at = @At("HEAD"), remap = false)
    private void tacz_sewv$destroyedVoice(CallbackInfo ci) {
        if (!CrewRadio.enabled()) return;
        VehicleEntity hull = (VehicleEntity) (Object) this;
        if (hull.level().isClientSide) return;
        CrewRadio.onHullDestroyed(hull, hull.getLastAttacker());
    }

    /** Launcher count when releaseSmokeDecoy was entered; -1 when the input was not held. */
    @Unique
    private int tacz_sewv$decoysBefore = -1;

    /**
     * SBW calls releaseSmokeDecoy every tick for any hull with smoke and checks the input inside, so
     * entering it means nothing. Only a drop in the launcher count is a volley.
     */
    @Inject(method = "releaseSmokeDecoy", at = @At("HEAD"), remap = false)
    private void tacz_sewv$smokeBefore(Vec3 vec3, CallbackInfo ci) {
        VehicleEntity hull = (VehicleEntity) (Object) this;
        this.tacz_sewv$decoysBefore = hull.decoyInputDown() ? hull.getDecoyCount() : -1;
    }

    @Inject(method = "releaseSmokeDecoy", at = @At("RETURN"), remap = false)
    private void tacz_sewv$smokeVoice(Vec3 vec3, CallbackInfo ci) {
        int before = this.tacz_sewv$decoysBefore;
        this.tacz_sewv$decoysBefore = -1;
        if (before <= 0 || !CrewRadio.enabled()) return;
        VehicleEntity hull = (VehicleEntity) (Object) this;
        if (!hull.level().isClientSide && hull.getDecoyCount() < before) CrewRadio.onSmoke(hull);
    }

    /** Same edge for flares: SBW calls releaseDecoy every tick for a hull that carries them. */
    @Unique
    private int tacz_sewv$flaresBefore = -1;

    @Inject(method = "releaseDecoy", at = @At("HEAD"), remap = false)
    private void tacz_sewv$flaresBefore(CallbackInfo ci) {
        VehicleEntity hull = (VehicleEntity) (Object) this;
        this.tacz_sewv$flaresBefore = hull.decoyInputDown() ? hull.getDecoyCount() : -1;
    }

    @Inject(method = "releaseDecoy", at = @At("RETURN"), remap = false)
    private void tacz_sewv$flaresVoice(CallbackInfo ci) {
        int before = this.tacz_sewv$flaresBefore;
        this.tacz_sewv$flaresBefore = -1;
        if (before <= 0 || !CrewRadio.enabled()) return;
        VehicleEntity hull = (VehicleEntity) (Object) this;
        if (!hull.level().isClientSide && hull.getDecoyCount() < before) CrewRadio.onSmoke(hull);
    }

    /**
     * Fire callout on a real launch. Hooks VehicleEntity's shoot so cancelled HEAD gates never
     * announce, and so native SBW AI fire (not only tryAiFireAssist) is covered. Player shooters
     * are ignored.
     */
    @Inject(
            method = "vehicleShoot(Lnet/minecraft/world/entity/LivingEntity;Ljava/util/UUID;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("TAIL"), remap = false)
    private void tacz_sewv$shootVoice(LivingEntity shooter, UUID uuid, Vec3 targetPos, CallbackInfo ci) {
        if (!CrewRadio.enabled()) return;
        if (!(shooter instanceof AbstractUnit unit)) return;
        VehicleEntity hull = (VehicleEntity) (Object) this;
        if (unit.getVehicle() != hull) return;
        int seat = hull.getSeatIndex(unit);
        if (seat < 0) return;
        int role = VehicleWeapons.roleOfCurrentWeapon(hull, seat);
        if (role == VehicleWeapons.WEAPON_CANNON) this.tacz_sewv$cannonFiredAt = hull.level().getGameTime();
        if (role == VehicleWeapons.WEAPON_MG || role == VehicleWeapons.WEAPON_CANNON) {
            CrewRadio.playShoot(hull, role);
        }
    }

    /**
     * Gun up: the first time the cannon seat that fired can shoot again. SBW asks canShoot for
     * every mob seat every tick, so the stamp check comes first and the slot test only runs once
     * per reload.
     */
    @Inject(method = "canShoot", at = @At("RETURN"), remap = false)
    private void tacz_sewv$gunReadyVoice(LivingEntity living, CallbackInfoReturnable<Boolean> cir) {
        if (this.tacz_sewv$cannonFiredAt == Long.MIN_VALUE || !cir.getReturnValueZ()) return;
        if (!(living instanceof AbstractUnit unit)) return;
        VehicleEntity hull = (VehicleEntity) (Object) this;
        if (hull.level().getGameTime() - this.tacz_sewv$cannonFiredAt < CrewRadio.gunReadyMinTicks()) return;
        int seat = hull.getSeatIndex(unit);
        if (seat < 0 || VehicleWeapons.roleOfCurrentWeapon(hull, seat) != VehicleWeapons.WEAPON_CANNON) return;
        this.tacz_sewv$cannonFiredAt = Long.MIN_VALUE;
        CrewRadio.gunReady(hull);
    }
}
