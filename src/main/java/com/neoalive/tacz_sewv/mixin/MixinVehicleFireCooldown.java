package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.SmokeVision;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
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

    // Hold fire while the line of fire is blocked — by terrain or by smoke. Gate at
    // canShoot() — the whole AI fire block (aim + every weapon's vehicleShoot,
    // cannon/MG/special, plus each tank's cannon muzzle-flash override) hangs off
    // this check, so denying it here suppresses the shot AND its flash for all
    // weapons, upstream of the subclass overrides. Turret auto-aim is independent,
    // so the barrel keeps tracking; fire resumes the instant the line clears.
    // Players aim manually and aren't AbstractUnit, so they're untouched.
    //
    // Terrain: SBW's AI fire loop shoots whenever the weapon bears within its aim
    // cone and never asks what's between the muzzle and the target, so crews hose
    // the wall a target is hiding behind. This gate also covers targets that never
    // went through the scan goal's acquisition LOS check (SEM retaliation,
    // ATTACK_THAT_TARGET orders). Smoke: SmokeDecoyEntity has no collision, so a
    // block raycast alone would keep landing shots straight through the cloud.
    @Inject(method = "canShoot", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$holdFireWhenLineBlocked(
            LivingEntity living, CallbackInfoReturnable<Boolean> cir) {

        if (!(living instanceof AbstractUnit unit)) return;
        LivingEntity target = unit.getTarget();
        if (target == null) return;

        VehicleEntity self = (VehicleEntity) (Object) this;
        Vec3 from = self.getShootPos(living, 1f);            // muzzle
        Vec3 to = target.getBoundingBox().getCenter();       // target center

        if (SewvConfig.VEHICLE_TARGET_REQUIRE_LOS.get()
                && tacz_sewv$terrainBlocksLine(self, from, target, to)) {
            cir.setReturnValue(false);
            return;
        }
        if (SmokeVision.lineBlockedBySmoke(self.level(), self, from, to, SewvConfig.SMOKE_BLOCK_RADIUS.get())) {
            cir.setReturnValue(false);
        }
    }

    // Blocked only when BOTH the target's center and its eyes are behind blocks:
    // a target peeking over low cover (sandbags, fences) has an exposed head a
    // direct-fire shot can genuinely reach, so the second raycast keeps those
    // engagements alive instead of silencing the guns against half cover.
    @Unique
    private static boolean tacz_sewv$terrainBlocksLine(
            VehicleEntity self, Vec3 from, LivingEntity target, Vec3 center) {
        if (self.level().clip(new ClipContext(from, center,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self))
                .getType() == HitResult.Type.MISS) {
            return false;
        }
        return self.level().clip(new ClipContext(from, target.getEyePosition(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self))
                .getType() != HitResult.Type.MISS;
    }
}
