package com.neoalive.tacz_sewv.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.ai.decision.CombatDecisionContext;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.SmallArmsSupport;
import com.neoalive.tacz_sewv.entity.ai.suppression.InfantryArmorDoctrine;
import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;
import com.neoalive.tacz_sewv.entity.ai.utility.DoctrineKnobs;

/**
 * SEM's infantry decision layer, taught what a vehicle is. {@code CombatDecisionContext.evaluate}
 * scores a target by the item in its hand, so a tank reads as its crewman's rifle. At RETURN this
 * rebuilds the record through its public constructor with {@link InfantryArmorDoctrine}'s
 * adjustments: a rifleman feels the hull (more pressure, less confidence, no advance), and an
 * anti-armour gunner is emboldened. SEM's own Schmitt thresholds then do the rest.
 *
 * <p>Then, for an owned PMC on foot, its owner's doctrine (DoctrineKnobs.infantry*): bolder ledgers raise
 * confidence and shrug off pressure, careful ones feel it more, and a passive one is never pushed forward.
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
        if (unit.isPassenger()) return;
        CombatDecisionContext c = cir.getReturnValue();
        if (c == null) return;

        float pressure = c.threatPressure();
        float confidence = c.combatConfidence();
        boolean advance = c.shouldAdvance();
        String reason = c.reason();
        boolean changed = false;

        float tier = SewvConfig.INFANTRY_ARMOR_AWARENESS.get() ? InfantryArmorDoctrine.tierOf(target) : 0.0f;
        if (tier > 0.0f) {
            boolean gunner = SmallArmsSupport.holdsLauncher(unit);
            if (!gunner) pressure = InfantryArmorDoctrine.riflemanPressure(pressure, tier);
            confidence = gunner
                    ? InfantryArmorDoctrine.gunnerConfidence(confidence, tier)
                    : InfantryArmorDoctrine.riflemanConfidence(confidence, tier);
            if (!gunner) advance = false;
            reason = reason + (gunner ? " +armor(at)" : " +armor");
            changed = true;
        }

        // A player's ledger reaches their riflemen too: SEM's own thresholds turn the shifted confidence and pressure
        // into advancing, taking cover or falling back. Owned PMCs only; RU/US infantry keep SEM's behaviour.
        if (unit instanceof PmcUnitEntity pmc && pmc.getOwnerUUID() != null) {
            Doctrine doctrine = Doctrine.forCrew(unit);
            if (!doctrine.isNeutral()) {
                pressure = DoctrineKnobs.infantryPressure(doctrine, pressure);
                confidence = DoctrineKnobs.infantryConfidence(doctrine, confidence);
                if (!DoctrineKnobs.infantryMayAdvance(doctrine)) advance = false;
                reason = reason + " +doctrine";
                changed = true;
            }
        }

        if (!changed) return;
        cir.setReturnValue(new CombatDecisionContext(pressure, confidence, advance, c.shouldHoldAmbush(),
                c.cqbMode(), c.killzone(), c.roleProfile(), c.squadContext(), reason));
    }
}
