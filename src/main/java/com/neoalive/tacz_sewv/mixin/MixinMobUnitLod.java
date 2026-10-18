package com.neoalive.tacz_sewv.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.neoalive.tacz_sewv.entity.ai.support.UnitLod;

/**
 * The single hook behind {@link UnitLod}.
 *
 * <p>{@code Mob.serverAiStep} (final, so no override is possible) runs full goal selection
 * — {@code targetSelector.tick()} then {@code goalSelector.tick()} — on vanilla's even ticks, and
 * only {@code tickRunningGoals(false)} on odd ones. This redirects both {@code tick()} calls: for a
 * throttled unit whose evaluation is not due, the call becomes {@code tickRunningGoals(false)},
 * which is exactly a vanilla odd tick. It is never replaced with nothing — goals that require
 * every-tick updates (SEM cover and move-to-range movement, grenade aiming) must keep ticking.
 * Every other mob passes straight through; nothing else in the pack targets these calls.
 */
@Mixin(Mob.class)
public abstract class MixinMobUnitLod {

    @Redirect(
            method = "serverAiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tick()V"))
    private void tacz_sewv$throttledSelection(GoalSelector selector) {
        if ((Object) this instanceof AbstractUnit unit && !UnitLod.due(unit)) {
            selector.tickRunningGoals(false);
        } else {
            selector.tick();
        }
    }
}
