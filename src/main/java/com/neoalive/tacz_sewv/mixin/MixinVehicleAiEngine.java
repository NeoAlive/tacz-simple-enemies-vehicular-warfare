package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.compat.fcp.FcpCompat;

/**
 * FCP 2.0 hulls start with the engine off and drop every driving input until the start key is held
 * through the start-up. An AI driver holds it here. baseTick TAIL runs inside super.tick, before
 * FCP's own EngineControl.tick in the same tick, so the hold is always fresh when it is read.
 */
@Mixin(VehicleEntity.class)
public abstract class MixinVehicleAiEngine {

    @Inject(method = "baseTick", at = @At("TAIL"))
    private void tacz_sewv$aiHoldsStart(CallbackInfo ci) {
        VehicleEntity self = (VehicleEntity) (Object) this;
        if (self.level().isClientSide()) return;
        if (!FcpCompat.isFcp(self)) return;
        if (!(self.getFirstPassenger() instanceof AbstractUnit)) return;
        if (!FcpCompat.engineBlocked(self)) return;
        FcpCompat.holdEngineStart(self);
    }
}
