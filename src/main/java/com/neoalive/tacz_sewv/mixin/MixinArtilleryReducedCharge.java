package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.ArtilleryEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.neoalive.tacz_sewv.entity.ai.support.ArtillerySupport;

/**
 * Fires an AI artillery crew's shell at the reduced charge its lay was solved for.
 *
 * <p>{@code GunItem.shootBullet} reads {@code GunProp.VELOCITY} into a local and hands it to
 * {@code Projectile.shoot(x, y, z, velocity, spread)}; that call is the only place the speed
 * becomes the shell's motion ({@code ShootParameters} has no velocity, and the shell constructor
 * takes none). Only the speed is swapped — the direction is SBW's barrel vector, already laid by
 * {@code MixinArtilleryShootVec}/{@code MixinVehicleMissileAim}. This mixin consumes
 * {@link ArtillerySupport#chargeFor}; it computes nothing. A player gunner, a crew with no fire
 * work, or a lay with no reduced charge ({@code chargeFor == null}) keeps SBW's full charge.
 *
 * <p>Distinct instruction from {@link MixinAiAimSpread}'s redirect in the same method, so the two
 * do not conflict. {@code shootBullet} is SBW's (literal name); {@code Projectile.shoot} is vanilla.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.item.gun.GunItem")
public abstract class MixinArtilleryReducedCharge {

    @Redirect(
            method = "shootBullet(Lcom/atsuishio/superbwarfare/data/gun/ShootParameters;)Z",
            at = @At(value = "INVOKE", remap = true,
                    target = "Lnet/minecraft/world/entity/projectile/Projectile;shoot(DDDFF)V"),
            remap = false)
    private void tacz_sewv$reducedCharge(Projectile shell, double x, double y, double z,
                                         float velocity, float inaccuracy) {
        if (shell.getOwner() instanceof AbstractUnit gunner
                && gunner.getVehicle() instanceof ArtilleryEntity hull
                && hull.getNthEntity(hull.getTurretControllerIndex()) == gunner
                && ArtillerySupport.isLaying(hull)) {
            Float charge = ArtillerySupport.chargeFor(hull);
            if (charge != null) velocity = charge;
        }
        shell.shoot(x, y, z, velocity, inaccuracy);
    }
}
