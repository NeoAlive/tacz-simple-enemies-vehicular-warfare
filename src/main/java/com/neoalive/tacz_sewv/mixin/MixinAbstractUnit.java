package com.neoalive.tacz_sewv.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.support.EmplacementHands;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.entity.ai.support.SupportRole;

/**
 * Hard friendly-fire gate, applied at the source. SEM's retaliation goal
 * (NoPlayerHurtByTargetGoal, priority 1) only refuses to retaliate against
 * players, so a unit clipped by an ally's stray shot — splash damage from a
 * crewed vehicle is the usual culprit — takes that ally as its target, and
 * every downstream system (drive goals, SBW's vehicle fire loop, SEM's rifle
 * goal) faithfully attacks it, cascading into a blue-on-blue firefight.
 *
 * <p>Cancelling the same-faction setTarget here means the friendly target
 * never exists at all, whatever tries to assign it: retaliation, vanilla
 * alertOthers propagation, or a player's ATTACK_THAT_TARGET order. Vanilla's
 * HurtByTargetGoal stamps its hurt-timestamp in start() regardless, so the
 * cancelled retaliation doesn't retry every tick — it stays dormant until the
 * unit is hurt again.
 *
 * <p>The same hook carries the <b>support roles</b> ({@link SupportRole}) for
 * the same reason: a unit holding a medical kit or a repair tool is not a
 * combatant, and the cheapest way to say so is that it never acquires a target
 * — every goal that fights, chases or drives at something reads getTarget().
 */
@Mixin(AbstractUnit.class)
public abstract class MixinAbstractUnit {

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void tacz_sewv$defineManningMortar(CallbackInfo ci) {
        ((Entity) (Object) this).getEntityData().define(EmplacementHands.MANNING_MORTAR, false);
    }

    // setTarget is a vanilla Mob method SEM overrides, so the target is remapped
    // (unlike the remap=false mod-declared methods elsewhere in this package).
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$blockFriendlyTarget(LivingEntity target, CallbackInfo ci) {
        // Never veto CLEARING a target. The veto's whole job is to stop a unit ACQUIRING one it
        // shouldn't; setTarget(null) is the opposite — it releases whatever was held — and
        // cancelling it would freeze a dead/stale target on the unit. SupportRole.refusesTarget
        // returns true for null (a medic refuses everything; an engineer refuses null), so without
        // this guard a PMC that became a medic/engineer mid-combat could never shed its target and
        // would sit neither fighting nor supporting. isFriendly/isMedic already pass a null through.
        if (target == null) return;
        AbstractUnit self = (AbstractUnit) (Object) this;

        boolean diplEnemy = VehicleTargeting.isDiplomacyEnemy(self, target);
        boolean sameClassFriendly = VehicleTargeting.isFriendly(self, target);
        boolean medic = VehicleTargeting.isMedic(target);
        boolean supportRefuse = SupportRole.refusesTarget(self, target);

        // Stage 4 ENEMY pairs must reach setTarget even when SEM class says "same faction" (PMC↔PMC).
        if (diplEnemy) {
            if (self instanceof PmcUnitEntity && target instanceof PmcUnitEntity) {
                SewvDiag.setTarget(
                        "MixinAbstractUnit APPROVE diplomacyEnemy=true self={}#{} target={}#{} "
                                + "sameClassFriendly={} (would have blocked without ENEMY exception)",
                        self.getClass().getSimpleName(), self.getId(),
                        target.getClass().getSimpleName(), target.getId(),
                        sameClassFriendly);
            }
            return;
        }
        // Same-faction friends and medics (neutral to everyone) are never taken as a target.
        if (sameClassFriendly || medic) {
            if (self instanceof PmcUnitEntity && target instanceof PmcUnitEntity) {
                SewvDiag.setTarget(
                        "MixinAbstractUnit BLOCK sameClassFriendly={} medic={} self={}#{} target={}#{}",
                        sameClassFriendly, medic,
                        self.getClass().getSimpleName(), self.getId(),
                        target.getClass().getSimpleName(), target.getId());
            }
            ci.cancel();
            return;
        }
        // ...and a unit whose own hands say it is not here to fight refuses the target outright.
        if (supportRefuse) {
            SewvDiag.setTarget(
                    "MixinAbstractUnit BLOCK supportRoleRefuse self={}#{} target={}#{}",
                    self.getClass().getSimpleName(), self.getId(),
                    target.getClass().getSimpleName(), target.getId());
            ci.cancel();
            return;
        }
        // Sweep / patrol / S&D: refuse locks outside the ordered ground so SEM's infantry scan
        // (and any other setTarget path) cannot pin a distant mob and stall Sweep & Advance quiet.
        if (self instanceof PmcUnitEntity pmc && PatrolSupport.refusesOutOfAreaTarget(pmc, target)) {
            SewvDiag.setTarget(
                    "MixinAbstractUnit BLOCK outOfAreaTask self={}#{} target={}#{}",
                    self.getClass().getSimpleName(), self.getId(),
                    target.getClass().getSimpleName(), target.getId());
            ci.cancel();
        }
    }
}
