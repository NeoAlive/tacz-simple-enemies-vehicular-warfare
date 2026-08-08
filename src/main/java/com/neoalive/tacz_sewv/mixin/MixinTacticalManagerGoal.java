package com.neoalive.tacz_sewv.mixin;

import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.TacticalManagerGoal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.util.SoldierState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.entity.ai.support.FollowLeash;

/**
 * SEM's tactical manager forces {@code SEEK_COVER} / flanks on every new contact. Under
 * FOLLOW_ME or an unfinished MOVE that peels the unit off its order. Mirror SEM's own
 * HOLD_POSITION short-circuit: keep a shootable state, drop the movement lock, and leave
 * locomotion to the priority-1 stick goals.
 */
@Mixin(TacticalManagerGoal.class)
public abstract class MixinTacticalManagerGoal {

    @Shadow(remap = false)
    @Final
    private AbstractUnit unit;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$honourOrderedMove(CallbackInfo ci) {
        if (!FollowLeash.ownsMove(this.unit)) return;

        this.unit.releaseMovementLock();
        if (this.unit.getTarget() != null && this.unit.getTarget().isAlive()) {
            if (this.unit.getSoldierState() != SoldierState.ENGAGE
                    && this.unit.getSoldierState() != SoldierState.IDLE) {
                this.unit.setSoldierState(SoldierState.ENGAGE);
            }
        } else if (this.unit.getSoldierState() != SoldierState.IDLE) {
            this.unit.setSoldierState(SoldierState.IDLE);
        }
        ci.cancel();
    }
}
