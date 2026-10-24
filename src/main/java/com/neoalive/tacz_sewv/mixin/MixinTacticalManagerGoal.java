package com.neoalive.tacz_sewv.mixin;

import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.TacticalManagerGoal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.util.SoldierState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.config.EasyTuning;
import com.neoalive.tacz_sewv.entity.ai.support.FollowLeash;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleCrew;

/**
 * SEM's tactical manager forces {@code SEEK_COVER} / flanks on every new contact. Under
 * FOLLOW_ME or an unfinished MOVE that peels the unit off its order. Mirror SEM's own
 * HOLD_POSITION short-circuit: keep a shootable state, drop the movement lock, and leave
 * locomotion to the priority-1 stick goals.
 *
 * <p>A unit crewing a hull takes the same short-circuit. Everything the manager decides (cover,
 * flank, rush, movement locks) is read only by MOVE goals that are already suppressed for crews,
 * so for a passenger its per-tick LOS raycast and decision context were pure cost (~1.3% of the
 * server thread in a large battle). The one thing given up is the crewman's feed into SEM's squad
 * memory; crews share contacts through our own contact board instead. Normalising the state here
 * also keeps a crewman from dismounting still locked in SEEK_COVER.
 */
@Mixin(TacticalManagerGoal.class)
public abstract class MixinTacticalManagerGoal {

    @Shadow(remap = false)
    @Final
    private AbstractUnit unit;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$honourOrderedMove(CallbackInfo ci) {
        if (!FollowLeash.ownsMove(this.unit) && !VehicleCrew.suppressOnFootAi(this.unit)) return;

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

    /**
     * Easy Mode: a timid hostile unit breaks off to cover at 60% health instead of SEM's 35%. SEM
     * compares against a literal in {@code tick}, not its own {@code RETREAT_HEALTH_PCT} field.
     */
    @ModifyConstant(method = "tick", constant = @Constant(floatValue = 0.35f), require = 0)
    private float tacz_sewv$easyRetreatThreshold(float original) {
        return EasyMode.timid(this.unit) ? (float) EasyTuning.RETREAT_HEALTH_FRACTION : original;
    }
}
