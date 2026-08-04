package com.neoalive.tacz_sewv.client;

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

    // --- Monitor (drone tablet) placement — tweak these, then rebuild/reload client ---
    private static final double MONITOR_TX = 0.05D;
    private static final double MONITOR_TY = 0.35D;
    private static final double MONITOR_TZ = 0.12D;
    private static final float MONITOR_YAW = -90.0F;
    private static final float MONITOR_PITCH = -20.0F;
    private static final float MONITOR_SCALE = 0.7F;

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

        ItemStack stack = entity.getItemInHand(InteractionHand.MAIN_HAND);
        // SEM's gun layer takes TACZ; this layer takes SBW guns, medical kits, and the drone
        // Monitor — plain Items with no ItemInHandLayer on SEM unit renderers.
        if (!(stack.getItem() instanceof GunItem)
                && !(stack.getItem() instanceof MedicalKitItem)
                && !(stack.getItem() instanceof MonitorItem)) return;

        if (!(this.getParentModel() instanceof HierarchicalModel<?> model)) return;

        if (model != this.armModel) {
            this.armModel = model;
            try {
                this.rightArm = model.root().getChild(UNIT_PART_NAME).getChild(RIGHT_ARM_PART_NAME);
            } catch (NoSuchElementException e) {
                this.rightArm = null; // a model shaped differently than SEM's: draw nothing rather than crash
            }
        }
        if (this.rightArm == null) return;

        poseStack.pushPose();
        this.rightArm.translateAndRotate(poseStack);

        if (stack.getItem() instanceof MonitorItem) {
            // Tunable lap/hand placement under UNIT_SIT (gun offsets put this above the head).
            // Axes are AFTER rightArm.translateAndRotate — local to the folded arm bone:
            //   X = left(+)/right(-) of the arm   Y = along the arm toward fingertips(+)
            //   Z = forward(+)/back of the palm plane
            // Rotations: YP spins the tablet face; XP tips it toward/away from the lap.
            // Scale: negative Y/Z match SBW third-person item convention (same as guns).
            poseStack.translate(MONITOR_TX, MONITOR_TY, MONITOR_TZ);
            poseStack.mulPose(Axis.YP.rotationDegrees(MONITOR_YAW));
            poseStack.mulPose(Axis.XP.rotationDegrees(MONITOR_PITCH));
            poseStack.scale(MONITOR_SCALE, -MONITOR_SCALE, -MONITOR_SCALE);
        } else {
            poseStack.translate(-0.06D, 0.73D, 0.3D);
            poseStack.mulPose(Axis.YP.rotationDegrees(-180));
            poseStack.mulPose(Axis.XP.rotationDegrees(-90));
            poseStack.scale(1.0F, -1.0F, -1.0F);
        }

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
