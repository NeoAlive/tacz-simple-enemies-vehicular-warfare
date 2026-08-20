package com.neoalive.tacz_sewv.entity.client.pmc_commander;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.client.animation.ModAnimationsDefinitions;
import net.nekoyuni.SimpleEnemyMod.entity.client.animation.config.UnitAnimationConfig;
import net.nekoyuni.SimpleEnemyMod.entity.client.util.IArmorBoneProvider;
import net.nekoyuni.SimpleEnemyMod.entity.client.util.UnitModelDefinitions;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.bridge.IPmcDowned;
import com.neoalive.tacz_sewv.client.DownedUnitPose;

/**
 * A fresh top-level model, not a {@code PmcUnitModel} subclass — that renderer's {@code model}
 * field is {@code protected final}, so a subclass cannot swap model types. {@code setupAnim} below
 * is otherwise a byte-for-byte copy of SEM's own {@code PmcUnitModel}: the Commander's animation is
 * completely unwired from any custom pose and pulls from the same idle/walk/hurt/death clips and
 * procedural layers (head tracking, weapon-hold arm pose) every regular PMC uses. Only the skin,
 * beret overlay and seat restriction (handled elsewhere) make the Commander visually distinct.
 *
 * <p>A Commander is still a {@code PmcUnitEntity} underneath (promotion is a flag, not a different
 * entity class), so it can still be downed ({@link IPmcDowned}) — but it renders through THIS class,
 * not {@code PmcUnitModel}, so {@code mixin.client.MixinPmcDownedPose} (which only targets
 * {@code PmcUnitModel}, a third-party SEM class) never reaches it. Since this class is our own, the
 * downed-pose guard is written directly at the top of {@link #setupAnim} instead of via a mixin —
 * same shape (reset every part, apply the clip, return before any of SEM's own animation logic
 * runs) as the mixin uses for the non-Commander case.
 */
public class PmcCommanderModel<T extends Entity> extends HierarchicalModel<T> implements IArmorBoneProvider {

    private final ModelPart fakeRoot;
    private final ModelPart unit;
    private final ModelPart head;
    private final ModelPart body;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;

    public PmcCommanderModel(ModelPart root) {
        this.fakeRoot = root.getChild("fakeRoot");
        this.unit = this.fakeRoot.getChild("unit");
        this.head = this.unit.getChild("head");
        this.body = this.unit.getChild("body");
        this.rightArm = this.unit.getChild("rightArm");
        this.leftArm = this.unit.getChild("leftArm");
        this.rightLeg = this.unit.getChild("rightLeg");
        this.leftLeg = this.unit.getChild("leftLeg");
    }

    @Override
    public void translateToBody(PoseStack poseStack) {
        this.fakeRoot.translateAndRotate(poseStack);
        this.unit.translateAndRotate(poseStack);
    }

    public static LayerDefinition createBodyLayer() {
        return UnitModelDefinitions.createBaseUnitBodyLayer();
    }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        this.root().getAllParts().forEach(ModelPart::resetPose);

        if (entity instanceof IPmcDowned downed && downed.sewv$isDownedSynced()) {
            DownedUnitPose.applyToUnit(this.fakeRoot, ageInTicks);
            return;
        }

        if (!(entity instanceof AbstractUnit unitEntity)) {
            return;
        }

        if (unitEntity.getAnimationManager() == null) {
            unitEntity.setAnimationManager(UnitAnimationConfig.create(unitEntity, this.head, this.rightArm, this.leftArm));
        }

        unitEntity.getAnimationManager().update(unitEntity, unitEntity.tickCount);

        if (unitEntity.deathAnimationState.isStarted()) {
            boolean playBack = unitEntity.getEntityData().get(AbstractUnit.BACK_DEATH);
            this.animate(unitEntity.deathAnimationState,
                    playBack ? ModAnimationsDefinitions.UNIT_DEATH_BACK : ModAnimationsDefinitions.UNIT_DEATH,
                    ageInTicks, 1.0f);
            return;
        }

        if (unitEntity.hurtAnimationState.isStarted()) {
            int variantIndex = unitEntity.currentHurtVariant;
            int safeVariantIndex = (variantIndex >= 0 && variantIndex < ModAnimationsDefinitions.UNIT_HURT_VARIANTS.length)
                    ? variantIndex : 0;
            this.animate(unitEntity.hurtAnimationState,
                    ModAnimationsDefinitions.UNIT_HURT_VARIANTS[safeVariantIndex], ageInTicks, 2.0f);
            return;
        }

        if (unitEntity.walkAnimationState.isStarted()) {
            this.animate(unitEntity.walkAnimationState, ModAnimationsDefinitions.UNIT_WALK, ageInTicks, 1.0f);
        } else if (unitEntity.idleAnimationState.isStarted()) {
            this.animate(unitEntity.idleAnimationState, ModAnimationsDefinitions.UNIT_IDLE, ageInTicks, 1.0f);
        }

        unitEntity.getAnimationManager().applyProceduralLayers(this.root(), unitEntity, ageInTicks);
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay,
                               float red, float green, float blue, float alpha) {
        fakeRoot.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
    }

    @Override
    public ModelPart root() {
        return this.fakeRoot;
    }

    @Override
    public ModelPart getUnit() {
        return this.unit;
    }

    @Override
    public ModelPart getHead() {
        return head;
    }

    @Override
    public ModelPart getBody() {
        return body;
    }

    @Override
    public ModelPart getRightArm() {
        return rightArm;
    }

    @Override
    public ModelPart getLeftArm() {
        return leftArm;
    }

    @Override
    public ModelPart getRightLeg() {
        return rightLeg;
    }

    @Override
    public ModelPart getLeftLeg() {
        return leftLeg;
    }
}
