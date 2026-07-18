package com.neoalive.tacz_sewv.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.ForgeHooksClient;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.GeckoCompat;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.GeckoCompatClient;

/**
 * Renders armor that supplies its own model (SuperbWarfare's helmets and vests, which are
 * simplebedrockmodel geometry) on a SimpleEnemyMod unit.
 *
 * <p><b>Why SEM's own layer can't.</b> {@code UniversalArmorLayer} assumes the model it gets back
 * from the Forge hook is a plain {@link HumanoidModel} whose {@code head}/{@code body}/limb parts
 * are what actually get drawn, so it poses those parts and then renders under
 * {@code translateToBody()} plus a hand-tuned {@code -0.85} block lift and ~2x part scales. A
 * bedrock armor renderer draws none of that: it draws its own bone tree, and it reads the pose
 * <em>once</em>, inside the hook call, off the {@code original} model handed to it. Everything SEM
 * does afterwards lands on parts that are never drawn, and the pose the armor did capture is
 * SEM's shared dummy model in whatever state the previous piece left it. That is the displacement
 * — helmets on the chest, vests low.
 *
 * <p><b>The contract the bedrock renderer actually wants</b> (read out of
 * {@code GeoArmorRenderer.preparePose} against the jar): each armor bone is placed at
 * {@code bindPivot + (originalPart.pos - bindPivot)}, i.e. exactly {@code originalPart.pos}, with
 * {@code originalPart}'s rotation. The bind pivot cancels, so the pose is purely relative to
 * whatever the PoseStack is at. That is why this layer feeds it the unit's part transforms
 * <em>local to the {@code unit} bone</em> and pushes {@code fakeRoot} and {@code unit} onto the
 * stack instead: the parent chain then does the composition, no offset table needed. SEM's units
 * are vanilla-proportioned ({@code UnitModelDefinitions} builds a standard humanoid, just parented
 * under a {@code fakeRoot} at (0,6,-1) and leaned 5°), so the armor lands on the body with no
 * fudge factors at all.
 *
 * <p>Only custom-model armor is drawn here. Plain texture armor is left to SEM's own layer, and
 * GeckoLib armor to SEM's GeckoLib one — neither of those exists on RU/US units, so ordinary armor
 * stays invisible on them; SBW's kit is bedrock and is the point of this.
 */
public class BedrockArmorLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    /**
     * Carries the wearer's pose into the Forge hook and nothing else — its geometry is never
     * drawn. Baked from the vanilla armor layer for the same reason the bedrock renderer bakes it:
     * the bind pivots have to be the standard humanoid ones for the deltas to mean anything.
     */
    private final HumanoidModel<LivingEntity> pose =
            new HumanoidModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));

    public BedrockArmorLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity,
                       float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
                       float netHeadYaw, float headPitch) {

        if (!(this.getParentModel() instanceof HierarchicalModel<?> model)) return;

        ModelPart fakeRoot = model.root();
        if (!fakeRoot.hasChild("unit")) return;
        ModelPart unit = fakeRoot.getChild("unit");

        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            if (GeckoCompat.LOADED && GeckoCompatClient.isGeckoArmor(stack)) continue;

            copyPose(unit);

            // The carrier hands over model PROPERTIES too, not just the pose: Forge follows the hook
            // below with copyModelProperties(carrier, armor), and `young` rides along. EntityModel
            // defaults it to TRUE and only LivingEntityRenderer ever clears it — on the parent model,
            // which this carrier is not. Leave it and the armor renderer runs scaleModelForBaby on
            // itself: helmet 0.75x and a block low, everything else HALF SIZE and lower still.
            this.pose.young = entity.isBaby();

            // Reading the pose is a side effect of this call, so the carrier must already be
            // filled — this is the one moment the armor looks at the wearer.
            Model armor = ForgeHooksClient.getArmorModel(entity, stack, slot, this.pose);
            if (armor == this.pose) continue; // no custom model: plain texture armor, not ours

            poseStack.pushPose();
            fakeRoot.translateAndRotate(poseStack);
            unit.translateAndRotate(poseStack);
            // The bedrock renderer builds its own vertex consumer from its own texture and
            // ignores the one passed in; this is only here so an override never sees null.
            armor.renderToBuffer(poseStack,
                    buffer.getBuffer(RenderType.armorCutoutNoCull(this.getTextureLocation(entity))),
                    packedLight, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
            poseStack.popPose();
        }
    }

    /** Mirrors the unit's live bone transforms onto the carrier's matching humanoid parts. */
    private void copyPose(ModelPart unit) {
        copy(unit, "head", this.pose.head);
        copy(unit, "body", this.pose.body);
        copy(unit, "rightArm", this.pose.rightArm);
        copy(unit, "leftArm", this.pose.leftArm);
        copy(unit, "rightLeg", this.pose.rightLeg);
        copy(unit, "leftLeg", this.pose.leftLeg);
    }

    private static void copy(ModelPart unit, String bone, ModelPart target) {
        if (unit.hasChild(bone)) target.copyFrom(unit.getChild(bone));
    }
}
