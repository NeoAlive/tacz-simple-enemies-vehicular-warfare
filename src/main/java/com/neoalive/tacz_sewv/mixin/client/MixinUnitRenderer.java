package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.client.renderer.culling.Frustum;
import net.nekoyuni.SimpleEnemyMod.entity.client.pmc_unit.PmcUnitRenderer;
import net.nekoyuni.SimpleEnemyMod.entity.client.ru_unit.RUunitRenderer;
import net.nekoyuni.SimpleEnemyMod.entity.client.us_unit.USunitRenderer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides any mounted unit whose seat encloses it ({@code getHidePassenger}). One mixin for
 * all three unit renderers — they share no SEM base class (each extends MobRenderer
 * directly), so the entity parameter's type differs per target and is {@code @Coerce}d to
 * the common {@link AbstractUnit}.
 */
@Mixin({PmcUnitRenderer.class, RUunitRenderer.class, USunitRenderer.class})
public abstract class MixinUnitRenderer {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$hideMountedInEnclosedSeat(
            @Coerce AbstractUnit entity, Frustum frustum, double camX, double camY, double camZ,
            CallbackInfoReturnable<Boolean> cir) {

        if (entity.getVehicle() instanceof VehicleEntity vehicle) {
            SeatInfo seat = vehicle.getSeat(entity);
            if (seat != null && seat.getHidePassenger()) {
                cir.setReturnValue(false); // enclosed seat, skip rendering entirely
            }
        }
    }
}
