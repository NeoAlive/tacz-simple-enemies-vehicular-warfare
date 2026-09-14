package com.neoalive.tacz_sewv.client;

import java.lang.reflect.Method;
import java.util.NoSuchElementException;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.neoalive.tacz_sewv.compat.ColtanCompat;
import com.neoalive.tacz_sewv.entity.ai.support.UnitHolster;

/**
 * SEM-unit TACZ guns through Coltan → GemRender. Pose constants match
 * {@code GunLayerRenderer}; draw is reflection so a clean checkout without
 * {@code libs/coltan.jar} still compiles.
 */
public class TaczGemHeldLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final String DRAW_CLASS = "com.neoalive.coltan.client.compat.TaczGunDraw";
    private static final String UNIT_PART_NAME = "unit";
    private static final String RIGHT_ARM_PART_NAME = "rightArm";

    private static Method canDraw;
    private static Method trySubmit;
    private static boolean resolved;
    private static boolean unavailable;

    private HierarchicalModel<?> armModel;
    private ModelPart rightArm;

    public TaczGemHeldLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    /** True when Coltan+GemRender can draw this TACZ stack (for GunLayer cancel). */
    public static boolean coltanHandles(ItemStack stack) {
        if (!ColtanCompat.bridgeActive() || stack == null || stack.isEmpty()) {
            return false;
        }
        if (!(stack.getItem() instanceof AbstractGunItem)) {
            return false;
        }
        ensureResolved();
        if (unavailable || canDraw == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(canDraw.invoke(null, stack));
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Coltan TaczGunDraw.canDraw failed", e);
            unavailable = true;
            return false;
        }
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity,
                       float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (!ColtanCompat.bridgeActive()) {
            return;
        }
        if (entity.isDeadOrDying()) {
            return;
        }
        if (UnitHolster.hideHeldItems(entity)) {
            return;
        }

        ItemStack stack = entity.getItemInHand(InteractionHand.MAIN_HAND);
        if (!(stack.getItem() instanceof AbstractGunItem gunItem)) {
            return;
        }
        if (!coltanHandles(stack)) {
            return;
        }

        if (!(this.getParentModel() instanceof HierarchicalModel<?> model)) {
            return;
        }
        if (model != this.armModel) {
            this.armModel = model;
            try {
                this.rightArm = model.root().getChild(UNIT_PART_NAME).getChild(RIGHT_ARM_PART_NAME);
            } catch (NoSuchElementException e) {
                this.rightArm = null;
            }
        }
        if (this.rightArm == null) {
            return;
        }

        ensureResolved();
        if (unavailable || trySubmit == null) {
            return;
        }

        poseStack.pushPose();
        try {
            this.rightArm.translateAndRotate(poseStack);
            ResourceLocation gunId = gunItem.getGunId(stack);
            boolean minigun = gunId != null && gunId.getPath().contains("minigun");
            if (minigun) {
                poseStack.translate(-0.03D, 0.85D, 0.1D);
                poseStack.mulPose(Axis.YP.rotationDegrees(-180));
                poseStack.mulPose(Axis.XP.rotationDegrees(-85));
                poseStack.mulPose(Axis.ZP.rotationDegrees(6));
            } else {
                poseStack.translate(-0.06D, 0.73D, 0.3D);
                poseStack.mulPose(Axis.YP.rotationDegrees(-180));
                poseStack.mulPose(Axis.XP.rotationDegrees(-90));
            }
            poseStack.scale(1.0F, -1.0F, -1.0F);
            trySubmit.invoke(null, entity, stack, poseStack, packedLight);
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Coltan TaczGunDraw.trySubmit failed", e);
            unavailable = true;
        } finally {
            poseStack.popPose();
        }
    }

    private static void ensureResolved() {
        if (resolved || unavailable) {
            return;
        }
        resolved = true;
        try {
            Class<?> draw = Class.forName(DRAW_CLASS);
            canDraw = draw.getMethod("canDraw", ItemStack.class);
            trySubmit = draw.getMethod("trySubmit", LivingEntity.class, ItemStack.class, PoseStack.class,
                    int.class);
            LOGGER.info("Bound SEWV TaczGemHeldLayer to Coltan TaczGunDraw");
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Coltan present but TaczGunDraw unavailable", e);
            unavailable = true;
        }
    }
}
