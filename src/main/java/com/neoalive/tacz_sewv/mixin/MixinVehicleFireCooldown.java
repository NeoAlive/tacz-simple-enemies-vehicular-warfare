package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.SmokeVision;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleFireCooldown {

    @Unique
    private long tacz_sewv$lastAiShotTick = 0;

    @Inject(method = "vehicleShoot", at = @At("HEAD"), cancellable = true, remap = false)
private void tacz_sewv$throttleAiFire(
        LivingEntity living, String weaponName,
        CallbackInfo ci) {

    if (!(living instanceof AbstractUnit)) return;

    // CEASE_FIRE order holds the crew's fire entirely — the vehicle still crews
    // and repositions, it just doesn't shoot. Only PmcUnitEntity has orders.
    if (living instanceof PmcUnitEntity pmc && pmc.getOrder() == OrderType.CEASE_FIRE) {
        ci.cancel();
        return;
    }

    VehicleEntity self = (VehicleEntity) (Object) this;
    long now = self.level().getGameTime();

    if (now - this.tacz_sewv$lastAiShotTick < SewvConfig.AI_FIRE_COOLDOWN_TICKS.get()) {
        ci.cancel();
        return;
    }

    this.tacz_sewv$lastAiShotTick = now;
}

    // Smoke screens are otherwise cosmetic: SmokeDecoyEntity has no collision, so
    // block-based line-of-sight ignores it and the AI keeps landing shots straight
    // through the cloud. Gate at canShoot() — the whole AI fire block (aim + every
    // weapon's vehicleShoot, cannon/MG/special, plus each tank's cannon muzzle-flash
    // override) hangs off this check, so denying it here suppresses the shot AND its
    // flash for all weapons, upstream of the subclass overrides. Turret auto-aim is
    // independent, so the barrel keeps tracking; fire resumes the instant the line
    // clears. Players aim manually and aren't AbstractUnit, so they're untouched.
    @Inject(method = "canShoot", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$smokeBlocksShooting(
            LivingEntity living, CallbackInfoReturnable<Boolean> cir) {

        if (!(living instanceof AbstractUnit unit)) return;
        LivingEntity target = unit.getTarget();
        if (target == null) return;

        VehicleEntity self = (VehicleEntity) (Object) this;
        Vec3 from = self.getShootPos(living, 1f);            // muzzle
        Vec3 to = target.getBoundingBox().getCenter();       // target center
        if (SmokeVision.lineBlockedBySmoke(self.level(), self, from, to, SewvConfig.SMOKE_BLOCK_RADIUS.get())) {
            cir.setReturnValue(false);
        }
    }
}
