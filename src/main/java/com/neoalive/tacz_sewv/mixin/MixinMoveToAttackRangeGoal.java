package com.neoalive.tacz_sewv.mixin;

import com.neoalive.tacz_sewv.entity.ai.FollowLeash;
import net.minecraft.world.entity.Mob;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.MoveToAttackRangeGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps a following unit from charging an enemy it spots.
 *
 * <p>{@link MoveToAttackRangeGoal} walks a unit that has a target up to ~90 blocks toward it
 * (detection 96, attack range ~88, speed 1.2). For a unit under a "stay with me" order that
 * is the whole straying problem — it abandons the commander to close on the first thing it
 * sees. {@link FollowLeash#leashed} gates the goal off for on-foot PMC units under those
 * orders, so they hold with the commander and fire only when the enemy comes into range
 * (SEM's RangedGunAttackGoal needs no movement of its own). RU/US units and free-fire /
 * attack-that-target orders keep the full advance.
 *
 * <p>Paired with {@link MixinCommanderOrderGoal}, which keeps the follow goal itself live
 * through combat — without that half this suppression alone would leave the unit standing
 * in place rather than actually following.
 */
@Mixin(MoveToAttackRangeGoal.class)
public abstract class MixinMoveToAttackRangeGoal {

    // Mod-declared field: its name is stable across dev/production, so don't remap it.
    @Shadow(remap = false)
    @Final
    private Mob mob;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$holdWhenLeashed(CallbackInfoReturnable<Boolean> cir) {
        if (FollowLeash.leashed(this.mob)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "canContinueToUse", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$stopWhenLeashed(CallbackInfoReturnable<Boolean> cir) {
        if (FollowLeash.leashed(this.mob)) {
            cir.setReturnValue(false);
        }
    }
}
