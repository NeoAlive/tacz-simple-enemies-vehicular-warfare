package com.neoalive.tacz_sewv.client;

import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.atsuishio.superbwarfare.item.misc.MedicalKitItem;
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

import java.util.NoSuchElementException;

/**
 * Draws a SuperbWarfare gun in a unit's right hand.
 *
 * <p>SimpleEnemyMod's own {@code GunLayerRenderer} is the only held-item layer its unit renderers
 * have, and its second statement is
 * {@code if (!(stack.getItem() instanceof AbstractGunItem)) return;} — a <b>TACZ</b> gun item. An
 * SBW {@code GunItem} is an unrelated class, so an issued launcher was equipped, fired, and
 * completely invisible. There is no vanilla {@code ItemInHandLayer} on these renderers to fall
 * back on either: SEM draws held guns exclusively through that one layer.
 *
 * <p>The two layers can never both draw: SEM's requires a TACZ item and this one requires an SBW
 * item, and no item is both. So this is additive — nothing about TACZ rifles changes.
 *
 * <p>The arm lookup and the placement transform are lifted verbatim from
 * {@code GunLayerRenderer.renderStandardGun} rather than re-derived, so an SBW launcher sits in
 * the hand exactly where a TACZ rifle does. If SEM ever re-tunes those numbers this will drift and
 * want the same edit — that is the cost of not being able to call a {@code private} method on a
 * layer we do not own, and it is cheaper than a mixin that would have to reproduce the same
 * constants anyway.
 *
 * <p>Hung on all three unit renderers by {@link ClientModEvents}, the same seam
 * {@link BedrockArmorLayer} uses.
 */
public class SmallArmsLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {

    /** SEM parents the whole humanoid under a {@code fakeRoot} → {@code unit} bone. */
    private static final String UNIT_PART_NAME = "unit";
    private static final String RIGHT_ARM_PART_NAME = "rightArm";

    public SmallArmsLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity,
                       float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
                       float netHeadYaw, float headPitch) {

        if (entity.isDeadOrDying()) return;

        ItemStack stack = entity.getItemInHand(InteractionHand.MAIN_HAND);
        // The whole gate. SEM's layer takes it from here for anything TACZ. A medical kit is not a
        // gun but has the same problem — a medic holding one would otherwise be empty-handed.
        if (!(stack.getItem() instanceof GunItem) && !(stack.getItem() instanceof MedicalKitItem)) return;

        if (!(this.getParentModel() instanceof HierarchicalModel<?> model)) return;

        ModelPart rightArm;
        try {
            rightArm = model.root().getChild(UNIT_PART_NAME).getChild(RIGHT_ARM_PART_NAME);
        } catch (NoSuchElementException e) {
            return; // a model shaped differently than SEM's: draw nothing rather than crash the frame
        }

        poseStack.pushPose();
        rightArm.translateAndRotate(poseStack);

        poseStack.translate(-0.06D, 0.73D, 0.3D);
        poseStack.mulPose(Axis.YP.rotationDegrees(-180));
        poseStack.mulPose(Axis.XP.rotationDegrees(-90));
        poseStack.scale(1.0F, -1.0F, -1.0F);

        // renderStatic is what ItemInHandRenderer delegates to, and it honours the item's custom
        // renderer — which matters here, because every SBW gun supplies its own geo model through
        // initializeClient rather than a flat item texture.
        Minecraft.getInstance().getItemRenderer().renderStatic(
                entity, stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false,
                poseStack, buffer, entity.level(), packedLight, OverlayTexture.NO_OVERLAY,
                entity.getId());

        poseStack.popPose();
    }
}
