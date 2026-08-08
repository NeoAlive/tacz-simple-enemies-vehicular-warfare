package com.neoalive.tacz_sewv.client;

import java.util.NoSuchElementException;

import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.atsuishio.superbwarfare.item.misc.MedicalKitItem;
import com.atsuishio.superbwarfare.item.misc.MonitorItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import com.neoalive.tacz_sewv.entity.ai.support.DroneControl;
import com.neoalive.tacz_sewv.entity.ai.support.MedicControl;
import com.neoalive.tacz_sewv.entity.ai.support.UnitHolster;

/**
 * Draws a SuperbWarfare gun / monitor / medical kit in a unit's right hand.
 *
 * <p>SimpleEnemyMod's own {@code GunLayerRenderer} is the only held-item layer its unit renderers
 * have, and its second statement is
 * {@code if (!(stack.getItem() instanceof AbstractGunItem)) return;} — a <b>TACZ</b> gun item. An
 * SBW {@code GunItem} is an unrelated class, so an issued launcher was equipped, fired, and
 * completely invisible.
 *
 * <p>SEM parents the humanoid under {@code fakeRoot → unit}. Sit/heal clips move {@code unit}
 * down, so item draws must apply {@code unit} then {@code rightArm} — arm-only parenting left
 * kits on the face and monitors above the shoulder while the mesh crouched.
 */
public class SmallArmsLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {

    private static final String UNIT_PART_NAME = "unit";
    private static final String RIGHT_ARM_PART_NAME = "rightArm";

    // --- Monitor (drone tablet) — local to the arm after unit+arm chain ---
    private static final double MONITOR_TX = 0.05D;
    private static final double MONITOR_TY = 0.38D;
    private static final double MONITOR_TZ = 0.10D;
    private static final float MONITOR_YAW = -90.0F;
    private static final float MONITOR_PITCH = -25.0F;
    private static final float MONITOR_SCALE = 0.65F;

    // --- Medical kit: lap at rest; heal offsets only while treating ---
    private static final double MEDKIT_IDLE_TX = 0.02D;
    private static final double MEDKIT_IDLE_TY = 0.42D;
    private static final double MEDKIT_IDLE_TZ = 0.08D;
    private static final float MEDKIT_IDLE_YAW = -90.0F;
    private static final float MEDKIT_IDLE_PITCH = -20.0F;
    private static final float MEDKIT_IDLE_SCALE = 0.60F;

    private static final double MEDKIT_HEAL_TX = 0.02D;
    private static final double MEDKIT_HEAL_TY = 0.48D;
    private static final double MEDKIT_HEAL_TZ = 0.12D;
    private static final float MEDKIT_HEAL_YAW = -95.0F;
    private static final float MEDKIT_HEAL_PITCH = -40.0F;
    private static final float MEDKIT_HEAL_SCALE = 0.60F;

    private HierarchicalModel<?> armModel;
    private ModelPart unit;
    private ModelPart rightArm;

    public SmallArmsLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity,
                       float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
                       float netHeadYaw, float headPitch) {

        if (entity.isDeadOrDying()) return;
        if (UnitHolster.hideHeldItems(entity)) return;

        ItemStack stack = entity.getItemInHand(InteractionHand.MAIN_HAND);
        if (!(stack.getItem() instanceof GunItem)
                && !(stack.getItem() instanceof MedicalKitItem)
                && !(stack.getItem() instanceof MonitorItem)) return;

        if (!(this.getParentModel() instanceof HierarchicalModel<?> model)) return;

        if (model != this.armModel) {
            this.armModel = model;
            try {
                this.unit = model.root().getChild(UNIT_PART_NAME);
                this.rightArm = this.unit.getChild(RIGHT_ARM_PART_NAME);
            } catch (NoSuchElementException e) {
                this.unit = null;
                this.rightArm = null;
            }
        }
        if (this.unit == null || this.rightArm == null) return;

        poseStack.pushPose();
        // Sit/heal move `unit` down; arm-only parenting left kits/monitors at standing height.
        // Guns keep SEM's arm-only chain so TACZ parity offsets stay valid.
        boolean crouchedItem = stack.getItem() instanceof MonitorItem
                || stack.getItem() instanceof MedicalKitItem;
        if (crouchedItem || MedicControl.isTreating(entity) || DroneControl.isLocked(entity)) {
            this.unit.translateAndRotate(poseStack);
        }
        this.rightArm.translateAndRotate(poseStack);

        if (stack.getItem() instanceof MonitorItem) {
            // After unit+arm: X left(+)/right(-), Y toward fingertips(+), Z palm forward(+).
            poseStack.translate(MONITOR_TX, MONITOR_TY, MONITOR_TZ);
            poseStack.mulPose(Axis.YP.rotationDegrees(MONITOR_YAW));
            poseStack.mulPose(Axis.XP.rotationDegrees(MONITOR_PITCH));
            poseStack.scale(MONITOR_SCALE, -MONITOR_SCALE, -MONITOR_SCALE);
        } else if (stack.getItem() instanceof MedicalKitItem) {
            boolean healing = MedicControl.isTreating(entity);
            double tx = healing ? MEDKIT_HEAL_TX : MEDKIT_IDLE_TX;
            double ty = healing ? MEDKIT_HEAL_TY : MEDKIT_IDLE_TY;
            double tz = healing ? MEDKIT_HEAL_TZ : MEDKIT_IDLE_TZ;
            float yaw = healing ? MEDKIT_HEAL_YAW : MEDKIT_IDLE_YAW;
            float pitch = healing ? MEDKIT_HEAL_PITCH : MEDKIT_IDLE_PITCH;
            float scale = healing ? MEDKIT_HEAL_SCALE : MEDKIT_IDLE_SCALE;
            poseStack.translate(tx, ty, tz);
            poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
            poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
            poseStack.scale(scale, -scale, -scale);
        } else {
            // SEM GunLayerRenderer.renderStandardGun constants (TACZ parity).
            poseStack.translate(-0.06D, 0.73D, 0.3D);
            poseStack.mulPose(Axis.YP.rotationDegrees(-180));
            poseStack.mulPose(Axis.XP.rotationDegrees(-90));
            poseStack.scale(1.0F, -1.0F, -1.0F);
        }

        Minecraft.getInstance().getItemRenderer().renderStatic(
                entity, stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false,
                poseStack, buffer, entity.level(), packedLight, OverlayTexture.NO_OVERLAY,
                entity.getId());

        poseStack.popPose();
    }
}
