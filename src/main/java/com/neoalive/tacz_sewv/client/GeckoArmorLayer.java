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
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.internal.GeckoArmorAdjuster;
import software.bernie.geckolib.renderer.GeoArmorRenderer;

/**
 * Draws GeckoLib armor ({@code GeoItem} + {@link GeoArmorRenderer}) on SEM units whose renderer
 * never got SEM's own gecko layer.
 *
 * <p>SEM only hangs {@code GeckoArmorLayerImpl} on {@code PmcUnitRenderer}. RU/US (and this mod's
 * support / commander renderers) therefore equip DragonRise / other GeoItem kits invisibly —
 * {@link BedrockArmorLayer} deliberately skips gecko, and SEM's UniversalArmorLayer only exists on
 * PMC. This layer mirrors SEM's pose path: bake the {@code unit} bone's transform into a vanilla
 * humanoid carrier, hand that to {@code prepForRender}, then apply SEM's
 * {@link GeckoArmorAdjuster} so the kit lands the same way it does on a PMC.
 *
 * <p>Do <b>not</b> hang this on {@code pmcunit} — SEM's layer already draws there and a second
 * pass would double the mesh.
 */
public class GeckoArmorLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    private final HumanoidModel<LivingEntity> pose =
            new HumanoidModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));

    public GeckoArmorLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity,
                       float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
                       float netHeadYaw, float headPitch) {

        if (!GeckoCompat.LOADED) return;
        if (!(this.getParentModel() instanceof HierarchicalModel<?> model)) return;

        ModelPart root = model.root();
        if (!root.hasChild("unit")) return;
        ModelPart unit = root.getChild("unit");

        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (stack.isEmpty() || !GeckoCompatClient.isGeckoArmor(stack)) continue;

            syncFromUnit(unit);
            this.pose.young = entity.isBaby();

            Model armor = ForgeHooksClient.getArmorModel(entity, stack, slot, this.pose);
            if (!(armor instanceof HumanoidModel<?> humanoid) || armor == this.pose) continue;

            if (armor instanceof GeoArmorRenderer<?> geo) {
                // DragonRise's IClientItemExtensions already prepForRender inside the Forge hook;
                // re-bind with our posed carrier so applyBaseTransformations reads the unit pose.
                geo.prepForRender(entity, stack, slot, this.pose);
                GeckoArmorAdjuster.applyAdjustments(geo, this.pose, slot);
            }

            poseStack.pushPose();
            // GeoArmorRenderer.renderToBuffer ignores this VertexConsumer's texture and builds its
            // own from the GeoModel — same call shape SEM's gecko layer uses.
            humanoid.renderToBuffer(poseStack,
                    buffer.getBuffer(RenderType.armorCutoutNoCull(this.getTextureLocation(entity))),
                    packedLight, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
            poseStack.popPose();
        }
    }

    /**
     * Same composition SEM's {@code syncModelParts} uses: copy each limb, then add the {@code unit}
     * bone's translate+rotate so the armor follows the hierarchical model when drawn at entity root
     * (GeckoLib's own {@code translate(0,1.5)/scale(-1,-1,1)} expects that).
     */
    private void syncFromUnit(ModelPart unit) {
        float ux = unit.x;
        float uy = unit.y;
        float uz = unit.z;
        float urx = unit.xRot;
        float ury = unit.yRot;
        float urz = unit.zRot;

        copyWithUnitOffset(unit, "head", this.pose.head, ux, uy, uz, urx, ury, urz);
        copyWithUnitOffset(unit, "body", this.pose.body, ux, uy, uz, urx, ury, urz);
        copyWithUnitOffset(unit, "rightArm", this.pose.rightArm, ux, uy, uz, urx, ury, urz);
        copyWithUnitOffset(unit, "leftArm", this.pose.leftArm, ux, uy, uz, urx, ury, urz);
        copyWithUnitOffset(unit, "rightLeg", this.pose.rightLeg, ux, uy, uz, urx, ury, urz);
        copyWithUnitOffset(unit, "leftLeg", this.pose.leftLeg, ux, uy, uz, urx, ury, urz);

        this.pose.crouching = false;
        this.pose.riding = false;
        this.pose.young = false;
    }

    private static void copyWithUnitOffset(ModelPart unit, String bone, ModelPart target,
                                           float ux, float uy, float uz,
                                           float urx, float ury, float urz) {
        if (!unit.hasChild(bone)) return;
        boolean visible = target.visible;
        target.setPos(0.0F, 0.0F, 0.0F);
        target.setRotation(0.0F, 0.0F, 0.0F);
        target.xScale = 1.0F;
        target.yScale = 1.0F;
        target.zScale = 1.0F;
        target.copyFrom(unit.getChild(bone));
        target.visible = visible;
        target.x += ux;
        target.y += uy;
        target.z += uz;
        target.xRot += urx;
        target.yRot += ury;
        target.zRot += urz;
    }
}
