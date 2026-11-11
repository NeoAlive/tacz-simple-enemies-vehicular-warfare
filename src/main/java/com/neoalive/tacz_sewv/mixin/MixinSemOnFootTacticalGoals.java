package com.neoalive.tacz_sewv.mixin;

import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.PeekFromCoverGoal;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.SeekCoverGoal;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.TacticalInvestigateSoundGoal;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.TacticalManeuverGoal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.entity.ai.support.CoverHoldOrders;
import com.neoalive.tacz_sewv.entity.ai.support.FollowLeash;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleCrew;

/**
 * SEM tactical MOVE goals (cover, peek, maneuver, sound-investigate) outrank or contend with
 * CommanderOrderGoal's follow (SeekCover is priority 2; follow is 3). Two cases must not let
 * them steal MOVE:
 * <ul>
 *   <li>crewing an SBW hull — drive / mortar / bail own locomotion;</li>
 *   <li>{@link FollowLeash#ownsMove} — FOLLOW/FORM/sweep glue, or MOVE still en route;
 *       HOLD and arrived MOVE still take local cover.</li>
 * </ul>
 * Seatless mortar crews are on foot beside the tube and are left alone unless sticks-to-leader.
 */
@Mixin({
        SeekCoverGoal.class,
        PeekFromCoverGoal.class,
        TacticalManeuverGoal.class,
        TacticalInvestigateSoundGoal.class
})
public abstract class MixinSemOnFootTacticalGoals {

    @Shadow(remap = false)
    @Final
    private AbstractUnit unit;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$blockCombatMove(CallbackInfoReturnable<Boolean> cir) {
        if (tacz_sewv$blocked()) cir.setReturnValue(false);
    }

    @Inject(method = "canContinueToUse", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$stopCombatMove(CallbackInfoReturnable<Boolean> cir) {
        if (tacz_sewv$blocked()) cir.setReturnValue(false);
    }

    /**
     * A Cover and Hold order has already picked the cover (SEM's own finder, from the order), so SEM may
     * not go looking for another or flank off it. The one SEM goal it keeps is the peek, once holding.
     */
    @Unique
    private boolean tacz_sewv$blocked() {
        if (VehicleCrew.suppressOnFootAi(this.unit) || FollowLeash.ownsMove(this.unit)) return true;
        if (this.unit.getVehicle() != null || !CoverHoldOrders.has(this.unit)) return false;
        return !((Object) this instanceof PeekFromCoverGoal && CoverHoldOrders.holding(this.unit));
    }
}
