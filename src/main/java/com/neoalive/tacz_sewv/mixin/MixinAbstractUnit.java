package com.neoalive.tacz_sewv.mixin;

import com.neoalive.tacz_sewv.entity.ai.SupportRole;
import com.neoalive.tacz_sewv.entity.ai.VehicleTargeting;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

    // setTarget is a vanilla Mob method SEM overrides, so the target is remapped
    // (unlike the remap=false mod-declared methods elsewhere in this package).
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$blockFriendlyTarget(LivingEntity target, CallbackInfo ci) {
        AbstractUnit self = (AbstractUnit) (Object) this;
        // Same-faction friends and medics (neutral to everyone) are never taken as a target.
        if (VehicleTargeting.isFriendly(self, target) || VehicleTargeting.isMedic(target)) {
            ci.cancel();
            return;
        }
        // ...and a unit whose own hands say it is not here to fight refuses the target outright.
        if (SupportRole.refusesTarget(self, target)) {
            ci.cancel();
        }
    }
}
