package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.bridge.IHullPressure;
import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.entity.ai.suppression.HullPressure;
import com.neoalive.tacz_sewv.entity.ai.suppression.SuppressionBus;

/**
 * Carries {@link IHullPressure} and feeds it from every attributable hit on the hull. Reads the RAW
 * amount at HEAD, before SBW's armour modifiers: a crew under a hail of rounds it can shrug off is
 * still under fire. A separate HEAD inject rather than part of {@link MixinVehicleDamageRedirect},
 * which yields to Doggie's New Vehicle and is skipped under it.
 *
 * <p>Fire from inside the hull, and damage nobody caused (fall, lava, collisions with no attacker),
 * is not pressure.
 */
@Mixin(VehicleEntity.class)
public abstract class MixinVehicleHullPressure implements IHullPressure {

    @Unique
    private float tacz_sewv$pressure;

    @Unique
    private long tacz_sewv$pressureStamp = Long.MIN_VALUE;

    @Override
    public float sewv$getPressureRaw() {
        return this.tacz_sewv$pressure;
    }

    @Override
    public void sewv$setPressureRaw(float value) {
        this.tacz_sewv$pressure = value;
    }

    @Override
    public long sewv$getPressureStamp() {
        return this.tacz_sewv$pressureStamp;
    }

    @Override
    public void sewv$setPressureStamp(long gameTime) {
        this.tacz_sewv$pressureStamp = gameTime;
    }

    @Inject(method = "hurt", at = @At("HEAD"), require = 0)
    private void tacz_sewv$notePressure(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        VehicleEntity hull = (VehicleEntity) (Object) this;
        if (hull.level().isClientSide() || !SuppressionBus.enabled()) return;
        Entity attacker = source.getEntity();
        if (attacker == null && !source.is(DamageTypeTags.IS_EXPLOSION)) return;
        if (attacker != null && attacker.getRootVehicle() == hull) return;
        float add = HullPressure.hitAmount(amount, hull.getMaxHealth()) * EasyMode.suppressionScale(hull);
        HullPressure.add(hull, add, hull.level().getGameTime());
    }
}
