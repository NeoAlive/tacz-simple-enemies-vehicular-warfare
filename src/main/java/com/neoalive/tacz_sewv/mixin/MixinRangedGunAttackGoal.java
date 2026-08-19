package com.neoalive.tacz_sewv.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.RangedGunAttackGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
import com.neoalive.tacz_sewv.entity.ai.navigation.VehiclePathObstacles;

/**
 * Stops a mounted crew member from firing its hand-held gun with SEM's
 * {@link RangedGunAttackGoal}. That goal's canUse only checks target + gun-in-hand
 * + range — it never notices the unit is crewing a vehicle, so a driver or gunner
 * would lean out and shoot its rifle instead of working the vehicle's weapons.
 *
 * <p>The gate is {@link VehicleWeapons#controlsVehicleWeapon}: drivers and armed
 * gunners are suppressed; pure passengers (no seat weapons) are excluded and keep
 * the goal, so a trooper riding in the back can still shoot out.
 *
 * <p>The fire LOS gate ({@code hasLineOfSightToTarget}) is a block clip of its own
 * — it does not call {@code hasLineOfSight} — so hull occlusion is applied here
 * as well, or an already-started burst keeps dumping into a tank in the way.
 */
@Mixin(RangedGunAttackGoal.class)
public abstract class MixinRangedGunAttackGoal {

    // Mod-declared field: its name is stable across dev/production, so don't remap it.
    @Shadow(remap = false)
    @Final
    private Mob mob;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$blockGunWhenCrewingVehicle(CallbackInfoReturnable<Boolean> cir) {
        if (VehicleWeapons.controlsVehicleWeapon(this.mob)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "canContinueToUse", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$stopGunWhenCrewingVehicle(CallbackInfoReturnable<Boolean> cir) {
        if (VehicleWeapons.controlsVehicleWeapon(this.mob)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "hasLineOfSightToTarget", at = @At("RETURN"), cancellable = true, remap = false)
    private void tacz_sewv$hullsBlockGunLos(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) return;
        if (VehiclePathObstacles.occludesLos(this.mob, target)) {
            cir.setReturnValue(false);
        }
    }
}
