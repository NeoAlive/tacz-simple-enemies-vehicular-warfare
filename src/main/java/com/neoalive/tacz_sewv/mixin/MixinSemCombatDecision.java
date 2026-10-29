package com.neoalive.tacz_sewv.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.ai.decision.CombatDecisionContext;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.SmallArmsSupport;
import com.neoalive.tacz_sewv.entity.ai.suppression.InfantryArmorDoctrine;

/**
 * SEM's infantry decision layer, taught what a vehicle is. {@code CombatDecisionContext.evaluate}
 * scores a target by the item in its hand, so a tank reads as its crewman's rifle. At RETURN this
 * rebuilds the record through its public constructor with {@link InfantryArmorDoctrine}'s
 * adjustments: a rifleman feels the hull (more pressure, less confidence, no advance), and an
 * anti-armour gunner is emboldened. SEM's own Schmitt thresholds then do the rest.
 *
 * <p>Mounted units are left alone: their combat is the hull's, and SEM's tactical manager is already
 * short-circuited for them. {@code require = 0}: on a SEM build that changed this record, infantry
 * simply keep SEM's own read of armour.
 */
@Mixin(CombatDecisionContext.class)
public abstract class MixinSemCombatDecision {

    @Inject(method = "evaluate", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void tacz_sewv$armorIsArmor(AbstractUnit unit, LivingEntity target, boolean canSee,
                                               double distance, CallbackInfoReturnable<CombatDecisionContext> cir) {
        if (!SewvConfig.INFANTRY_ARMOR_AWARENESS.get() || unit.isPassenger()) return;
        float tier = InfantryArmorDoctrine.tierOf(target);
        if (tier <= 0.0f) return;
        CombatDecisionContext c = cir.getReturnValue();
        if (c == null) return;

        boolean gunner = SmallArmsSupport.holdsLauncher(unit);
        float pressure = gunner ? c.threatPressure() : InfantryArmorDoctrine.riflemanPressure(c.threatPressure(), tier);
        float confidence = gunner
                ? InfantryArmorDoctrine.gunnerConfidence(c.combatConfidence(), tier)
                : InfantryArmorDoctrine.riflemanConfidence(c.combatConfidence(), tier);
        boolean advance = gunner ? c.shouldAdvance() : false;
        cir.setReturnValue(new CombatDecisionContext(pressure, confidence, advance, c.shouldHoldAmbush(),
                c.cqbMode(), c.killzone(), c.roleProfile(), c.squadContext(),
                c.reason() + (gunner ? " +armor(at)" : " +armor")));
    }
}
