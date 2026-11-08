package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.bridge.IHullRadio;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.crew.RadioQueue;

/** Carries the crew radio queue and plays its next line once the current clip is done. */
@Mixin(VehicleEntity.class)
public abstract class MixinVehicleRadioQueue implements IHullRadio {

    @Unique
    private RadioQueue tacz_sewv$radio;

    @Override
    public RadioQueue sewv$radio() {
        RadioQueue q = this.tacz_sewv$radio;
        if (q == null) this.tacz_sewv$radio = q = new RadioQueue();
        return q;
    }

    @Override
    public RadioQueue sewv$radioOrNull() {
        return this.tacz_sewv$radio;
    }

    @Inject(method = "baseTick", at = @At("TAIL"))
    private void tacz_sewv$drainRadio(CallbackInfo ci) {
        RadioQueue q = this.tacz_sewv$radio;
        if (q == null || q.isEmpty()) return;
        CrewRadio.drain((VehicleEntity) (Object) this, q);
    }
}
