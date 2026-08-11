package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.compat.NpcVehicleOverrides;

/**
 * NPC-only hull-yaw fix for {@code mcsp:t80bv_kantemir}: re-assert the driver's body yaw
 * onto the hull each {@code baseTick} so a stuck / NaN chassis rotation cannot park the
 * airframe forever. Uses {@code baseTick} (SBW override) rather than vanilla {@code tick}/
 * {@code setYRot}, which the AP cannot resolve on a string-targeted mixin.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinNpcHullYaw {

    @Inject(method = "baseTick", at = @At("TAIL"))
    private void tacz_sewv$forceKantemirYaw(CallbackInfo ci) {
        VehicleEntity hull = (VehicleEntity) (Object) this;
        if (!NpcVehicleOverrides.isT80bvKantemir(hull)) return;
        if (!(hull.getFirstPassenger() instanceof AbstractUnit unit)) return;
        float yaw = unit.getYRot();
        if (!Float.isFinite(yaw)) return;
        if (!Float.isFinite(hull.getYRot())) {
            hull.setYRot(yaw);
            hull.yRotO = yaw;
            return;
        }
        // Keep chassis within a few degrees of the driver so a stuck rotation unsticks.
        float err = net.minecraft.util.Mth.degreesDifference(hull.getYRot(), yaw);
        if (Math.abs(err) > 2.0f) {
            hull.setYRot(yaw);
            hull.yRotO = yaw;
        }
    }
}
