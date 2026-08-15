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

import com.neoalive.tacz_sewv.client.SandbagSeatPose;
import com.neoalive.tacz_sewv.entity.SandbagSeatEntity;
import com.neoalive.tacz_sewv.entity.client.pmc_commander.PmcCommanderModel;

/**
 * Seat posing for SEM units: sandbag Bedrock pose, or vanilla riding legs on SBW vehicles.
 *
 * <p>SEM's unit models drive every limb from a {@code LayeredAnimationManager} inside
 * {@code setupAnim} and never consult {@code EntityModel.riding}. Sandbag pose and vehicle
 * sitting both apply at TAIL after that stack. Head and arms are left alone on sandbags so look
 * tracking and aiming stay unlocked.
 *
 * <p>No dismount cleanup here, unlike the player's {@code HumanoidModel} counterpart: SEM opens
 * every {@code setupAnim} with {@code root().getAllParts().forEach(ModelPart::resetPose)}, so a
 * seated bone's transform cannot survive into the next frame.
 */
@Mixin({RUunitModel.class, USunitModel.class, PmcUnitModel.class, PmcCommanderModel.class})
public abstract class MixinUnitModelSeatPose {

    @Inject(method = "setupAnim", at = @At("TAIL"))
    private void tacz_sewv$seatPose(Entity entity, float limbSwing, float limbSwingAmount,
                                    float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        ModelPart root = ((HierarchicalModel<?>) (Object) this).root();
        if (!root.hasChild("unit")) return;
        ModelPart unit = root.getChild("unit");

        if (entity.getVehicle() instanceof SandbagSeatEntity) {
            SandbagSeatPose.applyToUnit(unit);
            return;
        }

        if (!(entity.getVehicle() instanceof VehicleEntity)) return;

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
