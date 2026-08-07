package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.util.VehicleDrops;

/**
 * Replaces SBW's unconditional container spill on {@link VehicleEntity#remove} with
 * {@link VehicleDrops#spillAndClear}, which honours {@code vehicleDeathDrops} and never
 * drops a creative ammo box. Inventory is emptied here so SBW's own loop is a no-op.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleDeathDrops {

    // remove is Entity's vanilla override (SRG in production) — remap must stay ON.
    @Inject(method = "remove", at = @At("HEAD"), remap = true)
    private void tacz_sewv$gateContainerDrops(Entity.RemovalReason reason, CallbackInfo ci) {
        VehicleEntity self = (VehicleEntity) (Object) this;
        if (self.level().isClientSide) return;
        if (reason == Entity.RemovalReason.DISCARDED
                || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
            return;
        }
        VehicleDrops.spillAndClear(self);
    }
}
