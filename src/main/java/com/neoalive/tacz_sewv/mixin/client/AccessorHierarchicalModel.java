package com.neoalive.tacz_sewv.mixin.client;

import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.world.entity.AnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(HierarchicalModel.class)
public interface AccessorHierarchicalModel {
    @Invoker("animate")
    void tacz_sewv$invokeAnimate(AnimationState animationState, AnimationDefinition animationDefinition,
                                 float ageInTicks, float speed);
}
