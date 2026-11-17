package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.ai.TowerAI;
import com.atsuishio.superbwarfare.entity.vehicle.base.AutoAimableEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.entity.ai.support.TowerFriends;

/**
 * SBW's auto turret target test. Units on the owner's side are not hostile, and neither are their
 * projectiles, so a CIWS neither shoots its owner's PMCs nor intercepts their rockets.
 */
@Mixin(value = TowerAI.TeamResolver.class, remap = false)
public abstract class MixinTowerFriendlyUnits {

    @Inject(method = "isHostile", at = @At("HEAD"), cancellable = true, require = 0)
    private static void tacz_sewv$spareUnits(AutoAimableEntity tower, Entity target,
            CallbackInfoReturnable<Boolean> cir) {
        if (TowerFriends.spares(tower, target)) cir.setReturnValue(false);
    }

    @Inject(method = "isHostileProjectile", at = @At("HEAD"), cancellable = true, require = 0)
    private static void tacz_sewv$spareUnitProjectiles(AutoAimableEntity tower, Projectile projectile,
            CallbackInfoReturnable<Boolean> cir) {
        if (TowerFriends.spares(tower, projectile.getOwner())) cir.setReturnValue(false);
    }
}
