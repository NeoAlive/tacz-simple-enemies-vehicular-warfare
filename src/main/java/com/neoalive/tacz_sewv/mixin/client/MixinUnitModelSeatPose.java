package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.client.pmc_unit.PmcUnitModel;
import net.nekoyuni.SimpleEnemyMod.entity.client.ru_unit.RUunitModel;
import net.nekoyuni.SimpleEnemyMod.entity.client.us_unit.USunitModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sits a SimpleEnemyMod unit down when it rides a SuperbWarfare vehicle.
 *
 * <p>SEM's unit models drive every limb from a {@code LayeredAnimationManager} inside
 * {@code setupAnim} (locomotion + action + procedural aim layers) and, unlike vanilla
 * {@link net.minecraft.client.model.HumanoidModel}, never consult {@code EntityModel.riding}. So a
 * mounted unit keeps running/idling in place instead of sitting. This re-applies vanilla's riding
 * leg-bend at {@code setupAnim} TAIL — after the animation manager and the procedural layers, which
 * only touch head/arms — but only while the unit is a passenger of a {@link VehicleEntity}; on-foot
 * and vanilla riding (boats, etc.) are untouched.
 *
 * <p>Legs only. SBW does not pose its own player passengers' arms per seat either, so this matches
 * its rendering, and SEM's arm-aiming layer keeps the weapon working. {@code BedrockArmorLayer} and
 * {@code SmallArmsLayer}/{@code GunLayerRenderer} read these bones <em>after</em> {@code setupAnim},
 * so the leg/foot armor follows the seated legs with no change to those layers.
 */
@Mixin({RUunitModel.class, USunitModel.class, PmcUnitModel.class})
public abstract class MixinUnitModelSeatPose {

    @Inject(method = "setupAnim", at = @At("TAIL"))
    private void tacz_sewv$seatLegs(Entity entity, float limbSwing, float limbSwingAmount,
                                    float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (!(entity.getVehicle() instanceof VehicleEntity)) return;

        ModelPart root = ((HierarchicalModel<?>) (Object) this).root();
        if (!root.hasChild("unit")) return;
        ModelPart unit = root.getChild("unit");

        // Vanilla HumanoidModel's riding leg-bend.
        tacz_sewv$setLeg(unit, "rightLeg", -1.4137167F, 0.31415927F, 0.07853982F);
        tacz_sewv$setLeg(unit, "leftLeg", -1.4137167F, -0.31415927F, -0.07853982F);
    }

    @Unique
    private static void tacz_sewv$setLeg(ModelPart unit, String bone, float xRot, float yRot, float zRot) {
        if (!unit.hasChild(bone)) return;
        ModelPart leg = unit.getChild(bone);
        leg.xRot = xRot;
        leg.yRot = yRot;
        leg.zRot = zRot;
    }
}
