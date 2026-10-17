package com.neoalive.tacz_sewv.mixin.client;

import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.client.pmc_unit.PmcUnitModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.bridge.IPmcDowned;
import com.neoalive.tacz_sewv.client.DownedUnitPose;

/**
 * Applies the authored downed pose ({@code assets/tacz_sewv/animations/downed.animation.json},
 * loaded by {@link DownedUnitPose}) to a downed PMC's every bone — head and arms included, unlike
 * {@link MixinUnitModelSeatPose}'s sandbag pose, which leaves those live for aim/look. A downed unit
 * has nothing left to aim or look with, so the full-body override is correct here.
 *
 * <p>PMC-only ({@link PmcUnitModel} alone, not the shared RU/US/Commander seat-pose mixin): only a
 * {@code PmcUnitEntity} can ever be downed ({@link IPmcDowned} — RU/US just die), so this needs no
 * unit-type check at all, only {@link IPmcDowned#sewv$isDownedSynced} — the client-visible mirror of
 * the durable state; see that interface's doc for why it is a separate flag from
 * {@code sewv$isDowned}.
 *
 * <p><b>Injected at HEAD, cancellable — blocks SEM's own animation entirely rather than posing on
 * top of it.</b> An earlier version injected at TAIL and relied on {@code resetPose()} + override to
 * clean up after idle/walk/procedural layers had already run, which is fragile for two reasons: (1)
 * {@code @At("TAIL")} only fires before the method's <em>last</em> return — SEM's
 * {@code setupAnim} has early returns for {@code deathAnimationState}/{@code hurtAnimationState},
 * which TAIL never reaches at all, so a downed unit taking another hit would flash the hurt
 * animation with no override that frame; (2) {@code applyProceduralLayers} is passed the model's
 * real {@code root()} ({@code fakeRoot}, not {@code unit}), so any full-rig sway/breathing effect it
 * applies to {@code fakeRoot} directly was never reset by the old code at all, since only
 * {@code unit} and its children were touched. Cancelling at HEAD means none of that ever runs while
 * downed — SEM's own bind-pose reset is reproduced here instead, then the clip is applied on top of
 * a guaranteed-clean rig.
 */
@Mixin(PmcUnitModel.class)
public abstract class MixinPmcDownedPose {

    @Inject(method = "setupAnim", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$downedPose(Entity entity, float limbSwing, float limbSwingAmount,
                                      float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (!(entity instanceof IPmcDowned downed) || !downed.sewv$isDownedSynced()) return;
        ModelPart root = ((HierarchicalModel<?>) (Object) this).root();
        root.getAllParts().forEach(ModelPart::resetPose);
        DownedUnitPose.applyToUnit(root, ageInTicks);
        ci.cancel();
    }
}
