package com.neoalive.tacz_sewv.mixin.client;

import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.client.pmc_unit.PmcUnitModel;
import net.nekoyuni.SimpleEnemyMod.entity.client.ru_unit.RUunitModel;
import net.nekoyuni.SimpleEnemyMod.entity.client.us_unit.USunitModel;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.client.animation.SewvAnimationsDefinitions;
import com.neoalive.tacz_sewv.entity.ai.support.DroneControl;

/**
 * While drone-control locked, skip SEM locomotion entirely and play a dedicated sit clip.
 *
 * <p>{@code LayeredAnimationManager.update} runs {@code BaseLocomotionLayer}, which stops/starts
 * {@code idleAnimationState} / {@code walkAnimationState} on any horizontal motion blip. Reusing
 * idle for sit meant the clip restarted from t=0 every time locomotion stopped it — flicker.
 * Cancelling at {@code update} keeps that layer off the path; sit uses its own
 * {@link AnimationState} that is only started once per lock.
 */
@Mixin({RUunitModel.class, USunitModel.class, PmcUnitModel.class})
public abstract class MixinUnitModelDroneSit {

    @Inject(
            method = "setupAnim",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/entity/client/animation/core/LayeredAnimationManager;update(Lnet/minecraft/world/entity/Entity;I)V",
                    remap = false
            ),
            cancellable = true
    )
    private void tacz_sewv$sitInsteadOfLocomotion(Entity entity, float limbSwing, float limbSwingAmount,
                                                   float ageInTicks, float netHeadYaw, float headPitch,
                                                   CallbackInfo ci) {
        AnimationState sit = DroneControl.sitAnimationState(entity);
        if (sit == null) return;

        if (!DroneControl.isLocked(entity)) {
            if (sit.isStarted()) sit.stop();
            return;
        }

        AbstractUnit unit = (AbstractUnit) entity;
        unit.walkAnimationState.stop();
        unit.idleAnimationState.stop();
        sit.startIfStopped(unit.tickCount);

        HierarchicalModel<?> model = (HierarchicalModel<?>) (Object) this;
        ((AccessorHierarchicalModel) model).tacz_sewv$invokeAnimate(
                sit, SewvAnimationsDefinitions.UNIT_SIT, ageInTicks, 1.0f);

        if (unit.getAnimationManager() != null) {
            unit.getAnimationManager().applyProceduralLayers(model.root(), unit, ageInTicks);
        }
        ci.cancel();
    }
}
