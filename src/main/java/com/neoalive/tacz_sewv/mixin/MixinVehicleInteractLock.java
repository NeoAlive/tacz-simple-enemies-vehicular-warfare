package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.crew.VehicleAccess.Verdict;
import com.neoalive.tacz_sewv.crew.VehicleAccessLive;
import com.neoalive.tacz_sewv.entity.ai.support.EnemyCrewInteractGuard;
import com.neoalive.tacz_sewv.util.VehicleEngineLoot;

/**
 * Defense-in-depth for the hull interact lock on SBW's {@code VehicleEntity.interact} and
 * {@code openMenu}. Addon short-circuits (FCP shift-hold, etc.) are caught by
 * {@link EnemyCrewInteractGuard}. The decision is {@link VehicleAccessLive}.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleInteractLock {

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true, remap = true)
    private void tacz_sewv$blockEnemyVehicleInteract(
            Player player, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir) {

        VehicleEntity self = (VehicleEntity) (Object) this;
        Verdict verdict = VehicleAccessLive.verdict(player, self);
        if (verdict != Verdict.ALLOW) {
            VehicleAccessLive.tell(player, verdict);
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        VehicleEngineLoot.tryApplyOnUnlock(self);
    }

    /** A subclass that opens the container without {@code super.interact} still has to pass. */
    @Inject(method = "openMenu", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$blockOpenMenu(Player player, CallbackInfo ci) {
        VehicleEntity self = (VehicleEntity) (Object) this;
        Verdict verdict = VehicleAccessLive.verdict(player, self);
        if (verdict == Verdict.ALLOW) return;
        VehicleAccessLive.tell(player, verdict);
        ci.cancel();
    }
}
