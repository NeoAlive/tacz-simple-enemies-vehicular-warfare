package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.neoalive.tacz_sewv.config.EasyModePlayerEvents;

/**
 * Easy Mode: a hull crewed by the player's side takes less hostile damage. A player riding a hull
 * takes most hits on the hull, which the player-side hurt scale never sees.
 *
 * <p>A HEAD {@code @ModifyVariable} on the amount, deliberately not part of
 * {@link MixinVehicleDamageRedirect}: that redirect yields to Doggie's New Vehicle's (see its note)
 * and is skipped when DNV is installed, while this coexists with both.
 */
@Mixin(VehicleEntity.class)
public abstract class MixinVehicleEasyDamage {

    @ModifyVariable(method = "hurt", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private float tacz_sewv$easyHullDamage(float amount, DamageSource source) {
        VehicleEntity hull = (VehicleEntity) (Object) this;
        if (hull.level().isClientSide()) return amount;
        return EasyModePlayerEvents.scaleHullDamage(hull, source, amount);
    }
}
