package com.neoalive.tacz_sewv.mixin.client;

import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.client.ru_unit.RUunitModel;
import net.nekoyuni.SimpleEnemyMod.entity.client.us_unit.USunitModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.bridge.IMedicCaptured;
import com.neoalive.tacz_sewv.client.DownedUnitPose;

/**
 * Poses a captured RU/US medic with the shared downed clip
 * ({@code assets/tacz_sewv/animations/downed.animation.json}, loaded by {@link DownedUnitPose}) —
 * one authored asset serving both incapacitated states. A bespoke captured kneel was tried first
 * ({@code captured.animation.json} + {@code CapturedUnitPose}) and retired: the Bedrock round-trip
 * through the modelling tool mangled that asset repeatedly, while the downed clip has rendered
 * faithfully since introduction, and an incapacitated silhouette serves both states.
 *
 * <p>Same shape as {@link MixinPmcDownedPose} — HEAD, cancellable, full-body override — with one
 * difference in scope: {@code RUunitModel}/{@code USunitModel} only, never
 * {@code PmcUnitModel}/{@code PmcCommanderModel}, because {@link IMedicCaptured} is implemented
 * only by {@code RuMedicEntity}/{@code UsMedicEntity} (never a mixin target on any PMC class — see
 * that interface's doc for why the PMC exclusion is airtight); a check on the PMC models would be
 * unreachable dead code.
 *
 * <p><b>HEAD, cancellable, for the identical reason {@code MixinPmcDownedPose} is</b>: SEM's own
 * {@code setupAnim} has early returns for {@code deathAnimationState}/{@code hurtAnimationState}
 * that a TAIL injection would never reach, and {@code applyProceduralLayers} touches the real
 * {@code root()} (not just {@code unit}) that a TAIL-only reset would miss. Cancelling at HEAD means
 * none of SEM's own animation ever runs while captured — the bind-pose reset is reproduced here
 * first, then the clip applies on top of a guaranteed-clean rig. The clip poses every real bone of
 * this rig too (its bare {@code root} entry stays deliberately unmapped — see {@link DownedUnitPose}),
 * so no bone falls through to the rifle-stance bind.
 */
@Mixin({RUunitModel.class, USunitModel.class})
public abstract class MixinRuUsCapturedPose {

    @Inject(method = "setupAnim", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$capturedPose(Entity entity, float limbSwing, float limbSwingAmount,
                                        float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (!(entity instanceof IMedicCaptured captured)) return;
        if (!captured.sewv$isCapturedSynced()) return;
        ModelPart root = ((HierarchicalModel<?>) (Object) this).root();
        root.getAllParts().forEach(ModelPart::resetPose);
        DownedUnitPose.applyToUnit(root, ageInTicks);
        ci.cancel();
    }
}
