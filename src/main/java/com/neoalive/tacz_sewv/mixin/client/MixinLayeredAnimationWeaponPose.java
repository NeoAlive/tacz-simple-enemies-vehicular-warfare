package com.neoalive.tacz_sewv.mixin.client;

import com.neoalive.tacz_sewv.entity.ai.DroneControl;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.client.animation.core.LayeredAnimationManager;
import net.nekoyuni.SimpleEnemyMod.entity.client.animation.procedural.AdvancedWeaponPoseLayer;
import net.nekoyuni.SimpleEnemyMod.entity.client.animation.procedural.IProceduralLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Skip SEM weapon-arm posing while drone-control locked so sit + Monitor are not overwritten.
 * Head tracking still runs.
 */
@Mixin(value = LayeredAnimationManager.class, remap = false)
public abstract class MixinLayeredAnimationWeaponPose {

    @Shadow @Final private List<IProceduralLayer> proceduralLayers;

    @Inject(method = "applyProceduralLayers", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$skipWeaponPoseWhenLocked(ModelPart root, Entity entity, float partialTick,
                                                    CallbackInfo ci) {
        if (!DroneControl.isLocked(entity)) return;
        ci.cancel();
        for (IProceduralLayer layer : this.proceduralLayers) {
            if (layer instanceof AdvancedWeaponPoseLayer) continue;
            if (layer.isEnabled()) {
                layer.apply(root, entity, partialTick);
            }
        }
    }
}
