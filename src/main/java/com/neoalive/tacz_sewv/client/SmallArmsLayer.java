package com.neoalive.tacz_sewv.client;

import java.util.NoSuchElementException;

import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.atsuishio.superbwarfare.item.misc.MedicalKitItem;
import com.atsuishio.superbwarfare.item.misc.MonitorItem;
import com.atsuishio.superbwarfare.item.weapon.MilitaryShovelItem;
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

import com.neoalive.tacz_sewv.entity.ai.support.UnitHolster;

/**
 * Draws a SuperbWarfare gun / monitor / medical kit / military shovel in a unit's right hand.
 *
 * <p>SimpleEnemyMod's own {@code GunLayerRenderer} is the only held-item layer its unit renderers
 * have, and its second statement is
 * {@code if (!(stack.getItem() instanceof AbstractGunItem)) return;} — a <b>TACZ</b> gun item. An
 * SBW {@code GunItem} is an unrelated class, so an issued launcher was equipped, fired, and
 * completely invisible. The military shovel is likewise Geo/bedrock-rendered via its own
 * {@code BlockEntityWithoutLevelRenderer} — {@code renderStatic} picks that up once we let the
 * stack through this gate.
 *
 * <p>Placement matches SEM {@code GunLayerRenderer.renderStandardGun}.
 */
public class SmallArmsLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {

    private static final String UNIT_PART_NAME = "unit";
    private static final String RIGHT_ARM_PART_NAME = "rightArm";

    private HierarchicalModel<?> armModel;
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
                && !(stack.getItem() instanceof MonitorItem)
                && !(stack.getItem() instanceof MilitaryShovelItem)) return;

        if (!(this.getParentModel() instanceof HierarchicalModel<?> model)) return;

        if (model != this.armModel) {
            this.armModel = model;
            try {
                this.rightArm = model.root().getChild(UNIT_PART_NAME).getChild(RIGHT_ARM_PART_NAME);
            } catch (NoSuchElementException e) {
                this.rightArm = null;
            }
        }
        if (this.rightArm == null) return;

        poseStack.pushPose();
        this.rightArm.translateAndRotate(poseStack);

        // SEM GunLayerRenderer.renderStandardGun constants (TACZ parity).
        poseStack.translate(-0.06D, 0.73D, 0.3D);
        poseStack.mulPose(Axis.YP.rotationDegrees(-180));
        poseStack.mulPose(Axis.XP.rotationDegrees(-90));
        poseStack.scale(1.0F, -1.0F, -1.0F);

        Minecraft.getInstance().getItemRenderer().renderStatic(
                entity, stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false,
                poseStack, buffer, entity.level(), packedLight, OverlayTexture.NO_OVERLAY,
                entity.getId());

        poseStack.popPose();
    }
}
