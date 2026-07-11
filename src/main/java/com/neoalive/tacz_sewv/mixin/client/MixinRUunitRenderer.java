package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.client.renderer.culling.Frustum;
import net.nekoyuni.SimpleEnemyMod.entity.client.ru_unit.RUunitRenderer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RUunitRenderer.class)
public abstract class MixinRUunitRenderer {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$hideMountedInEnclosedSeat(
            RUunitEntity entity, Frustum frustum, double camX, double camY, double camZ,
            CallbackInfoReturnable<Boolean> cir) {

        if (entity.getVehicle() instanceof VehicleEntity vehicle) {
            SeatInfo seat = vehicle.getSeat(entity);
            if (seat != null && seat.getHidePassenger()) {
                cir.setReturnValue(false); // enclosed seat — skip rendering entirely
            }
        }
    }
}
