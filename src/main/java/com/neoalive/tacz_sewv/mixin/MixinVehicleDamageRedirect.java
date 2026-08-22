package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.damage.DamageModifier;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.neoalive.tacz_sewv.ballistics.VehicleGunfireTranslation;
import com.neoalive.tacz_sewv.init.ModGameRules;
import com.neoalive.tacz_sewv.util.vehiclemelee.AttackerFacts;
import com.neoalive.tacz_sewv.util.vehiclemelee.DamageEvaluator;
import com.neoalive.tacz_sewv.util.vehiclemelee.VehicleFacts;

/**
 * The single {@code @Redirect} owner for SBW {@link DamageModifier#compute} inside
 * {@code VehicleEntity.hurt} — Mixin's {@code @Redirect} claims exclusive ownership of the
 * instruction it targets, so two mixins cannot both redirect the same call; every concern that
 * wants to override a hull's computed damage has to dispatch from here instead of adding its own
 * mixin on the same seam.
 *
 * <p>Dispatch order: TaCZ ballistic translation first (it substitutes both the source and the
 * amount before any "default" compute() would consume the untranslated ones — see
 * {@link VehicleGunfireTranslation}), then mob melee (formerly {@code MixinVehicleMobMeleeDamage}):
 * when {@code canMobsDamageVehicles} is on, so datapack {@code minecraft:mob_attack 0} immunities
 * no longer make hulls untouchable. Friendly-fire / {@code vehicle_immune} / bypass gates in
 * {@code hurt} still run first, upstream of this redirect.
 */
@Mixin(VehicleEntity.class)
public abstract class MixinVehicleDamageRedirect {

    @Redirect(
            method = "hurt",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/atsuishio/superbwarfare/entity/vehicle/damage/DamageModifier;compute(Lnet/minecraft/world/damagesource/DamageSource;F)F",
                    remap = false
            ),
            remap = true
    )
    private float tacz_sewv$redirectDamageCompute(DamageModifier modifier, DamageSource source, float amount) {
        VehicleEntity hull = (VehicleEntity) (Object) this;

        Float translated = VehicleGunfireTranslation.tryTranslate(hull, modifier, source, amount);
        if (translated != null) return translated;

        float computed = modifier.compute(source, amount);
        if (hull.level().isClientSide()) return computed;
        if (!hull.level().getGameRules().getBoolean(ModGameRules.CAN_MOBS_DAMAGE_VEHICLES)) {
            return computed;
        }
        if (!source.is(DamageTypes.MOB_ATTACK) && !source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)) {
            return computed;
        }
        if (!(source.getEntity() instanceof Mob mob) || !mob.isAlive()) return computed;

        return DamageEvaluator.evaluate(AttackerFacts.of(mob), VehicleFacts.of(hull));
    }
}
