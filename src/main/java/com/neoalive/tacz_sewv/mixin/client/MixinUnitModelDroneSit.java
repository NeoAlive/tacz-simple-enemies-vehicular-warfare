package com.neoalive.tacz_sewv.mixin.client;

import com.neoalive.tacz_sewv.client.animation.SewvAnimationsDefinitions;
import com.neoalive.tacz_sewv.entity.ai.DroneControl;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.client.pmc_unit.PmcUnitModel;
import net.nekoyuni.SimpleEnemyMod.entity.client.ru_unit.RUunitModel;
import net.nekoyuni.SimpleEnemyMod.entity.client.us_unit.USunitModel;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While drone-control locked, force sit regardless of locomotion's idle/walk flip.
 *
 * <p>SEM's {@code BaseLocomotionLayer} treats any horizontal {@code deltaMovement} &gt; 1e-6 as
 * walk and <b>stops</b> {@code idleAnimationState}. A prior sit path gated on idle being started,
 * so tiny client motion blips (while the drone was steered) flipped sit↔walk every frame — a hard
 * pop because {@code UNIT_SIT} drops the figure by 10.
 */
@Mixin(value = {RUunitModel.class, USunitModel.class, PmcUnitModel.class}, remap = false)
public abstract class MixinUnitModelDroneSit {

    /** Zero XZ before locomotion samples motion, so walkCondition stays false while locked. */
    @Inject(
            method = "setupAnim",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/entity/client/animation/core/LayeredAnimationManager;update(Lnet/minecraft/world/entity/Entity;I)V"
            )
    )
    private void tacz_sewv$freezeMotionBeforeUpdate(Entity entity, float limbSwing, float limbSwingAmount,
                                                    float ageInTicks, float netHeadYaw, float headPitch,
                                                    CallbackInfo ci) {
        if (!(entity instanceof AbstractUnit unit) || !DroneControl.isLocked(unit)) return;
        unit.setDeltaMovement(unit.getDeltaMovement().multiply(0.0, 1.0, 0.0));
    }

    @Inject(
            method = "setupAnim",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/AnimationState;isStarted()Z",
                    ordinal = 2
            ),
            cancellable = true
    )
    private void tacz_sewv$sitWhenLocked(Entity entity, float limbSwing, float limbSwingAmount,
                                         float ageInTicks, float netHeadYaw, float headPitch,
                                         CallbackInfo ci) {
        if (!(entity instanceof AbstractUnit unit)) return;
        if (!DroneControl.isLocked(unit)) return;

        // Depend on lock, not on locomotion having won idle — walk may still have been started
        // before freezeMotion applied, or from a prior frame.
        unit.walkAnimationState.stop();
        unit.idleAnimationState.startIfStopped(unit.tickCount);

        HierarchicalModel<?> model = (HierarchicalModel<?>) (Object) this;
        ((AccessorHierarchicalModel) model).tacz_sewv$invokeAnimate(
                unit.idleAnimationState, SewvAnimationsDefinitions.UNIT_SIT, ageInTicks, 1.0f);

        if (unit.getAnimationManager() != null) {
            unit.getAnimationManager().applyProceduralLayers(model.root(), unit, ageInTicks);
        }
        ci.cancel();
    }
}
