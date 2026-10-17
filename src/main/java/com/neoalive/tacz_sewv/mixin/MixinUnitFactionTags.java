package com.neoalive.tacz_sewv.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.util.UnitFactionTags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * SEM 0.1.6 tags every PMC as one faction. {@code isFriendlyFire} then cancels TaCZ
 * {@code EntityHurtByGunEvent.Pre} and {@code AbstractUnit.hurt} for any PMC↔PMC
 * (and PMC→player) pair — they shoot, but the round does no damage. Diplomacy /
 * invasion ENEMY pairs are a war, not friendly fire.
 */
@Mixin(value = UnitFactionTags.class, remap = false)
public abstract class MixinUnitFactionTags {

    /**
     * SEM 0.1.6-beta-hotfix routes its "is this target off-limits" answer through this one call:
     * RU/US {@code setTarget} (early return, before our {@code MixinAbstractUnit} bypass can run),
     * bullet-impact alerts, combat-sound alerts and suppression. A diplomacy / invasion ENEMY pair
     * is a war, so it must read as not friendly on every one of those paths. PMC's own
     * {@code setTarget} calls the one-argument {@code isFriendlyToPmc} instead — see
     * {@code MixinPmcUnitEntity}.
     */
    @Inject(method = "isFriendlyToNpcFaction", at = @At("HEAD"), cancellable = true)
    private static void tacz_sewv$enemyIsNotFriendly(LivingEntity self, LivingEntity other,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (self instanceof AbstractUnit unit && other != null
                && VehicleTargeting.isDiplomacyEnemy(unit, other)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isFriendlyFire", at = @At("HEAD"), cancellable = true)
    private static void tacz_sewv$diplomacyEnemy(Entity victim, Entity attacker,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if (victim instanceof AbstractUnit unit && attacker instanceof LivingEntity living
                && VehicleTargeting.isDiplomacyEnemy(unit, living)) {
            cir.setReturnValue(false);
            return;
        }
        if (victim instanceof Player player && attacker instanceof AbstractUnit shooter
                && VehicleTargeting.isDiplomacyEnemy(shooter, player)) {
            cir.setReturnValue(false);
        }
    }
}
