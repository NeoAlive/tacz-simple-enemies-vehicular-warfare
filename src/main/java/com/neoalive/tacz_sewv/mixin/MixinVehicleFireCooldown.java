package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
}
