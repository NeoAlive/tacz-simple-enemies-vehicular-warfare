package com.neoalive.tacz_sewv.mixin.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.client.SandbagSeatPose;
import com.neoalive.tacz_sewv.entity.SandbagSeatEntity;

/**
 * Applies the sandbag Bedrock seat pose to players (and any other humanoid, armour models
 * included) riding a {@link SandbagSeatEntity}. Head rotation is never written, so look stays
 * unlocked.
 *
 * <p>The {@code posed} flag exists because vanilla {@code setupAnim} rewrites rotations every
 * frame but never rewrites {@code body}'s position, so the seated offset would stay on this
 * shared model instance long after the rider got off. One restoring frame on the way out puts
 * the positions back to the bind pose and leaves the frame's rotations alone.
 *
 * <p>Injected at TAIL: {@code PlayerModel.setupAnim} copies body/arms/legs onto the outer
 * jacket and sleeve parts <em>after</em> its {@code super} call, so those follow the pose for
 * free.
 */
@Mixin(HumanoidModel.class)
public abstract class MixinHumanoidSandbagPose {

    @Unique
    private boolean tacz_sewv$sandbagPosed;

    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            at = @At("TAIL")
    )
    private void tacz_sewv$sandbagPose(LivingEntity living, float limbSwing, float limbSwingAmount,
                                       float ageInTicks, float netHeadYaw, float headPitch,
                                       CallbackInfo ci) {
        HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
        boolean seated = living.getVehicle() instanceof SandbagSeatEntity && SandbagSeatPose.isLoaded();

        if (seated) {
            SandbagSeatPose.applyToHumanoid(model.head, model.hat, model.body,
                    model.rightArm, model.leftArm, model.rightLeg, model.leftLeg);
            this.tacz_sewv$sandbagPosed = true;
        } else if (this.tacz_sewv$sandbagPosed) {
            SandbagSeatPose.restoreHumanoid(model.head, model.hat, model.body,
                    model.rightArm, model.leftArm, model.rightLeg, model.leftLeg);
            this.tacz_sewv$sandbagPosed = false;
        }
    }
}
