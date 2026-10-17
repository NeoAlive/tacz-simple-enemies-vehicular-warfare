package com.neoalive.tacz_sewv.client;

import java.util.NoSuchElementException;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.resource.pojo.display.gun.LayerGunShow;
import com.tacz.guns.util.math.MathUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.neoalive.tacz_sewv.entity.ai.support.UnitHolster;

/**
 * Draws a TACZ gun on the unit's body using the same FIXED / {@code offhand_show} transform
 * path as TACZ's player body mount — without calling {@code HumanoidOffhandRender} or the
 * offhand inventory pipeline. Stack comes from {@link UnitHolster#holsteredGun}.
 *
 * <p>SEM models parent under {@code fakeRoot → unit}; torso bob/sit lives on {@code unit}, so
 * the holster is parented there rather than at vanilla player body origin.
 */
public class HolsterLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {

    private static final String UNIT_PART_NAME = "unit";

    private HierarchicalModel<?> torsoModel;
    private ModelPart unit;

    public HolsterLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity,
                       float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        ItemStack stack = UnitHolster.holsteredGun(entity);
        if (stack.isEmpty()) return;

        if (!(this.getParentModel() instanceof HierarchicalModel<?> model)) return;
        if (model != this.torsoModel) {
            this.torsoModel = model;
            try {
                this.unit = model.root().getChild(UNIT_PART_NAME);
            } catch (NoSuchElementException e) {
                this.unit = null;
            }
        }
        if (this.unit == null) return;

        TimelessAPI.getGunDisplay(stack).ifPresent(display -> {
            LayerGunShow show = display.getOffhandShow();
            poseStack.pushPose();
            this.unit.translateAndRotate(poseStack);
            renderGunItem(entity, poseStack, buffer, packedLight, stack, show);
            poseStack.popPose();
        });
    }

    /** Same math as TACZ {@code HumanoidOffhandRender.renderGunItem}. */
    private static void renderGunItem(LivingEntity entity, PoseStack poseStack,
                                      MultiBufferSource buffer, int packedLight,
                                      ItemStack itemStack, LayerGunShow show) {
        Vector3f pos = show.getPos();
        Vector3f rotate = show.getRotate();
        Vector3f scale = show.getScale();
        poseStack.pushPose();
        poseStack.translate(-pos.x() / 16f, 1.5 - pos.y() / 16f, pos.z() / 16f);
        poseStack.scale(-scale.x(), -scale.y(), scale.z());
        Quaternionf rotation = new Quaternionf();
        MathUtil.toQuaternion(
                (float) Math.toRadians(rotate.x),
                (float) Math.toRadians(rotate.y),
                (float) Math.toRadians(rotate.z),
                rotation);
        poseStack.mulPose(rotation);
        Minecraft.getInstance().getItemRenderer().renderStatic(
                itemStack, ItemDisplayContext.FIXED, packedLight, OverlayTexture.NO_OVERLAY,
                poseStack, buffer, entity.level(), entity.getId());
        poseStack.popPose();
    }
}
