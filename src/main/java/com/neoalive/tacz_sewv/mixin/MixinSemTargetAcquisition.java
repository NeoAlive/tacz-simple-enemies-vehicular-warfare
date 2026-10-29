package com.neoalive.tacz_sewv.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.VerticalAwareTargetGoal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.entity.ai.sensor.UnitAcquisition;

/**
 * SEM infantry notice targets over time instead of instantly. SEM's proactive scan
 * ({@code VerticalAwareTargetGoal.canUse}) writes the candidate into vanilla's
 * {@code NearestAttackableTargetGoal.target} and answers true; at RETURN this asks
 * {@link UnitAcquisition} whether the unit has had long enough to notice it, and answers false
 * until it has. The scan keeps re-running, so acquisition lands on the first scan after the delay.
 *
 * <p>Only this proactive path is gated. Retaliation, radio orders ({@code AttackSpecificTargetGoal}),
 * drone broadcasts and anything at point blank go straight through. Extends the vanilla parent so
 * {@code target} and {@code mob} are plain inherited fields. {@code require = 0}: without it, SEM's
 * instant acquisition is unchanged.
 */
@Mixin(VerticalAwareTargetGoal.class)
public abstract class MixinSemTargetAcquisition<T extends LivingEntity> extends NearestAttackableTargetGoal<T> {

    private MixinSemTargetAcquisition(Mob mob, Class<T> targetType, boolean mustSee) {
        super(mob, targetType, mustSee);
    }

    @Inject(method = "canUse", at = @At("RETURN"), cancellable = true, require = 0)
    private void tacz_sewv$noticeOverTime(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || !(this.mob instanceof AbstractUnit unit) || this.target == null) return;
        if (UnitAcquisition.denies(unit, this.target, unit.level().getGameTime())) {
            cir.setReturnValue(false);
        }
    }
}
