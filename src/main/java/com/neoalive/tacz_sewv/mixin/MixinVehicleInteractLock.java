package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.entity.ai.support.EnemyCrewInteractGuard;
import com.neoalive.tacz_sewv.util.VehicleEngineLoot;

/**
 * Defense-in-depth for the enemy-crew interact lock on SBW's {@code VehicleEntity.interact}.
 * Addon short-circuits (FCP shift-hold, etc.) are caught by {@link EnemyCrewInteractGuard}.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleInteractLock {

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true, remap = true)
    private void tacz_sewv$blockEnemyVehicleInteract(
            Player player, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir) {

        VehicleEntity self = (VehicleEntity) (Object) this;

        if (EnemyCrewInteractGuard.hasEnemyCrew(self)) {
            if (!player.level().isClientSide()) {
                player.displayClientMessage(
                        Component.translatable("message.tacz_sewv.interact.enemy_crew")
                                .withStyle(ChatFormatting.GRAY), true);
            }
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        VehicleEngineLoot.tryApplyOnUnlock(self);
    }
}
