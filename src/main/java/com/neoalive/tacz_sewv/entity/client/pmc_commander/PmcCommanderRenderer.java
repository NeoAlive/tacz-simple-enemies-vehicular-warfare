package com.neoalive.tacz_sewv.entity.client.pmc_commander;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.nekoyuni.SimpleEnemyMod.config.ClientConfig;
import net.nekoyuni.SimpleEnemyMod.entity.client.GunLayerRenderer;
import org.joml.Quaternionf;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;

/**
 * A fresh top-level renderer (see {@link PmcCommanderModel} for why it cannot subclass
 * {@code PmcUnitRenderer}). Adds SEM's own {@code GunLayerRenderer} itself, since that draw call
 * normally comes from {@code PmcUnitRenderer}'s constructor and there is nothing to inherit it
 * from here; the SBW armor/gun/holster/curios layers are added externally by
 * {@code ClientModEvents.addUnitLayers}, the same as every other unit renderer in this mod.
 */
public class PmcCommanderRenderer extends MobRenderer<PmcCommanderEntity, PmcCommanderModel<PmcCommanderEntity>> {

    private static final ResourceLocation COMMANDER_SKIN =
            new ResourceLocation(TaczSewv.MODID, "skins/pmc_commander.png");

    public PmcCommanderRenderer(EntityRendererProvider.Context context) {
        super(context, new PmcCommanderModel<>(context.bakeLayer(PmcCommanderModelLayers.PMC_COMMANDER_LAYER)), 0.5f);
        this.addLayer(new GunLayerRenderer<>(this, context.getItemInHandRenderer()));
        this.addLayer(new CommanderBeretLayer(this));
    }

    /** Fallback when {@code MixinUnitRenderer}'s pooled-uniform inject finds no camo for this unit. */
    @Override
    public ResourceLocation getTextureLocation(PmcCommanderEntity entity) {
        return COMMANDER_SKIN;
    }

    @Override
    public boolean shouldRender(PmcCommanderEntity entity, Frustum frustum, double camX, double camY, double camZ) {
        int configDist = ClientConfig.RENDER_DISTANCE.get();
        double maxDistance = (double) configDist * configDist;

        double dx = entity.getX() - camX;
        double dy = entity.getY() - camY;
        double dz = entity.getZ() - camZ;
        double distanceSq = dx * dx + dy * dy + dz * dz;

        if (distanceSq <= maxDistance) return true;
        return super.shouldRender(entity, frustum, camX, camY, camZ);
    }

    @Override
    protected void setupRotations(PmcCommanderEntity entity, PoseStack poseStack,
                                  float ageInTicks, float bodyYRot, float partialTicks) {
        if (entity.deathAnimationState.isStarted()) {
            this.model.setupAnim(entity, 0, 0, entity.tickCount + partialTicks, 0, 0);

            float rootMotionX = this.model.root().x / 16.0F;
            float rootMotionY = this.model.root().y / 16.0F;
            float rootMotionZ = this.model.root().z / 16.0F;
            float rootRotX = this.model.root().xRot;
            float rootRotY = this.model.root().yRot;
            float rootRotZ = this.model.root().zRot;

            poseStack.translate(rootMotionX, rootMotionY, rootMotionZ);
            poseStack.mulPose(new Quaternionf().rotationXYZ(rootRotX, rootRotY, rootRotZ));

            this.model.root().setPos(0, 0, 0);
            this.model.root().setRotation(0, 0, 0);

            float bodyRotation = Mth.lerp(partialTicks, entity.yBodyRotO, entity.yBodyRot);
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - bodyRotation));
            return;
        }

        super.setupRotations(entity, poseStack, ageInTicks, bodyYRot, partialTicks);
    }
}
