package com.neoalive.tacz_sewv.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

/**
 * Draws Curios {@code head} items on SEM units (HierarchicalModel / SimpleBedrockModel).
 *
 * <p>Curios' own layer only hangs on player skins, and SBW's thermal-goggles
 * {@code ICurioRenderer.followHeadRotations} no-ops unless the entity model is a
 * {@code HumanoidModel} — SEM's is not. Mirror {@link BedrockArmorLayer}: push
 * {@code fakeRoot}→{@code unit}, copy the live {@code head} bone onto the goggles mesh, draw.
 *
 * <p>Geometry is SBW's registered {@code thermal_imaging_goggles} layer (the motivating case).
 * Other head curios with a registered Curios renderer reuse that pose path and a
 * {@code textures/curio/<path>.png} convention.
 */
public class CuriosHeadLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {

    private static final String HEAD_SLOT = "head";
    private static final ModelLayerLocation GOGGLES_LAYER =
            new ModelLayerLocation(new ResourceLocation("superbwarfare", "thermal_imaging_goggles"), "main");

    private ModelPart bone;

    public CuriosHeadLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity,
                       float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
                       float netHeadYaw, float headPitch) {

        ItemStack stack = headCurio(entity);
        if (stack.isEmpty()) return;
        Item item = stack.getItem();
        if (CuriosRendererRegistry.getRenderer(item).isEmpty()) return;

        if (!(this.getParentModel() instanceof HierarchicalModel<?> model)) return;
        ModelPart fakeRoot = model.root();
        if (!fakeRoot.hasChild("unit")) return;
        ModelPart unit = fakeRoot.getChild("unit");
        if (!unit.hasChild("head")) return;
        ModelPart head = unit.getChild("head");

        ModelPart goggles = bone();
        if (goggles == null) return;
        goggles.copyFrom(head);

        ResourceLocation texture = textureFor(item);
        VertexConsumer consumer = ItemRenderer.getArmorFoilBuffer(
                buffer, RenderType.armorCutoutNoCull(texture), false, stack.hasFoil());

        poseStack.pushPose();
        fakeRoot.translateAndRotate(poseStack);
        unit.translateAndRotate(poseStack);
        goggles.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
        poseStack.popPose();
    }

    private ModelPart bone() {
        if (this.bone != null) return this.bone;
        try {
            ModelPart root = Minecraft.getInstance().getEntityModels().bakeLayer(GOGGLES_LAYER);
            this.bone = root.getChild("bone");
            return this.bone;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static ItemStack headCurio(LivingEntity entity) {
        ICuriosItemHandler curios = CuriosApi.getCuriosInventory(entity).orElse(null);
        if (curios == null) return ItemStack.EMPTY;
        ICurioStacksHandler handler = curios.getStacksHandler(HEAD_SLOT).orElse(null);
        if (handler == null || handler.getSlots() < 1) return ItemStack.EMPTY;
        return handler.getStacks().getStackInSlot(0);
    }

    private static ResourceLocation textureFor(Item item) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (id == null) {
            return new ResourceLocation("superbwarfare", "textures/curio/thermal_imaging_goggles.png");
        }
        return new ResourceLocation(id.getNamespace(), "textures/curio/" + id.getPath() + ".png");
    }
}
