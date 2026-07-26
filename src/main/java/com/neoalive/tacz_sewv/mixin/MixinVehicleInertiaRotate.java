package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.data.vehicle.DefaultVehicleData;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Kills SBW's cosmetic chassis bank ({@code InertiaRotateRate × lateral accel}) while an SEM
 * unit is driving. FCP (and some ASH) hulls set that rate high (~1.8); at speed the bank fights
 * the AI's steering and reads as the hull tipping through every turn.
 *
 * <p>Redirects both pitch and roll reads of {@code getInertiaRotateRate} inside
 * {@code baseTick} — those are the only two call sites that apply the bank. Player crews keep
 * the datapack value untouched.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleInertiaRotate {

    // baseTick is Entity's vanilla override (SRG in production) — remap must stay ON for the
    // method name. getInertiaRotateRate is SBW-owned and stays remap = false.
    @Redirect(
            method = "baseTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/atsuishio/superbwarfare/data/vehicle/DefaultVehicleData;getInertiaRotateRate()F",
                    remap = false))
    private float tacz_sewv$semCrewInertia(DefaultVehicleData data) {
        if (SewvConfig.SEM_CREW_DISABLE_INERTIA_ROTATE.get()
                && ((VehicleEntity) (Object) this).getFirstPassenger() instanceof AbstractUnit) {
            return 0.0F;
        }
        return data.getInertiaRotateRate();
    }
}
