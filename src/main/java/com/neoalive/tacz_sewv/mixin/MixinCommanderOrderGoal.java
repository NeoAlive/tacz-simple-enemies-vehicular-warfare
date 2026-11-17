package com.neoalive.tacz_sewv.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.CommanderOrderGoal;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.entity.ai.support.FollowLeash;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleFormation;

/**
 * Two unrelated fixes to SEM's commander-order goal, both gated so a plain SEM infantry
 * squad is untouched.
 *
 * <p><b>1. Formation geometry.</b> An infantry formation ordered from the Quick Wheel carries its
 * own shape, stretch and frozen cardinal; left alone, SEM would send the unit to its own
 * 2.0/2.5-block wedge/column laid out on the commander's live body yaw, ignoring all three. The
 * two formation injections no-op unless the unit is actually in one of our formations.
 *
 * <p><b>2. Follow while fighting.</b> This goal abandons its follow / hold / formation order
 * the moment the unit has a live target — canUse, canContinueToUse and tick all bail on the
 * same {@code getTarget()} read, handing movement to MoveToAttackRangeGoal, which charges the
 * enemy up to ~90 blocks. {@link #tacz_sewv$ignoreTargetWhileLeashed} blanks that shared read
 * for an on-foot PMC under a positional order so the goal keeps running through combat and the
 * unit stays with its commander. The advance itself is suppressed in
 * {@link MixinMoveToAttackRangeGoal}; see {@link FollowLeash} for the whole picture.
 */
@Mixin(CommanderOrderGoal.class)
public abstract class MixinCommanderOrderGoal {

    @Shadow(remap = false)
    @Final
    private PathfinderMob mob;

    /**
     * Keeps a following unit tied to its commander while it fights instead of dropping the
     * order the instant it acquires a target.
     *
     * <p>canUse, canContinueToUse and tick each give up when {@code mob.getTarget()} is a live
     * entity — canUse/canContinueToUse return false, tick early-returns without repositioning.
     * All three read the target through this one call, so returning null for it (only for an
     * on-foot PMC under a positional order) makes the goal behave exactly as it would with no
     * target at all: it keeps following / holding / forming. The unit still aims and fires
     * through SEM's RangedGunAttackGoal (LOOK flag, no movement) and still takes local cover
     * through SeekCoverGoal (priority 2, ~13 blocks); it simply no longer runs across the map,
     * because the long advance is gone (see {@link MixinMoveToAttackRangeGoal}). Everything else
     * gets the real target, so non-leashed units — and SEM's own squads — are untouched.
     */
    @Redirect(
            method = {"canUse", "canContinueToUse", "tick"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/PathfinderMob;getTarget()Lnet/minecraft/world/entity/LivingEntity;"))
    private LivingEntity tacz_sewv$ignoreTargetWhileLeashed(PathfinderMob self) {
        LivingEntity target = self.getTarget();
        return (target != null && FollowLeash.leashed(self)) ? null : target;
    }

    @Inject(method = "calculateClassicFormationTarget", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$vehicleSpacedSlot(LivingEntity owner, OrderType order, int index,
                                             CallbackInfoReturnable<Vec3> cir) {
        if (tacz_sewv$formationAxis() == null) return;
        PmcUnitEntity pmc = (PmcUnitEntity) this.mob;
        Vec3 slot = VehicleFormation.formationSlotCenter(pmc, owner.position());
        if (slot != null) {
            cir.setReturnValue(slot);
        } else if (VehicleFormation.isUnitFollower(pmc)) {
            // A UNIT follower whose leader is out of reach holds where it stands — SEM's own answer would be
            // a slot on the player, who is not what this formation is laid on.
            cir.setReturnValue(this.mob.position());
        }
    }

    /**
     * SEM sends slot 0 to the OWNER when its slot is inside a block. In a UNIT formation slot 0 is a follower
     * of the leader, not of the player, so that fallback would walk it off to the player; answering "safe"
     * leaves it to its navigation, which already routes round what it cannot stand in. This is the method's
     * only caller.
     */
    @Inject(method = "isPositionSafe", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$unitSlotNeverFallsBackToOwner(Vec3 pos, CallbackInfoReturnable<Boolean> cir) {
        if (this.mob instanceof PmcUnitEntity pmc && VehicleFormation.isUnitFollower(pmc)) cir.setReturnValue(true);
    }

    /**
     * SEM's COLUMN never calls the formation math for index > 0 — it files each man 1.5 blocks
     * behind whoever holds index-1. In a vehicle column index-1 is a TANK, and 1.5 blocks behind
     * a moving hull is under its tracks. Answering "no predecessor" drops performFollowOwner back
     * onto calculateClassicFormationTarget, which the injection above owns.
     *
     * <p>Side benefit: it also skips a 20-block entity scan per tick per man.
     */
    @Inject(method = "findPredecessor", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$noPredecessorInVehicleColumn(LivingEntity owner, int index,
                                                        CallbackInfoReturnable<PmcUnitEntity> cir) {
        if (tacz_sewv$formationAxis() != null) cir.setReturnValue(null);
    }

    /** The axis this unit is formed on, or null when it is not in one of our formations. */
    @Unique
    private Direction tacz_sewv$formationAxis() {
        if (!(this.mob instanceof PmcUnitEntity pmc)) return null;
        OrderType order = pmc.getOrder();
        if (order != OrderType.FORM_WEDGE && order != OrderType.FORM_COLUMN) return null;
        return ((IFormationMember) pmc).sewv$getFormationDirection();
    }
}
