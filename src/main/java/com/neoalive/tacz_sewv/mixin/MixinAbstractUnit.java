package com.neoalive.tacz_sewv.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.bridge.IDelayedFire;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.entity.ai.support.SmallArmsSupport;
import com.neoalive.tacz_sewv.entity.ai.support.SupportRole;
import com.neoalive.tacz_sewv.entity.ai.support.UnitHolster;
import com.neoalive.tacz_sewv.notify.HudNotify;

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
public abstract class MixinAbstractUnit implements IDelayedFire {

    static {
        // SynchedEntityData IDs must be allocated parent-before-child. UnitHolster's
        // MANNING_MORTAR is defineId(AbstractUnit); if that class only loads on the first
        // defineSynchedData call, PmcUnitEntity (and RU/US) have already taken those id
        // numbers via their own static defineId — and every PMC construction then dies with
        // "Duplicate id value for 21!". Touching it here runs while AbstractUnit is loading,
        // before any subclass static fields.
        if (UnitHolster.MANNING_MORTAR == null) {
            throw new ExceptionInInitializerError("UnitHolster.MANNING_MORTAR");
        }
    }

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void tacz_sewv$defineManningMortar(CallbackInfo ci) {
        ((Entity) (Object) this).getEntityData().define(UnitHolster.MANNING_MORTAR, false);
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
            if (!VehicleTargeting.categoryAllowed(self, target)) {
                ci.cancel();
            }
            return;
        }
        // Same-faction friends and medics (neutral to everyone) are never taken as a target.
        if (sameClassFriendly || medic) {
            ci.cancel();
            return;
        }
        // An anti-tank launcher only ever points at armour: a crewed hull whose engine type is
        // declared. Vetoing here keeps SEM's own scans, retaliation and player orders from ever
        // handing a gunner an infantry target its tube cannot answer — see SmallArmsSupport.
        if (SmallArmsSupport.refusesTarget(self, target)) {
            ci.cancel();
            return;
        }
        // ...and a unit whose own hands say it is not here to fight refuses the target outright.
        if (supportRefuse) {
            ci.cancel();
            return;
        }
        // Sweep / patrol / S&D: refuse locks outside the ordered ground so SEM's infantry scan
        // (and any other setTarget path) cannot pin a distant mob and stall Sweep & Advance quiet.
        if (self instanceof PmcUnitEntity pmc && PatrolSupport.refusesOutOfAreaTarget(pmc, target)) {
            ci.cancel();
            return;
        }
        if (!VehicleTargeting.categoryAllowed(self, target)) {
            ci.cancel();
        }
    }

    /** After vetoes: owned PMC acquired a live hostile — rising-edge toast with cooldown. */
    @Inject(method = "setTarget", at = @At("TAIL"))
    private void tacz_sewv$notifyEngage(LivingEntity target, CallbackInfo ci) {
        if (target == null) return;
        AbstractUnit self = (AbstractUnit) (Object) this;
        if (!(self instanceof PmcUnitEntity pmc)) return;
        if (self.level().isClientSide()) return;
        if (self.getTarget() != target) return; // HEAD veto cancelled the assign
        HudNotify.pmcEngaging(pmc, target);
    }
}
