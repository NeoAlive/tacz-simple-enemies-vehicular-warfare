package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.data.vehicle.DefaultVehicleData;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.neoalive.tacz_sewv.compat.NeoArmsCarrierAccess;
import com.neoalive.tacz_sewv.compat.NeoArmsCompat;
import com.neoalive.tacz_sewv.heli.IFlightDynamics;

/**
 * The single owner of SBW's gravity read in {@code baseTick} (decision D1 of the helicopter
 * physics rework). Gravity is applied INLINE there ({@code getGravity()} at bytecode offset 2642,
 * feeding {@code setDeltaMovement} at 2657 and {@code move} at 2675, javap-verified), so there is no
 * method to HEAD-cancel. This redirect is the seam, and with {@code defaultRequire: 1} a moved or
 * renamed call fails loudly at mixin apply instead of drifting silently.
 *
 * <p>Zero for:
 * <ul>
 * <li>a hull our integrator is flying ({@link IFlightDynamics#FLYING}): the integrator owns
 *     gravity entirely, at the hull's own SBW value, so {@code move()} sees exactly the
 *     displacement we publish;</li>
 * <li>a Neo Arms deck-parked hull. Moved here verbatim from {@code MixinVehicleDeckParkPhysics}:
 *     a {@code @Redirect} claims its call exclusively, so two mixins cannot both redirect it
 *     (same precedent as {@code MixinVehicleDamageRedirect}).</li>
 * </ul>
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleHeliGravity {

    @Redirect(
            method = "baseTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/atsuishio/superbwarfare/data/vehicle/DefaultVehicleData;getGravity()D",
                    remap = false))
    private double tacz_sewv$gravity(DefaultVehicleData data) {
        VehicleEntity self = (VehicleEntity) (Object) this;
        if (((IFlightDynamics) self).sewv$flightMode() == IFlightDynamics.FLYING) return 0.0;
        if (NeoArmsCompat.present() && NeoArmsCarrierAccess.isDeckParked(self)) return 0.0;
        return data.getGravity();
    }
}
