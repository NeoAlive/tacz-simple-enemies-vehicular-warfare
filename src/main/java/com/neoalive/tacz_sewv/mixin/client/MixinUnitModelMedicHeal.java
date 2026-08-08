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
import com.neoalive.tacz_sewv.entity.ai.support.MedicControl;

/**
 * While treating, skip SEM locomotion and play {@link SewvAnimationsDefinitions#UNIT_HEAL}.
 * Same dedicated-{@link AnimationState} shape as {@link MixinUnitModelDroneSit}.
 */
@Mixin({RUunitModel.class, USunitModel.class, PmcUnitModel.class})
public abstract class MixinUnitModelMedicHeal {

    @Inject(
            method = "setupAnim",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/entity/client/animation/core/LayeredAnimationManager;update(Lnet/minecraft/world/entity/Entity;I)V",
                    remap = false
            ),
            cancellable = true
    )
    private void tacz_sewv$healInsteadOfLocomotion(Entity entity, float limbSwing, float limbSwingAmount,
                                                   float ageInTicks, float netHeadYaw, float headPitch,
                                                   CallbackInfo ci) {
        AnimationState heal = MedicControl.treatAnimationState(entity);
        if (heal == null) return;

        if (!MedicControl.isTreating(entity)) {
            if (heal.isStarted()) heal.stop();
            return;
        }

        AbstractUnit unit = (AbstractUnit) entity;
        unit.walkAnimationState.stop();
        unit.idleAnimationState.stop();
        heal.startIfStopped(unit.tickCount);

        HierarchicalModel<?> model = (HierarchicalModel<?>) (Object) this;
        ((AccessorHierarchicalModel) model).tacz_sewv$invokeAnimate(
                heal, SewvAnimationsDefinitions.UNIT_HEAL, ageInTicks, 1.0f);

        if (unit.getAnimationManager() != null) {
            unit.getAnimationManager().applyProceduralLayers(model.root(), unit, ageInTicks);
        }
        ci.cancel();
    }
}
