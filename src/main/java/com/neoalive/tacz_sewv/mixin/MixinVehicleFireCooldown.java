package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleFireCooldown {

    @Unique
    private long tacz_sewv$lastAiShotTick = 0;

    @Unique
    private static final long tacz_sewv$AI_FIRE_COOLDOWN = 25; // ~1.25s between AI shots

    @Inject(method = "vehicleShoot", at = @At("HEAD"), cancellable = true, remap = false)
private void tacz_sewv$throttleAiFire(
        LivingEntity living, String weaponName,
        CallbackInfo ci) {

    if (!(living instanceof AbstractUnit)) return;

    VehicleEntity self = (VehicleEntity) (Object) this;
    long now = self.level().getGameTime();

    if (now - this.tacz_sewv$lastAiShotTick < tacz_sewv$AI_FIRE_COOLDOWN) {
        ci.cancel();
        return;
    }

    this.tacz_sewv$lastAiShotTick = now;
}
}